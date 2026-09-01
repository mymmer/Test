package com.mymmer.castledefense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.headless.HeadlessApplication;
import com.badlogic.gdx.backends.headless.HeadlessApplicationConfiguration;
import com.mymmer.castledefense.render.GameRenderer;
import com.mymmer.castledefense.render.GameRendererFactory;
import com.mymmer.castledefense.render.NoOpRenderer;
import com.mymmer.castledefense.testsupport.GlStub;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves the CI foundation: the real application class starts, resizes, renders,
 * survives a pause/resume cycle and disposes cleanly with no window and no GPU.
 * Every later phase's simulation test is built on this fixture.
 *
 * <p>The headless backend is used only to install the {@code Gdx} statics
 * (app, files, net) — it is handed a throwaway listener. The game under test is
 * driven <em>synchronously from the test thread</em> instead of by libGDX's
 * application thread, because that loop calls {@code pause()} and
 * {@code dispose()} on its listener the moment it is configured not to render,
 * which would tear the fixture down before a single assertion ran. Driving it by
 * hand also makes every test deterministic, which matters much more once there
 * is a simulation to step.
 */
class CastleDefenseGameHeadlessTest {

    private HeadlessApplication host;
    private CastleDefenseGame game;
    private NoOpRenderer renderer;

    @BeforeEach
    void setUp() {
        GlStub.install();
        HeadlessApplicationConfiguration cfg = new HeadlessApplicationConfiguration();
        cfg.updatesPerSecond = -1;
        host = new HeadlessApplication(new ApplicationAdapter() {
        }, cfg);

        renderer = new NoOpRenderer();
        game = new CastleDefenseGame(new GameRendererFactory() {
            @Override
            public GameRenderer create() {
                return renderer;
            }
        });
        game.create();
    }

    @AfterEach
    void tearDown() {
        if (game.getDisposeCount() == 0) {
            game.dispose();
        }
        host.exit();
        GlStub.uninstall();
    }

    @Test
    @DisplayName("the application starts headlessly and wires up its renderer")
    void startsHeadless() {
        assertNotNull(Gdx.app, "the headless backend must install Gdx.app");
        assertNotNull(Gdx.files, "and Gdx.files, which persistence will need");
        assertEquals(1, game.getCreateCount());
        assertEquals(1, renderer.getCreateCount());
        assertNotNull(game.getViewports());
        assertNotNull(game.getRenderer());
    }

    @Test
    @DisplayName("resize lays the viewports out and reaches the renderer")
    void resizePropagates() {
        game.resize(2400, 1080);
        assertEquals(1, renderer.getResizeCount());
        assertEquals(1920, game.getViewports().getWorld().getScreenWidth(),
                "a 20:9 screen must fit the world to 1920x1080");
        assertEquals(240, game.getViewports().getLetterboxX());

        // a minimised window reports 0x0 on Android; it must not be laid out
        game.resize(0, 0);
        assertEquals(1, renderer.getResizeCount(), "0x0 must not reach the renderer");
        assertEquals(1920, game.getViewports().getWorld().getScreenWidth(),
                "and must not disturb the last good layout");
    }

    @Test
    @DisplayName("frames render and the pause/resume cycle is observable")
    void lifecycleCycle() {
        game.resize(1280, 720);
        for (int i = 0; i < 10; i++) {
            game.render();
        }
        assertEquals(10, game.getRenderCount());
        assertEquals(10, renderer.getRenderCount());
        assertFalse(game.isPaused());

        game.pause();
        assertTrue(game.isPaused());
        assertEquals(1, game.getPauseCount());

        game.resume();
        assertFalse(game.isPaused());
        assertEquals(1, game.getResumeCount());

        // Phase 4 asserts here that a long pause never reaches a simulation step
        assertEquals(0L, game.getStepsRun(), "there is no simulation yet — Phase 4 adds it");
    }

    @Test
    @DisplayName("dispose releases the renderer and is safe to reach twice")
    void disposeReleases() {
        game.resize(1280, 720);
        game.render();
        game.dispose();
        assertEquals(1, renderer.getDisposeCount(), "the renderer must be released");
        assertNull(game.getRenderer(), "and dropped");

        game.dispose();     // a second dispose must not explode
        assertEquals(1, renderer.getDisposeCount(), "and not be released twice");
        assertEquals(2, game.getDisposeCount());
    }
}
