package com.mymmer.castledefense.render;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.mymmer.castledefense.assets.AnimationState;
import com.mymmer.castledefense.assets.SkinManager;
import com.mymmer.castledefense.assets.UnitVisual;
import com.mymmer.castledefense.assets.VisualId;
import com.mymmer.castledefense.ui.TextLayout;
import com.mymmer.castledefense.config.QualityConfig;

/**
 * What every painter needs, in one object.
 *
 * <p>Passed down rather than injected into each painter's constructor, because
 * the batch and the shape renderer switch passes constantly and a painter that
 * captured them would be holding a reference to something whose state it does
 * not control.
 *
 * <h2>Scratch colours</h2>
 *
 * <p>{@link #c0} and friends exist so a painter can shade a colour without
 * allocating. The source shades constantly — {@code shade(col, 0.5)} for an
 * outline appears in nearly every body — and one {@link Color} per shade per
 * entity per frame is exactly the garbage a phone notices.
 *
 * <h2>It is read-only about gameplay</h2>
 *
 * <p>There is no route from here to anything mutable in the simulation. The
 * randomness is {@link VisualRng}, which wraps only the decoration stream; there
 * is no clock, because animation phases come from gameplay state
 * ({@code enemy.anim()}); and the skin manager is consulted, never written.
 */
public final class RenderContext {

    public final ShapeKit kit;
    public final SpriteBatch batch;
    public final BitmapFont font;
    public final GlyphLayout glyphs = new GlyphLayout();
    public final SkinManager skins;
    public final VisualRng rng;

    /** Scratch colours, for shading without allocating. */
    public final Color c0 = new Color();
    public final Color c1 = new Color();
    public final Color c2 = new Color();
    public final Color c3 = new Color();

    /** The fixed-step interpolation alpha for this frame, in [0, 1]. */
    public float alpha;
    /** Cosmetic seconds, for animation that gameplay has no phase for. */
    public float renderTime;
    /**
     * The simulation clock, in seconds.
     *
     * <p>For cosmetic motion that must nevertheless <b>freeze with the world</b>
     * — the castle's banner ripple is the example. Using {@link #renderTime} for
     * those would leave a flag waving over a paused game. Read-only: nothing
     * here advances it.
     */
    public double worldTime;
    /** Cosmetic detail budget. Never changes anything gameplay can feel. */
    public QualityConfig quality = QualityConfig.HIGH;

    /**
     * The size the built-in font was authored at.
     *
     * <p>A source size is converted through {@link TextLayout#GLYPH} first, so
     * the number reaching {@code setScale} is a libGDX size rather than a pygame
     * one. See the calibration note on {@code TextLayout}.
     */
    private static final float BASE_FONT = 15f;

    /** How far the faux-bold pass is offset. Pygame synthesises bold the same way. */
    private static final float BOLD_OFFSET = 0.7f;

    private void setSize(float sourceSize) {
        font.getData().setScale(TextLayout.glyph(sourceSize) / BASE_FONT);
    }

    public RenderContext(ShapeKit kit, SpriteBatch batch, BitmapFont font,
                         SkinManager skins, VisualRng rng) {
        this.kit = kit;
        this.batch = batch;
        this.font = font;
        this.skins = skins;
        this.rng = rng;
    }

    // ========================================================================
    //  Artwork, with a per-visual fallback
    // ========================================================================

    /**
     * The region for one visual in one state, or null to draw it by hand.
     *
     * <p><b>Per visual, not per skin.</b> A skin that supplies a Scout and a
     * Dragon but no Siege Ram keeps its Scout and its Dragon; only the Ram falls
     * back. Abandoning the whole skin because one file is missing would make
     * every partial skin useless, and partial skins are the normal case while
     * artwork is being made.
     *
     * <p>Missing artwork is a warning, once, from {@link MissingArtLog} — never
     * per frame — and then the procedural body. Malformed <em>gameplay</em> data
     * remains a different and fatal category; that is {@code SkinValidator}'s
     * business, not this method's.
     */
    public TextureRegion artwork(VisualId id, AnimationState state, float seconds) {
        if (skins == null) {
            return null;
        }
        UnitVisual visual = skins.visualFor(id);
        if (visual == null || visual.isProcedural()) {
            return null;
        }
        String region = visual.regionAt(state, seconds);
        if (region == null) {
            MissingArtLog.warnOnce(skins.activeSkinId(), id, state);
            return null;
        }
        TextureRegion tex = regionOf(region);
        if (tex == null) {
            MissingArtLog.warnOnce(skins.activeSkinId(), id, state);
            return null;
        }
        return tex;
    }

    /** Where the atlas lookup actually happens; overridden in tests. */
    private TextureRegion regionOf(String name) {
        return assets == null ? null
                : assets.region(skins.activeAtlasPath(), name);
    }

    private com.mymmer.castledefense.assets.GameAssets assets;

    public RenderContext withAssets(com.mymmer.castledefense.assets.GameAssets a) {
        this.assets = a;
        return this;
    }

    public com.mymmer.castledefense.assets.GameAssets assets() {
        return assets;
    }

    // ========================================================================
    //  Text, in world space
    // ========================================================================

    /** {@code sprites.draw_text} with {@code align="center"} and a shadow. */
    public void textCentered(float cx, float topY, String s, float size, Color c,
                             boolean shadow) {
        textCentered(cx, topY, s, size, c, shadow, false);
    }

    /**
     * {@code sprites.draw_text} with {@code align="center"}.
     *
     * <p>{@code bold} draws a second pass a fraction of a unit to the side,
     * which is how pygame synthesises bold for a font that has no bold face —
     * so a "bold" label here is thickened the same way the source's is, with no
     * second font to bundle or license.
     */
    public void textCentered(float cx, float topY, String s, float size, Color c,
                             boolean shadow, boolean bold) {
        if (s == null || s.isEmpty()) {
            return;
        }
        setSize(size);
        glyphs.setText(font, s);
        float x = cx - glyphs.width / 2f;
        //  Python measures from the top of the glyph box; libGDX draws from the
        //  baseline, so the height is added back to land in the same place.
        float y = topY + glyphs.height;
        if (shadow) {
            font.setColor(0f, 0f, 0f, c.a);
            font.draw(batch, s, x + 2f, y - 2f);        // world y grows upward
            if (bold) {
                font.draw(batch, s, x + 2f + BOLD_OFFSET, y - 2f);
            }
        }
        font.setColor(c);
        font.draw(batch, s, x, y);
        if (bold) {
            font.draw(batch, s, x + BOLD_OFFSET, y);
        }
        font.getData().setScale(1f);
    }

    public void textLeft(float x, float topY, String s, float size, Color c) {
        if (s == null || s.isEmpty()) {
            return;
        }
        setSize(size);
        glyphs.setText(font, s);
        font.setColor(0f, 0f, 0f, c.a);
        font.draw(batch, s, x + 2f, topY + glyphs.height - 2f);
        font.setColor(c);
        font.draw(batch, s, x, topY + glyphs.height);
        font.getData().setScale(1f);
    }

    // ========================================================================
    //  Shading shorthands
    // ========================================================================

    public Color shade(Color base, float factor) {
        return Palette.shade(base, factor, c0);
    }

    public Color shade2(Color base, float factor) {
        return Palette.shade(base, factor, c1);
    }

    public Color mix(Color a, Color b, float t) {
        return Palette.mix(a, b, t, c2);
    }

    public Color alpha(Color base, float a) {
        return Palette.alpha(base, a, c3);
    }
}
