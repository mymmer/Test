# Subsystem contract — Active skills

## Purpose

Three active abilities, one unlocked per boss defeated. Gameplay only.

## Owns

* Unlocking, in a fixed order (`SkillPanel`, `SkillId`).
* Cooldowns, targeting and casting.
* The three effects: Lightning, Meteor + `FireZone`, `Tornado`.

## Does not own

* **The skill bar.** No slot rectangles, no icons, no hotkey codes, no pixels.
  Phase 10 turns a tap into `select(id)` then `castAt(x, y)`.
* **Rendering.** No bolts, no meteor sprites, no funnel. Phase 11 reads the state
  these leave behind.
* **The talents.** Multipliers arrive through `CombatModifiers`.
* **Stepping the effects.** `RunWorld` owns the `FireZone` and `Tornado` lists
  and updates them in the world step.

## Unlocking

`main.py:772 SKILL_UNLOCK_ORDER` — one slot per boss defeated, fixed order:

```
1st boss  ->  lightning     targeted
2nd boss  ->  meteor        UNTARGETED: it rains across the field
3rd boss  ->  tornado       targeted
4th on    ->  nothing to unlock; the boss pays 3 talent points instead
```

`unlockNext()` returns what it unlocked, or null when the bar is full. The award
is the caller's business — `RunWorld.onBossDefeated` pays 2 or 3.

## Cooldowns are the time domain

Every cooldown is a `double`, decremented by the canonical step. They do not run
while the world is frozen, so shopping is not recharging.

`fullCooldown = base * skillCd`, computed **at the moment of the cast**. A Focus
rank bought while a skill is recharging does not shorten the wait already
running, but does shorten the next one. That is the source's behaviour:
`full_cooldown` is a method, called once when the cooldown is set.

| skill | base |
|---|---|
| lightning | `LIGHTNING_COOLDOWN` 14 s |
| meteor | `METEOR_COOLDOWN` 26 s |
| tornado | `TORNADO_COOLDOWN` 30 s |

Arcane Focus caps at 45% off.

## Targeting

```
select(id)          arms a targeted skill; an untargeted one is simply "ready"
castAimedAt(x, y)   casts what is armed
castAt(id, x, y)    casts directly -- what an untargeted skill uses
cancelAim()         drops the aim without casting
```

The aim is dropped automatically if the skill stops being ready. Coordinates are
**virtual world units**; no device reaches this.

## Lightning

A **column**, not a circle: everything within `LIGHTNING_RADIUS × skillArea` of
the cast's x is hit at any height, so a Gargoyle overhead is caught with the mob
below it.

```
damage = maxHp × LIGHTNING_DAMAGE × skillPower × lightningMult
boss   = damage × 0.25          shaken, not vaporised
```

A share of each victim's **maximum** health, so it clears a crowd of anything.
Note `lightningMult` is the Lightning Rod talent, shared with storm lightning.

## Meteor and FireZone

Untargeted: it rains across a band anchored on the cursor
(`max(CASTLE_FRONT + 40, x - 430)`, then 860 px wide, clamped to the arena).

Each of `int(METEOR_COUNT × meteorCount)` rocks is an ordinary `Projectile` with
splash and 40% gravity, **plus** a `FireZone` created at the same moment — the
burning ground exists from cast time, not from impact. That is the source's
arrangement and it is why the fire starts before the rocks land.

`FireZone` is also a column: `|e.x - x| <= radius`, flyers exempt. It burns
**continuously** — `dps × dt` every step — so there is no tick cadence to get
wrong. Emberfall lengthens its life; the radius is `meteorRadius × 0.75`.

## Tornado

Four distinct pieces, and flattening any into "push things away" would be a
different mechanic:

1. **It drifts** — `x += TORNADO_SPEED × dt`, away from the castle, for its whole
   life.
2. **It catches** — a mob on foot inside the radius goes airborne through
   `onRelease(0, 0)`, which counts as a player fling so the throw scores.
3. **It carries** — `tornadoHold` is renewed to 0.12 s every step, so the
   airborne integration applies only 12% of gravity. It really is carried.
   `vx` spirals toward the axis by `TORNADO_SWIRL`, `vy` lifts by
   `TORNADO_LIFT × pull`, clamped to [-900, 220].
4. **It throws** — on expiry everything it caught is hurled downfield at
   `|vx| + uniform(760, 1180)` and `-uniform(520, 820)`.

`pull = (1 - d/radius) × power`, where `power` is `tornadoMult` — Eye of the
Storm drives the lifetime **and** the pull from one value.

**The catch list is what it caught**, keyed by uid, because the funnel drifts
away from where it picked things up and a heavy mob lags behind. It *also*
throws anything airborne within `radius × 1.4`. Both halves are the source's.

**What it will not touch:** bosses, ungrabbable units and armoured ones — the
same gate as the cursor, so a plated Siege Ram rides it out.

## Snapshots

Every area effect iterates an `EntityList.Snapshot`, never the live horde.
Python walks `list(g.enemies)` and the copy is load-bearing: a blast can kill a
**boss**, and a boss's death purges it and its debris from the roster on the
spot. This is the same defect Phase 8 found in `Volatile.detonate`, and it is not
allowed to recur.

## Randomness

Every draw — meteor positions and speeds, tornado throw speeds, the fire zone's
visual phase — comes from the gameplay `Rng`. A seeded run drops identical rocks.
No `new Random()` anywhere.

## Query surface for Phase 10

`unlockedSkills()`, `isUnlocked(id)`, `isReady(id)`, `cooldownRemaining(id)`,
`fullCooldown(id)`, `aiming()`, `view()` / `view(id)` → immutable `SlotView` with
a `progress()` for the sweep.

## Relevant source files

`main.py:545` (`FireZone`), `main.py:584` (`Tornado`), `main.py:665` (`Skill`),
`main.py:697-767` (the three casts), `main.py:775` (`SkillPanel`),
`main.py:1561` (`on_boss_defeated`).

## Relevant tests

`SkillTest` (unlocking, cooldowns, targeting, all three effects, bosses,
snapshots, determinism), `ProgressionNineParityTest` (constants and every
scaling talent against Python), `ProgressionSmokeTest`.

## Phase 10 — the skill bar

One slot per skill, appearing as each is unlocked. The bar reads `SkillPanel` and
holds nothing: `isReady(id)` for readiness, `cooldownRemaining(id)` and
`fullCooldown(id)` for the shade, `aiming()` for which slot is armed.

**Readiness is never inferred from a rounded remaining-seconds value.** A skill
one step away from coming back must not display as ready, and
`UiIntegrationTest.readinessIsNeverInferredFromASeconds` steps to exactly that
moment to prove it.

**Two-stage targeting.** Tapping a targeted skill (Lightning, Meteor's zone,
Tornado) arms it and the next world press casts it at that point; tapping an
untargeted one fires immediately. The source casts at the mouse position, which a
finger does not have. Both stages are claimed by the interface, so arming does not
grab the enemy under the button and casting does not grab the one under the
target (`UiInputTest.armingDoesNotGrab`, `armThenCastThroughTheRouter`).

**The bar has no timer.** Opening the Endless armoury freezes the world and the
cooldown display freezes with it, to the bit, because the number displayed is the
panel's own (`UiIntegrationTest.cooldownComesFromGameplay`).
