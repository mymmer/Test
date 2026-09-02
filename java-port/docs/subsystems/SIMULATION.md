# Subsystem contract — Simulation

## Purpose

Turn a variable stream of rendered frames into a deterministic sequence of
fixed 1/60 s gameplay steps, and own the world those steps advance.

Every gameplay clock in the port is derived from a **step count**, never from a
frame delta and never from wall-clock time. That is the whole point of the
subsystem: two machines that render at 30 and 240 fps must run the identical
number of steps for the same amount of elapsed time, and a bug report that says
"wave 14, 92 seconds in" must mean the same thing on both.

## Owns

* The accumulator, the step budget and the frame-delta clamp (`Simulation`).
* The simulation clock and the gameplay clock (`GameWorld`).
* Game state and mode, and therefore the freeze rule (`GameState`, `GameMode`).
* The entity collection, its insertion order, and the mark-dead/sweep cycle
  (`EntityList`, `Entity`).
* The per-run RNG seed (`GameWorld.beginRun`).
* The trace seam (`SimulationTrace`).

## Does not own

* **Rendering.** `Simulation` produces an `alpha()`; what anyone does with it is
  the renderer's business. The world holds no textures, colours or viewports.
* **Input.** The world never reads a pointer. `CastleDefenseGame` routes input
  once per step and gameplay handlers receive the result.
* **Gameplay rules.** Phase 4 has no enemies, towers, waves, combat or economy.
  Those attach through `setStepListener` and, later, systems of their own.
* **Wall-clock time.** Nothing here calls `System.currentTimeMillis`,
  `TimeUtils`, or `Gdx.graphics.getDeltaTime` — the delta arrives as an argument
  to `advance`, from exactly one caller.
* **Threads.** Single-threaded by contract; there are no locks anywhere in it.

## Dependencies

`GameConfig` (for `SIMULATION_STEP`, `MAX_SIMULATION_STEPS_PER_FRAME`,
`MAX_FRAME_DELTA`), `Rng`, `SimulationTrace`, and libGDX `Array` inside
`EntityList`. No backend, no assets, no files.

## Public API

```java
// Simulation — the accumulator.  One caller: CastleDefenseGame.render().
int      advance(float frameDelta);   // runs 0..MAX_STEPS steps, returns how many
void     resetAccumulator();          // after a pause/resume; discards the partial step
void     reset();                     // accumulator + clock + counters
double   timeSeconds();               // stepCount * FIXED_DT — the only time that exists
static double secondsForSteps(long steps);   // canonical conversion, for other clocks
static long   stepsForSeconds(double s);
long     stepCount();
int      lastStepsRun();
float    alpha();                     // leftover fraction, for render interpolation only
float    accumulatorSeconds();
long     droppedStepEvents();         // frames where the budget was hit
long     clampedFrames();             // frames where the delta clamp bit
interface Stepper { void step(float dt); }

// GameWorld — what a step advances.
void        step(float dt);                     // called only by Simulation
void        setStepListener(StepListener l);    // where gameplay systems attach
double      simulationTime();                   // advances in every state
double      gameplayTime();                     // advances only while unfrozen
long        stepCount(); long gameplayStepCount();
GameState   state();  void setState(GameState);
GameMode    mode();   void setMode(GameMode);
boolean     isFrozen();
EntityList<Entity> entities();
void        spawn(Entity e);   void kill(Entity e);
void        beginRun(GameMode m, long seed);    // deterministic
long        beginRun(GameMode m);               // fresh seed, returned for the log
long        runSeed();
Rng         rng();
SimulationTrace trace();  void setTrace(SimulationTrace t);
String      describe();                         // one line, for the crash log

// EntityList — insertion order, snapshots, deferred removal.
void add(T);  int size();  T get(int);  int aliveCount();  boolean contains(T);
int  sweep();                       // compacts dead entries, order preserved
Snapshot beginSnapshot();           // AutoCloseable; a stable view for iteration
int openSnapshots(); int peakOpenSnapshots();
Array<T> unsafeItems();             // escape hatch, tests and bulk render only

// Entity
long uid();  boolean isAlive();  void markDead();   // revive() is protected
```

## Important invariants

1. **One step, two representations.** `Simulation.FIXED_DT ==
   GameConfig.SIMULATION_STEP == 1.0/60.0` (double, canonical) and
   `Simulation.DT == GameConfig.SIMULATION_STEP_F == (float) FIXED_DT` (gameplay).
   Nothing else defines a timestep; `step(dt)` always receives exactly `DT`.
2. **The canonical step is a `double`; the gameplay step is a `float`.**
   `FIXED_DT = 1.0/60.0` is used by the accumulator, the step comparison and the
   clock. `DT = (float) FIXED_DT` is what `step(dt)` receives, because positions,
   velocities and decay are float maths. The rule: **multiply by `DT`, never sum
   it.** `1f/60f` is 0.016666668…, not a sixtieth, so summing it drifts ~188 µs
   over an hour — `canonicalClockBeatsFloatAccumulation` demonstrates exactly
   that and pins the difference.
2b. **`timeSeconds()` is `stepCount * FIXED_DT`, computed fresh.** Not a running
   total, so it cannot drift: 60 steps are 1.0 s, 3600 are 60.0 s, 216 000 are
   3600.0 s, to double precision. Step ids in traces are the integer step count
   itself. `secondsForSteps` / `stepsForSeconds` exist so later long-lived clocks
   (Endless timetable, boss phases) convert the same way instead of summing their
   own float. This does **not** make gameplay integer ticks: physics stays float.
2c. **Exact assertions where the port controls the input, tolerance where the
   platform supplies it.** A step count is an integer we chose, so N steps must be
   exactly N/60 s and the tests assert that to 1e-12. A frame delta such as
   `1/144f` is an approximate float from a display, so "59 or 60 steps in a
   nominal second" is the honest assertion there. Loosening the first kind would
   hide a real drift bug; tightening the second would be asserting float noise.
3. **Bounded catch-up.** At most `MAX_STEPS` (5) steps per frame. Beyond that the
   remaining accumulated time is **dropped**, not banked — a spiral of death is
   worse than a hitch — and `droppedStepEvents()` counts it.
4. **Clamped, sanitised deltas.** A frame delta above `MAX_FRAME_DELTA` (0.25 s)
   is clamped; NaN, infinity and negatives are treated as zero. A backend that
   reports garbage cannot inject garbage into the world.
5. **Resume discards the partial step.** `pause()` may last hours. The fraction
   left in the accumulator before it is stale, so `resume()` zeroes it rather
   than replaying it.
6. **Only `PLAYING` advances the world.** `simulationTime()` advances in every
   state; `gameplayTime()`, entity updates and spawning advance only when
   `state().advancesWorld()`. This is what lets the Endless realtime shop freeze
   the horde mid-fight.
7. **Insertion order is semantic.** `EntityList` never swap-removes. `sweep()`
   compacts in place and preserves relative order, because the Python
   `separate_enemies` pass, the Cannon cluster scoring and shared tower
   targeting all depend on list position. Reordering them is a Phase 12 change
   behind parity proofs, not a free optimisation.
8. **Removal is deferred.** Kill marks; the sweep at the end of the step
   removes. Nothing mutates the collection mid-iteration.
9. **Snapshots are pooled and nestable.** `beginSnapshot()` hands out a reusable
   buffer; a snapshot keeps entities killed during the iteration and excludes
   entities spawned during it. Closing returns the buffer;
   `peakOpenSnapshots()` exists so a leak shows up in a test rather than as
   slow growth.
10. **Entity uids are monotonic and never reused.** They are the stable identity
    used by traces and logs — never an array index.
11. **Fixed-step is an intentional behavioural change.** The Python game steps a
    variable delta capped at 50 ms. Parity tests must therefore cover attack
    timers, projectile impacts, fall-damage and bounce thresholds, cooldown
    boundaries, spawn cadence, boss timers and wave-clear delays. Gameplay
    constants are **not** pre-adjusted to compensate; differences get measured
    first.

## Debug seeding and tracing

`GameWorld.beginRun(mode)` draws a fresh seed from `Rng` and returns it, and
`describe()` includes it, so a crash log names the seed that produced the crash.
`beginRun(mode, seed)` replays it. `Rng.debugSeedOr(fallback)` reads the
`castledefense.seed` system property, so a run is reproducible from the command
line without a code change.

`SimulationTrace` is an **observation seam, not control flow**: implementations
may only record. The default is `NoOpSimulationTrace.INSTANCE` (a singleton, so
tracing costs one already-predicted `isEnabled()` branch when off);
`RecordingSimulationTrace` keeps a bounded ring for tests. There is deliberately
no EventBus, no replay log and no event sourcing — gameplay calls gameplay
directly. Phase 5 added `PROJECTILE_SPAWN`, `TOWER_FIRE`, `TOWER_DISABLED`,
`TOWER_REBUILT`, `CASTLE_DAMAGE` and `BARRICADE_DAMAGE`; a test runs the same
seeded scenario with tracing on and off and asserts the outcomes are identical.

## Relevant source files

```
core/src/main/java/com/mymmer/castledefense/game/Simulation.java
core/src/main/java/com/mymmer/castledefense/game/GameWorld.java
core/src/main/java/com/mymmer/castledefense/game/GameState.java
core/src/main/java/com/mymmer/castledefense/game/GameMode.java
core/src/main/java/com/mymmer/castledefense/entity/Entity.java
core/src/main/java/com/mymmer/castledefense/entity/EntityList.java
core/src/main/java/com/mymmer/castledefense/util/Collisions.java
core/src/main/java/com/mymmer/castledefense/debug/*.java
core/src/main/java/com/mymmer/castledefense/CastleDefenseGame.java   (the only caller of advance)
```

## Relevant tests

```
core/src/test/java/com/mymmer/castledefense/game/SimulationTest.java   (12)
core/src/test/java/com/mymmer/castledefense/game/GameWorldTest.java    (9)
core/src/test/java/com/mymmer/castledefense/entity/EntityListTest.java (10)
core/src/test/java/com/mymmer/castledefense/util/CollisionsTest.java   (8)
```
