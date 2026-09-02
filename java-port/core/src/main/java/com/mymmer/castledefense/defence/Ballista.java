package com.mymmer.castledefense.defence;

/**
 * The anti-air gun: slow, heavy, and +200% damage against anything flying.
 *
 * <p>Its targeting is the only one in the game that is not "lowest score wins on
 * a single number": Python returns the tuple {@code (0 if flying else 1, -hp)},
 * so it takes <b>flyers first, and among equals the one with the most health</b>.
 * That is reproduced through the two-component score.
 *
 * <p>Its counters travel <b>on the projectile</b> rather than being applied at
 * fire time, so a piercing bolt that passes through a Gargoyle and then a
 * ground unit hits the first for triple and the second for normal.
 */
public final class Ballista extends DefenceTower {

    public Ballista(DefenceContext ctx, TowerConfig config, float x, float y) {
        super(ctx, config, x, y);
    }

    @Override
    protected void scoreTarget(Target e, float dist, float[] out) {
        out[0] = e.flying() ? 0f : 1f;      // flyers first: it is the anti-air gun
        out[1] = -e.hp();                   // then the beefiest of them
    }

    @Override
    protected void fire(Target target) {
        float speed = config.projectileSpeed;
        leadTarget(target, speed);
        float a = (float) Math.atan2(lead[1] - muzzleY(), lead[0] - muzzleX());
        ctx.addProjectile(new Projectile(ctx, muzzleX(), muzzleY(),
                (float) Math.cos(a) * speed, (float) Math.sin(a) * speed,
                config.projectile,
                //  the raw damage times the talent multiplier -- NOT damageVs():
                //  the counters go on the shot and are resolved per victim
                damage() * ctx.modifiers().towerDamage(),
                0f, 2 + ctx.modifiers().extraPierce(), 0f, false, 5f, 0f,
                config.bonusVsAir, config.bonusVsHeavy, false, uid()));
    }

    @Override
    protected void launchOvercharged(float dirX, float dirY, float power) {
        float speed = config.overchargeSpeed
                * com.mymmer.castledefense.config.GameConfig.OVERCHARGE_SPEED;
        float boosted = damage() * (1f
                + (com.mymmer.castledefense.config.GameConfig.OVERCHARGE_DAMAGE - 1f) * power);
        ctx.addProjectile(new Projectile(ctx, muzzleX(), muzzleY(),
                dirX * speed, dirY * speed, config.projectile, boosted,
                0f, 4, 0f, false, 5f, 0f,
                config.bonusVsAir, config.bonusVsHeavy, false, uid()));
    }
}
