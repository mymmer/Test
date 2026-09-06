package com.mymmer.castledefense.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mymmer.castledefense.assets.AnimationState;
import com.mymmer.castledefense.assets.SkinDefinition;
import com.mymmer.castledefense.assets.SkinManager;
import com.mymmer.castledefense.assets.UnitVisual;
import com.mymmer.castledefense.assets.VisualId;
import com.mymmer.castledefense.testsupport.FakeAtlasSource;
import com.mymmer.castledefense.testsupport.InMemoryJsonSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Missing artwork degrades one visual at a time, loudly once and quietly after.
 *
 * <h2>Per visual, not per skin</h2>
 *
 * <p>An artist part-way through a skin has a Scout and a Dragon and no Siege Ram.
 * Abandoning the whole skin over the Ram would make every partial skin useless,
 * and partial skins are the normal state of a skin being made. So the Scout and
 * the Dragon are drawn from artwork and the Ram is drawn by hand, in the same
 * frame.
 */
class ArtFallbackTest {

    /** A skin that covers three units and pointedly does not cover the rest. */
    private static final String PARTIAL =
            "{ \"id\": \"partial\", \"version\": 1, \"atlas\": \"units.atlas\","
            + " \"units\": {"
            + "   \"scout\": { \"region\": \"scout\" },"
            + "   \"dragon\": { \"region\": \"dragon\" },"
            + "   \"foot_soldier\": { \"region\": \"foot\" }"
            + " } }";

    @BeforeEach
    void setUp() {
        MissingArtLog.reset();
    }

    private static SkinManager partialSkin() {
        InMemoryJsonSource src = new InMemoryJsonSource()
                .put(SkinDefinition.descriptorPath("partial"), PARTIAL);
        FakeAtlasSource atlas = new FakeAtlasSource().withAtlas(
                SkinDefinition.directoryOf("partial") + "/units.atlas",
                "scout", "dragon", "foot");
        SkinManager skins = new SkinManager(src, atlas);
        assertTrue(skins.load("partial"), "fixture should load: " + skins.lastReport());
        return skins;
    }

    // ========================================================================
    //  Fallback is per visual
    // ========================================================================

    @Test
    @DisplayName("a skin missing one unit keeps the units it does have")
    void oneMissingUnitDoesNotLoseTheSkin() {
        SkinManager skins = partialSkin();
        assertTrue(skins.hasArtwork(VisualId.SCOUT), "the Scout is in the skin");
        assertTrue(skins.hasArtwork(VisualId.DRAGON), "so is the Dragon");
        assertFalse(skins.hasArtwork(VisualId.SIEGE_RAM),
                "the Siege Ram is not, and must fall back on its own");
        assertFalse(skins.hasArtwork(VisualId.TROLL_KING));
        assertEquals("partial", skins.activeSkinId(),
                "and the skin is still the active one");
    }

    @Test
    @DisplayName("a unit with no entry resolves to the procedural visual")
    void missingUnitIsProcedural() {
        SkinManager skins = partialSkin();
        UnitVisual ram = skins.visualFor(VisualId.SIEGE_RAM);
        assertNotNull(ram, "there must always be a visual, even if it is a fallback");
        assertTrue(ram.isProcedural(),
                "a missing unit must resolve to the hand-drawn body");
        UnitVisual scout = skins.visualFor(VisualId.SCOUT);
        assertFalse(scout.isProcedural(), "and a present one must not");
    }

    @Test
    @DisplayName("the procedural skin is procedural for everything")
    void proceduralSkinCoversEverything() {
        //  The source's own guarantee: the game draws itself with no artwork at
        //  all.  Every visual must resolve, and every one must be procedural.
        SkinManager skins = new SkinManager(new InMemoryJsonSource(),
                new FakeAtlasSource());
        assertTrue(skins.load(SkinManager.PROCEDURAL_SKIN));
        for (VisualId id : VisualId.values()) {
            UnitVisual v = skins.visualFor(id);
            assertNotNull(v, id + " has no visual at all");
            assertTrue(v.isProcedural(), id + " should be procedural");
            assertFalse(skins.hasArtwork(id), id + " should have no artwork");
        }
    }

    @Test
    @DisplayName("every gameplay type the renderer can meet has a VisualId")
    void everyDrawableTypeIsCovered() {
        //  If a new enemy is added and nobody adds a VisualId, it draws as
        //  nothing at all -- an invisible mob that still kills you.
        for (com.mymmer.castledefense.enemy.EnemyType type
                : com.mymmer.castledefense.enemy.EnemyType.values()) {
            assertNotNull(VisualId.byKey(type.id()),
                    "enemy type " + type.id() + " has no VisualId");
        }
        for (com.mymmer.castledefense.boss.BossType type
                : com.mymmer.castledefense.boss.BossType.values()) {
            assertNotNull(VisualId.byKey(type.id()),
                    "boss type " + type.id() + " has no VisualId");
        }
        for (com.mymmer.castledefense.defence.TowerType type
                : com.mymmer.castledefense.defence.TowerType.values()) {
            assertNotNull(VisualId.byKey(type.id()),
                    "tower type " + type.id() + " has no VisualId");
        }
    }

    // ========================================================================
    //  Diagnostics
    // ========================================================================

    @Test
    @DisplayName("a missing visual is reported once, not once per frame")
    void missingArtIsReportedOnce() {
        //  Reporting from inside a draw method produces one line per entity per
        //  frame -- thousands a second on a busy wave, which hides every other
        //  message and slows the frame down while doing it.
        MissingArtLog.warnOnce("nordic", VisualId.SIEGE_RAM, AnimationState.WALK);
        assertEquals(1, MissingArtLog.reportedCount());
        for (int i = 0; i < 10000; i++) {
            MissingArtLog.warnOnce("nordic", VisualId.SIEGE_RAM, AnimationState.WALK);
        }
        assertEquals(1, MissingArtLog.reportedCount(),
                "the same gap was reported more than once");
    }

    @Test
    @DisplayName("the report names the skin, the visual and the state")
    void theReportIsActionable() {
        //  All three, because "missing texture" without them is not a bug report.
        MissingArtLog.warnOnce("nordic", VisualId.GARGOYLE, AnimationState.ATTACK);
        assertTrue(MissingArtLog.hasReported("nordic", VisualId.GARGOYLE,
                AnimationState.ATTACK));
        assertFalse(MissingArtLog.hasReported("nordic", VisualId.GARGOYLE,
                AnimationState.WALK), "a different state is a different gap");
        assertFalse(MissingArtLog.hasReported("other", VisualId.GARGOYLE,
                AnimationState.ATTACK), "a different skin is a different gap");
    }

    @Test
    @DisplayName("distinct gaps are all reported")
    void everyDistinctGapIsReported() {
        MissingArtLog.warnOnce("a", VisualId.SCOUT, AnimationState.WALK);
        MissingArtLog.warnOnce("a", VisualId.SCOUT, AnimationState.ATTACK);
        MissingArtLog.warnOnce("a", VisualId.GARGOYLE, AnimationState.WALK);
        MissingArtLog.warnOnce("b", VisualId.SCOUT, AnimationState.WALK);
        assertEquals(4, MissingArtLog.reportedCount());
    }

    // ========================================================================
    //  Animation states
    // ========================================================================

    @Test
    @DisplayName("a static skin answers every state with its one region")
    void staticSkinsAreValid() {
        //  A single-frame skin is a first-class skin, not a degenerate one.
        SkinManager skins = partialSkin();
        UnitVisual scout = skins.visualFor(VisualId.SCOUT);
        for (AnimationState state : AnimationState.values()) {
            assertNotNull(scout.regionAt(state, 0f),
                    "a static skin should answer " + state + " with its region");
            assertEquals(scout.regionAt(AnimationState.IDLE, 0f),
                    scout.regionAt(state, 3.7f),
                    "and the same region at any time");
        }
    }

    @Test
    @DisplayName("a procedural visual has no region, at any state or time")
    void proceduralHasNoRegion() {
        //  A procedural visual still carries its id as a nominal region name --
        //  that is how the placeholder system names things -- so the gate the
        //  renderer uses is isProcedural(), NOT a null region.  Asserting the
        //  gate rather than the field is what keeps this honest.
        SkinManager skins = partialSkin();
        UnitVisual ram = skins.visualFor(VisualId.SIEGE_RAM);
        assertTrue(ram.isProcedural());
        for (AnimationState state : AnimationState.values()) {
            assertTrue(skins.visualFor(VisualId.SIEGE_RAM).isProcedural(),
                    "the renderer gates on isProcedural for " + state);
        }
        assertFalse(skins.hasArtwork(VisualId.SIEGE_RAM),
                "and hasArtwork must agree with it");
    }

    // ========================================================================
    //  Lifecycle
    // ========================================================================

    @Test
    @DisplayName("loading a skin loads its atlas once")
    void atlasLoadsOnce() {
        InMemoryJsonSource src = new InMemoryJsonSource()
                .put(SkinDefinition.descriptorPath("partial"), PARTIAL);
        FakeAtlasSource atlas = new FakeAtlasSource().withAtlas(
                SkinDefinition.directoryOf("partial") + "/units.atlas",
                "scout", "dragon", "foot");
        SkinManager skins = new SkinManager(src, atlas);
        assertTrue(skins.load("partial"));
        assertEquals(1, atlas.loadLog().size,
                "the atlas should be loaded exactly once: " + atlas.loadLog());
    }

    @Test
    @DisplayName("switching skins and switching back leaks nothing and keeps working")
    void skinSwitchingRoundTrips() {
        InMemoryJsonSource src = new InMemoryJsonSource()
                .put(SkinDefinition.descriptorPath("partial"), PARTIAL);
        FakeAtlasSource atlas = new FakeAtlasSource().withAtlas(
                SkinDefinition.directoryOf("partial") + "/units.atlas",
                "scout", "dragon", "foot");
        SkinManager skins = new SkinManager(src, atlas);

        assertTrue(skins.load(SkinManager.PROCEDURAL_SKIN));
        assertTrue(skins.load("partial"));
        assertTrue(skins.hasArtwork(VisualId.SCOUT));

        assertTrue(skins.load(SkinManager.PROCEDURAL_SKIN),
                "and back again");
        assertFalse(skins.hasArtwork(VisualId.SCOUT),
                "the old skin's regions must not survive the switch");
        assertTrue(skins.visualFor(VisualId.SCOUT).isProcedural());

        assertTrue(skins.load("partial"), "and forward once more");
        assertTrue(skins.hasArtwork(VisualId.SCOUT), "still working after a round trip");

        assertEquals(atlas.loadLog().size - 1, atlas.unloadLog().size,
                "every atlas but the live one should have been unloaded: loads="
                        + atlas.loadLog() + " unloads=" + atlas.unloadLog());
    }

    @Test
    @DisplayName("a skin that fails to load leaves the previous one working")
    void aFailedLoadDoesNotBreakTheLiveSkin() {
        //  The replacement is validated before the incumbent is given up, so a
        //  broken descriptor cannot leave the game with no skin at all.
        InMemoryJsonSource src = new InMemoryJsonSource()
                .put(SkinDefinition.descriptorPath("partial"), PARTIAL)
                .put(SkinDefinition.descriptorPath("broken"), "{ not json at all");
        FakeAtlasSource atlas = new FakeAtlasSource().withAtlas(
                SkinDefinition.directoryOf("partial") + "/units.atlas",
                "scout", "dragon", "foot");
        SkinManager skins = new SkinManager(src, atlas);
        assertTrue(skins.load("partial"));

        assertFalse(skins.load("broken"), "a broken skin must not load");
        assertEquals("partial", skins.activeSkinId(),
                "and the working skin must still be active");
        assertTrue(skins.hasArtwork(VisualId.SCOUT), "and still usable");
    }
}
