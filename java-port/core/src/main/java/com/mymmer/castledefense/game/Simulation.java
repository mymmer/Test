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
 * <h2>Two representations of one step, and why</h2>
 *
 * <p>{@code 1f/60f} is <em>not</em> a sixtieth: as a float it is 0.016666668…,
 * slightly larger than 0.016666666…. Anything that accumulates it drifts, so the
 * step exists twice and the two are used for strictly different jobs:
 *
 * <table>
 *   <tr><th>Constant</th><th>Type</th><th>Used for</th></tr>
 *   <tr><td>{@link #FIXED_DT}</td><td>{@code double}</td>
 *       <td><b>Canonical timing.</b> The accumulator, the step comparison, and
 *       {@link #timeSeconds()}. Never approximate.</td></tr>
 *   <tr><td>{@link #DT}</td><td>{@code float}</td>
 *       <td><b>Gameplay arithmetic.</b> The value handed to {@code step(dt)}:
 *       positions, velocities, per-step decay. Multiplied by, never summed.</td></tr>
 * </table>
 *
 * <p>So the authoritative clock is {@code stepCount * FIXED_DT} — an exact
 * integer times an exact double — and not a running float total. 60 steps are
 * 1.0 s, 3600 steps are 60.0 s and 216,000 steps are 3600.0 s, to double
 * precision, however long the session runs. Trace step ids are the integer
 * {@code stepCount} itself, so they are exact by construction.
 *
 * <p>This does <b>not</b> mean gameplay must become integer ticks. A projectile
 * still integrates in floats with {@code DT}; only the clock that says <em>when</em>
 * is canonical. Later long-lived clocks (Endless timetable, boss phases) should
 * read {@link #timeSeconds()} or a step count rather than summing their own float.
 *
 * <p>One thing this cannot fix: a frame delta arriving from the platform is a
 * float and is approximate. Feeding {@code 1/144f} repeatedly may yield 59 or 60
 * steps in a nominal second, because the <em>input</em> is imprecise, not the
 * clock. Tests distinguish the two: exact assertions where the port controls the
 * input (step counts), tolerance where the platform supplies it (frame deltas).
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

    /**
     * One simulation step in seconds, canonical. The accumulator and the
     * simulation clock use this and nothing else.
     */
    public static final double FIXED_DT = GameConfig.SIMULATION_STEP;

    /**
     * The same step as a float, for gameplay maths.
     *
     * <p>This is what {@code step(dt)} receives. It is safe to multiply by and
     * unsafe to accumulate — accumulate {@link #FIXED_DT} or count steps.
     */
    public static final float DT = GameConfig.SIMULATION_STEP_F;

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
        //  FIXED_DT here, DT in the callback: the clock is exact, the physics
        //  is float.  Subtracting the float step instead would put its
        //  0.016666668 approximation straight back into the canonical timing.
        while (accumulator >= FIXED_DT && steps < MAX_STEPS) {
            stepper.step(DT);
            accumulator -= FIXED_DT;
            stepCount++;
            steps++;
        }
        if (steps == MAX_STEPS && accumulator >= FIXED_DT) {
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
     * Canonical simulation time, in seconds: exactly {@code stepCount * FIXED_DT}.
     *
     * <p>An exact integer times an exact double, computed fresh each call. It is
     * never a running total, so it cannot drift no matter how long the session
     * lasts: 216,000 steps read 3600.0 s.
     */
    public double timeSeconds() {
        return stepCount * FIXED_DT;
    }

    /**
     * Canonical seconds for an arbitrary step count.
     *
     * <p>For subsystems that keep their own step index — a boss phase timer, the
     * Endless timetable — so they convert the same way the clock does instead of
     * summing a float of their own.
     */
    public static double secondsForSteps(long steps) {
        return steps * FIXED_DT;
    }

    /** Steps in a duration, rounded down. The inverse of {@link #secondsForSteps}. */
    public static long stepsForSeconds(double seconds) {
        return (long) Math.floor(seconds / FIXED_DT);
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
        float a = (float) (accumulator / FIXED_DT);
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
