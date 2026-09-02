package com.mymmer.castledefense.enemy;

import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.JsonValue;
import com.badlogic.gdx.utils.ObjectMap;
import com.badlogic.gdx.utils.ObjectSet;
import com.mymmer.castledefense.data.DataException;
import com.mymmer.castledefense.data.Json5;
import com.mymmer.castledefense.data.JsonSource;

/**
 * Every enemy stat block, the endgame tiers and the unlock table, loaded once at
 * startup from {@code data/enemies.json} and validated on the spot.
 *
 * <p>Numbers are data, behaviour is Java. A designer may retune a Berzerker's
 * damage without a recompile; nobody may express "recomputes its speed from the
 * base, discarding difficulty" in JSON.
 *
 * <p>Validation is loud and immediate: a duplicate id, a negative stat, a
 * strippable unit with no plates, an unknown unlock type, endgame tiers that are
 * out of order — all of them stop the game at startup, naming the file and the
 * field. The alternative is a mob that quietly behaves wrongly from wave 26.
 */
public final class EnemyTable {

    public static final String PATH = "data/enemies.json";

    private final ObjectMap<EnemyType, EnemyConfig> configs = new ObjectMap<>();
    private final Array<EndgameTier> tiers = new Array<>();
    private final Array<UnlockEntry> unlocks = new Array<>();

    /** One row of the unlock table: from this wave, this type, at this budget cost. */
    public static final class UnlockEntry {
        public final int firstWave;
        public final EnemyType type;
        /** Budget weight: dearer units are rarer and eat more of the wave budget. */
        public final float weight;

        UnlockEntry(int firstWave, EnemyType type, float weight) {
            this.firstWave = firstWave;
            this.type = type;
            this.weight = weight;
        }

        @Override
        public String toString() {
            return "w" + firstWave + ":" + type.id() + "@" + weight;
        }
    }

    private EnemyTable() {
    }

    public static EnemyTable load(JsonSource source) {
        JsonValue root = source.read(PATH);
        EnemyTable table = new EnemyTable();
        table.readEnemies(root);
        table.readTiers(root);
        table.readUnlocks(root);
        return table;
    }

    // --- enemies ------------------------------------------------------------

    private void readEnemies(JsonValue root) {
        JsonValue list = root.get("enemies");
        if (list == null || !list.isArray() || list.size == 0) {
            throw new DataException(PATH + ": 'enemies' must be a non-empty array");
        }
        ObjectSet<String> seen = new ObjectSet<>();
        for (JsonValue e = list.child; e != null; e = e.next) {
            String rawId = Json5.optString(e, "id", null);
            String where = PATH + " [enemy " + (rawId != null ? rawId : "?") + "]";
            String id = Json5.string(e, "id", where);
            if (!seen.add(id)) {
                throw new DataException(where + ": duplicate enemy id '" + id + "'");
            }
            EnemyType type = EnemyType.byId(id, null);
            if (type == null) {
                throw new DataException(where + ": unknown enemy id '" + id
                        + "'; expected one of " + typeIds());
            }

            float armor = Json5.inRange(Json5.optNumber(e, "armor", 0f), 0f, 1f,
                    "armor", where);
            int layers = Json5.optInt(e, "armorLayers", 0);
            if (layers < 0) {
                throw new DataException(where + ": armorLayers must not be negative");
            }
            boolean heavy = Json5.optBool(e, "heavy", false);
            boolean strippable = Json5.optBool(e, "strippable", false);
            //  A strippable unit with no plates could never be stripped, and one
            //  with plates that is not heavy could never be locked by them: both
            //  are silent no-ops rather than errors at runtime.
            if (strippable && layers <= 0) {
                throw new DataException(where + ": strippable needs armorLayers > 0");
            }
            if (layers > 0 && !heavy) {
                throw new DataException(where + ": armorLayers only apply to a heavy unit"
                        + " (armored() checks HEAVY), so this plating would do nothing");
            }

            configs.put(type, new EnemyConfig(
                    type,
                    Json5.string(e, "name", where),
                    Json5.optString(e, "description", ""),
                    positive(Json5.number(e, "baseHp", where), "baseHp", where),
                    positive(Json5.number(e, "baseSpeed", where), "baseSpeed", where),
                    nonNegative(Json5.number(e, "baseDamage", where), "baseDamage", where),
                    positive(Json5.number(e, "attackRate", where), "attackRate", where),
                    nonNegativeInt(Json5.optInt(e, "gold", 0), "gold", where),
                    armor,
                    positive(Json5.number(e, "mass", where), "mass", where),
                    Json5.inRange(Json5.number(e, "width", where), 1f, 400f, "width", where),
                    Json5.inRange(Json5.number(e, "height", where), 1f, 400f, "height", where),
                    Json5.optNumber(e, "flyY", 250f),
                    Json5.optBool(e, "flying", false),
                    Json5.optBool(e, "grabbable", true),
                    Json5.optBool(e, "trappable", false),
                    heavy,
                    strippable,
                    layers));
        }
        for (EnemyType type : EnemyType.values()) {
            if (!configs.containsKey(type)) {
                throw new DataException(PATH + ": no stat block for enemy '"
                        + type.id() + "'");
            }
        }
    }

    // --- endgame tiers ------------------------------------------------------

    private void readTiers(JsonValue root) {
        JsonValue list = root.get("endgameTiers");
        if (list == null || !list.isArray()) {
            throw new DataException(PATH + ": 'endgameTiers' must be an array");
        }
        ObjectSet<String> seen = new ObjectSet<>();
        int previousWave = 0;
        for (JsonValue t = list.child; t != null; t = t.next) {
            String where = PATH + " [tier " + Json5.optString(t, "id", "?") + "]";
            String id = Json5.string(t, "id", where);
            if (!seen.add(id)) {
                throw new DataException(where + ": duplicate endgame tier id '" + id + "'");
            }
            int firstWave = (int) Json5.number(t, "firstWave", where);
            //  tierIndex() keeps the LAST matching row, so ascending order is
            //  not cosmetic: out of order, a wave-36 mob would come out Bloodied
            if (firstWave <= previousWave) {
                throw new DataException(where + ": endgame tiers must be in ascending"
                        + " wave order; firstWave " + firstWave
                        + " does not follow " + previousWave);
            }
            previousWave = firstWave;
            tiers.add(new EndgameTier(
                    id,
                    Json5.string(t, "name", where),
                    firstWave,
                    parseTint(Json5.string(t, "tint", where), where),
                    Json5.inRange(Json5.number(t, "tintStrength", where), 0f, 1f,
                            "tintStrength", where),
                    atLeastOne(Json5.number(t, "hpMult", where), "hpMult", where),
                    atLeastOne(Json5.number(t, "damageMult", where), "damageMult", where),
                    atLeastOne(Json5.number(t, "speedMult", where), "speedMult", where)));
        }
    }

    private static int parseTint(String hex, String where) {
        String v = hex.startsWith("#") ? hex.substring(1) : hex;
        if (v.length() != 6) {
            throw new DataException(where + ": tint must be #RRGGBB, was '" + hex + "'");
        }
        try {
            return Integer.parseInt(v, 16);
        } catch (NumberFormatException e) {
            throw new DataException(where + ": tint is not hex: '" + hex + "'");
        }
    }

    // --- unlocks ------------------------------------------------------------

    private void readUnlocks(JsonValue root) {
        JsonValue list = root.get("unlocks");
        if (list == null || !list.isArray() || list.size == 0) {
            throw new DataException(PATH + ": 'unlocks' must be a non-empty array");
        }
        ObjectSet<String> seen = new ObjectSet<>();
        for (JsonValue u = list.child; u != null; u = u.next) {
            String where = PATH + " [unlock " + Json5.optString(u, "id", "?") + "]";
            String id = Json5.string(u, "id", where);
            EnemyType type = EnemyType.byId(id, null);
            if (type == null) {
                throw new DataException(where + ": unknown enemy id '" + id + "' in unlocks");
            }
            if (!seen.add(id)) {
                throw new DataException(where + ": '" + id + "' appears twice in unlocks");
            }
            if (!configs.containsKey(type)) {
                throw new DataException(where + ": '" + id + "' has no stat block");
            }
            int firstWave = (int) Json5.number(u, "firstWave", where);
            if (firstWave < 1) {
                throw new DataException(where + ": firstWave must be at least 1");
            }
            float weight = Json5.number(u, "weight", where);
            if (weight <= 0f) {
                //  the pick weight is 1/weight, so zero would divide by zero and
                //  a negative would make the unit infinitely likely
                throw new DataException(where + ": weight must be > 0, was " + weight);
            }
            unlocks.add(new UnlockEntry(firstWave, type, weight));
        }
    }

    // --- lookup -------------------------------------------------------------

    public EnemyConfig config(EnemyType type) {
        EnemyConfig c = configs.get(type);
        if (c == null) {
            throw new DataException(PATH + ": no stat block for '" + type.id() + "'");
        }
        return c;
    }

    /** The endgame tiers, in ascending wave order. */
    public EndgameTier[] tiers() {
        return tiers.toArray(EndgameTier.class);
    }

    public int tierCount() {
        return tiers.size;
    }

    public EndgameTier tier(int index) {
        return tiers.get(index);
    }

    /** The unlock table, in declaration order. */
    public Array<UnlockEntry> unlocks() {
        return unlocks;
    }

    /**
     * Builds an enemy of the given type.
     *
     * <p>The one place a type maps to a class, so nothing else needs a switch.
     */
    public Enemy create(EnemyContext ctx, EnemyType type, int wave, Float x, Float y) {
        EnemyConfig c = config(type);
        switch (type) {
            case SCOUT:
                return new Scout(ctx, c, wave, x, y);
            case FOOT_SOLDIER:
                return new FootSoldier(ctx, c, wave, x, y);
            case SHIELD_BEARER:
                return new ShieldBearer(ctx, c, wave, x, y);
            case BERZERKER:
                return new Berzerker(ctx, c, wave, x, y);
            case SIEGE_RAM:
                return new SiegeRam(ctx, c, wave, x, y);
            case SKELETON:
                return new Skeleton(ctx, c, wave, x, y);
            case NECROMANCER:
                return new Necromancer(ctx, c, wave, x, y);
            case ASSASSIN:
                return new Assassin(ctx, c, wave, x, y);
            case GARGOYLE:
                return new Gargoyle(ctx, c, wave, x, y);
            case VOLATILE:
                return new Volatile(ctx, c, wave, x, y);
            case TREASURE_GOBLIN:
                return new TreasureGoblin(ctx, c, wave, x, y);
            default:
                throw new DataException("no implementation for enemy '" + type.id() + "'");
        }
    }

    private static float positive(float v, String name, String where) {
        if (!(v > 0f)) {
            throw new DataException(where + ": " + name + " must be > 0, was " + v);
        }
        return v;
    }

    private static float nonNegative(float v, String name, String where) {
        if (!(v >= 0f)) {
            throw new DataException(where + ": " + name + " must not be negative, was " + v);
        }
        return v;
    }

    private static int nonNegativeInt(int v, String name, String where) {
        if (v < 0) {
            throw new DataException(where + ": " + name + " must not be negative, was " + v);
        }
        return v;
    }

    private static float atLeastOne(float v, String name, String where) {
        if (!(v >= 1f)) {
            throw new DataException(where + ": " + name
                    + " must be at least 1.0 (1.0 means no change), was " + v);
        }
        return v;
    }

    private static String typeIds() {
        StringBuilder sb = new StringBuilder();
        for (EnemyType t : EnemyType.values()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(t.id());
        }
        return sb.toString();
    }
}
