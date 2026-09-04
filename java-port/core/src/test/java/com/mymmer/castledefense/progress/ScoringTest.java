package com.mymmer.castledefense.progress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mymmer.castledefense.config.GameConfig;
import com.mymmer.castledefense.config.Tuning;
import com.mymmer.castledefense.defence.CombatModifiers;
import com.mymmer.castledefense.enemy.Enemy;
import com.mymmer.castledefense.enemy.EnemyType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Gold, the crowd multiplier, fling score and the combo. */
class ScoringTest {

    // ========================================================================
    //  Crowd gold
    // ========================================================================

    @Test
    @DisplayName("the first four mobs on screen are free; the fifth starts paying")
    void crowdMultiplierThreshold() {
        assertEquals(1f, mult(0), 1e-6f);
        assertEquals(1f, mult(4), 1e-6f, "POP_GOLD_FREE mobs earn no bonus");
        assertEquals(1f + GameConfig.POP_GOLD_STEP, mult(5), 1e-6f);
        assertEquals(1f + GameConfig.POP_GOLD_STEP * 2, mult(6), 1e-6f);
    }

    @Test
    @DisplayName("the crowd multiplier is capped at 3.0")
    void crowdMultiplierCap() {
        assertEquals(GameConfig.POP_GOLD_CAP, mult(100), 1e-6f);
        assertEquals(GameConfig.POP_GOLD_CAP, mult(1000), 1e-6f);
        //  1 + 0.055 * (n - 4) = 3 at n = 40.36..., so 40 is the last one
        //  under the cap and 41 is the first at it
        assertEquals(2.98f, mult(40), 1e-5f);
        assertTrue(mult(40) < GameConfig.POP_GOLD_CAP);
        assertEquals(GameConfig.POP_GOLD_CAP, mult(41), 1e-6f);
    }

    @Test
    @DisplayName("the horn and the difficulty multiply on top of the crowd bonus")
    void crowdMultiplierModifiers() {
        float plain = Scoring.crowdGoldMultiplier(20, 0f, 1f, CombatModifiers.NONE);
        float horned = Scoring.crowdGoldMultiplier(20, Tuning.HORN_BONUS, 1f,
                CombatModifiers.NONE);
        float rich = Scoring.crowdGoldMultiplier(20, 0f, 1.35f, CombatModifiers.NONE);
        assertEquals(plain * (1f + Tuning.HORN_BONUS), horned, 1e-5f);
        assertEquals(plain * 1.35f, rich, 1e-5f);
    }

    @Test
    @DisplayName("the dying mob is counted in its own payout")
    void theDyingMobCountsItself() {
        //  The documented quirk.  Enemy.die reads the multiplier BEFORE clearing
        //  its own alive flag, so a lone mob is n=1 and a crowd of ten is n=10 --
        //  never n-1.  Removing it first would quietly cut every reward.
        TestRun r = new TestRun().beginClassic();
        for (int i = 0; i < 10; i++) {
            r.run.spawnEnemy(EnemyType.SCOUT, 1);
        }
        assertEquals(10, r.aliveEnemies());

        float atTen = r.run.goldMultiplier();
        assertEquals(Scoring.crowdGoldMultiplier(10, 0f, r.session().goldScale(),
                CombatModifiers.NONE), atTen, 1e-6f);

        int before = r.session().gold();
        Enemy victim = firstAlive(r);
        int baseGold = victim.gold();
        victim.die(false);

        int paid = r.session().gold() - before;
        assertEquals(Scoring.killPayout(baseGold, atTen), paid,
                "paid at the ten-mob rate, not the nine-mob rate");

        float atNine = Scoring.crowdGoldMultiplier(9, 0f, r.session().goldScale(),
                CombatModifiers.NONE);
        assertTrue(atTen > atNine, "the two rates genuinely differ");
        assertTrue(paid >= Scoring.killPayout(baseGold, atNine),
                "and the mob is never paid at the lower one");
    }

    @Test
    @DisplayName("a kill always pays at least one coin")
    void killPayoutFloor() {
        assertEquals(1, Scoring.killPayout(0, 1f));
        assertEquals(1, Scoring.killPayout(1, 0.0001f));
        assertEquals(8, Scoring.killPayout(8, 1f));
        assertEquals(24, Scoring.killPayout(8, GameConfig.POP_GOLD_CAP));
    }

    @Test
    @DisplayName("a Treasure Goblin that escapes pays nothing at all")
    void silentEscapePaysNothing() {
        TestRun r = new TestRun().beginClassic();
        Enemy goblin = r.run.spawnEnemy(EnemyType.TREASURE_GOBLIN, 1);
        int gold = r.session().gold();
        int kills = r.session().kills();

        goblin.die(true);           // silent: what escape() does

        assertEquals(gold, r.session().gold(), "no gold");
        assertEquals(kills, r.session().kills(), "and no kill either");
    }

    // ========================================================================
    //  Fling score
    // ========================================================================

    @Test
    @DisplayName("fling score is distance plus airtime, both from the source constants")
    void flingBase() {
        //  travel = |x - x0| + max(0, y0 - peak)
        assertEquals(800f, Scoring.flingTravel(1200f, 400f, 600f, 600f), 1e-4f);
        assertEquals(800f + 420f, Scoring.flingTravel(1200f, 400f, 600f, 180f), 1e-4f);
        assertEquals(0f, Scoring.flingTravel(400f, 400f, 600f, 700f), 1e-4f,
                "a mob that never rose scores no height");

        int pts = Scoring.flingPoints(1220f, 1.75, 0);
        assertEquals((int) (1220f * GameConfig.SCORE_PER_PX
                + 1.75f * GameConfig.SCORE_PER_SEC), pts);
    }

    @Test
    @DisplayName("the combo multiplies the whole score, at 0.75 per mob struck")
    void comboMultiplier() {
        assertEquals(1f, Scoring.flingCombo(0), 1e-6f);
        assertEquals(1.75f, Scoring.flingCombo(1), 1e-6f);
        assertEquals(4f, Scoring.flingCombo(4), 1e-6f);

        int solo = Scoring.flingPoints(1000f, 1.0, 0);
        int three = Scoring.flingPoints(1000f, 1.0, 3);
        assertEquals((int) (solo * 1f), solo);
        assertTrue(three > solo * 3, "three hits is more than triple");
    }

    @Test
    @DisplayName("both truncations happen, and in the source's order")
    void doubleTruncation() {
        //  int() at resolve_fling, then int() again at add_score.  Rounding
        //  once at the end would give a different number.
        int raw = Scoring.flingPoints(333.3f, 0.5, 1);
        int awarded = Scoring.awardedScore(raw, Tuning.HORN_BONUS, CombatModifiers.NONE);
        assertEquals((int) (raw * (1f + Tuning.HORN_BONUS)), awarded);
    }

    @Test
    @DisplayName("best fling tracks the awarded score, and best combo only real combos")
    void bests() {
        TestRun r = new TestRun().beginClassic();
        RunSession s = r.session();
        assertEquals(0, s.bestFling());
        assertEquals(1f, s.bestCombo(), 0f);

        s.addScore(500, 0, 1f);
        assertEquals(500, s.score());
        assertEquals(500, s.bestFling());
        assertEquals(1f, s.bestCombo(), 0f, "a solo fling sets no combo record");

        s.addScore(300, 2, 2.5f);
        assertEquals(800, s.score());
        assertEquals(500, s.bestFling(), "the best is still the bigger one");
        assertEquals(2.5f, s.bestCombo(), 1e-6f);

        s.addScore(100, 1, 1.75f);
        assertEquals(2.5f, s.bestCombo(), 1e-6f, "a smaller combo does not lower it");
    }

    @Test
    @DisplayName("a combo award lights the combo flash, and it decays in every state")
    void comboFlashDecays() {
        TestRun r = new TestRun().beginClassic();
        r.session().addScore(100, 3, 3.25f);
        assertEquals(1f, r.session().comboFlash(), 0f);

        r.step();
        assertTrue(r.session().comboFlash() < 1f);

        //  Python decays it before the state check, so a pause does not freeze it
        r.world.setState(com.mymmer.castledefense.game.GameState.PAUSED);
        float paused = r.session().comboFlash();
        r.step();
        assertTrue(r.session().comboFlash() < paused,
                "the flash keeps fading on the pause screen");
    }

    @Test
    @DisplayName("the horn raises both gold and score for the rest of the stretch")
    void hornRaisesRewards() {
        TestRun r = new TestRun().beginClassic();
        float before = r.run.goldMultiplier();
        int scoreBefore = scoreFor(r, 1000);

        r.session().consumeHorn();

        assertEquals(before * (1f + Tuning.HORN_BONUS), r.run.goldMultiplier(), 1e-5f);
        assertEquals((int) (1000 * (1f + Tuning.HORN_BONUS)), scoreFor(r, 1000));
        assertTrue(scoreFor(r, 1000) > scoreBefore);
    }

    // ------------------------------------------------------------------------

    private static float mult(int alive) {
        return Scoring.crowdGoldMultiplier(alive, 0f, 1f, CombatModifiers.NONE);
    }

    private static int scoreFor(TestRun r, int points) {
        int before = r.session().score();
        r.session().addScore(points, 0, 1f);
        return r.session().score() - before;
    }

    private static Enemy firstAlive(TestRun r) {
        for (int i = 0; i < r.run.horde().size(); i++) {
            if (r.run.horde().get(i).isAlive()) {
                return r.run.horde().get(i);
            }
        }
        throw new IllegalStateException("no live enemy");
    }
}
