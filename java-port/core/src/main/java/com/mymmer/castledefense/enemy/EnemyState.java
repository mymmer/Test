package com.mymmer.castledefense.enemy;

/**
 * The states an enemy can be in. Python {@code Enemy.state}, which is a string.
 *
 * <p>An enum, not a behaviour tree and not a component: the Python base is a
 * small explicit state machine and stays one. Every transition in the port is a
 * direct assignment at the same place Python makes it, so a transition can be
 * traced to one line of source.
 *
 * <p>Only states that exist in the source appear here. {@link #HOLD} is the
 * ally's; {@link #RETRIEVE} is the bosses' and is declared now only because the
 * Python base's docstring names it — nothing in Phase 6 enters it.
 */
public enum EnemyState {

    /** Marching toward the castle, or to a stand-off point. */
    WALK("walk"),

    /** In contact with the castle, the barricade or an ally, swinging on a timer. */
    ATTACK("attack"),

    /** Held by the player's cursor. Physics and thinking are suspended. */
    GRABBED("grabbed"),

    /** Flying after a throw: gravity, wind, slams and fall damage apply. */
    AIR("air"),

    /** Imprisoned in the Outpost. Python sets this on the trapped Necromancer. */
    TRAPPED("trapped"),

    /** An ally holding its line. Only {@code FriendlySkeleton} uses it. */
    HOLD("hold"),

    /** A boss walking back to recover its stolen regalia. Phase 7. */
    RETRIEVE("retrieve");

    private final String id;

    EnemyState(String id) {
        this.id = id;
    }

    /** The Python string, for traces and data. Never the ordinal. */
    public String id() {
        return id;
    }

    /**
     * True in the two states the player may interact with a unit in.
     *
     * <p>Python spells this {@code state in ("walk", "attack")} in five separate
     * places — grabbable, shovable, strippable, slam knock-on and crowd
     * separation. One method so they cannot drift apart.
     */
    public boolean isOnFoot() {
        return this == WALK || this == ATTACK;
    }

    public static EnemyState byId(String id, EnemyState fallback) {
        if (id != null) {
            for (EnemyState s : values()) {
                if (s.id.equals(id)) {
                    return s;
                }
            }
        }
        return fallback;
    }
}
