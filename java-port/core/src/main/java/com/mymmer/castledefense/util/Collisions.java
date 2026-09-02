package com.mymmer.castledefense.util;

/**
 * Primitive collision and geometry maths.
 *
 * <p>Every method takes and returns primitives. Nothing here allocates, and no
 * caller in a gameplay loop may allocate a {@code Rectangle} or {@code Vector2}
 * to use it. That is not a style preference: the Python source records that
 * building a {@code pygame.Rect} inside the projectile/enemy test "was the
 * single biggest cost in the profile at high waves", and the same call runs once
 * per projectile per enemy per frame here.
 *
 * <p>The comparisons match the Python ones exactly, including the inclusive
 * {@code <=} boundaries — a mob exactly one half-width away counts as touching
 * in both implementations.
 *
 * <p>Deliberately absent: spatial grids, quadtrees, any broadphase. Those change
 * which pairs are tested and in what order, so they are Phase 12 work behind
 * parity tests, not Phase 4 work.
 */
public final class Collisions {

    private Collisions() {
    }

    /**
     * Point inside a box given by its centre and full size.
     *
     * <p>Python {@code Enemy.covers(px, py)}:
     * {@code abs(px - x) <= w * 0.5 and abs(py - y) <= h * 0.5}.
     */
    public static boolean pointInBox(float px, float py,
                                     float cx, float cy, float width, float height) {
        return Math.abs(px - cx) <= width * 0.5f && Math.abs(py - cy) <= height * 0.5f;
    }

    /**
     * Overlap of two centred boxes.
     *
     * <p>Python {@code Enemy.overlaps(other)}:
     * {@code abs(x - o.x) * 2 <= w + o.w and abs(y - o.y) * 2 <= h + o.h}.
     */
    public static boolean boxesOverlap(float ax, float ay, float aw, float ah,
                                       float bx, float by, float bw, float bh) {
        return Math.abs(ax - bx) * 2f <= aw + bw && Math.abs(ay - by) * 2f <= ah + bh;
    }

    /** Point inside a bottom-left-anchored AABB, the libGDX rectangle convention. */
    public static boolean pointInAabb(float px, float py,
                                      float x, float y, float width, float height) {
        return px >= x && px <= x + width && py >= y && py <= y + height;
    }

    /** Overlap of two bottom-left-anchored AABBs. */
    public static boolean aabbOverlap(float ax, float ay, float aw, float ah,
                                      float bx, float by, float bw, float bh) {
        return ax <= bx + bw && ax + aw >= bx && ay <= by + bh && ay + ah >= by;
    }

    /** Squared distance — prefer this to {@link #distance} in comparisons. */
    public static float distanceSquared(float ax, float ay, float bx, float by) {
        float dx = ax - bx;
        float dy = ay - by;
        return dx * dx + dy * dy;
    }

    /** True distance. Only where the value itself is needed, e.g. falloff. */
    public static float distance(float ax, float ay, float bx, float by) {
        return (float) Math.sqrt(distanceSquared(ax, ay, bx, by));
    }

    /** Point within {@code radius} of a centre. */
    public static boolean pointInCircle(float px, float py, float cx, float cy, float radius) {
        return distanceSquared(px, py, cx, cy) <= radius * radius;
    }

    /** Two circles touching or overlapping. */
    public static boolean circlesOverlap(float ax, float ay, float ar,
                                         float bx, float by, float br) {
        float r = ar + br;
        return distanceSquared(ax, ay, bx, by) <= r * r;
    }

    /**
     * Point inside a pygame {@code Rect}, with pygame's exact semantics.
     *
     * <p>Not a duplicate of {@link #pointInAabb}: {@code Rect.collidepoint} is
     * <b>half-open</b> ({@code left <= px < right}) and a {@code Rect} stores
     * <b>integers</b>, so the float rect the caller thinks it has was truncated
     * when it was built. Both details are load-bearing — they decide whether a
     * hostile shell that lands exactly on a tower's right edge hits the tower or
     * carries on into the wall — so the Python call sites that use a Rect
     * ({@code Castle.tower_at}, {@code Outpost.body_rect}) use this and the ones
     * that use raw arithmetic use the methods above.
     *
     * @param rx left edge, already truncated to an int as pygame would
     * @param ry top edge in pygame's y-down space, already truncated
     */
    public static boolean pointInPygameRect(float px, float py,
                                            int rx, int ry, int rw, int rh) {
        return px >= rx && px < rx + rw && py >= ry && py < ry + rh;
    }

    /**
     * Horizontal-only proximity.
     *
     * <p>Its own method because the Python game uses it constantly — fire zones,
     * lightning, tornadoes and ally engagement all test {@code abs(e.x - x)}
     * alone, ignoring height entirely.
     */
    public static boolean withinX(float ax, float bx, float range) {
        return Math.abs(ax - bx) <= range;
    }

    /** Python {@code clamp(v, lo, hi)}. */
    public static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    public static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** Python {@code lerp(a, b, t)}, unclamped as there. */
    public static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }
}
