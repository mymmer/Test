package com.mymmer.castledefense.skill;

import com.badlogic.gdx.utils.LongArray;
import com.mymmer.castledefense.config.Tuning;
import com.mymmer.castledefense.debug.TraceEvent;
import com.mymmer.castledefense.enemy.Enemy;
import com.mymmer.castledefense.enemy.EnemyState;
import com.mymmer.castledefense.entity.EntityList;
import com.mymmer.castledefense.util.Collisions;

/**
 * A funnel that lifts mobs, carries them, and hurls them downfield —
 * {@code main.py:584 Tornado}.
 *
 * <p>Not a knockback. It is a sustained physics effect with four distinct
 * pieces, and flattening any of them into "push things away" would be a
 * different mechanic:
 *
 * <ol>
 *   <li><b>It drifts.</b> {@code x += TORNADO_SPEED * dt}, away from the castle,
 *       for its whole life.</li>
 *   <li><b>It catches.</b> A mob on foot inside the radius is put into
 *       {@code AIR} through {@code onRelease(0, 0)} — which counts as a player
 *       fling, so the throw is scored — and then has its fling hit-count reset
 *       to zero.</li>
 *   <li><b>It carries.</b> While a caught mob is airborne it is pulled toward
 *       the axis and lifted, with {@code tornadoHold} renewed to 0.12 s every
 *       step so gravity is mostly cancelled. It really is carried, not merely
 *       slowed.</li>
 *   <li><b>It throws.</b> When the life runs out, everything it caught is hurled
 *       downfield at a randomised speed.</li>
 * </ol>
 *
 * <h2>The catch list is what it caught, not what is near it</h2>
 *
 * <p>{@code burst()} keys off the uids it recorded, because the funnel drifts
 * away from where it picked things up: a heavy mob lags behind and would fall
 * outside the radius by the end. It <em>also</em> throws anything airborne within
 * {@code radius * 1.4}, so a mob caught by a neighbouring effect is not stranded.
 * Both halves are the source's.
 *
 * <h2>What it will not touch</h2>
 *
 * <p>Bosses, ungrabbable units and armoured ones are skipped outright — the same
 * gate as the cursor, so a Siege Ram with its plates on rides the funnel out.
 */
public final class Tornado {

    private final SkillContext ctx;
    private final double maxLife;
    private final float radius;
    private final double power;

    private float x;
    private double life;
    private float phase;

    /** The uids this funnel actually lifted. Grows a handful of times, never per step. */
    private final LongArray caught = new LongArray(false, 16);

    private int thrown;

    public Tornado(SkillContext ctx, float x, double life, float radius, double power) {
        this.ctx = ctx;
        this.x = x;
        this.life = life;
        this.maxLife = life;
        this.radius = radius;
        this.power = power;
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

    /** Seconds left. Time domain. */
    public double life() {
        return life;
    }

    public double maxLife() {
        return maxLife;
    }

    /** Visual only. */
    public float phase() {
        return phase;
    }

    /** How many it hurled when it burst. Zero until then. */
    public int thrownCount() {
        return thrown;
    }

    public int caughtCount() {
        return caught.size;
    }

    /**
     * One step of the funnel.
     *
     * <p>A <b>snapshot</b>, for the usual reason: lifting a mob can put it into
     * the air, and anything that dies mid-loop — a Volatile detonating on
     * contact, a boss finished off by a fall — can change the roster.
     */
    public void update(double dt) {
        float fdt = (float) dt;
        life -= dt;
        phase += fdt * 7f;
        x += Tuning.TORNADO_SPEED * fdt;        // drifts away from the castle

        EntityList<Enemy> horde = ctx.horde();
        try (EntityList<Enemy>.Snapshot snap = horde.beginSnapshot()) {
            for (int i = 0; i < snap.size(); i++) {
                Enemy e = snap.get(i);
                if (!e.isAlive() || e.isBoss() || !e.grabbable() || e.armored()) {
                    continue;
                }
                float d = Math.abs(e.x() - x);
                if (d > radius) {
                    continue;
                }
                if (e.state().isOnFoot()) {
                    //  Counts as a player fling, so the throw scores.  Python
                    //  then clears fling_hits explicitly; onRelease already does
                    //  that in both languages, so there is nothing to repeat.
                    e.onRelease(0f, 0f);
                    caught.add(e.uid());
                }
                if (e.state() == EnemyState.AIR) {
                    float pull = (float) ((1d - d / radius) * power);
                    e.holdInTornado(0.12);
                    float vx = e.vx()
                            + ((x - e.x()) * Tuning.TORNADO_SWIRL - e.vx()) * pull * fdt;
                    float vy = e.vy()
                            + (-Tuning.TORNADO_LIFT * pull - e.vy()) * 2.4f * fdt;
                    e.setVelocity(vx, Collisions.clamp(vy, -900f, 220f));
                    e.addSpin(fdt * 16f);
                }
            }
        }

        if (life <= 0d) {
            burst();
        }
    }

    /**
     * Time's up: everything it picked up is hurled downfield.
     *
     * <p>Only airborne mobs are thrown, and only ones it caught or that are still
     * close to the funnel. The speeds come from the gameplay stream, so a seeded
     * run hurls them identically.
     */
    private void burst() {
        ctx.shake().add(9f);
        thrown = 0;
        EntityList<Enemy> horde = ctx.horde();
        try (EntityList<Enemy>.Snapshot snap = horde.beginSnapshot()) {
            for (int i = 0; i < snap.size(); i++) {
                Enemy e = snap.get(i);
                if (!e.isAlive() || e.state() != EnemyState.AIR) {
                    continue;
                }
                if (!caught.contains(e.uid()) && Math.abs(e.x() - x) > radius * 1.4f) {
                    continue;
                }
                e.holdInTornado(0d);
                e.setVelocity(Math.abs(e.vx()) + ctx.rng().uniform(760f, 1180f),
                        -ctx.rng().uniform(520f, 820f));
                thrown++;
            }
        }
        if (ctx.trace().isEnabled()) {
            ctx.trace().event(TraceEvent.SKILL_EFFECT_CREATED, ctx.step(), 0L,
                    x, thrown, "tornado-burst");
        }
    }

    @Override
    public String toString() {
        return "Tornado[x=" + (int) x + " r=" + (int) radius
                + " life=" + String.format("%.2f", life) + " caught=" + caught.size + "]";
    }
}
