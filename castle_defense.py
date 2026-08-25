"""
================================================================================
  CASTLE DEFENSE  --  a single-file, endless 2D castle defense game (pygame)
================================================================================

  Defend your keep against endless waves of increasingly nasty creatures.

  CONTROLS
    Mouse (hold LMB)   Grab a ground mob and fling it -- release to throw.
                       Thrown mobs take heavy fall damage and smash into
                       anything they land on.  Bosses and Siege Rams are far
                       too heavy to lift.
    Mouse (LMB)        Click shop cards between waves to buy upgrades.
    SPACE / ENTER      Start the next wave (from the shop screen).
    1 - 5              Shop hotkeys.
    P / ESC            Pause.
    R                  Restart after a defeat.

  Run with:  python castle_defense.py
  Self-test: python castle_defense.py --selftest   (headless sanity run)

  No external assets required -- every sprite is drawn with pygame primitives.
================================================================================
"""

import math
import random
import sys
from collections import deque

import pygame

# ------------------------------------------------------------------------------
# Configuration
# ------------------------------------------------------------------------------

WIDTH, HEIGHT = 1280, 720
FPS = 60
TITLE = "Castle Defense"

GROUND_Y = 620          # y of the ground line
CASTLE_FRONT = 252      # x of the castle's front face (enemies stop here)
WALL_TOP = 350          # y of the top of the curtain wall
KEEP_TOP = 230          # y of the top of the keep
SPAWN_X = WIDTH + 90    # where enemies walk in from

GRAVITY = 1650.0        # px / s^2 for thrown mobs and cannonballs
AIR_DRAG = 0.16         # horizontal drag per second while airborne
THROW_POWER = 1.15      # multiplier applied to mouse velocity on release
FALL_DMG_FLOOR = 250.0  # impact speed below which a landing is harmless
FALL_DMG_SCALE = 0.26   # damage per px/s of impact speed above the floor
SLAM_DMG_FLOOR = 170.0  # relative speed below which mid-air collisions are safe
SLAM_DMG_SCALE = 0.10
GRAB_COOLDOWN = 0.22

# --- armour stripping (heavy units) -------------------------------------
STRIP_DISTANCE = 420.0   # px of dragging needed to pry one plate loose
STRIP_SLOW = 0.76        # speed retained per plate torn off
STRIP_VULN = 0.22        # extra damage taken per plate torn off

STARTING_GOLD = 220
MAX_PARTICLES = 900

# ------------------------------------------------------------------------------
# Palette
# ------------------------------------------------------------------------------

C_SKY_TOP = (36, 42, 74)
C_SKY_BOT = (126, 108, 122)
C_GROUND = (58, 74, 46)
C_GROUND_DARK = (40, 52, 32)
C_DIRT = (74, 60, 44)
C_WHITE = (240, 244, 250)
C_DIM = (168, 176, 194)
C_GOLD = (248, 202, 78)
C_RED = (226, 74, 68)
C_GREEN = (110, 210, 120)
C_PANEL = (26, 28, 42)
C_PANEL_EDGE = (86, 94, 128)
C_HILITE = (96, 190, 236)

_FONT_CACHE = {}


def font(size, bold=False):
    """Cached default-font lookup (no external font files needed)."""
    key = (size, bold)
    f = _FONT_CACHE.get(key)
    if f is None:
        f = pygame.font.Font(None, size)
        f.set_bold(bold)
        _FONT_CACHE[key] = f
    return f


# ------------------------------------------------------------------------------
# Small helpers
# ------------------------------------------------------------------------------

def clamp(v, lo, hi):
    return lo if v < lo else hi if v > hi else v


def lerp(a, b, t):
    return a + (b - a) * t


def shade(color, factor):
    """Lighten (factor > 1) or darken (factor < 1) an RGB colour safely."""
    return (
        int(clamp(color[0] * factor, 0, 255)),
        int(clamp(color[1] * factor, 0, 255)),
        int(clamp(color[2] * factor, 0, 255)),
    )


def mix(c1, c2, t):
    return (
        int(lerp(c1[0], c2[0], t)),
        int(lerp(c1[1], c2[1], t)),
        int(lerp(c1[2], c2[2], t)),
    )


def draw_text(surf, text, x, y, size=22, color=C_WHITE, align="left",
              bold=False, shadow=True):
    f = font(size, bold)
    img = f.render(str(text), True, color)
    rect = img.get_rect()
    if align == "center":
        rect.midtop = (x, y)
    elif align == "right":
        rect.topright = (x, y)
    else:
        rect.topleft = (x, y)
    if shadow:
        sh = f.render(str(text), True, (0, 0, 0))
        surf.blit(sh, (rect.x + 2, rect.y + 2))
    surf.blit(img, rect)
    return rect


def draw_bar(surf, x, y, w, h, frac, fill, back=(28, 28, 34), border=(12, 12, 16)):
    frac = clamp(frac, 0.0, 1.0)
    pygame.draw.rect(surf, back, (x, y, w, h), border_radius=3)
    if frac > 0:
        pygame.draw.rect(surf, fill, (x, y, max(2, int(w * frac)), h),
                         border_radius=3)
    pygame.draw.rect(surf, border, (x, y, w, h), 1, border_radius=3)


# ------------------------------------------------------------------------------
# Effects: floating combat text and particles
# ------------------------------------------------------------------------------

class FloatingText:
    __slots__ = ("x", "y", "vy", "text", "color", "life", "max_life", "size")

    def __init__(self, x, y, text, color=C_WHITE, size=20, life=0.9):
        self.x, self.y = x, y
        self.vy = -46.0
        self.text = text
        self.color = color
        self.life = self.max_life = life
        self.size = size

    def update(self, dt):
        self.y += self.vy * dt
        self.vy += 62.0 * dt
        self.life -= dt
        return self.life > 0

    def draw(self, surf):
        a = clamp(self.life / self.max_life, 0.0, 1.0)
        f = font(self.size, True)
        img = f.render(self.text, True, self.color)
        img.set_alpha(int(255 * a))
        surf.blit(img, img.get_rect(center=(int(self.x), int(self.y))))


class Particle:
    __slots__ = ("x", "y", "vx", "vy", "life", "max_life", "color", "size",
                 "grav", "fade", "shape")

    def __init__(self, x, y, vx, vy, life, color, size=3, grav=900.0,
                 shape="rect"):
        self.x, self.y = x, y
        self.vx, self.vy = vx, vy
        self.life = self.max_life = life
        self.color = color
        self.size = size
        self.grav = grav
        self.shape = shape

    def update(self, dt):
        self.vy += self.grav * dt
        self.x += self.vx * dt
        self.y += self.vy * dt
        self.life -= dt
        return self.life > 0


class Effects:
    """Container for particles + floating text so the Game stays tidy."""

    def __init__(self):
        self.particles = []
        self.texts = []

    def clear(self):
        self.particles.clear()
        self.texts.clear()

    def text(self, x, y, msg, color=C_WHITE, size=20, life=0.9):
        if len(self.texts) < 90:
            self.texts.append(FloatingText(x, y, msg, color, size, life))

    def burst(self, x, y, count, color, speed=260, life=0.6, size=3,
              grav=900.0, shape="rect"):
        room = MAX_PARTICLES - len(self.particles)
        count = int(clamp(count, 0, room))
        for _ in range(count):
            a = random.uniform(0, math.tau)
            s = random.uniform(0.25, 1.0) * speed
            self.particles.append(Particle(
                x, y, math.cos(a) * s, math.sin(a) * s - speed * 0.25,
                life * random.uniform(0.6, 1.25), color,
                max(1, int(size * random.uniform(0.6, 1.4))), grav, shape))

    def ring(self, x, y, count, color, speed=320, life=0.5, size=3):
        room = MAX_PARTICLES - len(self.particles)
        count = int(clamp(count, 0, room))
        for i in range(count):
            a = math.tau * i / max(1, count)
            self.particles.append(Particle(
                x, y, math.cos(a) * speed, math.sin(a) * speed * 0.55,
                life, color, size, 340.0, "circle"))

    def update(self, dt):
        self.particles = [p for p in self.particles if p.update(dt)]
        self.texts = [t for t in self.texts if t.update(dt)]

    def draw(self, surf):
        for p in self.particles:
            a = clamp(p.life / p.max_life, 0.0, 1.0)
            col = shade(p.color, 0.45 + 0.55 * a)
            s = max(1, int(p.size * (0.35 + 0.65 * a)))
            if p.shape == "circle":
                pygame.draw.circle(surf, col, (int(p.x), int(p.y)), s)
            else:
                pygame.draw.rect(surf, col, (int(p.x), int(p.y), s, s))
        for t in self.texts:
            t.draw(surf)


# ------------------------------------------------------------------------------
# Projectiles
# ------------------------------------------------------------------------------

class Projectile:
    """
    One class covers every flying thing.  `kind` drives the visuals and a few
    behavioural switches; `hostile` flips who it is allowed to hurt.

    kinds: 'arrow' | 'bolt' | 'cannon' | 'magic' | 'fire' | 'bone'
    """

    def __init__(self, game, x, y, vx, vy, kind, damage,
                 splash=0.0, pierce=0, grav=0.0, hostile=False,
                 color=None, life=5.0, stun=0.0,
                 bonus_air=1.0, bonus_heavy=1.0):
        self.game = game
        self.x, self.y = float(x), float(y)
        self.vx, self.vy = float(vx), float(vy)
        self.kind = kind
        self.damage = float(damage)
        self.splash = float(splash)
        self.pierce = int(pierce)
        self.grav = float(grav)
        self.hostile = bool(hostile)
        # counter multipliers travel with the shot and are resolved against
        # each victim individually
        self.bonus_air = float(bonus_air)
        self.bonus_heavy = float(bonus_heavy)
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
        self.vy += self.grav * dt
        self.x += self.vx * dt
        self.y += self.vy * dt
        self.life -= dt

        if self.life <= 0 or self.x < -120 or self.x > WIDTH + 220 or self.y > HEIGHT + 200:
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
        for e in self.game.enemies:
            if not e.alive or id(e) in self.hit_ids or not e.targetable:
                continue
            if e.hit_rect.collidepoint(self.x, self.y):
                self.hit_ids.add(id(e))
                if self.splash > 0:
                    self.explode()
                    return
                e.take_damage(self.damage_for(e), "projectile")
                self.game.effects.burst(self.x, self.y, 5, self.color,
                                        speed=120, life=0.25, size=2)
                if self.pierce > 0:
                    self.pierce -= 1
                    self.damage *= 0.72
                else:
                    self.kill()
                return

    def _update_hostile(self):
        castle = self.game.castle
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
# Defensive towers
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
            self.rebuild = self.REBUILD_TIME
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
        m = 1.0
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
            self.cooldown = self.reload
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

    def draw_status(self, surf):
        if self.disabled:
            draw_text(surf, "X", self.x, self.y - self.H - 18, 22, C_RED,
                      "center", True)
        elif self.hp < self.max_hp:
            draw_bar(surf, int(self.x - 14), int(self.y - self.H - 10),
                     28, 4, self.hp / self.max_hp, C_GREEN)


class Bowman(DefenseTower):
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
            "arrow", self.damage))

    def draw(self, surf):
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
            "bolt", self.damage, pierce=2,
            bonus_air=self.BONUS_VS_AIR, bonus_heavy=self.BONUS_VS_HEAVY))
        self.game.add_shake(1.5)

    def draw(self, surf):
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
            self.game, mx, my, vx, vy, "cannon", self.damage,
            splash=self.splash, grav=GRAVITY, life=t + 1.4,
            bonus_air=self.BONUS_VS_AIR, bonus_heavy=self.BONUS_VS_HEAVY))
        self.game.effects.burst(mx, my, 10, (200, 190, 170), speed=180,
                                life=0.35, grav=200)
        self.game.add_shake(3.2)

    def draw(self, surf):
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
    @property
    def tier_index(self):
        return clamp(self.wall_level - 1, 0, len(self.TIERS) - 1)

    @property
    def tier_name(self):
        return self.TIERS[self.tier_index][0]

    @property
    def max_level(self):
        return len(self.TIERS)

    def upgrade_wall(self):
        if self.wall_level >= self.max_level:
            return False
        self.wall_level += 1
        gained = float(self.TIERS[self.tier_index][4]) - self.max_hp
        self.max_hp += gained
        self.hp = min(self.max_hp, self.hp + gained)
        for t in self.towers:                 # the platforms get tougher too
            t.max_hp *= 1.22
            t.hp = min(t.max_hp, t.hp * 1.22)
        self._cache_key = None
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
        boost = 1.22 ** (self.wall_level - 1)
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
        key = self.wall_level
        if self._cache_key == key and self._cache_surf is not None:
            return self._cache_surf
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

        for t in self.towers:
            t.draw(surf)
        for t in self.towers:
            t.draw_status(surf)


# ------------------------------------------------------------------------------
# Wave scaling
# ------------------------------------------------------------------------------

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
    HEAVY = False          # a tank: cannons get bonus damage against it
    STRIPPABLE = False     # armour can be torn off by dragging on it
    ARMOR_LAYERS = 0       # how many plates there are to tear off
    IS_BOSS = False
    FLY_Y = 250.0
    DESC = ""

    def __init__(self, game, wave, x=None, y=None):
        self.game = game
        self.wave = wave
        hp_m, dmg_m, spd_m = wave_scaling(wave)
        self.max_hp = self.BASE_HP * hp_m
        self.hp = self.max_hp
        # armour is per-instance because the player can tear it off
        self.armor = self.ARMOR
        self.layers = self.ARMOR_LAYERS
        self.strip_progress = 0.0      # 0..1 toward prying the next plate
        self.vulnerable = 1.0          # damage taken multiplier once stripped
        self.speed = self.BASE_SPEED * spd_m
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

    @property
    def grab_rect(self):
        return self.hit_rect.inflate(16, 16)

    @property
    def targetable(self):
        return self.alive

    @property
    def grabbable(self):
        return self.GRABBABLE and self.alive and self.state in ("walk", "attack")

    @property
    def strippable(self):
        """Heavy units refuse to be lifted, but their plating can be pried
        off by hauling on it -- that is the player's answer to a tank."""
        return (self.STRIPPABLE and self.alive and self.layers > 0
                and self.state in ("walk", "attack"))

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
        self.alive = False
        self.hp = 0.0
        g = self.game
        if not silent:
            g.gold += self.gold
            g.stats_kills += 1
            g.effects.burst(self.x, self.y, 18 if not self.IS_BOSS else 90,
                            self.COLOR, speed=280 if not self.IS_BOSS else 520,
                            life=0.7, size=3 if not self.IS_BOSS else 6)
            g.effects.text(self.x, self.y - self.h * 0.6, f"+{self.gold}g",
                           C_GOLD, 20 if not self.IS_BOSS else 34)
            if self.IS_BOSS:
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
        mult = THROW_POWER / (0.55 + 0.45 * self.MASS)
        self.state = "air"
        self.vx = vx * mult
        self.vy = vy * mult
        self.slam_cooldown.clear()
        self.game.effects.burst(self.x, self.y, 8, (220, 220, 240),
                                speed=150, life=0.3)

    def land(self):
        """Called when an airborne mob hits the dirt.  Ouch."""
        impact = math.hypot(self.vx * 0.5, self.vy)
        dmg = max(0.0, impact - FALL_DMG_FLOOR) * FALL_DMG_SCALE * \
            (0.75 + 0.35 * self.MASS)
        g = self.game
        if dmg > 0:
            self.take_damage(dmg, "fall")
            g.effects.text(self.x, self.y - self.h, f"{int(dmg)}",
                           (255, 168, 72), 24)
            g.add_shake(min(7.0, dmg * 0.07))
            g.effects.burst(self.x, self.ground_y + self.h / 2, 16,
                            C_DIRT, speed=min(320, impact * 0.5), life=0.5, size=4)
            g.stats_thrown_damage += dmg
        self.vy = -abs(self.vy) * 0.32
        self.vx *= 0.45
        self.spin *= 0.3
        if abs(self.vy) < 110:
            self.vy = 0.0
            self.spin = 0.0
            self.y = self.ground_y
            if self.alive:
                self.state = "walk"
                self.stagger = 0.45

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
        g.effects.text(other.x, other.y - other.h * 0.7, f"{int(dmg)}",
                       (255, 208, 96), 22)
        g.effects.burst((self.x + other.x) / 2, (self.y + other.y) / 2, 12,
                        (255, 220, 150), speed=240, life=0.4, size=3)
        g.add_shake(2.5)
        if other.alive and other.GRABBABLE and other.state in ("walk", "attack"):
            other.state = "air"
            other.vx = self.vx * 0.4
            other.vy = min(-140.0, self.vy * 0.5)
            other.slam_cooldown.clear()
            other.slam_cooldown[id(self)] = 0.4
        self.vx *= 0.55
        self.vy *= 0.55

    # -- update ----------------------------------------------------------
    def update(self, dt):
        self.hurt_flash = max(0.0, self.hurt_flash - dt * 4.0)
        self.stagger = max(0.0, self.stagger - dt)
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
        self.vy += GRAVITY * dt
        self.vx -= self.vx * AIR_DRAG * dt
        self.x += self.vx * dt
        self.y += self.vy * dt
        self.spin += self.vx * dt * 0.012
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

        # mid-air collisions
        for o in self.game.enemies:
            if o is self or not o.alive or id(o) in self.slam_cooldown:
                continue
            if o.state == "grabbed":
                continue
            if self.hit_rect.colliderect(o.hit_rect):
                self.slam_cooldown[id(o)] = 0.35
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
        self.anim += dt * self.speed * 0.06
        if self.flying:
            self.bob += dt * 3.0
            target_y = self.fly_y + math.sin(self.bob) * 18
            self.y += clamp(target_y - self.y, -160 * dt, 160 * dt)

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

    def attack_castle(self):
        self.game.castle.take_damage(self.damage)
        self.game.effects.burst(self.game.castle.front_x, self.y, 8,
                                (220, 200, 180), speed=180, life=0.35)

    # -- drawing ---------------------------------------------------------
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
        self.draw_hp(surf)

    def draw_body(self, surf):
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
    GRABBABLE = False      # ...too determined to be lifted...
    HEAVY = True           # ...but Cannons hit it for triple...
    STRIPPABLE = True      # ...and the player can rip the plates off
    ARMOR_LAYERS = 3

    def __init__(self, game, wave, x=None, y=None):
        super().__init__(game, wave, x, y)
        self.ram_push = 0.0

    def attack_castle(self):
        self.ram_push = 1.0
        self.game.castle.take_damage(self.damage)
        self.game.add_shake(9.0)
        self.game.effects.burst(self.game.castle.front_x, self.y, 26,
                                (190, 170, 150), speed=320, life=0.6, size=5)
        self.game.effects.text(self.game.castle.front_x + 30, self.y - 40,
                               "SMASH!", (255, 140, 110), 26)

    def think(self, dt):
        self.ram_push = max(0.0, self.ram_push - dt * 3.0)
        super().think(dt)

    def draw_body(self, surf):
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


class Necromancer(Enemy):
    NAME = "Necromancer"
    DESC = "Hangs back and raises skeletons."
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

    def think(self, dt):
        self.anim += dt * 3.0
        self.glow = max(0.0, self.glow - dt * 2.0)
        self.minions = [m for m in self.minions if m.alive]

        if self.x > self.standoff_x:
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
            math.sin(a) * speed, "magic", self.damage, hostile=True))
        self.glow = 1.0

    def draw_body(self, surf):
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
    HINT = "Immune to throws and stripping - hurl OTHER mobs into him."


    def __init__(self, game, wave, x=None, y=None):
        super().__init__(game, wave, x, y)
        self.leap_timer = random.uniform(4.0, 6.0)
        self.smash = 0.0

    def think(self, dt):
        self.smash = max(0.0, self.smash - dt * 2.5)
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
        # crown
        pygame.draw.polygon(surf, C_GOLD, [
            (r.centerx - 20, r.y + 12), (r.centerx + 20, r.y + 12),
            (r.centerx + 16, r.y - 4), (r.centerx + 8, r.y + 6),
            (r.centerx, r.y - 8), (r.centerx - 8, r.y + 6),
            (r.centerx - 16, r.y - 4)])
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
        self.breath_timer = 4.0
        self.breathing = 0.0
        self.shot_timer = 0.0
        self.standoff_x = CASTLE_FRONT + 330

    def think(self, dt):
        self.anim += dt * 6.0
        self.bob += dt * 2.2
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
                self.shot_timer = self.SHOT_INTERVAL
                self.spit_fire(self.BREATH_POWER)
            return

        self.breath_timer -= dt
        if self.breath_timer <= 0:
            self.breath_timer = random.uniform(4.4, 5.8)
            self.breathing = self.BREATH_TIME
            self.shot_timer = 0.0
            self.game.effects.text(self.x, self.y - self.h, "FIRE BREATH!",
                                   (255, 170, 80), 28)

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
            stun=0.8))
        self.game.effects.burst(mx, my, 8, (255, 180, 80), speed=200,
                                life=0.35, grav=-60)

    def draw_body(self, surf):
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
    HINT = "Summons endlessly - splash damage clears his skeletons fastest."


    def __init__(self, game, wave, x=None, y=None):
        super().__init__(game, wave, x, y)
        # kept inside the reach of a front-slot Bowman so the wave can
        # always be finished, whatever the player built
        self.standoff_x = CASTLE_FRONT + 400
        self.summon_timer = 3.0
        self.bolt_timer = 2.0
        self.phase_timer = 0.0
        self.shield = 0.0
        self.orb = 0.0

    def take_damage(self, amount, kind="projectile"):
        if self.shield > 0:
            amount *= 0.25
        return super().take_damage(amount, kind)

    def think(self, dt):
        self.anim += dt * 2.0
        self.orb = max(0.0, self.orb - dt * 2.0)
        self.shield = max(0.0, self.shield - dt)

        if self.x > self.standoff_x:
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
            self.bolt_timer = random.uniform(1.2, 2.0)
            self.death_bolt()

        self.phase_timer -= dt
        if self.phase_timer <= 0:
            self.phase_timer = random.uniform(9.0, 13.0)
            self.shield = 4.0
            self.game.effects.text(self.x, self.y - self.h, "BONE WARD",
                                   (170, 220, 255), 24)

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
        live = [t for t in c.towers if not t.disabled]
        if live and random.random() < 0.5:
            t = random.choice(live)
            tx, ty = t.x, t.y - t.H / 2
        else:
            tx, ty = c.front_x - 30, random.uniform(WALL_TOP + 20, GROUND_Y - 30)
        a = math.atan2(ty - (self.y - 20), tx - self.x)
        speed = 520.0
        self.game.projectiles.append(Projectile(
            self.game, self.x, self.y - 20, math.cos(a) * speed,
            math.sin(a) * speed, "magic", self.damage, splash=54.0,
            hostile=True, life=4.0))
        self.orb = 1.0

    def draw_body(self, surf):
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
        # staff
        stx = r.right + 6
        pygame.draw.line(surf, (76, 62, 96), (stx, r.bottom), (stx, r.y - 14), 5)
        gr = 10 + int(7 * self.orb)
        glow = pygame.Surface((gr * 4, gr * 4), pygame.SRCALPHA)
        pygame.draw.circle(glow, (170, 120, 255, 90), (gr * 2, gr * 2), gr * 2)
        surf.blit(glow, (stx - gr * 2, r.y - 16 - gr * 2))
        pygame.draw.circle(surf, (196, 150, 255), (stx, r.y - 16), gr)
        pygame.draw.circle(surf, (240, 220, 255), (stx, r.y - 16), max(2, gr - 4))


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
]

BOSS_ROTATION = [TrollKing, Dragon, LichLord]
# lets the shop cards show each tower's strategic counter tag
TOWER_FOR_KEY = {"bowman": Bowman, "ballista": Ballista, "cannon": Cannon}
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
    boss = boss_for_wave(wave)
    if boss is not None:
        # boss walks in a little after the vanguard
        picks.insert(min(len(picks), 3), boss)
        if wave >= 20:                      # late game: a second, older boss
            picks.append(BOSS_ROTATION[(wave // 5) % 3])
    return picks


# ------------------------------------------------------------------------------
# Shop
# ------------------------------------------------------------------------------

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
        self.rect = pygame.Rect(0, 0, 0, 0)

    @property
    def cost(self):
        return int(self.cost_fn())


def wrap_text(text, size, max_w):
    f = font(size)
    words, lines, cur = text.split(), [], ""
    for wd in words:
        trial = (cur + " " + wd).strip()
        if f.size(trial)[0] <= max_w or not cur:
            cur = trial
        else:
            lines.append(cur)
            cur = wd
    if cur:
        lines.append(cur)
    return lines


# ------------------------------------------------------------------------------
# The Game
# ------------------------------------------------------------------------------

class Game:
    MENU, PLAYING, SHOP, PAUSED, GAMEOVER = "menu", "playing", "shop", "paused", "over"

    def __init__(self, screen=None, headless=False):
        self.headless = headless
        self.screen = screen
        self.scene = pygame.Surface((WIDTH, HEIGHT))
        self.clock = pygame.time.Clock()
        self.running = True
        self.bg = self._build_background()
        self.reset()

    # -- lifecycle -------------------------------------------------------
    def reset(self):
        self.state = self.MENU
        self.time = 0.0
        self.wave = 0
        self.gold = STARTING_GOLD
        self.castle = Castle(self)
        self.enemies = []
        self.projectiles = []
        self.effects = Effects()
        self.spawn_queue = []
        self.spawn_timer = 0.0
        self.spawn_interval = 1.0
        self.wave_active = False
        self.wave_clear_delay = 0.0
        self.shake = 0.0
        self.banners = []
        self.grabbed = None
        self.stripping = None          # heavy unit currently being dismantled
        self.strip_anchor = (0, 0)
        self.grab_cd = 0.0
        self.mouse_hist = deque(maxlen=12)
        self.mouse_pos = (WIDTH // 2, HEIGHT // 2)
        self.stats_kills = 0
        self.stats_thrown_damage = 0.0
        self.stats_plates_torn = 0
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
                     "Heavy piercing bolt, slow reload. HARD COUNTER to "
                     "flyers: +200% damage to anything airborne.",
                     lambda: 250 * (1.28 ** self.purchases["ballista"]),
                     buy_tower(Ballista, "ballista"),
                     tower_status(Ballista, "ballista")),
            ShopItem("cannon", "Cannon", (168, 174, 196),
                     "Explosive splash on the densest cluster. HARD COUNTER "
                     "to tanks: +200% damage to Heavy ground units.",
                     lambda: 380 * (1.28 ** self.purchases["cannon"]),
                     buy_tower(Cannon, "cannon"),
                     tower_status(Cannon, "cannon")),
            ShopItem("wall", "Reinforce Walls", (206, 212, 228),
                     "Raises max castle health, adds a tower emplacement, and "
                     "visibly rebuilds the keep in tougher material.",
                     wall_cost, buy_wall,
                     lambda: f"Lv.{self.castle.wall_level}/{self.castle.max_level}"
                             f"   {len(self.castle.towers)}/"
                             f"{self.castle.slot_capacity} slots"),
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
    def add_shake(self, amount):
        # capped: the world is blitted at an offset, so a big shake would
        # expose bare edges at the screen border
        self.shake = min(14.0, self.shake + amount)

    def announce(self, text, color=C_WHITE, life=2.6):
        self.banners.append([text, color, life, life])

    def spawn_enemy(self, e):
        self.enemies.append(e)

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
    def start_wave(self):
        self.wave += 1
        self.state = self.PLAYING
        self.wave_active = True
        self.wave_clear_delay = 0.0
        self.spawn_queue = build_wave(self.wave)
        self.spawn_interval = max(0.32, 1.25 - self.wave * 0.032)
        self.spawn_timer = 0.8
        self.castle.restore_towers()
        self.announce(f"WAVE {self.wave}", C_GOLD, 2.2)
        for cls in newly_unlocked(self.wave):
            self.announce(f"New foe: {cls.NAME} - {cls.DESC}", cls.COLOR, 4.2)
        boss = boss_for_wave(self.wave)
        if boss is not None:
            self.announce(f"!! {boss.NAME} approaches !!", (255, 120, 100), 4.5)

    def open_first_shop(self):
        """Menu -> armoury, so the player can build a defence before wave 1."""
        self.state = self.SHOP
        self.shop_msg = "Spend your starting gold, then send in wave 1."
        self.shop_msg_t = 6.0

    def end_wave(self):
        self.wave_active = False
        bonus = 80 + self.wave * 22
        self.gold += bonus
        self.castle.restore_towers()
        self.effects.clear()
        self.projectiles.clear()
        self.grabbed = None
        self.stripping = None
        self.shop_msg = f"Wave {self.wave} cleared!  Bonus +{bonus} gold."
        self.shop_msg_t = 4.0
        self.state = self.SHOP

    def on_castle_destroyed(self):
        self.state = self.GAMEOVER
        self.grabbed = None
        self.stripping = None
        self.add_shake(14.0)
        self.effects.burst(CASTLE_FRONT * 0.5, WALL_TOP + 60, 160,
                           (200, 120, 90), speed=700, life=1.4, size=6)

    # -- purchasing ------------------------------------------------------
    def try_buy(self, item):
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
        """A heavy unit whose plating can still be torn off."""
        best = None
        for e in self.enemies:
            if not e.strippable:
                continue
            if e.grab_rect.collidepoint(pos):
                if best is None or e.x < best.x:
                    best = e
        return best

    def try_grab(self, pos):
        if self.grab_cd > 0 or self.grabbed is not None or self.stripping:
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
        self.mouse_hist.clear()
        self.mouse_hist.append((self.time, pos[0], pos[1]))
        self.effects.ring(e.x, e.y, 12, (230, 230, 255), speed=200,
                          life=0.3, size=3)

    def release_grab(self):
        if self.stripping is not None:
            self.stripping = None
            self.grab_cd = GRAB_COOLDOWN
            return
        e = self.grabbed
        self.grabbed = None
        self.grab_cd = GRAB_COOLDOWN
        if e is None or not e.alive:
            return
        vx = vy = 0.0
        if len(self.mouse_hist) >= 2:
            t1, x1, y1 = self.mouse_hist[-1]
            # sample ~90 ms back for a stable velocity
            t0, x0, y0 = self.mouse_hist[0]
            for sample in self.mouse_hist:
                if t1 - sample[0] <= 0.09:
                    t0, x0, y0 = sample
                    break
            dt = max(1e-3, t1 - t0)
            vx = clamp((x1 - x0) / dt, -2600, 2600)
            vy = clamp((y1 - y0) / dt, -2600, 2600)
        e.on_release(vx, vy)

    def update_grab(self, dt):
        self.grab_cd = max(0.0, self.grab_cd - dt)

        # --- dismantling a heavy unit ---------------------------------
        h = self.stripping
        if h is not None:
            if not h.strippable:
                self.stripping = None      # dead, or fully stripped already
            else:
                mx, my = self.mouse_pos
                ax, ay = self.strip_anchor
                pulled = math.hypot(mx - ax, my - ay)
                self.strip_anchor = (mx, my)
                if pulled > 0.5:
                    h.apply_strip(pulled)
                    if random.random() < 0.4:
                        self.effects.burst(h.x + random.uniform(-30, 30),
                                           h.y, 2, (188, 192, 206),
                                           speed=120, life=0.3, size=2)
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

    # -- main update -----------------------------------------------------
    def update(self, dt):
        self.time += dt
        self.shake = max(0.0, self.shake - dt * 42.0)
        for b in self.banners:
            b[2] -= dt
        self.banners = [b for b in self.banners if b[2] > 0]
        if self.shop_msg_t > 0:
            self.shop_msg_t -= dt

        if self.state != self.PLAYING:
            # the hit flash must keep fading even on the pause / defeat
            # screens, or the castle stays frozen mid-flash
            self.castle.flash = max(0.0, self.castle.flash - dt * 5.0)
            self.effects.update(dt)
            return

        self.update_grab(dt)

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

        self.castle.update(dt)
        for e in self.enemies:
            e.update(dt)
        self.separate_enemies(dt)
        for p in self.projectiles:
            p.update(dt)
        self.projectiles = [p for p in self.projectiles if p.alive]
        self.enemies = [e for e in self.enemies if e.alive]
        self.effects.update(dt)

        if self.grabbed is not None and not self.grabbed.alive:
            self.grabbed = None
        if self.stripping is not None and not self.stripping.alive:
            self.stripping = None

        # wave completion
        if self.wave_active and not self.spawn_queue and not self.alive_enemies():
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
            if ev.key in (pygame.K_ESCAPE, pygame.K_p):
                if self.state == self.PLAYING:
                    self.state = self.PAUSED
                elif self.state == self.PAUSED:
                    self.state = self.PLAYING
            elif ev.key in (pygame.K_SPACE, pygame.K_RETURN, pygame.K_KP_ENTER):
                if self.state == self.MENU:
                    self.open_first_shop()
                elif self.state == self.SHOP:
                    self.start_wave()
            elif ev.key == pygame.K_r and self.state == self.GAMEOVER:
                self.reset()
                self.state = self.MENU
            elif self.state == self.SHOP and pygame.K_1 <= ev.key <= pygame.K_5:
                idx = ev.key - pygame.K_1
                if idx < len(self.shop_items):
                    self.try_buy(self.shop_items[idx])
            return

        if ev.type == pygame.MOUSEBUTTONDOWN and ev.button == 1:
            self.mouse_pos = ev.pos
            if self.state == self.PLAYING:
                self.try_grab(ev.pos)
            elif self.state == self.MENU:
                self.open_first_shop()
            elif self.state == self.SHOP:
                if self.start_btn.collidepoint(ev.pos):
                    self.start_wave()
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
            if self.grabbed is not None or self.stripping is not None:
                self.release_grab()
            return

    # -- drawing ---------------------------------------------------------
    def draw(self):
        s = self.scene
        s.blit(self.bg, (0, 0))
        self.castle.draw(s)

        # flying first (behind), then ground back-to-front by depth
        order = sorted(self.enemies,
                       key=lambda e: (0 if e.flying else 1, e.depth, e.x))
        for e in order:
            e.draw(s)
        for p in self.projectiles:
            p.draw(s)
        self.effects.draw(s)

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
        elif self.state == self.PAUSED:
            self.draw_center_panel(self.screen, "PAUSED",
                                   ["Press P or ESC to resume."])
        elif self.state == self.GAMEOVER:
            self.draw_gameover(self.screen)

    def draw_grab_cursor(self, s):
        mx, my = self.mouse_pos

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
            draw_text(s, "PULL!" if self.stripping else "DRAG TO RIP ARMOR",
                      heavy.x, r.top - 48, 18, col, "center", True)
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
        panel = pygame.Surface((360, 96), pygame.SRCALPHA)
        panel.fill((*C_PANEL, 190))
        pygame.draw.rect(panel, (*C_PANEL_EDGE, 200), panel.get_rect(), 2,
                         border_radius=6)
        surf.blit(panel, (14, 12))

        draw_text(surf, f"WAVE {max(1, self.wave)}", 28, 22, 30, C_WHITE, bold=True)
        draw_text(surf, f"{self.gold} G", 346, 24, 28, C_GOLD, "right", True)
        draw_text(surf, self.castle.tier_name, 28, 52, 18, C_DIM)

        frac = self.castle.hp / max(1.0, self.castle.max_hp)
        col = C_GREEN if frac > 0.5 else (C_GOLD if frac > 0.25 else C_RED)
        draw_bar(surf, 28, 74, 318, 16, frac, col)
        draw_text(surf, f"{int(self.castle.hp)} / {int(self.castle.max_hp)}",
                  187, 75, 18, C_WHITE, "center")

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
    def draw_center_panel(self, surf, title, lines, w=620, h=280):
        veil = pygame.Surface((WIDTH, HEIGHT), pygame.SRCALPHA)
        veil.fill((6, 8, 16, 170))
        surf.blit(veil, (0, 0))
        r = pygame.Rect((WIDTH - w) // 2, (HEIGHT - h) // 2, w, h)
        pygame.draw.rect(surf, C_PANEL, r, border_radius=10)
        pygame.draw.rect(surf, C_PANEL_EDGE, r, 3, border_radius=10)
        draw_text(surf, title, r.centerx, r.top + 26, 44, C_GOLD, "center", True)
        y = r.top + 90
        for ln in lines:
            draw_text(surf, ln, r.centerx, y, 22, C_WHITE, "center")
            y += 30
        return r

    def draw_menu(self, surf):
        r = self.draw_center_panel(surf, "CASTLE DEFENSE", [], w=760, h=440)
        lines = [
            ("Endless waves. 8 mob types, 3 bosses, one castle.", C_DIM),
            ("", C_DIM),
            ("THROW:  hold LEFT MOUSE on a ground mob, then FLICK", C_WHITE),
            ("and release to hurl it. Falls hurt; landings crush others.", C_GREEN),
            ("", C_DIM),
            ("STRIP:  Siege Rams refuse to be lifted -- drag on one", C_WHITE),
            ("repeatedly to rip its armour plates off, slowing it and", (255, 200, 140)),
            ("leaving it wide open to your guns.", (255, 200, 140)),
            ("", C_DIM),
            ("Bowmen reach high, Ballistas shred flyers (+200%),", C_HILITE),
            ("Cannons shred tanks (+200%). Bosses need pure firepower.", C_HILITE),
            ("", C_DIM),
            ("Click or press SPACE to visit the armoury", C_GOLD),
        ]
        y = r.top + 84
        for text, col in lines:
            size = 24 if col is C_GOLD else 20
            draw_text(surf, text, r.centerx, y, size, col, "center",
                      bold=(col is C_GOLD))
            y += 27

    def draw_gameover(self, surf):
        self.draw_center_panel(surf, "THE CASTLE HAS FALLEN", [
            f"You survived {max(0, self.wave - 1)} full waves"
            f" (fell on wave {self.wave}).",
            f"Enemies slain: {self.stats_kills}",
            f"Damage dealt by throwing: {int(self.stats_thrown_damage)}",
            f"Armour plates torn off: {self.stats_plates_torn}",
            f"Final walls: {self.castle.tier_name}",
            "",
            "Press R or click to play again.",
        ], w=700, h=340)

    def draw_shop(self, surf):
        veil = pygame.Surface((WIDTH, HEIGHT), pygame.SRCALPHA)
        veil.fill((6, 8, 16, 195))
        surf.blit(veil, (0, 0))

        panel = pygame.Rect(70, 78, WIDTH - 140, HEIGHT - 156)
        pygame.draw.rect(surf, C_PANEL, panel, border_radius=12)
        pygame.draw.rect(surf, C_PANEL_EDGE, panel, 3, border_radius=12)

        draw_text(surf, "ARMOURY", panel.centerx, panel.top + 16, 40,
                  C_GOLD, "center", True)
        draw_text(surf, f"Gold: {self.gold}", panel.right - 30, panel.top + 24,
                  30, C_GOLD, "right", True)
        draw_text(surf, f"Next up: Wave {self.wave + 1}", panel.left + 30,
                  panel.top + 26, 24, C_DIM)

        if self.shop_msg and self.shop_msg_t > 0:
            draw_text(surf, self.shop_msg, panel.centerx, panel.top + 60, 22,
                      C_HILITE, "center")

        # cards
        n = len(self.shop_items)
        cw, gap = 200, 18
        total = n * cw + (n - 1) * gap
        x0 = panel.centerx - total // 2
        cy, ch = panel.top + 96, 300
        mouse = self.mouse_pos
        for i, item in enumerate(self.shop_items):
            r = pygame.Rect(x0 + i * (cw + gap), cy, cw, ch)
            item.rect = r
            cost = item.cost
            afford = self.gold >= cost
            hover = r.collidepoint(mouse)
            bgc = (40, 44, 62) if afford else (32, 32, 40)
            if hover and afford:
                bgc = (56, 62, 86)
            pygame.draw.rect(surf, bgc, r, border_radius=10)
            pygame.draw.rect(surf, item.color if afford else (70, 70, 80),
                             r, 3, border_radius=10)

            pygame.draw.rect(surf, item.color, (r.x + 12, r.y + 12, r.w - 24, 6),
                             border_radius=3)
            draw_text(surf, f"[{i + 1}]", r.x + 12, r.y + 24, 18, C_DIM)
            draw_text(surf, item.name, r.centerx, r.y + 42, 26,
                      C_WHITE if afford else (130, 130, 140), "center", True)

            self._draw_shop_icon(surf, item.key, r.centerx, r.y + 108, item.color)

            tower_cls = TOWER_FOR_KEY.get(item.key)
            if tower_cls is not None and tower_cls.COUNTER_TAG:
                draw_text(surf, tower_cls.COUNTER_TAG, r.centerx, r.y + 132, 17,
                          (255, 206, 120), "center", True)

            ty = r.y + 150
            for ln in wrap_text(item.desc, 17, r.w - 26):
                draw_text(surf, ln, r.centerx, ty, 17, C_DIM, "center")
                ty += 20

            draw_text(surf, item.status_fn(), r.centerx, r.bottom - 62, 18,
                      (170, 205, 235), "center")
            price_col = C_GOLD if afford else (140, 120, 80)
            draw_text(surf, f"{cost} G", r.centerx, r.bottom - 38, 30,
                      price_col, "center", True)

        # start button
        bw, bh = 360, 62
        self.start_btn = pygame.Rect(panel.centerx - bw // 2,
                                     panel.bottom - bh - 22, bw, bh)
        hov = self.start_btn.collidepoint(mouse)
        pygame.draw.rect(surf, (58, 118, 92) if hov else (42, 92, 72),
                         self.start_btn, border_radius=10)
        pygame.draw.rect(surf, C_GREEN, self.start_btn, 3, border_radius=10)
        draw_text(surf, f"START WAVE {self.wave + 1}   [SPACE]",
                  self.start_btn.centerx, self.start_btn.y + 16, 30,
                  C_WHITE, "center", True)

        nxt = boss_for_wave(self.wave + 1)
        if nxt is not None:
            draw_text(surf, f"WARNING: {nxt.NAME} arrives next wave!",
                      panel.centerx, panel.bottom - bh - 74, 24,
                      (255, 130, 110), "center", True)
            if nxt.HINT:
                draw_text(surf, nxt.HINT, panel.centerx,
                          panel.bottom - bh - 50, 20, (255, 190, 150), "center")

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
            if ((self.grabbed is not None or self.stripping is not None)
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

    g.reset()
    assert g.state == Game.MENU
    ev(type=pygame.MOUSEBUTTONDOWN, button=1, pos=(640, 400))
    assert g.state == Game.SHOP, "menu should open the armoury"
    g.draw()

    g.gold = 5000
    for i, item in enumerate(g.shop_items):          # click every card
        ev(type=pygame.MOUSEBUTTONDOWN, button=1, pos=item.rect.center)
        g.draw()
    for k in (pygame.K_1, pygame.K_2, pygame.K_3, pygame.K_4, pygame.K_5):
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
