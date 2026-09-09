package com.mymmer.castledefense.platform;

/**
 * Screen edges the UI must stay clear of, in physical pixels — of two different
 * kinds, which are deliberately not merged.
 *
 * <h2>Obscuring, and gesture</h2>
 *
 * <p><b>Obscuring</b> insets are where something is drawn on top of the app: a
 * notch, a camera hole, a rounded corner, a visible system bar. Nothing the
 * player must see or press may go there, because it would be covered.
 *
 * <p><b>Gesture</b> insets are where the <em>system</em> takes the touch. The
 * pixels are visible and the app may draw there quite happily — but a press can
 * be claimed by the platform before the game ever hears about it, so an
 * interactive control in that strip is unreliable while a background is fine.
 *
 * <p>They are not interchangeable and they are not nested. On a Galaxy S10+ in
 * landscape the cutout takes 142px from the <b>left</b> and the navigation strip
 * 168px from the <b>right</b>: treating either as "the" inset gets one edge
 * wrong. Phase 13 shipped with 45% of the SETTINGS button inside the right
 * strip precisely because only the cutout was being read.
 *
 * <p>Immutable and platform-free. The Android launcher fills it from
 * {@code WindowInsets}; desktop reports zeroes, or simulated values when the
 * launcher is asked to imitate a phone.
 *
 * <p>Only the UI viewport consults this. The world is never cropped by it —
 * background art may run under a cutout quite happily; a health bar may not.
 */
public final class SafeAreaInsets {

    public static final SafeAreaInsets NONE = new SafeAreaInsets(0, 0, 0, 0);

    private final int left;
    private final int right;
    private final int top;
    private final int bottom;

    private final int gestureLeft;
    private final int gestureRight;
    private final int gestureTop;
    private final int gestureBottom;

    /** Obscuring insets only; no gesture strips reported. */
    public SafeAreaInsets(int left, int right, int top, int bottom) {
        this(left, right, top, bottom, 0, 0, 0, 0);
    }

    /**
     * Both kinds.
     *
     * <p>The gesture quad is <b>absolute</b>, not additional: it is measured
     * from the same screen edges, so an edge with a 142px cutout and a 40px
     * gesture strip has {@code left = 142} and {@code gestureLeft = 40}, and the
     * strip is already inside the cutout's shadow.
     */
    public SafeAreaInsets(int left, int right, int top, int bottom,
                          int gestureLeft, int gestureRight,
                          int gestureTop, int gestureBottom) {
        this.left = Math.max(0, left);
        this.right = Math.max(0, right);
        this.top = Math.max(0, top);
        this.bottom = Math.max(0, bottom);
        this.gestureLeft = Math.max(0, gestureLeft);
        this.gestureRight = Math.max(0, gestureRight);
        this.gestureTop = Math.max(0, gestureTop);
        this.gestureBottom = Math.max(0, gestureBottom);
    }

    public int gestureLeft() {
        return gestureLeft;
    }

    public int gestureRight() {
        return gestureRight;
    }

    public int gestureTop() {
        return gestureTop;
    }

    public int gestureBottom() {
        return gestureBottom;
    }

    /**
     * The edge a <em>control</em> must clear: the larger of the two on each side.
     *
     * <p>A control has to be both visible and pressable, so it clears whichever
     * strip reaches further in. Decoration only has to clear the obscuring one.
     */
    public int touchLeft() {
        return Math.max(left, gestureLeft);
    }

    public int touchRight() {
        return Math.max(right, gestureRight);
    }

    public int touchTop() {
        return Math.max(top, gestureTop);
    }

    public int touchBottom() {
        return Math.max(bottom, gestureBottom);
    }

    public int left() {
        return left;
    }

    public int right() {
        return right;
    }

    public int top() {
        return top;
    }

    public int bottom() {
        return bottom;
    }

    public boolean isEmpty() {
        return left == 0 && right == 0 && top == 0 && bottom == 0
                && gestureLeft == 0 && gestureRight == 0
                && gestureTop == 0 && gestureBottom == 0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SafeAreaInsets)) {
            return false;
        }
        SafeAreaInsets i = (SafeAreaInsets) o;
        return left == i.left && right == i.right && top == i.top
                && bottom == i.bottom && gestureLeft == i.gestureLeft
                && gestureRight == i.gestureRight && gestureTop == i.gestureTop
                && gestureBottom == i.gestureBottom;
    }

    @Override
    public int hashCode() {
        int h = left;
        h = 31 * h + right;
        h = 31 * h + top;
        h = 31 * h + bottom;
        h = 31 * h + gestureLeft;
        h = 31 * h + gestureRight;
        h = 31 * h + gestureTop;
        h = 31 * h + gestureBottom;
        return h;
    }

    @Override
    public String toString() {
        return "insets[l=" + left + " r=" + right + " t=" + top + " b=" + bottom
                + " gesture l=" + gestureLeft + " r=" + gestureRight
                + " t=" + gestureTop + " b=" + gestureBottom + "]";
    }
}
