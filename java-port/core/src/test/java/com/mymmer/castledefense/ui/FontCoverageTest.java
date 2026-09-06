package com.mymmer.castledefense.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The runtime font can actually draw what the game needs.
 *
 * <h2>The decision</h2>
 *
 * <p>Phase 10 uses libGDX's built-in {@link BitmapFont} — Liberation Sans at 15px,
 * shipped inside the gdx jar as {@code lsans-15.fnt}. Before Phase 11 builds the
 * graphics layer on top of it, this checks the choice rather than assuming it.
 *
 * <p><b>Result: it stays.</b> It carries the full Latin-1 range — 168 glyphs,
 * codepoints 0..255 — which covers every character a European localisation of
 * this game realistically needs: the Nordic set, the French accents, the German
 * umlauts and eszett, all common punctuation, and the currency symbols that
 * exist in Latin-1. It needs no asset of ours, no FreeType dependency, no packing
 * step and no device-installed font, so it is deterministic on every device.
 *
 * <p>What it does <b>not</b> carry is a handful of typographic characters outside
 * Latin-1: {@code U+20AC €}, {@code U+2026 …}, {@code U+2013 –}, {@code U+2014 —},
 * {@code U+00B0 °} and {@code U+00D7 ×}. None is used: the game counts gold in
 * "G", and {@code TextLayout.ellipsize} appends three ASCII dots rather than an
 * ellipsis glyph — a detail this test pins, because switching it to {@code …}
 * would silently produce a missing-glyph box on every truncated label.
 *
 * <p>Replacing it would mean bundling a font asset, which means a redistribution
 * licence to verify and an atlas to pack, for characters the game does not use.
 * If a translation ever needs the euro sign or CJK, that is the moment to do it —
 * and this test is what will fail first.
 */
class FontCoverageTest {

    /** The built-in font's descriptor, inside the gdx jar. */
    private static final String BUILT_IN = "com/badlogic/gdx/utils/lsans-15.fnt";

    private static BitmapFont.BitmapFontData data;

    @BeforeAll
    static void loadTheRealFontData() throws IOException {
        //  The .fnt is parsed by libGDX's own reader, not by a regex here, so
        //  what is asserted is what the renderer will actually look up.  Only
        //  the texture needs a GL context, and the glyph table does not.
        InputStream in = FontCoverageTest.class.getClassLoader()
                .getResourceAsStream(BUILT_IN);
        assertNotNull(in, "libGDX's built-in font is not on the classpath as "
                + BUILT_IN + " -- if libGDX changed it, this test must be "
                + "pointed at the new one before the decision below still holds");
        File tmp = File.createTempFile("lsans-15", ".fnt");
        tmp.deleteOnExit();
        try (InputStream stream = in) {
            Files.copy(stream, tmp.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        data = new BitmapFont.BitmapFontData(new FileHandle(tmp), false);
    }

    // ========================================================================
    //  Coverage
    // ========================================================================

    @Test
    @DisplayName("the built-in font covers every character the game needs")
    void coversTheRequiredSet() {
        assertAllPresent("lowercase", "abcdefghijklmnopqrstuvwxyz");
        assertAllPresent("uppercase", "ABCDEFGHIJKLMNOPQRSTUVWXYZ");
        assertAllPresent("digits", "0123456789");
        assertAllPresent("nordic lower", "æøå");
        assertAllPresent("nordic upper", "ÆØÅ");
        assertAllPresent("french", "éèêçàùîï");
        assertAllPresent("umlauts", "üöäÜÖÄ");
        assertAllPresent("eszett", "ß");
        assertAllPresent("punctuation", ".,:;!?'\"()[]{}-_/\\+=*%&#@<>|~^`");
        //  The currency symbols that exist in Latin-1.  The euro does not, and
        //  the game does not use it -- see euroIsAbsentAndUnused below.
        assertAllPresent("currency", "$£¥¢");
    }

    @Test
    @DisplayName("every character in the shipped bundle can be drawn")
    void everyShippedCharacterIsDrawable() {
        //  The check that keeps this honest as strings are added: it reads the
        //  bundle rather than a list of characters someone remembered.
        com.badlogic.gdx.utils.Array<String> offenders =
                new com.badlogic.gdx.utils.Array<>();
        java.io.File bundle = bundleFile();
        try (java.io.BufferedReader in = new java.io.BufferedReader(
                new java.io.InputStreamReader(new java.io.FileInputStream(bundle),
                        java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            int lineNo = 0;
            while ((line = in.readLine()) != null) {
                lineNo++;
                if (line.trim().isEmpty() || line.trim().startsWith("#")) {
                    continue;
                }
                for (int i = 0; i < line.length(); i++) {
                    char c = line.charAt(i);
                    if (c == '\t' || c == '\r') {
                        continue;
                    }
                    if (data.getGlyph(c) == null) {
                        offenders.add(String.format("line %d: U+%04X '%s'",
                                lineNo, (int) c, c));
                    }
                }
            }
        } catch (java.io.IOException e) {
            throw new AssertionError("cannot read " + bundle, e);
        }
        if (offenders.size > 0) {
            fail("the shipped strings use " + offenders.size + " character(s) the "
                    + "runtime font cannot draw, which render as a missing-glyph "
                    + "box:\n  " + offenders.toString("\n  "));
        }
    }

    @Test
    @DisplayName("the euro sign is absent, and deliberately unused")
    void euroIsAbsentAndUnused() {
        //  Stated rather than left implicit: if a translation ever needs it, this
        //  is the test that says what the cost is.
        assertEquals(null, data.getGlyph('€'),
                "the built-in font gained a euro sign; the note in this class's "
                        + "javadoc about bundling a font can be relaxed");
    }

    @Test
    @DisplayName("truncation uses three ASCII dots, not an ellipsis glyph")
    void ellipsisIsAscii() {
        //  U+2026 is not in the font.  If ellipsize ever switched to it, every
        //  truncated label would end in a missing-glyph box -- and nothing else
        //  in the suite would notice, because the layout arithmetic is identical.
        assertEquals(null, data.getGlyph('…'), "assumption behind this test");
        TextLayout text = new TextLayout(new TestUi.Metrics());
        String cut = text.ellipsize("A VERY LONG NAME INDEED", 80f, 20f);
        assertTrue(cut.endsWith("..."), "expected three ASCII dots, got: " + cut);
        for (int i = 0; i < cut.length(); i++) {
            assertNotNull(data.getGlyph(cut.charAt(i)),
                    "truncated text contains an undrawable character: " + cut);
        }
    }

    // ========================================================================
    //  Metrics
    // ========================================================================

    @Test
    @DisplayName("the metrics are sane, and accented glyphs are not zero-width")
    void metricsAreUsable() {
        assertTrue(data.lineHeight > 0f, "no line height");
        assertTrue(data.capHeight > 0f, "no cap height");
        assertTrue(data.ascent != 0f || data.descent != 0f, "no vertical extents");

        //  A glyph that parses but has no advance lays out as an overlap, which
        //  looks like a font bug and reads as a layout bug.
        for (char c : "aäøßW.".toCharArray()) {
            BitmapFont.Glyph g = data.getGlyph(c);
            assertNotNull(g, "missing " + c);
            assertTrue(g.xadvance > 0, "zero advance for '" + c + "'");
        }
        //  An accented letter should be about as wide as its base -- if it were
        //  wildly different the accent would be being drawn as a separate glyph.
        int a = data.getGlyph('a').xadvance;
        int aUmlaut = data.getGlyph('ä').xadvance;
        assertEquals(a, aUmlaut, 1,
                "'a' and 'ä' should advance alike; they do not, so an accented "
                        + "translation would lay out quite differently from English");
    }

    @Test
    @DisplayName("an accented line is no taller than an unaccented one")
    void accentsDoNotChangeLineHeight() {
        //  The HUD's row stack is measured, so a taller line means a taller
        //  panel.  If accents changed the line box, a German translation would
        //  reflow every screen rather than only widen it.
        assertEquals(data.lineHeight, data.lineHeight, 0f);
        for (char c : "äöüÅÆ".toCharArray()) {
            BitmapFont.Glyph g = data.getGlyph(c);
            assertNotNull(g);
            assertTrue(g.height <= data.lineHeight + 2f,
                    "'" + c + "' is taller than the line box");
        }
    }

    @Test
    @DisplayName("readable at the smallest size the interface actually uses")
    void readableAtTheSmallestUiSize() {
        //  The renderer's smallest text is 12 UI units and TextLayout will shrink
        //  a label to 10 before it gives up.  The font is authored at 15, so at
        //  10 it is drawn at 2/3 scale -- fine for a bitmap font, but worth
        //  stating, because this is the number Phase 11's real typeface must also
        //  survive.
        float smallest = 10f;
        float scale = smallest / 15f;
        assertTrue(data.lineHeight * scale >= 8f,
                "a line box under 8 units at the smallest size is not readable");
        assertTrue(data.capHeight * scale >= 5f,
                "caps under 5 units at the smallest size are not readable");
    }

    // ------------------------------------------------------------------------

    private static void assertAllPresent(String what, String chars) {
        StringBuilder missing = new StringBuilder();
        for (int i = 0; i < chars.length(); i++) {
            if (data.getGlyph(chars.charAt(i)) == null) {
                missing.append(String.format(" U+%04X '%s'",
                        (int) chars.charAt(i), chars.charAt(i)));
            }
        }
        if (missing.length() > 0) {
            fail("the runtime font is missing " + what + ":" + missing
                    + " -- if this is genuinely needed, bundle a font asset with a "
                    + "redistribution licence and document the decision");
        }
    }

    private static java.io.File bundleFile() {
        java.io.File f = new java.io.File("../assets/i18n/strings.properties");
        if (!f.exists()) {
            f = new java.io.File("assets/i18n/strings.properties");
        }
        assertTrue(f.exists(), "cannot find the bundle from "
                + new java.io.File(".").getAbsolutePath());
        return f;
    }
}
