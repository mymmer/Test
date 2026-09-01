package com.mymmer.castledefense.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RngTest {

    @Test
    @DisplayName("the same seed replays the same gameplay sequence")
    void deterministicFromSeed() {
        Rng a = new Rng(12345L);
        Rng b = new Rng(12345L);
        for (int i = 0; i < 100; i++) {
            assertEquals(a.game().nextFloat(), b.game().nextFloat(), 0f);
        }
    }

    @Test
    @DisplayName("drawing decoration does not disturb the gameplay stream")
    void decorationIsIndependent() {
        // This is the Python quirk the split fixes: Castle.draw() reseeds the
        // global RNG every frame below 66% health, so rendering changed what
        // the game rolled next.
        Rng quiet = new Rng(777L);
        Rng busy = new Rng(777L);
        for (int i = 0; i < 500; i++) {
            busy.decoration().nextFloat();      // as if we were drawing cracks
        }
        for (int i = 0; i < 50; i++) {
            assertEquals(quiet.game().nextFloat(), busy.game().nextFloat(), 0f,
                    "decoration must not perturb gameplay randomness");
        }
    }

    @Test
    @DisplayName("uniform, chance and rangeInclusive keep Python's semantics")
    void distributions() {
        Rng rng = new Rng(99L);
        for (int i = 0; i < 2000; i++) {
            float u = rng.uniform(-3f, 7f);
            assertTrue(u >= -3f && u < 7.0001f, "uniform out of range: " + u);
            int r = rng.rangeInclusive(2, 5);
            assertTrue(r >= 2 && r <= 5, "randint out of range: " + r);
        }
        // chance(p) should land near p over a decent sample
        int hits = 0;
        for (int i = 0; i < 20000; i++) {
            if (rng.chance(0.25f)) {
                hits++;
            }
        }
        assertTrue(Math.abs(hits / 20000.0 - 0.25) < 0.02,
                "chance(0.25) produced " + (hits / 20000.0));
        // rangeInclusive must be able to hit both ends
        boolean sawLo = false;
        boolean sawHi = false;
        for (int i = 0; i < 500; i++) {
            int r = rng.rangeInclusive(0, 1);
            sawLo |= r == 0;
            sawHi |= r == 1;
        }
        assertTrue(sawLo && sawHi, "rangeInclusive must be inclusive at both ends");
    }

    @Test
    @DisplayName("reseeding restarts the gameplay stream")
    void reseed() {
        Rng rng = new Rng(1L);
        float first = rng.game().nextFloat();
        rng.game().nextFloat();
        rng.reseedGame(1L);
        assertEquals(first, rng.game().nextFloat(), 0f);
        assertEquals(1L, rng.getGameSeed());
    }

    @Test
    @DisplayName("different seeds diverge")
    void differentSeeds() {
        assertNotEquals(new Rng(1L).game().nextLong(), new Rng(2L).game().nextLong());
    }
}
