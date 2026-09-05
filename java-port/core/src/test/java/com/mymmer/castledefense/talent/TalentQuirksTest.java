package com.mymmer.castledefense.talent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.mymmer.castledefense.defence.CombatModifiers;
import com.mymmer.castledefense.progress.TestRun;
import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Progression behaviours that look like bugs and are not.
 *
 * <p>Each of these is documented in {@code PORT_ANALYSIS.md} §13 and each is
 * reproduced deliberately. A test here failing means someone has "fixed" the
 * source, which is a gameplay change and must be a decision rather than an
 * accident.
 */
class TalentQuirksTest {

    // ========================================================================
    //  Deep Foundations
    // ========================================================================

    @Test
    @DisplayName("Deep Foundations changes progression state and NOT castle health")
    void deepFoundationsDoesNotChangeCastleHealth() {
        //  THE QUIRK.  main.py defines TalentTree.castle_hp and NOTHING reads
        //  it -- grep the source and the only hit is its own definition.  So a
        //  player who buys all five ranks of "Deep Foundations" gets a node that
        //  says "Castle maximum health +50%" and no extra health at all.
        //
        //  It is reproduced, not fixed.  Making it work would be a balance
        //  change to a shipped game, and that is a deliberate decision for after
        //  the port -- not a side effect of finally implementing the tree.
        TestRun r = new TestRun().beginClassic();

        float maxBefore = r.run.castle().maxHp();
        float hpBefore = r.run.castle().hp();

        r.grantPoints(5).buyTalent("maxhp", 5);

        //  the progression state really did change
        assertEquals(5, r.talents().rank("maxhp"), "the ranks were bought");
        assertEquals(5, r.talents().branchPoints(TalentBranch.DEFENSE),
                "and they count toward the defence branch");
        assertEquals(1d + 0.50d, r.talents().castleHpClaim(), 1e-9,
                "and the node's claimed value moved");

        //  ...and the castle did not
        assertEquals(maxBefore, r.run.castle().maxHp(), 0f,
                "Deep Foundations must NOT change maximum health -- see "
                        + "PORT_ANALYSIS section 13");
        assertEquals(hpBefore, r.run.castle().hp(), 0f,
                "nor current health");

        //  and it still does nothing after a wall upgrade recomputes the keep
        r.run.castle().upgradeWall();
        float afterUpgrade = r.run.castle().maxHp();
        TestRun plain = new TestRun().beginClassic();
        plain.run.castle().upgradeWall();
        assertEquals(plain.run.castle().maxHp(), afterUpgrade, 1e-3f,
                "a reinforced wall is worth the same with or without the talent");
    }

    @Test
    @DisplayName("nothing in CombatModifiers exposes castle health, so nothing can wire it")
    void castleHpIsNotOnTheModifierSeam() {
        //  Structural half of the same quirk: the number is reachable only from
        //  TalentTree itself, for a tooltip.  If someone adds a castleHp() to
        //  the shared interface, this fails and asks them to mean it.
        for (Method m : CombatModifiers.class.getMethods()) {
            String n = m.getName().toLowerCase(java.util.Locale.ROOT);
            if (n.contains("castlehp") || n.equals("maxhp")) {
                fail("CombatModifiers." + m.getName() + " would let Deep Foundations "
                        + "reach the castle. That is a gameplay change, not a fix.");
            }
        }
    }

    // ========================================================================
    //  Other progression quirks
    // ========================================================================

    @Test
    @DisplayName("Prospector raises the crowd-gold CAP as well as the step")
    void crowdFinancierRaisesTheCap() {
        //  main.py: min(POP_GOLD_CAP * gold_pop, 1 + step * n) -- the talent is
        //  applied to BOTH terms.  Reading it as a cap on the base rate alone
        //  would quietly halve the talent at high populations.
        TestRun plain = new TestRun().beginClassic();
        TestRun greedy = new TestRun().beginClassic();
        greedy.grantPoints(5).buyTalent("greed", 5);

        for (int i = 0; i < 120; i++) {
            plain.run.spawnEnemy(com.mymmer.castledefense.enemy.EnemyType.SCOUT, 1);
            greedy.run.spawnEnemy(com.mymmer.castledefense.enemy.EnemyType.SCOUT, 1);
        }
        //  both are far past the cap; the talent must still separate them
        assertEquals(3f, plain.run.goldMultiplier(), 1e-4f, "the plain cap");
        assertEquals(3f * 1.60f, greedy.run.goldMultiplier(), 1e-3f,
                "5 ranks x 0.12 raises the ceiling too");
        assertTrue(greedy.run.goldMultiplier() > plain.run.goldMultiplier());
    }

    @Test
    @DisplayName("a talent point is spent on the rank, never refunded by a later refusal")
    void pointsAreNotRefunded() {
        TestRun r = new TestRun().beginClassic();
        r.grantPoints(5).buyTalent("rate", 5);
        assertEquals(0, r.talents().availablePoints());
        assertFalse(r.talents().purchase("rate"));
        assertEquals(0, r.talents().availablePoints(), "and nothing came back");
    }

    @Test
    @DisplayName("the boss bounty is 2 points for a skill slot and 3 once the bar is full")
    void bossBounty() {
        //  main.py on_boss_defeated: unlock_next() first, and the award depends
        //  on whether there was a slot left.
        TestRun r = new TestRun().beginEndless();
        int[] expected = {2, 2, 2, 3, 3};
        int points = r.talents().availablePoints();

        for (int i = 0; i < expected.length; i++) {
            r.run.onBossDefeated(null);
            int gained = r.talents().availablePoints() - points;
            points = r.talents().availablePoints();
            assertEquals(expected[i], gained,
                    "boss " + (i + 1) + " should pay " + expected[i]);
        }
        assertEquals(3, r.skills().unlockedCount(), "and only three slots exist");
    }
}
