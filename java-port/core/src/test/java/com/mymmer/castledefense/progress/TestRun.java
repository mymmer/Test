package com.mymmer.castledefense.progress;

import com.mymmer.castledefense.boss.BossTable;
import com.mymmer.castledefense.config.DifficultyConfig;
import com.mymmer.castledefense.config.DifficultyTable;
import com.mymmer.castledefense.data.JsonSource;
import com.mymmer.castledefense.debug.RecordingSimulationTrace;
import com.mymmer.castledefense.defence.DefenceTable;
import com.mymmer.castledefense.enemy.Enemy;
import com.mymmer.castledefense.enemy.EnemyTable;
import com.mymmer.castledefense.game.GameMode;
import com.mymmer.castledefense.game.GameState;
import com.mymmer.castledefense.game.GameWorld;
import com.mymmer.castledefense.game.RunWorld;
import com.mymmer.castledefense.game.Simulation;
import com.mymmer.castledefense.testsupport.DiskJsonSource;
import com.mymmer.castledefense.util.Rng;

/**
 * A real run, headless.
 *
 * <p>Deliberately <b>not</b> a fake: this builds the production
 * {@link RunWorld}, on the shipped balance tables, driven through the
 * production {@link Simulation}. A progression test that ran against a mock
 * director would prove the mock. The only things stubbed are the parts that need
 * a screen — the renderer is absent entirely, and the pointer is a pair of
 * fields rather than a device.
 *
 * <p>Steps are driven with {@link Simulation#FIXED_DT}, never the float step,
 * for the reason the whole time-domain rule exists.
 */
public final class TestRun {

    public static final long SEED = 20260908L;
    public static final double DT = Simulation.FIXED_DT;

    public final Rng rng;
    public final GameWorld world;
    public final RunWorld run;
    public final Simulation simulation;
    public final EnemyTable enemies;
    public final DefenceTable defences;
    public final BossTable bosses;
    public final DifficultyTable difficulties;
    public final com.mymmer.castledefense.talent.TalentTable talentTable;
    public final com.mymmer.castledefense.shop.ShopTable shopTable;
    public final RecordingSimulationTrace trace = new RecordingSimulationTrace(16384);

    public TestRun() {
        this(SEED);
    }

    public TestRun(long seed) {
        JsonSource json = new DiskJsonSource();
        this.rng = new Rng(seed);
        this.enemies = EnemyTable.load(json);
        this.defences = DefenceTable.load(json);
        this.bosses = BossTable.load(json);
        this.difficulties = DifficultyTable.load(json);
        this.talentTable = com.mymmer.castledefense.talent.TalentTable.load(json);
        this.shopTable = com.mymmer.castledefense.shop.ShopTable.load(json);
        this.world = new GameWorld(rng);
        this.run = new RunWorld(world, enemies, defences, bosses,
                talentTable, shopTable,
                (pointerId, out) -> {
                    out[0] = 0f;
                    out[1] = 0f;
                });
        this.simulation = new Simulation(world);
    }

    /** The run's talent tree. From Phase 9 it IS the run's CombatModifiers. */
    public com.mymmer.castledefense.talent.TalentTree talents() {
        return run.talentTree();
    }

    public com.mymmer.castledefense.shop.Shop shop() {
        return run.shop();
    }

    public com.mymmer.castledefense.skill.SkillPanel skills() {
        return run.skills();
    }

    /** Hands the tree the points a test wants to spend, without playing for them. */
    public TestRun grantPoints(int n) {
        talents().award(n, "test");
        return this;
    }

    /**
     * Opens a talent's tier gate by spending into its branch's entry nodes.
     *
     * <p>A deep node needs points already in its own branch; a test that wants
     * to prove what Eye of the Storm <em>does</em> should not have to spell out
     * how to get there. Grant the points first.
     */
    public TestRun openBranchFor(String id) {
        com.mymmer.castledefense.talent.TalentDef target = talents().table().require(id);
        int guard = 0;
        while (talents().branchPoints(target.branch) < target.tier && guard++ < 400) {
            com.badlogic.gdx.utils.Array<com.mymmer.castledefense.talent.TalentDef> siblings =
                    talents().table().branch(target.branch);
            boolean spent = false;
            for (int i = 0; i < siblings.size; i++) {
                com.mymmer.castledefense.talent.TalentDef other = siblings.get(i);
                if (other != target && talents().canPurchase(other.id)) {
                    talents().purchase(other.id);
                    spent = true;
                    break;
                }
            }
            if (!spent) {
                throw new IllegalStateException("cannot open '" + id + "': branch "
                        + target.branch.id() + " stalled at "
                        + talents().branchPoints(target.branch) + "/" + target.tier
                        + " with " + talents().availablePoints() + " points");
            }
        }
        return this;
    }

    /** {@link #openBranchFor} then {@link #buyTalent}. */
    public TestRun buyTalentDeep(String id, int ranks) {
        openBranchFor(id);
        return buyTalent(id, ranks);
    }

    /** Buys a talent to a rank, asserting each purchase took. */
    public TestRun buyTalent(String id, int ranks) {
        for (int i = 0; i < ranks; i++) {
            if (!talents().purchase(id)) {
                throw new IllegalStateException("could not buy '" + id + "' rank "
                        + (i + 1) + ": " + talents().describe());
            }
        }
        return this;
    }

    /** Puts gold in the purse, for a shop test that has not earned any. */
    public TestRun grantGold(int amount) {
        session().addGold(amount);
        return this;
    }

    // --- starting -----------------------------------------------------------

    public TestRun beginClassic() {
        return begin(GameMode.CLASSIC, "normal");
    }

    public TestRun beginEndless() {
        return begin(GameMode.ENDLESS, "normal");
    }

    public TestRun begin(GameMode mode, String difficultyId) {
        run.beginRun(mode, difficulty(difficultyId), SEED);
        return this;
    }

    public DifficultyConfig difficulty(String id) {
        return difficulties.get(id);
    }

    /** Turns tracing on from this point. Off by default so long runs stay cheap. */
    public TestRun recording() {
        trace.setEnabled(true);
        run.setTrace(trace);
        return this;
    }

    // --- stepping -----------------------------------------------------------

    /** One canonical simulation step, straight into the world. */
    public void step() {
        world.step(DT);
    }

    public void steps(int n) {
        for (int i = 0; i < n; i++) {
            world.step(DT);
        }
    }

    /** Steps for a number of simulated seconds, rounded to whole steps. */
    public void seconds(double s) {
        steps((int) Math.round(s * 60.0));
    }

    /**
     * Steps with the castle kept standing.
     *
     * <p>A schedule test is about the timetable, not about surviving it: an
     * undefended castle falls inside a minute and a run that has ended stops
     * advancing, which would make every long-run assertion vacuous. The keep is
     * topped up each step and nothing else is touched — the horde still spawns,
     * still walks and still attacks.
     */
    public void survivingSeconds(double s) {
        survivingSteps((int) Math.round(s * 60.0));
    }

    /** {@link #survivingSeconds} in steps. */
    public void survivingSteps(int n) {
        for (int i = 0; i < n; i++) {
            survivingStep();
        }
    }

    /**
     * Steps a long timetable quickly.
     *
     * <p>The same as {@link #survivingSeconds} except that ordinary mobs are
     * culled each step, leaving bosses alone. Without it an hour of undefended
     * play accumulates hundreds of Necromancer summons, and crowd separation is
     * O(n²) by contract — the sixty-minute test took four minutes of wall clock
     * and proved nothing about the schedule that a clear field does not.
     *
     * <p>Culling is silent, so it pays no gold and scores nothing. What it does
     * not touch is the director: the clock, the tier ladder, the boss timetable
     * and the spawn gap all run exactly as they would.
     */
    public void scheduleSeconds(double s) {
        scheduleSteps((int) Math.round(s * 60.0));
    }

    /** {@link #scheduleSeconds} in steps. */
    public void scheduleSteps(int n) {
        for (int i = 0; i < n; i++) {
            scheduleStep();
        }
    }

    /** One culled, castle-repaired step. */
    public void scheduleStep() {
        cullOrdinaryEnemies();
        survivingStep();
    }

    /** Kills every non-boss mob, silently. Bosses are left standing. */
    public void cullOrdinaryEnemies() {
        for (int i = 0; i < run.horde().size(); i++) {
            Enemy e = run.horde().get(i);
            if (e.isAlive() && !e.isBoss()) {
                e.die(true);
            }
        }
    }

    /**
     * One step with the keep kept standing.
     *
     * <p>The castle is repaired to full before the step, and if it fell anyway
     * — an undefended keep with a hundred mobs on it can take more than its
     * whole health bar in a single step — the run is put back into
     * {@code PLAYING}. Nothing else is touched: the horde still spawns, walks,
     * summons and attacks, and the director still runs its own timetable.
     *
     * <p>This is a harness for <b>schedule</b> tests, which are about when
     * things happen rather than about surviving them. Anything asserting
     * defeat behaviour uses {@link #step()}.
     */
    public void survivingStep() {
        //  Normalise BEFORE the step as well as after it.  A cull can detonate
        //  a Volatile into the wall and end the run before the step even
        //  begins, and a step entered in GAMEOVER advances nothing -- which
        //  silently loses time from a schedule the test is about to assert.
        reviveIfFallen();
        step();
        reviveIfFallen();
    }

    private void reviveIfFallen() {
        run.castle().repair(1f);
        if (world.state() == GameState.GAMEOVER) {
            world.setState(GameState.PLAYING);
        }
    }

    /** Steps until the predicate holds or the budget runs out. Returns the steps used. */
    public int stepsUntil(int budget, java.util.function.BooleanSupplier done) {
        for (int i = 0; i < budget; i++) {
            if (done.getAsBoolean()) {
                return i;
            }
            step();
        }
        return -1;
    }

    // --- inspection ---------------------------------------------------------

    public RunSession session() {
        return run.session();
    }

    public GameState state() {
        return world.state();
    }

    public int aliveEnemies() {
        int n = 0;
        for (int i = 0; i < run.horde().size(); i++) {
            if (run.horde().get(i).isAlive()) {
                n++;
            }
        }
        return n;
    }

    public int liveBosses() {
        return run.bossRegistry().liveCount();
    }

    /** Kills every live enemy outright, so a wave can be cleared on demand. */
    public void killEveryEnemy() {
        for (int i = 0; i < run.horde().size(); i++) {
            Enemy e = run.horde().get(i);
            if (e.isAlive()) {
                e.die(true);            // silent: no gold, no score, no noise
            }
        }
    }

    /** The Classic director, or null in an Endless run. */
    public WaveDirector waves() {
        return run.director() instanceof WaveDirector ? (WaveDirector) run.director() : null;
    }

    /** The Endless director, or null in a Classic run. */
    public EndlessDirector endless() {
        return run.director() instanceof EndlessDirector
                ? (EndlessDirector) run.director() : null;
    }
}
