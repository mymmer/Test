# Castle Defense — Java/LibGDX port

This directory contains the Java/LibGDX port of the Castle Defense game. It is
**isolated from the Python implementation on purpose**: the Python game in the
repository root (`main.py`, `sprites.py`, `castle.py`, `enemies.py`) stays the
authoritative, executable reference until this port reaches full parity, and
nothing here modifies it. Both can be run side by side for parity testing.

* Migration checklist: [`../PORTING_STATUS.md`](../PORTING_STATUS.md)
* Analysis and design: [`../docs/PORT_ANALYSIS.md`](../docs/PORT_ANALYSIS.md)

**Current state: Phase 2 (foundation).** There is no gameplay yet — no enemies,
towers, skins or balance data. What exists is the project skeleton, the two
viewports, the lifecycle, both launchers and the headless test foundation.

---

## Modules

| Module | Purpose | Depends on |
|---|---|---|
| `:core` | The game. Platform-independent: no Android types, no LWJGL types, no file paths. | `gdx` only |
| `:lwjgl3` | Desktop launcher — the development loop. | `:core`, `gdx-backend-lwjgl3` |
| `:android` | Android launcher — the production target. | `:core`, `gdx-backend-android` |

`:android` is included in the build **only when an Android SDK is configured**
(`local.properties` with `sdk.dir=…`, or `ANDROID_HOME` / `ANDROID_SDK_ROOT`).
Without it Gradle prints a one-line notice and builds `:core` + `:lwjgl3`
normally, so a missing SDK never blocks desktop development. Check with:

```bash
./gradlew whatBuilds
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

# android (needs the SDK configured)
./gradlew :android:assembleDebug
```

Launcher flags: `--size WxH`, `--frames N`, `--screenshot FILE`, `--no-vsync`.

## Pinned versions and why

All versions live in [`gradle/libs.versions.toml`](gradle/libs.versions.toml) —
never in a build file — so an upgrade is a one-file change.

| Pin | Version | Why this one |
|---|---|---|
| Java language level | **17** | The highest level Android tooling handles without core-library desugaring, and the level AGP 8.x expects. Applied with `options.release`, so `javac` also rejects JDK APIs that Android lacks. |
| Gradle | **8.14.3** | Current stable; required by (and compatible with) AGP 8.7.x. The wrapper pins it so every machine builds identically. |
| libGDX | **1.14.2** | Latest release on Maven Central at the time of writing (verified 2026-09-01). |
| JUnit Jupiter | **5.14.4** | Latest 5.x. JUnit 6 is available but 5.x is the version every Android/Gradle toolchain integrates with today; there is nothing in 6 this project needs. |
| Android Gradle Plugin | **8.7.3** | Conservative, widely deployed, compatible with Gradle 8.9+. See the caveat below. |
| compileSdk / targetSdk | **35** | Android 15, matching AGP 8.7. |
| minSdk | **21** | Android 5.0. Covers effectively the whole active device base and needs no desugaring. **Consequence:** `core` must avoid `java.util.function`, `java.util.stream`, `Optional` and `java.time`, which are API 24+. This is why the renderer seam uses a hand-written `GameRendererFactory` instead of `Supplier`. |

### Android build status in this environment

The Android Gradle Plugin and the Android SDK are published on Google's Maven
(`dl.google.com` / `maven.google.com`), which the sandbox this port was created
in **blocks by policy** (`HTTP 403` on CONNECT). Maven Central mirrors AGP only
up to 2.3.0 (2017), which is unusable.

Therefore, at the end of Phase 2:

* `:core` and `:lwjgl3` — compiled, tested and **run**.
* `:android` — written and XML-validated, but **not compiled or assembled**.
  The AGP pin (8.7.3) and `compileSdk 35` are conservative choices, not verified
  ones. On a machine with the SDK, `./gradlew :android:assembleDebug` is the
  command that proves it; if AGP 8.7.3 turns out to be unavailable or too old
  for the installed SDK, changing `agp` in `libs.versions.toml` is the whole fix.

This is recorded as an open item in `PORTING_STATUS.md` rather than being
claimed as done.

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
