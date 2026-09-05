# Subsystem contract — Difficulty

## Purpose

Three presets, eight knobs. Nothing else in the game reads a difficulty.

## The complete audit

Read out of `main.py:1281-1319` — every `Game` property that touches
`self.difficulty`. There are exactly eight, and this is all of them:

| Knob | Reaches | Easy | Normal | Hard |
|---|---|---|---|---|
| `scale` | enemy health and damage scaling | 0.80 | 1.00 | **1.30** |
| `gold` | the crowd gold multiplier | **1.15** | 1.00 | 1.00 |
| `headstart` | the effective wave a boss spawns at | 0.00 | 0.00 | **0.25** |
| `speed` | flat multiplier on base walking speed | 0.90 | 1.00 | **1.40** |
| `hpCurve` | how steeply health grows per tier | 0.80 | 1.00 | **1.60** |
| `bossFire` | boss projectile intervals | 1.00 | 1.00 | **0.50** |
| `eliteHorn` | what the Challenge Horn calls in | no | no | **yes** |
| `grabCd` | the wait between grabs | 0.00 | 0.25 | **0.50** |

There is **no** per-difficulty spawn rate, wave composition, tower stat, skill
tuning or talent change. Hard changes seven of the eight; only `gold` is
untouched.

## Where each is consumed

| Knob | Consumer | When |
|---|---|---|
| `scale` | `Enemy` constructor, via `EnemyContext.enemyScale()` | at spawn |
| `hpCurve` | `Enemy` constructor, via `enemyHpCurve()` | at spawn |
| `speed` | `Enemy` constructor, via `enemySpeedScale()` | at spawn |
| `gold` | `Scoring.crowdGoldMultiplier`, via `RunSession.goldScale()` | live |
| `headstart` | `WaveDirector` / `EndlessDirector` boss summon | at summon |
| `bossFire` | `Boss` constructor, via `BossContext.bossFireScale()` | at spawn |
| `eliteHorn` | `ChallengeHorn` | at the blow |
| `grabCd` | `CursorInteraction.grabCooldown` | at run start, and on a Light Fingers rank |

## Applied exactly once

The classic porting failure is applying scaling at config load *and* again at
spawn, or twice down a subclass chain. Easy and Hard bracket Normal from both
sides, so a squared factor cannot hide:

* an ordinary enemy's health at wave 12 is linear in the knobs, not quadratic;
* speed is exactly `base × 1.40` on Hard and `base × 0.90` on Easy;
* a boss's `fireScale` is captured once at construction, so `fireDelay(2.0)` is
  1.0 s on Hard and 2.0 s elsewhere;
* the grab delay is one difficulty value scaled by one talent — no double dip;
* the gold multiplier is `crowd × horn × killGold × goldScale`, each once.

`DifficultyIntegrationTest` asserts all of these.

## Settings versus the running run

Python reads `Game.difficulty` **live** off `Settings` — but the UI gives no path
from a running game to the settings screen: `PAUSED` only toggles back to
`PLAYING`, and `MENU` is reachable only through `GAMEOVER`, which calls
`reset()` first. So the observable contract is:

> **a run's difficulty cannot change while it runs.**

The port therefore **captures the difficulty at run creation**
(`RunWorld.beginRun`). That is faithful to the observable behaviour and
structurally safer: a Phase 10 pause menu with a difficulty button could not
retroactively rescale a live horde. The saved preference is the *preferred*
difficulty and applies to the **next** run.

Note that even in Python the capture is partial: already-spawned enemies and
bosses hold the scaling they were built with, so a mid-run change would only
affect future spawns anyway.

## Hard is an overhaul, not a multiplier

Beyond the numbers, Hard changes *what happens*:

* **the Challenge Horn** fields `HARD_HORN_RUSH` (10) elites from
  `HARD_HORN_UNITS`, rolled `HARD_HORN_TIER_BONUS` (3) tiers deeper — and in
  Classic it **swaps** chaff for elites rather than adding, so the head-count and
  therefore wave clearing are unchanged;
* **bosses** arrive at `wave + round(wave × 0.25)`, so they are scaled as though
  the run were further along;
* **bosses fire twice as fast**, which is the boundary that made the whole
  time-domain rule necessary — see `SIMULATION.md`.

## The Berzerker quirk, under difficulty

`Berzerker.think` recomputes `speed` from the base and the wave curve, discarding
the difficulty and tier multipliers its stored speed carries. So a Hard Berzerker
and a Normal one **cover the same ground**, while a Hard Scout genuinely moves
faster. Connecting the real difficulty did not fix that, and must not:
`DifficultyIntegrationTest.berzerkerQuirkStillHolds` measures the distance walked
rather than the restored field, because `think` mutates and restores `speed`.

## Relevant source files

`main.py:121-160` (the table), `main.py:1281-1319` (the properties).

## Relevant tests

`DifficultyIntegrationTest`, `DifficultyTableTest`, `EnemySpeedTest`,
`ChallengeHornTest`, `BossParityTest`.
