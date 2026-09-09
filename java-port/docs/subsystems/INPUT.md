# Subsystem contract — Input

## Purpose

Convert platform pointer events into one logical, world-space input model that
gameplay can read, and arbitrate who owns each pointer.

There is exactly **one** input model. A desktop mouse is pointer 0; a finger is
pointer 0..19. Gameplay never learns which it was, so the desktop build is a
faithful rehearsal of the phone build rather than a separate code path.

## Owns

* The single conversion from screen pixels to world/UI coordinates
  (`GameInput` — the only class in `core` that ever sees a pixel).
* Per-pointer state, including press/release edges and cancellation
  (`Pointer`).
* World-interaction ownership: at most one pointer may be manipulating the world
  at a time.
* Sticky UI consumption: a pointer a widget claimed belongs to that widget for
  its whole lifetime.
* Throw velocity sampling (`TouchVelocityTracker`).
* Dispatch order — UI first, then the world (`InputRouter`).

## Does not own

* **What a press means.** Grabbing, throwing, armour stripping and tower
  overcharge live behind `WorldInteractionHandler`, implemented by
  `interaction.CursorInteraction` in Phase 6. Routing did not change when it
  arrived — see `docs/subsystems/INTERACTIONS_PHYSICS.md`.
* **Widget layout or hit shapes.** A `UiConsumer` decides for itself whether a
  press is inside it.
* **Wall-clock time.** The tracker is clocked from simulation time, set by
  `CastleDefenseGame` before each step.
* **The viewports.** It reads a `ViewportSet` it does not create.
* **Keyboard bindings.** Key events are accepted and currently unhandled;
  bindings are UI-phase work.

## Dependencies

`ViewportSet` (unprojection), `GameConfig` (`THROW_SAMPLE_WINDOW`,
`THROW_SPEED_CLAMP`), libGDX `InputProcessor`. Nothing else.

## The pipeline, in order

```
backend event (screen px)
  → GameInput.touchDown/Dragged/Up/Cancelled
  → ViewportSet unprojection (world coords, and separately UI coords)
  → Pointer state + TouchVelocityTracker sample
  → InputRouter.route()          once per simulation step
      → UiConsumer.onPress in priority order   (first to return true claims it)
      → else WorldInteractionHandler.onWorldPress (may acquire the interaction)
  → GameInput.endStep()          clears the one-step edges
```

Raw pixels never leave `GameInput`. No gameplay code may call
`Gdx.input.getX()`.

## Public API

```java
// GameInput  (DesktopInput and TouchInput are the two platform flavours)
void    setClock(float simulationTimeSeconds);
Pointer pointer(int id);                       // never null, id 0..MAX_POINTERS-1
TouchVelocityTracker velocityTracker(int id);
void    releaseVelocity(int id, float[] out);  // fills out[0]=vx, out[1]=vy
boolean acquireInteraction(int pointerId);     // false if another pointer holds it
boolean releaseInteraction(int pointerId);     // only the owner may release
boolean ownsInteraction(int pointerId);
int     interactionOwner();  boolean hasInteraction();
void    consume(int pointerId, Object consumer);
void    endStep();                             // clears justPressed/justReleased
void    cancelPointer(int id);  void cancelAll();
void    reset();

// DesktopInput
static final int MOUSE_POINTER = 0;
void syncMouseButton(boolean physicallyDown);  // recovers from a lost button-up
void onFocusLost();

// TouchInput
void onAppPaused();                            // → cancelAll()

// Pointer  (read-only to gameplay; mutation is package-private)
int id(); boolean isDown(); boolean justPressed(); boolean justReleased();
boolean wasCancelled();
float worldX(); float worldY(); float deltaWorldX(); float deltaWorldY();
float uiX(); float uiY(); float downWorldX(); float downWorldY();
Object consumedBy(); boolean isConsumedByUi();

// InputRouter
InputRouter addConsumer(UiConsumer c);   // priority = registration order
void removeConsumer(UiConsumer c);  void clearConsumers();
void setWorldHandler(WorldInteractionHandler h);
void route();                            // once per simulation step

// UiConsumer                    // WorldInteractionHandler
boolean onPress(Pointer);        // boolean onWorldPress(Pointer);
void    onDrag(Pointer);         // void onWorldDrag(Pointer);
void    onRelease(Pointer);      // void onWorldRelease(Pointer);
String  consumerName();          // void onWorldCancel(Pointer);

// TouchVelocityTracker
void  sample(float timeSeconds, float worldX, float worldY);
void  velocity(float[] out);     // out[0]=vx, out[1]=vy, clamped
void  reset();  int sampleCount();  float historySpan();
static final float LOOKBACK_SECONDS = 0.09f;   // = GameConfig.THROW_SAMPLE_WINDOW
static final float SPEED_CLAMP     = 2600f;    // = GameConfig.THROW_SPEED_CLAMP
static final float HISTORY_SECONDS = 0.20f;
```

## Important invariants

1. **Pixels exist in exactly one place.** `GameInput`'s four touch callbacks.
   Everything downstream is world or UI units.
2. **One world interaction at a time.** `acquireInteraction` fails if another
   pointer already holds it — a second finger cannot steal a grab. Only the
   owner may release it.
3. **Ownership outlives the release event.** `touchUp` and `cancelPointer`
   deliberately do **not** clear ownership; `InputRouter` clears it *after*
   dispatching. Clearing early means the handler never learns the gesture ended,
   which in the real game is a grabbed mob that is never thrown. (This was a
   genuine bug, caught by `InputRouterTest`.)
4. **Cancellation is not a release.** `onWorldCancel` is a distinct callback: a
   released grab throws with the tracked velocity, a cancelled one drops.
5. **Consumption is sticky and per-pointer.** A `UiConsumer` that claims a
   pointer keeps it until release, even if the finger drags off the widget, and
   that pointer is never offered to the world.
6. **UI always gets first refusal**, in registration order.
7. **Pause cancels everything.** Android delivers no touch-up when the app
   backgrounds, so `CastleDefenseGame.pause()` calls `cancelAll()`. A finger
   that is no longer on the glass must not still be holding a mob on resume.
8. **Velocity history is time-based, not count-based.** Python keeps
   `deque(maxlen=12)`; twelve samples at 240 Hz spans 50 ms, not the intended
   90 ms, so the port keeps a **0.09 s lookback** over a 0.20 s ring of
   timestamped world-space samples. `TouchVelocityTrackerTest` asserts the same
   flick yields the same velocity within 1 % at 30, 60, 120 and 240 Hz.
9. **The tracker is clocked by simulation time.** A frame-rate change must not
   change how hard a mob is thrown.
10. **Edges last exactly one step.** `justPressed`/`justReleased` are cleared by
    `endStep()`, which runs once per simulation step — so a press is seen by
    exactly one step whatever the display rate is.
11. **Allocation-free.** The tracker is a ring of primitive triples;
    `velocity(float[] out)` writes into a caller-owned array. No `Vector2`
    escapes into a per-frame path.

## Relevant source files

```
core/src/main/java/com/mymmer/castledefense/input/GameInput.java
core/src/main/java/com/mymmer/castledefense/input/DesktopInput.java
core/src/main/java/com/mymmer/castledefense/input/TouchInput.java
core/src/main/java/com/mymmer/castledefense/input/Pointer.java
core/src/main/java/com/mymmer/castledefense/input/InputRouter.java
core/src/main/java/com/mymmer/castledefense/input/UiConsumer.java
core/src/main/java/com/mymmer/castledefense/input/WorldInteractionHandler.java
core/src/main/java/com/mymmer/castledefense/input/TouchVelocityTracker.java
core/src/main/java/com/mymmer/castledefense/render/ViewportSet.java   (unprojection)
```

## Relevant tests

```
core/src/test/java/com/mymmer/castledefense/input/InputRouterTest.java          (13)
core/src/test/java/com/mymmer/castledefense/input/TouchVelocityTrackerTest.java (8)
core/src/test/java/com/mymmer/castledefense/render/ViewportSetTest.java
```

## Phase 10 — the interface on top of this router

`UiRoot` is a `UiConsumer` like any other, registered **before** the world
handler, so nothing about the ownership model below changes: it claims a press or
it does not, and if it does, that pointer belongs to it for the whole gesture.

What Phase 10 adds is proof that the model holds with a real interface on it,
driven through `GameInput.touchDown` with screen pixels rather than around it
(`UiInputTest`):

- a press on a HUD control never reaches the world — including the horn with an
  enemy standing behind it, and including a *spent* horn, because a button that
  stops claiming presses once it is unusable becomes a hole to grab through;
- a press that misses every HUD control does reach the world, which is how
  grabbing works at all — the HUD is the only non-modal screen;
- a drag that started on a button stays with the button even when it travels
  across the field;
- a modal screen opening under a live finger does **not** steal the gesture,
  because ownership was decided at press time;
- a cancel arrives as a cancel, not a release;
- a second finger on the interface leaves the first one's world interaction
  intact.

**Skills are a two-stage cast**, which is the only new interaction shape: tapping
a targeted skill arms it, and the following world press casts it. That press is
claimed by the HUD as well, so casting never also grabs. The source casts at the
mouse position; a touch device has no cursor.

**Android Back** enters through `UiRoot.back()`, which returns the navigation
graph's own `BackResult` rather than a boolean — `EXIT_APP` is a decision for the
platform layer, and "unhandled" is not the same thing as "leave the app".

Desktop and Android share one path: `DesktopInput` and `TouchInput` both feed
`GameInput`, and below that there is one router and one `UiRoot`. See
[`UI.md`](UI.md).

## Phase 11.5 — the coordinate contract

### What Python actually does

Verified by reading the source before anything was changed:

- `Game.draw` renders the world into `self.scene`, then
  `self.screen.blit(s, (ox, oy))` where `ox, oy = random.uniform(-shake, shake)`
  is drawn **inside `draw`** and never stored;
- input is `self.mouse_pos = ev.pos` — the raw event position — and the main loop
  re-reads `pygame.mouse.get_pos()` once a frame;
- `mouse_hist`, which `mouse_velocity` samples ~90 ms back for a throw, records
  that same screen position.

So **shake is visual only, and input does not compensate for it.** While the
screen is shaking the picture is offset by up to ±14 px from where clicks land, a
stationary mouse has zero velocity, and `HORN_RECT` and `skill.rect` are fixed
rectangles hit-tested unshaken while being drawn into the shaken surface.

### The Java contract

```
   physical screen pixels          (GameInput.touchDown/Dragged/Up)
        │  viewport unproject, against the UNSHAKEN camera
        ├──────────────► UI viewport units      → SafeArea → UiRect hit bounds
        └──────────────► world viewport units   → WorldGeometry.toDrawY
                                                → gameplay world coordinates
```

Four rules hold it together:

1. **One conversion path.** `DesktopInput` and `TouchInput` both feed
   `GameInput`, which unprojects once per pointer into both spaces. There is no
   second path for mouse, and a click and a tap are the same event by the time
   anything decides what to do with it.
2. **Shake is not in it.** `WorldShake` offsets the world camera for the world
   pass and restores it before the frame returns, so every unprojection in the
   next frame runs against an unshaken camera. Gameplay positions, hitboxes,
   physics and the stored pointer velocities are never touched.
3. **The simulation keeps Pygame's downward y.** 771 parity fixtures and every
   ported constant depend on it. `WorldGeometry` converts at the single boundary
   where the renderer reads a position — see [`RENDERING.md`](RENDERING.md) §2.
4. **Safe-area insets reach the UI only.** A cutout shrinks the UI rectangle and
   never moves what a screen pixel means in the world.

### The invariants, and where they are held

```
   camera shake alone
   → no gameplay movement
   → no artificial throw velocity
   → no unintended grab / strip / smack
```

`ShakeInputTest.stationaryFingerGainsNothingFromShake` presses through the real
`GameInput` → `InputRouter`, holds the finger perfectly still for 200 frames of
maximum shake, and asserts the pointer's world position is unchanged to 1e-3 and
the released throw velocity is zero.

```
   same intentional virtual-world gesture
   → same gameplay result, at any render frequency
```

`throwIsFrameRateIndependent` makes the identical flick three times — once with
no shake, once shaking at one render per input step, once shaking at three — and
asserts all three produce the same throw velocity.

**No camera offset is ever injected into the drag tracker.** That was considered
and rejected: it would give a stationary finger velocity from the camera, which
is exactly the artefact the invariant forbids. The policy is simpler — the
tracker only ever sees unshaken positions, so there is nothing to compensate for.

### The shaken widgets

The skill bar and the horn are drawn into the shaken world (the source paints
them into `self.scene`); the stat panel, the Endless SHOP button, the boss bars
and every modal screen are not. **Only the drawing moves** — hit rectangles are
the ones the layout produced, exactly as `HORN_RECT` is fixed in the source.

Usability at the ceiling: the gameplay maximum is 14 units and the smallest
shaken widget is 58×60, so a fully displaced button still overlaps its own touch
box by more than three quarters, and tapping what the player sees lands inside
it. `shakeCannotWalkAWidgetOffItsTouchBox` asserts both the margin and the
displaced-centre hit, so a future widget small enough to be a problem fails the
test rather than shipping.

`theShakenSetIsExactlyTheSourceSet` reads `UiRenderer`'s own source for what lies
between `beginShaken()` and `endShaken()` — a list kept beside the code is what
let the SHOP button drift into the shaken block unnoticed in the first place.

## Connected to the platform, and in which space

Two things this document assumed for ten phases and neither was true until
Phase 13.

**The processor is registered.** `CastleDefenseGame.create()` calls
`Gdx.input.setInputProcessor(input)` and catches `Keys.BACK`. Nothing called
either before, so libGDX had nowhere to deliver a touch and `GameInput.touchDown`
was never once invoked in a running build. The router tests call `GameInput`
directly, which is the right way to test a router and cannot see this;
`InputWiringTest` asserts the wiring itself.

**A touch is converted into gameplay space.** The viewport unprojects into draw
space, which is y-up; the simulation keeps Pygame's y-down world with the ground
at `GROUND_Y = 620`. `GameInput.toWorld` crosses that boundary with
`WorldGeometry.toGameplayY`, exactly as the painters cross it the other way.
Without it a finger on the ground arrived at gameplay y 125 -- its own mirror
image about the horizon -- and nothing was ever under the cursor.

`WorldPickingTest` asserts absolute positions against known landmarks, which is
the question no earlier test asked: `ShakeInputTest` drives this path but
compares the pointer against *itself* across camera shake, and a consistently
wrong number passes that perfectly.

## Ownership

One world interaction at a time. A second finger may not start a second one,
and releasing it does not end the first one's hold -- `InputRouter` acquires
ownership on press and releases it only after dispatching the release, so a
handler always learns that its gesture ended.

A **cancel** and a **release** are different events. `pause()` calls
`cancelAll()`, because Android delivers no touch-up when an app is backgrounded,
and a cancelled gesture drops what it held with **zero** velocity rather than
throwing it. `ThrowCancellationTest` asserts a released drag genuinely throws
before asserting the cancelled one does not.

## Acquisition is not collision

A press that finds nothing now says **why**. `CursorInteraction.PressOutcome`
separates `NO_TARGET` (nothing was near the point) from `MISSED` (something was
near and the box did not cover it) from the refusals — `REFUSED_COOLDOWN`,
`REFUSED_BUSY` — and `lastMissDistance()` reports how far the nearest candidate
was. That distinction is the whole diagnosis: on the phone a dense crowd grabbed
every time and an isolated mob missed by up to 23 world units, which is an
aiming error, not a broken path.

The reason is arithmetic. A Scout's body is 26x34 world units and its grab box
is `hit_rect.inflate(16, 16)` — 42x50 — which on a 3040x1440 panel is **24x29
dp** against Android's 48 dp minimum, and a fingertip's contact patch is 8-10 mm
against the box's 4.6 mm.

So the platform reports a **touch acquisition tolerance** (`18f` on Android,
`0f` on desktop, because a mouse points at a pixel) and `grabbableNear` is
consulted **last** — after every exact `grabCovers` test has failed. Nothing
exact is ever overridden by something merely close, and `grabCovers` itself is
untouched: collision, damage, splash and crowd separation stay what the parity
fixtures recorded. `GrabAcquisitionTest` asserts the gameplay box still refuses a
point that acquisition accepts.

**A parity bug found while reading this path.** `main.py:1835`
`enemy_under_mouse` keeps the candidate with the **smallest x** — prefer the
nearest threat — and this port returned whichever came first in the list, so a
press into a crowd could lift someone standing behind the mob under the finger.
`heavy_under_mouse` had the same defect. Both now match.
