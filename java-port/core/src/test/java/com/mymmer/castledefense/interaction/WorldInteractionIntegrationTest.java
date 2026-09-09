package com.mymmer.castledefense.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.badlogic.gdx.utils.viewport.Viewport;
import com.mymmer.castledefense.boss.Boss;
import com.mymmer.castledefense.boss.BossType;
import com.mymmer.castledefense.config.GameConfig;
import com.mymmer.castledefense.enemy.Enemy;
import com.mymmer.castledefense.enemy.EnemyType;
import com.mymmer.castledefense.game.GameMode;
import com.mymmer.castledefense.render.WorldGeometry;
import com.mymmer.castledefense.ui.TestUi;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The interactions a phone could not be made to demonstrate, driven through the
 * production input path instead.
 *
 * <h2>Why these are tests and not device checks</h2>
 *
 * <p>Phase 13.1 verified grab, throw, armour stripping, tower overcharge, the
 * Troll King's crown and the Lich's staff on a real Galaxy S10+. Three things
 * resisted: the Dragon's claws are a small box on a boss that flies across the
 * screen, and {@code adb shell input} taps both miss it and coalesce; a second
 * finger cannot be injected at all; and the moment after a strip completes is
 * gone before another injected drag can arrive.
 *
 * <p>So they are driven here, through the same chain a finger uses — real
 * {@code GameInput}, real {@code InputRouter}, real {@code CursorInteraction},
 * real screen pixels converted by the real viewport. Nothing is stubbed but the
 * fingers.
 *
 * <p><b>Not</b> a spy. {@code TestUi} installs a world handler that records and
 * grabs nothing, which is right for routing tests and would make every
 * assertion here meaningless — a "nothing was grabbed" that is true because
 * nothing can ever be grabbed.
 */
class WorldInteractionIntegrationTest {

    private static TestUi playing() {
        TestUi t = new TestUi().startRun(GameMode.ENDLESS, "normal").beginPlaying();
        t.router.setWorldHandler(t.run.run.cursor());
        return t;
    }

    /** Screen pixels (origin top-left) for a point in gameplay space. */
    private static int[] screenFor(TestUi t, float gx, float gy) {
        Viewport vp = t.viewports.getWorld();
        float drawY = WorldGeometry.toDrawY(gy);
        float sx = vp.getScreenX() + gx / vp.getWorldWidth() * vp.getScreenWidth();
        float syUp = vp.getScreenY() + drawY / vp.getWorldHeight() * vp.getScreenHeight();
        return new int[] {
            Math.round(sx), Math.round(t.viewports.getScreenHeight() - syUp),
        };
    }

    private static void pressAtGameplay(TestUi t, float gx, float gy) {
        int[] s = screenFor(t, gx, gy);
        t.input.touchDown(s[0], s[1], 0, 0);
        t.pump();
    }

    private static void releaseAtGameplay(TestUi t, float gx, float gy) {
        int[] s = screenFor(t, gx, gy);
        t.input.touchUp(s[0], s[1], 0, 0);
        t.pump();
    }

    // ========================================================================
    //  Armour, and the state it leaves behind
    // ========================================================================

    @Test
    @DisplayName("a plated Siege Ram is stripped; a bare one becomes shovable")
    void strippingARamChangesWhatItIs() {
        TestUi t = playing();
        Enemy ram = t.run.run.spawnEnemy(EnemyType.SIEGE_RAM, 10);
        assertNotNull(ram, "precondition: a ram was spawned");
        ram.setX(700f);
        ram.setY(600f);
        assertTrue(ram.armored(), "precondition: the plating is on");
        assertFalse(ram.shovable(), "precondition: a plated ram cannot be hauled");

        //  Plated: the press begins a strip and refuses to lift.
        pressAtGameplay(t, 700f, 600f);
        assertSame(ram, t.run.run.cursor().stripping(),
                "a plated ram must be stripped, not grabbed");
        assertNull(t.run.run.cursor().grabbed(), "and must not be lifted");
        releaseAtGameplay(t, 700f, 600f);

        //  Strip it bare through the mechanic the cursor itself uses.
        for (int i = 0; i < 12 && ram.armored(); i++) {
            ram.applyStrip(GameConfig.STRIP_DISTANCE + 1f);
        }

        //  The transition. Note what it is NOT: a bare ram of mass 9 is still
        //  beyond an unupgraded cursor's capacity, so it becomes SHOVABLE, not
        //  liftable -- "Grab Strength alone is never enough against a tank".
        //  Asserting "liftable" here would be asserting a game this is not.
        assertFalse(ram.armored(), "the plating is off");
        assertEquals(0, ram.layers(), "no plates remain");
        assertTrue(ram.shovable(), "a stripped ram can be hauled forward");
        assertTrue(ram.tooHeavy(),
                "and is liftable in principle but beyond this cursor -- which "
                        + "is why it is shoved rather than thrown");
    }

    // ========================================================================
    //  Bosses
    // ========================================================================

    /**
     * A point the boss's regalia genuinely covers.
     *
     * <p>Found by probing rather than assumed, because the attachment offset is
     * the boss's business and hard-coding it here would make the test fail for
     * a cosmetic change. What is under test is the chain from a screen pixel to
     * the interaction, not where a crown is pinned.
     */
    private static float[] regaliaPoint(Boss b) {
        for (float dy = -80f; dy <= 80f; dy += 4f) {
            for (float dx = -80f; dx <= 80f; dx += 4f) {
                float px = b.x() + dx;
                float py = b.y() + dy;
                if (b.regaliaCovers(px, py)) {
                    return new float[] {px, py};
                }
            }
        }
        throw new AssertionError("no point on " + b + " is covered by its regalia");
    }

    @Test
    @DisplayName("the Dragon's claws are battered, not carried off")
    void dragonClawsAreSmacked() {
        TestUi t = playing();
        Enemy summoned = t.run.run.summonBoss(BossType.DRAGON, 12);
        assertNotNull(summoned, "precondition: a Dragon was summoned");
        Boss dragon = (Boss) summoned;
        assertTrue(dragon.isSmackTarget(), "precondition: claws are a smack target");

        float[] p = regaliaPoint(dragon);
        pressAtGameplay(t, p[0], p[1]);

        assertSame(dragon, t.run.run.cursor().smacking(),
                "the claws were not battered -- this is the interaction adb "
                        + "taps could not land on a boss in flight");
        assertNull(t.run.run.cursor().heldItem(),
                "claws are not an item: nothing may be detached and carried");
        assertNull(t.run.run.cursor().grabbed(), "and the Dragon is not grabbed");
    }

    @Test
    @DisplayName("two bosses keep their own regalia; taking one leaves the other")
    void twoBossesKeepTheirOwnRegalia() {
        TestUi t = playing();
        Boss troll = (Boss) t.run.run.summonBoss(BossType.TROLL_KING, 12);
        Boss lich = (Boss) t.run.run.summonBoss(BossType.LICH_LORD, 12);
        assertNotNull(troll);
        assertNotNull(lich);
        troll.setX(400f);
        lich.setX(900f);
        assertTrue(troll.regaliaAttached() && lich.regaliaAttached(),
                "precondition: both are still wearing theirs");

        float[] p = regaliaPoint(troll);
        pressAtGameplay(t, p[0], p[1]);

        assertNotNull(t.run.run.cursor().heldItem(),
                "the crown was not taken");
        assertFalse(troll.regaliaAttached(), "the Troll King kept his crown");
        assertTrue(lich.regaliaAttached(),
                "taking one boss's regalia removed another's -- they are not "
                        + "independent");
    }

    // ========================================================================
    //  Pointer ownership
    // ========================================================================

    @Test
    @DisplayName("a second finger cannot start a second world interaction")
    void secondFingerDoesNotStartASecondInteraction() {
        TestUi t = playing();
        Enemy first = t.run.run.spawnEnemy(EnemyType.SCOUT, 1);
        Enemy second = t.run.run.spawnEnemy(EnemyType.SCOUT, 1);
        first.setX(500f);
        first.setY(600f);
        second.setX(900f);
        second.setY(600f);

        int[] a = screenFor(t, 500f, 600f);
        t.input.touchDown(a[0], a[1], 0, 0);
        t.pump();
        assertSame(first, t.run.run.cursor().grabbed(), "precondition: finger one holds");

        //  A second finger lands on a different mob.
        int[] b = screenFor(t, 900f, 600f);
        t.input.touchDown(b[0], b[1], 1, 0);
        t.pump();

        assertTrue(t.input.ownsInteraction(0), "finger one still owns it");
        assertFalse(t.input.ownsInteraction(1),
                "finger two acquired a second world interaction; one gesture at "
                        + "a time is the router's rule");
        assertSame(first, t.run.run.cursor().grabbed(),
                "the second finger stole the first one's hold");
        assertNotSame(second, t.run.run.cursor().grabbed());
    }

    @Test
    @DisplayName("releasing the second finger does not end the first one's hold")
    void releasingTheSecondFingerLeavesTheFirstHolding() {
        TestUi t = playing();
        Enemy mob = t.run.run.spawnEnemy(EnemyType.SCOUT, 1);
        mob.setX(500f);
        mob.setY(600f);

        int[] a = screenFor(t, 500f, 600f);
        t.input.touchDown(a[0], a[1], 0, 0);
        t.pump();
        int[] b = screenFor(t, 900f, 300f);
        t.input.touchDown(b[0], b[1], 1, 0);
        t.pump();
        t.input.touchUp(b[0], b[1], 1, 0);
        t.pump();

        assertSame(mob, t.run.run.cursor().grabbed(),
                "the second finger lifting ended the first finger's grab");
        assertTrue(t.input.ownsInteraction(0), "and ownership survived it");
    }

}
