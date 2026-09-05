# Subsystem contract — Modes and progression

## Purpose

Pace a run. Classic counts waves and stops between them; Endless counts seconds
and never stops. Everything that decides *when* something happens — a spawn, a
tier, a boss, a wave bonus, a talent point — lives here.

**Gameplay time is `double`.** Every clock and interval in this subsystem is a
double, and the Endless run clock is derived from a step count rather than
summed. See [`SIMULATION.md`](SIMULATION.md), "Gameplay time is double".

## Owns

* Run-level state: mode, difficulty, wave/tier, gold, score, combo records,
  the horn, the run clock, the run seed (`RunSession`).
* The two directors (`WaveDirector`, `EndlessDirector`) and the interface they
  share (`RunDirector`).
* The Challenge Horn, in both modes (`ChallengeHorn`).
* The economy and scoring formulas (`Scoring`).
* The banner queue (`Announcements`) and the shake budget (`ScreenShake`).
* Weather (`Weather`) — see [`WEATHER.md`](WEATHER.md).
* When a talent point is earned (`TalentIncome`). **Not** what it buys — from
  Phase 9 the sink is the real `TalentTree`, and the director still only ever
  says "award".
* The run assembly and the step order (`RunWorld`, in `game`).

## Does not own

* **Entities.** A director spawns through a seam and never touches a mob again.
* **The shop.** [`SHOP.md`](SHOP.md). The session only holds the purse;
  `RunWorld` exposes the state transitions (`openRealtimeShop`,
  `resumeFromShop`, `startNextWave`) and nothing that draws.
* **The talent tree.** [`TALENTS.md`](TALENTS.md). `TalentIncome` is a
  one-method sink and stays one.
* **Active skills.** [`SKILLS.md`](SKILLS.md). A boss defeat unlocks a slot and
  pays a bounty; the panel owns the rest.
* **Rendering.** The shake is a number, the banners are ids. Phase 10/11 consume
  them.
* **Wall-clock time.** Nothing here reads a frame delta or a system clock.

## Dependencies

`enemy` (composition, unlocks, types), `boss` (types, registry), `defence`
(castle, modifiers), `config`, `util.Rng`, `debug.SimulationTrace`.
`DirectorContext` is the seam; `progress` names no concrete world.

## Public API

```
RunSession       mode(), difficulty(), seed(), wave(), playTime(), playSteps()
                 gold(), addGold(int), spendGold(int), goldMultiplier(int alive)
                 score(), addScore(int points, int hits, float combo)
                 bestFling(), bestCombo(), comboFlash()
                 hornUsed(), hornBonus(), resetHorn(), consumeHorn()
                 kills(), thrownDamage(), platesTorn(), describe()

RunDirector      begin(), update(double dt), blowHorn(), pendingSpawns(), describe()

WaveDirector     startWave(), waveActive(), waveClearDelay(), spawnInterval(),
                 spawnTimer(), consumeWaveCleared(), lastWaveBonus()
                 MAX_ALIVE = 58, WAVE_CLEAR_DELAY = 1.1, FIRST_SPAWN_DELAY = 0.8

EndlessDirector  tierAt(double), spawnGap(), baseSpawnGap(double) [static],
                 rollMob(), spawnTimer(), talentSeconds(), talentPointsAwarded(),
                 scriptedBossesSent(), repeatBossesSent(), secondsToNextBoss()
                 MAX_ALIVE = 60, FIRST_SPAWN_DELAY = 1.2

Scoring          crowdGoldMultiplier(int, float, float, CombatModifiers)
                 killPayout(int, float)
                 flingTravel(...), flingCombo(int), flingPoints(float, double, int)
                 awardedScore(int, float, CombatModifiers)
                 waveBonus(int, CombatModifiers)

RunWorld         beginRun(GameMode, DifficultyConfig[, long seed])
                 openRealtimeShop(), resumeFromShop(), startNextWave()
                 director(), session(), weather(), announcements(), screenShake()
                 describeRun()
```

## The step order

`RunWorld` transcribes `main.py`'s `update`, including its three tiers:

```
ALWAYS   (every state, via GameWorld.AlwaysListener)
    screen shake decay, combo/horn flashes, storm flash, banner countdowns

PLAYING only
    cursor
    director        Endless timetable / Classic spawn queue
    castle, outpost, barricade
    dropped items
    enemies         a SNAPSHOT: trapping and raising change the roster mid-loop
    allies
    crowd separation
    projectiles
    sweep
    dangling-reference cleanup
    wave-clear check                                        (Classic only)
```

The order is observable: separation mutates x mid-traversal, and projectiles
resolve against wherever the mobs ended up. It is transcribed, not tidied.

## Important invariants

1. **Only `PLAYING` advances the run.** The director is called from the world
   tier, so a frozen state stops the run clock, the tier ladder, the boss
   timetable, the spawn gap and the talent drip together. This is what makes the
   Endless realtime shop a true freeze rather than a menu over a running game.
2. **The alive caps differ, and that is not a typo.** Classic 58
   (`enemies.py:2131`), Endless 60 (`main.py:241`).
3. **The alive cap gates the trickle, not the population.** Necromancer summons,
   Lich raises and the horn all add mobs without consulting it, so a busy field
   genuinely exceeds 60. Python does the same.
4. **A `FriendlySkeleton` does not hold a wave open.** It is not an `Enemy` and
   is not in the horde, so `aliveHostileCount()` never sees it. Neither does a
   mob marked dead but not yet swept.
5. **The wave-clear delay is strict.** `> 1.1`, and it resets to zero the moment
   anything hostile exists — a Lich raising the dead at 1.05 s must not let the
   wave end at 1.1. The 66th clear step is the one that ends the wave.
6. **The Classic wave bonus is `80 + wave * 22 + wavePurse`**, and the talent
   point is awarded before the gold, as in the source.
7. **The Endless repeat-boss clock is absolute.** `int((playTime - 360) / 120)`
   counts from the last *scripted* time, not from the last boss, so a boss that
   arrives late does not push the next one out.
8. **Endless talent income cannot drift.** Derived from the exact run clock, so
   an hour is 60 points — never 59.
9. **The horn is once per wave in Classic and once per RUN in Endless.** See
   [`SCORING.md`](SCORING.md) and `PORT_ANALYSIS.md` §13. Do not "fix" it.
10. **An empty Classic queue refuses the horn without spending it.** A wasted
    click is not a wasted horn.
11. **One RNG stream.** Spawn choice, jitter, weather, boss picks and horn packs
    all draw from the single gameplay `Rng`, in the source's order. See
    "Randomness" below.
12. **Banners carry ids, not sentences.** `Announcements.Id` plus an int and a
    stable subject id; the wording is Phase 10's business.
13. **The shake is capped at 14 and decays at 42/s**, in the always tier. Quality
    settings never reach it.

## Randomness

**One stream, deliberately.** Python draws every gameplay decision from the
single `random` module state, and the order of those draws is observable: a wave
is composed, *then* the weather is rolled, and swapping them changes every
subsequent value in the run. Separate streams per domain would be tidier and
would silently break replay parity with the source, so the port keeps one and
preserves the order.

Decorative randomness has its own stream already (`Rng.decoration()`), and
nothing in this subsystem touches it.

## Deliberately deferred

* Banner layout, the HUD, boss bars — Phase 10.
* Particles, weather visuals, the shake compositor — Phase 11.
* Per-domain RNG streams — only if a measured need appears, and never for
  elegance alone.

## Relevant source files

`main.py:1595-1800` (weather, Endless schedule, wave start/end),
`main.py:1332-1445` (gold multiplier, horn, score), `enemies.py:420-520`
(payout, fling), `sprites.py:149-154` (scoring constants).

## Relevant tests

`ClassicFlowTest`, `EndlessFlowTest`, `ScoringTest`, `ChallengeHornTest`,
`WeatherTest`, `RunStateTest`, `AnnouncementsTest`, `ProgressionParityTest`.

## Phase 10 — how a player reaches all of this

The flows above are now driven through `Navigation`, which holds every transition
in one class; no screen sets a game state itself
(`ArchitectureTest.screensRouteThroughNavigation`).

```
   MENU ──choose mode──> SHOP ──start/resume──> PLAYING ──> GAMEOVER ──> MENU
                          │  ^                     │  ^
                       TALENTS                  PAUSED
```

The Classic armoury's action button sends in the next wave; the Endless one
resumes. Both are `Navigation.startPlaying()`, which asks the session which mode
it is — the screen does not branch on mode to decide what the button *does*, only
what it is labelled.

**Restarting is a genuine reset.** `UiIntegrationTest.restartIsFresh` plays a
Hard Endless run with towers, talents, an unlocked skill on cooldown and a live
horde, loses it, returns to the menu through the game-over button and starts a
new Classic run — then asserts nothing survives: no talent ranks, no talent
points, no purchases, no towers, no unlocked skills, no lingering cooldown, no
enemies, no projectiles, no dropped items, starting gold restored, and the new
difficulty in force. `screensRebindAfterRestart` additionally proves the *screens*
read the new run's subsystems rather than the old ones they were holding.
