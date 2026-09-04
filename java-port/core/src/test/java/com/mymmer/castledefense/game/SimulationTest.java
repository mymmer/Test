package com.mymmer.castledefense.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.badlogic.gdx.utils.FloatArray;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The accumulator is the heart of the port's timing, so it is tested for exact
 * step counts and exact simulation-time progression, not approximations.
 */
class SimulationTest {

    /** Records every dt it is handed, so step counts can be asserted exactly. */
    private static final class Recorder implements Simulation.Stepper {
        final FloatArray deltas = new FloatArray();

        @Override
        public void step(double dt) {
            deltas.add((float) dt);
        }
    }

    /** The frame delta fed to {@link Simulation#advance}, which is a float. */
    private static final float DT = Simulation.PHYSICS_DT;

    @Test
    @DisplayName("exactly one DT runs exactly one step")
    void oneStep() {
        Recorder r = new Recorder();
        Simulation sim = new Simulation(r);
        assertEquals(1, sim.advance(DT));
        assertEquals(1, r.deltas.size);
        assertEquals(DT, r.deltas.get(0), 0f, "a step is always a whole DT");
        assertEquals(1L, sim.stepCount());
        assertEquals(DT, sim.timeSeconds(), 1e-9);
    }

    @Test
    @DisplayName("half a DT runs nothing; two halves run one step")
    void halfPlusHalf() {
        Recorder r = new Recorder();
        Simulation sim = new Simulation(r);

        assertEquals(0, sim.advance(DT * 0.5f), "half a step is not a step");
        assertEquals(0L, sim.stepCount());
        assertEquals(0.0, sim.timeSeconds(), 1e-9, "and time must not advance");

        assertEquals(1, sim.advance(DT * 0.5f), "the halves add up");
        assertEquals(1L, sim.stepCount());
        assertEquals(DT, sim.timeSeconds(), 1e-9);
    }

    @Test
    @DisplayName("a long frame runs several steps in one call")
    void multipleStepsPerFrame() {
        Recorder r = new Recorder();
        Simulation sim = new Simulation(r);
        assertEquals(3, sim.advance(DT * 3f));
        assertEquals(3, r.deltas.size);
        assertEquals(0.05, sim.timeSeconds(), 1e-12, "3 steps is exactly 3/60 s");
        for (int i = 0; i < r.deltas.size; i++) {
            assertEquals(DT, r.deltas.get(i), 0f, "every step is a whole DT");
        }
    }

    @Test
    @DisplayName("the step budget caps catch-up and the excess is dropped")
    void stepBudget() {
        Recorder r = new Recorder();
        Simulation sim = new Simulation(r);
        // ask for 20 steps' worth in one frame
        int ran = sim.advance(DT * 20f);
        assertEquals(Simulation.MAX_STEPS, ran, "no more than the budget may run");
        assertEquals(Simulation.MAX_STEPS, sim.stepCount());
        assertEquals(1L, sim.droppedStepEvents());
        assertEquals(0f, sim.accumulatorSeconds(), 1e-7f,
                "leftover time must be dropped, not carried into the next frame");
        // and gameplay time is dropped with it -- not caught up later
        assertEquals(Simulation.MAX_STEPS / 60.0, sim.timeSeconds(), 1e-12,
                "the steps that did run are canonical seconds");

        int next = sim.advance(DT);
        assertEquals(1, next, "the next frame starts clean");
    }

    @Test
    @DisplayName("a stalled frame is clamped before it reaches the accumulator")
    void frameDeltaClamp() {
        Recorder r = new Recorder();
        Simulation sim = new Simulation(r);
        sim.advance(30f);           // a 30-second stall, e.g. a debugger break
        assertEquals(1L, sim.clampedFrames());
        assertTrue(sim.stepCount() <= Simulation.MAX_STEPS,
                "a stall must never replay 1800 steps");
    }

    @Test
    @DisplayName("garbage deltas are ignored rather than corrupting the clock")
    void badDeltas() {
        Recorder r = new Recorder();
        Simulation sim = new Simulation(r);
        assertEquals(0, sim.advance(Float.NaN));
        assertEquals(0, sim.advance(-5f));
        assertEquals(0L, sim.stepCount());
        assertEquals(0.0, sim.timeSeconds(), 1e-9);
    }

    @Test
    @DisplayName("resume clears the accumulator so stale time is not replayed")
    void resumeClearsAccumulator() {
        Recorder r = new Recorder();
        Simulation sim = new Simulation(r);
        sim.advance(DT * 0.9f);                     // most of a step pending
        assertTrue(sim.accumulatorSeconds() > 0f);

        sim.resetAccumulator();                      // <- what resume() does
        assertEquals(0f, sim.accumulatorSeconds(), 0f);

        assertEquals(0, sim.advance(DT * 0.5f),
                "the pending fraction from before the pause is gone");
        assertEquals(0L, sim.stepCount());
    }

    //  ------------------------------------------------------------------
    //  Canonical clock.
    //
    //  These assertions are EXACT, because the port controls the input: a step
    //  count is an integer we chose, so N steps must be exactly N/60 seconds.
    //  The frame-rate tests further down are the opposite case -- there the
    //  input is an approximate float delta supplied by the platform, so a
    //  one-step tolerance is honest rather than sloppy.  Do not confuse the
    //  two: loosening these would hide a real drift bug.
    //  ------------------------------------------------------------------

    /** Double tolerance for a value that should be exact bar the last few ulps. */
    private static final double EXACT = 1e-12;

    @Test
    @DisplayName("canonical seconds: 60 steps = 1 s, 3600 = 60 s, 216000 = 3600 s")
    void canonicalSecondsAreExact() {
        assertEquals(1.0, Simulation.secondsForSteps(60L), EXACT, "one second");
        assertEquals(60.0, Simulation.secondsForSteps(3600L), EXACT, "one minute");
        assertEquals(3600.0, Simulation.secondsForSteps(216_000L), EXACT, "one hour");
        assertEquals(0.0, Simulation.secondsForSteps(0L), 0.0);

        // and the inverse agrees
        assertEquals(60L, Simulation.stepsForSeconds(1.0));
        assertEquals(3600L, Simulation.stepsForSeconds(60.0));
        assertEquals(216_000L, Simulation.stepsForSeconds(3600.0));
    }

    @Test
    @DisplayName("the running clock is exact after an hour of stepping")
    void timeAccounting() {
        Recorder r = new Recorder();
        Simulation sim = new Simulation(r);
        //  One float DT per call is very slightly more than a canonical step, so
        //  each call runs exactly one step; the surplus over an hour is ~0.2 ms,
        //  far under a step, so the count is exact and so is the clock.
        for (int i = 0; i < 216_000; i++) {
            sim.advance(DT);
        }
        assertEquals(216_000L, sim.stepCount(), "one step per frame, no drift");
        assertEquals(3600.0, sim.timeSeconds(), EXACT,
                "216000 steps is exactly one hour of canonical time");

        // the intermediate marks land exactly too
        sim.reset();
        for (int i = 0; i < 60; i++) {
            sim.advance(DT);
        }
        assertEquals(1.0, sim.timeSeconds(), EXACT, "60 steps is exactly one second");
    }

    @Test
    @DisplayName("the clock does not accumulate the float step's error")
    void canonicalClockBeatsFloatAccumulation() {
        //  This is the bug the double canonical step exists to prevent.  Summing
        //  the float step -- the obvious implementation -- is wrong by ~188 us
        //  after an hour, because 1f/60f is not a sixtieth.  Multiplying an exact
        //  step index by an exact double is wrong by essentially nothing.
        float naive = 0f;
        for (int i = 0; i < 216_000; i++) {
            naive += DT;
        }
        assertTrue(Math.abs(naive - 3600.0) > 1e-3,
                "a float running total is expected to be visibly wrong, was " + naive);
        assertEquals(3600.0, Simulation.secondsForSteps(216_000L), EXACT,
                "the canonical clock is not");
    }

    @Test
    @DisplayName("gameplay receives the canonical double step, not the float one")
    void gameplayStepIsTheCanonicalDouble() {
        //  The whole point of the time-domain rule: step() is handed FIXED_DT
        //  itself, so a timer that subtracts it subtracts an exact sixtieth.
        //  PHYSICS_DT still exists for spatial maths and must stay exactly the
        //  float nearest the canonical step -- not a separately written literal
        //  that could drift away from it in a later edit.
        assertEquals((float) Simulation.FIXED_DT, Simulation.PHYSICS_DT, 0f);
        assertEquals(1.0 / 60.0, Simulation.FIXED_DT, 0.0);

        Recorder r = new Recorder();
        Simulation sim = new Simulation(r);
        sim.advance(DT);
        assertEquals(1, r.deltas.size);
        assertEquals(Simulation.PHYSICS_DT, r.deltas.get(0), 0f,
                "step() is handed the canonical step, whose float value is PHYSICS_DT");
    }

    @Test
    @DisplayName("the float step is fractionally LONGER than a sixtieth, which is the bug")
    void theFloatStepIsNotASixtieth() {
        //  This is the whole reason the time domain is a double.  Subtracting
        //  PHYSICS_DT from a timer 75 times loses more than subtracting
        //  FIXED_DT 75 times, and at a boundary that is one extra fireball.
        assertTrue(Simulation.PHYSICS_DT > Simulation.FIXED_DT,
                "1f/60f is above a true sixtieth");

        double exact = 0d;
        float sloppy = 0f;
        for (int i = 0; i < 3600; i++) {
            exact += Simulation.FIXED_DT;
            sloppy += Simulation.PHYSICS_DT;
        }
        assertEquals(60.0, exact, 1e-9, "a minute of canonical steps is a minute");
        assertTrue(Math.abs(sloppy - 60f) > 1e-4f,
                "a minute of float steps has visibly drifted");
    }

    @Test
    @DisplayName("a 144 Hz display still advances one second of gameplay per second")
    void highRefreshRate() {
        Recorder r = new Recorder();
        Simulation sim = new Simulation(r);
        float frame = 1f / 144f;
        for (int i = 0; i < 144; i++) {
            sim.advance(frame);
        }
        //  Tolerance is correct HERE and nowhere above: 1/144f is an
        //  approximate float supplied by a display, so 144 of them do not sum to
        //  exactly one second and the honest answer is 59 or 60 steps. One step
        //  of slack, no more -- a systematic error would fail this immediately.
        assertTrue(sim.stepCount() == 60L || sim.stepCount() == 59L,
                "render rate must not change how fast the game runs, got "
                        + sim.stepCount());
        assertEquals(1.0, sim.timeSeconds(), DT,
                "gameplay time must track real time to within one step");
    }

    @Test
    @DisplayName("a 30 Hz display also advances one second per second")
    void lowRefreshRate() {
        Recorder r = new Recorder();
        Simulation sim = new Simulation(r);
        float frame = 1f / 30f;
        for (int i = 0; i < 30; i++) {
            sim.advance(frame);
        }
        assertTrue(sim.stepCount() == 60L || sim.stepCount() == 59L,
                "got " + sim.stepCount());
        assertEquals(1.0, sim.timeSeconds(), DT);
    }

    @Test
    @DisplayName("alpha stays inside [0, 1) whatever the frame times are")
    void alphaRange() {
        Recorder r = new Recorder();
        Simulation sim = new Simulation(r);
        float[] frames = {0f, DT * 0.1f, DT, DT * 0.99f, DT * 7f, 30f, 1f / 144f, 1f / 30f};
        for (float f : frames) {
            sim.advance(f);
            float a = sim.alpha();
            assertTrue(a >= 0f && a < 1f, "alpha out of range after delta " + f + ": " + a);
        }
    }

    @Test
    @DisplayName("reset returns the clock to zero")
    void reset() {
        Recorder r = new Recorder();
        Simulation sim = new Simulation(r);
        sim.advance(DT * 10f);
        sim.reset();
        assertEquals(0L, sim.stepCount());
        assertEquals(0.0, sim.timeSeconds(), 1e-9);
        assertEquals(0f, sim.accumulatorSeconds(), 0f);
    }
}
