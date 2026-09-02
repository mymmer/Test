package com.mymmer.castledefense.input;

import com.badlogic.gdx.utils.Array;

/**
 * Decides whether a press belongs to the UI or to the world, and keeps that
 * decision for the life of the pointer.
 *
 * <h2>Priority</h2>
 *
 * <pre>
 *   UI consumers, in registration order
 *        -&gt; world interaction handler
 * </pre>
 *
 * <p>The Python game hard-codes this order inside {@code handle_event}: the
 * skill bar gets first refusal, then the Endless shop button, then the Challenge
 * Horn, and only then {@code try_grab}. Those specific consumers arrive with
 * their systems in Phases 9 and 10; what is fixed now is the structure — UI
 * before world, decided once, per pointer.
 *
 * <h2>Consumption is per pointer and sticky</h2>
 *
 * <p>If a consumer claims pointer 2's press, then pointer 2's drags, release and
 * cancellation all go to that consumer. Dragging off the button does not convert
 * the finger into a world pointer half way through a gesture — which is what
 * would otherwise happen the first time somebody's thumb slid off a shop card
 * onto a Siege Ram.
 *
 * <h2>Ownership</h2>
 * <ul>
 *   <li><b>Created and owned by</b> {@code CastleDefenseGame}.</li>
 *   <li><b>Mutates</b> pointer consumption flags and interaction ownership on
 *       {@link GameInput}; owns nothing else.</li>
 *   <li><b>Holds</b> references to UI consumers and one world handler, both
 *       registered by whoever builds the screen; it does not own their
 *       lifetimes.</li>
 * </ul>
 */
public final class InputRouter {

    private final GameInput input;
    private final Array<UiConsumer> consumers = new Array<>();
    private WorldInteractionHandler worldHandler;

    public InputRouter(GameInput input) {
        if (input == null) {
            throw new IllegalArgumentException("input must not be null");
        }
        this.input = input;
    }

    /** Adds a UI consumer. Registration order is priority order. */
    public InputRouter addConsumer(UiConsumer consumer) {
        if (consumer != null && !consumers.contains(consumer, true)) {
            consumers.add(consumer);
        }
        return this;
    }

    public void removeConsumer(UiConsumer consumer) {
        consumers.removeValue(consumer, true);
    }

    public void clearConsumers() {
        consumers.clear();
    }

    public void setWorldHandler(WorldInteractionHandler handler) {
        this.worldHandler = handler;
    }

    public int consumerCount() {
        return consumers.size;
    }

    /**
     * Routes every pointer for one simulation step.
     *
     * <p>Called once per step, after {@link GameInput#setClock}. Presses,
     * drags, releases and cancellations are dispatched in that order per
     * pointer, and the whole thing is allocation-free.
     */
    public void route() {
        for (int id = 0; id < GameInput.MAX_POINTERS; id++) {
            Pointer p = input.pointer(id);
            if (p == null) {
                continue;
            }
            if (p.justPressed()) {
                dispatchPress(p);
            } else if (p.isDown()) {
                dispatchDrag(p);
            }
            if (p.justReleased()) {
                dispatchRelease(p);
            }
        }
    }

    private void dispatchPress(Pointer p) {
        for (int i = 0; i < consumers.size; i++) {
            UiConsumer consumer = consumers.get(i);
            if (consumer.onPress(p)) {
                input.consume(p.id(), consumer);
                return;             // claimed: the world never sees this pointer
            }
        }
        if (worldHandler == null) {
            return;
        }
        // Ownership first: a second finger must not start a second interaction.
        if (!input.acquireInteraction(p.id())) {
            return;
        }
        if (!worldHandler.onWorldPress(p)) {
            input.releaseInteraction(p.id());
        }
    }

    private void dispatchDrag(Pointer p) {
        Object owner = p.consumedBy();
        if (owner instanceof UiConsumer) {
            ((UiConsumer) owner).onDrag(p);
            return;
        }
        if (worldHandler != null && input.ownsInteraction(p.id())) {
            worldHandler.onWorldDrag(p);
        }
    }

    private void dispatchRelease(Pointer p) {
        Object owner = p.consumedBy();
        if (owner instanceof UiConsumer) {
            ((UiConsumer) owner).onRelease(p);
            return;
        }
        if (worldHandler == null || !input.ownsInteraction(p.id())) {
            return;
        }
        if (p.wasCancelled()) {
            worldHandler.onWorldCancel(p);
        } else {
            worldHandler.onWorldRelease(p);
        }
        input.releaseInteraction(p.id());
    }
}
