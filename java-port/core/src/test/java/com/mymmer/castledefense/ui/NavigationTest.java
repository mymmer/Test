package com.mymmer.castledefense.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.mymmer.castledefense.game.GameMode;
import com.mymmer.castledefense.game.GameState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The navigation graph, transcribed from the source and enforced here.
 *
 * <p>The headline is the last section: <b>there is no route from a running game
 * to the difficulty selector</b>, and these tests are what stop one appearing.
 */
class NavigationTest {

    // ========================================================================
    //  The graph
    // ========================================================================

    @Test
    @DisplayName("the game opens on the menu")
    void startsOnMenu() {
        TestUi t = new TestUi();
        assertEquals(GameState.MENU, t.state());
        assertNotNull(t.ui.modalScreen());
        assertEquals(GameState.MENU, t.ui.modalScreen().state());
    }

    @Test
    @DisplayName("MENU -> SHOP by picking a mode, and the run is created there")
    void menuToShop() {
        TestUi t = new TestUi();
        t.press(t.ui.menu().classicButton());
        assertEquals(GameState.SHOP, t.state());
        assertEquals(GameMode.CLASSIC, t.run.session().mode());
        assertEquals(1, t.run.session().wave(), "wave 1 is composed with the run");
    }

    @Test
    @DisplayName("MENU -> SETTINGS -> MENU")
    void menuToSettingsAndBack() {
        TestUi t = new TestUi();
        t.press(t.ui.menu().settingsButton());
        assertEquals(GameState.SETTINGS, t.state());
        t.press(t.ui.settings().backButton());
        assertEquals(GameState.MENU, t.state());
    }

    @Test
    @DisplayName("SHOP -> PLAYING by the action button")
    void shopToPlaying() {
        TestUi t = new TestUi().startRun(GameMode.CLASSIC, "normal");
        assertEquals(GameState.SHOP, t.state());
        t.press(t.ui.shop().startButton());
        assertEquals(GameState.PLAYING, t.state());
    }

    @Test
    @DisplayName("SHOP -> TALENTS -> SHOP, and PLAYING -> TALENTS -> PLAYING")
    void talentsReturnWhereItCameFrom() {
        TestUi t = new TestUi().startRun(GameMode.CLASSIC, "normal");

        t.press(t.ui.shop().talentsButton());
        assertEquals(GameState.TALENTS, t.state());
        assertEquals(GameState.SHOP, t.ui.navigation().talentReturn());
        t.press(t.ui.talents().backButton());
        assertEquals(GameState.SHOP, t.state(), "back to the armoury");

        t.beginPlaying();
        assertEquals(GameState.PLAYING, t.state());
        assertTrue(t.key(UiRoot.Key.TOGGLE_TALENTS));
        assertEquals(GameState.TALENTS, t.state());
        assertEquals(GameState.PLAYING, t.ui.navigation().talentReturn());
        assertTrue(t.key(UiRoot.Key.TOGGLE_TALENTS));
        assertEquals(GameState.PLAYING, t.state(), "back to the fight");
    }

    @Test
    @DisplayName("PLAYING <-> PAUSED, and the pause panel has exactly one button")
    void pauseToggles() {
        TestUi t = new TestUi().startRun(GameMode.CLASSIC, "normal").beginPlaying();
        assertTrue(t.ui.navigation().pause());
        assertEquals(GameState.PAUSED, t.state());
        t.ui.layout();
        assertEquals(1, t.ui.pause().controls().size,
                "resume, and nothing else -- no settings door from a run");
        t.press(t.ui.pause().resumeButton());
        assertEquals(GameState.PLAYING, t.state());
    }

    @Test
    @DisplayName("Endless PLAYING -> realtime SHOP -> PLAYING")
    void endlessRealtimeShop() {
        TestUi t = new TestUi().startRun(GameMode.ENDLESS, "normal").beginPlaying();
        assertTrue(t.ui.navigation().canOpenRealtimeShop());
        t.press(t.ui.hud().shopButton());
        assertEquals(GameState.SHOP, t.state());
        t.press(t.ui.shop().startButton());
        assertEquals(GameState.PLAYING, t.state());
    }

    @Test
    @DisplayName("Classic has no mid-fight armoury button at all")
    void classicHasNoRealtimeShopButton() {
        TestUi t = new TestUi().startRun(GameMode.CLASSIC, "normal").beginPlaying();
        assertFalse(t.ui.hud().shopButton().visible(),
                "the source only builds that rectangle in the Endless branch");
        assertFalse(t.ui.navigation().canOpenRealtimeShop());
    }

    @Test
    @DisplayName("GAMEOVER -> MENU, after a full reset")
    void gameOverReturnsToMenu() {
        TestUi t = new TestUi().startRun(GameMode.CLASSIC, "normal").beginPlaying();
        t.run.run.castle().takeDamage(t.run.run.castle().maxHp() * 10f);
        t.run.step();
        assertEquals(GameState.GAMEOVER, t.state());

        t.press(t.ui.gameOver().restartButton());
        assertEquals(GameState.MENU, t.state());
    }

    // ========================================================================
    //  Android Back / desktop Escape
    // ========================================================================

    @Test
    @DisplayName("Back never leaves a fight directly -- it pauses first")
    void backFromPlaying() {
        TestUi t = new TestUi().startRun(GameMode.CLASSIC, "normal").beginPlaying();
        assertEquals(Navigation.BackResult.HANDLED, t.ui.navigation().back());
        assertEquals(GameState.PAUSED, t.state());
        assertEquals(Navigation.BackResult.HANDLED, t.ui.navigation().back());
        assertEquals(GameState.PLAYING, t.state());
    }

    @Test
    @DisplayName("Back takes each submenu to its parent")
    void backFromSubmenus() {
        TestUi t = new TestUi();
        t.press(t.ui.menu().settingsButton());
        assertEquals(Navigation.BackResult.HANDLED, t.ui.navigation().back());
        assertEquals(GameState.MENU, t.state(), "settings -> menu");

        t.startRun(GameMode.CLASSIC, "normal");
        t.press(t.ui.shop().talentsButton());
        assertEquals(Navigation.BackResult.HANDLED, t.ui.navigation().back());
        assertEquals(GameState.SHOP, t.state(), "talents -> where it came from");
    }

    @Test
    @DisplayName("Back closes the Endless armoury but not the Classic one")
    void backFromShop() {
        TestUi endless = new TestUi().startRun(GameMode.ENDLESS, "normal").beginPlaying();
        endless.press(endless.ui.hud().shopButton());
        assertEquals(GameState.SHOP, endless.state());
        assertEquals(Navigation.BackResult.HANDLED, endless.ui.navigation().back());
        assertEquals(GameState.PLAYING, endless.state());

        TestUi classic = new TestUi().startRun(GameMode.CLASSIC, "normal");
        assertEquals(GameState.SHOP, classic.state());
        assertEquals(Navigation.BackResult.HANDLED, classic.ui.navigation().back());
        assertEquals(GameState.SHOP, classic.state(),
                "the armoury between waves has nothing behind it but the menu, "
                        + "and a route there would be a route to SETTINGS mid-run");
    }

    @Test
    @DisplayName("Back on the menu asks the platform to exit")
    void backFromMenuExits() {
        TestUi t = new TestUi();
        assertEquals(Navigation.BackResult.EXIT_APP, t.ui.navigation().back());
        assertEquals(GameState.MENU, t.state());
    }

    @Test
    @DisplayName("Back from game over goes to the menu")
    void backFromGameOver() {
        TestUi t = new TestUi().startRun(GameMode.CLASSIC, "normal").beginPlaying();
        t.run.run.castle().takeDamage(t.run.run.castle().maxHp() * 10f);
        t.run.step();
        assertEquals(Navigation.BackResult.HANDLED, t.ui.navigation().back());
        assertEquals(GameState.MENU, t.state());
    }

    // ========================================================================
    //  THE DIFFICULTY CONTRACT
    // ========================================================================

    @Test
    @DisplayName("settings is reachable ONLY from the menu")
    void settingsOnlyFromMenu() {
        for (GameState from : GameState.values()) {
            TestUi t = new TestUi();
            t.startRun(GameMode.ENDLESS, "normal");
            t.run.world.setState(from);
            boolean allowed = t.ui.navigation().canOpenSettings();
            assertEquals(from == GameState.MENU, allowed,
                    "canOpenSettings() from " + from);

            boolean opened = t.ui.navigation().openSettings();
            assertEquals(from == GameState.MENU, opened,
                    "openSettings() from " + from);
        }
    }

    @Test
    @DisplayName("the preferred difficulty cannot be changed from any in-run state")
    void difficultyLockedDuringARun() {
        //  THE CONDITION.  Phase 9's run-level difficulty snapshot was approved
        //  on the understanding that no UI route lets a live run's difficulty
        //  change.  This is that understanding, as a test.
        for (GameState from : new GameState[]{
            GameState.PLAYING, GameState.PAUSED, GameState.SHOP,
            GameState.TALENTS, GameState.GAMEOVER}) {
            TestUi t = new TestUi();
            t.startRun(GameMode.ENDLESS, "normal");
            t.run.world.setState(from);
            assertFalse(t.ui.navigation().canChangePreferredDifficulty(),
                    "the difficulty selector must not be reachable from " + from);
        }
        TestUi menu = new TestUi();
        assertTrue(menu.ui.navigation().canChangePreferredDifficulty(),
                "...but the menu may set it for the next run");
    }

    @Test
    @DisplayName("no screen shown during a run offers a difficulty control")
    void noInRunScreenHasADifficultyButton() {
        //  Structural, not just predicate-level: walk the actual controls of
        //  every screen that can be up while a run exists and fail on anything
        //  that looks like a difficulty selector.
        TestUi t = new TestUi().startRun(GameMode.ENDLESS, "normal");
        GameState[] inRun = {GameState.PLAYING, GameState.PAUSED, GameState.SHOP,
            GameState.TALENTS, GameState.GAMEOVER};
        for (GameState s : inRun) {
            t.run.world.setState(s);
            t.ui.layout();
            for (UiRect c : t.ui.allControls()) {
                if (c.id.contains("difficulty") || c.id.contains("settings")) {
                    fail("control '" + c.id + "' is reachable in " + s
                            + ". Phase 10 must not add a way to change a running "
                            + "run's difficulty -- see Navigation's class comment.");
                }
            }
        }
    }

    @Test
    @DisplayName("changing the preference mid-run leaves the run alone and the next one takes it")
    void preferenceAppliesToTheNextRun() {
        TestUi t = new TestUi().startRun(GameMode.CLASSIC, "hard").beginPlaying();
        assertEquals("hard", t.run.session().difficulty().id());
        assertEquals(1.30f, t.run.run.enemyScale(), 1e-6f);

        //  a non-UI seam changes the saved preference, as a settings write would
        t.save.difficulty = "easy";
        t.ui.layout();

        assertEquals("hard", t.run.session().difficulty().id(),
                "the running run keeps what it started with");
        assertEquals(1.30f, t.run.run.enemyScale(), 1e-6f);

        //  end it and start again through the real menu
        t.run.run.castle().takeDamage(t.run.run.castle().maxHp() * 10f);
        t.run.step();
        t.press(t.ui.gameOver().restartButton());
        assertEquals(GameState.MENU, t.state());

        t.startRun(GameMode.CLASSIC, "easy");
        assertEquals("easy", t.run.session().difficulty().id(),
                "and the next run takes the new preference");
        assertEquals(0.80f, t.run.run.enemyScale(), 1e-6f);
    }

    @Test
    @DisplayName("the menu's difficulty buttons set the preference and nothing else")
    void menuDifficultyButtons() {
        TestUi t = new TestUi();
        assertEquals(3, t.ui.menu().difficultyButtons().size);
        t.press(t.ui.menu().difficultyButtons().get(2));       // hard
        assertEquals("hard", t.save.difficulty);
        assertEquals(GameState.MENU, t.state(), "and it does not navigate anywhere");
    }
}
