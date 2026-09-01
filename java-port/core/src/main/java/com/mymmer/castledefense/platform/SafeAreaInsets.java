package com.mymmer.castledefense.platform;

/**
 * Screen edges the UI must stay clear of: cutouts, notches, rounded corners and
 * gesture bars, in physical pixels.
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

    public SafeAreaInsets(int left, int right, int top, int bottom) {
        this.left = Math.max(0, left);
        this.right = Math.max(0, right);
        this.top = Math.max(0, top);
        this.bottom = Math.max(0, bottom);
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
        return left == 0 && right == 0 && top == 0 && bottom == 0;
    }

    @Override
    public String toString() {
        return "insets[l=" + left + " r=" + right + " t=" + top + " b=" + bottom + "]";
    }
}
