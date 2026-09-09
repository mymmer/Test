package com.mymmer.castledefense.talent;

import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.JsonValue;
import com.badlogic.gdx.utils.ObjectMap;
import com.mymmer.castledefense.data.DataException;
import com.mymmer.castledefense.data.Json5;
import com.mymmer.castledefense.data.JsonSource;

/**
 * The talent definitions, loaded and validated once.
 *
 * <p>Malformed progression data is <b>fatal</b>, and deliberately so: a talent
 * with a typo'd effect id would otherwise be a node the player can buy that
 * silently does nothing, which is indistinguishable from the one node that is
 * <em>supposed</em> to do nothing (Deep Foundations). Failing at startup with the
 * field name beats discovering it at wave 30.
 *
 * <h2>Why there is no cycle check</h2>
 *
 * <p>There is no dependency graph to have a cycle in. Python gates a node on
 * {@code branch_points(branch) >= tier} — the number of points already spent
 * <em>anywhere in the same branch</em> — not on named prerequisite nodes. So the
 * "graph" is a set of six independent ladders, every node is reachable by
 * construction, and self-dependency is not expressible.
 *
 * <p>What <em>can</em> go wrong is a branch whose entry tier is above zero, which
 * would make the whole branch permanently unbuyable. That is checked, because it
 * is the real version of "unreachable node" here.
 */
public final class TalentTable {

    public static final String PATH = "data/talents.json";

    private final Array<TalentDef> all = new Array<>(false, 40);
    private final ObjectMap<String, TalentDef> byId = new ObjectMap<>();
    private final ObjectMap<TalentBranch, Array<TalentDef>> byBranch = new ObjectMap<>();

    private TalentTable() {
    }

    public static TalentTable load(JsonSource source) {
        if (source == null) {
            throw new DataException(PATH + ": no json source");
        }
        JsonValue root = source.read(PATH);
        TalentTable table = new TalentTable();
        for (TalentBranch b : TalentBranch.values()) {
            table.byBranch.put(b, new Array<TalentDef>(false, 8));
        }

        JsonValue branches = root.get("branches");
        if (branches == null || !branches.isArray() || branches.size == 0) {
            throw new DataException(PATH + ": 'branches' must be a non-empty array");
        }
        Array<TalentBranch> declared = new Array<>(false, 8);
        for (JsonValue b = branches.child; b != null; b = b.next) {
            String id = Json5.string(b, "id", PATH + " branch");
            TalentBranch branch = TalentBranch.byId(id);
            if (branch == null) {
                throw new DataException(PATH + ": unknown branch '" + id + "'");
            }
            if (declared.contains(branch, true)) {
                throw new DataException(PATH + ": branch '" + id + "' declared twice");
            }
            declared.add(branch);
        }
        for (TalentBranch b : TalentBranch.values()) {
            if (!declared.contains(b, true)) {
                throw new DataException(PATH + ": no declaration for branch '"
                        + b.id() + "'");
            }
        }

        JsonValue talents = root.get("talents");
        if (talents == null || !talents.isArray() || talents.size == 0) {
            throw new DataException(PATH + ": 'talents' must be a non-empty array");
        }
        for (JsonValue t = talents.child; t != null; t = t.next) {
            String id = Json5.string(t, "id", PATH);
            String where = PATH + " talent '" + id + "'";
            if (table.byId.containsKey(id)) {
                throw new DataException(PATH + ": duplicate talent id '" + id + "'");
            }

            TalentBranch branch = TalentBranch.byId(Json5.string(t, "branch", where));
            if (branch == null) {
                throw new DataException(where + ": unknown branch");
            }

            int tier = (int) Json5.number(t, "tier", where);
            if (tier < 0) {
                throw new DataException(where + ": tier must not be negative, was " + tier);
            }

            int maxRank = (int) Json5.number(t, "maxRank", where);
            if (maxRank < 1) {
                throw new DataException(where + ": maxRank must be at least 1, was "
                        + maxRank);
            }

            double perRank = Json5.exact(t, "perRank", where);
            if (!(perRank > 0d)) {
                throw new DataException(where + ": perRank must be greater than 0, was "
                        + perRank);
            }

            String effectId = Json5.string(t, "effect", where);
            TalentEffect effect = TalentEffect.byId(effectId);
            if (effect == null) {
                throw new DataException(where + ": unknown effect '" + effectId
                        + "'. A talent whose effect does not exist is a talent the "
                        + "player can buy that does nothing.");
            }

            //  How the description's value is written. Carried in the data
            //  because the source formats each talent's {v} itself and no rule
            //  over perRank reproduces all of them -- see TalentDef.ValueFormat.
            String fmt = Json5.optString(t, "format", "none");
            TalentDef.ValueFormat format;
            try {
                format = TalentDef.ValueFormat.valueOf(
                        fmt.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new DataException(where + ": unknown value format '" + fmt
                        + "'; expected percent, percent1, number, decimal or none");
            }
            TalentDef def = new TalentDef(id, branch, tier, maxRank, perRank,
                    effect, format);
            table.all.add(def);
            table.byId.put(id, def);
            table.byBranch.get(branch).add(def);
        }

        table.validateBranches();
        return table;
    }

    /**
     * Every branch must have a way in.
     *
     * <p>A branch whose cheapest node needs points already in that branch can
     * never be entered, so every one of its nodes is unreachable. That is the
     * only shape of unreachability this gating model admits, and it is a data
     * error rather than a design choice.
     */
    private void validateBranches() {
        for (TalentBranch b : TalentBranch.values()) {
            Array<TalentDef> nodes = byBranch.get(b);
            if (nodes.size == 0) {
                throw new DataException(PATH + ": branch '" + b.id() + "' has no talents");
            }
            int cheapest = Integer.MAX_VALUE;
            for (TalentDef d : nodes) {
                cheapest = Math.min(cheapest, d.tier);
            }
            if (cheapest > 0) {
                throw new DataException(PATH + ": branch '" + b.id() + "' has no entry "
                        + "node -- its cheapest talent needs " + cheapest
                        + " points already in the branch, so nothing in it can ever "
                        + "be bought");
            }
        }
    }

    // --- queries ------------------------------------------------------------

    /** Every talent, in declaration order. */
    public Array<TalentDef> all() {
        return all;
    }

    public int size() {
        return all.size;
    }

    /** One talent by stable id, or null. */
    public TalentDef get(String id) {
        return byId.get(id);
    }

    /** One talent by stable id, or a {@link DataException} naming it. */
    public TalentDef require(String id) {
        TalentDef d = byId.get(id);
        if (d == null) {
            throw new DataException("no such talent: '" + id + "'");
        }
        return d;
    }

    public boolean contains(String id) {
        return byId.containsKey(id);
    }

    /** The nodes of one branch, in declaration order. */
    public Array<TalentDef> branch(TalentBranch branch) {
        return byBranch.get(branch);
    }
}
