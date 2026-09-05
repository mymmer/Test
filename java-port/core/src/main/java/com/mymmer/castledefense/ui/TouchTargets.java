package com.mymmer.castledefense.ui;

import com.badlogic.gdx.utils.Array;

/**
 * How big a thing has to be before a thumb can reliably hit it.
 *
 * <h2>The minimum</h2>
 *
 * <p>{@link #MIN_UI_UNITS} is the shortest side any interactive control's
 * <em>touch</em> bounds may have. It is expressed in UI viewport units, which
 * are device-independent by construction — the UI viewport keeps 720 units of
 * height on every screen, so a unit is a fixed fraction of the display rather
 * than a pixel that shrinks on a dense panel.
 *
 * <p>44 units of a 720-unit-tall viewport is about 6% of the screen height,
 * which on a typical phone is a little over 9 mm — comfortably inside the range
 * both platform guidelines call the minimum. Choosing it in UI units rather than
 * millimetres means it does not need a DPI query it cannot trust.
 *
 * <p><b>Visual bounds are never enlarged to satisfy this.</b> The artwork is the
 * source's; only the touch box grows. See {@link UiRect}.
 *
 * <h2>Overlap</h2>
 *
 * <p>Growing touch boxes can make two of them overlap, and a single press that
 * could activate two controls is a bug however it is resolved. The layout is
 * expected to space controls so that it does not happen; where it cannot,
 * {@link #firstHit} resolves by <b>registration order</b>, which is the same
 * first-claim rule the input router already uses for consumers. Nothing is ever
 * decided by area or by z-order.
 */
public final class TouchTargets {

    /** Shortest side of any touch box, in UI units. */
    public static final float MIN_UI_UNITS = 44f;

    /** A little breathing room between adjacent controls, in UI units. */
    public static final float RECOMMENDED_GAP = 8f;

    private TouchTargets() {
    }

    /** Grows a control's touch box to the minimum, leaving its artwork alone. */
    public static UiRect apply(UiRect rect) {
        return rect.expandHitTo(MIN_UI_UNITS, MIN_UI_UNITS);
    }

    /** Applies the minimum to every control in a list. */
    public static void applyAll(Array<UiRect> rects) {
        for (int i = 0; i < rects.size; i++) {
            apply(rects.get(i));
        }
    }

    /** Does this control meet the minimum? */
    public static boolean meetsMinimum(UiRect rect) {
        return rect.hitWidth() >= MIN_UI_UNITS - 1e-3f
                && rect.hitHeight() >= MIN_UI_UNITS - 1e-3f;
    }

    /**
     * The control a press belongs to.
     *
     * <p>First match in registration order wins, and exactly one control ever
     * does. Returns null when the press missed everything.
     */
    public static UiRect firstHit(Array<UiRect> rects, float x, float y) {
        for (int i = 0; i < rects.size; i++) {
            UiRect r = rects.get(i);
            if (r.hits(x, y)) {
                return r;
            }
        }
        return null;
    }

    /**
     * Pairs whose touch boxes overlap.
     *
     * <p>A diagnostic for layout tests. Overlap is not automatically wrong —
     * {@link #firstHit} resolves it deterministically — but it is worth knowing
     * about, because spacing the controls apart is nearly always the better fix.
     */
    public static Array<String> overlaps(Array<UiRect> rects) {
        Array<String> out = new Array<>();
        for (int i = 0; i < rects.size; i++) {
            UiRect a = rects.get(i);
            if (!a.pressable()) {
                continue;
            }
            for (int j = i + 1; j < rects.size; j++) {
                UiRect b = rects.get(j);
                if (b.pressable() && a.hitOverlaps(b)) {
                    out.add(a.id + " <-> " + b.id);
                }
            }
        }
        return out;
    }
}
