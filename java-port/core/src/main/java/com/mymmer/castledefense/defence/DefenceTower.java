package com.mymmer.castledefense.defence;

import com.mymmer.castledefense.debug.TraceEvent;
import com.mymmer.castledefense.entity.Entity;
import com.mymmer.castledefense.util.Collisions;

/**
 * Base class for the auto-firing castle defences.
 *
 * <p>Subclasses supply {@link #fire}, {@link #scoreTarget} and, where they are
 * overchargeable, {@link #launchOvercharged}. Everything else — targeting,
 * cooldowns, regeneration, being knocked down and rebuilt, stun, the overcharge
 * state machine — is here, as it is in Python.
 *
 * <h2>Which fields are gameplay-authoritative and which are visual-only</h2>
 *
 * <table>
 *   <tr><th>Gameplay-authoritative</th><th>Visual-only</th></tr>
 *   <tr><td>{@code damage}, {@code reload}, {@code range}, {@code splash}</td>
 *       <td>{@code aim} — the smoothed barrel angle</td></tr>
 *   <tr><td>{@code hp}, {@code maxHp}, {@code disabled}, {@code rebuild}</td>
 *       <td>{@code recoil} — decays at 5/s and is read by nothing else</td></tr>
 *   <tr><td>{@code stun}, {@code cooldown}, {@code overchargeCd}, {@code level}</td>
 *       <td></td></tr>
 * </table>
 *
 * <p>The visual-only two live here rather than in a renderer because Python
 * computes them inside {@code update} and a later parity test will want to
 * compare them. They are still <b>state, not drawing</b>: this class has no
 * {@code draw} method and never will — see Phase 11.
 *
 * <p>One caveat on {@code aim}: it is written from the targeting result, so it
 * consumes the gameplay RNG-free path but is never read back by gameplay. If
 * that ever stops being true it becomes authoritative and moves rows.
 */
public abstract class DefenceTower extends Entity {

    protected final DefenceContext ctx;
    protected final TowerConfig config;

    /** Anchor: the base of the tower, in pygame coordinates (y down). */
    private final float x;
    private final float y;

    // --- gameplay-authoritative --------------------------------------------
    private float damage;
    private double reload;
    private float range;
    private float splash;
    private double cooldown;
    private float hp;
    private float maxHp;
    private boolean disabled;
    private double rebuild;
    private double stun;
    private int level = 1;
    private double overchargeCd;

    // --- visual-only --------------------------------------------------------
    private float aim = -0.35f;
    private float recoil;

    /** Reused by {@link #pickTarget()} so scoring allocates nothing per step. */
    private final float[] scoreOut = new float[2];
    private final float[] bestOut = new float[2];
    /** Reused by {@link #leadTarget}. */
    protected final float[] lead = new float[2];

    protected DefenceTower(DefenceContext ctx, TowerConfig config, float x, float y) {
        if (ctx == null || config == null) {
            throw new IllegalArgumentException("ctx and config must not be null");
        }
        this.ctx = ctx;
        this.config = config;
        this.x = x;
        this.y = y;
        this.damage = config.damage;
        this.reload = config.cooldown;
        this.range = config.range;
        this.splash = config.splash;
        this.maxHp = config.maxHp;
        this.hp = config.maxHp;
        //  Python: random.uniform(0, 0.4).  Staggers the volley so towers bought
        //  together do not fire in lockstep -- and it comes from the seeded
        //  gameplay stream, so a seeded run reproduces the same stagger.
        this.cooldown = ctx.rng().uniformSeconds(0.0, 0.4);
    }

    // --- identity and geometry ---------------------------------------------

    public TowerConfig config() {
        return config;
    }

    public TowerType type() {
        return config.type;
    }

    public String name() {
        return config.name;
    }

    public float x() {
        return x;
    }

    public float y() {
        return y;
    }

    public float width() {
        return config.width;
    }

    public float height() {
        return config.height;
    }

    /** Muzzle x. Python {@code muzzle[0]}. */
    public float muzzleX() {
        return x;
    }

    /** Muzzle y. Python {@code muzzle[1]} = {@code y - H + 6}. */
    public float muzzleY() {
        return y - config.height + 6f;
    }

    /**
     * Does this point land on the tower?
     *
     * <p>Python builds {@code pygame.Rect(int(x - W/2), int(y - H), W, H)} and
     * calls {@code collidepoint}, which truncates to integers and is half-open on
     * the right and bottom edges. Both are reproduced — see
     * {@link Collisions#pointInPygameRect}.
     */
    public boolean contains(float px, float py) {
        return Collisions.pointInPygameRect(px, py,
                (int) (x - config.width / 2f), (int) (y - config.height),
                (int) config.width, (int) config.height);
    }

    // --- gameplay state -----------------------------------------------------

    public float damage() {
        return damage;
    }

    public double reload() {
        return reload;
    }

    public float range() {
        return range;
    }

    public float splash() {
        return splash;
    }

    public double cooldown() {
        return cooldown;
    }

    /** Test/boss hook: forces the reload timer. Python assigns {@code t.cooldown}. */
    public void setCooldown(double seconds) {
        this.cooldown = seconds;
    }

    public float hp() {
        return hp;
    }

    public float maxHp() {
        return maxHp;
    }

    /** Used by the Castle when a wall upgrade toughens every platform. */
    void scaleMaxHp(float factor) {
        maxHp *= factor;
        hp = Math.min(maxHp, hp * factor);
    }

    /** Used when a tower is first stationed: the platform bonus is absolute. */
    void setMaxHpAndHeal(float newMaxHp) {
        maxHp = newMaxHp;
        hp = newMaxHp;
    }

    public boolean disabled() {
        return disabled;
    }

    public double rebuildRemaining() {
        return rebuild;
    }

    public double stun() {
        return stun;
    }

    /** Bosses and splash blasts stun a tower; the longer stun wins. */
    public void applyStun(double seconds) {
        if (seconds > 0d) {
            stun = Math.max(stun, seconds);
        }
    }

    public int level() {
        return level;
    }

    public double overchargeCd() {
        return overchargeCd;
    }

    /** Test hook mirroring Python's direct assignment of {@code overcharge_cd}. */
    public void setOverchargeCd(double seconds) {
        this.overchargeCd = seconds;
    }

    /** Visual only: the smoothed barrel angle, in radians. */
    public float aim() {
        return aim;
    }

    /** Visual only: 1 on firing, decaying at 5/s. */
    public float recoil() {
        return recoil;
    }

    // --- combat -------------------------------------------------------------

    /**
     * Takes a hit.
     *
     * <p>The per-hit cap is the important part: <b>no single blow may take more
     * than 42% of a tower's maximum</b>, so a boss smash is frightening but a
     * crew always gets a chance to react. Python: {@code min(amount, max_hp * 0.42)}.
     */
    public void takeDamage(float amount) {
        if (disabled) {
            return;
        }
        hp -= Math.min(amount, maxHp * 0.42f);
        if (hp <= 0f) {
            hp = 0f;
            disabled = true;
            rebuild = config.rebuildTime * ctx.modifiers().rebuildMult();
            ctx.trace().event(TraceEvent.TOWER_DISABLED, ctx.step(), uid(),
                    x, y, config.type.id());
        }
    }

    /** Back to full and undamaged. Python {@code restore()}. */
    public void restore() {
        hp = maxHp;
        disabled = false;
        rebuild = 0f;
        stun = 0f;
    }

    /**
     * Distance to a target, measured in this tower's own range envelope.
     *
     * <p>Anything above the muzzle has its vertical offset divided by
     * {@code airRangeMult}, stretching the envelope upwards. That is the Bowmen's
     * air-reach buff and it is what stops flyers parking just out of range.
     * ({@code dy < 0} means "above" — y grows downward here.)
     */
    public float reachTo(Target e) {
        float dx = e.x() - muzzleX();
        float dy = e.y() - muzzleY();
        if (dy < 0f && config.airRangeMult > 1f) {
            dy /= config.airRangeMult;
        }
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    /** This tower's damage against one specific target, counters included. */
    public float damageVs(Target e) {
        float m = ctx.modifiers().towerDamage();
        if (e.flying()) {
            m *= config.bonusVsAir;
        }
        if (e.heavy()) {
            m *= config.bonusVsHeavy;
        }
        return damage * m;
    }

    /**
     * Chooses what to shoot.
     *
     * <p>A straight linear scan of every target, in list order, exactly as
     * Python does it. Lowest score wins and <b>ties go to the earlier entry</b>
     * (the comparison is strictly "better than", never "at least as good as"),
     * so list order is part of the observable behaviour.
     *
     * <p>Deliberately not optimised: no shared candidate cache, no range grid, no
     * broadphase. Where Python is O(E²) — the Cannon's cluster scoring is — this
     * is O(E²) too. Correctness first; Phase 12 may replace it once a parity
     * test can prove the replacement picks the same target every time.
     */
    public Target pickTarget() {
        Target best = null;
        boolean haveBest = false;
        int n = ctx.targetCount();
        for (int i = 0; i < n; i++) {
            Target e = ctx.target(i);
            if (!e.alive() || !e.targetable()) {
                continue;
            }
            if (e.flying() && !config.hitsAir) {
                continue;
            }
            float d = reachTo(e);
            if (d > range) {
                continue;
            }
            scoreTarget(e, d, scoreOut);
            if (!haveBest || lessThan(scoreOut, bestOut)) {
                best = e;
                bestOut[0] = scoreOut[0];
                bestOut[1] = scoreOut[1];
                haveBest = true;
            }
        }
        return best;
    }

    /** Lexicographic "strictly better", matching Python's tuple comparison. */
    private static boolean lessThan(float[] a, float[] b) {
        if (a[0] != b[0]) {
            return a[0] < b[0];
        }
        return a[1] < b[1];
    }

    /**
     * Scores a candidate; <b>lower is better</b>.
     *
     * <p>Two components, compared lexicographically, because Python's Ballista
     * returns a tuple {@code (flying?0:1, -hp)} while the others return a single
     * number. A scalar score is written as {@code out[0]} with {@code out[1] = 0},
     * which compares identically.
     *
     * <p>Written into a caller-supplied array rather than returned, so the
     * targeting loop allocates nothing.
     */
    protected void scoreTarget(Target e, float dist, float[] out) {
        //  Default: prefer whatever this tower hard-counters, then whoever is
        //  furthest along (smallest x, closest to the gate).
        int prio = 0;
        if (e.flying() && config.bonusVsAir > 1f) {
            prio = -1;
        }
        if (e.heavy() && config.bonusVsHeavy > 1f) {
            prio = -1;
        }
        out[0] = prio * 100000f + e.x();
        out[1] = 0f;
    }

    /**
     * One simulation step. The order is Python's {@code update}.
     *
     * <p>Note what a disabled tower skips: it does not regenerate, aim, cool down
     * or fire — it only counts down its rebuild, and comes back at <b>half</b>
     * health, not full.
     */
    public void update(double dt) {
        recoil = Math.max(0f, recoil - (float) dt * 5f);   // visual
        overchargeCd = Math.max(0d, overchargeCd - dt);

        if (disabled) {
            rebuild -= dt;
            if (rebuild <= 0d) {
                disabled = false;
                hp = maxHp * 0.5f;
                ctx.trace().event(TraceEvent.TOWER_REBUILT, ctx.step(), uid(),
                        x, y, config.type.id());
            }
            return;
        }

        hp = Math.min(maxHp, hp + maxHp * config.regen * (float) dt);

        if (stun > 0d) {
            stun -= dt;
            return;
        }

        cooldown -= dt;
        Target target = pickTarget();
        if (target != null) {
            float want = (float) Math.atan2(target.y() - muzzleY(), target.x() - muzzleX());
            aim = Collisions.lerp(aim, want, 0.25f);
        }
        if (cooldown <= 0d && target != null) {
            cooldown = reload * ctx.modifiers().towerRate();
            recoil = 1f;
            ctx.trace().event(TraceEvent.TOWER_FIRE, ctx.step(), uid(),
                    target.x(), target.y(), config.type.id());
            fire(target);
        }
    }

    /**
     * Bought again with no wall space free: the existing guns get better.
     *
     * @return the new level
     */
    public int upgrade() {
        level++;
        damage *= 1.35f;
        reload *= 0.90;
        range *= 1.04f;
        if (splash != 0f) {
            splash *= 1.08f;
        }
        maxHp *= 1.15f;
        hp = maxHp;
        return level;
    }

    // --- manual overcharge --------------------------------------------------

    /**
     * Is the player allowed to hand-fire this right now?
     *
     * <p>Purely a gameplay question. Nothing about how the gesture is made —
     * mouse, finger, how far it was dragged — reaches this class; the Phase 4
     * input pipeline resolves a gesture into {@code (aimX, aimY, power)} and
     * calls {@link #overchargeFire}. That separation is why the same tower code
     * works on desktop and on a phone.
     */
    public boolean canOvercharge() {
        return config.overchargeable && !disabled && stun <= 0d && overchargeCd <= 0d;
    }

    /**
     * Hand-fired slingshot shot, launched immediately whatever the reload says.
     *
     * @param aimX  where the slingshot was drawn back to, world x
     * @param aimY  world y
     * @param power 0..1, how far back it was drawn
     * @return false if the draw was too short to fire (under 12 px)
     */
    public boolean overchargeFire(float aimX, float aimY, float power) {
        float dx = muzzleX() - aimX;        // fires opposite the draw-back
        float dy = muzzleY() - aimY;
        float d = (float) Math.sqrt(dx * dx + dy * dy);
        if (d < 12f) {
            return false;
        }
        overchargeCd = com.mymmer.castledefense.config.GameConfig.OVERCHARGE_COOLDOWN
                * ctx.modifiers().overchargeCd();
        cooldown = reload;
        recoil = 1f;
        aim = (float) Math.atan2(dy, dx);
        launchOvercharged(dx / d, dy / d, power);
        return true;
    }

    /** What an overcharged shot actually is. Only overchargeable towers implement it. */
    protected void launchOvercharged(float dirX, float dirY, float power) {
        throw new UnsupportedOperationException(
                config.type.id() + " cannot be overcharged");
    }

    /** The ordinary auto-fire shot. */
    protected abstract void fire(Target target);

    /**
     * First-order intercept so shots do not lag behind runners.
     *
     * <p>Writes into {@link #lead} rather than returning a pair.
     */
    protected void leadTarget(Target target, float speed) {
        float dx = target.x() - muzzleX();
        float dy = target.y() - muzzleY();
        float d = (float) Math.sqrt(dx * dx + dy * dy);
        float t = d / Math.max(1f, speed);
        lead[0] = target.x() + target.vxEstimate() * t;
        lead[1] = target.y() + target.vyEstimate() * t;
    }

    @Override
    public String toString() {
        return config.type.id() + "[lv" + level + " hp=" + (int) hp + "/" + (int) maxHp
                + (disabled ? " DOWN" : "") + "]";
    }
}
