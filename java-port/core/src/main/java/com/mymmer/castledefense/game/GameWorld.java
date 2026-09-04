package com.mymmer.castledefense.game;

import com.mymmer.castledefense.debug.NoOpSimulationTrace;
import com.mymmer.castledefense.debug.SimulationTrace;
import com.mymmer.castledefense.debug.TraceEvent;
import com.mymmer.castledefense.entity.Entity;
import com.mymmer.castledefense.entity.EntityList;
import com.mymmer.castledefense.util.Rng;

/**
 * The authority for gameplay state.
 *
 * <p>Everything that belongs to a run lives here or below here: entities, the
 * gameplay clock, the current state and mode, and the run's random seed. Systems
 * added in later phases (spawning, combat, progression) operate <em>on</em> the
 * world rather than holding their own copies of its state, so there is exactly
 * one place to look for what is true right now.
 *
 * <h2>Two clocks, and why</h2>
 *
 * <ul>
 *   <li>{@link #simulationTime()} — advances on every step, in any state. The
 *       Python {@code Game.time} does the same: it is incremented before the
 *       state check, so it keeps running in menus. Fling scoring and input
 *       timestamps use it.</li>
 *   <li>{@link #gameplayTime()} — advances only while the state
 *       {@linkplain GameState#advancesWorld() advances the world}. This is the
 *       clock the Endless run timer, spawn timers, cooldowns, boss schedules and
 *       wave-clear delays will use, and it is what makes the mid-fight Endless
 *       shop a true freeze.</li>
 * </ul>
 *
 * <p>Both are derived from step counts, never from wall-clock. If the simulation
 * drops time because it hit the step budget, both clocks drop it too.
 *
 * <h2>Ownership</h2>
 * <ul>
 *   <li><b>Created by</b> {@code CastleDefenseGame}; <b>owned by</b> it.</li>
 *   <li><b>Owns</b> the entity lists and both clocks. It is the only thing that
 *       may sweep a list or advance a clock.</li>
 *   <li><b>Borrows</b> {@link Rng} and {@link SimulationTrace} from
 *       {@code Services} / the launcher — it uses them, it does not dispose
 *       them.</li>
 *   <li><b>Does not own</b> input, rendering, assets, skins or persistence.</li>
 * </ul>
 *
 * <p>Phase 4 keeps the entity list generic: there are no enemies, towers or
 * projectiles yet, only the lifecycle they will use.
 */
public final class GameWorld implements Simulation.Stepper {

    /** Generic entities. Typed lists (enemies, projectiles…) arrive in Phase 5+. */
    private final EntityList<Entity> entities = new EntityList<>();

    private final Rng rng;
    private SimulationTrace trace = NoOpSimulationTrace.INSTANCE;

    private GameState state = GameState.MENU;
    private GameMode mode = GameMode.CLASSIC;

    private long steps;
    private long gameplaySteps;
    private long runSeed;

    private StepListener stepListener;

    /**
     * Hook for systems that must run inside a step.
     *
     * <p>{@code dt} is the canonical {@code double} step. A system that
     * integrates positions narrows it once, itself, with
     * {@code float fdt = (float) dt}.
     */
    public interface StepListener {
        void onStep(GameWorld world, double dt);
    }

    public GameWorld(Rng rng) {
        if (rng == null) {
            throw new IllegalArgumentException("rng must not be null");
        }
        this.rng = rng;
        this.runSeed = rng.getGameSeed();
    }

    // --- the step -----------------------------------------------------------

    /**
     * One simulation step.
     *
     * <p>Called only by {@link Simulation}. The three tiers from
     * {@code main.py}'s {@code update} are visible here: simulation time always
     * advances, the world advances only in a state that
     * {@linkplain GameState#advancesWorld() allows it}, and effects sit in
     * between (they arrive in Phase 11).
     */
    @Override
    public void step(double dt) {
        steps++;
        if (trace.isEnabled()) {
            trace.event(TraceEvent.SIMULATION_STEP, steps, 0L, (float) dt, 0f, state.name());
        }

        if (!state.advancesWorld()) {
            // Frozen: no gameplay clock, no entity updates, no spawning.
            // Effects and cosmetic timers will tick here in Phase 11.
            return;
        }

        gameplaySteps++;

        if (stepListener != null) {
            stepListener.onStep(this, dt);
        }

        // Mark-dead then sweep, exactly as the Python does at the end of update.
        entities.sweep();
    }

    /** Attaches the per-step system hook. One owner, set by the run builder. */
    public void setStepListener(StepListener listener) {
        this.stepListener = listener;
    }

    // --- clocks -------------------------------------------------------------

    /**
     * Canonical seconds of simulation, advancing in every state.
     *
     * <p>{@code steps * FIXED_DT}, computed fresh — never a running total. See
     * {@link Simulation} for why the canonical step is a double while the step
     * handed to gameplay is a float.
     */
    public double simulationTime() {
        return Simulation.secondsForSteps(steps);
    }

    /** Canonical seconds of gameplay, advancing only while the world is unfrozen. */
    public double gameplayTime() {
        return Simulation.secondsForSteps(gameplaySteps);
    }

    public long stepCount() {
        return steps;
    }

    public long gameplayStepCount() {
        return gameplaySteps;
    }

    // --- state --------------------------------------------------------------

    public GameState state() {
        return state;
    }

    /** Changes state, tracing the transition. */
    public void setState(GameState next) {
        if (next == null || next == state) {
            return;
        }
        GameState previous = state;
        state = next;
        if (trace.isEnabled()) {
            trace.event(TraceEvent.STATE_TRANSITION, steps, 0L, 0f, 0f,
                    previous.name() + "->" + next.name());
        }
    }

    public GameMode mode() {
        return mode;
    }

    public void setMode(GameMode next) {
        if (next != null) {
            mode = next;
        }
    }

    public boolean isFrozen() {
        return !state.advancesWorld();
    }

    // --- entities -----------------------------------------------------------

    public EntityList<Entity> entities() {
        return entities;
    }

    /** Adds an entity and traces the spawn. */
    public void spawn(Entity entity) {
        entities.add(entity);
        if (trace.isEnabled()) {
            trace.event(TraceEvent.ENTITY_SPAWN, steps, entity.uid(), 0f, 0f,
                    entity.getClass().getSimpleName());
        }
    }

    /** Marks an entity dead and traces it. Removal happens at the sweep. */
    public void kill(Entity entity) {
        if (entity == null || !entity.isAlive()) {
            return;
        }
        entity.markDead();
        if (trace.isEnabled()) {
            trace.event(TraceEvent.ENTITY_DEATH, steps, entity.uid(), 0f, 0f,
                    entity.getClass().getSimpleName());
        }
    }

    // --- run lifecycle ------------------------------------------------------

    /**
     * Starts a run on a known seed.
     *
     * <p>The seed is recorded so a bug report can carry it: with a build id, a
     * seed and a mode, a session can be replayed. Players never see or choose
     * one; a debug build or a test does.
     */
    public void beginRun(GameMode runMode, long seed) {
        entities.clear();
        steps = 0;
        gameplaySteps = 0;
        runSeed = seed;
        rng.reseedGame(seed);
        mode = runMode != null ? runMode : GameMode.CLASSIC;
        setState(GameState.PLAYING);
    }

    /**
     * Starts a run on a fresh, arbitrary seed — unless one was supplied for
     * debugging, in which case every run in the process uses it.
     *
     * <p>{@code -Dcastledefense.seed=…} is how a bug report is reproduced: the
     * seed the reporter's crash log names goes on the command line and the same
     * run comes back. Production players have no way to reach it.
     */
    public long beginRun(GameMode runMode) {
        long seed = Rng.debugSeedOr(rng.nextRunSeed());
        beginRun(runMode, seed);
        return seed;
    }

    /** The seed this run is running on. */
    public long runSeed() {
        return runSeed;
    }

    public Rng rng() {
        return rng;
    }

    // --- diagnostics --------------------------------------------------------

    public SimulationTrace trace() {
        return trace;
    }

    public void setTrace(SimulationTrace trace) {
        this.trace = trace != null ? trace : NoOpSimulationTrace.INSTANCE;
    }

    /** One line for the crash log and the debug overlay. */
    public String describe() {
        return "mode=" + mode.id() + " state=" + state
                + " step=" + steps + " gameplay=" + String.format("%.2f", gameplayTime()) + "s"
                + " entities=" + entities.aliveCount() + "/" + entities.size()
                + " seed=" + runSeed;
    }
}
