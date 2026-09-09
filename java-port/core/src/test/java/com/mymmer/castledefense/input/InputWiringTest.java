package com.mymmer.castledefense.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.headless.HeadlessApplication;
import com.badlogic.gdx.backends.headless.HeadlessApplicationConfiguration;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputProcessor;
import com.mymmer.castledefense.CastleDefenseGame;
import com.mymmer.castledefense.game.GameMode;
import com.mymmer.castledefense.game.GameState;
import com.mymmer.castledefense.render.GameRenderer;
import com.mymmer.castledefense.render.GameRendererFactory;
import com.mymmer.castledefense.render.ViewportSet;
import com.mymmer.castledefense.testsupport.GlStub;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The game is actually connected to the platform's input.
 *
 * <h2>Why this test exists</h2>
 *
 * <p>Phase 13 put the game on a phone, pressed CLASSIC WAVES, and nothing
 * happened. Nor did SETTINGS, nor the difficulty buttons. The cause was that
 * <b>nothing in the project ever called {@code Gdx.input.setInputProcessor}</b>,
 * so libGDX had nowhere to deliver a touch and {@code GameInput.touchDown} was
 * never once invoked in a running build - on any platform, since Phase 3.
 *
 * <p>It survived that long because everything that exercises input does so
 * correctly but directly: the router tests call {@code GameInput} themselves,
 * which is the right way to test a router, and every screenshot is a staged
 * scenario that never presses anything. The gap was not in the input code,
 * which works; it was the one line that hands it to the platform.
 *
 * <p>So these tests assert the <em>wiring</em>, which is the part no other test
 * could see.
 */
class InputWiringTest {

    private HeadlessApplication host;
    private CastleDefenseGame game;
    private InputProcessor registered;
    private final java.util.Set<Integer> caught = new java.util.HashSet<>();

    /**
     * A recording {@code Gdx.input}.
     *
     * <p>{@link Input} is a wide interface and the game uses almost none of it,
     * so it is proxied rather than hand-implemented: this way the test does not
     * have to be edited every time libGDX adds a method, and it cannot pass by
     * accident, because the two calls it cares about are captured explicitly.
     */
    @BeforeEach
    void setUp() {
        GlStub.install();
        //  The headless backend supplies Gdx.files and Gdx.app, which the game
        //  needs to load its tables at all.  It also installs an input of its
        //  own, so the recording proxy goes in AFTER it -- otherwise this test
        //  would assert against the backend's object and pass while proving
        //  nothing.
        HeadlessApplicationConfiguration cfg = new HeadlessApplicationConfiguration();
        cfg.updatesPerSecond = -1;
        host = new HeadlessApplication(new ApplicationAdapter() {
        }, cfg);
        Gdx.input = (Input) Proxy.newProxyInstance(
                Input.class.getClassLoader(), new Class<?>[]{Input.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method m, Object[] args) {
                        if ("setInputProcessor".equals(m.getName())) {
                            registered = (InputProcessor) args[0];
                            return null;
                        }
                        if ("setCatchKey".equals(m.getName())
                                && Boolean.TRUE.equals(args[1])) {
                            caught.add((Integer) args[0]);
                            return null;
                        }
                        Class<?> r = m.getReturnType();
                        if (r == boolean.class) {
                            return false;
                        }
                        if (r == int.class) {
                            return 0;
                        }
                        if (r == long.class) {
                            return 0L;
                        }
                        if (r == float.class) {
                            return 0f;
                        }
                        return null;
                    }
                });
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
    }

    @AfterEach
    void tearDown() {
        if (host != null) {
            host.exit();
            host = null;
        }
        Gdx.input = null;
        GlStub.uninstall();
    }

    @Test
    @DisplayName("create() hands the game's own input to libGDX")
    void registersTheInputProcessor() {
        assertNotNull(registered,
                "setInputProcessor was never called -- the running game would "
                        + "receive no touches at all");
        assertSame(game.getInput(), registered,
                "a different processor was registered, so the router's input "
                        + "would never be filled in");
    }

    @Test
    @DisplayName("Android's Back key is caught, so navigation can route it")
    void catchesBack() {
        assertTrue(caught.contains(Input.Keys.BACK),
                "uncaught, Android treats Back as 'leave the activity' and "
                        + "Navigation.back() never runs");
    }

    @Test
    @DisplayName("Back is latched once and handed over once")
    void backIsLatchedOnce() {
        GameInput in = game.getInput();
        assertFalse(in.consumeBack(), "nothing pressed yet");
        assertTrue(in.keyDown(Input.Keys.BACK), "Back must be claimed");
        assertTrue(in.consumeBack(), "the press is delivered");
        assertFalse(in.consumeBack(), "and only once");
    }

    @Test
    @DisplayName("Escape is the desktop's Back; other keys are not")
    void escapeIsBackAndOthersAreNot() {
        GameInput in = game.getInput();
        assertTrue(in.keyDown(Input.Keys.ESCAPE));
        assertTrue(in.consumeBack());
        assertFalse(in.keyDown(Input.Keys.SPACE), "unrelated keys are not ours");
        assertFalse(in.consumeBack(), "and must not latch a Back");
    }

    @Test
    @DisplayName("Back during a run pauses it, through render(), not directly")
    void backPausesTheRunThroughTheFrame() {
        //  Classic opens in the armoury before the first wave, so the run has
        //  to be sent into the field before "pause the fight" means anything.
        game.startRun(GameMode.CLASSIC);
        game.getUi().navigation().startPlaying();
        assertEquals(GameState.PLAYING, game.getRun().world().state(),
                "precondition: the run is live");

        game.getInput().keyDown(Input.Keys.BACK);
        game.render();

        assertEquals(GameState.PAUSED, game.getRun().world().state(),
                "Navigation.back()'s documented PLAYING -> PAUSED route never "
                        + "ran before Phase 13, because nothing called it");
    }

    @Test
    @DisplayName("one press is one navigation, however many steps the frame ran")
    void onePressIsOneNavigation() {
        game.startRun(GameMode.CLASSIC);
        game.getUi().navigation().startPlaying();
        game.getInput().keyDown(Input.Keys.BACK);
        game.render();
        assertEquals(GameState.PAUSED, game.getRun().world().state());

        //  A second frame with no new press must not toggle it back: Back is
        //  consumed, not held.
        game.render();
        assertEquals(GameState.PAUSED, game.getRun().world().state(),
                "the latch was not cleared, so one press navigated twice");
    }
}
