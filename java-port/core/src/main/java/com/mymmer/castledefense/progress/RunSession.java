package com.mymmer.castledefense.progress;

import com.mymmer.castledefense.config.DifficultyConfig;
import com.mymmer.castledefense.config.GameConfig;
import com.mymmer.castledefense.config.Tuning;
import com.mymmer.castledefense.debug.NoOpSimulationTrace;
import com.mymmer.castledefense.debug.SimulationTrace;
import com.mymmer.castledefense.debug.TraceEvent;
import com.mymmer.castledefense.defence.CombatModifiers;
import com.mymmer.castledefense.game.GameMode;
import com.mymmer.castledefense.game.Simulation;
import java.util.Locale;

/**
 * Run-level state: what is true about <em>this attempt</em>, as opposed to this
 * step.
 *
 * <h2>What belongs here, and what does not</h2>
 *
 * <p>Phase 1 proposed separating run state from the world, and this is that
 * separation. The world owns entities, the clock and the freeze rule; the
 * session owns the scoreboard, the purse, the wave or tier number, the horn and
 * the run's identity. A director reads and writes the session; entities never
 * see it, they see the narrow context seams they already had.
 *
 * <p>It is deliberately <b>not</b> the Python {@code Game} object. It holds no
 * castle, no enemy list, no shop, no talents, no input and no screen. What it
 * does hold is a dozen scalars that genuinely travel together: you cannot
 * describe a run without all of them, and splitting "gold" from "score" from
 * "wave" into three components would be ceremony, not design.
 *
 * <h2>Time</h2>
 *
 * <p>{@link #playTime()} is the Endless run clock and is a {@code double}. It is
 * advanced by the director from the canonical step, only while the world is
 * unfrozen — so the realtime shop genuinely stops it, which the Python self-test
 * asserts.
 */
public final class RunSession {

    private final CombatModifiers mods;
    private SimulationTrace trace = NoOpSimulationTrace.INSTANCE;

    // --- identity -----------------------------------------------------------
    private GameMode mode = GameMode.CLASSIC;
    private DifficultyConfig difficulty;
    private long seed;

    // --- progression --------------------------------------------------------
    /** Classic: the wave number. Endless: the tier. One field, as in Python. */
    private int wave;
    /**
     * Steps of unfrozen play. The run clock is derived from this, never summed.
     *
     * <p>{@code SIMULATION.md} invariant 2b: a long-lived clock reads a step
     * count rather than accumulating its own total. It matters here more than
     * anywhere else — an Endless run is an hour of additions, and a summed
     * {@code playTime} reads 29.999999999999577 at the 1800th step, which puts
     * the tier ladder, the boss timetable and the talent drip all one step out.
     * Derived, it reads exactly 30.0.
     */
    private long playSteps;
    private boolean started;

    // --- economy and scoring ------------------------------------------------
    private int gold = GameConfig.STARTING_GOLD;
    private int score;
    private int bestFling;
    private float bestCombo = 1f;
    /** Visual only: 1.0 on a combo award, decaying at 1.6/s. */
    private float comboFlash;

    // --- the horn -----------------------------------------------------------
    private boolean hornUsed;
    private float hornBonus;
    /** Visual only: 1.0 when blown, decaying at 2.0/s. */
    private float hornGlow;

    // --- statistics ---------------------------------------------------------
    private int kills;
    private float thrownDamage;
    private int platesTorn;

    public RunSession(CombatModifiers mods) {
        this.mods = mods != null ? mods : CombatModifiers.NONE;
    }

    public void setTrace(SimulationTrace trace) {
        this.trace = trace != null ? trace : NoOpSimulationTrace.INSTANCE;
    }

    // --- lifecycle ----------------------------------------------------------

    /**
     * Resets everything a new attempt must not inherit.
     *
     * <p>Mirrors the run-level half of {@code Game.reset}. Note {@code wave}
     * starts at <b>0</b>: Classic's {@code start_wave} increments before
     * announcing, so the first wave is 1. Endless overrides it to 1 in
     * {@code begin_endless}.
     */
    public void begin(GameMode mode, DifficultyConfig difficulty, long seed) {
        this.mode = mode != null ? mode : GameMode.CLASSIC;
        this.difficulty = difficulty;
        this.seed = seed;
        wave = 0;
        playSteps = 0L;
        started = false;
        gold = GameConfig.STARTING_GOLD;
        score = 0;
        bestFling = 0;
        bestCombo = 1f;
        comboFlash = 0f;
        hornUsed = false;
        hornBonus = 0f;
        hornGlow = 0f;
        kills = 0;
        thrownDamage = 0f;
        platesTorn = 0;
    }

    /**
     * Ages the cosmetic flashes.
     *
     * <p>Called from the <b>always</b> tier of the world step, because Python
     * decays them before its state check. They are floats: amplitudes, not
     * durations.
     */
    public void ageFlashes(double dt) {
        float fdt = (float) dt;
        comboFlash = Math.max(0f, comboFlash - fdt * 1.6f);
        hornGlow = Math.max(0f, hornGlow - fdt * 2.0f);
    }

    // --- identity -----------------------------------------------------------

    public GameMode mode() {
        return mode;
    }

    public boolean isEndless() {
        return mode == GameMode.ENDLESS;
    }

    public DifficultyConfig difficulty() {
        return difficulty;
    }

    public long seed() {
        return seed;
    }

    /** The difficulty's gold multiplier, or 1 before a difficulty is chosen. */
    public float goldScale() {
        return difficulty != null ? difficulty.gold() : 1f;
    }

    // --- progression --------------------------------------------------------

    public int wave() {
        return wave;
    }

    public void setWave(int wave) {
        this.wave = wave;
    }

    /**
     * Canonical seconds of unfrozen play. The Endless clock.
     *
     * <p>{@code playSteps * FIXED_DT}, computed fresh. Exact at every scale:
     * 1800 steps read 30.0, 216,000 read 3600.0.
     */
    public double playTime() {
        return Simulation.secondsForSteps(playSteps);
    }

    /** Steps of unfrozen play. The exact form of {@link #playTime()}. */
    public long playSteps() {
        return playSteps;
    }

    /**
     * Advances the run clock by one step.
     *
     * <p>Only the director calls this, and only while the world advances — which
     * is what makes the Endless realtime shop a true freeze.
     */
    public void advancePlayStep() {
        playSteps++;
    }

    /** True once the run proper has begun — Endless uses it to avoid re-arming. */
    public boolean started() {
        return started;
    }

    public void setStarted(boolean started) {
        this.started = started;
    }

    // --- economy ------------------------------------------------------------

    public int gold() {
        return gold;
    }

    public void addGold(int amount) {
        if (amount == 0) {
            return;
        }
        gold += amount;
        if (trace.isEnabled()) {
            trace.event(TraceEvent.GOLD_CHANGED, 0L, 0L, amount, gold, "gold");
        }
    }

    /** Spending. Returns false and changes nothing when the purse is short. */
    public boolean spendGold(int amount) {
        if (amount < 0 || gold < amount) {
            return false;
        }
        gold -= amount;
        if (trace.isEnabled()) {
            trace.event(TraceEvent.GOLD_CHANGED, 0L, 0L, -amount, gold, "spend");
        }
        return true;
    }

    /**
     * The crowd gold multiplier as it stands right now.
     *
     * <p>Delegates to {@link Scoring}; the world passes in the live count,
     * because only the world knows the roster. Called from {@code Enemy.die}
     * before the mob is marked dead — see {@link Scoring} for that quirk.
     */
    public float goldMultiplier(int aliveCount) {
        return Scoring.crowdGoldMultiplier(aliveCount, hornBonus, goldScale(), mods);
    }

    // --- scoring ------------------------------------------------------------

    public int score() {
        return score;
    }

    public int bestFling() {
        return bestFling;
    }

    public float bestCombo() {
        return bestCombo;
    }

    /** Visual only. */
    public float comboFlash() {
        return comboFlash;
    }

    /**
     * Awards a completed fling — {@code main.py:1441 add_score}.
     *
     * <p>The order is the source's: the horn and Showman multipliers are applied
     * to the already-truncated fling points, the total goes up, {@code bestFling}
     * tracks the <b>awarded</b> figure rather than the raw one, and the combo
     * best is only touched when the fling actually struck something.
     */
    public void addScore(int points, int hits, float combo) {
        int awarded = Scoring.awardedScore(points, hornBonus, mods);
        score += awarded;
        if (awarded > bestFling) {
            bestFling = awarded;
        }
        if (hits > 0) {
            if (combo > bestCombo) {
                bestCombo = combo;
                if (trace.isEnabled()) {
                    trace.event(TraceEvent.COMBO_CHANGED, 0L, 0L, combo, hits, "best");
                }
            }
            comboFlash = 1f;
        }
        if (trace.isEnabled()) {
            trace.event(TraceEvent.SCORE_CHANGED, 0L, 0L, awarded, score,
                    hits > 0 ? "combo" : "fling");
        }
    }

    // --- the horn -----------------------------------------------------------

    public boolean hornUsed() {
        return hornUsed;
    }

    /** The extra-rewards fraction the horn is paying, 0 or {@code HORN_BONUS}. */
    public float hornBonus() {
        return hornBonus;
    }

    /** Visual only. */
    public float hornGlow() {
        return hornGlow;
    }

    /** Arms the horn again. Classic does this per wave; Endless only per run. */
    public void resetHorn() {
        hornUsed = false;
        hornBonus = 0f;
    }

    /** Consumes the horn and turns on the reward bonus for the rest of the stretch. */
    public void consumeHorn() {
        hornUsed = true;
        hornBonus = Tuning.HORN_BONUS;
        hornGlow = 1f;
    }

    // --- statistics ---------------------------------------------------------

    public int kills() {
        return kills;
    }

    public void addKill() {
        kills++;
    }

    public float thrownDamage() {
        return thrownDamage;
    }

    public void addThrownDamage(float amount) {
        thrownDamage += amount;
    }

    public int platesTorn() {
        return platesTorn;
    }

    public void addPlatesTorn() {
        platesTorn++;
    }

    // --- diagnostics --------------------------------------------------------

    /** One line for the crash log and the debug overlay. Built on demand only. */
    public String describe() {
        return "mode=" + mode.id()
                + " diff=" + (difficulty != null ? difficulty.id() : "?")
                + (isEndless() ? " tier=" : " wave=") + wave
                + " t=" + String.format(Locale.ROOT, "%.2f", playTime()) + "s"
                + " gold=" + gold
                + " score=" + score
                + " bestFling=" + bestFling
                + " bestCombo=" + String.format(Locale.ROOT, "%.2f", bestCombo)
                + " kills=" + kills
                + (hornUsed ? " horn=used" : " horn=ready");
    }
}
