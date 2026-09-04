package com.mymmer.castledefense.boss;

import com.badlogic.gdx.utils.Array;
import com.mymmer.castledefense.data.JsonSource;
import com.mymmer.castledefense.entity.EntityList;
import com.mymmer.castledefense.enemy.Enemy;
import com.mymmer.castledefense.enemy.EnemyType;
import com.mymmer.castledefense.enemy.TestEnemyWorld;
import com.mymmer.castledefense.interaction.CursorInteraction;
import com.mymmer.castledefense.interaction.PointerVelocity;
import com.mymmer.castledefense.input.Pointer;
import com.mymmer.castledefense.input.TestPointers;

/**
 * A world with bosses: the Phase 6 enemy world, plus the boss table, the dropped
 * item list, the registry and a cursor wired to all of it.
 *
 * <p>Composed rather than reimplemented — a boss <em>is</em> an enemy, so it must
 * be stepped by the same loop, appear in the same horde and take damage through
 * the same contracts. Anything a boss test proves about payout, targeting or
 * physics is therefore proving it against the real machinery.
 */
public final class TestBossWorld implements BossContext {

    public static final double DT = TestEnemyWorld.DT;

    public final TestEnemyWorld world;
    public final BossTable bosses;
    public final BossRegistry registry = new BossRegistry();
    public final EntityList<DroppedItem> items = new EntityList<>();
    public final CursorInteraction cursor;

    public double bossFireScale = 1d;
    public int bossDefeatedCount;
    public final Array<Boss> defeated = new Array<>();

    private float velocityX;
    private float velocityY;
    private Pointer pointer = TestPointers.at(0, 0f, 0f);

    public TestBossWorld() {
        this(TestEnemyWorld.SEED);
    }

    public TestBossWorld(long seed) {
        this.world = new TestEnemyWorld(seed);
        JsonSource json = new BossJson();
        this.bosses = BossTable.load(json);
        PointerVelocity velocity = (pointerId, out) -> {
            out[0] = velocityX;
            out[1] = velocityY;
        };
        this.cursor = new CursorInteraction(this, velocity);
        this.cursor.setDroppedItems(items);
        this.registry.addInteractionOwner(cursor);
        world.grabCapacity = cursor.grabCapacity();
    }

    // --- building -----------------------------------------------------------

    /** Summons a boss at a position, registering it exactly as the run would. */
    public Boss summon(BossType type, float x) {
        //  Python clears dead bosses before every summon, so a corpse is never
        //  on the field when a new one arrives.
        registry.purgeDead(world.horde, world.projectiles, items);
        Boss b = bosses.create(this, type, world.wave, x, null);
        world.horde.add(b);
        registry.register(b);
        return b;
    }

    public Boss summon(BossType type) {
        return summon(type, 900f);
    }

    // --- stepping -----------------------------------------------------------

    public void step(double dt) {
        world.step(dt);
        for (int i = 0; i < items.size(); i++) {
            DroppedItem it = items.get(i);
            if (it.isAlive()) {
                it.update(dt);
            }
        }
        items.sweep();
        registry.purgeDead(world.horde, world.projectiles, items);
    }

    public void steps(int n, double dt) {
        for (int i = 0; i < n; i++) {
            step(dt);
        }
    }

    /** The most recently defeated boss, or null. */
    public Boss bossDefeated() {
        return defeated.size == 0 ? null : defeated.peek();
    }

    public int liveItems() {
        int n = 0;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).isAlive()) {
                n++;
            }
        }
        return n;
    }

    /** Projectiles in flight owned by one uid. */
    public int projectilesOwnedBy(long uid) {
        int n = 0;
        for (int i = 0; i < world.projectiles.size(); i++) {
            if (world.projectiles.get(i).isAlive()
                    && world.projectiles.get(i).ownerUid() == uid) {
                n++;
            }
        }
        return n;
    }

    // --- the cursor ---------------------------------------------------------

    public void setVelocity(float vx, float vy) {
        this.velocityX = vx;
        this.velocityY = vy;
    }

    public boolean press(float worldX, float worldY) {
        pointer = TestPointers.at(0, worldX, worldY);
        return cursor.onWorldPress(pointer);
    }

    public void drag(float worldX, float worldY) {
        TestPointers.moveTo(pointer, worldX, worldY);
        cursor.update(DT, true, worldX, worldY);
    }

    public void release() {
        cursor.onWorldRelease(pointer);
    }

    public void cancel() {
        cursor.onWorldCancel(pointer);
    }

    /** The world position of a boss's gameplay regalia anchor. */
    public float[] anchorOf(Boss b) {
        float[] out = new float[2];
        b.regaliaAnchorInto(out);
        return out;
    }

    // --- BossContext (everything else delegates to the enemy world) ----------

    @Override
    public void addDroppedItem(DroppedItem item) {
        items.add(item);
    }

    /**
     * A boss died.
     *
     * <p><b>This is where the purge happens</b>, exactly as in Python
     * ({@code Enemy.die} → {@code game.on_boss_defeated} →
     * {@code game.purge_boss}). Doing it here rather than on the next step
     * matters: {@code releaseOwnedState} has just marked the boss's regalia
     * dead, and if the item list were swept before the purge walked it, the
     * cursor would never be told to let go and would be left carrying a corpse's
     * crown.
     */
    @Override
    public void onBossDefeated(Boss boss) {
        bossDefeatedCount++;
        defeated.add(boss);
        registry.purge(boss, world.horde, world.projectiles, items);
    }

    @Override
    public EnemyType[] summonableTypes() {
        //  Python: the wave's unlock pool minus Siege Rams and Necromancers,
        //  falling back to Skeletons.
        Array<EnemyType> pool = new Array<>();
        for (var u : world.enemies.unlocks()) {
            if (world.wave >= u.firstWave
                    && u.type != EnemyType.SIEGE_RAM && u.type != EnemyType.NECROMANCER) {
                pool.add(u.type);
            }
        }
        if (pool.size == 0) {
            pool.add(EnemyType.SKELETON);
        }
        return pool.toArray(EnemyType.class);
    }

    @Override
    public double bossFireScale() {
        return bossFireScale;
    }

    @Override
    public Boss createBoss(BossType type, int wave, Float x, Float y) {
        return bosses.create(this, type, wave, x, y);
    }

    @Override
    public EntityList<Enemy> horde() {
        return world.horde();
    }

    @Override
    public Enemy createEnemy(EnemyType type, int wave, Float x, Float y) {
        return world.createEnemy(type, wave, x, y);
    }

    @Override
    public void spawnEnemy(Enemy enemy) {
        world.spawnEnemy(enemy);
    }

    @Override
    public int allyCount() {
        return world.allyCount();
    }

    @Override
    public com.mymmer.castledefense.enemy.FriendlySkeleton ally(int index) {
        return world.ally(index);
    }

    @Override
    public double gameTime() {
        return world.gameTime();
    }

    @Override
    public int bounceLevel() {
        return world.bounceLevel();
    }

    @Override
    public com.mymmer.castledefense.defence.SpikeWalls spikes() {
        return world.spikes();
    }

    @Override
    public float goldMultiplier() {
        return world.goldMultiplier();
    }

    @Override
    public void addGold(int amount) {
        world.addGold(amount);
    }

    @Override
    public void addKill() {
        world.addKill();
    }

    @Override
    public void addScore(int points, float x, float y, int hits, float combo) {
        world.addScore(points, x, y, hits, combo);
    }

    @Override
    public void addThrownDamage(float amount) {
        world.addThrownDamage(amount);
    }

    @Override
    public void addPlatesTorn() {
        world.addPlatesTorn();
    }

    @Override
    public boolean storm() {
        return world.storm();
    }

    @Override
    public void strikeLightning(Enemy enemy) {
        world.strikeLightning(enemy);
    }

    @Override
    public float enemySlow(Enemy enemy) {
        return world.enemySlow(enemy);
    }

    @Override
    public float enemyScale() {
        return world.enemyScale();
    }

    @Override
    public float enemyHpCurve() {
        return world.enemyHpCurve();
    }

    @Override
    public float enemySpeedScale() {
        return world.enemySpeedScale();
    }

    @Override
    public com.mymmer.castledefense.enemy.EndgameTier[] endgameTiers() {
        return world.endgameTiers();
    }

    @Override
    public int targetCount() {
        return world.targetCount();
    }

    @Override
    public com.mymmer.castledefense.defence.Target target(int index) {
        return world.target(index);
    }

    @Override
    public void removeFromHorde(com.mymmer.castledefense.defence.Target target) {
        world.removeFromHorde(target);
    }

    @Override
    public void addProjectile(com.mymmer.castledefense.defence.Projectile projectile) {
        world.addProjectile(projectile);
    }

    @Override
    public com.mymmer.castledefense.defence.Castle castle() {
        return world.castle();
    }

    @Override
    public com.mymmer.castledefense.defence.Barricade barricade() {
        return world.barricade();
    }

    @Override
    public com.mymmer.castledefense.defence.Outpost outpost() {
        return world.outpost();
    }

    @Override
    public float wind() {
        return world.wind();
    }

    @Override
    public int wave() {
        return world.wave();
    }

    @Override
    public float grabCapacity() {
        return world.grabCapacity();
    }

    @Override
    public com.mymmer.castledefense.util.Rng rng() {
        return world.rng();
    }

    @Override
    public com.mymmer.castledefense.debug.SimulationTrace trace() {
        return world.trace();
    }

    @Override
    public long step() {
        return world.step();
    }

    @Override
    public com.mymmer.castledefense.defence.CombatModifiers modifiers() {
        return world.modifiers();
    }

    @Override
    public com.mymmer.castledefense.defence.AllyFactory allies() {
        return world.allies();
    }

    @Override
    public void onCastleDestroyed() {
        world.onCastleDestroyed();
    }
}
