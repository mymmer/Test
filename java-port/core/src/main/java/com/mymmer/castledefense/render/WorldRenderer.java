package com.mymmer.castledefense.render;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.utils.Array;
import com.mymmer.castledefense.boss.Boss;
import com.mymmer.castledefense.boss.DroppedItem;
import com.mymmer.castledefense.defence.DefenceTower;
import com.mymmer.castledefense.defence.Projectile;
import com.mymmer.castledefense.enemy.Enemy;
import com.mymmer.castledefense.game.GameState;
import com.mymmer.castledefense.game.RunWorld;
import com.mymmer.castledefense.skill.FireZone;
import com.mymmer.castledefense.skill.Tornado;

/**
 * The world, painted in the source's order.
 *
 * <h2>The order is the contract</h2>
 *
 * <p>{@link DrawOrder.Layer} lists it and {@link #render} walks it. Layers are
 * not merged to save draw calls: a Cannon's shell must be in front of the enemy
 * it is about to hit, and the moment the order becomes negotiable that stops
 * being reliable. Batching happens inside a layer.
 *
 * <h2>Screen shake: a camera offset, and nothing more</h2>
 *
 * <p>The source renders the scene to a surface and blits it at a random offset.
 * The equivalent here is to move the <b>world camera</b> for the duration of the
 * world pass and put it back immediately afterwards. That was chosen over a
 * framebuffer composite because it needs no render target, no lifecycle, no
 * resize handling and no second sampling of the scene — and over moving entities
 * because moving entities would be moving the game.
 *
 * <p>What it guarantees:
 *
 * <ul>
 *   <li><b>No gameplay coordinate changes.</b> Nothing is written to any entity.
 *       {@code enemy.x()} is the same during a shake as outside one.</li>
 *   <li><b>Pointer conversion is unaffected.</b> The camera is restored before
 *       the method returns, so every unproject in the following frame's input
 *       uses the unshaken camera. A finger aimed at world x lands on world x
 *       while the picture is shaking, which
 *       {@code ShakeTest.aimIsUnaffectedByShake} asserts directly.</li>
 *   <li><b>The interface does not shake</b> — it has its own camera and this
 *       never touches it. With two exceptions, below.</li>
 * </ul>
 *
 * <h2>The two widgets that do shake</h2>
 *
 * <p>{@code Game.draw} paints the skill bar, the horn and the grab cursor into
 * the world surface {@code s}, and only the HUD panel and the menus onto
 * {@code self.screen}. So in the source those three <b>do</b> shake and the rest
 * of the interface does not. That is reproduced: {@link #shakeX} / {@link #shakeY}
 * are published for the UI renderer to apply to exactly those widgets, and their
 * hit rectangles are untouched — a shaking button is still pressed where it was
 * laid out, as it is in the source.
 *
 * <h2>Interpolation</h2>
 *
 * <p>Anything that moves fast enough to stutter is drawn through
 * {@link Interpolator}: enemies, projectiles and dropped items. Towers, the
 * castle and the structures do not move and are drawn at their own coordinates.
 * The simulation's positions stay authoritative throughout; the blend produces
 * two floats for a draw call and is never written back.
 */
public final class WorldRenderer implements GameRenderer {

    private final RunWorld run;

    private ShapeRenderer shapes;
    private SpriteBatch batch;
    private BitmapFont font;
    private RenderContext ctx;

    private final DrawOrder order = new DrawOrder();
    private final EnemyPainter enemies = new EnemyPainter();
    private final BossPainter bosses = new BossPainter();
    private final DefencePainter defences = new DefencePainter();
    private final WorldPainters painters = new WorldPainters();
    private final CursorPainter cursor = new CursorPainter();
    private final BackgroundCache background = new BackgroundCache();
    private final ProjectileTrails trails = new ProjectileTrails();
    private final Interpolator interpolator = new Interpolator();
    private EffectsSystem effects;

    /** Screen shake, as a camera offset. See WorldShake for the choice. */
    private final WorldShake shake = new WorldShake();

    private final VisualRng rng;
    private final com.mymmer.castledefense.assets.SkinManager skins;
    private final com.mymmer.castledefense.assets.GameAssets gameAssets;

    /**
     * The cosmetic detail budget.
     *
     * <p>Owned here, set from {@code Services}, and read by nothing in the
     * simulation. That is what makes {@code QualityEquivalenceTest}'s claim
     * structural rather than hopeful: gameplay has no route to this value.
     */
    private com.mymmer.castledefense.config.QualityConfig quality =
            com.mymmer.castledefense.config.QualityConfig.HIGH;

    public void setQuality(com.mymmer.castledefense.config.QualityConfig q) {
        if (q != null) {
            this.quality = q;
        }
    }

    /** Counters for the debug overlay. Presentation only. */
    private int drawnEnemies;
    private int drawnProjectiles;

    public WorldRenderer(RunWorld run, com.mymmer.castledefense.util.Rng rng,
                         com.mymmer.castledefense.assets.SkinManager skins,
                         com.mymmer.castledefense.assets.GameAssets assets) {
        if (run == null) {
            throw new IllegalArgumentException("run must not be null");
        }
        this.run = run;
        this.rng = new VisualRng(rng);
        this.skins = skins;
        this.gameAssets = assets;
    }

    // ========================================================================
    //  Lifecycle
    // ========================================================================

    @Override
    public void create(ViewportSet viewports) {
        shapes = new ShapeRenderer();
        batch = new SpriteBatch();
        font = new BitmapFont();
        font.setUseIntegerPositions(false);
        effects = new EffectsSystem(rng);
        ctx = new RenderContext(new ShapeKit(shapes), batch, font, skins, rng)
                .withAssets(gameAssets);
        painters.setTrails(trails);
    }

    @Override
    public void resize(ViewportSet viewports, int width, int height) {
        // Nothing is cached per size: everything is drawn in world units.
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
        background.dispose();
    }

    /** The sink gameplay sends one-shot visual events to. */
    public VisualEvents events() {
        return effects == null ? VisualEvents.NONE : effects;
    }

    public EffectsSystem effects() {
        return effects;
    }

    public Interpolator interpolator() {
        return interpolator;
    }

    public ProjectileTrails trails() {
        return trails;
    }

    /**
     * The GL context may have been thrown away while the app was backgrounded.
     *
     * <p>Nothing cached survives across that, and at present nothing IS cached —
     * the background cache was measured and removed. The hook stays because the
     * game's {@code resume} should have somewhere to say so, and the next thing
     * that caches GPU state will need it.
     */
    public void onResume() {
        background.invalidate();
    }

    /** Called on a new run so nothing from the last one is drawn or blended. */
    public void reset() {
        interpolator.clear();
        trails.clear();
        if (effects != null) {
            effects.clear();
        }
    }

    /** Advances presentation-only animation. Never called outside PLAYING. */
    public void updatePresentation(float dt) {
        if (effects != null) {
            effects.update(dt);
        }
        if (ctx != null) {
            ctx.renderTime += dt;
        }
    }

    /**
     * Records this step's positions for interpolation.
     *
     * <p>Driven by the game loop after each simulation step. It reads gameplay
     * and writes only into {@link Interpolator}.
     */
    public void onSimulationStep() {
        interpolator.beginStep();
        for (int i = 0; i < run.horde().size(); i++) {
            Enemy e = run.horde().get(i);
            if (e != null) {
                interpolator.step(e.uid(), e.x(), e.y());
            }
        }
        for (int i = 0; i < run.projectiles().size(); i++) {
            Projectile p = run.projectiles().get(i);
            if (p != null) {
                interpolator.step(p.uid(), p.x(), p.y());
            }
        }
        for (int i = 0; i < run.droppedItems().size(); i++) {
            DroppedItem d = run.droppedItems().get(i);
            if (d != null) {
                interpolator.step(d.uid(), d.x(), d.y());
            }
        }
        interpolator.endStep();
    }

    // ========================================================================
    //  The frame
    // ========================================================================

    @Override
    public void render(ViewportSet viewports, float alpha) {
        Gdx.gl.glClearColor(0.024f, 0.031f, 0.063f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        ShapeKit.enableBlend();

        ctx.alpha = alpha;
        ctx.worldTime = run.session() == null ? 0d : run.session().playTime();
        ctx.quality = quality;
        if (effects != null) {
            effects.setQuality(ctx.quality);
        }
        trails.sample(run.projectiles());

        shake.apply(viewports, run.shake().amount(), rng);
        viewports.getWorld().apply();
        shapes.setProjectionMatrix(viewports.getWorldCamera().combined);
        batch.setProjectionMatrix(viewports.getWorldCamera().combined);

        drawWorld();

        //  Put the camera back before anything else can see it.  Input runs on
        //  the next frame against an unshaken camera, which is what keeps a
        //  pointer aimed at world x landing on world x.
        shake.clear(viewports);
    }

    /**
     * The source's painter order, layer by layer.
     *
     * <p>Shapes and text alternate because libGDX cannot mix them in one pass, so
     * each group draws its shapes and then its labels. The <b>order between
     * layers</b> is never disturbed by that: a layer's text belongs to that
     * layer.
     */
    private void drawWorld() {
        drawnEnemies = 0;
        drawnProjectiles = 0;
        GameState state = run.world().state();

        long t0 = LayerTimes.now();
        LayerTimes.layer(0);
        //  BACKGROUND -- one textured quad instead of 1188 shape primitives
        //  of entirely static content.  Measured on a fixed-roster scene: 541 us
        //  per frame with it, 598 us without.  See BackgroundCache.
        background.ensure(ctx, painters);
        if (background.isReady()) {
            batch.begin();
            background.draw(batch);
            batch.end();
            ctx.kit.fillBegin();
        } else {
            ctx.kit.fillBegin();
            painters.paintBackground(ctx);
        }
        t0 = LayerTimes.mark(0, t0);
        LayerTimes.layer(1);
        defences.paintOutpost(ctx, run.outpost());                    // OUTPOST
        t0 = LayerTimes.mark(1, t0);
        LayerTimes.layer(2);
        defences.paintCastle(ctx, run.castle(), run.spikes());        // CASTLE + spikes
        t0 = LayerTimes.mark(2, t0);
        LayerTimes.layer(3);
        for (int i = 0; i < run.castle().towers().size; i++) {
            defences.paintTower(ctx, run.castle().towers().get(i));   // ...and towers
        }
        defences.paintBarricade(ctx, run.barricade());                // BARRICADE
        t0 = LayerTimes.mark(3, t0);

        LayerTimes.layer(4);
        Array<Enemy> horde = order.sortedEnemies(run.horde());        // ENEMIES
        for (int i = 0; i < horde.size; i++) {
            Enemy e = horde.get(i);
            float[] at = interpolator.draw(e, e.x(), e.y(), ctx.alpha);
            at[1] = WorldGeometry.toDrawY(at[1]);
            if (e.isBoss() && e instanceof Boss) {
                bosses.paint(ctx, (Boss) e, at[0], at[1]);
            } else {
                enemies.paint(ctx, e, at[0], at[1]);
            }
            enemies.paintHealth(ctx, e, at[0], at[1]);
            drawnEnemies++;
        }
        t0 = LayerTimes.mark(4, t0);
        drawAllies();                                                 // ALLIES
        LayerTimes.layer(5);
        for (int i = 0; i < run.droppedItems().size(); i++) {         // ITEMS
            DroppedItem it = run.droppedItems().get(i);
            if (it == null || !it.isAlive()) {
                continue;
            }
            float[] at = interpolator.draw(it, it.x(), it.y(), ctx.alpha);
            painters.paintItem(ctx, it, at[0], WorldGeometry.toDrawY(at[1]));
        }
        for (int i = 0; i < run.fireZones().size; i++) {              // FIRE_ZONES
            FireZone z = run.fireZones().get(i);
            if (z.alive()) {
                painters.paintFireZone(ctx, z);
            }
        }
        for (int i = 0; i < run.tornados().size; i++) {               // TORNADOS
            Tornado t = run.tornados().get(i);
            if (t.alive()) {
                painters.paintTornado(ctx, t);
            }
        }
        for (int i = 0; i < run.projectiles().size(); i++) {          // PROJECTILES
            Projectile p = run.projectiles().get(i);
            if (p == null || !p.isAlive()) {
                continue;
            }
            float[] at = interpolator.draw(p, p.x(), p.y(), ctx.alpha);
            painters.paintProjectile(ctx, p, at[0], WorldGeometry.toDrawY(at[1]));
            drawnProjectiles++;
        }
        t0 = LayerTimes.mark(5, t0);
        LayerTimes.layer(6);
        effects.paintParticles(ctx);                                  // EFFECTS
        painters.paintWeather(ctx, run.weather(), ctx.worldTime);      // WEATHER
        effects.paintBolts(ctx);                                       // BOLTS
        painters.paintStormVeil(ctx, run.weather());                   // FLASH
        if (state == GameState.PLAYING) {
            //  GRAB_CURSOR -- PLAYING only, exactly as the source gates it.
            cursor.paint(ctx, run.cursor(), run.pointerX(), run.pointerY(),
                    run.pointerDown());
        }
        LayerTimes.mark(6, t0);
        ctx.kit.end();

        //  The text pass: every label the shape pass could not draw, in the same
        //  layer order it would have appeared in.
        batch.begin();
        for (int i = 0; i < horde.size; i++) {
            Enemy e = horde.get(i);
            float[] at = interpolator.draw(e, e.x(), e.y(), ctx.alpha);
            at[1] = WorldGeometry.toDrawY(at[1]);
            if (e.isBoss() && e instanceof Boss) {
                bosses.paintLabel(ctx, (Boss) e, at[0], at[1]);
            } else {
                enemies.paintLabels(ctx, e, at[0], at[1]);
            }
        }
        for (int i = 0; i < run.castle().towers().size; i++) {
            defences.paintTowerLabel(ctx, run.castle().towers().get(i));
        }
        defences.paintStructureLabels(ctx, run.outpost(), run.barricade());
        defences.paintPrisonerLabels(ctx, run.outpost());              // PRISONER
        effects.paintTexts(ctx);
        if (state == GameState.PLAYING) {
            cursor.paintLabels(ctx, run.cursor(), run, run.pointerX(),
                    run.pointerY());
        }
        batch.end();
    }

    private void drawAllies() {
        //  A separate roster from the horde, deliberately: an ally is not an
        //  enemy, the player's towers never target one, and the source draws
        //  them in their own layer after the enemies.
        for (int i = 0; i < run.allyCount(); i++) {
            com.mymmer.castledefense.enemy.FriendlySkeleton a = run.ally(i);
            if (a == null || !a.alive()) {
                continue;
            }
            //  FriendlySkeleton keeps no walk phase of its own, so its legs bob
            //  on the SIMULATION clock offset by position -- which freezes with
            //  the world and keeps two allies from marching in lockstep.
            painters.paintAlly(ctx, a.x(), WorldGeometry.toDrawY(a.y()),
                    a.width(), a.height(),
                    (float) ctx.worldTime * 6f + a.x() * 0.05f,
                    a.hp(), a.maxHp());
        }
    }

    // ========================================================================
    //  Shake
    // ========================================================================

    /** This frame's shake, for the two HUD widgets the source shakes too. */
    public float shakeX() {
        return shake.x();
    }

    public float shakeY() {
        return shake.y();
    }

    /** The shake, for the tests and the debug overlay. */
    public WorldShake shake() {
        return shake;
    }

    // --- for the debug overlay ------------------------------------------------

    /** This frame's interpolation alpha. For the overlay. */
    public float alpha() {
        return ctx == null ? 0f : ctx.alpha;
    }

    public com.mymmer.castledefense.config.QualityConfig quality() {
        return quality;
    }

    /** The active skin's id, or "-" before create(). For the overlay. */
    public String skinId() {
        return skins == null ? "-" : skins.activeSkinId();
    }

    /** The active atlas path, or "-" when everything is hand-drawn. */
    public String atlasPath() {
        String path = skins == null ? "" : skins.activeAtlasPath();
        return path == null || path.isEmpty() ? "-" : path;
    }

    public int drawnEnemies() {
        return drawnEnemies;
    }

    public int drawnProjectiles() {
        return drawnProjectiles;
    }
}
