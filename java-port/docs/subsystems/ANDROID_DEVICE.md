# ANDROID_DEVICE.md — Phase 13

What a real phone said, and what it found that nothing else could.

---

## 1. The headline

**The game had never been connected to input.** Not on Android, not on the
desktop, not since Phase 3. `Gdx.input.setInputProcessor` was never called, so
libGDX had nowhere to deliver a touch and `GameInput.touchDown` was never once
invoked in a running build.

**And underneath it, every world interaction was aimed at the wrong half of the
screen.** The input path unprojected a touch into draw space and handed it to
gameplay unconverted, so a finger on the ground arrived at gameplay y 125 —
mirrored about the horizon, up among the stars. Grabbing, throwing, armour
stripping, tower overcharge, the boss crown and the Dragon's claws were all
unreachable.

Neither is a mobile defect. Both were found in the first ten minutes of pressing
the game with a finger, because pressing the game with a finger is a thing no
test and no screenshot had ever done.

## 2. The device

| | |
|---|---|
| Model | Samsung Galaxy S10+ (SM-G975F, `beyond2lte`) |
| SoC | Exynos 9820, 8 cores |
| Android | 12 (API 31) |
| Display | 3040×1440, 560 dpi, **60 Hz** |
| Cutout | punch-hole, 142 px on the left short edge in landscape |
| Navigation | 3-button (`navigation_mode=0`), 168 px reserved on the right |
| RAM | 7.6 GB |
| Build | `9d0e446` at entry, APK 5.1 MB, minSdk 21 / targetSdk 35, four ABIs |

## 3. Defects found, all fixed

| # | Defect | How it showed | Fix |
|---|---|---|---|
| 1 | **No input processor registered** | CLASSIC WAVES, SETTINGS and the difficulty buttons all did nothing | one line in `create()`, plus `InputWiringTest` |
| 2 | **Back never routed** | `Navigation.back()` implemented the full policy; nothing called it, so Back always left the app | latched on the input thread, applied once per frame |
| 3 | **Menu difficulty row not drawn** | three invisible but pressable controls in empty sky | drawn, as `main.py` draws them |
| 4 | **Two sources for the preferred difficulty** | menu said NORMAL while the run started on HARD | one source: the save |
| 5 | **Touch delivered in draw space** | a finger on the ground reported y 125; nothing was ever grabbable | `WorldGeometry.toGameplayY` at the one boundary |
| 6 | **`!hud.hornSpent!`** | raw key drawn under the Challenge Horn | key that exists; scan broadened |
| 7 | **`!settings.unmute!`** | same shape, found by the broadened scan | state keys, as the source uses |
| 8 | **`LayerTimes` op counts never reset** | counts grew window on window and read as a rising cost | reset with the timings |

Defects 1–5 and 8 were invisible to 926 passing tests. Every one of them was in
the wiring or the units, not in the logic — the logic was right and unreachable.

### Why the tests missed the big one

`ShakeInputTest` drives the exact path defect 5 lived in, and even places a mob
at y 600. But it asserts the pointer **does not drift** under camera shake —
comparing the value against itself. A number that is consistently wrong passes
that perfectly. The router tests build pointers directly, in whichever space
they were asserting.

`WorldPickingTest` now asks the question none of them did: the absolute position,
against a landmark whose gameplay value is known. It runs the real
`CursorInteraction` rather than `TestUi`'s spy — with a spy in that seat, "a
press on empty sky grabs nothing" passes without proving anything.

## 4. Measuring on a phone

There is no command line and no stdout, so `--bench` and `--scenario` arrive as
intent extras and report to logcat:

```
adb shell am start -n com.mymmer.castledefense/.android.AndroidLauncher \
    -e bench dense -e scenario endless-late --ez keepAlive true --ei benchWindow 300
```

`MeasuredGame` stages the same seeded `VisualScenarios` the desktop uses, so the
two machines measure the same scene. `--ez dumpUi` prints every control's hit
rectangle in interface units *and* screen pixels, which is how defect 3 was
identified as invisible rather than merely unresponsive. `--ez layerTimes` gives
the per-layer breakdown.

### Two traps, both hit again

- **Measuring a dead world.** The staged scenes drop twenty-odd enemies at an
  undefended castle and it falls in seconds. A finished run still *draws* the
  whole scene, so frame times stay perfectly plausible while the simulation has
  stopped. The first device sweep measured **seven scenarios out of nine in
  GAMEOVER** — Phase 12's trap exactly. The probe now prints the world state
  beside every window, and `--ez keepAlive` holds the castle up.
- **Mistaking waiting for working.** See below.

## 5. What the numbers say

Nine scenarios, live worlds, 300-frame windows, first window discarded:

| scenario | fps | frame p50 | p95 | p99 | sim p50 | sim p99 | dropped | alive |
|---|---|---|---|---|---|---|---|---|
| mixed-wave | 59.8 | 16.70 | 19.28 | 21.26 | 72 µs | 178 µs | 0 | 34 |
| endless-late | 59.7 | 16.68 | 20.40 | 23.22 | 68 µs | 211 µs | 0 | 44 |
| all-bosses | 59.7 | 16.73 | 19.31 | 22.02 | 70 µs | 209 µs | 0 | 21 |
| projectiles | 59.8 | 16.66 | 19.02 | 21.47 | 71 µs | 224 µs | 0 | 8 |
| storm | 59.8 | 16.66 | 19.10 | 21.03 | 93 µs | 232 µs | 0 | 35 |
| tornado | 59.8 | 16.69 | 19.40 | 21.38 | 66 µs | 211 µs | 0 | 29 |
| fire-zone | 59.8 | 16.70 | 19.58 | 20.68 | 63 µs | 143 µs | 0 | 15 |
| particles | 59.6 | 16.70 | 19.10 | 20.89 | 97 µs | 213 µs | 0 | 11 |
| all-enemies | 59.8 | 16.69 | 19.41 | 20.86 | 67 µs | 158 µs | 0 | 26 |

**60 FPS is sustained in every scenario**, with zero dropped or clamped
simulation steps after startup and `thermal=0` throughout.

### Waiting is not working

The probe's `cpu_p50` — time inside the game's own `render()` — came back at
**13 ms** in every scenario. Identical for `all-bosses` (3 entities) and
`endless-late` (28 enemies and 2 bosses), and identical again on the empty menu.
A fixed floor that large would leave almost no headroom.

It is not a workload. Three measurements say so:

- **Resolution.** 3040×1440, 1520×720 and 1013×480 give 13.03, 12.95 and
  12.96 ms. A nine-fold cut in pixels changes nothing, so it is not fill rate.
- **Quality.** LOW, MEDIUM and HIGH give 13.08, 12.98 and 13.09 ms.
- **Layers.** The shape pass totals **2.6 ms**, and simulation 0.07 ms.

So the frame does about **2.7 ms of real work in a 16.7 ms budget — 16%** — and
spends the rest blocked in GL waiting for the display. `cpu_p50` measures the
wait, not the cost, and any conclusion drawn from it alone would have been
wrong in the alarming direction.

Per-layer, `endless-late`:

| layer | µs | share |
|---|---|---|
| enemies | 1623 | 62% |
| background | 397 | 15% |
| castle + spikes | 285 | 11% |
| towers + barricade | 166 | 6% |
| items/zones/projectiles | 85 | 3% |
| outpost | 41 | 2% |
| effects + weather | 34 | 1% |

Phase 12's desktop profile put enemies at 53–58% and the background at 22–25%.
The phone agrees closely enough that the desktop numbers were a fair guide —
which is worth knowing, since they were all there was for two phases.

**No gameplay broadphase, cache or pooling was added**, because the phone says
what the desktop said: simulation is 0.4% of a frame.

### Soak: five minutes, no drift

31 windows of 600 frames — 18 600 frames, about 5 min 10 s of continuous
`endless-late`:

| | |
|---|---|
| fps | 59.6 – 59.8, no trend |
| frame p50 | 16.57 – 16.77 ms, no trend |
| frame p99 | 20.8 – 26.1 ms |
| dropped / clamped steps | 0 after the first window |
| **thermal status** | **0 (`NONE`) in all 31 windows** |
| GC | 2 → 170 collections, 115 → 5783 ms cumulative |

No throttling, and no degradation to throttle. The GC figure is the one thing
worth noting: about one collection every 1.8 s, which says the frame allocates
steadily. It costs nothing measurable here — p99 never moves — so nothing was
changed for it, but it is the number to watch first if a weaker device ever
stutters.

## 6. Quality presets

Same scene, same seed, LOW / MEDIUM / HIGH: 13.08, 12.98, 13.09 ms — no
measurable difference, because the frame is display-bound long before the
particle budget matters. Presets stay as they are; there is nothing on this
device for a lower one to buy, and `EffectsAndQualityTest` still holds gameplay
identical across them.

On a weaker or higher-resolution phone that conclusion could differ, and the
harness to re-measure it is `-e quality LOW`.

## 7. Layout, safe area and touch

- **Landscape**, `rotation=1`, fixed by the manifest. Immersive; no system bars.
- **World letterboxing is correct**: 2560×1440 of 16:9 world, pillarboxed 240 px
  each side of a 2.111:1 panel. The 1280×720 world was *not* stretched to fill it.
- **UI is not letterboxed** — its viewport is the full 3040×1440, which is why
  SETTINGS sits in the corner rather than inside the world box.
- **Cutout**: 142 px, left short edge. `AndroidPlatformServices` reads
  `DisplayCutout` and the safe area honours it; no control crosses it.
- **Gesture/navigation**: `gesture=l142,r168,t84,b0`. `menu.settings` spans
  x 2708–3008 and the reserved right strip begins at 2872, so **136 px of that
  button (45% of its width) lie inside the system's strip**. On this device that
  strip is a hidden 3-button bar, and the button still responded at its centre —
  but it is the one control with no margin, and worth moving if a gesture-nav
  device ever shows a problem. Recorded, not fixed: no defect was demonstrated.

Interactions exercised through the production input path (`adb shell input`,
which injects real MotionEvents into the same pipeline a finger uses):

| | |
|---|---|
| Classic / Endless selection | works |
| Difficulty selection | works (after defects 3 and 4) |
| Armoury: buy a tower | works — 220 → 110 gold, "Owned: 1", price escalates, bowman appears on the wall |
| Talents open / close | works; Back returns to the armoury |
| Send wave | works |
| Back → pause | works (after defect 2) |
| Endless shop open / close | works; Back closes it to PLAYING, not to the menu |
| Challenge Horn | works — pack spawned, 27 → 39 enemies, multiplier 2.26 → 4.68 |
| Grab, drag, throw | works (after defect 5) |
| Boss crown (regalia) | works — detached, carried, thrown |
| Presses behind the HUD | the panel claims them; the world does not see them |

**Not verified: multi-touch.** `adb shell input` cannot inject two simultaneous
fingers, and no second pair of hands was available. The router's one-interaction
ownership rule is covered by tests, but two real fingers on real glass are not.

## 8. Lifecycle

Home → resume, and screen off → on, twice each. No crash, no GL error, two clean
`paused`/`resumed` pairs in the log, and **the background FrameBuffer cache
redrew correctly** — sky, stars, moon, hills and ground all present after the
context was lost. That is the one resource Phase 12 flagged as needing
invalidation on resume, and it does.

Back from the main menu exits the app, which is the documented platform default.

## 9. Verified, and not

| | |
|---|---|
| Android assembly | **Verified** — builds, lint clean, four ABIs |
| Emulator | **Verified** earlier; superseded by hardware |
| Physical device: functionality | **Verified** — every interaction above, on a Galaxy S10+ |
| Physical device: performance | **Verified** — 60 fps sustained, nine scenarios, numbers above |
| Physical device: lifecycle | **Verified** — background, resume, lock, GL restore |
| Multi-touch | **Not verified** — cannot be injected over adb |
| Other devices | **Not verified** — one phone, one SoC, one 60 Hz panel, one cutout |
| Gesture-navigation devices | **Not verified** — this device uses 3-button navigation |
| 90/120 Hz panels | **Not verified** — and the frame budget would be 11.1 or 8.3 ms there, not 16.7 |
