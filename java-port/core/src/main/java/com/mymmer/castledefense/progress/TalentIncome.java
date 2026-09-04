package com.mymmer.castledefense.progress;

/**
 * Where talent points go when a run earns them.
 *
 * <p>Phase 8 owns <b>when</b> a point is awarded — per wave in Classic, per
 * minute survived in Endless — and nothing else. Phase 9 owns the tree that
 * spends them. This interface is the whole seam between the two, deliberately
 * one method wide, so that implementing the tree later requires no change to
 * either director.
 *
 * <p>Not an event bus and not a listener list: exactly one sink, set once when
 * the run is built.
 */
public interface TalentIncome {

    /**
     * Awards points.
     *
     * @param points how many; always positive
     * @param reason a stable id for the trace and, later, the notification —
     *               {@code "wave"} or {@code "endless-minute"}, never a
     *               user-facing sentence
     */
    void award(int points, String reason);

    /** A sink that counts, for tests and for a run with no tree wired up yet. */
    final class Counter implements TalentIncome {

        private int points;
        private int awards;
        private String lastReason = "";

        @Override
        public void award(int points, String reason) {
            this.points += points;
            this.awards++;
            this.lastReason = reason == null ? "" : reason;
        }

        public int points() {
            return points;
        }

        public int awards() {
            return awards;
        }

        public String lastReason() {
            return lastReason;
        }
    }
}
