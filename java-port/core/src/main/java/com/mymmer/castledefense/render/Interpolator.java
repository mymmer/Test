package com.mymmer.castledefense.render;

import com.badlogic.gdx.utils.LongMap;
import com.mymmer.castledefense.entity.Entity;

/**
 * Where a thing was last step, so it can be drawn between steps.
 *
 * <h2>Why the renderer owns this</h2>
 *
 * <p>The simulation runs at a fixed 60 Hz; a 144 Hz display therefore shows the
 * same positions for two or three frames in a row, which reads as a stutter on
 * anything moving fast — a thrown mob, a cannonball. Interpolating between the
 * previous and current step removes it.
 *
 * <p>The previous positions live <b>here</b>, keyed by entity uid, and not on the
 * entities. That is deliberate and is the whole safety argument: gameplay cannot
 * read them, cannot be affected by them, and cannot come to depend on them.
 * Collision, targeting, grabbing and every pointer conversion use the
 * simulation's own {@code x()} and {@code y()}. What this produces is a pair of
 * floats handed to a draw call and then forgotten.
 *
 * <pre>
 *   drawn = previous + (current - previous) * alpha
 * </pre>
 *
 * <h2>Teleports must not smear</h2>
 *
 * <p>Interpolating across a discontinuity draws a streak between two unrelated
 * places: a spawning enemy sliding in from wherever the last one died, a boss
 * appearing to fly across the field when it is placed. So a position that jumps
 * further than {@link #TELEPORT} in one step is treated as a teleport and drawn
 * at its current position with no blend. That covers spawn, respawn, the tornado
 * hurl, and a run reset — {@link #clear} handles the last of those explicitly
 * anyway, because a new run must never interpolate from the old one's positions.
 */
public final class Interpolator {

    /**
     * A one-step move beyond this is a teleport, not motion.
     *
     * <p>The fastest thing in the game is a mob hurled by a tornado, at up to
     * ~1180 px/s, which is under 20 px in a 1/60 s step. 240 is far above
     * anything real and far below any teleport.
     */
    public static final float TELEPORT = 240f;

    private static final class Sample {
        float prevX;
        float prevY;
        float curX;
        float curY;
        boolean teleported;
        boolean seen;
    }

    private final LongMap<Sample> samples = new LongMap<>();
    private final float[] out = new float[2];

    /**
     * Records the current positions as "current", pushing the old ones back.
     *
     * <p>Called once per <b>simulation step</b> from the game loop — not per
     * frame, which would collapse the interval this exists to span, and not from
     * a painter, which runs an unknown number of times per step.
     */
    public void step(long uid, float x, float y) {
        Sample s = samples.get(uid);
        if (s == null) {
            s = new Sample();
            //  A thing seen for the first time has no history and must not be
            //  blended in from nowhere.
            s.prevX = x;
            s.prevY = y;
            samples.put(uid, s);
        } else {
            s.prevX = s.curX;
            s.prevY = s.curY;
        }
        s.curX = x;
        s.curY = y;
        s.teleported = Math.abs(s.curX - s.prevX) > TELEPORT
                || Math.abs(s.curY - s.prevY) > TELEPORT;
        s.seen = true;
    }

    /** Marks the start of a step's recording, so the unseen can be dropped. */
    public void beginStep() {
        for (LongMap.Entry<Sample> e : samples.entries()) {
            e.value.seen = false;
        }
    }

    /**
     * Forgets everything not recorded this step.
     *
     * <p>Without it, an Endless run accumulates one entry per entity that ever
     * existed. With it, the map is the size of the live roster.
     */
    public void endStep() {
        LongMap.Keys keys = samples.keys();
        while (keys.hasNext) {
            long uid = keys.next();
            if (!samples.get(uid).seen) {
                keys.remove();
            }
        }
    }

    /**
     * Where to draw one entity this frame.
     *
     * <p>Falls back to the entity's current position whenever there is no usable
     * history — an unknown uid, a teleport, the first step of its life. Drawing
     * at the truth is always safe; drawing a blend of two unrelated positions is
     * not.
     *
     * @return a two-element array owned by this class, valid until the next call
     */
    public float[] draw(Entity entity, float x, float y, float alpha) {
        Sample s = samples.get(entity.uid());
        if (s == null || s.teleported) {
            out[0] = x;
            out[1] = y;
            return out;
        }
        out[0] = s.prevX + (s.curX - s.prevX) * alpha;
        out[1] = s.prevY + (s.curY - s.prevY) * alpha;
        return out;
    }

    /** Everything forgotten. Called on a run reset. */
    public void clear() {
        samples.clear();
    }

    /** How many entities are being tracked. For the overlay and the tests. */
    public int tracked() {
        return samples.size;
    }

    /** Whether this uid would be drawn without blending. For the tests. */
    public boolean isTeleporting(long uid) {
        Sample s = samples.get(uid);
        return s == null || s.teleported;
    }
}
