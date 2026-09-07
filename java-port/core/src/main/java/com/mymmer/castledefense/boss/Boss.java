package com.mymmer.castledefense.boss;

import com.mymmer.castledefense.config.GameConfig;
import com.mymmer.castledefense.config.GameplayAnchor;
import com.mymmer.castledefense.debug.TraceEvent;
import com.mymmer.castledefense.enemy.Enemy;
import com.mymmer.castledefense.enemy.EnemyConfig;
import com.mymmer.castledefense.util.Collisions;

/**
 * Base for the three bosses.
 *
 * <p>It extends {@link Enemy} because the Python one does, and because that is
 * genuinely what a boss is: it walks, it attacks, it takes damage through the
 * same contracts, it dies and pays out through the same {@code die()}. Building
 * a separate hierarchy would mean reimplementing all of that and re-testing it.
 *
 * <p>What a boss adds is three things: it cannot be picked up, it fires on a
 * difficulty-scaled clock, and it has an <b>interactive disruption</b> the
 * player has to perform by hand.
 *
 * <h2>Immunity is a flag, not an {@code instanceof}</h2>
 *
 * <p>{@link #isBoss()} returns true and the config sets {@code grabbable} false,
 * so {@code Enemy.grabbable()} already refuses without knowing what a Troll King
 * is. Nothing anywhere in the port switches on a concrete boss class.
 *
 * <h2>The shared disruption guard</h2>
 *
 * <p>{@link #regaliaTaken} and {@link #guardRegalia()} are shared by all three,
 * and the Dragon uses them for its <b>claws</b> — which are not regalia at all.
 * That is Python's arrangement: {@code Dragon.apply_smack} increments
 * {@code regalia_taken} and calls {@code guard_regalia()}, so battering the claws
 * makes the crown-style guard longer each time exactly as stealing a crown does.
 * The Java name is generalised to "disruption" where it reads better, but the
 * <b>behaviour is identical</b> and is pinned by a named test. See
 * {@code docs/PORT_ANALYSIS.md} §14.
 */
public abstract class Boss extends Enemy {

    protected final BossConfig boss;
    protected final BossContext bossCtx;

    /** Seconds of arrival animation. Visual state; nothing gates on it yet. */
    private double intro;
    /** Visual only: the hit glow, decaying at 3/s. */
    private float aura;

    /**
     * Interval multiplier for every projectile a boss throws.
     *
     * <p>Hard sets this to 0.5, which is literally twice the rate of fire.
     * Captured once at construction, so a difficulty change mid-run cannot
     * retune a boss already on the field.
     */
    private final double fireScale;

    /** Seconds until the regalia can be disrupted again. */
    private double regaliaCd;
    /** How many times this boss has been disrupted. Drives the guard growth. */
    private int regaliaTaken;

    protected Boss(BossContext ctx, EnemyConfig config, BossConfig boss,
                   int wave, Float x, Float y) {
        super(ctx, config, wave, x, y);
        this.bossCtx = ctx;
        this.boss = boss;
        this.intro = boss.introTime;
        this.fireScale = ctx.bossFireScale();
        ctx.trace().event(TraceEvent.BOSS_SPAWN, ctx.step(), uid(), x(), y(),
                boss.type.id());
    }

    // --- identity -----------------------------------------------------------

    public BossType bossType() {
        return boss.type;
    }

    public BossConfig bossConfig() {
        return boss;
    }

    @Override
    public boolean isBoss() {
        return true;
    }

    /** Visual only. */
    public double intro() {
        return intro;
    }

    /** Visual only. */
    public float aura() {
        return aura;
    }

    /**
     * Scales a reload time by the difficulty's boss fire rate.
     *
     * <p>Time in, time out: {@code double} at both ends. Hard's 0.5 turns the
     * Dragon's 0.15 s shot interval into 0.075 s, and doing that in floats is
     * precisely what made a Hard breath fire sixteen fireballs where Python
     * fires fifteen.
     */
    public double fireDelay(double seconds) {
        return seconds * fireScale;
    }

    public double fireScale() {
        return fireScale;
    }

    // --- the disruption guard -----------------------------------------------

    public double regaliaCd() {
        return regaliaCd;
    }

    /** How many times this boss has been disrupted. Zero on a fresh boss. */
    public int regaliaTaken() {
        return regaliaTaken;
    }

    /**
     * The boss recovers its item — or shakes off a battering — and holds on
     * tighter each time.
     *
     * <p>{@code REGALIA_COOLDOWN × (1 + REGALIA_CD_GROWTH × regaliaTaken)}, so
     * the guard grows without bound but linearly: 6 s, then 9.6 s, then 13.2 s.
     * That keeps the mechanic strong without letting it become a stun-lock.
     */
    public void guardRegalia() {
        regaliaCd = GameConfig.REGALIA_COOLDOWN
                * (1d + GameConfig.REGALIA_CD_GROWTH * regaliaTaken);
    }

    /** Counts one successful disruption. Call <em>before</em> {@link #guardRegalia}. */
    protected void countDisruption() {
        regaliaTaken++;
        bossCtx.trace().event(TraceEvent.BOSS_DISRUPTION, bossCtx.step(), uid(),
                regaliaTaken, (float) regaliaCd, boss.type.id());
    }

    /**
     * The gameplay-authoritative world position of this boss's interactive
     * point, or null if it has none.
     *
     * <p><b>From {@code data/bosses.json}, never from a skin.</b> A skin may draw
     * the crown anywhere; where the player must click to take it does not move.
     */
    public boolean regaliaAnchorInto(float[] out) {
        GameplayAnchor anchor = boss.regaliaAnchor;
        if (anchor == null) {
            return false;
        }
        out[0] = anchor.worldX(x(), width());
        out[1] = anchor.worldY(y(), height());
        return true;
    }

    /** Reused so an anchor query allocates nothing. */
    private final float[] anchorOut = new float[2];

    /**
     * Is this point on the interactive regalia right now?
     *
     * <p>False while the guard is up, while the boss is dead, and while the
     * subclass says its item is not there to be taken.
     */
    public boolean regaliaCovers(float px, float py) {
        if (!isAlive() || regaliaCd > 0d || !hasRegalia()) {
            return false;
        }
        if (!regaliaAnchorInto(anchorOut)) {
            return false;
        }
        return Collisions.pointInBox(px, py,
                anchorOut[0], anchorOut[1] + boss.regaliaBoxOffsetY,
                boss.regaliaHalfWidth * 2f, boss.regaliaHalfHeight * 2f);
    }

    /** Does this boss currently have something to disrupt? */
    protected boolean hasRegalia() {
        return false;
    }

    /**
     * Tears the regalia loose, returning the dropped item, or null.
     *
     * <p>Overridden by the Troll King and the Lich Lord. The Dragon has none —
     * its claws are battered rather than removed.
     */
    public DroppedItem detachRegalia() {
        return null;
    }

    /**
     * Batters this boss's interactive point with a drag.
     *
     * <p>Only the Dragon implements it. Separate from {@link #detachRegalia}
     * because the two are genuinely different interactions: one takes an object
     * away in a single grab, the other accumulates drag distance.
     *
     * @return true when the battering completes a disruption this call
     */
    public boolean applySmack(float amount) {
        return false;
    }

    /** True when this boss's interactive point is a smack target, not a grab target. */
    // ========================================================================
    //  A uniform display surface
    // ========================================================================
    //
    //  Phase 11 needs to draw three bosses differently, and `instanceof
    //  TrollKing` in a painter is exactly what ArchitectureTest forbids -- for
    //  good reason: the moment a type test is acceptable in one place it spreads.
    //  So each boss's distinguishing display state is published here with a
    //  neutral default and overridden by the one boss it applies to. A painter
    //  switches on bossType() to pick a BODY and reads these for its STATE.
    //
    //  These are reads. None of them can change anything, and gameplay does not
    //  call them.

    /** Whether this boss still wears or holds its regalia. True if it has none. */
    public boolean regaliaAttached() {
        return true;
    }

    /** A melee swing in progress, 0..1. The Troll King's club. */
    public float swing() {
        return 0f;
    }

    /** Whether a breath weapon is firing right now. The Dragon's. */
    public boolean venting() {
        return false;
    }

    /** Seconds of staggered recovery left. The Dragon's, after a claw smack. */
    public double reeling() {
        return 0d;
    }

    /** A protective ward's strength, 0 when none. The Lich Lord's. */
    public double wardStrength() {
        return 0d;
    }

    /** A charging orb, 0..1. The Lich Lord's staff. */
    public float orbCharge() {
        return 0f;
    }

    /**
     * How far a hand-performed disruption has got, 0..1.
     *
     * <p>The Dragon's claw battering. Published here rather than reached for by
     * a type test, for the reason in this section's header.
     */
    public float disruptionProgress() {
        return 0f;
    }

    /** Seconds left disarmed, 0 when armed. The Lich Lord's. */
    public double disarmedFor() {
        return 0d;
    }

    public boolean isSmackTarget() {
        return false;
    }

    // --- lifecycle ----------------------------------------------------------

    @Override
    public float applyDamage(float amount, String kind) {
        float dealt = super.applyDamage(amount, kind);
        if (dealt > 0f) {
            aura = 1f;
        }
        return dealt;
    }

    @Override
    public void update(double dt) {
        aura = Math.max(0f, aura - (float) dt * 3f);       // visual
        intro = Math.max(0d, intro - dt);
        regaliaCd = Math.max(0d, regaliaCd - dt);
        super.update(dt);
    }

    /**
     * Dies.
     *
     * <p>The ordinary {@code Enemy} payout runs first — gold at the crowd
     * multiplier, the kill count, the fling score — and only then does the
     * boss-specific hook fire, exactly once. Everything a boss owns is released
     * by {@link #releaseOwnedState}, which subclasses extend.
     */
    @Override
    public void die(boolean silent) {
        boolean wasAlive = isAlive();
        super.die(silent);
        if (!wasAlive) {
            return;
        }
        bossCtx.trace().event(TraceEvent.BOSS_DEATH, bossCtx.step(), uid(),
                x(), y(), boss.type.id());
        releaseOwnedState();
        bossCtx.onBossDefeated(this);
    }

    /**
     * Drops everything this boss instance owns.
     *
     * <p>Called on death and by an explicit purge. Subclasses override to add
     * their own — the crown, the staff — and <b>must call through</b>. Java's
     * GC is not a substitute: these are logical ownerships, and a stale
     * reference from a dead boss is a bug long before it is a leak.
     */
    protected void releaseOwnedState() {
        // nothing at this level; subclasses add theirs
    }

    /**
     * A description dense enough to reproduce a boss bug from a report.
     *
     * <p>Built on demand only. Nothing logs this per frame.
     */
    @Override
    public String describe() {
        return super.describe()
                + " boss=" + boss.type.id()
                + " guard=" + String.format("%.2f", regaliaCd)
                + " taken=" + regaliaTaken
                + " fireScale=" + fireScale
                + bossStateSummary();
    }

    /** Subclass-specific diagnostics, appended to {@link #describe()}. */
    protected String bossStateSummary() {
        return "";
    }
}
