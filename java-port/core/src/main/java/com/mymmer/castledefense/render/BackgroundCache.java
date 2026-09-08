package com.mymmer.castledefense.render;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.mymmer.castledefense.config.GameConfig;

/**
 * The sky, moon, stars, hills and ground, drawn once into a texture.
 *
 * <h2>Measured — and nearly thrown away by a bad measurement</h2>
 *
 * <p>Phase 12's profile counted shape primitives per layer, per frame:
 *
 * <pre>
 *   background               1188      27% of the frame's primitives
 *   enemies                  2326
 *   castle + spikes           419
 *   towers + barricade        353
 * </pre>
 *
 * <p>The background is the same gradient, the same 140 stars from a fixed seed,
 * the same three sine ridges and the same 260 tufts of grass, rebuilt from
 * scratch sixty times a second. Cached, the whole layer is one textured quad.
 *
 * <p>The first A/B ran on the {@code endless-late} scenario and came back
 * inconclusive — 693 us against 672 us — so the cache was deleted. That was
 * wrong, and why is worth recording: <b>{@code endless-late} keeps simulating
 * while it is measured</b>, so its entity count drifts and its frame times swing
 * by 40%. Repeating the A/B on {@code mixed-wave}, whose roster is fixed, gave a
 * clean answer:
 *
 * <pre>
 *   with the cache     p50 541 us    (5 runs, +/-5%)
 *   without            p50 598 us    (5 runs, +/-3%)
 * </pre>
 *
 * <p>A reproducible 10% saving. The lesson is recorded in
 * {@code PORT_ANALYSIS.md} 14.9: a benchmark whose workload moves cannot resolve
 * a 10% change, and will state confidently that there isn't one.
 *
 * <p>Phase 11 predicted the castle's brickwork would be the first thing worth
 * caching. Measurement put it at a third of the background's cost.
 *
 * <h2>When it is rebuilt</h2>
 *
 * <p>Its contents depend on nothing but the world's fixed dimensions, so the only
 * reasons to rebuild are structural:
 *
 * <ul>
 *   <li><b>First use</b> — there is no texture yet.</li>
 *   <li><b>GL context loss</b> — Android discards the texture when the app is
 *       backgrounded. libGDX restores the {@link FrameBuffer} object but not its
 *       contents, so it is redrawn; {@link #invalidate()} is called from the
 *       game's {@code resume}.</li>
 * </ul>
 *
 * <p>It deliberately does <b>not</b> depend on the castle's tier, its damage, the
 * quality preset or the active skin — none of those appears in it. The castle is
 * a separate layer drawn live on top, so upgrading a wall or cracking it changes
 * the picture without touching this. That is exactly why this layer is cacheable
 * and that one is not.
 *
 * <h2>If it cannot be built</h2>
 *
 * <p>A device that refuses the framebuffer gets {@link #isReady()} false for ever
 * and the renderer draws the background the long way: slower, identical, and no
 * crash — the right failure for a cache.
 */
public final class BackgroundCache {

    private FrameBuffer buffer;
    private TextureRegion region;
    private boolean dirty = true;

    /**
     * Off when {@code -Dcastledefense.noBgCache=true}.
     *
     * <p>Kept deliberately: it is the switch the A/B above was run with, so the
     * measurement can be reproduced rather than taken on trust. It is also what
     * to reach for if a device ever renders the cached background wrongly.
     */
    private boolean unavailable = Boolean.getBoolean("castledefense.noBgCache");

    private final OrthographicCamera camera = new OrthographicCamera();

    /** Marks the cache stale. Called on resume, when a context may have been lost. */
    public void invalidate() {
        dirty = true;
    }

    public boolean isReady() {
        return region != null && !dirty && !unavailable;
    }

    /**
     * Rebuilds the texture if needed, using the given painter.
     *
     * <p>Binds its own camera at the world's size, so what is captured is exactly
     * the world rectangle whatever shape the window is; the viewport then
     * stretches it like any other world content.
     */
    public void ensure(RenderContext ctx, WorldPainters painters) {
        if (unavailable || !dirty) {
            return;
        }
        int w = (int) GameConfig.WORLD_WIDTH;
        int h = (int) GameConfig.WORLD_HEIGHT;
        try {
            if (buffer == null) {
                buffer = new FrameBuffer(Pixmap.Format.RGB888, w, h, false);
                buffer.getColorBufferTexture().setFilter(
                        Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
                region = new TextureRegion(buffer.getColorBufferTexture());
                //  A framebuffer's texture is stored the other way up from how it
                //  will be drawn, so it is flipped once here, not per frame.
                region.flip(false, true);
            }
            camera.setToOrtho(false, w, h);
            camera.update();

            buffer.begin();
            Gdx.gl.glClearColor(0f, 0f, 0f, 1f);
            Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
            ctx.kit.renderer().setProjectionMatrix(camera.combined);
            ctx.kit.fillBegin();
            painters.paintBackground(ctx);
            ctx.kit.end();
            buffer.end();

            dirty = false;
        } catch (RuntimeException e) {
            unavailable = true;
            dispose();
            if (Gdx.app != null) {
                Gdx.app.log("CastleDefense", "background cache unavailable ("
                        + e.getClass().getSimpleName() + "); drawing it live");
            }
        }
    }

    /** Draws the cached background. Only valid when {@link #isReady()}. */
    public void draw(SpriteBatch batch) {
        if (region == null) {
            return;
        }
        batch.draw(region, 0f, 0f, GameConfig.WORLD_WIDTH, GameConfig.WORLD_HEIGHT);
    }

    public void dispose() {
        if (buffer != null) {
            buffer.dispose();
            buffer = null;
        }
        region = null;
        dirty = true;
    }
}
