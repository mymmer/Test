package com.mymmer.castledefense.boss;

import com.mymmer.castledefense.config.GameConfig;
import com.mymmer.castledefense.debug.TraceEvent;
import com.mymmer.castledefense.defence.Projectile;
import com.mymmer.castledefense.defence.ProjectileKind;
import com.mymmer.castledefense.enemy.EnemyConfig;
import com.mymmer.castledefense.enemy.EnemyState;
import com.mymmer.castledefense.util.Collisions;

/**
 * Airborne. Breathes searing AoE fire.
 *
 * <h2>The breath is a real timer, streamed</h2>
 *
 * <p>Not one attack: a <b>sustained stream</b> of {@link BossConfig#breathTime}
 * seconds, spitting a fireball every {@link BossConfig#breathShotInterval}, each
 * for a fraction of a full hit.
 *
 * <p>Both timers count down in <b>double</b> simulation seconds on the fixed
 * 1/60 step — never render frames, never wall-clock, never an animation
 * callback, and never a float. That last one is not fastidiousness: with float
 * timers the shipped Hard breath (1.25 s at a 0.075 s interval, the difficulty
 * halving the gap) produced <b>sixteen</b> fireballs where the Python source
 * produces fifteen, because a float step of 0.016666668 is fractionally longer
 * than a sixtieth and eighty-odd subtractions of it move the boundary. Eight
 * fireballs on Normal, fifteen on Hard, on every device. See
 * {@code SIMULATION.md}, "Gameplay time is double".
 *
 * <h2>The claws are not regalia, and share the regalia guard anyway</h2>
 *
 * <p>Battering the claws accumulates drag distance rather than taking an object
 * away, and when it completes it increments the <b>same</b>
 * {@code regaliaTaken} counter and calls the <b>same</b> {@code guardRegalia()}
 * as stealing a crown. That is Python's arrangement, quirk and all, and it is
 * what makes repeated claw disruption progressively harder. See
 * {@code docs/PORT_ANALYSIS.md} §14.
 */
public final class Dragon extends Boss {

    private double breathTimer;
    /** Seconds of breath left in the current stream. Zero when not breathing. */
    private double breathing;
    private double shotTimer;

    /** Drag distance accumulated toward the next claw disruption, 0..1. */
    private float clawProgress;
    /** Seconds of reeling left after a successful battering. */
    private double reel;

    public Dragon(BossContext ctx, EnemyConfig config, BossConfig boss,
                  int wave, Float x, Float y) {
        super(ctx, config, boss, wave, x, y);
        this.breathTimer = fireDelay(4.0);
    }

    public boolean breathing() {
        return breathing > 0d;
    }

    public double breathingRemaining() {
        return breathing;
    }

    public double breathTimer() {
        return breathTimer;
    }

    public float clawProgress() {
        return clawProgress;
    }

    public double reel() {
        return reel;
    }

    /** The claws are battered, not grabbed. */
    @Override
    public boolean isSmackTarget() {
        return true;
    }

    /**
     * The claws are available whenever the Dragon is alive, unguarded and not
     * already reeling. There is no object to have or not have, so
     * {@code hasRegalia} is simply true.
     */
    @Override
    protected boolean hasRegalia() {
        return reel <= 0d;
    }

    /**
     * Batters the claws.
     *
     * <p>Enough punishment and the Dragon reels: the fire breath is cut off
     * <b>mid-stream</b>, the next breath is pushed out to at least two seconds,
     * and it is driven back 120 px off the wall.
     *
     * @param amount drag distance in world units, from the interaction layer
     * @return true when this call completes a disruption
     */
    @Override
    public boolean applySmack(float amount) {
        if (!isAlive() || regaliaCd() > 0d || reel > 0d) {
            return false;
        }
        clawProgress += amount / GameConfig.CLAW_SMACK_DISTANCE;
        if (clawProgress < 1f) {
            return false;
        }
        clawProgress = 0f;
        //  The quirk, reproduced: a claw battering counts as a REGALIA taking
        //  and grows the same guard, even though no regalia is involved.
        countDisruption();
        reel = GameConfig.CLAW_STAGGER;
        breathing = 0d;                     // breath is cut off mid-stream
        breathTimer = Math.max(breathTimer, 2d);
        setX(x() + 120f);                   // driven back off the wall
        guardRegalia();
        bossCtx.trace().event(TraceEvent.BOSS_DISRUPTION, bossCtx.step(), uid(),
                x(), y(), "claws");
        return true;
    }

    @Override
    protected void think(double dt) {
        float fdt = (float) dt;             // flight, bob and animation
        anim += fdt * 6f;
        bob += fdt * 2.2f;

        if (reel > 0d) {
            //  knocked off its attack run: climbing and shaking it off
            reel -= dt;
            setState(EnemyState.ATTACK);
            setY(y() + Collisions.clamp((flyY - 60f) - y(), -170f * fdt, 170f * fdt));
            return;
        }

        float targetY = flyY + (float) Math.sin(bob) * 26f;
        setY(y() + Collisions.clamp(targetY - y(), -180f * fdt, 180f * fdt));

        if (x() > boss.standoffX) {
            setX(x() - speed * fdt);
            setVxEstimate(-speed);
            setState(EnemyState.WALK);
            return;
        }

        setState(EnemyState.ATTACK);

        //  A sustained stream on a real timer.  While breathing, nothing else
        //  happens -- the breath timer does not even tick.
        if (breathing > 0d) {
            breathing -= dt;
            shotTimer -= dt;
            if (shotTimer <= 0d) {
                shotTimer = fireDelay(boss.breathShotInterval);
                spitFire(boss.breathPower);
            }
            return;
        }

        breathTimer -= dt;
        if (breathTimer <= 0d) {
            breathTimer = fireDelay(bossCtx.rng().uniformSeconds(
                    boss.breathIntervalMin, boss.breathIntervalMax));
            breathing = boss.breathTime;
            shotTimer = 0d;             // the first fireball leaves immediately
        }
    }

    /**
     * One fireball, on a ballistic arc into the wall.
     *
     * <p>Aimed at a random point along the castle face, under a third of normal
     * gravity so it arcs lazily, with splash and a stun. The whole stream lands
     * across the wall rather than on one spot.
     */
    public void spitFire(float power) {
        float tx = ctx.castle().frontX() - bossCtx.rng().uniform(10f, 150f);
        float ty = bossCtx.rng().uniform(GameConfig.WALL_TOP - 30f, GameConfig.GROUND_Y - 20f);
        float mx = x() - width() / 2f;
        float my = y() + 6f;
        float dx = tx - mx;
        float dy = ty - my;
        float grav = GameConfig.GRAVITY * 0.35f;
        float t = Collisions.clamp(Math.abs(dx) / 460f, 0.4f, 1.6f);
        float vx = dx / t;
        float vy = (dy - 0.5f * grav * t * t) / t;
        ctx.addProjectile(new Projectile(ctx, mx, my, vx, vy, ProjectileKind.FIRE,
                damage() * power, 92f, 0, grav, true, t + 1.2f, 0.8f,
                1f, 1f, false, uid()));
        bossCtx.trace().event(TraceEvent.BOSS_ATTACK, bossCtx.step(), uid(),
                mx, my, "breath");
    }

    @Override
    protected String bossStateSummary() {
        return " breathing=" + String.format("%.2f", breathing)
                + " nextBreath=" + String.format("%.2f", breathTimer)
                + " claws=" + String.format("%.2f", clawProgress)
                + " reel=" + String.format("%.2f", reel);
    }
}
