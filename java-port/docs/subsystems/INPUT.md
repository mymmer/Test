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
