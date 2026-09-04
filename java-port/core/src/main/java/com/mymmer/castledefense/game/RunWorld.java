package com.mymmer.castledefense.game;

import com.badlogic.gdx.utils.Array;
import com.mymmer.castledefense.boss.Boss;
import com.mymmer.castledefense.boss.BossContext;
import com.mymmer.castledefense.boss.BossRegistry;
import com.mymmer.castledefense.boss.BossTable;
import com.mymmer.castledefense.boss.BossType;
import com.mymmer.castledefense.boss.DroppedItem;
import com.mymmer.castledefense.config.DifficultyConfig;
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
import com.mymmer.castledefense.enemy.CrowdSeparation;
import com.mymmer.castledefense.enemy.EndgameTier;
import com.mymmer.castledefense.enemy.Enemy;
import com.mymmer.castledefense.enemy.EnemyTable;
import com.mymmer.castledefense.enemy.EnemyType;
import com.mymmer.castledefense.enemy.FriendlySkeleton;
import com.mymmer.castledefense.enemy.WaveComposition;
import com.mymmer.castledefense.entity.EntityList;
import com.mymmer.castledefense.interaction.CursorInteraction;
import com.mymmer.castledefense.interaction.PointerVelocity;
import com.mymmer.castledefense.progress.Announcements;
import com.mymmer.castledefense.progress.DirectorContext;
import com.mymmer.castledefense.progress.EndlessDirector;
import com.mymmer.castledefense.progress.RunDirector;
import com.mymmer.castledefense.progress.RunSession;
import com.mymmer.castledefense.progress.ScreenShake;
import com.mymmer.castledefense.progress.Scoring;
import com.mymmer.castledefense.progress.TalentIncome;
import com.mymmer.castledefense.progress.WaveDirector;
import com.mymmer.castledefense.progress.Weather;
import com.mymmer.castledefense.util.Rng;
import java.util.Locale;

/**
 * A run, assembled: the field, the fight and the pacing, in one place.
 *
 * <p>{@link GameWorld} owns the clock, the state and the entity lifecycle.
 * {@code RunWorld} owns everything a run needs on top of that — the castle and
 * its outbuildings, the horde, the projectiles, the cursor, and the
 * {@link RunDirector} that paces the mode. It is the production implementation
 * of the three context seams the earlier phases were written against
 * ({@code DefenceContext} → {@code EnemyContext} → {@code BossContext}) plus the
 * Phase 8 {@link DirectorContext}, so nothing below it had to change to be run
 * for real.
 *
 * <h2>It is not the Python {@code Game} object</h2>
 *
 * <p>The scoreboard, the purse, the tier and the horn live in {@link RunSession};
 * the pacing lives in a director; the weather, the shake and the banners are
 * their own small objects. What is left here is genuinely the world: the things
 * that occupy space and have to be stepped in a particular order.
 *
 * <h2>The step order is Python's</h2>
 *
 * <pre>
 *   ALWAYS     shake, flashes, banners           (every state, via AlwaysListener)
 *   PLAYING    cursor
 *              director  (Endless timetable / Classic spawn queue)
 *              castle, outpost, barricade
 *              dropped items
 *              enemies    (a SNAPSHOT: trapping and raising change the roster)
 *              allies
 *              crowd separation
 *              projectiles
 *              sweep
 *              dangling-reference cleanup
 *              wave-clear check                  (Classic only)
 * </pre>
 *
 * <p>The order is observable — separation mutates x mid-traversal and projectiles
 * resolve against wherever the mobs ended up — so it is transcribed rather than
 * tidied.
 */
public final class RunWorld implements BossContext, DirectorContext, AllyFactory,
        GameWorld.StepListener, GameWorld.AlwaysListener {

    private final GameWorld world;
    private final Rng rng;
    private final EnemyTable enemyTable;
    private final DefenceTable defenceTable;
    private final BossTable bossTable;
    private final WaveComposition composition;
    private final CombatModifiers mods;

    // --- the field ----------------------------------------------------------
    private final EntityList<Enemy> horde = new EntityList<>();
    private final EntityList<Projectile> projectiles = new EntityList<>();
    private final EntityList<DroppedItem> items = new EntityList<>();
    private final Array<FriendlySkeleton> allies = new Array<>();
    private final Castle castle;
    private final Barricade barricade;
    private final Outpost outpost;
    private final SpikeWalls spikes;
    private final CrowdSeparation separation = new CrowdSeparation();
    private final BossRegistry bosses = new BossRegistry();
    private final CursorInteraction cursor;

    // --- the run ------------------------------------------------------------
    private final RunSession session;
    private final Weather weather;
    private final ScreenShake shake = new ScreenShake();
    private final Announcements banners = new Announcements();
    private TalentIncome talents = new TalentIncome.Counter();
    private RunDirector director;

    private DifficultyConfig difficulty;

    // --- cursor plumbing ----------------------------------------------------
    private boolean pointerDown;
    private float pointerX;
    private float pointerY;

    public RunWorld(GameWorld world, EnemyTable enemyTable, DefenceTable defenceTable,
                    BossTable bossTable, CombatModifiers mods, PointerVelocity velocity) {
        if (world == null || enemyTable == null || defenceTable == null || bossTable == null) {
            throw new IllegalArgumentException("world and tables must not be null");
        }
        this.world = world;
        this.rng = world.rng();
        this.enemyTable = enemyTable;
        this.defenceTable = defenceTable;
        this.bossTable = bossTable;
        this.mods = mods != null ? mods : CombatModifiers.NONE;
        this.composition = new WaveComposition(enemyTable);

        this.session = new RunSession(this.mods);
        this.weather = new Weather(rng, this.mods, shake, world.trace());

        this.castle = new Castle(this, defenceTable);
        this.barricade = new Barricade(this);
        this.outpost = new Outpost(this);
        this.spikes = new SpikeWalls(this);

        this.cursor = new CursorInteraction(this,
                velocity != null ? velocity : (pointerId, out) -> {
                    out[0] = 0f;
                    out[1] = 0f;
                });
        this.cursor.setDroppedItems(items);
        this.bosses.addInteractionOwner(cursor);

        world.setStepListener(this);
        world.setAlwaysListener(this);
        setTrace(world.trace());
    }

    /** Wires the trace through everything that reports. */
    public void setTrace(SimulationTrace trace) {
        SimulationTrace t = trace != null ? trace : NoOpSimulationTrace.INSTANCE;
        world.setTrace(t);
        session.setTrace(t);
        weather.setTrace(t);
    }

    /** The sink talent points go to. Phase 9 replaces the counter with the tree. */
    public void setTalentIncome(TalentIncome income) {
        this.talents = income != null ? income : new TalentIncome.Counter();
    }

    // ========================================================================
    //  Run lifecycle
    // ========================================================================

    /**
     * Starts a run.
     *
     * <p>Clears the field, resets the session, builds the mode's director and
     * lets it begin. The seed is the world's, so a run is reproducible from the
     * one number the crash log already carries.
     */
    public long beginRun(GameMode mode, DifficultyConfig difficulty) {
        long seed = world.beginRun(mode);
        startRun(mode, difficulty, seed);
        return seed;
    }

    /** Starts a run on an explicit seed. Used by tests and by a bug report. */
    public void beginRun(GameMode mode, DifficultyConfig difficulty, long seed) {
        world.beginRun(mode, seed);
        startRun(mode, difficulty, seed);
    }

    private void startRun(GameMode mode, DifficultyConfig difficulty, long seed) {
        this.difficulty = difficulty;
        clearField();
        session.begin(mode, difficulty, seed);
        weather.reset();
        shake.reset();
        banners.clear();
        castle.restoreTowers();
        cursor.setGrabCooldown(grabCooldownSeconds());

        director = mode == GameMode.ENDLESS
                ? new EndlessDirector(this, mods, enemyTable)
                : new WaveDirector(this, mods);
        director.begin();
        world.setState(GameState.PLAYING);
    }

    private void clearField() {
        horde.clear();
        projectiles.clear();
        items.clear();
        allies.clear();
        bosses.clear();
        cursor.releaseEverything();
    }

    /** The difficulty's grab delay, after the Light Fingers talent. Time domain. */
    private double grabCooldownSeconds() {
        double base = difficulty != null
                ? difficulty.grabCd()
                : com.mymmer.castledefense.config.GameConfig.GRAB_COOLDOWN;
        return base * mods.grabCdScale();
    }

    // ========================================================================
    //  State transitions
    // ========================================================================

    /**
     * Opens the Endless armoury mid-fight — {@code main.py:1719
     * open_realtime_shop}.
     *
     * <p><b>This is a freeze, not a pause menu.</b> The state becomes
     * {@link GameState#SHOP}, which does not advance the world, so the run
     * clock, every enemy's position, the spawn timer, the tier ladder and the
     * boss timetable all stop together. The Python self-test asserts exactly
     * that by checking {@code play_time} and every enemy's x after 120 frames of
     * shopping.
     *
     * <p>Phase 10 draws the shop. What is here is the transition and the
     * cursor's release, which is the part with gameplay consequences: a mob
     * being held when the shop opens is dropped rather than frozen in the air.
     *
     * @return false if a shop cannot be opened right now
     */
    public boolean openRealtimeShop() {
        if (world.state() != GameState.PLAYING || !session.isEndless()) {
            return false;
        }
        cursor.releaseEverything();
        world.setState(GameState.SHOP);
        return true;
    }

    /** Leaves the shop and lets the world run again. */
    public boolean resumeFromShop() {
        if (world.state() != GameState.SHOP) {
            return false;
        }
        world.setState(GameState.PLAYING);
        return true;
    }

    /** Classic: the shop between waves has been closed; send in the next wave. */
    public boolean startNextWave() {
        if (session.isEndless() || !(director instanceof WaveDirector)) {
            return false;
        }
        world.setState(GameState.PLAYING);
        ((WaveDirector) director).startWave();
        return true;
    }

    @Override
    public void onCastleDestroyed() {
        if (world.state() == GameState.GAMEOVER) {
            return;
        }
        cursor.releaseEverything();
        shake.add(14f);
        world.setState(GameState.GAMEOVER);
    }

    // ========================================================================
    //  The step
    // ========================================================================

    /** Tier 1: what ages in every state, including the frozen ones. */
    @Override
    public void onAlwaysStep(GameWorld w, double dt) {
        shake.decay(dt);
        session.ageFlashes(dt);
        weather.update(dt);
        banners.age(dt);
    }

    /** Tier 3: the world proper. Only reached while {@code PLAYING}. */
    @Override
    public void onStep(GameWorld w, double dt) {
        cursor.update(dt, pointerDown, pointerX, pointerY);

        if (director != null) {
            director.update(dt);
        }

        castle.update(dt);
        outpost.update(dt);
        barricade.update(dt);

        for (int i = 0; i < items.size(); i++) {
            DroppedItem it = items.get(i);
            if (it.isAlive()) {
                it.update(dt);
            }
        }
        items.sweep();

        //  A snapshot, not the live list: trapping removes an entry and a
        //  Necromancer or a Lich adds one, both mid-loop.
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

        separation.separate(this, dt);

        for (int i = 0; i < projectiles.size(); i++) {
            Projectile p = projectiles.get(i);
            if (p.isAlive()) {
                p.update(dt);
            }
        }
        projectiles.sweep();
        horde.sweep();

        //  a dead owner's regalia is scenery: drop it, including a piece the
        //  player is carrying
        for (int i = 0; i < items.size(); i++) {
            DroppedItem it = items.get(i);
            if (it.isAlive() && it.owner() != null && !it.owner().isAlive()) {
                it.markDead();
            }
        }
        items.sweep();
        cursor.dropDeadReferences();
        bosses.purgeDead(horde, projectiles, items);
    }

    // ========================================================================
    //  Pointer plumbing (the router calls these; gameplay never sees a device)
    // ========================================================================

    public CursorInteraction cursor() {
        return cursor;
    }

    public void setPointer(boolean down, float worldX, float worldY) {
        this.pointerDown = down;
        this.pointerX = worldX;
        this.pointerY = worldY;
    }

    // ========================================================================
    //  DirectorContext
    // ========================================================================

    @Override
    public int aliveEnemyCount() {
        int n = 0;
        for (int i = 0; i < horde.size(); i++) {
            if (horde.get(i).isAlive()) {
                n++;
            }
        }
        return n;
    }

    /**
     * Live hostiles, for the wave-clear gate.
     *
     * <p>The same count as {@link #aliveEnemyCount()}, and deliberately a
     * separate method: allies are not in {@code horde} at all, so the rule
     * "a FriendlySkeleton does not hold a wave open" is enforced by the roster's
     * structure rather than by a filter someone could later remove.
     */
    @Override
    public int aliveHostileCount() {
        return aliveEnemyCount();
    }

    @Override
    public Enemy spawnEnemy(EnemyType type, int wave) {
        Enemy e = enemyTable.create(this, type, wave, null, null);
        spawnEnemy(e);
        return e;
    }

    @Override
    public Enemy summonBoss(BossType type, int wave) {
        //  Python clears the previous boss's remains before the new one exists,
        //  so nothing from its first appearance can reach its second.
        bosses.purgeDead(horde, projectiles, items);
        Boss b = bossTable.create(this, type, Math.max(1, wave), null, null);
        if (b == null) {
            return null;
        }
        horde.add(b);
        bosses.register(b);
        return b;
    }

    @Override
    public boolean bossAlive() {
        return bosses.anyLive();
    }

    @Override
    public void restoreTowers() {
        castle.restoreTowers();
    }

    @Override
    public void clearBetweenWaves() {
        projectiles.clear();
        items.clear();
        cursor.releaseEverything();
    }

    @Override
    public void enterShop() {
        world.setState(GameState.SHOP);
    }

    @Override
    public RunSession session() {
        return session;
    }

    @Override
    public Weather weather() {
        return weather;
    }

    @Override
    public Announcements banners() {
        return banners;
    }

    @Override
    public ScreenShake shake() {
        return shake;
    }

    @Override
    public TalentIncome talents() {
        return talents;
    }

    @Override
    public WaveComposition composition() {
        return composition;
    }

    @Override
    public EndgameTier[] endgameTiers() {
        return enemyTable.tiers();
    }

    @Override
    public Array<EnemyTable.UnlockEntry> unlocks() {
        return enemyTable.unlocks();
    }

    @Override
    public float bossHeadstart() {
        return difficulty != null ? difficulty.headstart() : 0f;
    }

    @Override
    public boolean eliteHorn() {
        return difficulty != null && difficulty.eliteHorn();
    }

    // ========================================================================
    //  BossContext / EnemyContext / DefenceContext
    // ========================================================================

    @Override
    public void addDroppedItem(DroppedItem item) {
        items.add(item);
    }

    @Override
    public void onBossDefeated(Boss boss) {
        bosses.purge(boss, horde, projectiles, items);
    }

    @Override
    public EnemyType[] summonableTypes() {
        Array<EnemyType> pool = new Array<>(false, 8);
        int wave = session.wave();
        for (EnemyTable.UnlockEntry u : enemyTable.unlocks()) {
            if (wave >= u.firstWave
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
        return difficulty != null ? difficulty.bossFire() : 1d;
    }

    @Override
    public Boss createBoss(BossType type, int wave, Float x, Float y) {
        return bossTable.create(this, type, wave, x, y);
    }

    @Override
    public EntityList<Enemy> horde() {
        return horde;
    }

    @Override
    public Enemy createEnemy(EnemyType type, int wave, Float x, Float y) {
        return enemyTable.create(this, type, wave, x, y);
    }

    @Override
    public void spawnEnemy(Enemy enemy) {
        if (enemy == null) {
            return;
        }
        if (enemy.isBoss()) {
            bosses.purgeDead(horde, projectiles, items);
        }
        horde.add(enemy);
        if (enemy instanceof Boss) {
            bosses.register((Boss) enemy);
        }
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
        return world.simulationTime();
    }

    @Override
    public int bounceLevel() {
        return bounceLevel;
    }

    private int bounceLevel;

    /** Bought in the shop. Phase 9 drives it; the field exists now so throws work. */
    public void setBounceLevel(int level) {
        this.bounceLevel = level;
    }

    @Override
    public SpikeWalls spikes() {
        return spikes;
    }

    @Override
    public float goldMultiplier() {
        return session.goldMultiplier(aliveEnemyCount());
    }

    @Override
    public void addGold(int amount) {
        session.addGold(amount);
    }

    @Override
    public void addKill() {
        session.addKill();
    }

    @Override
    public void addScore(int points, float x, float y, int hits, float combo) {
        session.addScore(points, hits, combo);
    }

    @Override
    public void addThrownDamage(float amount) {
        session.addThrownDamage(amount);
    }

    @Override
    public void addPlatesTorn() {
        session.addPlatesTorn();
    }

    @Override
    public boolean storm() {
        return weather.storm();
    }

    @Override
    public void strikeLightning(Enemy enemy) {
        weather.strike(enemy);
    }

    @Override
    public float enemySlow(Enemy enemy) {
        //  main.py:1470 enemy_slow -- Gale while a strong headwind blows, Grave
        //  Chill while an attacking mob is being held up by allies.
        float slow = 1f;
        float gale = mods.stormWindSlow();
        if (gale > 0f && weather.strongHeadwind()) {
            slow *= (1f - gale);
        }
        float chill = mods.graveChill();
        if (chill > 0f && allies.size > 0
                && enemy.state() == com.mymmer.castledefense.enemy.EnemyState.ATTACK) {
            slow *= (1f - Math.min(0.6f, chill));
        }
        return slow;
    }

    @Override
    public float enemyScale() {
        return difficulty != null ? difficulty.scale() : 1f;
    }

    @Override
    public float enemyHpCurve() {
        return difficulty != null ? difficulty.hpCurve() : 1f;
    }

    @Override
    public float enemySpeedScale() {
        return difficulty != null ? difficulty.speed() : 1f;
    }

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
        if (projectile != null) {
            projectiles.add(projectile);
        }
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
        return weather.wind();
    }

    @Override
    public int wave() {
        return session.wave();
    }

    @Override
    public float grabCapacity() {
        return cursor.grabCapacity();
    }

    @Override
    public Rng rng() {
        return rng;
    }

    @Override
    public SimulationTrace trace() {
        return world.trace();
    }

    @Override
    public long step() {
        return world.stepCount();
    }

    @Override
    public CombatModifiers modifiers() {
        return mods;
    }

    @Override
    public AllyFactory allies() {
        return this;
    }

    // --- AllyFactory --------------------------------------------------------

    @Override
    public boolean spawnAlly(float x, float y) {
        allies.add(new FriendlySkeleton(this, session.wave(), x, y));
        return true;
    }

    // ========================================================================
    //  Accessors and diagnostics
    // ========================================================================

    public GameWorld world() {
        return world;
    }

    public RunDirector director() {
        return director;
    }

    public BossRegistry bossRegistry() {
        return bosses;
    }

    public EntityList<Projectile> projectiles() {
        return projectiles;
    }

    public EntityList<DroppedItem> droppedItems() {
        return items;
    }

    public Announcements announcements() {
        return banners;
    }

    public ScreenShake screenShake() {
        return shake;
    }

    /**
     * Everything needed to reproduce this run from a bug report.
     *
     * <p>Built on demand and never logged per frame. A crash log, a debug
     * overlay or a test failure calls it; nothing else does.
     */
    public String describeRun() {
        return "state=" + world.state()
                + " step=" + world.stepCount()
                + " simTime=" + String.format(Locale.ROOT, "%.2f", world.simulationTime()) + "s"
                + " seed=" + world.runSeed()
                + " | " + session.describe()
                + " | alive=" + aliveEnemyCount() + "/" + horde.size()
                + " projectiles=" + projectiles.size()
                + " items=" + items.size()
                + " allies=" + allies.size
                + " bosses=" + bosses.liveCount()
                + " | " + weather.describe()
                + " shake=" + String.format(Locale.ROOT, "%.1f", shake.amount())
                + " | " + (director != null ? director.describe() : "no director");
    }
}
