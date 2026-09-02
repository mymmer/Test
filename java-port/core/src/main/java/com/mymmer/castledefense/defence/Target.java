package com.mymmer.castledefense.defence;

/**
 * The smallest thing a defence needs to know about something it can shoot.
 *
 * <p>This interface is the whole reason the defence package can exist before the
 * enemy roster does. Towers, projectiles and the Outpost are written against it;
 * Phase 5 tests implement it with plain doubles; Phase 6's {@code Enemy}
 * implements the same contract and nothing here changes.
 *
 * <h2>Why it lives in {@code defence} and not somewhere neutral</h2>
 *
 * <p>The Python source is deliberately one-directional: {@code enemies.py}
 * imports {@code castle.py} and never the other way round, even though the
 * Outpost eventually produces friendly skeletons. Declaring the contract here
 * reproduces exactly that: {@code defence} depends on nothing, and
 * {@code enemy} will depend on {@code defence}. A "shared types" package in the
 * middle would look tidier and would let the dependency quietly become circular
 * later.
 *
 * <h2>Gameplay only</h2>
 *
 * <p>No colours, sprites, animation state or draw calls. {@link #width()} and
 * {@link #height()} are the <b>gameplay</b> hitbox — the same numbers the Python
 * {@code Enemy.w/h} carry — never an artwork size.
 *
 * <p>Coordinates are Python/pygame world coordinates: y increases <em>downward</em>
 * from the top of the 1280x720 world, matching every constant in
 * {@link com.mymmer.castledefense.config.GameConfig}. The single flip to libGDX's
 * y-up happens at the rendering boundary, in Phase 11.
 */
public interface Target {

    /** Stable identity. Used for pierce/splash hit tracking; never a list index. */
    long uid();

    /** False once killed. A dead target is skipped, not removed mid-iteration. */
    boolean alive();

    /**
     * False while the unit cannot be shot at all — spawning in, already caught,
     * phased out. Python {@code Enemy.targetable}.
     */
    boolean targetable();

    float x();

    float y();

    /** Gameplay hitbox width. Python {@code Enemy.w}. */
    float width();

    /** Gameplay hitbox height. Python {@code Enemy.h}. */
    float height();

    /** Drives air counters and the {@code HITS_AIR} filter. Python {@code flying}. */
    boolean flying();

    /** Drives heavy counters. Python class constant {@code HEAVY}. */
    boolean heavy();

    /** Current health, read by the Ballista's "beefiest target" preference. */
    float hp();

    /** Horizontal speed used for first-order intercept. Python {@code vx_estimate}. */
    float vxEstimate();

    /** Vertical speed used for first-order intercept. Python {@code vy_estimate}. */
    float vyEstimate();

    /**
     * Applies damage.
     *
     * @param amount already multiplied by counters, crits and falloff
     * @param source Python's damage-source tag ({@code "projectile"},
     *               {@code "explosive"}, {@code "spike"}, {@code "bleed"}); it
     *               drives later on-hit effects, so it is passed through rather
     *               than dropped
     */
    void takeDamage(float amount, String source);
}
