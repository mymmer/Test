package com.mymmer.castledefense.config;

/**
 * World constants that the foundation needs.
 *
 * <p>This class is the Java home of {@code sprites.py}'s configuration block:
 * world geometry, physics, cursor strength, structures, weather, scoring and
 * limits. Values are transcribed verbatim from the Python so the two can be
 * compared side by side during parity testing.
 *
 * <p>What lives here vs. in JSON: numbers that describe <em>the world itself</em>
 * and are referenced from many places stay as constants (a wrong one is a
 * compile error, not a runtime surprise). Numbers that describe <em>content</em>
 * — enemy stats, tower stats, difficulty presets, talents — are data files, so
 * balance can change without recompiling. See {@code docs/PORT_ANALYSIS.md} §9.
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
     * Simulation rate, canonical. The Python game steps a variable delta capped
     * at 50 ms; the port steps a fixed 1/60 s (see {@code docs/PORT_ANALYSIS.md}
     * §6).
     *
     * <p><b>double, deliberately.</b> {@code 1f/60f} is not a sixtieth: its float
     * value is 0.016666668…, slightly larger than 0.016666666…. Anything that
     * <em>accumulates</em> a timestep — the accumulator, the simulation clock,
     * an Endless or boss timer — must use this double, or 216,000 steps of a
     * one-hour run drift measurably away from 3600 s.
     */
    public static final double SIMULATION_STEP = 1.0 / 60.0;

    /**
     * The same step as a float, for gameplay arithmetic.
     *
     * <p>Positions, velocities and per-step decay are floats throughout the port
     * (as they are in libGDX and in the Python source), so the value handed to a
     * gameplay update is this. It is a <em>presentation</em> of the canonical
     * step, never the thing that gets summed: gameplay multiplies by it, the
     * clock counts steps.
     */
    public static final float SIMULATION_STEP_F = (float) SIMULATION_STEP;

    /** Most catch-up steps one rendered frame may run before time is dropped. */
    public static final int MAX_SIMULATION_STEPS_PER_FRAME = 5;

    /** Longest frame delta fed to the accumulator, in seconds. */
    public static final float MAX_FRAME_DELTA = 0.25f;

    // ========================================================================
    //  World geometry — sprites.py
    // ========================================================================
    //  NOTE ON THE Y AXIS.  pygame measures y downward from the top; libGDX
    //  measures it upward from the bottom.  Every constant below is kept at its
    //  Python value so the two codebases can be compared line by line, and the
    //  single conversion lives in worldY(): call it once at the rendering or
    //  spawning boundary rather than sprinkling (720 - y) through the port.

    /** Python GROUND_Y: y of the ground line, measured pygame-style. */
    public static final float GROUND_Y = 620f;

    /** Python CASTLE_FRONT: x of the castle's front face; enemies stop here. */
    public static final float CASTLE_FRONT = 252f;

    /** Python WALL_TOP: y of the top of the curtain wall. */
    public static final float WALL_TOP = 350f;

    /** Python KEEP_TOP: y of the top of the keep. */
    public static final float KEEP_TOP = 230f;

    /** Python SPAWN_X: where enemies walk in from. */
    public static final float SPAWN_X = WORLD_WIDTH + 90f;

    /** Converts a pygame-style y (down from the top) to libGDX world y. */
    public static float worldY(float pygameY) {
        return WORLD_HEIGHT - pygameY;
    }

    // ========================================================================
    //  Physics — sprites.py
    // ========================================================================
    public static final float GRAVITY = 1650f;
    public static final float AIR_DRAG = 0.16f;
    public static final float THROW_POWER = 1.15f;
    public static final float FALL_DMG_FLOOR = 250f;
    public static final float FALL_DMG_SCALE = 0.26f;
    public static final float SLAM_DMG_FLOOR = 170f;
    public static final float SLAM_DMG_SCALE = 0.10f;

    /** Normal-mode delay between grabs; the difficulty table overrides it. */
    public static final double GRAB_COOLDOWN = 0.25;

    /** Cursor velocity sampling: look back this far, and clamp to this speed. */
    public static final double THROW_SAMPLE_WINDOW = 0.09;
    public static final float THROW_SPEED_CLAMP = 2600f;

    // --- boss regalia -------------------------------------------------------
    public static final float REGALIA_MAX_THROW_CROWN = 2300f;
    public static final float REGALIA_MAX_THROW_STAFF = 780f;
    public static final float CROWN_RETRIEVE_SPEED = 1.7f;
    public static final double STAFF_DISARM_TIME = 5.0;
    public static final double REGALIA_COOLDOWN = 6.0;
    public static final double REGALIA_CD_GROWTH = 0.6;

    // --- bounce upgrade -----------------------------------------------------
    public static final int BOUNCE_MAX_LEVEL = 5;
    public static final float[] BOUNCE_RESTITUTION =
            {0.32f, 0.46f, 0.56f, 0.64f, 0.71f, 0.78f};
    public static final float BOUNCE_DMG_BONUS = 0.22f;
    public static final double BOUNCE_STAGGER = 0.34;

    // --- weather ------------------------------------------------------------
    public static final float WIND_MAX = 260f;
    public static final float WIND_PROJECTILE = 0.45f;
    public static final float STORM_CHANCE = 0.3f;
    public static final float STORM_CEILING = 132f;
    public static final float STORM_DAMAGE = 0.34f;
    public static final double STORM_COOLDOWN = 1.1;

    // --- dragon claws -------------------------------------------------------
    public static final float CLAW_SMACK_DISTANCE = 360f;
    public static final double CLAW_STAGGER = 3.2;

    // --- cursor strength ----------------------------------------------------
    public static final int GRAB_MAX_LEVEL = 4;
    public static final float[] GRAB_CAPACITY = {3.5f, 5.5f, 7.5f, 9.5f, 13f};
    public static final int MULTI_MAX_LEVEL = 3;
    public static final float MULTI_RADIUS = 110f;

    // --- shoving / stripping ------------------------------------------------
    public static final float SHOVE_FACTOR = 3.4f;
    public static final float SHOVE_DECAY = 1.6f;
    public static final float SHOVE_MAX = 340f;
    public static final float STRIP_DISTANCE = 420f;
    public static final float STRIP_SLOW = 0.76f;
    public static final float STRIP_VULN = 0.22f;

    // --- overcharge ---------------------------------------------------------
    public static final float OVERCHARGE_PULL = 190f;
    public static final float OVERCHARGE_DAMAGE = 2.5f;
    public static final float OVERCHARGE_SPLASH = 1.7f;
    public static final float OVERCHARGE_SPEED = 1.45f;
    public static final double OVERCHARGE_COOLDOWN = 5.0;

    // --- world structures ---------------------------------------------------
    public static final float OUTPOST_X = 1015f;
    public static final float OUTPOST_BASE_Y = 505f;
    public static final int OUTPOST_MAX_LEVEL = 6;
    public static final int OUTPOST_TURRET_FROM = 4;
    public static final float OUTPOST_RANGE = 560f;
    public static final float BARRICADE_X = 585f;
    public static final int BARRICADE_MAX_LEVEL = 5;
    public static final float[] BARRICADE_HP = {0f, 340f, 620f, 980f, 1450f, 2050f};
    public static final int SPIKE_MAX_LEVEL = 4;
    public static final float SPIKE_DAMAGE = 15f;

    // --- the Necromancer betrayal -------------------------------------------
    public static final double TRAP_SKELETON_RATE = 7.2;
    public static final int TRAP_SKELETON_CAP = 6;
    public static final float PRISONER_HP = 260f;
    public static final float PRISONER_REGEN = 3f;
    public static final double RIVAL_BOLT_RATE = 2.6;
    public static final float RIVAL_BOLT_DAMAGE = 34f;
    public static final float ALLY_ENGAGE_RANGE = 40f;
    public static final float ALLY_HOLD_X = WORLD_WIDTH - 90f;

    // --- risk / reward ------------------------------------------------------
    public static final int POP_GOLD_FREE = 4;
    public static final float POP_GOLD_STEP = 0.055f;
    public static final float POP_GOLD_CAP = 3f;
    public static final float SCORE_PER_PX = 0.32f;
    public static final float SCORE_PER_SEC = 55f;
    public static final float SCORE_COMBO_STEP = 0.75f;

    // --- economy / limits ---------------------------------------------------
    public static final int STARTING_GOLD = 220;
    public static final int MAX_PARTICLES = 900;
    public static final int MAX_ALIVE = 58;

    private GameConfig() {
    }
}
