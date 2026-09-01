package com.mymmer.castledefense.assets;

/**
 * Where something attaches to a unit — the Troll King's crown, the Lich Lord's
 * staff, a health bar, a projectile origin, a tower muzzle.
 *
 * <p>Coordinates are <b>normalised to the unit's gameplay box</b>: (0,0) is the
 * bottom-left of the hitbox, (1,1) the top-right. Two consequences, both
 * deliberate:
 *
 * <ul>
 *   <li>Replacing the artwork cannot move an attachment. A 4x larger Troll King
 *       PNG still has its crown at y=0.91 of the same gameplay box, so
 *       {@code TrollKing} never changes.</li>
 *   <li>Attachments are resolution-independent, so nothing needs rescaling when
 *       the device changes.</li>
 * </ul>
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

    /** Normalised x within the gameplay box, 0 = left edge, 1 = right edge. */
    public float x() {
        return x;
    }

    /** Normalised y within the gameplay box, 0 = bottom edge, 1 = top edge. */
    public float y() {
        return y;
    }

    /** Absolute world x for a unit whose box is centred at {@code cx}. */
    public float worldX(float cx, float boxWidth) {
        return cx + (x - 0.5f) * boxWidth;
    }

    /** Absolute world y for a unit whose box is centred at {@code cy}. */
    public float worldY(float cy, float boxHeight) {
        return cy + (y - 0.5f) * boxHeight;
    }

    @Override
    public String toString() {
        return name + "(" + x + ", " + y + ")";
    }
}
