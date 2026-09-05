package com.mymmer.castledefense.skill;

import com.badlogic.gdx.utils.Array;
import com.mymmer.castledefense.config.GameConfig;
import com.mymmer.castledefense.config.Tuning;
import com.mymmer.castledefense.debug.TraceEvent;
import com.mymmer.castledefense.defence.Projectile;
import com.mymmer.castledefense.defence.ProjectileKind;
import com.mymmer.castledefense.enemy.Enemy;
import com.mymmer.castledefense.entity.EntityList;
import com.mymmer.castledefense.util.Collisions;

/**
 * The skill bar: unlocking, cooldowns, targeting and casting.
 *
 * <p>Gameplay only. It has no slot rectangles, no icons, no hotkey codes and no
 * idea what a click is. Phase 10 turns a tap into two calls — {@link #select}
 * then {@link #castAt} — and this validates and performs them.
 *
 * <h2>Unlocking</h2>
 *
 * <p>One slot per boss defeated, in a fixed order, and the run's fourth boss
 * onward pays a talent bounty instead. {@code main.py:1561 on_boss_defeated}:
 *
 * <pre>
 *   slot free   -> unlock it, and award 2 talent points
 *   all three   -> award 3 talent points
 * </pre>
 *
 * <p>The award is the caller's business — {@link #unlockNext()} returns what it
 * unlocked, or null when the bar is full, and the run decides what to pay.
 *
 * <h2>Cooldowns are the time domain</h2>
 *
 * <p>Every cooldown here is a {@code double} and is decremented by the canonical
 * step. The full cooldown is {@code base * skillCd}, recomputed at the moment of
 * the cast — so a Focus rank bought while a skill is recharging does not
 * retroactively shorten the current wait, but does shorten the next one. That is
 * the source's behaviour: {@code full_cooldown} is a method, called once when
 * the cooldown is set.
 */
public final class SkillPanel {

    private final SkillContext ctx;
    private final Array<SkillId> unlocked = new Array<>(false, 3);
    private final double[] cooldowns = new double[SkillId.values().length];
    private final boolean[] wasReady = new boolean[SkillId.values().length];

    /** The skill waiting for the player to place it, or null. */
    private SkillId aiming;
    private int casts;

    public SkillPanel(SkillContext ctx) {
        if (ctx == null) {
            throw new IllegalArgumentException("ctx must not be null");
        }
        this.ctx = ctx;
        reset();
    }

    /** A new run: no skills, no cooldowns, nothing aimed. */
    public void reset() {
        unlocked.clear();
        java.util.Arrays.fill(cooldowns, 0d);
        java.util.Arrays.fill(wasReady, true);
        aiming = null;
        casts = 0;
    }

    // ========================================================================
    //  Unlocking
    // ========================================================================

    /**
     * Lights up the next slot.
     *
     * @return the skill unlocked, or null when all three already are
     */
    public SkillId unlockNext() {
        if (unlocked.size >= SkillId.values().length) {
            return null;
        }
        SkillId next = SkillId.values()[unlocked.size];
        unlocked.add(next);
        if (ctx.trace().isEnabled()) {
            ctx.trace().event(TraceEvent.SKILL_UNLOCKED, ctx.step(), 0L,
                    unlocked.size, 0f, next.id());
        }
        return next;
    }

    /** The skills the player has, in unlock order. */
    public Array<SkillId> unlockedSkills() {
        return unlocked;
    }

    public boolean isUnlocked(SkillId id) {
        return unlocked.contains(id, true);
    }

    public int unlockedCount() {
        return unlocked.size;
    }

    public int castCount() {
        return casts;
    }

    // ========================================================================
    //  Cooldowns
    // ========================================================================

    /** Seconds until this skill is castable. Zero when ready. */
    public double cooldownRemaining(SkillId id) {
        return cooldowns[id.ordinal()];
    }

    /** The full wait a cast would impose right now, after Arcane Focus. */
    public double fullCooldown(SkillId id) {
        return baseCooldown(id) * ctx.modifiers().skillCd();
    }

    /** The unmodified cooldown from the tuning block. */
    public static double baseCooldown(SkillId id) {
        switch (id) {
            case LIGHTNING:
                return Tuning.LIGHTNING_COOLDOWN;
            case METEOR:
                return Tuning.METEOR_COOLDOWN;
            case TORNADO:
                return Tuning.TORNADO_COOLDOWN;
            default:
                return 0d;
        }
    }

    public boolean isReady(SkillId id) {
        return isUnlocked(id) && cooldowns[id.ordinal()] <= 0d;
    }

    /**
     * One step.
     *
     * <p>Ages every cooldown — including locked slots', which is harmless and is
     * what the source does since it only holds unlocked ones — and drops the aim
     * if the aimed skill stopped being ready.
     */
    public void update(double dt) {
        for (int i = 0; i < cooldowns.length; i++) {
            if (cooldowns[i] > 0d) {
                cooldowns[i] = Math.max(0d, cooldowns[i] - dt);
                if (cooldowns[i] <= 0d && !wasReady[i]) {
                    wasReady[i] = true;
                    if (ctx.trace().isEnabled()) {
                        ctx.trace().event(TraceEvent.SKILL_COOLDOWN_READY, ctx.step(),
                                0L, 0f, 0f, SkillId.values()[i].id());
                    }
                }
            }
        }
        if (aiming != null && !isReady(aiming)) {
            aiming = null;
        }
    }

    // ========================================================================
    //  Targeting and casting
    // ========================================================================

    /** The skill currently waiting for a target, or null. */
    public SkillId aiming() {
        return aiming;
    }

    /**
     * Arms a skill — Python {@code activate}.
     *
     * <p>A targeted skill waits for a position; an untargeted one is not armed
     * here at all, because the caller has nowhere to aim it. Phase 10 calls
     * {@link #castAt} directly for those, with wherever the cursor is.
     *
     * @return false when the skill is locked or still recharging
     */
    public boolean select(SkillId id) {
        if (!isReady(id)) {
            return false;
        }
        if (!id.needsTarget()) {
            return true;                // ready, but there is nothing to aim
        }
        aiming = id;
        if (ctx.trace().isEnabled()) {
            ctx.trace().event(TraceEvent.SKILL_SELECTED, ctx.step(), 0L, 0f, 0f, id.id());
        }
        return true;
    }

    /** Drops the aim without casting. */
    public void cancelAim() {
        aiming = null;
    }

    /**
     * Casts the skill currently aimed, at a world position.
     *
     * @return false when nothing is aimed, or it stopped being ready
     */
    public boolean castAimedAt(float worldX, float worldY) {
        return aiming != null && castAt(aiming, worldX, worldY);
    }

    /**
     * Casts a skill at a world position.
     *
     * <p>Virtual world units. No pixel, no screen, no device reaches this.
     *
     * @return false when the skill is locked or still recharging
     */
    public boolean castAt(SkillId id, float worldX, float worldY) {
        if (!isReady(id)) {
            return false;
        }
        switch (id) {
            case LIGHTNING:
                castLightning(worldX, worldY);
                break;
            case METEOR:
                castMeteor(worldX, worldY);
                break;
            case TORNADO:
                castTornado(worldX, worldY);
                break;
            default:
                return false;
        }
        //  the wait is computed at the moment of the cast, as in the source
        cooldowns[id.ordinal()] = fullCooldown(id);
        wasReady[id.ordinal()] = false;
        aiming = null;
        casts++;
        ctx.onSkillCast(id);
        if (ctx.trace().isEnabled()) {
            ctx.trace().event(TraceEvent.SKILL_CAST, ctx.step(), 0L, worldX, worldY,
                    id.id());
        }
        return true;
    }

    // ========================================================================
    //  The three skills
    // ========================================================================

    /**
     * Lightning Strike — {@code main.py:697}.
     *
     * <p>A <b>column</b>, not a circle: everything within {@code radius} of the
     * cast's x is hit, whatever its height, so a Gargoyle overhead is caught
     * along with the mob below it. Damage is a share of each victim's
     * <em>maximum</em> health, so it vaporises a crowd of anything; a boss takes
     * a quarter of that, shaken rather than deleted.
     *
     * <p>A snapshot, because a bolt that kills a boss purges the roster mid-loop.
     */
    private void castLightning(float x, float y) {
        float radius = Tuning.LIGHTNING_RADIUS * ctx.modifiers().skillArea();
        float scale = Tuning.LIGHTNING_DAMAGE * ctx.modifiers().skillPower()
                * ctx.modifiers().lightningMult();
        int hit = 0;

        EntityList<Enemy> horde = ctx.horde();
        try (EntityList<Enemy>.Snapshot snap = horde.beginSnapshot()) {
            for (int i = 0; i < snap.size(); i++) {
                Enemy e = snap.get(i);
                if (!e.isAlive() || Math.abs(e.x() - x) > radius) {
                    continue;
                }
                float dmg = e.maxHp() * scale;
                if (e.isBoss()) {
                    dmg *= 0.25f;           // shaken, not vaporised
                }
                e.takeDamage(dmg, "lightning");
                hit++;
            }
        }
        ctx.shake().add(13f);
        if (ctx.trace().isEnabled()) {
            ctx.trace().event(TraceEvent.SKILL_EFFECT_CREATED, ctx.step(), 0L,
                    x, hit, "lightning");
        }
    }

    /**
     * Meteor Shower — {@code main.py:729}.
     *
     * <p>Untargeted: it rains across a band anchored on the cursor, not on a
     * point. Each rock is an ordinary {@link Projectile} with splash and low
     * gravity, plus a {@link FireZone} where it lands — so the burning ground is
     * created <em>at cast time</em>, not on impact. That is the source's
     * arrangement and it is why the fire starts before the rocks arrive.
     *
     * <p>Every position and speed comes from the gameplay stream, in the source's
     * order, so a seeded run drops identical rocks.
     */
    private void castMeteor(float x, float y) {
        float radius = Tuning.METEOR_RADIUS * ctx.modifiers().skillArea();
        float dmg = Tuning.METEOR_DAMAGE * ctx.modifiers().skillPower();
        float left = Math.max(GameConfig.CASTLE_FRONT + 40f, x - 430f);
        int count = (int) (Tuning.METEOR_COUNT * ctx.modifiers().meteorCount());

        for (int i = 0; i < count; i++) {
            float mx = Collisions.clamp(left + ctx.rng().uniform(0f, 860f),
                    GameConfig.CASTLE_FRONT + 30f, GameConfig.WORLD_WIDTH - 20f);
            ctx.addProjectile(new Projectile(ctx.projectileContext(),
                    mx + ctx.rng().uniform(-90f, -40f), -60f - i * 26f,
                    ctx.rng().uniform(60f, 140f), ctx.rng().uniform(520f, 700f),
                    ProjectileKind.FIRE, dmg,
                    radius, 0, GameConfig.GRAVITY * 0.4f, false,
                    5.0, 0d, 1f, 1f, false, 0L));
            ctx.addFireZone(new FireZone(ctx, mx,
                    Tuning.FIRE_ZONE_TIME * ctx.modifiers().fireTime(),
                    Tuning.FIRE_ZONE_DPS * ctx.modifiers().skillPower(),
                    radius * 0.75f));
        }
        ctx.shake().add(10f);
        if (ctx.trace().isEnabled()) {
            ctx.trace().event(TraceEvent.SKILL_EFFECT_CREATED, ctx.step(), 0L,
                    x, count, "meteor");
        }
    }

    /**
     * Tornado — {@code main.py:760}.
     *
     * <p>{@code tornadoMult} drives both the lifetime and the pull, from the one
     * Eye of the Storm talent; {@code skillArea} drives the radius. See
     * {@link Tornado} for what the funnel then does.
     */
    private void castTornado(float x, float y) {
        double mult = ctx.modifiers().tornadoMult();
        ctx.addTornado(new Tornado(ctx, x, Tuning.TORNADO_LIFE * mult,
                Tuning.TORNADO_RADIUS * ctx.modifiers().skillArea(), mult));
        ctx.shake().add(7f);
        if (ctx.trace().isEnabled()) {
            ctx.trace().event(TraceEvent.SKILL_EFFECT_CREATED, ctx.step(), 0L,
                    x, 0f, "tornado");
        }
    }

    // ========================================================================
    //  Read-only query surface for Phase 10
    // ========================================================================

    /** One slot's state, for the skill bar. */
    public static final class SlotView {
        public final SkillId id;
        public final boolean unlocked;
        public final boolean ready;
        public final double cooldownRemaining;
        public final double fullCooldown;
        public final boolean aiming;

        SlotView(SkillId id, boolean unlocked, boolean ready, double remaining,
                 double full, boolean aiming) {
            this.id = id;
            this.unlocked = unlocked;
            this.ready = ready;
            this.cooldownRemaining = remaining;
            this.fullCooldown = full;
            this.aiming = aiming;
        }

        /** How far through the cooldown, 0 just cast and 1 ready. */
        public float progress() {
            if (fullCooldown <= 0d) {
                return 1f;
            }
            return (float) Math.max(0d, Math.min(1d,
                    1d - cooldownRemaining / fullCooldown));
        }
    }

    public Array<SlotView> view() {
        Array<SlotView> out = new Array<>(false, unlocked.size);
        for (SkillId id : unlocked) {
            out.add(view(id));
        }
        return out;
    }

    public SlotView view(SkillId id) {
        return new SlotView(id, isUnlocked(id), isReady(id), cooldownRemaining(id),
                fullCooldown(id), aiming == id);
    }

    public String describe() {
        StringBuilder sb = new StringBuilder("skills ");
        if (unlocked.size == 0) {
            return sb.append("none").toString();
        }
        for (SkillId id : unlocked) {
            sb.append(id.id());
            double cd = cooldownRemaining(id);
            sb.append(cd > 0d ? String.format(java.util.Locale.ROOT, "(%.1f)", cd) : "(ready)");
            sb.append(' ');
        }
        return sb.toString().trim();
    }
}
