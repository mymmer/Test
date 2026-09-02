package com.mymmer.castledefense.entity;

/**
 * The lifecycle every gameplay object shares.
 *
 * <p>Deliberately tiny. This is not a component base class and it is not the
 * start of an ECS: it carries the two things the Python game gives every entity
 * — a stable identity and an alive flag — and nothing else. Position, health,
 * mass and speed belong to the concrete types that actually have them.
 *
 * <h2>Mark-dead, then sweep</h2>
 *
 * <p>Nothing is ever removed from a list mid-frame. An entity is marked dead,
 * the rest of the frame skips it, and {@link EntityList#sweep()} removes it
 * afterwards. That is exactly what {@code main.py} does
 * ({@code self.enemies = [e for e in self.enemies if e.alive]} at the end of
 * {@code update}) and several behaviours depend on it — a Volatile's blast still
 * sees the mobs it killed a moment earlier in the same explosion, for instance.
 *
 * <h2>Identity</h2>
 *
 * <p>{@code uid} is a monotonic counter, never an array index and never a hash.
 * The Python game learned this the hard way: it used {@code id()} for
 * already-hit bookkeeping until CPython recycled an address and a fresh mob
 * inherited a dead one's marker.
 */
public abstract class Entity {

    private static long nextUid = 0L;

    private final long uid;
    private boolean alive = true;

    protected Entity() {
        uid = ++nextUid;
    }

    /** Stable identity for the lifetime of this object. Never reused. */
    public final long uid() {
        return uid;
    }

    public final boolean isAlive() {
        return alive;
    }

    /**
     * Marks this entity for removal at the next sweep.
     *
     * <p>Idempotent, and it does <em>not</em> remove anything: the entity stays
     * in its list, and in any snapshot currently being traversed, until the
     * frame's sweep.
     */
    public final void markDead() {
        alive = false;
    }

    /**
     * Brings a marked-dead entity back. Only for object reuse, which this
     * project does not do yet — Phase 12 decides whether pooling is justified,
     * and pooling without a complete reset contract is worse than allocating.
     */
    protected final void revive() {
        alive = true;
    }

    /** Test hook so uid assertions do not depend on what ran before them. */
    static void resetUidCounterForTests() {
        nextUid = 0L;
    }
}
