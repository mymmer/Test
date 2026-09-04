package com.mymmer.castledefense.defence;

import com.mymmer.castledefense.config.GameConfig;
import com.mymmer.castledefense.util.Collisions;

/**
 * A stone tower back in the scenery, and the cage the Necromancer ends up in.
 *
 * <p>Enemies march straight past it and can never attack it — it has no health
 * at all, which the Python self-test asserts explicitly — so whatever is
 * garrisoned here keeps firing all round. Two mechanics live on it:
 *
 * <ul>
 *   <li><b>The garrison.</b> Levels add crew up to a cap; levels past the cap
 *       stop adding bodies and multiply the damage of everyone already there
 *       ({@link #overdrive()}). At level 4 the bows become turrets: harder
 *       hitting, faster, and firing bolts instead of arrows.</li>
 *   <li><b>The betrayal.</b> A Necromancer thrown in here is imprisoned, and his
 *       magic runs backwards: he raises skeletons <em>for</em> the castle. Rival
 *       Necromancers then shoot at the cage to kill him.</li>
 * </ul>
 *
 * <h2>The ally seam</h2>
 *
 * <p>The Outpost decides <em>when</em> a skeleton should be raised — the timer,
 * the cap, the prisoner's state are all defence-side gameplay — and hands the
 * actual creation to {@link AllyFactory}. It never learns what an ally is. That
 * is what keeps this package free of Phase 6 types while still owning the
 * mechanic in full.
 */
public final class Outpost {

    /** Damage multiplier gained per level bought past the crew cap. */
    public static final float OVERDRIVE_PER_LEVEL = 0.35f;

    private final DefenceContext ctx;
    private final float x = GameConfig.OUTPOST_X;
    private final float y = GameConfig.OUTPOST_BASE_Y;

    private int level;
    /** One reload timer per crew member, in crew order. */
    //  Crew reload timers, one per gun.  A plain double[] rather than a
    //  libGDX FloatArray: these are time-domain values and libGDX ships no
    //  DoubleArray.  The crew is capped, so it grows a handful of times per run
    //  and never inside the step loop.
    private double[] cooldowns = new double[8];
    private int crew;

    /** Visual only: upgrade flash, decays at 2/s. */
    private float flash;

    // --- the betrayal -------------------------------------------------------
    private Trappable prisoner;
    private float prisonerHp;
    private float prisonerMax = GameConfig.PRISONER_HP;
    /** Counts down after a hit; regeneration is locked out while it is above zero. */
    private double prisonerHit;
    private double skeletonTimer;
    /** Visual only. */
    private float trapGlow;

    public Outpost(DefenceContext ctx) {
        if (ctx == null) {
            throw new IllegalArgumentException("ctx must not be null");
        }
        this.ctx = ctx;
    }

    // --- geometry -----------------------------------------------------------

    public float x() {
        return x;
    }

    public float y() {
        return y;
    }

    /**
     * Does a point land on the outpost body?
     *
     * <p>Python {@code body_rect} is {@code Rect(int(x)-42, int(y)-62, 84, 70)},
     * so this is pygame's truncated, half-open containment — the test a rival's
     * bolt makes against the cage.
     */
    public boolean bodyContains(float px, float py) {
        return Collisions.pointInPygameRect(px, py,
                (int) x - 42, (int) y - 62, 84, 70);
    }

    /**
     * Where a rival Necromancer aims, and where a throw must land to imprison.
     *
     * <p>Deliberately more generous than the body: landing a throw in here is the
     * whole trick, so it should not be pixel-perfect. Python inflates the body
     * rect by 26 on each axis (13 per side).
     */
    public boolean trapAreaContains(float px, float py) {
        return Collisions.pointInPygameRect(px, py,
                (int) x - 42 - 13, (int) y - 62 - 13, 84 + 26, 70 + 26);
    }

    /** The point a rival's bolt is fired at. */
    public float aimPointX() {
        return x;
    }

    /** The point a rival's bolt is fired at. */
    public float aimPointY() {
        return y - 26f;
    }

    // --- the garrison -------------------------------------------------------

    public int level() {
        return level;
    }

    /** Crew actually stationed: capped, however many levels were bought. */
    public int guns() {
        return Math.min(level, GameConfig.OUTPOST_MAX_LEVEL);
    }

    /** Damage multiplier from levels bought past the crew cap. */
    public float overdrive() {
        return 1f + OVERDRIVE_PER_LEVEL
                * Math.max(0, level - GameConfig.OUTPOST_MAX_LEVEL);
    }

    /** From level 4 the bows are replaced by turrets. */
    public boolean isTurret() {
        return level >= GameConfig.OUTPOST_TURRET_FROM;
    }

    public float gunDamage() {
        float base = isTurret() ? 15f : 7.5f;
        return base * overdrive() * ctx.modifiers().towerDamage();
    }

    public double gunReload() {
        return isTurret() ? 0.62 : 0.92;
    }

    /** How many reload timers exist. Never grows past the crew cap. */
    public int crewCount() {
        return crew;
    }

    /**
     * Buys a level. Always succeeds.
     *
     * <p>New crew arrive up to the cap; after that the levels pour into raw
     * firepower instead — no extra bodies, no extra gun slots.
     */
    public boolean upgrade() {
        level++;
        if (crew < guns()) {
            if (crew == cooldowns.length) {
                double[] grown = new double[crew * 2];
                System.arraycopy(cooldowns, 0, grown, 0, crew);
                cooldowns = grown;
            }
            cooldowns[crew++] = ctx.rng().uniformSeconds(0.0, 0.5);
        }
        flash = 1f;
        return true;
    }

    /** Where crew member {@code i} stands. */
    public float gunX(int i) {
        return x - 26f + (i % 3) * 26f;
    }

    /** Where crew member {@code i} stands. */
    public float gunY(int i) {
        return y - 60f - (i / 3) * 26f;
    }

    /**
     * Picks a target: whatever is furthest along, inside {@code OUTPOST_RANGE}.
     *
     * <p>Note this is a plain straight-line distance from the outpost base —
     * there is no air-range stretching here — and the comparison is strictly
     * "smaller x", so ties go to the earlier entry in the list.
     */
    public Target pickTarget() {
        Target best = null;
        float bestX = 0f;
        int n = ctx.targetCount();
        for (int i = 0; i < n; i++) {
            Target e = ctx.target(i);
            if (!e.alive() || !e.targetable()) {
                continue;
            }
            if (Collisions.distance(e.x(), e.y(), x, y) > GameConfig.OUTPOST_RANGE) {
                continue;
            }
            if (best == null || e.x() < bestX) {
                best = e;
                bestX = e.x();
            }
        }
        return best;
    }

    // --- the betrayal -------------------------------------------------------

    public boolean hasPrisoner() {
        return prisoner != null;
    }

    public Trappable prisoner() {
        return prisoner;
    }

    public float prisonerHp() {
        return prisonerHp;
    }

    public float prisonerMax() {
        return prisonerMax;
    }

    /** Above zero while the prisoner was recently hit; blocks regeneration. */
    public double prisonerHit() {
        return prisonerHit;
    }

    /** Visual only. */
    public float trapGlow() {
        return trapGlow;
    }

    /** Seconds until the next skeleton. */
    public double skeletonTimer() {
        return skeletonTimer;
    }

    /**
     * Only a Necromancer, only one at a time, and only if the player's Grab
     * Strength was enough to have lifted him in the first place.
     */
    public boolean canTrap(Trappable enemy) {
        return prisoner == null && enemy != null && enemy.alive()
                && enemy.mass() <= ctx.grabCapacity();
    }

    /**
     * Imprisons him.
     *
     * <p>He leaves the horde entirely — that removal is why the Python enemy loop
     * has to iterate a snapshot, and it is the reason {@code EntityList.Snapshot}
     * exists in this port.
     */
    public boolean trap(Trappable enemy) {
        if (!canTrap(enemy)) {
            return false;
        }
        enemy.onTrapped();
        prisoner = enemy;
        prisonerMax = GameConfig.PRISONER_HP;
        prisonerHp = GameConfig.PRISONER_HP;
        prisonerHit = 0f;
        enemy.moveTo(x, y - 26f);
        skeletonTimer = 1f;
        trapGlow = 1f;
        ctx.removeFromHorde(enemy);
        return true;
    }

    /** A free Necromancer is shooting the turncoat. */
    public void hurtPrisoner(float amount) {
        if (prisoner == null) {
            return;
        }
        prisonerHp -= amount;
        prisonerHit = 1f;
        if (prisonerHp <= 0f) {
            prisonerHp = 0f;
            prisoner = null;
            skeletonTimer = 0f;
        }
    }

    /**
     * The prisoner's own step: regeneration, and raising skeletons.
     *
     * <p>Regeneration is locked out for a second after each hit, so sustained
     * fire actually kills him rather than being outpaced. The raise timer is
     * checked <em>after</em> the cap, and neither advances while the cap is full —
     * so a player at the ally cap does not bank raises.
     */
    public void updatePrisoner(double dt) {
        if (prisoner == null) {
            return;
        }
        prisonerHit = Math.max(0d, prisonerHit - dt);
        if (prisonerHit <= 0d && prisonerHp < prisonerMax) {
            prisonerHp = Math.min(prisonerMax,
                    prisonerHp + GameConfig.PRISONER_REGEN * (float) dt);
        }
        trapGlow = Math.max(0f, trapGlow - (float) dt * 0.9f);   // visual

        AllyFactory allies = ctx.allies();
        int cap = GameConfig.TRAP_SKELETON_CAP + ctx.modifiers().allyCapBonus();
        skeletonTimer -= dt;
        if (skeletonTimer > 0d || allies.allyCount() >= cap) {
            return;
        }
        skeletonTimer = GameConfig.TRAP_SKELETON_RATE * ctx.modifiers().allyRate();
        if (allies.spawnAlly(x - 30f, y)) {
            trapGlow = 1f;
        }
    }

    // --- step ---------------------------------------------------------------

    /**
     * One step.
     *
     * <p>The prisoner is updated <b>before</b> the level check, so a captive keeps
     * raising skeletons even at an ungarrisoned outpost.
     */
    public void update(double dt) {
        flash = Math.max(0f, flash - (float) dt * 2f);      // visual
        updatePrisoner(dt);
        if (level <= 0) {
            return;
        }
        for (int i = 0; i < crew; i++) {
            cooldowns[i] -= dt;
            if (cooldowns[i] > 0d) {
                continue;
            }
            Target target = pickTarget();
            if (target == null) {
                //  nothing in range: re-check in 0.15 s rather than every step
                cooldowns[i] = 0.15;
                continue;
            }
            cooldowns[i] = gunReload();
            float gx = gunX(i);
            float gy = gunY(i);
            float speed = isTurret() ? 1000f : 840f;
            float a = (float) Math.atan2(target.y() - gy, target.x() - gx);
            ctx.addProjectile(Projectile.friendly(ctx, gx, gy,
                    (float) Math.cos(a) * speed, (float) Math.sin(a) * speed,
                    isTurret() ? ProjectileKind.BOLT : ProjectileKind.ARROW,
                    gunDamage()));
        }
    }

    /** Visual only. */
    public float flash() {
        return flash;
    }

    @Override
    public String toString() {
        return "Outpost[lv" + level + " guns=" + guns() + " x" + overdrive() + " pwr"
                + (prisoner != null ? " prisoner=" + (int) prisonerHp : "") + "]";
    }
}
