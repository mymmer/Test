package com.mymmer.castledefense.assets;

import com.badlogic.gdx.utils.ObjectMap;

/**
 * How one unit's animation states map onto atlas regions.
 *
 * <p>A state is described by a base region name, a frame count and a rate. Frame
 * <i>n</i> is {@code <base>_<n>}; a one-frame state is just {@code <base>}.
 * Resolution is pure string work here — the actual regions are looked up by
 * {@link UnitVisual}, which is what keeps this class testable without a GPU.
 *
 * <p>A missing state falls back to {@link AnimationState#IDLE}, and a missing
 * IDLE falls back to the unit's base region, so a skin with a single static
 * image is valid and complete.
 */
public final class AnimationSet {

    /** One state's frames. */
    public static final class Clip {
        private final AnimationState state;
        private final String base;
        private final int frames;
        private final float fps;
        private final boolean looping;

        public Clip(AnimationState state, String base, int frames, float fps, boolean looping) {
            this.state = state;
            this.base = base;
            this.frames = Math.max(1, frames);
            this.fps = fps > 0f ? fps : 1f;
            this.looping = looping;
        }

        public AnimationState state() {
            return state;
        }

        public String base() {
            return base;
        }

        public int frames() {
            return frames;
        }

        public float fps() {
            return fps;
        }

        public boolean looping() {
            return looping;
        }

        public boolean isStatic() {
            return frames == 1;
        }

        /** Region name for a frame index, wrapping or clamping as configured. */
        public String regionFor(int frameIndex) {
            if (frames == 1) {
                return base;
            }
            int i = looping
                    ? Math.floorMod(frameIndex, frames)
                    : Math.min(Math.max(frameIndex, 0), frames - 1);
            return base + "_" + i;
        }

        /** Region name at a point in time, in seconds since the state began. */
        public String regionAt(float seconds) {
            if (frames == 1) {
                return base;
            }
            return regionFor((int) (seconds * fps));
        }
    }

    private final ObjectMap<AnimationState, Clip> clips = new ObjectMap<>();
    private final String fallbackRegion;

    public AnimationSet(String fallbackRegion) {
        this.fallbackRegion = fallbackRegion;
    }

    public AnimationSet put(Clip clip) {
        clips.put(clip.state(), clip);
        return this;
    }

    public boolean has(AnimationState state) {
        return clips.containsKey(state);
    }

    public int size() {
        return clips.size;
    }

    /**
     * The clip for a state, or the closest thing to it: the requested state,
     * else IDLE, else a synthesised one-frame clip over the base region.
     */
    public Clip resolve(AnimationState state) {
        Clip c = clips.get(state);
        if (c != null) {
            return c;
        }
        c = clips.get(AnimationState.IDLE);
        if (c != null) {
            return c;
        }
        return new Clip(state, fallbackRegion, 1, 1f, false);
    }

    /** Region to draw for a state at a point in time. Never null. */
    public String regionAt(AnimationState state, float seconds) {
        return resolve(state).regionAt(seconds);
    }
}
