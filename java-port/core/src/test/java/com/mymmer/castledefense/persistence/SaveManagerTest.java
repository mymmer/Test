package com.mymmer.castledefense.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.headless.HeadlessApplication;
import com.badlogic.gdx.backends.headless.HeadlessApplicationConfiguration;
import com.badlogic.gdx.files.FileHandle;
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

    //  ------------------------------------------------------------------
    //  Interruption safety.
    //
    //  Saving happens at pause(), which on Android is the last moment before the
    //  OS may kill the process.  A write that truncates the live file first
    //  loses the player's settings and high score if it is interrupted there.
    //  These tests pin the invariant: after ANY failed or interrupted save, one
    //  of the two files on disk is still a complete, loadable save.
    //  ------------------------------------------------------------------

    @Test
    @DisplayName("a normal save round-trips and leaves no staging file behind")
    void normalSaveCleansUp() {
        SaveManager saves = new SaveManager(PATH);
        SaveData data = new SaveData();
        data.difficulty = "hard";
        data.highScore = 4242;
        assertTrue(saves.save(data));

        assertTrue(Gdx.files.local(PATH).exists(), "the live save exists");
        assertFalse(Gdx.files.local(saves.stagingPath()).exists(),
                "the staging file is consumed by the replace, not left lying around");

        SaveData back = new SaveManager(PATH).load();
        assertEquals("hard", back.difficulty);
        assertEquals(4242, back.highScore);
    }

    @Test
    @DisplayName("a failed staging write leaves the previous save intact")
    void failedStagingWriteKeepsPreviousSave() {
        SaveManager saves = new SaveManager(PATH);
        SaveData good = new SaveData();
        good.difficulty = "hard";
        good.highScore = 900;
        assertTrue(saves.save(good));

        //  Make the staging path unwritable by putting a DIRECTORY there: any
        //  attempt to write a file over it fails, which is the closest a test
        //  can get to a full disk without one.
        FileHandle blocker = Gdx.files.local(saves.stagingPath());
        blocker.delete();
        blocker.mkdirs();
        try {
            SaveData replacement = new SaveData();
            replacement.difficulty = "easy";
            replacement.highScore = 1;
            assertFalse(saves.save(replacement), "the save must report failure");

            SaveData back = new SaveManager(PATH).load();
            assertEquals("hard", back.difficulty, "the good save survived");
            assertEquals(900, back.highScore);
        } finally {
            blocker.deleteDirectory();
        }
    }

    @Test
    @DisplayName("malformed staged data never replaces a valid save")
    void malformedStagedDataNeverReplaces() {
        SaveManager saves = new SaveManager(PATH);
        SaveData good = new SaveData();
        good.difficulty = "hard";
        good.highScore = 777;
        assertTrue(saves.save(good));
        String liveBefore = Gdx.files.local(PATH).readString("UTF-8");

        //  Simulate the process dying part-way through writing the staging file.
        //  load() must not prefer it: the live save is fine, and a half-written
        //  replacement is not a save.
        Gdx.files.local(saves.stagingPath())
                .writeString("{\n  \"saveVersion\": 1,\n  \"sett", false, "UTF-8");

        SaveData back = new SaveManager(PATH).load();
        assertEquals("hard", back.difficulty, "the valid live save is still used");
        assertEquals(777, back.highScore);
        assertEquals(liveBefore, Gdx.files.local(PATH).readString("UTF-8"),
                "and it was not rewritten from the garbage");
    }

    @Test
    @DisplayName("a save interrupted between delete and rename is recovered")
    void recoversFromAnInterruptedReplace() {
        SaveManager saves = new SaveManager(PATH);

        //  The one window where the live file can be missing: staged file
        //  written and validated, live file already gone, process killed before
        //  the rename landed.  Reproduced exactly.
        Gdx.files.local(saves.stagingPath()).writeString(
                "{\n  \"saveVersion\": 1,\n  \"settings\": {\n"
                        + "    \"muted\": true,\n    \"difficulty\": \"hard\",\n"
                        + "    \"quality\": \"LOW\",\n    \"haptics\": false,\n"
                        + "    \"skin\": \"procedural\"\n  },\n"
                        + "  \"highScore\": 31337\n}\n", false, "UTF-8");
        assertFalse(Gdx.files.local(PATH).exists(), "the live save is gone");

        SaveManager reader = new SaveManager(PATH);
        SaveData back = reader.load();
        assertEquals("hard", back.difficulty, "the staged save was recovered");
        assertEquals(31337, back.highScore);
        assertTrue(back.muted);
        assertTrue(reader.lastLoadNote().contains("recovered"),
                "and the recovery is reported, not silent: " + reader.lastLoadNote());

        // it was promoted, so the next load is an ordinary one
        SaveManager again = new SaveManager(PATH);
        assertEquals(31337, again.load().highScore);
        assertEquals("", again.lastLoadNote(), "no note the second time");
        assertFalse(Gdx.files.local(again.stagingPath()).exists());
    }

    @Test
    @DisplayName("a corrupt live save falls back to a valid staged one")
    void corruptLiveSaveFallsBackToStaged() {
        SaveManager saves = new SaveManager(PATH);
        Gdx.files.local(PATH).writeString("}{ not json at all", false, "UTF-8");
        Gdx.files.local(saves.stagingPath()).writeString(
                "{\n  \"saveVersion\": 1,\n  \"settings\": {\n"
                        + "    \"difficulty\": \"easy\"\n  },\n"
                        + "  \"highScore\": 55\n}\n", false, "UTF-8");

        SaveManager reader = new SaveManager(PATH);
        SaveData back = reader.load();
        assertEquals("easy", back.difficulty);
        assertEquals(55, back.highScore, "the shared high score came back");
        assertTrue(reader.lastLoadNote().contains("recovered"), reader.lastLoadNote());
    }

    @Test
    @DisplayName("a stale staging file is discarded once a good save is loaded")
    void staleStagingFileIsDiscarded() {
        SaveManager saves = new SaveManager(PATH);
        SaveData good = new SaveData();
        good.highScore = 10;
        assertTrue(saves.save(good));

        // an older, complete-looking staged save that the replace already used
        Gdx.files.local(saves.stagingPath()).writeString(
                "{\n  \"saveVersion\": 1,\n  \"settings\": {},\n"
                        + "  \"highScore\": 99999\n}\n", false, "UTF-8");

        SaveManager reader = new SaveManager(PATH);
        assertEquals(10, reader.load().highScore, "the live save wins while it is valid");
        assertFalse(Gdx.files.local(reader.stagingPath()).exists(),
                "and the stale staging file is cleared so it can never be promoted");
    }

    @Test
    @DisplayName("delete() removes the staging file too")
    void deleteRemovesStagingFile() {
        SaveManager saves = new SaveManager(PATH);
        SaveData data = new SaveData();
        data.highScore = 5;
        assertTrue(saves.save(data));
        Gdx.files.local(saves.stagingPath()).writeString(
                "{\"saveVersion\":1,\"settings\":{},\"highScore\":123}", false, "UTF-8");

        saves.delete();
        assertFalse(Gdx.files.local(PATH).exists());
        assertFalse(Gdx.files.local(saves.stagingPath()).exists(),
                "otherwise a reset would 'recover' the save it just deleted");
        assertEquals(0, new SaveManager(PATH).load().highScore);
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
