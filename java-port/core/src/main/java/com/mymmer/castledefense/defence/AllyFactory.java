package com.mymmer.castledefense.defence;

/**
 * Creates the friendly skeletons a trapped Necromancer raises.
 *
 * <p>The seam that keeps the Python dependency direction intact. The Outpost
 * decides <em>when</em> an ally should appear — the timer, the cap, the
 * prisoner's state are all defence-side gameplay — but it must never know what
 * an ally <em>is</em>, because that is an enemy-package type arriving in Phase 6.
 *
 * <p>So the factory both builds the ally and files it with the world; the
 * Outpost learns only whether one appeared. Phase 5 tests use a counting fake.
 */
public interface AllyFactory {

    /** Raises nobody. The default until Phase 6 supplies the real one. */
    AllyFactory NONE = new AllyFactory() {
        @Override
        public boolean spawnAlly(float x, float y) {
            return false;
        }

        @Override
        public int allyCount() {
            return 0;
        }
    };

    /**
     * Raises one ally at a world position and adds it to the world.
     *
     * @return true if one was actually created
     */
    boolean spawnAlly(float x, float y);

    /** How many allies are currently up, for the cap check. */
    int allyCount();
}
