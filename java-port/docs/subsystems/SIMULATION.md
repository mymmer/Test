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
interface Stepper { void step(double dt); }

// GameWorld — what a step advances.
void        step(double dt);                    // called only by Simulation
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
boolean remove(T);                  // removes a LIVE entity, order preserved
Snapshot beginSnapshot();           // AutoCloseable; a stable view for iteration
int openSnapshots(); int peakOpenSnapshots();
Array<T> unsafeItems();             // escape hatch, tests and bulk render only

// Entity
long uid();  boolean isAlive();  void markDead();   // revive() is protected
```

## Gameplay time is double

**The one cross-cutting rule of this subsystem:**

```
time domain    -> double     durations, deadlines, cooldowns, intervals,
                             elapsed and remaining time, schedules
spatial domain -> float      x/y, velocities, dimensions, angles,
                             collision geometry, rendering-facing state
```

`step(double dt)` receives the canonical step. A timer subtracts it as it comes.
A system that integrates space narrows it **once, itself**, at the top of the
method:

```java
public void update(double dt) {
    float fdt = (float) dt;         // spatial only
    cooldown -= dt;                 // time domain: never fdt
    x += vx * fdt;                  // spatial domain
}
```

**Why.** `1f/60f` is 0.016666668 — fractionally *longer* than a true sixtieth —
so a float timer subtracting it drifts, and at a comparison boundary that becomes
a whole extra event. The shipped Hard Dragon breath fired **16** fireballs where
the Python source fires 15. Three of the six fixtured breath configurations
diverged, in *both* directions, which is why no constant could have compensated.

**What this rule is not:**

* **Not integer ticks.** Durations stay seconds — many are random or
  configurable fractions — they are simply *double* seconds. The integer
  `stepCount` remains the canonical clock and trace id.
* **Not epsilons.** Source comparison operators are preserved exactly: `<= 0`
  stays `<= 0`. No `EPSILON` was added to make a parity test pass.
* **Not a global conversion.** Rates that are not times stay float: `regen` is
  HP per second, `breathPower` is a damage fraction, `shove` is a velocity.
  So does drawing state: `hurtFlash`, `recoil`, `aura`, `trapGlow`, `orb`,
  `smash`, `fuse`, `anim`, `bob`, `spin`.
* **Not two clocks.** See invariant 1.

**What it does not claim.** A double countdown does not land exactly on the
mathematical step — neither `0.15` nor `1.0/60.0` is representable, so nine
sequential subtractions leave 0.15 s a hair above zero and it expires on the
tenth. **Python does the same.** The guarantee is parity with the source plus a
deterministic, drift-free boundary, not an idealised one.

**Reading a duration from data:** `Json5.seconds` / `Json5.optSeconds`, which
return `double`. **Drawing a random duration:** `Rng.uniformSeconds`, which draws
one `nextLong` exactly as `Rng.uniform` does, so the gameplay stream advances
identically and a seeded run stays reproducible.

Guarded by `TimeDomainTest`.

## Important invariants

1. **One step, two precisions — not two clocks.** `Simulation.FIXED_DT ==
   GameConfig.SIMULATION_STEP == 1.0/60.0` (double, canonical) and
   `Simulation.PHYSICS_DT == GameConfig.SIMULATION_STEP_F == (float) FIXED_DT`
   (spatial). They name the *same* step and nothing advances them independently.
   Nothing else defines a timestep; `step(dt)` always receives exactly
   `FIXED_DT`.
2. **The step reaches gameplay as a `double`.** The accumulator, the step
   comparison, the clock and every gameplay timer use `FIXED_DT`.
   `PHYSICS_DT` exists for float maths, and **no production class reads it** —
   each narrows the `dt` it was handed, where the narrowing is visible.
   `TimeDomainTest.productionNeverReadsTheFloatStepConstant` fails the build if
   one starts to. The rule for the float step remains: **multiply by it, never
   sum it.** Summing `1f/60f` drifts ~188 µs over an hour —
   `canonicalClockBeatsFloatAccumulation` pins the difference.
2b. **`timeSeconds()` is `stepCount * FIXED_DT`, computed fresh.** Not a running
   total, so it cannot drift: 60 steps are 1.0 s, 3600 are 60.0 s, 216 000 are
   3600.0 s, to double precision. Step ids in traces are the integer step count
   itself. `secondsForSteps` / `stepsForSeconds` exist so later long-lived clocks
   (Endless timetable, boss phases) convert the same way instead of summing their
   own float. This does **not** make gameplay integer ticks: durations stay
   double seconds and spatial integration stays float.
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
7. **Insertion order is semantic.** `EntityList` never swap-removes. Its backing
   `Array` is **ordered**, so even a direct `removeValue` preserves order —
   libGDX swap-removes on an unordered array, which would silently reorder the
   list and change which mob the crowd pass, the Cannon's cluster scoring and
   shared targeting all pick. `sweep()` compacts in place. Reordering is a Phase
   12 change behind parity proofs, not a free optimisation.
7b. **`remove(T)` takes a *live* entity out without killing it.** Rare and
   deliberate: the Outpost imprisoning a Necromancer, who then lives on as the
   prisoner. It can happen mid-iteration, which is why the enemy loop iterates a
   snapshot — proven by `EnemyBehaviourTest.trapDuringSnapshotDoesNotSkip`.
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
Phase 6 added `ENTITY_STATE_CHANGED`, `ENEMY_GRABBED`, `ENEMY_RELEASED`,
`ARMOUR_STRIPPED`, `SLAM`, `FALL_DAMAGE` and `GOLD_PAYOUT`; Phase 7 added
`BOSS_SPAWN`, `BOSS_STATE_CHANGE`, `BOSS_ATTACK`, `BOSS_DISRUPTION`,
`BOSS_DEATH`, `REGALIA_DETACH`, `REGALIA_RECOVER`, `DROPPED_ITEM_CREATED` and
`DROPPED_ITEM_RECOVERED`. Nothing traces a position every frame;
`Enemy.describe()`, `Boss.describe()` and `BossRegistry.describe()` build
bug-report lines on demand.

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
