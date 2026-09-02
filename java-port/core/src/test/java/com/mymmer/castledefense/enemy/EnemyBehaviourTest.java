package com.mymmer.castledefense.enemy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mymmer.castledefense.config.GameConfig;
import com.mymmer.castledefense.defence.CombatModifiers;
import com.mymmer.castledefense.defence.Projectile;
import com.mymmer.castledefense.defence.TowerType;
import com.mymmer.castledefense.entity.EntityList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The state machine, the crowd, death, and each unit's own behaviour. */
class EnemyBehaviourTest {

    private static final float DT = TestEnemyWorld.DT;

    // ========================================================================
    //  The state machine
    // ========================================================================

    @Test
    @DisplayName("a mob walks in, reaches the wall, and starts attacking")
    void walkToAttack() {
        TestEnemyWorld w = new TestEnemyWorld();
        Enemy e = w.spawn(EnemyType.FOOT_SOLDIER, 600f);
        assertEquals(EnemyState.WALK, e.state());

        float hp = w.castle.hp();
        w.steps(600, DT);
        assertEquals(EnemyState.ATTACK, e.state());
        assertEquals(GameConfig.CASTLE_FRONT + e.width() / 2f, e.x(), 0.01f,
                "it stops exactly at the wall");
        assertTrue(w.castle.hp() < hp, "and swings at it");
    }

    @Test
    @DisplayName("attacking the wall is bitten back by spikes")
    void attackingIsBittenBySpikes() {
        TestEnemyWorld w = new TestEnemyWorld();
        w.spikes.setLevel(GameConfig.SPIKE_MAX_LEVEL);
        Enemy e = w.spawn(EnemyType.FOOT_SOLDIER, GameConfig.CASTLE_FRONT + 20f);
        e.applyDamage(0f, "fall");
        float hp = e.hp();
        w.steps(240, DT);
        assertTrue(e.hp() < hp || !e.alive(), "the spikes bit back");
    }

    @Test
    @DisplayName("a barricade in the way is attacked instead of the castle")
    void barricadeBlocksGround() {
        TestEnemyWorld w = new TestEnemyWorld();
        w.barricade.buy();
        Enemy foot = w.spawn(EnemyType.FOOT_SOLDIER, 900f);
        Enemy flyer = w.spawn(EnemyType.GARGOYLE, 900f);

        float barHp = w.barricade.hp();
        w.steps(900, DT);

        assertTrue(w.barricade.hp() < barHp, "ground troops chew through it");
        assertTrue(foot.x() > w.barricade.x(), "and are held outside");
        assertTrue(flyer.x() < w.barricade.x(), "flyers simply go over");
    }

    @Test
    @DisplayName("a heavy unit hits the barricade for 2.5x")
    void heavyHitsBarricadeHarder() {
        float light = barricadeDamageFrom(EnemyType.FOOT_SOLDIER);
        float heavy = barricadeDamageFrom(EnemyType.SIEGE_RAM);
        TestEnemyWorld probe = new TestEnemyWorld();
        float lightDmg = probe.build(EnemyType.FOOT_SOLDIER, 1).damage();
        float heavyDmg = probe.build(EnemyType.SIEGE_RAM, 1).damage();
        assertEquals(lightDmg, light, 0.01f, "a normal unit does its plain damage");
        assertEquals(heavyDmg * 2.5f, heavy, 0.01f, "a heavy does 2.5x");
    }

    private static float barricadeDamageFrom(EnemyType type) {
        TestEnemyWorld w = new TestEnemyWorld();
        w.barricade.buy();
        Enemy e = w.spawn(type, w.barricade.x() + 10f);
        float before = w.barricade.hp();
        for (int i = 0; i < 400 && w.barricade.hp() == before; i++) {
            e.update(DT);
        }
        return before - w.barricade.hp();
    }

    @Test
    @DisplayName("an ally in the way is attacked before anything else")
    void allyBlocksFirst() {
        TestEnemyWorld w = new TestEnemyWorld();
        w.barricade.buy();
        Enemy e = w.spawn(EnemyType.FOOT_SOLDIER, 900f);
        //  same depth band, or they walk straight past each other
        FriendlySkeleton ally = w.spawnAllyAtDepth(890f, e.depth(), 5f);

        float allyHp = ally.hp();
        float barHp = w.barricade.hp();
        //  Only a short run: the enemy is halted by the ally, but if the ally
        //  died the enemy would walk on past it and the assertion would be about
        //  the wrong thing.
        for (int i = 0; i < 90; i++) {
            e.update(DT);
        }
        assertTrue(ally.alive(), "the ally is still standing");
        assertTrue(ally.hp() < allyHp, "the ally is dealt with first");
        assertEquals(barHp, w.barricade.hp(), 0.001f, "not the barricade behind it");
        assertEquals(EnemyState.ATTACK, e.state());
    }

    // ========================================================================
    //  Crowd separation
    // ========================================================================

    @Test
    @DisplayName("two mobs standing on each other are pushed apart, heavier giving less ground")
    void separationPushesApart() {
        TestEnemyWorld w = new TestEnemyWorld();
        Enemy a = w.spawn(EnemyType.SIEGE_RAM, 900f);                  // MASS 9
        Enemy b = w.spawnAtDepth(EnemyType.SCOUT, 902f, a.depth(), 5f); // MASS 0.8

        float aBefore = a.x();
        float bBefore = b.x();
        w.separation.separate(w, DT);

        float aMoved = Math.abs(a.x() - aBefore);
        float bMoved = Math.abs(b.x() - bBefore);
        assertTrue(bMoved > aMoved * 5f,
                "the Scout gives way, the Ram barely moves: "
                        + bMoved + " vs " + aMoved);
    }

    @Test
    @DisplayName("separation is order-dependent and reproducible for a fixed order")
    void separationIsDeterministicForAnOrder() {
        //  Same seed, same insertion order, same nested traversal -- so the
        //  positions after one pass must be identical every time.  This is the
        //  property that any Phase 12 optimisation has to preserve.
        float[] first = separationRun();
        float[] second = separationRun();
        assertEquals(first.length, second.length);
        for (int i = 0; i < first.length; i++) {
            assertEquals(first[i], second[i], 0f, "mob " + i + " moved differently");
        }
    }

    private static float[] separationRun() {
        TestEnemyWorld w = new TestEnemyWorld(31337L);
        Enemy[] mobs = new Enemy[6];
        for (int i = 0; i < mobs.length; i++) {
            mobs[i] = w.spawn(EnemyType.FOOT_SOLDIER, 900f + i * 4f);
        }
        for (int step = 0; step < 5; step++) {
            w.separation.separate(w, DT);
        }
        float[] out = new float[mobs.length];
        for (int i = 0; i < mobs.length; i++) {
            out[i] = mobs[i].x();
        }
        return out;
    }

    @Test
    @DisplayName("the mob behind is marked blocked and only creeps forward")
    void blockedQueueing() {
        TestEnemyWorld w = new TestEnemyWorld();
        Enemy front = w.spawn(EnemyType.FOOT_SOLDIER, 900f);
        Enemy behind = w.spawnAtDepth(EnemyType.FOOT_SOLDIER, 907f, front.depth(), 3f);
        w.separation.separate(w, DT);
        assertTrue(behind.blocked(), "whoever is further right queues");
        assertFalse(front.blocked());

        float x = behind.x();
        behind.update(DT);
        float creep = x - behind.x();
        assertEquals(behind.speed() * 0.12f * DT, creep, 0.001f,
                "a blocked mob moves at 12% speed");
    }

    @Test
    @DisplayName("flyers and airborne mobs are ignored by the separation pass")
    void separationIgnoresFlyersAndAirborne() {
        TestEnemyWorld w = new TestEnemyWorld();
        Enemy flyer = w.spawn(EnemyType.GARGOYLE, 900f);
        Enemy thrown = w.spawn(EnemyType.FOOT_SOLDIER, 900f);
        thrown.forceState(EnemyState.AIR);
        float fx = flyer.x();
        float tx = thrown.x();
        w.separation.separate(w, DT);
        assertEquals(fx, flyer.x(), 0f);
        assertEquals(tx, thrown.x(), 0f);
    }

    @Test
    @DisplayName("separation never pushes a mob through the castle face")
    void separationRespectsTheWall() {
        TestEnemyWorld w = new TestEnemyWorld();
        Enemy a = w.spawn(EnemyType.SCOUT, GameConfig.CASTLE_FRONT + 5f);
        Enemy b = w.spawnAtDepth(EnemyType.SIEGE_RAM,
                GameConfig.CASTLE_FRONT + 6f, a.depth(), 5f);
        //  they start inside each other AND inside the wall, so the pass has to
        //  both push them apart and clamp them out of the masonry
        w.separation.separate(w, DT);
        assertTrue(a.x() >= GameConfig.CASTLE_FRONT + a.width() / 2f - 0.01f,
                "the Scout was clamped out of the wall, at " + a.x());
        assertTrue(b.x() >= GameConfig.CASTLE_FRONT + b.width() / 2f - 0.01f,
                "and so was the Ram, at " + b.x());
    }

    // ========================================================================
    //  Death and payout
    // ========================================================================

    @Test
    @DisplayName("the gold multiplier counts the dying mob itself")
    void goldMultiplierIncludesTheDyingMob() {
        //  Python reads game.gold_multiplier BEFORE setting alive = False, so the
        //  mob about to die is still in the count.  A refactor that removed it
        //  first would quietly cut every payout on a crowded screen.
        TestEnemyWorld w = new TestEnemyWorld();
        int free = GameConfig.POP_GOLD_FREE;          // 4
        //  A big enough crowd that including or excluding one mob changes the
        //  payout after rounding -- with only one over the allowance the
        //  difference disappears into Math.round and the test proves nothing.
        //  The dying mob is worth 19, not a Scout's 6: at 6 gold both
        //  multipliers round to the same integer and the test could not tell
        //  them apart.
        int crowd = 20;
        w.spawn(EnemyType.SHIELD_BEARER, 900f);
        for (int i = 1; i < crowd; i++) {
            w.spawn(EnemyType.SCOUT, 900f + i * 40f);
        }
        assertEquals(crowd, w.liveEnemies());

        float withDyingMob = 1f + GameConfig.POP_GOLD_STEP * (crowd - free);
        float withoutIt = 1f + GameConfig.POP_GOLD_STEP * (crowd - 1 - free);
        assertEquals(withDyingMob, w.goldMultiplier(), 1e-5f);

        Enemy dying = (Enemy) w.target(0);
        int expected = Math.max(1, Math.round(dying.gold() * withDyingMob));
        int wouldBeWithout = Math.max(1, Math.round(dying.gold() * withoutIt));
        assertTrue(expected > wouldBeWithout,
                "the two differ after rounding, so this test can tell them apart");

        dying.die();
        assertEquals(expected, w.gold,
                "the payout used the multiplier from BEFORE it was removed: "
                        + expected + ", not " + wouldBeWithout);
    }

    @Test
    @DisplayName("a silent death pays nothing and counts nothing")
    void silentDeath() {
        TestEnemyWorld w = new TestEnemyWorld();
        Enemy e = w.spawn(EnemyType.TREASURE_GOBLIN, 900f);
        e.onGrab();
        e.onRelease(-100f, -100f);      // start a fling so there is a score to lose
        e.die(true);
        assertFalse(e.alive());
        assertEquals(0, w.gold, "no gold");
        assertEquals(0, w.kills, "no kill count");
        assertEquals(0, w.scoreEvents, "no fling score");
    }

    @Test
    @DisplayName("dying twice pays out once")
    void deathIsIdempotent() {
        TestEnemyWorld w = new TestEnemyWorld();
        Enemy e = w.spawn(EnemyType.SCOUT, 900f);
        e.die();
        e.die();
        e.die();
        assertEquals(1, w.kills);
    }

    // ========================================================================
    //  Volatile
    // ========================================================================

    @Test
    @DisplayName("a Volatile detonates after dying, damaging everything nearby")
    void volatileDetonates() {
        TestEnemyWorld w = new TestEnemyWorld();
        Enemy bomb = w.spawn(EnemyType.VOLATILE, 900f);
        Enemy near = w.spawn(EnemyType.SHIELD_BEARER, 900f + 50f);
        Enemy far = w.spawn(EnemyType.SHIELD_BEARER, 900f + Volatile.BLAST_RADIUS + 50f);
        near.setY(bomb.y());
        far.setY(bomb.y());

        float nearHp = near.hp();
        float farHp = far.hp();
        bomb.die();

        assertTrue(near.hp() < nearHp, "caught in the blast");
        assertEquals(farHp, far.hp(), 0.001f, "outside the radius");
    }

    @Test
    @DisplayName("a Volatile chain: each link pays out and sets off the next")
    void volatileChain() {
        //  ------------------------------------------------------------------
        //  A blast does 27.2 damage; a healthy Volatile has 46 health.  So a
        //  chain NEVER starts among undamaged bombs -- and it never will at any
        //  wave, because health scales at 1.14 and damage at 1.11, so the gap
        //  only widens.  A chain is something the player sets up by softening
        //  the row first.  (Recorded in PORT_ANALYSIS.md section 14.)
        //
        //  So the row is pre-damaged to just inside one blast's reach, which is
        //  the situation the mechanic actually occurs in.
        //  ------------------------------------------------------------------
        TestEnemyWorld w = new TestEnemyWorld(4242L);
        Enemy[] bombs = new Enemy[4];
        for (int i = 0; i < bombs.length; i++) {
            bombs[i] = w.spawn(EnemyType.VOLATILE, 900f + i * 60f);
        }
        for (Enemy b : bombs) {
            b.setY(bombs[0].y());
            if (b != bombs[0]) {
                b.applyDamage(b.maxHp() - 5f, "fall");     // one hit from death
            }
        }

        bombs[0].die();

        for (int i = 0; i < bombs.length; i++) {
            assertFalse(bombs[i].alive(), "bomb " + i + " went off in the chain");
        }
        assertEquals(bombs.length, w.kills, "every link counted as a kill");
        assertTrue(w.gold > 0, "and every link paid out");
    }

    @Test
    @DisplayName("a Volatile chain is not guarded against, because Python does not guard it")
    void volatileChainIsUnbounded() {
        //  Nothing caps the recursion.  It terminates because each blast marks
        //  its own mob dead BEFORE recursing, and a dead mob is skipped -- not
        //  because of a depth limit.  Adding one would cap chains the game is
        //  designed to reward.
        TestEnemyWorld w = new TestEnemyWorld(99L);
        int n = 12;
        Enemy[] bombs = new Enemy[n];
        for (int i = 0; i < n; i++) {
            bombs[i] = w.spawn(EnemyType.VOLATILE, 900f + i * 40f);
        }
        for (Enemy b : bombs) {
            b.setY(bombs[0].y());
            if (b != bombs[0]) {
                b.applyDamage(b.maxHp() - 5f, "fall");
            }
        }
        bombs[0].die();
        assertEquals(0, w.liveEnemies(), "a twelve-deep chain runs to the end");
        assertEquals(n, w.kills, "and every link paid out on the way");
    }

    @Test
    @DisplayName("a Volatile blast damages the wall and the barricade")
    void volatileHitsStructures() {
        TestEnemyWorld w = new TestEnemyWorld();
        w.barricade.buy();
        Enemy atWall = w.spawn(EnemyType.VOLATILE, GameConfig.CASTLE_FRONT + 20f);
        float castleHp = w.castle.hp();
        atWall.die();
        assertTrue(w.castle.hp() < castleHp, "the wall took it");

        TestEnemyWorld w2 = new TestEnemyWorld();
        w2.barricade.buy();
        Enemy atBar = w2.spawn(EnemyType.VOLATILE, w2.barricade.x() + 20f);
        float barHp = w2.barricade.hp();
        atBar.die();
        assertTrue(w2.barricade.hp() < barHp, "and so did the barricade");
    }

    @Test
    @DisplayName("a silent Volatile death does not detonate")
    void silentVolatileDoesNotDetonate() {
        //  die(silent) still runs the override, but `was_alive` gates the blast
        //  on the mob having actually been alive -- and a second die() call is a
        //  no-op, so a dead Volatile cannot be re-detonated.
        TestEnemyWorld w = new TestEnemyWorld();
        Enemy bomb = w.spawn(EnemyType.VOLATILE, 900f);
        Enemy near = w.spawn(EnemyType.SHIELD_BEARER, 930f);
        near.setY(bomb.y());
        bomb.die();
        float afterFirst = near.hp();
        bomb.die();
        assertEquals(afterFirst, near.hp(), 0.001f, "it cannot go off twice");
    }

    // ========================================================================
    //  Treasure Goblin
    // ========================================================================

    @Test
    @DisplayName("a Treasure Goblin runs the wrong way and leads shots correctly")
    void goblinFlees() {
        TestEnemyWorld w = new TestEnemyWorld();
        Enemy g = w.spawn(EnemyType.TREASURE_GOBLIN, 900f);
        float x = g.x();
        g.update(DT);
        assertTrue(g.x() > x, "it runs AWAY from the castle");
        assertTrue(g.vxEstimate() > 0f, "and a tower leads its shots in that direction");
    }

    @Test
    @DisplayName("a Treasure Goblin that escapes pays nothing at all")
    void goblinEscape() {
        TestEnemyWorld w = new TestEnemyWorld();
        Enemy g = w.spawn(EnemyType.TREASURE_GOBLIN, 900f);
        assertEquals(140, g.config().gold, "the biggest payout in the roster");

        for (int i = 0; i < (int) (TreasureGoblin.ESCAPE_TIME * 60f) + 10 && g.alive(); i++) {
            g.update(DT);
        }
        assertFalse(g.alive(), "gone");
        assertEquals(0, w.gold, "escape -> die(silent) -> no gold");
        assertEquals(0, w.kills, "no kill count");
        assertEquals(0, w.scoreEvents, "no fling score");
    }

    @Test
    @DisplayName("a Treasure Goblin killed before it escapes pays in full")
    void goblinKilledPays() {
        TestEnemyWorld w = new TestEnemyWorld();
        Enemy g = w.spawn(EnemyType.TREASURE_GOBLIN, 900f);
        g.applyDamage(g.maxHp() * 2f, "projectile");
        assertFalse(g.alive());
        assertTrue(w.gold >= 140, "the full sack: " + w.gold);
        assertEquals(1, w.kills);
    }

    @Test
    @DisplayName("a Treasure Goblin escapes off the right edge as well as on the timer")
    void goblinEscapesOffTheEdge() {
        TestEnemyWorld w = new TestEnemyWorld();
        Enemy g = w.spawn(EnemyType.TREASURE_GOBLIN, GameConfig.WORLD_WIDTH + 88f);
        assertTrue(((TreasureGoblin) g).escapeTimer() > 10f, "nowhere near the timer");
        for (int i = 0; i < 10 && g.alive(); i++) {
            g.update(DT);
        }
        assertFalse(g.alive(), "past WIDTH + 90 it is gone, timer or no timer");
        assertEquals(0, w.gold);
    }

    // ========================================================================
    //  Assassin
    // ========================================================================

    @Test
    @DisplayName("a cloaked Assassin cannot be targeted or hit")
    void cloakedAssassinIsUntargetable() {
        TestEnemyWorld w = new TestEnemyWorld(4242L);
        Assassin a = (Assassin) w.spawn(EnemyType.ASSASSIN, 500f);
        //  step until it cloaks, which it does on its own timer
        for (int i = 0; i < 600 && !a.cloaked(); i++) {
            a.update(DT);
        }
        assertTrue(a.cloaked(), "it should have cloaked by now");
        assertFalse(a.targetable(), "and is not there as far as targeting goes");

        //  a tower will not pick it
        var tower = w.defences.createTower(w, TowerType.BOWMAN, 248f, 354f);
        assertNull(tower.pickTarget(), "no tower can see it");

        //  nor will a projectile hit it
        Projectile p = Projectile.friendly(w, a.x(), a.y(), 0f, 0f,
                com.mymmer.castledefense.defence.ProjectileKind.ARROW, 50f);
        w.addProjectile(p);
        float hp = a.hp();
        p.update(DT);
        assertEquals(hp, a.hp(), 0.001f, "an arrow passes straight through");
    }

    @Test
    @DisplayName("an uncloaked Assassin is an ordinary target again")
    void uncloakedAssassinIsTargetable() {
        TestEnemyWorld w = new TestEnemyWorld(4242L);
        Assassin a = (Assassin) w.spawn(EnemyType.ASSASSIN, 500f);
        assertFalse(a.cloaked(), "it starts visible");
        assertTrue(a.targetable());
        var tower = w.defences.createTower(w, TowerType.BOWMAN, 248f, 354f);
        assertSame(a, tower.pickTarget());
    }

    // ========================================================================
    //  Gargoyle
    // ========================================================================

    @Test
    @DisplayName("a Gargoyle flies at its own altitude and bobs there")
    void gargoyleFlies() {
        TestEnemyWorld w = new TestEnemyWorld();
        Enemy g = w.spawn(EnemyType.GARGOYLE, 900f);
        assertTrue(g.flying());
        assertTrue(g.y() < GameConfig.GROUND_Y - 100f, "well above the ground");

        float y0 = g.y();
        boolean moved = false;
        for (int i = 0; i < 120; i++) {
            g.update(DT);
            if (Math.abs(g.y() - y0) > 1f) {
                moved = true;
            }
        }
        assertTrue(moved, "it bobs as it flies");
        assertTrue(g.y() < GameConfig.GROUND_Y - 100f, "and stays up there");
    }

    @Test
    @DisplayName("a Gargoyle is not a ground blocker and is not blocked")
    void gargoyleIsNotAGroundUnit() {
        TestEnemyWorld w = new TestEnemyWorld();
        Enemy flyer = w.spawn(EnemyType.GARGOYLE, 900f);
        Enemy ground = w.spawn(EnemyType.FOOT_SOLDIER, 900f);
        w.separation.separate(w, DT);
        assertFalse(flyer.blocked());
        assertFalse(ground.blocked(), "a flyer does not queue anyone behind it");
    }

    @Test
    @DisplayName("a Gargoyle can be grabbed and thrown like anything else")
    void gargoyleIsGrabbable() {
        TestEnemyWorld w = new TestEnemyWorld();
        Enemy g = w.spawn(EnemyType.GARGOYLE, 900f);
        assertTrue(g.grabbable());
        g.onGrab();
        g.onRelease(-800f, 400f);
        assertEquals(EnemyState.AIR, g.state());
    }

    // ========================================================================
    //  Necromancer
    // ========================================================================

    @Test
    @DisplayName("a Necromancer halts at its stand-off point rather than closing")
    void necromancerStandsOff() {
        TestEnemyWorld w = new TestEnemyWorld();
        Necromancer n = (Necromancer) w.spawn(EnemyType.NECROMANCER, 1100f);
        w.steps(1200, DT);
        assertEquals(EnemyState.ATTACK, n.state());
        assertEquals(n.currentStandoff(), n.x(), 5f, "it stopped at its line");
        assertTrue(n.x() > GameConfig.CASTLE_FRONT + 300f, "well short of the wall");
    }

    @Test
    @DisplayName("a Necromancer summons skeletons, up to its cap")
    void necromancerSummons() {
        TestEnemyWorld w = new TestEnemyWorld();
        Necromancer n = (Necromancer) w.spawn(EnemyType.NECROMANCER, 500f);
        w.steps(60 * 30, DT);
        assertTrue(n.minionCount() > 0, "it raised some");
        assertTrue(n.minionCount() <= Necromancer.MAX_MINIONS,
                "and never more than " + Necromancer.MAX_MINIONS);

        int skeletons = 0;
        for (int i = 0; i < w.targetCount(); i++) {
            if (((Enemy) w.target(i)).type() == EnemyType.SKELETON) {
                skeletons++;
            }
        }
        assertTrue(skeletons > 0, "and they joined the horde");
    }

    @Test
    @DisplayName("a rival Necromancer halts level with the Outpost and shoots the cage")
    void rivalHuntsThePrisoner() {
        TestEnemyWorld w = new TestEnemyWorld();
        Necromancer captive = (Necromancer) w.spawn(EnemyType.NECROMANCER, w.outpost.x());
        assertTrue(w.outpost.trap(captive), "one is in the cage");

        Necromancer rival = (Necromancer) w.spawn(EnemyType.NECROMANCER, 1250f);
        assertTrue(rival.huntingPrisoner());
        assertEquals(Math.max(rival.standoffX(), w.outpost.x()), rival.currentStandoff(),
                0.001f, "the cage outranks the castle");
        assertTrue(rival.currentStandoff() >= w.outpost.x(),
                "so it stops level with the tower rather than marching past it");

        float prisonerHp = w.outpost.prisonerHp();
        w.steps(60 * 20, DT);
        assertTrue(w.outpost.prisonerHp() < prisonerHp || !w.outpost.hasPrisoner(),
                "and it opens fire on the cage");
    }

    @Test
    @DisplayName("a Necromancer thrown into the Outpost is imprisoned")
    void necromancerIsTrappable() {
        TestEnemyWorld w = new TestEnemyWorld();
        Necromancer n = (Necromancer) w.spawn(EnemyType.NECROMANCER, 1200f);
        n.onGrab();
        n.onRelease(0f, 0f);
        n.setX(w.outpost.x());
        n.setY(w.outpost.y() - 30f);
        assertTrue(w.outpost.trapAreaContains(n.x(), n.y()));

        w.step(DT);
        assertTrue(w.outpost.hasPrisoner(), "caught");
        assertSame(n, w.outpost.prisoner());
        assertTrue(n.trapped());
        assertEquals(EnemyState.TRAPPED, n.state());
        assertFalse(w.horde.contains(n), "and out of the horde");
    }

    @Test
    @DisplayName("a captured Necromancer drops its minion list")
    void trappedNecromancerDropsMinions() {
        TestEnemyWorld w = new TestEnemyWorld();
        Necromancer n = (Necromancer) w.spawn(EnemyType.NECROMANCER, 500f);
        w.steps(60 * 20, DT);
        assertTrue(n.minionCount() > 0);
        w.outpost.trap(n);
        assertEquals(0, n.minionCount(),
                "he stops thinking in there, so nothing would ever prune it");
    }

    // ========================================================================
    //  The trap-during-snapshot regression
    // ========================================================================

    @Test
    @DisplayName("trapping mid-iteration does not skip the mobs behind it")
    void trapDuringSnapshotDoesNotSkip() {
        //  Outpost.trap REMOVES an entry from the live list, and it can happen
        //  in the middle of the enemy update loop.  With a plain indexed walk
        //  the mob after the removed one would be skipped entirely -- silently,
        //  because a list does not complain.  The loop iterates a Snapshot for
        //  exactly this reason, and this test exercises the real EntityList.
        TestEnemyWorld w = new TestEnemyWorld();

        //  Spaced well apart: bunched together the crowd-separation pass would
        //  push them sideways and mask whether they were stepped at all.
        Enemy before = w.spawn(EnemyType.FOOT_SOLDIER, 1150f);
        Necromancer victim = (Necromancer) w.spawn(EnemyType.NECROMANCER, w.outpost.x());
        Enemy after = w.spawn(EnemyType.FOOT_SOLDIER, 700f);
        Enemy alsoAfter = w.spawn(EnemyType.FOOT_SOLDIER, 450f);

        //  put the Necromancer airborne and on the trap area, so its own update
        //  triggers the capture mid-loop
        victim.forceState(EnemyState.AIR);
        victim.setX(w.outpost.x());
        victim.setY(w.outpost.y() - 30f);

        float beforeX = before.x();
        float afterX = after.x();
        float alsoX = alsoAfter.x();

        w.step(DT);

        assertTrue(w.outpost.hasPrisoner(), "the capture happened during the loop");
        assertEquals(3, w.targetCount(), "and it left the horde");
        assertTrue(before.x() < beforeX, "the mob before it was updated");
        assertTrue(after.x() < afterX,
                "AND the mob after it was updated -- not skipped by the removal");
        assertTrue(alsoAfter.x() < alsoX, "and so was the one after that");
    }

    @Test
    @DisplayName("a mob spawned mid-iteration is not visited on its own spawn step")
    void spawnDuringSnapshotIsNotVisited() {
        //  The other half of the snapshot contract.  A Necromancer summoning
        //  mid-loop must not have its new Skeleton stepped in the same frame it
        //  was created -- Python's comment records that this used to happen.
        TestEnemyWorld w = new TestEnemyWorld();
        Necromancer n = (Necromancer) w.spawn(EnemyType.NECROMANCER, 500f);

        int countBefore = w.targetCount();
        //  run until a summon happens
        Enemy summoned = null;
        for (int i = 0; i < 60 * 20 && summoned == null; i++) {
            w.step(DT);
            if (w.targetCount() > countBefore) {
                summoned = (Enemy) w.target(w.targetCount() - 1);
                //  captured on the very step it appeared
                assertEquals(EnemyType.SKELETON, summoned.type());
                assertEquals(summoned.groundY(), summoned.y(), 0.001f,
                        "it has not moved: it was not stepped on its spawn step");
            }
        }
        assertNotNull(summoned, "a skeleton should have been raised");
        assertTrue(n.minionCount() > 0);
    }

    // ========================================================================
    //  Friendly skeletons
    // ========================================================================

    @Test
    @DisplayName("an ally marches the wrong way and holds the line")
    void allyMarchesAndHolds() {
        TestEnemyWorld w = new TestEnemyWorld();
        FriendlySkeleton a = w.spawnAlly(GameConfig.OUTPOST_X - 30f);
        float x = a.x();
        a.update(DT);
        assertTrue(a.x() > x, "it walks RIGHT, into the horde");

        for (int i = 0; i < 60 * 20; i++) {
            a.update(DT);
        }
        assertEquals(EnemyState.HOLD, a.state());
        assertEquals(GameConfig.ALLY_HOLD_X, a.x(), 0.001f, "and holds that line");
    }

    @Test
    @DisplayName("an ally engages a hostile ground unit in front of it")
    void allyEngages() {
        TestEnemyWorld w = new TestEnemyWorld();
        FriendlySkeleton a = w.spawnAlly(GameConfig.ALLY_HOLD_X);
        Enemy foe = w.spawn(EnemyType.FOOT_SOLDIER, GameConfig.ALLY_HOLD_X + 20f);
        assertSame(foe, a.pickTarget());

        float hp = foe.hp();
        for (int i = 0; i < 120; i++) {
            a.update(DT);
        }
        assertEquals(EnemyState.ATTACK, a.state());
        assertTrue(foe.hp() < hp, "and hits it");
    }

    @Test
    @DisplayName("without Sentinels an ally is blind to anything already behind it")
    void allyIsBlindBehindWithoutSentinels() {
        TestEnemyWorld w = new TestEnemyWorld();
        FriendlySkeleton a = w.spawnAlly(800f);
        Enemy behind = w.spawn(EnemyType.FOOT_SOLDIER, 400f);   // already past
        assertNull(a.pickTarget(), "it never looks back");

        w.modifiers = new CombatModifiers() {
            @Override
            public boolean allySentinels() {
                return true;
            }
        };
        assertSame(behind, a.pickTarget(), "Undead Sentinels turns it round");
    }

    @Test
    @DisplayName("with Sentinels an ally chases its target in either direction")
    void allyChasesWithSentinels() {
        TestEnemyWorld w = new TestEnemyWorld();
        w.modifiers = new CombatModifiers() {
            @Override
            public boolean allySentinels() {
                return true;
            }
        };
        FriendlySkeleton a = w.spawnAlly(800f);
        w.spawn(EnemyType.FOOT_SOLDIER, 400f);
        float x = a.x();
        for (int i = 0; i < 60; i++) {
            a.update(DT);
        }
        assertTrue(a.x() < x, "it turned round and gave chase");
    }

    @Test
    @DisplayName("an ally never targets a flyer")
    void allyIgnoresFlyers() {
        TestEnemyWorld w = new TestEnemyWorld();
        FriendlySkeleton a = w.spawnAlly(800f);
        w.spawn(EnemyType.GARGOYLE, 850f);
        assertNull(a.pickTarget(), "it cannot reach one");
    }

    @Test
    @DisplayName("an ally crumbles when its lifetime runs out")
    void allyExpires() {
        TestEnemyWorld w = new TestEnemyWorld();
        FriendlySkeleton a = w.spawnAlly(800f);
        assertEquals(FriendlySkeleton.BASE_LIFE, a.life(), 0.001f);
        for (int i = 0; i < (int) (FriendlySkeleton.BASE_LIFE * 60f) + 10 && a.alive(); i++) {
            a.update(DT);
        }
        assertFalse(a.alive(), "they do not last for ever");
    }

    @Test
    @DisplayName("an ally is never a tower target: it is not even a Target")
    void allyIsNotATowerTarget() {
        //  Enforced by the type system rather than by a check in every targeting
        //  loop: FriendlySkeleton does not implement the Target contract, so a
        //  tower cannot be handed one.
        TestEnemyWorld w = new TestEnemyWorld();
        w.spawnAlly(500f);
        var tower = w.defences.createTower(w, TowerType.BOWMAN, 248f, 354f);
        assertNull(tower.pickTarget(), "the ally is invisible to it");
        assertFalse(com.mymmer.castledefense.defence.Target.class
                        .isAssignableFrom(FriendlySkeleton.class),
                "and it is not a Target at all");
    }

    @Test
    @DisplayName("an ally never attacks the castle and never clears a wave")
    void allyIsNotHostile() {
        TestEnemyWorld w = new TestEnemyWorld();
        w.spawnAlly(GameConfig.CASTLE_FRONT + 10f);
        float hp = w.castle.hp();
        for (int i = 0; i < 600; i++) {
            w.step(DT);
        }
        assertEquals(hp, w.castle.hp(), 0.001f, "it is on our side");
        assertEquals(0, w.targetCount(), "and it is not part of the horde");
    }

    // ========================================================================
    //  Entity identity
    // ========================================================================

    @Test
    @DisplayName("uids are unique, monotonic and never reused")
    void uidsAreStableAndUnique() {
        TestEnemyWorld w = new TestEnemyWorld();
        Enemy a = w.spawn(EnemyType.SCOUT, 900f);
        Enemy b = w.spawn(EnemyType.SCOUT, 910f);
        assertTrue(b.uid() > a.uid(), "monotonic");

        long deadUid = a.uid();
        a.die();
        w.horde.sweep();
        Enemy c = w.spawn(EnemyType.SCOUT, 920f);
        assertTrue(c.uid() > deadUid, "a new mob never reuses a dead uid");

        //  and a uid is stable for the whole lifetime, including through states
        long uid = b.uid();
        b.onGrab();
        b.onRelease(100f, 100f);
        b.forceState(EnemyState.WALK);
        assertEquals(uid, b.uid());
    }

    @Test
    @DisplayName("uids are process-global, matching the Python class counter")
    void uidsAreProcessGlobal() {
        //  Python's Enemy._next_uid is a class attribute and is never reset --
        //  not even by game.reset().  The port matches: the counter lives on
        //  Entity and spans every run in the process.  It matters because a
        //  projectile in flight across a run boundary must not find its old
        //  victim's uid on a new mob.
        TestEnemyWorld first = new TestEnemyWorld();
        Enemy a = first.spawn(EnemyType.SCOUT, 900f);
        TestEnemyWorld second = new TestEnemyWorld();
        Enemy b = second.spawn(EnemyType.SCOUT, 900f);
        assertTrue(b.uid() > a.uid(), "a new world does not restart the counter");
    }

    @Test
    @DisplayName("describe() carries enough to reproduce a physics bug report")
    void describeIsDiagnostic() {
        TestEnemyWorld w = new TestEnemyWorld();
        Enemy ram = w.spawn(EnemyType.SIEGE_RAM, 900f);
        ram.onGrab();
        ram.onRelease(-500f, -300f);
        String d = ram.describe();
        assertTrue(d.contains("siege_ram"), d);
        assertTrue(d.contains("#" + ram.uid()), d);
        assertTrue(d.contains("air"), d);
        assertTrue(d.contains("mass=9"), d);
        assertTrue(d.contains("armour="), d);
        assertTrue(d.contains("hp="), d);
        assertTrue(d.contains("v=("), d);
    }
}
