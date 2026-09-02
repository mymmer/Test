package com.mymmer.castledefense.enemy;

/**
 * Armoured tank. Drag on it to tear the plating off.
 *
 * <p>The unit the whole interaction stack exists for. It is
 * {@code grabbable} <em>and</em> {@code heavy} <em>and</em> {@code strippable}
 * at MASS 9.0, which produces the intended progression:
 *
 * <pre>
 *   plated       → cannot be lifted at any Grab Strength, cannot be shoved
 *   drag away    → plates come off one at a time; armour, speed and
 *                  vulnerability all move with each one
 *   fully bare   → no longer strippable; can now be shoved castle-ward
 *   Grab Str. 3+ → capacity 9.5 finally exceeds MASS 9.0, so it can be lifted
 * </pre>
 *
 * <p>It hits the barricade for 2.5x, like every heavy, and its attack on the
 * castle is its own — the ram log recoils, which is the {@code ramPush} timer.
 */
public final class SiegeRam extends Enemy {

    /** Visual only: the log's recoil after an impact, decaying at 3/s. */
    private float ramPush;

    public SiegeRam(EnemyContext ctx, EnemyConfig config, int wave, Float x, Float y) {
        super(ctx, config, wave, x, y);
    }

    /** Visual only. */
    public float ramPush() {
        return ramPush;
    }

    @Override
    protected void attackCastle() {
        ramPush = 1f;
        ctx.castle().takeDamage(damage());
        ctx.spikes().bite(this);
    }

    @Override
    protected void think(float dt) {
        ramPush = Math.max(0f, ramPush - dt * 3f);
        super.think(dt);
    }
}
