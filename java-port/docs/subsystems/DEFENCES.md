# Subsystem contract — Defences

## Purpose

Everything the player builds: the castle and its walls, the towers on it, the
spiked parapet, the outer barricade, and the Outpost with its garrison and its
cage.

## Owns

* Tower stats, targeting, firing cadence, damage, downtime and manual overcharge.
* The castle: health, wall tiers, reinforcement, repair, tower slots and the
  boss-facing smash/stun entry points.
* Spike reflection.
* The barricade: tiers, health, rebuild and its collision band.
* The Outpost: garrison, turret upgrade, overdrive, and the whole prisoner
  mechanic including regeneration and the skeleton-raising timer.
* The seams that keep this package free of enemy types: `Target`, `Trappable`,
  `AllyFactory`, `CombatModifiers`, `DefenceContext`.

## Does not own

* **Enemies.** Not one concrete enemy type is imported, referenced or
  instantiated here. See "The dependency direction" below.
* **Allies.** The Outpost decides *when* a skeleton is raised; `AllyFactory`
  decides what one is.
* **Talents.** `CombatModifiers` is the handful of scalars the defences read,
  not the tree. Phase 9 implements the tree behind that interface.
* **Input.** A tower is handed `(aimX, aimY, power)`; no mouse, no touch id, no
  gesture reaches it.
* **Drawing.** No class here has a `draw` method. Visual state that Python
  computes inside `update` (`aim`, `recoil`, `flash`, `trapGlow`) is kept and
  labelled, because Phase 13 parity will want to compare it — but it is *state*,
  and Phase 11 owns everything that reads it.
* **The shop.** Buying, pricing and affordability are Phase 8; the methods those
  will call (`addTower`, `upgradeWall`, `buy`, `upgrade`) exist and are tested.
* **Bosses.** Phase 7 arrived and changed nothing here: the Troll King's tower
  smash calls the existing `Castle.smashRandomTower(damage, stun)`, and boss
  projectiles are ordinary `Projectile`s carrying an `ownerUid`.

## The dependency direction

`castle.py` never imports `enemies.py`, even though the Outpost eventually
produces friendly skeletons. That is reproduced exactly:

```
defence  ──depends on──▶  config, util, entity, debug, data
   ▲
   └── enemy  depends on defence: Enemy implements Target,
              Necromancer implements Trappable
```

**Phase 6 confirmed this without changing anything here.** The concrete enemies
arrived and implemented the existing contracts; not one line of `defence` was
adjusted to accommodate them, and no class in this package names an enemy type.

`Target` therefore lives **in** `defence`, not in a neutral "shared types"
package. A middle package would look tidier and would let the dependency quietly
become circular later.

## Dependencies

`GameConfig`, `Collisions`, `Entity`, `Rng`, `SimulationTrace`, `JsonSource` /
`Json5` / `DataException`. Nothing else.

## Public API

```java
// The seams
interface Target       { long uid(); boolean alive(), targetable(), flying(), heavy();
                         float x(), y(), width(), height(), hp(), vxEstimate(), vyEstimate();
                         void takeDamage(float amount, String source); }
interface Trappable extends Target { float mass(); void onTrapped(); void moveTo(float, float); }
interface AllyFactory  { boolean spawnAlly(float x, float y); int allyCount(); }   // .NONE
interface CombatModifiers { /* defaulted scalars; Phase 6 added the enemy-side
                              ones -- throwPower, fallDamage, windMult, ally*,
                              grabBonus, graveChill, stormWindSlow */ }        // .NONE
interface DefenceContext  { int targetCount(); Target target(int i);
                            void removeFromHorde(Target t); void addProjectile(Projectile p);
                            Castle castle(); Barricade barricade(); Outpost outpost();
                            float wind(); int wave(); float grabCapacity();
                            Rng rng(); SimulationTrace trace(); long step();
                            CombatModifiers modifiers(); AllyFactory allies();
                            void onCastleDestroyed(); }

// Towers
abstract DefenceTower  (Bowman, Ballista, Cannon)
  void update(double dt);  Target pickTarget();  float reachTo(Target), damageVs(Target);
  void takeDamage(float), applyStun(float), restore();  int upgrade();
  boolean canOvercharge();  boolean overchargeFire(float aimX, float aimY, float power);
  boolean contains(float px, float py);         // pygame rect semantics
  float x(), y(), muzzleX(), muzzleY(), damage(), reload(), range(), splash(),
        cooldown(), hp(), maxHp(), stun(), overchargeCd(), aim(), recoil();
  boolean disabled();  int level();

// Castle
int   wallLevel(), tierIndex(), maxVisualLevel(), slotCapacity(), towerCount(), countOf(TowerType);
boolean upgradeWall(), visualCapped();   String tierLabel();   CastleTier tier();
float repair(), repair(float), hp(), maxHp(), frontX(), keepRight(), flash();
DefenceTower addTower(TowerType), towerAt(float x, float y), smashRandomTower(float, float);
void  takeDamage(float), splashHit(float x, float y, float r, float dmg, float stun),
      restoreTowers(), update(double dt);
Array<TowerSlot> freeSlots();  Array<DefenceTower> towers();

// Barricade / SpikeWalls / Outpost
Barricade:  boolean alive(), buy();  void takeDamage(float), update(double);
            float x(), topY(), hp(), maxHp();  int level();   static float WIDTH
SpikeWalls: boolean upgrade();  void bite(Target), setLevel(int);  float damage();  int level()
Outpost:    boolean upgrade(), isTurret(), hasPrisoner(), canTrap(Trappable), trap(Trappable);
            int level(), guns(), crewCount();  float overdrive(), gunDamage(), gunReload();
            Target pickTarget();  void hurtPrisoner(float), updatePrisoner(double), update(double);
            boolean bodyContains(float, float), trapAreaContains(float, float);
            float aimPointX(), aimPointY(), prisonerHp(), prisonerMax(), prisonerHit();

// Data
DefenceTable.load(JsonSource) → tower(TowerType), tier(int), slot(int),
                                createTower(ctx, type, x, y)
```

## Important invariants

0c. **`CombatModifiers` is the talent tree now.** From Phase 9 the object behind
   `ctx.modifiers()` is `TalentTree`, and its values are computed live from the
   current ranks — so a talent bought mid-run is in effect on the next step here
   with no cache to invalidate. Nothing in this package names `TalentTree`, and
   nothing may: see [`TALENTS.md`](TALENTS.md).

0. **Gameplay time is `double`.** Tower reload, cooldown, rebuild, stun and overcharge lockout, and the Outpost's crew reloads, raise timer and prisoner regen lockout are `double` seconds; positions,
   velocities, angles and drawing state stay `float`. `update`/`think` receive
   the canonical `double` step and narrow it once, themselves, with
   `float fdt = (float) dt`. See
   [`SIMULATION.md`](SIMULATION.md), "Gameplay time is double" — the rule exists
   because float timers made a Hard Dragon breathe 16 fireballs where Python
   breathes 15.

1. **Targeting is a parity implementation.** A linear scan of every target in
   list order; **lowest score wins and ties go to the earlier entry** (the
   comparison is strictly "better than"). Scores are two components compared
   lexicographically, because the Ballista returns a Python tuple. Deliberately
   O(E²) where Python is (the Cannon's cluster count): correctness first, and
   Phase 12 may replace it once a parity test proves the replacement picks the
   same target every time.
2. **The three counters are the character of each unit** and are in data:
   Bowman `airRangeMult 1.9`, Ballista `bonusVsAir 3.0` (+200%), Cannon
   `bonusVsHeavy 3.0` and `hitsAir false`.
3. **Bowmen bake their counters in at fire time; Ballista and Cannon send them on
   the projectile.** Both are Python's. Copying the Ballista's pattern into the
   Bowman would apply the talent damage multiplier twice.
4. **`airRangeMult` stretches upward only** — `dy < 0` means above, since y grows
   downward.
5. **No single blow may take more than 42% of a tower's maximum**, so a
   full-health tower always survives one hit. A downed tower does not
   regenerate, aim, cool down or fire, and comes back at **half** health.
6. **Reinforcement never refuses.** Past the last visual tier each level adds
   `520 * 1.16^(n-1)` health and the label picks up a `+N`. The gain is added to
   *current* health too, so reinforcing mid-fight is a real heal, and every
   platform gets 22% tougher with it.
7. **Wall slots fill front-first**, by a **stable** descending-x sort, so slots at
   equal x keep their declaration order.
8. **Slot occupancy is tested by position**, which is why `DefenceTable` rejects
   two slots at the same point.
9. **Hit-testing a tower uses pygame semantics** — integer-truncated bounds,
   half-open on the right and bottom. It decides whether a shell landing exactly
   on a tower's edge hits it or carries on into the wall.
10. **The Outpost has no health.** It cannot be attacked, and no accessor for it
    exists — the Python self-test asserts `not hasattr(g.outpost, "hp")`.
11. **Levels past the Outpost cap add firepower, not crew** — no extra bodies, no
    extra gun slots.
12. **The prisoner is updated before the garrison check**, so a captive keeps
    raising skeletons at an ungarrisoned outpost. Regeneration is locked out for
    a second after each hit, and the raise timer does not bank while the ally cap
    is full.
13. **All randomness comes from the seeded gameplay `Rng`** — tower cooldown
    jitter, crit rolls, the boss's tower smash. No `new Random()` anywhere.
    Every test that touches randomness names its seed.
14. **Tracing is observation only.** `DefenceIntegrationTest.tracing` runs the
    same scenario with tracing on and off and asserts the outcomes are identical.
15. **Numbers are data, behaviour is Java**, and every config id is a stable
    semantic id. `DefenceTable` validates at load and fails loudly: duplicate
    ids, non-positive cooldown/range/damage/health, negative splash, unknown
    projectile kind, unknown tower id, missing stat block, a counter below 1.0, a
    castle tier that does not strengthen, a slot outside the world or sharing a
    position.
15b. **A tower's muzzle is a gameplay origin, not a skin attachment.**
    `muzzleX()`/`muzzleY()` derive from the config box, so a skin that declares a
    `muzzle` attachment somewhere else moves the flash artwork and not the
    projectile. `SkinIndependenceTest.towerMuzzleIsSkinIndependent` proves it by
    firing a real shot under two wildly different skins.
16. **Allocation discipline in the step:** no per-target rectangle or vector,
    reused `float[]` for scoring and lead points, indexed loops throughout, no
    per-shot target list. `Castle.freeSlots()` does allocate — it runs on a shop
    purchase, not per frame.

## Deliberately deferred to Phase 12

Shared candidate cache across towers · Cannon cluster grid · projectile
broadphase · projectile pooling. Each changes which pairs are tested or in what
order, so each needs a parity proof first.

## Relevant source files

```
core/src/main/java/com/mymmer/castledefense/defence/
    Target.java  Trappable.java  AllyFactory.java  CombatModifiers.java  DefenceContext.java
    Projectile.java  ProjectileKind.java
    DefenceTower.java  Bowman.java  Ballista.java  Cannon.java
    TowerType.java  TowerConfig.java  TowerSlot.java  CastleTier.java  DefenceTable.java
    Castle.java  Barricade.java  SpikeWalls.java  Outpost.java
assets/data/defences.json
```

## Relevant tests

```
core/src/test/java/com/mymmer/castledefense/defence/
    TowerCountersTest.java       (7)   counters, air envelope, target preference
    TowerLifecycleTest.java      (16)  hp cap, downtime, regen, stun, upgrade, hit test
    OverchargeTest.java          (14)  eligibility, power, multipliers, lockout
    CastleTest.java              (21)  tiers, infinite reinforcement, slots, splash, smash
    StructuresTest.java          (28)  barricade, spikes, outpost garrison + prisoner
    ProjectileTest.java          (14)  trajectory, pierce, splash, crits
    HostileResolutionTest.java   (10)  obstacle priority under overlap
    DefenceTableTest.java        (16)  shipped values vs castle.py, and validation
    DefenceIntegrationTest.java  (6)   seeded reproducibility, tracing, ordering
    FakeTarget.java  TestWorld.java  DefenceJson.java    (test doubles)
```
