package com.mymmer.castledefense.interaction;

/**
 * Where a throw's speed comes from.
 *
 * <p>A one-method seam over the Phase 4 {@code GameInput.releaseVelocity}, for
 * two reasons. It keeps the interaction layer from depending on the whole input
 * stack, and it lets a test hand in an exact flick velocity instead of
 * synthesising a plausible drag path and hoping the tracker agrees.
 *
 * <p>Whatever supplies it, the values are <b>world units per second</b> — the
 * conversion from screen pixels happened in {@code GameInput}, and no raw pixel
 * reaches gameplay.
 */
public interface PointerVelocity {

    /** No movement. Used where a release should drop rather than throw. */
    PointerVelocity ZERO = (pointerId, out) -> {
        out[0] = 0f;
        out[1] = 0f;
    };

    /**
     * Writes the current flick velocity for a pointer.
     *
     * @param out a caller-owned array; {@code out[0]} is x, {@code out[1]} is y
     */
    void velocityFor(int pointerId, float[] out);
}
