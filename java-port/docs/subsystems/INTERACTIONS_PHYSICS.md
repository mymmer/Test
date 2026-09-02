# Subsystem contract — Interaction and physics

## Purpose

Turn a pointer gesture into a gameplay consequence: grab, drag, throw, strip,
shove, overcharge.

## Owns

* The press priority (tower → grabbable mob → heavy unit).
* Grab state, the grab cooldown, and Magnetic Gloves multi-grab.
* Drag following, and its clamps.
* Release: the throw, the multi-grab scatter, and the cancel-drops rule.
* The two-phase heavy-unit interaction (strip while plated, shove once bare).
* The gesture-to-power conversion for overcharge.

## Does not own

* **Input devices.** Not one line mentions a mouse, a finger or a pixel.
* **The velocity tracker.** `PointerVelocity` is a one-method seam over
  `GameInput.releaseVelocity`.
* **What a throw does.** `Enemy.onRelease` owns the release physics; this layer
  supplies the velocity.
* **Towers.** It calls `canOvercharge()` and `overchargeFire(x, y, power)`.

## Dependencies

`enemy` (via `EnemyContext`), `defence` (`DefenceTower`, `Target`), `input`
(`Pointer`, `WorldInteractionHandler`), `config`, `util`.

## The pipeline

```
backend event (screen px)
  → GameInput          unproject to world  ← the only place pixels exist
  → InputRouter        UI first, then the world
  → CursorInteraction  onWorldPress / onWorldDrag / onWorldRelease / onWorldCancel
  → Enemy / DefenceTower
```

`CursorInteraction.update(dt, down, worldX, worldY)` runs on the **simulation
step**, not per input event, so a device reporting 240 drags a second and one
reporting 60 produce the same motion.

## Public API

```java
CursorInteraction(EnemyContext ctx, PointerVelocity velocity)
  implements WorldInteractionHandler
  boolean onWorldPress(Pointer);  void onWorldDrag/onWorldRelease/onWorldCancel(Pointer);
  void  update(float dt, boolean pointerDown, float worldX, float worldY);
  void  setGrabLevel(int), setMultiLevel(int), setGrabCooldown(float);
  float grabCapacity(), grabCooldown(), grabCdRemaining();
  Enemy grabbed(), stripping();  DefenceTower charging();
  int   extraGrabbedCount();  Enemy extraGrabbed(int);
  boolean busy();
  static float overchargePower(DefenceTower t, float px, float py);

interface PointerVelocity { void velocityFor(int pointerId, float[] out); }   // .ZERO
```

## Important invariants

1. **Desktop and mobile must produce identical results for identical world-space
   paths.** Everything below `GameInput` works in world units.
2. **Press priority is gameplay.** An overchargeable tower outranks the mob
   standing on it; a grabbable mob outranks the tank behind it.
3. **A cancel drops, a release throws.** Losing a grab because the phone rang
   must not launch the mob.
4. **The grab cooldown is charged on every release path** — thrown, stripped or
   overcharged — as in Python.
5. **Multi-grab jitter is per extra mob, ×0.85–1.15, from the seeded gameplay
   stream.** The primary mob's release is exact.
6. **The heavy-unit grip survives the armour coming off.** Phase 1 strips while
   plated (drag away only); phase 2 shoves once bare (drag toward only); the grip
   is dropped only when neither is possible.
7. **Drag follow falls with mass and is clamped to 0.25–0.95**, so nothing is
   ever perfectly rigid or completely unresponsive.
8. **A held mob is clamped inside the arena** and cannot be dragged through the
   castle face or the ceiling.
9. **The overcharge power formula lives here, not in the tower.** The tower is
   handed a number in 0..1 and never learns what a drag is.

## Physics summary (owned by `enemy`, driven from here)

| Quantity | Rule |
|---|---|
| Release velocity | `v × THROW_POWER × talent / (0.55 + 0.45·MASS)` |
| Air step | `vy += g·dt` → `vx += wind·dt` → `vx -= vx·DRAG·dt` → integrate |
| Fall damage | `max(0, impact − 250) × 0.26 × (0.75 + 0.35·MASS) × (1 + 0.22·lvl)` |
| Bounce | `vy' = −|vy|·RESTITUTION[lvl]`, `vx' = vx·(0.45 + 0.07·lvl)`; ends on `count > lvl` or `|vy| < 90` |
| Slam | `max(0, rel − 170) × 0.10 × (0.6 + 0.5·MASS)`; victim full, thrower 45% |
| Shove | `min(340, shove + drag·3.4)`, decaying 1.6/s, cleared under 8 |
| Strip | `progress += drag / 420`; each plate: armour ↓, speed ×0.76, vulnerability +0.22 |

All of it integrates on the fixed 1/60 step. No render delta reaches enemy
physics, and there is no Box2D.

## Relevant source files

```
core/src/main/java/com/mymmer/castledefense/interaction/
    CursorInteraction.java  PointerVelocity.java
core/src/main/java/com/mymmer/castledefense/enemy/Enemy.java   (the physics itself)
```

## Relevant tests

```
core/src/test/java/com/mymmer/castledefense/interaction/InteractionTest.java  (25)
core/src/test/java/com/mymmer/castledefense/enemy/EnemyPhysicsTest.java       (21)
core/src/test/java/com/mymmer/castledefense/enemy/PythonParityTest.java       (11)
core/src/test/java/com/mymmer/castledefense/input/TestPointers.java
```
