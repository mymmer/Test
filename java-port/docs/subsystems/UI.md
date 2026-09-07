# UI.md — the interface

Phase 10. The screens, the navigation graph, the layout rules and the input
contract. Rendering of entities, particles, weather and skill effects is Phase 11;
this document covers the chrome and everything that decides where it goes.

---

## 1. The shape of it

```
                    InputRouter
                         |
                    UiRoot  (a UiConsumer, registered FIRST)
                         |
      +---------+--------+--------+---------+---------+
      |         |        |        |         |         |
   MainMenu  Settings  Shop   Talents     Hud      Pause/GameOver
      |         |        |        |         |         |
      +---------+--------+--------+---------+---------+
                         |
                    Navigation          (every transition, in one place)
                         |
                     RunWorld           (gameplay: reads only)
```

Two rules hold the whole thing up.

**The interface reads gameplay and commands it; it owns nothing.** There is no
`displayGold`, no cached wave number, no copy of a cooldown. Every number on
screen is fetched from the subsystem that owns it at the moment it is drawn.
A cached copy is a copy that can be wrong, and the HUD is where a wrong number is
most visible. `ArchitectureTest` enforces the other direction: no gameplay
package may import `ui`.

**One press has one owner.** `UiRoot` is a `UiConsumer` on the existing Phase 4
router, registered before the world handler. If it claims a press, the world never
sees that pointer — for the whole gesture, not just the press. This is why
tapping a skill button does not also grab the enemy standing behind it.

## 2. Why not Scene2D

Scene2D would have brought a second interaction model beside `InputRouter`'s
pointer ownership: its own actor tree, its own hit detection, its own input
multiplexer, its own idea of who owns a touch. Phase 4 spent its whole budget
establishing that *one* pointer has *one* owner across press, drag, release and
cancel, with a multi-touch ownership token on top. Running a second model beside
it would mean two answers to "who has this finger", and the bug that produces —
a UI element and a grabbed enemy both following the same touch — is exactly the
class Phase 4 exists to prevent.

So the interface is immediate-layout and custom: a screen positions
`UiRect`s in `layout()`, the router asks it about a press in `press()`, and the
renderer draws the rectangles. It is about 1,200 lines in total, it needs no
skin file, and — the part that actually pays for itself — **it lays out headlessly**,
which is why the layout can be asserted at seven screen shapes in a unit test
rather than looked at in a screenshot.

`ArchitectureTest.noScene2dAtAll` keeps the decision.

## 3. UiRect: two rectangles, not one

Every control carries a **visual** rectangle and a **hit** rectangle.

```
   visual   what the renderer paints
   hit      what the router tests, never smaller, often larger
```

`TouchTargets.apply` grows the hit box around the control's own centre until it
is at least `MIN_UI_UNITS` (44) on both axes, and leaves the visual box alone. A
small icon must stay a small icon while being comfortably pressable; if
enlargement changed the drawing, every touch-target fix would be a visual
regression. `UiLayoutTest.artworkIsNotEnlarged` states it directly, and the debug
overlay draws both boxes so a discrepancy is visible rather than theoretical.

A control's `State` is `NORMAL`, `DISABLED` or `SELECTED`, and `hits()` returns
false unless the control is `pressable()`. **`DISABLED` is used sparingly**: in
Python a click on an unaffordable shop card is still consumed and answered with
"Not enough gold!", so those cards stay pressable and the *shop* refuses. A
button that stops claiming presses once it is unusable becomes a hole in the
interface that the world can be grabbed through — `UiInputTest.spentHornStillConsumes`
is that bug written down. How a control *looks* is a render question, answered by
asking the owning subsystem (`shop.view(id).buyable()`, `tree.canPurchase(id)`),
never by a flag cached on the rectangle.

## 4. Navigation: the whole graph, in one class

```
   MENU ──choose mode──> SHOP ──start/resume──> PLAYING
    │  ^                  │  ^                    │  ^
    │  │                  │  │                    │  │
    │  │                TALENTS                 PAUSED
    │  │                                          │
    └──┴────────────── GAMEOVER <─────────────────┘
    │
    └──> SETTINGS
```

No screen sets a game state itself; `ArchitectureTest.screensRouteThroughNavigation`
enforces it. Consequences worth stating:

- **Settings is reachable only from the menu.** Python's mouse handler has no
  route to it from anywhere else, and neither has this.
- **Back from the Classic armoury does nothing.** There is no state to go back
  to that Python can reach — a route there would be a route to SETTINGS mid-run.
- **Back from the talent screen** returns to whichever screen opened it, with
  Python's SHOP fallback preserved (`Navigation.talentReturn`).
- **Back from the menu returns `EXIT_APP`**, which is a decision for the platform
  layer. `UiRoot.back()` returns the graph's answer rather than a boolean,
  because "unhandled" and "leave the app" are not the same thing.

### 4.1 Difficulty cannot change mid-run

Phase 9 captures the difficulty into `RunSession` when a run is created, and
Phase 10 was required not to open a gameplay-reachable route that would let it
change afterwards. It does not, and the guarantee is enforced rather than assumed:

- `Navigation.canChangePreferredDifficulty()` is true only in `MENU` and
  `SETTINGS`;
- `canOpenSettings()` is true only in `MENU`;
- `PAUSED` has exactly one control, RESUME — no settings door;
- the only route from a run to `MENU` is through `GAMEOVER`, which resets first;
- **no in-run screen has a difficulty control at all**, asserted directly by
  `NavigationTest.noInRunScreenHasADifficultyButton`, which walks every control
  of every in-run screen rather than checking the ones it remembers to.

Changing the setting stores a *preference* used by the next run
(`NavigationTest.preferenceAppliesToTheNextRun`); a run in progress keeps what it
started with.

## 5. Layout

Immediate: every screen re-lays out each frame from gameplay state and the
viewport, with no retained tree and no invalidation. It costs a few hundred float
operations per frame and removes an entire category of bug — a stale rectangle
after a rotation, an inset change, or a row appearing.

**Two viewports.** The world is a `FitViewport(1280, 720)` and never moves; the
UI is an `ExtendViewport`, so a taller phone gets *more interface*, not a stretched
one. `UiLayoutTest.insetsDoNotReachTheWorld` proves a cutout changes the UI safe
rectangle and leaves the world's dimensions untouched — gameplay coordinates are
identical on every device, which is what keeps the parity fixtures meaningful.

**The HUD is a measured row stack**, as in Python: rows are appended only when
they apply, then the panel is sized to fit them. A row that does not apply — the
crowd multiplier below its threshold, the Endless clock in a Classic run — is
simply not added, and the rows below move up. The renderer walks `hud.rows()`
rather than a fixed sequence, so it cannot disagree with the measurement.

**Safe areas.** `SafeAreaInsets` arrives in screen pixels through
`PlatformServices` — the same seam Android reports a real cutout on — and
`SafeArea` converts it to UI units with the viewport's own ratios. Absurd input
degrades to the full rectangle rather than to a negative one. Every visible
control must lie inside it, at every screen shape, checked by
`UiLayoutTest.controlsRespectTheSafeArea`.

**Responsiveness** is reflow, not scale: the shop picks its column count from the
available width, the talent screen scrolls when a branch is deeper than the panel
is tall. Tested at 16:9, 18:9, 19.5:9, 20:9 and portrait.

## 6. Input

One path. `DesktopInput` and `TouchInput` both feed `GameInput`; below that there
is one router and one `UiRoot`, so a mouse click and a finger tap are the same
event by the time anything decides what to do with it
(`UiInputTest.mouseAndTouchAreOnePath`).

Every screen except the HUD is **modal**: a press anywhere is claimed, including
empty space, so a menu cannot be clicked through. The HUD is not modal, which is
precisely how grabbing works — a press that misses every control falls through to
the world.

Priority within a screen is registration order, matching the router's own
first-claim-wins rule. `TouchTargets.overlaps` reports any two controls whose hit
boxes intersect, and `UiLayoutTest.noOverlappingTargets` fails on any, so
"registration order decides" is a tie-break that never has to be used.

**Skills are a two-stage cast.** Tapping a targeted skill *arms* it; the next
world press casts it — and that press is claimed by the HUD too, so casting never
also grabs. Untargeted skills fire on the tap. Neither stage exists in the source,
which casts at the mouse position; a touch device has no hover, and this is the
smallest addition that makes targeting possible with a finger.

**Android Back** goes through `UiRoot.back()` into the graph. There is no
per-screen back handler.

## 7. Time and randomness: the interface has neither

A screen that read a wall clock would draw a cooldown ring that finishes at a
different moment from the cooldown; a screen that drew from the run's `Rng` would
make the simulation depend on how many buttons the player pressed. So:

- everything about time is asked of the subsystem that owns it —
  `SkillPanel.cooldownRemaining`, `RunSession.playTime` — which is frozen exactly
  when the world is;
- readiness is `isReady(id)`, never a rounded remaining-seconds comparison;
- `ArchitectureTest.uiHasNoRngAndNoClock` bans `System.currentTimeMillis`,
  `Gdx.graphics.getDeltaTime`, `TimeUtils` and every constructor of a generator
  from the `ui` package.

`UiInputTest.uiDoesNotTouchTheRng` compares the generator's exact stream position
across twenty button presses, then blows the horn to show the measurement can see
a draw at all.

**The Endless armoury freeze is not the interface's doing.** The state is `SHOP`,
the state machine does not advance the world in it, and that is the entire
mechanism — no screen holds a timer or suppresses a step.
`UiIntegrationTest.realtimeShopFreezesThroughTheUi` opens the shop the way a
player does, runs 120 frames, and asserts play time, enemy positions, the tier
ladder and the spawn timer are all exactly unchanged.

## 8. Rendering

`UiRenderer` (in `render`, not `ui`) paints the chrome. The `ui` package holds no
graphics import at all — `ArchitectureTest.uiHoldsNoGraphics` — which is what
makes the layout testable headlessly.

The font is libGDX's built-in `BitmapFont`, the same choice `FoundationRenderer`
already made: no asset, no FreeType dependency, no packing step, so Phase 10 can
be verified end to end before Phase 11 introduces the game's real typeface. Its
metrics are published as a `TextLayout.Measurer`, so the layout is measured with
the font that will actually draw it.

### The debug overlay

`--ui-debug` draws safe area, both bounds of every control, the live pointer and
who owns it, and marks in red anything below the touch minimum, outside the safe
area or overlapping a neighbour. Every layout bug in this phase is a discrepancy
between two rectangles that cannot normally both be seen; this makes them one
picture.

### Device frames

The desktop launcher takes `--device desktop|phone169|phone189|phone195|phone209|portrait|tablet`,
which sets an aspect ratio *and* simulated cutouts, and `--insets L,R,T,B` for
anything else. The insets go through `PlatformServices.setSafeAreaInsets` — the
production seam — so what is being checked on a desk is the real path.

`--screen NAME` opens a screen through the real navigation graph before the
screenshot: a picture of a state the game cannot route to would be a picture of
something no player can reach.

`tools/ui/screenshots.sh` renders the matrix: **8 screens x 3 device frames = 24
images, plus 1 more of the shop with the debug overlay on — 25 files.** (The
overlay capture is the odd one out, and the reason the file count is not a clean
multiple.)

It asserts nothing: a screenshot
suite that fails on a one-pixel difference gets switched off within a month. It
proves every screen renders at every shape without throwing and leaves images a
person can flip through. **The layout assertions live in `UiLayoutTest`**, which
is the gate.

## 9. Deliberate differences from the source

Each of these exists because a touchscreen cannot do what a mouse does. Every one
sends the identical gameplay command; none of them reaches a subsystem.

| Source | Here | Why |
|---|---|---|
| hover for talent details, click to buy | tap to inspect, tap again to buy | no hover on a touchscreen |
| `P`/`ESC` to leave pause | a RESUME button (and Back) | Python's pause handles no clicks at all; a phone has no ESC |
| skills cast at the mouse position | arm, then tap the target | no cursor to cast at |
| fixed 1280×720 window | safe areas, reflow, 44-unit touch targets | phones have cutouts, gesture bars and thumbs |

Everything else is the source's behaviour, including the ones that look like bugs:
the Deep Foundations tooltip still claims +50% castle health and still changes
nothing (`UiIntegrationTest.deepFoundationsStaysInert`), and the Endless horn
stays spent for the whole run.

## 10. Tests

| File | What it holds down |
|---|---|
| `NavigationTest` | the graph, and the difficulty guarantee |
| `UiLayoutTest` | safe areas, touch targets, overlap, seven screen shapes |
| `UiInputTest` | consumption and ownership through the real router |
| `UiIntegrationTest` | buttons reaching real gameplay, and the fresh-run reset |
| `UiTextTest` | localisation completeness and long-string layout |
| `ArchitectureTest` | no gameplay→ui, no Scene2D, no clock, no Rng, no routing outside `Navigation` |

See also [`INPUT.md`](INPUT.md), [`TEXT_LOCALIZATION.md`](TEXT_LOCALIZATION.md).

## Phase 11 — what changed under the interface

Three things, and none of them touches the layout or the input model.

**Three widgets now shake.** `Game.draw` paints the skill bar, the horn and the
grab cursor into the world surface and only the stat panel and the menus onto the
unshaken screen — so in the source those three move with a castle hit. That is
reproduced: `UiRenderer.setWorldShake` applies the world's offset as a projection
translate around exactly those widgets. **Only the drawing moves**; hit rectangles
are the ones the layout produced, so a shaking button is still pressed where it
was laid out, as in the source. Boss health bars stay put — a jittering health
bar is unreadable exactly when it matters.

**The world is drawn underneath.** `FoundationRenderer` is gone; `WorldRenderer`
paints the game and the UI renderer draws over it, unchanged.

**The debug overlay gained the rendering numbers**: fps, frame time, interpolation
alpha, quality, drawn enemies and projectiles, live particles against the cap,
active skin and atlas, missing-art count. A handful of counters, not a profiler.

`ArchitectureTest.uiRendererDrawsNoEntities` keeps the split: the UI renderer may
not import a painter or an `Enemy`, because boss bars are unshaken UI and boss
bodies are shaken world, and the two must not end up in one coordinate space.

## Phase 11.5 — corrections from the visual audit

Three fixes, all from putting the Java and Python renders side by side:

- **The Endless SHOP button no longer shakes.** It is row 7 of the stat panel and
  `draw_hud` paints the panel onto the unshaken screen. It had drifted inside the
  shaken block. `ShakeInputTest.theShakenSetIsExactlyTheSourceSet` now reads
  `UiRenderer`'s own source for what is between `beginShaken()` and
  `endShaken()`, rather than trusting a list kept beside the code.
- **The Challenge Horn moved to the source's position** — a 58x60 brass disc on
  the castle wall at `HORN_RECT = (170, 452)`, with the word underneath, instead
  of a 132x46 labelled rectangle in the top-right corner. The old one could not
  fit its own label (it rendered as "CHALLE...") and collided with the field
  readout. Offset into the safe rectangle so a cutout cannot eat it.
- **Boss bars moved to the bottom**, at most two, 620 units wide alone or 400
  each in a pair, red rather than magenta, with the boss's name above the bar and
  the hit points on it — all as `draw_hud` does it.

Added, having been absent since Phase 10: the top-right field readout (enemies
left, kills, throw damage, wind, storm), the announcement banners, and the
one-line control hint along the bottom.

Shake usability is now asserted rather than assumed: the ceiling is 14 units, the
smallest shaken widget is 58x60, and the test fails if a future widget is ever
small enough for the offset to walk it off its own touch box.

## Phase 11.5 polish — HUD, boss bars and instructions

**HUD panel geometry is now the source's own constants.** Phase 10 had guessed
300/16/12/8; the real values are `HUD_W = 372`, `HUD_X, HUD_Y = 14, 12`,
`HUD_PAD = 14`, `HUD_ROW_GAP = 6`. A 372-wide panel is why the source fits the
wall name and the gold on one row without either being cramped.

**Boss bars follow `draw_hud` exactly** — 620 wide for a lone boss, 400 each for
a pair, 24 apart, centred as a group, bottom edge 30 above the floor, red
`(208, 62, 60)`, with the name at `HEIGHT - 78` above the bar and the hit points
at `HEIGHT - 48` inside it. At most two, as `current_bosses()[:2]` gives.
`BossBarLayoutTest` holds all of it, including that a third boss gets no bar and
that a death rearranges the survivor to the full width.

**Two instruction sets, deliberately.** The source's line reads *"LMB a mob to
fling it - LMB-drag a Siege Ram to rip its armour - P to pause"*. A phone has
neither a left mouse button nor a P key, so a touch device gets the same three
instructions in its own vocabulary — *"Drag a mob to fling it - drag a Siege Ram
to rip its armour - Back to pause"*. **Nothing is dropped**: pausing is still
mentioned, because Back is how it is done. The choice follows the platform, not
the build, and either set can be forced with
`UiRenderer.setTouchInstructions(boolean)`.

Typography across the whole interface is now calibrated against pygame's — see
[`TEXT_LOCALIZATION.md`](TEXT_LOCALIZATION.md).
