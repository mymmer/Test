package com.mymmer.castledefense.enemy;

/**
 * Glass cannon; hits like a truck, and gets angrier as it takes damage.
 *
 * <h2>The speed quirk — reproduced, not fixed</h2>
 *
 * <p>{@code Berzerker.think} <b>recomputes</b> its speed from scratch:
 *
 * <pre>
 *   speed = BASE_SPEED * waveScalingSpeed(wave) * rage
 * </pre>
 *
 * <p>It does not start from {@code this.speed}, so for the duration of that call
 * it <b>discards the difficulty speed multiplier and the endgame-tier speed
 * multiplier</b>, and any permanent speed loss from armour stripping. On Hard at
 * wave 36 a raging Berzerker is therefore <em>slower</em> than a calm one.
 *
 * <p>That reads like a bug. It is also authoritative: it is what the shipped
 * game does, and the port's job is to match it. {@code BerzerkerQuirkTest} pins
 * it by name so a later cleanup cannot quietly change how fast the unit closes.
 * If it is ever fixed, that will be a deliberate balance change with its own
 * decision behind it — not a refactor.
 *
 * <p>Note the ordering: the rage speed is installed <em>before</em> the base
 * {@code think} runs, so the talent slow still multiplies it, and the original
 * value is put back afterwards.
 */
public final class Berzerker extends Enemy {

    public Berzerker(EnemyContext ctx, EnemyConfig config, int wave, Float x, Float y) {
        super(ctx, config, wave, x, y);
    }

    /** 1.0 at full health, rising to 1.45 at death's door. */
    public float rage() {
        return 1f + 0.45f * (1f - hp() / Math.max(1f, maxHp()));
    }

    @Override
    protected void think(double dt) {
        float old = speed;
        speed = config.baseSpeed * WaveScaling.speed(wave) * rage();
        super.think(dt);
        speed = old;
    }
}
