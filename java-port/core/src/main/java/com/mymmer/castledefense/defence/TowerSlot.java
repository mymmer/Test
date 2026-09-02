package com.mymmer.castledefense.defence;

/**
 * An emplacement on the castle, in the order slots unlock.
 *
 * <p>Coordinates are pygame world coordinates (y down), matching
 * {@code GameConfig.WALL_TOP} and {@code KEEP_TOP}. Sturdier walls unlock more
 * of them, so reinforcing buys space as well as hit points.
 */
public final class TowerSlot {

    /** Stable semantic id — {@code wall_1}, {@code keep_2}, {@code corner}. */
    public final String id;
    public final float x;
    public final float y;

    TowerSlot(String id, float x, float y) {
        this.id = id;
        this.x = x;
        this.y = y;
    }

    @Override
    public String toString() {
        return id + "@" + x + "," + y;
    }
}
