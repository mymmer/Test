package com.mymmer.castledefense.boss;

import com.mymmer.castledefense.enemy.EnemyContext;
import com.mymmer.castledefense.enemy.EnemyType;

/**
 * What a boss needs from the world, on top of what an enemy needs.
 *
 * <p>Small, because a boss is an {@code Enemy} and most of what it does goes
 * through the contracts that already exist. The additions are the two things
 * only bosses have: dropped regalia, and a defeat notification the run reacts to.
 */
public interface BossContext extends EnemyContext {

    /** Files a newly torn-off piece of regalia with the world. */
    void addDroppedItem(DroppedItem item);

    /**
     * A boss has been defeated.
     *
     * <p>The narrow hook. Phase 8 and 9 hang the skill unlock and the talent
     * bounty off it; Phase 7 only guarantees it fires exactly once per boss,
     * after the ordinary {@code Enemy} payout has already happened.
     */
    void onBossDefeated(Boss boss);

    /**
     * Which enemy types the Lich Lord may raise.
     *
     * <p>Python builds it from the wave's unlock pool minus Siege Rams and
     * Necromancers, falling back to Skeletons. It lives on the context because
     * the pool depends on the run's wave, which is the world's business.
     */
    EnemyType[] summonableTypes();

    /**
     * The difficulty's boss fire-rate multiplier.
     *
     * <p>Hard sets it to 0.5, which is literally twice the rate of fire. Read
     * once per boss at construction, so changing difficulty mid-run cannot
     * retune a boss already on the field. Python {@code game.boss_fire_scale}.
     */
    float bossFireScale();

    /** Builds a boss without the caller knowing its class. */
    Boss createBoss(BossType type, int wave, Float x, Float y);
}
