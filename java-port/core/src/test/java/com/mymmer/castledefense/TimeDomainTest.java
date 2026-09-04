package com.mymmer.castledefense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.badlogic.gdx.utils.Array;
import com.mymmer.castledefense.boss.Boss;
import com.mymmer.castledefense.boss.Dragon;
import com.mymmer.castledefense.boss.LichLord;
import com.mymmer.castledefense.config.GameConfig;
import com.mymmer.castledefense.config.Tuning;
import com.mymmer.castledefense.defence.DefenceTower;
import com.mymmer.castledefense.defence.Outpost;
import com.mymmer.castledefense.defence.Projectile;
import com.mymmer.castledefense.enemy.Enemy;
import com.mymmer.castledefense.enemy.FriendlySkeleton;
import com.mymmer.castledefense.enemy.TreasureGoblin;
import com.mymmer.castledefense.game.GameWorld;
import com.mymmer.castledefense.game.Simulation;
import com.mymmer.castledefense.interaction.CursorInteraction;
import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The one cross-cutting rule of the simulation:
 *
 * <pre>
 *   Gameplay time  = double precision.
 *   Spatial maths  = float where appropriate.
 * </pre>
 *
 * <h2>Why this file exists</h2>
 *
 * <p>The rule was not adopted for tidiness. With float timers a Hard Dragon
 * breathed <b>sixteen</b> fireballs per breath where the Python source breathes
 * fifteen, because {@code 1f/60f} is 0.016666668 — fractionally longer than a
 * true sixtieth — and eighty-odd subtractions of it walk a comparison across its
 * boundary. Three of the six fixtured breath configurations diverged, in both
 * directions. That class of bug is invisible in review, survives every unit test
 * that does not sit exactly on a boundary, and cannot be fixed by adjusting a
 * constant.
 *
 * <h2>What is guarded, and what deliberately is not</h2>
 *
 * <p>The important invariant is <b>behavioural</b>: the same configured duration
 * plus the same number of simulation steps must produce the same boundary
 * decision, whatever the duration is. Most of this file tests that directly.
 *
 * <p>There is also a small <b>explicit</b> list of the authoritative timer
 * accessors, checked to still return {@code double}. It is a named list on
 * purpose. A reflective rule that rejected every {@code float} field whose name
 * looked temporal would be worse than nothing: it would flag {@code hurtFlash},
 * {@code recoil}, {@code aura} and {@code trapGlow}, which are drawing state and
 * are supposed to be floats, and it would miss {@code shield} and {@code reel},
 * which are not obviously temporal and are supposed to be doubles. Naming them
 * is the point — the list is the contract.
 */
class TimeDomainTest {

    // ========================================================================
    //  Behavioural: the boundary is where the source says it is
    // ========================================================================

    /**
     * A duration expires on a stable step, within one step of the ideal.
     *
     * <p>Note what is <em>not</em> claimed: that the countdown lands exactly on
     * the mathematical step. It does not, and neither does the Python source.
     * Neither {@code 0.15} nor {@code 1.0/60.0} is representable, so nine
     * sequential subtractions leave 0.15 s a hair above zero and it expires on
     * the tenth step — in Python too, which is why the fixtures agree. Asserting
     * the ideal would be asserting something the reference implementation also
     * fails.
     *
     * <p>What is claimed is what actually matters: the boundary is
     * <b>deterministic</b> and it is <b>within one step</b> of the ideal, at
     * every scale from a 50 ms cooldown to an hour-long Endless clock. Exact
     * agreement with Python is proven separately, by the parity fixtures.
     */
    @Test
    @DisplayName("a duration expires on a stable step, within one step of the ideal")
    void durationsExpireOnAStableStep() {
        double[] durations = {
            0.05, 0.075, 0.15, 0.5, 1.25, 2.6, 3.1,     // gameplay cooldowns
            1.0, 30.0, 60.0, 120.0, 360.0, 3600.0,      // the Endless timetable
        };
        for (double duration : durations) {
            long ideal = Math.round(duration * 60.0);
            long firedOn = stepsToExpire(duration);
            assertTrue(firedOn > 0, duration + "s never expired");
            assertTrue(Math.abs(firedOn - ideal) <= 1,
                    "a " + duration + "s timer expired on step " + firedOn
                            + ", more than one step from the ideal " + ideal);
            //  deterministic: the same duration always resolves the same way,
            //  which is what lets a fixture pin it at all
            assertEquals(firedOn, stepsToExpire(duration),
                    duration + "s must expire on the same step every time");
        }
    }

    /** Steps a countdown of {@code duration} takes to reach {@code <= 0}. */
    private static long stepsToExpire(double duration) {
        double timer = duration;
        for (long step = 1; step <= 400_000L; step++) {
            timer -= Simulation.FIXED_DT;
            if (timer <= 0d) {
                return step;
            }
        }
        return -1;
    }

    /**
     * A repeating timer fires on an <b>identical</b> cadence for an hour.
     *
     * <p>This is the invariant in its most general form, and the one that holds
     * even for a duration that is not a whole number of steps: reset to the
     * configured value and every cycle is bit-for-bit the same cycle, so the gap
     * between firings never varies. A timer that accumulated its error would
     * alternate 4, 5, 4, 5 steps for a 4.5-step interval and slowly slide;
     * this one takes 5 every time, for ever.
     *
     * <p>Reload timers, spawn gaps and boss schedules are all this shape.
     */
    @Test
    @DisplayName("a repeating timer keeps an identical cadence over an hour of simulation")
    void repeatingTimersKeepAnIdenticalCadence() {
        double[] intervals = {0.075, 0.15, 0.5, 2.6, 3.1, 30.0};
        long hour = 216_000L;                       // 60 minutes at 1/60 s
        for (double interval : intervals) {
            double timer = interval;
            long lastFire = 0;
            long gap = -1;
            long fires = 0;
            for (long step = 1; step <= hour; step++) {
                timer -= Simulation.FIXED_DT;
                if (timer > 0d) {
                    continue;
                }
                timer = interval;                   // reset to the configured value
                fires++;
                if (fires > 1) {
                    long thisGap = step - lastFire;
                    if (gap < 0) {
                        gap = thisGap;
                    }
                    assertEquals(gap, thisGap,
                            interval + "s interval drifted: firing " + fires
                                    + " came " + thisGap + " steps after the last, not " + gap);
                }
                lastFire = step;
            }
            assertTrue(fires > 1, interval + "s should fire repeatedly within an hour");
            //  and the steady-state gap is the same one a single countdown
            //  produces: resetting to the configured value means the 48,000th
            //  cycle is arithmetically identical to the first
            assertEquals(stepsToExpire(interval), gap,
                    interval + "s drifted away from its own first cycle");
        }
    }

    /** The clock itself: 216,000 steps is 3600.0 s, to the last bit. */
    @Test
    @DisplayName("an hour of simulation reads exactly 3600 seconds")
    void theClockIsExactOverAnHour() {
        assertEquals(3600.0, Simulation.secondsForSteps(216_000L), 0.0);
        assertEquals(60.0, Simulation.secondsForSteps(3_600L), 0.0);
        assertEquals(216_000L, Simulation.stepsForSeconds(3600.0));
    }

    /**
     * The counter-example, pinned locally, in the exact shape that shipped.
     *
     * <p>This is the Hard Dragon breath reduced to arithmetic: a 1.25 s stream
     * with a fireball every 0.075 s. Fifteen in doubles, sixteen in floats. The
     * test exists so that the reason for the rule cannot be forgotten, and so
     * that "just use floats, it's only a timer" fails a build rather than an
     * argument.
     */
    @Test
    @DisplayName("the shipped Hard breath is 15 in doubles and 16 in floats")
    void floatTimersMissTheBoundary() {
        assertEquals(15, streamCount(1.25, 0.075), "double timers: fifteen, as in Python");
        assertEquals(16, streamCountFloat(1.25f, 0.075f), "float timers: sixteen, the bug");
    }

    /** The production cadence loop, in the time domain. */
    private static int streamCount(double duration, double interval) {
        double remaining = duration;
        double shot = 0d;
        int fired = 0;
        while (remaining > 0d) {
            remaining -= Simulation.FIXED_DT;
            shot -= Simulation.FIXED_DT;
            if (shot <= 0d) {
                shot = interval;
                fired++;
            }
        }
        return fired;
    }

    /** The same loop as it used to be written. Kept only as the counter-example. */
    private static int streamCountFloat(float duration, float interval) {
        float remaining = duration;
        float shot = 0f;
        int fired = 0;
        while (remaining > 0f) {
            remaining -= Simulation.PHYSICS_DT;
            shot -= Simulation.PHYSICS_DT;
            if (shot <= 0f) {
                shot = interval;
                fired++;
            }
        }
        return fired;
    }

    // ========================================================================
    //  Structural: the step is a double all the way down
    // ========================================================================

    @Test
    @DisplayName("the simulation hands gameplay the canonical double step")
    void theStepperTakesADouble() throws Exception {
        Method step = Simulation.Stepper.class.getMethod("step", double.class);
        assertEquals(void.class, step.getReturnType());
        assertEquals(double.class,
                GameWorld.StepListener.class.getMethod("onStep", GameWorld.class, double.class)
                        .getParameterTypes()[1]);
        //  and there must be no float overload left anywhere to fall into
        for (Method m : Simulation.Stepper.class.getMethods()) {
            if (m.getName().equals("step")) {
                assertEquals(double.class, m.getParameterTypes()[0],
                        "Stepper.step must take the canonical step, never the float one");
            }
        }
    }

    /**
     * The authoritative timers, named one by one.
     *
     * <p>Adding a timer to a gameplay class does not require adding it here.
     * Changing one of <em>these</em> back to a float does — and that is the
     * change this test is here to stop.
     */
    @Test
    @DisplayName("every authoritative gameplay timer is a double")
    void authoritativeTimersAreDoubles() {
        Object[][] timers = {
            //  defences
            {DefenceTower.class, "cooldown"},
            {DefenceTower.class, "reload"},
            {DefenceTower.class, "rebuildRemaining"},
            {DefenceTower.class, "stun"},
            {DefenceTower.class, "overchargeCd"},
            {Projectile.class, "life"},
            {Projectile.class, "stun"},
            {Outpost.class, "skeletonTimer"},
            {Outpost.class, "prisonerHit"},
            {Outpost.class, "gunReload"},
            //  enemies
            {Enemy.class, "stagger"},
            {TreasureGoblin.class, "escapeTimer"},
            {FriendlySkeleton.class, "life"},
            //  bosses
            {Boss.class, "regaliaCd"},
            {Boss.class, "intro"},
            {Boss.class, "fireScale"},
            {Dragon.class, "breathTimer"},
            {Dragon.class, "breathingRemaining"},
            {Dragon.class, "reel"},
            {LichLord.class, "disarm"},
            {LichLord.class, "shield"},
            //  the cursor
            {CursorInteraction.class, "grabCooldown"},
            {CursorInteraction.class, "grabCdRemaining"},
        };
        Array<String> wrong = new Array<>();
        for (Object[] entry : timers) {
            Class<?> owner = (Class<?>) entry[0];
            String getter = (String) entry[1];
            try {
                Method m = owner.getMethod(getter);
                if (m.getReturnType() != double.class) {
                    wrong.add(owner.getSimpleName() + "." + getter + "() returns "
                            + m.getReturnType().getSimpleName() + ", must be double");
                }
            } catch (NoSuchMethodException e) {
                wrong.add(owner.getSimpleName() + "." + getter + "() no longer exists — "
                        + "if it was renamed, rename it here too rather than deleting the row");
            }
        }
        if (wrong.size > 0) {
            fail("gameplay time is double precision; these are not:\n  "
                    + wrong.toString("\n  ")
                    + "\n\nSee java-port/docs/subsystems/SIMULATION.md, "
                    + "\"Gameplay time is double\".");
        }
    }

    /**
     * The configured durations are doubles too.
     *
     * <p>A double timer fed a float constant has already lost the precision
     * before the first step: {@code 0.15f * 0.5f} is not {@code 0.075}.
     */
    @Test
    @DisplayName("configured durations are declared as doubles, not float literals")
    void configuredDurationsAreDoubles() throws Exception {
        Object[][] fields = {
            {GameConfig.class, "GRAB_COOLDOWN"},
            {GameConfig.class, "STAFF_DISARM_TIME"},
            {GameConfig.class, "REGALIA_COOLDOWN"},
            {GameConfig.class, "REGALIA_CD_GROWTH"},
            {GameConfig.class, "BOUNCE_STAGGER"},
            {GameConfig.class, "STORM_COOLDOWN"},
            {GameConfig.class, "CLAW_STAGGER"},
            {GameConfig.class, "OVERCHARGE_COOLDOWN"},
            {GameConfig.class, "TRAP_SKELETON_RATE"},
            {GameConfig.class, "RIVAL_BOLT_RATE"},
            {Tuning.class, "ENDLESS_TIER_SECONDS"},
            {Tuning.class, "ENDLESS_SPAWN_START"},
            {Tuning.class, "ENDLESS_SPAWN_MIN"},
            {Tuning.class, "ENDLESS_SPAWN_RAMP"},
            {Tuning.class, "ENDLESS_SPAWN_JITTER"},
            {Tuning.class, "ENDLESS_BOSS_REPEAT"},
            {Tuning.class, "TALENT_SECONDS_PER_POINT"},
        };
        Array<String> wrong = new Array<>();
        for (Object[] entry : fields) {
            Class<?> owner = (Class<?>) entry[0];
            String name = (String) entry[1];
            Class<?> type = owner.getField(name).getType();
            if (type != double.class) {
                wrong.add(owner.getSimpleName() + "." + name + " is a "
                        + type.getSimpleName() + ", must be double");
            }
        }
        assertEquals(double.class, Tuning.class.getField("ENDLESS_BOSS_TIMES")
                .getType().getComponentType(), "the boss timetable is a double[]");
        if (wrong.size > 0) {
            fail("configured durations must be doubles:\n  " + wrong.toString("\n  "));
        }
    }

    /**
     * Nothing in production reaches for the float step.
     *
     * <p>{@code PHYSICS_DT} exists, and a spatial system is welcome to it — but
     * every one of them currently narrows the {@code dt} it was handed, at the
     * top of the method, where the narrowing is visible. If a class starts
     * reading the constant instead, that is the shape of the original bug
     * returning, so it is worth knowing about.
     */
    @Test
    @DisplayName("production code narrows the step at the call site, never imports the float one")
    void productionNeverReadsTheFloatStepConstant() {
        java.nio.file.Path root =
                new java.io.File("../core/src/main/java/com/mymmer/castledefense").toPath();
        Array<String> offenders = new Array<>();
        try (java.util.stream.Stream<java.nio.file.Path> files =
                     java.nio.file.Files.walk(root)) {
            files.filter(f -> f.toString().endsWith(".java"))
                 .filter(f -> !f.endsWith("Simulation.java"))
                 .forEach(f -> {
                     String text;
                     try {
                         text = new String(java.nio.file.Files.readAllBytes(f),
                                 java.nio.charset.StandardCharsets.UTF_8);
                     } catch (java.io.IOException e) {
                         throw new RuntimeException(e);
                     }
                     if (text.replaceAll("(?s)/\\*.*?\\*/", "")
                             .replaceAll("//[^\n]*", "")
                             .contains("PHYSICS_DT")) {
                         offenders.add(root.relativize(f).toString());
                     }
                 });
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
        if (offenders.size > 0) {
            fail("these read Simulation.PHYSICS_DT instead of narrowing the dt they were "
                    + "given:\n  " + offenders.toString("\n  ")
                    + "\n\nWrite `float fdt = (float) dt;` at the top of the method instead, "
                    + "so the narrowing is visible where it happens.");
        }
    }
}
