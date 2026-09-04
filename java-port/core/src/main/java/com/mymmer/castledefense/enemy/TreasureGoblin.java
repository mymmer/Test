package com.mymmer.castledefense.enemy;

import com.mymmer.castledefense.config.GameConfig;

/**
 * Flees with a sack of gold. Kill it before it escapes.
 *
 * <p>It never attacks and never approaches: it runs the <b>other</b> way, and
 * its {@code vxEstimate} is positive, so a tower leading its shots aims ahead of
 * it in the correct direction.
 *
 * <h2>Escaping is a silent death</h2>
 *
 * <pre>
 *   escape() → die(silent = true) → no gold, no kill count, no fling score
 * </pre>
 *
 * <p>That is the entire risk of the encounter: letting it go costs the player
 * everything it was carrying, and the 140 base gold is by far the largest payout
 * in the roster. A death that paid out on escape would remove the tension.
 */
public final class TreasureGoblin extends Enemy {

    /** Seconds before it is gone, whatever happens. */
    public static final double ESCAPE_TIME = 11.0;

    private double escapeTimer = ESCAPE_TIME;
    /** Visual only: the hopping run. */
    private float hop;

    public TreasureGoblin(EnemyContext ctx, EnemyConfig config, int wave, Float x, Float y) {
        //  starts out in the open field rather than at the spawn line
        super(ctx, config, wave,
                x != null ? x : ctx.rng().uniform(720f, 1040f), y);
        this.hop = ctx.rng().uniform(0f, 6.28f);
    }

    public double escapeTimer() {
        return escapeTimer;
    }

    /** Visual only. */
    public float hop() {
        return hop;
    }

    @Override
    protected void think(double dt) {
        //  Deliberately does NOT call super: it shares none of the base's
        //  behaviour -- no ally check, no barricade, no castle approach, no
        //  blocked queueing, and no talent slow.  It only runs.
        float fdt = (float) dt;
        anim += fdt * speed * 0.09f;
        hop += fdt * 11f;
        setState(EnemyState.WALK);
        x += speed * fdt;
        setVxEstimate(speed);
        escapeTimer -= dt;
        if (escapeTimer <= 0d || x > GameConfig.WORLD_WIDTH + 90f) {
            escape();
        }
    }

    /** Gone. No reward for letting it go. */
    public void escape() {
        if (!isAlive()) {
            return;
        }
        die(true);
    }
}
