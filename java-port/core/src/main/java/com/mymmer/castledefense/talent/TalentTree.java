package com.mymmer.castledefense.talent;

import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.ObjectIntMap;
import com.mymmer.castledefense.debug.NoOpSimulationTrace;
import com.mymmer.castledefense.debug.SimulationTrace;
import com.mymmer.castledefense.debug.TraceEvent;
import com.mymmer.castledefense.defence.CombatModifiers;
import com.mymmer.castledefense.progress.TalentIncome;

/**
 * Passive progression: points in, effects out.
 *
 * <p>Points are earned per wave (Classic) or per minute survived (Endless) and
 * spent on tier-gated nodes. <b>All of it is per-run</b> — see the persistence
 * note below.
 *
 * <h2>It is the modifier seam, not a thing systems reach into</h2>
 *
 * <p>{@code TalentTree implements CombatModifiers}, which is the interface every
 * gameplay system was already written against in Phases 5–8. So a tower asks its
 * context for {@code modifiers().towerRate()} exactly as it always did, and the
 * tree is simply what is behind that call now. Nothing in {@code enemy},
 * {@code boss}, {@code defence}, {@code shop} or {@code skill} names this class:
 *
 * <pre>
 *   TalentTree  ->  CombatModifiers  ->  gameplay systems
 * </pre>
 *
 * <p>There is deliberately no {@code enemy.getGame().talents...} path, which is
 * how the Python god object works and is the coupling this port exists to avoid.
 *
 * <h2>Live values, never a cached snapshot</h2>
 *
 * <p>Every modifier is computed from the current ranks on each call. Python does
 * the same — they are {@code @property} reads — and it removes a whole class of
 * bug by construction: there is no cached copy anywhere that a purchase could
 * forget to invalidate, so a talent bought mid-run is in effect on the very next
 * step for <em>every</em> system at once, not just the ones someone remembered to
 * refresh.
 *
 * <p>The cost is a multiply and a {@code min} per call. That is measured in
 * nanoseconds and none of these are in an inner loop over entities; the
 * alternative is a consistency problem that would be found in the field.
 *
 * <p>The two things that genuinely <em>are</em> captured are captured because the
 * source captures them: a projectile samples {@code critChance} and
 * {@code splashMult} once at construction, so a talent bought while a shot is in
 * the air does not retune that shot.
 *
 * <h2>Persistence</h2>
 *
 * <p><b>Nothing here is saved.</b> Python's {@code Settings} writes exactly
 * {@code muted}, {@code difficulty} and {@code high_score}; the tree is rebuilt
 * by {@code Game.reset()} on every new run. Points, ranks and everything derived
 * from them are run state. See {@code PERSISTENCE.md}.
 */
public final class TalentTree implements CombatModifiers {

    private final TalentTable table;
    private final ObjectIntMap<String> ranks = new ObjectIntMap<>();
    private final ObjectIntMap<String> branchPoints = new ObjectIntMap<>();

    private int points;
    private int earned;

    private SimulationTrace trace = NoOpSimulationTrace.INSTANCE;
    private Listener listener;

    /** Told when a point is awarded or a node bought, for banners and traces. */
    public interface Listener {
        void onPointsAwarded(int amount, String reason, int total);

        void onTalentPurchased(TalentDef talent, int newRank, int pointsLeft);
    }

    public TalentTree(TalentTable table) {
        if (table == null) {
            throw new IllegalArgumentException("table must not be null");
        }
        this.table = table;
        reset();
    }

    public void setTrace(SimulationTrace trace) {
        this.trace = trace != null ? trace : NoOpSimulationTrace.INSTANCE;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** Everything back to zero. Called for every new run. */
    public void reset() {
        ranks.clear();
        branchPoints.clear();
        for (TalentDef d : table.all()) {
            ranks.put(d.id, 0);
        }
        for (TalentBranch b : TalentBranch.values()) {
            branchPoints.put(b.id(), 0);
        }
        points = 0;
        earned = 0;
    }

    public TalentTable table() {
        return table;
    }

    // ========================================================================
    //  Points
    // ========================================================================

    /** Unspent points. */
    public int availablePoints() {
        return points;
    }

    /** Every point this run has ever earned, spent or not. */
    public int earnedPoints() {
        return earned;
    }

    /**
     * Awards points — Python {@code TalentTree.award}.
     *
     * <p>Non-positive awards do nothing at all, as in the source.
     */
    public void award(int amount, String reason) {
        if (amount <= 0) {
            return;
        }
        points += amount;
        earned += amount;
        if (trace.isEnabled()) {
            trace.event(TraceEvent.TALENT_POINT_AWARDED, 0L, 0L, amount, points,
                    reason == null ? "" : reason);
        }
        if (listener != null) {
            listener.onPointsAwarded(amount, reason == null ? "" : reason, points);
        }
    }

    /** The {@link TalentIncome} sink a director awards through. */
    public TalentIncome asIncome() {
        return this::award;
    }

    // ========================================================================
    //  Ranks and purchasing
    // ========================================================================

    public int rank(String id) {
        return ranks.get(id, 0);
    }

    public int maxRank(String id) {
        TalentDef d = table.get(id);
        return d == null ? 0 : d.maxRank;
    }

    /** Points spent in one branch. Python {@code branch_points}. */
    public int branchPoints(TalentBranch branch) {
        return branchPoints.get(branch.id(), 0);
    }

    /**
     * Is this node's tier gate open?
     *
     * <p>Python {@code unlocked}: {@code branch_points(branch) >= tier}. Note it
     * says nothing about points in hand or about the rank cap — a maxed node is
     * still "unlocked".
     */
    public boolean isUnlocked(String id) {
        TalentDef d = table.get(id);
        return d != null && branchPoints(d.branch) >= d.tier;
    }

    /**
     * Can this be bought right now?
     *
     * <p>Python {@code can_buy}: a point in hand, the tier open, and the rank
     * below the cap. All three, in that order.
     */
    public boolean canPurchase(String id) {
        TalentDef d = table.get(id);
        return d != null
                && points > 0
                && branchPoints(d.branch) >= d.tier
                && rank(id) < d.maxRank;
    }

    /**
     * Buys one rank.
     *
     * <p><b>A failed purchase changes nothing</b> — no point is consumed, no rank
     * moves, no branch total shifts. The guard runs first and returns before any
     * mutation, which is the whole of the transaction story for a talent: there
     * is one point and one rank, and they move together or not at all.
     *
     * @return true if a rank was bought
     */
    public boolean purchase(String id) {
        if (!canPurchase(id)) {
            return false;
        }
        TalentDef d = table.get(id);
        int newRank = rank(id) + 1;
        ranks.put(id, newRank);
        branchPoints.put(d.branch.id(), branchPoints(d.branch) + 1);
        points--;
        if (trace.isEnabled()) {
            trace.event(TraceEvent.TALENT_PURCHASED, 0L, 0L, newRank, points, id);
        }
        if (listener != null) {
            listener.onTalentPurchased(d, newRank, points);
        }
        return true;
    }

    /** Total magnitude: {@code rank * perRank}. Python {@code value}. */
    public double value(String id) {
        TalentDef d = table.get(id);
        return d == null ? 0d : rank(id) * d.perRank;
    }

    // ========================================================================
    //  CombatModifiers — the one place a rank becomes an effect
    // ========================================================================

    //  Each of these mirrors a Python @property line for line.  Where the source
    //  caps a value, the cap is here and has the source's number.

    @Override
    public double towerRate() {
        return 1d - Math.min(0.45d, value("rate"));
    }

    @Override
    public float towerDamage() {
        return (float) (1d + value("power"));
    }

    @Override
    public float critChance() {
        return (float) value("crit");
    }

    /** Extra bodies a bolt punches through. {@code int(v)}, truncated. */
    @Override
    public int extraPierce() {
        return (int) value("pierce");
    }

    @Override
    public float splashMult() {
        return (float) (1d + value("splash"));
    }

    @Override
    public double overchargeCd() {
        return 1d - Math.min(0.5d, value("overcharge"));
    }

    /**
     * Deep Foundations, and <b>nothing reads this</b>.
     *
     * <p>Exposed so the Phase 10 tooltip can show the player what the node
     * claims, and so the quirk is visible rather than absent. It is deliberately
     * <em>not</em> a {@code CombatModifiers} method: there is no override here to
     * accidentally wire up, and {@code Castle} has no way to reach it.
     *
     * <p>See {@code TalentQuirksTest.deepFoundationsDoesNotChangeCastleHealth}.
     */
    public double castleHpClaim() {
        return 1d + value("maxhp");
    }

    @Override
    public float barricadeRegen() {
        return (float) value("regen");
    }

    @Override
    public float spikeDot() {
        return (float) value("spikedot");
    }

    @Override
    public float towerHp() {
        return (float) (1d + value("towerhp"));
    }

    @Override
    public double rebuildMult() {
        return 1d - Math.min(0.6d, value("rebuild"));
    }

    @Override
    public float damageTaken() {
        return (float) (1d - Math.min(0.4d, value("thorns")));
    }

    @Override
    public float goldPop() {
        return (float) (1d + value("greed"));
    }

    @Override
    public float grabBonus() {
        return (float) (1d + value("lighthands"));
    }

    /** Shop prices. Capped at 40% off. */
    @Override
    public double shopDiscount() {
        return 1d - Math.min(0.4d, value("haggle"));
    }

    @Override
    public float wavePurse() {
        return (float) value("purse");
    }

    @Override
    public double grabCdScale() {
        return Math.max(0d, 1d - value("lightfingers"));
    }

    @Override
    public float scoreMult() {
        return (float) (1d + value("showman"));
    }

    @Override
    public float killGold() {
        return (float) (1d + value("scavenge"));
    }

    @Override
    public boolean allySentinels() {
        return rank("sentinels") > 0;
    }

    @Override
    public float stormWindSlow() {
        return (float) value("stormwinds");
    }

    @Override
    public float throwPower() {
        return (float) (1d + value("throwarm"));
    }

    @Override
    public float fallDamage() {
        return (float) (1d + value("updraft"));
    }

    @Override
    public float lightningMult() {
        return (float) (1d + value("conductor"));
    }

    @Override
    public float windMult() {
        return (float) (1d + value("gale"));
    }

    /**
     * Storm frequency.
     *
     * <p>{@code 2.0 if rank else 1.0} — a flag, not {@code 1 + v}. Tempest Caller
     * is rank 1 and doubles the chance outright.
     */
    @Override
    public float stormChance() {
        return rank("tempest") > 0 ? 2f : 1f;
    }

    @Override
    public float allyPower() {
        return (float) (1d + value("bonecraft"));
    }

    @Override
    public int allyCapBonus() {
        return (int) value("hostmaster");
    }

    @Override
    public double allyRate() {
        return 1d - Math.min(0.5d, value("quickraise"));
    }

    @Override
    public float graveChill() {
        return (float) value("gravechill");
    }

    @Override
    public double allyLife() {
        return value("secondwind");
    }

    @Override
    public float allyTough() {
        return (float) (1d - Math.min(0.5d, value("bonewall")));
    }

    /** Active skill cooldowns. Time domain. Capped at 45% off. */
    @Override
    public double skillCd() {
        return 1d - Math.min(0.45d, value("focus"));
    }

    /** Active skill damage. */
    @Override
    public float skillPower() {
        return (float) (1d + value("amplify"));
    }

    /** Active skill radius. */
    @Override
    public float skillArea() {
        return (float) (1d + value("widecast"));
    }

    /** How long meteor fire burns. Time domain. */
    @Override
    public double fireTime() {
        return 1d + value("emberfall");
    }

    /** Tornado lifetime <b>and</b> pull strength — one value drives both. */
    @Override
    public double tornadoMult() {
        return 1d + value("eyeofstorm");
    }

    /** Meteors dropped, as a multiplier on {@code METEOR_COUNT}. */
    @Override
    public float meteorCount() {
        return (float) (1d + value("twincast"));
    }

    // ========================================================================
    //  Read-only query surface for Phase 10
    // ========================================================================

    /** One node's current state, for the UI. Allocated on demand, never per step. */
    public static final class NodeView {
        public final TalentDef def;
        public final int rank;
        public final boolean unlocked;
        public final boolean purchasable;
        public final double value;

        NodeView(TalentDef def, int rank, boolean unlocked, boolean purchasable,
                 double value) {
            this.def = def;
            this.rank = rank;
            this.unlocked = unlocked;
            this.purchasable = purchasable;
            this.value = value;
        }

        public boolean maxed() {
            return rank >= def.maxRank;
        }
    }

    /**
     * A snapshot of every node, for the talent screen.
     *
     * <p>Built on demand. The screen is opened by hand and redrawn at most once a
     * frame while it is up, so this allocating is not a hot path — and a
     * read-only view is worth far more than the allocation it saves.
     */
    public Array<NodeView> view() {
        Array<NodeView> out = new Array<>(false, table.size());
        for (TalentDef d : table.all()) {
            out.add(new NodeView(d, rank(d.id), isUnlocked(d.id),
                    canPurchase(d.id), value(d.id)));
        }
        return out;
    }

    public NodeView view(String id) {
        TalentDef d = table.require(id);
        return new NodeView(d, rank(d.id), isUnlocked(d.id), canPurchase(d.id),
                value(d.id));
    }

    /** One line for the debug overlay. Only the nodes actually bought. */
    public String describe() {
        StringBuilder sb = new StringBuilder("talents ").append(points).append('/')
                .append(earned);
        for (TalentDef d : table.all()) {
            int r = rank(d.id);
            if (r > 0) {
                sb.append(' ').append(d.id).append(':').append(r);
            }
        }
        return sb.toString();
    }
}
