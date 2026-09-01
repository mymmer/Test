package com.mymmer.castledefense.util;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.headless.HeadlessApplication;
import com.badlogic.gdx.backends.headless.HeadlessApplicationConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The Java equivalent of the Python game_errors.log, with its promises intact. */
class CrashLoggerTest {

    private static final String PATH = "castle-defense-test/logs/errors.log";
    private static HeadlessApplication app;

    @BeforeAll
    static void boot() {
        HeadlessApplicationConfiguration cfg = new HeadlessApplicationConfiguration();
        cfg.updatesPerSecond = -1;
        app = new HeadlessApplication(new ApplicationAdapter() {
        }, cfg);
    }

    @AfterAll
    static void shutdown() {
        app.exit();
    }

    @AfterEach
    void cleanUp() {
        Gdx.files.local(PATH).delete();
        Gdx.files.local(PATH + ".1").delete();
    }

    @Test
    @DisplayName("a crash writes the whole stack trace, not just the message")
    void writesFullTrace() {
        CrashLogger logger = new CrashLogger(PATH);
        try {
            throw new IllegalStateException("selftest boom");
        } catch (IllegalStateException e) {
            logger.logCrash(e, "unit test");
        }
        String written = Gdx.files.local(PATH).readString("UTF-8");
        assertTrue(written.contains("selftest boom"), written);
        assertTrue(written.contains("IllegalStateException"), written);
        assertTrue(written.contains("CrashLoggerTest"),
                "the trace must name the frames, not only the exception");
        assertTrue(written.contains("unhandled exception in unit test"), written);
    }

    @Test
    @DisplayName("the state snapshot is written beside the trace")
    void writesStateSnapshot() {
        CrashLogger logger = new CrashLogger(PATH);
        logger.setContextProvider(new CrashLogger.ContextProvider() {
            @Override
            public String describe() {
                return "mode=endless wave=22 difficulty=hard enemies=26";
            }
        });
        logger.logCrash(new RuntimeException("late-game"), "main loop");
        String written = Gdx.files.local(PATH).readString("UTF-8");
        assertTrue(written.contains("state at crash: mode=endless wave=22"), written);
    }

    @Test
    @DisplayName("a context provider that itself fails does not break the log")
    void contextProviderMayFail() {
        CrashLogger logger = new CrashLogger(PATH);
        logger.setContextProvider(new CrashLogger.ContextProvider() {
            @Override
            public String describe() {
                throw new IllegalStateException("the world is already gone");
            }
        });
        assertDoesNotThrow(() -> logger.logCrash(new RuntimeException("boom"), "main loop"));
        String written = Gdx.files.local(PATH).readString("UTF-8");
        assertTrue(written.contains("state unavailable"), written);
        assertTrue(written.contains("boom"), "the real crash must still be recorded");
    }

    @Test
    @DisplayName("the log rotates instead of growing without limit")
    void rotates() {
        CrashLogger logger = new CrashLogger(PATH);
        StringBuilder filler = new StringBuilder();
        for (int i = 0; i < 1024; i++) {
            filler.append("x");
        }
        for (int i = 0; i < 600; i++) {          // ~600 KB, past the 512 KB cap
            logger.logInfo(filler.toString());
        }
        assertTrue(Gdx.files.local(PATH + ".1").exists(), "a backup must be kept");
        assertTrue(Gdx.files.local(PATH).length() < 512L * 1024L,
                "the live log must have been rotated");
    }

    @Test
    @DisplayName("a null throwable is survivable")
    void nullThrowable() {
        CrashLogger logger = new CrashLogger(PATH);
        assertDoesNotThrow(() -> logger.logCrash(null, "nowhere"));
    }
}
