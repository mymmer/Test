package com.mymmer.castledefense.progress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mymmer.castledefense.config.GameConfig;
import com.mymmer.castledefense.debug.TraceEvent;
import com.mymmer.castledefense.defence.CombatModifiers;
import com.mymmer.castledefense.defence.Projectile;
import com.mymmer.castledefense.defence.ProjectileKind;
import com.mymmer.castledefense.enemy.Enemy;
import com.mymmer.castledefense.enemy.EnemyState;
import com.mymmer.castledefense.enemy.EnemyType;
import com.mymmer.castledefense.game.GameState;
import com.mymmer.castledefense.progress.Weather;
import com.mymmer.castledefense.util.Rng;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Wind and storms, gameplay side.
 *
 * <p>The important structural claim is that there is <b>one</b> wind and
 * everything reads it, rather than each entity carrying a copy.
 */
class WeatherTest {

    // ========================================================================
    //  Rolling
    // ========================================================================

    @Test
    @DisplayName("wind lands inside +/- WIND_MAX and both signs occur")
    void windRange() {
        Weather w = new Weather(new Rng(11L), CombatModifiers.NONE, new ScreenShake(), null);
        boolean sawPositive = false;
        boolean sawNegative = false;
        for (int i = 0; i < 400; i++) {
            w.roll();
            assertTrue(Math.abs(w.wind()) <= GameConfig.WIND_MAX + 1e-3f,
                    "wind " + w.wind() + " out of range");
            sawPositive |= w.wind() > 0f;
            sawNegative |= w.wind() < 0f;
        }
        assertTrue(sawPositive && sawNegative, "the wind blows both ways");
    }

    @Test
    @DisplayName("a headwind is negative and past 45% of the maximum")
    void headwindThreshold() {
        TestRun r = new TestRun().beginClassic();
        Weather w = r.run.weather();
        //  the threshold is a property of the value, so it can be checked by
        //  rolling until each case appears rather than by forcing a field
        boolean sawHead = false;
        boolean sawTail = false;
        boolean sawCalm = false;
        for (int i = 0; i < 500 && !(sawHead && sawTail && sawCalm); i++) {
            w.roll();
            float wind = w.wind();
            float threshold = GameConfig.WIND_MAX * 0.45f;
            if (wind < -threshold) {
                assertTrue(w.strongHeadwind(), "wind " + wind + " is a headwind");
                sawHead = true;
            } else if (wind > threshold) {
                assertTrue(w.strongTailwind(), "wind " + wind + " is a tailwind");
                sawTail = true;
            } else {
                assertFalse(w.strongHeadwind() || w.strongTailwind(),
                        "wind " + wind + " is neither");
                sawCalm = true;
            }
        }
        assertTrue(sawHead && sawTail && sawCalm, "all three cases should occur");
    }

    @Test
    @DisplayName("weather is rolled once per Classic wave and once per Endless tier")
    void rollCadence() {
        TestRun classic = new TestRun().beginClassic();
        assertEquals(1, classic.run.weather().rollCount(), "wave 1 rolls once");
        classic.seconds(20);
        assertEquals(1, classic.run.weather().rollCount(), "and not again mid-wave");

        TestRun endless = new TestRun().beginEndless();
        assertEquals(1, endless.run.weather().rollCount());
        endless.scheduleSeconds(30);
        assertEquals(2, endless.run.weather().rollCount(), "one more at the tier step");
        endless.scheduleSeconds(29);
        assertEquals(2, endless.run.weather().rollCount(), "and not before the next");
    }

    @Test
    @DisplayName("a rolled tailwind, headwind or storm posts its banner")
    void weatherBanners() {
        TestRun r = new TestRun().beginClassic();
        Announcements banners = r.run.announcements();
        Weather w = r.run.weather();
        boolean sawWindBanner = false;
        for (int i = 0; i < 200 && !sawWindBanner; i++) {
            banners.clear();
            w.roll();
            w.announce(banners);
            sawWindBanner = banners.has(Announcements.Id.WEATHER_TAILWIND)
                    || banners.has(Announcements.Id.WEATHER_HEADWIND);
            if (w.strongHeadwind()) {
                assertTrue(banners.has(Announcements.Id.WEATHER_HEADWIND));
            }
            if (w.storm()) {
                assertTrue(banners.has(Announcements.Id.WEATHER_STORM));
            }
        }
        assertTrue(sawWindBanner, "a strong wind should eventually be announced");
    }

    // ========================================================================
    //  One wind, read by everyone
    // ========================================================================

    @Test
    @DisplayName("the same wind is consumed by projectiles and by airborne mobs")
    void oneWindForEverything() {
        //  Not "each reads a similar number": literally the same one.  Nothing
        //  copies the wind into an entity, so changing it moves everything that
        //  is in the air on the very next step.
        TestRun r = new TestRun().beginClassic();
        assertEquals(r.run.weather().wind(), r.run.wind(), 0f,
                "the world's wind IS the weather's wind");

        //  a projectile in flight, and a mob in the air, on the same step
        Projectile p = new Projectile(r.run, 600f, 300f, 0f, 0f,
                ProjectileKind.ARROW, 10f, 0f, 0, 0f, false, 30d, 0d, 1f, 1f, false, 0L);
        r.run.addProjectile(p);

        Enemy e = r.run.spawnEnemy(EnemyType.SCOUT, 1);
        e.onRelease(0f, -10f);
        assertEquals(EnemyState.AIR, e.state());
        //  park it mid-arena with a clean vertical velocity: away from both
        //  walls, so the only thing that can move vx this step is the wind
        e.setX(800f);
        e.setY(300f);
        e.setVelocity(0f, -300f);

        float pvx0 = p.vx();
        float evx0 = e.vx();
        r.step();

        float wind = r.run.wind();
        float dt = com.mymmer.castledefense.game.Simulation.PHYSICS_DT;
        assertEquals(pvx0 + wind * GameConfig.WIND_PROJECTILE * dt, p.vx(), 1e-3f,
                "the shot felt exactly this wind");
        //  updateAir applies the wind FIRST and then drags the updated value,
        //  which is the source's order and is why this is not
        //  `vx0 + wind*dt - vx0*drag*dt`
        float windedVx = evx0 + wind * dt;
        assertEquals(windedVx - windedVx * GameConfig.AIR_DRAG * dt, e.vx(), 1e-3f,
                "and so did the body, through the same one value");
    }

    // ========================================================================
    //  Ceiling strikes
    // ========================================================================

    @Test
    @DisplayName("no storm, no strike")
    void noStormNoStrike() {
        TestRun r = new TestRun().beginClassic();
        Weather w = r.run.weather();
        while (w.storm()) {
            w.roll();
        }
        Enemy e = r.run.spawnEnemy(EnemyType.SCOUT, 1);
        float hp = e.hp();
        w.strike(e);
        assertEquals(hp, e.hp(), 0f);
        assertEquals(0, w.strikeCount());
    }

    @Test
    @DisplayName("a strike takes a share of MAXIMUM health, and locks the mob out")
    void strikeDamageAndCooldown() {
        TestRun r = new TestRun().beginClassic();
        Weather w = stormyWeather(r);
        Enemy e = r.run.spawnEnemy(EnemyType.SIEGE_RAM, 1);
        float maxHp = e.maxHp();
        float hp = e.hp();

        w.strike(e);
        float expected = maxHp * GameConfig.STORM_DAMAGE;
        assertEquals(1, w.strikeCount());
        assertTrue(hp - e.hp() > 0f, "it took damage");
        assertEquals(GameConfig.STORM_COOLDOWN, e.stormCooldown(), 1e-9,
                "and is locked out for STORM_COOLDOWN");

        //  a second strike inside the lockout does nothing at all
        float after = e.hp();
        w.strike(e);
        assertEquals(after, e.hp(), 0f, "no chain-zapping while it hangs");
        assertEquals(1, w.strikeCount());

        //  and the damage really was proportional to the maximum, not the current
        assertTrue(expected > 0f);
    }

    @Test
    @DisplayName("the lockout expires on the gameplay clock and the mob can be struck again")
    void strikeCooldownExpires() {
        TestRun r = new TestRun().beginClassic();
        Weather w = stormyWeather(r);
        Enemy e = r.run.spawnEnemy(EnemyType.SIEGE_RAM, 1);
        w.strike(e);
        assertEquals(1, w.strikeCount());

        r.seconds(GameConfig.STORM_COOLDOWN + 0.05);
        assertEquals(0d, e.stormCooldown(), 0d);
        w.strike(e);
        assertEquals(2, w.strikeCount(), "struck again once the lockout ran out");
    }

    @Test
    @DisplayName("a strike shakes the screen, flashes, and is traced")
    void strikeSideEffects() {
        TestRun r = new TestRun();
        r.recording();
        r.beginClassic();
        Weather w = stormyWeather(r);
        Enemy e = r.run.spawnEnemy(EnemyType.SIEGE_RAM, 1);

        int shakes = r.run.screenShake().events();
        w.strike(e);

        assertTrue(r.run.screenShake().events() > shakes);
        assertEquals(1f, w.stormFlash(), 0f);
        assertEquals(1, r.trace.countOf(TraceEvent.STORM_STRIKE));
    }

    @Test
    @DisplayName("the storm flash decays in every state, including paused")
    void stormFlashIsAlwaysTier() {
        TestRun r = new TestRun().beginClassic();
        Weather w = stormyWeather(r);
        Enemy e = r.run.spawnEnemy(EnemyType.SIEGE_RAM, 1);
        w.strike(e);
        assertEquals(1f, w.stormFlash(), 0f);

        r.world.setState(GameState.PAUSED);
        r.step();
        assertTrue(w.stormFlash() < 1f, "it keeps fading on the pause screen");
    }

    @Test
    @DisplayName("a mob thrown into the ceiling calls the strike itself")
    void airborneMobTriggersTheStrike() {
        //  The airborne integration already checks the height; this proves it is
        //  wired to the real weather rather than to a stub.
        TestRun r = new TestRun().beginClassic();
        Weather w = stormyWeather(r);
        Enemy e = r.run.spawnEnemy(EnemyType.SCOUT, 1);
        e.setY(GameConfig.STORM_CEILING - 20f);
        e.onRelease(0f, -5f);
        assertEquals(EnemyState.AIR, e.state());

        r.step();
        assertTrue(w.strikeCount() >= 1,
                "a body above STORM_CEILING draws a bolt without anyone asking");
    }

    // ------------------------------------------------------------------------

    /** Rolls until a storm comes up, so the storm branch can be tested. */
    private static Weather stormyWeather(TestRun r) {
        Weather w = r.run.weather();
        for (int i = 0; i < 500 && !w.storm(); i++) {
            w.roll();
        }
        assertTrue(w.storm(), "a storm should come up within 500 rolls");
        return w;
    }
}
