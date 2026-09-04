package com.mymmer.castledefense.defence;

import com.mymmer.castledefense.config.GameConfig;
import com.mymmer.castledefense.debug.TraceEvent;

/**
 * A bought wall standing out in the field.
 *
 * <p>Ground troops have to chew through it before they can reach the castle;
 * flyers simply go over. Phase 5 owns the structure — its health, its tiers, its
 * geometry and its interception of hostile shots. The <em>blocking</em> itself is
 * an enemy-side movement rule and arrives in Phase 6; what it will consult is
 * {@link #alive()} and {@link #x()}, both of which exist now.
 */
public final class Barricade {

    /** Full width of the structure. Python class constant {@code W}. */
    public static final float WIDTH = 40f;

    private final DefenceContext ctx;
    private final float x = GameConfig.BARRICADE_X;

    private int level;
    private float hp;
    private float maxHp;

    /** Visual only: hit flash, decays at 3/s. */
    private float flash;

    public Barricade(DefenceContext ctx) {
        if (ctx == null) {
            throw new IllegalArgumentException("ctx must not be null");
        }
        this.ctx = ctx;
    }

    public float x() {
        return x;
    }

    /** Top edge, in pygame coordinates: 96 above the ground line. */
    public float topY() {
        return GameConfig.GROUND_Y - 96f;
    }

    public int level() {
        return level;
    }

    public float hp() {
        return hp;
    }

    public float maxHp() {
        return maxHp;
    }

    public float flash() {
        return flash;
    }

    /** Standing only while it has both a level and health left. */
    public boolean alive() {
        return level > 0 && hp > 0f;
    }

    /**
     * Buy, rebuild after a collapse, or reinforce to the next tier.
     *
     * <p>One button does all three, which is why a collapsed level-5 barricade
     * can be rebuilt at full strength for the same cost as reinforcing it: the
     * level only rises while it is below the cap, and either way the health is
     * reset to that level's maximum.
     *
     * @return false only when it is already at the cap <em>and</em> undamaged
     */
    public boolean buy() {
        if (level < GameConfig.BARRICADE_MAX_LEVEL) {
            level++;
        } else if (hp >= maxHp) {
            return false;
        }
        maxHp = GameConfig.BARRICADE_HP[level];
        hp = maxHp;
        return true;
    }

    public void takeDamage(float amount) {
        if (!alive()) {
            return;
        }
        hp -= amount;
        flash = 1f;
        ctx.trace().event(TraceEvent.BARRICADE_DAMAGE, ctx.step(), 0L, amount, hp, null);
        if (hp <= 0f) {
            hp = 0f;
        }
    }

    /**
     * One step.
     *
     * <p>The regeneration talent is applied <b>here</b>, whereas Python applies
     * it from the game loop right after calling {@code barricade.update}. Same
     * order, same result, one fewer thing for the game loop to remember.
     */
    public void update(double dt) {
        float fdt = (float) dt;
        flash = Math.max(0f, flash - fdt * 3f);            // visual
        float regen = ctx.modifiers().barricadeRegen();
        if (regen > 0f && alive()) {
            hp = Math.min(maxHp, hp + maxHp * regen * fdt);
        }
    }

    @Override
    public String toString() {
        return "Barricade[lv" + level + " hp=" + (int) hp + "/" + (int) maxHp
                + (alive() ? "" : " DOWN") + "]";
    }
}
