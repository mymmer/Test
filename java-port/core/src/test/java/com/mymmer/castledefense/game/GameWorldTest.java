package com.mymmer.castledefense.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mymmer.castledefense.debug.RecordingSimulationTrace;
import com.mymmer.castledefense.debug.TraceEvent;
import com.mymmer.castledefense.entity.Entity;
import com.mymmer.castledefense.util.Rng;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Freeze semantics, the two clocks, and run seeding. */
class GameWorldTest {

    /** A stand-in for the real entities of Phase 5+. */
    private static final class Probe extends Entity {
        int steps;
    }

    private static final float DT = Simulation.DT;

    private static GameWorld world() {
        return new GameWorld(new Rng(42L));
    }

    @Test
    @DisplayName("gameplay time advances only while the state advances the world")
    void freezeStopsGameplayTime() {
        GameWorld w = world();
        Simulation sim = new Simulation(w);
        w.setState(GameState.PLAYING);

        for (int i = 0; i < 60; i++) {
            sim.advance(DT);
        }
        assertEquals(60L, w.stepCount());
        assertEquals(60L, w.gameplayStepCount());
        assertEquals(1.0, w.gameplayTime(), 1e-4);

        // open the Endless shop mid-fight: the world freezes
        w.setState(GameState.SHOP);
        double frozenAt = w.gameplayTime();
        for (int i = 0; i < 120; i++) {
            sim.advance(DT);
        }
        assertEquals(180L, w.stepCount(), "simulation time keeps running");
        assertEquals(60L, w.gameplayStepCount(), "but gameplay time does not");
        assertEquals(frozenAt, w.gameplayTime(), 0d,
                "two seconds of shopping must cost the run zero seconds");

        w.setState(GameState.PLAYING);
        sim.advance(DT);
        assertEquals(61L, w.gameplayStepCount(), "and it resumes exactly where it stopped");
    }

    @Test
    @DisplayName("every non-playing state freezes the world")
    void everyNonPlayingStateFreezes() {
        for (GameState state : GameState.values()) {
            GameWorld w = world();
            Simulation sim = new Simulation(w);
            w.setState(state);
            sim.advance(DT * 5f);
            if (state == GameState.PLAYING) {
                assertTrue(w.gameplayStepCount() > 0, state + " must advance the world");
            } else {
                assertEquals(0L, w.gameplayStepCount(), state + " must freeze the world");
                assertTrue(w.isFrozen(), state + " must report as frozen");
            }
        }
    }

    @Test
    @DisplayName("entity systems only run while unfrozen")
    void stepListenerRespectsFreeze() {
        GameWorld w = world();
        Simulation sim = new Simulation(w);
        final Probe probe = new Probe();
        w.spawn(probe);
        w.setStepListener(new GameWorld.StepListener() {
            @Override
            public void onStep(GameWorld world, float dt) {
                probe.steps++;
            }
        });

        w.setState(GameState.PLAYING);
        sim.advance(DT * 3f);
        assertEquals(3, probe.steps);

        w.setState(GameState.PAUSED);
        sim.advance(DT * 10f);
        assertEquals(3, probe.steps, "a paused game must not step its systems");
    }

    @Test
    @DisplayName("dead entities are swept at the end of a step, not mid-step")
    void sweepHappensAfterTheStep() {
        GameWorld w = world();
        Simulation sim = new Simulation(w);
        final Probe a = new Probe();
        final Probe b = new Probe();
        w.spawn(a);
        w.spawn(b);
        w.setState(GameState.PLAYING);

        w.setStepListener(new GameWorld.StepListener() {
            @Override
            public void onStep(GameWorld world, float dt) {
                world.kill(a);
                // still present for the rest of this step, exactly as in Python
                assertEquals(2, world.entities().size(),
                        "a killed entity stays in the list until the sweep");
                assertFalse(a.isAlive());
            }
        });

        sim.advance(DT);
        assertEquals(1, w.entities().size(), "and is gone once the step ends");
        assertEquals(b, w.entities().get(0), "the survivor keeps its place");
    }

    @Test
    @DisplayName("a run begins on a recorded seed that can be replayed")
    void runSeeding() {
        GameWorld w = world();
        w.beginRun(GameMode.ENDLESS, 82736191L);
        assertEquals(82736191L, w.runSeed());
        assertEquals(GameMode.ENDLESS, w.mode());
        assertEquals(GameState.PLAYING, w.state());
        assertTrue(w.describe().contains("seed=82736191"),
                "the seed must reach a bug report: " + w.describe());

        float first = w.rng().game().nextFloat();
        w.beginRun(GameMode.ENDLESS, 82736191L);
        assertEquals(first, w.rng().game().nextFloat(), 0f,
                "the same seed must replay the same run");
    }

    @Test
    @DisplayName("beginning a run clears the previous one")
    void runResets() {
        GameWorld w = world();
        w.setState(GameState.PLAYING);
        w.spawn(new Probe());
        new Simulation(w).advance(DT * 5f);
        assertTrue(w.stepCount() > 0);

        w.beginRun(GameMode.CLASSIC, 1L);
        assertEquals(0L, w.stepCount());
        assertEquals(0L, w.gameplayStepCount());
        assertEquals(0, w.entities().size());
    }

    @Test
    @DisplayName("the trace observes steps, transitions, spawns and deaths")
    void tracing() {
        GameWorld w = world();
        RecordingSimulationTrace trace = new RecordingSimulationTrace();
        w.setTrace(trace);
        w.setState(GameState.PLAYING);

        Probe probe = new Probe();
        w.spawn(probe);
        new Simulation(w).advance(DT * 2f);
        w.kill(probe);

        assertEquals(2, trace.countOf(TraceEvent.SIMULATION_STEP));
        assertEquals(1, trace.countOf(TraceEvent.ENTITY_SPAWN));
        assertEquals(1, trace.countOf(TraceEvent.ENTITY_DEATH));
        assertTrue(trace.countOf(TraceEvent.STATE_TRANSITION) >= 1);
    }

    @Test
    @DisplayName("the default trace is off and costs nothing")
    void traceOffByDefault() {
        GameWorld w = world();
        assertFalse(w.trace().isEnabled(),
                "shipping builds must not pay for diagnostics");
        w.setTrace(null);
        assertFalse(w.trace().isEnabled(), "null must fall back to the no-op");
    }

    @Test
    @DisplayName("game modes round-trip through stable ids, never ordinals")
    void modeIds() {
        assertEquals(GameMode.ENDLESS, GameMode.byId("endless", GameMode.CLASSIC));
        assertEquals(GameMode.CLASSIC, GameMode.byId("classic", GameMode.ENDLESS));
        assertEquals(GameMode.CLASSIC, GameMode.byId("nonsense", GameMode.CLASSIC));
        assertEquals(GameMode.CLASSIC, GameMode.byId(null, GameMode.CLASSIC));
        assertEquals("endless", GameMode.ENDLESS.id());
    }
}
