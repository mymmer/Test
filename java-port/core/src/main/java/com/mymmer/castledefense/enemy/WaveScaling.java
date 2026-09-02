package com.mymmer.castledefense.enemy;

/**
 * How much nastier a mob is on wave N, and the endgame tier it belongs to.
 *
 * <p>Both functions are transcribed from {@code enemies.py}. The formulas are
 * short and the order they combine in is not obvious, so the whole calculation
 * is written out in {@link Enemy}'s constructor comment rather than being split
 * across helpers that hide it.
 *
 * <pre>
 *   hp  = 1.14^(wave-1)
 *   dmg = 1.11^(wave-1)
 *   spd = min(1.70, 1 + 0.026*(wave-1))
 * </pre>
 *
 * <p>The speed curve saturates at 1.70, which happens at wave 28 — past that,
 * only the difficulty multiplier and the endgame tiers make anything faster.
 */
public final class WaveScaling {

    private WaveScaling() {
    }

    /** Health multiplier for a wave. Python {@code wave_scaling(wave)[0]}. */
    public static float hp(int wave) {
        return (float) Math.pow(1.14, Math.max(1, wave) - 1);
    }

    /** Damage multiplier for a wave. Python {@code wave_scaling(wave)[1]}. */
    public static float damage(int wave) {
        return (float) Math.pow(1.11, Math.max(1, wave) - 1);
    }

    /** Speed multiplier for a wave, capped at 1.70. Python {@code wave_scaling(wave)[2]}. */
    public static float speed(int wave) {
        return (float) Math.min(1.70, 1.0 + 0.026 * (Math.max(1, wave) - 1));
    }

    /**
     * The endgame tier for a wave, or {@code -1} for the normal game.
     *
     * <p>Python walks the whole table and keeps the <em>last</em> match rather
     * than returning the first, so the tiers must stay in ascending wave order —
     * which {@link EnemyTable} validates.
     */
    public static int tierIndex(EndgameTier[] tiers, int wave) {
        int idx = -1;
        for (int i = 0; i < tiers.length; i++) {
            if (wave >= tiers[i].firstWave) {
                idx = i;
            }
        }
        return idx;
    }
}
