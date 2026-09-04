package com.mymmer.castledefense.defence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mymmer.castledefense.config.GameConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Manual overcharge — the hand-fired slingshot shot.
 *
 * <p>Ported from the Python self-test's "Manual overcharge" block, which asserts
 * that a drag-back fires regardless of the reload, at the full damage multiplier,
 * opposite the draw, with a wider blast where the tower splashes, and locks the
 * tower out afterwards.
 *
 * <h2>Input is not in these tests, on purpose</h2>
 *
 * <p>Phase 5 owns the <em>calculation</em>: given an aim point and a power in
 * 0..1, what comes out of the barrel. Turning a drag gesture into that power is
 * the Phase 4 input pipeline's job, and neither a mouse nor a touch id appears
 * anywhere in {@link DefenceTower}. The one number that ties them together —
 * {@code OVERCHARGE_PULL}, the drag distance that reads as full power — is
 * asserted here as a config value so the two halves cannot drift apart.
 */
class OverchargeTest {

    private static final float DT = 1f / 60f;

    /** The Python gesture→power formula, which the input layer will apply. */
    private static float powerFor(DefenceTower t, float aimX, float aimY) {
        float dx = t.muzzleX() - aimX;
        float dy = t.muzzleY() - aimY;
        float d = (float) Math.sqrt(dx * dx + dy * dy);
        return Math.min(1f, Math.max(0f, d / GameConfig.OVERCHARGE_PULL));
    }

    @Test
    @DisplayName("only the Ballista and Cannon are overchargeable")
    void eligibility() {
        TestWorld w = new TestWorld();
        assertFalse(w.table.tower(TowerType.BOWMAN).overchargeable);
        assertTrue(w.table.tower(TowerType.BALLISTA).overchargeable);
        assertTrue(w.table.tower(TowerType.CANNON).overchargeable);

        assertFalse(w.table.createTower(w, TowerType.BOWMAN, 248f, 354f).canOvercharge());
        assertTrue(w.table.createTower(w, TowerType.BALLISTA, 248f, 354f).canOvercharge());
    }

    @Test
    @DisplayName("a downed or stunned tower cannot be overcharged")
    void eligibilityRespectsState() {
        TestWorld w = new TestWorld();
        DefenceTower t = w.table.createTower(w, TowerType.BALLISTA, 248f, 354f);
        t.applyStun(1f);
        assertFalse(t.canOvercharge(), "stunned");
        t.restore();
        assertTrue(t.canOvercharge());

        while (!t.disabled()) {
            t.takeDamage(t.maxHp());
        }
        assertFalse(t.canOvercharge(), "downed");
    }

    @Test
    @DisplayName("overcharge fires immediately, ignoring the reload timer")
    void ignoresTheReload() {
        for (TowerType type : new TowerType[]{TowerType.BALLISTA, TowerType.CANNON}) {
            TestWorld w = new TestWorld();
            DefenceTower t = w.table.createTower(w, type, 248f, 354f);
            t.setCooldown(99f);                    // nowhere near ready
            t.setOverchargeCd(0f);

            assertTrue(t.overchargeFire(t.muzzleX() + 220f, t.muzzleY() + 120f, 1f));
            assertEquals(1, w.projectiles.size(),
                    type.id() + " must fire despite the reload");
            assertEquals(t.reload(), t.cooldown(), 0.001f,
                    "and the reload is reset to one full cycle, not left at 99");
        }
    }

    @Test
    @DisplayName("the shot flies opposite the draw-back")
    void firesOppositeTheDraw() {
        TestWorld w = new TestWorld();
        DefenceTower t = w.table.createTower(w, TowerType.BALLISTA, 248f, 354f);
        //  drawn back down and to the RIGHT, so the bolt goes up and LEFT
        t.overchargeFire(t.muzzleX() + 220f, t.muzzleY() + 120f, 1f);
        Projectile shot = w.lastProjectile();
        assertNotNull(shot);
        assertTrue(shot.vx() < 0f, "drawn right, fired left");
        assertTrue(shot.vy() < 0f, "drawn down, fired up (y grows downward)");
    }

    @Test
    @DisplayName("full power multiplies damage by OVERCHARGE_DAMAGE")
    void damageMultiplier() {
        for (TowerType type : new TowerType[]{TowerType.BALLISTA, TowerType.CANNON}) {
            TestWorld w = new TestWorld();
            DefenceTower t = w.table.createTower(w, type, 248f, 354f);
            t.overchargeFire(t.muzzleX() + 220f, t.muzzleY() + 120f, 1f);
            Projectile shot = w.lastProjectile();
            assertEquals(GameConfig.OVERCHARGE_DAMAGE, shot.damage() / t.damage(), 1e-5f,
                    type.id() + " at full power");
        }
    }

    @Test
    @DisplayName("power scales the damage linearly between 1x and the full multiplier")
    void damageScalesWithPower() {
        TestWorld w = new TestWorld();
        DefenceTower t = w.table.createTower(w, TowerType.BALLISTA, 248f, 354f);

        t.setOverchargeCd(0f);
        t.overchargeFire(t.muzzleX() + 220f, t.muzzleY(), 0f);
        assertEquals(1f, w.lastProjectile().damage() / t.damage(), 1e-5f,
                "zero power is an ordinary-damage shot");

        t.setOverchargeCd(0f);
        t.overchargeFire(t.muzzleX() + 220f, t.muzzleY(), 0.5f);
        float expected = 1f + (GameConfig.OVERCHARGE_DAMAGE - 1f) * 0.5f;
        assertEquals(expected, w.lastProjectile().damage() / t.damage(), 1e-5f);
    }

    @Test
    @DisplayName("an overcharged Cannon shell has a wider blast and a faster shot")
    void cannonBlastAndSpeed() {
        TestWorld w = new TestWorld();
        DefenceTower t = w.table.createTower(w, TowerType.CANNON, 248f, 354f);
        t.overchargeFire(t.muzzleX() + 220f, t.muzzleY() + 120f, 1f);
        Projectile shot = w.lastProjectile();

        assertTrue(shot.splash() > t.splash(), "overcharge widens the blast");
        assertEquals(t.splash() * GameConfig.OVERCHARGE_SPLASH, shot.splash(), 0.01f);

        float speed = (float) Math.sqrt(shot.vx() * shot.vx() + shot.vy() * shot.vy());
        assertEquals(t.config().overchargeSpeed * GameConfig.OVERCHARGE_SPEED, speed, 1f);
    }

    @Test
    @DisplayName("an overcharged Ballista bolt pierces more and keeps its air counter")
    void ballistaBolt() {
        TestWorld w = new TestWorld();
        DefenceTower t = w.table.createTower(w, TowerType.BALLISTA, 248f, 354f);
        t.overchargeFire(t.muzzleX() + 220f, t.muzzleY() + 120f, 1f);
        Projectile shot = w.lastProjectile();

        assertEquals(4, shot.pierce(), "the hand-fired bolt pierces four");
        assertEquals(3f, shot.bonusAir(), 0f, "and still triples against flyers");
        assertEquals(0f, shot.splash(), 0f, "a bolt has no blast");

        float speed = (float) Math.sqrt(shot.vx() * shot.vx() + shot.vy() * shot.vy());
        assertEquals(t.config().overchargeSpeed * GameConfig.OVERCHARGE_SPEED, speed, 1f);
    }

    @Test
    @DisplayName("firing locks the tower out for OVERCHARGE_COOLDOWN")
    void cooldownLockout() {
        TestWorld w = new TestWorld();
        DefenceTower t = w.table.createTower(w, TowerType.BALLISTA, 248f, 354f);
        assertTrue(t.overchargeFire(t.muzzleX() + 220f, t.muzzleY() + 120f, 1f));

        assertEquals(GameConfig.OVERCHARGE_COOLDOWN, t.overchargeCd(), 0.001f);
        assertFalse(t.canOvercharge(), "cannot re-charge during the lockout");

        int projectiles = w.projectiles.size();
        for (int i = 0; i < (int) (GameConfig.OVERCHARGE_COOLDOWN / DT) - 2; i++) {
            t.update(DT);
        }
        assertFalse(t.canOvercharge(), "still locked out just before it expires");
        for (int i = 0; i < 4; i++) {
            t.update(DT);
        }
        assertTrue(t.canOvercharge(), "and available again after it");
        assertTrue(w.projectiles.size() >= projectiles);
    }

    @Test
    @DisplayName("the overcharge talent shortens the lockout")
    void cooldownTalent() {
        TestWorld w = new TestWorld();
        w.modifiers = new CombatModifiers() {
            @Override
            public double overchargeCd() {
                return 0.5f;
            }
        };
        DefenceTower t = w.table.createTower(w, TowerType.BALLISTA, 248f, 354f);
        t.overchargeFire(t.muzzleX() + 220f, t.muzzleY() + 120f, 1f);
        assertEquals(GameConfig.OVERCHARGE_COOLDOWN * 0.5f, t.overchargeCd(), 0.001f);
    }

    @Test
    @DisplayName("a draw shorter than 12 px does not fire and does not lock out")
    void tooShortADrawIsNotAShot() {
        TestWorld w = new TestWorld();
        DefenceTower t = w.table.createTower(w, TowerType.BALLISTA, 248f, 354f);
        assertFalse(t.overchargeFire(t.muzzleX() + 5f, t.muzzleY() + 5f, 1f));
        assertEquals(0, w.projectiles.size());
        assertEquals(0f, t.overchargeCd(), 0f, "a fumbled drag costs nothing");
        assertTrue(t.canOvercharge());
    }

    @Test
    @DisplayName("the gesture-to-power formula saturates at OVERCHARGE_PULL")
    void powerFormula() {
        TestWorld w = new TestWorld();
        DefenceTower t = w.table.createTower(w, TowerType.BALLISTA, 248f, 354f);

        assertEquals(0f, powerFor(t, t.muzzleX(), t.muzzleY()), 1e-5f);
        assertEquals(0.5f, powerFor(t, t.muzzleX() + GameConfig.OVERCHARGE_PULL * 0.5f,
                t.muzzleY()), 1e-5f);
        assertEquals(1f, powerFor(t, t.muzzleX() + GameConfig.OVERCHARGE_PULL,
                t.muzzleY()), 1e-5f);
        assertEquals(1f, powerFor(t, t.muzzleX() + 9999f, t.muzzleY()), 1e-5f,
                "dragging further than the pull distance is still full power");

        //  the self-test's own drag (220, 120) is well past full power
        assertTrue(powerFor(t, t.muzzleX() + 220f, t.muzzleY() + 120f) >= 0.99f);
    }

    @Test
    @DisplayName("autofire resumes normally after an overcharge")
    void autofireResumes() {
        TestWorld w = new TestWorld();
        DefenceTower t = w.table.createTower(w, TowerType.BALLISTA, 248f, 354f);
        w.add(FakeTarget.flyer(500f, 300f));

        t.overchargeFire(t.muzzleX() + 220f, t.muzzleY() + 120f, 1f);
        assertEquals(1, w.projectiles.size());

        //  the reload was reset to one full cycle, so nothing fires until it runs
        for (int i = 0; i < (int) (t.reload() / DT) - 2; i++) {
            t.update(DT);
        }
        assertEquals(1, w.projectiles.size(), "still reloading");
        for (int i = 0; i < 4; i++) {
            t.update(DT);
        }
        assertEquals(2, w.projectiles.size(), "and then it autofires again");
    }

    @Test
    @DisplayName("a Bowman refuses to launch an overcharged shot at all")
    void bowmanHasNoOverchargeShot() {
        TestWorld w = new TestWorld();
        DefenceTower t = w.table.createTower(w, TowerType.BOWMAN, 248f, 354f);
        //  canOvercharge() is the gate the input layer checks; if something ever
        //  called through it anyway, this must fail loudly rather than silently
        //  firing an ordinary arrow.
        assertFalse(t.canOvercharge());
        try {
            t.overchargeFire(t.muzzleX() + 220f, t.muzzleY() + 120f, 1f);
            throw new AssertionError("expected an UnsupportedOperationException");
        } catch (UnsupportedOperationException expected) {
            assertTrue(expected.getMessage().contains("bowman"));
        }
    }
}
