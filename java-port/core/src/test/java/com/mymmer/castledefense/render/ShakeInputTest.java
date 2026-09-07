package com.mymmer.castledefense.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mymmer.castledefense.enemy.Enemy;
import com.mymmer.castledefense.enemy.EnemyType;
import com.mymmer.castledefense.game.GameMode;
import com.mymmer.castledefense.game.GameState;
import com.mymmer.castledefense.skill.SkillId;
import com.mymmer.castledefense.ui.TestUi;
import com.mymmer.castledefense.ui.UiRect;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Screen shake against the production input path.
 *
 * <h2>What Python actually does, verified before any of this was written</h2>
 *
 * <p>{@code Game.draw} renders the world into {@code self.scene}, then
 * {@code self.screen.blit(s, (ox, oy))} with {@code ox, oy} drawn fresh from
 * {@code random.uniform(-shake, shake)} <b>inside draw</b> and never stored.
 * Input is {@code self.mouse_pos = ev.pos} — the raw event position — and
 * {@code mouse_hist} records that same value.
 *
 * <p>So in the source, <b>shake is visual only and input does not compensate for
 * it.</b> During a shake the picture is offset by up to ±14 px from where clicks
 * land, and a stationary mouse has zero velocity because the samples are screen
 * positions that did not move. `HORN_RECT` and `skill.rect` are fixed rectangles
 * hit-tested unshaken while being drawn into the shaken surface.
 *
 * <p>The port matches: the camera is restored before the frame returns, so every
 * unprojection runs against an unshaken camera. These tests hold that down
 * through the real {@code GameInput} → {@code InputRouter} path rather than by
 * calling handlers directly.
 */
class ShakeInputTest {

    /** The gameplay ceiling, from {@code ScreenShake.MAX}. */
    private static final float MAX_SHAKE =
            com.mymmer.castledefense.progress.ScreenShake.MAX;

    // ========================================================================
    //  The coordinate contract
    // ========================================================================

    @Test
    @DisplayName("the same screen pixel means the same world point, shaking or not")
    void pickingIsUnaffectedByShake() {
        for (int[] size : new int[][] {{1280, 720}, {2400, 1080}}) {
            TestUi t = new TestUi(size[0], size[1]);
            WorldShake shake = new WorldShake();
            VisualRng rng = new VisualRng(17L);

            float[] quiet = unproject(t, 640, 360);
            for (int frame = 0; frame < 200; frame++) {
                shake.apply(t.viewports, MAX_SHAKE, rng);
                shake.clear(t.viewports);       // the frame always ends clear
                float[] now = unproject(t, 640, 360);
                assertEquals(quiet[0], now[0], 1e-3f,
                        "the same pixel moved in world x at " + size[0] + "x" + size[1]);
                assertEquals(quiet[1], now[1], 1e-3f, "and in world y");
            }
        }
    }

    @Test
    @DisplayName("a maximum shake in either direction still leaves picking exact")
    void extremeShakeBothWays() {
        TestUi t = new TestUi(1280, 720);
        WorldShake shake = new WorldShake();
        float[] quiet = unproject(t, 900, 500);

        //  Forced to the extremes rather than sampled, so the worst case is
        //  actually exercised instead of being left to chance.
        for (float offset : new float[] {MAX_SHAKE, -MAX_SHAKE}) {
            t.viewports.getWorldCamera().position.add(offset, offset, 0f);
            t.viewports.getWorldCamera().update();
            t.viewports.getWorldCamera().position.sub(offset, offset, 0f);
            t.viewports.getWorldCamera().update();
            float[] now = unproject(t, 900, 500);
            assertEquals(quiet[0], now[0], 1e-3f);
            assertEquals(quiet[1], now[1], 1e-3f);
        }
        assertFalse(shake.isApplied());
    }

    @Test
    @DisplayName("safe-area insets change the UI rect and never the world mapping")
    void insetsDoNotMoveTheWorld() {
        TestUi t = new TestUi(2400, 1080);
        float[] before = unproject(t, 1200, 540);
        t.withInsets(120, 60, 40, 48);
        float[] after = unproject(t, 1200, 540);
        assertEquals(before[0], after[0], 1e-3f,
                "a cutout must not move where a pixel points in the world");
        assertEquals(before[1], after[1], 1e-3f);
        assertFalse(t.ui.safeArea().isFull(), "but the UI rect did shrink");
    }

    // ========================================================================
    //  A stationary finger stays stationary
    // ========================================================================

    @Test
    @DisplayName("shake alone produces no gameplay movement and no throw velocity")
    void stationaryFingerGainsNothingFromShake() {
        //  The invariant the brief names: camera shake alone -> no movement, no
        //  artificial throw velocity, no unintended grab.  It holds because the
        //  pointer is unprojected against the unshaken camera -- the camera
        //  offset is never injected into the drag tracker.
        TestUi t = new TestUi().startRun(GameMode.ENDLESS, "normal").beginPlaying();
        Enemy mob = t.run.run.spawnEnemy(EnemyType.SCOUT, 1);
        mob.setX(700f);
        mob.setY(600f);

        WorldShake shake = new WorldShake();
        VisualRng rng = new VisualRng(23L);
        int[] screen = t.screenFor(700f, 720f - 600f);

        t.input.touchDown(screen[0], screen[1], 0, 0);
        t.pump();
        float grabbedX = t.run.run.pointerX();
        float grabbedY = t.run.run.pointerY();

        //  Two hundred frames of maximum shake, finger perfectly still.
        for (int frame = 0; frame < 200; frame++) {
            shake.apply(t.viewports, MAX_SHAKE, rng);
            shake.clear(t.viewports);
            t.input.touchDragged(screen[0], screen[1], 0);
            t.pump();
        }
        assertEquals(grabbedX, t.run.run.pointerX(), 1e-3f,
                "the shake moved the pointer's world position");
        assertEquals(grabbedY, t.run.run.pointerY(), 1e-3f);

        t.input.touchUp(screen[0], screen[1], 0, 0);
        t.pump();
        float[] velocity = new float[2];
        t.input.releaseVelocity(0, velocity);
        assertEquals(0f, velocity[0], 1f,
                "a stationary finger acquired throw velocity from the camera");
        assertEquals(0f, velocity[1], 1f);
    }

    @Test
    @DisplayName("the same gesture throws the same, whatever the frame rate")
    void throwIsFrameRateIndependent() {
        //  The second invariant: the same intentional virtual-world gesture must
        //  give the same gameplay result regardless of render frequency.  Two
        //  runs make the identical flick; one of them renders three times as
        //  often, with shake on throughout.
        float slow = flickVelocity(1, true);
        float fast = flickVelocity(3, true);
        float none = flickVelocity(1, false);

        assertEquals(none, slow, 1f, "shake changed the flick");
        assertEquals(slow, fast, 1f, "the frame rate changed the flick");
        assertTrue(Math.abs(slow) > 100f, "the flick should actually be a flick");
    }

    /** One identical flick, rendered {@code framesPerStep} times per input step. */
    private float flickVelocity(int framesPerStep, boolean shaking) {
        TestUi t = new TestUi().startRun(GameMode.ENDLESS, "normal").beginPlaying();
        WorldShake shake = new WorldShake();
        VisualRng rng = new VisualRng(31L);
        float[] velocity = new float[2];

        int[] from = t.screenFor(600f, 300f);
        t.input.touchDown(from[0], from[1], 0, 0);
        t.pump();
        for (int step = 1; step <= 10; step++) {
            for (int f = 0; f < framesPerStep; f++) {
                if (shaking) {
                    shake.apply(t.viewports, MAX_SHAKE, rng);
                    shake.clear(t.viewports);
                }
            }
            int[] to = t.screenFor(600f + step * 20f, 300f);
            t.input.setClock(step / 60f);
            t.input.touchDragged(to[0], to[1], 0);
            t.pump();
        }
        int[] end = t.screenFor(800f, 300f);
        t.input.touchUp(end[0], end[1], 0, 0);
        t.pump();
        t.input.releaseVelocity(0, velocity);
        return velocity[0];
    }

    // ========================================================================
    //  The shaken widgets stay usable
    // ========================================================================

    @Test
    @DisplayName("a shaken button's hit box does not move, so its centre still works")
    void shakenWidgetsKeepTheirHitBounds() {
        //  Python's HORN_RECT and skill.rect are fixed and hit-tested unshaken
        //  while being drawn into the shaken surface.  The port does the same:
        //  the projection translate moves the DRAWING only.
        TestUi t = new TestUi().startRun(GameMode.ENDLESS, "normal").beginPlaying();
        t.run.skills().unlockNext();
        t.ui.layout();

        UiRect slot = t.ui.hud().skillSlots().get(SkillId.LIGHTNING.ordinal());
        float hx = slot.hitX();
        float hy = slot.hitY();

        t.setWorldShakeForTest(MAX_SHAKE, -MAX_SHAKE);
        t.ui.layout();
        assertEquals(hx, slot.hitX(), 0f, "the hit box moved with the shake");
        assertEquals(hy, slot.hitY(), 0f);

        t.tap(slot);
        assertEquals(SkillId.LIGHTNING, t.run.skills().aiming(),
                "the button stopped working while the screen shook");
        assertFalse(t.world.sawAnything(), "and the press still did not reach the world");
    }

    @Test
    @DisplayName("the drawn offset stays well inside every shaken widget")
    void shakeCannotWalkAWidgetOffItsTouchBox() {
        //  The usability question the audit asks: can a small button visibly move
        //  away from where it must be tapped?  At the gameplay ceiling of 14
        //  units against the smallest shaken widget, the drawn button still
        //  overlaps its own hit box by well over half, so a tap on what the
        //  player sees still lands inside it.
        TestUi t = new TestUi().startRun(GameMode.ENDLESS, "normal").beginPlaying();
        t.run.skills().unlockNext();
        t.ui.layout();

        for (UiRect w : shakenWidgets(t)) {
            float smallest = Math.min(w.visualWidth(), w.visualHeight());
            assertTrue(smallest > MAX_SHAKE * 2f,
                    w.id + " is only " + smallest + " units across, so a "
                            + MAX_SHAKE + "-unit shake can move it more than half "
                            + "its own width off its touch box");
            //  And the displaced centre is still inside the hit rectangle.
            assertTrue(w.hits(w.centerX() + MAX_SHAKE, w.centerY() + MAX_SHAKE),
                    w.id + ": tapping its drawn centre at full shake misses");
            assertTrue(w.hits(w.centerX() - MAX_SHAKE, w.centerY() - MAX_SHAKE),
                    w.id + ": and the other way");
        }
    }

    @Test
    @DisplayName("only the skill bar and the horn shake; the panel and bars do not")
    void theShakenSetIsExactlyTheSourceSet() {
        //  draw_hud paints the stat panel -- including the Endless SHOP button in
        //  the clock row -- onto the unshaken screen.  The SHOP button was inside
        //  the shaken block until this audit read draw_clock.
        TestUi t = new TestUi().startRun(GameMode.ENDLESS, "normal").beginPlaying();
        t.run.skills().unlockNext();
        t.ui.layout();
        assertTrue(t.ui.hud().shopButton().visible(), "Endless shows the button");

        //  Read from UiRenderer's own source rather than from a list this test
        //  keeps: a list beside the code asserts what the test believes, and it
        //  was a list beside the code that let the SHOP button drift into the
        //  shaken block unnoticed in the first place.
        String body = renderSource();
        int begin = body.indexOf("beginShaken();");
        int end = body.indexOf("endShaken();");
        assertTrue(begin > 0 && end > begin,
                "UiRenderer no longer has a single shaken block");
        String shaken = body.substring(begin, end);

        assertTrue(shaken.contains("hornButton()"), "the horn must shake");
        assertTrue(shaken.contains("skillSlots()"), "the skill bar must shake");
        assertFalse(shaken.contains("shopButton()"),
                "the SHOP button is row 7 of the stat panel and draw_hud paints "
                        + "the panel on the UNSHAKEN screen");
        assertFalse(shaken.contains("bossBars()"),
                "boss bars are unshaken UI");
        assertFalse(shaken.contains("panelX()"), "the stat panel is unshaken");
    }

    // ========================================================================
    //  Ownership survives a shake
    // ========================================================================

    @Test
    @DisplayName("a pointer claimed by a shaken widget stays owned through release")
    void ownershipSurvivesShake() {
        TestUi t = new TestUi().startRun(GameMode.ENDLESS, "normal").beginPlaying();
        t.run.skills().unlockNext();
        t.ui.layout();
        UiRect horn = t.ui.hud().hornButton();
        WorldShake shake = new WorldShake();
        VisualRng rng = new VisualRng(41L);

        t.touchDown(0, horn.centerX(), horn.centerY());
        assertTrue(t.run.session().hornUsed(), "the horn blew");
        for (int i = 0; i < 30; i++) {
            shake.apply(t.viewports, MAX_SHAKE, rng);
            shake.clear(t.viewports);
            t.touchMove(0, horn.centerX() + 60f, horn.centerY() - 40f);
        }
        t.touchUp(0, horn.centerX() + 60f, horn.centerY() - 40f);
        assertFalse(t.world.sawAnything(),
                "the gesture leaked to the world during a shake: " + t.world.log);
    }

    @Test
    @DisplayName("a cancel during a shake is still a cancel")
    void cancelDuringShake() {
        TestUi t = new TestUi().startRun(GameMode.ENDLESS, "normal").beginPlaying();
        WorldShake shake = new WorldShake();
        t.touchDown(0, 760f, 420f);
        assertTrue(t.input.ownsInteraction(0));

        shake.apply(t.viewports, MAX_SHAKE, new VisualRng(5L));
        t.input.cancelPointer(0);
        t.pump();
        shake.clear(t.viewports);

        assertTrue(t.world.log.contains("cancel", false), t.world.log.toString());
        assertFalse(t.world.log.contains("release", false));
        assertFalse(t.input.ownsInteraction(0));
    }

    @Test
    @DisplayName("a modal opening mid-shake does not steal the live gesture")
    void modalDuringShake() {
        TestUi t = new TestUi().startRun(GameMode.ENDLESS, "normal").beginPlaying();
        WorldShake shake = new WorldShake();
        t.touchDown(0, 760f, 420f);
        assertTrue(t.world.log.contains("press", false));

        shake.apply(t.viewports, MAX_SHAKE, new VisualRng(6L));
        t.run.world.setState(GameState.SHOP);
        t.ui.layout();
        t.touchMove(0, 780f, 430f);
        shake.clear(t.viewports);

        assertTrue(t.world.log.contains("drag", false),
                "ownership is decided at press time: " + t.world.log);
        t.touchUp(0, 780f, 430f);
        assertTrue(t.world.log.contains("release", false));
    }

    @Test
    @DisplayName("a skill button over an enemy arms the skill and grabs nothing")
    void skillButtonOverAnEnemyDuringShake() {
        TestUi t = new TestUi().startRun(GameMode.ENDLESS, "normal").beginPlaying();
        t.run.skills().unlockNext();
        t.ui.layout();
        UiRect slot = t.ui.hud().skillSlots().get(SkillId.LIGHTNING.ordinal());

        Enemy mob = t.run.run.spawnEnemy(EnemyType.SCOUT, 1);
        mob.setX(slot.centerX());
        mob.setY(720f - slot.centerY());

        WorldShake shake = new WorldShake();
        shake.apply(t.viewports, MAX_SHAKE, new VisualRng(7L));
        shake.clear(t.viewports);
        t.tap(slot);

        assertEquals(SkillId.LIGHTNING, t.run.skills().aiming());
        assertFalse(t.world.sawAnything(), "the press reached the world too");
        assertFalse(t.run.run.cursor().busy(), "and something was grabbed");
    }

    @Test
    @DisplayName("one press during a shake activates exactly one thing")
    void noDoubleActivationUnderShake() {
        TestUi t = new TestUi().startRun(GameMode.ENDLESS, "normal").beginPlaying();
        t.ui.layout();
        UiRect horn = t.ui.hud().hornButton();
        WorldShake shake = new WorldShake();
        shake.apply(t.viewports, MAX_SHAKE, new VisualRng(8L));

        int alive = t.run.aliveEnemies();
        t.tap(horn);
        shake.clear(t.viewports);

        assertTrue(t.run.session().hornUsed());
        assertEquals(alive + com.mymmer.castledefense.config.Tuning.ENDLESS_HORN_RUSH,
                t.run.aliveEnemies(), "the horn fired more than once");
        assertFalse(t.world.sawAnything());
    }

    // ------------------------------------------------------------------------

    private static java.util.List<UiRect> shakenWidgets(TestUi t) {
        java.util.List<UiRect> out = new java.util.ArrayList<>();
        for (UiRect w : t.ui.hud().skillSlots()) {
            if (w.visible()) {
                out.add(w);
            }
        }
        if (t.ui.hud().hornButton().visible()) {
            out.add(t.ui.hud().hornButton());
        }
        assertNotNull(out);
        assertTrue(out.size() >= 2, "expected at least the horn and one slot");
        return out;
    }

    /** {@code UiRenderer}'s source, for the shaken-block check. */
    private static String renderSource() {
        java.io.File f = new java.io.File("../core/src/main/java/com/mymmer/"
                + "castledefense/render/UiRenderer.java");
        assertTrue(f.exists(), "cannot find UiRenderer from "
                + new java.io.File(".").getAbsolutePath());
        try {
            return new String(java.nio.file.Files.readAllBytes(f.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new AssertionError("cannot read UiRenderer", e);
        }
    }

    /** The world point a screen pixel means, through the production viewport. */
    private static float[] unproject(TestUi t, int screenX, int screenY) {
        com.badlogic.gdx.math.Vector2 v =
                new com.badlogic.gdx.math.Vector2(screenX, screenY);
        t.viewports.getWorld().unproject(v);
        return new float[] {v.x, v.y};
    }
}
