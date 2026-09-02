package com.mymmer.castledefense.defence;

/**
 * Fast, cheap, and reaches much higher than it reaches far.
 *
 * <p>Its {@code airRangeMult} of 1.9 is the whole point of the unit: the range
 * envelope is stretched upward, so a Gargoyle hovering above the wall is inside
 * a 430-unit range that a flat measurement would put well outside it.
 *
 * <p>It bakes its counters into the shot at fire time (via {@code damageVs}),
 * unlike the Ballista and Cannon which send them along with the projectile. Both
 * are Python's, and the difference matters: a Bowman's counters are all 1.0, so
 * the two are equivalent for it today — but copying the Ballista's pattern here
 * would apply the talent damage multiplier twice.
 */
public final class Bowman extends DefenceTower {

    public Bowman(DefenceContext ctx, TowerConfig config, float x, float y) {
        super(ctx, config, x, y);
    }

    @Override
    protected void fire(Target target) {
        float speed = config.projectileSpeed;
        leadTarget(target, speed);
        float a = (float) Math.atan2(lead[1] - muzzleY(), lead[0] - muzzleX());
        ctx.addProjectile(Projectile.friendly(ctx, muzzleX(), muzzleY(),
                (float) Math.cos(a) * speed, (float) Math.sin(a) * speed,
                config.projectile, damageVs(target)));
    }
}
