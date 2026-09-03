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

REQUIRED = ["GRAVITY", "AIR_DRAG", "THROW_POWER", "FALL_DMG_FLOOR", "FALL_DMG_SCALE",
            "SLAM_DMG_FLOOR", "SLAM_DMG_SCALE", "BOUNCE_RESTITUTION", "BOUNCE_DMG_BONUS",
            "BOUNCE_STAGGER", "SHOVE_FACTOR", "SHOVE_DECAY", "SHOVE_MAX",
            "STRIP_DISTANCE", "STRIP_SLOW", "STRIP_VULN", "GROUND_Y", "CASTLE_FRONT",
            "WIDTH", "GRAB_CAPACITY",
            "REGALIA_COOLDOWN", "REGALIA_CD_GROWTH", "CROWN_RETRIEVE_SPEED",
            "STAFF_DISARM_TIME", "CLAW_SMACK_DISTANCE", "CLAW_STAGGER", "WALL_TOP"]
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
