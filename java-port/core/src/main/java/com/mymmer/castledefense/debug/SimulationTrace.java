package com.mymmer.castledefense.debug;

/**
 * A diagnostic tap on the simulation.
 *
 * <p>Deliberately narrow. This is <b>not</b> an event bus, a replay system or an
 * event-sourced architecture: there is one optional observer, it cannot affect
 * anything, and ordinary gameplay with a known receiver keeps calling that
 * receiver directly.
 *
 * <p>Zero cost when off. Call sites guard with {@link #isEnabled()}, so a
 * disabled trace costs one field read and no argument evaluation:
 *
 * <pre>
 * if (trace.isEnabled()) {
 *     trace.event(TraceEvent.DAMAGE, step, enemy.uid(), amount, 0f, "cannon");
 * }
 * </pre>
 *
 * <p>The signature is primitives plus one optional label so recording a common
 * event never boxes or allocates.
 */
public interface SimulationTrace {

    /** False for the production no-op, so callers can skip the work entirely. */
    boolean isEnabled();

    /**
     * Records one event.
     *
     * @param event  what happened
     * @param step   the simulation step it happened on
     * @param subject entity uid, or 0 when there is no subject
     * @param a      event-specific number (damage, gold, score delta …)
     * @param b      a second event-specific number, or 0
     * @param label  a short constant string, or null — never a built one
     */
    void event(TraceEvent event, long step, long subject, float a, float b, String label);
}
