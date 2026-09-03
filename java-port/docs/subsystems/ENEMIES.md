# Subsystem contract — Enemies

## Purpose

Every hostile unit, its state machine, its scaling, and what a wave is made of.

## Owns

* The `Enemy` base state machine, and the eleven concrete units.
* Wave scaling, difficulty scaling and the endgame tiers.
* Armour, armour stripping and their permanent consequences.
* Death, payout and the silent-death path.
* Crowd separation and forward blocking.
* `FriendlySkeleton` — the ally, deliberately **not** an `Enemy`.
* Wave composition: the unlock table, the budget, the goblin roll, boss
  placement as specs.

## Does not own

* **The defences.** It depends on `defence` and never the other way round.
* **The cursor.** Grabbing, throwing, stripping and shoving are driven by
  `interaction`; `Enemy` exposes `onGrab`/`onRelease`/`applyStrip`/`applyShove`
  and knows nothing about pointers.
* **Spawning and pacing.** Composition says *what*; the Phase 8 directors say
  *when*.
* **Bosses.** `Boss extends Enemy`, so the arrow runs `boss → enemy` and this
  package never names a concrete boss — `ArchitectureTest` enforces it. Boss
  identity is `Enemy.isBoss()`, a flag on the base. Wave composition still emits
  a `SpawnSpec` carrying a stable boss id; Phase 7's factory resolves it.
* **Drawing.** No class here has a `draw` method. Visual state Python computes
  in `update` (`anim`, `spin`, `hurtFlash`, `fuse`, `hop`, `ramPush`, `glow`) is
  kept and labelled, because Phase 13 parity will compare it.
* **Talents.** `CombatModifiers` is the narrow seam; Phase 9 implements the tree.

## Dependencies

`config`, `util`, `entity`, `debug`, `data`, and `defence` (for `Target`,
`Trappable`, `CombatModifiers`, and the structures a mob attacks).

## Public API

```java
abstract Enemy extends Entity implements Target
  void  update(float dt);            // one fixed step
  void  onGrab(); void onRelease(float vx, float vy);
  void  land(); void slamInto(Enemy other); void resolveFling();
  float applyDamage(float amount, String kind);   // returns what was dealt
  void  die(); void die(boolean silent);          // NOT final -- see Volatile
  boolean grabbable(), armored(), shovable(), tooHeavy(), strippable();
  boolean applyShove(float amount), applyStrip(float amount);
  boolean covers(float px, float py), grabCovers(float px, float py),
          overlaps(Enemy other);
  EnemyState state(); void forceState(EnemyState);
  float x(), y(), vx(), vy(), speed(), mass(), depth(), groundY(),
        hp(), maxHp(), damage(), armor(), vulnerable(), shove(), stagger();
  int   layers(), gold(), tier();  String tierName(), describe();
  boolean blocked(), trapped(), isBoss();
  EnemyType type();      // NULL for a boss -- bosses are not in the roster
  String   typeId();     // always present: the roster id, or the boss id

Necromancer  implements Trappable: summon(), castBolt(), castAtPrisoner(),
             huntingPrisoner(), currentStandoff(), minionCount()
Berzerker    rage()
Assassin     cloaked(), dashing()          // targetable() is overridden
Volatile     detonate(), fuse()            // die() is overridden
TreasureGoblin escape(), escapeTimer()
SiegeRam     ramPush()

FriendlySkeleton extends Entity            // NOT a Target
  void update(float dt); void takeDamage(float amount); Enemy pickTarget();
  float x(), y(), hp(), maxHp(), damage(), depth(), life(); EnemyState state();

WaveScaling   static hp/damage/speed(int wave); tierIndex(EndgameTier[], int)
EnemyTable    load(JsonSource); config(EnemyType); tiers(); unlocks();
              create(ctx, type, wave, x, y)
WaveComposition  build(int wave, Rng); bossForWave(int);
                 unlockedTypes(int); newlyUnlocked(int)
CrowdSeparation  separate(EnemyContext, float dt)
EnemyContext  extends DefenceContext: horde(), createEnemy(), spawnEnemy(),
              allyCount(), ally(int), gameTime(), bounceLevel(), spikes(),
              goldMultiplier(), addGold/addKill/addScore/addThrownDamage/
              addPlatesTorn(), storm(), strikeLightning(), enemySlow(),
              enemyScale(), enemyHpCurve(), enemySpeedScale(), endgameTiers()
```

## Important invariants

1. **`Enemy` implements `Target`; `defence` was not changed to accommodate it.**
   The arrow runs `enemy → defence`, exactly as `enemies.py` imports
   `castle.py`. Nothing in `defence` names a concrete enemy.
2. **The scaling chain's order is observable** and is Python's: wave curve →
   difficulty scale (health and damage only) → HP curve bend → endgame tier.
   Difficulty speed is a separate **flat** multiplier applied last.
   `PythonParityTest` checks all 48 combinations against the source.
3. **`speed` is a mutable field that three effects multiply and restore.** The
   talent slow multiplies and divides back (imprecise, as in Python); the
   Berzerker and Assassin save, overwrite and assign back. They compose through
   that field in an order that is observable — do not replace it with a formula.
4. **The Berzerker quirk** (`docs/PORT_ANALYSIS.md` §14): its movement uses
   `BASE_SPEED × waveSpeed × rage` and therefore discards difficulty and tier
   speed *at every health level*, not only while raging. Reproduced; pinned by a
   named test.
5. **Grab gating is four conditions**, and mass-vs-capacity is only the last:
   type allows it, alive, state is walk-or-attack, **not armoured**, then mass.
   A plated tank rides out every lift at any Grab Strength.
6. **Grab capacity is a table lookup times a talent**, not a formula. The talent
   multiplies the entry, so it can push one level past the next level's
   threshold. Normalising it would change which level lifts a Siege Ram.
7. **Fall and impact damage ignore armour**; projectile and explosive do not.
   That is the whole reason throwing counters a Shield Bearer.
8. **The bounce chain ends on `bounceCount > level`, strictly greater**, so
   level 0 never rebounds. `>=` would give every level one extra bounce.
9. **Slam cooldowns are keyed by uid.** Uids are process-global and monotonic —
   matching Python's `Enemy._next_uid` class counter, which `game.reset()` does
   not touch — so a dead entry can never match a new entity.
10. **`die()` is not final and its ordering is load-bearing.** The gold
    multiplier is read **before** the mob is marked dead, so the dying mob counts
    toward its own payout. The Volatile calls through *then* detonates, which is
    what makes chains possible.
11. **Crowd separation is order-dependent**: insertion order, nested `(i, j)`
    traversal, and immediate per-pair mutation. No bucketing, sorting, grid or
    parallelism — each produces a plausible and *different* crowd.
12. **`FriendlySkeleton` does not implement `Target`.** A tower cannot target
    what it cannot be handed, so the rule is enforced by the type system rather
    than by a check in every targeting loop.
13. **Snapshot iteration where the roster changes mid-loop.** The airborne slam
    loop and the world's enemy loop both iterate `EntityList.Snapshot`, because
    `Outpost.trap` removes a live entry and a summon adds one.
14. **Gameplay geometry comes from config, never artwork.** `width`/`height` are
    the hitbox; a 2× larger PNG changes nothing.
15. **All randomness is the seeded gameplay `Rng`.** Spawn position, depth, fly
    altitude, timers, cloak, dash, summon jitter, multi-grab scatter, wave
    composition. No `new Random()`.
16. **No pooling, no ECS, no EventBus.** Deferred or rejected per the phase brief.
17. **`EnemyConfig.type` is null for a boss**, because a boss is not in the
    roster — it never appears in the unlock table, is never picked by wave
    weight, and cannot be found by `EnemyType.byId`. Anything that just needs a
    name uses `typeId()`, which is always present. `EnemyConfig.forBoss` is the
    one way to build a config from outside this package, and it cannot produce a
    grabbable, trappable, heavy or strippable unit.

## Relevant source files

```
core/src/main/java/com/mymmer/castledefense/enemy/
    Enemy.java  EnemyState.java  EnemyType.java  EnemyConfig.java  EnemyContext.java
    EnemyTable.java  WaveScaling.java  EndgameTier.java  SlamCooldowns.java
    Scout.java  FootSoldier.java  ShieldBearer.java  Berzerker.java  SiegeRam.java
    Skeleton.java  Necromancer.java  Assassin.java  Gargoyle.java  Volatile.java
    TreasureGoblin.java  FriendlySkeleton.java
    CrowdSeparation.java  WaveComposition.java  SpawnSpec.java
assets/data/enemies.json
tools/parity/generate_fixtures.py
```

## Relevant tests

```
core/src/test/java/com/mymmer/castledefense/enemy/
    PythonParityTest.java     (11)  formulas vs values generated from the source
    EnemySpeedTest.java       (11)  slow, rage, dash, difficulty, tiers
    EnemyPhysicsTest.java     (21)  release, fall, bounce, slam, cooldown identity
    EnemyBehaviourTest.java   (45)  states, crowd, death, and every unit
    WaveCompositionTest.java  (16)  unlocks, budget, goblins, boss specs
    EnemyTableTest.java       (16)  shipped values vs enemies.py, and validation
    TestEnemyWorld.java  TestInteractionWorld.java  EnemyJson.java
core/src/test/resources/parity/fixtures.json
```
