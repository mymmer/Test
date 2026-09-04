package com.mymmer.castledefense.progress;

/**
 * The screen-shake budget — gameplay state, not rendering.
 *
 * <p>Python {@code Game.add_shake} and the decay line at the top of
 * {@code update}:
 *
 * <pre>
 *   add_shake(a):  shake = min(14.0, shake + a)     // capped
 *   update(dt):    shake = max(0.0, shake - dt * 42.0)
 * </pre>
 *
 * <p>The cap is not cosmetic restraint: the world is blitted at an offset, so a
 * larger shake would expose bare canvas at the screen edge. Phase 11's
 * compositor reads {@link #amount()}; nothing here knows what a pixel is.
 *
 * <h2>Two rules that matter</h2>
 *
 * <ul>
 *   <li><b>It decays in the <em>always</em> tier.</b> Python decays shake before
 *       its state check, so a shake started just before a pause keeps unwinding
 *       on the pause screen rather than freezing mid-jolt.</li>
 *   <li><b>Quality settings must not reach it.</b> A LOW-quality device gets the
 *       same shake events and the same budget; what changes is what Phase 11
 *       does with the number. Contributions come from gameplay only.</li>
 * </ul>
 */
public final class ScreenShake {

    /** Hard ceiling, from {@code add_shake}. */
    public static final float MAX = 14f;
    /** Decay per second, from {@code update}. */
    public static final float DECAY_PER_SECOND = 42f;

    private float amount;
    private int events;

    /** Adds a jolt, capped. Python {@code add_shake}. */
    public void add(float magnitude) {
        if (magnitude <= 0f) {
            return;
        }
        amount = Math.min(MAX, amount + magnitude);
        events++;
    }

    /**
     * Decays the budget.
     *
     * <p>Called from the always-tier of the world step. The magnitude is float
     * because it is a visual amplitude, not a duration; only {@code dt} is a
     * time and it is narrowed once here.
     */
    public void decay(double dt) {
        amount = Math.max(0f, amount - (float) dt * DECAY_PER_SECOND);
    }

    /** Current amplitude, 0..{@link #MAX}. Read by the Phase 11 compositor. */
    public float amount() {
        return amount;
    }

    /** How many jolts have been added this run. Diagnostics and tests. */
    public int events() {
        return events;
    }

    public void reset() {
        amount = 0f;
        events = 0;
    }
}
