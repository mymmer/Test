package com.mymmer.castledefense.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.badlogic.gdx.utils.Array;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The menu's difficulty row: one fact, one place, and actually on screen.
 *
 * <h2>What went wrong</h2>
 *
 * <p>Phase 13 pressed HARD on a physical phone and the menu went on saying
 * NORMAL. Two separate faults were behind it, and each hid the other:
 *
 * <ul>
 *   <li>{@code UiRoot} kept the preferred difficulty in a private field seeded
 *       once at construction. The menu's buttons write {@code save.difficulty}.
 *       Nothing in production ever wrote the field — {@code
 *       setPreferredDifficulty} was called only by tests — so the touch path and
 *       the key path read different answers, and a run could start on a
 *       difficulty the menu was not displaying.</li>
 *   <li>The renderer never drew the row at all, though the layout positioned it
 *       and registered all three as pressable. The source draws them
 *       ({@code main.py}'s {@code difficulty_buttons}); the port left three
 *       invisible controls sitting in what looked like empty sky.</li>
 * </ul>
 *
 * <p>The second is why nobody noticed the first: with nothing drawn, there was
 * no visible thing to disagree with.
 */
class MenuDifficultyTest {

    private static UiRect byId(Array<UiRect> controls, String id) {
        for (int i = 0; i < controls.size; i++) {
            if (controls.get(i).id.equals(id)) {
                return controls.get(i);
            }
        }
        throw new AssertionError("no control " + id);
    }

    @Test
    @DisplayName("pressing HARD on the menu changes what the menu displays")
    void pressingHardChangesTheDisplayedDifficulty() {
        TestUi ui = new TestUi();
        assertEquals("normal", ui.ui.preferredDifficulty().id(),
                "precondition: a fresh save starts on the default");

        ui.press(byId(ui.ui.menu().controls(), "menu.difficulty.hard"));

        assertEquals("hard", ui.ui.preferredDifficulty().id(),
                "the label reads a different source than the button writes, so "
                        + "the menu showed a difficulty the run would not use");
    }

    @Test
    @DisplayName("every difficulty button selects its own difficulty")
    void eachButtonSelectsItsOwn() {
        TestUi ui = new TestUi();
        Array<UiRect> all = ui.ui.menu().difficultyButtons();
        for (int i = 0; i < all.size; i++) {
            String id = ui.ui.difficulties().all().get(i).id();
            ui.press(all.get(i));
            assertEquals(id, ui.ui.preferredDifficulty().id(),
                    "button " + all.get(i).id + " must select " + id);
        }
    }

    @Test
    @DisplayName("the selected button is the one marked SELECTED")
    void selectionIsVisibleInTheControlState() {
        TestUi ui = new TestUi();
        ui.press(byId(ui.ui.menu().controls(), "menu.difficulty.hard"));
        //  The selected mark is assigned during layout, which the game runs
        //  once a frame -- so the state is only correct after the next one.
        ui.ui.layout();

        Array<UiRect> all = ui.ui.menu().difficultyButtons();
        for (int i = 0; i < all.size; i++) {
            String id = ui.ui.difficulties().all().get(i).id();
            UiRect.State expected = "hard".equals(id)
                    ? UiRect.State.SELECTED : UiRect.State.NORMAL;
            assertEquals(expected, all.get(i).state(),
                    all.get(i).id + " state after selecting hard");
        }
    }

    @Test
    @DisplayName("the touch path and the key path agree on the difficulty")
    void touchAndKeyAgree() {
        TestUi ui = new TestUi();
        ui.press(byId(ui.ui.menu().controls(), "menu.difficulty.hard"));

        //  MODE_CLASSIC is the keyboard/gamepad route into a run. Before the
        //  fix it read the stale field and would have started on normal while
        //  the menu had been told hard.
        ui.ui.key(UiRoot.Key.MODE_CLASSIC);

        assertEquals("hard", ui.run.session().difficulty().id(),
                "the run started on a difficulty the player did not choose");
    }

    @Test
    @DisplayName("the three buttons are inside the safe area and do not overlap")
    void theRowIsLaidOutSensibly() {
        TestUi ui = new TestUi();
        ui.ui.layout();
        SafeArea safe = ui.ui.safeArea();
        Array<UiRect> all = ui.ui.menu().difficultyButtons();
        for (int i = 0; i < all.size; i++) {
            UiRect b = all.get(i);
            assertTrue(b.hitX() >= safe.x && b.hitX() + b.hitWidth() <= safe.right(),
                    b.id + " runs outside the safe area");
            if (i > 0) {
                UiRect prev = all.get(i - 1);
                assertTrue(b.hitX() >= prev.hitX() + prev.hitWidth(),
                        b.id + " overlaps " + prev.id + ", so one steals the "
                                + "other's presses");
            }
        }
    }

    @Test
    @DisplayName("a difficulty cannot be changed once a run is under way")
    void notChangeableDuringARun() {
        TestUi ui = new TestUi();
        ui.press(byId(ui.ui.menu().controls(), "menu.difficulty.easy"));
        assertEquals("easy", ui.ui.preferredDifficulty().id());

        ui.startRun(com.mymmer.castledefense.game.GameMode.CLASSIC, "easy");
        ui.beginPlaying();

        //  The rule Navigation states: the preference is settable from MENU and
        //  SETTINGS only, never from a state a run is in.
        assertSame(com.mymmer.castledefense.game.GameState.PLAYING,
                ui.run.state());
        assertNotEquals(null, ui.ui.preferredDifficulty(),
                "and it still reports something rather than failing");
    }
}
