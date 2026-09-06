package com.mymmer.castledefense.render;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.MathUtils;

/**
 * The Pygame primitives this game actually uses, and nothing else.
 *
 * <p>Deliberately not a vector-graphics framework. The source draws with
 * {@code pygame.draw.rect/circle/ellipse/line/lines/polygon/arc} and a
 * {@code border_radius} argument, so those are the operations here. Anything
 * broader would be code with no caller.
 *
 * <h2>Rounded rectangles</h2>
 *
 * <p>Pygame's {@code border_radius} appears in roughly eighty places — every
 * panel, button, card and bar. libGDX's {@link ShapeRenderer} has no such thing,
 * so it is built once here from a filled centre cross plus four corner arcs, and
 * every caller uses it. The alternative — each renderer assembling its own — is
 * how eighty panels end up with six slightly different corner radii.
 *
 * <p>Exact Pygame rasterisation is not reproduced and is not the goal: the shape
 * and layout must be recognisable, which they are.
 *
 * <h2>Batching discipline</h2>
 *
 * <p>Every method assumes the {@link ShapeRenderer} is already
 * {@code begin()}-ed in the right {@link ShapeRenderer.ShapeType}, and none of
 * them begins or ends one. That is what lets a renderer draw a whole layer in a
 * single filled pass and then a single line pass, instead of flipping type per
 * shape. {@link #fillBegin} / {@link #lineBegin} exist so a caller can switch
 * explicitly and readably.
 */
public final class ShapeKit {

    /** Segments per 90° of corner. Enough to read as round at UI scale. */
    private static final int CORNER_SEGMENTS = 6;

    private final ShapeRenderer shapes;
    private final Color tmp = new Color();

    public ShapeKit(ShapeRenderer shapes) {
        if (shapes == null) {
            throw new IllegalArgumentException("shapes must not be null");
        }
        this.shapes = shapes;
    }

    public ShapeRenderer renderer() {
        return shapes;
    }

    // ========================================================================
    //  Passes
    // ========================================================================

    /** Starts a filled pass, ending whatever was open. */
    public void fillBegin() {
        switchTo(ShapeRenderer.ShapeType.Filled);
    }

    /** Starts a line pass, ending whatever was open. */
    public void lineBegin() {
        switchTo(ShapeRenderer.ShapeType.Line);
    }

    public void end() {
        if (shapes.isDrawing()) {
            shapes.end();
        }
    }

    private void switchTo(ShapeRenderer.ShapeType type) {
        if (shapes.isDrawing()) {
            if (shapes.getCurrentType() == type) {
                return;             // already in the right pass: no flip
            }
            shapes.end();
        }
        shapes.begin(type);
    }

    /**
     * Turns blending on for translucent shapes.
     *
     * <p>{@link ShapeRenderer} does not enable it itself, so an alpha below 1
     * silently draws opaque. Every glow, veil and fading particle needs this.
     */
    public static void enableBlend() {
        if (com.badlogic.gdx.Gdx.gl != null) {
            com.badlogic.gdx.Gdx.gl.glEnable(GL20.GL_BLEND);
            com.badlogic.gdx.Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA,
                    GL20.GL_ONE_MINUS_SRC_ALPHA);
        }
    }

    /** Additive blending, for the castle's damage flash. */
    public static void enableAdditive() {
        if (com.badlogic.gdx.Gdx.gl != null) {
            com.badlogic.gdx.Gdx.gl.glEnable(GL20.GL_BLEND);
            com.badlogic.gdx.Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE);
        }
    }

    /** Back to the normal mode, so an additive pass cannot leak onward. */
    public static void restoreBlend() {
        enableBlend();
    }

    // ========================================================================
    //  Primitives
    // ========================================================================

    /**
     * A vertical gradient, bottom colour to top colour.
     *
     * <p>{@link ShapeRenderer} interpolates between per-vertex colours, so this
     * is smooth in one draw call. The alternative -- a stack of solid bands --
     * both costs more and shows seams where the rows meet.
     */
    public void gradientRect(float x, float y, float w, float h, Color bottom,
                             Color top) {
        shapes.rect(x, y, w, h, bottom, bottom, top, top);
    }

    public void rect(float x, float y, float w, float h, Color c) {
        shapes.setColor(c);
        shapes.rect(x, y, w, h);
    }

    /** An outlined rectangle of a given thickness, as pygame's width argument. */
    public void rectOutline(float x, float y, float w, float h, float thickness,
                            Color c) {
        shapes.setColor(c);
        //  Drawn as four filled bars rather than a Line-type rect: pygame's
        //  width is in pixels and a Line rect is always one pixel wide.
        shapes.rect(x, y, w, thickness);
        shapes.rect(x, y + h - thickness, w, thickness);
        shapes.rect(x, y, thickness, h);
        shapes.rect(x + w - thickness, y, thickness, h);
    }

    /**
     * A filled rounded rectangle. Pygame's {@code border_radius}.
     *
     * <p>A centre cross plus four quarter-disc corners: no overdraw seams at the
     * joins and no dependence on blending, which matters because many of these
     * are drawn opaque over one another.
     */
    public void roundRect(float x, float y, float w, float h, float radius,
                          Color c) {
        float r = Math.min(radius, Math.min(w, h) / 2f);
        if (r <= 0.5f) {
            rect(x, y, w, h, c);
            return;
        }
        shapes.setColor(c);
        shapes.rect(x + r, y, w - 2f * r, h);           // the vertical bar
        shapes.rect(x, y + r, r, h - 2f * r);           // left
        shapes.rect(x + w - r, y + r, r, h - 2f * r);   // right
        shapes.arc(x + r, y + r, r, 180f, 90f, CORNER_SEGMENTS);
        shapes.arc(x + w - r, y + r, r, 270f, 90f, CORNER_SEGMENTS);
        shapes.arc(x + r, y + h - r, r, 90f, 90f, CORNER_SEGMENTS);
        shapes.arc(x + w - r, y + h - r, r, 0f, 90f, CORNER_SEGMENTS);
    }

    /**
     * The outline of a rounded rectangle, of a given thickness.
     *
     * <p>Drawn as a larger rounded rect with a smaller one punched out of it
     * would need stencilling, so instead it is the same construction at two
     * radii: four bars and four arc bands.
     */
    public void roundRectOutline(float x, float y, float w, float h, float radius,
                                 float thickness, Color c) {
        float r = Math.min(radius, Math.min(w, h) / 2f);
        float t = Math.max(1f, thickness);
        shapes.setColor(c);
        shapes.rect(x + r, y, w - 2f * r, t);
        shapes.rect(x + r, y + h - t, w - 2f * r, t);
        shapes.rect(x, y + r, t, h - 2f * r);
        shapes.rect(x + w - t, y + r, t, h - 2f * r);
        arcBand(x + r, y + r, r, t, 180f, 90f);
        arcBand(x + w - r, y + r, r, t, 270f, 90f);
        arcBand(x + r, y + h - r, r, t, 90f, 90f);
        arcBand(x + w - r, y + h - r, r, t, 0f, 90f);
    }

    public void circle(float cx, float cy, float radius, Color c) {
        if (radius <= 0f) {
            return;
        }
        shapes.setColor(c);
        shapes.circle(cx, cy, radius, segmentsFor(radius));
    }

    /** A circle outline of a given thickness — pygame's circle(..., width). */
    public void circleOutline(float cx, float cy, float radius, float thickness,
                              Color c) {
        if (radius <= 0f) {
            return;
        }
        shapes.setColor(c);
        float t = Math.max(1f, thickness);
        int seg = segmentsFor(radius);
        for (int i = 0; i < seg; i++) {
            float a0 = MathUtils.PI2 * i / seg;
            float a1 = MathUtils.PI2 * (i + 1) / seg;
            quad(cx + MathUtils.cos(a0) * (radius - t),
                    cy + MathUtils.sin(a0) * (radius - t),
                    cx + MathUtils.cos(a0) * radius, cy + MathUtils.sin(a0) * radius,
                    cx + MathUtils.cos(a1) * radius, cy + MathUtils.sin(a1) * radius,
                    cx + MathUtils.cos(a1) * (radius - t),
                    cy + MathUtils.sin(a1) * (radius - t));
        }
    }

    /**
     * An ellipse outline of a given thickness — pygame's ellipse(..., width).
     *
     * <p>Needed by the tornado, whose funnel is thirteen stacked outlines; a
     * filled funnel would hide the mobs caught inside it.
     */
    public void ellipseOutline(float x, float y, float w, float h,
                               float thickness, Color c) {
        if (w <= 0f || h <= 0f) {
            return;
        }
        shapes.setColor(c);
        float t = Math.max(1f, thickness);
        float cx = x + w / 2f;
        float cy = y + h / 2f;
        float rx = w / 2f;
        float ry = h / 2f;
        int seg = segmentsFor(Math.max(rx, ry));
        for (int i = 0; i < seg; i++) {
            float a0 = MathUtils.PI2 * i / seg;
            float a1 = MathUtils.PI2 * (i + 1) / seg;
            quad(cx + MathUtils.cos(a0) * (rx - t), cy + MathUtils.sin(a0) * (ry - t),
                    cx + MathUtils.cos(a0) * rx, cy + MathUtils.sin(a0) * ry,
                    cx + MathUtils.cos(a1) * rx, cy + MathUtils.sin(a1) * ry,
                    cx + MathUtils.cos(a1) * (rx - t),
                    cy + MathUtils.sin(a1) * (ry - t));
        }
    }

    public void ellipse(float x, float y, float w, float h, Color c) {
        if (w <= 0f || h <= 0f) {
            return;
        }
        shapes.setColor(c);
        shapes.ellipse(x, y, w, h, segmentsFor(Math.max(w, h) / 2f));
    }

    /** A line of a given thickness. Pygame's line width, in pixels. */
    public void line(float x1, float y1, float x2, float y2, float thickness,
                     Color c) {
        shapes.setColor(c);
        shapes.rectLine(x1, y1, x2, y2, Math.max(1f, thickness));
    }

    /**
     * A connected path, as {@code pygame.draw.lines(..., closed=False)}.
     *
     * <p>Round joins are approximated with a dot at each interior vertex, which
     * is what stops a jagged lightning bolt from showing gaps at its bends.
     */
    public void path(float[] points, int count, float thickness, Color c) {
        if (count < 4) {
            return;
        }
        shapes.setColor(c);
        float t = Math.max(1f, thickness);
        for (int i = 0; i + 3 < count; i += 2) {
            shapes.rectLine(points[i], points[i + 1], points[i + 2], points[i + 3], t);
        }
        if (t > 2f) {
            for (int i = 2; i + 1 < count - 2; i += 2) {
                shapes.circle(points[i], points[i + 1], t / 2f, 8);
            }
        }
    }

    /** A filled triangle — the source's most common polygon by far. */
    public void triangle(float x1, float y1, float x2, float y2, float x3, float y3,
                         Color c) {
        shapes.setColor(c);
        shapes.triangle(x1, y1, x2, y2, x3, y3);
    }

    /** A filled convex polygon, fanned from the first vertex. */
    public void polygon(float[] points, int count, Color c) {
        if (count < 6) {
            return;
        }
        shapes.setColor(c);
        for (int i = 2; i + 3 < count; i += 2) {
            shapes.triangle(points[0], points[1],
                    points[i], points[i + 1], points[i + 2], points[i + 3]);
        }
    }

    /** A filled quad in a given colour. */
    public void quadFill(float x1, float y1, float x2, float y2,
                         float x3, float y3, float x4, float y4, Color c) {
        shapes.setColor(c);
        quad(x1, y1, x2, y2, x3, y3, x4, y4);
    }

    /** A filled quad, used by the outline helpers. */
    public void quad(float x1, float y1, float x2, float y2,
                     float x3, float y3, float x4, float y4) {
        shapes.triangle(x1, y1, x2, y2, x3, y3);
        shapes.triangle(x1, y1, x3, y3, x4, y4);
    }

    /**
     * An arc band of a given thickness — {@code pygame.draw.arc}.
     *
     * @param start degrees, counter-clockwise from east, as libGDX measures them
     */
    public void arcBand(float cx, float cy, float radius, float thickness,
                        float start, float degrees) {
        float t = Math.max(1f, thickness);
        int seg = Math.max(3, (int) (degrees / 90f * CORNER_SEGMENTS) + 2);
        float inner = Math.max(0f, radius - t);
        for (int i = 0; i < seg; i++) {
            float a0 = (start + degrees * i / seg) * MathUtils.degRad;
            float a1 = (start + degrees * (i + 1) / seg) * MathUtils.degRad;
            quad(cx + MathUtils.cos(a0) * inner, cy + MathUtils.sin(a0) * inner,
                    cx + MathUtils.cos(a0) * radius, cy + MathUtils.sin(a0) * radius,
                    cx + MathUtils.cos(a1) * radius, cy + MathUtils.sin(a1) * radius,
                    cx + MathUtils.cos(a1) * inner, cy + MathUtils.sin(a1) * inner);
        }
    }

    /**
     * {@code sprites.draw_bar}: a backed, filled, bordered bar with radius 3.
     *
     * <p>Reproduced exactly, including the {@code max(2, ...)} floor on the fill
     * width — which is why a nearly-dead enemy still shows a sliver rather than
     * nothing, and is a visible behaviour, not a rounding detail.
     */
    public void bar(float x, float y, float w, float h, float frac, Color fill,
                    Color back, Color border) {
        float f = frac < 0f ? 0f : (frac > 1f ? 1f : frac);
        roundRect(x, y, w, h, 3f, back);
        if (f > 0f) {
            roundRect(x, y, Math.max(2f, w * f), h, 3f, fill);
        }
        roundRectOutline(x, y, w, h, 3f, 1f, border);
    }

    /** The common case: the source's default back and border colours. */
    public void bar(float x, float y, float w, float h, float frac, Color fill) {
        bar(x, y, w, h, frac, fill, Palette.BAR_BACK, Palette.BAR_BORDER);
    }

    /**
     * A soft glow: concentric translucent discs, brightest in the middle.
     *
     * <p>The source builds a per-frame {@code SRCALPHA} surface and blits it,
     * which is a texture allocation every frame for every glowing thing. Drawn
     * directly instead — no allocation, no surface, and the same look at the
     * sizes this game uses. Needs blending on.
     */
    public void glow(float cx, float cy, float radius, Color c, float strength) {
        if (radius <= 0f || strength <= 0f) {
            return;
        }
        final int rings = 3;
        for (int i = rings; i >= 1; i--) {
            float t = i / (float) rings;
            shapes.setColor(Palette.alpha(c, strength * (1f - t) * 0.7f + 0.08f, tmp));
            shapes.circle(cx, cy, radius * t, segmentsFor(radius * t));
        }
    }

    /** Circle segments that stay smooth without being wasteful at small sizes. */
    private static int segmentsFor(float radius) {
        return Math.max(8, Math.min(48, (int) (radius * 1.6f) + 8));
    }
}
