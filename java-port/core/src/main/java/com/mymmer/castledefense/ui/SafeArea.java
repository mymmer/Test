package com.mymmer.castledefense.ui;

import com.mymmer.castledefense.platform.SafeAreaInsets;
import com.mymmer.castledefense.render.ViewportSet;

/**
 * The rectangle of the UI viewport it is safe to put a control in.
 *
 * <p>A phone's screen is not all usable: a notch, a camera hole, a rounded
 * corner or a gesture bar can each sit on top of what is drawn there. The
 * platform reports those as {@link SafeAreaInsets} in <b>screen pixels</b>; this
 * converts them into the UI viewport's own units and hands back a rectangle.
 *
 * <h2>The rule</h2>
 *
 * <ul>
 *   <li><b>Controls and information</b> live inside {@link #x}/{@link #y}/
 *       {@link #width}/{@link #height}. A button under a camera hole is a button
 *       the player cannot press.</li>
 *   <li><b>Background and decoration</b> may extend past it, edge to edge. A
 *       panel that stopped at the safe rect would leave a visible band.</li>
 *   <li><b>The gameplay world is unaffected.</b> It has its own viewport and its
 *       own 1280x720 coordinates; a notch never moves the castle.</li>
 * </ul>
 */
public final class SafeArea {

    /** Left edge of the safe rectangle, in UI units. */
    public final float x;
    /** Bottom edge, in UI units (y grows upward in the UI viewport). */
    public final float y;
    public final float width;
    public final float height;

    /** The full UI viewport, safe or not. Decoration may use all of it. */
    public final float fullWidth;
    public final float fullHeight;

    /**
     * The tighter rectangle an <b>interactive control</b> must sit inside.
     *
     * <p>Same as the display rectangle plus the system's gesture strips — the
     * pixels the platform may claim a press from before the game hears about
     * it. Visible, drawable, and not reliably pressable.
     *
     * <p>Text, panels and readouts use the display rectangle; anything with a
     * hit box uses this one. They differ whenever a device puts its navigation
     * strip somewhere the cutout is not, which is the ordinary case in
     * landscape.
     */
    public final float touchX;
    public final float touchY;
    public final float touchWidth;
    public final float touchHeight;

    private SafeArea(float x, float y, float width, float height,
                     float touchX, float touchY, float touchWidth,
                     float touchHeight, float fullWidth, float fullHeight) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.touchX = touchX;
        this.touchY = touchY;
        this.touchWidth = touchWidth;
        this.touchHeight = touchHeight;
        this.fullWidth = fullWidth;
        this.fullHeight = fullHeight;
    }

    /** The whole viewport, with nothing cut off. */
    public static SafeArea full(float uiWidth, float uiHeight) {
        return new SafeArea(0f, 0f, uiWidth, uiHeight,
                0f, 0f, uiWidth, uiHeight, uiWidth, uiHeight);
    }

    public float touchRight() {
        return touchX + touchWidth;
    }

    public float touchTop() {
        return touchY + touchHeight;
    }

    public float touchCenterX() {
        return touchX + touchWidth / 2f;
    }

    public float touchCenterY() {
        return touchY + touchHeight / 2f;
    }

    /** Is this control entirely inside the rectangle a control must sit in? */
    public boolean containsTouch(UiRect rect) {
        return rect != null
                && rect.insideBounds(touchX, touchY, touchWidth, touchHeight);
    }

    /**
     * Converts platform insets into UI units.
     *
     * <p>The insets arrive in screen pixels; the UI viewport has its own scale
     * and may be letterboxed, so a 100-pixel notch is not 100 UI units. The
     * conversion is the viewport's world-per-pixel ratio on each axis.
     *
     * <p>An inset larger than the screen, or a degenerate viewport, yields the
     * full rectangle rather than a negative one: a broken report must not make
     * the interface unusable.
     */
    public static SafeArea of(ViewportSet viewports, SafeAreaInsets insets) {
        if (viewports == null) {
            return full(1f, 1f);
        }
        float uiWidth = viewports.getUi().getWorldWidth();
        float uiHeight = viewports.getUi().getWorldHeight();
        if (insets == null || insets.isEmpty()) {
            return full(uiWidth, uiHeight);
        }

        int screenW = viewports.getUi().getScreenWidth();
        int screenH = viewports.getUi().getScreenHeight();
        if (screenW <= 0 || screenH <= 0) {
            return full(uiWidth, uiHeight);
        }

        float perPixelX = uiWidth / screenW;
        float perPixelY = uiHeight / screenH;

        float left = Math.max(0f, insets.left() * perPixelX);
        float right = Math.max(0f, insets.right() * perPixelX);
        //  the platform reports top and bottom in screen space, where y grows
        //  downward; the UI viewport grows upward, so they swap
        float top = Math.max(0f, insets.top() * perPixelY);
        float bottom = Math.max(0f, insets.bottom() * perPixelY);

        float w = uiWidth - left - right;
        float h = uiHeight - top - bottom;
        if (w <= 0f || h <= 0f) {
            return full(uiWidth, uiHeight);
        }

        //  The control rectangle: the same conversion applied to whichever
        //  strip reaches further in on each edge.
        float tLeft = Math.max(0f, insets.touchLeft() * perPixelX);
        float tRight = Math.max(0f, insets.touchRight() * perPixelX);
        float tTop = Math.max(0f, insets.touchTop() * perPixelY);
        float tBottom = Math.max(0f, insets.touchBottom() * perPixelY);
        float tw = uiWidth - tLeft - tRight;
        float th = uiHeight - tTop - tBottom;
        if (tw <= 0f || th <= 0f) {
            //  A device claiming the whole screen is a device to disbelieve:
            //  fall back to the display rectangle rather than to nothing.
            tLeft = left;
            tBottom = bottom;
            tw = w;
            th = h;
        }
        return new SafeArea(left, bottom, w, h,
                tLeft, tBottom, tw, th, uiWidth, uiHeight);
    }

    public float right() {
        return x + width;
    }

    public float top() {
        return y + height;
    }

    public float centerX() {
        return x + width / 2f;
    }

    public float centerY() {
        return y + height / 2f;
    }

    /** Is this control entirely inside the safe rectangle? */
    public boolean contains(UiRect rect) {
        return rect != null && rect.insideBounds(x, y, width, height);
    }

    /** True when the platform reported no cutouts at all. */
    public boolean isFull() {
        return x == 0f && y == 0f && width == fullWidth && height == fullHeight;
    }

    @Override
    public String toString() {
        return "SafeArea[" + (int) x + "," + (int) y + " " + (int) width + "x"
                + (int) height + " of " + (int) fullWidth + "x" + (int) fullHeight + "]";
    }
}
