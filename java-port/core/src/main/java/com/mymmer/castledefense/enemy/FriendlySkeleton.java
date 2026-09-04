package com.mymmer.castledefense.enemy;

import com.mymmer.castledefense.config.GameConfig;
import com.mymmer.castledefense.debug.TraceEvent;
import com.mymmer.castledefense.entity.Entity;

/**
 * A skeleton raised by the Necromancer imprisoned in the Outpost.
 *
 * <p>His magic runs backwards in there, so these march the <b>wrong way</b> —
 * left to right, out of the outpost and into the oncoming horde.
 *
 * <h2>Deliberately not an {@link Enemy}</h2>
 *
 * <p>It would share a good deal of code, and it would be wrong. An ally is not a
 * hostile unit with a flag flipped: it lives in the ally collection, the
 * player's own towers never see it, it never threatens the castle, it does not
 * count toward clearing a wave, and it walks in the opposite direction on a
 * lifetime timer. Python keeps it as a separate class for exactly those reasons.
 *
 * <p>The concrete consequence is that it does <b>not</b> implement
 * {@link com.mymmer.castledefense.defence.Target}. Towers cannot target what
 * they cannot see, and that is enforced by the type system rather than by a
 * check every targeting loop would have to remember.
 *
 * <p>It extends {@link Entity} for a uid and the mark-dead lifecycle — that is
 * shared infrastructure, not shared behaviour.
 */
public final class FriendlySkeleton extends Entity {

    public static final String NAME = "Bone Ally";
    public static final float WIDTH = 18f;
    public static final float HEIGHT = 28f;

    /**
     * Deliberately fragile — 60% below the original 46. Early allies are a speed
     * bump, not a wall; the Necromancy branch is what makes them stick.
     */
    public static final float BASE_HP = 18.4f;
    public static final float BASE_SPEED = 78f;
    public static final float BASE_DAMAGE = 13f;
    public static final double ATTACK_RATE = 0.85;
    /** Seconds before it crumbles on its own. */
    public static final double BASE_LIFE = 60.0;

    private final EnemyContext ctx;

    private final float maxHp;
    private float hp;
    private final float damage;
    private final float speed = BASE_SPEED;
    private final float depth;

    private float x;
    private float y;
    private EnemyState state = EnemyState.WALK;
    private double attackTimer;
    private double life;
    private Enemy target;

    // visual only
    private float anim;
    private float hurtFlash;

    public FriendlySkeleton(EnemyContext ctx, int wave, float x, Float y) {
        if (ctx == null) {
            throw new IllegalArgumentException("ctx must not be null");
        }
        this.ctx = ctx;
        float hpM = WaveScaling.hp(wave);
        float dmgM = WaveScaling.damage(wave);
        float boost = ctx.modifiers().allyPower();
        this.maxHp = BASE_HP * hpM * boost;
        this.hp = maxHp;
        this.damage = BASE_DAMAGE * dmgM * boost;
        this.depth = ctx.rng().uniform(-14f, 14f);
        this.x = x;
        this.y = y != null ? y : groundY();
        this.attackTimer = ctx.rng().uniformSeconds(0.0, 0.3);
        this.anim = ctx.rng().uniform(0f, 6f);
        this.life = BASE_LIFE + ctx.modifiers().allyLife();
        ctx.trace().event(TraceEvent.ENTITY_SPAWN, ctx.step(), uid(), x, this.y, "ally");
    }

    // --- geometry -----------------------------------------------------------

    public float groundY() {
        return GameConfig.GROUND_Y + depth - HEIGHT / 2f;
    }

    public float x() {
        return x;
    }

    public float y() {
        return y;
    }

    public float width() {
        return WIDTH;
    }

    public float height() {
        return HEIGHT;
    }

    public float depth() {
        return depth;
    }

    public boolean alive() {
        return isAlive();
    }

    public float hp() {
        return hp;
    }

    public float maxHp() {
        return maxHp;
    }

    public float damage() {
        return damage;
    }

    public EnemyState state() {
        return state;
    }

    public double life() {
        return life;
    }

    public Enemy target() {
        return target;
    }

    /** Visual only. */
    public float hurtFlash() {
        return hurtFlash;
    }

    // --- combat -------------------------------------------------------------

    /** Takes a hit, reduced by the ally-toughness talent. */
    public void takeDamage(float amount) {
        if (!isAlive()) {
            return;
        }
        hp -= amount * ctx.modifiers().allyTough();
        hurtFlash = 1f;
        if (hp <= 0f) {
            markDead();
            ctx.trace().event(TraceEvent.ENTITY_DEATH, ctx.step(), uid(), x, y, "ally");
        }
    }

    /**
     * The nearest ground mob worth walking at.
     *
     * <p>Without Undead Sentinels they only notice what is <b>in front of</b>
     * them — anything that already slipped past is invisible. With it, the reach
     * jumps from 260 to 2000 and the direction filter goes away, so they turn and
     * run things down.
     *
     * <p>Bosses are never chosen: an ally would simply die on one.
     */
    public Enemy pickTarget() {
        boolean sentinel = ctx.modifiers().allySentinels();
        float reach = sentinel ? 2000f : 260f;
        Enemy best = null;
        float bestD = 0f;
        int n = ctx.targetCount();
        for (int i = 0; i < n; i++) {
            com.mymmer.castledefense.defence.Target t = ctx.target(i);
            if (!(t instanceof Enemy)) {
                continue;
            }
            Enemy en = (Enemy) t;
            if (!en.alive() || en.flying() || en.isBoss()) {
                continue;
            }
            float d = Math.abs(en.x() - x);
            if (!sentinel && en.x() < x) {
                continue;           // blind to anything already behind them
            }
            if (d < reach && (best == null || d < bestD)) {
                best = en;
                bestD = d;
            }
        }
        return best;
    }

    /** One fixed simulation step. */
    public void update(double dt) {
        if (!isAlive()) {
            return;
        }
        float fdt = (float) dt;                 // spatial / visual only
        hurtFlash = Math.max(0f, hurtFlash - fdt * 4f);
        life -= dt;
        if (life <= 0d) {
            markDead();
            ctx.trace().event(TraceEvent.ENTITY_DEATH, ctx.step(), uid(), x, y, "ally-expired");
            return;
        }
        anim += fdt * 8f;

        if (target != null && !target.alive()) {
            target = null;
        }
        if (target == null) {
            target = pickTarget();
        }

        Enemy tgt = target;
        if (tgt != null && Math.abs(tgt.x() - x) <= GameConfig.ALLY_ENGAGE_RANGE) {
            state = EnemyState.ATTACK;
            attackTimer -= dt;
            if (attackTimer <= 0d) {
                attackTimer = ATTACK_RATE;
                tgt.applyDamage(damage, "melee");
            }
            return;
        }

        if (tgt != null && ctx.modifiers().allySentinels()) {
            //  Undead Sentinels: run it down, whichever way it is
            state = EnemyState.WALK;
            x += Math.copySign(speed * fdt, tgt.x() - x);
        } else if (x < GameConfig.ALLY_HOLD_X) {
            state = EnemyState.WALK;
            x += speed * fdt;           // marching the wrong way, on purpose
        } else {
            //  far enough out: hold this line and meet whatever arrives
            state = EnemyState.HOLD;
            x = GameConfig.ALLY_HOLD_X;
        }
    }

    @Override
    public String toString() {
        return "ally#" + uid() + " " + state.id() + " x=" + Math.round(x)
                + " hp=" + Math.round(hp) + "/" + Math.round(maxHp)
                + " life=" + String.format("%.1f", life);
    }
}
