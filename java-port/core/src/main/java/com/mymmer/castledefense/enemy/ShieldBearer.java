package com.mymmer.castledefense.enemy;

/**
 * Slow bulwark; shrugs off arrows.
 *
 * <p>No behaviour of its own — its character is entirely in the data: 60% armour
 * against projectiles, triple a Scout's health, and MASS 3.0, which puts it out
 * of reach of the cursor until Grab Strength 1.
 *
 * <p>Note it is <b>not</b> {@code heavy}: it is armoured but not a tank, so it
 * has no plates to strip, cannot be shoved, and cannons get no bonus on it. Its
 * armour is countered by throwing it instead — fall damage ignores armour.
 */
public final class ShieldBearer extends Enemy {
    public ShieldBearer(EnemyContext ctx, EnemyConfig config, int wave, Float x, Float y) {
        super(ctx, config, wave, x, y);
    }
}
