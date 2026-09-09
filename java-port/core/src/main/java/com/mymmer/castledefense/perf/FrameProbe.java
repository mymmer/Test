package com.mymmer.castledefense.perf;

/**
 * Frame and simulation timings, reported periodically to a sink.
 *
 * <h2>Why this exists when {@code --bench} already does it</h2>
 *
 * <p>Phase 12's {@code --bench} lives in the desktop launcher: it subclasses the
 * game, runs a fixed number of frames and prints percentiles as the process
 * exits. None of that is available on a phone. There is no command line, the
 * process does not exit when the measurement is done, and stdout goes nowhere a
 * developer can read it.
 *
 * <p>So Phase 13 needs a probe that reports <b>while the game is running</b>, in
 * rolling windows, to whatever the platform uses for logging — logcat on
 * Android. It measures the same two things {@code --bench} and
 * {@code :core:benchmark} measure, so the three sets of numbers are comparable:
 * the whole frame, and the simulation inside it.
 *
 * <h2>Cost when off</h2>
 *
 * <p>None. The game holds a null reference until something enables it, so a
 * shipped build pays one null check per frame and allocates nothing.
 *
 * <h2>What it deliberately does not do</h2>
 *
 * <p>It does not touch gameplay, consume RNG or read a wall clock that anything
 * simulated can see. {@code System.nanoTime} here is a stopwatch around work
 * that has already been decided by the fixed-step accumulator — the same
 * relationship {@code Benchmark} has to {@code RunWorld.step}.
 */
public final class FrameProbe {

    /** Where a finished window's line goes. */
    public interface Sink {
        void line(String text);
    }

    /**
     * Extra platform detail appended to each line.
     *
     * <p>Memory, GC counts and thermal status are Android APIs, and core cannot
     * see them. The launcher supplies them through this rather than core
     * growing a dependency it has no other use for.
     */
    public interface DeviceStats {
        String stats();
    }

    private final int window;
    private final double[] frameMicros;
    private final double[] simMicros;
    /** Wall-clock gap between successive frame starts: the delivered rate. */
    private final double[] intervalMicros;
    private final Sink sink;

    private int n;
    private int windowSteps;
    private long lastDropped;
    private long lastClamped;
    private int windowIndex;

    private String label = "play";
    private DeviceStats deviceStats;
    /**
     * What the world was doing while it was timed.
     *
     * <p>Phase 12's headless benchmark once reported a 0.1 us median for every
     * scenario because a crowd had flattened the castle and it was timing a
     * step that returns immediately in GAMEOVER. The fix there was to fail
     * loudly. Here the frame keeps drawing whatever happened -- the game-over
     * screen still paints the whole scene -- so a dead world produces perfectly
     * plausible frame times that mean nothing about play. Printing the state
     * beside the numbers is what makes that visible instead of assumed.
     */
    private DeviceStats sceneStats;

    /** Timestamps for the frame currently being measured. */
    private long frameStart;
    private long prevFrameStart;
    private long simNanos;

    public FrameProbe(int window, Sink sink) {
        this.window = Math.max(30, window);
        this.frameMicros = new double[this.window];
        this.simMicros = new double[this.window];
        this.intervalMicros = new double[this.window];
        this.sink = sink;
    }

    /** A short name for the scenario being measured, echoed in every line. */
    public void setLabel(String label) {
        this.label = label == null ? "play" : label;
    }

    public void setDeviceStats(DeviceStats stats) {
        this.deviceStats = stats;
    }

    public void setSceneStats(DeviceStats stats) {
        this.sceneStats = stats;
    }

    public String label() {
        return label;
    }

    public int windowsReported() {
        return windowIndex;
    }

    // ------------------------------------------------------------------
    //  Per-frame hooks
    // ------------------------------------------------------------------

    /**
     * Starts a frame.
     *
     * <p>This also closes the <em>interval</em> since the previous frame began,
     * which is the delivered frame rate and is not the same number as the work
     * done inside a frame. Under vsync a phone may spend 3 ms rendering and
     * still present every 16.7 ms; reporting the 3 ms as "330 fps" would be
     * true of the CPU and useless as a statement about the game. Both are
     * measured, and both are reported.
     */
    public void frameBegin() {
        long now = System.nanoTime();
        if (prevFrameStart != 0L && n < intervalMicros.length) {
            intervalMicros[n] = (now - prevFrameStart) / 1000.0;
        }
        prevFrameStart = now;
        frameStart = now;
        simNanos = 0L;
    }

    /** Called around the fixed-step loop, which may run zero or more times. */
    public void simBegin() {
        simNanos -= System.nanoTime();
    }

    public void simEnd() {
        simNanos += System.nanoTime();
    }

    /**
     * Closes the frame and, once a window is full, reports it.
     *
     * <p>The dropped and clamped counters are read as running totals and
     * differenced, because they belong to the simulation and outlive any one
     * window. A non-zero {@code dropped} is the number that matters most on a
     * phone: it means the device could not keep up and gameplay time was
     * discarded rather than replayed.
     */
    public void frameEnd(int steps, long droppedTotal, long clampedTotal) {
        if (n < frameMicros.length) {
            frameMicros[n] = (System.nanoTime() - frameStart) / 1000.0;
            simMicros[n] = simNanos / 1000.0;
            n++;
        }
        windowSteps += steps;
        if (n < frameMicros.length) {
            return;
        }
        report(droppedTotal - lastDropped, clampedTotal - lastClamped);
        lastDropped = droppedTotal;
        lastClamped = clampedTotal;
        n = 0;
        windowSteps = 0;
        java.util.Arrays.fill(intervalMicros, 0.0);
    }

    private void report(long dropped, long clamped) {
        double[] f = new double[n];
        double[] s = new double[n];
        System.arraycopy(frameMicros, 0, f, 0, n);
        System.arraycopy(simMicros, 0, s, 0, n);
        //  The first interval of the very first window has no predecessor and
        //  is left at zero; it is dropped rather than averaged in as an
        //  impossibly fast frame.
        int in = 0;
        double[] iv = new double[n];
        for (int i = 0; i < n; i++) {
            if (intervalMicros[i] > 0.0) {
                iv[in++] = intervalMicros[i];
            }
        }
        double total = 0;
        for (int i = 0; i < in; i++) {
            total += iv[i];
        }
        java.util.Arrays.sort(f);
        java.util.Arrays.sort(s);
        java.util.Arrays.sort(iv, 0, in);
        double[] ivs = new double[in];
        System.arraycopy(iv, 0, ivs, 0, in);
        windowIndex++;

        //  Frame times in milliseconds because that is the unit a 60 Hz budget
        //  is discussed in; simulation in microseconds because that is the unit
        //  it lands in, and the two are not close enough to share one.
        StringBuilder b = new StringBuilder(220);
        b.append(String.format(java.util.Locale.ROOT,
                "[perf] w=%d label=%s frames=%d fps=%.1f "
                        + "frame_p50=%.2f p95=%.2f p99=%.2f max=%.2fms "
                        + "cpu_p50=%.2f p95=%.2f p99=%.2fms "
                        + "sim_p50=%.0f p95=%.0f p99=%.0fus "
                        + "steps=%.2f/frame dropped=%d clamped=%d",
                windowIndex, label, n,
                in == 0 ? 0.0 : in / (total / 1_000_000.0),
                pct(ivs, 0.50) / 1000.0, pct(ivs, 0.95) / 1000.0,
                pct(ivs, 0.99) / 1000.0,
                in == 0 ? 0.0 : ivs[in - 1] / 1000.0,
                pct(f, 0.50) / 1000.0, pct(f, 0.95) / 1000.0,
                pct(f, 0.99) / 1000.0,
                pct(s, 0.50), pct(s, 0.95), pct(s, 0.99),
                windowSteps / (double) n, dropped, clamped));
        if (sceneStats != null) {
            String scene = sceneStats.stats();
            if (scene != null && !scene.isEmpty()) {
                b.append(' ').append(scene);
            }
        }
        if (deviceStats != null) {
            String extra = deviceStats.stats();
            if (extra != null && !extra.isEmpty()) {
                b.append(' ').append(extra);
            }
        }
        if (sink != null) {
            sink.line(b.toString());
        }
    }

    private static double pct(double[] sorted, double q) {
        if (sorted.length == 0) {
            return 0;
        }
        return sorted[(int) Math.min(sorted.length - 1,
                Math.round(q * (sorted.length - 1)))];
    }
}
