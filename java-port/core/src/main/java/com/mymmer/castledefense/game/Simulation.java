package com.mymmer.castledefense.game;

import com.mymmer.castledefense.config.GameConfig;

/**
 * The fixed-step clock. Everything in the game that ages, ages through here.
 *
 * <p>Rendering runs at whatever rate the device offers — 60, 90, 120, 144 Hz —
 * and the simulation runs at exactly 1/60 s per step regardless:
 *
 * <pre>
 *   accumulator += min(frameDelta, MAX_FRAME_DELTA)
 *   while (accumulator &gt;= DT &amp;&amp; steps &lt; MAX_STEPS) { step(DT); accumulator -= DT }
 *   if (steps == MAX_STEPS) accumulator = 0        // no spiral of death
 * </pre>
 *
 * <h2>Simulation time is the only gameplay clock</h2>
 *
 * <p>{@link #timeSeconds()} is {@code stepCount * DT} — derived from steps, never
 * from wall-clock. Two consequences the rest of the codebase depends on:
 *
 * <ul>
 *   <li>Two steps advance gameplay by exactly {@code 2 * DT}, always.</li>
 *   <li>When {@code MAX_STEPS} is hit and excess wall-clock time is dropped,
 *       <b>gameplay time is dropped with it</b>. Nothing anywhere may quietly
 *       catch a gameplay clock back up using real elapsed time.</li>
 * </ul>
 *
 * <p>Counting steps rather than summing floats keeps long runs stable: an hour
 * of Endless is 216,000 steps, and {@code 216000 * DT} carries no accumulated
 * rounding error the way {@code time += 0.016666f} would.
 *
 * <p>One honest caveat about precision. {@code DT} is {@code 1f/60f}, whose
 * float value is very slightly <em>larger</em> than a true sixtieth
 * (0.016666667536… against 0.016666666666…). Sixty steps therefore consume about
 * 5.2e-8 s more than one real second, so a wall-clock second occasionally yields
 * 59 steps rather than 60, and {@link #timeSeconds()} reads about 3 µs high per
 * simulated minute. This is deliberate: the reported clock is
 * {@code stepCount * DT}, which is exactly the time the physics actually
 * integrated, and agreeing with the systems matters more than agreeing with a
 * stopwatch. Nothing in the game compares gameplay time to wall-clock.
 *
 * <h2>This is an intentional behavioural change</h2>
 *
 * <p>The Python game steps a <em>variable</em> delta capped at 50 ms. At a steady
 * 60 fps the two are extremely close, but they are not identical, and this port
 * does not claim they are. Threshold and boundary cases — an attack timer
 * crossing zero, a projectile's position on the frame it lands, fall-damage and
 * bounce cut-offs, cooldown expiry, spawn and boss schedules, the wave-clear
 * delay — can land on a different side of a comparison. Those are exactly what
 * the later parity tests must target. <b>No gameplay constant has been altered
 * to compensate</b>, and none will be without a measured discrepancy and an
 * explicit decision about it.
 *
 * <h2>Ownership</h2>
 * <ul>
 *   <li><b>Created by</b> {@code CastleDefenseGame}, once.</li>
 *   <li><b>Owned by</b> {@code CastleDefenseGame}.</li>
 *   <li><b>Mutated by</b> {@code CastleDefenseGame} only, from the render
 *       thread: {@link #advance}, {@link #resetAccumulator}, {@link #reset}.</li>
 *   <li><b>Disposed by</b> nobody — it holds no resources.</li>
 * </ul>
 */
public final class Simulation {

    /** One simulation step, in seconds. */
    public static final float DT = GameConfig.SIMULATION_STEP;

    /** Catch-up steps allowed in a single rendered frame. */
    public static final int MAX_STEPS = GameConfig.MAX_SIMULATION_STEPS_PER_FRAME;

    /** Longest frame delta that may enter the accumulator, in seconds. */
    public static final float MAX_FRAME_DELTA = GameConfig.MAX_FRAME_DELTA;

    /** What one step does. Implemented by {@link GameWorld}. */
    public interface Stepper {
        void step(float dt);
    }

    private final Stepper stepper;

    private double accumulator;
    private long stepCount;
    private int lastStepsRun;
    private long droppedStepEvents;
    private long clampedFrames;

    public Simulation(Stepper stepper) {
        if (stepper == null) {
            throw new IllegalArgumentException("stepper must not be null");
        }
        this.stepper = stepper;
    }

    /**
     * Feeds one rendered frame's elapsed time to the clock.
     *
     * @param frameDelta seconds since the previous frame, from the platform
     * @return how many simulation steps ran this call (0..{@link #MAX_STEPS})
     */
    public int advance(float frameDelta) {
        if (Float.isNaN(frameDelta) || frameDelta < 0f) {
            frameDelta = 0f;                // a backend hiccup is not time travel
        }
        if (frameDelta > MAX_FRAME_DELTA) {
            frameDelta = MAX_FRAME_DELTA;   // a stall is dropped, not replayed
            clampedFrames++;
        }
        //  double, not float: an hour at 144 Hz is half a million additions, and
        //  float would accumulate visible drift over that. The deltas arrive as
        //  floats from the platform; only the running total needs the headroom.
        accumulator += frameDelta;

        int steps = 0;
        while (accumulator >= DT && steps < MAX_STEPS) {
            stepper.step(DT);
            accumulator -= DT;
            stepCount++;
            steps++;
        }
        if (steps == MAX_STEPS && accumulator >= DT) {
            // Still behind after the budget: throw the rest away rather than
            // trying to catch up, which would only make the next frame worse.
            accumulator = 0d;
            droppedStepEvents++;
        }
        lastStepsRun = steps;
        return steps;
    }

    /**
     * Drops the partial step held in the accumulator.
     *
     * <p>Called on resume. An Android pause can last hours and the first frame
     * after it reports a huge delta; the clamp handles that, but any fraction of
     * a step left over from before the pause is stale and is discarded here.
     */
    public void resetAccumulator() {
        accumulator = 0d;
    }

    /** Full reset: clock back to zero. Used when a new run begins. */
    public void reset() {
        accumulator = 0d;
        stepCount = 0;
        lastStepsRun = 0;
    }

    /**
     * Gameplay time, in seconds: exactly {@code stepCount * DT}.
     *
     * <p>{@code double} because a long Endless run accumulates hundreds of
     * thousands of steps and float would start losing sub-frame precision.
     */
    public double timeSeconds() {
        return stepCount * (double) DT;
    }

    /** Steps run since the last {@link #reset()}. */
    public long stepCount() {
        return stepCount;
    }

    /** Steps run in the most recent {@link #advance} call. */
    public int lastStepsRun() {
        return lastStepsRun;
    }

    /**
     * How far into the next step we are, 0..1 — for render interpolation.
     *
     * <p>Guaranteed to stay inside [0, 1): the accumulator always holds less
     * than one step's worth of time after {@link #advance} returns.
     */
    public float alpha() {
        float a = (float) (accumulator / DT);
        if (a < 0f) {
            return 0f;
        }
        return a >= 1f ? 0.999999f : a;
    }

    /** Seconds currently held in the accumulator. Diagnostics only. */
    public float accumulatorSeconds() {
        return (float) accumulator;
    }

    /** How often the step budget was exhausted and time was dropped. */
    public long droppedStepEvents() {
        return droppedStepEvents;
    }

    /** How often a frame delta was clamped by {@link #MAX_FRAME_DELTA}. */
    public long clampedFrames() {
        return clampedFrames;
    }
}
