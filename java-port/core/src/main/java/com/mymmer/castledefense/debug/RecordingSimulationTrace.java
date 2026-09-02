package com.mymmer.castledefense.debug;

import com.badlogic.gdx.utils.Array;

/**
 * A trace that keeps what it is told, for tests and for a developer build.
 *
 * <p>Bounded: once {@link #capacity()} entries are held, the oldest are dropped.
 * A diagnostic that can exhaust memory during a long soak is not a diagnostic.
 */
public final class RecordingSimulationTrace implements SimulationTrace {

    /** One recorded event. Allocated only because this is not the shipping path. */
    public static final class Entry {
        public final TraceEvent event;
        public final long step;
        public final long subject;
        public final float a;
        public final float b;
        public final String label;

        Entry(TraceEvent event, long step, long subject, float a, float b, String label) {
            this.event = event;
            this.step = step;
            this.subject = subject;
            this.a = a;
            this.b = b;
            this.label = label;
        }

        @Override
        public String toString() {
            return event + "@" + step + (subject != 0 ? " #" + subject : "")
                    + " a=" + a + " b=" + b + (label != null ? " " + label : "");
        }
    }

    private final Array<Entry> entries = new Array<>();
    private final int capacity;
    private boolean enabled = true;

    public RecordingSimulationTrace() {
        this(4096);
    }

    public RecordingSimulationTrace(int capacity) {
        this.capacity = Math.max(16, capacity);
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void event(TraceEvent event, long step, long subject, float a, float b, String label) {
        if (!enabled) {
            return;
        }
        if (entries.size >= capacity) {
            entries.removeIndex(0);
        }
        entries.add(new Entry(event, step, subject, a, b, label));
    }

    public Array<Entry> entries() {
        return entries;
    }

    public void clear() {
        entries.clear();
    }

    public int countOf(TraceEvent event) {
        int n = 0;
        for (Entry e : entries) {
            if (e.event == event) {
                n++;
            }
        }
        return n;
    }

    public int capacity() {
        return capacity;
    }
}
