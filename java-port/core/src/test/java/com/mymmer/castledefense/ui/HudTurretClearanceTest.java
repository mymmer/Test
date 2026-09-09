package com.mymmer.castledefense.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.badlogic.gdx.utils.Array;
import com.mymmer.castledefense.defence.DefenceTower;
import com.mymmer.castledefense.game.GameMode;
import com.mymmer.castledefense.render.WorldGeometry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The stat panel does not sit on top of the castle's turrets.
 *
 * <h2>The defect</h2>
 *
 * <p>Phase 13.1 anchored the HUD panel to the <em>touch</em> rectangle so its
 * SHOP button would clear the navigation strip. That rectangle also excludes
 * the system's gesture strips, and the top strip on a Galaxy S10+ is 84 px --
 * 42 interface units. The whole panel therefore dropped 42 units down the
 * screen and its lower edge landed across the keep's cannon, which is the
 * turret that became awkward to reach.
 *
 * <p>It is anchored to the display rectangle again. Nothing about the panel
 * needs to dodge a gesture strip: a strip steals swipes, and the one control it
 * carries sits at its bottom edge, which {@code SafeAreaLayoutTest} still checks
 * against the touch rectangle.
 *
 * <h2>Why this is measured against real towers</h2>
 *
 * <p>Against the towers the game actually places, not a remembered coordinate.
 * A constant copied into a test goes stale the first time the castle changes and
 * then quietly asserts nothing.
 */
class HudTurretClearanceTest {

    /** Landscape shapes, and the S10+'s real asymmetric insets. */
    private static final int[][] DEVICES = {
        {1280, 720}, {2340, 1080}, {3040, 1440},
    };

    private static TestUi armed(int w, int h) {
        TestUi t = new TestUi(w, h)
                .withInsets(142, 0, 0, 0, 0, 168, 84, 0);
        t.startRun(GameMode.ENDLESS, "normal").beginPlaying();
        t.run.run.session().addGold(200000);
        //  Everything that can sit on the wall, so the highest one is present.
        for (String id : new String[] {"bowman", "ballista", "cannon"}) {
            for (int i = 0; i < 3; i++) {
                t.run.run.shop().buy(id);
            }
        }
        t.ui.layout();
        return t;
    }

    @Test
    @DisplayName("a turret under the panel can still be pressed through it")
    void aTurretUnderThePanelIsStillReachable() {
        //  The keep-top emplacements sit at gameplay y 234, and a tall stat
        //  panel reaches y 228 -- they overlap in the source too, because the
        //  panel grows with its rows. What must never be true is that the panel
        //  EATS the press: the source checks its two buttons and then falls
        //  through to try_grab, and so does this port.
        TestUi t = armed(3040, 1440);
        t.router.setWorldHandler(t.run.run.cursor());

        Array<DefenceTower> towers = t.run.run.castle().towers();
        DefenceTower highest = null;
        for (int i = 0; i < towers.size; i++) {
            if (highest == null || towers.get(i).y() < highest.y()) {
                highest = towers.get(i);
            }
        }
        assertTrue(highest != null && highest.y() < 300f,
                "precondition: a turret high enough to sit under the panel");

        //  Press its centre, through whatever the interface is drawing there.
        com.badlogic.gdx.utils.viewport.Viewport vp = t.viewports.getWorld();
        float drawY = WorldGeometry.toDrawY(highest.y() - highest.height() / 2f);
        int sx = Math.round(vp.getScreenX()
                + highest.x() / vp.getWorldWidth() * vp.getScreenWidth());
        int sy = Math.round(t.viewports.getScreenHeight()
                - (vp.getScreenY() + drawY / vp.getWorldHeight() * vp.getScreenHeight()));
        t.input.touchDown(sx, sy, 0, 0);
        t.pump();

        assertTrue(t.run.run.cursor().charging() != null,
                "the press did not reach the turret -- the stat panel is "
                        + "swallowing it, which the source never does");
    }

    @Test
    @DisplayName("the panel keeps the source's own offset from the safe top")
    void theOffsetIsTheSources() {
        //  main.py: HUD_X, HUD_Y = 14, 12. The panel hangs 12 units below the
        //  top of the safe area and 14 in from its left, and a phone's gesture
        //  strips must not add to that.
        TestUi t = armed(3040, 1440);
        HudScreen hud = t.ui.hud();
        SafeArea safe = t.ui.safeArea();

        assertTrue(Math.abs((safe.top() - (hud.panelY() + hud.panelHeight()))
                        - HudScreen.PANEL_TOP_GAP) < 0.01f,
                "the panel's top gap is "
                        + (safe.top() - (hud.panelY() + hud.panelHeight()))
                        + ", not the source's " + HudScreen.PANEL_TOP_GAP);
        assertTrue(Math.abs((hud.panelX() - safe.x) - HudScreen.PANEL_X) < 0.01f,
                "the panel's left gap is not the source's");
    }

    @Test
    @DisplayName("the SHOP button it carries is still inside the touch rect")
    void theButtonStillClearsTheStrips() {
        //  The reason the panel was moved onto the touch rectangle in the first
        //  place. Undoing that must not put the button back in the strip.
        TestUi t = armed(3040, 1440);
        SafeArea safe = t.ui.safeArea();
        Array<UiRect> controls = t.ui.allControls();
        for (int i = 0; i < controls.size; i++) {
            UiRect c = controls.get(i);
            if (c.visible()) {
                assertTrue(safe.containsTouch(c),
                        c.id + " left the touch rectangle when the panel moved");
            }
        }
    }
}
