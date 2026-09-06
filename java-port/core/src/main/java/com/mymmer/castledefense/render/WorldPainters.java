package com.mymmer.castledefense.render;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.RandomXS128;
import com.mymmer.castledefense.boss.DroppedItem;
import com.mymmer.castledefense.boss.RegaliaKind;
import com.mymmer.castledefense.config.GameConfig;
import com.mymmer.castledefense.defence.Projectile;
import com.mymmer.castledefense.defence.ProjectileKind;
import com.mymmer.castledefense.progress.Weather;
import com.mymmer.castledefense.skill.FireZone;
import com.mymmer.castledefense.skill.Tornado;

/**
 * The smaller world painters: background, projectiles, items, allies, weather
 * and skill effects.
 *
 * <p>Grouped because each is a few dozen lines and none has state worth a class
 * of its own. The large ones — units, bosses, the castle — have their own files.
 */
public final class WorldPainters {

    private static final float GROUND_Y = WorldGeometry.GROUND;
    private final float[] pts = new float[64];
    /** Cosmetic shot history. Set by the world renderer; may be null. */
    private ProjectileTrails trails;

    public void setTrails(ProjectileTrails trails) {
        this.trails = trails;
    }
    private final float[] poly = new float[16];

    // ========================================================================
    //  Background
    // ========================================================================

    /**
     * Sky, hills, ground and scattered scenery.
     *
     * <p>The source builds this once into a surface at startup. Here it is drawn
     * each frame, and every decorative position comes from
     * {@link VisualRng#stable} keyed to a fixed seed — so the same stars and the
     * same tufts of grass appear in the same places every frame and every run,
     * and none of it touches the gameplay generator.
     */
    public void paintBackground(RenderContext ctx) {
        float w = GameConfig.WORLD_WIDTH;
        float h = GameConfig.WORLD_HEIGHT;

        //  Sky: one gradient quad rather than a stack of bands.  Banding it
        //  left visible seams where adjacent rows met -- and ShapeRenderer
        //  interpolates per vertex, so the smooth version is also one draw call
        //  instead of twenty-four.
        ctx.kit.gradientRect(0f, GROUND_Y, w, h - GROUND_Y,
                Palette.SKY_BOT, Palette.SKY_TOP);
        RandomXS128 r = ctx.rng.stable(90210L);
        for (int i = 0; i < 60; i++) {
            float sx = VisualRng.uniform(r, 0f, w);
            float sy = VisualRng.uniform(r, GROUND_Y + 140f, h - 8f);
            float b = VisualRng.uniform(r, 0.35f, 0.9f);
            ctx.kit.circle(sx, sy, VisualRng.uniform(r, 0.8f, 1.8f),
                    ctx.alpha(Palette.WHITE, b));
        }
        //  Distant hills, two ridges, darker at the back.
        hills(ctx, r, GROUND_Y + 96f, 0.55f, 7);
        hills(ctx, r, GROUND_Y + 54f, 0.75f, 9);

        //  Ground.
        ctx.kit.rect(0f, 0f, w, GROUND_Y, Palette.GROUND_DARK);
        ctx.kit.rect(0f, GROUND_Y - 26f, w, 26f, Palette.GROUND);
        ctx.kit.rect(0f, GROUND_Y - 4f, w, 4f, Palette.DIRT);
        for (int i = 0; i < 120; i++) {
            float gx = VisualRng.uniform(r, 0f, w);
            float gy = VisualRng.uniform(r, 4f, GROUND_Y - 8f);
            ctx.kit.line(gx, gy, gx + VisualRng.uniform(r, -2f, 2f), gy + 5f, 1f,
                    ctx.shade(Palette.GROUND, VisualRng.uniform(r, 0.8f, 1.25f)));
        }
    }

    private void hills(RenderContext ctx, RandomXS128 r, float baseY, float shade,
                       int count) {
        Color col = ctx.shade2(Palette.GROUND_DARK, shade);
        float w = GameConfig.WORLD_WIDTH;
        float step = w / count;
        for (int i = 0; i < count; i++) {
            float cx = i * step + VisualRng.uniform(r, -20f, 20f);
            float peak = VisualRng.uniform(r, 26f, 74f);
            ctx.kit.triangle(cx - step * 0.75f, GROUND_Y, cx + step * 0.75f, GROUND_Y,
                    cx, baseY + peak, col);
        }
    }

    // ========================================================================
    //  Projectiles
    // ========================================================================

    /**
     * {@code Projectile.draw}: one shape per kind.
     *
     * <p>The collision radius is {@code ProjectileKind}'s, never the drawn size —
     * a fatter arrow is still the same arrow to hit with.
     */
    public void paintProjectile(RenderContext ctx, Projectile p, float drawX,
                                float drawY) {
        //  Down in the simulation is up on the screen, so the heading flips too.
        float angle = WorldGeometry.toDrawAngle(p.vx(), p.vy());
        float radius = p.kind().radius();
        Color col = colourFor(p.kind());

        switch (p.kind()) {
            case ARROW: {
                float tx = drawX - MathUtils.cos(angle) * 16f;
                float ty = drawY - MathUtils.sin(angle) * 16f;
                ctx.kit.line(drawX, drawY, tx, ty, 2f, col);
                ctx.kit.circle(drawX, drawY, 2f, Palette.ARROW_TIP);
                break;
            }
            case BOLT: {
                float tx = drawX - MathUtils.cos(angle) * 26f;
                float ty = drawY - MathUtils.sin(angle) * 26f;
                ctx.kit.line(drawX, drawY, tx, ty, 5f, ctx.shade(col, 0.6f));
                ctx.kit.line(drawX, drawY, tx, ty, 2f, col);
                ctx.kit.circle(drawX, drawY, 3f, Palette.BOLT_TIP);
                break;
            }
            case CANNON: {
                //  The trail is cosmetic history, capped and never fed back into
                //  the shot's own motion.  Quality may thin it.
                if (ctx.quality.trails() && trails != null) {
                    int n = trails.length(p.uid());
                    for (int i = 0; i < n; i++) {
                        float tr = Math.max(1f, radius * (i / (float) Math.max(1, n)));
                        ctx.kit.circle(trails.x(p.uid(), i),
                                WorldGeometry.toDrawY(trails.y(p.uid(), i)),
                                tr, Palette.CANNONBALL_TRAIL);
                    }
                }
                ctx.kit.circle(drawX, drawY, radius, Palette.CANNONBALL);
                ctx.kit.circle(drawX - 2f, drawY + 3f, 3f, Palette.CANNONBALL_HI);
                break;
            }
            case MAGIC:
            case FIRE: {
                ShapeKit.enableBlend();
                ctx.kit.glow(drawX, drawY, radius * 3f, col,
                        140f / 255f * ctx.quality.glowIntensity());
                ctx.kit.circle(drawX, drawY, Math.max(2f, radius - 3f),
                        ctx.shade(col, 1.5f));
                break;
            }
            default: {
                ctx.kit.circle(drawX, drawY, radius, col);
                ctx.kit.circleOutline(drawX, drawY, radius, 1f,
                        Palette.rgb(140, 140, 130));
                break;
            }
        }
    }

    /**
     * A projectile's colour.
     *
     * <p>Friendly and hostile shots of the same kind look the same in the source
     * — the distinction the player reads is direction of travel, not tint — so
     * that is preserved rather than "improved".
     */
    private Color colourFor(ProjectileKind kind) {
        switch (kind) {
            case ARROW:  return Palette.rgb(228, 220, 190);
            case BOLT:   return Palette.rgb(206, 150, 84);
            case CANNON: return Palette.CANNONBALL;
            case MAGIC:  return Palette.rgb(170, 120, 255);
            case FIRE:   return Palette.rgb(255, 150, 70);
            case BONE:   return Palette.BONE;
            default:     return Palette.WHITE;
        }
    }

    // ========================================================================
    //  Dropped regalia
    // ========================================================================

    /**
     * A crown or a staff lying on the field, or in flight.
     *
     * <p>Drawn at the <b>item's</b> position, which is the item's own gameplay
     * state. There is deliberately no path by which a detached item is drawn
     * still attached to its boss: the boss painter asks {@code hasCrown()} and
     * this asks the item where it is, so the two cannot disagree.
     */
    public void paintItem(RenderContext ctx, DroppedItem item, float drawX,
                          float drawY) {
        float y = drawY + item.bob();
        float spin = item.spin();
        if (item.kind() == RegaliaKind.CROWN) {
            float w = item.width();
            float h = item.height();
            //  A spinning crown, approximated by squashing it horizontally --
            //  the source rotates the polygon; the read is the same.
            float sq = Math.abs(MathUtils.cos(spin));
            float hw = Math.max(3f, w / 2f * (0.35f + 0.65f * sq));
            poly[0] = drawX - hw;        poly[1] = y - h / 4f;
            poly[2] = drawX + hw;        poly[3] = y - h / 4f;
            poly[4] = drawX + hw * 0.8f; poly[5] = y + h / 2f;
            poly[6] = drawX + hw * 0.4f; poly[7] = y;
            poly[8] = drawX;             poly[9] = y + h / 2f + 3f;
            poly[10] = drawX - hw * 0.4f; poly[11] = y;
            poly[12] = drawX - hw * 0.8f; poly[13] = y + h / 2f;
            ctx.kit.polygon(poly, 14, Palette.GOLD);
            ShapeKit.enableBlend();
            ctx.kit.glow(drawX, y, 22f, Palette.GOLD,
                    0.35f * ctx.quality.glowIntensity());
        } else {
            float len = item.height();
            float dx = MathUtils.cos(spin) * len / 2f;
            float dy = MathUtils.sin(spin) * len / 2f;
            ctx.kit.line(drawX - dx, y - dy, drawX + dx, y + dy, 5f,
                    Palette.rgb(76, 62, 96));
            ctx.kit.circle(drawX + dx, y + dy, 7f, Palette.rgb(196, 150, 255));
            ShapeKit.enableBlend();
            ctx.kit.glow(drawX + dx, y + dy, 20f, Palette.rgb(170, 120, 255),
                    0.4f * ctx.quality.glowIntensity());
        }
    }

    // ========================================================================
    //  Allies
    // ========================================================================

    /**
     * A friendly skeleton, in the ally palette.
     *
     * <p>It is drawn in its own layer, after the enemies, because it is not an
     * enemy — a distinction the gameplay keeps too, in a separate collection the
     * player's towers never target.
     */
    public void paintAlly(RenderContext ctx, float x, float y, float w, float h,
                          float anim, float hp, float maxHp) {
        float left = x - w / 2f;
        float bottom = y - h / 2f;
        float cx = x;
        Color col = Palette.ALLY;

        for (int s = -1; s <= 1; s += 2) {
            float off = MathUtils.sin(anim + (s < 0 ? 0f : MathUtils.PI)) * 4f;
            ctx.kit.line(cx + s * 6f, bottom + 2f, cx + s * 6f + off, bottom - 8f,
                    3f, ctx.shade(col, 0.6f));
        }
        ctx.kit.circle(cx, bottom + h - 6f, 6f, col);
        for (int i = 0; i < 3; i++) {
            float yy = bottom + h - 13f - i * 5f;
            ctx.kit.line(cx - 5f, yy, cx + 5f, yy, 2f, col);
        }
        ctx.kit.line(cx, bottom + h - 12f, cx, bottom + h - 24f, 2f, col);
        ctx.kit.circle(cx - 2f, bottom + h - 5f, 2f, Palette.rgb(40, 90, 70));
        ctx.kit.circle(cx + 2f, bottom + h - 5f, 2f, Palette.rgb(40, 90, 70));
        if (hp < maxHp) {
            ctx.kit.bar(left, bottom + h + 6f, Math.max(20f, w), 4f, hp / maxHp,
                    Palette.ALLY);
        }
    }

    // ========================================================================
    //  Weather
    // ========================================================================

    /**
     * {@code draw_weather}: streaks, bolts, storm flash.
     *
     * <p>Everything here is a picture of a decision gameplay already made.
     * {@link Weather} owns the wind, decides when a storm breaks, chooses which
     * mob a strike hits and applies the damage. Nothing in this method can
     * create a strike, and dropping to LOW quality thins the visuals without
     * touching one.
     */
    public void paintWeather(RenderContext ctx, Weather weather, double time) {
        float wind = weather.wind();
        if (Math.abs(wind) > 40f) {
            //  The streak positions come from a hash of index and time, exactly
            //  as the source's `seed` expression does -- not from a draw, so
            //  they stream smoothly rather than flickering.
            int n = (int) (Math.abs(wind) / 26f);
            if (ctx.quality.maxParticles() < 520) {
                n = Math.min(n, 8);         // LOW: fewer streaks, same wind
            }
            for (int i = 0; i < n; i++) {
                int seed = (int) ((i * 97 + (int) (time * Math.abs(wind) * 0.5)) % 1400);
                float wx = wind > 0f ? (seed - 60f) : (1340f - seed);
                float wy = GameConfig.WORLD_HEIGHT - (90f + (i * 137) % 430);
                float ln = 14f + (i % 3) * 10f;
                ctx.kit.line(wx, wy, wx - Math.copySign(ln, wind), wy, 1f,
                        Palette.WIND);
            }
        }
        if (weather.stormFlash() > 0f) {
            ShapeKit.enableBlend();
            ctx.kit.rect(0f, 0f, GameConfig.WORLD_WIDTH, GameConfig.WORLD_HEIGHT,
                    ctx.alpha(Palette.STORM_VEIL, 70f / 255f * weather.stormFlash()));
        }
    }

    /**
     * One lightning bolt, as a jagged path down from the clouds.
     *
     * <p>The path is random, and the randomness is the <b>decoration</b> stream
     * keyed to the bolt so the same bolt keeps its shape for its whole life. In
     * the source this draw perturbs the gameplay generator; here it cannot.
     *
     * @param x     where gameplay put the strike
     * @param fromY where the bolt starts, in world units
     */
    public void paintBolt(RenderContext ctx, float x, float fromY, long key) {
        RandomXS128 r = ctx.rng.stable(key);
        int n = 0;
        float px = x;
        float py = fromY;
        pts[n++] = px;
        pts[n++] = py;
        while (py < GameConfig.WORLD_HEIGHT + 20f && n < pts.length - 2) {
            py += VisualRng.uniform(r, 24f, 46f);
            px += VisualRng.uniform(r, -26f, 26f);
            pts[n++] = px;
            pts[n++] = py;
        }
        ctx.kit.path(pts, n, 4f, Palette.BOLT_CORE);
        ctx.kit.path(pts, n, 2f, Palette.BOLT_INNER);
    }

    // ========================================================================
    //  Skill effects
    // ========================================================================

    /**
     * {@code FireZone.draw}: seven flames along the ground, fading with the zone.
     *
     * <p>Position, radius and remaining life are all read from the gameplay zone,
     * so the fire cannot outlive the damage or burn a wider patch than it hurts.
     * The individual flame heights wobble on the zone's own {@code phase}, which
     * the simulation advances — so they stop when the world does.
     */
    public void paintFireZone(RenderContext ctx, FireZone zone) {
        float a = MathUtils.clamp(zone.fraction(), 0f, 1f);
        for (int i = 0; i < 7; i++) {
            float fx = zone.x() - zone.radius() + i * (zone.radius() * 2f / 6f);
            float fh = (14f + 12f * MathUtils.sin(zone.phase() + i)) * a;
            ctx.kit.triangle(fx - 7f, GROUND_Y, fx + 7f, GROUND_Y,
                    fx, GROUND_Y + fh + 10f,
                    Palette.rgb(255, Math.max(0, 150 - i * 8), 60));
            ctx.kit.triangle(fx - 3f, GROUND_Y, fx + 3f, GROUND_Y,
                    fx, GROUND_Y + fh * 0.55f + 5f, Palette.FLAME_INNER);
        }
    }

    /**
     * {@code Tornado.draw}: thirteen stacked ellipse outlines and a core line.
     *
     * <p>The funnel is drawn at the tornado's own {@code x}; the pull on caught
     * mobs is computed by the {@link Tornado} from that same value. The rendered
     * funnel is never consulted by the physics — it could not be, since it does
     * not exist outside this method.
     */
    public void paintTornado(RenderContext ctx, Tornado t) {
        float a = (float) MathUtils.clamp(t.life() / Math.max(1e-6, t.maxLife()),
                0d, 1d);
        float top = GROUND_Y + 250f;
        for (int i = 0; i < 13; i++) {
            float f = i / 12f;
            float y = GROUND_Y + f * 250f;
            float w = t.radius() * (0.28f + 0.72f * f) * a;
            float off = MathUtils.sin(t.phase() + f * 5f) * 12f * f;
            Color col = ctx.mix(Palette.rgb(150, 190, 214),
                    Palette.rgb(232, 244, 250), f);
            //  Outlines, not fills -- the source passes width=3, and a filled
            //  funnel would hide the mobs caught inside it.
            ctx.kit.ellipseOutline(t.x() - w + off, y - 8f, w * 2f, 20f, 3f, col);
        }
        ctx.kit.line(t.x(), GROUND_Y, t.x() + MathUtils.sin(t.phase()) * 10f, top,
                2f, Palette.rgb(210, 232, 244));
    }

    /**
     * The aiming reticle while a targeted skill is armed.
     *
     * <p>The radius is the skill's real radius including talent scaling, asked of
     * gameplay — so what the ring shows is what the cast will actually cover.
     */
    public void paintAiming(RenderContext ctx, float x, float radius, Color colour) {
        ctx.kit.circleOutline(x, GROUND_Y + 10f, radius, 2f, colour);
        ctx.kit.line(x, GROUND_Y, x, GameConfig.WORLD_HEIGHT, 1f, colour);
    }
}
