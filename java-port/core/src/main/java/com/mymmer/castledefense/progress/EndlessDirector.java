package com.mymmer.castledefense.progress;

import com.badlogic.gdx.utils.Array;
import com.mymmer.castledefense.boss.BossType;
import com.mymmer.castledefense.config.Tuning;
import com.mymmer.castledefense.debug.TraceEvent;
import com.mymmer.castledefense.defence.CombatModifiers;
import com.mymmer.castledefense.enemy.Enemy;
import com.mymmer.castledefense.enemy.EnemyTable;
import com.mymmer.castledefense.enemy.EnemyType;
import com.mymmer.castledefense.enemy.WaveComposition;
import com.mymmer.castledefense.util.Rng;
import java.util.Locale;

/**
 * Endless mode: a clock, and a horde that never stops.
 *
 * <p>There are no waves. The run clock drives everything, and the run only ends
 * when the castle does.
 *
 * <h2>The timetable, from {@code main.py}'s tuning block</h2>
 *
 * <pre>
 *   tier            1 + int(playTime / 30)
 *   spawn gap       lerp(1.70, 0.38, clamp(playTime / 300, 0, 1))
 *                       * uniform(1 - 0.28, 1 + 0.28)
 *   alive cap       60          (Classic's is 58 -- they differ)
 *   bosses          120 s Troll King, 240 s Dragon, 360 s Lich Lord,
 *                   then one RANDOM boss every further 120 s
 *   talent income   +1 per 60 s survived
 * </pre>
 *
 * <h2>Four details that are easy to get wrong</h2>
 *
 * <ol>
 *   <li><b>The ramp is driven by {@code playTime}, not by tier.</b> The gap
 *       tightens smoothly and continuously; it does not step at tier
 *       boundaries.</li>
 *   <li><b>The scripted bosses are a {@code while}, not an {@code if}.</b> A run
 *       that somehow jumped past two scheduled times summons both, in order.</li>
 *   <li><b>The repeat schedule counts from the last <em>scripted</em> time</b>,
 *       not from the last boss: {@code due = int((playTime - 360) / 120)}, and a
 *       boss is summoned whenever {@code due} exceeds the number already sent.
 *       So the first repeat lands at 480 s, not 480 s after the Lich died.</li>
 *   <li><b>The talent-second counter subtracts rather than resetting.</b>
 *       {@code talent_seconds -= 60} keeps the remainder, so 100 minutes of play
 *       awards exactly 100 points with no drift. With a float counter it would
 *       not; with the double clock it does.</li>
 * </ol>
 *
 * <h2>The realtime shop</h2>
 *
 * <p>Nothing here needs to know about it. {@link #update} is only called while
 * the world advances, so opening the shop stops the run clock, the tier ladder,
 * the boss timetable, the spawn gap and the talent drip in one move — which is
 * exactly what the Python self-test asserts by checking that {@code play_time}
 * and every enemy's x are unchanged after 120 frames of shopping.
 */
public final class EndlessDirector implements RunDirector {

    /** {@code main.py:241}. Deliberately different from Classic's 58. */
    public static final int MAX_ALIVE = Tuning.ENDLESS_MAX_ALIVE;
    /** The very first mob of a run arrives after this. */
    public static final double FIRST_SPAWN_DELAY = 1.2;

    private final DirectorContext ctx;
    private final CombatModifiers mods;
    private final EnemyTable table;

    private double spawnTimer;
    private int talentPointsAwarded;
    private int nextScriptedBoss;
    private int extraBosses;

    /** Scratch, reused: the weighted pool for one Endless roll. */
    private final Array<EnemyType> pool = new Array<>(false, 16);
    private final com.badlogic.gdx.utils.FloatArray weights =
            new com.badlogic.gdx.utils.FloatArray(16);

    public EndlessDirector(DirectorContext ctx, CombatModifiers mods, EnemyTable table) {
        if (ctx == null || table == null) {
            throw new IllegalArgumentException("ctx and table must not be null");
        }
        this.ctx = ctx;
        this.mods = mods != null ? mods : CombatModifiers.NONE;
        this.table = table;
    }

    // --- lifecycle ----------------------------------------------------------

    /**
     * First entry into an Endless run — {@code main.py:1626 begin_endless}.
     *
     * <p>Tier starts at <b>1</b>, not 0: {@code begin_endless} sets
     * {@code self.wave = 1} directly rather than going through
     * {@code start_wave}.
     *
     * <p>The horn is armed <b>here and nowhere else</b>. That is the documented
     * quirk: an Endless run gets one horn for its entire length, however many
     * tiers it survives. It is not a bug to be tidied into once-per-tier.
     */
    @Override
    public void begin() {
        RunSession s = ctx.session();
        s.setWave(1);
        spawnTimer = FIRST_SPAWN_DELAY;
        talentPointsAwarded = 0;
        nextScriptedBoss = 0;
        extraBosses = 0;

        s.resetHorn();              // ONCE PER RUN -- see ChallengeHorn
        s.setStarted(true);

        ctx.restoreTowers();
        ctx.weather().roll();
        ctx.banners().post(Announcements.Id.ENDLESS_BEGIN, 2.6);
        ctx.banners().post(Announcements.Id.ENDLESS_SUBTITLE, 3.0);
        ctx.weather().announce(ctx.banners());

        if (ctx.trace().isEnabled()) {
            ctx.trace().event(TraceEvent.TIER_CHANGED, ctx.step(), 0L, 1, 0f, "begin");
        }
    }

    // --- the step -----------------------------------------------------------

    /**
     * One step of the timetable — {@code main.py:1659 update_endless_schedule}.
     *
     * <p>The order is the source's: clock, talent drip, tier, bosses, spawn. It
     * is observable, because each stage can post a banner and the tier step
     * rolls the weather, which draws from the gameplay stream.
     */
    @Override
    public void update(double dt) {
        RunSession s = ctx.session();
        s.advancePlayStep();

        awardTalentIncome();
        stepTier();
        runBossTimetable();
        trickleSpawns(dt);
    }

    /**
     * +1 talent point per minute survived.
     *
     * <p>Python keeps a {@code talent_seconds} counter and subtracts 60 from it
     * rather than resetting, precisely so the remainder is not thrown away and
     * the award rate does not drift. Deriving the total from the exact run clock
     * gets the same answer with no counter at all: an hour is 60 points, not 59.
     */
    private void awardTalentIncome() {
        int due = (int) (ctx.session().playTime() / Tuning.TALENT_SECONDS_PER_POINT);
        while (talentPointsAwarded < due) {
            talentPointsAwarded++;
            ctx.talents().award(1, "endless-minute");
            if (ctx.trace().isEnabled()) {
                ctx.trace().event(TraceEvent.TALENT_AWARDED, ctx.step(), 0L, 1,
                        (float) ctx.session().playTime(), "endless-minute");
            }
        }
    }

    /** The tier the clock is on. {@code 1 + int(playTime / 30)}. */
    public int tierAt(double playTime) {
        return 1 + (int) (playTime / Tuning.ENDLESS_TIER_SECONDS);
    }

    private void stepTier() {
        RunSession s = ctx.session();
        int tier = tierAt(s.playTime());
        if (tier == s.wave()) {
            return;
        }
        s.setWave(tier);
        ctx.restoreTowers();
        ctx.weather().roll();
        ctx.banners().post(Announcements.Id.TIER_REACHED, tier, 1.8);
        for (EnemyType type : ctx.composition().newlyUnlocked(tier)) {
            ctx.banners().post(Announcements.Id.NEW_FOE, tier, type.id(), 3.6);
        }
        if (ctx.trace().isEnabled()) {
            ctx.trace().event(TraceEvent.TIER_CHANGED, ctx.step(), 0L, tier,
                    (float) s.playTime(), "tier");
        }
    }

    /**
     * The boss timetable.
     *
     * <p>Scripted arrivals first, then the repeating random one. Both are
     * compared with {@code >=} against the run clock, as in the source.
     */
    private void runBossTimetable() {
        double t = ctx.session().playTime();
        double[] schedule = Tuning.ENDLESS_BOSS_TIMES;

        while (nextScriptedBoss < schedule.length && t >= schedule[nextScriptedBoss]) {
            summon(scriptedBoss(nextScriptedBoss), true);
            nextScriptedBoss++;
        }
        if (nextScriptedBoss < schedule.length) {
            return;
        }
        double last = schedule[schedule.length - 1];
        int due = (int) ((t - last) / Tuning.ENDLESS_BOSS_REPEAT);
        if (due > extraBosses) {
            extraBosses = due;
            //  a RANDOM boss, from the gameplay stream: the repeat schedule is
            //  not a rotation
            summon(WaveComposition.BOSS_ROTATION[
                    ctx.rng().rangeInclusive(0, WaveComposition.BOSS_ROTATION.length - 1)],
                    false);
        }
    }

    /** The boss the nth scripted slot summons. Order: Troll King, Dragon, Lich. */
    private static String scriptedBoss(int index) {
        return WaveComposition.BOSS_ROTATION[index % WaveComposition.BOSS_ROTATION.length];
    }

    private void summon(String bossId, boolean scripted) {
        BossType type = BossType.byId(bossId, null);
        if (type == null) {
            return;
        }
        RunSession s = ctx.session();
        int effective = Math.max(1, s.wave() + Math.round(s.wave() * ctx.bossHeadstart()));
        Enemy boss = ctx.summonBoss(type, effective);
        if (boss == null) {
            return;
        }
        ctx.shake().add(9f);
        ctx.banners().post(Announcements.Id.BOSS_ARRIVES, effective, bossId, 4.0);
        ctx.banners().post(Announcements.Id.BOSS_HINT, effective, bossId, 4.6);
        if (ctx.trace().isEnabled()) {
            ctx.trace().event(TraceEvent.BOSS_SCHEDULED, ctx.step(), boss.uid(),
                    (float) s.playTime(), effective, scripted ? bossId : bossId + "-repeat");
        }
    }

    /**
     * The gap before the next mob — {@code main.py:1619 endless_spawn_gap}.
     *
     * <p>Ramps linearly from 1.70 s to 0.38 s over the first 300 s, then holds,
     * with ±28 % jitter on every individual gap. The jitter is drawn from the
     * gameplay stream, so a seeded run reproduces the exact spawn cadence.
     */
    public double spawnGap() {
        double t = clamp01(ctx.session().playTime() / Tuning.ENDLESS_SPAWN_RAMP);
        double gap = Tuning.ENDLESS_SPAWN_START
                + (Tuning.ENDLESS_SPAWN_MIN - Tuning.ENDLESS_SPAWN_START) * t;
        return gap * ctx.rng().uniformSeconds(1d - Tuning.ENDLESS_SPAWN_JITTER,
                1d + Tuning.ENDLESS_SPAWN_JITTER);
    }

    /** The un-jittered gap at a given time. For fixtures and the debug overlay. */
    public static double baseSpawnGap(double playTime) {
        double t = clamp01(playTime / Tuning.ENDLESS_SPAWN_RAMP);
        return Tuning.ENDLESS_SPAWN_START
                + (Tuning.ENDLESS_SPAWN_MIN - Tuning.ENDLESS_SPAWN_START) * t;
    }

    private static double clamp01(double v) {
        return v < 0d ? 0d : (v > 1d ? 1d : v);
    }

    /**
     * The continuous trickle.
     *
     * <p>Note the shape: the timer is re-armed <b>whether or not</b> the mob is
     * actually spawned. Python re-arms first and then checks the alive cap, so a
     * full field silently skips that mob rather than banking it. Reproduced.
     */
    private void trickleSpawns(double dt) {
        spawnTimer -= dt;
        if (spawnTimer > 0d) {
            return;
        }
        spawnTimer = spawnGap();
        if (ctx.aliveEnemyCount() >= MAX_ALIVE) {
            return;
        }
        EnemyType type = rollMob();
        ctx.spawnEnemy(type, ctx.session().wave());
        if (ctx.trace().isEnabled()) {
            ctx.trace().event(TraceEvent.SPAWN_SCHEDULED, ctx.step(), 0L,
                    ctx.session().wave(), (float) spawnTimer, type.id());
        }
    }

    /**
     * One Endless mob — {@code main.py:1697 roll_endless_mob}.
     *
     * <p>Two draws in a fixed order: the Goblin roll first, then the weighted
     * pick. The Goblin chance is {@code GOBLIN_CHANCE * 0.05} — five percent of
     * the Classic per-wave chance, because this rolls per spawn rather than per
     * wave.
     */
    public EnemyType rollMob() {
        int tier = ctx.session().wave();
        pool.clear();
        weights.clear();
        for (EnemyTable.UnlockEntry u : table.unlocks()) {
            if (tier >= u.firstWave) {
                pool.add(u.type);
                weights.add(1f / u.weight);
            }
        }
        if (pool.size == 0) {
            return EnemyType.SCOUT;
        }
        Rng rng = ctx.rng();
        if (tier >= WaveComposition.GOBLIN_FROM_WAVE
                && rng.game().nextFloat() < WaveComposition.GOBLIN_CHANCE * 0.05f) {
            return EnemyType.TREASURE_GOBLIN;
        }
        return weightedPick(rng);
    }

    /** Python {@code random.choices(classes, weights, k=1)}. */
    private EnemyType weightedPick(Rng rng) {
        float total = 0f;
        for (int i = 0; i < weights.size; i++) {
            total += weights.get(i);
        }
        float roll = rng.game().nextFloat() * total;
        float running = 0f;
        for (int i = 0; i < pool.size; i++) {
            running += weights.get(i);
            if (roll < running) {
                return pool.get(i);
            }
        }
        return pool.get(pool.size - 1);
    }

    // --- the horn -----------------------------------------------------------

    @Override
    public boolean blowHorn() {
        return ChallengeHorn.blowEndless(ctx, mods, table, this);
    }

    // --- state --------------------------------------------------------------

    /** Endless never queues: it spawns straight from the roll. Always zero. */
    @Override
    public int pendingSpawns() {
        return 0;
    }

    public double spawnTimer() {
        return spawnTimer;
    }

    /** Seconds banked toward the next talent point, always in [0, 60). */
    public double talentSeconds() {
        return ctx.session().playTime()
                - talentPointsAwarded * Tuning.TALENT_SECONDS_PER_POINT;
    }

    /** Talent points this run has earned from surviving. */
    public int talentPointsAwarded() {
        return talentPointsAwarded;
    }

    /** How many of the three scripted bosses have been sent. */
    public int scriptedBossesSent() {
        return nextScriptedBoss;
    }

    /** How many random repeat bosses have been sent after the scripted three. */
    public int repeatBossesSent() {
        return extraBosses;
    }

    /** Seconds until the next scheduled boss, or -1 once past the script. */
    public double secondsToNextBoss() {
        double t = ctx.session().playTime();
        double[] schedule = Tuning.ENDLESS_BOSS_TIMES;
        if (nextScriptedBoss < schedule.length) {
            return schedule[nextScriptedBoss] - t;
        }
        double last = schedule[schedule.length - 1];
        double nextDue = last + (extraBosses + 1) * Tuning.ENDLESS_BOSS_REPEAT;
        return nextDue - t;
    }

    @Override
    public String describe() {
        return "endless tier=" + ctx.session().wave()
                + " t=" + String.format(Locale.ROOT, "%.2f", ctx.session().playTime()) + "s"
                + " nextSpawn=" + String.format(Locale.ROOT, "%.2f", spawnTimer)
                + " gap=" + String.format(Locale.ROOT, "%.2f", baseSpawnGap(ctx.session().playTime()))
                + " bosses=" + nextScriptedBoss + "+" + extraBosses
                + " nextBoss=" + String.format(Locale.ROOT, "%.1f", secondsToNextBoss()) + "s"
                + " talentBank=" + String.format(Locale.ROOT, "%.1f", talentSeconds());
    }
}
