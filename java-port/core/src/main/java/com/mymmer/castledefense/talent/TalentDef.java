package com.mymmer.castledefense.talent;

/**
 * One talent node, as defined by the data.
 *
 * <p>Immutable. Identity is {@link #id}, a stable semantic string — never a
 * branch index, a grid position or a display name. The display name and the
 * description are localisation keys derived from the id, so translating the game
 * cannot change what a save or a test refers to.
 */
public final class TalentDef {

    /** Stable semantic id: {@code "light_fingers"}-style, from the source. */
    public final String id;
    public final TalentBranch branch;
    /**
     * Points that must already be in this node's <b>own branch</b> before it
     * unlocks.
     *
     * <p>Not a row index and not a prerequisite node. A branch deepens as you
     * commit points to it, whichever of its nodes you spent them on — which is
     * why the graph has no edges and cannot have a cycle.
     */
    public final int tier;
    public final int maxRank;
    /** Effect magnitude per rank. {@code value = rank * perRank}. */
    public final double perRank;
    public final TalentEffect effect;

    /**
     * How the description's value is written, from the data.
     *
     * <p>The source formats each talent's {@code {v}} itself -- 28 as
     * {@code .0%}, one as {@code .1%}, four as {@code .0f}, one as
     * {@code .1f}, and two have no value at all. A rule like "below 1.0 means
     * a percentage" gets 35 of them right and {@code spikedot} (perRank 0.9,
     * written as a plain number) wrong, which would print "90%" where the game
     * means "0.9 damage a tick". So the intent is carried in the data instead
     * of guessed from the number.
     */
    public enum ValueFormat { PERCENT, PERCENT1, NUMBER, DECIMAL, NONE }

    public final ValueFormat format;

    /** The value at a rank, written the way the source writes it. */
    public String formatValue(int rank) {
        double v = valueAt(Math.max(1, rank));
        switch (format) {
            case PERCENT:  return Math.round(v * 100d) + "%";
            case PERCENT1: return String.format(java.util.Locale.ROOT, "%.1f%%", v * 100d);
            case NUMBER:   return String.valueOf(Math.round(v));
            case DECIMAL:  return String.format(java.util.Locale.ROOT, "%.1f", v);
            default:       return "";
        }
    }

    TalentDef(String id, TalentBranch branch, int tier, int maxRank,
              double perRank, TalentEffect effect, ValueFormat format) {
        this.id = id;
        this.branch = branch;
        this.tier = tier;
        this.maxRank = maxRank;
        this.perRank = perRank;
        this.effect = effect;
        this.format = format != null ? format : ValueFormat.NONE;
    }

    /** Localisation key for the display name. Never gameplay identity. */
    public String nameKey() {
        return "talent." + id + ".name";
    }

    /** Localisation key for the description. */
    public String descriptionKey() {
        return "talent." + id + ".desc";
    }

    /** The magnitude at a given rank. */
    public double valueAt(int rank) {
        return rank * perRank;
    }

    @Override
    public String toString() {
        return id + "(" + branch.id() + " t" + tier + " x" + maxRank + ")";
    }
}
