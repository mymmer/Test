package com.mymmer.castledefense.ui;

import com.mymmer.castledefense.game.GameState;
import com.mymmer.castledefense.game.RunWorld;

/**
 * Where the player can go from where, transcribed from the source.
 *
 * <h2>The graph</h2>
 *
 * <pre>
 *   MENU ──1/2/space/click──▶ SHOP (first armoury)      choose_mode
 *   MENU ──settings button──▶ SETTINGS
 *   MENU ──difficulty click─▶ MENU        (sets the PREFERRED difficulty)
 *
 *   SETTINGS ──back / ESC / P──▶ MENU
 *   SETTINGS ──difficulty click─▶ SETTINGS (sets the PREFERRED difficulty)
 *   SETTINGS ──mute / clear score──▶ SETTINGS
 *
 *   SHOP ──start button / space──▶ PLAYING            begin_play
 *   SHOP ──T / talent button──▶ TALENTS
 *   SHOP ──number keys 1..0──▶ SHOP        (buy item n)
 *
 *   PLAYING ──ESC / P──▶ PAUSED
 *   PLAYING ──T──▶ TALENTS
 *   PLAYING ──shop button (ENDLESS only)──▶ SHOP      open_realtime_shop
 *   PLAYING ──horn / skill bar / world──▶ PLAYING
 *
 *   PAUSED ──ESC / P──▶ PLAYING
 *   PAUSED ── (nothing else; clicks are ignored entirely)
 *
 *   TALENTS ──T / ESC / back──▶ whichever of SHOP or PLAYING it came from
 *
 *   GAMEOVER ──any click / R──▶ reset() then MENU
 * </pre>
 *
 * <h2>What is NOT in the graph</h2>
 *
 * <p><b>There is no route from a running game to SETTINGS.</b> {@code PAUSED}
 * only toggles back to {@code PLAYING}; {@code MENU} is reachable only through
 * {@code GAMEOVER}, which calls {@code reset()} first. That is why the run's
 * difficulty cannot change while the run lasts, and it is why
 * {@link RunWorld} captures it at run creation.
 *
 * <p>This class enforces that rather than merely documenting it:
 * {@link #canOpenSettings()} is false in every in-run state, and
 * {@code NavigationTest} fails the build if a route to SETTINGS appears from
 * one. If Phase 11 or a later design genuinely wants a settings button on the
 * pause panel, the difficulty contract has to be revisited deliberately — the
 * test is the place that conversation starts.
 *
 * <h2>Ownership</h2>
 *
 * <p>Navigation <b>asks the world</b> to change state; it does not set
 * {@code GameState} itself and it never touches gameplay. Freezing is the state
 * machine's job, exactly as in Phase 8.
 */
public final class Navigation {

    private final RunWorld run;

    /**
     * Where {@code TALENTS} came from, so it can go back.
     *
     * <p>Python's {@code talent_return}, including its guard: anything other
     * than SHOP or PLAYING falls back to SHOP.
     */
    private GameState talentReturn = GameState.SHOP;

    /** Told when navigation lands somewhere, for the screen stack and traces. */
    public interface Listener {
        void onNavigated(GameState from, GameState to);
    }

    private Listener listener;

    public Navigation(RunWorld run) {
        if (run == null) {
            throw new IllegalArgumentException("run must not be null");
        }
        this.run = run;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public GameState state() {
        return run.world().state();
    }

    public GameState talentReturn() {
        return talentReturn;
    }

    // ========================================================================
    //  Predicates -- what the UI may offer from here
    // ========================================================================

    /** True in the three states that mean "a run is under way". */
    public boolean inRun() {
        GameState s = state();
        return s == GameState.PLAYING || s == GameState.PAUSED
                || s == GameState.SHOP || s == GameState.TALENTS;
    }

    /**
     * Can the settings screen be opened from here?
     *
     * <p><b>Only from the menu.</b> See the class comment: this is the
     * difficulty contract, expressed as code.
     */
    public boolean canOpenSettings() {
        return state() == GameState.MENU;
    }

    /**
     * Can the preferred difficulty be changed from here?
     *
     * <p>The same answer, for the same reason, plus the settings screen itself.
     * Never from a state a run is in.
     */
    public boolean canChangePreferredDifficulty() {
        GameState s = state();
        return s == GameState.MENU || s == GameState.SETTINGS;
    }

    /** The talent tree opens from the armoury and from play. Python's `T`. */
    public boolean canOpenTalents() {
        GameState s = state();
        return s == GameState.SHOP || s == GameState.PLAYING;
    }

    /** The mid-fight armoury is an Endless mechanic, and only while playing. */
    public boolean canOpenRealtimeShop() {
        return state() == GameState.PLAYING && run.session().isEndless();
    }

    public boolean canPause() {
        return state() == GameState.PLAYING;
    }

    // ========================================================================
    //  Transitions
    // ========================================================================

    /** MENU → SETTINGS. Refuses from anywhere else. */
    public boolean openSettings() {
        if (!canOpenSettings()) {
            return false;
        }
        return go(GameState.SETTINGS);
    }

    /** SETTINGS → MENU. */
    public boolean closeSettings() {
        if (state() != GameState.SETTINGS) {
            return false;
        }
        return go(GameState.MENU);
    }

    /**
     * MENU → the first armoury, with the mode locked in.
     *
     * <p>Python {@code choose_mode}: the run is created here, which is where it
     * captures its difficulty.
     */
    public boolean chooseMode(com.mymmer.castledefense.game.GameMode mode,
                              com.mymmer.castledefense.config.DifficultyConfig difficulty) {
        if (state() != GameState.MENU) {
            return false;
        }
        run.beginRun(mode, difficulty);
        run.world().setState(GameState.SHOP);
        notifyListener(GameState.MENU, GameState.SHOP);
        return true;
    }

    /** SHOP → PLAYING. Python {@code begin_play}. */
    public boolean startPlaying() {
        if (state() != GameState.SHOP) {
            return false;
        }
        if (run.session().isEndless()) {
            return run.resumeFromShop();
        }
        //  Classic: the shop between waves sends in the next one.  The very
        //  first armoury has already had wave 1 composed by beginRun, so it
        //  simply resumes.
        if (run.director() != null && run.director().pendingSpawns() > 0) {
            boolean ok = run.resumeFromShop();
            if (ok) {
                notifyListener(GameState.SHOP, GameState.PLAYING);
            }
            return ok;
        }
        boolean ok = run.startNextWave();
        if (ok) {
            notifyListener(GameState.SHOP, GameState.PLAYING);
        }
        return ok;
    }

    /** PLAYING → SHOP, Endless only. The Phase 8 freeze does the rest. */
    public boolean openRealtimeShop() {
        if (!canOpenRealtimeShop()) {
            return false;
        }
        boolean ok = run.openRealtimeShop();
        if (ok) {
            notifyListener(GameState.PLAYING, GameState.SHOP);
        }
        return ok;
    }

    /** SHOP → TALENTS or PLAYING → TALENTS, remembering which. */
    public boolean openTalents() {
        if (!canOpenTalents()) {
            return false;
        }
        talentReturn = state();
        return go(GameState.TALENTS);
    }

    /**
     * TALENTS → wherever it came from.
     *
     * <p>Python's guard included: anything other than SHOP or PLAYING becomes
     * SHOP, so a corrupted return can never strand the player.
     */
    public boolean closeTalents() {
        if (state() != GameState.TALENTS) {
            return false;
        }
        GameState back = talentReturn;
        if (back != GameState.SHOP && back != GameState.PLAYING) {
            back = GameState.SHOP;
        }
        return go(back);
    }

    /** PLAYING → PAUSED. */
    public boolean pause() {
        if (!canPause()) {
            return false;
        }
        return go(GameState.PAUSED);
    }

    /** PAUSED → PLAYING. */
    public boolean resume() {
        if (state() != GameState.PAUSED) {
            return false;
        }
        return go(GameState.PLAYING);
    }

    /** ESC / P: pause or resume, whichever applies. */
    public boolean togglePause() {
        return state() == GameState.PLAYING ? pause() : resume();
    }

    /**
     * GAMEOVER → MENU, after a full reset.
     *
     * <p>Python resets <b>then</b> shows the menu, so nothing from the finished
     * run is reachable from the front end. The reset is Phase 9's proven path.
     */
    public boolean returnToMenu() {
        if (state() != GameState.GAMEOVER) {
            return false;
        }
        run.resetToMenu();
        notifyListener(GameState.GAMEOVER, GameState.MENU);
        return true;
    }

    // ========================================================================
    //  Android Back / desktop Escape
    // ========================================================================

    /** What a Back press does. */
    public enum BackResult {
        /** Navigation handled it. */
        HANDLED,
        /** Nothing to go back to: the platform should do its default. */
        EXIT_APP
    }

    /**
     * One Back press — {@code BACKSPACE}/Escape on desktop, the system gesture
     * or button on Android.
     *
     * <p>The routes are the source's own, so Back never reaches somewhere a
     * click could not:
     *
     * <pre>
     *   PLAYING   -> PAUSED          (never straight out of a fight)
     *   PAUSED    -> PLAYING
     *   TALENTS   -> wherever it came from
     *   SETTINGS  -> MENU
     *   SHOP      -> PLAYING in Endless (the realtime shop closes)
     *                MENU stays out of reach in Classic: the armoury between
     *                waves has no "back", and inventing one would be a route
     *                to SETTINGS mid-run
     *   GAMEOVER  -> MENU
     *   MENU      -> the platform's default (exit)
     * </pre>
     */
    public BackResult back() {
        switch (state()) {
            case PLAYING:
                pause();
                return BackResult.HANDLED;
            case PAUSED:
                resume();
                return BackResult.HANDLED;
            case TALENTS:
                closeTalents();
                return BackResult.HANDLED;
            case SETTINGS:
                closeSettings();
                return BackResult.HANDLED;
            case SHOP:
                if (run.session().isEndless() && run.world().state() == GameState.SHOP) {
                    //  the mid-fight armoury closes; the Classic one does not,
                    //  because there is nothing behind it but the menu
                    if (run.resumeFromShop()) {
                        notifyListener(GameState.SHOP, GameState.PLAYING);
                    }
                }
                return BackResult.HANDLED;
            case GAMEOVER:
                returnToMenu();
                return BackResult.HANDLED;
            case MENU:
            default:
                return BackResult.EXIT_APP;
        }
    }

    // ------------------------------------------------------------------------

    private boolean go(GameState to) {
        GameState from = state();
        if (from == to) {
            return false;
        }
        run.world().setState(to);
        notifyListener(from, to);
        return true;
    }

    private void notifyListener(GameState from, GameState to) {
        if (listener != null) {
            listener.onNavigated(from, to);
        }
    }
}
