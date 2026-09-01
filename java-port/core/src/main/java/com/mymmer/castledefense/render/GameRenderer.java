package com.mymmer.castledefense.render;

/**
 * Everything the application drives the screen through.
 *
 * <p>The game class owns the loop and the lifecycle; it never owns GL objects
 * directly. That split is what lets the whole application be started headlessly
 * in a test with a renderer that draws nothing, and it is the seam the real
 * world/UI renderers slot into from Phase 10 onward.
 */
public interface GameRenderer {

    /** Called once after the GL context exists. */
    void create(ViewportSet viewports);

    /** Called after the viewports have been re-laid out. */
    void resize(ViewportSet viewports, int width, int height);

    /**
     * Draws one frame.
     *
     * @param alpha fraction of a simulation step already accumulated, for
     *              interpolation once there is a simulation to interpolate
     */
    void render(ViewportSet viewports, float alpha);

    /** Releases every GL resource this renderer created. */
    void dispose();
}
