package com.mymmer.castledefense.progress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mymmer.castledefense.config.Tuning;
import com.mymmer.castledefense.debug.TraceEvent;
import com.mymmer.castledefense.enemy.Enemy;
import com.mymmer.castledefense.enemy.EnemyType;
import com.mymmer.castledefense.game.GameMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Challenge Horn, in both modes and on both difficulties.
 *
 * <p>The once-per-run Endless behaviour is the headline: it looks like a bug and
 * is not one.
 */
class ChallengeHornTest {

    // ========================================================================
    //  Classic
    // ========================================================================

    @Test
    @DisplayName("Classic: the horn empties the queue onto the field at once")
    void classicCallsInTheRest() {
        TestRun r = new TestRun().beginClassic();
        r.seconds(2);
        int queued = r.waves().pendingSpawns();
        int alive = r.aliveEnemies();
        assertTrue(queued > 0);

        assertTrue(r.run.director().blowHorn());

        assertEquals(0, r.waves().pendingSpawns(), "the queue is emptied");
        assertEquals(alive + queued, r.aliveEnemies(),
                "every queued mob is now on the field, head for head");
        assertTrue(r.session().hornUsed());
        assertEquals(Tuning.HORN_BONUS, r.session().hornBonus(), 1e-6f);
    }

    @Test
    @DisplayName("Classic: the horn is re-armed every wave")
    void classicHornPerWave() {
        TestRun r = new TestRun().beginClassic();
        r.seconds(2);
        assertTrue(r.run.director().blowHorn());
        assertTrue(r.session().hornUsed());
        assertFalse(r.run.director().blowHorn(), "not twice in one wave");

        //  clear the wave and start the next one
        r.killEveryEnemy();
        r.seconds(2.0);
        assertTrue(r.run.startNextWave());

        assertFalse(r.session().hornUsed(), "a new wave re-arms it");
        assertEquals(0f, r.session().hornBonus(), 0f, "and drops the bonus with it");
        r.seconds(2);
        assertTrue(r.run.director().blowHorn());
    }

    @Test
    @DisplayName("Classic: an empty queue refuses, and does NOT spend the horn")
    void classicEmptyQueueRefuses() {
        TestRun r = new TestRun().beginClassic();
        //  drain the queue the ordinary way
        for (int i = 0; i < 200 && r.waves().pendingSpawns() > 0; i++) {
            r.seconds(1.0);
            r.killEveryEnemy();
        }
        assertEquals(0, r.waves().pendingSpawns());

        assertFalse(r.run.director().blowHorn(), "nothing left to call in");
        assertFalse(r.session().hornUsed(), "a wasted click is not a wasted horn");
    }

    // ========================================================================
    //  Endless
    // ========================================================================

    @Test
    @DisplayName("Endless: the horn calls in twelve fresh mobs")
    void endlessRush() {
        TestRun r = new TestRun().beginEndless();
        r.seconds(2);
        int alive = r.aliveEnemies();

        assertTrue(r.run.director().blowHorn());
        assertEquals(alive + Tuning.ENDLESS_HORN_RUSH, r.aliveEnemies());
        assertTrue(r.session().hornUsed());
    }

    @Test
    @DisplayName("Endless: ONE horn per run, however many tiers are survived")
    void endlessHornIsOncePerRun() {
        //  THE QUIRK.  begin_endless arms the horn; update_endless_schedule
        //  never re-arms it.  Do not "fix" this into once-per-tier.
        TestRun r = new TestRun().beginEndless();
        r.seconds(2);
        assertTrue(r.run.director().blowHorn());
        assertTrue(r.session().hornUsed());

        //  survive ten tiers
        r.scheduleSeconds(300);
        assertEquals(11, r.session().wave(), "ten tier steps have happened");
        assertTrue(r.session().hornUsed(), "and the horn is still spent");
        assertFalse(r.run.director().blowHorn(), "still refused, five minutes later");
        assertEquals(Tuning.HORN_BONUS, r.session().hornBonus(), 1e-6f,
                "and the reward bonus lasts the whole run with it");
    }

    @Test
    @DisplayName("a fresh Endless run gets its horn back")
    void newRunRearmsTheHorn() {
        TestRun r = new TestRun().beginEndless();
        r.seconds(2);
        assertTrue(r.run.director().blowHorn());

        r.run.beginRun(GameMode.ENDLESS, r.difficulty("normal"), 4242L);
        assertFalse(r.session().hornUsed(), "a new run is a new horn");
    }

    // ========================================================================
    //  Hard
    // ========================================================================

    @Test
    @DisplayName("Hard Endless: ten elites, not twelve of anything")
    void hardEndlessPack() {
        TestRun r = new TestRun();
        r.begin(GameMode.ENDLESS, "hard");
        assertTrue(r.difficulty("hard").eliteHorn(), "Hard must set eliteHorn");
        r.seconds(2);
        int alive = r.aliveEnemies();

        assertTrue(r.run.director().blowHorn());
        assertEquals(alive + Tuning.HARD_HORN_RUSH, r.aliveEnemies(),
                "HARD_HORN_RUSH, not ENDLESS_HORN_RUSH");
    }

    @Test
    @DisplayName("Hard Classic: the head-count is unchanged, the heads are not")
    void hardClassicSwapsChaff() {
        TestRun plain = new TestRun();
        plain.begin(GameMode.CLASSIC, "normal");
        plain.seconds(2);
        int plainQueued = plain.waves().pendingSpawns();
        int plainAlive = plain.aliveEnemies();
        plain.run.director().blowHorn();
        int plainTotal = plain.aliveEnemies();

        TestRun hard = new TestRun();
        hard.begin(GameMode.CLASSIC, "hard");
        hard.seconds(2);
        int hardQueued = hard.waves().pendingSpawns();
        int hardAlive = hard.aliveEnemies();
        hard.run.director().blowHorn();

        assertEquals(plainAlive + plainQueued, plainTotal);
        assertEquals(hardAlive + hardQueued, hard.aliveEnemies(),
                "Hard swaps units, it does not add them -- wave clearing still adds up");
    }

    @Test
    @DisplayName("a Hard pack is drawn from the elite roster, deterministically")
    void hardPackIsSeededAndElite() {
        assertEquals(hardPackFingerprint(31337L), hardPackFingerprint(31337L));
        assertFalse(hardPackFingerprint(31337L).equals(hardPackFingerprint(999L)),
                "a different seed rolls a different pack");
    }

    @Test
    @DisplayName("before any heavy unlocks, the Hard roster is the last two classes")
    void earlyHardRosterFallsBackToTheToughest() {
        //  elite_roster falls back to `opened[-2:]`, not to Scouts.  At tier 1
        //  only the Scout exists, so a Hard pack there IS Scouts -- in Python
        //  too, and asserting otherwise would be asserting a fiction.  Tier 2 is
        //  the first place the fallback is observable: the last two unlocked
        //  are Scout and FootSoldier, and a pack must be able to contain both.
        TestRun r = new TestRun(31337L);
        r.run.beginRun(GameMode.ENDLESS, r.difficulty("hard"), 31337L);
        r.scheduleSeconds(31);                  // tier 2
        assertEquals(2, r.session().wave());

        String pack = packAfterHorn(r);
        assertTrue(pack.contains(EnemyType.FOOT_SOLDIER.id()),
                "the fallback must reach past the Scout: " + pack);
    }

    @Test
    @DisplayName("once a heavy unlocks, the Hard pack is heavies only")
    void hardRosterPrefersHeavies() {
        //  From tier 3 the ShieldBearer is unlocked and IS in HARD_HORN_UNITS,
        //  so `avail` is non-empty and the fallback is never reached -- no
        //  Scouts, no FootSoldiers, whatever the seed.
        TestRun r = new TestRun(4242L);
        r.run.beginRun(GameMode.ENDLESS, r.difficulty("hard"), 4242L);
        r.scheduleSeconds(61);                  // tier 3
        assertEquals(3, r.session().wave());

        String pack = packAfterHorn(r);
        assertFalse(pack.contains(EnemyType.SCOUT.id()), "no chaff in an elite pack: " + pack);
        assertFalse(pack.contains(EnemyType.FOOT_SOLDIER.id()), "nor any of this: " + pack);
        assertTrue(pack.contains(EnemyType.SHIELD_BEARER.id()), "the heavy that is open");
    }

    /** The type ids the horn just put on the field, comma separated. */
    private static String packAfterHorn(TestRun r) {
        int before = r.run.horde().size();
        assertTrue(r.run.director().blowHorn());
        StringBuilder sb = new StringBuilder();
        for (int i = before; i < r.run.horde().size(); i++) {
            Enemy e = r.run.horde().get(i);
            sb.append(e.typeId()).append(',');
        }
        return sb.toString();
    }

    @Test
    @DisplayName("the horn is traced, once, with its head-count")
    void hornIsTraced() {
        TestRun r = new TestRun();
        r.recording();
        r.beginEndless();
        r.seconds(2);
        r.run.director().blowHorn();
        assertEquals(1, r.trace.countOf(TraceEvent.HORN_USED));
    }

    @Test
    @DisplayName("blowing the horn shakes the screen")
    void hornShakes() {
        TestRun r = new TestRun().beginEndless();
        r.seconds(2);
        int events = r.run.screenShake().events();
        r.run.director().blowHorn();
        assertTrue(r.run.screenShake().events() > events);
        assertTrue(r.run.screenShake().amount() > 0f);
    }

    // ------------------------------------------------------------------------

    private static String hardPackFingerprint(long seed) {
        TestRun r = new TestRun(seed);
        r.run.beginRun(GameMode.ENDLESS, r.difficulty("hard"), seed);
        r.scheduleSeconds(200);         // deep enough for a real elite roster
        int before = r.run.horde().size();
        r.run.director().blowHorn();
        StringBuilder sb = new StringBuilder();
        for (int i = before; i < r.run.horde().size(); i++) {
            sb.append(r.run.horde().get(i).typeId()).append(',');
        }
        return sb.toString();
    }
}
