# Subsystem contract — Persistence and platform

## Purpose

Keep the player's settings and progress across launches, survive a schema
change, and report a crash with enough context to act on — without any of it
knowing which platform it is running on.

## Owns

* The save file: read, write, delete, and the version-migration chain
  (`SaveManager`, `SaveData`, `SaveMigration`).
* Crash and warning logging to `game_errors.log`, including the global
  uncaught-exception handler (`CrashLogger`).
* The platform capability boundary (`PlatformServices`, `HapticEvent`,
  `SafeAreaInsets`, `NoOpPlatformServices`).
* Data-file reading (`JsonSource`, `AssetJsonSource`, `Json5`, `DataException`).
* Startup ordering and single ownership of all of the above (`Services`).

## Does not own

* **Gameplay state.** A save holds settings and the high score, not a resumable
  mid-run world. There is no mid-run save in the Python game and the port does
  not invent one.
* **Where the file physically lives.** `Gdx.files.local` decides; the path
  (`castle-defense/save.json`) is the only thing stated here.
* **Balance data.** Difficulty presets and tuning are read-only content loaded
  through `JsonSource`, not saved.
* **Any Android type.** `PlatformServices` is an interface; the launcher
  implements it.

## Dependencies

libGDX `Gdx.files`, `Json`/`JsonValue`. Nothing else — no simulation, no input,
no assets.

## Public API

```java
// Services — creates and owns everything below.  One instance per application.
void start();                  // crash handler, strings, difficulties, save, skins — in that order
boolean persist();             // write settings; called on pause and dispose
CrashLogger crashLogger();  PlatformServices platform();
GameAssets assets();  SkinManager skins();  SaveManager saves();  Rng rng();
DifficultyTable difficulties();  SaveData save();
QualityConfig quality();  void setQuality(QualityConfig q);
boolean isStarted();  static boolean isFatalDataProblem(Throwable t);
void dispose();

// SaveManager
static final String DEFAULT_PATH = "castle-defense/save.json";
SaveManager register(SaveMigration m);   // chain, applied in version order
SaveData load();                         // never throws; falls back to defaults
boolean  save(SaveData d);
void     delete();                       // removes the live and staged files
String   path();  String stagingPath();
String   lastLoadNote();                 // why a load fell back or recovered

// SaveData  (version 1)
static final int CURRENT_VERSION = 1;
int saveVersion; boolean muted; String difficulty; String quality;
boolean haptics; String skin; int highScore;
SaveData copy();

// SaveMigration
int fromVersion(); int toVersion(); JsonValue migrate(JsonValue root);

// PlatformServices
void vibrate(HapticEvent e);  boolean hapticsAvailable();
void setHapticsEnabled(boolean enabled);
boolean openUrl(String url);  boolean share(String subject, String text);
SafeAreaInsets safeAreaInsets();  String deviceDescription();

// CrashLogger
static final String DEFAULT_PATH = "castle-defense/logs/game_errors.log";
void installGlobalHandler();
void setContextProvider(ContextProvider p);   // one line describing the world
void logCrash(Throwable t, String where);
void logWarning(String m);  void logInfo(String m);
String path();

// JsonSource
JsonValue read(String path);  boolean exists(String path);
```

## Important invariants

1. **Every save is versioned.** `saveVersion` is written on every save and read
   on every load. An unversioned or unknown-version file is treated as broken
   and falls back to defaults rather than being parsed hopefully.
2. **Migrations are a chain, applied in order.** Each `SaveMigration` moves the
   JSON from `fromVersion` to `toVersion`. Nothing skips versions, and a save
   from any earlier version reaches the current one by composition. Version 1 is
   the current schema, so the chain is currently empty — the mechanism is
   proven by tests, not by production data.
3. **One shared `highScore`.** Classic and Endless share a single high score, as
   the Python game does. Per-mode scores would be a gameplay change, not a port.
4. **Loading never throws.** A missing, truncated, corrupt or future-versioned
   save produces defaults and a `lastLoadNote()` explaining why. A bad file
   cannot stop the game from starting.
4b. **A write never destroys a good save.** Saves are staged:
   serialise → write `save.json.tmp` → **read it back and re-parse it** →
   replace `save.json` (`File.renameTo`, atomic on Android and desktop; a copy
   where a rename is refused). The live file is untouched until a validated
   replacement exists on disk. The one window where the live file can be absent —
   killed mid-replace — is covered by recovery: `load()` promotes a valid staged
   file and says so in `lastLoadNote()`. So after any interruption, either the
   old save or the new one is intact; never neither. A stale staged file is
   discarded once a good live save loads, and `delete()` removes both.
   `java.nio.file` is deliberately unused — API 26+, not covered by desugaring,
   so it would crash on minSdk 21 devices.
5. **A save happens at `pause()`.** Android may never call `dispose()`, so
   backgrounding is the last reliable write opportunity and `persist()` is
   called there as well as on dispose.
6. **Quality settings never change gameplay.** `QualityConfig` controls particle
   caps, glow, shadows and trails only. Two players on different presets get
   identical simulations. (`GameConfig.MAX_PARTICLES` remains the gameplay cap;
   quality may lower the *rendered* count.)
7. **Crash logging is installed first.** `Services.start()` runs it before
   anything that could fail, and `CastleDefenseGame.create()` registers the
   context provider immediately after, so even a startup data failure is logged
   with world context rather than dying silently. A broken data file is logged
   and exposed as `getStartupFailure()`; the game continues far enough to say so.
8. **`core` holds no platform code.** Every capability that differs by platform
   is an interface method; the desktop and Android launchers supply the
   implementation, and `NoOpPlatformServices` supplies the test one.
9. **User-identifying data stays local.** The crash log and save file are
   written to local storage only. Nothing in this subsystem transmits anything.

## Relevant source files

```
core/src/main/java/com/mymmer/castledefense/Services.java
core/src/main/java/com/mymmer/castledefense/persistence/SaveManager.java
core/src/main/java/com/mymmer/castledefense/persistence/SaveData.java
core/src/main/java/com/mymmer/castledefense/persistence/SaveMigration.java
core/src/main/java/com/mymmer/castledefense/platform/PlatformServices.java
core/src/main/java/com/mymmer/castledefense/platform/NoOpPlatformServices.java
core/src/main/java/com/mymmer/castledefense/platform/HapticEvent.java
core/src/main/java/com/mymmer/castledefense/platform/SafeAreaInsets.java
core/src/main/java/com/mymmer/castledefense/util/CrashLogger.java
core/src/main/java/com/mymmer/castledefense/data/*.java
core/src/main/java/com/mymmer/castledefense/config/QualityConfig.java
```

## Relevant tests

```
core/src/test/java/com/mymmer/castledefense/persistence/SaveManagerTest.java
core/src/test/java/com/mymmer/castledefense/util/CrashLoggerTest.java
core/src/test/java/com/mymmer/castledefense/ServicesStartupTest.java
core/src/test/java/com/mymmer/castledefense/config/DifficultyTableTest.java
core/src/test/java/com/mymmer/castledefense/testsupport/InMemoryJsonSource.java
```
