package com.mymmer.castledefense.util;

import com.badlogic.gdx.math.RandomXS128;
import com.badlogic.gdx.utils.Array;

/**
 * Weighted choice with Python's {@code random.choices} semantics: an item's
 * chance is its weight over the total, weights need not sum to anything in
 * particular, and sampling is with replacement.
 *
 * <p>Reusable and allocation-free once built: {@link #pick} walks a prefix-sum
 * array, so it can sit in a spawn loop without producing garbage.
 *
 * <p>The Python game builds these inline in several places — wave composition
 * ({@code build_wave}), the endless roster ({@code roll_endless_mob}) and the
 * Hard-mode horn pack all use {@code weights=[1.0 / w …]}. Note the reciprocal:
 * in {@code UNLOCKS} a bigger number means a <em>rarer</em> unit, so callers
 * pass {@code 1/cost}, not {@code cost}. That inversion stays at the call site
 * where it is visible, not hidden in here.
 */
public final class WeightedPicker<T> {

    private final Array<T> items = new Array<>();
    private final Array<Float> weights = new Array<>();
    private float[] cumulative = new float[0];
    private float total;
    private boolean dirty = true;

    public WeightedPicker<T> add(T item, float weight) {
        if (item == null) {
            throw new IllegalArgumentException("item must not be null");
        }
        if (!(weight > 0f) || Float.isInfinite(weight) || Float.isNaN(weight)) {
            throw new IllegalArgumentException(
                    "weight must be finite and positive, got " + weight);
        }
        items.add(item);
        weights.add(weight);
        dirty = true;
        return this;
    }

    public void clear() {
        items.clear();
        weights.clear();
        dirty = true;
    }

    public int size() {
        return items.size;
    }

    public boolean isEmpty() {
        return items.size == 0;
    }

    public float totalWeight() {
        rebuildIfNeeded();
        return total;
    }

    /** The probability of one item, for tests and for tooltips. */
    public float probabilityOf(T item) {
        rebuildIfNeeded();
        if (total <= 0f) {
            return 0f;
        }
        float sum = 0f;
        for (int i = 0; i < items.size; i++) {
            if (items.get(i).equals(item)) {
                sum += weights.get(i);
            }
        }
        return sum / total;
    }

    public T pick(RandomXS128 random) {
        rebuildIfNeeded();
        if (items.size == 0) {
            throw new IllegalStateException("cannot pick from an empty picker");
        }
        float roll = random.nextFloat() * total;
        for (int i = 0; i < cumulative.length; i++) {
            if (roll < cumulative[i]) {
                return items.get(i);
            }
        }
        return items.get(items.size - 1);       // float rounding at the very top
    }

    private void rebuildIfNeeded() {
        if (!dirty) {
            return;
        }
        if (cumulative.length != items.size) {
            cumulative = new float[items.size];
        }
        float running = 0f;
        for (int i = 0; i < items.size; i++) {
            running += weights.get(i);
            cumulative[i] = running;
        }
        total = running;
        dirty = false;
    }
}
