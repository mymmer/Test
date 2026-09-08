package com.mymmer.castledefense.render;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.utils.Array;
import com.mymmer.castledefense.boss.Boss;
import com.mymmer.castledefense.game.GameState;
import com.mymmer.castledefense.game.RunWorld;
import com.mymmer.castledefense.progress.Announcements;
import com.mymmer.castledefense.shop.Shop;
import com.mymmer.castledefense.shop.ShopItemDef;
import com.mymmer.castledefense.skill.SkillId;
import com.mymmer.castledefense.talent.TalentBranch;
import com.mymmer.castledefense.talent.TalentDef;
import com.mymmer.castledefense.talent.TalentTree;
import com.mymmer.castledefense.text.Strings;
import com.mymmer.castledefense.ui.HudScreen;
import com.mymmer.castledefense.ui.SafeArea;
import com.mymmer.castledefense.ui.ShopScreen;
import com.mymmer.castledefense.ui.TalentScreen;
import com.mymmer.castledefense.ui.TextLayout;
import com.mymmer.castledefense.ui.UiRect;
import com.mymmer.castledefense.ui.UiRoot;

/**
 * Paints the interface. Chrome only — entities and effects are Phase 11.
 *
 * <h2>It draws what the layout decided</h2>
 *
 * <p>Every rectangle on screen comes from a {@link UiRect} that
 * {@code UiRoot.layout()} has already positioned, and every number comes from
 * the subsystem that owns it, fetched at draw time. This class computes no
 * positions and caches no values. That separation is why the layout can be
 * tested headlessly at seven screen shapes and why what those tests assert is
 * genuinely what a player sees.
 *
 * <h2>Visual bounds, not hit bounds</h2>
 *
 * <p>A control has two rectangles: what it looks like and what it accepts a
 * finger inside. Only the first is drawn. A small button enlarged to the 44-unit
 * touch minimum must still <em>look</em> small, or the enlargement becomes
 * visible as a wrong-sized button. The debug overlay draws both, which is the
 * point of having one.
 *
 * <h2>The font</h2>
 *
 * <p>libGDX's built-in {@link BitmapFont} — the same choice
 * {@code FoundationRenderer} already makes, and for the same reason: it needs no
 * asset, no FreeType dependency and no packing step, so Phase 10 can be verified
 * end to end before Phase 11 introduces the game's real typeface. It is scaled
 * per draw rather than regenerated, which is exactly what a bitmap font is bad
 * at, and is the main thing Phase 11 changes.
 *
 * <p>Its metrics are published through {@link #measurer()}, so the layout is
 * measured with the font that will actually draw it rather than an estimate.
 */
public final class UiRenderer {

    // Palette. Deliberately flat: Phase 11 owns the game's actual look.
    private static final Color PANEL = new Color(0.06f, 0.07f, 0.11f, 0.88f);
    private static final Color PANEL_EDGE = new Color(0.36f, 0.40f, 0.52f, 1f);
    private static final Color BUTTON = new Color(0.13f, 0.16f, 0.24f, 1f);
    private static final Color BUTTON_SELECTED = new Color(0.20f, 0.30f, 0.46f, 1f);
    private static final Color BUTTON_DISABLED = new Color(0.10f, 0.11f, 0.14f, 1f);
    private static final Color GOLD = new Color(0.97f, 0.79f, 0.31f, 1f);
    private static final Color TEXT = new Color(0.92f, 0.93f, 0.96f, 1f);
    private static final Color TEXT_DIM = new Color(0.55f, 0.58f, 0.66f, 1f);
    private static final Color HEALTH = new Color(0.42f, 0.72f, 0.35f, 1f);
    private static final Color HEALTH_LOW = new Color(0.78f, 0.28f, 0.24f, 1f);
    /** The source's boss bar red, from draw_bar(..., (208, 62, 60)). */
    private static final Color BOSS = Palette.rgb(208, 62, 60);
    private static final Color SHADE = new Color(0f, 0f, 0f, 0.62f);

    /** The size the built-in font was designed at. Scaling is relative to it. */
    private static final float BASE_FONT = 15f;

    private final RunWorld run;
    private final UiRoot ui;

    /**
     * The world's shake offset, applied to the two widgets that share it.
     *
     * <p>{@code Game.draw} paints the skill bar, the horn and the grab cursor
     * into the world surface and only the stat panel and the menus onto the
     * unshaken screen. So those move with a castle hit and the rest of the
     * interface does not — reproduced rather than tidied, because a HUD that
     * shakes as one is a different game to look at.
     *
     * <p>Only the <b>drawing</b> moves. The hit rectangles are the ones the
     * layout produced, so a shaking button is still pressed where it was laid
     * out — exactly as in the source, whose click tests use the fixed rect.
     */
    private float shakeX;
    private float shakeY;

    private ShapeRenderer shapes;
    private SpriteBatch batch;
    private BitmapFont font;
    private final GlyphLayout glyphs = new GlyphLayout();
    /** Scratch for a banner's fade, so a frame allocates no Color. */
    private final Color fade = new Color();
    private final com.badlogic.gdx.math.Matrix4 baseMatrix =
            new com.badlogic.gdx.math.Matrix4();
    private final com.badlogic.gdx.math.Matrix4 shakeMatrix =
            new com.badlogic.gdx.math.Matrix4();
    private FontMeasurer measurer;

    public UiRenderer(RunWorld run, UiRoot ui) {
        if (run == null || ui == null) {
            throw new IllegalArgumentException("run and ui must not be null");
        }
        this.run = run;
        this.ui = ui;
    }

    /** This frame's world shake, from the world renderer. */
    public void setWorldShake(float dx, float dy) {
        this.shakeX = dx;
        this.shakeY = dy;
    }

    public void create() {
        shapes = new ShapeRenderer();
        batch = new SpriteBatch();
        font = new BitmapFont();
        font.setUseIntegerPositions(false);
        measurer = new FontMeasurer(font, glyphs);
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

    /**
     * Real metrics for the layout, from the font that will draw it.
     *
     * <p>Handed to {@code UiRoot.setTextLayout} once GL exists. Before that the
     * interface measures with an estimate, which is fine because it lays out
     * again every frame.
     */
    public TextLayout.Measurer measurer() {
        return measurer;
    }

    // ========================================================================
    //  Drawing
    // ========================================================================

    public void render(ViewportSet viewports) {
        viewports.getUi().apply();
        baseMatrix.set(viewports.getUiCamera().combined);
        shapes.setProjectionMatrix(baseMatrix);
        batch.setProjectionMatrix(baseMatrix);

        GameState state = run.world().state();
        switch (state) {
            case MENU:      drawMenu(); break;
            case SETTINGS:  drawSettings(); break;
            case SHOP:      drawShop(); break;
            case TALENTS:   drawTalents(); break;
            case PAUSED:    drawHud(); drawPause(); break;
            case GAMEOVER:  drawHud(); drawGameOver(); break;
            default:        drawHud(); break;
        }
    }

    // --- the field ----------------------------------------------------------

    private void drawHud() {
        HudScreen hud = ui.hud();

        fill(hud.panelX(), hud.panelY(), hud.panelWidth(), hud.panelHeight(),
                PANEL, PANEL_EDGE);
        drawStatRows(hud);

        //  The skill bar and the horn shake with the world; the stat panel,
        //  the SHOP button and the boss bars do not.  See RENDERING.md §5.
        beginShaken();
        for (int i = 0; i < hud.skillSlots().size; i++) {
            drawSkillSlot(hud.skillSlots().get(i), SkillId.values()[i]);
        }
        if (hud.hornButton().visible()) {
            drawHorn(hud.hornButton());
        }
        endShaken();
        drawFieldReadout();
        drawBanners();
        drawHint();
        //  The Endless SHOP button is row 7 of the stat panel, and draw_hud
        //  paints the whole panel onto the UNSHAKEN screen -- so it must not
        //  move.  It was inside the shaken block until the Phase 11.5 audit
        //  read draw_clock and found it there.
        if (hud.shopButton().visible()) {
            button(hud.shopButton(), Strings.get("hud.shop"), BUTTON, GOLD, 13f);
        }
        //  Boss bars are Phase 10 UI and stay put: the source draws them on the
        //  unshaken screen, and a health bar that jitters is unreadable exactly
        //  when it matters most.
        for (int i = 0; i < hud.visibleBossBars(); i++) {
            drawBossBar(hud.bossBars().get(i), i);
        }
    }

    /**
     * Translates the projection by the world shake, for the widgets that share it.
     *
     * <p>A matrix nudge rather than an offset threaded through every draw call:
     * it cannot be forgotten in one of them, and it is undone in exactly one
     * place.
     */
    private void beginShaken() {
        if (shakeX == 0f && shakeY == 0f) {
            return;
        }
        shakeMatrix.set(baseMatrix).translate(shakeX, shakeY, 0f);
        shapes.setProjectionMatrix(shakeMatrix);
        batch.setProjectionMatrix(shakeMatrix);
    }

    private void endShaken() {
        if (shakeX == 0f && shakeY == 0f) {
            return;
        }
        shapes.setProjectionMatrix(baseMatrix);
        batch.setProjectionMatrix(baseMatrix);
    }

    /**
     * The measured row stack, in the order the layout produced it.
     *
     * <p>Driven by {@code hud.rows()} rather than by a fixed sequence here, so a
     * row the layout chose not to add — the crowd multiplier below its
     * threshold, the Endless clock in a Classic run — simply is not drawn, and
     * the rows below it move up exactly as the layout said they would.
     */
    private void drawStatRows(HudScreen hud) {
        float x = hud.panelX() + HudScreen.PANEL_PAD;
        float right = hud.panelX() + hud.panelWidth() - HudScreen.PANEL_PAD;

        //  Every row is drawn at the TOP edge the layout assigned it, rather
        //  than by walking a running y.  The walk had the gap applied AFTER the
        //  text was drawn, so each text row abutted the one above it -- which is
        //  why SCORE sat on the castle health bar.  Reading the layout's own
        //  rectangles also makes the spacing something a test can assert.
        Array<String> rows = hud.rows();
        for (int i = 0; i < rows.size; i++) {
            String row = rows.get(i);
            float top = hud.rowTop(row);
            if ("hud.row.title".equals(row)) {
                text(x, top, titleText(), TEXT, 30f, false, false, true);
                text(right, top, String.valueOf(run.session().gold()) + " G",
                        GOLD, 26f, true, false, true);
            } else if ("hud.row.badge".equals(row)) {
                text(right, top, badgeText(), TEXT_DIM, 17f, true, false, true);
            } else if ("hud.row.wall".equals(row)) {
                text(x, top, run.castle().tierLabel(), TEXT_DIM, 18f, false);
            } else if ("hud.row.multiplier".equals(row)) {
                //  Two placeholders: the multiplier and the head count that
                //  earned it.  Passing one left a literal {1} on the HUD.
                text(x, top, Strings.format("hud.multiplier",
                        String.format(java.util.Locale.ROOT, "%.2f",
                                run.goldMultiplier()),
                        run.aliveEnemyCount()), GOLD, 18f, false, false, true);
            } else if ("hud.row.health".equals(row)) {
                drawHealth(x, hud.rowBottom(row), right - x);
            } else if ("hud.row.score".equals(row)) {
                text(x, top, Strings.format("hud.score", run.session().score()),
                        TEXT, 28f, false, false, true);
            } else if ("hud.row.talents".equals(row)) {
                text(x, top, Strings.format("hud.talentPoints",
                        run.talentTree().availablePoints()), TEXT_DIM, 19f, false);
            } else if ("hud.row.clock".equals(row)) {
                text(x, top, clockText(), Palette.rgb(150, 220, 255), 24f,
                        false, false, true);
            }
        }
    }

    private void drawHealth(float x, float y, float width) {
        float hp = run.castle().hp();
        float max = Math.max(1f, run.castle().maxHp());
        float frac = Math.max(0f, Math.min(1f, hp / max));
        fill(x, y, width, 18f, BUTTON_DISABLED, PANEL_EDGE);
        fill(x + 1f, y + 1f, (width - 2f) * frac, 16f,
                frac < 0.30f ? HEALTH_LOW : HEALTH, null);
        text(x + width / 2f, y + 14f, ((int) Math.ceil(hp)) + " / " + ((int) max),
                TEXT, 14f, false, true);
    }

    private void drawSkillSlot(UiRect slot, SkillId id) {
        if (!slot.visible()) {
            return;
        }
        boolean ready = run.skills().isReady(id);
        boolean armed = run.skills().aiming() == id;
        fill(slot.visualX(), slot.visualY(), slot.visualWidth(), slot.visualHeight(),
                armed ? BUTTON_SELECTED : (ready ? BUTTON : BUTTON_DISABLED),
                PANEL_EDGE);

        //  The cooldown shade is a fraction of the panel's own numbers -- never
        //  a timer of this class's, which would drift the moment the world froze.
        if (!ready) {
            double remaining = run.skills().cooldownRemaining(id);
            double total = Math.max(1e-9, run.skills().fullCooldown(id));
            float frac = (float) Math.max(0d, Math.min(1d, remaining / total));
            fill(slot.visualX(), slot.visualY(), slot.visualWidth(),
                    slot.visualHeight() * frac, SHADE, null);
            text(slot.centerX(), slot.centerY() + 6f,
                    String.valueOf((int) Math.ceil(remaining)), TEXT, 18f, false, true);
        }
        text(slot.centerX(), slot.visualY() + 14f,
                Strings.get("skill." + id.id() + ".short"), ready ? TEXT : TEXT_DIM,
                12f, false, true);
    }

    /**
     * The top-right readout: what is left, what has been killed, what it cost.
     *
     * <p>Missing entirely until the Phase 11.5 image comparison — the source
     * draws it in {@code draw_hud} and it is the only place the player sees
     * enemies remaining, kills, throw damage and the wind. Right-aligned to the
     * safe rectangle rather than to the screen edge, so a cutout does not eat it.
     */
    private void drawFieldReadout() {
        GameState state = run.world().state();
        if (state != GameState.PLAYING && state != GameState.PAUSED) {
            return;
        }
        SafeArea safe = ui.safeArea();
        float right = safe.x + safe.width - 20f;
        float y = safe.top() - 18f;

        //  What is on the field plus what is still queued to walk on, which
        //  is what "enemies left" means to a player in Classic.
        int queued = run.director() == null ? 0 : run.director().pendingSpawns();
        text(right, y, Strings.format("hud.enemiesLeft",
                run.aliveEnemyCount() + queued), TEXT_DIM, 22f, true);
        text(right, y - 24f, Strings.format("hud.kills", run.session().kills()),
                TEXT_DIM, 20f, true);
        text(right, y - 46f, Strings.format("hud.throwDamage",
                (int) run.session().thrownDamage()), Palette.rgb(255, 190, 120),
                20f, true, false, true);
        float wy = y - 92f;
        float wind = run.weather().wind();
        if (Math.abs(wind) > 40f) {
            //  The player cannot feel the wind until a throw goes wrong; this
            //  label is the warning, and it was absent.
            text(right, wy, Strings.get(wind > 0f ? "hud.tailwind" : "hud.headwind"),
                    wind > 0f ? Palette.rgb(150, 220, 255) : Palette.rgb(255, 180, 140),
                    20f, true, false, true);
            wy -= 22f;
        }
        if (run.weather().storm()) {
            text(right, wy, Strings.get("hud.thunderstorm"),
                    Palette.rgb(200, 220, 255), 20f, true, false, true);
        }
    }

    /**
     * The announcement banners, centred below the top of the screen.
     *
     * <p>Phase 8 built {@code Announcements} and nothing ever drew it. Every
     * wave name, weather change and boss arrival went unseen. They fade with
     * {@code min(1, a * 2.2)} — the source holds them at full opacity for most
     * of their life and drops them quickly at the end.
     */
    private void drawBanners() {
        Announcements banners = run.banners();
        if (banners == null) {
            return;
        }
        SafeArea safe = ui.safeArea();
        float y = safe.top() - 130f;
        for (int i = 0; i < banners.size(); i++) {
            Announcements.Banner b = banners.get(i);
            //  min(1, a * 2.2): held at full opacity for most of its life and
            //  dropped quickly at the end, as the source fades them.
            float a = (float) Math.min(1d,
                    b.remaining() / Math.max(0.001d, b.life) * 2.2d);
            text(safe.centerX(), y, textFor(b),
                    Palette.alpha(colourFor(b.id), a, fade), 34f, false, true, true);
            y -= 40f;
        }
    }

    /**
     * A banner's text.
     *
     * <p>Phase 8 deliberately stored an {@code Id} and its arguments rather than
     * a finished string, so the wording is a localisation concern and the
     * gameplay never holds English. This is where the two meet.
     */
    private String textFor(Announcements.Banner b) {
        String key = bannerKey(b.id);
        if (!b.subject.isEmpty()) {
            //  A subject is an enemy, boss or skill id -- localise it too, so a
            //  banner never shows a raw identifier.
            return Strings.format(key, Strings.get(subjectKey(b)));
        }
        return b.amount != 0 ? Strings.format(key, b.amount) : Strings.get(key);
    }

    private String subjectKey(Announcements.Banner b) {
        switch (b.id) {
            case BOSS_APPROACHES:
            case BOSS_ARRIVES:
                return "boss." + b.subject;
            case SKILL_UNLOCKED:
                return "skill." + b.subject + ".name";
            case NEW_FOE:
                return "enemy." + b.subject + ".name";
            default:
                return b.subject;
        }
    }

    /** {@code WAVE_START} to {@code banner.waveStart}. */
    private static String bannerKey(Announcements.Id id) {
        StringBuilder out = new StringBuilder("banner.");
        boolean upper = false;
        String name = id.name();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '_') {
                upper = true;
                continue;
            }
            out.append(upper ? Character.toUpperCase(c) : Character.toLowerCase(c));
            upper = false;
        }
        return out.toString();
    }

    /**
     * A banner's colour, from the source's own {@code announce} call sites.
     *
     * <p>Not invented: every one of these is the literal triple Python passes.
     * The colour is half of what a banner communicates — a cyan TAILWIND reads
     * as good news and a red boss arrival as bad, before the words are read.
     */
    private Color colourFor(Announcements.Id id) {
        switch (id) {
            case WEATHER_TAILWIND:  return Palette.rgb(150, 220, 255);
            case WEATHER_HEADWIND:  return Palette.rgb(255, 180, 140);
            case WEATHER_STORM:     return Palette.rgb(200, 220, 255);
            case ENDLESS_BEGIN:     return Palette.rgb(120, 214, 240);
            case ENDLESS_SUBTITLE:  return Palette.DIM;
            case BOSS_APPROACHES:
            case BOSS_ARRIVES:      return Palette.rgb(255, 120, 100);
            case BOSS_HINT:         return Palette.rgb(255, 190, 150);
            case WAVE_START:
            case TIER_REACHED:
            case ENDGAME_TIER:
            case HORN_CALLED:
            case HORN_ELITES:
            case SKILL_UNLOCKED:
            case TALENT_POINTS:
            case WAVE_CLEARED:      return Palette.GOLD;
            default:                return TEXT;
        }
    }

    /**
     * The control hint along the bottom, PLAYING only.
     *
     * <p>Two versions of it. The source's line names the left mouse button and
     * the P key, neither of which exists on a phone; a touch device gets the
     * same three instructions in its own vocabulary. Nothing is dropped —
     * pausing is still mentioned, because Back is how it is done — and the
     * choice follows the platform, not the build.
     */
    private void drawHint() {
        if (run.world().state() != GameState.PLAYING) {
            return;
        }
        SafeArea safe = ui.safeArea();
        text(safe.centerX(), safe.y + 24f,
                Strings.get(touch ? "hud.hint.touch" : "hud.hint.desktop"),
                TEXT_DIM, 18f, false, true);
    }

    /** Whether this device is driven by fingers rather than a mouse. */
    private boolean touch = com.badlogic.gdx.Gdx.app != null
            && com.badlogic.gdx.Gdx.app.getType()
                    == com.badlogic.gdx.Application.ApplicationType.Android;

    /** Forces the touch or desktop instruction set. For tests and the launcher. */
    public void setTouchInstructions(boolean touch) {
        this.touch = touch;
    }

    /**
     * {@code draw_horn}: a brass disc with a curled horn on it.
     *
     * <p>Phase 10 drew this as a labelled rectangle, which the Phase 11.5 image
     * comparison showed both differs from the source and cannot fit its own
     * label in the source's 58x60 box — it rendered as "CHALLE...". The source
     * draws an icon and puts the word underneath.
     */
    private void drawHorn(UiRect r) {
        boolean spent = run.session().hornUsed();
        Color base = spent ? Palette.rgb(96, 84, 60) : Palette.rgb(168, 138, 78);
        Color ink = spent ? Palette.rgb(130, 120, 100) : Palette.rgb(250, 238, 206);
        float cx = r.centerX();
        float cy = r.centerY();

        com.badlogic.gdx.Gdx.gl.glEnable(com.badlogic.gdx.graphics.GL20.GL_BLEND);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(Palette.rgb(54, 48, 40));
        shapes.circle(cx, cy, 26f, 28);
        shapes.setColor(base);
        shapes.circle(cx, cy, 23f, 28);
        //  The curled horn glyph: an arc band with a flared bell at its end.
        shapes.setColor(ink);
        for (int i = 0; i < 18; i++) {
            float a0 = (28f + 212f * i / 18f) * com.badlogic.gdx.math.MathUtils.degRad;
            float a1 = (28f + 212f * (i + 1) / 18f)
                    * com.badlogic.gdx.math.MathUtils.degRad;
            float inner = 11f;
            float outer = 16f;
            shapes.triangle(
                    cx + com.badlogic.gdx.math.MathUtils.cos(a0) * inner,
                    cy + com.badlogic.gdx.math.MathUtils.sin(a0) * inner,
                    cx + com.badlogic.gdx.math.MathUtils.cos(a0) * outer,
                    cy + com.badlogic.gdx.math.MathUtils.sin(a0) * outer,
                    cx + com.badlogic.gdx.math.MathUtils.cos(a1) * outer,
                    cy + com.badlogic.gdx.math.MathUtils.sin(a1) * outer);
            shapes.triangle(
                    cx + com.badlogic.gdx.math.MathUtils.cos(a0) * inner,
                    cy + com.badlogic.gdx.math.MathUtils.sin(a0) * inner,
                    cx + com.badlogic.gdx.math.MathUtils.cos(a1) * outer,
                    cy + com.badlogic.gdx.math.MathUtils.sin(a1) * outer,
                    cx + com.badlogic.gdx.math.MathUtils.cos(a1) * inner,
                    cy + com.badlogic.gdx.math.MathUtils.sin(a1) * inner);
        }
        shapes.triangle(cx + 9f, cy + 11f, cx + 19f, cy + 17f, cx + 14f, cy + 4f);
        shapes.end();

        shapes.begin(ShapeRenderer.ShapeType.Line);
        shapes.setColor(Palette.shade(base, 0.6f, hornEdge));
        shapes.circle(cx, cy, 23f, 28);
        shapes.end();

        //  The word goes UNDER the disc, as the source puts it, which is why the
        //  icon does not have to carry a label it cannot fit.
        text(cx, r.visualY() - 4f, Strings.get(spent ? "hud.hornSpent" : "hud.horn"),
                spent ? TEXT_DIM : GOLD, 15f, false, true);
    }

    private final Color hornEdge = new Color();

    private void drawBossBar(UiRect bar, int index) {
        Array<Boss> live = run.bossRegistry().liveBosses();
        if (index >= live.size) {
            return;
        }
        Boss boss = live.get(index);
        float frac = Math.max(0f, Math.min(1f, boss.hp() / Math.max(1f, boss.maxHp())));
        //  The source's own colours: a red bar on the dark backing, the name
        //  ABOVE it and the numbers ON it.  Phase 10 had a magenta bar with the
        //  name inside and no numbers.
        fill(bar.visualX(), bar.visualY(), bar.visualWidth(), bar.visualHeight(),
                Palette.BAR_BACK, Palette.BAR_BORDER);
        fill(bar.visualX() + 2f, bar.visualY() + 2f,
                (bar.visualWidth() - 4f) * frac, bar.visualHeight() - 4f,
                BOSS, null);
        //  Anchored exactly where draw_hud puts them: the name at HEIGHT-78
        //  and the hit points at HEIGHT-48, both centred on the bar.
        SafeArea safe = ui.safeArea();
        text(bar.centerX(), safe.y + HudScreen.BOSS_NAME_TOP,
                Strings.get("boss." + boss.bossType().id()),
                Palette.rgb(255, 210, 130), 24f, false, true, true);
        text(bar.centerX(), safe.y + HudScreen.BOSS_HP_TOP,
                ((int) boss.hp()) + " / " + ((int) boss.maxHp()), TEXT, 18f,
                false, true);
    }

    // --- the modal screens --------------------------------------------------

    private void drawMenu() {
        SafeArea safe = ui.safeArea();
        title(safe, Strings.get("menu.title"));
        subtitle(safe, Strings.format("menu.best", ui.saveData().highScore));
        button(ui.menu().classicButton(), Strings.get("mode.classic"));
        button(ui.menu().endlessButton(), Strings.get("mode.endless"));
        button(ui.menu().settingsButton(), Strings.get("menu.settings"));
        text(safe.centerX(), safe.y + 40f, Strings.format("menu.difficulty",
                Strings.get("difficulty." + ui.preferredDifficulty().id())),
                TEXT_DIM, 18f, false, true);
    }

    private void drawSettings() {
        SafeArea safe = ui.safeArea();
        title(safe, Strings.get("settings.title"));
        button(ui.settings().muteButton(),
                Strings.get(ui.saveData().muted ? "settings.unmute" : "settings.mute"));
        for (int i = 0; i < ui.settings().difficultyButtons().size; i++) {
            UiRect b = ui.settings().difficultyButtons().get(i);
            button(b, Strings.get("difficulty."
                    + ui.difficulties().all().get(i).id()));
        }
        button(ui.settings().clearScoreButton(), Strings.get("settings.clearScore"));
        button(ui.settings().backButton(), Strings.get("common.back"));
    }

    private void drawShop() {
        SafeArea safe = ui.safeArea();
        ShopScreen shop = ui.shop();
        title(safe, Strings.get("shop.title"));
        subtitle(safe, Strings.format("shop.gold", run.session().gold()));

        Array<ShopItemDef> items = run.shop().items();
        for (int i = 0; i < items.size; i++) {
            drawCard(shop.card(items.get(i).id), items.get(i).id, i);
        }
        button(shop.talentsButton(),
                Strings.format("shop.talents", run.talentTree().availablePoints()));
        button(shop.startButton(), run.session().isEndless()
                ? Strings.get("shop.resume")
                : Strings.format("shop.start", run.session().wave()));
    }

    private void drawCard(UiRect card, String itemId, int index) {
        if (card == null || !card.visible()) {
            return;
        }
        //  Availability and affordability are the shop's answers, asked now.
        Shop.ItemView view = ui.shop().cardView(itemId);
        Color face = card.state() == UiRect.State.SELECTED ? BUTTON_SELECTED
                : (view.buyable() ? BUTTON : BUTTON_DISABLED);
        fill(card.visualX(), card.visualY(), card.visualWidth(), card.visualHeight(),
                face, PANEL_EDGE);

        float pad = 8f;
        float x = card.visualX() + pad;
        float top = card.visualY() + card.visualHeight() - pad;
        text(x, top, (index + 1) % 10 + ". " + Strings.get("shop." + itemId + ".name"),
                view.buyable() ? TEXT : TEXT_DIM, 16f, false);
        text(x, top - 20f, view.available
                ? Strings.format("shop.cost", view.cost)
                : Strings.get("shop.unavailable"),
                view.affordable && view.available ? GOLD : TEXT_DIM, 15f, false);
        if (view.level > 0) {
            text(x, top - 38f, Strings.format("shop.owned", view.level),
                    TEXT_DIM, 13f, false);
        }
    }

    private void drawTalents() {
        SafeArea safe = ui.safeArea();
        TalentScreen screen = ui.talents();
        TalentTree tree = run.talentTree();
        title(safe, Strings.get("talent.title"));
        subtitle(safe, tree.availablePoints() > 0
                ? Strings.format("talent.points", tree.availablePoints())
                : Strings.get("talent.noPoints"));

        //  Branch headings sit above their column; the column x comes from the
        //  first node in it, so headings cannot drift away from their nodes.
        int index = 0;
        for (TalentBranch branch : TalentBranch.values()) {
            Array<TalentDef> inBranch = tree.table().branch(branch);
            if (inBranch.size > 0) {
                UiRect first = screen.node(inBranch.get(0).id);
                if (first != null && first.visible()) {
                    text(first.centerX(),
                            first.visualY() + first.visualHeight() + 22f,
                            Strings.get("talent.branch." + branch.id()),
                            GOLD, 16f, false, true);
                }
            }
            index += inBranch.size;
        }
        for (int i = 0; i < screen.nodes().size; i++) {
            drawTalentNode(screen.nodes().get(i), screen.order().get(i), tree);
        }
        button(screen.backButton(), Strings.get("common.back"));
    }

    private void drawTalentNode(UiRect node, TalentDef def, TalentTree tree) {
        if (!node.visible()) {
            return;
        }
        boolean buyable = tree.canPurchase(def.id);
        boolean unlocked = tree.isUnlocked(def.id);
        Color face = node.state() == UiRect.State.SELECTED ? BUTTON_SELECTED
                : (buyable ? BUTTON : BUTTON_DISABLED);
        fill(node.visualX(), node.visualY(), node.visualWidth(), node.visualHeight(),
                face, PANEL_EDGE);
        text(node.visualX() + 6f, node.visualY() + node.visualHeight() - 6f,
                Strings.get("talent." + def.id + ".name"),
                unlocked ? TEXT : TEXT_DIM, 13f, false);
        text(node.visualX() + 6f, node.visualY() + 16f,
                Strings.format("talent.rank", tree.rank(def.id), def.maxRank),
                tree.rank(def.id) > 0 ? GOLD : TEXT_DIM, 12f, false);
    }

    private void drawPause() {
        SafeArea safe = ui.safeArea();
        shade(safe);
        title(safe, Strings.get("pause.title"));
        button(ui.pause().resumeButton(), Strings.get("pause.resume"));
    }

    private void drawGameOver() {
        SafeArea safe = ui.safeArea();
        shade(safe);
        title(safe, Strings.get("gameover.title"));
        subtitle(safe, Strings.format("gameover.score", run.session().score()));
        button(ui.gameOver().restartButton(), Strings.get("gameover.continue"));
    }

    // ========================================================================
    //  Primitives
    // ========================================================================

    private void title(SafeArea safe, String s) {
        text(safe.centerX(), safe.top() - 46f, s, GOLD, 40f, false, true);
    }

    private void subtitle(SafeArea safe, String s) {
        text(safe.centerX(), safe.top() - 84f, s, TEXT_DIM, 20f, false, true);
    }

    private void shade(SafeArea safe) {
        fill(0f, 0f, safe.x * 2f + safe.width * 2f, safe.y * 2f + safe.height * 2f,
                SHADE, null);
    }

    private void button(UiRect r, String label) {
        button(r, label, null, null, 20f);
    }

    private void button(UiRect r, String label, Color face, Color ink, float size) {
        if (!r.visible()) {
            return;
        }
        Color f = face != null ? face
                : (r.state() == UiRect.State.SELECTED ? BUTTON_SELECTED
                        : (r.pressable() ? BUTTON : BUTTON_DISABLED));
        Color i = ink != null ? ink : (r.pressable() ? TEXT : TEXT_DIM);
        //  Visual bounds, never hit bounds: an enlarged touch box must not make
        //  the button look bigger than it is.
        fill(r.visualX(), r.visualY(), r.visualWidth(), r.visualHeight(), f, PANEL_EDGE);
        String fitted = ui.text().ellipsize(label, r.visualWidth() - 12f,
                ui.text().fitToWidth(label, r.visualWidth() - 12f, size, 10f));
        text(r.centerX(), r.centerY() + size * 0.35f, fitted, i,
                ui.text().fitToWidth(label, r.visualWidth() - 12f, size, 10f),
                false, true);
    }

    /** Returns the line height consumed, so a row stack can walk downward. */
    private float line(float x, float y, String s, Color color, float size) {
        return line(x, y, s, color, size, false);
    }

    private float line(float x, float y, String s, Color color, float size,
                       boolean bold) {
        text(x, y, s, color, size, false, false, bold);
        return measurer.lineHeight(size);
    }

    private void text(float x, float y, String s, Color color, float size,
                      boolean rightAligned) {
        text(x, y, s, color, size, rightAligned, false);
    }

    private void text(float x, float y, String s, Color color, float size,
                      boolean rightAligned, boolean centred) {
        text(x, y, s, color, size, rightAligned, centred, false);
    }

    /**
     * One string.
     *
     * <p>{@code bold} draws a second pass a fraction of a unit across, which is
     * exactly how pygame synthesises bold for a face that has none — so a label
     * the source marks {@code bold=True} is thickened the same way here, with no
     * second font to bundle or license. The source uses it for almost every HUD
     * value and every banner, and without it the interface reads noticeably
     * lighter than the original.
     */
    private void text(float x, float y, String s, Color color, float size,
                      boolean rightAligned, boolean centred, boolean bold) {
        if (s == null || s.isEmpty()) {
            return;
        }
        //  A source size, converted once.  See TextLayout's calibration note.
        font.getData().setScale(TextLayout.glyph(size) / BASE_FONT);
        font.setColor(color);
        batch.begin();
        float drawX = x;
        if (rightAligned || centred) {
            glyphs.setText(font, s);
            drawX = rightAligned ? x - glyphs.width : x - glyphs.width / 2f;
        }
        font.draw(batch, s, drawX, y);
        if (bold) {
            font.draw(batch, s, drawX + BOLD_OFFSET, y);
        }
        batch.end();
        font.getData().setScale(1f);
    }

    /** How far the faux-bold pass is offset, in UI units. */
    private static final float BOLD_OFFSET = 0.7f;

    private void fill(float x, float y, float w, float h, Color face, Color edge) {
        if (w <= 0f || h <= 0f) {
            return;
        }
        if (face != null) {
            com.badlogic.gdx.Gdx.gl.glEnable(com.badlogic.gdx.graphics.GL20.GL_BLEND);
            shapes.begin(ShapeRenderer.ShapeType.Filled);
            shapes.setColor(face);
            shapes.rect(x, y, w, h);
            shapes.end();
        }
        if (edge != null) {
            shapes.begin(ShapeRenderer.ShapeType.Line);
            shapes.setColor(edge);
            shapes.rect(x, y, w, h);
            shapes.end();
        }
    }

    // --- the strings the HUD rows need --------------------------------------

    private String titleText() {
        return run.session().isEndless()
                ? Strings.format("hud.tier", run.session().wave())
                : Strings.format("hud.wave", run.session().wave());
    }

    private String badgeText() {
        //  "difficulty.<id>" -- there is no ".name" suffix in the bundle.
        return Strings.get("difficulty." + run.session().difficulty().id());
    }

    private String clockText() {
        int total = (int) run.session().playTime();
        return String.format(java.util.Locale.ROOT, "%d:%02d", total / 60, total % 60);
    }

    // ========================================================================

    /**
     * {@link TextLayout.Measurer} backed by the real font.
     *
     * <p>A {@link GlyphLayout} rather than a per-character estimate, so kerning
     * and the actual advance widths are what the layout sees. Shared with the
     * renderer to avoid an allocation per measurement, which matters because the
     * interface measures many strings every frame.
     */
    static final class FontMeasurer implements TextLayout.Measurer {
        private final BitmapFont font;
        private final GlyphLayout layout;

        FontMeasurer(BitmapFont font, GlyphLayout layout) {
            this.font = font;
            this.layout = layout;
        }

        @Override
        public float width(String text, float size) {
            if (text == null || text.isEmpty()) {
                return 0f;
            }
            font.getData().setScale(TextLayout.glyph(size) / BASE_FONT);
            layout.setText(font, text);
            float w = layout.width;
            font.getData().setScale(1f);
            return w;
        }

        @Override
        public float lineHeight(float size) {
            //  Both calibrations: the glyphs are scaled to pygame's, and then the
            //  line advance is scaled again because lsans leaves more air around
            //  a glyph than pygame's default face does.  Without the second one
            //  the HUD's measured row stack comes out a third too tall.
            font.getData().setScale(TextLayout.glyph(size) / BASE_FONT);
            float h = font.getLineHeight() * TextLayout.LINE;
            font.getData().setScale(1f);
            return h;
        }
    }
}
