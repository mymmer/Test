package com.mymmer.castledefense.render;

import com.badlogic.gdx.math.RandomXS128;
import com.mymmer.castledefense.util.Rng;

/**
 * Randomness for things that are only looked at.
 *
 * <h2>Why this is not just {@code Rng.game()}</h2>
 *
 * <p>The Python source draws from the module-level {@code random} inside its
 * draw methods — the screen shake offset, the lightning bolt's jagged path, the
 * castle's cracks. That makes the gameplay stream depend on how many frames were
 * rendered, which on a 144 Hz display is a different game from a 60 Hz one.
 *
 * <p>Phase 1 separated the two streams. This class is the render side of that
 * separation made explicit and hard to get wrong: it wraps only
 * {@link Rng#decoration()}, exposes no route to {@link Rng#game()}, and is the
 * only generator any renderer is given.
 *
 * <p>{@code RenderPurityTest} states the consequence directly: rendering ten
 * thousand frames before a simulation step leaves the next gameplay draw
 * identical to rendering none.
 *
 * <h2>Stable decoration</h2>
 *
 * <p>Some decoration must not flicker — the castle's cracks are in fixed places
 * as the wall degrades, and the source achieves that with
 * {@code random.seed(1337)} before drawing them and {@code random.seed()} after.
 * {@link #stable(long)} is the equivalent without touching any shared stream: a
 * private generator keyed to whatever the caller considers the identity of that
 * decoration. Same key, same crack pattern, every frame, for ever.
 */
public final class VisualRng {

    /** The source's own seed for its fixed decoration. */
    public static final long CRACK_SEED = 1337L;

    private final RandomXS128 stream;
    private final RandomXS128 scratch = new RandomXS128(1L);

    public VisualRng(Rng rng) {
        if (rng == null) {
            throw new IllegalArgumentException("rng must not be null");
        }
        //  Only ever the decoration stream.  There is deliberately no
        //  constructor, field or accessor here that can reach the gameplay one.
        this.stream = rng.decoration();
    }

    /** For tests and tools that have no {@link Rng}. */
    public VisualRng(long seed) {
        this.stream = new RandomXS128(seed);
    }

    // ========================================================================
    //  Free-running decoration: sparks, shake, flicker
    // ========================================================================

    public float uniform(float lo, float hi) {
        return lo + stream.nextFloat() * (hi - lo);
    }

    public float nextFloat() {
        return stream.nextFloat();
    }

    public int range(int loInclusive, int hiInclusive) {
        return loInclusive + stream.nextInt(hiInclusive - loInclusive + 1);
    }

    public boolean chance(float p) {
        return stream.nextFloat() < p;
    }

    /** An angle in radians, uniform over the circle. */
    public float angle() {
        return stream.nextFloat() * com.badlogic.gdx.math.MathUtils.PI2;
    }

    // ========================================================================
    //  Stable decoration: the same pattern every frame
    // ========================================================================

    /**
     * A generator reset to a fixed key, for decoration that must not move.
     *
     * <p>The returned instance is <b>shared and reset on every call</b>, so use
     * it immediately and do not retain it. That is deliberate: a caller holding
     * one across frames would be building exactly the hidden state this class
     * exists to avoid.
     *
     * <p>The source's equivalent is {@code random.seed(1337)} ... {@code random.seed()},
     * which perturbs the global stream on the way past. This does not.
     */
    public RandomXS128 stable(long key) {
        scratch.setSeed(key);
        return scratch;
    }

    /** {@code uniform} on a stable generator, for readability at call sites. */
    public static float uniform(RandomXS128 r, float lo, float hi) {
        return lo + r.nextFloat() * (hi - lo);
    }

    /** {@code randint(lo, hi)} inclusive, as Python's is. */
    public static int range(RandomXS128 r, int loInclusive, int hiInclusive) {
        return loInclusive + r.nextInt(hiInclusive - loInclusive + 1);
    }
}
