package com.mymmer.castledefense.progress;

import com.badlogic.gdx.utils.Array;
import com.mymmer.castledefense.boss.BossType;
import com.mymmer.castledefense.debug.TraceEvent;
import com.mymmer.castledefense.defence.CombatModifiers;
import com.mymmer.castledefense.enemy.Enemy;
import com.mymmer.castledefense.enemy.EndgameTier;
import com.mymmer.castledefense.enemy.EnemyType;
import com.mymmer.castledefense.enemy.SpawnSpec;
import com.mymmer.castledefense.enemy.WaveScaling;
import java.util.Locale;

/**
 * Classic mode: numbered waves, a shop between them.
 *
 * <h2>The loop</h2>
 *
 * <pre>
 *   startWave()   wave++, compose the queue, restore towers, roll weather,
 *                 re-arm the horn, announce
 *   update(dt)    drip the queue out on spawn_interval, gated by MAX_ALIVE
 *   ...           when the queue is empty AND nothing hostile is alive,
 *                 count 1.1 s, then
 *   endWave()     award the talent point and the wave bonus, restore towers,
 *                 clear the field, hand control to the shop
 * </pre>
 *
 * <h2>The numbers, from the source</h2>
 *
 * <ul>
 *   <li>{@code spawn_interval = max(0.32, 1.25 - wave * 0.032)} — the wave
 *       tightens as it goes up, to a floor.</li>
 *   <li>First spawn after {@code 0.8 s}.</li>
 *   <li>Each spawn re-arms the timer at {@code interval * uniform(0.7, 1.3)},
 *       or {@code interval * 2.4} after a boss — a boss buys the player a
 *       breather.</li>
 *   <li>{@code MAX_ALIVE = 58} — note this is <b>not</b> Endless's 60.</li>
 *   <li>Wave bonus {@code 80 + wave * 22 + wavePurse}.</li>
 *   <li>Wave-clear delay {@code 1.1 s}, and the comparison is
 *       <b>strictly greater</b>: {@code if self.wave_clear_delay > 1.1}.</li>
 * </ul>
 *
 * <h2>The wave-clear rule</h2>
 *
 * <p>Python: {@code not self.spawn_queue and not self.alive_enemies()}. Two
 * things follow, and both are load-bearing:
 *
 * <ul>
 *   <li>A {@code FriendlySkeleton} <b>does not</b> hold a wave open. It is not
 *       an {@code Enemy} and lives in a different list. A player who leaves
 *       allies standing still gets their shop.</li>
 *   <li>A mob that is marked dead but not yet swept <b>does not</b> hold it open
 *       either, because {@code alive_enemies()} filters on the flag rather than
 *       on list membership. The Java {@code aliveHostileCount()} filters the
 *       same way.</li>
 * </ul>
 *
 * <p>A boss <b>does</b> hold it open: it is an {@code Enemy}, so it is in the
 * count like anything else.
 */
public final class WaveDirector implements RunDirector {

    /** {@code enemies.py:2131}. Deliberately different from Endless's 60. */
    public static final int MAX_ALIVE = 58;
    /** {@code main.py:2190}. The comparison is strictly greater than this. */
    public static final double WAVE_CLEAR_DELAY = 1.1;
    /** First spawn of a wave. */
    public static final double FIRST_SPAWN_DELAY = 0.8;

    private final DirectorContext ctx;
    private final CombatModifiers mods;

    private final Array<SpawnSpec> queue = new Array<>(false, 32);

    private boolean waveActive;
    private double spawnTimer;
    private double spawnInterval = 1.0;
    private double waveClearDelay;

    /** Set when a wave completes, read and cleared by the owner. */
    private boolean waveJustCleared;
    private int lastBonus;

    public WaveDirector(DirectorContext ctx, CombatModifiers mods) {
        if (ctx == null) {
            throw new IllegalArgumentException("ctx must not be null");
        }
        this.ctx = ctx;
        this.mods = mods != null ? mods : CombatModifiers.NONE;
    }

    // --- lifecycle ----------------------------------------------------------

    @Override
    public void begin() {
        startWave();
    }

    /**
     * Starts the next wave — {@code main.py:1734 start_wave}.
     *
     * <p>The order is the source's, and two parts of it are observable: the
     * weather is rolled <em>after</em> the queue is composed (so the composition
     * and the wind draw from the stream in that order and a seeded run
     * reproduces both), and the horn is re-armed here rather than at the end of
     * the previous wave.
     */
    public void startWave() {
        RunSession s = ctx.session();
        s.setWave(s.wave() + 1);
        int wave = s.wave();

        waveActive = true;
        waveClearDelay = 0d;
        waveJustCleared = false;

        queue.clear();
        queue.addAll(ctx.composition().build(wave, ctx.rng()));

        spawnInterval = Math.max(0.32, 1.25 - wave * 0.032);
        spawnTimer = FIRST_SPAWN_DELAY;

        ctx.restoreTowers();
        ctx.weather().roll();

        //  Classic re-arms the horn every wave.  Endless deliberately does not
        //  -- see ChallengeHorn.
        s.resetHorn();

        announceWaveStart(wave);

        if (ctx.trace().isEnabled()) {
            ctx.trace().event(TraceEvent.WAVE_START, ctx.step(), 0L, wave, queue.size,
                    "wave");
        }
    }

    private void announceWaveStart(int wave) {
        Announcements banners = ctx.banners();
        banners.post(Announcements.Id.WAVE_START, wave, 2.2);
        ctx.weather().announce(banners);

        //  an endgame tier that has just opened is announced once
        EndgameTier tier = enteredEndgameTier(wave);
        if (tier != null) {
            banners.post(Announcements.Id.ENDGAME_TIER, wave, tier.name, 4.0);
        }
        for (EnemyType type : ctx.composition().newlyUnlocked(wave)) {
            banners.post(Announcements.Id.NEW_FOE, wave, type.id(), 4.2);
        }
        String boss = ctx.composition().bossForWave(wave);
        if (boss != null) {
            banners.post(Announcements.Id.BOSS_APPROACHES, wave, boss, 4.5);
        }
    }

    /**
     * The endgame tier this wave has just entered, or null.
     *
     * <p>Python: {@code tier >= 0 and endgame_tier(wave - 1) != tier}. Announced
     * on the wave it opens and never again.
     */
    private EndgameTier enteredEndgameTier(int wave) {
        EndgameTier[] tiers = ctx.endgameTiers();
        int now = WaveScaling.tierIndex(tiers, wave);
        if (now < 0 || now == WaveScaling.tierIndex(tiers, wave - 1)) {
            return null;
        }
        return tiers[now];
    }

    /**
     * Ends the wave — {@code main.py:1780 end_wave}.
     *
     * <p>Awards the talent point <b>before</b> the gold, because the wave purse
     * talent is read while computing the bonus and a point awarded first could
     * in principle have been spent. Python's order; kept.
     */
    private void endWave() {
        RunSession s = ctx.session();
        waveActive = false;

        ctx.talents().award(com.mymmer.castledefense.config.Tuning.TALENT_POINTS_PER_WAVE,
                "wave");

        lastBonus = Scoring.waveBonus(s.wave(), mods);
        s.addGold(lastBonus);

        ctx.restoreTowers();
        ctx.clearBetweenWaves();

        ctx.banners().post(Announcements.Id.WAVE_CLEARED, lastBonus, 4.0);
        waveJustCleared = true;
        ctx.enterShop();

        if (ctx.trace().isEnabled()) {
            ctx.trace().event(TraceEvent.WAVE_END, ctx.step(), 0L, s.wave(), lastBonus,
                    "wave");
        }
    }

    // --- the step -----------------------------------------------------------

    @Override
    public void update(double dt) {
        if (!waveActive) {
            return;
        }
        drainQueue(dt);
        checkWaveClear(dt);
    }

    /**
     * Lets one spawn out when the timer expires and the field has room.
     *
     * <p>The alive cap is checked <b>with</b> the timer, not instead of it:
     * Python's condition is {@code spawn_timer <= 0 and alive < MAX_ALIVE}, so a
     * full field leaves the timer expired and the next mob appears the moment a
     * slot frees. It does not queue up a burst.
     */
    private void drainQueue(double dt) {
        if (queue.size == 0) {
            return;
        }
        spawnTimer -= dt;
        if (spawnTimer > 0d || ctx.aliveEnemyCount() >= MAX_ALIVE) {
            return;
        }
        SpawnSpec spec = queue.removeIndex(0);
        boolean isBoss = spec.isBoss();
        if (isBoss) {
            summonScheduledBoss(spec.bossId);
        } else {
            ctx.spawnEnemy(spec.type, ctx.session().wave());
        }
        //  a boss buys a longer breather; everything else jitters +/-30%
        spawnTimer = isBoss
                ? spawnInterval * 2.4
                : spawnInterval * ctx.rng().uniformSeconds(0.7, 1.3);
        if (isBoss) {
            ctx.shake().add(8f);
        }
        if (ctx.trace().isEnabled()) {
            ctx.trace().event(TraceEvent.SPAWN_SCHEDULED, ctx.step(), 0L,
                    queue.size, (float) spawnTimer, spec.id());
        }
    }

    /** Resolves a composed boss id and summons it with the difficulty headstart. */
    private Enemy summonScheduledBoss(String bossId) {
        BossType type = BossType.byId(bossId, null);
        if (type == null) {
            return null;
        }
        int wave = ctx.session().wave();
        int effective = Math.max(1, wave + Math.round(wave * ctx.bossHeadstart()));
        Enemy boss = ctx.summonBoss(type, effective);
        if (boss != null) {
            ctx.banners().post(Announcements.Id.BOSS_ARRIVES, effective, bossId, 4.0);
            if (ctx.trace().isEnabled()) {
                ctx.trace().event(TraceEvent.BOSS_SCHEDULED, ctx.step(), boss.uid(),
                        wave, effective, bossId);
            }
        }
        return boss;
    }

    /**
     * The wave-clear gate — {@code main.py:2188}.
     *
     * <p>The delay accumulates only while the field is genuinely empty; a mob
     * spawning or a Necromancer raising one resets it to zero, because the
     * condition is re-evaluated every step and the counter is only advanced
     * inside it. That is Python's structure and it matters: a Lich raising the
     * dead at 1.05 s must not let the wave end at 1.1.
     */
    private void checkWaveClear(double dt) {
        if (queue.size > 0 || ctx.aliveHostileCount() > 0) {
            waveClearDelay = 0d;
            return;
        }
        waveClearDelay += dt;
        if (waveClearDelay > WAVE_CLEAR_DELAY) {
            endWave();
        }
    }

    // --- the horn -----------------------------------------------------------

    @Override
    public boolean blowHorn() {
        return ChallengeHorn.blowClassic(ctx, mods, queue);
    }

    // --- state --------------------------------------------------------------

    @Override
    public int pendingSpawns() {
        return queue.size;
    }

    public boolean waveActive() {
        return waveActive;
    }

    /** Seconds the field has been clear. Zero whenever anything hostile lives. */
    public double waveClearDelay() {
        return waveClearDelay;
    }

    public double spawnInterval() {
        return spawnInterval;
    }

    public double spawnTimer() {
        return spawnTimer;
    }

    /** True once, after a wave ends; the owner clears it when it opens the shop. */
    public boolean consumeWaveCleared() {
        boolean was = waveJustCleared;
        waveJustCleared = false;
        return was;
    }

    /** The gold the last completed wave paid. */
    public int lastWaveBonus() {
        return lastBonus;
    }

    @Override
    public String describe() {
        return "classic wave=" + ctx.session().wave()
                + (waveActive ? " active" : " between")
                + " queued=" + queue.size
                + " nextSpawn=" + String.format(Locale.ROOT, "%.2f", spawnTimer)
                + " interval=" + String.format(Locale.ROOT, "%.2f", spawnInterval)
                + " clearDelay=" + String.format(Locale.ROOT, "%.2f", waveClearDelay);
    }
}
