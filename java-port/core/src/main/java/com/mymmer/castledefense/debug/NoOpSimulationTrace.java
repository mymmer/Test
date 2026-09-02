package com.mymmer.castledefense.debug;

/**
 * The trace that records nothing — what ships.
 *
 * <p>A singleton so no allocation and no null checks are needed anywhere.
 */
public final class NoOpSimulationTrace implements SimulationTrace {

    public static final NoOpSimulationTrace INSTANCE = new NoOpSimulationTrace();

    private NoOpSimulationTrace() {
    }

    @Override
    public boolean isEnabled() {
        return false;
    }

    @Override
    public void event(TraceEvent event, long step, long subject, float a, float b, String label) {
        // deliberately empty
    }
}
