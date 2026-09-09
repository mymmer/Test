package com.mymmer.castledefense.skill;

import com.mymmer.castledefense.debug.SimulationTrace;
import com.mymmer.castledefense.defence.CombatModifiers;
import com.mymmer.castledefense.defence.Projectile;
import com.mymmer.castledefense.enemy.Enemy;
import com.mymmer.castledefense.entity.EntityList;
import com.mymmer.castledefense.progress.ScreenShake;
import com.mymmer.castledefense.util.Rng;

/**
 * What an active skill needs from the world.
 *
 * <p>Virtual world coordinates only. Nothing here is a pixel, an icon, a
 * rectangle or a touch — Phase 10 turns a tap into
 * {@code select(id)} then {@code castAt(x, y)}, and those are the only entry
 * points. The skills name no {@code TalentTree}: their multipliers arrive
 * through {@link CombatModifiers} like every other talent effect.
 */
public interface SkillContext {

    /**
     * The horde.
     *
     * <p>Handed out whole rather than through an index accessor because every
     * area effect here has to iterate a <b>snapshot</b>: a blast can kill a
     * boss, and a boss's death purges entries from this list on the spot. See
     * {@code SKILLS.md}.
     */
    EntityList<Enemy> horde();

    void addProjectile(Projectile projectile);

    /**
     * The context a {@link Projectile} is constructed against.
     *
     * <p>A meteor is an ordinary projectile and needs the ordinary defence
     * context to fly, hit and splash. Handing it over here keeps
     * {@code SkillContext} a superset rather than making the skills reach for a
     * world.
     */
    com.mymmer.castledefense.defence.DefenceContext projectileContext();

    /** Adds burning ground. Owned and stepped by the world. */
    void addFireZone(FireZone zone);

    /** Adds a funnel. Owned and stepped by the world. */
    void addTornado(Tornado tornado);

    /** Ground level, and the arena's left edge, come from GameConfig; this is state. */
    CombatModifiers modifiers();

    ScreenShake shake();

    Rng rng();

    SimulationTrace trace();

    /**
     * The presentation sink. Defaulted, so no existing implementor changes.
     *
     * <p>Same one-way seam every other subsystem emits through: a skill decides
     * its damage and its targets, and this only says what to draw.
     */
    default com.mymmer.castledefense.render.VisualEvents visuals() {
        return com.mymmer.castledefense.render.VisualEvents.NONE;
    }

    /** Lights the storm white-out. {@code main.py:716}. */
    default void stormFlash() {
    }

    long step();

    /** Counts a cast, for the run's statistics. */
    void onSkillCast(SkillId id);
}
