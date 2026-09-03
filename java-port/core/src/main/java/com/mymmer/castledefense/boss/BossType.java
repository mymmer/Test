package com.mymmer.castledefense.boss;

/**
 * The three bosses, by stable semantic id.
 *
 * <p>These are the ids Phase 6's {@code SpawnSpec} already carries — wave
 * composition has been placing {@code "troll_king"}, {@code "dragon"} and
 * {@code "lich_lord"} since before any of them existed. Never a rotation index:
 * {@code boss[0]} in a save would break the moment the rotation order changed.
 */
public enum BossType {

    TROLL_KING("troll_king"),
    DRAGON("dragon"),
    LICH_LORD("lich_lord");

    private final String id;

    BossType(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static BossType byId(String id, BossType fallback) {
        if (id != null) {
            for (BossType t : values()) {
                if (t.id.equals(id)) {
                    return t;
                }
            }
        }
        return fallback;
    }
}
