package com.mymmer.castledefense.ui;

/**
 * One interactive control: what is drawn, and what can be touched.
 *
 * <h2>Two rectangles, deliberately</h2>
 *
 * <p>{@code visual*} is where the control is painted. {@code hit*} is where a
 * finger counts as having pressed it, and it is usually larger. Keeping them
 * apart is what lets a 26-pixel skill icon meet a 48-pixel touch minimum without
 * the artwork growing to match — enlarging the drawing to enlarge the target
 * would change the design, and the design is the source's.
 *
 * <p>All coordinates are <b>UI viewport units</b>, never screen pixels and never
 * world units. See {@code UI.md}.
 *
 * <h2>Identity</h2>
 *
 * <p>{@link #id} is a stable semantic string — {@code "shop.buy.bowman"},
 * {@code "talent.rate"}, {@code "menu.mode.classic"}. Never a display label,
 * never an index. A test names a control by it and a debug overlay prints it.
 */
public final class UiRect {

    /** A control that is present but cannot be pressed right now. */
    public enum State {
        NORMAL,
        DISABLED,
        SELECTED
    }

    public final String id;

    private float visualX;
    private float visualY;
    private float visualWidth;
    private float visualHeight;

    private float hitX;
    private float hitY;
    private float hitWidth;
    private float hitHeight;

    private State state = State.NORMAL;
    private boolean visible = true;

    public UiRect(String id) {
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("a control needs a stable id");
        }
        this.id = id;
    }

    // --- geometry -----------------------------------------------------------

    /**
     * Sets the drawn bounds, and the touch bounds to match.
     *
     * <p>Call {@link #expandHitTo} afterwards to grow the target.
     */
    public UiRect setBounds(float x, float y, float width, float height) {
        visualX = x;
        visualY = y;
        visualWidth = width;
        visualHeight = height;
        hitX = x;
        hitY = y;
        hitWidth = width;
        hitHeight = height;
        return this;
    }

    /**
     * Grows the touch bounds to at least this size, around the same centre.
     *
     * <p>Never shrinks: a control already larger than the minimum keeps its own
     * bounds. The visual bounds are untouched.
     */
    public UiRect expandHitTo(float minWidth, float minHeight) {
        float w = Math.max(hitWidth, minWidth);
        float h = Math.max(hitHeight, minHeight);
        float cx = visualX + visualWidth / 2f;
        float cy = visualY + visualHeight / 2f;
        hitX = cx - w / 2f;
        hitY = cy - h / 2f;
        hitWidth = w;
        hitHeight = h;
        return this;
    }

    /** Grows the touch bounds by a margin on every side. */
    public UiRect padHit(float margin) {
        hitX -= margin;
        hitY -= margin;
        hitWidth += margin * 2f;
        hitHeight += margin * 2f;
        return this;
    }

    public float visualX() {
        return visualX;
    }

    public float visualY() {
        return visualY;
    }

    public float visualWidth() {
        return visualWidth;
    }

    public float visualHeight() {
        return visualHeight;
    }

    public float centerX() {
        return visualX + visualWidth / 2f;
    }

    public float centerY() {
        return visualY + visualHeight / 2f;
    }

    public float hitX() {
        return hitX;
    }

    public float hitY() {
        return hitY;
    }

    public float hitWidth() {
        return hitWidth;
    }

    public float hitHeight() {
        return hitHeight;
    }

    // --- state --------------------------------------------------------------

    public State state() {
        return state;
    }

    public UiRect setState(State state) {
        this.state = state != null ? state : State.NORMAL;
        return this;
    }

    public boolean visible() {
        return visible;
    }

    public UiRect setVisible(boolean visible) {
        this.visible = visible;
        return this;
    }

    /** Visible and not disabled. Only these accept a press. */
    public boolean pressable() {
        return visible && state != State.DISABLED;
    }

    // --- hit testing --------------------------------------------------------

    /**
     * Is this point inside the touch bounds?
     *
     * <p>Half-open on the far edges, so two controls whose hit boxes share a
     * border cannot both claim the same point.
     */
    public boolean hits(float x, float y) {
        return pressable()
                && x >= hitX && x < hitX + hitWidth
                && y >= hitY && y < hitY + hitHeight;
    }

    /** Do two controls' touch bounds overlap at all? */
    public boolean hitOverlaps(UiRect other) {
        return other != null
                && hitX < other.hitX + other.hitWidth
                && other.hitX < hitX + hitWidth
                && hitY < other.hitY + other.hitHeight
                && other.hitY < hitY + hitHeight;
    }

    /** Is the whole touch area inside this rectangle? */
    public boolean insideBounds(float x, float y, float width, float height) {
        return hitX >= x && hitY >= y
                && hitX + hitWidth <= x + width
                && hitY + hitHeight <= y + height;
    }

    @Override
    public String toString() {
        return id + "[" + (int) visualX + "," + (int) visualY + " "
                + (int) visualWidth + "x" + (int) visualHeight
                + " hit " + (int) hitWidth + "x" + (int) hitHeight
                + " " + state + (visible ? "" : " hidden") + "]";
    }
}
