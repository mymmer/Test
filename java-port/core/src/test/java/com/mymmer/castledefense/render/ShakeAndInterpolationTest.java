package com.mymmer.castledefense.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.badlogic.gdx.math.Vector2;
import com.mymmer.castledefense.enemy.Enemy;
import com.mymmer.castledefense.enemy.EnemyType;
import com.mymmer.castledefense.game.GameMode;
import com.mymmer.castledefense.progress.TestRun;
import com.mymmer.castledefense.testsupport.GlStub;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Screen shake moves the picture and nothing else, and interpolation is a
 * drawing detail that never becomes the truth.
 */
class ShakeAndInterpolationTest {

    private ViewportSet viewports;

    @BeforeEach
    void setUp() {
        GlStub.install();
        viewports = new ViewportSet();
        viewports.resize(1280, 720);        // 1:1, so screen and world coincide
    }

    @AfterEach
    void tearDown() {
        GlStub.uninstall();
    }

    // ========================================================================
    //  Shake
    // ========================================================================

    @Test
    @DisplayName("a shake displaces the world camera and then puts it back")
    void shakeIsRestored() {
        WorldShake shake = new WorldShake();
        VisualRng rng = new VisualRng(7L);
        float x0 = viewports.getWorldCamera().position.x;
        float y0 = viewports.getWorldCamera().position.y;

        shake.apply(viewports, 9f, rng);
        assertTrue(shake.isApplied());
        assertNotEquals(x0, viewports.getWorldCamera().position.x,
                "the camera should have moved");

        shake.clear(viewports);
        assertEquals(x0, viewports.getWorldCamera().position.x, 1e-4f,
                "and it must be exactly back, not approximately back");
        assertEquals(y0, viewports.getWorldCamera().position.y, 1e-4f);
        assertFalse(shake.isApplied());
    }

    @Test
    @DisplayName("a thousand shakes leave the camera exactly where it started")
    void shakeDoesNotDrift() {
        //  Add and subtract in floating point a thousand times and a naive
        //  implementation walks the camera slowly off the world.
        WorldShake shake = new WorldShake();
        VisualRng rng = new VisualRng(11L);
        float x0 = viewports.getWorldCamera().position.x;
        float y0 = viewports.getWorldCamera().position.y;
        for (int i = 0; i < 1000; i++) {
            shake.apply(viewports, 14f, rng);
            shake.clear(viewports);
        }
        assertEquals(x0, viewports.getWorldCamera().position.x, 0.01f);
        assertEquals(y0, viewports.getWorldCamera().position.y, 0.01f);
    }

    @Test
    @DisplayName("applying twice without clearing does not stack two offsets")
    void shakeDoesNotStack() {
        WorldShake shake = new WorldShake();
        VisualRng rng = new VisualRng(3L);
        float x0 = viewports.getWorldCamera().position.x;
        shake.apply(viewports, 10f, rng);
        shake.apply(viewports, 10f, rng);        // a dropped clear must not compound
        shake.clear(viewports);
        assertEquals(x0, viewports.getWorldCamera().position.x, 1e-4f);
    }

    @Test
    @DisplayName("below the source's threshold there is no offset at all")
    void tinyShakeDoesNotJitter() {
        WorldShake shake = new WorldShake();
        float x0 = viewports.getWorldCamera().position.x;
        shake.apply(viewports, WorldShake.THRESHOLD, new VisualRng(1L));
        assertEquals(0f, shake.x(), 0f);
        assertEquals(0f, shake.y(), 0f);
        assertEquals(x0, viewports.getWorldCamera().position.x, 0f);
    }

    @Test
    @DisplayName("a pointer aimed at world x still lands on world x while shaking")
    void aimIsUnaffectedByShake() {
        //  The property that matters most.  If shake reached the unprojection,
        //  a player would grab a mob that is not where they are pointing --
        //  precisely while the screen is hardest to read.
        WorldShake shake = new WorldShake();
        Vector2 quiet = new Vector2(640f, 360f);
        viewports.getWorld().unproject(quiet);

        shake.apply(viewports, 14f, new VisualRng(5L));
        shake.clear(viewports);                 // as the frame does, before input

        Vector2 after = new Vector2(640f, 360f);
        viewports.getWorld().unproject(after);
        assertEquals(quiet.x, after.x, 1e-3f, "the same pixel now means a different "
                + "world position");
        assertEquals(quiet.y, after.y, 1e-3f);
    }

    @Test
    @DisplayName("shake never touches the UI camera")
    void uiCameraIsUntouched() {
        WorldShake shake = new WorldShake();
        float ux = viewports.getUiCamera().position.x;
        float uy = viewports.getUiCamera().position.y;
        shake.apply(viewports, 14f, new VisualRng(2L));
        assertEquals(ux, viewports.getUiCamera().position.x, 0f,
                "the interface must not move with the world");
        assertEquals(uy, viewports.getUiCamera().position.y, 0f);
        shake.clear(viewports);
    }

    @Test
    @DisplayName("the shake offset comes from decoration randomness")
    void shakeUsesDecorationRandomness() {
        TestRun t = new TestRun();
        long[] before = {t.rng.game().getState(0), t.rng.game().getState(1)};
        WorldShake shake = new WorldShake();
        VisualRng rng = new VisualRng(t.rng);
        for (int i = 0; i < 500; i++) {
            shake.apply(viewports, 12f, rng);
            shake.clear(viewports);
        }
        assertEquals(before[0], t.rng.game().getState(0),
                "shaking moved the gameplay generator");
        assertEquals(before[1], t.rng.game().getState(1));
    }

    // ========================================================================
    //  Interpolation
    // ========================================================================

    @Test
    @DisplayName("a drawn position is between the last two simulation positions")
    void interpolatesBetweenSteps() {
        Interpolator interp = new Interpolator();
        TestRun t = new TestRun();
        t.begin(GameMode.ENDLESS, "normal");
        Enemy e = t.run.spawnEnemy(EnemyType.SCOUT, 1);

        e.setX(100f);
        interp.step(e.uid(), 100f, 50f);
        e.setX(110f);
        interp.step(e.uid(), 110f, 50f);

        assertEquals(100f, interp.draw(e, 110f, 50f, 0f)[0], 1e-4f, "alpha 0 is the start");
        assertEquals(105f, interp.draw(e, 110f, 50f, 0.5f)[0], 1e-4f, "halfway");
        assertEquals(110f, interp.draw(e, 110f, 50f, 1f)[0], 1e-4f, "alpha 1 is the end");
    }

    @Test
    @DisplayName("a newly spawned entity is drawn where it is, not blended in")
    void spawnDoesNotSlideIn() {
        //  Otherwise a spawning enemy visibly slides in from wherever the last
        //  entity with that slot happened to be.
        Interpolator interp = new Interpolator();
        TestRun t = new TestRun();
        Enemy e = t.run.spawnEnemy(EnemyType.SCOUT, 1);
        interp.step(e.uid(), 1370f, 60f);
        assertEquals(1370f, interp.draw(e, 1370f, 60f, 0f)[0], 1e-4f);
        assertEquals(1370f, interp.draw(e, 1370f, 60f, 0.5f)[0], 1e-4f);
    }

    @Test
    @DisplayName("a teleport is not smeared across the screen")
    void teleportIsNotInterpolated() {
        Interpolator interp = new Interpolator();
        TestRun t = new TestRun();
        Enemy e = t.run.spawnEnemy(EnemyType.SCOUT, 1);
        interp.step(e.uid(), 100f, 50f);
        interp.step(e.uid(), 105f, 50f);
        assertFalse(interp.isTeleporting(e.uid()), "ordinary motion");

        interp.step(e.uid(), 900f, 50f);            // a jump, not a walk
        assertTrue(interp.isTeleporting(e.uid()));
        assertEquals(900f, interp.draw(e, 900f, 50f, 0.5f)[0], 1e-4f,
                "a teleport must be drawn where it is");
    }

    @Test
    @DisplayName("a new run interpolates nothing from the old one")
    void resetClearsHistory() {
        Interpolator interp = new Interpolator();
        TestRun t = new TestRun();
        Enemy e = t.run.spawnEnemy(EnemyType.SCOUT, 1);
        interp.step(e.uid(), 100f, 50f);
        interp.step(e.uid(), 140f, 50f);
        assertEquals(1, interp.tracked());

        interp.clear();
        assertEquals(0, interp.tracked());
        assertEquals(600f, interp.draw(e, 600f, 90f, 0.5f)[0], 1e-4f,
                "with no history it must draw where the entity is");
    }

    @Test
    @DisplayName("history for entities that are gone is dropped")
    void historyDoesNotAccumulate() {
        //  Without this an Endless run grows the map by one entry per entity
        //  that ever existed.
        Interpolator interp = new Interpolator();
        for (int step = 0; step < 5; step++) {
            interp.beginStep();
            for (int i = 0; i < 10; i++) {
                interp.step(step * 100L + i, i * 10f, 50f);
            }
            interp.endStep();
        }
        assertEquals(10, interp.tracked(),
                "only the entities recorded in the last step should remain");
    }

    @Test
    @DisplayName("interpolation never writes back into the simulation")
    void interpolationIsNotAuthority() {
        Interpolator interp = new Interpolator();
        TestRun t = new TestRun();
        Enemy e = t.run.spawnEnemy(EnemyType.SCOUT, 1);
        e.setX(200f);
        e.setY(80f);
        interp.step(e.uid(), 100f, 50f);
        interp.step(e.uid(), 200f, 80f);

        for (float a = 0f; a <= 1f; a += 0.1f) {
            interp.draw(e, e.x(), e.y(), a);
        }
        assertEquals(200f, e.x(), 0f, "the entity moved because it was drawn");
        assertEquals(80f, e.y(), 0f);
    }
}
