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

## 9. Phase 13.1 — the readiness pass

### 9.1 Two kinds of inset, kept apart

Phase 13 recorded that 45% of SETTINGS sat inside the navigation strip and left
it there. The cause was reading one inset family. This phone's cutout is 142 px
on the **left**; its navigation strip is 168 px on the **right**. Reading the
cutout alone gets one edge right and the other wrong.

| family | what it is | rule |
|---|---|---|
| **obscuring** | system bars + display cutout | nothing that must be seen or pressed |
| **gesture** | `mandatorySystemGestures` — strips the system takes a touch from, which an app may not opt out of | visible and drawable; **not reliably pressable** |

`SafeArea` now carries both: a display rectangle for extents, and a **touch
rectangle** — the wider strip on each edge — that every control is laid out
against. Decoration still reaches the display edge, because a background under a
navigation bar looks right.

The full `systemGestures` region is deliberately **not** used. It is larger, it
includes back-swipe edges an app may exclude, and treating it as unusable would
surrender far more screen than the platform actually claims.

Read with a gate per API — `getRootWindowInsets` at 23, cutouts at 28, gesture
insets at 29, typed `getInsets` at 30 — against minSdk 21, where each simply
contributes nothing. The result is cached and recomputed only when Android
reports new insets, so the layout now **follows** them: immersive bars swiping
in, rotation, resume. Previously they were read at `create()` and `resize()`
only, which misses every change that does not resize the window.

On the device: `game=insets[l=142 r=0 t=0 b=0 gesture l=0 r=168 t=84 b=0]`,
matching Android's `mandatory` exactly. `menu.settings` moved from screen
2708–3008 to **2540–2840**, against a strip beginning at 2872.

Centred *text* follows the touch rectangle too — not because a caption can be
stolen by a gesture, but because it has to line up with the buttons beneath it.

### 9.2 Defects fixed

| # | Defect | Evidence |
|---|---|---|
| 1 | SETTINGS 45% inside the navigation strip | measured hit box against measured inset |
| 2 | `!enemy.foot_soldier.name!` on the NEW FOE banner | seen on the phone |
| 3 | `!skill.{lightning,meteor,tornado}.short!` under every skill slot | seen on the phone |
| 4 | Talent branch headings printed through "0 POINTS TO SPEND" | seen on the phone |
| 5 | `LayerTimes` op counters never reset (Phase 13 leftover) | counts grew window on window |

Defects 2 and 3 are the **third** appearance of one mistake. The bundle keys
bosses as `boss.<id>` and difficulties as `difficulty.<id>`; the renderer asked
for `enemy.<id>.name`. Phase 12 fixed exactly this shape for difficulties.

### 9.3 Interactions verified on hardware

Through the production input path — injected `MotionEvent`s enter the same
pipeline a finger does.

| Interaction | Evidence |
|---|---|
| Grab / drag / throw | `grabbed=<type> owns=true busy=true`, mob visibly lifted |
| Armour stripping | `stripping=siege_ram`, "DRAG AWAY TO STRIP THE PLATING" and a progress bar |
| Tower overcharge | `charging=YES`, slingshot line drawn, **46%** charge meter |
| Boss regalia (crown) | `held=YES`, crown detached and carried |
| Lich staff disarm | `held=YES`, staff detached |
| Skills: lightning, meteor, tornado | armed from the slot and cast into the world; kills 0 to 37 |
| Challenge Horn | pack spawned, 27 to 39 enemies, multiplier 2.26 to 4.68 |
| Buy a tower | 220 to 110 gold, "Owned: 1", price 110 to 138, bowman appears on the wall |
| Talents open and close | Back returns to the armoury |
| Endless shop freeze | **alive 24, 25, 27 while playing; then 28, 28, 28, 28 across 12 s in SHOP; then 29, 31** |
| Back to pause | `[screen] PLAYING -> PAUSED` |
| Game Over to restart | `PLAYING -> GAMEOVER -> MENU -> SHOP`, fresh run at 220 gold, wave 1 |
| Lifecycle with a live grab | Home/resume and lock/unlock; nothing held on return, no crash |

The shop-freeze figures are worth reading twice: `steps/frame` stayed at **1.00**
throughout. The frame keeps running at 60 Hz; it is the **world** that is frozen,
not the loop.

### 9.4 Driven by test instead, and why

| Interaction | Why not on hardware |
|---|---|
| Dragon claws | a small box on a boss in flight; injected taps miss it, and 36 rapid `input tap` calls produced 5 delivered events |
| Second-finger ownership | `adb shell input` injects one pointer; two simultaneous contacts cannot be synthesised |
| Ram strip to shovable transition | the moment after a strip completes is gone before another injected drag arrives |

These run through real `GameInput` to `InputRouter` to `CursorInteraction` with
real screen pixels, and with the **production** cursor installed rather than
`TestUi`'s spy — a spy grabs nothing, so "nothing was grabbed" would be true
because nothing can ever be grabbed.

`ThrowCancellationTest` assembles the whole `CastleDefenseGame`, because
`TestRun` wires the velocity source to a stub that returns zero: on that harness
"the cancelled drag threw nothing" passes whatever the game does. It asserts that
a released drag genuinely throws **before** asserting the cancelled one does not.

Two of my own assertions were wrong and the game was right. A stripped Siege Ram
does not become liftable — mass 9 exceeds an unupgraded cursor, so it becomes
**shovable**. And a cancelled grab does not leave the mob standing: it drops.
The real claim is about velocity, not posture.

### 9.5 Multi-touch is still unverified

It cannot be injected. `docs/MANUAL_DEVICE_CHECKLIST.md` section A is five
minutes of real fingers and is the only thing that can close it.

## 10. Measurement terminology

Phase 13 reported a number as "CPU time" that was mostly waiting. Each quantity,
named for what it actually is:

| Quantity | How it is obtained | Status |
|---|---|---|
| **Delivered frame interval / fps** | wall time between successive frame starts | **measured** |
| **Wall time inside `render()`** | stopwatch around the game's own frame | **measured** — and it includes GL/vsync blocking, so it is *not* a workload |
| **Simulation work** | stopwatch around the fixed-step loop | **measured** |
| **Render-layer work** | `LayerTimes`, per draw layer | **measured**, but only the layers instrumented in Java |
| **GL / vsync blocking** | frame wall time minus instrumented work | **inferred**, not measured directly |
| **GPU time / utilisation** | — | **not measured.** No profiler was installed |
| **GC count and cumulative time** | `Debug.getRuntimeStat` `art.gc.gc-count` / `gc-time` | **measured**; cumulative and includes concurrent collection, so it is not a frame-time cost |
| **Thermal** | `PowerManager.getCurrentThermalStatus()` | **measured as a status enum.** `0 = NONE`. It is **not** a temperature |

**The sum of the instrumented Java layers is not CPU utilisation and is not GPU
utilisation.** It is the cost of the shape pass and the simulation, which is what
it is labelled as.

Every measurement window records `state=`, `alive=` and whether `keepAlive` is
on. A window in `GAMEOVER` is not a live benchmark and is never reported as one.

The Phase 13 baseline — nine live scenarios and the five-minute soak — is
unchanged and was not re-run. 13.1 changed layout constants, key lookups and
tests; none of them touch the draw loop's cost.

## 11. Text size on this phone

Measured, not eyeballed. The UI viewport is 1520x720 over 3040x1440, so
**1 UI unit = 2 px**, and this panel's density is **3.5 px/dp**.

| element | UI units | px | dp |
|---|---|---|---|
| Title | 40 | 80 | 22.9 |
| Top-right run statistics | 22 | 44 | 12.6 |
| Subtitle | 20 | 40 | 11.4 |
| Bottom instructions, skill cooldown | 18 | 36 | 10.3 |
| Badge | 17 | 34 | 9.7 |
| Shop card name | 16 | 32 | 9.1 |
| Horn label, shop price | 15 | 30 | 8.6 |
| Health and boss bar numbers | 14 | 28 | 8.0 |
| Shop "Owned", talent rank | 13 | 26 | 7.4 |
| Skill caption, talent level | 12 | 24 | 6.9 |

Android's guidance puts the minimum comfortable caption at **12 sp**, which is
42 px here. **Only the title clears it.** The layout is a faithful port of a
1280x720 desktop design, and a design that is comfortable on a monitor is small
on a handheld at the same logical size.

**Not changed in 13.1, deliberately.** Fixing it with a global UI scale is a
redesign, and a per-element floor would flatten six distinct sizes into one and
risk overflowing the shop cards and talent nodes that contain them. Section D of
the manual checklist asked for a human judgement on it, and 13.2 is the answer:
the shop and the talent tree were **the two screens the judgement came back
against**, and both were enlarged there by growing their containers first — see
13.2 and 13.3. The rest of this table still stands as measured.

## 12. Verified, and not

| | |
|---|---|
| Android assembly | **Verified** — builds, lint clean, four ABIs |
| Emulator | **Verified** earlier; superseded by hardware |
| Physical device: functionality | **Verified** — every interaction above, on a Galaxy S10+ |
| Physical device: performance | **Verified** — 60 fps sustained, nine scenarios, numbers above |
| Physical device: lifecycle | **Verified** — background, resume, lock, GL restore |
| Multi-touch | **Not verified** — cannot be injected over adb; see MANUAL_DEVICE_CHECKLIST.md section A |
| Other devices | **Not verified** — one phone, one SoC, one 60 Hz panel, one cutout |
| Gesture-navigation devices | **Not verified** — this device uses 3-button navigation |
| 90/120 Hz panels | **Not verified** — and the frame budget would be 11.1 or 8.3 ms there, not 16.7 |
| Grab reliability on real fingers | **Not verified** — the cause is measured, the tolerance is not; see 13.6 |

## 13. Phase 13.2 — the play review

Seven findings from playing the build on the phone. Each was traced to the
authoritative source before anything changed.

### 13.1 Lightning was applied and never drawn

The Troll King has no lightning: he leaps, smashes a tower, and wears a crown.
The source has exactly two lightning sources and both fill one list,
`Game.bolts` — a mob flung above `STORM_CEILING` during a storm
(`enemies.py:586` → `main.py:1426`) and the Lightning Strike skill
(`main.py:693`).

Both were silent here. `Weather.strike` applied the damage, started the
cooldown, set the flash and shook the screen and **emitted no visual events at
all**; so did `castLightning`. The white-out veil *was* ported, which is why
something clearly happened and nothing showed what.

`Palette` already held `BOLT_CORE` and `BOLT_INNER` in the source's two stroke
colours. Phase 11 named the paint and never drew the stroke.

`VisualEvents` gains `bolt(x, y, life)` — a fourth `void` on the same one-way
seam, carrying only the anchor and the lifetime. The zig-zag is decoration,
generated in `EffectsSystem` from `VisualRng`. The source re-rolls that path
every frame, which is what makes a bolt flicker, so the key is the bolt plus its
remaining life: both strokes agree within a frame and the path re-rolls between
frames. The gameplay generator is never touched, and a test proves it by
comparing a struck run against an unstruck one.

The skill's four scattered bolts are spread evenly rather than randomly: the
source rolls those offsets from its own generator and this port has no gameplay
roll to spend on decoration.

### 13.2 The shop explained nothing

`main.py` puts a stripe, a hotkey, a name, an icon, a counter tag, a three-line
wrapped description, a status line and a price on every card. This port drew a
name, a price and "Owned: N" — so a player learned what an upgrade cost and
never what it did, though the descriptions had been in the bundle since Phase
10. The card height cap of 112 units is why; it is now 150, of which the layout
already had 146.

What the **next** purchase does is answered by the shop, not the interface:
`ItemView.upgradesInstead` is `main.py:1047`'s "(upgrades)" tail. No cost or
effect formula was copied into the renderer.

### 13.3 The talent tree, and a scroll nobody called

Nodes are 64 units rather than 46, names 16 rather than 13, ranks 15 rather than
12 — the old sizes were 7.4 and 6.9 dp against Android's 12 sp minimum.

The source shows a tooltip for whatever the mouse hovers. A finger has no hover,
so it is shown for the **selected** talent, and selection already existed: a
first tap selects and only a second tap on the same node spends a point.
Inspecting has never bought anything.

That uncovered the hazard: `scrollBy` existed and **nothing called it**. The
tree could not scroll, so any talent below the visible rows was unreachable —
and taller nodes would have hidden three for good. Two scroll buttons, not a
drag: a drag on a modal screen is owned by nobody today and inventing an owner
would touch the router's rules for nothing visible. A test walks the scroll on
three shapes and asserts all 38 are reachable.

The talent **value** comes from the data. `main.py` formats each `{v}` itself —
30 as `.0%`, one as `.1%`, four as `.0f`, one as `.1f`, two with no value. "Below
1.0 is a percentage" gets 35 right and `spikedot` (perRank 0.9, written as a
plain number) wrong, printing "90%" where the game means 0.9 damage a tick. So
`talents.json` carries the format.

Annotating 38 entries by hand missed two of them — `lightfingers` and
`stormwinds`, both `{v:.0%}` — and a missing key defaults to "print no value",
which is silent. `TalentPresentationTest` now counts the formats against the
source's own tally (30 / 1 / 4 / 1, and exactly Sentinels and Tempest with no
value), so a talent cannot go quiet again.

### 13.4 The HUD had been pushed onto the keep turret

My own regression from 13.1. The panel was anchored to the **touch** rectangle
so its SHOP button would clear the navigation strip; that rectangle also
excludes the gesture strips, and the top one is 84 px — 42 units. The panel hung
42 units lower on the phone and its lower edge landed across the keep-top
emplacement at gameplay y 234.

Nothing about the panel needed to dodge a gesture strip. It is anchored to the
display rectangle again, at the source's own `HUD_X 14, HUD_Y 12`, and
`SafeAreaLayoutTest` still checks the button against the touch rectangle.

A second divergence made it worse: `sprites.py` draws the panel `(26, 28, 42)`
at alpha 190 and this port used `(15, 18, 28)` at 0.88 — darker **and** more
opaque, so a turret behind it was invisible rather than dimmed. Both now match.

Stated plainly: the panel and the keep slots **do** overlap when the panel is
tall, in the source as much as here, because the panel grows with its rows. What
must never happen is the panel eating the press, and it does not — `main.py`
checks its two buttons and falls through to `try_grab`. Verified on the phone:
pressing the keep turret reports `outcome=TOWER charging=YES`.

### 13.5 The Outpost's cage was never drawn

`castle.py Outpost.draw_prisoner` draws a cage, a glow, the hunched Necromancer,
four bars, **his** health bar and two captions. None of it was ported, while the
gameplay trapped him, drained him, let rivals shoot him, regenerated him and
released him on death.

The bar reads `prisonerHp / prisonerMax`. The source's Outpost has no health of
its own and this port does not give it one to feed a bar with — a test pins
that, because inventing a pool for the structure is the obvious wrong way to
make the indicator appear.

### 13.6 Grabbing: the target is smaller than the finger

Not a hitbox bug. The hit box is exactly `hit_rect.inflate(16, 16)`, centred on
the same point. Ruled out by measurement: the coordinate conversion (already
pinned by `WorldPickingTest`) and render interpolation, which lags the
authoritative position by at most one step of motion — about 2 units for a Scout
against a box 42 wide.

The cursor now records **why** a press produced nothing, and the phone answered:
in a dense crowd every tap grabbed; isolated mobs missed, by 23 world units in
one case.

| | |
|---|---|
| Scout body | 26 x 34 world units |
| Grab box | 42 x 50 (`+16` each axis) |
| On this panel | 84 x 100 px, **24 x 29 dp** |
| Android minimum target | 48 dp |
| Fingertip contact patch | 8–10 mm against the box's 4.6 mm |

So an **acquisition** tolerance of 18 world units, reported by the platform —
zero on a mouse, because a mouse points at a pixel. Consulted only after every
exact test has failed, so nothing exact is ever overridden by something merely
close. `grabCovers` is untouched: collision, damage, splash and crowd separation
are what the parity fixtures recorded, and a test asserts the gameplay box still
refuses a point acquisition accepts.

Separately, a real parity bug found while reading: `main.py:1835` keeps the match
with the **smallest x** — "prefer the nearest threat" — and this port returned
whichever came first in the target list, so a press into a crowd could lift
someone standing behind the mob under the finger. Both
`enemy_under_mouse` and `heavy_under_mouse` now match.

**The cause is measured; the feel is not.** adb cannot reproduce a human's
aiming error, so whether 18 units is the right number is a question for real
fingers.

### 13.7 Deliberate departures from Python

| | Why |
|---|---|
| Talent detail panel follows **selection**, not hover | a finger has no hover |
| Talent scroll **buttons** | the tree could not scroll at all; a drag would need a new owner in the router |
| Node and card text enlarged | the source's sizes are 7–9 dp on this panel |
| Shop cards up to 150 units tall | to carry the description the source has and this port dropped |
| Grab acquisition tolerance, touch only | the target is smaller than the finger; gameplay boxes unchanged |
