package com.mymmer.castledefense.enemy;

import com.badlogic.gdx.utils.Array;
import com.mymmer.castledefense.debug.NoOpSimulationTrace;
import com.mymmer.castledefense.debug.SimulationTrace;
import com.mymmer.castledefense.defence.AllyFactory;
import com.mymmer.castledefense.defence.Barricade;
import com.mymmer.castledefense.defence.Castle;
import com.mymmer.castledefense.defence.CombatModifiers;
import com.mymmer.castledefense.defence.DefenceTable;
import com.mymmer.castledefense.defence.Outpost;
import com.mymmer.castledefense.defence.Projectile;
import com.mymmer.castledefense.defence.SpikeWalls;
import com.mymmer.castledefense.defence.Target;
import com.mymmer.castledefense.entity.EntityList;
import com.mymmer.castledefense.game.Simulation;
import com.mymmer.castledefense.util.Rng;

/**
 * A world for the enemy tests: the full {@link EnemyContext}, the Phase 5
 * structures, and a step that advances everything in the game-loop order.
 *
 * <p>Every test seeds the RNG explicitly. Phase 6 draws from it constantly —
 * spawn position, depth, fly altitude, attack timer, cloak and dash timers,
 * summon jitter, multi-grab release scatter, wave composition — so an unseeded
 * test would be flaky in exactly the places that matter most.
 */
public final class TestEnemyWorld implements EnemyContext {

    public static final long SEED = 20260906L;
    public static final float DT = Simulation.DT;

    public final Rng rng;
    public final EnemyTable enemies;
    public final DefenceTable defences;
    public final EntityList<Enemy> horde = new EntityList<>();
    public final EntityList<Projectile> projectiles = new EntityList<>();
    public final Array<FriendlySkeleton> allies = new Array<>();
    public final Castle castle;
    public final Barricade barricade;
    public final Outpost outpost;
    public final SpikeWalls spikes;
    public final CrowdSeparation separation = new CrowdSeparation();
    public final WaveComposition composition;

    public CombatModifiers modifiers = CombatModifiers.NONE;
    public SimulationTrace trace = NoOpSimulationTrace.INSTANCE;
    public float wind;
    public int wave = 1;
    public float grabCapacity = 99f;
    public int bounceLevel;
    public long step;
    public double gameTime;
    public boolean storm;

    // difficulty
    public float enemyScale = 1f;
    public float enemyHpCurve = 1f;
    public float enemySpeedScale = 1f;

    // slow hook
    public float slowFactor = 1f;

    // accounting
    public int gold;
    public int kills;
    public int score;
    public int scoreEvents;
    public float thrownDamage;
    public int platesTorn;
    public int lightningStrikes;
    public int castleDestroyedCount;

    public TestEnemyWorld() {
        this(SEED);
    }

    public TestEnemyWorld(long seed) {
        this.rng = new Rng(seed);
        EnemyJson json = new EnemyJson();
        this.enemies = EnemyTable.load(json);
        this.defences = DefenceTable.load(json);
        this.composition = new WaveComposition(enemies);
        this.castle = new Castle(this, defences);
        this.barricade = new Barricade(this);
        this.outpost = new Outpost(this);
        this.spikes = new SpikeWalls(this);
    }

    // --- building -----------------------------------------------------------

    /** Spawns an enemy at an exact position. Most tests want deterministic x. */
    public Enemy spawn(EnemyType type, float x) {
        Enemy e = enemies.create(this, type, wave, x, null);
        horde.add(e);
        return e;
    }

    /**
     * Spawns a mob whose depth is close to a given one.
     *
     * <p>Depth is a random lateral offset, and several mechanics (crowd
     * separation, ally blocking) only apply within a depth band. Rather than
     * exposing a setter that production code would then be able to call, this
     * draws until it gets a match — deterministic under a seed, and it leaves
     * the field final.
     */
    public Enemy spawnAtDepth(EnemyType type, float x, float targetDepth, float tolerance) {
        for (int i = 0; i < 500; i++) {
            Enemy e = enemies.create(this, type, wave, x, null);
            if (Math.abs(e.depth() - targetDepth) <= tolerance) {
                horde.add(e);
                return e;
            }
        }
        throw new IllegalStateException("no " + type.id() + " drew a depth within "
                + tolerance + " of " + targetDepth);
    }

    /** As {@link #spawnAtDepth}, for an ally. */
    public FriendlySkeleton spawnAllyAtDepth(float x, float targetDepth, float tolerance) {
        for (int i = 0; i < 500; i++) {
            FriendlySkeleton a = new FriendlySkeleton(this, wave, x, null);
            if (Math.abs(a.depth() - targetDepth) <= tolerance) {
                allies.add(a);
                return a;
            }
        }
        throw new IllegalStateException("no ally drew a depth within " + tolerance);
    }

    public Enemy spawn(EnemyType type, float x, float y) {
        Enemy e = enemies.create(this, type, wave, x, y);
        horde.add(e);
        return e;
    }

    /** Builds without adding, for constructor-only assertions. */
    public Enemy build(EnemyType type, int atWave) {
        return enemies.create(this, type, atWave, 900f, null);
    }

    public FriendlySkeleton spawnAlly(float x) {
        FriendlySkeleton a = new FriendlySkeleton(this, wave, x, null);
        allies.add(a);
        return a;
    }

    // --- stepping -----------------------------------------------------------

    /** One step, in the order the real game loop uses. */
    public void step(float dt) {
        step++;
        gameTime += dt;
        castle.update(dt);
        outpost.update(dt);
        barricade.update(dt);
        for (int i = 0; i < projectiles.size(); i++) {
            Projectile p = projectiles.get(i);
            if (p.isAlive()) {
                p.update(dt);
            }
        }
        projectiles.sweep();
        separation.separate(this, dt);
        try (EntityList<Enemy>.Snapshot snap = horde.beginSnapshot()) {
            for (int i = 0; i < snap.size(); i++) {
                Enemy e = snap.get(i);
                if (e.isAlive()) {
                    e.update(dt);
                }
            }
        }
        for (int i = 0; i < allies.size; i++) {
            allies.get(i).update(dt);
        }
        for (int i = allies.size - 1; i >= 0; i--) {
            if (!allies.get(i).alive()) {
                allies.removeIndex(i);
            }
        }
        horde.sweep();
    }

    public void steps(int n, float dt) {
        for (int i = 0; i < n; i++) {
            step(dt);
        }
    }

    public int liveEnemies() {
        int n = 0;
        for (int i = 0; i < horde.size(); i++) {
            if (horde.get(i).isAlive()) {
                n++;
            }
        }
        return n;
    }

    // --- DefenceContext -----------------------------------------------------

    @Override
    public int targetCount() {
        return horde.size();
    }

    @Override
    public Target target(int index) {
        return horde.get(index);
    }

    @Override
    public void removeFromHorde(Target target) {
        if (target instanceof Enemy) {
            horde.remove((Enemy) target);
        }
    }

    @Override
    public void addProjectile(Projectile projectile) {
        projectiles.add(projectile);
    }

    @Override
    public Castle castle() {
        return castle;
    }

    @Override
    public Barricade barricade() {
        return barricade;
    }

    @Override
    public Outpost outpost() {
        return outpost;
    }

    @Override
    public float wind() {
        return wind;
    }

    @Override
    public int wave() {
        return wave;
    }

    @Override
    public float grabCapacity() {
        return grabCapacity;
    }

    @Override
    public Rng rng() {
        return rng;
    }

    @Override
    public SimulationTrace trace() {
        return trace;
    }

    @Override
    public long step() {
        return step;
    }

    @Override
    public CombatModifiers modifiers() {
        return modifiers;
    }

    @Override
    public AllyFactory allies() {
        return allyFactory;
    }

    @Override
    public void onCastleDestroyed() {
        castleDestroyedCount++;
    }

    private final AllyFactory allyFactory = new AllyFactory() {
        @Override
        public boolean spawnAlly(float x, float y) {
            allies.add(new FriendlySkeleton(TestEnemyWorld.this, wave, x, null));
            return true;
        }

        @Override
        public int allyCount() {
            return allies.size;
        }
    };

    // --- EnemyContext -------------------------------------------------------

    @Override
    public EntityList<Enemy> horde() {
        return horde;
    }

    @Override
    public Enemy createEnemy(EnemyType type, int atWave, Float x, Float y) {
        return enemies.create(this, type, atWave, x, y);
    }

    @Override
    public void spawnEnemy(Enemy enemy) {
        horde.add(enemy);
    }

    @Override
    public int allyCount() {
        return allies.size;
    }

    @Override
    public FriendlySkeleton ally(int index) {
        return allies.get(index);
    }

    @Override
    public double gameTime() {
        return gameTime;
    }

    @Override
    public int bounceLevel() {
        return bounceLevel;
    }

    @Override
    public SpikeWalls spikes() {
        return spikes;
    }

    @Override
    public float goldMultiplier() {
        //  Python: 1 + POP_GOLD_STEP * max(0, aliveCount - POP_GOLD_FREE), capped.
        //  Counts every living mob INCLUDING the one currently dying.
        int n = liveEnemies();
        float step = com.mymmer.castledefense.config.GameConfig.POP_GOLD_STEP
                * modifiers.goldPop();
        return Math.min(com.mymmer.castledefense.config.GameConfig.POP_GOLD_CAP
                        * modifiers.goldPop(),
                1f + step * Math.max(0, n
                        - com.mymmer.castledefense.config.GameConfig.POP_GOLD_FREE))
                * modifiers.killGold();
    }

    @Override
    public void addGold(int amount) {
        gold += amount;
    }

    @Override
    public void addKill() {
        kills++;
    }

    @Override
    public void addScore(int points, float x, float y, int hits, float combo) {
        score += points;
        scoreEvents++;
    }

    @Override
    public void addThrownDamage(float amount) {
        thrownDamage += amount;
    }

    @Override
    public void addPlatesTorn() {
        platesTorn++;
    }

    @Override
    public boolean storm() {
        return storm;
    }

    @Override
    public void strikeLightning(Enemy enemy) {
        lightningStrikes++;
    }

    @Override
    public float enemySlow(Enemy enemy) {
        return slowFactor;
    }

    @Override
    public float enemyScale() {
        return enemyScale;
    }

    @Override
    public float enemyHpCurve() {
        return enemyHpCurve;
    }

    @Override
    public float enemySpeedScale() {
        return enemySpeedScale;
    }

    @Override
    public EndgameTier[] endgameTiers() {
        return enemies.tiers();
    }
}
