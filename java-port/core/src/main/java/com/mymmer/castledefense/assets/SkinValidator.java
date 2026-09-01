package com.mymmer.castledefense.assets;

import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.ObjectMap;
import com.badlogic.gdx.utils.ObjectSet;

/**
 * Checks a parsed skin against the regions an atlas actually contains.
 *
 * <p>Pure data in, report out: no textures, no GL, no file system. That is what
 * lets skin validation run in a unit test and, later, as a build step over an
 * artist's folder before anything ships.
 *
 * <p>Checks performed:
 * <ul>
 *   <li>duplicate unit ids in the descriptor</li>
 *   <li>unit keys that are not a known {@link VisualId}</li>
 *   <li>declared regions missing from the atlas</li>
 *   <li>animation frames missing from the atlas</li>
 *   <li>attachment coordinates outside the unit box</li>
 *   <li>non-positive scale</li>
 *   <li>units with no artwork at all (warning — procedural fallback)</li>
 * </ul>
 */
public final class SkinValidator {

    /** Attachment coordinates are normalised; a little overhang is legitimate. */
    private static final float ATTACH_MIN = -0.5f;
    private static final float ATTACH_MAX = 1.5f;

    private SkinValidator() {
    }

    /**
     * @param definition   the parsed descriptor
     * @param atlasRegions region names the atlas provides; empty for a
     *                     procedural skin
     */
    public static SkinValidationReport validate(SkinDefinition definition,
                                                ObjectSet<String> atlasRegions) {
        SkinValidationReport report = new SkinValidationReport(definition.id());

        for (String dup : definition.duplicateKeys()) {
            report.error("duplicate unit id '" + dup + "'");
        }
        for (String unknown : definition.unknownKeys()) {
            report.warn("unit '" + unknown + "' is not a known visual id and will be ignored");
        }

        ObjectSet<String> regions = atlasRegions != null ? atlasRegions : new ObjectSet<String>();

        for (ObjectMap.Entry<String, SkinDefinition.UnitEntry> e : definition.units().entries()) {
            SkinDefinition.UnitEntry unit = e.value;
            if (unit.id == null) {
                continue;               // already warned about
            }
            String label = "unit '" + unit.key + "'";

            if (!(unit.scale > 0f)) {
                report.error(label + ": scale must be greater than 0, got " + unit.scale);
            }

            for (AttachmentPoint p : unit.attachments.values()) {
                if (p.x() < ATTACH_MIN || p.x() > ATTACH_MAX
                        || p.y() < ATTACH_MIN || p.y() > ATTACH_MAX) {
                    report.error(label + ": attachment '" + p.name() + "' at ("
                            + p.x() + ", " + p.y() + ") is outside the unit box; "
                            + "attachment coordinates are normalised to it");
                }
            }

            if (definition.isProcedural()) {
                report.countProcedural();
                continue;
            }

            boolean hasBase = regions.contains(unit.region);
            if (!hasBase) {
                report.warn(label + ": no atlas region '" + unit.region
                        + "'; it will be drawn procedurally");
            }

            for (AnimationSet.Clip clip : unit.clips) {
                Array<String> missing = new Array<>();
                for (int i = 0; i < clip.frames(); i++) {
                    String needed = clip.regionFor(i);
                    if (!regions.contains(needed)) {
                        missing.add(needed);
                    }
                }
                if (missing.size == clip.frames()) {
                    report.error(label + ": animation '" + clip.state().key()
                            + "' names " + clip.frames() + " frame(s) but none are in "
                            + "the atlas (expected " + clip.regionFor(0) + ")");
                } else if (missing.size > 0) {
                    report.error(label + ": animation '" + clip.state().key()
                            + "' is missing frame(s) " + missing);
                }
            }

            if (hasBase) {
                report.countArtwork();
            } else {
                report.countProcedural();
            }
        }

        // every id the game can ask for should be accounted for somewhere
        for (VisualId id : VisualId.values()) {
            if (!definition.units().containsKey(id.key())
                    && !definition.isProcedural()
                    && !regions.contains(id.key())) {
                report.warn("no artwork for '" + id.key()
                        + "'; it will be drawn procedurally");
            }
        }
        return report;
    }
}
