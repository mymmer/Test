package com.mymmer.castledefense.config;

/**
 * One difficulty preset — the Java form of Python's {@code Difficulty}
 * NamedTuple, loaded from {@code assets/data/difficulties.json}.
 *
 * <p>Immutable: a preset is read once at startup and shared. Nothing in the
 * simulation may modify it, which is why every field is final and there are no
 * setters.
 *
 * <p>Field meanings are the Python ones, unchanged:
 * <ul>
 *   <li>{@code scale} — multiplies enemy health and damage scaling</li>
 *   <li>{@code gold} — payout multiplier</li>
 *   <li>{@code headstart} — extra effective waves a boss spawns with</li>
 *   <li>{@code speed} — flat multiplier on every enemy's base movement speed</li>
 *   <li>{@code hpCurve} — multiplies the growth earned per tier (1.60 = +60%)</li>
 *   <li>{@code bossFire} — boss projectile interval multiplier (0.50 = 2x rate)</li>
 *   <li>{@code eliteHorn} — the Challenge Horn calls in elites</li>
 *   <li>{@code grabCd} — seconds the cursor waits between grabs</li>
 * </ul>
 */
public final class DifficultyConfig {

    private final String id;
    private final String label;
    private final float scale;
    private final float gold;
    private final float headstart;
    private final float speed;
    private final float hpCurve;
    private final double bossFire;
    private final boolean eliteHorn;
    private final double grabCd;
    private final String blurb;

    public DifficultyConfig(String id, String label, float scale, float gold,
                            float headstart, float speed, float hpCurve,
                            double bossFire, boolean eliteHorn, double grabCd,
                            String blurb) {
        this.id = id;
        this.label = label;
        this.scale = scale;
        this.gold = gold;
        this.headstart = headstart;
        this.speed = speed;
        this.hpCurve = hpCurve;
        this.bossFire = bossFire;
        this.eliteHorn = eliteHorn;
        this.grabCd = grabCd;
        this.blurb = blurb;
    }

    public String id() {
        return id;
    }

    public String label() {
        return label;
    }

    public float scale() {
        return scale;
    }

    public float gold() {
        return gold;
    }

    public float headstart() {
        return headstart;
    }

    public float speed() {
        return speed;
    }

    public float hpCurve() {
        return hpCurve;
    }

    public double bossFire() {
        return bossFire;
    }

    public boolean eliteHorn() {
        return eliteHorn;
    }

    public double grabCd() {
        return grabCd;
    }

    public String blurb() {
        return blurb;
    }

    @Override
    public String toString() {
        return "Difficulty[" + id + "]";
    }
}
