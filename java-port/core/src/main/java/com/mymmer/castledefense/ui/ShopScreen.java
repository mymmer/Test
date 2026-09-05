package com.mymmer.castledefense.ui;

import com.badlogic.gdx.utils.Array;
import com.mymmer.castledefense.game.GameState;
import com.mymmer.castledefense.game.RunWorld;
import com.mymmer.castledefense.shop.Shop;
import com.mymmer.castledefense.shop.ShopItemDef;

/**
 * The armoury: eleven cards, a talents door and a way back to the fight.
 *
 * <h2>It asks; it never calculates</h2>
 *
 * <p>Every number on a card comes from {@link Shop}:
 *
 * <pre>
 *   price        shop.currentCost(id)
 *   affordable   shop.view(id).affordable
 *   available    shop.isAvailable(id)
 *   level        shop.view(id).level
 *   buying       shop.buy(id)
 * </pre>
 *
 * <p>There is no cost formula here, no gold subtraction, no availability rule.
 * Phase 9's discount ordering is the precedent: the one place that knows what a
 * thing costs is the shop, and a second implementation in a draw method is how
 * the two drift apart.
 *
 * <h2>One screen, two modes</h2>
 *
 * <p>Classic shows it between waves and the action button sends in the next one.
 * Endless shows it mid-fight and the same button resumes. The <b>freeze is not
 * this screen's doing</b> — the state is {@code SHOP}, the state machine does not
 * advance the world in it, and that is the whole mechanism. Nothing here holds a
 * timer or suppresses a step.
 */
public final class ShopScreen implements UiScreen {

    private final RunWorld run;
    private final Navigation nav;

    private final Array<UiRect> controls = new Array<>(false, 16);
    private final Array<UiRect> cards = new Array<>(false, 11);
    private final UiRect startButton = new UiRect("shop.start");
    private final UiRect talentsButton = new UiRect("shop.talents");

    /** Which card the player last tapped, for a details panel. Presentation only. */
    private String selectedItem;

    public ShopScreen(RunWorld run, Navigation nav) {
        if (run == null || nav == null) {
            throw new IllegalArgumentException("run and nav must not be null");
        }
        this.run = run;
        this.nav = nav;
        Array<ShopItemDef> items = run.shop().items();
        for (int i = 0; i < items.size; i++) {
            cards.add(new UiRect("shop.item." + items.get(i).id));
        }
    }

    @Override
    public GameState state() {
        return GameState.SHOP;
    }

    // ========================================================================
    //  Layout
    // ========================================================================

    @Override
    public void layout(SafeArea safe, TextLayout text) {
        controls.clear();

        float panelWidth = Math.min(1120f, safe.width - 32f);
        float panelX = safe.centerX() - panelWidth / 2f;
        //  Below the title AND its gold subtitle: 96 put the first row of cards
        //  under the subtitle's descenders on a short screen.
        float top = safe.top() - 124f;
        float bottom = safe.y + 92f;

        //  A grid that reflows: as many columns as fit at a comfortable card
        //  width, so a 20:9 screen gets more per row rather than wider cards.
        int columns = Math.max(2, Math.min(4, (int) (panelWidth / 260f)));
        float gap = 12f;
        float cardWidth = (panelWidth - gap * (columns - 1)) / columns;
        int rows = (cards.size + columns - 1) / columns;
        float available = top - bottom;
        float cardHeight = Math.min(112f, (available - gap * (rows - 1)) / rows);

        Array<ShopItemDef> items = run.shop().items();
        for (int i = 0; i < cards.size; i++) {
            int col = i % columns;
            int row = i / columns;
            UiRect card = cards.get(i);
            card.setBounds(panelX + col * (cardWidth + gap),
                    top - cardHeight - row * (cardHeight + gap),
                    cardWidth, cardHeight);
            //  A card is ALWAYS pressable, even when it cannot be bought.
            //  Python calls try_buy for any card the click lands on and answers
            //  "maxed out" or "Not enough gold!"; a card that swallowed the
            //  press silently would lose that.  How it LOOKS is a render
            //  question, answered by shop.view(id) -- see cardView below.
            card.setState(items.get(i).id.equals(selectedItem)
                    ? UiRect.State.SELECTED : UiRect.State.NORMAL);
        }

        float actionWidth = Math.min(300f, safe.width * 0.32f);
        startButton.setBounds(safe.centerX() + 8f, safe.y + 20f, actionWidth, 56f);
        talentsButton.setBounds(safe.centerX() - actionWidth - 8f, safe.y + 20f,
                actionWidth, 56f);
        //  The talents door is always open from the armoury; the source's `T`
        //  works here too.
        talentsButton.setState(nav.canOpenTalents()
                ? UiRect.State.NORMAL : UiRect.State.DISABLED);

        //  Cards first: they are the bulk of the screen and the action buttons
        //  are well clear of them, so priority order is only a tie-break.
        for (int i = 0; i < cards.size; i++) {
            controls.add(cards.get(i));
        }
        controls.add(talentsButton);
        controls.add(startButton);
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
            return null;                    // modal: swallowed by UiRoot
        }
        if (hit == startButton) {
            nav.startPlaying();
            return hit.id;
        }
        if (hit == talentsButton) {
            nav.openTalents();
            return hit.id;
        }
        Array<ShopItemDef> items = run.shop().items();
        for (int i = 0; i < cards.size; i++) {
            if (hit == cards.get(i)) {
                String id = items.get(i).id;
                selectedItem = id;
                //  The command, and only the command.  Whether it succeeds,
                //  what it costs and what it changes are entirely the shop's.
                run.shop().buy(id);
                return hit.id;
            }
        }
        return hit.id;
    }

    /** Buys by index, for the source's number hotkeys (1..9 then 0). */
    public boolean buyByIndex(int index) {
        Array<ShopItemDef> items = run.shop().items();
        if (index < 0 || index >= items.size) {
            return false;
        }
        selectedItem = items.get(index).id;
        return run.shop().buy(items.get(index).id).ok();
    }

    // ========================================================================
    //  Read-only, for the renderer and the tests
    // ========================================================================

    /**
     * How a card should look right now.
     *
     * <p>The renderer asks this rather than reading a flag off the rectangle:
     * availability and affordability are the shop's to know, and a copy on the
     * control would be a copy that can go stale.
     */
    public Shop.ItemView cardView(String itemId) {
        return run.shop().view(itemId);
    }

    /** The card for one item id, or null. */
    public UiRect card(String itemId) {
        Array<ShopItemDef> items = run.shop().items();
        for (int i = 0; i < items.size; i++) {
            if (items.get(i).id.equals(itemId)) {
                return cards.get(i);
            }
        }
        return null;
    }

    public Array<UiRect> cards() {
        return cards;
    }

    public UiRect startButton() {
        return startButton;
    }

    public UiRect talentsButton() {
        return talentsButton;
    }

    public String selectedItem() {
        return selectedItem;
    }

    /** The action button's label key: Classic sends a wave, Endless resumes. */
    public String actionLabelKey() {
        return run.session().isEndless() ? "shop.resume" : "shop.start";
    }
}
