package com.mymmer.castledefense.render;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.math.MathUtils;

/**
 * Particles and floating text: the source's {@code Effects}, pooled.
 *
 * <h2>Pooled, with a complete reset</h2>
 *
 * <h2>Coordinates</h2>
 *
 * <p>Callers are gameplay, so every position arriving here is in the
 * simulation's downward-y space. It is converted <b>once, on entry</b>, and
 * everything after that — the velocities, the gravity, the drawing — is in draw
 * space with y upward. That is why {@code vy -= grav} reads correctly and why
 * the source's upward launch bias appears here with the opposite sign.
 *
 * <p>Converting on entry rather than on draw matters because a particle is drawn
 * many times and created once. It also means a burst emitted at a mob's feet is
 * at that mob's feet, which is not what happened before the Phase 11.5 image
 * comparison: every spark and every damage number was appearing mirrored about
 * the horizon.
 *
 * <p>Both arrays are allocated once at their cap and never grow. A particle is
 * taken from the free list, <b>every field written</b> in {@link Particle#set},
 * and returned on death. That "every field" is the whole contract: a pooled
 * object that keeps one stale field from its previous life is the classic
 * pooling bug, and here it would show as a spark inheriting the wrong colour or
 * an immortal one that never fades.
 *
 * <h2>It is presentation, so it runs on presentation time</h2>
 *
 * <p>Particles are advanced with the frame delta, not the simulation step. They
 * affect nothing — no damage, no collision, no gameplay reads them — so tying
 * them to the fixed step would only make them stutter on a 144 Hz display for no
 * benefit. Gameplay timing stays on simulation time; this does not.
 *
 * <p>They are still frozen when the world is, because the game loop simply does
 * not call {@link #update} outside PLAYING — the same rule that freezes the
 * Endless armoury.
 *
 * <h2>Quality changes how many, never what happens</h2>
 *
 * <p>{@code QualityConfig.maxParticles()} caps the pool: 220 on LOW, 520 on
 * MEDIUM, 900 on HIGH — the source's {@code MAX_PARTICLES}. A burst that does
 * not fit is simply smaller. Nothing else about the game differs, and
 * {@code QualityEquivalenceTest} runs identical seeded gameplay on LOW and HIGH
 * and asserts the simulations match exactly.
 */
public final class EffectsSystem implements VisualEvents {

    /** {@code sprites.MAX_PARTICLES}. The pool is allocated at this size. */
    public static final int MAX_PARTICLES = 900;
    /** {@code Effects.text}'s own cap. */
    public static final int MAX_TEXTS = 90;

    /** One spark. Mutable and reused; never handed outside this class. */
    static final class Particle {
        float x;
        float y;
        float vx;
        float vy;
        float life;
        float maxLife;
        float size;
        float grav;
        boolean circle;
        final Color color = new Color();

        /** Every field, every time. A partial reset is a stale particle. */
        void set(float x, float y, float vx, float vy, float life, int rgb,
                 float size, float grav, boolean circle) {
            this.x = x;
            this.y = y;
            this.vx = vx;
            this.vy = vy;
            this.life = life;
            this.maxLife = life;
            this.size = size;
            this.grav = grav;
            this.circle = circle;
            this.color.set((rgb >> 16 & 0xFF) / 255f, (rgb >> 8 & 0xFF) / 255f,
                    (rgb & 0xFF) / 255f, 1f);
        }
    }

    /** One rising label. */
    static final class FloatingText {
        float x;
        float y;
        float vy;
        float life;
        float maxLife;
        float size;
        String text;
        final Color color = new Color();

        void set(float x, float y, String text, int rgb, float size, float life) {
            this.x = x;
            this.y = y;
            this.vy = 46f;              // the source's -46 in a downward-y world
            this.text = text;
            this.size = size;
            this.life = life;
            this.maxLife = life;
            this.color.set((rgb >> 16 & 0xFF) / 255f, (rgb >> 8 & 0xFF) / 255f,
                    (rgb & 0xFF) / 255f, 1f);
        }
    }

    private final Particle[] pool = new Particle[MAX_PARTICLES];
    private final Array<Particle> live = new Array<>(false, MAX_PARTICLES);
    private int freeCount;

    private final FloatingText[] textPool = new FloatingText[MAX_TEXTS];
    private final Array<FloatingText> liveTexts = new Array<>(false, MAX_TEXTS);
    private int freeTexts;

    private final VisualRng rng;
    private int cap = MAX_PARTICLES;

    public EffectsSystem(VisualRng rng) {
        this.rng = rng == null ? new VisualRng(1L) : rng;
        for (int i = 0; i < pool.length; i++) {
            pool[i] = new Particle();
        }
        for (int i = 0; i < textPool.length; i++) {
            textPool[i] = new FloatingText();
        }
        freeCount = pool.length;
        freeTexts = textPool.length;
    }

    /** The cosmetic budget. Never affects anything gameplay can feel. */
    public void setQuality(com.mymmer.castledefense.config.QualityConfig quality) {
        cap = Math.min(MAX_PARTICLES, Math.max(0, quality.maxParticles()));
        while (live.size > cap) {
            recycle(live.size - 1);
        }
    }

    // ========================================================================
    //  VisualEvents
    // ========================================================================

    /**
     * {@code Effects.burst}: a spray with the source's own randomisation.
     *
     * <p>Angle uniform over the circle, speed 25–100% of the nominal, an upward
     * bias of a quarter of it, life 0.6–1.25x and size 0.6–1.4x. Every draw is
     * from the <b>decoration</b> stream.
     */
    @Override
    public void burst(float x, float y, int count, int rgb, float speed,
                      float life, float size, float grav, Shape shape) {
        y = WorldGeometry.toDrawY(y);       // see the class note on coordinates
        int room = Math.min(cap - live.size, freeCount);
        int n = MathUtils.clamp(count, 0, Math.max(0, room));
        for (int i = 0; i < n; i++) {
            float a = rng.angle();
            float s = rng.uniform(0.25f, 1f) * speed;
            Particle p = take();
            p.set(x, y, MathUtils.cos(a) * s,
                    MathUtils.sin(a) * s + speed * 0.25f,
                    life * rng.uniform(0.6f, 1.25f), rgb,
                    Math.max(1f, size * rng.uniform(0.6f, 1.4f)), grav,
                    shape == Shape.CIRCLE);
        }
    }

    /** {@code Effects.ring}: evenly spaced, flattened vertically, gravity 340. */
    @Override
    public void ring(float x, float y, int count, int rgb, float speed,
                     float life, float size) {
        y = WorldGeometry.toDrawY(y);
        int room = Math.min(cap - live.size, freeCount);
        int n = MathUtils.clamp(count, 0, Math.max(0, room));
        for (int i = 0; i < n; i++) {
            float a = MathUtils.PI2 * i / Math.max(1, n);
            Particle p = take();
            p.set(x, y, MathUtils.cos(a) * speed, MathUtils.sin(a) * speed * 0.55f,
                    life, rgb, size, 340f, true);
        }
    }

    @Override
    public void text(float x, float y, String message, int rgb, float size,
                     float life) {
        if (message == null || freeTexts <= 0 || liveTexts.size >= MAX_TEXTS) {
            return;
        }
        y = WorldGeometry.toDrawY(y);
        FloatingText t = textPool[--freeTexts];
        t.set(x, y, message, rgb, size, life);
        liveTexts.add(t);
    }

    // ========================================================================
    //  Lifecycle
    // ========================================================================

    /** Advances on the frame delta. Nothing here is gameplay. */
    public void update(float dt) {
        for (int i = live.size - 1; i >= 0; i--) {
            Particle p = live.get(i);
            p.vy -= p.grav * dt;            // gravity pulls down in a upward-y world
            p.x += p.vx * dt;
            p.y += p.vy * dt;
            p.life -= dt;
            if (p.life <= 0f) {
                recycle(i);
            }
        }
        for (int i = liveTexts.size - 1; i >= 0; i--) {
            FloatingText t = liveTexts.get(i);
            t.y += t.vy * dt;
            t.vy -= 62f * dt;               // the source's +62 downward decay
            t.life -= dt;
            if (t.life <= 0f) {
                liveTexts.removeIndex(i);
                textPool[freeTexts++] = t;
            }
        }
    }

    /** Everything back to the pool. Called on a new run, as {@code Effects.clear}. */
    public void clear() {
        while (live.size > 0) {
            recycle(live.size - 1);
        }
        while (liveTexts.size > 0) {
            FloatingText t = liveTexts.pop();
            textPool[freeTexts++] = t;
        }
    }

    private Particle take() {
        Particle p = pool[--freeCount];
        live.add(p);
        return p;
    }

    private void recycle(int index) {
        Particle p = live.removeIndex(index);
        pool[freeCount++] = p;
    }

    // ========================================================================
    //  Drawing
    // ========================================================================

    /**
     * {@code Effects.draw}: particles first, then the text on top.
     *
     * <p>A particle both darkens and shrinks as it dies —
     * {@code shade(color, 0.45 + 0.55a)} and {@code size * (0.35 + 0.65a)} —
     * which is what makes a burst read as embers cooling rather than as dots
     * vanishing.
     */
    public void paintParticles(RenderContext ctx) {
        ShapeKit.enableBlend();
        for (int i = 0; i < live.size; i++) {
            Particle p = live.get(i);
            float a = MathUtils.clamp(p.life / p.maxLife, 0f, 1f);
            Color col = ctx.shade(p.color, 0.45f + 0.55f * a);
            float s = Math.max(1f, p.size * (0.35f + 0.65f * a));
            if (p.circle) {
                ctx.kit.circle(p.x, p.y, s, col);
            } else {
                ctx.kit.rect(p.x, p.y, s, s, col);
            }
        }
    }

    /** The text half, in a batch pass. */
    public void paintTexts(RenderContext ctx) {
        for (int i = 0; i < liveTexts.size; i++) {
            FloatingText t = liveTexts.get(i);
            float a = MathUtils.clamp(t.life / t.maxLife, 0f, 1f);
            ctx.textCentered(t.x, t.y, t.text, t.size,
                    ctx.alpha(t.color, a), false);
        }
    }

    // --- for the overlay and the tests ---------------------------------------

    public int particleCount() {
        return live.size;
    }

    public int textCount() {
        return liveTexts.size;
    }

    public int freeParticles() {
        return freeCount;
    }

    public int capacity() {
        return cap;
    }

    /** Every pooled particle is either live or free, never both, never lost. */
    public boolean poolIsIntact() {
        return live.size + freeCount == MAX_PARTICLES
                && liveTexts.size + freeTexts == MAX_TEXTS;
    }
}
