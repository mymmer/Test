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
    public static final float DASH_TIME = 0.38f;

    private boolean cloaked;
    private float cloakTimer;
    private float dashTimer;
    private float dashing;

    public Assassin(EnemyContext ctx, EnemyConfig config, int wave, Float x, Float y) {
        super(ctx, config, wave, x, y);
        this.cloakTimer = ctx.rng().uniform(1.2f, 2.6f);
        this.dashTimer = ctx.rng().uniform(1.8f, 3.4f);
    }

    public boolean cloaked() {
        return cloaked;
    }

    public float dashing() {
        return dashing;
    }

    /** Alive AND visible. A cloaked Assassin is simply not there to be shot. */
    @Override
    public boolean targetable() {
        return isAlive() && !cloaked;
    }

    @Override
    protected void think(float dt) {
        anim += dt * speed * 0.07f;

        cloakTimer -= dt;
        if (cloakTimer <= 0f) {
            cloaked = !cloaked;
            cloakTimer = cloaked ? ctx.rng().uniform(1.6f, 2.8f)
                    : ctx.rng().uniform(1.4f, 2.4f);
        }

        dashTimer -= dt;
        if (dashTimer <= 0f && dashing <= 0f) {
            dashTimer = ctx.rng().uniform(2.4f, 4.2f);
            dashing = DASH_TIME;
        }

        float boost = 1f;
        if (dashing > 0f) {
            dashing -= dt;
            boost = DASH_BOOST;
        }
        float old = speed;
        speed *= boost;
        super.think(dt);
        speed = old;
    }
}
