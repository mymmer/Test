package com.mymmer.castledefense.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.headless.HeadlessApplication;
import com.badlogic.gdx.backends.headless.HeadlessApplicationConfiguration;
import com.badlogic.gdx.ApplicationAdapter;
import com.mymmer.castledefense.data.AssetJsonSource;
import com.mymmer.castledefense.data.DataException;
import com.mymmer.castledefense.testsupport.InMemoryJsonSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The shipped difficulty data must match the Python table exactly — this is the
 * first real parity assertion in the port.
 */
class DifficultyTableTest {

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
    @DisplayName("the shipped file carries the Python difficulty values verbatim")
    void shippedDataMatchesPython() {
        // assets/ is the working dir for tests via Gdx.files.internal
        DifficultyTable table = DifficultyTable.load(new AssetJsonSource());
        assertEquals(3, table.size());
        assertEquals("normal", table.defaultDifficulty().id());

        DifficultyConfig easy = table.get("easy");
        assertEquals(0.80f, easy.scale(), 1e-6f);
        assertEquals(1.15f, easy.gold(), 1e-6f);
        assertEquals(0.00f, easy.headstart(), 1e-6f);
        assertEquals(0.90f, easy.speed(), 1e-6f);
        assertEquals(0.80f, easy.hpCurve(), 1e-6f);
        assertEquals(1.00f, easy.bossFire(), 1e-6f);
        assertFalse(easy.eliteHorn());
        assertEquals(0.00f, easy.grabCd(), 1e-6f);

        DifficultyConfig normal = table.get("normal");
        assertEquals(1.00f, normal.scale(), 1e-6f);
        assertEquals(1.00f, normal.gold(), 1e-6f);
        assertEquals(1.00f, normal.speed(), 1e-6f);
        assertEquals(0.25f, normal.grabCd(), 1e-6f);
        assertEquals(GameConfig.GRAB_COOLDOWN, normal.grabCd(), 1e-6f);

        DifficultyConfig hard = table.get("hard");
        assertEquals(1.30f, hard.scale(), 1e-6f);
        assertEquals(0.25f, hard.headstart(), 1e-6f);
        assertEquals(1.40f, hard.speed(), 1e-6f, "Hard is +40% flat move speed");
        assertEquals(1.60f, hard.hpCurve(), 1e-6f, "Hard steepens the curve by 60%");
        assertEquals(0.50f, hard.bossFire(), 1e-6f, "Hard bosses fire twice as fast");
        assertTrue(hard.eliteHorn(), "Hard's horn calls in elites");
        assertEquals(0.50f, hard.grabCd(), 1e-6f);
    }

    @Test
    @DisplayName("difficulty ordering matches the menu: easy, normal, hard")
    void menuOrder() {
        DifficultyTable table = DifficultyTable.load(new AssetJsonSource());
        assertEquals("easy", table.all().get(0).id());
        assertEquals("normal", table.all().get(1).id());
        assertEquals("hard", table.all().get(2).id());
    }

    @Test
    @DisplayName("an unknown id falls back to the default rather than failing")
    void unknownIdFallsBack() {
        DifficultyTable table = DifficultyTable.load(new AssetJsonSource());
        assertSame(table.defaultDifficulty(), table.get("nightmare"));
        assertSame(table.defaultDifficulty(), table.get(null));
        assertFalse(table.contains("nightmare"));
    }

    @Test
    @DisplayName("malformed content fails at load with a message naming the field")
    void malformedContentIsLoud() {
        InMemoryJsonSource src = new InMemoryJsonSource();

        src.put(DifficultyTable.PATH, "{ \"defaultDifficulty\": \"normal\" }");
        DataException e1 = assertThrows(DataException.class,
                () -> DifficultyTable.load(src));
        assertTrue(e1.getMessage().contains("difficulties"), e1.getMessage());

        src.put(DifficultyTable.PATH, "{ \"defaultDifficulty\": \"normal\","
                + " \"difficulties\": [ { \"id\": \"normal\", \"label\": \"N\","
                + " \"scale\": 1, \"gold\": 1, \"headstart\": 0, \"speed\": 1,"
                + " \"hpCurve\": 1, \"bossFire\": 1, \"eliteHorn\": false } ] }");
        DataException e2 = assertThrows(DataException.class,
                () -> DifficultyTable.load(src));
        assertTrue(e2.getMessage().contains("grabCd"), e2.getMessage());

        src.put(DifficultyTable.PATH, "{ \"defaultDifficulty\": \"missing\","
                + " \"difficulties\": [ { \"id\": \"normal\", \"label\": \"N\","
                + " \"scale\": 1, \"gold\": 1, \"headstart\": 0, \"speed\": 1,"
                + " \"hpCurve\": 1, \"bossFire\": 1, \"eliteHorn\": false,"
                + " \"grabCd\": 0.25 } ] }");
        DataException e3 = assertThrows(DataException.class,
                () -> DifficultyTable.load(src));
        assertTrue(e3.getMessage().contains("defaultDifficulty"), e3.getMessage());

        src.put(DifficultyTable.PATH, "{ not json at all ");
        assertThrows(DataException.class, () -> DifficultyTable.load(src));
    }

    @Test
    @DisplayName("a zero or negative multiplier is rejected, not silently used")
    void rejectsImpossibleMultipliers() {
        InMemoryJsonSource src = new InMemoryJsonSource().put(DifficultyTable.PATH,
                "{ \"defaultDifficulty\": \"normal\","
                + " \"difficulties\": [ { \"id\": \"normal\", \"label\": \"N\","
                + " \"scale\": 0, \"gold\": 1, \"headstart\": 0, \"speed\": 1,"
                + " \"hpCurve\": 1, \"bossFire\": 1, \"eliteHorn\": false,"
                + " \"grabCd\": 0.25 } ] }");
        DataException e = assertThrows(DataException.class, () -> DifficultyTable.load(src));
        assertTrue(e.getMessage().contains("scale"), e.getMessage());
    }
}
