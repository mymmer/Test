package com.mymmer.castledefense.ui;

import com.badlogic.gdx.utils.Array;
import com.mymmer.castledefense.boss.Boss;
import com.mymmer.castledefense.game.GameState;
import com.mymmer.castledefense.game.RunWorld;
import com.mymmer.castledefense.skill.SkillId;

/**
 * The in-play interface: the stat panel, the skill bar, the horn, the boss bars.
 *
 * <h2>It reads; it does not remember</h2>
 *
 * <p>There is no {@code displayGold}, no {@code displayWave}, no
 * {@code displayBossHp}. Every number is fetched from the subsystem that owns it
 * at the moment it is laid out — {@code RunSession} for the purse and the score,
 * {@code Castle} for health, {@code SkillPanel} for readiness,
 * {@code BossRegistry} for who is on the field. A cached copy is a copy that can
 * be wrong, and the HUD is where a wrong number is most visible.
 *
 * <h2>The row stack</h2>
 *
 * <p>Python builds the panel as a list of measured rows and then lays them out,
 * so the panel is exactly as tall as its contents and a row that is not
 * applicable simply is not added. That is reproduced, because it is what keeps
 * the stack from overlapping when a row grows: the rows are
 *
 * <pre>
 *   1   WAVE/TIER          + difficulty badge + gold          (right-aligned)
 *   1b  the badge alone, when row 1 was too narrow to hold it
 *   2   wall tier label    + endgame horde name
 *   3   crowd multiplier   -- only while playing, and only above 1.005
 *   4   castle health bar  + the numbers on it
 *   5   score              + best fling
 *   6   talent points      + skills ready
 *   7   run clock          + the SHOP button        -- ENDLESS ONLY
 * </pre>
 *
 * <p>Row 7 is the reason there is no armoury button in Classic: the source only
 * creates that rectangle inside the Endless branch, so in Classic there is
 * nothing there to press.
 *
 * <h2>Not modal</h2>
 *
 * <p>The only screen that is not. A press that misses every control falls
 * through to the world, which is how grabbing works at all.
 */
public final class HudScreen implements UiScreen {

    /**
     * Panel geometry, in UI units. The source's own {@code HUD_*} constants.
     *
     * <p>Phase 10 had guessed 300/16/12/8; the real values are
     * {@code HUD_W = 372}, {@code HUD_X, HUD_Y = 14, 12}, {@code HUD_PAD = 14},
     * {@code HUD_ROW_GAP = 6}. A 372-wide panel is why the source can fit the
     * wall name and the gold on one row without either being cramped.
     */
    public static final float PANEL_WIDTH = 372f;
    public static final float PANEL_X = 14f;
    public static final float PANEL_TOP_GAP = 12f;
    public static final float PANEL_PAD = 14f;
    public static final float ROW_GAP = 6f;
    /** {@code HUD_GAP}: the least space between two items sharing a row. */
    public static final float ROW_ITEM_GAP = 16f;

    /** The skill bar's slots, from {@code SkillPanel.SLOT}/{@code GAP}. */
    public static final float SKILL_SLOT = 62f;
    public static final float SKILL_GAP = 12f;

    /**
     * Boss bar geometry, from {@code draw_hud}'s boss-bar block.
     *
     * <p>{@code HEIGHT - 50} with height 20 leaves the bar's bottom edge 30
     * above the floor; the name sits at {@code HEIGHT - 78} and the hit points
     * at {@code HEIGHT - 48}, i.e. just inside the bar's top.
     */
    public static final float BOSS_BAR_WIDE = 620f;
    public static final float BOSS_BAR_PAIR = 400f;
    public static final float BOSS_BAR_HEIGHT = 20f;
    public static final float BOSS_BAR_GAP = 24f;
    public static final float BOSS_BAR_BOTTOM = 30f;
    /** Name baseline above the bar, and the hit points inside it. */
    public static final float BOSS_NAME_TOP = 78f;
    public static final float BOSS_HP_TOP = 48f;

    /**
     * The horn, from {@code HORN_RECT = Rect(170, 452, 58, 60)}.
     *
     * <p>Its size and its place on the castle wall are the source's. Phase 10
     * had guessed a 132x46 button in the top-right corner, which the Phase 11.5
     * image comparison showed both differs from the source and collides with the
     * field readout that lives up there.
     */
    public static final float HORN_WIDTH = 58f;
    public static final float HORN_HEIGHT = 60f;
    /** Source position, from the left and (converted) from the bottom. */
    public static final float HORN_X = 170f;
    public static final float HORN_Y = 720f - 452f - HORN_HEIGHT;

    private final RunWorld run;

    private final Array<UiRect> controls = new Array<>(false, 8);
    private final UiRect shopButton = new UiRect("hud.shop");
    private final UiRect hornButton = new UiRect("hud.horn");
    private final Array<UiRect> skillSlots = new Array<>(false, 3);
    private final Array<UiRect> bossBars = new Array<>(false, 4);

    /** Where the stat panel ended up, for the renderer and the layout tests. */
    private float panelX;
    private float panelY;
    private float panelWidth;
    private float panelHeight;

    /** The rows the last layout produced, as ids, for tests and the renderer. */
    private final Array<String> rows = new Array<>(false, 8);
    /** Each row's height and its top edge, parallel to {@link #rows}. */
    private final com.badlogic.gdx.utils.FloatArray rowHeights =
            new com.badlogic.gdx.utils.FloatArray(8);
    private final com.badlogic.gdx.utils.FloatArray rowTops =
            new com.badlogic.gdx.utils.FloatArray(8);

    /** The castle health bar's height, and the Endless clock row's. */
    public static final float HEALTH_BAR_HEIGHT = 18f;
    public static final float CLOCK_ROW_HEIGHT = 26f;

    private void addRow(String id, float height) {
        rows.add(id);
        rowHeights.add(height);
    }

    public HudScreen(RunWorld run) {
        if (run == null) {
            throw new IllegalArgumentException("run must not be null");
        }
        this.run = run;
        for (SkillId id : SkillId.values()) {
            skillSlots.add(new UiRect("hud.skill." + id.id()));
        }
    }

    @Override
    public GameState state() {
        return GameState.PLAYING;
    }

    @Override
    public boolean modal() {
        return false;
    }

    // ========================================================================
    //  Layout
    // ========================================================================

    @Override
    public void layout(SafeArea safe, TextLayout text) {
        controls.clear();
        rows.clear();

        GameState state = run.world().state();
        boolean playing = state == GameState.PLAYING || state == GameState.PAUSED;

        layoutStatPanel(safe, text, playing);
        layoutSkillBar(safe, playing);
        layoutHorn(safe, playing);
        layoutBossBars(safe, playing);

        //  Priority order: the skill bar gets first refusal on a press, exactly
        //  as in the source, then the armoury button, then the horn.  The world
        //  is last and is not a control at all.
        for (int i = 0; i < skillSlots.size; i++) {
            controls.add(skillSlots.get(i));
        }
        controls.add(shopButton);
        controls.add(hornButton);
    }

    /**
     * The measured row stack.
     *
     * <p>Rows are appended only when they apply, then the panel is sized to fit
     * them — never the other way round, which is what stops a long wall label
     * from pushing the score out of the box.
     */
    private void layoutStatPanel(SafeArea safe, TextLayout text, boolean playing) {
        rowHeights.clear();
        rowTops.clear();

        //  Measure first: append a row only when it applies, and remember how
        //  tall it is.  Positions are assigned afterwards, from the panel's own
        //  top -- which is what makes "row N does not touch row N+1" something a
        //  test can assert rather than something a draw method hopes for.
        addRow("hud.row.title", text.lineHeight(30f));

        //  1b: only when the gold and the badge could not share row 1
        float inner = PANEL_WIDTH - PANEL_PAD * 2f;
        String title = titleText();
        float titleWidth = text.width(title, 30f);
        String badge = badgeText();
        if (!badge.isEmpty() && !badgeFitsBesideGold(text, inner, titleWidth, badge)) {
            addRow("hud.row.badge", text.lineHeight(17f));
        }

        addRow("hud.row.wall", text.lineHeight(18f));

        if (playing && run.goldMultiplier() > 1.005f) {
            addRow("hud.row.multiplier", text.lineHeight(18f));
        }

        addRow("hud.row.health", HEALTH_BAR_HEIGHT);
        addRow("hud.row.score", text.lineHeight(28f));
        addRow("hud.row.talents", text.lineHeight(19f));

        if (run.session().isEndless()) {
            addRow("hud.row.clock", CLOCK_ROW_HEIGHT);
        }

        float total = 0f;
        for (int i = 0; i < rowHeights.size; i++) {
            total += rowHeights.get(i);
        }
        total += ROW_GAP * Math.max(0, rows.size - 1);

        panelWidth = PANEL_WIDTH;
        panelHeight = total + PANEL_PAD * 2f;
        //  Anchored to the DISPLAY rectangle, not the touch one.
        //
        //  The touch rectangle also excludes the system's gesture strips, and
        //  the top strip is 84px -- 42 interface units -- on this phone. Hanging
        //  the panel from there pushed the whole thing 42 units down the screen
        //  and its bottom edge landed on the keep's cannon at world y 205-235,
        //  which is exactly the turret that became hard to use.
        //
        //  Nothing about this panel needs to avoid a gesture strip. A strip
        //  steals swipes, and the panel's only control is the Endless SHOP
        //  button sitting at its bottom edge, far from the top. Controls are
        //  still checked against the touch rectangle by SafeAreaLayoutTest, so
        //  a future row that pushed the button into a strip would fail there.
        panelX = safe.x + PANEL_X;
        panelY = safe.top() - panelHeight - PANEL_TOP_GAP;

        //  Now the positions.  Each row's TOP edge, walking down from the pad,
        //  with ROW_GAP between one row's bottom and the next row's top.
        float top = panelY + panelHeight - PANEL_PAD;
        for (int i = 0; i < rows.size; i++) {
            rowTops.add(top);
            top -= rowHeights.get(i) + ROW_GAP;
        }

        //  The armoury button rides in the clock row, and only in Endless.
        shopButton.setVisible(false);
        if (run.session().isEndless() && playing) {
            //  The button rides in the clock row, which the loop above has now
            //  positioned -- so it follows the row rather than assuming the row
            //  is the last thing above the padding.
            float rowY = rowBottom("hud.row.clock");
            shopButton.setVisible(true)
                    .setBounds(panelX + panelWidth - PANEL_PAD - 106f, rowY, 106f, 26f);
            TouchTargets.apply(shopButton);
            shopButton.setState(run.world().state() == GameState.PLAYING
                    ? UiRect.State.NORMAL : UiRect.State.DISABLED);
        }
    }

    private boolean badgeFitsBesideGold(TextLayout text, float inner, float titleWidth,
                                        String badge) {
        String gold = String.valueOf(run.session().gold()) + " G";
        float needed = text.width(gold, 26f) + text.width(badge, 17f) + ROW_GAP;
        return titleWidth + needed <= inner;
    }

    /** Centred along the bottom, one slot per unlocked skill. */
    private void layoutSkillBar(SafeArea safe, boolean playing) {
        int n = run.skills().unlockedCount();
        for (int i = 0; i < skillSlots.size; i++) {
            skillSlots.get(i).setVisible(false);
        }
        if (!playing || n == 0) {
            return;
        }
        float total = n * SKILL_SLOT + (n - 1) * SKILL_GAP;
        float x = safe.touchCenterX() - total / 2f;
        float y = safe.touchY + 34f;

        Array<SkillId> unlocked = run.skills().unlockedSkills();
        for (int i = 0; i < unlocked.size; i++) {
            SkillId id = unlocked.get(i);
            UiRect slot = slotFor(id);
            slot.setVisible(true)
                    .setBounds(x + i * (SKILL_SLOT + SKILL_GAP), y, SKILL_SLOT, SKILL_SLOT);
            TouchTargets.apply(slot);
            //  A slot is always pressable: Python's handle_click consumes the
            //  click and `activate` refuses with a message.  SELECTED means
            //  armed and waiting for a world tap.  Whether it is READY is the
            //  panel's answer -- skills().isReady(id) -- and the renderer asks
            //  that, never a remaining-seconds comparison here.  See UI.md.
            slot.setState(run.skills().aiming() == id
                    ? UiRect.State.SELECTED : UiRect.State.NORMAL);
        }
    }

    private UiRect slotFor(SkillId id) {
        return skillSlots.get(id.ordinal());
    }

    /** Top-right, opposite the stat panel. */
    private void layoutHorn(SafeArea safe, boolean playing) {
        hornButton.setVisible(playing);
        if (!playing) {
            return;
        }
        //  The source's own place for it, offset into the safe rectangle so a
        //  cutout cannot eat it on a phone.
        hornButton.setBounds(safe.touchX + HORN_X, safe.touchY + HORN_Y,
                HORN_WIDTH, HORN_HEIGHT);
        TouchTargets.apply(hornButton);
        //  Pressable even when spent, so the press is consumed rather than
        //  falling through and grabbing a mob behind the button.  Whether it
        //  WORKS is the horn's own rule -- session().hornUsed() -- and in
        //  Endless that stays true for the whole run.
        hornButton.setState(UiRect.State.NORMAL);
    }

    /**
     * One bar per live boss.
     *
     * <p>A <b>list</b>, never a {@code currentBoss}: Phase 7 proved two can be on
     * the field at once and the registry is the authority on who. Bars beyond
     * the ones in use are hidden rather than stale, so a boss that dies takes
     * its bar with it on the very next layout.
     */
    private void layoutBossBars(SafeArea safe, boolean playing) {
        Array<Boss> live = run.bossRegistry().liveBosses();
        while (bossBars.size < live.size) {
            bossBars.add(new UiRect("hud.boss." + bossBars.size));
        }
        for (int i = 0; i < bossBars.size; i++) {
            bossBars.get(i).setVisible(false);
        }
        if (!playing) {
            return;
        }
        //  The source's exact arrangement:
        //
        //      bw   = 620 for one boss, 400 each for two
        //      step = bw + 24
        //      x0   = WIDTH/2 - (n * step - 24) / 2
        //      bar  at y = HEIGHT - 50, height 20
        //
        //  which puts the bar's BOTTOM edge 30 units up from the floor.  Widths
        //  are clamped to the safe rectangle so a narrow phone does not push a
        //  pair off the sides.
        int shown = Math.min(2, live.size);
        if (shown == 0) {
            return;
        }
        float barHeight = BOSS_BAR_HEIGHT;
        float barWidth = shown == 1
                ? Math.min(BOSS_BAR_WIDE, safe.width - 40f)
                : Math.min(BOSS_BAR_PAIR, (safe.width - 40f - BOSS_BAR_GAP) / 2f);
        float step = barWidth + BOSS_BAR_GAP;
        float x0 = safe.centerX() - (shown * step - BOSS_BAR_GAP) / 2f;
        float y = safe.y + BOSS_BAR_BOTTOM;
        for (int i = 0; i < shown; i++) {
            bossBars.get(i).setVisible(true)
                    .setBounds(x0 + i * step, y, barWidth, barHeight);
        }
    }

    // ========================================================================
    //  Commands
    // ========================================================================

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
        if (hit == shopButton) {
            //  Ask; the world decides.  A refusal is still a consumed press --
            //  the finger landed on a button, so it must not also grab a mob.
            run.openRealtimeShop();
            return hit.id;
        }
        if (hit == hornButton) {
            //  The command only.  Which units arrive is entirely the horn's.
            if (run.director() != null) {
                run.director().blowHorn();
            }
            return hit.id;
        }
        for (int i = 0; i < skillSlots.size; i++) {
            if (hit == skillSlots.get(i)) {
                SkillId id = SkillId.values()[i];
                if (id.needsTarget()) {
                    //  Two stages, as in the source: arming, then a world tap.
                    //  Arming here is what stops the same press reaching the mob
                    //  standing under the button.
                    run.skills().select(id);
                } else {
                    //  Untargeted, so it fires where the cursor already is --
                    //  Python casts at mouse_pos.  The centre of the arena is
                    //  the touch equivalent; see UI.md, "deliberate differences".
                    run.skills().castAt(id,
                            com.mymmer.castledefense.config.GameConfig.WORLD_WIDTH / 2f,
                            com.mymmer.castledefense.config.GameConfig.GROUND_Y);
                }
                return hit.id;
            }
        }
        return hit.id;
    }

    /**
     * A tap on the world while a skill is armed.
     *
     * <p>Offered <b>before</b> the world sees the press, so placing a bolt never
     * also grabs whatever was standing there. Returns true when it consumed the
     * tap.
     */
    public boolean castArmedSkillAt(float worldX, float worldY) {
        if (run.skills().aiming() == null) {
            return false;
        }
        return run.skills().castAimedAt(worldX, worldY);
    }

    // ========================================================================
    //  Read-only geometry, for the renderer and the layout tests
    // ========================================================================

    public float panelX() {
        return panelX;
    }

    public float panelY() {
        return panelY;
    }

    public float panelWidth() {
        return panelWidth;
    }

    public float panelHeight() {
        return panelHeight;
    }

    /** The row ids the last layout produced, top to bottom. */
    public Array<String> rows() {
        return rows;
    }

    // ========================================================================
    //  Row geometry, for the renderer and the overlap tests
    // ========================================================================

    /** Whether the last layout produced this row at all. */
    public boolean hasRow(String id) {
        return rows.indexOf(id, false) >= 0;
    }

    /** A row's TOP edge in UI units, or 0 when the row is not present. */
    public float rowTop(String id) {
        int i = rows.indexOf(id, false);
        return i < 0 ? 0f : rowTops.get(i);
    }

    /** A row's height, or 0 when it is not present. */
    public float rowHeight(String id) {
        int i = rows.indexOf(id, false);
        return i < 0 ? 0f : rowHeights.get(i);
    }

    /** A row's BOTTOM edge, which is where a bar or a button in it sits. */
    public float rowBottom(String id) {
        return rowTop(id) - rowHeight(id);
    }

    public UiRect shopButton() {
        return shopButton;
    }

    public UiRect hornButton() {
        return hornButton;
    }

    public Array<UiRect> skillSlots() {
        return skillSlots;
    }

    /** One bar per live boss; hidden entries are not in use. */
    public Array<UiRect> bossBars() {
        return bossBars;
    }

    public int visibleBossBars() {
        int n = 0;
        for (int i = 0; i < bossBars.size; i++) {
            if (bossBars.get(i).visible()) {
                n++;
            }
        }
        return n;
    }

    private String titleText() {
        int n = Math.max(1, run.session().wave());
        return (run.session().isEndless() ? "TIER " : "WAVE ") + n;
    }

    private String badgeText() {
        //  The RUN's difficulty, not the saved preference.  Python reads
        //  settings here; they cannot differ during a run, and reading the run
        //  is the one that stays correct if that ever changes.
        return run.session().difficulty() == null
                ? "" : run.session().difficulty().label();
    }
}
