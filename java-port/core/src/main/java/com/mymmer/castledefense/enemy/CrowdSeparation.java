package com.mymmer.castledefense.enemy;

import com.badlogic.gdx.utils.Array;
import com.mymmer.castledefense.config.GameConfig;

/**
 * Keeps the ground crowd from stacking into a single pixel column, and decides
 * who is queued behind whom.
 *
 * <h2>This pass is order-dependent, and stays that way</h2>
 *
 * <p>It walks every pair {@code (i, j)} with {@code j > i} and <b>mutates
 * positions as it goes</b>, so the position B is pushed to depends on where A
 * was already pushed to earlier in the same pass. Three things are therefore
 * part of the observable behaviour and none of them may change:
 *
 * <ul>
 *   <li>the <b>insertion order</b> of the horde,</li>
 *   <li>the <b>nested traversal order</b> (i ascending, then j from i+1),</li>
 *   <li>the <b>mutation timing</b> — each pair is resolved immediately, not
 *       accumulated into a force and applied at the end.</li>
 * </ul>
 *
 * <p>So: no spatial bucketing, no sorting, no grid, no parallelism, and no
 * "gather then apply" rewrite. Every one of those produces a <em>plausible</em>
 * crowd and a different one, and the difference decides which mob reaches the
 * wall first. Phase 12 may replace this only behind a parity proof — see
 * {@code docs/PORT_ANALYSIS.md} §3.
 *
 * <p>The one allocation is the filtered ground list, which Python also builds
 * every frame. It is a reused {@link Array} rather than a fresh one per step.
 */
public final class CrowdSeparation {

    /** Depth difference beyond which two mobs simply do not see each other. */
    private static final float BLOCK_DEPTH = 13f;
    /** Gap within which the mob further right counts as queued behind. */
    private static final float BLOCK_GAP = 0.55f;
    /** Separation distance, as a fraction of the two widths. */
    private static final float MIN_DISTANCE = 0.42f;

    /** Reused across steps so the pass allocates nothing in the steady state. */
    private final Array<Enemy> ground = new Array<>(false, 64);

    /**
     * Runs one separation pass over the horde.
     *
     * @param dt unused, and kept only because Python's signature has it — the
     *           pass is a positional constraint solve, not an integration, so it
     *           produces the same result at any timestep. It is the canonical
     *           double step like every other {@code dt} in the step chain.
     */
    public void separate(EnemyContext ctx, double dt) {
        ground.clear();
        int total = ctx.targetCount();
        for (int i = 0; i < total; i++) {
            com.mymmer.castledefense.defence.Target t = ctx.target(i);
            if (!(t instanceof Enemy)) {
                continue;
            }
            Enemy e = (Enemy) t;
            if (e.alive() && !e.flying() && e.state().isOnFoot()) {
                ground.add(e);
            }
        }
        for (int i = 0; i < ground.size; i++) {
            ground.get(i).setBlocked(false);
        }

        int n = ground.size;
        for (int i = 0; i < n; i++) {
            Enemy a = ground.get(i);
            for (int j = i + 1; j < n; j++) {
                Enemy b = ground.get(j);
                if (Math.abs(a.depth() - b.depth()) > BLOCK_DEPTH) {
                    continue;
                }

                //  whoever is further right is queued behind the other
                float gap = (a.width() + b.width()) * BLOCK_GAP;
                float dx = a.x() - b.x();
                if (dx > 0f && dx <= gap) {
                    a.setBlocked(true);
                } else if (-dx > 0f && -dx <= gap) {
                    b.setBlocked(true);
                }

                //  and neither may stand inside the other
                float minD = (a.width() + b.width()) * MIN_DISTANCE;
                float d = a.x() - b.x();
                if (d > -minD && d < minD) {
                    float push = (minD - Math.abs(d)) * 0.5f;
                    float sgn = d >= 0f ? 1f : -1f;
                    float total2 = a.mass() + b.mass();
                    //  heavier mobs give less ground
                    a.setX(a.x() + push * sgn * (b.mass() / total2));
                    b.setX(b.x() - push * sgn * (a.mass() / total2));
                    a.setX(Math.max(a.x(), GameConfig.CASTLE_FRONT + a.width() / 2f));
                    b.setX(Math.max(b.x(), GameConfig.CASTLE_FRONT + b.width() / 2f));
                }
            }
        }
    }

    /** How many ground mobs the last pass considered. Diagnostics only. */
    public int lastGroundCount() {
        return ground.size;
    }
}
