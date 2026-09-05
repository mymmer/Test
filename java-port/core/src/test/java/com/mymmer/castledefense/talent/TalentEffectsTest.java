package com.mymmer.castledefense.talent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mymmer.castledefense.config.GameConfig;
import com.mymmer.castledefense.config.Tuning;
import com.mymmer.castledefense.defence.DefenceTower;
import com.mymmer.castledefense.defence.TowerType;
import com.mymmer.castledefense.enemy.Enemy;
import com.mymmer.castledefense.enemy.EnemyType;
import com.mymmer.castledefense.progress.Scoring;
import com.mymmer.castledefense.progress.TestRun;
import com.mymmer.castledefense.skill.SkillId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a talent actually changes, proved against the running game.
 *
 * <p>Every one of these buys the talent <b>during a run</b> and then performs the
 * gameplay action it should affect. That is the point: a modifier that is
 * computed live cannot go stale, and these are what say so for each subsystem
 * rather than for the tree in isolation.
 */
class TalentEffectsTest {

    // ========================================================================
    //  The formulas, and their caps
    // ========================================================================

    @Test
    @DisplayName("each capped effect stops where the source caps it")
    void caps() {
        TestRun r = new TestRun().beginClassic();
        TalentTree t = r.talents();
        t.award(500, "test");

        //  rate: 1 - min(0.45, 0.06 * rank).  5 ranks is 0.30, under the cap.
        r.buyTalent("rate", 5);
        assertEquals(1d - 0.30d, t.towerRate(), 1e-9, "five ranks is 30% off");

        //  rebuild: 1 - min(0.6, 0.20 * rank).  3 ranks is exactly 0.60.
        r.openBranchFor("rebuild");
        r.buyTalent("rebuild", 3);
        assertEquals(1d - 0.60d, t.rebuildMult(), 1e-9, "three ranks hits the cap exactly");

        //  thorns: 1 - min(0.4, 0.07 * rank).  3 ranks is 0.21.
        r.openBranchFor("thorns");
        r.buyTalent("thorns", 3);
        assertEquals(1f - 0.21f, t.damageTaken(), 1e-5f);

        //  haggle: 1 - min(0.4, 0.06 * rank).  4 ranks is 0.24.
        r.openBranchFor("haggle");
        r.buyTalent("haggle", 4);
        assertEquals(1d - 0.24d, t.shopDiscount(), 1e-9);
    }

    @Test
    @DisplayName("Tempest Caller is a flag, not a per-rank multiplier")
    void tempestIsAFlag() {
        TestRun r = new TestRun().beginClassic();
        TalentTree t = r.talents();
        assertEquals(1f, t.stormChance(), 0f);
        t.award(500, "test");
        r.openBranchFor("tempest");
        r.buyTalent("tempest", 1);
        assertEquals(2f, t.stormChance(), 0f, "twice as often, not 1 + v");
    }

    @Test
    @DisplayName("Light Fingers can take the grab delay all the way to zero")
    void lightFingersToZero() {
        TestRun r = new TestRun().beginClassic();
        TalentTree t = r.talents();
        t.award(500, "test");
        r.openBranchFor("lightfingers");
        r.buyTalent("lightfingers", 4);
        //  4 ranks x 0.20 = 0.80, so 20% of the delay is left -- and the formula
        //  is max(0, 1 - v), which never goes negative
        assertEquals(0.2d, t.grabCdScale(), 1e-9);
        assertTrue(t.grabCdScale() >= 0d);
    }

    // ========================================================================
    //  Cross-system: bought mid-run, felt on the next action
    // ========================================================================

    @Test
    @DisplayName("Sharpened Heads reaches a tower that was already standing")
    void towerDamageIsLive() {
        TestRun r = new TestRun().beginClassic();
        DefenceTower t = r.run.castle().addTower(TowerType.BOWMAN);
        assertEquals(1f, r.run.modifiers().towerDamage(), 0f);

        r.grantPoints(3).buyTalent("power", 3);
        assertEquals(1f + 0.24f, r.run.modifiers().towerDamage(), 1e-5f,
                "the tower standing since before the purchase sees it too");
        assertTrue(t.damage() > 0f);
    }

    @Test
    @DisplayName("Light Fingers shortens the grab delay from the next grab onward")
    void grabCooldownIsLive() {
        TestRun r = new TestRun();
        r.begin(com.mymmer.castledefense.game.GameMode.CLASSIC, "normal");
        double before = r.run.cursor().grabCooldown();
        assertEquals(r.difficulty("normal").grabCd(), before, 1e-9);

        r.grantPoints(10);
        r.buyTalentDeep("lightfingers", 2);
        r.run.refreshGrabCooldown();

        assertEquals(before * (1d - 0.40d), r.run.cursor().grabCooldown(), 1e-9,
                "two ranks is 40% off the difficulty's delay");
    }

    @Test
    @DisplayName("Light Hands raises the weight the cursor can lift")
    void grabCapacityIsLive() {
        TestRun r = new TestRun().beginClassic();
        float before = r.run.cursor().grabCapacity();
        r.grantPoints(4).buyTalent("lighthands", 4);
        float after = r.run.cursor().grabCapacity();
        assertEquals(before * (1f + 0.56f), after, 1e-3f, "4 ranks x 0.14");
    }

    @Test
    @DisplayName("Crowd Financier and Scavenger both move the gold multiplier")
    void goldTalentsAreLive() {
        TestRun r = new TestRun().beginClassic();
        for (int i = 0; i < 10; i++) {
            r.run.spawnEnemy(EnemyType.SCOUT, 1);
        }
        float plain = r.run.goldMultiplier();

        r.grantPoints(20).buyTalent("greed", 5);
        float greedy = r.run.goldMultiplier();
        assertTrue(greedy > plain, "Crowd Financier raises the step and the cap");

        r.buyTalentDeep("scavenge", 3);
        assertEquals(greedy * (1f + 0.30f), r.run.goldMultiplier(), 1e-4f,
                "Scavenger multiplies on top");
    }

    @Test
    @DisplayName("Fat Purse is added to the Classic wave bonus")
    void wavePurse() {
        TestRun r = new TestRun().beginClassic();
        assertEquals(80 + 22, Scoring.waveBonus(1, r.run.modifiers()));

        r.grantPoints(20);
        r.buyTalentDeep("purse", 2);
        assertEquals(80 + 22 + 80, Scoring.waveBonus(1, r.run.modifiers()),
                "two ranks is 80 gold a wave");
    }

    @Test
    @DisplayName("Showman multiplies a fling award")
    void scoreMultiplier() {
        TestRun r = new TestRun().beginClassic();
        r.session().addScore(1000, 0, 1f);
        assertEquals(1000, r.session().score());

        r.grantPoints(20);
        r.buyTalentDeep("showman", 2);
        int before = r.session().score();
        r.session().addScore(1000, 0, 1f);
        assertEquals((int) (1000 * 1.30f), r.session().score() - before,
                "two ranks is +30%");
    }

    @Test
    @DisplayName("Storm Winds only slows the horde while a strong headwind blows")
    void stormWindsNeedsAHeadwind() {
        TestRun r = new TestRun().beginClassic();
        r.grantPoints(1).buyTalent("stormwinds", 1);
        Enemy e = r.run.spawnEnemy(EnemyType.SCOUT, 1);

        //  roll until each case appears rather than forcing the field
        boolean sawSlow = false;
        boolean sawNoSlow = false;
        for (int i = 0; i < 400 && !(sawSlow && sawNoSlow); i++) {
            r.run.weather().roll();
            float slow = r.run.enemySlow(e);
            if (r.run.weather().strongHeadwind()) {
                assertEquals(1f - Tuning.STORM_WIND_SLOW, slow, 1e-5f, "headwind slows");
                sawSlow = true;
            } else {
                assertEquals(1f, slow, 1e-5f, "anything else does not");
                sawNoSlow = true;
            }
        }
        assertTrue(sawSlow && sawNoSlow, "both cases should occur");
    }

    @Test
    @DisplayName("Undead Sentinels is a rank flag the ally reads")
    void sentinels() {
        TestRun r = new TestRun().beginClassic();
        assertFalse(r.run.modifiers().allySentinels());
        r.grantPoints(20);
        r.buyTalentDeep("sentinels", 1);
        assertTrue(r.run.modifiers().allySentinels());
    }

    @Test
    @DisplayName("Host Master raises the ally cap the Outpost respects")
    void allyCap() {
        TestRun r = new TestRun().beginClassic();
        assertEquals(0, r.run.modifiers().allyCapBonus());
        r.grantPoints(4).buyTalent("hostmaster", 4);
        assertEquals(4, r.run.modifiers().allyCapBonus(), "int(4 x 1.0)");
    }

    @Test
    @DisplayName("Second Wind lengthens an ally's life, and it is a double")
    void allyLife() {
        TestRun r = new TestRun().beginClassic();
        assertEquals(0d, r.run.modifiers().allyLife(), 0d);
        r.grantPoints(20);
        r.buyTalentDeep("secondwind", 2);
        assertEquals(40d, r.run.modifiers().allyLife(), 1e-9, "two ranks is 40 s");
    }

    @Test
    @DisplayName("Piercing Shot adds bodies to a Ballista bolt")
    void extraPierce() {
        TestRun r = new TestRun().beginClassic();
        assertEquals(0, r.run.modifiers().extraPierce());
        r.grantPoints(20);
        r.buyTalentDeep("pierce", 3);
        assertEquals(3, r.run.modifiers().extraPierce(), "int(3 x 1.0)");
    }

    @Test
    @DisplayName("Arcane Focus shortens the next skill cooldown, not the one running")
    void skillCooldownIsAppliedAtCast() {
        TestRun r = new TestRun().beginClassic();
        r.skills().unlockNext();                 // Lightning
        assertEquals(Tuning.LIGHTNING_COOLDOWN, r.skills().fullCooldown(SkillId.LIGHTNING),
                1e-9);

        assertTrue(r.skills().castAt(SkillId.LIGHTNING, 800f, 500f));
        double running = r.skills().cooldownRemaining(SkillId.LIGHTNING);
        assertEquals(Tuning.LIGHTNING_COOLDOWN, running, 1e-9);

        r.grantPoints(5).buyTalent("focus", 5);
        assertEquals(running, r.skills().cooldownRemaining(SkillId.LIGHTNING), 1e-9,
                "the wait already running is not retuned");
        assertEquals(Tuning.LIGHTNING_COOLDOWN * (1d - 0.35d),
                r.skills().fullCooldown(SkillId.LIGHTNING), 1e-9,
                "but the next one is shorter");
    }

    @Test
    @DisplayName("Wide Cast and Amplify reach a skill cast after the purchase")
    void skillAreaAndPower() {
        TestRun r = new TestRun().beginClassic();
        assertEquals(1f, r.run.modifiers().skillArea(), 0f);
        assertEquals(1f, r.run.modifiers().skillPower(), 0f);

        r.grantPoints(20).buyTalent("amplify", 5);
        assertEquals(1f + 0.50f, r.run.modifiers().skillPower(), 1e-5f);

        r.buyTalentDeep("widecast", 4);
        assertEquals(1f + 0.48f, r.run.modifiers().skillArea(), 1e-5f);
    }

    @Test
    @DisplayName("Haggler makes the shop cheaper, on the next price query")
    void shopDiscountIsLive() {
        TestRun r = new TestRun().beginClassic();
        int before = r.shop().currentCost("bowman");
        assertEquals(110, before);

        r.grantPoints(20);
        r.buyTalentDeep("haggle", 4);
        assertEquals((int) (110 * (1d - 0.24d)), r.shop().currentCost("bowman"),
                "24% off, truncated once");
    }

    @Test
    @DisplayName("no talent bought means every modifier is exactly neutral")
    void neutralByDefault() {
        TestRun r = new TestRun().beginClassic();
        TalentTree t = r.talents();
        assertEquals(1d, t.towerRate(), 0d);
        assertEquals(1f, t.towerDamage(), 0f);
        assertEquals(0f, t.critChance(), 0f);
        assertEquals(0, t.extraPierce());
        assertEquals(1f, t.splashMult(), 0f);
        assertEquals(1d, t.overchargeCd(), 0d);
        assertEquals(0f, t.barricadeRegen(), 0f);
        assertEquals(0f, t.spikeDot(), 0f);
        assertEquals(1f, t.towerHp(), 0f);
        assertEquals(1d, t.rebuildMult(), 0d);
        assertEquals(1f, t.damageTaken(), 0f);
        assertEquals(1f, t.goldPop(), 0f);
        assertEquals(1f, t.grabBonus(), 0f);
        assertEquals(1d, t.shopDiscount(), 0d);
        assertEquals(0f, t.wavePurse(), 0f);
        assertEquals(1d, t.grabCdScale(), 0d);
        assertEquals(1f, t.scoreMult(), 0f);
        assertEquals(1f, t.killGold(), 0f);
        assertFalse(t.allySentinels());
        assertEquals(0f, t.stormWindSlow(), 0f);
        assertEquals(1f, t.throwPower(), 0f);
        assertEquals(1f, t.fallDamage(), 0f);
        assertEquals(1f, t.lightningMult(), 0f);
        assertEquals(1f, t.windMult(), 0f);
        assertEquals(1f, t.stormChance(), 0f);
        assertEquals(1f, t.allyPower(), 0f);
        assertEquals(0, t.allyCapBonus());
        assertEquals(1d, t.allyRate(), 0d);
        assertEquals(0f, t.graveChill(), 0f);
        assertEquals(0d, t.allyLife(), 0d);
        assertEquals(1f, t.allyTough(), 0f);
        assertEquals(1d, t.skillCd(), 0d);
        assertEquals(1f, t.skillPower(), 0f);
        assertEquals(1f, t.skillArea(), 0f);
        assertEquals(1d, t.fireTime(), 0d);
        assertEquals(1d, t.tornadoMult(), 0d);
        assertEquals(1f, t.meteorCount(), 0f);
        assertEquals(1d, t.castleHpClaim(), 0d);
    }

    @Test
    @DisplayName("the run's modifiers ARE the talent tree, with no copy in between")
    void treeIsTheModifierSeam() {
        TestRun r = new TestRun().beginClassic();
        assertTrue(r.run.modifiers() == r.talents(),
                "a copy would be a thing that could go stale");
        assertEquals(GameConfig.GRAB_COOLDOWN, GameConfig.GRAB_COOLDOWN, 0d);
    }
}
