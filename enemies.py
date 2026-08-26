"""
Castle Defense -- the whole hostile roster.

The Enemy base class with its throw/strip/shove physics, the eight mob
types plus Volatiles and Treasure Goblins, the three bosses with their
interactive disruption states, the endgame tier system, and the wave
composition rules.

"""

import math
import random

import pygame

from sprites import *  # noqa: F401,F403
from castle import Projectile

class DroppedItem:
    """
    A piece of boss equipment -- the Troll King's crown, the Lich Lord's staff
    -- that the player has torn loose.  It follows the cursor while held, flies
    with the throw, then lies on the ground until its owner recovers it.
    """

    def __init__(self, game, owner, kind, x, y):
        self.game = game
        self.owner = owner
        self.kind = kind              # "crown" | "staff"
        self.x, self.y = float(x), float(y)
        self.vx = self.vy = 0.0
        self.state = "held"           # held -> flying -> ground
        self.alive = True
        self.spin = 0.0
        self.bob = 0.0
        self.w, self.h = (44, 26) if kind == "crown" else (18, 60)

    @property
    def rect(self):
        return pygame.Rect(int(self.x - self.w / 2), int(self.y - self.h / 2),
                           self.w, self.h)

    @property
    def rest_y(self):
        return GROUND_Y - self.h / 2 + 2

    def kill(self):
        self.alive = False

    def throw(self, vx, vy):
        cap = REGALIA_MAX_THROW.get(self.kind, 1600.0)
        sp = math.hypot(vx, vy)
        if sp > cap:
            vx, vy = vx * cap / sp, vy * cap / sp
        self.vx, self.vy = vx, vy
        self.state = "flying"
        self.game.effects.burst(self.x, self.y, 10, (255, 226, 150),
                                speed=180, life=0.4)

    def update(self, dt):
        if self.state == "held":
            return
        self.bob += dt * 3.0
        if self.state == "flying":
            self.vy += GRAVITY * dt
            self.x += self.vx * dt
            self.y += self.vy * dt
            self.spin += self.vx * dt * 0.02
            # keep it on the battlefield, out of the castle
            left = CASTLE_FRONT + self.w
            right = WIDTH - self.w
            if self.x < left:
                self.x = left
                self.vx = abs(self.vx) * 0.4
            if self.x > right:
                self.x = right
                self.vx = -abs(self.vx) * 0.4
            if self.y >= self.rest_y:
                self.y = self.rest_y
                self.vy = -abs(self.vy) * 0.34
                self.vx *= 0.6
                self.game.effects.burst(self.x, self.y + self.h / 2, 8,
                                        C_DIRT, speed=140, life=0.35)
                if abs(self.vy) < 90:
                    self.vy = 0.0
                    self.state = "ground"

    def draw(self, surf):
        x, y = int(self.x), int(self.y)
        if self.state == "ground":
            y += int(math.sin(self.bob) * 2)
            glow = pygame.Surface((self.w * 3, self.h * 3), pygame.SRCALPHA)
            col = (255, 214, 110) if self.kind == "crown" else (186, 140, 255)
            pygame.draw.ellipse(glow, (*col, 46), glow.get_rect())
            surf.blit(glow, (x - self.w * 1.5, y - self.h * 1.5))

        if self.kind == "crown":
            pygame.draw.polygon(surf, C_GOLD, [
                (x - 20, y + 10), (x + 20, y + 10), (x + 16, y - 6),
                (x + 8, y + 4), (x, y - 10), (x - 8, y + 4), (x - 16, y - 6)])
            pygame.draw.polygon(surf, shade(C_GOLD, 0.6), [
                (x - 20, y + 10), (x + 20, y + 10), (x + 16, y - 6),
                (x + 8, y + 4), (x, y - 10), (x - 8, y + 4), (x - 16, y - 6)], 2)
            pygame.draw.circle(surf, (228, 84, 96), (x, y + 4), 3)
        else:
            a = self.spin if self.state != "ground" else -1.2
            dx, dy = math.cos(a) * 26, math.sin(a) * 26
            pygame.draw.line(surf, (76, 62, 96), (x - dx, y - dy),
                             (x + dx, y + dy), 5)
            glow = pygame.Surface((44, 44), pygame.SRCALPHA)
            pygame.draw.circle(glow, (170, 120, 255, 100), (22, 22), 20)
            surf.blit(glow, (x + dx - 22, y + dy - 22))
            pygame.draw.circle(surf, (196, 150, 255),
                               (int(x + dx), int(y + dy)), 9)
            pygame.draw.circle(surf, (240, 220, 255),
                               (int(x + dx), int(y + dy)), 5)


# ------------------------------------------------------------------------------
# Defensive towers
# ------------------------------------------------------------------------------


def endgame_tier(wave):
    """Index into ENDGAME_TIERS for this wave, or -1 for the normal game."""
    idx = -1
    for i, t in enumerate(ENDGAME_TIERS):
        if wave >= t[1]:
            idx = i
    return idx


def wave_scaling(wave):
    """(health, damage, speed) multipliers -- mobs keep getting nastier."""
    w = max(1, wave) - 1
    hp = 1.14 ** w
    dmg = 1.11 ** w
    spd = min(1.70, 1.0 + 0.026 * w)
    return hp, dmg, spd


# ------------------------------------------------------------------------------
# Enemies
# ------------------------------------------------------------------------------


class Enemy:
    _next_uid = 0

    """
    Base class for every hostile unit.  Behaviour is a small state machine:

        walk   -> marching toward the castle (or to a stand-off point)
        attack -> in contact with the castle, swinging on a timer
        grabbed-> held by the player's cursor
        air    -> flying through the air after a throw (takes fall damage)
    """
    NAME = "Enemy"
    COLOR = (200, 80, 80)
    W, H = 26, 34
    BASE_HP = 50.0
    BASE_SPEED = 70.0
    BASE_DAMAGE = 10.0
    ATTACK_RATE = 1.1
    GOLD = 10
    ARMOR = 0.0            # fraction of projectile damage ignored
    MASS = 1.0             # heavier = harder to fling, worse landings
    FLYING = False
    GRABBABLE = True
    SPRITE = None          # assets/<name>.png; None means "draw me by hand"
    TRAPPABLE = False      # can be imprisoned in the Outpost
    HEAVY = False          # a tank: cannons get bonus damage against it
    STRIPPABLE = False     # armour can be torn off by dragging on it
    ARMOR_LAYERS = 0       # how many plates there are to tear off
    IS_BOSS = False
    FLY_Y = 250.0
    DESC = ""

    def __init__(self, game, wave, x=None, y=None):
        self.game = game
        self.wave = wave
        # a stable identity: CPython recycles id() values, which could make a
        # fresh mob inherit a dead one's "already hit" marker
        Enemy._next_uid += 1
        self.uid = Enemy._next_uid
        hp_m, dmg_m, spd_m = wave_scaling(wave)
        # difficulty stretches or compresses the whole scaling curve
        diff = getattr(game, "enemy_scale", 1.0)
        if diff != 1.0:
            hp_m *= diff
            dmg_m *= diff
            # speed is deliberately not stretched here: difficulty moves it
            # with one flat multiplier instead, applied further down.
        # Hard steepens the health curve itself -- the growth *earned per
        # tier* is multiplied, so tier 1 is barely touched and tier 20 hurts.
        curve = getattr(game, "enemy_hp_curve", 1.0)
        if curve != 1.0:
            hp_m = 1.0 + (hp_m - 1.0) * curve
        # endgame tiers stack on top of the normal per-wave scaling and
        # repaint the mob so the danger is readable at a glance
        self.tier = endgame_tier(wave)
        self.tier_name = ""
        if self.tier >= 0:
            name, _first, tint, strength, thp, tdmg, tspd = ENDGAME_TIERS[self.tier]
            self.tier_name = name
            hp_m *= thp
            dmg_m *= tdmg
            spd_m *= tspd
            # shadows the class attribute for this instance only, so every
            # existing `self.COLOR` reference picks up the tint for free
            self.COLOR = mix(self.COLOR, tint, strength)
        self.max_hp = self.BASE_HP * hp_m
        self.hp = self.max_hp
        # armour is per-instance because the player can tear it off
        self.armor = self.ARMOR
        self.layers = self.ARMOR_LAYERS
        self.strip_progress = 0.0      # 0..1 toward prying the next plate
        self.vulnerable = 1.0          # damage taken multiplier once stripped
        # a flat base-speed multiplier on top of the wave curve --
        # Hard mobs close the distance 40% sooner at every tier
        self.speed = (self.BASE_SPEED * spd_m
                      * getattr(game, "enemy_speed_scale", 1.0))
        self.damage = self.BASE_DAMAGE * dmg_m
        self.gold = int(round(self.GOLD * (1.0 + 0.05 * (wave - 1))))

        self.depth = 0.0 if self.FLYING else random.uniform(-16.0, 16.0)
        self.fly_y = self.FLY_Y + random.uniform(-46.0, 46.0)
        self.blocked = False        # someone is standing directly in front
        self.w, self.h = float(self.W), float(self.H)
        self.flying = self.FLYING
        self.x = float(SPAWN_X + random.uniform(0, 140)) if x is None else float(x)
        if y is None:
            self.y = self.fly_y if self.flying else self.ground_y
        else:
            self.y = float(y)

        self.vx = 0.0
        self.vy = 0.0
        self.vx_estimate = -self.speed
        self.vy_estimate = 0.0
        self.state = "walk"
        self.alive = True
        self.attack_timer = random.uniform(0.0, 0.4)
        self.anim = random.uniform(0.0, 10.0)
        self.hurt_flash = 0.0
        self.slam_cooldown = {}
        self.stagger = 0.0
        # --- score tracking for one fling ---
        self.fling_active = False
        self.fling_x0 = self.fling_y0 = 0.0
        self.fling_t0 = 0.0
        self.fling_hits = 0
        self.fling_peak = 0.0
        self.bounce_count = 0
        self.storm_cd = 0.0          # lightning re-strike delay
        self.regalia_cd = 0.0        # seconds until its item can be taken again
        self.regalia_taken = 0       # how many times the player has robbed it
        self.shove = 0.0             # forward momentum from the player shoving
        self.trapped = False         # imprisoned in the Outpost
        self.tornado_hold = 0.0      # seconds of tornado lift still acting
        self.bob = random.uniform(0, math.tau)
        self.spin = 0.0

    # -- geometry --------------------------------------------------------
    @property
    def ground_y(self):
        return GROUND_Y + self.depth - self.h / 2.0

    @property
    def hit_rect(self):
        return pygame.Rect(int(self.x - self.w / 2), int(self.y - self.h / 2),
                           int(self.w), int(self.h))

    def covers(self, px, py):
        """Point-in-hitbox without building a Rect.

        This is the single hottest call in the game -- once per projectile
        per enemy per frame -- and allocating a pygame.Rect for it dominated
        the profile at high waves.  The arithmetic is equivalent."""
        return (abs(px - self.x) <= self.w * 0.5
                and abs(py - self.y) <= self.h * 0.5)

    def overlaps(self, other):
        """Box overlap against another entity, allocation-free."""
        return (abs(self.x - other.x) * 2.0 <= self.w + other.w
                and abs(self.y - other.y) * 2.0 <= self.h + other.h)

    @property
    def grab_rect(self):
        return self.hit_rect.inflate(16, 16)

    @property
    def targetable(self):
        return self.alive

    @property
    def grabbable(self):
        if not (self.GRABBABLE and self.alive
                and self.state in ("walk", "attack")):
            return False
        # A heavy unit rides out every lift attempt while its plating is on.
        # Grab Strength alone is not enough -- the armour comes off first.
        if self.armored:
            return False
        return self.MASS <= self.game.grab_capacity

    @property
    def armored(self):
        """Heavy unit still wearing plating: locks lifting and shoving."""
        return self.HEAVY and self.layers > 0

    @property
    def shovable(self):
        """Stripped heavy unit: can be hauled forward by the cursor."""
        return (self.HEAVY and self.alive and not self.armored
                and self.state in ("walk", "attack"))

    @property
    def too_heavy(self):
        """Stripped and liftable in principle, but the cursor is too weak."""
        return (self.GRABBABLE and self.alive and not self.armored
                and self.MASS > self.game.grab_capacity)

    def regalia_anchor(self):
        """Where this enemy's detachable item sits, or None."""
        return None

    def regalia_rect(self):
        """Rect of a detachable item on this enemy, or None. Bosses override
        this to expose a crown / staff the player can rip off."""
        return None

    def smack_rect(self):
        """Rect the player can batter with the cursor, or None. Used by the
        Dragon's claws; shares the boss-disruption guard timer."""
        return None

    def apply_smack(self, amount):
        return False

    def guard_regalia(self):
        """Called when a boss recovers its item: it holds on tighter each
        time, so the mechanic stays strong without becoming a stun-lock."""
        self.regalia_cd = REGALIA_COOLDOWN * (
            1.0 + REGALIA_CD_GROWTH * self.regalia_taken)

    def detach_regalia(self):
        """Pull the item loose and hand back a DroppedItem."""
        return None

    @property
    def strippable(self):
        """Heavy units refuse to be lifted, but their plating can be pried
        off by hauling on it -- that is the player's answer to a tank."""
        return (self.STRIPPABLE and self.alive and self.layers > 0
                and self.state in ("walk", "attack"))

    def apply_shove(self, amount):
        """Player is hauling this heavy unit castle-ward: give it momentum.
        Rushes the last slow tank into the guns, at the cost of it arriving
        at the wall far sooner."""
        if not (self.HEAVY and self.alive):
            return False
        if self.armored:
            return False        # strip the plating before hauling it about
        was = self.shove
        self.shove = min(SHOVE_MAX, self.shove + amount * SHOVE_FACTOR)
        if self.shove > 40 and was <= 40:
            self.game.effects.text(self.x, self.y - self.h * 0.8, "SHOVE!",
                                   (150, 220, 255), 22)
        self.game.effects.burst(self.x + self.w * 0.4, self.y, 2,
                                (170, 210, 255), speed=90, life=0.25, size=2)
        return True

    def apply_strip(self, amount):
        """Feed drag distance into prying off the next armour plate.
        Returns True when a plate actually comes away."""
        if not self.strippable:
            return False
        self.strip_progress += amount / STRIP_DISTANCE
        if self.strip_progress < 1.0:
            return False
        self.strip_progress = 0.0
        self.layers -= 1
        g = self.game
        # each plate: less armour, slower advance, more damage taken
        self.armor = max(0.0, self.ARMOR * (self.layers / max(1, self.ARMOR_LAYERS)))
        self.speed *= STRIP_SLOW
        self.vulnerable += STRIP_VULN
        g.stats_plates_torn += 1
        g.add_shake(4.0)
        g.effects.burst(self.x, self.y, 26, (176, 180, 196), speed=340,
                        life=0.7, size=4)
        g.effects.text(self.x, self.y - self.h * 0.8,
                       "ARMOR TORN!" if self.layers else "FULLY EXPOSED!",
                       (255, 214, 120) if self.layers else (255, 130, 110), 24)
        return True

    @property
    def speed_now(self):
        return math.hypot(self.vx, self.vy)

    # -- damage ----------------------------------------------------------
    def take_damage(self, amount, kind="projectile"):
        if not self.alive:
            return 0.0
        if kind == "projectile":
            amount *= (1.0 - self.armor) * self.vulnerable
        elif kind == "explosive":
            amount *= (1.0 - self.armor * 0.35) * self.vulnerable
        # 'fall' and 'impact' deliberately ignore armour: hurling a Shield
        # Bearer off a cliff is the player's answer to all that plating.
        amount = max(0.0, amount)
        self.hp -= amount
        self.hurt_flash = 1.0
        if self.hp <= 0:
            self.die()
        return amount

    def die(self, silent=False):
        if not self.alive:
            return
        g = self.game
        # the crowd bonus counts this mob too -- chaos pays
        mult = g.gold_multiplier
        self.alive = False
        self.hp = 0.0
        if not silent:
            gain = max(1, int(round(self.gold * mult)))
            g.gold += gain
            g.stats_kills += 1
            g.effects.burst(self.x, self.y, 18 if not self.IS_BOSS else 90,
                            self.COLOR, speed=280 if not self.IS_BOSS else 520,
                            life=0.7, size=3 if not self.IS_BOSS else 6)
            label = f"+{gain}g" + (f"  x{mult:.1f}" if mult > 1.05 else "")
            g.effects.text(self.x, self.y - self.h * 0.6, label,
                           C_GOLD, 20 if not self.IS_BOSS else 34)
            self.resolve_fling()
            if self.IS_BOSS:
                g.on_boss_defeated(self)
                g.add_shake(12.0)
                g.effects.ring(self.x, self.y, 40, (255, 220, 140),
                               speed=620, life=0.8, size=6)
                g.announce(f"{self.NAME} defeated!", C_GOLD)

    # -- throw physics ---------------------------------------------------
    def on_grab(self):
        self.state = "grabbed"
        self.vx = self.vy = 0.0
        self.spin = 0.0

    def on_release(self, vx, vy):
        mult = (THROW_POWER * self.game.talents.throw_power
                / (0.55 + 0.45 * self.MASS))
        self.state = "air"
        self.vx = vx * mult
        self.vy = vy * mult
        self.slam_cooldown.clear()
        # start scoring this fling
        self.fling_active = True
        self.fling_x0, self.fling_y0 = self.x, self.y
        self.fling_peak = self.y
        self.fling_t0 = self.game.time
        self.fling_hits = 0
        self.bounce_count = 0
        self.game.effects.burst(self.x, self.y, 8, (220, 220, 240),
                                speed=150, life=0.3)

    def land(self):
        """Called when an airborne mob hits the dirt.  Ouch.

        With the Bounce upgrade the mob is heavier and springier: it rebounds
        several times, each impact doing damage and resetting a longer
        recovery, so a good throw keeps a mob out of the fight for ages."""
        g = self.game
        lvl = clamp(g.bounce_level, 0, BOUNCE_MAX_LEVEL)
        impact = math.hypot(self.vx * 0.5, self.vy)
        dmg = max(0.0, impact - FALL_DMG_FLOOR) * FALL_DMG_SCALE * \
            (0.75 + 0.35 * self.MASS) * (1.0 + BOUNCE_DMG_BONUS * lvl) * \
            g.talents.fall_damage
        self.bounce_count += 1
        if dmg > 0:
            self.take_damage(dmg, "fall")
            g.effects.text(self.x, self.y - self.h, f"{int(dmg)}",
                           (255, 168, 72), 24)
            g.add_shake(min(7.0, dmg * 0.07))
            g.effects.burst(self.x, self.ground_y + self.h / 2, 16,
                            C_DIRT, speed=min(320, impact * 0.5), life=0.5, size=4)
            g.stats_thrown_damage += dmg
        if self.bounce_count > 1 and dmg > 0:
            g.effects.text(self.x, self.y - self.h * 1.2, "BOUNCE!",
                           (150, 220, 255), 20, 0.6)
        self.vy = -abs(self.vy) * BOUNCE_RESTITUTION[lvl]
        self.vx *= 0.45 + 0.07 * lvl
        self.spin *= 0.3
        # a hard cap on rebounds guarantees the mob always settles
        if self.bounce_count > lvl or abs(self.vy) < 90:
            self.vy = 0.0
            self.spin = 0.0
            self.y = self.ground_y
            if self.alive:
                self.state = "walk"
                self.stagger = 0.45 + BOUNCE_STAGGER * lvl
            self.resolve_fling()

    def resolve_fling(self):
        """Cash in the score for one completed fling: distance + airtime,
        multiplied up by every mob clobbered on the way."""
        if not self.fling_active:
            return
        self.fling_active = False
        g = self.game
        travel = abs(self.x - self.fling_x0) + max(0.0, self.fling_y0 - self.fling_peak)
        airtime = max(0.0, g.time - self.fling_t0)
        base = travel * SCORE_PER_PX + airtime * SCORE_PER_SEC
        combo = 1.0 + SCORE_COMBO_STEP * self.fling_hits
        pts = int(base * combo)
        if pts > 0:
            g.add_score(pts, self.x, self.y - self.h, self.fling_hits, combo)

    def slam_into(self, other):
        """Airborne mob crashes into another mob -- both suffer."""
        rel = math.hypot(self.vx - other.vx, self.vy - other.vy)
        dmg = max(0.0, rel - SLAM_DMG_FLOOR) * SLAM_DMG_SCALE * \
            (0.6 + 0.5 * self.MASS)
        if dmg <= 0:
            return
        g = self.game
        other.take_damage(dmg, "impact")
        self.take_damage(dmg * 0.45, "impact")
        g.stats_thrown_damage += dmg
        self.fling_hits += 1
        g.effects.text(other.x, other.y - other.h * 0.7, f"{int(dmg)}",
                       (255, 208, 96), 22)
        g.effects.burst((self.x + other.x) / 2, (self.y + other.y) / 2, 12,
                        (255, 220, 150), speed=240, life=0.4, size=3)
        g.add_shake(2.5)
        if (other.alive and other.GRABBABLE and other.MASS <= 4.0
                and other.state in ("walk", "attack")):
            other.state = "air"
            other.vx = self.vx * 0.4
            other.vy = min(-140.0, self.vy * 0.5)
            other.slam_cooldown.clear()
            other.slam_cooldown[self.uid] = 0.4
        self.vx *= 0.55
        self.vy *= 0.55

    # -- update ----------------------------------------------------------
    def update(self, dt):
        self.hurt_flash = max(0.0, self.hurt_flash - dt * 4.0)
        self.stagger = max(0.0, self.stagger - dt)
        self.regalia_cd = max(0.0, self.regalia_cd - dt)
        self.storm_cd = max(0.0, self.storm_cd - dt)
        for k in list(self.slam_cooldown):
            self.slam_cooldown[k] -= dt
            if self.slam_cooldown[k] <= 0:
                del self.slam_cooldown[k]

        if self.state == "grabbed":
            self.vx_estimate = self.vy_estimate = 0.0
            return
        if self.state == "air":
            self._update_air(dt)
            return

        self.vx_estimate = 0.0
        self.vy_estimate = 0.0
        if self.stagger > 0:
            return
        self.think(dt)

    def _update_air(self, dt):
        if self.tornado_hold > 0:
            # held in a vortex: the funnel carries its own weight
            self.tornado_hold = max(0.0, self.tornado_hold - dt)
            self.vy += GRAVITY * 0.12 * dt
        else:
            self.vy += GRAVITY * dt
        self.vx += (self.game.wind * self.game.talents.wind_mult
                    * dt)                        # weather pushes bodies too
        self.vx -= self.vx * AIR_DRAG * dt
        self.x += self.vx * dt
        self.y += self.vy * dt
        self.spin += self.vx * dt * 0.012
        self.fling_peak = min(self.fling_peak, self.y)
        if self.game.storm and self.y < STORM_CEILING:
            self.game.strike_lightning(self)
        self.vx_estimate, self.vy_estimate = self.vx, self.vy

        # walls of the arena
        left_wall = CASTLE_FRONT + self.w / 2
        if self.x < left_wall:
            self.x = left_wall
            if self.vx < 0:
                self.vx = -self.vx * 0.45
                self.take_damage(max(0.0, abs(self.vx) - 120) * 0.09, "impact")
        if self.x > WIDTH + 200:
            self.x = WIDTH + 200
            self.vx = -abs(self.vx) * 0.4
        if self.y < 24:
            self.y = 24
            self.vy = abs(self.vy) * 0.3

        # flung into the Outpost?  That is the Necromancer betrayal.
        if self.TRAPPABLE:
            post = self.game.outpost
            if post.can_trap(self) and post.trap_rect.colliderect(self.hit_rect):
                post.trap(self)
                return

        # mid-air collisions -- snapshot: Outpost.trap() above can have just
        # removed an entry, and a slam may yet change the roster
        for o in list(self.game.enemies):
            if o is self or not o.alive or o.uid in self.slam_cooldown:
                continue
            if o.state == "grabbed":
                continue
            if self.overlaps(o):
                self.slam_cooldown[o.uid] = 0.35
                self.slam_into(o)
                if not self.alive:
                    return

        if self.y >= self.ground_y:
            self.y = self.ground_y
            self.land()
            if self.flying and self.alive and self.state == "walk":
                self.state = "walk"   # gargoyles climb back up in think()

    # -- behaviour (override me) -----------------------------------------
    def think(self, dt):
        slow = self.game.enemy_slow(self)
        if slow < 1.0:
            self.speed *= slow          # restored at the end of think()
        if self.shove > 0:      # carried momentum from the player's shove
            self.x -= self.shove * dt
            self.shove *= max(0.0, 1.0 - SHOVE_DECAY * dt)
            if self.shove < 8.0:
                self.shove = 0.0
        self.anim += dt * self.speed * 0.06
        if self.flying:
            self.bob += dt * 3.0
            target_y = self.fly_y + math.sin(self.bob) * 18
            self.y += clamp(target_y - self.y, -160 * dt, 160 * dt)

        # a friendly skeleton in the way has to be dealt with first
        if not self.flying:
            ally = self.game.ally_in_front(self)
            if ally is not None:
                self.state = "attack"
                self.attack_timer -= dt
                if self.attack_timer <= 0:
                    self.attack_timer = self.ATTACK_RATE
                    ally.take_damage(max(1.0, self.damage * 0.8))
                    self.game.effects.burst(ally.x, ally.y, 4, (255, 180, 150),
                                            speed=120, life=0.25, size=2)
                return

        bar = self.game.barricade
        if (bar.alive and not self.flying and self.x >= bar.x
                and self.x - self.w / 2 <= bar.x + bar.W / 2):
            # blocked out in the field -- chew through the barricade.
            # (a mob thrown *over* it lands inside and ignores it)
            self.state = "attack"
            self.x = bar.x + bar.W / 2 + self.w / 2
            self.attack_timer -= dt
            if self.attack_timer <= 0:
                self.attack_timer = self.ATTACK_RATE
                bar.take_damage(self.damage * (2.5 if self.HEAVY else 1.0))
                self.game.effects.burst(bar.x, self.y, 6, (170, 150, 120),
                                        speed=160, life=0.3)
            return

        if self.x - self.w / 2 <= self.game.castle.front_x:
            self.state = "attack"
            self.x = self.game.castle.front_x + self.w / 2
            self.attack_timer -= dt
            if self.attack_timer <= 0:
                self.attack_timer = self.ATTACK_RATE
                self.attack_castle()
        elif self.blocked:
            # wait your turn -- only the front rank gets to swing at the wall
            self.state = "walk"
            self.x -= self.speed * 0.12 * dt
            self.vx_estimate = -self.speed * 0.12
        else:
            self.state = "walk"
            self.x -= self.speed * dt
            self.vx_estimate = -self.speed
        if slow < 1.0:
            self.speed /= slow

    def attack_castle(self):
        g = self.game
        g.castle.take_damage(self.damage)
        g.effects.burst(g.castle.front_x, self.y, 8,
                        (220, 200, 180), speed=180, life=0.35)
        g.apply_spikes(self)

    def draw_too_heavy_hint(self, surf):
        pass

    # -- drawing ---------------------------------------------------------
    def blit_sprite(self, surf):
        """Draw supplied artwork for this unit if the player dropped a file
        into assets/.  Returns False so the hand-drawn body is used instead."""
        if not self.SPRITE:
            return False
        return blit_asset(surf, self.SPRITE, self.hit_rect)

    def body_color(self):
        if self.hurt_flash > 0:
            return mix(self.COLOR, (255, 255, 255), self.hurt_flash * 0.75)
        return self.COLOR

    def draw(self, surf):
        surf_x = int(self.x)
        if self.state == "air" or self.state == "grabbed":
            # shadow on the ground so the player can judge the landing spot
            gy = int(GROUND_Y + self.depth)
            sw = int(self.w * clamp(1.0 - (gy - self.y) / 900.0, 0.35, 1.0))
            sh = pygame.Surface((max(6, sw), 8), pygame.SRCALPHA)
            pygame.draw.ellipse(sh, (0, 0, 0, 90), sh.get_rect())
            surf.blit(sh, (surf_x - sw // 2, gy - 4))
        self.draw_body(surf)
        if self.regalia_cd > 0:
            self.draw_regalia_guard(surf)
        self.draw_hp(surf)

    def draw_regalia_guard(self, surf):
        """A shrinking ward around the item while the boss is guarding it."""
        a = self.regalia_anchor()
        if a is None:
            return
        ax, ay = int(a[0]), int(a[1])
        span = max(1.0, REGALIA_COOLDOWN * (
            1.0 + REGALIA_CD_GROWTH * max(0, self.regalia_taken - 1)))
        frac = clamp(self.regalia_cd / span, 0.0, 1.0)
        ring = pygame.Surface((60, 60), pygame.SRCALPHA)
        pygame.draw.arc(ring, (150, 205, 255, 200), (6, 6, 48, 48),
                        -math.pi / 2, -math.pi / 2 + math.tau * frac, 3)
        surf.blit(ring, (ax - 30, ay - 30))

    def draw_body(self, surf):
        if self.blit_sprite(surf):
            return
        r = self.hit_rect
        pygame.draw.rect(surf, self.body_color(), r, border_radius=4)
        pygame.draw.rect(surf, shade(self.COLOR, 0.5), r, 2, border_radius=4)

    def draw_hp(self, surf):
        if not self.alive:
            return
        w = max(20, int(self.w))
        if self.strip_progress > 0:
            draw_bar(surf, int(self.x - w / 2), int(self.y - self.h / 2 - 17),
                     w, 5, self.strip_progress, (255, 196, 90))
        if self.IS_BOSS or self.hp >= self.max_hp:
            return
        draw_bar(surf, int(self.x - w / 2), int(self.y - self.h / 2 - 10),
                 w, 4, self.hp / self.max_hp, C_GREEN)

    # small shared painter: two bobbing legs
    def _legs(self, surf, color, span=6, length=8):
        if self.state in ("air", "grabbed"):
            phase = self.spin * 3.0
        else:
            phase = self.anim
        b = self.hit_rect.bottom
        for s in (-1, 1):
            off = math.sin(phase + (0 if s < 0 else math.pi)) * 4
            pygame.draw.line(surf, color, (self.x + s * span, b - 2),
                             (self.x + s * span + off, b + length), 3)


# --- 1. Scout -----------------------------------------------------------------
class Scout(Enemy):
    SPRITE = "scout"
    NAME = "Scout"
    DESC = "Fast, fragile skirmisher."
    COLOR = (118, 204, 116)
    W, H = 20, 26
    BASE_HP = 26.0
    BASE_SPEED = 122.0
    BASE_DAMAGE = 5.0
    ATTACK_RATE = 0.8
    GOLD = 6
    MASS = 0.8

    def draw_body(self, surf):
        if self.blit_sprite(surf):
            return
        r = self.hit_rect
        col = self.body_color()
        self._legs(surf, shade(col, 0.6))
        pygame.draw.rect(surf, col, (r.x, r.y + 6, r.w, r.h - 8), border_radius=4)
        pygame.draw.rect(surf, shade(col, 0.5), (r.x, r.y + 6, r.w, r.h - 8),
                         2, border_radius=4)
        pygame.draw.circle(surf, (232, 202, 164), (r.centerx, r.y + 5), 6)
        pygame.draw.line(surf, (198, 194, 186), (r.left - 4, r.centery),
                         (r.left - 12, r.centery + 6), 2)


# --- 2. Foot Soldier ----------------------------------------------------------
class FootSoldier(Enemy):
    SPRITE = "foot_soldier"
    NAME = "Foot Soldier"
    DESC = "Reliable line infantry."
    COLOR = (92, 130, 200)
    W, H = 26, 34
    BASE_HP = 62.0
    BASE_SPEED = 72.0
    BASE_DAMAGE = 10.0
    ATTACK_RATE = 1.0
    GOLD = 11
    MASS = 1.5

    def draw_body(self, surf):
        if self.blit_sprite(surf):
            return
        r = self.hit_rect
        col = self.body_color()
        self._legs(surf, (58, 62, 84))
        pygame.draw.rect(surf, col, (r.x, r.y + 8, r.w, r.h - 10), border_radius=3)
        pygame.draw.rect(surf, shade(col, 0.5), (r.x, r.y + 8, r.w, r.h - 10),
                         2, border_radius=3)
        pygame.draw.circle(surf, (226, 194, 158), (r.centerx, r.y + 7), 7)
        pygame.draw.rect(surf, (150, 156, 172), (r.x + 2, r.y, r.w - 4, 6),
                         border_radius=2)   # helmet
        sw = math.sin(self.anim * 0.9) * 4
        pygame.draw.line(surf, (206, 210, 220), (r.left - 2, r.centery + 4),
                         (r.left - 14 + sw, r.centery - 8), 3)


# --- 3. Shield Bearer ---------------------------------------------------------
class ShieldBearer(Enemy):
    SPRITE = "shield_bearer"
    NAME = "Shield Bearer"
    DESC = "Slow bulwark; shrugs off arrows."
    COLOR = (176, 148, 96)
    W, H = 34, 38
    BASE_HP = 160.0
    BASE_SPEED = 46.0
    BASE_DAMAGE = 12.0
    ATTACK_RATE = 1.4
    GOLD = 19
    ARMOR = 0.60
    MASS = 3.0

    def draw_body(self, surf):
        if self.blit_sprite(surf):
            return
        r = self.hit_rect
        col = self.body_color()
        self._legs(surf, (72, 62, 46), span=8, length=9)
        pygame.draw.rect(surf, col, (r.x + 6, r.y + 8, r.w - 8, r.h - 10),
                         border_radius=3)
        pygame.draw.circle(surf, (226, 194, 158), (r.centerx + 4, r.y + 7), 7)
        # the big shield on the leading edge
        sh = pygame.Rect(r.left - 6, r.y + 2, 14, r.h - 2)
        pygame.draw.rect(surf, (150, 156, 168), sh, border_radius=4)
        pygame.draw.rect(surf, (98, 104, 116), sh, 3, border_radius=4)
        pygame.draw.circle(surf, (206, 176, 96), (sh.centerx, sh.centery), 5)
        draw_text(surf, "ARMOR", self.x, r.y - 22, 14, (200, 200, 210),
                  "center", shadow=False)


# --- 4. Berzerker -------------------------------------------------------------
class Berzerker(Enemy):
    SPRITE = "berzerker"
    NAME = "Berzerker"
    DESC = "Glass cannon; hits like a truck."
    COLOR = (222, 96, 64)
    W, H = 26, 32
    BASE_HP = 48.0
    BASE_SPEED = 134.0
    BASE_DAMAGE = 24.0
    ATTACK_RATE = 0.7
    GOLD = 15
    MASS = 1.2

    def think(self, dt):
        # gets angrier (and faster) as it takes damage
        rage = 1.0 + 0.45 * (1.0 - self.hp / max(1.0, self.max_hp))
        old = self.speed
        self.speed = self.BASE_SPEED * wave_scaling(self.wave)[2] * rage
        super().think(dt)
        self.speed = old

    def draw_body(self, surf):
        if self.blit_sprite(surf):
            return
        r = self.hit_rect
        col = self.body_color()
        self._legs(surf, (110, 48, 34))
        pygame.draw.rect(surf, col, (r.x, r.y + 7, r.w, r.h - 9), border_radius=3)
        pygame.draw.circle(surf, (240, 176, 140), (r.centerx, r.y + 6), 7)
        pygame.draw.line(surf, (255, 232, 120), (r.centerx - 5, r.y + 5),
                         (r.centerx - 1, r.y + 5), 2)
        pygame.draw.line(surf, (255, 232, 120), (r.centerx + 1, r.y + 5),
                         (r.centerx + 5, r.y + 5), 2)
        sw = math.sin(self.anim * 1.6) * 8
        for s in (-1, 1):
            pygame.draw.line(surf, (216, 216, 226),
                             (r.centerx, r.centery), 
                             (r.centerx + s * 16, r.centery - 10 + sw), 4)


# --- 5. Siege Ram -------------------------------------------------------------
class SiegeRam(Enemy):
    SPRITE = "siege_ram"
    NAME = "Siege Ram"
    DESC = "Armoured tank. Drag on it to tear the plating off."
    COLOR = (128, 92, 58)
    W, H = 76, 44
    BASE_HP = 520.0
    BASE_SPEED = 28.0
    BASE_DAMAGE = 58.0
    ATTACK_RATE = 2.2
    GOLD = 52
    ARMOR = 0.60           # very heavily plated...
    MASS = 9.0
    GRABBABLE = True       # ...liftable only at high Grab Strength...
    HEAVY = True           # ...but Cannons hit it for triple...
    STRIPPABLE = True      # ...and the player can rip the plates off
    ARMOR_LAYERS = 3

    def __init__(self, game, wave, x=None, y=None):
        super().__init__(game, wave, x, y)
        self.ram_push = 0.0

    def attack_castle(self):
        self.ram_push = 1.0
        self.game.castle.take_damage(self.damage)
        self.game.apply_spikes(self)
        self.game.add_shake(9.0)
        self.game.effects.burst(self.game.castle.front_x, self.y, 26,
                                (190, 170, 150), speed=320, life=0.6, size=5)
        self.game.effects.text(self.game.castle.front_x + 30, self.y - 40,
                               "SMASH!", (255, 140, 110), 26)

    def think(self, dt):
        self.ram_push = max(0.0, self.ram_push - dt * 3.0)
        super().think(dt)

    def draw_body(self, surf):
        if self.blit_sprite(surf):
            return
        r = self.hit_rect
        col = self.body_color()
        # wheels
        for wx in (r.left + 16, r.right - 16):
            pygame.draw.circle(surf, (58, 46, 34), (wx, r.bottom + 4), 10)
            pygame.draw.circle(surf, (96, 78, 56), (wx, r.bottom + 4), 10, 2)
            ang = self.anim * 0.6
            pygame.draw.line(surf, (140, 118, 88),
                             (wx - math.cos(ang) * 8, r.bottom + 4 - math.sin(ang) * 8),
                             (wx + math.cos(ang) * 8, r.bottom + 4 + math.sin(ang) * 8), 2)
        # frame
        pygame.draw.rect(surf, col, (r.x, r.y + 10, r.w, r.h - 10), border_radius=4)
        pygame.draw.rect(surf, shade(col, 0.55), (r.x, r.y + 10, r.w, r.h - 10),
                         3, border_radius=4)
        # roof
        pygame.draw.polygon(surf, (86, 66, 44), [
            (r.left - 6, r.y + 10), (r.right + 6, r.y + 10),
            (r.right - 6, r.y - 6), (r.left + 6, r.y - 6)])
        # --- armour plates: one bolted panel per remaining layer ---
        plate_w = (r.w - 8) / max(1, self.ARMOR_LAYERS)
        for i in range(self.layers):
            px = r.x + 4 + i * plate_w
            plate = pygame.Rect(int(px), r.y + 8, int(plate_w - 3), r.h - 12)
            pygame.draw.rect(surf, (150, 157, 174), plate, border_radius=3)
            pygame.draw.line(surf, (196, 202, 218), (plate.left + 3, plate.bottom - 4),
                             (plate.right - 4, plate.top + 3), 3)
            pygame.draw.rect(surf, (84, 90, 104), plate, 2, border_radius=3)
            for by in (plate.top + 5, plate.bottom - 5):
                for bx in (plate.left + 5, plate.right - 5):
                    pygame.draw.circle(surf, (214, 218, 230), (bx, by), 2)
        if self.layers == 0:
            draw_text(surf, "EXPOSED", self.x, r.bottom + 6, 16, (255, 140, 120),
                      "center", True)
        # the ram log, recoiling on impact
        px = r.left - 16 - self.ram_push * 12
        pygame.draw.line(surf, (74, 56, 38), (px + 34, r.centery + 6),
                         (px, r.centery + 6), 12)
        pygame.draw.circle(surf, (152, 156, 168), (int(px), r.centery + 6), 9)
        pygame.draw.circle(surf, (98, 102, 114), (int(px), r.centery + 6), 9, 2)


# --- 6. Necromancer -----------------------------------------------------------
class Skeleton(Enemy):
    SPRITE = "skeleton"
    NAME = "Skeleton"
    DESC = "Summoned rabble."
    COLOR = (222, 220, 206)
    W, H = 18, 28
    BASE_HP = 20.0
    BASE_SPEED = 92.0
    BASE_DAMAGE = 6.0
    ATTACK_RATE = 0.9
    GOLD = 2
    MASS = 0.7

    def draw_body(self, surf):
        if self.blit_sprite(surf):
            return
        r = self.hit_rect
        col = self.body_color()
        self._legs(surf, (186, 184, 170))
        pygame.draw.circle(surf, col, (r.centerx, r.y + 6), 6)
        pygame.draw.circle(surf, (40, 36, 40), (r.centerx - 2, r.y + 5), 2)
        pygame.draw.circle(surf, (40, 36, 40), (r.centerx + 2, r.y + 5), 2)
        for i in range(3):
            yy = r.y + 13 + i * 5
            pygame.draw.line(surf, col, (r.centerx - 5, yy), (r.centerx + 5, yy), 2)
        pygame.draw.line(surf, col, (r.centerx, r.y + 12), (r.centerx, r.y + 24), 2)


class FriendlySkeleton:
    """A skeleton raised by the Necromancer imprisoned in the Outpost.

    His magic runs backwards in there, so these march the wrong way -- left
    to right, out of the outpost and into the oncoming horde.  They live in
    `game.allies`, never `game.enemies`, so the player's own towers ignore
    them and they never threaten the castle.
    """
    NAME = "Bone Ally"
    SPRITE = "friendly_skeleton"
    W, H = 18, 28
    # Deliberately fragile: early allies are a speed bump, not a wall.
    # Bonecraft in the Necromancy branch is what makes them stick.
    BASE_HP = 18.4          # 60% below the original 46
    BASE_SPEED = 78.0
    BASE_DAMAGE = 13.0
    ATTACK_RATE = 0.85

    def __init__(self, game, wave, x, y=None):
        self.game = game
        hp_m, dmg_m, _spd = wave_scaling(wave)
        boost = game.talents.ally_power if hasattr(game, "talents") else 1.0
        self.max_hp = self.BASE_HP * hp_m * boost
        self.hp = self.max_hp
        self.damage = self.BASE_DAMAGE * dmg_m * boost
        self.w, self.h = float(self.W), float(self.H)
        self.depth = random.uniform(-14.0, 14.0)
        self.x = float(x)
        self.y = self.ground_y if y is None else float(y)
        self.speed = self.BASE_SPEED
        self.alive = True
        self.state = "walk"
        self.attack_timer = random.uniform(0.0, 0.3)
        self.anim = random.uniform(0.0, 6.0)
        self.hurt_flash = 0.0
        self.target = None
        self.life = 60.0            # they crumble eventually

    @property
    def ground_y(self):
        return GROUND_Y + self.depth - self.h / 2.0

    @property
    def hit_rect(self):
        return pygame.Rect(int(self.x - self.w / 2), int(self.y - self.h / 2),
                           int(self.w), int(self.h))

    def take_damage(self, amount, kind="melee"):
        if not self.alive:
            return
        self.hp -= amount * self.game.talents.ally_tough
        self.hurt_flash = 1.0
        if self.hp <= 0:
            self.alive = False
            self.game.effects.burst(self.x, self.y, 12, C_ALLY,
                                    speed=200, life=0.5, size=3)

    def pick_target(self):
        """Nearest ground mob.  Without Undead Sentinels they only notice
        what is in front of them; with it they will turn and chase anything
        that slipped past."""
        sentinel = self.game.talents.rank("sentinels") > 0
        reach = 2000.0 if sentinel else 260.0
        best, bd = None, None
        for en in self.game.enemies:
            if not en.alive or en.flying or en.IS_BOSS:
                continue
            d = abs(en.x - self.x)
            if not sentinel and en.x < self.x:
                continue          # blind to anything already behind them
            if d < reach and (bd is None or d < bd):
                best, bd = en, d
        return best

    def update(self, dt):
        if not self.alive:
            return
        self.hurt_flash = max(0.0, self.hurt_flash - dt * 4.0)
        self.life -= dt
        if self.life <= 0:
            self.alive = False
            self.game.effects.burst(self.x, self.y, 10, (180, 190, 200),
                                    speed=140, life=0.5)
            return
        self.anim += dt * 8.0

        if self.target is not None and not self.target.alive:
            self.target = None
        if self.target is None:
            self.target = self.pick_target()

        tgt = self.target
        if tgt is not None and abs(tgt.x - self.x) <= ALLY_ENGAGE_RANGE:
            self.state = "attack"
            self.attack_timer -= dt
            if self.attack_timer <= 0:
                self.attack_timer = self.ATTACK_RATE
                tgt.take_damage(self.damage, "melee")
                self.game.effects.burst(tgt.x, tgt.y, 4, C_ALLY,
                                        speed=120, life=0.25, size=2)
            return

        if tgt is not None and self.game.talents.rank("sentinels") > 0:
            # Undead Sentinels: run it down, whichever way it is
            self.state = "walk"
            self.x += math.copysign(self.speed * dt, tgt.x - self.x)
        elif self.x < ALLY_HOLD_X:
            self.state = "walk"
            self.x += self.speed * dt      # marching the wrong way, on purpose
        else:
            # far enough out: hold this line and meet whatever arrives
            self.state = "hold"
            self.x = ALLY_HOLD_X

    def draw(self, surf):
        r = self.hit_rect
        if blit_asset(surf, self.SPRITE, r):
            return
        col = mix(C_ALLY, (255, 255, 255), self.hurt_flash * 0.7)
        b = r.bottom
        for s_ in (-1, 1):
            off = math.sin(self.anim + (0 if s_ < 0 else math.pi)) * 3
            pygame.draw.line(surf, shade(col, 0.8), (self.x + s_ * 4, b - 2),
                             (self.x + s_ * 4 + off, b + 6), 3)
        pygame.draw.circle(surf, col, (r.centerx, r.y + 6), 6)
        pygame.draw.circle(surf, (30, 60, 48), (r.centerx - 2, r.y + 5), 2)
        pygame.draw.circle(surf, (30, 60, 48), (r.centerx + 2, r.y + 5), 2)
        for i in range(3):
            yy = r.y + 13 + i * 5
            pygame.draw.line(surf, col, (r.centerx - 5, yy), (r.centerx + 5, yy), 2)
        # a faint halo so allies read differently from enemy skeletons
        glow = pygame.Surface((r.w + 14, r.h + 14), pygame.SRCALPHA)
        pygame.draw.ellipse(glow, (*C_ALLY, 46), glow.get_rect())
        surf.blit(glow, (r.x - 7, r.y - 7))
        if self.hp < self.max_hp:
            draw_bar(surf, r.x - 1, r.y - 9, r.w + 2, 3,
                     self.hp / self.max_hp, C_ALLY)


class Necromancer(Enemy):
    SPRITE = "necromancer"
    NAME = "Necromancer"
    DESC = "Hangs back and raises skeletons. Fling him into the Outpost!"
    TRAPPABLE = True
    COLOR = (146, 96, 196)
    W, H = 26, 38
    BASE_HP = 90.0
    BASE_SPEED = 54.0
    BASE_DAMAGE = 14.0
    GOLD = 26
    MASS = 1.4
    STANDOFF = 400.0
    SUMMON_RATE = 3.4
    MAX_MINIONS = 4

    def __init__(self, game, wave, x=None, y=None):
        super().__init__(game, wave, x, y)
        self.summon_timer = 2.0
        self.cast_timer = random.uniform(1.5, 3.0)
        self.standoff_x = CASTLE_FRONT + self.STANDOFF + random.uniform(-40, 60)
        self.minions = []
        self.glow = 0.0

    def hunting_prisoner(self):
        """True while there is a turncoat in the Outpost for him to punish."""
        post = self.game.outpost
        return post.has_prisoner and post.prisoner is not self

    def current_standoff(self):
        """Where he halts.

        A traitor in the Outpost outranks the castle.  He used to march all
        the way to the barricade before noticing the cage behind him, which
        put the prisoner out of the fight for the whole approach; now the
        moment he draws level with the tower he stops there and opens fire.
        """
        if self.hunting_prisoner():
            return max(self.standoff_x, self.game.outpost.x)
        return self.standoff_x

    def think(self, dt):
        self.anim += dt * 3.0
        self.glow = max(0.0, self.glow - dt * 2.0)
        self.minions = [m for m in self.minions if m.alive]

        standoff = self.current_standoff()
        if self.x > standoff:
            self.x -= self.speed * dt
            self.vx_estimate = -self.speed
            self.state = "walk"
            return
        self.state = "attack"

        self.summon_timer -= dt
        if self.summon_timer <= 0 and len(self.minions) < self.MAX_MINIONS:
            self.summon_timer = self.SUMMON_RATE
            self.summon()

        self.cast_timer -= dt
        if self.cast_timer <= 0:
            # halted at the Outpost rather than the wall: he is here for the
            # prisoner and nothing else, so every bolt goes into the cage
            at_post = self.x > self.standoff_x
            if self.hunting_prisoner() and (at_post or random.random() < 0.65):
                self.cast_timer = RIVAL_BOLT_RATE
                self.cast_at_prisoner()
            else:
                self.cast_timer = random.uniform(2.6, 4.0)
                self.cast_bolt()

    def summon(self):
        sk = Skeleton(self.game, self.wave,
                      x=self.x - random.uniform(20, 60), y=None)
        sk.y = sk.ground_y
        self.minions.append(sk)
        self.game.spawn_enemy(sk)
        self.glow = 1.0
        self.game.effects.ring(sk.x, sk.y + sk.h / 2, 14, (168, 120, 220),
                               speed=180, life=0.5, size=4)
        self.game.effects.text(self.x, self.y - self.h, "RISE", (196, 150, 245), 18)

    def cast_bolt(self):
        c = self.game.castle
        tx, ty = c.front_x - 20, WALL_TOP + 60
        a = math.atan2(ty - self.y, tx - self.x)
        speed = 430.0
        self.game.projectiles.append(Projectile(
            self.game, self.x, self.y - 8, math.cos(a) * speed,
            math.sin(a) * speed, "magic", self.damage, hostile=True,
            owner_uid=self.uid))
        self.glow = 1.0

    def on_trapped(self):
        """He stops thinking in the cage, so drop the minion list now rather
        than pinning dead Skeletons for as long as he lives."""
        self.minions = []

    def cast_at_prisoner(self):
        """Punish the turncoat: a bolt aimed at the Outpost cage."""
        post = self.game.outpost
        tx, ty = post.x, post.y - 30
        a = math.atan2(ty - self.y, tx - self.x)
        speed = 470.0
        self.game.projectiles.append(Projectile(
            self.game, self.x, self.y - 8, math.cos(a) * speed,
            math.sin(a) * speed, "magic", RIVAL_BOLT_DAMAGE,
            hostile=True, color=(214, 130, 255), at_prisoner=True,
            owner_uid=self.uid))
        self.glow = 1.0

    def draw_body(self, surf):
        if self.blit_sprite(surf):
            return
        r = self.hit_rect
        col = self.body_color()
        pygame.draw.polygon(surf, col, [
            (r.centerx, r.y), (r.right, r.bottom), (r.left, r.bottom)])
        pygame.draw.polygon(surf, shade(col, 0.55), [
            (r.centerx, r.y), (r.right, r.bottom), (r.left, r.bottom)], 2)
        pygame.draw.circle(surf, (44, 32, 56), (r.centerx, r.y + 10), 7)
        pygame.draw.circle(surf, (206, 140, 255), (r.centerx - 2, r.y + 9), 2)
        pygame.draw.circle(surf, (206, 140, 255), (r.centerx + 3, r.y + 9), 2)
        # staff with an orb that flares when casting
        sx = r.right + 4
        pygame.draw.line(surf, (120, 96, 72), (sx, r.bottom), (sx, r.y - 8), 3)
        gr = 5 + int(4 * self.glow) + int(math.sin(self.anim) * 1.5)
        pygame.draw.circle(surf, (196, 140, 255), (sx, r.y - 10), gr)
        pygame.draw.circle(surf, (238, 214, 255), (sx, r.y - 10), max(1, gr - 3))


# --- 7. Assassin --------------------------------------------------------------
class Assassin(Enemy):
    SPRITE = "assassin"
    NAME = "Assassin"
    DESC = "Cloaks, then dashes. Untargetable while hidden."
    COLOR = (62, 66, 92)
    W, H = 22, 30
    BASE_HP = 58.0
    BASE_SPEED = 96.0
    BASE_DAMAGE = 20.0
    ATTACK_RATE = 0.65
    GOLD = 24
    MASS = 1.0

    def __init__(self, game, wave, x=None, y=None):
        super().__init__(game, wave, x, y)
        self.cloaked = False
        self.cloak_timer = random.uniform(1.2, 2.6)
        self.dash_timer = random.uniform(1.8, 3.4)
        self.dashing = 0.0

    @property
    def targetable(self):
        return self.alive and not self.cloaked

    def think(self, dt):
        self.anim += dt * self.speed * 0.07
        self.cloak_timer -= dt
        if self.cloak_timer <= 0:
            self.cloaked = not self.cloaked
            self.cloak_timer = random.uniform(1.6, 2.8) if self.cloaked \
                else random.uniform(1.4, 2.4)
            self.game.effects.burst(self.x, self.y, 12, (120, 140, 200),
                                    speed=170, life=0.4, size=3)
        self.dash_timer -= dt
        if self.dash_timer <= 0 and self.dashing <= 0:
            self.dash_timer = random.uniform(2.4, 4.2)
            self.dashing = 0.38
            self.game.effects.text(self.x, self.y - self.h, "DASH!",
                                   (150, 190, 255), 18)
        boost = 1.0
        if self.dashing > 0:
            self.dashing -= dt
            boost = 3.6
            self.game.effects.burst(self.x, self.y, 2, (110, 130, 190),
                                    speed=40, life=0.25, grav=0)
        old = self.speed
        self.speed *= boost
        super().think(dt)
        self.speed = old

    def draw_body(self, surf):
        if self.blit_sprite(surf):
            return
        r = self.hit_rect
        col = self.body_color()
        if self.cloaked:
            ghost = pygame.Surface((r.w + 12, r.h + 12), pygame.SRCALPHA)
            pygame.draw.rect(ghost, (*shade(col, 1.6), 70),
                             (6, 6, r.w, r.h), border_radius=5)
            pygame.draw.rect(ghost, (140, 170, 230, 110),
                             (6, 6, r.w, r.h), 2, border_radius=5)
            surf.blit(ghost, (r.x - 6, r.y - 6))
            return
        self._legs(surf, (40, 44, 62))
        pygame.draw.rect(surf, col, (r.x, r.y + 6, r.w, r.h - 8), border_radius=5)
        pygame.draw.rect(surf, (36, 38, 54), (r.x, r.y + 6, r.w, r.h - 8),
                         2, border_radius=5)
        pygame.draw.circle(surf, (44, 46, 66), (r.centerx, r.y + 5), 6)
        pygame.draw.line(surf, (232, 96, 96), (r.centerx - 4, r.y + 4),
                         (r.centerx + 4, r.y + 4), 2)
        pygame.draw.line(surf, (214, 220, 236), (r.left - 2, r.centery),
                         (r.left - 13, r.centery - 5), 2)


# --- 8. Gargoyle --------------------------------------------------------------
class Gargoyle(Enemy):
    SPRITE = "gargoyle"
    NAME = "Gargoyle"
    DESC = "Flies over ballistas and cannons."
    COLOR = (122, 126, 140)
    W, H = 30, 26
    BASE_HP = 78.0
    BASE_SPEED = 92.0
    BASE_DAMAGE = 13.0
    ATTACK_RATE = 0.9
    GOLD = 26
    MASS = 1.1
    FLYING = True
    FLY_Y = 232.0

    def think(self, dt):
        self.anim += dt * 12.0
        super().think(dt)

    def draw_body(self, surf):
        if self.blit_sprite(surf):
            return
        r = self.hit_rect
        col = self.body_color()
        flap = math.sin(self.anim) * 12
        for s in (-1, 1):
            pygame.draw.polygon(surf, shade(col, 0.75), [
                (r.centerx, r.centery - 2),
                (r.centerx + s * 26, r.centery - 12 + flap),
                (r.centerx + s * 20, r.centery + 8 + flap * 0.4)])
        pygame.draw.ellipse(surf, col, r)
        pygame.draw.ellipse(surf, shade(col, 0.5), r, 2)
        pygame.draw.circle(surf, (250, 190, 90), (r.centerx - 5, r.centery - 3), 3)
        pygame.draw.circle(surf, (250, 190, 90), (r.centerx + 5, r.centery - 3), 3)
        for s in (-1, 1):
            pygame.draw.line(surf, shade(col, 1.25),
                             (r.centerx + s * 6, r.y + 2),
                             (r.centerx + s * 9, r.y - 6), 2)


# --- Volatile: walks in, goes off like a bomb --------------------------------
class Volatile(Enemy):
    SPRITE = "volatile"
    NAME = "Volatile"
    DESC = "Detonates violently when killed. Mind the blast."
    COLOR = (232, 138, 52)
    W, H = 26, 28
    BASE_HP = 46.0
    BASE_SPEED = 84.0
    BASE_DAMAGE = 8.0
    ATTACK_RATE = 1.1
    GOLD = 16
    MASS = 1.1
    BLAST_RADIUS = 132.0
    BLAST_DAMAGE = 3.4        # multiple of its own contact damage

    def __init__(self, game, wave, x=None, y=None):
        super().__init__(game, wave, x, y)
        self.fuse = random.uniform(0.0, 6.28)

    def die(self, silent=False):
        was_alive = self.alive
        super().die(silent)
        if was_alive:
            self.detonate()

    def detonate(self):
        g = self.game
        dmg = self.damage * self.BLAST_DAMAGE
        g.add_shake(7.0)
        g.effects.ring(self.x, self.y, 26, (255, 190, 90),
                       speed=self.BLAST_RADIUS * 3.4, life=0.45, size=6)
        g.effects.burst(self.x, self.y, 34, (255, 140, 60), speed=380,
                        life=0.7, size=5)
        g.effects.text(self.x, self.y - self.h, "BOOM!", (255, 170, 80), 26)
        for o in list(g.enemies):
            if o is self or not o.alive:
                continue
            d = math.hypot(o.x - self.x, o.y - self.y)
            if d <= self.BLAST_RADIUS:
                o.take_damage(dmg * (1.0 - 0.5 * d / self.BLAST_RADIUS),
                              "explosive")
        # it will happily take the wall with it
        gap = self.x - g.castle.front_x
        if 0 <= gap <= self.BLAST_RADIUS:
            g.castle.take_damage(dmg * (1.0 - 0.5 * gap / self.BLAST_RADIUS))
        if g.barricade.alive and abs(self.x - g.barricade.x) <= self.BLAST_RADIUS:
            g.barricade.take_damage(dmg)

    def think(self, dt):
        self.fuse += dt * 7.0
        super().think(dt)

    def draw_body(self, surf):
        if self.blit_sprite(surf):
            return
        r = self.hit_rect
        col = self.body_color()
        pulse = 0.5 + 0.5 * math.sin(self.fuse)
        self._legs(surf, shade(col, 0.6))
        glow = pygame.Surface((r.w * 3, r.h * 3), pygame.SRCALPHA)
        pygame.draw.circle(glow, (255, 150, 60, int(60 + 60 * pulse)),
                           (r.w * 3 // 2, r.h * 3 // 2), int(r.w * 0.9))
        surf.blit(glow, (r.centerx - r.w * 1.5, r.centery - r.h * 1.5))
        pygame.draw.circle(surf, mix(col, (255, 240, 180), pulse * 0.55),
                           (r.centerx, r.centery), r.w // 2)
        pygame.draw.circle(surf, shade(col, 0.5), (r.centerx, r.centery),
                           r.w // 2, 2)
        pygame.draw.circle(surf, (40, 30, 24), (r.centerx - 4, r.centery - 3), 2)
        pygame.draw.circle(surf, (40, 30, 24), (r.centerx + 4, r.centery - 3), 2)
        # sputtering fuse
        fx, fy = r.centerx + 6, r.top - 4
        pygame.draw.line(surf, (90, 74, 58), (r.centerx, r.top + 2), (fx, fy), 2)
        pygame.draw.circle(surf, (255, 226, 130), (fx, int(fy - pulse * 2)),
                           2 + int(pulse * 2))


# --- Treasure Goblin: catch it before it gets away ---------------------------
class TreasureGoblin(Enemy):
    SPRITE = "treasure_goblin"
    NAME = "Treasure Goblin"
    DESC = "Flees with a sack of gold. Kill it before it escapes!"
    COLOR = (218, 176, 60)
    W, H = 24, 30
    BASE_HP = 70.0
    BASE_SPEED = 104.0
    BASE_DAMAGE = 0.0
    GOLD = 140
    MASS = 0.9

    def __init__(self, game, wave, x=None, y=None):
        if x is None:
            x = random.uniform(720, 1040)      # starts out in the open field
        super().__init__(game, wave, x, y)
        self.escape_timer = 11.0
        self.hop = random.uniform(0, 6.28)

    def think(self, dt):
        """It never attacks -- it just legs it for the edge of the map."""
        self.anim += dt * self.speed * 0.09
        self.hop += dt * 11.0
        self.state = "walk"
        self.x += self.speed * dt          # runs away from the castle
        self.vx_estimate = self.speed
        self.escape_timer -= dt
        if self.escape_timer <= 0 or self.x > WIDTH + 90:
            self.escape()

    def escape(self):
        if not self.alive:
            return
        self.game.effects.text(min(self.x, WIDTH - 80), self.y - self.h,
                               "ESCAPED!", (200, 190, 160), 24)
        self.die(silent=True)              # no reward for letting it go

    def draw_body(self, surf):
        if self.blit_sprite(surf):
            return
        r = self.hit_rect
        col = self.body_color()
        bounce = abs(math.sin(self.hop)) * 4
        r = r.move(0, -bounce)
        self._legs(surf, (150, 120, 40))
        pygame.draw.ellipse(surf, col, (r.x, r.y + 8, r.w, r.h - 10))
        pygame.draw.circle(surf, (150, 200, 130), (r.centerx, r.y + 7), 7)
        pygame.draw.circle(surf, (30, 40, 30), (r.centerx - 3, r.y + 6), 2)
        pygame.draw.circle(surf, (30, 40, 30), (r.centerx + 3, r.y + 6), 2)
        # the sack, slung over one shoulder
        sack = pygame.Rect(r.right - 4, r.y + 4, 18, 18)
        pygame.draw.ellipse(surf, (196, 156, 48), sack)
        pygame.draw.ellipse(surf, (120, 92, 24), sack, 2)
        pygame.draw.line(surf, (120, 92, 24), (sack.centerx, sack.top),
                         (sack.centerx, sack.top - 4), 3)
        for k in range(3):
            pygame.draw.circle(surf, (255, 226, 120),
                               (sack.centerx - 4 + k * 4, sack.centery + 2), 2)
        draw_text(surf, f"{int(self.escape_timer)}s", self.x, r.top - 18, 15,
                  (240, 214, 130), "center", True)


# ------------------------------------------------------------------------------
# Bosses
# ------------------------------------------------------------------------------

class Boss(Enemy):
    IS_BOSS = True
    GRABBABLE = False
    HINT = ""      # shown in the shop as pre-wave advice

    def __init__(self, game, wave, x=None, y=None):
        super().__init__(game, wave, x, y)
        self.intro = 1.6
        self.aura = 0.0
        # interval multiplier for every projectile a boss throws: Hard sets
        # this to 0.5, which is literally twice the rate of fire
        self.fire_scale = getattr(game, "boss_fire_scale", 1.0)

    def fire_delay(self, seconds):
        """Scale a reload time by the difficulty's boss fire rate."""
        return seconds * self.fire_scale

    def take_damage(self, amount, kind="projectile"):
        dealt = super().take_damage(amount, kind)
        if dealt > 0:
            self.aura = 1.0
        return dealt

    def update(self, dt):
        self.aura = max(0.0, self.aura - dt * 3.0)
        super().update(dt)

    def draw(self, surf):
        if self.aura > 0:
            glow = pygame.Surface((int(self.w) + 40, int(self.h) + 40),
                                  pygame.SRCALPHA)
            pygame.draw.ellipse(glow, (255, 120, 90, int(70 * self.aura)),
                                glow.get_rect())
            surf.blit(glow, (self.x - self.w / 2 - 20, self.y - self.h / 2 - 20))
        super().draw(surf)
        draw_text(surf, self.NAME, self.x, self.y - self.h / 2 - 34, 22,
                  (255, 208, 120), "center", True)


# --- Boss 1: The Troll King (wave 5) ------------------------------------------
class TrollKing(Boss):
    SPRITE = "troll_king"
    NAME = "The Troll King"
    DESC = "Smashes walls and flattens your defences."
    COLOR = (108, 156, 92)
    W, H = 84, 112
    BASE_HP = 1150.0
    BASE_SPEED = 34.0
    BASE_DAMAGE = 42.0
    ATTACK_RATE = 2.2
    GOLD = 320
    ARMOR = 0.25
    MASS = 12.0
    HINT = "Drag the CROWN off his head! He must go and fetch it before "\
           "he can attack again."


    def __init__(self, game, wave, x=None, y=None):
        super().__init__(game, wave, x, y)
        self.leap_timer = random.uniform(4.0, 6.0)
        self.smash = 0.0
        self.has_crown = True
        self.crown_item = None

    def regalia_anchor(self):
        r = self.hit_rect
        return (r.centerx, r.y + 2)

    def regalia_rect(self):
        if not self.has_crown or not self.alive or self.regalia_cd > 0:
            return None
        cx, cy = self.regalia_anchor()
        return pygame.Rect(cx - 26, cy - 20, 52, 34)

    def detach_regalia(self):
        if not self.has_crown:
            return None
        cx, cy = self.regalia_anchor()
        self.has_crown = False
        self.regalia_taken += 1
        self.crown_item = DroppedItem(self.game, self, "crown", cx, cy)
        g = self.game
        g.effects.ring(cx, cy, 18, C_GOLD, speed=280, life=0.5, size=4)
        g.effects.text(self.x, self.y - self.h * 0.7, "MY CROWN!",
                       (255, 210, 120), 26)
        g.announce("The Troll King is uncrowned!", C_GOLD, 2.2)
        return self.crown_item

    def retrieve_crown(self, dt):
        """Uncrowned: he abandons the castle and stamps off after his crown."""
        self.state = "retrieve"
        crown = self.crown_item
        if crown is None or not crown.alive:
            self.has_crown = True          # nothing to fetch; put it back on
            self.guard_regalia()
            self.crown_item = None
            return
        self.anim += dt * self.speed * 0.06
        dx = crown.x - self.x
        step = self.speed * CROWN_RETRIEVE_SPEED * dt
        if abs(dx) > 6:
            self.x += clamp(dx, -step, step)
            self.vx_estimate = math.copysign(
                self.speed * CROWN_RETRIEVE_SPEED, dx)
        if crown.state == "ground" and abs(dx) < 46:
            self.has_crown = True
            self.guard_regalia()
            crown.kill()
            self.crown_item = None
            self.game.effects.ring(self.x, self.y - self.h * 0.5, 20, C_GOLD,
                                   speed=300, life=0.5, size=4)
            self.game.effects.text(self.x, self.y - self.h * 0.8, "CROWNED!",
                                   (255, 214, 120), 24)

    def think(self, dt):
        self.smash = max(0.0, self.smash - dt * 2.5)
        if not self.has_crown:
            self.retrieve_crown(dt)        # no attacking until he is crowned
            return
        self.leap_timer -= dt
        if self.leap_timer <= 0 and self.x > CASTLE_FRONT + 200:
            self.leap_timer = random.uniform(5.0, 7.5)
            self.x -= 170
            self.game.add_shake(10.0)
            self.game.effects.ring(self.x, GROUND_Y, 26, (150, 190, 120),
                                   speed=420, life=0.5, size=5)
            self.game.effects.text(self.x, self.y - self.h, "LEAP!",
                                   (180, 240, 150), 26)
        super().think(dt)

    def attack_castle(self):
        self.smash = 1.0
        c = self.game.castle
        c.take_damage(self.damage)
        c.smash_random_tower(self.damage * 0.5, stun=1.0)
        self.game.add_shake(14.0)
        self.game.effects.ring(c.front_x, self.y + 20, 24, (210, 190, 160),
                               speed=460, life=0.55, size=6)
        self.game.effects.text(c.front_x + 40, WALL_TOP - 20, "CRUNCH!",
                               (255, 150, 110), 30)

    def draw_body(self, surf):
        if self.blit_sprite(surf):
            return
        r = self.hit_rect
        col = self.body_color()
        self._legs(surf, shade(col, 0.65), span=20, length=16)
        pygame.draw.ellipse(surf, col, (r.x, r.y + 24, r.w, r.h - 26))
        pygame.draw.ellipse(surf, shade(col, 0.55), (r.x, r.y + 24, r.w, r.h - 26), 4)
        pygame.draw.ellipse(surf, shade(col, 1.2),
                            (r.centerx - 22, r.y + 10, 44, 40))
        # eyes + tusks
        pygame.draw.circle(surf, (250, 220, 90), (r.centerx - 9, r.y + 26), 5)
        pygame.draw.circle(surf, (250, 220, 90), (r.centerx + 9, r.y + 26), 5)
        pygame.draw.circle(surf, (30, 26, 20), (r.centerx - 9, r.y + 26), 2)
        pygame.draw.circle(surf, (30, 26, 20), (r.centerx + 9, r.y + 26), 2)
        for s in (-1, 1):
            pygame.draw.polygon(surf, (238, 236, 220), [
                (r.centerx + s * 12, r.y + 38), (r.centerx + s * 8, r.y + 38),
                (r.centerx + s * 10, r.y + 28)])
        # crown -- gone while the player has knocked it off
        if self.has_crown:
            pygame.draw.polygon(surf, C_GOLD, [
                (r.centerx - 20, r.y + 12), (r.centerx + 20, r.y + 12),
                (r.centerx + 16, r.y - 4), (r.centerx + 8, r.y + 6),
                (r.centerx, r.y - 8), (r.centerx - 8, r.y + 6),
                (r.centerx - 16, r.y - 4)])
        else:
            draw_text(surf, "RETRIEVING CROWN", self.x, r.y - 54, 18,
                      (255, 200, 120), "center", True)
        # club, swinging on the smash
        ang = -0.9 + self.smash * 2.2
        hx = r.left + 6
        hy = r.centery
        ex = hx + math.cos(math.pi - ang) * 54
        ey = hy + math.sin(math.pi - ang) * 54 - 10
        pygame.draw.line(surf, (110, 84, 54), (hx, hy), (ex, ey), 10)
        pygame.draw.circle(surf, (92, 70, 46), (int(ex), int(ey)), 16)
        pygame.draw.circle(surf, (140, 110, 74), (int(ex), int(ey)), 16, 3)


# --- Boss 2: The Dragon (wave 10) ---------------------------------------------
class Dragon(Boss):
    SPRITE = "dragon"
    NAME = "The Dragon"
    DESC = "Airborne. Breathes searing AoE fire."
    COLOR = (198, 62, 58)
    W, H = 118, 62
    BASE_HP = 1200.0
    BASE_SPEED = 66.0
    BASE_DAMAGE = 34.0
    ATTACK_RATE = 2.6
    BREATH_TIME = 1.25       # seconds of sustained breath
    SHOT_INTERVAL = 0.15     # seconds between fireballs while breathing
    BREATH_POWER = 0.34      # each breath bolt is a fraction of a full hit
    GOLD = 520
    ARMOR = 0.15
    MASS = 14.0
    HINT = "FLYING - Ballistas hit it for +200%. Bowmen reach it too."
    FLYING = True
    FLY_Y = 210.0

    def __init__(self, game, wave, x=None, y=None):
        super().__init__(game, wave, x, y)
        self.breath_timer = self.fire_delay(4.0)
        self.breathing = 0.0
        self.shot_timer = 0.0
        self.standoff_x = CASTLE_FRONT + 330
        self.claw_progress = 0.0
        self.reel = 0.0

    def think(self, dt):
        self.anim += dt * 6.0
        self.bob += dt * 2.2
        if self.reel > 0:
            # knocked off its attack run: climbing and shaking it off
            self.reel -= dt
            self.state = "attack"
            self.y += clamp((self.fly_y - 60) - self.y, -170 * dt, 170 * dt)
            return
        target_y = self.fly_y + math.sin(self.bob) * 26
        self.y += clamp(target_y - self.y, -180 * dt, 180 * dt)

        if self.x > self.standoff_x:
            self.x -= self.speed * dt
            self.vx_estimate = -self.speed
            self.state = "walk"
            return

        self.state = "attack"
        # a sustained stream on a real timer -- never framerate dependent
        if self.breathing > 0:
            self.breathing -= dt
            self.shot_timer -= dt
            if self.shot_timer <= 0:
                self.shot_timer = self.fire_delay(self.SHOT_INTERVAL)
                self.spit_fire(self.BREATH_POWER)
            return

        self.breath_timer -= dt
        if self.breath_timer <= 0:
            self.breath_timer = self.fire_delay(random.uniform(4.4, 5.8))
            self.breathing = self.BREATH_TIME
            self.shot_timer = 0.0
            self.game.effects.text(self.x, self.y - self.h, "FIRE BREATH!",
                                   (255, 170, 80), 28)

    def regalia_anchor(self):
        r = self.hit_rect
        return (r.centerx - 6, r.bottom - 2)

    def smack_rect(self):
        if not self.alive or self.regalia_cd > 0 or self.reel > 0:
            return None
        r = self.hit_rect
        return pygame.Rect(r.centerx - 40, r.bottom - 18, 80, 40)

    def apply_smack(self, amount):
        """Batter the claws: enough punishment and the Dragon reels,
        cutting off its fire breath and driving it back."""
        if self.smack_rect() is None:
            return False
        self.claw_progress += amount / CLAW_SMACK_DISTANCE
        g = self.game
        if random.random() < 0.35:
            g.effects.burst(self.x + random.uniform(-30, 30), self.hit_rect.bottom,
                            2, (255, 200, 130), speed=110, life=0.25, size=2)
        if self.claw_progress < 1.0:
            return False
        self.claw_progress = 0.0
        self.regalia_taken += 1
        self.reel = CLAW_STAGGER
        self.breathing = 0.0            # breath is cut off mid-stream
        self.breath_timer = max(self.breath_timer, 2.0)
        self.vy_estimate = 0.0
        self.x += 120                   # driven back off the wall
        self.guard_regalia()
        g.add_shake(9.0)
        g.effects.ring(self.x, self.hit_rect.bottom, 20, (255, 190, 120),
                       speed=380, life=0.5, size=5)
        g.effects.text(self.x, self.y - self.h * 0.7, "CLAWS SMACKED!",
                       (255, 190, 120), 26)
        g.announce("The Dragon reels back!", (255, 180, 120), 2.0)
        return True

    def spit_fire(self, power=1.0):
        c = self.game.castle
        tx = c.front_x - random.uniform(10, 150)
        ty = random.uniform(WALL_TOP - 30, GROUND_Y - 20)
        mx, my = self.x - self.w / 2, self.y + 6
        dx, dy = tx - mx, ty - my
        t = clamp(abs(dx) / 460.0, 0.4, 1.6)
        vx = dx / t
        vy = (dy - 0.5 * GRAVITY * 0.35 * t * t) / t
        self.game.projectiles.append(Projectile(
            self.game, mx, my, vx, vy, "fire", self.damage * power,
            splash=92.0, grav=GRAVITY * 0.35, hostile=True, life=t + 1.2,
            stun=0.8, owner_uid=self.uid))
        self.game.effects.burst(mx, my, 8, (255, 180, 80), speed=200,
                                life=0.35, grav=-60)

    def draw_body(self, surf):
        if self.blit_sprite(surf):
            return
        r = self.hit_rect
        col = self.body_color()
        flap = math.sin(self.anim) * 26
        # wings behind the body
        for s in (-1, 1):
            pygame.draw.polygon(surf, shade(col, 0.62), [
                (r.centerx, r.centery - 6),
                (r.centerx + s * 30, r.centery - 52 + flap),
                (r.centerx + s * 66, r.centery - 16 + flap * 0.7),
                (r.centerx + s * 28, r.centery + 12)])
            pygame.draw.polygon(surf, shade(col, 0.4), [
                (r.centerx, r.centery - 6),
                (r.centerx + s * 30, r.centery - 52 + flap),
                (r.centerx + s * 66, r.centery - 16 + flap * 0.7),
                (r.centerx + s * 28, r.centery + 12)], 2)
        pygame.draw.ellipse(surf, col, (r.x + 20, r.y + 16, r.w - 30, r.h - 22))
        # tail
        pygame.draw.polygon(surf, col, [
            (r.right - 14, r.centery), (r.right + 34, r.centery - 16),
            (r.right + 30, r.centery + 6), (r.right - 14, r.centery + 12)])
        # neck + head
        hx, hy = r.left + 14, r.centery - 12
        pygame.draw.line(surf, col, (r.x + 34, r.centery + 4), (hx, hy), 16)
        pygame.draw.ellipse(surf, shade(col, 1.12), (hx - 22, hy - 12, 40, 24))
        pygame.draw.polygon(surf, (240, 220, 190), [
            (hx - 22, hy - 2), (hx - 36, hy + 2), (hx - 20, hy + 8)])
        pygame.draw.circle(surf, (255, 226, 90), (hx - 4, hy - 4), 4)
        pygame.draw.circle(surf, (30, 20, 16), (hx - 5, hy - 4), 2)
        # dorsal spines
        for i in range(5):
            sx = r.x + 34 + i * 14
            pygame.draw.polygon(surf, (250, 190, 90), [
                (sx, r.y + 18), (sx + 10, r.y + 18), (sx + 5, r.y + 4)])
        if self.breathing > 0:
            for i in range(6):
                fx = hx - 34 - i * 16
                fy = hy + 4 + math.sin(self.anim * 3 + i) * 6
                pygame.draw.circle(surf, (255, 170 - i * 12, 60),
                                   (int(fx), int(fy)), 10 - i)


# --- Boss 3: The Lich Lord (wave 15+) -----------------------------------------
class LichLord(Boss):
    SPRITE = "lich_lord"
    NAME = "The Lich Lord"
    DESC = "Summons endless dead and hurls death magic."
    COLOR = (120, 92, 190)
    W, H = 70, 100
    BASE_HP = 2900.0
    BASE_SPEED = 36.0
    BASE_DAMAGE = 24.0
    ATTACK_RATE = 1.6
    GOLD = 780
    ARMOR = 0.30
    MASS = 10.0
    HINT = "Flick the STAFF out of his hands to disarm him for 5s - no "\
           "bolts, no summons, no ward."


    def __init__(self, game, wave, x=None, y=None):
        super().__init__(game, wave, x, y)
        # kept inside the reach of a front-slot Bowman so the wave can
        # always be finished, whatever the player built
        self.standoff_x = CASTLE_FRONT + 400
        self.summon_timer = 3.0
        self.bolt_timer = self.fire_delay(2.0)
        self.phase_timer = 0.0
        self.shield = 0.0
        self.orb = 0.0
        self.has_staff = True
        self.staff_item = None
        self.disarm = 0.0

    def take_damage(self, amount, kind="projectile"):
        if self.shield > 0:
            amount *= 0.25
        return super().take_damage(amount, kind)

    def regalia_anchor(self):
        r = self.hit_rect
        return (r.right + 6, r.y - 16)

    def regalia_rect(self):
        if not self.has_staff or not self.alive or self.regalia_cd > 0:
            return None
        sx, sy = self.regalia_anchor()
        return pygame.Rect(sx - 20, sy - 20, 40, 62)

    def detach_regalia(self):
        if not self.has_staff:
            return None
        sx, sy = self.regalia_anchor()
        self.has_staff = False
        self.regalia_taken += 1
        self.disarm = STAFF_DISARM_TIME
        self.shield = 0.0                  # the ward drops with the staff
        self.staff_item = DroppedItem(self.game, self, "staff", sx, sy)
        g = self.game
        g.effects.ring(sx, sy, 22, (196, 150, 255), speed=340, life=0.6, size=5)
        g.effects.text(self.x, self.y - self.h * 0.7, "DISARMED!",
                       (208, 160, 255), 28)
        g.announce("The Lich Lord is disarmed!", (208, 160, 255), 2.2)
        return self.staff_item

    def recover_staff(self):
        """Focus regained -- the staff is magically recalled to his hand."""
        if self.staff_item is not None:
            self.game.effects.ring(self.staff_item.x, self.staff_item.y, 16,
                                   (196, 150, 255), speed=260, life=0.5, size=4)
            self.staff_item.kill()
            self.staff_item = None
        self.has_staff = True
        self.guard_regalia()
        self.orb = 1.0
        self.game.effects.text(self.x, self.y - self.h * 0.8, "REFOCUSED",
                               (208, 160, 255), 22)

    def think(self, dt):
        self.anim += dt * 2.0
        self.orb = max(0.0, self.orb - dt * 2.0)
        self.shield = max(0.0, self.shield - dt)

        # disarmed: no bolts, no summons, no ward -- just standing there
        if self.disarm > 0:
            self.disarm -= dt
            self.state = "attack"
            if self.disarm <= 0:
                self.recover_staff()
            return

        # halt in front of the outer barricade -- he will not advance on
        # the castle while a live wall stands between him and it
        if self.x > self.current_standoff():
            self.x -= self.speed * dt
            self.vx_estimate = -self.speed
            self.state = "walk"
            return
        self.state = "attack"

        self.summon_timer -= dt
        if self.summon_timer <= 0:
            self.summon_timer = max(3.2, 6.0 - self.wave * 0.06)
            self.raise_dead()

        self.bolt_timer -= dt
        if self.bolt_timer <= 0:
            self.bolt_timer = self.fire_delay(random.uniform(1.2, 2.0))
            self.death_bolt()

        self.phase_timer -= dt
        if self.phase_timer <= 0:
            self.phase_timer = random.uniform(9.0, 13.0)
            self.shield = 4.0
            self.game.effects.text(self.x, self.y - self.h, "BONE WARD",
                                   (170, 220, 255), 24)

    def current_standoff(self):
        """Where he halts.  While the outer barricade stands he stops in
        front of it -- he will not advance on the castle past a live wall."""
        bar = self.game.barricade
        if bar.alive:
            return max(self.standoff_x,
                       bar.x + bar.W / 2 + self.w / 2 + 18)
        return self.standoff_x

    def raise_dead(self):
        pool = self.game.summonable_types()
        count = 2 + min(4, self.wave // 8)
        for i in range(count):
            cls = random.choice(pool)
            e = cls(self.game, max(1, self.wave - 3),
                    x=self.x + random.uniform(-70, 90))
            if not e.flying:
                e.y = e.ground_y
            self.game.spawn_enemy(e)
            self.game.effects.ring(e.x, e.y, 12, (168, 120, 240),
                                   speed=180, life=0.45, size=4)
        self.orb = 1.0
        self.game.effects.text(self.x, self.y - self.h, "ARISE!",
                               (200, 150, 255), 26)

    def death_bolt(self):
        c = self.game.castle
        bar = self.game.barricade
        if bar.alive:
            # the outer wall is the target until it comes down
            tx = bar.x
            ty = random.uniform(bar.top_y + 12, GROUND_Y - 12)
        else:
            live = [t for t in c.towers if not t.disabled]
            if live and random.random() < 0.5:
                t = random.choice(live)
                tx, ty = t.x, t.y - t.H / 2
            else:
                tx, ty = c.front_x - 30, random.uniform(WALL_TOP + 20,
                                                        GROUND_Y - 30)
        a = math.atan2(ty - (self.y - 20), tx - self.x)
        speed = 520.0
        self.game.projectiles.append(Projectile(
            self.game, self.x, self.y - 20, math.cos(a) * speed,
            math.sin(a) * speed, "magic", self.damage, splash=54.0,
            hostile=True, life=4.0, owner_uid=self.uid))
        self.orb = 1.0

    def draw_body(self, surf):
        if self.blit_sprite(surf):
            return
        r = self.hit_rect
        col = self.body_color()
        if self.shield > 0:
            sh = pygame.Surface((r.w + 60, r.h + 60), pygame.SRCALPHA)
            pygame.draw.ellipse(sh, (150, 210, 255, 70), sh.get_rect())
            pygame.draw.ellipse(sh, (200, 235, 255, 150), sh.get_rect(), 3)
            surf.blit(sh, (r.centerx - (r.w + 60) // 2, r.centery - (r.h + 60) // 2))
        # robe
        hover = math.sin(self.anim) * 5
        pts = [(r.centerx, r.y + 6 + hover), (r.right, r.bottom + hover),
               (r.centerx, r.bottom - 10 + hover), (r.left, r.bottom + hover)]
        pygame.draw.polygon(surf, col, pts)
        pygame.draw.polygon(surf, shade(col, 0.5), pts, 3)
        # skull
        sx, sy = r.centerx, r.y + 16 + hover
        pygame.draw.circle(surf, (232, 230, 216), (sx, int(sy)), 15)
        pygame.draw.circle(surf, (24, 46, 40), (sx - 6, int(sy) - 2), 4)
        pygame.draw.circle(surf, (24, 46, 40), (sx + 6, int(sy) - 2), 4)
        pygame.draw.circle(surf, (120, 255, 210), (sx - 6, int(sy) - 2), 2)
        pygame.draw.circle(surf, (120, 255, 210), (sx + 6, int(sy) - 2), 2)
        for i in range(3):
            pygame.draw.line(surf, (60, 56, 50),
                             (sx - 5 + i * 5, sy + 8), (sx - 5 + i * 5, sy + 13), 2)
        # crown of spikes
        for i in range(-2, 3):
            pygame.draw.polygon(surf, (168, 140, 220), [
                (sx + i * 8 - 3, sy - 12), (sx + i * 8 + 3, sy - 12),
                (sx + i * 8, sy - 24)])
        # staff -- gone while disarmed
        if self.has_staff:
            stx = r.right + 6
            pygame.draw.line(surf, (76, 62, 96), (stx, r.bottom), (stx, r.y - 14), 5)
            gr = 10 + int(7 * self.orb)
            glow = pygame.Surface((gr * 4, gr * 4), pygame.SRCALPHA)
            pygame.draw.circle(glow, (170, 120, 255, 90), (gr * 2, gr * 2), gr * 2)
            surf.blit(glow, (stx - gr * 2, r.y - 16 - gr * 2))
            pygame.draw.circle(surf, (196, 150, 255), (stx, r.y - 16), gr)
            pygame.draw.circle(surf, (240, 220, 255), (stx, r.y - 16), max(2, gr - 4))
        else:
            draw_text(surf, f"DISARMED  {self.disarm:.1f}s", self.x,
                      r.y - 54, 20, (208, 160, 255), "center", True)
            for i in range(3):      # dazed sparks circling his head
                a = self.anim * 3 + i * 2.1
                pygame.draw.circle(surf, (180, 150, 240),
                                   (int(sx + math.cos(a) * 22),
                                    int(sy - 26 + math.sin(a) * 7)), 3)


# ------------------------------------------------------------------------------
# Wave composition

# ------------------------------------------------------------------------------

# (first wave the type appears on, class, budget weight)
UNLOCKS = [
    (1, Scout,        1.0),
    (2, FootSoldier,  1.5),
    (3, ShieldBearer, 2.6),
    (4, Berzerker,    2.0),
    (6, SiegeRam,     4.5),
    (7, Necromancer,  3.2),
    (8, Assassin,     2.8),
    (9, Gargoyle,     2.8),
    (11, Volatile,    2.2),
]

# Treasure Goblins are a bonus roll rather than part of the wave budget
GOBLIN_FROM_WAVE = 4
GOBLIN_CHANCE = 0.55

BOSS_ROTATION = [TrollKing, Dragon, LichLord]
MAX_ALIVE = 58


def boss_for_wave(wave):
    """Bosses at 5 / 10 / 15, then every fifth wave on rotation."""
    if wave <= 0 or wave % 5 != 0:
        return None
    idx = (wave // 5) - 1
    if idx < 3:
        return BOSS_ROTATION[idx]
    return BOSS_ROTATION[idx % 3]


def unlocked_types(wave):
    return [(cls, w) for (first, cls, w) in UNLOCKS if wave >= first]


def newly_unlocked(wave):
    return [cls for (first, cls, _) in UNLOCKS if first == wave]


def build_wave(wave):
    """Returns a list of enemy classes to spawn, in order."""
    pool = unlocked_types(wave)
    if not pool:
        pool = [(Scout, 1.0)]
    budget = 6.0 + wave * 3.1
    picks = []
    # guarantee a couple of any newly introduced type so the player meets it
    for cls in newly_unlocked(wave):
        for _ in range(2 if cls is not SiegeRam else 1):
            picks.append(cls)
            budget -= next(w for (c, w) in pool if c is cls)
    classes = [c for (c, _) in pool]
    weights = [1.0 / w for (_, w) in pool]      # cheap units appear more often
    guard = 0
    while budget > 0 and len(picks) < 70 and guard < 500:
        guard += 1
        cls = random.choices(classes, weights=weights, k=1)[0]
        cost = next(w for (c, w) in pool if c is cls)
        picks.append(cls)
        budget -= cost
    random.shuffle(picks)
    # a goblin may wander in with the wave
    if wave >= GOBLIN_FROM_WAVE and random.random() < GOBLIN_CHANCE:
        picks.insert(random.randint(0, max(0, len(picks) - 1)), TreasureGoblin)
    boss = boss_for_wave(wave)
    if boss is not None:
        # boss walks in a little after the vanguard
        picks.insert(min(len(picks), 3), boss)
        if wave >= 20:                      # late game: a second, older boss
            picks.append(BOSS_ROTATION[(wave // 5) % 3])
    return picks


# ------------------------------------------------------------------------------
