package com.mymmer.castledefense.boss;

import com.badlogic.gdx.utils.Array;
import com.mymmer.castledefense.config.GameConfig;
import com.mymmer.castledefense.debug.TraceEvent;
import com.mymmer.castledefense.defence.Barricade;
import com.mymmer.castledefense.defence.DefenceTower;
import com.mymmer.castledefense.defence.Projectile;
import com.mymmer.castledefense.defence.ProjectileKind;
import com.mymmer.castledefense.enemy.Enemy;
import com.mymmer.castledefense.enemy.EnemyConfig;
import com.mymmer.castledefense.enemy.EnemyState;
import com.mymmer.castledefense.enemy.EnemyType;

/**
 * Summons endless dead and hurls death magic.
 *
 * <p>The disruption is a single flick: <b>take the staff out of his hands</b> and
 * for five seconds he does nothing at all — no bolts, no summons, and the bone
 * ward drops with it. Then he recalls the staff magically, wherever it landed,
 * and guards it harder.
 *
 * <h2>The ward is applied BEFORE armour</h2>
 *
 * <pre>
 *   incoming damage → ward x0.25 → armour reduction
 * </pre>
 *
 * <p>Python overrides {@code take_damage} to scale the amount and <em>then</em>
 * calls {@code super()}, which applies the armour. The two orders differ
 * whenever anything between them is not a plain multiply — and the armour step
 * is {@code (1 - armor) * vulnerable}, with a vulnerability that stripping can
 * change. The order is the contract; see {@code docs/PORT_ANALYSIS.md} §14.
 */
public final class LichLord extends Boss {

    private double summonTimer = 3.0;
    private double boltTimer;
    private double phaseTimer;
    /** Seconds of bone ward left. */
    private double shield;
    /** Seconds of disarm left. */
    private double disarm;
    /** Visual only: the staff orb flare. */
    private float orb;

    private boolean hasStaff = true;
    private DroppedItem staffItem;

    /** Reused by {@link #deathBolt()} so target selection allocates nothing per shot. */
    private final Array<DefenceTower> liveTowers = new Array<>(false, 9);

    public LichLord(BossContext ctx, EnemyConfig config, BossConfig boss,
                    int wave, Float x, Float y) {
        super(ctx, config, boss, wave, x, y);
        this.boltTimer = fireDelay(2.0);
    }

    public boolean hasStaff() {
        return hasStaff;
    }

    public DroppedItem staffItem() {
        return staffItem;
    }

    /** Seconds of disarm left. Zero when armed. */
    public double disarm() {
        return disarm;
    }

    /** Seconds of bone ward left. Zero when unwarded. */
    public double shield() {
        return shield;
    }

    /** True while the ward is up. Phase 11 draws it; nothing here does. */
    public boolean wardActive() {
        return shield > 0d;
    }

    /** Visual only. */
    public float orb() {
        return orb;
    }

    @Override
    protected boolean hasRegalia() {
        return hasStaff;
    }

    /**
     * Takes damage, warded first.
     *
     * <p>The ward multiplies the <b>incoming</b> amount, and the armour and
     * vulnerability are then applied by {@code Enemy} to the result. Reversing
     * the two is not equivalent in general and is not permitted here.
     */
    @Override
    public float applyDamage(float amount, String kind) {
        if (shield > 0d) {
            amount *= boss.wardDamageMultiplier;
        }
        return super.applyDamage(amount, kind);
    }

    /**
     * Flicked out of his hands.
     *
     * <p>Three things happen at once: he is disarmed for
     * {@code STAFF_DISARM_TIME}, the ward <b>drops immediately</b> rather than
     * running out, and the staff becomes a real thrown object.
     */
    @Override
    public DroppedItem detachRegalia() {
        if (!hasStaff) {
            return null;
        }
        float[] anchor = new float[2];
        if (!regaliaAnchorInto(anchor)) {
            return null;
        }
        hasStaff = false;
        countDisruption();
        disarm = GameConfig.STAFF_DISARM_TIME;
        shield = 0d;                    // the ward drops with the staff
        staffItem = new DroppedItem(bossCtx, this, RegaliaKind.STAFF, anchor[0], anchor[1]);
        bossCtx.addDroppedItem(staffItem);
        bossCtx.trace().event(TraceEvent.REGALIA_DETACH, bossCtx.step(), uid(),
                anchor[0], anchor[1], RegaliaKind.STAFF.id());
        return staffItem;
    }

    /**
     * Focus regained — the staff is magically recalled to his hand.
     *
     * <p>Note he does <b>not</b> walk to it: unlike the Troll King, distance
     * buys the player nothing here, only the five seconds. The staff is killed
     * wherever it lies, even mid-flight.
     */
    public void recoverStaff() {
        if (staffItem != null) {
            staffItem.markDead();
            staffItem = null;
        }
        hasStaff = true;
        guardRegalia();
        orb = 1f;
        bossCtx.trace().event(TraceEvent.REGALIA_RECOVER, bossCtx.step(), uid(),
                x(), y(), "staff");
    }

    @Override
    protected void think(double dt) {
        float fdt = (float) dt;         // animation and the walk
        anim += fdt * 2f;
        orb = Math.max(0f, orb - fdt * 2f);         // visual
        shield = Math.max(0d, shield - dt);

        //  disarmed: no bolts, no summons, no ward -- just standing there
        if (disarm > 0d) {
            disarm -= dt;
            setState(EnemyState.ATTACK);
            if (disarm <= 0d) {
                recoverStaff();
            }
            return;
        }

        if (x() > currentStandoff()) {
            setX(x() - speed * fdt);
            setVxEstimate(-speed);
            setState(EnemyState.WALK);
            return;
        }
        setState(EnemyState.ATTACK);

        summonTimer -= dt;
        if (summonTimer <= 0d) {
            summonTimer = Math.max(boss.summonIntervalFloor,
                    boss.summonIntervalBase - wave * boss.summonIntervalPerWave);
            raiseDead();
        }

        boltTimer -= dt;
        if (boltTimer <= 0d) {
            boltTimer = fireDelay(bossCtx.rng().uniformSeconds(1.2, 2.0));
            deathBolt();
        }

        phaseTimer -= dt;
        if (phaseTimer <= 0d) {
            phaseTimer = bossCtx.rng().uniformSeconds(boss.wardIntervalMin,
                    boss.wardIntervalMax);
            shield = boss.wardDuration;
            bossCtx.trace().event(TraceEvent.BOSS_STATE_CHANGE, bossCtx.step(), uid(),
                    (float) shield, 0f, "bone-ward");
        }
    }

    /**
     * Where he halts.
     *
     * <p><b>He will not advance on the castle past a live wall.</b> While the
     * outer barricade stands he stops in front of it and works from there; once
     * it falls he closes to his ordinary stand-off. That is a real tactical
     * difference from every other unit, which simply attacks the barricade.
     */
    public float currentStandoff() {
        Barricade bar = ctx.barricade();
        if (bar != null && bar.alive()) {
            return Math.max(boss.standoffX,
                    bar.x() + Barricade.WIDTH / 2f + width() / 2f + 18f);
        }
        return boss.standoffX;
    }

    /**
     * Raises the dead.
     *
     * <p>The units are <b>real Phase 6 hostiles</b>, built through the enemy
     * factory and filed with the horde — not a boss-owned side list. They are
     * three waves weaker than the current one, and the pool excludes Siege Rams
     * and Necromancers (a summoned tank or summoner would be absurd).
     */
    public void raiseDead() {
        EnemyType[] pool = bossCtx.summonableTypes();
        if (pool.length == 0) {
            return;
        }
        int count = boss.summonBaseCount
                + Math.min(boss.summonMaxBonus, wave / 8);
        for (int i = 0; i < count; i++) {
            EnemyType type = pool[bossCtx.rng().game().nextInt(pool.length)];
            Enemy e = bossCtx.createEnemy(type, Math.max(1, wave - 3),
                    x() + bossCtx.rng().uniform(-70f, 90f), null);
            if (!e.flying()) {
                e.setY(e.groundY());
            }
            bossCtx.spawnEnemy(e);
        }
        orb = 1f;
        bossCtx.trace().event(TraceEvent.BOSS_ATTACK, bossCtx.step(), uid(),
                count, 0f, "raise-dead");
    }

    /**
     * A bolt of death magic.
     *
     * <p>Target priority: the barricade while it stands, otherwise a coin
     * between a live tower and the wall itself.
     */
    public void deathBolt() {
        float tx;
        float ty;
        Barricade bar = ctx.barricade();
        if (bar != null && bar.alive()) {
            //  the outer wall is the target until it comes down
            tx = bar.x();
            ty = bossCtx.rng().uniform(bar.topY() + 12f, GameConfig.GROUND_Y - 12f);
        } else {
            liveTowers.clear();
            Array<DefenceTower> towers = ctx.castle().towers();
            for (int i = 0; i < towers.size; i++) {
                if (!towers.get(i).disabled()) {
                    liveTowers.add(towers.get(i));
                }
            }
            if (liveTowers.size > 0 && bossCtx.rng().game().nextFloat() < 0.5f) {
                DefenceTower t = liveTowers.get(bossCtx.rng().game().nextInt(liveTowers.size));
                tx = t.x();
                ty = t.y() - t.height() / 2f;
            } else {
                tx = ctx.castle().frontX() - 30f;
                ty = bossCtx.rng().uniform(GameConfig.WALL_TOP + 20f,
                        GameConfig.GROUND_Y - 30f);
            }
        }
        float a = (float) Math.atan2(ty - (y() - 20f), tx - x());
        float speedOf = 520f;
        ctx.addProjectile(new Projectile(ctx, x(), y() - 20f,
                (float) Math.cos(a) * speedOf, (float) Math.sin(a) * speedOf,
                ProjectileKind.MAGIC, damage(), 54f, 0, 0f, true, 4f, 0f,
                1f, 1f, false, uid()));
        orb = 1f;
        bossCtx.trace().event(TraceEvent.BOSS_ATTACK, bossCtx.step(), uid(),
                tx, ty, "death-bolt");
    }

    @Override
    protected void releaseOwnedState() {
        super.releaseOwnedState();
        if (staffItem != null) {
            staffItem.markDead();
            staffItem = null;
        }
    }

    @Override
    protected String bossStateSummary() {
        return " staff=" + (hasStaff ? "held" : "gone")
                + " disarm=" + String.format("%.2f", disarm)
                + " ward=" + String.format("%.2f", shield)
                + (staffItem != null ? " item=#" + staffItem.uid() : "");
    }
}
