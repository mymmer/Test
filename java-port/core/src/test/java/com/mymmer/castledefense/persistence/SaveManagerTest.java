package com.mymmer.castledefense.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.headless.HeadlessApplication;
import com.badlogic.gdx.backends.headless.HeadlessApplicationConfiguration;
import com.badlogic.gdx.utils.JsonValue;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Persistence must round-trip, must survive anything, and must never stop the
 * game starting — the same promise the Python {@code Settings} class makes.
 */
class SaveManagerTest {

    private static final String PATH = "castle-defense-test/save.json";
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
        new SaveManager(PATH).delete();
    }

    @Test
    @DisplayName("a fresh install loads defaults instead of failing")
    void freshInstall() {
        SaveManager saves = new SaveManager(PATH);
        SaveData data = saves.load();
        assertNotNull(data);
        assertEquals(SaveData.CURRENT_VERSION, data.saveVersion);
        assertEquals("normal", data.difficulty);
        assertEquals(0, data.highScore);
        assertTrue(saves.lastLoadNote().contains("no save file"));
    }

    @Test
    @DisplayName("settings and the shared high score round-trip")
    void roundTrip() {
        SaveManager saves = new SaveManager(PATH);
        SaveData data = new SaveData();
        data.muted = true;
        data.difficulty = "hard";
        data.quality = "LOW";
        data.haptics = false;
        data.skin = "medieval";
        data.highScore = 918233;
        assertTrue(saves.save(data));

        SaveData read = new SaveManager(PATH).load();
        assertTrue(read.muted);
        assertEquals("hard", read.difficulty);
        assertEquals("LOW", read.quality);
        assertFalse(read.haptics);
        assertEquals("medieval", read.skin);
        assertEquals(918233, read.highScore);
    }

    @Test
    @DisplayName("save v1 keeps ONE shared high score, as the Python game does")
    void oneSharedHighScore() {
        SaveManager saves = new SaveManager(PATH);
        SaveData data = new SaveData();
        data.highScore = 4321;
        saves.save(data);

        String raw = Gdx.files.local(PATH).readString("UTF-8");
        assertTrue(raw.contains("\"highScore\""), raw);
        assertFalse(raw.contains("highScores"),
                "per-mode scores would be a gameplay change; v1 must not have them");
        assertTrue(raw.contains("\"saveVersion\": 1"), raw);
    }

    @Test
    @DisplayName("corrupt JSON falls back to defaults rather than crashing")
    void corruptFile() {
        Gdx.files.local(PATH).writeString("{ this is not json", false, "UTF-8");
        SaveManager saves = new SaveManager(PATH);
        SaveData data = saves.load();
        assertEquals("normal", data.difficulty);
        assertTrue(saves.lastLoadNote().contains("not readable JSON"));
    }

    @Test
    @DisplayName("a partially written file keeps whatever fields survived")
    void partialFile() {
        Gdx.files.local(PATH).writeString(
                "{ \"saveVersion\": 1, \"settings\": { \"difficulty\": \"hard\" } }",
                false, "UTF-8");
        SaveData data = new SaveManager(PATH).load();
        assertEquals("hard", data.difficulty, "the field that was there is kept");
        assertEquals("HIGH", data.quality, "the missing one falls back");
        assertEquals(0, data.highScore);
    }

    @Test
    @DisplayName("a save from a newer build is left alone, not mangled")
    void futureSave() {
        Gdx.files.local(PATH).writeString(
                "{ \"saveVersion\": 99, \"highScore\": 5 }", false, "UTF-8");
        SaveManager saves = new SaveManager(PATH);
        SaveData data = saves.load();
        assertEquals(0, data.highScore, "a future format must not be guessed at");
        assertTrue(saves.lastLoadNote().contains("newer than this build"));
        assertTrue(Gdx.files.local(PATH).readString().contains("99"),
                "and the file must be left where it is");
    }

    @Test
    @DisplayName("an old save is walked up the migration chain")
    void migrationChain() {
        // a hypothetical v0 that stored the score under a different name
        Gdx.files.local(PATH).writeString(
                "{ \"saveVersion\": 0, \"best\": 777,"
                + " \"settings\": { \"difficulty\": \"hard\" } }", false, "UTF-8");

        SaveManager saves = new SaveManager(PATH).register(new SaveMigration() {
            @Override
            public int fromVersion() {
                return 0;
            }

            @Override
            public int toVersion() {
                return 1;
            }

            @Override
            public JsonValue migrate(JsonValue root) {
                JsonValue best = root.get("best");
                if (best != null) {
                    JsonValue renamed = new JsonValue(best.asInt());
                    renamed.name = "highScore";
                    root.addChild(renamed);
                }
                return root;
            }
        });

        SaveData data = saves.load();
        assertEquals(777, data.highScore, "the migration must carry the value across");
        assertEquals("hard", data.difficulty);
        assertTrue(saves.lastLoadNote().contains("migrated"));
    }

    @Test
    @DisplayName("an old save with no migration path falls back to defaults")
    void missingMigration() {
        Gdx.files.local(PATH).writeString("{ \"saveVersion\": 0 }", false, "UTF-8");
        SaveManager saves = new SaveManager(PATH);
        SaveData data = saves.load();
        assertEquals(0, data.highScore);
        assertTrue(saves.lastLoadNote().contains("no migration path"));
    }
}
