package com.mymmer.castledefense.defence;

/**
 * One wall tier: a stable id, a display name and a maximum health.
 *
 * <p>The Python tuple also carries three colours. Those are Phase 11's business
 * and are deliberately absent, so a repaint can never change the castle's health.
 */
public final class CastleTier {

    /** Stable semantic id, used in data and traces. Never the index. */
    public final String id;
    /** Display name. Becomes a localisation key in Phase 10. */
    public final String name;
    public final float maxHp;

    CastleTier(String id, String name, float maxHp) {
        this.id = id;
        this.name = name;
        this.maxHp = maxHp;
    }

    @Override
    public String toString() {
        return id + "(" + (int) maxHp + ")";
    }
}
