package com.mymmer.castledefense.config;

/**
 * A <b>gameplay-authoritative</b> point on a unit's hitbox.
 *
 * <p>This is where something <em>happens</em>: where a projectile leaves a
 * barrel, where a crown can be grabbed, where a dropped item first appears,
 * where a claw can be battered. Its coordinates come from gameplay
 * configuration and are <b>identical under every skin</b>.
 *
 * <h2>Not to be confused with {@code assets.AttachmentPoint}</h2>
 *
 * <table>
 *   <tr><th></th><th>{@code GameplayAnchor}</th><th>{@code AttachmentPoint}</th></tr>
 *   <tr><td>Defined in</td><td>gameplay data ({@code data/*.json}) or code</td>
 *       <td>skin data ({@code skins/&lt;id&gt;/skin.json})</td></tr>
 *   <tr><td>Authority</td><td><b>gameplay</b></td><td><b>cosmetic</b></td></tr>
 *   <tr><td>Changing a skin</td><td>cannot move it</td><td>moves it</td></tr>
 *   <tr><td>Read by</td><td>gameplay</td><td>the renderer, only</td></tr>
 * </table>
 *
 * <p>The separation is structural, not a convention: gameplay code has no route
 * to a {@code UnitVisual} at all — no context, no service and no entity exposes
 * one — and {@code ArchitectureTest} fails the build if a gameplay package ever
 * imports the assets package. An artist can move the crown artwork anywhere;
 * where the player has to click to steal it does not move.
 *
 * <p>Coordinates are normalised to the gameplay box: (0,0) is the bottom-left of
 * the hitbox, (1,1) the top-right. Values slightly outside are legitimate — a
 * Lich Lord's staff head genuinely sits above and to the right of his box.
 */
public final class GameplayAnchor {

    /** Stable semantic name: {@code crown}, {@code staff}, {@code claws}, {@code muzzle}. */
    private final String name;
    private final float x;
    private final float y;

    public GameplayAnchor(String name, float x, float y) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("a gameplay anchor needs a stable name");
        }
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

    /**
     * The authoritative world x, for a box of {@code boxWidth} centred at
     * {@code cx}.
     *
     * <p>Note the y convention: the port simulates in pygame space, where y
     * grows downward, so {@code y() = 1} (the top of the box) is at
     * {@code cy - boxHeight/2}.
     */
    public float worldX(float cx, float boxWidth) {
        return cx + (x - 0.5f) * boxWidth;
    }

    /** The authoritative world y. See {@link #worldX} for the y convention. */
    public float worldY(float cy, float boxHeight) {
        return cy - (y - 0.5f) * boxHeight;
    }

    @Override
    public String toString() {
        return name + "@(" + x + ", " + y + ")";
    }
}
