package com.mymmer.castledefense.assets;

import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.JsonValue;
import com.badlogic.gdx.utils.ObjectMap;
import com.mymmer.castledefense.data.DataException;
import com.mymmer.castledefense.data.Json5;
import com.mymmer.castledefense.data.JsonSource;

/**
 * A parsed {@code skin.json}: what artwork a skin claims to provide.
 *
 * <p>Parsing is separate from loading on purpose. This class does pure data work
 * — no textures, no GL, no AssetManager — so a skin can be parsed and validated
 * in a unit test, in a build-time check, or against a modder's folder without
 * starting a game.
 *
 * <p>Shape:
 * <pre>
 * {
 *   "id": "medieval", "version": 1,
 *   "atlas": "units.atlas",
 *   "units": {
 *     "troll_king": {
 *       "region": "troll_king", "scale": 1.2, "offsetX": 0, "offsetY": 0,
 *       "attachments": { "crown": { "x": 0.50, "y": 0.91 } },
 *       "animations": { "walk": { "frames": 4, "fps": 8, "loop": true } }
 *     }
 *   }
 * }
 * </pre>
 *
 * <p>A skin with {@code "procedural": true} and no atlas is legal and is what
 * ships by default: the game runs with no artwork at all, exactly like the
 * Python original.
 */
public final class SkinDefinition {

    /** One unit entry, still in data form. */
    public static final class UnitEntry {
        public final String key;
        public final VisualId id;               // null when the key is unknown
        public final String region;
        public final float scale;
        public final float offsetX;
        public final float offsetY;
        public final ObjectMap<String, AttachmentPoint> attachments = new ObjectMap<>();
        public final Array<AnimationSet.Clip> clips = new Array<>();

        UnitEntry(String key, VisualId id, String region, float scale,
                  float offsetX, float offsetY) {
            this.key = key;
            this.id = id;
            this.region = region;
            this.scale = scale;
            this.offsetX = offsetX;
            this.offsetY = offsetY;
        }
    }

    private final String id;
    private final int version;
    private final String atlasFile;
    private final boolean procedural;
    private final ObjectMap<String, UnitEntry> units = new ObjectMap<>();
    private final Array<String> duplicateKeys = new Array<>();

    private SkinDefinition(String id, int version, String atlasFile, boolean procedural) {
        this.id = id;
        this.version = version;
        this.atlasFile = atlasFile;
        this.procedural = procedural;
    }

    /** Directory holding a skin, e.g. {@code skins/medieval}. */
    public static String directoryOf(String skinId) {
        return "skins/" + skinId;
    }

    /** Path of a skin's descriptor. */
    public static String descriptorPath(String skinId) {
        return directoryOf(skinId) + "/skin.json";
    }

    public static SkinDefinition parse(String skinId, JsonSource source) {
        String path = descriptorPath(skinId);
        JsonValue root = source.read(path);
        String declaredId = Json5.string(root, "id", path);
        if (!declaredId.equals(skinId)) {
            throw new DataException(path + ": skin declares id '" + declaredId
                    + "' but lives in the folder '" + skinId + "'");
        }
        boolean procedural = Json5.optBool(root, "procedural", false);
        String atlas = Json5.optString(root, "atlas", procedural ? "" : "units.atlas");
        SkinDefinition def = new SkinDefinition(declaredId,
                Json5.optInt(root, "version", 1), atlas, procedural);

        JsonValue units = root.get("units");
        if (units != null) {
            for (JsonValue u = units.child; u != null; u = u.next) {
                def.parseUnit(path, u);
            }
        }
        return def;
    }

    private void parseUnit(String path, JsonValue u) {
        String key = u.name;
        String where = path + " [" + key + "]";
        if (units.containsKey(key)) {
            duplicateKeys.add(key);
            return;
        }
        UnitEntry entry = new UnitEntry(
                key,
                VisualId.byKey(key),
                Json5.optString(u, "region", key),
                Json5.optNumber(u, "scale", 1f),
                Json5.optNumber(u, "offsetX", 0f),
                Json5.optNumber(u, "offsetY", 0f));

        JsonValue att = u.get("attachments");
        if (att != null) {
            for (JsonValue a = att.child; a != null; a = a.next) {
                entry.attachments.put(a.name, new AttachmentPoint(
                        a.name,
                        Json5.number(a, "x", where + " attachment '" + a.name + "'"),
                        Json5.number(a, "y", where + " attachment '" + a.name + "'")));
            }
        }

        JsonValue anims = u.get("animations");
        if (anims != null) {
            for (JsonValue a = anims.child; a != null; a = a.next) {
                AnimationState state = AnimationState.byKey(a.name);
                if (state == null) {
                    // recorded by the validator, not fatal here
                    continue;
                }
                entry.clips.add(new AnimationSet.Clip(
                        state,
                        Json5.optString(a, "base", entry.region + "_" + a.name),
                        Json5.optInt(a, "frames", 1),
                        Json5.optNumber(a, "fps", 8f),
                        Json5.optBool(a, "loop", true)));
            }
        }
        units.put(key, entry);
    }

    public String id() {
        return id;
    }

    public int version() {
        return version;
    }

    /** Atlas filename inside the skin folder, empty for a procedural skin. */
    public String atlasFile() {
        return atlasFile;
    }

    /** Full path of the atlas, or empty for a procedural skin. */
    public String atlasPath() {
        return procedural || atlasFile.isEmpty() ? "" : directoryOf(id) + "/" + atlasFile;
    }

    /** True when this skin intentionally supplies no artwork. */
    public boolean isProcedural() {
        return procedural;
    }

    public ObjectMap<String, UnitEntry> units() {
        return units;
    }

    /** Unit keys that appeared more than once — reported by the validator. */
    public Array<String> duplicateKeys() {
        return duplicateKeys;
    }

    /** Raw json keys that are not one of our {@link VisualId}s. */
    public Array<String> unknownKeys() {
        Array<String> unknown = new Array<>();
        for (ObjectMap.Entry<String, UnitEntry> e : units.entries()) {
            if (e.value.id == null) {
                unknown.add(e.key);
            }
        }
        return unknown;
    }
}
