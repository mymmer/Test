package com.mymmer.castledefense.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.badlogic.gdx.utils.Array;
import com.mymmer.castledefense.game.GameMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every control the player can press sits where a press actually lands.
 *
 * <h2>The defect this exists for</h2>
 *
 * <p>Phase 13 measured the shipped layout on a Galaxy S10+ and found
 * {@code menu.settings} spanning screen x 2708–3008 while the system's
 * navigation strip began at 2872 — <b>136 px of a 300 px button, 45% of its
 * width, inside a region the platform can take the touch from</b>.
 *
 * <p>It happened because only one inset family was being read. The phone's
 * cutout is 142 px on the <em>left</em>; its navigation strip is 168 px on the
 * <em>right</em>. Reading the cutout alone gets the left edge right and the
 * right edge wrong, and no test noticed because the layout was self-consistent:
 * everything was inside the rectangle it had been told about.
 *
 * <h2>What these tests check</h2>
 *
 * <p><b>Hit rectangles, not visual ones.</b> {@code TouchTargets.applyAll}
 * grows a small control's touch box past its painted box, so a button can look
 * clear of the strip and still take presses inside it. {@code insideBounds}
 * reads the hit box, which is the one the player's finger meets.
 *
 * <p>Three aspect ratios and asymmetric insets on each, because a symmetric test
 * cannot tell a correctly-centred layout from one centred on the wrong
 * rectangle.
 */
class SafeAreaLayoutTest {

    /** Landscape screens: 16:9, 19.5:9 and 20:9. */
    private static final int[][] DEVICES = {
        {1280, 720}, {2340, 1080}, {2400, 1080},
    };

    private static String describe(int[] device) {
        return device[0] + "x" + device[1];
    }

    /**
     * Drives one screen into place and returns its controls.
     *
     * <p>Laid out after the insets are applied, which is the order the game uses
     * — insets arrive from the platform, then the frame lays out.
     */
    private static Array<UiRect> controlsOn(TestUi t, String screen) {
        switch (screen) {
            case "menu":
                break;
            case "settings":
                t.ui.navigation().openSettings();
                break;
            case "shop":
                t.startRun(GameMode.CLASSIC, "normal");
                break;
            case "playing":
                t.startRun(GameMode.ENDLESS, "normal").beginPlaying();
                break;
            case "paused":
                t.startRun(GameMode.ENDLESS, "normal").beginPlaying();
                t.ui.navigation().pause();
                break;
            case "talents":
                t.startRun(GameMode.CLASSIC, "normal");
                t.ui.navigation().openTalents();
                break;
            default:
                throw new IllegalArgumentException(screen);
        }
        t.ui.layout();
        return t.ui.allControls();
    }

    private static final String[] SCREENS = {
        "menu", "settings", "shop", "playing", "paused", "talents",
    };

    @Test
    @DisplayName("no control lands in a system strip, on any screen or shape")
    void everyControlIsInsideTheTouchRect() {
        //  A Galaxy S10+ in landscape: cutout on the left, navigation on the
        //  right, status strip along the top. Asymmetric on purpose.
        for (int[] device : DEVICES) {
            for (String screen : SCREENS) {
                TestUi t = new TestUi(device[0], device[1])
                        .withInsets(142, 0, 0, 0, 0, 168, 84, 0);
                Array<UiRect> controls = controlsOn(t, screen);
                SafeArea safe = t.ui.safeArea();
                assertFalse(controls.isEmpty(),
                        describe(device) + " " + screen + ": no controls at all");
                for (int i = 0; i < controls.size; i++) {
                    UiRect c = controls.get(i);
                    if (!c.visible()) {
                        continue;
                    }
                    assertTrue(safe.containsTouch(c),
                            describe(device) + " " + screen + ": " + c.id
                                    + " hit box " + c.hitX() + ".."
                                    + (c.hitX() + c.hitWidth())
                                    + " x " + c.hitY() + ".."
                                    + (c.hitY() + c.hitHeight())
                                    + " leaves the touch rect ["
                                    + safe.touchX + ".." + safe.touchRight()
                                    + " x " + safe.touchY + ".."
                                    + safe.touchTop() + "]");
                }
            }
        }
    }

    @Test
    @DisplayName("and again with the insets mirrored, so nothing passes by luck")
    void mirroredInsets() {
        for (int[] device : DEVICES) {
            for (String screen : SCREENS) {
                //  Cutout on the right, navigation on the left: the same
                //  geometry reflected. A layout that happens to work only
                //  because the wider strip is on one particular side fails here.
                TestUi t = new TestUi(device[0], device[1])
                        .withInsets(0, 142, 0, 0, 168, 0, 84, 0);
                Array<UiRect> controls = controlsOn(t, screen);
                SafeArea safe = t.ui.safeArea();
                for (int i = 0; i < controls.size; i++) {
                    UiRect c = controls.get(i);
                    if (c.visible()) {
                        assertTrue(safe.containsTouch(c),
                                describe(device) + " " + screen + " mirrored: "
                                        + c.id + " leaves the touch rect");
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("SETTINGS clears the navigation strip -- the Phase 13 defect")
    void settingsClearsTheNavigationStrip() {
        //  The S10+'s real numbers, in its real landscape resolution.
        TestUi t = new TestUi(3040, 1440).withInsets(142, 0, 0, 0, 0, 168, 84, 0);
        t.ui.layout();

        UiRect settings = t.ui.menu().settingsButton();
        SafeArea safe = t.ui.safeArea();

        assertTrue(settings.hitX() + settings.hitWidth() <= safe.touchRight(),
                "SETTINGS ends at " + (settings.hitX() + settings.hitWidth())
                        + " but the strip starts at " + safe.touchRight()
                        + " -- this is exactly the 45% overlap Phase 13 shipped");
        assertTrue(safe.containsTouch(settings),
                "SETTINGS is not fully inside the touch rect");
    }

    @Test
    @DisplayName("the two inset families stay distinct, not merged")
    void theTwoFamiliesAreNotInterchangeable() {
        TestUi t = new TestUi(3040, 1440).withInsets(142, 0, 0, 0, 0, 168, 84, 0);
        t.ui.layout();
        SafeArea safe = t.ui.safeArea();

        //  Display rect: clear of the cutout only. It may extend under the
        //  navigation strip, because a background there is drawn perfectly well.
        assertTrue(safe.right() > safe.touchRight(),
                "the display rect must reach further right than the touch rect; "
                        + "if they are equal the gesture inset was applied to "
                        + "both and decoration is being cropped for no reason");
        //  The left edge is a cutout, which obscures: both rects clear it.
        assertTrue(safe.x > 0f, "the cutout must inset the display rect too");
        assertTrue(Math.abs(safe.touchX - safe.x) < 0.01f,
                "nothing claims the left edge but the cutout, so the two rects "
                        + "must agree there");
    }

    @Test
    @DisplayName("the talent tree leaves room for its own headings")
    void talentHeadingsDoNotCollideWithTheSubtitle() {
        //  The branch heading is drawn just above the first node and the
        //  "N POINTS TO SPEND" subtitle just below the safe top. On the phone
        //  they were four units apart, so UTILITY and AERO-MASTERY were printed
        //  straight through the subtitle.
        for (int[] device : DEVICES) {
            TestUi t = new TestUi(device[0], device[1])
                    .withInsets(142, 0, 0, 0, 0, 168, 84, 0);
            t.startRun(GameMode.CLASSIC, "normal");
            t.ui.navigation().openTalents();
            t.ui.layout();

            SafeArea safe = t.ui.safeArea();
            Array<UiRect> nodes = t.ui.talents().controls();
            float highest = Float.NEGATIVE_INFINITY;
            for (int i = 0; i < nodes.size; i++) {
                UiRect n = nodes.get(i);
                if (n.visible() && n.id.startsWith("talent.")) {
                    highest = Math.max(highest, n.visualY() + n.visualHeight());
                }
            }
            assertTrue(highest > Float.NEGATIVE_INFINITY,
                    describe(device) + ": no talent nodes were laid out");
            //  Where the renderer actually puts them: the heading 22 units
            //  above the first node, the subtitle 84 below the safe top.
            //  Asserting the band alone was too weak -- the shipped 76 gave a
            //  band of 110 against a constant of 100 and passed.
            float heading = highest + 22f;
            float subtitle = safe.touchTop() - 84f;
            assertTrue(subtitle - heading >= 20f,
                    describe(device) + ": the branch heading sits at " + heading
                            + " and the points subtitle at " + subtitle
                            + " -- only " + (subtitle - heading)
                            + " units apart, so they overlap");
            assertTrue(safe.touchTop() - highest >= TalentScreen.HEADER_BAND,
                    describe(device) + ": the tree reaches into its own header "
                            + "band");
        }
    }

    @Test
    @DisplayName("with no insets at all, controls may use the whole screen")
    void noInsetsMeansNoInset() {
        TestUi t = new TestUi(1280, 720).withInsets(0, 0, 0, 0, 0, 0, 0, 0);
        t.ui.layout();
        SafeArea safe = t.ui.safeArea();
        assertTrue(safe.isFull(), "a device with no insets was inset anyway");
        assertTrue(Math.abs(safe.touchWidth - safe.fullWidth) < 0.01f,
                "the touch rect was narrowed on a device that reported nothing");
    }

    @Test
    @DisplayName("a device claiming the entire screen is disbelieved, not obeyed")
    void absurdInsetsDoNotEraseTheInterface() {
        //  A broken report must not make the game unplayable.
        TestUi t = new TestUi(1280, 720)
                .withInsets(0, 0, 0, 0, 4000, 4000, 4000, 4000);
        t.ui.layout();
        SafeArea safe = t.ui.safeArea();
        assertTrue(safe.touchWidth > 0f && safe.touchHeight > 0f,
                "the touch rect collapsed, so no control could be placed");
    }
}
