# raw-assets — source artwork

Individual PNGs live here, one file per logical unit. The build packs them into
a `TextureAtlas`; nothing in the game references a file in this folder.

```
create/edit a PNG   →   put it in raw-assets/skins/<skin>/   →
    ./gradlew packAssets   →   ./gradlew :lwjgl3:run
```

## Layout

```
raw-assets/skins/medieval/
    scout.png            ->  region "scout"
    scout_walk_0.png     ->  region "scout_walk_0"   (frame 0 of the walk clip)
    scout_walk_1.png     ->  region "scout_walk_1"
    troll_king.png       ->  region "troll_king"
```

The region name is the filename without its extension. `AnimationSet` builds
frame names as `<base>_<n>`, so naming frames `scout_walk_0.png`,
`scout_walk_1.png` … is all that an animated unit needs.

`packAssets` writes:

```
assets/skins/medieval/units.atlas
assets/skins/medieval/units.png
```

Both are build outputs. Do not hand-edit them, and do not commit them — commit
the PNGs here instead.

## Adding a skin

1. `mkdir raw-assets/skins/<name>` and drop PNGs in.
2. Write `assets/skins/<name>/skin.json` (copy `assets/skins/procedural/skin.json`
   as a starting point) describing scale, offsets, attachment points and
   animations.
3. `./gradlew packAssets`.
4. Select it — the skin id is stored in the save file.

Missing artwork is never fatal: any unit the skin does not cover is drawn
procedurally, and the validator reports it as a warning at load time.

## Master artwork

Vector sources (SVG, .ai, .kra) belong in `art-source/`, outside the runtime
tree, and export to PNG here. Nothing at runtime rasterises vectors.
