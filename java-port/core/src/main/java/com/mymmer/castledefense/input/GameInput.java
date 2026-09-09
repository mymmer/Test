package com.mymmer.castledefense.input;

import com.badlogic.gdx.InputProcessor;
import com.mymmer.castledefense.render.ViewportSet;

/**
 * The logical pointer model both platforms feed.
 *
 * <p>The canonical pipeline, and the only one gameplay may see:
 *
 * <pre>
 *   physical screen coordinates
 *        -&gt; viewport conversion        (ViewportSet)
 *        -&gt; virtual world / UI units   (Pointer)
 *        -&gt; GameInput
 *        -&gt; InputRouter                (UI first, then world)
 *        -&gt; logical interaction
 * </pre>
 *
 * <p>Desktop and Android share this class outright rather than each having their
 * own path. A mouse is simply pointer 0. That is on purpose: two parallel
 * implementations drift, and the one that gets less use drifts silently.
 *
 * <h2>Interaction ownership</h2>
 *
 * <p>At most one pointer owns the world interaction at a time. Once pointer 2
 * has grabbed something, pointer 3 cannot take it, and pointer 3's release does
 * not end it — only the owner's release or cancellation does. Multitouch is
 * therefore safe by construction even though the game expects one manipulation
 * at a time.
 *
 * <h2>Ownership of this object</h2>
 * <ul>
 *   <li><b>Created by</b> the launcher (via {@code CastleDefenseGame}).</li>
 *   <li><b>Owned by</b> {@code CastleDefenseGame}.</li>
 *   <li><b>Mutated by</b> libGDX's input thread through {@link InputProcessor}
 *       callbacks, and by {@code CastleDefenseGame} once per step via
 *       {@link #endStep()}.</li>
 *   <li><b>Read by</b> {@code InputRouter} and, through it, gameplay.</li>
 * </ul>
 */
public class GameInput implements InputProcessor {

    /** libGDX tracks at most 20 simultaneous touches. */
    public static final int MAX_POINTERS = 20;

    private final ViewportSet viewports;
    private final Pointer[] pointers = new Pointer[MAX_POINTERS];
    private final TouchVelocityTracker[] velocity = new TouchVelocityTracker[MAX_POINTERS];
    private final float[] velocityOut = new float[2];

    private int interactionOwner = Pointer.NO_OWNER;
    private float clockSeconds;

    public GameInput(ViewportSet viewports) {
        if (viewports == null) {
            throw new IllegalArgumentException("viewports must not be null");
        }
        this.viewports = viewports;
        for (int i = 0; i < MAX_POINTERS; i++) {
            pointers[i] = new Pointer(i);
            velocity[i] = new TouchVelocityTracker();
        }
    }

    /**
     * Advances the input clock. Driven by simulation time, never wall-clock, so
     * throw velocities are measured on the same clock as the physics they feed.
     */
    public void setClock(float simulationTimeSeconds) {
        clockSeconds = simulationTimeSeconds;
    }

    public float clock() {
        return clockSeconds;
    }

    public Pointer pointer(int id) {
        if (id < 0 || id >= MAX_POINTERS) {
            return null;
        }
        return pointers[id];
    }

    /** Velocity history for a pointer, in world units. */
    public TouchVelocityTracker velocityTracker(int id) {
        if (id < 0 || id >= MAX_POINTERS) {
            return null;
        }
        return velocity[id];
    }

    /** Release velocity of a pointer, world units per second, into {@code out}. */
    public void releaseVelocity(int id, float[] out) {
        TouchVelocityTracker t = velocityTracker(id);
        if (t == null) {
            out[0] = 0f;
            out[1] = 0f;
            return;
        }
        t.velocity(out);
    }

    // --- interaction ownership ---------------------------------------------

    /** The pointer that owns the world interaction, or {@link Pointer#NO_OWNER}. */
    public int interactionOwner() {
        return interactionOwner;
    }

    public boolean hasInteraction() {
        return interactionOwner != Pointer.NO_OWNER;
    }

    /**
     * Claims the world interaction for a pointer.
     *
     * @return false when another pointer already owns it, or this one was
     *         consumed by the UI
     */
    public boolean acquireInteraction(int pointerId) {
        Pointer p = pointer(pointerId);
        if (p == null || p.isConsumedByUi()) {
            return false;
        }
        if (interactionOwner != Pointer.NO_OWNER && interactionOwner != pointerId) {
            return false;
        }
        interactionOwner = pointerId;
        return true;
    }

    /** Releases the interaction if {@code pointerId} is the owner. */
    public boolean releaseInteraction(int pointerId) {
        if (interactionOwner != pointerId) {
            return false;
        }
        interactionOwner = Pointer.NO_OWNER;
        return true;
    }

    public boolean ownsInteraction(int pointerId) {
        return interactionOwner == pointerId;
    }

    /** Marks a pointer as claimed by a UI element, for its whole lifetime. */
    public void consume(int pointerId, Object consumer) {
        Pointer p = pointer(pointerId);
        if (p != null) {
            p.consume(consumer);
        }
    }

    /**
     * Ends the step: clears edge flags and rolls delta baselines forward.
     *
     * <p>Called once per <em>simulation step</em>, not per rendered frame, so a
     * press is visible to exactly one step however fast the display runs.
     */
    public void endStep() {
        for (int i = 0; i < MAX_POINTERS; i++) {
            pointers[i].endStep();
        }
    }

    /** Drops all pointer state — used on focus loss and when a run resets. */
    public void reset() {
        for (int i = 0; i < MAX_POINTERS; i++) {
            pointers[i].reset();
            velocity[i].reset();
        }
        interactionOwner = Pointer.NO_OWNER;
    }

    /**
     * Ends a pointer as if the platform cancelled it.
     *
     * <p>The desktop focus-loss guard and Android's touch-cancel both land here.
     * The Python game has the same safety net: {@code run()} checks every frame
     * whether the mouse button is still down and releases the grab if not.
     */
    public void cancelPointer(int pointerId) {
        Pointer p = pointer(pointerId);
        if (p == null || !p.isDown()) {
            return;
        }
        //  Same rule as touchUp: mark it released-and-cancelled, and let the
        //  router tell the owner before ownership is dropped.  A cancel and a
        //  release are not the same thing to gameplay -- one throws, the other
        //  simply lets go -- so the handler has to see which it was.
        p.release(p.worldX(), p.worldY(), p.uiX(), p.uiY(), true);
    }

    public void cancelAll() {
        for (int i = 0; i < MAX_POINTERS; i++) {
            cancelPointer(i);
        }
    }

    // --- InputProcessor: the only place screen pixels exist ------------------

    @Override
    public boolean touchDown(int screenX, int screenY, int pointerId, int button) {
        Pointer p = pointer(pointerId);
        if (p == null) {
            return false;
        }
        float[] world = toWorld(screenX, screenY);
        float wx = world[0];
        float wy = world[1];
        float[] ui = toUi(screenX, screenY);
        p.press(wx, wy, ui[0], ui[1]);
        velocity[pointerId].reset();
        velocity[pointerId].sample(clockSeconds, wx, wy);
        return true;
    }

    @Override
    public boolean touchDragged(int screenX, int screenY, int pointerId) {
        Pointer p = pointer(pointerId);
        if (p == null) {
            return false;
        }
        float[] world = toWorld(screenX, screenY);
        float[] ui = toUi(screenX, screenY);
        p.move(world[0], world[1], ui[0], ui[1]);
        velocity[pointerId].sample(clockSeconds, world[0], world[1]);
        return true;
    }

    @Override
    public boolean touchUp(int screenX, int screenY, int pointerId, int button) {
        Pointer p = pointer(pointerId);
        if (p == null) {
            return false;
        }
        float[] world = toWorld(screenX, screenY);
        float[] ui = toUi(screenX, screenY);
        velocity[pointerId].sample(clockSeconds, world[0], world[1]);
        p.release(world[0], world[1], ui[0], ui[1], false);
        //  Ownership is deliberately NOT cleared here.  The router must still be
        //  able to deliver this release to whoever owns the interaction -- clear
        //  it now and the handler never learns the gesture ended, which in the
        //  real game means a grabbed mob is never thrown.  InputRouter releases
        //  ownership after dispatching.
        return true;
    }

    @Override
    public boolean touchCancelled(int screenX, int screenY, int pointerId, int button) {
        cancelPointer(pointerId);
        return true;
    }

    @Override
    public boolean mouseMoved(int screenX, int screenY) {
        Pointer p = pointers[0];
        float[] world = toWorld(screenX, screenY);
        float[] ui = toUi(screenX, screenY);
        p.move(world[0], world[1], ui[0], ui[1]);
        return false;
    }

    /**
     * Back, latched rather than acted on.
     *
     * <p>libGDX delivers keys on the input thread, and navigation belongs to
     * the render thread -- the same split the pointers already live with. So
     * this records the press and {@link #consumeBack()} hands it over once, on
     * the thread that owns the interface. Acting here would mutate game state
     * from whichever thread the platform happened to use.
     *
     * <p>Escape is included because it is the desktop's Back, and having one
     * route for both is what stops the two drifting apart.
     */
    @Override
    public boolean keyDown(int keycode) {
        if (keycode == com.badlogic.gdx.Input.Keys.BACK
                || keycode == com.badlogic.gdx.Input.Keys.ESCAPE) {
            backRequested = true;
            return true;
        }
        return false;
    }

    /** Set on the input thread, read on the render thread. */
    private volatile boolean backRequested;

    /**
     * Takes the pending Back press, if there is one.
     *
     * <p>Returns true at most once per press: a Back that is read is a Back
     * that has been dealt with.
     */
    public boolean consumeBack() {
        if (!backRequested) {
            return false;
        }
        backRequested = false;
        return true;
    }

    @Override
    public boolean keyUp(int keycode) {
        return false;
    }

    @Override
    public boolean keyTyped(char character) {
        return false;
    }

    @Override
    public boolean scrolled(float amountX, float amountY) {
        return false;
    }

    // --- conversion ---------------------------------------------------------

    private final float[] worldScratch = new float[2];
    private final float[] uiScratch = new float[2];

    private float[] toWorld(int screenX, int screenY) {
        com.badlogic.gdx.math.Vector2 v = viewports.screenToWorld(screenX, screenY);
        worldScratch[0] = v.x;
        worldScratch[1] = v.y;
        return worldScratch;
    }

    private float[] toUi(int screenX, int screenY) {
        com.badlogic.gdx.math.Vector2 v = viewports.screenToUi(screenX, screenY);
        uiScratch[0] = v.x;
        uiScratch[1] = v.y;
        return uiScratch;
    }
}
