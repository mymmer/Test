package com.mymmer.castledefense;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.mymmer.castledefense.data.DataException;
import com.mymmer.castledefense.game.GameWorld;
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
    private final ViewportSet viewports = new ViewportSet();
    private final Services services;

    //  Ownership, stated once: this class creates and owns all four. Nothing
    //  else may replace them, and their lifetimes match the application's.
    private final GameWorld world;
    private final Simulation simulation;
    private final GameInput input;
    private final InputRouter inputRouter;

    private volatile GameRenderer renderer;

    //  volatile: libGDX runs create()/render() on the application thread while
    //  tests (and Android lifecycle callbacks) observe from another one
    private volatile int createCount;
    private volatile int resizeCount;
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
        this(new GameRendererFactory() {
            @Override
            public GameRenderer create() {
                return new FoundationRenderer();
            }
        }, new Services(platform));
    }

    /**
     * Test/embedding constructor.
     *
     * @param rendererFactory builds the renderer once a context exists; tests
     *                        pass one that draws nothing
     */
    public CastleDefenseGame(GameRendererFactory rendererFactory) {
        this(rendererFactory, new Services(new NoOpPlatformServices()));
    }

    /** Full injection, for tests that supply their own infrastructure. */
    public CastleDefenseGame(GameRendererFactory rendererFactory, Services services) {
        if (rendererFactory == null) {
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
        renderer = rendererFactory.create();
        renderer.create(viewports);
        // A backend may never call resize() before the first frame (the headless
        // one does not), so start from a defined layout.
        int w = Gdx.graphics != null ? Gdx.graphics.getWidth() : 0;
        int h = Gdx.graphics != null ? Gdx.graphics.getHeight() : 0;
        if (w > 0 && h > 0) {
            viewports.resize(w, h);
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
        float delta = Gdx.graphics != null ? Gdx.graphics.getDeltaTime() : 0f;

        int steps = simulation.advance(delta);
        for (int i = 0; i < steps; i++) {
            // the input clock is simulation time, never wall-clock
            input.setClock((float) world.simulationTime());
            inputRouter.route();
            input.endStep();
        }

        if (renderer != null) {
            renderer.render(viewports, simulation.alpha());
        }
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
            return world.describe()
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
