package com.mymmer.castledefense.enemy;

import com.mymmer.castledefense.config.GameConfig;
import com.mymmer.castledefense.debug.TraceEvent;
import com.mymmer.castledefense.defence.Barricade;
import com.mymmer.castledefense.defence.CombatModifiers;
import com.mymmer.castledefense.defence.Target;
import com.mymmer.castledefense.entity.Entity;
import com.mymmer.castledefense.entity.EntityList;
import com.mymmer.castledefense.util.Collisions;

/**
 * Base class for every hostile unit: a small, explicit state machine.
 *
 * <pre>
 *   walk    -> marching toward the castle, or to a stand-off point
 *   attack  -> in contact with the castle, the barricade or an ally
 *   grabbed -> held by the player's cursor; physics and thinking suspended
 *   air     -> flying after a throw; gravity, slams and fall damage apply
 *   trapped -> imprisoned in the Outpost
 * </pre>
 *
 * <p>It implements {@link Target}, the contract Phase 5 wrote its towers and
 * projectiles against. Nothing in {@code defence} was changed to accommodate
 * concrete enemies, and nothing in {@code defence} imports this package.
 *
 * <h2>Coordinates</h2>
 *
 * <p>Python/pygame space: y grows <b>downward</b>, gravity is positive, and
 * {@code GROUND_Y = 620} is below {@code WALL_TOP = 350}. Same rule as the
 * defences — the single flip to libGDX y-up happens in the renderer.
 *
 * <h2>Speed is a mutable field, deliberately</h2>
 *
 * <p>{@link #speed} is multiplied and divided back in place by
 * {@code think()} (talent slow), the Berzerker (rage) and the Assassin (dash),
 * exactly as Python does. That is unusual, and a "cleaner" formula would be a
 * different game: the three effects compose through that shared mutable field in
 * an order that is observable, and the divide-back does not restore the original
 * bit pattern. See {@code EnemySpeedTest} for the parity assertions and
 * {@code docs/PORT_ANALYSIS.md} §14 for the Berzerker quirk.
 */
public abstract class Enemy extends Entity implements Target {

    protected final EnemyContext ctx;
    protected final EnemyConfig config;

    /** The wave that spawned this mob. Read back by the Berzerker's rage speed. */
    public final int wave;

    // --- scaled stats -------------------------------------------------------
    private float maxHp;
    private float hp;
    /** Fraction of projectile damage ignored. Per-instance: plates come off. */
    private float armor;
    private int layers;
    private float stripProgress;
    /** Damage taken multiplier, rising as plates are torn off. */
    private float vulnerable = 1f;
    /** Mutable: see the class comment. */
    protected float speed;
    private float damage;
    private int gold;

    private final int tier;
    private final String tierName;

    // --- geometry -----------------------------------------------------------
    private final float w;
    private final float h;
    /** Lateral offset into the scene, so the crowd is not one pixel column. */
    private final float depth;
    /** This flyer's own cruising altitude. */
    protected final float flyY;
    private final boolean flying;

    protected float x;
    protected float y;
    protected float vx;
    protected float vy;

    // --- state --------------------------------------------------------------
    private EnemyState state = EnemyState.WALK;
    protected double attackTimer;
    private double stagger;
    private float shove;
    private boolean blocked;
    private boolean trapped;

    /** Speed estimates a tower leads its shots with. Python {@code vx_estimate}. */
    private float vxEstimate;
    private float vyEstimate;

    // --- airborne -----------------------------------------------------------
    private final SlamCooldowns slamCooldown = new SlamCooldowns();
    private boolean flingActive;
    private float flingX0;
    private float flingY0;
    private double flingT0;
    private int flingHits;
    private float flingPeak;
    private int bounceCount;
    /** Seconds of tornado lift still acting. Weather is Phase 11. */
    protected double tornadoHold;
    protected double stormCd;

    // --- visual-only --------------------------------------------------------
    /** Animation phase. Advanced by think(); nothing gameplay reads it. */
    protected float anim;
    private float hurtFlash;
    /** Rotation while airborne. Visual only. */
    private float spin;
    /** Bob phase for flyers. Drives the flyer's y target, so gameplay-relevant. */
    protected float bob;

    protected Enemy(EnemyContext ctx, EnemyConfig config, int wave, Float spawnX, Float spawnY) {
        if (ctx == null || config == null) {
            throw new IllegalArgumentException("ctx and config must not be null");
        }
        this.ctx = ctx;
        this.config = config;
        this.wave = wave;

        //  ---- the scaling chain, in Python's order.  Order matters: the
        //  difficulty scale stretches the wave curve, the HP curve then bends
        //  what came out of that, and the endgame tier multiplies the result.
        float hpM = WaveScaling.hp(wave);
        float dmgM = WaveScaling.damage(wave);
        float spdM = WaveScaling.speed(wave);

        float diff = ctx.enemyScale();
        if (diff != 1f) {
            hpM *= diff;
            dmgM *= diff;
            //  Speed is deliberately NOT stretched here: difficulty moves it
            //  with one flat multiplier instead, applied below.
        }
        //  Hard steepens the health curve itself -- the growth EARNED PER TIER
        //  is multiplied, so wave 1 is barely touched and wave 20 hurts.
        float curve = ctx.enemyHpCurve();
        if (curve != 1f) {
            hpM = 1f + (hpM - 1f) * curve;
        }

        EndgameTier[] tiers = ctx.endgameTiers();
        this.tier = WaveScaling.tierIndex(tiers, wave);
        if (tier >= 0) {
            EndgameTier t = tiers[tier];
            this.tierName = t.name;
            hpM *= t.hpMult;
            dmgM *= t.damageMult;
            spdM *= t.speedMult;
        } else {
            this.tierName = "";
        }

        this.maxHp = config.baseHp * hpM;
        this.hp = maxHp;
        this.armor = config.armor;
        this.layers = config.armorLayers;
        //  A flat base-speed multiplier on top of the wave curve: Hard mobs
        //  close the distance 40% sooner at every tier.
        this.speed = config.baseSpeed * spdM * ctx.enemySpeedScale();
        this.damage = config.baseDamage * dmgM;
        this.gold = Math.round(config.gold * (1f + 0.05f * (wave - 1)));

        this.flying = config.flying;
        this.depth = flying ? 0f : ctx.rng().uniform(-16f, 16f);
        this.flyY = config.flyY + ctx.rng().uniform(-46f, 46f);
        this.w = config.width;
        this.h = config.height;

        this.x = spawnX != null ? spawnX
                : GameConfig.SPAWN_X + ctx.rng().uniform(0f, 140f);
        this.y = spawnY != null ? spawnY : (flying ? flyY : groundY());

        this.vxEstimate = -speed;
        this.attackTimer = ctx.rng().uniform(0f, 0.4f);
        this.anim = ctx.rng().uniform(0f, 10f);
        this.bob = ctx.rng().uniform(0f, (float) (Math.PI * 2.0));

        ctx.trace().event(TraceEvent.ENTITY_SPAWN, ctx.step(), uid(), x, y, config.typeId);
    }

    // ========================================================================
    //  Identity and the Target contract
    // ========================================================================

    /** The roster type, or <b>null for a boss</b>. See {@link EnemyConfig#type}. */
    public EnemyType type() {
        return config.type;
    }

    /** A stable id that is always present, boss or not. */
    public String typeId() {
        return config.typeId;
    }

    public EnemyConfig config() {
        return config;
    }

    public String name() {
        return config.name;
    }

    @Override
    public boolean alive() {
        return isAlive();
    }

    /**
     * Can a tower or projectile pick this unit?
     *
     * <p>Base: anything alive. The Assassin overrides it — cloaked, it is simply
     * not there as far as targeting is concerned.
     */
    @Override
    public boolean targetable() {
        return isAlive();
    }

    @Override
    public float x() {
        return x;
    }

    @Override
    public float y() {
        return y;
    }

    @Override
    public float width() {
        return w;
    }

    @Override
    public float height() {
        return h;
    }

    @Override
    public boolean flying() {
        return flying;
    }

    @Override
    public boolean heavy() {
        return config.heavy;
    }

    @Override
    public float hp() {
        return hp;
    }

    @Override
    public float vxEstimate() {
        return vxEstimate;
    }

    @Override
    public float vyEstimate() {
        return vyEstimate;
    }

    /** True for the three bosses. Phase 7 supplies them; nothing here is one. */
    public boolean isBoss() {
        return false;
    }

    // ========================================================================
    //  Geometry
    // ========================================================================

    /** Resting y for a ground unit, including its depth offset. */
    public float groundY() {
        return GameConfig.GROUND_Y + depth - h / 2f;
    }

    public float depth() {
        return depth;
    }

    public float mass() {
        return config.mass;
    }

    /** Python {@code Enemy.covers} — point in hitbox, allocation-free. */
    public boolean covers(float px, float py) {
        return Collisions.pointInBox(px, py, x, y, w, h);
    }

    /** Python {@code Enemy.overlaps} — box overlap, allocation-free. */
    public boolean overlaps(Enemy other) {
        return Collisions.boxesOverlap(x, y, w, h, other.x, other.y, other.w, other.h);
    }

    /** Python {@code grab_rect}: the hitbox inflated by 16 on each axis. */
    public boolean grabCovers(float px, float py) {
        return Collisions.pointInBox(px, py, x, y, w + 16f, h + 16f);
    }

    // ========================================================================
    //  Interaction gates — all read, none mutate
    // ========================================================================

    /**
     * May the cursor lift this unit?
     *
     * <p>Four conditions, and mass-versus-capacity is only the last of them:
     * the type must allow it at all, it must be alive, it must be on foot (not
     * already airborne, grabbed or trapped), and <b>a heavy unit rides out every
     * lift attempt while its plating is on</b>. Grab Strength alone is never
     * enough against a tank; the armour comes off first.
     */
    public boolean grabbable() {
        if (!(config.grabbable && isAlive() && state.isOnFoot())) {
            return false;
        }
        if (armored()) {
            return false;
        }
        return config.mass <= ctx.grabCapacity();
    }

    /** A heavy unit still wearing plating: locks lifting and shoving. */
    public boolean armored() {
        return config.heavy && layers > 0;
    }

    /** A stripped heavy unit: can be hauled forward by the cursor. */
    public boolean shovable() {
        return config.heavy && isAlive() && !armored() && state.isOnFoot();
    }

    /** Stripped and liftable in principle, but the cursor is too weak. */
    public boolean tooHeavy() {
        return config.grabbable && isAlive() && !armored()
                && config.mass > ctx.grabCapacity();
    }

    /** Heavy plating that can be pried off by hauling on it. */
    public boolean strippable() {
        return config.strippable && isAlive() && layers > 0 && state.isOnFoot();
    }

    // ========================================================================
    //  Armour and shove
    // ========================================================================

    public float armor() {
        return armor;
    }

    public int layers() {
        return layers;
    }

    public float stripProgress() {
        return stripProgress;
    }

    public float vulnerable() {
        return vulnerable;
    }

    public float shove() {
        return shove;
    }

    /**
     * The player is hauling this heavy unit castle-ward.
     *
     * <p>Rushes the last slow tank into the guns, at the cost of it arriving at
     * the wall far sooner. Refused while the plating is on.
     */
    public boolean applyShove(float amount) {
        if (!(config.heavy && isAlive())) {
            return false;
        }
        if (armored()) {
            return false;       // strip the plating before hauling it about
        }
        shove = Math.min(GameConfig.SHOVE_MAX, shove + amount * GameConfig.SHOVE_FACTOR);
        return true;
    }

    /**
     * Feeds drag distance into prying off the next armour plate.
     *
     * <p>Each plate that comes away leaves the unit with less armour, a slower
     * advance and more damage taken — permanently, and the speed loss is applied
     * to the mutable {@link #speed} so it compounds with everything else.
     *
     * @return true when a plate actually comes off this call
     */
    public boolean applyStrip(float amount) {
        if (!strippable()) {
            return false;
        }
        stripProgress += amount / GameConfig.STRIP_DISTANCE;
        if (stripProgress < 1f) {
            return false;
        }
        stripProgress = 0f;
        layers--;
        armor = Math.max(0f, config.armor * ((float) layers / Math.max(1, config.armorLayers)));
        speed *= GameConfig.STRIP_SLOW;
        vulnerable += GameConfig.STRIP_VULN;
        ctx.addPlatesTorn();
        ctx.trace().event(TraceEvent.ARMOUR_STRIPPED, ctx.step(), uid(),
                layers, armor, config.typeId);
        //  The source shouts about it -- "ARMOR TORN!" while plates remain,
        //  "FULLY EXPOSED!" on the last one.  Absent until the Phase 11.5 image
        //  comparison, which left a stripped Ram with no feedback at all.
        ctx.visuals().text(x, y - height() / 2f - 24f,
                layers > 0 ? "ARMOR TORN!" : "FULLY EXPOSED!", 0xFFD25A, 22f, 1.1f);
        ctx.visuals().burst(x, y, 12, com.mymmer.castledefense.render.VisualEvents.STONE, 260f, 0.45f, 4f, 900f,
                com.mymmer.castledefense.render.VisualEvents.Shape.RECT);
        return true;
    }

    // ========================================================================
    //  Damage and death
    // ========================================================================

    /**
     * Seconds before this mob may be struck by ceiling lightning again.
     *
     * <p>Owned here rather than by the weather because it is per-mob state that
     * has to age with the mob, and because Python keeps it on the enemy for the
     * same reason.
     */
    public double stormCooldown() {
        return stormCd;
    }

    /** Starts the lightning re-strike lockout. Called by {@code Weather.strike}. */
    public void startStormCooldown() {
        stormCd = GameConfig.STORM_COOLDOWN;
    }

    /**
     * Seconds of tornado lift still acting.
     *
     * <p>While it is positive the airborne integration applies only 12% of
     * gravity, which is what makes a funnel carry a body rather than slow its
     * fall. The Tornado renews it every step it holds something.
     */
    public double tornadoHold() {
        return tornadoHold;
    }

    /** Renews (or clears) the tornado lift. Time domain. */
    public void holdInTornado(double seconds) {
        tornadoHold = Math.max(0d, seconds);
    }

    public float maxHp() {
        return maxHp;
    }

    public float damage() {
        return damage;
    }

    public int gold() {
        return gold;
    }

    public int tier() {
        return tier;
    }

    public String tierName() {
        return tierName;
    }

    /**
     * Takes damage.
     *
     * <p>Armour applies to projectiles in full and to explosives at 35%
     * strength. <b>Fall and impact damage ignore armour entirely</b> — hurling a
     * Shield Bearer off a cliff is the player's answer to all that plating, and
     * that is the whole reason the throw mechanic matters against tanks.
     *
     * @return the damage actually dealt, after reduction
     */
    @Override
    public void takeDamage(float amount, String source) {
        applyDamage(amount, source);
    }

    /** As {@link #takeDamage}, returning what was dealt. Python returns it too. */
    public float applyDamage(float amount, String kind) {
        if (!isAlive()) {
            return 0f;
        }
        if ("projectile".equals(kind)) {
            amount *= (1f - armor) * vulnerable;
        } else if ("explosive".equals(kind)) {
            amount *= (1f - armor * 0.35f) * vulnerable;
        }
        amount = Math.max(0f, amount);
        hp -= amount;
        hurtFlash = 1f;
        ctx.trace().event(TraceEvent.DAMAGE, ctx.step(), uid(), amount, hp, kind);
        if (hp <= 0f) {
            die();
        }
        return amount;
    }

    public void die() {
        die(false);
    }

    /**
     * Dies, paying out unless the death is silent.
     *
     * <p><b>Not final, and the ordering inside it is load-bearing.</b> The
     * Volatile overrides it to detonate <em>after</em> calling through, which is
     * what allows one blast to set off the next. A "tidier" arrangement that
     * detonated first, or that made this final and moved the hook elsewhere,
     * would break chain reactions.
     *
     * <p><b>The gold multiplier is read before the mob is marked dead</b>, so the
     * dying mob counts toward its own payout. That is Python's behaviour: chaos
     * pays. Marking it dead first would quietly cut every reward on screen.
     *
     * @param silent no gold, no kill count, no fling score — the Treasure
     *               Goblin's escape uses it
     */
    public void die(boolean silent) {
        if (!isAlive()) {
            return;
        }
        float mult = ctx.goldMultiplier();       // BEFORE markDead: see above
        markDead();
        hp = 0f;
        ctx.trace().event(TraceEvent.ENTITY_DEATH, ctx.step(), uid(),
                x, y, silent ? "silent" : config.typeId);
        if (!silent) {
            int gain = Math.max(1, Math.round(gold * mult));
            ctx.addGold(gain);
            ctx.addKill();
            ctx.trace().event(TraceEvent.GOLD_PAYOUT, ctx.step(), uid(),
                    gain, mult, config.typeId);
            //  A death sprays and pays.  Both are notices, not decisions: the
            //  sink cannot refuse and cannot answer back.
            ctx.visuals().burst(x, y, 14,
                    com.mymmer.castledefense.render.VisualEvents.BLOOD,
                    240f, 0.5f, 3f, 900f,
                    com.mymmer.castledefense.render.VisualEvents.Shape.RECT);
            ctx.visuals().text(x, y - 18f, "+" + gain,
                    com.mymmer.castledefense.render.VisualEvents.GOLD, 20f, 0.9f);
            resolveFling();
        }
    }

    // ========================================================================
    //  Throw physics
    // ========================================================================

    public EnemyState state() {
        return state;
    }

    /** Sets the state and traces the transition. */
    protected void setState(EnemyState next) {
        if (next != state) {
            ctx.trace().event(TraceEvent.ENTITY_STATE_CHANGED, ctx.step(), uid(),
                    x, y, next.id());
            state = next;
        }
    }

    /** Test and world hook: forces a state without going through behaviour. */
    public void forceState(EnemyState next) {
        setState(next);
    }

    /** Picked up by the cursor. */
    public void onGrab() {
        setState(EnemyState.GRABBED);
        vx = 0f;
        vy = 0f;
        spin = 0f;
        ctx.trace().event(TraceEvent.ENEMY_GRABBED, ctx.step(), uid(), x, y, config.typeId);
    }

    /**
     * Released by the cursor at a world-space velocity.
     *
     * <p>The velocity arrives already converted from screen to world units by the
     * Phase 4 input pipeline — no pixel ever reaches this method, which is why a
     * flick of the same length throws the same distance on a phone and a desktop.
     *
     * <p>Heavier mobs fly less far: the divisor {@code 0.55 + 0.45*MASS} is 1.0
     * at MASS 1 and 4.6 at a Siege Ram's MASS 9.
     */
    public void onRelease(float releaseVx, float releaseVy) {
        float mult = GameConfig.THROW_POWER * ctx.modifiers().throwPower()
                / (0.55f + 0.45f * config.mass);
        setState(EnemyState.AIR);
        vx = releaseVx * mult;
        vy = releaseVy * mult;
        slamCooldown.clear();
        flingActive = true;
        flingX0 = x;
        flingY0 = y;
        flingPeak = y;
        flingT0 = ctx.gameTime();
        flingHits = 0;
        bounceCount = 0;
        ctx.trace().event(TraceEvent.ENEMY_RELEASED, ctx.step(), uid(), vx, vy, config.typeId);
    }

    /**
     * An airborne mob hits the dirt.
     *
     * <p>With the Bounce upgrade it is springier: it rebounds several times, each
     * impact doing damage and resetting a longer recovery, so one good throw
     * keeps a mob out of the fight for ages.
     *
     * <p>The termination rule is <b>{@code bounceCount > level}</b>, strictly
     * greater. At level 0 that means the first landing always ends the chain —
     * which is why an un-upgraded throw does not bounce at all. Changing it to
     * {@code >=} because it reads more evenly would give level 0 a rebound and
     * every level one fewer.
     */
    public void land() {
        int lvl = Collisions.clamp(ctx.bounceLevel(), 0, GameConfig.BOUNCE_MAX_LEVEL);
        float impact = (float) Math.sqrt(vx * 0.5f * (vx * 0.5f) + vy * vy);
        float dmg = Math.max(0f, impact - GameConfig.FALL_DMG_FLOOR)
                * GameConfig.FALL_DMG_SCALE
                * (0.75f + 0.35f * config.mass)
                * (1f + GameConfig.BOUNCE_DMG_BONUS * lvl)
                * ctx.modifiers().fallDamage();
        bounceCount++;
        if (dmg > 0f) {
            applyDamage(dmg, "fall");
            ctx.addThrownDamage(dmg);
            ctx.trace().event(TraceEvent.FALL_DAMAGE, ctx.step(), uid(),
                    dmg, impact, config.typeId);
        }
        vy = -Math.abs(vy) * GameConfig.BOUNCE_RESTITUTION[lvl];
        vx *= 0.45f + 0.07f * lvl;
        spin *= 0.3f;
        //  a hard cap on rebounds guarantees the mob always settles
        if (bounceCount > lvl || Math.abs(vy) < 90f) {
            vy = 0f;
            spin = 0f;
            y = groundY();
            if (isAlive()) {
                setState(EnemyState.WALK);
                stagger = 0.45f + GameConfig.BOUNCE_STAGGER * lvl;
            }
            resolveFling();
        }
    }

    /**
     * Cashes in the score for one completed fling: distance plus airtime,
     * multiplied up by every mob clobbered on the way.
     */
    public void resolveFling() {
        if (!flingActive) {
            return;
        }
        flingActive = false;
        float travel = Math.abs(x - flingX0) + Math.max(0f, flingY0 - flingPeak);
        float airtime = (float) Math.max(0.0, ctx.gameTime() - flingT0);
        float base = travel * GameConfig.SCORE_PER_PX + airtime * GameConfig.SCORE_PER_SEC;
        float combo = 1f + GameConfig.SCORE_COMBO_STEP * flingHits;
        int pts = (int) (base * combo);
        if (pts > 0) {
            ctx.addScore(pts, x, y - h, flingHits, combo);
        }
    }

    /**
     * An airborne mob crashes into another mob. Both suffer.
     *
     * <p>The damage is driven by the <em>relative</em> velocity, so slamming into
     * something running the other way hurts far more than catching up with it.
     * The victim takes it in full and the thrown mob takes 45%.
     */
    public void slamInto(Enemy other) {
        float rvx = vx - other.vx;
        float rvy = vy - other.vy;
        float rel = (float) Math.sqrt(rvx * rvx + rvy * rvy);
        float dmg = Math.max(0f, rel - GameConfig.SLAM_DMG_FLOOR)
                * GameConfig.SLAM_DMG_SCALE * (0.6f + 0.5f * config.mass);
        if (dmg <= 0f) {
            return;
        }
        other.applyDamage(dmg, "impact");
        applyDamage(dmg * 0.45f, "impact");
        ctx.addThrownDamage(dmg);
        flingHits++;
        ctx.trace().event(TraceEvent.SLAM, ctx.step(), uid(), dmg, rel, config.typeId);

        //  a light enough victim is knocked airborne too, and briefly made
        //  immune to a second hit from the same attacker
        if (other.isAlive() && other.config.grabbable && other.config.mass <= 4f
                && other.state.isOnFoot()) {
            other.setState(EnemyState.AIR);
            other.vx = vx * 0.4f;
            other.vy = Math.min(-140f, vy * 0.5f);
            other.slamCooldown.clear();
            other.slamCooldown.put(uid(), 0.4);
        }
        vx *= 0.55f;
        vy *= 0.55f;
    }

    // ========================================================================
    //  Update
    // ========================================================================

    /**
     * One fixed simulation step. {@code dt} is always {@code Simulation.FIXED_DT}.
     *
     * <p>It arrives as a {@code double} and every timer below subtracts it as it
     * comes. The one float here is the hurt flash, which is drawing state.
     */
    public void update(double dt) {
        float fdt = (float) dt;                 // spatial / visual only
        hurtFlash = Math.max(0f, hurtFlash - fdt * 4f);
        stagger = Math.max(0d, stagger - dt);
        stormCd = Math.max(0d, stormCd - dt);
        slamCooldown.tick(dt);

        if (state == EnemyState.GRABBED) {
            vxEstimate = 0f;
            vyEstimate = 0f;
            return;
        }
        if (state == EnemyState.AIR) {
            updateAir(dt);
            return;
        }

        vxEstimate = 0f;
        vyEstimate = 0f;
        if (stagger > 0d) {
            return;         // still picking itself up
        }
        think(dt);
    }

    /**
     * Airborne integration.
     *
     * <p>Custom physics, not a rigid-body engine: gravity, a linear horizontal
     * drag, wind, and hand-written arena walls. Introducing Box2D would replace
     * every number in here with one of its own.
     */
    private void updateAir(double dt) {
        float fdt = (float) dt;                 // the integration is spatial
        if (tornadoHold > 0d) {
            //  held in a vortex: the funnel carries its own weight
            tornadoHold = Math.max(0d, tornadoHold - dt);
            vy += GameConfig.GRAVITY * 0.12f * fdt;
        } else {
            vy += GameConfig.GRAVITY * fdt;
        }
        vx += ctx.wind() * ctx.modifiers().windMult() * fdt;
        vx -= vx * GameConfig.AIR_DRAG * fdt;
        x += vx * fdt;
        y += vy * fdt;
        spin += vx * fdt * 0.012f;
        flingPeak = Math.min(flingPeak, y);
        if (ctx.storm() && y < GameConfig.STORM_CEILING) {
            ctx.strikeLightning(this);
        }
        vxEstimate = vx;
        vyEstimate = vy;

        // --- walls of the arena
        float leftWall = GameConfig.CASTLE_FRONT + w / 2f;
        if (x < leftWall) {
            x = leftWall;
            if (vx < 0f) {
                vx = -vx * 0.45f;
                applyDamage(Math.max(0f, Math.abs(vx) - 120f) * 0.09f, "impact");
            }
        }
        if (x > GameConfig.WORLD_WIDTH + 200f) {
            x = GameConfig.WORLD_WIDTH + 200f;
            vx = -Math.abs(vx) * 0.4f;
        }
        if (y < 24f) {
            y = 24f;
            vy = Math.abs(vy) * 0.3f;
        }

        //  flung into the Outpost?  That is the Necromancer betrayal.
        if (config.trappable && this instanceof com.mymmer.castledefense.defence.Trappable) {
            com.mymmer.castledefense.defence.Outpost post = ctx.outpost();
            com.mymmer.castledefense.defence.Trappable me =
                    (com.mymmer.castledefense.defence.Trappable) this;
            if (post != null && post.canTrap(me) && post.trapAreaContains(x, y)) {
                post.trap(me);
                return;
            }
        }

        // --- mid-air collisions.
        //  A SNAPSHOT, not the live list: the trap above can have just removed an
        //  entry, and a slam may knock another mob airborne and change the roster
        //  mid-loop.  Python iterates `list(self.game.enemies)` for exactly this.
        EntityList<Enemy> horde = ctx.horde();
        try (EntityList<Enemy>.Snapshot snap = horde.beginSnapshot()) {
            for (int i = 0; i < snap.size(); i++) {
                Enemy o = snap.get(i);
                if (o == this || !o.isAlive() || slamCooldown.contains(o.uid())) {
                    continue;
                }
                if (o.state == EnemyState.GRABBED) {
                    continue;
                }
                if (overlaps(o)) {
                    slamCooldown.put(o.uid(), 0.35);
                    slamInto(o);
                    if (!isAlive()) {
                        return;
                    }
                }
            }
        }

        if (y >= groundY()) {
            y = groundY();
            land();
        }
    }

    /**
     * Behaviour. Subclasses override and call through.
     *
     * <p>The talent slow is applied by <b>mutating {@link #speed} and dividing it
     * back at the end</b>, which is Python's approach and is why the Berzerker
     * and Assassin can wrap this method with their own multiplications and have
     * them compose. The divide-back does not restore the exact original bits;
     * that imprecision exists in the source too and is not corrected here.
     */
    protected void think(double dt) {
        float fdt = (float) dt;         // movement, animation and the bob
        float slow = ctx.enemySlow(this);
        if (slow < 1f) {
            speed *= slow;              // restored at the end of this method
        }
        if (shove > 0f) {               // carried momentum from the player's shove
            x -= shove * fdt;
            shove *= Math.max(0f, 1f - GameConfig.SHOVE_DECAY * fdt);
            if (shove < 8f) {
                shove = 0f;
            }
        }
        anim += fdt * speed * 0.06f;
        if (flying) {
            bob += fdt * 3f;
            float targetY = flyY + (float) Math.sin(bob) * 18f;
            y += Collisions.clamp(targetY - y, -160f * fdt, 160f * fdt);
        }

        //  a friendly skeleton in the way has to be dealt with first
        if (!flying) {
            FriendlySkeleton ally = allyInFront();
            if (ally != null) {
                setState(EnemyState.ATTACK);
                attackTimer -= dt;
                if (attackTimer <= 0d) {
                    attackTimer = config.attackRate;
                    ally.takeDamage(Math.max(1f, damage * 0.8f));
                }
                restoreSpeed(slow);
                return;
            }
        }

        Barricade bar = ctx.barricade();
        if (bar != null && bar.alive() && !flying && x >= bar.x()
                && x - w / 2f <= bar.x() + Barricade.WIDTH / 2f) {
            //  blocked out in the field: chew through the barricade.
            //  (a mob thrown OVER it lands inside and ignores it entirely)
            setState(EnemyState.ATTACK);
            x = bar.x() + Barricade.WIDTH / 2f + w / 2f;
            attackTimer -= dt;
            if (attackTimer <= 0d) {
                attackTimer = config.attackRate;
                bar.takeDamage(damage * (config.heavy ? 2.5f : 1f));
            }
            restoreSpeed(slow);
            return;
        }

        if (x - w / 2f <= ctx.castle().frontX()) {
            setState(EnemyState.ATTACK);
            x = ctx.castle().frontX() + w / 2f;
            attackTimer -= dt;
            if (attackTimer <= 0d) {
                attackTimer = config.attackRate;
                attackCastle();
            }
        } else if (blocked) {
            //  wait your turn: only the front rank gets to swing at the wall
            setState(EnemyState.WALK);
            x -= speed * 0.12f * fdt;
            vxEstimate = -speed * 0.12f;
        } else {
            setState(EnemyState.WALK);
            x -= speed * fdt;
            vxEstimate = -speed;
        }
        restoreSpeed(slow);
    }

    /** The other half of the mutate-and-restore dance. See {@link #think}. */
    private void restoreSpeed(float slow) {
        if (slow < 1f) {
            speed /= slow;
        }
    }

    /** Swings at the wall, and gets bitten by the spikes for doing so. */
    protected void attackCastle() {
        ctx.castle().takeDamage(damage);
        ctx.spikes().bite(this);
    }

    private FriendlySkeleton allyInFront() {
        int n = ctx.allyCount();
        for (int i = 0; i < n; i++) {
            FriendlySkeleton a = ctx.ally(i);
            if (!a.alive()) {
                continue;
            }
            if (Math.abs(a.depth() - depth) > 22f) {
                continue;
            }
            float gap = x - a.x();
            if (gap >= 0f && gap <= (w + a.width()) * 0.5f
                    + GameConfig.ALLY_ENGAGE_RANGE * 0.5f) {
                return a;
            }
        }
        return null;
    }

    // ========================================================================
    //  Crowd state, written by the separation pass
    // ========================================================================

    public boolean blocked() {
        return blocked;
    }

    void setBlocked(boolean blocked) {
        this.blocked = blocked;
    }

    /**
     * Sets the speed estimate a tower leads its shots with.
     *
     * <p>Protected because a subclass that replaces {@link #think} wholesale —
     * the Treasure Goblin runs the other way — still has to report which way it
     * is going, or every tower will lead its shots backwards.
     */
    protected void setVxEstimate(float v) {
        this.vxEstimate = v;
    }

    /** Only {@code CrowdSeparation} and the cursor move a mob laterally. */
    public void setX(float x) {
        this.x = x;
    }

    public void setY(float y) {
        this.y = y;
    }

    public float vx() {
        return vx;
    }

    public float vy() {
        return vy;
    }

    public void setVelocity(float vx, float vy) {
        this.vx = vx;
        this.vy = vy;
    }

    public float speed() {
        return speed;
    }

    public double stagger() {
        return stagger;
    }

    public boolean trapped() {
        return trapped;
    }

    /** Visual only. */
    public float spin() {
        return spin;
    }

    /** Visual only. */
    public float hurtFlash() {
        return hurtFlash;
    }

    /** Visual only. */
    public float anim() {
        return anim;
    }

    /** Adds spin while the cursor drags this mob about. Visual only. */
    public void addSpin(float amount) {
        spin += amount;
    }

    // ========================================================================
    //  Trapping (the Necromancer only; see Necromancer for the override)
    // ========================================================================

    protected void markTrapped() {
        trapped = true;
        setState(EnemyState.TRAPPED);
        vx = 0f;
        vy = 0f;
    }

    /**
     * One line describing this mob, for a crash log or a bug report.
     *
     * <p>Deliberately built on demand, never logged per frame.
     */
    public String describe() {
        return config.typeId + "#" + uid()
                + " " + state.id()
                + " x=" + Math.round(x) + " y=" + Math.round(y)
                + " v=(" + Math.round(vx) + "," + Math.round(vy) + ")"
                + " hp=" + Math.round(hp) + "/" + Math.round(maxHp)
                + " mass=" + config.mass
                + " armour=" + String.format("%.2f", armor) + "/" + layers + "L"
                + (tier >= 0 ? " tier=" + tierName : "")
                + (blocked ? " blocked" : "")
                + (shove > 0f ? " shove=" + Math.round(shove) : "");
    }

    @Override
    public String toString() {
        return describe();
    }
}
