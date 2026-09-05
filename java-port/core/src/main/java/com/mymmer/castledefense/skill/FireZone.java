package com.mymmer.castledefense.skill;

import com.mymmer.castledefense.enemy.Enemy;
import com.mymmer.castledefense.entity.EntityList;

/**
 * Burning ground left by a meteor — {@code main.py:545 FireZone}.
 *
 * <p>A column, not a circle: it tests {@code |e.x - x| <= radius} and ignores y
 * entirely, so it burns everything on the ground in a band. Flyers are exempt,
 * which is the only eligibility rule it has.
 *
 * <h2>It burns continuously, not in ticks</h2>
 *
 * <p>{@code take_damage(dps * dt)} every step — there is no tick cadence to get
 * wrong, and inventing one would change the damage a mob takes while walking
 * through. The lifetime is a {@code double}; the damage it deals per step is
 * {@code dps * (float) dt}, which is a quantity rather than a time.
 */
public final class FireZone {

    private final SkillContext ctx;
    private final float x;
    private final float radius;
    private final float dps;

    private final double maxLife;
    private double life;

    /** Visual phase, advanced at 9/s. Nothing gameplay reads it. */
    private float phase;

    public FireZone(SkillContext ctx, float x, double life, float dps, float radius) {
        this.ctx = ctx;
        this.x = x;
        this.life = life;
        this.maxLife = life;
        this.dps = dps;
        this.radius = radius;
        this.phase = ctx.rng().uniform(0f, 6.28f);
    }

    public boolean alive() {
        return life > 0d;
    }

    public float x() {
        return x;
    }

    public float radius() {
        return radius;
    }

    public float dps() {
        return dps;
    }

    /** Seconds left. Time domain. */
    public double life() {
        return life;
    }

    public double maxLife() {
        return maxLife;
    }

    /** Visual only, for Phase 11. */
    public float phase() {
        return phase;
    }

    /** How far through its life it is, 1 at birth and 0 at the end. */
    public float fraction() {
        return maxLife <= 0d ? 0f : (float) Math.max(0d, Math.min(1d, life / maxLife));
    }

    /**
     * One step of burning.
     *
     * <p>Iterates a <b>snapshot</b>: the burn can finish a mob off, and if that
     * mob is a boss its death purges entries from the horde on the spot. Python
     * walks {@code list(self.game.enemies)} for the same reason.
     */
    public void update(double dt) {
        life -= dt;
        phase += (float) dt * 9f;

        float tick = dps * (float) dt;
        EntityList<Enemy> horde = ctx.horde();
        try (EntityList<Enemy>.Snapshot snap = horde.beginSnapshot()) {
            for (int i = 0; i < snap.size(); i++) {
                Enemy e = snap.get(i);
                if (e.isAlive() && !e.flying() && Math.abs(e.x() - x) <= radius) {
                    e.takeDamage(tick, "fire");
                }
            }
        }
    }

    @Override
    public String toString() {
        return "FireZone[x=" + (int) x + " r=" + (int) radius
                + " dps=" + (int) dps + " life=" + String.format("%.2f", life) + "]";
    }
}
