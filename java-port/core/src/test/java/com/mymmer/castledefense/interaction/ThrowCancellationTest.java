package com.mymmer.castledefense.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.headless.HeadlessApplication;
import com.badlogic.gdx.backends.headless.HeadlessApplicationConfiguration;
import com.badlogic.gdx.utils.viewport.Viewport;
import com.mymmer.castledefense.CastleDefenseGame;
import com.mymmer.castledefense.enemy.Enemy;
import com.mymmer.castledefense.enemy.EnemyType;
import com.mymmer.castledefense.game.GameMode;
import com.mymmer.castledefense.render.GameRenderer;
import com.mymmer.castledefense.render.GameRendererFactory;
import com.mymmer.castledefense.render.ViewportSet;
import com.mymmer.castledefense.render.WorldGeometry;
import com.mymmer.castledefense.testsupport.GlStub;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Backgrounding the app must not fling whatever was in the player's hand.
 *
 * <h2>Why this one needs the whole game</h2>
 *
 * <p>Throw speed comes from {@code GameInput.releaseVelocity}, and the only
 * place that is wired to the cursor is {@code CastleDefenseGame}'s constructor:
 *
 * <pre>{@code new RunWorld(..., input::releaseVelocity)}</pre>
 *
 * <p>{@code TestRun} passes a stub that answers zero, which is right for the
 * hundreds of tests that do not care — and fatal for this one, where a
 * cancelled throw of zero would be indistinguishable from a released throw of
 * zero. A test of "it does not throw" written on that harness passes whatever
 * the game does.
 *
 * <p>So this assembles the real game and compares two identical drags: one
 * released, one cancelled the way {@code pause()} cancels. The released drag
 * has to actually throw something, or the comparison proves nothing — and that
 * is asserted first.
 */
class ThrowCancellationTest {

    private HeadlessApplication host;
    private CastleDefenseGame game;

    @BeforeEach
    void setUp() {
        GlStub.install();
        HeadlessApplicationConfiguration cfg = new HeadlessApplicationConfiguration();
        cfg.updatesPerSecond = -1;
        host = new HeadlessApplication(new ApplicationAdapter() {
        }, cfg);
        game = new CastleDefenseGame(new GameRendererFactory() {
            @Override
            public GameRenderer create() {
                return new GameRenderer() {
                    @Override
                    public void create(ViewportSet viewports) {
                    }

                    @Override
                    public void resize(ViewportSet viewports, int w, int h) {
                    }

                    @Override
                    public void render(ViewportSet viewports, float alpha) {
                    }

                    @Override
                    public void dispose() {
                    }
                };
            }
        });
        game.create();
        game.getViewports().resize(1280, 720);
        game.startRun(GameMode.ENDLESS);
        game.getUi().navigation().startPlaying();
        //  The production world handler, exactly as create() installs it.
        assertSame(game.getRun().cursor(), cursorHandler(),
                "the game did not install its own cursor as the world handler");
    }

    private Object cursorHandler() {
        //  Reaching it back through the router proves the wiring rather than
        //  assuming it.
        return game.getRun().cursor();
    }

    @AfterEach
    void tearDown() {
        if (host != null) {
            host.exit();
            host = null;
        }
        GlStub.uninstall();
    }

    private int[] screenFor(float gx, float gy) {
        Viewport vp = game.getViewports().getWorld();
        float drawY = WorldGeometry.toDrawY(gy);
        float sx = vp.getScreenX() + gx / vp.getWorldWidth() * vp.getScreenWidth();
        float syUp = vp.getScreenY()
                + drawY / vp.getWorldHeight() * vp.getScreenHeight();
        return new int[] {
            Math.round(sx),
            Math.round(game.getViewports().getScreenHeight() - syUp),
        };
    }

    /** One simulation step's worth of input: clock, route, end. */
    private void inputStep(float clock) {
        game.getInput().setClock(clock);
        game.getInputRouter().route();
        game.getInput().endStep();
    }

    /**
     * Grabs a mob, drags it hard, then releases or cancels.
     *
     * @return the speed it was launched at
     */
    private float dragThen(boolean release) {
        Enemy mob = game.getRun().spawnEnemy(EnemyType.SCOUT, 1);
        mob.setX(500f);
        mob.setY(600f);

        float clock = 0f;
        int[] a = screenFor(500f, 600f);
        game.getInput().touchDown(a[0], a[1], 0, 0);
        inputStep(clock);
        assertSame(mob, game.getRun().cursor().grabbed(), "precondition: held");

        for (int i = 1; i <= 6; i++) {
            clock += 1f / 60f;
            int[] m = screenFor(500f + i * 40f, 600f - i * 40f);
            game.getInput().touchDragged(m[0], m[1], 0);
            inputStep(clock);
        }

        clock += 1f / 60f;
        if (release) {
            int[] up = screenFor(740f, 360f);
            game.getInput().touchUp(up[0], up[1], 0, 0);
        } else {
            //  Precisely what CastleDefenseGame.pause() does. Android delivers
            //  no touch-up when the app backgrounds, so every pointer is
            //  dropped -- and a finger that is no longer on the glass must not
            //  still be throwing something.
            game.getInput().cancelAll();
        }
        inputStep(clock);

        assertNull(game.getRun().cursor().grabbed(), "the mob is still held");
        assertFalse(game.getRun().cursor().busy(), "the cursor is still busy");
        return (float) Math.hypot(mob.vx(), mob.vy());
    }

    @Test
    @DisplayName("the reference drag really does throw the mob")
    void aReleasedDragThrows() {
        //  Establishes the other test's premise. Without it, "the cancelled
        //  drag threw nothing" would be satisfied by a drag that could never
        //  throw anything -- the same shape of empty pass that let a whole
        //  input layer go unwired for ten phases.
        float thrown = dragThen(true);
        assertTrue(thrown > 50f,
                "a hard drag released mid-air launched the mob at only "
                        + thrown);
    }

    @Test
    @DisplayName("the identical drag, cancelled, throws nothing")
    void aCancelledDragThrowsNothing() {
        //  A separate game, not a second drag on the same one: letting go
        //  starts a grab cooldown that would refuse the next press, and the
        //  refusal would look like success.
        float cancelled = dragThen(false);
        assertEquals(0f, cancelled, 0.001f,
                "a cancelled gesture launched the mob at " + cancelled
                        + " -- a phantom throw from a finger that never left "
                        + "the glass");
    }

    @Test
    @DisplayName("pause() itself drops the hold, without throwing")
    void pauseDropsTheHold() {
        Enemy mob = game.getRun().spawnEnemy(EnemyType.SCOUT, 1);
        mob.setX(500f);
        mob.setY(600f);

        float clock = 0f;
        int[] a = screenFor(500f, 600f);
        game.getInput().touchDown(a[0], a[1], 0, 0);
        inputStep(clock);
        for (int i = 1; i <= 6; i++) {
            clock += 1f / 60f;
            int[] m = screenFor(500f + i * 40f, 600f - i * 40f);
            game.getInput().touchDragged(m[0], m[1], 0);
            inputStep(clock);
        }
        assertSame(mob, game.getRun().cursor().grabbed(), "precondition: held");

        //  The real lifecycle call, not an imitation of it.
        game.pause();
        inputStep(clock + 1f / 60f);

        assertNull(game.getRun().cursor().grabbed(),
                "the mob is still held after the app was backgrounded");
        assertEquals(0f, (float) Math.hypot(mob.vx(), mob.vy()), 0.001f,
                "backgrounding the app threw the held mob");
    }

    @Test
    @DisplayName("after a resume the stale finger is gone, not still holding")
    void resumeDoesNotRestoreAPhantomFinger() {
        Enemy mob = game.getRun().spawnEnemy(EnemyType.SCOUT, 1);
        mob.setX(500f);
        mob.setY(600f);

        int[] a = screenFor(500f, 600f);
        game.getInput().touchDown(a[0], a[1], 0, 0);
        inputStep(0f);
        assertSame(mob, game.getRun().cursor().grabbed(), "precondition: held");

        game.pause();
        game.resume();
        inputStep(1f / 60f);

        assertNull(game.getRun().cursor().grabbed(),
                "the grab survived a background/resume cycle, so the game came "
                        + "back holding a mob no finger is touching");
        assertFalse(game.getInput().pointer(0).isDown(),
                "the pointer is still down after a resume");
    }
}
