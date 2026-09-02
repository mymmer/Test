package com.mymmer.castledefense.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Snapshot traversal is not a convenience here — several Python behaviours
 * depend on its exact semantics, so they are pinned down.
 */
class EntityListTest {

    private static final class Probe extends Entity {
        final String name;

        Probe(String name) {
            this.name = name;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private static EntityList<Probe> listOf(String... names) {
        EntityList<Probe> list = new EntityList<>();
        for (String n : names) {
            list.add(new Probe(n));
        }
        return list;
    }

    @Test
    @DisplayName("insertion order is preserved, and a sweep does not disturb it")
    void insertionOrderSurvivesSweep() {
        EntityList<Probe> list = listOf("a", "b", "c", "d", "e");
        list.get(1).markDead();
        list.get(3).markDead();
        assertEquals(2, list.sweep());
        assertEquals(3, list.size());
        // a swap-remove would leave a, e, c here -- and quietly change the
        // order that crowd separation and tower targeting depend on
        assertEquals("a", list.get(0).name);
        assertEquals("c", list.get(1).name);
        assertEquals("e", list.get(2).name);
    }

    @Test
    @DisplayName("a snapshot still contains entities removed during traversal")
    void snapshotKeepsRemovedEntities() {
        EntityList<Probe> list = listOf("a", "b", "c");
        int seen = 0;
        try (EntityList<Probe>.Snapshot snap = list.beginSnapshot()) {
            for (int i = 0; i < snap.size(); i++) {
                Probe p = snap.get(i);
                seen++;
                if (i == 0) {
                    // as Outpost.trap() does: remove the current entity mid-loop
                    p.markDead();
                    list.sweep();
                }
            }
        }
        assertEquals(3, seen,
                "removing the current entity must not make the loop skip its neighbour");
        assertEquals(2, list.size());
    }

    @Test
    @DisplayName("entities added during traversal are not stepped on their spawn frame")
    void snapshotExcludesNewEntities() {
        EntityList<Probe> list = listOf("a", "b");
        int seen = 0;
        try (EntityList<Probe>.Snapshot snap = list.beginSnapshot()) {
            for (int i = 0; i < snap.size(); i++) {
                seen++;
                if (i == 0) {
                    // a Necromancer summoning, the Lich raising dead, the horn
                    list.add(new Probe("spawned"));
                    list.add(new Probe("spawned2"));
                }
            }
        }
        assertEquals(2, seen, "the snapshot is frozen at the moment it began");
        assertEquals(4, list.size(), "though the additions are certainly there");

        // and they are visible on the next pass
        try (EntityList<Probe>.Snapshot snap = list.beginSnapshot()) {
            assertEquals(4, snap.size());
        }
    }

    @Test
    @DisplayName("snapshots keep the list's order")
    void snapshotPreservesOrder() {
        EntityList<Probe> list = listOf("a", "b", "c");
        try (EntityList<Probe>.Snapshot snap = list.beginSnapshot()) {
            assertEquals("a", snap.get(0).name);
            assertEquals("b", snap.get(1).name);
            assertEquals("c", snap.get(2).name);
        }
    }

    @Test
    @DisplayName("snapshots nest, because one step can trigger another traversal")
    void snapshotsNest() {
        EntityList<Probe> list = listOf("a", "b", "c");
        try (EntityList<Probe>.Snapshot outer = list.beginSnapshot()) {
            assertEquals(1, list.openSnapshots());
            try (EntityList<Probe>.Snapshot inner = list.beginSnapshot()) {
                // e.g. a Volatile detonating inside the enemy update loop
                assertEquals(2, list.openSnapshots());
                assertEquals(outer.size(), inner.size());
            }
            assertEquals(1, list.openSnapshots());
        }
        assertEquals(0, list.openSnapshots(), "every snapshot must be returned");
        assertTrue(list.peakOpenSnapshots() >= 2);
    }

    @Test
    @DisplayName("snapshot buffers are reused rather than allocated per frame")
    void snapshotBuffersAreReused() {
        EntityList<Probe> list = listOf("a", "b", "c");
        EntityList<Probe>.Snapshot first = list.beginSnapshot();
        first.close();
        EntityList<Probe>.Snapshot second = list.beginSnapshot();
        assertSame(first, second,
                "the pool must hand the same buffer back, not allocate a new one");
        second.close();
    }

    @Test
    @DisplayName("a closed snapshot releases its references")
    void closedSnapshotIsEmpty() {
        EntityList<Probe> list = listOf("a");
        EntityList<Probe>.Snapshot snap = list.beginSnapshot();
        assertTrue(snap.isOpen());
        snap.close();
        assertFalse(snap.isOpen());
        assertEquals(0, snap.size(), "a closed snapshot must not pin dead entities");
        snap.close();       // closing twice is harmless
    }

    @Test
    @DisplayName("markDead is idempotent and does not remove anything by itself")
    void markDeadSemantics() {
        EntityList<Probe> list = listOf("a");
        Probe p = list.get(0);
        p.markDead();
        p.markDead();
        assertFalse(p.isAlive());
        assertEquals(1, list.size(), "removal only happens at the sweep");
        assertEquals(0, list.aliveCount());
        list.sweep();
        assertEquals(0, list.size());
    }

    @Test
    @DisplayName("uids are unique and never reused")
    void uidsAreUnique() {
        EntityList<Probe> list = listOf("a", "b", "c");
        long first = list.get(0).uid();
        long second = list.get(1).uid();
        assertTrue(second > first, "uids increase monotonically");
        list.get(0).markDead();
        list.sweep();
        Probe fresh = new Probe("d");
        assertTrue(fresh.uid() > second,
                "a fresh entity must never inherit a dead one's identity");
    }

    @Test
    @DisplayName("clear marks everything dead so stale references cannot act")
    void clearMarksDead() {
        EntityList<Probe> list = listOf("a", "b");
        Probe held = list.get(0);
        list.clear();
        assertEquals(0, list.size());
        assertFalse(held.isAlive(),
                "anything still holding a reference must see it is gone");
    }
}
