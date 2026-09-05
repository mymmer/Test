package com.mymmer.castledefense.talent;

/**
 * The six branches — {@code main.py:304 TALENT_BRANCHES}.
 *
 * <p>Order is the menu order and is part of the contract; the display names are
 * localisation keys, never gameplay identity.
 */
public enum TalentBranch {

    OFFENSE("offense"),
    DEFENSE("defense"),
    UTILITY("utility"),
    AERO("aero"),
    NECROMANCY("necromancy"),
    ARCANE("arcane");

    private final String id;

    TalentBranch(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    /** Localisation key for the branch heading. */
    public String nameKey() {
        return "talent.branch." + id;
    }

    public static TalentBranch byId(String id) {
        for (TalentBranch b : values()) {
            if (b.id.equals(id)) {
                return b;
            }
        }
        return null;
    }
}
