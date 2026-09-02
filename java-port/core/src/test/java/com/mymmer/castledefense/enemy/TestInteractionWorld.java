package com.mymmer.castledefense.enemy;

import com.mymmer.castledefense.input.Pointer;
import com.mymmer.castledefense.input.TestPointers;
import com.mymmer.castledefense.interaction.CursorInteraction;
import com.mymmer.castledefense.interaction.PointerVelocity;

/**
 * A world plus a cursor, for the interaction tests.
 *
 * <p>The handler is called with a real {@link Pointer} carrying world
 * coordinates, which is exactly what {@code InputRouter} hands it in production.
 * The routing itself — ownership, sticky UI consumption, cancellation — has its
 * own suite in {@code InputRouterTest}; this one is about what the cursor then
 * does to the world.
 *
 * <p>The flick velocity is injected as an exact world-space pair. Synthesising a
 * drag path and hoping the tracker produces the intended number would be testing
 * {@code TouchVelocityTracker}, which also has its own suite.
 */
public final class TestInteractionWorld {

    public final TestEnemyWorld world;
    public final CursorInteraction cursor;

    private float velocityX;
    private float velocityY;
    private Pointer pointer = TestPointers.at(0, 0f, 0f);

    public TestInteractionWorld() {
        this(TestEnemyWorld.SEED);
    }

    public TestInteractionWorld(long seed) {
        this.world = new TestEnemyWorld(seed);
        PointerVelocity velocity = (pointerId, out) -> {
            out[0] = velocityX;
            out[1] = velocityY;
        };
        this.cursor = new CursorInteraction(world, velocity);
        world.grabCapacity = cursor.grabCapacity();
    }

    /** The flick velocity a release will read, in world units per second. */
    public void setVelocity(float vx, float vy) {
        this.velocityX = vx;
        this.velocityY = vy;
    }

    public boolean press(float worldX, float worldY) {
        pointer = TestPointers.at(0, worldX, worldY);
        return cursor.onWorldPress(pointer);
    }

    public void drag(float worldX, float worldY) {
        TestPointers.moveTo(pointer, worldX, worldY);
        cursor.onWorldDrag(pointer);
        cursor.update(1f / 60f, true, worldX, worldY);
    }

    public void release() {
        cursor.onWorldRelease(pointer);
    }

    public void cancel() {
        cursor.onWorldCancel(pointer);
    }
}
