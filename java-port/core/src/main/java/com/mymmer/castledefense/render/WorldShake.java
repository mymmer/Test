package com.mymmer.castledefense.render;

/**
 * Screen shake, as a world-camera offset.
 *
 * <h2>The choice, and why</h2>
 *
 * <p>The source renders the scene to a surface and blits it at a random offset.
 * Three ways to get the same result in libGDX were available:
 *
 * <ol>
 *   <li><b>Move the world camera</b> for the world pass and put it back. No
 *       render target, no lifecycle, no resize handling, no second sampling of
 *       the scene, and the UI is untouched because it has its own camera.</li>
 *   <li>Render to a {@code FrameBuffer} and composite it offset. Faithful to the
 *       source's mechanism, but it adds a GPU resource to create, resize,
 *       dispose and reason about — and a full-screen resample every frame,
 *       shaking or not.</li>
 *   <li>Offset the entities. Rejected outright: that is moving the game.</li>
 * </ol>
 *
 * <p>The first was chosen. It is the simplest thing that preserves world-only
 * shake, UI stability, clipping, viewport behaviour and — the property that
 * matters most — pointer correctness.
 *
 * <h2>Nothing gameplay can feel</h2>
 *
 * <p>This class writes to exactly one thing: the world camera's position, which
 * it then puts back. It never touches an entity, a hitbox, or a stored
 * coordinate. Because {@link #clear} runs before the frame ends, every
 * unprojection in the next frame's input uses an unshaken camera — so a finger
 * aimed at world x still lands on world x while the picture is moving, which
 * {@code ShakeTest} asserts by unprojecting the same screen pixel with the shake
 * applied and with it cleared.
 *
 * <h2>The offset is decoration randomness</h2>
 *
 * <p>The source draws it from the global generator, which makes the gameplay
 * sequence depend on how many frames were rendered while the screen shook. Here
 * it comes from {@link VisualRng}, which reaches only the decoration stream.
 */
public final class WorldShake {

    /** Below this the source does not offset at all. {@code if self.shake > 0.4}. */
    public static final float THRESHOLD = 0.4f;

    private float x;
    private float y;
    private boolean applied;

    /**
     * Offsets the camera for this frame's world pass.
     *
     * @param amount the gameplay shake magnitude, read not written
     */
    public void apply(ViewportSet viewports, float amount, VisualRng rng) {
        clear(viewports);           // never stack two frames' offsets
        if (viewports == null || amount <= THRESHOLD) {
            x = 0f;
            y = 0f;
            return;
        }
        x = rng.uniform(-amount, amount);
        y = rng.uniform(-amount, amount);
        viewports.getWorldCamera().position.add(x, y, 0f);
        viewports.getWorldCamera().update();
        applied = true;
    }

    /** Puts the camera back. Nothing outside the world pass sees the offset. */
    public void clear(ViewportSet viewports) {
        if (!applied || viewports == null) {
            applied = false;
            return;
        }
        viewports.getWorldCamera().position.sub(x, y, 0f);
        viewports.getWorldCamera().update();
        applied = false;
    }

    /** This frame's offset, for the two HUD widgets the source shakes too. */
    public float x() {
        return x;
    }

    public float y() {
        return y;
    }

    /** Whether the camera is currently displaced. For the tests. */
    public boolean isApplied() {
        return applied;
    }
}
