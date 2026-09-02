package com.mymmer.castledefense.game;

/**
 * The screens the game can be in, and what each one lets advance.
 *
 * <p>Transcribed from {@code main.py}'s {@code Game.update}, which has three
 * tiers rather than a simple paused/running flag:
 *
 * <pre>
 *   ALWAYS   simulation time, screen shake, combo/storm/horn flashes, banner and
 *            bolt ageing, shop message timers   -- these run in every state
 *   EFFECTS  particles and floating text         -- every state except none
 *   WORLD    enemies, towers, projectiles, spawning, gameplay clocks
 *            -- PLAYING only
 * </pre>
 *
 * <p>Two details worth keeping because they are load-bearing in the Python:
 *
 * <ul>
 *   <li>The Endless "realtime shop" is {@link #SHOP} while a run is in progress.
 *       It freezes the run clock, spawning <em>and</em> all motion — the Python
 *       self-test asserts that every enemy's x is unchanged after 120 frames of
 *       shopping.</li>
 *   <li>{@link #TALENTS} and {@link #SETTINGS} tick effects but skip even the
 *       castle's damage-flash decay, which the other non-playing states do.</li>
 * </ul>
 */
public enum GameState {

    MENU(false, true),
    PLAYING(true, true),
    SHOP(false, true),
    PAUSED(false, true),
    GAMEOVER(false, true),
    TALENTS(false, true),
    SETTINGS(false, true);

    private final boolean advancesWorld;
    private final boolean advancesEffects;

    GameState(boolean advancesWorld, boolean advancesEffects) {
        this.advancesWorld = advancesWorld;
        this.advancesEffects = advancesEffects;
    }

    /**
     * True when gameplay advances: entities, spawning and every gameplay clock.
     *
     * <p>Only {@link #PLAYING}. This is the freeze rule the whole simulation
     * hangs off, and it is why an Endless shop can be opened mid-fight without
     * the horde taking another step.
     */
    public boolean advancesWorld() {
        return advancesWorld;
    }

    /** True when cosmetic particles and floating text keep animating. */
    public boolean advancesEffects() {
        return advancesEffects;
    }

    /** True when the player is inside a run rather than on a front-end screen. */
    public boolean isInRun() {
        return this == PLAYING || this == PAUSED || this == SHOP || this == TALENTS;
    }
}
