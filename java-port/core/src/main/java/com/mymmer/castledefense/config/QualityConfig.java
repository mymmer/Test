package com.mymmer.castledefense.config;

/**
 * Graphics quality presets.
 *
 * <p><b>These may only ever change how the game looks.</b> Enemy counts, health,
 * damage, collision, physics, projectile behaviour, scoring and spawn rates are
 * identical on every preset — a cheap phone and a flagship play exactly the same
 * game, they just render different numbers of sparks. Anything that would change
 * the simulation does not belong in this enum.
 *
 * <p>The particle cap is the one number that needs care: it is a
 * <em>rendering</em> budget, and the effects system drops particles it cannot
 * afford rather than refusing to spawn them, so the simulation never notices.
 */
public enum QualityConfig {

    /** Low-end phones: minimal particles, no glows, no trails. */
    LOW(220, 0f, false, false),

    /** The default: most effects, moderate particle budget. */
    MEDIUM(520, 0.6f, true, false),

    /** Desktop and flagship phones: the full Python look. */
    HIGH(GameConfig.MAX_PARTICLES, 1f, true, true);

    private final int maxParticles;
    private final float glowIntensity;
    private final boolean shadows;
    private final boolean trails;

    QualityConfig(int maxParticles, float glowIntensity, boolean shadows, boolean trails) {
        this.maxParticles = maxParticles;
        this.glowIntensity = glowIntensity;
        this.shadows = shadows;
        this.trails = trails;
    }

    public int maxParticles() {
        return maxParticles;
    }

    public float glowIntensity() {
        return glowIntensity;
    }

    public boolean shadows() {
        return shadows;
    }

    public boolean trails() {
        return trails;
    }

    /** Parses a stored name, falling back to {@link #HIGH} for anything odd. */
    public static QualityConfig parse(String name, QualityConfig fallback) {
        if (name == null) {
            return fallback;
        }
        for (QualityConfig q : values()) {
            if (q.name().equalsIgnoreCase(name.trim())) {
                return q;
            }
        }
        return fallback;
    }
}
