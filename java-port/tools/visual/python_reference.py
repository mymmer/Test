"""
Reference screenshots from the authoritative Python game.

Phase 11.5. Renders a handful of controlled scenes with the *real* Python game
and saves them as PNGs, so the Java painters can be compared against images
rather than against a reading of the source.

WHY THIS WORKS WITHOUT TOUCHING THE SOURCE
------------------------------------------
``Game.__init__`` already takes a ``screen`` and always draws the world into its
own offscreen ``self.scene`` surface before blitting it.  So a plain
``pygame.Surface`` is a perfectly good screen, SDL's dummy video driver means no
window is ever opened, and ``pygame.image.save`` writes what ``Game.draw``
produced.  Nothing here monkey-patches, subclasses or edits anything:

    main.py, sprites.py, castle.py, enemies.py are imported and read only.

Every scene is built the way the game builds one -- ``reset``, ``begin_play``,
``spawn_enemy`` -- and mirrors a scenario in ``VisualScenarios.java`` so the two
sets line up.

    python java-port/tools/visual/python_reference.py [output-dir]

Requires pygame (the game's own runtime dependency).
"""

import os
import sys

# The dummy driver must be chosen before pygame initialises its video backend.
os.environ.setdefault("SDL_VIDEODRIVER", "dummy")
os.environ.setdefault("SDL_AUDIODRIVER", "dummy")

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", "..", ".."))
sys.path.insert(0, ROOT)

import pygame                                          # noqa: E402

pygame.init()
pygame.display.set_mode((1280, 720))

import main as cd                                      # noqa: E402
import enemies as en                                   # noqa: E402


def new_game():
    """A game drawing onto an offscreen surface. No window, no display flip."""
    surface = pygame.Surface((cd.WIDTH, cd.HEIGHT))
    return cd.Game(screen=surface)


def playing(mode=cd.MODE_ENDLESS):
    g = new_game()
    g.reset(mode)
    g.state = g.SHOP
    g.begin_play()
    return g


def place(g, cls, wave, x):
    """Spawn one unit through the game's own path, then position it."""
    e = cls(g, wave)
    g.spawn_enemy(e)
    e.x = float(x)
    return e


# ---------------------------------------------------------------------------
# Scenes -- each mirrors a VisualScenarios entry of the same name
# ---------------------------------------------------------------------------

def scene_mixed_wave(g):
    roster = [en.Scout, en.FootSoldier, en.ShieldBearer, en.Berzerker,
              en.Gargoyle, en.Skeleton, en.Assassin]
    for i in range(22):
        place(g, roster[i % len(roster)], 8, 340 + (i * 43) % 940)


def scene_all_enemies(g):
    types = [en.Scout, en.FootSoldier, en.ShieldBearer, en.Berzerker,
             en.SiegeRam, en.Skeleton, en.Necromancer, en.Assassin,
             en.Gargoyle, en.Volatile, en.TreasureGoblin]
    step = 980.0 / len(types)
    for i, cls in enumerate(types):
        place(g, cls, 6, 320 + i * step)


def scene_all_bosses(g):
    for i, cls in enumerate((en.TrollKing, en.Dragon, en.LichLord)):
        place(g, cls, 12, 460 + i * 280)


def scene_all_towers(g):
    g.gold = 100000
    for item in g.shop_items:
        if item.key in ("bowman", "ballista", "cannon"):
            g.try_buy(item)
            g.try_buy(item)
    for item in g.shop_items:
        if item.key == "wall":
            for _ in range(3):
                g.try_buy(item)


def scene_armour(g):
    for i in range(3):
        ram = place(g, en.SiegeRam, 10, 420 + i * 300)
        for _ in range(i):
            #  apply_strip is what the cursor's drag calls, so the plate comes
            #  off exactly as it does in play.
            ram.apply_strip(cd.STRIP_DISTANCE + 1)


def scene_regalia(g):
    place(g, en.TrollKing, 8, 520)
    place(g, en.LichLord, 15, 900)
    for e in list(g.enemies):
        if isinstance(e, en.TrollKing):
            e.has_crown = False
        elif isinstance(e, en.LichLord):
            e.has_staff = False


def scene_dragon_breath(g):
    place(g, en.Dragon, 10, 760)
    for i in range(6):
        place(g, en.FootSoldier, 10, 400 + i * 60)
    for _ in range(900):
        if any(getattr(e, "breathing", 0) > 0 for e in g.enemies):
            break
        g.update(1 / 60.0)


def scene_lich_ward(g):
    place(g, en.LichLord, 15, 820)
    for i in range(8):
        place(g, en.Skeleton, 15, 700 + i * 40)


def cast(g, key, x, y):
    """Cast one skill through SkillPanel.cast, the game's own entry point."""
    while len(g.skills.skills) < len(cd.SKILL_UNLOCK_ORDER):
        g.skills.unlock_next()
    for skill in g.skills.skills:
        if skill.key == key:
            skill.cooldown = 0.0
            g.skills.cast(skill, x, y)
            return True
    return False


def scene_fire_zone(g):
    cast(g, "meteor", 700, 200)
    scene_mixed_wave(g)


def scene_tornado(g):
    cast(g, "tornado", 620, 160)
    scene_mixed_wave(g)
    for _ in range(90):
        g.update(1 / 60.0)


def scene_storm(g):
    for _ in range(400):
        g.roll_weather()
        if g.storm:
            break
    scene_mixed_wave(g)


def scene_endless_late(g):
    scene_all_towers(g)
    types = [en.Scout, en.FootSoldier, en.ShieldBearer, en.Berzerker,
             en.SiegeRam, en.Skeleton, en.Necromancer, en.Assassin,
             en.Gargoyle, en.Volatile, en.TreasureGoblin]
    for i in range(26):
        place(g, types[i % len(types)], 40, 300 + (i * 39) % 980)
    place(g, en.TrollKing, 40, 560)
    place(g, en.Dragon, 40, 940)


SCENES = [
    ("mixed-wave", scene_mixed_wave),
    ("all-enemies", scene_all_enemies),
    ("all-bosses", scene_all_bosses),
    ("all-towers", scene_all_towers),
    ("armour", scene_armour),
    ("regalia", scene_regalia),
    ("dragon-breath", scene_dragon_breath),
    ("lich-ward", scene_lich_ward),
    ("fire-zone", scene_fire_zone),
    ("tornado", scene_tornado),
    ("storm", scene_storm),
    ("endless-late", scene_endless_late),
]


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, "reference")
    os.makedirs(out, exist_ok=True)
    ok, failed = 0, []
    for name, build in SCENES:
        try:
            g = playing()
            #  The shake is a fresh random draw inside draw(); zeroing it keeps
            #  the reference images stable between runs.  It is a visual offset
            #  only -- see the Phase 11.5 audit.
            g.shake = 0.0
            build(g)
            g.shake = 0.0
            g.draw()
            path = os.path.join(out, "py-%s.png" % name)
            pygame.image.save(g.screen, path)
            print("%-16s ok   %s" % (name, path))
            ok += 1
        except Exception as exc:                        # noqa: BLE001
            print("%-16s FAILED  %s: %s" % (name, type(exc).__name__, exc))
            failed.append(name)
    print("\n%d captured, %d failed" % (ok, len(failed)))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
