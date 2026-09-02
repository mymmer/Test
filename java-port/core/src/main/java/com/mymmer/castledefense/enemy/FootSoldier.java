package com.mymmer.castledefense.enemy;

/** Reliable line infantry. Pure base behaviour, heavier and slower than a Scout. */
public final class FootSoldier extends Enemy {
    public FootSoldier(EnemyContext ctx, EnemyConfig config, int wave, Float x, Float y) {
        super(ctx, config, wave, x, y);
    }
}
