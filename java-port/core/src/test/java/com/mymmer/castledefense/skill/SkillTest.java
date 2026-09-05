package com.mymmer.castledefense.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.badlogic.gdx.utils.Array;
import com.mymmer.castledefense.config.GameConfig;
import com.mymmer.castledefense.config.Tuning;
import com.mymmer.castledefense.debug.TraceEvent;
import com.mymmer.castledefense.enemy.Enemy;
import com.mymmer.castledefense.enemy.EnemyState;
import com.mymmer.castledefense.enemy.EnemyType;
import com.mymmer.castledefense.game.GameMode;
import com.mymmer.castledefense.game.Simulation;
import com.mymmer.castledefense.progress.TestRun;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The three active skills: unlocking, cooldowns, targeting and what they do. */
class SkillTest {

    // ========================================================================
    //  Unlocking
    // ========================================================================

    @Test
    @DisplayName("a run starts with no skills, and one lights up per boss")
    void unlockOrder() {
        TestRun r = new TestRun().beginEndless();
        assertEquals(0, r.skills().unlockedCount(), "no skills before a boss falls");

        SkillId[] expected = {SkillId.LIGHTNING, SkillId.METEOR, SkillId.TORNADO};
        for (int i = 0; i < expected.length; i++) {
            SkillId got = r.skills().unlockNext();
            assertEquals(expected[i], got, "slot " + (i + 1));
            assertEquals(i + 1, r.skills().unlockedCount());
        }
        assertNull(r.skills().unlockNext(), "there is no fourth slot");
        assertEquals(3, r.skills().unlockedCount());
    }

    @Test
    @DisplayName("a locked skill cannot be selected or cast")
    void lockedSkillsRefuse() {
        TestRun r = new TestRun().beginEndless();
        for (SkillId id : SkillId.values()) {
            assertFalse(r.skills().isUnlocked(id));
            assertFalse(r.skills().isReady(id));
            assertFalse(r.skills().select(id));
            assertFalse(r.skills().castAt(id, 800f, 500f));
        }
    }

    @Test
    @DisplayName("only Meteor Shower is untargeted")
    void targetingFlags() {
        assertTrue(SkillId.LIGHTNING.needsTarget());
        assertFalse(SkillId.METEOR.needsTarget(), "it rains across the field");
        assertTrue(SkillId.TORNADO.needsTarget());
    }

    // ========================================================================
    //  Cooldowns
    // ========================================================================

    @Test
    @DisplayName("the base cooldowns are the tuning block's")
    void baseCooldowns() {
        assertEquals(Tuning.LIGHTNING_COOLDOWN, SkillPanel.baseCooldown(SkillId.LIGHTNING), 0d);
        assertEquals(Tuning.METEOR_COOLDOWN, SkillPanel.baseCooldown(SkillId.METEOR), 0d);
        assertEquals(Tuning.TORNADO_COOLDOWN, SkillPanel.baseCooldown(SkillId.TORNADO), 0d);
    }

    @Test
    @DisplayName("a cast starts the cooldown, and it runs down on the canonical clock")
    void cooldownRunsOnTheGameplayClock() {
        TestRun r = new TestRun().beginEndless();
        r.skills().unlockNext();
        assertTrue(r.skills().isReady(SkillId.LIGHTNING));

        assertTrue(r.skills().castAt(SkillId.LIGHTNING, 800f, 500f));
        assertFalse(r.skills().isReady(SkillId.LIGHTNING));
        assertEquals(Tuning.LIGHTNING_COOLDOWN,
                r.skills().cooldownRemaining(SkillId.LIGHTNING), 1e-9);

        //  one second of simulation is exactly one second off the cooldown
        r.survivingSeconds(1);
        assertEquals(Tuning.LIGHTNING_COOLDOWN - 1.0,
                r.skills().cooldownRemaining(SkillId.LIGHTNING), 1e-9,
                "the cooldown is a double and does not drift");

        //  and it is ready on the exact step
        int steps = (int) Math.ceil((Tuning.LIGHTNING_COOLDOWN - 1.0)
                / Simulation.FIXED_DT);
        r.survivingSteps(steps);
        assertTrue(r.skills().isReady(SkillId.LIGHTNING));
        assertEquals(0d, r.skills().cooldownRemaining(SkillId.LIGHTNING), 0d);
    }

    @Test
    @DisplayName("a recharging skill cannot be recast")
    void noRecastWhileCooling() {
        TestRun r = new TestRun().beginEndless();
        r.skills().unlockNext();
        assertTrue(r.skills().castAt(SkillId.LIGHTNING, 800f, 500f));
        assertFalse(r.skills().castAt(SkillId.LIGHTNING, 800f, 500f));
        assertFalse(r.skills().select(SkillId.LIGHTNING));
    }

    @Test
    @DisplayName("the cooldown does not run while the world is frozen")
    void cooldownFreezesWithTheWorld() {
        TestRun r = new TestRun().beginEndless();
        r.skills().unlockNext();
        r.skills().castAt(SkillId.LIGHTNING, 800f, 500f);
        double remaining = r.skills().cooldownRemaining(SkillId.LIGHTNING);

        assertTrue(r.run.openRealtimeShop());
        r.steps(120);
        assertEquals(remaining, r.skills().cooldownRemaining(SkillId.LIGHTNING), 0d,
                "shopping is not recharging");

        r.run.resumeFromShop();
        r.survivingSeconds(1);
        assertEquals(remaining - 1.0, r.skills().cooldownRemaining(SkillId.LIGHTNING),
                1e-9);
    }

    // ========================================================================
    //  Targeting
    // ========================================================================

    @Test
    @DisplayName("selecting a targeted skill arms it; casting clears the aim")
    void aiming() {
        TestRun r = new TestRun().beginEndless();
        r.skills().unlockNext();
        assertNull(r.skills().aiming());

        assertTrue(r.skills().select(SkillId.LIGHTNING));
        assertEquals(SkillId.LIGHTNING, r.skills().aiming());

        assertTrue(r.skills().castAimedAt(800f, 500f));
        assertNull(r.skills().aiming(), "the aim is spent with the cast");
    }

    @Test
    @DisplayName("an untargeted skill is never left armed")
    void untargetedIsNotArmed() {
        TestRun r = new TestRun().beginEndless();
        r.skills().unlockNext();
        r.skills().unlockNext();                 // Meteor
        assertTrue(r.skills().select(SkillId.METEOR), "it is ready...");
        assertNull(r.skills().aiming(), "...but there is nothing to aim");
    }

    @Test
    @DisplayName("the aim is dropped when the skill stops being ready")
    void aimDropsWhenNotReady() {
        TestRun r = new TestRun().beginEndless();
        r.skills().unlockNext();
        r.skills().unlockNext();
        r.skills().unlockNext();

        assertTrue(r.skills().select(SkillId.TORNADO));
        assertEquals(SkillId.TORNADO, r.skills().aiming());
        r.skills().castAt(SkillId.TORNADO, 900f, 500f);   // casts it directly
        r.survivingSeconds(0.1);
        assertNull(r.skills().aiming());
    }

    @Test
    @DisplayName("cancelling the aim casts nothing")
    void cancelAim() {
        TestRun r = new TestRun().beginEndless();
        r.skills().unlockNext();
        r.skills().select(SkillId.LIGHTNING);
        r.skills().cancelAim();
        assertNull(r.skills().aiming());
        assertFalse(r.skills().castAimedAt(800f, 500f));
        assertTrue(r.skills().isReady(SkillId.LIGHTNING), "and it is still charged");
    }

    // ========================================================================
    //  Lightning
    // ========================================================================

    @Test
    @DisplayName("Lightning hits everything within its radius of the cast x, at any height")
    void lightningIsAColumn() {
        TestRun r = new TestRun().beginEndless();
        r.skills().unlockNext();

        Enemy inside = r.run.spawnEnemy(EnemyType.SCOUT, 1);
        inside.setX(800f);
        Enemy overhead = r.run.spawnEnemy(EnemyType.GARGOYLE, 1);
        overhead.setX(820f);
        overhead.setY(120f);                    // far above the ground mob
        Enemy outside = r.run.spawnEnemy(EnemyType.SCOUT, 1);
        outside.setX(800f + Tuning.LIGHTNING_RADIUS + 60f);

        float insideHp = inside.hp();
        float overheadHp = overhead.hp();
        float outsideHp = outside.hp();

        assertTrue(r.skills().castAt(SkillId.LIGHTNING, 800f, 500f));

        assertTrue(inside.hp() < insideHp, "the ground mob was hit");
        assertTrue(overhead.hp() < overheadHp, "and so was the flyer above it");
        assertEquals(outsideHp, outside.hp(), 0f, "the one out of range was not");
    }

    @Test
    @DisplayName("Lightning damage is a share of MAXIMUM health, and a boss takes a quarter")
    void lightningDamage() {
        TestRun r = new TestRun().beginEndless();
        r.skills().unlockNext();

        Enemy mob = r.run.spawnEnemy(EnemyType.SCOUT, 1);
        mob.setX(800f);
        float maxHp = mob.maxHp();
        float before = mob.hp();
        r.skills().castAt(SkillId.LIGHTNING, 800f, 500f);
        float dealt = before - mob.hp();
        //  the mob may simply die; either way it took at least the full share
        assertTrue(dealt >= Math.min(before, maxHp * Tuning.LIGHTNING_DAMAGE) - 1e-2f,
                "expected about " + (maxHp * Tuning.LIGHTNING_DAMAGE) + ", took " + dealt);

        //  a boss takes a quarter of that
        TestRun b = new TestRun().beginEndless();
        b.skills().unlockNext();
        Enemy boss = b.run.summonBoss(com.mymmer.castledefense.boss.BossType.TROLL_KING, 1);
        assertNotNull(boss);
        boss.setX(800f);
        float bossMax = boss.maxHp();
        float bossBefore = boss.hp();
        b.skills().castAt(SkillId.LIGHTNING, 800f, 500f);
        float bossDealt = bossBefore - boss.hp();
        assertEquals(bossMax * Tuning.LIGHTNING_DAMAGE * 0.25f, bossDealt, 1f,
                "shaken, not vaporised");
    }

    @Test
    @DisplayName("Amplify and Lightning Rod both multiply the bolt")
    void lightningTalents() {
        float plain = lightningDealt(new TestRun().beginEndless());

        TestRun amped = new TestRun().beginEndless();
        amped.grantPoints(20).buyTalent("amplify", 5);
        assertTrue(lightningDealt(amped) > plain, "Amplify raises it");

        TestRun rod = new TestRun().beginEndless();
        rod.grantPoints(20).buyTalentDeep("conductor", 4);
        assertTrue(lightningDealt(rod) > plain, "Lightning Rod raises it too");
    }

    private static float lightningDealt(TestRun r) {
        r.skills().unlockNext();
        Enemy mob = r.run.spawnEnemy(EnemyType.SIEGE_RAM, 1);   // tough enough to survive
        mob.setX(800f);
        float before = mob.hp();
        r.skills().castAt(SkillId.LIGHTNING, 800f, 500f);
        return before - mob.hp();
    }

    // ========================================================================
    //  Meteor and FireZone
    // ========================================================================

    @Test
    @DisplayName("Meteor drops rocks and lights the ground at the same moment")
    void meteorDropsRocksAndFire() {
        TestRun r = new TestRun().beginEndless();
        r.skills().unlockNext();
        r.skills().unlockNext();

        assertEquals(0, r.run.projectiles().size());
        assertEquals(0, r.run.fireZones().size);

        assertTrue(r.skills().castAt(SkillId.METEOR, 800f, 400f));
        assertEquals(Tuning.METEOR_COUNT, r.run.projectiles().size(),
                "one rock per METEOR_COUNT");
        assertEquals(Tuning.METEOR_COUNT, r.run.fireZones().size,
                "and the fire is created at cast time, not on impact");
    }

    @Test
    @DisplayName("Twin Cast drops more rocks")
    void twinCast() {
        TestRun r = new TestRun().beginEndless();
        r.skills().unlockNext();
        r.skills().unlockNext();
        r.grantPoints(30).buyTalentDeep("twincast", 2);

        r.skills().castAt(SkillId.METEOR, 800f, 400f);
        assertEquals((int) (Tuning.METEOR_COUNT * 2.0f), r.run.projectiles().size(),
                "two ranks x 0.5 doubles the shower");
    }

    @Test
    @DisplayName("a FireZone burns ground mobs continuously and ignores flyers")
    void fireZoneBurns() {
        TestRun r = new TestRun().beginEndless();
        Enemy ground = r.run.spawnEnemy(EnemyType.SIEGE_RAM, 1);
        ground.setX(800f);
        Enemy flyer = r.run.spawnEnemy(EnemyType.GARGOYLE, 1);
        flyer.setX(800f);

        FireZone zone = new FireZone(r.run, 800f, 2.0, 100f, 70f);
        r.run.addFireZone(zone);

        float groundHp = ground.hp();
        float flyerHp = flyer.hp();
        r.survivingSeconds(0.5);

        assertTrue(ground.hp() < groundHp, "the ground mob burns");
        assertEquals(flyerHp, flyer.hp(), 0f, "the flyer does not");
        assertTrue(zone.alive());
        assertEquals(1.5, zone.life(), 1e-6, "and the zone aged by exactly half a second");
    }

    @Test
    @DisplayName("a FireZone expires and is swept")
    void fireZoneExpires() {
        TestRun r = new TestRun().beginEndless();
        r.run.addFireZone(new FireZone(r.run, 800f, 0.5, 50f, 70f));
        assertEquals(1, r.run.fireZones().size);
        r.survivingSeconds(0.6);
        assertEquals(0, r.run.fireZones().size);
    }

    @Test
    @DisplayName("Emberfall makes the fire burn longer")
    void emberfall() {
        TestRun r = new TestRun().beginEndless();
        r.skills().unlockNext();
        r.skills().unlockNext();
        r.grantPoints(30).buyTalentDeep("emberfall", 3);

        r.skills().castAt(SkillId.METEOR, 800f, 400f);
        FireZone zone = r.run.fireZones().first();
        assertEquals(Tuning.FIRE_ZONE_TIME * (1d + 0.90d), zone.maxLife(), 1e-6,
                "three ranks x 0.30");
    }

    // ========================================================================
    //  Tornado
    // ========================================================================

    @Test
    @DisplayName("a Tornado lifts a ground mob, carries it, and hurls it at the end")
    void tornadoLiftsAndThrows() {
        TestRun r = new TestRun().beginEndless();
        Enemy mob = r.run.spawnEnemy(EnemyType.SCOUT, 1);
        mob.setX(900f);
        mob.setY(GameConfig.GROUND_Y);
        assertTrue(mob.state().isOnFoot());

        Tornado t = new Tornado(r.run, 900f, 1.0, Tuning.TORNADO_RADIUS, 1.0);
        r.run.addTornado(t);

        r.survivingSeconds(0.2);
        assertEquals(EnemyState.AIR, mob.state(), "it was picked up");
        assertTrue(t.caughtCount() >= 1);
        assertTrue(mob.tornadoHold() > 0d, "and is being carried, not just falling");
        assertTrue(mob.vy() < 0f, "it is going up");

        //  let it burst
        r.survivingSeconds(1.0);
        assertFalse(t.alive());
        assertTrue(t.thrownCount() >= 1, "it hurled what it caught");
        assertEquals(0, r.run.tornados().size, "and was swept");
    }

    @Test
    @DisplayName("a Tornado drifts away from the castle while it runs")
    void tornadoDrifts() {
        TestRun r = new TestRun().beginEndless();
        Tornado t = new Tornado(r.run, 700f, 2.0, Tuning.TORNADO_RADIUS, 1.0);
        r.run.addTornado(t);
        float x0 = t.x();
        r.survivingSeconds(1.0);
        assertEquals(x0 + Tuning.TORNADO_SPEED, t.x(), 1.0f,
                "TORNADO_SPEED per second, away from the keep");
    }

    @Test
    @DisplayName("a Tornado will not touch a boss, an armoured tank or an ungrabbable unit")
    void tornadoSkipsWhatItCannotLift() {
        TestRun r = new TestRun().beginEndless();
        Enemy boss = r.run.summonBoss(com.mymmer.castledefense.boss.BossType.TROLL_KING, 1);
        boss.setX(900f);
        Enemy ram = r.run.spawnEnemy(EnemyType.SIEGE_RAM, 1);
        ram.setX(900f);
        assertTrue(ram.armored(), "a fresh Siege Ram still has its plates");

        r.run.addTornado(new Tornado(r.run, 900f, 0.5, Tuning.TORNADO_RADIUS, 1.0));
        r.survivingSeconds(0.3);

        assertFalse(boss.state() == EnemyState.AIR, "a boss rides it out");
        assertFalse(ram.state() == EnemyState.AIR, "and so does a plated tank");
    }

    @Test
    @DisplayName("Eye of the Storm lengthens the funnel and strengthens the pull")
    void eyeOfTheStorm() {
        TestRun r = new TestRun().beginEndless();
        r.skills().unlockNext();
        r.skills().unlockNext();
        r.skills().unlockNext();
        r.grantPoints(30).buyTalentDeep("eyeofstorm", 2);

        r.skills().castAt(SkillId.TORNADO, 900f, 500f);
        Tornado t = r.run.tornados().first();
        assertEquals(Tuning.TORNADO_LIFE * (1d + 0.50d), t.maxLife(), 1e-6,
                "two ranks x 0.25 -- and the same value drives the pull");
    }

    // ========================================================================
    //  Bosses and snapshots
    // ========================================================================

    @Test
    @DisplayName("a boss dying inside an area effect does not corrupt the iteration")
    void bossDeathDuringAnAreaEffect() {
        //  The Phase 8 lesson: a boss's death purges entries from the horde on
        //  the spot, so anything iterating it must snapshot.
        TestRun r = new TestRun().beginEndless();
        r.skills().unlockNext();

        Enemy boss = r.run.summonBoss(com.mymmer.castledefense.boss.BossType.TROLL_KING, 1);
        boss.setX(800f);
        boss.applyDamage(boss.maxHp() * 0.99f, "test");     // one bolt from death

        for (int i = 0; i < 20; i++) {
            Enemy e = r.run.spawnEnemy(EnemyType.SCOUT, 1);
            e.setX(800f + i);
        }

        //  hit them all at once; the boss dies mid-loop
        assertTrue(r.skills().castAt(SkillId.LIGHTNING, 800f, 500f));
        r.survivingSeconds(0.1);
        //  no exception, and the roster is consistent
        assertTrue(r.run.horde().size() >= 0);
    }

    @Test
    @DisplayName("two bosses can both be hit, and one dying leaves the other alone")
    void twoBosses() {
        TestRun r = new TestRun().beginEndless();
        r.skills().unlockNext();
        Enemy a = r.run.summonBoss(com.mymmer.castledefense.boss.BossType.TROLL_KING, 1);
        a.setX(800f);
        Enemy b = r.run.summonBoss(com.mymmer.castledefense.boss.BossType.DRAGON, 1);
        b.setX(810f);
        assertEquals(2, r.run.bossRegistry().liveCount());

        float aHp = a.hp();
        float bHp = b.hp();
        r.skills().castAt(SkillId.LIGHTNING, 805f, 500f);
        assertTrue(a.hp() < aHp);
        assertTrue(b.hp() < bHp);
        assertEquals(2, r.run.bossRegistry().liveCount(), "neither died");
    }

    // ========================================================================
    //  Lifecycle, determinism and diagnostics
    // ========================================================================

    @Test
    @DisplayName("a new run clears the skills, the cooldowns and the field effects")
    void resetOnNewRun() {
        TestRun r = new TestRun().beginEndless();
        r.skills().unlockNext();
        r.skills().unlockNext();
        r.skills().castAt(SkillId.LIGHTNING, 800f, 500f);
        r.skills().castAt(SkillId.METEOR, 800f, 400f);
        assertTrue(r.run.fireZones().size > 0);

        r.run.beginRun(GameMode.ENDLESS, r.difficulty("normal"), 5L);

        assertEquals(0, r.skills().unlockedCount(), "no skills carry over");
        assertEquals(0d, r.skills().cooldownRemaining(SkillId.LIGHTNING), 0d);
        assertEquals(0, r.run.fireZones().size, "and no burning ground");
        assertEquals(0, r.run.tornados().size);
        assertEquals(0, r.skills().castCount());
    }

    @Test
    @DisplayName("a seeded meteor shower drops identical rocks")
    void seededMeteorIsReproducible() {
        assertEquals(meteorFingerprint(1234L), meteorFingerprint(1234L));
        assertFalse(meteorFingerprint(1234L).equals(meteorFingerprint(4321L)));
    }

    private static String meteorFingerprint(long seed) {
        TestRun r = new TestRun(seed);
        r.run.beginRun(GameMode.ENDLESS, r.difficulty("normal"), seed);
        r.skills().unlockNext();
        r.skills().unlockNext();
        r.skills().castAt(SkillId.METEOR, 800f, 400f);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < r.run.fireZones().size; i++) {
            sb.append(String.format(java.util.Locale.ROOT, "%.3f,",
                    r.run.fireZones().get(i).x()));
        }
        return sb.toString();
    }

    @Test
    @DisplayName("casts are traced, and counted on the run")
    void tracingAndStats() {
        TestRun r = new TestRun();
        r.recording();
        r.beginEndless();
        r.skills().unlockNext();
        r.skills().select(SkillId.LIGHTNING);
        r.skills().castAimedAt(800f, 500f);

        assertEquals(1, r.trace.countOf(TraceEvent.SKILL_UNLOCKED));
        assertEquals(1, r.trace.countOf(TraceEvent.SKILL_SELECTED));
        assertEquals(1, r.trace.countOf(TraceEvent.SKILL_CAST));
        assertEquals(1, r.skills().castCount());
        assertEquals(1, r.session().casts());
    }

    @Test
    @DisplayName("the view reports readiness and progress for the future skill bar")
    void queryView() {
        TestRun r = new TestRun().beginEndless();
        r.skills().unlockNext();
        Array<SkillPanel.SlotView> view = r.skills().view();
        assertEquals(1, view.size);
        assertTrue(view.first().ready);
        assertEquals(1f, view.first().progress(), 0f);

        r.skills().castAt(SkillId.LIGHTNING, 800f, 500f);
        SkillPanel.SlotView v = r.skills().view(SkillId.LIGHTNING);
        assertFalse(v.ready);
        assertEquals(0f, v.progress(), 1e-6f, "just cast");

        r.survivingSeconds(Tuning.LIGHTNING_COOLDOWN / 2d);
        assertEquals(0.5f, r.skills().view(SkillId.LIGHTNING).progress(), 1e-3f);
    }
}
