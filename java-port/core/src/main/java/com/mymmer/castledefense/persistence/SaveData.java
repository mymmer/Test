package com.mymmer.castledefense.persistence;

/**
 * Everything the game remembers between runs.
 *
 * <p>Mutable and deliberately plain: this is a snapshot handed to
 * {@link SaveManager}, not a live game object.
 *
 * <p><b>One shared high score.</b> The Python game keeps a single
 * {@code high_score} across Classic and Endless, and the port keeps that
 * behaviour exactly — splitting it per mode would be a gameplay change, and this
 * migration changes no gameplay. The versioned format leaves room to add
 * per-mode scores in a future v1 → v2 migration if that is ever asked for.
 */
public final class SaveData {

    /** Bump this when the shape changes, and add a migration step for it. */
    public static final int CURRENT_VERSION = 1;

    public int saveVersion = CURRENT_VERSION;

    // --- settings ---
    public boolean muted;
    public String difficulty = "normal";
    public String quality = "HIGH";
    public boolean haptics = true;
    public String skin = "procedural";

    // --- progression ---
    /** Shared across both modes, exactly as the Python game does it. */
    public int highScore;

    public SaveData copy() {
        SaveData c = new SaveData();
        c.saveVersion = saveVersion;
        c.muted = muted;
        c.difficulty = difficulty;
        c.quality = quality;
        c.haptics = haptics;
        c.skin = skin;
        c.highScore = highScore;
        return c;
    }

    @Override
    public String toString() {
        return "SaveData[v" + saveVersion + " difficulty=" + difficulty
                + " quality=" + quality + " skin=" + skin + " muted=" + muted
                + " haptics=" + haptics + " highScore=" + highScore + "]";
    }
}
