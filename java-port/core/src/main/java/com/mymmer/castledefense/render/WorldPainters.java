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
     * Sky, moon, stars, three ridges of hills, ground and grass.
     *
     * <p>Transcribed from {@code Game._build_background} after the Phase 11.5
     * image comparison, which showed the first attempt had invented sharp
     * triangular hills, dropped the moon and thinned the stars. Reading a draw
     * method is not the same as looking at what it draws.
     *
     * <p>The source builds this once into a surface at startup and seeds the
     * global generator with 7 to place the stars and grass. Here it is drawn
     * each frame from {@link VisualRng#stable}, which gives the same fixed
     * scatter without perturbing any shared stream.
     */
    public void paintBackground(RenderContext ctx) {
        float w = GameConfig.WORLD_WIDTH;

        //  Sky: mix(TOP, BOT, t^0.85) with t running from the top down.  The
        //  exponent matters -- it keeps the horizon warm and the zenith dark,
        //  and a linear ramp reads noticeably washed out.
        final int bands = 96;
        for (int i = 0; i < bands; i++) {
            float t0 = i / (float) bands;
            float t1 = (i + 1) / (float) bands;
            float yTop = GameConfig.WORLD_HEIGHT - t0 * 620f;
            float yBot = GameConfig.WORLD_HEIGHT - t1 * 620f;
            ctx.kit.gradientRect(0f, yBot, w, yTop - yBot,
                    ctx.mix(Palette.SKY_TOP, Palette.SKY_BOT,
                            (float) Math.pow(t1, 0.85f)),
                    ctx.shade2(ctx.mix(Palette.SKY_TOP, Palette.SKY_BOT,
                            (float) Math.pow(t0, 0.85f)), 1f));
        }

        //  Moon, with its two craters.
        float moonY = WorldGeometry.toDrawY(110f);
        ctx.kit.circle(1080f, moonY, 44f, Palette.rgb(238, 236, 214));
        ctx.kit.circle(1064f, WorldGeometry.toDrawY(100f), 8f,
                Palette.rgb(216, 214, 196));
        ctx.kit.circle(1098f, WorldGeometry.toDrawY(126f), 6f,
                Palette.rgb(216, 214, 196));

        //  Stars: 140 of them, in the top 380 pixels, from the source's seed 7.
        RandomXS128 r = ctx.rng.stable(7L);
        for (int i = 0; i < 140; i++) {
            float sx = VisualRng.range(r, 0, (int) w);
            float sy = WorldGeometry.toDrawY(VisualRng.range(r, 0, 380));
            float radius = (i % 4 == 3) ? 2f : 1f;
            int c = VisualRng.range(r, 150, 235);
            ctx.kit.circle(sx, sy, radius, Palette.rgb(c, c, Math.max(0, c - 10)));
        }

        //  Three ridges, each a sum of two sines sampled every 40 px.  Drawn as
        //  quads down to the ground because a fan-filled polygon of this shape
        //  is concave and would tear.
        hills(ctx, Palette.rgb(44, 48, 72), 470f, 60f, 0);
        hills(ctx, Palette.rgb(38, 44, 62), 520f, 44f, 1);
        hills(ctx, Palette.rgb(32, 40, 50), 560f, 30f, 2);

        //  Ground: a lighter band above the line, darker below, with the source's
        //  3 px rim on top.
        ctx.kit.rect(0f, 0f, w, GROUND_Y + 18f, Palette.GROUND);
        ctx.kit.rect(0f, 0f, w, GROUND_Y - 6f, Palette.GROUND_DARK);
        ctx.kit.rect(0f, GROUND_Y + 18f - 3f, w, 3f, Palette.rgb(86, 104, 66));
        for (int i = 0; i < 260; i++) {
            float gx = VisualRng.range(r, 0, (int) w);
            float gy = WorldGeometry.toDrawY(
                    VisualRng.range(r, (int) 620f - 16, (int) GameConfig.WORLD_HEIGHT - 4));
            ctx.kit.line(gx, gy, gx + VisualRng.range(r, -2, 2), gy + 5f, 2f,
                    ctx.shade(Palette.GROUND, VisualRng.uniform(r, 0.7f, 1.3f)));
        }
    }

    /**
     * One ridge: {@code base + sin(x*0.006 + layer*2.1)*amp + sin(x*0.017 + layer)*amp*0.35}.
     *
     * <p>Two sines at different frequencies is what makes the silhouette read as
     * rolling hills rather than as a repeating wave — and nothing like the
     * triangles the first attempt used.
     */
    private void hills(RenderContext ctx, Color col, float base, float amp,
                       int layer) {
        float prevX = 0f;
        float prevY = ridge(0f, base, amp, layer);
        for (float x = 40f; x <= GameConfig.WORLD_WIDTH + 40f; x += 40f) {
            float y = ridge(x, base, amp, layer);
            //  A quad from the ground up to the ridge, one per sample step.
            ctx.kit.quadFill(prevX, 0f, x, 0f, x, y, prevX, prevY, col);
            prevX = x;
            prevY = y;
        }
    }

    private static float ridge(float x, float base, float amp, int layer) {
        float y = base
                + MathUtils.sin(x * 0.006f + layer * 2.1f) * amp
                + MathUtils.sin(x * 0.017f + layer) * amp * 0.35f;
        return WorldGeometry.toDrawY(y);
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
    }

    /**
     * The white-out over everything, after a strike.
     *
     * <p>Separate from the streaks because the bolts belong <b>between</b> them:
     * the source draws wind, then every bolt, then the veil over the lot, and a
     * bolt painted on top of its own flash would read as a different effect.
     */
    public void paintStormVeil(RenderContext ctx, Weather weather) {
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
