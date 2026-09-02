package com.mymmer.castledefense.enemy;

/**
 * Per-victim slam cooldowns, keyed by entity uid.
 *
 * <p>Python uses {@code dict[uid] -> seconds}. This is two parallel primitive
 * arrays scanned linearly: an airborne mob is in contact with a handful of
 * others at once, so the scan is shorter than a hash and it allocates nothing
 * per tick — and this runs inside the airborne update, once per pair, per step.
 *
 * <p><b>Why uid and not the object.</b> The Python source added explicit uids
 * precisely for this: CPython recycles {@code id()} values, so a freshly spawned
 * mob could inherit a dead one's cooldown and be immune to a slam it never took.
 * Java object identity would not recycle, but a uid is still the right key —
 * it is stable, it survives being written to a trace, and because uids are
 * monotonic and never reused, a dead entry can never match a new entity.
 */
final class SlamCooldowns {

    private long[] uids = new long[8];
    private float[] remaining = new float[8];
    private int size;

    /** Sets or refreshes a cooldown against one victim. */
    void put(long uid, float seconds) {
        for (int i = 0; i < size; i++) {
            if (uids[i] == uid) {
                remaining[i] = seconds;
                return;
            }
        }
        if (size == uids.length) {
            long[] u = new long[size * 2];
            float[] r = new float[size * 2];
            System.arraycopy(uids, 0, u, 0, size);
            System.arraycopy(remaining, 0, r, 0, size);
            uids = u;
            remaining = r;
        }
        uids[size] = uid;
        remaining[size] = seconds;
        size++;
    }

    boolean contains(long uid) {
        for (int i = 0; i < size; i++) {
            if (uids[i] == uid) {
                return true;
            }
        }
        return false;
    }

    /**
     * Ages every entry and drops the expired ones.
     *
     * <p>Python deletes from the dict as it walks a copy of the keys. Here the
     * survivors are compacted forward, which keeps the same "expired entries are
     * gone this step" semantics without allocating a key list.
     */
    void tick(float dt) {
        int out = 0;
        for (int i = 0; i < size; i++) {
            float left = remaining[i] - dt;
            if (left > 0f) {
                uids[out] = uids[i];
                remaining[out] = left;
                out++;
            }
        }
        size = out;
    }

    void clear() {
        size = 0;
    }

    int size() {
        return size;
    }
}
