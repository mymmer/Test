# Subsystem contract — Talents

## Purpose

Passive progression. Points earned per wave (Classic) or per minute survived
(Endless), spent on 38 tier-gated nodes across six branches.

**All of it is per-run.** Nothing here is saved — see
[`PERSISTENCE.md`](PERSISTENCE.md).

## Owns

* The 38 definitions and their validation (`TalentTable`, `TalentDef`,
  `TalentBranch`, `TalentEffect`).
* The point balance, the ranks and the branch totals (`TalentTree`).
* **The one place a rank becomes an effect.**

## Does not own

* **When a point is earned.** Phase 8's directors decide that and award through
  `TalentIncome`. The tree never has a clock.
* **The talent screen.** No coordinates, no cards, no fonts, no touch areas.
  Phase 10 reads `TalentTree.view()`.
* **The wording.** `nameKey()` / `descriptionKey()` are localisation keys derived
  from the id.

## The modifier seam

```
TalentTree  ->  CombatModifiers  ->  gameplay systems
```

`TalentTree implements CombatModifiers`, the interface every system was already
written against in Phases 5–8. A tower asks its context for
`modifiers().towerRate()` exactly as it always did; the tree is simply what is
behind that call now. **Nothing in `enemy`, `boss`, `defence`, `shop` or `skill`
names `TalentTree`**, and there is no `enemy.getGame().talents…` path.

`ArchitectureTest` covers the package rules; `TalentEffectsTest` asserts
`run.modifiers() == run.talentTree()` — one object, no copy.

## Live values, never a snapshot

Every modifier is computed from the current ranks on each call, as Python's
`@property` reads are. That removes a class of bug by construction: there is no
cached copy anywhere for a purchase to forget to invalidate, so a talent bought
mid-run is in effect on the next step for *every* system at once.

The two things that genuinely *are* captured are captured because the source
captures them: a `Projectile` samples `critChance` and `splashMult` once at
construction, so a talent bought while a shot is in the air does not retune that
shot.

**One exception, and it is explicit.** `CursorInteraction` caches its grab
cooldown, because it is set once at run start and read on every grab. Light
Fingers is the only talent that changes it, so the caller invokes
`RunWorld.refreshGrabCooldown()` when a rank lands. That is a named method with a
named reason rather than a silent staleness.

## Gating

```
unlocked(node)     branchPoints(node.branch) >= node.tier
canPurchase(node)  points > 0 AND unlocked AND rank < maxRank
```

`tier` is **the number of points already in the node's own branch** — not a row
index and not a named prerequisite. So:

* the "graph" is six independent ladders; there are no edges, so a cycle and a
  self-dependency are not expressible;
* points spent on *any* node of a branch open that branch's next tier;
* points in another branch do nothing for it;
* a maxed node is still "unlocked" — that is a different question from
  "purchasable".

**A failed purchase changes nothing.** No point spent, no rank moved, no branch
total shifted. The guard returns before any mutation.

## Validation

`TalentTable.load` is fatal on: a duplicate id, an unknown branch, an unknown
effect, a negative tier, a `maxRank` below 1, a non-positive `perRank`, a missing
or doubled branch declaration, and **a branch with no entry node** — the only
shape of unreachability this gating model admits.

A typo'd effect id would otherwise be a node the player can buy that silently
does nothing, which is indistinguishable from the one node that is *supposed* to
do nothing.

## The Deep Foundations quirk

`maxhp` — "Deep Foundations", *Castle maximum health +10% per rank* — **has no
effect**. `TalentTree.castle_hp` exists in `main.py` and nothing reads it; grep
the source and the only hit is its own definition.

Reproduced deliberately. `TalentTree.castleHpClaim()` exposes the number for a
Phase 10 tooltip and is **not** a `CombatModifiers` method, so the castle has no
way to reach it. `TalentQuirksTest` asserts both halves: buying all five ranks
moves the progression state and leaves `Castle.maxHp()` untouched, and adding a
`castleHp()` to the shared interface fails the build.

Making it work would be a balance change to a shipped game — a decision for after
the port, not a side effect of implementing the tree.

## The 38, by branch

| Branch | Nodes |
|---|---|
| offense | rate, power, crit, pierce, splash, overcharge |
| defense | maxhp*, regen, spikedot, towerhp, rebuild, thorns |
| utility | greed, lighthands, haggle, purse, lightfingers, showman, scavenge, sentinels |
| aero | stormwinds, throwarm, updraft, conductor, gale, tempest |
| necromancy | bonecraft, hostmaster, quickraise, gravechill, secondwind, bonewall |
| arcane | focus, amplify, widecast, emberfall, eyeofstorm, twincast |

\* dead effect, above.

Full table with tiers, caps, per-rank magnitudes and effect ids:
`TalentInventoryTest.SOURCE`, and `data/talents.json`.

## Two effects that are not `1 + v`

* **`tempest`** (Tempest Caller) is `2.0 if rank else 1.0` — a flag, not a
  per-rank multiplier.
* **`lightfingers`** is `max(0, 1 - v)`, so four ranks leave 20% of the delay and
  it can never go negative.

Six effects are capped, at the source's numbers: `rate` 0.45, `overcharge` 0.5,
`rebuild` 0.6, `thorns` 0.4, `haggle` 0.4, `quickraise` 0.5, `bonewall` 0.5,
`focus` 0.45.

## Income

Phase 8 owns the timing; the tree owns the balance.

| Event | Points |
|---|---|
| Classic wave cleared | 1 |
| Endless minute survived | 1 |
| Boss defeated, a skill slot free | 2 |
| Boss defeated, all three unlocked | 3 |

## Query surface for Phase 10

`availablePoints()`, `earnedPoints()`, `rank(id)`, `maxRank(id)`,
`branchPoints(branch)`, `isUnlocked(id)`, `canPurchase(id)`, `value(id)`,
`view()` / `view(id)` → immutable `NodeView`.

## Relevant source files

`main.py:288-537` — the `Talent` class, `TALENT_BRANCHES`, `TALENTS` and the
whole `TalentTree`.

## Relevant tests

`TalentInventoryTest` (all 38, exhaustively), `TalentTreeTest` (gating, caps,
failures, validation), `TalentEffectsTest` (cross-system, live),
`TalentQuirksTest`, `ProgressionNineParityTest` (every effect at every rank
against Python).

## Phase 10 — the talent screen

Data-driven end to end: the screen walks the table, branch by branch, and builds
one `UiRect` per node keyed by the node's stable id. There is no per-talent code
and no switch on a talent name anywhere in `TalentScreen`; a new entry in
`talents.json` appears on screen, and the only thing needing a hand is its
localisation key (which `UiTextTest` fails until it is added).

It reads the tree and never computes an effect. Rank, cap, unlocked, buyable,
points and purchase all come from `TalentTree`; the number in a tooltip is the
tree's own `valueAt(rank)`, so a tooltip cannot disagree with the game.

**Tap to inspect, tap again to buy.** Python hovers for the detail panel and
clicks to buy, which a touchscreen cannot do. A locked node is still pressable —
tapping it selects it and shows why it is locked, which is the mobile equivalent
of the hover — and the *tree* refuses the purchase. The gameplay command is
`purchase(id)` either way.

**Deep Foundations is unchanged**, quirk included: its description still claims
+50% castle maximum health at rank 5, nothing reads the value, and buying all
five ranks through the interface leaves the castle exactly as tough as it was
(`UiIntegrationTest.deepFoundationsStaysInert`). The port reproduces the game, not
its tooltip.

Buying **Light Fingers** calls `run.refreshGrabCooldown()` — the one talent whose
effect the cursor caches.
