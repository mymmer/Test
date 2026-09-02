package com.mymmer.castledefense.defence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mymmer.castledefense.config.GameConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Projectile motion, collision and payload.
 *
 * <p>Two kinds of assertion live here and they prove different things:
 *
 * <ul>
 *   <li><b>Trajectory tests</b> compute the closed-form result of N fixed steps
 *       of semi-implicit Euler and check the simulation matches. They prove the
 *       Java integration is internally stable and did not pick up an off-by-one
 *       step or a swapped update order. They are <em>not</em> Python parity —
 *       that is Phase 13, against the real thing.</li>
 *   <li><b>Behaviour tests</b> pin the Python rules: hit order, one target per
 *       step, pierce falloff, splash falloff, obstacle priority.</li>
 * </ul>
 */
class ProjectileTest {

    private static final float DT = 1f / 60f;

    /**
     * Closed form for the port's integration order, which is Python's:
     * {@code v += a*dt} first, then {@code p += v*dt}. After n steps
     * {@code v = v0 + n*a*dt} and {@code p = p0 + v0*n*dt + a*dt²*n(n+1)/2}.
     */
    private static float expectedPos(float p0, float v0, float a, int n, float dt) {
        return p0 + v0 * n * dt + a * dt * dt * (n * (n + 1) / 2f);
    }

    @Test
    @DisplayName("a gravity-free shot travels exactly velocity x time")
    void straightLineTrajectory() {
        TestWorld w = new TestWorld();
        Projectile p = new Projectile(w, 100f, 200f, 600f, -120f,
                ProjectileKind.ARROW, 10f, 0f, 0, 0f, false, 5f, 0f, 1f, 1f, false, 0L);
        w.addProjectile(p);
        int n = 30;
        for (int i = 0; i < n; i++) {
            p.update(DT);
        }
        assertEquals(100f + 600f * n * DT, p.x(), 0.01f);
        assertEquals(200f - 120f * n * DT, p.y(), 0.01f);
        assertEquals(600f, p.vx(), 0f, "no wind, no drag: vx is untouched");
        assertEquals(5f - n * DT, p.life(), 1e-4f);
    }

    @Test
    @DisplayName("a ballistic shot matches the closed-form fixed-step arc")
    void gravityTrajectory() {
        TestWorld w = new TestWorld();
        float g = GameConfig.GRAVITY;
        Projectile p = new Projectile(w, 300f, 100f, 400f, -300f,
                //  MAGIC, not CANNON: a cannon shell would detonate on the
                //  ground line part-way through and this test is about the arc
                ProjectileKind.MAGIC, 10f, 0f, 0, g, false, 9f, 0f, 1f, 1f, false, 0L);
        w.addProjectile(p);
        int n = 45;
        for (int i = 0; i < n; i++) {
            p.update(DT);
        }
        assertEquals(expectedPos(300f, 400f, 0f, n, DT), p.x(), 0.02f);
        assertEquals(expectedPos(100f, -300f, g, n, DT), p.y(), 0.05f,
                "y is integrated with gravity, semi-implicit, in that order");
        assertEquals(-300f + g * n * DT, p.vy(), 0.01f);
    }

    @Test
    @DisplayName("wind accelerates a shot horizontally, scaled by WIND_PROJECTILE")
    void windTrajectory() {
        TestWorld w = new TestWorld();
        w.wind = 200f;
        float ax = w.wind * GameConfig.WIND_PROJECTILE;
        Projectile p = new Projectile(w, 400f, 300f, 0f, 0f,
                ProjectileKind.MAGIC, 10f, 0f, 0, 0f, false, 9f, 0f, 1f, 1f, false, 0L);
        w.addProjectile(p);
        int n = 60;
        for (int i = 0; i < n; i++) {
            p.update(DT);
        }
        assertEquals(expectedPos(400f, 0f, ax, n, DT), p.x(), 0.02f);
        assertEquals(ax * n * DT, p.vx(), 0.01f);
        assertEquals(300f, p.y(), 0.001f, "wind is horizontal only");
    }

    @Test
    @DisplayName("wind is applied before gravity and before the position update")
    void updateOrderIsPythons() {
        //  If gravity were applied after the position update, one step of a
        //  dropped shot would move it 0 instead of g*dt*dt.  Cheap to get wrong,
        //  invisible without this test, and it shifts every impact by a frame.
        TestWorld w = new TestWorld();
        Projectile p = new Projectile(w, 0f, 0f, 0f, 0f, ProjectileKind.MAGIC,
                1f, 0f, 0, GameConfig.GRAVITY, false, 9f, 0f, 1f, 1f, false, 0L);
        w.addProjectile(p);
        p.update(DT);
        assertEquals(GameConfig.GRAVITY * DT * DT, p.y(), 1e-4f,
                "velocity is updated first, then position");
    }

    @Test
    @DisplayName("a friendly shot hits at most one target per step, in list order")
    void oneHitPerStep() {
        TestWorld w = new TestWorld();
        FakeTarget first = w.add(FakeTarget.ground(500f));
        FakeTarget second = w.add(FakeTarget.ground(500f));
        //  both occupy the same point, so both pass the bounds test
        second.y = first.y;

        Projectile p = new Projectile(w, 500f, first.y, 0f, 0f, ProjectileKind.ARROW,
                25f, 0f, 3, 0f, false, 5f, 0f, 1f, 1f, false, 0L);
        w.addProjectile(p);

        p.update(DT);
        assertEquals(1, first.damageTaken.size, "the earlier entry is hit");
        assertEquals(0, second.damageTaken.size, "and only it, this step");

        p.update(DT);
        assertEquals(1, first.damageTaken.size, "already-hit units are skipped");
        assertEquals(1, second.damageTaken.size);
    }

    @Test
    @DisplayName("pierce lets a shot continue, at 72% damage each time")
    void pierceAndFalloff() {
        TestWorld w = new TestWorld();
        FakeTarget a = w.add(FakeTarget.ground(500f));
        FakeTarget b = w.add(FakeTarget.ground(500f));
        FakeTarget c = w.add(FakeTarget.ground(500f));
        b.y = a.y;
        c.y = a.y;
        a.hp = b.hp = c.hp = 10000f;

        Projectile p = new Projectile(w, 500f, a.y, 0f, 0f, ProjectileKind.BOLT,
                100f, 0f, 1, 0f, false, 5f, 0f, 1f, 1f, false, 0L);
        w.addProjectile(p);

        p.update(DT);
        assertEquals(100f, a.damageTaken.get(0), 0.01f);
        assertTrue(p.isAlive(), "one pierce left");
        assertEquals(0, p.pierce(), "and it was spent");

        p.update(DT);
        assertEquals(72f, b.damageTaken.get(0), 0.01f, "damage x0.72 after a pierce");
        assertFalse(p.isAlive(), "out of pierces, the shot dies on that hit");

        assertEquals(0, c.damageTaken.size, "a dead shot hits nobody else");
    }

    @Test
    @DisplayName("a splash shot detonates on its first contact instead of piercing")
    void splashDetonatesOnContact() {
        TestWorld w = new TestWorld();
        FakeTarget hit = w.add(FakeTarget.ground(500f));
        FakeTarget near = w.add(FakeTarget.ground(540f));
        FakeTarget far = w.add(FakeTarget.ground(900f));
        hit.hp = near.hp = far.hp = 10000f;
        near.y = hit.y;

        Projectile p = new Projectile(w, 500f, hit.y, 0f, 0f, ProjectileKind.MAGIC,
                100f, 80f, 5, 0f, false, 5f, 0f, 1f, 1f, false, 0L);
        w.addProjectile(p);
        p.update(DT);

        assertTrue(p.hasExploded());
        assertFalse(p.isAlive(), "splash ignores its remaining pierce");
        assertEquals(1, hit.damageTaken.size);
        assertEquals(1, near.damageTaken.size, "caught in the blast");
        assertEquals(0, far.damageTaken.size, "outside the radius");
        assertEquals("explosive", hit.damageSources.get(0));
    }

    @Test
    @DisplayName("splash damage falls off with distance, at 55% across the radius")
    void splashFalloff() {
        TestWorld w = new TestWorld();
        float radius = 100f;
        FakeTarget centre = w.add(new FakeTarget(500f, 400f));
        FakeTarget edge = w.add(new FakeTarget(500f + radius, 400f));
        FakeTarget half = w.add(new FakeTarget(500f + radius * 0.5f, 400f));
        centre.hp = edge.hp = half.hp = 10000f;

        Projectile p = new Projectile(w, 500f, 400f, 0f, 0f, ProjectileKind.MAGIC,
                200f, radius, 0, 0f, false, 5f, 0f, 1f, 1f, false, 0L);
        w.addProjectile(p);
        p.explode();

        assertEquals(200f, centre.damageTaken.get(0), 0.01f, "full at the centre");
        assertEquals(200f * 0.45f, edge.damageTaken.get(0), 0.05f, "45% at the rim");
        assertEquals(200f * 0.725f, half.damageTaken.get(0), 0.05f, "linear between");
    }

    @Test
    @DisplayName("splash walks the target list in order, skipping the dead")
    void splashOrderAndLiveChecks() {
        TestWorld w = new TestWorld();
        FakeTarget alive1 = w.add(new FakeTarget(500f, 400f));
        FakeTarget dead = w.add(new FakeTarget(500f, 400f));
        FakeTarget alive2 = w.add(new FakeTarget(500f, 400f));
        alive1.hp = alive2.hp = 10000f;
        dead.alive = false;

        Projectile p = new Projectile(w, 500f, 400f, 0f, 0f, ProjectileKind.MAGIC,
                50f, 100f, 0, 0f, false, 5f, 0f, 1f, 1f, false, 0L);
        w.addProjectile(p);
        p.explode();

        assertEquals(1, alive1.damageTaken.size);
        assertEquals(0, dead.damageTaken.size, "dead units take no splash");
        assertEquals(1, alive2.damageTaken.size);
    }

    @Test
    @DisplayName("a cannon shell detonates when it reaches the ground line")
    void cannonExplodesOnGround() {
        TestWorld w = new TestWorld();
        //  Off to the side: outside the shell's own hitbox test (so it cannot
        //  detonate on contact and confound the test) but well inside the blast.
        FakeTarget nearby = w.add(new FakeTarget(560f, GameConfig.GROUND_Y - 20f));
        nearby.hp = 10000f;

        Projectile p = new Projectile(w, 500f, GameConfig.GROUND_Y - 40f, 0f, 400f,
                ProjectileKind.CANNON, 60f, 90f, 0, 0f, false, 5f, 0f, 1f, 1f, false, 0L);
        w.addProjectile(p);
        for (int i = 0; i < 20 && p.isAlive(); i++) {
            p.update(DT);
        }
        assertTrue(p.hasExploded(), "it reached the ground and detonated");
        assertEquals(GameConfig.GROUND_Y - 2f, p.y(), 0.001f, "snapped to the ground line");
        assertEquals(1, nearby.damageTaken.size);
    }

    @Test
    @DisplayName("a splash shot that times out detonates; one that flies off the map does not")
    void deathSemantics() {
        TestWorld w = new TestWorld();
        Projectile timedOut = new Projectile(w, 500f, 300f, 0f, 0f, ProjectileKind.MAGIC,
                50f, 60f, 0, 0f, false, DT * 0.5f, 0f, 1f, 1f, false, 0L);
        w.addProjectile(timedOut);
        timedOut.update(DT);
        assertTrue(timedOut.hasExploded(), "running out of time detonates it");
        assertFalse(timedOut.isAlive());

        Projectile offMap = new Projectile(w, 500f, 300f, 100000f, 0f, ProjectileKind.MAGIC,
                50f, 60f, 0, 0f, false, 5f, 0f, 1f, 1f, false, 0L);
        w.addProjectile(offMap);
        offMap.update(DT);
        assertFalse(offMap.isAlive());
        assertFalse(offMap.hasExploded(), "leaving the map is not a detonation");
    }

    @Test
    @DisplayName("counters are resolved per victim, not baked in at launch")
    void perTargetCounters() {
        TestWorld w = new TestWorld();
        Projectile p = new Projectile(w, 0f, 0f, 0f, 0f, ProjectileKind.BOLT,
                100f, 0f, 0, 0f, false, 5f, 0f, 3f, 2f, false, 0L);
        FakeTarget plain = FakeTarget.ground(0f);
        FakeTarget flyer = FakeTarget.flyer(0f, 0f);
        FakeTarget heavy = FakeTarget.heavy(0f);
        FakeTarget both = FakeTarget.flyer(0f, 0f);
        both.heavy = true;

        assertEquals(100f, p.damageFor(plain), 0.01f);
        assertEquals(300f, p.damageFor(flyer), 0.01f);
        assertEquals(200f, p.damageFor(heavy), 0.01f);
        assertEquals(600f, p.damageFor(both), 0.01f, "both counters multiply");
    }

    @Test
    @DisplayName("splash radius takes the talent bonus once, at construction")
    void splashTalentAppliesAtConstruction() {
        TestWorld w = new TestWorld();
        w.modifiers = new CombatModifiers() {
            @Override
            public float splashMult() {
                return 1.5f;
            }
        };
        Projectile p = new Projectile(w, 0f, 0f, 0f, 0f, ProjectileKind.CANNON,
                10f, 80f, 0, 0f, false, 5f, 0f, 1f, 1f, false, 0L);
        assertEquals(120f, p.splash(), 0.01f);

        //  a shot with no splash stays with no splash: 0 * 1.5 would still be 0,
        //  but the guard also keeps a "no splash" shot from becoming a fizzling
        //  explosion if the multiplier were ever additive
        Projectile arrow = new Projectile(w, 0f, 0f, 0f, 0f, ProjectileKind.ARROW,
                10f, 0f, 0, 0f, false, 5f, 0f, 1f, 1f, false, 0L);
        assertEquals(0f, arrow.splash(), 0f);
    }

    @Test
    @DisplayName("crits are seeded, so a replay crits on the same steps")
    void critsUseTheSeededStream() {
        float[] runA = critRun(4242L);
        float[] runB = critRun(4242L);
        float[] runC = critRun(99L);

        assertEquals(runA.length, runB.length);
        for (int i = 0; i < runA.length; i++) {
            assertEquals(runA[i], runB[i], 0f, "same seed, same crits at step " + i);
        }
        boolean differs = false;
        for (int i = 0; i < runA.length && !differs; i++) {
            differs = runA[i] != runC[i];
        }
        assertTrue(differs, "a different seed must produce a different crit pattern");
    }

    private static float[] critRun(long seed) {
        TestWorld w = new TestWorld(seed);
        w.modifiers = new CombatModifiers() {
            @Override
            public float critChance() {
                return 0.5f;
            }
        };
        Projectile p = new Projectile(w, 0f, 0f, 0f, 0f, ProjectileKind.ARROW,
                10f, 0f, 0, 0f, false, 5f, 0f, 1f, 1f, false, 0L);
        FakeTarget dummy = FakeTarget.ground(0f);
        dummy.hp = Float.MAX_VALUE;
        float[] out = new float[40];
        for (int i = 0; i < out.length; i++) {
            out[i] = p.damageFor(dummy);
        }
        return out;
    }
}
