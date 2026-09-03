package com.mymmer.castledefense.enemy;

/**
 * One enemy's immutable stat block — the Python class constants, as data.
 *
 * <p>Numbers only, per {@code docs/PORT_ANALYSIS.md} §9. How a Necromancer picks
 * a stand-off point, when an Assassin dashes and what a Volatile does when it
 * dies are all Java; how much they weigh and how hard they hit are here.
 *
 * <p>{@link #width} and {@link #height} are the <b>gameplay</b> hitbox, and are
 * the only thing that decides projectile collision, grabbing, slam collision,
 * armour stripping and blocking. A skin may draw a mob at any size; it cannot
 * move these.
 */
public final class EnemyConfig {

    /**
     * The roster type, or <b>null for a boss</b>.
     *
     * <p>Bosses are not in the roster: they never appear in the unlock table,
     * are never picked by wave weight, and cannot be looked up by
     * {@code EnemyType.byId}. Use {@link #typeId} for anything that just needs a
     * stable name — a trace, a log line — and {@code Boss.bossType()} for a
     * boss's own identity.
     */
    public final EnemyType type;

    /**
     * A stable id that is always present: the roster id, or the boss id.
     *
     * <p>Exists so tracing and diagnostics never have to ask whether a unit is a
     * boss, and never dereference a null {@link #type}.
     */
    public final String typeId;
    /** Display name. Localisation key in Phase 10. */
    public final String name;
    /** Shop/codex blurb. Localisation key in Phase 10. */
    public final String description;

    public final float baseHp;
    public final float baseSpeed;
    public final float baseDamage;
    public final float attackRate;
    public final int gold;
    /** Fraction of projectile damage ignored, 0..1. */
    public final float armor;
    /** Heavier is harder to fling and lands worse. Also the grab-capacity gate. */
    public final float mass;

    /** Gameplay hitbox width. Python {@code W}. Never an artwork size. */
    public final float width;
    /** Gameplay hitbox height. Python {@code H}. */
    public final float height;
    /** Cruising altitude for a flyer, before its per-instance jitter. */
    public final float flyY;

    public final boolean flying;
    public final boolean grabbable;
    /** Can be imprisoned in the Outpost. */
    public final boolean trappable;
    /** A tank: cannons get bonus damage, and it cannot be lifted while plated. */
    public final boolean heavy;
    /** Its armour can be torn off by dragging on it. */
    public final boolean strippable;
    /** How many plates there are to tear off. */
    public final int armorLayers;

    EnemyConfig(EnemyType type, String name, String description,
                float baseHp, float baseSpeed, float baseDamage, float attackRate,
                int gold, float armor, float mass,
                float width, float height, float flyY,
                boolean flying, boolean grabbable, boolean trappable,
                boolean heavy, boolean strippable, int armorLayers) {
        this.type = type;
        this.typeId = type != null ? type.id() : "?";
        this.name = name;
        this.description = description;
        this.baseHp = baseHp;
        this.baseSpeed = baseSpeed;
        this.baseDamage = baseDamage;
        this.attackRate = attackRate;
        this.gold = gold;
        this.armor = armor;
        this.mass = mass;
        this.width = width;
        this.height = height;
        this.flyY = flyY;
        this.flying = flying;
        this.grabbable = grabbable;
        this.trappable = trappable;
        this.heavy = heavy;
        this.strippable = strippable;
        this.armorLayers = armorLayers;
    }

    /**
     * A private constructor for the boss variant, which has no roster type.
     *
     * <p>Package-private construction is deliberate — {@code EnemyTable} owns
     * enemy stat blocks — so bosses come in through {@link #forBoss}, which is
     * the one documented exception.
     */
    private EnemyConfig(String typeId, String name, String description,
                        float baseHp, float baseSpeed, float baseDamage, float attackRate,
                        int gold, float armor, float mass,
                        float width, float height, float flyY, boolean flying) {
        this.type = null;
        this.typeId = typeId;
        this.name = name;
        this.description = description;
        this.baseHp = baseHp;
        this.baseSpeed = baseSpeed;
        this.baseDamage = baseDamage;
        this.attackRate = attackRate;
        this.gold = gold;
        this.armor = armor;
        this.mass = mass;
        this.width = width;
        this.height = height;
        this.flyY = flyY;
        this.flying = flying;
        //  Every one of these is false for a boss, and hard-coded rather than
        //  read from data.  A boss that could be grabbed, trapped or stripped
        //  would break its own disruption mechanic outright; these are not
        //  numbers a designer should be able to flip by accident.
        this.grabbable = false;
        this.trappable = false;
        this.heavy = false;
        this.strippable = false;
        this.armorLayers = 0;
    }

    /**
     * The enemy-side view of a boss, so {@code Enemy}'s constructor works
     * unchanged.
     *
     * <p>The one way to build an {@code EnemyConfig} from outside this package,
     * and it cannot produce a grabbable or strippable unit.
     */
    public static EnemyConfig forBoss(String bossId, String name, String description,
                                      float baseHp, float baseSpeed, float baseDamage,
                                      float attackRate, int gold, float armor, float mass,
                                      float width, float height, float flyY,
                                      boolean flying) {
        if (bossId == null || bossId.isEmpty()) {
            throw new IllegalArgumentException("a boss config needs a stable id");
        }
        return new EnemyConfig(bossId, name, description, baseHp, baseSpeed, baseDamage,
                attackRate, gold, armor, mass, width, height, flyY, flying);
    }

    @Override
    public String toString() {
        return "EnemyConfig[" + typeId + " hp=" + baseHp + " spd=" + baseSpeed
                + " mass=" + mass + "]";
    }
}
