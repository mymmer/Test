package com.mymmer.castledefense;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.mymmer.castledefense.data.DataException;
import com.mymmer.castledefense.game.GameMode;
import com.mymmer.castledefense.game.GameWorld;
import com.mymmer.castledefense.game.RunWorld;
import com.mymmer.castledefense.game.Simulation;
import com.mymmer.castledefense.input.DesktopInput;
import com.mymmer.castledefense.input.GameInput;
import com.mymmer.castledefense.input.InputRouter;
import com.mymmer.castledefense.platform.NoOpPlatformServices;
import com.mymmer.castledefense.platform.PlatformServices;
import com.mymmer.castledefense.render.FoundationRenderer;
import com.mymmer.castledefense.render.GameRenderer;
import com.mymmer.castledefense.render.GameRendererFactory;
import com.mymmer.castledefense.render.ViewportSet;

/**
 * The application: owns the viewports, the lifecycle and (from Phase 4) the
 * fixed-step simulation. It knows nothing about windows, touch APIs, file paths
 * or Android.
 *
 * <p>It owns the four long-lived pieces of a session — {@link Services},
 * {@link GameWorld}, {@link Simulation} and {@link GameInput} (plus its
 * {@link InputRouter}) — creates them once, and is the only thing that may
 * replace them. Gameplay systems are attached to the world in later phases
 * rather than being held here.
 *
 * <p>Lifecycle counters are exposed for tests. That is the only reason they are
 * public: an Android pause/resume cycle is otherwise unobservable in CI.
 */
public class CastleDefenseGame extends ApplicationAdapter {

    private final GameRendererFactory rendererFactory;
    /**
     * Whether to build the interface renderer.
     *
     * <p>False for the injecting constructors, which exist so a test can run the
     * whole lifecycle with no GL context. {@code UiRenderer} allocates a
     * {@code SpriteBatch} the moment it is created, and a headless backend
     * cannot compile its shader — the same reason the world renderer is behind a
     * factory. The interface still lays out; only the painting is skipped.
     */
    private final boolean paintUi;
    private final ViewportSet viewports = new ViewportSet();
    private final Services services;

    //  Ownership, stated once: this class creates and owns all four. Nothing
    //  else may replace them, and their lifetimes match the application's.
    private final GameWorld world;
    private final Simulation simulation;
    private final GameInput input;
    private final InputRouter inputRouter;

    private volatile GameRenderer renderer;
    /** The run, built once the balance tables are loaded. Null before create(). */
    private volatile RunWorld run;
    /** The interface, built with it. Registered as the router's first consumer. */
    private volatile com.mymmer.castledefense.ui.UiRoot ui;
    private volatile com.mymmer.castledefense.render.UiRenderer uiRenderer;
    private volatile com.mymmer.castledefense.render.WorldRenderer worldRenderer;
    private volatile com.mymmer.castledefense.render.UiDebugOverlay uiDebug;

    //  volatile: libGDX runs create()/render() on the application thread while
    //  tests (and Android lifecycle callbacks) observe from another one
    private volatile int createCount;
    private volatile int resizeCount;
    /**
     * Phase 13's on-device measurement, or null.
     *
     * <p>Null in every shipped build and every test: the game pays one null
     * check per frame for it. Set by a launcher that was asked to measure --
     * {@code --bench} on the desktop, an intent extra on Android.
     */
    private volatile com.mymmer.castledefense.perf.FrameProbe frameProbe;

    private volatile int renderCount;
    private volatile int pauseCount;
    private volatile int resumeCount;
    private volatile int disposeCount;
    private volatile boolean paused;
    private volatile Throwable startupFailure;

    /** Production constructor: the real (Phase 2 scaffolding) renderer. */
    public CastleDefenseGame() {
        this(new NoOpPlatformServices());
    }

    /** The constructor a launcher uses, supplying its platform implementation. */
    public CastleDefenseGame(PlatformServices platform) {
        //  Phase 11: the real world renderer. It needs the run and the asset
        //  system, which do not exist until create(), so the factory is a
        //  closure over this game rather than a constant -- and the injecting
        //  constructors below still get a renderer that draws nothing, which is
        //  what lets the whole lifecycle be tested with no GL context.
        this(null, new Services(platform), true);
    }

    /**
     * Test/embedding constructor.
     *
     * @param rendererFactory builds the renderer once a context exists; tests
     *                        pass one that draws nothing
     */
    public CastleDefenseGame(GameRendererFactory rendererFactory) {
        this(rendererFactory, new Services(new NoOpPlatformServices()), false);
    }

    /** Full injection, for tests that supply their own infrastructure. */
    public CastleDefenseGame(GameRendererFactory rendererFactory, Services services) {
        this(rendererFactory, services, false);
    }

    private CastleDefenseGame(GameRendererFactory rendererFactory, Services services,
                              boolean paintUi) {
        this.paintUi = paintUi;
        if (rendererFactory == null && !paintUi) {
            throw new IllegalArgumentException("rendererFactory must not be null");
        }
        if (services == null) {
            throw new IllegalArgumentException("services must not be null");
        }
        this.rendererFactory = rendererFactory;
        this.services = services;
        this.world = new GameWorld(services.rng());
        this.simulation = new Simulation(world);
        this.input = new DesktopInput(viewports);
        this.inputRouter = new InputRouter(input);
    }

    @Override
    public void create() {
        createCount++;
        // Infrastructure first: crash logging is installed before anything that
        // could fail, and the state snapshot is registered so a later crash
        // reports what the game was doing.
        try {
            services.start();
            services.crashLogger().setContextProvider(new CrashContext());
        } catch (DataException e) {
            // Content is broken. Log it loudly; the game continues far enough
            // to show a failure rather than dying with no explanation.
            services.crashLogger().logCrash(e, "startup data loading");
            startupFailure = e;
        }
        //  The run is assembled only if the tables loaded.  A startup data
        //  failure leaves it null and the game shows a broken-content screen
        //  rather than crashing inside a step.
        if (startupFailure == null) {
            run = new RunWorld(world, services.enemies(), services.defences(),
                    services.bosses(), services.talents(), services.shop(),
                    input::releaseVelocity);
            //  The UI is a UiConsumer like any other, and it is registered
            //  FIRST so a press it claims never reaches the world.  There is no
            //  second input path -- see UI.md.
            ui = new com.mymmer.castledefense.ui.UiRoot(run, viewports,
                    services.difficulties(), services.save(), services::persist, null);
            ui.setSafeAreaInsets(services.platform().safeAreaInsets());
            inputRouter.addConsumer(ui);
            inputRouter.setWorldHandler(run.cursor());
        }

        if (rendererFactory != null) {
            renderer = rendererFactory.create();
        } else {
            //  The world renderer, built now that the run and the assets exist.
            worldRenderer = new com.mymmer.castledefense.render.WorldRenderer(
                    run, services.rng(), services.skins(), services.assets());
            worldRenderer.setQuality(services.quality());
            renderer = worldRenderer;
        }
        renderer.create(viewports);
        //  One-shot visual events go to the renderer's particle system and
        //  nowhere else.  Attached AFTER create(), because the particle system
        //  is built there -- doing it before captured VisualEvents.NONE and the
        //  game silently had no particles or floating text at all.  Found by the
        //  Phase 11.5 image comparison: thirty kills, no sparks.
        if (worldRenderer != null && run != null) {
            run.setVisualEvents(worldRenderer.events());
        }
        if (ui != null && paintUi) {
            //  The interface paints only once a GL context exists, and only then
            //  can it be measured with the font that will draw it.  Until this
            //  point the layout used an estimate, which is harmless because it
            //  lays out again every frame.
            uiRenderer = new com.mymmer.castledefense.render.UiRenderer(run, ui);
            uiRenderer.create();
            ui.setTextLayout(new com.mymmer.castledefense.ui.TextLayout(
                    uiRenderer.measurer()));
            uiDebug = new com.mymmer.castledefense.render.UiDebugOverlay(ui, input);
            uiDebug.create();
            uiDebug.setWorldRenderer(worldRenderer);
        }
        // A backend may never call resize() before the first frame (the headless
        // one does not), so start from a defined layout.
        int w = Gdx.graphics != null ? Gdx.graphics.getWidth() : 0;
        int h = Gdx.graphics != null ? Gdx.graphics.getHeight() : 0;
        if (w > 0 && h > 0) {
            viewports.resize(w, h);
        }
        //  Connect the input.
        //
        //  Until Phase 13 this line did not exist, and nothing else called
        //  Gdx.input.setInputProcessor either -- so libGDX never delivered a
        //  touch or a click to GameInput and the running game had NO input at
        //  all, on any platform.  It survived ten phases because the tests
        //  drive GameInput directly (which is the right way to test the router)
        //  and every screenshot is a staged scenario, so nothing ever pressed a
        //  button in a running build until it was tried on a phone.
        //
        //  setCatchKey stops Android treating Back as "leave the activity" so
        //  Navigation.back() can apply the source's own routes; it is a no-op
        //  on the desktop backend.
        if (Gdx.input != null) {
            Gdx.input.setInputProcessor(input);
            Gdx.input.setCatchKey(com.badlogic.gdx.Input.Keys.BACK, true);
        }
        log("created");
    }

    @Override
    public void resize(int width, int height) {
        resizeCount++;
        if (width <= 0 || height <= 0) {
            return;                 // Android reports 0x0 while minimised
        }
        viewports.resize(width, height);
        if (ui != null) {
            //  the cutouts can change with the orientation, so they are re-read
            //  rather than cached from startup
            ui.setSafeAreaInsets(services.platform().safeAreaInsets());
            ui.layout();
        }
        if (renderer != null) {
            renderer.resize(viewports, width, height);
        }
    }

    /**
     * One rendered frame.
     *
     * <p>Render rate and simulation rate are independent: the frame's elapsed
     * time is handed to the accumulator, which runs zero or more fixed steps,
     * and the leftover fraction becomes the interpolation alpha. Input is
     * clocked from <em>simulation</em> time and stepped once per simulation
     * step, so a press is seen by exactly one step whatever the display does.
     */
    @Override
    public void render() {
        renderCount++;
        final com.mymmer.castledefense.perf.FrameProbe probe = frameProbe;
        if (probe != null) {
            probe.frameBegin();
        }
        float delta = Gdx.graphics != null ? Gdx.graphics.getDeltaTime() : 0f;

        int steps = simulation.advance(delta);
        if (probe != null) {
            probe.simBegin();
        }
        for (int i = 0; i < steps; i++) {
            // the input clock is simulation time, never wall-clock
            input.setClock((float) world.simulationTime());
            inputRouter.route();
            if (run != null) {
                //  the world position the cursor is working at, sampled once
                //  per step -- gameplay never reads a device
                com.mymmer.castledefense.input.Pointer p = input.pointer(0);
                run.setPointer(p.isDown(), p.worldX(), p.worldY());
            }
            input.endStep();
            //  Interpolation history is recorded per simulation step, which is
            //  the interval it exists to span.  It reads gameplay and writes
            //  only into the renderer's own map.
            if (worldRenderer != null) {
                worldRenderer.onSimulationStep();
            }
        }
        if (probe != null) {
            probe.simEnd();
        }

        applyBack();

        //  Particles and floating text are presentation, so they run on the
        //  frame delta -- and only while the world itself is running, which is
        //  the same rule that freezes the Endless armoury.
        if (worldRenderer != null
                && world.state() == com.mymmer.castledefense.game.GameState.PLAYING) {
            worldRenderer.updatePresentation(delta);
        }

        if (ui != null) {
            //  Laid out once per rendered frame, from gameplay state and the
            //  viewport only -- never from the frame delta.
            ui.layout();
        }
        if (renderer != null) {
            renderer.render(viewports, simulation.alpha());
        }
        //  The interface last, over the world, and the overlay last of all.
        //  The skill bar, the horn and the grab cursor shake with the world --
        //  the source draws those three into the shaken surface and the HUD
        //  panel and menus onto the unshaken screen.  See RENDERING.md.
        if (uiRenderer != null) {
            if (worldRenderer != null) {
                uiRenderer.setWorldShake(worldRenderer.shakeX(),
                        worldRenderer.shakeY());
            }
            uiRenderer.render(viewports);
        }
        if (uiDebug != null) {
            uiDebug.render(viewports);
        }
        if (probe != null) {
            probe.frameEnd(steps, simulation.droppedStepEvents(),
                    simulation.clampedFrames());
        }
    }

    /**
     * Applies a pending Back press, on the render thread.
     *
     * <p>Called once per frame rather than once per simulation step: Back is a
     * navigation event, not a gameplay one -- it moves between screens and
     * pauses the world, and running it twice because a slow frame ran two steps
     * would close two menus for one press.
     */
    private void applyBack() {
        if (ui == null || !input.consumeBack()) {
            return;
        }
        if (ui.navigation().back()
                == com.mymmer.castledefense.ui.Navigation.BackResult.EXIT_APP
                && Gdx.app != null) {
            Gdx.app.exit();
        }
    }

    /**
     * Attaches (or clears) the frame probe.
     *
     * <p>Deliberately settable after {@code create()}: on Android the decision
     * arrives in the launching intent, and on the desktop from the command
     * line, but neither is a construction-time property of the game.
     */
    public void setFrameProbe(com.mymmer.castledefense.perf.FrameProbe probe) {
        this.frameProbe = probe;
    }

    public com.mymmer.castledefense.perf.FrameProbe getFrameProbe() {
        return frameProbe;
    }

    /** The interface's debug overlay, or null before create(). */
    public com.mymmer.castledefense.render.WorldRenderer getWorldRenderer() {
        return worldRenderer;
    }

    public com.mymmer.castledefense.render.UiDebugOverlay getUiDebugOverlay() {
        return uiDebug;
    }

    /**
     * Android backgrounding, desktop minimise, or an incoming call.
     *
     * <p>The rule from {@code docs/PORT_ANALYSIS.md} §6: a pause may last
     * seconds or hours, and none of that time may ever reach a simulation step.
     * Phase 4 zeroes the accumulator here and forces PLAYING to PAUSED.
     */
    @Override
    public void pause() {
        pauseCount++;
        paused = true;
        // Every pointer is dropped: Android delivers no touch-up when the app
        // backgrounds, and a finger that is no longer on the screen must not
        // still be holding something on resume.
        input.cancelAll();
        // Android may never call dispose(); backgrounding is the last reliable
        // chance to write settings, so it is taken here.
        services.persist();
        log("paused");
    }

    @Override
    public void resume() {
        if (worldRenderer != null) {
            //  A backgrounded Android app loses its GL context; the
            //  cached background must be redrawn, not reused.
            worldRenderer.onResume();
        }
        resumeCount++;
        paused = false;
        // A pause can last hours. The frame delta after it is clamped, but the
        // fraction of a step left in the accumulator from before is stale, so
        // it is discarded rather than replayed into the world.
        simulation.resetAccumulator();
        log("resumed");
    }

    @Override
    public void dispose() {
        disposeCount++;
        if (renderer != null) {
            renderer.dispose();
            renderer = null;
        }
        if (uiRenderer != null) {
            uiRenderer.dispose();
            uiRenderer = null;
        }
        worldRenderer = null;       // disposed above, as `renderer`
        if (uiDebug != null) {
            uiDebug.dispose();
            uiDebug = null;
        }
        services.persist();
        services.dispose();
        log("disposed");
    }

    private void log(String what) {
        if (Gdx.app != null) {
            Gdx.app.log("CastleDefense", what);
        }
    }

    /** Describes what the game was doing, for the crash log. */
    private final class CrashContext implements com.mymmer.castledefense.util.CrashLogger.ContextProvider {
        @Override
        public String describe() {
            return (run != null ? run.describeRun() : world.describe())
                    + " frames=" + renderCount
                    + " paused=" + paused
                    + " screen=" + viewports.getScreenWidth() + "x" + viewports.getScreenHeight()
                    + " skin=" + services.skins().activeSkinId()
                    + " difficulty=" + (services.save() != null ? services.save().difficulty : "?");
        }
    }

    public Services getServices() {
        return services;
    }

    public GameWorld getWorld() {
        return world;
    }

    /** The assembled run, or null if the balance tables failed to load. */
    public RunWorld getRun() {
        return run;
    }

    /** The interface, or null if the balance tables failed to load. */
    public com.mymmer.castledefense.ui.UiRoot getUi() {
        return ui;
    }

    /**
     * Starts a run. The entry point Phase 10's menu will call.
     *
     * @return the seed the run is on, or {@code Long.MIN_VALUE} if content is
     *         broken and there is no run to start
     */
    public long startRun(GameMode mode) {
        if (run == null) {
            return Long.MIN_VALUE;
        }
        //  Through the navigation graph, which is the only way a player can
        //  start one.  A second entry point that called beginRun directly would
        //  land in a different state from the menu button -- and it did: it
        //  left the game PLAYING while the menu opens the first armoury.
        if (worldRenderer != null) {
            //  No particle, trail or interpolation history may cross a run
            //  boundary; a fresh run must not blend an entity in from where the
            //  last one's was.
            worldRenderer.reset();
        }
        if (ui != null) {
            if (!ui.navigation().chooseMode(mode, ui.preferredDifficulty())) {
                return Long.MIN_VALUE;
            }
            return run.session().seed();
        }
        String id = services.save() != null ? services.save().difficulty : null;
        return run.beginRun(mode, services.difficulties().get(id));
    }

    public Simulation getSimulation() {
        return simulation;
    }

    public GameInput getInput() {
        return input;
    }

    public InputRouter getInputRouter() {
        return inputRouter;
    }

    /** Non-null when startup data could not be loaded. */
    public Throwable getStartupFailure() {
        return startupFailure;
    }

    public ViewportSet getViewports() {
        return viewports;
    }

    public GameRenderer getRenderer() {
        return renderer;
    }

    public boolean isPaused() {
        return paused;
    }

    public int getCreateCount() {
        return createCount;
    }

    public int getResizeCount() {
        return resizeCount;
    }

    public int getRenderCount() {
        return renderCount;
    }

    public int getPauseCount() {
        return pauseCount;
    }

    public int getResumeCount() {
        return resumeCount;
    }

    public int getDisposeCount() {
        return disposeCount;
    }

    /** Simulation steps run since startup. */
    public long getStepsRun() {
        return simulation.stepCount();
    }
}
