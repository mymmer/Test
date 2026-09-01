package com.mymmer.castledefense.assets;

/**
 * The logical things a skin can supply artwork for.
 *
 * <p>Gameplay code asks for {@code VisualId.SCOUT}; it never names a PNG, an
 * atlas region or a file path. Swapping skins therefore cannot require touching
 * a single gameplay class — which is the whole point of the skin system.
 *
 * <p>The ids mirror the Python {@code ASSET_SPECS} table so an artist who
 * produced files for the Python game can drop them straight into a skin folder.
 */
public enum VisualId {

    // structures
    CASTLE("castle"),
    BARRICADE("barricade"),
    OUTPOST("outpost"),

    // towers
    BOWMAN("bowman"),
    BALLISTA("ballista"),
    CANNON("cannon"),

    // mobs
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
    TREASURE_GOBLIN("treasure_goblin"),
    FRIENDLY_SKELETON("friendly_skeleton"),

    // bosses
    TROLL_KING("troll_king"),
    DRAGON("dragon"),
    LICH_LORD("lich_lord"),

    // ui glyphs
    SKILL_LIGHTNING("skill_lightning"),
    SKILL_METEOR("skill_meteor"),
    SKILL_TORNADO("skill_tornado"),
    TALENT_NODE("talent_node");

    private final String key;

    VisualId(String key) {
        this.key = key;
    }

    /** The id as it appears in {@code skin.json} and in atlas region names. */
    public String key() {
        return key;
    }

    /** Looks an id up by its json key, or null when it is not one of ours. */
    public static VisualId byKey(String key) {
        if (key == null) {
            return null;
        }
        for (VisualId id : values()) {
            if (id.key.equals(key)) {
                return id;
            }
        }
        return null;
    }
}
