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
        public boolean spawnAlly(float x) {
            return false;
        }

        @Override
        public int allyCount() {
            return 0;
        }
    };

    /**
     * Raises one ally at a world <b>x</b> and adds it to the world.
     *
     * <h2>Why there is no y</h2>
     *
     * <p>There was one, and the Outpost passed its <em>own</em> y into it --
     * {@code OUTPOST_BASE_Y}, 505, the centre of a structure that stands well
     * clear of the ground. An ally took that as its authoritative position and
     * spent its whole life 88 units above the ground plane, because nothing in
     * {@code update} ever touches y.
     *
     * <p>Python's {@code Game.make_ally(x)} takes only an x, and
     * {@code FriendlySkeleton.__init__} defaults y to its own {@code ground_y}.
     * This seam now says the same thing, so a caller cannot supply a y that is
     * not the ground.
     *
     * @return true if one was actually created
     */
    boolean spawnAlly(float x);

    /** How many allies are currently up, for the cap check. */
    int allyCount();
}
