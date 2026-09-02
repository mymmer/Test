package com.mymmer.castledefense.defence;

/**
 * The tower kinds, by stable semantic id.
 *
 * <p>Used as the key in {@code data/defences.json}, in traces and in the save.
 * Never an ordinal and never a display name: renaming "Bowmen" or reordering the
 * shop must not invalidate anything on disk.
 */
public enum TowerType {

    BOWMAN("bowman"),
    BALLISTA("ballista"),
    CANNON("cannon");

    private final String id;

    TowerType(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static TowerType byId(String id, TowerType fallback) {
        if (id != null) {
            for (TowerType t : values()) {
                if (t.id.equals(id)) {
                    return t;
                }
            }
        }
        return fallback;
    }
}
