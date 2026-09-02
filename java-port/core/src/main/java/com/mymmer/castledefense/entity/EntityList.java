package com.mymmer.castledefense.entity;

import com.badlogic.gdx.utils.Array;

/**
 * An ordered collection of entities with Python's traversal semantics.
 *
 * <h2>Insertion order is part of the behaviour</h2>
 *
 * <p>Order is never disturbed: no sorting, and above all <b>no swap-remove</b>.
 * The Python crowd-separation pass mutates positions while walking pairs, so the
 * result depends on list order; the same is true of tower target selection
 * ("whoever is furthest along" resolves ties by position in the list). A
 * swap-remove would silently reshuffle both. Cosmetic collections (particles,
 * floating text) may use cheaper strategies later precisely because nothing
 * observes their order.
 *
 * <h2>Snapshot traversal</h2>
 *
 * <p>{@code main.py} iterates {@code list(self.enemies)} in several places, and
 * the copy is load-bearing:
 *
 * <ul>
 *   <li>A mob spawned mid-frame (a Necromancer summoning, the Lich raising dead,
 *       the Challenge Horn) must <b>not</b> be stepped on its own spawn frame.</li>
 *   <li>{@code Outpost.trap()} removes the current enemy from the list mid-loop;
 *       without a snapshot the loop skipped its neighbour.</li>
 * </ul>
 *
 * <p>{@link #beginSnapshot()} reproduces that, without allocating in the hot
 * path: buffers are pooled and reused, and nesting is supported because a
 * snapshot traversal can trigger another one (an entity's step causing a blast
 * that walks the list again).
 *
 * <pre>
 * Snapshot&lt;Enemy&gt; snap = enemies.beginSnapshot();
 * try {
 *     for (int i = 0; i &lt; snap.size(); i++) {
 *         Enemy e = snap.get(i);
 *         if (!e.isAlive()) continue;      // died earlier this frame
 *         e.step(dt);
 *     }
 * } finally {
 *     snap.close();
 * }
 * </pre>
 *
 * <h2>Ownership</h2>
 * Created and owned by {@code GameWorld}; only {@code GameWorld} and the systems
 * it calls add to or sweep it.
 */
public final class EntityList<T extends Entity> {

    /**
     * A frozen view of the list as it was when traversal began.
     *
     * <p>Entities removed during traversal still appear (callers check
     * {@link Entity#isAlive()}); entities added during traversal do not appear
     * until the next snapshot. Must be closed, ideally in a {@code finally}.
     */
    public final class Snapshot implements AutoCloseable {
        private Object[] items = new Object[16];
        private int size;
        private boolean open;

        void fill(Array<T> source) {
            if (items.length < source.size) {
                items = new Object[Math.max(source.size, items.length * 2)];
            }
            for (int i = 0; i < source.size; i++) {
                items[i] = source.get(i);
            }
            size = source.size;
            open = true;
        }

        public int size() {
            return size;
        }

        @SuppressWarnings("unchecked")
        public T get(int index) {
            return (T) items[index];
        }

        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() {
            if (!open) {
                return;
            }
            // release the references so a snapshot cannot pin dead entities
            for (int i = 0; i < size; i++) {
                items[i] = null;
            }
            size = 0;
            open = false;
            release(this);
        }
    }

    private final Array<T> items;
    private final Array<Snapshot> pool = new Array<>();
    private int openSnapshots;
    private int peakOpenSnapshots;

    public EntityList() {
        this(32);
    }

    public EntityList(int initialCapacity) {
        items = new Array<>(false, initialCapacity);
    }

    /** Appends, keeping insertion order. */
    public void add(T entity) {
        if (entity == null) {
            throw new IllegalArgumentException("entity must not be null");
        }
        items.add(entity);
    }

    /** Live and dead entities currently held. */
    public int size() {
        return items.size;
    }

    public T get(int index) {
        return items.get(index);
    }

    public boolean isEmpty() {
        return items.size == 0;
    }

    /** Entities still alive. O(n) — for tests and HUD counts, not hot loops. */
    public int aliveCount() {
        int n = 0;
        for (int i = 0; i < items.size; i++) {
            if (items.get(i).isAlive()) {
                n++;
            }
        }
        return n;
    }

    public boolean contains(T entity) {
        return items.contains(entity, true);
    }

    /**
     * Removes every dead entity, preserving the order of the survivors.
     *
     * @return how many were removed
     */
    public int sweep() {
        int write = 0;
        int removed = 0;
        for (int read = 0; read < items.size; read++) {
            T e = items.get(read);
            if (e.isAlive()) {
                items.set(write++, e);
            } else {
                removed++;
            }
        }
        items.truncate(write);
        return removed;
    }

    /** Marks everything dead and sweeps. Used when a run ends. */
    public void clear() {
        for (int i = 0; i < items.size; i++) {
            items.get(i).markDead();
        }
        items.clear();
    }

    /** Opens a reusable snapshot. Always close it. */
    public Snapshot beginSnapshot() {
        Snapshot snap = pool.size > 0 ? pool.pop() : new Snapshot();
        snap.fill(items);
        openSnapshots++;
        if (openSnapshots > peakOpenSnapshots) {
            peakOpenSnapshots = openSnapshots;
        }
        return snap;
    }

    private void release(Snapshot snap) {
        openSnapshots--;
        pool.add(snap);
    }

    /** Snapshots currently open. Should be zero between frames. */
    public int openSnapshots() {
        return openSnapshots;
    }

    /** Deepest nesting seen — how many buffers the pool had to hold. */
    public int peakOpenSnapshots() {
        return peakOpenSnapshots;
    }

    /**
     * Direct access to the backing array.
     *
     * <p>For read-only walks that are provably safe against mutation. Anything
     * that can spawn, kill or trap must use {@link #beginSnapshot()} instead.
     */
    public Array<T> unsafeItems() {
        return items;
    }
}
