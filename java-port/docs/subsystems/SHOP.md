# Subsystem contract — Shop

## Purpose

The armoury: what things cost, whether they are on offer, and what buying one
does. Eleven items, transaction logic only.

## Owns

* The eleven definitions and their validation (`ShopTable`, `ShopItemDef`).
* Prices, including the discount (`Shop.rawCost`, `Shop.currentCost`).
* Availability (`Shop.isAvailable`).
* Purchases and every failure path (`Shop.buy`).
* The per-tower purchase counters that drive their prices.

## Does not own

* **The shop screen.** No cards, no positions, no fonts, no icons, no mouse, no
  touch. Phase 10 reads `Shop.view()`.
* **The simulation.** A shop cannot step the world. In Endless it runs while the
  world is frozen, and a shop that could step would be a shop that could break
  that.
* **Gold.** `RunSession` holds the purse; the shop asks it to spend.
* **The talents.** The discount arrives through `CombatModifiers` like every
  other talent effect; the shop names no `TalentTree`.

## The order of a purchase

Transcribed from `main.py:1815 try_buy`, and the order is the contract:

```
1  availability   unavailable  -> refuse, nothing happens
2  price          raw curve x discount, truncated once
3  purse          not enough   -> refuse, nothing happens
4  apply          the effect runs; it MAY still refuse
5  deduct         gold comes off only after the effect succeeded
6  count          and the purchase counter moves only for the towers
```

**Gold is deducted last.** An effect that refuses at step 4 costs the player
nothing and does not move the price for next time. `Shop.Result` names which
stage refused: `UNAVAILABLE`, `TOO_EXPENSIVE`, `REFUSED`, `UNKNOWN`.

Repair on a pristine keep is the one refusal reachable past the availability
check, which is exactly why the two stages are separate.

## The discount, and why the ordering matters

Python: `int(cost_fn() * discount)`.

The **raw** curve value — fractional, straight out of `110 * 1.26**n` — is
multiplied by the discount and truncated **once, at the end**. Not
`int(curve) * discount`, which rounds twice and differs by a coin at most prices.
The fixture emits both and `ProgressionNineParityTest` asserts the port matches
the first and that the two genuinely differ.

**Cost constants are `double`.** Not because they are times, but because
`150f * 1.4f` is 209.9999964 and truncates to 209 where the source charges 210.
That is a real, visible coin.

*One faithful deviation.* Python assigns `item.discount` inside its **draw**
loop, so a price technically depends on the shop having been rendered. Since the
screen is always drawn before the player can click, the observable behaviour is
"the discount always applies", and that is what `currentCost` computes — live, at
the point of sale, with no rendering in the path.

## The eleven items

| id | curve | counter | cap | effect |
|---|---|---|---|---|
| `bowman` | `110 × 1.26^n` | purchases | — | station or upgrade Bowmen |
| `ballista` | `250 × 1.28^n` | purchases | — | station or upgrade Ballistas |
| `cannon` | `380 × 1.28^n` | purchases | — | station or upgrade Cannons |
| `wall` | `180 × 1.62^(lvl-1)` | wall level | **uncapped** | health, a slot, a tier |
| `bounce` | `200 × 1.55^lvl` | bounce level | 5 | thrown mobs bounce |
| `grab` | `260 × 2.05^lvl` | grab level | 4 | cursor lifting weight |
| `multi` | `340 × 1.85^lvl` | multi level | 3 | mobs held at once |
| `outpost` | `300 × 1.5^lvl` | outpost level | **uncapped** | garrison, then overdrive |
| `barricade` | four cases, below | — | 5 + top-up | build / rebuild / reinforce |
| `spikes` | `190 × 1.7^lvl` | spike level | 4 | parapet spikes |
| `repair` | `max(50, int(missing × 0.55))` | — | — | heals 40% of maximum |

Order is part of the contract: the Python shop is keyed by number as well as by
click, so reordering changes what a hotkey does.

### The barricade's four cases

```
level == 0                          240
not alive                           int(150 × 1.4^level)      rebuild the wreck
hp < maxHp and level >= cap         int(60 + missing × 0.35)  top up a capped one
otherwise                           int(240 × 1.5^level)      reinforce
```

Checked in that order. It is available whenever it is below the cap **or**
damaged.

## The tower fallback

`main.py:1057 buy_tower`, three outcomes:

* **A slot is free** — a new tower is stationed.
* **No slot, this type already stands** — **every** tower of that type is
  upgraded. Not the weakest, not the nearest: all of them, in list order. Buying
  a fourth Bowman with a full wall makes all three existing Bowmen better.
* **No slot and none of this type** — refused, and it costs nothing.

No "better" selection strategy is invented.

## Uncapped items

The wall and the outpost never stop being available. Past its last *visual* tier
the keep keeps gaining health; past the crew cap the outpost buys raw firepower.
Both are the source's behaviour.

## Validation

Fatal on: a duplicate id, an unknown curve / counter / effect / tower, a
non-positive base, a geometric growth below 1, a `maxLevel` below 1, a `tower`
field on a non-tower item, a missing bespoke field, a `healFraction` outside
(0, 1], and **a count other than eleven** — every source item must have a Java
disposition.

## Mode integration

* **Classic** — a wave ends, `WaveDirector` hands to `SHOP`, the player buys,
  `RunWorld.startNextWave()` sends in the next wave.
* **Endless** — `RunWorld.openRealtimeShop()` freezes the world, the player buys,
  `resumeFromShop()` continues. The freeze contract is Phase 8's and the shop
  does nothing to weaken it.

## Query surface for Phase 10

`items()`, `currentCost(id)`, `rawCost(id)`, `isAvailable(id)`, `canBuy(id)`,
`purchaseCount(id)`, `view()` / `view(id)` → immutable `ItemView` carrying cost,
availability, affordability and level.

## Relevant source files

`main.py:885` (`ShopItem`), `main.py:1039-1240` (`_build_shop` and the item
list), `main.py:1815` (`try_buy`).

## Relevant tests

`ShopTest` (inventory, curves, discount ordering, availability, every failure
path, the fallback), `ProgressionNineParityTest` (63 curve cases, 25 discount
cases, 11 bespoke cases against Python).

## Phase 10 — the shop screen

Every number on a card comes from `Shop`: `currentCost(id)`, `view(id).affordable`,
`isAvailable(id)`, `view(id).level`, and `buy(id)` to purchase. There is no cost
formula, no gold arithmetic and no availability rule in the screen. Phase 9's
discount ordering is the precedent — the one place that knows what a thing costs
is the shop, and a second implementation in a draw method is how the two drift
apart. `UiIntegrationTest.uiDoesNotComputePrices` asserts the card shows exactly
what `buy()` will charge, with a Haggle discount applied.

**An unaffordable card is still pressable.** Python calls `try_buy` for any card
the click lands on and answers "maxed out" or "Not enough gold!"; the refusal
belongs to the shop, not to the hit test. How a card *looks* is answered by
`shop.view(id)` at draw time rather than by a flag cached on the rectangle.

The grid reflows rather than scaling: the column count comes from the available
width, so a 20:9 screen gets more cards per row instead of wider cards. All eleven
are laid out at every tested shape.

The number hotkeys keep the source's order — 1..9 then 0 — which is why the item
order in `shop.json` is load-bearing.

One screen serves both modes: Classic shows it between waves and the button sends
in the next one, Endless shows it mid-fight and the button resumes. The freeze is
the state machine's, not the screen's — see [`UI.md`](UI.md) §7.
