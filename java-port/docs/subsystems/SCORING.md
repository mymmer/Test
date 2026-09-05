# Subsystem contract — Scoring and economy

## Purpose

Decide what a kill is worth and what a throw is worth. Two formulas, both short,
both with a quirk that looks like a bug.

## Owns

* The crowd gold multiplier and the kill payout.
* Fling scoring: distance, airtime, combo, and the award.
* The Classic wave bonus.
* `bestFling` and `bestCombo`.

All of it is `Scoring` (pure functions) plus the totals in `RunSession`.

## Does not own

* **Spending.** The [shop](SHOP.md).
* **The talent values** the formulas multiply by — `CombatModifiers` supplies
  them, and from Phase 9 the thing behind that interface is the real
  `TalentTree`. See [`TALENTS.md`](TALENTS.md).
* **The floating text** that shows a payout. Phase 11.

## Gold

```
n     = live enemies RIGHT NOW, including the one that is dying
step  = POP_GOLD_STEP (0.055) * goldPop
base  = min(POP_GOLD_CAP (3.0) * goldPop, 1 + step * max(0, n - POP_GOLD_FREE (4)))
mult  = base * (1 + hornBonus) * killGold * goldScale
pay   = max(1, round(baseGold * mult))
```

* The first four mobs on screen are free; the fifth starts paying.
* The multiplier reaches its 3.0 ceiling at **41** live mobs.
* The cap is scaled by the talent as well as the slope, so Prospector raises the
  ceiling too. That is what the source does.
* A kill always pays at least one coin.

### The quirk: the dying mob pays for itself

`Enemy.die` reads the multiplier **before** clearing its own `alive` flag, so a
lone mob is `n = 1` and a crowd of ten is `n = 10` — never `n - 1`. Removing it
from the count first would quietly cut every reward in the game.

Reproduced deliberately. `PORT_ANALYSIS.md` §13.

### Silence

A Treasure Goblin that escapes calls `die(silent=true)`: no gold, no kill count,
no fling score, no particles. The tension of a goblin getting away depends on it
paying nothing at all.

## Score

```
travel  = |x - x0| + max(0, y0 - peak)          // across, plus how high
base    = travel * SCORE_PER_PX (0.32) + airtime * SCORE_PER_SEC (55)
combo   = 1 + SCORE_COMBO_STEP (0.75) * hits
points  = int(base * combo)                     // TRUNCATED
awarded = int(points * (1 + hornBonus) * scoreMult)   // truncated AGAIN
```

* `peak` is the *smallest* y reached, because y grows downward: a mob thrown
  straight up scores its height even if it lands where it started.
* **Both truncations happen, in that order.** Rounding once at the end would give
  a different number.
* `bestFling` tracks the **awarded** figure, not the raw one.
* `bestCombo` is only touched when the fling actually struck something
  (`hits > 0`); a solo fling sets no combo record however large it is.
* The airtime comes from `simulationTime()`, which advances in every state — as
  in Python, where `Game.time` is incremented before the state check.

## The combo lifecycle

There is no decaying combo counter in this game, and inventing one would be a
different game. A combo exists **only within one fling**: it is
`1 + 0.75 * fling_hits`, where `fling_hits` counts the mobs that airborne body
struck between leaving the cursor and landing. It is resolved and banked at
`resolve_fling`, and the next throw starts from zero.

What the run keeps is `bestCombo`, a high-water mark, and `comboFlash` — a
visual amplitude that decays at 1.6/s in the always tier, so it keeps fading on
the pause screen.

## The wave bonus

```
bonus = 80 + wave * 22 + int(wavePurse)
```

Awarded by `WaveDirector.endWave`, after the talent point and before the shop.

## Important invariants

1. **The dying mob counts itself.** See above.
2. **Two truncations, in the source's order.**
3. **`bestCombo` only moves on a real combo.**
4. **A silent death pays nothing** — not gold, not kills, not score.
5. **The horn multiplies both** gold and score, for the rest of the stretch it
   was blown in (the wave in Classic, the run in Endless).
6. **Nothing here is a time-domain value**, so nothing here is a `double` except
   the airtime it is handed. Points and gold are integers; multipliers are
   floats.

## Relevant source files

`main.py:1332` (`gold_multiplier`), `main.py:1441` (`add_score`),
`main.py:1783` (`end_wave`), `enemies.py:420` (`die`),
`enemies.py:506` (`resolve_fling`), `sprites.py:149-154`.

## Relevant tests

`ScoringTest`, `ProgressionParityTest`, `EnemyPhysicsTest` (the fling that
produces the numbers).
