package com.mymmer.castledefense.enemy;

/**
 * Summoned rabble. Cheap, fast and worth almost nothing.
 *
 * <p>Never appears in a wave on its own — it exists only because a Necromancer
 * raised it, which is why it is absent from the unlock table.
 */
public final class Skeleton extends Enemy {
    public Skeleton(EnemyContext ctx, EnemyConfig config, int wave, Float x, Float y) {
        super(ctx, config, wave, x, y);
    }
}
