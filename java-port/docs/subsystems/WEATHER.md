# Subsystem contract — Weather

## Purpose

Wind and thunderstorms, gameplay side. Phase 8 ports what the player *feels*;
Phase 11 ports what they see.

## Owns

* The single gameplay-authoritative wind value.
* Whether a storm is running.
* Ceiling lightning: eligibility, damage, the per-mob lockout.
* `stormFlash`, exposed as **state** for Phase 11 to consume.

## Does not own

* **Wind streaks, bolt sprites, the screen flash.** Phase 11.
* **When to roll.** The director says; see below.
* **The airborne height check.** The enemy's own integration already knows it is
  above `STORM_CEILING` and calls `strikeLightning`.

## One wind, read by everyone

`Weather.wind()` is the only wind in the game. Projectiles read it through
`DefenceContext.wind()`, airborne bodies read it through the same seam, and the
Gale talent reads it to decide whether a headwind is blowing.

**Nothing copies it into an entity.** A mob thrown before the wind changed feels
the new wind on its next step, exactly as in Python, because there is only ever
one number. `WeatherTest.oneWindForEverything` proves a shot and a body in flight
consume the same value on the same step.

Sign convention, from `sprites.py`: **positive blows away from the castle** — a
tailwind for the player's throws, which travel rightwards. Negative blows back
toward the wall.

## Rolling

```
wind  = uniform(-1, 1) * WIND_MAX (260)
storm = random() < STORM_CHANCE (0.30) * stormChance
```

Two draws, **in that order**, from the gameplay stream. Order matters: a seeded
run must reproduce both, and swapping them changes every subsequent value.

It happens at exactly two moments, and both belong to the director rather than to
a clock of its own:

* the start of a Classic wave, and
* each Endless tier step.

There is no continuous wind drift in the source and none is invented here.

A roll past 45% of `WIND_MAX` posts a `WEATHER_TAILWIND` or `WEATHER_HEADWIND`
banner; a storm posts `WEATHER_STORM`. `announce()` is separate from `roll()`
because Python's `roll_weather` takes an `announce` flag.

## Ceiling strikes

```
guard   storm running, mob alive, mob's own STORM_COOLDOWN (1.1 s) expired
damage  maxHp * STORM_DAMAGE (0.34) * lightningMult
after   the mob's lockout starts, stormFlash = 1, the screen shakes by 8
```

* The damage is a share of **maximum** health, so a wounded tank takes the same
  bolt as a fresh one.
* The lockout is per mob and lives on the mob, because it has to age with it.
* Flying enemies are **not** excluded. A Gargoyle cruises at `FLY_Y` 250, far
  below the 132 ceiling, so it can only be struck if the player threw it up
  there. The source has no special case and neither does this.
* The height test belongs to the airborne integration, which already runs it —
  the weather is asked, it does not look.

## Important invariants

1. **One wind value, no copies.**
2. **Two draws per roll, in order.**
3. **Rolled per wave / per tier, never per step.**
4. **A strike takes a share of maximum health, then locks that mob out.**
5. **`stormFlash` is visual state**, a float, and decays in the always tier — so
   it keeps fading on the pause screen.
6. **The lockout is time-domain**, so it is a `double` and lives on the enemy
   (`Enemy.stormCooldown()`).

## Talent hooks (Phase 9 fills these in)

`stormChance` scales how often a stretch is stormy; `lightningMult` scales the
bolt; `stormWindSlow` (Gale) slows the horde while a strong headwind blows —
that one is applied by `RunWorld.enemySlow`, not here, because it is a property
of an enemy rather than of the weather.

## Relevant source files

`main.py:1595` (`roll_weather`), `main.py:1425` (`strike_lightning`),
`sprites.py` (`WIND_MAX`, `STORM_*`).

## Relevant tests

`WeatherTest`, `ProgressionParityTest.weather`, `EnemyPhysicsTest` (the airborne
integration that consumes the wind).

## Phase 11 — weather visuals

Rendering reads the weather and draws it. It cannot create a strike.

- **Wind streaks** above `|wind| > 40`, positioned by a hash of index and time
  exactly as the source's `seed` expression does — not a random draw, so they
  stream rather than flicker.
- **The storm veil**, a full-screen tint at `stormFlash` strength.
- **Lightning bolts** as a jagged path from the clouds, keyed to the bolt so it
  keeps its shape for its whole life. In the source this draw perturbs the
  gameplay generator; here it cannot.

**Gameplay lightning and visual lightning are separate.** `Weather` chooses the
target, the damage and the timing; this draws the result. LOW quality shows fewer
streaks and a simpler bolt, and never removes a strike.
