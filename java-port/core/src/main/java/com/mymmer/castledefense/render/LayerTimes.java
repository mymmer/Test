package com.mymmer.castledefense.render;

/**
 * Optional per-layer timing, for finding where a frame actually goes.
 *
 * <p>Off unless {@code -Dcastledefense.layerTimes=true}, and when off every
 * method is an immediately-returning constant that the JIT removes — so the
 * shipped game pays nothing for the instrumentation being present.
 *
 * <p>Deliberately six counters and a print, not a profiler. Phase 12 needs to
 * know which layer costs what; it does not need a framework to find out.
 */
public final class LayerTimes {

    public static final String[] NAMES = {
        "background", "outpost", "castle+spikes", "towers+barricade",
        "enemies", "items/zones/projectiles", "effects+weather",
    };

    private static final boolean ON =
            Boolean.getBoolean("castledefense.layerTimes");

    private static final long[] TOTAL = new long[NAMES.length];
    private static long frames;

    private LayerTimes() {
    }

    public static long now() {
        return ON ? System.nanoTime() : 0L;
    }

    /** Charges the elapsed time to a layer and returns the new mark. */
    public static long mark(int layer, long since) {
        if (!ON) {
            return 0L;
        }
        long now = System.nanoTime();
        TOTAL[layer] += now - since;
        if (layer == NAMES.length - 1) {
            frames++;
        }
        return now;
    }

    public static boolean enabled() {
        return ON;
    }

    // --- shape-operation counting, to stop a hotspot hunt being guesswork ----

    private static final long[] OPS = new long[NAMES.length];
    private static int layer;
    private static long glCalls;

    /** Which layer subsequent shape work belongs to. */
    public static void layer(int index) {
        if (ON) {
            layer = Math.max(0, Math.min(NAMES.length - 1, index));
        }
    }

    /** One primitive submitted. */
    public static void op() {
        if (ON) {
            OPS[layer]++;
        }
    }

    public static void op(int n) {
        if (ON) {
            OPS[layer] += n;
        }
    }

    /** One GL state call, which crosses into the driver. */
    public static void glCall() {
        if (ON) {
            glCalls++;
        }
    }

    public static String ops() {
        if (!ON || frames == 0) {
            return "";
        }
        StringBuilder out = new StringBuilder("[ops] per frame:");
        for (int i = 0; i < NAMES.length; i++) {
            out.append(String.format(java.util.Locale.ROOT, "%n    %-24s %8.0f",
                    NAMES[i], OPS[i] / (double) frames));
        }
        out.append(String.format(java.util.Locale.ROOT, "%n    %-24s %8.0f",
                "GL state calls", glCalls / (double) frames));
        return out.toString();
    }

    /** A per-frame breakdown in microseconds, or an empty string when off. */
    public static String report() {
        if (!ON || frames == 0) {
            return "";
        }
        StringBuilder out = new StringBuilder("[layers] over ")
                .append(frames).append(" frames:");
        long sum = 0;
        for (long t : TOTAL) {
            sum += t;
        }
        for (int i = 0; i < NAMES.length; i++) {
            out.append(String.format(java.util.Locale.ROOT, "%n    %-24s %7.1f us  %5.1f%%",
                    NAMES[i], TOTAL[i] / 1000.0 / frames,
                    sum == 0 ? 0.0 : TOTAL[i] * 100.0 / sum));
        }
        out.append(String.format(java.util.Locale.ROOT,
                "%n    %-24s %7.1f us", "shape pass total", sum / 1000.0 / frames));
        return out.toString();
    }

    /**
     * Clears both accumulators for the next window.
     *
     * <p>{@code OPS} was left out of this until Phase 13, which did not matter
     * while the only caller printed once at exit -- but the device harness
     * reports every 300 frames, so the counts grew window on window (1873, then
     * 3751, then 5629) and read as a rising cost that was not happening. A
     * counter that is reported repeatedly has to be reset with what it is
     * reported beside.
     */
    public static void reset() {
        java.util.Arrays.fill(TOTAL, 0L);
        java.util.Arrays.fill(OPS, 0L);
        glCalls = 0L;
        frames = 0;
    }
}
