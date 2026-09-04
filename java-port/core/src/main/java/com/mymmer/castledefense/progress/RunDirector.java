package com.mymmer.castledefense.progress;

/**
 * What paces a run.
 *
 * <p>Two implementations, and they are genuinely different games rather than one
 * with a flag: {@link WaveDirector} runs numbered waves with a shop between them
 * and pauses itself when a wave is cleared; {@link EndlessDirector} runs a clock
 * and never pauses itself at all. Sharing a base class would mean a sequence of
 * {@code if (endless)} branches, which is exactly what the Python does and
 * exactly what is worth not reproducing.
 *
 * <p>What they do share is this interface and the {@link ChallengeHorn}, because
 * the horn is one mechanic with two behaviours rather than two mechanics.
 */
public interface RunDirector {

    /** Sets the run going. Classic starts wave 1; Endless arms the timetable. */
    void begin();

    /**
     * One simulation step of pacing.
     *
     * <p>Called only while the world advances, so a frozen state — the Endless
     * realtime shop above all — stops the run clock, the spawn timer, the tier
     * ladder and the boss timetable together.
     *
     * @param dt the canonical step
     */
    void update(double dt);

    /**
     * Blows the Challenge Horn.
     *
     * @return true if it was blown; false if it was already used, or there was
     *         nothing to call in
     */
    boolean blowHorn();

    /** How many spawns are still queued. Classic's wave-clear gate reads this. */
    int pendingSpawns();

    /** One line for the debug overlay. Built on demand only. */
    String describe();
}
