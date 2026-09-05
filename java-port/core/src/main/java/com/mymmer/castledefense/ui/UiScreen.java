package com.mymmer.castledefense.ui;

import com.badlogic.gdx.utils.Array;
import com.mymmer.castledefense.game.GameState;

/**
 * One screen's worth of interface.
 *
 * <h2>Two jobs, and only two</h2>
 *
 * <ol>
 *   <li><b>Lay out.</b> {@link #layout} turns the safe rectangle into a set of
 *       {@link UiRect}s with stable ids. Called when the viewport or the safe
 *       area changes, and once per frame while the screen is up so that state
 *       (a maxed shop item, a spent talent point) is reflected.</li>
 *   <li><b>Dispatch.</b> {@link #press} turns a hit on one of those rectangles
 *       into a <b>command to a gameplay subsystem</b>.</li>
 * </ol>
 *
 * <p>A screen never computes a price, a cooldown, an availability or a damage
 * figure. It asks the subsystem that owns it. The one authority rule from
 * Phase 9 is what this interface exists to keep.
 *
 * <p>A screen also never advances anything. Cosmetic animation may use the
 * render delta; nothing that a gameplay decision depends on may.
 */
public interface UiScreen {

    /** The state this screen is shown in. */
    GameState state();

    /**
     * Rebuilds the control rectangles for the current safe area and game state.
     *
     * <p>Idempotent and allocation-light: the {@link UiRect} objects are reused
     * between calls, only their bounds and states change.
     */
    void layout(SafeArea safe, TextLayout text);

    /**
     * Every control, in <b>priority order</b>.
     *
     * <p>Where two enlarged touch boxes overlap, the earlier one wins — the same
     * first-claim rule the input router uses.
     */
    Array<UiRect> controls();

    /**
     * A press at a point in UI units.
     *
     * @return the id of the control that took it, or null if none did
     */
    String press(float uiX, float uiY);

    /**
     * Does this screen block everything behind it?
     *
     * <p>Modal screens — the pause panel, the armoury, the talent tree, the
     * settings page, the game-over panel — swallow every press inside the UI
     * viewport whether or not it landed on a control, so nothing can click
     * through to the world. Only the in-play HUD is non-modal.
     */
    default boolean modal() {
        return true;
    }
}
