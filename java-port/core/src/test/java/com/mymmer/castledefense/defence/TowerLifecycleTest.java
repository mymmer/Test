package com.mymmer.castledefense.defence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A tower's health, downtime, cooldowns, upgrades and stun. */
class TowerLifecycleTest {

    private static final float DT = 1f / 60f;

    @Test
    @DisplayName("no single blow may take more than 42% of a tower's maximum")
    void perHitDamageCap() {
        TestWorld w = new TestWorld();
        DefenceTower t = w.table.createTower(w, TowerType.CANNON, 248f, 354f);
        float max = t.maxHp();

        t.takeDamage(max * 1000f);
        assertEquals(max - max * 0.42f, t.hp(), 0.01f,
                "a colossal blow is capped, not lethal");
        assertFalse(t.disabled(), "so a full-health tower always survives one hit");

        //  three capped hits do finish it: 0.42 * 3 > 1
        t.takeDamage(max * 1000f);
        assertFalse(t.disabled());
        t.takeDamage(max * 1000f);
        assertTrue(t.disabled(), "but it cannot survive for ever");
        assertEquals(0f, t.hp(), 0f);
    }

    @Test
    @DisplayName("a downed tower rebuilds after its countdown, at half health")
    void disableAndRebuild() {
        TestWorld w = new TestWorld();
        DefenceTower t = w.table.createTower(w, TowerType.BOWMAN, 248f, 354f);
        float max = t.maxHp();
        while (!t.disabled()) {
            t.takeDamage(max);
        }
        assertEquals(t.config().rebuildTime, t.rebuildRemaining(), 0.001f);

        //  a downed tower does not regenerate and does not fire
        w.add(FakeTarget.ground(400f));
        int steps = (int) (t.config().rebuildTime / DT) - 2;
        for (int i = 0; i < steps; i++) {
            t.update(DT);
        }
        assertTrue(t.disabled(), "still down just before the countdown ends");
        assertEquals(0f, t.hp(), 0f, "and it did not heal while down");
        assertEquals(0, w.projectiles.size(), "nor did it shoot");

        for (int i = 0; i < 4; i++) {
            t.update(DT);
        }
        assertFalse(t.disabled(), "back up");
        assertEquals(max * 0.5f, t.hp(), max * 0.02f,
                "and it comes back at half health, not full");
    }

    @Test
    @DisplayName("the rebuild talent shortens the countdown")
    void rebuildTalent() {
        TestWorld w = new TestWorld();
        w.modifiers = new CombatModifiers() {
            @Override
            public double rebuildMult() {
                return 0.5f;
            }
        };
        DefenceTower t = w.table.createTower(w, TowerType.BOWMAN, 248f, 354f);
        while (!t.disabled()) {
            t.takeDamage(t.maxHp());
        }
        assertEquals(t.config().rebuildTime * 0.5f, t.rebuildRemaining(), 0.001f);
    }

    @Test
    @DisplayName("a live tower regenerates 5% of its maximum per second")
    void regeneration() {
        TestWorld w = new TestWorld();
        DefenceTower t = w.table.createTower(w, TowerType.BOWMAN, 248f, 354f);
        t.takeDamage(t.maxHp() * 0.4f);
        float after = t.hp();
        for (int i = 0; i < 60; i++) {
            t.update(DT);
        }
        assertEquals(after + t.maxHp() * 0.05f, t.hp(), t.maxHp() * 0.005f);

        //  and it never overshoots
        for (int i = 0; i < 6000; i++) {
            t.update(DT);
        }
        assertEquals(t.maxHp(), t.hp(), 0.001f);
    }

    @Test
    @DisplayName("a stunned tower stops firing but keeps regenerating")
    void stun() {
        TestWorld w = new TestWorld();
        DefenceTower t = w.table.createTower(w, TowerType.BOWMAN, 248f, 354f);
        w.add(FakeTarget.ground(400f));
        t.setCooldown(0f);
        t.takeDamage(t.maxHp() * 0.3f);
        float hurt = t.hp();

        t.applyStun(0.5f);
        for (int i = 0; i < 20; i++) {
            t.update(DT);
        }
        assertEquals(0, w.projectiles.size(), "a stunned tower does not fire");

        //  regeneration DOES still run: Python regenerates before the stun check
        assertTrue(t.hp() > hurt, "regeneration happens before the stun check");

        for (int i = 0; i < 20; i++) {
            t.update(DT);
        }
        assertEquals(0f, t.stun(), 0.02f);
        t.update(DT);
        assertTrue(w.projectiles.size() > 0, "and it fires once the stun ends");
    }

    @Test
    @DisplayName("the longer stun wins")
    void stunTakesTheMaximum() {
        TestWorld w = new TestWorld();
        DefenceTower t = w.table.createTower(w, TowerType.BOWMAN, 248f, 354f);
        t.applyStun(2f);
        t.applyStun(0.5f);
        assertEquals(2f, t.stun(), 0f);
        t.applyStun(3f);
        assertEquals(3f, t.stun(), 0f);
        t.applyStun(-1f);
        assertEquals(3f, t.stun(), 0f, "a negative stun is ignored");
    }

    @Test
    @DisplayName("upgrading improves every stat by its Python factor")
    void upgrade() {
        TestWorld w = new TestWorld();
        DefenceTower t = w.table.createTower(w, TowerType.CANNON, 248f, 354f);
        float d = t.damage();
        double r = t.reload();
        float range = t.range();
        float splash = t.splash();
        float hp = t.maxHp();

        assertEquals(2, t.upgrade());
        assertEquals(d * 1.35f, t.damage(), 0.01f);
        assertEquals(r * 0.90f, t.reload(), 0.001f);
        assertEquals(range * 1.04f, t.range(), 0.01f);
        assertEquals(splash * 1.08f, t.splash(), 0.01f);
        assertEquals(hp * 1.15f, t.maxHp(), 0.01f);
        assertEquals(t.maxHp(), t.hp(), 0.001f, "and it is fully repaired");
    }

    @Test
    @DisplayName("a tower without splash does not gain any on upgrade")
    void upgradeDoesNotInventSplash() {
        TestWorld w = new TestWorld();
        DefenceTower bow = w.table.createTower(w, TowerType.BOWMAN, 248f, 354f);
        assertEquals(0f, bow.splash(), 0f);
        bow.upgrade();
        assertEquals(0f, bow.splash(), 0f);
    }

    @Test
    @DisplayName("the reload is the config cooldown, scaled by the rate talent")
    void firingCadence() {
        TestWorld w = new TestWorld();
        w.modifiers = new CombatModifiers() {
            @Override
            public double towerRate() {
                return 0.5f;
            }
        };
        DefenceTower t = w.table.createTower(w, TowerType.BOWMAN, 248f, 354f);
        w.add(FakeTarget.ground(400f));
        t.setCooldown(0f);
        t.update(DT);
        assertEquals(1, w.projectiles.size());
        assertEquals(t.reload() * 0.5f, t.cooldown(), 0.001f);
    }

    @Test
    @DisplayName("the initial cooldown jitter comes from the seeded stream")
    void seededCooldownJitter() {
        double a = w(4242L);
        double b = w(4242L);
        double c = w(99L);
        assertEquals(a, b, 0d, "same seed, same stagger");
        assertTrue(a != c, "a different seed staggers differently");
        assertTrue(a >= 0d && a <= 0.4d, "and it is within Python's 0..0.4 window");
    }

    private static void assertNotEquals(float a, float b) {
        assertFalse(a == b, "expected different values, both were " + a);
    }

    private static double w(long seed) {
        TestWorld world = new TestWorld(seed);
        return world.table.createTower(world, TowerType.BOWMAN, 248f, 354f).cooldown();
    }

    @Test
    @DisplayName("restore brings a downed tower fully back")
    void restore() {
        TestWorld w = new TestWorld();
        DefenceTower t = w.table.createTower(w, TowerType.BOWMAN, 248f, 354f);
        while (!t.disabled()) {
            t.takeDamage(t.maxHp());
        }
        t.applyStun(5f);
        t.restore();
        assertFalse(t.disabled());
        assertEquals(t.maxHp(), t.hp(), 0f);
        assertEquals(0f, t.stun(), 0f);
        assertEquals(0f, t.rebuildRemaining(), 0f);
    }

    @Test
    @DisplayName("a damaged tower ignores further hits once it is down")
    void disabledTowersTakeNoDamage() {
        TestWorld w = new TestWorld();
        DefenceTower t = w.table.createTower(w, TowerType.BOWMAN, 248f, 354f);
        while (!t.disabled()) {
            t.takeDamage(t.maxHp());
        }
        double rebuild = t.rebuildRemaining();
        t.takeDamage(9999f);
        assertEquals(rebuild, t.rebuildRemaining(), 0f,
                "hitting rubble must not restart the countdown");
    }

    @Test
    @DisplayName("hit testing uses pygame's truncated, half-open rectangle")
    void hitTestingMatchesPygame() {
        TestWorld w = new TestWorld();
        DefenceTower t = w.table.createTower(w, TowerType.BOWMAN, 248f, 354f);
        int left = (int) (t.x() - t.width() / 2f);
        int top = (int) (t.y() - t.height());
        int right = left + (int) t.width();
        int bottom = top + (int) t.height();

        assertTrue(t.contains(left, top), "the top-left corner is inside");
        assertTrue(t.contains(right - 0.5f, bottom - 0.5f), "just inside the far corner");
        assertFalse(t.contains(right, top), "the right edge is NOT inside (half-open)");
        assertFalse(t.contains(left, bottom), "nor is the bottom edge");
        assertFalse(t.contains(left - 0.5f, top), "nor anything left of it");
    }

    @Test
    @DisplayName("aim tracks the target but is never read back by gameplay")
    void aimIsVisualState() {
        TestWorld w = new TestWorld();
        DefenceTower t = w.table.createTower(w, TowerType.BOWMAN, 248f, 354f);
        float initial = t.aim();
        FakeTarget e = w.add(FakeTarget.ground(500f));
        t.update(DT);
        assertNotEquals(initial, t.aim());

        //  Whether it has finished slewing makes no difference to firing: a
        //  tower shoots the moment its cooldown expires, aim or no aim.
        t.setCooldown(0f);
        t.update(DT);
        assertEquals(1, w.projectiles.size());
        assertNotNull(e);
    }

    @Test
    @DisplayName("recoil is set on firing and decays at 5 per second")
    void recoilIsVisualState() {
        TestWorld w = new TestWorld();
        DefenceTower t = w.table.createTower(w, TowerType.BOWMAN, 248f, 354f);
        w.add(FakeTarget.ground(400f));
        t.setCooldown(0f);
        t.update(DT);
        assertEquals(1f, t.recoil(), 0f);
        for (int i = 0; i < 6; i++) {
            t.update(DT);
        }
        assertEquals(1f - 6 * DT * 5f, t.recoil(), 0.01f);
    }

    @Test
    @DisplayName("towers of each type are built from their own config")
    void factoryBuildsTheRightClasses() {
        TestWorld w = new TestWorld();
        assertTrue(w.table.createTower(w, TowerType.BOWMAN, 0f, 0f) instanceof Bowman);
        assertTrue(w.table.createTower(w, TowerType.BALLISTA, 0f, 0f) instanceof Ballista);
        assertTrue(w.table.createTower(w, TowerType.CANNON, 0f, 0f) instanceof Cannon);
        assertNotSame(w.table.tower(TowerType.BOWMAN), w.table.tower(TowerType.CANNON));
        assertNull(TowerType.byId("trebuchet", null));
        assertSame(TowerType.CANNON, TowerType.byId("cannon", null));
    }
}
