package com.mymmer.castledefense.bench;

import com.badlogic.gdx.utils.Array;
import com.mymmer.castledefense.testsupport.GlStub;

/**
 * Runs the simulation benchmark and prints a Markdown table.
 *
 * <pre>
 *   ./gradlew :core:benchmark
 * </pre>
 *
 * <p>Deliberately a {@code main}, not a JUnit test. A timing assertion fails on a
 * busy laptop and passes on an idle one, so it teaches people to ignore the
 * build; these numbers are read by a person and compared against a recorded
 * baseline instead.
 *
 * <p>Nothing here renders. Simulation and rendering are measured separately
 * because they cost separately and are optimised separately.
 */
public final class BenchmarkMain {

    private BenchmarkMain() {
    }

    public static void main(String[] args) {
        GlStub.install();
        try {
            System.out.println("# Simulation benchmark");
            System.out.println();
            System.out.println("| | |");
            System.out.println("|---|---|");
            System.out.println("| JVM | " + System.getProperty("java.vm.name")
                    + " " + System.getProperty("java.version") + " |");
            System.out.println("| OS | " + System.getProperty("os.name")
                    + " " + System.getProperty("os.arch") + " |");
            System.out.println("| cores | " + Runtime.getRuntime().availableProcessors()
                    + " |");
            System.out.println("| seed | " + BenchmarkScenarios.SEED + " |");
            System.out.println("| step | fixed 1/60 s, no sleeping |");
            System.out.println();

            Array<Benchmark> benchmarks = BenchmarkScenarios.all();
            System.out.println(Benchmark.header());
            long worst = 0;
            String worstName = "";
            for (int i = 0; i < benchmarks.size; i++) {
                Benchmark.Result r = benchmarks.get(i).run();
                System.out.println(r.row());
                System.out.flush();
                if (r.p99Micros > worst) {
                    worst = (long) r.p99Micros;
                    worstName = r.scenario;
                }
            }
            System.out.println();
            //  A 60 Hz step has 16667 us of budget for EVERYTHING -- simulation,
            //  rendering and the platform.  A simulation p99 anywhere near that
            //  is a problem on a phone even when a desktop hides it.
            System.out.println("Worst p99: " + worstName + " at " + worst
                    + " us, which is " + String.format(java.util.Locale.ROOT,
                            "%.1f%%", worst / 16667.0 * 100.0)
                    + " of a 60 Hz step's total budget.");
        } finally {
            GlStub.uninstall();
        }
    }
}
