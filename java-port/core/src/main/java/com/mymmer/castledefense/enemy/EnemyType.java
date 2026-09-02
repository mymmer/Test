package com.mymmer.castledefense.enemy;

/**
 * The enemy roster, by stable semantic id.
 *
 * <p>Used as the key in {@code data/enemies.json}, in the unlock table, in
 * traces and in save data. Never an ordinal and never a display name.
 *
 * <p>The three bosses are deliberately absent: Phase 7 owns them. Where wave
 * composition needs to place one, it emits a {@link SpawnSpec} carrying a boss
 * id string rather than a type from this enum — see {@link WaveComposition}.
 */
public enum EnemyType {

    SCOUT("scout"),
    FOOT_SOLDIER("foot_soldier"),
    SHIELD_BEARER("shield_bearer"),
    BERZERKER("berzerker"),
    SIEGE_RAM("siege_ram"),
    SKELETON("skeleton"),
    NECROMANCER("necromancer"),
    ASSASSIN("assassin"),
    GARGOYLE("gargoyle"),
    VOLATILE("volatile"),
    TREASURE_GOBLIN("treasure_goblin");

    private final String id;

    EnemyType(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static EnemyType byId(String id, EnemyType fallback) {
        if (id != null) {
            for (EnemyType t : values()) {
                if (t.id.equals(id)) {
                    return t;
                }
            }
        }
        return fallback;
    }
}
