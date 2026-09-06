package com.mymmer.castledefense.render;

import com.mymmer.castledefense.config.GameConfig;

/**
 * The one place that knows which way up the world is.
 *
 * <h2>Two conventions, one conversion</h2>
 *
 * <p>The simulation keeps Pygame's coordinates, because that is what every ported
 * formula, every parity fixture and every gameplay constant is written in:
 * <b>y grows downward</b>, the ground is at {@code GROUND_Y = 620}, and the sky
 * is at small y. libGDX's world is the other way up: y grows from the bottom.
 *
 * <p>Changing the simulation to match libGDX was never an option — it would
 * invalidate 771 parity fixtures and every constant transcribed from the source.
 * So the conversion happens here, at the boundary, and nowhere else:
 *
 * <pre>
 *   drawY = WORLD_HEIGHT - gameplayY
 * </pre>
 *
 * <p>Every painter works in <b>draw space</b> (y up) and every gameplay read goes
 * through {@link #toDrawY}. Scattering the subtraction through the painters is
 * how half a renderer ends up upside down — which is exactly what the first
 * Phase 11 capture showed, with the horde walking along the top of the sky.
 *
 * <h2>Angles flip too</h2>
 *
 * <p>A velocity's direction is not preserved by the flip: a projectile falling in
 * gameplay space is rising in draw space. {@link #toDrawAngle} negates the y
 * component, which is why an arrow points where it is going rather than mirrored
 * about the horizon.
 */
public final class WorldGeometry {

    /** The ground line, in draw space. {@code GROUND_Y = 620} from the top. */
    public static final float GROUND = GameConfig.WORLD_HEIGHT - 620f;

    /** The top of the curtain wall, in draw space. */
    public static final float WALL_TOP = GameConfig.WORLD_HEIGHT - 350f;

    /** The top of the keep, in draw space. */
    public static final float KEEP_TOP = GameConfig.WORLD_HEIGHT - 230f;

    private WorldGeometry() {
    }

    /** A gameplay y (down from the top) as a draw y (up from the bottom). */
    public static float toDrawY(float gameplayY) {
        return GameConfig.WORLD_HEIGHT - gameplayY;
    }

    /** The same, for a value already interpolated. */
    public static float toDrawY(double gameplayY) {
        return GameConfig.WORLD_HEIGHT - (float) gameplayY;
    }

    /**
     * A gameplay velocity's heading, in draw space.
     *
     * <p>{@code atan2(-vy, vx)}: down in the simulation is up on the screen.
     */
    public static float toDrawAngle(float vx, float vy) {
        return com.badlogic.gdx.math.MathUtils.atan2(-vy, vx);
    }
}
