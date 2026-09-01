package com.mymmer.castledefense.config;

/**
 * World constants that the foundation needs.
 *
 * <p>This class is the Java home of {@code sprites.py}'s configuration block, but
 * Phase 2 only fills in what the viewports and the window need. The rest of the
 * table (physics, scoring, structure positions, tier data) arrives in Phase 3
 * together with the JSON data loader — see {@code PORTING_STATUS.md}.
 *
 * <p>The virtual world is exactly the Python game's surface: 1280x720 logical
 * units. Every gameplay coordinate in the port is expressed in these units and
 * is identical to the Python value, whatever the physical screen is.
 */
public final class GameConfig {

    /** Python: {@code sprites.py} WIDTH. */
    public static final float WORLD_WIDTH = 1280f;

    /** Python: {@code sprites.py} HEIGHT. */
    public static final float WORLD_HEIGHT = 720f;

    /** Python: {@code sprites.py} TITLE. */
    public static final String TITLE = "Castle Defense";

    /**
     * Simulation rate. The Python game steps a variable delta capped at 50 ms;
     * the port steps a fixed 1/60 s (see {@code docs/PORT_ANALYSIS.md} §6). The
     * constant lives here from Phase 2 so the launchers and the future
     * {@code Simulation} agree on one number.
     */
    public static final float SIMULATION_STEP = 1f / 60f;

    /** Most catch-up steps one rendered frame may run before time is dropped. */
    public static final int MAX_SIMULATION_STEPS_PER_FRAME = 5;

    /** Longest frame delta fed to the accumulator, in seconds. */
    public static final float MAX_FRAME_DELTA = 0.25f;

    private GameConfig() {
    }
}
