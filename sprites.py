"""
Castle Defense -- shared foundation: configuration, palette, drawing
helpers, text layout, particle effects, and the asset system.

Every module imports from here; this module imports nothing of its own, so
the dependency graph stays acyclic:

    sprites  <-  castle  <-  enemies  <-  main

"""

import math
import random
import os
import pygame

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

# --- boss regalia (crown / staff) ---------------------------------------
REGALIA_MAX_THROW = {"crown": 2300.0, "staff": 780.0}
CROWN_RETRIEVE_SPEED = 1.7     # the Troll King hurries when uncrowned
STAFF_DISARM_TIME = 5.0        # seconds the Lich Lord spends re-focusing
# Once a boss gets its regalia back it guards it, and grows wiser every time
# it is robbed -- without this the player could stun-lock a boss forever.
REGALIA_COOLDOWN = 6.0
REGALIA_CD_GROWTH = 0.6

# --- bounce upgrade ------------------------------------------------------
BOUNCE_MAX_LEVEL = 5
# restitution per level -- capped so a mob can never bounce forever
BOUNCE_RESTITUTION = (0.32, 0.46, 0.56, 0.64, 0.71, 0.78)
BOUNCE_DMG_BONUS = 0.22        # extra impact damage per level ("weight")
BOUNCE_STAGGER = 0.34          # extra recovery time per level, in seconds

# --- weather --------------------------------------------------------------
WIND_MAX = 260.0             # px/s^2 pushed onto anything airborne
WIND_PROJECTILE = 0.45       # projectiles are less affected than bodies
STORM_CHANCE = 0.3           # of waves that break into a thunderstorm
STORM_CEILING = 132.0        # fling a mob above this line and it gets hit
STORM_DAMAGE = 0.34          # fraction of a mob's max health per strike
STORM_COOLDOWN = 1.1         # per-mob, so one mob is not fried 60x a second

# --- Dragon claw smacking --------------------------------------------------
CLAW_SMACK_DISTANCE = 360.0  # px of dragging to land a solid smack
CLAW_STAGGER = 3.2           # seconds the Dragon reels for

# --- Challenge Horn --------------------------------------------------------
HORN_RECT = pygame.Rect(170, 452, 58, 60)
HORN_BONUS = 0.6             # extra gold and score for the rest of the wave

# --- endgame mob tiers (after the first three bosses are behind you) ------
# (name, first wave, colour tint, tint strength, hp mult, dmg mult, spd mult)
ENDGAME_TIERS = (
    ("Bloodied",    16, (214,  58,  52), 0.42, 1.35, 1.22, 1.05),
    ("Frostbound",  26, ( 74, 148, 230), 0.46, 1.85, 1.46, 1.10),
    ("Voidtouched", 36, ( 24,  20,  34), 0.55, 2.60, 1.80, 1.16),
)

# --- cursor strength ------------------------------------------------------
GRAB_MAX_LEVEL = 4
# the heaviest MASS the cursor can lift at each Grab Strength level.
# A Siege Ram is MASS 9.0, so it stays unliftable until level 3.
GRAB_CAPACITY = (3.5, 5.5, 7.5, 9.5, 13.0)
MULTI_MAX_LEVEL = 3          # Magnetic Gloves: extra mobs held at once
MULTI_RADIUS = 110.0

# --- shoving a heavy unit castle-ward -------------------------------------
SHOVE_FACTOR = 3.4           # forward momentum gained per px dragged inward
SHOVE_DECAY = 1.6            # fraction of the shove bled off per second
SHOVE_MAX = 340.0

# --- manual overcharge (slingshot) ----------------------------------------
OVERCHARGE_PULL = 190.0      # px of draw-back for a full-power shot
OVERCHARGE_DAMAGE = 2.5      # +150% damage on an overcharged round
OVERCHARGE_SPLASH = 1.7      # blast radius multiplier
OVERCHARGE_SPEED = 1.45      # projectile speed multiplier
OVERCHARGE_COOLDOWN = 5.0    # per-tower, so it cannot simply be spammed

# --- world structures ------------------------------------------------------
OUTPOST_X = 1015.0           # safely back in the scenery; mobs ignore it
OUTPOST_BASE_Y = 505.0
OUTPOST_MAX_LEVEL = 6
OUTPOST_TURRET_FROM = 4      # at this garrison level the bows become turrets
OUTPOST_RANGE = 560.0

BARRICADE_X = 585.0          # out on the field, well ahead of the wall
BARRICADE_MAX_LEVEL = 5
BARRICADE_HP = (0, 340, 620, 980, 1450, 2050)

SPIKE_MAX_LEVEL = 4
SPIKE_DAMAGE = 15.0          # reflected onto anything striking the wall

# --- risk / reward -------------------------------------------------------
POP_GOLD_FREE = 4              # mobs on screen before the bonus kicks in
POP_GOLD_STEP = 0.055          # extra gold multiplier per additional mob
POP_GOLD_CAP = 3.0
SCORE_PER_PX = 0.32            # fling score per pixel travelled
SCORE_PER_SEC = 55.0           # fling score per second airborne
SCORE_COMBO_STEP = 0.75        # combo multiplier added per mob struck

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
# Assets
# ------------------------------------------------------------------------------
#
# The game ships with no image files: every sprite below is drawn from pygame
# primitives, so it runs the moment you clone it.  Drop a PNG into assets/ and
# it is picked up automatically on the next run -- no code change needed.
#
#   assets/castle.png      assets/barricade.png    assets/outpost.png
#   assets/scout.png       assets/foot_soldier.png assets/shield_bearer.png
#   assets/berzerker.png   assets/siege_ram.png    assets/necromancer.png
#   assets/assassin.png    assets/gargoyle.png     assets/volatile.png
#   assets/treasure_goblin.png  assets/skeleton.png
#   assets/troll_king.png  assets/dragon.png       assets/lich_lord.png
#   assets/bowman.png      assets/ballista.png     assets/cannon.png
#
# ASSETS.get(name) returns None when no file exists, and the caller falls back
# to its hand-drawn version.  ASSETS[name] always returns a surface, using a
# labelled placeholder box when the file is missing -- handy while blocking out
# new artwork.

ASSET_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "assets")
ASSET_EXTS = (".png", ".jpg", ".jpeg", ".bmp", ".gif")

# name -> (width, height, placeholder colour) used only for generated stand-ins
ASSET_SPECS = {
    "castle":          (252, 390, (132, 132, 140)),
    "barricade":       (40, 96, (128, 112, 88)),
    "outpost":         (84, 70, (74, 78, 92)),
    "bowman":          (22, 32, (118, 186, 108)),
    "ballista":        (34, 30, (206, 150, 84)),
    "cannon":          (36, 28, (92, 96, 110)),
    "scout":           (20, 26, (118, 204, 116)),
    "foot_soldier":    (26, 34, (92, 130, 200)),
    "shield_bearer":   (34, 38, (176, 148, 96)),
    "berzerker":       (26, 32, (222, 96, 64)),
    "siege_ram":       (76, 44, (128, 92, 58)),
    "skeleton":        (18, 28, (222, 220, 206)),
    "necromancer":     (26, 38, (146, 96, 196)),
    "assassin":        (22, 30, (62, 66, 92)),
    "gargoyle":        (30, 26, (122, 126, 140)),
    "volatile":        (26, 28, (232, 138, 52)),
    "treasure_goblin": (24, 30, (218, 176, 60)),
    "troll_king":      (84, 112, (108, 156, 92)),
    "dragon":          (118, 62, (198, 62, 58)),
    "lich_lord":       (70, 100, (120, 92, 190)),
}


class AssetStore:
    """Loads artwork from assets/ if it is there, and stands in for it if not.

    Nothing here is required for the game to run -- every caller keeps its
    procedural drawing as the fallback, so an empty assets/ folder simply
    means you see the hand-drawn version.
    """

    def __init__(self, directory=ASSET_DIR):
        self.directory = directory
        self._images = {}        # name -> Surface | None (None = no file)
        self._scaled = {}        # (name, w, h) -> Surface
        self._placeholders = {}
        self.loaded = []         # names actually read from disk

    # -- loading ---------------------------------------------------------
    def _find(self, name):
        for ext in ASSET_EXTS:
            path = os.path.join(self.directory, name + ext)
            if os.path.isfile(path):
                return path
        return None

    def get(self, name):
        """The real artwork for `name`, or None if no file was supplied."""
        if name in self._images:
            return self._images[name]
        img = None
        path = self._find(name)
        if path is not None:
            try:
                img = pygame.image.load(path)
                img = img.convert_alpha() if pygame.display.get_surface() \
                    else img.convert_alpha(pygame.Surface((1, 1), pygame.SRCALPHA))
                self.loaded.append(name)
            except pygame.error:
                img = None       # unreadable file: fall back, never crash
        self._images[name] = img
        return img

    def scaled(self, name, w, h):
        """Real artwork scaled to (w, h), cached.  None if there is no file."""
        w, h = max(1, int(w)), max(1, int(h))
        key = (name, w, h)
        if key in self._scaled:
            return self._scaled[key]
        img = self.get(name)
        if img is not None and img.get_size() != (w, h):
            img = pygame.transform.smoothscale(img, (w, h))
        self._scaled[key] = img
        return img

    # -- placeholders ----------------------------------------------------
    def placeholder(self, name):
        """A generated stand-in: a labelled, hatched box in the unit's colour."""
        if name in self._placeholders:
            return self._placeholders[name]
        w, h, colour = ASSET_SPECS.get(name, (32, 32, (200, 80, 200)))
        surf = pygame.Surface((w, h), pygame.SRCALPHA)
        surf.fill((*colour, 235))
        pygame.draw.rect(surf, shade(colour, 0.5), surf.get_rect(), 2)
        for i in range(-h, w, 10):      # diagonal hatch marks it as temporary
            pygame.draw.line(surf, (*shade(colour, 1.25), 90),
                             (i, 0), (i + h, h), 1)
        if w >= 40 and h >= 22:
            label = font(14).render(name[:10], True, (255, 255, 255))
            surf.blit(label, label.get_rect(center=(w // 2, h // 2)))
        self._placeholders[name] = surf
        return surf

    def __getitem__(self, name):
        """Always a surface: the real file if present, else a placeholder."""
        img = self.get(name)
        return img if img is not None else self.placeholder(name)

    def __contains__(self, name):
        return self.get(name) is not None

    # -- reporting -------------------------------------------------------
    def missing(self):
        return sorted(n for n in ASSET_SPECS if self.get(n) is None)

    def report(self):
        have = len(ASSET_SPECS) - len(self.missing())
        return f"assets: {have}/{len(ASSET_SPECS)} supplied from {self.directory}"


ASSETS = AssetStore()


def blit_asset(surf, name, rect):
    """Draw supplied artwork into `rect`.  Returns False when there is no
    file for `name`, which tells the caller to draw its own version."""
    img = ASSETS.scaled(name, rect.w, rect.h)
    if img is None:
        return False
    surf.blit(img, rect.topleft)
    return True


# ------------------------------------------------------------------------------
# Effects: floating combat text and particles
# ------------------------------------------------------------------------------

def wrap_text(text, size, max_w, bold=False):
    """Greedy word wrap measured with font.size() -- pygame's render() has no
    notion of wrapping, so every break has to be measured by hand."""
    f = font(size, bold)
    lines, cur = [], ""
    for wd in text.split():
        # a single word wider than the box has to be broken by character,
        # otherwise it would hang out over the edge no matter where it goes
        while f.size(wd)[0] > max_w and len(wd) > 1:
            cut = len(wd)
            while cut > 1 and f.size(wd[:cut])[0] > max_w:
                cut -= 1
            if cur:
                lines.append(cur)
                cur = ""
            lines.append(wd[:cut])
            wd = wd[cut:]
        trial = (cur + " " + wd).strip()
        if f.size(trial)[0] <= max_w or not cur:
            cur = trial
        else:
            lines.append(cur)
            cur = wd
    if cur:
        lines.append(cur)
    return lines


# Line height is tied to the font size rather than hard-coded, so nothing
# overlaps when a paragraph is rendered at a different size.
LINE_SPACING = 1.22


def line_height(size):
    return max(size + 4, int(size * LINE_SPACING))


def layout_paragraphs(paragraphs, max_w, scale=1.0):
    """Wrap (text, size, colour, bold) paragraphs to `max_w`.

    Returns (lines, total_height) where each line is a ready-to-draw
    (text, size, colour, bold, height) tuple.  An empty paragraph becomes a
    half-height spacer.
    """
    out, total = [], 0
    for text, size, colour, bold in paragraphs:
        size = max(11, int(round(size * scale)))
        lh = line_height(size)
        if not text:
            out.append(("", size, colour, bold, lh // 2))
            total += lh // 2
            continue
        for ln in wrap_text(text, size, max_w, bold):
            out.append((ln, size, colour, bold, lh))
            total += lh
    return out, total


def fit_paragraphs(paragraphs, max_w, max_h):
    """Wrap to `max_w`, shrinking the type a step at a time until the block
    also fits inside `max_h`.  Guarantees the caller a block that fits."""
    lines, total = [], 0
    scale = 1.0
    for scale in (1.0, 0.94, 0.88, 0.82, 0.76, 0.7, 0.64, 0.58):
        lines, total = layout_paragraphs(paragraphs, max_w, scale)
        if total <= max_h:
            break
    return lines, total, scale



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

