package com.mymmer.castledefense.boss;

import com.mymmer.castledefense.config.GameplayAnchor;

/**
 * One boss's stat block and its <b>gameplay anchors</b>.
 *
 * <p>Numbers are data; behaviour is Java. A designer may retune the Dragon's
 * breath cadence without a recompile; nobody expresses "cuts the breath off and
 * drives it back 120 px" in JSON.
 *
 * <h2>The anchors here are gameplay-authoritative</h2>
 *
 * <p>{@link #regaliaAnchor} is where the crown can be grabbed, where the dropped
 * item is born, and where the retrieval ends. It comes from
 * {@code data/bosses.json} and is <b>identical under every skin</b>. The skin's
 * matching {@code AttachmentPoint} decides only where the crown <em>artwork</em>
 * is drawn. See {@link GameplayAnchor}.
 */
public final class BossConfig {

    public final BossType type;
    /** Display name. Localisation key in Phase 10. */
    public final String name;
    public final String description;
    /** Pre-wave advice, shown in the shop. Localisation key in Phase 10. */
    public final String hint;

    public final float baseHp;
    public final float baseSpeed;
    public final float baseDamage;
    public final float attackRate;
    public final int gold;
    public final float armor;
    public final float mass;
    public final float width;
    public final float height;
    public final boolean flying;
    public final float flyY;

    /** Seconds of intro before the boss is fully engaged. */
    public final float introTime;

    /**
     * Where the interactive regalia sits, normalised to the gameplay box.
     *
     * <p>Null for a boss with none. The Dragon's is its claws, which are not
     * regalia — but they share the guard mechanism, so they share the anchor
     * slot too. See {@code BOSSES.md}.
     */
    public final GameplayAnchor regaliaAnchor;

    /** Half-width of the interactive area around the anchor, in world units. */
    public final float regaliaHalfWidth;
    /** Half-height of the interactive area around the anchor, in world units. */
    public final float regaliaHalfHeight;
    /** Vertical offset of the interactive box's centre from the anchor. */
    public final float regaliaBoxOffsetY;

    // --- per-boss specials --------------------------------------------------
    /** Troll King: how far from the castle he must be to leap, and how far he goes. */
    public final float leapDistance;
    public final float leapMinRange;
    public final float leapIntervalMin;
    public final float leapIntervalMax;

    /** Dragon: seconds of sustained breath, and the gap between its fireballs. */
    public final float breathTime;
    public final float breathShotInterval;
    /** Each breath bolt as a fraction of a full hit. */
    public final float breathPower;
    public final float breathIntervalMin;
    public final float breathIntervalMax;
    public final float standoffX;

    /** Lich Lord: ward strength and duration, and the summon cadence. */
    public final float wardDamageMultiplier;
    public final float wardDuration;
    public final float wardIntervalMin;
    public final float wardIntervalMax;
    public final float summonIntervalBase;
    public final float summonIntervalFloor;
    public final float summonIntervalPerWave;
    public final int summonBaseCount;
    public final int summonMaxBonus;

    BossConfig(BossType type, String name, String description, String hint,
               float baseHp, float baseSpeed, float baseDamage, float attackRate,
               int gold, float armor, float mass, float width, float height,
               boolean flying, float flyY, float introTime,
               GameplayAnchor regaliaAnchor, float regaliaHalfWidth,
               float regaliaHalfHeight, float regaliaBoxOffsetY,
               float leapDistance, float leapMinRange,
               float leapIntervalMin, float leapIntervalMax,
               float breathTime, float breathShotInterval, float breathPower,
               float breathIntervalMin, float breathIntervalMax, float standoffX,
               float wardDamageMultiplier, float wardDuration,
               float wardIntervalMin, float wardIntervalMax,
               float summonIntervalBase, float summonIntervalFloor,
               float summonIntervalPerWave, int summonBaseCount, int summonMaxBonus) {
        this.type = type;
        this.name = name;
        this.description = description;
        this.hint = hint;
        this.baseHp = baseHp;
        this.baseSpeed = baseSpeed;
        this.baseDamage = baseDamage;
        this.attackRate = attackRate;
        this.gold = gold;
        this.armor = armor;
        this.mass = mass;
        this.width = width;
        this.height = height;
        this.flying = flying;
        this.flyY = flyY;
        this.introTime = introTime;
        this.regaliaAnchor = regaliaAnchor;
        this.regaliaHalfWidth = regaliaHalfWidth;
        this.regaliaHalfHeight = regaliaHalfHeight;
        this.regaliaBoxOffsetY = regaliaBoxOffsetY;
        this.leapDistance = leapDistance;
        this.leapMinRange = leapMinRange;
        this.leapIntervalMin = leapIntervalMin;
        this.leapIntervalMax = leapIntervalMax;
        this.breathTime = breathTime;
        this.breathShotInterval = breathShotInterval;
        this.breathPower = breathPower;
        this.breathIntervalMin = breathIntervalMin;
        this.breathIntervalMax = breathIntervalMax;
        this.standoffX = standoffX;
        this.wardDamageMultiplier = wardDamageMultiplier;
        this.wardDuration = wardDuration;
        this.wardIntervalMin = wardIntervalMin;
        this.wardIntervalMax = wardIntervalMax;
        this.summonIntervalBase = summonIntervalBase;
        this.summonIntervalFloor = summonIntervalFloor;
        this.summonIntervalPerWave = summonIntervalPerWave;
        this.summonBaseCount = summonBaseCount;
        this.summonMaxBonus = summonMaxBonus;
    }

    @Override
    public String toString() {
        return "BossConfig[" + type.id() + " hp=" + baseHp + " gold=" + gold + "]";
    }
}
