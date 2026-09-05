package com.mymmer.castledefense.ui;

import com.badlogic.gdx.utils.Array;
import com.mymmer.castledefense.config.DifficultyConfig;
import com.mymmer.castledefense.config.DifficultyTable;
import com.mymmer.castledefense.game.GameMode;
import com.mymmer.castledefense.game.GameState;
import com.mymmer.castledefense.persistence.SaveData;

/**
 * The front end: the main menu, the settings page, the pause panel and the
 * game-over panel.
 *
 * <p>Four small screens in one file because they share one shape — a centred
 * panel with a column of buttons — and splitting them would be four files of
 * ceremony around the same twenty lines of layout.
 *
 * <h2>The difficulty contract, enforced here</h2>
 *
 * <p>The difficulty buttons appear on the <b>menu</b> and the <b>settings</b>
 * page, and nowhere else. They set the <em>preferred</em> difficulty, which is
 * what the <em>next</em> run will capture. Neither screen is reachable while a
 * run is in progress — {@link Navigation#canChangePreferredDifficulty()} is the
 * predicate and {@code NavigationTest} is the guard.
 *
 * <p>The pause panel deliberately has <b>no settings button</b>. Adding one
 * would create the first route from a live run to the difficulty selector, which
 * the source does not have.
 */
public final class MenuScreens {

    private MenuScreens() {
    }

    // ========================================================================
    //  Main menu
    // ========================================================================

    /** Title, the two modes, the three difficulties, settings, the best score. */
    public static final class MainMenu implements UiScreen {

        private final DifficultyTable difficulties;
        private final SaveData save;
        private final Navigation nav;

        private final Array<UiRect> controls = new Array<>(false, 8);
        private final UiRect classic = new UiRect("menu.mode.classic");
        private final UiRect endless = new UiRect("menu.mode.endless");
        private final UiRect settings = new UiRect("menu.settings");
        private final Array<UiRect> difficultyButtons = new Array<>(false, 3);

        public MainMenu(Navigation nav, DifficultyTable difficulties, SaveData save) {
            this.nav = nav;
            this.difficulties = difficulties;
            this.save = save;
            for (DifficultyConfig d : difficulties.all()) {
                difficultyButtons.add(new UiRect("menu.difficulty." + d.id()));
            }
        }

        @Override
        public GameState state() {
            return GameState.MENU;
        }

        @Override
        public void layout(SafeArea safe, TextLayout text) {
            controls.clear();

            float buttonWidth = Math.min(360f, safe.width * 0.42f);
            float buttonHeight = 62f;
            float cx = safe.centerX();

            //  the two modes, side by side when there is room and stacked when
            //  there is not -- a 20:9 phone in portrait-ish UI space is narrow
            boolean sideBySide = safe.width >= buttonWidth * 2f + 40f;
            float modeY = safe.y + safe.height * 0.34f;
            if (sideBySide) {
                classic.setBounds(cx - buttonWidth - 20f, modeY, buttonWidth, buttonHeight);
                endless.setBounds(cx + 20f, modeY, buttonWidth, buttonHeight);
            } else {
                classic.setBounds(cx - buttonWidth / 2f, modeY + buttonHeight + 12f,
                        buttonWidth, buttonHeight);
                endless.setBounds(cx - buttonWidth / 2f, modeY, buttonWidth, buttonHeight);
            }

            //  the difficulty row sits under them
            float diffWidth = Math.min(150f, (safe.width - 40f) / difficultyButtons.size - 12f);
            float diffHeight = 44f;
            float rowWidth = difficultyButtons.size * diffWidth
                    + (difficultyButtons.size - 1) * 12f;
            float dx = cx - rowWidth / 2f;
            float dy = modeY - diffHeight - 28f;
            for (int i = 0; i < difficultyButtons.size; i++) {
                UiRect b = difficultyButtons.get(i);
                b.setBounds(dx + i * (diffWidth + 12f), dy, diffWidth, diffHeight);
                DifficultyConfig d = difficulties.all().get(i);
                b.setState(d.id().equals(preferredId())
                        ? UiRect.State.SELECTED : UiRect.State.NORMAL);
            }

            settings.setBounds(safe.right() - 150f - 16f, safe.y + 16f, 150f, 48f);

            controls.add(classic);
            controls.add(endless);
            for (int i = 0; i < difficultyButtons.size; i++) {
                controls.add(difficultyButtons.get(i));
            }
            controls.add(settings);
            TouchTargets.applyAll(controls);
        }

        @Override
        public Array<UiRect> controls() {
            return controls;
        }

        @Override
        public String press(float uiX, float uiY) {
            UiRect hit = TouchTargets.firstHit(controls, uiX, uiY);
            if (hit == null) {
                return null;
            }
            if (hit == classic) {
                nav.chooseMode(GameMode.CLASSIC, preferred());
            } else if (hit == endless) {
                nav.chooseMode(GameMode.ENDLESS, preferred());
            } else if (hit == settings) {
                nav.openSettings();
            } else {
                for (int i = 0; i < difficultyButtons.size; i++) {
                    if (hit == difficultyButtons.get(i)) {
                        setPreferred(difficulties.all().get(i));
                        break;
                    }
                }
            }
            return hit.id;
        }

        private String preferredId() {
            return save != null && save.difficulty != null
                    ? save.difficulty : difficulties.defaultDifficulty().id();
        }

        private DifficultyConfig preferred() {
            return difficulties.get(preferredId());
        }

        private void setPreferred(DifficultyConfig d) {
            if (save != null && nav.canChangePreferredDifficulty()) {
                save.difficulty = d.id();
            }
        }

        public UiRect classicButton() {
            return classic;
        }

        public UiRect endlessButton() {
            return endless;
        }

        public UiRect settingsButton() {
            return settings;
        }

        public Array<UiRect> difficultyButtons() {
            return difficultyButtons;
        }
    }

    // ========================================================================
    //  Settings
    // ========================================================================

    /**
     * Mute, the preferred difficulty, and clearing the high score.
     *
     * <p><b>Exactly the source's three controls.</b> The save can hold a skin, a
     * quality preset and a haptics flag, and the backend honours all three — but
     * Python's settings screen does not show them, so neither does this. A
     * larger settings page would be a new design rather than a port, and adding
     * one is a decision to take deliberately. See {@code UI.md}.
     */
    public static final class Settings implements UiScreen {

        private final DifficultyTable difficulties;
        private final SaveData save;
        private final Navigation nav;
        private final Runnable persist;

        private final Array<UiRect> controls = new Array<>(false, 8);
        private final UiRect mute = new UiRect("settings.mute");
        private final UiRect clearScore = new UiRect("settings.clearScore");
        private final UiRect back = new UiRect("settings.back");
        private final Array<UiRect> difficultyButtons = new Array<>(false, 3);

        public Settings(Navigation nav, DifficultyTable difficulties, SaveData save,
                        Runnable persist) {
            this.nav = nav;
            this.difficulties = difficulties;
            this.save = save;
            this.persist = persist;
            for (DifficultyConfig d : difficulties.all()) {
                difficultyButtons.add(new UiRect("settings.difficulty." + d.id()));
            }
        }

        @Override
        public GameState state() {
            return GameState.SETTINGS;
        }

        @Override
        public void layout(SafeArea safe, TextLayout text) {
            controls.clear();
            float panelWidth = Math.min(640f, safe.width - 40f);
            float cx = safe.centerX();
            float top = safe.top() - 120f;

            mute.setBounds(cx - panelWidth / 2f + 24f, top, 220f, 48f);
            clearScore.setBounds(cx - panelWidth / 2f + 24f, top - 68f, 260f, 48f);

            float diffWidth = Math.min(150f, (panelWidth - 60f) / difficultyButtons.size - 12f);
            float rowWidth = difficultyButtons.size * diffWidth
                    + (difficultyButtons.size - 1) * 12f;
            float dx = cx - rowWidth / 2f;
            float dy = top - 168f;
            for (int i = 0; i < difficultyButtons.size; i++) {
                UiRect b = difficultyButtons.get(i);
                b.setBounds(dx + i * (diffWidth + 12f), dy, diffWidth, 44f);
                b.setState(difficulties.all().get(i).id().equals(preferredId())
                        ? UiRect.State.SELECTED : UiRect.State.NORMAL);
            }

            back.setBounds(cx - 90f, safe.y + 28f, 180f, 52f);

            controls.add(mute);
            controls.add(clearScore);
            for (int i = 0; i < difficultyButtons.size; i++) {
                controls.add(difficultyButtons.get(i));
            }
            controls.add(back);
            TouchTargets.applyAll(controls);
        }

        @Override
        public Array<UiRect> controls() {
            return controls;
        }

        @Override
        public String press(float uiX, float uiY) {
            UiRect hit = TouchTargets.firstHit(controls, uiX, uiY);
            if (hit == null) {
                return null;
            }
            if (hit == mute) {
                //  The game ships silent.  This flips a remembered flag and
                //  nothing else -- there is no audio system to mute, and faking
                //  one would be worse than the silence.
                save.muted = !save.muted;
                save();
            } else if (hit == clearScore) {
                save.highScore = 0;
                save();
            } else if (hit == back) {
                nav.closeSettings();
            } else {
                for (int i = 0; i < difficultyButtons.size; i++) {
                    if (hit == difficultyButtons.get(i)) {
                        if (nav.canChangePreferredDifficulty()) {
                            save.difficulty = difficulties.all().get(i).id();
                            save();
                        }
                        break;
                    }
                }
            }
            return hit.id;
        }

        private void save() {
            if (persist != null) {
                persist.run();
            }
        }

        private String preferredId() {
            return save != null && save.difficulty != null
                    ? save.difficulty : difficulties.defaultDifficulty().id();
        }

        public UiRect muteButton() {
            return mute;
        }

        public UiRect clearScoreButton() {
            return clearScore;
        }

        public UiRect backButton() {
            return back;
        }

        public Array<UiRect> difficultyButtons() {
            return difficultyButtons;
        }
    }

    // ========================================================================
    //  Pause
    // ========================================================================

    /**
     * One button, and nothing else.
     *
     * <p>Python's pause panel is a centred card that says "Press P or ESC to
     * resume" and handles <b>no clicks at all</b> — the mouse handler's chain
     * has no {@code PAUSED} branch. A touch device has no ESC key, so a RESUME
     * button is added; that is the one control here, and it is the smallest
     * addition that makes the state exitable on a phone.
     *
     * <p>There is deliberately no settings button, no restart, no quit. See the
     * class comment.
     */
    public static final class Pause implements UiScreen {

        private final Navigation nav;
        private final Array<UiRect> controls = new Array<>(false, 2);
        private final UiRect resume = new UiRect("pause.resume");

        public Pause(Navigation nav) {
            this.nav = nav;
        }

        @Override
        public GameState state() {
            return GameState.PAUSED;
        }

        @Override
        public void layout(SafeArea safe, TextLayout text) {
            controls.clear();
            resume.setBounds(safe.centerX() - 110f, safe.centerY() - 90f, 220f, 56f);
            controls.add(resume);
            TouchTargets.applyAll(controls);
        }

        @Override
        public Array<UiRect> controls() {
            return controls;
        }

        @Override
        public String press(float uiX, float uiY) {
            UiRect hit = TouchTargets.firstHit(controls, uiX, uiY);
            if (hit == resume) {
                nav.resume();
            }
            //  Modal: a press anywhere is swallowed whether it hit or not, so
            //  the frozen world behind never sees it.
            return hit != null ? hit.id : null;
        }

        public UiRect resumeButton() {
            return resume;
        }
    }

    // ========================================================================
    //  Game over
    // ========================================================================

    /**
     * The result, and the way back.
     *
     * <p>Python takes <b>any</b> click, or {@code R}, and does
     * {@code reset(); state = MENU}. The reset comes first, so nothing from the
     * finished run survives into the front end — that is the Phase 9 path,
     * unchanged.
     */
    public static final class GameOver implements UiScreen {

        private final Navigation nav;
        private final Array<UiRect> controls = new Array<>(false, 2);
        private final UiRect restart = new UiRect("gameover.restart");

        public GameOver(Navigation nav) {
            this.nav = nav;
        }

        @Override
        public GameState state() {
            return GameState.GAMEOVER;
        }

        @Override
        public void layout(SafeArea safe, TextLayout text) {
            controls.clear();
            restart.setBounds(safe.centerX() - 140f, safe.y + safe.height * 0.18f,
                    280f, 58f);
            controls.add(restart);
            TouchTargets.applyAll(controls);
        }

        @Override
        public Array<UiRect> controls() {
            return controls;
        }

        @Override
        public String press(float uiX, float uiY) {
            //  Any press anywhere returns to the menu, as in the source; the
            //  button is where the player is told to press.
            nav.returnToMenu();
            UiRect hit = TouchTargets.firstHit(controls, uiX, uiY);
            return hit != null ? hit.id : "gameover.anywhere";
        }

        public UiRect restartButton() {
            return restart;
        }
    }
}
