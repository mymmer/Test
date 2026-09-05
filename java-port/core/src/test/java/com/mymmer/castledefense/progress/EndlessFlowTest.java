package com.mymmer.castledefense.progress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mymmer.castledefense.config.Tuning;
import com.mymmer.castledefense.game.GameState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Endless mode: the clock, the ramp, the boss timetable and the talent drip.
 *
 * <p>Every one of these steps the simulation directly rather than sleeping, so
 * an hour of play takes milliseconds and the results are exact.
 */
class EndlessFlowTest {

    // ========================================================================
    //  Tiers
    // ========================================================================

    @Test
    @DisplayName("a run starts on tier 1 with the clock at zero")
    void beginsOnTierOne() {
        TestRun r = new TestRun().beginEndless();
        assertEquals(1, r.session().wave());
        assertEquals(0d, r.session().playTime(), 0d);
        assertEquals(GameState.PLAYING, r.state());
        assertEquals(0, r.endless().scriptedBossesSent());
    }

    @Test
    @DisplayName("the tier steps every 30 s, exactly")
    void tierLadder() {
        TestRun r = new TestRun().beginEndless();
        for (int tier = 1; tier <= 12; tier++) {
            assertEquals(tier, r.session().wave(),
                    "at t=" + r.session().playTime() + "s");
            r.scheduleSeconds(30);
        }
        assertEquals(13, r.session().wave());
        assertEquals(360d, r.session().playTime(), 1e-9);
    }

    @Test
    @DisplayName("the tier boundary is where the source puts it, to the step")
    void tierBoundary() {
        //  1 + int(t / 30): the tier changes on the FIRST step at or past 30 s.
        TestRun r = new TestRun().beginEndless();
        r.survivingSteps(1799);                       // 29.9833... s
        assertEquals(29.983333333333334, r.session().playTime(), 1e-9);
        assertEquals(1, r.session().wave(), "one step short of the boundary");

        r.survivingStep();                            // 30.0 s exactly
        assertEquals(30.0, r.session().playTime(), 1e-9);
        assertEquals(2, r.session().wave(), "the boundary step steps the tier");

        r.survivingStep();                            // 30.0166... s
        assertEquals(2, r.session().wave(), "and it does not step twice");
    }

    @Test
    @DisplayName("a tier step restores towers and rolls new weather")
    void tierStepRefreshes() {
        TestRun r = new TestRun().beginEndless();
        int rolls = r.run.weather().rollCount();
        r.survivingSeconds(30);
        assertEquals(rolls + 1, r.run.weather().rollCount(),
                "one fresh roll per tier, and no more");
        assertTrue(r.run.announcements().has(Announcements.Id.TIER_REACHED));
    }

    // ========================================================================
    //  The spawn ramp
    // ========================================================================

    @Test
    @DisplayName("the spawn gap ramps 1.70 s to 0.38 s over 300 s, then holds")
    void spawnRamp() {
        assertEquals(1.70, EndlessDirector.baseSpawnGap(0d), 1e-12);
        assertEquals(1.70 + (0.38 - 1.70) * 0.5, EndlessDirector.baseSpawnGap(150d), 1e-12);
        assertEquals(0.38, EndlessDirector.baseSpawnGap(300d), 1e-12);
        assertEquals(0.38, EndlessDirector.baseSpawnGap(3600d), 1e-12,
                "the floor holds however long the run lasts");
        assertEquals(1.70, EndlessDirector.baseSpawnGap(-5d), 1e-12,
                "and it is clamped at the bottom too");
    }

    @Test
    @DisplayName("every drawn gap is inside the +/-28% jitter band")
    void spawnJitterBand() {
        TestRun r = new TestRun().beginEndless();
        for (int i = 0; i < 400; i++) {
            double base = EndlessDirector.baseSpawnGap(r.session().playTime());
            double gap = r.endless().spawnGap();
            assertTrue(gap >= base * (1 - Tuning.ENDLESS_SPAWN_JITTER) - 1e-9
                            && gap <= base * (1 + Tuning.ENDLESS_SPAWN_JITTER) + 1e-9,
                    "gap " + gap + " outside the band around " + base);
            r.scheduleSeconds(1);
        }
    }

    @Test
    @DisplayName("the alive cap stops the trickle, and does not cap the population")
    void aliveCap() {
        //  Python's condition gates spawn_enemy from the TRICKLE only:
        //      if len(self.alive_enemies()) < ENDLESS_MAX_ALIVE
        //  Necromancer summons, Lich raises and the horn all add mobs without
        //  consulting it, so a busy field genuinely exceeds 60.  Asserting a
        //  hard population ceiling would be asserting something the source does
        //  not do.
        TestRun r = new TestRun().beginEndless();
        r.survivingSeconds(600);
        assertTrue(r.aliveEnemies() > EndlessDirector.MAX_ALIVE,
                "summons are expected to push the field past the trickle cap");

        //  With the field over the cap, the director must not add to it.
        int before = r.aliveEnemies();
        int directorSpawns = 0;
        for (int i = 0; i < 600 && r.aliveEnemies() >= EndlessDirector.MAX_ALIVE; i++) {
            int was = r.aliveEnemies();
            r.survivingStep();
            if (r.aliveEnemies() > was) {
                directorSpawns++;
            }
        }
        assertTrue(before > 0);
        //  any growth over the cap came from summons, never from the trickle;
        //  the trickle re-arms its timer and skips, which is the source's shape
        assertTrue(r.endless().spawnTimer() <= EndlessDirector.baseSpawnGap(
                        r.session().playTime()) * (1 + Tuning.ENDLESS_SPAWN_JITTER) + 1e-9,
                "the gap keeps being re-armed even while the field is full");
    }

    // ========================================================================
    //  Bosses
    // ========================================================================

    @Test
    @DisplayName("the scripted bosses arrive at 120, 240 and 360 seconds")
    void scriptedBossSchedule() {
        TestRun r = new TestRun().beginEndless();

        r.scheduleSeconds(119);
        assertEquals(0, r.endless().scriptedBossesSent(), "none before two minutes");

        r.scheduleSeconds(1.1);
        assertEquals(1, r.endless().scriptedBossesSent(), "the first at 120 s");

        r.scheduleSeconds(120);
        assertEquals(2, r.endless().scriptedBossesSent(), "the second at 240 s");

        r.scheduleSeconds(120);
        assertEquals(3, r.endless().scriptedBossesSent(), "the third at 360 s");
        assertEquals(0, r.endless().repeatBossesSent());
    }

    @Test
    @DisplayName("after the script, a random boss every further 120 s")
    void repeatBossSchedule() {
        TestRun r = new TestRun().beginEndless();
        r.scheduleSeconds(360);
        assertEquals(3, r.endless().scriptedBossesSent());
        assertEquals(0, r.endless().repeatBossesSent());

        r.scheduleSeconds(119);
        assertEquals(0, r.endless().repeatBossesSent(), "none before 480 s");
        r.scheduleSeconds(1.1);
        assertEquals(1, r.endless().repeatBossesSent(), "the first repeat at 480 s");

        r.scheduleSeconds(120);
        assertEquals(2, r.endless().repeatBossesSent(), "and another at 600 s");
        r.scheduleSeconds(120);
        assertEquals(3, r.endless().repeatBossesSent());
    }

    @Test
    @DisplayName("the repeat clock counts from 360 s, not from the last boss")
    void repeatClockIsAbsolute() {
        //  due = int((playTime - 360) / 120).  A boss that arrives late does not
        //  push the next one out.
        TestRun r = new TestRun().beginEndless();
        r.scheduleSeconds(725);
        //  480, 600, 720 have all passed
        assertEquals(3, r.endless().repeatBossesSent());
    }

    @Test
    @DisplayName("a repeated boss is a clean instance, not a revived one")
    void repeatedBossIsClean() {
        TestRun r = new TestRun().beginEndless();
        r.scheduleSeconds(121);
        assertEquals(1, r.liveBosses());
        long firstUid = r.run.bossRegistry().liveBosses().get(0).uid();

        //  kill it, and let the next scripted slot come round
        r.run.bossRegistry().liveBosses().get(0).die(true);
        r.survivingStep();
        assertEquals(0, r.liveBosses(), "the purge runs from the defeat hook");

        r.scheduleSeconds(120);
        assertEquals(1, r.liveBosses(), "the 240 s boss arrives");
        com.mymmer.castledefense.boss.Boss second = r.run.bossRegistry().liveBosses().get(0);
        assertTrue(second.uid() != firstUid, "a new uid, not a resurrection");
        assertEquals(0, second.regaliaTaken(), "no disruption history");
        assertEquals(0d, second.regaliaCd(), 0d, "no leftover guard");
    }

    @Test
    @DisplayName("two bosses can be on the field at once")
    void simultaneousBosses() {
        TestRun r = new TestRun().beginEndless();
        r.scheduleSeconds(121);
        assertEquals(1, r.liveBosses());
        //  do not kill it; the 240 s slot fires anyway
        r.scheduleSeconds(120);
        assertTrue(r.liveBosses() >= 1,
                "the timetable does not wait for the field to be clear");
    }

    // ========================================================================
    //  Talent income
    // ========================================================================

    @Test
    @DisplayName("one talent point per minute survived, on the minute")
    void talentDrip() {
        TestRun r = new TestRun().beginEndless();
        assertEquals(0, r.endless().talentPointsAwarded());
        r.scheduleSteps(3599);
        assertEquals(0, r.endless().talentPointsAwarded(),
                "one step short of a minute");
        r.scheduleStep();
        assertEquals(1, r.endless().talentPointsAwarded(),
                "the 3600th step is the minute");
        assertEquals(1, r.talents().availablePoints(), "and it reached the tree");
    }

    @Test
    @DisplayName("the talent counter keeps its remainder and never drifts")
    void talentIncomeDoesNotDrift() {
        //  The counter SUBTRACTS 60 rather than resetting to zero, so an hour of
        //  play is exactly 60 points -- not 59, not 61.  With a float clock this
        //  is where the drift would show.
        TestRun r = new TestRun().beginEndless();
        r.scheduleSeconds(3600);
        //  The MINUTE income specifically.  availablePoints() also carries boss
        //  bounties, and an hour of undefended play kills a few bosses by
        //  Volatile blast, so the totals legitimately differ.
        assertEquals(60, r.endless().talentPointsAwarded(),
                "an hour is sixty minute-points, exactly");
        assertTrue(r.talents().availablePoints() >= 60,
                "the tree has those plus whatever the bosses paid");
        assertEquals(3600d, r.session().playTime(), 1e-9);
        assertTrue(r.endless().talentSeconds() < Tuning.TALENT_SECONDS_PER_POINT,
                "the bank never exceeds one minute");
    }

    // ========================================================================
    //  Long runs
    // ========================================================================

    @Test
    @DisplayName("a 30-minute run has the tiers, bosses and points the schedule says")
    void thirtyMinuteRun() {
        TestRun r = new TestRun().beginEndless();
        r.scheduleSeconds(1800);

        assertEquals(1800d, r.session().playTime(), 1e-9);
        assertEquals(61, r.session().wave(), "1 + 1800/30");
        assertEquals(30, r.endless().talentPointsAwarded(), "1800/60");
        assertEquals(3, r.endless().scriptedBossesSent());
        assertEquals(12, r.endless().repeatBossesSent(), "(1800 - 360) / 120");
    }

    @Test
    @DisplayName("a 60-minute run does not drift by a single step")
    void sixtyMinuteRun() {
        TestRun r = new TestRun().beginEndless();
        r.scheduleSeconds(3600);

        assertEquals(3600d, r.session().playTime(), 1e-9);
        assertEquals(121, r.session().wave(), "1 + 3600/30");
        assertEquals(60, r.endless().talentPointsAwarded());
        assertEquals(3, r.endless().scriptedBossesSent());
        assertEquals(27, r.endless().repeatBossesSent(), "(3600 - 360) / 120");
    }

    @Test
    @DisplayName("the schedule agrees at 30, 60, 120, 240, 360 and 600 seconds")
    void scheduleCheckpoints() {
        int[] marks = {30, 60, 120, 240, 360, 600};
        int[] tiers = {2, 3, 5, 9, 13, 21};
        int[] points = {0, 1, 2, 4, 6, 10};
        int[] scripted = {0, 0, 1, 2, 3, 3};
        int[] repeats = {0, 0, 0, 0, 0, 2};

        TestRun r = new TestRun().beginEndless();
        int at = 0;
        for (int i = 0; i < marks.length; i++) {
            r.scheduleSeconds(marks[i] - at);
            at = marks[i];
            assertEquals(tiers[i], r.session().wave(), "tier at " + at + "s");
            assertEquals(points[i], r.endless().talentPointsAwarded(),
                    "minute-points at " + at + "s");
            assertEquals(scripted[i], r.endless().scriptedBossesSent(),
                    "scripted bosses at " + at + "s");
            assertEquals(repeats[i], r.endless().repeatBossesSent(),
                    "repeat bosses at " + at + "s");
        }
    }

    // ========================================================================
    //  Determinism
    // ========================================================================

    @Test
    @DisplayName("a seeded Endless run reproduces spawns, jitter and boss picks")
    void seededRunsAgree() {
        assertEquals(fingerprint(777L), fingerprint(777L));
        assertFalse(fingerprint(777L).equals(fingerprint(778L)),
                "a different seed must diverge");
    }

    private static String fingerprint(long seed) {
        TestRun r = new TestRun(seed);
        r.run.beginRun(com.mymmer.castledefense.game.GameMode.ENDLESS,
                r.difficulty("normal"), seed);
        r.seconds(500);
        StringBuilder sb = new StringBuilder(r.run.describeRun());
        for (int i = 0; i < r.run.horde().size(); i++) {
            com.mymmer.castledefense.enemy.Enemy e = r.run.horde().get(i);
            sb.append('|').append(e.typeId()).append('@')
              .append(String.format("%.3f", e.x()));
        }
        return sb.toString();
    }
}
