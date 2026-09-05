package com.mymmer.castledefense.ui;

import com.badlogic.gdx.utils.Array;
import com.mymmer.castledefense.config.DifficultyTable;
import com.mymmer.castledefense.game.GameState;
import com.mymmer.castledefense.game.RunWorld;
import com.mymmer.castledefense.input.Pointer;
import com.mymmer.castledefense.input.UiConsumer;
import com.mymmer.castledefense.persistence.SaveData;
import com.mymmer.castledefense.platform.SafeAreaInsets;
import com.mymmer.castledefense.render.ViewportSet;

/**
 * The interface, assembled: which screen is up, and who gets a press.
 *
 * <h2>One input path, not two</h2>
 *
 * <p>{@code UiRoot} is a {@link UiConsumer}, registered with the Phase 4
 * {@code InputRouter} like any other. It does not read a mouse, poll a touch or
 * install a second processor. That is the whole reason a press it claims can
 * never also grab a mob: the router's existing rule — first claim owns the
 * pointer for its whole life, through drag, release and cancel — does the work,
 * and there is no second path for a press to arrive by.
 *
 * <h2>Modality</h2>
 *
 * <p>Every screen but the HUD is modal, and a modal screen claims <b>every</b>
 * press inside the UI viewport whether or not it landed on a control. So a tap
 * beside the pause panel does not reach the frozen world behind it, and a tap
 * between two shop cards does not grab an enemy.
 *
 * <p>The HUD is not modal: a press that misses its controls falls through, which
 * is how throwing works at all.
 *
 * <h2>Coordinates</h2>
 *
 * <p>A {@link Pointer} already carries <b>both</b> spaces: Phase 4's
 * {@code GameInput} is the only class in {@code core} that sees a screen pixel,
 * and it projects each press through the world viewport and the UI viewport
 * once. So this class reads {@code uiX()/uiY()} for hit testing and
 * {@code worldX()/worldY()} for skill targeting, and converts nothing.
 */
public final class UiRoot implements UiConsumer {

    private final RunWorld run;
    private final Navigation nav;
    private final ViewportSet viewports;
    private final DifficultyTable difficulties;
    private final SaveData save;

    private final HudScreen hud;
    private final MenuScreens.MainMenu menu;
    private final MenuScreens.Settings settings;
    private final MenuScreens.Pause pause;
    private final MenuScreens.GameOver gameOver;
    private final ShopScreen shop;
    private final TalentScreen talents;

    private TextLayout text;
    private SafeAreaInsets insets = SafeAreaInsets.NONE;
    private SafeArea safe = SafeArea.full(1280f, 720f);

    /** The last control pressed, for the debug overlay and the tests. */
    private String lastPressed;
    /** Which screen owns the pointer it claimed, so drag and release match. */
    private UiScreen owner;

    public UiRoot(RunWorld run, ViewportSet viewports, DifficultyTable difficulties,
                  SaveData save, Runnable persist, TextLayout text) {
        if (run == null || viewports == null) {
            throw new IllegalArgumentException("run and viewports must not be null");
        }
        this.run = run;
        this.viewports = viewports;
        this.text = text != null ? text : new TextLayout(new FixedMeasurer());
        this.nav = new Navigation(run);

        this.hud = new HudScreen(run);
        this.difficulties = difficulties;
        this.save = save;
        //  Seeded from the save so it is never null: the menu shows a selected
        //  difficulty on the first frame, and starting a run before touching
        //  the setting uses the one that was saved rather than nothing.
        this.menuDifficulty = difficulties.contains(save != null ? save.difficulty : null)
                ? difficulties.get(save.difficulty)
                : difficulties.defaultDifficulty();
        this.menu = new MenuScreens.MainMenu(nav, difficulties, save);
        this.settings = new MenuScreens.Settings(nav, difficulties, save, persist);
        this.pause = new MenuScreens.Pause(nav);
        this.gameOver = new MenuScreens.GameOver(nav);
        this.shop = new ShopScreen(run, nav);
        this.talents = new TalentScreen(run, nav);
    }

    /** A stand-in when no font exists yet. Proportional and predictable. */
    static final class FixedMeasurer implements TextLayout.Measurer {
        @Override
        public float width(String s, float size) {
            return s == null ? 0f : s.length() * size * 0.5f;
        }

        @Override
        public float lineHeight(float size) {
            return size * 1.2f;
        }
    }

    public void setTextLayout(TextLayout text) {
        if (text != null) {
            this.text = text;
        }
    }

    /** The platform's cutouts, in screen pixels. */
    public void setSafeAreaInsets(SafeAreaInsets insets) {
        this.insets = insets != null ? insets : SafeAreaInsets.NONE;
    }

    public Navigation navigation() {
        return nav;
    }

    public SafeArea safeArea() {
        return safe;
    }

    public TextLayout text() {
        return text;
    }

    public String lastPressed() {
        return lastPressed;
    }

    // ========================================================================
    //  Layout
    // ========================================================================

    /**
     * Recomputes the safe area and lays the current screen out.
     *
     * <p>Called on resize and once per rendered frame. It reads gameplay state
     * but changes none, and it uses no delta of any kind — a control's position
     * is a function of the viewport and the game's state, never of time.
     */
    public void layout() {
        safe = SafeArea.of(viewports, insets);
        //  The HUD is laid out in every in-run state, because it is drawn under
        //  the modal panels exactly as the source draws it under theirs.
        if (nav.inRun() || run.world().state() == GameState.GAMEOVER) {
            hud.layout(safe, text);
        }
        UiScreen modal = modalScreen();
        if (modal != null) {
            modal.layout(safe, text);
        }
    }

    /** The screen for the current state, or null while simply playing. */
    public UiScreen modalScreen() {
        switch (run.world().state()) {
            case MENU:
                return menu;
            case SETTINGS:
                return settings;
            case SHOP:
                return shop;
            case TALENTS:
                return talents;
            case PAUSED:
                return pause;
            case GAMEOVER:
                return gameOver;
            case PLAYING:
            default:
                return null;
        }
    }

    // ========================================================================
    //  Input
    // ========================================================================

    @Override
    public boolean onPress(Pointer pointer) {
        layout();
        //  The pointer already carries both spaces: Phase 4's GameInput is the
        //  only class in core that sees a screen pixel, and it converted once.
        float uiX = pointer.uiX();
        float uiY = pointer.uiY();
        UiScreen modal = modalScreen();

        if (modal != null) {
            lastPressed = modal.press(uiX, uiY);
            owner = modal;
            //  Modal: claimed whether or not a control was hit, so nothing
            //  reaches the world behind the panel.
            return true;
        }

        //  Playing.  The skill bar, the armoury button and the horn get first
        //  refusal, exactly as in the source.
        String hit = hud.press(uiX, uiY);
        if (hit != null) {
            lastPressed = hit;
            owner = hud;
            return true;
        }
        //  An armed skill consumes the next world tap; placing a bolt must not
        //  also pick up whatever was standing there.
        if (hud.castArmedSkillAt(pointer.worldX(), pointer.worldY())) {
            lastPressed = "world.skillTarget";
            owner = hud;
            return true;
        }
        return false;                       // the world may have it
    }

    @Override
    public void onDrag(Pointer pointer) {
        //  A claimed pointer stays claimed.  No screen acts on a drag today --
        //  the shop and the tree are tap-only, as in the source -- but the
        //  ownership has to be honoured or the router's contract breaks.
    }

    @Override
    public void onRelease(Pointer pointer) {
        owner = null;
    }

    @Override
    public String consumerName() {
        return "UiRoot";
    }

    // ========================================================================
    //  Keys -- the desktop equivalents of every touch route
    // ========================================================================

    /** Semantic key commands, so {@code ui} never sees a keycode. */
    public enum Key {
        TOGGLE_TALENTS,
        BACK,
        CONFIRM,
        MODE_CLASSIC,
        MODE_ENDLESS,
        SKILL_1,
        SKILL_2,
        SKILL_3,
        SHOP_ITEM_1, SHOP_ITEM_2, SHOP_ITEM_3, SHOP_ITEM_4, SHOP_ITEM_5,
        SHOP_ITEM_6, SHOP_ITEM_7, SHOP_ITEM_8, SHOP_ITEM_9, SHOP_ITEM_10
    }

    /**
     * One key command.
     *
     * <p>The precedence is the source's: the talent door is checked first and is
     * consumed in every state, then the state-specific routes.
     */
    /**
     * Android's Back, and the desktop Escape that mirrors it.
     *
     * <p>Returns the graph's own answer rather than a boolean, because
     * {@link Navigation.BackResult#EXIT_APP} is a decision the platform layer has
     * to make — closing the activity is not something this class should do, and
     * "unhandled" is not the same thing as "leave".
     *
     * <p>There is no per-screen back handler anywhere: every route lives in
     * {@link Navigation}, so what Back does from a given state is written down in
     * one place and tested there. See {@code UI.md}.
     */
    public Navigation.BackResult back() {
        return nav.back();
    }

    public boolean key(Key key) {
        GameState state = run.world().state();
        if (key == Key.TOGGLE_TALENTS) {
            if (state == GameState.TALENTS) {
                nav.closeTalents();
            } else {
                nav.openTalents();
            }
            return true;                    // consumed either way, as in the source
        }
        switch (key) {
            case BACK:
                //  As a key it is only "did something happen"; the platform layer
                //  wanting to know whether to close the app calls back() itself.
                return back() == Navigation.BackResult.HANDLED;
            case CONFIRM:
                if (state == GameState.MENU) {
                    return nav.chooseMode(com.mymmer.castledefense.game.GameMode.CLASSIC,
                            menuPreferred());
                }
                if (state == GameState.SHOP) {
                    return nav.startPlaying();
                }
                if (state == GameState.GAMEOVER) {
                    return nav.returnToMenu();
                }
                return false;
            case MODE_CLASSIC:
                return state == GameState.MENU
                        && nav.chooseMode(com.mymmer.castledefense.game.GameMode.CLASSIC,
                                menuPreferred());
            case MODE_ENDLESS:
                return state == GameState.MENU
                        && nav.chooseMode(com.mymmer.castledefense.game.GameMode.ENDLESS,
                                menuPreferred());
            case SKILL_1:
            case SKILL_2:
            case SKILL_3:
                return castOrArm(key.ordinal() - Key.SKILL_1.ordinal());
            default:
                if (state == GameState.SHOP && key.name().startsWith("SHOP_ITEM_")) {
                    return shop.buyByIndex(key.ordinal() - Key.SHOP_ITEM_1.ordinal());
                }
                return false;
        }
    }

    private boolean castOrArm(int slot) {
        if (run.world().state() != GameState.PLAYING) {
            return false;
        }
        Array<com.mymmer.castledefense.skill.SkillId> unlocked =
                run.skills().unlockedSkills();
        if (slot < 0 || slot >= unlocked.size) {
            return false;
        }
        com.mymmer.castledefense.skill.SkillId id = unlocked.get(slot);
        return id.needsTarget()
                ? run.skills().select(id)
                : run.skills().castAt(id,
                        com.mymmer.castledefense.config.GameConfig.WORLD_WIDTH / 2f,
                        com.mymmer.castledefense.config.GameConfig.GROUND_Y);
    }

    private com.mymmer.castledefense.config.DifficultyConfig menuPreferred() {
        return menuDifficulty;
    }

    private com.mymmer.castledefense.config.DifficultyConfig menuDifficulty;

    /** The difficulty the next run will use. Set by the menu, read on start. */
    public void setPreferredDifficulty(
            com.mymmer.castledefense.config.DifficultyConfig d) {
        this.menuDifficulty = d;
    }

    // ========================================================================
    //  Screens, for the renderer and the tests
    // ========================================================================

    /**
     * The difficulty the next run will use.
     *
     * <p>Read-only on purpose. Changing it is {@code setPreferredDifficulty},
     * which the navigation graph gates to MENU and SETTINGS -- a run already in
     * progress keeps the difficulty it was created with, and there is no route
     * from inside a run to a screen that could offer this.
     */
    public com.mymmer.castledefense.config.DifficultyConfig preferredDifficulty() {
        return menuDifficulty;
    }

    /** The difficulty table, for a renderer that needs to name one. */
    public DifficultyTable difficulties() {
        return difficulties;
    }

    /** The saved settings, for a renderer that needs to show one. */
    public SaveData saveData() {
        return save;
    }

    public HudScreen hud() {
        return hud;
    }

    public MenuScreens.MainMenu menu() {
        return menu;
    }

    public MenuScreens.Settings settings() {
        return settings;
    }

    public MenuScreens.Pause pause() {
        return pause;
    }

    public MenuScreens.GameOver gameOver() {
        return gameOver;
    }

    public ShopScreen shop() {
        return shop;
    }

    public TalentScreen talents() {
        return talents;
    }

    /** Every control currently on screen, modal panel and HUD together. */
    public Array<UiRect> allControls() {
        Array<UiRect> out = new Array<>(false, 48);
        UiScreen modal = modalScreen();
        if (modal != null) {
            out.addAll(modal.controls());
        } else {
            out.addAll(hud.controls());
        }
        return out;
    }
}
