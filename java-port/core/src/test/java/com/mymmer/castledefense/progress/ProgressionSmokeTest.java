package com.mymmer.castledefense.progress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mymmer.castledefense.defence.TowerType;
import com.mymmer.castledefense.game.GameMode;
import com.mymmer.castledefense.game.GameState;
import com.mymmer.castledefense.skill.SkillId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Seeded end-to-end smokes: a run played the way a player plays it.
 *
 * <p>The unit tests prove each subsystem; these prove they work <em>together</em>
 * on one seed, from a fresh run to a defended castle with talents, purchases and
 * a skill in hand. They are deterministic and take milliseconds.
 */
class ProgressionSmokeTest {

    private static final long SEED = 20260905L;

    @Test
    @DisplayName("a seeded Classic run: buy, fight, clear, earn, spend, repeat")
    void classicProgressionSmoke() {
        TestRun r = new TestRun(SEED);
        r.run.beginRun(GameMode.CLASSIC, r.difficulty("normal"), SEED);

        //  wave 1: spend the starting purse on a Bowman
        assertEquals(220, r.session().gold());
        assertTrue(r.shop().buy("bowman").ok(), "a Bowman for the wall");
        assertEquals(1, r.run.castle().countOf(TowerType.BOWMAN));

        int wavesCleared = 0;
        for (int wave = 1; wave <= 4; wave++) {
            //  play the wave out; the tower does the work, the keep is topped up
            //  so the smoke is about progression rather than survival
            for (int guard = 0; guard < 600 && r.state() == GameState.PLAYING; guard++) {
                r.survivingSeconds(0.5);
                if (r.waves().pendingSpawns() == 0 && r.aliveEnemies() > 0
                        && guard > 120) {
                    r.killEveryEnemy();          // finish the stragglers
                }
            }
            assertEquals(GameState.SHOP, r.state(), "wave " + wave + " should clear");
            wavesCleared++;

            //  the wave paid a talent point and a purse
            assertEquals(wavesCleared, r.talents().earnedPoints(),
                    "one talent point per wave cleared");

            //  spend it, and buy whatever is affordable
            assertTrue(r.talents().purchase("rate") || r.talents().purchase("power"),
                    "a point should be spendable in offense");
            r.shop().buy("bowman");

            assertTrue(r.run.startNextWave());
        }

        assertEquals(4, wavesCleared);
        assertEquals(5, r.session().wave());
        assertEquals(4, r.talents().earnedPoints());
        assertEquals(0, r.talents().availablePoints(), "all four were spent");
        assertTrue(r.talents().branchPoints(
                com.mymmer.castledefense.talent.TalentBranch.OFFENSE) == 4);
        assertTrue(r.run.modifiers().towerRate() < 1d
                        || r.run.modifiers().towerDamage() > 1f,
                "and the purchases changed a real modifier");
    }

    @Test
    @DisplayName("a seeded Endless run: survive, tier up, kill a boss, unlock a skill")
    void endlessProgressionSmoke() {
        TestRun r = new TestRun(SEED);
        r.run.beginRun(GameMode.ENDLESS, r.difficulty("normal"), SEED);

        //  two minutes: four tier steps, two talent points, one boss
        r.scheduleSeconds(121);

        assertEquals(5, r.session().wave(), "1 + 120/30");
        assertEquals(2, r.endless().talentPointsAwarded(), "one point a minute");
        assertEquals(1, r.endless().scriptedBossesSent(), "the Troll King at 120 s");
        assertEquals(1, r.liveBosses());

        //  kill it: the first slot lights up and pays a bounty
        int pointsBefore = r.talents().availablePoints();
        r.run.bossRegistry().liveBosses().get(0).die(true);
        r.step();

        assertEquals(1, r.skills().unlockedCount());
        assertTrue(r.skills().isUnlocked(SkillId.LIGHTNING));
        assertEquals(pointsBefore + 2, r.talents().availablePoints(),
                "a boss with a slot left pays two");

        //  cast it, and it recharges on the canonical clock
        assertTrue(r.skills().castAt(SkillId.LIGHTNING, 900f, 500f));
        assertTrue(r.skills().cooldownRemaining(SkillId.LIGHTNING) > 0d);
        r.scheduleSeconds(com.mymmer.castledefense.config.Tuning.LIGHTNING_COOLDOWN);
        assertTrue(r.skills().isReady(SkillId.LIGHTNING), "and it comes back");

        //  spend the bounty
        assertTrue(r.talents().purchase("focus"), "arcane is open from the start");
        assertTrue(r.run.modifiers().skillCd() < 1d, "and the next cooldown is shorter");
    }

    @Test
    @DisplayName("both smokes are reproducible from their seed")
    void smokesAreDeterministic() {
        assertEquals(endlessFingerprint(SEED), endlessFingerprint(SEED));
        assertTrue(!endlessFingerprint(SEED).equals(endlessFingerprint(SEED + 1)),
                "a different seed diverges");
    }

    private static String endlessFingerprint(long seed) {
        TestRun r = new TestRun(seed);
        r.run.beginRun(GameMode.ENDLESS, r.difficulty("hard"), seed);
        r.grantPoints(12);
        r.buyTalent("focus", 3);
        r.buyTalentDeep("widecast", 2);
        r.skills().unlockNext();
        r.skills().unlockNext();
        r.skills().castAt(SkillId.METEOR, 850f, 400f);
        r.scheduleSeconds(90);
        return r.run.describeRun();
    }

    @Test
    @DisplayName("the run description carries the Phase 9 systems for a bug report")
    void diagnosticsCarryProgression() {
        TestRun r = new TestRun(SEED);
        r.run.beginRun(GameMode.ENDLESS, r.difficulty("normal"), SEED);
        r.grantPoints(3).buyTalent("rate", 2);
        r.skills().unlockNext();
        r.scheduleSeconds(5);

        String d = r.run.describeRun();
        assertNotNull(d);
        for (String needed : new String[]{"talents ", "rate:2", "skills ", "lightning",
            "fire=", "tornados="}) {
            assertTrue(d.contains(needed),
                    "the description should carry " + needed + "\n  got: " + d);
        }
    }
}
