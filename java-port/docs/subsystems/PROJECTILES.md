# Subsystem contract — Projectiles

## Purpose

One class for every flying thing, friendly or hostile, and the rules for what it
hits and in what order.

## Owns

* Projectile motion: wind, gravity, integration, lifetime, world bounds.
* The friendly hit test and its hot-path ordering.
* The hostile obstacle resolution order.
* Splash: which targets are caught, in what order, at what falloff.
* Pierce, per-target counters, crit rolls, hit-id tracking, damage falloff.
* Death semantics — when a shot detonates and when it merely disappears.

## Does not own

* **Firing.** Towers, the Outpost and (later) enemies construct projectiles;
  this class never decides that a shot should exist.
* **Drawing.** The Python class keeps an 8-sample position `trail` and a colour;
  both are drawing state that nothing reads for gameplay, so they arrive with the
  renderer in Phase 11 rather than being carried here unused.
* **What a target is.** Only `Target`, never an enemy type.
* **Its own lifetime in the world.** It is an `Entity`: it marks itself dead and
  the world's `EntityList` sweeps it.

## Dependencies

`GameConfig`, `DefenceContext`, `Target`, `Collisions`, `Entity`, `TraceEvent`.

## Public API

```java
Projectile(DefenceContext ctx, float x, float y, float vx, float vy,
           ProjectileKind kind, float damage, float splash, int pierce, float grav,
           boolean hostile, float life, float stun, float bonusAir, float bonusHeavy,
           boolean atPrisoner, long ownerUid);
static Projectile friendly(ctx, x, y, vx, vy, kind, damage);   // the common case

void  update(float dt);          // one fixed step
void  explode();                 // detonate now
float damageFor(Target e);       // counters + crit, resolved per victim
boolean hasHit(long targetUid);

float x(), y(), vx(), vy(), angle(), life(), damage(), splash(), stun();
int   pierce();
boolean hostile(), atPrisoner(), hasExploded();
long  ownerUid();
float bonusAir(), bonusHeavy();
ProjectileKind kind();           // id(), radius(), explodesOnGround()
```

## Important invariants

1. **Coordinates are Python/pygame space** — y grows *downward*, gravity is
   positive, `GROUND_Y = 620` is below `WALL_TOP = 350`. Every `GameConfig`
   constant is in that space, so the simulation stays in it and the single flip
   to libGDX y-up happens in the renderer. Flipping here would mean inverting
   every comparison in the file, which is how parity bugs get written.
2. **The update order is observable**, and it is Python's: wind → gravity →
   integrate → age → bounds/life → ground → collision. `updateOrderIsPythons`
   pins it; getting it wrong shifts every impact by a frame.
3. **Friendly collision order is the hot path**: cheap primitive bounds first,
   then alive / already-hit / targetable, then resolve. **At most one target per
   step.** Nothing is allocated per candidate — no rectangle, no vector, no
   iterator. The Python comment records that building a `pygame.Rect` here "was
   the single biggest cost in the profile at high waves".
4. **The hostile obstacle order is gameplay, not layout**:
   `prisoner shot` — else — `Barricade → Tower → Castle wall/front → Keep`.
   `HostileResolutionTest` proves it by placing shots that overlap several
   structures at once; a test that overlapped only one would pass under any
   ordering.
5. **A disabled tower is transparent** — shots pass through the rubble.
6. **Splash walks the target list in insertion order, checking each entry live.**
   No precomputation, no reordering, no spatial partitioning. Falloff is
   `1 - 0.55*(d/radius)` for targets and `1 - 0.5*(gap/radius)` for the wall and
   the barricade — three different curves, all Python's.
7. **Counters are resolved per victim at impact**, which is what lets one cannon
   shell triple a Siege Ram while only tickling the scouts beside it. The splash
   multiplier and the crit chance, by contrast, are sampled **once at
   construction**, so a talent bought mid-flight does not affect a shot already
   in the air.
8. **Pierce costs 28% of the damage each time** and the shot dies on the hit
   after its last pierce.
9. **A splash shot detonates on its first contact** regardless of remaining
   pierce; one that times out detonates; one that flies off the map does not.
10. **`hitIds` is a linear `LongArray`,** not a hash set: pierce caps around six,
    so the scan is shorter than a hash and allocates nothing per lookup.
11. **No broadphase.** Deferred to Phase 12 behind parity proofs — see
    `docs/PORT_ANALYSIS.md` §3.

## Relevant source files

```
core/src/main/java/com/mymmer/castledefense/defence/Projectile.java
core/src/main/java/com/mymmer/castledefense/defence/ProjectileKind.java
core/src/main/java/com/mymmer/castledefense/defence/Target.java
core/src/main/java/com/mymmer/castledefense/util/Collisions.java
```

## Relevant tests

```
core/src/test/java/com/mymmer/castledefense/defence/ProjectileTest.java          (14)
core/src/test/java/com/mymmer/castledefense/defence/HostileResolutionTest.java   (10)
core/src/test/java/com/mymmer/castledefense/defence/DefenceIntegrationTest.java  (6)
```
