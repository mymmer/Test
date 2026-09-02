package com.mymmer.castledefense.defence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mymmer.castledefense.config.GameConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The order a hostile shot resolves against the player's structures.
 *
 * <pre>
 *   a rival's shot at the cage  (ignores everything else)
 *   otherwise:  Barricade → Tower → Castle wall/front → Keep
 * </pre>
 *
 * <p>These tests are built the hard way on purpose: each one puts a shot where
 * it <b>geometrically overlaps several structures at once</b> and proves the
 * Python priority decides. A test that only ever overlapped one thing would pass
 * under any ordering, which makes it worthless for exactly the property the
 * order exists to guarantee.
 */
class HostileResolutionTest {

    private static final float DT = 1f / 60f;

    /** A shot sitting still at a point, so only the resolution order can move it. */
    private static Projectile parked(TestWorld w, float x, float y, float splash) {
        Projectile p = new Projectile(w, x, y, 0f, 0f, ProjectileKind.MAGIC,
                50f, splash, 0, 0f, true, 5f, 0f, 1f, 1f, false, 0L);
        w.addProjectile(p);
        return p;
    }

    @Test
    @DisplayName("the barricade absorbs a shot that also overlaps a tower and the wall")
    void barricadeWinsOverEverything() {
        TestWorld w = new TestWorld();
        w.barricade.buy();
        //  Station a tower and then park a shot inside the barricade's band.
        DefenceTower tower = w.castle.addTower(TowerType.BOWMAN);
        assertNotNull(tower);

        float barHp = w.barricade.hp();
        float castleHp = w.castle.hp();
        float towerHp = tower.hp();

        //  Inside the barricade band AND left of the castle front AND below the
        //  wall top -- so tests 1, 3 and 4 would all fire on this point.
        float x = w.barricade.x();
        float y = w.barricade.topY() + 10f;
        assertTrue(x > w.castle.frontX(), "sanity: the barricade is outside the wall");
        Projectile p = parked(w, x, y, 0f);
        p.update(DT);

        assertTrue(w.barricade.hp() < barHp, "the barricade took it");
        assertEquals(castleHp, w.castle.hp(), 0.001f, "the castle did not");
        assertEquals(towerHp, tower.hp(), 0.001f);
        assertFalse(p.isAlive());
    }

    @Test
    @DisplayName("a tower absorbs a shot that also overlaps the wall behind it")
    void towerWinsOverTheWall() {
        TestWorld w = new TestWorld();
        DefenceTower tower = w.castle.addTower(TowerType.BOWMAN);
        assertNotNull(tower);
        //  The wall slots are at x=248, left of CASTLE_FRONT=252, and their y is
        //  below WALL_TOP -- so a point on the tower is also a point on the wall.
        float x = tower.x();
        float y = tower.y() - tower.height() / 2f;
        assertTrue(x <= w.castle.frontX(), "the tower overlaps the wall band");
        assertTrue(y >= GameConfig.WALL_TOP - 30f);

        float castleHp = w.castle.hp();
        float towerHp = tower.hp();
        Projectile p = parked(w, x, y, 0f);
        p.update(DT);

        assertTrue(tower.hp() < towerHp, "the tower took it");
        assertEquals(castleHp, w.castle.hp(), 0.001f, "the wall did not");
    }

    @Test
    @DisplayName("a downed tower is transparent: the shot carries on to the wall")
    void disabledTowersDoNotAbsorb() {
        TestWorld w = new TestWorld();
        DefenceTower tower = w.castle.addTower(TowerType.BOWMAN);
        assertNotNull(tower);
        while (!tower.disabled()) {
            tower.takeDamage(tower.maxHp());
        }

        float castleHp = w.castle.hp();
        Projectile p = parked(w, tower.x(), tower.y() - tower.height() / 2f, 0f);
        p.update(DT);

        assertTrue(w.castle.hp() < castleHp, "it went through the rubble into the wall");
    }

    @Test
    @DisplayName("the wall absorbs a shot that also overlaps the keep band")
    void wallWinsOverTheKeep() {
        TestWorld w = new TestWorld();
        //  Left of keepRight AND below KEEP_TOP, so the keep test would fire --
        //  but it is also left of frontX and below WALL_TOP, and the wall is first.
        float x = w.castle.keepRight() - 10f;
        float y = GameConfig.GROUND_Y - 50f;
        assertTrue(x <= w.castle.frontX());
        assertTrue(y >= GameConfig.KEEP_TOP - 26f);

        float castleHp = w.castle.hp();
        Projectile p = parked(w, x, y, 0f);
        p.update(DT);
        //  Both branches damage the castle, so the observable difference is that
        //  exactly ONE of them ran: the damage is a single hit, not two.
        assertEquals(1, hitCount(castleHp, w), "one structure resolved it, not two");
    }

    private static int hitCount(float before, TestWorld w) {
        float dealt = before - w.castle.hp();
        return dealt <= 0f ? 0 : Math.round(dealt / 50f);
    }

    @Test
    @DisplayName("a shot high above the wall passes over everything untouched")
    void nothingAbsorbsAShotOverTheTop() {
        TestWorld w = new TestWorld();
        w.barricade.buy();
        float castleHp = w.castle.hp();
        float barHp = w.barricade.hp();

        Projectile p = parked(w, 100f, GameConfig.KEEP_TOP - 100f, 0f);
        p.update(DT);

        assertEquals(castleHp, w.castle.hp(), 0.001f);
        assertEquals(barHp, w.barricade.hp(), 0.001f);
        assertTrue(p.isAlive(), "it is still in flight");
    }

    @Test
    @DisplayName("the keep catches a shot that cleared the wall band")
    void keepCatchesWhatTheWallMissed() {
        TestWorld w = new TestWorld();
        //  Above WALL_TOP - 30 (so the wall test fails) but below KEEP_TOP - 26.
        float y = GameConfig.KEEP_TOP - 10f;
        assertTrue(y < GameConfig.WALL_TOP - 30f, "sanity: the wall test must miss");
        assertTrue(y >= GameConfig.KEEP_TOP - 26f);

        float castleHp = w.castle.hp();
        Projectile p = parked(w, w.castle.keepRight() - 5f, y, 0f);
        p.update(DT);
        assertTrue(w.castle.hp() < castleHp, "the keep took it");
        assertFalse(p.isAlive());
    }

    @Test
    @DisplayName("a splash shot detonates on the structure it hits rather than damaging it directly")
    void splashDetonatesAtTheObstacle() {
        TestWorld w = new TestWorld();
        w.barricade.buy();
        float barHp = w.barricade.hp();
        float castleHp = w.castle.hp();

        //  A splashing shell at the barricade: the blast hits BOTH the barricade
        //  (by gap falloff) and, if it reaches, the wall -- which is the
        //  difference between explode() and a direct hit.
        Projectile p = parked(w, w.barricade.x(), w.barricade.topY() + 10f, 400f);
        p.update(DT);

        assertTrue(p.hasExploded());
        assertTrue(w.barricade.hp() < barHp, "the barricade is caught in its own blast");
        assertTrue(w.castle.hp() < castleHp,
                "and so is the wall, because the radius reaches it");
    }

    @Test
    @DisplayName("a rival's shot at the cage ignores every structure in the way")
    void prisonerShotIgnoresStructures() {
        TestWorld w = new TestWorld();
        w.barricade.buy();
        FakeTarget necromancer = new FakeTarget(w.outpost.x(), w.outpost.y());
        assertTrue(w.outpost.trap(necromancer));

        float prisonerHp = w.outpost.prisonerHp();
        float barHp = w.barricade.hp();

        //  Parked right inside the barricade band -- an ordinary hostile shot
        //  here would be absorbed by it.
        Projectile p = new Projectile(w, w.barricade.x(), w.barricade.topY() + 10f,
                0f, 0f, ProjectileKind.MAGIC, 40f, 0f, 0, 0f, true, 5f, 0f,
                1f, 1f, true, 0L);
        w.addProjectile(p);
        p.update(DT);

        assertEquals(barHp, w.barricade.hp(), 0.001f, "it flew straight through");
        assertEquals(prisonerHp, w.outpost.prisonerHp(), 0.001f, "and has not arrived yet");
        assertTrue(p.isAlive());

        //  now put it on the cage
        Projectile onCage = new Projectile(w, w.outpost.x(), w.outpost.y() - 30f,
                0f, 0f, ProjectileKind.MAGIC, 40f, 0f, 0, 0f, true, 5f, 0f,
                1f, 1f, true, 0L);
        w.addProjectile(onCage);
        onCage.update(DT);
        assertEquals(prisonerHp - 40f, w.outpost.prisonerHp(), 0.01f);
        assertFalse(onCage.isAlive());
    }

    @Test
    @DisplayName("a shot at a cage with nobody in it dies immediately")
    void prisonerShotWithNoPrisonerDies() {
        TestWorld w = new TestWorld();
        Projectile p = new Projectile(w, w.outpost.x(), w.outpost.y(), 0f, 0f,
                ProjectileKind.MAGIC, 40f, 0f, 0, 0f, true, 5f, 0f, 1f, 1f, true, 0L);
        w.addProjectile(p);
        p.update(DT);
        assertFalse(p.isAlive(), "nothing to shoot at");
    }

    @Test
    @DisplayName("a friendly shot ignores the player's own structures entirely")
    void friendlyShotsPassThroughOwnStructures() {
        TestWorld w = new TestWorld();
        w.barricade.buy();
        float barHp = w.barricade.hp();
        float castleHp = w.castle.hp();

        Projectile p = new Projectile(w, w.barricade.x(), w.barricade.topY() + 10f,
                0f, 0f, ProjectileKind.ARROW, 50f, 0f, 0, 0f, false, 5f, 0f,
                1f, 1f, false, 0L);
        w.addProjectile(p);
        p.update(DT);

        assertEquals(barHp, w.barricade.hp(), 0.001f);
        assertEquals(castleHp, w.castle.hp(), 0.001f);
        assertTrue(p.isAlive());
    }
}
