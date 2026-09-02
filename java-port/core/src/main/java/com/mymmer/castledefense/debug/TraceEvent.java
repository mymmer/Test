package com.mymmer.castledefense.debug;

/**
 * The things a simulation trace can record.
 *
 * <p>Observation only. Nothing in the game may branch on a trace event or use
 * one to communicate: if a projectile needs to damage an enemy, it calls the
 * enemy. Tracing exists so a Java run and a Python run can be compared, and so a
 * player's bug report can be reproduced from a seed and a step number.
 */
public enum TraceEvent {

    SIMULATION_STEP,
    STATE_TRANSITION,
    ENTITY_SPAWN,
    ENTITY_DEATH,
    DAMAGE,
    GOLD_CHANGE,
    SCORE_CHANGE,

    // --- defences (Phase 5) -------------------------------------------------
    PROJECTILE_SPAWN,
    PROJECTILE_IMPACT,
    TOWER_FIRE,
    TOWER_DISABLED,
    TOWER_REBUILT,
    CASTLE_DAMAGE,
    BARRICADE_DAMAGE,

    // --- enemies and interaction physics (Phase 6) --------------------------
    ENTITY_STATE_CHANGED,
    ENEMY_GRABBED,
    ENEMY_RELEASED,
    ARMOUR_STRIPPED,
    SLAM,
    FALL_DAMAGE,
    GOLD_PAYOUT
}
