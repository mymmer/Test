package com.mymmer.castledefense.shop;

import com.mymmer.castledefense.defence.Barricade;
import com.mymmer.castledefense.defence.Castle;
import com.mymmer.castledefense.defence.CombatModifiers;
import com.mymmer.castledefense.defence.Outpost;
import com.mymmer.castledefense.defence.SpikeWalls;
import com.mymmer.castledefense.progress.RunSession;

/**
 * What the shop needs from the world.
 *
 * <p>The fourth and last of these seams. The shop names no world, no screen and
 * no {@code TalentTree} — the discount arrives through {@link CombatModifiers}
 * like every other talent effect.
 *
 * <p>Note what is <b>not</b> here: any way to advance the simulation. A shop is a
 * transaction, and in Endless it happens while the world is frozen; giving it a
 * step would be giving it a way to break that.
 */
public interface ShopContext {

    // --- the purse ----------------------------------------------------------

    RunSession session();

    CombatModifiers modifiers();

    // --- what can be bought -------------------------------------------------

    Castle castle();

    Barricade barricade();

    Outpost outpost();

    SpikeWalls spikes();

    // --- cursor upgrades, which live on the run rather than on a structure ---

    int bounceLevel();

    void setBounceLevel(int level);

    int grabLevel();

    void setGrabLevel(int level);

    int multiLevel();

    void setMultiLevel(int level);

    // --- diagnostics --------------------------------------------------------

    com.mymmer.castledefense.debug.SimulationTrace trace();

    long step();
}
