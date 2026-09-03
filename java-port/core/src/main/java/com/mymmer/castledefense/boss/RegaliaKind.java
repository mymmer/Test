package com.mymmer.castledefense.boss;

/**
 * What kind of boss equipment a {@link DroppedItem} is.
 *
 * <p>Its gameplay geometry lives here, not in artwork: a crown's pickup box is
 * 44x26 because the game says so, and a skin that draws a four-times larger
 * crown does not make it easier to grab off the ground.
 */
public enum RegaliaKind {

    /** The Troll King's crown: wide, flat, and thrown a long way. */
    CROWN("crown", 44f, 26f, 2300f),

    /**
     * The Lich Lord's staff: tall, narrow, and <b>much</b> harder to throw.
     * The 780 cap against the crown's 2300 is what stops a disarmed Lich's
     * staff being flung off the map — he recalls it either way, but the player
     * cannot buy extra seconds by punting it into the distance.
     */
    STAFF("staff", 18f, 60f, 780f);

    private final String id;
    private final float width;
    private final float height;
    private final float maxThrowSpeed;

    RegaliaKind(String id, float width, float height, float maxThrowSpeed) {
        this.id = id;
        this.width = width;
        this.height = height;
        this.maxThrowSpeed = maxThrowSpeed;
    }

    /** Stable semantic id, for data, saves and traces. Never the ordinal. */
    public String id() {
        return id;
    }

    /** Gameplay pickup/collision width. Python {@code DroppedItem.w}. */
    public float width() {
        return width;
    }

    /** Gameplay pickup/collision height. Python {@code DroppedItem.h}. */
    public float height() {
        return height;
    }

    /** Speed cap applied on release. Python {@code REGALIA_MAX_THROW}. */
    public float maxThrowSpeed() {
        return maxThrowSpeed;
    }

    public static RegaliaKind byId(String id, RegaliaKind fallback) {
        if (id != null) {
            for (RegaliaKind k : values()) {
                if (k.id.equals(id)) {
                    return k;
                }
            }
        }
        return fallback;
    }
}
