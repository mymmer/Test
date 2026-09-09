# PORTING_STATUS.md — Castle Defense: Python/Pygame → Java/LibGDX

Authoritative migration checklist. Source of truth is the Python game in this repository
at commit `a7142ab` (`sprites.py`, `castle.py`, `enemies.py`, `main.py`; 9,363 lines).
Analysis: [`docs/PORT_ANALYSIS.md`](docs/PORT_ANALYSIS.md).

Legend: `[ ]` not started · `[~]` in progress · `[x]` ported (compiles, believed correct)
· `[T]` ported **and tested** (JUnit and/or a smoke run proves the behaviour)

Nothing is complete merely because it compiles. A row may only reach `[T]` when a named
test exercises it.

**Phase status: Phases 1 through 13.1 complete, plus the pre-Phase-8 time-domain
hardening and the Phase 11.5 audit. The Android device gate is now CLOSED: the
game has been built, installed and played on a physical Samsung Galaxy S10+,
holds 60 fps in nine scenarios with no thermal throttling, and survives
backgrounding and screen lock. Phase 13 also found that the game had never been
connected to input at all, and that every world interaction was aimed at the
mirror image of the finger. Both are fixed.**

**(superseded) Earlier phase status: Phase 2 implemented and hardened but NOT fully tested —
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
integration. **The gameplay is now complete.** Phase 10 adds the interface —
every screen, the navigation graph, safe areas, touch targets, text layout and
localisation — leaving Phase 11 for the entity, effect and weather rendering the
interface deliberately does not do. Phase 11 does it: the world is drawn, from
primitives, in the source's painter order, with the skin path and the effect
systems behind it. **The game is now visually complete**, and what remains is
Phase 12's optimisation work.

Phase 10's load-bearing decision is that the interface is **custom immediate
layout, not Scene2D**. Scene2D would have brought a second pointer-ownership model
beside `InputRouter`'s, and two answers to "who owns this finger" is the exact bug
Phase 4 exists to prevent. The custom path also lays out headlessly, which is why
the layout is asserted at seven screen shapes in unit tests rather than eyeballed
in a screenshot. See [`docs/subsystems/UI.md`](java-port/docs/subsystems/UI.md).

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

Contract: [`docs/subsystems/UI.md`](java-port/docs/subsystems/UI.md),
[`TEXT_LOCALIZATION.md`](java-port/docs/subsystems/TEXT_LOCALIZATION.md).

**Framework decision: custom immediate layout, not Scene2D.** Recorded in `UI.md`
§2 and held by `ArchitectureTest.noScene2dAtAll`.

- [T] Main menu + mode picker + difficulty row + settings button — `MenuScreens.MainMenu`
- [T] Settings screen — mute, difficulty, clear high score, and nothing else, matching
      a source whose settings screen has exactly those. `MenuScreens.Settings`
- [T] Shop screen — eleven reflowing cards, every number asked of `Shop`, number
      hotkeys in the source's order. `ShopScreen`
- [T] Realtime Endless shop — the freeze is the state machine's; 120 frames in the
      armoury move play time, enemy positions, the tier ladder and the spawn timer
      by exactly nothing. `UiIntegrationTest.realtimeShopFreezesThroughTheUi`
- [T] Talent screen — data-driven over all 38 nodes, tap to inspect then tap to
      buy, tooltip values read from the tree. `TalentScreen`
- [T] HUD measured row stack — title/badge/gold, wall, multiplier, health, score,
      talents, clock; rows appended only when they apply and the panel sized to
      fit, as in the source. `HudScreen`
- [T] Boss health bars — a list, not a `currentBoss`; two live bosses get two
      non-overlapping bars and a death removes one cleanly. `UiIntegrationTest.twoBossBars`
- [T] Pause / game-over panels — pause has one control (RESUME) and deliberately no
      settings door; game over routes to the menu, which resets
- [T] Skill bar — two-stage targeting, readiness from `isReady`, cooldown display
      frozen with the world
- [T] Challenge Horn button — pressable when spent so the press is consumed rather
      than falling through to a grab
- [T] Navigation graph — every transition in `Navigation`; no screen sets a state
      (`ArchitectureTest.screensRouteThroughNavigation`). `NavigationTest`
- [T] **Difficulty cannot change mid-run** — the Phase 9 snapshot's condition.
      Settings only from MENU, no in-run screen has a difficulty control at all,
      and the preference applies to the next run. `NavigationTest`
- [T] Text layout engine — wrap, ellipsize, fit-to-width, paragraph fit with a
      `clipped` report; font injected as a `Measurer` so it tests headlessly
- [T] Safe-area anchoring — insets through `PlatformServices`, converted with the
      viewport's own ratios, degrading to the full rect on absurd input; the world
      viewport is provably untouched
- [T] Enlarged touch targets — 44 UI units minimum on the hit box only, never the
      artwork; no two controls overlap on any screen at any tested shape
- [T] Responsive layout — 16:9, 18:9, 19.5:9, 20:9 and portrait, by reflow
- [T] Localisation for all user-facing strings — completeness checked against the
      data tables *and* by scanning the source for literal keys
- [T] Long-string layout — every screen at 3x English width and 2x line height
- [T] Input consumption — proven through the real `InputRouter` with a world spy
      underneath. `UiInputTest`
- [T] Android Back through the existing seam — `UiRoot.back()` returns the graph's
      `BackResult`; `EXIT_APP` is the platform layer's decision
- [T] Desktop mouse on the same logical path — one `GameInput`, one router, one
      `UiRoot`. `UiInputTest.mouseAndTouchAreOnePath`
- [x] `UiRenderer` — UI chrome only; built-in `BitmapFont`, whose metrics feed the
      layout so it is measured with the font that draws it. Entities are Phase 11
- [x] UI debug overlay — safe area, visual and hit bounds, viewports, live pointer
      and its owner, with violations in red. `--ui-debug`
- [x] Device-frame presets and simulated cutouts — `--device`, `--insets`,
      `--screen`, through `PlatformServices`, i.e. the production seam
- [x] Screenshot smoke matrix — 8 screens x 3 device frames + the debug overlay,
      `tools/ui/screenshots.sh`. Evidence, not a gate: the layout assertions are in
      `UiLayoutTest`

Two defects Phase 10 found and fixed in earlier code:

- `CastleDefenseGame.startRun` called `beginRun` directly, bypassing the navigation
  graph and leaving the game `PLAYING` where the menu button opens the first
  armoury — two entry points that disagreed. It now routes through `chooseMode`.
- `UiRoot`'s preferred difficulty was never seeded, so it was null until the player
  touched the setting. It now defaults from the save.

Not in Phase 10, by the brief: entity and skin rendering, particles, weather
visuals, skill visual effects, screen-shake compositing, audio.

## Phase 11 — Effects & graphics

Contracts: [`RENDERING.md`](java-port/docs/subsystems/RENDERING.md),
[`VISUAL_EFFECTS.md`](java-port/docs/subsystems/VISUAL_EFFECTS.md).

**The rule the phase rests on: rendering reads gameplay and never touches it.**
Enforced structurally (no gameplay mutator, no gameplay `Rng`, gameplay may name
only `VisualEvents` in the render package) and behaviourally — ten thousand
rendered frames leave every position, every hit point, the run clock and both
halves of the generator's state exactly as they were.

- [T] Draw-order parity — `DrawOrder.Layer` transcribes `Game.draw`; the enemy
      comparator is `(flying, depth, x)`, and the sort runs on a buffer the
      renderer owns so the authoritative roster keeps its insertion order.
      `RenderPurityTest`
- [T] Coordinate boundary — the simulation keeps Pygame's downward y (771 parity
      fixtures depend on it); `WorldGeometry` converts at the one place the
      renderer reads a position. Angles flip too. Found by the first capture,
      which had the horde walking along the top of the sky
- [T] Screen shake — a world-camera offset, restored before the frame ends: no
      entity moves, no hitbox moves, and a pointer aimed at world x still lands
      on world x. No drift over a thousand cycles. `ShakeAndInterpolationTest`
- [T] World/UI separation — the skill bar, horn and grab cursor shake because the
      source draws them into the shaken surface; the HUD panel, menus and boss
      bars do not. Only the drawing moves — hit rectangles stay put
- [T] Interpolation — enemies, projectiles and items; previous transforms live in
      the renderer keyed by uid, never on the entities; a jump past 240 px is a
      teleport and is not blended; a run reset clears the history
- [x] Procedural renderer — background, castle (all 6 wall tiers, with the
      turret, portcullis, buttresses and runes), 3 towers, outpost, barricade,
      spikes, all 11 enemies, the ally, all 3 bosses, crown and staff, every
      projectile kind
- [T] Skin path with **per-visual** fallback — a skin missing one unit keeps the
      rest; missing art warns once per skin/visual/state and never per frame;
      static and animated skins both valid; animation timing is cosmetic.
      `ArtFallbackTest`
- [T] Particles + floating text — pooled at the source's caps, every field reset
      on reuse, bounded, cleared on a new run. `EffectsAndQualityTest`
- [T] Presentation-event seam — `VisualEvents`, three void methods, one sink,
      default `NONE`. Deliberately **not** `SimulationTrace` and not an event bus;
      a run with a sink attached is bit-identical to one without
- [x] Weather visuals — wind streaks, storm veil, jagged bolts; the strike itself
      stays Phase 8's
- [x] Skill visuals — Lightning, Meteor, FireZone flames, Tornado funnel, all
      from gameplay geometry; no radius or lifetime is duplicated
- [x] Damage flashes, glows and auras — from gameplay timers; no texture is
      allocated during a frame
- [T] `ShapeKit` — the Pygame primitives this game uses and no more; rounded
      rectangles built once for all ~80 uses
- [T] Quality is cosmetic — LOW/MEDIUM/HIGH change particle counts, glow, shadows
      and trails and nothing else; a minute of identically seeded Endless matches
      exactly on LOW and HIGH. `EffectsAndQualityTest`
- [x] Visual scenario harness — 14 controlled states built from **real gameplay
      objects**; `--scenario`. No parallel implementation
- [x] Rendering diagnostics in the debug overlay — fps, frame time, alpha,
      quality, drawn counts, particles against the cap, skin, atlas, missing art
- [x] Visual baseline — 18 captures in `build/visual-baseline`, including three
      device shapes and one with the overlay on

Two defects this phase found and fixed:

- **Entities were drawn upside down.** The simulation keeps Pygame's y and the
  renderer assumed libGDX's. Found by looking at the first capture, not by a
  test — which is exactly what the capture suite is for.
- **The procedural skin could not be re-selected.** `SkinManager.load` looked for
  a descriptor file, and the built-in skin has none, so switching *back* to it
  failed once a real skin was loaded. Found by `ArtFallbackTest`.

Not in Phase 11, by the brief: the Phase 12 optimisation rewrite, spatial
broadphase, cluster grids, target caches, gameplay pooling changes, audio.

### The visual reference task — closed in Phase 11.5

Phase 11 deferred it on the belief that the Python game had no headless mode.
**That was wrong.** `Game.__init__` already takes a `screen` and always renders
into its own offscreen `self.scene` surface, so a plain `pygame.Surface` plus
SDL's dummy video driver captures a frame with no window at all. See Phase 11.5
below; the four authoritative files were not touched.

## Phase 11.5 — Shake/input audit and visual reference review

A verification and hardening checkpoint between the renderer and the optimisation
phase. No architecture was redesigned and no Phase 12 work was started.

### A. Shake, input coordinates and touch usability

- [T] **Python's actual behaviour established first.** `Game.draw` blits the
      scene at `random.uniform(-shake, shake)` computed inside `draw` and never
      stored; input is `self.mouse_pos = ev.pos`, the raw event position, and
      `mouse_hist` records that same value. **Shake is visual only and input does
      not compensate.** The port already matched; the audit confirmed it rather
      than assuming a bug
- [T] One coordinate contract, written down in
      [`INPUT.md`](java-port/docs/subsystems/INPUT.md): screen → UI/safe-area →
      world → gameplay, with the shake transform outside all of it. One
      conversion path for mouse and touch
- [T] `ShakeInputTest` — 13 tests through the real `GameInput` → `InputRouter`:
      picking unaffected at 16:9 and 20:9, at both shake extremes, with insets;
      a stationary finger gains no world movement and no throw velocity across
      200 shaking frames; the same flick throws identically at 1x and 3x render
      rate; ownership survives shake through drag, release and cancel; a modal
      opening mid-shake does not steal a live gesture; a skill button over an
      enemy arms and does not grab; no double activation
- [x] **No camera offset is injected into the drag tracker** — considered and
      rejected, because it would give a stationary finger velocity from the
      camera. The tracker only ever sees unshaken positions
- [T] Touch usability at the ceiling: shake is 14 units, the smallest shaken
      widget is 58x60, and the test fails if a future widget ever shrinks enough
      for the offset to walk it off its own touch box
- [T] **Fixed: the Endless SHOP button was shaking.** It is row 7 of the stat
      panel, which `draw_hud` paints on the unshaken screen. The test that guards
      this now reads `UiRenderer`'s own source rather than a list beside it

### B. Visual reference review

- [x] **Python reference captures — done, not deferred.**
      `tools/visual/python_reference.py` imports the unmodified game, builds 12
      scenes through its own `reset`/`begin_play`/`spawn_enemy`/`apply_strip`
      calls, and saves what `Game.draw` produced. 12 of 12 captured
- [x] Paired contact sheet — `tools/visual/contact_sheet.py`, one image per scene
      with Python above and Java below, plus an overview sheet.
      `build/visual-review/`
- [x] Reviewed by looking at the images, which found **eight defects and three
      omissions** that reading the draw methods had missed:

| Found | Cause |
|---|---|
| no particles or floating text anywhere in the game | the event sink was captured from `worldRenderer.events()` *before* `create()` built the particle system, so it held `VisualEvents.NONE` for ever |
| effects drawn mirrored about the horizon | `VisualEvents` takes gameplay coordinates and `EffectsSystem` stored them unconverted |
| the keep rendered almost black | `ctx.shade()` returns a shared scratch `Color`; passing it as a base that is then shaded per block aliased the two |
| hills were sharp triangles | invented rather than transcribed; the source uses three ridges of summed sines |
| no moon, too few stars | missing |
| boss bars across the top | the source draws them along the bottom, at most two, with the name above and the numbers on the bar, in red |
| the horn was a clipped label in the corner | it is a 58x60 brass disc on the castle wall with the word underneath |
| the SHOP button shook | see A above |
| **missing:** the whole field readout | enemies left, kills, throw damage, wind, storm — the only place a player sees any of it |
| **missing:** the announcement banners | Phase 8 built `Announcements`; nothing ever drew it, so every wave name, weather change and boss arrival went unseen |
| **missing:** the grab-cursor layer | named in `DrawOrder.Layer` and never implemented — the overcharge slingshot, the claw-smack ring, and the prompts that make the crown, staff, stripping and overcharge mechanics discoverable at all |

- [x] Deliberate differences kept: Pygame and libGDX differ in font metrics,
      anti-aliasing and primitive rasterisation, and no gameplay geometry was
      altered to make the pictures agree

### C. Presentation polish (second comparison pass)

The first pass fixed structure; a second pass on the reviewed pairs fixed
presentation.

- [T] **Boss bars** — `(208, 62, 60)` red, name above the bar, `hp / maxHp`
      inside it, 620 wide alone and 400 each in a pair 24 apart, capped at two,
      bottom edge 30 above the floor. `BossBarLayoutTest`, 7 tests
- [x] **Typography calibrated.** A pygame size and a libGDX size are different
      units: measured across nine strings and twelve sizes, libGDX draws 1.364x
      wider and leaves more air per line. `TextLayout.GLYPH` and
      `TextLayout.LINE` convert once, so the source's relative hierarchy is
      preserved exactly and no call site is tuned by hand
- [x] **Bold** — the source marks most HUD values and every banner bold; pygame
      synthesises it for a face that has none, and so does the port, with a
      second offset draw pass. No new font, no licence to verify
- [x] **HUD panel** — the source's own `HUD_W = 372`, `HUD_X, HUD_Y = 14, 12`,
      `HUD_PAD = 14`, `HUD_ROW_GAP = 6`, replacing Phase 10's guesses
- [x] **Banner colours** — every one is now the literal triple from the matching
      `announce` call, rather than invented
- [T] **Endgame tier tint** — the painter kept a duplicate colour table indexed
      1-based against a 0-based tier, so a Voidtouched horde rendered Frostbound
      blue and Bloodied units were untinted. The tint now comes from the tier
      data through `Enemy.tierTint()` and the duplicate is gone
- [x] **Instruction line** — the source's wording on desktop, a touch equivalent
      on a phone. Nothing is dropped: pausing is still named, because Back is how
      it is done. Documented as a deliberate platform adaptation

### Captures

`build/visual-review/` — 12 `compare-*.png` pairs, the Python and Java halves
alone, `contact-sheet.png`, and `MANIFEST.md`.

**Every pair is representative-state, not exact-state.** The two sides are
independent programs on independent seeds, so gold, wind, storm and timers differ
by design. `MANIFEST.md` records that for each scene, because a reader who takes
a different gold total for a parity failure is being misled by the artefact
rather than informed by it. Gameplay parity is the 771 fixtures and the unit
suite.

### Still open

- **Manual review.** The captures exist and have been inspected by the port
  author; they have **not** been approved by the repository owner. That approval
  is the gate on Phase 12, and this document does not claim it.

## Phase 13.2 — The play review

Contract: [`ANDROID_DEVICE.md`](java-port/docs/subsystems/ANDROID_DEVICE.md) 13.
Seven findings from the repository owner playing the Phase 13.1 build on the
phone. Each was traced to the authoritative Python before anything changed.

| # | Finding | What it actually was |
|---|---|---|
| 1 | Lightning has no visible effect | Not a Troll King attack at all -- he leaps, smashes a tower and wears a crown. The two lightning sources (`enemies.py:586` -> `main.py:1426` storm strike, `main.py:693` skill) both applied damage and emitted **no visual event**. `Palette.BOLT_CORE`/`BOLT_INNER` existed unused since Phase 11. |
| 2 | Shop upgrades need descriptions | The descriptions were in the bundle since Phase 10 and unreachable: `CARD_MAX_HEIGHT` was 112 units and the layout needed 146. |
| 3 | Talent tree too small to read | And **unscrollable** -- `scrollBy` existed and nothing called it, so enlarging the nodes would have made three talents permanently unreachable. |
| 4 | Shop text too small | 7.4-8.6 dp on a 3040x1440 panel, against Android's 12 sp floor. |
| 5 | HUD covering the upper turrets | My own 13.1 regression: anchoring the panel to the touch rectangle also made it dodge an 84 px gesture strip, dropping it 42 units onto the keep-top emplacement. Its fill was `(15,18,28)@0.88` where `sprites.py` paints `(26,28,42)@190`. |
| 6 | Outpost shows no prisoner | `castle.py Outpost.draw_prisoner` -- cage, glow, hunched Necromancer, four bars, health bar, two captions -- was never ported, while the gameplay around it was complete. |
| 7 | Grabbing unreliable | **Not a hitbox bug.** The box is exactly `hit_rect.inflate(16,16)`. A Scout's 42x50 grab box is 24x29 dp against Android's 48 dp minimum and a fingertip's 8-10 mm patch. Diagnosed by outcome logging on the phone: dense crowds grabbed every time, isolated mobs missed by up to 23 world units. |

### What changed

- `VisualEvents.bolt(x, y, life)` -- a fourth `void` on the same one-way seam.
  The zig-zag is generated in `EffectsSystem` from `VisualRng`, keyed on the bolt
  plus its remaining life so both strokes agree within a frame and the path
  re-rolls between them, as the source's does. No gameplay roll is spent on
  decoration, and the skill's four scattered bolts are spread evenly for the same
  reason.
- Shop cards to 150 units, carrying the source's wrapped description, status line
  and price. What the *next* purchase does comes from `ItemView.upgradesInstead`
  (`main.py:1047`'s "(upgrades)" tail), not from a formula copied into the UI.
- Talent nodes 46 -> 64 units, names 13 -> 16, ranks 12 -> 15; a detail panel for
  the **selected** talent (a finger has no hover), and two scroll buttons.
  Selection already existed: a first tap inspects, only a second buys, through
  `TalentTree`. All 38 talents, six branches and the source gating are unchanged.
- `TalentDef.ValueFormat` carries the source's own `{v}` formatting through
  `talents.json` -- 30 percentages, one to one decimal, four integers, one
  decimal, two valueless. A "below 1.0 is a percentage" rule would print 90% for
  `spikedot` where the game means 0.9 damage a tick.
- The stat panel is back on the display rectangle at the source's `HUD_X 14,
  HUD_Y 12`, in the source's colour and alpha. Panel and turret still overlap
  when the panel is tall -- so do they in Python -- but the press falls through,
  as `main.py` does after checking its two buttons.
- `DefencePainter.paintPrisoner` reads existing prisoner state only. **The
  Outpost stays healthless**, as in the source; the bar is the prisoner's.
- A **touch acquisition tolerance** (18 world units on Android, 0 on desktop),
  consulted only after every exact `grabCovers` test has failed. `grabCovers`,
  collision, damage, physics and grab capacity are untouched.
- **A parity bug found while reading the grab path**: `main.py:1835` keeps the
  candidate with the smallest x -- prefer the nearest threat -- and this port
  returned whichever came first in the list. `enemy_under_mouse` and
  `heavy_under_mouse` both match now.

### Verified, and not

- **Verified on the phone**: bolts visible on both sources; the cage, prisoner
  and health bar; shop descriptions legible; the talent detail panel (Rapid Fire
  read "Lv.0/5 1 pt / Every emplacement reloads 6% faster."); the keep turret
  pressable through the panel (`outcome=TOWER charging=YES`); grabs landing.
- **Not verified**: whether 18 units is the right tolerance for a human hand. adb
  cannot reproduce a person's aiming error. The cause is measured; the feel is a
  question for real fingers.
- **Not verified**: multi-touch, other devices, gesture-navigation devices and
  90/120 Hz panels remain open exactly as Phase 13 left them.

## Phase 13.1 — Device readiness

Contract: [`ANDROID_DEVICE.md`](java-port/docs/subsystems/ANDROID_DEVICE.md) 9-11,
manual steps in [`MANUAL_DEVICE_CHECKLIST.md`](java-port/docs/MANUAL_DEVICE_CHECKLIST.md).

**Two kinds of inset, kept apart.** Phase 13 measured 45% of the SETTINGS button
inside the navigation strip and recorded it rather than fixing it. The cause was
reading one family: this phone's cutout is 142px on the LEFT and its navigation
strip 168px on the RIGHT, so reading the cutout gets one edge right and the other
wrong. `SafeArea` now carries a display rectangle for extents and a TOUCH
rectangle -- the wider strip per edge -- that every control is laid out against.
Decoration still reaches the display edge. The full `systemGestures` region is
deliberately not used: it is larger, includes back-swipe edges an app may
exclude, and would surrender more screen than the platform claims.

Insets are now re-read when Android reports new ones, so the layout follows
immersive bars, rotation and resume; previously they were read at create() and
resize() only.

### Five defects, four of them seen on the phone

| Defect | |
|---|---|
| SETTINGS 45% inside the navigation strip | fixed; `SafeAreaLayoutTest` fails on the shipped layout |
| `!enemy.foot_soldier.name!` on the NEW FOE banner | the THIRD appearance of one key-shape mistake |
| `!skill.{lightning,meteor,tornado}.short!` under every slot | captions the port draws and nobody wrote keys for |
| Talent headings printed through "0 POINTS TO SPEND" | four units apart; the screen no review had opened |
| `LayerTimes` op counters never reset | Phase 13 leftover; counts read as a rising cost |

Neither localisation defect was reachable by scanning source text -- both keys
are composed at runtime, a class the literal scan excludes by design. So
`ComposedKeysTest` walks the real enumerations, and `BannerKeysTest` drives
`UiRenderer.subjectKey` itself. The second exists because the first is not
enough: the bundle DID contain `enemy.<id>`; only asking the production mapping
what key it builds can catch a renderer asking for one nobody wrote.

### Verified on hardware

Grab/drag/throw, armour stripping (with its progress affordance), tower
overcharge (slingshot line, 46% meter), boss crown, Lich staff, all three skills
armed and cast, the Challenge Horn, buying a tower, talents, Back-to-pause,
Game Over to restart with fresh state, and lifecycle with a live grab.

The Endless shop freeze contract, in numbers: alive 24, 25, 27 while playing;
28, 28, 28, 28 across twelve seconds in SHOP; 29, 31 on resume -- while
`steps/frame` stayed at 1.00. The frame keeps running; the WORLD is frozen.

### Driven by test instead

Dragon claws, second-finger ownership and the strip-to-shovable transition, all
through real GameInput/InputRouter/CursorInteraction with the PRODUCTION cursor
rather than TestUi's spy. `ThrowCancellationTest` assembles the whole game,
because TestRun stubs the velocity source to zero and "the cancelled drag threw
nothing" would pass whatever the game does.

**Multi-touch remains unverified.** adb injects one pointer. Five minutes of real
fingers is the only thing that can close it.

**Text size is measured and unchanged.** Only the title clears Android's 12sp
caption guidance; the rest of a faithful 1280x720 desktop layout lands between
6.9 and 12.6 dp on this panel. Fixing it is a global UI scale -- a redesign, and
a decision to take deliberately with the numbers rather than a defect to patch.

## Phase 13 — Android device validation

Contract: [`ANDROID_DEVICE.md`](java-port/docs/subsystems/ANDROID_DEVICE.md).

**Two defects that ten phases of green tests could not see, because both were in
the wiring rather than the logic.**

1. **`Gdx.input.setInputProcessor` was never called** -- anywhere, by anything,
   since Phase 3. libGDX had nowhere to deliver a touch, so `GameInput` was never
   invoked in a running build on any platform. The router tests call `GameInput`
   directly (correctly -- that is how you test a router) and every screenshot is
   a staged scenario, so nothing had ever pressed a button in a running game.
2. **Touches arrived in draw space.** The viewport unprojects y-up; the
   simulation keeps Pygame's y-down world with the ground at 620.
   `WorldGeometry` is the one boundary between them and input never crossed it,
   so a finger on the ground reported gameplay y 125 -- its own mirror image
   about the horizon. Grabbing, throwing, armour stripping, tower overcharge,
   the boss crown and the Dragon's claws were all unreachable.

`ShakeInputTest` drives that exact path and even places a mob at y 600 -- but it
asserts the pointer does not DRIFT under shake, comparing the value against
itself, which a consistently wrong number passes perfectly.

### Also found on the device

| | |
|---|---|
| Back never routed | `Navigation.back()` had the full policy; nothing called it |
| Menu difficulty row never drawn | three invisible pressable controls in empty sky |
| Two sources for the difficulty | menu said NORMAL while the run started HARD |
| `!hud.hornSpent!`, `!settings.unmute!` | raw keys on screen; the Phase 12 scan could not see conditional keys |
| `LayerTimes` op counts never reset | grew window on window, read as a rising cost |

### Measured on a Galaxy S10+ (Exynos 9820, Android 12, 3040x1440, 60 Hz)

**60 fps sustained in all nine scenarios**, zero dropped or clamped steps, and a
five-minute soak with `thermal=0` throughout and no drift.

The frame does **2.7 ms of real work in a 16.7 ms budget** -- 2.6 ms of shapes,
0.07 ms of simulation. The probe's 13 ms of apparent CPU time is the frame
waiting on the display, not working: it is identical at 4.4, 1.1 and 0.49
megapixels, and identical on LOW, MEDIUM and HIGH. **No gameplay broadphase,
cache or pooling was added**, on the device's own evidence as well as the
desktop's.

Not verified: multi-touch (adb cannot inject two fingers), any other device,
gesture-navigation phones, and 90/120 Hz panels -- where the budget would be
11.1 or 8.3 ms rather than 16.7.

## Phase 12 — Measured performance and mobile readiness

Contract: [`PERFORMANCE.md`](java-port/docs/subsystems/PERFORMANCE.md).

**The headline: the simulation is not a bottleneck, and none of the algorithms
`PORT_ANALYSIS` flagged as theoretically expensive costs anything at this game's
densities.** Worst simulation p99 is 661 us at 160 enemies -- 4% of a step's
budget, at a density the game never reaches. So no broadphase, no candidate
cache, no cluster grid and no gameplay pooling was built. Each was measured
first, and each would have been solving a problem that is not there.

### Measurement

- [x] `:core:benchmark` -- ten seeded scenarios, fixed-step, headless, no
      sleeping; at least one per flagged hotspot
- [x] `--bench` on the desktop launcher -- whole-frame percentiles on a real
      backend, startup frames discarded
- [x] `-Dcastledefense.layerTimes=true` -- per-layer times and primitive counts,
      free when off
- [T] The benchmark **fails loudly** if fewer than 90% of a window's steps really
      simulated. The first run reported a 0.1 us median for everything: a crowd
      flattens the castle, a step in GAMEOVER advances nothing, and it was timing
      an early return

### Retained optimisations, all in the renderer

| | change | result |
|---|---|---|
| 1 | corner tessellation scales with radius | enemies 2326 -> 2211 primitives |
| 2 | `roundRectOutlined` -- nested fills instead of fill + four arc bands | 2211 -> 1836 |
| 3 | static background cached to a `FrameBuffer` | 1188 -> 3 primitives; **p50 532 us vs 614 us, -13%** |

**4400 -> 2719 primitives per frame, -38%.** Frame cost is linear in primitives
at ~0.1 us each, which is the measurement the whole phase turned on.

Phase 11 predicted the castle's brickwork would be the first thing to fix. It was
wrong: the castle is a third of the background's cost and an eighth of the
enemies'.

### Not done, on evidence

Projectile broadphase (16 us median), slam broadphase (8.6 us), crowd-separation
broadphase (46 us at 70 packed enemies), Cannon cluster grid (90 us -- the
dearest scenario, still 0.5% of a frame), shared candidate cache, projectile
pooling, entity pooling, castle brickwork cache. Every one is order-sensitive
gameplay; not touching them is also the cheapest way to keep the parity fixtures
honest.

### Parity

**No simulation code was changed at all.** Insertion order, crowd traversal, hit
order, pierce semantics, splash order, Cannon scoring and ties, slam cooldown
identity, snapshot iteration and RNG order are untouched by construction rather
than by argument. 926 tests green, including the Python fixture suites, render
purity, quality equivalence, skin independence, shake/input and the seeded
smokes.

### The Android gate -- CLOSED

An SDK was installed with approval. The `:android` module entered the build for
the first time since Phase 2 and did not configure, let alone compile. Four real
defects, every one invisible while the module was excluded:

| Defect | Detail |
|---|---|
| `configurations { natives }` below the `dependencies` block using it | Gradle evaluates top to bottom, so `natives "..."` was an unknown method |
| `gdx-backend-android` pulls `androidx.core` unpinned -> 1.17.0 | It demands compileSdk 36 and AGP 8.9.1. The toolchain is pinned deliberately, so the DEPENDENCY was pinned to 1.15.0 -- with `strictly`, since a plain constraint is a floor and gdx asks for 1.17.0 outright |
| `copyAndroidNatives` used `tokenize('-').last()` for the ABI | `...natives-armeabi-v7a.jar` became `v7a`, not an ABI; `mergeDebugNativeLibs` rejected the build. x86 and x86_64 happened to work, which made it look plausible |
| **`Vibrator.vibrate` with no `VIBRATE` permission** | Phase 3's haptics would have failed on every real device. Found by Android lint the moment the module was in the build. `windowLayoutInDisplayCutoutMode` also moved to `values-v27/`, being API 27+ against minSdk 21 |

Result: `android-debug.apk`, 4.90 MB, minSdk 21 / targetSdk 35 / compileSdk 35,
all four ABIs, lint clean. It installs on an emulator, launches and renders.

### One defect only the emulator could find

The menu displayed `DIFFICULTY: !difficulty.normal.name!`. Two faults behind it:
the renderer asked for `difficulty.<id>.name` where the bundle has
`difficulty.<id>`; and **the entire localisation-completeness suite had been
vacuous since it was written** -- `Strings.load` needs `Gdx.files`, which does
not exist headlessly, so it silently fell back to raw keys, and the test's
missing-key check looked for a `???` marker nothing produces. `Strings.loadFrom`
now gives tests an explicit handle, the test asserts the bundle really loaded,
and the check recognises `!key!`.

### Verified, and not

| | |
|---|---|
| Desktop performance | **Verified**, with noise bounds stated |
| Android assembly | **Verified** -- verifyAndroid passes, APK builds, lint clean |
| Android emulator runs | **Verified** -- installs, launches, renders, no crash |
| Android emulator performance | **Not measured** -- SwiftShader software GL says nothing about a phone's GPU |
| Physical device | **Not verified** -- none available. Performance, touch, real cutouts, pause/resume, backgrounding and context loss all untested on hardware |

**No mobile performance target has been met, because none has been measured on a
device.**

### Superseded Phase 11 note



> **Input from Phase 11.** The first thing to profile is the castle's brickwork:
> a few hundred small rectangles redrawn every frame, because the source caches it
> into a surface and there is nothing to cache into here without a framebuffer. It
> was left alone deliberately — caching it now would mean inventing an
> invalidation rule before knowing what the profile says. Nothing else in the
> renderer allocates per entity per frame, and no texture is created during a
> frame at all. Atlas memory is currently **zero**: the shipped game runs on the
> procedural skin and loads no atlas, so the mobile asset budget starts from
> whatever artwork is added rather than from anything already present.

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
