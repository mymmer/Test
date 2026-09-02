package com.mymmer.castledefense.enemy;

/**
 * Flies over ballistas and cannons.
 *
 * <p>Its flying flag changes four things across the whole game, none of them
 * here: a Cannon cannot target it at all, a Ballista triples its damage against
 * it, a Bowman's stretched vertical envelope is what lets it reach, and the
 * barricade and the crowd separation pass both ignore it entirely.
 *
 * <p>Its own contribution is only a faster animation. The bobbing flight itself
 * is base behaviour, driven by the {@code flying} flag.
 */
public final class Gargoyle extends Enemy {

    public Gargoyle(EnemyContext ctx, EnemyConfig config, int wave, Float x, Float y) {
        super(ctx, config, wave, x, y);
    }

    @Override
    protected void think(float dt) {
        anim += dt * 12f;
        super.think(dt);
    }
}
