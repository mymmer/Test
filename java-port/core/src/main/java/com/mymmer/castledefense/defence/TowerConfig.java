package com.mymmer.castledefense.defence;

/**
 * One tower's immutable stat block — the Python class constants, as data.
 *
 * <p>The split follows the rule in {@code docs/PORT_ANALYSIS.md} §9: <b>numbers
 * are data, behaviour is Java</b>. Which target a Cannon prefers, how a Ballista
 * leads a runner and what an overcharged shot does are code in
 * {@link Bowman}/{@link Ballista}/{@link Cannon}; how much they hurt and how far
 * they reach are here, so balance can change without a recompile.
 *
 * <p>Instance stats (damage, reload, range, splash, max HP) are <em>copied</em>
 * out of this block into each tower at construction, because the shop upgrades
 * them per tower. The config itself is never mutated.
 */
public final class TowerConfig {

    public final TowerType type;
    /** Display name. Becomes a localisation key in Phase 10. */
    public final String name;
    public final float range;
    public final float cooldown;
    public final float damage;
    public final float splash;
    public final boolean hitsAir;
    public final float maxHp;
    public final float width;
    public final float height;
    /** {@code 3.0} means "+200% damage" against that class. */
    public final float bonusVsAir;
    public final float bonusVsHeavy;
    /** Stretches the range envelope upward, so flyers cannot park out of reach. */
    public final float airRangeMult;
    public final boolean overchargeable;
    /** Fraction of max HP repaired per second. */
    public final float regen;
    /** Seconds a downed tower takes to come back. */
    public final float rebuildTime;
    /** Projectile speed for the ordinary shot. */
    public final float projectileSpeed;
    public final ProjectileKind projectile;
    /** Projectile speed for a hand-fired overcharge shot, before the multiplier. */
    public final float overchargeSpeed;

    TowerConfig(TowerType type, String name, float range, float cooldown, float damage,
                float splash, boolean hitsAir, float maxHp, float width, float height,
                float bonusVsAir, float bonusVsHeavy, float airRangeMult,
                boolean overchargeable, float regen, float rebuildTime,
                float projectileSpeed, ProjectileKind projectile, float overchargeSpeed) {
        this.type = type;
        this.name = name;
        this.range = range;
        this.cooldown = cooldown;
        this.damage = damage;
        this.splash = splash;
        this.hitsAir = hitsAir;
        this.maxHp = maxHp;
        this.width = width;
        this.height = height;
        this.bonusVsAir = bonusVsAir;
        this.bonusVsHeavy = bonusVsHeavy;
        this.airRangeMult = airRangeMult;
        this.overchargeable = overchargeable;
        this.regen = regen;
        this.rebuildTime = rebuildTime;
        this.projectileSpeed = projectileSpeed;
        this.projectile = projectile;
        this.overchargeSpeed = overchargeSpeed;
    }

    @Override
    public String toString() {
        return "TowerConfig[" + type.id() + " dmg=" + damage + " range=" + range
                + " cd=" + cooldown + "]";
    }
}
