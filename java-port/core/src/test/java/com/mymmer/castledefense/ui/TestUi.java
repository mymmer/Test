package com.mymmer.castledefense.ui;

import com.badlogic.gdx.utils.Array;
import com.mymmer.castledefense.game.GameMode;
import com.mymmer.castledefense.game.GameState;
import com.mymmer.castledefense.input.GameInput;
import com.mymmer.castledefense.input.InputRouter;
import com.mymmer.castledefense.input.Pointer;
import com.mymmer.castledefense.input.WorldInteractionHandler;
import com.mymmer.castledefense.persistence.SaveData;
import com.mymmer.castledefense.progress.TestRun;
import com.mymmer.castledefense.render.ViewportSet;
import com.mymmer.castledefense.testsupport.GlStub;

/**
 * A real run with a real interface on top of it, headless.
 *
 * <p>The production {@link UiRoot}, the production screens and the production
 * {@link TestRun} world — nothing about the interface is faked. What is stubbed
 * is only what needs a GPU: the viewport needs a {@code GL20} to lay out, and
 * text needs metrics, so a predictable {@link TextLayout.Measurer} stands in for
 * a font.
 *
 * <p>Presses arrive in <b>UI units</b>, which is what a real pointer carries by
 * the time it reaches {@code UiRoot}.
 */
public final class TestUi {

    /** Metrics that are proportional, predictable and roughly font-shaped. */
    public static final class Metrics implements TextLayout.Measurer {
        /** Average glyph width as a fraction of the size. */
        public float widthRatio = 0.52f;
        /** Line box as a fraction of the size. */
        public float heightRatio = 1.20f;

        @Override
        public float width(String text, float size) {
            return text == null ? 0f : text.length() * size * widthRatio;
        }

        @Override
        public float lineHeight(float size) {
            return size * heightRatio;
        }
    }

    /**
     * What the world was asked to do, if anything.
     *
     * <p>Wired underneath {@link UiRoot} in the real {@link InputRouter}, so a
     * press that the interface should have swallowed shows up here as evidence
     * rather than as a silently grabbed enemy.
     */
    public static final class WorldSpy implements WorldInteractionHandler {
        public final Array<String> log = new Array<>();
        public boolean accept = true;

        @Override
        public boolean onWorldPress(Pointer p) {
            log.add("press");
            return accept;
        }

        @Override
        public void onWorldDrag(Pointer p) {
            log.add("drag");
        }

        @Override
        public void onWorldRelease(Pointer p) {
            log.add("release");
        }

        @Override
        public void onWorldCancel(Pointer p) {
            log.add("cancel");
        }

        public boolean sawAnything() {
            return log.size > 0;
        }
    }

    public final TestRun run;
    public final ViewportSet viewports;
    public final UiRoot ui;
    /** The production input stack: real GameInput, real router, real ordering. */
    public final GameInput input;
    public final InputRouter router;
    public final WorldSpy world = new WorldSpy();
    public final SaveData save = new SaveData();
    public final Metrics metrics = new Metrics();
    public int persistCount;
    /** The window, as the test set it. Screen y is flipped against this. */
    public int screenWidth;
    public int screenHeight;

    public TestUi() {
        this(1280, 720);
    }

    public TestUi(int screenWidth, int screenHeight) {
        GlStub.install();
        this.run = new TestRun();
        this.viewports = new ViewportSet();
        this.viewports.resize(screenWidth, screenHeight);
        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;
        this.save.difficulty = "normal";
        this.ui = new UiRoot(run.run, viewports, run.difficulties, save,
                () -> persistCount++, new TextLayout(metrics));
        this.ui.setPreferredDifficulty(run.difficulty("normal"));
        this.ui.layout();

        //  Exactly the wiring CastleDefenseGame uses: the interface is added as
        //  a consumer BEFORE the world handler is set, because the router walks
        //  consumers first and only then offers the pointer to the world.
        this.input = new GameInput(viewports);
        this.router = new InputRouter(input);
        this.router.addConsumer(ui);
        this.router.setWorldHandler(world);
    }

    /** Resizes the screen and re-lays out, as a rotation would. */
    public TestUi resize(int width, int height) {
        viewports.resize(width, height);
        screenWidth = width;
        screenHeight = height;
        ui.layout();
        return this;
    }

    /** Applies platform cutouts, in screen pixels. */
    public TestUi withInsets(int left, int right, int top, int bottom) {
        ui.setSafeAreaInsets(new com.mymmer.castledefense.platform.SafeAreaInsets(
                left, right, top, bottom));
        ui.layout();
        return this;
    }

    // --- driving the interface ----------------------------------------------

    /** A press at a point in UI units. Returns the control id, or null. */
    public String pressAt(float uiX, float uiY) {
        ui.layout();
        UiScreen modal = ui.modalScreen();
        return modal != null ? modal.press(uiX, uiY) : ui.hud().press(uiX, uiY);
    }

    /**
     * A press in the middle of a named control.
     *
     * <p>Lays out FIRST, then reads the centre: a screen that has just become
     * visible has not positioned its controls yet, and reading a stale (0,0,0,0)
     * rectangle would press the corner of the screen instead of the button.
     */
    public String press(UiRect control) {
        ui.layout();
        return pressAt(control.centerX(), control.centerY());
    }

    // --- the real input path -------------------------------------------------

    /**
     * A screen pixel for a point in UI units.
     *
     * <p>Screen y runs down from the top; UI y runs up from the bottom. The
     * viewport does the conversion, so this is the same arithmetic the pointer
     * itself will go through in reverse.
     */
    public int[] screenFor(float uiX, float uiY) {
        com.badlogic.gdx.math.Vector2 v = new com.badlogic.gdx.math.Vector2(uiX, uiY);
        viewports.getUi().project(v);
        //  project() answers in window pixels measured from the BOTTOM; touch
        //  events are measured from the top.  The window height is the one the
        //  test set, not Gdx.graphics', which is a stub here.
        return new int[] {Math.round(v.x), Math.round(screenHeight - v.y)};
    }

    /** One router pass, then the end-of-frame bookkeeping. */
    public TestUi pump() {
        router.route();
        input.endStep();
        return this;
    }

    /** Finger down at a UI point, through the production stack. */
    public TestUi touchDown(int pointerId, float uiX, float uiY) {
        int[] s = screenFor(uiX, uiY);
        input.touchDown(s[0], s[1], pointerId, 0);
        return pump();
    }

    public TestUi touchMove(int pointerId, float uiX, float uiY) {
        int[] s = screenFor(uiX, uiY);
        input.touchDragged(s[0], s[1], pointerId);
        return pump();
    }

    public TestUi touchUp(int pointerId, float uiX, float uiY) {
        int[] s = screenFor(uiX, uiY);
        input.touchUp(s[0], s[1], pointerId, 0);
        return pump();
    }

    /** Down and up in the same place: a click. */
    public TestUi tap(float uiX, float uiY) {
        return touchDown(0, uiX, uiY).touchUp(0, uiX, uiY);
    }

    /** A click in the middle of a control, laid out first. */
    public TestUi tap(UiRect control) {
        ui.layout();
        return tap(control.centerX(), control.centerY());
    }

    /** Sends a key command through the real routing. */
    public boolean key(UiRoot.Key key) {
        return ui.key(key);
    }

    public GameState state() {
        return run.world.state();
    }

    /** Starts a run through the real menu, so navigation is exercised. */
    public TestUi startRun(GameMode mode, String difficultyId) {
        save.difficulty = difficultyId;
        ui.setPreferredDifficulty(run.difficulty(difficultyId));
        ui.layout();
        press(mode == GameMode.CLASSIC
                ? ui.menu().classicButton() : ui.menu().endlessButton());
        return this;
    }

    /** Leaves the first armoury for the field. */
    public TestUi beginPlaying() {
        ui.layout();
        press(ui.shop().startButton());
        return this;
    }

    /** Steps the world, as the game loop would. */
    public void steps(int n) {
        run.steps(n);
        ui.layout();
    }

    public void seconds(double s) {
        run.seconds(s);
        ui.layout();
    }
}
