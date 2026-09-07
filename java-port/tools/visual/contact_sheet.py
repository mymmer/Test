"""
Build a side-by-side Python vs Java contact sheet.

Phase 11.5. Pairs ``py-<scene>.png`` from the Python reference capture with
``<scene>.png`` from the Java scenario capture, stacks each pair with a caption,
and writes one PNG per scene plus a single overview sheet.

    python java-port/tools/visual/contact_sheet.py PY_DIR JAVA_DIR OUT_DIR

Nothing here compares pixels. Fonts, anti-aliasing and primitive rasterisation
differ between Pygame and libGDX, and a check that failed on those would be
noise. The output is for a person to look at.
"""

import os
import sys

os.environ.setdefault("SDL_VIDEODRIVER", "dummy")
os.environ.setdefault("SDL_AUDIODRIVER", "dummy")

import pygame                                          # noqa: E402

SCENES = [
    "mixed-wave", "all-enemies", "all-bosses", "all-towers", "armour",
    "regalia", "dragon-breath", "lich-ward", "fire-zone", "tornado",
    "storm", "endless-late",
]

PAD = 10
LABEL_H = 26
THUMB_W = 480          # each half of a pair, in the overview sheet


def label(surface, text, x, y, font, colour=(235, 238, 245)):
    surface.blit(font.render(text, True, colour), (x, y))


def pair(py_path, java_path, font):
    """One scene: Python above, Java below, each captioned."""
    py = pygame.image.load(py_path) if os.path.exists(py_path) else None
    jv = pygame.image.load(java_path) if os.path.exists(java_path) else None
    w = max(py.get_width() if py else 0, jv.get_width() if jv else 0, 640)
    h = ((py.get_height() if py else 0) + (jv.get_height() if jv else 0)
         + LABEL_H * 2 + PAD * 3)
    out = pygame.Surface((w + PAD * 2, h))
    out.fill((16, 18, 26))
    y = PAD
    for img, name in ((py, "PYTHON  (pygame reference)"),
                      (jv, "JAVA  (libGDX port)")):
        label(out, name, PAD, y, font)
        y += LABEL_H
        if img is not None:
            out.blit(img, (PAD, y))
            y += img.get_height() + PAD
        else:
            label(out, "   -- missing --", PAD, y, font, (230, 120, 110))
            y += LABEL_H + PAD
    return out


def main():
    if len(sys.argv) < 4:
        print(__doc__)
        return 2
    py_dir, java_dir, out_dir = sys.argv[1], sys.argv[2], sys.argv[3]
    os.makedirs(out_dir, exist_ok=True)

    pygame.init()
    pygame.display.set_mode((64, 64))
    font = pygame.font.Font(None, 24)

    made = []
    for scene in SCENES:
        img = pair(os.path.join(py_dir, "py-%s.png" % scene),
                   os.path.join(java_dir, "%s.png" % scene), font)
        path = os.path.join(out_dir, "compare-%s.png" % scene)
        pygame.image.save(img, path)
        made.append((scene, path))
        print("%-16s %s" % (scene, path))

    #  One overview: every pair reduced to thumbnails, four across.
    cols = 4
    rows = (len(made) + cols - 1) // cols
    cell_w = THUMB_W + PAD
    cell_h = int(THUMB_W * 0.5625) * 2 + LABEL_H + PAD * 2
    sheet = pygame.Surface((cols * cell_w + PAD, rows * cell_h + PAD))
    sheet.fill((16, 18, 26))
    for i, (scene, path) in enumerate(made):
        img = pygame.image.load(path)
        scale = THUMB_W / img.get_width()
        thumb = pygame.transform.smoothscale(
            img, (THUMB_W, int(img.get_height() * scale)))
        x = PAD + (i % cols) * cell_w
        y = PAD + (i // cols) * cell_h
        label(sheet, scene, x, y, font, (250, 210, 120))
        sheet.blit(thumb, (x, y + LABEL_H))
    overview = os.path.join(out_dir, "contact-sheet.png")
    pygame.image.save(sheet, overview)
    print("\noverview: %s" % overview)
    return 0


if __name__ == "__main__":
    sys.exit(main())
