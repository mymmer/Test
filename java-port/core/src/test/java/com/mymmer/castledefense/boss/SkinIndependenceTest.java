package com.mymmer.castledefense.boss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mymmer.castledefense.assets.AttachmentPoint;
import com.mymmer.castledefense.assets.SkinDefinition;
import com.mymmer.castledefense.assets.SkinManager;
import com.mymmer.castledefense.assets.UnitVisual;
import com.mymmer.castledefense.assets.VisualId;
import com.mymmer.castledefense.config.GameplayAnchor;
import com.mymmer.castledefense.defence.DefenceTower;
import com.mymmer.castledefense.defence.TowerType;
import com.mymmer.castledefense.testsupport.FakeAtlasSource;
import com.mymmer.castledefense.testsupport.InMemoryJsonSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>Changing a skin must not change gameplay.</b>
 *
 * <p>The cross-cutting invariant, tested rather than asserted in a comment. Two
 * skins with radically different scales, offsets and visual attachments are
 * loaded, and every gameplay anchor and origin is compared before and after.
 *
 * <p>The architecture makes the invariant structural — gameplay cannot import
 * the assets package, and {@code ArchitectureTest} fails the build if it ever
 * does — but structure is a claim about imports, and this is a claim about
 * <em>numbers</em>. Both are worth having.
 */
class SkinIndependenceTest {

    /** Two skins that disagree about everything an artist controls. */
    private static final String TINY_SKIN =
            "{ \"id\": \"tiny\", \"version\": 1, \"atlas\": \"units.atlas\","
            + " \"units\": {"
            + "   \"troll_king\": { \"region\": \"troll\", \"scale\": 0.25,"
            + "     \"offsetX\": -80, \"offsetY\": -40,"
            + "     \"attachments\": { \"crown\": { \"x\": 0.02, \"y\": 0.05 } } },"
            + "   \"lich_lord\": { \"region\": \"lich\", \"scale\": 0.25,"
            + "     \"offsetX\": -60, \"offsetY\": 90,"
            + "     \"attachments\": { \"staff\": { \"x\": -0.4, \"y\": -0.3 } } },"
            + "   \"dragon\": { \"region\": \"dragon\", \"scale\": 0.25,"
            + "     \"attachments\": { \"claws\": { \"x\": 1.4, \"y\": 1.45 } } },"
            + "   \"ballista\": { \"region\": \"ballista\", \"scale\": 0.2,"
            + "     \"attachments\": { \"muzzle\": { \"x\": -0.5, \"y\": 1.4 } } }"
            + " } }";

    private static final String HUGE_SKIN =
            "{ \"id\": \"huge\", \"version\": 1, \"atlas\": \"units.atlas\","
            + " \"units\": {"
            + "   \"troll_king\": { \"region\": \"troll\", \"scale\": 8.0,"
            + "     \"offsetX\": 250, \"offsetY\": 310,"
            + "     \"attachments\": { \"crown\": { \"x\": 1.45, \"y\": 1.48 } } },"
            + "   \"lich_lord\": { \"region\": \"lich\", \"scale\": 8.0,"
            + "     \"offsetX\": 400, \"offsetY\": -200,"
            + "     \"attachments\": { \"staff\": { \"x\": 1.5, \"y\": -0.5 } } },"
            + "   \"dragon\": { \"region\": \"dragon\", \"scale\": 8.0,"
            + "     \"attachments\": { \"claws\": { \"x\": -0.45, \"y\": -0.5 } } },"
            + "   \"ballista\": { \"region\": \"ballista\", \"scale\": 9.0,"
            + "     \"attachments\": { \"muzzle\": { \"x\": 1.5, \"y\": -0.45 } } }"
            + " } }";

    private static SkinManager skinWith(String id, String json) {
        InMemoryJsonSource src = new InMemoryJsonSource()
                .put(SkinDefinition.descriptorPath(id), json);
        FakeAtlasSource atlas = new FakeAtlasSource().withAtlas(
                SkinDefinition.directoryOf(id) + "/units.atlas",
                "troll", "lich", "dragon", "ballista");
        SkinManager skins = new SkinManager(src, atlas);
        assertTrue(skins.load(id), "the test skin '" + id + "' should load: "
                + skins.lastReport());
        return skins;
    }

    @Test
    @DisplayName("the two test skins really do disagree about everything visual")
    void theSkinsAreActuallyDifferent() {
        //  Guard against a vacuous test: if the two skins were identical, every
        //  assertion below would pass and prove nothing.
        SkinManager tiny = skinWith("tiny", TINY_SKIN);
        SkinManager huge = skinWith("huge", HUGE_SKIN);

        UnitVisual tinyTroll = tiny.visualFor(VisualId.TROLL_KING);
        UnitVisual hugeTroll = huge.visualFor(VisualId.TROLL_KING);
        assertNotEquals(tinyTroll.scale(), hugeTroll.scale(), "scales differ");
        assertNotEquals(tinyTroll.offsetX(), hugeTroll.offsetX(), "offsets differ");

        AttachmentPoint tinyCrown = tinyTroll.attachment("crown");
        AttachmentPoint hugeCrown = hugeTroll.attachment("crown");
        assertNotNull(tinyCrown);
        assertNotNull(hugeCrown);
        assertNotEquals(tinyCrown.visualX(), hugeCrown.visualX(), "crown x differs");
        assertNotEquals(tinyCrown.visualY(), hugeCrown.visualY(), "crown y differs");

        //  and the difference is enormous, not a rounding wobble
        float boxW = 84f;
        float boxH = 112f;
        float dx = Math.abs(tinyCrown.drawX(700f, boxW) - hugeCrown.drawX(700f, boxW));
        float dy = Math.abs(tinyCrown.drawY(300f, boxH) - hugeCrown.drawY(300f, boxH));
        assertTrue(dx > 100f && dy > 140f,
                "the two skins draw the crown " + dx + "," + dy + " apart");
    }

    @Test
    @DisplayName("a boss's gameplay anchor is identical under both skins")
    void bossAnchorsAreSkinIndependent() {
        //  Load one skin, measure every gameplay anchor, load the other, measure
        //  again.  The skins are live and radically different; the numbers must
        //  not move by a single float.
        SkinManager skins = skinWith("tiny", TINY_SKIN);
        TestBossWorld w = new TestBossWorld();

        float[][] before = new float[BossType.values().length][];
        Boss[] bosses = new Boss[BossType.values().length];
        for (int i = 0; i < BossType.values().length; i++) {
            bosses[i] = w.summon(BossType.values()[i], 700f + i * 200f);
            before[i] = w.anchorOf(bosses[i]);
        }

        //  swap the skin under a running world
        SkinManager other = skinWith("huge", HUGE_SKIN);
        assertEquals("huge", other.activeSkinId());

        for (int i = 0; i < bosses.length; i++) {
            float[] after = w.anchorOf(bosses[i]);
            assertEquals(before[i][0], after[0], 0f,
                    bosses[i].bossType().id() + " anchor x moved with the skin");
            assertEquals(before[i][1], after[1], 0f,
                    bosses[i].bossType().id() + " anchor y moved with the skin");
        }
    }

    @Test
    @DisplayName("the interaction area, dropped-item origin and hit box are skin-independent")
    void interactionGeometryIsSkinIndependent() {
        SkinManager tiny = skinWith("tiny", TINY_SKIN);

        TestBossWorld a = new TestBossWorld(4242L);
        TrollKing trollA = (TrollKing) a.summon(BossType.TROLL_KING, 900f);
        float[] anchorA = a.anchorOf(trollA);
        boolean coversA = trollA.regaliaCovers(anchorA[0], anchorA[1]);
        boolean missesA = trollA.regaliaCovers(anchorA[0] + 200f, anchorA[1]);
        DroppedItem itemA = trollA.detachRegalia();
        float itemAx = itemA.x();
        float itemAy = itemA.y();
        boolean hitA = trollA.covers(trollA.x() + 30f, trollA.y());
        float widthA = trollA.width();

        //  now the same run under a wildly different skin
        SkinManager huge = skinWith("huge", HUGE_SKIN);
        assertEquals("huge", huge.activeSkinId());
        assertNotNull(tiny);

        TestBossWorld b = new TestBossWorld(4242L);
        TrollKing trollB = (TrollKing) b.summon(BossType.TROLL_KING, 900f);
        float[] anchorB = b.anchorOf(trollB);

        assertEquals(anchorA[0], anchorB[0], 0f, "interaction anchor x");
        assertEquals(anchorA[1], anchorB[1], 0f, "interaction anchor y");
        assertEquals(coversA, trollB.regaliaCovers(anchorB[0], anchorB[1]),
                "whether the crown can be interacted with");
        assertEquals(missesA, trollB.regaliaCovers(anchorB[0] + 200f, anchorB[1]),
                "and where it cannot");

        DroppedItem itemB = trollB.detachRegalia();
        assertEquals(itemAx, itemB.x(), 0f, "dropped-item spawn origin x");
        assertEquals(itemAy, itemB.y(), 0f, "dropped-item spawn origin y");
        assertEquals(itemA.width(), itemB.width(), 0f, "pickup box width");
        assertEquals(itemA.height(), itemB.height(), 0f, "pickup box height");

        assertEquals(widthA, trollB.width(), 0f, "gameplay hit box width");
        assertEquals(hitA, trollB.covers(trollB.x() + 30f, trollB.y()),
                "and what it covers");
    }

    @Test
    @DisplayName("a tower's muzzle -- the projectile origin -- is skin-independent")
    void towerMuzzleIsSkinIndependent() {
        //  The muzzle was always gameplay-owned (DefenceTower.muzzleX/Y derives
        //  from the config box), but the skins here deliberately declare a
        //  "muzzle" attachment in wildly different places to prove the two are
        //  genuinely unconnected.
        SkinManager tiny = skinWith("tiny", TINY_SKIN);
        TestBossWorld w = new TestBossWorld();
        DefenceTower t = w.world.defences.createTower(w.world, TowerType.BALLISTA,
                248f, 354f);
        float mx = t.muzzleX();
        float my = t.muzzleY();

        SkinManager huge = skinWith("huge", HUGE_SKIN);
        assertEquals(mx, t.muzzleX(), 0f, "muzzle x moved with the skin");
        assertEquals(my, t.muzzleY(), 0f, "muzzle y moved with the skin");

        //  the two skins DO place the muzzle artwork in completely different spots
        AttachmentPoint tinyMuzzle = tiny.visualFor(VisualId.BALLISTA).attachment("muzzle");
        AttachmentPoint hugeMuzzle = huge.visualFor(VisualId.BALLISTA).attachment("muzzle");
        assertNotNull(tinyMuzzle);
        assertNotNull(hugeMuzzle);
        assertNotEquals(tinyMuzzle.drawX(t.x(), t.width()),
                hugeMuzzle.drawX(t.x(), t.width()),
                "the artwork anchors really are in different places");

        //  and the shot leaves the gameplay muzzle, not the artwork one
        t.setCooldown(0f);
        w.world.spawn(com.mymmer.castledefense.enemy.EnemyType.GARGOYLE, 500f);
        t.update(1f / 60f);
        assertTrue(w.world.projectiles.size() > 0, "it fired");
        var p = w.world.projectiles.get(0);
        assertEquals(mx, p.x(), 0.001f, "the projectile is born at the GAMEPLAY muzzle");
        assertEquals(my, p.y(), 0.001f);
    }

    @Test
    @DisplayName("a gameplay anchor and a visual attachment are different types")
    void theTwoConceptsAreSeparateTypes() {
        //  Not interchangeable, and not accidentally assignable to each other:
        //  the separation is in the type system, not only in a naming convention.
        GameplayAnchor anchor = new GameplayAnchor("crown", 0.5f, 0.98f);
        AttachmentPoint visual = new AttachmentPoint("crown", 0.5f, 0.98f);

        assertFalse(GameplayAnchor.class.isAssignableFrom(AttachmentPoint.class));
        assertFalse(AttachmentPoint.class.isAssignableFrom(GameplayAnchor.class));

        //  the same normalised coordinates, and the same maths -- the difference
        //  is entirely one of AUTHORITY: one comes from gameplay data and one
        //  from a skin
        assertEquals(anchor.worldX(700f, 84f), visual.drawX(700f, 84f), 1e-6f);
        assertEquals(anchor.worldY(300f, 112f), visual.drawY(300f, 112f), 1e-6f);
    }

    @Test
    @DisplayName("a boss simulates identically under either skin, for many steps")
    void bossSimulationIsSkinIndependent() {
        //  The end-to-end version: run the same seeded scenario under each skin
        //  and compare the whole observable outcome, not just the anchors.
        String underTiny = runScenario("tiny", TINY_SKIN);
        String underHuge = runScenario("huge", HUGE_SKIN);
        assertEquals(underTiny, underHuge,
                "a skin change altered the simulation");
    }

    /** Loads a skin, runs a fixed seeded boss scenario, and digests the result. */
    private static String runScenario(String skinId, String json) {
        SkinManager skins = skinWith(skinId, json);
        assertEquals(skinId, skins.activeSkinId());

        TestBossWorld w = new TestBossWorld(31337L);
        TrollKing troll = (TrollKing) w.summon(BossType.TROLL_KING, 950f);
        LichLord lich = (LichLord) w.summon(BossType.LICH_LORD, 1150f);
        w.world.castle.addTower(TowerType.BOWMAN);
        w.steps(120, TestBossWorld.DT);

        float[] anchor = w.anchorOf(troll);
        w.press(anchor[0], anchor[1]);
        w.setVelocity(900f, -400f);
        w.release();
        w.steps(400, TestBossWorld.DT);

        lich.detachRegalia();
        w.steps(400, TestBossWorld.DT);

        StringBuilder sb = new StringBuilder();
        sb.append((int) troll.x()).append('/').append((int) troll.y())
                .append('/').append((int) troll.hp())
                .append('/').append(troll.hasCrown())
                .append('/').append(troll.regaliaTaken()).append('|');
        sb.append((int) lich.x()).append('/').append((int) lich.hp())
                .append('/').append(lich.hasStaff())
                .append('/').append((int) (lich.disarm() * 100)).append('|');
        sb.append(w.world.targetCount()).append('/').append(w.liveItems())
                .append('/').append((int) w.world.castle.hp())
                .append('/').append(w.world.gold);
        return sb.toString();
    }
}
