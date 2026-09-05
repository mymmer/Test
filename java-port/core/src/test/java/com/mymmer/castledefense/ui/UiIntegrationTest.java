package com.mymmer.castledefense.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.badlogic.gdx.utils.Array;
import com.mymmer.castledefense.boss.BossType;
import com.mymmer.castledefense.config.Tuning;
import com.mymmer.castledefense.defence.TowerType;
import com.mymmer.castledefense.enemy.Enemy;
import com.mymmer.castledefense.game.GameMode;
import com.mymmer.castledefense.game.GameState;
import com.mymmer.castledefense.skill.SkillId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The interface driving the real gameplay, through the real screens.
 *
 * <p>Each of these presses a control and then checks the <b>authoritative</b>
 * subsystem, not a UI field. That is the point: the interface sends commands and
 * owns nothing, so the proof of a working button is a changed game.
 */
class UiIntegrationTest {

    // ========================================================================
    //  Shop
    // ========================================================================

    @Test
    @DisplayName("tapping a card buys through the real Shop, at the Shop's price")
    void shopCardBuys() {
        TestUi t = new TestUi().startRun(GameMode.CLASSIC, "normal");
        int gold = t.run.session().gold();
        int cost = t.run.shop().currentCost("bowman");
        assertEquals(110, cost, "the price is the shop's, not the card's");

        t.press(t.ui.shop().card("bowman"));

        assertEquals(gold - cost, t.run.session().gold(), "the shop took the gold");
        assertEquals(1, t.run.run.castle().countOf(TowerType.BOWMAN));
        assertEquals(1, t.run.shop().purchaseCount("bowman"));
        assertEquals((int) (110 * 1.26), t.run.shop().currentCost("bowman"),
                "and the next one is dearer, by the shop's curve");
    }

    @Test
    @DisplayName("tapping an unaffordable card is consumed and changes nothing")
    void unaffordableCardRefuses() {
        TestUi t = new TestUi().startRun(GameMode.CLASSIC, "normal");
        assertEquals(220, t.run.session().gold());
        assertEquals(380, t.run.shop().currentCost("cannon"), "more than the purse");

        String hit = t.press(t.ui.shop().card("cannon"));

        assertNotNull(hit, "the press was consumed, so it cannot reach the world");
        assertEquals(220, t.run.session().gold(), "and cost nothing");
        assertEquals(0, t.run.run.castle().countOf(TowerType.CANNON));
        assertEquals(380, t.run.shop().currentCost("cannon"), "the price did not move");
    }

    @Test
    @DisplayName("the UI never computes a price -- it reads the one the shop gives")
    void uiDoesNotComputePrices() {
        TestUi t = new TestUi().startRun(GameMode.CLASSIC, "normal");
        t.run.grantPoints(20).buyTalentDeep("haggle", 4);
        t.ui.layout();
        //  the discount is the talent's and the ordering is the shop's; the card
        //  view simply carries the answer
        assertEquals(t.run.shop().currentCost("bowman"),
                t.ui.shop().cardView("bowman").cost,
                "the card shows exactly what buy() will charge");
        assertEquals((int) (110 * (1d - 0.24d)), t.ui.shop().cardView("bowman").cost);
    }

    @Test
    @DisplayName("the number hotkeys buy the same items in the same order")
    void shopHotkeys() {
        TestUi t = new TestUi().startRun(GameMode.CLASSIC, "normal");
        t.run.grantGold(100000);
        assertTrue(t.key(UiRoot.Key.SHOP_ITEM_1));
        assertEquals(1, t.run.shop().purchaseCount("bowman"), "item 1 is the Bowman");
        assertTrue(t.key(UiRoot.Key.SHOP_ITEM_2));
        assertEquals(1, t.run.shop().purchaseCount("ballista"));
    }

    // ========================================================================
    //  The Endless realtime shop, through the real UI
    // ========================================================================

    @Test
    @DisplayName("opening the armoury from the HUD freezes the world, and closing resumes it")
    void realtimeShopFreezesThroughTheUi() {
        //  The Phase 8 freeze contract, exercised the way a player reaches it.
        //  The UI does not stop anything itself: it asks for a state change and
        //  the state machine does the rest.
        TestUi t = new TestUi().startRun(GameMode.ENDLESS, "normal").beginPlaying();
        t.seconds(20);
        assertTrue(t.run.aliveEnemies() > 0, "there should be a horde to freeze");

        double time = t.run.session().playTime();
        int alive = t.run.aliveEnemies();
        int tier = t.run.session().wave();
        double spawnTimer = t.run.endless().spawnTimer();
        float[] xs = enemyXs(t);

        t.press(t.ui.hud().shopButton());
        assertEquals(GameState.SHOP, t.state());

        t.steps(120);               // the Python self-test's 120 frames

        assertEquals(time, t.run.session().playTime(), 0d, "play time is frozen");
        assertEquals(alive, t.run.aliveEnemies(), "the enemy count is frozen");
        assertEquals(tier, t.run.session().wave(), "the tier ladder is frozen");
        assertEquals(spawnTimer, t.run.endless().spawnTimer(), 0d, "and the spawn timer");
        float[] after = enemyXs(t);
        assertEquals(xs.length, after.length);
        for (int i = 0; i < xs.length; i++) {
            assertEquals(xs[i], after[i], 0f, "enemy " + i + " moved while shopping");
        }

        t.press(t.ui.shop().startButton());
        assertEquals(GameState.PLAYING, t.state());
        t.steps(60);
        assertEquals(time + 1.0, t.run.session().playTime(), 1e-9,
                "exactly one more second, and none of the shopping");
    }

    @Test
    @DisplayName("buying while frozen changes the game and still does not advance it")
    void buyingWhileFrozen() {
        TestUi t = new TestUi().startRun(GameMode.ENDLESS, "normal").beginPlaying();
        t.seconds(10);
        t.run.grantGold(100000);
        t.press(t.ui.hud().shopButton());

        double time = t.run.session().playTime();
        t.press(t.ui.shop().card("bowman"));

        assertEquals(1, t.run.run.castle().countOf(TowerType.BOWMAN), "it was bought");
        assertEquals(time, t.run.session().playTime(), 0d, "and no time passed");
    }

    // ========================================================================
    //  Talents
    // ========================================================================

    @Test
    @DisplayName("tap to inspect, tap again to buy -- through the real TalentTree")
    void talentTapToInspectThenBuy() {
        TestUi t = new TestUi().startRun(GameMode.CLASSIC, "normal");
        t.run.grantPoints(3);
        t.ui.navigation().openTalents();
        t.ui.layout();

        UiRect node = t.ui.talents().node("rate");
        assertNotNull(node);
        assertEquals(0, t.run.talents().rank("rate"));

        t.press(node);
        assertEquals("rate", t.ui.talents().selected(), "the first tap selects");
        assertEquals(0, t.run.talents().rank("rate"), "and buys nothing");

        t.press(node);
        assertEquals(1, t.run.talents().rank("rate"), "the second tap buys a rank");
        assertEquals(2, t.run.talents().availablePoints());
        assertEquals(1d - 0.06d, t.run.run.modifiers().towerRate(), 1e-9,
                "and the modifier moved with it");
    }

    @Test
    @DisplayName("tapping a locked node selects it and spends nothing")
    void lockedTalentRefuses() {
        TestUi t = new TestUi().startRun(GameMode.CLASSIC, "normal");
        t.run.grantPoints(1);
        t.ui.navigation().openTalents();
        t.ui.layout();

        UiRect node = t.ui.talents().node("crit");        // tier 2, nothing spent yet
        assertFalse(t.run.talents().isUnlocked("crit"));
        t.press(node);
        t.press(node);
        assertEquals(0, t.run.talents().rank("crit"));
        assertEquals(1, t.run.talents().availablePoints(), "the point is still there");
    }

    @Test
    @DisplayName("the tooltip number is the tree's own value, not a second calculation")
    void talentPreviewUsesTheTree() {
        TestUi t = new TestUi().startRun(GameMode.CLASSIC, "normal");
        t.run.grantPoints(5).buyTalent("rate", 3);
        t.ui.navigation().openTalents();
        t.ui.layout();
        assertEquals(t.run.talents().value("rate"),
                t.ui.talents().previewValue("rate"), 1e-9);
    }

    @Test
    @DisplayName("Deep Foundations can be bought from the UI and still changes no health")
    void deepFoundationsStaysInert() {
        //  The quirk, through the interface this time: the node is real, the
        //  purchase is real, and the castle is untouched.
        TestUi t = new TestUi().startRun(GameMode.CLASSIC, "normal");
        t.run.grantPoints(5);
        t.ui.navigation().openTalents();
        t.ui.layout();

        float maxBefore = t.run.run.castle().maxHp();
        UiRect node = t.ui.talents().node("maxhp");
        for (int i = 0; i < 5; i++) {
            t.press(node);                  // select
            t.press(node);                  // buy
        }
        assertEquals(5, t.run.talents().rank("maxhp"), "all five ranks bought");
        assertEquals(maxBefore, t.run.run.castle().maxHp(), 0f,
                "and the castle is exactly as tough as it was");
    }

    // ========================================================================
    //  Skills
    // ========================================================================

    @Test
    @DisplayName("tapping a targeted skill arms it; tapping the world casts it")
    void skillTwoStageTargeting() {
        TestUi t = new TestUi().startRun(GameMode.ENDLESS, "normal").beginPlaying();
        t.run.skills().unlockNext();                 // Lightning
        t.ui.layout();

        UiRect slot = t.ui.hud().skillSlots().get(SkillId.LIGHTNING.ordinal());
        assertTrue(slot.visible());
        t.press(slot);
        assertEquals(SkillId.LIGHTNING, t.run.skills().aiming(), "armed, not cast");
        assertEquals(0d, t.run.skills().cooldownRemaining(SkillId.LIGHTNING), 0d);

        assertTrue(t.ui.hud().castArmedSkillAt(900f, 500f), "the world tap casts it");
        assertNull(t.run.skills().aiming());
        assertTrue(t.run.skills().cooldownRemaining(SkillId.LIGHTNING) > 0d);
    }

    @Test
    @DisplayName("arming a skill stops the same press grabbing the mob under the button")
    void armingDoesNotGrab() {
        TestUi t = new TestUi().startRun(GameMode.ENDLESS, "normal").beginPlaying();
        t.run.skills().unlockNext();
        t.ui.layout();

        Enemy mob = t.run.run.spawnEnemy(com.mymmer.castledefense.enemy.EnemyType.SCOUT, 1);
        UiRect slot = t.ui.hud().skillSlots().get(SkillId.LIGHTNING.ordinal());
        //  put the mob exactly under the button
        mob.setX(slot.centerX());

        String consumed = t.press(slot);
        assertNotNull(consumed, "the skill bar claimed the press");
        assertFalse(t.run.run.cursor().busy(), "and nothing was grabbed");
    }

    @Test
    @DisplayName("an untargeted skill fires on the tap, with no second stage")
    void untargetedSkillFiresImmediately() {
        TestUi t = new TestUi().startRun(GameMode.ENDLESS, "normal").beginPlaying();
        t.run.skills().unlockNext();
        t.run.skills().unlockNext();                 // Meteor
        t.ui.layout();

        UiRect slot = t.ui.hud().skillSlots().get(SkillId.METEOR.ordinal());
        t.press(slot);
        assertNull(t.run.skills().aiming(), "nothing to aim");
        assertTrue(t.run.skills().cooldownRemaining(SkillId.METEOR) > 0d, "it fired");
        assertEquals(Tuning.METEOR_COUNT, t.run.run.projectiles().size());
    }

    @Test
    @DisplayName("the cooldown the bar shows is the gameplay one, and it freezes with the world")
    void cooldownComesFromGameplay() {
        TestUi t = new TestUi().startRun(GameMode.ENDLESS, "normal").beginPlaying();
        t.run.skills().unlockNext();
        t.run.skills().castAt(SkillId.LIGHTNING, 800f, 500f);
        t.ui.layout();

        SkillPanelView before = view(t);
        assertFalse(before.ready);
        assertEquals(Tuning.LIGHTNING_COOLDOWN, before.remaining, 1e-9);

        //  freeze: the bar must not tick
        t.press(t.ui.hud().shopButton());
        t.steps(120);
        t.ui.layout();
        assertEquals(before.remaining, view(t).remaining, 0d,
                "the UI has no timer of its own");

        t.press(t.ui.shop().startButton());
        t.seconds(1);
        assertEquals(before.remaining - 1.0, view(t).remaining, 1e-9);
    }

    @Test
    @DisplayName("a skill that is not ready never displays as ready")
    void readinessIsNeverInferredFromASeconds() {
        TestUi t = new TestUi().startRun(GameMode.ENDLESS, "normal").beginPlaying();
        t.run.skills().unlockNext();
        t.run.skills().castAt(SkillId.LIGHTNING, 800f, 500f);

        //  step to the last moment before it comes back
        int steps = (int) Math.ceil(Tuning.LIGHTNING_COOLDOWN
                / com.mymmer.castledefense.game.Simulation.FIXED_DT) - 1;
        t.run.survivingSteps(steps);
        t.ui.layout();
        assertFalse(t.run.skills().isReady(SkillId.LIGHTNING));
        assertTrue(view(t).remaining > 0d,
                "readiness comes from isReady(), never from a rounded remaining");

        t.run.survivingSteps(1);
        assertTrue(t.run.skills().isReady(SkillId.LIGHTNING));
    }

    private static final class SkillPanelView {
        boolean ready;
        double remaining;
    }

    private static SkillPanelView view(TestUi t) {
        SkillPanelView v = new SkillPanelView();
        v.ready = t.run.skills().isReady(SkillId.LIGHTNING);
        v.remaining = t.run.skills().cooldownRemaining(SkillId.LIGHTNING);
        return v;
    }

    // ========================================================================
    //  The Challenge Horn
    // ========================================================================

    @Test
    @DisplayName("the horn button sends the command and the horn decides the rest")
    void hornButton() {
        TestUi t = new TestUi().startRun(GameMode.ENDLESS, "normal").beginPlaying();
        t.seconds(3);
        int alive = t.run.aliveEnemies();
        assertFalse(t.run.session().hornUsed());

        t.press(t.ui.hud().hornButton());

        assertTrue(t.run.session().hornUsed());
        assertEquals(alive + Tuning.ENDLESS_HORN_RUSH, t.run.aliveEnemies(),
                "the horn chose the units, not the button");
    }

    @Test
    @DisplayName("the Endless horn stays spent for the whole run, across tiers")
    void hornStaysSpentInEndless() {
        TestUi t = new TestUi().startRun(GameMode.ENDLESS, "normal").beginPlaying();
        t.seconds(3);
        t.press(t.ui.hud().hornButton());
        assertTrue(t.run.session().hornUsed());

        t.run.scheduleSeconds(300);           // five minutes, ten tiers
        t.ui.layout();
        assertEquals(11, t.run.session().wave());
        assertTrue(t.run.session().hornUsed(), "still spent");

        int alive = t.run.aliveEnemies();
        t.press(t.ui.hud().hornButton());
        assertEquals(alive, t.run.aliveEnemies(), "and pressing it does nothing");
    }

    @Test
    @DisplayName("the Classic horn is re-armed by the next wave")
    void hornRearmsInClassic() {
        TestUi t = new TestUi().startRun(GameMode.CLASSIC, "normal").beginPlaying();
        t.seconds(2);
        t.press(t.ui.hud().hornButton());
        assertTrue(t.run.session().hornUsed());

        t.run.killEveryEnemy();
        t.run.seconds(2);
        assertEquals(GameState.SHOP, t.state());
        t.press(t.ui.shop().startButton());
        assertFalse(t.run.session().hornUsed(), "a new wave, a new horn");
    }

    // ========================================================================
    //  Boss bars
    // ========================================================================

    @Test
    @DisplayName("two live bosses get two bars, and a death takes one away cleanly")
    void twoBossBars() {
        TestUi t = new TestUi().startRun(GameMode.ENDLESS, "normal").beginPlaying();
        t.ui.layout();
        assertEquals(0, t.ui.hud().visibleBossBars());

        t.run.run.summonBoss(BossType.TROLL_KING, 5);
        t.ui.layout();
        assertEquals(1, t.ui.hud().visibleBossBars());

        t.run.run.summonBoss(BossType.DRAGON, 5);
        t.ui.layout();
        assertEquals(2, t.ui.hud().visibleBossBars(), "not a single currentBoss");

        //  the two bars do not sit on top of each other
        UiRect a = t.ui.hud().bossBars().get(0);
        UiRect b = t.ui.hud().bossBars().get(1);
        assertFalse(a.hitOverlaps(b), "the bars must be readable side by side");

        t.run.run.bossRegistry().liveBosses().get(0).die(true);
        t.run.step();
        t.ui.layout();
        assertEquals(1, t.ui.hud().visibleBossBars(), "no stale bar for a dead boss");

        t.run.run.bossRegistry().liveBosses().get(0).die(true);
        t.run.step();
        t.ui.layout();
        assertEquals(0, t.ui.hud().visibleBossBars());
    }

    // ========================================================================
    //  Game over and the fresh run
    // ========================================================================

    @Test
    @DisplayName("restarting from the UI leaves nothing of the old run behind")
    void restartIsFresh() {
        TestUi t = new TestUi().startRun(GameMode.ENDLESS, "hard").beginPlaying();
        t.run.grantGold(100000);
        t.run.grantPoints(5).buyTalent("rate", 3);
        t.run.shop().buy("bowman");
        t.run.skills().unlockNext();
        t.run.skills().castAt(SkillId.LIGHTNING, 800f, 500f);
        t.seconds(5);
        assertTrue(t.run.aliveEnemies() > 0);

        t.run.run.castle().takeDamage(t.run.run.castle().maxHp() * 10f);
        t.run.step();
        assertEquals(GameState.GAMEOVER, t.state());
        t.press(t.ui.gameOver().restartButton());
        assertEquals(GameState.MENU, t.state());

        //  and a new run through the real menu starts from nothing
        t.startRun(GameMode.CLASSIC, "normal");
        assertEquals(0, t.run.talents().rank("rate"));
        assertEquals(0, t.run.talents().availablePoints());
        assertEquals(0, t.run.shop().purchaseCount("bowman"));
        assertEquals(0, t.run.run.castle().towerCount());
        assertEquals(0, t.run.skills().unlockedCount());
        assertEquals(0d, t.run.skills().cooldownRemaining(SkillId.LIGHTNING), 0d);
        assertEquals(0, t.run.aliveEnemies());
        assertEquals(0, t.run.run.projectiles().size());
        assertEquals(0, t.run.run.droppedItems().size());
        assertEquals(com.mymmer.castledefense.config.GameConfig.STARTING_GOLD,
                t.run.session().gold());
        assertEquals("normal", t.run.session().difficulty().id());
    }

    @Test
    @DisplayName("the screens bind to the new run, not the old one")
    void screensRebindAfterRestart() {
        //  Phase 9 found structures surviving a reset once; the UI holds
        //  references too, so this is the same class of bug one layer up.
        TestUi t = new TestUi().startRun(GameMode.CLASSIC, "normal");
        t.run.grantGold(100000);
        t.press(t.ui.shop().card("bowman"));
        assertEquals(1, t.ui.shop().cardView("bowman").level);

        t.beginPlaying();
        t.run.run.castle().takeDamage(t.run.run.castle().maxHp() * 10f);
        t.run.step();
        t.press(t.ui.gameOver().restartButton());
        t.startRun(GameMode.CLASSIC, "normal");
        t.ui.layout();

        assertEquals(0, t.ui.shop().cardView("bowman").level,
                "the card reads the NEW run's shop");
        assertEquals(110, t.ui.shop().cardView("bowman").cost);
        assertEquals(0, t.ui.hud().visibleBossBars(), "and no boss bar survived");
    }

    // ------------------------------------------------------------------------

    private static float[] enemyXs(TestUi t) {
        Array<Enemy> horde = new Array<>();
        for (int i = 0; i < t.run.run.horde().size(); i++) {
            horde.add(t.run.run.horde().get(i));
        }
        float[] xs = new float[horde.size];
        for (int i = 0; i < xs.length; i++) {
            xs[i] = horde.get(i).x();
        }
        return xs;
    }
}
