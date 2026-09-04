package com.mymmer.castledefense.enemy;

import com.mymmer.castledefense.defence.Barricade;
import com.mymmer.castledefense.defence.Target;

/**
 * Detonates violently when killed. Mind the blast.
 *
 * <h2>The death ordering is what makes chains work</h2>
 *
 * <pre>
 *   Volatile.die(silent)
 *     → super.die(silent)     marks it dead and pays out FIRST
 *     → detonate()            then the blast goes off
 * </pre>
 *
 * <p>Because it is already dead when the blast lands, the blast cannot damage it
 * again, and any other Volatile caught in the radius dies and detonates from
 * inside this call — a real recursive chain, with each link paying out its own
 * gold at the crowd multiplier as it stood at that moment.
 *
 * <p><b>Nothing guards against that recursion, deliberately.</b> Python does not,
 * and a depth limit or an "already exploding" flag would cap chains that the
 * game is designed to reward. The recursion terminates because each blast marks
 * its own mob dead before recursing, and a dead mob is skipped.
 */
public final class Volatile extends Enemy {

    /** Blast radius, in world units. */
    public static final float BLAST_RADIUS = 132f;
    /** Blast damage as a multiple of its own contact damage. */
    public static final float BLAST_DAMAGE = 3.4f;

    /** Visual only: the sputtering fuse phase. */
    private float fuse;

    public Volatile(EnemyContext ctx, EnemyConfig config, int wave, Float x, Float y) {
        super(ctx, config, wave, x, y);
        this.fuse = ctx.rng().uniform(0f, 6.28f);
    }

    /** Visual only. */
    public float fuse() {
        return fuse;
    }

    @Override
    public void die(boolean silent) {
        boolean wasAlive = isAlive();
        super.die(silent);
        if (wasAlive) {
            detonate();
        }
    }

    /**
     * The blast: everything within {@link #BLAST_RADIUS}, at linear falloff to
     * half damage at the rim, plus the wall and the barricade.
     *
     * <p>Walks the target list in insertion order and checks each entry live —
     * entries killed earlier in this same loop are skipped, which is what stops
     * a chain re-hitting its own links.
     */
    public void detonate() {
        float dmg = damage() * BLAST_DAMAGE;

        int n = ctx.targetCount();
        for (int i = 0; i < n; i++) {
            Target o = ctx.target(i);
            if (o == this || !o.alive()) {
                continue;
            }
            float dx = o.x() - x;
            float dy = o.y() - y;
            float d = (float) Math.sqrt(dx * dx + dy * dy);
            if (d <= BLAST_RADIUS) {
                o.takeDamage(dmg * (1f - 0.5f * d / BLAST_RADIUS), "explosive");
            }
        }

        //  it will happily take the wall with it -- but only from outside:
        //  the gap must be non-negative, so a blast behind the wall does nothing
        float gap = x - ctx.castle().frontX();
        if (gap >= 0f && gap <= BLAST_RADIUS) {
            ctx.castle().takeDamage(dmg * (1f - 0.5f * gap / BLAST_RADIUS));
        }
        Barricade bar = ctx.barricade();
        if (bar != null && bar.alive() && Math.abs(x - bar.x()) <= BLAST_RADIUS) {
            bar.takeDamage(dmg);        // no falloff on the barricade, in Python
        }
    }

    @Override
    protected void think(double dt) {
        fuse += (float) dt * 7f;
        super.think(dt);
    }
}
