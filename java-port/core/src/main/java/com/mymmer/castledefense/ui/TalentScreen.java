package com.mymmer.castledefense.ui;

import com.badlogic.gdx.utils.Array;
import com.mymmer.castledefense.game.GameState;
import com.mymmer.castledefense.game.RunWorld;
import com.mymmer.castledefense.talent.TalentBranch;
import com.mymmer.castledefense.talent.TalentDef;
import com.mymmer.castledefense.talent.TalentTree;

/**
 * The talent tree: six branch columns, thirty-eight nodes.
 *
 * <h2>Data-driven, not thirty-eight handlers</h2>
 *
 * <p>The layout walks the table — branch, then tier order within the branch —
 * and builds one {@link UiRect} per node, keyed by the node's <b>stable
 * id</b>. There is no per-talent code anywhere in this class and no switch on a
 * talent name. Adding a node to {@code data/talents.json} makes it appear; the
 * only thing that would need touching is a localisation key.
 *
 * <h2>It reads the tree; it never computes an effect</h2>
 *
 * <pre>
 *   rank        tree.rank(id)
 *   cap         def.maxRank
 *   unlocked    tree.isUnlocked(id)
 *   buyable     tree.canPurchase(id)
 *   points      tree.availablePoints()
 *   buying      tree.purchase(id)
 * </pre>
 *
 * <p>No effect formula is duplicated here. The description a tooltip shows is a
 * localised string with the tree's own {@code value(id)} substituted into it —
 * so the number in the tooltip is the number the game is using, not a second
 * calculation of it.
 *
 * <h2>Deep Foundations</h2>
 *
 * <p>Its description is the source's, unchanged: <em>"Castle maximum health
 * +50%"</em> at rank 5. That claim is not true in the shipped game — nothing
 * reads the value — and the port reproduces the game rather than correcting its
 * tooltip. The mismatch is recorded in {@code PORT_ANALYSIS.md} §13 and in
 * {@code TALENTS.md}. This screen reaches {@code value(id)} for the number and
 * has no access at all to anything that could wire it into the castle.
 *
 * <h2>Tap to inspect, tap again to buy</h2>
 *
 * <p>Python hovers for the detail panel and clicks to buy, which a touchscreen
 * cannot do. Here the first tap on a node <b>selects</b> it and shows its
 * details; a tap on the already-selected node buys a rank. Desktop hover still
 * previews without selecting. The gameplay command is identical either way —
 * {@code purchase(id)} — and nothing about the two-stage interaction reaches the
 * tree. See {@code UI.md}, "deliberate differences".
 */
public final class TalentScreen implements UiScreen {

    /** Node box, in UI units. */
    public static final float NODE_WIDTH = 150f;
    public static final float NODE_HEIGHT = 46f;
    public static final float NODE_GAP = 8f;

    private final RunWorld run;
    private final Navigation nav;

    private final Array<UiRect> controls = new Array<>(false, 40);
    private final Array<UiRect> nodes = new Array<>(false, 40);
    private final Array<TalentDef> order = new Array<>(false, 40);
    private final UiRect back = new UiRect("talent.back");

    /** The node whose details are showing, or null. Presentation only. */
    private String selected;
    /** Rows scrolled off the top, for a branch column taller than the panel. */
    private int scroll;
    private int maxScroll;

    public TalentScreen(RunWorld run, Navigation nav) {
        if (run == null || nav == null) {
            throw new IllegalArgumentException("run and nav must not be null");
        }
        this.run = run;
        this.nav = nav;
        //  Branch by branch, in the table's declared order within each -- the
        //  source's column layout, driven entirely by the data.
        TalentTree tree = run.talentTree();
        for (TalentBranch branch : TalentBranch.values()) {
            Array<TalentDef> inBranch = tree.table().branch(branch);
            for (int i = 0; i < inBranch.size; i++) {
                order.add(inBranch.get(i));
                nodes.add(new UiRect("talent." + inBranch.get(i).id));
            }
        }
    }

    @Override
    public GameState state() {
        return GameState.TALENTS;
    }

    // ========================================================================
    //  Layout
    // ========================================================================

    @Override
    public void layout(SafeArea safe, TextLayout text) {
        controls.clear();
        TalentTree tree = run.talentTree();

        int branches = TalentBranch.values().length;
        float columnGap = 10f;
        //  Talent nodes are pressable, so the tree follows the touch rect.
        float usable = safe.touchWidth - 32f;
        float columnWidth = Math.min(NODE_WIDTH,
                (usable - columnGap * (branches - 1)) / branches);
        float totalWidth = branches * columnWidth + columnGap * (branches - 1);
        float x0 = safe.touchCenterX() - totalWidth / 2f;

        float headerHeight = 34f;
        float top = safe.touchTop() - 76f - headerHeight;
        float bottom = safe.touchY + 96f;
        float columnHeight = top - bottom;

        //  the deepest branch decides whether the panel needs to scroll
        int deepest = 0;
        for (TalentBranch b : TalentBranch.values()) {
            deepest = Math.max(deepest, tree.table().branch(b).size);
        }
        int visibleRows = Math.max(1, (int) ((columnHeight + NODE_GAP)
                / (NODE_HEIGHT + NODE_GAP)));
        maxScroll = Math.max(0, deepest - visibleRows);
        if (scroll > maxScroll) {
            scroll = maxScroll;
        }

        int index = 0;
        for (int b = 0; b < branches; b++) {
            TalentBranch branch = TalentBranch.values()[b];
            Array<TalentDef> inBranch = tree.table().branch(branch);
            float cx = x0 + b * (columnWidth + columnGap);
            for (int row = 0; row < inBranch.size; row++) {
                UiRect node = nodes.get(index);
                TalentDef def = order.get(index);
                index++;

                int visualRow = row - scroll;
                boolean onScreen = visualRow >= 0 && visualRow < visibleRows;
                node.setVisible(onScreen);
                if (!onScreen) {
                    continue;
                }
                node.setBounds(cx, top - NODE_HEIGHT - visualRow * (NODE_HEIGHT + NODE_GAP),
                        columnWidth, NODE_HEIGHT);

                //  Always pressable: a tap on a locked node still selects it
                //  and shows why it is locked, which is the mobile equivalent
                //  of the source's hover.  Whether it can be BOUGHT is
                //  tree.canPurchase(id), which the renderer asks.
                node.setState(def.id.equals(selected)
                        ? UiRect.State.SELECTED : UiRect.State.NORMAL);
            }
        }

        back.setBounds(safe.touchCenterX() - 90f, safe.touchY + 26f, 180f, 52f);

        for (int i = 0; i < nodes.size; i++) {
            controls.add(nodes.get(i));
        }
        controls.add(back);
        //  Nodes are only 46 tall and packed 8 apart, so an unconditional 44x44
        //  minimum would overlap the neighbour below.  The columns are already
        //  wide enough; only the height needs a floor, and NODE_HEIGHT + NODE_GAP
        //  is 54, so growing to 44 is safe in the vertical direction too.
        TouchTargets.applyAll(controls);
    }

    @Override
    public Array<UiRect> controls() {
        return controls;
    }

    // ========================================================================
    //  Commands
    // ========================================================================

    @Override
    public String press(float uiX, float uiY) {
        UiRect hit = TouchTargets.firstHit(controls, uiX, uiY);
        if (hit == null) {
            return null;
        }
        if (hit == back) {
            nav.closeTalents();
            return hit.id;
        }
        for (int i = 0; i < nodes.size; i++) {
            if (hit == nodes.get(i)) {
                TalentDef def = order.get(i);
                if (def.id.equals(selected)) {
                    //  second tap on the same node: buy.  The tree decides
                    //  whether it can, and a refusal costs nothing.
                    run.talentTree().purchase(def.id);
                    if (def.id.equals("lightfingers")) {
                        //  the one talent the cursor caches -- see TALENTS.md
                        run.refreshGrabCooldown();
                    }
                } else {
                    selected = def.id;      // first tap: show what it does
                }
                return hit.id;
            }
        }
        return hit.id;
    }

    /** Scrolls the branch columns. Presentation only. */
    public void scrollBy(int rows) {
        scroll = Math.max(0, Math.min(maxScroll, scroll + rows));
    }

    public int scroll() {
        return scroll;
    }

    public int maxScroll() {
        return maxScroll;
    }

    // ========================================================================
    //  Read-only, for the renderer and the tests
    // ========================================================================

    /** The node box for a stable talent id, or null when scrolled out of view. */
    public UiRect node(String talentId) {
        for (int i = 0; i < order.size; i++) {
            if (order.get(i).id.equals(talentId)) {
                return nodes.get(i);
            }
        }
        return null;
    }

    public Array<UiRect> nodes() {
        return nodes;
    }

    /** The definitions, in the same order as {@link #nodes()}. */
    public Array<TalentDef> order() {
        return order;
    }

    public UiRect backButton() {
        return back;
    }

    public String selected() {
        return selected;
    }

    /** Test/UI hook: selects a node without buying it. */
    public void select(String talentId) {
        selected = talentId;
    }

    /**
     * The value to substitute into a node's localised description.
     *
     * <p>The tree's own number, so a tooltip cannot disagree with the game. For
     * an unbought node the source previews rank 1.
     */
    public double previewValue(String talentId) {
        TalentTree tree = run.talentTree();
        int rank = Math.max(1, tree.rank(talentId));
        TalentDef def = tree.table().require(talentId);
        return def.valueAt(rank);
    }
}
