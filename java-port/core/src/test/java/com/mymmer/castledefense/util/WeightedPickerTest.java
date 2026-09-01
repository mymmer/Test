package com.mymmer.castledefense.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.badlogic.gdx.math.RandomXS128;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WeightedPickerTest {

    @Test
    @DisplayName("picks land in proportion to weight, like random.choices")
    void proportional() {
        WeightedPicker<String> picker = new WeightedPicker<String>()
                .add("common", 3f)
                .add("rare", 1f);
        assertEquals(0.75f, picker.probabilityOf("common"), 0.0001f);
        assertEquals(0.25f, picker.probabilityOf("rare"), 0.0001f);

        RandomXS128 random = new RandomXS128(4242L);
        int common = 0;
        int n = 40000;
        for (int i = 0; i < n; i++) {
            if ("common".equals(picker.pick(random))) {
                common++;
            }
        }
        assertTrue(Math.abs(common / (double) n - 0.75) < 0.01,
                "expected ~75% common, got " + (common / (double) n));
    }

    @Test
    @DisplayName("the UNLOCKS reciprocal makes expensive units rarer")
    void reciprocalWeighting() {
        // Python: weights = [1.0 / w for (_, w) in pool] -- a bigger budget cost
        // means a RARER unit.  The inversion stays at the call site, so this
        // test documents the convention the wave builder will use in Phase 6.
        WeightedPicker<String> picker = new WeightedPicker<String>()
                .add("scout", 1f / 1.0f)        // cost 1.0
                .add("siege_ram", 1f / 4.5f);   // cost 4.5
        assertTrue(picker.probabilityOf("scout") > picker.probabilityOf("siege_ram") * 4f,
                "a 4.5-cost unit must be far rarer than a 1.0-cost one");
    }

    @Test
    @DisplayName("a single entry is always chosen")
    void singleEntry() {
        WeightedPicker<String> picker = new WeightedPicker<String>().add("only", 0.01f);
        RandomXS128 random = new RandomXS128(1L);
        for (int i = 0; i < 100; i++) {
            assertEquals("only", picker.pick(random));
        }
    }

    @Test
    @DisplayName("bad input is rejected loudly rather than skewing the odds")
    void rejectsBadInput() {
        WeightedPicker<String> picker = new WeightedPicker<>();
        assertThrows(IllegalStateException.class, () -> picker.pick(new RandomXS128(1L)));
        assertThrows(IllegalArgumentException.class, () -> picker.add("x", 0f));
        assertThrows(IllegalArgumentException.class, () -> picker.add("x", -1f));
        assertThrows(IllegalArgumentException.class, () -> picker.add("x", Float.NaN));
        assertThrows(IllegalArgumentException.class, () -> picker.add(null, 1f));
    }

    @Test
    @DisplayName("clearing and re-adding rebuilds the distribution")
    void rebuildAfterClear() {
        WeightedPicker<String> picker = new WeightedPicker<String>().add("a", 1f);
        assertEquals(1f, picker.totalWeight(), 0.0001f);
        picker.clear();
        picker.add("b", 2f).add("c", 2f);
        assertEquals(4f, picker.totalWeight(), 0.0001f);
        assertEquals(0.5f, picker.probabilityOf("b"), 0.0001f);
        assertEquals(0f, picker.probabilityOf("a"), 0.0001f);
    }
}
