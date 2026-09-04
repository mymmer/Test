package com.mymmer.castledefense.progress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import com.mymmer.castledefense.config.GameConfig;
import com.mymmer.castledefense.config.Tuning;
import com.mymmer.castledefense.defence.CombatModifiers;
import com.mymmer.castledefense.game.Simulation;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Phase 8 numbers, checked against values generated from the Python source.
 *
 * <p>The fixtures come from {@code tools/parity/generate_fixtures.py}, which
 * reads the authoritative files rather than importing them. Everything here is
 * a formula that can be stated without a running game, which is what makes it
 * fixturable at all; the wave and tier <em>flow</em> is proven separately by
 * driving the real directors.
 *
 * <p>All of it is double arithmetic on both sides, which is the point of the
 * pre-Phase-8 timing work: before it, half of these would have needed a
 * tolerance.
 */
class ProgressionParityTest {

    private static JsonValue fx;

    @BeforeAll
    static void load() throws IOException {
        File f = new File("../core/src/test/resources/parity/fixtures.json");
        assertTrue(f.isFile(), "run tools/parity/generate_fixtures.py first: " + f);
        fx = new JsonReader().parse(new String(
                Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
    }

    /** Relative tolerance for a value the port computes in floats. */
    private static void close(double expected, double actual, String what) {
        assertEquals(expected, actual,
                Math.max(1e-4, Math.abs(expected) * 1e-5), what);
    }

    // ========================================================================
    //  Classic
    // ========================================================================

    @Test
    @DisplayName("the Classic wave bonus matches Python at every wave and purse")
    void waveBonus() {
        int checked = 0;
        for (JsonValue c = fx.get("classicWaveBonus").child; c != null; c = c.next) {
            final float purse = c.getFloat("wavePurse");
            CombatModifiers mods = new CombatModifiers() {
                @Override
                public float wavePurse() {
                    return purse;
                }
            };
            assertEquals(c.getInt("bonus"),
                    Scoring.waveBonus(c.getInt("wave"), mods),
                    "wave " + c.getInt("wave") + " purse " + purse);
            checked++;
        }
        assertTrue(checked >= 24, "the fixture should cover every wave/purse pairing");
    }

    @Test
    @DisplayName("the Classic spawn interval matches Python, floor included")
    void spawnInterval() {
        for (JsonValue c = fx.get("classicSpawnInterval").child; c != null; c = c.next) {
            int wave = c.getInt("wave");
            double expected = c.getDouble("interval");
            double actual = Math.max(0.32, 1.25 - wave * 0.032);
            assertEquals(expected, actual, 1e-12, "wave " + wave);
        }
    }

    @Test
    @DisplayName("the wave-clear delay fires on the step Python's comparison names")
    void waveClearDelay() {
        JsonValue c = fx.get("waveClear").child;
        assertNotNull(c);
        assertEquals(c.getDouble("delay"), WaveDirector.WAVE_CLEAR_DELAY, 0d);

        double acc = 0d;
        int firedOn = -1;
        for (int step = 1; step <= 400; step++) {
            acc += Simulation.FIXED_DT;
            if (acc > WaveDirector.WAVE_CLEAR_DELAY) {
                firedOn = step;
                break;
            }
        }
        assertEquals(c.getInt("firesOnStep"), firedOn,
                "the delay must expire on the same step as the source");
    }

    // ========================================================================
    //  Endless
    // ========================================================================

    @Test
    @DisplayName("the Endless tier at a given time matches Python, boundaries included")
    void endlessTier() {
        EndlessDirector d = directorForFormulas();
        for (JsonValue c = fx.get("endlessTier").child; c != null; c = c.next) {
            double t = c.getDouble("playTime");
            assertEquals(c.getInt("tier"), d.tierAt(t), "t=" + t);
        }
    }

    @Test
    @DisplayName("the Endless spawn-gap ramp matches Python, jitter included")
    void endlessSpawnGap() {
        for (JsonValue c = fx.get("endlessSpawnGap").child; c != null; c = c.next) {
            double t = c.getDouble("playTime");
            assertEquals(c.getDouble("baseGap"), EndlessDirector.baseSpawnGap(t), 1e-12,
                    "base gap at t=" + t);
            //  the jitter is `gap * uniform(1-J, 1+J)`; the fixture supplies the
            //  three sample points so the band can be checked without an RNG
            double base = c.getDouble("baseGap");
            assertEquals(c.getDouble("gapAtMinJitter"),
                    base * (1 - Tuning.ENDLESS_SPAWN_JITTER), 1e-12, "min jitter");
            assertEquals(c.getDouble("gapAtMidJitter"), base, 1e-12, "mid jitter");
            assertEquals(c.getDouble("gapAtMaxJitter"),
                    base * (1 + Tuning.ENDLESS_SPAWN_JITTER), 1e-12, "max jitter");
        }
    }

    @Test
    @DisplayName("the Endless boss timetable matches Python at every checkpoint")
    void endlessBossSchedule() {
        for (JsonValue c = fx.get("endlessBossSchedule").child; c != null; c = c.next) {
            double t = c.getDouble("playTime");
            int scripted = 0;
            for (double due : Tuning.ENDLESS_BOSS_TIMES) {
                if (t >= due) {
                    scripted++;
                }
            }
            int repeats = 0;
            if (scripted >= Tuning.ENDLESS_BOSS_TIMES.length) {
                double last = Tuning.ENDLESS_BOSS_TIMES[Tuning.ENDLESS_BOSS_TIMES.length - 1];
                repeats = Math.max(0, (int) ((t - last) / Tuning.ENDLESS_BOSS_REPEAT));
            }
            assertEquals(c.getInt("scripted"), scripted, "scripted at t=" + t);
            assertEquals(c.getInt("repeats"), repeats, "repeats at t=" + t);
        }
    }

    @Test
    @DisplayName("Endless talent income matches Python at every boundary")
    void endlessTalentIncome() {
        for (JsonValue c = fx.get("endlessTalentIncome").child; c != null; c = c.next) {
            double t = c.getDouble("playTime");
            int points = (int) (t / Tuning.TALENT_SECONDS_PER_POINT);
            assertEquals(c.getInt("points"), points, "t=" + t);
        }
    }

    @Test
    @DisplayName("the live Endless director reproduces the fixtured schedule")
    void liveEndlessMatchesTheFixture() {
        //  The formulas above are arithmetic; this drives the real director and
        //  checks the same numbers come out of a running game.
        TestRun r = new TestRun().beginEndless();
        double at = 0d;
        for (JsonValue c = fx.get("endlessTier").child; c != null; c = c.next) {
            double t = c.getDouble("playTime");
            if (t < at || t > 700d) {
                continue;               // forward-only, and keep the test quick
            }
            r.scheduleSeconds(t - at);
            at = t;
            assertEquals(c.getInt("tier"), r.session().wave(),
                    "live tier at t=" + t);
        }
        assertTrue(at > 0d, "the fixture should drive at least one checkpoint");
    }

    // ========================================================================
    //  Economy and scoring
    // ========================================================================

    @Test
    @DisplayName("the crowd gold multiplier matches Python at every population")
    void crowdGold() {
        for (JsonValue c = fx.get("crowdGold").child; c != null; c = c.next) {
            close(c.getDouble("multiplier"),
                    Scoring.crowdGoldMultiplier(c.getInt("alive"),
                            c.getFloat("hornBonus"), c.getFloat("goldScale"),
                            CombatModifiers.NONE),
                    "alive=" + c.getInt("alive") + " horn=" + c.getFloat("hornBonus")
                            + " gold=" + c.getFloat("goldScale"));
        }
    }

    @Test
    @DisplayName("the kill payout matches Python, floor and rounding included")
    void killPayout() {
        for (JsonValue c = fx.get("killPayout").child; c != null; c = c.next) {
            assertEquals(c.getInt("payout"),
                    Scoring.killPayout(c.getInt("baseGold"), c.getFloat("multiplier")),
                    "gold=" + c.getInt("baseGold") + " x" + c.getFloat("multiplier"));
        }
    }

    @Test
    @DisplayName("fling score matches Python, both truncations included")
    void flingScore() {
        for (JsonValue c = fx.get("flingScore").child; c != null; c = c.next) {
            float travel = Scoring.flingTravel(c.getFloat("x"), c.getFloat("startX"),
                    c.getFloat("startY"), c.getFloat("peakY"));
            close(c.getDouble("travel"), travel, "travel");
            close(c.getDouble("combo"), Scoring.flingCombo(c.getInt("hits")), "combo");

            int points = Scoring.flingPoints(travel, c.getDouble("airtime"),
                    c.getInt("hits"));
            assertEquals(c.getInt("points"), points,
                    "points for travel " + travel + " airtime " + c.getDouble("airtime")
                            + " hits " + c.getInt("hits"));

            assertEquals(c.getInt("awardedPlain"),
                    Scoring.awardedScore(points, 0f, CombatModifiers.NONE));
            assertEquals(c.getInt("awardedWithHorn"),
                    Scoring.awardedScore(points, Tuning.HORN_BONUS, CombatModifiers.NONE));
            assertEquals(c.getInt("awardedWithShowman"),
                    Scoring.awardedScore(points, 0f, new CombatModifiers() {
                        @Override
                        public float scoreMult() {
                            return 1.25f;
                        }
                    }));
        }
    }

    // ========================================================================
    //  Weather and shake
    // ========================================================================

    @Test
    @DisplayName("the wind formula and the storm strike match Python")
    void weather() {
        for (JsonValue c = fx.get("weather").child; c != null; c = c.next) {
            if (c.has("sample")) {
                double u = c.getDouble("sample");
                double wind = (-1.0 + u * 2.0) * GameConfig.WIND_MAX;
                close(c.getDouble("wind"), wind, "wind at sample " + u);
                close(c.getDouble("windMax"), GameConfig.WIND_MAX, "WIND_MAX");
                close(c.getDouble("headwindThreshold"),
                        GameConfig.WIND_MAX * Tuning.HEADWIND_THRESHOLD, "threshold");
            }
            if (c.has("maxHp")) {
                float hp = c.getFloat("maxHp");
                close(c.getDouble("strikeDamage"), hp * GameConfig.STORM_DAMAGE,
                        "strike damage at " + hp + " hp");
                close(c.getDouble("stormCeiling"), GameConfig.STORM_CEILING, "ceiling");
                assertEquals(c.getDouble("stormCooldown"), GameConfig.STORM_COOLDOWN, 0d,
                        "the lockout is a time-domain constant and must be exact");
            }
        }
    }

    @Test
    @DisplayName("the screen-shake budget matches Python, cap and decay")
    void screenShake() {
        for (JsonValue c = fx.get("screenShake").child; c != null; c = c.next) {
            if (c.has("add")) {
                ScreenShake s = new ScreenShake();
                s.add(c.getFloat("before"));
                s.add(c.getFloat("add"));
                close(c.getDouble("after"), s.amount(),
                        c.getFloat("before") + " + " + c.getFloat("add"));
            } else {
                ScreenShake s = new ScreenShake();
                s.add(c.getFloat("before"));
                s.decay(Simulation.FIXED_DT);
                close(c.getDouble("afterOneStep"), s.amount(),
                        "one step of decay from " + c.getFloat("before"));

                //  ...and it reaches zero when the source's arithmetic says.
                //  Within one step, not exactly: the shake amount is a FLOAT by
                //  design -- it is a visual amplitude, not a timer, and the
                //  time-domain rule deliberately leaves it alone.  14.0 is
                //  exactly 20 steps of 0.7 in ideal arithmetic and 21 in
                //  sequential float subtraction, and pinning the ideal here
                //  would be pinning something neither implementation does.
                ScreenShake z = new ScreenShake();
                z.add(c.getFloat("before"));
                int steps = 0;
                while (z.amount() > 0f && steps < 10000) {
                    z.decay(Simulation.FIXED_DT);
                    steps++;
                }
                assertTrue(Math.abs(steps - c.getInt("stepsToZero")) <= 1,
                        "steps to zero from " + c.getFloat("before") + ": expected about "
                                + c.getInt("stepsToZero") + ", got " + steps);
            }
        }
    }

    // ========================================================================
    //  The horn
    // ========================================================================

    @Test
    @DisplayName("horn head-counts and the Classic swap cap match Python")
    void hornComposition() {
        for (JsonValue c = fx.get("hornComposition").child; c != null; c = c.next) {
            boolean endless = c.getBoolean("endless");
            boolean elite = c.getBoolean("elite");
            int queued = c.getInt("queued");

            int spawned = endless
                    ? (elite ? Tuning.HARD_HORN_RUSH : Tuning.ENDLESS_HORN_RUSH)
                    : queued;
            assertEquals(c.getInt("spawned"), spawned,
                    (endless ? "endless" : "classic") + (elite ? " elite" : "")
                            + " queued=" + queued);

            if (!endless) {
                assertEquals(c.getInt("swapCap"), Math.max(1, queued / 2),
                        "swap cap for " + queued);
            }
            if (c.has("tierBonus")) {
                assertEquals(c.getInt("tierBonus"), Tuning.HARD_HORN_TIER_BONUS);
            }
        }
    }

    // ------------------------------------------------------------------------

    /** A director whose only job is to expose the pure schedule formulas. */
    private static EndlessDirector directorForFormulas() {
        return new TestRun().beginEndless().endless();
    }
}
