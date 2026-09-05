package com.mymmer.castledefense.talent;

/**
 * What a talent actually does, as a stable id.
 *
 * <p>Each node in {@code data/talents.json} names one of these, and
 * {@link TalentTree} is the single place that turns a rank into the value the
 * rest of the game reads. Loading a talent whose effect is not on this list is a
 * {@code DataException}, not a silently inert node — that is the difference
 * between a typo caught at startup and a talent the player buys that does
 * nothing.
 *
 * <p>The names are the Python property names, unchanged, so a reader can put the
 * two side by side.
 */
public enum TalentEffect {

    // --- offence ------------------------------------------------------------
    /** {@code 1 - min(0.45, v)} — tower reload interval. */
    TOWER_RATE("tower_rate"),
    /** {@code 1 + v} — tower damage. */
    TOWER_DAMAGE("tower_damage"),
    /** {@code v} — chance a shot triples. */
    CRIT_CHANCE("crit_chance"),
    /** {@code int(v)} — extra bodies a bolt punches through. */
    EXTRA_PIERCE("extra_pierce"),
    /** {@code 1 + v} — explosive radius. */
    SPLASH_MULT("splash_mult"),
    /** {@code 1 - min(0.5, v)} — overcharge lockout. */
    OVERCHARGE_CD("overcharge_cd"),

    // --- defence ------------------------------------------------------------
    /**
     * {@code 1 + v} — and <b>nothing reads it</b>.
     *
     * <p>Deep Foundations. `TalentTree.castle_hp` exists in the Python source and
     * has no consumer anywhere, so buying the talent does not change castle
     * health. Reproduced deliberately; see {@code PORT_ANALYSIS.md} §13 and
     * {@code TalentQuirksTest}.
     */
    CASTLE_HP("castle_hp"),
    /** {@code v} — barricade health regrown per second. */
    BARRICADE_REGEN("barricade_regen"),
    /** {@code v} — spike bleed multiplier. */
    SPIKE_DOT("spike_dot"),
    /** {@code 1 + v} — emplacement health. */
    TOWER_HP("tower_hp"),
    /** {@code 1 - min(0.6, v)} — rebuild time. */
    REBUILD_MULT("rebuild_mult"),
    /** {@code 1 - min(0.4, v)} — damage the castle takes. */
    DAMAGE_TAKEN("damage_taken"),

    // --- utility ------------------------------------------------------------
    /** {@code 1 + v} — crowd gold step and cap. */
    GOLD_POP("gold_pop"),
    /** {@code 1 + v} — grab capacity. */
    GRAB_BONUS("grab_bonus"),
    /** {@code 1 - min(0.4, v)} — shop prices. */
    SHOP_DISCOUNT("shop_discount"),
    /** {@code v} — flat gold added to the Classic wave bonus. */
    WAVE_PURSE("wave_purse"),
    /** {@code max(0, 1 - v)} — what is left of the grab delay. */
    GRAB_CD_SCALE("grab_cd_scale"),
    /** {@code 1 + v} — fling score. */
    SCORE_MULT("score_mult"),
    /** {@code 1 + v} — gold per kill. */
    KILL_GOLD("kill_gold"),
    /** rank &gt; 0 — allies hunt rather than march. */
    ALLY_SENTINELS("ally_sentinels"),

    // --- aero ---------------------------------------------------------------
    /** {@code v} — enemy slow while a strong headwind blows. */
    STORM_WIND_SLOW("storm_wind_slow"),
    /** {@code 1 + v} — release velocity. */
    THROW_POWER("throw_power"),
    /** {@code 1 + v} — fall damage. */
    FALL_DAMAGE("fall_damage"),
    /** {@code 1 + v} — storm lightning AND the Lightning skill. */
    LIGHTNING_MULT("lightning_mult"),
    /** {@code 1 + v} — how hard wind pushes an airborne body. */
    WIND_MULT("wind_mult"),
    /** {@code 2 if rank else 1} — storm frequency. Not {@code 1 + v}. */
    STORM_CHANCE("storm_chance"),

    // --- necromancy ---------------------------------------------------------
    /** {@code 1 + v} — ally health and damage. */
    ALLY_POWER("ally_power"),
    /** {@code int(v)} — extra simultaneous allies. */
    ALLY_CAP_BONUS("ally_cap_bonus"),
    /** {@code 1 - min(0.5, v)} — the imprisoned Necromancer's raise interval. */
    ALLY_RATE("ally_rate"),
    /** {@code v} — slow on a mob fighting an ally. */
    GRAVE_CHILL("grave_chill"),
    /** {@code v} — extra seconds before an ally crumbles. */
    ALLY_LIFE("ally_life"),
    /** {@code 1 - min(0.5, v)} — damage an ally takes. */
    ALLY_TOUGH("ally_tough"),

    // --- arcane -------------------------------------------------------------
    /** {@code 1 - min(0.45, v)} — active skill cooldowns. */
    SKILL_CD("skill_cd"),
    /** {@code 1 + v} — active skill damage. */
    SKILL_POWER("skill_power"),
    /** {@code 1 + v} — active skill radius. */
    SKILL_AREA("skill_area"),
    /** {@code 1 + v} — how long meteor fire burns. */
    FIRE_TIME("fire_time"),
    /** {@code 1 + v} — tornado lifetime AND pull strength. */
    TORNADO_MULT("tornado_mult"),
    /** {@code 1 + v} — meteors dropped. */
    METEOR_COUNT("meteor_count");

    private final String id;

    TalentEffect(String id) {
        this.id = id;
    }

    /** The stable id used in {@code data/talents.json}. */
    public String id() {
        return id;
    }

    /** Looks up by stable id, or null. Callers turn null into a DataException. */
    public static TalentEffect byId(String id) {
        for (TalentEffect e : values()) {
            if (e.id.equals(id)) {
                return e;
            }
        }
        return null;
    }
}
