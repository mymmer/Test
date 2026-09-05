package com.mymmer.castledefense.skill;

/**
 * The three active skills, in <b>unlock order</b>.
 *
 * <p>{@code main.py:772 SKILL_UNLOCK_ORDER}. One slot lights up per boss
 * defeated, and the order is fixed — the first boss always yields Lightning.
 * Declaration order here <em>is</em> that order and
 * {@code SkillPanelTest.unlockOrder} pins it.
 *
 * <p>{@code needsTarget} is the source's flag: a targeted skill waits for the
 * player to place it, an untargeted one fires where the cursor already is.
 * Meteor Shower is the untargeted one, because it rains across the field rather
 * than landing on a point.
 */
public enum SkillId {

    LIGHTNING("lightning", true),
    METEOR("meteor", false),
    TORNADO("tornado", true);

    private final String id;
    private final boolean needsTarget;

    SkillId(String id, boolean needsTarget) {
        this.id = id;
        this.needsTarget = needsTarget;
    }

    /** Stable id, for saves, traces and localisation keys. */
    public String id() {
        return id;
    }

    public boolean needsTarget() {
        return needsTarget;
    }

    /** Localisation key for the display name. Never gameplay identity. */
    public String nameKey() {
        return "skill." + id + ".name";
    }

    public String descriptionKey() {
        return "skill." + id + ".desc";
    }

    public static SkillId byId(String id, SkillId fallback) {
        if (id != null) {
            for (SkillId s : values()) {
                if (s.id.equals(id)) {
                    return s;
                }
            }
        }
        return fallback;
    }
}
