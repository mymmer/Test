package com.mymmer.castledefense.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.badlogic.gdx.utils.Array;
import com.mymmer.castledefense.boss.BossType;
import com.mymmer.castledefense.game.GameMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Boss health bars, against the source's own arrangement.
 *
 * <p>{@code draw_hud} lays them out like this:
 *
 * <pre>
 *   bosses = self.current_bosses()[:2]
 *   bw     = 620 if len(bosses) == 1 else 400
 *   step   = bw + 24
 *   x0     = WIDTH // 2 - (len(bosses) * step - 24) // 2
 *   name   at (cx, HEIGHT - 78), size 24, (255, 210, 130), bold
 *   bar    at (bx, HEIGHT - 50), 20 tall, fill (208, 62, 60)
 *   hp     at (cx, HEIGHT - 48), size 18, white
 * </pre>
 *
 * <p>Phase 11 had them across the top of the screen, one per boss with no cap,
 * magenta, with the name inside the bar and no hit points at all. Every one of
 * those was found by putting a Java capture beside a Python one, so this test
 * exists to make the next such drift a build failure instead.
 */
class BossBarLayoutTest {

    /** {@code WIDTH // 2} in UI units, at the reference size. */
    private static final float CENTRE = 640f;

    private static TestUi playing() {
        TestUi t = new TestUi(1280, 720)
                .startRun(GameMode.ENDLESS, "normal").beginPlaying();
        t.ui.layout();
        return t;
    }

    // ========================================================================
    //  One boss
    // ========================================================================

    @Test
    @DisplayName("a lone boss gets a 620-wide bar, centred, 30 above the floor")
    void oneBoss() {
        TestUi t = playing();
        t.run.run.summonBoss(BossType.TROLL_KING, 5);
        t.ui.layout();

        assertEquals(1, t.ui.hud().visibleBossBars());
        UiRect bar = t.ui.hud().bossBars().get(0);
        assertEquals(HudScreen.BOSS_BAR_WIDE, bar.visualWidth(), 0.5f,
                "a single boss uses the wide bar");
        assertEquals(HudScreen.BOSS_BAR_HEIGHT, bar.visualHeight(), 0.5f);
        assertEquals(CENTRE, bar.centerX(), 0.5f, "and it is centred");
        assertEquals(HudScreen.BOSS_BAR_BOTTOM, bar.visualY(), 0.5f,
                "the bar sits 30 units up from the floor, as HEIGHT-50 does");
    }

    // ========================================================================
    //  Two bosses
    // ========================================================================

    @Test
    @DisplayName("two bosses get 400-wide bars, 24 apart, straddling the centre")
    void twoBosses() {
        TestUi t = playing();
        t.run.run.summonBoss(BossType.TROLL_KING, 5);
        t.run.run.summonBoss(BossType.DRAGON, 5);
        t.ui.layout();

        assertEquals(2, t.ui.hud().visibleBossBars());
        UiRect left = t.ui.hud().bossBars().get(0);
        UiRect right = t.ui.hud().bossBars().get(1);

        assertEquals(HudScreen.BOSS_BAR_PAIR, left.visualWidth(), 0.5f);
        assertEquals(HudScreen.BOSS_BAR_PAIR, right.visualWidth(), 0.5f);
        assertEquals(left.visualWidth(), right.visualWidth(), 0.01f,
                "a pair must be the same size as each other");
        assertEquals(HudScreen.BOSS_BAR_GAP,
                right.visualX() - (left.visualX() + left.visualWidth()), 0.5f,
                "the gap between them is the source's 24");
        assertEquals(CENTRE, (left.visualX() + right.visualX()
                + right.visualWidth()) / 2f, 0.5f,
                "the pair is centred as a group");
        assertEquals(left.visualY(), right.visualY(), 0.01f,
                "and they are level with each other");
    }

    @Test
    @DisplayName("two bars never overlap, and both stay inside the safe rect")
    void twoBarsFit() {
        for (int[] size : new int[][] {{1280, 720}, {2400, 1080}, {1080, 2400}}) {
            TestUi t = new TestUi(size[0], size[1])
                    .startRun(GameMode.ENDLESS, "normal").beginPlaying();
            t.withInsets(96, 96, 44, 56);
            t.run.run.summonBoss(BossType.TROLL_KING, 5);
            t.run.run.summonBoss(BossType.DRAGON, 5);
            t.ui.layout();

            UiRect a = t.ui.hud().bossBars().get(0);
            UiRect b = t.ui.hud().bossBars().get(1);
            String at = " at " + size[0] + "x" + size[1];
            assertFalse(a.hitOverlaps(b), "the two bars overlap" + at);
            SafeArea safe = t.ui.safeArea();
            assertTrue(safe.contains(a), "the left bar escapes the safe rect" + at);
            assertTrue(safe.contains(b), "the right bar escapes the safe rect" + at);
        }
    }

    @Test
    @DisplayName("a third boss gets no bar, as the source's [:2] slice gives none")
    void atMostTwo() {
        TestUi t = playing();
        t.run.run.summonBoss(BossType.TROLL_KING, 5);
        t.run.run.summonBoss(BossType.DRAGON, 5);
        t.run.run.summonBoss(BossType.LICH_LORD, 5);
        t.ui.layout();

        assertEquals(3, t.run.run.bossRegistry().liveBosses().size,
                "all three really are alive");
        assertEquals(2, t.ui.hud().visibleBossBars(),
                "but only two are shown -- current_bosses()[:2]");
    }

    // ========================================================================
    //  Lifecycle
    // ========================================================================

    @Test
    @DisplayName("a death removes one bar and rearranges the survivor to full width")
    void deathRearranges() {
        TestUi t = playing();
        t.run.run.summonBoss(BossType.TROLL_KING, 5);
        t.run.run.summonBoss(BossType.DRAGON, 5);
        t.ui.layout();
        assertEquals(HudScreen.BOSS_BAR_PAIR,
                t.ui.hud().bossBars().get(0).visualWidth(), 0.5f);

        t.run.run.bossRegistry().liveBosses().get(0).die(true);
        t.run.step();
        t.ui.layout();

        assertEquals(1, t.ui.hud().visibleBossBars(), "no stale bar for a dead boss");
        assertEquals(HudScreen.BOSS_BAR_WIDE,
                t.ui.hud().bossBars().get(0).visualWidth(), 0.5f,
                "the survivor widens to the lone-boss size");
        assertEquals(CENTRE, t.ui.hud().bossBars().get(0).centerX(), 0.5f);
    }

    @Test
    @DisplayName("with no boss alive there are no bars at all")
    void noBossNoBars() {
        TestUi t = playing();
        assertEquals(0, t.ui.hud().visibleBossBars());
        Array<UiRect> bars = t.ui.hud().bossBars();
        for (int i = 0; i < bars.size; i++) {
            assertFalse(bars.get(i).visible(), "bar " + i + " is still showing");
        }
    }

    @Test
    @DisplayName("the bars are laid out but not drawn outside PLAYING")
    void hiddenWhenNotPlaying() {
        //  The source guards the whole block with `self.state == self.PLAYING`,
        //  so a boss bar does not hang over the armoury or the game-over panel.
        TestUi t = playing();
        t.run.run.summonBoss(BossType.DRAGON, 5);
        t.ui.layout();
        assertEquals(1, t.ui.hud().visibleBossBars());

        t.run.world.setState(com.mymmer.castledefense.game.GameState.GAMEOVER);
        t.ui.layout();
        assertEquals(0, t.ui.hud().visibleBossBars(),
                "a boss bar must not survive into the game-over screen");
    }
}
