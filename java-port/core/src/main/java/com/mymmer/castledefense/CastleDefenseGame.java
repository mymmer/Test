package com.mymmer.castledefense;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.mymmer.castledefense.render.FoundationRenderer;
import com.mymmer.castledefense.render.GameRenderer;
import com.mymmer.castledefense.render.GameRendererFactory;
import com.mymmer.castledefense.render.ViewportSet;

/**
 * The application: owns the viewports, the lifecycle and (from Phase 4) the
 * fixed-step simulation. It knows nothing about windows, touch APIs, file paths
 * or Android.
 *
 * <p>Phase 2 scope: viewports, lifecycle hooks and a renderer seam. There is no
 * simulation yet — {@link #getStepsRun()} stays at zero until Phase 4 attaches
 * the accumulator described in {@code docs/PORT_ANALYSIS.md} §6.
 *
 * <p>Lifecycle counters are exposed for tests. That is the only reason they are
 * public: an Android pause/resume cycle is otherwise unobservable in CI.
 */
public class CastleDefenseGame extends ApplicationAdapter {

    private final GameRendererFactory rendererFactory;
    private final ViewportSet viewports = new ViewportSet();

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

    /** Production constructor: the real (Phase 2 scaffolding) renderer. */
    public CastleDefenseGame() {
        this(new GameRendererFactory() {
            @Override
            public GameRenderer create() {
                return new FoundationRenderer();
            }
        });
    }

    /**
     * Test/embedding constructor.
     *
     * @param rendererFactory builds the renderer once a context exists; tests
     *                        pass one that draws nothing
     */
    public CastleDefenseGame(GameRendererFactory rendererFactory) {
        if (rendererFactory == null) {
            throw new IllegalArgumentException("rendererFactory must not be null");
        }
        this.rendererFactory = rendererFactory;
    }

    @Override
    public void create() {
        createCount++;
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

    @Override
    public void render() {
        renderCount++;
        if (renderer != null) {
            renderer.render(viewports, 0f);
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
        log("paused");
    }

    @Override
    public void resume() {
        resumeCount++;
        paused = false;
        log("resumed");
    }

    @Override
    public void dispose() {
        disposeCount++;
        if (renderer != null) {
            renderer.dispose();
            renderer = null;
        }
        log("disposed");
    }

    private void log(String what) {
        if (Gdx.app != null) {
            Gdx.app.log("CastleDefense", what);
        }
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

    /** Simulation steps run so far. Zero until Phase 4. */
    public long getStepsRun() {
        return 0L;
    }
}
