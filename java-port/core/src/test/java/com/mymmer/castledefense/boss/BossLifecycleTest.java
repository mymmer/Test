package com.mymmer.castledefense.boss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mymmer.castledefense.config.GameConfig;
import com.mymmer.castledefense.defence.Projectile;
import com.mymmer.castledefense.defence.ProjectileKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Boss lifecycle: purging, repeat spawns, and two bosses at once.
 *
 * <p>The most correctness-sensitive part of the phase. A boss leaves references
 * in five places, and every one of them is a <em>gameplay</em> bug before it is a
 * memory leak: a stale cursor reference means the player is holding a corpse, and
 * a surviving projectile means a dead boss is still shooting. The Java GC solves
 * neither.
 */
class BossLifecycleTest {

    private static final double DT = TestBossWorld.DT;

    /** A hostile projectile attributed to a uid, as a boss's own would be. */
    private static Projectile shotBy(TestBossWorld w, long ownerUid) {
        Projectile p = new Projectile(w, 400f, 200f, -50f, 0f, ProjectileKind.MAGIC,
                10f, 0f, 0, 0f, true, 20f, 0f, 1f, 1f, false, ownerUid);
        w.world.projectiles.add(p);
        return p;
    }

    // ========================================================================
    //  Purge
    // ========================================================================

    @Test
    @DisplayName("a purge drops every reference the run holds to a boss")
    void purgeDropsEverything() {
        TestBossWorld w = new TestBossWorld();
        TrollKing troll = (TrollKing) w.summon(BossType.TROLL_KING);

        //  give the run every kind of dangling reference to that boss
        float[] anchor = w.anchorOf(troll);
        assertTrue(w.press(anchor[0], anchor[1]));
        assertNotNull(w.cursor.heldItem(), "the cursor is carrying its crown");
        Projectile itsShot = shotBy(w, troll.uid());
        long uid = troll.uid();

        assertTrue(w.world.horde.contains(troll));
        assertEquals(1, w.liveItems());
        assertEquals(1, w.projectilesOwnedBy(uid));

        w.registry.purge(troll, w.world.horde, w.world.projectiles, w.items);

        assertFalse(troll.isAlive(), "the boss is dead");
        assertFalse(w.world.horde.contains(troll), "and off the field");
        assertNull(w.cursor.heldItem(), "the cursor let go of its crown");
        assertEquals(0, w.liveItems(), "and the crown is gone");
        assertFalse(itsShot.isAlive(), "its fire went out with it");
        assertEquals(0, w.projectilesOwnedBy(uid));
        assertFalse(w.registry.isLive(troll));
        assertEquals(0, w.registry.liveCount());
    }

    @Test
    @DisplayName("a purge is idempotent and survives an unregistered boss")
    void purgeIsSafeToRepeat() {
        TestBossWorld w = new TestBossWorld();
        Boss b = w.summon(BossType.DRAGON);
        w.registry.purge(b, w.world.horde, w.world.projectiles, w.items);
        w.registry.purge(b, w.world.horde, w.world.projectiles, w.items);
        w.registry.purge(null, w.world.horde, w.world.projectiles, w.items);

        Boss never = w.bosses.create(w, BossType.LICH_LORD, 1, 900f, null);
        w.registry.purge(never, w.world.horde, w.world.projectiles, w.items);
        assertFalse(never.isAlive());
    }

    @Test
    @DisplayName("dying purges the boss automatically on the next step")
    void deathPurgesOnStep() {
        TestBossWorld w = new TestBossWorld();
        Boss b = w.summon(BossType.TROLL_KING);
        shotBy(w, b.uid());
        b.die();
        w.step(DT);
        assertFalse(w.world.horde.contains(b));
        assertEquals(0, w.projectilesOwnedBy(b.uid()));
    }

    @Test
    @DisplayName("a dying boss releases its own regalia")
    void deathReleasesRegalia() {
        TestBossWorld w = new TestBossWorld();
        LichLord lich = (LichLord) w.summon(BossType.LICH_LORD);
        lich.detachRegalia();
        DroppedItem staff = lich.staffItem();
        assertNotNull(staff);
        assertTrue(staff.isAlive());

        lich.die();
        assertFalse(staff.isAlive(), "its staff went with it");
        assertNull(lich.staffItem());
    }

    // ========================================================================
    //  Projectile ownership
    // ========================================================================

    @Test
    @DisplayName("killing one boss does not remove another boss's projectiles")
    void purgeRespectsProjectileOwnership() {
        TestBossWorld w = new TestBossWorld();
        Boss a = w.summon(BossType.TROLL_KING, 900f);
        Boss b = w.summon(BossType.DRAGON, 1000f);

        Projectile shotA = shotBy(w, a.uid());
        Projectile shotB = shotBy(w, b.uid());
        Projectile unowned = shotBy(w, 0L);

        w.registry.purge(a, w.world.horde, w.world.projectiles, w.items);

        assertFalse(shotA.isAlive(), "A's fire goes out with A");
        assertTrue(shotB.isAlive(), "B's does NOT");
        assertTrue(unowned.isAlive(), "and neither does anything unowned");
        assertEquals(1, w.projectilesOwnedBy(b.uid()));
        assertTrue(w.registry.isLive(b));
    }

    @Test
    @DisplayName("killing one boss does not take another boss's dropped item")
    void purgeRespectsItemOwnership() {
        TestBossWorld w = new TestBossWorld();
        TrollKing a = (TrollKing) w.summon(BossType.TROLL_KING, 900f);
        LichLord b = (LichLord) w.summon(BossType.LICH_LORD, 1100f);

        a.detachRegalia();
        b.detachRegalia();
        DroppedItem crown = a.crownItem();
        DroppedItem staff = b.staffItem();
        assertEquals(2, w.liveItems());
        assertNotEqualsUid(crown, staff);

        w.registry.purge(a, w.world.horde, w.world.projectiles, w.items);

        assertFalse(crown.isAlive());
        assertTrue(staff.isAlive(), "the Lich still has a staff on the ground");
        assertEquals(1, w.liveItems());
        assertSame(staff, b.staffItem());
    }

    private static void assertNotEqualsUid(DroppedItem a, DroppedItem b) {
        assertTrue(a.ownerUid() != b.ownerUid(), "each belongs to its own boss");
    }

    // ========================================================================
    //  Repeat bosses
    // ========================================================================

    @Test
    @DisplayName("a repeat Troll King inherits nothing from the first")
    void repeatTrollKing() {
        repeatBoss(BossType.TROLL_KING);
    }

    @Test
    @DisplayName("a repeat Dragon inherits nothing from the first")
    void repeatDragon() {
        repeatBoss(BossType.DRAGON);
    }

    @Test
    @DisplayName("a repeat Lich Lord inherits nothing from the first")
    void repeatLichLord() {
        repeatBoss(BossType.LICH_LORD);
    }

    /**
     * Spawn → interact → kill → purge → spawn again, for one boss type.
     *
     * <p>Mirrors the Python self-test's "boss recurrence: no state survives a
     * boss" block, generalised across all three.
     */
    private static void repeatBoss(BossType type) {
        TestBossWorld w = new TestBossWorld();
        Boss first = w.summon(type, 900f);
        w.steps(240, DT);
        long firstUid = first.uid();

        //  give the run every kind of dangling reference
        float[] anchor = w.anchorOf(first);
        w.press(anchor[0], anchor[1]);
        if (first.isSmackTarget()) {
            w.drag(anchor[0] + GameConfig.CLAW_SMACK_DISTANCE, anchor[1]);
        }
        shotBy(w, firstUid);
        assertTrue(first.regaliaTaken() > 0 || w.cursor.heldItem() != null
                        || w.cursor.smacking() != null,
                type.id() + ": the interaction should have taken hold");

        first.die();
        w.step(DT);

        assertFalse(w.world.horde.contains(first), "a dead boss must leave the field");
        assertEquals(0, w.projectilesOwnedBy(firstUid), "its projectiles go with it");
        assertEquals(0, w.liveItems(), "and so does its regalia");
        assertNull(w.cursor.heldItem(), "including a piece the cursor was holding");
        assertNull(w.cursor.smacking(), "and anything it was battering");
        assertFalse(w.registry.anyLive(), "no boss on the field");

        //  now bring the same boss back
        Boss second = w.summon(type, 900f);
        assertNotSame(first, second, "a repeat boss is a new object");
        assertTrue(second.uid() != firstUid, "with a new uid");
        assertEquals(second.maxHp(), second.hp(), 0.001f, "at full health");
        assertEquals(0, second.regaliaTaken(), "no inherited disruption count");
        assertEquals(0f, second.regaliaCd(), 0f, "no inherited guard");
        assertTrue(second.isAlive());
        assertEquals(1, w.registry.liveCount());

        //  and its own state is fresh
        if (second instanceof TrollKing) {
            TrollKing t = (TrollKing) second;
            assertTrue(t.hasCrown(), "crowned again");
            assertNull(t.crownItem(), "with no leftover crown");
        } else if (second instanceof LichLord) {
            LichLord l = (LichLord) second;
            assertTrue(l.hasStaff(), "armed again");
            assertEquals(0f, l.disarm(), 0f, "not disarmed");
            assertEquals(0f, l.shield(), 0f, "unwarded");
            assertNull(l.staffItem());
        } else if (second instanceof Dragon) {
            Dragon d = (Dragon) second;
            assertEquals(0f, d.clawProgress(), 0f, "no inherited claw progress");
            assertEquals(0f, d.reel(), 0f, "not reeling");
            assertFalse(d.breathing());
        }

        //  and it runs without blowing up
        w.steps(600, DT);
        assertTrue(second.isAlive() || !second.isAlive(), "it ran");
    }

    @Test
    @DisplayName("summoning purges a corpse first, so a new boss never shares the field")
    void summonPurgesDeadBosses() {
        TestBossWorld w = new TestBossWorld();
        Boss dead = w.summon(BossType.DRAGON);
        dead.markDead();                    // died without a purge

        Boss fresh = w.summon(BossType.DRAGON);
        assertFalse(w.world.horde.contains(dead), "no corpse lingers");
        assertTrue(w.world.horde.contains(fresh));
        assertEquals(1, w.registry.liveCount());
    }

    // ========================================================================
    //  Simultaneous bosses
    // ========================================================================

    @Test
    @DisplayName("Troll King and Dragon coexist with independent state")
    void twoBossesTrollAndDragon() {
        twoBosses(BossType.TROLL_KING, BossType.DRAGON);
    }

    @Test
    @DisplayName("Troll King and Lich Lord coexist with independent state")
    void twoBossesTrollAndLich() {
        twoBosses(BossType.TROLL_KING, BossType.LICH_LORD);
    }

    @Test
    @DisplayName("Dragon and Lich Lord coexist with independent state")
    void twoBossesDragonAndLich() {
        twoBosses(BossType.DRAGON, BossType.LICH_LORD);
    }

    private static void twoBosses(BossType typeA, BossType typeB) {
        TestBossWorld w = new TestBossWorld();
        Boss a = w.summon(typeA, 900f);
        Boss b = w.summon(typeB, 1150f);
        assertEquals(2, w.registry.liveCount(), "two bosses is an ordinary case");
        assertSame(a, w.registry.liveBosses().get(0), "and the list keeps arrival order");
        assertSame(b, w.registry.liveBosses().get(1));

        //  independent health
        a.applyDamage(a.maxHp() * 0.5f, "fall");
        assertTrue(a.hp() < a.maxHp());
        assertEquals(b.maxHp(), b.hp(), 0.001f, "B is untouched");

        //  independent disruption state
        if (a.isSmackTarget()) {
            a.applySmack(GameConfig.CLAW_SMACK_DISTANCE);
        } else {
            a.detachRegalia();
        }
        assertEquals(1, a.regaliaTaken());
        assertEquals(0, b.regaliaTaken(), "B's disruption state is its own");
        assertEquals(0f, b.regaliaCd(), 0f);

        //  independent projectiles
        Projectile shotA = shotBy(w, a.uid());
        Projectile shotB = shotBy(w, b.uid());

        //  and one dying does not purge the other
        a.die();
        w.step(DT);
        assertFalse(a.isAlive());
        assertTrue(b.isAlive(), "B survives A's death");
        assertTrue(w.registry.isLive(b));
        assertEquals(1, w.registry.liveCount());
        assertFalse(shotA.isAlive());
        assertTrue(shotB.isAlive());
        assertFalse(w.world.horde.contains(a));
        assertTrue(w.world.horde.contains(b));
    }

    @Test
    @DisplayName("two bosses run their attack timers independently")
    void independentTimers() {
        TestBossWorld w = new TestBossWorld();
        Dragon first = (Dragon) w.summon(BossType.DRAGON, 900f);
        Dragon second = (Dragon) w.summon(BossType.DRAGON, 1200f);

        //  Two Dragons summoned together DO start with the same breath timer --
        //  Python seeds it with a constant fire_delay(4.0), not a random draw --
        //  so a test asserting they differ at spawn would be asserting the
        //  opposite of the source.  What must be true is that the timers are
        //  independent STATE: changing one cannot move the other.
        assertEquals(first.breathTimer(), second.breathTimer(), 1e-6f,
                "they genuinely start in step, as in Python");

        //  battering one pushes its next breath out; the other is untouched
        double before = second.breathTimer();
        first.applySmack(GameConfig.CLAW_SMACK_DISTANCE);
        assertTrue(first.breathTimer() >= 2f);
        assertEquals(before, second.breathTimer(), 1e-6f,
                "the second Dragon's clock is its own");
        assertTrue(first.reel() > 0f);
        assertEquals(0f, second.reel(), 0f, "and so is its reel state");
    }

    @Test
    @DisplayName("interaction ownership cannot cross boss instances")
    void interactionCannotCrossBosses() {
        TestBossWorld w = new TestBossWorld();
        TrollKing a = (TrollKing) w.summon(BossType.TROLL_KING, 700f);
        TrollKing b = (TrollKing) w.summon(BossType.TROLL_KING, 1100f);

        float[] anchorA = w.anchorOf(a);
        w.press(anchorA[0], anchorA[1]);
        DroppedItem crown = w.cursor.heldItem();
        assertNotNull(crown);
        assertEquals(a.uid(), crown.ownerUid(), "it belongs to the one it came from");
        assertFalse(a.hasCrown());
        assertTrue(b.hasCrown(), "the other keeps its own");

        //  throw it beside B and let B try to retrieve: it is not B's
        w.setVelocity(0f, 0f);
        w.release();
        crown.moveTo(b.x(), b.y());
        b.retrieveCrown(DT);
        assertTrue(b.hasCrown(), "B never lost one, so retrieval is a no-op for it");
        assertNull(b.crownItem());
        assertTrue(crown.isAlive(), "and A's crown is untouched by B");
        assertSame(crown, a.crownItem());
    }

    @Test
    @DisplayName("two dropped items coexist, each tied to its own boss")
    void twoDroppedItems() {
        TestBossWorld w = new TestBossWorld();
        TrollKing troll = (TrollKing) w.summon(BossType.TROLL_KING, 700f);
        LichLord lich = (LichLord) w.summon(BossType.LICH_LORD, 1100f);

        troll.detachRegalia();
        lich.detachRegalia();
        assertEquals(2, w.liveItems(), "a collection, never a single slot");
        assertEquals(RegaliaKind.CROWN, troll.crownItem().kind());
        assertEquals(RegaliaKind.STAFF, lich.staffItem().kind());
        assertEquals(troll.uid(), troll.crownItem().ownerUid());
        assertEquals(lich.uid(), lich.staffItem().ownerUid());
    }

    @Test
    @DisplayName("the registry reports 0, 1 and 2 bosses correctly")
    void registryCounts() {
        TestBossWorld w = new TestBossWorld();
        assertEquals(0, w.registry.liveCount());
        assertFalse(w.registry.anyLive());

        Boss a = w.summon(BossType.TROLL_KING, 800f);
        assertEquals(1, w.registry.liveCount());
        assertTrue(w.registry.anyLive());

        Boss b = w.summon(BossType.DRAGON, 1000f);
        assertEquals(2, w.registry.liveCount());

        a.die();
        w.step(DT);
        assertEquals(1, w.registry.liveCount());
        b.die();
        w.step(DT);
        assertEquals(0, w.registry.liveCount());
        assertFalse(w.registry.anyLive());
    }

    @Test
    @DisplayName("the registry describes every live boss for a bug report")
    void registryDiagnostics() {
        TestBossWorld w = new TestBossWorld();
        assertTrue(w.registry.describe().contains("none"));

        Boss troll = w.summon(BossType.TROLL_KING, 900f);
        Boss lich = w.summon(BossType.LICH_LORD, 1100f);
        String d = w.registry.describe();

        assertTrue(d.contains("troll_king"), d);
        assertTrue(d.contains("lich_lord"), d);
        assertTrue(d.contains("#" + troll.uid()), d);
        assertTrue(d.contains("#" + lich.uid()), d);
        assertTrue(d.contains("guard="), d);
        assertTrue(d.contains("taken="), d);
        assertTrue(d.contains("crown="), "boss-specific state: " + d);
        assertTrue(d.contains("staff="), d);
        assertTrue(d.contains("hp="), d);
        assertTrue(d.contains("x="), d);
    }

    // ========================================================================
    //  Dropped item physics
    // ========================================================================

    @Test
    @DisplayName("a dropped item flies, bounces and settles")
    void droppedItemPhysics() {
        TestBossWorld w = new TestBossWorld();
        TrollKing troll = (TrollKing) w.summon(BossType.TROLL_KING, 900f);
        troll.detachRegalia();
        DroppedItem crown = troll.crownItem();

        assertEquals(DroppedItem.State.HELD, crown.state());
        crown.update(DT);
        assertEquals(0f, crown.vy(), 0f, "held items have no physics");

        crown.throwIt(300f, -400f);
        assertEquals(DroppedItem.State.FLYING, crown.state());
        for (int i = 0; i < 60 * 20 && crown.state() != DroppedItem.State.GROUND; i++) {
            crown.update(DT);
        }
        assertEquals(DroppedItem.State.GROUND, crown.state(), "it settles");
        assertEquals(crown.restY(), crown.y(), 0.01f);
        assertEquals(0f, crown.vy(), 0f);
    }

    @Test
    @DisplayName("a throw is capped by kind, with the direction preserved")
    void throwCap() {
        TestBossWorld w = new TestBossWorld();
        TrollKing troll = (TrollKing) w.summon(BossType.TROLL_KING, 900f);
        LichLord lich = (LichLord) w.summon(BossType.LICH_LORD, 1100f);
        troll.detachRegalia();
        lich.detachRegalia();

        DroppedItem crown = troll.crownItem();
        DroppedItem staff = lich.staffItem();

        crown.throwIt(9000f, -9000f);
        staff.throwIt(9000f, -9000f);

        float crownSpeed = (float) Math.sqrt(crown.vx() * crown.vx() + crown.vy() * crown.vy());
        float staffSpeed = (float) Math.sqrt(staff.vx() * staff.vx() + staff.vy() * staff.vy());
        assertEquals(RegaliaKind.CROWN.maxThrowSpeed(), crownSpeed, 0.5f);
        assertEquals(RegaliaKind.STAFF.maxThrowSpeed(), staffSpeed, 0.5f);
        assertTrue(staffSpeed < crownSpeed / 2f, "a staff is far harder to throw");

        //  the direction survives the cap: it was 45 degrees, and still is
        assertEquals(-1f, crown.vy() / crown.vx(), 1e-4f);
        assertEquals(-1f, staff.vy() / staff.vx(), 1e-4f);
    }

    @Test
    @DisplayName("a dropped item stays on the battlefield, out of the castle")
    void droppedItemWalls() {
        TestBossWorld w = new TestBossWorld();
        TrollKing troll = (TrollKing) w.summon(BossType.TROLL_KING, 900f);
        troll.detachRegalia();
        DroppedItem crown = troll.crownItem();

        crown.throwIt(-9000f, 0f);
        for (int i = 0; i < 200; i++) {
            crown.update(DT);
        }
        assertTrue(crown.x() >= GameConfig.CASTLE_FRONT + crown.width() - 0.01f,
                "it cannot be thrown into the castle: " + crown.x());
        assertTrue(crown.x() <= GameConfig.WORLD_WIDTH, "nor off the right edge");
    }

    @Test
    @DisplayName("a loose item on the ground can be picked up and thrown again")
    void pickUpLooseItem() {
        TestBossWorld w = new TestBossWorld();
        TrollKing troll = (TrollKing) w.summon(BossType.TROLL_KING, 1100f);
        troll.detachRegalia();
        DroppedItem crown = troll.crownItem();
        crown.throwIt(0f, 0f);
        for (int i = 0; i < 600 && crown.state() != DroppedItem.State.GROUND; i++) {
            crown.update(DT);
        }
        assertEquals(DroppedItem.State.GROUND, crown.state());

        w.cursor.setGrabCooldown(0f);
        assertTrue(w.press(crown.x(), crown.y()), "it can be picked up again");
        assertSame(crown, w.cursor.heldItem());
        assertEquals(DroppedItem.State.HELD, crown.state());

        w.setVelocity(600f, -400f);
        w.release();
        assertEquals(DroppedItem.State.FLYING, crown.state(), "and thrown again");
        assertNull(w.cursor.heldItem());
    }

    @Test
    @DisplayName("a cancelled carry drops the item rather than throwing it")
    void cancelDropsTheItem() {
        TestBossWorld w = new TestBossWorld();
        TrollKing troll = (TrollKing) w.summon(BossType.TROLL_KING, 900f);
        float[] anchor = w.anchorOf(troll);
        w.press(anchor[0], anchor[1]);
        DroppedItem crown = w.cursor.heldItem();
        assertNotNull(crown);

        w.setVelocity(2000f, -2000f);
        w.cancel();

        assertNull(w.cursor.heldItem());
        assertEquals(0f, crown.vx(), 0f, "dropped, not thrown");
        assertEquals(0f, crown.vy(), 0f);
    }

    @Test
    @DisplayName("dropped-item geometry comes from the kind, not from artwork")
    void itemGeometryIsGameplay() {
        assertEquals(44f, RegaliaKind.CROWN.width(), 0f);
        assertEquals(26f, RegaliaKind.CROWN.height(), 0f);
        assertEquals(18f, RegaliaKind.STAFF.width(), 0f);
        assertEquals(60f, RegaliaKind.STAFF.height(), 0f);

        TestBossWorld w = new TestBossWorld();
        TrollKing troll = (TrollKing) w.summon(BossType.TROLL_KING, 900f);
        troll.detachRegalia();
        DroppedItem crown = troll.crownItem();
        crown.moveTo(600f, 400f);
        assertTrue(crown.covers(600f, 400f), "dead centre");
        assertTrue(crown.covers(600f + 21f, 400f), "just inside");
        assertFalse(crown.covers(600f + 23f, 400f), "just outside");
    }
}
