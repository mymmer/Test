package com.mymmer.castledefense.boss;

import com.badlogic.gdx.utils.Array;
import com.mymmer.castledefense.defence.Projectile;
import com.mymmer.castledefense.entity.EntityList;

/**
 * Tracks the bosses on the field and purges everything a dead one owned.
 *
 * <h2>A collection, never a {@code currentBoss}</h2>
 *
 * <p>From wave 20 the game fields <b>two</b> bosses at once, and the Python HUD
 * bug of drawing a bar for only the first is exactly what a single-slot design
 * produces. Nothing here is a single slot: 0, 1 or 2 bosses are all ordinary
 * cases, and more would be too. Per-boss state lives on the boss instance; this
 * class only tracks which ones exist.
 *
 * <h2>Purging is ownership, not garbage collection</h2>
 *
 * <p>A dead boss leaves references behind in five places, and every one is a bug
 * before it is a leak: the horde, the cursor, its dropped regalia, its
 * projectiles, and the registry itself. {@link #purge} drops all of them,
 * matching projectiles and items by <b>owner uid</b> rather than by object
 * identity, so a second Troll King can never inherit the first one's crown.
 *
 * <p>The Java GC would eventually reclaim the memory and would never fix the
 * gameplay: a stale {@code grabbed} reference means the player is holding a
 * corpse, and a surviving projectile means a dead boss is still shooting.
 */
public final class BossRegistry {

    /**
     * Something the cursor might still be holding on to when a boss dies.
     *
     * <p>The interaction layer implements it. Kept as a narrow callback rather
     * than a direct dependency so the boss package does not have to know what a
     * cursor is.
     */
    public interface InteractionOwner {
        /** Drops any reference to this boss, or to anything it owned. */
        void releaseBoss(Boss boss);

        /** Drops any reference to this item. */
        void releaseItem(DroppedItem item);
    }

    private final Array<Boss> live = new Array<>(false, 4);
    private final Array<InteractionOwner> interactions = new Array<>(false, 2);

    /** Registers a listener that may hold boss references. */
    public void addInteractionOwner(InteractionOwner owner) {
        if (owner != null && !interactions.contains(owner, true)) {
            interactions.add(owner);
        }
    }

    public void removeInteractionOwner(InteractionOwner owner) {
        interactions.removeValue(owner, true);
    }

    /** A boss has walked on. */
    public void register(Boss boss) {
        if (boss != null && !live.contains(boss, true)) {
            live.add(boss);
        }
    }

    /** Every boss currently alive, in arrival order. */
    public Array<Boss> liveBosses() {
        return live;
    }

    public int liveCount() {
        int n = 0;
        for (int i = 0; i < live.size; i++) {
            if (live.get(i).isAlive()) {
                n++;
            }
        }
        return n;
    }

    /** True while any boss is on the field. Python {@code boss_spawned}. */
    public boolean anyLive() {
        return liveCount() > 0;
    }

    /** Is this specific boss still tracked and alive? */
    public boolean isLive(Boss boss) {
        return live.contains(boss, true) && boss.isAlive();
    }

    /**
     * Drops every reference the run still holds to one boss.
     *
     * <p>Safe to call more than once, and safe to call on a boss that was never
     * registered — a purge that threw would be worse than one that did nothing.
     *
     * @param horde       the enemy list the boss is in
     * @param projectiles every projectile in flight
     * @param items       every dropped regalia item
     */
    public void purge(Boss boss, EntityList<? extends com.mymmer.castledefense.enemy.Enemy> horde,
                      EntityList<Projectile> projectiles, EntityList<DroppedItem> items) {
        if (boss == null) {
            return;
        }
        long uid = boss.uid();

        // 1. the boss itself, off the field and out of the enemy list
        boss.markDead();
        if (horde != null) {
            removeFromHorde(horde, boss);
        }

        // 2. anything the cursor is still holding on to
        for (int i = 0; i < interactions.size; i++) {
            interactions.get(i).releaseBoss(boss);
        }

        // 3. its regalia -- including a piece the player may be carrying.
        //    Matched by OWNER UID: object identity would work today and would
        //    quietly stop working the moment an item outlived a reference swap.
        if (items != null) {
            for (int i = 0; i < items.size(); i++) {
                DroppedItem it = items.get(i);
                if (it.ownerUid() == uid) {
                    it.markDead();
                    for (int j = 0; j < interactions.size; j++) {
                        interactions.get(j).releaseItem(it);
                    }
                }
            }
            items.sweep();
        }

        // 4. its projectiles -- a dead boss's fire goes out with it, and
        //    another boss's does NOT
        if (projectiles != null) {
            for (int i = 0; i < projectiles.size(); i++) {
                Projectile p = projectiles.get(i);
                if (p.ownerUid() == uid) {
                    p.markDead();
                }
            }
            projectiles.sweep();
        }

        // 5. and the registry's own record
        live.removeValue(boss, true);
    }

    private static void removeFromHorde(
            EntityList<? extends com.mymmer.castledefense.enemy.Enemy> horde, Boss boss) {
        for (int i = 0; i < horde.size(); i++) {
            if (horde.get(i) == boss) {
                horde.unsafeItems().removeIndex(i);
                return;
            }
        }
    }

    /**
     * Purges every boss that has already died.
     *
     * <p>Python calls this before creating a new boss, so an arriving boss never
     * shares the field with a corpse.
     */
    public void purgeDead(EntityList<? extends com.mymmer.castledefense.enemy.Enemy> horde,
                          EntityList<Projectile> projectiles, EntityList<DroppedItem> items) {
        for (int i = live.size - 1; i >= 0; i--) {
            Boss b = live.get(i);
            if (!b.isAlive()) {
                purge(b, horde, projectiles, items);
            }
        }
    }

    /** Forgets everything. Used when a run ends. */
    public void clear() {
        live.clear();
    }

    /** One line per live boss, for a crash log or a bug report. */
    public String describe() {
        if (live.size == 0) {
            return "bosses: none";
        }
        StringBuilder sb = new StringBuilder("bosses:");
        for (int i = 0; i < live.size; i++) {
            sb.append("\n  ").append(live.get(i).describe());
        }
        return sb.toString();
    }
}
