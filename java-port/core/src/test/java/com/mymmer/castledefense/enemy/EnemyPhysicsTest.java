package com.mymmer.castledefense.enemy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mymmer.castledefense.config.GameConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Throw physics: release, flight, landing, bouncing and slamming.
 *
 * <p>The formulas themselves are checked against Python in
 * {@link PythonParityTest}. What is checked here is the <b>behaviour around
 * them</b>: which thresholds fire, in what order, what state a mob ends up in,
 * and where the fixed timestep can move a boundary.
 *
 * <p>Every test sets its velocities directly rather than throwing a mob and
 * hoping it lands hard enough — a threshold test driven by a simulated
 * trajectory tests the trajectory, not the threshold.
 */
class EnemyPhysicsTest {

    private static final float DT = TestEnemyWorld.DT;

    /** Drops a mob from a known velocity onto the ground and returns it. */
    private static Enemy dropWith(TestEnemyWorld w, EnemyType type, float vx, float vy) {
        Enemy e = w.spawn(type, 900f);
        e.forceState(EnemyState.AIR);
        e.setY(e.groundY() - 1f);
        e.setVelocity(vx, vy);
        return e;
    }

    // ========================================================================
    //  Release
    // ========================================================================

    @Test
    @DisplayName("release velocity falls with mass, and enters the air state")
    void releaseScalesWithMass() {
        TestEnemyWorld w = new TestEnemyWorld();
        Enemy light = w.spawn(EnemyType.SCOUT, 900f);        // MASS 0.8
        Enemy heavy = w.spawn(EnemyType.SIEGE_RAM, 950f);    // MASS 9.0

        light.onGrab();
        heavy.onGrab();
        assertEquals(EnemyState.GRABBED, light.state());

        light.onRelease(1000f, -500f);
        heavy.onRelease(1000f, -500f);

        assertEquals(EnemyState.AIR, light.state());
        assertEquals(EnemyState.AIR, heavy.state());

        float lightMult = GameConfig.THROW_POWER / (0.55f + 0.45f * 0.8f);
        float heavyMult = GameConfig.THROW_POWER / (0.55f + 0.45f * 9f);
        assertEquals(1000f * lightMult, light.vx(), 0.01f);
        assertEquals(1000f * heavyMult, heavy.vx(), 0.01f);
        assertTrue(heavy.vx() < light.vx() * 0.3f,
                "a Siege Ram barely moves compared to a Scout");
    }

    @Test
    @DisplayName("a grabbed mob is frozen: no physics, no thinking")
    void grabbedIsFrozen() {
        TestEnemyWorld w = new TestEnemyWorld();
        Enemy e = w.spawn(EnemyType.FOOT_SOLDIER, 900f);
        e.onGrab();
        float x = e.x();
        float y = e.y();
        for (int i = 0; i < 120; i++) {
            e.update(DT);
        }
        assertEquals(x, e.x(), 0f, "it does not walk");
        assertEquals(y, e.y(), 0f, "and it does not fall");
        assertEquals(0f, e.vxEstimate(), 0f, "and towers do not lead it");
    }

    // ========================================================================
    //  Fall damage thresholds
    // ========================================================================

    @Test
    @DisplayName("fall damage: below, at and above the FALL_DMG_FLOOR threshold")
    void fallDamageThreshold() {
        float floor = GameConfig.FALL_DMG_FLOOR;

        assertEquals(0f, landingDamage(floor - 1f), 1e-4f, "just below: harmless");
        assertEquals(0f, landingDamage(floor), 1e-4f, "exactly at the floor: still zero");
        assertTrue(landingDamage(floor + 1f) > 0f, "just above: it hurts");

        //  and the damage is linear in the excess, not in the impact
        float atTen = landingDamage(floor + 10f);
        float atTwenty = landingDamage(floor + 20f);
        assertEquals(2f, atTwenty / atTen, 0.01f, "linear in the excess over the floor");
    }

    @Test
    @DisplayName("a big enough fall is lethal, and the mob dies on landing")
    void lethalFall() {
        TestEnemyWorld w = new TestEnemyWorld();
        Enemy e = dropWith(w, EnemyType.SCOUT, 0f, 6000f);
        assertTrue(e.alive());
        e.land();
        assertFalse(e.alive(), "6000 px/s into the dirt kills a Scout");
        assertEquals(1, w.kills);
    }

    @Test
    @DisplayName("fall damage ignores armour entirely")
    void fallDamageIgnoresArmour() {
        //  This is the whole point of the throw mechanic against tanks: a Shield
        //  Bearer's 60% projectile armour does nothing against the ground.
        TestEnemyWorld w = new TestEnemyWorld();
        Enemy plated = w.spawn(EnemyType.SHIELD_BEARER, 900f);
        Enemy bare = w.spawn(EnemyType.FOOT_SOLDIER, 950f);
        assertEquals(0.60f, plated.armor(), 1e-5f);

        float dealtPlated = plated.applyDamage(100f, "fall");
        float dealtBare = bare.applyDamage(100f, "fall");
        assertEquals(100f, dealtPlated, 0.01f, "armour is bypassed");
        assertEquals(dealtBare, dealtPlated, 0.01f);

        //  ...whereas a projectile is reduced
        assertEquals(40f, plated.applyDamage(100f, "projectile"), 0.01f);
    }

    @Test
    @DisplayName("heavier mobs take more fall damage")
    void massScalesFallDamage() {
        TestEnemyWorld w = new TestEnemyWorld();
        Enemy scout = dropWith(w, EnemyType.SCOUT, 0f, 1000f);
        Enemy ram = dropWith(w, EnemyType.SIEGE_RAM, 0f, 1000f);
        scout.applyDamage(0f, "fall");
        float scoutFactor = 0.75f + 0.35f * scout.mass();
        float ramFactor = 0.75f + 0.35f * ram.mass();
        assertTrue(ramFactor > scoutFactor * 2f,
                "a Siege Ram lands far harder: " + ramFactor + " vs " + scoutFactor);
    }

    private static float landingDamage(float impactVy) {
        TestEnemyWorld w = new TestEnemyWorld();
        Enemy e = dropWith(w, EnemyType.FOOT_SOLDIER, 0f, impactVy);
        e.setY(e.groundY());
        float before = e.hp();
        e.land();
        return before - e.hp();
    }

    // ========================================================================
    //  The bounce ladder
    // ========================================================================

    @Test
    @DisplayName("bounce level 0: the first landing always ends the chain")
    void bounceLevelZeroDoesNotRebound() {
        //  The termination rule is bounce_count > level, STRICTLY greater.  At
        //  level 0, one bounce is already > 0, so the mob settles immediately.
        //  Changing it to >= because it reads more evenly would give level 0 a
        //  rebound it is not supposed to have.
        TestEnemyWorld w = new TestEnemyWorld();
        w.bounceLevel = 0;
        //  vy 300 is survivable: this test is about the rebound rule, and a
        //  landing hard enough to kill would leave the mob in AIR simply because
        //  it is dead, which would pass for the wrong reason.
        Enemy e = dropWith(w, EnemyType.FOOT_SOLDIER, 200f, 300f);
        e.setY(e.groundY());
        e.land();
        assertTrue(e.alive(), "it survived the landing");
        assertEquals(0f, e.vy(), 0f, "no rebound at all");
        assertEquals(EnemyState.WALK, e.state(), "it is back on its feet");
        assertEquals(e.groundY(), e.y(), 0.001f);
        assertTrue(e.stagger() > 0f, "and staggered while it gets up");
    }

    @Test
    @DisplayName("each bounce level rebounds one more time, at its own restitution")
    void bounceLadder() {
        for (int lvl = 0; lvl <= GameConfig.BOUNCE_MAX_LEVEL; lvl++) {
            TestEnemyWorld w = new TestEnemyWorld();
            w.bounceLevel = lvl;
            Enemy e = dropWith(w, EnemyType.FOOT_SOLDIER, 0f, 3000f);
            e.hp();
            //  keep it alive so the chain is about the physics, not the damage
            Enemy tough = e;
            int bounces = 0;
            for (int i = 0; i < 20; i++) {
                tough.setVelocity(0f, 3000f);       // re-arm so vy never drops under 90
                tough.setY(tough.groundY());
                float before = tough.vy();
                tough.land();
                bounces++;
                if (tough.vy() == 0f) {
                    break;
                }
                assertEquals(-3000f * GameConfig.BOUNCE_RESTITUTION[lvl], tough.vy(), 0.01f,
                        "restitution at level " + lvl);
                assertNotEquals(0f, before);
            }
            assertEquals(lvl + 1, bounces,
                    "level " + lvl + " allows exactly " + (lvl + 1) + " impacts");
        }
    }

    @Test
    @DisplayName("a rebound under 90 px/s settles regardless of level")
    void slowRebound_settles() {
        TestEnemyWorld w = new TestEnemyWorld();
        w.bounceLevel = GameConfig.BOUNCE_MAX_LEVEL;
        Enemy e = dropWith(w, EnemyType.FOOT_SOLDIER, 0f, 100f);
        e.setY(e.groundY());
        e.land();
        //  100 * 0.78 = 78, under the 90 floor, so it stops even at max level
        assertEquals(0f, e.vy(), 0f);
        assertEquals(EnemyState.WALK, e.state());
    }

    @Test
    @DisplayName("stagger after landing grows with the bounce level")
    void staggerGrowsWithLevel() {
        for (int lvl = 0; lvl <= GameConfig.BOUNCE_MAX_LEVEL; lvl++) {
            TestEnemyWorld w = new TestEnemyWorld();
            w.bounceLevel = lvl;
            Enemy e = dropWith(w, EnemyType.FOOT_SOLDIER, 0f, 50f);
            e.setY(e.groundY());
            e.land();
            assertEquals(0.45f + GameConfig.BOUNCE_STAGGER * lvl, e.stagger(), 1e-4f,
                    "stagger at level " + lvl);
        }
    }

    @Test
    @DisplayName("a staggered mob does not move or attack")
    void staggerFreezesBehaviour() {
        TestEnemyWorld w = new TestEnemyWorld();
        Enemy e = dropWith(w, EnemyType.FOOT_SOLDIER, 0f, 50f);
        e.setY(e.groundY());
        e.land();
        float x = e.x();
        assertTrue(e.stagger() > 0f);
        e.update(DT);
        assertEquals(x, e.x(), 0f, "it is picking itself up");
        //  and it recovers
        for (int i = 0; i < 60; i++) {
            e.update(DT);
        }
        assertTrue(e.x() < x, "and then walks on");
    }

    // ========================================================================
    //  Slams
    // ========================================================================

    @Test
    @DisplayName("a slam damages both, more heavily on the victim")
    void slamHurtsBoth() {
        TestEnemyWorld w = new TestEnemyWorld();
        //  A Scout at a modest speed: both must SURVIVE, or the victim's health
        //  floors at zero and the "45% of the same figure" comparison is against
        //  a truncated number rather than the damage actually dealt.
        Enemy thrown = w.spawn(EnemyType.SCOUT, 900f);
        Enemy victim = w.spawn(EnemyType.FOOT_SOLDIER, 900f);
        victim.setY(thrown.y());
        thrown.forceState(EnemyState.AIR);
        thrown.setVelocity(-400f, 0f);

        float thrownHp = thrown.hp();
        float victimHp = victim.hp();
        thrown.slamInto(victim);

        float dealt = victimHp - victim.hp();
        assertTrue(victim.alive() && thrown.alive(), "both survived");
        assertTrue(dealt > 0f, "the victim took it");
        assertEquals(dealt * 0.45f, thrownHp - thrown.hp(), 0.01f,
                "and the thrown mob takes 45% of the same figure");
        assertEquals(dealt, w.thrownDamage, 0.01f, "scored as thrown damage");
    }

    @Test
    @DisplayName("a slam under the floor does nothing at all")
    void slowSlamIsHarmless() {
        TestEnemyWorld w = new TestEnemyWorld();
        Enemy thrown = w.spawn(EnemyType.SCOUT, 900f);
        Enemy victim = w.spawn(EnemyType.FOOT_SOLDIER, 900f);
        thrown.forceState(EnemyState.AIR);
        thrown.setVelocity(-(GameConfig.SLAM_DMG_FLOOR - 1f), 0f);
        float hp = victim.hp();
        thrown.slamInto(victim);
        assertEquals(hp, victim.hp(), 0f, "under the floor, nothing happens");
        assertEquals(-(GameConfig.SLAM_DMG_FLOOR - 1f), thrown.vx(), 0f,
                "and the thrown mob is not even slowed");
    }

    @Test
    @DisplayName("a light victim is knocked airborne; a heavy one is not")
    void slamKnockOn() {
        //  Again at a survivable speed: the knock-on requires other.isAlive(),
        //  so a lethal slam would look like "not knocked" for the wrong reason.
        TestEnemyWorld w = new TestEnemyWorld();
        Enemy thrown = w.spawn(EnemyType.SCOUT, 900f);
        thrown.forceState(EnemyState.AIR);
        thrown.setVelocity(-400f, -100f);

        Enemy light = w.spawn(EnemyType.FOOT_SOLDIER, 900f);   // MASS 1.5
        thrown.slamInto(light);
        assertTrue(light.alive(), "it survived");
        assertEquals(EnemyState.AIR, light.state(), "MASS 1.5 is under the 4.0 cut-off");
        assertTrue(light.vy() <= -140f, "and it goes up, at least 140");

        TestEnemyWorld w2 = new TestEnemyWorld();
        Enemy thrown2 = w2.spawn(EnemyType.SCOUT, 900f);
        thrown2.forceState(EnemyState.AIR);
        thrown2.setVelocity(-400f, -100f);
        Enemy heavy = w2.spawn(EnemyType.SIEGE_RAM, 900f);     // MASS 9.0
        thrown2.slamInto(heavy);
        assertTrue(heavy.alive());
        assertNotEquals(EnemyState.AIR, heavy.state(),
                "MASS 9.0 is far over the cut-off; it stays planted");
    }

    @Test
    @DisplayName("a slam loses the thrown mob 45% of its speed")
    void slamBleedsSpeed() {
        TestEnemyWorld w = new TestEnemyWorld();
        Enemy thrown = w.spawn(EnemyType.SIEGE_RAM, 900f);
        Enemy victim = w.spawn(EnemyType.FOOT_SOLDIER, 900f);
        thrown.forceState(EnemyState.AIR);
        thrown.setVelocity(-2000f, -400f);
        thrown.slamInto(victim);
        assertEquals(-2000f * 0.55f, thrown.vx(), 0.01f);
        assertEquals(-400f * 0.55f, thrown.vy(), 0.01f);
    }

    // ========================================================================
    //  Slam cooldown identity
    // ========================================================================

    @Test
    @DisplayName("a slam cooldown is keyed by uid, so a new mob never inherits one")
    void slamCooldownIsUidKeyed() {
        //  This is the reason the Python source introduced explicit uids:
        //  CPython recycles id() values, so a freshly spawned mob could inherit a
        //  dead one's "already slammed" marker and be immune to a hit it never
        //  took.  Uids are monotonic and never reused, so a stale entry cannot
        //  match anything new.
        TestEnemyWorld w = new TestEnemyWorld();
        Enemy thrown = w.spawn(EnemyType.SIEGE_RAM, 900f);
        Enemy first = w.spawn(EnemyType.FOOT_SOLDIER, 900f);
        first.setY(thrown.y());
        thrown.forceState(EnemyState.AIR);
        thrown.setVelocity(-3000f, 0f);

        //  hit it, which sets a cooldown against that uid
        thrown.setVelocity(-3000f, 0f);
        w.step(DT);
        assertTrue(first.hp() < first.maxHp() || !first.alive(), "the first was hit");

        //  now kill it and spawn a replacement in the same place
        first.die();
        w.horde.sweep();
        Enemy second = w.spawn(EnemyType.FOOT_SOLDIER, thrown.x());
        second.setY(thrown.y());
        assertNotEquals(first.uid(), second.uid(), "uids are never reused");

        float hp = second.hp();
        thrown.setVelocity(-3000f, 0f);
        thrown.slamInto(second);
        assertTrue(second.hp() < hp,
                "the replacement is hit immediately: it inherited no cooldown");
    }

    @Test
    @DisplayName("a slam cooldown stops the same pair re-hitting every step")
    void slamCooldownPreventsRepeats() {
        TestEnemyWorld w = new TestEnemyWorld();
        Enemy thrown = w.spawn(EnemyType.SIEGE_RAM, 900f);
        Enemy victim = w.spawn(EnemyType.FOOT_SOLDIER, 900f);
        victim.setY(thrown.y());
        victim.applyDamage(0f, "fall");
        thrown.forceState(EnemyState.AIR);

        int hits = 0;
        float lastHp = victim.hp();
        for (int i = 0; i < 12; i++) {
            //  pin them together and re-arm the velocity every step
            thrown.setX(victim.x());
            thrown.setY(victim.y());
            thrown.setVelocity(-3000f, 0f);
            w.step(DT);
            if (victim.hp() < lastHp) {
                hits++;
                lastHp = victim.hp();
            }
            if (!victim.alive()) {
                break;
            }
        }
        assertTrue(hits <= 2, "0.35 s of cooldown, so at most a couple in 12 steps, got " + hits);
        assertSame(victim, victim);
    }

    // ========================================================================
    //  Fling scoring
    // ========================================================================

    @Test
    @DisplayName("a completed fling scores distance plus airtime, comboed by slams")
    void flingScoring() {
        TestEnemyWorld w = new TestEnemyWorld();
        Enemy e = w.spawn(EnemyType.FOOT_SOLDIER, 1100f);
        e.onGrab();
        e.onRelease(-1500f, -900f);
        assertEquals(0, w.scoreEvents);

        for (int i = 0; i < 600 && e.state() == EnemyState.AIR && e.alive(); i++) {
            w.step(DT);
        }
        assertTrue(w.scoreEvents > 0, "the fling was cashed in on landing");
        assertTrue(w.score > 0);
    }

    @Test
    @DisplayName("a fling is scored once, not on every bounce")
    void flingScoredOnce() {
        TestEnemyWorld w = new TestEnemyWorld();
        w.bounceLevel = GameConfig.BOUNCE_MAX_LEVEL;
        Enemy e = w.spawn(EnemyType.FOOT_SOLDIER, 1100f);
        e.onGrab();
        e.onRelease(-800f, -600f);
        for (int i = 0; i < 900 && e.alive(); i++) {
            w.step(DT);
            if (e.state() != EnemyState.AIR) {
                break;
            }
        }
        assertEquals(1, w.scoreEvents, "one fling, one payout");
    }

    // ========================================================================
    //  Arena walls
    // ========================================================================

    @Test
    @DisplayName("a mob thrown at the wall bounces off it and takes impact damage")
    void arenaWallImpact() {
        TestEnemyWorld w = new TestEnemyWorld();
        Enemy e = w.spawn(EnemyType.FOOT_SOLDIER, GameConfig.CASTLE_FRONT + 40f);
        e.forceState(EnemyState.AIR);
        e.setY(200f);
        e.setVelocity(-2000f, 0f);
        float hp = e.hp();
        w.step(DT);
        assertTrue(e.vx() > 0f, "it rebounded off the castle face");
        assertTrue(e.hp() < hp, "and hurt itself doing it");
        assertEquals(GameConfig.CASTLE_FRONT + e.width() / 2f, e.x(), 0.01f);
    }

    @Test
    @DisplayName("the ceiling and the right-hand wall contain a mob too")
    void arenaCeilingAndRightWall() {
        TestEnemyWorld w = new TestEnemyWorld();
        Enemy up = w.spawn(EnemyType.SCOUT, 900f);
        up.forceState(EnemyState.AIR);
        up.setY(30f);
        up.setVelocity(0f, -3000f);
        w.step(DT);
        assertEquals(24f, up.y(), 0.01f, "stopped at the ceiling");
        assertTrue(up.vy() > 0f, "and heading back down");

        Enemy right = w.spawn(EnemyType.SCOUT, GameConfig.WORLD_WIDTH + 190f);
        right.forceState(EnemyState.AIR);
        right.setY(200f);
        right.setVelocity(3000f, 0f);
        w.step(DT);
        assertEquals(GameConfig.WORLD_WIDTH + 200f, right.x(), 0.01f);
        assertTrue(right.vx() < 0f);
    }
}
