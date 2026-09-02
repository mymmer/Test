package com.mymmer.castledefense.defence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mymmer.castledefense.config.GameConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The strategic counter system, ported from the Python self-test's defence
 * assertions plus the class constants they rest on.
 *
 * <p>These are the numbers a player feels: a Ballista that does not triple its
 * damage against a flyer, or a Bowman that cannot reach one hovering over the
 * wall, is a different game even though nothing crashes.
 */
class TowerCountersTest {

    private static final float DT = 1f / 60f;

    @Test
    @DisplayName("Bowmen reach far higher than they reach far")
    void bowmanAirRangeEnvelope() {
        TestWorld w = new TestWorld();
        DefenceTower bow = w.table.createTower(w, TowerType.BOWMAN, 248f, 354f);

        //  A flyer directly above at a height that a FLAT measurement puts out
        //  of range, but the 1.9x vertical stretch brings inside it.  This is
        //  the assertion that stops flyers parking above the wall untouched.
        float range = bow.range();
        float straightUp = range * 1.6f;
        FakeTarget high = FakeTarget.flyer(bow.muzzleX(), bow.muzzleY() - straightUp);
        assertTrue(bow.reachTo(high) <= range,
                "a flyer " + straightUp + " above must be inside a " + range + " range");
        assertEquals(straightUp / 1.9f, bow.reachTo(high), 0.01f,
                "the vertical offset is divided by AIR_RANGE_MULT");

        //  the same distance horizontally is NOT in range: the stretch is
        //  upward only
        FakeTarget far = FakeTarget.ground(bow.muzzleX() + straightUp);
        far.y = bow.muzzleY();
        assertTrue(bow.reachTo(far) > range, "horizontal reach is unchanged");

        //  and downward is unstretched too -- dy < 0 means above, in pygame space
        FakeTarget below = new FakeTarget(bow.muzzleX(), bow.muzzleY() + straightUp);
        assertEquals(straightUp, bow.reachTo(below), 0.01f,
                "below the muzzle the envelope is not stretched");
    }

    @Test
    @DisplayName("Ballista deals +200% to flyers, and normal damage to ground")
    void ballistaTriplesAgainstAir() {
        TestWorld w = new TestWorld();
        TowerConfig cfg = w.table.tower(TowerType.BALLISTA);
        assertEquals(3.0f, cfg.bonusVsAir, 0f, "+200% is a x3 multiplier");

        DefenceTower gun = w.table.createTower(w, TowerType.BALLISTA, 248f, 354f);
        FakeTarget flyer = FakeTarget.flyer(500f, 300f);
        FakeTarget ground = FakeTarget.ground(500f);
        assertEquals(3f * gun.damageVs(ground), gun.damageVs(flyer), 0.001f);

        //  and the counter travels ON THE SHOT, resolved per victim, so one
        //  piercing bolt hits a flyer for triple and the next target for normal
        gun.setCooldown(0f);
        w.add(flyer);
        gun.update(DT);
        Projectile bolt = w.lastProjectile();
        assertNotNull(bolt, "the ballista fired");
        assertEquals(3f, bolt.bonusAir(), 0f);
        assertEquals(1f, bolt.bonusHeavy(), 0f);
        assertEquals(3f * bolt.damageFor(ground), bolt.damageFor(flyer), 0.001f);
    }

    @Test
    @DisplayName("Cannon deals +200% to heavies and cannot shoot air at all")
    void cannonTriplesAgainstHeavy() {
        TestWorld w = new TestWorld();
        TowerConfig cfg = w.table.tower(TowerType.CANNON);
        assertEquals(3.0f, cfg.bonusVsHeavy, 0f);
        assertFalse(cfg.hitsAir, "a lobbed shell cannot lead a flyer");

        DefenceTower gun = w.table.createTower(w, TowerType.CANNON, 248f, 354f);
        FakeTarget heavy = FakeTarget.heavy(500f);
        FakeTarget light = FakeTarget.ground(500f);
        assertEquals(3f * gun.damageVs(light), gun.damageVs(heavy), 0.001f);

        //  a flyer well inside range is simply not a candidate
        w.add(FakeTarget.flyer(400f, 300f));
        assertNull(gun.pickTarget(), "the cannon must never target air");
    }

    @Test
    @DisplayName("a Ballista prefers flyers, then the beefiest of them")
    void ballistaTargetPreference() {
        TestWorld w = new TestWorld();
        DefenceTower gun = w.table.createTower(w, TowerType.BALLISTA, 248f, 354f);

        FakeTarget closeGround = w.add(FakeTarget.ground(300f));
        FakeTarget weakFlyer = w.add(FakeTarget.flyer(600f, 400f));
        weakFlyer.hp = 50f;
        FakeTarget toughFlyer = w.add(FakeTarget.flyer(620f, 400f));
        toughFlyer.hp = 400f;

        //  ground unit is much closer and would win any distance-based scoring
        assertSame(toughFlyer, gun.pickTarget(),
                "flyers first, and among flyers the one with the most health");

        toughFlyer.alive = false;
        assertSame(weakFlyer, gun.pickTarget());
        weakFlyer.targetable = false;
        assertSame(closeGround, gun.pickTarget(), "with no flyers it takes the ground unit");
    }

    @Test
    @DisplayName("a Cannon takes a heavy over any cluster, then the densest cluster")
    void cannonTargetPreference() {
        TestWorld w = new TestWorld();
        DefenceTower gun = w.table.createTower(w, TowerType.CANNON, 248f, 354f);

        //  a tight knot of five right next to the wall...
        FakeTarget clusterLead = null;
        for (int i = 0; i < 5; i++) {
            FakeTarget t = w.add(FakeTarget.ground(400f + i * 10f));
            if (clusterLead == null) {
                clusterLead = t;
            }
        }
        //  ...and one lone heavy further out
        FakeTarget ram = w.add(FakeTarget.heavy(700f));

        assertSame(ram, gun.pickTarget(), "a heavy in range always wins");

        ram.alive = false;
        Target picked = gun.pickTarget();
        assertTrue(picked instanceof FakeTarget, "something in the cluster");
        //  every member of that knot sees the same neighbour count, so the tie
        //  breaks on distance and then on list order -- the closest to the gate
        assertSame(clusterLead, picked,
                "with equal density the nearest cluster member wins");
    }

    @Test
    @DisplayName("the default scoring prefers a countered target, then whoever is nearest the gate")
    void defaultTargetScoring() {
        TestWorld w = new TestWorld();
        DefenceTower bow = w.table.createTower(w, TowerType.BOWMAN, 248f, 354f);

        FakeTarget far = w.add(FakeTarget.ground(600f));
        FakeTarget near = w.add(FakeTarget.ground(350f));
        assertSame(near, bow.pickTarget(), "smallest x is furthest along");

        //  ties go to the earlier entry: the comparison is strictly "better"
        FakeTarget tie = w.add(FakeTarget.ground(350f));
        assertSame(near, bow.pickTarget(), "a tie must not steal the target");
        assertTrue(tie.alive && far.alive);
    }

    @Test
    @DisplayName("dead, untargetable and out-of-range units are never chosen")
    void targetingFilters() {
        TestWorld w = new TestWorld();
        DefenceTower bow = w.table.createTower(w, TowerType.BOWMAN, 248f, 354f);

        FakeTarget dead = w.add(FakeTarget.ground(300f));
        dead.alive = false;
        FakeTarget untargetable = w.add(FakeTarget.ground(310f));
        untargetable.targetable = false;
        FakeTarget outOfRange = w.add(FakeTarget.ground(GameConfig.WORLD_WIDTH));
        assertNull(bow.pickTarget());

        FakeTarget good = w.add(FakeTarget.ground(500f));
        assertSame(good, bow.pickTarget());
        assertTrue(outOfRange.alive);
    }
}
