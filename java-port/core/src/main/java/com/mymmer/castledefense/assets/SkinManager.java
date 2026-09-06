package com.mymmer.castledefense.assets;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.utils.ObjectMap;
import com.badlogic.gdx.utils.ObjectSet;
import com.mymmer.castledefense.data.DataException;
import com.mymmer.castledefense.data.JsonSource;

/**
 * Loads, validates, activates and unloads visual skins.
 *
 * <p>The switch sequence is the one from the design:
 *
 * <pre>
 *   current skin -> unload old atlas -> load new -> validate -> activate
 * </pre>
 *
 * <p>Two rules make this safe to call at runtime:
 *
 * <ol>
 *   <li><b>A bad skin never takes the game down.</b> A missing descriptor,
 *       malformed JSON or a failed validation leaves the previous skin active
 *       (or falls back to procedural on first load) and logs why.</li>
 *   <li><b>Missing artwork is not an error.</b> {@link #visualFor} always
 *       returns a usable {@link UnitVisual}; when a skin has no region for an
 *       id, the visual is marked procedural and the renderer draws the shape
 *       version — which is how the game ships with no artwork at all.</li>
 * </ol>
 */
public final class SkinManager {

    private static final String TAG = "SkinManager";

    /** The skin that always exists: no atlas, everything drawn from shapes. */
    public static final String PROCEDURAL_SKIN = "procedural";

    private final JsonSource json;
    private final AtlasSource atlases;

    private final ObjectMap<VisualId, UnitVisual> active = new ObjectMap<>();
    private final ObjectSet<VisualId> warnedMissing = new ObjectSet<>();

    private String activeSkinId = PROCEDURAL_SKIN;
    private String activeAtlasPath = "";
    private SkinValidationReport lastReport;

    public SkinManager(JsonSource json, AtlasSource atlases) {
        this.json = json;
        this.atlases = atlases;
        activateProcedural(PROCEDURAL_SKIN);
    }

    /**
     * Switches to a skin.
     *
     * @return true when the requested skin is now active; false when it was
     *         rejected and the previous one is still in place
     */
    public boolean load(String skinId) {
        if (skinId == null || skinId.trim().isEmpty()) {
            return false;
        }
        if (skinId.equals(activeSkinId)) {
            return true;
        }
        if (PROCEDURAL_SKIN.equals(skinId)) {
            //  The built-in skin has no descriptor and needs none: it IS the
            //  fallback, so it must always be reachable.  Before Phase 11 this
            //  fell through to the descriptor check below and failed, which made
            //  switching BACK to procedural impossible once a real skin was
            //  loaded -- found by ArtFallbackTest.skinSwitchingRoundTrips.
            if (!activeAtlasPath.isEmpty()) {
                atlases.unload(activeAtlasPath);
            }
            active.clear();
            for (VisualId id : VisualId.values()) {
                active.put(id, UnitVisual.procedural(id));
            }
            activeSkinId = PROCEDURAL_SKIN;
            activeAtlasPath = "";
            lastReport = new SkinValidationReport(PROCEDURAL_SKIN);
            warnedMissing.clear();
            log("skin '" + PROCEDURAL_SKIN + "' active: everything hand-drawn");
            return true;
        }

        SkinDefinition definition;
        try {
            if (!json.exists(SkinDefinition.descriptorPath(skinId))) {
                log("skin '" + skinId + "' has no " + SkinDefinition.descriptorPath(skinId)
                        + "; keeping '" + activeSkinId + "'");
                return false;
            }
            definition = SkinDefinition.parse(skinId, json);
        } catch (DataException e) {
            log("skin '" + skinId + "' could not be read (" + e.getMessage()
                    + "); keeping '" + activeSkinId + "'");
            return false;
        }

        String newAtlas = definition.atlasPath();
        ObjectSet<String> regions;
        if (newAtlas.isEmpty()) {
            regions = new ObjectSet<>();
        } else {
            try {
                atlases.load(newAtlas);
                regions = atlases.regionNames(newAtlas);
            } catch (RuntimeException e) {
                log("skin '" + skinId + "': atlas " + newAtlas + " failed to load ("
                        + e.getMessage() + "); keeping '" + activeSkinId + "'");
                atlases.unload(newAtlas);
                return false;
            }
        }

        SkinValidationReport report = SkinValidator.validate(definition, regions);
        lastReport = report;
        log(report.summary());
        for (String w : report.warnings()) {
            log("  warning: " + w);
        }
        for (String err : report.errors()) {
            logError("  error: " + err);
        }
        if (!report.isValid()) {
            logError("skin '" + skinId + "' is invalid; keeping '" + activeSkinId + "'");
            if (!newAtlas.isEmpty() && !newAtlas.equals(activeAtlasPath)) {
                atlases.unload(newAtlas);
            }
            return false;
        }

        // committed: release the previous skin before the new one goes live, so
        // two atlases are never resident at once
        if (!activeAtlasPath.isEmpty() && !activeAtlasPath.equals(newAtlas)) {
            atlases.unload(activeAtlasPath);
        }
        buildVisuals(definition, regions);
        activeSkinId = definition.id();
        activeAtlasPath = newAtlas;
        warnedMissing.clear();
        return true;
    }

    private void buildVisuals(SkinDefinition definition, ObjectSet<String> regions) {
        active.clear();
        for (VisualId id : VisualId.values()) {
            SkinDefinition.UnitEntry entry = definition.units().get(id.key());
            if (entry == null) {
                // not described: use the region named after the id if the atlas
                // happens to have one, else procedural
                if (!definition.isProcedural() && regions.contains(id.key())) {
                    active.put(id, new UnitVisual(id, id.key(), 1f, 0f, 0f,
                            new AnimationSet(id.key()),
                            new ObjectMap<String, AttachmentPoint>(), false));
                } else {
                    active.put(id, UnitVisual.procedural(id));
                }
                continue;
            }
            boolean hasArt = !definition.isProcedural() && regions.contains(entry.region);
            AnimationSet animations = new AnimationSet(entry.region);
            for (AnimationSet.Clip clip : entry.clips) {
                animations.put(clip);
            }
            active.put(id, new UnitVisual(id, entry.region, entry.scale,
                    entry.offsetX, entry.offsetY, animations, entry.attachments, !hasArt));
        }
    }

    private void activateProcedural(String skinId) {
        active.clear();
        for (VisualId id : VisualId.values()) {
            active.put(id, UnitVisual.procedural(id));
        }
        activeSkinId = skinId;
        activeAtlasPath = "";
    }

    /**
     * The visual for a logical id. Never null.
     *
     * <p>This is the procedural fallback in action: an id the active skin has no
     * artwork for still gets a visual, marked {@link UnitVisual#isProcedural()},
     * and the first time each one is asked for the reason is logged once.
     */
    public UnitVisual visualFor(VisualId id) {
        UnitVisual v = active.get(id);
        if (v == null) {
            v = UnitVisual.procedural(id);
            active.put(id, v);
        }
        if (v.isProcedural() && warnedMissing.add(id)) {
            log("no artwork for '" + id.key() + "' in skin '" + activeSkinId
                    + "' -- drawing it procedurally");
        }
        return v;
    }

    public String activeSkinId() {
        return activeSkinId;
    }

    public String activeAtlasPath() {
        return activeAtlasPath;
    }

    /** The report from the most recent load attempt, or null. */
    public SkinValidationReport lastReport() {
        return lastReport;
    }

    /** True when the active skin draws this id from artwork. */
    public boolean hasArtwork(VisualId id) {
        UnitVisual v = active.get(id);
        return v != null && !v.isProcedural();
    }

    private void log(String message) {
        if (Gdx.app != null) {
            Gdx.app.log(TAG, message);
        }
    }

    private void logError(String message) {
        if (Gdx.app != null) {
            Gdx.app.error(TAG, message);
        }
    }
}
