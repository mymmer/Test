package com.mymmer.castledefense.android;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Debug;
import android.os.PowerManager;
import android.util.DisplayMetrics;
import android.view.Display;
import com.badlogic.gdx.backends.android.AndroidApplication;
import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration;
import com.mymmer.castledefense.CastleDefenseGame;
import com.mymmer.castledefense.perf.FrameProbe;

/**
 * Android entry point.
 *
 * <p>The only Android-aware code in the project besides the platform services
 * that arrive in Phase 3. It constructs the same {@link CastleDefenseGame} the
 * desktop launcher does — the game itself cannot tell which one started it.
 *
 * <p>Back-button handling (playing → pause, submenu → parent, menu → platform
 * default) is a Phase 10 item; until there are game states to move between,
 * the platform default applies.
 */
public class AndroidLauncher extends AndroidApplication {

    /** Tag for both the device banner and the rolling measurement lines. */
    private static final String TAG = "CastleDefensePerf";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        //  LayerTimes reads this once, when the class loads, and the class is
        //  not touched until the GL thread builds the renderer -- so setting it
        //  here, before initialize() starts that thread, is early enough.
        if (getIntent() != null && getIntent().getBooleanExtra("layerTimes", false)) {
            System.setProperty("castledefense.layerTimes", "true");
        }

        AndroidApplicationConfiguration config = new AndroidApplicationConfiguration();
        config.useImmersiveMode = true;     // hide the nav bar; more world, fewer mis-taps
        config.useAccelerometer = false;
        config.useCompass = false;
        config.useGyroscope = false;
        config.numSamples = 0;              // MSAA off: fill rate matters more on mobile
        config.useWakelock = true;

        //  A scenario extra swaps in the measuring subclass; without one this
        //  is exactly the game a player launches.
        String scenario = getIntent() == null
                ? null : getIntent().getStringExtra("scenario");
        AndroidPlatformServices services = new AndroidPlatformServices(this);
        boolean dumpUi = getIntent() != null
                && getIntent().getBooleanExtra("dumpUi", false);
        boolean uiDebug = getIntent() != null
                && getIntent().getBooleanExtra("uiDebug", false);
        String quality = getIntent() == null
                ? null : getIntent().getStringExtra("quality");
        boolean measured = (scenario != null && !scenario.isEmpty())
                || dumpUi || uiDebug || quality != null;
        CastleDefenseGame game = measured
                ? new MeasuredGame(services, scenario, modeFrom(getIntent()),
                        dumpUi, uiDebug, quality,
                        getIntent().getBooleanExtra("keepAlive", false))
                : new CastleDefenseGame(services);
        initialize(game, config);
        maybeMeasure(game, getIntent());
    }

    /**
     * Turns on Phase 13's frame probe when the launching intent asks for it.
     *
     * <p>A phone has no command line, so the equivalent of {@code --bench} is an
     * intent extra:
     *
     * <pre>
     *   adb shell am start -n com.mymmer.castledefense/.android.AndroidLauncher      *       -e bench early-wave --ei benchWindow 300
     * </pre>
     *
     * <p>Absent the extra nothing is allocated and the game never learns the
     * probe exists. This is debug instrumentation reached only through an
     * explicit launch, not a shipped feature or a menu.
     */
    private void maybeMeasure(CastleDefenseGame game, Intent intent) {
        if (intent == null) {
            return;
        }
        String label = intent.getStringExtra("bench");
        if (label == null || label.isEmpty()) {
            return;
        }
        int window = intent.getIntExtra("benchWindow", 300);
        logDeviceBanner(label);

        FrameProbe probe = new FrameProbe(window, new FrameProbe.Sink() {
            @Override
            public void line(String text) {
                android.util.Log.i(TAG, text);
            }
        });
        probe.setLabel(label);
        //  So a window that timed a dead world says so.
        final CastleDefenseGame g = game;
        probe.setSceneStats(new FrameProbe.DeviceStats() {
            @Override
            public String stats() {
                if (g.getRun() == null) {
                    return "state=none";
                }
                int alive = 0;
                for (int i = 0; i < g.getRun().horde().size(); i++) {
                    if (g.getRun().horde().get(i).isAlive()) {
                        alive++;
                    }
                }
                return "state=" + g.getRun().world().state() + " alive=" + alive;
            }
        });
        probe.setDeviceStats(new FrameProbe.DeviceStats() {
            @Override
            public String stats() {
                return deviceStats();
            }
        });
        game.setFrameProbe(probe);
    }

    /** {@code -e mode classic} selects Classic; anything else means Endless. */
    private static com.mymmer.castledefense.game.GameMode modeFrom(Intent intent) {
        String m = intent == null ? null : intent.getStringExtra("mode");
        return "classic".equalsIgnoreCase(m)
                ? com.mymmer.castledefense.game.GameMode.CLASSIC
                : com.mymmer.castledefense.game.GameMode.ENDLESS;
    }

    /**
     * The facts a measurement is worthless without: which chip, which Android,
     * which panel, and how fast it refreshes.
     *
     * <p>Refresh rate especially -- a 120 Hz phone that holds 60 fps is missing
     * half its frames, and a 60 Hz phone that holds 60 fps is perfect. The same
     * two numbers, opposite conclusions.
     */
    //  getDefaultDisplay/getRealMetrics are deprecated at API 30/31 in favour
    //  of WindowMetrics, which does not exist below 30.  minSdk here is 21, so
    //  the deprecated pair is the one path that works on every supported
    //  device; this is debug instrumentation, and a second code path gated on
    //  API 30 would be more code to be wrong for no measured benefit.
    @SuppressWarnings("deprecation")
    private void logDeviceBanner(String label) {
        DisplayMetrics dm = new DisplayMetrics();
        float refresh = 0f;
        int rotation = -1;
        try {
            Display display = getWindowManager().getDefaultDisplay();
            display.getRealMetrics(dm);
            refresh = display.getRefreshRate();
            rotation = display.getRotation();
        } catch (RuntimeException e) {                       // noqa
            getResources().getDisplayMetrics();
        }
        android.util.Log.i(TAG, String.format(java.util.Locale.ROOT,
                "[device] model=%s %s board=%s soc=%s abi=%s android=%s api=%d "
                        + "display=%dx%d dpi=%d density=%.2f refresh=%.1fHz "
                        + "rotation=%d label=%s",
                Build.MANUFACTURER, Build.MODEL, Build.BOARD,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                        ? Build.SOC_MODEL : Build.HARDWARE,
                Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "?",
                Build.VERSION.RELEASE, Build.VERSION.SDK_INT,
                dm.widthPixels, dm.heightPixels, dm.densityDpi, dm.density,
                refresh, rotation, label));
    }

    /**
     * Per-window memory, GC and thermal state.
     *
     * <p>GC counts are cumulative totals, not deltas -- a window that adds none
     * did not collect. Thermal status is API 29+, and its absence is reported
     * rather than guessed: throttling that cannot be observed must not be
     * assumed away.
     */
    /** Logged once, the first time stats are asked for. */
    private boolean insetsLogged;

    /**
     * What the window actually reports, against what the game decided.
     *
     * <p>Deferred to the first measured window rather than {@code onCreate},
     * because {@code getRootWindowInsets()} returns null until the decor view is
     * attached -- reading it too early reports "no cutout" on a phone that
     * plainly has one, which is exactly the sort of quiet wrong answer this
     * phase exists to catch.
     *
     * <p>Cutout insets and <em>gesture</em> insets are separate things and both
     * are printed: a control can sit clear of the camera hole and still be in
     * the strip the system takes swipes from.
     */
    @SuppressWarnings("deprecation")
    private void logInsetsOnce() {
        if (insetsLogged) {
            return;
        }
        insetsLogged = true;
        //  Cutouts arrive with Android 9, and the window-insets accessor with
        //  API 23.  Below that there is nothing to report and no way to ask,
        //  which is the same line AndroidPlatformServices.safeAreaInsets draws.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            android.util.Log.i(TAG, "[insets] no cutout API below Android 9");
            return;
        }
        try {
            android.view.WindowInsets wi =
                    getWindow().getDecorView().getRootWindowInsets();
            if (wi == null) {
                android.util.Log.i(TAG, "[insets] not available yet");
                return;
            }
            StringBuilder b = new StringBuilder("[insets] ");
            android.view.DisplayCutout dc = wi.getDisplayCutout();
            if (dc == null) {
                b.append("cutout=none ");
            } else {
                b.append(String.format(java.util.Locale.ROOT,
                        "cutout=l%d,r%d,t%d,b%d ", dc.getSafeInsetLeft(),
                        dc.getSafeInsetRight(), dc.getSafeInsetTop(),
                        dc.getSafeInsetBottom()));
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                android.graphics.Insets g = wi.getSystemGestureInsets();
                android.graphics.Insets m = wi.getMandatorySystemGestureInsets();
                b.append(String.format(java.util.Locale.ROOT,
                        "gesture=l%d,r%d,t%d,b%d mandatory=l%d,r%d,t%d,b%d ",
                        g.left, g.right, g.top, g.bottom,
                        m.left, m.right, m.top, m.bottom));
            }
            b.append("game=").append(
                    new AndroidPlatformServices(this).safeAreaInsets());
            android.util.Log.i(TAG, b.toString());
        } catch (RuntimeException e) {                       // noqa
            android.util.Log.w(TAG, "[insets] unavailable", e);
        }
    }

    private String deviceStats() {
        logInsetsOnce();
        Runtime rt = Runtime.getRuntime();
        long usedKb = (rt.totalMemory() - rt.freeMemory()) / 1024L;
        long nativeKb = Debug.getNativeHeapAllocatedSize() / 1024L;
        //  Per-layer times, when asked for.  Reported alongside the frame
        //  numbers so the two can be read together: if the layers sum to far
        //  less than the frame's CPU time, the frame is waiting, not working.
        if (com.mymmer.castledefense.render.LayerTimes.enabled()) {
            android.util.Log.i(TAG, "[layers] "
                    + com.mymmer.castledefense.render.LayerTimes.report());
            android.util.Log.i(TAG, "[ops] "
                    + com.mymmer.castledefense.render.LayerTimes.ops());
            com.mymmer.castledefense.render.LayerTimes.reset();
        }
        String gc = "?";
        String gcTime = "?";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            String c = Debug.getRuntimeStat("art.gc.gc-count");
            String t = Debug.getRuntimeStat("art.gc.gc-time");
            gc = c == null ? "?" : c;
            gcTime = t == null ? "?" : t;
        }
        String thermal = "n/a";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) {
                thermal = String.valueOf(pm.getCurrentThermalStatus());
            }
        }
        return String.format(java.util.Locale.ROOT,
                "heap=%dKB native=%dKB gc=%s gcms=%s thermal=%s",
                usedKb, nativeKb, gc, gcTime, thermal);
    }
}
