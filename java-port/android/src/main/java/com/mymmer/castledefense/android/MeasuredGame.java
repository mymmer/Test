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
    private final String quality;
    private final boolean keepAlive;
    private boolean staged;

    MeasuredGame(PlatformServices platform, String scenario, GameMode mode,
                 boolean dumpUi, boolean uiDebug, String quality,
                 boolean keepAlive) {
        super(platform);
        this.scenario = scenario;
        this.mode = mode == null ? GameMode.ENDLESS : mode;
        this.dumpUi = dumpUi;
        this.uiDebug = uiDebug;
        this.quality = quality;
        this.keepAlive = keepAlive;
    }

    /**
     * Keeps the world simulating, so a measurement measures play.
     *
     * <p>The staged scenes drop twenty-odd enemies at an undefended castle, and
     * it falls in seconds. A run in GAMEOVER still <em>draws</em> everything --
     * the scene, dimmed, under the overlay -- so the frame times stay perfectly
     * plausible while the simulation has stopped advancing. The first device
     * sweep measured exactly that in seven scenarios out of nine, and only the
     * state printed beside the numbers gave it away.
     *
     * <p>This is the same trick {@code TestRun.survivingStep} plays for the
     * headless benchmark, for the same reason, and it is debug-harness
     * behaviour: reachable only through an intent extra.
     */
    private void reviveIfFallen() {
        if (!keepAlive || getRun() == null) {
            return;
        }
        getRun().castle().repair(1f);
        if (getRun().world().state() == GameState.GAMEOVER) {
            getRun().world().setState(GameState.PLAYING);
        }
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
                //  The preset is a presentation budget, so it is set on the
                //  renderer.  Phase 13 measures the same scene on all three to
                //  show what quality actually buys -- and the equivalence tests
                //  show it buys nothing in gameplay.
                if (quality != null && getWorldRenderer() != null) {
                    com.mymmer.castledefense.config.QualityConfig q =
                            com.mymmer.castledefense.config.QualityConfig.parse(
                                    quality, null);
                    if (q == null) {
                        android.util.Log.w("CastleDefensePerf",
                                "unknown quality '" + quality + "'");
                    } else {
                        getWorldRenderer().setQuality(q);
                        android.util.Log.i("CastleDefensePerf",
                                "[quality] " + q.name());
                    }
                }
                if (uiDebug && getUiDebugOverlay() != null) {
                    getUiDebugOverlay().setEnabled(true);
                }
            } catch (RuntimeException e) {                   // noqa
                android.util.Log.e("CastleDefensePerf",
                        "scenario '" + scenario + "' failed to stage", e);
            }
        }
        reviveIfFallen();
        super.render();
        reviveIfFallen();
        traceInput();
        //  Dumped a few frames after staging, not on the staging frame: the
        //  interface lays out every frame from the viewport, and the first
        //  layout after a scene change is not necessarily the settled one.
        //  Dumped on every screen change, not once: each screen has its own
        //  controls, and a phase that has to press the shop, the talent tree
        //  and the pause menu needs their real coordinates, not the menu's.
        if (dumpUi && getRenderCount() > 5 && getRun() != null) {
            GameState now = getRun().world().state();
            if (now != lastDumped) {
                lastDumped = now;
                try {
                    android.util.Log.i("CastleDefensePerf", "[screen] " + now);
                    dumpControls();
                } catch (RuntimeException e) {               // noqa
                    android.util.Log.e("CastleDefensePerf", "ui dump failed", e);
                }
            }
        }
    }

    private GameState lastDumped;
    private boolean lastDown;

    /**
     * Reports every pointer press and what the world made of it.
     *
     * <p>Phase 13 found that dragging a mob on the phone did nothing, and no
     * amount of reading the router settled why: every seam it goes through --
     * the processor, the consumers, the world handler -- was correctly wired.
     * This prints the two facts that separate the possibilities: whether a
     * press reached {@code GameInput} at all, and whether the cursor took hold
     * of anything when it did.
     */
    private void traceInput() {
        if (!dumpUi || getInput() == null || getRun() == null) {
            return;
        }
        com.mymmer.castledefense.input.Pointer p = getInput().pointer(0);
        boolean down = p != null && p.isDown();
        if (down == lastDown) {
            return;
        }
        lastDown = down;
        com.mymmer.castledefense.interaction.CursorInteraction c = getRun().cursor();
        android.util.Log.i("CastleDefensePerf", String.format(java.util.Locale.ROOT,
                "[touch] down=%b world=(%.0f,%.0f) owns=%b outcome=%s "
                        + "miss=%.1f of=%s grabbed=%s extra=%d "
                        + "stripping=%s held=%s charging=%s smacking=%s "
                        + "grabCd=%.2f busy=%b state=%s",
                down, p == null ? -1f : p.worldX(), p == null ? -1f : p.worldY(),
                getInput().ownsInteraction(0),
                c.lastOutcome(), c.lastMissDistance(), c.lastMissSubject(),
                c.grabbed() == null ? "-" : c.grabbed().type().id(),
                c.extraGrabbedCount(),
                c.stripping() == null ? "-" : c.stripping().type().id(),
                c.heldItem() == null ? "-" : "YES",
                c.charging() == null ? "-" : "YES",
                c.smacking() == null ? "-" : "YES",
                c.grabCdRemaining(), c.busy(), getRun().world().state()));
    }

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
