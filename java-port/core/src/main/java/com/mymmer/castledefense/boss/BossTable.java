package com.mymmer.castledefense.boss;

import com.badlogic.gdx.utils.JsonValue;
import com.badlogic.gdx.utils.ObjectMap;
import com.badlogic.gdx.utils.ObjectSet;
import com.mymmer.castledefense.config.GameConfig;
import com.mymmer.castledefense.config.GameplayAnchor;
import com.mymmer.castledefense.data.DataException;
import com.mymmer.castledefense.data.Json5;
import com.mymmer.castledefense.data.JsonSource;
import com.mymmer.castledefense.enemy.EnemyConfig;

/**
 * Every boss stat block and gameplay anchor, loaded once from
 * {@code data/bosses.json} and validated on the spot.
 *
 * <p>Separate from {@code EnemyTable} because a boss is not in the enemy roster:
 * it never appears in the unlock table, it is never picked by wave weight, and
 * it carries fields no ordinary unit has. Sharing the file would mean every
 * enemy row carrying empty breath and ward columns.
 *
 * <p>Bosses reuse {@link EnemyConfig} for the parts they share, built here from
 * the boss row so {@code Enemy}'s constructor works unchanged.
 */
public final class BossTable {

    public static final String PATH = "data/bosses.json";

    private final ObjectMap<BossType, BossConfig> configs = new ObjectMap<>();
    private final ObjectMap<BossType, EnemyConfig> enemyConfigs = new ObjectMap<>();

    private BossTable() {
    }

    public static BossTable load(JsonSource source) {
        JsonValue root = source.read(PATH);
        BossTable table = new BossTable();
        table.read(root);
        return table;
    }

    private void read(JsonValue root) {
        JsonValue list = root.get("bosses");
        if (list == null || !list.isArray() || list.size == 0) {
            throw new DataException(PATH + ": 'bosses' must be a non-empty array");
        }
        ObjectSet<String> seen = new ObjectSet<>();
        for (JsonValue b = list.child; b != null; b = b.next) {
            String rawId = Json5.optString(b, "id", null);
            String where = PATH + " [boss " + (rawId != null ? rawId : "?") + "]";
            String id = Json5.string(b, "id", where);
            if (!seen.add(id)) {
                throw new DataException(where + ": duplicate boss id '" + id + "'");
            }
            BossType type = BossType.byId(id, null);
            if (type == null) {
                throw new DataException(where + ": unknown boss id '" + id
                        + "'; expected one of " + ids());
            }

            float width = Json5.inRange(Json5.number(b, "width", where),
                    1f, 600f, "width", where);
            float height = Json5.inRange(Json5.number(b, "height", where),
                    1f, 600f, "height", where);

            GameplayAnchor anchor = readAnchor(b, where);
            float halfW = Json5.optNumber(b, "regaliaHalfWidth", 0f);
            float halfH = Json5.optNumber(b, "regaliaHalfHeight", 0f);
            if (anchor != null && (halfW <= 0f || halfH <= 0f)) {
                throw new DataException(where + ": a boss with a regalia anchor needs"
                        + " a positive regaliaHalfWidth and regaliaHalfHeight, or the"
                        + " player could never click it");
            }

            BossConfig cfg = new BossConfig(
                    type,
                    Json5.string(b, "name", where),
                    Json5.optString(b, "description", ""),
                    Json5.optString(b, "hint", ""),
                    positive(Json5.number(b, "baseHp", where), "baseHp", where),
                    positive(Json5.number(b, "baseSpeed", where), "baseSpeed", where),
                    positive(Json5.number(b, "baseDamage", where), "baseDamage", where),
                    positive(Json5.number(b, "attackRate", where), "attackRate", where),
                    (int) nonNegative(Json5.number(b, "gold", where), "gold", where),
                    Json5.inRange(Json5.optNumber(b, "armor", 0f), 0f, 1f, "armor", where),
                    positive(Json5.number(b, "mass", where), "mass", where),
                    width, height,
                    Json5.optBool(b, "flying", false),
                    Json5.optNumber(b, "flyY", 250f),
                    nonNegative(Json5.optNumber(b, "introTime", 1.6f), "introTime", where),
                    anchor, halfW, halfH,
                    Json5.optNumber(b, "regaliaBoxOffsetY", 0f),
                    Json5.optNumber(b, "leapDistance", 0f),
                    Json5.optNumber(b, "leapMinRange", 0f),
                    Json5.optNumber(b, "leapIntervalMin", 0f),
                    Json5.optNumber(b, "leapIntervalMax", 0f),
                    Json5.optNumber(b, "breathTime", 0f),
                    Json5.optNumber(b, "breathShotInterval", 0f),
                    Json5.optNumber(b, "breathPower", 0f),
                    Json5.optNumber(b, "breathIntervalMin", 0f),
                    Json5.optNumber(b, "breathIntervalMax", 0f),
                    Json5.optNumber(b, "standoffX", GameConfig.CASTLE_FRONT + 400f),
                    Json5.inRange(Json5.optNumber(b, "wardDamageMultiplier", 1f),
                            0f, 1f, "wardDamageMultiplier", where),
                    Json5.optNumber(b, "wardDuration", 0f),
                    Json5.optNumber(b, "wardIntervalMin", 0f),
                    Json5.optNumber(b, "wardIntervalMax", 0f),
                    Json5.optNumber(b, "summonIntervalBase", 0f),
                    Json5.optNumber(b, "summonIntervalFloor", 0f),
                    Json5.optNumber(b, "summonIntervalPerWave", 0f),
                    Json5.optInt(b, "summonBaseCount", 0),
                    Json5.optInt(b, "summonMaxBonus", 0));

            validateRanges(cfg, where);
            configs.put(type, cfg);
            enemyConfigs.put(type, toEnemyConfig(cfg));
        }
        for (BossType t : BossType.values()) {
            if (!configs.containsKey(t)) {
                throw new DataException(PATH + ": no stat block for boss '" + t.id() + "'");
            }
        }
    }

    private static GameplayAnchor readAnchor(JsonValue b, String where) {
        JsonValue a = b.get("regaliaAnchor");
        if (a == null) {
            return null;
        }
        float x = Json5.number(a, "x", where + " regaliaAnchor");
        float y = Json5.number(a, "y", where + " regaliaAnchor");
        //  Normalised to the gameplay box, with room for genuine overhang -- a
        //  Lich Lord's staff head really does sit above and right of his box.
        if (x < -1f || x > 2f || y < -1f || y > 2f) {
            throw new DataException(where + ": regaliaAnchor (" + x + ", " + y
                    + ") is far outside the gameplay box; anchors are normalised to it");
        }
        return new GameplayAnchor(Json5.optString(a, "name", "regalia"), x, y);
    }

    /** Cross-field checks: a value that is individually fine but useless together. */
    private static void validateRanges(BossConfig c, String where) {
        if (c.type == BossType.DRAGON) {
            if (!(c.breathTime > 0f) || !(c.breathShotInterval > 0f)) {
                throw new DataException(where + ": the Dragon needs a positive breathTime"
                        + " and breathShotInterval, or it never breathes");
            }
            if (c.breathShotInterval > c.breathTime) {
                throw new DataException(where + ": breathShotInterval "
                        + c.breathShotInterval + " exceeds breathTime " + c.breathTime
                        + ", so a breath would produce exactly one fireball");
            }
            if (!(c.breathPower > 0f)) {
                throw new DataException(where + ": breathPower must be > 0");
            }
        }
        if (c.type == BossType.LICH_LORD) {
            if (!(c.wardDuration > 0f)) {
                throw new DataException(where + ": the Lich Lord needs a positive"
                        + " wardDuration");
            }
            if (c.summonBaseCount <= 0) {
                throw new DataException(where + ": summonBaseCount must be > 0");
            }
            if (c.summonIntervalFloor <= 0f || c.summonIntervalBase <= 0f) {
                throw new DataException(where + ": summon intervals must be > 0");
            }
        }
        if (c.type == BossType.TROLL_KING && !(c.leapDistance > 0f)) {
            throw new DataException(where + ": the Troll King needs a positive"
                    + " leapDistance");
        }
        if (c.wardIntervalMin > c.wardIntervalMax) {
            throw new DataException(where + ": wardIntervalMin exceeds wardIntervalMax");
        }
        if (c.breathIntervalMin > c.breathIntervalMax) {
            throw new DataException(where + ": breathIntervalMin exceeds breathIntervalMax");
        }
        if (c.leapIntervalMin > c.leapIntervalMax) {
            throw new DataException(where + ": leapIntervalMin exceeds leapIntervalMax");
        }
    }

    /**
     * The {@link EnemyConfig} view of a boss.
     *
     * <p>{@link EnemyConfig#forBoss} hard-codes grabbable, trappable, heavy and
     * strippable to false: a boss that could be picked up would break its own
     * disruption mechanic, and those are not numbers a designer should be able
     * to flip by accident.
     */
    private static EnemyConfig toEnemyConfig(BossConfig c) {
        return EnemyConfig.forBoss(c.type.id(), c.name, c.description,
                c.baseHp, c.baseSpeed, c.baseDamage, c.attackRate,
                c.gold, c.armor, c.mass, c.width, c.height, c.flyY, c.flying);
    }

    public BossConfig config(BossType type) {
        BossConfig c = configs.get(type);
        if (c == null) {
            throw new DataException(PATH + ": no stat block for '" + type.id() + "'");
        }
        return c;
    }

    /** The enemy-side view, for {@code Enemy}'s constructor. */
    public EnemyConfig enemyConfig(BossType type) {
        return enemyConfigs.get(type);
    }

    /** The one place a boss type maps to a class. */
    public Boss create(BossContext ctx, BossType type, int wave, Float x, Float y) {
        BossConfig c = config(type);
        EnemyConfig e = enemyConfig(type);
        switch (type) {
            case TROLL_KING:
                return new TrollKing(ctx, e, c, wave, x, y);
            case DRAGON:
                return new Dragon(ctx, e, c, wave, x, y);
            case LICH_LORD:
                return new LichLord(ctx, e, c, wave, x, y);
            default:
                throw new DataException("no implementation for boss '" + type.id() + "'");
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

    private static String ids() {
        StringBuilder sb = new StringBuilder();
        for (BossType t : BossType.values()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(t.id());
        }
        return sb.toString();
    }
}
