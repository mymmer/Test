package com.mymmer.castledefense.enemy;

import com.mymmer.castledefense.defence.DefenceContext;
import com.mymmer.castledefense.defence.SpikeWalls;
import com.mymmer.castledefense.entity.EntityList;

/**
 * What an enemy needs from the world, on top of what a defence needs.
 *
 * <p>Extends {@link DefenceContext} rather than duplicating it: an enemy shoots
 * at the same structures, files the same projectiles and reads the same wind and
 * modifiers. The direction is still one-way — {@code enemy} depends on
 * {@code defence}, exactly as {@code enemies.py} imports {@code castle.py}.
 *
 * <p>The extra surface is everything the Python god object exposes to a mob and
 * a tower does not: the ally line, the scoreboard, the run clock and the
 * weather.
 */
public interface EnemyContext extends DefenceContext {

    // --- roster -------------------------------------------------------------

    /**
     * The horde itself, for the few places that need snapshot iteration.
     *
     * <p>Most callers use {@link #targetCount()}/{@link #target(int)}, which is
     * allocation-free and enough. This exists because the airborne slam loop
     * genuinely needs a {@link EntityList.Snapshot}: trapping and slamming both
     * change the roster mid-loop, and Python iterates a copy for that reason.
     */
    EntityList<Enemy> horde();

    /**
     * Builds an enemy of a type without the caller knowing its class.
     *
     * <p>Used by the Necromancer's summon and by the Phase 8 spawner, so
     * {@code EnemyTable} stays the only place that maps a type to a constructor.
     */
    Enemy createEnemy(EnemyType type, int wave, Float x, Float y);

    /**
     * Adds a newly created enemy to the horde.
     *
     * <p>Python {@code game.spawn_enemy}. Used by the Necromancer's summon and by
     * the spawner in Phase 8.
     */
    void spawnEnemy(Enemy enemy);

    /** How many allies exist, alive or not. */
    int allyCount();

    /** One ally, in insertion order. */
    FriendlySkeleton ally(int index);

    // --- the run ------------------------------------------------------------

    /**
     * Canonical simulation seconds since the run began.
     *
     * <p>Python {@code game.time}. Used only for fling airtime scoring — and it
     * comes from the step count, never a frame delta.
     */
    double gameTime();

    /** Bounce upgrade level, 0..{@code BOUNCE_MAX_LEVEL}. */
    int bounceLevel();

    /** The spiked parapet, so an attacking mob can be bitten. */
    SpikeWalls spikes();

    // --- scoring and economy ------------------------------------------------

    /**
     * The crowd gold multiplier <em>as it stands right now</em>.
     *
     * <p>Called from {@link Enemy#die} <b>before</b> the mob is marked dead, so
     * the dying mob counts toward its own payout. That is Python's behaviour and
     * removing it first would quietly cut every reward.
     */
    float goldMultiplier();

    void addGold(int amount);

    void addKill();

    /** Fling score: distance and airtime, multiplied by mobs clobbered. */
    void addScore(int points, float x, float y, int hits, float combo);

    void addThrownDamage(float amount);

    void addPlatesTorn();

    // --- weather ------------------------------------------------------------

    /** True while a thunderstorm is running. Full weather is Phase 11. */
    boolean storm();

    /**
     * A mob has been thrown up into the storm ceiling.
     *
     * <p>The hook exists now so the airborne integration is complete; the strike
     * itself arrives with the weather.
     */
    void strikeLightning(Enemy enemy);

    // --- talent-driven behaviour --------------------------------------------

    /**
     * The combined speed multiplier the talent tree imposes on one enemy.
     *
     * <p>Python {@code game.enemy_slow(enemy)}. Returns 1.0 for no slow.
     */
    float enemySlow(Enemy enemy);

    // --- difficulty ---------------------------------------------------------
    //  Read once, in the Enemy constructor, in a specific order.  They are
    //  separate methods rather than one object because Python reads them as
    //  three independent getattr() lookups on the game and combines them in an
    //  order that matters -- see Enemy's constructor.

    /** Stretches the whole health and damage curve. Python {@code enemy_scale}. */
    float enemyScale();

    /**
     * Bends the health curve itself: the growth <em>earned per wave</em> is
     * multiplied, so wave 1 is barely touched and wave 20 hurts. Python
     * {@code enemy_hp_curve}.
     */
    float enemyHpCurve();

    /** A flat multiplier on base speed. Python {@code enemy_speed_scale}. */
    float enemySpeedScale();

    /** The endgame tier table, in ascending wave order. */
    EndgameTier[] endgameTiers();
}
