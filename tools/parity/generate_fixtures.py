#!/usr/bin/env python3
"""
Generate numeric parity fixtures from the authoritative Python game.

WHAT THIS IS
------------
The Java port reimplements a set of small numeric formulas -- wave scaling,
throw release, airborne integration, fall damage, bounce, shove, armour
stripping.  Each one is short enough that a transcription error is easy to make
and impossible to see.  This script produces reference values FROM THE PYTHON
SOURCE ITSELF and writes them to a JSON fixture the Java tests assert against.

WHAT THIS IS NOT
----------------
It is not a replay system and it does not compare whole simulations.  It covers
formulas that are deterministic, isolated, and expressible without a running
game object.  Anything involving the RNG, the enemy list, or the render loop is
out of scope here and belongs to the Phase 13 parity work.

HOW IT AVOIDS TOUCHING THE GAME
-------------------------------
`enemies.py` and `castle.py` import pygame and, at import time, build a game
world.  Importing them here would be both fragile and a side effect on the
authoritative files' behaviour.  So the constants are READ OUT of sprites.py by
parsing it (no execution, no import), and the formulas are transcribed here in a
single clearly-marked block that mirrors the source line for line.

That transcription is the one place a copy exists, and it is deliberate: the
fixtures are only meaningful if they come from the source's arithmetic rather
than the port's.  Each function below carries the file and line it mirrors, so a
change to the source is a one-line diff here.

The four authoritative files are opened READ-ONLY and never modified.

USAGE
-----
    python3 tools/parity/generate_fixtures.py
writes java-port/core/src/test/resources/parity/fixtures.json
"""

import json
import math
import os
import re
import struct
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SPRITES = os.path.join(ROOT, "sprites.py")
MAIN = os.path.join(ROOT, "main.py")
OUT = os.path.join(ROOT, "java-port", "core", "src", "test",
                   "resources", "parity", "fixtures.json")


# ---------------------------------------------------------------------------
#  Constants, read out of sprites.py without importing it
# ---------------------------------------------------------------------------

def read_constants(path):
    """Pull `NAME = <number>` and simple tuples out of the config block."""
    src = open(path, encoding="utf-8").read()
    consts = {}
    for m in re.finditer(r"^([A-Z][A-Z0-9_]*)\s*=\s*([-\d.]+)\s*(?:#.*)?$",
                         src, re.MULTILINE):
        try:
            consts[m.group(1)] = float(m.group(2))
        except ValueError:
            pass
    for m in re.finditer(r"^([A-Z][A-Z0-9_]*)\s*=\s*\(([-\d.,\s]+)\)\s*(?:#.*)?$",
                         src, re.MULTILINE):
        parts = [p.strip() for p in m.group(2).split(",") if p.strip()]
        try:
            consts[m.group(1)] = [float(p) for p in parts]
        except ValueError:
            pass
    #  `WIDTH, HEIGHT = 1280, 720` -- a tuple assignment, not a single name
    for m in re.finditer(r"^([A-Z][A-Z0-9_]*)\s*,\s*([A-Z][A-Z0-9_]*)\s*=\s*"
                         r"([-\d.]+)\s*,\s*([-\d.]+)\s*(?:#.*)?$",
                         src, re.MULTILINE):
        consts[m.group(1)] = float(m.group(3))
        consts[m.group(2)] = float(m.group(4))
    return consts


C = read_constants(SPRITES)
#  The Endless timetable, the horn and the talent income live in main.py's
#  tuning block rather than in sprites.py, so it is parsed the same way -- read
#  only, never imported.
M = read_constants(MAIN)
C.update({k: v for k, v in M.items() if k not in C})

REQUIRED = ["GRAVITY", "AIR_DRAG", "THROW_POWER", "FALL_DMG_FLOOR", "FALL_DMG_SCALE",
            "SLAM_DMG_FLOOR", "SLAM_DMG_SCALE", "BOUNCE_RESTITUTION", "BOUNCE_DMG_BONUS",
            "BOUNCE_STAGGER", "SHOVE_FACTOR", "SHOVE_DECAY", "SHOVE_MAX",
            "STRIP_DISTANCE", "STRIP_SLOW", "STRIP_VULN", "GROUND_Y", "CASTLE_FRONT",
            "WIDTH", "GRAB_CAPACITY",
            "REGALIA_COOLDOWN", "REGALIA_CD_GROWTH", "CROWN_RETRIEVE_SPEED",
            "STAFF_DISARM_TIME", "CLAW_SMACK_DISTANCE", "CLAW_STAGGER", "WALL_TOP",
            #  --- Phase 9: talents, shop and skills ---
            "GRAB_CD_PER_RANK", "STORM_WIND_SLOW",
            "LIGHTNING_RADIUS", "LIGHTNING_DAMAGE", "LIGHTNING_COOLDOWN",
            "METEOR_COUNT", "METEOR_RADIUS", "METEOR_DAMAGE", "METEOR_COOLDOWN",
            "FIRE_ZONE_TIME", "FIRE_ZONE_DPS",
            "TORNADO_COOLDOWN", "TORNADO_LIFE", "TORNADO_SPEED", "TORNADO_RADIUS",
            "TORNADO_LIFT", "TORNADO_SWIRL",
            #  --- Phase 8: progression, scoring and the Endless timetable ---
            "POP_GOLD_FREE", "POP_GOLD_STEP", "POP_GOLD_CAP",
            "SCORE_PER_PX", "SCORE_PER_SEC", "SCORE_COMBO_STEP",
            "STARTING_GOLD", "WIND_MAX", "STORM_CHANCE", "STORM_DAMAGE",
            "STORM_CEILING", "STORM_COOLDOWN",
            "ENDLESS_TIER_SECONDS", "ENDLESS_SPAWN_START", "ENDLESS_SPAWN_MIN",
            "ENDLESS_SPAWN_RAMP", "ENDLESS_SPAWN_JITTER", "ENDLESS_MAX_ALIVE",
            "ENDLESS_BOSS_REPEAT", "ENDLESS_HORN_RUSH",
            "HARD_HORN_RUSH", "HARD_HORN_TIER_BONUS", "HORN_BONUS",
            "TALENT_POINTS_PER_WAVE", "TALENT_SECONDS_PER_POINT"]
missing = [k for k in REQUIRED if k not in C]
if missing:
    sys.exit("could not read these constants out of sprites.py: " + ", ".join(missing))

DT = 1.0 / 60.0


# ===========================================================================
#  TRANSCRIBED FORMULAS.  Each mirrors the named source location exactly.
#  This block is the only copy of the source arithmetic in this file.
# ===========================================================================

def wave_scaling(wave):
    """enemies.py:133  wave_scaling"""
    w = max(1, wave) - 1
    return (1.14 ** w, 1.11 ** w, min(1.70, 1.0 + 0.026 * w))


def scaled_stats(base_hp, base_dmg, base_speed, wave,
                 enemy_scale, hp_curve, speed_scale, tier):
    """enemies.py:179  Enemy.__init__ -- the whole scaling chain, in order."""
    hp_m, dmg_m, spd_m = wave_scaling(wave)
    if enemy_scale != 1.0:
        hp_m *= enemy_scale
        dmg_m *= enemy_scale
    if hp_curve != 1.0:
        hp_m = 1.0 + (hp_m - 1.0) * hp_curve
    if tier is not None:
        thp, tdmg, tspd = tier
        hp_m *= thp
        dmg_m *= tdmg
        spd_m *= tspd
    return {
        "maxHp": base_hp * hp_m,
        "damage": base_dmg * dmg_m,
        "speed": base_speed * spd_m * speed_scale,
    }


def release_velocity(vx, vy, mass, throw_power_talent=1.0):
    """enemies.py:452  Enemy.on_release"""
    mult = C["THROW_POWER"] * throw_power_talent / (0.55 + 0.45 * mass)
    return vx * mult, vy * mult


def air_step(x, y, vx, vy, wind, wind_mult, dt):
    """enemies.py:572  Enemy._update_air -- one step, no tornado, no collisions."""
    vy += C["GRAVITY"] * dt
    vx += wind * wind_mult * dt
    vx -= vx * C["AIR_DRAG"] * dt
    x += vx * dt
    y += vy * dt
    return x, y, vx, vy


def fall_damage(vx, vy, mass, bounce_level, fall_talent=1.0):
    """enemies.py:469  Enemy.land -- the damage half."""
    impact = math.hypot(vx * 0.5, vy)
    return max(0.0, impact - C["FALL_DMG_FLOOR"]) * C["FALL_DMG_SCALE"] * \
        (0.75 + 0.35 * mass) * (1.0 + C["BOUNCE_DMG_BONUS"] * bounce_level) * fall_talent


def bounce_after(vx, vy, level):
    """enemies.py:469  Enemy.land -- the rebound half."""
    return (vx * (0.45 + 0.07 * level),
            -abs(vy) * C["BOUNCE_RESTITUTION"][level])


def bounce_terminates(bounce_count, level, vy_after):
    """enemies.py:469  `if self.bounce_count > lvl or abs(self.vy) < 90`"""
    return bounce_count > level or abs(vy_after) < 90


def slam_damage(vx, vy, ovx, ovy, mass):
    """enemies.py:521  Enemy.slam_into"""
    rel = math.hypot(vx - ovx, vy - ovy)
    return max(0.0, rel - C["SLAM_DMG_FLOOR"]) * C["SLAM_DMG_SCALE"] * (0.6 + 0.5 * mass)


def shove_apply(current, amount):
    """enemies.py:358  Enemy.apply_shove"""
    return min(C["SHOVE_MAX"], current + amount * C["SHOVE_FACTOR"])


def shove_step(x, shove, dt):
    """enemies.py:631  Enemy.think -- the shove block."""
    x -= shove * dt
    shove *= max(0.0, 1.0 - C["SHOVE_DECAY"] * dt)
    if shove < 8.0:
        shove = 0.0
    return x, shove


def strip_plate(base_armor, layers, total_layers, speed, vulnerable):
    """enemies.py:375  Enemy.apply_strip -- the consequences of one plate."""
    layers -= 1
    return {
        "layers": layers,
        "armor": max(0.0, base_armor * (layers / max(1, total_layers))),
        "speed": speed * C["STRIP_SLOW"],
        "vulnerable": vulnerable + C["STRIP_VULN"],
    }


def grab_capacity(level, bonus=1.0):
    """main.py:1328  Game.grab_capacity"""
    idx = int(max(0, min(level, 4)))
    return C["GRAB_CAPACITY"][idx] * bonus


# --- bosses (Phase 7) ------------------------------------------------------

def regalia_guard(taken):
    """enemies.py:341  Enemy.guard_regalia

    NOTE the off-by-one that matters: regalia_taken is incremented when the item
    is DETACHED, and guard_regalia() is called when it is RECOVERED.  So by the
    time a guard is ever set, taken is already >= 1, and the FIRST guard a boss
    ever has is 6.0 * (1 + 0.6 * 1) = 9.6 -- never 6.0.  Fixtures start at 1
    because taken == 0 is a state in which no guard exists."""
    return C["REGALIA_COOLDOWN"] * (1.0 + C["REGALIA_CD_GROWTH"] * taken)


def crown_retrieve_step(speed, dt):
    """enemies.py:388  TrollKing.retrieve_crown -- distance covered in one step."""
    return speed * C["CROWN_RETRIEVE_SPEED"] * dt


def f32(x):
    """Round a Python float to the nearest float32, as Java's `float` would."""
    return struct.unpack("f", struct.pack("f", x))[0]


def breath_shot_count(breath_time, shot_interval, dt, fire_scale=1.0, single=False):
    """enemies.py:1760  Dragon.think -- how many fireballs one breath produces.

    Reproduces the loop exactly: the first shot leaves on the step the breath
    starts (shot_timer is set to 0), then the timer is reset to the scaled
    interval each time it fires.  Both timers count in simulation seconds.

    `single=True` re-runs the same loop with every value rounded to float32,
    which is what the Java port necessarily computes -- its timers are floats
    like all its gameplay state, while Python's are doubles.  Emitting both makes
    the precision boundary visible in the fixture instead of leaving it to be
    rediscovered as a mysterious off-by-one."""
    r = f32 if single else (lambda v: v)
    breathing = r(breath_time)
    shot_timer = r(0.0)
    step = r(dt)
    interval = r(r(shot_interval) * r(fire_scale))
    shots = 0
    guard = 0
    while breathing > 0 and guard < 100000:
        guard += 1
        breathing = r(breathing - step)
        shot_timer = r(shot_timer - step)
        if shot_timer <= 0:
            shot_timer = interval
            shots += 1
    return shots


def dragon_smack_progress(amount):
    """enemies.py:1800  Dragon.apply_smack -- progress added by one drag."""
    return amount / C["CLAW_SMACK_DISTANCE"]


def lich_ward_then_armour(amount, warded, armor, vulnerable):
    """enemies.py:1912  LichLord.take_damage -> Enemy.take_damage

    THE ORDER IS THE CONTRACT: the ward scales the incoming amount, and the
    armour and vulnerability are applied by the base class to that result."""
    if warded:
        amount *= 0.25
    return max(0.0, amount * (1.0 - armor) * vulnerable)


def lich_armour_then_ward(amount, warded, armor, vulnerable):
    """The WRONG order, generated so the test can prove the two differ."""
    amount = max(0.0, amount * (1.0 - armor) * vulnerable)
    if warded:
        amount *= 0.25
    return amount


def dropped_item_step(x, y, vx, vy, w, h, dt):
    """enemies.py:60  DroppedItem.update -- one flying step, with the walls."""
    vy += C["GRAVITY"] * dt
    x += vx * dt
    y += vy * dt
    left = C["CASTLE_FRONT"] + w
    right = C["WIDTH"] - w
    if x < left:
        x = left
        vx = abs(vx) * 0.4
    if x > right:
        x = right
        vx = -abs(vx) * 0.4
    rest_y = C["GROUND_Y"] - h / 2.0 + 2
    grounded = False
    if y >= rest_y:
        y = rest_y
        vy = -abs(vy) * 0.34
        vx *= 0.6
        if abs(vy) < 90:
            vy = 0.0
            grounded = True
    return x, y, vx, vy, grounded


def throw_cap(vx, vy, kind):
    """enemies.py:50  DroppedItem.throw -- magnitude cap, direction preserved."""
    cap = {"crown": 2300.0, "staff": 780.0}[kind]
    sp = math.hypot(vx, vy)
    if sp > cap:
        return vx * cap / sp, vy * cap / sp
    return vx, vy


def lich_summon_interval(wave):
    """enemies.py:1975  LichLord.think -- the summon cadence."""
    return max(3.2, 6.0 - wave * 0.06)


def lich_summon_count(wave):
    """enemies.py:2016  LichLord.raise_dead"""
    return 2 + min(4, wave // 8)


# ===========================================================================
#  Fixture cases
# ===========================================================================

# ===========================================================================
#  PHASE 8: progression, scoring and the Endless timetable.
#
#  Same rule as the block above -- each function names the source location it
#  mirrors, and every one of them is DOUBLE arithmetic, because that is what
#  Python does and now what the port does too.
# ===========================================================================

#  main.py:244 ENDLESS_BOSS_SCHEDULE, transcribed: the parser above reads
#  scalars, not tuples of (time, class).
ENDLESS_BOSS_SCHEDULE = ((120.0, "troll_king"), (240.0, "dragon"), (360.0, "lich_lord"))
MAX_ALIVE_CLASSIC = 58          # enemies.py:2131 -- NOT the Endless 60


def classic_wave_bonus(wave, wave_purse=0.0):
    """main.py:1783  end_wave -- 80 + wave * 22 + int(talents.wave_purse)."""
    return 80 + wave * 22 + int(wave_purse)


def classic_spawn_interval(wave):
    """main.py:1740  start_wave -- max(0.32, 1.25 - wave * 0.032)."""
    return max(0.32, 1.25 - wave * 0.032)


def endless_tier(play_time):
    """main.py:1668  update_endless_schedule -- 1 + int(play_time / 30)."""
    return 1 + int(play_time / C["ENDLESS_TIER_SECONDS"])


def endless_spawn_gap_base(play_time):
    """main.py:1619  endless_spawn_gap, before the jitter.

    lerp(START, MIN, clamp(play_time / RAMP, 0, 1))."""
    t = min(1.0, max(0.0, play_time / C["ENDLESS_SPAWN_RAMP"]))
    a, b = C["ENDLESS_SPAWN_START"], C["ENDLESS_SPAWN_MIN"]
    return a + (b - a) * t


def endless_spawn_gap(play_time, u):
    """The same gap with an explicit uniform sample u in [0, 1].

    Python draws random.uniform(1 - J, 1 + J); passing u makes the fixture
    independent of the RNG while still checking the arithmetic."""
    j = C["ENDLESS_SPAWN_JITTER"]
    return endless_spawn_gap_base(play_time) * ((1.0 - j) + u * 2.0 * j)


def endless_bosses_due(play_time):
    """How many scripted bosses have arrived by play_time, and how many
    repeats.  main.py:1678.

      scripted:  while next < len(SCHEDULE) and play_time >= SCHEDULE[next][0]
      repeats:   due = int((play_time - LAST) / REPEAT), summoned when it
                 exceeds the number already sent
    """
    scripted = sum(1 for (t, _c) in ENDLESS_BOSS_SCHEDULE if play_time >= t)
    repeats = 0
    if scripted >= len(ENDLESS_BOSS_SCHEDULE):
        last = ENDLESS_BOSS_SCHEDULE[-1][0]
        repeats = max(0, int((play_time - last) / C["ENDLESS_BOSS_REPEAT"]))
    return scripted, repeats


def endless_talent_points(play_time):
    """main.py:1663 -- one point per TALENT_SECONDS_PER_POINT survived.

    The counter SUBTRACTS rather than resetting, so the answer is exactly
    floor(play_time / 60) with no drift however long the run is."""
    return int(play_time / C["TALENT_SECONDS_PER_POINT"])


def crowd_gold_multiplier(n, horn_bonus=0.0, gold_scale=1.0,
                          gold_pop=1.0, kill_gold=1.0):
    """main.py:1332  gold_multiplier.

    NOTE the cap is scaled by the talent as well as the step, and NOTE that
    `n` includes the mob that is dying -- Enemy.die reads this before it
    clears its own alive flag."""
    step = C["POP_GOLD_STEP"] * gold_pop
    base = min(C["POP_GOLD_CAP"] * gold_pop,
               1.0 + step * max(0, n - int(C["POP_GOLD_FREE"])))
    return base * (1.0 + horn_bonus) * kill_gold * gold_scale


def kill_payout(gold, mult):
    """enemies.py:426  die -- max(1, int(round(gold * mult)))."""
    return max(1, int(round(gold * mult)))


def fling_travel(x, x0, y0, peak):
    """enemies.py:514 -- across, plus how high it got."""
    return abs(x - x0) + max(0.0, y0 - peak)


def fling_combo(hits):
    """enemies.py:516 -- 1 + SCORE_COMBO_STEP * hits."""
    return 1.0 + C["SCORE_COMBO_STEP"] * hits


def fling_points(travel, airtime, hits):
    """enemies.py:515  base = travel*PER_PX + airtime*PER_SEC; int(base*combo).

    The int() TRUNCATES; it does not round."""
    base = travel * C["SCORE_PER_PX"] + airtime * C["SCORE_PER_SEC"]
    return int(base * fling_combo(hits))


def awarded_score(points, horn_bonus=0.0, score_mult=1.0):
    """main.py:1442  add_score -- int(pts * (1 + horn) * showman).

    A SECOND truncation, applied to the already-truncated fling points."""
    return int(points * (1.0 + horn_bonus) * score_mult)


def wind_from_sample(u):
    """main.py:1597  roll_weather -- uniform(-1, 1) * WIND_MAX, with u in [0,1]."""
    return (-1.0 + u * 2.0) * C["WIND_MAX"]


def storm_strike_damage(max_hp, lightning_mult=1.0):
    """main.py:1430  strike_lightning -- max_hp * STORM_DAMAGE * talent."""
    return max_hp * C["STORM_DAMAGE"] * lightning_mult


def shake_add(current, amount):
    """main.py:1483  add_shake -- min(14, shake + amount)."""
    return min(14.0, current + amount)


def shake_after(current, dt):
    """main.py:2098 -- max(0, shake - dt * 42)."""
    return max(0.0, current - dt * 42.0)


def horn_head_count(endless, elite, queued):
    """How many units a horn puts on the field.  main.py:1380 blow_horn.

    Classic keeps the head-count of the remaining queue, elite or not: the
    Hard horn SWAPS chaff rather than adding to it."""
    if endless:
        return int(C["HARD_HORN_RUSH"]) if elite else int(C["ENDLESS_HORN_RUSH"])
    return queued


def horn_swap_cap(queued):
    """main.py:1370  upgrade_horn_queue -- max(1, len // 2) entries swapped."""
    return max(1, queued // 2)


def wave_clear_fires(elapsed, dt):
    """main.py:2190 -- the delay accumulates and fires on `> 1.1`, strictly.

    Returns the step index the wave ends on when the field is clear from
    step 1, or -1."""
    acc = 0.0
    for step in range(1, 100000):
        acc += dt
        if acc > 1.1:
            return step
        if step * dt > elapsed:
            return -1
    return -1


# ===========================================================================
#  PHASE 9: talents, shop and skills.
#
#  The talent table is READ OUT of main.py by parsing its Talent(...) calls --
#  no import, no execution -- so the fixture cannot drift from the source's
#  numbers.  The shop curves and skill constants are transcribed, each naming
#  the line it mirrors.
# ===========================================================================

MAIN_SRC = open(MAIN, encoding="utf-8").read()


def read_talents():
    """Parse the TALENTS list out of main.py without importing it.

    Talent(key, branch, tier, name, max_rank, per_rank, desc) -- the first five
    are literals and the sixth is either a literal or a named constant."""
    block = MAIN_SRC[MAIN_SRC.index("TALENTS = ["):MAIN_SRC.index("TALENTS_BY_KEY")]
    out = []
    pattern = re.compile(
        r'Talent\(\s*"([a-z]+)"\s*,\s*"([a-z]+)"\s*,\s*(\d+)\s*,\s*'
        r'"([^"]*)"\s*,\s*(\d+)\s*,\s*([A-Z_0-9.]+)\s*,')
    for m in pattern.finditer(block):
        per = m.group(6)
        try:
            per_rank = float(per)
        except ValueError:
            #  a named constant from the tuning block
            per_rank = C[per]
        out.append({"id": m.group(1), "branch": m.group(2), "tier": int(m.group(3)),
                    "maxRank": int(m.group(5)), "perRank": per_rank})
    return out


#  --- talent effect formulas, main.py:456-537 -------------------------------
#  Each is (id, python expression as a lambda of the raw value v).
TALENT_EFFECTS = {
    "rate":         lambda v: 1.0 - min(0.45, v),
    "power":        lambda v: 1.0 + v,
    "crit":         lambda v: v,
    "pierce":       lambda v: float(int(v)),
    "splash":       lambda v: 1.0 + v,
    "overcharge":   lambda v: 1.0 - min(0.5, v),
    "maxhp":        lambda v: 1.0 + v,          # DEAD: nothing reads it
    "regen":        lambda v: v,
    "spikedot":     lambda v: v,
    "towerhp":      lambda v: 1.0 + v,
    "rebuild":      lambda v: 1.0 - min(0.6, v),
    "thorns":       lambda v: 1.0 - min(0.4, v),
    "greed":        lambda v: 1.0 + v,
    "lighthands":   lambda v: 1.0 + v,
    "haggle":       lambda v: 1.0 - min(0.4, v),
    "purse":        lambda v: v,
    "lightfingers": lambda v: max(0.0, 1.0 - v),
    "showman":      lambda v: 1.0 + v,
    "scavenge":     lambda v: 1.0 + v,
    "sentinels":    lambda v: 1.0 if v > 0 else 0.0,
    "stormwinds":   lambda v: v,
    "throwarm":     lambda v: 1.0 + v,
    "updraft":      lambda v: 1.0 + v,
    "conductor":    lambda v: 1.0 + v,
    "gale":         lambda v: 1.0 + v,
    "tempest":      lambda v: 2.0 if v > 0 else 1.0,
    "bonecraft":    lambda v: 1.0 + v,
    "hostmaster":   lambda v: float(int(v)),
    "quickraise":   lambda v: 1.0 - min(0.5, v),
    "gravechill":   lambda v: v,
    "secondwind":   lambda v: v,
    "bonewall":     lambda v: 1.0 - min(0.5, v),
    "focus":        lambda v: 1.0 - min(0.45, v),
    "amplify":      lambda v: 1.0 + v,
    "widecast":     lambda v: 1.0 + v,
    "emberfall":    lambda v: 1.0 + v,
    "eyeofstorm":   lambda v: 1.0 + v,
    "twincast":     lambda v: 1.0 + v,
}


#  --- shop cost curves, main.py:1039-1155 -----------------------------------
SHOP_GEOMETRIC = {
    "bowman":    (110.0, 1.26),
    "ballista":  (250.0, 1.28),
    "cannon":    (380.0, 1.28),
    "wall":      (180.0, 1.62),
    "bounce":    (200.0, 1.55),
    "grab":      (260.0, 2.05),
    "multi":     (340.0, 1.85),
    "outpost":   (300.0, 1.5),
    "spikes":    (190.0, 1.7),
}


def shop_raw_cost(item, level):
    """The undiscounted curve value.  Fractional -- the truncation is later."""
    base, growth = SHOP_GEOMETRIC[item]
    return base * (growth ** level)


def shop_price(item, level, discount=1.0):
    """main.py:900  ShopItem.cost -- int(cost_fn() * discount).

    ONE truncation, at the end, on the RAW curve times the discount."""
    return int(shop_raw_cost(item, level) * discount)


def shop_price_wrong_order(item, level, discount=1.0):
    """The other ordering, emitted so the Java test can prove they differ."""
    return int(int(shop_raw_cost(item, level)) * discount)


def barricade_cost(level, alive, hp, max_hp, max_level=5):
    """main.py:1128  barricade_cost -- four cases, in this order."""
    if level == 0:
        return 240.0
    if not alive:
        return float(int(150 * (1.4 ** level)))
    if hp < max_hp and level >= max_level:
        return float(int(60 + (max_hp - hp) * 0.35))
    return float(int(240 * (1.5 ** level)))


def repair_cost(missing):
    """main.py:1150 -- max(50, int(missing * 0.55))."""
    return max(50, int(missing * 0.55))


#  --- skills, main.py:697-767 ------------------------------------------------
def skill_cooldown(base, focus_value):
    """main.py:680  full_cooldown -- base * (1 - min(0.45, focus))."""
    return base * (1.0 - min(0.45, focus_value))


def lightning_damage(max_hp, skill_power=1.0, lightning_mult=1.0, is_boss=False):
    """main.py:707.  A boss takes a quarter."""
    dmg = max_hp * C["LIGHTNING_DAMAGE"] * skill_power * lightning_mult
    return dmg * 0.25 if is_boss else dmg


def meteor_count(twincast_value):
    """main.py:737 -- int(METEOR_COUNT * (1 + v))."""
    return int(C["METEOR_COUNT"] * (1.0 + twincast_value))


def fire_zone_life(emberfall_value):
    """main.py:746 -- FIRE_ZONE_TIME * (1 + v)."""
    return C["FIRE_ZONE_TIME"] * (1.0 + emberfall_value)


def tornado_life(eye_value):
    """main.py:763 -- TORNADO_LIFE * (1 + v)."""
    return C["TORNADO_LIFE"] * (1.0 + eye_value)


def skill_radius(base, widecast_value):
    """Every skill area is base * (1 + widecast)."""
    return base * (1.0 + widecast_value)


def build():
    fx = {
        "//": "Generated by tools/parity/generate_fixtures.py from the "
              "authoritative Python source. Do not hand-edit.",
        "dt": DT,
        "constants": {k: C[k] for k in REQUIRED},
        "waveScaling": [],
        "scaledStats": [],
        "release": [],
        "airIntegration": [],
        "fallDamage": [],
        "bounce": [],
        "slam": [],
        "shove": [],
        "strip": [],
        "grabCapacity": [],
        "regaliaGuard": [],
        "crownRetrieve": [],
        "dragonBreath": [],
        "dragonSmack": [],
        "classicWaveBonus": [],
        "classicSpawnInterval": [],
        "endlessTier": [],
        "endlessSpawnGap": [],
        "endlessBossSchedule": [],
        "endlessTalentIncome": [],
        "crowdGold": [],
        "killPayout": [],
        "flingScore": [],
        "weather": [],
        "screenShake": [],
        "hornComposition": [],
        "waveClear": [],
        "talentDefs": [],
        "talentEffects": [],
        "shopCosts": [],
        "shopDiscount": [],
        "shopBespokeCosts": [],
        "skillConstants": [],
        "skillScaling": [],
        "lichWard": [],
        "droppedItem": [],
        "regaliaThrowCap": [],
        "lichSummon": [],
    }

    for wave in (1, 2, 5, 10, 16, 26, 36, 50):
        hp, dmg, spd = wave_scaling(wave)
        fx["waveScaling"].append({"wave": wave, "hp": hp, "damage": dmg, "speed": spd})

    # difficulty presets, matching data/difficulties.json
    difficulties = {
        "easy":   (0.82, 1.0, 0.92),
        "normal": (1.0, 1.0, 1.0),
        "hard":   (1.35, 1.6, 1.4),
    }
    tiers = {16: (1.35, 1.22, 1.05), 26: (1.85, 1.46, 1.10), 36: (2.60, 1.80, 1.16)}

    def tier_for(wave):
        best = None
        for first in sorted(tiers):
            if wave >= first:
                best = tiers[first]
        return best

    # a Scout and a Siege Ram, across every wave and difficulty
    for name, base in (("scout", (26.0, 5.0, 122.0)), ("siege_ram", (520.0, 58.0, 28.0))):
        for wave in (1, 2, 5, 10, 16, 26, 36, 50):
            for diff, (scale, curve, speed_scale) in difficulties.items():
                out = scaled_stats(base[0], base[1], base[2], wave,
                                   scale, curve, speed_scale, tier_for(wave))
                out.update({"enemy": name, "wave": wave, "difficulty": diff})
                fx["scaledStats"].append(out)

    for mass in (0.7, 0.8, 1.0, 1.5, 3.0, 9.0):
        for vx, vy in ((900.0, -400.0), (-1200.0, 250.0), (0.0, 0.0), (2600.0, -2600.0)):
            rvx, rvy = release_velocity(vx, vy, mass)
            fx["release"].append({"mass": mass, "vx": vx, "vy": vy,
                                  "outVx": rvx, "outVy": rvy})

    for steps in (1, 10, 60, 180):
        for wind in (0.0, 260.0, -260.0):
            x, y, vx, vy = 800.0, 300.0, 400.0, -600.0
            for _ in range(steps):
                x, y, vx, vy = air_step(x, y, vx, vy, wind, 1.0, DT)
            fx["airIntegration"].append({"steps": steps, "wind": wind,
                                         "x": x, "y": y, "vx": vx, "vy": vy})

    for mass in (0.8, 1.5, 9.0):
        for lvl in range(0, 6):
            for vy in (0.0, 249.0, 250.0, 251.0, 600.0, 3000.0):
                fx["fallDamage"].append({
                    "mass": mass, "bounceLevel": lvl, "vx": 0.0, "vy": vy,
                    "damage": fall_damage(0.0, vy, mass, lvl)})

    for lvl in range(0, 6):
        for vy in (200.0, 600.0, 1400.0):
            bvx, bvy = bounce_after(300.0, vy, lvl)
            fx["bounce"].append({
                "level": lvl, "inVx": 300.0, "inVy": vy,
                "outVx": bvx, "outVy": bvy,
                "restitution": C["BOUNCE_RESTITUTION"][lvl],
                "terminatesAtCount1": bounce_terminates(1, lvl, bvy),
                "stagger": 0.45 + C["BOUNCE_STAGGER"] * lvl})

    for mass in (0.8, 1.5, 9.0):
        for rel in ((0.0, 0.0, 0.0, 0.0), (600.0, 0.0, -200.0, 0.0),
                    (100.0, 100.0, 0.0, 0.0), (2000.0, -500.0, 0.0, 0.0)):
            fx["slam"].append({"mass": mass, "vx": rel[0], "vy": rel[1],
                               "otherVx": rel[2], "otherVy": rel[3],
                               "damage": slam_damage(rel[0], rel[1], rel[2], rel[3], mass)})

    for amount in (1.0, 10.0, 60.0, 200.0):
        s = shove_apply(0.0, amount)
        x, sh = 900.0, s
        trail = []
        for i in range(30):
            x, sh = shove_step(x, sh, DT)
            if i in (0, 9, 29):
                trail.append({"step": i + 1, "x": x, "shove": sh})
        fx["shove"].append({"amount": amount, "initial": s, "trail": trail})

    # a Siege Ram, plate by plate
    armor, layers, speed, vuln = 0.60, 3, 28.0, 1.0
    steps = []
    while layers > 0:
        r = strip_plate(0.60, layers, 3, speed, vuln)
        layers, armor, speed, vuln = r["layers"], r["armor"], r["speed"], r["vulnerable"]
        steps.append({"layers": layers, "armor": armor, "speed": speed,
                      "vulnerable": vuln})
    fx["strip"].append({"enemy": "siege_ram", "baseArmor": 0.60, "totalLayers": 3,
                        "baseSpeed": 28.0, "stripDistance": C["STRIP_DISTANCE"],
                        "steps": steps})

    for lvl in range(-1, 6):
        for bonus in (1.0, 1.25):
            fx["grabCapacity"].append({"level": lvl, "bonus": bonus,
                                       "capacity": grab_capacity(lvl, bonus)})

    # --- bosses ------------------------------------------------------------

    #  taken starts at 1: see regalia_guard's note.  A boss with taken == 0 has
    #  no guard at all rather than a 6.0 one.
    for taken in range(1, 9):
        fx["regaliaGuard"].append({"taken": taken, "guard": regalia_guard(taken)})

    for speed in (34.0, 47.6, 66.0):
        fx["crownRetrieve"].append({
            "speed": speed, "dt": DT,
            "stepDistance": crown_retrieve_step(speed, DT),
            "retrieveSpeedMult": C["CROWN_RETRIEVE_SPEED"]})

    #  shots        -- Python's own double arithmetic
    #  shotsFloat32 -- the same loop in single precision, which is what the Java
    #                  port computes.  They agree for the SHIPPED configuration
    #                  (1.25 s / 0.15 s) and can differ by one at a boundary; the
    #                  Java test asserts against shotsFloat32 and separately
    #                  asserts that the shipped case has no divergence at all.
    for fire_scale in (1.0, 0.5):
        for breath_time, interval in ((1.25, 0.15), (2.0, 0.25), (0.5, 0.15)):
            fx["dragonBreath"].append({
                "breathTime": breath_time, "shotInterval": interval,
                "fireScale": fire_scale, "dt": DT,
                "shipped": breath_time == 1.25 and interval == 0.15,
                "shots": breath_shot_count(breath_time, interval, DT, fire_scale),
                "shotsFloat32": breath_shot_count(breath_time, interval, DT,
                                                  fire_scale, single=True)})

    for amount in (10.0, 90.0, 360.0, 720.0):
        fx["dragonSmack"].append({
            "amount": amount, "progress": dragon_smack_progress(amount),
            "smackDistance": C["CLAW_SMACK_DISTANCE"],
            "completesAlone": dragon_smack_progress(amount) >= 1.0})

    #  ordering matters wherever vulnerable != 1: with armour alone the two
    #  orders coincide, which is exactly why the fixture includes both
    for armor in (0.0, 0.30, 0.60):
        for vulnerable in (1.0, 1.22, 1.66):
            for warded in (False, True):
                fx["lichWard"].append({
                    "amount": 400.0, "warded": warded, "armor": armor,
                    "vulnerable": vulnerable,
                    "correct": lich_ward_then_armour(400.0, warded, armor, vulnerable),
                    "reversed": lich_armour_then_ward(400.0, warded, armor, vulnerable)})

    for kind, (w, h) in (("crown", (44, 26)), ("staff", (18, 60))):
        for steps in (1, 10, 60):
            x, y, vx, vy = 800.0, 300.0, 400.0, -300.0
            grounded = False
            for _ in range(steps):
                x, y, vx, vy, g = dropped_item_step(x, y, vx, vy, w, h, DT)
                grounded = grounded or g
            fx["droppedItem"].append({
                "kind": kind, "steps": steps, "x": x, "y": y,
                "vx": vx, "vy": vy, "grounded": grounded,
                "restY": C["GROUND_Y"] - h / 2.0 + 2})

    for kind in ("crown", "staff"):
        for vx, vy in ((100.0, 0.0), (3000.0, -3000.0), (-5000.0, 0.0)):
            cx, cy = throw_cap(vx, vy, kind)
            fx["regaliaThrowCap"].append({"kind": kind, "vx": vx, "vy": vy,
                                          "outVx": cx, "outVy": cy})

    for wave in (1, 8, 16, 24, 40, 60):
        fx["lichSummon"].append({"wave": wave,
                                 "interval": lich_summon_interval(wave),
                                 "count": lich_summon_count(wave)})

    # ---------------------------------------------------------------- Phase 8
    for wave in (1, 2, 5, 10, 20, 33, 50, 99):
        for purse in (0.0, 40.0, 125.0):
            fx["classicWaveBonus"].append({"wave": wave, "wavePurse": purse,
                                           "bonus": classic_wave_bonus(wave, purse)})
        fx["classicSpawnInterval"].append({"wave": wave,
                                           "interval": classic_spawn_interval(wave)})

    #  Boundaries first: 30 s is a tier edge, and the step either side of it is
    #  where a float clock would disagree with a double one.
    tier_times = [0.0, 29.98333333333333, 30.0, 30.016666666666666,
                  59.98333333333333, 60.0, 60.016666666666666,
                  119.98333333333333, 120.0, 120.016666666666666,
                  300.0, 599.9, 600.0, 1800.0, 3600.0]
    for t in tier_times:
        fx["endlessTier"].append({"playTime": t, "tier": endless_tier(t)})
        fx["endlessTalentIncome"].append({"playTime": t,
                                          "points": endless_talent_points(t)})
        s, r = endless_bosses_due(t)
        fx["endlessBossSchedule"].append({"playTime": t, "scripted": s, "repeats": r})

    for t in (0.0, 30.0, 60.0, 150.0, 299.9, 300.0, 300.1, 600.0, 3600.0):
        row = {"playTime": t, "baseGap": endless_spawn_gap_base(t)}
        for name, u in (("gapAtMinJitter", 0.0), ("gapAtMidJitter", 0.5),
                        ("gapAtMaxJitter", 1.0)):
            row[name] = endless_spawn_gap(t, u)
        fx["endlessSpawnGap"].append(row)

    for t in (480.0, 600.0, 720.0, 1200.0):
        s, r = endless_bosses_due(t)
        fx["endlessBossSchedule"].append({"playTime": t, "scripted": s, "repeats": r})

    for n in (0, 1, 4, 5, 6, 12, 30, 60, 100):
        for horn in (0.0, C["HORN_BONUS"]):
            for gold_scale in (1.0, 1.35):
                fx["crowdGold"].append({
                    "alive": n, "hornBonus": horn, "goldScale": gold_scale,
                    "multiplier": crowd_gold_multiplier(n, horn, gold_scale)})
    for gold, n in ((8, 1), (8, 30), (46, 12), (520, 60), (1, 100)):
        mult = crowd_gold_multiplier(n)
        fx["killPayout"].append({"baseGold": gold, "alive": n, "multiplier": mult,
                                 "payout": kill_payout(gold, mult)})

    for (x, x0, y0, peak, airtime, hits) in (
            (900.0, 900.0, 600.0, 600.0, 0.0, 0),
            (1200.0, 400.0, 600.0, 180.0, 1.75, 0),
            (1200.0, 400.0, 600.0, 180.0, 1.75, 3),
            (300.0, 1100.0, 620.0, 24.0, 3.5, 7),
            (640.0, 640.0, 500.0, 120.0, 0.9166666666666666, 1)):
        travel = fling_travel(x, x0, y0, peak)
        pts = fling_points(travel, airtime, hits)
        fx["flingScore"].append({
            "x": x, "startX": x0, "startY": y0, "peakY": peak,
            "airtime": airtime, "hits": hits,
            "travel": travel, "combo": fling_combo(hits), "points": pts,
            "awardedPlain": awarded_score(pts),
            "awardedWithHorn": awarded_score(pts, C["HORN_BONUS"]),
            "awardedWithShowman": awarded_score(pts, 0.0, 1.25)})

    for u in (0.0, 0.25, 0.5, 0.75, 1.0):
        fx["weather"].append({"sample": u, "wind": wind_from_sample(u),
                              "windMax": C["WIND_MAX"],
                              "headwindThreshold": C["WIND_MAX"] * 0.45})
    for hp in (120.0, 1200.0, 8400.0):
        fx["weather"].append({"maxHp": hp, "strikeDamage": storm_strike_damage(hp),
                              "stormCeiling": C["STORM_CEILING"],
                              "stormCooldown": C["STORM_COOLDOWN"]})

    for start, add in ((0.0, 8.0), (8.0, 9.0), (13.0, 10.0), (14.0, 5.0)):
        fx["screenShake"].append({"before": start, "add": add,
                                  "after": shake_add(start, add)})
    for start in (14.0, 8.0, 0.5):
        fx["screenShake"].append({"before": start, "dt": DT,
                                  "afterOneStep": shake_after(start, DT),
                                  "stepsToZero": int(math.ceil(start / (DT * 42.0)))})

    for queued in (1, 4, 9, 12, 25):
        fx["hornComposition"].append({
            "endless": False, "elite": False, "queued": queued,
            "spawned": horn_head_count(False, False, queued),
            "swapCap": horn_swap_cap(queued)})
        fx["hornComposition"].append({
            "endless": False, "elite": True, "queued": queued,
            "spawned": horn_head_count(False, True, queued),
            "swapCap": horn_swap_cap(queued)})
    fx["hornComposition"].append({"endless": True, "elite": False, "queued": 0,
                                  "spawned": horn_head_count(True, False, 0),
                                  "swapCap": 0})
    fx["hornComposition"].append({"endless": True, "elite": True, "queued": 0,
                                  "spawned": horn_head_count(True, True, 0),
                                  "swapCap": 0,
                                  "tierBonus": int(C["HARD_HORN_TIER_BONUS"])})

    fx["waveClear"].append({"delay": 1.1, "dt": DT,
                            "firesOnStep": wave_clear_fires(10.0, DT),
                            "note": "strictly greater than 1.1"})

    # ---------------------------------------------------------------- Phase 9
    #  All 38 talent definitions, parsed out of main.py rather than retyped.
    talents = read_talents()
    if len(talents) != 38:
        sys.exit("expected 38 talents in main.py, parsed %d" % len(talents))
    for t in talents:
        fx["talentDefs"].append(t)

    #  every effect at every rank, including 0 and the cap
    for t in talents:
        fn = TALENT_EFFECTS[t["id"]]
        for rank in range(0, t["maxRank"] + 1):
            v = rank * t["perRank"]
            fx["talentEffects"].append({
                "id": t["id"], "rank": rank, "value": v, "effect": fn(v)})

    #  shop curves over representative levels
    for item in sorted(SHOP_GEOMETRIC):
        for level in (0, 1, 2, 3, 5, 8, 12):
            fx["shopCosts"].append({
                "id": item, "level": level,
                "raw": shop_raw_cost(item, level),
                "cost": shop_price(item, level)})

    #  the discount, and the ordering that would be wrong
    for haggle_rank in (0, 1, 2, 3, 4):
        discount = 1.0 - min(0.4, haggle_rank * 0.06)
        for item, level in (("bowman", 2), ("ballista", 3), ("cannon", 1),
                            ("wall", 4), ("grab", 2)):
            fx["shopDiscount"].append({
                "id": item, "level": level, "haggleRank": haggle_rank,
                "discount": discount,
                "cost": shop_price(item, level, discount),
                "costIfTruncatedFirst": shop_price_wrong_order(item, level, discount)})

    #  the two bespoke curves
    for level, alive, hp, max_hp in ((0, False, 0.0, 0.0),
                                     (1, True, 340.0, 340.0),
                                     (1, False, 0.0, 340.0),
                                     (3, True, 500.0, 980.0),
                                     (5, True, 900.0, 2050.0),
                                     (5, False, 0.0, 2050.0)):
        fx["shopBespokeCosts"].append({
            "id": "barricade", "level": level, "alive": alive,
            "hp": hp, "maxHp": max_hp,
            "cost": barricade_cost(level, alive, hp, max_hp)})
    for missing in (0.0, 50.0, 90.0, 400.0, 1200.0):
        fx["shopBespokeCosts"].append({
            "id": "repair", "missing": missing, "cost": repair_cost(missing)})

    #  skill constants, straight out of the tuning block
    fx["skillConstants"].append({
        "lightningRadius": C["LIGHTNING_RADIUS"],
        "lightningDamage": C["LIGHTNING_DAMAGE"],
        "lightningCooldown": C["LIGHTNING_COOLDOWN"],
        "bossLightningShare": 0.25,
        "meteorCount": int(C["METEOR_COUNT"]),
        "meteorRadius": C["METEOR_RADIUS"],
        "meteorDamage": C["METEOR_DAMAGE"],
        "meteorCooldown": C["METEOR_COOLDOWN"],
        "fireZoneTime": C["FIRE_ZONE_TIME"],
        "fireZoneDps": C["FIRE_ZONE_DPS"],
        "tornadoCooldown": C["TORNADO_COOLDOWN"],
        "tornadoLife": C["TORNADO_LIFE"],
        "tornadoSpeed": C["TORNADO_SPEED"],
        "tornadoRadius": C["TORNADO_RADIUS"],
        "tornadoLift": C["TORNADO_LIFT"],
        "tornadoSwirl": C["TORNADO_SWIRL"],
        "tornadoHold": 0.12,
        "tornadoBurstRadiusFactor": 1.4})

    #  and how the talents scale them
    for rank in range(0, 6):
        focus = rank * 0.07
        fx["skillScaling"].append({
            "talent": "focus", "rank": rank, "value": focus,
            "lightningCooldown": skill_cooldown(C["LIGHTNING_COOLDOWN"], focus),
            "meteorCooldown": skill_cooldown(C["METEOR_COOLDOWN"], focus),
            "tornadoCooldown": skill_cooldown(C["TORNADO_COOLDOWN"], focus)})
    for rank in range(0, 5):
        wide = rank * 0.12
        fx["skillScaling"].append({
            "talent": "widecast", "rank": rank, "value": wide,
            "lightningRadius": skill_radius(C["LIGHTNING_RADIUS"], wide),
            "meteorRadius": skill_radius(C["METEOR_RADIUS"], wide),
            "tornadoRadius": skill_radius(C["TORNADO_RADIUS"], wide)})
    for rank in range(0, 3):
        fx["skillScaling"].append({
            "talent": "twincast", "rank": rank, "value": rank * 0.5,
            "meteorCount": meteor_count(rank * 0.5)})
    for rank in range(0, 4):
        fx["skillScaling"].append({
            "talent": "emberfall", "rank": rank, "value": rank * 0.30,
            "fireZoneLife": fire_zone_life(rank * 0.30)})
    for rank in range(0, 3):
        fx["skillScaling"].append({
            "talent": "eyeofstorm", "rank": rank, "value": rank * 0.25,
            "tornadoLife": tornado_life(rank * 0.25),
            "tornadoPower": 1.0 + rank * 0.25})
    for hp in (58.0, 1200.0, 8400.0):
        for amp in (0, 5):
            for cond in (0, 4):
                sp = 1.0 + amp * 0.10
                lm = 1.0 + cond * 0.15
                fx["skillScaling"].append({
                    "talent": "lightning-damage", "maxHp": hp,
                    "amplifyRank": amp, "conductorRank": cond,
                    "damage": lightning_damage(hp, sp, lm),
                    "bossDamage": lightning_damage(hp, sp, lm, is_boss=True)})
    return fx


def main():
    for f in ("main.py", "sprites.py", "castle.py", "enemies.py"):
        p = os.path.join(ROOT, f)
        if not os.path.isfile(p):
            sys.exit("authoritative source missing: " + p)
    fixtures = build()
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as fh:
        json.dump(fixtures, fh, indent=2, sort_keys=False)
        fh.write("\n")
    counts = {k: len(v) for k, v in fixtures.items() if isinstance(v, list)}
    print("wrote " + OUT)
    for k, v in counts.items():
        print("  %-16s %d cases" % (k, v))


if __name__ == "__main__":
    main()
