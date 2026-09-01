package com.mymmer.castledefense.assets;

import com.badlogic.gdx.utils.Array;

/**
 * What a skin got wrong, split by how bad it is.
 *
 * <p><b>Errors</b> mean the skin is not usable as declared — a duplicate id, an
 * attachment outside its box, an animation whose frames are not in the atlas.
 * <b>Warnings</b> mean it is usable but incomplete — a unit with no artwork
 * (procedural fallback covers it), an unknown key (ignored), an atlas region
 * nothing references.
 *
 * <p>Missing optional artwork is deliberately a warning, never an error: the
 * game must stay playable with an empty skin, exactly as the Python version
 * stays playable with an empty {@code assets/} folder.
 */
public final class SkinValidationReport {

    private final String skinId;
    private final Array<String> errors = new Array<>();
    private final Array<String> warnings = new Array<>();
    private int unitsWithArtwork;
    private int unitsProcedural;

    public SkinValidationReport(String skinId) {
        this.skinId = skinId;
    }

    public void error(String message) {
        errors.add(message);
    }

    public void warn(String message) {
        warnings.add(message);
    }

    void countArtwork() {
        unitsWithArtwork++;
    }

    void countProcedural() {
        unitsProcedural++;
    }

    public String skinId() {
        return skinId;
    }

    public Array<String> errors() {
        return errors;
    }

    public Array<String> warnings() {
        return warnings;
    }

    public boolean isValid() {
        return errors.size == 0;
    }

    public boolean hasWarnings() {
        return warnings.size > 0;
    }

    public int unitsWithArtwork() {
        return unitsWithArtwork;
    }

    public int unitsProcedural() {
        return unitsProcedural;
    }

    /** One-line summary for the log. */
    public String summary() {
        return "skin '" + skinId + "': " + unitsWithArtwork + " drawn from artwork, "
                + unitsProcedural + " procedural, " + errors.size + " error(s), "
                + warnings.size + " warning(s)";
    }
}
