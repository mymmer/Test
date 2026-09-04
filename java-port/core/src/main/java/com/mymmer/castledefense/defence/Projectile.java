package com.mymmer.castledefense.defence;

import com.badlogic.gdx.utils.LongArray;
import com.mymmer.castledefense.config.GameConfig;
import com.mymmer.castledefense.debug.TraceEvent;
import com.mymmer.castledefense.entity.Entity;
import com.mymmer.castledefense.util.Collisions;

/**
 * One class covers every flying thing, exactly as the Python one does.
 *
 * <p>{@link ProjectileKind} drives a couple of behavioural switches and
 * {@link #hostile} flips who it is allowed to hurt. Ported before being
 * optimised: the update order, the hit-test order, the splash iteration and the
 * hostile obstacle priority are all reproduced move for move, because each of
 * them is observable.
 *
 * <h2>Coordinates</h2>
 *
 * <p>Python/pygame space: y increases <b>downward</b>, gravity is positive, and
 * {@code GROUND_Y = 620} is below {@code WALL_TOP = 350}. Every constant in
 * {@link GameConfig} is in that space, so the simulation stays in it and the
 * single flip to libGDX y-up happens in the renderer. Flipping here would mean
 * inverting every comparison in this file, which is exactly how parity bugs get
 * written.
 *
 * <h2>What is deliberately missing</h2>
 *
 * <p>The Python class keeps an 8-sample position {@code trail} and a colour.
 * Both are drawing state and nothing reads them for gameplay, so they arrive in
 * Phase 11 with the renderer rather than being carried here unused.
 */
public final class Projectile extends Entity {

    private final DefenceContext ctx;

    // --- motion -------------------------------------------------------------
    private float x;
    private float y;
    private float vx;
    private float vy;
    private final float grav;

    // --- payload ------------------------------------------------------------
    private final ProjectileKind kind;
    private float damage;
    private float splash;
    private int pierce;
    private final double stun;
    private final boolean hostile;
    private final boolean atPrisoner;
    private final long ownerUid;
    private final float bonusAir;
    private final float bonusHeavy;
    private final float critChance;
    private double life;

    /**
     * Who this shot has already hit, by uid.
     *
     * <p>A {@code LongArray} scanned linearly, not a hash set: pierce caps out
     * around six, so the scan is shorter than a hash and it allocates nothing per
     * lookup. Python uses a set for the same job.
     */
    private final LongArray hitIds = new LongArray(8);

    /** Set when a splash shot detonates, so the caller can see it resolved. */
    private boolean exploded;

    /**
     * Full constructor, mirroring the Python signature.
     *
     * <p>Note {@code splash} is multiplied by the talent splash bonus <b>here</b>,
     * at construction, exactly as Python does in {@code __init__} — not at impact.
     * The crit chance is likewise sampled from the talents once and travels with
     * the shot, so a talent bought mid-flight does not affect a shot already in
     * the air.
     */
    public Projectile(DefenceContext ctx, float x, float y, float vx, float vy,
                      ProjectileKind kind, float damage,
                      float splash, int pierce, float grav, boolean hostile,
                      double life, double stun,
                      float bonusAir, float bonusHeavy,
                      boolean atPrisoner, long ownerUid) {
        if (ctx == null) {
            throw new IllegalArgumentException("ctx must not be null");
        }
        this.ctx = ctx;
        this.x = x;
        this.y = y;
        this.vx = vx;
        this.vy = vy;
        this.kind = kind != null ? kind : ProjectileKind.ARROW;
        this.damage = damage;
        this.pierce = pierce;
        this.grav = grav;
        this.hostile = hostile;
        this.atPrisoner = atPrisoner;
        this.ownerUid = ownerUid;
        this.bonusAir = bonusAir;
        this.bonusHeavy = bonusHeavy;
        CombatModifiers mods = ctx.modifiers();
        this.critChance = mods.critChance();
        this.splash = splash != 0f ? splash * mods.splashMult() : splash;
        this.life = life;
        this.stun = stun;
        ctx.trace().event(TraceEvent.PROJECTILE_SPAWN, ctx.step(), uid(),
                x, y, this.kind.id());
    }

    /** The common friendly shot: no splash, no gravity, default life. */
    public static Projectile friendly(DefenceContext ctx, float x, float y,
                                      float vx, float vy, ProjectileKind kind,
                                      float damage) {
        return new Projectile(ctx, x, y, vx, vy, kind, damage,
                0f, 0, 0f, false, 5f, 0f, 1f, 1f, false, 0L);
    }

    // --- accessors ----------------------------------------------------------

    public float x() {
        return x;
    }

    public float y() {
        return y;
    }

    public float vx() {
        return vx;
    }

    public float vy() {
        return vy;
    }

    public ProjectileKind kind() {
        return kind;
    }

    /** Current damage. Falls by 28% after each pierce, as in Python. */
    public float damage() {
        return damage;
    }

    public float splash() {
        return splash;
    }

    public int pierce() {
        return pierce;
    }

    public double stun() {
        return stun;
    }

    public double life() {
        return life;
    }

    public boolean hostile() {
        return hostile;
    }

    public boolean atPrisoner() {
        return atPrisoner;
    }

    /** Who fired it, so a dead boss's shots can be dropped with the boss. */
    public long ownerUid() {
        return ownerUid;
    }

    public float bonusAir() {
        return bonusAir;
    }

    public float bonusHeavy() {
        return bonusHeavy;
    }

    public boolean hasExploded() {
        return exploded;
    }

    /** Whether this shot has already hit that unit. Pierce bookkeeping. */
    public boolean hasHit(long targetUid) {
        return hitIds.contains(targetUid);
    }

    /** Radians, for the renderer's benefit later. Python {@code angle}. */
    public float angle() {
        return (float) Math.atan2(vy, vx);
    }

    // --- damage -------------------------------------------------------------

    /**
     * This shot's damage against one specific victim.
     *
     * <p>Counters are resolved per target at the moment of impact, which is what
     * lets a single cannon shell hit a Siege Ram for triple while only tickling
     * the scouts caught in the same blast.
     */
    public float damageFor(Target e) {
        float d = damage;
        if (e.flying()) {
            d *= bonusAir;
        }
        if (e.heavy()) {
            d *= bonusHeavy;
        }
        if (critChance > 0f && ctx.rng().game().nextFloat() < critChance) {
            d *= 3f;
        }
        return d;
    }

    // --- lifecycle ----------------------------------------------------------

    /**
     * Detonates, or fizzles if this shot has no splash.
     *
     * <p>The splash loop walks the target list <b>in insertion order</b> and
     * checks each entry live. No precomputation, no spatial partitioning, no
     * reordering: which units are caught and in what order is observable, and
     * changing it is a Phase 12 optimisation that has to be proven first.
     */
    public void explode() {
        exploded = true;
        if (splash > 0f) {
            ctx.trace().event(TraceEvent.PROJECTILE_IMPACT, ctx.step(), uid(),
                    x, y, "splash");
            if (hostile) {
                ctx.castle().splashHit(x, y, splash, damage, stun);
                Barricade bar = ctx.barricade();
                if (bar != null && bar.alive()) {
                    float gap = Math.abs(x - bar.x());
                    if (gap <= splash) {
                        bar.takeDamage(damage * (1f - 0.5f * gap / Math.max(1f, splash)));
                    }
                }
            } else {
                int n = ctx.targetCount();
                for (int i = 0; i < n; i++) {
                    Target e = ctx.target(i);
                    if (!e.alive()) {
                        continue;
                    }
                    float d = Collisions.distance(e.x(), e.y(), x, y);
                    if (d <= splash) {
                        float falloff = 1f - 0.55f * (d / Math.max(1f, splash));
                        e.takeDamage(damageFor(e) * falloff, "explosive");
                    }
                }
            }
        } else {
            ctx.trace().event(TraceEvent.PROJECTILE_IMPACT, ctx.step(), uid(),
                    x, y, "fizzle");
        }
        markDead();
    }

    /**
     * One simulation step.
     *
     * <p>The order is Python's, and every line of it is observable: wind before
     * gravity, integrate, age, then the bounds test, then the ground test, then
     * the collision resolution.
     */
    public void update(double dt) {
        float fdt = (float) dt;                 // the flight is spatial
        vx += ctx.wind() * GameConfig.WIND_PROJECTILE * fdt;
        vy += grav * fdt;
        x += vx * fdt;
        y += vy * fdt;
        life -= dt;

        if (life <= 0d || x < -120f || x > GameConfig.WORLD_WIDTH + 220f
                || y > GameConfig.WORLD_HEIGHT + 200f || y < -600f) {
            //  A splash shot that simply ran out of time still detonates; one
            //  that flew off the map does not.
            if (splash > 0f && life <= 0d) {
                explode();
            } else {
                markDead();
            }
            return;
        }

        if (kind.explodesOnGround() && y >= GameConfig.GROUND_Y - 2f) {
            y = GameConfig.GROUND_Y - 2f;
            explode();
            return;
        }

        if (hostile) {
            updateHostile();
        } else {
            updateFriendly();
        }
    }

    /**
     * Friendly collision, in the Python hot-path order.
     *
     * <p>Cheap primitive bounds first, then the alive / already-hit / targetable
     * checks, then resolution — and at most one target per step. Nothing is
     * allocated per candidate: no rectangle, no vector, no iterator. The Python
     * comment records that building a {@code pygame.Rect} here "was the single
     * biggest cost in the profile at high waves"; the same call runs once per
     * projectile per target per step in Java.
     */
    private void updateFriendly() {
        final float px = x;
        final float py = y;
        int n = ctx.targetCount();
        for (int i = 0; i < n; i++) {
            Target e = ctx.target(i);
            if (Math.abs(px - e.x()) > e.width() * 0.5f
                    || Math.abs(py - e.y()) > e.height() * 0.5f) {
                continue;
            }
            if (!e.alive() || hitIds.contains(e.uid()) || !e.targetable()) {
                continue;
            }
            hitIds.add(e.uid());
            if (splash > 0f) {
                explode();
                return;
            }
            float dealt = damageFor(e);
            e.takeDamage(dealt, "projectile");
            ctx.trace().event(TraceEvent.DAMAGE, ctx.step(), e.uid(),
                    dealt, 0f, "projectile");
            if (pierce > 0) {
                pierce--;
                damage *= 0.72f;
            } else {
                markDead();
            }
            return;
        }
    }

    /**
     * Hostile collision. <b>The obstacle order below is gameplay, not layout.</b>
     *
     * <pre>
     *   a rival's shot at the cage  (ignores everything else)
     *   otherwise:  Barricade → Tower → Castle wall/front → Keep
     * </pre>
     *
     * <p>A shell can geometrically overlap several of these at once, and which
     * one absorbs it decides whether the wall or the barricade takes the damage.
     * Reordering it because another order reads more cleanly changes the game.
     */
    private void updateHostile() {
        if (atPrisoner) {
            Outpost post = ctx.outpost();
            if (post == null || !post.hasPrisoner()) {
                markDead();
                return;
            }
            if (post.bodyContains(x, y)) {
                post.hurtPrisoner(damage);
                markDead();
            }
            return;
        }

        Castle castle = ctx.castle();
        Barricade bar = ctx.barricade();

        // 1. the outer barricade is the first thing in the way
        if (bar != null && bar.alive() && y >= bar.topY()
                && Math.abs(x - bar.x()) <= Barricade.WIDTH / 2f + kind.radius()) {
            if (splash > 0f) {
                explode();
            } else {
                bar.takeDamage(damage);
                markDead();
            }
            return;
        }

        if (castle == null) {
            return;
        }

        // 2. a tower on the wall
        DefenceTower tower = castle.towerAt(x, y);
        if (tower != null) {
            if (splash > 0f) {
                explode();
            } else {
                tower.takeDamage(damage);
                markDead();
            }
            return;
        }

        // 3. the curtain wall
        if (x <= castle.frontX() && y >= GameConfig.WALL_TOP - 30f) {
            if (splash > 0f) {
                explode();
            } else {
                castle.takeDamage(damage);
                markDead();
            }
            return;
        }

        // 4. the keep behind it
        if (x <= castle.keepRight() && y >= GameConfig.KEEP_TOP - 26f) {
            if (splash > 0f) {
                explode();
            } else {
                castle.takeDamage(damage);
                markDead();
            }
        }
    }

    @Override
    public String toString() {
        return "Projectile[" + kind.id() + " uid=" + uid()
                + (hostile ? " hostile" : " friendly")
                + " dmg=" + damage + " splash=" + splash + " pierce=" + pierce + "]";
    }
}
