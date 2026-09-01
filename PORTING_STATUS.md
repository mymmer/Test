# PORTING_STATUS.md — Castle Defense: Python/Pygame → Java/LibGDX

Authoritative migration checklist. Source of truth is the Python game in this repository
at commit `a7142ab` (`sprites.py`, `castle.py`, `enemies.py`, `main.py`; 9,363 lines).
Analysis: [`docs/PORT_ANALYSIS.md`](docs/PORT_ANALYSIS.md).

Legend: `[ ]` not started · `[~]` in progress · `[x]` ported (compiles, believed correct)
· `[T]` ported **and tested** (JUnit and/or a smoke run proves the behaviour)

Nothing is complete merely because it compiles. A row may only reach `[T]` when a named
test exercises it.

**Phase status: Phase 1 (Analysis) complete. Phase 2 not started — no Java exists yet.**

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

- [ ] Gradle multi-project (`core`, `lwjgl3`, `android`)
- [ ] LibGDX + AGP + Java toolchain pinned
- [ ] `CastleDefenseGame` application skeleton
- [ ] World viewport `FitViewport(1280, 720)`
- [ ] Separate UI viewport
- [ ] Android manifest, icons, permissions (none beyond default)
- [ ] Lifecycle hooks (`pause`/`resume`/`dispose`)
- [ ] Desktop launcher runs
- [ ] Android assemble succeeds
- [ ] CI-runnable headless test source set

## Phase 3 — Core infrastructure

- [ ] `GameConfig` (world geometry, physics, scoring constants)
- [ ] `Tuning` (endless timetable, horn, talent income)
- [ ] `DifficultyConfig` + `difficulties.json`
- [ ] `QualityConfig` (LOW/MEDIUM/HIGH, cosmetic only)
- [ ] JSON data loader + validation
- [ ] `GameAssets` / `AssetManager` ownership + disposal
- [ ] `SkinManager` (load / unload / switch)
- [ ] `SkinDefinition`, `UnitVisual`, `AnimationSet`
- [ ] `SkinValidator` (missing units/regions, bad attachments, dup ids, malformed JSON)
- [ ] `gradlew packAssets` TexturePacker task
- [ ] `ProceduralRenderer` fallback wiring (missing art ⇒ warn + draw, never crash)
- [ ] `SaveManager` / `SaveData` / `SaveMigration` (saveVersion 1)
- [ ] `PlatformServices` interface + desktop no-op + Android impl
- [ ] `CrashLogger` (file log in `Gdx.files.local`, global handler)
- [ ] `Strings` localisation table
- [ ] `Rng` + `WeightedPicker`

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

- [ ] Allocation audit of the step loop (target: zero steady-state allocation)
- [ ] Projectile × enemy broadphase
- [ ] Crowd separation bucketing
- [ ] Shared per-frame target candidate list for towers
- [ ] Cannon cluster grid
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
