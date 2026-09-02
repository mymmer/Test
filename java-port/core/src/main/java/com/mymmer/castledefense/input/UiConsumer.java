package com.mymmer.castledefense.input;

/**
 * Something on screen that can claim a press before the world sees it.
 *
 * <p>Shop cards, the skill bar, the Challenge Horn, talent nodes, the pause
 * button: all of them get first refusal on a touch, and a touch they claim must
 * never also grab a mob behind them.
 *
 * <p>Only {@link #onPress} decides. Once a consumer has claimed a pointer, that
 * pointer belongs to it for its whole lifetime — dragging off the button does
 * not hand the finger to the world.
 */
public interface UiConsumer {

    /**
     * Offered a press.
     *
     * @return true to claim this pointer until it is released
     */
    boolean onPress(Pointer pointer);

    /** A pointer this consumer claimed has moved. */
    default void onDrag(Pointer pointer) {
    }

    /** A pointer this consumer claimed was released or cancelled. */
    default void onRelease(Pointer pointer) {
    }

    /** Name for logs and tests. */
    default String consumerName() {
        return getClass().getSimpleName();
    }
}
