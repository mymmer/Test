package com.mymmer.castledefense.render;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.math.MathUtils;
import com.mymmer.castledefense.assets.AnimationState;
import com.mymmer.castledefense.assets.VisualId;
import com.mymmer.castledefense.boss.Boss;
import com.mymmer.castledefense.boss.BossType;

/**
 * The three bosses, and the regalia the player can take off two of them.
 *
 * <h2>What is here and what is not</h2>
 *
 * <p>Body, aura, breath, ward, crown, staff and the reeling club — everything
 * attached to the boss itself. The <b>health bar is not here</b>: Phase 10's UI
 * owns boss health, drawn unshaken in UI space, and duplicating it in world
 * space would give a boss two bars that disagree during a screen shake.
 *
 * <h2>Regalia follows gameplay, not animation</h2>
 *
 * <p>{@code has_crown} and {@code has_staff} are gameplay flags. The crown is
 * drawn on the Troll King's head only while {@code hasCrown()} is true, and the
 * instant the player pulls it off, the head is bare and a {@code DroppedItem}
 * appears at the item's own position. Nothing waits for an animation to finish;
 * an animation cannot be the reason a detached item still looks attached.
 *
 * <h2>The regalia ward</h2>
 *
 * <p>After a boss recovers its regalia it guards it, and guards it longer each
 * time it is robbed: {@code REGALIA_COOLDOWN * (1 + GROWTH * (taken - 1))}. The
 * shrinking arc is that timer, and it is the player's only cue that grabbing
 * again will fail — so the span is computed exactly as the source computes it
 * rather than from the raw cooldown.
 */
public final class BossPainter {

    private static final Color TROLL_KING = Palette.rgb(108, 156, 92);
    private static final Color DRAGON = Palette.rgb(198, 62, 58);
    private static final Color LICH_LORD = Palette.rgb(120, 92, 190);

    private final float[] poly = new float[16];
    private final Color body = new Color();
    private final float[] anchor = new float[2];

    /** The whole boss, shapes only. Assumes a filled pass is open. */
    public void paint(RenderContext ctx, Boss boss, float drawX, float drawY) {
        float w = boss.width();
        float h = boss.height();
        float left = drawX - w / 2f;
        float bottom = drawY - h / 2f;

        //  The aura reads as "this one is dangerous"; its strength is a gameplay
        //  value, so it swells when the boss actually enrages.
        if (boss.aura() > 0f) {
            ShapeKit.enableBlend();
            ctx.kit.glow(drawX, drawY, Math.max(w, h) / 2f + 20f,
                    Palette.rgb(255, 120, 90),
                    70f / 255f * boss.aura() * ctx.quality.glowIntensity());
        }
        if (artwork(ctx, boss, left, bottom, w, h)) {
            paintRegaliaWard(ctx, boss, drawX, drawY);
            return;
        }
        Color col = bodyColour(ctx, boss);
        switch (boss.bossType()) {
            case TROLL_KING: trollKing(ctx, boss, col, left, bottom, w, h); break;
            case DRAGON:     dragon(ctx, boss, col, left, bottom, w, h); break;
            case LICH_LORD:  lichLord(ctx, boss, col, left, bottom, w, h); break;
            default:         break;
        }
        paintRegaliaWard(ctx, boss, drawX, drawY);
    }

    private boolean artwork(RenderContext ctx, Boss boss, float left, float bottom,
                            float w, float h) {
        VisualId id = VisualId.byKey(boss.bossType().id());
        if (id == null) {
            return false;
        }
        TextureRegion region = ctx.artwork(id, stateOf(boss), boss.anim());
        if (region == null) {
            return false;
        }
        ctx.kit.end();
        ctx.batch.begin();
        ctx.batch.setColor(bodyColour(ctx, boss));
        ctx.batch.draw(region, left, bottom, w, h);
        ctx.batch.setColor(Color.WHITE);
        ctx.batch.end();
        ctx.kit.fillBegin();
        return true;
    }

    /** The gameplay state a skin animates a boss against. */
    public static AnimationState stateOf(Boss boss) {
        if (!boss.alive()) {
            return AnimationState.DEAD;
        }
        //  Read through Boss's uniform display surface, never a type test:
        //  every one of these has a neutral default, so a boss it does not apply
        //  to simply answers no.  See Boss's "uniform display surface" section.
        if (boss.venting()) {
            return AnimationState.BREATHE;
        }
        if (boss.reeling() > 0d) {
            return AnimationState.REEL;
        }
        if (!boss.regaliaAttached()) {
            return boss.bossType() == BossType.LICH_LORD
                    ? AnimationState.DISARMED : AnimationState.RETRIEVE;
        }
        if (boss.hurtFlash() > 0f) {
            return AnimationState.HURT;
        }
        return AnimationState.WALK;
    }

    // ========================================================================
    //  The Troll King
    // ========================================================================

    private void trollKing(RenderContext ctx, Boss b, Color col, float left,
                           float bottom, float w, float h) {
        float cx = left + w / 2f;
        float top = bottom + h;

        legs(ctx, b, ctx.shade(col, 0.65f), cx, bottom, 20f, 16f);
        ctx.kit.ellipse(left, bottom, w, h - 26f, col);
        ctx.kit.ellipse(left + 22f, top - 50f, 44f, 40f, ctx.shade(col, 1.2f));

        for (int s = -1; s <= 1; s += 2) {
            ctx.kit.circle(cx + s * 9f, top - 26f, 5f, Palette.rgb(250, 220, 90));
            ctx.kit.circle(cx + s * 9f, top - 26f, 2f, Palette.rgb(30, 26, 20));
            ctx.kit.triangle(cx + s * 12f, top - 38f, cx + s * 8f, top - 38f,
                    cx + s * 10f, top - 28f, Palette.rgb(238, 236, 220));  // tusks
        }
        //  The crown is on his head only while gameplay says he has it.
        if (b.regaliaAttached()) {
            poly[0] = cx - 20f; poly[1] = top - 12f;
            poly[2] = cx + 20f; poly[3] = top - 12f;
            poly[4] = cx + 16f; poly[5] = top + 4f;
            poly[6] = cx + 8f;  poly[7] = top - 6f;
            poly[8] = cx;       poly[9] = top + 8f;
            poly[10] = cx - 8f; poly[11] = top - 6f;
            poly[12] = cx - 16f; poly[13] = top + 4f;
            ctx.kit.polygon(poly, 14, Palette.GOLD);
        }
        //  The club swings on the smash timer, so the arc lands with the damage.
        float ang = -0.9f + b.swing() * 2.2f;
        float hx = left + 6f;
        float hy = bottom + h / 2f;
        float ex = hx + MathUtils.cos(MathUtils.PI - ang) * 54f;
        float ey = hy - MathUtils.sin(MathUtils.PI - ang) * 54f + 10f;
        ctx.kit.line(hx, hy, ex, ey, 10f, Palette.rgb(110, 84, 54));
        ctx.kit.circle(ex, ey, 16f, Palette.rgb(92, 70, 46));
        ctx.kit.circleOutline(ex, ey, 16f, 3f, Palette.rgb(140, 110, 74));
    }

    // ========================================================================
    //  The Dragon
    // ========================================================================

    private void dragon(RenderContext ctx, Boss b, Color col, float left,
                        float bottom, float w, float h) {
        float cx = left + w / 2f;
        float cy = bottom + h / 2f;
        float top = bottom + h;
        float right = left + w;
        float flap = MathUtils.sin(b.anim()) * 26f;

        for (int s = -1; s <= 1; s += 2) {
            poly[0] = cx;               poly[1] = cy + 6f;
            poly[2] = cx + s * 30f;     poly[3] = cy + 52f - flap;
            poly[4] = cx + s * 66f;     poly[5] = cy + 16f - flap * 0.7f;
            poly[6] = cx + s * 28f;     poly[7] = cy - 12f;
            ctx.kit.polygon(poly, 8, ctx.shade(col, 0.62f));           // wings
        }
        ctx.kit.ellipse(left + 20f, bottom + 6f, w - 30f, h - 22f, col);

        poly[0] = right - 14f; poly[1] = cy;
        poly[2] = right + 34f; poly[3] = cy + 16f;
        poly[4] = right + 30f; poly[5] = cy - 6f;
        poly[6] = right - 14f; poly[7] = cy - 12f;
        ctx.kit.polygon(poly, 8, col);                                 // tail

        float hx = left + 14f;
        float hy = cy + 12f;
        ctx.kit.line(left + 34f, cy - 4f, hx, hy, 16f, col);           // neck
        ctx.kit.ellipse(hx - 22f, hy - 12f, 40f, 24f, ctx.shade(col, 1.12f));
        ctx.kit.triangle(hx - 22f, hy + 2f, hx - 36f, hy - 2f, hx - 20f, hy - 8f,
                Palette.rgb(240, 220, 190));                           // jaw
        ctx.kit.circle(hx - 4f, hy + 4f, 4f, Palette.rgb(255, 226, 90));
        ctx.kit.circle(hx - 5f, hy + 4f, 2f, Palette.rgb(30, 20, 16));
        for (int i = 0; i < 5; i++) {
            float sx = left + 34f + i * 14f;
            ctx.kit.triangle(sx, top - 18f, sx + 10f, top - 18f, sx + 5f, top - 4f,
                    Palette.rgb(250, 190, 90));                        // spines
        }
        //  The breath is drawn only while gameplay says it is breathing; the
        //  damage is the breath's, not this loop's.
        if (b.venting()) {
            for (int i = 0; i < 6; i++) {
                float fx = hx - 34f - i * 16f;
                float fy = hy - 4f + MathUtils.sin(b.anim() * 3f + i) * 6f;
                ctx.kit.circle(fx, fy, 10f - i,
                        Palette.rgb(255, Math.max(0, 170 - i * 12), 60));
            }
        }
    }

    // ========================================================================
    //  The Lich Lord
    // ========================================================================

    private void lichLord(RenderContext ctx, Boss b, Color col, float left,
                          float bottom, float w, float h) {
        float cx = left + w / 2f;
        float top = bottom + h;
        float right = left + w;

        //  The ward: the visible reason arrows are doing nothing.  Its presence
        //  is gameplay's shield value.
        if (b.wardStrength() > 0d) {
            ShapeKit.enableBlend();
            ctx.kit.ellipse(cx - (w + 60f) / 2f, bottom + h / 2f - (h + 60f) / 2f,
                    w + 60f, h + 60f,
                    ctx.alpha(Palette.rgb(150, 210, 255), 70f / 255f));
        }
        float hover = MathUtils.sin(b.anim()) * 5f;
        poly[0] = cx;     poly[1] = top - 6f + hover;
        poly[2] = right;  poly[3] = bottom + hover;
        poly[4] = cx;     poly[5] = bottom + 10f + hover;
        poly[6] = left;   poly[7] = bottom + hover;
        ctx.kit.polygon(poly, 8, col);                                 // robe

        float sx = cx;
        float sy = top - 16f + hover;
        ctx.kit.circle(sx, sy, 15f, Palette.rgb(232, 230, 216));       // skull
        for (int s = -1; s <= 1; s += 2) {
            ctx.kit.circle(sx + s * 6f, sy + 2f, 4f, Palette.rgb(24, 46, 40));
            ctx.kit.circle(sx + s * 6f, sy + 2f, 2f, Palette.rgb(120, 255, 210));
        }
        for (int i = 0; i < 3; i++) {
            ctx.kit.line(sx - 5f + i * 5f, sy - 8f, sx - 5f + i * 5f, sy - 13f, 2f,
                    Palette.rgb(60, 56, 50));                          // teeth
        }
        for (int i = -2; i <= 2; i++) {
            ctx.kit.triangle(sx + i * 8f - 3f, sy + 12f, sx + i * 8f + 3f, sy + 12f,
                    sx + i * 8f, sy + 24f, Palette.rgb(168, 140, 220));
        }
        //  The staff is in his hand only while he has it.  Disarmed, the orb and
        //  its glow are simply absent -- there is no faded-out staff.
        if (b.regaliaAttached()) {
            float stx = right + 6f;
            ctx.kit.line(stx, bottom, stx, top + 14f, 5f, Palette.rgb(76, 62, 96));
            float gr = 10f + 7f * b.orbCharge();
            ShapeKit.enableBlend();
            ctx.kit.glow(stx, top + 16f, gr * 2f, Palette.rgb(170, 120, 255),
                    90f / 255f * ctx.quality.glowIntensity());
            ctx.kit.circle(stx, top + 16f, gr, Palette.rgb(196, 150, 255));
            ctx.kit.circle(stx, top + 16f, Math.max(2f, gr - 4f),
                    Palette.rgb(240, 220, 255));
        } else {
            for (int i = 0; i < 3; i++) {
                float a = b.anim() * 3f + i * 2.1f;
                ctx.kit.circle(sx + MathUtils.cos(a) * 22f,
                        sy + 26f + MathUtils.sin(a) * 7f, 3f,
                        Palette.rgb(180, 150, 240));                   // dazed
            }
        }
    }

    // ========================================================================
    //  Shared
    // ========================================================================

    /**
     * The shrinking ward around guarded regalia.
     *
     * <p>The span grows every time the boss is robbed, exactly as the source
     * computes it — otherwise the arc would empty at the wrong rate on the
     * second theft and mislead the player about when to try again.
     */
    private void paintRegaliaWard(RenderContext ctx, Boss boss, float drawX,
                                  float drawY) {
        if (boss.regaliaCd() <= 0d || !boss.regaliaAnchorInto(anchor)) {
            return;
        }
        float ax = anchor[0];
        float ay = anchor[1];
        double span = Math.max(1d,
                com.mymmer.castledefense.config.GameConfig.REGALIA_COOLDOWN
                        * (1d + com.mymmer.castledefense.config.GameConfig.REGALIA_CD_GROWTH
                                * Math.max(0, boss.regaliaTaken() - 1)));
        float frac = (float) MathUtils.clamp(boss.regaliaCd() / span, 0d, 1d);
        ShapeKit.enableBlend();
        //  Starts at twelve o'clock and empties clockwise, as the source's
        //  -pi/2 start and positive sweep do.
        ctx.kit.arcBand(ax, ay, 24f, 3f, 90f - 360f * frac, 360f * frac);
    }

    /** The boss name, in a batch pass. */
    public void paintLabel(RenderContext ctx, Boss boss, float drawX, float drawY) {
        ctx.textCentered(drawX, drawY + boss.height() / 2f + 12f, boss.name(), 22f,
                Palette.rgb(255, 208, 120), true);
        if (boss.bossType() == BossType.TROLL_KING && !boss.regaliaAttached()) {
            ctx.textCentered(drawX, drawY + boss.height() / 2f + 34f,
                    "RETRIEVING CROWN", 18f, Palette.rgb(255, 200, 120), true);
        } else if (boss.bossType() == BossType.LICH_LORD && !boss.regaliaAttached()) {
            ctx.textCentered(drawX, drawY + boss.height() / 2f + 34f,
                    String.format(java.util.Locale.ROOT, "DISARMED  %.1fs",
                            boss.disarmedFor()),
                    20f, Palette.rgb(208, 160, 255), true);
        }
    }

    private void legs(RenderContext ctx, Boss b, Color color, float cx,
                      float bottom, float span, float length) {
        float phase = b.anim();
        for (int s = -1; s <= 1; s += 2) {
            float off = MathUtils.sin(phase + (s < 0 ? 0f : MathUtils.PI)) * 4f;
            ctx.kit.line(cx + s * span, bottom + 2f, cx + s * span + off,
                    bottom - length, 3f, color);
        }
    }

    private Color bodyColour(RenderContext ctx, Boss boss) {
        body.set(baseColour(boss.bossType()));
        if (boss.hurtFlash() > 0f) {
            Palette.mix(body, Color.WHITE, boss.hurtFlash() * 0.75f, body);
        }
        return body;
    }

    public static Color baseColour(BossType type) {
        switch (type) {
            case TROLL_KING: return TROLL_KING;
            case DRAGON:     return DRAGON;
            case LICH_LORD:  return LICH_LORD;
            default:         return Palette.DIM;
        }
    }
}
