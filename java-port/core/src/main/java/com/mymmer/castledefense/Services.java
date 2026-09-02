package com.mymmer.castledefense;

import com.badlogic.gdx.utils.Disposable;
import com.mymmer.castledefense.assets.GameAssets;
import com.mymmer.castledefense.assets.SkinManager;
import com.mymmer.castledefense.config.DifficultyTable;
import com.mymmer.castledefense.config.QualityConfig;
import com.mymmer.castledefense.data.AssetJsonSource;
import com.mymmer.castledefense.data.DataException;
import com.mymmer.castledefense.data.JsonSource;
import com.mymmer.castledefense.persistence.SaveData;
import com.mymmer.castledefense.persistence.SaveManager;
import com.mymmer.castledefense.platform.NoOpPlatformServices;
import com.mymmer.castledefense.platform.PlatformServices;
import com.mymmer.castledefense.text.Strings;
import com.mymmer.castledefense.util.CrashLogger;
import com.mymmer.castledefense.util.Rng;

/**
 * The long-lived infrastructure a run is built on: assets, skins, data,
 * persistence, platform, logging, randomness.
 *
 * <p>Constructed once at startup and owned by {@link CastleDefenseGame}. It is
 * plumbing, not gameplay — nothing here knows what an enemy is. Later phases
 * build the world <em>on top of</em> this rather than inside it, which is what
 * keeps a run restartable without reloading a single texture.
 *
 * <p>Startup order matters and is fixed here: crash logging first (so a failure
 * in anything below is recorded), then data, then persistence, then the skin the
 * save asks for.
 */
public final class Services implements Disposable {

    private final CrashLogger crashLogger;
    private final PlatformServices platform;
    private final JsonSource json;
    private final GameAssets assets;
    private final SkinManager skins;
    private final SaveManager saves;
    private final Rng rng;

    private DifficultyTable difficulties;
    private SaveData save;
    private QualityConfig quality = QualityConfig.HIGH;

    public Services(PlatformServices platform) {
        this(platform, new AssetJsonSource(), new GameAssets(), new SaveManager(),
                new CrashLogger(), new Rng());
    }

    /** Full injection, for tests. */
    public Services(PlatformServices platform, JsonSource json, GameAssets assets,
                    SaveManager saves, CrashLogger crashLogger, Rng rng) {
        this.platform = platform != null ? platform : new NoOpPlatformServices();
        this.json = json;
        this.assets = assets;
        this.saves = saves;
        this.crashLogger = crashLogger;
        this.rng = rng;
        this.skins = new SkinManager(json, assets);
    }

    /**
     * Brings everything up.
     *
     * <p>Data problems are fatal here on purpose: a malformed
     * {@code difficulties.json} means the game cannot be balanced correctly, and
     * failing at startup with a clear message beats discovering it at wave 12.
     * Everything else — a missing save, an unusable skin, no writable storage —
     * degrades to a working default.
     */
    public void start() {
        crashLogger.installGlobalHandler();
        crashLogger.logInfo("starting on " + platform.deviceDescription());
        // Logged once, loudly, because a pinned seed changes what every later
        // line in this log means -- a "random" run in the report would not be.
        long pinned = Rng.debugSeedOr(Long.MIN_VALUE);
        if (pinned != Long.MIN_VALUE) {
            crashLogger.logWarning("castledefense.seed=" + pinned
                    + " -- every run this session is pinned to that seed");
        }

        Strings.load();

        difficulties = DifficultyTable.load(json);   // fatal if broken

        save = saves.load();
        if (!saves.lastLoadNote().isEmpty()) {
            crashLogger.logInfo("save: " + saves.lastLoadNote());
        }
        if (!difficulties.contains(save.difficulty)) {
            crashLogger.logWarning("save names unknown difficulty '" + save.difficulty
                    + "'; using " + difficulties.defaultDifficulty().id());
            save.difficulty = difficulties.defaultDifficulty().id();
        }
        quality = QualityConfig.parse(save.quality, QualityConfig.HIGH);
        platform.setHapticsEnabled(save.haptics);

        if (!skins.load(save.skin)) {
            crashLogger.logWarning("skin '" + save.skin + "' is unavailable; using '"
                    + skins.activeSkinId() + "'");
            save.skin = skins.activeSkinId();
        }
    }

    /** Persists the current settings. Failure is logged, never thrown. */
    public boolean persist() {
        if (save == null) {
            return false;
        }
        save.quality = quality.name();
        save.skin = skins.activeSkinId();
        return saves.save(save);
    }

    public CrashLogger crashLogger() {
        return crashLogger;
    }

    public PlatformServices platform() {
        return platform;
    }

    public GameAssets assets() {
        return assets;
    }

    public SkinManager skins() {
        return skins;
    }

    public SaveManager saves() {
        return saves;
    }

    public Rng rng() {
        return rng;
    }

    /** Null until {@link #start()} has run. */
    public DifficultyTable difficulties() {
        return difficulties;
    }

    /** Null until {@link #start()} has run. */
    public SaveData save() {
        return save;
    }

    public QualityConfig quality() {
        return quality;
    }

    public void setQuality(QualityConfig q) {
        if (q != null) {
            quality = q;
        }
    }

    /** True when data loading has completed successfully. */
    public boolean isStarted() {
        return difficulties != null && save != null;
    }

    /** Rethrown by {@link #start()} for callers that want to report it nicely. */
    public static boolean isFatalDataProblem(Throwable t) {
        return t instanceof DataException;
    }

    @Override
    public void dispose() {
        assets.dispose();
    }
}
