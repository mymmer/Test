package com.mymmer.castledefense.defence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The castle: tiers, reinforcement, slots, damage and the boss entry points.
 *
 * <p>The "infinite progression" block is ported from the Python self-test, which
 * asserts that Reinforce Walls never refuses, that health keeps growing past the
 * last visual tier, and that the label picks up a {@code +N}.
 */
class CastleTest {

    private static final float DT = 1f / 60f;

    @Test
    @DisplayName("the castle starts on the first tier at its full health")
    void startingState() {
        TestWorld w = new TestWorld();
        assertEquals(1, w.castle.wallLevel());
        assertEquals(400f, w.castle.maxHp(), 0f, "Wooden Palisade");
        assertEquals(w.castle.maxHp(), w.castle.hp(), 0f);
        assertEquals("Wooden Palisade", w.castle.tierLabel());
        assertFalse(w.castle.visualCapped());
    }

    @Test
    @DisplayName("reinforcing walks the tiers and raises current health with the maximum")
    void reinforcementWalksTheTiers() {
        TestWorld w = new TestWorld();
        w.castle.takeDamage(200f);
        float before = w.castle.hp();

        w.castle.upgradeWall();
        assertEquals(2, w.castle.wallLevel());
        assertEquals(620f, w.castle.maxHp(), 0f, "Stone Keep");
        assertEquals(before + 220f, w.castle.hp(), 0.01f,
                "the gain is added to current health too -- reinforcing heals");
        assertEquals("Stone Keep", w.castle.tierLabel());
    }

    @Test
    @DisplayName("reinforcement never refuses, and keeps adding health past the last tier")
    void infiniteProgression() {
        TestWorld w = new TestWorld();
        int tiers = w.castle.maxVisualLevel();
        for (int i = 0; i < tiers - 1; i++) {
            assertTrue(w.castle.upgradeWall());
        }
        assertEquals(tiers, w.castle.wallLevel());
        assertFalse(w.castle.visualCapped());
        float hpAtCap = w.castle.maxHp();
        String lookAtCap = w.castle.tier().id;

        for (int i = 0; i < 6; i++) {
            assertTrue(w.castle.upgradeWall(), "Reinforce Walls must never refuse");
        }
        assertEquals(tiers + 6, w.castle.wallLevel());
        assertTrue(w.castle.maxHp() > hpAtCap, "extra levels must keep adding health");
        assertTrue(w.castle.visualCapped());
        assertTrue(w.castle.tierLabel().contains("+6"), w.castle.tierLabel());
        assertEquals(tiers - 1, w.castle.tierIndex(), "the look stays on the last tier");
        assertEquals(lookAtCap, w.castle.tier().id, "and so does the tier it names");
    }

    @Test
    @DisplayName("the endless health steps compound")
    void endlessStepsCompound() {
        TestWorld w = new TestWorld();
        int tiers = w.castle.maxVisualLevel();
        for (int i = 0; i < tiers - 1; i++) {
            w.castle.upgradeWall();
        }
        float base = w.castle.maxHp();
        w.castle.upgradeWall();
        float first = w.castle.maxHp() - base;
        assertEquals(Castle.ENDLESS_WALL_STEP, first, 0.01f);

        float second = w.castle.maxHp();
        w.castle.upgradeWall();
        assertEquals(Castle.ENDLESS_WALL_STEP * Castle.ENDLESS_WALL_GROWTH,
                w.castle.maxHp() - second, 0.01f);
    }

    @Test
    @DisplayName("reinforcing also toughens every platform already built")
    void reinforcingToughensTowers() {
        TestWorld w = new TestWorld();
        DefenceTower t = w.castle.addTower(TowerType.BOWMAN);
        assertNotNull(t);
        float before = t.maxHp();
        w.castle.upgradeWall();
        assertEquals(before * 1.22f, t.maxHp(), 0.01f);
    }

    @Test
    @DisplayName("slot capacity is 4 + wall level, capped at the number of slots")
    void slotCapacity() {
        TestWorld w = new TestWorld();
        assertEquals(5, w.castle.slotCapacity());
        w.castle.upgradeWall();
        assertEquals(6, w.castle.slotCapacity());
        for (int i = 0; i < 20; i++) {
            w.castle.upgradeWall();
        }
        assertEquals(w.table.slotCount(), w.castle.slotCapacity(), "capped at the slot list");
    }

    @Test
    @DisplayName("towers fill the wall slots first, front to back")
    void wallSlotsFillFirst() {
        TestWorld w = new TestWorld();
        for (int i = 0; i < 20; i++) {
            w.castle.upgradeWall();       // unlock every slot
        }
        DefenceTower first = w.castle.addTower(TowerType.BOWMAN);
        DefenceTower second = w.castle.addTower(TowerType.BOWMAN);
        DefenceTower third = w.castle.addTower(TowerType.BOWMAN);
        assertNotNull(first);
        assertEquals(248f, first.x(), 0f, "the frontmost wall slot");
        assertEquals(228f, second.x(), 0f, "then the corner turret, which is next by x");
        assertEquals(210f, third.x(), 0f);
    }

    @Test
    @DisplayName("addTower returns null once every unlocked slot is taken")
    void slotsRunOut() {
        TestWorld w = new TestWorld();
        int capacity = w.castle.slotCapacity();
        for (int i = 0; i < capacity; i++) {
            assertNotNull(w.castle.addTower(TowerType.BOWMAN), "slot " + i);
        }
        assertNull(w.castle.addTower(TowerType.BOWMAN), "no wall space left");
        assertEquals(capacity, w.castle.towerCount());
    }

    @Test
    @DisplayName("a new tower is toughened by the wall level and the tower-HP talent")
    void newTowersScaleWithTheWall() {
        TestWorld w = new TestWorld();
        w.modifiers = new CombatModifiers() {
            @Override
            public float towerHp() {
                return 2f;
            }
        };
        float base = w.table.tower(TowerType.BOWMAN).maxHp;
        DefenceTower atLevelOne = w.castle.addTower(TowerType.BOWMAN);
        assertEquals(base * 2f, atLevelOne.maxHp(), 0.01f, "1.22^0 * 2.0");

        w.castle.upgradeWall();
        DefenceTower atLevelTwo = w.castle.addTower(TowerType.BOWMAN);
        assertEquals(base * 1.22f * 2f, atLevelTwo.maxHp(), 0.01f);
        assertEquals(atLevelTwo.maxHp(), atLevelTwo.hp(), 0.001f, "and it starts full");
    }

    @Test
    @DisplayName("damage is reduced by the thorns talent and reported once")
    void damageAndTalent() {
        TestWorld w = new TestWorld();
        w.modifiers = new CombatModifiers() {
            @Override
            public float damageTaken() {
                return 0.5f;
            }
        };
        float hp = w.castle.hp();
        w.castle.takeDamage(100f);
        assertEquals(hp - 50f, w.castle.hp(), 0.01f);
    }

    @Test
    @DisplayName("the castle is destroyed exactly once")
    void destructionFiresOnce() {
        TestWorld w = new TestWorld();
        w.castle.takeDamage(w.castle.maxHp() * 2f);
        assertEquals(0f, w.castle.hp(), 0f);
        assertEquals(1, w.castleDestroyedCount);

        w.castle.takeDamage(500f);
        assertEquals(1, w.castleDestroyedCount, "a dead castle absorbs nothing further");
    }

    @Test
    @DisplayName("splash near the wall falls off with the horizontal gap")
    void splashFalloffAcrossTheWall() {
        TestWorld w = new TestWorld();
        float radius = 100f;

        float hp = w.castle.hp();
        w.castle.splashHit(w.castle.frontX(), 400f, radius, 100f, 0f);
        assertEquals(hp - 100f, w.castle.hp(), 0.01f, "right on the face: full damage");

        hp = w.castle.hp();
        w.castle.splashHit(w.castle.frontX() + radius, 400f, radius, 100f, 0f);
        assertEquals(hp - 50f, w.castle.hp(), 0.01f, "at the rim: half");

        hp = w.castle.hp();
        w.castle.splashHit(w.castle.frontX() - 500f, 400f, radius, 100f, 0f);
        assertEquals(hp - 100f, w.castle.hp(), 0.01f,
                "behind the wall the gap clamps to zero, so it is full damage");

        hp = w.castle.hp();
        w.castle.splashHit(w.castle.frontX() + radius * 2f, 400f, radius, 100f, 0f);
        assertEquals(hp, w.castle.hp(), 0.001f, "out of range: nothing");
    }

    @Test
    @DisplayName("splash hits towers for half, and stuns them")
    void splashHitsTowers() {
        TestWorld w = new TestWorld();
        DefenceTower t = w.castle.addTower(TowerType.BOWMAN);
        float hp = t.hp();
        w.castle.splashHit(t.x(), t.y() - t.height() / 2f, 60f, 40f, 1.5f);
        assertEquals(hp - 20f, t.hp(), 0.01f, "towers take half the splash damage");
        assertEquals(1.5f, t.stun(), 0f);
    }

    @Test
    @DisplayName("splash ignores towers that are already down")
    void splashIgnoresDownedTowers() {
        TestWorld w = new TestWorld();
        DefenceTower t = w.castle.addTower(TowerType.BOWMAN);
        while (!t.disabled()) {
            t.takeDamage(t.maxHp());
        }
        double rebuild = t.rebuildRemaining();
        w.castle.splashHit(t.x(), t.y() - t.height() / 2f, 60f, 40f, 2f);
        assertEquals(0f, t.stun(), 0f, "rubble cannot be stunned");
        assertEquals(rebuild, t.rebuildRemaining(), 0f);
    }

    @Test
    @DisplayName("a boss smash picks a live tower from the seeded stream")
    void smashRandomTower() {
        TestWorld a = new TestWorld(777L);
        TestWorld b = new TestWorld(777L);
        for (int i = 0; i < 4; i++) {
            a.castle.addTower(TowerType.BOWMAN);
            b.castle.addTower(TowerType.BOWMAN);
        }
        DefenceTower hitA = a.castle.smashRandomTower(30f, 1f);
        DefenceTower hitB = b.castle.smashRandomTower(30f, 1f);
        assertNotNull(hitA);
        assertEquals(a.castle.towers().indexOf(hitA, true),
                b.castle.towers().indexOf(hitB, true),
                "the same seed smashes the same tower");
        assertEquals(1f, hitA.stun(), 0f);
        assertTrue(hitA.hp() < hitA.maxHp());
    }

    @Test
    @DisplayName("a smash with no live towers is a no-op")
    void smashWithNothingStanding() {
        TestWorld w = new TestWorld();
        assertNull(w.castle.smashRandomTower(30f, 1f), "nothing built");

        DefenceTower t = w.castle.addTower(TowerType.BOWMAN);
        while (!t.disabled()) {
            t.takeDamage(t.maxHp());
        }
        assertNull(w.castle.smashRandomTower(30f, 1f), "nothing standing");
    }

    @Test
    @DisplayName("repair heals a fraction and never overshoots")
    void repair() {
        TestWorld w = new TestWorld();
        w.castle.takeDamage(w.castle.maxHp() * 0.9f);
        float healed = w.castle.repair(0.35f);
        assertEquals(w.castle.maxHp() * 0.35f, healed, 0.01f);

        assertEquals(w.castle.maxHp() - w.castle.hp(), w.castle.repair(1f), 0.01f,
                "a full repair heals exactly what was missing");
        assertEquals(w.castle.maxHp(), w.castle.hp(), 0.001f);
        assertEquals(0f, w.castle.repair(), 0.001f, "and healing a full castle does nothing");
    }

    @Test
    @DisplayName("restoreTowers brings every platform back")
    void restoreTowers() {
        TestWorld w = new TestWorld();
        DefenceTower a = w.castle.addTower(TowerType.BOWMAN);
        DefenceTower b = w.castle.addTower(TowerType.BALLISTA);
        while (!a.disabled()) {
            a.takeDamage(a.maxHp());
        }
        b.takeDamage(b.maxHp() * 0.3f);

        w.castle.restoreTowers();
        assertFalse(a.disabled());
        assertEquals(a.maxHp(), a.hp(), 0f);
        assertEquals(b.maxHp(), b.hp(), 0f);
    }

    @Test
    @DisplayName("towerAt finds the first live tower under a point")
    void towerLookup() {
        TestWorld w = new TestWorld();
        DefenceTower t = w.castle.addTower(TowerType.BOWMAN);
        assertSame(t, w.castle.towerAt(t.x(), t.y() - 1f));
        assertNull(w.castle.towerAt(t.x() + 500f, t.y() - 1f));
        assertNull(w.castle.towerAt(t.x(), t.y() - t.height() - 20f), "above the tower");
    }

    @Test
    @DisplayName("the flash decays and is never read by gameplay")
    void flashIsVisualState() {
        TestWorld w = new TestWorld();
        w.castle.takeDamage(w.castle.maxHp() * 0.5f);
        assertEquals(1f, w.castle.flash(), 0f, "a big hit saturates the flash");
        float hp = w.castle.hp();
        for (int i = 0; i < 20; i++) {
            w.castle.update(DT);
        }
        assertTrue(w.castle.flash() < 1f);
        assertEquals(hp, w.castle.hp(), 0.001f, "and it changes nothing");
    }

    @Test
    @DisplayName("counting towers by type is exact")
    void countByType() {
        TestWorld w = new TestWorld();
        w.castle.addTower(TowerType.BOWMAN);
        w.castle.addTower(TowerType.BOWMAN);
        w.castle.addTower(TowerType.CANNON);
        assertEquals(2, w.castle.countOf(TowerType.BOWMAN));
        assertEquals(1, w.castle.countOf(TowerType.CANNON));
        assertEquals(0, w.castle.countOf(TowerType.BALLISTA));
    }
}
