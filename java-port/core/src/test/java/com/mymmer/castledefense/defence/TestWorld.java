package com.mymmer.castledefense.defence;

import com.badlogic.gdx.utils.Array;
import com.mymmer.castledefense.debug.NoOpSimulationTrace;
import com.mymmer.castledefense.debug.SimulationTrace;
import com.mymmer.castledefense.entity.EntityList;
import com.mymmer.castledefense.util.Rng;

/**
 * A minimal world for the defence tests: the {@link DefenceContext} seam, the
 * three structures, and a stepper that advances them in the game-loop order.
 *
 * <p>Every test seeds the RNG explicitly. Two things in Phase 5 draw from it —
 * a tower's initial cooldown jitter and crit rolls — so an unseeded test would
 * be flaky in exactly the places that matter.
 */
final class TestWorld implements DefenceContext {

    static final long SEED = 20260902L;

    final Rng rng;
    final Array<Target> targets = new Array<>();
    final EntityList<Projectile> projectiles = new EntityList<>();
    final Castle castle;
    final Barricade barricade;
    final Outpost outpost;
    final SpikeWalls spikes;
    final DefenceTable table;

    CombatModifiers modifiers = CombatModifiers.NONE;
    SimulationTrace trace = NoOpSimulationTrace.INSTANCE;
    CountingAllyFactory allies = new CountingAllyFactory();
    float wind;
    int wave = 1;
    float grabCapacity = 99f;
    long step;
    int castleDestroyedCount;

    TestWorld() {
        this(SEED);
    }

    TestWorld(long seed) {
        this.rng = new Rng(seed);
        this.table = DefenceTable.load(new DefenceJson());
        this.castle = new Castle(this, table);
        this.barricade = new Barricade(this);
        this.outpost = new Outpost(this);
        this.spikes = new SpikeWalls(this);
    }

    /** Adds a target to the horde, preserving insertion order. */
    FakeTarget add(FakeTarget t) {
        targets.add(t);
        return t;
    }

    /** One simulation step, in the order the real game loop uses. */
    void step(float dt) {
        step++;
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
    }

    void steps(int n, float dt) {
        for (int i = 0; i < n; i++) {
            step(dt);
        }
    }

    int liveProjectiles() {
        int n = 0;
        for (int i = 0; i < projectiles.size(); i++) {
            if (projectiles.get(i).isAlive()) {
                n++;
            }
        }
        return n;
    }

    Projectile lastProjectile() {
        return projectiles.size() == 0 ? null : projectiles.get(projectiles.size() - 1);
    }

    // --- DefenceContext -----------------------------------------------------

    @Override
    public int targetCount() {
        return targets.size;
    }

    @Override
    public Target target(int index) {
        return targets.get(index);
    }

    @Override
    public void removeFromHorde(Target target) {
        targets.removeValue(target, true);
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
        return allies;
    }

    @Override
    public void onCastleDestroyed() {
        castleDestroyedCount++;
    }

    /** Counts raises without knowing what an ally is — the Phase 6 seam, faked. */
    static final class CountingAllyFactory implements AllyFactory {
        int spawned;
        int alive;
        boolean refuse;

        @Override
        public boolean spawnAlly(float x, float y) {
            if (refuse) {
                return false;
            }
            spawned++;
            alive++;
            return true;
        }

        @Override
        public int allyCount() {
            return alive;
        }
    }
}
