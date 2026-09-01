package com.mymmer.castledefense.platform;

/**
 * The moments worth a buzz.
 *
 * <p>Named events rather than durations, so the platform decides what each one
 * feels like and gameplay never hard-codes milliseconds. The list is short on
 * purpose: haptics that fire constantly are worse than none, so only impacts a
 * player would call "significant" appear here.
 *
 * <p>Haptics are always optional. Nothing in the game may depend on one being
 * felt, and the player can switch them off.
 */
public enum HapticEvent {

    /** A heavy unit lands or is slammed. */
    HEAVY_IMPACT(28),
    /** A boss takes a real hit. */
    BOSS_HIT(18),
    /** An armour plate comes away. */
    ARMOR_STRIPPED(22),
    /** A crown or staff is torn off. */
    REGALIA_TAKEN(40),
    /** A tower is hand-fired at full draw. */
    OVERCHARGE(24),
    /** A major skill goes off. */
    SKILL_CAST(35);

    private final int suggestedMillis;

    HapticEvent(int suggestedMillis) {
        this.suggestedMillis = suggestedMillis;
    }

    /** A hint for platforms with no richer haptic API. */
    public int suggestedMillis() {
        return suggestedMillis;
    }
}
