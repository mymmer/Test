# RENDERING.md — the world on screen

Phase 11. The draw order, the coordinate boundary, screen shake, interpolation,
the procedural painters and the skin path. Particles, floating text, weather and
skill visuals are in [`VISUAL_EFFECTS.md`](VISUAL_EFFECTS.md); the interface is
[`UI.md`](UI.md).

---

## 1. The one rule

**Rendering reads gameplay. It never touches it.**

```
   GAMEPLAY
      │  read-only
      ▼
   RENDERING
```

Not a convention — enforced four ways:

| Enforcement | What it catches |
|---|---|
| `ArchitectureTest.renderMutatesNothing` | a painter calling a gameplay mutator |
| `ArchitectureTest.renderHasNoGameplayRandomness` | any generator but `VisualRng` |
| `ArchitectureTest.gameplayOnlySeesTheEventSeam` | gameplay naming anything in `render` except `VisualEvents` |
| `RenderPurityTest` | the behaviour: ten thousand frames change no position, no health, no timer and not one bit of the generator's state |

The last is the one that matters. A scan can be evaded; a simulation that is
byte-identical after ten thousand rendered frames cannot.

## 2. Which way is up

The simulation keeps **Pygame's coordinates**: y grows *downward*, the ground is
`GROUND_Y = 620`, the sky is at small y. Every ported formula, every gameplay
constant and all 771 parity fixtures are written that way, so changing it was
never on the table. libGDX's world is the other way up.

`WorldGeometry` is the entire conversion, and it is applied at exactly one
boundary — where the renderer reads a position:

```
   drawY = WORLD_HEIGHT - gameplayY
   drawAngle = atan2(-vy, vx)          // falling in the sim is rising on screen
```

Every painter then works in draw space and never subtracts anything. Scattering
the flip through the painters is how half a renderer ends up upside down — which
is precisely what the first Phase 11 capture showed, with the horde walking
along the top of the sky and the outpost stretched into a tower.

## 3. The draw order

Transcribed from `Game.draw`, listed in `DrawOrder.Layer` and walked by
`WorldRenderer`:

```
   background
   outpost                      background scenery, behind the fight
   castle          + spikes + towers   (the source's castle.draw paints all three)
   barricade
   enemies         flyers first, then ground back-to-front
   allies
   dropped items
   fire zones
   tornados
   projectiles
   effects         particles, then floating text
   weather
   skill bar   ┐
   horn        ├─ PLAYING/PAUSED only, and these DO shake — see §5
   grab cursor ┘  PLAYING only
   ─────────── the world surface ends here; the shake is applied to all of it ──
   HUD panel, menus, boss health bars      never shaken
```

**Layers are never merged to save draw calls.** A cannon shell must be in front
of the enemy it is about to hit; the moment ordering becomes negotiable that
stops being reliable. Batching happens *inside* a layer — each group draws its
shapes in one pass and its text in another, and a layer's text belongs to that
layer.

### The enemy comparator

`(0 if flying else 1, depth, x)` — flyers behind everything on the ground, then
ground units back-to-front by `depth`, then by `x`. Two things are easy to lose:

- `depth` is a per-enemy random offset in [-16, 16] fixed **at spawn**, not a
  screen coordinate. It is what stops a crowd from looking like a single line.
- Python's `sorted` is stable, so a genuine three-way tie falls back on list
  order. The Java comparator is *total*, breaking the last tie on entity uid,
  which is monotonic with insertion — so it reaches the same answer without
  depending on sort stability.

### It never touches the gameplay list

`DrawOrder.sortedEnemies` copies into a buffer it owns and sorts **that**. Python's
own `sorted` returns a new list; a `.sort()` on the live roster here would be a
rendering concern silently rewriting gameplay, since several rules depend on
insertion order. `RenderPurityTest.renderDoesNotReorderTheHorde` fails if it ever
does. The buffer is reused between frames, so the per-frame cost is a copy.

## 4. Screen shake

**Chosen: offset the world camera for the world pass, then put it back.**

Three options were available. A **framebuffer composite** is closest to the
source's mechanism but adds a GPU resource to create, resize and dispose, plus a
full-screen resample every frame whether shaking or not. **Offsetting entities**
was rejected outright: that is moving the game. The camera offset needs no render
target, no lifecycle, no resize handling, and leaves the UI alone because the UI
has its own camera.

`WorldShake` is the whole implementation. What it guarantees:

- **No gameplay coordinate changes.** Nothing is written to any entity.
- **Pointer conversion is unaffected.** The camera is restored before the frame
  returns, so every unprojection in the next frame's input uses an unshaken
  camera. `ShakeAndInterpolationTest.aimIsUnaffectedByShake` unprojects the same
  screen pixel and asserts it means the same world position.
- **No drift.** A thousand apply/clear cycles leave the camera exactly where it
  started, and applying twice without clearing does not compound.
- **Decoration randomness.** The source draws the offset from the global
  generator, which makes the gameplay sequence depend on how many frames were
  rendered while the screen shook. Here it is `VisualRng`.

Below the source's own `shake > 0.4` threshold there is no offset at all, so a
nearly-spent shake does not jitter for ever.

## 5. Three widgets that shake, and everything else that does not

`Game.draw` paints the **skill bar, the horn and the grab cursor** into the world
surface `s`, and only the HUD panel and the menus onto the unshaken `self.screen`.
So in the source those three move with a castle hit and the rest of the interface
does not. That is reproduced rather than tidied: a HUD that shakes as one is a
different game to look at.

`WorldRenderer` publishes the offset; `UiRenderer.setWorldShake` applies it as a
projection translate around exactly those widgets. **Only the drawing moves** —
hit rectangles are the ones the layout produced, so a shaking button is still
pressed where it was laid out, as it is in the source.

Boss health bars stay put. They are Phase 10 UI, and a health bar that jitters is
unreadable exactly when it matters most.

## 6. Interpolation

The simulation is a fixed 60 Hz; a 144 Hz display shows the same positions for
two or three frames, which reads as a stutter on anything fast. `Interpolator`
blends:

```
   drawn = previous + (current - previous) * alpha
```

Applied to **enemies, projectiles and dropped items** — the things that move fast
enough to stutter. Towers, the castle and the structures do not move and are
drawn at their own coordinates.

**The previous positions live in the renderer**, keyed by uid, not on the
entities. Gameplay cannot read them, cannot be affected by them, and cannot come
to depend on them. Collision, targeting, grabbing and every pointer conversion
use the simulation's own `x()`/`y()`. What this produces is two floats handed to
a draw call and then forgotten.

**Discontinuities are not smeared.** A one-step move beyond 240 px is treated as
a teleport and drawn at its current position with no blend — otherwise a spawning
enemy slides in from wherever the last one was, and a hurled mob streaks across
the field. The fastest real motion is a tornado throw at ~1180 px/s, under 20 px
per step, so the threshold is far above anything genuine and far below any jump.
A run reset calls `clear()` explicitly: a new run must never blend from the old
one's positions.

History for entities no longer present is dropped each step, so an Endless run's
map stays the size of the live roster rather than growing by one entry per
entity that ever existed.

## 7. Procedural rendering is the primary path

The Python game draws the complete game from primitives with no artwork at all,
and the port keeps that. Every visible type has a hand-drawn body:

```
   castle (6 wall tiers)   bowman  ballista  cannon   outpost  barricade  spikes
   scout  foot soldier  shield bearer  berzerker  siege ram  skeleton
   necromancer  assassin  gargoyle  volatile  treasure goblin  friendly skeleton
   troll king  dragon  lich lord   crown  staff
   arrows  bolts  cannonballs  magic  fire  bone
```

`ArtFallbackTest.proceduralSkinCoversEverything` asserts every `VisualId`
resolves and every one is procedural under the built-in skin, and
`everyDrawableTypeIsCovered` asserts every enemy, boss and tower type *has* a
`VisualId` — otherwise a newly added enemy draws as nothing at all, which is an
invisible mob that still kills you.

### Where the bodies come from

Directly from the source's `draw_body` methods, including the details that carry
information: the Siege Ram's plates are drawn **one per remaining armour layer**
read from gameplay, so stripping one really removes a panel; the Volatile's fuse
pulses on its own fuse timer; the Assassin is a translucent ghost while cloaked
and untargetable; a Berzerker's axes swing on `anim`, which the simulation
advances, so they stop when the world does.

### Colour comes from gameplay

`body_color()` is `mix(COLOR, white, hurt_flash * 0.75)` and `hurt_flash` is a
gameplay timer. The endgame tier tint on top of it is the unit's own tier. No
painter keeps a clock or a colour of its own, which is why a frame drawn twice
looks identical and a frame not drawn changes nothing.

## 8. Skins, and per-visual fallback

A skin supplies artwork per `VisualId`. **Fallback is per visual, not per skin**:
a skin with a Scout and a Dragon but no Siege Ram keeps its Scout and its Dragon
and draws the Ram by hand, in the same frame. Abandoning the whole skin over one
missing file would make every partial skin useless, and a partial skin is the
normal state of a skin being made.

Missing artwork is reported **once** per skin/visual/state by `MissingArtLog` —
never per frame, which on a busy wave would be thousands of lines a second — and
then falls back silently. Malformed *gameplay* data remains a different and fatal
category; that is `SkinValidator`'s business.

**Skins cannot change gameplay.** Phase 7's invariant is untouched: a skin
controls region, scale, offset, animation frames and visual attachment placement,
and nothing else. Artwork is drawn into the unit's own `width()`/`height()` box,
which is gameplay's, so no picture can change how big a thing is to hit. The
`SkinIndependenceTest` suite still passes unchanged.

**Static and animated skins are both first-class.** A single-frame skin answers
every animation state with its one region; an animated one maps gameplay states
(`WALK`, `ATTACK`, `HURT`, `GRABBED`, `AIR`, `BREATHE`, `REEL`, `DISARMED`, …) to
frames. A missing state falls back to the default region, then to the procedural
body. Nothing crashes because one optional animation is absent.

**Animation timing is cosmetic.** Damage, projectile launch, ability completion
and cooldowns are all decided by gameplay; rendering only chooses which frame
corresponds to the state it is already in. No gameplay event waits for a frame.

One gap was found and fixed: `SkinManager.load("procedural")` used to fail once a
real skin was loaded, because the built-in skin has no descriptor file — which
made switching *back* impossible. It is now special-cased, since it is the
fallback and must always be reachable.

## 9. Attachments and regalia

Visual attachments are for **alignment only**. The gameplay anchor stays
separately authoritative — the Phase 7 separation is not undone.

Regalia follows gameplay, never animation. The crown is drawn on the Troll King's
head only while `regaliaAttached()` is true; the instant the player pulls it off
the head is bare and a real `DroppedItem` is drawn at its own position. There is
no path by which a detached item is drawn still attached, because the boss painter
asks the boss and the item painter asks the item.

The shrinking regalia ward is the boss's own cooldown against its own growing
span — `REGALIA_COOLDOWN * (1 + GROWTH * (taken - 1))` — because it is the
player's only cue that grabbing again will fail, and an arc emptying at the wrong
rate would mislead them.

### The uniform boss display surface

`ArchitectureTest` forbids `instanceof TrollKing` and it is right to: the moment a
type test is acceptable in one place it spreads. So `Boss` publishes
`regaliaAttached()`, `swing()`, `venting()`, `reeling()`, `wardStrength()`,
`orbCharge()` and `disarmedFor()` with neutral defaults, each overridden by the
one boss it applies to. A painter switches on `bossType()` to pick a *body* and
reads these for its *state*.

## 10. ShapeKit

The Pygame primitives this game uses, and nothing else — deliberately not a
vector-graphics framework. Rectangles, rounded rectangles, circles, ellipses,
lines, paths, polygons, arcs, bars, gradients and glows.

**Rounded rectangles** are the reason it exists: Pygame's `border_radius` appears
in roughly eighty places, libGDX has no equivalent, and eighty renderers each
assembling their own corner is how eighty panels end up with six different radii.
Built once from a centre cross plus four corner arcs. Exact Pygame rasterisation
is not reproduced and is not the goal; a recognisable shape is.

**Glows** are drawn as concentric translucent discs rather than the source's
per-frame `SRCALPHA` surface — same look, no texture allocated sixty times a
second for every glowing thing.

**Pass discipline**: no method begins or ends a `ShapeRenderer` pass, so a
renderer can draw a whole layer in one filled pass and one line pass instead of
flipping type per shape. `fillBegin`/`lineBegin` switch explicitly and are no-ops
when already in the right pass.

## 11. Allocation

Nothing allocates per entity per frame. Scratch colours live on `RenderContext`,
the sort buffer and the interpolation map are reused, particles are pooled, and
polygon vertex arrays are fields. Trail history is a fixed ring buffer per shot.

The one real per-frame cost is the castle's brickwork loop — a few hundred small
rectangles, redrawn every frame because the source caches it into a surface and
there is nothing to cache into here without a framebuffer. **That is the first
thing Phase 12 should look at.** It was left alone deliberately: Phase 11 is
correctness, and caching it now would mean inventing an invalidation rule before
knowing what the profile says.

## 12. Quality presets

`QualityConfig` caps particles (220 / 520 / 900), scales glow intensity, and
switches shadows and projectile trails on or off. It reaches **nothing** the
player can feel: no damage, no radius, no cooldown, no spawn timing, no physics.

The renderer owns the value and the simulation has no route to it.
`EffectsAndQualityTest.qualityDoesNotTouchGameplay` runs a minute of identically
seeded Endless on LOW and on HIGH — both emitting into real particle systems —
and asserts gold, score, tier, roster size, castle health, the run clock and both
halves of the generator's state match exactly.

## 13. Tools

| | |
|---|---|
| `--scenario NAME` | builds a controlled visual state with **real gameplay objects** — `VisualScenarios` spawns real enemies, buys real towers, strips real armour by applying the real mechanic. There is no fake enemy and no parallel path; a harness that built its own objects would be showing something the game cannot produce. |
| `--ui-debug` | fps, frame time, interpolation alpha, quality, drawn enemies and projectiles, live particles against the cap, floating texts, active skin and atlas, missing-art count, tracked trails and interpolation entries, and the shake offset. A handful of counters, not a profiler — the input to Phase 12's budget. |
| `--device`, `--insets`, `--screen` | Phase 10's device frames, unchanged |

`tools/ui/screenshots.sh` for the interface; the scenario captures land in
`build/visual-baseline`.

**Screenshots are reference, never a gate.** Fonts, anti-aliasing and primitive
rasterisation all differ from Pygame, and a suite that fails on a one-pixel
change gets switched off within a month. The automated assertions are structural:
the renderer completes, the order is the source's, gameplay is unchanged, the
pool is intact, the camera is restored.

## 14. Deliberate visual differences

| Source | Here | Why |
|---|---|---|
| draws shake, bolts and cracks from the global RNG | decoration stream, and `VisualRng.stable` for fixed decoration | otherwise the gameplay sequence depends on how many frames were rendered — a 144 Hz display would play a different game |
| `random.seed(1337)` … `random.seed()` around the castle cracks | a private generator keyed to 1337 | same fixed pattern, without perturbing a shared stream on the way past |
| castle blitted from a cached surface | drawn directly each frame | nothing to cache into without a framebuffer; noted for Phase 12 |
| castle damage flash uses `BLEND_RGBA_MULT` + `ADD` | the closest clean equivalent: an additive tinted pass | that composite has no direct batch equivalent. A **visual approximation**, not parity |
| glows via a new `SRCALPHA` surface per frame | concentric translucent discs | no per-frame texture allocation |
| `Projectile.trail` lives on the projectile | trails live in the renderer | a cosmetic list on a gameplay object is a list something can read |
| sky as stacked bands | one gradient quad | the bands showed seams, and this is one draw call |

Everything else is the source's behaviour.

## 15. Tests

| File | What it holds down |
|---|---|
| `RenderPurityTest` | no RNG, no reorder, no mutation, one-way sink |
| `ShakeAndInterpolationTest` | camera restore, no drift, aim unaffected, teleports, reset |
| `EffectsAndQualityTest` | pool integrity, bounds, reset, LOW≡MEDIUM≡HIGH gameplay |
| `ArtFallbackTest` | per-visual fallback, warn-once, skin round trip, atlas lifecycle |
| `ArchitectureTest` | the structural half of §1 |

## Phase 11.5 — what the image comparison found

Phase 11's painters were written by reading Python's draw methods. That was
useful and it was not sufficient. Putting the two renderers' output side by side
found eight defects that source-reading had missed, several of them serious.

| Defect | How it looked | Cause |
|---|---|---|
| **No particles or floating text, anywhere** | thirty kills, no sparks, no gold numbers | the event sink was attached from `worldRenderer.events()` **before** `create()` built the particle system, so it captured `VisualEvents.NONE` permanently |
| **Effects drawn mirrored about the horizon** | sparks in the sky | `VisualEvents` takes gameplay coordinates; `EffectsSystem` stored them unconverted. Now converted once on entry, so the whole system's maths is in draw space |
| **The keep was almost black** | a dark tower beside a brown wall | `ctx.shade()` returns a **shared scratch** `Color`; passing it as `base` to `brickwork`, which shades again per block, aliased the two and darkened the base cumulatively |
| **Hills were sharp triangles** | a jagged skyline | invented rather than transcribed. `_build_background` uses three ridges, each a sum of two sines sampled every 40 px |
| **No moon, too few stars** | an empty sky | simply missing |
| **Boss bars across the top** | wrong half of the screen | the source draws them along the **bottom**, at most two, 620 wide alone or 400 each in a pair, with the name above and the numbers on the bar |
| **The horn was a labelled rectangle in the top-right** | "CHALLE..." clipped, and overlapping the new field readout | `draw_horn` is a brass **disc** with a curled horn glyph at `HORN_RECT = (170, 452, 58, 60)` — on the castle wall — with the word underneath |
| **The Endless SHOP button shook** | jitter on a stat-panel row | it is row 7 of the panel, and `draw_hud` paints the panel on the unshaken screen |

Two things that were missing rather than wrong:

- **The whole field readout** — enemies left, kills, throw damage, the wind
  indicator, the storm label. The only place a player sees any of it.
- **The announcement banners.** Phase 8 built `Announcements` and stored ids
  rather than English; nothing ever drew them, so every wave name, weather
  change and boss arrival went unseen.

And one layer named in `DrawOrder.Layer` that Phase 11 never implemented: the
**grab cursor** — the overcharge slingshot with its predicted arc, the claw-smack
ring, and the prompts that are the only way the crown, staff, stripping and
overcharge mechanics are discoverable at all.

### The lesson, recorded

Reading a draw method tells you what it draws. It does not tell you whether what
you wrote draws the same thing, and it cannot tell you about a layer you forgot
or an object graph wired in the wrong order. The comparison is cheap — the
Python side is a 200-line helper that imports the game unmodified — and it found
more in one pass than the whole of Phase 11's own review.
