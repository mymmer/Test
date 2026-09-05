package com.mymmer.castledefense.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.badlogic.gdx.utils.Array;
import com.mymmer.castledefense.config.DifficultyTable;
import com.mymmer.castledefense.game.GameMode;
import com.mymmer.castledefense.game.GameState;
import com.mymmer.castledefense.shop.ShopItemDef;
import com.mymmer.castledefense.skill.SkillId;
import com.mymmer.castledefense.talent.TalentBranch;
import com.mymmer.castledefense.talent.TalentDef;
import com.mymmer.castledefense.text.Strings;
import com.mymmer.castledefense.progress.TestRun;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every string the interface can show exists, and every box it can show it in
 * survives a much longer one.
 *
 * <h2>Why completeness is a test and not a review</h2>
 *
 * <p>The shop, the talent tree and the skill bar are all data-driven: a new entry
 * in {@code talents.json} appears on screen without any code being written. The
 * one thing that does <b>not</b> follow automatically is its name, so the failure
 * mode is a live node labelled {@code ???} or {@code talent.newthing.name}. The
 * table is the source of truth here and the bundle is checked against it, not the
 * other way round.
 */
class UiTextTest {

    private TestRun run;

    @BeforeEach
    void setUp() {
        Strings.load(Locale.ENGLISH);
        run = new TestRun();
    }

    @AfterEach
    void tearDown() {
        Strings.unload();
    }

    // ========================================================================
    //  Completeness, driven by the data tables
    // ========================================================================

    @Test
    @DisplayName("every shop item has a name and a description")
    void everyShopItemIsNamed() {
        Array<ShopItemDef> items = run.shop().items();
        assertEquals(11, items.size, "all eleven, or this test is checking the wrong list");
        Array<String> missing = new Array<>();
        for (int i = 0; i < items.size; i++) {
            require("shop." + items.get(i).id + ".name", missing);
            require("shop." + items.get(i).id + ".desc", missing);
        }
        assertNoneMissing(missing);
    }

    @Test
    @DisplayName("every talent has a name and a description")
    void everyTalentIsNamed() {
        Array<String> missing = new Array<>();
        int count = 0;
        for (TalentBranch branch : TalentBranch.values()) {
            Array<TalentDef> inBranch = run.talents().table().branch(branch);
            for (int i = 0; i < inBranch.size; i++) {
                count++;
                require("talent." + inBranch.get(i).id + ".name", missing);
                require("talent." + inBranch.get(i).id + ".desc", missing);
            }
        }
        assertEquals(38, count, "all thirty-eight");
        assertNoneMissing(missing);
    }

    @Test
    @DisplayName("every talent branch has a heading")
    void everyBranchIsNamed() {
        Array<String> missing = new Array<>();
        for (TalentBranch branch : TalentBranch.values()) {
            require("talent.branch." + branch.id(), missing);
        }
        assertNoneMissing(missing);
    }

    @Test
    @DisplayName("every skill has a name and a description")
    void everySkillIsNamed() {
        Array<String> missing = new Array<>();
        for (SkillId id : SkillId.values()) {
            require("skill." + id.id() + ".name", missing);
            require("skill." + id.id() + ".desc", missing);
        }
        assertNoneMissing(missing);
    }

    @Test
    @DisplayName("every difficulty has a name")
    void everyDifficultyIsNamed() {
        Array<String> missing = new Array<>();
        DifficultyTable table = run.difficulties;
        Array<com.mymmer.castledefense.config.DifficultyConfig> all = table.all();
        for (int i = 0; i < all.size; i++) {
            require("difficulty." + all.get(i).id() + ".name", missing);
        }
        assertNoneMissing(missing);
    }

    @Test
    @DisplayName("no string in the bundle is blank")
    void nothingIsBlank() {
        //  A blank value is worse than a missing one: it passes a lookup and
        //  shows an empty button.
        Array<String> blank = new Array<>();
        for (String key : bundleKeys()) {
            if (Strings.get(key).trim().isEmpty()) {
                blank.add(key);
            }
        }
        if (blank.size > 0) {
            fail("blank strings: " + blank);
        }
    }

    @Test
    @DisplayName("every placeholder string formats without throwing")
    void placeholdersAreWellFormed() {
        //  A stray unbalanced brace in a .properties file is a crash at the
        //  moment the player opens that screen, which is the worst time to find
        //  it.  Formatting each one with generous arguments finds it here.
        Object[] args = {1, 2, 3, 4};
        Array<String> broken = new Array<>();
        for (String key : bundleKeys()) {
            try {
                assertNotNull(Strings.format(key, args));
            } catch (RuntimeException e) {
                broken.add(key + " (" + e.getClass().getSimpleName() + ")");
            }
        }
        if (broken.size > 0) {
            fail("malformed patterns: " + broken);
        }
    }

    /**
     * Every literal key the interface and its renderer ask for actually exists.
     *
     * <p>The data-driven checks above cover the keys built from a table id. This
     * covers the other kind — the ones typed into the source — by reading the
     * source rather than by keeping a list beside it, because a list beside it
     * is a list that goes stale. It found nine missing keys the first time it
     * ran, including the price on every shop card.
     *
     * <p>Keys assembled at runtime ({@code "talent." + id + ".name"}) are not
     * literals and are deliberately not matched here; they have their own tests.
     */
    @Test
    @DisplayName("every string key written in the interface source exists")
    void literalKeysAllExist() {
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "Strings\\.(?:get|format)\\(\\s*\"([^\"]+)\"\\s*[,)]");
        Array<String> missing = new Array<>();
        int found = 0;
        for (String pkg : new String[] {"ui", "render"}) {
            java.io.File dir = new java.io.File(
                    "../core/src/main/java/com/mymmer/castledefense/" + pkg);
            assertTrue(dir.isDirectory(), "no such package: " + dir.getAbsolutePath());
            for (java.io.File f : dir.listFiles()) {
                if (!f.getName().endsWith(".java")) {
                    continue;
                }
                java.util.regex.Matcher m = p.matcher(readFile(f));
                while (m.find()) {
                    found++;
                    require(m.group(1), missing);
                }
            }
        }
        assertTrue(found > 20, "the scan found almost no keys: " + found);
        assertNoneMissing(missing);
    }

    // ========================================================================
    //  Long strings
    // ========================================================================

    @Test
    @DisplayName("a translation three times as long does not burst any layout")
    void longStringsDoNotEscape() {
        //  German and Finnish routinely run half again as long as English;
        //  three times is deliberately past anything real.  Nothing may leave
        //  the safe rectangle and every control must stay pressable.
        for (int[] screen : new int[][] {{1280, 720}, {2400, 1080}, {1080, 2400}}) {
            for (GameState state : new GameState[] {GameState.MENU, GameState.SETTINGS,
                GameState.SHOP, GameState.TALENTS, GameState.PLAYING,
                GameState.PAUSED, GameState.GAMEOVER}) {
                TestUi t = new TestUi(screen[0], screen[1]);
                t.metrics.widthRatio = 0.52f * 3f;      // every glyph three times wider
                if (state != GameState.MENU && state != GameState.SETTINGS) {
                    t.startRun(GameMode.ENDLESS, "normal");
                }
                t.run.world.setState(state);
                t.ui.layout();

                SafeArea safe = t.ui.safeArea();
                for (UiRect c : t.ui.allControls()) {
                    if (!c.visible()) {
                        continue;
                    }
                    assertTrue(safe.contains(c), c + " escaped with long text at "
                            + screen[0] + "x" + screen[1] + " in " + state);
                    assertTrue(TouchTargets.meetsMinimum(c),
                            c + " shrank below the touch minimum with long text");
                }
            }
        }
    }

    @Test
    @DisplayName("the HUD panel grows downward for a taller line, not off the screen")
    void hudPanelAbsorbsATallerLine() {
        TestUi t = new TestUi(1280, 720).startRun(GameMode.ENDLESS, "normal").beginPlaying();
        float normal = t.ui.hud().panelHeight();

        t.metrics.heightRatio = 1.20f * 2f;
        t.ui.layout();
        float tall = t.ui.hud().panelHeight();

        assertTrue(tall > normal, "the measured stack should have grown");
        SafeArea safe = t.ui.safeArea();
        assertTrue(t.ui.hud().panelY() >= safe.y - 0.01f,
                "and it must not run off the bottom");
        assertTrue(t.ui.hud().panelY() + t.ui.hud().panelHeight() <= safe.top() + 0.01f);
    }

    // ========================================================================
    //  TextLayout itself
    // ========================================================================

    @Test
    @DisplayName("fitToWidth steps the size down until it fits, and no further")
    void fitToWidthShrinksJustEnough() {
        TextLayout text = new TextLayout(new TestUi.Metrics());
        float size = text.fitToWidth("A REASONABLY LONG BUTTON LABEL", 200f, 40f, 10f);
        assertTrue(size <= 40f && size >= 10f);
        assertTrue(text.width("A REASONABLY LONG BUTTON LABEL", size) <= 200f,
                "it must actually fit");
        assertTrue(text.width("A REASONABLY LONG BUTTON LABEL", size + 1f) > 200f,
                "and one step larger must not, or it shrank too far");
    }

    @Test
    @DisplayName("a word longer than the box is broken rather than left overflowing")
    void unbreakableWordsAreBroken() {
        TextLayout text = new TextLayout(new TestUi.Metrics());
        TextLayout.Paragraph wrapped =
                text.wrap("SUPERCALIFRAGILISTICEXPIALIDOCIOUS", 100f, 20f, 4f);
        assertTrue(wrapped.lines.size > 1, "it had to break somewhere");
        for (String line : wrapped.lines) {
            assertTrue(text.width(line, 20f) <= 100f + 0.01f,
                    "'" + line + "' still overflows");
        }
    }

    @Test
    @DisplayName("ellipsis is added inside the width, never past it")
    void ellipsisFitsInside() {
        TextLayout text = new TextLayout(new TestUi.Metrics());
        String cut = text.ellipsize("A VERY LONG NAME INDEED", 80f, 20f);
        assertTrue(text.width(cut, 20f) <= 80f + 0.01f, "'" + cut + "' is too wide");
        assertTrue(cut.endsWith("..."), "and it should read as truncated: " + cut);
    }

    @Test
    @DisplayName("a paragraph that cannot fit is clipped, and says so")
    void clippingIsReported() {
        TextLayout text = new TextLayout(new TestUi.Metrics());
        TextLayout.Paragraph p = text.fitInBox(
                "ONE TWO THREE FOUR FIVE SIX SEVEN EIGHT NINE TEN ELEVEN TWELVE",
                120f, 30f, 18f, 9f, 4f);
        assertTrue(p.clipped, "it plainly does not fit, and pretending otherwise "
                + "is how text ends up drawn over a button");
        assertTrue(p.lines.size >= 1);
        for (String line : p.lines) {
            assertTrue(text.width(line, p.size) <= 120f + 0.01f);
        }
    }

    @Test
    @DisplayName("an empty or null string is laid out without complaint")
    void emptyTextIsHarmless() {
        TextLayout text = new TextLayout(new TestUi.Metrics());
        assertEquals(0, text.wrap("", 100f, 20f, 4f).lines.size);
        assertEquals(0, text.wrap(null, 100f, 20f, 4f).lines.size);
        assertEquals("", text.ellipsize("", 100f, 20f));
        assertFalse(text.fitInBox("", 100f, 100f, 20f, 10f, 4f).clipped);
    }

    // ------------------------------------------------------------------------

    private static String readFile(java.io.File f) {
        try {
            return new String(java.nio.file.Files.readAllBytes(f.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new AssertionError("cannot read " + f, e);
        }
    }

    private static void require(String key, Array<String> missing) {
        String value = Strings.get(key);
        if (value == null || value.isEmpty() || value.equals(key)
                || value.startsWith("???")) {
            missing.add(key);
        }
    }

    private static void assertNoneMissing(Array<String> missing) {
        if (missing.size > 0) {
            fail("missing " + missing.size + " string(s): " + missing);
        }
    }

    /** Every key in the shipped bundle. */
    private static Array<String> bundleKeys() {
        Array<String> keys = new Array<>();
        java.io.File file = new java.io.File("../assets/i18n/strings.properties");
        if (!file.exists()) {
            file = new java.io.File("assets/i18n/strings.properties");
        }
        assertTrue(file.exists(), "cannot find the bundle from "
                + new java.io.File(".").getAbsolutePath());
        try (java.io.BufferedReader in = new java.io.BufferedReader(
                new java.io.InputStreamReader(new java.io.FileInputStream(file),
                        java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq > 0) {
                    keys.add(line.substring(0, eq).trim());
                }
            }
        } catch (java.io.IOException e) {
            throw new AssertionError("cannot read the bundle", e);
        }
        assertTrue(keys.size > 100, "suspiciously few keys: " + keys.size);
        return keys;
    }
}
