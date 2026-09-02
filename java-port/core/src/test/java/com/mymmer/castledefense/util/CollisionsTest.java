package com.mymmer.castledefense.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The collision helpers against the Python arithmetic they replace.
 *
 * <p>The boundary cases matter more than the obvious ones: {@code enemies.py}
 * uses inclusive {@code <=} everywhere, so a mob exactly one half-width away
 * counts as touching. Flipping one of those to {@code <} would pass every
 * "clearly inside" and "clearly outside" test while quietly changing which
 * projectile hits land.
 */
class CollisionsTest {

    @Test
    @DisplayName("pointInBox matches Python Enemy.covers, boundary included")
    void pointInBox() {
        // enemies.py:274  abs(px - x) <= w * 0.5 and abs(py - y) <= h * 0.5
        assertTrue(Collisions.pointInBox(100f, 100f, 100f, 100f, 40f, 60f), "centre");
        assertTrue(Collisions.pointInBox(120f, 100f, 100f, 100f, 40f, 60f), "exactly on +x edge");
        assertTrue(Collisions.pointInBox(80f, 130f, 100f, 100f, 40f, 60f), "exactly on the corner");
        assertFalse(Collisions.pointInBox(120.01f, 100f, 100f, 100f, 40f, 60f), "just past +x");
        assertFalse(Collisions.pointInBox(100f, 130.01f, 100f, 100f, 40f, 60f), "just past +y");
    }

    @Test
    @DisplayName("boxesOverlap matches Python Enemy.overlaps, touching counts")
    void boxesOverlap() {
        // enemies.py:283  abs(x - o.x) * 2 <= w + o.w and abs(y - o.y) * 2 <= h + o.h
        assertTrue(Collisions.boxesOverlap(0f, 0f, 40f, 40f, 10f, 10f, 40f, 40f), "interpenetrating");
        assertTrue(Collisions.boxesOverlap(0f, 0f, 40f, 40f, 40f, 0f, 40f, 40f), "edges exactly flush");
        assertFalse(Collisions.boxesOverlap(0f, 0f, 40f, 40f, 40.01f, 0f, 40f, 40f), "a hair apart in x");
        assertFalse(Collisions.boxesOverlap(0f, 0f, 40f, 40f, 0f, 40.01f, 40f, 40f), "a hair apart in y");
        // separation on one axis is enough, whatever the other does
        assertFalse(Collisions.boxesOverlap(0f, 0f, 40f, 40f, 0f, 100f, 40f, 40f), "same column, far above");
    }

    @Test
    @DisplayName("the two box conventions are not interchangeable")
    void centredAndAnchoredBoxesDiffer() {
        // pointInBox takes a centre; pointInAabb takes a bottom-left corner.
        // Same numbers, different answer -- which is exactly why they are named
        // differently rather than overloaded.
        assertTrue(Collisions.pointInBox(10f, 10f, 0f, 0f, 40f, 40f), "inside the centred box");
        assertFalse(Collisions.pointInAabb(-10f, -10f, 0f, 0f, 40f, 40f), "outside the anchored box");
        assertTrue(Collisions.pointInAabb(10f, 10f, 0f, 0f, 40f, 40f));
        assertTrue(Collisions.pointInAabb(0f, 40f, 0f, 0f, 40f, 40f), "corner is inside");
        assertFalse(Collisions.pointInAabb(40.5f, 20f, 0f, 0f, 40f, 40f));
    }

    @Test
    @DisplayName("aabbOverlap is inclusive on touching edges")
    void aabbOverlap() {
        assertTrue(Collisions.aabbOverlap(0f, 0f, 10f, 10f, 5f, 5f, 10f, 10f));
        assertTrue(Collisions.aabbOverlap(0f, 0f, 10f, 10f, 10f, 0f, 10f, 10f), "flush");
        assertFalse(Collisions.aabbOverlap(0f, 0f, 10f, 10f, 10.5f, 0f, 10f, 10f));
        assertFalse(Collisions.aabbOverlap(0f, 0f, 10f, 10f, 0f, -10.5f, 10f, 10f));
    }

    @Test
    @DisplayName("distanceSquared agrees with distance without the sqrt")
    void distances() {
        assertEquals(25f, Collisions.distanceSquared(0f, 0f, 3f, 4f), 1e-6f);
        assertEquals(5f, Collisions.distance(0f, 0f, 3f, 4f), 1e-6f);
        assertEquals(0f, Collisions.distanceSquared(7f, -2f, 7f, -2f), 0f);
        // the identity gameplay code relies on when it compares squared values
        float d = Collisions.distance(11f, -6f, -3f, 21f);
        assertEquals(d * d, Collisions.distanceSquared(11f, -6f, -3f, 21f), 1e-3f);
    }

    @Test
    @DisplayName("circle tests include the radius itself")
    void circles() {
        assertTrue(Collisions.pointInCircle(3f, 4f, 0f, 0f, 5f), "exactly on the rim");
        assertFalse(Collisions.pointInCircle(3f, 4f, 0f, 0f, 4.99f));
        assertTrue(Collisions.circlesOverlap(0f, 0f, 3f, 10f, 0f, 7f), "rims exactly touching");
        assertFalse(Collisions.circlesOverlap(0f, 0f, 3f, 10f, 0f, 6.9f));
        assertTrue(Collisions.circlesOverlap(0f, 0f, 3f, 1f, 1f, 1f), "one inside the other");
    }

    @Test
    @DisplayName("withinX ignores y entirely, as the Python fire zones do")
    void withinXIgnoresHeight() {
        // e.g. main.py's fire zone / lightning tests use abs(e.x - x) alone
        assertTrue(Collisions.withinX(100f, 160f, 60f), "on the boundary");
        assertTrue(Collisions.withinX(160f, 100f, 60f), "argument order is irrelevant");
        assertFalse(Collisions.withinX(100f, 161f, 60f));
        assertTrue(Collisions.withinX(0f, 0f, 0f), "zero range still matches an exact column");
    }

    @Test
    @DisplayName("clamp and lerp behave like the Python helpers")
    void clampAndLerp() {
        assertEquals(5f, Collisions.clamp(-2f, 5f, 10f), 0f);
        assertEquals(10f, Collisions.clamp(99f, 5f, 10f), 0f);
        assertEquals(7f, Collisions.clamp(7f, 5f, 10f), 0f);
        assertEquals(5, Collisions.clamp(-2, 5, 10));
        assertEquals(10, Collisions.clamp(99, 5, 10));

        assertEquals(0f, Collisions.lerp(0f, 100f, 0f), 1e-6f);
        assertEquals(100f, Collisions.lerp(0f, 100f, 1f), 1e-6f);
        assertEquals(25f, Collisions.lerp(0f, 100f, 0.25f), 1e-6f);
        // unclamped, exactly like the Python one -- overshoot is a caller's job
        assertEquals(200f, Collisions.lerp(0f, 100f, 2f), 1e-6f);
        assertEquals(-50f, Collisions.lerp(0f, 100f, -0.5f), 1e-6f);
    }
}
