# PORTING_STATUS.md — Castle Defense: Python/Pygame → Java/LibGDX

Authoritative migration checklist. Source of truth is the Python game in this repository
at commit `a7142ab` (`sprites.py`, `castle.py`, `enemies.py`, `main.py`; 9,363 lines).
Analysis: [`docs/PORT_ANALYSIS.md`](docs/PORT_ANALYSIS.md).

Legend: `[ ]` not started · `[~]` in progress · `[x]` ported (compiles, believed correct)
· `[T]` ported **and tested** (JUnit and/or a smoke run proves the behaviour)

Nothing is complete merely because it compiles. A row may only reach `[T]` when a named
test exercises it.

**Phase status: Phases 1 and 3 complete. Phase 2 implemented and hardened but NOT
fully tested — the Android assembly gate is still open because Google's Maven is
unreachable from the build environment, and stays open until
`./gradlew verifyAndroid` succeeds on a machine with the SDK.**

Phase 3 adds no gameplay: no enemies, towers, physics or progression exist yet.

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

- [ ] `Simulation` fixed 1/60 accumulator + `MAX_STEPS`
- [ ] Frame-delta clamp + post-resume guard
- [ ] `GameWorld` entity lists + insertion-order semantics
- [ ] Mark-dead / skip-dead / sweep-after-iteration lifecycle
- [ ] Snapshot iteration where Python relies on it (enemy update, air slams, tornado, blast)
- [ ] `Collisions` primitive AABB helpers (no allocation)
- [ ] `GameState` / `GameMode` enums + freeze rules
- [ ] `GameInput` abstraction
- [ ] `DesktopInput` (mouse → pointer 0, focus-loss release guard)
- [ ] `TouchInput` (multitouch, pointer ownership, cancel handling)
- [ ] `TouchVelocityTracker` (time-based eviction, 90 ms lookback, ±2600 clamp)
- [ ] `InputRouter` UI-before-world consumption

## Phase 5 — Defences

- [ ] `Projectile` (kinds, splash, pierce, gravity, wind, stun, `ownerUid`, `atPrisoner`)
- [ ] Friendly projectile collision (primitive bounds test first)
- [ ] Hostile projectile resolution order (barricade → tower → wall → keep)
- [ ] Crit (talent) and per-target counter multipliers
- [ ] `DefenseTower` base (regen, rebuild, 42 % per-hit cap, stun, aim lerp, lead target)
- [ ] `Bowman` (AIR_RANGE_MULT 1.9 envelope)
- [ ] `Ballista` (+200 % air, pierce, flyer-first scoring)
- [ ] `Cannon` (+200 % heavy, splash, cluster scoring, ballistic solution)
- [ ] Overcharge slingshot (both overchargeable towers, lockout, talent cooldown)
- [ ] `Castle` (6 tiers, uncapped reinforcement, slots, repair, splash, tower smash)
- [ ] `SpikeWalls` (reflect + bleed talent)
- [ ] `Outpost` (garrison, turret upgrade, overdrive, untouchable)
- [ ] `Barricade` (buy/rebuild/reinforce, ground-only blocking)

## Phase 6 — Enemies

- [ ] `Enemy` base state machine (walk/attack/grabbed/air/retrieve/trapped)
- [ ] `waveScaling` + endgame tiers + difficulty scale/curve/speed
- [ ] Armour model + stripping (progress, slow, vulnerability)
- [ ] Grab gating (`grabbable` / `armored` / `shovable` / `tooHeavy`)
- [ ] Throw physics (release power by mass, drag, wind, spin)
- [ ] Fall damage + bounce ladder + stagger
- [ ] Slam damage + knock-on
- [ ] Shove (factor/decay/max)
- [ ] Blocking / queueing (`blocked`) and crowd separation
- [ ] Scout · FootSoldier · ShieldBearer · Berzerker (rage quirk) · SiegeRam
- [ ] Skeleton · Necromancer (standoff, summon, rival bolts, Outpost targeting)
- [ ] Assassin (cloak untargetable, dash) · Gargoyle (flying)
- [ ] Volatile (detonation, chain, wall + barricade damage)
- [ ] TreasureGoblin (flee, timer, silent death)
- [ ] `FriendlySkeleton` ally (march, hold line, sentinels, decay)
- [ ] `UNLOCKS` / `buildWave` / goblin roll / boss insertion / second boss ≥ wave 20

## Phase 7 — Bosses

- [ ] `Boss` base (intro, aura, `fireDelay`)
- [ ] `TrollKing` (crown detach, retrieve, leap, tower smash + stun)
- [ ] `Dragon` (flight, breath stream on a real timer, claws, reel)
- [ ] `LichLord` (staff disarm, raise dead, bone ward, barricade standoff)
- [ ] `DroppedItem` physics (throw caps, bounce, rest, pickup)
- [ ] Regalia guard cooldown growth
- [ ] Attachment-point driven regalia anchors
- [ ] Boss lifecycle purge (sprite, regalia, projectiles, cursor refs, flags)
- [ ] Repeat-boss cleanliness

## Phase 8 — Game progression

- [ ] Classic wave flow (start/end, bonus, restore towers, wave-clear delay)
- [ ] Endless tier clock + spawn ramp + alive cap
- [ ] Endless boss timetable + repeat bosses
- [ ] Gold, kill payout, crowd multiplier
- [ ] Score (distance + airtime × combo), best fling, best combo
- [ ] Challenge Horn (Classic queue, Endless rush, Hard elite pack, once-per-wave/run)
- [ ] Weather: wind, storm, ceiling lightning strikes
- [ ] Screen shake budget
- [ ] Announcements / banners

## Phase 9 — Player progression

- [ ] Shop (11 items, cost curves, availability, discount talent, hotkeys)
- [ ] Tower purchase → upgrade fallback when slots are full
- [ ] Talent tree (38 nodes, 6 branches, tier gating, ranks, all effect getters)
- [ ] Talent income (per wave / per minute / boss bounty)
- [ ] Active skills (unlock order, cooldowns, targeting, casts)
- [ ] Lightning / Meteor + FireZone / Tornado physics
- [ ] Difficulty presets incl. Hard overhaul + grab cooldown + Light Fingers
- [ ] Settings persistence + high score

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
