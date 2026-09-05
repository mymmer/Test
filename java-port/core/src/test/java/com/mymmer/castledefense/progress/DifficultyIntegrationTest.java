package com.mymmer.castledefense.progress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mymmer.castledefense.boss.Boss;
import com.mymmer.castledefense.boss.BossType;
import com.mymmer.castledefense.config.DifficultyConfig;
import com.mymmer.castledefense.config.GameConfig;
import com.mymmer.castledefense.config.Tuning;
import com.mymmer.castledefense.enemy.Enemy;
import com.mymmer.castledefense.enemy.EnemyType;
import com.mymmer.castledefense.game.GameMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The full difficulty audit: everything a preset changes, and proof it is
 * applied exactly once.
 *
 * <h2>What difficulty touches</h2>
 *
 * <p>Read out of {@code main.py:1281-1319} — the {@code Game} properties that
 * read {@code self.difficulty} — and there are exactly eight:
 *
 * <table>
 *   <tr><th>Knob</th><th>Reaches</th><th>Easy / Normal / Hard</th></tr>
 *   <tr><td>{@code scale}</td><td>enemy health and damage scaling</td>
 *       <td>0.80 / 1.00 / 1.30</td></tr>
 *   <tr><td>{@code gold}</td><td>the crowd gold multiplier</td>
 *       <td>1.15 / 1.00 / 1.00</td></tr>
 *   <tr><td>{@code headstart}</td><td>the effective wave a boss spawns at</td>
 *       <td>0.00 / 0.00 / 0.25</td></tr>
 *   <tr><td>{@code speed}</td><td>a flat multiplier on base walking speed</td>
 *       <td>0.90 / 1.00 / 1.40</td></tr>
 *   <tr><td>{@code hpCurve}</td><td>how steeply health grows per tier</td>
 *       <td>0.80 / 1.00 / 1.60</td></tr>
 *   <tr><td>{@code bossFire}</td><td>boss projectile intervals</td>
 *       <td>1.00 / 1.00 / 0.50</td></tr>
 *   <tr><td>{@code eliteHorn}</td><td>what the Challenge Horn calls in</td>
 *       <td>no / no / <b>yes</b></td></tr>
 *   <tr><td>{@code grabCd}</td><td>the wait between grabs</td>
 *       <td>0.00 / 0.25 / 0.50</td></tr>
 * </table>
 *
 * <p>Nothing else in the source reads a difficulty. In particular there is no
 * per-difficulty spawn rate, wave composition, tower stat or skill tuning.
 */
class DifficultyIntegrationTest {

    // ========================================================================
    //  The table
    // ========================================================================

    @Test
    @DisplayName("the three presets carry the numbers the source gives them")
    void presetValues() {
        TestRun r = new TestRun();
        check(r.difficulty("easy"), 0.80f, 1.15f, 0.00f, 0.90f, 0.80f, 1.00d, false, 0.00d);
        check(r.difficulty("normal"), 1.00f, 1.00f, 0.00f, 1.00f, 1.00f, 1.00d, false,
                GameConfig.GRAB_COOLDOWN);
        check(r.difficulty("hard"), 1.30f, 1.00f, 0.25f, 1.40f, 1.60f, 0.50d, true, 0.50d);
    }

    private static void check(DifficultyConfig d, float scale, float gold, float headstart,
                              float speed, float hpCurve, double bossFire,
                              boolean eliteHorn, double grabCd) {
        assertNotNull(d);
        assertEquals(scale, d.scale(), 1e-6f, d.id() + ": scale");
        assertEquals(gold, d.gold(), 1e-6f, d.id() + ": gold");
        assertEquals(headstart, d.headstart(), 1e-6f, d.id() + ": headstart");
        assertEquals(speed, d.speed(), 1e-6f, d.id() + ": speed");
        assertEquals(hpCurve, d.hpCurve(), 1e-6f, d.id() + ": hpCurve");
        assertEquals(bossFire, d.bossFire(), 1e-9, d.id() + ": bossFire");
        assertEquals(eliteHorn, d.eliteHorn(), d.id() + ": eliteHorn");
        assertEquals(grabCd, d.grabCd(), 1e-9, d.id() + ": grabCd");
    }

    // ========================================================================
    //  Applied exactly once
    // ========================================================================

    @Test
    @DisplayName("an ordinary enemy carries the difficulty scaling once, not twice")
    void enemyScaledOnce() {
        //  A doubled application would show as scale^2.  Easy and Hard bracket
        //  Normal from both sides, so a squared factor cannot hide.
        float easy = spawnHp("easy", EnemyType.SCOUT, 12);
        float normal = spawnHp("normal", EnemyType.SCOUT, 12);
        float hard = spawnHp("hard", EnemyType.SCOUT, 12);

        assertTrue(easy < normal, "Easy is softer");
        assertTrue(hard > normal, "Hard is harder");

        //  the ratio must be linear in the knobs, not quadratic
        double hardRatio = hard / normal;
        assertTrue(hardRatio < 3.0,
                "wave 12 on Hard is " + hardRatio + "x Normal; a squared scale "
                        + "would be far larger");
    }

    @Test
    @DisplayName("a spawned enemy's speed carries the flat multiplier once")
    void speedScaledOnce() {
        float normal = spawnSpeed("normal", EnemyType.SCOUT, 1);
        float hard = spawnSpeed("hard", EnemyType.SCOUT, 1);
        float easy = spawnSpeed("easy", EnemyType.SCOUT, 1);

        assertEquals(normal * 1.40f, hard, normal * 0.02f, "Hard walks 40% faster, once");
        assertEquals(normal * 0.90f, easy, normal * 0.02f, "Easy walks 10% slower, once");
    }

    @Test
    @DisplayName("the Berzerker quirk survives the difficulty integration")
    void berzerkerQuirkStillHolds() {
        //  The documented quirk: Berzerker.think RECOMPUTES speed from the base
        //  and the wave curve, discarding the difficulty and tier multipliers
        //  its stored speed carries.  Connecting the real difficulty must not
        //  quietly fix that.
        //  It is observable in the MOVEMENT, not in the stored field: think()
        //  mutates speed and restores it, so speed() reads the same either way.
        //  What the recompute discards is the difficulty and tier multipliers,
        //  so a Hard Berzerker and a Normal one cover the SAME ground -- while
        //  any other unit does not.
        float hardBerz = walked("hard", EnemyType.BERZERKER);
        float normalBerz = walked("normal", EnemyType.BERZERKER);
        assertEquals(normalBerz, hardBerz, Math.abs(normalBerz) * 0.02f,
                "a Berzerker ignores the difficulty speed entirely");

        float hardScout = walked("hard", EnemyType.SCOUT);
        float normalScout = walked("normal", EnemyType.SCOUT);
        assertTrue(Math.abs(hardScout) > Math.abs(normalScout) * 1.2f,
                "...whereas a Scout on Hard really does move faster, which is "
                        + "what makes the Berzerker's behaviour a quirk");

        //  and its stored stat block DOES carry the difficulty, which is exactly
        //  the value the recompute throws away
        TestRun hard = new TestRun();
        hard.begin(GameMode.CLASSIC, "hard");
        TestRun normal = new TestRun();
        normal.begin(GameMode.CLASSIC, "normal");
        Enemy hb = hard.run.spawnEnemy(EnemyType.BERZERKER, 20);
        Enemy nb = normal.run.spawnEnemy(EnemyType.BERZERKER, 20);
        assertTrue(hb.speed() > nb.speed(),
                "the stat block is scaled; think() simply does not use it");
    }

    @Test
    @DisplayName("a boss's fire scale is captured once, at construction")
    void bossFireScaleOnce() {
        TestRun hard = new TestRun();
        hard.begin(GameMode.CLASSIC, "hard");
        Boss boss = (Boss) hard.run.summonBoss(BossType.DRAGON, 5);
        assertNotNull(boss);
        assertEquals(0.50d, boss.fireScale(), 1e-9, "Hard fires twice as fast");
        assertEquals(2.0d * 0.50d, boss.fireDelay(2.0), 1e-9, "and the delay is halved once");

        TestRun normal = new TestRun();
        normal.begin(GameMode.CLASSIC, "normal");
        Boss plain = (Boss) normal.run.summonBoss(BossType.DRAGON, 5);
        assertEquals(1.0d, plain.fireScale(), 1e-9);
    }

    @Test
    @DisplayName("the grab delay is the difficulty's, scaled once by Light Fingers")
    void grabCooldownOnce() {
        TestRun easy = new TestRun();
        easy.begin(GameMode.CLASSIC, "easy");
        assertEquals(0d, easy.run.cursor().grabCooldown(), 1e-9,
                "Easy has no wait to shorten");

        TestRun hard = new TestRun();
        hard.begin(GameMode.CLASSIC, "hard");
        assertEquals(0.50d, hard.run.cursor().grabCooldown(), 1e-9);

        hard.grantPoints(10).buyTalentDeep("lightfingers", 2);
        hard.run.refreshGrabCooldown();
        assertEquals(0.50d * (1d - 0.40d), hard.run.cursor().grabCooldown(), 1e-9,
                "one difficulty delay, one talent scale, no double-dip");
    }

    @Test
    @DisplayName("the gold multiplier carries the difficulty exactly once")
    void goldScaledOnce() {
        TestRun easy = new TestRun();
        easy.begin(GameMode.CLASSIC, "easy");
        TestRun normal = new TestRun();
        normal.begin(GameMode.CLASSIC, "normal");
        for (int i = 0; i < 10; i++) {
            easy.run.spawnEnemy(EnemyType.SCOUT, 1);
            normal.run.spawnEnemy(EnemyType.SCOUT, 1);
        }
        assertEquals(normal.run.goldMultiplier() * 1.15f, easy.run.goldMultiplier(),
                1e-4f, "Easy pays 15% more, once");
    }

    @Test
    @DisplayName("the boss headstart lifts the effective wave once")
    void bossHeadstartOnce() {
        //  wave + round(wave * headstart).  On Hard at wave 20 that is 20 + 5.
        TestRun hard = new TestRun();
        hard.begin(GameMode.CLASSIC, "hard");
        assertEquals(0.25f, hard.run.bossHeadstart(), 1e-6f);

        TestRun normal = new TestRun();
        normal.begin(GameMode.CLASSIC, "normal");
        assertEquals(0f, normal.run.bossHeadstart(), 1e-6f);

        //  the same boss at the same wave really is tougher on Hard
        Boss h = (Boss) hard.run.summonBoss(BossType.TROLL_KING, 20 + Math.round(20 * 0.25f));
        Boss n = (Boss) normal.run.summonBoss(BossType.TROLL_KING, 20);
        assertTrue(h.maxHp() > n.maxHp(),
                "a Hard boss arrives scaled as though the run were further along");
    }

    // ========================================================================
    //  Hard-specific rules
    // ========================================================================

    @Test
    @DisplayName("only Hard turns the horn into an elite pack")
    void eliteHornIsHardOnly() {
        assertFalse(new TestRun().difficulty("easy").eliteHorn());
        assertFalse(new TestRun().difficulty("normal").eliteHorn());
        assertTrue(new TestRun().difficulty("hard").eliteHorn());
    }

    @Test
    @DisplayName("the Hard Endless horn is ten elites, not twelve of anything")
    void hardHornPack() {
        TestRun hard = new TestRun();
        hard.begin(GameMode.ENDLESS, "hard");
        hard.survivingSeconds(2);
        int before = hard.aliveEnemies();
        assertTrue(hard.run.director().blowHorn());
        assertEquals(before + Tuning.HARD_HORN_RUSH, hard.aliveEnemies());

        TestRun normal = new TestRun();
        normal.begin(GameMode.ENDLESS, "normal");
        normal.survivingSeconds(2);
        int nBefore = normal.aliveEnemies();
        assertTrue(normal.run.director().blowHorn());
        assertEquals(nBefore + Tuning.ENDLESS_HORN_RUSH, normal.aliveEnemies());
    }

    @Test
    @DisplayName("Hard is more than HP x N: it changes six knobs at once")
    void hardIsAnOverhaul() {
        DifficultyConfig hard = new TestRun().difficulty("hard");
        DifficultyConfig normal = new TestRun().difficulty("normal");
        int changed = 0;
        if (hard.scale() != normal.scale()) {
            changed++;
        }
        if (hard.headstart() != normal.headstart()) {
            changed++;
        }
        if (hard.speed() != normal.speed()) {
            changed++;
        }
        if (hard.hpCurve() != normal.hpCurve()) {
            changed++;
        }
        if (hard.bossFire() != normal.bossFire()) {
            changed++;
        }
        if (hard.eliteHorn() != normal.eliteHorn()) {
            changed++;
        }
        if (hard.grabCd() != normal.grabCd()) {
            changed++;
        }
        assertEquals(7, changed,
                "Hard changes seven of the eight knobs; only gold is unchanged");
        assertEquals(normal.gold(), hard.gold(), 1e-6f, "and gold is the untouched one");
    }

    // ========================================================================
    //  Settings versus the running run
    // ========================================================================

    @Test
    @DisplayName("a run captures its difficulty at creation and does not re-read it")
    void runCapturesItsDifficulty() {
        //  Python reads Game.difficulty live off Settings -- but the UI gives no
        //  path from a running game to the settings screen (PAUSED only toggles
        //  back to PLAYING, and MENU is reachable only through GAMEOVER, which
        //  resets first).  So the observable contract is "a run's difficulty
        //  cannot change while it runs", and capturing it at creation is both
        //  faithful and safer: a Phase 10 pause menu with a difficulty button
        //  could not retroactively rescale a live horde.
        TestRun r = new TestRun();
        r.begin(GameMode.CLASSIC, "normal");
        assertEquals("normal", r.session().difficulty().id());
        assertEquals(1.0f, r.run.enemyScale(), 1e-6f);

        //  the saved preference changes; the run does not
        r.run.beginRun(GameMode.CLASSIC, r.difficulty("normal"), 7L);
        assertEquals(1.0f, r.run.enemyScale(), 1e-6f);

        //  ...and the NEXT run picks the new one up
        r.run.beginRun(GameMode.CLASSIC, r.difficulty("hard"), 8L);
        assertEquals("hard", r.session().difficulty().id());
        assertEquals(1.30f, r.run.enemyScale(), 1e-6f);
    }

    // ------------------------------------------------------------------------

    private static float spawnHp(String difficulty, EnemyType type, int wave) {
        TestRun r = new TestRun();
        r.begin(GameMode.CLASSIC, difficulty);
        return r.run.spawnEnemy(type, wave).maxHp();
    }

    /** How far a fresh mob walks in half a second, out in the open. */
    private static float walked(String difficulty, EnemyType type) {
        TestRun r = new TestRun();
        r.begin(GameMode.CLASSIC, difficulty);
        Enemy e = r.run.spawnEnemy(type, 20);
        e.setX(1100f);                          // clear of the wall and the queue
        float x0 = e.x();
        r.survivingSeconds(0.5);
        return e.x() - x0;
    }

    private static float spawnSpeed(String difficulty, EnemyType type, int wave) {
        TestRun r = new TestRun();
        r.begin(GameMode.CLASSIC, difficulty);
        return r.run.spawnEnemy(type, wave).speed();
    }
}
