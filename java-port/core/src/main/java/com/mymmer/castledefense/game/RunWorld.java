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
import com.mymmer.castledefense.shop.Shop;
import com.mymmer.castledefense.shop.ShopContext;
import com.mymmer.castledefense.shop.ShopTable;
import com.mymmer.castledefense.skill.FireZone;
import com.mymmer.castledefense.skill.SkillContext;
import com.mymmer.castledefense.skill.SkillId;
import com.mymmer.castledefense.skill.SkillPanel;
import com.mymmer.castledefense.skill.Tornado;
import com.mymmer.castledefense.talent.TalentTable;
import com.mymmer.castledefense.talent.TalentTree;
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
        ShopContext, SkillContext, GameWorld.StepListener, GameWorld.AlwaysListener {

    private final GameWorld world;
    private final Rng rng;
    private final EnemyTable enemyTable;
    private final DefenceTable defenceTable;
    private final BossTable bossTable;
    private final WaveComposition composition;

    /**
     * The talent tree, which <b>is</b> this run's {@link CombatModifiers}.
     *
     * <p>Every gameplay system already asks its context for
     * {@code modifiers().something()}; from Phase 9 the thing behind that call is
     * the tree. Nothing had to change below this line, and nothing outside this
     * field names {@code TalentTree}.
     */
    private final TalentTree talents;
    private final CombatModifiers mods;

    // --- the field ----------------------------------------------------------
    private final EntityList<Enemy> horde = new EntityList<>();
    private final EntityList<Projectile> projectiles = new EntityList<>();
    private final EntityList<DroppedItem> items = new EntityList<>();
    private final Array<FriendlySkeleton> allies = new Array<>();
    /**
     * The structures.
     *
     * <p><b>Not final:</b> Python's {@code Game.reset()} constructs a brand new
     * {@code Castle}, {@code Outpost}, {@code Barricade} and {@code SpikeWalls}
     * for every run, and so does {@link #startRun}. Rebuilding them rather than
     * clearing them by hand is what guarantees nothing is forgotten — a wall
     * level, a garrison, a prisoner, a tower still standing from the last
     * attempt. Everything downstream reads them through this context, so nothing
     * holds a stale reference.
     */
    private Castle castle;
    private Barricade barricade;
    private Outpost outpost;
    private SpikeWalls spikes;
    private final CrowdSeparation separation = new CrowdSeparation();
    private final BossRegistry bosses = new BossRegistry();
    private final CursorInteraction cursor;

    // --- skill effects, owned and stepped here -------------------------------
    private final Array<FireZone> fireZones = new Array<>(false, 32);
    private final Array<Tornado> tornados = new Array<>(false, 4);

    // --- the run ------------------------------------------------------------
    private final RunSession session;
    private final Weather weather;
    private final ScreenShake shake = new ScreenShake();
    private final Announcements banners = new Announcements();
    private final Shop shop;
    private final SkillPanel skills;
    private RunDirector director;

    private DifficultyConfig difficulty;
    /** Bought in the shop. Lives on the run rather than on any structure. */
    private int bounceLevel;

    // --- cursor plumbing ----------------------------------------------------
    private boolean pointerDown;
    private float pointerX;
    private float pointerY;

    public RunWorld(GameWorld world, EnemyTable enemyTable, DefenceTable defenceTable,
                    BossTable bossTable, TalentTable talentTable, ShopTable shopTable,
                    PointerVelocity velocity) {
        if (world == null || enemyTable == null || defenceTable == null
                || bossTable == null || talentTable == null || shopTable == null) {
            throw new IllegalArgumentException("world and tables must not be null");
        }
        this.world = world;
        this.rng = world.rng();
        this.enemyTable = enemyTable;
        this.defenceTable = defenceTable;
        this.bossTable = bossTable;
        this.talents = new TalentTree(talentTable);
        this.mods = talents;
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

        this.shop = new Shop(shopTable, this);
        this.skills = new SkillPanel(this);

        world.setStepListener(this);
        world.setAlwaysListener(this);
        setTrace(world.trace());
    }

    /**
     * Convenience for a run with no talents of its own.
     *
     * <p>Used by nothing in production -- it exists so a Phase 5-8 test can build
     * a world without a talent table and get the neutral defaults.
     */
    public static RunWorld withoutProgression(GameWorld world, EnemyTable enemies,
                                              DefenceTable defences, BossTable bosses,
                                              TalentTable talents, ShopTable shop,
                                              PointerVelocity velocity) {
        return new RunWorld(world, enemies, defences, bosses, talents, shop, velocity);
    }

    /** Wires the trace through everything that reports. */
    public void setTrace(SimulationTrace trace) {
        SimulationTrace t = trace != null ? trace : NoOpSimulationTrace.INSTANCE;
        world.setTrace(t);
        session.setTrace(t);
        weather.setTrace(t);
        talents.setTrace(t);
    }

    /** The talent tree. The UI reads it; gameplay goes through {@link #modifiers}. */
    public TalentTree talentTree() {
        return talents;
    }

    public Shop shop() {
        return shop;
    }

    public SkillPanel skills() {
        return skills;
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
        //  Progression is PER RUN.  Python rebuilds TalentTree and SkillPanel in
        //  Game.reset(); nothing here is persisted and nothing survives a new
        //  run.  See PERSISTENCE.md.
        //
        //  The structures are rebuilt for the same reason and in the same way
        //  the source does it: a fresh keep, a bare wall, no garrison, no
        //  barricade, no spikes.
        castle = new Castle(this, defenceTable);
        barricade = new Barricade(this);
        outpost = new Outpost(this);
        spikes = new SpikeWalls(this);
        talents.reset();
        shop.reset();
        skills.reset();
        bounceLevel = 0;
        cursor.setGrabLevel(0);
        cursor.setMultiLevel(0);
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
        fireZones.clear();
        tornados.clear();
        cursor.releaseEverything();
    }

    /**
     * Re-reads the grab delay after a talent purchase.
     *
     * <p>The cursor caches its cooldown because it is set once at run start and
     * read on every grab; Light Fingers is the one talent that changes it
     * mid-run, so the shop and the talent screen call this when a rank lands.
     * Everything else a talent touches is read live and needs no such call.
     */
    public void refreshGrabCooldown() {
        cursor.setGrabCooldown(grabCooldownSeconds());
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

    /**
     * Ends the run and goes back to the front end.
     *
     * <p>Python's {@code GAMEOVER} handler: {@code reset()} <b>then</b>
     * {@code state = MENU}, so nothing from the finished run is reachable from
     * the menu. Everything a run owns is cleared here, using the same path a
     * new run uses.
     */
    public void resetToMenu() {
        clearField();
        talents.reset();
        shop.reset();
        skills.reset();
        bounceLevel = 0;
        castle = new Castle(this, defenceTable);
        barricade = new Barricade(this);
        outpost = new Outpost(this);
        spikes = new SpikeWalls(this);
        cursor.setGrabLevel(0);
        cursor.setMultiLevel(0);
        session.begin(session.mode(), difficulty, session.seed());
        weather.reset();
        shake.reset();
        banners.clear();
        director = null;
        world.setState(GameState.MENU);
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

        skills.update(dt);
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

        //  Skill effects, in Python's position: after the allies and before the
        //  separation pass, so a mob a funnel just lifted is airborne by the
        //  time the crowd is untangled and is therefore not part of it.
        for (int i = 0; i < fireZones.size; i++) {
            fireZones.get(i).update(dt);
        }
        for (int i = fireZones.size - 1; i >= 0; i--) {
            if (!fireZones.get(i).alive()) {
                fireZones.removeIndex(i);
            }
        }
        for (int i = 0; i < tornados.size; i++) {
            tornados.get(i).update(dt);
        }
        for (int i = tornados.size - 1; i >= 0; i--) {
            if (!tornados.get(i).alive()) {
                tornados.removeIndex(i);
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

    /**
     * Where the pointer is, in gameplay world coordinates.
     *
     * <p>Read-only, for the cursor prompts. This is the value the simulation
     * itself works at -- sampled once per step from an <b>unshaken</b>
     * unprojection -- so a prompt drawn from it points at the same thing the
     * interaction will act on. See {@code INPUT.md}, "the coordinate contract".
     */
    public float pointerX() {
        return pointerX;
    }

    public float pointerY() {
        return pointerY;
    }

    public boolean pointerDown() {
        return pointerDown;
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

    /**
     * Where one-shot visual notices go.
     *
     * <p>{@link com.mymmer.castledefense.render.VisualEvents#NONE} until a
     * renderer attaches one, so the whole simulation runs headlessly and
     * behaves identically. Nothing gameplay does may depend on who is
     * listening: every method returns void and none can fail.
     */
    private com.mymmer.castledefense.render.VisualEvents visuals =
            com.mymmer.castledefense.render.VisualEvents.NONE;

    /** Attaches the presentation sink. One-way; gameplay never reads it back. */
    public void setVisualEvents(com.mymmer.castledefense.render.VisualEvents sink) {
        this.visuals = sink == null
                ? com.mymmer.castledefense.render.VisualEvents.NONE : sink;
        //  Weather holds its own reference: it is handed to enemies through
        //  EnemyContext and strikes from there, so it cannot reach back here.
        weather.setVisualEvents(this.visuals);
    }

    /** The sink, for the systems that emit through it. */
    @Override
    public com.mymmer.castledefense.render.VisualEvents visuals() {
        return visuals;
    }

    @Override
    public ScreenShake shake() {
        return shake;
    }

    /** {@code SkillContext}: the skill's white-out is the weather's own state. */
    @Override
    public void stormFlash() {
        weather.flash();
    }

    /**
     * Where a director's talent points go.
     *
     * <p>Phase 8 owns <em>when</em> a point is earned and this owns the balance.
     * The director never sees the tree, only the one-method sink.
     */
    @Override
    public TalentIncome talents() {
        return talents.asIncome();
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

    /**
     * A boss died.
     *
     * <p>Python {@code on_boss_defeated}: purge it, then light up the next skill
     * slot -- and when the bar is already full, pay a bigger talent bounty
     * instead. Two points for a slot, three for a boss that has nothing left to
     * unlock.
     */
    @Override
    public void onBossDefeated(Boss boss) {
        bosses.purge(boss, horde, projectiles, items);
        SkillId unlockedNow = skills.unlockNext();
        if (unlockedNow == null) {
            talents.award(3, "boss-bounty");
            return;
        }
        banners.post(Announcements.Id.SKILL_UNLOCKED, skills.unlockedCount(),
                unlockedNow.id(), 5.0);
        talents.award(2, "boss-bounty");
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

    // ========================================================================
    //  ShopContext
    // ========================================================================

    @Override
    public int bounceLevel() {
        return bounceLevel;
    }

    @Override
    public void setBounceLevel(int level) {
        this.bounceLevel = Math.max(0, level);
    }

    @Override
    public int grabLevel() {
        return cursor.grabLevel();
    }

    @Override
    public void setGrabLevel(int level) {
        cursor.setGrabLevel(level);
    }

    @Override
    public int multiLevel() {
        return cursor.multiLevel();
    }

    @Override
    public void setMultiLevel(int level) {
        cursor.setMultiLevel(level);
    }

    // ========================================================================
    //  SkillContext
    // ========================================================================

    @Override
    public com.mymmer.castledefense.defence.DefenceContext projectileContext() {
        return this;
    }

    @Override
    public void addFireZone(FireZone zone) {
        if (zone != null) {
            fireZones.add(zone);
        }
    }

    @Override
    public void addTornado(Tornado tornado) {
        if (tornado != null) {
            tornados.add(tornado);
        }
    }

    @Override
    public void onSkillCast(SkillId id) {
        session.addCast();
    }

    /** Burning ground currently on the field. */
    public Array<FireZone> fireZones() {
        return fireZones;
    }

    /** Funnels currently on the field. */
    public Array<Tornado> tornados() {
        return tornados;
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
                + " fire=" + fireZones.size + " tornados=" + tornados.size
                + " | " + talents.describe()
                + " | " + skills.describe()
                + " | " + weather.describe()
                + " shake=" + String.format(Locale.ROOT, "%.1f", shake.amount())
                + " | " + (director != null ? director.describe() : "no director");
    }
}
