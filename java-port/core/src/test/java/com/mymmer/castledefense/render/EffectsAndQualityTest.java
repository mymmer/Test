package com.mymmer.castledefense.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mymmer.castledefense.config.QualityConfig;
import com.mymmer.castledefense.game.GameMode;
import com.mymmer.castledefense.progress.TestRun;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The particle pool holds its shape, and quality is cosmetic all the way down.
 */
class EffectsAndQualityTest {

    // ========================================================================
    //  Pool lifecycle
    // ========================================================================

    @Test
    @DisplayName("every particle is either live or free, never both, never lost")
    void poolIsIntact() {
        EffectsSystem fx = new EffectsSystem(new VisualRng(1L));
        assertTrue(fx.poolIsIntact());
        for (int i = 0; i < 500; i++) {
            fx.burst(100f, 100f, 20, VisualEvents.FIRE, 300f, 0.4f, 3f, 900f,
                    VisualEvents.Shape.RECT);
            fx.ring(200f, 120f, 10, VisualEvents.SPARK, 300f, 0.3f, 2f);
            fx.update(1f / 60f);
            assertTrue(fx.poolIsIntact(), "the pool leaked at iteration " + i);
        }
        for (int i = 0; i < 400; i++) {
            fx.update(1f / 60f);            // let everything die
        }
        assertEquals(0, fx.particleCount(), "nothing should still be alive");
        assertEquals(EffectsSystem.MAX_PARTICLES, fx.freeParticles(),
                "every particle should be back in the pool");
    }

    @Test
    @DisplayName("the pool never grows past its cap, however hard it is pushed")
    void poolIsBounded() {
        EffectsSystem fx = new EffectsSystem(new VisualRng(2L));
        for (int i = 0; i < 100; i++) {
            fx.burst(0f, 0f, 10000, VisualEvents.BLOOD, 400f, 5f, 3f, 900f,
                    VisualEvents.Shape.RECT);
        }
        assertEquals(EffectsSystem.MAX_PARTICLES, fx.particleCount());
        assertTrue(fx.poolIsIntact());
    }

    @Test
    @DisplayName("a recycled particle keeps nothing from its previous life")
    void recycledParticlesAreFullyReset() {
        //  The classic pooling bug: one field left over from last time.  Here it
        //  would show as a spark inheriting the wrong colour, or an immortal one.
        EffectsSystem fx = new EffectsSystem(new VisualRng(3L));
        fx.burst(0f, 0f, EffectsSystem.MAX_PARTICLES, VisualEvents.FIRE, 300f, 0.1f,
                3f, 900f, VisualEvents.Shape.CIRCLE);
        assertEquals(EffectsSystem.MAX_PARTICLES, fx.particleCount());
        for (int i = 0; i < 60; i++) {
            fx.update(1f / 60f);
        }
        assertEquals(0, fx.particleCount(), "the short-lived batch should be gone");

        //  A second batch with a long life must live its own life, not inherit
        //  the first batch's exhausted one.
        fx.burst(0f, 0f, 50, VisualEvents.MAGIC, 300f, 5f, 3f, 900f,
                VisualEvents.Shape.RECT);
        assertEquals(50, fx.particleCount());
        for (int i = 0; i < 60; i++) {
            fx.update(1f / 60f);
        }
        assertEquals(50, fx.particleCount(),
                "the new batch died on the old batch's timer");
    }

    @Test
    @DisplayName("clear returns everything, so a new run starts empty")
    void clearEmptiesThePool() {
        EffectsSystem fx = new EffectsSystem(new VisualRng(4L));
        fx.burst(0f, 0f, 400, VisualEvents.STONE, 300f, 2f, 3f, 900f,
                VisualEvents.Shape.RECT);
        fx.text(10f, 10f, "+5", VisualEvents.GOLD, 20f, 2f);
        assertTrue(fx.particleCount() > 0 && fx.textCount() > 0);

        fx.clear();
        assertEquals(0, fx.particleCount());
        assertEquals(0, fx.textCount());
        assertEquals(EffectsSystem.MAX_PARTICLES, fx.freeParticles());
        assertTrue(fx.poolIsIntact());
    }

    @Test
    @DisplayName("floating text is capped too")
    void textIsBounded() {
        EffectsSystem fx = new EffectsSystem(new VisualRng(5L));
        for (int i = 0; i < 1000; i++) {
            fx.text(0f, 0f, "+" + i, VisualEvents.GOLD, 20f, 5f);
        }
        assertEquals(EffectsSystem.MAX_TEXTS, fx.textCount());
        assertTrue(fx.poolIsIntact());
    }

    @Test
    @DisplayName("lowering quality drops live particles to the new cap")
    void loweringQualityTrimsTheLivePool() {
        EffectsSystem fx = new EffectsSystem(new VisualRng(6L));
        fx.setQuality(QualityConfig.HIGH);
        fx.burst(0f, 0f, 900, VisualEvents.FIRE, 300f, 5f, 3f, 900f,
                VisualEvents.Shape.RECT);
        assertTrue(fx.particleCount() > QualityConfig.LOW.maxParticles());

        fx.setQuality(QualityConfig.LOW);
        assertTrue(fx.particleCount() <= QualityConfig.LOW.maxParticles(),
                "dropping quality must take effect at once, not gradually");
        assertTrue(fx.poolIsIntact());
    }

    // ========================================================================
    //  Quality is cosmetic
    // ========================================================================

    @Test
    @DisplayName("identical seeded gameplay is identical on LOW and on HIGH")
    void qualityDoesNotTouchGameplay() {
        //  The invariant this locks down before Phase 12 starts moving things:
        //  a quality preset may change how many sparks there are and nothing
        //  else.  Both runs emit into a real particle system, at opposite caps.
        TestRun low = new TestRun(31337L);
        low.begin(GameMode.ENDLESS, "normal");
        EffectsSystem lowFx = new EffectsSystem(new VisualRng(1L));
        lowFx.setQuality(QualityConfig.LOW);
        low.run.setVisualEvents(lowFx);

        TestRun high = new TestRun(31337L);
        high.begin(GameMode.ENDLESS, "normal");
        EffectsSystem highFx = new EffectsSystem(new VisualRng(1L));
        highFx.setQuality(QualityConfig.HIGH);
        high.run.setVisualEvents(highFx);

        final int steps = 3600;             // a minute of play
        low.steps(steps);
        high.steps(steps);

        assertEquals(high.session().gold(), low.session().gold(), "gold");
        assertEquals(high.session().score(), low.session().score(), "score");
        assertEquals(high.session().wave(), low.session().wave(), "tier");
        assertEquals(high.aliveEnemies(), low.aliveEnemies(), "enemies alive");
        assertEquals(high.run.castle().hp(), low.run.castle().hp(), 0f, "castle");
        assertEquals(high.session().playTime(), low.session().playTime(), 0d, "clock");
        assertEquals(high.rng.game().getState(0), low.rng.game().getState(0),
                "the gameplay generators diverged");
        assertEquals(high.rng.game().getState(1), low.rng.game().getState(1));
    }

    @Test
    @DisplayName("...and MEDIUM matches them too")
    void mediumMatchesAsWell() {
        TestRun medium = new TestRun(31337L);
        medium.begin(GameMode.ENDLESS, "normal");
        EffectsSystem fx = new EffectsSystem(new VisualRng(1L));
        fx.setQuality(QualityConfig.MEDIUM);
        medium.run.setVisualEvents(fx);

        TestRun plain = new TestRun(31337L);
        plain.begin(GameMode.ENDLESS, "normal");

        medium.steps(3600);
        plain.steps(3600);

        assertEquals(plain.session().gold(), medium.session().gold());
        assertEquals(plain.session().score(), medium.session().score());
        assertEquals(plain.aliveEnemies(), medium.aliveEnemies());
        assertEquals(plain.run.castle().hp(), medium.run.castle().hp(), 0f);
    }

    @Test
    @DisplayName("the caps genuinely differ, so the equivalence test means something")
    void theCapsActuallyDiffer() {
        assertNotEquals(QualityConfig.LOW.maxParticles(),
                QualityConfig.HIGH.maxParticles());
        EffectsSystem low = new EffectsSystem(new VisualRng(9L));
        low.setQuality(QualityConfig.LOW);
        EffectsSystem high = new EffectsSystem(new VisualRng(9L));
        high.setQuality(QualityConfig.HIGH);
        for (int i = 0; i < 50; i++) {
            low.burst(0f, 0f, 40, VisualEvents.FIRE, 300f, 3f, 3f, 900f,
                    VisualEvents.Shape.RECT);
            high.burst(0f, 0f, 40, VisualEvents.FIRE, 300f, 3f, 3f, 900f,
                    VisualEvents.Shape.RECT);
        }
        assertTrue(high.particleCount() > low.particleCount(),
                "HIGH should be showing more than LOW");
    }

    @Test
    @DisplayName("LOW switches trails off; HIGH leaves them on")
    void trailsFollowQuality() {
        assertEquals(false, QualityConfig.LOW.trails());
        assertEquals(true, QualityConfig.HIGH.trails());
    }

    // ========================================================================
    //  Projectile trails are cosmetic and bounded
    // ========================================================================

    @Test
    @DisplayName("trail history is bounded and forgotten when the shot is gone")
    void trailsAreBounded() {
        TestRun t = new TestRun();
        t.begin(GameMode.CLASSIC, "normal");
        t.grantGold(100000);
        t.shop().buy("cannon");
        t.seconds(30);

        ProjectileTrails trails = new ProjectileTrails();
        for (int i = 0; i < 600; i++) {
            trails.sample(t.run.projectiles());
            t.step();
        }
        assertTrue(trails.tracked() <= t.run.projectiles().size(),
                "trails outlived their projectiles: " + trails.tracked()
                        + " tracked for " + t.run.projectiles().size() + " shots");
        for (int i = 0; i < t.run.projectiles().size(); i++) {
            long uid = t.run.projectiles().get(i).uid();
            assertTrue(trails.length(uid) <= ProjectileTrails.SAMPLES,
                    "a trail grew past its cap");
        }
        trails.clear();
        assertEquals(0, trails.tracked());
    }
}
