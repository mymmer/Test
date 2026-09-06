package com.mymmer.castledefense.render;

/**
 * One-way notice that something visually notable just happened.
 *
 * <h2>Why this exists at all</h2>
 *
 * <p>Almost everything the renderer draws it can read from state: an enemy's
 * hurt flash, a boss's aura, a barricade's damage tint, a fire zone's remaining
 * life. Those need no events and get none — reading state is simpler and cannot
 * go stale.
 *
 * <p>What state cannot express is a <b>moment</b>. A Volatile detonating throws
 * sparks once; by the next frame it is gone from the roster and there is nothing
 * left to read. A cannon firing puffs smoke at the muzzle at the instant of the
 * shot. Those are the events, and they are the only ones.
 *
 * <h2>What this deliberately is not</h2>
 *
 * <p>It is <b>not</b> {@code SimulationTrace}. That is a diagnostic interface
 * with a recording implementation used by tests, and hanging production visuals
 * off it would make a debug facility load-bearing — turn the trace off and the
 * game stops sparkling. The two never meet.
 *
 * <p>It is <b>not</b> an event bus. There is one sink, set once, and gameplay
 * cannot ask who is listening or get anything back. Every method returns
 * {@code void} and none can fail, so nothing gameplay does can depend on whether
 * anyone is drawing — which is what makes it safe for the simulation to call.
 *
 * <p>The default is {@link #NONE}: a headless test runs the whole simulation with
 * no sink attached and behaves identically, which is the property that keeps
 * this honest.
 */
public interface VisualEvents {

    /** Particle shapes the source uses. */
    enum Shape { RECT, CIRCLE }

    /**
     * An outward spray. {@code sprites.Effects.burst}.
     *
     * @param count how many, before the quality cap is applied
     * @param rgb   packed 0xRRGGBB, so gameplay need not know about Color
     */
    void burst(float x, float y, int count, int rgb, float speed, float life,
               float size, float grav, Shape shape);

    /** An expanding ring of evenly spaced particles. {@code Effects.ring}. */
    void ring(float x, float y, int count, int rgb, float speed, float life,
              float size);

    /**
     * Rising text. {@code Effects.text}.
     *
     * <p>The message and its value are the caller's — the renderer never
     * recomputes a damage number or a reward, it only floats the one it was
     * handed.
     */
    void text(float x, float y, String message, int rgb, float size, float life);

    /** The sink when nothing is drawing. Every method does nothing. */
    VisualEvents NONE = new VisualEvents() {
        @Override
        public void burst(float x, float y, int count, int rgb, float speed,
                          float life, float size, float grav, Shape shape) {
        }

        @Override
        public void ring(float x, float y, int count, int rgb, float speed,
                         float life, float size) {
        }

        @Override
        public void text(float x, float y, String message, int rgb, float size,
                         float life) {
        }
    };

    // --- the colours the source passes, so call sites stay readable -----------
    int WHITE = 0xF0F4FA;
    int BLOOD = 0xE24A44;
    int GOLD = 0xF8CA4E;
    int STONE = 0xBEAA96;
    int SMOKE = 0xC8BEAA;
    int FIRE = 0xFF963C;
    int BONE = 0xDEDCCE;
    int MAGIC = 0xC48CFF;
    int SPARK = 0xFFE28C;
}
