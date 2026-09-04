package com.mymmer.castledefense.defence;

/**
 * The talent effects the defence side reads.
 *
 * <p>Narrow on purpose. The full talent tree — nodes, ranks, prerequisites,
 * costs, the panel — is Phase 9. What Phase 5 needs is the handful of scalars
 * the Python {@code TalentTree} exposes as properties, so the defence formulas
 * can be ported and tested with their multipliers in place instead of having
 * them retro-fitted later.
 *
 * <p>{@link #NONE} is the untalented baseline: every multiplier 1.0, every bonus
 * 0. Tests that are not about talents use it and read the raw Python numbers.
 */
public interface CombatModifiers {

    /** No talents bought: every value at its neutral identity. */
    CombatModifiers NONE = new CombatModifiers() {
    };

    /** Reload multiplier, {@code 1 - min(0.45, rate)}. Lower is faster. */
    default double towerRate() {
        return 1d;
    }

    /** Damage multiplier, {@code 1 + power}. */
    default float towerDamage() {
        return 1f;
    }

    /** Crit probability per hit, 0..1. A crit is x3 damage. */
    default float critChance() {
        return 0f;
    }

    /** Extra Ballista pierces. */
    default int extraPierce() {
        return 0;
    }

    /** Splash radius multiplier applied when a projectile is created. */
    default float splashMult() {
        return 1f;
    }

    /** Overcharge cooldown multiplier, {@code 1 - min(0.5, overcharge)}. */
    default double overchargeCd() {
        return 1d;
    }

    /** Barricade self-repair per second, as a fraction of max HP. */
    default float barricadeRegen() {
        return 0f;
    }

    /** Bleed follow-up on a spike bite, as a fraction of the spike damage. */
    default float spikeDot() {
        return 0f;
    }

    /** Tower HP multiplier applied when a tower is stationed. */
    default float towerHp() {
        return 1f;
    }

    /** Rebuild-time multiplier, {@code 1 - min(0.6, rebuild)}. */
    default double rebuildMult() {
        return 1d;
    }

    /** Incoming castle damage multiplier, {@code 1 - min(0.4, thorns)}. */
    default float damageTaken() {
        return 1f;
    }

    /** Extra simultaneous allies the trapped Necromancer may keep raised. */
    default int allyCapBonus() {
        return 0;
    }

    /** Ally raise-interval multiplier. Lower is faster. */
    default double allyRate() {
        return 1d;
    }

    // ========================================================================
    //  Enemy-side effects (Phase 6).
    //
    //  Added here rather than in a second interface because there is one talent
    //  tree, and splitting it would mean two objects to thread through the world
    //  and two places for a talent to be forgotten.  Package `enemy` depends on
    //  package `defence` already; the reverse is still never true.
    // ========================================================================

    /** Throw velocity multiplier applied on release. */
    default float throwPower() {
        return 1f;
    }

    /** Fall-damage multiplier applied on landing. */
    default float fallDamage() {
        return 1f;
    }

    /** How strongly wind pushes an airborne body. */
    default float windMult() {
        return 1f;
    }

    /** Health and damage multiplier for a raised ally. */
    default float allyPower() {
        return 1f;
    }

    /** Incoming damage multiplier for an ally. Lower is tougher. */
    default float allyTough() {
        return 1f;
    }

    /** Extra seconds before an ally crumbles. */
    default double allyLife() {
        return 0d;
    }

    /** Undead Sentinels: allies chase anything, in any direction. */
    default boolean allySentinels() {
        return false;
    }

    /** Crowd gold step per extra mob on screen. */
    default float goldPop() {
        return 1f;
    }

    /** Flat gold multiplier per kill. */
    default float killGold() {
        return 1f;
    }

    /** Heaviest-mass multiplier on the grab capacity table. */
    default float grabBonus() {
        return 1f;
    }

    /** Grave Chill: slows an attacking mob while allies are up, 0..0.6. */
    default float graveChill() {
        return 0f;
    }

    /** Gale: slows every mob while a strong headwind blows. */
    default float stormWindSlow() {
        return 0f;
    }
}
