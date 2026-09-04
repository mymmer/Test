package com.mymmer.castledefense.progress;

import com.mymmer.castledefense.config.GameConfig;
import com.mymmer.castledefense.defence.CombatModifiers;

/**
 * The two economy formulas, transcribed and kept in one place.
 *
 * <p>Both are pure functions of their arguments. They live here rather than
 * inside {@link RunSession} because they are the parts a fixture can check
 * directly against the Python source, and because the crowd multiplier is read
 * by {@code Enemy.die} through the world seam rather than by the session.
 */
public final class Scoring {

    private Scoring() {
    }

    /**
     * The crowd gold multiplier — {@code main.py:1332 gold_multiplier}.
     *
     * <pre>
     *   n    = live enemies RIGHT NOW
     *   step = POP_GOLD_STEP * goldPop
     *   base = min(POP_GOLD_CAP * goldPop, 1 + step * max(0, n - POP_GOLD_FREE))
     *   out  = base * (1 + hornBonus) * killGold * goldScale
     * </pre>
     *
     * <p><b>The quirk:</b> {@code Enemy.die} reads this <em>before</em> marking
     * itself dead, so the dying mob is still counted in {@code n} and pays
     * toward its own bounty. Reproduced deliberately; see
     * {@code PORT_ANALYSIS.md} §13.
     *
     * <p>Note the cap is scaled by the talent too, not just the step, so
     * Prospector raises the ceiling as well as the slope. That is what the
     * source does.
     *
     * @param aliveCount live enemies including the one about to die
     * @param hornBonus  0 normally, {@code HORN_BONUS} after the horn
     * @param goldScale  the difficulty's gold multiplier
     */
    public static float crowdGoldMultiplier(int aliveCount, float hornBonus,
                                            float goldScale, CombatModifiers mods) {
        float goldPop = mods.goldPop();
        float step = GameConfig.POP_GOLD_STEP * goldPop;
        int over = Math.max(0, aliveCount - GameConfig.POP_GOLD_FREE);
        float base = Math.min(GameConfig.POP_GOLD_CAP * goldPop, 1f + step * over);
        return base * (1f + hornBonus) * mods.killGold() * goldScale;
    }

    /**
     * What one kill actually pays — {@code enemies.py:420 die}.
     *
     * <p>{@code max(1, round(gold * multiplier))}: even a fully devalued mob
     * pays one coin, and the rounding is half-up on the product rather than on
     * the parts.
     */
    public static int killPayout(int baseGold, float multiplier) {
        return Math.max(1, Math.round(baseGold * multiplier));
    }

    /**
     * Fling score before the horn and talent multipliers —
     * {@code enemies.py:506 resolve_fling}.
     *
     * <pre>
     *   travel  = |x - x0| + max(0, y0 - peak)      // across, plus how high
     *   base    = travel * SCORE_PER_PX + airtime * SCORE_PER_SEC
     *   combo   = 1 + SCORE_COMBO_STEP * hits
     *   points  = int(base * combo)                 // truncated, not rounded
     * </pre>
     *
     * <p>{@code peak} is the <em>smallest</em> y reached, because y grows
     * downward: a mob thrown straight up scores its height even if it lands
     * where it started.
     */
    public static float flingTravel(float x, float startX, float startY, float peakY) {
        return Math.abs(x - startX) + Math.max(0f, startY - peakY);
    }

    /** The combo multiplier for a fling that struck {@code hits} other mobs. */
    public static float flingCombo(int hits) {
        return 1f + GameConfig.SCORE_COMBO_STEP * hits;
    }

    /** Points for one completed fling, truncated exactly as Python's {@code int()}. */
    public static int flingPoints(float travel, double airtimeSeconds, int hits) {
        float base = travel * GameConfig.SCORE_PER_PX
                + (float) airtimeSeconds * GameConfig.SCORE_PER_SEC;
        return (int) (base * flingCombo(hits));
    }

    /**
     * The horn and talent multipliers applied at award time —
     * {@code main.py:1441 add_score}.
     *
     * <p>Applied to the already-truncated fling points and truncated again, so
     * the two truncations both happen and in this order.
     */
    public static int awardedScore(int points, float hornBonus, CombatModifiers mods) {
        return (int) (points * (1f + hornBonus) * mods.scoreMult());
    }

    /** Classic wave bonus — {@code main.py:1783}: {@code 80 + wave * 22 + wave_purse}. */
    public static int waveBonus(int wave, CombatModifiers mods) {
        return 80 + wave * 22 + (int) mods.wavePurse();
    }
}
