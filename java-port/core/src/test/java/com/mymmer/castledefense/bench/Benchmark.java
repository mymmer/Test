package com.mymmer.castledefense.bench;

import com.badlogic.gdx.utils.Array;
import com.mymmer.castledefense.game.GameMode;
import com.mymmer.castledefense.progress.TestRun;

/**
 * A repeatable measurement of the simulation, with no rendering in it.
 *
 * <h2>What this measures, and what it does not</h2>
 *
 * <p><b>Simulation only.</b> It advances the real {@code RunWorld} by fixed steps
 * and times them. There is no GL context, no draw call and no frame; render cost
 * is measured separately, on a real backend, because that is the only place it
 * exists.
 *
 * <p>Every scenario runs from a <b>fixed seed</b> with <b>fixed-step</b> input.
 * Nothing sleeps and nothing consults a wall clock for pacing — the only wall
 * clock reading is the stopwatch around the work, which is what makes two runs
 * of the same scenario comparable.
 *
 * <h2>Why it is a test-source class</h2>
 *
 * <p>It builds controlled game states, which is {@code VisualScenarios}' job on
 * the main source set — but a benchmark has no business shipping in the game, and
 * it needs the test set's {@code TestRun} harness to drive a world without a
 * launcher. Running it is a Gradle task, not a JUnit assertion: a timing that
 * fails the build on a busy machine is a timing everyone learns to ignore.
 */
public final class Benchmark {

    /** One scenario's numbers. */
    public static final class Result {
        public final String scenario;
        public final long seed;
        public final int steps;
        /** Nanoseconds of simulation, total and per step. */
        public final long totalNanos;
        public final double meanMicros;
        public final double p50Micros;
        public final double p95Micros;
        public final double p99Micros;
        public final double maxMicros;
        /** What was on the field at the end. */
        public final int enemies;
        public final int projectiles;
        public final int items;
        public final int bosses;
        /** Bytes allocated across the measured window, where measurable. */
        public final long allocatedBytes;

        Result(String scenario, long seed, int steps, long totalNanos,
               double[] sorted, int enemies, int projectiles, int items,
               int bosses, long allocatedBytes) {
            this.scenario = scenario;
            this.seed = seed;
            this.steps = steps;
            this.totalNanos = totalNanos;
            this.meanMicros = totalNanos / 1000.0 / Math.max(1, steps);
            this.p50Micros = percentile(sorted, 0.50);
            this.p95Micros = percentile(sorted, 0.95);
            this.p99Micros = percentile(sorted, 0.99);
            this.maxMicros = sorted.length == 0 ? 0 : sorted[sorted.length - 1];
            this.enemies = enemies;
            this.projectiles = projectiles;
            this.items = items;
            this.bosses = bosses;
            this.allocatedBytes = allocatedBytes;
        }

        private static double percentile(double[] sorted, double q) {
            if (sorted.length == 0) {
                return 0;
            }
            int i = (int) Math.min(sorted.length - 1, Math.round(q * (sorted.length - 1)));
            return sorted[i];
        }

        /** One row of the report table. */
        public String row() {
            return String.format(java.util.Locale.ROOT,
                    "| %-22s | %6d | %8.1f | %8.1f | %8.1f | %8.1f | %5d | %5d | %5d |",
                    scenario, steps, meanMicros, p50Micros, p95Micros, p99Micros,
                    enemies, projectiles, bosses);
        }
    }

    /** Builds one scenario's world. Named so the report reads sensibly. */
    public interface Scenario {
        void build(TestRun t);
    }

    private final String name;
    private final Scenario scenario;
    private final int warmupSteps;
    private final int measuredSteps;
    private final long seed;

    public Benchmark(String name, Scenario scenario, long seed, int warmupSteps,
                     int measuredSteps) {
        this.name = name;
        this.scenario = scenario;
        this.seed = seed;
        this.warmupSteps = warmupSteps;
        this.measuredSteps = measuredSteps;
    }

    /**
     * Runs the scenario and times the measured window.
     *
     * <p>The warm-up is not decoration: the JIT needs several thousand steps
     * before {@code RunWorld.step} settles, and a measurement taken before that
     * is a measurement of the interpreter.
     */
    public Result run() {
        TestRun t = new TestRun(seed);
        t.begin(GameMode.ENDLESS, "normal");
        scenario.build(t);

        for (int i = 0; i < warmupSteps; i++) {
            t.step();
        }

        double[] micros = new double[measuredSteps];
        long allocBefore = allocatedBytes();
        long start = System.nanoTime();
        for (int i = 0; i < measuredSteps; i++) {
            long s = System.nanoTime();
            t.step();
            micros[i] = (System.nanoTime() - s) / 1000.0;
        }
        long total = System.nanoTime() - start;
        long allocAfter = allocatedBytes();

        java.util.Arrays.sort(micros);
        return new Result(name, seed, measuredSteps, total, micros,
                t.aliveEnemies(), t.run.projectiles().size(),
                t.run.droppedItems().size(), t.run.bossRegistry().liveBosses().size,
                allocBefore < 0 || allocAfter < 0 ? -1 : allocAfter - allocBefore);
    }

    /**
     * Bytes this thread has allocated, or -1 where the JVM will not say.
     *
     * <p>{@code com.sun.management.ThreadMXBean} exposes it on HotSpot. Reached
     * reflectively so the benchmark still runs where it is absent rather than
     * failing to load.
     */
    private static long allocatedBytes() {
        try {
            java.lang.management.ThreadMXBean bean =
                    java.lang.management.ManagementFactory.getThreadMXBean();
            java.lang.reflect.Method m = bean.getClass()
                    .getMethod("getThreadAllocatedBytes", long.class);
            m.setAccessible(true);
            return (Long) m.invoke(bean, Thread.currentThread().getId());
        } catch (Exception e) {                         // noqa
            return -1L;
        }
    }

    // ========================================================================
    //  Reporting
    // ========================================================================

    public static String header() {
        return "| scenario               |  steps |  mean us |   p50 us |   p95 us |"
                + "   p99 us | enemy |  proj | bosses |\n"
                + "|------------------------|--------|----------|----------|----------|"
                + "----------|-------|-------|--------|";
    }

    /** Runs a list of benchmarks and returns a Markdown table. */
    public static String report(Array<Benchmark> benchmarks) {
        StringBuilder out = new StringBuilder(header()).append('\n');
        for (int i = 0; i < benchmarks.size; i++) {
            Result r = benchmarks.get(i).run();
            out.append(r.row()).append('\n');
        }
        return out.toString();
    }
}
