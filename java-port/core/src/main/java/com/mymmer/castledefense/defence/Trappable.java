package com.mymmer.castledefense.defence;

/**
 * A target the Outpost can imprison — in practice, a Necromancer.
 *
 * <p>Separate from {@link Target} so the trap mechanic does not widen what every
 * shootable thing must implement. Phase 6's Necromancer implements both; nothing
 * else implements this one.
 */
public interface Trappable extends Target {

    /** Python {@code Enemy.MASS} — checked against the player's grab capacity. */
    float mass();

    /**
     * Called at the moment of capture, before the unit leaves the horde.
     *
     * <p>The unit itself is responsible for switching its own state; the Outpost
     * only decides that a capture happened. Python {@code enemy.on_trapped()}.
     */
    void onTrapped();

    /** Moves the captive to its cage position. Python sets x/y directly. */
    void moveTo(float x, float y);
}
