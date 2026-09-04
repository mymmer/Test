package com.mymmer.castledefense.progress;

import com.badlogic.gdx.utils.Array;

/**
 * The banner queue: what the run wants to tell the player, and for how long.
 *
 * <p>Gameplay state only. Phase 10 lays these out and Phase 11 animates them;
 * what lives here is the lifecycle — a message arrives, it ages on the gameplay
 * clock, it expires.
 *
 * <h2>Ids, not sentences</h2>
 *
 * <p>Python appends the rendered English straight into {@code self.banners}.
 * This stores a stable {@link Id} plus its arguments instead, so that the
 * localisation table decides the wording and a test can assert
 * {@code TIER_REACHED} rather than string-matching {@code "TIER 4"}. The
 * argument slots are deliberately primitive — an int and a string — because
 * every message in the source needs at most that.
 *
 * <h2>Ageing</h2>
 *
 * <p>Python ages banners in the <em>always</em> tier of {@code update}, before
 * the state check, so they keep counting down on the pause screen. That is
 * reproduced: {@link #age} is driven from the always-tier of the world step, not
 * from the director.
 */
public final class Announcements {

    /** Stable message identifiers. The renderer and the i18n table key off these. */
    public enum Id {
        WAVE_START,
        TIER_REACHED,
        ENDLESS_BEGIN,
        ENDLESS_SUBTITLE,
        NEW_FOE,
        ENDGAME_TIER,
        BOSS_APPROACHES,
        BOSS_ARRIVES,
        BOSS_HINT,
        WEATHER_TAILWIND,
        WEATHER_HEADWIND,
        WEATHER_STORM,
        HORN_CALLED,
        HORN_ELITES,
        WAVE_CLEARED
    }

    /** One live banner. Mutable and reused only through the owning list. */
    public static final class Banner {
        public final Id id;
        /** Numeric argument: a wave number, a tier, a head-count. Zero if unused. */
        public final int amount;
        /** Stable id argument: an enemy or boss id. Empty if unused. */
        public final String subject;
        /** Seconds it was created with, for the renderer's fade. */
        public final double life;
        double remaining;

        Banner(Id id, int amount, String subject, double life) {
            this.id = id;
            this.amount = amount;
            this.subject = subject == null ? "" : subject;
            this.life = life;
            this.remaining = life;
        }

        /** Seconds left. Time domain. */
        public double remaining() {
            return remaining;
        }

        @Override
        public String toString() {
            return id + (amount != 0 ? "(" + amount + ")" : "")
                    + (subject.isEmpty() ? "" : "[" + subject + "]");
        }
    }

    private final Array<Banner> banners = new Array<>();

    /** Posts a banner with an explicit lifetime in seconds. */
    public void post(Id id, int amount, String subject, double life) {
        if (id == null || life <= 0d) {
            return;
        }
        banners.add(new Banner(id, amount, subject, life));
    }

    public void post(Id id, double life) {
        post(id, 0, "", life);
    }

    public void post(Id id, int amount, double life) {
        post(id, amount, "", life);
    }

    /**
     * Ages every banner and drops the expired ones.
     *
     * <p>Called from the <em>always</em> tier of the world step: Python ages
     * banners before its state check, so they keep counting down while paused.
     */
    public void age(double dt) {
        for (int i = banners.size - 1; i >= 0; i--) {
            Banner b = banners.get(i);
            b.remaining -= dt;
            if (b.remaining <= 0d) {
                banners.removeIndex(i);
            }
        }
    }

    public int size() {
        return banners.size;
    }

    public Banner get(int index) {
        return banners.get(index);
    }

    /** The most recent banner with this id, or null. Test and HUD convenience. */
    public Banner latest(Id id) {
        for (int i = banners.size - 1; i >= 0; i--) {
            if (banners.get(i).id == id) {
                return banners.get(i);
            }
        }
        return null;
    }

    public boolean has(Id id) {
        return latest(id) != null;
    }

    /** Everything goes: a new run, or a mode change. */
    public void clear() {
        banners.clear();
    }
}
