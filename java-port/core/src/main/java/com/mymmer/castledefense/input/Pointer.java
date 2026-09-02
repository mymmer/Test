package com.mymmer.castledefense.input;

/**
 * One finger, or the mouse, in <b>virtual world coordinates</b>.
 *
 * <p>Physical pixels never reach gameplay: the input layer converts through the
 * viewport before anything is stored here, so a value read from a pointer is
 * directly comparable with an enemy's x and a world constant like
 * {@code CASTLE_FRONT}, on every device.
 *
 * <p>Owned exclusively by {@code GameInput}; gameplay reads it and never writes.
 */
public final class Pointer {

    /** Nothing owns this pointer's interaction. */
    public static final int NO_OWNER = -1;

    private final int id;

    private boolean down;
    private boolean justPressed;
    private boolean justReleased;
    private boolean cancelled;

    private float worldX;
    private float worldY;
    private float prevWorldX;
    private float prevWorldY;
    private float uiX;
    private float uiY;

    private float downWorldX;
    private float downWorldY;

    /** Non-null when a UI element claimed this pointer at press time. */
    private Object consumedBy;

    Pointer(int id) {
        this.id = id;
    }

    public int id() {
        return id;
    }

    public boolean isDown() {
        return down;
    }

    /** True for the single simulation step in which the press arrived. */
    public boolean justPressed() {
        return justPressed;
    }

    /** True for the single simulation step in which the release arrived. */
    public boolean justReleased() {
        return justReleased;
    }

    /**
     * True when the platform cancelled this pointer rather than releasing it.
     *
     * <p>Android does this when a gesture is stolen by the system (a swipe from
     * the edge, a notification pull). It has to end the interaction exactly like
     * a release, or the player is left holding a mob that never lands.
     */
    public boolean wasCancelled() {
        return cancelled;
    }

    public float worldX() {
        return worldX;
    }

    public float worldY() {
        return worldY;
    }

    /** Movement since the previous simulation step, in world units. */
    public float deltaWorldX() {
        return worldX - prevWorldX;
    }

    public float deltaWorldY() {
        return worldY - prevWorldY;
    }

    public float uiX() {
        return uiX;
    }

    public float uiY() {
        return uiY;
    }

    /** Where this press began — for drag distance and slingshot pull. */
    public float downWorldX() {
        return downWorldX;
    }

    public float downWorldY() {
        return downWorldY;
    }

    /** The UI element that claimed this pointer, or null. */
    public Object consumedBy() {
        return consumedBy;
    }

    public boolean isConsumedByUi() {
        return consumedBy != null;
    }

    // --- package-private mutation: only GameInput drives these ---------------

    void press(float wx, float wy, float ux, float uy) {
        down = true;
        justPressed = true;
        justReleased = false;
        cancelled = false;
        consumedBy = null;
        worldX = prevWorldX = downWorldX = wx;
        worldY = prevWorldY = downWorldY = wy;
        uiX = ux;
        uiY = uy;
    }

    void move(float wx, float wy, float ux, float uy) {
        worldX = wx;
        worldY = wy;
        uiX = ux;
        uiY = uy;
    }

    void release(float wx, float wy, float ux, float uy, boolean wasCancel) {
        down = false;
        justReleased = true;
        cancelled = wasCancel;
        worldX = wx;
        worldY = wy;
        uiX = ux;
        uiY = uy;
    }

    void consume(Object consumer) {
        consumedBy = consumer;
    }

    /** Clears the one-step edge flags and rolls the delta baseline forward. */
    void endStep() {
        justPressed = false;
        justReleased = false;
        prevWorldX = worldX;
        prevWorldY = worldY;
        if (!down) {
            cancelled = false;
            consumedBy = null;
        }
    }

    void reset() {
        down = false;
        justPressed = false;
        justReleased = false;
        cancelled = false;
        consumedBy = null;
        worldX = worldY = prevWorldX = prevWorldY = 0f;
        uiX = uiY = 0f;
        downWorldX = downWorldY = 0f;
    }
}
