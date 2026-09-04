package com.mymmer.castledefense.progress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mymmer.castledefense.enemy.Enemy;
import com.mymmer.castledefense.enemy.EnemyType;
import com.mymmer.castledefense.game.GameState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What advances in which state, proved one state at a time.
 *
 * <p>The Endless realtime shop is the one that matters most: the Python
 * self-test asserts that opening it stops {@code play_time}, stops spawning and
 * stops every enemy where it stands. It is a freeze, not a menu drawn over a
 * running game.
 */
class RunStateTest {

    // ========================================================================
    //  The freeze table
    // ========================================================================

    @Test
    @DisplayName("only PLAYING advances the world; every other state freezes it")
    void onlyPlayingAdvancesTheWorld() {
        for (GameState state : GameState.values()) {
            TestRun r = new TestRun().beginEndless();
            r.seconds(3);                       // get some mobs walking

            double timeBefore = r.session().playTime();
            long gameplayBefore = r.world.gameplayStepCount();
            float[] xs = enemyXs(r);

            r.world.setState(state);
            r.steps(120);                       // two seconds of frames

            if (state == GameState.PLAYING) {
                assertTrue(r.session().playTime() > timeBefore, "PLAYING must advance");
                continue;
            }
            assertEquals(timeBefore, r.session().playTime(), 0d,
                    state + " must not advance the run clock");
            assertEquals(gameplayBefore, r.world.gameplayStepCount(),
                    state + " must not advance the gameplay clock");
            assertArrayEquals(xs, enemyXs(r),
                    state + " must not move a single enemy");
        }
    }

    @Test
    @DisplayName("simulation time advances in every state, gameplay time does not")
    void simulationTimeAlwaysRuns() {
        //  Python increments Game.time before its state check, so it keeps
        //  running in menus; fling airtime and input timestamps depend on it.
        TestRun r = new TestRun().beginEndless();
        r.world.setState(GameState.PAUSED);
        double sim = r.world.simulationTime();
        long gameplay = r.world.gameplayStepCount();

        r.steps(60);

        assertTrue(r.world.simulationTime() > sim, "the simulation clock never stops");
        assertEquals(gameplay, r.world.gameplayStepCount(), "the gameplay clock does");
    }

    @Test
    @DisplayName("the shake, the flashes and the banners age in every state")
    void alwaysTierRunsEverywhere() {
        for (GameState state : GameState.values()) {
            TestRun r = new TestRun().beginEndless();
            r.run.screenShake().add(ScreenShake.MAX);
            r.session().addScore(10, 2, 2f);            // lights the combo flash
            r.run.announcements().post(Announcements.Id.WAVE_START, 1, 5.0);

            float shake = r.run.screenShake().amount();
            float flash = r.session().comboFlash();
            double banner = r.run.announcements().latest(Announcements.Id.WAVE_START)
                    .remaining();

            r.world.setState(state);
            r.steps(30);

            assertTrue(r.run.screenShake().amount() < shake, state + ": shake decays");
            assertTrue(r.session().comboFlash() < flash, state + ": the flash fades");
            assertTrue(r.run.announcements().latest(Announcements.Id.WAVE_START)
                    .remaining() < banner, state + ": banners count down");
        }
    }

    // ========================================================================
    //  The Endless realtime shop
    // ========================================================================

    @Test
    @DisplayName("the Endless shop freezes the clock, the spawning and every mob")
    void realtimeShopIsATrueFreeze() {
        TestRun r = new TestRun().beginEndless();
        r.seconds(20);
        assertTrue(r.aliveEnemies() > 0, "there should be a horde to freeze");

        double time = r.session().playTime();
        int alive = r.aliveEnemies();
        int tier = r.session().wave();
        double spawnTimer = r.endless().spawnTimer();
        float[] xs = enemyXs(r);

        assertTrue(r.run.openRealtimeShop());
        assertEquals(GameState.SHOP, r.state());

        r.steps(120);                           // the Python self-test's 120 frames

        assertEquals(time, r.session().playTime(), 0d, "play time is frozen");
        assertEquals(alive, r.aliveEnemies(), "the enemy count is frozen");
        assertEquals(tier, r.session().wave(), "the tier ladder is frozen");
        assertEquals(spawnTimer, r.endless().spawnTimer(), 0d, "so is the spawn timer");
        assertArrayEquals(xs, enemyXs(r), "and not one of them has moved");
    }

    @Test
    @DisplayName("resuming from the shop starts everything again where it stopped")
    void resumeContinues() {
        TestRun r = new TestRun().beginEndless();
        r.seconds(20);
        double time = r.session().playTime();

        r.run.openRealtimeShop();
        r.steps(120);
        assertTrue(r.run.resumeFromShop());
        assertEquals(GameState.PLAYING, r.state());

        r.steps(60);
        assertEquals(time + 1.0, r.session().playTime(), 1e-9,
                "exactly one more second of run, and none of the shopping");
    }

    @Test
    @DisplayName("the shop drops whatever the cursor was holding")
    void shopReleasesTheCursor() {
        TestRun r = new TestRun().beginEndless();
        r.seconds(3);
        assertTrue(grabSomething(r), "something is held");

        r.run.openRealtimeShop();
        assertFalse(r.run.cursor().busy(),
                "a mob must not hang in the air while the player shops");
    }

    @Test
    @DisplayName("Classic has no realtime shop")
    void classicHasNoRealtimeShop() {
        TestRun r = new TestRun().beginClassic();
        assertFalse(r.run.openRealtimeShop(),
                "the mid-fight shop is an Endless mechanic");
        assertEquals(GameState.PLAYING, r.state());
    }

    @Test
    @DisplayName("the shop cannot be opened from a state that is not PLAYING")
    void shopOnlyFromPlaying() {
        TestRun r = new TestRun().beginEndless();
        for (GameState s : GameState.values()) {
            if (s == GameState.PLAYING) {
                continue;
            }
            r.world.setState(s);
            assertFalse(r.run.openRealtimeShop(), "must refuse from " + s);
        }
    }

    // ========================================================================
    //  Game over
    // ========================================================================

    @Test
    @DisplayName("a fallen castle ends the run and stops the world")
    void gameOverStopsEverything() {
        TestRun r = new TestRun().beginEndless();
        r.seconds(5);
        r.run.castle().takeDamage(r.run.castle().maxHp() * 10f);
        r.step();

        assertEquals(GameState.GAMEOVER, r.state());
        double time = r.session().playTime();
        r.steps(120);
        assertEquals(time, r.session().playTime(), 0d, "a finished run does not tick");
    }

    @Test
    @DisplayName("game over shakes the screen and lets go of everything")
    void gameOverReleases() {
        TestRun r = new TestRun().beginEndless();
        r.seconds(3);
        assertTrue(grabSomething(r));

        r.run.castle().takeDamage(r.run.castle().maxHp() * 10f);
        r.step();

        assertEquals(GameState.GAMEOVER, r.state());
        assertFalse(r.run.cursor().busy());
        assertTrue(r.run.screenShake().amount() > 0f);
    }

    // ========================================================================
    //  Diagnostics
    // ========================================================================

    @Test
    @DisplayName("a run can be described well enough to reproduce it")
    void runDiagnostics() {
        TestRun r = new TestRun().beginEndless();
        r.seconds(200);
        String d = r.run.describeRun();
        for (String needed : new String[]{
            "state=", "step=", "seed=", "mode=endless", "diff=", "tier=",
            "gold=", "score=", "bestFling=", "bestCombo=", "alive=",
            "projectiles=", "bosses=", "wind=", "shake=", "nextSpawn=", "nextBoss=",
            "horn="}) {
            assertTrue(d.contains(needed), "the description should carry " + needed
                    + "\n  got: " + d);
        }
    }

    // ------------------------------------------------------------------------

    private static float[] enemyXs(TestRun r) {
        float[] xs = new float[r.run.horde().size()];
        for (int i = 0; i < xs.length; i++) {
            xs[i] = r.run.horde().get(i).x();
        }
        return xs;
    }

    private static void assertArrayEquals(float[] a, float[] b, String message) {
        assertEquals(a.length, b.length, message + " (roster size)");
        for (int i = 0; i < a.length; i++) {
            assertEquals(a[i], b[i], 0f, message + " (index " + i + ")");
        }
    }

    /** Presses the cursor onto a grabbable mob, the way the router would. */
    private static boolean grabSomething(TestRun r) {
        for (int i = 0; i < r.run.horde().size(); i++) {
            Enemy e = r.run.horde().get(i);
            if (!e.isAlive() || !e.grabbable()) {
                continue;
            }
            if (r.run.cursor().onWorldPress(
                    com.mymmer.castledefense.input.TestPointers.at(0, e.x(), e.y()))
                    && r.run.cursor().busy()) {
                return true;
            }
        }
        return false;
    }

    private static Enemy firstAlive(TestRun r) {
        for (int i = 0; i < r.run.horde().size(); i++) {
            if (r.run.horde().get(i).isAlive()) {
                return r.run.horde().get(i);
            }
        }
        throw new IllegalStateException("no live enemy");
    }
}
