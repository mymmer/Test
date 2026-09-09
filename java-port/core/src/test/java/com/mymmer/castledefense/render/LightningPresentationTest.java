package com.mymmer.castledefense.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mymmer.castledefense.config.GameConfig;
import com.mymmer.castledefense.enemy.Enemy;
import com.mymmer.castledefense.enemy.EnemyType;
import com.mymmer.castledefense.game.GameMode;
import com.mymmer.castledefense.progress.TestRun;
import com.mymmer.castledefense.skill.SkillId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Lightning is shown, not merely applied.
 *
 * <h2>The defect</h2>
 *
 * <p>Both of the source's lightning paths — a mob flung above
 * {@code STORM_CEILING} during a storm, and the Lightning Strike skill — landed
 * their damage in this port and drew <b>nothing</b>. The bolt, the spray and the
 * readout were all missing; the only thing on screen was the white-out veil,
 * which has no visible cause without the bolt that earns it.
 *
 * <p>{@code Palette} had already defined {@code BOLT_CORE} and {@code BOLT_INNER}
 * in exactly the source's two colours. Phase 11 named the paint and never drew
 * the stroke.
 *
 * <h2>What is asserted</h2>
 *
 * <p>The <em>event-to-presentation path</em>: gameplay is driven for real, and a
 * recording sink stands where the renderer normally does. It checks that the
 * events arrive, that they carry gameplay's own numbers, and — the part that
 * matters for determinism — that emitting them consumes no gameplay randomness.
 */
class LightningPresentationTest {

    /** Every call, recorded. Stands exactly where {@code EffectsSystem} does. */
    private static final class Recorder implements VisualEvents {
        final List<float[]> bolts = new ArrayList<>();
        final List<String> texts = new ArrayList<>();
        int bursts;
        int rings;

        @Override
        public void burst(float x, float y, int count, int rgb, float speed,
                          float life, float size, float grav, Shape shape) {
            bursts++;
        }

        @Override
        public void ring(float x, float y, int count, int rgb, float speed,
                         float life, float size) {
            rings++;
        }

        @Override
        public void text(float x, float y, String message, int rgb, float size,
                         float life) {
            texts.add(message);
        }

        @Override
        public void bolt(float x, float y, float life) {
            bolts.add(new float[] {x, y, life});
        }
    }

    private static TestRun storming(Recorder r) {
        TestRun t = new TestRun(4242L);
        t.begin(GameMode.ENDLESS, "normal");
        t.run.setVisualEvents(r);
        for (int i = 0; i < 500 && !t.run.weather().storm(); i++) {
            t.run.weather().roll();
        }
        assertTrue(t.run.weather().storm(), "precondition: a storm blew up");
        return t;
    }

    @Test
    @DisplayName("a storm strike draws a bolt, a spray and its own damage figure")
    void stormStrikeIsVisible() {
        Recorder r = new Recorder();
        TestRun t = storming(r);

        Enemy mob = t.run.spawnEnemy(EnemyType.SCOUT, 1);
        mob.setX(700f);
        //  Above the ceiling is where the source strikes.
        mob.setY(GameConfig.STORM_CEILING - 10f);
        r.bolts.clear();
        r.texts.clear();
        r.bursts = 0;

        t.run.weather().strike(mob);

        assertEquals(1, r.bolts.size(), "a strike must draw exactly one bolt");
        assertEquals(700f, r.bolts.get(0)[0], 0.01f,
                "the bolt is anchored at the mob, not at a guess");
        assertEquals(GameConfig.STORM_CEILING - 10f, r.bolts.get(0)[1], 0.01f);
        assertEquals(0.28f, r.bolts.get(0)[2], 1e-4f, "main.py:1435 life");
        assertEquals(1, r.bursts, "the 26-particle spray");
        assertEquals(1, r.texts.size(), "the ZAP readout");
        assertTrue(r.texts.get(0).startsWith("ZAP "),
                "the readout is the source's: " + r.texts.get(0));
    }

    @Test
    @DisplayName("the readout carries the damage gameplay actually dealt")
    void theZapNumberIsGameplays() {
        Recorder r = new Recorder();
        TestRun t = storming(r);
        Enemy mob = t.run.spawnEnemy(EnemyType.SCOUT, 1);
        mob.setX(500f);
        mob.setY(GameConfig.STORM_CEILING - 10f);
        float hpBefore = mob.hp();
        r.texts.clear();

        t.run.weather().strike(mob);

        float dealt = hpBefore - mob.hp();
        assertTrue(dealt > 0f, "precondition: the strike hurt it");
        //  The renderer must not recompute a damage number. The one on screen
        //  is the one the simulation subtracted.
        assertEquals("ZAP " + (int) dealt, r.texts.get(0),
                "the figure on screen disagrees with the damage applied");
    }

    @Test
    @DisplayName("drawing a strike consumes no gameplay randomness")
    void presentationDoesNotTouchTheGameplayStream() {
        //  Two runs from one seed, driven identically except that one is struck
        //  by lightning. Their gameplay generators must still be in step
        //  afterwards -- if the presentation drew from it, a run watched by a
        //  renderer would diverge from the same run replayed headlessly.
        //
        //  Comparing two FRESH generators would pass no matter what the strike
        //  did, which is the shape of empty test this whole phase keeps finding.
        long[] struck = streamAfter(true);
        long[] untouched = streamAfter(false);

        org.junit.jupiter.api.Assertions.assertArrayEquals(untouched, struck,
                "the gameplay stream moved when lightning was drawn");
    }

    /** The next eight gameplay draws, with or without a strike beforehand. */
    private static long[] streamAfter(boolean strike) {
        Recorder r = new Recorder();
        TestRun t = storming(r);
        Enemy mob = t.run.spawnEnemy(EnemyType.SCOUT, 1);
        mob.setX(600f);
        mob.setY(GameConfig.STORM_CEILING - 10f);

        if (strike) {
            t.run.weather().strike(mob);
            assertEquals(1, r.bolts.size(), "the strike really did draw");
        }
        long[] out = new long[8];
        for (int i = 0; i < out.length; i++) {
            out[i] = t.run.rng().game().nextLong();
        }
        return out;
    }

    @Test
    @DisplayName("Lightning Strike draws five bolts, a ring and the kill count")
    void theSkillIsVisible() {
        Recorder r = new Recorder();
        TestRun t = new TestRun(99L);
        t.begin(GameMode.ENDLESS, "normal");
        t.run.setVisualEvents(r);
        for (int i = 0; i < 6; i++) {
            Enemy e = t.run.spawnEnemy(EnemyType.SCOUT, 1);
            e.setX(640f + i * 12f);
            e.setY(GameConfig.GROUND_Y);
        }
        while (t.run.skills().unlockedCount() == 0) {
            t.run.skills().unlockNext();
        }
        r.bolts.clear();
        r.texts.clear();

        assertTrue(t.run.skills().castAt(SkillId.LIGHTNING, 640f, GameConfig.GROUND_Y),
                "precondition: the skill cast");

        //  main.py:712-714 -- one down the middle, four across the radius.
        assertEquals(5, r.bolts.size(), "five bolts, as the source appends");
        assertEquals(640f, r.bolts.get(0)[0], 0.01f, "the first is on the point");
        assertEquals(0.45f, r.bolts.get(0)[2], 1e-4f, "and lasts longest");
        assertEquals(1, r.rings, "the ring");
        assertEquals(1, r.texts.size());
        assertTrue(r.texts.get(0).endsWith(" VAPORISED"),
                "the count readout: " + r.texts.get(0));
    }

    @Test
    @DisplayName("a strike refused by the cooldown draws nothing")
    void arefusedStrikeDrawsNothing() {
        Recorder r = new Recorder();
        TestRun t = storming(r);
        Enemy mob = t.run.spawnEnemy(EnemyType.SCOUT, 1);
        mob.setX(700f);
        mob.setY(GameConfig.STORM_CEILING - 10f);

        t.run.weather().strike(mob);
        int after = r.bolts.size();
        //  Immediately again: the mob is inside STORM_COOLDOWN.
        t.run.weather().strike(mob);

        assertEquals(after, r.bolts.size(),
                "a strike the simulation refused still drew a bolt, so the "
                        + "renderer is inventing events gameplay did not raise");
    }
}
