package com.mymmer.castledefense.assets;

/**
 * Where a piece of <b>artwork</b> is aligned on a unit — the crown sprite, the
 * staff sprite, a muzzle flash, a health bar.
 *
 * <h2>Cosmetic. It has no gameplay authority whatsoever.</h2>
 *
 * <p>This comes from skin JSON, so an artist controls it, so it <b>must not</b>
 * decide anything the player can feel. Where a crown can be grabbed, where a
 * projectile is born, where a dropped item lands, what a hitbox covers — all of
 * those are {@link com.mymmer.castledefense.config.GameplayAnchor}s, defined in
 * gameplay data and identical under every skin.
 *
 * <pre>
 *   Boss gameplay box
 *     ├── gameplay crown anchor    fixed, from data/bosses.json
 *     └── skin visual crown offset cosmetic, from skin.json
 * </pre>
 *
 * <p>The separation is structural rather than a convention:
 *
 * <ul>
 *   <li>Gameplay code has no route to a {@link UnitVisual} — no context, no
 *       service and no entity exposes one.</li>
 *   <li>{@code ArchitectureTest} fails the build if a gameplay package imports
 *       this one.</li>
 *   <li>The accessors here are named {@code visual*} and {@code draw*}, so a
 *       call site that reads one is visibly a rendering call site.</li>
 * </ul>
 *
 * <p>There is deliberately no {@code worldX}/{@code worldY}: those names invited
 * exactly the mistake this class must not permit. Use {@link #drawX} and
 * {@link #drawY}, from the renderer.
 *
 * <p>Coordinates are normalised to the unit's <em>gameplay</em> box, so a 4x
 * larger PNG does not move the artwork relative to the unit, and nothing needs
 * rescaling when the device changes.
 */
public final class AttachmentPoint {

    private final String name;
    private final float x;
    private final float y;

    public AttachmentPoint(String name, float x, float y) {
        this.name = name;
        this.x = x;
        this.y = y;
    }

    public String name() {
        return name;
    }

    /** Normalised x for <b>drawing</b>, 0 = left edge of the gameplay box. */
    public float visualX() {
        return x;
    }

    /** Normalised y for <b>drawing</b>, 0 = bottom edge of the gameplay box. */
    public float visualY() {
        return y;
    }

    /**
     * Where to draw the artwork, in world x. <b>Rendering only.</b>
     *
     * <p>If you are reaching for this from gameplay, you want a
     * {@link com.mymmer.castledefense.config.GameplayAnchor} instead.
     */
    public float drawX(float cx, float boxWidth) {
        return cx + (x - 0.5f) * boxWidth;
    }

    /** Where to draw the artwork, in world y. <b>Rendering only.</b> */
    public float drawY(float cy, float boxHeight) {
        return cy - (y - 0.5f) * boxHeight;
    }

    @Override
    public String toString() {
        return name + "(visual " + x + ", " + y + ")";
    }
}
