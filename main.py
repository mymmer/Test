"""
Castle Defense -- entry point and game driver.

Owns the Game class: the main loop, both game modes, weather, the score and
multiplier systems, the HUD, the shop, and the menus.

Run with:  python main.py
Self-test: python main.py --selftest

"""

import math
import random
import sys
from collections import deque
import pygame

from sprites import *  # noqa: F401,F403
from castle import *  # noqa: F401,F403
from enemies import *  # noqa: F401,F403

# Shop
# ------------------------------------------------------------------------------

# ==============================================================================
#  GAME MODES
# ==============================================================================

MODE_CLASSIC = "classic"
MODE_ENDLESS = "endless"

MODE_INFO = {
    MODE_CLASSIC: (
        "CLASSIC WAVES",
        "Enemies arrive in numbered waves. Clear every one of them and the "
        "armoury opens automatically before the next.",
        C_GOLD,
    ),
    MODE_ENDLESS: (
        "ENDLESS ATTACKERS",
        "No breaks. Mobs pour in continuously and get nastier the longer you "
        "last. Bosses arrive on the clock, and the armoury is a button you "
        "hit mid-fight.",
        (120, 214, 240),
    ),
}

# ------------------------------------------------------------------------------
#  ENDLESS ATTACKERS -- SPAWN AND BOSS TIMETABLE
#
#  Everything that paces Endless mode is in this one block.  All times are in
#  seconds of elapsed play.  Tweak freely; nothing outside this block needs to
#  change to re-time the bosses or the spawn rate.
# ------------------------------------------------------------------------------

ENDLESS_TIER_SECONDS = 30.0    # every N seconds the mobs step up one tier
ENDLESS_SPAWN_START = 1.70     # gap between spawns at the very start
ENDLESS_SPAWN_MIN = 0.38       # hard floor on that gap, however long you last
ENDLESS_SPAWN_RAMP = 300.0     # seconds taken to ramp from START down to MIN
ENDLESS_SPAWN_JITTER = 0.28    # +/- randomness on each gap, as a fraction
ENDLESS_MAX_ALIVE = 60         # spawning pauses above this many live mobs

#  Scripted boss arrivals: (seconds since the run began, boss class)
ENDLESS_BOSS_SCHEDULE = (
    (120.0, TrollKing),        # 2 minutes
    (240.0, Dragon),           # 4 minutes
    (360.0, LichLord),         # 6 minutes
)
#  After the scripted three, one random boss every this many seconds
ENDLESS_BOSS_REPEAT = 120.0    # 2 minutes

#  How many mobs the Challenge Horn calls in at once in Endless mode
ENDLESS_HORN_RUSH = 12

# ------------------------------------------------------------------------------


def format_clock(seconds):
    """mm:ss for the Endless-mode run timer."""
    seconds = max(0, int(seconds))
    return f"{seconds // 60:02d}:{seconds % 60:02d}"


# ==============================================================================
#  TALENT TREE
# ==============================================================================
#
#  Six branches.  A node only becomes available once enough points have gone
#  into its own branch, so each branch deepens as you commit to it.
#  Talent(key, branch, tier, name, max_rank, per_rank, description)
#  `tier` is the number of points that must already be in the branch.
# ==============================================================================

class Talent:
    def __init__(self, key, branch, tier, name, max_rank, per_rank, desc):
        self.key = key
        self.branch = branch
        self.tier = tier
        self.name = name
        self.max_rank = max_rank
        self.per_rank = per_rank
        self.desc = desc
        self.rect = pygame.Rect(0, 0, 0, 0)


TALENT_BRANCHES = (
    ("offense", "OFFENSE"),
    ("defense", "DEFENCE"),
    ("utility", "UTILITY"),
    ("aero", "AERO-MASTERY"),
    ("necromancy", "NECROMANCY"),
    ("arcane", "ARCANE"),
)

TALENTS = [
    # --- OFFENCE ---------------------------------------------------------
    Talent("rate", "offense", 0, "Rapid Fire", 5, 0.06,
           "Every emplacement reloads {v:.0%} faster."),
    Talent("power", "offense", 0, "Sharpened Heads", 5, 0.08,
           "All tower damage +{v:.0%}."),
    Talent("crit", "offense", 2, "Critical Aim", 5, 0.05,
           "{v:.0%} chance for a shot to hit for triple."),
    Talent("pierce", "offense", 4, "Piercing Shot", 3, 1.0,
           "Bolts and arrows punch through {v:.0f} extra bodies."),
    Talent("splash", "offense", 6, "Wide Ordnance", 4, 0.10,
           "Explosive blast radius +{v:.0%}."),
    Talent("overcharge", "offense", 9, "Hair Trigger", 3, 0.18,
           "Manual overcharge recharges {v:.0%} sooner."),

    # --- DEFENCE ---------------------------------------------------------
    Talent("maxhp", "defense", 0, "Deep Foundations", 5, 0.10,
           "Castle maximum health +{v:.0%}."),
    Talent("regen", "defense", 0, "Barricade Repair Crew", 4, 0.012,
           "The outer barricade regrows {v:.1%} of its health a second."),
    Talent("spikedot", "defense", 2, "Barbed Spikes", 4, 0.9,
           "Spiked walls also bleed attackers for {v:.1f}x damage over time."),
    Talent("towerhp", "defense", 3, "Reinforced Platforms", 4, 0.18,
           "Emplacements have +{v:.0%} health."),
    Talent("rebuild", "defense", 5, "Standby Crews", 3, 0.20,
           "Downed emplacements rebuild {v:.0%} faster."),
    Talent("thorns", "defense", 8, "Iron Bulwark", 3, 0.07,
           "The castle takes {v:.0%} less damage."),

    # --- UTILITY ---------------------------------------------------------
    Talent("greed", "utility", 0, "Crowd Financier", 5, 0.12,
           "Screen-population gold bonus is {v:.0%} stronger."),
    Talent("lighthands", "utility", 0, "Light Hands", 4, 0.14,
           "Heavy units feel {v:.0%} lighter on the cursor."),
    Talent("haggle", "utility", 2, "Haggler", 4, 0.06,
           "Everything in the armoury costs {v:.0%} less."),
    Talent("purse", "utility", 3, "Fat Purse", 4, 40.0,
           "Start each wave with {v:.0f} extra gold."),
    Talent("showman", "utility", 5, "Showman", 4, 0.15,
           "Fling scores are worth +{v:.0%}."),
    Talent("scavenge", "utility", 7, "Scavenger", 3, 0.10,
           "Every kill pays {v:.0%} more gold."),

    # --- AERO-MASTERY ----------------------------------------------------
    Talent("stormwinds", "aero", 0, "Storm Winds", 1, STORM_WIND_SLOW,
           "While a high HEADWIND blows, every enemy is {v:.0%} slower."),
    Talent("throwarm", "aero", 0, "Throwing Arm", 5, 0.08,
           "Your throws leave the hand {v:.0%} faster."),
    Talent("updraft", "aero", 2, "Updraft", 4, 0.12,
           "Fall damage from your throws +{v:.0%}."),
    Talent("conductor", "aero", 4, "Lightning Rod", 4, 0.15,
           "Storm lightning hits for +{v:.0%}."),
    Talent("gale", "aero", 6, "Gale Force", 3, 0.25,
           "Wind pushes {v:.0%} harder -- on your throws only."),
    Talent("tempest", "aero", 9, "Tempest Caller", 1, 1.0,
           "Thunderstorms roll in twice as often."),

    # --- NECROMANCY ------------------------------------------------------
    Talent("bonecraft", "necromancy", 0, "Bonecraft", 5, 0.16,
           "Friendly skeletons have +{v:.0%} health and damage."),
    Talent("hostmaster", "necromancy", 0, "Host Master", 4, 1.0,
           "{v:.0f} more friendly skeletons may stand at once."),
    Talent("quickraise", "necromancy", 2, "Quick Raise", 4, 0.12,
           "The imprisoned Necromancer works {v:.0%} faster."),
    Talent("gravechill", "necromancy", 4, "Grave Chill", 3, 0.06,
           "Enemies fighting your skeletons are {v:.0%} slower."),
    Talent("secondwind", "necromancy", 7, "Second Wind", 2, 20.0,
           "Friendly skeletons last {v:.0f}s longer before crumbling."),
    Talent("bonewall", "necromancy", 9, "Bone Wall", 3, 0.12,
           "Friendly skeletons shrug off {v:.0%} of incoming damage."),

    # --- ARCANE ----------------------------------------------------------
    Talent("focus", "arcane", 0, "Arcane Focus", 5, 0.07,
           "Active skills come off cooldown {v:.0%} sooner."),
    Talent("amplify", "arcane", 0, "Amplify", 5, 0.10,
           "Active skills hit for +{v:.0%}."),
    Talent("widecast", "arcane", 3, "Wide Cast", 4, 0.12,
           "Active skill areas are {v:.0%} larger."),
    Talent("emberfall", "arcane", 5, "Emberfall", 3, 0.30,
           "Meteor fire burns {v:.0%} longer."),
    Talent("eyeofstorm", "arcane", 8, "Eye of the Storm", 2, 0.25,
           "Tornadoes last {v:.0%} longer and pull harder."),
    Talent("twincast", "arcane", 10, "Twin Cast", 2, 0.5,
           "Meteor Shower drops {v:.0%} more rocks."),
]

TALENTS_BY_KEY = {t.key: t for t in TALENTS}


class TalentTree:
    """Passive progression. Points are earned per wave (Classic) or per
    minute survived (Endless) and spent on tier-gated nodes."""

    def __init__(self, game):
        self.game = game
        self.points = 0
        self.earned = 0
        self.ranks = {t.key: 0 for t in TALENTS}
        self.scroll = 0

    # -- economy ---------------------------------------------------------
    def award(self, n=1, reason=""):
        if n <= 0:
            return
        self.points += n
        self.earned += n
        self.game.announce(f"+{n} TALENT POINT{'S' if n > 1 else ''}{reason}",
                           C_TALENT, 2.4)

    def branch_points(self, branch):
        return sum(r for k, r in self.ranks.items()
                   if TALENTS_BY_KEY[k].branch == branch)

    def unlocked(self, talent):
        return self.branch_points(talent.branch) >= talent.tier

    def can_buy(self, talent):
        return (self.points > 0 and self.unlocked(talent)
                and self.ranks[talent.key] < talent.max_rank)

    def buy(self, talent):
        if not self.can_buy(talent):
            return False
        self.ranks[talent.key] += 1
        self.points -= 1
        return True

    def rank(self, key):
        return self.ranks.get(key, 0)

    def value(self, key):
        """Total magnitude of a talent: rank x per-rank."""
        t = TALENTS_BY_KEY[key]
        return self.ranks[key] * t.per_rank

    # -- the effects, read by the rest of the game ------------------------
    @property
    def tower_rate(self):        return 1.0 - min(0.45, self.value("rate"))
    @property
    def tower_damage(self):      return 1.0 + self.value("power")
    @property
    def crit_chance(self):       return self.value("crit")
    @property
    def extra_pierce(self):      return int(self.value("pierce"))
    @property
    def splash_mult(self):       return 1.0 + self.value("splash")
    @property
    def overcharge_cd(self):     return 1.0 - min(0.5, self.value("overcharge"))

    @property
    def castle_hp(self):         return 1.0 + self.value("maxhp")
    @property
    def barricade_regen(self):   return self.value("regen")
    @property
    def spike_dot(self):         return self.value("spikedot")
    @property
    def tower_hp(self):          return 1.0 + self.value("towerhp")
    @property
    def rebuild_mult(self):      return 1.0 - min(0.6, self.value("rebuild"))
    @property
    def damage_taken(self):      return 1.0 - min(0.4, self.value("thorns"))

    @property
    def gold_pop(self):          return 1.0 + self.value("greed")
    @property
    def grab_bonus(self):        return 1.0 + self.value("lighthands")
    @property
    def shop_discount(self):     return 1.0 - min(0.4, self.value("haggle"))
    @property
    def wave_purse(self):        return self.value("purse")
    @property
    def score_mult(self):        return 1.0 + self.value("showman")
    @property
    def kill_gold(self):         return 1.0 + self.value("scavenge")

    @property
    def storm_wind_slow(self):   return self.value("stormwinds")
    @property
    def throw_power(self):       return 1.0 + self.value("throwarm")
    @property
    def fall_damage(self):       return 1.0 + self.value("updraft")
    @property
    def lightning_mult(self):    return 1.0 + self.value("conductor")
    @property
    def wind_mult(self):         return 1.0 + self.value("gale")
    @property
    def storm_chance(self):      return 2.0 if self.rank("tempest") else 1.0

    @property
    def ally_power(self):        return 1.0 + self.value("bonecraft")
    @property
    def ally_cap_bonus(self):    return int(self.value("hostmaster"))
    @property
    def ally_rate(self):         return 1.0 - min(0.5, self.value("quickraise"))
    @property
    def grave_chill(self):       return self.value("gravechill")
    @property
    def ally_life(self):         return self.value("secondwind")

    @property
    def skill_cd(self):          return 1.0 - min(0.45, self.value("focus"))
    @property
    def skill_power(self):       return 1.0 + self.value("amplify")
    @property
    def skill_area(self):        return 1.0 + self.value("widecast")
    @property
    def fire_time(self):         return 1.0 + self.value("emberfall")
    @property
    def tornado_mult(self):      return 1.0 + self.value("eyeofstorm")
    @property
    def ally_tough(self):        return 1.0 - min(0.5, self.value("bonewall"))
    @property
    def meteor_count(self):      return 1.0 + self.value("twincast")


# ==============================================================================
#  ACTIVE SKILLS  (one slot unlocked per boss defeated)
# ==============================================================================

class FireZone:
    """Burning ground left by a meteor."""

    def __init__(self, game, x, life, dps, radius=70.0):
        self.game = game
        self.x = float(x)
        self.life = self.max_life = float(life)
        self.dps = float(dps)
        self.radius = float(radius)
        self.phase = random.uniform(0, 6.28)

    @property
    def alive(self):
        return self.life > 0

    def update(self, dt):
        self.life -= dt
        self.phase += dt * 9.0
        for e in self.game.enemies:
            if e.alive and not e.flying and abs(e.x - self.x) <= self.radius:
                e.take_damage(self.dps * dt, "fire")
        if random.random() < 0.5:
            self.game.effects.burst(
                self.x + random.uniform(-self.radius, self.radius),
                GROUND_Y - 4, 1, (255, 160, 60), speed=70, life=0.5,
                grav=-140, size=3)

    def draw(self, surf):
        a = clamp(self.life / self.max_life, 0.0, 1.0)
        for i in range(7):
            fx = self.x - self.radius + i * (self.radius * 2 / 6)
            fh = (14 + 12 * math.sin(self.phase + i)) * a
            pygame.draw.polygon(surf, (255, 150 - i * 8, 60), [
                (fx - 7, GROUND_Y), (fx + 7, GROUND_Y),
                (fx, GROUND_Y - fh - 10)])
            pygame.draw.polygon(surf, (255, 226, 140), [
                (fx - 3, GROUND_Y), (fx + 3, GROUND_Y),
                (fx, GROUND_Y - fh * 0.55 - 5)])


class Tornado:
    """Sucks up regular mobs, spins them, then hurls them downfield."""

    def __init__(self, game, x, life, radius, power=1.0):
        self.game = game
        self.x = float(x)
        self.life = self.max_life = float(life)
        self.radius = float(radius)
        self.power = power
        self.phase = 0.0
        self.caught = {}

    @property
    def alive(self):
        return self.life > 0

    def update(self, dt):
        self.life -= dt
        self.phase += dt * 7.0
        self.x += TORNADO_SPEED * dt          # drifts away from the castle
        for e in list(self.game.enemies):
            if not e.alive or e.IS_BOSS or not e.GRABBABLE or e.armored:
                continue
            d = abs(e.x - self.x)
            if d > self.radius:
                continue
            if e.state in ("walk", "attack"):
                e.state = "air"
                e.on_release(0.0, 0.0)        # counts as a player fling
                e.fling_hits = 0
                self.caught[id(e)] = True
            if e.state == "air":
                # spiral inward and upward; the hold cancels most of gravity
                # so they really do get carried, not merely slowed
                pull = (1.0 - d / self.radius) * self.power
                e.tornado_hold = 0.12
                e.vx += ((self.x - e.x) * TORNADO_SWIRL - e.vx * 1.4) * pull * dt
                e.vy += (-TORNADO_LIFT * pull - e.vy) * 2.4 * dt
                e.vy = clamp(e.vy, -900.0, 220.0)
                e.spin += dt * 16.0
        if self.life <= 0:
            self.burst()

    def burst(self):
        """Time's up -- everything it picked up is hurled downfield.

        Keyed off what the funnel actually caught rather than what happens to
        be near it now: heavy mobs lag behind as the tornado drifts, and they
        should still be thrown."""
        g = self.game
        g.add_shake(9.0)
        g.effects.ring(self.x, GROUND_Y - 120, 30, (200, 230, 240),
                       speed=560, life=0.7, size=5)
        thrown = 0
        for e in g.enemies:
            if not e.alive or e.state != "air":
                continue
            if id(e) not in self.caught and abs(e.x - self.x) > self.radius * 1.4:
                continue
            e.tornado_hold = 0.0
            e.vx = abs(e.vx) + random.uniform(760, 1180)
            e.vy = -random.uniform(520, 820)
            thrown += 1
        if thrown:
            g.effects.text(self.x, GROUND_Y - 190, f"{thrown} HURLED BACK",
                           (200, 230, 245), 26)

    def draw(self, surf):
        a = clamp(self.life / self.max_life, 0.0, 1.0)
        top = GROUND_Y - 250
        for i in range(13):
            t = i / 12.0
            y = GROUND_Y - t * 250
            w = self.radius * (0.28 + 0.72 * t) * a
            off = math.sin(self.phase + t * 5.0) * 12 * t
            col = mix((150, 190, 214), (232, 244, 250), t)
            pygame.draw.ellipse(surf, col,
                                (self.x - w + off, y - 12, w * 2, 20), 3)
        pygame.draw.line(surf, (210, 232, 244), (self.x, GROUND_Y),
                         (self.x + math.sin(self.phase) * 10, top), 2)


class Skill:
    """One slot on the skill bar."""

    def __init__(self, key, name, hotkey, cooldown, desc, needs_target=True):
        self.key = key
        self.name = name
        self.hotkey = hotkey
        self.base_cooldown = cooldown
        self.desc = desc
        self.needs_target = needs_target
        self.cooldown = 0.0
        self.rect = pygame.Rect(0, 0, 0, 0)

    def full_cooldown(self, game):
        return self.base_cooldown * game.talents.skill_cd

    @property
    def ready(self):
        return self.cooldown <= 0

    def update(self, dt):
        self.cooldown = max(0.0, self.cooldown - dt)

    def cast(self, game, x, y):
        raise NotImplementedError


class LightningStrike(Skill):
    def __init__(self):
        super().__init__("lightning", "Lightning Strike", pygame.K_q,
                         LIGHTNING_COOLDOWN,
                         "Call a bolt down anywhere. Vaporises a cluster of "
                         "ground mobs.")

    def cast(self, game, x, y):
        t = game.talents
        radius = LIGHTNING_RADIUS * t.skill_area
        hit = 0
        for e in game.enemies:
            if not e.alive or abs(e.x - x) > radius:
                continue
            dmg = e.max_hp * LIGHTNING_DAMAGE * t.skill_power * t.lightning_mult
            if e.IS_BOSS:
                dmg *= 0.25          # bosses are shaken, not vaporised
            e.take_damage(dmg, "lightning")
            hit += 1
        game.bolts.append([x, GROUND_Y - 40, 0.45])
        for k in range(4):
            game.bolts.append([x + random.uniform(-radius, radius),
                               GROUND_Y - 30, 0.3])
        game.storm_flash = 1.0
        game.add_shake(13.0)
        game.effects.ring(x, GROUND_Y - 20, 34, (200, 230, 255),
                          speed=radius * 4, life=0.55, size=6)
        game.effects.text(x, GROUND_Y - 150, f"{hit} VAPORISED",
                          (190, 225, 255), 30)
        return True


class MeteorShower(Skill):
    def __init__(self):
        super().__init__("meteor", "Meteor Shower", pygame.K_w,
                         METEOR_COOLDOWN,
                         "Rains fireballs across the field and sets the "
                         "ground alight.", needs_target=False)

    def cast(self, game, x, y):
        t = game.talents
        radius = METEOR_RADIUS * t.skill_area
        dmg = METEOR_DAMAGE * t.skill_power
        left = max(CASTLE_FRONT + 40, x - 430)
        for i in range(int(METEOR_COUNT * t.meteor_count)):
            mx = clamp(left + random.uniform(0, 860), CASTLE_FRONT + 30,
                       WIDTH - 20)
            game.projectiles.append(Projectile(
                game, mx + random.uniform(-90, -40), -60 - i * 26,
                random.uniform(60, 140), random.uniform(520, 700),
                "fire", dmg, splash=radius, grav=GRAVITY * 0.4,
                life=5.0, color=(255, 150, 70)))
            game.fire_zones.append(FireZone(
                game, mx, FIRE_ZONE_TIME * t.fire_time,
                FIRE_ZONE_DPS * t.skill_power, radius * 0.75))
        game.add_shake(10.0)
        game.announce("METEOR SHOWER!", (255, 170, 90), 2.2)
        return True


class TornadoSkill(Skill):
    def __init__(self):
        super().__init__("tornado", "Tornado", pygame.K_e,
                         TORNADO_COOLDOWN,
                         "Whips mobs into the air for maximum fall damage, "
                         "then hurls them downfield.")

    def cast(self, game, x, y):
        t = game.talents
        game.tornados.append(Tornado(
            game, x, TORNADO_LIFE * t.tornado_mult,
            TORNADO_RADIUS * t.skill_area, t.tornado_mult))
        game.add_shake(7.0)
        game.announce("TORNADO!", (190, 226, 240), 2.0)
        return True


#  Boss defeated -> the next slot on the bar lights up, in this order
SKILL_UNLOCK_ORDER = (LightningStrike, MeteorShower, TornadoSkill)


class SkillPanel:
    """The skill bar: unlocking, cooldowns, targeting and drawing."""
    SLOT = 62
    GAP = 12

    def __init__(self, game):
        self.game = game
        self.skills = []
        self.aiming = None          # skill waiting for a click to place it

    def unlock_next(self):
        if len(self.skills) >= len(SKILL_UNLOCK_ORDER):
            return None
        skill = SKILL_UNLOCK_ORDER[len(self.skills)]()
        self.skills.append(skill)
        return skill

    def update(self, dt):
        for s in self.skills:
            s.update(dt)
        if self.aiming is not None and not self.aiming.ready:
            self.aiming = None

    # -- input -----------------------------------------------------------
    def activate(self, skill):
        """Arm a skill: targeted ones wait for a click, the rest fire now."""
        if not skill.ready:
            self.game.shop_msg = f"{skill.name} is still recharging."
            self.game.shop_msg_t = 1.2
            return False
        if skill.needs_target:
            self.aiming = skill
            return True
        return self.cast(skill, *self.game.mouse_pos)

    def cast(self, skill, x, y):
        if not skill.ready:
            return False
        skill.cast(self.game, x, y)
        skill.cooldown = skill.full_cooldown(self.game)
        self.aiming = None
        self.game.stats_casts += 1
        return True

    def handle_key(self, key):
        for s in self.skills:
            if s.hotkey == key:
                return self.activate(s)
        return False

    def handle_click(self, pos):
        """Returns True when the click was consumed by the skill bar."""
        for s in self.skills:
            if s.rect.collidepoint(pos):
                self.activate(s)
                return True
        if self.aiming is not None:
            self.cast(self.aiming, pos[0], pos[1])
            return True
        return False

    # -- drawing ---------------------------------------------------------
    def bar_rect(self):
        n = max(1, len(self.skills))
        w = n * self.SLOT + (n - 1) * self.GAP
        return pygame.Rect(WIDTH // 2 - w // 2, HEIGHT - 96, w, self.SLOT)

    def draw(self, surf):
        if not self.skills:
            return
        base = self.bar_rect()
        for i, s in enumerate(self.skills):
            r = pygame.Rect(base.x + i * (self.SLOT + self.GAP), base.y,
                            self.SLOT, self.SLOT)
            s.rect = r
            colour = SKILL_COLORS.get(s.key, C_WHITE)
            ready = s.ready
            pygame.draw.rect(surf, (26, 30, 46), r, border_radius=8)
            edge = colour if ready else C_SKILL_COOL
            if self.aiming is s:
                edge = C_SKILL_READY
            pygame.draw.rect(surf, edge, r, 3, border_radius=8)
            draw_skill_glyph(surf, s.key, r.centerx, r.centery - 2,
                             self.SLOT * 0.62,
                             colour if ready else shade(colour, 0.45))
            if not ready:
                frac = s.cooldown / max(0.01, s.full_cooldown(self.game))
                veil = pygame.Surface((r.w - 6, int((r.h - 6) * frac)),
                                      pygame.SRCALPHA)
                veil.fill((8, 10, 18, 190))
                surf.blit(veil, (r.x + 3, r.y + 3))
                draw_text(surf, f"{s.cooldown:.0f}", r.centerx, r.centery - 9,
                          24, C_WHITE, "center", True)
            key_name = pygame.key.name(s.hotkey).upper()
            draw_text(surf, key_name, r.centerx, r.bottom - 15, 16,
                      C_DIM if not ready else C_WHITE, "center", True)
        if self.aiming is not None:
            mx, my = self.game.mouse_pos
            colour = SKILL_COLORS.get(self.aiming.key, C_WHITE)
            rad = int((LIGHTNING_RADIUS if self.aiming.key == "lightning"
                       else TORNADO_RADIUS) * self.game.talents.skill_area)
            pygame.draw.circle(surf, colour, (mx, GROUND_Y - 10), rad, 2)
            pygame.draw.line(surf, colour, (mx, 0), (mx, GROUND_Y), 1)
            draw_text(surf, f"CLICK TO PLACE {self.aiming.name.upper()}",
                      mx, GROUND_Y + 8, 18, colour, "center", True)


# lets the shop cards show each tower's strategic counter tag
TOWER_FOR_KEY = {"bowman": Bowman, "ballista": Ballista, "cannon": Cannon}


class ShopItem:
    def __init__(self, key, name, color, desc, cost_fn, buy_fn,
                 status_fn, avail_fn=None):
        self.key = key
        self.name = name
        self.color = color
        self.desc = desc
        self.cost_fn = cost_fn
        self.buy_fn = buy_fn
        self.status_fn = status_fn
        self.avail_fn = avail_fn or (lambda: True)
        self.discount = 1.0        # set from the talent tree each frame
        self.rect = pygame.Rect(0, 0, 0, 0)

    @property
    def cost(self):
        return int(self.cost_fn() * self.discount)




# --- the start screen's copy, as paragraphs rather than pre-broken lines ---
MENU_PARAGRAPHS = (
    ("Endless waves. 8 mob types, 3 bosses, one castle.", 21, C_DIM, False),
    ("", 20, C_DIM, False),
    ("THROW  -  hold LEFT MOUSE on a ground mob, then flick and release to "
     "hurl it. The fall hurts, and it crushes whatever it lands on.",
     20, C_WHITE, False),
    ("", 20, C_DIM, False),
    ("TANKS  -  a Siege Ram's plating comes off first: drag AWAY from the "
     "castle to rip it apart. Once bare, drag TOWARDS the castle to shove it "
     "forward, or lift it outright with enough Grab Strength.",
     20, (255, 200, 140), False),
    ("", 20, C_DIM, False),
    ("BOSSES  -  rip the Troll King's CROWN off and fling it, flick the Lich "
     "Lord's STAFF out of his hands, batter the Dragon's CLAWS.",
     20, (200, 170, 255), False),
    ("", 20, C_DIM, False),
    ("Drag back on a Ballista or Cannon to overcharge-fire it.",
     20, (150, 200, 255), False),
    ("", 20, C_DIM, False),
    ("Long flings score big and mid-air hits multiply them. A crowded screen "
     "pays more gold - blow the CHALLENGE HORN for even more.",
     20, C_HILITE, False),
    ("", 20, C_DIM, False),
    ("Pick a mode below to begin  -  or press 1 / 2", 24, C_GOLD, True),
)


# ------------------------------------------------------------------------------
# The Game
# ------------------------------------------------------------------------------


class Game:
    MENU, PLAYING, SHOP, PAUSED, GAMEOVER = "menu", "playing", "shop", "paused", "over"
    TALENTS = "talents"

    def __init__(self, screen=None, headless=False):
        self.headless = headless
        self.screen = screen
        self.scene = pygame.Surface((WIDTH, HEIGHT))
        self.clock = pygame.time.Clock()
        self.running = True
        self.bg = self._build_background()
        self.reset()

    # -- lifecycle -------------------------------------------------------
    def reset(self, mode=None):
        self.state = self.MENU
        self.mode = mode or getattr(self, "mode", MODE_CLASSIC)
        self.time = 0.0
        self.play_time = 0.0          # elapsed play, Endless mode's clock
        self.next_boss = 0            # index into ENDLESS_BOSS_SCHEDULE
        self.extra_bosses = 0         # random bosses spawned after the script
        self.realtime_shop = False    # shop opened mid-fight rather than between waves
        self.started = False
        self.mode_buttons = {}
        self.shop_btn = pygame.Rect(0, 0, 0, 0)
        self.talent_btn = pygame.Rect(0, 0, 0, 0)
        self.talent_back_btn = pygame.Rect(0, 0, 0, 0)
        self.talent_return = self.SHOP
        self.wave = 0
        self.gold = STARTING_GOLD
        self.castle = Castle(self)
        self.outpost = Outpost(self)
        self.barricade = Barricade(self)
        self.spikes = SpikeWalls(self)
        self.talents = TalentTree(self)
        self.skills = SkillPanel(self)
        self.enemies = []
        self.allies = []              # friendly skeletons, never targeted by towers
        self.fire_zones = []
        self.tornados = []
        self.projectiles = []
        self.effects = Effects()
        self.items = []
        self.allies = []
        self.fire_zones = []
        self.tornados = []
        self.bolts = []
        self.spawn_queue = []
        self.spawn_timer = 0.0
        self.spawn_interval = 1.0
        self.wave_active = False
        self.wave_clear_delay = 0.0
        self.shake = 0.0
        self.banners = []
        self.grabbed = None
        self.grabbed_extra = []        # Magnetic Gloves: mobs held alongside
        self.stripping = None          # heavy unit currently being dismantled
        self.strip_anchor = (0, 0)
        self.items = []                # crowns / staves knocked loose
        self.held_item = None
        self.charging = None           # tower being hand-aimed, slingshot style
        self.smacking = None           # Dragon whose claws are being battered
        self.wind = 0.0                # + blows away from the castle
        self.storm = False
        self.storm_flash = 0.0
        self.horn_used = False
        self.horn_bonus = 0.0
        self.horn_glow = 0.0
        self.grab_cd = 0.0
        self.mouse_hist = deque(maxlen=12)
        self.mouse_pos = (WIDTH // 2, HEIGHT // 2)
        self.stats_kills = 0
        self.stats_thrown_damage = 0.0
        self.stats_plates_torn = 0
        self.stats_trapped = 0
        self.stats_casts = 0
        self.talent_seconds = 0.0     # Endless: drip-feeds talent points
        self.score = 0
        self.best_fling = 0
        self.best_combo = 1.0
        self.combo_flash = 0.0
        self.bounce_level = 0
        self.grab_level = 0
        self.multi_level = 0
        self.purchases = {"bowman": 0, "ballista": 0, "cannon": 0}
        self.shop_items = self._build_shop()
        self.shop_msg = ""
        self.shop_msg_t = 0.0
        self.start_btn = pygame.Rect(0, 0, 0, 0)

    # -- shop definition -------------------------------------------------
    def _build_shop(self):
        def tower_status(cls, key):
            def f():
                towers = [t for t in self.castle.towers if isinstance(t, cls)]
                free = len(self.castle.free_slots())
                if not towers:
                    return "Owned: 0" if free else "needs wall space"
                lvl = max(t.level for t in towers)
                tail = "  (upgrades)" if not free else ""
                return f"Owned: {len(towers)}   Lv.{lvl}{tail}"
            return f

        def buy_tower(cls, key):
            def f():
                t = self.castle.add_tower(cls)
                if t is None:
                    existing = [x for x in self.castle.towers if isinstance(x, cls)]
                    if not existing:
                        return False, "No wall space -- reinforce the walls first!"
                    for x in existing:
                        x.upgrade()
                    return True, f"{cls.NAME} upgraded to Lv.{existing[0].level}!"
                return True, f"{cls.NAME} stationed on the wall."
            return f

        def wall_cost():
            return 180 * (1.62 ** (self.castle.wall_level - 1))

        def buy_wall():
            before = self.castle.tier_name
            if not self.castle.upgrade_wall():
                return False, "Walls are already at maximum."
            self.effects.ring(CASTLE_FRONT * 0.5, WALL_TOP, 30, C_HILITE,
                              speed=520, life=0.7, size=5)
            return True, f"{before} -> {self.castle.tier_name}!"

        def bounce_cost():
            return 200 * (1.55 ** self.bounce_level)

        def buy_bounce():
            if self.bounce_level >= BOUNCE_MAX_LEVEL:
                return False, "Bounce is already maxed out."
            self.bounce_level += 1
            n = self.bounce_level + 1
            return True, f"Thrown mobs now bounce up to {n} times!"

        def grab_cost():
            return 260 * (2.05 ** self.grab_level)

        def buy_grab():
            if self.grab_level >= GRAB_MAX_LEVEL:
                return False, "Grab Strength is already maxed."
            self.grab_level += 1
            cap = self.grab_capacity
            heavy = " You can now LIFT Siege Rams!" if cap >= SiegeRam.MASS \
                and GRAB_CAPACITY[self.grab_level - 1] < SiegeRam.MASS else ""
            return True, f"Cursor can lift {cap:.1f} mass.{heavy}"

        def multi_cost():
            return 340 * (1.85 ** self.multi_level)

        def buy_multi():
            if self.multi_level >= MULTI_MAX_LEVEL:
                return False, "Magnetic Gloves are already maxed."
            self.multi_level += 1
            return True, f"You can now hold {self.multi_level + 1} mobs at once."

        def outpost_cost():
            return 300 * (1.5 ** self.outpost.level)

        def buy_outpost():
            was_bow = not self.outpost.is_turret
            if not self.outpost.upgrade():
                return False, "The outpost is fully garrisoned."
            if was_bow and self.outpost.is_turret:
                return True, "Outpost upgraded to automated TURRETS!"
            return True, f"Outpost garrison: {self.outpost.guns}."

        def barricade_cost():
            b = self.barricade
            if b.level == 0:
                return 240
            if not b.alive:
                return int(150 * (1.4 ** b.level))     # rebuild the wreck
            if b.hp < b.max_hp and b.level >= BARRICADE_MAX_LEVEL:
                return int(60 + (b.max_hp - b.hp) * 0.35)
            return int(240 * (1.5 ** b.level))

        def buy_barricade():
            return self.barricade.buy()

        def spike_cost():
            return 190 * (1.7 ** self.spike_level)

        def buy_spikes():
            if not self.spikes.upgrade():
                return False, "The walls are already bristling."
            return True, f"Spike Walls Lv.{self.spikes.level}."

        def repair_cost():
            missing = self.castle.max_hp - self.castle.hp
            return max(50, int(missing * 0.55))

        def buy_repair():
            if self.castle.hp >= self.castle.max_hp:
                return False, "The walls are already pristine."
            healed = self.castle.repair(0.40)
            self.effects.ring(CASTLE_FRONT * 0.5, WALL_TOP + 60, 24, C_GREEN,
                              speed=400, life=0.6, size=4)
            return True, f"Repaired {int(healed)} health."

        return [
            ShopItem("bowman", "Bowmen", Bowman.COLOR,
                     "Rapid arrows, low damage. Its range stretches far "
                     "UPWARD, so it always reaches flyers.",
                     lambda: 110 * (1.26 ** self.purchases["bowman"]),
                     buy_tower(Bowman, "bowman"),
                     tower_status(Bowman, "bowman")),
            ShopItem("ballista", "Ballista", Ballista.COLOR,
                     "Heavy piercing bolt, slow reload. +200% damage to "
                     "anything airborne.",
                     lambda: 250 * (1.28 ** self.purchases["ballista"]),
                     buy_tower(Ballista, "ballista"),
                     tower_status(Ballista, "ballista")),
            ShopItem("cannon", "Cannon", (168, 174, 196),
                     "Explosive splash on the densest cluster. +200% damage "
                     "to Heavy ground units.",
                     lambda: 380 * (1.28 ** self.purchases["cannon"]),
                     buy_tower(Cannon, "cannon"),
                     tower_status(Cannon, "cannon")),
            ShopItem("wall", "Reinforce Walls", (206, 212, 228),
                     "More max health, another tower slot, and a visibly "
                     "tougher keep.",
                     wall_cost, buy_wall,
                     lambda: f"Lv.{self.castle.wall_level}/{self.castle.max_level}"
                             f"   {len(self.castle.towers)}/"
                             f"{self.castle.slot_capacity} slots"),
            ShopItem("bounce", "Bounce", (120, 214, 240),
                     "Thrown mobs bounce again and again. Every bounce adds "
                     "impact damage and a longer stun.",
                     bounce_cost, buy_bounce,
                     lambda: (f"Lv.{self.bounce_level}/{BOUNCE_MAX_LEVEL}"
                              f"   {self.bounce_level + 1} bounce"
                              f"{'' if self.bounce_level == 0 else 's'}"),
                     lambda: self.bounce_level < BOUNCE_MAX_LEVEL),
            ShopItem("grab", "Grab Strength", (232, 168, 96),
                     "Raises the weight your cursor can lift. Heavy tanks "
                     "need Lv.3.",
                     grab_cost, buy_grab,
                     lambda: (f"Lv.{self.grab_level}/{GRAB_MAX_LEVEL}"
                              f"   lifts {self.grab_capacity:.1f}"),
                     lambda: self.grab_level < GRAB_MAX_LEVEL),
            ShopItem("multi", "Magnetic Gloves", (176, 150, 240),
                     "Snatch extra mobs near the one you grab and fling the "
                     "whole bunch at once.",
                     multi_cost, buy_multi,
                     lambda: (f"Lv.{self.multi_level}/{MULTI_MAX_LEVEL}"
                              f"   holds {self.multi_level + 1}"),
                     lambda: self.multi_level < MULTI_MAX_LEVEL),
            ShopItem("outpost", "Outpost", (150, 200, 226),
                     "Garrison the background tower. Mobs cannot reach it, so "
                     "it never stops firing.",
                     outpost_cost, buy_outpost,
                     lambda: (f"{self.outpost.guns}/{OUTPOST_MAX_LEVEL} "
                              + ("turrets" if self.outpost.is_turret
                                 else "bowmen")),
                     lambda: self.outpost.level < OUTPOST_MAX_LEVEL),
            ShopItem("barricade", "Barricade", (206, 182, 140),
                     "A wall out in the field. Ground troops must break it "
                     "before they reach you.",
                     barricade_cost, buy_barricade,
                     lambda: ("not built" if self.barricade.level == 0 else
                              (f"Lv.{self.barricade.level}  "
                               f"{int(self.barricade.hp)}/"
                               f"{int(self.barricade.max_hp)}")),
                     lambda: (self.barricade.level < BARRICADE_MAX_LEVEL
                              or self.barricade.hp < self.barricade.max_hp)),
            ShopItem("spikes", "Spike Walls", (226, 140, 130),
                     "Iron spikes along the parapet. Anything that hits the "
                     "wall takes damage back.",
                     spike_cost, buy_spikes,
                     lambda: (f"Lv.{self.spike_level}/{SPIKE_MAX_LEVEL}"
                              f"   {int(self.spike_damage)} dmg"),
                     lambda: self.spike_level < SPIKE_MAX_LEVEL),
            ShopItem("repair", "Repair", C_GREEN,
                     "Instantly restore 40% of maximum castle health.",
                     repair_cost, buy_repair,
                     lambda: f"HP {int(self.castle.hp)}/{int(self.castle.max_hp)}"),
        ]

    # -- background ------------------------------------------------------
    def _build_background(self):
        bg = pygame.Surface((WIDTH, HEIGHT))
        for y in range(GROUND_Y):
            t = y / GROUND_Y
            pygame.draw.line(bg, mix(C_SKY_TOP, C_SKY_BOT, t ** 0.85),
                             (0, y), (WIDTH, y))
        # moon
        pygame.draw.circle(bg, (238, 236, 214), (1080, 110), 44)
        pygame.draw.circle(bg, (216, 214, 196), (1064, 100), 8)
        pygame.draw.circle(bg, (216, 214, 196), (1098, 126), 6)
        # stars
        random.seed(7)
        for _ in range(140):
            sx, sy = random.randint(0, WIDTH), random.randint(0, 380)
            r = random.choice([1, 1, 1, 2])
            c = random.randint(150, 235)
            pygame.draw.circle(bg, (c, c, c - 10), (sx, sy), r)
        # distant hills
        for layer, (col, base, amp) in enumerate([
                ((44, 48, 72), 470, 60), ((38, 44, 62), 520, 44),
                ((32, 40, 50), 560, 30)]):
            pts = [(0, HEIGHT)]
            for x in range(0, WIDTH + 40, 40):
                yy = base + math.sin(x * 0.006 + layer * 2.1) * amp \
                    + math.sin(x * 0.017 + layer) * amp * 0.35
                pts.append((x, yy))
            pts.append((WIDTH, HEIGHT))
            pygame.draw.polygon(bg, col, pts)
        # ground
        pygame.draw.rect(bg, C_GROUND, (0, GROUND_Y - 18, WIDTH, HEIGHT - GROUND_Y + 18))
        pygame.draw.rect(bg, C_GROUND_DARK, (0, GROUND_Y + 6, WIDTH, HEIGHT - GROUND_Y))
        pygame.draw.line(bg, (86, 104, 66), (0, GROUND_Y - 18), (WIDTH, GROUND_Y - 18), 3)
        for _ in range(260):
            gx = random.randint(0, WIDTH)
            gy = random.randint(GROUND_Y - 16, HEIGHT - 4)
            pygame.draw.line(bg, shade(C_GROUND, random.uniform(0.7, 1.3)),
                             (gx, gy), (gx + random.randint(-2, 2), gy - 5), 2)
        random.seed()
        return bg

    # -- helpers ---------------------------------------------------------
    @property
    def grab_capacity(self):
        """Heaviest MASS the cursor can currently lift."""
        return (GRAB_CAPACITY[int(clamp(self.grab_level, 0, GRAB_MAX_LEVEL))]
                * self.talents.grab_bonus)

    @property
    def gold_multiplier(self):
        """Risk vs reward: a crowded screen pays far better, but a crowd is
        exactly what flattens the castle."""
        n = sum(1 for e in self.enemies if e.alive)
        step = POP_GOLD_STEP * self.talents.gold_pop
        base = min(POP_GOLD_CAP * self.talents.gold_pop,
                   1.0 + step * max(0, n - POP_GOLD_FREE))
        return base * (1.0 + self.horn_bonus) * self.talents.kill_gold

    def blow_horn(self):
        """Taunt the horde: the rest of the wave charges in at once, and
        everything it drops for the rest of the round is worth more."""
        if self.horn_used or not self.wave_active:
            return False
        if self.endless:
            # no queue to empty in Endless -- call in a rush instead
            pending = ENDLESS_HORN_RUSH
            for _ in range(pending):
                self.spawn_enemy(self.roll_endless_mob()(self, self.wave))
        else:
            pending = len(self.spawn_queue)
            if pending == 0:
                self.shop_msg, self.shop_msg_t = "Nothing left to call in.", 1.5
                return False
            for cls in self.spawn_queue:
                self.spawn_enemy(cls(self, self.wave))
            self.spawn_queue = []
        self.horn_used = True
        self.horn_bonus = HORN_BONUS
        self.horn_glow = 1.0
        self.add_shake(10.0)
        self.effects.ring(CASTLE_FRONT * 0.6, WALL_TOP + 40, 34, C_GOLD,
                          speed=560, life=0.8, size=5)
        self.announce(f"CHALLENGE HORN! {pending} more incoming, "
                      f"+{int(HORN_BONUS * 100)}% rewards", C_GOLD, 3.2)
        return True

    def strike_lightning(self, enemy):
        """A mob flung into the storm ceiling draws a bolt."""
        if not self.storm or not enemy.alive or enemy.storm_cd > 0:
            return
        enemy.storm_cd = STORM_COOLDOWN
        dmg = enemy.max_hp * STORM_DAMAGE * self.talents.lightning_mult
        enemy.take_damage(dmg, "lightning")
        self.storm_flash = 1.0
        self.add_shake(8.0)
        self.bolts.append([enemy.x, enemy.y, 0.28])
        self.effects.burst(enemy.x, enemy.y, 26, (220, 235, 255), speed=340,
                           life=0.5, size=4)
        self.effects.text(enemy.x, enemy.y - 30, f"ZAP {int(dmg)}",
                          (190, 225, 255), 26)

    def add_score(self, pts, x, y, hits, combo):
        pts = int(pts * (1.0 + self.horn_bonus) * self.talents.score_mult)
        self.score += pts
        self.best_fling = max(self.best_fling, pts)
        if hits > 0:
            self.best_combo = max(self.best_combo, combo)
            self.combo_flash = 1.0
            self.effects.text(x, y, f"+{pts}   x{combo:.2f} COMBO",
                              (255, 214, 120), 27, 1.35)
        else:
            self.effects.text(x, y, f"+{pts}", (196, 216, 248), 20, 0.9)

    @property
    def spike_level(self):
        return self.spikes.level

    @spike_level.setter
    def spike_level(self, value):
        self.spikes.level = int(value)

    @property
    def spike_damage(self):
        return self.spikes.damage

    def apply_spikes(self, enemy):
        """Anything that strikes a spiked wall takes damage straight back."""
        self.spikes.bite(enemy)

    def enemy_slow(self, enemy):
        """Combined speed multiplier the talent tree imposes on an enemy."""
        slow = 1.0
        gale = self.talents.storm_wind_slow
        if gale > 0 and self.wind < 0 and abs(self.wind) >= WIND_MAX * HEADWIND_THRESHOLD:
            slow *= (1.0 - gale)
        chill = self.talents.grave_chill
        if chill > 0 and enemy.state == "attack" and self.allies:
            slow *= (1.0 - min(0.6, chill))
        return slow

    def add_shake(self, amount):
        # capped: the world is blitted at an offset, so a big shake would
        # expose bare edges at the screen border
        self.shake = min(14.0, self.shake + amount)

    def announce(self, text, color=C_WHITE, life=2.6):
        self.banners.append([text, color, life, life])

    def spawn_enemy(self, e):
        self.enemies.append(e)

    def make_ally(self, x):
        """Built here so castle.py never has to import from enemies.py."""
        ally = FriendlySkeleton(self, max(1, self.wave), x)
        ally.life += self.talents.ally_life
        return ally

    def ally_in_front(self, enemy):
        """The friendly skeleton blocking this enemy's path, if any."""
        for a in self.allies:
            if not a.alive:
                continue
            if abs(a.depth - enemy.depth) > 22:
                continue
            gap = enemy.x - a.x
            if 0 <= gap <= (enemy.w + a.w) * 0.5 + ALLY_ENGAGE_RANGE * 0.5:
                return a
        return None

    def on_boss_defeated(self, boss):
        """Every boss killed lights up the next slot on the skill bar."""
        skill = self.skills.unlock_next()
        if skill is None:
            self.talents.award(3, " -- boss bounty")
            return
        self.announce(f"SKILL UNLOCKED: {skill.name}  [{pygame.key.name(skill.hotkey).upper()}]",
                      SKILL_COLORS.get(skill.key, C_GOLD), 5.0)
        self.announce(skill.desc, C_DIM, 5.0)
        self.talents.award(2, " -- boss bounty")

    def summonable_types(self):
        pool = [c for (c, _) in unlocked_types(max(1, self.wave))
                if c not in (SiegeRam, Necromancer)]
        return pool or [Skeleton]

    def alive_enemies(self):
        return [e for e in self.enemies if e.alive]

    def current_boss(self):
        for e in self.enemies:
            if e.alive and e.IS_BOSS:
                return e
        return None

    # -- wave control ----------------------------------------------------
    def roll_weather(self, announce=True):
        """Pick this stretch's wind and storm."""
        self.wind = random.uniform(-1.0, 1.0) * WIND_MAX
        self.storm = random.random() < STORM_CHANCE * self.talents.storm_chance
        if not announce:
            return
        if abs(self.wind) > WIND_MAX * 0.45:
            if self.wind > 0:
                self.announce("TAILWIND -- your throws carry further",
                              (150, 220, 255), 3.0)
            else:
                self.announce("HEADWIND -- throws blow back at the wall",
                              (255, 180, 140), 3.0)
        if self.storm:
            self.announce("THUNDERSTORM -- fling them high to call lightning!",
                          (200, 220, 255), 3.6)

    # ------------------------------------------------------------------
    #  Endless Attackers
    # ------------------------------------------------------------------
    @property
    def endless(self):
        return self.mode == MODE_ENDLESS

    def endless_spawn_gap(self):
        """Seconds until the next mob, tightening the longer you survive."""
        t = clamp(self.play_time / ENDLESS_SPAWN_RAMP, 0.0, 1.0)
        gap = lerp(ENDLESS_SPAWN_START, ENDLESS_SPAWN_MIN, t)
        return gap * random.uniform(1.0 - ENDLESS_SPAWN_JITTER,
                                    1.0 + ENDLESS_SPAWN_JITTER)

    def begin_endless(self):
        """First entry into an Endless run."""
        self.wave = 1
        self.play_time = 0.0
        self.next_boss = 0
        self.extra_bosses = 0
        self.wave_active = True
        self.spawn_queue = []
        self.spawn_timer = 1.2
        self.horn_used = False
        self.horn_bonus = 0.0
        self.started = True
        self.castle.restore_towers()
        self.roll_weather()
        self.announce("ENDLESS ATTACKERS", (120, 214, 240), 2.6)
        self.announce("They do not stop coming. Good luck.", C_DIM, 3.0)

    def summon_boss(self, cls, scripted=True):
        boss = cls(self, self.wave)
        self.spawn_enemy(boss)
        self.add_shake(9.0)
        self.announce(f"!! {boss.NAME} arrives !!", (255, 120, 100), 4.0)
        if boss.HINT:
            self.announce(boss.HINT, (255, 190, 150), 4.6)

    def update_endless_schedule(self, dt):
        """Advance the run clock, step the difficulty, and run the boss
        timetable defined in the tuning block at the top of this file."""
        self.play_time += dt
        self.talent_seconds += dt
        if self.talent_seconds >= TALENT_SECONDS_PER_POINT:
            self.talent_seconds -= TALENT_SECONDS_PER_POINT
            self.talents.award(1, " -- another minute survived")

        tier = 1 + int(self.play_time / ENDLESS_TIER_SECONDS)
        if tier != self.wave:
            self.wave = tier
            self.castle.restore_towers()
            self.roll_weather()
            self.announce(f"TIER {tier}", C_GOLD, 1.8)
            for cls in newly_unlocked(tier):
                self.announce(f"New foe: {cls.NAME} - {cls.DESC}", cls.COLOR, 3.6)

        # scripted bosses, then a random one on repeat
        while (self.next_boss < len(ENDLESS_BOSS_SCHEDULE)
               and self.play_time >= ENDLESS_BOSS_SCHEDULE[self.next_boss][0]):
            self.summon_boss(ENDLESS_BOSS_SCHEDULE[self.next_boss][1])
            self.next_boss += 1
        if self.next_boss >= len(ENDLESS_BOSS_SCHEDULE):
            last = ENDLESS_BOSS_SCHEDULE[-1][0]
            due = int((self.play_time - last) / ENDLESS_BOSS_REPEAT)
            if due > self.extra_bosses:
                self.extra_bosses = due
                self.summon_boss(random.choice(BOSS_ROTATION), scripted=False)

        # continuous trickle of regular mobs
        self.spawn_timer -= dt
        if self.spawn_timer <= 0:
            self.spawn_timer = self.endless_spawn_gap()
            if len(self.alive_enemies()) < ENDLESS_MAX_ALIVE:
                self.spawn_enemy(self.roll_endless_mob()(self, self.wave))

    def roll_endless_mob(self):
        pool = unlocked_types(self.wave)
        if not pool:
            return Scout
        classes = [c for (c, _w) in pool]
        weights = [1.0 / w for (_c, w) in pool]
        if (self.wave >= GOBLIN_FROM_WAVE
                and random.random() < GOBLIN_CHANCE * 0.05):
            return TreasureGoblin
        return random.choices(classes, weights=weights, k=1)[0]

    # ------------------------------------------------------------------
    def begin_play(self):
        """Leave the armoury and get on with it, whichever mode is running."""
        if self.endless:
            if not self.started:
                self.begin_endless()
            self.realtime_shop = False
            self.state = self.PLAYING
        else:
            self.start_wave()

    def open_realtime_shop(self):
        """Endless mode: the armoury mid-fight. The action holds while it
        is open -- update() only ticks the world in the PLAYING state."""
        if self.state != self.PLAYING or not self.endless:
            return False
        self.realtime_shop = True
        self.state = self.SHOP
        self.shop_msg = "Action paused. Spend, then hit RESUME."
        self.shop_msg_t = 4.0
        self.grabbed = None
        self.grabbed_extra = []
        self.stripping = None
        self.smacking = None
        self.charging = None
        return True

    def start_wave(self):
        self.wave += 1
        self.state = self.PLAYING
        self.wave_active = True
        self.wave_clear_delay = 0.0
        self.spawn_queue = build_wave(self.wave)
        self.spawn_interval = max(0.32, 1.25 - self.wave * 0.032)
        self.spawn_timer = 0.8
        self.castle.restore_towers()
        self.roll_weather()
        self.horn_used = False
        self.horn_bonus = 0.0
        self.announce(f"WAVE {self.wave}", C_GOLD, 2.2)
        if abs(self.wind) > WIND_MAX * 0.45:
            if self.wind > 0:
                self.announce("TAILWIND -- your throws carry further",
                              (150, 220, 255), 3.0)
            else:
                self.announce("HEADWIND -- throws blow back at the wall",
                              (255, 180, 140), 3.0)
        if self.storm:
            self.announce("THUNDERSTORM -- fling them high to call lightning!",
                          (200, 220, 255), 3.6)
        tier = endgame_tier(self.wave)
        if tier >= 0 and endgame_tier(self.wave - 1) != tier:
            name, _f, tint, _s, _h, _d, _sp = ENDGAME_TIERS[tier]
            self.announce(f"{name.upper()} TIER -- the horde has changed",
                          tint, 4.0)
        for cls in newly_unlocked(self.wave):
            self.announce(f"New foe: {cls.NAME} - {cls.DESC}", cls.COLOR, 4.2)
        boss = boss_for_wave(self.wave)
        if boss is not None:
            self.announce(f"!! {boss.NAME} approaches !!", (255, 120, 100), 4.5)

    def choose_mode(self, mode):
        """Menu -> armoury, with the picked mode locked in for this run."""
        self.mode = mode
        self.started = False
        self.open_first_shop()

    def open_first_shop(self):
        """Menu -> armoury, so the player can build a defence before wave 1."""
        self.state = self.SHOP
        self.shop_msg = "Spend your starting gold, then send in wave 1."
        self.shop_msg_t = 6.0

    def end_wave(self):
        self.wave_active = False
        self.talents.award(TALENT_POINTS_PER_WAVE, f" -- wave {self.wave}")
        bonus = 80 + self.wave * 22 + int(self.talents.wave_purse)
        self.gold += bonus
        self.castle.restore_towers()
        self.effects.clear()
        self.projectiles.clear()
        self.grabbed = None
        self.grabbed_extra = []
        self.stripping = None
        self.held_item = None
        self.charging = None
        self.smacking = None
        self.items.clear()
        self.shop_msg = f"Wave {self.wave} cleared!  Bonus +{bonus} gold."
        self.shop_msg_t = 4.0
        self.state = self.SHOP

    def on_castle_destroyed(self):
        self.state = self.GAMEOVER
        self.grabbed = None
        self.grabbed_extra = []
        self.stripping = None
        self.held_item = None
        self.charging = None
        self.smacking = None
        self.add_shake(14.0)
        self.effects.burst(CASTLE_FRONT * 0.5, WALL_TOP + 60, 160,
                           (200, 120, 90), speed=700, life=1.4, size=6)

    # -- purchasing ------------------------------------------------------
    def try_buy(self, item):
        if not item.avail_fn():
            self.shop_msg, self.shop_msg_t = f"{item.name} is maxed out.", 2.0
            return
        cost = item.cost
        if self.gold < cost:
            self.shop_msg, self.shop_msg_t = "Not enough gold!", 2.0
            return
        ok, msg = item.buy_fn()
        if ok:
            self.gold -= cost
            if item.key in self.purchases:
                self.purchases[item.key] += 1
        self.shop_msg, self.shop_msg_t = msg, 2.6

    # -- grabbing --------------------------------------------------------
    def enemy_under_mouse(self, pos):
        best = None
        for e in self.enemies:
            if not e.grabbable:
                continue
            if e.grab_rect.collidepoint(pos):
                if best is None or e.x < best.x:   # prefer the nearest threat
                    best = e
        return best

    def heavy_under_mouse(self, pos):
        """A heavy unit the cursor can work on -- either armour left to tear
        off, or a bare hull that can be shoved."""
        best = None
        for e in self.enemies:
            if not (e.strippable or e.shovable):
                continue
            if e.grab_rect.collidepoint(pos):
                if best is None or e.x < best.x:
                    best = e
        return best

    def regalia_under_mouse(self, pos):
        """A boss item (crown / staff) still attached to its owner."""
        for e in self.enemies:
            if not e.alive:
                continue
            r = e.regalia_rect()
            if r is not None and r.collidepoint(pos):
                return e
        return None

    def item_on_ground_at(self, pos):
        for it in self.items:
            if it.alive and it.state == "ground" and it.rect.collidepoint(pos):
                return it
        return None

    def tower_under_mouse(self, pos):
        for t in self.castle.towers:
            if t.can_overcharge and t.rect.inflate(14, 14).collidepoint(pos):
                return t
        return None

    def overcharge_power(self):
        """How far the slingshot has been drawn back, 0..1."""
        if self.charging is None:
            return 0.0
        mx, my = self.mouse_pos
        d = math.hypot(self.charging.muzzle[0] - mx,
                       self.charging.muzzle[1] - my)
        return clamp(d / OVERCHARGE_PULL, 0.0, 1.0)

    def try_grab(self, pos):
        if (self.grab_cd > 0 or self.grabbed is not None or self.stripping
                or self.held_item is not None or self.charging is not None):
            return

        # a Ballista or Cannon under the cursor gets hand-aimed instead
        t = self.tower_under_mouse(pos)
        if t is not None:
            self.charging = t
            return

        # boss regalia wins the click -- it sits on top of a big target
        owner = self.regalia_under_mouse(pos)
        if owner is not None:
            item = owner.detach_regalia()
            if item is not None:
                self.items.append(item)
                self.held_item = item
                self.mouse_hist.clear()
                self.mouse_hist.append((self.time, pos[0], pos[1]))
                self.add_shake(5.0)
                return

        # a crown already lying about can be picked up and thrown again
        loose = self.item_on_ground_at(pos)
        if loose is not None:
            loose.state = "held"
            self.held_item = loose
            self.mouse_hist.clear()
            self.mouse_hist.append((self.time, pos[0], pos[1]))
            return

        # a Dragon's claws can be battered
        for en in self.enemies:
            sr = en.smack_rect()
            if sr is not None and sr.collidepoint(pos):
                self.smacking = en
                self.strip_anchor = pos
                self.effects.text(en.x, en.y, "SMACK!", (255, 190, 120), 20)
                return

        e = self.enemy_under_mouse(pos)
        if e is None:
            # nothing liftable here -- is there a tank to dismantle instead?
            heavy = self.heavy_under_mouse(pos)
            if heavy is not None:
                self.stripping = heavy
                self.strip_anchor = pos
                self.effects.text(heavy.x, heavy.y - heavy.h, "PULL!",
                                  (255, 214, 120), 20)
            return
        self.grabbed = e
        e.on_grab()
        # Magnetic Gloves drag nearby mobs along in formation
        self.grabbed_extra = []
        if self.multi_level > 0:
            for o in self.enemies:
                if o is e or len(self.grabbed_extra) >= self.multi_level:
                    continue
                if not o.grabbable:
                    continue
                if math.hypot(o.x - e.x, o.y - e.y) <= MULTI_RADIUS:
                    o.on_grab()
                    self.grabbed_extra.append([o, o.x - e.x, o.y - e.y])
            if self.grabbed_extra:
                self.effects.text(e.x - 46, e.y - e.h - 26,
                                  f"x{len(self.grabbed_extra) + 1} GRAB",
                                  (170, 220, 255), 22)
        self.mouse_hist.clear()
        self.mouse_hist.append((self.time, pos[0], pos[1]))
        self.effects.ring(e.x, e.y, 12, (230, 230, 255), speed=200,
                          life=0.3, size=3)

    def release_grab(self):
        if self.smacking is not None:
            self.smacking = None
            self.grab_cd = GRAB_COOLDOWN
            return
        if self.charging is not None:
            # power must be read *before* clearing the charge, or it is 0
            power = self.overcharge_power()
            t, self.charging = self.charging, None
            self.grab_cd = GRAB_COOLDOWN
            if power > 0.12 and t.can_overcharge:
                t.overcharge_fire(self.mouse_pos[0], self.mouse_pos[1], power)
            return
        if self.held_item is not None:
            it = self.held_item
            self.held_item = None
            self.grab_cd = GRAB_COOLDOWN
            vx, vy = self.mouse_velocity()
            it.throw(vx, vy)
            return
        if self.stripping is not None:
            self.stripping = None
            self.grab_cd = GRAB_COOLDOWN
            return
        e = self.grabbed
        self.grabbed = None
        self.grab_cd = GRAB_COOLDOWN
        if e is None or not e.alive:
            return
        vx, vy = self.mouse_velocity()
        e.on_release(vx, vy)
        for o, _ox, _oy in self.grabbed_extra:
            if o.alive:
                o.on_release(vx * random.uniform(0.85, 1.15),
                             vy * random.uniform(0.85, 1.15))
        self.grabbed_extra = []

    def mouse_velocity(self):
        """Cursor speed sampled ~90 ms back, so a flick reads cleanly."""
        if len(self.mouse_hist) < 2:
            return 0.0, 0.0
        t1, x1, y1 = self.mouse_hist[-1]
        t0, x0, y0 = self.mouse_hist[0]
        for sample in self.mouse_hist:
            if t1 - sample[0] <= 0.09:
                t0, x0, y0 = sample
                break
        dt = max(1e-3, t1 - t0)
        return (clamp((x1 - x0) / dt, -2600, 2600),
                clamp((y1 - y0) / dt, -2600, 2600))

    def update_grab(self, dt):
        self.grab_cd = max(0.0, self.grab_cd - dt)

        if self.charging is not None:
            if not self.charging.can_overcharge:
                self.charging = None
            return

        # --- carrying a crown or staff --------------------------------
        it = self.held_item
        if it is not None:
            if not it.alive:
                self.held_item = None
            else:
                mx, my = self.mouse_pos
                self.mouse_hist.append((self.time, mx, my))
                it.x += (mx - it.x) * 0.55
                it.y += (my - it.y) * 0.55
                it.spin += 0.25
                return

        # --- battering a Dragon's claws -------------------------------
        d = self.smacking
        if d is not None:
            if d.smack_rect() is None:
                self.smacking = None
            else:
                mx, my = self.mouse_pos
                ax, ay = self.strip_anchor
                moved = math.hypot(mx - ax, my - ay)
                self.strip_anchor = (mx, my)
                if moved > 0.5:
                    d.apply_smack(moved)
                return

        # --- dismantling a heavy unit ---------------------------------
        h = self.stripping
        if h is not None:
            # the session survives the armour coming off -- a bare hull can
            # still be shoved, so only drop it when there is nothing left to do
            if not (h.strippable or h.shovable):
                self.stripping = None      # dead, or no longer interactable
            else:
                mx, my = self.mouse_pos
                ax, ay = self.strip_anchor
                dx = mx - ax
                self.strip_anchor = (mx, my)
                if h.armored:
                    # PHASE 1 -- armour on: hauling AWAY strips it, and that
                    # is the *only* thing the cursor can do to this unit.
                    if dx > 0.5:
                        h.apply_strip(dx)
                        if random.random() < 0.4:
                            self.effects.burst(h.x + random.uniform(-30, 30),
                                               h.y, 2, (188, 192, 206),
                                               speed=120, life=0.3, size=2)
                    elif dx < -0.5 and random.random() < 0.03:
                        self.effects.text(h.x, h.y - h.h * 0.9,
                                          "ARMOR FIRST!", (255, 150, 130), 20)
                elif dx < -0.5:
                    # PHASE 2 -- stripped bare: hauling TOWARD the castle
                    # shoves it forward.
                    h.apply_shove(-dx)
                return

        e = self.grabbed
        if e is None:
            return
        if not e.alive:
            self.grabbed = None
            return
        mx, my = self.mouse_pos
        self.mouse_hist.append((self.time, mx, my))
        follow = clamp(1.0 - 0.10 * e.MASS, 0.25, 0.95)
        e.x += (mx - e.x) * follow
        e.y += (my - e.y) * follow
        e.x = clamp(e.x, CASTLE_FRONT + e.w / 2, WIDTH + 120)
        e.y = clamp(e.y, 30, GROUND_Y + e.depth - e.h / 2)
        e.spin += 0.08

        self.grabbed_extra = [g for g in self.grabbed_extra if g[0].alive]
        for o, ox, oy in self.grabbed_extra:
            of = clamp(1.0 - 0.10 * o.MASS, 0.25, 0.95)
            o.x += (e.x + ox - o.x) * of
            o.y += (e.y + oy - o.y) * of
            o.x = clamp(o.x, CASTLE_FRONT + o.w / 2, WIDTH + 120)
            o.y = clamp(o.y, 30, GROUND_Y + o.depth - o.h / 2)
            o.spin += 0.08

    # -- main update -----------------------------------------------------
    def update(self, dt):
        self.time += dt
        self.shake = max(0.0, self.shake - dt * 42.0)
        self.combo_flash = max(0.0, self.combo_flash - dt * 1.6)
        self.storm_flash = max(0.0, self.storm_flash - dt * 3.5)
        self.horn_glow = max(0.0, self.horn_glow - dt * 2.0)
        for b in self.bolts:
            b[2] -= dt
        self.bolts = [b for b in self.bolts if b[2] > 0]
        for b in self.banners:
            b[2] -= dt
        self.banners = [b for b in self.banners if b[2] > 0]
        if self.shop_msg_t > 0:
            self.shop_msg_t -= dt

        if self.state == self.TALENTS:
            self.effects.update(dt)
            return
        if self.state != self.PLAYING:
            # the hit flash must keep fading even on the pause / defeat
            # screens, or the castle stays frozen mid-flash
            self.castle.flash = max(0.0, self.castle.flash - dt * 5.0)
            self.effects.update(dt)
            return

        self.update_grab(dt)

        if self.endless:
            self.update_endless_schedule(dt)

        # spawn
        if self.spawn_queue:
            self.spawn_timer -= dt
            if self.spawn_timer <= 0 and len(self.alive_enemies()) < MAX_ALIVE:
                cls = self.spawn_queue.pop(0)
                e = cls(self, self.wave)
                self.spawn_enemy(e)
                self.spawn_timer = self.spawn_interval * \
                    (2.4 if e.IS_BOSS else random.uniform(0.7, 1.3))
                if e.IS_BOSS:
                    self.add_shake(8.0)

        self.skills.update(dt)
        self.castle.update(dt)
        self.outpost.update(dt)
        self.barricade.update(dt)
        regen = self.talents.barricade_regen
        if regen > 0 and self.barricade.alive:
            self.barricade.hp = min(self.barricade.max_hp,
                                    self.barricade.hp
                                    + self.barricade.max_hp * regen * dt)
        for it in self.items:
            it.update(dt)
        self.items = [it for it in self.items if it.alive]
        for e in self.enemies:
            e.update(dt)
        for a in self.allies:
            a.update(dt)
        self.allies = [a for a in self.allies if a.alive]
        for z in self.fire_zones:
            z.update(dt)
        self.fire_zones = [z for z in self.fire_zones if z.alive]
        for t in self.tornados:
            t.update(dt)
        self.tornados = [t for t in self.tornados if t.alive]
        self.separate_enemies(dt)
        for p in self.projectiles:
            p.update(dt)
        self.projectiles = [p for p in self.projectiles if p.alive]
        self.enemies = [e for e in self.enemies if e.alive]
        self.effects.update(dt)

        if self.grabbed is not None and not self.grabbed.alive:
            self.grabbed = None
            self.grabbed_extra = []
        if self.stripping is not None and not self.stripping.alive:
            self.stripping = None
        # a dead owner's regalia is just scenery -- clear it out, including
        # the piece the player may still be carrying
        for it in self.items:
            if it.owner is not None and not it.owner.alive:
                it.kill()
        self.items = [it for it in self.items if it.alive]
        if self.held_item is not None and not self.held_item.alive:
            self.held_item = None
        if self.charging is not None and self.charging.disabled:
            self.charging = None
        if self.smacking is not None and not self.smacking.alive:
            self.smacking = None

        # wave completion -- Classic only; an Endless run never pauses itself
        if (not self.endless and self.wave_active
                and not self.spawn_queue and not self.alive_enemies()):
            self.wave_clear_delay += dt
            if self.wave_clear_delay > 1.1:
                self.end_wave()

    def separate_enemies(self, dt):
        """Keep the ground crowd from stacking into a single pixel column."""
        ground = [e for e in self.enemies
                  if e.alive and not e.flying and e.state in ("walk", "attack")]
        for e in ground:
            e.blocked = False
        n = len(ground)
        for i in range(n):
            a = ground[i]
            for j in range(i + 1, n):
                b = ground[j]
                if abs(a.depth - b.depth) > 13:
                    continue
                # whoever is further right is queued behind the other
                gap = (a.w + b.w) * 0.55
                if 0 < a.x - b.x <= gap:
                    a.blocked = True
                elif 0 < b.x - a.x <= gap:
                    b.blocked = True
                min_d = (a.w + b.w) * 0.42
                d = a.x - b.x
                if -min_d < d < min_d:
                    push = (min_d - abs(d)) * 0.5
                    sgn = 1.0 if d >= 0 else -1.0
                    total = a.MASS + b.MASS
                    a.x += push * sgn * (b.MASS / total)
                    b.x -= push * sgn * (a.MASS / total)
                    a.x = max(a.x, CASTLE_FRONT + a.w / 2)
                    b.x = max(b.x, CASTLE_FRONT + b.w / 2)

    # -- input -----------------------------------------------------------
    def handle_event(self, ev):
        if ev.type == pygame.QUIT:
            self.running = False
            return
        if ev.type == pygame.MOUSEMOTION:
            self.mouse_pos = ev.pos
            return

        if ev.type == pygame.KEYDOWN:
            # --- talent tree door, and the skill hotkeys ---
            if ev.key == pygame.K_t:
                if self.state == self.TALENTS:
                    self.close_talents()
                elif self.state in (self.SHOP, self.PLAYING):
                    self.open_talents()
                return
            if self.state == self.TALENTS:
                if ev.key == pygame.K_ESCAPE:
                    self.close_talents()
                return
            if (self.state == self.PLAYING
                    and self.skills.handle_key(ev.key)):
                return
            if ev.key in (pygame.K_ESCAPE, pygame.K_p):
                if self.state == self.PLAYING:
                    self.state = self.PAUSED
                elif self.state == self.PAUSED:
                    self.state = self.PLAYING
            elif ev.key in (pygame.K_1, pygame.K_2) and self.state == self.MENU:
                self.choose_mode(MODE_CLASSIC if ev.key == pygame.K_1
                                 else MODE_ENDLESS)
            elif ev.key in (pygame.K_SPACE, pygame.K_RETURN, pygame.K_KP_ENTER):
                if self.state == self.MENU:
                    self.choose_mode(MODE_CLASSIC)
                elif self.state == self.SHOP:
                    self.begin_play()
            elif ev.key == pygame.K_r and self.state == self.GAMEOVER:
                self.reset()
                self.state = self.MENU
            elif self.state == self.SHOP and (pygame.K_0 <= ev.key <= pygame.K_9):
                idx = 9 if ev.key == pygame.K_0 else ev.key - pygame.K_1
                if 0 <= idx < len(self.shop_items):
                    self.try_buy(self.shop_items[idx])
            return

        if ev.type == pygame.MOUSEBUTTONDOWN and ev.button == 1:
            self.mouse_pos = ev.pos
            if self.state == self.TALENTS:
                if self.talent_back_btn.collidepoint(ev.pos):
                    self.close_talents()
                    return
                for t in TALENTS:
                    if t.rect.collidepoint(ev.pos) and self.talents.can_buy(t):
                        self.talents.buy(t)
                        self.effects.text(t.rect.centerx, t.rect.top - 6,
                                          "+1", BRANCH_COLORS[t.branch], 22)
                        return
                return
            if self.state == self.PLAYING:
                # the skill bar gets first refusal on a click
                if self.skills.handle_click(ev.pos):
                    return
                if self.shop_btn.collidepoint(ev.pos):
                    self.open_realtime_shop()
                    return
                if HORN_RECT.collidepoint(ev.pos) and not self.horn_used:
                    self.blow_horn()
                    return
                self.try_grab(ev.pos)
            elif self.state == self.MENU:
                for mode, box in self.mode_buttons.items():
                    if box.collidepoint(ev.pos):
                        self.choose_mode(mode)
                        return
            elif self.state == self.SHOP:
                if self.talent_btn.collidepoint(ev.pos):
                    self.open_talents()
                    return
                if self.start_btn.collidepoint(ev.pos):
                    self.begin_play()
                    return
                for item in self.shop_items:
                    if item.rect.collidepoint(ev.pos):
                        self.try_buy(item)
                        return
            elif self.state == self.GAMEOVER:
                self.reset()
                self.state = self.MENU
            return

        if ev.type == pygame.MOUSEBUTTONUP and ev.button == 1:
            self.mouse_pos = ev.pos
            if (self.grabbed is not None or self.stripping is not None
                    or self.held_item is not None or self.charging is not None
                    or self.smacking is not None):
                self.release_grab()
            return

    # -- drawing ---------------------------------------------------------
    def draw(self):
        s = self.scene
        s.blit(self.bg, (0, 0))
        self.outpost.draw(s)          # background scenery, behind the fight
        self.castle.draw(s)
        self.barricade.draw(s)

        # flying first (behind), then ground back-to-front by depth
        order = sorted(self.enemies,
                       key=lambda e: (0 if e.flying else 1, e.depth, e.x))
        for e in order:
            e.draw(s)
        for a in self.allies:
            a.draw(s)
        for it in self.items:
            it.draw(s)
        for z in self.fire_zones:
            z.draw(s)
        for t in self.tornados:
            t.draw(s)
        for p in self.projectiles:
            p.draw(s)
        self.effects.draw(s)
        self.draw_weather(s)
        if self.state in (self.PLAYING, self.PAUSED):
            self.skills.draw(s)
        if self.state in (self.PLAYING, self.PAUSED):
            self.draw_horn(s)

        if self.state == self.PLAYING:
            self.draw_grab_cursor(s)

        # blit the world with screen shake, then UI on top
        if self.shake > 0.4:
            ox = random.uniform(-self.shake, self.shake)
            oy = random.uniform(-self.shake, self.shake)
        else:
            ox = oy = 0
        self.screen.fill((0, 0, 0))
        self.screen.blit(s, (ox, oy))

        self.draw_hud(self.screen)
        if self.state == self.MENU:
            self.draw_menu(self.screen)
        elif self.state == self.SHOP:
            self.draw_shop(self.screen)
        elif self.state == self.TALENTS:
            self.draw_talents(self.screen)
        elif self.state == self.PAUSED:
            self.draw_center_panel(self.screen, "PAUSED",
                                   ["Press P or ESC to resume."])
        elif self.state == self.GAMEOVER:
            self.draw_gameover(self.screen)

    def draw_weather(self, s):
        # wind streaks
        if abs(self.wind) > 40:
            n = int(abs(self.wind) / 26)
            for i in range(n):
                seed = (i * 97 + int(self.time * abs(self.wind) * 0.5)) % 1400
                wx = (seed - 60) if self.wind > 0 else (1340 - seed)
                wy = 90 + (i * 137) % 430
                ln = 14 + (i % 3) * 10
                pygame.draw.line(s, (188, 200, 224),
                                 (wx, wy), (wx - math.copysign(ln, self.wind), wy), 1)
        # lightning bolts, drawn as a jagged path from the clouds
        for bx, by, life in self.bolts:
            pts, y = [(bx, by)], by
            x = bx
            while y > -20:
                y -= random.uniform(24, 46)
                x += random.uniform(-26, 26)
                pts.append((x, y))
            pygame.draw.lines(s, (236, 244, 255), False, pts, 4)
            pygame.draw.lines(s, (150, 200, 255), False, pts, 2)
        if self.storm_flash > 0:
            veil = pygame.Surface((WIDTH, HEIGHT), pygame.SRCALPHA)
            veil.fill((190, 215, 255, int(70 * self.storm_flash)))
            s.blit(veil, (0, 0))

    def draw_horn(self, s):
        r = HORN_RECT
        used = self.horn_used
        base = (96, 84, 60) if used else (168, 138, 78)
        if self.horn_glow > 0:
            base = mix(base, (255, 240, 180), self.horn_glow)
        pygame.draw.circle(s, (54, 48, 40), r.center, 26)
        pygame.draw.circle(s, base, r.center, 23)
        pygame.draw.circle(s, shade(base, 0.6), r.center, 23, 3)
        # a curled horn glyph
        cx, cy = r.center
        pygame.draw.arc(s, (250, 238, 206) if not used else (130, 120, 100),
                        pygame.Rect(cx - 15, cy - 13, 30, 26), 0.5, 4.2, 5)
        pygame.draw.polygon(s, (250, 238, 206) if not used else (130, 120, 100),
                            [(cx + 9, cy - 11), (cx + 19, cy - 17),
                             (cx + 14, cy - 4)])
        if not used and self.wave_active and self.spawn_queue:
            draw_text(s, "CHALLENGE", cx, r.bottom + 2, 15, C_GOLD,
                      "center", True)
        elif used:
            draw_text(s, f"+{int(self.horn_bonus * 100)}%", cx, r.bottom + 2,
                      15, (200, 190, 160), "center", True)

    def draw_grab_cursor(self, s):
        mx, my = self.mouse_pos

        # --- slingshot draw-back on a hand-aimed tower ---
        if self.charging is not None:
            t = self.charging
            gx, gy = t.muzzle
            power = self.overcharge_power()
            col = mix((150, 200, 255), (255, 170, 90), power)
            pygame.draw.line(s, col, (gx, gy), (mx, my), 3)
            # the shot will travel opposite the pull
            dx, dy = gx - mx, gy - my
            d = max(1.0, math.hypot(dx, dy))
            for k in range(1, 7):
                px = gx + dx / d * k * 34
                py = gy + dy / d * k * 34 + 0.5 * 9.8 * (k * 0.1) ** 2 * 30
                pygame.draw.circle(s, (*col, 200), (int(px), int(py)),
                                   max(1, 4 - k // 2))
            pygame.draw.circle(s, col, (int(gx), int(gy)), int(8 + 10 * power), 2)
            draw_bar(s, int(gx - 26), int(gy - 34), 52, 6, power, col)
            draw_text(s, f"{int(power * 100)}%", gx, gy - 54, 18, col,
                      "center", True)
            return

        smack = self.smacking
        if smack is None:
            for en in self.enemies:
                sr = en.smack_rect()
                if sr is not None and sr.collidepoint(self.mouse_pos):
                    smack = en
                    break
        if smack is not None:
            sr = smack.smack_rect()
            if sr is not None:
                col = (255, 190, 120)
                pygame.draw.rect(s, col, sr, 2, border_radius=5)
                draw_text(s, "SMACK THE CLAWS!" if self.smacking
                          else "DRAG ON THE CLAWS", sr.centerx, sr.bottom + 4,
                          17, col, "center", True)
                if self.smacking is not None:
                    draw_bar(s, sr.centerx - 30, sr.top - 12, 60, 6,
                             smack.claw_progress, col)

        hov = self.tower_under_mouse(self.mouse_pos)
        if hov is not None and self.grabbed is None:
            r = hov.rect.inflate(14, 14)
            pygame.draw.rect(s, (150, 200, 255), r, 2, border_radius=5)
            draw_text(s, "DRAG BACK TO OVERCHARGE", r.centerx, r.top - 22, 16,
                      (150, 200, 255), "center", True)

        # boss regalia under the cursor -- how the player discovers this at all
        if self.held_item is None:
            owner = self.regalia_under_mouse(self.mouse_pos)
            if owner is not None:
                r = owner.regalia_rect()
                pygame.draw.rect(s, (255, 226, 140), r, 2, border_radius=5)
                label = ("RIP OFF THE CROWN" if isinstance(owner, TrollKing)
                         else "FLICK THE STAFF AWAY")
                draw_text(s, label, r.centerx, r.top - 46, 18,
                          (255, 226, 140), "center", True)
        elif self.held_item is not None:
            it = self.held_item
            draw_text(s, "FLING IT!", it.x, it.y - 34, 18,
                      (255, 226, 140), "center", True)

        # heavy unit under the cursor (or being dismantled right now)
        heavy = self.stripping or self.heavy_under_mouse(self.mouse_pos)
        if heavy is not None and self.grabbed is None:
            r = heavy.grab_rect
            col = (255, 170, 90) if self.stripping else (220, 190, 150)
            pygame.draw.rect(s, col, r, 2, border_radius=6)
            for i in range(heavy.ARMOR_LAYERS):
                px = r.left + 6 + i * 12
                on = i < heavy.layers
                pygame.draw.rect(s, col if on else (90, 80, 74),
                                 (px, r.top - 26, 9, 7), 0 if on else 1)
            if heavy.armored:
                label = "RIPPING ARMOR!" if self.stripping \
                    else "DRAG AWAY TO RIP ARMOR"
            else:
                label = "SHOVING!" if self.stripping else "DRAG IN TO SHOVE"
            draw_text(s, label, heavy.x, r.top - 48, 18, col, "center", True)
            if self.stripping:
                pygame.draw.line(s, (255, 200, 120), (mx, my),
                                 (heavy.x, heavy.y), 2)

        target = self.grabbed or self.enemy_under_mouse(self.mouse_pos)
        if target is not None:
            r = target.grab_rect
            col = (255, 230, 140) if self.grabbed else (200, 220, 255)
            pygame.draw.rect(s, col, r, 2, border_radius=6)
            for cx, cy in ((r.left, r.top), (r.right, r.top),
                           (r.left, r.bottom), (r.right, r.bottom)):
                pygame.draw.circle(s, col, (cx, cy), 3)
            if self.grabbed:
                pygame.draw.line(s, (255, 230, 140, 120), (mx, my),
                                 (target.x, target.y), 1)
                draw_text(s, "FLING!", target.x, r.top - 20, 18,
                          (255, 220, 120), "center", True)
        # crosshair
        pygame.draw.circle(s, (230, 235, 250), (mx, my), 9, 1)
        pygame.draw.line(s, (230, 235, 250), (mx - 13, my), (mx - 4, my), 1)
        pygame.draw.line(s, (230, 235, 250), (mx + 4, my), (mx + 13, my), 1)
        pygame.draw.line(s, (230, 235, 250), (mx, my - 13), (mx, my - 4), 1)
        pygame.draw.line(s, (230, 235, 250), (mx, my + 4), (mx, my + 13), 1)

    def draw_hud(self, surf):
        # top-left status block
        # Endless needs an extra row for the run clock and the SHOP button
        panel_h = (176 if self.endless else 148)
        panel = pygame.Surface((360, panel_h), pygame.SRCALPHA)
        panel.fill((*C_PANEL, 190))
        pygame.draw.rect(panel, (*C_PANEL_EDGE, 200), panel.get_rect(), 2,
                         border_radius=6)
        surf.blit(panel, (14, 12))

        if self.endless:
            draw_text(surf, f"TIER {max(1, self.wave)}", 28, 22, 30,
                      C_WHITE, bold=True)
        else:
            draw_text(surf, f"WAVE {max(1, self.wave)}", 28, 22, 30,
                      C_WHITE, bold=True)
        draw_text(surf, f"{self.gold} G", 346, 24, 28, C_GOLD, "right", True)
        tier = endgame_tier(max(1, self.wave))
        if tier >= 0:
            tname, _f, ttint, _s, _h, _d, _sp = ENDGAME_TIERS[tier]
            draw_text(surf, f"{self.castle.tier_name}  -  {tname} horde",
                      28, 52, 18, mix(ttint, C_WHITE, 0.35))
        else:
            draw_text(surf, self.castle.tier_name, 28, 52, 18, C_DIM)

        # crowd bonus: the risk/reward readout
        mult = self.gold_multiplier
        if self.state in (self.PLAYING, self.PAUSED) and mult > 1.005:
            n = sum(1 for e in self.enemies if e.alive)
            hot = mult / POP_GOLD_CAP
            draw_text(surf, f"x{mult:.2f} gold  ({n} mobs)", 346, 52, 18,
                      mix((255, 214, 120), (255, 110, 90), hot), "right", True)

        frac = self.castle.hp / max(1.0, self.castle.max_hp)
        col = C_GREEN if frac > 0.5 else (C_GOLD if frac > 0.25 else C_RED)
        draw_bar(surf, 28, 74, 318, 16, frac, col)
        draw_text(surf, f"{int(self.castle.hp)} / {int(self.castle.max_hp)}",
                  187, 75, 18, C_WHITE, "center")

        sc_col = mix(C_GOLD, (255, 255, 255), self.combo_flash)
        draw_text(surf, f"SCORE {self.score:,}", 28, 96,
                  24 + int(4 * self.combo_flash), sc_col, bold=True)
        if self.best_fling:
            draw_text(surf, f"best fling {self.best_fling:,}", 346, 100, 16,
                      C_DIM, "right")
        # talent points get their own row, clear of the health bar
        ty = 150 if self.endless else 120
        pts = self.talents.points
        draw_text(surf, f"{pts} TALENT POINT{'S' if pts != 1 else ''}  [T]",
                  28, ty, 19, C_TALENT_ON if pts else C_DIM, bold=bool(pts))
        if self.skills.skills:
            ready = sum(1 for s in self.skills.skills if s.ready)
            draw_text(surf, f"skills {ready}/{len(self.skills.skills)} ready",
                      346, ty + 2, 16,
                      C_SKILL_READY if ready else C_DIM, "right")

        # Endless: the run clock and the live armoury button get their own row
        self.shop_btn = pygame.Rect(0, 0, 0, 0)
        if self.endless:
            draw_text(surf, format_clock(self.play_time), 28, 122, 24,
                      (150, 220, 255), bold=True)
            if self.state in (self.PLAYING, self.PAUSED):
                b = pygame.Rect(240, 118, 106, 26)
                self.shop_btn = b
                hot = b.collidepoint(self.mouse_pos)
                pygame.draw.rect(surf, (46, 92, 74) if hot else (34, 66, 54),
                                 b, border_radius=6)
                pygame.draw.rect(surf, C_GREEN, b, 2, border_radius=6)
                draw_text(surf, "SHOP", b.centerx, b.y + 4, 21, C_WHITE,
                          "center", True)

        # top-right wave progress
        if self.state in (self.PLAYING, self.PAUSED):
            left = len(self.alive_enemies()) + len(self.spawn_queue)
            draw_text(surf, f"Enemies left: {left}", WIDTH - 20, 18, 22,
                      C_DIM, "right")
            draw_text(surf, f"Kills: {self.stats_kills}", WIDTH - 20, 42, 20,
                      C_DIM, "right")
            draw_text(surf, f"Throw damage: {int(self.stats_thrown_damage)}",
                      WIDTH - 20, 64, 20, (255, 190, 120), "right")
            if self.stats_plates_torn:
                draw_text(surf, f"Plates torn: {self.stats_plates_torn}",
                          WIDTH - 20, 86, 20, (200, 206, 224), "right")
            wy = 110
            if abs(self.wind) > 40:
                arrow = ">>>" if self.wind > 0 else "<<<"
                lbl = "TAILWIND" if self.wind > 0 else "HEADWIND"
                draw_text(surf, f"{lbl} {arrow}", WIDTH - 20, wy, 20,
                          (150, 220, 255) if self.wind > 0 else (255, 180, 140),
                          "right", True)
                wy += 22
            if self.storm:
                draw_text(surf, "THUNDERSTORM", WIDTH - 20, wy, 20,
                          (200, 220, 255), "right", True)

        # boss bar
        boss = self.current_boss()
        if boss is not None and self.state == self.PLAYING:
            bw = 620
            bx = (WIDTH - bw) // 2
            draw_text(surf, boss.NAME, WIDTH // 2, HEIGHT - 78, 26,
                      (255, 210, 130), "center", True)
            draw_bar(surf, bx, HEIGHT - 50, bw, 20,
                     boss.hp / max(1.0, boss.max_hp), (208, 62, 60))
            draw_text(surf, f"{int(boss.hp)} / {int(boss.max_hp)}",
                      WIDTH // 2, HEIGHT - 48, 18, C_WHITE, "center")

        # banners
        y = 130
        for text, color, life, max_life in self.banners:
            a = clamp(life / max(0.001, max_life), 0.0, 1.0)
            f = font(34, True)
            img = f.render(text, True, color)
            img.set_alpha(int(255 * min(1.0, a * 2.2)))
            surf.blit(img, img.get_rect(midtop=(WIDTH // 2, y)))
            y += 40

        if self.state == self.PLAYING:
            draw_text(surf, "LMB a mob to fling it  -  LMB-drag a Siege Ram to rip "
                      "its armour  -  P to pause",
                      WIDTH // 2, HEIGHT - 24, 18, (170, 176, 194), "center")

    # -- overlay panels --------------------------------------------------
    # padding inside a centred panel, and the space the title block eats
    PANEL_PAD = 34
    PANEL_TITLE_H = 74

    @staticmethod
    def panel_layout(paragraphs, w, title_size=44, reserve_bottom=0):
        """Measure a panel's contents and give back everything needed to draw
        it: the wrapped lines, the panel rect, and where the text starts.

        The panel is sized to its content instead of a hard-coded height,
        which is what used to push the start screen's text out of the box.
        """
        max_w = w - Game.PANEL_PAD * 2
        # `reserve_bottom` keeps a strip clear beneath the panel (the mode
        # picker lives there), so the panel shrinks rather than colliding
        room = HEIGHT - 40 - reserve_bottom
        avail = room - Game.PANEL_TITLE_H - Game.PANEL_PAD * 2
        lines, total, scale = fit_paragraphs(paragraphs, max_w, avail)
        h = min(room, Game.PANEL_TITLE_H + total + Game.PANEL_PAD * 2)
        rect = pygame.Rect((WIDTH - w) // 2, (room - h) // 2 + 20, w, h)
        return lines, rect, rect.top + Game.PANEL_TITLE_H + Game.PANEL_PAD, scale

    def draw_center_panel(self, surf, title, body, w=620, title_size=44,
                          reserve_bottom=0):
        """Centred modal panel. `body` is a list of paragraphs -- either plain
        strings or (text, size, colour, bold) tuples -- and every one of them
        is word-wrapped to the panel width."""
        paragraphs = [p if isinstance(p, tuple) else (p, 22, C_WHITE, False)
                      for p in body]
        lines, r, y, scale = self.panel_layout(paragraphs, w, title_size,
                                               reserve_bottom)

        veil = pygame.Surface((WIDTH, HEIGHT), pygame.SRCALPHA)
        veil.fill((6, 8, 16, 170))
        surf.blit(veil, (0, 0))
        pygame.draw.rect(surf, C_PANEL, r, border_radius=10)
        pygame.draw.rect(surf, C_PANEL_EDGE, r, 3, border_radius=10)
        draw_text(surf, title, r.centerx, r.top + 22,
                  max(24, int(title_size * scale)), C_GOLD, "center", True)

        for text, size, colour, bold, lh in lines:
            if text:
                draw_text(surf, text, r.centerx, y, size, colour, "center",
                          bold=bold)
            y += lh
        return r

    def draw_menu(self, surf):
        cw, gap, ch = 372, 22, 112
        r = self.draw_center_panel(surf, "CASTLE DEFENSE",
                                   list(MENU_PARAGRAPHS), w=820,
                                   reserve_bottom=ch + 30)

        # --- mode picker, in the strip reserved beneath the panel ---
        total = cw * 2 + gap
        x0 = r.centerx - total // 2
        top = min(r.bottom + 16, HEIGHT - ch - 14)
        self.mode_buttons = {}
        for i, mode in enumerate((MODE_CLASSIC, MODE_ENDLESS)):
            name, blurb, colour = MODE_INFO[mode]
            box = pygame.Rect(x0 + i * (cw + gap), top, cw, ch)
            self.mode_buttons[mode] = box
            hot = box.collidepoint(self.mouse_pos)
            pygame.draw.rect(surf, (52, 58, 82) if hot else (32, 36, 54),
                             box, border_radius=10)
            pygame.draw.rect(surf, colour, box, 3, border_radius=10)
            draw_text(surf, f"[{i + 1}]", box.left + 12, box.top + 10, 17,
                      C_DIM)
            draw_text(surf, name, box.centerx, box.top + 10, 25, colour,
                      "center", True)
            ty = box.top + 40
            for ln in wrap_text(blurb, 16, box.w - 28)[:4]:
                draw_text(surf, ln, box.centerx, ty, 16, C_DIM, "center")
                ty += 18

    def draw_gameover(self, surf):
        self.draw_center_panel(surf, "THE CASTLE HAS FALLEN", [
            f"FINAL SCORE   {self.score:,}",
            f"You survived {max(0, self.wave - 1)} full waves"
            f" (fell on wave {self.wave}).",
            f"Enemies slain: {self.stats_kills}",
            f"Best single fling: {self.best_fling:,}"
            f"   Best combo: x{self.best_combo:.2f}",
            f"Damage dealt by throwing: {int(self.stats_thrown_damage)}",
            f"Armour plates torn off: {self.stats_plates_torn}",
            f"Final walls: {self.castle.tier_name}",
            "",
            ("Press R or click to play again.", 24, C_GOLD, True),
        ], w=760)

    def open_talents(self):
        """The tree sits alongside the armoury -- reachable from the shop in
        Classic, and from the same mid-fight button in Endless."""
        self.talent_return = self.state
        self.state = self.TALENTS
        return True

    def close_talents(self):
        self.state = getattr(self, "talent_return", self.SHOP)
        if self.state not in (self.SHOP, self.PLAYING):
            self.state = self.SHOP

    def draw_talents(self, surf):
        veil = pygame.Surface((WIDTH, HEIGHT), pygame.SRCALPHA)
        veil.fill((6, 8, 16, 205))
        surf.blit(veil, (0, 0))
        panel = pygame.Rect(16, 26, WIDTH - 32, HEIGHT - 52)
        pygame.draw.rect(surf, C_PANEL, panel, border_radius=12)
        pygame.draw.rect(surf, C_TALENT, panel, 3, border_radius=12)

        draw_text(surf, "TALENT TREE", panel.centerx, panel.top + 10, 36,
                  C_TALENT_ON, "center", True)
        pts = self.talents.points
        draw_text(surf, f"{pts} POINT{'S' if pts != 1 else ''} UNSPENT",
                  panel.right - 24, panel.top + 16, 26,
                  C_GOLD if pts else C_DIM, "right", True)
        draw_text(surf, f"spent {self.talents.earned - pts}/{self.talents.earned}",
                  panel.left + 24, panel.top + 20, 20, C_DIM)

        cols = len(TALENT_BRANCHES)
        cw = (panel.w - 40) // cols
        top = panel.top + 54
        mouse = self.mouse_pos
        hover = None
        for ci, (branch, label) in enumerate(TALENT_BRANCHES):
            bx = panel.left + 20 + ci * cw
            colour = BRANCH_COLORS[branch]
            invested = self.talents.branch_points(branch)
            draw_text(surf, label, bx + cw // 2, top, 21, colour, "center", True)
            draw_text(surf, f"{invested} invested", bx + cw // 2, top + 22, 15,
                      C_DIM, "center")
            nodes = [t for t in TALENTS if t.branch == branch]
            ny = top + 44
            prev_centre = None
            for t in nodes:
                nh = 62
                r = pygame.Rect(bx + 8, ny, cw - 20, nh)
                t.rect = r
                rank = self.talents.rank(t.key)
                unlocked = self.talents.unlocked(t)
                maxed = rank >= t.max_rank
                buyable = self.talents.can_buy(t)
                if prev_centre is not None:
                    pygame.draw.line(surf, shade(colour, 0.5 if not unlocked else 1.0),
                                     prev_centre, (r.centerx, r.top), 2)
                prev_centre = (r.centerx, r.bottom)

                if not unlocked:
                    bg, edge = (24, 26, 36), (62, 66, 80)
                elif maxed:
                    bg, edge = (34, 46, 40), C_GREEN
                elif buyable:
                    bg = (56, 48, 74) if r.collidepoint(mouse) else (40, 38, 60)
                    edge = colour
                else:
                    bg, edge = (30, 32, 46), shade(colour, 0.6)
                pygame.draw.rect(surf, bg, r, border_radius=7)
                pygame.draw.rect(surf, edge, r, 2, border_radius=7)

                name_col = C_WHITE if unlocked else (110, 114, 128)
                draw_text(surf, t.name, r.centerx, r.top + 5, 18, name_col,
                          "center", True)
                # rank pips
                pip_w = 12
                total_w = t.max_rank * pip_w
                px = r.centerx - total_w // 2
                for k in range(t.max_rank):
                    pr = pygame.Rect(px + k * pip_w, r.top + 26, pip_w - 3, 7)
                    pygame.draw.rect(surf, colour if k < rank else (56, 58, 72),
                                     pr, border_radius=2)
                if not unlocked:
                    draw_text(surf, f"needs {t.tier} in branch", r.centerx,
                              r.bottom - 22, 15, (128, 132, 148), "center")
                elif maxed:
                    draw_text(surf, "MAXED", r.centerx, r.bottom - 22, 16,
                              C_GREEN, "center", True)
                else:
                    draw_text(surf, f"rank {rank}/{t.max_rank}   1 pt",
                              r.centerx, r.bottom - 22, 15,
                              C_GOLD if buyable else C_DIM, "center")
                if r.collidepoint(mouse):
                    hover = t
                ny += nh + 8

        # tooltip for whatever the cursor is over
        if hover is not None:
            rank = self.talents.rank(hover.key)
            shown = max(1, rank)
            body = hover.desc.format(v=hover.per_rank * shown)
            note = ("current" if rank else "at rank 1")
            lines = wrap_text(f"{body}  ({note})", 18, 620)
            th = 26 + len(lines) * 20
            tip = pygame.Rect(panel.centerx - 330, panel.bottom - th - 46, 660, th)
            pygame.draw.rect(surf, (18, 20, 32), tip, border_radius=8)
            pygame.draw.rect(surf, BRANCH_COLORS[hover.branch], tip, 2,
                             border_radius=8)
            ty = tip.top + 6
            for ln in lines:
                draw_text(surf, ln, tip.centerx, ty, 18, C_WHITE, "center")
                ty += 20

        bw, bh = 300, 44
        self.talent_back_btn = pygame.Rect(panel.centerx - bw // 2,
                                           panel.bottom - bh - 8, bw, bh)
        hot = self.talent_back_btn.collidepoint(mouse)
        pygame.draw.rect(surf, (58, 118, 92) if hot else (42, 92, 72),
                         self.talent_back_btn, border_radius=9)
        pygame.draw.rect(surf, C_GREEN, self.talent_back_btn, 3, border_radius=9)
        draw_text(surf, "BACK   [T / ESC]", self.talent_back_btn.centerx,
                  self.talent_back_btn.y + 11, 24, C_WHITE, "center", True)

    def draw_shop(self, surf):
        veil = pygame.Surface((WIDTH, HEIGHT), pygame.SRCALPHA)
        veil.fill((6, 8, 16, 195))
        surf.blit(veil, (0, 0))

        panel = pygame.Rect(24, 34, WIDTH - 48, HEIGHT - 68)
        pygame.draw.rect(surf, C_PANEL, panel, border_radius=12)
        pygame.draw.rect(surf, C_PANEL_EDGE, panel, 3, border_radius=12)

        draw_text(surf, "ARMOURY", panel.centerx, panel.top + 12, 38,
                  C_GOLD, "center", True)
        draw_text(surf, f"Gold: {self.gold}", panel.right - 26, panel.top + 18,
                  30, C_GOLD, "right", True)
        if self.endless:
            sub = (f"Endless  -  tier {max(1, self.wave)}  -  "
                   f"{format_clock(self.play_time)}" if self.started
                   else "Endless Attackers  -  kit out before you start")
        else:
            sub = f"Next up: Wave {self.wave + 1}"
        draw_text(surf, sub, panel.left + 26, panel.top + 20, 23, C_DIM)

        if self.shop_msg and self.shop_msg_t > 0:
            draw_text(surf, self.shop_msg, panel.centerx, panel.top + 50, 21,
                      C_HILITE, "center")

        # cards, laid out on a two-row grid
        cols, cw, gap, ch, vgap = 6, 192, 12, 206, 12
        total = cols * cw + (cols - 1) * gap
        x0 = panel.centerx - total // 2
        cy = panel.top + 76
        mouse = self.mouse_pos
        for item in self.shop_items:
            item.discount = self.talents.shop_discount
        for i, item in enumerate(self.shop_items):
            col, row = i % cols, i // cols
            r = pygame.Rect(x0 + col * (cw + gap), cy + row * (ch + vgap),
                            cw, ch)
            item.rect = r
            cost = item.cost
            available = item.avail_fn()
            afford = self.gold >= cost and available
            hover = r.collidepoint(mouse)
            bgc = (40, 44, 62) if afford else (32, 32, 40)
            if hover and afford:
                bgc = (56, 62, 86)
            pygame.draw.rect(surf, bgc, r, border_radius=10)
            pygame.draw.rect(surf, item.color if afford else (70, 70, 80),
                             r, 3, border_radius=10)

            pygame.draw.rect(surf, item.color, (r.x + 10, r.y + 8, r.w - 20, 5),
                             border_radius=3)
            if i < 10:
                draw_text(surf, f"[{(i + 1) % 10}]", r.x + 10, r.y + 17, 17, C_DIM)
            draw_text(surf, item.name, r.centerx, r.y + 30, 22,
                      C_WHITE if afford else (130, 130, 140), "center", True)

            self._draw_shop_icon(surf, item.key, r.centerx, r.y + 74, item.color)

            tower_cls = TOWER_FOR_KEY.get(item.key)
            if tower_cls is not None and tower_cls.COUNTER_TAG:
                draw_text(surf, tower_cls.COUNTER_TAG, r.centerx, r.y + 96, 16,
                          (255, 206, 120), "center", True)

            ty = r.y + 112
            # clipped so long copy can never collide with the status/price
            for ln in wrap_text(item.desc, 15, r.w - 18)[:3]:
                draw_text(surf, ln, r.centerx, ty, 15, C_DIM, "center")
                ty += 17

            draw_text(surf, item.status_fn(), r.centerx, r.bottom - 46, 16,
                      (170, 205, 235), "center")
            if not available:
                draw_text(surf, "MAXED", r.centerx, r.bottom - 26, 24,
                          (140, 200, 150), "center", True)
            else:
                price_col = C_GOLD if afford else (140, 120, 80)
                draw_text(surf, f"{cost} G", r.centerx, r.bottom - 28, 26,
                          price_col, "center", True)

        # start button
        bw, bh = 360, 54
        self.start_btn = pygame.Rect(panel.centerx - bw // 2,
                                     panel.bottom - bh - 14, bw, bh)
        # --- talent tree door, alongside the armoury ---
        tb = pygame.Rect(panel.right - 250, panel.bottom - bh - 14, 226, bh)
        self.talent_btn = tb
        pts = self.talents.points
        hot = tb.collidepoint(mouse)
        pygame.draw.rect(surf, (62, 50, 88) if hot else (44, 38, 66), tb,
                         border_radius=9)
        pygame.draw.rect(surf, C_TALENT, tb, 3, border_radius=9)
        draw_text(surf, "TALENTS  [T]", tb.centerx, tb.y + 8, 23,
                  C_TALENT_ON, "center", True)
        draw_text(surf, f"{pts} point{'s' if pts != 1 else ''} to spend",
                  tb.centerx, tb.y + 30, 16,
                  C_GOLD if pts else C_DIM, "center")
        hov = self.start_btn.collidepoint(mouse)
        pygame.draw.rect(surf, (58, 118, 92) if hov else (42, 92, 72),
                         self.start_btn, border_radius=10)
        pygame.draw.rect(surf, C_GREEN, self.start_btn, 3, border_radius=10)
        if self.endless:
            label = "RESUME   [SPACE]" if self.realtime_shop else \
                "BEGIN THE ASSAULT   [SPACE]"
        else:
            label = f"START WAVE {self.wave + 1}   [SPACE]"
        draw_text(surf, label, self.start_btn.centerx,
                  self.start_btn.y + 13, 28, C_WHITE, "center", True)

        nxt = None if self.endless else boss_for_wave(self.wave + 1)
        if nxt is not None:
            draw_text(surf, f"WARNING: {nxt.NAME} arrives next wave!",
                      panel.centerx, panel.bottom - bh - 60, 22,
                      (255, 130, 110), "center", True)
            if nxt.HINT:
                draw_text(surf, nxt.HINT, panel.centerx,
                          panel.bottom - bh - 40, 18, (255, 190, 150), "center")

    def _draw_shop_icon(self, surf, key, cx, cy, color):
        if key == "bowman":
            pygame.draw.circle(surf, (226, 194, 158), (cx, cy - 18), 8)
            pygame.draw.rect(surf, color, (cx - 8, cy - 8, 16, 22),
                             border_radius=4)
            pygame.draw.arc(surf, (206, 176, 110),
                            pygame.Rect(cx + 6, cy - 22, 24, 44), -1.2, 1.2, 3)
            pygame.draw.line(surf, (240, 240, 230), (cx + 8, cy),
                             (cx + 34, cy), 2)
        elif key == "ballista":
            pygame.draw.rect(surf, (104, 82, 58), (cx - 22, cy + 8, 44, 12),
                             border_radius=3)
            pygame.draw.line(surf, (150, 120, 80), (cx - 4, cy - 16),
                             (cx - 4, cy + 12), 4)
            pygame.draw.line(surf, color, (cx - 16, cy - 2), (cx + 26, cy - 2), 5)
            pygame.draw.polygon(surf, (230, 230, 220),
                                [(cx + 26, cy - 8), (cx + 38, cy - 2),
                                 (cx + 26, cy + 4)])
        elif key == "cannon":
            pygame.draw.rect(surf, (72, 76, 88), (cx - 20, cy + 6, 40, 14),
                             border_radius=4)
            pygame.draw.circle(surf, (40, 42, 50), (cx - 10, cy + 22), 8)
            pygame.draw.circle(surf, (40, 42, 50), (cx + 12, cy + 22), 6)
            pygame.draw.line(surf, color, (cx - 14, cy), (cx + 22, cy - 12), 12)
            pygame.draw.circle(surf, (30, 30, 36), (cx + 26, cy - 14), 7)
        elif key == "wall":
            for i in range(3):
                pygame.draw.rect(surf, shade(color, 0.9 + i * 0.06),
                                 (cx - 30 + i * 21, cy - 6, 19, 26))
                pygame.draw.rect(surf, (60, 62, 74),
                                 (cx - 30 + i * 21, cy - 6, 19, 26), 2)
            for i in range(4):
                pygame.draw.rect(surf, color, (cx - 30 + i * 16, cy - 20, 11, 14))
        elif key == "grab":
            pygame.draw.circle(surf, color, (cx, cy - 6), 13, 3)
            for a in (-2.4, -0.7, 0.9):
                pygame.draw.line(surf, color, (cx, cy - 6),
                                 (cx + math.cos(a) * 20, cy - 6 + math.sin(a) * 20), 4)
            pygame.draw.rect(surf, shade(color, 0.7), (cx - 9, cy + 12, 18, 8),
                             border_radius=2)
        elif key == "multi":
            for dx, dy in ((-16, 2), (0, -8), (16, 4)):
                pygame.draw.circle(surf, color, (cx + dx, cy + dy), 7)
                pygame.draw.circle(surf, shade(color, 0.6), (cx + dx, cy + dy), 7, 2)
            pygame.draw.arc(surf, (240, 230, 255),
                            pygame.Rect(cx - 24, cy - 20, 48, 34), 0.3, 2.9, 2)
        elif key == "outpost":
            pygame.draw.rect(surf, color, (cx - 16, cy - 10, 32, 28))
            pygame.draw.rect(surf, shade(color, 0.6), (cx - 16, cy - 10, 32, 28), 2)
            for i in range(3):
                pygame.draw.rect(surf, shade(color, 1.15),
                                 (cx - 16 + i * 12, cy - 18, 8, 8))
            pygame.draw.polygon(surf, (60, 66, 80),
                                [(cx - 30, cy + 20), (cx + 30, cy + 20),
                                 (cx + 16, cy + 2), (cx - 16, cy + 2)])
        elif key == "barricade":
            pygame.draw.rect(surf, color, (cx - 9, cy - 18, 18, 38))
            pygame.draw.rect(surf, shade(color, 0.6), (cx - 9, cy - 18, 18, 38), 2)
            for i in range(3):
                pygame.draw.line(surf, shade(color, 0.75),
                                 (cx - 7, cy - 10 + i * 12), (cx + 7, cy - 10 + i * 12), 2)
            for s_ in (-1, 1):
                pygame.draw.line(surf, shade(color, 0.8), (cx, cy - 14),
                                 (cx + s_ * 20, cy + 20), 3)
        elif key == "spikes":
            pygame.draw.rect(surf, (110, 114, 128), (cx - 22, cy - 18, 14, 40))
            for i in range(4):
                ty = cy - 14 + i * 11
                pygame.draw.polygon(surf, color,
                                    [(cx - 8, ty - 4), (cx + 20, ty), (cx - 8, ty + 4)])
        elif key == "bounce":
            pygame.draw.line(surf, (86, 92, 108), (cx - 34, cy + 22),
                             (cx + 34, cy + 22), 3)
            for i, (bx, by, rr) in enumerate([(-24, 6, 9), (-2, -8, 8),
                                              (18, 2, 7), (32, 12, 5)]):
                pygame.draw.circle(surf, color, (cx + bx, cy + by), rr)
                pygame.draw.circle(surf, shade(color, 0.6),
                                   (cx + bx, cy + by), rr, 2)
                if i < 3:
                    pygame.draw.arc(surf, shade(color, 0.75),
                                    pygame.Rect(cx + bx, cy + by - 4, 22, 26),
                                    0.4, 2.6, 2)
        elif key == "repair":
            pygame.draw.rect(surf, color, (cx - 6, cy - 20, 12, 40),
                             border_radius=3)
            pygame.draw.rect(surf, color, (cx - 20, cy - 6, 40, 12),
                             border_radius=3)
            pygame.draw.rect(surf, shade(color, 0.6), (cx - 6, cy - 20, 12, 40),
                             2, border_radius=3)

    # -- main loop -------------------------------------------------------
    def run(self):
        pygame.display.set_caption(TITLE)
        while self.running:
            dt = min(0.05, self.clock.tick(FPS) / 1000.0)
            for ev in pygame.event.get():
                self.handle_event(ev)
            self.mouse_pos = pygame.mouse.get_pos()
            # if the button-up was swallowed (focus loss, alt-tab), drop the mob
            if ((self.grabbed is not None or self.stripping is not None
                    or self.held_item is not None or self.charging is not None
                    or self.smacking is not None)
                    and not pygame.mouse.get_pressed()[0]):
                self.release_grab()
            self.update(dt)
            self.draw()
            pygame.display.flip()


# ------------------------------------------------------------------------------
# Entry points
# ------------------------------------------------------------------------------

def init_pygame():
    """Init only what we need -- skipping the mixer keeps the game happy on
    machines with no sound device."""
    pygame.display.init()
    pygame.font.init()


def main():
    init_pygame()
    pygame.display.set_caption(TITLE)
    screen = pygame.display.set_mode((WIDTH, HEIGHT))
    game = Game(screen)
    try:
        game.run()
    finally:
        pygame.quit()


def _ui_smoke(g):
    """Drive every screen through real pygame events, so the menus, the shop
    buttons, pause and the restart path are all exercised too."""
    def ev(**kw):
        g.handle_event(pygame.event.Event(kw.pop("type"), **kw))

    g.reset(MODE_CLASSIC)
    assert g.state == Game.MENU
    g.draw()                      # lays out the mode buttons
    ev(type=pygame.MOUSEBUTTONDOWN, button=1,
       pos=g.mode_buttons[MODE_CLASSIC].center)
    assert g.state == Game.SHOP, "picking a mode should open the armoury"
    assert g.mode == MODE_CLASSIC
    g.draw()

    g.gold = 5000
    for i, item in enumerate(g.shop_items):          # click every card
        ev(type=pygame.MOUSEBUTTONDOWN, button=1, pos=item.rect.center)
        g.draw()
    for k in (pygame.K_1, pygame.K_2, pygame.K_3, pygame.K_4, pygame.K_5,
              pygame.K_6, pygame.K_7, pygame.K_8, pygame.K_9, pygame.K_0):
        ev(type=pygame.KEYDOWN, key=k)               # and every hotkey
    g.draw()

    ev(type=pygame.MOUSEBUTTONDOWN, button=1, pos=g.start_btn.center)
    assert g.state == Game.PLAYING and g.wave == 1
    for _ in range(120):
        g.update(1 / 60.0)
    g.draw()

    ev(type=pygame.KEYDOWN, key=pygame.K_p)
    assert g.state == Game.PAUSED
    g.draw()
    ev(type=pygame.KEYDOWN, key=pygame.K_ESCAPE)
    assert g.state == Game.PLAYING

    # grab-and-throw through real mouse events
    tgt = next((e for e in g.enemies if e.grabbable), None)
    if tgt is not None:
        ev(type=pygame.MOUSEBUTTONDOWN, button=1, pos=(int(tgt.x), int(tgt.y)))
        for step in range(6):
            ev(type=pygame.MOUSEMOTION, pos=(int(tgt.x) + step * 40,
                                             max(80, int(tgt.y) - step * 30)))
            g.update(1 / 60.0)
        ev(type=pygame.MOUSEBUTTONUP, button=1, pos=(int(g.mouse_pos[0]),
                                                     int(g.mouse_pos[1])))
        assert g.grabbed is None

    # ---------- every modal panel must contain its own text ----------
    def _panel_fits(paragraphs, w):
        paragraphs = [p if isinstance(p, tuple) else (p, 22, C_WHITE, False)
                      for p in paragraphs]
        lines, rect, y, _scale = Game.panel_layout(paragraphs, w)
        inner = w - Game.PANEL_PAD * 2
        widest = 0
        for text, size, _c, bold, lh in lines:
            if text:
                widest = max(widest, font(size, bold).size(text)[0])
            y += lh
        return (y <= rect.bottom and widest <= inner
                and rect.top >= 0 and rect.bottom <= HEIGHT)

    assert _panel_fits(list(MENU_PARAGRAPHS), 780), \
        "the start screen must not overflow its panel"
    assert _panel_fits(["Press P or ESC to resume."], 620)
    assert _panel_fits([
        f"FINAL SCORE   {9876543:,}",
        "You survived 129 full waves (fell on wave 130).",
        "Enemies slain: 99999",
        "Best single fling: 123,456   Best combo: x12.75",
        "Damage dealt by throwing: 8120433",
        "Armour plates torn off: 1140",
        "Final walls: Runed Obsidian",
        "",
        ("Press R or click to play again.", 24, C_GOLD, True),
    ], 760), "the defeat screen must not overflow either"

    # the wrapper must reflow to any width, and never exceed it
    for w in (1000, 780, 560, 420, 320):
        assert _panel_fits(list(MENU_PARAGRAPHS), w), \
            f"menu copy must re-wrap cleanly at width {w}"
    narrow = len(wrap_text(MENU_PARAGRAPHS[2][0], 20, 300))
    wide = len(wrap_text(MENU_PARAGRAPHS[2][0], 20, 900))
    assert narrow > wide > 0, "wrapping must actually respond to the width"
    # a word too long for the box is broken rather than left hanging out
    for line in wrap_text("X" * 90, 20, 300):
        assert font(20).size(line)[0] <= 300, \
            "an oversized word must be split by character"
    # line height has to track the font size or lines overlap
    assert line_height(16) < line_height(24) < line_height(44)

    g.castle.take_damage(g.castle.max_hp * 2)        # force the defeat screen
    assert g.state == Game.GAMEOVER
    g.draw()
    ev(type=pygame.KEYDOWN, key=pygame.K_r)
    assert g.state == Game.MENU, "R should return to the menu"
    # --- strategic counter table must hold ---
    bow, bal, can = Bowman(g, 200, 350), Ballista(g, 200, 350), Cannon(g, 200, 350)
    air, heavy = Gargoyle(g, 5), SiegeRam(g, 5)
    ground = FootSoldier(g, 5)
    assert abs(bal.damage_vs(air) / bal.damage_vs(ground) - 3.0) < 1e-6, \
        "Ballista must deal +200% to flyers"
    assert abs(can.damage_vs(heavy) / can.damage_vs(ground) - 3.0) < 1e-6, \
        "Cannon must deal +200% to heavy ground units"
    assert bal.damage_vs(heavy) == bal.damage_vs(ground)
    assert can.damage_vs(air) == can.damage_vs(ground)
    assert bow.AIR_RANGE_MULT > 1.0 and bal.HITS_AIR

    # a target straight overhead must be reachable where a level one is not
    class _Probe:
        flying, HEAVY = True, False
        def __init__(self, x, y): self.x, self.y = x, y
    mx, my = bow.muzzle
    assert bow.reach_to(_Probe(mx, my - bow.range * 1.3)) <= bow.range
    assert bow.reach_to(_Probe(mx + bow.range * 1.3, my)) > bow.range

    # --- stripping: plates come off, armour falls, it slows, it softens ---
    ram = SiegeRam(g, 5)
    ram.x, ram.y = 800, ram.ground_y
    g.enemies.append(ram)
    g.state = Game.PLAYING
    before = (ram.armor, ram.speed, ram.vulnerable)
    assert not ram.grabbable and ram.strippable
    g.mouse_pos = (int(ram.x), int(ram.y))
    g.try_grab(g.mouse_pos)
    assert g.stripping is ram and g.grabbed is None, "drag on a tank must strip"
    for step in range(600):
        g.mouse_pos = (int(ram.x) + (70 if step % 2 else -70), int(ram.y))
        g.update_grab(1 / 60.0)
        if ram.layers == 0:
            break
    assert ram.layers == 0, "dragging must eventually strip every plate"
    assert ram.armor < before[0] and ram.speed < before[1] \
        and ram.vulnerable > before[2], "stripping must reduce armour+speed"
    assert not ram.strippable, "a fully stripped tank has nothing left to tear"
    g.release_grab()
    assert g.stripping is None

    for cls in (TrollKing, Dragon, LichLord):
        b = cls(g, 15)
        assert not b.grabbable and not b.strippable, \
            f"{cls.NAME} must resist both throwing and stripping"

    print("selftest: counters OK (Ballista +200% air, Cannon +200% heavy, "
          "Bowman air-range buff)")
    print("selftest: stripping OK (3 plates -> armour "
          f"{before[0]:.2f}->{ram.armor:.2f}, speed {before[1]:.0f}->{ram.speed:.0f}"
          f", damage taken x{ram.vulnerable:.2f}); bosses immune")
    # --- boss disruption: crown ---
    g.reset(); g.state = Game.PLAYING; g.wave = 5; g.wave_active = True
    troll = TrollKing(g, 5); troll.x = 700; troll.y = troll.ground_y
    g.enemies.append(troll)
    cr = troll.regalia_rect()
    assert cr is not None and troll.has_crown
    g.mouse_pos = cr.center
    g.try_grab(cr.center)
    assert g.held_item is not None and g.held_item.kind == "crown"
    assert not troll.has_crown, "grabbing the crown must remove it"
    for k in range(6):
        g.mouse_pos = (cr.center[0] - k * 80, cr.center[1] - k * 25)
        g.update_grab(1 / 60.0)
    g.release_grab()
    assert g.held_item is None and troll.crown_item.state == "flying"
    troll.x = CASTLE_FRONT + 60          # parked at the wall, would be smashing
    hp_before = g.castle.hp
    saw_retrieve = False
    for _ in range(900):
        g.update(1 / 60.0)
        if troll.state == "retrieve":
            saw_retrieve = True
        if troll.has_crown:
            break
    assert saw_retrieve, "an uncrowned Troll King must enter Retrieve Crown"
    assert g.castle.hp == hp_before, "he must not attack while uncrowned"
    assert troll.has_crown, "he must eventually pick his crown back up"

    # regalia must not be re-stealable instantly, or a boss is stun-locked
    assert troll.regalia_cd > 0 and troll.regalia_rect() is None, \
        "a recovered crown must be guarded for a while"
    first_guard = troll.regalia_cd
    troll.regalia_taken += 1
    troll.guard_regalia()
    assert troll.regalia_cd > first_guard, \
        "each theft must make the next guard window longer"

    # --- boss disruption: staff ---
    g.reset(); g.state = Game.PLAYING; g.wave = 15; g.wave_active = True
    lich = LichLord(g, 15); lich.x = lich.standoff_x; lich.y = lich.ground_y
    g.enemies.append(lich)
    for _ in range(60):
        g.update(1 / 60.0)
    sr = lich.regalia_rect()
    assert sr is not None and lich.has_staff
    g.mouse_pos = sr.center
    g.try_grab(sr.center)
    for k in range(5):
        g.mouse_pos = (sr.center[0] + k * 90, sr.center[1] - k * 30)
        g.update_grab(1 / 60.0)
    g.release_grab()
    assert not lich.has_staff and abs(lich.disarm - STAFF_DISARM_TIME) < 0.2
    hp_before, n_before = g.castle.hp, len(g.enemies)
    for _ in range(int(STAFF_DISARM_TIME * 60) - 30):
        g.update(1 / 60.0)
    assert g.castle.hp == hp_before, "a disarmed Lich must not attack"
    assert len(g.enemies) <= n_before, "a disarmed Lich must not summon"
    for _ in range(120):
        g.update(1 / 60.0)
    assert lich.has_staff, "he must recover his staff after the stun"
    assert lich.regalia_cd > 0 and lich.regalia_rect() is None, \
        "a recovered staff must be guarded too"

    # --- gold multiplier rises with the crowd ---
    g.reset(); g.state = Game.PLAYING
    g.enemies = []
    assert abs(g.gold_multiplier - 1.0) < 1e-9
    g.enemies = [Scout(g, 1) for _ in range(40)]
    crowded = g.gold_multiplier
    assert crowded > 1.5 and crowded <= POP_GOLD_CAP + 1e-9, \
        "a packed screen must pay substantially more"

    # --- score: further/longer pays more, collisions multiply it ---
    def _fling(vx, vy, bystanders=0, lvl=0):
        gg = Game(g.screen); gg.state = Game.PLAYING
        gg.wave, gg.wave_active, gg.bounce_level = 1, True, lvl
        m = ShieldBearer(gg, 1); m.depth = 0; m.x = 1200; m.y = m.ground_y
        m.max_hp *= 60; m.hp = m.max_hp
        gg.enemies.append(m)
        for i in range(bystanders):
            o = FootSoldier(gg, 1); o.depth = 0; o.x = 700 - i * 30
            o.y = o.ground_y; o.max_hp *= 60; o.hp = o.max_hp
            gg.enemies.append(o)
        m.on_grab(); m.on_release(vx, vy)
        for _ in range(1200):
            gg.update(1 / 60.0)
            if not m.fling_active:
                break
        return gg.score, m.fling_hits, m.bounce_count, m.stagger

    small, _, _, _ = _fling(220, -260)
    big, _, _, _ = _fling(2400, -2300)
    assert big > small * 3, "a longer fling must score far more"
    solo, h0, _, _ = _fling(-1900, -900)
    combo, h1, _, _ = _fling(-1900, -900, bystanders=8)
    assert h1 > h0 == 0 and combo > solo, "mid-air collisions must combo"

    # --- bounce upgrade: more bounces, more damage, longer stun ---
    _, _, b0, st0 = _fling(500, -800, lvl=0)
    _, _, b5, st5 = _fling(500, -800, lvl=BOUNCE_MAX_LEVEL)
    assert b5 > b0 and st5 > st0, "Bounce must add rebounds and recovery time"
    assert b0 == 1, "at Bounce Lv.0 a mob just hits the floor once"

    print(f"selftest: boss disruption OK (crown retrieval halts the Troll "
          f"King; staff flick disarms the Lich for {STAFF_DISARM_TIME:.0f}s; "
          f"regalia guarded {first_guard:.1f}s+ after recovery)")
    print(f"selftest: scoring OK (fling {small} -> {big} pts by distance, "
          f"x{1 + SCORE_COMBO_STEP * h1:.2f} combo on {h1} mobs struck)")
    print(f"selftest: bounce OK (Lv.0 {b0} bounce/{st0:.2f}s stun -> "
          f"Lv.{BOUNCE_MAX_LEVEL} {b5} bounces/{st5:.2f}s stun); "
          f"crowd gold x{crowded:.2f}")
    # ---------- Grab Strength gates heavy lifting ----------
    g.reset(); g.state = Game.PLAYING; g.wave = 8; g.wave_active = True
    ram = SiegeRam(g, 8); ram.x = 900; ram.y = ram.ground_y
    g.enemies.append(ram)
    g.grab_level = 0
    assert not ram.grabbable, "a tank must start unliftable"
    assert ram.armored and not ram.too_heavy, \
        "a fresh tank is blocked by its armour, not by weight"
    assert ShieldBearer(g, 8).grabbable, "regular mobs stay liftable at Lv.0"
    g.grab_level = GRAB_MAX_LEVEL
    assert not ram.grabbable, "armour still blocks the lift at max strength"
    while ram.layers:                       # strip it bare
        ram.strip_progress = 1.0
        ram.apply_strip(0.0)
    assert ram.grabbable, "stripped + max Grab Strength must lift a Siege Ram"
    g.grab_level = 0
    assert not ram.grabbable and ram.too_heavy, \
        "stripped but under-powered is the 'too heavy' case"

    # ---------- drag direction: away strips, toward shoves ----------
    g.enemies.clear()          # the stripped one would steal the click
    ram = SiegeRam(g, 8); ram.x = 900; ram.y = ram.ground_y   # fresh plating
    g.enemies.append(ram)
    g.mouse_pos = (int(ram.x), int(ram.y))
    g.try_grab(g.mouse_pos)
    assert g.stripping is ram and g.grabbed is None
    for _ in range(90):
        g.mouse_pos = (g.mouse_pos[0] + 40, int(ram.y))
        g.update_grab(1 / 60.0)
        if ram.layers == 0:
            break
    assert ram.layers == 0, "dragging away must strip the plating off"
    assert ram.shove == 0, "stripping must not shove it forward"
    for _ in range(30):
        g.mouse_pos = (g.mouse_pos[0] - 40, int(ram.y))
        g.update_grab(1 / 60.0)
    assert ram.shove > 0, "dragging castle-ward must add forward momentum"
    shoved_x = ram.x
    g.release_grab()
    for _ in range(60):
        g.update(1 / 60.0)
    assert shoved_x - ram.x > ram.speed, "the shove must outrun a normal walk"

    # ---------- the Necromancer betrayal ----------
    g.reset(MODE_CLASSIC)
    g.state = Game.PLAYING
    g.wave, g.wave_active = 8, False
    post = g.outpost
    necro = Necromancer(g, 8); necro.x = 1200; necro.y = necro.ground_y
    g.enemies.append(necro)
    assert necro.TRAPPABLE and necro.grabbable, \
        "a Necromancer must be light enough to lift"
    assert post.can_trap(necro) and not post.has_prisoner
    necro.on_grab()
    necro.x, necro.y = post.x - 260, post.y - 140
    necro.on_release(560, 40)
    for _ in range(240):
        g.update(1 / 60.0)
        if post.has_prisoner:
            break
    assert post.has_prisoner, "flinging him into the Outpost must trap him"
    assert necro not in g.enemies, "a prisoner leaves the horde"
    assert necro.trapped
    second = Necromancer(g, 8)
    assert not post.can_trap(second), "only one prisoner at a time"
    for _ in range(int(40 * 60)):
        g.update(1 / 60.0)
    allies_made = len(g.allies)
    assert allies_made > 0, "the prisoner must raise friendly skeletons"
    assert allies_made <= TRAP_SKELETON_CAP + g.talents.ally_cap_bonus
    assert all(a not in g.enemies for a in g.allies), \
        "allies must never sit in the enemy list"

    # they march the wrong way and then hold a forward line
    g.reset(MODE_CLASSIC); g.state = Game.PLAYING
    g.wave, g.wave_active = 8, False
    ally = g.make_ally(1015); ally.depth = 0; ally.y = ally.ground_y
    g.allies.append(ally)
    start_x = ally.x
    for _ in range(600):
        g.update(1 / 60.0)
    assert ally.x > start_x, "friendly skeletons march left-to-right"
    assert ally.x <= ALLY_HOLD_X + 1, "and hold the line rather than leaving"

    # and they actually block and damage the horde
    g.reset(MODE_CLASSIC); g.state = Game.PLAYING
    g.wave, g.wave_active = 8, False
    ally = g.make_ally(700); ally.depth = 0; ally.y = ally.ground_y
    g.allies.append(ally)
    foe = FootSoldier(g, 8); foe.depth = 0; foe.x = 760; foe.y = foe.ground_y
    foe.max_hp *= 8; foe.hp = foe.max_hp
    g.enemies.append(foe)
    foe_hp, castle_hp = foe.hp, g.castle.hp
    for _ in range(300):
        g.update(1 / 60.0)
    assert foe.hp < foe_hp, "an ally must damage what it meets"
    assert foe.x > ally.x, "and hold it out in front"
    assert g.castle.hp == castle_hp, "so nothing reaches the wall"

    # ---------- talent tree ----------
    g.reset(MODE_CLASSIC)
    t = g.talents
    assert len(TALENTS) >= 30 and len(TALENT_BRANCHES) == 6
    for branch, _label in TALENT_BRANCHES:
        nodes = [n for n in TALENTS if n.branch == branch]
        assert len(nodes) >= 5, f"{branch} needs depth"
        assert any(n.tier == 0 for n in nodes), f"{branch} needs an entry node"
        assert max(n.tier for n in nodes) >= 4, f"{branch} needs deep nodes"
    deep = TALENTS_BY_KEY["pierce"]
    assert not t.unlocked(deep), "deep nodes start locked"
    assert t.points == 0 and not t.can_buy(TALENTS_BY_KEY["rate"]), \
        "nothing is affordable with no points"
    t.award(20)
    for _ in range(4):
        assert t.buy(TALENTS_BY_KEY["rate"])
    assert t.buy(TALENTS_BY_KEY["power"])
    assert t.branch_points("offense") == 5 and t.unlocked(deep), \
        "investing in a branch must open its deeper nodes"
    assert t.points == 15, "each rank costs exactly one point"
    assert t.tower_rate < 1.0 and t.tower_damage > 1.0, \
        "offence talents must actually change the numbers"
    while t.buy(TALENTS_BY_KEY["rate"]):
        pass
    assert t.rank("rate") == TALENTS_BY_KEY["rate"].max_rank, "ranks cap"

    # Storm Winds only bites in a high headwind
    assert t.buy(TALENTS_BY_KEY["stormwinds"])
    probe = Scout(g, 5)
    g.wind = -WIND_MAX
    assert abs(g.enemy_slow(probe) - (1.0 - STORM_WIND_SLOW)) < 1e-6, \
        "Storm Winds must slow enemies in a headwind"
    g.wind = WIND_MAX
    assert g.enemy_slow(probe) == 1.0, "and do nothing in a tailwind"
    g.wind = 0.0

    # points are earned by playing
    g.reset(MODE_CLASSIC); g.choose_mode(MODE_CLASSIC); g.begin_play()
    before = g.talents.points
    g.spawn_queue.clear(); g.enemies.clear()
    for _ in range(180):
        g.update(1 / 60.0)
    assert g.talents.points == before + TALENT_POINTS_PER_WAVE, \
        "clearing a wave must pay a talent point"
    g.reset(MODE_ENDLESS); g.choose_mode(MODE_ENDLESS); g.begin_play()
    before = g.talents.points
    g.talent_seconds = TALENT_SECONDS_PER_POINT - 0.01
    g.update(1 / 30.0)
    assert g.talents.points == before + 1, "Endless pays a point a minute"

    # ---------- active skills ----------
    g.reset(MODE_CLASSIC); g.state = Game.PLAYING
    g.wave, g.wave_active = 15, False
    assert not g.skills.skills, "no skills before a boss falls"
    for cls, expect in zip((TrollKing, Dragon, LichLord),
                           ("lightning", "meteor", "tornado")):
        boss = cls(g, 15); g.enemies.append(boss); boss.die()
        assert g.skills.skills[-1].key == expect, \
            f"{cls.NAME} must unlock {expect}"
    assert len(g.skills.skills) == 3
    g.enemies.clear()

    def _mobs(n, x0=700, step=30, tough=3):
        out = []
        for i in range(n):
            mm = FootSoldier(g, 15); mm.depth = 0; mm.x = x0 + i * step
            mm.y = mm.ground_y; mm.max_hp *= tough; mm.hp = mm.max_hp
            g.enemies.append(mm); out.append(mm)
        return out

    # Lightning: targeted, and it vaporises a cluster
    bolt = g.skills.skills[0]
    victims = _mobs(6)
    hp0 = sum(v.hp for v in victims)
    assert g.skills.activate(bolt) and g.skills.aiming is bolt, \
        "a targeted skill must arm rather than fire instantly"
    g.skills.cast(bolt, 800, 500)
    assert sum(v.hp for v in victims) < hp0 * 0.5, "lightning must devastate"
    assert bolt.cooldown > 0 and not bolt.ready, "and go on cooldown"
    assert not g.skills.activate(bolt), "a cooling skill cannot be recast"
    g.enemies.clear()

    # Meteor: untargeted, lights the ground
    rain = g.skills.skills[1]
    victims = _mobs(8, 600, 60)
    hp0 = sum(v.hp for v in victims)
    zones = len(g.fire_zones)
    g.skills.cast(rain, 800, 400)
    assert len(g.fire_zones) > zones, "meteors must set the ground alight"
    for _ in range(180):
        g.update(1 / 60.0)
    assert sum(v.hp for v in victims) < hp0, "and burn what stands in it"
    g.enemies.clear(); g.fire_zones.clear()

    # Tornado: lifts, spins, hurls downfield
    twist = g.skills.skills[2]
    victims = _mobs(6, 700, 30, tough=200)
    xs = [v.x for v in victims]
    hp0 = sum(v.hp for v in victims)
    g.skills.cast(twist, 760, 400)
    lifted = 0
    peak = 0.0
    # track how far downfield each one gets: they bounce off the far
    # boundary and walk back, so the *final* position says nothing
    far = list(xs)
    for _ in range(int(11 * 60)):
        g.update(1 / 60.0)
        lifted = max(lifted, sum(1 for v in victims if v.state == "air"))
        peak = max(peak, max(GROUND_Y - v.y for v in victims))
        far = [max(f, v.x) for f, v in zip(far, victims)]
    assert lifted >= 4, "a tornado must pick mobs up"
    assert peak > 200, "and genuinely carry them, not just slow their fall"
    assert sum(1 for f, x in zip(far, xs) if f > x + 80) >= 4, \
        "and hurl them back downfield"
    assert sum(v.hp for v in victims) < hp0, "the landing must hurt"
    g.enemies.clear(); g.tornados.clear()

    # cooldowns tick, and the Arcane branch shortens them
    base = twist.full_cooldown(g)
    g.talents.award(10)
    for _ in range(5):
        g.talents.buy(TALENTS_BY_KEY["focus"])
    assert twist.full_cooldown(g) < base, "Arcane Focus must cut cooldowns"

    # ---------- talent screen opens and buys ----------
    g.reset(MODE_CLASSIC); g.choose_mode(MODE_CLASSIC)
    g.talents.award(5)
    assert g.state == Game.SHOP
    g.draw()
    ev(type=pygame.MOUSEBUTTONDOWN, button=1, pos=g.talent_btn.center)
    assert g.state == Game.TALENTS, "the shop must open the talent tree"
    g.draw()
    node = TALENTS_BY_KEY["rate"]
    spent = g.talents.points
    ev(type=pygame.MOUSEBUTTONDOWN, button=1, pos=node.rect.center)
    assert g.talents.rank("rate") == 1 and g.talents.points == spent - 1, \
        "clicking a node must invest a point"
    ev(type=pygame.KEYDOWN, key=pygame.K_t)
    assert g.state == Game.SHOP, "T must close the tree again"

    # ---------- game modes ----------
    for mode in (MODE_CLASSIC, MODE_ENDLESS):
        g.reset(MODE_CLASSIC)
        g.draw()
        assert set(g.mode_buttons) == {MODE_CLASSIC, MODE_ENDLESS}, \
            "the menu must offer both modes"
        ev(type=pygame.MOUSEBUTTONDOWN, button=1,
           pos=g.mode_buttons[mode].center)
        assert g.mode == mode and g.state == Game.SHOP

    # ---------- Endless: continuous spawning, no wave breaks ----------
    g.reset(MODE_ENDLESS); g.choose_mode(MODE_ENDLESS); g.begin_play()
    assert g.state == Game.PLAYING and g.endless and g.wave == 1
    for _ in range(int(20 * 60)):
        g.castle.hp = g.castle.max_hp
        g.update(1 / 60.0)
    assert g.state == Game.PLAYING, "an Endless run never breaks for the shop"
    assert len(g.enemies) > 0, "Endless must spawn continuously"
    assert abs(g.play_time - 20.0) < 0.5, "the run clock must track real time"
    tier_at_20 = g.wave
    assert tier_at_20 == 1 + int(20.0 / ENDLESS_TIER_SECONDS)
    g.play_time = ENDLESS_TIER_SECONDS * 6
    g.update(1 / 60.0)
    assert g.wave > tier_at_20, "tiers must climb with elapsed time"

    g.play_time = 0.0
    early = min(g.endless_spawn_gap() for _ in range(60))
    g.play_time = ENDLESS_SPAWN_RAMP
    late = max(g.endless_spawn_gap() for _ in range(60))
    assert late < early, "spawning must speed up over a long run"

    # ---------- Endless: boss timetable ----------
    g.reset(MODE_ENDLESS); g.choose_mode(MODE_ENDLESS); g.begin_play()
    seen = []
    for when, cls in ENDLESS_BOSS_SCHEDULE:
        g.play_time = when - 0.005      # one 1/30s step short of the mark
        g.enemies.clear()
        g.update(1 / 30.0)
        boss = next((e for e in g.enemies if e.IS_BOSS), None)
        assert boss is not None and isinstance(boss, cls), \
            f"{cls.NAME} must arrive at {when:.0f}s"
        seen.append((when, cls.NAME))
    g.enemies.clear()
    g.play_time = ENDLESS_BOSS_SCHEDULE[-1][0] + ENDLESS_BOSS_REPEAT + 0.1
    g.update(1 / 30.0)
    assert any(e.IS_BOSS for e in g.enemies), \
        "a repeat boss must follow the scripted three"

    # ---------- Endless: real-time shop freezes the world ----------
    g.reset(MODE_ENDLESS); g.choose_mode(MODE_ENDLESS); g.begin_play()
    for _ in range(120):
        g.update(1 / 60.0)
    g.draw()
    assert g.shop_btn.w > 0, "Endless must show a SHOP button in the HUD"
    ev(type=pygame.MOUSEBUTTONDOWN, button=1, pos=g.shop_btn.center)
    assert g.state == Game.SHOP and g.realtime_shop, \
        "the HUD button must open the shop mid-fight"
    frozen_t, frozen_n = g.play_time, len(g.enemies)
    frozen_x = [e.x for e in g.enemies]
    for _ in range(120):
        g.update(1 / 60.0)
    assert g.play_time == frozen_t, "the run clock must hold while shopping"
    assert len(g.enemies) == frozen_n, "and nothing new may spawn"
    assert [e.x for e in g.enemies] == frozen_x, "and nothing may move"
    g.draw()
    ev(type=pygame.MOUSEBUTTONDOWN, button=1, pos=g.start_btn.center)
    assert g.state == Game.PLAYING and not g.realtime_shop, \
        "RESUME must drop straight back into the fight"

    g.reset(MODE_CLASSIC); g.choose_mode(MODE_CLASSIC); g.begin_play()
    g.draw()
    assert g.shop_btn.w == 0, "Classic mode has no mid-fight shop button"
    assert not g.open_realtime_shop()

    # ---------- Classic still breaks between waves ----------
    g.reset(MODE_CLASSIC); g.choose_mode(MODE_CLASSIC); g.begin_play()
    assert g.wave == 1
    g.spawn_queue.clear(); g.enemies.clear()
    for _ in range(180):
        g.update(1 / 60.0)
    assert g.state == Game.SHOP, "Classic must open the shop when a wave clears"

    # ---------- assets: placeholder fallback, real files take over ----------
    assert ASSETS.get("scout") is None, "no artwork ships with the game"
    assert ASSETS["scout"].get_size() == ASSET_SPECS["scout"][:2], \
        "a missing asset must still yield a placeholder surface"
    assert not blit_asset(pygame.Surface((40, 40)), "scout",
                          pygame.Rect(0, 0, 20, 26)), \
        "blit_asset must report False so the hand-drawn version is used"
    fake = pygame.Surface((16, 16), pygame.SRCALPHA)
    fake.fill((255, 0, 255))
    ASSETS._images["scout"] = fake          # stand in for assets/scout.png
    ASSETS._scaled.clear()
    assert blit_asset(pygame.Surface((40, 40)), "scout",
                      pygame.Rect(0, 0, 20, 26)), \
        "supplied artwork must be picked up automatically"
    assert Scout(g, 1).blit_sprite(pygame.Surface((60, 60)))
    del ASSETS._images["scout"]
    ASSETS._scaled.clear()

    # ---------- BUG 1 regression: strict armour -> shove -> lift order -----
    def _tank(grab_lvl, stripped):
        gg = Game(g.screen); gg.state = Game.PLAYING
        gg.wave, gg.wave_active, gg.grab_level = 8, True, grab_lvl
        t = SiegeRam(gg, 8); t.x = 900; t.y = t.ground_y
        gg.enemies.append(t)
        if stripped:
            while t.layers:
                t.strip_progress = 1.0
                t.apply_strip(0.0)
        return gg, t

    # phase 1: armour on -- lifting is locked even at max Grab Strength
    gg, tank = _tank(GRAB_MAX_LEVEL, False)
    assert tank.armored and not tank.grabbable, \
        "an armoured tank must never be liftable, whatever the Grab Strength"
    assert not tank.shovable, "an armoured tank must not be shovable either"
    gg.mouse_pos = (int(tank.x), int(tank.y))
    gg.try_grab(gg.mouse_pos)
    assert gg.grabbed is None and gg.stripping is tank, \
        "clicking an armoured tank must start stripping, never a lift"
    for _ in range(30):          # haul castle-ward: must do nothing at all
        gg.mouse_pos = (gg.mouse_pos[0] - 40, int(tank.y))
        gg.update_grab(1 / 60.0)
    assert tank.shove == 0, "an armoured tank cannot be shoved forward"
    assert tank.layers == SiegeRam.ARMOR_LAYERS, "and is not stripped by it"
    layers_before = tank.layers
    for _ in range(80):          # haul away: strips, and keeps the session
        gg.mouse_pos = (gg.mouse_pos[0] + 40, int(tank.y))
        gg.update_grab(1 / 60.0)
        if tank.layers == 0:
            break
    assert tank.layers < layers_before and not tank.armored
    assert gg.stripping is tank, \
        "the drag must survive the armour coming off, mid-click"
    for _ in range(30):          # same click, now inward: shoves
        gg.mouse_pos = (gg.mouse_pos[0] - 40, int(tank.y))
        gg.update_grab(1 / 60.0)
    assert tank.shove > 0, "a stripped tank must shove"

    # phase 2: stripped but too weak -- shove yes, lift no
    gg, tank = _tank(0, True)
    assert not tank.armored and tank.shovable
    assert not tank.grabbable and tank.too_heavy
    gg.mouse_pos = (int(tank.x), int(tank.y))
    gg.try_grab(gg.mouse_pos)
    assert gg.grabbed is None and gg.stripping is tank

    # phase 3: stripped and strong enough -- lifts
    gg, tank = _tank(GRAB_MAX_LEVEL, True)
    assert tank.grabbable
    gg.mouse_pos = (int(tank.x), int(tank.y))
    gg.try_grab(gg.mouse_pos)
    assert gg.grabbed is tank and gg.stripping is None, \
        "a stripped tank must lift once Grab Strength allows"

    # ---------- BUG 2 regression: Lich halts at the outer barricade -------
    def _lich(wall):
        gg = Game(g.screen); gg.state = Game.PLAYING
        gg.wave, gg.wave_active = 15, True
        for _ in range(3):
            gg.barricade.buy()
        if not wall:
            gg.barricade.hp = 0.0
        lich = LichLord(gg, 15)
        lich.x, lich.y = SPAWN_X, lich.ground_y
        gg.enemies.append(lich)
        hits = {"wall": 0.0, "castle": 0.0}
        gg.castle.take_damage = lambda a: hits.__setitem__("castle",
                                                           hits["castle"] + a)
        real_bar = gg.barricade.take_damage
        def bar_spy(a):
            hits["wall"] += a
            if wall:
                gg.barricade.hp = gg.barricade.max_hp   # keep it standing
            else:
                real_bar(a)
        gg.barricade.take_damage = bar_spy
        halted = None
        for _ in range(60 * 60):
            gg.update(1 / 60.0)
            gg.enemies = [e for e in gg.enemies if e is lich]
            if halted is None and lich.state == "attack":
                halted = lich.x
        return gg, lich, halted, hits

    gw, lw, halted_w, hits_w = _lich(True)
    bar = gw.barricade
    assert halted_w is not None and halted_w > bar.x + bar.W / 2, \
        "the Lich Lord must stop in front of a standing barricade"
    assert abs(halted_w - lw.current_standoff()) < 2.0
    assert lw.current_standoff() > lw.standoff_x, \
        "a live wall must push his halt point further out"
    assert hits_w["wall"] > 0, "he must attack the barricade"
    assert hits_w["castle"] == 0, \
        "and must not touch the castle while the barricade stands"

    gn, ln, halted_n, hits_n = _lich(False)
    assert abs(ln.current_standoff() - ln.standoff_x) < 1e-6, \
        "with the wall gone he reverts to his normal stand-off"
    assert hits_n["castle"] > 0, "and only then attacks the castle"

    # a Necromancer shares the same think() shape -- make sure it still runs
    gg = Game(g.screen); gg.state = Game.PLAYING
    gg.wave, gg.wave_active = 8, True
    necro = Necromancer(gg, 8); necro.x = 700; necro.y = necro.ground_y
    gg.enemies.append(necro)
    for _ in range(300):
        gg.update(1 / 60.0)
    assert necro.alive and necro.state in ("walk", "attack")

    # ---------- Magnetic Gloves ----------
    g.reset(); g.state = Game.PLAYING
    g.multi_level = MULTI_MAX_LEVEL
    lead = Scout(g, 3); lead.x = 800; lead.y = lead.ground_y
    g.enemies.append(lead)
    for k in range(4):
        o = Scout(g, 3); o.x = 800 + (k - 2) * 32; o.y = lead.ground_y
        g.enemies.append(o)
    g.mouse_pos = (int(lead.x), int(lead.y))
    g.try_grab(g.mouse_pos)
    assert len(g.grabbed_extra) == MULTI_MAX_LEVEL, "gloves must hold extras"
    g.mouse_pos = (900, 200)
    for _ in range(4):
        g.update_grab(1 / 60.0)
    g.release_grab()
    assert sum(1 for e in g.enemies if e.state == "air") == MULTI_MAX_LEVEL + 1

    # ---------- Outpost: fires, and is untouchable ----------
    g.reset(); g.state = Game.PLAYING; g.wave = 8; g.wave_active = True
    for _ in range(OUTPOST_MAX_LEVEL):
        g.outpost.upgrade()
    assert g.outpost.is_turret and g.outpost.guns == OUTPOST_MAX_LEVEL
    victim = FootSoldier(g, 8); victim.x = 900; victim.y = victim.ground_y
    victim.max_hp *= 60; victim.hp = victim.max_hp
    g.enemies.append(victim)
    hp0 = victim.hp
    for _ in range(240):
        g.update(1 / 60.0)
    assert victim.hp < hp0, "a garrisoned outpost must shoot"
    assert not hasattr(g.outpost, "hp"), "the outpost has no health to lose"

    # ---------- Barricade blocks ground, not air ----------
    g.reset(); g.state = Game.PLAYING; g.wave = 8; g.wave_active = True
    ok, _msg = g.barricade.buy()
    assert ok and g.barricade.alive
    foot = FootSoldier(g, 8); foot.x = 900; foot.y = foot.ground_y
    flyer = Gargoyle(g, 8); flyer.x = 900
    g.enemies += [foot, flyer]
    bar_hp0 = g.barricade.hp
    for _ in range(600):
        g.update(1 / 60.0)
    assert g.barricade.hp < bar_hp0, "ground troops must attack it"
    assert foot.x > g.barricade.x, "ground troops must be held outside"
    assert flyer.x < g.barricade.x, "flyers must pass over it"

    # ---------- Spike Walls reflect ----------
    g.reset(); g.state = Game.PLAYING; g.wave = 10
    g.spike_level = 0
    m = FootSoldier(g, 10); m.max_hp *= 99; m.hp = m.max_hp
    h0 = m.hp; m.attack_castle()
    assert m.hp == h0, "no spikes, no reflected damage"
    g.spike_level = SPIKE_MAX_LEVEL
    h0 = m.hp; m.attack_castle()
    assert m.hp < h0, "spiked walls must bite back"

    # ---------- Manual overcharge ----------
    g.reset(); g.state = Game.PLAYING; g.wave = 8; g.wave_active = True
    g.gold = 9000
    for key in ("ballista", "cannon"):
        g.try_buy(next(i for i in g.shop_items if i.key == key))
    for cls in (Ballista, Cannon):
        gun = next(t for t in g.castle.towers if isinstance(t, cls))
        g.grab_cd = 0.0
        gun.cooldown = 99.0        # normal reload nowhere near ready
        gun.overcharge_cd = 0.0
        g.projectiles.clear()
        g.mouse_pos = gun.rect.center
        g.try_grab(g.mouse_pos)
        assert g.charging is gun, f"{cls.NAME} must be hand-aimable"
        g.mouse_pos = (gun.muzzle[0] + 220, gun.muzzle[1] + 120)
        assert g.overcharge_power() >= 0.99
        g.release_grab()
        assert len(g.projectiles) == 1, "overcharge must ignore the reload"
        shot = g.projectiles[0]
        assert abs(shot.damage / gun.damage - OVERCHARGE_DAMAGE) < 1e-6
        assert shot.vx < 0, "the shot flies opposite the draw-back"
        if gun.splash:
            assert shot.splash > gun.splash, "overcharge widens the blast"
        assert gun.overcharge_cd > 0, "it must lock out afterwards"
        g.grab_cd = 0.0
        g.mouse_pos = gun.rect.center
        g.try_grab(g.mouse_pos)
        assert g.charging is None, "cannot re-charge during the lockout"

    # ---------- Volatile detonation ----------
    g.reset(); g.state = Game.PLAYING; g.wave = 12; g.wave_active = True
    vol = Volatile(g, 12); vol.depth = 0; vol.x = 800; vol.y = vol.ground_y
    g.enemies.append(vol)
    near = FootSoldier(g, 12); near.depth = 0; near.x = 830
    near.y = near.ground_y; near.max_hp *= 30; near.hp = near.max_hp
    far = FootSoldier(g, 12); far.depth = 0
    far.x = 800 + Volatile.BLAST_RADIUS + 60
    far.y = far.ground_y; far.max_hp *= 30; far.hp = far.max_hp
    g.enemies += [near, far]
    n0, f0 = near.hp, far.hp
    vol.die()
    assert near.hp < n0, "the blast must hurt neighbours"
    assert far.hp == f0, "and must respect its radius"

    # ---------- Treasure Goblin ----------
    g.reset(); g.state = Game.PLAYING; g.wave = 8; g.wave_active = True
    gob = TreasureGoblin(g, 8)
    g.enemies.append(gob)
    # no wave bonus in the way: it would mask the goblin's own payout
    g.wave_active = False
    gx0, chp0, gold0 = gob.x, g.castle.hp, g.gold
    for _ in range(200):
        g.update(1 / 60.0)
    assert gob.x > gx0, "the goblin flees away from the castle"
    assert g.castle.hp == chp0, "it never attacks"
    for _ in range(900):         # it escapes by leaving the map, or on its timer
        g.update(1 / 60.0)
        if not gob.alive:
            break
    assert not gob.alive, "the goblin must eventually escape"
    assert g.gold == gold0, "escaping must pay nothing"
    g2 = Game(g.screen); g2.state = Game.PLAYING; g2.wave = 8
    gob2 = TreasureGoblin(g2, 8); g2.enemies.append(gob2)
    before = g2.gold
    gob2.die()
    assert g2.gold - before >= TreasureGoblin.GOLD, "killing one must pay well"

    # ---------- Endgame tiers ----------
    assert endgame_tier(10) == -1 and endgame_tier(16) == 0
    assert endgame_tier(26) == 1 and endgame_tier(40) == 2
    plain, tiers = Scout(g, 10), [Scout(g, w) for w in (16, 26, 36)]
    assert plain.tier_name == ""
    last_hp = plain.max_hp
    for t in tiers:
        assert t.tier_name and t.max_hp > last_hp and t.COLOR != Scout.COLOR
        last_hp = t.max_hp

    # ---------- Dragon claws ----------
    g.reset(); g.state = Game.PLAYING; g.wave = 10; g.wave_active = True
    drg = Dragon(g, 10); drg.x = drg.standoff_x; drg.y = drg.fly_y
    g.enemies.append(drg)
    for _ in range(120):
        g.update(1 / 60.0)
    drg.breathing = 1.0
    claw = drg.smack_rect()
    assert claw is not None
    g.mouse_pos = claw.center
    g.try_grab(claw.center)
    assert g.smacking is drg, "dragging the claws must start a smack"
    for k in range(60):
        g.mouse_pos = (claw.centerx + (60 if k % 2 else -60), claw.centery)
        g.update_grab(1 / 60.0)
        if drg.reel > 0:
            break
    assert drg.reel > 0, "enough battering must stagger the Dragon"
    assert drg.breathing == 0.0, "and cut off its fire breath"
    chp = g.castle.hp
    for _ in range(int(CLAW_STAGGER * 60) - 20):
        g.update(1 / 60.0)
    assert g.castle.hp == chp, "a reeling Dragon cannot attack"
    assert drg.regalia_cd > 0, "the claws are guarded afterwards"
    assert not drg.grabbable and not drg.strippable, "still boss-immune"

    # ---------- Wind ----------
    def _drift(w):
        gg = Game(g.screen); gg.state = Game.PLAYING
        gg.wave, gg.wave_active, gg.wind = 5, True, w
        mob = FootSoldier(gg, 5); mob.depth = 0; mob.x = 800
        mob.y = mob.ground_y; mob.max_hp *= 90; mob.hp = mob.max_hp
        gg.enemies.append(mob)
        mob.on_grab(); mob.on_release(0, -1000)
        for _ in range(240):
            gg.update(1 / 60.0)
            if mob.state != "air":
                break
        return mob.x - 800
    assert _drift(WIND_MAX) > 60 > -60 > _drift(-WIND_MAX), \
        "wind must push a vertical throw both ways"

    # ---------- Thunderstorm lightning ----------
    def _ceiling_throw(storm):
        gg = Game(g.screen); gg.state = Game.PLAYING
        gg.wave, gg.wave_active, gg.storm, gg.wind = 5, True, storm, 0.0
        mob = ShieldBearer(gg, 5); mob.depth = 0; mob.x = 800
        mob.y = mob.ground_y; mob.max_hp *= 40; mob.hp = mob.max_hp
        gg.enemies.append(mob)
        start = mob.hp
        mob.on_grab(); mob.on_release(0, -2400)
        for _ in range(300):
            gg.update(1 / 60.0)
            if mob.state != "air":
                break
        return start - mob.hp, mob.fling_peak
    zapped, peak = _ceiling_throw(True)
    calm, _ = _ceiling_throw(False)
    assert peak < STORM_CEILING, "the test throw must reach the ceiling"
    assert zapped > calm * 2, "a storm must punish mobs flung that high"

    # ---------- Challenge Horn ----------
    g.reset(); g.open_first_shop(); g.start_wave()
    for _ in range(120):
        g.update(1 / 60.0)
    pending = len(g.spawn_queue)
    assert pending > 0
    on_field = len(g.enemies)
    assert g.blow_horn()
    assert not g.spawn_queue and len(g.enemies) == on_field + pending, \
        "the horn must call the whole remaining wave in at once"
    assert g.horn_bonus > 0 and not g.blow_horn(), "and only work once"

    print("selftest: cursor OK (armour locks lift AND shove; strip -> shove -> "
          f"lift order enforced; gloves fling {MULTI_MAX_LEVEL + 1} at once)")
    print(f"selftest: Lich Lord halts at {halted_w:.0f} in front of the "
          f"barricade ({hits_w['wall']:.0f} dmg to the wall, 0 to the castle) "
          "and only advances once it falls")
    print("selftest: structures OK (outpost fires and cannot be attacked, "
          "barricade stops ground but not flyers, spikes reflect)")
    print(f"selftest: overcharge OK (x{OVERCHARGE_DAMAGE} damage, wider blast, "
          "fires mid-reload, then locks out)")
    print("selftest: endgame OK (Volatile blast, Goblin flees/pays, "
          "Bloodied/Frostbound/Voidtouched tiers)")
    print("selftest: world OK (Dragon claws stagger, wind drifts throws, "
          "storm lightning, Challenge Horn)")
    print(f"selftest: betrayal OK (a flung Necromancer is imprisoned, raises "
          f"up to {TRAP_SKELETON_CAP} allies that march right, hold the line "
          "and block the horde)")
    print(f"selftest: talents OK ({len(TALENTS)} nodes over "
          f"{len(TALENT_BRANCHES)} branches, tier gating, ranks cap, effects "
          "reach the numbers; Storm Winds only in a headwind)")
    print("selftest: skills OK (one slot per boss; lightning vaporises, "
          "meteors burn the ground, tornado lifts and hurls; cooldowns "
          "tick and Arcane Focus shortens them)")
    print(f"selftest: modes OK (Classic breaks for the shop; Endless spawns "
          f"continuously, tiers every {ENDLESS_TIER_SECONDS:.0f}s, bosses at "
          + ", ".join(f"{w:.0f}s {n}" for w, n in seen)
          + f", then every {ENDLESS_BOSS_REPEAT:.0f}s; its HUD shop button "
          "freezes the world)")
    print(f"selftest: assets OK ({ASSETS.report()}; missing ones fall back to "
          "the hand-drawn sprites, supplied files are used automatically)")
    _mlines, _mrect, _my, _ms = Game.panel_layout(list(MENU_PARAGRAPHS), 780)
    print(f"selftest: text layout OK (menu wraps to "
          f"{sum(1 for t, *_ in _mlines if t)} lines in a "
          f"{_mrect.w}x{_mrect.h} panel; all modals contain their text)")
    print("selftest: UI paths OK (menu, shop clicks + hotkeys, pause, "
          "throw, defeat, restart)")


def selftest(frames=32000):
    """
    Headless smoke test: plays itself for a while, exercising every mob, every
    boss, the shop, and the grab/throw physics, while rendering each frame.
    Used to prove the build is stable -- not part of normal play.
    """
    import os
    os.environ.setdefault("SDL_VIDEODRIVER", "dummy")
    init_pygame()
    screen = pygame.display.set_mode((WIDTH, HEIGHT))
    g = Game(screen)
    g.start_wave()
    dt = 1.0 / 60.0
    seen_types, held, hold_left = set(), None, 0

    for i in range(frames):
        if g.state == Game.SHOP:
            g.gold += 900                      # keep the shop exercised
            for item in g.shop_items:
                if g.gold >= item.cost:
                    g.try_buy(item)
            g.draw()
            g.start_wave()
            continue
        if g.state == Game.GAMEOVER:
            raise AssertionError(f"castle fell during selftest at wave {g.wave}")

        # keep the castle alive so we reach the late waves
        if g.castle.hp < g.castle.max_hp * 0.5:
            g.castle.hp = g.castle.max_hp

        # dismantle any heavy unit on the field, then go back to throwing
        if g.stripping is not None:
            g.mouse_pos = (int(g.stripping.x) + (70 if i % 2 else -70),
                           int(g.stripping.y))
            g.update(dt); g.draw()
            continue
        heavy = next((e for e in g.enemies if e.strippable), None)
        if heavy is not None and held is None and i % 7 == 0:
            g.mouse_pos = (int(heavy.x), int(heavy.y))
            g.try_grab(g.mouse_pos)

        # drive the grab/throw mechanic
        if held is None and i % 23 == 0:
            cands = [e for e in g.enemies if e.grabbable]
            if cands:
                held = random.choice(cands)
                g.mouse_pos = (int(held.x), int(held.y))
                g.try_grab(g.mouse_pos)
                held = g.grabbed
                hold_left = random.randint(6, 20)
        elif held is not None:
            hold_left -= 1
            g.mouse_pos = (int(clamp(g.mouse_pos[0] + random.uniform(-45, 45),
                                     300, WIDTH - 10)),
                           int(clamp(g.mouse_pos[1] - random.uniform(0, 40),
                                     60, GROUND_Y - 10)))
            if hold_left <= 0 or not held.alive:
                g.release_grab()
                held = None
        else:
            g.mouse_pos = (random.randint(300, WIDTH - 10),
                           random.randint(80, GROUND_Y))

        for e in g.enemies:
            seen_types.add(type(e).__name__)

        g.update(dt)
        g.draw()

    reached, kills, thrown = g.wave, g.stats_kills, int(g.stats_thrown_damage)
    _ui_smoke(g)          # note: this resets the game, so report stats first
    print(f"selftest: {frames} frames rendered, reached wave {reached}, "
          f"{kills} kills, {thrown} throw damage")
    print("selftest: unit types encountered ->",
          ", ".join(sorted(seen_types)))
    expected = {c.__name__ for (_, c, _) in UNLOCKS} | \
        {b.__name__ for b in BOSS_ROTATION}
    missing = expected - seen_types
    if missing:
        print("selftest: WARNING, never encountered:", ", ".join(sorted(missing)))
    else:
        extra = " (plus summoned Skeletons)" if "Skeleton" in seen_types else ""
        print(f"selftest: all 8 mob types and all 3 bosses appeared{extra}.")
    pygame.quit()
    return not missing


if __name__ == "__main__":
    if "--selftest" in sys.argv:
        n = 32000
        for a in sys.argv[1:]:
            if a.isdigit():
                n = int(a)
        selftest(n)
    else:
        main()

