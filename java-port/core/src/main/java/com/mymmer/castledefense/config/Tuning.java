package com.mymmer.castledefense.config;

/**
 * Pacing constants — the Java home of {@code main.py}'s tuning blocks.
 *
 * <p>Kept apart from {@link GameConfig} because these are the numbers a designer
 * reaches for when the game feels too fast or too slow, and the Python source
 * deliberately gathers them in one editable block ("Tweak freely; nothing
 * outside this block needs to change to re-time the bosses or the spawn rate").
 * That property is worth preserving.
 *
 * <p><b>Time-domain values here are {@code double}.</b> Every one of them is a
 * duration, an interval or a deadline that a gameplay clock is compared
 * against, and the Endless timetable in particular is compared against a clock
 * that runs for an hour. See {@code SIMULATION.md}, "Gameplay time is double".
 * Radii, speeds and damage stay {@code float} — they are spatial.
 */
public final class Tuning {

    // --- Endless: spawn and tier timetable ----------------------------------
    /** Seconds between tier steps. */
    public static final double ENDLESS_TIER_SECONDS = 30.0;
    /** Gap between spawns at the very start of a run. */
    public static final double ENDLESS_SPAWN_START = 1.70;
    /** Hard floor on that gap, however long the run lasts. */
    public static final double ENDLESS_SPAWN_MIN = 0.38;
    /** Seconds taken to ramp from START down to MIN. */
    public static final double ENDLESS_SPAWN_RAMP = 300.0;
    /** Randomness applied to each gap, as a fraction either way. */
    public static final double ENDLESS_SPAWN_JITTER = 0.28;
    /** Spawning pauses above this many live mobs. */
    public static final int ENDLESS_MAX_ALIVE = 60;

    // --- Endless: boss timetable --------------------------------------------
    /** Scripted arrivals, in seconds since the run began. */
    public static final double[] ENDLESS_BOSS_TIMES = {120.0, 240.0, 360.0};
    /** After the scripted three, one random boss every this many seconds. */
    public static final double ENDLESS_BOSS_REPEAT = 120.0;

    // --- Challenge Horn -----------------------------------------------------
    /** Mobs the horn calls in at once in Endless. */
    public static final int ENDLESS_HORN_RUSH = 12;
    /** Units in a Hard-mode elite horn pack. */
    public static final int HARD_HORN_RUSH = 10;
    /** Hard horn packs are rolled as if the run were this many tiers deeper. */
    public static final int HARD_HORN_TIER_BONUS = 3;
    /** Extra gold and score for the rest of the wave. */
    public static final float HORN_BONUS = 0.6f;

    // --- Talent income ------------------------------------------------------
    /** Classic: points per wave cleared. */
    public static final int TALENT_POINTS_PER_WAVE = 1;
    /** Endless: seconds survived per point. */
    public static final double TALENT_SECONDS_PER_POINT = 60.0;
    /** Aero-Mastery headwind slow, as a fraction. */
    public static final float STORM_WIND_SLOW = 0.30f;
    /** |wind| / WIND_MAX that counts as a "high" headwind. */
    public static final float HEADWIND_THRESHOLD = 0.45f;
    /** Each rank of Light Fingers cuts the grab delay by this much. */
    public static final double GRAB_CD_PER_RANK = 0.20;

    // --- Active skills ------------------------------------------------------
    public static final float LIGHTNING_RADIUS = 155f;
    public static final float LIGHTNING_DAMAGE = 0.85f;
    public static final double LIGHTNING_COOLDOWN = 14.0;
    public static final int METEOR_COUNT = 16;
    public static final float METEOR_RADIUS = 96f;
    public static final float METEOR_DAMAGE = 130f;
    public static final double METEOR_COOLDOWN = 26.0;
    public static final double FIRE_ZONE_TIME = 6.0;
    public static final float FIRE_ZONE_DPS = 48f;
    public static final double TORNADO_COOLDOWN = 30.0;
    public static final double TORNADO_LIFE = 6.0;
    public static final float TORNADO_SPEED = 120f;
    public static final float TORNADO_RADIUS = 130f;
    public static final float TORNADO_LIFT = 620f;
    public static final float TORNADO_SWIRL = 6.5f;

    private Tuning() {
    }
}
