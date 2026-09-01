package com.mymmer.castledefense.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.backends.headless.HeadlessApplication;
import com.badlogic.gdx.backends.headless.HeadlessApplicationConfiguration;
import java.util.Locale;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StringsTest {

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

    @Test
    @DisplayName("the English bundle loads and serves keys")
    void servesKeys() {
        Strings.load(Locale.ENGLISH);
        assertTrue(Strings.isLoaded());
        assertEquals("Castle Defense", Strings.get("app.title"));
        assertEquals("HARD", Strings.get("difficulty.hard"));
    }

    @Test
    @DisplayName("arguments are substituted")
    void formats() {
        Strings.load(Locale.ENGLISH);
        String s = Strings.format("skin.missingArtwork", "troll_king");
        assertTrue(s.contains("troll_king"), s);
    }

    @Test
    @DisplayName("a missing key shows as !key! instead of throwing")
    void missingKey() {
        Strings.load(Locale.ENGLISH);
        assertEquals("!menu.doesNotExist!", Strings.get("menu.doesNotExist"));
    }

    @Test
    @DisplayName("an unknown locale falls back to the shipped language")
    void unknownLocaleFallsBack() {
        Strings.load(new Locale("xx", "YY"));
        assertEquals("Castle Defense", Strings.get("app.title"),
                "a locale with no bundle must fall back, not blank the UI");
    }

    @Test
    @DisplayName("with no bundle at all the UI shows keys rather than crashing")
    void noBundle() {
        Strings.unload();
        assertEquals("!app.title!", Strings.get("app.title"));
        assertEquals("!x!", Strings.format("x", 1));
        Strings.load(Locale.ENGLISH);
    }
}
