package com.mymmer.castledefense.boss;

import com.mymmer.castledefense.config.GameConfig;
import com.mymmer.castledefense.debug.TraceEvent;
import com.mymmer.castledefense.enemy.EnemyConfig;
import com.mymmer.castledefense.enemy.EnemyState;
import com.mymmer.castledefense.util.Collisions;

/**
 * Smashes walls and flattens your defences.
 *
 * <p>The disruption is the whole fight: <b>drag the crown off his head</b> and he
 * abandons the castle to go and fetch it, attacking nothing on the way. Get it
 * far enough away and he is out of the fight for a long time — but he guards it
 * harder each time, so the trick does not scale for ever.
 *
 * <pre>
 *   crowned   → walks, leaps, smashes the wall and a random tower
 *   uncrowned → RETRIEVE: walks to the crown at 1.7x speed, attacking nothing
 *   reaches it → crowned again, and the guard grows
 * </pre>
 */
public final class TrollKing extends Boss {

    private double leapTimer;
    /** Visual only: the smash recoil, decaying at 2.5/s. */
    private float smash;

    private boolean hasCrown = true;
    /** The crown this instance dropped. Never another Troll King's. */
    private DroppedItem crownItem;

    public TrollKing(BossContext ctx, EnemyConfig config, BossConfig boss,
                     int wave, Float x, Float y) {
        super(ctx, config, boss, wave, x, y);
        this.leapTimer = ctx.rng().uniformSeconds(boss.leapIntervalMin, boss.leapIntervalMax);
    }

    public boolean hasCrown() {
        return hasCrown;
    }

    /** The crown this boss dropped, or null. */
    public DroppedItem crownItem() {
        return crownItem;
    }

    /** Visual only. */
    public float smash() {
        return smash;
    }

    @Override
    protected boolean hasRegalia() {
        return hasCrown;
    }

    /**
     * The player drags the crown off.
     *
     * <p>Order matters: the guard counter is incremented here, but
     * {@link #guardRegalia()} is <b>not</b> called until he gets it back — so
     * while he is uncrowned there is no guard, and the crown he is chasing can
     * be picked up and thrown again.
     */
    @Override
    public DroppedItem detachRegalia() {
        if (!hasCrown) {
            return null;
        }
        float[] anchor = new float[2];
        if (!regaliaAnchorInto(anchor)) {
            return null;
        }
        hasCrown = false;
        countDisruption();
        crownItem = new DroppedItem(bossCtx, this, RegaliaKind.CROWN, anchor[0], anchor[1]);
        bossCtx.addDroppedItem(crownItem);
        bossCtx.trace().event(TraceEvent.REGALIA_DETACH, bossCtx.step(), uid(),
                anchor[0], anchor[1], RegaliaKind.CROWN.id());
        return crownItem;
    }

    /**
     * Uncrowned: he abandons the castle and stamps off after his crown.
     *
     * <p>He moves at {@code CROWN_RETRIEVE_SPEED} (1.7x) and does not attack
     * anything at all on the way — no castle, no barricade, no ally.
     *
     * <p>If the crown has gone (killed by a purge, or a run reset), he simply
     * puts it back on and guards it. That branch is what stops a destroyed crown
     * leaving him walking for ever.
     */
    public void retrieveCrown(double dt) {
        setState(EnemyState.RETRIEVE);
        DroppedItem crown = crownItem;
        if (crown == null || !crown.isAlive()) {
            hasCrown = true;            // nothing to fetch; put it back on
            guardRegalia();
            crownItem = null;
            bossCtx.trace().event(TraceEvent.REGALIA_RECOVER, bossCtx.step(), uid(),
                    x(), y(), "crown-vanished");
            return;
        }
        float fdt = (float) dt;
        anim += fdt * speed * 0.06f;
        float dx = crown.x() - x();
        float step = speed * GameConfig.CROWN_RETRIEVE_SPEED * fdt;
        if (Math.abs(dx) > 6f) {
            setX(x() + Collisions.clamp(dx, -step, step));
            setVxEstimate(Math.copySign(speed * GameConfig.CROWN_RETRIEVE_SPEED, dx));
        }
        //  Only a crown at REST can be picked up: one still in the air stays out
        //  of reach, so a good throw buys time even after he arrives.
        if (crown.state() == DroppedItem.State.GROUND && Math.abs(dx) < 46f) {
            hasCrown = true;
            guardRegalia();
            crown.markDead();
            crownItem = null;
            bossCtx.trace().event(TraceEvent.REGALIA_RECOVER, bossCtx.step(), uid(),
                    x(), y(), "crown");
        }
    }

    @Override
    protected void think(double dt) {
        smash = Math.max(0f, smash - (float) dt * 2.5f);   // visual
        if (!hasCrown) {
            retrieveCrown(dt);          // no attacking until he is crowned
            return;
        }
        leapTimer -= dt;
        if (leapTimer <= 0d && x() > GameConfig.CASTLE_FRONT + boss.leapMinRange) {
            leapTimer = bossCtx.rng().uniformSeconds(boss.leapIntervalMin, boss.leapIntervalMax);
            setX(x() - boss.leapDistance);
        }
        super.think(dt);
    }

    /**
     * Smashes the wall, and flattens a tower with it.
     *
     * <p>The tower is chosen by {@code Castle.smashRandomTower} — the Phase 5
     * contract, drawing from the seeded gameplay stream. No new selection
     * algorithm: a boss picks a live tower at random and hits it for half its
     * own damage, stunning it for a second.
     */
    @Override
    protected void attackCastle() {
        smash = 1f;
        ctx.castle().takeDamage(damage());
        ctx.castle().smashRandomTower(damage() * 0.5f, 1f);
        bossCtx.trace().event(TraceEvent.BOSS_ATTACK, bossCtx.step(), uid(),
                x(), y(), "smash");
    }

    @Override
    protected void releaseOwnedState() {
        super.releaseOwnedState();
        if (crownItem != null) {
            crownItem.markDead();
            crownItem = null;
        }
    }

    @Override
    protected String bossStateSummary() {
        return " crown=" + (hasCrown ? "on" : "off")
                + (crownItem != null ? " item=#" + crownItem.uid() : "");
    }
}
