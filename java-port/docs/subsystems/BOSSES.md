# Subsystem contract — Bosses

## Purpose

The three bosses, their interactive disruptions, the equipment the player tears
off them, and the lifecycle that guarantees a dead one leaves nothing behind.

## Owns

* `Boss` and the three concrete bosses.
* The shared disruption guard (`regaliaTaken` / `guardRegalia`).
* Boss gameplay anchors, from `data/bosses.json`.
* `DroppedItem` — the crown and the staff, with their own physics.
* `BossRegistry` — which bosses are on the field, and purging what a dead one owned.
* Per-boss mechanics: crown retrieval and the tower smash; breath streaming and
  claw battering; the staff disarm, the bone ward, raise-dead and the barricade
  stand-off.

## Does not own

* **Boss immunity as a special case.** `isBoss()` and `config.grabbable = false`
  are enough; `Enemy.grabbable()` already refuses. Nothing in the port switches
  on a concrete boss class, and `ArchitectureTest` fails the build if it starts.
* **Wave placement.** Phase 6's `WaveComposition` has been emitting
  `SpawnSpec.boss("troll_king")` since before any boss existed; Phase 7 only
  supplies the factory that resolves the id.
* **Tower selection.** The Troll King's smash calls
  `Castle.smashRandomTower(damage, stun)` — the Phase 5 contract, on the seeded
  stream. No new algorithm.
* **Input.** Disruptions arrive through `CursorInteraction` as world-space
  positions and drag distances.
* **Drawing.** No boss class loads a texture, touches an atlas, or knows a
  resolution. Visual state (`aura`, `intro`, `smash`, `orb`, `breathing`,
  `reel`, `spin`) is exposed for Phase 11.
* **The run.** `onBossDefeated` is a one-line hook; the skill unlock and the
  talent bounty behind it are Phases 8 and 9.

## Dependencies

`enemy` (Boss *is* an Enemy), `defence` (Castle, Barricade, Projectile,
DefenceTower), `config`, `util`, `entity`, `debug`, `data`.

## Public API

```java
abstract Boss extends Enemy
  BossType bossType();  BossConfig bossConfig();  boolean isBoss();   // always true
  float fireDelay(float seconds), fireScale();
  int   regaliaTaken();  float regaliaCd();  void guardRegalia();
  boolean regaliaAnchorInto(float[] out);         // GAMEPLAY-authoritative
  boolean regaliaCovers(float px, float py);
  boolean isSmackTarget();
  DroppedItem detachRegalia();                    // TrollKing, LichLord
  boolean applySmack(float amount);               // Dragon
  String describe();                              // dense enough for a bug report

TrollKing  hasCrown(), crownItem(), retrieveCrown(double dt), smash()
Dragon     breathing(), breathingRemaining(), breathTimer(), clawProgress(),
           reel(), spitFire(float power)
LichLord   hasStaff(), staffItem(), disarm(), shield(), wardActive(), orb(),
           recoverStaff(), raiseDead(), deathBolt(), currentStandoff()

DroppedItem extends Entity            // NOT an Enemy
  void  update(double dt), hold(), moveTo(float,float), throwIt(float,float);
  boolean covers(float px, float py);           // gameplay pickup box
  RegaliaKind kind();  State state();  Boss owner();  long ownerUid();
  float x(), y(), vx(), vy(), width(), height(), restY();

BossRegistry
  void  register(Boss), purge(Boss, horde, projectiles, items),
        purgeDead(...), clear(), addInteractionOwner(InteractionOwner);
  Array<Boss> liveBosses();  int liveCount();  boolean anyLive(), isLive(Boss);
  String describe();
  interface InteractionOwner { void releaseBoss(Boss); void releaseItem(DroppedItem); }

BossTable  load(JsonSource); config(BossType); enemyConfig(BossType);
           create(ctx, type, wave, x, y)
BossContext extends EnemyContext: addDroppedItem, onBossDefeated,
           summonableTypes, bossFireScale, createBoss
```

## Important invariants

0c. **`CombatModifiers` is the talent tree now.** From Phase 9 the object behind
   `ctx.modifiers()` is `TalentTree`, and its values are computed live from the
   current ranks — so a talent bought mid-run is in effect on the next step here
   with no cache to invalidate. Nothing in this package names `TalentTree`, and
   nothing may: see [`TALENTS.md`](TALENTS.md).

0b. **Scheduling goes through `BossRegistry`, never a `currentBoss`.** Both
   directors summon by `BossType` through `DirectorContext.summonBoss`, which
   purges dead bosses first (as `summon_boss` does) and registers the new one.
   The Endless timetable can therefore put a second boss on the field while the
   first is still alive, and a repeated boss is a fresh instance with a new uid,
   full health, no disruption history and no leftover guard. See
   [`MODES_PROGRESSION.md`](MODES_PROGRESSION.md).

0. **Gameplay time is `double`.** The intro, the regalia guard ladder, `fireDelay`, the Dragon's breath/shot/reel timers and the Lich's summon, bolt, phase, ward and disarm timers are `double` seconds; positions,
   velocities, angles and drawing state stay `float`. `update`/`think` receive
   the canonical `double` step and narrow it once, themselves, with
   `float fdt = (float) dt`. See
   [`SIMULATION.md`](SIMULATION.md), "Gameplay time is double" — the rule exists
   because float timers made a Hard Dragon breathe 16 fireballs where Python
   breathes 15.

1. **A boss is an `Enemy`.** It walks, takes damage, dies and pays out through
   the same contracts, so everything already tested about payout, targeting and
   physics applies to it unchanged.
2. **Immunity is a flag, never an `instanceof`.** Enforced by a test.
3. **The disruption guard is shared, and the Dragon's claws use it.** Battering
   claws increments `regaliaTaken` and calls `guardRegalia()`, exactly as
   stealing a crown does, even though claws are not regalia. Reproduced quirk —
   see `PORT_ANALYSIS.md` §14.
4. **The guard ladder starts at 9.6 s, not 6 s.** The counter increments at
   *detach* and the guard is applied at *recovery*, so by the time a guard exists
   the count is already 1. There is no state in which it is 6.0.
5. **Gameplay anchors come from `data/bosses.json`, never from a skin.** Where a
   crown can be grabbed, where the dropped item is born, and where retrieval ends
   are `GameplayAnchor`s. A skin's `AttachmentPoint` moves only the artwork —
   proven numerically by `SkinIndependenceTest` and structurally by
   `ArchitectureTest`.
6. **The registry is a collection, never a `currentBoss`.** 0, 1 and 2 bosses are
   all ordinary. Per-boss state lives on the instance.
7. **Purging is ownership, not garbage collection.** A dead boss leaves
   references in five places — horde, cursor, regalia, projectiles, registry —
   and every one is a gameplay bug before it is a leak. Items and projectiles are
   matched by **owner uid**, so killing boss A never touches boss B's.
8. **The purge runs from the defeat hook**, as in Python
   (`die` → `on_boss_defeated` → `purge_boss`). Deferring it to the next step
   would let the item list be swept before the cursor was told to let go.
9. **A repeat boss inherits nothing**: new uid, full health, zero
   `regaliaTaken`, no guard, crown on, staff held, no ward, no reel, no leftover
   dropped item. Tested for all three.
10. **`DroppedItem` is not an `Enemy`.** No health, no armour, no targeting, no
    payout, and its own physics: 0.34 restitution, 0.6 horizontal loss, arena
    walls one item-width in, a 90 px/s settle threshold. Its pickup box comes
    from `RegaliaKind`, never from artwork.
11. **A throw is capped by kind, magnitude-wise.** 2300 for a crown, 780 for a
    staff, with the direction preserved — a per-axis clamp would change the angle.
12. **A crown must be at rest to be recovered.** One still in the air stays out of
    reach, so a good throw buys time even after the Troll King arrives.
13. **The bone ward multiplies incoming damage before armour.** Preserved as the
    source order. **Documented finding:** with the current formulas the two orders
    are numerically identical, because both steps are pure multiplies and ×0.25 is
    an exact power of two. The order is kept because it stops being equivalent the
    moment armour gains a floor, a cap or a flat subtraction.
14. **The Dragon's breath is a simulation timer**, never render frames or
    wall-clock. **Documented finding:** on Hard (`fireScale` 0.5) the port fires
    16 fireballs per breath where Python fires 15, because Python's timers are
    doubles and the port's are floats. The shipped Normal case agrees exactly.
15. **All randomness is the seeded gameplay `Rng`**, including the tower smashed,
    the summon pool draw and every timer jitter.
16. **Raised units are real Phase 6 hostiles**, built through the enemy factory
    and filed with the horde — not a boss-owned side list.
17. **The Lich will not advance past a live barricade.** He halts in front of it
    and bolts it from a distance rather than attacking it.
18. **No pooling, no boss scripting system, no generic state framework.** Three
    bosses with distinct mechanics, each readable in its own file.

## Relevant source files

```
core/src/main/java/com/mymmer/castledefense/boss/
    Boss.java  BossType.java  BossConfig.java  BossTable.java  BossContext.java
    TrollKing.java  Dragon.java  LichLord.java
    DroppedItem.java  RegaliaKind.java  BossRegistry.java
core/src/main/java/com/mymmer/castledefense/config/GameplayAnchor.java
core/src/main/java/com/mymmer/castledefense/interaction/CursorInteraction.java
assets/data/bosses.json
tools/parity/generate_fixtures.py
```

## Relevant tests

```
core/src/test/java/com/mymmer/castledefense/boss/
    BossMechanicsTest.java     (28)  immunity, guards, crown, breath, claws, ward
    BossLifecycleTest.java     (24)  purge, repeat bosses, two at once, item physics
    BossParityTest.java        (11)  formulas vs values generated from the source
    SkinIndependenceTest.java  (6)   a skin change cannot move a gameplay anchor
    TestBossWorld.java  BossJson.java
core/src/test/java/com/mymmer/castledefense/ArchitectureTest.java  (9)
```

## Phase 11 — bodies, regalia and the display surface

Body, aura, breath, ward, crown, staff and the reeling club are world rendering.
The **health bar is not**: Phase 10's UI owns it, drawn unshaken, and a second
bar in world space would disagree with the first during a screen shake.

**Regalia follows gameplay, never animation.** The crown is on the Troll King's
head only while `regaliaAttached()` is true; the instant it comes off, the head
is bare and a real `DroppedItem` is drawn at its own position. Nothing waits for
an animation, because an animation cannot be the reason a detached item still
looks attached.

The **regalia ward** arc is the boss's own cooldown against its own growing span
— `REGALIA_COOLDOWN * (1 + GROWTH * (taken - 1))` — because it is the player's
only cue that grabbing again will fail, and an arc emptying at the wrong rate
would mislead them about when to try.

### The uniform display surface

`ArchitectureTest` forbids `instanceof TrollKing`, and rightly: once a type test
is acceptable in one place it spreads. So `Boss` publishes neutral-default
accessors that each boss overrides where they mean something —
`regaliaAttached()`, `swing()`, `venting()`, `reeling()`, `wardStrength()`,
`orbCharge()`, `disarmedFor()`. A painter switches on `bossType()` to choose a
body and reads these for its state.
