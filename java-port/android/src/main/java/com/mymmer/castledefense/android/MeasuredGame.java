package com.mymmer.castledefense.android;

import com.mymmer.castledefense.CastleDefenseGame;
import com.mymmer.castledefense.devtools.VisualScenarios;
import com.mymmer.castledefense.game.GameMode;
import com.mymmer.castledefense.game.GameState;
import com.mymmer.castledefense.platform.PlatformServices;

/**
 * The game, plus the ability to stage one controlled scene on the first frame.
 *
 * <h2>Why the device needs this</h2>
 *
 * <p>Phase 13 has to measure named scenarios — an early wave, a dense late
 * Endless crowd, several bosses at once — and compare the numbers against the
 * desktop baseline. That comparison is only meaningful if both machines are
 * running <b>the same scene</b>, and a scene reached by playing to it by hand is
 * neither reproducible nor the same twice.
 *
 * <p>{@link VisualScenarios} already builds exactly those states through
 * production gameplay calls, and it lives in core, so the phone can build them
 * too. This is the same trick the desktop launcher's frame-limited game plays,
 * for the same reason; it is duplicated rather than shared because sharing it
 * would mean editing the verified desktop measurement path to serve Android,
 * and ten lines is a cheaper price than that risk.
 *
 * <h2>Not a shipped feature</h2>
 *
 * <p>Reachable only by launching the activity with an explicit intent extra. A
 * player cannot get here, there is no menu entry, and with no extra the launcher
 * constructs the ordinary {@link CastleDefenseGame} instead.
 */
final class MeasuredGame extends CastleDefenseGame {

    private final String scenario;
    private final GameMode mode;
    private final boolean dumpUi;
    private final boolean uiDebug;
    private boolean staged;

    MeasuredGame(PlatformServices platform, String scenario, GameMode mode,
                 boolean dumpUi, boolean uiDebug) {
        super(platform);
        this.scenario = scenario;
        this.mode = mode == null ? GameMode.ENDLESS : mode;
        this.dumpUi = dumpUi;
        this.uiDebug = uiDebug;
    }

    /**
     * Staged on the first frame, not in {@code create()}.
     *
     * <p>By the first frame the viewports have their real size, so the scene is
     * built against the phone's actual screen rather than a default one — which
     * matters here more than it does on the desktop, because the phone's aspect
     * ratio is the thing under test.
     */
    @Override
    public void render() {
        if (!staged) {
            staged = true;
            try {
                stageScenario();
                if (uiDebug && getUiDebugOverlay() != null) {
                    getUiDebugOverlay().setEnabled(true);
                }
            } catch (RuntimeException e) {                   // noqa
                android.util.Log.e("CastleDefensePerf",
                        "scenario '" + scenario + "' failed to stage", e);
            }
        }
        super.render();
        //  Dumped a few frames after staging, not on the staging frame: the
        //  interface lays out every frame from the viewport, and the first
        //  layout after a scene change is not necessarily the settled one.
        if (dumpUi && !dumped && getRenderCount() > 5) {
            dumped = true;
            try {
                dumpControls();
            } catch (RuntimeException e) {                   // noqa
                android.util.Log.e("CastleDefensePerf", "ui dump failed", e);
            }
        }
    }

    private boolean dumped;

    /**
     * Every control's hit rectangle, in interface units and in screen pixels.
     *
     * <p>Phase 13 has to press real controls on a real phone, and pressing them
     * by reading coordinates off a screenshot is guesswork that silently tests
     * the wrong pixel. This prints where each control genuinely is, so a tap can
     * be aimed at a measured centre and, just as usefully, so a control that has
     * drifted outside the usable screen can be seen as a number rather than
     * argued about from an image.
     *
     * <p>Screen y is converted to <b>top-down</b>, because that is the space
     * {@code adb shell input tap} and every screenshot use, while the interface
     * works bottom-up.
     */
    private void dumpControls() {
        if (getUi() == null || getViewports() == null) {
            return;
        }
        com.badlogic.gdx.utils.viewport.Viewport vp = getViewports().getUi();
        float sx = vp.getScreenWidth() / vp.getWorldWidth();
        float sy = vp.getScreenHeight() / vp.getWorldHeight();
        int screenH = getViewports().getScreenHeight();
        android.util.Log.i("CastleDefensePerf", String.format(java.util.Locale.ROOT,
                "[ui] viewport screen=%d,%d %dx%d world=%.0fx%.0f scale=%.3f,%.3f "
                        + "display=%dx%d",
                vp.getScreenX(), vp.getScreenY(), vp.getScreenWidth(),
                vp.getScreenHeight(), vp.getWorldWidth(), vp.getWorldHeight(),
                sx, sy, getViewports().getScreenWidth(), screenH));
        com.badlogic.gdx.utils.Array<com.mymmer.castledefense.ui.UiRect> all =
                getUi().allControls();
        for (int i = 0; i < all.size; i++) {
            com.mymmer.castledefense.ui.UiRect r = all.get(i);
            //  bottom-up interface units -> top-down screen pixels
            float px = vp.getScreenX() + r.hitX() * sx;
            float pw = r.hitWidth() * sx;
            float pyBottom = vp.getScreenY() + r.hitY() * sy;
            float ph = r.hitHeight() * sy;
            float pyTop = screenH - (pyBottom + ph);
            android.util.Log.i("CastleDefensePerf", String.format(java.util.Locale.ROOT,
                    "[ui] %-28s hit=(%.0f,%.0f %.0fx%.0f) screen=(%.0f,%.0f %.0fx%.0f) "
                            + "centre=(%.0f,%.0f)",
                    r.id, r.hitX(), r.hitY(), r.hitWidth(), r.hitHeight(),
                    px, pyTop, pw, ph, px + pw / 2f, pyTop + ph / 2f));
        }
    }

    private void stageScenario() {
        if (scenario == null || getRun() == null) {
            return;
        }
        if (getRun().world().state() == GameState.MENU) {
            startRun(mode);
        }
        //  Endless opens in its shop; a scene is only meaningful once the world
        //  is actually running.
        if (getUi() != null && getRun().world().state() == GameState.SHOP) {
            getUi().navigation().startPlaying();
        }
        if (!VisualScenarios.build(getRun(), scenario)) {
            android.util.Log.w("CastleDefensePerf", "unknown scenario '"
                    + scenario + "'; known: "
                    + java.util.Arrays.toString(VisualScenarios.NAMES));
            return;
        }
        android.util.Log.i("CastleDefensePerf",
                "[scene] " + scenario + " -> " + getRun().describeRun());
    }

}
