package com.mymmer.castledefense.input;

/**
 * Builds a {@link Pointer} at a world position, for tests outside this package.
 *
 * <p>{@code Pointer} is final with a package-private constructor and
 * package-private mutators, deliberately: only {@code GameInput} may write one.
 * A test of the interaction layer still needs one, and the alternatives are
 * worse — driving {@code GameInput.touchDown} needs a live graphics backend for
 * unprojection, and loosening {@code Pointer}'s visibility would open it to
 * gameplay code for ever.
 *
 * <p>So this lives in the same package, on the test source set only, and is the
 * one door in.
 */
public final class TestPointers {

    private TestPointers() {
    }

    /** A pointer that is down at a world position, as if just pressed. */
    public static Pointer at(int id, float worldX, float worldY) {
        Pointer p = new Pointer(id);
        p.press(worldX, worldY, worldX, worldY);
        return p;
    }

    /** Moves an existing pointer, so a drag delta is meaningful. */
    public static void moveTo(Pointer p, float worldX, float worldY) {
        p.move(worldX, worldY, worldX, worldY);
    }
}
