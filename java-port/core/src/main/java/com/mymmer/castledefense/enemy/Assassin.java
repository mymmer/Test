package com.mymmer.castledefense.enemy;

/**
 * Cloaks, then dashes. Untargetable while hidden.
 *
 * <h2>Untargetable is enforced by the Phase 5 contract, not by special cases</h2>
 *
 * <p>Overriding {@link #targetable()} is the whole implementation. Every tower's
 * {@code pickTarget} and every friendly projectile's hit test already consult
 * it, so a cloaked Assassin walks through arrow fire without a single line of
 * Phase 5 code knowing this class exists.
 *
 * <h2>Speed ordering</h2>
 *
 * <p>The dash multiplies {@code speed} <b>after</b> any slow has been applied,
 * and restores the previous value afterwards:
 *
 * <pre>
 *   old = speed
 *   speed *= boost          (3.6 while dashing, else 1.0)
 *   super.think(dt)         (which applies the talent slow to that, then divides back)
 *   speed = old
 * </pre>
 *
 * <p>So a slowed Assassin's dash is slowed too — the two compose multiplicatively
 * — and the restore is an assignment of the saved value, not a divide, so it is
 * exact. That ordering is asserted in {@code EnemySpeedTest}; do not replace it
 * with an algebraically equivalent-looking single expression, because the slow is
 * applied inside the call in the middle.
 */
public final class Assassin extends Enemy {

    /** Speed multiplier while the dash is running. */
    public static final float DASH_BOOST = 3.6f;
    /** How long one dash lasts. */
    public static final double DASH_TIME = 0.38;

    private boolean cloaked;
    private double cloakTimer;
    private double dashTimer;
    private double dashing;

    public Assassin(EnemyContext ctx, EnemyConfig config, int wave, Float x, Float y) {
        super(ctx, config, wave, x, y);
        this.cloakTimer = ctx.rng().uniformSeconds(1.2, 2.6);
        this.dashTimer = ctx.rng().uniformSeconds(1.8, 3.4);
    }

    public boolean cloaked() {
        return cloaked;
    }

    public double dashing() {
        return dashing;
    }

    /** Alive AND visible. A cloaked Assassin is simply not there to be shot. */
    @Override
    public boolean targetable() {
        return isAlive() && !cloaked;
    }

    @Override
    protected void think(double dt) {
        anim += (float) dt * speed * 0.07f;

        cloakTimer -= dt;
        if (cloakTimer <= 0d) {
            cloaked = !cloaked;
            cloakTimer = cloaked ? ctx.rng().uniformSeconds(1.6, 2.8)
                    : ctx.rng().uniformSeconds(1.4, 2.4);
        }

        dashTimer -= dt;
        if (dashTimer <= 0d && dashing <= 0d) {
            dashTimer = ctx.rng().uniformSeconds(2.4, 4.2);
            dashing = DASH_TIME;
        }

        float boost = 1f;
        if (dashing > 0d) {
            dashing -= dt;
            boost = DASH_BOOST;
        }
        float old = speed;
        speed *= boost;
        super.think(dt);
        speed = old;
    }
}
