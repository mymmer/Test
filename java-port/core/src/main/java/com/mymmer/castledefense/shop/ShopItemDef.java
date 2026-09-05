package com.mymmer.castledefense.shop;

import com.mymmer.castledefense.defence.TowerType;

/**
 * One shop item, as defined by the data.
 *
 * <p>Identity is {@link #id}, a stable semantic string. The display name and
 * description are localisation keys derived from it; gameplay never identifies an
 * item by rendered text.
 */
public final class ShopItemDef {

    /** How the price is computed. */
    public enum Curve {
        /** {@code base * growth^n}, where {@code n} is the item's own counter. */
        GEOMETRIC,
        /** Four cases, all reading live barricade state. */
        BARRICADE,
        /** {@code max(base, int(missing * perMissingHp))}. */
        REPAIR
    }

    /** Which running number feeds a {@link Curve#GEOMETRIC} price. */
    public enum Counter {
        /** How many of this tower have been <b>bought</b> — not how many stand. */
        PURCHASES,
        /** {@code wallLevel - 1}: the first reinforcement costs the base. */
        WALL_LEVEL_MINUS_ONE,
        BOUNCE_LEVEL,
        GRAB_LEVEL,
        MULTI_LEVEL,
        OUTPOST_LEVEL,
        SPIKE_LEVEL,
        /** Not price-driving. */
        NONE
    }

    /** What buying it does. */
    public enum Effect {
        TOWER, WALL, BOUNCE, GRAB, MULTI, OUTPOST, BARRICADE, SPIKES, REPAIR
    }

    public final String id;
    public final Curve curve;
    public final Counter counter;
    public final Effect effect;

    /**
     * Cost curve constants, as <b>doubles</b>.
     *
     * <p>Not because they are times — they are not — but because a fixture
     * compares them against Python's. {@code 150f * 1.4f} is 209.9999964 and
     * truncates to 209 where the source charges 210: a real, visible coin.
     */
    public final double base;
    public final double growth;

    /** Non-null only for {@link Effect#TOWER}. */
    public final TowerType tower;
    /** Cap for the levelled items, or -1 where the source has none. */
    public final int maxLevel;

    // --- barricade's four cases ---------------------------------------------
    public final double firstCost;
    public final double rebuildBase;
    public final double rebuildGrowth;
    public final double topUpFlat;
    public final double topUpPerMissingHp;

    // --- repair --------------------------------------------------------------
    public final double perMissingHp;
    public final float healFraction;

    ShopItemDef(String id, Curve curve, Counter counter, Effect effect,
                double base, double growth, TowerType tower, int maxLevel,
                double firstCost, double rebuildBase, double rebuildGrowth,
                double topUpFlat, double topUpPerMissingHp,
                double perMissingHp, float healFraction) {
        this.id = id;
        this.curve = curve;
        this.counter = counter;
        this.effect = effect;
        this.base = base;
        this.growth = growth;
        this.tower = tower;
        this.maxLevel = maxLevel;
        this.firstCost = firstCost;
        this.rebuildBase = rebuildBase;
        this.rebuildGrowth = rebuildGrowth;
        this.topUpFlat = topUpFlat;
        this.topUpPerMissingHp = topUpPerMissingHp;
        this.perMissingHp = perMissingHp;
        this.healFraction = healFraction;
    }

    /** True when the source caps this item's level. */
    public boolean capped() {
        return maxLevel >= 0;
    }

    /** Localisation key for the display name. Never gameplay identity. */
    public String nameKey() {
        return "shop." + id + ".name";
    }

    public String descriptionKey() {
        return "shop." + id + ".desc";
    }

    @Override
    public String toString() {
        return id + "(" + effect + " " + curve + ")";
    }
}
