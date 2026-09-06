package com.mymmer.castledefense.render;

import com.badlogic.gdx.utils.LongMap;
import com.mymmer.castledefense.defence.Projectile;
import com.mymmer.castledefense.entity.EntityList;

/**
 * Where a cannonball has been, for the smoke behind it.
 *
 * <h2>It lives here, not on the projectile</h2>
 *
 * <p>The source keeps {@code self.trail} on the {@code Projectile}, which makes
 * a cosmetic list a field of a gameplay object — and the moment that exists,
 * something can read it. Keeping it in the renderer makes it structurally
 * impossible for the shot's own motion to depend on its trail: the projectile
 * does not know this class exists.
 *
 * <p>Nothing here is authoritative. If the trail is empty, wrong, or a frame
 * behind, the only consequence is a shorter puff of smoke.
 *
 * <h2>Bounded, in every direction</h2>
 *
 * <ul>
 *   <li>{@link #SAMPLES} positions per projectile, in a fixed ring buffer that is
 *       allocated once per projectile and never grows;</li>
 *   <li>entries for projectiles that no longer exist are dropped in
 *       {@link #sample}, so a long Endless run cannot accumulate them;</li>
 *   <li>{@link #clear} on a new run, so a fresh game starts with no smoke
 *       trailing from the last one's shots.</li>
 * </ul>
 *
 * <p>Quality can thin the trail — {@code QualityConfig.trails()} switches it off
 * entirely on LOW — and that changes nothing but the picture.
 */
public final class ProjectileTrails {

    /** Positions kept per shot. The source keeps a comparable handful. */
    public static final int SAMPLES = 8;

    private static final class Trail {
        final float[] xs = new float[SAMPLES];
        final float[] ys = new float[SAMPLES];
        int count;
        int head;
        boolean seen;
    }

    private final LongMap<Trail> trails = new LongMap<>();

    /**
     * Records one position per live projectile, then forgets the dead.
     *
     * <p>Called once per rendered frame from the world renderer — never from the
     * simulation, which has no idea this exists.
     */
    public void sample(EntityList<Projectile> projectiles) {
        for (LongMap.Entry<Trail> e : trails.entries()) {
            e.value.seen = false;
        }
        for (int i = 0; i < projectiles.size(); i++) {
            Projectile p = projectiles.get(i);
            if (p == null || !p.isAlive() || p.kind()
                    != com.mymmer.castledefense.defence.ProjectileKind.CANNON) {
                continue;               // only the cannonball has a trail
            }
            Trail t = trails.get(p.uid());
            if (t == null) {
                t = new Trail();
                trails.put(p.uid(), t);
            }
            t.seen = true;
            t.xs[t.head] = p.x();
            t.ys[t.head] = p.y();
            t.head = (t.head + 1) % SAMPLES;
            if (t.count < SAMPLES) {
                t.count++;
            }
        }
        //  Drop what is gone.  Without this an Endless run's map would grow by
        //  one entry per shot for ever.
        LongMap.Keys keys = trails.keys();
        while (keys.hasNext) {
            long uid = keys.next();
            if (!trails.get(uid).seen) {
                keys.remove();
            }
        }
    }

    /** How many samples this shot has, oldest first. */
    public int length(long uid) {
        Trail t = trails.get(uid);
        return t == null ? 0 : t.count;
    }

    public float x(long uid, int index) {
        Trail t = trails.get(uid);
        return t == null ? 0f : t.xs[slot(t, index)];
    }

    public float y(long uid, int index) {
        Trail t = trails.get(uid);
        return t == null ? 0f : t.ys[slot(t, index)];
    }

    private static int slot(Trail t, int index) {
        int start = (t.head - t.count + SAMPLES) % SAMPLES;
        return (start + Math.max(0, Math.min(index, t.count - 1))) % SAMPLES;
    }

    /** Everything forgotten. Called when a run starts, so nothing carries over. */
    public void clear() {
        trails.clear();
    }

    /** How many shots are being tracked. For the overlay and the tests. */
    public int tracked() {
        return trails.size;
    }
}
