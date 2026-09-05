# PORTING_STATUS.md — Castle Defense: Python/Pygame → Java/LibGDX

Authoritative migration checklist. Source of truth is the Python game in this repository
at commit `a7142ab` (`sprites.py`, `castle.py`, `enemies.py`, `main.py`; 9,363 lines).
Analysis: [`docs/PORT_ANALYSIS.md`](docs/PORT_ANALYSIS.md).

Legend: `[ ]` not started · `[~]` in progress · `[x]` ported (compiles, believed correct)
· `[T]` ported **and tested** (JUnit and/or a smoke run proves the behaviour)

Nothing is complete merely because it compiles. A row may only reach `[T]` when a named
test exercises it.

**Phase status: Phases 1, 3, 4, 5, 6, 7, 8 and 9 complete, plus the pre-Phase-8
time-domain hardening. Phase 2 implemented and hardened but NOT fully tested —
the Android assembly gate is still open because Google's Maven is unreachable
from the build environment, and stays open until `./gradlew verifyAndroid`
succeeds on a machine with the SDK.**

Phase 7 adds the three bosses and their interactive disruptions. `Boss extends
Enemy`, so a boss walks, takes damage, dies and pays out through contracts that
were already tested; what it adds is immunity to ordinary grabbing, a
difficulty-scaled fire clock, and a disruption the player performs by hand.

Phase 8 added the run itself: `RunWorld` assembles the field, the fight and the
pacing, and two directors run the two modes. Phase 9 adds the player's half of it
— 38 talents, 11 shop items, 3 active skills and the full difficulty
integration. **The gameplay is now complete.** What is left is rendering:
Phases 10 and 11.

**A skin cannot change gameplay, and that is now enforced three ways:**
gameplay packages cannot import `assets` (`ArchitectureTest`);
`AttachmentPoint` has no `worldX`/`worldY` and is named `visual*`/`draw*`
throughout; and `SkinIndependenceTest` loads two skins that disagree about every
scale, offset and attachment and asserts every gameplay anchor, interaction area,
dropped-item origin, hit box and projectile origin is identical to the float.

Numeric parity with the Python source is proven for the isolated formulas by
fixtures generated from it — see `tools/parity/generate_fixtures.py`.

Subsystem contracts:
[`java-port/docs/subsystems/`](java-port/docs/subsystems/).

The Java port lives in [`java-port/`](java-port/) and is isolated from the Python
game, which remains the authoritative executable reference and is unmodified.

---

## Phase 1 — Analysis

- [x] Read `sprites.py` in full (639 lines)
- [x] Read `castle.py` in full (1,436 lines)
- [x] Read `enemies.py` in full (2,186 lines)
- [x] Read `main.py` in full (5,102 lines)
- [x] Dependency map (compile-time + runtime)
- [x] Feature inventory
- [x] Gameplay systems inventory
- [x] Performance hotspot inventory
- [x] Pygame → LibGDX mapping table
- [x] Proposed Java class structure
- [x] Fixed-timestep design
- [x] Input / touch / velocity design
- [x] Viewport + safe-area design
- [x] Data-driven configuration plan
- [x] Skin / atlas / attachment / animation architecture
- [x] Persistence + save-versioning plan
- [x] Test plan mapped from the Python self-test
- [x] Quirk list ("do not fix these")
- [x] Known-difference list
- [x] This checklist

## Phase 2 — Project foundation

- [T] Gradle multi-project (`core`, `lwjgl3`, `android`). `:android` inclusion has
      three outcomes and only the first is silent: no SDK configured → omitted
      with a notice; SDK configured → **always included, failures are loud**;
      configured but the path is missing → **the build fails immediately**. All
      three verified by running them (`whatBuilds`, and with a bogus
      `ANDROID_HOME`).
- [x] Toolchain pinned as one coherent, officially documented set:
      **AGP 8.7.3 → Gradle 8.9 → JDK 17 → compileSdk/targetSdk 35 → minSdk 21**,
      plus libGDX 1.14.2, JUnit 5.14.4, desugar_jdk_libs 2.1.5. All in
      `java-port/gradle/libs.versions.toml`; rationale in `java-port/README.md`.
      Gradle/libGDX/JUnit verified by building; **AGP and desugar pins are
      unverified** (Google Maven unreachable here).
- [x] Core-library desugaring enabled in `:android`, so minSdk 21 does not
      restrict which JDK APIs `core` may use. The remaining restriction is a
      *performance* rule about per-frame allocation, not an API-level one.
- [T] `CastleDefenseGame` application skeleton — `CastleDefenseGameHeadlessTest`
- [T] World viewport `FitViewport(1280, 720)` — `ViewportSetTest`: world stays
      1280x720 on 7 screen shapes; 20:9 gets 240px pillarboxes; 4:3 gets 96px
      letterboxes. Confirmed visually by a real 2400x1080 desktop frame.
- [T] Separate UI viewport — `ViewportSetTest`: 720 units of height kept, width
      extends to 1600 on 20:9, world unaffected
- [x] Android manifest (landscape, cutout mode `shortEdges`, immersive, no
      permissions), theme and launcher written and XML-validated
- [T] Lifecycle hooks (`create`/`resize`/`render`/`pause`/`resume`/`dispose`) —
      `CastleDefenseGameHeadlessTest`, including 0x0 resize rejection and a
      double `dispose()`
- [T] Desktop launcher runs — ran at 1280x720, 2400x1080 and 1024x768 under a
      virtual display, 60-120 frames each, clean `created -> paused -> disposed`
- [ ] **Android assemble succeeds — OPEN GATE, blocks Phase 2 completion.** The
      Android Gradle Plugin and SDK live on `dl.google.com`/`maven.google.com`,
      which this build environment blocks by policy (403 on CONNECT); Maven
      Central mirrors AGP only to 2.3.0. The module is written and its XML
      validated, but it has **never been compiled**.
      **To close:** `cd java-port && ./gradlew verifyAndroid` on a machine with
      the SDK. That task assembles the debug APK and prints its path; record the
      result here and only then may Phase 2 be called complete.
- [T] CI-runnable headless test source set — `gradle :core:test`, 9 tests, no
      window and no GPU (a `GL20` stub makes viewport layout assertable)
- [T] `verifyAndroid` task — fails with an actionable message when no SDK is
      configured (verified), assembles and reports the APK when one is

## Phase 3 — Core infrastructure

- [x] `GameConfig` — the `sprites.py` constant block transcribed verbatim (world
      geometry, physics, regalia, bounce, weather, cursor strength, structures,
      betrayal, scoring, limits), plus `worldY()` as the single pygame→libGDX
      y-axis conversion
- [x] `Tuning` — endless timetable, horn, talent income, skill constants
- [T] `DifficultyConfig` + `DifficultyTable` + `assets/data/difficulties.json` —
      `DifficultyTableTest` asserts all 30 values against the Python table,
      menu order, unknown-id fallback and loud failure on malformed content
- [T] `QualityConfig` (LOW/MEDIUM/HIGH, cosmetic only) — `ServicesStartupTest`
- [T] JSON data loader + strict validation (`JsonSource`, `Json5`,
      `DataException`) — missing/mistyped fields name the file and the field
      rather than defaulting silently
- [x] `GameAssets` — sole owner of the `AssetManager`; nothing else may build a
      texture. Disposal verified through `Services`
- [T] `SkinManager` (load / validate / activate / unload) — `SkinSystemTest`:
      switching releases the previous atlas before the new one goes live, and a
      broken, malformed, missing or unloadable skin leaves the previous one
      running
- [T] `SkinDefinition`, `UnitVisual`, `AnimationSet`, `AttachmentPoint`,
      `VisualId`, `AnimationState` — attachments normalised to the **gameplay**
      box, so artwork can never move a hitbox or an anchor
- [T] `SkinValidator` — duplicate ids, unknown keys, missing regions, partial
      animations, out-of-range attachments, non-positive scale
- [T] `gradlew packAssets` TexturePacker task — verified end to end on generated
      PNGs. Writes the packer settings itself, with `useIndexes:false` so
      `scout_walk_0.png` stays region `scout_walk_0` instead of being collapsed
      into an indexed `scout_walk`
- [T] Procedural fallback wiring — `visualFor()` never returns null, marks
      uncovered ids procedural and logs each once; the shipped `procedural` skin
      declares no atlas at all, so the game runs with zero artwork exactly like
      the Python original
- [T] `SaveManager` / `SaveData` / `SaveMigration` (saveVersion 1) —
      `SaveManagerTest`: round-trip, corrupt file, partial file, future version,
      migration chain, missing migration. Save v1 keeps **one shared
      `highScore`**, asserted by a test that fails if per-mode scores appear
- [x] `PlatformServices` + `HapticEvent` + `SafeAreaInsets`, desktop no-op and
      Android implementation (vibrator, share, open URL, display cutout).
      The Android side compiles only where the SDK exists — **untested here**
- [T] `CrashLogger` — full stack traces plus a state snapshot to
      `Gdx.files.local`, global uncaught handler, rotation, and a context
      provider that may itself fail without breaking the log
- [T] `Strings` localisation foundation + `assets/i18n/strings.properties` —
      missing keys render as `!key!`, unknown locales fall back
- [T] `Rng` (split gameplay/decoration streams) + `WeightedPicker` (Python
      `random.choices` semantics) — distributions asserted statistically
- [T] `Services` startup wiring — order, what is fatal (broken balance data) and
      what degrades (missing save, unknown difficulty, unavailable skin)

## Phase 4 — Simulation foundation

Contract: [`java-port/docs/subsystems/SIMULATION.md`](java-port/docs/subsystems/SIMULATION.md),
[`INPUT.md`](java-port/docs/subsystems/INPUT.md).

- [T] `Simulation` fixed 1/60 accumulator + `MAX_STEPS` — `SimulationTest`: one
      step per DT, split frames, multi-step catch-up, the budget cap and its
      dropped time, 144 Hz and 30 Hz frame rates, alpha range, reset. The
      accumulator is a **`double`**: `DT = 1f/60f` is fractionally larger than a
      true sixtieth and a `float` accumulator drifts over a long Endless run
- [T] Frame-delta clamp + post-resume guard — clamps above 0.25 s, treats NaN /
      infinity / negative deltas as zero, and `resume()` discards the stale
      partial step instead of replaying it into the world
- [T] Simulation-time accounting — `timeSeconds() == stepCount * DT`. **No
      gameplay clock is ever derived from a frame delta or from wall-clock**;
      `GameWorld` exposes `simulationTime()` (always advances) and
      `gameplayTime()` (only while unfrozen)
- [T] `GameWorld` lifecycle foundation + insertion-order semantics —
      `GameWorldTest`, `EntityListTest`. `EntityList` never swap-removes, because
      `separate_enemies`, Cannon cluster scoring and shared tower targeting all
      depend on list position (deferred to Phase 12, per `PORT_ANALYSIS.md` §3)
- [T] Mark-dead / skip-dead / sweep-after-iteration lifecycle — the sweep runs at
      the end of a step, exactly where the Python `update` does it, and preserves
      relative order
- [T] Snapshot iteration support — pooled, nestable, `AutoCloseable`. A snapshot
      keeps entities killed during the iteration and excludes ones spawned during
      it; `peakOpenSnapshots()` turns a leak into a test failure. The Python
      call sites that need it (enemy update, air slams, tornado, blast) are wired
      in Phases 5–7
- [T] `Collisions` primitive AABB helpers (no allocation) — `CollisionsTest`
      asserts the inclusive `<=` boundaries against `enemies.py:274` (`covers`)
      and `enemies.py:283` (`overlaps`). No spatial grid, quadtree or broadphase:
      those change which pairs are tested and are Phase 12 work behind parity
      proofs
- [T] `GameState` / `GameMode` enums + freeze rules — only `PLAYING` advances the
      world, which is what freezes the Endless realtime shop mid-fight. `GameMode`
      carries a stable `id()`, never an ordinal
- [T] `GameInput` abstraction — the **only** class in `core` that sees a screen
      pixel; everything downstream is world or UI units. One shared logical model:
      a desktop mouse is pointer 0
- [T] `DesktopInput` (mouse → pointer 0, focus-loss release guard) —
      `syncMouseButton` recovers from a button-up the window never delivered
- [T] `TouchInput` (multitouch, pointer ownership, cancel handling) — `onAppPaused`
      cancels every pointer, since Android sends no touch-up when backgrounded
- [T] `TouchVelocityTracker` (time-based eviction, 90 ms lookback, ±2600 clamp) —
      `TouchVelocityTrackerTest` asserts the same flick yields the same velocity
      within 1 % at 30, 60, 120 and 240 Hz. Python's `deque(maxlen=12)` is a
      **count** window that spans only 50 ms at 240 Hz, so the port keeps a
      duration window instead
- [T] `InputRouter` UI-before-world consumption — `InputRouterTest` (13): pointer
      ownership cannot be stolen, only the owner may release, consumption is
      sticky per pointer, and a cancel dispatches `onWorldCancel` rather than
      `onWorldRelease`
- [T] Deterministic gameplay RNG seed — `GameWorld.beginRun(mode)` returns the
      seed and `describe()` puts it in the crash log; `beginRun(mode, seed)`
      replays it; `-Dcastledefense.seed=…` sets it from the command line
- [T] `SimulationTrace` observation seam — `NoOpSimulationTrace.INSTANCE` by
      default (one predicted branch when off), `RecordingSimulationTrace` for
      tests. Deliberately **not** an EventBus, a replay log or event sourcing:
      the trace observes, it never carries control flow
- [x] Wiring in `CastleDefenseGame` — it owns `GameWorld`, `Simulation`,
      `GameInput` and `InputRouter`; `render()` advances the accumulator, routes
      input once per simulation step, then draws with `alpha()`. Smoke-verified
      on a real backend: 600 frames at 2400x1080 ran 69 steps / 1.150 s — steps
      track elapsed *time*, not frames — with zero clamped frames and zero
      dropped steps. `-Dcastledefense.seed=…` is forwarded by `:lwjgl3:run` and
      logged at startup, verified end to end

## Phase 5 — Defences

Contracts: [`DEFENCES.md`](java-port/docs/subsystems/DEFENCES.md),
[`PROJECTILES.md`](java-port/docs/subsystems/PROJECTILES.md).

- [T] World seams that keep `defence` free of enemy types — `Target`,
      `Trappable`, `AllyFactory`, `CombatModifiers`, `DefenceContext`. The
      package imports no enemy class and never will: `castle.py` does not import
      `enemies.py`, so `Target` is declared **here** and Phase 6's `Enemy` will
      implement it
- [T] `Projectile` (kinds, splash, pierce, gravity, wind, stun, `ownerUid`,
      `atPrisoner`, life, damage falloff, hit-id tracking) — `ProjectileTest`.
      The 8-sample `trail` and colour are drawing state and are deliberately
      absent until Phase 11
- [T] Friendly projectile collision, in the Python hot-path order (cheap
      primitive bounds → alive/already-hit/targetable → resolve), at most one
      target per step, nothing allocated per candidate
- [T] Hostile projectile resolution order (prisoner shot; else barricade → tower
      → wall → keep) — `HostileResolutionTest` places shots that **overlap
      several structures at once** and proves the Python priority decides. A
      disabled tower is transparent
- [T] Crit (talent) and per-target counter multipliers — counters resolved per
      victim at impact; splash bonus and crit chance sampled once at construction
- [T] `DefenseTower` base (regen, rebuild, 42% per-hit cap, stun, aim lerp, lead
      target, overcharge state) — `TowerLifecycleTest`. Fields are documented as
      gameplay-authoritative or visual-only; no class here has a `draw` method
- [T] `Bowman` (`AIR_RANGE_MULT` 1.9 envelope, stretched **upward only**) —
      `TowerCountersTest`
- [T] `Ballista` (+200% air, pierce, flyer-first then beefiest scoring). The
      two-component score reproduces Python's tuple comparison
- [T] `Cannon` (+200% heavy, splash, cluster scoring, ballistic solution,
      no air). Cluster scoring is **O(E²), like Python's**; the grid is Phase 12
- [T] Overcharge slingshot (both overchargeable towers, power scaling, lockout,
      talent cooldown, 12 px minimum draw) — `OverchargeTest`. The gameplay
      calculation only: no mouse or touch type reaches a tower
- [T] `Castle` (6 tiers, uncapped reinforcement, slots, repair, splash, tower
      smash, stun entry points) — `CastleTest`, including the Python self-test's
      "Reinforce Walls must never refuse" and the `+N` label
- [T] `SpikeWalls` (reflect + bleed talent, wave scaling) — `StructuresTest`.
      The bleed is a second, separately tagged hit, not a bigger first one
- [T] `Outpost` (garrison, turret upgrade at level 4, overdrive past the cap,
      untouchable, prisoner state/damage/regen, rival aim point, ally seam) —
      `StructuresTest`, using a counting fake factory. No Phase 6 type is pulled
      forward
- [T] `Barricade` (buy/rebuild/reinforce in one action, HP progression, regen
      talent, projectile interception, collision band). Ground-unit *blocking* is
      an enemy-side movement rule and belongs to Phase 6; what it will read
      (`alive()`, `x()`) exists now
- [T] `data/defences.json` + `DefenceTable` — every number transcribed from
      `castle.py` and asserted against it, with load-time validation for
      duplicate ids, non-positive cooldown/range/damage/health, negative splash,
      unknown projectile kind, unknown tower id, missing stat block, counters
      below 1.0, non-monotonic castle tiers, and slots that overlap or sit
      outside the world
- [T] Seeded determinism — cooldown jitter, crit rolls and the boss tower smash
      all draw from the gameplay `Rng`. `DefenceIntegrationTest` runs the same
      scenario twice on one seed and asserts identical outcomes
- [T] Trace integration (`PROJECTILE_SPAWN`, `TOWER_FIRE`, `TOWER_DISABLED`,
      `TOWER_REBUILT`, `CASTLE_DAMAGE`, `BARRICADE_DAMAGE`) — proven to be
      observation only by running a scenario with tracing on and off

## Phase 6 — Enemies, interaction physics and wave composition

Contracts: [`ENEMIES.md`](java-port/docs/subsystems/ENEMIES.md),
[`INTERACTIONS_PHYSICS.md`](java-port/docs/subsystems/INTERACTIONS_PHYSICS.md).

- [T] `Enemy` base state machine (walk / attack / grabbed / air / trapped;
      `retrieve` declared for Phase 7) — explicit transitions, each traced, no
      behaviour tree and no ECS. `EnemyBehaviourTest`
- [T] `Enemy implements Target` — the Phase 5 contract, unchanged. `defence`
      names no enemy type; `Necromancer implements Trappable`
- [T] `WaveScaling` + endgame tiers + difficulty scale / curve / flat speed —
      the chain's **order** is asserted against Python for 8 waves × 3
      difficulties × 2 units in `PythonParityTest`
- [T] Armour model + stripping (progress, permanent slow, vulnerability) —
      the Siege Ram's plate-by-plate progression is checked against the source
- [T] Grab gating (`grabbable` / `armored` / `shovable` / `tooHeavy`) — four
      conditions, with mass-vs-capacity only the last: a plated tank rides out
      every lift at any Grab Strength
- [T] Grab capacity — a **table lookup times the talent**, not a formula, so the
      talent can push a level past the next threshold. Asserted against Python
- [T] Throw physics (release power by mass, air drag, wind, arena walls) —
      `PythonParityTest` checks the integration step for step at dt = 1/60
- [T] Fall damage + bounce ladder + stagger — thresholds tested just below, at,
      just above and lethal; every bounce level tested by name. The chain ends on
      `count > level`, **strictly**, so level 0 never rebounds
- [T] Slam damage + knock-on + per-uid cooldown — including the regression that
      a new mob never inherits a dead one's cooldown
- [T] Shove (factor / decay / max) — magnitude and decay checked against Python
- [T] Blocking / queueing and crowd separation — **order-dependent by contract**:
      insertion order, nested `(i, j)` traversal, immediate per-pair mutation.
      No bucketing, sorting, grid or parallelism
- [T] Scout · FootSoldier · ShieldBearer · Berzerker · SiegeRam — the Berzerker
      speed quirk is reproduced and pinned by a named test; the Siege Ram is the
      integration test for the whole interaction stack
- [T] Skeleton · Necromancer (stand-off, summon cap, rival bolts, halting level
      with the Outpost, trappable) — no circular dependency: the Outpost knows
      nothing about Necromancers
- [T] Assassin (cloaked → untargetable, dash speed ordering) · Gargoyle (flying,
      not a ground blocker). A cloaked Assassin is invisible to towers and
      projectiles through the existing `targetable()` contract alone
- [T] Volatile (detonation after `super.die()`, chain, wall and barricade damage)
- [T] TreasureGoblin (flees, timer, off-map escape, **silent** death)
- [T] `FriendlySkeleton` ally — **not** an `Enemy` and **not** a `Target`, so
      towers cannot target it by construction. March, hold line, engage,
      Sentinels chase, lifetime decay
- [T] `CursorInteraction` — grab, drag, multi-grab, throw, cancel-drops, strip,
      shove, overcharge power. No input device reaches gameplay
- [T] Multi-grab release jitter ×0.85–1.15 from the seeded stream, with the
      primary release exact
- [T] Unlock table / wave composition / goblin roll / boss insertion / second
      boss from wave 20 — bosses are `SpawnSpec`s carrying stable ids;
      **no boss class is faked**
- [T] `data/enemies.json` + `EnemyTable` — every number asserted against
      `enemies.py`, with load-time validation for duplicate and unknown ids,
      non-positive stats, incoherent armour flags, non-ascending endgame tiers,
      bad tints and bad unlock weights
- [T] Python↔Java numeric parity fixtures — `tools/parity/generate_fixtures.py`
      generates 249 reference cases from the source (read-only, by parsing rather
      than importing) covering wave scaling, the full scaling chain, release,
      air integration, fall damage, bounce, slam, shove, stripping and capacity
- [T] `EntityList.remove` + ordered backing array — a live entity can leave the
      list (the Outpost trap) without breaking insertion order, and the
      snapshot contract is proven against the real `EntityList`

## Phase 7 — Bosses and interactive disruption

Contract: [`BOSSES.md`](java-port/docs/subsystems/BOSSES.md).

- [T] **Gameplay anchors separated from visual attachments** (pre-phase work).
      `config.GameplayAnchor` is gameplay-authoritative and comes from
      `data/bosses.json`; `assets.AttachmentPoint` is cosmetic, has no
      `worldX`/`worldY` any more, and is named `visual*`/`draw*` so a call site
      reading one is visibly a rendering call site. `ArchitectureTest` fails the
      build if a gameplay package imports `assets`
- [T] `ArchitectureTest` — nine dependency rules checked against the source:
      gameplay never names assets, rendering, a backend or `java.util.Random`;
      `defence` never names an enemy; `enemy` never names a boss; nothing does
      `instanceof TrollKing`
- [T] `Boss` base (intro, aura, `fireDelay`, shared disruption guard) — immunity
      is a flag, never an `instanceof`, and a boss dies and pays out through the
      ordinary `Enemy` contracts
- [T] `TrollKing` (crown detach, retrieval at 1.7x attacking nothing, the
      at-rest-only pickup rule, leap with its minimum range, tower smash + stun
      via the existing `Castle.smashRandomTower`)
- [T] `Dragon` (flight, breath **streamed on a simulation timer**, claw
      battering, reel, breath cut off mid-stream, driven back 120 px)
- [T] `LichLord` (staff disarm for 5 s with the ward dropping too, magical staff
      recall, raise-dead through the enemy factory, bone ward applied **before**
      armour, barricade stand-off, death bolt target priority)
- [T] `DroppedItem` physics — **not an `Enemy`**: its own gravity response,
      0.34 restitution, arena walls, rest state, and a pickup box from
      `RegaliaKind` rather than from artwork. Throw caps 2300/780 by kind, applied
      to the magnitude with the direction preserved
- [T] Regalia guard cooldown growth — the ladder starts at **9.6 s, not 6 s**
      (the counter increments at detach, the guard applies at recovery), and the
      Dragon's claws grow the same ladder
- [T] Boss lifecycle purge — the boss, the horde entry, every cursor reference,
      its regalia (including a piece being carried) and its projectiles, all
      matched by **owner uid**. Driven from the defeat hook, as in Python
- [T] Repeat-boss cleanliness — spawn, interact, kill, purge, respawn, for all
      three: new uid, full health, zero disruption count, no guard, no leftover
      item, no cursor reference
- [T] Simultaneous bosses — `BossRegistry` is a collection, never a
      `currentBoss`. All three pairings tested for independent health, timers,
      disruption state, projectiles and dropped items; one dying never purges
      the other, and interaction ownership cannot cross instances
- [T] Boss projectile ownership — killing boss A leaves boss B's fire in the air
- [T] `data/bosses.json` + `BossTable` — every number transcribed from
      `enemies.py`, with load-time validation for duplicate and unknown ids,
      non-positive stats, an anchor far outside the box, an interactive area with
      no size, a Dragon that could never breathe, a shot interval longer than the
      breath, and inverted min/max ranges
- [T] Boss parity fixtures — the guard ladder, crown retrieval speed, breath
      cadence at **both precisions**, claw progress, ward ordering, dropped-item
      flight, throw caps and the summon cadence, all generated from the Python
      source
- [T] `SkinIndependenceTest` — two skins that place the crown artwork over 100 px
      apart produce byte-identical boss simulation over 900 seeded steps

## Pre-Phase 8 — the time domain

Contract: [`SIMULATION.md`](java-port/docs/subsystems/SIMULATION.md),
§ "Gameplay time is double". Analysis: [`PORT_ANALYSIS.md`](docs/PORT_ANALYSIS.md) §14.1.

**`Gameplay time = double precision. Spatial simulation = float where appropriate.`**

- [T] `step(double dt)` end to end — `Simulation.Stepper`, `GameWorld.step`,
      `GameWorld.StepListener`, and every `update`/`think` below them. The step
      is handed to gameplay as `FIXED_DT` itself; a system that integrates space
      narrows it once, itself, with `float fdt = (float) dt`. `Simulation.DT` is
      renamed `PHYSICS_DT` so a call site reading one is visibly spatial, and
      **no production class reads it** — `TimeDomainTest` fails the build if one
      starts to. One step at two precisions, never two clocks
- [T] Every authoritative gameplay timer converted — 45 fields across defences,
      enemies, bosses and the cursor. Drawing state (`hurtFlash`, `recoil`,
      `aura`, `trapGlow`, `orb`, `smash`, `fuse`, `anim`, `bob`, `spin`) and
      non-time rates (`regen` HP/s, `breathPower`, `shove` px/s) deliberately
      stay `float`
- [T] Configured durations converted — 10 `GameConfig` constants, 13 in
      `Tuning`, and the duration fields of `EnemyConfig`, `BossConfig`,
      `TowerConfig` and `DifficultyConfig`. `Json5.seconds`/`optSeconds` parse
      them, so a duration is visibly a duration at the call site
- [T] `Rng.uniformSeconds` for randomised durations — Python's `random.uniform`
      is a double. Draws exactly one `nextLong` like `Rng.uniform`, so the
      gameplay stream advances identically and a seeded run stays reproducible
- [T] `Outpost` crew reloads moved from libGDX `FloatArray` to `double[]`
      (libGDX ships no `DoubleArray`); grows outside the step loop
- [T] **Dragon Hard breath: Python 15, Java 15.** Was 16. Asserted in the
      standalone cadence loop *and* against a live Dragon, exactly — no
      tolerance, because the boundary is no longer precision-dependent.
      `BossParityTest.shippedHardBreathIsFifteen`
- [T] The float32 fixture kept as a **regression demonstration** rather than a
      description of production — `BossParityTest.floatTimersAreWhyThisRuleExists`
      pins that three of six configurations diverged, in both directions
- [T] `TimeDomainTest` (8) — behavioural boundary from 50 ms to an hour, an
      identical repeating cadence over 216,000 steps, an explicitly **named**
      list of authoritative timer accessors, the configured-duration types, and
      a source scan for the float step. Named on purpose: a reflective
      "any float called `*Timer`" rule would flag drawing state and miss
      `shield` and `reel`
- [x] No source comparison operator changed; no `EPSILON` introduced; no
      gameplay constant adjusted

## Phase 8 — Game progression and runtime directors

Contracts: [`MODES_PROGRESSION.md`](java-port/docs/subsystems/MODES_PROGRESSION.md),
[`SCORING.md`](java-port/docs/subsystems/SCORING.md),
[`WEATHER.md`](java-port/docs/subsystems/WEATHER.md).

- [T] `RunSession` — run-level state separated from the world exactly as Phase 1
      proposed: mode, difficulty, wave/tier, gold, score, combo records, horn,
      run clock, seed. **Not** the Python `Game` god object: no castle, no
      roster, no shop, no input, no screen. The Endless clock is
      `playSteps * FIXED_DT`, derived rather than summed — a summed one reads
      29.999999999999577 at the 1800th step and puts the whole timetable a step out
- [T] `RunWorld` — the run assembly and the production implementation of all four
      context seams (`DefenceContext` → `EnemyContext` → `BossContext`, plus
      `DirectorContext`). Nothing below it changed to be run for real. Step order
      transcribed from `main.py`'s `update`, including the three tiers
- [T] `GameWorld.AlwaysListener` — the tier Python runs *before* its state check.
      Shake, flashes and banners age on the pause screen; entities do not
- [T] Classic wave flow — `WaveDirector`: numbered waves, composed queue,
      `max(0.32, 1.25 - wave*0.032)` interval, 0.8 s first spawn, `MAX_ALIVE` 58,
      boss entries resolved from the queue with the difficulty headstart and a
      2.4x breather, wave bonus `80 + wave*22 + wavePurse`, talent point,
      tower restore, shop handover — `ClassicFlowTest` (17)
- [T] Classic wave-clear semantics — strictly `> 1.1 s`, reset to zero by
      anything hostile, and **allies do not hold a wave open**; neither does a
      mob marked dead but not yet swept. The 66th clear step ends the wave
- [T] Endless runtime — `EndlessDirector`: tier every 30 s, spawn gap ramping
      1.70 → 0.38 s over 300 s with ±28% jitter, `MAX_ALIVE` 60, bosses at
      120/240/360 s then a **random** one every further 120 s counted from 360 s,
      +1 talent point per minute — `EndlessFlowTest` (18)
- [T] Long-run schedule tests — 30 s, 60 s, 120 s, 240 s, 360 s, 600 s, 10, 30
      and 60 minutes, stepped directly. An hour is exactly 3600.000 s, tier 121,
      60 talent points, 3 scripted + 27 repeat bosses. The whole suite runs in
      about two seconds
- [T] Boundary tests — 29.9833/30.0/30.0166 and the same either side of 60 and
      120, asserted to the step. No epsilons
- [T] Endless realtime shop — a true freeze: `play_time`, the enemy count, every
      enemy's x, the spawn timer and the tier ladder are all unchanged after 120
      frames of shopping, and the cursor lets go rather than holding a mob in
      mid-air. `RunStateTest`
- [T] Freeze contract for every state — MENU/PLAYING/SHOP/PAUSED/GAMEOVER/
      TALENTS/SETTINGS each proved individually
- [T] Gold, kill payout and the crowd multiplier — including the quirk that the
      dying mob is counted in its own payout, and the silent Goblin escape.
      `ScoringTest`
- [T] Fling score — distance + airtime, combo at 0.75/hit, **both** truncations
      in the source's order, best fling on the awarded figure, best combo only on
      a real combo
- [T] Challenge Horn — `ChallengeHorn`: Classic empties the queue head-for-head,
      Endless calls in 12, Hard swaps chaff for elites in Classic and fields a
      10-strong pack in Endless rolled 3 tiers deep. **Once per wave in Classic,
      once per RUN in Endless** — the quirk, pinned. An empty queue refuses
      without spending the horn. `ChallengeHornTest` (11)
- [T] Weather — `Weather`: one gameplay-authoritative wind that projectiles and
      airborne bodies both consume on the same step, storms, ceiling strikes at a
      share of maximum health with a per-mob lockout. `WeatherTest` (10)
- [T] Screen shake — `ScreenShake`: capped at 14, decays at 42/s in the always
      tier, gameplay contributions only. State, not rendering
- [T] Announcements — `Announcements`: stable `Id` plus an int and a subject id,
      never a rendered sentence. Ages in the always tier. `AnnouncementsTest`
- [T] Talent income — `TalentIncome`, one method wide. Phase 9 owns the tree
- [T] Trace integration — `WAVE_START`, `WAVE_END`, `TIER_CHANGED`,
      `SPAWN_SCHEDULED`, `BOSS_SCHEDULED`, `HORN_USED`, `GOLD_CHANGED`,
      `SCORE_CHANGED`, `COMBO_CHANGED`, `WEATHER_CHANGED`, `STORM_STRIKE`,
      `TALENT_AWARDED`. Proven observation-only by running a wave with tracing on
      and off and comparing the run description
- [T] Reproducible run diagnostics — `RunWorld.describeRun()` carries build state,
      seed, mode, difficulty, step, simulation time, tier/wave, gold, score,
      bests, alive/pending counts, active bosses, next spawn, next boss, wind,
      storm, shake and horn. Built on demand; nothing logs per frame. Locale-
      stable (`Locale.ROOT`), so a report from any machine reads the same
- [T] Python↔Java progression fixtures — the generator now emits **425** cases
      (was 249): wave bonus, spawn interval, tier-at-time, spawn-gap ramp, boss
      schedule, talent income, crowd gold, kill payout, fling score, wind, storm
      damage, shake, horn composition and the wave-clear boundary.
      `ProgressionParityTest`
- [T] Desktop smoke — `:lwjgl3:run --mode classic|endless --frames N` starts a
      real run on the real backend and prints the reproducible line. Verified
      seeded, both modes
- [x] `--mode` added to the desktop launcher; the seeded run and its diagnostics
      are the smoke test

## Phase 9 — Player progression

Contracts: [`TALENTS.md`](java-port/docs/subsystems/TALENTS.md),
[`SHOP.md`](java-port/docs/subsystems/SHOP.md),
[`SKILLS.md`](java-port/docs/subsystems/SKILLS.md),
[`DIFFICULTY.md`](java-port/docs/subsystems/DIFFICULTY.md).

- [T] **All 38 talents**, 6 branches — `TalentTable` + `data/talents.json`.
      Every node's branch, tier, rank cap, per-rank magnitude and effect id
      asserted against the source one by one; there is no talent in `main.py`
      without an explicit Java disposition. `TalentInventoryTest` (10)
- [T] `TalentTree` — tier gating (`branchPoints(branch) >= tier`, which is
      points in the node's OWN branch, not a prerequisite node), rank caps,
      purchase failures that consume nothing, reset. `TalentTreeTest` (19)
- [T] **`TalentTree` IS the run's `CombatModifiers`.** No new coupling: every
      system was already written against that interface in Phases 5–8, so the
      tree simply became what sits behind it. Nothing in `enemy`, `boss`,
      `defence`, `shop` or `skill` names `TalentTree`, and `ArchitectureTest`
      now covers `talent`, `shop` and `skill` too
- [T] **Live values, no snapshot** — every modifier is computed from the current
      ranks on each call, as Python's `@property` reads are, so there is no
      cached copy for a purchase to forget to invalidate. Each effect is proved
      by buying it mid-run and performing the action it should change.
      `TalentEffectsTest` (16). The one deliberate cache, the cursor's grab
      cooldown, is refreshed by a named method
- [T] Talent graph validation — duplicate ids, unknown branch/effect, negative
      tier, `maxRank < 1`, non-positive `perRank`, doubled branch declarations,
      and **a branch with no entry node**. There is no cycle check because the
      gating model has no edges: six independent ladders, so a cycle is not
      expressible
- [T] **All 11 shop items** — `ShopTable` + `data/shop.json`, in the source's
      order (the number hotkeys depend on it). Every curve, cap and availability
      rule transcribed. `ShopTest` (22)
- [T] Transactional purchases — availability, price, purse, apply, **then**
      deduct. A refused effect costs nothing and does not move the price. Every
      failure path tested
- [T] Discount ordering — `int(rawCurve * discount)`, one truncation at the end.
      The fixture emits the other ordering too and the test asserts they differ,
      so the assertion is not vacuous. **Cost constants are `double`**: `150f *
      1.4f` truncates to 209 where the source charges 210
- [T] Tower fallback — free slot stations one; a full wall upgrades **every**
      tower of that type; a full wall with none of that type refuses and costs
      nothing
- [T] **All 3 active skills** — `SkillPanel`, unlock order fixed by a boss each,
      cooldowns in the time domain, targeting on virtual world coordinates.
      `SkillTest` (24)
- [T] Lightning — a column at any height, damage as a share of MAXIMUM health,
      a boss takes a quarter
- [T] Meteor + `FireZone` — rocks are ordinary projectiles; the burning ground
      is created at **cast** time, not on impact, and burns continuously rather
      than in ticks
- [T] `Tornado` — drift, catch, carry and hurl, all four. `tornadoHold` renewed
      each step so gravity is mostly cancelled; the throw list is keyed by the
      uids it actually caught, because the funnel drifts away from them
- [T] **Every skill area effect iterates a snapshot** — the Phase 8 lesson, not
      allowed to recur. Proved with a boss dying mid-blast and with two bosses
      on the field
- [T] Full difficulty audit — all eight knobs, where each is consumed, and
      **applied exactly once**: enemy health, speed, boss fire scale, grab delay,
      gold and the boss headstart each proved single. `DifficultyIntegrationTest`
      (11)
- [T] Hard overhaul — seven of eight knobs changed, the elite horn (10 elites
      rolled 3 tiers deep, swapping chaff in Classic so the head-count holds),
      and the Berzerker quirk still holding under a real difficulty
- [T] Run difficulty captured at run creation — Python reads it live off
      Settings, but no UI path reaches Settings during a run, so the observable
      contract is "it cannot change while the run runs". Documented and tested
- [T] **Persistence re-audited: nothing was added.** `saveVersion` stays 1.
      Talent points and ranks, shop purchases, cursor levels, unlocked skills,
      cooldowns and run economy are all per-run, exactly as in the source.
      `ProgressionPersistenceTest` (11) enforces the save's field list by
      reflection
- [T] New-run reset semantics — new Classic, new Endless, restart after game
      over and a mode switch all start from nothing. **Found and fixed:** the
      structures were built once and never rebuilt, so a tower survived into the
      next run; they are now reconstructed as `Game.reset()` does
- [T] Talent income wired to the real tree — the directors still only say
      "award"; the tree owns the balance. Boss bounty 2 with a slot free, 3 once
      the bar is full
- [T] Read-only query surfaces for Phase 10 — `TalentTree.NodeView`,
      `Shop.ItemView`, `SkillPanel.SlotView`. Immutable, built on demand, no
      view-model framework
- [T] Trace integration — `TALENT_POINT_AWARDED`, `TALENT_PURCHASED`,
      `SHOP_PURCHASED`, `SHOP_PURCHASE_FAILED`, `SKILL_UNLOCKED`,
      `SKILL_SELECTED`, `SKILL_CAST`, `SKILL_EFFECT_CREATED`,
      `SKILL_COOLDOWN_READY`. Observation only
- [T] Python↔Java Phase 9 fixtures — **346 new cases**, total **771**. The 38
      talent definitions are **parsed out of `main.py`** rather than retyped, so
      the fixture cannot drift; every effect at every rank (175), every shop
      curve (63), the discount ordering (25), the bespoke curves (11), the skill
      constants and every scaling talent (33). `ProgressionNineParityTest`
- [T] Seeded progression smokes — a Classic run that buys, clears four waves,
      earns and spends; an Endless run that tiers up, kills a boss, unlocks
      Lightning and recharges it. `ProgressionSmokeTest`

## Phase 10 — UI

- [ ] Main menu + mode picker + difficulty row + settings button
- [ ] Settings screen
- [ ] Shop screen (grid, cards, icons, counter tags, start/resume button)
- [ ] Realtime Endless shop (world frozen)
- [ ] Talent screen (measured grid, tooltip, back)
- [ ] HUD vertical stack (tier/badge/gold, wall, multiplier, health, score, talents, clock)
- [ ] Boss health bars (two)
- [ ] Pause / game-over panels
- [ ] Text layout engine (wrap, line height, paragraph fit)
- [ ] Safe-area anchoring
- [ ] Enlarged touch targets
- [ ] Localisation lookup for all user-facing strings

## Phase 11 — Effects & graphics

- [ ] Particles + floating text (pooled)
- [ ] Screen shake compositing
- [ ] Weather visuals (wind streaks, bolts, storm flash)
- [ ] Skill visuals (bolts, meteors, fire zones, tornado funnel)
- [ ] Procedural renderer for every unit, tower, structure, item
- [ ] Skinned rendering path + animation states
- [ ] Draw-order parity
- [ ] `ShapeKit` rounded rects / arcs / bars / glows

## Phase 12 — Mobile optimisation

> **Rule for this phase:** correctness and parity come first. Every optimisation
> below touches an order-dependent algorithm, so none of them may be applied
> until the parity tests exist, and each must be shown behaviourally equivalent
> to the reference implementation before it lands. The initial port implements
> the Python algorithm verbatim — same insertion order, same nested pair
> traversal, same mutation timing.

- [ ] Allocation audit of the step loop (target: zero steady-state allocation)
- [ ] Projectile × enemy broadphase — *only if provably equivalent*
- [ ] Crowd separation bucketing — *only if provably equivalent; `separate_enemies`
      mutates x mid-traversal, so ordering is observable*
- [ ] Shared per-frame target candidate list for towers — *only if provably
      equivalent*
- [ ] Cannon cluster grid — *only if provably equivalent*
- [ ] Particle pooling + caps per quality preset
- [ ] Glow/shadow pre-baked textures
- [ ] Text draw batching
- [ ] Draw-call / texture-switch audit
- [ ] Quality presets verified to leave gameplay identical

## Phase 13 — Testing & parity

- [ ] JUnit suite green (see `docs/PORT_ANALYSIS.md` §12 for the full list)
- [ ] Headless long-run soak (equivalent of `--selftest 32000`)
- [ ] Desktop smoke test with device-frame simulation (16:9, 18:9, 19.5:9, 20:9)
- [ ] Android smoke test on a real device
- [ ] Parity sweep against the Python build, table by table
- [ ] Every row in this file at `[T]` or explicitly deferred with a reason

---

## Cross-cutting requirements

- [ ] No gameplay class references a texture path, atlas coordinate, screen resolution or
      Android API
- [ ] No rendering code inside gameplay entities
- [ ] Gameplay hitboxes never derived from artwork dimensions
- [ ] All disposables owned and disposed
- [ ] No `Random` instantiation outside `Rng`
- [ ] No `ConcurrentModificationException` paths; mark-dead + sweep everywhere
- [ ] Android Back: playing → pause, submenu → parent, menu → platform default
- [ ] Backgrounding pauses the game and never feeds a long delta into a step
- [ ] Haptics optional, configurable, off the critical path
- [ ] Debug overlay cheap when disabled

## Deferred / explicitly out of scope (until requested)

- [ ] Audio (the Python game ships silent; `Settings.apply_audio` is a stub) — mute flag is
      persisted and honoured, but there is nothing to mute yet
- [ ] Google Play services, achievements, monetisation (§27: not without a request)
- [ ] Additional skins beyond `default` (architecture supports them; artwork is not part of
      the port)

## Quirks that must NOT be "fixed"

See `docs/PORT_ANALYSIS.md` §13 — 15 documented behaviours (Berzerker rage speed ignoring
difficulty/tier speed, slow-multiplier round trip, ally exclusion from wave clearing,
mid-iteration trap removal, Volatile chain detonation, once-per-run Endless horn, gold
multiplier including the dying mob, order-dependent crowd separation, silent goblin escape,
ward-before-armour, bounce chain termination, grab capacity indexing, multi-grab jitter,
shared regalia guard counter).
