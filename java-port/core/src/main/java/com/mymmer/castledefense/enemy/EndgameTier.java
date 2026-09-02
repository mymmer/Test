package com.mymmer.castledefense.enemy;

/**
 * One endgame tier: the repaint-and-buff that lands on every mob past a wave.
 *
 * <p>The tint and its strength are carried even though Phase 6 draws nothing.
 * They are not decoration in the "safe to drop" sense — the whole point of the
 * tier is that the danger is readable at a glance, so the colour is part of the
 * design and Phase 11 needs it. It is kept here, unused, rather than being
 * rediscovered later.
 */
public final class EndgameTier {

    /** Stable semantic id. */
    public final String id;
    /** Display name, shown next to the mob. Localisation key in Phase 10. */
    public final String name;
    /** First wave this tier applies from. */
    public final int firstWave;
    /** Tint, as 0xRRGGBB. Rendering metadata; nothing in Phase 6 reads it. */
    public final int tint;
    /** How far toward the tint a mob's colour is mixed, 0..1. */
    public final float tintStrength;
    public final float hpMult;
    public final float damageMult;
    public final float speedMult;

    EndgameTier(String id, String name, int firstWave, int tint, float tintStrength,
                float hpMult, float damageMult, float speedMult) {
        this.id = id;
        this.name = name;
        this.firstWave = firstWave;
        this.tint = tint;
        this.tintStrength = tintStrength;
        this.hpMult = hpMult;
        this.damageMult = damageMult;
        this.speedMult = speedMult;
    }

    @Override
    public String toString() {
        return id + "(w" + firstWave + " hp x" + hpMult + ")";
    }
}
