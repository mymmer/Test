package com.mymmer.castledefense.defence;

import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.JsonValue;
import com.badlogic.gdx.utils.ObjectMap;
import com.badlogic.gdx.utils.ObjectSet;
import com.mymmer.castledefense.data.DataException;
import com.mymmer.castledefense.data.Json5;
import com.mymmer.castledefense.data.JsonSource;

/**
 * Every defence stat block, loaded once at startup from {@code data/defences.json}.
 *
 * <p>Follows the rule from {@code docs/PORT_ANALYSIS.md} §9: <b>numbers are data,
 * behaviour is Java</b>. A designer may retune a Ballista's damage without a
 * recompile; nobody may express "prefers flyers, then the beefiest" in JSON.
 *
 * <h2>Validation is loud and immediate</h2>
 *
 * <p>Every field is checked as it is read, and a bad one throws a
 * {@link DataException} naming the file, the entry and the field. Duplicate ids,
 * a negative cooldown, a non-positive range, an unknown projectile kind, a
 * castle tier whose health goes backwards, a malformed slot, a missing tower
 * type — all of them stop the game at startup. The alternative is a defence that
 * quietly does nothing until wave 20, which is far more expensive to find.
 */
public final class DefenceTable {

    public static final String PATH = "data/defences.json";

    private final ObjectMap<TowerType, TowerConfig> towers = new ObjectMap<>();
    private final Array<CastleTier> tiers = new Array<>();
    private final Array<TowerSlot> slots = new Array<>();

    private DefenceTable() {
    }

    public static DefenceTable load(JsonSource source) {
        JsonValue root = source.read(PATH);
        DefenceTable table = new DefenceTable();
        table.readTowers(root);
        table.readCastle(root);
        return table;
    }

    // --- towers -------------------------------------------------------------

    private void readTowers(JsonValue root) {
        JsonValue list = root.get("towers");
        if (list == null || !list.isArray() || list.size == 0) {
            throw new DataException(PATH + ": 'towers' must be a non-empty array");
        }
        ObjectSet<String> seen = new ObjectSet<>();
        for (JsonValue t = list.child; t != null; t = t.next) {
            String rawId = Json5.optString(t, "id", null);
            String where = PATH + " [tower " + (rawId != null ? rawId : "?") + "]";
            String id = Json5.string(t, "id", where);
            if (!seen.add(id)) {
                throw new DataException(where + ": duplicate tower id '" + id + "'");
            }
            TowerType type = TowerType.byId(id, null);
            if (type == null) {
                throw new DataException(where + ": unknown tower id '" + id
                        + "'; expected one of " + idsOf());
            }
            String kindId = Json5.string(t, "projectile", where);
            ProjectileKind kind = ProjectileKind.byId(kindId, null);
            if (kind == null) {
                throw new DataException(where + ": unknown projectile type '" + kindId + "'");
            }
            float cooldown = Json5.number(t, "cooldown", where);
            if (cooldown <= 0f) {
                throw new DataException(where + ": cooldown must be > 0, was " + cooldown);
            }
            float range = Json5.number(t, "range", where);
            if (range <= 0f) {
                throw new DataException(where + ": range must be > 0, was " + range);
            }
            float damage = Json5.number(t, "damage", where);
            if (damage <= 0f) {
                throw new DataException(where + ": damage must be > 0, was " + damage);
            }
            float splash = Json5.optNumber(t, "splash", 0f);
            if (splash < 0f) {
                throw new DataException(where + ": splash must not be negative, was " + splash);
            }
            float maxHp = Json5.number(t, "maxHp", where);
            if (maxHp <= 0f) {
                throw new DataException(where + ": maxHp must be > 0, was " + maxHp);
            }
            float speed = Json5.number(t, "projectileSpeed", where);
            if (speed <= 0f) {
                throw new DataException(where + ": projectileSpeed must be > 0, was " + speed);
            }
            boolean overchargeable = Json5.optBool(t, "overchargeable", false);
            float overchargeSpeed = Json5.optNumber(t, "overchargeSpeed", speed);
            if (overchargeable && overchargeSpeed <= 0f) {
                throw new DataException(where
                        + ": an overchargeable tower needs overchargeSpeed > 0");
            }
            towers.put(type, new TowerConfig(
                    type,
                    Json5.string(t, "name", where),
                    range,
                    cooldown,
                    damage,
                    splash,
                    Json5.bool(t, "hitsAir", where),
                    maxHp,
                    Json5.inRange(Json5.number(t, "width", where), 1f, 400f, "width", where),
                    Json5.inRange(Json5.number(t, "height", where), 1f, 400f, "height", where),
                    atLeastOne(Json5.optNumber(t, "bonusVsAir", 1f), "bonusVsAir", where),
                    atLeastOne(Json5.optNumber(t, "bonusVsHeavy", 1f), "bonusVsHeavy", where),
                    atLeastOne(Json5.optNumber(t, "airRangeMult", 1f), "airRangeMult", where),
                    overchargeable,
                    Json5.inRange(Json5.number(t, "regen", where), 0f, 1f, "regen", where),
                    positive(Json5.number(t, "rebuildTime", where), "rebuildTime", where),
                    speed,
                    kind,
                    overchargeSpeed));
        }
        for (TowerType type : TowerType.values()) {
            if (!towers.containsKey(type)) {
                throw new DataException(PATH + ": no stat block for tower '"
                        + type.id() + "'");
            }
        }
    }

    // --- castle -------------------------------------------------------------

    private void readCastle(JsonValue root) {
        JsonValue castle = Json5.child(root, "castle", PATH);

        JsonValue tierList = castle.get("tiers");
        if (tierList == null || !tierList.isArray() || tierList.size == 0) {
            throw new DataException(PATH + ": 'castle.tiers' must be a non-empty array");
        }
        ObjectSet<String> seenTiers = new ObjectSet<>();
        float previousHp = 0f;
        for (JsonValue t = tierList.child; t != null; t = t.next) {
            String where = PATH + " [tier " + Json5.optString(t, "id", "?") + "]";
            String id = Json5.string(t, "id", where);
            if (!seenTiers.add(id)) {
                throw new DataException(where + ": duplicate castle tier id '" + id + "'");
            }
            float hp = Json5.number(t, "maxHp", where);
            if (hp <= 0f) {
                throw new DataException(where + ": maxHp must be > 0, was " + hp);
            }
            //  Tiers must strengthen monotonically: upgradeWall() computes its
            //  gain as the difference between consecutive maxima, so a tier that
            //  went backwards would silently REDUCE the castle's health on a
            //  purchase.
            if (hp <= previousHp) {
                throw new DataException(where + ": castle tiers must increase in maxHp; "
                        + hp + " is not greater than the previous " + previousHp);
            }
            previousHp = hp;
            tiers.add(new CastleTier(id, Json5.string(t, "name", where), hp));
        }

        JsonValue slotList = castle.get("slots");
        if (slotList == null || !slotList.isArray() || slotList.size == 0) {
            throw new DataException(PATH + ": 'castle.slots' must be a non-empty array");
        }
        ObjectSet<String> seenSlots = new ObjectSet<>();
        for (JsonValue s = slotList.child; s != null; s = s.next) {
            String where = PATH + " [slot " + Json5.optString(s, "id", "?") + "]";
            String id = Json5.string(s, "id", where);
            if (!seenSlots.add(id)) {
                throw new DataException(where + ": duplicate slot id '" + id + "'");
            }
            float sx = Json5.number(s, "x", where);
            float sy = Json5.number(s, "y", where);
            if (sx < 0f || sx > com.mymmer.castledefense.config.GameConfig.WORLD_WIDTH
                    || sy < 0f || sy > com.mymmer.castledefense.config.GameConfig.WORLD_HEIGHT) {
                throw new DataException(where + ": slot (" + sx + ", " + sy
                        + ") is outside the world");
            }
            TowerSlot slot = new TowerSlot(id, sx, sy);
            //  Two slots at the same point would let a tower be stationed on an
            //  "occupied" slot for ever, because occupancy is tested by position.
            for (int i = 0; i < slots.size; i++) {
                if (slots.get(i).x == sx && slots.get(i).y == sy) {
                    throw new DataException(where + ": slot shares its position with '"
                            + slots.get(i).id + "'");
                }
            }
            slots.add(slot);
        }
    }

    // --- lookup -------------------------------------------------------------

    public TowerConfig tower(TowerType type) {
        TowerConfig c = towers.get(type);
        if (c == null) {
            throw new DataException(PATH + ": no stat block for '" + type.id() + "'");
        }
        return c;
    }

    public int tierCount() {
        return tiers.size;
    }

    public CastleTier tier(int index) {
        return tiers.get(index);
    }

    public int slotCount() {
        return slots.size;
    }

    public TowerSlot slot(int index) {
        return slots.get(index);
    }

    /**
     * Builds a tower of the given type at a position.
     *
     * <p>The one place that maps a {@link TowerType} to a class, so nothing else
     * needs a switch over tower types.
     */
    public DefenceTower createTower(DefenceContext ctx, TowerType type, float x, float y) {
        TowerConfig c = tower(type);
        switch (type) {
            case BOWMAN:
                return new Bowman(ctx, c, x, y);
            case BALLISTA:
                return new Ballista(ctx, c, x, y);
            case CANNON:
                return new Cannon(ctx, c, x, y);
            default:
                throw new DataException("no implementation for tower '" + type.id() + "'");
        }
    }

    private static float positive(float value, String name, String where) {
        if (!(value > 0f)) {
            throw new DataException(where + ": " + name + " must be > 0, was " + value);
        }
        return value;
    }

    private static float atLeastOne(float value, String name, String where) {
        if (!(value >= 1f)) {
            throw new DataException(where + ": " + name
                    + " must be at least 1.0 (1.0 means no bonus), was " + value);
        }
        return value;
    }

    private static String idsOf() {
        StringBuilder sb = new StringBuilder();
        for (TowerType t : TowerType.values()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(t.id());
        }
        return sb.toString();
    }
}
