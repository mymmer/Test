package com.mymmer.castledefense.interaction;

import com.badlogic.gdx.utils.Array;
import com.mymmer.castledefense.config.GameConfig;
import com.mymmer.castledefense.defence.DefenceTower;
import com.mymmer.castledefense.defence.Target;
import com.mymmer.castledefense.enemy.Enemy;
import com.mymmer.castledefense.enemy.EnemyContext;
import com.mymmer.castledefense.input.Pointer;
import com.mymmer.castledefense.input.WorldInteractionHandler;
import com.mymmer.castledefense.util.Collisions;

/**
 * What the player's cursor does to the world: grab, drag, throw, strip, shove
 * and overcharge.
 *
 * <p>This is the class that joins the Phase 4 input pipeline to the Phase 5 and
 * 6 gameplay. It implements {@link WorldInteractionHandler}, so it receives
 * presses, drags and releases that the UI did not claim, <b>already converted to
 * world coordinates</b>.
 *
 * <h2>No input device reaches gameplay through here</h2>
 *
 * <p>Nothing in this class or below it knows whether a mouse or a finger caused
 * the gesture. A {@link Pointer} carries world-space position and the velocity
 * tracker produces world-space velocity, so the same drag path produces the same
 * strip progress and the same throw on desktop and on a phone. That is the whole
 * reason the interaction lives here rather than in the enemies.
 *
 * <h2>The priority of a press</h2>
 *
 * <p>Python's {@code try_grab} checks candidates in a fixed order and the order
 * is gameplay: a Ballista's overcharge outranks the mob standing on it, and a
 * grabbable mob outranks the tank behind it. Phase 6 implements the subset that
 * exists — regalia and the Dragon's claws arrive with the bosses in Phase 7:
 *
 * <pre>
 *   overchargeable tower under the cursor  → start charging
 *   grabbable mob under the cursor         → grab it (and its neighbours)
 *   heavy unit under the cursor            → start stripping / shoving
 * </pre>
 */
public final class CursorInteraction implements WorldInteractionHandler {

    /** Python's grab hitbox inflation, and the tower's. */
    private static final float TOWER_GRAB_INFLATE = 14f;

    private final EnemyContext ctx;
    private final PointerVelocity velocity;

    /** Seconds until another grab is allowed. Difficulty sets the base. */
    private float grabCd;
    private float grabCooldown = GameConfig.GRAB_COOLDOWN;

    private Enemy grabbed;
    /** Extra mobs dragged along by Magnetic Gloves, with their offsets. */
    private final Array<Held> grabbedExtra = new Array<>(false, 4);

    private Enemy stripping;
    private DefenceTower charging;

    /** Last drag position, in world units. Strip and shove measure against it. */
    private float anchorX;
    private float anchorY;

    private int grabLevel;
    private int multiLevel;

    /** One extra mob held by Magnetic Gloves, and where it sits relative to the main one. */
    private static final class Held {
        final Enemy enemy;
        final float offsetX;
        final float offsetY;

        Held(Enemy enemy, float offsetX, float offsetY) {
            this.enemy = enemy;
            this.offsetX = offsetX;
            this.offsetY = offsetY;
        }
    }

    public CursorInteraction(EnemyContext ctx, PointerVelocity velocity) {
        if (ctx == null || velocity == null) {
            throw new IllegalArgumentException("ctx and velocity must not be null");
        }
        this.ctx = ctx;
        this.velocity = velocity;
    }

    // --- upgrades -----------------------------------------------------------

    /** Grab Strength, 0..{@code GRAB_MAX_LEVEL}. Sets the capacity table index. */
    public void setGrabLevel(int level) {
        this.grabLevel = level;
    }

    public int grabLevel() {
        return grabLevel;
    }

    /** Magnetic Gloves: how many extra mobs come along. */
    public void setMultiLevel(int level) {
        this.multiLevel = level;
    }

    public int multiLevel() {
        return multiLevel;
    }

    /** The difficulty's delay between grabs, after the Light Fingers talent. */
    public void setGrabCooldown(float seconds) {
        this.grabCooldown = Math.max(0f, seconds);
    }

    public float grabCooldown() {
        return grabCooldown;
    }

    public float grabCdRemaining() {
        return grabCd;
    }

    /**
     * The heaviest MASS the cursor can lift right now.
     *
     * <p>{@code GRAB_CAPACITY[clamp(level, 0, 4)] * grabBonus}. The talent
     * multiplies the <b>table entry</b>, so it can push a level's capacity past
     * the next one's threshold — a Siege Ram at MASS 9 becomes liftable at Grab
     * Strength 2 (7.5 x 1.25 = 9.375... still short) or 3 depending on the
     * bonus, rather than always exactly at 3. That is Python's behaviour and it
     * is why the table is not "normalised" into a formula.
     */
    public float grabCapacity() {
        int idx = Collisions.clamp(grabLevel, 0, GameConfig.GRAB_MAX_LEVEL);
        return GameConfig.GRAB_CAPACITY[idx] * ctx.modifiers().grabBonus();
    }

    // --- state --------------------------------------------------------------

    public Enemy grabbed() {
        return grabbed;
    }

    public int extraGrabbedCount() {
        return grabbedExtra.size;
    }

    public Enemy extraGrabbed(int i) {
        return grabbedExtra.get(i).enemy;
    }

    public Enemy stripping() {
        return stripping;
    }

    public DefenceTower charging() {
        return charging;
    }

    public boolean busy() {
        return grabbed != null || stripping != null || charging != null;
    }

    // --- the press ----------------------------------------------------------

    @Override
    public boolean onWorldPress(Pointer pointer) {
        if (grabCd > 0f || busy()) {
            return false;
        }
        float px = pointer.worldX();
        float py = pointer.worldY();
        anchorX = px;
        anchorY = py;

        //  1. an overchargeable tower under the cursor is hand-aimed instead
        DefenceTower tower = towerUnder(px, py);
        if (tower != null) {
            charging = tower;
            return true;
        }

        //  2. a grabbable mob
        Enemy e = grabbableUnder(px, py);
        if (e != null) {
            beginGrab(e);
            return true;
        }

        //  3. nothing liftable here -- is there a tank to dismantle instead?
        Enemy heavy = heavyUnder(px, py);
        if (heavy != null) {
            stripping = heavy;
            return true;
        }
        return false;
    }

    private DefenceTower towerUnder(float px, float py) {
        if (ctx.castle() == null) {
            return null;
        }
        Array<DefenceTower> towers = ctx.castle().towers();
        for (int i = 0; i < towers.size; i++) {
            DefenceTower t = towers.get(i);
            if (!t.canOvercharge()) {
                continue;
            }
            //  Python inflates the tower rect by 14 on each axis for this test
            if (Collisions.pointInPygameRect(px, py,
                    (int) (t.x() - t.width() / 2f) - (int) TOWER_GRAB_INFLATE / 2,
                    (int) (t.y() - t.height()) - (int) TOWER_GRAB_INFLATE / 2,
                    (int) t.width() + (int) TOWER_GRAB_INFLATE,
                    (int) t.height() + (int) TOWER_GRAB_INFLATE)) {
                return t;
            }
        }
        return null;
    }

    private Enemy grabbableUnder(float px, float py) {
        int n = ctx.targetCount();
        for (int i = 0; i < n; i++) {
            Target t = ctx.target(i);
            if (!(t instanceof Enemy)) {
                continue;
            }
            Enemy e = (Enemy) t;
            if (e.grabbable() && e.grabCovers(px, py)) {
                return e;
            }
        }
        return null;
    }

    private Enemy heavyUnder(float px, float py) {
        int n = ctx.targetCount();
        for (int i = 0; i < n; i++) {
            Target t = ctx.target(i);
            if (!(t instanceof Enemy)) {
                continue;
            }
            Enemy e = (Enemy) t;
            if ((e.strippable() || e.shovable()) && e.grabCovers(px, py)) {
                return e;
            }
        }
        return null;
    }

    /** Grabs a mob, and drags along whatever Magnetic Gloves can reach. */
    private void beginGrab(Enemy e) {
        grabbed = e;
        e.onGrab();
        grabbedExtra.clear();
        if (multiLevel > 0) {
            int n = ctx.targetCount();
            for (int i = 0; i < n && grabbedExtra.size < multiLevel; i++) {
                Target t = ctx.target(i);
                if (!(t instanceof Enemy)) {
                    continue;
                }
                Enemy o = (Enemy) t;
                if (o == e || !o.grabbable()) {
                    continue;
                }
                if (Collisions.distance(o.x(), o.y(), e.x(), e.y())
                        <= GameConfig.MULTI_RADIUS) {
                    o.onGrab();
                    grabbedExtra.add(new Held(o, o.x() - e.x(), o.y() - e.y()));
                }
            }
        }
    }

    // --- the drag -----------------------------------------------------------

    @Override
    public void onWorldDrag(Pointer pointer) {
        // Positions are followed in update(), which runs on the simulation step.
        // Nothing is done per input event, so a device that reports 240 drags a
        // second and one that reports 60 produce the same motion.
    }

    /**
     * One simulation step of whatever the cursor is currently doing.
     *
     * <p>Called from the world step with the pointer's current world position,
     * so all of this advances on the fixed clock rather than on input events.
     */
    public void update(float dt, boolean pointerDown, float px, float py) {
        grabCd = Math.max(0f, grabCd - dt);

        if (charging != null) {
            if (!charging.canOvercharge()) {
                charging = null;        // it went down or got stunned mid-pull
            }
            return;
        }

        if (stripping != null) {
            updateStripping(px);
            return;
        }

        Enemy e = grabbed;
        if (e == null) {
            return;
        }
        if (!e.alive()) {
            grabbed = null;
            grabbedExtra.clear();
            return;
        }
        dragTo(e, px, py);
        for (int i = grabbedExtra.size - 1; i >= 0; i--) {
            Held h = grabbedExtra.get(i);
            if (!h.enemy.alive()) {
                grabbedExtra.removeIndex(i);
                continue;
            }
            dragTo(h.enemy, e.x() + h.offsetX, e.y() + h.offsetY);
        }
    }

    /**
     * Moves a held mob toward a point.
     *
     * <p>The follow fraction falls with mass, so a Siege Ram lags visibly behind
     * the cursor while a Scout is glued to it — and it is clamped, so nothing is
     * ever completely unresponsive or perfectly rigid.
     */
    private void dragTo(Enemy e, float tx, float ty) {
        float follow = Collisions.clamp(1f - 0.10f * e.mass(), 0.25f, 0.95f);
        e.setX(e.x() + (tx - e.x()) * follow);
        e.setY(e.y() + (ty - e.y()) * follow);
        e.setX(Collisions.clamp(e.x(),
                GameConfig.CASTLE_FRONT + e.width() / 2f, GameConfig.WORLD_WIDTH + 120f));
        e.setY(Collisions.clamp(e.y(), 30f,
                GameConfig.GROUND_Y + e.depth() - e.height() / 2f));
        e.addSpin(0.08f);
    }

    /**
     * Dismantling a heavy unit: two phases, decided by whether the plating is on.
     *
     * <pre>
     *   PHASE 1 -- armoured: hauling AWAY from the castle strips a plate, and it
     *              is the ONLY thing the cursor can do to this unit.
     *   PHASE 2 -- bare:     hauling TOWARD the castle shoves it forward.
     * </pre>
     *
     * <p>The session survives the armour coming off — a bare hull can still be
     * shoved — so the grip is only dropped when there is nothing left to do.
     */
    private void updateStripping(float px) {
        Enemy h = stripping;
        if (!(h.strippable() || h.shovable())) {
            stripping = null;           // dead, or no longer interactable
            return;
        }
        float dx = px - anchorX;
        anchorX = px;
        if (h.armored()) {
            if (dx > 0.5f) {
                h.applyStrip(dx);
            }
        } else if (dx < -0.5f) {
            h.applyShove(-dx);
        }
    }

    // --- the release --------------------------------------------------------

    /**
     * Released.
     *
     * <p>The release velocity is world-space, produced by the Phase 4
     * {@code TouchVelocityTracker} from timestamped samples over a 90 ms window.
     * It is the same number whatever produced the gesture.
     */
    @Override
    public void onWorldRelease(Pointer pointer) {
        release(pointer, true);
    }

    /**
     * Cancelled by the platform — the app was backgrounded, or the gesture was
     * interrupted.
     *
     * <p>Distinct from a release: a cancelled grab <b>drops</b> the mob rather
     * than throwing it. Throwing a mob because the phone rang would be a
     * free kill the player did not earn.
     */
    @Override
    public void onWorldCancel(Pointer pointer) {
        release(pointer, false);
    }

    private void release(Pointer pointer, boolean throwIt) {
        if (charging != null) {
            DefenceTower t = charging;
            charging = null;
            grabCd = grabCooldown;
            if (throwIt) {
                float power = overchargePower(t, pointer.worldX(), pointer.worldY());
                if (power > 0.12f && t.canOvercharge()) {
                    t.overchargeFire(pointer.worldX(), pointer.worldY(), power);
                }
            }
            return;
        }
        if (stripping != null) {
            stripping = null;
            grabCd = grabCooldown;
            return;
        }
        Enemy e = grabbed;
        grabbed = null;
        grabCd = grabCooldown;
        if (e == null || !e.alive()) {
            grabbedExtra.clear();
            return;
        }

        float vx = 0f;
        float vy = 0f;
        if (throwIt) {
            velocity.velocityFor(pointer.id(), velocityOut);
            vx = velocityOut[0];
            vy = velocityOut[1];
        }
        e.onRelease(vx, vy);

        //  Every extra mob gets its own jitter, so a multi-grab throw scatters
        //  rather than launching a rigid block.  From the seeded gameplay stream,
        //  so a replay scatters identically.
        for (int i = 0; i < grabbedExtra.size; i++) {
            Enemy o = grabbedExtra.get(i).enemy;
            if (!o.alive()) {
                continue;
            }
            o.onRelease(vx * ctx.rng().uniform(0.85f, 1.15f),
                    vy * ctx.rng().uniform(0.85f, 1.15f));
        }
        grabbedExtra.clear();
    }

    /** Reused, so a release allocates nothing. */
    private final float[] velocityOut = new float[2];

    /**
     * How far the slingshot has been drawn back, 0..1.
     *
     * <p>The gesture-to-power conversion. It lives here, on the interaction side,
     * so the tower never learns what a drag is — it is handed a number.
     */
    public static float overchargePower(DefenceTower tower, float px, float py) {
        float d = Collisions.distance(tower.muzzleX(), tower.muzzleY(), px, py);
        return Collisions.clamp(d / GameConfig.OVERCHARGE_PULL, 0f, 1f);
    }
}
