package com.mymmer.castledefense.render;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.math.MathUtils;
import com.mymmer.castledefense.assets.AnimationState;
import com.mymmer.castledefense.assets.VisualId;
import com.mymmer.castledefense.enemy.Enemy;
import com.mymmer.castledefense.enemy.EnemyState;
import com.mymmer.castledefense.enemy.EnemyType;

/**
 * Every ordinary unit, drawn from primitives — the source's {@code draw_body}.
 *
 * <h2>The shared frame</h2>
 *
 * <p>{@code Enemy.draw} is the same four steps for every type:
 *
 * <pre>
 *   shadow, if the unit is airborne or held
 *   draw_body()          -- artwork if the skin has it, else by hand
 *   draw_regalia_guard() -- only while a boss is warding its regalia
 *   draw_hp()            -- the strip bar, then the health bar
 * </pre>
 *
 * <p>Only {@code draw_body} differs per type, so that is the only thing that
 * branches here.
 *
 * <h2>Colours come from gameplay, not from the frame</h2>
 *
 * <p>{@code body_color()} is {@code mix(COLOR, white, hurt_flash * 0.75)}, and
 * {@code hurt_flash} is a gameplay-owned timer. Every animation phase is the
 * same: {@code anim()} for walking, {@code spin()} for a tumbling body,
 * {@code fuse()} for a Volatile's pulse. Nothing here keeps a clock, so a frame
 * drawn twice looks identical and a frame not drawn changes nothing.
 *
 * <h2>Coordinates</h2>
 *
 * <p>Pygame's y grows downward and libGDX's grows upward, so every vertical
 * offset in the source is negated here. The rectangle is built once in
 * {@link #frame} and the body drawn relative to it, which keeps that conversion
 * in one place instead of in ninety subtractions.
 */
public final class EnemyPainter {

    // --- the per-type base colours, from each class's COLOR ------------------
    private static final Color SCOUT = Palette.rgb(118, 204, 116);
    private static final Color FOOT_SOLDIER = Palette.rgb(92, 130, 200);
    private static final Color SHIELD_BEARER = Palette.rgb(176, 148, 96);
    private static final Color BERZERKER = Palette.rgb(222, 96, 64);
    private static final Color SIEGE_RAM = Palette.rgb(128, 92, 58);
    private static final Color SKELETON = Palette.rgb(222, 220, 206);
    private static final Color NECROMANCER = Palette.rgb(146, 96, 196);
    private static final Color ASSASSIN = Palette.rgb(62, 66, 92);
    private static final Color GARGOYLE = Palette.rgb(122, 126, 140);
    private static final Color VOLATILE = Palette.rgb(232, 138, 52);
    private static final Color TREASURE_GOBLIN = Palette.rgb(218, 176, 60);

    /** Reused rectangle: left, bottom, width, height, in world units. */
    private final float[] frame = new float[4];
    private final Color body = new Color();
    private final float[] poly = new float[16];

    // ========================================================================
    //  The shared frame
    // ========================================================================

    /** One unit, complete. Assumes a filled shape pass is open. */
    public void paint(RenderContext ctx, Enemy e, float drawX, float drawY) {
        frame[0] = drawX - e.width() / 2f;
        frame[1] = drawY - e.height() / 2f;
        frame[2] = e.width();
        frame[3] = e.height();

        if (e.state() == EnemyState.AIR || e.state() == EnemyState.GRABBED) {
            shadow(ctx, e, drawX);
        }
        if (!artwork(ctx, e, drawX, drawY)) {
            drawBody(ctx, e);
        }
    }

    /**
     * The ground shadow under a thrown or held body.
     *
     * <p>Its width shrinks with altitude — {@code clamp(1 - (gy - y)/900, .35, 1)}
     * — which is the only cue the player has for where a fling will land, so the
     * numbers are the source's exactly.
     */
    private void shadow(RenderContext ctx, Enemy e, float drawX) {
        float groundY = WorldGeometry.toDrawY(e.groundY());
        float rise = Math.abs(WorldGeometry.toDrawY(e.y()) - groundY);
        float scale = MathUtils.clamp(1f - rise / 900f, 0.35f, 1f);
        float w = Math.max(6f, e.width() * scale);
        ShapeKit.enableBlend();
        ctx.kit.ellipse(drawX - w / 2f, groundY - 4f, w, 8f,
                ctx.alpha(Color.BLACK, 90f / 255f));
    }

    /** Skin artwork for this unit, or false to draw it by hand. */
    private boolean artwork(RenderContext ctx, Enemy e, float drawX, float drawY) {
        VisualId id = visualFor(e.type());
        if (id == null) {
            return false;
        }
        TextureRegion region = ctx.artwork(id, stateOf(e), e.anim());
        if (region == null) {
            return false;
        }
        //  Drawn into the unit's own box.  The box is gameplay's -- width() and
        //  height() -- so artwork can never change how big a thing is to hit.
        ctx.kit.end();
        ctx.batch.begin();
        ctx.batch.setColor(bodyColor(ctx, e, baseColor(e.type())));
        ctx.batch.draw(region, frame[0], frame[1], frame[2], frame[3]);
        ctx.batch.setColor(Color.WHITE);
        ctx.batch.end();
        ctx.kit.fillBegin();
        return true;
    }

    /** The gameplay state a skin animates against. */
    public static AnimationState stateOf(Enemy e) {
        if (!e.alive()) {
            return AnimationState.DEAD;
        }
        switch (e.state()) {
            case GRABBED: return AnimationState.GRABBED;
            case AIR:     return AnimationState.AIR;
            case RETRIEVE: return AnimationState.RETRIEVE;
            default:      break;
        }
        if (e.hurtFlash() > 0f) {
            return AnimationState.HURT;
        }
        if (e.blocked()) {
            return AnimationState.ATTACK;
        }
        return AnimationState.WALK;
    }

    // ========================================================================
    //  draw_hp: the strip bar, then the health bar
    // ========================================================================

    /**
     * {@code draw_hp}, exactly.
     *
     * <p>Two rules worth keeping: a boss never shows this bar — Phase 10's UI
     * owns boss health — and a unit at full health shows nothing at all, which is
     * what keeps an untouched wave from looking like a row of gauges.
     */
    public void paintHealth(RenderContext ctx, Enemy e, float drawX, float drawY) {
        if (!e.alive()) {
            return;
        }
        float w = Math.max(20f, e.width());
        float left = drawX - w / 2f;
        if (e.stripProgress() > 0f) {
            ctx.kit.bar(left, drawY + e.height() / 2f + 12f, w, 5f,
                    e.stripProgress(), Palette.rgb(255, 196, 90));
        }
        if (e.isBoss() || e.hp() >= e.maxHp()) {
            return;
        }
        ctx.kit.bar(left, drawY + e.height() / 2f + 6f, w, 4f,
                e.hp() / e.maxHp(), Palette.GREEN);
    }

    // ========================================================================
    //  The bodies
    // ========================================================================

    private void drawBody(RenderContext ctx, Enemy e) {
        Color col = bodyColor(ctx, e, baseColor(e.type()));
        switch (e.type()) {
            case SCOUT:           scout(ctx, e, col); break;
            case FOOT_SOLDIER:    footSoldier(ctx, e, col); break;
            case SHIELD_BEARER:   shieldBearer(ctx, e, col); break;
            case BERZERKER:       berzerker(ctx, e, col); break;
            case SIEGE_RAM:       siegeRam(ctx, e, col); break;
            case SKELETON:        skeleton(ctx, e, col); break;
            case NECROMANCER:     necromancer(ctx, e, col); break;
            case ASSASSIN:        assassin(ctx, e, col); break;
            case GARGOYLE:        gargoyle(ctx, e, col); break;
            case VOLATILE:        volatileMob(ctx, e, col); break;
            case TREASURE_GOBLIN: goblin(ctx, e, col); break;
            default:              generic(ctx, col); break;
        }
    }

    /** The base body, for a type with no bespoke painter. */
    private void generic(RenderContext ctx, Color col) {
        ctx.kit.roundRect(l(), b(), w(), h(), 4f, col);
        ctx.kit.roundRectOutline(l(), b(), w(), h(), 4f, 2f, ctx.shade(col, 0.5f));
    }

    private void scout(RenderContext ctx, Enemy e, Color col) {
        legs(ctx, e, ctx.shade(col, 0.6f), 6f, 8f);
        ctx.kit.roundRect(l(), b() + 2f, w(), h() - 8f, 4f, col);
        ctx.kit.roundRectOutline(l(), b() + 2f, w(), h() - 8f, 4f, 2f,
                ctx.shade(col, 0.5f));
        ctx.kit.circle(cx(), t() - 5f, 6f, Palette.rgb(232, 202, 164));
        ctx.kit.line(l() - 4f, cy(), l() - 12f, cy() - 6f, 2f,
                Palette.rgb(198, 194, 186));
    }

    private void footSoldier(RenderContext ctx, Enemy e, Color col) {
        legs(ctx, e, Palette.rgb(58, 62, 84), 6f, 8f);
        ctx.kit.roundRect(l(), b() + 2f, w(), h() - 10f, 3f, col);
        ctx.kit.roundRectOutline(l(), b() + 2f, w(), h() - 10f, 3f, 2f,
                ctx.shade(col, 0.5f));
        ctx.kit.circle(cx(), t() - 7f, 7f, Palette.SKIN);
        ctx.kit.roundRect(l() + 2f, t() - 6f, w() - 4f, 6f, 2f,
                Palette.rgb(150, 156, 172));                       // helmet
        float sw = MathUtils.sin(e.anim() * 0.9f) * 4f;
        ctx.kit.line(l() - 2f, cy() - 4f, l() - 14f + sw, cy() + 8f, 3f,
                Palette.rgb(206, 210, 220));                       // sword
    }

    private void shieldBearer(RenderContext ctx, Enemy e, Color col) {
        legs(ctx, e, Palette.rgb(72, 62, 46), 8f, 9f);
        ctx.kit.roundRect(l() + 6f, b() + 2f, w() - 8f, h() - 10f, 3f, col);
        ctx.kit.circle(cx() + 4f, t() - 7f, 7f, Palette.SKIN);
        //  The shield on the leading edge -- the visual reason arrows bounce.
        float sx = l() - 6f;
        float sy = b();
        ctx.kit.roundRect(sx, sy, 14f, h() - 2f, 4f, Palette.rgb(150, 156, 168));
        ctx.kit.roundRectOutline(sx, sy, 14f, h() - 2f, 4f, 3f,
                Palette.rgb(98, 104, 116));
        ctx.kit.circle(sx + 7f, sy + (h() - 2f) / 2f, 5f, Palette.rgb(206, 176, 96));
    }

    /** The "ARMOR" label, drawn in a text pass after the shapes. */
    public void paintLabels(RenderContext ctx, Enemy e, float drawX, float drawY) {
        float top = drawY + e.height() / 2f;
        if (e.type() == EnemyType.SHIELD_BEARER) {
            ctx.textCentered(drawX, top + 8f, "ARMOR", 14f,
                    Palette.rgb(200, 200, 210), false);
        } else if (e.type() == EnemyType.SIEGE_RAM && e.layers() == 0) {
            ctx.textCentered(drawX, drawY - e.height() / 2f - 20f, "EXPOSED", 16f,
                    Palette.rgb(255, 140, 120), true);
        } else if (e.type() == EnemyType.TREASURE_GOBLIN) {
            ctx.textCentered(drawX, top + 4f,
                    ((int) ((com.mymmer.castledefense.enemy.TreasureGoblin) e)
                            .escapeTimer()) + "s", 15f,
                    Palette.rgb(240, 214, 130), true);
        }
    }

    private void berzerker(RenderContext ctx, Enemy e, Color col) {
        legs(ctx, e, Palette.rgb(110, 48, 34), 6f, 8f);
        ctx.kit.roundRect(l(), b() + 2f, w(), h() - 9f, 3f, col);
        ctx.kit.circle(cx(), t() - 6f, 7f, Palette.rgb(240, 176, 140));
        Color brow = Palette.rgb(255, 232, 120);
        ctx.kit.line(cx() - 5f, t() - 5f, cx() - 1f, t() - 5f, 2f, brow);
        ctx.kit.line(cx() + 1f, t() - 5f, cx() + 5f, t() - 5f, 2f, brow);
        float sw = MathUtils.sin(e.anim() * 1.6f) * 8f;
        for (int s = -1; s <= 1; s += 2) {
            ctx.kit.line(cx(), cy(), cx() + s * 16f, cy() + 10f - sw, 4f,
                    Palette.rgb(216, 216, 226));                   // twin axes
        }
    }

    /**
     * The Siege Ram: wheels, frame, roof, <b>one plate per remaining layer</b>,
     * and the recoiling log.
     *
     * <p>The plates are drawn from {@code e.layers()} — the gameplay count — not
     * from which texture happens to be loaded. That is the whole armour contract:
     * strip a plate and the layer count drops, so the panel disappears. Reading
     * it the other way round would make the picture the authority.
     */
    private void siegeRam(RenderContext ctx, Enemy e, Color col) {
        float bottom = b();
        for (int i = 0; i < 2; i++) {
            float wx = i == 0 ? l() + 16f : l() + w() - 16f;
            float wy = bottom - 4f;
            ctx.kit.circle(wx, wy, 10f, Palette.rgb(58, 46, 34));
            ctx.kit.circleOutline(wx, wy, 10f, 2f, Palette.rgb(96, 78, 56));
            float ang = e.anim() * 0.6f;
            ctx.kit.line(wx - MathUtils.cos(ang) * 8f, wy - MathUtils.sin(ang) * 8f,
                    wx + MathUtils.cos(ang) * 8f, wy + MathUtils.sin(ang) * 8f, 2f,
                    Palette.rgb(140, 118, 88));                    // spoke
        }
        ctx.kit.roundRect(l(), bottom, w(), h() - 10f, 4f, col);
        ctx.kit.roundRectOutline(l(), bottom, w(), h() - 10f, 4f, 3f,
                ctx.shade(col, 0.55f));
        poly[0] = l() - 6f;      poly[1] = t() - 10f;
        poly[2] = l() + w() + 6f; poly[3] = t() - 10f;
        poly[4] = l() + w() - 6f; poly[5] = t() + 6f;
        poly[6] = l() + 6f;      poly[7] = t() + 6f;
        ctx.kit.polygon(poly, 8, Palette.rgb(86, 66, 44));         // roof

        int maxLayers = 3;                                          // ARMOR_LAYERS
        float plateW = (w() - 8f) / maxLayers;
        for (int i = 0; i < e.layers(); i++) {
            float px = l() + 4f + i * plateW;
            float py = b() + 4f;
            float ph = h() - 12f;
            float pw = plateW - 3f;
            ctx.kit.roundRect(px, py, pw, ph, 3f, Palette.rgb(150, 157, 174));
            ctx.kit.line(px + 3f, py + 4f, px + pw - 4f, py + ph - 3f, 3f,
                    Palette.rgb(196, 202, 218));
            ctx.kit.roundRectOutline(px, py, pw, ph, 3f, 2f, Palette.rgb(84, 90, 104));
            for (int by = 0; by < 2; by++) {
                for (int bx = 0; bx < 2; bx++) {
                    ctx.kit.circle(px + (bx == 0 ? 5f : pw - 5f),
                            py + (by == 0 ? 5f : ph - 5f), 2f,
                            Palette.rgb(214, 218, 230));           // bolts
                }
            }
        }
        //  The log, recoiling on impact.  ramPush is gameplay's, so the recoil
        //  is synchronised with the damage rather than with a render clock.
        float px = l() - 16f
                - ((com.mymmer.castledefense.enemy.SiegeRam) e).ramPush() * 12f;
        ctx.kit.line(px + 34f, cy() - 6f, px, cy() - 6f, 12f, Palette.rgb(74, 56, 38));
        ctx.kit.circle(px, cy() - 6f, 9f, Palette.rgb(152, 156, 168));
        ctx.kit.circleOutline(px, cy() - 6f, 9f, 2f, Palette.rgb(98, 102, 114));
    }

    private void skeleton(RenderContext ctx, Enemy e, Color col) {
        legs(ctx, e, Palette.rgb(186, 184, 170), 6f, 8f);
        ctx.kit.circle(cx(), t() - 6f, 6f, col);
        ctx.kit.circle(cx() - 2f, t() - 5f, 2f, Palette.rgb(40, 36, 40));
        ctx.kit.circle(cx() + 2f, t() - 5f, 2f, Palette.rgb(40, 36, 40));
        for (int i = 0; i < 3; i++) {
            float yy = t() - 13f - i * 5f;
            ctx.kit.line(cx() - 5f, yy, cx() + 5f, yy, 2f, col);    // ribs
        }
        ctx.kit.line(cx(), t() - 12f, cx(), t() - 24f, 2f, col);    // spine
    }

    private void necromancer(RenderContext ctx, Enemy e, Color col) {
        poly[0] = cx();       poly[1] = t();
        poly[2] = l() + w();  poly[3] = b();
        poly[4] = l();        poly[5] = b();
        ctx.kit.polygon(poly, 6, col);                              // the robe
        ctx.kit.circle(cx(), t() - 10f, 7f, Palette.rgb(44, 32, 56));
        ctx.kit.circle(cx() - 2f, t() - 9f, 2f, Palette.rgb(206, 140, 255));
        ctx.kit.circle(cx() + 3f, t() - 9f, 2f, Palette.rgb(206, 140, 255));
        float sx = l() + w() + 4f;
        ctx.kit.line(sx, b(), sx, t() + 8f, 3f, Palette.rgb(120, 96, 72));
        //  The orb flares while casting; glow() is gameplay's cast phase.
        float gr = 5f + 4f * ((com.mymmer.castledefense.enemy.Necromancer) e).glow()
                + MathUtils.sin(e.anim()) * 1.5f;
        ctx.kit.circle(sx, t() + 10f, gr, Palette.rgb(196, 140, 255));
        ctx.kit.circle(sx, t() + 10f, Math.max(1f, gr - 3f), Palette.rgb(238, 214, 255));
    }

    private void assassin(RenderContext ctx, Enemy e, Color col) {
        if (((com.mymmer.castledefense.enemy.Assassin) e).cloaked()) {
            //  Untargetable and nearly invisible: a translucent ghost, which is
            //  the player's only warning that one is out there.
            ShapeKit.enableBlend();
            ctx.kit.roundRect(l(), b(), w(), h(), 5f,
                    ctx.alpha(ctx.shade(col, 1.6f), 70f / 255f));
            ctx.kit.roundRectOutline(l(), b(), w(), h(), 5f, 2f,
                    ctx.alpha(Palette.rgb(140, 170, 230), 110f / 255f));
            return;
        }
        legs(ctx, e, Palette.rgb(40, 44, 62), 6f, 8f);
        ctx.kit.roundRect(l(), b() + 2f, w(), h() - 8f, 5f, col);
        ctx.kit.roundRectOutline(l(), b() + 2f, w(), h() - 8f, 5f, 2f,
                Palette.rgb(36, 38, 54));
        ctx.kit.circle(cx(), t() - 5f, 6f, Palette.rgb(44, 46, 66));
        ctx.kit.line(cx() - 4f, t() - 4f, cx() + 4f, t() - 4f, 2f,
                Palette.rgb(232, 96, 96));                          // the eye slit
        ctx.kit.line(l() - 2f, cy(), l() - 12f, cy() + 4f, 2f,
                Palette.rgb(214, 220, 236));                        // dagger
    }

    private void gargoyle(RenderContext ctx, Enemy e, Color col) {
        float flap = MathUtils.sin(e.anim()) * 12f;
        for (int s = -1; s <= 1; s += 2) {
            poly[0] = cx();               poly[1] = cy() + 2f;
            poly[2] = cx() + s * 26f;     poly[3] = cy() + 12f - flap;
            poly[4] = cx() + s * 20f;     poly[5] = cy() - 8f - flap * 0.4f;
            ctx.kit.polygon(poly, 6, ctx.shade(col, 0.75f));        // wings
        }
        ctx.kit.ellipse(l(), b(), w(), h(), col);
        ctx.kit.circle(cx() - 5f, cy() + 3f, 3f, Palette.rgb(250, 190, 90));
        ctx.kit.circle(cx() + 5f, cy() + 3f, 3f, Palette.rgb(250, 190, 90));
        for (int s = -1; s <= 1; s += 2) {
            ctx.kit.line(cx() + s * 6f, t() - 2f, cx() + s * 9f, t() + 6f, 2f,
                    ctx.shade2(col, 1.25f));                        // horns
        }
    }

    private void volatileMob(RenderContext ctx, Enemy e, Color col) {
        float pulse = 0.5f + 0.5f
                * MathUtils.sin(((com.mymmer.castledefense.enemy.Volatile) e).fuse());
        legs(ctx, e, ctx.shade(col, 0.6f), 6f, 8f);
        //  The source builds a per-frame SRCALPHA surface here; drawn directly
        //  instead -- same look, no texture allocated sixty times a second.
        ShapeKit.enableBlend();
        ctx.kit.glow(cx(), cy(), w() * 0.9f, Palette.rgb(255, 150, 60),
                (60f + 60f * pulse) / 255f);
        ctx.kit.circle(cx(), cy(), w() / 2f,
                ctx.mix(col, Palette.rgb(255, 240, 180), pulse * 0.55f));
        ctx.kit.circleOutline(cx(), cy(), w() / 2f, 2f, ctx.shade(col, 0.5f));
        ctx.kit.circle(cx() - 4f, cy() + 3f, 2f, Palette.rgb(40, 30, 24));
        ctx.kit.circle(cx() + 4f, cy() + 3f, 2f, Palette.rgb(40, 30, 24));
        float fx = cx() + 6f;
        float fy = t() + 4f;
        ctx.kit.line(cx(), t() - 2f, fx, fy, 2f, Palette.rgb(90, 74, 58));
        ctx.kit.circle(fx, fy + pulse * 2f, 2f + pulse * 2f,
                Palette.rgb(255, 226, 130));                        // the fuse
    }

    private void goblin(RenderContext ctx, Enemy e, Color col) {
        //  It hops, and the whole body moves with it.
        float bounce = Math.abs(MathUtils.sin(
                ((com.mymmer.castledefense.enemy.TreasureGoblin) e).hop())) * 4f;
        float y0 = b() + bounce;
        legs(ctx, e, Palette.rgb(150, 120, 40), 6f, 8f);
        ctx.kit.ellipse(l(), y0 + 2f, w(), h() - 10f, col);
        ctx.kit.circle(cx(), y0 + h() - 7f, 7f, Palette.rgb(150, 200, 130));
        ctx.kit.circle(cx() - 3f, y0 + h() - 6f, 2f, Palette.rgb(30, 40, 30));
        ctx.kit.circle(cx() + 3f, y0 + h() - 6f, 2f, Palette.rgb(30, 40, 30));
        float sx = l() + w() - 4f;
        float sy = y0 + h() - 22f;
        ctx.kit.ellipse(sx, sy, 18f, 18f, Palette.rgb(196, 156, 48));
        ctx.kit.line(sx + 9f, sy + 18f, sx + 9f, sy + 22f, 3f, Palette.rgb(120, 92, 24));
        for (int k = 0; k < 3; k++) {
            ctx.kit.circle(sx + 5f + k * 4f, sy + 7f, 2f, Palette.rgb(255, 226, 120));
        }
    }

    // ========================================================================
    //  Shared pieces
    // ========================================================================

    /**
     * {@code Enemy._legs}: two legs bobbing out of phase.
     *
     * <p>The phase is {@code spin * 3} while airborne or held and {@code anim}
     * otherwise — both gameplay values, so a tumbling body's legs windmill with
     * its actual rotation.
     */
    private void legs(RenderContext ctx, Enemy e, Color color, float span,
                      float length) {
        float phase = (e.state() == EnemyState.AIR || e.state() == EnemyState.GRABBED)
                ? e.spin() * 3f : e.anim();
        float bottom = b();
        for (int s = -1; s <= 1; s += 2) {
            float off = MathUtils.sin(phase + (s < 0 ? 0f : MathUtils.PI)) * 4f;
            ctx.kit.line(cx() + s * span, bottom + 2f,
                    cx() + s * span + off, bottom - length, 3f, color);
        }
    }

    /**
     * {@code body_color}, plus the endgame tier tint.
     *
     * <p>The tint is a property of the unit's tier, which gameplay assigns at
     * spawn, so a Voidtouched Scout is dark because it <em>is</em> one.
     */
    private Color bodyColor(RenderContext ctx, Enemy e, Color base) {
        body.set(base);
        Color tint = tierTint(e.tier());
        if (tint != null) {
            Palette.mix(body, tint, tierStrength(e.tier()), body);
        }
        if (e.hurtFlash() > 0f) {
            Palette.mix(body, Color.WHITE, e.hurtFlash() * 0.75f, body);
        }
        return body;
    }

    private static Color tierTint(int tier) {
        switch (tier) {
            case 1:  return Palette.TIER_BLOODIED;
            case 2:  return Palette.TIER_FROSTBOUND;
            case 3:  return Palette.TIER_VOIDTOUCHED;
            default: return null;
        }
    }

    private static float tierStrength(int tier) {
        switch (tier) {
            case 1:  return 0.42f;
            case 2:  return 0.46f;
            case 3:  return 0.55f;
            default: return 0f;
        }
    }

    public static Color baseColor(EnemyType type) {
        switch (type) {
            case SCOUT:           return SCOUT;
            case FOOT_SOLDIER:    return FOOT_SOLDIER;
            case SHIELD_BEARER:   return SHIELD_BEARER;
            case BERZERKER:       return BERZERKER;
            case SIEGE_RAM:       return SIEGE_RAM;
            case SKELETON:        return SKELETON;
            case NECROMANCER:     return NECROMANCER;
            case ASSASSIN:        return ASSASSIN;
            case GARGOYLE:        return GARGOYLE;
            case VOLATILE:        return VOLATILE;
            case TREASURE_GOBLIN: return TREASURE_GOBLIN;
            default:              return Palette.DIM;
        }
    }

    public static VisualId visualFor(EnemyType type) {
        return VisualId.byKey(type.id());
    }

    // --- frame accessors, so the bodies read like the source ------------------
    private float l() {
        return frame[0];
    }

    private float b() {
        return frame[1];
    }

    private float w() {
        return frame[2];
    }

    private float h() {
        return frame[3];
    }

    private float cx() {
        return frame[0] + frame[2] / 2f;
    }

    private float cy() {
        return frame[1] + frame[3] / 2f;
    }

    /** The TOP edge in libGDX terms — the source's {@code r.y}. */
    private float t() {
        return frame[1] + frame[3];
    }
}
