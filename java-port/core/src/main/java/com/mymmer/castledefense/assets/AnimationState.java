package com.mymmer.castledefense.assets;

/**
 * The animation states gameplay can ask a visual to be in.
 *
 * <p>Gameplay sets a <em>state</em>, never a frame name: {@code visual
 * .setAnimation(WALK)}, not {@code "scout_walk_2"}. A skin that supplies only a
 * single static image is completely valid — every state resolves to that one
 * region — which is what lets the game ship with no artwork at all.
 */
public enum AnimationState {

    IDLE("idle"),
    WALK("walk"),
    ATTACK("attack"),
    HURT("hurt"),
    GRABBED("grabbed"),
    AIR("air"),
    DEAD("dead"),

    // boss and special-unit extras
    CAST("cast"),
    BREATHE("breathe"),
    LEAP("leap"),
    REEL("reel"),
    RETRIEVE("retrieve"),
    DISARMED("disarmed"),
    CLOAKED("cloaked");

    private final String key;

    AnimationState(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    public static AnimationState byKey(String key) {
        if (key == null) {
            return null;
        }
        for (AnimationState s : values()) {
            if (s.key.equals(key)) {
                return s;
            }
        }
        return null;
    }
}
