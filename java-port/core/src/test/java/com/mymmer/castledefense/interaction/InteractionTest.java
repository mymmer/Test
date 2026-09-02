package com.mymmer.castledefense.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mymmer.castledefense.config.GameConfig;
import com.mymmer.castledefense.defence.CombatModifiers;
import com.mymmer.castledefense.enemy.Enemy;
import com.mymmer.castledefense.enemy.EnemyState;
import com.mymmer.castledefense.enemy.EnemyType;
import com.mymmer.castledefense.enemy.TestInteractionWorld;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The cursor: grab gating, capacity, dragging, throwing, stripping and shoving.
 *
 * <p>All of it driven through world-space coordinates and world-space velocities.
 * Not one test mentions a mouse, a finger or a pixel — which is the point: the
 * same drag path must produce the same result on both platforms, and nothing
 * below the input layer knows which one it is on.
 */
class InteractionTest {

    private static final float DT = 1f / 60f;

    // ========================================================================
    //  Grab gating
    // ========================================================================

    @Test
    @DisplayName("an ordinary mob within capacity can be grabbed")
    void ordinaryGrab() {
        TestInteractionWorld w = new TestInteractionWorld();
        Enemy e = w.world.spawn(EnemyType.FOOT_SOLDIER, 900f);
        assertTrue(e.grabbable());
        assertTrue(w.press(e.x(), e.y()));
        assertSame(e, w.cursor.grabbed());
        assertEquals(EnemyState.GRABBED, e.state());
    }

    @Test
    @DisplayName("a dead mob cannot be grabbed")
    void deadIsNotGrabbable() {
        TestInteractionWorld w = new TestInteractionWorld();
        Enemy e = w.world.spawn(EnemyType.FOOT_SOLDIER, 900f);
        e.die();
        assertFalse(e.grabbable());
        assertFalse(w.press(e.x(), e.y()));
        assertNull(w.cursor.grabbed());
    }

    @Test
    @DisplayName("an attacking mob CAN be grabbed -- Python allows both walk and attack")
    void attackingIsGrabbable() {
        TestInteractionWorld w = new TestInteractionWorld();
        Enemy e = w.world.spawn(EnemyType.FOOT_SOLDIER, GameConfig.CASTLE_FRONT + 5f);
        e.update(DT);
        assertEquals(EnemyState.ATTACK, e.state(), "it reached the wall");
        assertTrue(e.grabbable(), "state in (walk, attack) -- attack counts");
    }

    @Test
    @DisplayName("an airborne mob cannot be grabbed out of the air")
    void airborneIsNotGrabbable() {
        TestInteractionWorld w = new TestInteractionWorld();
        Enemy e = w.world.spawn(EnemyType.FOOT_SOLDIER, 900f);
        e.forceState(EnemyState.AIR);
        assertFalse(e.grabbable(), "only walk and attack are grabbable");
    }

    @Test
    @DisplayName("a plated heavy unit cannot be lifted at ANY grab strength")
    void armouredHeavyIsNeverLiftable() {
        TestInteractionWorld w = new TestInteractionWorld();
        w.world.grabCapacity = 9999f;              // absurd capacity
        w.cursor.setGrabLevel(GameConfig.GRAB_MAX_LEVEL);
        Enemy ram = w.world.spawn(EnemyType.SIEGE_RAM, 900f);

        assertTrue(ram.armored());
        assertFalse(ram.grabbable(),
                "Grab Strength alone is never enough: the armour comes off first");
        assertFalse(ram.shovable(), "and it cannot be shoved while plated either");
        assertTrue(ram.strippable(), "only stripping is available");
    }

    @Test
    @DisplayName("a fully stripped heavy unit becomes liftable once capacity allows")
    void strippedHeavyBecomesLiftable() {
        TestInteractionWorld w = new TestInteractionWorld();
        Enemy ram = w.world.spawn(EnemyType.SIEGE_RAM, 900f);
        while (ram.layers() > 0) {
            ram.applyStrip(GameConfig.STRIP_DISTANCE);
        }
        assertFalse(ram.armored());
        assertFalse(ram.strippable(), "nothing left to tear off");
        assertTrue(ram.shovable(), "but a bare hull can be hauled");

        //  MASS 9.0 against the capacity table
        w.world.grabCapacity = GameConfig.GRAB_CAPACITY[2];      // 7.5
        assertFalse(ram.grabbable(), "7.5 is not enough for MASS 9");
        assertTrue(ram.tooHeavy(), "and the game says so");

        w.world.grabCapacity = GameConfig.GRAB_CAPACITY[3];      // 9.5
        assertTrue(ram.grabbable(), "9.5 finally lifts it");
        assertFalse(ram.tooHeavy());
    }

    @Test
    @DisplayName("the capacity boundary is inclusive: MASS exactly equal to capacity lifts")
    void capacityBoundaryIsInclusive() {
        TestInteractionWorld w = new TestInteractionWorld();
        Enemy ram = w.world.spawn(EnemyType.SIEGE_RAM, 900f);
        while (ram.layers() > 0) {
            ram.applyStrip(GameConfig.STRIP_DISTANCE);
        }
        w.world.grabCapacity = 9f;              // exactly MASS
        assertTrue(ram.grabbable(), "mass <= capacity, so equal lifts");
        w.world.grabCapacity = 8.999f;
        assertFalse(ram.grabbable());
    }

    // ========================================================================
    //  Grab capacity table
    // ========================================================================

    @Test
    @DisplayName("grab capacity walks the table and clamps at both ends")
    void capacityTable() {
        TestInteractionWorld w = new TestInteractionWorld();
        for (int lvl = 0; lvl <= GameConfig.GRAB_MAX_LEVEL; lvl++) {
            w.cursor.setGrabLevel(lvl);
            assertEquals(GameConfig.GRAB_CAPACITY[lvl], w.cursor.grabCapacity(), 1e-4f,
                    "level " + lvl);
        }
        w.cursor.setGrabLevel(-5);
        assertEquals(GameConfig.GRAB_CAPACITY[0], w.cursor.grabCapacity(), 1e-4f);
        w.cursor.setGrabLevel(99);
        assertEquals(GameConfig.GRAB_CAPACITY[GameConfig.GRAB_MAX_LEVEL],
                w.cursor.grabCapacity(), 1e-4f);
    }

    @Test
    @DisplayName("the Light Hands talent multiplies the table entry, not a formula")
    void capacityTalentMultipliesTheEntry() {
        //  Python multiplies GRAB_CAPACITY[level] by the talent, so the talent
        //  can push one level's capacity past the next level's threshold.  That
        //  is why the table is a table and not an interpolated curve: a
        //  "normalised" progression would change which level lifts a Siege Ram.
        TestInteractionWorld w = new TestInteractionWorld();
        w.world.modifiers = new CombatModifiers() {
            @Override
            public float grabBonus() {
                return 1.25f;
            }
        };
        w.cursor.setGrabLevel(2);
        assertEquals(7.5f * 1.25f, w.cursor.grabCapacity(), 1e-4f);
        assertTrue(w.cursor.grabCapacity() > 9f,
                "9.375 clears a Siege Ram's MASS 9 a whole level early");
    }

    // ========================================================================
    //  Dragging and throwing
    // ========================================================================

    @Test
    @DisplayName("a held mob follows the cursor, more sluggishly the heavier it is")
    void dragFollow() {
        TestInteractionWorld w = new TestInteractionWorld();
        Enemy light = w.world.spawn(EnemyType.SCOUT, 900f);
        w.press(light.x(), light.y());
        float startX = light.x();
        w.cursor.update(DT, true, startX + 100f, light.y());
        float lightMoved = light.x() - startX;

        TestInteractionWorld w2 = new TestInteractionWorld();
        w2.world.grabCapacity = 99f;
        Enemy heavy = w2.world.spawn(EnemyType.SIEGE_RAM, 900f);
        while (heavy.layers() > 0) {
            heavy.applyStrip(GameConfig.STRIP_DISTANCE);
        }
        w2.press(heavy.x(), heavy.y());
        assertSame(heavy, w2.cursor.grabbed());
        float heavyStart = heavy.x();
        w2.cursor.update(DT, true, heavyStart + 100f, heavy.y());
        float heavyMoved = heavy.x() - heavyStart;

        assertTrue(lightMoved > heavyMoved,
                "a Scout is glued to the cursor, a Ram lags: "
                        + lightMoved + " vs " + heavyMoved);
        //  and the follow fraction is clamped, so nothing is ever unresponsive
        assertTrue(heavyMoved >= 100f * 0.25f - 0.01f, "clamped at 0.25 minimum");
    }

    @Test
    @DisplayName("releasing throws at the world-space flick velocity")
    void releaseThrows() {
        TestInteractionWorld w = new TestInteractionWorld();
        Enemy e = w.world.spawn(EnemyType.FOOT_SOLDIER, 900f);
        w.press(e.x(), e.y());
        w.setVelocity(-1200f, -800f);
        w.release();

        assertNull(w.cursor.grabbed());
        assertEquals(EnemyState.AIR, e.state());
        float mult = GameConfig.THROW_POWER / (0.55f + 0.45f * e.mass());
        assertEquals(-1200f * mult, e.vx(), 0.01f);
        assertEquals(-800f * mult, e.vy(), 0.01f);
    }

    @Test
    @DisplayName("a cancelled grab DROPS the mob instead of throwing it")
    void cancelDropsRatherThanThrows() {
        //  The phone rang.  Throwing the mob because of that would be a free kill
        //  the player never earned.
        TestInteractionWorld w = new TestInteractionWorld();
        Enemy e = w.world.spawn(EnemyType.FOOT_SOLDIER, 900f);
        w.press(e.x(), e.y());
        w.setVelocity(-2000f, -2000f);
        w.cancel();

        assertNull(w.cursor.grabbed());
        assertEquals(EnemyState.AIR, e.state(), "it is released...");
        assertEquals(0f, e.vx(), 0f, "...but with no velocity at all");
        assertEquals(0f, e.vy(), 0f);
    }

    @Test
    @DisplayName("a grab cooldown blocks the next grab for its full duration")
    void grabCooldown() {
        TestInteractionWorld w = new TestInteractionWorld();
        w.cursor.setGrabCooldown(0.5f);
        Enemy first = w.world.spawn(EnemyType.FOOT_SOLDIER, 900f);
        Enemy second = w.world.spawn(EnemyType.FOOT_SOLDIER, 950f);

        w.press(first.x(), first.y());
        w.release();
        assertEquals(0.5f, w.cursor.grabCdRemaining(), 1e-4f);

        assertFalse(w.press(second.x(), second.y()), "locked out");
        for (int i = 0; i < 29; i++) {
            w.cursor.update(DT, false, 0f, 0f);
        }
        assertFalse(w.press(second.x(), second.y()), "still locked out");
        for (int i = 0; i < 3; i++) {
            w.cursor.update(DT, false, 0f, 0f);
        }
        assertTrue(w.press(second.x(), second.y()), "and free again");
    }

    @Test
    @DisplayName("a zero cooldown allows an immediate second grab")
    void zeroCooldown() {
        TestInteractionWorld w = new TestInteractionWorld();
        w.cursor.setGrabCooldown(0f);
        Enemy first = w.world.spawn(EnemyType.FOOT_SOLDIER, 900f);
        Enemy second = w.world.spawn(EnemyType.FOOT_SOLDIER, 950f);
        w.press(first.x(), first.y());
        w.release();
        assertTrue(w.press(second.x(), second.y()), "Easy has no delay at all");
    }

    // ========================================================================
    //  Multi-grab
    // ========================================================================

    @Test
    @DisplayName("Magnetic Gloves drag neighbours along, up to the level")
    void multiGrab() {
        TestInteractionWorld w = new TestInteractionWorld();
        w.cursor.setMultiLevel(2);
        Enemy main = w.world.spawn(EnemyType.FOOT_SOLDIER, 900f);
        Enemy near1 = w.world.spawn(EnemyType.FOOT_SOLDIER, 930f);
        Enemy near2 = w.world.spawn(EnemyType.FOOT_SOLDIER, 960f);
        Enemy near3 = w.world.spawn(EnemyType.FOOT_SOLDIER, 980f);
        Enemy far = w.world.spawn(EnemyType.FOOT_SOLDIER, 900f + GameConfig.MULTI_RADIUS + 50f);
        for (Enemy e : new Enemy[]{main, near1, near2, near3, far}) {
            e.setY(main.y());
        }

        w.press(main.x(), main.y());
        assertSame(main, w.cursor.grabbed());
        assertEquals(2, w.cursor.extraGrabbedCount(), "capped at the level");
        assertEquals(EnemyState.GRABBED, near1.state());
        assertEquals(EnemyState.GRABBED, near2.state());
        assertEquals(EnemyState.WALK, near3.state(), "over the cap");
        assertEquals(EnemyState.WALK, far.state(), "outside MULTI_RADIUS");
    }

    @Test
    @DisplayName("each extra mob is released with its own 0.85-1.15 jitter")
    void multiGrabReleaseJitter() {
        float[] a = multiReleaseVelocities(777L);
        float[] b = multiReleaseVelocities(777L);
        float[] c = multiReleaseVelocities(778L);

        //  same seed, identical scatter
        assertEquals(a.length, b.length);
        for (int i = 0; i < a.length; i++) {
            assertEquals(a[i], b[i], 0f, "same seed must reproduce sample " + i);
        }
        //  different seed, different scatter
        boolean differs = false;
        for (int i = 0; i < a.length && !differs; i++) {
            differs = a[i] != c[i];
        }
        assertTrue(differs, "a different seed must scatter differently");

        //  and every value is inside the intended band
        float base = -1000f * (GameConfig.THROW_POWER / (0.55f + 0.45f * 1.5f));
        for (int i = 0; i < a.length; i++) {
            float ratio = a[i] / base;
            assertTrue(ratio >= 0.85f - 1e-4f && ratio <= 1.15f + 1e-4f,
                    "jitter ratio " + ratio + " outside 0.85..1.15");
        }
    }

    /** Grabs three mobs, throws them, and returns the extras' x velocities. */
    private static float[] multiReleaseVelocities(long seed) {
        TestInteractionWorld w = new TestInteractionWorld(seed);
        w.cursor.setMultiLevel(3);
        Enemy main = w.world.spawn(EnemyType.FOOT_SOLDIER, 900f);
        Enemy[] extra = {
                w.world.spawn(EnemyType.FOOT_SOLDIER, 920f),
                w.world.spawn(EnemyType.FOOT_SOLDIER, 940f),
                w.world.spawn(EnemyType.FOOT_SOLDIER, 960f),
        };
        for (Enemy e : extra) {
            e.setY(main.y());
        }
        w.press(main.x(), main.y());
        w.setVelocity(-1000f, 0f);
        w.release();

        float[] out = new float[extra.length];
        for (int i = 0; i < extra.length; i++) {
            out[i] = extra[i].vx();
        }
        return out;
    }

    @Test
    @DisplayName("the primary mob's release is exact -- only the extras are jittered")
    void primaryReleaseIsNotJittered() {
        TestInteractionWorld w = new TestInteractionWorld(777L);
        w.cursor.setMultiLevel(2);
        Enemy main = w.world.spawn(EnemyType.FOOT_SOLDIER, 900f);
        Enemy extra = w.world.spawn(EnemyType.FOOT_SOLDIER, 920f);
        extra.setY(main.y());

        w.press(main.x(), main.y());
        w.setVelocity(-1000f, -500f);
        w.release();

        float mult = GameConfig.THROW_POWER / (0.55f + 0.45f * main.mass());
        assertEquals(-1000f * mult, main.vx(), 0.01f, "exact, no jitter");
        assertEquals(-500f * mult, main.vy(), 0.01f);
        assertTrue(extra.vx() != main.vx() || extra.vy() != main.vy(),
                "and the extra is scattered off it");
    }

    // ========================================================================
    //  Stripping and shoving
    // ========================================================================

    @Test
    @DisplayName("pressing a plated tank starts stripping, not grabbing")
    void pressOnTankStartsStripping() {
        TestInteractionWorld w = new TestInteractionWorld();
        Enemy ram = w.world.spawn(EnemyType.SIEGE_RAM, 900f);
        assertTrue(w.press(ram.x(), ram.y()));
        assertNull(w.cursor.grabbed(), "it is not lifted");
        assertSame(ram, w.cursor.stripping());
    }

    @Test
    @DisplayName("dragging AWAY strips plates; dragging toward does nothing while plated")
    void strippingDirection() {
        TestInteractionWorld w = new TestInteractionWorld();
        Enemy ram = w.world.spawn(EnemyType.SIEGE_RAM, 900f);
        w.press(ram.x(), ram.y());

        //  toward the castle (negative dx): refused while the plating is on
        w.cursor.update(DT, true, ram.x() - GameConfig.STRIP_DISTANCE, ram.y());
        assertEquals(3, ram.layers(), "ARMOUR FIRST");
        assertEquals(0f, ram.shove(), 0f, "and no shove either");

        //  away from the castle (positive dx): a full strip distance frees a plate
        float anchor = ram.x() - GameConfig.STRIP_DISTANCE;
        w.cursor.update(DT, true, anchor + GameConfig.STRIP_DISTANCE, ram.y());
        assertEquals(2, ram.layers(), "one plate came away");
        assertEquals(1, w.world.platesTorn);
    }

    @Test
    @DisplayName("the full Siege Ram progression: armoured to liftable")
    void siegeRamFullProgression() {
        //  The primary integration test for the whole interaction stack, mirroring
        //  the Python self-test's invariant chain.
        TestInteractionWorld w = new TestInteractionWorld();
        w.cursor.setGrabLevel(3);              // capacity 9.5
        w.world.grabCapacity = w.cursor.grabCapacity();
        Enemy ram = w.world.spawn(EnemyType.SIEGE_RAM, 900f);

        float armour0 = ram.armor();
        float speed0 = ram.speed();
        float vuln0 = ram.vulnerable();

        assertTrue(ram.armored());
        assertFalse(ram.grabbable(), "armoured: cannot be grabbed");
        assertFalse(ram.shovable(), "armoured: cannot be shoved");

        int plate = 0;
        float lastArmour = armour0;
        float lastSpeed = speed0;
        float lastVuln = vuln0;
        while (ram.layers() > 0) {
            assertTrue(ram.applyStrip(GameConfig.STRIP_DISTANCE));
            plate++;
            assertTrue(ram.armor() < lastArmour, "armour decreases: plate " + plate);
            assertTrue(ram.speed() < lastSpeed, "speed decreases: plate " + plate);
            assertTrue(ram.vulnerable() > lastVuln, "vulnerability rises: plate " + plate);
            lastArmour = ram.armor();
            lastSpeed = ram.speed();
            lastVuln = ram.vulnerable();
        }
        assertEquals(3, plate, "three plates");
        assertEquals(0f, ram.armor(), 1e-6f, "fully stripped");
        assertFalse(ram.strippable(), "no longer strippable");
        assertFalse(ram.armored());
        assertTrue(ram.shovable(), "and now shovable");
        assertTrue(ram.grabbable(), "and liftable at Grab Strength 3");
    }

    @Test
    @DisplayName("a bare tank can be shoved castle-ward, up to SHOVE_MAX")
    void shoving() {
        TestInteractionWorld w = new TestInteractionWorld();
        Enemy ram = w.world.spawn(EnemyType.SIEGE_RAM, 900f);
        while (ram.layers() > 0) {
            ram.applyStrip(GameConfig.STRIP_DISTANCE);
        }
        w.press(ram.x(), ram.y());
        assertSame(ram, w.cursor.stripping(), "the grip survives the armour coming off");

        w.cursor.update(DT, true, ram.x() - 50f, ram.y());
        assertEquals(50f * GameConfig.SHOVE_FACTOR, ram.shove(), 0.01f);

        //  it saturates
        for (int i = 0; i < 20; i++) {
            w.cursor.update(DT, true, ram.x() - 500f, ram.y());
        }
        assertEquals(GameConfig.SHOVE_MAX, ram.shove(), 0.01f);
    }

    @Test
    @DisplayName("a shove carries the tank forward and decays away")
    void shoveDecays() {
        TestInteractionWorld w = new TestInteractionWorld();
        Enemy ram = w.world.spawn(EnemyType.SIEGE_RAM, 900f);
        while (ram.layers() > 0) {
            ram.applyStrip(GameConfig.STRIP_DISTANCE);
        }
        ram.applyShove(100f);
        float shove0 = ram.shove();
        assertTrue(shove0 > 0f);

        float x0 = ram.x();
        ram.update(DT);
        assertTrue(ram.x() < x0 - ram.speed() * DT,
                "it moves further than walking alone would carry it");
        assertTrue(ram.shove() < shove0, "and the shove bleeds off");

        for (int i = 0; i < 300; i++) {
            ram.update(DT);
        }
        assertEquals(0f, ram.shove(), 0f, "eventually to nothing");
    }

    @Test
    @DisplayName("stripping a mob that dies mid-drag releases the grip")
    void strippingReleasesOnDeath() {
        TestInteractionWorld w = new TestInteractionWorld();
        Enemy ram = w.world.spawn(EnemyType.SIEGE_RAM, 900f);
        w.press(ram.x(), ram.y());
        assertNotNull(w.cursor.stripping());
        ram.die();
        w.cursor.update(DT, true, ram.x() + 10f, ram.y());
        assertNull(w.cursor.stripping());
    }

    @Test
    @DisplayName("a grabbed mob that dies mid-drag releases the grip")
    void grabReleasesOnDeath() {
        TestInteractionWorld w = new TestInteractionWorld();
        Enemy e = w.world.spawn(EnemyType.FOOT_SOLDIER, 900f);
        w.press(e.x(), e.y());
        e.die();
        w.cursor.update(DT, true, e.x(), e.y());
        assertNull(w.cursor.grabbed());
    }

    @Test
    @DisplayName("a held mob is clamped inside the arena")
    void dragClamping() {
        TestInteractionWorld w = new TestInteractionWorld();
        Enemy e = w.world.spawn(EnemyType.FOOT_SOLDIER, 900f);
        w.press(e.x(), e.y());
        for (int i = 0; i < 40; i++) {
            w.cursor.update(DT, true, -5000f, -5000f);
        }
        assertEquals(GameConfig.CASTLE_FRONT + e.width() / 2f, e.x(), 0.5f);
        assertEquals(30f, e.y(), 0.5f, "and it cannot be dragged through the ceiling");
    }
}
