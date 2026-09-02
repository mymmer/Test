# Subsystem contract — Assets and skins

## Purpose

Load artwork, and let artwork be swapped without touching gameplay.

The hard rule the whole design serves: **visuals never define gameplay
geometry.** A skin may change every sprite, its scale, its offsets and its
animation frames, and not one hitbox, range or spawn position moves.

## Owns

* Texture loading and lifetime (`GameAssets` over libGDX `AssetManager`).
* Atlas region lookup by **logical name** (`AtlasSource`).
* Skin descriptor parsing (`SkinDefinition`), the active skin
  (`SkinManager`), and per-unit visuals (`UnitVisual`, `AnimationSet`,
  `AttachmentPoint`).
* Skin validation and its errors/warnings split (`SkinValidator`,
  `SkinValidationReport`).
* The procedural fallback: what happens when a region is missing.

## Does not own

* **Drawing.** Nothing here touches a `SpriteBatch`. It answers "which region,
  at what scale and offset"; the renderer draws it.
* **Hitboxes, ranges, speeds.** Those come from `GameConfig` and the data files,
  never from a texture's width.
* **Asset packing.** `gradlew packAssets` runs TexturePacker at build time; the
  runtime only reads the produced `.atlas`.
* **File paths on a device.** `Gdx.files` is reached through `JsonSource` /
  `AtlasSource` so tests can supply in-memory content.

## Dependencies

`JsonSource` (descriptors), libGDX `AssetManager`/`TextureAtlas`/`TextureRegion`.
No gameplay, no simulation, no input.

## Public API

```java
// GameAssets implements AtlasSource, Disposable
AssetManager manager();
void  load(String atlasPath);   boolean isLoaded(String atlasPath);
ObjectSet<String> regionNames(String atlasPath);
TextureRegion region(String atlasPath, String name);
void  unload(String atlasPath);
float progress();  boolean update();  void dispose();

// SkinManager
static final String PROCEDURAL_SKIN = "procedural";
boolean load(String skinId);            // false → fell back, never throws
UnitVisual visualFor(VisualId id);      // never null
boolean hasArtwork(VisualId id);
String activeSkinId();  String activeAtlasPath();
SkinValidationReport lastReport();

// SkinDefinition
static SkinDefinition parse(String skinId, JsonSource source);
static String directoryOf(String skinId);  static String descriptorPath(String skinId);
String id(); int version(); String atlasFile(); String atlasPath();
boolean isProcedural();
ObjectMap<String, UnitEntry> units();
Array<String> duplicateKeys();  Array<String> unknownKeys();

// UnitVisual
static UnitVisual procedural(VisualId id);
VisualId id(); String region(); float scale(); float offsetX(); float offsetY();
AnimationSet animations();  boolean isProcedural();
AttachmentPoint attachment(String name);  boolean hasAttachment(String name);
String regionAt(AnimationState state, float seconds);

// AnimationSet / AnimationSet.Clip
AnimationSet put(Clip clip);  boolean has(AnimationState s);  int size();
Clip resolve(AnimationState s);            // falls back rather than returning null
String regionAt(AnimationState s, float seconds);
Clip(AnimationState state, String base, int frames, float fps, boolean looping);
String regionFor(int frameIndex);  boolean isStatic();

// SkinValidator
static SkinValidationReport validate(SkinDefinition def, ...);
```

## Important invariants

1. **Gameplay geometry is independent of artwork.** Scale and offsets are
   presentation only. A sprite twice the size does not become twice as easy to
   hit. Attachment points are stored **normalised against the gameplay box**,
   not against the texture, so they survive an art change.
2. **Identity is a stable semantic id.** `VisualId` and `AnimationState` are
   enums; regions are addressed by logical name. Never an array index, never a
   frame position, never a localised string.
3. **A region is named after its source file.** `scout_walk_0.png` →
   `scout_walk_0`. This only holds because `packAssets` writes `pack.json` with
   `useIndexes:false`; with indexing on, TexturePacker collapses `scout_walk_0`
   and `scout_walk_1` into a single indexed region called `scout_walk` and every
   frame lookup silently breaks. That setting is load-bearing.
4. **A missing or broken skin is never fatal.** `SkinManager.load` returns
   `false` and leaves the procedural fallback active. `UnitVisual` and
   `AnimationSet` never return null; `resolve` degrades to a static clip and
   then to the fallback region.
5. **Errors and warnings are different things.** A *warning* (an unknown key, a
   missing optional attachment) loads. An *error* (a missing required region, a
   bad frame count, a duplicate key) rejects the skin. Both are reported through
   `SkinValidationReport` rather than logged and forgotten.
6. **Textures are owned by `GameAssets`.** It is the only thing that disposes
   them. `SkinManager` holds regions, not textures, and disposes nothing.
7. **PNG at runtime, SVG for authoring.** The runtime never parses SVG.

## Relevant source files

```
core/src/main/java/com/mymmer/castledefense/assets/GameAssets.java
core/src/main/java/com/mymmer/castledefense/assets/AtlasSource.java
core/src/main/java/com/mymmer/castledefense/assets/SkinManager.java
core/src/main/java/com/mymmer/castledefense/assets/SkinDefinition.java
core/src/main/java/com/mymmer/castledefense/assets/SkinValidator.java
core/src/main/java/com/mymmer/castledefense/assets/SkinValidationReport.java
core/src/main/java/com/mymmer/castledefense/assets/UnitVisual.java
core/src/main/java/com/mymmer/castledefense/assets/AnimationSet.java
core/src/main/java/com/mymmer/castledefense/assets/AnimationState.java
core/src/main/java/com/mymmer/castledefense/assets/AttachmentPoint.java
core/src/main/java/com/mymmer/castledefense/assets/VisualId.java
build.gradle                                   (the packAssets task and pack.json)
```

## Relevant tests

```
core/src/test/java/com/mymmer/castledefense/assets/SkinSystemTest.java
core/src/test/java/com/mymmer/castledefense/testsupport/FakeAtlasSource.java
```
