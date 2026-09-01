package com.mymmer.castledefense.persistence;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Json;
import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import com.badlogic.gdx.utils.JsonWriter;

/**
 * Reads and writes the save file, and upgrades old ones.
 *
 * <p>Android-safe by construction: the file lives in {@code Gdx.files.local},
 * never next to the packaged assets (which are read-only on a device). The
 * Python game writes {@code settings.json} beside the script; that is fine for a
 * desktop script and impossible on Android, so this is the one storage detail
 * that had to change.
 *
 * <p><b>Persistence failure must never stop the game starting.</b> Every read
 * and write is guarded, exactly like the Python {@code Settings} class: a
 * missing file, malformed JSON, a save from the future, or a read-only folder
 * all end in usable defaults and a log line, never an exception reaching the
 * caller.
 */
public final class SaveManager {

    private static final String TAG = "SaveManager";
    public static final String DEFAULT_PATH = "castle-defense/save.json";

    private final String path;
    private final Array<SaveMigration> migrations = new Array<>();
    private final JsonReader reader = new JsonReader();
    private final Json json = new Json();

    private String lastLoadNote = "";

    public SaveManager() {
        this(DEFAULT_PATH);
    }

    public SaveManager(String path) {
        this.path = path;
        json.setOutputType(JsonWriter.OutputType.json);
        json.setUsePrototypes(false);
    }

    /** Registers an upgrade step. Order does not matter; the chain is walked. */
    public SaveManager register(SaveMigration migration) {
        migrations.add(migration);
        return this;
    }

    /**
     * Loads the save, migrating it if necessary.
     *
     * <p>Never throws. Never returns null. Check {@link #lastLoadNote()} to see
     * whether defaults were substituted and why.
     */
    public SaveData load() {
        lastLoadNote = "";
        FileHandle file = fileHandle();
        if (file == null || !file.exists()) {
            lastLoadNote = "no save file yet; starting from defaults";
            return new SaveData();
        }
        JsonValue root;
        try {
            root = reader.parse(file);
        } catch (RuntimeException e) {
            lastLoadNote = "save file is not readable JSON (" + e.getMessage()
                    + "); starting from defaults";
            logError(lastLoadNote);
            return new SaveData();
        }
        if (root == null) {
            lastLoadNote = "save file is empty; starting from defaults";
            return new SaveData();
        }

        int version = root.getInt("saveVersion", 0);
        if (version > SaveData.CURRENT_VERSION) {
            // a newer build wrote this; keep the file, do not guess at its shape
            lastLoadNote = "save is version " + version + ", newer than this build ("
                    + SaveData.CURRENT_VERSION + "); using defaults and leaving it alone";
            logError(lastLoadNote);
            return new SaveData();
        }
        if (version < SaveData.CURRENT_VERSION) {
            root = migrate(root, version);
            if (root == null) {
                lastLoadNote = "no migration path from version " + version
                        + "; starting from defaults";
                logError(lastLoadNote);
                return new SaveData();
            }
        }
        return fromJson(root);
    }

    private JsonValue migrate(JsonValue root, int fromVersion) {
        int version = fromVersion;
        int guard = 0;
        while (version < SaveData.CURRENT_VERSION) {
            SaveMigration step = stepFrom(version);
            if (step == null) {
                return null;
            }
            root = step.migrate(root);
            version = step.toVersion();
            log("migrated save " + step.fromVersion() + " -> " + version);
            if (++guard > 64) {
                logError("migration chain did not terminate; abandoning it");
                return null;
            }
        }
        lastLoadNote = "migrated save from version " + fromVersion;
        return root;
    }

    private SaveMigration stepFrom(int version) {
        for (SaveMigration m : migrations) {
            if (m.fromVersion() == version) {
                return m;
            }
        }
        return null;
    }

    /** A field-by-field read, so a partially corrupt file still yields defaults. */
    private SaveData fromJson(JsonValue root) {
        SaveData data = new SaveData();
        data.saveVersion = SaveData.CURRENT_VERSION;
        JsonValue settings = root.get("settings");
        if (settings != null) {
            data.muted = settings.getBoolean("muted", data.muted);
            data.difficulty = settings.getString("difficulty", data.difficulty);
            data.quality = settings.getString("quality", data.quality);
            data.haptics = settings.getBoolean("haptics", data.haptics);
            data.skin = settings.getString("skin", data.skin);
        }
        data.highScore = Math.max(0, root.getInt("highScore", 0));
        return data;
    }

    /**
     * Writes the save.
     *
     * @return true when it reached the disk; false when it could not be written
     *         (which is logged and otherwise ignored — a read-only folder must
     *         never break the game)
     */
    public boolean save(SaveData data) {
        FileHandle file = fileHandle();
        if (file == null) {
            return false;
        }
        try {
            StringBuilder sb = new StringBuilder(256);
            sb.append("{\n  \"saveVersion\": ").append(SaveData.CURRENT_VERSION)
                    .append(",\n  \"settings\": {")
                    .append("\n    \"muted\": ").append(data.muted)
                    .append(",\n    \"difficulty\": ").append(quote(data.difficulty))
                    .append(",\n    \"quality\": ").append(quote(data.quality))
                    .append(",\n    \"haptics\": ").append(data.haptics)
                    .append(",\n    \"skin\": ").append(quote(data.skin))
                    .append("\n  },\n  \"highScore\": ").append(Math.max(0, data.highScore))
                    .append("\n}\n");
            file.writeString(sb.toString(), false, "UTF-8");
            return true;
        } catch (RuntimeException e) {
            logError("could not write the save file (" + e.getMessage()
                    + "); progress this session will not persist");
            return false;
        }
    }

    /** Deletes the save. Used by tests and by a future "reset progress". */
    public void delete() {
        FileHandle file = fileHandle();
        if (file != null && file.exists()) {
            file.delete();
        }
    }

    public String path() {
        return path;
    }

    /** Why the last {@link #load()} produced what it did. Empty when normal. */
    public String lastLoadNote() {
        return lastLoadNote;
    }

    private FileHandle fileHandle() {
        try {
            return Gdx.files != null ? Gdx.files.local(path) : null;
        } catch (RuntimeException e) {
            logError("no writable storage available: " + e.getMessage());
            return null;
        }
    }

    private static String quote(String s) {
        return "\"" + (s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"")) + "\"";
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
