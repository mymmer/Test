package com.mymmer.castledefense.defence;

import com.mymmer.castledefense.config.GameConfig;
import com.mymmer.castledefense.util.Collisions;

/**
 * The siege breaker: a lobbed, splashing shell with +200% against heavies.
 *
 * <p>It cannot hit air at all ({@code hitsAir = false}) — a ballistic shell
 * cannot lead a flyer — and its shot is a real ballistic solution rather than a
 * straight line: given a lead point it solves for a flight time and the vertical
 * velocity that lands there under gravity.
 *
 * <h2>Cluster scoring is O(E²) and stays that way for now</h2>
 *
 * <p>{@link #scoreTarget} counts, for <em>each</em> candidate, how many other
 * ground units sit inside a splash-sized box around it — so a full targeting
 * pass over E enemies is O(E²). That is exactly what Python does, and the
 * count it produces decides which shell lands where.
 *
 * <p>The obvious fix (a grid, or one precomputed density pass per step) is a
 * Phase 12 item, listed in {@code docs/PORT_ANALYSIS.md} §3. It is deferred on
 * purpose: any of those changes the tie-breaking, and a shell that picks a
 * different cluster is a different game. It gets replaced when a parity test can
 * prove the replacement chooses identically, not before.
 */
public final class Cannon extends DefenceTower {

    public Cannon(DefenceContext ctx, TowerConfig config, float x, float y) {
        super(ctx, config, x, y);
    }

    @Override
    protected void scoreTarget(Target e, float dist, float[] out) {
        out[1] = 0f;
        if (e.heavy()) {
            out[0] = -1000000f + dist;      // a heavy in range always wins
            return;
        }
        //  otherwise hit the densest cluster.  Note the box test uses splash on
        //  BOTH axes and is strict (<), not <= -- both are Python's.
        int n = 0;
        int count = ctx.targetCount();
        float s = splash();
        for (int i = 0; i < count; i++) {
            Target o = ctx.target(i);
            if (o.alive() && o.targetable() && !o.flying()
                    && Math.abs(o.x() - e.x()) < s && Math.abs(o.y() - e.y()) < s) {
                n++;
            }
        }
        out[0] = -(n * 1000f) + dist;
    }

    @Override
    protected void fire(Target target) {
        float mx = muzzleX();
        float my = muzzleY();
        leadTarget(target, 700f);
        float dx = lead[0] - mx;
        float dy = lead[1] - my;
        //  Ballistic solution for a clamped flight time: pick t from the
        //  horizontal distance, then solve vy so the shell falls to dy in that
        //  time.  life = t + 1.4 so a shot that misses still detonates late
        //  rather than sailing on for the default five seconds.
        float t = Collisions.clamp(Math.abs(dx) / 620f, 0.35f, 1.5f);
        float vx = dx / t;
        float vy = (dy - 0.5f * GameConfig.GRAVITY * t * t) / t;
        ctx.addProjectile(new Projectile(ctx, mx, my, vx, vy, config.projectile,
                damage() * ctx.modifiers().towerDamage(),
                splash(), 0, GameConfig.GRAVITY, false, t + 1.4f, 0f,
                config.bonusVsAir, config.bonusVsHeavy, false, uid()));
    }

    @Override
    protected void launchOvercharged(float dirX, float dirY, float power) {
        float speed = config.overchargeSpeed * GameConfig.OVERCHARGE_SPEED;
        float boostedDamage = damage() * (1f + (GameConfig.OVERCHARGE_DAMAGE - 1f) * power);
        float boostedSplash = splash() * (1f + (GameConfig.OVERCHARGE_SPLASH - 1f) * power);
        ctx.addProjectile(new Projectile(ctx, muzzleX(), muzzleY(),
                dirX * speed, dirY * speed, config.projectile,
                boostedDamage, boostedSplash, 0, GameConfig.GRAVITY * 0.55f,
                false, 4f, 0f,
                config.bonusVsAir, config.bonusVsHeavy, false, uid()));
    }
}
