package com.mymmer.castledefense.input;

/**
 * Gameplay's side of the pointer pipeline: what happens when a press reaches the
 * world rather than the UI.
 *
 * <p>An interface so Phase 4 can prove ownership and consumption rules with a
 * plain test fixture, while the real implementation — grabbing, armour
 * stripping, boss regalia, tower overcharge — arrives with those mechanics in
 * Phases 5 to 7. Nothing about the routing changes when it does.
 */
public interface WorldInteractionHandler {

    /**
     * A press the UI did not claim.
     *
     * @return true to begin a world interaction, which then belongs to this
     *         pointer until it is released or cancelled
     */
    boolean onWorldPress(Pointer pointer);

    /** The owning pointer moved. */
    default void onWorldDrag(Pointer pointer) {
    }

    /** The owning pointer was released normally. */
    default void onWorldRelease(Pointer pointer) {
    }

    /**
     * The owning pointer was cancelled by the platform.
     *
     * <p>Distinct from a release because the two can differ: a released grab
     * throws the mob with the tracked velocity, whereas a cancelled one should
     * simply drop it.
     */
    default void onWorldCancel(Pointer pointer) {
    }
}
