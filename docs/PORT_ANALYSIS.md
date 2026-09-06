# Castle Defense — Python/Pygame → Java/LibGDX port analysis (Phase 1)

Source of truth: this repository at commit `a7142ab`, four files, **9,363 lines**, all
read in full for this analysis:

| File | Lines | Responsibility |
|---|---:|---|
| `sprites.py` | 639 | Config constants, palette, font cache, draw helpers, text layout engine, asset store, particles |
| `castle.py` | 1,436 | Projectile, towers, Castle, SpikeWalls, Outpost (+ prisoner), Barricade |
| `enemies.py` | 2,186 | DroppedItem, wave scaling, Enemy base + 10 mobs, FriendlySkeleton, 3 bosses, wave composition |
| `main.py` | 5,102 | Logging, difficulty, settings, endless timetable, talents, skills, shop, `Game` (loop/HUD/menus), self-test |

`main(3).py` in the task description is a copy of this repo's `main.py`; the only upload
present in the workspace is the pre-split single-file version from an earlier iteration
(`8e2c9fc6-castle_defense.py`), which is superseded.

---

## 1. Existing architecture

### 1.1 Dependency graph (compile-time)

```
        sprites.py            (imports: math, random, os, pygame)
            ^
            |  from sprites import *
        castle.py             (+ Projectile, towers, Castle, Outpost, Barricade)
            ^
            |  from sprites import *; from castle import Projectile
        enemies.py            (+ Enemy tree, bosses, wave composition)
            ^
            |  from sprites/castle/enemies import *
         main.py              (Game, UI, talents, skills, shop, entry point)
```

Strictly acyclic *at import time*. At **runtime** the graph is fully cyclic through one
object: every entity holds `self.game` and calls back into it.

```
                       ┌──────────────────────────────┐
                       │            Game              │  (main.py, god object)
                       │  wave/gold/score/wind/storm  │
                       │  enemies allies projectiles  │
                       │  items fire_zones tornados   │
                       │  talents skills castle …     │
                       └──────────────────────────────┘
              ▲            ▲           ▲            ▲          ▲
   game.castle│ game.effects│ game.talents│ game.spawn_enemy│ game.wind
              │            │           │            │          │
        ┌─────┴────┐ ┌─────┴─────┐ ┌───┴────┐ ┌─────┴────┐ ┌───┴─────┐
        │ Enemy/*  │ │ Projectile│ │ Tower/*│ │ Outpost  │ │ Tornado │
        └──────────┘ └───────────┘ └────────┘ └──────────┘ └─────────┘
```

Two deliberate inversions worth preserving:

* `castle.py` must never import `enemies.py`. `Outpost` creates friendly skeletons through
  `game.make_ally(x)`, which is defined in `main.py`. → In Java this becomes an explicit
  `AllyFactory` on the world context.
* `Projectile` lives in `castle.py` but is used by enemies (`from castle import Projectile`).
  → In Java it moves to `entity/Projectile.java`, owned by neither side.

### 1.2 Runtime object graph

`Game` owns: `castle` (which owns `towers`), `outpost` (which owns `prisoner` + `cooldowns`),
`barricade`, `spikes`, `talents`, `skills`, `effects`, and seven entity lists:
`enemies`, `allies`, `projectiles`, `items`, `fire_zones`, `tornados`, `bolts`
(plus `banners`, `spawn_queue`, `mouse_hist`).

Cursor state is five mutually exclusive "modes" held directly on `Game`:
`grabbed` (+`grabbed_extra`), `stripping`, `held_item`, `charging`, `smacking`.

### 1.3 Frame structure (`Game.update`, main.py:2096)

```
time += dt; decay shake/combo_flash/storm_flash/horn_glow; age bolts + banners
  ├─ state in (TALENTS, SETTINGS)  → effects only, return
  ├─ state != PLAYING              → castle.flash decay + effects, return   ← freeze
  ├─ update_grab(dt)                                        (cursor modes)
  ├─ endless? update_endless_schedule(dt)                   (clock/tier/boss/spawn)
  ├─ classic spawn from spawn_queue (gated by MAX_ALIVE=58)
  ├─ skills / castle(+towers) / outpost(+prisoner) / barricade(+regen talent)
  ├─ items → prune
  ├─ enemies (over a *snapshot*) → allies → prune allies
  ├─ fire_zones → tornados → prune both
  ├─ separate_enemies(dt)                                   (O(n²) crowd push)
  ├─ projectiles → prune; prune dead enemies
  ├─ effects
  ├─ cursor-reference hygiene (dead grabbed/stripping/item/charging/smacking)
  └─ classic wave-clear check (1.1 s delay) → end_wave()
```

Draw order (`Game.draw`, main.py:2359) is a fixed painter's algorithm:
`bg → outpost → castle(+spikes+towers) → barricade → enemies (flyers first, then ground
sorted by depth, then x) → allies → items → fire_zones → tornados → projectiles →
effects → weather → skill bar → horn → grab cursor` — then the whole scene is blitted with
a random shake offset, and the HUD/menus are drawn *unshaken* on top.

---

## 2. Complete feature inventory

### 2.1 Game states (main.py:940)
`MENU`, `PLAYING`, `SHOP`, `PAUSED`, `GAMEOVER`, `TALENTS`, `SETTINGS`.
Only `PLAYING` advances the world. `TALENTS`/`SETTINGS` still tick `effects`.
`SHOP` in Endless is the **realtime shop** — it freezes clock, spawns and motion (proved by
the self-test: `play_time`, enemy count and every `e.x` must be identical after 120 frames).

### 2.2 Modes
* **Classic** — numbered waves, `build_wave()` composition, shop between waves, wave bonus
  `80 + wave*22 + talents.wave_purse`, +1 talent point per wave, bosses at 5/10/15 then
  every 5th wave on rotation, second (older) boss appended from wave 20.
* **Endless** — tier every 30 s, spawn gap lerps 1.70 s → 0.38 s over 300 s with ±28 %
  jitter, cap 60 alive, bosses at 120/240/360 s then a random one every 120 s,
  +1 talent point per 60 s, shop is a HUD button.

### 2.3 Enemies (10 + 1 ally + 3 bosses)

| Class | HP | Speed | Dmg | Rate | Gold | Mass | Armour | Flags |
|---|---:|---:|---:|---:|---:|---:|---:|---|
| Scout | 26 | 122 | 5 | 0.8 | 6 | 0.8 | — | |
| FootSoldier | 62 | 72 | 10 | 1.0 | 11 | 1.5 | — | |
| ShieldBearer | 160 | 46 | 12 | 1.4 | 19 | 3.0 | 0.60 | |
| Berzerker | 48 | 134 | 24 | 0.7 | 15 | 1.2 | — | rage speed ×(1+0.45·missing hp) |
| SiegeRam | 520 | 28 | 58 | 2.2 | 52 | 9.0 | 0.60 | HEAVY, STRIPPABLE, 3 layers |
| Skeleton | 20 | 92 | 6 | 0.9 | 2 | 0.7 | — | summoned |
| Necromancer | 90 | 54 | 14 | — | 26 | 1.4 | — | TRAPPABLE, standoff 400±, summons ≤4 |
| Assassin | 58 | 96 | 20 | 0.65 | 24 | 1.0 | — | cloak (untargetable), dash ×3.6 |
| Gargoyle | 78 | 92 | 13 | 0.9 | 26 | 1.1 | — | FLYING, fly_y 232 |
| Volatile | 46 | 84 | 8 | 1.1 | 16 | 1.1 | — | detonates r=132, ×3.4 dmg |
| TreasureGoblin | 70 | 104 | 0 | — | 140 | 0.9 | — | flees, 11 s timer, silent death |
| FriendlySkeleton | 18.4 | 78 | 13 | 0.85 | — | — | — | **not** an `Enemy`; `game.allies` |
| TrollKing | 1150 | 34 | 42 | 2.2 | 320 | 12 | 0.25 | crown, leap, tower smash+stun |
| Dragon | 1200 | 66 | 34 | 2.6 | 520 | 14 | 0.15 | FLYING, breath stream, claws |
| LichLord | 2900 | 36 | 24 | 1.6 | 780 | 10 | 0.30 | staff, raise dead, bone ward |

Scaling: `hp=1.14^(w-1)`, `dmg=1.11^(w-1)`, `spd=min(1.70, 1+0.026(w-1))`; endgame tiers
Bloodied(16)/Frostbound(26)/Voidtouched(36) multiply hp/dmg/spd and tint the colour.

### 2.4 Player systems
Throw physics (grab → follow cursor → velocity-sampled release → gravity/drag/wind →
fall damage → bounce ladder → slam damage → stagger), armour stripping, shoving,
Magnetic Gloves multi-grab, boss disruption (crown / staff / claws with a growing guard
cooldown), tower overcharge slingshot, Challenge Horn, scoring (distance + airtime ×
combo), crowd gold multiplier, weather (wind + thunderstorm ceiling strikes),
6-branch/38-node talent tree, 3 active skills, 11 shop items, 3 difficulties, grab
cooldown, settings + high score persistence, crash logging.

### 2.5 Data tables that must survive verbatim
`ENDGAME_TIERS`, `BOUNCE_RESTITUTION`, `GRAB_CAPACITY`, `BARRICADE_HP`, `Castle.TIERS`,
`Castle.SLOTS`, `UNLOCKS`, `BOSS_ROTATION`, `ENDLESS_BOSS_SCHEDULE`, `HARD_HORN_UNITS`,
`DIFFICULTIES`, `TALENTS` (38 nodes), skill constants, and ~120 loose tuning constants in
`sprites.py`.

---

## 3. Performance hotspots (measured/documented in the Python source)

| Rank | Path | Cost | Java plan |
|---|---|---|---|
| 1 | `Projectile._update_friendly` (castle.py:144) | O(P×E)/frame. Comment records that allocating a `pygame.Rect` here "was the single biggest cost in the profile at high waves" | Keep the primitive AABB test; iterate an array-backed list; no allocation |
| 2 | `Enemy._update_air` slam loop (enemies.py:613) | O(E) per airborne mob **plus a `list()` copy per mob per frame** | Snapshot once per frame into a reusable array; uid-keyed cooldowns in an `IntFloatMap` |
| 3 | `Game.separate_enemies` (main.py:2198) | O(n²) over ground mobs, n≤60 → ~1.8 k pair tests/frame | **Port verbatim.** It mutates `x` mid-traversal, so insertion order, pair order and mutation timing are all observable. Bucketing by `depth` is a *candidate* for Phase 12 and only if proven equivalent under parity tests |
| 4 | `Cannon.score_target` (castle.py:630) | O(E) inside `pick_target`'s O(E) → O(E²) per cannon | **Port verbatim.** A shared per-frame cluster grid is a Phase 12 candidate, gated on proving it picks the same target in the same order |
| 5 | `DefenseTower.pick_target` | O(E) per tower per frame (up to 9 towers) | **Port verbatim.** A shared candidate list is a Phase 12 candidate, gated on equivalence |
| 6 | Per-draw `pygame.Surface` allocations for glows (Volatile, Dragon aura, Lich ward, magic/fire projectiles, ally halo, shadows) | A new surface + fill every frame per entity | Pre-baked radial-gradient texture drawn additively; zero per-frame allocation |
| 7 | `font.render` per text draw | New surface per call, every frame | `BitmapFont` + `GlyphLayout` cache; measured layout cached per string |
| 8 | `sorted(self.enemies)` per frame in `draw` | allocation + comparator | Sort a reusable array with a primitive comparator |
| 9 | `Effects` particle list rebuild per frame (≤900) | list churn | Pooled particles in an array with swap-remove |
| 10 | `hit_rect` property | allocates a Rect on every access | Primitive `x/y/w/h` accessors; a scratch `Rectangle` only where APIs demand it |

> **Sequencing rule (set in Phase 2 review).** Rows 1–2, 6–10 are representation
> changes that cannot alter behaviour and are safe during the initial port. Rows
> 3–5 change *traversal or ordering* of algorithms whose results depend on it, so
> the initial port implements the Python algorithm literally and they are
> deferred to Phase 12, each behind a parity test that proves equivalence.
> Correctness before optimisation, without exception.

Non-obvious but important: `Castle.draw` calls `random.seed(1337)` … `random.seed()` every
frame while hp < 66 %, and `_build_background` seeds 7 — i.e. **rendering perturbs the
global gameplay RNG**. Java uses a dedicated `Random` for decoration, so the gameplay
stream is unaffected (allowed by the brief's §37).

---

## 4. Pygame → LibGDX mapping

| Pygame | LibGDX | Notes / risk |
|---|---|---|
| `pygame.Surface` + `blit` | `SpriteBatch` + `TextureRegion` | Offscreen composites → `FrameBuffer` only where needed (castle body cache) |
| `pygame.draw.rect/circle/line/polygon/ellipse/arc` | `ShapeRenderer` (Filled/Line) + custom helpers | **`border_radius` has no equivalent** → `RoundedRect` helper (two rects + 4 arc fans), used by ~80 call sites |
| `Surface(SRCALPHA)` + `set_alpha` | Batch colour alpha | Straightforward |
| `BLEND_RGBA_MULT` then `BLEND_RGBA_ADD` (castle damage flash) | Additive pass with tinted colour | Visual match, not bit-exact |
| `pygame.transform.smoothscale` | Linear-filtered texture draw | |
| `pygame.Rect.collidepoint/colliderect/inflate/clip` | Primitive float math + `Rectangle` for UI only | Keep gameplay allocation-free |
| `pygame.font.Font(None, size)` | `FreeTypeFontGenerator` → `BitmapFont` per size | **Metrics differ** → the measured-layout engine (HUD stack, panel fitter, talent grid, word wrap) must query the real font; layout invariants become JUnit tests over the Java metrics |
| `pygame.time.Clock.tick(60)`, `dt=min(0.05,…)` | Fixed-step accumulator (1/60) | Determinism improvement; all logic is already dt-based |
| Event queue (`MOUSEBUTTONDOWN/UP/MOTION/KEYDOWN/QUIT`) | `InputProcessor` + `InputMultiplexer` | UI layer gets first refusal, mirroring `skills.handle_click` → `shop_btn` → `HORN_RECT` → `try_grab` |
| `pygame.mouse.get_pressed()` focus-loss guard in `run()` | `pause()`/`resume()` + pointer-cancel | Android needs this more, not less |
| `random.choices(weights=…)` | `WeightedPicker` over a shared `RandomXS128` | Distribution preserved, sequence not |
| `json` settings beside the script | `Preferences` + versioned JSON in `Gdx.files.local` | Never write next to assets |
| `logging` + `RotatingFileHandler` | `CrashLogger` → `Gdx.files.local("logs/game_errors.log")` + `Gdx.app.error` | Same "capture the traceback" purpose |
| `assets/<name>.png` auto-discovery + procedural fallback | `SkinManager` + `TextureAtlas` + `ProceduralRenderer` fallback | The fallback *is* the current look and must stay pixel-recognisable |

---

## 5. Proposed Java architecture

Package root `com.mymmer.castledefense`. Guiding rule: **gameplay classes carry no
rendering code and no platform types.**

> **Java API policy** (corrected during the Phase 2 hardening pass, refined in
> Phase 4). Four things are distinct and must not be conflated: the JDK that runs
> Gradle/AGP (17), the Java source/target level (17), the Android platform API
> floor (minSdk 21), and the JDK library APIs supplied by *core-library
> desugaring* (enabled). A fifth practical point: JVM modules set that level with
> `options.release`, while `:android` sets it through
> `android.compileOptions` — AGP owns Android compilation, and forcing
> `--release` onto its tasks bypasses the `android.jar` bootclasspath and the
> desugaring contract. Because
> desugaring is configured, `java.time`, `java.util.stream`, `Optional` and
> `java.util.function` are available down to API 21 and are **not** banned.
> What is restricted is narrower and is about performance, not compatibility:
> inside per-frame paths (simulation step, collision, particles, projectiles)
> prefer allocation-free Java — indexed loops, primitives, no streams, no
> capturing lambdas. Outside them, use whatever reads best. The Python classes mix `think()` and `draw_body()`;
the port splits them — behaviour stays in `enemy/`, appearance moves to `render/painter/`
keyed by an `EnemyType` enum. That is the single biggest architectural improvement and it
changes no gameplay.

```
core/src/main/java/com/mymmer/castledefense/
  CastleDefenseGame.java            ApplicationAdapter; owns screens + services
  config/
    GameConfig.java                 sprites.py constants (world geometry, physics, scoring)
    DifficultyConfig.java           the Difficulty record + the 3 presets (data-loaded)
    QualityConfig.java              LOW/MEDIUM/HIGH — cosmetic caps only
    Tuning.java                     endless timetable, horn, talents-per-wave …
  game/
    GameWorld.java                  entity lists + systems (was Game's simulation half)
    WorldContext.java               interface entities see (wind, talents, effects, spawn…)
    Simulation.java                 fixed-step accumulator, MAX_STEPS guard
    GameState.java  GameMode.java   enums
    Session.java                    gold, score, wave, stats, horn, weather
    WaveDirector.java               build_wave + classic pacing
    EndlessDirector.java            tier clock, boss timetable, spawn ramp
    BossLifecycle.java              purge/clear (the boss-recurrence fix)
  entity/
    Entity.java  Projectile.java  DroppedItem.java  Ally.java
  enemy/  (Enemy.java + 10 mobs)     behaviour only
  boss/   (Boss.java + 3 bosses)     behaviour only
  defense/ (Castle, DefenseTower, Bowman, Ballista, Cannon, Barricade, Outpost, SpikeWalls)
  progression/
    Talent.java TalentTree.java TalentEffects.java
    Skill.java SkillPanel.java LightningStrike/MeteorShower/TornadoSkill
    Shop.java ShopItem.java
  input/
    GameInput.java DesktopInput.java TouchInput.java TouchVelocityTracker.java
    InputRouter.java                UI-vs-world consumption
  render/
    GameRenderer.java WorldRenderer.java UiRenderer.java HudRenderer.java
    ProceduralRenderer.java          the hand-drawn fallback (1:1 with pygame draws)
    ShapeKit.java                    rounded rects, arcs, bars, glows
    painter/…                        one painter per visual type
  assets/
    GameAssets.java SkinManager.java SkinDefinition.java UnitVisual.java
    AnimationSet.java SkinValidator.java
  effects/ Effects.java Particle.java FloatingText.java ParticlePool.java
  text/    Strings.java (localisation) TextLayout.java (wrap/fit engine)
  persistence/ SaveManager.java SaveData.java SaveMigration.java
  platform/ PlatformServices.java Haptics.java
  debug/   DebugOverlay.java PerformanceStats.java
  util/    Rng.java WeightedPicker.java Collisions.java Pools.java
```

Android-only code stays in `android/`; desktop launcher + device-frame simulation in
`lwjgl3/`. `core` never references `android.*` or LWJGL.

---

## 6. Fixed-timestep design

Implemented in Phase 4 as `game/Simulation.java`:

```java
public static final float DT        = 1f / 60f;   // = GameConfig.SIMULATION_STEP
public static final int   MAX_STEPS = 5;          // 83 ms of catch-up, then drop time
// the accumulator is a double, deliberately -- see the drift note below
accumulator += clampAndSanitise(frameDelta);      // ≤ 0.25 s; NaN/∞/negative → 0
int steps = 0;
while (accumulator >= DT && steps < MAX_STEPS) { stepper.step(DT); accumulator -= DT; steps++; }
if (steps == MAX_STEPS) { accumulator = 0.0; droppedStepEvents++; }
// time is stepCount * DT.  Never a summed delta, never wall-clock.
renderer.render(viewports, alpha());              // alpha = accumulator / DT
```

**This is an intentional behavioural change, not a free win.** Python used a
*variable* step capped at 50 ms, so a 40 ms frame advanced every timer by exactly
40 ms; the port advances it by two 16.67 ms steps and carries 6.7 ms forward.
Every timer is `-= dt` in both, so the *shape* of the logic is unchanged, but the
moment a threshold is crossed can differ by up to one step. Phase 13 parity tests
must therefore measure, at minimum: attack and reload timers, projectile impact
frames, fall-damage and bounce thresholds, cooldown boundaries (including the new
difficulty grab cooldowns), spawn cadence, boss phase and fire timers, and
wave-clear delays. Gameplay constants are **not** pre-adjusted to compensate —
differences get measured against the Python reference first, and only then, if at
all, tuned.

Two implementation notes worth keeping:

* **The accumulator is a `double`.** `DT = 1f/60f` is fractionally larger than a
  true sixtieth, so a `float` accumulator drifts measurably over a long Endless
  run; 3600 steps read 60.0000031 s rather than 60 s. The `double` keeps the
  error at ~5.2e-8 s per 60 steps, which is documented in `Simulation`'s javadoc
  and is why `SimulationTest` asserts 59-or-60 steps for 144 frames at 1/144
  rather than pretending the arithmetic is exact.
* **Dropped time is dropped, not banked.** Hitting the step budget discards the
  remainder; banking it produces a spiral of death on a slow device.

`pause()` cancels every pointer and persists settings; `resume()` calls
`resetAccumulator()`, so a pause that lasted hours contributes nothing — the stale
partial step from before it is discarded rather than replayed. Only `PLAYING`
advances the world (`GameState.advancesWorld()`), which is also what freezes the
Endless realtime shop.

---

## 7. Input abstraction

`GameInput` exposes only logical pointer state:
`pointerDown(id)`, `pointerWorld(id)`, `pointerDelta(id)`, `pointerVelocity(id)`,
`justPressed`, `justReleased`, `activeOwner`. Desktop maps mouse → pointer 0 (with the
`get_pressed()` focus-loss guard preserved); Android maps touches 1:1.

**Ownership.** The five cursor modes become one `Interaction` object owning a pointer id.
A second finger cannot steal or cancel an active grab; releasing the *owning* pointer
releases the mob. Pointer-cancel (Android) is treated as release.

**UI vs world.** `InputRouter` runs consumers in the Python order — skill bar → HUD shop
button → horn → world grab — and a consumed touch is recorded per pointer id so its
subsequent move/up events never reach gameplay.

**Velocity.** Python samples `deque(maxlen=12)` and uses the oldest sample within 90 ms,
clamped to ±2600 px/s. At 240 Hz touch reporting 12 samples only span 50 ms, which would
*halve* effective throw power on flagship phones. `TouchVelocityTracker` therefore evicts
by **time** (keep ≥120 ms), samples in **world** coordinates, and applies the same 90 ms
lookback and ±2600 clamp. Same numbers, device-independent.

---

## 8. Resolution, viewports, safe area

* World: `FitViewport(1280, 720)` — gameplay coordinates identical to Python, letterboxed
  on any aspect. All constants (`GROUND_Y=620`, `CASTLE_FRONT=252`, `OUTPOST_X=1015`,
  `BARRICADE_X=585`, `HORN_RECT`) stay literal.
* UI: separate `ExtendViewport(1280, 720)` so HUD can use the extra width of 20:9 phones
  without moving the world.
* `SafeArea` supplies insets (Android cutouts/gesture bars via `PlatformServices`, faked on
  desktop). The HUD panel, skill bar, horn and shop button anchor inside the safe rect;
  background art may bleed outside it.
* Touch targets: every interactive rect has a *visual* rect and a `hitRect` inflated to a
  minimum 48 dp equivalent (~9 mm), independent of the drawn size.

---

## 9. Data-driven configuration

JSON under `assets/data/`:
`enemies/*.json` (15 stat blocks), `towers/*.json`, `difficulties.json`, `talents.json`
(38 nodes), `skills.json`, `waves.json` (UNLOCKS + budget + goblin chance + boss rotation),
`endless.json` (timetable), `castle_tiers.json`, `barricade.json`.

Behaviour stays in Java: rage curves, cloak/dash state machines, breath streams, crown
retrieval, tornado physics. Only numbers move out. Every file is loaded once at start,
validated, and frozen into immutable config objects — no per-frame JSON access.

---

## 10. Skin / atlas / attachment architecture

```
raw-assets/skins/<skin>/*.png   →  gradlew packAssets  →  assets/skins/<skin>/units.{atlas,png}
```

`skin.json` per skin:

```json
{
  "id": "default", "version": 1,
  "units": {
    "troll_king": {
      "region": "troll_king", "scale": 1.2, "offsetX": 0.0, "offsetY": 0.0,
      "attachments": { "crown": { "x": 0.50, "y": 0.91 } },
      "animations": { "walk": { "frames": 4, "fps": 8 }, "attack": { "frames": 3, "fps": 10 } }
    }
  }
}
```

* Attachment points are **normalised** to the gameplay box, so `TrollKing.java` asks for
  `visual.attachment("crown")` and never knows the artwork's pixel size. The Python
  `regalia_anchor()` values become the default attachment table for the procedural skin.
* Gameplay geometry (`W/H`, `hitRect`, `grabRect`, `smackRect`) comes from
  `config/enemies/*.json`; visual scale/offset comes from the skin. A new skin cannot move
  a hitbox.
* Animation states: `IDLE, WALK, ATTACK, HURT, GRABBED, AIR, DEAD` (+ boss extras:
  `BREATHE, LEAP, CAST, REEL, RETRIEVE, DISARMED`). Gameplay sets a state; a static skin
  maps every state to the single region.
* `SkinValidator` reports missing units, missing regions, bad attachment ranges, duplicate
  ids, malformed animation blocks. Missing artwork ⇒ warning + `ProceduralRenderer`, never
  a crash.

---

## 11. Persistence

```json
{ "saveVersion": 1, "settings": { "muted": false, "difficulty": "normal",
  "quality": "HIGH", "haptics": true, "skin": "default" },
  "highScore": 0, "unlocks": {} }
```

`Preferences` for the small settings; a versioned JSON blob in `Gdx.files.local` for
anything structured. `SaveMigration` is a chain `v(n) → v(n+1)`; unknown/corrupt data falls
back to defaults and the game still starts (mirrors the Python "never let a failed write
break the game" rule).

Save v1 keeps the Python semantics exactly: **one shared `highScore`** across both
modes, because that is what the game currently does and the port changes no
gameplay. Splitting it per mode is a gameplay change, so it waits for an explicit
request and would arrive as a v1 → v2 migration.

---

## 12. Test plan (JUnit, headless)

The Python self-test is 30 assertion groups (~1,680 lines). Every one maps to a JUnit test
against a headless `GameWorld` (no rendering):

counters · air-range envelope · armour stripping (armour/speed/vulnerability) · boss
immunity · crown retrieval + guard growth · staff disarm + recovery · crowd gold ·
fling scoring + combo · bounce ladder · grab gating (armour → shove → lift) · shove
kinematics · trap/betrayal · ally march + hold line · ally duel balance · rival bolt
targeting · rival standoff at the Outpost · grab cooldown ladder + Light Fingers ·
Undead Sentinels chase · talent gating/caps/effects · Storm Winds · talent point income ·
skills (lightning/meteor/tornado) + cooldown talents · iteration safety · horn rules ·
two live bosses · infinite wall/outpost progression · difficulty scaling · Hard overhaul ·
boss recurrence purge · Endless timetable/spawn ramp/realtime shop freeze · Classic wave
break · Volatile blast · Goblin escape · endgame tiers · Dragon claws · wind drift ·
storm ceiling · overcharge · spikes · barricade air/ground.

Layout invariants (HUD non-overlap, panel fitting, talent grid) become tests over the
**Java** font metrics, run in a headless GL context (`HeadlessApplication` + a stub
`GlyphLayout` provider) so they run in CI.

---

## 13. Behaviours to reproduce exactly (do not "fix")

1. `Berzerker.think` recomputes speed from `BASE_SPEED × wave_scaling(wave)[2] × rage`,
   which **discards** the difficulty speed multiplier and the endgame tier speed multiplier
   for that frame, then restores the old value.
2. `Assassin.think` multiplies the *already slowed* speed by the dash boost and restores it.
3. `enemy_slow` is applied by mutating `speed`, then dividing it back at the end of `think`.
4. `FriendlySkeleton` is not an `Enemy`: towers never target it, it never reaches the castle,
   and it does not count toward wave clearing.
5. `Outpost.trap` removes the enemy from `enemies` **mid-iteration** — the snapshot loop is
   load-bearing.
6. `Volatile.die` detonates *after* `super().die()`, so chains are possible and each blast
   pays its own gold at the multiplier of that instant.
7. The Challenge Horn resets only in `start_wave` / `begin_endless` — in Endless it is
   therefore **once per run**, not once per tier.
8. `Enemy.die` computes `gold_multiplier` *including the dying mob*.
9. `separate_enemies` mutates `x` while iterating pairs, so results depend on list order
   (= spawn order). The Java port keeps insertion order and the same nested loop.
10. `TreasureGoblin.escape` → `die(silent=True)`: no gold, no kill count, no fling score.
11. `LichLord.take_damage` applies the 0.25 ward multiplier *before* armour reduction.
12. Bounce: `bounce_count > lvl` ends the rebound chain, so Lv.0 lands exactly once.
13. `grab_capacity` indexes `GRAB_CAPACITY[level]` with level clamped 0..4 and multiplies by
    the Light Hands talent — so the talent can lift a Siege Ram one level early.
14. Multi-grab release jitters each extra mob's velocity by ×0.85–1.15.
15. Dragon claw smacks share `regalia_taken` with the crown/staff guard growth.

---

## 14. Known behavioural differences (unavoidable, documented)

Both deviations below were reviewed and approved at the end of Phase 1. Neither
is claimed as bit-for-bit parity; gameplay probability distributions and observable
behaviour are preserved.

| Area | Difference | Why |
|---|---|---|
| RNG sequence | Different stream | §37 permits; distributions preserved |
| Decoration RNG | Castle cracks / stars use a private `Random` | Python's `random.seed(1337)` inside `draw` perturbs the gameplay stream; isolating it is strictly better and invisible |
| Font metrics | Text pixel widths differ slightly | Different font rasteriser; the layout engine is measurement-driven, so layouts adapt |
| Rounded rects / arcs | Redrawn with a helper | No `border_radius` in `ShapeRenderer` |
| Timestep | Fixed 1/60 vs variable ≤50 ms | Determinism; identical at the 60 fps the Python game targets |
| Blend of castle damage flash | Additive tint approximation | `BLEND_RGBA_MULT`+`ADD` composite has no direct batch equivalent |

### Findings in the Python source, reproduced rather than fixed (Phase 5)

Two things turned up while porting the defences that look like bugs in the
reference. Both are **reproduced as-is**, because the brief is a port and
"fixing" a source behaviour silently would make later parity testing meaningless.
Each is recorded here so it can be decided on deliberately, after parity.

| Finding | Detail | Status in the port |
|---|---|---|
| `TalentTree.castle_hp` is dead code | `main.py:471` defines `castle_hp = 1 + value("maxhp")`, and **nothing reads it**. The "Deep Foundations" talent (5 ranks, +10% each) therefore does nothing to the castle's health. | Not implemented. `CombatModifiers` has no `castleHp()`, so nothing in the port pretends to apply it either. Adding it would be a balance change, not a port. |
| Overcharge power is read from a live cursor position | `Game.release_grab` reads `overcharge_power()` *before* clearing `self.charging`, with a comment saying so — a fragile ordering that has clearly bitten before. | The port removes the hazard structurally rather than by comment: `overchargeFire(aimX, aimY, power)` takes the power as an argument, so there is no state to clear in the wrong order. The formula itself is unchanged and is asserted in `OverchargeTest.powerFormula`. |

### Source quirks reproduced in Phase 6

Each of these looks like a defect and each is authoritative. All are reproduced,
and each has a **named** regression test so a later cleanup has to argue with it
rather than silently changing the game.

| Quirk | Detail | Test |
|---|---|---|
| **Berzerker discards difficulty and tier speed** | `Berzerker.think` recomputes `speed = BASE_SPEED × wave_scaling(wave)[2] × rage` instead of scaling the value it holds. Phase 1 recorded this as affecting the rage behaviour; it is in fact broader — the recompute runs on **every** call, so its stored speed (which does carry difficulty and the endgame tier) never moves it at all. On Hard at wave 36 the discarded factors are 1.4 × 1.16 = 1.624, more than the 1.45 rage cap, so a Berzerker there is slower than its own stat block at any health. | `EnemySpeedTest.berzerkerRageDiscardsDifficultyAndTierSpeed` |
| **The gold multiplier counts the dying mob** | `Enemy.die` reads `game.gold_multiplier` before setting `alive = False`, so the mob about to die is still in the crowd count. Removing it first would quietly cut every payout. | `EnemyBehaviourTest.goldMultiplierIncludesTheDyingMob` |
| **The bounce chain ends on `count > level`** | Strictly greater, so level 0 never rebounds at all. `>=` reads more evenly and would give every level one extra bounce. | `EnemyPhysicsTest.bounceLevelZeroDoesNotRebound`, `.bounceLadder` |
| **`Outpost.trap` removes a live entry mid-iteration** | The enemy loop must iterate a snapshot or the mob after the captured one is skipped — silently, because a list does not complain. | `EnemyBehaviourTest.trapDuringSnapshotDoesNotSkip` |
| **Temporary speed mutation** | The talent slow multiplies and divides one shared mutable field; the Berzerker and Assassin save-overwrite-restore around it. They compose through that field in an order that is observable, and the divide-back is not bit-exact. | `EnemySpeedTest` (five tests) |

### Newly discovered in Phase 6

| Finding | Detail | Status |
|---|---|---|
| **A Volatile blast cannot chain among healthy Volatiles** | The blast does `damage × 3.4` = 27.2 at wave 1; a Volatile has 46 health. Worse, it never closes: health scales at 1.14 per wave and damage at 1.11, so the gap only widens (wave 30: 561 blast against 2056 health). Chain reactions are therefore something the player *sets up* by softening a row first, not something a single kill triggers. | Reproduced exactly. The chain tests pre-damage the row, which is the situation the mechanic actually occurs in. Not a port bug and not "fixed"; recorded so a future balance pass knows the blast is far below the one-shot threshold. |
| **`TalentTree.castle_hp` remains dead code** | Confirmed again in Phase 6: nothing reads it. | Still not implemented. |

### Source quirks reproduced in Phase 7

| Quirk | Detail | Test |
|---|---|---|
| **Claw batterings grow the *regalia* guard** | `Dragon.apply_smack` increments `regalia_taken` and calls `guard_regalia()` — the crown/staff mechanism — even though claws are not regalia and nothing is taken away. The observable effect is that repeated batterings get harder on the same 9.6 / 13.2 / 16.8 s ladder as stealing a crown. Reproduced; the Java name is generalised to "disruption" where it reads better, the behaviour is not. | `BossMechanicsTest.clawSmacksShareTheRegaliaGuard` |
| **The guard ladder starts at 9.6 s, not 6 s** | `regalia_taken` increments at *detach* and `guard_regalia()` is called at *recovery*, so by the time a guard exists the count is already 1. `REGALIA_COOLDOWN × (1 + 0.6 × 0)` = 6.0 is a state that never occurs. Easy to get wrong from the constant alone. | `BossMechanicsTest.guardGrowth`, `BossParityTest.regaliaGuardLadder` |
| **A crown must be at rest to be recovered** | `TrollKing.retrieve_crown` requires `crown.state == "ground"`, so a crown still in the air is out of reach even when he is standing on it. A good throw therefore buys time after he arrives, not only before. | `BossMechanicsTest.crownMustBeAtRest` |
| **The purge runs from the defeat hook** | `Enemy.die` → `game.on_boss_defeated` → `game.purge_boss`, not on a later tick. Deferring it lets the item list be swept before the cursor is told to let go, leaving the player holding a corpse's crown. Found by a failing test during the port. | `BossLifecycleTest.purgeDropsEverything` |

### Newly discovered in Phase 7

| Finding | Detail | Status |
|---|---|---|
| **The Lich ward ordering is not observable** | Phase 1 recorded "ward × 0.25 → armour" as an ordering that must be preserved because reversing it changes the result. It does not: both steps are pure multiplies and ×0.25 is an *exact power of two*, so the two orders agree bit for bit — verified across 100 float cases and all 18 fixtured ones. The order is still preserved, because it stops being equivalent the moment armour gains a floor, a cap or a flat subtraction. The test says so plainly rather than claiming to detect a difference that does not exist. | Order preserved; the claim corrected. `BossParityTest.wardOrdering` asserts the two agree, and fails if that ever changes. |
| **Dragon breath: one extra fireball on Hard** | ~~Python's timers are doubles; the port's are floats.~~ **RESOLVED before Phase 8 — see §14.1.** The port fired 16 fireballs on Hard where Python fires 15. It now fires 15. The cause was the precision of the timer type, and the fix was architectural rather than a balance tweak: the whole time domain moved to `double`. | Fixed, not accepted. `BossParityTest.shippedHardBreathIsFifteen` asserts 15 in the standalone loop *and* in a live Dragon; `floatTimersAreWhyThisRuleExists` keeps the single-precision counts as a regression demonstration. |
| **A Volatile blast still cannot chain unaided** | Unchanged from Phase 6; re-confirmed. | Recorded above. |

### Newly discovered in Phase 8

| Finding | Detail | Status |
|---|---|---|
| **A Volatile detonation could read past the end of the roster** | `Volatile.detonate` cached `targetCount()` and walked the **live** horde by index. Python walks `list(g.enemies)` — a copy — and the reason turns out to be load-bearing: the blast can kill a **boss**, and a boss's death purges it and its debris from the roster on the spot, so the list shrinks mid-loop. Before Phase 8 nothing could remove a horde entry during a detonation, so the defect was latent; the first undefended Endless run found it in seconds. | Fixed: the loop now iterates an `EntityList.Snapshot`, the same way the airborne slam loop already did. Covered by the Endless long-run tests. |
| **The Endless alive cap does not cap the population** | `ENDLESS_MAX_ALIVE` gates only the director's own trickle. Necromancer summons, Lich raises and the horn all add mobs without consulting it, so a busy field genuinely exceeds 60 — in Python too. A test asserting a hard ceiling would be asserting something the source does not do. | Recorded; `EndlessFlowTest.aliveCap` asserts the real behaviour, which is that the *trickle* stops. |
| **A summed run clock is a step out at every boundary** | `play_time += dt` over 1800 steps reads 29.999999999999577, which puts the tier ladder, the boss timetable and the talent drip one step late — and the error grows. | Fixed by deriving `playTime()` from a step count, per §14.1's invariant 2b. An hour now reads exactly 3600.0 and awards exactly 60 talent points. |
| **The Classic and Endless alive caps genuinely differ** | 58 (`enemies.py:2131`) and 60 (`main.py:241`). It reads like a typo and is not; both are preserved. | Recorded, and pinned by a named test. |

### Newly discovered in Phase 9

| Finding | Detail | Status |
|---|---|---|
| **`TalentTree.castle_hp` is dead code** | Phase 5 suspected it; Phase 9 confirmed it by implementing the tree. `grep castle_hp main.py castle.py enemies.py` returns exactly one hit — its own definition. So **Deep Foundations changes no health at all**, at any rank, and the node's description is a lie the shipped game tells. | Reproduced. `TalentTree.castleHpClaim()` exposes the number for a tooltip and is deliberately NOT a `CombatModifiers` method, so nothing in gameplay can reach it. `TalentQuirksTest` asserts both the behaviour and the structure — adding a `castleHp()` to the shared interface fails the build. |
| **Crowd Financier raises the gold CAP, not just the slope** | `min(POP_GOLD_CAP * gold_pop, 1 + step * n)` — the talent multiplies **both** terms. Reading it as a cap on the base rate alone would silently halve the talent at high populations, which is exactly where a player buys it. | Reproduced and pinned by a named test. |
| **The shop's tower fallback upgrades EVERY tower of that type** | With the wall full, `buy_tower` iterates `for x in existing: x.upgrade()`. Not the weakest, not the nearest — all of them. Buying a fourth Bowman makes all three existing Bowmen better, which makes a full wall a deliberate strategy rather than a dead end. | Reproduced. No "better" selection strategy invented. |
| **Float cost constants change a price by a coin** | `150f * 1.4f` is 209.9999964 and truncates to **209** where Python charges **210**. Same class of error as the Dragon breath, in the economy rather than the clock. | Fixed: every shop cost constant is a `double`, read through `Json5.exact`. Fixtured at 63 curve points. |
| **The discount is applied in the DRAW loop** | `main.py:3192` assigns `item.discount` while rendering the shop, so a price technically depends on the screen having been drawn. Harmless in the source because the screen is always drawn before a click. | Deviated deliberately and documented: the port computes the discount live at the point of sale, which is the same observable behaviour with no rendering in the path. |
| **The structures survived a new run** | Not a source quirk — a port defect Phase 9's reset tests found. `RunWorld` built the castle, outpost, barricade and spikes once in its constructor; Python's `Game.reset()` constructs new ones every run. A tower bought in one run was still standing in the next. | Fixed by reconstructing them in `startRun`, which is what the source does and what guarantees no field is forgotten. |

---

## 14.1 The time-domain rule (pre-Phase 8)

**Gameplay time is `double`. Spatial simulation is `float` where appropriate.**

```
time domain    -> double     durations, deadlines, cooldowns, intervals,
                             elapsed and remaining time, schedules
spatial domain -> float      x/y, velocities, dimensions, angles,
                             collision geometry, rendering-facing state
```

### Why

The Dragon's Hard breath was the first *observable* symptom, not the disease.
`1f/60f` is 0.016666668 — fractionally **longer** than a true sixtieth — so a
float timer that subtracts it walks away from the truth a little every step, and
at a comparison boundary that becomes a whole extra event. Measured across the
six fixtured breath configurations, **three diverged**, and not all in the same
direction:

| fireScale | breath | interval | Python (double) | float32 | delta |
|---|---|---|---|---|---|
| 1.0 | 1.25 | 0.15 | 8 | 8 | — |
| 1.0 | 2.00 | 0.25 | 8 | 8 | — |
| 1.0 | 0.50 | 0.15 | 4 | **3** | −1 |
| **0.5** | **1.25** | **0.15** | **15** | **16** | **+1** (shipped Hard) |
| 0.5 | 2.00 | 0.25 | 16 | 16 | — |
| 0.5 | 0.50 | 0.15 | 7 | **6** | −1 |

A divergence that goes both ways cannot be compensated for by adjusting a
constant. The type was wrong.

### What the rule is not

* **Not integer ticks.** §6 deliberately rejected making every duration a step
  counter, and that decision stands: the source expresses durations in seconds,
  many are random or configurable fractions, and `simulationStep` remains useful
  for tracing and reproducibility without every timer becoming one.
* **Not epsilons.** Source comparison operators are preserved exactly — `<= 0`
  stays `<= 0`. No `EPSILON` was introduced to make a parity test green.
* **Not a global conversion.** Positions, velocities, angles and drawing state
  stay `float`. So do rates that are not times: `regen` is HP per second,
  `breathPower` is a damage fraction, `shove` is a velocity.
* **Not two clocks.** `Simulation.FIXED_DT` (double) and `Simulation.PHYSICS_DT`
  (float) are the *same* step at two precisions. `step(double dt)` receives the
  canonical one; a system that integrates space narrows it once, itself, with
  `float fdt = (float) dt`. No production class reads `PHYSICS_DT` —
  `TimeDomainTest` fails the build if one starts to.

### What it does not claim

A `double` countdown does not land exactly on the mathematical step, because
neither `0.15` nor `1.0/60.0` is representable: nine sequential subtractions
leave 0.15 s a hair above zero and it expires on the tenth. **Python does exactly
the same**, which is why the fixtures agree. The guarantee is parity with the
source plus a deterministic, drift-free boundary — not an idealised one.

### Guarded by

`TimeDomainTest` (8 tests): the behavioural boundary at every scale from 50 ms to
an hour, an identical repeating cadence over 216,000 steps, an explicitly *named*
list of the authoritative timer accessors, the configured-duration types, and a
source scan proving nothing in production reaches for the float step. The named
list is deliberate: a reflective "any float called `*Timer`" rule would flag
`hurtFlash`, `recoil`, `aura` and `trapGlow` — which are drawing state and are
meant to be floats — and miss `shield` and `reel`, which are not obviously
temporal and are meant to be doubles.

---

### 14.2 Interface differences (Phase 10)

A touchscreen cannot do some of what a mouse does. Each row below sends the
identical gameplay command; none of them reaches a subsystem, and none changes a
number.

| Source | Port | Why |
|---|---|---|
| Hover shows talent details, click buys | Tap to inspect, tap again to buy | There is no hover on a touchscreen. A locked node is still pressable and still selectable — the *tree* refuses the purchase |
| `P` / `ESC` leaves pause | A RESUME button, plus Android Back | Python's pause panel handles **no clicks at all**; its mouse chain has no `PAUSED` branch. A phone has no ESC key, so the state would be unexitable |
| Skills cast at the mouse position | Arm on the bar, then tap the target | A finger has no cursor to cast at. Untargeted skills still fire on the tap |
| Fixed 1280x720 window | Safe areas, reflow, 44-unit touch targets | Phones have cutouts, gesture bars and thumbs. The **world** viewport is still exactly 1280x720 on every device, so no gameplay coordinate moves |
| Discount assigned in the shop's draw loop (§14 above) | Computed live at the point of sale | Already deviated in Phase 9; Phase 10 keeps it, and the shop screen has no cost arithmetic at all |

Behaviours reproduced rather than corrected, now that a player can see them:

| Behaviour | Kept because |
|---|---|
| **Deep Foundations' tooltip lies** | It claims +50% castle maximum health at rank 5; nothing in the shipped game reads the value. Buying all five ranks through the interface changes the castle by exactly nothing (`UiIntegrationTest.deepFoundationsStaysInert`). The port reproduces the game, not its tooltip |
| **The Endless horn stays spent all run** | It re-arms per wave in Classic and never in Endless. The button remains present and pressable, and does nothing |
| **An unaffordable shop card is still clickable** | `try_buy` runs for any card the click lands on and answers "Not enough gold!". Making the card refuse the *press* would silently swallow it — and would open a hole for the press to fall through into a grab |
| **Settings is reachable only from the menu** | The source's handler has no other route to it. This is also what makes the run-difficulty guarantee enforceable rather than assumed |

Two port defects Phase 10 found in earlier code, both fixed:

| Defect | Detail |
|---|---|
| **Two entry points into a run disagreed** | `CastleDefenseGame.startRun` called `RunWorld.beginRun` directly and left the game `PLAYING`, while the menu button routes through `Navigation.chooseMode` and opens the first armoury. Found by staging a screenshot: `--screen menu` produced a shop. Fixed by routing the entry point through the graph, so there is one way in |
| **Preferred difficulty was never seeded** | `UiRoot`'s was null until the player touched the setting, so a run started before then had no difficulty selected in the menu. It now defaults from `SaveData` |

---

### 14.3 Rendering differences (Phase 11)

Rendering is where Pygame and libGDX differ most, so these are the deviations
that could not be avoided — each with what was preserved instead.

| Source | Port | Why, and what is kept |
|---|---|---|
| **Draw methods pull from the global `random`** — the shake offset, the lightning path, the castle cracks | the decoration stream, plus `VisualRng.stable` for fixed decoration | Otherwise the gameplay sequence depends on how many frames were rendered: a 144 Hz display would literally play a different game from a 60 Hz one. `RenderPurityTest` asserts ten thousand rendered frames move the gameplay generator by nothing |
| `random.seed(1337)` … `random.seed()` around the cracks | a private generator keyed to 1337 | The same fixed crack pattern, without perturbing a shared stream on the way past |
| The castle is built into a cached surface and blitted | drawn directly each frame | There is nothing to cache into without a framebuffer, which is a lifecycle Phase 11 does not need. Recorded as Phase 12's first profiling target |
| Castle damage flash: `BLEND_RGBA_MULT` then `BLEND_RGBA_ADD` | an additive tinted pass | That composite has no direct batch equivalent. **A documented visual approximation** — recognisably the same red pulse, not bit-identical, and it adds no gameplay effect |
| Glows via a new `SRCALPHA` surface per frame | concentric translucent discs | Same look; no texture allocated during a frame, by anything, anywhere |
| `Projectile.trail` is a field on the projectile | trails live in the renderer, keyed by uid | A cosmetic list on a gameplay object is a list something can read. This way the shot's motion structurally cannot depend on it |
| The scene is blitted at a random offset for shake | the world camera is offset for the world pass and restored | No render target, no resize handling, no full-screen resample every frame. Entities are never moved — that would be moving the game |
| Sky drawn as stacked bands | one gradient quad | The bands showed seams where rows met, and this is one draw call instead of twenty-four |
| No interpolation (fixed 60 fps window) | previous/current blend on fast movers | The port runs at the display's rate. Simulation positions stay authoritative; the blend is two floats handed to a draw call. Teleports are not blended |

### 14.4 Port defects Phase 11 found

| Defect | Detail |
|---|---|
| **Entities drawn upside down** | The simulation keeps Pygame's downward y — every ported formula and all 771 parity fixtures depend on it — and the renderer assumed libGDX's upward y. Static geometry had been flipped; entity positions had not. Found by *looking at* the first capture, which had the horde walking along the top of the sky. Fixed by putting the conversion in `WorldGeometry` at the single boundary where the renderer reads a position |
| **The procedural skin could not be re-selected** | `SkinManager.load` looks for a descriptor file and the built-in skin has none, so switching *back* to procedural failed once a real skin was loaded — leaving no way to return to the fallback. Now special-cased, since it is the fallback and must always be reachable |
| **`hud.multiplier` was formatted with one argument** | The key takes two — the multiplier and the head count that earned it — so the HUD showed a literal `{1}`. A Phase 10 slip, visible only once the row was drawn |

---

## 15. Phase plan

| Phase | Content | Exit criterion |
|---|---|---|
| 1 | Analysis, inventory, this document, `PORTING_STATUS.md` | Reviewed |
| 2 | Gradle multi-project, core/desktop/android, viewports, lifecycle | Both launchers run and show a 1280×720 letterboxed clear screen |
| 3 | Config loading, `AssetManager`, `SkinManager`+validation, atlas pipeline, save manager, platform services | `gradlew packAssets` produces an atlas; skins load/validate; save round-trips |
| 4 | Fixed-step `Simulation`, entity lifecycle, collision helpers, `GameInput`, velocity tracker | Headless world steps; input tests pass |
| 5 | Projectile, Castle, towers, Barricade, Outpost, SpikeWalls, overcharge, counters | Tower/counter/overcharge tests pass |
| 6 | Enemy base + 10 mobs, armour, grab/throw/shove/bounce, scaling, tiers | Physics + gating tests pass |
| 7 | 3 bosses, regalia, claws, boss lifecycle purge | Boss tests pass |
| 8 | Classic + Endless directors, gold/score/multiplier, horn, weather | Mode tests pass |
| 9 | Shop, talents, skills, difficulty, persistence | Progression tests pass |
| 10 | All screens, HUD, safe area, touch targets | Layout tests pass; device frames look right |
| 11 | Particles, shake, weather, skill effects, procedural + skinned rendering | Visual parity review |
| 12 | Profiling, pooling, quality presets | Frame budget met on a low-end device profile |
| 13 | Full test run, desktop + Android smoke, parity sweep | Every `PORTING_STATUS.md` row is `[T]` or explicitly deferred |
