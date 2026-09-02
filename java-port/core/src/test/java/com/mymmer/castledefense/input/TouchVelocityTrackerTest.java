package com.mymmer.castledefense.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The most important mobile test in the port.
 *
 * <p>The same physical flick must produce the same throw on a 60 Hz budget phone
 * and a 240 Hz flagship. The Python keeps a fixed <em>count</em> of samples,
 * which silently becomes a shorter time window as the reporting rate rises;
 * this implementation keeps a fixed <em>duration</em> instead.
 */
class TouchVelocityTrackerTest {

    private static final float[] OUT = new float[2];

    /**
     * Plays one gesture at a given sampling rate.
     *
     * <p>The gesture is identical in world terms every time: a straight drag of
     * {@code speed} world units per second, lasting {@code seconds}. Only the
     * number of samples used to describe it changes.
     */
    private static float[] flick(float hz, float speedX, float speedY, float seconds) {
        TouchVelocityTracker tracker = new TouchVelocityTracker();
        int samples = Math.max(2, Math.round(hz * seconds));
        float dt = seconds / samples;
        for (int i = 0; i <= samples; i++) {
            float t = i * dt;
            tracker.sample(t, speedX * t, speedY * t);
        }
        float[] out = new float[2];
        tracker.velocity(out);
        return out;
    }

    @Test
    @DisplayName("a flick reads the same at 30, 60, 120 and 240 Hz")
    void samplingRateIndependent() {
        float speedX = 900f;
        float speedY = -1400f;
        float[] r30 = flick(30f, speedX, speedY, 0.30f);
        float[] r60 = flick(60f, speedX, speedY, 0.30f);
        float[] r120 = flick(120f, speedX, speedY, 0.30f);
        float[] r240 = flick(240f, speedX, speedY, 0.30f);

        // Tolerance, and why: the lookback lands on whichever sample is nearest
        // the 90 ms boundary, and that sample differs by up to one interval
        // between rates. For a constant-velocity drag the error cancels, so 1%
        // is generous; a rate-dependent implementation would be out by ~50%.
        float tolerance = 0.01f;
        for (float[] r : new float[][]{r30, r120, r240}) {
            assertEquals(r60[0], r[0], Math.abs(r60[0]) * tolerance,
                    "x velocity must not depend on the sampling rate");
            assertEquals(r60[1], r[1], Math.abs(r60[1]) * tolerance,
                    "y velocity must not depend on the sampling rate");
        }
        assertEquals(speedX, r60[0], speedX * 0.02f, "and must be roughly the real speed");
        assertEquals(speedY, r60[1], Math.abs(speedY) * 0.02f);
    }

    @Test
    @DisplayName("240 Hz keeps a full 90 ms of history, not 12 samples' worth")
    void highRateKeepsTheWindow() {
        TouchVelocityTracker tracker = new TouchVelocityTracker();
        float dt = 1f / 240f;
        for (int i = 0; i < 48; i++) {           // 200 ms of samples
            tracker.sample(i * dt, i * 10f, 0f);
        }
        assertTrue(tracker.historySpan() >= TouchVelocityTracker.LOOKBACK_SECONDS,
                "history must cover the lookback window, spans "
                        + tracker.historySpan());
        // 12 samples at 240 Hz would be 50 ms -- the Python's failure mode
        assertTrue(tracker.sampleCount() > 12,
                "a fixed sample count would truncate the window");
    }

    @Test
    @DisplayName("history is bounded, so a long drag cannot grow without limit")
    void historyIsBounded() {
        TouchVelocityTracker tracker = new TouchVelocityTracker();
        float dt = 1f / 240f;
        for (int i = 0; i < 5000; i++) {         // 20 seconds of dragging
            tracker.sample(i * dt, i, 0f);
        }
        assertTrue(tracker.historySpan() <= TouchVelocityTracker.HISTORY_SECONDS + 0.01f,
                "old samples must be forgotten, span " + tracker.historySpan());
    }

    @Test
    @DisplayName("velocity is clamped to the Python limit, in world units")
    void clampMatchesPython() {
        TouchVelocityTracker tracker = new TouchVelocityTracker();
        // an impossibly fast drag: 100000 world units per second
        tracker.sample(0f, 0f, 0f);
        tracker.sample(0.01f, 1000f, -1000f);
        tracker.velocity(OUT);
        assertEquals(TouchVelocityTracker.SPEED_CLAMP, OUT[0], 0.01f);
        assertEquals(-TouchVelocityTracker.SPEED_CLAMP, OUT[1], 0.01f);
        assertEquals(2600f, TouchVelocityTracker.SPEED_CLAMP, 0f,
                "the clamp must still be the Python value");
    }

    @Test
    @DisplayName("a tap is not a throw")
    void tapHasNoVelocity() {
        TouchVelocityTracker tracker = new TouchVelocityTracker();
        tracker.velocity(OUT);
        assertEquals(0f, OUT[0], 0f);
        assertEquals(0f, OUT[1], 0f);

        tracker.sample(0f, 100f, 100f);          // one sample only
        tracker.velocity(OUT);
        assertEquals(0f, OUT[0], 0f);
        assertEquals(0f, OUT[1], 0f);
    }

    @Test
    @DisplayName("a finger that stops before release throws weakly, as in Python")
    void stoppingBeforeReleaseKillsTheThrow() {
        TouchVelocityTracker tracker = new TouchVelocityTracker();
        // fast drag...
        for (int i = 0; i < 10; i++) {
            tracker.sample(i / 60f, i * 50f, 0f);
        }
        // ...then hold still for longer than the lookback window
        for (int i = 10; i < 20; i++) {
            tracker.sample(i / 60f, 450f, 0f);
        }
        tracker.velocity(OUT);
        assertEquals(0f, OUT[0], 1f,
                "holding still must drain the throw, not bank the earlier speed");
    }

    @Test
    @DisplayName("reset clears history between gestures")
    void resetBetweenGestures() {
        TouchVelocityTracker tracker = new TouchVelocityTracker();
        for (int i = 0; i < 10; i++) {
            tracker.sample(i / 60f, i * 100f, 0f);
        }
        tracker.reset();
        assertEquals(0, tracker.sampleCount());
        tracker.velocity(OUT);
        assertEquals(0f, OUT[0], 0f, "a new grab must not inherit the last throw");
    }

    @Test
    @DisplayName("the lookback window is the Python's 90 ms")
    void lookbackMatchesPython() {
        assertEquals(0.09f, TouchVelocityTracker.LOOKBACK_SECONDS, 1e-6f);
        assertTrue(TouchVelocityTracker.HISTORY_SECONDS
                        > TouchVelocityTracker.LOOKBACK_SECONDS,
                "history must exceed the lookback or the window cannot be filled");
    }
}
