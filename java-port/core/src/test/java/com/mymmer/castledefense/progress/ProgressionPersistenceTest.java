package com.mymmer.castledefense.progress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.mymmer.castledefense.config.GameConfig;
import com.mymmer.castledefense.game.GameMode;
import com.mymmer.castledefense.game.GameState;
import com.mymmer.castledefense.persistence.SaveData;
import com.mymmer.castledefense.skill.SkillId;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What survives a run, and what must not.
 *
 * <h2>The persistence contract</h2>
 *
 * <p>Read out of {@code main.py:166 Settings}, which writes exactly three keys:
 *
 * <pre>
 *   muted        settings
 *   difficulty   settings -- the PREFERRED one, not a run's
 *   high_score   one shared best, across both modes
 * </pre>
 *
 * <p>Everything else is per-run and is rebuilt by {@code Game.reset()}: talent
 * points and ranks, shop purchases, cursor levels, unlocked skills, cooldowns,
 * gold, score, wave and tier.
 *
 * <p>The Java save adds {@code skin}, {@code quality} and {@code haptics} — all
 * settings, all approved in Phase 3, and none of them run state. <b>No schema
 * change is needed for Phase 9 and {@code saveVersion} is not bumped.</b> The
 * save system being able to hold progression is not a reason to put progression
 * in it.
 */
class ProgressionPersistenceTest {

    // ========================================================================
    //  The save holds settings only
    // ========================================================================

    /** Every field the save is allowed to have. Anything else fails the test. */
    private static final String[] ALLOWED = {
        "saveVersion", "highScore", "difficulty", "skin", "quality", "haptics", "muted",
    };

    @Test
    @DisplayName("the save holds settings and a high score, and nothing about a run")
    void saveHoldsNoRunState() {
        for (Field f : SaveData.class.getDeclaredFields()) {
            if (Modifier.isStatic(f.getName().isEmpty() ? 0 : f.getModifiers())) {
                continue;
            }
            if (f.isSynthetic()) {
                continue;
            }
            boolean allowed = false;
            for (String name : ALLOWED) {
                if (name.equals(f.getName())) {
                    allowed = true;
                    break;
                }
            }
            if (!allowed) {
                fail("SaveData." + f.getName() + " is not in the persistence contract. "
                        + "Talent ranks, points, shop purchases, skill cooldowns and "
                        + "run economy are PER RUN -- the Python source persists none "
                        + "of them. See PERSISTENCE.md before adding a field.");
            }
        }
    }

    @Test
    @DisplayName("no save field is named after a progression system")
    void noProgressionFieldsByName() {
        String[] banned = {"talent", "point", "rank", "shop", "purchase", "skill",
            "cooldown", "gold", "score" + "PerRun", "wave", "tier", "bounce", "grab",
            "multi", "outpost", "barricade", "spike"};
        for (Field f : SaveData.class.getDeclaredFields()) {
            String n = f.getName().toLowerCase(java.util.Locale.ROOT);
            for (String b : banned) {
                if (n.contains(b)) {
                    fail("SaveData." + f.getName() + " looks like run state");
                }
            }
        }
    }

    @Test
    @DisplayName("the save version is unchanged -- Phase 9 needed no schema change")
    void saveVersionUnchanged() {
        assertEquals(1, SaveData.CURRENT_VERSION,
                "Phase 9 added no persistent state, so there is nothing to migrate");
    }

    // ========================================================================
    //  New-run reset semantics
    // ========================================================================

    @Test
    @DisplayName("a new Classic run starts from nothing")
    void newClassicRunResetsEverything() {
        TestRun r = playALittle(GameMode.ENDLESS);
        r.run.beginRun(GameMode.CLASSIC, r.difficulty("normal"), 11L);
        assertFreshRun(r);
        assertEquals(1, r.session().wave(), "Classic starts on wave 1");
    }

    @Test
    @DisplayName("a new Endless run starts from nothing")
    void newEndlessRunResetsEverything() {
        TestRun r = playALittle(GameMode.CLASSIC);
        r.run.beginRun(GameMode.ENDLESS, r.difficulty("normal"), 12L);
        assertFreshRun(r);
        assertEquals(1, r.session().wave(), "Endless starts on tier 1");
        assertEquals(0d, r.session().playTime(), 0d);
    }

    @Test
    @DisplayName("restarting after game over leaves nothing behind")
    void restartAfterGameOver() {
        TestRun r = playALittle(GameMode.ENDLESS);
        r.run.castle().takeDamage(r.run.castle().maxHp() * 10f);
        r.step();
        assertEquals(GameState.GAMEOVER, r.state());

        r.run.beginRun(GameMode.ENDLESS, r.difficulty("normal"), 13L);
        assertEquals(GameState.PLAYING, r.state());
        assertFreshRun(r);
    }

    @Test
    @DisplayName("switching mode between runs carries nothing across")
    void switchingModes() {
        TestRun r = playALittle(GameMode.CLASSIC);
        int points = r.talents().availablePoints();
        assertTrue(points > 0, "the first run earned something");

        r.run.beginRun(GameMode.ENDLESS, r.difficulty("hard"), 14L);
        assertFreshRun(r);
        assertEquals("hard", r.session().difficulty().id(), "and the new choice applies");
    }

    @Test
    @DisplayName("a talent bought in one run is gone in the next")
    void talentsDoNotCarryOver() {
        TestRun r = new TestRun().beginClassic();
        r.grantPoints(5).buyTalent("rate", 5);
        assertEquals(5, r.talents().rank("rate"));
        assertEquals(1d - 0.30d, r.run.modifiers().towerRate(), 1e-9);

        r.run.beginRun(GameMode.CLASSIC, r.difficulty("normal"), 15L);
        assertEquals(0, r.talents().rank("rate"));
        assertEquals(1d, r.run.modifiers().towerRate(), 0d,
                "and the effect is gone with it");
    }

    @Test
    @DisplayName("shop prices reset, so the second run is not more expensive")
    void shopPricesReset() {
        TestRun r = new TestRun().beginClassic().grantGold(100000);
        for (int i = 0; i < 4; i++) {
            r.shop().buy("bowman");
        }
        assertTrue(r.shop().currentCost("bowman") > 110);

        r.run.beginRun(GameMode.CLASSIC, r.difficulty("normal"), 16L);
        assertEquals(110, r.shop().currentCost("bowman"));
        assertEquals(0, r.shop().purchaseCount("bowman"));
    }

    @Test
    @DisplayName("an unlocked skill and its cooldown are both gone next run")
    void skillsDoNotCarryOver() {
        TestRun r = new TestRun().beginEndless();
        r.skills().unlockNext();
        r.skills().castAt(SkillId.LIGHTNING, 800f, 500f);
        assertTrue(r.skills().cooldownRemaining(SkillId.LIGHTNING) > 0d);

        r.run.beginRun(GameMode.ENDLESS, r.difficulty("normal"), 17L);
        assertEquals(0, r.skills().unlockedCount());
        assertFalse(r.skills().isUnlocked(SkillId.LIGHTNING));
        assertEquals(0d, r.skills().cooldownRemaining(SkillId.LIGHTNING), 0d);
    }

    // ------------------------------------------------------------------------

    /** Plays far enough to accumulate progression of every kind. */
    private static TestRun playALittle(GameMode mode) {
        TestRun r = new TestRun();
        r.begin(mode, "normal");
        r.grantGold(100000);
        r.grantPoints(6);
        r.buyTalent("rate", 3);
        r.shop().buy("bowman");
        r.shop().buy("bounce");
        r.shop().buy("grab");
        r.skills().unlockNext();
        r.skills().castAt(SkillId.LIGHTNING, 800f, 500f);
        r.session().addScore(500, 2, 2.5f);
        r.survivingSeconds(2);
        return r;
    }

    private static void assertFreshRun(TestRun r) {
        assertEquals(0, r.talents().availablePoints(), "no talent points");
        assertEquals(0, r.talents().earnedPoints(), "not even earned ones");
        assertEquals(0, r.talents().rank("rate"), "no ranks");
        assertEquals(0, r.shop().purchaseCount("bowman"), "no purchase history");
        assertEquals(0, r.run.bounceLevel(), "no bounce");
        assertEquals(0, r.run.grabLevel(), "no grab strength");
        assertEquals(0, r.run.multiLevel(), "no gloves");
        assertEquals(0, r.skills().unlockedCount(), "no skills");
        assertEquals(0, r.skills().castCount(), "no casts");
        assertEquals(GameConfig.STARTING_GOLD, r.session().gold(), "the starting purse");
        assertEquals(0, r.session().score(), "no score");
        assertEquals(0, r.session().bestFling(), "no best fling");
        assertEquals(1f, r.session().bestCombo(), 0f, "no best combo");
        assertEquals(0, r.session().kills(), "no kills");
        assertEquals(0, r.session().casts(), "no casts");
        assertFalse(r.session().hornUsed(), "a fresh horn");
        assertEquals(0, r.aliveEnemies(), "an empty field");
        assertEquals(0, r.run.fireZones().size, "no burning ground");
        assertEquals(0, r.run.tornados().size, "no funnels");
        assertEquals(0, r.run.projectiles().size(), "nothing in the air");
        assertEquals(1, r.run.castle().wallLevel(), "an unreinforced keep");
        assertEquals(0, r.run.castle().towerCount(), "and a bare wall");
    }
}
