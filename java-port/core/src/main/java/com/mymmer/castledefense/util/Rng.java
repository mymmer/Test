package com.mymmer.castledefense.util;

import com.badlogic.gdx.math.RandomXS128;

/**
 * The project's random number generators.
 *
 * <p>Two independent streams, deliberately:
 *
 * <ul>
 *   <li>{@link #game()} — everything that affects play: wave composition, spawn
 *       jitter, crit rolls, boss timers, drop rolls.</li>
 *   <li>{@link #decoration()} — everything that only affects looks: particle
 *       spread, background stars, wall cracks, screen shake offsets.</li>
 * </ul>
 *
 * <p>The split fixes a real quirk in the Python game: {@code Castle.draw} calls
 * {@code random.seed(1337)} … {@code random.seed()} on every frame below 66%
 * health, so <em>rendering perturbs the gameplay RNG</em>. Keeping decoration on
 * its own stream means the gameplay sequence no longer depends on what is being
 * drawn — an intentional, documented deviation (see
 * {@code docs/PORT_ANALYSIS.md} §14). Probability distributions are unchanged.
 *
 * <p>Instances are not thread-safe; the simulation is single-threaded by design.
 */
public final class Rng {

    private final RandomXS128 game;
    private final RandomXS128 decoration;
    private long gameSeed;

    public Rng() {
        this(System.nanoTime());
    }

    public Rng(long seed) {
        gameSeed = seed;
        game = new RandomXS128(seed);
        // a fixed offset keeps decoration reproducible without ever colliding
        // with the gameplay stream
        decoration = new RandomXS128(seed ^ 0x5DEECE66DL);
    }

    /** The gameplay stream. Never call this from rendering code. */
    public RandomXS128 game() {
        return game;
    }

    /** The looks-only stream. Never call this from simulation code. */
    public RandomXS128 decoration() {
        return decoration;
    }

    /** Restarts the gameplay stream from a known seed — used by tests. */
    public void reseedGame(long seed) {
        gameSeed = seed;
        game.setSeed(seed);
    }

    public long getGameSeed() {
        return gameSeed;
    }

    /**
     * A fresh seed for a new run, from the gameplay stream itself.
     *
     * <p>Players never see or choose a seed. A debug build, a test, or a bug
     * report does: "build abc123, seed 82736191, mode endless" is enough to
     * replay a session, which is the whole reason the seed is tracked.
     */
    public long nextRunSeed() {
        return game.nextLong();
    }

    /** One line for the crash log and the debug overlay. */
    public String describe() {
        return "seed=" + gameSeed;
    }

    /**
     * Reads a seed supplied for debugging, or returns {@code fallback}.
     *
     * <p>Checked in order: the {@code castledefense.seed} system property (which
     * a launcher flag or a test can set), then nothing. Production players have
     * no way to reach this and no reason to.
     */
    public static long debugSeedOr(long fallback) {
        String raw = System.getProperty("castledefense.seed");
        if (raw == null || raw.trim().isEmpty()) {
            return fallback;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    // --- conveniences that read like the Python they replace -----------------

    /** Python {@code random.uniform(a, b)}. */
    public float uniform(float a, float b) {
        return a + game.nextFloat() * (b - a);
    }

    /** Python {@code random.random() < chance}. */
    public boolean chance(float chance) {
        return game.nextFloat() < chance;
    }

    /** Python {@code random.randint(lo, hi)} — inclusive at both ends. */
    public int rangeInclusive(int lo, int hi) {
        if (hi <= lo) {
            return lo;
        }
        return lo + game.nextInt(hi - lo + 1);
    }

    /** Uniform pick from a non-empty array. */
    public <T> T pick(T[] items) {
        if (items == null || items.length == 0) {
            throw new IllegalArgumentException("cannot pick from an empty set");
        }
        return items[game.nextInt(items.length)];
    }
}
