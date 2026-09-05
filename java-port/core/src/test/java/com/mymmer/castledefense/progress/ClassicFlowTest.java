package com.mymmer.castledefense.progress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mymmer.castledefense.config.GameConfig;
import com.mymmer.castledefense.debug.TraceEvent;
import com.mymmer.castledefense.game.GameState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Classic mode: the wave loop, its boundaries and its payouts. */
class ClassicFlowTest {

    // ========================================================================
    //  Starting a wave
    // ========================================================================

    @Test
    @DisplayName("a run starts on wave 1 with a composed queue and full towers")
    void firstWave() {
        TestRun r = new TestRun().beginClassic();
        assertEquals(1, r.session().wave());
        assertEquals(GameState.PLAYING, r.state());
        assertTrue(r.waves().pendingSpawns() > 0, "wave 1 must have a queue");
        assertTrue(r.waves().waveActive());
        assertEquals(GameConfig.STARTING_GOLD, r.session().gold());
    }

    @Test
    @DisplayName("the first mob arrives after 0.8 s, not immediately")
    void firstSpawnDelay() {
        TestRun r = new TestRun().beginClassic();
        assertEquals(0, r.aliveEnemies());
        r.seconds(0.75);
        assertEquals(0, r.aliveEnemies(), "nothing before 0.8 s");
        r.seconds(0.1);
        assertTrue(r.aliveEnemies() >= 1, "the vanguard arrives just after 0.8 s");
    }

    @Test
    @DisplayName("the spawn interval tightens with the wave, to a floor of 0.32 s")
    void spawnIntervalCurve() {
        //  main.py: max(0.32, 1.25 - wave * 0.032)
        TestRun r = new TestRun().beginClassic();
        assertEquals(1.25 - 0.032, r.waves().spawnInterval(), 1e-12);
        for (int w = 2; w <= 30; w++) {
            r.waves().startWave();
            assertEquals(Math.max(0.32, 1.25 - w * 0.032), r.waves().spawnInterval(), 1e-12,
                    "wave " + w);
        }
        for (int w = 31; w <= 40; w++) {
            r.waves().startWave();
            assertEquals(0.32, r.waves().spawnInterval(), 1e-12, "floored at wave " + w);
        }
    }

    @Test
    @DisplayName("starting a wave restores every downed tower")
    void towersRestored() {
        TestRun r = new TestRun().beginClassic();
        com.mymmer.castledefense.defence.DefenceTower t = r.run.castle()
                .addTower(com.mymmer.castledefense.defence.TowerType.BOWMAN);
        assertNotNull(t);
        //  a tower takes at most 42% of its maximum in one hit, so knocking one
        //  down takes several -- that cap is a Phase 5 contract, not an
        //  accident, and this test must not pretend otherwise
        for (int i = 0; i < 6 && !t.disabled(); i++) {
            t.takeDamage(t.maxHp());
        }
        assertTrue(t.disabled(), "the tower should be down");

        r.waves().startWave();
        assertFalse(t.disabled(), "a new wave puts the guns back up");
    }

    // ========================================================================
    //  Clearing a wave
    // ========================================================================

    @Test
    @DisplayName("a wave ends 1.1 s after the last mob dies, not before")
    void waveClearDelay() {
        TestRun r = new TestRun().beginClassic();
        clearTheField(r);

        //  clearTheField ends on a step during which the field was already
        //  clear, so one step of delay is banked.  Count from there.
        int banked = (int) Math.round(r.waves().waveClearDelay() / TestRun.DT);
        assertEquals(1, banked, "one step of delay should already be banked");

        int more = 0;
        while (r.state() == GameState.PLAYING && more < 400) {
            r.step();
            more++;
        }
        //  The source compares STRICTLY: `if wave_clear_delay > 1.1`.  66
        //  sixtieths is 1.1000000000000010, the first value above 1.1, so the
        //  66th clear step is the one that ends the wave -- not the 65th, and
        //  not the 67th.
        assertEquals(66, banked + more, "the 66th clear step ends the wave");
        assertTrue(r.waves().consumeWaveCleared());
    }

    @Test
    @DisplayName("a mob raised during the delay resets it to zero")
    void raisingResetsTheDelay() {
        TestRun r = new TestRun().beginClassic();
        clearTheField(r);
        r.seconds(0.9);
        assertTrue(r.waves().waveClearDelay() > 0.85, "the delay is running");

        //  something arrives: a Necromancer summon, a Lich raise, a horn
        r.run.spawnEnemy(com.mymmer.castledefense.enemy.EnemyType.SCOUT, 1);
        r.step();
        assertEquals(0d, r.waves().waveClearDelay(), 0d, "the delay restarts from zero");
        assertTrue(r.waves().waveActive(), "and the wave is still running");
    }

    @Test
    @DisplayName("a FriendlySkeleton does not hold a wave open")
    void alliesDoNotBlockWaveClear() {
        //  The documented quirk.  An ally is not an Enemy and is not in the
        //  horde, so `alive_enemies()` never sees it.
        TestRun r = new TestRun().beginClassic();
        clearTheField(r);
        assertTrue(r.run.spawnAlly(900f, 560f));
        assertEquals(1, r.run.allyCount());

        r.seconds(1.5);
        assertEquals(GameState.SHOP, r.state(),
                "allies on the field must not deadlock the wave");
        assertEquals(1, r.run.allyCount(), "and the ally is still standing");
    }

    @Test
    @DisplayName("a mob marked dead but not yet swept does not hold a wave open")
    void deadButUnsweptDoesNotBlock() {
        TestRun r = new TestRun().beginClassic();
        r.seconds(2.0);
        assertTrue(r.aliveEnemies() > 0);
        r.waves();
        //  kill everything without stepping: the corpses are still in the list
        r.killEveryEnemy();
        r.run.director();
        assertTrue(r.run.horde().size() > 0, "the corpses are still in the roster");
        assertEquals(0, r.aliveEnemies(), "but none of them is alive");

        //  drain the queue too, then let the delay run
        while (r.waves().pendingSpawns() > 0) {
            r.seconds(2.0);
            r.killEveryEnemy();
        }
        r.seconds(1.5);
        assertEquals(GameState.SHOP, r.state());
    }

    @Test
    @DisplayName("clearing a wave pays 80 + wave * 22 and one talent point")
    void waveBonus() {
        TestRun r = new TestRun().beginClassic();
        int before = r.session().gold();
        int points = r.talents().availablePoints();
        clearTheField(r);
        r.seconds(1.5);

        assertEquals(GameState.SHOP, r.state());
        assertEquals(80 + 1 * 22, r.waves().lastWaveBonus());
        assertEquals(before + 80 + 22, r.session().gold());
        assertEquals(points + 1, r.talents().availablePoints(),
                "one point per wave cleared");
    }

    @Test
    @DisplayName("the shop hands back to wave 2, and the wave number keeps climbing")
    void waveSequence() {
        TestRun r = new TestRun().beginClassic();
        for (int expected = 1; expected <= 4; expected++) {
            assertEquals(expected, r.session().wave());
            clearTheField(r);
            r.seconds(1.5);
            assertEquals(GameState.SHOP, r.state());
            assertTrue(r.run.startNextWave());
            assertEquals(GameState.PLAYING, r.state());
        }
        assertEquals(5, r.session().wave());
    }

    @Test
    @DisplayName("ending a wave clears projectiles and dropped items")
    void endWaveClearsTheField() {
        TestRun r = new TestRun().beginClassic();
        r.run.addProjectile(com.mymmer.castledefense.defence.Projectile.friendly(
                r.run, 400f, 300f, 100f, 0f,
                com.mymmer.castledefense.defence.ProjectileKind.ARROW, 10f));
        assertEquals(1, r.run.projectiles().size());
        clearTheField(r);
        r.seconds(1.5);
        assertEquals(0, r.run.projectiles().size(), "no shot survives into the shop");
        assertEquals(0, r.run.droppedItems().size());
    }

    // ========================================================================
    //  Bosses in the queue
    // ========================================================================

    @Test
    @DisplayName("wave 5 fields a boss, and it arrives from the composed queue")
    void bossAtWaveFive() {
        TestRun r = new TestRun().beginClassic();
        for (int w = 1; w < 5; w++) {
            clearTheField(r);
            r.seconds(1.5);
            r.run.startNextWave();
        }
        assertEquals(5, r.session().wave());
        assertNotNull(r.run.composition().bossForWave(5));

        int used = r.stepsUntil(60 * 90, () -> r.liveBosses() > 0);
        assertTrue(used > 0, "a boss should walk in during wave 5");
        assertEquals(1, r.liveBosses());
    }

    @Test
    @DisplayName("a boss buys a breather: the next spawn is 2.4x the interval")
    void bossSlowsTheQueue() {
        TestRun r = new TestRun().beginClassic();
        for (int w = 1; w < 5; w++) {
            clearTheField(r);
            r.seconds(1.5);
            r.run.startNextWave();
        }
        r.stepsUntil(60 * 90, () -> r.liveBosses() > 0);
        //  the timer was re-armed on the boss's own spawn step
        assertTrue(r.waves().spawnTimer() > r.waves().spawnInterval() * 1.3,
                "after a boss the queue pauses noticeably longer than a jittered gap");
    }

    @Test
    @DisplayName("a run is reproducible from its seed")
    void seededRunsAgree() {
        assertEquals(describeAfter(4242L), describeAfter(4242L));
        assertFalse(describeAfter(4242L).equals(describeAfter(99L)),
                "a different seed must diverge");
    }

    private static String describeAfter(long seed) {
        TestRun r = new TestRun(seed);
        r.run.beginRun(com.mymmer.castledefense.game.GameMode.CLASSIC,
                r.difficulty("normal"), seed);
        r.seconds(20);
        return r.run.describeRun();
    }

    // ========================================================================
    //  Trace
    // ========================================================================

    @Test
    @DisplayName("the wave lifecycle is traced, and tracing changes nothing")
    void traceIsObservationOnly() {
        TestRun quiet = new TestRun().beginClassic();
        clearTheField(quiet);
        quiet.seconds(1.5);
        String withoutTrace = quiet.run.describeRun();

        TestRun loud = new TestRun();
        loud.recording();
        loud.beginClassic();
        clearTheField(loud);
        loud.seconds(1.5);

        assertEquals(withoutTrace, loud.run.describeRun(),
                "a trace must not change a single outcome");
        assertTrue(loud.trace.countOf(TraceEvent.WAVE_START) >= 1);
        assertEquals(1, loud.trace.countOf(TraceEvent.WAVE_END));
    }

    // ------------------------------------------------------------------------

    /** Empties the queue and the field, leaving the wave-clear delay at zero. */
    private static void clearTheField(TestRun r) {
        for (int guard = 0; guard < 400 && r.waves().pendingSpawns() > 0; guard++) {
            r.seconds(1.0);
            r.killEveryEnemy();
        }
        r.killEveryEnemy();
        r.step();
        assertEquals(0, r.waves().pendingSpawns(), "the queue should be drained");
        assertEquals(0, r.aliveEnemies(), "the field should be clear");
    }

    @Test
    @DisplayName("the Classic alive cap is 58, and it is not the Endless one")
    void aliveCapsDiffer() {
        assertEquals(58, WaveDirector.MAX_ALIVE);
        assertEquals(60, EndlessDirector.MAX_ALIVE);
        assertNotEquals(WaveDirector.MAX_ALIVE, EndlessDirector.MAX_ALIVE);
    }

    private static void assertNotEquals(int a, int b) {
        assertTrue(a != b, "expected different values, both were " + a);
    }

    @Test
    @DisplayName("the director the mode asks for is the one that runs")
    void directorPerMode() {
        assertSame(WaveDirector.class, new TestRun().beginClassic().run.director().getClass());
        assertSame(EndlessDirector.class,
                new TestRun().beginEndless().run.director().getClass());
    }
}
