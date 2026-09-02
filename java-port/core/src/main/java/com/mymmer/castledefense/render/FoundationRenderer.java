package com.mymmer.castledefense.render;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.mymmer.castledefense.config.GameConfig;

/**
 * Phase 2 scaffolding: draws the world bounds so the viewport work can be seen.
 *
 * <p>This is <em>not</em> the game's renderer. It exists so the desktop and
 * Android launchers have something that proves the plumbing — that the world is
 * exactly 1280x720, that it letterboxes rather than stretches, and that the UI
 * viewport is a separate space that grows on tall screens. Phase 10/11 replace
 * it with {@code WorldRenderer} + {@code UiRenderer}, and this class is deleted.
 *
 * <p>It draws no game content and reads no game state on purpose.
 */
public final class FoundationRenderer implements GameRenderer {

    private static final Color BACKGROUND = new Color(0.024f, 0.031f, 0.063f, 1f);
    private static final Color WORLD_EDGE = new Color(0.34f, 0.37f, 0.50f, 1f);
    private static final Color GROUND_LINE = new Color(0.23f, 0.29f, 0.18f, 1f);
    private static final Color UI_EDGE = new Color(0.97f, 0.79f, 0.31f, 1f);

    private ShapeRenderer shapes;
    private SpriteBatch batch;
    private BitmapFont font;

    @Override
    public void create(ViewportSet viewports) {
        shapes = new ShapeRenderer();
        batch = new SpriteBatch();
        font = new BitmapFont();          // libGDX's built-in font: no asset needed
        font.setColor(Color.WHITE);
    }

    @Override
    public void resize(ViewportSet viewports, int width, int height) {
        // nothing cached per size yet
    }

    @Override
    public void render(ViewportSet viewports, float alpha) {
        Gdx.gl.glClearColor(BACKGROUND.r, BACKGROUND.g, BACKGROUND.b, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);

        // --- world space: the 1280x720 rectangle every gameplay coordinate lives in
        viewports.getWorld().apply();
        shapes.setProjectionMatrix(viewports.getWorldCamera().combined);
        shapes.begin(ShapeRenderer.ShapeType.Line);
        shapes.setColor(WORLD_EDGE);
        shapes.rect(1f, 1f, GameConfig.WORLD_WIDTH - 2f, GameConfig.WORLD_HEIGHT - 2f);
        // the ground line and the castle front, so the world's orientation is
        // obvious at a glance -- both are Python constants (GROUND_Y=620 is
        // measured from the top in pygame, so it is 720-620 from the bottom)
        shapes.setColor(GROUND_LINE);
        shapes.line(0f, GameConfig.WORLD_HEIGHT - 620f,
                GameConfig.WORLD_WIDTH, GameConfig.WORLD_HEIGHT - 620f);
        shapes.line(252f, 0f, 252f, GameConfig.WORLD_HEIGHT);
        shapes.end();

        // --- ui space: a separate rectangle that grows on tall screens
        viewports.getUi().apply();
        shapes.setProjectionMatrix(viewports.getUiCamera().combined);
        shapes.begin(ShapeRenderer.ShapeType.Line);
        shapes.setColor(UI_EDGE);
        float uiW = viewports.getUi().getWorldWidth();
        float uiH = viewports.getUi().getWorldHeight();
        shapes.rect(4f, 4f, uiW - 8f, uiH - 8f);
        shapes.end();

        batch.setProjectionMatrix(viewports.getUiCamera().combined);
        batch.begin();
        font.draw(batch, GameConfig.TITLE + " -- Java/libGDX port, Phase 4 foundation",
                18f, uiH - 18f);
        font.draw(batch, String.format(
                "world %.0fx%.0f (fit)   ui %.0fx%.0f (extend)   screen %dx%d   letterbox %d,%d",
                GameConfig.WORLD_WIDTH, GameConfig.WORLD_HEIGHT, uiW, uiH,
                viewports.getScreenWidth(), viewports.getScreenHeight(),
                viewports.getLetterboxX(), viewports.getLetterboxY()), 18f, uiH - 40f);
        // alpha is the leftover fraction of a simulation step: it proves the
        // fixed-step accumulator is driving this frame rather than the frame
        // driving the world.  Phase 11 replaces this whole renderer.
        font.draw(batch, String.format("fps %d   step alpha %.2f",
                Gdx.graphics.getFramesPerSecond(), alpha), 18f, uiH - 62f);
        batch.end();
    }

    @Override
    public void dispose() {
        if (shapes != null) {
            shapes.dispose();
            shapes = null;
        }
        if (batch != null) {
            batch.dispose();
            batch = null;
        }
        if (font != null) {
            font.dispose();
            font = null;
        }
    }
}
