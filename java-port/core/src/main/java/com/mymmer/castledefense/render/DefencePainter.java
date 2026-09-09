package com.mymmer.castledefense.render;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.RandomXS128;
import com.mymmer.castledefense.assets.VisualId;
import com.mymmer.castledefense.config.GameConfig;
import com.mymmer.castledefense.defence.Barricade;
import com.mymmer.castledefense.defence.Castle;
import com.mymmer.castledefense.defence.Outpost;
import com.mymmer.castledefense.defence.SpikeWalls;
import com.mymmer.castledefense.defence.DefenceTower;
import com.mymmer.castledefense.defence.TowerType;

/**
 * The castle, its emplacements, and the two structures out in the field.
 *
 * <h2>The castle is its wall level</h2>
 *
 * <p>{@code Castle.TIERS} pairs a name with a wall colour, a mortar colour, a
 * trim colour and a health pool, and the source rebuilds the whole keep from
 * whichever tier is current. Reinforcing the wall is therefore a visible change,
 * not just a bigger number — brickwork gets larger blocks at level 2, a corner
 * turret appears at 3, a portcullis at 4, buttresses at 5, glowing runes at 6.
 * All six are here.
 *
 * <p>The source caches the built keep into a surface and blits it. This draws it
 * directly instead: the whole thing is flat shapes, so there is nothing to cache
 * <em>into</em> without allocating a framebuffer, and a framebuffer is a
 * lifecycle problem Phase 11 does not need. The brickwork loop is the one place
 * in this renderer with a real per-frame cost, and it is noted in
 * {@code RENDERING.md} as the first thing Phase 12 should look at.
 *
 * <h2>Cracks stay where they are</h2>
 *
 * <p>Below 66% health the source seeds the global generator with 1337, draws the
 * cracks, and unseeds — so the cracks are in fixed places, and drawing them
 * perturbs gameplay randomness. Here they come from
 * {@link VisualRng#stable(long)}, which gives the same fixed pattern without
 * touching any shared stream.
 */
public final class DefencePainter {

    // --- Castle.TIERS: wall, mortar, trim -------------------------------------
    private static final Color[][] TIERS = {
        {Palette.rgb(122, 86, 52),  Palette.rgb(92, 62, 36),  Palette.rgb(156, 116, 70)},
        {Palette.rgb(132, 132, 140), Palette.rgb(98, 98, 106), Palette.rgb(168, 168, 178)},
        {Palette.rgb(112, 120, 132), Palette.rgb(82, 88, 98),  Palette.rgb(150, 160, 174)},
        {Palette.rgb(94, 100, 116),  Palette.rgb(62, 66, 78),  Palette.rgb(176, 182, 198)},
        {Palette.rgb(72, 78, 96),    Palette.rgb(48, 52, 64),  Palette.rgb(204, 178, 108)},
        {Palette.rgb(52, 50, 70),    Palette.rgb(34, 32, 48),  Palette.rgb(150, 210, 245)},
    };

    /** Source geometry, in pygame's downward-y; converted at the point of use. */
    private static final float FRONT = GameConfig.CASTLE_FRONT;
    private static final float KEEP_RIGHT = 132f;
    private static final float WALL_TOP_Y = up(350f);      // WALL_TOP
    private static final float KEEP_TOP_Y = up(230f);      // KEEP_TOP
    private static final float GROUND = up(620f);          // GROUND_Y

    private final float[] poly = new float[12];
    /** Own storage for a shaded base, so it cannot alias the scratch. */
    private final Color keepColour = new Color();
    private final Color turretColour = new Color();

    /** Pygame y (down from the top) to draw y. See {@link WorldGeometry}. */
    private static float up(float pygameY) {
        return WorldGeometry.toDrawY(pygameY);
    }

    // ========================================================================
    //  The castle
    // ========================================================================

    /** Assumes a filled pass is open. Paints spikes and towers too, as the source does. */
    public void paintCastle(RenderContext ctx, Castle castle, SpikeWalls spikes) {
        int lvl = MathUtils.clamp(castle.wallLevel(), 1, TIERS.length);
        Color wall = TIERS[lvl - 1][0];
        Color mortar = TIERS[lvl - 1][1];
        Color trim = TIERS[lvl - 1][2];

        //  curtain wall, then the keep in front of it
        brickwork(ctx, 0f, GROUND, FRONT, WALL_TOP_Y - GROUND, wall, mortar,
                lvl <= 1 ? 22f : 30f, lvl <= 1 ? 14f : 20f);
        //  Materialised into its own Color first.  ctx.shade() hands back a
        //  SHARED scratch instance, and brickwork() calls ctx.shade() again for
        //  every block -- so passing the scratch as `base` meant each block
        //  darkened the base for the next one, and the keep came out nearly
        //  black.  Found by the Phase 11.5 image comparison.
        keepColour.set(ctx.shade(wall, 1.06f));
        brickwork(ctx, 0f, GROUND, KEEP_RIGHT, KEEP_TOP_Y - GROUND,
                keepColour, mortar, 26f, 18f);

        merlons(ctx, 0f, FRONT, WALL_TOP_Y, 18f, 12f, 18f, wall, mortar);
        merlons(ctx, 0f, KEEP_RIGHT, KEEP_TOP_Y, 18f, 12f, 18f, wall, mortar);

        //  walkway trim
        ctx.kit.rect(0f, WALL_TOP_Y - 4f, FRONT, 4f, ctx.shade(trim, 0.8f));
        ctx.kit.rect(0f, KEEP_TOP_Y - 4f, KEEP_RIGHT, 4f, ctx.shade(trim, 0.8f));
        ctx.kit.rect(FRONT - 6f, GROUND, 6f, WALL_TOP_Y - GROUND,
                ctx.shade(trim, 0.7f));

        gate(ctx, lvl, mortar, trim);
        windows(ctx, lvl);
        flourishes(ctx, lvl, wall, mortar, trim);
        cracks(ctx, castle);
        banner(ctx, castle, trim);

        if (spikes != null) {
            paintSpikes(ctx, spikes);
        }
    }

    /**
     * Offset masonry with a per-block tint.
     *
     * <p>The tint is {@code 0.86 + 0.28 * ((x*7 + y*13) % 5)/5} — a hash of the
     * block's own position, not a random draw. That is why the wall does not
     * shimmer between frames, and it is reproduced rather than replaced with
     * randomness for exactly that reason.
     */
    private void brickwork(RenderContext ctx, float x, float bottom, float w,
                           float h, Color base, Color mortar, float blockW,
                           float blockH) {
        ctx.kit.rect(x, bottom, w, h, mortar);
        int row = 0;
        for (float y = bottom; y < bottom + h; y += blockH) {
            float offset = (row % 2 == 0) ? 0f : blockW / 2f;
            for (float bx = x - offset; bx < x + w; bx += blockW) {
                float bw = Math.min(blockW - 3f, x + w - bx - 2f);
                float bh = Math.min(blockH - 3f, bottom + h - y - 2f);
                if (bw > 2f && bh > 2f && bx + 2f >= x) {
                    int hash = (int) ((bx * 7f + y * 13f)) % 5;
                    if (hash < 0) {
                        hash += 5;
                    }
                    float tint = 0.86f + 0.28f * hash / 5f;
                    ctx.kit.rect(bx + 2f, y + 2f, bw, bh, ctx.shade(base, tint));
                }
            }
            row++;
        }
    }

    private void merlons(RenderContext ctx, float x0, float x1, float top,
                         float w, float gap, float h, Color wall, Color mortar) {
        for (float x = x0; x < x1; x += w + gap) {
            float bw = Math.min(w, x1 - x);
            ctx.kit.rect(x, top, bw, h, ctx.shade(wall, 1.12f));
            ctx.kit.rectOutline(x, top, bw, h, 1f, mortar);
        }
    }

    private void gate(RenderContext ctx, int lvl, Color mortar, Color trim) {
        float gx = FRONT - 78f;
        float gw = 62f;
        float gh = 108f;
        float gy = GROUND;
        //  Only the top corners are rounded in the source; roundRect rounds all
        //  four, and at this radius against the ground line the difference is
        //  not visible -- the bottom corners sit on the dirt.
        ctx.kit.roundRect(gx, gy, gw, gh, 28f, ctx.shade(mortar, 0.7f));
        ctx.kit.roundRect(gx + 4f, gy, gw - 8f, gh, 24f, Palette.rgb(96, 68, 40));
        for (int i = 1; i < 5; i++) {
            float y = gy + gh - 22f - i * 18f;
            ctx.kit.line(gx + 6f, y, gx + gw - 6f, y, 2f, ctx.shade(trim, 0.8f));
        }
        if (lvl >= 4) {
            for (int i = 0; i < 5; i++) {
                float bx = gx + 10f + i * 11f;
                ctx.kit.line(bx, gy + gh - 16f, bx, gy + 4f, 2f,
                        Palette.rgb(170, 176, 190));           // portcullis
            }
        }
    }

    private void windows(RenderContext ctx, int lvl) {
        for (int i = 0; i < 3; i++) {
            float wx = 22f + i * 36f;
            float wy = KEEP_TOP_Y - 42f - 26f;
            ctx.kit.roundRect(wx, wy, 10f, 26f, 4f, Palette.rgb(28, 26, 36));
            if (lvl >= 2) {
                ctx.kit.roundRect(wx + 2f, wy + 4f, 6f, 18f, 3f,
                        Palette.rgb(250, 214, 140));           // lit from within
            }
        }
    }

    private void flourishes(RenderContext ctx, int lvl, Color wall, Color mortar,
                            Color trim) {
        if (lvl >= 3) {
            float tx = FRONT - 46f;
            float ty = WALL_TOP_Y;
            turretColour.set(ctx.shade(wall, 1.1f));
            brickwork(ctx, tx, ty, 44f, 66f, turretColour, mortar, 20f, 16f);
            merlons(ctx, tx, tx + 44f, ty + 66f, 12f, 8f, 12f, wall, mortar);
            ctx.kit.rect(tx, ty + 62f, 44f, 4f, trim);
        }
        if (lvl >= 5) {
            for (float bx : new float[] {46f, 150f}) {
                poly[0] = bx;         poly[1] = GROUND;
                poly[2] = bx + 26f;   poly[3] = GROUND;
                poly[4] = bx + 18f;   poly[5] = GROUND + 90f;
                poly[6] = bx + 8f;    poly[7] = GROUND + 90f;
                ctx.kit.polygon(poly, 8, ctx.shade(wall, 0.82f));  // buttresses
            }
        }
        if (lvl >= 6) {
            for (int i = 0; i < 6; i++) {
                float rx = 16f + i * 38f;
                float ry = WALL_TOP_Y - 70f;
                ctx.kit.circle(rx, ry, 5f, Palette.rgb(120, 200, 250));
                ctx.kit.circle(rx, ry, 2f, Palette.rgb(200, 240, 255));  // runes
            }
        }
    }

    /**
     * Cracks below two thirds health, in fixed places.
     *
     * <p>{@code random.seed(1337)} in the source. Here the same fixed pattern
     * comes from a private generator, so a frame drawn is a frame that changed
     * nothing.
     */
    private void cracks(RenderContext ctx, Castle castle) {
        float frac = castle.hp() / Math.max(1f, castle.maxHp());
        if (frac >= 0.66f) {
            return;
        }
        RandomXS128 r = ctx.rng.stable(VisualRng.CRACK_SEED);
        int count = (int) ((0.66f - frac) * 26f);
        for (int i = 0; i < count; i++) {
            float cx = VisualRng.range(r, 6, (int) FRONT - 10);
            float cy = up(VisualRng.range(r, (int) 350f + 12, (int) 620f - 12));
            float px = cx;
            float py = cy;
            for (int k = 0; k < 3; k++) {
                float nx = px + VisualRng.range(r, -12, 12);
                float ny = py - VisualRng.range(r, 2, 14);
                ctx.kit.line(px, py, nx, ny, 2f, Palette.CRACK);
                px = nx;
                py = ny;
            }
        }
    }

    private void banner(RenderContext ctx, Castle castle, Color trim) {
        float poleX = KEEP_RIGHT - 16f;
        ctx.kit.line(poleX, KEEP_TOP_Y + 18f, poleX, KEEP_TOP_Y + 78f, 3f,
                Palette.rgb(200, 196, 186));
        //  The banner ripples on the SIMULATION clock, not the render clock,
        //  so it stops when the world does -- as the source's does, its phase
        //  being advanced in Castle.update.  A render-time ripple would leave a
        //  flag waving over a paused game.
        float phase = (float) ctx.worldTime * 2f;
        for (int i = 0; i < 4; i++) {
            float t0 = i / 4f;
            float t1 = (i + 1) / 4f;
            float x0 = poleX + 4f + t0 * 34f;
            float x1 = poleX + 4f + t1 * 34f;
            float y0 = KEEP_TOP_Y + 74f - MathUtils.sin(phase + t0 * 3f) * 4f - t0 * 3f;
            float y1 = KEEP_TOP_Y + 74f - MathUtils.sin(phase + t1 * 3f) * 4f - t1 * 3f;
            ctx.kit.quadFill(x0, y0, x1, y1, x1, y1 - 16f, x0, y0 - 16f, trim);
        }
    }

    /**
     * {@code SpikeWalls.draw}: four spikes per row, one row per level.
     *
     * <p>Purely a read of {@code level} — the damage they reflect is gameplay's.
     */
    public void paintSpikes(RenderContext ctx, SpikeWalls spikes) {
        Color face = Palette.rgb(176, 182, 198);
        Color edge = Palette.rgb(96, 102, 116);
        for (int row = 0; row < spikes.level(); row++) {
            float yy = up(350f + 26f + row * 34f);
            for (int k = 0; k < 4; k++) {
                float ty = yy - k * 8f;
                ctx.kit.triangle(FRONT - 2f, ty + 4f, FRONT + 18f, ty,
                        FRONT - 2f, ty - 4f, face);
                ctx.kit.line(FRONT - 2f, ty + 4f, FRONT + 18f, ty, 1f, edge);
                ctx.kit.line(FRONT + 18f, ty, FRONT - 2f, ty - 4f, 1f, edge);
            }
        }
    }

    // ========================================================================
    //  Towers
    // ========================================================================

    /** One emplacement. Its anchor is the base, as the source's is. */
    public void paintTower(RenderContext ctx, DefenceTower tower) {
        float x = tower.x();
        float y = up(tower.y());
        float w = tower.width();
        float h = tower.height();
        float left = x - w / 2f;

        if (artworkFor(ctx, tower, left, y, w, h)) {
            paintTowerStatus(ctx, tower, x, y, h);
            return;
        }
        switch (tower.type()) {
            case BOWMAN:   bowman(ctx, tower, left, y, w, h); break;
            case BALLISTA: ballista(ctx, tower, left, y, w, h); break;
            case CANNON:   cannon(ctx, tower, left, y, w, h); break;
            default:       break;
        }
    }

    private boolean artworkFor(RenderContext ctx, DefenceTower tower, float left, float y,
                               float w, float h) {
        VisualId id = VisualId.byKey(tower.type().id());
        if (id == null || tower.disabled()) {
            return false;       // the source refuses artwork for a wrecked tower
        }
        com.badlogic.gdx.graphics.g2d.TextureRegion region = ctx.artwork(id,
                com.mymmer.castledefense.assets.AnimationState.IDLE, tower.aim());
        if (region == null) {
            return false;
        }
        ctx.kit.end();
        ctx.batch.begin();
        ctx.batch.draw(region, left, y, w, h);
        ctx.batch.end();
        ctx.kit.fillBegin();
        return true;
    }

    private void bowman(RenderContext ctx, DefenceTower t, float left, float y, float w,
                        float h) {
        Color body = t.disabled() ? Palette.rgb(70, 62, 52) : Palette.rgb(96, 84, 66);
        ctx.kit.roundRectOutlined(left, y, w, h, 4f, 2f,
                body, ctx.shade(body, 0.6f));
        if (t.disabled()) {
            return;
        }
        float cx = left + w / 2f;
        float headY = y + h + 6f;
        ctx.kit.circle(cx, headY, 5f, Palette.SKIN);
        ctx.kit.roundRect(cx - 5f, headY - 16f, 10f, 12f, 3f,
                Palette.rgb(118, 186, 108));
        //  The bow follows the aim, which is gameplay's -- so it points at what
        //  the tower is actually about to shoot.
        float bx = cx + MathUtils.cos(t.aim()) * 12f;
        float by = headY - 6f + MathUtils.sin(t.aim()) * 12f;
        ctx.kit.arcBand(bx, by, 11f, 2f,
                (t.aim() - 1.1f) * MathUtils.radDeg, 2.2f * MathUtils.radDeg);
    }

    private void ballista(RenderContext ctx, DefenceTower t, float left, float y, float w,
                          float h) {
        Color base = t.disabled() ? Palette.rgb(74, 60, 44) : Palette.rgb(104, 82, 58);
        ctx.kit.roundRectOutlined(left, y, w, h - 12f, 3f, 2f,
                base, ctx.shade(base, 0.6f));
        if (t.disabled()) {
            return;
        }
        //  recoil() is the gameplay recoil timer, so the kick is synchronised
        //  with the shot rather than with a render clock
        float px = left + w / 2f - MathUtils.cos(t.aim()) * t.recoil() * 6f;
        float py = y + h - 8f + MathUtils.sin(t.aim()) * t.recoil() * 6f;
        float perp = t.aim() + MathUtils.PI / 2f;
        for (int s = -1; s <= 1; s += 2) {
            float ex = px + MathUtils.cos(perp) * 13f * s + MathUtils.cos(t.aim()) * 4f;
            float ey = py + MathUtils.sin(perp) * 13f * s + MathUtils.sin(t.aim()) * 4f;
            ctx.kit.line(px, py, ex, ey, 3f, Palette.rgb(150, 120, 80));
        }
        ctx.kit.line(px, py, px + MathUtils.cos(t.aim()) * 22f,
                py + MathUtils.sin(t.aim()) * 22f, 4f, Palette.rgb(206, 150, 84));
    }

    private void cannon(RenderContext ctx, DefenceTower t, float left, float y, float w,
                        float h) {
        Color base = t.disabled() ? Palette.rgb(54, 56, 64) : Palette.rgb(72, 76, 88);
        ctx.kit.roundRectOutlined(left, y, w, h - 10f, 4f, 2f,
                base, ctx.shade(base, 0.6f));
        ctx.kit.circle(left + w / 2f, y + 6f, 7f, Palette.rgb(40, 42, 50));
        if (t.disabled()) {
            return;
        }
        float px = left + w / 2f - MathUtils.cos(t.aim()) * t.recoil() * 8f;
        float py = y + h - 6f + MathUtils.sin(t.aim()) * t.recoil() * 8f;
        float ex = px + MathUtils.cos(t.aim()) * 26f;
        float ey = py + MathUtils.sin(t.aim()) * 26f;
        ctx.kit.line(px, py, ex, ey, 11f, Palette.rgb(46, 48, 56));
        ctx.kit.line(px, py, ex, ey, 7f, Palette.rgb(92, 96, 110));
        ctx.kit.circle(px, py, 6f, Palette.rgb(150, 152, 164));
    }

    /** {@code draw_status}: an X if wrecked, a health bar if hurt. */
    public void paintTowerStatus(RenderContext ctx, DefenceTower t, float x, float y,
                                 float h) {
        if (t.disabled()) {
            return;             // the X is text; drawn in the label pass
        }
        if (t.hp() < t.maxHp()) {
            ctx.kit.bar(x - 14f, up(y) + h + 6f, 28f, 4f, t.hp() / t.maxHp(),
                    Palette.GREEN);
        }
    }

    /** The text half of a tower's status, in a batch pass. */
    public void paintTowerLabel(RenderContext ctx, DefenceTower t) {
        if (t.disabled()) {
            ctx.textCentered(t.x(), up(t.y()) + t.height() + 14f, "X", 22f,
                    Palette.RED, true);
        }
    }

    // ========================================================================
    //  The two field structures
    // ========================================================================

    /** {@code enemies.py Necromancer.COLOR}, the same value EnemyPainter uses. */
    private static final Color NECROMANCER_ROBE = Palette.rgb(146, 96, 196);

    /** {@code Outpost.draw}: the outcrop, the blockhouse, its battlements. */
    public void paintOutpost(RenderContext ctx, Outpost outpost) {
        float x = outpost.x();
        float y = up(outpost.y());
        Color stone = Palette.rgb(74, 78, 92);

        poly[0] = x - 78f; poly[1] = GROUND + 16f;
        poly[2] = x - 46f; poly[3] = y - 6f;
        poly[4] = x + 46f; poly[5] = y - 6f;
        poly[6] = x + 78f; poly[7] = GROUND + 16f;
        ctx.kit.polygon(poly, 8, Palette.rgb(38, 44, 54));         // the outcrop

        float bx = x - 42f;
        float by = y - 8f;
        ctx.kit.rect(bx, by, 84f, 70f, stone);
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 4; col++) {
                ctx.kit.rect(bx + 3f + col * 20f, by + 70f - 20f - row * 17f, 17f, 14f,
                        ctx.shade(stone, 0.86f + 0.1f * ((row + col) % 2)));
            }
        }
        ctx.kit.rectOutline(bx, by, 84f, 70f, 2f, Palette.rgb(46, 50, 62));
        for (int i = 0; i < 5; i++) {
            ctx.kit.rect(bx + i * 18f, by + 70f, 12f, 12f, Palette.rgb(92, 96, 112));
        }
        paintGarrison(ctx, outpost, bx, by);
        paintPrisoner(ctx, outpost, x, by);
    }

    /**
     * The caged Necromancer. {@code castle.py Outpost.draw_prisoner}.
     *
     * <p>Missing entirely from this port: the gameplay trapped him, drained
     * him, let rivals shoot at him and released him on death, and the outpost
     * on screen looked exactly as it did when empty.
     *
     * <p>Every number here is read from the outpost's own state. The bar is the
     * <b>prisoner's</b> pool -- {@code prisonerHp / prisonerMax} -- and not the
     * outpost's, which has no health at all in the source and must not grow one
     * to give the bar something to show.
     *
     * <p>Coordinates: the source builds the cage from {@code body_rect.y + 8}
     * in a y-down space. Here {@code by} is the blockhouse's bottom edge in
     * draw space, so its top is {@code by + 70} and the cage hangs 8 below that.
     */
    private void paintPrisoner(RenderContext ctx, Outpost outpost, float cx,
                               float by) {
        if (!outpost.hasPrisoner()) {
            return;
        }
        float cageX = cx - CAGE_W * 0.5f;
        float cageBottom = cageBottom(outpost);
        float cageTop = cageBottom + CAGE_H;
        final float cageW = CAGE_W;
        final float cageH = CAGE_H;

        //  The ally-coloured glow behind the bars, brighter while the trap is
        //  fresh: alpha 50..120 of 255, as the source blends it.
        ShapeKit.enableBlend();
        float glow = (50f + 70f * outpost.trapGlow()) / 255f;
        //  ShapeRenderer.ellipse takes the bounding box's bottom-left, and the
        //  source blits the glow at (cage.x - 15, cage.y - 15) -- the cage
        //  inflated by 15 a side.
        ctx.kit.ellipse(cageX - 15f, cageBottom - 15f,
                cageW + 30f, cageH + 30f, ctx.alpha(Palette.ALLY, glow));

        //  The prisoner, hunched: a robe triangle, a hooded head, two eyes.
        //  The prisoner is always a Necromancer -- he is the only Trappable --
        //  and the source shades his own COLOR to 0.8.
        Color robe = ctx.shade(NECROMANCER_ROBE, 0.8f);
        poly[0] = cx;               poly[1] = cageTop - 8f;
        poly[2] = cageX + cageW - 6f; poly[3] = cageBottom + 4f;
        poly[4] = cageX + 6f;       poly[5] = cageBottom + 4f;
        ctx.kit.polygon(poly, 6, robe);
        ctx.kit.circle(cx, cageTop - 14f, 6f, Palette.rgb(44, 32, 56));
        ctx.kit.circle(cx - 2f, cageTop - 13f, 2f, Palette.ALLY);
        ctx.kit.circle(cx + 3f, cageTop - 13f, 2f, Palette.ALLY);

        //  The cage: a frame and four vertical bars.
        ctx.kit.rectOutline(cageX, cageBottom, cageW, cageH, 3f,
                Palette.rgb(66, 72, 88));
        for (int i = 0; i < 4; i++) {
            float barX = cageX + 8f + i * 10f;
            ctx.kit.line(barX, cageBottom + 2f, barX, cageTop - 2f, 2f,
                    Palette.rgb(150, 158, 176));
        }

        //  His own health, above the cage. Green until a third is left.
        float frac = outpost.prisonerHp() / Math.max(1f, outpost.prisonerMax());
        ctx.kit.bar(cageX - 4f, cageTop + 5f, cageW + 8f, 5f, frac,
                frac > 0.35f ? Palette.ALLY : Palette.rgb(208, 62, 60));
    }

    private static final float CAGE_W = 44f;
    private static final float CAGE_H = 46f;

    /**
     * The cage's bottom edge in draw space, from one place.
     *
     * <p>Shared by the shapes and the labels because they are drawn in separate
     * passes and derived it separately at first -- which put the two captions
     * eight units up, inside the bars.
     *
     * <p>{@code paintOutpost} sets the blockhouse's bottom to {@code up(y) - 8}
     * and its height to 70, and the source hangs the cage 8 below the
     * blockhouse's top.
     */
    private float cageBottom(Outpost outpost) {
        return up(outpost.y()) - 8f + 70f - 8f - CAGE_H;
    }

    /** The prisoner's two labels, drawn in the text pass with the others. */
    public void paintPrisonerLabels(RenderContext ctx, Outpost outpost) {
        if (outpost == null || !outpost.hasPrisoner()) {
            return;
        }
        //  textCentered's y is the text's LOWER edge in draw space, and the
        //  source puts each caption's top just under the cage -- so a line's
        //  height comes off as well as the source's own offset.
        float below = cageBottom(outpost);
        ctx.textCentered(outpost.x(), below - 2f - 15f, "TRAPPED", 15f,
                Palette.ALLY, true, true);
        if (outpost.prisonerHit() > 0d) {
            ctx.textCentered(outpost.x(), below - 17f - 15f, "UNDER FIRE", 15f,
                    Palette.rgb(208, 62, 60), true, true);
        }
    }

    /** The bows or turrets on the outpost roof, one per garrison level. */
    private void paintGarrison(RenderContext ctx, Outpost outpost, float bx,
                               float by) {
        if (outpost.level() <= 0) {
            return;
        }
        boolean turret = outpost.isTurret();
        for (int i = 0; i < outpost.guns(); i++) {
            float gx = outpost.gunX(i);
            float gy = up(outpost.gunY(i));
            if (turret) {
                ctx.kit.roundRect(gx - 8f, gy - 8f, 16f, 14f, 3f,
                        Palette.rgb(96, 104, 124));
                ctx.kit.line(gx, gy + 2f, gx + 14f, gy + 6f, 4f,
                        Palette.rgb(150, 158, 178));
                ctx.kit.circle(gx, gy + 2f, 4f, Palette.rgb(198, 206, 226));
            } else {
                ctx.kit.circle(gx, gy + 6f, 4f, Palette.rgb(222, 190, 152));
                ctx.kit.roundRect(gx - 4f, gy - 8f, 8f, 10f, 2f,
                        Palette.rgb(110, 170, 108));
                ctx.kit.arcBand(gx + 9f, gy, 10f, 2f, -63f, 126f);
            }
        }
    }

    /** {@code Barricade.draw}, including the rubble it leaves behind. */
    public void paintBarricade(RenderContext ctx, Barricade barricade) {
        float x = barricade.x();
        if (!barricade.alive()) {
            if (barricade.level() > 0) {
                for (int i = 0; i < 5; i++) {
                    ctx.kit.rect(x - 22f + i * 10f, GROUND + (i % 2) * 5f, 9f, 8f,
                            Palette.rgb(72, 64, 54));              // rubble
                }
            }
            return;
        }
        float top = up(barricade.topY());
        float w = Barricade.WIDTH;
        float left = x - w / 2f;
        float h = top - GROUND;

        Color col = Palette.rgb(128, 112, 88);
        if (barricade.flash() > 0f) {
            col = ctx.mix(col, Palette.rgb(255, 190, 170), barricade.flash() * 0.8f);
        }
        ctx.kit.rect(left, GROUND, w, h, col);
        ctx.kit.rectOutline(left, GROUND, w, h, 3f, ctx.shade2(col, 0.6f));
        for (int i = 0; i < 4; i++) {
            float yy = top - 14f - i * 22f;
            ctx.kit.line(left + 2f, yy, left + w - 2f, yy, 2f, ctx.shade2(col, 0.7f));
        }
        for (int s = -1; s <= 1; s += 2) {
            ctx.kit.line(x, top - 10f, x + s * 22f, GROUND + 2f, 4f,
                    ctx.shade2(col, 0.8f));                        // braces
        }
        ctx.kit.triangle(left - 4f, top, left + w + 4f, top, x, top + 14f,
                Palette.rgb(156, 140, 112));
        ctx.kit.bar(x - 30f, top + 24f, 60f, 6f,
                barricade.hp() / Math.max(1f, barricade.maxHp()),
                Palette.rgb(206, 160, 92));
    }

    /** The structures' text, in a batch pass. */
    public void paintStructureLabels(RenderContext ctx, Outpost outpost,
                                     Barricade barricade) {
        if (outpost != null) {
            float labelY = up(outpost.y()) + 70f + 14f;
            if (outpost.level() <= 0) {
                ctx.textCentered(outpost.x(), labelY, "OUTPOST (empty)", 16f,
                        Palette.rgb(150, 156, 174), true);
            } else {
                String tag = outpost.isTurret() ? "TURRETS" : "BOWMEN";
                String label = "OUTPOST " + tag + " x" + outpost.guns();
                if (outpost.overdrive() > 1.0f) {
                    label += String.format(java.util.Locale.ROOT, "  x%.2f PWR",
                            outpost.overdrive());
                }
                ctx.textCentered(outpost.x(), labelY, label, 15f,
                        Palette.rgb(176, 200, 226), true);
            }
        }
        if (barricade != null && barricade.alive()) {
            ctx.textCentered(barricade.x(), up(barricade.topY()) + 42f,
                    "Lv." + barricade.level(), 15f, Palette.rgb(206, 182, 140), true);
        }
    }
}
