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
    private static final Color BOSS = new Color(0.72f, 0.24f, 0.55f, 1f);
    private static final Color SHADE = new Color(0f, 0f, 0f, 0.62f);

    /** The size the built-in font was designed at. Scaling is relative to it. */
    private static final float BASE_FONT = 15f;

    private final RunWorld run;
    private final UiRoot ui;

    private ShapeRenderer shapes;
    private SpriteBatch batch;
    private BitmapFont font;
    private final GlyphLayout glyphs = new GlyphLayout();
    private FontMeasurer measurer;

    public UiRenderer(RunWorld run, UiRoot ui) {
        if (run == null || ui == null) {
            throw new IllegalArgumentException("run and ui must not be null");
        }
        this.run = run;
        this.ui = ui;
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
        shapes.setProjectionMatrix(viewports.getUiCamera().combined);
        batch.setProjectionMatrix(viewports.getUiCamera().combined);

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

        for (int i = 0; i < hud.skillSlots().size; i++) {
            drawSkillSlot(hud.skillSlots().get(i), SkillId.values()[i]);
        }
        if (hud.hornButton().visible()) {
            boolean spent = run.session().hornUsed();
            button(hud.hornButton(), Strings.get(spent ? "hud.hornSpent" : "hud.horn"),
                    spent ? BUTTON_DISABLED : BUTTON, spent ? TEXT_DIM : GOLD, 15f);
        }
        if (hud.shopButton().visible()) {
            button(hud.shopButton(), Strings.get("hud.shop"), BUTTON, GOLD, 13f);
        }
        for (int i = 0; i < hud.visibleBossBars(); i++) {
            drawBossBar(hud.bossBars().get(i), i);
        }
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
        float y = hud.panelY() + hud.panelHeight() - HudScreen.PANEL_PAD;

        Array<String> rows = hud.rows();
        for (int i = 0; i < rows.size; i++) {
            String row = rows.get(i);
            if ("hud.row.title".equals(row)) {
                y -= line(x, y, titleText(), TEXT, 30f);
                text(right, y + 30f, String.valueOf(run.session().gold()) + " G",
                        GOLD, 26f, true);
            } else if ("hud.row.badge".equals(row)) {
                y -= HudScreen.ROW_GAP + line(x, y, badgeText(), TEXT_DIM, 17f);
            } else if ("hud.row.wall".equals(row)) {
                y -= HudScreen.ROW_GAP
                        + line(x, y, run.castle().tierLabel(), TEXT_DIM, 18f);
            } else if ("hud.row.multiplier".equals(row)) {
                y -= HudScreen.ROW_GAP + line(x, y, Strings.format("hud.multiplier",
                        String.format(java.util.Locale.ROOT, "%.2f",
                                run.goldMultiplier())), GOLD, 18f);
            } else if ("hud.row.health".equals(row)) {
                y -= HudScreen.ROW_GAP + 18f;
                drawHealth(x, y, right - x);
            } else if ("hud.row.score".equals(row)) {
                y -= HudScreen.ROW_GAP + line(x, y, Strings.format("hud.score",
                        run.session().score()), TEXT, 28f);
            } else if ("hud.row.talents".equals(row)) {
                y -= HudScreen.ROW_GAP + line(x, y, Strings.format("hud.talentPoints",
                        run.talentTree().availablePoints()), TEXT_DIM, 19f);
            } else if ("hud.row.clock".equals(row)) {
                y -= HudScreen.ROW_GAP + 26f;
                text(x, y + 20f, clockText(), TEXT, 22f, false);
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

    private void drawBossBar(UiRect bar, int index) {
        Array<Boss> live = run.bossRegistry().liveBosses();
        if (index >= live.size) {
            return;
        }
        Boss boss = live.get(index);
        float frac = Math.max(0f, Math.min(1f, boss.hp() / Math.max(1f, boss.maxHp())));
        fill(bar.visualX(), bar.visualY(), bar.visualWidth(), bar.visualHeight(),
                PANEL, PANEL_EDGE);
        fill(bar.visualX() + 2f, bar.visualY() + 2f,
                (bar.visualWidth() - 4f) * frac, bar.visualHeight() - 4f, BOSS, null);
        text(bar.centerX(), bar.centerY() + 5f,
                Strings.get("boss." + boss.bossType().id()), TEXT, 14f, false, true);
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
                Strings.get("difficulty." + ui.preferredDifficulty().id() + ".name")),
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
                    + ui.difficulties().all().get(i).id() + ".name"));
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
        text(x, y, s, color, size, false);
        return measurer.lineHeight(size);
    }

    private void text(float x, float y, String s, Color color, float size,
                      boolean rightAligned) {
        text(x, y, s, color, size, rightAligned, false);
    }

    private void text(float x, float y, String s, Color color, float size,
                      boolean rightAligned, boolean centred) {
        if (s == null || s.isEmpty()) {
            return;
        }
        font.getData().setScale(size / BASE_FONT);
        font.setColor(color);
        batch.begin();
        if (rightAligned || centred) {
            glyphs.setText(font, s);
            float drawX = rightAligned ? x - glyphs.width : x - glyphs.width / 2f;
            font.draw(batch, s, drawX, y);
        } else {
            font.draw(batch, s, x, y);
        }
        batch.end();
        font.getData().setScale(1f);
    }

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
        return Strings.get("difficulty." + run.session().difficulty().id() + ".name");
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
            font.getData().setScale(size / BASE_FONT);
            layout.setText(font, text);
            float w = layout.width;
            font.getData().setScale(1f);
            return w;
        }

        @Override
        public float lineHeight(float size) {
            font.getData().setScale(size / BASE_FONT);
            float h = font.getLineHeight();
            font.getData().setScale(1f);
            return h;
        }
    }
}
