package com.mymmer.castledefense.talent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.badlogic.gdx.utils.Array;
import com.mymmer.castledefense.config.Tuning;
import com.mymmer.castledefense.testsupport.DiskJsonSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The authoritative inventory: all 38 talents, checked one by one.
 *
 * <p>There are only 38, so this samples nothing. Every node is named here with
 * its branch, tier, rank cap and per-rank magnitude, transcribed from
 * {@code main.py:313}. If a node is added, removed or retuned in the source,
 * exactly one row here fails and says which.
 *
 * <p>At the end of Phase 9 there is no talent in the Python source without an
 * explicit Java disposition, and this test is what says so.
 */
class TalentInventoryTest {

    private static TalentTable table;

    @BeforeAll
    static void load() {
        table = TalentTable.load(new DiskJsonSource());
    }

    /**
     * The whole table, transcribed.
     *
     * <p>{@code id, branch, tier, maxRank, perRank, effect}
     */
    private static final Object[][] SOURCE = {
        // --- OFFENCE (6) ----------------------------------------------------
        {"rate",         TalentBranch.OFFENSE,    0, 5, 0.06,  TalentEffect.TOWER_RATE},
        {"power",        TalentBranch.OFFENSE,    0, 5, 0.08,  TalentEffect.TOWER_DAMAGE},
        {"crit",         TalentBranch.OFFENSE,    2, 5, 0.05,  TalentEffect.CRIT_CHANCE},
        {"pierce",       TalentBranch.OFFENSE,    4, 3, 1.0,   TalentEffect.EXTRA_PIERCE},
        {"splash",       TalentBranch.OFFENSE,    6, 4, 0.10,  TalentEffect.SPLASH_MULT},
        {"overcharge",   TalentBranch.OFFENSE,    9, 3, 0.18,  TalentEffect.OVERCHARGE_CD},

        // --- DEFENCE (6) ----------------------------------------------------
        {"maxhp",        TalentBranch.DEFENSE,    0, 5, 0.10,  TalentEffect.CASTLE_HP},
        {"regen",        TalentBranch.DEFENSE,    0, 4, 0.012, TalentEffect.BARRICADE_REGEN},
        {"spikedot",     TalentBranch.DEFENSE,    2, 4, 0.9,   TalentEffect.SPIKE_DOT},
        {"towerhp",      TalentBranch.DEFENSE,    3, 4, 0.18,  TalentEffect.TOWER_HP},
        {"rebuild",      TalentBranch.DEFENSE,    5, 3, 0.20,  TalentEffect.REBUILD_MULT},
        {"thorns",       TalentBranch.DEFENSE,    8, 3, 0.07,  TalentEffect.DAMAGE_TAKEN},

        // --- UTILITY (8) ----------------------------------------------------
        {"greed",        TalentBranch.UTILITY,    0, 5, 0.12,  TalentEffect.GOLD_POP},
        {"lighthands",   TalentBranch.UTILITY,    0, 4, 0.14,  TalentEffect.GRAB_BONUS},
        {"haggle",       TalentBranch.UTILITY,    2, 4, 0.06,  TalentEffect.SHOP_DISCOUNT},
        {"purse",        TalentBranch.UTILITY,    3, 4, 40.0,  TalentEffect.WAVE_PURSE},
        {"lightfingers", TalentBranch.UTILITY,    3, 4, 0.20,  TalentEffect.GRAB_CD_SCALE},
        {"showman",      TalentBranch.UTILITY,    5, 4, 0.15,  TalentEffect.SCORE_MULT},
        {"scavenge",     TalentBranch.UTILITY,    7, 3, 0.10,  TalentEffect.KILL_GOLD},
        {"sentinels",    TalentBranch.UTILITY,    5, 1, 1.0,   TalentEffect.ALLY_SENTINELS},

        // --- AERO-MASTERY (6) -----------------------------------------------
        {"stormwinds",   TalentBranch.AERO,       0, 1, 0.30,  TalentEffect.STORM_WIND_SLOW},
        {"throwarm",     TalentBranch.AERO,       0, 5, 0.08,  TalentEffect.THROW_POWER},
        {"updraft",      TalentBranch.AERO,       2, 4, 0.12,  TalentEffect.FALL_DAMAGE},
        {"conductor",    TalentBranch.AERO,       4, 4, 0.15,  TalentEffect.LIGHTNING_MULT},
        {"gale",         TalentBranch.AERO,       6, 3, 0.25,  TalentEffect.WIND_MULT},
        {"tempest",      TalentBranch.AERO,       9, 1, 1.0,   TalentEffect.STORM_CHANCE},

        // --- NECROMANCY (6) -------------------------------------------------
        {"bonecraft",    TalentBranch.NECROMANCY, 0, 5, 0.16,  TalentEffect.ALLY_POWER},
        {"hostmaster",   TalentBranch.NECROMANCY, 0, 4, 1.0,   TalentEffect.ALLY_CAP_BONUS},
        {"quickraise",   TalentBranch.NECROMANCY, 2, 4, 0.12,  TalentEffect.ALLY_RATE},
        {"gravechill",   TalentBranch.NECROMANCY, 4, 3, 0.06,  TalentEffect.GRAVE_CHILL},
        {"secondwind",   TalentBranch.NECROMANCY, 7, 2, 20.0,  TalentEffect.ALLY_LIFE},
        {"bonewall",     TalentBranch.NECROMANCY, 9, 3, 0.12,  TalentEffect.ALLY_TOUGH},

        // --- ARCANE (6) -----------------------------------------------------
        {"focus",        TalentBranch.ARCANE,     0, 5, 0.07,  TalentEffect.SKILL_CD},
        {"amplify",      TalentBranch.ARCANE,     0, 5, 0.10,  TalentEffect.SKILL_POWER},
        {"widecast",     TalentBranch.ARCANE,     3, 4, 0.12,  TalentEffect.SKILL_AREA},
        {"emberfall",    TalentBranch.ARCANE,     5, 3, 0.30,  TalentEffect.FIRE_TIME},
        {"eyeofstorm",   TalentBranch.ARCANE,     8, 2, 0.25,  TalentEffect.TORNADO_MULT},
        {"twincast",     TalentBranch.ARCANE,     10, 2, 0.5,  TalentEffect.METEOR_COUNT},
    };

    @Test
    @DisplayName("there are exactly 38 talents, and the table has no extras")
    void count() {
        assertEquals(38, SOURCE.length, "the transcription itself must have 38 rows");
        assertEquals(38, table.size(), "and the data must have the same");

        for (int i = 0; i < table.all().size; i++) {
            TalentDef d = table.all().get(i);
            boolean known = false;
            for (Object[] row : SOURCE) {
                if (row[0].equals(d.id)) {
                    known = true;
                    break;
                }
            }
            if (!known) {
                fail("data has talent '" + d.id + "', which the source does not");
            }
        }
    }

    @Test
    @DisplayName("every talent matches the source row for row")
    void everyTalentMatchesTheSource() {
        for (Object[] row : SOURCE) {
            String id = (String) row[0];
            TalentDef d = table.get(id);
            assertNotNull(d, "missing talent '" + id + "'");
            assertEquals(row[1], d.branch, id + ": branch");
            assertEquals(((Integer) row[2]).intValue(), d.tier, id + ": tier");
            assertEquals(((Integer) row[3]).intValue(), d.maxRank, id + ": maxRank");
            assertEquals((Double) row[4], d.perRank, 1e-9, id + ": perRank");
            assertEquals(row[5], d.effect, id + ": effect");
        }
    }

    @Test
    @DisplayName("the branches hold the counts the source gives them")
    void branchSizes() {
        assertEquals(6, table.branch(TalentBranch.OFFENSE).size);
        assertEquals(6, table.branch(TalentBranch.DEFENSE).size);
        assertEquals(8, table.branch(TalentBranch.UTILITY).size);
        assertEquals(6, table.branch(TalentBranch.AERO).size);
        assertEquals(6, table.branch(TalentBranch.NECROMANCY).size);
        assertEquals(6, table.branch(TalentBranch.ARCANE).size);
    }

    @Test
    @DisplayName("the two talents whose magnitude lives in the tuning block agree with it")
    void tunedMagnitudes() {
        assertEquals(Tuning.GRAB_CD_PER_RANK, table.get("lightfingers").perRank, 1e-9,
                "Light Fingers is GRAB_CD_PER_RANK per rank");
        //  STORM_WIND_SLOW is a FLOAT in the tuning block, and correctly so: it
        //  is a fraction applied to a speed, not a duration.  The talent's
        //  perRank is a double because a parity fixture compares it against
        //  Python's 0.30.  They are the same number at the constant's own
        //  precision, which is what this asserts.
        assertEquals(Tuning.STORM_WIND_SLOW,
                (float) table.get("stormwinds").perRank, 0f,
                "Storm Winds is STORM_WIND_SLOW");
    }

    @Test
    @DisplayName("every effect id is used, so none was defined and forgotten")
    void everyEffectIsUsed() {
        Array<TalentEffect> unused = new Array<>();
        for (TalentEffect e : TalentEffect.values()) {
            boolean used = false;
            for (int i = 0; i < table.all().size; i++) {
                TalentDef d = table.all().get(i);
                if (d.effect == e) {
                    used = true;
                    break;
                }
            }
            if (!used) {
                unused.add(e);
            }
        }
        if (unused.size > 0) {
            fail("these effects are declared but no talent uses them: " + unused);
        }
    }

    @Test
    @DisplayName("no two talents share an effect")
    void effectsAreOneToOne() {
        for (int i = 0; i < table.all().size; i++) {
            for (int j = i + 1; j < table.all().size; j++) {
                TalentDef a = table.all().get(i);
                TalentDef b = table.all().get(j);
                assertTrue(a.effect != b.effect,
                        a.id + " and " + b.id + " both claim " + a.effect);
            }
        }
    }

    @Test
    @DisplayName("every node is reachable: its branch can be entered and its tier reached")
    void everyNodeIsReachable() {
        for (int i = 0; i < table.all().size; i++) {
            TalentDef d = table.all().get(i);
            //  A branch's total capacity has to cover this node's tier, or no
            //  amount of play could ever open it.
            int capacity = 0;
            Array<TalentDef> siblings = table.branch(d.branch);
            for (int j = 0; j < siblings.size; j++) {
                TalentDef other = siblings.get(j);
                if (other != d) {
                    capacity += other.maxRank;
                }
            }
            assertTrue(capacity >= d.tier,
                    d.id + " needs " + d.tier + " points in " + d.branch.id()
                            + " but the rest of that branch only holds " + capacity);
        }
    }

    @Test
    @DisplayName("localisation keys are derived from the id, never the other way round")
    void localisationKeys() {
        for (int i = 0; i < table.all().size; i++) {
            TalentDef d = table.all().get(i);
            assertEquals("talent." + d.id + ".name", d.nameKey());
            assertEquals("talent." + d.id + ".desc", d.descriptionKey());
        }
        for (TalentBranch b : TalentBranch.values()) {
            assertEquals("talent.branch." + b.id(), b.nameKey());
        }
    }

    @Test
    @DisplayName("value scales linearly with rank, up to the cap")
    void valueScaling() {
        for (int n = 0; n < table.all().size; n++) {
            TalentDef d = table.all().get(n);
            //  open the branch first: buy the cheapest entry node repeatedly
            TalentTree fresh = new TalentTree(table);
            fresh.award(200, "test");
            openBranch(fresh, d);
            for (int r = 1; r <= d.maxRank; r++) {
                assertTrue(fresh.purchase(d.id), d.id + ": rank " + r + " should be buyable");
                assertEquals(r * d.perRank, fresh.value(d.id), 1e-9,
                        d.id + " at rank " + r);
            }
            assertEquals(d.maxRank, fresh.rank(d.id));
        }
    }

    /** Spends into a branch's entry nodes until the given node's tier is open. */
    static void openBranch(TalentTree tree, TalentDef target) {
        int guard = 0;
        while (tree.branchPoints(target.branch) < target.tier && guard++ < 200) {
            boolean spent = false;
            //  index loop: libGDX Array iterators are pooled and cannot nest
            Array<TalentDef> siblings = tree.table().branch(target.branch);
            for (int i = 0; i < siblings.size; i++) {
                TalentDef other = siblings.get(i);
                if (other != target && tree.canPurchase(other.id)) {
                    tree.purchase(other.id);
                    spent = true;
                    break;
                }
            }
            if (!spent) {
                fail("cannot open " + target.id + ": branch "
                        + target.branch.id() + " stalled at "
                        + tree.branchPoints(target.branch) + "/" + target.tier);
            }
        }
    }
}
