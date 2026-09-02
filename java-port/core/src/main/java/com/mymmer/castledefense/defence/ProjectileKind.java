package com.mymmer.castledefense.defence;

/**
 * What kind of flying thing this is. Python {@code Projectile.kind}.
 *
 * <p>Two behavioural switches hang off it and are gameplay, not decoration:
 * {@link #explodesOnGround()} (cannon and fire shells detonate when they reach
 * the ground line) and {@link #radius()} (the collision radius used against the
 * barricade). Colours and sprites do not live here — they are Phase 11.
 */
public enum ProjectileKind {

    ARROW("arrow", 4f),
    BOLT("bolt", 6f),
    CANNON("cannon", 9f),
    MAGIC("magic", 8f),
    FIRE("fire", 11f),
    BONE("bone", 5f);

    private final String id;
    private final float radius;

    ProjectileKind(String id, float radius) {
        this.id = id;
        this.radius = radius;
    }

    /** Stable id used in data files and traces. Never the ordinal. */
    public String id() {
        return id;
    }

    /** Python's {@code radius} table, defaulting to 5 for anything unlisted. */
    public float radius() {
        return radius;
    }

    /**
     * True for the two kinds that detonate on the ground line.
     *
     * <p>Python: {@code if self.kind in ("cannon", "fire") and self.y >= GROUND_Y - 2}.
     */
    public boolean explodesOnGround() {
        return this == CANNON || this == FIRE;
    }

    /** Looks up a kind by its stable id. */
    public static ProjectileKind byId(String id, ProjectileKind fallback) {
        if (id != null) {
            for (ProjectileKind k : values()) {
                if (k.id.equals(id)) {
                    return k;
                }
            }
        }
        return fallback;
    }
}
