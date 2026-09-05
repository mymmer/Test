package com.mymmer.castledefense.lwjgl3;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.utils.ScreenUtils;
import com.mymmer.castledefense.CastleDefenseGame;
import com.mymmer.castledefense.config.GameConfig;
import com.mymmer.castledefense.game.GameMode;
import com.mymmer.castledefense.game.GameState;
import com.mymmer.castledefense.platform.SafeAreaInsets;

/**
 * Desktop entry point — the development loop.
 *
 * <p>Arguments (all optional):
 * <pre>
 *   --size WxH        window size, default 1280x720
 *   --device NAME     a device frame: size and cutouts together (see below)
 *   --insets L,R,T,B  simulated safe-area cutouts, in screen pixels
 *   --frames N        quit after N rendered frames (smoke testing / CI)
 *   --screenshot FILE write a PNG of the last frame before quitting
 *   --screen NAME     open a screen before the screenshot (see below)
 *   --ui-debug        draw the layout overlay: safe area, visual/hit bounds
 *   --no-vsync        uncap the frame rate
 * </pre>
 *
 * <p>Device frames, so a phone-shaped layout can be checked from a desk:
 * <pre>
 *   desktop      1280x720   no cutouts       the reference
 *   phone169      960x540   no cutouts       an old 16:9 handset
 *   phone189     1080x540   top 28           18:9
 *   phone195     1170x540   top 34, bot 16   19.5:9, notch and gesture bar
 *   phone209     1200x540   top 40, bot 18   20:9, punch-hole and gesture bar
 *   portrait      540x1200  top 40, bot 18   the same phone held upright
 *   tablet       1600x1000  no cutouts
 * </pre>
 *
 * <p>The insets go through {@code PlatformServices.setSafeAreaInsets} — the same
 * seam Android reports real cutouts on — so what is being checked on desktop is
 * the production path and not a simulation of it.
 *
 * <p>{@code --screen} drives the real navigation graph rather than setting a
 * state: {@code menu}, {@code settings}, {@code shop}, {@code talents},
 * {@code playing}, {@code paused}, {@code gameover}, {@code bosses} (playing,
 * with two live bosses so both health bars are on screen).
 *
 * <p>{@code --frames} with {@code --screenshot} is how a layout change is
 * checked without a human looking at a window, which keeps the desktop loop
 * useful from CI as well as from a desk.
 *
 * <p>Phase 10 adds the device-frame presets (16:9 / 18:9 / 19.5:9 / 20:9 and
 * simulated cutout insets) described in the brief; Phase 2 keeps the launcher
 * to what the foundation needs.
 */
public final class Lwjgl3Launcher {

    private Lwjgl3Launcher() {
    }

    public static void main(String[] args) {
        Options options = Options.parse(args);

        Lwjgl3ApplicationConfiguration config = new Lwjgl3ApplicationConfiguration();
        config.setTitle(GameConfig.TITLE);
        config.setWindowedMode(options.width, options.height);
        config.setWindowSizeLimits(640, 360, -1, -1);
        config.useVsync(options.vsync);
        // libGDX advises foreground FPS a little above the display rate
        config.setForegroundFPS(options.vsync ? 0 : 300);
        config.setBackBufferConfig(8, 8, 8, 8, 16, 0, 0);
        // The game ships silent (audio is deferred -- see PORTING_STATUS.md), and
        // asking for an audio device spews ALSA errors on headless machines.
        config.disableAudio(true);

        DesktopPlatformServices platform = new DesktopPlatformServices();
        //  Through the real seam, before create(), so the first layout already
        //  has them -- exactly as Android reports a cutout before the first frame.
        platform.setSafeAreaInsets(options.insets);

        CastleDefenseGame game = options.frameLimit > 0
                ? new FrameLimitedGame(options.frameLimit, options.screenshot,
                        options.mode, options.screen, options.uiDebug, platform)
                : new CastleDefenseGame(platform);
        if (options.frameLimit <= 0 && options.uiDebug) {
            System.out.println("[ui] --ui-debug needs --frames to take effect on "
                    + "a windowed run; toggle it in-game instead");
        }

        new Lwjgl3Application(game, config);
    }

    /** Renders a fixed number of frames, optionally saves a PNG, then exits. */
    private static final class FrameLimitedGame extends CastleDefenseGame {
        private final int limit;
        private final String screenshotPath;
        private final GameMode mode;
        private final String screen;
        private final boolean uiDebug;
        private boolean staged;

        FrameLimitedGame(int limit, String screenshotPath, GameMode mode,
                         String screen, boolean uiDebug,
                         DesktopPlatformServices platform) {
            super(platform);
            this.limit = limit;
            this.screenshotPath = screenshotPath;
            this.mode = mode;
            this.screen = screen;
            this.uiDebug = uiDebug;
        }

        /**
         * Starts a run as soon as the world exists, so a smoke run exercises the
         * game rather than an idle menu.
         *
         * <p>{@code --mode classic|endless} plus {@code --frames N} is the
         * headless-ish smoke the port is verified with: it plays for N frames on
         * a real backend and prints a line dense enough to reproduce the run.
         */
        @Override
        public void create() {
            super.create();
            //  With --screen the staging decides whether a run is needed at
            //  all: --screen menu must show the MENU, not a run that create()
            //  started behind its back.
            if (mode != null && screen == null) {
                long seed = startRun(mode);
                //  startRun opens the first armoury, which is where the menu
                //  button lands too.  A smoke run wants the fight, so it presses
                //  the same button a player would rather than skipping the state.
                if (getUi() != null) {
                    getUi().navigation().startPlaying();
                }
                System.out.println("[smoke] started " + mode.id() + " on seed " + seed);
            }
            if (uiDebug && getUiDebugOverlay() != null) {
                getUiDebugOverlay().setEnabled(true);
            }
        }

        @Override
        public void render() {
            //  Staged on the first frame rather than in create(), so the
            //  viewports already have their real size and the screen lays out
            //  for the device frame the screenshot is being taken at.
            if (!staged) {
                staged = true;
                stageScreen();
            }
            super.render();
            if (getRenderCount() < limit) {
                return;
            }
            if (mode != null && getRun() != null) {
                System.out.println("[smoke] " + getRun().describeRun());
            }
            if (screenshotPath != null) {
                saveScreenshot(screenshotPath);
            }
            // The smoke run's actual evidence: a headless test can prove the
            // accumulator arithmetic, but only a real backend proves that a real
            // frame delta drives it.  Steps should be close to frames at 60 Hz
            // and clearly fewer at 144 Hz.
            Gdx.app.log("CastleDefense", "frame limit " + limit + " reached, exiting"
                    + " (steps=" + getStepsRun()
                    + ", simTime=" + String.format("%.3f", getSimulation().timeSeconds()) + "s"
                    + ", clampedFrames=" + getSimulation().clampedFrames()
                    + ", droppedSteps=" + getSimulation().droppedStepEvents() + ")");
            Gdx.app.exit();
        }

        /**
         * Opens the requested screen through the real navigation graph.
         *
         * <p>Deliberately not {@code world.setState(...)}: a screenshot of a
         * state the game cannot actually route to would be a picture of
         * something no player can reach. Every call here is one a button makes.
         */
        private void stageScreen() {
            if (screen == null || getUi() == null || getRun() == null) {
                return;
            }
            com.mymmer.castledefense.ui.Navigation nav = getUi().navigation();
            switch (screen) {
                case "menu":
                    break;                          // where a fresh game starts
                case "settings":
                    nav.openSettings();
                    break;
                case "shop":
                    ensureRun();
                    break;                          // a new run opens in the armoury
                case "talents":
                    ensureRun();
                    nav.openTalents();
                    break;
                case "playing":
                    ensureRun();
                    nav.startPlaying();
                    warmUp(240);
                    break;
                case "bosses":
                    ensureRun();
                    nav.startPlaying();
                    warmUp(240);
                    getRun().summonBoss(
                            com.mymmer.castledefense.boss.BossType.TROLL_KING, 5);
                    getRun().summonBoss(
                            com.mymmer.castledefense.boss.BossType.DRAGON, 5);
                    break;
                case "paused":
                    ensureRun();
                    nav.startPlaying();
                    warmUp(240);
                    nav.pause();
                    break;
                case "gameover":
                    ensureRun();
                    nav.startPlaying();
                    warmUp(60);
                    getRun().castle().takeDamage(getRun().castle().maxHp() * 10f);
                    warmUp(2);
                    break;
                default:
                    System.out.println("[ui] unknown --screen " + screen);
                    return;
            }
            System.out.println("[ui] staged " + screen + " -> "
                    + getRun().world().state());
        }

        private void ensureRun() {
            if (getRun().world().state() == GameState.MENU) {
                startRun(mode != null ? mode : GameMode.ENDLESS);
            }
        }

        /** Runs the simulation forward so the screenshot has something in it. */
        private void warmUp(int steps) {
            for (int i = 0; i < steps; i++) {
                getRun().step();
            }
        }

        private void saveScreenshot(String path) {
            byte[] pixels = ScreenUtils.getFrameBufferPixels(
                    0, 0, Gdx.graphics.getBackBufferWidth(),
                    Gdx.graphics.getBackBufferHeight(), true);
            Pixmap pixmap = new Pixmap(Gdx.graphics.getBackBufferWidth(),
                    Gdx.graphics.getBackBufferHeight(), Pixmap.Format.RGBA8888);
            com.badlogic.gdx.utils.BufferUtils.copy(pixels, 0, pixmap.getPixels(), pixels.length);
            FileHandle file = Gdx.files.absolute(path);
            try {
                PixmapIO.writePNG(file, pixmap, -1, false);
                Gdx.app.log("CastleDefense", "screenshot written to " + file.path());
            } finally {
                pixmap.dispose();
            }
        }
    }

    private static final class Options {
        int width = (int) GameConfig.WORLD_WIDTH;
        int height = (int) GameConfig.WORLD_HEIGHT;
        int frameLimit = 0;
        String screenshot = null;
        boolean vsync = true;
        GameMode mode = null;
        String screen = null;
        boolean uiDebug = false;
        SafeAreaInsets insets = SafeAreaInsets.NONE;

        static Options parse(String[] args) {
            Options o = new Options();
            for (int i = 0; i < args.length; i++) {
                String a = args[i];
                if ("--size".equals(a) && i + 1 < args.length) {
                    String[] parts = args[++i].toLowerCase().split("x");
                    if (parts.length == 2) {
                        o.width = Integer.parseInt(parts[0].trim());
                        o.height = Integer.parseInt(parts[1].trim());
                    }
                } else if ("--frames".equals(a) && i + 1 < args.length) {
                    o.frameLimit = Integer.parseInt(args[++i].trim());
                } else if ("--screenshot".equals(a) && i + 1 < args.length) {
                    o.screenshot = args[++i];
                } else if ("--mode".equals(a) && i + 1 < args.length) {
                    o.mode = GameMode.byId(args[++i].trim().toLowerCase(), null);
                } else if ("--screen".equals(a) && i + 1 < args.length) {
                    o.screen = args[++i].trim().toLowerCase();
                } else if ("--ui-debug".equals(a)) {
                    o.uiDebug = true;
                } else if ("--insets".equals(a) && i + 1 < args.length) {
                    String[] edges = args[++i].split(",");
                    if (edges.length == 4) {
                        o.insets = new SafeAreaInsets(
                                Integer.parseInt(edges[0].trim()),
                                Integer.parseInt(edges[1].trim()),
                                Integer.parseInt(edges[2].trim()),
                                Integer.parseInt(edges[3].trim()));
                    }
                } else if ("--device".equals(a) && i + 1 < args.length) {
                    o.applyDevice(args[++i].trim().toLowerCase());
                } else if ("--no-vsync".equals(a)) {
                    o.vsync = false;
                }
            }
            return o;
        }

        /**
         * A named device frame: aspect ratio and cutouts together.
         *
         * <p>The windows are deliberately small — a 20:9 phone at its real pixel
         * count does not fit on most desks, and the layout is resolution
         * independent, so the aspect ratio is the part that matters. The insets
         * are sized for these windows, which keeps the safe rectangle roughly
         * the fraction of the screen it would be on the device.
         */
        void applyDevice(String name) {
            switch (name) {
                case "desktop":  set(1280, 720, 0, 0, 0, 0); break;
                case "phone169": set(960, 540, 0, 0, 0, 0); break;
                case "phone189": set(1080, 540, 0, 0, 28, 0); break;
                case "phone195": set(1170, 540, 0, 0, 34, 16); break;
                case "phone209": set(1200, 540, 0, 0, 40, 18); break;
                case "portrait": set(540, 1200, 0, 0, 40, 18); break;
                case "tablet":   set(1600, 1000, 0, 0, 0, 0); break;
                default:
                    System.out.println("[ui] unknown --device " + name
                            + "; keeping " + width + "x" + height);
            }
        }

        private void set(int w, int h, int l, int r, int t, int b) {
            width = w;
            height = h;
            insets = new SafeAreaInsets(l, r, t, b);
        }
    }
}
