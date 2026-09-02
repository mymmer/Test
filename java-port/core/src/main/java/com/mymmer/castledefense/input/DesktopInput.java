package com.mymmer.castledefense.input;

import com.mymmer.castledefense.render.ViewportSet;

/**
 * Desktop input: a mouse pretending to be one finger.
 *
 * <p>Thin on purpose. It adds no gameplay path of its own — the whole point is
 * that desktop and Android drive the same {@link GameInput}, so a mechanic
 * tested with a mouse behaves identically under a thumb. All it contributes is
 * the focus-loss guard.
 *
 * <p>That guard exists in the Python too: {@code Game.run()} checks every frame
 * whether the button is still physically down and releases the grab if it is
 * not, because alt-tabbing away swallows the button-up event and would otherwise
 * leave the player holding a mob forever.
 */
public final class DesktopInput extends GameInput {

    /** A mouse is always pointer 0. */
    public static final int MOUSE_POINTER = 0;

    public DesktopInput(ViewportSet viewports) {
        super(viewports);
    }

    /**
     * Reconciles our state with the physical button.
     *
     * @param buttonPhysicallyDown what the platform reports right now
     */
    public void syncMouseButton(boolean buttonPhysicallyDown) {
        Pointer p = pointer(MOUSE_POINTER);
        if (p != null && p.isDown() && !buttonPhysicallyDown) {
            cancelPointer(MOUSE_POINTER);
        }
    }

    /** Window focus lost: drop everything rather than stranding an interaction. */
    public void onFocusLost() {
        cancelAll();
    }
}
