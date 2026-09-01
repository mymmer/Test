package com.mymmer.castledefense.render;

import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.utils.viewport.ExtendViewport;
import com.badlogic.gdx.utils.viewport.FitViewport;
import com.badlogic.gdx.utils.viewport.Viewport;
import com.mymmer.castledefense.config.GameConfig;

/**
 * The two viewports the game draws through, and the conversions between them.
 *
 * <p><b>World</b> is a {@link FitViewport} over exactly 1280x720 logical units.
 * Fit (never stretch) is what keeps gameplay geometry honest: a 20:9 phone gets
 * pillarboxes, not a wider battlefield, so no player sees further up the field
 * than another and every constant ported from {@code sprites.py} stays literal.
 *
 * <p><b>UI</b> is an {@link ExtendViewport} with the same minimum size. It keeps
 * 720 units of height and gains width on taller-than-16:9 screens, which is the
 * room the HUD needs to sit clear of cutouts and gesture bars without moving a
 * single world coordinate. Phase 10 anchors the HUD inside the safe area of
 * this viewport.
 *
 * <p>Screen-space arithmetic lives here and nowhere else — gameplay code never
 * sees a physical resolution.
 */
public final class ViewportSet {

    private final OrthographicCamera worldCamera = new OrthographicCamera();
    private final OrthographicCamera uiCamera = new OrthographicCamera();

    private final Viewport world;
    private final Viewport ui;

    /** Scratch vector: unprojecting must not allocate, it happens per touch. */
    private final Vector2 scratch2 = new Vector2();

    private int screenWidth;
    private int screenHeight;

    public ViewportSet() {
        world = new FitViewport(GameConfig.WORLD_WIDTH, GameConfig.WORLD_HEIGHT, worldCamera);
        ui = new ExtendViewport(GameConfig.WORLD_WIDTH, GameConfig.WORLD_HEIGHT, uiCamera);
    }

    /**
     * Re-lays out both viewports for a physical size.
     *
     * <p>libGDX applies a viewport by issuing {@code glViewport}, so this needs a
     * GL binding to exist. The headless test backend installs a stub binding for
     * exactly that reason, which is what lets the letterbox layout be asserted
     * in CI without a window (see {@code GlStub} in the test sources).
     */
    public void resize(int width, int height) {
        screenWidth = width;
        screenHeight = height;
        world.update(width, height, true);
        ui.update(width, height, true);
    }

    /**
     * Converts a screen-space pointer position (origin top-left, the convention
     * every input backend reports in) to world units.
     *
     * <p>Deliberately not {@code Viewport.unproject}: that reads
     * {@code Gdx.graphics.getHeight()} internally, which ties pointer maths to a
     * live graphics backend and makes it untestable headlessly. This viewport
     * already knows the screen size, so the flip is done here. Both cameras are
     * fixed and unzoomed — if one ever moves or zooms, this must go back through
     * {@code camera.unproject}.
     */
    public Vector2 screenToWorld(int screenX, int screenY) {
        return unproject(world, screenX, screenY);
    }

    /** Converts a screen-space pointer position to UI units. */
    public Vector2 screenToUi(int screenX, int screenY) {
        return unproject(ui, screenX, screenY);
    }

    private Vector2 unproject(Viewport vp, int screenX, int screenY) {
        float vw = vp.getScreenWidth();
        float vh = vp.getScreenHeight();
        if (vw <= 0f || vh <= 0f) {
            return scratch2.set(0f, 0f);
        }
        float x = (screenX - vp.getScreenX()) / vw * vp.getWorldWidth();
        // screen y grows downward, world y grows upward
        float flipped = (screenHeight - screenY) - vp.getScreenY();
        float y = flipped / vh * vp.getWorldHeight();
        return scratch2.set(x, y);
    }

    public Viewport getWorld() {
        return world;
    }

    public Viewport getUi() {
        return ui;
    }

    public OrthographicCamera getWorldCamera() {
        return worldCamera;
    }

    public OrthographicCamera getUiCamera() {
        return uiCamera;
    }

    public int getScreenWidth() {
        return screenWidth;
    }

    public int getScreenHeight() {
        return screenHeight;
    }

    /** Width of one world unit in physical pixels, after fitting. */
    public float getWorldScale() {
        return world.getScreenWidth() / GameConfig.WORLD_WIDTH;
    }

    /** Pillarbox/letterbox bar thickness in physical pixels, x then y. */
    public int getLetterboxX() {
        return world.getScreenX();
    }

    public int getLetterboxY() {
        return world.getScreenY();
    }
}
