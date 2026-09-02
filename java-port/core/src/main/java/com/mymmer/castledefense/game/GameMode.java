package com.mymmer.castledefense.game;

/**
 * The two ways to play, from {@code main.py}'s MODE_CLASSIC / MODE_ENDLESS.
 *
 * <p>They are genuinely different, not variations of one another: Classic runs
 * numbered waves with a shop between them, Endless runs a clock with continuous
 * spawning and a mid-fight shop. Phase 8 implements both directors; this enum
 * exists now so state and save code can name a mode without inventing strings.
 *
 * <p>{@link #id()} is the stable identifier used in saves and configuration.
 * Never persist the ordinal.
 */
public enum GameMode {

    CLASSIC("classic"),
    ENDLESS("endless");

    private final String id;

    GameMode(String id) {
        this.id = id;
    }

    /** Stable id for saves and data files. */
    public String id() {
        return id;
    }

    /** Looks up by stable id, falling back rather than throwing. */
    public static GameMode byId(String id, GameMode fallback) {
        if (id != null) {
            for (GameMode m : values()) {
                if (m.id.equals(id)) {
                    return m;
                }
            }
        }
        return fallback;
    }
}
