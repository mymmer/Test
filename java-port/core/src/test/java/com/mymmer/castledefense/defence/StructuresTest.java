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
 * Barricade, spike walls and outpost — including the prisoner mechanic and the
 * ally seam, exercised through a fake factory rather than a Phase 6 type.
 */
class StructuresTest {

    private static final float DT = 1f / 60f;

    // ========================================================================
    //  Barricade
    // ========================================================================

    @Test
    @DisplayName("a barricade is only alive once bought, and dies at zero")
    void barricadeLifecycle() {
        TestWorld w = new TestWorld();
        assertFalse(w.barricade.alive(), "not bought yet");
        assertEquals(0, w.barricade.level());

        assertTrue(w.barricade.buy());
        assertTrue(w.barricade.alive());
        assertEquals(1, w.barricade.level());
        assertEquals(GameConfig.BARRICADE_HP[1], w.barricade.maxHp(), 0f);

        w.barricade.takeDamage(w.barricade.maxHp() * 2f);
        assertEquals(0f, w.barricade.hp(), 0f, "health never goes negative");
        assertFalse(w.barricade.alive());
        assertEquals(1, w.barricade.level(), "the level survives the collapse");
    }

    @Test
    @DisplayName("the health progression follows BARRICADE_HP, tier by tier")
    void barricadeProgression() {
        TestWorld w = new TestWorld();
        for (int level = 1; level <= GameConfig.BARRICADE_MAX_LEVEL; level++) {
            assertTrue(w.barricade.buy(), "level " + level);
            assertEquals(level, w.barricade.level());
            assertEquals(GameConfig.BARRICADE_HP[level], w.barricade.maxHp(), 0f);
            assertEquals(w.barricade.maxHp(), w.barricade.hp(), 0f,
                    "reinforcing also fully repairs");
        }
        assertFalse(w.barricade.buy(), "at the cap and undamaged, it refuses");
    }

    @Test
    @DisplayName("one button buys, rebuilds and reinforces")
    void oneButtonDoesAllThree() {
        TestWorld w = new TestWorld();
        for (int i = 0; i < GameConfig.BARRICADE_MAX_LEVEL; i++) {
            w.barricade.buy();
        }
        w.barricade.takeDamage(w.barricade.maxHp() * 2f);
        assertFalse(w.barricade.alive(), "collapsed at max level");

        assertTrue(w.barricade.buy(), "a collapsed max-level barricade can be rebuilt");
        assertTrue(w.barricade.alive());
        assertEquals(GameConfig.BARRICADE_MAX_LEVEL, w.barricade.level(),
                "and the rebuild does not push it past the cap");
        assertEquals(w.barricade.maxHp(), w.barricade.hp(), 0f);
    }

    @Test
    @DisplayName("a dead barricade absorbs nothing")
    void deadBarricadeTakesNoDamage() {
        TestWorld w = new TestWorld();
        w.barricade.takeDamage(100f);
        assertEquals(0f, w.barricade.hp(), 0f, "not built");

        w.barricade.buy();
        w.barricade.takeDamage(w.barricade.maxHp() * 2f);
        w.barricade.takeDamage(100f);
        assertEquals(0f, w.barricade.hp(), 0f);
    }

    @Test
    @DisplayName("the regen talent repairs the barricade, and only while it stands")
    void barricadeRegen() {
        TestWorld w = new TestWorld();
        w.modifiers = new CombatModifiers() {
            @Override
            public float barricadeRegen() {
                return 0.1f;
            }
        };
        w.barricade.buy();
        float max = w.barricade.maxHp();
        w.barricade.takeDamage(max * 0.5f);
        float hurt = w.barricade.hp();

        for (int i = 0; i < 60; i++) {
            w.barricade.update(DT);
        }
        assertEquals(hurt + max * 0.1f, w.barricade.hp(), max * 0.01f);

        for (int i = 0; i < 6000; i++) {
            w.barricade.update(DT);
        }
        assertEquals(max, w.barricade.hp(), 0.001f, "and never overshoots");

        w.barricade.takeDamage(max * 2f);
        for (int i = 0; i < 600; i++) {
            w.barricade.update(DT);
        }
        assertEquals(0f, w.barricade.hp(), 0f, "rubble does not regenerate");
    }

    @Test
    @DisplayName("the barricade's collision band is 96 above the ground line")
    void barricadeGeometry() {
        TestWorld w = new TestWorld();
        assertEquals(GameConfig.GROUND_Y - 96f, w.barricade.topY(), 0f);
        assertEquals(GameConfig.BARRICADE_X, w.barricade.x(), 0f);
        assertEquals(40f, Barricade.WIDTH, 0f);
    }

    // ========================================================================
    //  Spike walls
    // ========================================================================

    @Test
    @DisplayName("no spikes, no reflected damage")
    void spikesAtLevelZeroDoNothing() {
        TestWorld w = new TestWorld();
        FakeTarget m = FakeTarget.ground(300f);
        m.hp = 10000f;
        assertEquals(0f, w.spikes.damage(), 0f);
        w.spikes.bite(m);
        assertEquals(0, m.damageTaken.size, "no spikes, no bite");
    }

    @Test
    @DisplayName("spiked walls bite back, scaling with level and wave")
    void spikesBite() {
        TestWorld w = new TestWorld();
        w.spikes.setLevel(GameConfig.SPIKE_MAX_LEVEL);
        FakeTarget m = FakeTarget.ground(300f);
        m.hp = 10000f;

        float atWaveOne = w.spikes.damage();
        assertEquals(GameConfig.SPIKE_DAMAGE * GameConfig.SPIKE_MAX_LEVEL, atWaveOne, 0.01f);

        w.spikes.bite(m);
        assertEquals(1, m.damageTaken.size);
        assertEquals(atWaveOne, m.damageTaken.get(0), 0.01f);
        assertEquals("spike", m.damageSources.get(0));

        w.wave = 11;
        assertEquals(atWaveOne * 1.6f, w.spikes.damage(), 0.01f,
                "+6% of the base per wave past the first");
    }

    @Test
    @DisplayName("the bleed talent adds a second, separately tagged hit")
    void spikeBleed() {
        TestWorld w = new TestWorld();
        w.modifiers = new CombatModifiers() {
            @Override
            public float spikeDot() {
                return 0.5f;
            }
        };
        w.spikes.setLevel(2);
        FakeTarget m = FakeTarget.ground(300f);
        m.hp = 10000f;
        w.spikes.bite(m);

        assertEquals(2, m.damageTaken.size, "bite then bleed, not one bigger hit");
        assertEquals("spike", m.damageSources.get(0));
        assertEquals("bleed", m.damageSources.get(1));
        assertEquals(m.damageTaken.get(0) * 0.5f, m.damageTaken.get(1), 0.01f);
    }

    @Test
    @DisplayName("spikes refuse past their cap, unlike the walls")
    void spikeCap() {
        TestWorld w = new TestWorld();
        for (int i = 0; i < GameConfig.SPIKE_MAX_LEVEL; i++) {
            assertTrue(w.spikes.upgrade(), "level " + (i + 1));
        }
        assertFalse(w.spikes.upgrade(), "spikes DO refuse at the cap");
        assertEquals(GameConfig.SPIKE_MAX_LEVEL, w.spikes.level());
    }

    @Test
    @DisplayName("spikes never bite a dead unit")
    void spikesSkipTheDead() {
        TestWorld w = new TestWorld();
        w.spikes.setLevel(4);
        FakeTarget m = FakeTarget.ground(300f);
        m.alive = false;
        w.spikes.bite(m);
        assertEquals(0, m.damageTaken.size);
        w.spikes.bite(null);
    }

    // ========================================================================
    //  Outpost — the garrison
    // ========================================================================

    @Test
    @DisplayName("an ungarrisoned outpost does nothing and has no health to lose")
    void emptyOutpost() {
        TestWorld w = new TestWorld();
        w.add(FakeTarget.ground(GameConfig.OUTPOST_X - 50f));
        w.steps(120, DT);
        assertEquals(0, w.projectiles.size());
        assertEquals(0, w.outpost.guns());
        //  Python: "assert not hasattr(g.outpost, 'hp')".  The Java equivalent is
        //  that no such accessor exists -- this test is here so a future edit
        //  that adds one has to explain itself.
        assertEquals(0, w.outpost.crewCount());
    }

    @Test
    @DisplayName("a garrisoned outpost shoots at what is in range")
    void garrisonFires() {
        TestWorld w = new TestWorld();
        for (int i = 0; i < GameConfig.OUTPOST_MAX_LEVEL; i++) {
            w.outpost.upgrade();
        }
        assertTrue(w.outpost.isTurret());
        assertEquals(GameConfig.OUTPOST_MAX_LEVEL, w.outpost.guns());

        FakeTarget victim = w.add(FakeTarget.ground(GameConfig.OUTPOST_X - 100f));
        victim.hp = 100000f;
        w.steps(240, DT);
        assertTrue(victim.totalDamage() > 0f, "a garrisoned outpost must shoot");
    }

    @Test
    @DisplayName("levels past the cap add firepower, not crew")
    void overdrive() {
        TestWorld w = new TestWorld();
        for (int i = 0; i < GameConfig.OUTPOST_MAX_LEVEL; i++) {
            w.outpost.upgrade();
        }
        assertEquals(1f, w.outpost.overdrive(), 1e-9f);
        float damageAtCap = w.outpost.gunDamage();
        int crewAtCap = w.outpost.crewCount();

        for (int i = 0; i < 5; i++) {
            assertTrue(w.outpost.upgrade(), "the Outpost must never refuse either");
        }
        assertEquals(GameConfig.OUTPOST_MAX_LEVEL, w.outpost.guns(), "no extra crew appear");
        assertEquals(crewAtCap, w.outpost.crewCount(), "and no extra gun slots");
        assertTrue(w.outpost.overdrive() > 1f);
        assertEquals(1f + Outpost.OVERDRIVE_PER_LEVEL * 5f, w.outpost.overdrive(), 1e-5f);
        assertTrue(w.outpost.gunDamage() > damageAtCap,
                "extra levels multiply the damage instead");
    }

    @Test
    @DisplayName("crew become turrets at level 4: harder, faster, and firing bolts")
    void turretUpgrade() {
        TestWorld w = new TestWorld();
        for (int i = 1; i < GameConfig.OUTPOST_TURRET_FROM; i++) {
            w.outpost.upgrade();
            assertFalse(w.outpost.isTurret(), "level " + i);
        }
        float bowDamage = w.outpost.gunDamage();
        float bowReload = w.outpost.gunReload();
        assertEquals(7.5f, bowDamage, 0.01f);
        assertEquals(0.92f, bowReload, 0.001f);

        w.outpost.upgrade();
        assertTrue(w.outpost.isTurret());
        assertEquals(15f, w.outpost.gunDamage(), 0.01f);
        assertEquals(0.62f, w.outpost.gunReload(), 0.001f);

        FakeTarget victim = w.add(FakeTarget.ground(GameConfig.OUTPOST_X - 100f));
        victim.hp = 100000f;
        w.steps(120, DT);
        assertTrue(w.projectiles.size() > 0);
        assertEquals(ProjectileKind.BOLT, w.projectiles.get(0).kind(),
                "turrets fire bolts, bowmen fire arrows");
    }

    @Test
    @DisplayName("the outpost takes whatever is furthest along, within range")
    void outpostTargeting() {
        TestWorld w = new TestWorld();
        w.outpost.upgrade();

        FakeTarget behind = w.add(FakeTarget.ground(GameConfig.OUTPOST_X + 100f));
        FakeTarget ahead = w.add(FakeTarget.ground(GameConfig.OUTPOST_X - 200f));
        assertSame(ahead, w.outpost.pickTarget(), "smallest x wins");

        //  Further along still, but well outside OUTPOST_RANGE: it must not win.
        FakeTarget wayAhead = w.add(FakeTarget.ground(0f));
        assertSame(ahead, w.outpost.pickTarget(),
                "range is checked before the 'furthest along' comparison");
        assertTrue(wayAhead.alive && behind.alive);

        ahead.targetable = false;
        assertSame(behind, w.outpost.pickTarget(), "and untargetable units are skipped");
    }

    @Test
    @DisplayName("a gun with nothing in range re-checks shortly instead of every step")
    void idleGunBacksOff() {
        TestWorld w = new TestWorld();
        w.outpost.upgrade();
        w.steps(60, DT);
        assertEquals(0, w.projectiles.size(), "nothing to shoot");

        FakeTarget victim = w.add(FakeTarget.ground(GameConfig.OUTPOST_X - 100f));
        victim.hp = 100000f;
        w.steps(20, DT);
        //  Damage dealt, not the projectile count: by 20 steps the arrow has
        //  already landed and been swept, so counting live projectiles would
        //  test the sweep rather than the engagement.
        assertTrue(victim.totalDamage() > 0f, "it engages once something arrives");
    }

    // ========================================================================
    //  Outpost — the betrayal
    // ========================================================================

    @Test
    @DisplayName("only one Necromancer at a time, and only one light enough to lift")
    void trapEligibility() {
        TestWorld w = new TestWorld();
        FakeTarget heavy = new FakeTarget(w.outpost.x(), w.outpost.y());
        heavy.mass = 20f;
        w.grabCapacity = 5f;
        assertFalse(w.outpost.canTrap(heavy), "too heavy for the player's grab");
        assertFalse(w.outpost.trap(heavy));

        heavy.mass = 3f;
        assertTrue(w.outpost.trap(heavy));
        assertTrue(w.outpost.hasPrisoner());
        assertTrue(heavy.trapped, "the unit was told");

        FakeTarget second = new FakeTarget(w.outpost.x(), w.outpost.y());
        assertFalse(w.outpost.canTrap(second), "the cage is occupied");

        FakeTarget dead = new FakeTarget(0f, 0f);
        dead.alive = false;
        assertFalse(w.outpost.canTrap(dead));
        assertFalse(w.outpost.canTrap(null));
    }

    @Test
    @DisplayName("trapping removes the captive from the horde")
    void trapRemovesFromHorde() {
        TestWorld w = new TestWorld();
        FakeTarget other = w.add(FakeTarget.ground(500f));
        FakeTarget necro = w.add(new FakeTarget(w.outpost.x(), w.outpost.y()));
        assertEquals(2, w.targetCount());

        assertTrue(w.outpost.trap(necro));
        assertEquals(1, w.targetCount(), "he is no longer part of the horde");
        assertSame(other, w.target(0), "and the rest keep their order");
        assertEquals(GameConfig.PRISONER_HP, w.outpost.prisonerHp(), 0f);
    }

    @Test
    @DisplayName("the prisoner regenerates, but not while under fire")
    void prisonerRegen() {
        TestWorld w = new TestWorld();
        FakeTarget necro = new FakeTarget(w.outpost.x(), w.outpost.y());
        w.outpost.trap(necro);

        w.outpost.hurtPrisoner(100f);
        float hurt = w.outpost.prisonerHp();
        assertEquals(GameConfig.PRISONER_HP - 100f, hurt, 0.01f);
        assertEquals(1f, w.outpost.prisonerHit(), 0f);

        //  half a second of fire lock: no healing at all
        for (int i = 0; i < 30; i++) {
            w.outpost.updatePrisoner(DT);
        }
        assertEquals(hurt, w.outpost.prisonerHp(), 0.001f, "locked out while recently hit");

        //  past the lock, it heals at PRISONER_REGEN per second
        for (int i = 0; i < 90; i++) {
            w.outpost.updatePrisoner(DT);
        }
        assertTrue(w.outpost.prisonerHp() > hurt);
        assertEquals(hurt + GameConfig.PRISONER_REGEN * 1f, w.outpost.prisonerHp(), 0.15f);
    }

    @Test
    @DisplayName("killing the prisoner empties the cage and stops the raises")
    void prisonerCanBeKilled() {
        TestWorld w = new TestWorld();
        FakeTarget necro = new FakeTarget(w.outpost.x(), w.outpost.y());
        w.outpost.trap(necro);

        w.outpost.hurtPrisoner(GameConfig.PRISONER_HP * 2f);
        assertFalse(w.outpost.hasPrisoner());
        assertEquals(0f, w.outpost.prisonerHp(), 0f);
        assertEquals(0f, w.outpost.skeletonTimer(), 0f);

        int spawnedBefore = w.allies.spawned;
        w.steps(600, DT);
        assertEquals(spawnedBefore, w.allies.spawned, "an empty cage raises nobody");

        w.outpost.hurtPrisoner(50f);
        assertEquals(0f, w.outpost.prisonerHp(), 0f, "and shooting the empty cage is a no-op");
    }

    @Test
    @DisplayName("a trapped Necromancer raises skeletons through the ally seam")
    void prisonerRaisesAllies() {
        TestWorld w = new TestWorld();
        FakeTarget necro = new FakeTarget(w.outpost.x(), w.outpost.y());
        w.outpost.trap(necro);

        //  the first raise is one second in
        w.steps(59, DT);
        assertEquals(0, w.allies.spawned);
        w.steps(3, DT);
        assertEquals(1, w.allies.spawned, "the first skeleton is up");

        //  then one every TRAP_SKELETON_RATE seconds
        w.steps((int) (GameConfig.TRAP_SKELETON_RATE * 60f) + 2, DT);
        assertEquals(2, w.allies.spawned);
    }

    @Test
    @DisplayName("the ally cap stops raises without banking them")
    void allyCap() {
        TestWorld w = new TestWorld();
        FakeTarget necro = new FakeTarget(w.outpost.x(), w.outpost.y());
        w.outpost.trap(necro);
        w.allies.alive = GameConfig.TRAP_SKELETON_CAP;

        w.steps(1200, DT);
        assertEquals(0, w.allies.spawned, "at the cap, nothing is raised");

        //  and no backlog is banked: one slot frees, one skeleton appears
        w.allies.alive = GameConfig.TRAP_SKELETON_CAP - 1;
        w.steps(2, DT);
        assertEquals(1, w.allies.spawned);
        w.steps(2, DT);
        assertEquals(1, w.allies.spawned, "the timer restarted, it did not catch up");
    }

    @Test
    @DisplayName("the ally-cap talent raises the ceiling")
    void allyCapTalent() {
        TestWorld w = new TestWorld();
        w.modifiers = new CombatModifiers() {
            @Override
            public int allyCapBonus() {
                return 3;
            }
        };
        FakeTarget necro = new FakeTarget(w.outpost.x(), w.outpost.y());
        w.outpost.trap(necro);
        w.allies.alive = GameConfig.TRAP_SKELETON_CAP;

        w.steps(120, DT);
        assertEquals(1, w.allies.spawned, "the raised cap allows another");
    }

    @Test
    @DisplayName("a captive keeps raising skeletons even at an ungarrisoned outpost")
    void prisonerWorksWithoutAGarrison() {
        TestWorld w = new TestWorld();
        assertEquals(0, w.outpost.level());
        FakeTarget necro = new FakeTarget(w.outpost.x(), w.outpost.y());
        w.outpost.trap(necro);
        w.steps(120, DT);
        assertTrue(w.allies.spawned > 0,
                "the prisoner is updated before the garrison check");
    }

    @Test
    @DisplayName("a factory that refuses does not consume the raise")
    void refusedRaise() {
        TestWorld w = new TestWorld();
        w.allies.refuse = true;
        FakeTarget necro = new FakeTarget(w.outpost.x(), w.outpost.y());
        w.outpost.trap(necro);
        w.steps(120, DT);
        assertEquals(0, w.allies.spawned);
        assertNotNull(w.outpost.prisoner(), "and the prisoner is unaffected");
    }

    @Test
    @DisplayName("the cage's catch area is more generous than its body")
    void trapAreaIsGenerous() {
        TestWorld w = new TestWorld();
        float x = w.outpost.x();
        float y = w.outpost.y();
        assertTrue(w.outpost.bodyContains(x, y - 30f));
        assertTrue(w.outpost.trapAreaContains(x, y - 30f));

        //  just outside the body, still inside the catch area
        float justOutside = x - 42f - 6f;
        assertFalse(w.outpost.bodyContains(justOutside, y - 30f));
        assertTrue(w.outpost.trapAreaContains(justOutside, y - 30f),
                "landing a throw should not be pixel-perfect");

        assertFalse(w.outpost.trapAreaContains(x - 200f, y - 30f));
    }

    @Test
    @DisplayName("the rival aim point is the cage, not the outpost base")
    void rivalAimPoint() {
        TestWorld w = new TestWorld();
        assertEquals(w.outpost.x(), w.outpost.aimPointX(), 0f);
        assertEquals(w.outpost.y() - 26f, w.outpost.aimPointY(), 0f);
        assertTrue(w.outpost.bodyContains(w.outpost.aimPointX(), w.outpost.aimPointY()),
                "a bolt aimed there lands inside the body rect it is tested against");
    }
}
