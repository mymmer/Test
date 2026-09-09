package com.mymmer.castledefense.progress;

import com.mymmer.castledefense.config.GameConfig;
import com.mymmer.castledefense.config.Tuning;
import com.mymmer.castledefense.debug.SimulationTrace;
import com.mymmer.castledefense.debug.TraceEvent;
import com.mymmer.castledefense.defence.CombatModifiers;
import com.mymmer.castledefense.enemy.Enemy;
import com.mymmer.castledefense.util.Rng;
import java.util.Locale;

/**
 * Wind and storms — the gameplay half.
 *
 * <h2>One wind, read by everyone</h2>
 *
 * <p>{@link #wind()} is the single gameplay-authoritative value. Projectiles read
 * it through {@code DefenceContext.wind()}, airborne enemies read it through the
 * same seam, and the Gale talent reads it to decide whether a headwind is
 * blowing. <b>Nothing copies it into an entity.</b> A mob thrown before the wind
 * changed feels the new wind on its next step, exactly as in Python, because
 * there is only ever one number.
 *
 * <p>Sign convention, from {@code sprites.py}: <b>positive blows away from the
 * castle</b> (a tailwind for the player's throws, which travel rightwards);
 * negative blows back toward the wall.
 *
 * <h2>When it is rolled</h2>
 *
 * <p>{@code roll()} happens at exactly two moments, and both are the director's
 * business rather than a clock of its own: the start of a Classic wave, and each
 * Endless tier step. There is no continuous wind drift in the source and none is
 * invented here.
 *
 * <h2>Ceiling strikes</h2>
 *
 * <p>{@code main.py:1425 strike_lightning}. A mob flung above
 * {@code STORM_CEILING} draws a bolt: damage proportional to its <em>maximum</em>
 * health, then a {@code STORM_COOLDOWN} lockout on that mob so a long hang does
 * not chain-zap it. The eligibility check is entirely the enemy's own state —
 * airborne and above the ceiling — which the Phase 6 airborne integration
 * already calls through {@code EnemyContext.strikeLightning}.
 *
 * <p>Flying enemies are <b>not</b> excluded: a Gargoyle cruises at
 * {@code FLY_Y} 250, far below the 132 ceiling, so it can only be struck if the
 * player has thrown it up there. The source has no special case and neither does
 * this.
 *
 * <p>What is deliberately absent: wind streaks, bolt sprites, the screen flash.
 * {@link #stormFlash()} is exposed as <em>state</em> for Phase 11 to consume.
 */
public final class Weather {

    private final Rng rng;
    private final CombatModifiers mods;
    private final ScreenShake shake;
    private SimulationTrace trace;

    private float wind;
    private boolean storm;

    /** Visual metadata for Phase 11: 1.0 on a strike, decaying at 3.5/s. */
    private float stormFlash;

    private int strikes;
    private int rolls;

    public Weather(Rng rng, CombatModifiers mods, ScreenShake shake, SimulationTrace trace) {
        if (rng == null) {
            throw new IllegalArgumentException("rng must not be null");
        }
        this.rng = rng;
        this.mods = mods != null ? mods : CombatModifiers.NONE;
        this.shake = shake;
        this.trace = trace;
    }

    /**
     * The presentation sink. One-way, and {@code NONE} until a renderer exists.
     *
     * <p>A strike used to apply its damage, its cooldown, its flash and its
     * shake and then draw nothing whatever: the bolt, the spray and the "ZAP"
     * readout were all absent, so the only sign of a strike was a white flash
     * with no cause visible on screen.
     */
    private com.mymmer.castledefense.render.VisualEvents visuals =
            com.mymmer.castledefense.render.VisualEvents.NONE;

    public void setVisualEvents(com.mymmer.castledefense.render.VisualEvents sink) {
        this.visuals = sink == null
                ? com.mymmer.castledefense.render.VisualEvents.NONE : sink;
    }

    /**
     * Lights the white-out without striking anything.
     *
     * <p>The Lightning Strike skill sets {@code game.storm_flash = 1.0}
     * directly ({@code main.py:716}); it is presentation state that lives here,
     * so the skill asks for it rather than reaching into the field.
     */
    public void flash() {
        stormFlash = 1f;
    }

    public void setTrace(SimulationTrace trace) {
        this.trace = trace;
    }

    // --- state --------------------------------------------------------------

    /** Signed wind, +/- {@code WIND_MAX}. Positive blows away from the castle. */
    public float wind() {
        return wind;
    }

    public boolean storm() {
        return storm;
    }

    /** Visual only. Phase 11 reads it; nothing gameplay branches on it. */
    public float stormFlash() {
        return stormFlash;
    }

    public int strikeCount() {
        return strikes;
    }

    public int rollCount() {
        return rolls;
    }

    /** True while the wind is a strong headwind — the Gale talent's condition. */
    public boolean strongHeadwind() {
        return wind < 0f
                && Math.abs(wind) >= GameConfig.WIND_MAX * Tuning.HEADWIND_THRESHOLD;
    }

    /** True while the wind is a strong tailwind. Drives the banner only. */
    public boolean strongTailwind() {
        return wind > 0f
                && Math.abs(wind) > GameConfig.WIND_MAX * Tuning.HEADWIND_THRESHOLD;
    }

    // --- rolling ------------------------------------------------------------

    /**
     * Picks this stretch's weather — {@code main.py:1595 roll_weather}.
     *
     * <pre>
     *   wind  = uniform(-1, 1) * WIND_MAX
     *   storm = random() &lt; STORM_CHANCE * stormChance
     * </pre>
     *
     * <p>Two draws, in that order, from the gameplay stream. Order matters: a
     * seeded run must reproduce both, and swapping them would change every
     * subsequent value in the run.
     */
    public void roll() {
        wind = rng.uniform(-1f, 1f) * GameConfig.WIND_MAX;
        storm = rng.chance(GameConfig.STORM_CHANCE * mods.stormChance());
        rolls++;
        if (trace != null && trace.isEnabled()) {
            trace.event(TraceEvent.WEATHER_CHANGED, 0L, 0L, wind, storm ? 1f : 0f,
                    storm ? "storm" : "clear");
        }
    }

    /**
     * Posts the weather banners the source posts after a roll.
     *
     * <p>Separated from {@link #roll} because Python's {@code roll_weather} takes
     * an {@code announce} flag, and the two callers that pass False want the
     * numbers without the noise.
     */
    public void announce(Announcements banners) {
        if (banners == null) {
            return;
        }
        if (strongTailwind()) {
            banners.post(Announcements.Id.WEATHER_TAILWIND, 3.0);
        } else if (strongHeadwind()) {
            banners.post(Announcements.Id.WEATHER_HEADWIND, 3.0);
        }
        if (storm) {
            banners.post(Announcements.Id.WEATHER_STORM, 3.6);
        }
    }

    // --- the step -----------------------------------------------------------

    /** Ages the visual flash. Gameplay state does not change per step. */
    public void update(double dt) {
        stormFlash = Math.max(0f, stormFlash - (float) dt * 3.5f);
    }

    /**
     * A mob has been flung into the storm ceiling.
     *
     * <p>Called by the airborne integration, which has already checked the
     * height. The rest of the guard is here because it is the weather's
     * business: no storm, no strike; a dead mob, no strike; and a mob still
     * inside its own {@code STORM_COOLDOWN}, no strike.
     */
    public void strike(Enemy enemy) {
        if (!storm || enemy == null || !enemy.isAlive() || enemy.stormCooldown() > 0d) {
            return;
        }
        enemy.startStormCooldown();
        float damage = enemy.maxHp() * GameConfig.STORM_DAMAGE * mods.lightningMult();
        enemy.applyDamage(damage, "lightning");
        stormFlash = 1f;
        strikes++;
        if (shake != null) {
            shake.add(8f);
        }
        //  main.py:1435-1439 -- the bolt, the spray and the readout. Gameplay
        //  has already decided the damage above; this only shows it.
        visuals.bolt(enemy.x(), enemy.y(), 0.28f);
        visuals.burst(enemy.x(), enemy.y(), 26,
                com.mymmer.castledefense.render.VisualEvents.STORM_SPARK,
                340f, 0.5f, 4f, 0f,
                com.mymmer.castledefense.render.VisualEvents.Shape.RECT);
        visuals.text(enemy.x(), enemy.y() - 30f, "ZAP " + (int) damage,
                com.mymmer.castledefense.render.VisualEvents.STORM_TEXT, 26f, 1f);
        if (trace != null && trace.isEnabled()) {
            trace.event(TraceEvent.STORM_STRIKE, 0L, enemy.uid(), enemy.x(), enemy.y(),
                    "lightning");
        }
    }

    /** A fresh run. */
    public void reset() {
        wind = 0f;
        storm = false;
        stormFlash = 0f;
        strikes = 0;
        rolls = 0;
    }

    public String describe() {
        return "wind=" + String.format(Locale.ROOT, "%.0f", wind) + (storm ? " STORM" : "");
    }
}
