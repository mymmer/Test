package com.mymmer.castledefense.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.badlogic.gdx.utils.Array;
import com.mymmer.castledefense.game.GameMode;
import com.mymmer.castledefense.game.GameState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Safe areas, touch targets, overlap and responsiveness across phone shapes. */
class UiLayoutTest {

    /** The shapes a phone actually is, plus the two desktop sizes. */
    private static final int[][] SCREENS = {
        {1280, 720},        // 16:9, the reference
        {1920, 1080},       // 16:9
        {2400, 1080},       // 20:9
        {2560, 1440},       // 16:9
        {2340, 1080},       // 19.5:9
        {2160, 1080},       // 18:9
        {1080, 2400},       // 20:9 held upright
    };

    private static final GameState[] IN_RUN = {
        GameState.SHOP, GameState.PLAYING, GameState.TALENTS,
        GameState.PAUSED, GameState.GAMEOVER,
    };

    // ========================================================================
    //  The world is never disturbed
    // ========================================================================

    @Test
    @DisplayName("the world stays 1280x720 whatever shape the screen is")
    void worldViewportIsUntouched() {
        for (int[] s : SCREENS) {
            TestUi t = new TestUi(s[0], s[1]);
            assertEquals(1280f, t.viewports.getWorld().getWorldWidth(), 0.01f,
                    s[0] + "x" + s[1]);
            assertEquals(720f, t.viewports.getWorld().getWorldHeight(), 0.01f,
                    s[0] + "x" + s[1]);
        }
    }

    @Test
    @DisplayName("a cutout changes the UI safe rect and never the world")
    void insetsDoNotReachTheWorld() {
        TestUi t = new TestUi(2400, 1080);
        float worldW = t.viewports.getWorld().getWorldWidth();
        float worldH = t.viewports.getWorld().getWorldHeight();

        t.withInsets(120, 60, 0, 48);
        assertEquals(worldW, t.viewports.getWorld().getWorldWidth(), 0.01f);
        assertEquals(worldH, t.viewports.getWorld().getWorldHeight(), 0.01f);
        assertFalse(t.ui.safeArea().isFull(), "the UI safe rect did shrink");
    }

    // ========================================================================
    //  Safe area
    // ========================================================================

    @Test
    @DisplayName("every control stays inside the safe rect, on every screen shape")
    void controlsRespectTheSafeArea() {
        for (int[] s : SCREENS) {
            for (GameState state : allStates()) {
                TestUi t = uiIn(state, s[0], s[1]);
                t.withInsets(96, 96, 44, 56);
                SafeArea safe = t.ui.safeArea();
                for (UiRect c : t.ui.allControls()) {
                    if (!c.visible()) {
                        continue;
                    }
                    assertTrue(safe.contains(c),
                            c + " escapes the safe rect " + safe
                                    + " at " + s[0] + "x" + s[1] + " in " + state);
                }
            }
        }
    }

    @Test
    @DisplayName("with no cutouts the safe rect is the whole UI viewport")
    void noInsetsMeansFull() {
        TestUi t = new TestUi(1920, 1080);
        assertTrue(t.ui.safeArea().isFull());
        assertEquals(t.viewports.getUi().getWorldWidth(), t.ui.safeArea().width, 0.01f);
    }

    @Test
    @DisplayName("a nonsensical inset report leaves the interface usable")
    void absurdInsetsDegradeSafely() {
        TestUi t = new TestUi(1280, 720);
        t.withInsets(9000, 9000, 9000, 9000);
        assertTrue(t.ui.safeArea().width > 0f, "never a negative rectangle");
        assertTrue(t.ui.safeArea().height > 0f);
    }

    // ========================================================================
    //  Touch targets
    // ========================================================================

    @Test
    @DisplayName("every control meets the touch minimum, on every screen shape")
    void touchTargetsMeetTheMinimum() {
        for (int[] s : SCREENS) {
            for (GameState state : allStates()) {
                TestUi t = uiIn(state, s[0], s[1]);
                for (UiRect c : t.ui.allControls()) {
                    if (!c.visible()) {
                        continue;
                    }
                    assertTrue(TouchTargets.meetsMinimum(c),
                            c + " is under " + TouchTargets.MIN_UI_UNITS
                                    + " units at " + s[0] + "x" + s[1] + " in " + state);
                }
            }
        }
    }

    @Test
    @DisplayName("growing the touch box never grows the artwork")
    void artworkIsNotEnlarged() {
        UiRect r = new UiRect("test").setBounds(100f, 100f, 20f, 20f);
        TouchTargets.apply(r);
        assertEquals(20f, r.visualWidth(), 0f, "the drawing is unchanged");
        assertEquals(20f, r.visualHeight(), 0f);
        assertEquals(TouchTargets.MIN_UI_UNITS, r.hitWidth(), 0.01f);
        assertEquals(110f, r.centerX(), 0.01f, "and it grew around the same centre");
    }

    @Test
    @DisplayName("no two controls on a screen have overlapping touch boxes")
    void noOverlappingTargets() {
        for (int[] s : SCREENS) {
            for (GameState state : allStates()) {
                TestUi t = uiIn(state, s[0], s[1]);
                Array<UiRect> controls = t.ui.allControls();
                Array<String> clashes = TouchTargets.overlaps(controls);
                if (clashes.size > 0) {
                    fail("overlapping touch boxes at " + s[0] + "x" + s[1]
                            + " in " + state + ": " + clashes);
                }
            }
        }
    }

    @Test
    @DisplayName("one press activates exactly one control, even where boxes touch")
    void onePressOneControl() {
        TestUi t = uiIn(GameState.SHOP, 2400, 1080);
        Array<UiRect> controls = t.ui.allControls();
        for (int i = 0; i < controls.size; i++) {
            UiRect c = controls.get(i);
            if (!c.visible()) {
                continue;
            }
            int hits = 0;
            for (int j = 0; j < controls.size; j++) {
                if (controls.get(j).hits(c.centerX(), c.centerY())) {
                    hits++;
                }
            }
            assertEquals(1, hits, "pressing the middle of " + c.id
                    + " must reach exactly one control");
        }
    }

    @Test
    @DisplayName("where boxes did overlap, registration order decides")
    void overlapResolvesByOrder() {
        Array<UiRect> controls = new Array<>();
        UiRect first = new UiRect("a").setBounds(100f, 100f, 40f, 40f);
        UiRect second = new UiRect("b").setBounds(120f, 100f, 40f, 40f);
        controls.add(first);
        controls.add(second);
        assertTrue(first.hitOverlaps(second), "these deliberately overlap");
        assertEquals(first, TouchTargets.firstHit(controls, 130f, 110f),
                "the earlier registration wins, as the input router does");
    }

    // ========================================================================
    //  Responsiveness
    // ========================================================================

    @Test
    @DisplayName("the HUD panel fits inside the safe rect at every shape")
    void hudPanelFits() {
        for (int[] s : SCREENS) {
            TestUi t = uiIn(GameState.PLAYING, s[0], s[1]);
            SafeArea safe = t.ui.safeArea();
            HudScreen hud = t.ui.hud();
            assertTrue(hud.panelHeight() > 0f, "the panel was measured");
            assertTrue(hud.panelY() >= safe.y - 0.01f,
                    "the HUD stack runs off the bottom at " + s[0] + "x" + s[1]
                            + " (panelY " + hud.panelY() + " < safe " + safe.y + ")");
            assertTrue(hud.panelY() + hud.panelHeight() <= safe.top() + 0.01f,
                    "and off the top at " + s[0] + "x" + s[1]);
        }
    }

    @Test
    @DisplayName("the shop shows all eleven cards at every shape")
    void shopFitsEveryCard() {
        for (int[] s : SCREENS) {
            TestUi t = uiIn(GameState.SHOP, s[0], s[1]);
            assertEquals(11, t.ui.shop().cards().size);
            for (UiRect card : t.ui.shop().cards()) {
                assertTrue(card.visualWidth() > 0f && card.visualHeight() > 0f,
                        card.id + " collapsed at " + s[0] + "x" + s[1]);
            }
        }
    }

    @Test
    @DisplayName("the talent screen reaches all 38 nodes, scrolling if it must")
    void talentScreenReachesEveryNode() {
        for (int[] s : SCREENS) {
            TestUi t = uiIn(GameState.TALENTS, s[0], s[1]);
            assertEquals(38, t.ui.talents().nodes().size);
            //  every node is either on screen now, or reachable by scrolling
            int visible = 0;
            for (UiRect n : t.ui.talents().nodes()) {
                if (n.visible()) {
                    visible++;
                }
            }
            assertTrue(visible > 0, "nothing visible at " + s[0] + "x" + s[1]);
            if (visible < 38) {
                assertTrue(t.ui.talents().maxScroll() > 0,
                        "some nodes are off screen but there is no way to scroll to "
                                + "them at " + s[0] + "x" + s[1]);
            }
        }
    }

    @Test
    @DisplayName("a rotation re-lays everything out without leaving a stale rectangle")
    void rotationRelayouts() {
        TestUi t = uiIn(GameState.SHOP, 2400, 1080);
        float wideCardX = t.ui.shop().card("bowman").visualX();
        t.resize(1080, 2400);
        float tallCardX = t.ui.shop().card("bowman").visualX();
        assertTrue(Math.abs(wideCardX - tallCardX) > 1f,
                "the layout should react to the new shape");
        for (UiRect c : t.ui.allControls()) {
            if (c.visible()) {
                assertTrue(TouchTargets.meetsMinimum(c), c + " after rotation");
            }
        }
    }

    // ------------------------------------------------------------------------

    private static GameState[] allStates() {
        GameState[] out = new GameState[IN_RUN.length + 2];
        out[0] = GameState.MENU;
        out[1] = GameState.SETTINGS;
        System.arraycopy(IN_RUN, 0, out, 2, IN_RUN.length);
        return out;
    }

    /** A UI sitting in one state, with a run behind it where one is needed. */
    static TestUi uiIn(GameState state, int width, int height) {
        TestUi t = new TestUi(width, height);
        if (state != GameState.MENU && state != GameState.SETTINGS) {
            t.startRun(GameMode.ENDLESS, "normal");
        }
        t.run.world.setState(state);
        t.ui.layout();
        assertNotNull(t.ui.allControls());
        return t;
    }
}
