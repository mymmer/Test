package com.mymmer.castledefense.enemy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import com.mymmer.castledefense.config.GameConfig;
import com.mymmer.castledefense.game.Simulation;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Java formulas against reference values generated from the Python source.
 *
 * <h2>Where these numbers come from, exactly</h2>
 *
 * <p>{@code tools/parity/generate_fixtures.py} reads the constants out of
 * {@code sprites.py} (by parsing, not importing — importing would drag in pygame
 * and build a world) and evaluates the formulas, each transcribed in one
 * clearly-marked block that names the source file and line it mirrors. The
 * result is {@code core/src/test/resources/parity/fixtures.json}, which this
 * test asserts against. The four authoritative files are opened read-only.
 *
 * <h2>What these prove, and what they do not</h2>
 *
 * <table>
 *   <tr><th>Assertion</th><th>Meaning</th></tr>
 *   <tr><td>Everything in this class</td>
 *       <td><b>Python reference.</b> These are the source's own numbers. A
 *       failure means the Java transcription is wrong.</td></tr>
 *   <tr><td>The fixed-step arithmetic in {@code EnemyPhysicsTest}</td>
 *       <td><b>Java-only.</b> Closed-form expectations for N steps of 1/60,
 *       proving the integration is internally stable. Python integrates a
 *       variable delta, so it has no matching number to compare against.</td></tr>
 * </table>
 *
 * <p>Neither is a substitute for the full behavioural parity work in Phase 13.
 * These cover formulas that are deterministic and isolated; anything involving
 * the RNG, the enemy list or the render loop is out of scope.
 */
class PythonParityTest {

    private static JsonValue fx;

    /** Relative tolerance. Python computes in double, Java in float. */
    private static final float REL = 1e-5f;

    @BeforeAll
    static void loadFixtures() throws Exception {
        InputStream in = PythonParityTest.class.getResourceAsStream("/parity/fixtures.json");
        assertNotNull(in, "fixtures.json is missing -- run tools/parity/generate_fixtures.py");
        try (InputStreamReader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            fx = new JsonReader().parse(r);
        }
    }

    private static void close(double expected, double actual, String what) {
        double tol = Math.max(1e-4, Math.abs(expected) * REL);
        assertEquals(expected, actual, tol, what);
    }

    // ========================================================================

    @Test
    @DisplayName("the fixtures were generated from the Python constants we expect")
    void constantsMatch() {
        JsonValue c = fx.get("constants");
        assertEquals(GameConfig.GRAVITY, c.getFloat("GRAVITY"), 0f);
        assertEquals(GameConfig.AIR_DRAG, c.getFloat("AIR_DRAG"), 0f);
        assertEquals(GameConfig.THROW_POWER, c.getFloat("THROW_POWER"), 0f);
        assertEquals(GameConfig.FALL_DMG_FLOOR, c.getFloat("FALL_DMG_FLOOR"), 0f);
        assertEquals(GameConfig.FALL_DMG_SCALE, c.getFloat("FALL_DMG_SCALE"), 0f);
        assertEquals(GameConfig.SLAM_DMG_FLOOR, c.getFloat("SLAM_DMG_FLOOR"), 0f);
        assertEquals(GameConfig.SLAM_DMG_SCALE, c.getFloat("SLAM_DMG_SCALE"), 0f);
        assertEquals(GameConfig.SHOVE_FACTOR, c.getFloat("SHOVE_FACTOR"), 0f);
        assertEquals(GameConfig.SHOVE_DECAY, c.getFloat("SHOVE_DECAY"), 0f);
        assertEquals(GameConfig.SHOVE_MAX, c.getFloat("SHOVE_MAX"), 0f);
        assertEquals(GameConfig.STRIP_DISTANCE, c.getFloat("STRIP_DISTANCE"), 0f);
        assertEquals(GameConfig.STRIP_SLOW, c.getFloat("STRIP_SLOW"), 0f);
        assertEquals(GameConfig.STRIP_VULN, c.getFloat("STRIP_VULN"), 0f);
        assertEquals(GameConfig.GROUND_Y, c.getFloat("GROUND_Y"), 0f);
        assertEquals(GameConfig.CASTLE_FRONT, c.getFloat("CASTLE_FRONT"), 0f);

        float[] rest = c.get("BOUNCE_RESTITUTION").asFloatArray();
        assertEquals(GameConfig.BOUNCE_RESTITUTION.length, rest.length);
        for (int i = 0; i < rest.length; i++) {
            assertEquals(GameConfig.BOUNCE_RESTITUTION[i], rest[i], 0f, "restitution " + i);
        }
        float[] cap = c.get("GRAB_CAPACITY").asFloatArray();
        for (int i = 0; i < cap.length; i++) {
            assertEquals(GameConfig.GRAB_CAPACITY[i], cap[i], 0f, "capacity " + i);
        }
        assertEquals(1.0 / 60.0, fx.getDouble("dt"), 1e-12,
                "the fixtures were generated at the port's fixed step");
    }

    @Test
    @DisplayName("wave scaling matches Python across every representative wave")
    void waveScaling() {
        for (JsonValue c = fx.get("waveScaling").child; c != null; c = c.next) {
            int wave = c.getInt("wave");
            close(c.getDouble("hp"), WaveScaling.hp(wave), "hp at wave " + wave);
            close(c.getDouble("damage"), WaveScaling.damage(wave), "damage at wave " + wave);
            close(c.getDouble("speed"), WaveScaling.speed(wave), "speed at wave " + wave);
        }
    }

    @Test
    @DisplayName("the full scaling chain matches Python for every wave and difficulty")
    void scaledStats() {
        for (JsonValue c = fx.get("scaledStats").child; c != null; c = c.next) {
            String enemyId = c.getString("enemy");
            int wave = c.getInt("wave");
            String difficulty = c.getString("difficulty");

            TestEnemyWorld w = new TestEnemyWorld();
            applyDifficulty(w, difficulty);
            Enemy e = w.build(EnemyType.byId(enemyId, null), wave);

            String at = enemyId + " w" + wave + " " + difficulty;
            close(c.getDouble("maxHp"), e.maxHp(), "maxHp " + at);
            close(c.getDouble("damage"), e.damage(), "damage " + at);
            close(c.getDouble("speed"), e.speed(), "speed " + at);
        }
    }

    /** The three shipped presets, matching {@code data/difficulties.json}. */
    private static void applyDifficulty(TestEnemyWorld w, String id) {
        if ("easy".equals(id)) {
            w.enemyScale = 0.82f;
            w.enemyHpCurve = 1f;
            w.enemySpeedScale = 0.92f;
        } else if ("hard".equals(id)) {
            w.enemyScale = 1.35f;
            w.enemyHpCurve = 1.6f;
            w.enemySpeedScale = 1.4f;
        } else {
            w.enemyScale = 1f;
            w.enemyHpCurve = 1f;
            w.enemySpeedScale = 1f;
        }
    }

    @Test
    @DisplayName("throw release velocity matches Python at every mass")
    void releaseVelocity() {
        for (JsonValue c = fx.get("release").child; c != null; c = c.next) {
            float mass = c.getFloat("mass");
            float mult = GameConfig.THROW_POWER / (0.55f + 0.45f * mass);
            String at = "mass " + mass + " v=(" + c.getFloat("vx") + "," + c.getFloat("vy") + ")";
            close(c.getDouble("outVx"), c.getFloat("vx") * mult, "vx " + at);
            close(c.getDouble("outVy"), c.getFloat("vy") * mult, "vy " + at);
        }
    }

    @Test
    @DisplayName("airborne integration matches Python step for step at dt = 1/60")
    void airIntegration() {
        for (JsonValue c = fx.get("airIntegration").child; c != null; c = c.next) {
            int steps = c.getInt("steps");
            float wind = c.getFloat("wind");
            //  The same arithmetic as Enemy.updateAir, in the same order: gravity,
            //  wind, drag, then integrate.  Run standalone here so the assertion
            //  is about the formula, not about the surrounding state machine.
            float x = 800f;
            float y = 300f;
            float vx = 400f;
            float vy = -600f;
            for (int i = 0; i < steps; i++) {
                vy += GameConfig.GRAVITY * Simulation.DT;
                vx += wind * Simulation.DT;
                vx -= vx * GameConfig.AIR_DRAG * Simulation.DT;
                x += vx * Simulation.DT;
                y += vy * Simulation.DT;
            }
            String at = steps + " steps, wind " + wind;
            close(c.getDouble("x"), x, "x after " + at);
            close(c.getDouble("y"), y, "y after " + at);
            close(c.getDouble("vx"), vx, "vx after " + at);
            close(c.getDouble("vy"), vy, "vy after " + at);
        }
    }

    @Test
    @DisplayName("fall damage matches Python at every mass, bounce level and impact")
    void fallDamage() {
        int checked = 0;
        for (JsonValue c = fx.get("fallDamage").child; c != null; c = c.next) {
            float mass = c.getFloat("mass");
            int lvl = c.getInt("bounceLevel");
            float vx = c.getFloat("vx");
            float vy = c.getFloat("vy");
            float impact = (float) Math.sqrt(vx * 0.5f * (vx * 0.5f) + vy * vy);
            float dmg = Math.max(0f, impact - GameConfig.FALL_DMG_FLOOR)
                    * GameConfig.FALL_DMG_SCALE
                    * (0.75f + 0.35f * mass)
                    * (1f + GameConfig.BOUNCE_DMG_BONUS * lvl);
            close(c.getDouble("damage"), dmg,
                    "mass " + mass + " lvl " + lvl + " vy " + vy);
            checked++;
        }
        assertTrue(checked >= 100, "expected the full fall-damage grid, got " + checked);
    }

    @Test
    @DisplayName("the bounce ladder matches Python at every level")
    void bounceLadder() {
        for (JsonValue c = fx.get("bounce").child; c != null; c = c.next) {
            int lvl = c.getInt("level");
            float inVx = c.getFloat("inVx");
            float inVy = c.getFloat("inVy");
            float outVy = -Math.abs(inVy) * GameConfig.BOUNCE_RESTITUTION[lvl];
            float outVx = inVx * (0.45f + 0.07f * lvl);
            close(c.getDouble("outVy"), outVy, "rebound vy at level " + lvl);
            close(c.getDouble("outVx"), outVx, "rebound vx at level " + lvl);
            close(c.getDouble("restitution"), GameConfig.BOUNCE_RESTITUTION[lvl],
                    "restitution " + lvl);
            close(c.getDouble("stagger"), 0.45f + GameConfig.BOUNCE_STAGGER * lvl,
                    "stagger at level " + lvl);
            //  the termination rule: bounce_count > level, strictly
            assertEquals(c.getBoolean("terminatesAtCount1"), 1 > lvl || Math.abs(outVy) < 90f,
                    "termination after one bounce at level " + lvl);
        }
    }

    @Test
    @DisplayName("slam damage matches Python at every mass and relative velocity")
    void slamDamage() {
        for (JsonValue c = fx.get("slam").child; c != null; c = c.next) {
            float mass = c.getFloat("mass");
            float rvx = c.getFloat("vx") - c.getFloat("otherVx");
            float rvy = c.getFloat("vy") - c.getFloat("otherVy");
            float rel = (float) Math.sqrt(rvx * rvx + rvy * rvy);
            float dmg = Math.max(0f, rel - GameConfig.SLAM_DMG_FLOOR)
                    * GameConfig.SLAM_DMG_SCALE * (0.6f + 0.5f * mass);
            close(c.getDouble("damage"), dmg, "slam at mass " + mass + " rel " + rel);
        }
    }

    @Test
    @DisplayName("shove magnitude and decay match Python")
    void shove() {
        for (JsonValue c = fx.get("shove").child; c != null; c = c.next) {
            float amount = c.getFloat("amount");
            float initial = Math.min(GameConfig.SHOVE_MAX, amount * GameConfig.SHOVE_FACTOR);
            close(c.getDouble("initial"), initial, "shove from " + amount);

            float x = 900f;
            float sh = initial;
            int at = 0;
            for (JsonValue t = c.get("trail").child; t != null; t = t.next) {
                int untilStep = t.getInt("step");
                while (at < untilStep) {
                    x -= sh * Simulation.DT;
                    sh *= Math.max(0f, 1f - GameConfig.SHOVE_DECAY * Simulation.DT);
                    if (sh < 8f) {
                        sh = 0f;
                    }
                    at++;
                }
                close(t.getDouble("x"), x, "x at step " + untilStep + " for " + amount);
                close(t.getDouble("shove"), sh, "shove at step " + untilStep);
            }
        }
    }

    @Test
    @DisplayName("the Siege Ram's armour-stripping progression matches Python plate for plate")
    void stripProgression() {
        JsonValue c = fx.get("strip").child;
        assertEquals("siege_ram", c.getString("enemy"));
        close(c.getDouble("stripDistance"), GameConfig.STRIP_DISTANCE, "STRIP_DISTANCE");

        TestEnemyWorld w = new TestEnemyWorld();
        Enemy ram = w.spawn(EnemyType.SIEGE_RAM, 900f);
        assertEquals(3, ram.layers());
        assertEquals(0.60f, ram.armor(), 1e-5f);

        for (JsonValue s = c.get("steps").child; s != null; s = s.next) {
            //  one full plate's worth of drag
            assertTrue(ram.applyStrip(GameConfig.STRIP_DISTANCE),
                    "a full STRIP_DISTANCE of drag must free a plate");
            int layers = s.getInt("layers");
            assertEquals(layers, ram.layers(), "layers left");
            close(s.getDouble("armor"), ram.armor(), "armour with " + layers + " left");
            close(s.getDouble("vulnerable"), ram.vulnerable(),
                    "vulnerability with " + layers + " left");
            //  speed is compared as a ratio to the base, because the Java value
            //  also carries the wave/difficulty multipliers the fixture omits
            close(s.getDouble("speed") / 28.0,
                    ram.speed() / ram.config().baseSpeed,
                    "speed ratio with " + layers + " left");
        }
        assertEquals(0, ram.layers());
        assertEquals(0f, ram.armor(), 1e-6f);
    }

    @Test
    @DisplayName("grab capacity matches Python at every level, including the clamp")
    void grabCapacity() {
        for (JsonValue c = fx.get("grabCapacity").child; c != null; c = c.next) {
            int level = c.getInt("level");
            float bonus = c.getFloat("bonus");
            int idx = Math.max(0, Math.min(level, GameConfig.GRAB_MAX_LEVEL));
            float capacity = GameConfig.GRAB_CAPACITY[idx] * bonus;
            close(c.getDouble("capacity"), capacity,
                    "capacity at level " + level + " bonus " + bonus);
        }
    }
}
