package com.mymmer.castledefense.defence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mymmer.castledefense.debug.RecordingSimulationTrace;
import com.mymmer.castledefense.debug.TraceEvent;
import com.mymmer.castledefense.entity.EntityList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The whole defence stack running together over many fixed steps.
 *
 * <p>The unit tests above pin individual formulas; these prove the pieces still
 * behave when they are stepped in the game-loop order — and, crucially, that a
 * seeded run is <b>reproducible</b>, which is the property the whole
 * seed/trace architecture exists to provide.
 */
class DefenceIntegrationTest {

    private static final float DT = 1f / 60f;

    @Test
    @DisplayName("a defended castle kills what walks into range")
    void towersKillOverTime() {
        TestWorld w = new TestWorld();
        w.castle.addTower(TowerType.BOWMAN);
        w.castle.addTower(TowerType.BALLISTA);

        FakeTarget ground = w.add(FakeTarget.ground(500f));
        ground.hp = 300f;
        FakeTarget flyer = w.add(FakeTarget.flyer(520f, 300f));
        flyer.hp = 300f;

        w.steps(600, DT);

        assertFalse(flyer.alive, "the ballista shreds flyers");
        assertTrue(ground.totalDamage() > 0f, "and the bowmen work on the ground unit");
        assertEquals(0, w.liveProjectiles(),
                "every shot resolved; none are left hanging");
    }

    @Test
    @DisplayName("two runs on the same seed are identical, step for step")
    void seededRunsAreReproducible() {
        String a = runDigest(31337L);
        String b = runDigest(31337L);
        String c = runDigest(31338L);
        assertEquals(a, b, "the same seed must produce the same run");
        assertFalse(a.equals(c),
                "and a different seed a different one, or the seed is not being used");
    }

    /** Runs a fixed scenario and boils it down to one comparable string. */
    private static String runDigest(long seed) {
        TestWorld w = new TestWorld(seed);
        w.modifiers = new CombatModifiers() {
            @Override
            public float critChance() {
                return 0.25f;      // crits are the other RNG consumer
            }
        };
        w.castle.addTower(TowerType.BOWMAN);
        w.castle.addTower(TowerType.CANNON);
        for (int i = 0; i < 6; i++) {
            FakeTarget t = w.add(FakeTarget.ground(420f + i * 25f));
            t.hp = 400f;
        }
        w.add(FakeTarget.heavy(600f)).hp = 900f;
        w.steps(900, DT);

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < w.targetCount(); i++) {
            Target t = w.target(i);
            sb.append((int) t.hp()).append(t.alive() ? "a" : "d").append('/');
        }
        return sb.toString();
    }

    @Test
    @DisplayName("a besieged castle falls, exactly once, and reports it")
    void castleUnderFire() {
        TestWorld w = new TestWorld();
        w.barricade.buy();

        //  a stream of hostile shells landing on the barricade, then the wall
        for (int volley = 0; volley < 40; volley++) {
            Projectile p = new Projectile(w, w.castle.frontX() - 10f,
                    com.mymmer.castledefense.config.GameConfig.GROUND_Y - 50f,
                    0f, 0f, com.mymmer.castledefense.defence.ProjectileKind.MAGIC,
                    60f, 0f, 0, 0f, true, 5f, 0f, 1f, 1f, false, 0L);
            w.addProjectile(p);
            w.steps(2, DT);
        }
        assertEquals(0f, w.castle.hp(), 0f);
        assertEquals(1, w.castleDestroyedCount, "reported once, however many shells land");
    }

    @Test
    @DisplayName("the trace records the defence events without steering anything")
    void tracing() {
        RecordingSimulationTrace trace = new RecordingSimulationTrace();
        trace.setEnabled(true);

        TestWorld traced = new TestWorld(555L);
        traced.trace = trace;
        String tracedResult = scenario(traced);

        TestWorld untraced = new TestWorld(555L);
        String untracedResult = scenario(untraced);

        assertEquals(untracedResult, tracedResult,
                "turning tracing on must not change a single outcome");

        assertTrue(trace.countOf(TraceEvent.TOWER_FIRE) > 0);
        assertTrue(trace.countOf(TraceEvent.PROJECTILE_SPAWN) > 0);
        assertTrue(trace.countOf(TraceEvent.DAMAGE) > 0);
        assertTrue(trace.countOf(TraceEvent.CASTLE_DAMAGE) > 0);
        assertTrue(trace.countOf(TraceEvent.BARRICADE_DAMAGE) > 0);
        assertTrue(trace.countOf(TraceEvent.TOWER_DISABLED) > 0);
    }

    private static String scenario(TestWorld w) {
        w.barricade.buy();
        DefenceTower tower = w.castle.addTower(TowerType.BOWMAN);
        assertNotNull(tower);
        FakeTarget e = w.add(FakeTarget.ground(500f));
        e.hp = 5000f;

        for (int i = 0; i < 200; i++) {
            if (i % 20 == 0) {
                //  a hostile shell at the barricade, and one on the tower
                w.addProjectile(new Projectile(w, w.barricade.x(),
                        w.barricade.topY() + 5f, 0f, 0f, ProjectileKind.MAGIC,
                        90f, 0f, 0, 0f, true, 5f, 0f, 1f, 1f, false, 0L));
                w.addProjectile(new Projectile(w, tower.x(),
                        tower.y() - tower.height() / 2f, 0f, 0f, ProjectileKind.MAGIC,
                        tower.maxHp(), 0f, 0, 0f, true, 5f, 0f, 1f, 1f, false, 0L));
                w.addProjectile(new Projectile(w, w.castle.frontX() - 5f,
                        com.mymmer.castledefense.config.GameConfig.GROUND_Y - 60f,
                        0f, 0f, ProjectileKind.MAGIC, 20f, 0f, 0, 0f, true,
                        5f, 0f, 1f, 1f, false, 0L));
            }
            w.step(DT);
        }
        return (int) w.castle.hp() + "/" + (int) w.barricade.hp()
                + "/" + (int) e.hp() + "/" + tower.disabled();
    }

    @Test
    @DisplayName("projectiles keep insertion order through spawn and sweep")
    void projectileOrderIsStable() {
        TestWorld w = new TestWorld();
        EntityList<Projectile> list = w.projectiles;

        Projectile a = shortLived(w, 0.5f);
        Projectile b = shortLived(w, DT * 0.5f);      // dies on the first step
        Projectile c = shortLived(w, 0.5f);
        w.step(DT);

        assertFalse(b.isAlive());
        assertEquals(2, list.size(), "the dead one was swept");
        assertEquals(a.uid(), list.get(0).uid(), "and the survivors kept their order");
        assertEquals(c.uid(), list.get(1).uid());
    }

    private static Projectile shortLived(TestWorld w, float life) {
        Projectile p = new Projectile(w, 500f, 300f, 0f, 0f, ProjectileKind.ARROW,
                1f, 0f, 0, 0f, false, life, 0f, 1f, 1f, false, 0L);
        w.addProjectile(p);
        return p;
    }

    @Test
    @DisplayName("targets are scanned in list order and dead ones are skipped, not removed")
    void targetIterationContract() {
        TestWorld w = new TestWorld();
        FakeTarget first = w.add(FakeTarget.ground(400f));
        FakeTarget dead = w.add(FakeTarget.ground(390f));
        dead.alive = false;
        FakeTarget third = w.add(FakeTarget.ground(410f));

        DefenceTower bow = w.castle.addTower(TowerType.BOWMAN);
        //  the dead one has the smallest x and would win on score alone
        assertEquals(first.uid(), bow.pickTarget().uid());
        assertEquals(3, w.targetCount(), "and nothing was removed from the list");
        assertTrue(third.alive);
    }
}
