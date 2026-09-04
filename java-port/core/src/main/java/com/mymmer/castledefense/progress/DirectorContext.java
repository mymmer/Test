package com.mymmer.castledefense.progress;

import com.badlogic.gdx.utils.Array;
import com.mymmer.castledefense.boss.BossType;
import com.mymmer.castledefense.debug.SimulationTrace;
import com.mymmer.castledefense.enemy.Enemy;
import com.mymmer.castledefense.enemy.EndgameTier;
import com.mymmer.castledefense.enemy.EnemyTable;
import com.mymmer.castledefense.enemy.EnemyType;
import com.mymmer.castledefense.enemy.WaveComposition;
import com.mymmer.castledefense.util.Rng;

/**
 * What a director needs from the world.
 *
 * <p>The same shape as {@code DefenceContext} and {@code EnemyContext}: a narrow
 * seam, so that {@code progress} names no concrete world and the directors can
 * be tested against a small fake. It is the third and last of these seams; there
 * will not be a fourth, because Phase 9 spends through the session rather than
 * driving the run.
 *
 * <p>Note what is <em>not</em> here: no castle, no projectile list, no cursor, no
 * screen. A director schedules and pays out; it does not fight.
 */
public interface DirectorContext {

    // --- the roster ---------------------------------------------------------

    /** Live enemies right now — the alive cap and the wave-clear gate read this. */
    int aliveEnemyCount();

    /**
     * Live enemies that <b>count toward clearing a wave</b>.
     *
     * <p>Separate from {@link #aliveEnemyCount()} because a
     * {@code FriendlySkeleton} is not an {@code Enemy} and never was in the
     * roster — Python's {@code alive_enemies()} walks {@code self.enemies}, and
     * allies live in {@code self.allies}. The two counts are the same today and
     * the distinction is still worth naming, because the wave-clear rule is the
     * one place where getting it wrong deadlocks a run.
     */
    int aliveHostileCount();

    /** Creates and adds an ordinary enemy. Python {@code spawn_enemy}. */
    Enemy spawnEnemy(EnemyType type, int wave);

    /**
     * Summons a boss — {@code main.py:1645 summon_boss}.
     *
     * <p>The implementation purges dead bosses first, so a repeat boss never
     * arrives onto its predecessor's debris, and registers the new one with the
     * {@code BossRegistry}. Returns null only if the boss id is unknown.
     *
     * @param wave the <em>effective</em> wave, headstart already applied
     */
    Enemy summonBoss(BossType type, int wave);

    /** True while any boss is on the field. */
    boolean bossAlive();

    // --- the run ------------------------------------------------------------

    /** Puts every downed tower back up. Python {@code castle.restore_towers()}. */
    void restoreTowers();

    /**
     * Clears what a finished wave must not carry into the shop.
     *
     * <p>Python's {@code end_wave} drops effects, projectiles, dropped items and
     * every cursor reference. The director says when; the world knows what.
     */
    void clearBetweenWaves();

    /**
     * The wave is over: hand control to the shop.
     *
     * <p>Python's {@code end_wave} assigns {@code self.state = self.SHOP}
     * directly. A director owns pacing, not the state machine, so it asks.
     */
    void enterShop();

    // --- collaborators ------------------------------------------------------

    RunSession session();

    Weather weather();

    Announcements banners();

    ScreenShake shake();

    TalentIncome talents();

    WaveComposition composition();

    /** The endgame tier table, for the "the horde has changed" banner. */
    EndgameTier[] endgameTiers();

    /** The unlock table, in ascending wave order. The horn's roster reads it. */
    Array<EnemyTable.UnlockEntry> unlocks();

    Rng rng();

    SimulationTrace trace();

    /** The canonical step index, for trace entries. */
    long step();

    /**
     * The difficulty's boss headstart, as a fraction of the wave number.
     *
     * <p>Python: {@code wave + int(round(wave * boss_headstart))}. Hard sends
     * bosses in scaled as though the run were further along than it is.
     */
    float bossHeadstart();

    /** True when the difficulty turns the horn into an elite pack. */
    boolean eliteHorn();
}
