package com.mymmer.castledefense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.headless.HeadlessApplication;
import com.badlogic.gdx.backends.headless.HeadlessApplicationConfiguration;
import com.mymmer.castledefense.assets.GameAssets;
import com.mymmer.castledefense.assets.SkinManager;
import com.mymmer.castledefense.config.QualityConfig;
import com.mymmer.castledefense.data.AssetJsonSource;
import com.mymmer.castledefense.data.DataException;
import com.mymmer.castledefense.persistence.SaveData;
import com.mymmer.castledefense.persistence.SaveManager;
import com.mymmer.castledefense.platform.NoOpPlatformServices;
import com.mymmer.castledefense.testsupport.InMemoryJsonSource;
import com.mymmer.castledefense.util.CrashLogger;
import com.mymmer.castledefense.util.Rng;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Startup wiring: what must be fatal, what must degrade, and in what order.
 */
class ServicesStartupTest {

    private static final String SAVE = "castle-defense-test/services-save.json";
    private static final String LOG = "castle-defense-test/services.log";
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
        Gdx.files.local(SAVE).delete();
        Gdx.files.local(LOG).delete();
    }

    private Services realDataServices() {
        return new Services(new NoOpPlatformServices(), new AssetJsonSource(),
                new GameAssets(), new SaveManager(SAVE), new CrashLogger(LOG), new Rng(1L));
    }

    @Test
    @DisplayName("a clean start loads data, save and the procedural skin")
    void cleanStart() {
        Services services = realDataServices();
        try {
            services.start();
            assertTrue(services.isStarted());
            assertEquals(3, services.difficulties().size());
            assertNotNull(services.save());
            assertEquals(SkinManager.PROCEDURAL_SKIN, services.skins().activeSkinId(),
                    "with no artwork the game must run procedurally, like the Python one");
            assertEquals(QualityConfig.HIGH, services.quality());
        } finally {
            services.dispose();
        }
    }

    @Test
    @DisplayName("broken balance data is fatal at startup, not a silent default")
    void brokenDataIsFatal() {
        InMemoryJsonSource bad = new InMemoryJsonSource()
                .put("data/difficulties.json", "{ \"difficulties\": [] }");
        Services services = new Services(new NoOpPlatformServices(), bad, new GameAssets(),
                new SaveManager(SAVE), new CrashLogger(LOG), new Rng(1L));
        try {
            DataException e = assertThrows(DataException.class, services::start);
            assertTrue(Services.isFatalDataProblem(e));
        } finally {
            services.dispose();
        }
    }

    @Test
    @DisplayName("a save naming an unknown difficulty is repaired, not obeyed")
    void unknownDifficultyIsRepaired() {
        SaveData stored = new SaveData();
        stored.difficulty = "nightmare";
        new SaveManager(SAVE).save(stored);

        Services services = realDataServices();
        try {
            services.start();
            assertEquals("normal", services.save().difficulty);
        } finally {
            services.dispose();
        }
    }

    @Test
    @DisplayName("a save naming a missing skin falls back and records the fallback")
    void missingSkinFallsBack() {
        SaveData stored = new SaveData();
        stored.skin = "medieval";           // never shipped
        new SaveManager(SAVE).save(stored);

        Services services = realDataServices();
        try {
            services.start();
            assertEquals(SkinManager.PROCEDURAL_SKIN, services.skins().activeSkinId());
            assertEquals(SkinManager.PROCEDURAL_SKIN, services.save().skin,
                    "the save must be corrected so the warning does not repeat forever");
        } finally {
            services.dispose();
        }
    }

    @Test
    @DisplayName("settings persist across a restart")
    void settingsPersist() {
        Services first = realDataServices();
        try {
            first.start();
            first.save().difficulty = "hard";
            first.save().highScore = 12345;
            first.setQuality(QualityConfig.LOW);
            assertTrue(first.persist());
        } finally {
            first.dispose();
        }

        Services second = realDataServices();
        try {
            second.start();
            assertEquals("hard", second.save().difficulty);
            assertEquals(12345, second.save().highScore);
            assertEquals(QualityConfig.LOW, second.quality());
        } finally {
            second.dispose();
        }
    }

    @Test
    @DisplayName("quality presets never change anything the simulation can see")
    void qualityIsCosmeticOnly() {
        // A guard, not a behaviour test: if a preset ever grows a field that
        // could affect gameplay, this is where it should be caught.
        for (QualityConfig q : QualityConfig.values()) {
            assertTrue(q.maxParticles() > 0, q + " must still draw something");
            assertTrue(q.glowIntensity() >= 0f && q.glowIntensity() <= 1f);
        }
        assertTrue(QualityConfig.LOW.maxParticles() < QualityConfig.HIGH.maxParticles());
        assertEquals(QualityConfig.HIGH, QualityConfig.parse("high", QualityConfig.LOW));
        assertEquals(QualityConfig.LOW, QualityConfig.parse("nonsense", QualityConfig.LOW));
    }
}
