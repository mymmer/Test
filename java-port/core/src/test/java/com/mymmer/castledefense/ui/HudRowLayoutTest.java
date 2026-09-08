package com.mymmer.castledefense.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.badlogic.gdx.utils.Array;
import com.mymmer.castledefense.game.GameMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The HUD's measured row stack: every row separated, none overlapping.
 *
 * <h2>The bug this exists for</h2>
 *
 * <p>The renderer used to walk a running {@code y} and apply the row gap
 * <em>after</em> drawing each text row:
 *
 * <pre>
 *   y -= ROW_GAP + line(x, y, text, ...);   // line() draws at the PRE-gap y
 * </pre>
 *
 * <p>so every text row was drawn abutting the row above it, with no gap at all.
 * The health bar and the clock subtracted first and were therefore fine, which
 * is why the visible symptom was {@code SCORE} — the first text row after the
 * bar — sitting on the castle health bar's edge.
 *
 * <p>The fix moved row positions into {@code HudScreen.layout}, so the renderer
 * draws each row where the layout put it. That also makes the spacing something
 * this test can assert, rather than something a draw method has to get right.
 */
class HudRowLayoutTest {

    /** Every row that carries information, in the order the panel stacks them. */
    private static final String[] ROWS = {
        "hud.row.title", "hud.row.wall", "hud.row.health",
        "hud.row.score", "hud.row.talents", "hud.row.clock",
    };

    private static TestUi endless(int w, int h) {
        TestUi t = new TestUi(w, h).startRun(GameMode.ENDLESS, "normal").beginPlaying();
        t.ui.layout();
        return t;
    }

    // ========================================================================
    //  Separation
    // ========================================================================

    @Test
    @DisplayName("no two rows overlap, and each is separated by the row gap")
    void rowsAreSeparated() {
        for (int[] size : new int[][] {{1280, 720}, {2400, 1080}, {1080, 2400}}) {
            TestUi t = endless(size[0], size[1]);
            assertRowsSeparated(t, size[0] + "x" + size[1] + " (no insets)");
        }
    }

    @Test
    @DisplayName("...and with a phone's cutouts applied")
    void rowsAreSeparatedInsideTheSafeArea() {
        for (int[] size : new int[][] {{2400, 1080}, {2340, 1080}, {1080, 2400}}) {
            TestUi t = endless(size[0], size[1]);
            t.withInsets(96, 96, 44, 56);
            assertRowsSeparated(t, size[0] + "x" + size[1] + " (inset)");
        }
    }

    /** Walks the stack top to bottom and checks the gap between each pair. */
    private static void assertRowsSeparated(TestUi t, String where) {
        HudScreen hud = t.ui.hud();
        Array<String> rows = hud.rows();
        assertTrue(rows.size >= 5, "expected a full stack at " + where);

        for (int i = 1; i < rows.size; i++) {
            String above = rows.get(i - 1);
            String below = rows.get(i);
            float bottomOfAbove = hud.rowBottom(above);
            float topOfBelow = hud.rowTop(below);
            float gap = bottomOfAbove - topOfBelow;

            assertTrue(gap > 0f,
                    above + " and " + below + " overlap at " + where
                            + " (gap " + gap + ")");
            assertEquals(HudScreen.ROW_GAP, gap, 0.01f,
                    "the gap between " + above + " and " + below
                            + " is not ROW_GAP at " + where);
        }
    }

    // ========================================================================
    //  The specific rows the review named
    // ========================================================================

    @Test
    @DisplayName("SCORE does not touch the castle health bar")
    void scoreClearsTheHealthBar() {
        //  The exact symptom from the visual comparison.
        for (int[] size : new int[][] {{1280, 720}, {2400, 1080}}) {
            TestUi t = endless(size[0], size[1]);
            HudScreen hud = t.ui.hud();
            float barBottom = hud.rowBottom("hud.row.health");
            float scoreTop = hud.rowTop("hud.row.score");
            assertTrue(barBottom - scoreTop >= HudScreen.ROW_GAP - 0.01f,
                    "SCORE sits against the health bar at " + size[0] + "x" + size[1]
                            + ": bar bottom " + barBottom + ", score top " + scoreTop);
        }
    }

    @Test
    @DisplayName("the health bar, score, talents and clock rows all exist and are ordered")
    void everyInformationRowIsPresent() {
        TestUi t = endless(1280, 720);
        HudScreen hud = t.ui.hud();
        for (String id : ROWS) {
            assertTrue(hud.hasRow(id), "missing row " + id);
            assertTrue(hud.rowHeight(id) > 0f, id + " has no height");
        }
        //  Top to bottom, in the source's order.
        for (int i = 1; i < ROWS.length; i++) {
            assertTrue(hud.rowTop(ROWS[i - 1]) > hud.rowTop(ROWS[i]),
                    ROWS[i - 1] + " should sit above " + ROWS[i]);
        }
    }

    @Test
    @DisplayName("the health bar is the source's 18 units tall")
    void healthBarHeight() {
        TestUi t = endless(1280, 720);
        assertEquals(HudScreen.HEALTH_BAR_HEIGHT,
                t.ui.hud().rowHeight("hud.row.health"), 0.01f);
    }

    @Test
    @DisplayName("the Endless SHOP button sits in the clock row, not below the panel")
    void shopButtonRidesTheClockRow() {
        TestUi t = endless(1280, 720);
        HudScreen hud = t.ui.hud();
        assertTrue(hud.shopButton().visible(), "Endless shows the button");
        assertEquals(hud.rowBottom("hud.row.clock"), hud.shopButton().visualY(), 0.01f,
                "the button should sit on the clock row's baseline");
        assertTrue(hud.shopButton().visualX() > hud.panelX(),
                "and inside the panel");
    }

    // ========================================================================
    //  The stack stays inside its own panel
    // ========================================================================

    @Test
    @DisplayName("every row lies inside the panel, with the padding respected")
    void rowsStayInsideThePanel() {
        for (int[] size : new int[][] {{1280, 720}, {2400, 1080}, {1080, 2400}}) {
            TestUi t = endless(size[0], size[1]);
            t.withInsets(96, 96, 44, 56);
            HudScreen hud = t.ui.hud();
            float innerTop = hud.panelY() + hud.panelHeight() - HudScreen.PANEL_PAD;
            float innerBottom = hud.panelY() + HudScreen.PANEL_PAD;
            String where = " at " + size[0] + "x" + size[1];

            Array<String> rows = hud.rows();
            for (int i = 0; i < rows.size; i++) {
                String id = rows.get(i);
                assertTrue(hud.rowTop(id) <= innerTop + 0.01f,
                        id + " starts above the panel's padding" + where);
                assertTrue(hud.rowBottom(id) >= innerBottom - 0.01f,
                        id + " runs below the panel's padding" + where);
            }
        }
    }

    @Test
    @DisplayName("the panel is exactly as tall as its rows and padding")
    void panelHeightMatchesTheStack() {
        //  The measured stack is the whole point: a panel sized to anything but
        //  its contents is how a row ends up outside it.
        TestUi t = endless(1280, 720);
        HudScreen hud = t.ui.hud();
        Array<String> rows = hud.rows();

        float total = 0f;
        for (int i = 0; i < rows.size; i++) {
            total += hud.rowHeight(rows.get(i));
        }
        total += HudScreen.ROW_GAP * (rows.size - 1);
        assertEquals(total + HudScreen.PANEL_PAD * 2f, hud.panelHeight(), 0.01f);
    }

    @Test
    @DisplayName("a Classic run has no clock row, and the rest still do not overlap")
    void classicHasNoClockRow() {
        TestUi t = new TestUi(1280, 720)
                .startRun(GameMode.CLASSIC, "normal").beginPlaying();
        t.ui.layout();
        assertTrue(t.ui.hud().hasRow("hud.row.score"));
        assertEquals(false, t.ui.hud().hasRow("hud.row.clock"),
                "the run clock is an Endless row");
        assertRowsSeparated(t, "classic 1280x720");
    }
}
