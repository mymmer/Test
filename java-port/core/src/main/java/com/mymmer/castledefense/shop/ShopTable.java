package com.mymmer.castledefense.shop;

import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.JsonValue;
import com.badlogic.gdx.utils.ObjectMap;
import com.mymmer.castledefense.data.DataException;
import com.mymmer.castledefense.data.Json5;
import com.mymmer.castledefense.data.JsonSource;
import com.mymmer.castledefense.defence.TowerType;

/**
 * The eleven shop items, loaded and validated once.
 *
 * <p>Order is the source's, and it is part of the contract: the Python shop is
 * keyed by number as well as by click ({@code 1}-{@code 0} buy items 1..10), so
 * reordering the list would change what a hotkey does.
 */
public final class ShopTable {

    public static final String PATH = "data/shop.json";
    /** The source has exactly this many. A missing one is a data error. */
    public static final int EXPECTED_ITEMS = 11;

    private final Array<ShopItemDef> all = new Array<>(false, 16);
    private final ObjectMap<String, ShopItemDef> byId = new ObjectMap<>();

    private ShopTable() {
    }

    public static ShopTable load(JsonSource source) {
        if (source == null) {
            throw new DataException(PATH + ": no json source");
        }
        JsonValue root = source.read(PATH);
        JsonValue items = root.get("items");
        if (items == null || !items.isArray()) {
            throw new DataException(PATH + ": 'items' must be an array");
        }

        ShopTable table = new ShopTable();
        for (JsonValue it = items.child; it != null; it = it.next) {
            String id = Json5.string(it, "id", PATH);
            String where = PATH + " item '" + id + "'";
            if (table.byId.containsKey(id)) {
                throw new DataException(PATH + ": duplicate shop id '" + id + "'");
            }

            ShopItemDef.Curve curve = parseCurve(Json5.string(it, "curve", where), where);
            ShopItemDef.Effect effect = parseEffect(Json5.string(it, "effect", where), where);

            double base = Json5.exact(it, "base", where);
            if (!(base > 0d)) {
                throw new DataException(where + ": base cost must be greater than 0, was "
                        + base);
            }

            double growth = optExact(it, "growth", 1d);
            ShopItemDef.Counter counter = ShopItemDef.Counter.NONE;
            if (curve == ShopItemDef.Curve.GEOMETRIC) {
                counter = parseCounter(Json5.string(it, "counter", where), where);
                if (!(growth >= 1d)) {
                    throw new DataException(where + ": a geometric growth below 1 would "
                            + "make the item cheaper each time, was " + growth);
                }
            }

            TowerType tower = null;
            if (effect == ShopItemDef.Effect.TOWER) {
                String towerId = Json5.string(it, "tower", where);
                tower = TowerType.byId(towerId, null);
                if (tower == null) {
                    throw new DataException(where + ": unknown tower '" + towerId + "'");
                }
            } else if (it.has("tower")) {
                throw new DataException(where + ": only a TOWER item may name a tower");
            }

            int maxLevel = Json5.optInt(it, "maxLevel", -1);
            if (it.has("maxLevel") && maxLevel < 1) {
                throw new DataException(where + ": maxLevel must be at least 1, was "
                        + maxLevel);
            }

            double firstCost = optExact(it, "firstCost", 0d);
            double rebuildBase = optExact(it, "rebuildBase", 0d);
            double rebuildGrowth = optExact(it, "rebuildGrowth", 1d);
            double topUpFlat = optExact(it, "topUpFlat", 0d);
            double topUpPerMissingHp = optExact(it, "topUpPerMissingHp", 0d);
            double perMissingHp = optExact(it, "perMissingHp", 0d);
            float healFraction = Json5.optNumber(it, "healFraction", 0f);

            if (curve == ShopItemDef.Curve.BARRICADE) {
                requirePositive(firstCost, "firstCost", where);
                requirePositive(rebuildBase, "rebuildBase", where);
                requirePositive(topUpPerMissingHp, "topUpPerMissingHp", where);
                if (!(rebuildGrowth >= 1d)) {
                    throw new DataException(where + ": rebuildGrowth must be at least 1");
                }
            }
            if (curve == ShopItemDef.Curve.REPAIR) {
                requirePositive(perMissingHp, "perMissingHp", where);
                if (!(healFraction > 0f) || healFraction > 1f) {
                    throw new DataException(where + ": healFraction must be in (0, 1], was "
                            + healFraction);
                }
            }

            ShopItemDef def = new ShopItemDef(id, curve, counter, effect, base, growth,
                    tower, maxLevel, firstCost, rebuildBase, rebuildGrowth,
                    topUpFlat, topUpPerMissingHp, perMissingHp, healFraction);
            table.all.add(def);
            table.byId.put(id, def);
        }

        if (table.all.size != EXPECTED_ITEMS) {
            throw new DataException(PATH + ": expected " + EXPECTED_ITEMS
                    + " shop items, found " + table.all.size
                    + " -- the source has eleven and every one of them must have a "
                    + "Java disposition");
        }
        return table;
    }

    /** {@link Json5#exact} with a fallback. */
    private static double optExact(JsonValue v, String name, double fallback) {
        JsonValue f = v.get(name);
        return f != null && f.isNumber() ? f.asDouble() : fallback;
    }

    private static void requirePositive(double v, String field, String where) {
        if (!(v > 0d)) {
            throw new DataException(where + ": '" + field + "' must be greater than 0, was "
                    + v);
        }
    }

    private static ShopItemDef.Curve parseCurve(String s, String where) {
        for (ShopItemDef.Curve c : ShopItemDef.Curve.values()) {
            if (c.name().equals(s)) {
                return c;
            }
        }
        throw new DataException(where + ": unknown cost curve '" + s + "'");
    }

    private static ShopItemDef.Counter parseCounter(String s, String where) {
        for (ShopItemDef.Counter c : ShopItemDef.Counter.values()) {
            if (c.name().equals(s)) {
                return c;
            }
        }
        throw new DataException(where + ": unknown cost counter '" + s + "'");
    }

    private static ShopItemDef.Effect parseEffect(String s, String where) {
        for (ShopItemDef.Effect e : ShopItemDef.Effect.values()) {
            if (e.name().equals(s)) {
                return e;
            }
        }
        throw new DataException(where + ": unknown purchase effect '" + s + "'");
    }

    /** Every item, in the source's order. Hotkeys depend on it. */
    public Array<ShopItemDef> all() {
        return all;
    }

    public int size() {
        return all.size;
    }

    public ShopItemDef get(String id) {
        return byId.get(id);
    }

    public ShopItemDef require(String id) {
        ShopItemDef d = byId.get(id);
        if (d == null) {
            throw new DataException("no such shop item: '" + id + "'");
        }
        return d;
    }

    public boolean contains(String id) {
        return byId.containsKey(id);
    }
}
