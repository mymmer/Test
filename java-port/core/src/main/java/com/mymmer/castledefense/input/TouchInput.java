package com.mymmer.castledefense.input;

import com.mymmer.castledefense.render.ViewportSet;

/**
 * Android input: real multitouch on the same logical model as the mouse.
 *
 * <p>Also thin, and for the same reason. What it adds is defensive multitouch
 * handling: the game expects one manipulation at a time, but a phone will
 * happily deliver five, and a second thumb must never disturb the first.
 * {@link GameInput}'s ownership rules do the work; this class documents the
 * mobile-specific lifecycle and offers the wholesale cancel that an app
 * backgrounding needs.
 */
public final class TouchInput extends GameInput {

    public TouchInput(ViewportSet viewports) {
        super(viewports);
    }

    /**
     * The app is going to the background.
     *
     * <p>Android delivers no touch-up when the activity loses focus, so every
     * pointer is cancelled here. Without it, returning to a paused game would
     * find a mob still glued to a finger that is no longer on the screen.
     */
    public void onAppPaused() {
        cancelAll();
    }
}
