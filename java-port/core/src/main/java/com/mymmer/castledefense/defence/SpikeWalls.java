package com.mymmer.castledefense.defence;

import com.mymmer.castledefense.config.GameConfig;

/**
 * Iron spikes bolted to the outer face of the curtain wall.
 *
 * <p>Purely reactive: anything that swings at the castle takes damage back. The
 * reflected damage scales with the wave, so a level bought early keeps mattering
 * late.
 *
 * <p>The bleed follow-up comes from a talent, reached through the narrow
 * {@link CombatModifiers#spikeDot()} rather than by depending on the talent tree
 * — Phase 5 has no business knowing what a talent node is.
 */
public final class SpikeWalls {

    public static final int MAX_LEVEL = GameConfig.SPIKE_MAX_LEVEL;

    private final DefenceContext ctx;
    private int level;

    public SpikeWalls(DefenceContext ctx) {
        if (ctx == null) {
            throw new IllegalArgumentException("ctx must not be null");
        }
        this.ctx = ctx;
    }

    public int level() {
        return level;
    }

    /** Test/shop hook, mirroring Python's assignable {@code game.spike_level}. */
    public void setLevel(int level) {
        this.level = Math.max(0, Math.min(MAX_LEVEL, level));
    }

    /** @return false at the cap; unlike the walls, spikes do refuse. */
    public boolean upgrade() {
        if (level >= MAX_LEVEL) {
            return false;
        }
        level++;
        return true;
    }

    /**
     * Damage reflected per bite: {@code SPIKE_DAMAGE * level * (1 + 0.06*(wave-1))}.
     *
     * <p>Zero at level 0, which is what makes "no spikes, no reflected damage"
     * fall out of the same code path rather than needing a guard at every caller.
     */
    public float damage() {
        if (level <= 0) {
            return 0f;
        }
        return GameConfig.SPIKE_DAMAGE * level
                * (1f + 0.06f * Math.max(0, ctx.wave() - 1));
    }

    /**
     * Reflects damage onto whatever just hit the wall.
     *
     * <p>Called by an attacking unit at the moment it lands its blow — Phase 6
     * wires it up; Phase 5 tests call it directly. The bleed is a <b>second,
     * separate</b> damage application with its own source tag, not a larger
     * first one, because later on-hit effects key off the tag.
     */
    public void bite(Target enemy) {
        float dmg = damage();
        if (dmg <= 0f || enemy == null || !enemy.alive()) {
            return;
        }
        enemy.takeDamage(dmg, "spike");
        float bleed = ctx.modifiers().spikeDot();
        if (bleed > 0f) {
            enemy.takeDamage(dmg * bleed, "bleed");
        }
    }

    @Override
    public String toString() {
        return "SpikeWalls[lv" + level + "/" + MAX_LEVEL + " dmg=" + damage() + "]";
    }
}
