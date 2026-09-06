package com.mymmer.castledefense.render;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.badlogic.gdx.utils.Array;
import com.mymmer.castledefense.enemy.Enemy;
import com.mymmer.castledefense.enemy.EnemyType;
import com.mymmer.castledefense.game.GameMode;
import com.mymmer.castledefense.progress.TestRun;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Rendering reads the game. It does not touch it.
 *
 * <p>The whole phase rests on this, so it is asserted from several directions
 * rather than argued for once:
 *
 * <ul>
 *   <li>the gameplay generator's stream position is unmoved by any amount of
 *       rendering work;</li>
 *   <li>the authoritative enemy list keeps its insertion order after a draw-order
 *       sort;</li>
 *   <li>entity positions, health and the run's own state are identical before
 *       and after;</li>
 *   <li>the presentation event sink cannot answer back, so nothing gameplay does
 *       can depend on whether anyone is drawing.</li>
 * </ul>
 */
class RenderPurityTest {

    // ========================================================================
    //  Randomness
    // ========================================================================

    @Test
    @DisplayName("ten thousand frames of render work leave the gameplay Rng untouched")
    void renderingNeverDrawsFromTheGameplayRng() {
        //  The property the source does NOT have: its draw methods pull from the
        //  global generator, so a 144 Hz display plays a different game from a
        //  60 Hz one.  Here the next gameplay draw must be identical whether
        //  nothing or everything was rendered first.
        TestRun t = new TestRun();
        t.begin(GameMode.ENDLESS, "normal");
        t.seconds(6);

        long[] before = rngState(t);

        DrawOrder order = new DrawOrder();
        Interpolator interp = new Interpolator();
        ProjectileTrails trails = new ProjectileTrails();
        EffectsSystem effects = new EffectsSystem(new VisualRng(t.rng));
        for (int frame = 0; frame < 10000; frame++) {
            order.sortedEnemies(t.run.horde());
            trails.sample(t.run.projectiles());
            effects.update(1f / 144f);
            for (int i = 0; i < t.run.horde().size(); i++) {
                Enemy e = t.run.horde().get(i);
                interp.draw(e, e.x(), e.y(), (frame % 60) / 60f);
            }
        }
        assertArrayEquals(before, rngState(t),
                "rendering moved the gameplay generator, so how many frames were "
                        + "drawn would change what the game does");
    }

    @Test
    @DisplayName("particles and shake draw from the decoration stream, never the game one")
    void decorationIsASeparateStream() {
        TestRun t = new TestRun();
        t.begin(GameMode.ENDLESS, "normal");
        long[] game = rngState(t);
        long[] deco = new long[] {t.rng.decoration().getState(0),
            t.rng.decoration().getState(1)};

        VisualRng rng = new VisualRng(t.rng);
        EffectsSystem effects = new EffectsSystem(rng);
        for (int i = 0; i < 200; i++) {
            effects.burst(100f, 100f, 12, VisualEvents.FIRE, 300f, 0.5f, 3f, 900f,
                    VisualEvents.Shape.RECT);
            rng.uniform(-4f, 4f);           // as the shake does
        }
        assertArrayEquals(game, rngState(t), "the gameplay stream must not move");
        assertFalse(java.util.Arrays.equals(deco,
                new long[] {t.rng.decoration().getState(0),
                    t.rng.decoration().getState(1)}),
                "and the decoration stream must -- or this test proves nothing");
    }

    @Test
    @DisplayName("stable decoration repeats exactly, and disturbs nothing")
    void stableDecorationIsReproducible() {
        //  The castle's cracks.  Python does this with random.seed(1337) and
        //  random.seed(), which perturbs the shared stream on the way past.
        TestRun t = new TestRun();
        long[] game = rngState(t);
        VisualRng rng = new VisualRng(t.rng);

        float[] first = new float[8];
        for (int i = 0; i < first.length; i++) {
            first[i] = rng.stable(VisualRng.CRACK_SEED).nextFloat();
        }
        //  Each call resets, so drawing the same decoration twice is identical.
        for (int i = 0; i < first.length; i++) {
            assertEquals(first[i], rng.stable(VisualRng.CRACK_SEED).nextFloat(), 0f,
                    "crack " + i + " moved between frames");
        }
        assertArrayEquals(game, rngState(t));
    }

    // ========================================================================
    //  The gameplay list is never reordered
    // ========================================================================

    @Test
    @DisplayName("the draw-order sort leaves the authoritative horde untouched")
    void renderDoesNotReorderTheHorde() {
        //  Several gameplay rules depend on insertion order.  Python's `sorted`
        //  returns a new list; a .sort() on the live roster here would be a
        //  rendering concern quietly rewriting the game.
        TestRun t = new TestRun();
        t.begin(GameMode.ENDLESS, "normal");
        t.seconds(20);
        assertTrue(t.run.horde().size() > 3, "need a crowd to shuffle");

        long[] before = new long[t.run.horde().size()];
        for (int i = 0; i < before.length; i++) {
            before[i] = t.run.horde().get(i).uid();
        }

        DrawOrder order = new DrawOrder();
        Array<Enemy> sorted = order.sortedEnemies(t.run.horde());
        assertTrue(sorted.size > 0);

        long[] after = new long[t.run.horde().size()];
        for (int i = 0; i < after.length; i++) {
            after[i] = t.run.horde().get(i).uid();
        }
        assertArrayEquals(before, after,
                "the renderer sorted the gameplay list in place");
    }

    @Test
    @DisplayName("the sort buffer is the renderer's own, not the world's")
    void sortBufferIsNotTheGameplayList() {
        TestRun t = new TestRun();
        t.begin(GameMode.ENDLESS, "normal");
        t.seconds(10);
        DrawOrder order = new DrawOrder();
        Array<Enemy> sorted = order.sortedEnemies(t.run.horde());
        assertNotEquals(System.identityHashCode(sorted),
                System.identityHashCode(t.run.horde().unsafeItems()),
                "the sorted view must not be the live roster");
    }

    @Test
    @DisplayName("flyers behind, then ground back-to-front by depth, then x")
    void theOrderIsTheSourceOrder() {
        TestRun t = new TestRun();
        t.begin(GameMode.ENDLESS, "normal");
        for (int i = 0; i < 6; i++) {
            t.run.spawnEnemy(EnemyType.GARGOYLE, 3);
            t.run.spawnEnemy(EnemyType.FOOT_SOLDIER, 3);
        }
        Array<Enemy> sorted = new DrawOrder().sortedEnemies(t.run.horde());

        boolean seenGround = false;
        for (int i = 0; i < sorted.size; i++) {
            Enemy e = sorted.get(i);
            if (!e.flying()) {
                seenGround = true;
            } else {
                assertFalse(seenGround,
                        "a flyer appeared after a ground unit: flyers draw behind");
            }
        }
        for (int i = 1; i < sorted.size; i++) {
            Enemy a = sorted.get(i - 1);
            Enemy b = sorted.get(i);
            if (a.flying() != b.flying()) {
                continue;
            }
            if (a.depth() != b.depth()) {
                assertTrue(a.depth() <= b.depth(), "depth is not ascending");
            } else {
                assertTrue(a.x() <= b.x(), "the x tie-break is not ascending");
            }
        }
    }

    // ========================================================================
    //  Gameplay state is unchanged
    // ========================================================================

    @Test
    @DisplayName("a render pass changes no position, no health and no timer")
    void renderMutatesNothing() {
        TestRun t = new TestRun();
        t.begin(GameMode.ENDLESS, "normal");
        t.seconds(18);
        int n = t.run.horde().size();
        assertTrue(n > 0);

        float[] xs = new float[n];
        float[] ys = new float[n];
        float[] hps = new float[n];
        for (int i = 0; i < n; i++) {
            Enemy e = t.run.horde().get(i);
            xs[i] = e.x();
            ys[i] = e.y();
            hps[i] = e.hp();
        }
        double time = t.session().playTime();
        int gold = t.session().gold();
        int alive = t.aliveEnemies();
        float castleHp = t.run.castle().hp();

        DrawOrder order = new DrawOrder();
        Interpolator interp = new Interpolator();
        EffectsSystem effects = new EffectsSystem(new VisualRng(t.rng));
        for (int frame = 0; frame < 500; frame++) {
            Array<Enemy> sorted = order.sortedEnemies(t.run.horde());
            for (int i = 0; i < sorted.size; i++) {
                Enemy e = sorted.get(i);
                interp.draw(e, e.x(), e.y(), 0.5f);
            }
            effects.update(1f / 60f);
        }

        for (int i = 0; i < n; i++) {
            Enemy e = t.run.horde().get(i);
            assertEquals(xs[i], e.x(), 0f, "enemy " + i + " moved during a render");
            assertEquals(ys[i], e.y(), 0f, "enemy " + i + " moved during a render");
            assertEquals(hps[i], e.hp(), 0f, "enemy " + i + " changed health");
        }
        assertEquals(time, t.session().playTime(), 0d, "the run clock advanced");
        assertEquals(gold, t.session().gold(), "gold changed");
        assertEquals(alive, t.aliveEnemies(), "the roster changed size");
        assertEquals(castleHp, t.run.castle().hp(), 0f, "the castle took damage");
    }

    @Test
    @DisplayName("the event sink cannot answer back, so gameplay cannot depend on it")
    void theSinkIsOneWay() {
        //  Every method returns void.  A sink that could report failure, or a
        //  count, or a handle would be something a simulation could branch on --
        //  and then whether anything was drawing would be a gameplay input.
        for (java.lang.reflect.Method m : VisualEvents.class.getDeclaredMethods()) {
            if (m.isSynthetic()) {
                continue;
            }
            assertEquals(void.class, m.getReturnType(),
                    "VisualEvents." + m.getName() + " returns a value; the sink "
                            + "must be one-way");
        }
    }

    @Test
    @DisplayName("with no sink attached the simulation is bit-identical")
    void noSinkChangesNothing() {
        //  The property that lets 847 headless tests run against production
        //  gameplay code that emits visual events.
        TestRun quiet = new TestRun(4242L);
        quiet.begin(GameMode.ENDLESS, "normal");
        quiet.seconds(30);

        TestRun loud = new TestRun(4242L);
        loud.begin(GameMode.ENDLESS, "normal");
        loud.run.setVisualEvents(new EffectsSystem(new VisualRng(9L)));
        loud.seconds(30);

        assertEquals(quiet.session().gold(), loud.session().gold());
        assertEquals(quiet.session().score(), loud.session().score());
        assertEquals(quiet.aliveEnemies(), loud.aliveEnemies());
        assertEquals(quiet.run.castle().hp(), loud.run.castle().hp(), 0f);
        assertArrayEquals(rngState(quiet), rngState(loud),
                "attaching a sink moved the gameplay generator");
    }

    @Test
    @DisplayName("the default sink is the silent one")
    void defaultSinkIsNone() {
        TestRun t = new TestRun();
        assertSame(VisualEvents.NONE, t.run.visuals());
        t.run.setVisualEvents(null);
        assertSame(VisualEvents.NONE, t.run.visuals(), "null must fall back, not NPE");
    }

    // ------------------------------------------------------------------------

    private static long[] rngState(TestRun t) {
        return new long[] {t.rng.game().getState(0), t.rng.game().getState(1)};
    }
}
