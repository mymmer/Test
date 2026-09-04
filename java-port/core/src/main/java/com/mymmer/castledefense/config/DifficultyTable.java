package com.mymmer.castledefense.config;

import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.JsonValue;
import com.badlogic.gdx.utils.ObjectMap;
import com.mymmer.castledefense.data.DataException;
import com.mymmer.castledefense.data.Json5;
import com.mymmer.castledefense.data.JsonSource;

/**
 * Every difficulty preset, in menu order, plus the default.
 *
 * <p>Loaded once at startup from {@code data/difficulties.json}. Lookup by id
 * never returns null: an unknown id falls back to the default, which is what
 * keeps a corrupted save from bricking a run (the Python {@code Settings.load}
 * does the same thing).
 */
public final class DifficultyTable {

    public static final String PATH = "data/difficulties.json";

    private final Array<DifficultyConfig> ordered = new Array<>();
    private final ObjectMap<String, DifficultyConfig> byId = new ObjectMap<>();
    private final DifficultyConfig fallback;

    private DifficultyTable(Array<DifficultyConfig> list, String defaultId) {
        ordered.addAll(list);
        for (DifficultyConfig d : list) {
            if (byId.containsKey(d.id())) {
                throw new DataException(PATH + ": duplicate difficulty id '" + d.id() + "'");
            }
            byId.put(d.id(), d);
        }
        DifficultyConfig def = byId.get(defaultId);
        if (def == null) {
            throw new DataException(PATH + ": defaultDifficulty '" + defaultId
                    + "' is not one of the defined difficulties");
        }
        fallback = def;
    }

    public static DifficultyTable load(JsonSource source) {
        JsonValue root = source.read(PATH);
        JsonValue list = root.get("difficulties");
        if (list == null || !list.isArray() || list.size == 0) {
            throw new DataException(PATH + ": 'difficulties' must be a non-empty array");
        }
        Array<DifficultyConfig> parsed = new Array<>();
        for (JsonValue d = list.child; d != null; d = d.next) {
            String where = PATH + " [" + Json5.optString(d, "id", "?") + "]";
            String id = Json5.string(d, "id", where);
            parsed.add(new DifficultyConfig(
                    id,
                    Json5.string(d, "label", where),
                    positive(Json5.number(d, "scale", where), "scale", where),
                    positive(Json5.number(d, "gold", where), "gold", where),
                    Json5.inRange(Json5.number(d, "headstart", where), 0f, 10f,
                            "headstart", where),
                    positive(Json5.number(d, "speed", where), "speed", where),
                    positive(Json5.number(d, "hpCurve", where), "hpCurve", where),
                    positiveSeconds(Json5.seconds(d, "bossFire", where), "bossFire", where),
                    Json5.bool(d, "eliteHorn", where),
                    inRangeSeconds(Json5.seconds(d, "grabCd", where), 0.0, 5.0,
                            "grabCd", where),
                    Json5.optString(d, "blurb", "")));
        }
        String defaultId = Json5.string(root, "defaultDifficulty", PATH);
        return new DifficultyTable(parsed, defaultId);
    }

    private static float positive(float v, String name, String where) {
        if (!(v > 0f)) {
            throw new DataException(where + ": '" + name + "' must be greater than 0, got " + v);
        }
        return v;
    }

    /** The time-domain counterpart of {@link #positive}. */
    private static double positiveSeconds(double v, String name, String where) {
        if (!(v > 0d)) {
            throw new DataException(where + ": '" + name + "' must be greater than 0, got " + v);
        }
        return v;
    }

    /** The time-domain counterpart of {@code Json5.inRange}. */
    private static double inRangeSeconds(double v, double lo, double hi,
                                         String name, String where) {
        if (!(v >= lo) || !(v <= hi) || Double.isNaN(v)) {
            throw new DataException(where + ": '" + name + "' must be between " + lo
                    + " and " + hi + ", got " + v);
        }
        return v;
    }

    /** Presets in the order the menu shows them. */
    public Array<DifficultyConfig> all() {
        return ordered;
    }

    /** The preset a fresh save starts on, and the fallback for unknown ids. */
    public DifficultyConfig defaultDifficulty() {
        return fallback;
    }

    /** Never null — an unrecognised id yields the default. */
    public DifficultyConfig get(String id) {
        DifficultyConfig d = id == null ? null : byId.get(id);
        return d != null ? d : fallback;
    }

    public boolean contains(String id) {
        return id != null && byId.containsKey(id);
    }

    public int size() {
        return ordered.size;
    }
}
