package com.mymmer.castledefense.enemy;

/** Fast, fragile skirmisher. Pure base behaviour — the reference implementation. */
public final class Scout extends Enemy {
    public Scout(EnemyContext ctx, EnemyConfig config, int wave, Float x, Float y) {
        super(ctx, config, wave, x, y);
    }
}
