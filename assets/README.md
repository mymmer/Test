# Assets

The game ships with **no image files** — every sprite is drawn from pygame
primitives, so it runs the moment you clone it.

Drop a PNG in here and it is picked up automatically on the next run. No code
change is needed: `sprites.ASSETS` looks for the file, and if it isn't there
the unit falls back to its hand-drawn version.

| File | Used by |
|---|---|
| `castle.png` | the keep and curtain wall |
| `barricade.png` | the outer barricade |
| `outpost.png` | the background outpost tower |
| `bowman.png`, `ballista.png`, `cannon.png` | wall emplacements |
| `scout.png`, `foot_soldier.png`, `shield_bearer.png`, `berzerker.png` | basic mobs |
| `siege_ram.png`, `necromancer.png`, `assassin.png`, `gargoyle.png` | later mobs |
| `volatile.png`, `treasure_goblin.png`, `skeleton.png` | specials |
| `troll_king.png`, `dragon.png`, `lich_lord.png` | bosses |

`.png`, `.jpg`, `.jpeg`, `.bmp` and `.gif` are all accepted. Images are scaled
to the unit's hitbox, so match the aspect ratios in `ASSET_SPECS`
(`sprites.py`) to avoid squashing. Transparency is preserved.

A file that fails to load is ignored rather than crashing the game.
