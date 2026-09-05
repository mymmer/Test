package com.mymmer.castledefense.talent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.badlogic.gdx.utils.Array;
import com.mymmer.castledefense.data.DataException;
import com.mymmer.castledefense.testsupport.DiskJsonSource;
import com.mymmer.castledefense.testsupport.InMemoryJsonSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Gating, rank caps, purchase failures and the validation that rejects bad data. */
class TalentTreeTest {

    private static TalentTable table;

    @BeforeAll
    static void load() {
        table = TalentTable.load(new DiskJsonSource());
    }

    private static TalentTree tree() {
        return new TalentTree(table);
    }

    // ========================================================================
    //  Gating
    // ========================================================================

    @Test
    @DisplayName("a tier-0 node is open from the start; a deeper one is not")
    void tierGate() {
        TalentTree t = tree();
        t.award(1, "test");
        assertTrue(t.isUnlocked("rate"), "tier 0 is open immediately");
        assertFalse(t.isUnlocked("crit"), "tier 2 needs two points in offense");
        assertTrue(t.canPurchase("rate"));
        assertFalse(t.canPurchase("crit"));
    }

    @Test
    @DisplayName("the gate counts points in the node's OWN branch, whichever node they went to")
    void gateCountsTheBranchNotTheNode() {
        TalentTree t = tree();
        t.award(10, "test");

        //  two points into offense -- split across two different nodes
        assertTrue(t.purchase("rate"));
        assertTrue(t.purchase("power"));
        assertEquals(2, t.branchPoints(TalentBranch.OFFENSE));
        assertTrue(t.isUnlocked("crit"), "two points anywhere in offense opens tier 2");

        //  and points in another branch do nothing for it
        TalentTree other = tree();
        other.award(10, "test");
        other.purchase("greed");
        other.purchase("lighthands");
        assertEquals(2, other.branchPoints(TalentBranch.UTILITY));
        assertEquals(0, other.branchPoints(TalentBranch.OFFENSE));
        assertFalse(other.isUnlocked("crit"), "utility points do not open offense");
    }

    @Test
    @DisplayName("a node's own ranks count toward its branch, so it can open the next tier")
    void ownRanksCountTowardTheBranch() {
        TalentTree t = tree();
        t.award(10, "test");
        t.purchase("rate");
        t.purchase("rate");
        assertEquals(2, t.branchPoints(TalentBranch.OFFENSE));
        assertTrue(t.isUnlocked("crit"));
    }

    @Test
    @DisplayName("the deepest node in each branch can be reached by playing")
    void deepestNodesAreReachable() {
        for (TalentBranch b : TalentBranch.values()) {
            TalentDef deepest = null;
            Array<TalentDef> nodes = table.branch(b);
            for (int i = 0; i < nodes.size; i++) {
                if (deepest == null || nodes.get(i).tier > deepest.tier) {
                    deepest = nodes.get(i);
                }
            }
            TalentTree t = tree();
            t.award(500, "test");
            TalentInventoryTest.openBranch(t, deepest);
            assertTrue(t.canPurchase(deepest.id),
                    b.id() + ": " + deepest.id + " should become buyable");
            assertTrue(t.purchase(deepest.id));
        }
    }

    // ========================================================================
    //  Rank caps
    // ========================================================================

    @Test
    @DisplayName("a talent cannot be bought past its rank cap")
    void rankCap() {
        TalentTree t = tree();
        t.award(50, "test");
        for (int i = 0; i < 5; i++) {
            assertTrue(t.purchase("rate"), "rank " + (i + 1));
        }
        assertEquals(5, t.rank("rate"));
        assertEquals(5, t.maxRank("rate"));

        int before = t.availablePoints();
        assertFalse(t.purchase("rate"), "the sixth is refused");
        assertEquals(5, t.rank("rate"), "and the rank did not move");
        assertEquals(before, t.availablePoints(), "and no point was spent");
    }

    @Test
    @DisplayName("a maxed node is still 'unlocked' -- the two are different questions")
    void maxedIsStillUnlocked() {
        TalentTree t = tree();
        t.award(50, "test");
        for (int i = 0; i < 5; i++) {
            t.purchase("rate");
        }
        assertTrue(t.isUnlocked("rate"), "its tier gate is still open");
        assertFalse(t.canPurchase("rate"), "but there is nothing left to buy");
    }

    @Test
    @DisplayName("every talent stops exactly at its own cap")
    void everyCapHolds() {
        for (int n = 0; n < table.all().size; n++) {
            TalentDef d = table.all().get(n);
            TalentTree t = tree();
            t.award(500, "test");
            TalentInventoryTest.openBranch(t, d);
            for (int r = 0; r < d.maxRank; r++) {
                assertTrue(t.purchase(d.id), d.id + " rank " + (r + 1));
            }
            assertFalse(t.purchase(d.id), d.id + " must stop at " + d.maxRank);
            assertEquals(d.maxRank, t.rank(d.id), d.id);
        }
    }

    // ========================================================================
    //  Failed purchases
    // ========================================================================

    @Test
    @DisplayName("a failed purchase never consumes a point")
    void failedPurchaseCostsNothing() {
        TalentTree t = tree();
        t.award(1, "test");

        //  locked tier
        assertFalse(t.purchase("crit"));
        assertEquals(1, t.availablePoints());
        assertEquals(0, t.rank("crit"));
        assertEquals(0, t.branchPoints(TalentBranch.OFFENSE));

        //  unknown id
        assertFalse(t.purchase("no_such_talent"));
        assertEquals(1, t.availablePoints());

        //  spend the point, then try again with an empty balance
        assertTrue(t.purchase("rate"));
        assertEquals(0, t.availablePoints());
        assertFalse(t.purchase("power"), "no points, no purchase");
        assertEquals(0, t.rank("power"));
    }

    @Test
    @DisplayName("awarding nothing, or a negative, changes nothing")
    void nonPositiveAwards() {
        TalentTree t = tree();
        t.award(0, "test");
        t.award(-5, "test");
        assertEquals(0, t.availablePoints());
        assertEquals(0, t.earnedPoints());
    }

    @Test
    @DisplayName("earned counts every point ever awarded; available counts what is left")
    void earnedVersusAvailable() {
        TalentTree t = tree();
        t.award(3, "test");
        t.purchase("rate");
        assertEquals(3, t.earnedPoints());
        assertEquals(2, t.availablePoints());
    }

    @Test
    @DisplayName("reset clears points, ranks and branch totals together")
    void reset() {
        TalentTree t = tree();
        t.award(10, "test");
        t.purchase("rate");
        t.purchase("greed");
        assertTrue(t.availablePoints() > 0 && t.rank("rate") == 1);

        t.reset();
        assertEquals(0, t.availablePoints());
        assertEquals(0, t.earnedPoints());
        assertEquals(0, t.rank("rate"));
        assertEquals(0, t.rank("greed"));
        for (TalentBranch b : TalentBranch.values()) {
            assertEquals(0, t.branchPoints(b), b.id());
        }
    }

    // ========================================================================
    //  The query surface Phase 10 will read
    // ========================================================================

    @Test
    @DisplayName("the view reports every node with its rank and eligibility")
    void queryView() {
        TalentTree t = tree();
        t.award(2, "test");
        t.purchase("rate");

        Array<TalentTree.NodeView> view = t.view();
        assertEquals(38, view.size);

        TalentTree.NodeView rate = t.view("rate");
        assertEquals(1, rate.rank);
        assertTrue(rate.unlocked);
        assertTrue(rate.purchasable);
        assertFalse(rate.maxed());
        assertEquals(0.06, rate.value, 1e-9);

        TalentTree.NodeView crit = t.view("crit");
        assertFalse(crit.unlocked, "one point in offense is not two");
        assertFalse(crit.purchasable);
    }

    // ========================================================================
    //  Data validation
    // ========================================================================

    @Test
    @DisplayName("a duplicate talent id is rejected")
    void duplicateId() {
        expectFailure(broken("\"id\": \"power\"", "\"id\": \"rate\""),
                "duplicate talent id");
    }

    @Test
    @DisplayName("an unknown effect is rejected rather than silently doing nothing")
    void unknownEffect() {
        expectFailure(broken("\"effect\": \"tower_rate\"", "\"effect\": \"tower_rat\""),
                "unknown effect");
    }

    @Test
    @DisplayName("an unknown branch is rejected")
    void unknownBranch() {
        expectFailure(broken("\"branch\": \"offense\"", "\"branch\": \"offence\""),
                "unknown branch");
    }

    @Test
    @DisplayName("a zero or negative max rank is rejected")
    void badMaxRank() {
        expectFailure(broken("\"maxRank\": 5, \"perRank\": 0.06",
                "\"maxRank\": 0, \"perRank\": 0.06"), "maxRank must be at least 1");
    }

    @Test
    @DisplayName("a negative tier is rejected")
    void negativeTier() {
        expectFailure(broken("\"tier\": 2, \"maxRank\": 5, \"perRank\": 0.05",
                "\"tier\": -1, \"maxRank\": 5, \"perRank\": 0.05"),
                "tier must not be negative");
    }

    @Test
    @DisplayName("a non-positive per-rank magnitude is rejected")
    void badPerRank() {
        expectFailure(broken("\"perRank\": 0.06", "\"perRank\": 0"),
                "perRank must be greater than 0");
    }

    @Test
    @DisplayName("a branch with no entry node is rejected as unreachable")
    void unreachableBranch() {
        //  push both offense tier-0 nodes out of reach and the whole branch is dead
        String json = read()
                .replace("\"id\": \"rate\",        \"branch\": \"offense\",    \"tier\": 0",
                        "\"id\": \"rate\",        \"branch\": \"offense\",    \"tier\": 1")
                .replace("\"id\": \"power\",       \"branch\": \"offense\",    \"tier\": 0",
                        "\"id\": \"power\",       \"branch\": \"offense\",    \"tier\": 1");
        expectFailure(json, "has no entry node");
    }

    @Test
    @DisplayName("a missing branch declaration is rejected")
    void missingBranchDeclaration() {
        expectFailure(read().replace("{ \"id\": \"arcane\",     \"name\": \"ARCANE\" }",
                        "{ \"id\": \"offense\",    \"name\": \"AGAIN\" }"),
                "declared twice");
    }

    // ------------------------------------------------------------------------

    private static String read() {
        try {
            return new String(java.nio.file.Files.readAllBytes(
                    new java.io.File("data/talents.json").toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }

    /** The shipped file with one substitution, applied once. */
    private static String broken(String from, String to) {
        String json = read();
        int at = json.indexOf(from);
        assertTrue(at >= 0, "the shipped file should contain: " + from);
        return json.substring(0, at) + to + json.substring(at + from.length());
    }

    private static void expectFailure(String json, String expectedMessage) {
        InMemoryJsonSource src = new InMemoryJsonSource();
        src.put(TalentTable.PATH, json);
        DataException e = assertThrows(DataException.class,
                () -> TalentTable.load(src));
        assertTrue(e.getMessage().contains(expectedMessage),
                "expected a message mentioning '" + expectedMessage
                        + "', got: " + e.getMessage());
    }
}
