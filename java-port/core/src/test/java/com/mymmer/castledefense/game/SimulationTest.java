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
        public void step(float dt) {
            deltas.add(dt);
        }
    }

    private static final float DT = Simulation.DT;

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
        assertEquals(3 * (double) DT, sim.timeSeconds(), 1e-9);
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
        assertEquals(Simulation.MAX_STEPS * (double) DT, sim.timeSeconds(), 1e-9);

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

    @Test
    @DisplayName("simulation time is exactly stepCount * DT over a long run")
    void timeAccounting() {
        Recorder r = new Recorder();
        Simulation sim = new Simulation(r);
        for (int i = 0; i < 3600; i++) {            // a minute at 60 fps
            sim.advance(DT);
        }
        assertEquals(3600L, sim.stepCount());
        assertEquals(3600 * (double) DT, sim.timeSeconds(), 1e-9,
                "time is derived from steps, so it cannot drift");
        //  Not exactly 60.0: DT is 1f/60f, whose float value is a hair above a
        //  true sixtieth, so 3600 steps read ~3 microseconds high. That is the
        //  documented, intended behaviour -- the clock reports what the physics
        //  actually integrated, not what a stopwatch says.
        assertEquals(60.0, sim.timeSeconds(), 1e-4, "3600 steps is one minute");
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
        //  One real second of frames must buy one second of gameplay. Not to
        //  the step: 60 steps cost marginally more than a second (see DT's
        //  float value), so a second's worth of frames yields 59 or 60. One
        //  step of slack, no more -- a systematic error would fail this fast.
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
