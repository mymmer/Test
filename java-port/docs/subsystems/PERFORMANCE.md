# PERFORMANCE.md — Phase 12

What was measured, what was changed, what was left alone, and what remains
unverified.

---

## 1. The headline

**The simulation is not a bottleneck, and none of the algorithms `PORT_ANALYSIS`
flagged as theoretically expensive costs anything at this game's densities.** The
frame is dominated by rendering, and rendering is dominated by the sheer number
of shape primitives submitted.

So no broadphase, no candidate cache, no cluster grid and no gameplay pooling was
implemented. Each was measured first, and each would have been solving a problem
that is not there.

## 2. How it is measured

Two harnesses, because the two costs are separate and are optimised separately.

**Simulation** — `./gradlew :core:benchmark`. Advances the real `RunWorld` by
fixed steps with no GL context, no frame and no sleeping. Ten scenarios, one
fixed seed, warm-up before the measured window because the JIT needs a few
thousand steps before `step()` settles.

**Rendering** — `--bench` on the desktop launcher. Times whole frames on a real
backend and reports percentiles, discarding the first frames (shader compilation
and texture upload are real costs, but they are startup costs and averaging them
into a steady-state figure hides what the game does per frame).

**Per-layer** — `-Dcastledefense.layerTimes=true` adds a time and a primitive
count per draw layer. Off by default and free when off.

### Two traps this harness fell into

Both are recorded because the numbers would have been wrong and confident.

- **Timing a no-op.** The first simulation run reported a 0.1 µs median for every
  scenario. A crowd of 160 flattens a wooden castle in seconds, and a step
  entered in `GAMEOVER` advances nothing — so it was timing an early return. The
  benchmark now uses `survivingStep` and **fails loudly** if fewer than 90% of
  the window's steps actually simulated.
- **Benchmarking a moving workload.** The first A/B of the background cache ran
  on `endless-late`, which keeps simulating while it is measured; its entity
  count drifts and its frame times swing 40%. It reported no difference, the
  cache was deleted, and repeating it on the fixed-roster `mixed-wave` showed a
  clean 13%. **A benchmark whose workload moves cannot resolve a 10% change and
  will tell you confidently that there isn't one.**

This machine (4 cores, Windows) holds ±3–5% on a stable scene when quiet, and far
worse when anything else is running. That bounds every claim below.

## 3. Simulation baseline

Seed 8675309, fixed 1/60 s steps, no rendering.

| scenario | steps | mean µs | p50 | p95 | p99 | enemies |
|---|---|---|---|---|---|---|
| early-wave | 3000 | 22.7 | 7.5 | 93.6 | 189 | 43 |
| mixed-late-wave | 3000 | 29.2 | 16.2 | 54.3 | 137 | 49 |
| dense-crowd | 2000 | 64.4 | 46.0 | 96.6 | 349 | 67 |
| huge-crowd | 1000 | 143.0 | 109.5 | 205.3 | 661 | 160 |
| multi-boss | 2000 | 63.6 | 44.8 | 116.5 | 266 | 78 |
| projectile-heavy | 2000 | 24.8 | 16.0 | 33.5 | 104 | 60 |
| cannon-heavy | 2000 | 108.6 | 90.3 | 163.6 | 267 | 59 |
| slam-airborne | 1500 | 17.0 | 8.6 | 45.4 | 143 | 60 |
| effects-weather | 2000 | 24.2 | 21.3 | 34.3 | 68 | 40 |
| endless-long-run | 3000 | 35.2 | 24.8 | 48.7 | 124 | 83 |

**Worst p99 is 661 µs at 160 enemies — 4% of a 60 Hz step's entire budget**, and
160 is far beyond anything the game produces. The realistic worst case
(`cannon-heavy`, 59 enemies with eight Cannons scoring clusters every shot) is
90 µs median.

Each scenario targets one flagged hotspot: `dense-crowd` and `huge-crowd` the
crowd-separation pair loop, `cannon-heavy` the per-candidate cluster scoring,
`slam-airborne` the airborne pair scan, `projectile-heavy` the collision scan,
`endless-long-run` the director. None is expensive.

## 4. Rendering baseline, and where it goes

Per-layer, `endless-late`, 29 enemies:

| layer | primitives | µs | share |
|---|---|---|---|
| background | 1188 | 105–135 | 22–25% |
| enemies | 2326 | 250–300 | 53–58% |
| castle + spikes | 419 | 34–46 | 8–9% |
| towers + barricade | 353 | 22–27 | 5% |
| items / zones / projectiles | 70 | 11–17 | 3% |
| effects + weather | 19 | 8 | 2% |
| outpost | 22 | 4 | 1% |

Cost is **linear in primitives at roughly 0.1 µs each**. That single fact made
the rest of the phase straightforward: to make the frame cheaper, submit fewer
primitives.

**Phase 11 predicted the castle's brickwork would be the first thing to fix. It
was wrong** — the castle is a third of the background's cost and an eighth of the
enemies'.

## 5. Changes retained

| # | Hotspot | Baseline | Change | Result | Kept? |
|---|---|---|---|---|---|
| 1 | corner tessellation | 6 segments per 90° corner regardless of size | segments scale with radius (2/3/4/6); circle floor 8→6 below 3 units | enemies 2326→2211 primitives | **yes** |
| 2 | fill-then-outline | `roundRect` + `roundRectOutline` = 51 primitives per body | `roundRectOutlined`: two nested filled rounded rects, 30 primitives, border still inset | enemies 2211→1836 | **yes** |
| 3 | static background | 1188 primitives/frame of content that never changes | drawn once into a `FrameBuffer`, blitted as one quad | 1188→3 primitives; **p50 532 µs vs 614 µs, −13%** | **yes** |

Total: **4400 → 2719 primitives per frame, −38%.**

Change 3's measurement is the one with a clean A/B: same build, `mixed-wave`,
four runs each way, non-overlapping distributions. Changes 1 and 2 remove work
unconditionally with no added complexity and no new resource; wall-clock cannot
resolve them individually against this machine's noise, and the primitive counts
are deterministic.

### What change 3 costs

A GPU resource with a lifecycle. It is invalidated on `resume` because Android
discards the texture when the app is backgrounded, and it falls back to drawing
live — same picture, slower — on any device that refuses the framebuffer. It
deliberately caches only the layer that depends on nothing: not the castle, whose
tier and cracks change; not anything skin-, quality- or damage-dependent.
`-Dcastledefense.noBgCache=true` reproduces the A/B.

## 6. Deliberately not done

| Candidate | Why not |
|---|---|
| projectile collision broadphase | `projectile-heavy` is 16 µs median. Optimising it would risk hit ordering, pierce semantics and already-hit sets to save nothing measurable |
| slam broadphase | `slam-airborne` is 8.6 µs median with 45 mobs in the air at once |
| crowd-separation broadphase | `dense-crowd` is 46 µs median with 70 packed enemies; `huge-crowd` 110 µs at 160. Both are order-sensitive traversals with in-loop mutation, and neither costs anything |
| Cannon cluster grid | `cannon-heavy` is 90 µs median with eight Cannons. It is the dearest scenario and still 0.5% of a frame |
| shared target candidate cache | Same reasoning, plus an invalidation rule to get wrong |
| projectile pooling | Projectiles are short-lived and few (peak single digits in every scenario) |
| Enemy/Boss/DroppedItem pooling | Explicitly out of scope, and their lifecycles carry uid, owner, hit lists and cooldowns — exactly the state a pool leaks |
| castle brickwork cache | 34–46 µs. Cacheable in principle, but it depends on wall tier and crack bucket, so it needs an invalidation rule — complexity for an eighth of what the background was worth |

Every one of these is order-sensitive gameplay. Not touching them is also the
cheapest way to keep the 771 parity fixtures honest.

## 7. Parity

Every optimisation is in the renderer. **No simulation code was changed at all**,
so enemy insertion order, crowd-separation traversal, projectile hit order,
pierce semantics, splash order, Cannon scoring and tie-breaks, slam cooldown
identity, snapshot iteration and RNG consumption order are untouched by
construction rather than by argument.

Verified after: 926 tests, including the Python-fixture parity suites (14 + 8),
render purity (10), quality equivalence (11), skin independence (6), shake/input
(13) and the seeded progression smokes (4).

## 8. Quality presets

`EffectsAndQualityTest` runs a minute of identically seeded Endless on LOW and on
HIGH — both emitting into real particle systems at opposite caps — and asserts
gold, score, tier, roster size, castle health, the run clock and both halves of
the gameplay generator's state match exactly. MEDIUM is checked against a run
with no sink at all.

Presets change particle caps (220/520/900), glow intensity, shadows, trails and
wind-streak density. Nothing else.

## 9. Mobile

**Atlas memory is currently zero.** The shipped game runs on the procedural skin
and loads no atlas at all, so there is nothing to budget yet; the figure to watch
starts accumulating when artwork is added. `packAssets`, stable visual ids,
per-visual procedural fallback and skin switching are unchanged.

The APK is 4.90 MB with four ABIs, of which the libGDX natives are the bulk.

## 10. What is verified, and what is not

| | |
|---|---|
| **Desktop performance** | **Verified.** Numbers above, on this machine, with its noise bounds stated |
| **Android assembly** | **Verified.** `verifyAndroid` passes; debug APK builds; lint clean |
| **Android emulator: runs** | **Verified.** Installs, launches, renders the menu, no crash |
| **Android emulator: performance** | **Not measured.** The emulator runs SwiftShader software GL; its frame times say nothing about a phone's GPU |
| **Physical device: anything** | **Not verified.** No device was available. Performance, touch, real cutouts, pause/resume, backgrounding and GL context loss all remain untested on hardware |

**No mobile performance target has been met, because none has been measured on a
device.** The desktop numbers are a lower bound on effort, not a prediction.
