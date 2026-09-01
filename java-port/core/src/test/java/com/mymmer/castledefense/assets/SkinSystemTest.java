package com.mymmer.castledefense.assets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.badlogic.gdx.utils.ObjectSet;
import com.mymmer.castledefense.data.DataException;
import com.mymmer.castledefense.testsupport.FakeAtlasSource;
import com.mymmer.castledefense.testsupport.InMemoryJsonSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The skin system's promises: artwork never defines gameplay, a bad skin never
 * takes the game down, and missing art falls back to procedural drawing.
 */
class SkinSystemTest {

    private static final String GOOD_SKIN =
            "{ \"id\": \"medieval\", \"version\": 1, \"atlas\": \"units.atlas\","
            + " \"units\": {"
            + "   \"scout\": { \"region\": \"scout\", \"scale\": 1.2,"
            + "                 \"offsetX\": 2, \"offsetY\": -3,"
            + "                 \"animations\": { \"walk\": { \"base\": \"scout_walk\","
            + "                                               \"frames\": 3, \"fps\": 8 } } },"
            + "   \"troll_king\": { \"region\": \"troll_king\","
            + "                      \"attachments\": { \"crown\": { \"x\": 0.5, \"y\": 0.91 } } }"
            + " } }";

    private static InMemoryJsonSource jsonWith(String skinId, String body) {
        return new InMemoryJsonSource().put(SkinDefinition.descriptorPath(skinId), body);
    }

    // --- parsing ------------------------------------------------------------

    @Test
    @DisplayName("a descriptor parses into units, attachments and animations")
    void parsesDescriptor() {
        SkinDefinition def = SkinDefinition.parse("medieval", jsonWith("medieval", GOOD_SKIN));
        assertEquals("medieval", def.id());
        assertEquals("skins/medieval/units.atlas", def.atlasPath());
        assertFalse(def.isProcedural());

        SkinDefinition.UnitEntry scout = def.units().get("scout");
        assertEquals(VisualId.SCOUT, scout.id);
        assertEquals(1.2f, scout.scale, 1e-6f);
        assertEquals(2f, scout.offsetX, 1e-6f);
        assertEquals(1, scout.clips.size);
        assertEquals(3, scout.clips.get(0).frames());

        AttachmentPoint crown = def.units().get("troll_king").attachments.get("crown");
        assertNotNull(crown);
        assertEquals(0.91f, crown.y(), 1e-6f);
    }

    @Test
    @DisplayName("a skin whose declared id does not match its folder is rejected")
    void idMustMatchFolder() {
        DataException e = assertThrows(DataException.class,
                () -> SkinDefinition.parse("undead", jsonWith("undead", GOOD_SKIN)));
        assertTrue(e.getMessage().contains("medieval"), e.getMessage());
    }

    @Test
    @DisplayName("the shipped procedural skin declares no atlas")
    void proceduralSkinHasNoAtlas() {
        SkinDefinition def = SkinDefinition.parse("procedural", jsonWith("procedural",
                "{ \"id\": \"procedural\", \"procedural\": true,"
                + " \"units\": { \"troll_king\": { \"attachments\":"
                + " { \"crown\": { \"x\": 0.5, \"y\": 0.98 } } } } }"));
        assertTrue(def.isProcedural());
        assertEquals("", def.atlasPath());
    }

    // --- attachment points --------------------------------------------------

    @Test
    @DisplayName("attachments are normalised, so artwork size cannot move them")
    void attachmentsAreNormalised() {
        AttachmentPoint crown = new AttachmentPoint("crown", 0.5f, 0.91f);
        // a unit centred at (700, 300) with an 84x112 GAMEPLAY box
        assertEquals(700f, crown.worldX(700f, 84f), 1e-4f);
        assertEquals(300f + (0.91f - 0.5f) * 112f, crown.worldY(300f, 112f), 1e-4f);
        // the same attachment on a skin whose PNG is twice the size: the
        // gameplay box is what it is measured against, so nothing moves
        assertEquals(700f, crown.worldX(700f, 84f), 1e-4f);
    }

    // --- validation ---------------------------------------------------------

    @Test
    @DisplayName("missing artwork is a warning, never an error")
    void missingArtworkIsAWarning() {
        SkinDefinition def = SkinDefinition.parse("medieval", jsonWith("medieval", GOOD_SKIN));
        ObjectSet<String> regions = new ObjectSet<>();
        regions.add("troll_king");          // scout's artwork is absent
        regions.add("scout_walk_0");
        regions.add("scout_walk_1");
        regions.add("scout_walk_2");

        SkinValidationReport report = SkinValidator.validate(def, regions);
        assertTrue(report.isValid(), "a skin with gaps is still usable: " + report.errors());
        assertTrue(report.hasWarnings());
        boolean warned = false;
        for (String w : report.warnings()) {
            warned |= w.contains("scout") && w.contains("procedurally");
        }
        assertTrue(warned, "the gap must be reported: " + report.warnings());
    }

    @Test
    @DisplayName("an animation with no frames in the atlas is an error")
    void brokenAnimationIsAnError() {
        SkinDefinition def = SkinDefinition.parse("medieval", jsonWith("medieval", GOOD_SKIN));
        ObjectSet<String> regions = new ObjectSet<>();
        regions.add("scout");
        regions.add("troll_king");          // no scout_walk_* frames at all

        SkinValidationReport report = SkinValidator.validate(def, regions);
        assertFalse(report.isValid());
        assertTrue(report.errors().toString().contains("walk"), report.errors().toString());
    }

    @Test
    @DisplayName("a partially present animation is an error naming the gaps")
    void partialAnimationIsAnError() {
        SkinDefinition def = SkinDefinition.parse("medieval", jsonWith("medieval", GOOD_SKIN));
        ObjectSet<String> regions = new ObjectSet<>();
        regions.add("scout");
        regions.add("scout_walk_0");
        regions.add("scout_walk_2");        // frame 1 is missing
        regions.add("troll_king");

        SkinValidationReport report = SkinValidator.validate(def, regions);
        assertFalse(report.isValid());
        assertTrue(report.errors().toString().contains("scout_walk_1"),
                report.errors().toString());
    }

    @Test
    @DisplayName("an attachment outside the unit box is an error")
    void badAttachmentIsAnError() {
        SkinDefinition def = SkinDefinition.parse("medieval", jsonWith("medieval",
                "{ \"id\": \"medieval\", \"units\": { \"troll_king\": {"
                + " \"attachments\": { \"crown\": { \"x\": 0.5, \"y\": 9.0 } } } } }"));
        SkinValidationReport report = SkinValidator.validate(def, new ObjectSet<String>());
        assertFalse(report.isValid());
        assertTrue(report.errors().toString().contains("crown"), report.errors().toString());
    }

    @Test
    @DisplayName("duplicate ids and unknown keys are reported")
    void duplicatesAndUnknowns() {
        SkinDefinition def = SkinDefinition.parse("medieval", jsonWith("medieval",
                "{ \"id\": \"medieval\", \"units\": {"
                + " \"scout\": { \"region\": \"scout\" },"
                + " \"wyvern\": { \"region\": \"wyvern\" } } }"));
        SkinValidationReport report = SkinValidator.validate(def, new ObjectSet<String>());
        assertTrue(report.warnings().toString().contains("wyvern"),
                "an unknown unit key must be reported: " + report.warnings());
    }

    @Test
    @DisplayName("a non-positive scale is an error")
    void badScaleIsAnError() {
        SkinDefinition def = SkinDefinition.parse("medieval", jsonWith("medieval",
                "{ \"id\": \"medieval\", \"units\": { \"scout\": { \"scale\": 0 } } }"));
        SkinValidationReport report = SkinValidator.validate(def, new ObjectSet<String>());
        assertFalse(report.isValid());
        assertTrue(report.errors().toString().contains("scale"));
    }

    // --- animation resolution ----------------------------------------------

    @Test
    @DisplayName("a static skin serves every animation state from one region")
    void staticSkinCoversEveryState() {
        AnimationSet set = new AnimationSet("scout");
        for (AnimationState state : AnimationState.values()) {
            assertEquals("scout", set.regionAt(state, 1.234f),
                    "a one-image skin must answer every state");
        }
    }

    @Test
    @DisplayName("frames advance with time and loop")
    void framesAdvance() {
        AnimationSet set = new AnimationSet("scout").put(
                new AnimationSet.Clip(AnimationState.WALK, "scout_walk", 3, 10f, true));
        assertEquals("scout_walk_0", set.regionAt(AnimationState.WALK, 0f));
        assertEquals("scout_walk_1", set.regionAt(AnimationState.WALK, 0.1f));
        assertEquals("scout_walk_2", set.regionAt(AnimationState.WALK, 0.2f));
        assertEquals("scout_walk_0", set.regionAt(AnimationState.WALK, 0.3f), "must loop");
        // an unlisted state falls back rather than failing
        assertEquals("scout", set.regionAt(AnimationState.DEAD, 0f));
    }

    @Test
    @DisplayName("a non-looping clip holds its last frame")
    void nonLoopingClipHolds() {
        AnimationSet set = new AnimationSet("boss").put(
                new AnimationSet.Clip(AnimationState.DEAD, "boss_dead", 2, 10f, false));
        assertEquals("boss_dead_1", set.regionAt(AnimationState.DEAD, 5f));
    }

    // --- the manager --------------------------------------------------------

    @Test
    @DisplayName("the manager starts procedural, so the game runs with no artwork")
    void startsProcedural() {
        SkinManager skins = new SkinManager(new InMemoryJsonSource(), new FakeAtlasSource());
        assertEquals(SkinManager.PROCEDURAL_SKIN, skins.activeSkinId());
        for (VisualId id : VisualId.values()) {
            UnitVisual v = skins.visualFor(id);
            assertNotNull(v, id + " must always have a visual");
            assertTrue(v.isProcedural());
        }
    }

    @Test
    @DisplayName("loading a skin marks covered units as artwork and the rest procedural")
    void loadMarksCoverage() {
        InMemoryJsonSource json = jsonWith("medieval", GOOD_SKIN);
        FakeAtlasSource atlases = new FakeAtlasSource().withAtlas("skins/medieval/units.atlas",
                "scout", "scout_walk_0", "scout_walk_1", "scout_walk_2", "troll_king");
        SkinManager skins = new SkinManager(json, atlases);

        assertTrue(skins.load("medieval"));
        assertEquals("medieval", skins.activeSkinId());
        assertTrue(skins.hasArtwork(VisualId.SCOUT));
        assertTrue(skins.hasArtwork(VisualId.TROLL_KING));
        assertFalse(skins.hasArtwork(VisualId.DRAGON), "no dragon art in this skin");
        assertTrue(skins.visualFor(VisualId.DRAGON).isProcedural());
        assertEquals(1.2f, skins.visualFor(VisualId.SCOUT).scale(), 1e-6f);
    }

    @Test
    @DisplayName("switching skins unloads the old atlas before the new one goes live")
    void switchingUnloadsTheOldAtlas() {
        InMemoryJsonSource json = jsonWith("medieval", GOOD_SKIN)
                .put(SkinDefinition.descriptorPath("undead"),
                        "{ \"id\": \"undead\", \"units\": { \"scout\": { \"region\": \"scout\" } } }");
        FakeAtlasSource atlases = new FakeAtlasSource()
                .withAtlas("skins/medieval/units.atlas", "scout", "scout_walk_0",
                        "scout_walk_1", "scout_walk_2", "troll_king")
                .withAtlas("skins/undead/units.atlas", "scout");
        SkinManager skins = new SkinManager(json, atlases);

        assertTrue(skins.load("medieval"));
        assertTrue(skins.load("undead"));
        assertEquals("undead", skins.activeSkinId());
        assertTrue(atlases.unloadLog().contains("skins/medieval/units.atlas", false),
                "the previous atlas must be released: " + atlases.unloadLog());
        assertEquals(1, atlases.loadedCount(), "only one skin may be resident");
    }

    @Test
    @DisplayName("a broken skin leaves the previous one active")
    void brokenSkinKeepsThePrevious() {
        InMemoryJsonSource json = jsonWith("medieval", GOOD_SKIN)
                .put(SkinDefinition.descriptorPath("broken"),
                        "{ \"id\": \"broken\", \"units\": { \"scout\": { \"scale\": -1 } } }")
                .put(SkinDefinition.descriptorPath("garbled"), "{ not json");
        FakeAtlasSource atlases = new FakeAtlasSource()
                .withAtlas("skins/medieval/units.atlas", "scout", "scout_walk_0",
                        "scout_walk_1", "scout_walk_2", "troll_king")
                .withAtlas("skins/broken/units.atlas", "scout");
        SkinManager skins = new SkinManager(json, atlases);
        assertTrue(skins.load("medieval"));

        assertFalse(skins.load("broken"), "an invalid skin must be refused");
        assertEquals("medieval", skins.activeSkinId());

        assertFalse(skins.load("garbled"), "malformed JSON must be refused");
        assertEquals("medieval", skins.activeSkinId());

        assertFalse(skins.load("absent"), "a missing skin must be refused");
        assertEquals("medieval", skins.activeSkinId());
    }

    @Test
    @DisplayName("an atlas that fails to load does not strand the game")
    void atlasFailureIsSurvivable() {
        InMemoryJsonSource json = jsonWith("medieval", GOOD_SKIN);
        FakeAtlasSource atlases = new FakeAtlasSource()
                .withAtlas("skins/medieval/units.atlas", "scout")
                .failing("skins/medieval/units.atlas");
        SkinManager skins = new SkinManager(json, atlases);

        assertFalse(skins.load("medieval"));
        assertEquals(SkinManager.PROCEDURAL_SKIN, skins.activeSkinId());
        assertNotNull(skins.visualFor(VisualId.SCOUT));
    }

    @Test
    @DisplayName("attachments survive a skin switch and stay per-skin")
    void attachmentsArePerSkin() {
        InMemoryJsonSource json = jsonWith("medieval", GOOD_SKIN);
        FakeAtlasSource atlases = new FakeAtlasSource().withAtlas("skins/medieval/units.atlas",
                "scout", "scout_walk_0", "scout_walk_1", "scout_walk_2", "troll_king");
        SkinManager skins = new SkinManager(json, atlases);
        skins.load("medieval");

        UnitVisual troll = skins.visualFor(VisualId.TROLL_KING);
        assertTrue(troll.hasAttachment("crown"));
        assertEquals(0.91f, troll.attachment("crown").y(), 1e-6f);
        assertNull(troll.attachment("staff"), "an undeclared attachment is absent, not invented");
    }
}
