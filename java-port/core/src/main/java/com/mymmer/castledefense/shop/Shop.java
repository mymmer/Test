package com.mymmer.castledefense.shop;

import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.ObjectIntMap;
import com.mymmer.castledefense.config.GameConfig;
import com.mymmer.castledefense.debug.TraceEvent;
import com.mymmer.castledefense.defence.Barricade;
import com.mymmer.castledefense.defence.Castle;
import com.mymmer.castledefense.defence.DefenceTower;
import com.mymmer.castledefense.defence.TowerType;

/**
 * The armoury: prices, availability and purchases.
 *
 * <p>Transaction logic only. It knows nothing about cards, fonts, icons, a mouse
 * or a touch, and it cannot advance the simulation — in Endless it runs while the
 * world is frozen, and a shop that could step would be a shop that could break
 * that.
 *
 * <h2>The order of a purchase</h2>
 *
 * <p>Transcribed from {@code main.py:1815 try_buy}, and the order is the
 * contract:
 *
 * <pre>
 *   1  availability     unavailable -> refuse, nothing happens
 *   2  price            base curve x discount, truncated (see below)
 *   3  purse            not enough gold -> refuse, nothing happens
 *   4  apply            the effect runs; it MAY still refuse
 *   5  deduct           gold comes off only after the effect succeeded
 *   6  count            and the purchase counter moves only for the towers
 * </pre>
 *
 * <p><b>Gold is deducted last.</b> An effect that refuses at step 4 — a Repair on
 * a pristine keep, a Bounce already at its cap — costs the player nothing and
 * does not move the price for next time. Every failure path is tested.
 *
 * <h2>The discount, and why the order matters</h2>
 *
 * <p>Python: {@code int(cost_fn() * discount)}. The <b>raw</b> curve value —
 * fractional, straight out of {@code 110 * 1.26**n} — is multiplied by the
 * discount and truncated <em>once, at the end</em>. Not {@code int(curve) *
 * discount}, which rounds twice and differs by a coin at most prices. The two
 * are fixtured against the source.
 *
 * <p>One faithful deviation: Python assigns {@code item.discount} inside its
 * <em>draw</em> loop, so the price technically depends on the shop having been
 * rendered. Since the screen is always drawn before the player can click, the
 * observable behaviour is "the discount always applies", and that is what this
 * computes — live, at the point of sale, with no rendering in the path.
 */
public final class Shop {

    private final ShopTable table;
    private final ShopContext ctx;

    /**
     * How many of each tower have been <b>bought</b>.
     *
     * <p>Python's {@code self.purchases}, and it is deliberately not the number
     * standing: a tower that was bought, smashed and never rebuilt still counts
     * toward the price of the next one, and only these three keys exist.
     */
    private final ObjectIntMap<String> purchases = new ObjectIntMap<>();

    /** The outcome of a purchase attempt, for the caller and the trace. */
    public enum Result {
        BOUGHT,
        /** The item is not available right now — maxed out, or nothing to do. */
        UNAVAILABLE,
        /** Not enough gold. */
        TOO_EXPENSIVE,
        /** Available and affordable, but the effect itself refused. */
        REFUSED,
        /** No such item id. */
        UNKNOWN;

        public boolean ok() {
            return this == BOUGHT;
        }
    }

    public Shop(ShopTable table, ShopContext ctx) {
        if (table == null || ctx == null) {
            throw new IllegalArgumentException("table and ctx must not be null");
        }
        this.table = table;
        this.ctx = ctx;
        reset();
    }

    /** A new run: the purchase counters go back to zero. */
    public void reset() {
        purchases.clear();
        for (ShopItemDef d : table.all()) {
            if (d.effect == ShopItemDef.Effect.TOWER) {
                purchases.put(d.id, 0);
            }
        }
    }

    public ShopTable table() {
        return table;
    }

    /** Every item, in the source's order. Hotkeys depend on it. */
    public Array<ShopItemDef> items() {
        return table.all();
    }

    /** How many of this tower have been bought this run. */
    public int purchaseCount(String id) {
        return purchases.get(id, 0);
    }

    // ========================================================================
    //  Prices
    // ========================================================================

    /** The undiscounted curve value, fractional. Python's {@code cost_fn()}. */
    public double rawCost(String id) {
        ShopItemDef d = table.get(id);
        if (d == null) {
            return 0d;
        }
        switch (d.curve) {
            case GEOMETRIC:
                return d.base * Math.pow(d.growth, counterFor(d));
            case BARRICADE:
                return barricadeCost(d);
            case REPAIR:
                return repairCost(d);
            default:
                return 0d;
        }
    }

    /**
     * What the player actually pays — {@code int(rawCost * discount)}.
     *
     * <p>One truncation, at the end, exactly as the source does it.
     */
    public int currentCost(String id) {
        return (int) (rawCost(id) * ctx.modifiers().shopDiscount());
    }

    private int counterFor(ShopItemDef d) {
        switch (d.counter) {
            case PURCHASES:
                return purchases.get(d.id, 0);
            case WALL_LEVEL_MINUS_ONE:
                return ctx.castle().wallLevel() - 1;
            case BOUNCE_LEVEL:
                return ctx.bounceLevel();
            case GRAB_LEVEL:
                return ctx.grabLevel();
            case MULTI_LEVEL:
                return ctx.multiLevel();
            case OUTPOST_LEVEL:
                return ctx.outpost().level();
            case SPIKE_LEVEL:
                return ctx.spikes().level();
            default:
                return 0;
        }
    }

    /**
     * The barricade's four cases — {@code main.py:1128 barricade_cost}.
     *
     * <p>Note the third: once it is at the level cap but damaged, the price is a
     * flat fee plus a share of the missing health, so topping up a battered
     * wall is cheap. The order of the checks is the source's.
     */
    private double barricadeCost(ShopItemDef d) {
        Barricade b = ctx.barricade();
        if (b.level() == 0) {
            return d.firstCost;
        }
        if (!b.alive()) {
            //  Python truncates this case itself, before the discount
            return (int) (d.rebuildBase * Math.pow(d.rebuildGrowth, b.level()));
        }
        if (b.hp() < b.maxHp() && b.level() >= GameConfig.BARRICADE_MAX_LEVEL) {
            double missing = b.maxHp() - b.hp();
            return (int) (d.topUpFlat + missing * d.topUpPerMissingHp);
        }
        return (int) (d.base * Math.pow(d.growth, b.level()));
    }

    /** {@code max(50, int(missing * 0.55))} — {@code main.py:1150}. */
    private double repairCost(ShopItemDef d) {
        Castle c = ctx.castle();
        double missing = c.maxHp() - c.hp();
        return Math.max(d.base, (int) (missing * d.perMissingHp));
    }

    // ========================================================================
    //  Availability
    // ========================================================================

    /**
     * Is the item on offer at all? Python's {@code avail_fn}.
     *
     * <p>Availability is about the item, not the purse: an item you cannot
     * afford is still available. That split is the source's and it is what lets
     * the shop grey out a maxed item differently from an expensive one.
     */
    public boolean isAvailable(String id) {
        ShopItemDef d = table.get(id);
        if (d == null) {
            return false;
        }
        switch (d.effect) {
            case BOUNCE:
                return ctx.bounceLevel() < GameConfig.BOUNCE_MAX_LEVEL;
            case GRAB:
                return ctx.grabLevel() < GameConfig.GRAB_MAX_LEVEL;
            case MULTI:
                return ctx.multiLevel() < GameConfig.MULTI_MAX_LEVEL;
            case SPIKES:
                return ctx.spikes().level() < GameConfig.SPIKE_MAX_LEVEL;
            case BARRICADE: {
                Barricade b = ctx.barricade();
                return b.level() < GameConfig.BARRICADE_MAX_LEVEL || b.hp() < b.maxHp();
            }
            //  Towers, the wall, the outpost and repair are ALWAYS on offer in
            //  the source -- the wall and the outpost because they are uncapped,
            //  a tower because it may fall back to an upgrade, and repair
            //  because its own effect refuses when the keep is pristine.
            default:
                return true;
        }
    }

    /** Available, affordable, and the effect is likely to take. */
    public boolean canBuy(String id) {
        return isAvailable(id) && ctx.session().gold() >= currentCost(id);
    }

    // ========================================================================
    //  Buying
    // ========================================================================

    /**
     * Attempts a purchase.
     *
     * <p>Nothing is mutated until {@link #apply} returns true, and the gold comes
     * off after that. A refusal at any stage leaves the run exactly as it was:
     * same purse, same levels, same price next time.
     */
    public Result buy(String id) {
        ShopItemDef d = table.get(id);
        if (d == null) {
            return fail(id, Result.UNKNOWN, 0);
        }
        if (!isAvailable(id)) {
            return fail(id, Result.UNAVAILABLE, 0);
        }
        int cost = currentCost(id);
        if (ctx.session().gold() < cost) {
            return fail(id, Result.TOO_EXPENSIVE, cost);
        }
        if (!apply(d)) {
            return fail(id, Result.REFUSED, cost);
        }
        //  Only now.  Python deducts after buy_fn reports success, and the
        //  purchase counter -- which drives the next price -- moves with it.
        ctx.session().spendGold(cost);
        if (d.effect == ShopItemDef.Effect.TOWER) {
            purchases.put(d.id, purchases.get(d.id, 0) + 1);
        }
        if (ctx.trace().isEnabled()) {
            ctx.trace().event(TraceEvent.SHOP_PURCHASED, ctx.step(), 0L,
                    cost, ctx.session().gold(), id);
        }
        return Result.BOUGHT;
    }

    private Result fail(String id, Result why, int cost) {
        if (ctx.trace().isEnabled()) {
            ctx.trace().event(TraceEvent.SHOP_PURCHASE_FAILED, ctx.step(), 0L,
                    cost, ctx.session().gold(), id + ":" + why);
        }
        return why;
    }

    /**
     * Runs the effect. Returns false when the effect itself refuses.
     *
     * <p>The refusals here are the source's {@code return False, ...} branches.
     * Most are unreachable behind {@link #isAvailable}; Repair's is not, and is
     * the reason this stage exists as a separate one at all.
     */
    private boolean apply(ShopItemDef d) {
        switch (d.effect) {
            case TOWER:
                return buyTower(d.tower);
            case WALL:
                //  never refuses -- see Castle.upgradeWall
                ctx.castle().upgradeWall();
                return true;
            case BOUNCE:
                if (ctx.bounceLevel() >= GameConfig.BOUNCE_MAX_LEVEL) {
                    return false;
                }
                ctx.setBounceLevel(ctx.bounceLevel() + 1);
                return true;
            case GRAB:
                if (ctx.grabLevel() >= GameConfig.GRAB_MAX_LEVEL) {
                    return false;
                }
                ctx.setGrabLevel(ctx.grabLevel() + 1);
                return true;
            case MULTI:
                if (ctx.multiLevel() >= GameConfig.MULTI_MAX_LEVEL) {
                    return false;
                }
                ctx.setMultiLevel(ctx.multiLevel() + 1);
                return true;
            case OUTPOST:
                //  never refuses -- past the crew cap it buys raw firepower
                ctx.outpost().upgrade();
                return true;
            case BARRICADE:
                return ctx.barricade().buy();
            case SPIKES:
                return ctx.spikes().upgrade();
            case REPAIR: {
                Castle c = ctx.castle();
                if (c.hp() >= c.maxHp()) {
                    return false;               // the walls are already pristine
                }
                c.repair(d.healFraction);
                return true;
            }
            default:
                return false;
        }
    }

    /**
     * The tower fallback — {@code main.py:1057 buy_tower}.
     *
     * <p>Three outcomes, and the middle one is the interesting one:
     *
     * <ul>
     *   <li><b>A slot is free</b> — a new tower is stationed.</li>
     *   <li><b>No slot, but this type already stands</b> — <b>every</b> tower of
     *       that type is upgraded. Not the weakest, not the nearest: all of
     *       them, in list order. That is what the source does, and it is why
     *       buying a fourth Bowman with a full wall makes all three existing
     *       Bowmen better rather than picking one.</li>
     *   <li><b>No slot and none of this type</b> — refused, and it costs
     *       nothing. "No wall space — reinforce the walls first."</li>
     * </ul>
     *
     * <p>No "better" selection strategy is invented here.
     */
    private boolean buyTower(TowerType type) {
        Castle castle = ctx.castle();
        DefenceTower stationed = castle.addTower(type);
        if (stationed != null) {
            return true;
        }
        Array<DefenceTower> towers = castle.towers();
        boolean any = false;
        for (int i = 0; i < towers.size; i++) {
            if (towers.get(i).type() == type) {
                any = true;
            }
        }
        if (!any) {
            return false;
        }
        for (int i = 0; i < towers.size; i++) {
            DefenceTower t = towers.get(i);
            if (t.type() == type) {
                t.upgrade();
            }
        }
        return true;
    }

    // ========================================================================
    //  Read-only query surface for Phase 10
    // ========================================================================

    /** One item's current state. Allocated on demand; the shop is not a hot path. */
    public static final class ItemView {
        public final ShopItemDef def;
        public final int cost;
        public final boolean available;
        public final boolean affordable;
        /** The item's own level, or the purchase count for a tower. */
        public final int level;
        /**
         * True when the next purchase of this tower upgrades the ones already
         * built instead of adding another.
         *
         * <p>{@code main.py:1047 tower_status} appends "(upgrades)" when the
         * wall has no free emplacement left, which is the one thing a player
         * needs to know before spending: the same button does two quite
         * different things depending on space. Answered here rather than
         * recomputed in the interface, because free-slot counting is the
         * castle's business.
         */
        public final boolean upgradesInstead;

        ItemView(ShopItemDef def, int cost, boolean available, boolean affordable,
                 int level, boolean upgradesInstead) {
            this.def = def;
            this.cost = cost;
            this.available = available;
            this.affordable = affordable;
            this.level = level;
            this.upgradesInstead = upgradesInstead;
        }

        public boolean buyable() {
            return available && affordable;
        }
    }

    public Array<ItemView> view() {
        Array<ItemView> out = new Array<>(false, table.size());
        for (ShopItemDef d : table.all()) {
            out.add(view(d.id));
        }
        return out;
    }

    public ItemView view(String id) {
        ShopItemDef d = table.require(id);
        int cost = currentCost(id);
        boolean upgrades = d.effect == ShopItemDef.Effect.TOWER
                && purchases.get(d.id, 0) > 0
                && ctx.castle().freeSlots().size == 0;
        return new ItemView(d, cost, isAvailable(id),
                ctx.session().gold() >= cost, levelOf(d), upgrades);
    }

    private int levelOf(ShopItemDef d) {
        switch (d.effect) {
            case TOWER:
                return purchases.get(d.id, 0);
            case WALL:
                return ctx.castle().wallLevel();
            case BOUNCE:
                return ctx.bounceLevel();
            case GRAB:
                return ctx.grabLevel();
            case MULTI:
                return ctx.multiLevel();
            case OUTPOST:
                return ctx.outpost().level();
            case SPIKES:
                return ctx.spikes().level();
            case BARRICADE:
                return ctx.barricade().level();
            default:
                return 0;
        }
    }
}
