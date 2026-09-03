package com.mymmer.castledefense.boss;

import com.mymmer.castledefense.config.GameConfig;
import com.mymmer.castledefense.debug.TraceEvent;
import com.mymmer.castledefense.entity.Entity;
import com.mymmer.castledefense.util.Collisions;

/**
 * A piece of boss equipment the player has torn loose.
 *
 * <p>It follows the cursor while held, flies with the throw, then lies on the
 * ground until its owner recovers it.
 *
 * <h2>Deliberately not an {@link com.mymmer.castledefense.enemy.Enemy}</h2>
 *
 * <p>It has no health, no armour, no state machine worth the name, no attack, no
 * targeting and no death payout. Its physics is its own: a different gravity
 * response, its own bounce constant, arena walls at different places, and a rest
 * height measured from the ground line rather than a per-instance depth. Forcing
 * it under {@code Enemy} for the handful of shared lines would drag all of that
 * along and make both harder to read.
 *
 * <p>It extends {@link Entity} for a uid and the mark-dead lifecycle, which is
 * shared infrastructure rather than shared behaviour — and the uid matters here:
 * it is how a dropped item is tied back to the boss that owns it, so a purge can
 * find it and a second Troll King never inherits the first one's crown.
 *
 * <h2>Its geometry is gameplay, not artwork</h2>
 *
 * <p>{@link RegaliaKind} carries the pickup box. Nothing here consults a texture,
 * and a skin that draws a bigger crown does not widen the area the player can
 * click to pick it up.
 */
public final class DroppedItem extends Entity {

    /** Where a dropped item is in its short life. */
    public enum State {
        /** On the cursor. Physics suspended. */
        HELD("held"),
        /** Thrown, under gravity. */
        FLYING("flying"),
        /** Settled, waiting to be recovered. */
        GROUND("ground");

        private final String id;

        State(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }
    }

    private final BossContext ctx;
    /** The boss this belongs to. Held by uid as well, for purge lookups. */
    private final Boss owner;
    private final long ownerUid;
    private final RegaliaKind kind;

    private float x;
    private float y;
    private float vx;
    private float vy;
    private State state = State.HELD;

    /** Visual only. */
    private float spin;
    /** Visual only: the idle bob once it is on the ground. */
    private float bob;

    public DroppedItem(BossContext ctx, Boss owner, RegaliaKind kind, float x, float y) {
        if (ctx == null || owner == null || kind == null) {
            throw new IllegalArgumentException("ctx, owner and kind must not be null");
        }
        this.ctx = ctx;
        this.owner = owner;
        this.ownerUid = owner.uid();
        this.kind = kind;
        this.x = x;
        this.y = y;
        ctx.trace().event(TraceEvent.DROPPED_ITEM_CREATED, ctx.step(), uid(),
                x, y, kind.id());
    }

    // --- identity -----------------------------------------------------------

    public RegaliaKind kind() {
        return kind;
    }

    /** The boss that owns this. Never null; may be dead. */
    public Boss owner() {
        return owner;
    }

    /**
     * The owning boss's uid.
     *
     * <p>Kept separately from the reference so a purge can match on a stable id
     * rather than on object identity — the same reason projectiles carry
     * {@code ownerUid}. Two Troll Kings on the field at once each have their own
     * crown, and neither can pick up the other's.
     */
    public long ownerUid() {
        return ownerUid;
    }

    public State state() {
        return state;
    }

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

    public float width() {
        return kind.width();
    }

    public float height() {
        return kind.height();
    }

    /** Visual only. */
    public float spin() {
        return spin;
    }

    /** Visual only. */
    public float bob() {
        return bob;
    }

    /** Where it settles: on the ground line, allowing for its own height. */
    public float restY() {
        return GameConfig.GROUND_Y - kind.height() / 2f + 2f;
    }

    /** Gameplay pickup test. Python {@code rect.collidepoint}. */
    public boolean covers(float px, float py) {
        return Collisions.pointInPygameRect(px, py,
                (int) (x - kind.width() / 2f), (int) (y - kind.height() / 2f),
                (int) kind.width(), (int) kind.height());
    }

    // --- interaction --------------------------------------------------------

    /** Picked up again off the ground. */
    public void hold() {
        state = State.HELD;
    }

    /** Dragged by the cursor. */
    public void moveTo(float nx, float ny) {
        this.x = nx;
        this.y = ny;
    }

    public void addSpin(float amount) {
        spin += amount;
    }

    /**
     * Thrown.
     *
     * <p>The speed is capped <b>by kind</b>, and the cap is applied to the
     * magnitude with the direction preserved — not clamped per axis, which would
     * change the angle as well as the speed.
     */
    public void throwIt(float throwVx, float throwVy) {
        float cap = kind.maxThrowSpeed();
        float sp = (float) Math.sqrt(throwVx * throwVx + throwVy * throwVy);
        if (sp > cap) {
            throwVx = throwVx * cap / sp;
            throwVy = throwVy * cap / sp;
        }
        this.vx = throwVx;
        this.vy = throwVy;
        state = State.FLYING;
    }

    // --- physics ------------------------------------------------------------

    /**
     * One fixed simulation step.
     *
     * <p>Its own physics, and none of it is the enemy physics: no air drag, no
     * wind, no slams, its own restitution of 0.34, its own 0.6 horizontal loss
     * per bounce, arena walls one item-width inside the castle front and the
     * right edge, and a 90 px/s settle threshold.
     */
    public void update(float dt) {
        if (state == State.HELD) {
            return;
        }
        bob += dt * 3f;
        if (state != State.FLYING) {
            return;
        }
        vy += GameConfig.GRAVITY * dt;
        x += vx * dt;
        y += vy * dt;
        spin += vx * dt * 0.02f;

        //  keep it on the battlefield, out of the castle
        float left = GameConfig.CASTLE_FRONT + kind.width();
        float right = GameConfig.WORLD_WIDTH - kind.width();
        if (x < left) {
            x = left;
            vx = Math.abs(vx) * 0.4f;
        }
        if (x > right) {
            x = right;
            vx = -Math.abs(vx) * 0.4f;
        }
        if (y >= restY()) {
            y = restY();
            vy = -Math.abs(vy) * 0.34f;
            vx *= 0.6f;
            if (Math.abs(vy) < 90f) {
                vy = 0f;
                state = State.GROUND;
            }
        }
    }

    @Override
    public String toString() {
        return kind.id() + "#" + uid() + " " + state.id()
                + " owner=" + ownerUid
                + " x=" + Math.round(x) + " y=" + Math.round(y)
                + (isAlive() ? "" : " DEAD");
    }
}
