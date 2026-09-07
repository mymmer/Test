package com.mymmer.castledefense.ui;

import com.badlogic.gdx.utils.Array;

/**
 * Measuring, wrapping and fitting text — in one place, on purpose.
 *
 * <h2>Why this exists</h2>
 *
 * <p>Phase 1 flagged {@code pygame.font.Font(None, size)} as a genuine
 * portability difference: the source measures a string, shrinks the size until
 * it fits, and lays paragraphs out by hand, all inline in its draw methods.
 * Scattering the Java equivalent across every screen would mean a dozen slightly
 * different wrap rules and a dozen places for a long translation to break the
 * layout.
 *
 * <p>So the rules live here, once, and every screen calls them.
 *
 * <h2>Metrics are injected</h2>
 *
 * <p>This class never touches a {@code BitmapFont}. It takes a
 * {@link Measurer} — width of a string at a size, and the line height at a size
 * — which the renderer supplies from real font metrics and a test supplies from
 * a predictable stub. That is what lets layout be tested headlessly, and what
 * keeps {@code ui} free of GL.
 *
 * <h2>Pixel parity is not a goal; layout correctness is</h2>
 *
 * <p>Java's fonts will not measure exactly what Pygame's did, and chasing that
 * would be chasing a rasteriser. What is required instead is that text stays
 * inside its panel, does not overlap its neighbours, and shrinks or wraps
 * according to the component's contract. Those are the properties the tests
 * assert, against the real metrics rather than against transcribed Python pixel
 * widths.
 */
public final class TextLayout {

    // ========================================================================
    //  Pygame sizes to libGDX sizes
    // ========================================================================
    //
    //  Every font size in this port is a number copied from the Python source,
    //  where it is an argument to `pygame.font.Font(None, size)`.  Pygame and
    //  libGDX disagree about what that number means, and the port originally
    //  assumed they agreed -- which made every string on screen noticeably
    //  larger than the source's, with the announcements sprawling across the
    //  middle of the scene.
    //
    //  Measured, not guessed.  Nine representative strings at their real sizes
    //  were rendered with pygame and measured against the same strings through
    //  libGDX's built-in font:
    //
    //      libGDX advance width / pygame advance width  =  1.364  (mean of 9)
    //      pygame get_height() / nominal size           =  0.6676 (mean of 12)
    //      libGDX lineHeight   / nominal size           =  1.2
    //
    //  So a source size N is drawn at N * GLYPH, and one line of it advances by
    //  N * GLYPH * 1.2 * LINE.  Two constants rather than one because the two
    //  fonts disagree about the glyph size AND about how much air to leave
    //  around it; folding them together would fix the widths and leave the HUD's
    //  measured row stack a third too tall.
    //
    //  This is a UNIT CONVERSION, not a design decision.  It scales every size
    //  by the same factor, so the source's relative hierarchy -- a 34-point
    //  banner over a 24-point boss name over an 18-point hint -- is preserved
    //  exactly.  Nothing here should ever be tuned per call site; if one label
    //  looks wrong, its source size is wrong.

    /** A source font size, as a libGDX size. Measured; see above. */
    public static final float GLYPH = 0.7333f;

    /** Extra line-advance factor, so a measured row stack matches pygame's. */
    public static final float LINE = 0.7587f;

    /** A source size as the size to hand libGDX. */
    public static float glyph(float sourceSize) {
        return sourceSize * GLYPH;
    }


    /** Real font metrics, or a test's stand-in for them. */
    public interface Measurer {
        /** Width of one line of text at a size, in UI units. */
        float width(String text, float size);

        /** Height of one line at a size, in UI units. */
        float lineHeight(float size);
    }

    /** Where a line sits inside its box. */
    public enum Align {
        LEFT,
        CENTER,
        RIGHT
    }

    /** The result of laying a paragraph out: the lines, and the box they fill. */
    public static final class Paragraph {
        public final Array<String> lines = new Array<>(false, 8);
        /** The size the text was actually laid out at, after any shrinking. */
        public float size;
        /** Widest line, in UI units. */
        public float width;
        /** Total height including the gaps between lines. */
        public float height;
        /** True when the text had to be clipped to fit the box. */
        public boolean clipped;

        public int lineCount() {
            return lines.size;
        }

        @Override
        public String toString() {
            return "Paragraph[" + lines.size + " lines @" + size + " "
                    + (int) width + "x" + (int) height + (clipped ? " CLIPPED" : "") + "]";
        }
    }

    private final Measurer measurer;

    public TextLayout(Measurer measurer) {
        if (measurer == null) {
            throw new IllegalArgumentException("measurer must not be null");
        }
        this.measurer = measurer;
    }

    public Measurer measurer() {
        return measurer;
    }

    // ========================================================================
    //  Measurement
    // ========================================================================

    public float width(String text, float size) {
        return text == null || text.isEmpty() ? 0f : measurer.width(text, size);
    }

    public float lineHeight(float size) {
        return measurer.lineHeight(size);
    }

    /** Where a line starts, given its box and alignment. */
    public float alignedX(String text, float size, float boxX, float boxWidth,
                          Align align) {
        float w = width(text, size);
        switch (align) {
            case CENTER:
                return boxX + (boxWidth - w) / 2f;
            case RIGHT:
                return boxX + boxWidth - w;
            case LEFT:
            default:
                return boxX;
        }
    }

    // ========================================================================
    //  Fitting one line
    // ========================================================================

    /**
     * The largest size, not above {@code maxSize}, at which the text fits.
     *
     * <p>Python's idiom — {@code while size > floor and measure(size) > w: size--}
     * — generalised. Steps down one unit at a time so the result is the same as
     * the source's, rather than a binary search that could land elsewhere.
     *
     * <p>Returns {@code minSize} when even that is too wide; the caller decides
     * whether to clip or let it spill, because those are different contracts for
     * a button label and a HUD row.
     */
    public float fitToWidth(String text, float maxWidth, float maxSize, float minSize) {
        if (text == null || text.isEmpty() || maxWidth <= 0f) {
            return maxSize;
        }
        float size = maxSize;
        while (size > minSize && measurer.width(text, size) > maxWidth) {
            size -= 1f;
        }
        return size;
    }

    /**
     * Truncates with an ellipsis so the result fits.
     *
     * <p>For a label that must not spill and must not shrink — a shop card's
     * counter tag, a boss's name in a narrow bar.
     */
    public String ellipsize(String text, float maxWidth, float size) {
        if (text == null || text.isEmpty() || measurer.width(text, size) <= maxWidth) {
            return text;
        }
        String ellipsis = "...";
        float ellipsisWidth = measurer.width(ellipsis, size);
        if (ellipsisWidth > maxWidth) {
            return "";
        }
        int end = text.length();
        while (end > 0) {
            end--;
            String candidate = text.substring(0, end);
            if (measurer.width(candidate, size) + ellipsisWidth <= maxWidth) {
                return candidate + ellipsis;
            }
        }
        return ellipsis;
    }

    // ========================================================================
    //  Wrapping a paragraph
    // ========================================================================

    /**
     * Wraps text to a width, breaking on spaces.
     *
     * <p>A single word longer than the box is broken mid-word rather than
     * allowed to spill — a translation with a long compound noun must not run
     * out of its panel.
     */
    public Paragraph wrap(String text, float maxWidth, float size, float lineGap) {
        Paragraph p = new Paragraph();
        p.size = size;
        if (text == null || text.isEmpty()) {
            p.height = 0f;
            return p;
        }
        for (String hard : text.split("\n", -1)) {
            wrapOneLine(hard, maxWidth, size, p);
        }
        measure(p, lineGap);
        return p;
    }

    private void wrapOneLine(String text, float maxWidth, float size, Paragraph p) {
        if (text.isEmpty()) {
            p.lines.add("");
            return;
        }
        String[] words = text.split(" ");
        StringBuilder line = new StringBuilder();
        for (String word : words) {
            String candidate = line.length() == 0 ? word : line + " " + word;
            if (measurer.width(candidate, size) <= maxWidth || line.length() == 0) {
                if (measurer.width(candidate, size) > maxWidth && line.length() == 0) {
                    //  one word wider than the whole box: break it
                    breakLongWord(word, maxWidth, size, p);
                    line.setLength(0);
                    continue;
                }
                line.setLength(0);
                line.append(candidate);
            } else {
                p.lines.add(line.toString());
                line.setLength(0);
                if (measurer.width(word, size) > maxWidth) {
                    breakLongWord(word, maxWidth, size, p);
                } else {
                    line.append(word);
                }
            }
        }
        if (line.length() > 0) {
            p.lines.add(line.toString());
        }
    }

    private void breakLongWord(String word, float maxWidth, float size, Paragraph p) {
        int start = 0;
        while (start < word.length()) {
            int end = start + 1;
            while (end < word.length()
                    && measurer.width(word.substring(start, end + 1), size) <= maxWidth) {
                end++;
            }
            p.lines.add(word.substring(start, end));
            start = end;
        }
    }

    /**
     * Wraps into a box, shrinking the size until the whole paragraph fits.
     *
     * <p>The source's behaviour for its longer panels: try the intended size,
     * and step down rather than overflow. When even the minimum does not fit,
     * the extra lines are dropped and {@link Paragraph#clipped} says so — the
     * caller can then scroll, which is what the talent screen does.
     */
    public Paragraph fitInBox(String text, float maxWidth, float maxHeight,
                              float maxSize, float minSize, float lineGap) {
        Paragraph best = null;
        for (float size = maxSize; size >= minSize; size -= 1f) {
            Paragraph p = wrap(text, maxWidth, size, lineGap);
            best = p;
            if (p.height <= maxHeight) {
                return p;
            }
        }
        //  even the smallest overflows: clip to whole lines
        if (best != null && best.height > maxHeight) {
            float lh = measurer.lineHeight(best.size);
            int fit = Math.max(1, (int) ((maxHeight + lineGap) / (lh + lineGap)));
            while (best.lines.size > fit) {
                best.lines.removeIndex(best.lines.size - 1);
                best.clipped = true;
            }
            measure(best, lineGap);
        }
        return best;
    }

    private void measure(Paragraph p, float lineGap) {
        float widest = 0f;
        for (int i = 0; i < p.lines.size; i++) {
            widest = Math.max(widest, measurer.width(p.lines.get(i), p.size));
        }
        p.width = widest;
        p.height = p.lines.size == 0
                ? 0f
                : p.lines.size * measurer.lineHeight(p.size)
                        + (p.lines.size - 1) * lineGap;
    }
}
