package com.mymmer.castledefense.defence;

import com.mymmer.castledefense.debug.SimulationTrace;
import com.mymmer.castledefense.util.Rng;

/**
 * Everything the defence side needs from the wider world, and nothing else.
 *
 * <p>The Python code reaches all of this through a single god object
 * ({@code self.game.enemies}, {@code self.game.wind}, {@code self.game.talents},
 * …). Porting that literally would drag the whole game into this package. This
 * interface is the same access, narrowed to what the defences actually touch, so
 * the dependency runs one way: {@code defence} → this contract, and the game
 * implements it.
 *
 * <h2>Targets are exposed by index, not as a collection</h2>
 *
 * <p>{@link #targetCount()} / {@link #target(int)} rather than an iterable,
 * because the hot loops run once per projectile per enemy per frame and must not
 * allocate an iterator. It also makes the ordering contract explicit: index order
 * <b>is</b> the Python list order, and the port depends on it — see
 * {@code docs/PORT_ANALYSIS.md} §3.
 */
public interface DefenceContext {

    // --- targets ------------------------------------------------------------

    /** How many hostile targets exist, alive or not. */
    int targetCount();

    /**
     * One target, in insertion order.
     *
     * <p>Dead entries are still present until the world sweeps, exactly as the
     * Python list holds them, so callers check {@link Target#alive()} rather than
     * assuming the list is compacted.
     */
    Target target(int index);

    /** Removes a target from the horde entirely. Used when the Outpost traps one. */
    void removeFromHorde(Target target);

    // --- projectiles --------------------------------------------------------

    /** Files a newly fired projectile with the world. */
    void addProjectile(Projectile projectile);

    // --- structures ---------------------------------------------------------
    //  A hostile projectile has to resolve against these in a fixed order, so it
    //  reaches them through the context rather than being handed three
    //  references at construction.  Any of them may be null before a run starts.

    Castle castle();

    Barricade barricade();

    Outpost outpost();

    // --- world state --------------------------------------------------------

    /** Current wind, in px/s². Python {@code game.wind}. */
    float wind();

    /** Current wave number, 1-based. Drives spike damage scaling. */
    int wave();

    /** The player's current grab capacity, checked before a Necromancer is trapped. */
    float grabCapacity();

    // --- services -----------------------------------------------------------

    /** The gameplay RNG stream. Never {@code new Random()}, never the decoration stream. */
    Rng rng();

    /** Diagnostics only. Never gameplay control flow. */
    SimulationTrace trace();

    /** The current simulation step, for trace entries. */
    long step();

    CombatModifiers modifiers();

    AllyFactory allies();

    // --- callbacks ----------------------------------------------------------

    /** The castle reached zero. Python {@code game.on_castle_destroyed()}. */
    void onCastleDestroyed();
}
