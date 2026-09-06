package com.mymmer.castledefense.render;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.utils.Array;
import com.mymmer.castledefense.input.GameInput;
import com.mymmer.castledefense.input.Pointer;
import com.mymmer.castledefense.ui.SafeArea;
import com.mymmer.castledefense.ui.TouchTargets;
import com.mymmer.castledefense.ui.UiRect;
import com.mymmer.castledefense.ui.UiRoot;

/**
 * Draws the things that are normally invisible and normally wrong.
 *
 * <p>Every layout bug in this phase is a discrepancy between two rectangles that
 * cannot both be seen: what a control looks like against what it accepts a press
 * inside, the safe area against the viewport, where the finger is against which
 * control thinks it owns it. This overlay draws all of them at once, which turns
 * "the button is hard to hit on my phone" into a picture.
 *
 * <pre>
 *   green     visual bounds  -- what the renderer paints
 *   cyan      hit bounds     -- what the router tests, often larger
 *   yellow    the safe area  -- inside the cutouts and the gesture bar
 *   magenta   the UI viewport edge
 *   red       a control whose touch box is under the minimum, or overlapping
 *   white dot the live pointer, in UI units
 * </pre>
 *
 * <p>Off by default, toggled with {@code --ui-debug} on desktop. It reads state
 * and draws; it never changes a layout, so what it shows is what would have
 * happened without it.
 */
public final class UiDebugOverlay {

    private static final Color VISUAL = new Color(0.30f, 0.85f, 0.40f, 1f);
    private static final Color HIT = new Color(0.30f, 0.80f, 0.95f, 0.85f);
    private static final Color SAFE = new Color(0.97f, 0.79f, 0.31f, 1f);
    private static final Color VIEWPORT = new Color(0.85f, 0.30f, 0.85f, 1f);
    private static final Color BAD = new Color(0.95f, 0.25f, 0.20f, 1f);
    private static final Color INK = new Color(1f, 1f, 1f, 1f);

    private final UiRoot ui;
    private final GameInput input;
    /** The world renderer, for the rendering numbers. May be null. */
    private WorldRenderer world;

    /**
     * Attaches the world renderer, so the overlay can report on it.
     *
     * <p>Optional: the overlay works without one and simply omits that half.
     * Phase 12 is what these numbers are for -- they are the input to the mobile
     * budget, not a profiler.
     */
    public void setWorldRenderer(WorldRenderer world) {
        this.world = world;
    }

    private ShapeRenderer shapes;
    private SpriteBatch batch;
    private BitmapFont font;
    private boolean enabled;

    public UiDebugOverlay(UiRoot ui, GameInput input) {
        this.ui = ui;
        this.input = input;
    }

    public void create() {
        shapes = new ShapeRenderer();
        batch = new SpriteBatch();
        font = new BitmapFont();
    }

    public void dispose() {
        if (shapes != null) {
            shapes.dispose();
        }
        if (batch != null) {
            batch.dispose();
        }
        if (font != null) {
            font.dispose();
        }
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void toggle() {
        enabled = !enabled;
    }

    public void render(ViewportSet viewports) {
        if (!enabled || shapes == null) {
            return;
        }
        viewports.getUi().apply();
        shapes.setProjectionMatrix(viewports.getUiCamera().combined);
        batch.setProjectionMatrix(viewports.getUiCamera().combined);

        Array<UiRect> controls = ui.allControls();
        Array<String> clashes = TouchTargets.overlaps(controls);

        shapes.begin(ShapeRenderer.ShapeType.Line);

        //  the two viewports' own rectangles
        shapes.setColor(VIEWPORT);
        shapes.rect(0.5f, 0.5f, viewports.getUi().getWorldWidth() - 1f,
                viewports.getUi().getWorldHeight() - 1f);

        SafeArea safe = ui.safeArea();
        shapes.setColor(SAFE);
        shapes.rect(safe.x, safe.y, safe.width, safe.height);

        for (int i = 0; i < controls.size; i++) {
            UiRect c = controls.get(i);
            if (!c.visible()) {
                continue;
            }
            boolean bad = !TouchTargets.meetsMinimum(c) || !safe.contains(c)
                    || mentions(clashes, c.id);
            shapes.setColor(bad ? BAD : HIT);
            shapes.rect(c.hitX(), c.hitY(), c.hitWidth(), c.hitHeight());
            shapes.setColor(bad ? BAD : VISUAL);
            shapes.rect(c.visualX(), c.visualY(), c.visualWidth(), c.visualHeight());
        }

        //  every live pointer, where the UI thinks it is
        if (input != null) {
            for (int id = 0; id < GameInput.MAX_POINTERS; id++) {
                Pointer p = input.pointer(id);
                if (p == null || !p.isDown()) {
                    continue;
                }
                shapes.setColor(p.isConsumedByUi() ? VISUAL : VIEWPORT);
                shapes.circle(p.uiX(), p.uiY(), 14f, 16);
            }
        }
        shapes.end();

        batch.begin();
        font.setColor(INK);
        float y = safe.top() - 6f;
        font.draw(batch, "ui debug -- safe " + fmt(safe.x) + "," + fmt(safe.y)
                + " " + fmt(safe.width) + "x" + fmt(safe.height)
                + (safe.isFull() ? " (full)" : " (inset)"), safe.x + 6f, y);
        font.draw(batch, "controls " + controls.size
                + "   overlaps " + clashes.size
                + "   pressed " + (ui.lastPressed() == null ? "-" : ui.lastPressed()),
                safe.x + 6f, y - 16f);
        font.draw(batch, "owner " + ownerName(), safe.x + 6f, y - 32f);
        if (world != null) {
            //  Deliberately a handful of counters, not an instrumentation
            //  framework: enough to answer "what is expensive right now", which
            //  is the question Phase 12 will start from.
            float fps = com.badlogic.gdx.Gdx.graphics == null ? 0f
                    : com.badlogic.gdx.Gdx.graphics.getFramesPerSecond();
            float frameMs = com.badlogic.gdx.Gdx.graphics == null ? 0f
                    : com.badlogic.gdx.Gdx.graphics.getDeltaTime() * 1000f;
            font.draw(batch, String.format(java.util.Locale.ROOT,
                    "fps %.0f  frame %.1fms  alpha %.2f  quality %s",
                    fps, frameMs, world.alpha(), world.quality()),
                    safe.x + 6f, y - 48f);
            EffectsSystem fx = world.effects();
            font.draw(batch, "enemies " + world.drawnEnemies()
                    + "   projectiles " + world.drawnProjectiles()
                    + "   particles " + (fx == null ? 0 : fx.particleCount())
                    + "/" + (fx == null ? 0 : fx.capacity())
                    + "   texts " + (fx == null ? 0 : fx.textCount()),
                    safe.x + 6f, y - 64f);
            font.draw(batch, "skin " + world.skinId()
                    + "   atlas " + world.atlasPath()
                    + "   missing art " + MissingArtLog.reportedCount()
                    + "   trails " + world.trails().tracked()
                    + "   interp " + world.interpolator().tracked(),
                    safe.x + 6f, y - 80f);
            font.draw(batch, String.format(java.util.Locale.ROOT,
                    "shake %.1f, %.1f", world.shakeX(), world.shakeY()),
                    safe.x + 6f, y - 96f);
        }
        if (clashes.size > 0) {
            font.setColor(BAD);
            font.draw(batch, clashes.toString(", "), safe.x + 6f, y - 48f);
        }
        batch.end();
    }

    private String ownerName() {
        if (input == null) {
            return "-";
        }
        for (int id = 0; id < GameInput.MAX_POINTERS; id++) {
            Pointer p = input.pointer(id);
            if (p != null && p.isDown() && p.consumedBy() != null) {
                return "ui(" + p.id() + ")";
            }
        }
        return input.hasInteraction() ? "world(" + input.interactionOwner() + ")" : "-";
    }

    private static boolean mentions(Array<String> clashes, String id) {
        for (int i = 0; i < clashes.size; i++) {
            if (clashes.get(i).contains(id)) {
                return true;
            }
        }
        return false;
    }

    private static String fmt(float v) {
        return String.format(java.util.Locale.ROOT, "%.0f", v);
    }
}
