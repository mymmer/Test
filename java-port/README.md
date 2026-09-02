# Castle Defense — Java/LibGDX port

This directory contains the Java/LibGDX port of the Castle Defense game. It is
**isolated from the Python implementation on purpose**: the Python game in the
repository root (`main.py`, `sprites.py`, `castle.py`, `enemies.py`) stays the
authoritative, executable reference until this port reaches full parity, and
nothing here modifies it. Both can be run side by side for parity testing.

* Migration checklist: [`../PORTING_STATUS.md`](../PORTING_STATUS.md)
* Analysis and design: [`../docs/PORT_ANALYSIS.md`](../docs/PORT_ANALYSIS.md)

**Current state: Phase 6 (enemies and interaction physics).** Both sides of the
fight exist: the castle, its towers and their counters; the eleven-unit enemy
roster with its armour, throw physics, crowd behaviour and wave composition; and
the cursor that grabs, throws, strips and shoves. Still to come: bosses (7), the
run directors and shop (8), talents (9), UI (10) and rendering (11).

Numeric parity with the Python source is proven for the isolated formulas by
fixtures generated from it — see [`../tools/parity/`](../tools/parity/).

Subsystem contracts: [`docs/subsystems/`](docs/subsystems/) —
[SIMULATION](docs/subsystems/SIMULATION.md) ·
[INPUT](docs/subsystems/INPUT.md) ·
[DEFENCES](docs/subsystems/DEFENCES.md) ·
[PROJECTILES](docs/subsystems/PROJECTILES.md) ·
[ENEMIES](docs/subsystems/ENEMIES.md) ·
[INTERACTIONS_PHYSICS](docs/subsystems/INTERACTIONS_PHYSICS.md) ·
[ASSETS_SKINS](docs/subsystems/ASSETS_SKINS.md) ·
[PERSISTENCE](docs/subsystems/PERSISTENCE.md).

---

## Modules

| Module | Purpose | Depends on |
|---|---|---|
| `:core` | The game. Platform-independent: no Android types, no LWJGL types, no file paths. | `gdx` only |
| `:lwjgl3` | Desktop launcher — the development loop. | `:core`, `gdx-backend-lwjgl3` |
| `:android` | Android launcher — the production target. | `:core`, `gdx-backend-android` |

### How `:android` inclusion is decided

`settings.gradle` looks for an SDK in this order: `sdk.dir` in
`local.properties`, then `ANDROID_HOME`, then `ANDROID_SDK_ROOT`. There are
exactly three outcomes, and **only the first is silent**:

| Situation | Outcome |
|---|---|
| No SDK configured anywhere | `:android` is omitted, with a printed notice. The desktop-only / CI / sandbox case: a developer without the SDK still gets a working build instead of a failure they cannot fix. |
| An SDK **is** configured | `:android` is **always** included. Nothing about the Android build is skipped, softened or caught — if the plugin cannot resolve, the SDK is the wrong version, or the manifest is invalid, **the build fails loudly with the real error**. |
| Configured but the path does not exist | The build **fails immediately** with a clear message. A stale `sdk.dir` is a misconfiguration, not an absence, and dropping the module would hide it. |

There is no `try`/`catch` anywhere in that decision. Print it any time with:

```bash
./gradlew whatBuilds
# modules: :core, :lwjgl3, :android
# android SDK: /opt/android-sdk  (from ANDROID_HOME)
```

## Running

```bash
# desktop, the fast loop
./gradlew :lwjgl3:run

# desktop with a phone-shaped window
./gradlew :lwjgl3:run --args="--size 2400x1080"

# headless smoke run: N frames, optional PNG, then exit (used by CI)
java -jar lwjgl3/build/libs/castle-defense.jar --size 2400x1080 --frames 90 \
     --screenshot /tmp/frame.png

# tests
./gradlew :core:test

# reproduce a run from a bug report's seed
./gradlew :lwjgl3:run -Dcastledefense.seed=8149274512

# regenerate the Python parity fixtures (read-only against the four source files)
python3 ../tools/parity/generate_fixtures.py

# android (needs the SDK configured)
./gradlew verifyAndroid           # assembles + reports the APK path
./gradlew :android:assembleDebug  # the same thing, plainly
```

Launcher flags: `--size WxH`, `--frames N`, `--screenshot FILE`, `--no-vsync`.

## Pinned versions and why

All versions live in [`gradle/libs.versions.toml`](gradle/libs.versions.toml) —
never in a build file — so an upgrade is a one-file change.

The toolchain is pinned as **one coherent, officially documented set**, not as
"newest of everything":

```
AGP 8.7.3  →  Gradle 8.9  →  JDK 17  →  compileSdk / targetSdk 35  →  minSdk 21
```

| Pin | Version | Why this one |
|---|---|---|
| Android Gradle Plugin | **8.7.3** | A stable, widely deployed AGP generation. Chosen first; everything else is chosen to match it. |
| Gradle | **8.9** | The version that ships with the AGP 8.7 generation and the minimum AGP 8.7 accepts. Deliberately *not* the newest Gradle: staying on the pairing Google tests is worth more here than a version number. |
| JDK (to run Gradle/AGP) | **17** | AGP 8.7's required minimum. Newer JDKs work, but 17 is the documented baseline. |
| Java source/target level | **17** | See the decision below. |
| compileSdk / targetSdk | **35** | Android 15, the level AGP 8.7 is built against. |
| minSdk | **21** | Android 5.0 — effectively the whole active device base. |
| libGDX | **1.14.2** | Latest release on Maven Central (verified 2026-09-01). Independent of the Android toolchain. |
| JUnit Jupiter | **5.14.4** | Latest 5.x. JUnit 6 exists but 5.x is what the Gradle/Android tooling integrates with today, and nothing here needs 6. |
| desugar_jdk_libs | **2.1.5** | Core-library desugaring, see below. |

To upgrade later: change the version in `libs.versions.toml`, change the
`distributionUrl` in `gradle/wrapper/gradle-wrapper.properties` to match, and
re-run the build. Nothing else references a version.

## Java APIs and Android — four separate things

These get conflated constantly, so the project states them separately:

| Concern | This project | Notes |
|---|---|---|
| **1. JDK that runs Gradle/AGP** | 17 (21 also works) | A build-machine property. Nothing to do with what the app may call. |
| **2. Java source/target language level** | **17**, in every module — but configured *differently* per module type. See "Two compilation models" below. |
| **3. Android platform API availability** | minSdk 21 | Which `android.*` APIs and which *bundled* JDK APIs exist on the device. Without desugaring this would cap the JDK library at roughly Java 7 + parts of 8. |
| **4. APIs supplied by desugaring** | enabled (`desugar_jdk_libs`) | AGP rewrites calls to modern JDK library APIs so they run on old devices. With it enabled, `java.time`, `java.util.stream`, `Optional`, `java.util.function` and friends are available **down to API 21**. |

**The rule for `core`, stated correctly:** modern Java is allowed. `Supplier`,
`Optional`, `java.time` and streams are *not* banned by minSdk — desugaring
covers them, and the Android module is configured for it.

What *is* restricted is narrower and is a **performance** rule, not a
compatibility one:

> Inside per-frame gameplay paths — the simulation step, collision loops,
> particle updates, projectile resolution — prefer plain allocation-free Java:
> indexed loops over arrays, primitives over boxed types, no streams, no
> `Optional` wrappers, no capturing lambdas. Everything the Python profile
> flagged (see `docs/PORT_ANALYSIS.md` §3) lives in those paths, and on a
> low-end phone the GC pressure is what shows up as stutter.
>
> Outside those paths — loading, configuration, skin validation, UI assembly,
> save migration, tests — use whatever reads best.

`GameRendererFactory` is a named interface for readability, not because
`Supplier` was unavailable.

### Two compilation models

Java 17 is the language level everywhere, but it is *configured* in two
different ways, and mixing them up breaks the Android build:

| Modules | Compiled by | How the level is set | Why |
|---|---|---|---|
| `:core`, `:lwjgl3` | the Gradle Java plugin | `options.release = 17` | `--release` pins the language level *and* the JDK API surface, so a plain JVM module cannot call an API that does not exist at that level. |
| `:android` | the Android Gradle Plugin | `android { compileOptions { sourceCompatibility/targetCompatibility = VERSION_17; coreLibraryDesugaringEnabled true } }` | AGP owns this compilation: it supplies the `android.jar` bootclasspath, runs D8/R8, and applies core-library desugaring. Forcing `--release` onto its `JavaCompile` tasks bypasses that bootclasspath and disables the desugaring contract — which shows up as confusing "cannot find symbol" or API-level errors. |

**Never put `options.release` in a `subprojects` block.** The root build script
says so in a comment, and `:android` has a `doFirst` guard that fails the build
if it ever appears there.

> **Android Java compatibility is UNVERIFIED.** Everything above is the correct
> configuration, but no Android compilation has happened in this environment (see
> the next section). It stays unverified until `:android:assembleDebug` succeeds
> on a real Android toolchain.

## Android build status in this environment

The Android Gradle Plugin and the Android SDK are published on Google's Maven
(`dl.google.com` / `maven.google.com`), which the sandbox this port was created
in **blocks by policy** (`HTTP 403` on CONNECT). Maven Central mirrors AGP only
up to 2.3.0 (2017), which is unusable.

So, as of the Phase 2 hardening pass:

* `:core` and `:lwjgl3` — compiled, tested and **run**.
* `:android` — written and XML-validated, but **never compiled or assembled**.
  The AGP (8.7.3) and desugaring (2.1.5) pins are conservative choices, not
  verified ones.

**Closing the gate** (on any machine with the SDK):

```bash
cd java-port
./gradlew verifyAndroid
```

`verifyAndroid` earns its place rather than aliasing one command: without the
module, `:android:assembleDebug` fails with Gradle's generic "project not
found", which reads like a broken build script instead of a missing SDK. The
task explains the actual reason, and on success prints the APK it produced so
the gate can be closed with evidence.

Until that run succeeds, `PORTING_STATUS.md` keeps `[ ] Android assemble
succeeds` open and **Phase 2 is not fully tested**.

## Layout

```
java-port/
  settings.gradle          module list + conditional :android
  build.gradle             shared config, no versions
  gradle/libs.versions.toml   every pinned version
  gradle.properties        JVM + Android flags
  assets/                  shared asset root (desktop + android)
  core/                    the game
  lwjgl3/                  desktop launcher
  android/                 android launcher
```

## Conventions

* `core` never imports `android.*`, LWJGL, or a backend. Platform capability is
  reached through interfaces implemented by the launchers.
* Gameplay classes hold no rendering code (the Python classes mix the two; the
  port separates them — see `docs/PORT_ANALYSIS.md` §5).
* Gameplay geometry is never derived from artwork dimensions.
* World coordinates are the Python game's 1280×720, unchanged, on every device.
