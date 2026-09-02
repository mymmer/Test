package com.mymmer.castledefense.input;

import com.mymmer.castledefense.config.GameConfig;

/**
 * Release velocity for a throw, independent of how fast the device reports
 * touches.
 *
 * <h2>What the Python does</h2>
 *
 * <p>{@code Game.mouse_velocity} keeps a {@code deque(maxlen=12)} of
 * {@code (time, x, y)}, walks it for the oldest sample within 90 ms of the
 * newest, and divides the displacement by that interval, clamping the result to
 * ±2600 px/s.
 *
 * <h2>Why this is not a straight translation</h2>
 *
 * <p>A fixed <em>count</em> of samples is a fixed <em>duration</em> only if the
 * sampling rate is fixed. It is not: phones report touches anywhere from 60 Hz
 * to 240 Hz+, and twelve samples at 240 Hz span 50 ms, not 90. The same flick
 * would therefore produce a materially weaker throw on a better phone — the
 * opposite of what the player expects.
 *
 * <p>So history is bounded by <b>time</b>, not count: enough samples are kept to
 * always cover the lookback window with margin. The 90 ms window and the ±2600
 * clamp are unchanged, and samples are stored in <b>world units</b>, so the
 * clamp means the same thing on every screen.
 *
 * <p>Allocation-free after construction: a fixed ring buffer of primitives, no
 * per-sample objects.
 *
 * <h2>Ownership</h2>
 * One tracker per pointer, created and owned by {@code GameInput}.
 */
public final class TouchVelocityTracker {

    /** How far back the release looks, in seconds. Python: 0.09. */
    public static final float LOOKBACK_SECONDS = GameConfig.THROW_SAMPLE_WINDOW;

    /** Speed cap in world units per second. Python: 2600. */
    public static final float SPEED_CLAMP = GameConfig.THROW_SPEED_CLAMP;

    /**
     * History kept, in seconds — comfortably more than the lookback so the
     * window is always covered even if a couple of events are dropped.
     */
    public static final float HISTORY_SECONDS = 0.20f;

    /**
     * Ring capacity. Sized for a 480 Hz reporting rate over the history window,
     * which is well beyond any shipping phone; the buffer is time-trimmed, so
     * this is only an upper bound and costs 96 floats.
     */
    private static final int CAPACITY = 96;

    private final float[] times = new float[CAPACITY];
    private final float[] xs = new float[CAPACITY];
    private final float[] ys = new float[CAPACITY];

    private int head;       // index of the next write
    private int count;

    /** Drops all history. Called when a pointer goes down. */
    public void reset() {
        head = 0;
        count = 0;
    }

    /**
     * Records a position.
     *
     * @param timeSeconds monotonic time; simulation time is the right clock
     * @param worldX      world-space x, never a screen pixel
     * @param worldY      world-space y
     */
    public void sample(float timeSeconds, float worldX, float worldY) {
        times[head] = timeSeconds;
        xs[head] = worldX;
        ys[head] = worldY;
        head = (head + 1) % CAPACITY;
        if (count < CAPACITY) {
            count++;
        }
        trim(timeSeconds);
    }

    /** Forgets samples older than the history window. */
    private void trim(float now) {
        while (count > 2) {
            int oldest = oldestIndex();
            if (now - times[oldest] <= HISTORY_SECONDS) {
                return;
            }
            count--;                    // the ring walks forward; nothing moves
        }
    }

    private int oldestIndex() {
        return ((head - count) % CAPACITY + CAPACITY) % CAPACITY;
    }

    private int newestIndex() {
        return ((head - 1) % CAPACITY + CAPACITY) % CAPACITY;
    }

    public int sampleCount() {
        return count;
    }

    /** Seconds spanned by the retained history. */
    public float historySpan() {
        if (count < 2) {
            return 0f;
        }
        return times[newestIndex()] - times[oldestIndex()];
    }

    /**
     * Velocity to throw with, in world units per second.
     *
     * <p>Mirrors the Python: take the newest sample, walk from the oldest
     * forward to the first sample within {@link #LOOKBACK_SECONDS} of it, and
     * divide the displacement between them by their time difference. With fewer
     * than two samples the answer is zero — a tap is not a throw.
     *
     * @param out a two-element array receiving {x, y}; never allocated here
     */
    public void velocity(float[] out) {
        out[0] = 0f;
        out[1] = 0f;
        if (count < 2) {
            return;
        }
        int newest = newestIndex();
        float t1 = times[newest];

        int oldest = oldestIndex();
        int chosen = oldest;
        for (int i = 0; i < count; i++) {
            int idx = (oldest + i) % CAPACITY;
            if (t1 - times[idx] <= LOOKBACK_SECONDS) {
                chosen = idx;
                break;
            }
        }
        if (chosen == newest) {
            return;             // every sample is the same instant
        }
        float dt = Math.max(1e-3f, t1 - times[chosen]);
        float vx = (xs[newest] - xs[chosen]) / dt;
        float vy = (ys[newest] - ys[chosen]) / dt;
        out[0] = clampSpeed(vx);
        out[1] = clampSpeed(vy);
    }

    private static float clampSpeed(float v) {
        if (v > SPEED_CLAMP) {
            return SPEED_CLAMP;
        }
        return v < -SPEED_CLAMP ? -SPEED_CLAMP : v;
    }
}
