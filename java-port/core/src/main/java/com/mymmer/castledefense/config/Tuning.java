package com.mymmer.castledefense.config;

/**
 * Pacing constants — the Java home of {@code main.py}'s tuning blocks.
 *
 * <p>Kept apart from {@link GameConfig} because these are the numbers a designer
 * reaches for when the game feels too fast or too slow, and the Python source
 * deliberately gathers them in one editable block ("Tweak freely; nothing
 * outside this block needs to change to re-time the bosses or the spawn rate").
 * That property is worth preserving.
 */
public final class Tuning {

    // --- Endless: spawn and tier timetable ----------------------------------
    /** Seconds between tier steps. */
    public static final float ENDLESS_TIER_SECONDS = 30f;
    /** Gap between spawns at the very start of a run. */
    public static final float ENDLESS_SPAWN_START = 1.70f;
    /** Hard floor on that gap, however long the run lasts. */
    public static final float ENDLESS_SPAWN_MIN = 0.38f;
    /** Seconds taken to ramp from START down to MIN. */
    public static final float ENDLESS_SPAWN_RAMP = 300f;
    /** Randomness applied to each gap, as a fraction either way. */
    public static final float ENDLESS_SPAWN_JITTER = 0.28f;
    /** Spawning pauses above this many live mobs. */
    public static final int ENDLESS_MAX_ALIVE = 60;

    // --- Endless: boss timetable --------------------------------------------
    /** Scripted arrivals, in seconds since the run began. */
    public static final float[] ENDLESS_BOSS_TIMES = {120f, 240f, 360f};
    /** After the scripted three, one random boss every this many seconds. */
    public static final float ENDLESS_BOSS_REPEAT = 120f;

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
    public static final float TALENT_SECONDS_PER_POINT = 60f;
    /** Aero-Mastery headwind slow, as a fraction. */
    public static final float STORM_WIND_SLOW = 0.30f;
    /** |wind| / WIND_MAX that counts as a "high" headwind. */
    public static final float HEADWIND_THRESHOLD = 0.45f;
    /** Each rank of Light Fingers cuts the grab delay by this much. */
    public static final float GRAB_CD_PER_RANK = 0.20f;

    // --- Active skills ------------------------------------------------------
    public static final float LIGHTNING_RADIUS = 155f;
    public static final float LIGHTNING_DAMAGE = 0.85f;
    public static final float LIGHTNING_COOLDOWN = 14f;
    public static final int METEOR_COUNT = 16;
    public static final float METEOR_RADIUS = 96f;
    public static final float METEOR_DAMAGE = 130f;
    public static final float METEOR_COOLDOWN = 26f;
    public static final float FIRE_ZONE_TIME = 6f;
    public static final float FIRE_ZONE_DPS = 48f;
    public static final float TORNADO_COOLDOWN = 30f;
    public static final float TORNADO_LIFE = 6f;
    public static final float TORNADO_SPEED = 120f;
    public static final float TORNADO_RADIUS = 130f;
    public static final float TORNADO_LIFT = 620f;
    public static final float TORNADO_SWIRL = 6.5f;

    private Tuning() {
    }
}
