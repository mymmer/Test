package com.mymmer.castledefense.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.badlogic.gdx.utils.viewport.Viewport;
import com.mymmer.castledefense.enemy.Enemy;
import com.mymmer.castledefense.enemy.EnemyType;
import com.mymmer.castledefense.game.GameMode;
import com.mymmer.castledefense.render.WorldGeometry;
import com.mymmer.castledefense.ui.TestUi;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Why a press does not grab, and the fingertip's second look.
 *
 * <h2>What was measured</h2>
 *
 * <p>"Grabbing is unreliable" is a symptom with several possible causes, so the
 * cursor now records why each press produced nothing and the phone was asked.
 * In a dense crowd every tap grabbed. Isolated mobs missed, by 23 world units
 * in one case.
 *
 * <p>The arithmetic explains it. A Scout is 26x34 world units and its grab box
 * is {@code +16} on each axis, so 42x50. On a 3040x1440 phone the world is
 * drawn at 2 px per unit, giving 84x100 physical pixels; at 3.5 px/dp that is
 * <b>24 x 29 dp</b>. Android's minimum recommended touch target is 48 dp, and
 * the contact patch of a fingertip is 8-10 mm against this target's 4.6 mm
 * width. The player is aiming at something smaller than the finger.
 *
 * <p>Ruled out by measurement rather than opinion: the hit box matches the
 * source exactly ({@code hit_rect.inflate(16, 16)}, centred on the same point);
 * the coordinate conversion is covered by {@code WorldPickingTest}; and render
 * interpolation lags the authoritative position by at most one step of motion,
 * about 2 units for a Scout, against a box 42 wide.
 *
 * <h2>The adaptation</h2>
 *
 * <p>An <b>acquisition</b> tolerance, consulted only after every exact test has
 * failed. Gameplay hit boxes are untouched, so collision, damage, splash and
 * crowd separation are exactly what the parity fixtures recorded.
 */
class GrabAcquisitionTest {

    private static final float TOLERANCE = 18f;

    private static TestUi playing(float tolerance) {
        TestUi t = new TestUi().startRun(GameMode.ENDLESS, "normal").beginPlaying();
        t.router.setWorldHandler(t.run.run.cursor());
        t.run.run.cursor().setTouchTolerance(tolerance);
        return t;
    }

    private static int[] screenFor(TestUi t, float gx, float gy) {
        Viewport vp = t.viewports.getWorld();
        float drawY = WorldGeometry.toDrawY(gy);
        float sx = vp.getScreenX() + gx / vp.getWorldWidth() * vp.getScreenWidth();
        float syUp = vp.getScreenY() + drawY / vp.getWorldHeight() * vp.getScreenHeight();
        return new int[] {
            Math.round(sx), Math.round(t.viewports.getScreenHeight() - syUp),
        };
    }

    private static void press(TestUi t, float gx, float gy) {
        int[] s = screenFor(t, gx, gy);
        t.input.touchDown(s[0], s[1], 0, 0);
        t.pump();
    }

    private static Enemy scoutAt(TestUi t, float x, float y) {
        Enemy e = t.run.run.spawnEnemy(EnemyType.SCOUT, 1);
        e.setX(x);
        e.setY(y);
        return e;
    }

    // ========================================================================
    //  The diagnostic
    // ========================================================================

    @Test
    @DisplayName("a press on nothing at all says so, rather than merely failing")
    void anEmptyFieldReportsNoTarget() {
        TestUi t = playing(0f);
        press(t, 400f, 300f);

        assertEquals(CursorInteraction.PressOutcome.NO_TARGET,
                t.run.run.cursor().lastOutcome(),
                "with no grabbable mob alive this must be NO_TARGET, not a miss");
    }

    @Test
    @DisplayName("a near miss is reported as a miss, with how far out it was")
    void aNearMissReportsItsDistance() {
        TestUi t = playing(0f);
        Enemy mob = scoutAt(t, 700f, 600f);

        //  Just outside the box: half the width plus the +16 pad, plus 10.
        float outside = 700f + (mob.width() + 16f) / 2f + 10f;
        press(t, outside, 600f);

        assertEquals(CursorInteraction.PressOutcome.MISSED,
                t.run.run.cursor().lastOutcome());
        assertEquals(10f, t.run.run.cursor().lastMissDistance(), 0.5f,
                "the reported miss distance is what separates a fingertip's "
                        + "error from a coordinate bug");
    }

    @Test
    @DisplayName("a press inside the cooldown is refused, and says which gate")
    void theCooldownIsNamed() {
        TestUi t = playing(0f);
        Enemy mob = scoutAt(t, 700f, 600f);
        press(t, 700f, 600f);
        assertSame(mob, t.run.run.cursor().grabbed(), "precondition: grabbed");
        int[] s = screenFor(t, 700f, 600f);
        t.input.touchUp(s[0], s[1], 0, 0);
        t.pump();

        press(t, 700f, 600f);

        assertEquals(CursorInteraction.PressOutcome.REFUSED_COOLDOWN,
                t.run.run.cursor().lastOutcome(),
                "the grab cooldown is a refusal, not a missed target -- these "
                        + "want completely different fixes");
    }

    // ========================================================================
    //  The tolerance
    // ========================================================================

    @Test
    @DisplayName("without a tolerance a near miss stays a miss -- the desktop")
    void withoutToleranceANearMissFails() {
        TestUi t = playing(0f);
        Enemy mob = scoutAt(t, 700f, 600f);
        float justOutside = 700f + (mob.width() + 16f) / 2f + 8f;

        press(t, justOutside, 600f);

        assertNull(t.run.run.cursor().grabbed(),
                "a mouse points at a pixel; the source's rule is exact and "
                        + "stays exact where nothing asked for otherwise");
    }

    @Test
    @DisplayName("with a tolerance the same near miss grabs -- the phone")
    void withToleranceANearMissGrabs() {
        TestUi t = playing(TOLERANCE);
        Enemy mob = scoutAt(t, 700f, 600f);
        float justOutside = 700f + (mob.width() + 16f) / 2f + 8f;

        press(t, justOutside, 600f);

        assertSame(mob, t.run.run.cursor().grabbed(),
                "the press the player plainly meant still did nothing");
    }

    @Test
    @DisplayName("a press well outside the tolerance still grabs nothing")
    void farMissesStillMiss() {
        TestUi t = playing(TOLERANCE);
        Enemy mob = scoutAt(t, 700f, 600f);
        float wellOutside = 700f + (mob.width() + 16f) / 2f + TOLERANCE + 15f;

        press(t, wellOutside, 600f);

        assertNull(t.run.run.cursor().grabbed(),
                "the tolerance reached something the player can see they "
                        + "missed, which is worse than missing");
        assertEquals(CursorInteraction.PressOutcome.MISSED,
                t.run.run.cursor().lastOutcome());
    }

    @Test
    @DisplayName("an exact hit is never overridden by something merely nearer")
    void exactBeatsNear() {
        TestUi t = playing(TOLERANCE);
        //  `under` is directly beneath the press; `nearer` is outside the press
        //  but closer to it in a straight line. Exact must win.
        Enemy under = scoutAt(t, 700f, 600f);
        Enemy beside = scoutAt(t, 700f + 40f, 600f);

        press(t, 700f, 600f);

        assertSame(under, t.run.run.cursor().grabbed(),
                "the tolerance pass ran before the exact one and lifted " + beside);
    }

    @Test
    @DisplayName("the source's nearest-threat preference decides an overlap")
    void overlapPrefersTheNearestThreat() {
        TestUi t = playing(0f);
        //  Two mobs whose boxes overlap the same point. main.py:1835 keeps the
        //  smaller x -- "prefer the nearest threat" -- and this port used to
        //  take whichever came first in the list.
        Enemy behind = scoutAt(t, 712f, 600f);
        Enemy front = scoutAt(t, 690f, 600f);
        assertTrue(front.grabCovers(701f, 600f) && behind.grabCovers(701f, 600f),
                "precondition: both boxes cover the point");

        press(t, 701f, 600f);

        assertSame(front, t.run.run.cursor().grabbed(),
                "the mob nearest the castle is the one being aimed at");
    }

    @Test
    @DisplayName("the tolerance never lifts something ungrabbable")
    void toleranceRespectsEveryGate() {
        TestUi t = playing(TOLERANCE);
        //  A plated Siege Ram: heavy, armoured, and never liftable.
        Enemy ram = t.run.run.spawnEnemy(EnemyType.SIEGE_RAM, 10);
        ram.setX(700f);
        ram.setY(600f);
        float justOutside = 700f + (ram.width() + 16f) / 2f + 8f;

        press(t, justOutside, 600f);

        assertNull(t.run.run.cursor().grabbed(),
                "the tolerance bypassed the armour gate, which would be a "
                        + "gameplay change wearing an ergonomics hat");
    }

    @Test
    @DisplayName("the gameplay hit box is untouched by the tolerance")
    void theGameplayBoxIsUnchanged() {
        TestUi t = playing(TOLERANCE);
        Enemy mob = scoutAt(t, 700f, 600f);
        float justOutside = 700f + (mob.width() + 16f) / 2f + 8f;

        //  grabCovers is what collision, splash and projectile hits ask. It
        //  must still say no at a point acquisition now accepts, or the
        //  tolerance has leaked into gameplay.
        assertTrue(!mob.grabCovers(justOutside, 600f),
                "the tolerance widened the gameplay hit box");
        press(t, justOutside, 600f);
        assertNotNull(t.run.run.cursor().grabbed(),
                "precondition: acquisition did accept it");
    }
}
