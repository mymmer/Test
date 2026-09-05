package com.mymmer.castledefense.ui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mymmer.castledefense.enemy.EnemyType;
import com.mymmer.castledefense.game.GameMode;
import com.mymmer.castledefense.game.GameState;
import com.mymmer.castledefense.skill.SkillId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Presses going through the production input stack, not around it.
 *
 * <p>Every test here drives {@code GameInput.touchDown} with a <b>screen pixel</b>
 * and lets the real {@link com.mymmer.castledefense.input.InputRouter} decide who
 * gets it. A {@link TestUi.WorldSpy} sits underneath the interface as the world
 * handler, so "the UI swallowed it" is proven by the world never being told —
 * which is the only formulation that catches the bug where a button works
 * <em>and</em> grabs the enemy behind it.
 */
class UiInputTest {

    // ========================================================================
    //  Consumption
    // ========================================================================

    @Test
    @DisplayName("a press on a HUD control never reaches the world")
    void hudControlConsumes() {
        TestUi t = new TestUi().startRun(GameMode.ENDLESS, "normal").beginPlaying();
        t.run.skills().unlockNext();
        t.ui.layout();

        UiRect slot = t.ui.hud().skillSlots().get(SkillId.LIGHTNING.ordinal());
        t.tap(slot);
        assertFalse(t.world.sawAnything(),
                "the world was offered a press it should never see: " + t.world.log);
        assertEquals(SkillId.LIGHTNING, t.run.skills().aiming(), "and the button worked");
    }

    @Test
    @DisplayName("a press that misses every HUD control does reach the world")
    void hudIsNotModal() {
        TestUi t = new TestUi().startRun(GameMode.ENDLESS, "normal").beginPlaying();
        t.ui.layout();
        //  the middle of the field, well clear of the panel and the bar
        t.tap(760f, 420f);
        assertTrue(t.world.log.contains("press", false),
                "grabbing works because the HUD lets the miss through: " + t.world.log);
    }

    @Test
    @DisplayName("every modal screen swallows a press anywhere, including empty space")
    void modalScreensSwallowEverything() {
        GameState[] modal = {GameState.MENU, GameState.SETTINGS, GameState.SHOP,
            GameState.TALENTS, GameState.PAUSED, GameState.GAMEOVER};
        for (GameState state : modal) {
            TestUi t = new TestUi();
            if (state != GameState.MENU && state != GameState.SETTINGS) {
                t.startRun(GameMode.ENDLESS, "normal");
            }
            t.run.world.setState(state);
            t.ui.layout();
            t.tap(760f, 420f);          // deliberately not on any control
            assertFalse(t.world.sawAnything(),
                    state + " let a press through to the world: " + t.world.log);
        }
    }

    @Test
    @DisplayName("a press on the horn does not grab the enemy standing behind it")
    void hornDoesNotGrab() {
        TestUi t = new TestUi().startRun(GameMode.ENDLESS, "normal").beginPlaying();
        t.ui.layout();
        UiRect horn = t.ui.hud().hornButton();
        //  At 1280x720 the world and UI viewports coincide, so a UI point and a
        //  world point are the same number here.  That is only true at the
        //  reference size, which is exactly why this test uses it.
        com.mymmer.castledefense.enemy.Enemy mob =
                t.run.run.spawnEnemy(EnemyType.SCOUT, 1);
        mob.setX(horn.centerX());
        mob.setY(horn.centerY());

        t.tap(horn);
        assertTrue(t.run.session().hornUsed(), "the horn blew");
        assertFalse(t.world.sawAnything(), "and nothing was grabbed: " + t.world.log);
    }

    @Test
    @DisplayName("a spent horn still swallows the press")
    void spentHornStillConsumes() {
        //  The refusal has to be as opaque as the success: a button that stops
        //  claiming presses once it is used becomes a hole in the interface.
        TestUi t = new TestUi().startRun(GameMode.ENDLESS, "normal").beginPlaying();
        t.press(t.ui.hud().hornButton());
        assertTrue(t.run.session().hornUsed());

        t.world.log.clear();
        t.tap(t.ui.hud().hornButton());
        assertFalse(t.world.sawAnything(), "the dead button is still a button");
    }

    @Test
    @DisplayName("an unaffordable shop card swallows the press too")
    void unaffordableCardConsumes() {
        TestUi t = new TestUi().startRun(GameMode.CLASSIC, "normal");
        t.tap(t.ui.shop().card("cannon"));
        assertFalse(t.world.sawAnything());
        assertEquals(220, t.run.session().gold(), "and bought nothing");
    }

    // ========================================================================
    //  Ownership over a whole gesture
    // ========================================================================

    @Test
    @DisplayName("the whole gesture belongs to whoever claimed the press")
    void ownershipIsForTheWholeGesture() {
        TestUi t = new TestUi().startRun(GameMode.ENDLESS, "normal").beginPlaying();
        t.run.skills().unlockNext();
        t.ui.layout();
        UiRect slot = t.ui.hud().skillSlots().get(SkillId.LIGHTNING.ordinal());

        t.touchDown(0, slot.centerX(), slot.centerY());
        t.touchMove(0, 700f, 400f);         // dragged far out, over the field
        t.touchMove(0, 900f, 300f);
        t.touchUp(0, 900f, 300f);

        assertFalse(t.world.sawAnything(),
                "a drag that started on a button must not become a world drag: "
                        + t.world.log);
    }

    @Test
    @DisplayName("a drag off a button does not fire it, and does not fire the world either")
    void dragOffAButtonCancelsIt() {
        TestUi t = new TestUi().startRun(GameMode.CLASSIC, "normal");
        t.run.grantGold(100000);
        UiRect card = t.ui.shop().card("bowman");
        t.ui.layout();

        t.touchDown(0, card.centerX(), card.centerY());
        t.touchMove(0, card.centerX(), card.centerY() - 400f);
        t.touchUp(0, card.centerX(), card.centerY() - 400f);

        assertFalse(t.world.sawAnything());
    }

    @Test
    @DisplayName("a world drag in progress is not stolen by a modal opening under the finger")
    void modalDoesNotStealALiveGesture() {
        //  The router hands a drag to whoever owns the pointer, and ownership is
        //  decided at press time.  Opening the armoury mid-drag must therefore
        //  not redirect the finger that is already carrying an enemy.
        TestUi t = new TestUi().startRun(GameMode.ENDLESS, "normal").beginPlaying();
        t.ui.layout();

        t.touchDown(0, 760f, 420f);
        assertTrue(t.world.log.contains("press", false), "the world took the pointer");
        assertTrue(t.input.ownsInteraction(0));

        t.run.world.setState(GameState.SHOP);       // a modal appears
        t.ui.layout();
        t.touchMove(0, 780f, 430f);
        assertTrue(t.world.log.contains("drag", false),
                "the drag still belongs to the world: " + t.world.log);

        t.touchUp(0, 780f, 430f);
        assertTrue(t.world.log.contains("release", false));
        assertFalse(t.input.ownsInteraction(0), "and the interaction was released");
    }

    @Test
    @DisplayName("a cancelled gesture is cancelled, not released")
    void cancellationReachesTheOwner() {
        TestUi t = new TestUi().startRun(GameMode.ENDLESS, "normal").beginPlaying();
        t.ui.layout();
        t.touchDown(0, 760f, 420f);
        assertTrue(t.input.ownsInteraction(0));

        t.input.cancelPointer(0);
        t.pump();
        assertTrue(t.world.log.contains("cancel", false),
                "a cancel is not a release: " + t.world.log);
        assertFalse(t.world.log.contains("release", false));
        assertFalse(t.input.ownsInteraction(0));
    }

    @Test
    @DisplayName("a second finger on the interface does not disturb the first on the world")
    void secondFingerOnTheUi() {
        TestUi t = new TestUi().startRun(GameMode.ENDLESS, "normal").beginPlaying();
        t.run.skills().unlockNext();
        t.ui.layout();

        t.touchDown(0, 760f, 420f);
        assertTrue(t.input.ownsInteraction(0), "finger 0 has the world");

        UiRect slot = t.ui.hud().skillSlots().get(SkillId.LIGHTNING.ordinal());
        t.touchDown(1, slot.centerX(), slot.centerY());
        assertEquals(SkillId.LIGHTNING, t.run.skills().aiming(), "finger 1 armed a skill");
        assertTrue(t.input.ownsInteraction(0), "and finger 0 still holds the world");
        assertFalse(t.input.ownsInteraction(1));
    }

    // ========================================================================
    //  The two-stage cast, through the router
    // ========================================================================

    @Test
    @DisplayName("arm on the bar, cast on the field -- two presses, two owners")
    void armThenCastThroughTheRouter() {
        TestUi t = new TestUi().startRun(GameMode.ENDLESS, "normal").beginPlaying();
        t.run.skills().unlockNext();
        t.ui.layout();

        t.tap(t.ui.hud().skillSlots().get(SkillId.LIGHTNING.ordinal()));
        assertEquals(SkillId.LIGHTNING, t.run.skills().aiming());
        assertFalse(t.world.sawAnything(), "the arming press stayed in the UI");

        //  The armed cast is a UI concern too: the HUD claims the world press
        //  while a skill is waiting for a target, so it does not also grab.
        t.tap(760f, 420f);
        assertNull(t.run.skills().aiming(), "it cast");
        assertTrue(t.run.skills().cooldownRemaining(SkillId.LIGHTNING) > 0d);
        assertFalse(t.world.sawAnything(),
                "and the same press must not also have grabbed: " + t.world.log);
    }

    @Test
    @DisplayName("with nothing armed the same field press goes to the world")
    void unarmedFieldPressIsAWorldPress() {
        TestUi t = new TestUi().startRun(GameMode.ENDLESS, "normal").beginPlaying();
        t.ui.layout();
        t.tap(760f, 420f);
        assertTrue(t.world.log.contains("press", false), t.world.log.toString());
    }

    // ========================================================================
    //  Desktop and Android take the same road
    // ========================================================================

    @Test
    @DisplayName("a mouse click and a finger tap are the same event by the time it routes")
    void mouseAndTouchAreOnePath() {
        //  DesktopInput and TouchInput both feed GameInput; below that there is
        //  one router and one UiRoot.  If this ever stops being true the desktop
        //  build silently grows a second interaction model.
        TestUi t = new TestUi().startRun(GameMode.CLASSIC, "normal");
        t.run.grantGold(100000);
        UiRect card = t.ui.shop().card("bowman");

        t.ui.layout();
        int[] screen = t.screenFor(card.centerX(), card.centerY());
        t.input.touchDown(screen[0], screen[1], 0, 0);      // button 0 == left mouse
        t.pump();
        t.input.touchUp(screen[0], screen[1], 0, 0);
        t.pump();

        assertEquals(1, t.run.shop().purchaseCount("bowman"));
        assertFalse(t.world.sawAnything());
    }

    @Test
    @DisplayName("Android Back travels the navigation graph, never a screen's own guess")
    void backGoesThroughNavigation() {
        TestUi t = new TestUi().startRun(GameMode.ENDLESS, "normal").beginPlaying();
        assertEquals(GameState.PLAYING, t.state());

        assertEquals(Navigation.BackResult.HANDLED, t.ui.back());
        assertEquals(GameState.PAUSED, t.state(), "Back pauses a run");

        assertEquals(Navigation.BackResult.HANDLED, t.ui.back());
        assertEquals(GameState.PLAYING, t.state(), "and Back again resumes it");

        t.run.world.setState(GameState.MENU);
        assertEquals(Navigation.BackResult.EXIT_APP, t.ui.back(),
                "only the menu leaves, and the platform layer decides how");
    }

    @Test
    @DisplayName("Back out of the talent screen returns where the source returns")
    void backFromTalents() {
        TestUi t = new TestUi().startRun(GameMode.CLASSIC, "normal");
        t.press(t.ui.shop().talentsButton());
        assertEquals(GameState.TALENTS, t.state());
        assertEquals(Navigation.BackResult.HANDLED, t.ui.back());
        assertEquals(GameState.SHOP, t.state());
    }

    // ========================================================================
    //  The press never leaks into gameplay randomness
    // ========================================================================

    @Test
    @DisplayName("pressing buttons does not advance the gameplay Rng")
    void uiDoesNotTouchTheRng() {
        //  If a screen ever drew from the run's Rng -- for a shuffle, a wobble,
        //  a random tip -- the simulation would diverge based on how many
        //  buttons the player pressed.  The stream position is the proof.
        //
        //  The claim is about the INTERFACE, not about the commands it sends:
        //  blowing the horn spawns a rush and of course draws.  So this presses
        //  something purely presentational many times, and then blows the horn
        //  once to show the measurement can see a draw at all.
        TestUi t = new TestUi().startRun(GameMode.ENDLESS, "normal").beginPlaying();
        t.run.skills().unlockNext();
        t.ui.layout();

        long[] before = rngState(t);
        UiRect slot = t.ui.hud().skillSlots().get(SkillId.LIGHTNING.ordinal());
        for (int i = 0; i < 20; i++) {
            t.ui.layout();
            t.tap(slot);                    // arm, disarm, arm, ... never casting
        }
        assertNotNull(t.ui.safeArea());
        assertArrayEquals(before, rngState(t),
                "the interface drew from the gameplay Rng");

        t.tap(t.ui.hud().hornButton());
        assertFalse(java.util.Arrays.equals(before, rngState(t)),
                "a gameplay command does draw -- so the check above was real");
    }

    @Test
    @DisplayName("laying out a screen does not advance the gameplay Rng either")
    void layoutDoesNotTouchTheRng() {
        TestUi t = new TestUi().startRun(GameMode.ENDLESS, "normal").beginPlaying();
        long[] before = rngState(t);
        for (int i = 0; i < 200; i++) {
            t.ui.layout();
        }
        assertArrayEquals(before, rngState(t));
    }

    /**
     * The gameplay generator's exact position in its stream.
     *
     * <p>Unchanged state is a stronger claim than an unchanged draw count: it
     * says not one number was taken, in either direction.
     */
    private static long[] rngState(TestUi t) {
        return new long[] {
            t.run.rng.game().getState(0),
            t.run.rng.game().getState(1),
        };
    }
}
