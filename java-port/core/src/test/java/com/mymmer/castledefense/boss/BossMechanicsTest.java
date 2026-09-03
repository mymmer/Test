package com.mymmer.castledefense.boss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mymmer.castledefense.config.GameConfig;
import com.mymmer.castledefense.enemy.Enemy;
import com.mymmer.castledefense.enemy.EnemyState;
import com.mymmer.castledefense.enemy.EnemyType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The three bosses and their disruptions, ported from the Python self-test's
 * boss block.
 */
class BossMechanicsTest {

    private static final float DT = TestBossWorld.DT;

    // ========================================================================
    //  Generic boss invariants
    // ========================================================================

    @Test
    @DisplayName("no boss can be grabbed or stripped")
    void bossesAreImmuneToOrdinaryInteraction() {
        for (BossType type : BossType.values()) {
            TestBossWorld w = new TestBossWorld();
            w.world.grabCapacity = 9999f;           // absurd Grab Strength
            Boss b = w.summon(type);

            assertTrue(b.isBoss(), type.id());
            assertFalse(b.grabbable(), type.id() + " must never be grabbable");
            assertFalse(b.strippable(), type.id() + " must never be strippable");
            assertFalse(b.shovable(), type.id() + " must never be shovable");
            assertFalse(b.tooHeavy(), type.id() + " is not 'too heavy', it is immune");
            assertFalse(b.config().grabbable, "and the config says so too");
        }
    }

    @Test
    @DisplayName("pressing a boss's body is rejected; pressing its disruption is not")
    void bodyPressIsRejectedButDisruptionIsNot() {
        TestBossWorld w = new TestBossWorld();
        Boss troll = w.summon(BossType.TROLL_KING, 900f);

        //  the body: dead centre of a very large unit
        assertFalse(w.press(troll.x(), troll.y()),
                "grabbing a boss by the body must be refused");
        assertNull(w.cursor.grabbed());
        assertNull(w.cursor.heldItem());

        //  the crown: the same click, a few pixels higher
        float[] anchor = w.anchorOf(troll);
        assertTrue(w.press(anchor[0], anchor[1]),
                "but its crown is interactive");
        assertNotNull(w.cursor.heldItem());
    }

    @Test
    @DisplayName("a boss takes damage, dies and pays out through the ordinary contracts")
    void bossDeathUsesTheEnemyPayout() {
        TestBossWorld w = new TestBossWorld();
        Boss b = w.summon(BossType.TROLL_KING);
        //  a crowd, so the multiplier is not 1.0 and the ordering is visible
        for (int i = 0; i < 12; i++) {
            w.world.spawn(EnemyType.SCOUT, 900f + i * 40f);
        }
        float mult = w.goldMultiplier();
        int expected = Math.max(1, Math.round(b.gold() * mult));

        b.applyDamage(b.maxHp() * 2f, "projectile");

        assertFalse(b.alive());
        assertEquals(expected, w.world.gold, "the ordinary crowd-multiplied payout");
        assertEquals(1, w.world.kills, "and the ordinary kill count");
        assertEquals(1, w.bossDefeatedCount, "plus the boss hook, exactly once");
        assertSame(b, w.bossDefeated());
    }

    @Test
    @DisplayName("the boss defeat hook fires once, however many times die() is called")
    void defeatHookFiresOnce() {
        TestBossWorld w = new TestBossWorld();
        Boss b = w.summon(BossType.DRAGON);
        b.die();
        b.die();
        b.die();
        assertEquals(1, w.bossDefeatedCount);
    }

    @Test
    @DisplayName("armour applies to a boss like any other unit")
    void bossArmour() {
        TestBossWorld w = new TestBossWorld();
        Boss troll = w.summon(BossType.TROLL_KING);
        assertEquals(0.25f, troll.armor(), 1e-6f);
        float dealt = troll.applyDamage(100f, "projectile");
        assertEquals(75f, dealt, 0.01f, "25% armour");
        //  and fall damage still bypasses it
        assertEquals(100f, troll.applyDamage(100f, "fall"), 0.01f);
    }

    @Test
    @DisplayName("the difficulty's boss fire scale is captured once, at construction")
    void fireScaleIsCapturedAtConstruction() {
        TestBossWorld w = new TestBossWorld();
        w.bossFireScale = 0.5f;                     // Hard: twice the rate of fire
        Boss b = w.summon(BossType.DRAGON);
        assertEquals(0.5f, b.fireScale(), 0f);
        assertEquals(1f, b.fireDelay(2f), 1e-6f, "a 2 s reload becomes 1 s");

        //  changing it now must not retune a boss already on the field
        w.bossFireScale = 1f;
        assertEquals(0.5f, b.fireScale(), 0f);
    }

    // ========================================================================
    //  The shared disruption guard
    // ========================================================================

    @Test
    @DisplayName("the disruption guard grows by REGALIA_CD_GROWTH each time")
    void guardGrowth() {
        TestBossWorld w = new TestBossWorld();
        Boss b = w.summon(BossType.TROLL_KING);
        assertEquals(0, b.regaliaTaken());
        assertEquals(0f, b.regaliaCd(), 0f, "a fresh boss has no guard");

        //  The off-by-one that matters: the counter increments when the item is
        //  DETACHED and the guard is applied when it is RECOVERED, so by the time
        //  a guard exists the count is already 1.  A boss's FIRST guard is
        //  9.6 s, never 6.0 -- there is no state in which it is 6.0.
        float[] expected = new float[5];
        for (int i = 0; i < expected.length; i++) {
            expected[i] = GameConfig.REGALIA_COOLDOWN
                    * (1f + GameConfig.REGALIA_CD_GROWTH * (i + 1));
        }
        assertEquals(9.6f, expected[0], 1e-3f, "first: 9.6 s");
        assertEquals(13.2f, expected[1], 1e-3f, "second: 13.2 s");
        assertEquals(16.8f, expected[2], 1e-3f, "third: 16.8 s");

        //  drive the sequence: detach, recover, detach, recover...
        for (int n = 0; n < 4; n++) {
            TrollKing troll = (TrollKing) b;
            assertTrue(troll.hasCrown(), "round " + n);
            assertNotNull(troll.detachRegalia());
            assertEquals(n + 1, b.regaliaTaken());
            //  the crown vanishes, so retrieval puts it straight back on and
            //  applies the guard for THIS count
            troll.crownItem().markDead();
            troll.retrieveCrown(DT);
            assertTrue(troll.hasCrown());
            assertEquals(expected[n], b.regaliaCd(), 1e-3f,
                    "guard after disruption " + (n + 1));
            //  Wind it down for the next round.  The bound is captured FIRST:
            //  regaliaCd() shrinks every step, so re-reading it in the loop
            //  condition would halve the number of steps and leave a guard up.
            int windDown = (int) (b.regaliaCd() / DT) + 2;
            for (int i = 0; i < windDown; i++) {
                b.update(DT);
            }
            assertEquals(0f, b.regaliaCd(), 1e-4f, "guard fully wound down");
        }
    }

    @Test
    @DisplayName("the regalia cannot be taken while the guard is up")
    void guardBlocksInteraction() {
        TestBossWorld w = new TestBossWorld();
        TrollKing troll = (TrollKing) w.summon(BossType.TROLL_KING);
        float[] anchor = w.anchorOf(troll);
        assertTrue(troll.regaliaCovers(anchor[0], anchor[1]));

        troll.detachRegalia();
        troll.crownItem().markDead();
        troll.retrieveCrown(DT);
        assertTrue(troll.hasCrown());
        assertTrue(troll.regaliaCd() > 0f);

        assertFalse(troll.regaliaCovers(anchor[0], anchor[1]),
                "guarded: the crown is not interactive");
        assertFalse(w.press(anchor[0], anchor[1]), "so the click does nothing");
    }

    @Test
    @DisplayName("QUIRK: battering the Dragon's claws grows the REGALIA guard")
    void clawSmacksShareTheRegaliaGuard() {
        //  ------------------------------------------------------------------
        //  A REPRODUCED SOURCE QUIRK.  Dragon.apply_smack increments
        //  regalia_taken and calls guard_regalia() -- the crown/staff mechanism
        //  -- even though claws are not regalia and nothing is taken away.
        //
        //  The observable effect is that repeated claw batterings get
        //  progressively harder in exactly the same 6 / 9.6 / 13.2 s ladder as
        //  stealing a crown.  Do not "correct" it because the naming is odd:
        //  the guard growth is the mechanic's balance.
        //  ------------------------------------------------------------------
        TestBossWorld w = new TestBossWorld();
        Dragon dragon = (Dragon) w.summon(BossType.DRAGON);
        assertEquals(0, dragon.regaliaTaken());

        assertTrue(dragon.applySmack(GameConfig.CLAW_SMACK_DISTANCE),
                "a full CLAW_SMACK_DISTANCE of drag completes a battering");
        assertEquals(1, dragon.regaliaTaken(),
                "which counts as a REGALIA taking, quirk and all");
        assertEquals(GameConfig.REGALIA_COOLDOWN * (1f + GameConfig.REGALIA_CD_GROWTH),
                dragon.regaliaCd(), 1e-3f,
                "and applies the regalia guard -- 9.6 s, the same first value a "
                        + "stolen crown produces");

        //  Wind down the guard and the reel, then do it again.  Bound captured
        //  first, for the same reason as above.
        int windDown = (int) (dragon.regaliaCd() / DT) + 4;
        for (int i = 0; i < windDown; i++) {
            dragon.update(DT);
        }
        assertEquals(0f, dragon.regaliaCd(), 1e-4f);
        //  reel is decremented without a clamp, as in Python, so it lands
        //  slightly under zero on the step it expires.  Every check is
        //  "reel > 0", so that is harmless -- and clamping it would be an
        //  unrequested change to the source's arithmetic.
        assertTrue(dragon.reel() <= 0f, "reeling is over: " + dragon.reel());
        assertTrue(dragon.applySmack(GameConfig.CLAW_SMACK_DISTANCE));
        assertEquals(2, dragon.regaliaTaken());
        assertEquals(GameConfig.REGALIA_COOLDOWN * (1f + GameConfig.REGALIA_CD_GROWTH * 2f),
                dragon.regaliaCd(), 1e-3f, "the second battering is guarded longer");
    }

    // ========================================================================
    //  Troll King
    // ========================================================================

    @Test
    @DisplayName("dragging the crown off removes it and creates a dropped item")
    void crownDetach() {
        TestBossWorld w = new TestBossWorld();
        TrollKing troll = (TrollKing) w.summon(BossType.TROLL_KING);
        assertTrue(troll.hasCrown());

        float[] anchor = w.anchorOf(troll);
        assertTrue(w.press(anchor[0], anchor[1]));

        DroppedItem crown = w.cursor.heldItem();
        assertNotNull(crown, "the crown is on the cursor");
        assertEquals(RegaliaKind.CROWN, crown.kind());
        assertFalse(troll.hasCrown(), "grabbing the crown must remove it");
        assertSame(crown, troll.crownItem());
        assertEquals(troll.uid(), crown.ownerUid());
        assertEquals(DroppedItem.State.HELD, crown.state());
        assertEquals(1, w.liveItems());
    }

    @Test
    @DisplayName("an uncrowned Troll King goes to fetch it and attacks nothing")
    void crownRetrieval() {
        TestBossWorld w = new TestBossWorld();
        TrollKing troll = (TrollKing) w.summon(BossType.TROLL_KING,
                GameConfig.CASTLE_FRONT + 60f);

        float[] anchor = w.anchorOf(troll);
        w.press(anchor[0], anchor[1]);
        w.setVelocity(1800f, -600f);        // hurl it away down the field
        w.release();

        DroppedItem crown = troll.crownItem();
        assertNotNull(crown);
        assertEquals(DroppedItem.State.FLYING, crown.state());

        float castleHp = w.world.castle.hp();
        boolean sawRetrieve = false;
        for (int i = 0; i < 60 * 40 && !troll.hasCrown(); i++) {
            w.step(DT);
            if (troll.state() == EnemyState.RETRIEVE) {
                sawRetrieve = true;
            }
        }
        assertTrue(sawRetrieve, "an uncrowned Troll King must enter RETRIEVE");
        assertEquals(castleHp, w.world.castle.hp(), 0.001f,
                "he must not attack while uncrowned");
        assertTrue(troll.hasCrown(), "he must eventually pick his crown back up");
        assertTrue(troll.regaliaCd() > 0f, "and guard it afterwards");
        assertEquals(0, w.liveItems(), "the crown is consumed");
        assertNull(troll.crownItem());
    }

    @Test
    @DisplayName("a crown still in the air cannot be picked up, even from beside it")
    void crownMustBeAtRest() {
        TestBossWorld w = new TestBossWorld();
        TrollKing troll = (TrollKing) w.summon(BossType.TROLL_KING, 900f);
        troll.detachRegalia();
        DroppedItem crown = troll.crownItem();

        //  park it right on top of him, but flying
        crown.throwIt(0f, -400f);
        crown.moveTo(troll.x(), troll.y());
        assertEquals(DroppedItem.State.FLYING, crown.state());

        troll.retrieveCrown(DT);
        assertFalse(troll.hasCrown(), "a crown in flight stays out of reach");
    }

    @Test
    @DisplayName("a crown that vanishes puts itself back on his head")
    void crownVanishing() {
        //  The branch that stops a destroyed crown leaving him walking for ever.
        TestBossWorld w = new TestBossWorld();
        TrollKing troll = (TrollKing) w.summon(BossType.TROLL_KING);
        troll.detachRegalia();
        troll.crownItem().markDead();

        troll.retrieveCrown(DT);
        assertTrue(troll.hasCrown());
        assertNull(troll.crownItem());
        assertTrue(troll.regaliaCd() > 0f, "and it is guarded");
    }

    @Test
    @DisplayName("the Troll King smashes the wall and stuns a tower")
    void towerSmash() {
        TestBossWorld w = new TestBossWorld();
        var tower = w.world.castle.addTower(
                com.mymmer.castledefense.defence.TowerType.BOWMAN);
        assertNotNull(tower);
        float towerHp = tower.hp();
        float castleHp = w.world.castle.hp();

        TrollKing troll = (TrollKing) w.summon(BossType.TROLL_KING,
                GameConfig.CASTLE_FRONT + 50f);
        troll.forceState(EnemyState.ATTACK);

        for (int i = 0; i < 60 * 10 && w.world.castle.hp() == castleHp; i++) {
            w.step(DT);
        }
        assertTrue(w.world.castle.hp() < castleHp, "the wall took it");
        assertTrue(tower.hp() < towerHp || tower.disabled(),
                "and so did a tower -- via Castle.smashRandomTower, not a new algorithm");
        assertTrue(tower.stun() > 0f || tower.disabled(), "stunned for a second");
    }

    @Test
    @DisplayName("the Troll King leaps, but not once he is close to the wall")
    void leap() {
        TestBossWorld w = new TestBossWorld();
        TrollKing far = (TrollKing) w.summon(BossType.TROLL_KING, 1100f);
        float startX = far.x();
        boolean leapt = false;
        for (int i = 0; i < 60 * 12 && !leapt; i++) {
            float before = far.x();
            w.step(DT);
            if (before - far.x() > 100f) {
                leapt = true;
            }
        }
        assertTrue(leapt, "he should have leapt by now");
        assertTrue(far.x() < startX);

        //  inside leapMinRange of the wall he stops leaping
        TestBossWorld w2 = new TestBossWorld();
        TrollKing near = (TrollKing) w2.summon(BossType.TROLL_KING,
                GameConfig.CASTLE_FRONT + 100f);
        for (int i = 0; i < 60 * 12; i++) {
            float before = near.x();
            w2.step(DT);
            assertFalse(before - near.x() > 100f,
                    "no leaping this close to the wall");
        }
    }

    // ========================================================================
    //  Dragon
    // ========================================================================

    @Test
    @DisplayName("the Dragon breathes a stream of fireballs on a simulation timer")
    void breathStream() {
        TestBossWorld w = new TestBossWorld();
        Dragon dragon = (Dragon) w.summon(BossType.DRAGON,
                w.bosses.config(BossType.DRAGON).standoffX);

        //  run until it starts breathing
        for (int i = 0; i < 60 * 20 && !dragon.breathing(); i++) {
            w.step(DT);
        }
        assertTrue(dragon.breathing(), "it should have breathed by now");

        //  Counted from the trace, not from the projectile list: fire projectiles
        //  detonate on the ground and are swept, so a net list size would
        //  measure the sweep rather than the stream.
        com.mymmer.castledefense.debug.RecordingSimulationTrace trace =
                new com.mymmer.castledefense.debug.RecordingSimulationTrace();
        trace.setEnabled(true);
        w.world.trace = trace;

        int steps = 0;
        while (dragon.breathing() && steps < 60 * 5) {
            w.step(DT);
            steps++;
        }
        int fired = trace.countOf(com.mymmer.castledefense.debug.TraceEvent.BOSS_ATTACK);
        assertTrue(fired >= 5, "a sustained stream, not one shot: " + fired);

        //  the stream lasts about breathTime, on the fixed step
        float expectedSteps = w.bosses.config(BossType.DRAGON).breathTime / DT;
        assertEquals(expectedSteps, steps, 2f, "measured in simulation steps");
    }

    @Test
    @DisplayName("a breath fireball is a fraction of a full hit, and splashes")
    void breathProjectile() {
        TestBossWorld w = new TestBossWorld();
        Dragon dragon = (Dragon) w.summon(BossType.DRAGON,
                w.bosses.config(BossType.DRAGON).standoffX);
        dragon.spitFire(w.bosses.config(BossType.DRAGON).breathPower);

        var p = w.world.projectiles.get(w.world.projectiles.size() - 1);
        assertTrue(p.hostile());
        assertEquals(dragon.uid(), p.ownerUid(), "owned, for cleanup and diagnostics");
        assertEquals(dragon.damage() * 0.34f, p.damage(), 0.01f);
        assertTrue(p.splash() > 0f, "it is an AoE");
        assertTrue(p.stun() > 0f, "and it stuns what it lands on");
    }

    @Test
    @DisplayName("battering the claws cuts the breath off mid-stream and drives it back")
    void clawDisruption() {
        TestBossWorld w = new TestBossWorld();
        Dragon dragon = (Dragon) w.summon(BossType.DRAGON,
                w.bosses.config(BossType.DRAGON).standoffX);

        for (int i = 0; i < 60 * 20 && !dragon.breathing(); i++) {
            w.step(DT);
        }
        assertTrue(dragon.breathing());
        float x = dragon.x();

        assertTrue(dragon.applySmack(GameConfig.CLAW_SMACK_DISTANCE));
        assertFalse(dragon.breathing(), "breath cut off mid-stream");
        assertEquals(GameConfig.CLAW_STAGGER, dragon.reel(), 1e-4f);
        assertEquals(x + 120f, dragon.x(), 0.01f, "driven back off the wall");
        assertTrue(dragon.breathTimer() >= 2f, "and the next breath pushed out");
    }

    @Test
    @DisplayName("claw progress accumulates and does not complete early")
    void clawProgressAccumulates() {
        TestBossWorld w = new TestBossWorld();
        Dragon dragon = (Dragon) w.summon(BossType.DRAGON);
        float third = GameConfig.CLAW_SMACK_DISTANCE / 3f;

        assertFalse(dragon.applySmack(third), "one third is not enough");
        assertFalse(dragon.applySmack(third), "nor two");
        assertEquals(0, dragon.regaliaTaken());
        assertTrue(dragon.applySmack(third + 1f), "the third completes it");
        assertEquals(1, dragon.regaliaTaken());
        assertEquals(0f, dragon.clawProgress(), 1e-5f, "and progress resets");
    }

    @Test
    @DisplayName("a reeling Dragon cannot be battered again")
    void reelingDragonIsNotSmackable() {
        TestBossWorld w = new TestBossWorld();
        Dragon dragon = (Dragon) w.summon(BossType.DRAGON);
        dragon.applySmack(GameConfig.CLAW_SMACK_DISTANCE);
        assertTrue(dragon.reel() > 0f);
        assertFalse(dragon.applySmack(GameConfig.CLAW_SMACK_DISTANCE),
                "it is already reeling");
        float[] anchor = w.anchorOf(dragon);
        assertFalse(dragon.regaliaCovers(anchor[0], anchor[1]));
    }

    @Test
    @DisplayName("the Dragon is a smack target, the others are grab targets")
    void disruptionKinds() {
        TestBossWorld w = new TestBossWorld();
        assertTrue(w.summon(BossType.DRAGON, 900f).isSmackTarget());
        assertFalse(w.summon(BossType.TROLL_KING, 950f).isSmackTarget());
        assertFalse(w.summon(BossType.LICH_LORD, 1000f).isSmackTarget());

        //  and the Dragon has nothing to detach
        TestBossWorld w2 = new TestBossWorld();
        assertNull(w2.summon(BossType.DRAGON).detachRegalia());
    }

    // ========================================================================
    //  Lich Lord
    // ========================================================================

    @Test
    @DisplayName("flicking the staff away disarms him and drops the ward")
    void staffDisarm() {
        TestBossWorld w = new TestBossWorld();
        LichLord lich = (LichLord) w.summon(BossType.LICH_LORD,
                w.bosses.config(BossType.LICH_LORD).standoffX);
        assertTrue(lich.hasStaff());

        //  give him a ward first, so the drop is observable
        for (int i = 0; i < 60 * 30 && !lich.wardActive(); i++) {
            w.step(DT);
        }
        assertTrue(lich.wardActive(), "he should have warded by now");

        float[] anchor = w.anchorOf(lich);
        assertTrue(w.press(anchor[0], anchor[1]));
        assertNotNull(w.cursor.heldItem());
        assertEquals(RegaliaKind.STAFF, w.cursor.heldItem().kind());
        assertFalse(lich.hasStaff());
        assertEquals(GameConfig.STAFF_DISARM_TIME, lich.disarm(), 0.01f);
        assertEquals(0f, lich.shield(), 0f, "the ward drops with the staff");
    }

    @Test
    @DisplayName("a disarmed Lich does nothing at all for five seconds")
    void disarmedLichIsInert() {
        TestBossWorld w = new TestBossWorld();
        LichLord lich = (LichLord) w.summon(BossType.LICH_LORD,
                w.bosses.config(BossType.LICH_LORD).standoffX);
        w.world.barricade.buy();

        lich.detachRegalia();
        float castleHp = w.world.castle.hp();
        float barHp = w.world.barricade.hp();
        int mobs = w.world.targetCount();
        int projectiles = w.world.projectiles.size();

        for (int i = 0; i < (int) (GameConfig.STAFF_DISARM_TIME * 60f) - 4; i++) {
            w.step(DT);
        }
        assertFalse(lich.hasStaff(), "still disarmed");
        assertEquals(castleHp, w.world.castle.hp(), 0.001f, "no attack");
        assertEquals(barHp, w.world.barricade.hp(), 0.001f);
        assertEquals(mobs, w.world.targetCount(), "no summons");
        assertEquals(projectiles, w.world.projectiles.size(), "no bolts");
        assertEquals(0f, lich.shield(), 0f, "and no ward");
    }

    @Test
    @DisplayName("he recalls the staff after the disarm, wherever it lies")
    void staffRecovery() {
        TestBossWorld w = new TestBossWorld();
        LichLord lich = (LichLord) w.summon(BossType.LICH_LORD,
                w.bosses.config(BossType.LICH_LORD).standoffX);

        float[] anchor = w.anchorOf(lich);
        w.press(anchor[0], anchor[1]);
        w.setVelocity(700f, -300f);
        w.release();
        DroppedItem staff = lich.staffItem();
        assertNotNull(staff);

        for (int i = 0; i < (int) (GameConfig.STAFF_DISARM_TIME * 60f) + 6
                && !lich.hasStaff(); i++) {
            w.step(DT);
        }
        assertTrue(lich.hasStaff(), "he must recover his staff after the stun");
        assertFalse(staff.isAlive(), "and the dropped one is consumed wherever it was");
        assertNull(lich.staffItem());
        assertTrue(lich.regaliaCd() > 0f, "guarded afterwards");
        assertEquals(0, w.liveItems());
    }

    @Test
    @DisplayName("the bone ward multiplies incoming damage BEFORE armour")
    void wardOrdering() {
        //  ------------------------------------------------------------------
        //  Python overrides take_damage to scale the incoming amount and THEN
        //  calls super(), which applies (1 - armor) * vulnerable.  That order is
        //  the contract and is preserved here.
        //
        //  HONEST NOTE: with the current formulas the two orders are numerically
        //  IDENTICAL -- both steps are pure multiplies, and x0.25 is an exact
        //  power of two, so they commute bit for bit.  The parity fixture
        //  generates both and this test asserts they agree, rather than pretending
        //  to detect a difference that does not exist.  The order is still kept,
        //  because it stops being equivalent the moment armour gains a floor, a
        //  cap or a flat subtraction.  See PORT_ANALYSIS.md section 14.
        //  ------------------------------------------------------------------
        TestBossWorld w = new TestBossWorld();
        LichLord lich = (LichLord) w.summon(BossType.LICH_LORD);
        float armor = lich.armor();
        assertEquals(0.30f, armor, 1e-6f);

        float unwarded = lich.applyDamage(400f, "projectile");
        assertEquals(400f * (1f - armor), unwarded, 0.01f);

        //  ward it, then hit it again
        for (int i = 0; i < 60 * 30 && !lich.wardActive(); i++) {
            w.step(DT);
        }
        assertTrue(lich.wardActive());
        float warded = lich.applyDamage(400f, "projectile");
        assertEquals(400f * 0.25f * (1f - armor), warded, 0.01f);
        assertEquals(unwarded * 0.25f, warded, 0.01f, "a quarter of the unwarded hit");
    }

    @Test
    @DisplayName("he raises real hostile units through the enemy factory")
    void raiseDead() {
        TestBossWorld w = new TestBossWorld();
        w.world.wave = 16;
        LichLord lich = (LichLord) w.summon(BossType.LICH_LORD,
                w.bosses.config(BossType.LICH_LORD).standoffX);

        int before = w.world.targetCount();
        lich.raiseDead();
        int raised = w.world.targetCount() - before;
        assertEquals(2 + Math.min(4, 16 / 8), raised, "2 + min(4, wave/8)");

        for (int i = before; i < w.world.targetCount(); i++) {
            Enemy e = (Enemy) w.world.target(i);
            assertTrue(e.alive());
            assertFalse(e.isBoss());
            assertNull(e.type() == EnemyType.SIEGE_RAM ? "" : null,
                    "no Siege Rams in the summon pool");
            assertTrue(e.type() != EnemyType.NECROMANCER,
                    "and no Necromancers");
            assertEquals(w.world.wave - 3,
                    ((Enemy) w.world.target(i)).wave, "three waves weaker");
        }
    }

    @Test
    @DisplayName("he halts in front of a live barricade, and closes once it falls")
    void barricadeStandoff() {
        TestBossWorld w = new TestBossWorld();
        w.world.barricade.buy();
        LichLord lich = (LichLord) w.summon(BossType.LICH_LORD, 1100f);

        float withBarricade = lich.currentStandoff();
        float expected = Math.max(w.bosses.config(BossType.LICH_LORD).standoffX,
                w.world.barricade.x()
                        + com.mymmer.castledefense.defence.Barricade.WIDTH / 2f
                        + lich.width() / 2f + 18f);
        assertEquals(expected, withBarricade, 0.01f);
        assertTrue(withBarricade > w.bosses.config(BossType.LICH_LORD).standoffX,
                "he stops further out while a wall stands");

        //  he walks to it and stops
        for (int i = 0; i < 60 * 40; i++) {
            w.step(DT);
        }
        assertTrue(lich.x() >= withBarricade - 2f,
                "he does not advance past a live wall: " + lich.x());
        assertTrue(w.world.barricade.alive(),
                "and he never attacks it -- he bolts it from a distance");

        //  bring the barricade down and he closes
        w.world.barricade.takeDamage(w.world.barricade.maxHp() * 2f);
        assertFalse(w.world.barricade.alive());
        assertEquals(w.bosses.config(BossType.LICH_LORD).standoffX,
                lich.currentStandoff(), 0.01f);
    }

    @Test
    @DisplayName("his death bolt targets the barricade while it stands")
    void deathBoltTargeting() {
        TestBossWorld w = new TestBossWorld();
        w.world.barricade.buy();
        LichLord lich = (LichLord) w.summon(BossType.LICH_LORD,
                w.bosses.config(BossType.LICH_LORD).standoffX);
        lich.deathBolt();

        var p = w.world.projectiles.get(w.world.projectiles.size() - 1);
        assertTrue(p.hostile());
        assertEquals(lich.uid(), p.ownerUid());
        assertTrue(p.splash() > 0f);
        assertTrue(p.vx() < 0f, "aimed left, at the wall");
    }
}
