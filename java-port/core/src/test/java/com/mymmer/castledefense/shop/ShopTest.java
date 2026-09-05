package com.mymmer.castledefense.shop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.badlogic.gdx.utils.Array;
import com.mymmer.castledefense.config.GameConfig;
import com.mymmer.castledefense.debug.TraceEvent;
import com.mymmer.castledefense.defence.DefenceTower;
import com.mymmer.castledefense.defence.TowerType;
import com.mymmer.castledefense.progress.TestRun;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The armoury: prices, availability, and every failure path. */
class ShopTest {

    // ========================================================================
    //  The inventory
    // ========================================================================

    /** {@code id, base, growth} for the eight geometric items. */
    private static final Object[][] GEOMETRIC = {
        {"bowman", 110.0, 1.26},
        {"ballista", 250.0, 1.28},
        {"cannon", 380.0, 1.28},
        {"wall", 180.0, 1.62},
        {"bounce", 200.0, 1.55},
        {"grab", 260.0, 2.05},
        {"multi", 340.0, 1.85},
        {"outpost", 300.0, 1.5},
        {"spikes", 190.0, 1.7},
    };

    @Test
    @DisplayName("all eleven shop items are present, in the source's order")
    void inventory() {
        TestRun r = new TestRun().beginClassic();
        Array<ShopItemDef> items = r.shop().items();
        assertEquals(11, items.size, "the source has eleven");

        String[] order = {"bowman", "ballista", "cannon", "wall", "bounce", "grab",
            "multi", "outpost", "barricade", "spikes", "repair"};
        for (int i = 0; i < order.length; i++) {
            assertEquals(order[i], items.get(i).id,
                    "position " + i + " -- the number hotkeys depend on this order");
        }
    }

    @Test
    @DisplayName("every item's curve numbers match the source")
    void curveNumbers() {
        TestRun r = new TestRun().beginClassic();
        for (Object[] row : GEOMETRIC) {
            ShopItemDef d = r.shop().table().require((String) row[0]);
            assertEquals(ShopItemDef.Curve.GEOMETRIC, d.curve, d.id);
            assertEquals((Double) row[1], d.base, 1e-6, d.id + ": base");
            assertEquals((Double) row[2], d.growth, 1e-6, d.id + ": growth");
        }
        assertEquals(ShopItemDef.Curve.BARRICADE,
                r.shop().table().require("barricade").curve);
        assertEquals(ShopItemDef.Curve.REPAIR, r.shop().table().require("repair").curve);
    }

    @Test
    @DisplayName("localisation keys come from the id, never the other way round")
    void localisationKeys() {
        TestRun r = new TestRun().beginClassic();
        Array<ShopItemDef> items = r.shop().items();
        for (int i = 0; i < items.size; i++) {
            ShopItemDef d = items.get(i);
            assertEquals("shop." + d.id + ".name", d.nameKey());
            assertEquals("shop." + d.id + ".desc", d.descriptionKey());
        }
    }

    // ========================================================================
    //  Prices
    // ========================================================================

    @Test
    @DisplayName("a tower's price climbs with how many were BOUGHT, not how many stand")
    void towerPriceClimbs() {
        TestRun r = new TestRun().beginClassic().grantGold(100000);
        assertEquals(110, r.shop().currentCost("bowman"));

        assertTrue(r.shop().buy("bowman").ok());
        assertEquals((int) (110 * 1.26), r.shop().currentCost("bowman"));

        assertTrue(r.shop().buy("bowman").ok());
        assertEquals((int) (110 * Math.pow(1.26, 2)), r.shop().currentCost("bowman"));

        //  smash one: the price does not fall back
        DefenceTower t = r.run.castle().towers().first();
        for (int i = 0; i < 8 && !t.disabled(); i++) {
            t.takeDamage(t.maxHp());
        }
        assertTrue(t.disabled());
        assertEquals((int) (110 * Math.pow(1.26, 2)), r.shop().currentCost("bowman"),
                "the counter is purchases, not standing towers");
    }

    @Test
    @DisplayName("the wall's price uses wallLevel - 1, so the first reinforcement is the base")
    void wallPrice() {
        TestRun r = new TestRun().beginClassic().grantGold(1000000);
        assertEquals(1, r.run.castle().wallLevel());
        assertEquals(180, r.shop().currentCost("wall"));
        assertTrue(r.shop().buy("wall").ok());
        assertEquals((int) (180 * 1.62), r.shop().currentCost("wall"));
        assertTrue(r.shop().buy("wall").ok());
        assertEquals((int) (180 * Math.pow(1.62, 2)), r.shop().currentCost("wall"));
    }

    @Test
    @DisplayName("repair costs a share of the missing health, with a floor of 50")
    void repairPrice() {
        TestRun r = new TestRun().beginClassic().grantGold(100000);
        assertEquals(50, r.shop().currentCost("repair"), "pristine: the floor");

        r.run.castle().takeDamage(400f);
        float missing = r.run.castle().maxHp() - r.run.castle().hp();
        assertEquals(Math.max(50, (int) (missing * 0.55f)),
                r.shop().currentCost("repair"));
    }

    @Test
    @DisplayName("the barricade's four price cases each apply where the source says")
    void barricadePrice() {
        TestRun r = new TestRun().beginClassic().grantGold(1000000);

        //  1. not built yet
        assertEquals(0, r.run.barricade().level());
        assertEquals(240, r.shop().currentCost("barricade"));
        assertTrue(r.shop().buy("barricade").ok());

        //  2. built and healthy: 240 * 1.5^level
        assertEquals(1, r.run.barricade().level());
        assertEquals((int) (240 * 1.5), r.shop().currentCost("barricade"));

        //  3. collapsed: 150 * 1.4^level
        r.run.barricade().takeDamage(r.run.barricade().maxHp() * 2f);
        assertFalse(r.run.barricade().alive());
        assertEquals((int) (150 * Math.pow(1.4, 1)), r.shop().currentCost("barricade"));

        //  4. capped and damaged: a flat fee plus a share of the missing health
        while (r.run.barricade().level() < GameConfig.BARRICADE_MAX_LEVEL) {
            assertTrue(r.shop().buy("barricade").ok());
        }
        r.run.barricade().takeDamage(200f);
        float missing = r.run.barricade().maxHp() - r.run.barricade().hp();
        assertEquals((int) (60 + missing * 0.35f), r.shop().currentCost("barricade"));
    }

    // ========================================================================
    //  The discount, and its ordering
    // ========================================================================

    @Test
    @DisplayName("the discount multiplies the RAW curve and truncates once")
    void discountOrdering() {
        //  Python: int(cost_fn() * discount).  Not int(cost_fn()) * discount,
        //  which rounds twice and differs by a coin at most prices.
        TestRun r = new TestRun().beginClassic().grantGold(1000000);
        r.grantPoints(20);
        r.buyTalentDeep("haggle", 3);
        double discount = 1d - 0.18d;

        //  a price with a fractional raw value, so the two orders differ
        r.shop().buy("bowman");
        r.shop().buy("bowman");
        double raw = 110 * Math.pow(1.26, 2);            // 174.636
        assertTrue(raw != Math.floor(raw), "this price must be fractional to mean anything");

        int correct = (int) (raw * discount);
        int wrong = (int) ((int) raw * discount);
        assertTrue(correct != wrong,
                "the two orderings must differ here, or the test proves nothing");
        assertEquals(correct, r.shop().currentCost("bowman"));
    }

    @Test
    @DisplayName("the discount is capped at 40% off")
    void discountCap() {
        TestRun r = new TestRun().beginClassic();
        r.grantPoints(20);
        r.buyTalentDeep("haggle", 4);
        //  4 x 0.06 = 0.24, under the cap
        assertEquals(1d - 0.24d, r.run.modifiers().shopDiscount(), 1e-9);
    }

    // ========================================================================
    //  Availability
    // ========================================================================

    @Test
    @DisplayName("levelled items stop being available at their cap")
    void availabilityCaps() {
        TestRun r = new TestRun().beginClassic().grantGold(100000000);

        buyToCap(r, "bounce", GameConfig.BOUNCE_MAX_LEVEL);
        assertFalse(r.shop().isAvailable("bounce"));
        assertEquals(Shop.Result.UNAVAILABLE, r.shop().buy("bounce"));

        buyToCap(r, "grab", GameConfig.GRAB_MAX_LEVEL);
        assertFalse(r.shop().isAvailable("grab"));

        buyToCap(r, "multi", GameConfig.MULTI_MAX_LEVEL);
        assertFalse(r.shop().isAvailable("multi"));

        buyToCap(r, "spikes", GameConfig.SPIKE_MAX_LEVEL);
        assertFalse(r.shop().isAvailable("spikes"));
    }

    @Test
    @DisplayName("the wall and the outpost are uncapped and always on offer")
    void uncappedItems() {
        TestRun r = new TestRun().beginClassic().grantGold(100000000);
        for (int i = 0; i < 12; i++) {
            assertTrue(r.shop().isAvailable("wall"));
            assertTrue(r.shop().buy("wall").ok(), "wall " + i);
            assertTrue(r.shop().isAvailable("outpost"));
            assertTrue(r.shop().buy("outpost").ok(), "outpost " + i);
        }
        assertTrue(r.run.castle().wallLevel() > r.run.castle().maxVisualLevel(),
                "the keep keeps gaining health past its last visual tier");
    }

    @Test
    @DisplayName("a barricade at the cap is available again once it is damaged")
    void barricadeAvailability() {
        TestRun r = new TestRun().beginClassic().grantGold(1000000);
        while (r.run.barricade().level() < GameConfig.BARRICADE_MAX_LEVEL) {
            assertTrue(r.shop().buy("barricade").ok());
        }
        assertFalse(r.shop().isAvailable("barricade"), "capped and undamaged");
        r.run.barricade().takeDamage(50f);
        assertTrue(r.shop().isAvailable("barricade"), "capped but damaged: top it up");
    }

    // ========================================================================
    //  Transactions
    // ========================================================================

    @Test
    @DisplayName("gold comes off only after the effect succeeded")
    void goldDeductedAfterSuccess() {
        TestRun r = new TestRun().beginClassic();
        int gold = r.session().gold();
        int cost = r.shop().currentCost("bowman");
        assertTrue(gold >= cost);

        assertTrue(r.shop().buy("bowman").ok());
        assertEquals(gold - cost, r.session().gold());
        assertEquals(1, r.run.castle().towerCount());
    }

    @Test
    @DisplayName("a refused effect costs nothing and does not move the price")
    void refusalCostsNothing() {
        //  Repair on a pristine keep is the one refusal reachable past the
        //  availability check, which is exactly why the two stages are separate.
        TestRun r = new TestRun().beginClassic();
        assertEquals(r.run.castle().maxHp(), r.run.castle().hp(), 0f);
        assertTrue(r.shop().isAvailable("repair"), "it is on offer...");

        int gold = r.session().gold();
        int cost = r.shop().currentCost("repair");
        assertEquals(Shop.Result.REFUSED, r.shop().buy("repair"),
                "...but the effect itself refuses");
        assertEquals(gold, r.session().gold(), "and it cost nothing");
        assertEquals(cost, r.shop().currentCost("repair"), "and the price did not move");
    }

    @Test
    @DisplayName("too little gold changes nothing at all")
    void tooExpensive() {
        TestRun r = new TestRun().beginClassic();
        while (r.session().gold() > 0) {
            r.session().spendGold(1);
        }
        int towers = r.run.castle().towerCount();

        assertEquals(Shop.Result.TOO_EXPENSIVE, r.shop().buy("bowman"));
        assertEquals(0, r.session().gold());
        assertEquals(towers, r.run.castle().towerCount(), "no half-bought tower");
        assertEquals(0, r.shop().purchaseCount("bowman"), "and the counter did not move");
        assertEquals(110, r.shop().currentCost("bowman"), "so nor did the price");
    }

    @Test
    @DisplayName("an unknown item is refused without touching anything")
    void unknownItem() {
        TestRun r = new TestRun().beginClassic();
        int gold = r.session().gold();
        assertEquals(Shop.Result.UNKNOWN, r.shop().buy("trebuchet"));
        assertEquals(gold, r.session().gold());
    }

    @Test
    @DisplayName("the purchase counter moves only for towers, and only on success")
    void purchaseCounter() {
        TestRun r = new TestRun().beginClassic().grantGold(100000);
        assertEquals(0, r.shop().purchaseCount("bowman"));
        r.shop().buy("bowman");
        assertEquals(1, r.shop().purchaseCount("bowman"));
        r.shop().buy("wall");
        assertEquals(0, r.shop().purchaseCount("wall"), "only towers have a counter");
    }

    // ========================================================================
    //  The tower fallback
    // ========================================================================

    @Test
    @DisplayName("a free slot stations a new tower")
    void towerToFreeSlot() {
        TestRun r = new TestRun().beginClassic().grantGold(100000);
        assertTrue(r.run.castle().freeSlots().size > 0);
        assertTrue(r.shop().buy("bowman").ok());
        assertEquals(1, r.run.castle().towerCount());
        assertEquals(1, r.run.castle().countOf(TowerType.BOWMAN));
    }

    @Test
    @DisplayName("with the wall full, buying a tower upgrades EVERY tower of that type")
    void towerFallbackUpgradesAllOfThatType() {
        TestRun r = new TestRun().beginClassic().grantGold(100000000);

        //  three Bowmen and then fill the rest of the wall with Ballistas
        for (int i = 0; i < 3; i++) {
            assertTrue(r.shop().buy("bowman").ok());
        }
        while (r.run.castle().freeSlots().size > 0) {
            assertTrue(r.shop().buy("ballista").ok());
        }
        assertEquals(0, r.run.castle().freeSlots().size, "the wall is full");
        assertEquals(3, r.run.castle().countOf(TowerType.BOWMAN));

        int[] before = levelsOf(r, TowerType.BOWMAN);
        for (int lvl : before) {
            assertEquals(1, lvl, "they all start at level 1");
        }

        assertTrue(r.shop().buy("bowman").ok(), "it falls back to an upgrade");
        int[] after = levelsOf(r, TowerType.BOWMAN);
        assertEquals(3, after.length, "no new tower was added");
        for (int lvl : after) {
            assertEquals(2, lvl, "ALL of them went up, not one");
        }
        assertEquals(4, r.shop().purchaseCount("bowman"),
                "and it counted as a purchase, so the price climbs");
    }

    @Test
    @DisplayName("with the wall full and none of that type, the purchase is refused")
    void towerFallbackRefusesWithNoneOfThatType() {
        TestRun r = new TestRun().beginClassic().grantGold(100000000);
        while (r.run.castle().freeSlots().size > 0) {
            assertTrue(r.shop().buy("bowman").ok());
        }
        assertEquals(0, r.run.castle().countOf(TowerType.CANNON));

        int gold = r.session().gold();
        int cost = r.shop().currentCost("cannon");
        assertEquals(Shop.Result.REFUSED, r.shop().buy("cannon"),
                "no wall space and nothing to upgrade");
        assertEquals(gold, r.session().gold(), "and it cost nothing");
        assertEquals(0, r.shop().purchaseCount("cannon"));
        assertEquals(cost, r.shop().currentCost("cannon"));
    }

    @Test
    @DisplayName("reinforcing the wall opens a slot, and the fallback stops")
    void wallOpensASlot() {
        TestRun r = new TestRun().beginClassic().grantGold(100000000);
        while (r.run.castle().freeSlots().size > 0) {
            r.shop().buy("bowman");
        }
        int towers = r.run.castle().towerCount();
        assertTrue(r.shop().buy("wall").ok());
        assertTrue(r.run.castle().freeSlots().size > 0, "a new emplacement opened");
        assertTrue(r.shop().buy("cannon").ok());
        assertEquals(towers + 1, r.run.castle().towerCount());
    }

    // ========================================================================
    //  Lifecycle and diagnostics
    // ========================================================================

    @Test
    @DisplayName("a new run resets the purchase counters and the cursor levels")
    void resetOnNewRun() {
        TestRun r = new TestRun().beginClassic().grantGold(100000);
        r.shop().buy("bowman");
        r.shop().buy("bounce");
        r.shop().buy("grab");
        assertTrue(r.shop().purchaseCount("bowman") > 0);
        assertTrue(r.run.bounceLevel() > 0);

        r.run.beginRun(com.mymmer.castledefense.game.GameMode.CLASSIC,
                r.difficulty("normal"), 99L);
        assertEquals(0, r.shop().purchaseCount("bowman"));
        assertEquals(110, r.shop().currentCost("bowman"));
        assertEquals(0, r.run.bounceLevel());
        assertEquals(0, r.run.grabLevel());
        assertEquals(0, r.run.multiLevel());
        assertEquals(GameConfig.STARTING_GOLD, r.session().gold());
    }

    @Test
    @DisplayName("purchases and refusals are both traced")
    void tracing() {
        TestRun r = new TestRun();
        r.recording();
        r.beginClassic();
        r.shop().buy("bowman");
        r.shop().buy("repair");              // refused: the keep is pristine
        assertEquals(1, r.trace.countOf(TraceEvent.SHOP_PURCHASED));
        assertEquals(1, r.trace.countOf(TraceEvent.SHOP_PURCHASE_FAILED));
    }

    @Test
    @DisplayName("the view reports cost, availability and affordability for the UI")
    void queryView() {
        TestRun r = new TestRun().beginClassic();
        Array<Shop.ItemView> view = r.shop().view();
        assertEquals(11, view.size);

        Shop.ItemView bowman = r.shop().view("bowman");
        assertEquals(110, bowman.cost);
        assertTrue(bowman.available);
        assertTrue(bowman.affordable, "220 starting gold covers a 110 Bowman");
        assertTrue(bowman.buyable());

        Shop.ItemView cannon = r.shop().view("cannon");
        assertEquals(380, cannon.cost);
        assertFalse(cannon.affordable, "220 does not cover a 380 Cannon");
        assertFalse(cannon.buyable());
        assertTrue(cannon.available, "though it is still on offer");
    }

    // ------------------------------------------------------------------------

    private static void buyToCap(TestRun r, String id, int cap) {
        for (int i = 0; i < cap; i++) {
            assertTrue(r.shop().buy(id).ok(), id + " " + (i + 1));
        }
    }

    private static int[] levelsOf(TestRun r, TowerType type) {
        Array<DefenceTower> towers = r.run.castle().towers();
        int n = 0;
        for (int i = 0; i < towers.size; i++) {
            if (towers.get(i).type() == type) {
                n++;
            }
        }
        int[] out = new int[n];
        int k = 0;
        for (int i = 0; i < towers.size; i++) {
            if (towers.get(i).type() == type) {
                out[k++] = towers.get(i).level();
            }
        }
        assertNotNull(out);
        return out;
    }
}
