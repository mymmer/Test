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

/**
 * Desktop entry point — the development loop.
 *
 * <p>Arguments (all optional):
 * <pre>
 *   --size WxH        window size, default 1280x720
 *   --frames N        quit after N rendered frames (smoke testing / CI)
 *   --screenshot FILE write a PNG of the last frame before quitting
 *   --no-vsync        uncap the frame rate
 * </pre>
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

        CastleDefenseGame game = options.frameLimit > 0
                ? new FrameLimitedGame(options.frameLimit, options.screenshot)
                : new CastleDefenseGame();

        new Lwjgl3Application(game, config);
    }

    /** Renders a fixed number of frames, optionally saves a PNG, then exits. */
    private static final class FrameLimitedGame extends CastleDefenseGame {
        private final int limit;
        private final String screenshotPath;

        FrameLimitedGame(int limit, String screenshotPath) {
            this.limit = limit;
            this.screenshotPath = screenshotPath;
        }

        @Override
        public void render() {
            super.render();
            if (getRenderCount() < limit) {
                return;
            }
            if (screenshotPath != null) {
                saveScreenshot(screenshotPath);
            }
            Gdx.app.log("CastleDefense", "frame limit " + limit + " reached, exiting");
            Gdx.app.exit();
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
                } else if ("--no-vsync".equals(a)) {
                    o.vsync = false;
                }
            }
            return o;
        }
    }
}
