"""
Castle Defense -- everything the player builds and shoots with.

Projectiles, the auto-firing defence towers with their strategic damage
multipliers and manual slingshot overcharge, the castle itself with its six
visual wall tiers, the spiked parapet, the background Outpost and the outer
Barricade.

"""

import math
import random
from collections import deque

import pygame

from sprites import *  # noqa: F401,F403  (shared config + helpers)

class Projectile:
    """
    One class covers every flying thing.  `kind` drives the visuals and a few
    behavioural switches; `hostile` flips who it is allowed to hurt.

    kinds: 'arrow' | 'bolt' | 'cannon' | 'magic' | 'fire' | 'bone'
    """

    def __init__(self, game, x, y, vx, vy, kind, damage,
                 splash=0.0, pierce=0, grav=0.0, hostile=False,
                 color=None, life=5.0, stun=0.0,
                 bonus_air=1.0, bonus_heavy=1.0, at_prisoner=False,
                 owner_uid=0):
        self.game = game
        self.x, self.y = float(x), float(y)
        self.vx, self.vy = float(vx), float(vy)
        self.kind = kind
        self.damage = float(damage)
        self.splash = float(splash)
        self.pierce = int(pierce)
        self.grav = float(grav)
        self.hostile = bool(hostile)
        self.at_prisoner = bool(at_prisoner)   # a rival's shot at the cage
        # who fired it: lets the game drop a dead boss's shots along with the
        # boss itself, so nothing outlives its owner
        self.owner_uid = int(owner_uid)
        # counter multipliers travel with the shot and are resolved against
        # each victim individually
        self.bonus_air = float(bonus_air)
        self.bonus_heavy = float(bonus_heavy)
        t = game.talents
        self.crit_chance = t.crit_chance
        if splash:
            self.splash *= t.splash_mult
        self.life = float(life)
        self.stun = float(stun)
        self.alive = True
        self.hit_ids = set()
        self.trail = deque(maxlen=8)
        default = {
            "arrow": (232, 226, 200), "bolt": (250, 180, 96),
            "cannon": (60, 60, 68), "magic": (176, 110, 240),
            "fire": (255, 150, 50),
        }
        self.color = color or default.get(kind, C_WHITE)
        self.radius = {"arrow": 4, "bolt": 6, "cannon": 9,
                       "magic": 8, "fire": 11}.get(kind, 5)

    # -- helpers ---------------------------------------------------------
    @property
    def angle(self):
        return math.atan2(self.vy, self.vx)

    def kill(self):
        self.alive = False

    def damage_for(self, e):
        d = self.damage
        if e.flying:
            d *= self.bonus_air
        if e.HEAVY:
            d *= self.bonus_heavy
        if self.crit_chance and random.random() < self.crit_chance:
            d *= 3.0
            self.game.effects.text(e.x, e.y - e.h * 0.9, "CRIT!",
                                   (255, 226, 120), 22, 0.7)
        return d

    def explode(self):
        g = self.game
        if self.splash > 0:
            g.effects.ring(self.x, self.y, 18, shade(self.color, 1.3),
                           speed=self.splash * 3.2, life=0.35, size=4)
            g.effects.burst(self.x, self.y, 22, self.color,
                            speed=self.splash * 2.2, life=0.5, size=4)
            g.add_shake(min(9.0, self.splash * 0.08))
            if self.hostile:
                g.castle.splash_hit(self.x, self.y, self.splash, self.damage,
                                    stun=self.stun)
                bar = g.barricade
                if bar.alive:
                    gap = abs(self.x - bar.x)
                    if gap <= self.splash:
                        bar.take_damage(self.damage
                                        * (1.0 - 0.5 * gap / max(1.0, self.splash)))
            else:
                for e in g.enemies:
                    if not e.alive:
                        continue
                    d = math.hypot(e.x - self.x, e.y - self.y)
                    if d <= self.splash:
                        falloff = 1.0 - 0.55 * (d / max(1.0, self.splash))
                        e.take_damage(self.damage_for(e) * falloff, "explosive")
        else:
            g.effects.burst(self.x, self.y, 6, self.color, speed=140,
                            life=0.3, size=3)
        self.kill()

    # -- update ----------------------------------------------------------
    def update(self, dt):
        self.trail.append((self.x, self.y))
        self.vx += self.game.wind * WIND_PROJECTILE * dt
        self.vy += self.grav * dt
        self.x += self.vx * dt
        self.y += self.vy * dt
        self.life -= dt

        if (self.life <= 0 or self.x < -120 or self.x > WIDTH + 220
                or self.y > HEIGHT + 200 or self.y < -600):
            if self.splash > 0 and self.life <= 0:
                self.explode()
            else:
                self.kill()
            return

        if self.kind in ("cannon", "fire") and self.y >= GROUND_Y - 2:
            self.y = GROUND_Y - 2
            self.explode()
            return

        if self.hostile:
            self._update_hostile()
        else:
            self._update_friendly()

    def _update_friendly(self):
        px, py = self.x, self.y
        for e in self.game.enemies:
            # Bounds first, and without building a pygame.Rect: this runs once
            # per projectile per enemy per frame, and that allocation was the
            # single biggest cost in the profile at high waves.  Sized from the
            # enemy itself, so no unit can slip through a fixed band.
            if abs(px - e.x) > e.w * 0.5 or abs(py - e.y) > e.h * 0.5:
                continue
            if not e.alive or e.uid in self.hit_ids or not e.targetable:
                continue
            self.hit_ids.add(e.uid)
            if self.splash > 0:
                self.explode()
                return
            e.take_damage(self.damage_for(e), "projectile")
            self.game.effects.burst(px, py, 5, self.color,
                                    speed=120, life=0.25, size=2)
            if self.pierce > 0:
                self.pierce -= 1
                self.damage *= 0.72
            else:
                self.kill()
            return

    def _update_hostile(self):
        # a rival Necromancer's bolt ignores the walls -- it wants the cage
        if self.at_prisoner:
            post = self.game.outpost
            if not post.has_prisoner:
                self.kill()
                return
            if post.body_rect.collidepoint(self.x, self.y):
                post.hurt_prisoner(self.damage)
                self.game.effects.burst(self.x, self.y, 8, self.color,
                                        speed=170, life=0.35, size=3)
                self.kill()
            return

        castle = self.game.castle
        # the outer barricade is the first thing in the way
        bar = self.game.barricade
        if (bar.alive and self.y >= bar.top_y
                and abs(self.x - bar.x) <= bar.W / 2 + self.radius):
            if self.splash > 0:
                self.explode()
            else:
                bar.take_damage(self.damage)
                self.game.effects.burst(self.x, self.y, 6, self.color,
                                        speed=150, life=0.3, size=3)
                self.kill()
            return
        tower = castle.tower_at(self.x, self.y)
        if tower is not None:
            if self.splash > 0:
                self.explode()
            else:
                tower.take_damage(self.damage)
                self.game.effects.burst(self.x, self.y, 6, self.color,
                                        speed=150, life=0.3, size=3)
                self.kill()
            return
        if self.x <= castle.front_x and self.y >= WALL_TOP - 30:
            if self.splash > 0:
                self.explode()
            else:
                castle.take_damage(self.damage)
                self.game.effects.burst(self.x, self.y, 8, self.color,
                                        speed=170, life=0.35, size=3)
                self.kill()
            return
        if self.x <= castle.keep_right and self.y >= KEEP_TOP - 26:
            if self.splash > 0:
                self.explode()
            else:
                castle.take_damage(self.damage)
                self.kill()
            return

    # -- draw ------------------------------------------------------------
    def draw(self, surf):
        x, y = int(self.x), int(self.y)
        if self.kind == "arrow":
            a = self.angle
            tail = (x - math.cos(a) * 16, y - math.sin(a) * 16)
            pygame.draw.line(surf, self.color, (x, y), tail, 2)
            pygame.draw.circle(surf, (250, 250, 235), (x, y), 2)
        elif self.kind == "bolt":
            a = self.angle
            tail = (x - math.cos(a) * 26, y - math.sin(a) * 26)
            pygame.draw.line(surf, shade(self.color, 0.6), (x, y), tail, 5)
            pygame.draw.line(surf, self.color, (x, y), tail, 2)
            pygame.draw.circle(surf, (255, 236, 190), (x, y), 3)
        elif self.kind == "cannon":
            for i, (tx, ty) in enumerate(self.trail):
                r = max(1, int(self.radius * (i / max(1, len(self.trail)))))
                pygame.draw.circle(surf, (90, 88, 96), (int(tx), int(ty)), r)
            pygame.draw.circle(surf, (36, 36, 42), (x, y), self.radius)
            pygame.draw.circle(surf, (120, 120, 130), (x - 2, y - 3), 3)
        elif self.kind in ("magic", "fire"):
            glow = pygame.Surface((self.radius * 6, self.radius * 6),
                                  pygame.SRCALPHA)
            pygame.draw.circle(glow, (*self.color, 70),
                               (self.radius * 3, self.radius * 3), self.radius * 3)
            pygame.draw.circle(glow, (*self.color, 140),
                               (self.radius * 3, self.radius * 3), self.radius * 2)
            surf.blit(glow, (x - self.radius * 3, y - self.radius * 3))
            pygame.draw.circle(surf, shade(self.color, 1.5), (x, y),
                               max(2, self.radius - 3))
        else:  # generic fallback shot
            pygame.draw.circle(surf, self.color, (x, y), self.radius)
            pygame.draw.circle(surf, (140, 140, 130), (x, y), self.radius, 1)


# ------------------------------------------------------------------------------
# Boss regalia the player can pull off
# ------------------------------------------------------------------------------


class DefenseTower:
    """
    Base class for the auto-firing castle defences.  Subclasses override the
    stat block and `fire()`.
    """
    NAME = "Tower"
    COLOR = (150, 150, 160)
    RANGE = 400.0
    COOLDOWN = 1.0
    DAMAGE = 10.0
    HITS_AIR = True
    MAX_HP = 100.0
    W, H = 26, 34

    # --- strategic counter system -------------------------------------
    # A multiplier of 3.0 is "+200% bonus damage" against that class of
    # target.  Applied per-target at the moment of impact, so a cannon's
    # splash can crit a Siege Ram while only tickling the scouts beside it.
    BONUS_VS_AIR = 1.0
    BONUS_VS_HEAVY = 1.0
    # Vertical reach multiplier: >1 stretches the range envelope upwards so
    # the tower can engage flyers well above its own altitude.
    AIR_RANGE_MULT = 1.0
    COUNTER_TAG = ""          # short label drawn on the shop card
    OVERCHARGEABLE = False    # can the player slingshot-fire this by hand?
    SPRITE = None             # assets/<name>.png overrides the drawn version

    def __init__(self, game, x, y):
        self.game = game
        self.x, self.y = float(x), float(y)   # anchor = base of the tower
        # stats live on the instance so the shop can upgrade them
        self.damage = self.DAMAGE
        self.reload = self.COOLDOWN
        self.range = self.RANGE
        self.splash = getattr(self, "SPLASH", 0.0)
        self.cooldown = random.uniform(0.0, 0.4)
        self.hp = float(self.MAX_HP)
        self.max_hp = float(self.MAX_HP)
        self.disabled = False
        self.rebuild = 0.0        # seconds until a downed tower comes back
        self.stun = 0.0
        self.aim = -0.35          # rendered barrel angle
        self.recoil = 0.0
        self.level = 1
        self.overcharge_cd = 0.0

    # -- geometry --------------------------------------------------------
    @property
    def muzzle(self):
        return (self.x, self.y - self.H + 6)

    @property
    def rect(self):
        return pygame.Rect(int(self.x - self.W / 2), int(self.y - self.H),
                           self.W, self.H)

    # -- combat ----------------------------------------------------------
    REGEN = 0.05          # fraction of max HP repaired per second
    REBUILD_TIME = 9.0    # a downed tower is rebuilt rather than lost forever

    def take_damage(self, amount):
        if self.disabled:
            return
        # no single blow may erase a tower outright -- crews always get a
        # chance to react, which keeps boss smashes scary but not unwinnable
        self.hp -= min(amount, self.max_hp * 0.42)
        self.game.effects.burst(self.x, self.y - self.H * 0.6, 6,
                                (190, 170, 140), speed=150, life=0.35)
        if self.hp <= 0:
            self.hp = 0.0
            self.disabled = True
            self.rebuild = self.REBUILD_TIME * self.game.talents.rebuild_mult
            self.game.effects.burst(self.x, self.y - self.H * 0.5, 24,
                                    (120, 110, 100), speed=250, life=0.7, size=4)
            self.game.effects.text(self.x, self.y - self.H - 12,
                                   f"{self.NAME} down!", C_RED, 18)

    def restore(self):
        self.hp = self.max_hp
        self.disabled = False
        self.rebuild = 0.0
        self.stun = 0.0

    def reach_to(self, e):
        """Distance to a target, measured in the tower's own range envelope.

        Anything above the muzzle has its vertical offset divided by
        AIR_RANGE_MULT, which stretches the envelope upwards -- that is the
        Bowmen's air-range buff, and it is what stops flyers parking just
        out of reach."""
        mx, my = self.muzzle
        dx = e.x - mx
        dy = e.y - my
        if dy < 0 and self.AIR_RANGE_MULT > 1.0:
            dy /= self.AIR_RANGE_MULT
        return math.hypot(dx, dy)

    def damage_vs(self, e):
        """This tower's damage against one specific target."""
        m = self.game.talents.tower_damage
        if e.flying:
            m *= self.BONUS_VS_AIR
        if e.HEAVY:
            m *= self.BONUS_VS_HEAVY
        return self.damage * m

    def pick_target(self):
        best, best_score = None, None
        for e in self.game.enemies:
            if not e.alive or not e.targetable:
                continue
            if e.flying and not self.HITS_AIR:
                continue
            d = self.reach_to(e)
            if d > self.range:
                continue
            score = self.score_target(e, d)
            if best_score is None or score < best_score:
                best, best_score = e, score
        return best

    def score_target(self, enemy, dist):
        # by default prefer whatever this tower is a hard counter to,
        # then whoever is closest to the gate
        prio = 0
        if enemy.flying and self.BONUS_VS_AIR > 1.0:
            prio = -1
        if enemy.HEAVY and self.BONUS_VS_HEAVY > 1.0:
            prio = -1
        return prio * 100000 + enemy.x

    def update(self, dt):
        self.recoil = max(0.0, self.recoil - dt * 5.0)
        self.overcharge_cd = max(0.0, self.overcharge_cd - dt)
        if self.disabled:
            self.rebuild -= dt
            if self.rebuild <= 0:
                self.disabled = False
                self.hp = self.max_hp * 0.5
                self.game.effects.text(self.x, self.y - self.H - 12,
                                       "rebuilt", C_GREEN, 16)
            return
        self.hp = min(self.max_hp, self.hp + self.max_hp * self.REGEN * dt)
        if self.stun > 0:
            self.stun -= dt
            return
        self.cooldown -= dt
        target = self.pick_target()
        if target is not None:
            mx, my = self.muzzle
            self.aim = lerp(self.aim,
                            math.atan2(target.y - my, target.x - mx), 0.25)
        if self.cooldown <= 0 and target is not None:
            self.cooldown = self.reload * self.game.talents.tower_rate
            self.recoil = 1.0
            self.fire(target)

    def upgrade(self):
        """Bought again with no wall space free -> the existing guns get better."""
        self.level += 1
        self.damage *= 1.35
        self.reload *= 0.90
        self.range *= 1.04
        if self.splash:
            self.splash *= 1.08
        self.max_hp *= 1.15
        self.hp = self.max_hp
        return self.level

    @property
    def can_overcharge(self):
        return (self.OVERCHARGEABLE and not self.disabled
                and self.stun <= 0 and self.overcharge_cd <= 0)

    def overcharge_fire(self, ax, ay, power):
        """Hand-fired slingshot shot: aimed by dragging back from the tower,
        released to launch instantly regardless of the reload timer."""
        mx, my = self.muzzle
        dx, dy = mx - ax, my - ay          # fires opposite the draw-back
        d = math.hypot(dx, dy)
        if d < 12:
            return False
        self.overcharge_cd = OVERCHARGE_COOLDOWN * self.game.talents.overcharge_cd
        self.cooldown = self.reload
        self.recoil = 1.0
        self.aim = math.atan2(dy, dx)
        self.launch_overcharged(dx / d, dy / d, power)
        g = self.game
        g.add_shake(6.0 * power)
        g.effects.ring(mx, my, 16, (255, 226, 150),
                       speed=320 * power, life=0.4, size=4)
        g.effects.text(self.x, self.y - self.H - 20, "OVERCHARGE!",
                       (255, 214, 120), 22)
        return True

    def launch_overcharged(self, dirx, diry, power):
        raise NotImplementedError

    def fire(self, target):
        raise NotImplementedError

    def lead_target(self, target, speed):
        """Simple first-order intercept so shots do not lag behind runners."""
        mx, my = self.muzzle
        dx, dy = target.x - mx, target.y - my
        d = math.hypot(dx, dy)
        t = d / max(1.0, speed)
        px = target.x + target.vx_estimate * t
        py = target.y + target.vy_estimate * t
        return px, py

    # -- draw ------------------------------------------------------------
    def draw(self, surf):
        raise NotImplementedError

    def blit_sprite(self, surf):
        """Use supplied artwork for this emplacement, if any."""
        if not self.SPRITE or self.disabled:
            return False
        return blit_asset(surf, self.SPRITE, self.rect)

    def draw_status(self, surf):
        if self.disabled:
            draw_text(surf, "X", self.x, self.y - self.H - 18, 22, C_RED,
                      "center", True)
        elif self.hp < self.max_hp:
            draw_bar(surf, int(self.x - 14), int(self.y - self.H - 10),
                     28, 4, self.hp / self.max_hp, C_GREEN)


class Bowman(DefenseTower):
    SPRITE = "bowman"
    NAME = "Bowmen"
    COLOR = (118, 186, 108)
    RANGE = 430.0
    COOLDOWN = 0.50
    DAMAGE = 11.0
    HITS_AIR = True
    AIR_RANGE_MULT = 1.9      # reaches far higher than it does far
    MAX_HP = 70.0
    W, H = 22, 32
    COUNTER_TAG = "High air reach"

    def fire(self, target):
        mx, my = self.muzzle
        speed = 880.0
        px, py = self.lead_target(target, speed)
        a = math.atan2(py - my, px - mx)
        self.game.projectiles.append(Projectile(
            self.game, mx, my, math.cos(a) * speed, math.sin(a) * speed,
            "arrow", self.damage_vs(target)))

    def draw(self, surf):
        if self.blit_sprite(surf):
            self.draw_status(surf)
            return
        r = self.rect
        body = (70, 62, 52) if self.disabled else (96, 84, 66)
        pygame.draw.rect(surf, body, r, border_radius=4)
        pygame.draw.rect(surf, shade(body, 0.6), r, 2, border_radius=4)
        if self.disabled:
            return
        # archer figure
        cx = int(self.x)
        head_y = int(self.y - self.H - 6)
        pygame.draw.circle(surf, (226, 190, 150), (cx, head_y), 5)
        pygame.draw.rect(surf, self.COLOR, (cx - 5, head_y + 4, 10, 12),
                         border_radius=3)
        # bow arc following the aim
        bx = cx + math.cos(self.aim) * 12
        by = head_y + 6 + math.sin(self.aim) * 12
        pygame.draw.arc(surf, (200, 170, 110),
                        pygame.Rect(int(bx - 9), int(by - 11), 18, 22),
                        self.aim - 1.1, self.aim + 1.1, 2)


class Ballista(DefenseTower):
    SPRITE = "ballista"
    NAME = "Ballista"
    COLOR = (206, 150, 84)
    RANGE = 640.0
    COOLDOWN = 2.5
    DAMAGE = 62.0
    HITS_AIR = True
    AIR_RANGE_MULT = 1.5
    BONUS_VS_AIR = 3.0        # +200% damage to flyers
    MAX_HP = 120.0
    W, H = 34, 30
    COUNTER_TAG = "+200% vs FLYING"
    OVERCHARGEABLE = True

    def launch_overcharged(self, dirx, diry, power):
        mx, my = self.muzzle
        speed = 1150.0 * OVERCHARGE_SPEED
        self.game.projectiles.append(Projectile(
            self.game, mx, my, dirx * speed, diry * speed, "bolt",
            self.damage * (1.0 + (OVERCHARGE_DAMAGE - 1.0) * power),
            pierce=4, color=(255, 226, 150),
            bonus_air=self.BONUS_VS_AIR, bonus_heavy=self.BONUS_VS_HEAVY))

    def score_target(self, enemy, dist):
        # flyers first (it is the anti-air gun), then the beefiest target
        return (0 if enemy.flying else 1, -enemy.hp)

    def fire(self, target):
        mx, my = self.muzzle
        speed = 1150.0
        px, py = self.lead_target(target, speed)
        a = math.atan2(py - my, px - mx)
        self.game.projectiles.append(Projectile(
            self.game, mx, my, math.cos(a) * speed, math.sin(a) * speed,
            "bolt", self.damage * self.game.talents.tower_damage,
            pierce=2 + self.game.talents.extra_pierce,
            bonus_air=self.BONUS_VS_AIR, bonus_heavy=self.BONUS_VS_HEAVY))
        self.game.add_shake(1.5)

    def draw(self, surf):
        if self.blit_sprite(surf):
            self.draw_status(surf)
            return
        r = self.rect
        base = (74, 60, 44) if self.disabled else (104, 82, 58)
        pygame.draw.rect(surf, base, (r.x, r.y + 12, r.w, r.h - 12),
                         border_radius=3)
        pygame.draw.rect(surf, shade(base, 0.6), (r.x, r.y + 12, r.w, r.h - 12),
                         2, border_radius=3)
        if self.disabled:
            return
        px = self.x - math.cos(self.aim) * self.recoil * 6
        py = self.y - self.H + 8 - math.sin(self.aim) * self.recoil * 6
        # limbs
        perp = self.aim + math.pi / 2
        for s in (-1, 1):
            ex = px + math.cos(perp) * 13 * s + math.cos(self.aim) * 4
            ey = py + math.sin(perp) * 13 * s + math.sin(self.aim) * 4
            pygame.draw.line(surf, (150, 120, 80), (px, py), (ex, ey), 3)
        # stock
        pygame.draw.line(surf, self.COLOR, (px, py),
                         (px + math.cos(self.aim) * 22,
                          py + math.sin(self.aim) * 22), 4)


class Cannon(DefenseTower):
    SPRITE = "cannon"
    NAME = "Cannon"
    COLOR = (92, 96, 110)
    RANGE = 600.0
    COOLDOWN = 3.1
    DAMAGE = 40.0
    SPLASH = 86.0
    HITS_AIR = False          # the lobbed shell cannot lead a flyer
    BONUS_VS_HEAVY = 3.0      # +200% damage to Siege Rams and other tanks
    MAX_HP = 140.0
    W, H = 36, 28
    COUNTER_TAG = "+200% vs HEAVY"
    OVERCHARGEABLE = True

    def launch_overcharged(self, dirx, diry, power):
        mx, my = self.muzzle
        speed = 900.0 * OVERCHARGE_SPEED
        self.game.projectiles.append(Projectile(
            self.game, mx, my, dirx * speed, diry * speed, "cannon",
            self.damage * (1.0 + (OVERCHARGE_DAMAGE - 1.0) * power),
            splash=self.splash * (1.0 + (OVERCHARGE_SPLASH - 1.0) * power),
            grav=GRAVITY * 0.55, life=4.0, color=(255, 190, 120),
            bonus_air=self.BONUS_VS_AIR, bonus_heavy=self.BONUS_VS_HEAVY))

    def score_target(self, enemy, dist):
        # a heavy in range always wins; otherwise hit the densest cluster
        if enemy.HEAVY:
            return -1000000 + dist
        n = 0
        for o in self.game.enemies:
            if o.alive and o.targetable and not o.flying:
                if abs(o.x - enemy.x) < self.splash and abs(o.y - enemy.y) < self.splash:
                    n += 1
        return -(n * 1000) + dist

    def fire(self, target):
        mx, my = self.muzzle
        # ballistic solution for a fixed flight time
        px, py = self.lead_target(target, 700.0)
        dx, dy = px - mx, py - my
        t = clamp(abs(dx) / 620.0, 0.35, 1.5)
        vx = dx / t
        vy = (dy - 0.5 * GRAVITY * t * t) / t
        self.game.projectiles.append(Projectile(
            self.game, mx, my, vx, vy, "cannon",
            self.damage * self.game.talents.tower_damage,
            splash=self.splash, grav=GRAVITY, life=t + 1.4,
            bonus_air=self.BONUS_VS_AIR, bonus_heavy=self.BONUS_VS_HEAVY))
        self.game.effects.burst(mx, my, 10, (200, 190, 170), speed=180,
                                life=0.35, grav=200)
        self.game.add_shake(3.2)

    def draw(self, surf):
        if self.blit_sprite(surf):
            self.draw_status(surf)
            return
        r = self.rect
        base = (54, 56, 64) if self.disabled else (72, 76, 88)
        pygame.draw.rect(surf, base, (r.x, r.y + 10, r.w, r.h - 10),
                         border_radius=4)
        pygame.draw.rect(surf, shade(base, 0.6), (r.x, r.y + 10, r.w, r.h - 10),
                         2, border_radius=4)
        pygame.draw.circle(surf, (40, 42, 50), (r.centerx, r.bottom - 6), 7)
        if self.disabled:
            return
        px = self.x - math.cos(self.aim) * self.recoil * 8
        py = self.y - self.H + 6 - math.sin(self.aim) * self.recoil * 8
        ex = px + math.cos(self.aim) * 26
        ey = py + math.sin(self.aim) * 26
        pygame.draw.line(surf, (46, 48, 56), (px, py), (ex, ey), 11)
        pygame.draw.line(surf, self.COLOR, (px, py), (ex, ey), 7)
        pygame.draw.circle(surf, (150, 152, 164), (int(px), int(py)), 6)


# ------------------------------------------------------------------------------
# The castle
# ------------------------------------------------------------------------------


class Castle:
    """
    The player's keep.  Its wall level changes both its stats *and* its looks:
    Wood -> Stone -> Reinforced Iron -> Runed Obsidian, gaining extra turrets
    and banners along the way.
    """

    # (name, wall colour, mortar colour, trim colour, max hp)
    TIERS = [
        ("Wooden Palisade",  (122,  86,  52), (92, 62, 36),  (156, 116, 70),  400),
        ("Stone Keep",       (132, 132, 140), (98, 98, 106), (168, 168, 178),  620),
        ("Granite Bastion",  (112, 120, 132), (82, 88, 98),  (150, 160, 174),  900),
        ("Reinforced Iron",  (94, 100, 116),  (62, 66, 78),  (176, 182, 198), 1300),
        ("Blacksteel Hold",  (72, 78, 96),    (48, 52, 64),  (204, 178, 108), 1800),
        ("Runed Obsidian",   (52, 50, 70),    (34, 32, 48),  (150, 210, 245), 2500),
    ]

    # Emplacements, in the order they unlock.  Sturdier walls mean more of
    # them, so Reinforce Walls buys space as well as hit points.
    SLOTS = [
        (248, WALL_TOP + 4), (210, WALL_TOP + 4), (172, WALL_TOP + 4),
        (140, WALL_TOP + 4),
        (30, KEEP_TOP + 4), (62, KEEP_TOP + 4), (94, KEEP_TOP + 4),
        (120, KEEP_TOP + 4),
        (228, WALL_TOP - 62),          # corner turret (drawn from level 3)
    ]

    def __init__(self, game):
        self.game = game
        self.front_x = CASTLE_FRONT
        self.keep_right = 132
        self.wall_level = 1
        self.max_hp = float(self.TIERS[0][4])
        self.hp = self.max_hp
        self.towers = []
        self.flash = 0.0
        self.banner_phase = 0.0
        self._cache_key = None
        self._cache_surf = None

    # -- stats -----------------------------------------------------------
    # Past the last visual tier the walls keep getting stronger; only the
    # look stops changing.  Each extra level adds this much max health.
    ENDLESS_WALL_STEP = 520.0
    ENDLESS_WALL_GROWTH = 1.16

    @property
    def tier_index(self):
        return clamp(self.wall_level - 1, 0, len(self.TIERS) - 1)

    @property
    def visual_capped(self):
        return self.wall_level > len(self.TIERS)

    @property
    def tier_label(self):
        """What the HUD shows -- the tier name, plus the reinforcement count
        once the appearance has topped out."""
        if self.visual_capped:
            extra = self.wall_level - len(self.TIERS)
            return f"{self.TIERS[-1][0]} +{extra}"
        return self.TIERS[self.tier_index][0]

    @property
    def tier_name(self):
        return self.TIERS[self.tier_index][0]

    @property
    def max_level(self):
        return len(self.TIERS)

    def upgrade_wall(self):
        """Never refuses.  Up to the last tier the castle also changes its
        look; beyond that it just keeps getting tougher."""
        self.wall_level += 1
        if self.wall_level <= len(self.TIERS):
            gained = float(self.TIERS[self.tier_index][4]) - self.max_hp
        else:
            over = self.wall_level - len(self.TIERS)
            gained = (self.ENDLESS_WALL_STEP
                      * self.ENDLESS_WALL_GROWTH ** (over - 1))
        self.max_hp += gained
        self.hp = min(self.max_hp, self.hp + gained)
        for t in self.towers:                 # the platforms get tougher too
            t.max_hp *= 1.22
            t.hp = min(t.max_hp, t.hp * 1.22)
        if self.wall_level <= len(self.TIERS):
            self._cache_key = None            # the look actually changed
        return True

    def repair(self, frac=0.35):
        healed = min(self.max_hp - self.hp, self.max_hp * frac)
        self.hp += healed
        return healed

    @property
    def slot_capacity(self):
        return min(len(self.SLOTS), 4 + self.wall_level)

    def free_slots(self):
        used = {(t.x, t.y) for t in self.towers}
        return [s for s in self.SLOTS[:self.slot_capacity]
                if (float(s[0]), float(s[1])) not in used]

    def add_tower(self, cls):
        free = self.free_slots()
        if not free:
            return None
        # fill the wall (front) slots first so defences look deliberate
        free.sort(key=lambda s: -s[0])
        x, y = free[0]
        t = cls(self.game, x, y)
        boost = 1.22 ** (self.wall_level - 1) * self.game.talents.tower_hp
        t.max_hp *= boost
        t.hp = t.max_hp
        self.towers.append(t)
        return t

    def tower_at(self, x, y):
        for t in self.towers:
            if not t.disabled and t.rect.collidepoint(x, y):
                return t
        return None

    # -- damage ----------------------------------------------------------
    def take_damage(self, amount):
        if self.hp <= 0:
            return
        amount *= self.game.talents.damage_taken
        self.hp -= amount
        # proportional to how hard the blow was, so a stream of small hits
        # never leaves the castle permanently tinted red
        self.flash = min(1.0, self.flash + amount / max(1.0, self.max_hp * 0.10))
        self.game.add_shake(min(8.0, 1.5 + amount * 0.05))
        if self.hp <= 0:
            self.hp = 0.0
            self.game.on_castle_destroyed()

    def splash_hit(self, x, y, radius, damage, stun=0.0):
        # only blasts that actually land on or near the wall hurt the castle
        gap = max(0.0, x - self.front_x)
        if gap <= radius:
            self.take_damage(damage * (1.0 - 0.5 * gap / max(1.0, radius)))
        for t in self.towers:
            if t.disabled:
                continue
            if math.hypot(t.x - x, (t.y - t.H / 2) - y) <= radius:
                t.take_damage(damage * 0.5)
                if stun > 0:
                    t.stun = max(t.stun, stun)

    def smash_random_tower(self, damage, stun=0.0):
        live = [t for t in self.towers if not t.disabled]
        if not live:
            return
        t = random.choice(live)
        t.take_damage(damage)
        if stun > 0:
            t.stun = max(t.stun, stun)
        self.game.effects.burst(t.x, t.y - t.H, 16, (210, 180, 120),
                                speed=260, life=0.6, size=4)

    def restore_towers(self):
        for t in self.towers:
            t.restore()

    # -- update / draw ---------------------------------------------------
    def update(self, dt):
        self.flash = max(0.0, self.flash - dt * 5.0)
        self.banner_phase += dt * 3.4
        for t in self.towers:
            t.update(dt)

    def _build_surface(self):
        """Render the castle body once per wall level into a cached surface."""
        # The look saturates at the last tier, so key the cache on the
        # visual level only -- otherwise every extra reinforcement rebuilt
        # an identical surface, redrawing all the brickwork for nothing.
        key = min(self.wall_level, len(self.TIERS))
        if self._cache_key == key and self._cache_surf is not None:
            return self._cache_surf
        art = ASSETS.get("castle")
        if art is not None:
            surf = pygame.Surface((CASTLE_FRONT + 40, HEIGHT), pygame.SRCALPHA)
            surf.blit(pygame.transform.smoothscale(
                art, (CASTLE_FRONT, GROUND_Y - KEEP_TOP + 2)), (0, KEEP_TOP))
            self._cache_key = key
            self._cache_surf = surf
            return surf

        wall_col, mortar, trim, _ = self.TIERS[self.tier_index][1:5]
        lvl = self.wall_level
        surf = pygame.Surface((CASTLE_FRONT + 40, HEIGHT), pygame.SRCALPHA)

        def brickwork(rect, cols, mort, block_w=26, block_h=18):
            pygame.draw.rect(surf, mort, rect)
            row = 0
            y = rect.top
            while y < rect.bottom:
                offset = 0 if row % 2 == 0 else block_w // 2
                x = rect.left - offset
                while x < rect.right:
                    bw = min(block_w - 3, rect.right - x - 2)
                    bh = min(block_h - 3, rect.bottom - y - 2)
                    if bw > 2 and bh > 2 and x + 2 >= rect.left:
                        tint = 0.86 + 0.28 * ((x * 7 + y * 13) % 5) / 5.0
                        pygame.draw.rect(surf, shade(cols, tint),
                                         (x + 2, y + 2, bw, bh))
                    x += block_w
                y += block_h
                row += 1

        # --- curtain wall ---
        wall = pygame.Rect(0, WALL_TOP, CASTLE_FRONT, GROUND_Y - WALL_TOP + 2)
        brickwork(wall, wall_col, mortar,
                  block_w=22 if lvl <= 1 else 30, block_h=14 if lvl <= 1 else 20)

        # --- keep ---
        keep = pygame.Rect(0, KEEP_TOP, self.keep_right, GROUND_Y - KEEP_TOP + 2)
        brickwork(keep, shade(wall_col, 1.06), mortar, block_w=26, block_h=18)

        # --- battlements (merlons) ---
        def merlons(x0, x1, top, w=18, gap=12, h=18):
            x = x0
            while x < x1:
                pygame.draw.rect(surf, shade(wall_col, 1.12),
                                 (x, top - h, min(w, x1 - x), h))
                pygame.draw.rect(surf, mortar,
                                 (x, top - h, min(w, x1 - x), h), 1)
                x += w + gap

        merlons(0, CASTLE_FRONT, WALL_TOP)
        merlons(0, self.keep_right, KEEP_TOP)

        # --- walkway trim ---
        pygame.draw.rect(surf, shade(trim, 0.8), (0, WALL_TOP, CASTLE_FRONT, 4))
        pygame.draw.rect(surf, shade(trim, 0.8), (0, KEEP_TOP, self.keep_right, 4))
        pygame.draw.rect(surf, shade(trim, 0.7),
                         (CASTLE_FRONT - 6, WALL_TOP, 6, GROUND_Y - WALL_TOP))

        # --- gate ---
        gate = pygame.Rect(CASTLE_FRONT - 78, GROUND_Y - 108, 62, 108)
        pygame.draw.rect(surf, shade(mortar, 0.7), gate, border_top_left_radius=28,
                         border_top_right_radius=28)
        pygame.draw.rect(surf, shade((96, 68, 40), 1.0), gate.inflate(-8, 0),
                         border_top_left_radius=24, border_top_right_radius=24)
        for i in range(1, 5):
            gy = gate.top + 22 + i * 18
            pygame.draw.line(surf, shade(trim, 0.8),
                             (gate.left + 6, gy), (gate.right - 6, gy), 2)
        if lvl >= 4:   # portcullis bars once we go iron
            for i in range(5):
                gx = gate.left + 10 + i * 11
                pygame.draw.line(surf, (170, 176, 190),
                                 (gx, gate.top + 16), (gx, gate.bottom - 4), 2)

        # --- windows / arrow slits ---
        for i in range(3):
            wx = 22 + i * 36
            pygame.draw.rect(surf, (28, 26, 36), (wx, KEEP_TOP + 42, 10, 26),
                             border_radius=4)
            if lvl >= 2:
                pygame.draw.rect(surf, (250, 214, 140),
                                 (wx + 2, KEEP_TOP + 46, 6, 18), border_radius=3)

        # --- upgrade flourishes ---
        if lvl >= 3:   # corner turret on the wall
            tur = pygame.Rect(CASTLE_FRONT - 46, WALL_TOP - 66, 44, 66)
            brickwork(tur, shade(wall_col, 1.1), mortar, 20, 16)
            merlons(tur.left, tur.right, tur.top, 12, 8, 12)
            pygame.draw.rect(surf, trim, (tur.left, tur.top, tur.w, 4))
        if lvl >= 5:   # buttresses
            for bx in (46, 150):
                pygame.draw.polygon(surf, shade(wall_col, 0.82), [
                    (bx, GROUND_Y), (bx + 26, GROUND_Y),
                    (bx + 18, GROUND_Y - 90), (bx + 8, GROUND_Y - 90)])
        if lvl >= 6:   # glowing runes
            for i in range(6):
                rx = 16 + i * 38
                pygame.draw.circle(surf, (120, 200, 250), (rx, WALL_TOP + 70), 5)
                pygame.draw.circle(surf, (200, 240, 255), (rx, WALL_TOP + 70), 2)

        self._cache_key = key
        self._cache_surf = surf
        return surf

    def draw(self, surf):
        body = self._build_surface()
        surf.blit(body, (0, 0))

        # damage flash
        if self.flash > 0:
            ov = pygame.Surface((CASTLE_FRONT + 40, HEIGHT), pygame.SRCALPHA)
            ov.blit(body, (0, 0))
            ov.fill((255, 90, 70, int(95 * self.flash)),
                    special_flags=pygame.BLEND_RGBA_MULT)
            surf.blit(ov, (0, 0), special_flags=pygame.BLEND_RGBA_ADD)

        # cracks as health drops
        frac = self.hp / max(1.0, self.max_hp)
        if frac < 0.66:
            random.seed(1337)
            cracks = int((0.66 - frac) * 26)
            for _ in range(cracks):
                cx = random.randint(6, CASTLE_FRONT - 10)
                cy = random.randint(WALL_TOP + 12, GROUND_Y - 12)
                pts = [(cx, cy)]
                for _ in range(3):
                    cx += random.randint(-12, 12)
                    cy += random.randint(2, 14)
                    pts.append((cx, cy))
                pygame.draw.lines(surf, (26, 22, 26), False, pts, 2)
            random.seed()

        # banner on the keep
        pole_x = self.keep_right - 16
        pygame.draw.line(surf, (200, 196, 186), (pole_x, KEEP_TOP - 18),
                         (pole_x, KEEP_TOP - 78), 3)
        trim = self.TIERS[self.tier_index][3]
        pts = []
        for i in range(5):
            t = i / 4.0
            pts.append((pole_x + 4 + t * 34,
                        KEEP_TOP - 74 + math.sin(self.banner_phase + t * 3) * 4 + t * 3))
        for i in range(4, -1, -1):
            t = i / 4.0
            pts.append((pole_x + 4 + t * 34,
                        KEEP_TOP - 48 + math.sin(self.banner_phase + t * 3) * 4 + t * 3))
        pygame.draw.polygon(surf, trim, pts)
        pygame.draw.polygon(surf, shade(trim, 0.6), pts, 2)

        self.game.spikes.draw(surf, self.front_x)

        for t in self.towers:
            t.draw(surf)
        for t in self.towers:
            t.draw_status(surf)


# ------------------------------------------------------------------------------
# Outpost and Barricade
# ------------------------------------------------------------------------------

class SpikeWalls:
    """Iron spikes bolted to the outer face of the curtain wall.

    Purely defensive: anything that swings at the castle takes damage back,
    scaled by the wave so it keeps mattering late on.
    """
    MAX_LEVEL = SPIKE_MAX_LEVEL

    def __init__(self, game):
        self.game = game
        self.level = 0

    def upgrade(self):
        if self.level >= self.MAX_LEVEL:
            return False
        self.level += 1
        return True

    @property
    def damage(self):
        if self.level <= 0:
            return 0.0
        return (SPIKE_DAMAGE * self.level
                * (1.0 + 0.06 * max(0, self.game.wave - 1)))

    def bite(self, enemy):
        """Reflect damage onto whatever just hit the wall."""
        dmg = self.damage
        if dmg <= 0 or not enemy.alive:
            return
        enemy.take_damage(dmg, "spike")
        bleed = self.game.talents.spike_dot
        if bleed > 0:
            enemy.take_damage(dmg * bleed, "bleed")
        self.game.effects.burst(self.game.castle.front_x + 8, enemy.y, 5,
                                (226, 120, 110), speed=150, life=0.3, size=2)

    def draw(self, surf, front_x):
        for row in range(self.level):
            yy = WALL_TOP + 26 + row * 34
            for k in range(4):
                ty = yy + k * 8
                pygame.draw.polygon(surf, (176, 182, 198), [
                    (front_x - 2, ty - 4), (front_x + 18, ty),
                    (front_x - 2, ty + 4)])
                pygame.draw.polygon(surf, (96, 102, 116), [
                    (front_x - 2, ty - 4), (front_x + 18, ty),
                    (front_x - 2, ty + 4)], 1)


class Outpost:
    """
    A stone tower back in the scenery.  Enemies march straight past it and
    can never attack it, so whatever is garrisoned here keeps firing all
    round -- an uninterrupted trickle of damage the player buys into.
    """

    def __init__(self, game):
        self.game = game
        self.x = OUTPOST_X
        self.y = OUTPOST_BASE_Y
        self.level = 0
        self.cooldowns = []
        self.flash = 0.0
        # --- the Necromancer betrayal ---
        self.prisoner = None        # a Necromancer flung in here
        self.prisoner_hp = 0.0      # his own pool; rivals shoot at it
        self.prisoner_max = PRISONER_HP
        self.prisoner_hit = 0.0     # recently-damaged flash / regen lock
        self.skeleton_timer = 0.0
        self.trap_glow = 0.0

    #  Past OUTPOST_MAX_LEVEL no more crew appear; every further level
    #  multiplies the damage of everything already stationed there.
    OVERDRIVE_PER_LEVEL = 0.35

    @property
    def guns(self):
        return min(self.level, OUTPOST_MAX_LEVEL)

    @property
    def overdrive(self):
        """Damage multiplier from levels bought past the visual cap."""
        return 1.0 + self.OVERDRIVE_PER_LEVEL * max(
            0, self.level - OUTPOST_MAX_LEVEL)

    @property
    def is_turret(self):
        return self.level >= OUTPOST_TURRET_FROM

    @property
    def gun_damage(self):
        base = 15.0 if self.is_turret else 7.5
        return base * self.overdrive * self.game.talents.tower_damage

    @property
    def gun_reload(self):
        return 0.62 if self.is_turret else 0.92

    # ------------------------------------------------------------------
    #  The Necromancer betrayal
    # ------------------------------------------------------------------
    @property
    def body_rect(self):
        return pygame.Rect(int(self.x) - 42, int(self.y) - 62, 84, 70)

    @property
    def trap_rect(self):
        """Slightly generous catch area -- landing a throw in here is the
        whole trick, so it should not be pixel-perfect."""
        return self.body_rect.inflate(26, 26)

    @property
    def has_prisoner(self):
        return self.prisoner is not None

    def can_trap(self, enemy):
        """Only a Necromancer, only one at a time, and only if the player's
        Grab Strength was enough to have lifted him in the first place."""
        return (self.prisoner is None and enemy is not None and enemy.alive
                and getattr(enemy, "TRAPPABLE", False)
                and enemy.MASS <= self.game.grab_capacity)

    def trap(self, enemy):
        """Imprison him. His magic runs backwards from in here: instead of
        raising skeletons for the horde, he raises them for the castle."""
        if not self.can_trap(enemy):
            return False
        enemy.on_trapped()
        self.prisoner = enemy
        self.prisoner_max = PRISONER_HP
        self.prisoner_hp = PRISONER_HP
        self.prisoner_hit = 0.0
        enemy.trapped = True
        enemy.state = "trapped"
        enemy.vx = enemy.vy = 0.0
        enemy.x, enemy.y = self.x, self.y - 26
        self.skeleton_timer = 1.0
        self.trap_glow = 1.0
        g = self.game
        if enemy in g.enemies:
            g.enemies.remove(enemy)       # no longer part of the horde
        g.add_shake(8.0)
        g.effects.ring(self.x, self.y - 30, 26, (168, 214, 232),
                       speed=380, life=0.7, size=5)
        g.effects.text(self.x, self.y - 80, "IMPRISONED!", C_ALLY, 26)
        g.announce("The Necromancer is trapped -- his magic turns on them!",
                   C_ALLY, 3.4)
        g.stats_trapped += 1
        return True

    def hurt_prisoner(self, amount):
        """A free Necromancer is shooting the turncoat."""
        if self.prisoner is None:
            return
        self.prisoner_hp -= amount
        self.prisoner_hit = 1.0
        g = self.game
        if self.prisoner_hp <= 0:
            self.prisoner_hp = 0.0
            g.effects.burst(self.x, self.y - 30, 26, (196, 140, 240),
                            speed=280, life=0.7, size=4)
            g.effects.text(self.x, self.y - 80, "PRISONER SLAIN", C_RED, 24)
            g.announce("Your imprisoned Necromancer is dead -- find another!",
                       (255, 150, 130), 3.4)
            self.prisoner = None
            self.skeleton_timer = 0.0

    def update_prisoner(self, dt):
        if self.prisoner is None:
            return
        self.prisoner_hit = max(0.0, self.prisoner_hit - dt)
        if self.prisoner_hit <= 0 and self.prisoner_hp < self.prisoner_max:
            self.prisoner_hp = min(self.prisoner_max,
                                   self.prisoner_hp + PRISONER_REGEN * dt)
        self.trap_glow = max(0.0, self.trap_glow - dt * 0.9)
        g = self.game
        cap = TRAP_SKELETON_CAP + g.talents.ally_cap_bonus
        self.skeleton_timer -= dt
        if self.skeleton_timer > 0 or len(g.allies) >= cap:
            return
        self.skeleton_timer = TRAP_SKELETON_RATE * g.talents.ally_rate
        ally = g.make_ally(self.x - 30)
        g.allies.append(ally)
        self.trap_glow = 1.0
        g.effects.ring(ally.x, ally.y, 12, C_ALLY, speed=170, life=0.45, size=3)

    def draw_prisoner(self, surf):
        if self.prisoner is None:
            return
        b = self.body_rect
        cage = pygame.Rect(b.centerx - 22, b.y + 8, 44, 46)
        glow = pygame.Surface((cage.w + 30, cage.h + 30), pygame.SRCALPHA)
        pygame.draw.ellipse(glow, (*C_ALLY, int(50 + 70 * self.trap_glow)),
                            glow.get_rect())
        surf.blit(glow, (cage.x - 15, cage.y - 15))
        # the prisoner, hunched inside
        p = self.prisoner
        pygame.draw.polygon(surf, shade(p.COLOR, 0.8), [
            (cage.centerx, cage.y + 8), (cage.right - 6, cage.bottom - 4),
            (cage.left + 6, cage.bottom - 4)])
        pygame.draw.circle(surf, (44, 32, 56), (cage.centerx, cage.y + 14), 6)
        pygame.draw.circle(surf, C_ALLY, (cage.centerx - 2, cage.y + 13), 2)
        pygame.draw.circle(surf, C_ALLY, (cage.centerx + 3, cage.y + 13), 2)
        # bars
        pygame.draw.rect(surf, (66, 72, 88), cage, 3, border_radius=3)
        for i in range(4):
            bx = cage.left + 8 + i * 10
            pygame.draw.line(surf, (150, 158, 176),
                             (bx, cage.top + 2), (bx, cage.bottom - 2), 2)
        frac = self.prisoner_hp / max(1.0, self.prisoner_max)
        col = C_ALLY if frac > 0.35 else C_RED
        draw_bar(surf, cage.left - 4, cage.top - 10, cage.w + 8, 5, frac, col)
        draw_text(surf, "TRAPPED", self.x, cage.bottom + 2, 15, C_ALLY,
                  "center", True)
        if self.prisoner_hit > 0:
            draw_text(surf, "UNDER FIRE", self.x, cage.bottom + 17, 15, C_RED,
                      "center", True)

    def upgrade(self):
        """Always succeeds.  New crew arrive up to the visual cap; after
        that the levels pour into raw firepower instead."""
        self.level += 1
        if len(self.cooldowns) < self.guns:
            self.cooldowns.append(random.uniform(0.0, 0.5))
        self.flash = 1.0
        return True

    def gun_pos(self, i):
        return (self.x - 26 + (i % 3) * 26, self.y - 60 - (i // 3) * 26)

    def pick_target(self):
        best, bx = None, None
        for e in self.game.enemies:
            if not e.alive or not e.targetable:
                continue
            if math.hypot(e.x - self.x, e.y - self.y) > OUTPOST_RANGE:
                continue
            if bx is None or e.x < bx:      # whatever is furthest along
                best, bx = e, e.x
        return best

    def update(self, dt):
        self.flash = max(0.0, self.flash - dt * 2.0)
        self.update_prisoner(dt)
        if self.level <= 0:
            return
        for i in range(len(self.cooldowns)):
            self.cooldowns[i] -= dt
            if self.cooldowns[i] > 0:
                continue
            target = self.pick_target()
            if target is None:
                self.cooldowns[i] = 0.15
                continue
            self.cooldowns[i] = self.gun_reload
            gx, gy = self.gun_pos(i)
            speed = 1000.0 if self.is_turret else 840.0
            a = math.atan2(target.y - gy, target.x - gx)
            self.game.projectiles.append(Projectile(
                self.game, gx, gy, math.cos(a) * speed, math.sin(a) * speed,
                "bolt" if self.is_turret else "arrow", self.gun_damage))

    def draw(self, surf):
        x, y = int(self.x), int(self.y)
        if blit_asset(surf, "outpost",
                      pygame.Rect(x - 42, y - 62, 84, 70)):
            self.draw_garrison(surf)
            return
        # rocky outcrop it stands on
        pygame.draw.polygon(surf, (38, 44, 54), [
            (x - 78, GROUND_Y - 16), (x - 46, y + 6),
            (x + 46, y + 6), (x + 78, GROUND_Y - 16)])
        body = pygame.Rect(x - 42, y - 62, 84, 70)
        pygame.draw.rect(surf, (74, 78, 92), body)
        for row in range(4):
            for col in range(4):
                pygame.draw.rect(surf, shade((74, 78, 92), 0.86 + 0.1 * ((row + col) % 2)),
                                 (body.x + 3 + col * 20, body.y + 3 + row * 17, 17, 14))
        pygame.draw.rect(surf, (46, 50, 62), body, 2)
        for i in range(5):      # battlements
            pygame.draw.rect(surf, (92, 96, 112),
                             (body.x + i * 18, body.y - 10, 12, 12))
        self.draw_garrison(surf)

    def draw_garrison(self, surf):
        body = self.body_rect
        self.draw_prisoner(surf)
        if self.level <= 0:
            draw_text(surf, "OUTPOST (empty)", self.x, body.y - 30, 16,
                      (150, 156, 174), "center")
            return
        for i in range(self.guns):
            gx, gy = self.gun_pos(i)
            if self.is_turret:
                pygame.draw.rect(surf, (96, 104, 124),
                                 (gx - 8, gy - 6, 16, 14), border_radius=3)
                pygame.draw.line(surf, (150, 158, 178), (gx, gy - 2),
                                 (gx + 14, gy - 6), 4)
                pygame.draw.circle(surf, (198, 206, 226), (gx, gy - 2), 4)
            else:
                pygame.draw.circle(surf, (222, 190, 152), (int(gx), int(gy) - 6), 4)
                pygame.draw.rect(surf, (110, 170, 108), (gx - 4, gy - 2, 8, 10),
                                 border_radius=2)
                pygame.draw.arc(surf, (198, 170, 110),
                                pygame.Rect(int(gx) + 3, int(gy) - 10, 12, 20),
                                -1.1, 1.1, 2)
        tag = "TURRETS" if self.is_turret else "BOWMEN"
        label = f"OUTPOST {tag} x{self.guns}"
        if self.overdrive > 1.0:
            label += f"  x{self.overdrive:.2f} PWR"
        draw_text(surf, label, self.x, body.y - 30, 15,
                  (176, 200, 226), "center", True)


class Barricade:
    """
    A bought wall standing out in the field.  Ground troops have to chew
    through it before they can reach the castle; flyers simply go over.
    """
    W = 40

    def __init__(self, game):
        self.game = game
        self.x = BARRICADE_X
        self.level = 0
        self.hp = 0.0
        self.max_hp = 0.0
        self.flash = 0.0

    @property
    def alive(self):
        return self.level > 0 and self.hp > 0

    @property
    def top_y(self):
        return GROUND_Y - 96

    def buy(self):
        """Buy, rebuild after a collapse, or reinforce to the next tier."""
        if self.level < BARRICADE_MAX_LEVEL:
            self.level += 1
        elif self.hp >= self.max_hp:
            return False, "The barricade is already at full strength."
        self.max_hp = float(BARRICADE_HP[self.level])
        self.hp = self.max_hp
        return True, f"Barricade raised to Lv.{self.level}."

    def take_damage(self, amount):
        if not self.alive:
            return
        self.hp -= amount
        self.flash = 1.0
        if self.hp <= 0:
            self.hp = 0.0
            g = self.game
            g.add_shake(8.0)
            g.effects.burst(self.x, GROUND_Y - 48, 40, (140, 120, 96),
                            speed=340, life=0.8, size=5)
            g.announce("The barricade has fallen!", (255, 160, 120), 2.0)

    def update(self, dt):
        self.flash = max(0.0, self.flash - dt * 3.0)

    def draw(self, surf):
        if not self.alive:
            if self.level > 0:      # rubble where it stood
                for i in range(5):
                    pygame.draw.rect(surf, (72, 64, 54),
                                     (int(self.x) - 22 + i * 10,
                                      GROUND_Y - 8 - (i % 2) * 5, 9, 8))
            return
        top = self.top_y
        if blit_asset(surf, "barricade",
                      pygame.Rect(int(self.x - self.W / 2), int(top),
                                  self.W, GROUND_Y - int(top))):
            self.draw_status(surf)
            return
        col = (128, 112, 88)
        if self.flash > 0:
            col = mix(col, (255, 190, 170), self.flash * 0.8)
        r = pygame.Rect(int(self.x - self.W / 2), int(top), self.W,
                        GROUND_Y - int(top))
        pygame.draw.rect(surf, col, r)
        pygame.draw.rect(surf, shade(col, 0.6), r, 3)
        for i in range(4):          # plank lines
            yy = top + 14 + i * 22
            pygame.draw.line(surf, shade(col, 0.7),
                             (r.left + 2, yy), (r.right - 2, yy), 2)
        for s_ in (-1, 1):          # angled braces
            pygame.draw.line(surf, shade(col, 0.8),
                             (r.centerx, top + 10),
                             (r.centerx + s_ * 22, GROUND_Y - 2), 4)
        pygame.draw.polygon(surf, (156, 140, 112), [
            (r.left - 4, top), (r.right + 4, top), (r.centerx, top - 14)])
        self.draw_status(surf)

    def draw_status(self, surf):
        top = self.top_y
        draw_bar(surf, int(self.x - 30), int(top - 30), 60, 6,
                 self.hp / max(1.0, self.max_hp), (206, 160, 92))
        draw_text(surf, f"Lv.{self.level}", self.x, top - 48, 15,
                  (206, 182, 140), "center", True)


# ------------------------------------------------------------------------------
# Wave scaling
# ------------------------------------------------------------------------------

