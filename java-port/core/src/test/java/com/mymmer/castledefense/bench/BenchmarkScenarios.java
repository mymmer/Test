package com.mymmer.castledefense.bench;

import com.badlogic.gdx.utils.Array;
import com.mymmer.castledefense.boss.BossType;
import com.mymmer.castledefense.defence.TowerType;
import com.mymmer.castledefense.enemy.Enemy;
import com.mymmer.castledefense.enemy.EnemyType;
import com.mymmer.castledefense.progress.TestRun;
import com.mymmer.castledefense.skill.SkillId;

/**
 * The scenarios the performance baseline is measured on.
 *
 * <p>Each is built from real gameplay calls — spawning through {@code RunWorld},
 * buying through the real shop — so what is timed is the game, not a mock of it.
 * Every one is seeded, so two runs of the same scenario are comparable.
 *
 * <p><b>At least one scenario per known hotspot.</b> {@code PORT_ANALYSIS.md} §3
 * lists the algorithms transcribed verbatim from Python that are quadratic or
 * near it, and the point of a baseline is to find out which of them actually
 * costs anything at the densities this game reaches:
 *
 * <pre>
 *   crowd separation          every alive pair, per step   -> DENSE, HUGE_CROWD
 *   projectile collision      every shot x every enemy     -> PROJECTILES, CANNONS
 *   airborne slam checks      every airborne pair          -> SLAM
 *   Cannon cluster scoring    per candidate x every enemy  -> CANNONS
 *   tower target selection    every tower x every enemy    -> CANNONS, DENSE
 *   area effects              fire zones and tornados      -> EFFECTS
 *   Endless scheduling        director work per step       -> LONG_RUN
 * </pre>
 */
public final class BenchmarkScenarios {

    /** The seed every scenario runs on, so the set is reproducible. */
    public static final long SEED = 8_675_309L;

    private BenchmarkScenarios() {
    }

    public static Array<Benchmark> all() {
        Array<Benchmark> out = new Array<>();
        out.add(new Benchmark("early-wave", BenchmarkScenarios::earlyWave,
                SEED, 600, 3000));
        out.add(new Benchmark("mixed-late-wave", BenchmarkScenarios::mixedLate,
                SEED, 600, 3000));
        out.add(new Benchmark("dense-crowd", BenchmarkScenarios::denseCrowd,
                SEED, 600, 2000));
        out.add(new Benchmark("huge-crowd", BenchmarkScenarios::hugeCrowd,
                SEED, 300, 1000));
        out.add(new Benchmark("multi-boss", BenchmarkScenarios::multiBoss,
                SEED, 600, 2000));
        out.add(new Benchmark("projectile-heavy", BenchmarkScenarios::projectileHeavy,
                SEED, 600, 2000));
        out.add(new Benchmark("cannon-heavy", BenchmarkScenarios::cannonHeavy,
                SEED, 600, 2000));
        out.add(new Benchmark("slam-airborne", BenchmarkScenarios::slamAirborne,
                SEED, 300, 1500));
        out.add(new Benchmark("effects-weather", BenchmarkScenarios::effectsWeather,
                SEED, 600, 2000));
        out.add(new Benchmark("endless-long-run", BenchmarkScenarios::endlessLongRun,
                SEED, 600, 3000));
        return out;
    }

    // ========================================================================
    //  Scenarios
    // ========================================================================

    /** An ordinary opening wave: the floor the rest is measured against. */
    private static void earlyWave(TestRun t) {
        for (int i = 0; i < 8; i++) {
            place(t, EnemyType.SCOUT, 2, 400f + i * 90f);
        }
    }

    /** A busy mid-game wave with a full defence: the common case. */
    private static void mixedLate(TestRun t) {
        buyDefences(t, 2);
        EnemyType[] roster = {EnemyType.SCOUT, EnemyType.FOOT_SOLDIER,
            EnemyType.SHIELD_BEARER, EnemyType.BERZERKER, EnemyType.GARGOYLE,
            EnemyType.SKELETON, EnemyType.ASSASSIN, EnemyType.SIEGE_RAM};
        for (int i = 0; i < 30; i++) {
            place(t, roster[i % roster.length], 18, 320f + (i * 41f) % 960f);
        }
    }

    /** Crowd separation and target selection at a realistic worst case. */
    private static void denseCrowd(TestRun t) {
        buyDefences(t, 2);
        for (int i = 0; i < 70; i++) {
            //  Packed tightly, so nearly every pair is within separation range --
            //  which is what makes this the crowd-separation scenario rather
            //  than just a lot of enemies.
            place(t, EnemyType.FOOT_SOLDIER, 20, 400f + (i % 35) * 22f);
        }
    }

    /** Beyond anything the game produces, to see where the curve goes. */
    private static void hugeCrowd(TestRun t) {
        buyDefences(t, 3);
        for (int i = 0; i < 160; i++) {
            place(t, i % 3 == 0 ? EnemyType.SKELETON : EnemyType.FOOT_SOLDIER,
                    28, 300f + (i % 60) * 16f);
        }
    }

    private static void multiBoss(TestRun t) {
        buyDefences(t, 2);
        for (BossType type : BossType.values()) {
            t.run.summonBoss(type, 30);
        }
        for (int i = 0; i < 24; i++) {
            place(t, EnemyType.SKELETON, 30, 420f + (i * 37f) % 880f);
        }
    }

    /** Many shots in the air at once: the projectile collision scan. */
    private static void projectileHeavy(TestRun t) {
        buyDefences(t, 6);              // six of everything, all firing
        for (int i = 0; i < 40; i++) {
            place(t, EnemyType.FOOT_SOLDIER, 20, 340f + (i * 31f) % 920f);
        }
    }

    /** Cannons specifically: cluster scoring is per candidate per enemy. */
    private static void cannonHeavy(TestRun t) {
        t.grantGold(1000000);
        for (int i = 0; i < 8; i++) {
            t.shop().buy("cannon");
        }
        for (int i = 0; i < 60; i++) {
            place(t, i % 5 == 0 ? EnemyType.SIEGE_RAM : EnemyType.FOOT_SOLDIER,
                    24, 360f + (i % 30) * 26f);
        }
    }

    /** Everything airborne at once: the mid-air slam pair scan. */
    private static void slamAirborne(TestRun t) {
        for (int i = 0; i < 45; i++) {
            Enemy e = place(t, EnemyType.SCOUT, 15, 400f + (i % 25) * 30f);
            if (e != null) {
                //  Thrown, so every one of them is in the air together and the
                //  slam check has the most pairs it will ever see.
                e.onGrab();
                e.onRelease(260f - (i % 7) * 60f, -420f - (i % 5) * 40f);
            }
        }
    }

    /** Fire zones, a tornado and a storm, all live. */
    private static void effectsWeather(TestRun t) {
        while (t.skills().unlockedCount() < SkillId.values().length
                && t.skills().unlockNext() != null) {
            // unlock all three
        }
        t.skills().castAt(SkillId.METEOR, 700f, 200f);
        t.skills().castAt(SkillId.TORNADO, 620f, 160f);
        for (int i = 0; i < 400 && !t.run.weather().storm(); i++) {
            t.run.weather().roll();
        }
        buyDefences(t, 2);
        for (int i = 0; i < 40; i++) {
            place(t, EnemyType.FOOT_SOLDIER, 22, 360f + (i * 33f) % 900f);
        }
    }

    /** A long Endless run: the director's scheduling cost over time. */
    private static void endlessLongRun(TestRun t) {
        buyDefences(t, 3);
        //  Five minutes in, so the tier ladder, the boss schedule and the talent
        //  bank are all doing real work every step.
        t.scheduleSeconds(300);
    }

    // ========================================================================

    private static Enemy place(TestRun t, EnemyType type, int wave, float x) {
        Enemy e = t.run.spawnEnemy(type, wave);
        if (e != null) {
            e.setX(x);
        }
        return e;
    }

    private static void buyDefences(TestRun t, int each) {
        t.grantGold(1000000);
        for (TowerType type : TowerType.values()) {
            for (int i = 0; i < each; i++) {
                t.shop().buy(type.id());
            }
        }
        for (int i = 0; i < 3; i++) {
            t.shop().buy("wall");
        }
    }
}
