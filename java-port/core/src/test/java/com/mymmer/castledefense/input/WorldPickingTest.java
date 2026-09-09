package com.mymmer.castledefense.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.badlogic.gdx.utils.viewport.Viewport;
import com.mymmer.castledefense.config.GameConfig;
import com.mymmer.castledefense.enemy.Enemy;
import com.mymmer.castledefense.enemy.EnemyType;
import com.mymmer.castledefense.game.GameMode;
import com.mymmer.castledefense.render.WorldGeometry;
import com.mymmer.castledefense.ui.TestUi;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A finger lands where the finger is — in the space gameplay uses.
 *
 * <h2>The defect this exists for</h2>
 *
 * <p>The viewport unprojects a touch into <b>draw space</b>, which is y-up like
 * the rest of libGDX. The simulation keeps Pygame's y-down world, where the
 * ground is {@code GROUND_Y = 620} measured from the top. {@link WorldGeometry}
 * is the one boundary between them, and until Phase 13 the input path did not
 * cross it: a touch was unprojected and handed to gameplay unconverted.
 *
 * <p>So a finger on the ground arrived as gameplay y 125 — up in the sky —
 * and nothing was ever underneath it. Grabbing, throwing, armour stripping,
 * tower overcharge, the boss crown and the Dragon's claws were all unreachable,
 * on every platform, for three phases. It was found by holding a finger on a
 * crowd of mobs on a real phone and watching nothing happen.
 *
 * <h2>Why the existing tests did not catch it</h2>
 *
 * <p>They tested the right code and asked the wrong question.
 * {@code ShakeInputTest} drives this exact path and even names a mob at y 600 —
 * but it asserts that the pointer <em>does not drift</em> under camera shake,
 * comparing the value against itself. A number that is consistently wrong passes
 * that test perfectly. The router tests build pointers directly, in whichever
 * space they were asserting.
 *
 * <p>So these tests assert the one thing none of them did: the absolute
 * position, against a landmark whose gameplay value is known.
 */
class WorldPickingTest {

    /**
     * A live run whose world handler is the real cursor.
     *
     * <p>{@code TestUi} installs a spy in that seat, which is right for routing
     * tests and useless here: a spy grabs nothing, so every assertion about
     * grabbing would pass or fail for reasons that have nothing to do with the
     * game. The production {@code CursorInteraction} goes in instead.
     */
    private static TestUi playing() {
        TestUi t = new TestUi().startRun(GameMode.ENDLESS, "normal").beginPlaying();
        t.router.setWorldHandler(t.run.run.cursor());
        return t;
    }

    /** Screen pixels (origin top-left) for a point in <b>gameplay</b> space. */
    private static int[] screenForGameplay(TestUi t, float gx, float gy) {
        Viewport vp = t.viewports.getWorld();
        float drawY = WorldGeometry.toDrawY(gy);
        float sx = vp.getScreenX() + gx / vp.getWorldWidth() * vp.getScreenWidth();
        float syFromBottom =
                vp.getScreenY() + drawY / vp.getWorldHeight() * vp.getScreenHeight();
        return new int[] {
            Math.round(sx),
            Math.round(t.viewports.getScreenHeight() - syFromBottom),
        };
    }

    @Test
    @DisplayName("a touch on the ground reports the ground, not the sky")
    void groundIsGround() {
        TestUi t = playing();
        int[] s = screenForGameplay(t, 700f, GameConfig.GROUND_Y);

        t.input.touchDown(s[0], s[1], 0, 0);

        assertEquals(GameConfig.GROUND_Y, t.input.pointer(0).worldY(), 1.0f,
                "the pointer is in draw space, so gameplay sees the sky where "
                        + "the finger is on the ground");
        assertEquals(700f, t.input.pointer(0).worldX(), 1.0f);
    }

    @Test
    @DisplayName("down the screen is a larger gameplay y, as the simulation counts it")
    void theAxisPointsTheRightWay() {
        TestUi t = playing();

        int[] high = screenForGameplay(t, 640f, 100f);      // near the sky
        int[] low = screenForGameplay(t, 640f, 600f);       // near the ground
        assertTrue(high[1] < low[1],
                "precondition: the sky is nearer the top of the screen");

        t.input.touchDown(high[0], high[1], 0, 0);
        float skyY = t.input.pointer(0).worldY();
        t.input.touchUp(high[0], high[1], 0, 0);
        t.pump();

        t.input.touchDown(low[0], low[1], 0, 0);
        float groundY = t.input.pointer(0).worldY();

        assertTrue(skyY < groundY,
                "gameplay y grows downward (GROUND_Y = 620 from the top); a "
                        + "flipped axis reports the sky as the larger value");
        assertEquals(100f, skyY, 1.0f);
        assertEquals(600f, groundY, 1.0f);
    }

    @Test
    @DisplayName("screen and gameplay round-trip at several points")
    void roundTrips() {
        TestUi t = playing();
        float[][] points = {
            {100f, 80f}, {640f, 360f}, {1200f, 620f}, {900f, 500f},
        };
        for (float[] p : points) {
            int[] s = screenForGameplay(t, p[0], p[1]);
            t.input.touchDown(s[0], s[1], 0, 0);
            assertEquals(p[0], t.input.pointer(0).worldX(), 1.0f,
                    "x at gameplay (" + p[0] + "," + p[1] + ")");
            assertEquals(p[1], t.input.pointer(0).worldY(), 1.0f,
                    "y at gameplay (" + p[0] + "," + p[1] + ")");
            t.input.touchUp(s[0], s[1], 0, 0);
            t.pump();
        }
    }

    @Test
    @DisplayName("pressing on a mob grabs that mob, through the production path")
    void pressingAMobGrabsIt() {
        TestUi t = playing();
        Enemy mob = t.run.run.spawnEnemy(EnemyType.SCOUT, 1);
        mob.setX(700f);
        mob.setY(600f);

        assertNull(t.run.run.cursor().grabbed(), "precondition: nothing held");

        int[] s = screenForGameplay(t, 700f, 600f);
        t.input.touchDown(s[0], s[1], 0, 0);
        t.pump();

        assertNotNull(t.run.run.cursor().grabbed(),
                "the press found nothing under it -- which is what a whole "
                        + "phase of unreachable world interaction looked like");
        assertSame(mob, t.run.run.cursor().grabbed(),
                "a different mob was grabbed than the one pressed");
    }

    @Test
    @DisplayName("a press on empty sky grabs nothing")
    void emptySkyGrabsNothing() {
        TestUi t = playing();
        Enemy mob = t.run.run.spawnEnemy(EnemyType.SCOUT, 1);
        mob.setX(700f);
        mob.setY(600f);

        //  The mirror image of the mob about the horizon: exactly where the
        //  flipped conversion used to send a finger aimed at it.
        int[] s = screenForGameplay(t, 700f, WorldGeometry.toDrawY(600f));
        t.input.touchDown(s[0], s[1], 0, 0);
        t.pump();

        assertNull(t.run.run.cursor().grabbed(),
                "something was grabbed from empty sky, so the axis is inverted");
    }

    @Test
    @DisplayName("dragging a held mob moves it to the finger, in gameplay space")
    void dragFollowsTheFinger() {
        TestUi t = playing();
        Enemy mob = t.run.run.spawnEnemy(EnemyType.SCOUT, 1);
        mob.setX(700f);
        mob.setY(600f);

        int[] down = screenForGameplay(t, 700f, 600f);
        t.input.touchDown(down[0], down[1], 0, 0);
        t.pump();
        assertNotNull(t.run.run.cursor().grabbed(), "precondition: held");

        //  Lift it towards the sky, which is a SMALLER gameplay y.
        int[] up = screenForGameplay(t, 760f, 300f);
        t.input.touchDragged(up[0], up[1], 0);
        t.pump();
        //  A held mob is carried to the cursor by the simulation, not by the
        //  router: routing delivers the drag, the step applies it.
        t.run.run.setPointer(true, t.input.pointer(0).worldX(),
                t.input.pointer(0).worldY());
        t.run.step();

        assertEquals(300f, t.input.pointer(0).worldY(), 1.0f);
        assertTrue(mob.y() < 600f,
                "the mob was dragged towards the sky, so its gameplay y must "
                        + "fall; it rose instead, which means the drag is "
                        + "working in the wrong space");
    }
}
