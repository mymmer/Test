package com.mymmer.castledefense.enemy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The speed system: the three effects that mutate the shared {@code speed} field
 * and the order they compose in.
 *
 * <p>This is the most delicate corner of the port. Python multiplies and divides
 * one mutable field from three places, and the result depends on the order. Each
 * test here names what it pins so a later "simplification" has to argue with it.
 */
class EnemySpeedTest {

    private static final double DT = TestEnemyWorld.DT;

    /** How far a mob moves left in one step, which is what speed actually means. */
    private static float stepDistance(TestEnemyWorld w, Enemy e) {
        float before = e.x();
        e.update(DT);
        return before - e.x();
    }

    // ========================================================================
    //  The Berzerker quirk
    // ========================================================================

    @Test
    @DisplayName("QUIRK: a Berzerker's movement ignores the difficulty and tier speed multipliers")
    void berzerkerRageDiscardsDifficultyAndTierSpeed() {
        //  ------------------------------------------------------------------
        //  This is a REPRODUCED SOURCE QUIRK, not a bug in the port.
        //
        //  Berzerker.think recomputes speed as
        //      BASE_SPEED * wave_scaling(wave)[2] * rage
        //  rather than scaling the value it already has.  It does that on EVERY
        //  call, not only while raging, so its stored speed -- which does carry
        //  enemy_speed_scale (difficulty) and the endgame tier's speed
        //  multiplier -- is never used to move it at all.
        //
        //  On Hard at wave 36 the discarded factors are 1.4 x 1.16 = 1.624,
        //  which is more than the 1.45 rage cap.  So a Berzerker there is slower
        //  than its own stat block says, at any health.
        //
        //  Do not "fix" it: that would be a balance change, and it would
        //  silently invalidate every parity comparison.  If it is ever fixed, it
        //  should be a deliberate decision with its own test.
        //  ------------------------------------------------------------------
        TestEnemyWorld w = new TestEnemyWorld();
        w.enemySpeedScale = 1.4f;           // Hard
        w.wave = 36;                        // Voidtouched: speedMult 1.16
        Berzerker b = (Berzerker) w.spawn(EnemyType.BERZERKER, 900f);

        float base = b.config().baseSpeed;
        float waveSpeed = WaveScaling.speed(36);

        //  the STORED speed is scaled normally, like every other unit
        assertEquals(base * waveSpeed * 1.16f * 1.4f, b.speed(), 0.01f,
                "the stat block carries difficulty and tier");

        //  ...but the movement does not use it
        float calmDistance = stepDistance(w, b);
        assertEquals(base * waveSpeed * 1f * DT, calmDistance, 0.01f,
                "at full health it moves at BASE x waveSpeed x rage(1.0), "
                        + "with difficulty and tier discarded");
        assertTrue(calmDistance < b.speed() * DT,
                "so it is SLOWER than its own stat block: " + calmDistance
                        + " vs " + (b.speed() * DT));

        //  hurt it to the floor: rage is at its maximum
        b.applyDamage(b.maxHp() * 0.999f, "fall");
        assertTrue(b.alive());
        float rage = b.rage();
        assertTrue(rage > 1.4f, "nearly dead, so rage is near its 1.45 cap: " + rage);

        float ragingDistance = stepDistance(w, b);
        assertEquals(base * waveSpeed * rage * DT, ragingDistance, 0.01f,
                "raging speed is BASE x waveSpeed x rage, and nothing else");

        //  The sharp end of the quirk: even at full rage it is still slower than
        //  its stat block, because 1.45 < 1.4 x 1.16.
        assertTrue(ragingDistance < b.speed() * DT,
                "even fully enraged it moves " + ragingDistance
                        + ", under its configured " + (b.speed() * DT));
    }

    @Test
    @DisplayName("without difficulty or tier the Berzerker's rage does speed it up")
    void berzerkerRageIsAnIncreaseOnNormal() {
        //  The other half of the picture: on Normal at an early wave nothing is
        //  discarded, so rage behaves exactly as it reads -- faster when hurt.
        //  This is what makes the quirk above invisible in ordinary play.
        TestEnemyWorld w = new TestEnemyWorld();
        Berzerker b = (Berzerker) w.spawn(EnemyType.BERZERKER, 900f);
        float calm = stepDistance(w, b);
        b.applyDamage(b.maxHp() * 0.999f, "fall");
        float raging = stepDistance(w, b);
        assertTrue(raging > calm * 1.4f,
                "on Normal, rage is a straight speed-up: " + raging + " vs " + calm);
    }

    @Test
    @DisplayName("the Berzerker restores its stored speed after thinking")
    void berzerkerRestoresSpeed() {
        TestEnemyWorld w = new TestEnemyWorld();
        w.enemySpeedScale = 1.4f;
        Enemy b = w.spawn(EnemyType.BERZERKER, 900f);
        float stored = b.speed();
        b.update(DT);
        assertEquals(stored, b.speed(), 0f,
                "the rage speed is temporary; the field goes back afterwards");
    }

    @Test
    @DisplayName("rage rises from 1.0 to 1.45 as health falls")
    void rageCurve() {
        TestEnemyWorld w = new TestEnemyWorld();
        Berzerker b = (Berzerker) w.spawn(EnemyType.BERZERKER, 900f);
        assertEquals(1f, b.rage(), 1e-5f, "untouched");
        b.applyDamage(b.maxHp() * 0.5f, "fall");
        assertEquals(1.225f, b.rage(), 1e-3f, "half health");
        b.applyDamage(b.maxHp() * 0.499f, "fall");
        assertTrue(b.rage() > 1.44f && b.rage() <= 1.45f, "nearly dead: " + b.rage());
    }

    // ========================================================================
    //  The Assassin dash
    // ========================================================================

    @Test
    @DisplayName("the Assassin's dash multiplies the speed left after any slow")
    void assassinDashComposesWithSlow() {
        //  Base, slow, dash and slow+dash, measured as distance travelled.
        float base = assassinStepDistance(1f, false);
        float slowed = assassinStepDistance(0.5f, false);
        float dashing = assassinStepDistance(1f, true);
        float both = assassinStepDistance(0.5f, true);

        assertEquals(base * 0.5f, slowed, 0.01f, "a slow halves it");
        assertEquals(base * Assassin.DASH_BOOST, dashing, 0.05f, "a dash is 3.6x");
        //  The ordering assertion: the two compose MULTIPLICATIVELY, because the
        //  dash multiplies the field and think() then applies the slow to that.
        assertEquals(base * 0.5f * Assassin.DASH_BOOST, both, 0.05f,
                "a slowed dash is slowed: 0.5 x 3.6, not 3.6 and not 3.1");
    }

    /** One step of an Assassin, optionally slowed and optionally mid-dash. */
    private static float assassinStepDistance(float slow, boolean dash) {
        TestEnemyWorld w = new TestEnemyWorld(4242L);
        w.slowFactor = slow;
        Assassin a = (Assassin) w.spawn(EnemyType.ASSASSIN, 900f);
        if (dash) {
            //  step until it happens to be dashing rather than forcing state,
            //  so the test exercises the real trigger
            for (int i = 0; i < 600 && a.dashing() <= 0f; i++) {
                a.update(TestEnemyWorld.DT);
            }
            assertTrue(a.dashing() > 0f, "the assassin should have dashed by now");
        }
        float before = a.x();
        a.update(TestEnemyWorld.DT);
        return before - a.x();
    }

    @Test
    @DisplayName("the Assassin restores its stored speed exactly after a dash step")
    void assassinRestoresSpeedExactly() {
        TestEnemyWorld w = new TestEnemyWorld(4242L);
        Assassin a = (Assassin) w.spawn(EnemyType.ASSASSIN, 900f);
        float stored = a.speed();
        for (int i = 0; i < 600; i++) {
            a.update(DT);
            //  The restore is an assignment of a saved value, not a divide, so
            //  it is exact on every step -- including the dashing ones.
            assertEquals(stored, a.speed(), 0f, "speed drifted at step " + i);
        }
    }

    // ========================================================================
    //  The talent slow
    // ========================================================================

    @Test
    @DisplayName("a slow scales movement, and the field is restored afterwards")
    void slowIsAppliedAndRestored() {
        TestEnemyWorld w = new TestEnemyWorld();
        Enemy e = w.spawn(EnemyType.FOOT_SOLDIER, 900f);
        float stored = e.speed();
        float full = stepDistance(w, e);

        w.slowFactor = 0.4f;
        float slowed = stepDistance(w, e);
        assertEquals(full * 0.4f, slowed, 0.01f);

        //  Python multiplies then divides the same field.  That round trip is
        //  not bit-exact, so the port does the same thing and asserts a
        //  tolerance rather than pretending it is.
        assertEquals(stored, e.speed(), stored * 1e-5f,
                "restored to within float round-trip error, as in Python");
    }

    @Test
    @DisplayName("armour stripping permanently slows a heavy unit, and it compounds")
    void strippingSlowsPermanently() {
        TestEnemyWorld w = new TestEnemyWorld();
        Enemy ram = w.spawn(EnemyType.SIEGE_RAM, 900f);
        float full = ram.speed();
        ram.applyStrip(com.mymmer.castledefense.config.GameConfig.STRIP_DISTANCE);
        assertEquals(full * com.mymmer.castledefense.config.GameConfig.STRIP_SLOW,
                ram.speed(), 0.001f);
        ram.applyStrip(com.mymmer.castledefense.config.GameConfig.STRIP_DISTANCE);
        assertEquals(full * com.mymmer.castledefense.config.GameConfig.STRIP_SLOW
                        * com.mymmer.castledefense.config.GameConfig.STRIP_SLOW,
                ram.speed(), 0.001f, "each plate compounds on the last");
    }

    // ========================================================================
    //  Difficulty and tier scaling
    // ========================================================================

    @Test
    @DisplayName("difficulty speed is a flat multiplier, not a curve stretch")
    void difficultySpeedIsFlat() {
        TestEnemyWorld normal = new TestEnemyWorld();
        TestEnemyWorld hard = new TestEnemyWorld();
        hard.enemyScale = 1.35f;
        hard.enemyHpCurve = 1.6f;
        hard.enemySpeedScale = 1.4f;

        for (int wave : new int[]{1, 10, 30}) {
            Enemy n = normal.build(EnemyType.SCOUT, wave);
            Enemy h = hard.build(EnemyType.SCOUT, wave);
            assertEquals(n.speed() * 1.4f, h.speed(), 0.01f,
                    "flat 1.4x at wave " + wave + ", whatever the wave curve does");
            //  and health is NOT flat: the curve is bent as well as scaled
            assertNotEquals(n.maxHp() * 1.35f, h.maxHp(), 0.01f,
                    "health is scaled AND curved, so it is not a flat 1.35x");
        }
    }

    @Test
    @DisplayName("the HP curve bends the growth, leaving wave 1 almost untouched")
    void hpCurveBendsGrowthNotBaseline() {
        TestEnemyWorld normal = new TestEnemyWorld();
        TestEnemyWorld curved = new TestEnemyWorld();
        curved.enemyHpCurve = 1.6f;

        //  At wave 1 the wave multiplier is exactly 1.0, so 1 + (1-1)*1.6 = 1:
        //  the curve has nothing to bend and the two are identical.
        assertEquals(normal.build(EnemyType.SCOUT, 1).maxHp(),
                curved.build(EnemyType.SCOUT, 1).maxHp(), 1e-4f,
                "wave 1 is untouched by the curve");

        //  By wave 20 it is a large difference.
        float plain = normal.build(EnemyType.SCOUT, 20).maxHp();
        float bent = curved.build(EnemyType.SCOUT, 20).maxHp();
        assertTrue(bent > plain * 1.5f,
                "the curve multiplies the growth EARNED, so it compounds: "
                        + bent + " vs " + plain);
    }

    @Test
    @DisplayName("endgame tiers land on the right waves and stack on the wave curve")
    void endgameTiers() {
        TestEnemyWorld w = new TestEnemyWorld();
        assertEquals(-1, w.build(EnemyType.SCOUT, 10).tier(), "no tier before 16");
        assertEquals(-1, w.build(EnemyType.SCOUT, 15).tier());
        assertEquals(0, w.build(EnemyType.SCOUT, 16).tier(), "Bloodied from 16");
        assertEquals(0, w.build(EnemyType.SCOUT, 25).tier());
        assertEquals(1, w.build(EnemyType.SCOUT, 26).tier(), "Frostbound from 26");
        assertEquals(2, w.build(EnemyType.SCOUT, 36).tier(), "Voidtouched from 36");
        assertEquals(2, w.build(EnemyType.SCOUT, 99).tier(), "and it stays there");

        assertEquals("", w.build(EnemyType.SCOUT, 10).tierName());
        assertEquals("Bloodied", w.build(EnemyType.SCOUT, 16).tierName());
        assertEquals("Voidtouched", w.build(EnemyType.SCOUT, 40).tierName());

        //  the tier multiplies whatever the wave curve produced
        float atFifteen = w.build(EnemyType.SCOUT, 15).maxHp();
        float atSixteen = w.build(EnemyType.SCOUT, 16).maxHp();
        assertTrue(atSixteen > atFifteen * 1.35f,
                "crossing into a tier is a step change, not a smooth curve");
    }
}
