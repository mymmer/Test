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
rendering code and no platform types.** The Python classes mix `think()` and `draw_body()`;
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

```java
private static final float DT = 1f / 60f;
private static final int   MAX_STEPS = 5;      // 83 ms of catch-up, then drop time
accumulator += Math.min(Gdx.graphics.getDeltaTime(), 0.25f);
int steps = 0;
while (accumulator >= DT && steps < MAX_STEPS) { world.step(DT); accumulator -= DT; steps++; }
if (steps == MAX_STEPS) accumulator = 0f;      // no spiral of death after a stall
renderer.render(world, accumulator / DT);      // alpha available for interpolation
```

Python used a *variable* step capped at 50 ms. Every timer in the game is already
`-= dt`, so a fixed step is a strict determinism improvement with no behavioural change.
`pause()` zeroes the accumulator and forces `PLAYING → PAUSED` so an Android
backgrounding can never be fed into one step.

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
