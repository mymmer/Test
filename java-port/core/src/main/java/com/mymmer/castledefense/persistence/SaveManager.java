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
 *
 * <h2>A write never destroys a good save</h2>
 *
 * <p>Saving happens at {@code pause()}, which on Android is the moment before
 * the OS may kill the process. Writing in place there means a truncated file and
 * a player who lost their settings and high score. So a save is staged:
 *
 * <pre>
 *   serialise → write save.json.tmp → read it back and validate
 *              → replace save.json (rename where the platform allows)
 * </pre>
 *
 * <p>The live file is not touched until a complete, re-parsed, validated
 * replacement exists. If the process dies at the worst moment — after the live
 * file is gone and before the temp file has taken its place — {@link #load()}
 * finds the valid temp file and promotes it. The failure modes are therefore:
 * the old save survives, or the new save survives. Never neither.
 *
 * <p>This is deliberately not a journal or a database. One temp file, one
 * validation, one replace, and a recovery branch on load.
 */
public final class SaveManager {

    private static final String TAG = "SaveManager";
    public static final String DEFAULT_PATH = "castle-defense/save.json";

    /** Appended to the save path for the staging file. */
    private static final String STAGING_SUFFIX = ".tmp";

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
        if (file == null) {
            lastLoadNote = "no writable storage; starting from defaults";
            return new SaveData();
        }

        JsonValue root = null;
        if (file.exists()) {
            try {
                root = reader.parse(file);
            } catch (RuntimeException e) {
                lastLoadNote = "save file is not readable JSON (" + e.getMessage() + ")";
                logError(lastLoadNote);
            }
        }

        //  Recovery.  A staged temp file only outlives a save() call if the
        //  process died mid-replace, so if the live file is missing or unusable
        //  and the temp one parses, the temp one IS the save.
        if (root == null) {
            JsonValue staged = readStaged();
            if (staged != null) {
                String why = file.exists()
                        ? "live save was unreadable"
                        : "live save was missing";
                if (promoteStaged()) {
                    lastLoadNote = why + "; recovered the staged save from an "
                            + "interrupted write";
                } else {
                    lastLoadNote = why + "; read the staged save from an interrupted "
                            + "write but could not promote it";
                }
                log(lastLoadNote);
                root = staged;
            }
        }

        if (root == null) {
            if (lastLoadNote.isEmpty()) {
                lastLoadNote = "no save file yet; starting from defaults";
            } else {
                lastLoadNote = lastLoadNote + "; starting from defaults";
            }
            return new SaveData();
        }
        discardStaged();          // a leftover temp file is stale once we have a save

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
        FileHandle temp = stagingHandle();
        if (file == null || temp == null) {
            return false;
        }

        String text = serialise(data);

        // 1. stage: the live save is still untouched at this point
        try {
            temp.writeString(text, false, "UTF-8");
        } catch (RuntimeException e) {
            logError("could not stage the save (" + e.getMessage()
                    + "); the previous save is untouched");
            discardStaged();
            return false;
        }

        // 2. validate what actually reached the disk, not what we meant to write.
        //    A short write, a full disk or a mangled encoding is caught here,
        //    while the good save is still in place.
        if (!isUsableSave(temp)) {
            logError("the staged save did not read back correctly; the previous "
                    + "save is untouched");
            discardStaged();
            return false;
        }

        // 3. replace.  Dying inside this step is the one window where the live
        //    file may be absent -- load() recovers from the temp file there.
        if (!replaceWithStaged(file, temp)) {
            logError("could not replace the save file; the staged copy is kept at "
                    + temp.path() + " and will be recovered on the next load");
            return false;
        }
        return true;
    }

    /** The exact bytes a save consists of. Kept separate so it can be validated. */
    private String serialise(SaveData data) {
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
        return sb.toString();
    }

    /**
     * Does this file hold a save we would be willing to load?
     *
     * <p>Read back from disk and re-parsed, so it proves the write, not the
     * intent. The check is the structure a save must have — it deliberately does
     * not compare field by field, because {@link #fromJson} already tolerates a
     * missing optional field and a validator stricter than the loader would
     * reject files the game can read perfectly well.
     */
    private boolean isUsableSave(FileHandle handle) {
        try {
            if (!handle.exists() || handle.length() == 0) {
                return false;
            }
            JsonValue root = reader.parse(handle);
            if (root == null) {
                return false;
            }
            int version = root.getInt("saveVersion", -1);
            if (version < 1 || version > SaveData.CURRENT_VERSION) {
                return false;
            }
            return root.get("settings") != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Reads the staged file if it is a usable save, else null. */
    private JsonValue readStaged() {
        FileHandle temp = stagingHandle();
        if (temp == null || !isUsableSave(temp)) {
            return null;
        }
        try {
            return reader.parse(temp);
        } catch (RuntimeException e) {
            return null;          // unreachable: isUsableSave already parsed it
        }
    }

    private boolean promoteStaged() {
        FileHandle file = fileHandle();
        FileHandle temp = stagingHandle();
        return file != null && temp != null && replaceWithStaged(file, temp);
    }

    /**
     * Moves the staged file over the live one.
     *
     * <p>Prefers {@code File.renameTo}, which is a POSIX rename on Android and
     * desktop and therefore atomic within a filesystem. {@code java.nio.file} is
     * deliberately not used: it is API 26+ and core-library desugaring does not
     * cover it, so it would crash on the minSdk 21 devices this port targets.
     * Where a rename is unavailable or refused (Windows will not rename onto an
     * existing file) it falls back to libGDX's copy — non-atomic, but the temp
     * file survives a failure there and {@link #load()} recovers from it.
     */
    private boolean replaceWithStaged(FileHandle file, FileHandle temp) {
        return renameStagedOver(file, temp) || copyStagedOver(file, temp);
    }

    /**
     * The good path: one atomic rename, which also removes the staged file.
     *
     * @return false if the platform would not do it, leaving both files untouched
     */
    private boolean renameStagedOver(FileHandle file, FileHandle temp) {
        try {
            java.io.File src = temp.file();
            java.io.File dst = file.file();
            if (src.renameTo(dst)) {
                return true;
            }
            //  Windows refuses to rename onto an existing file.  Removing the
            //  destination first opens the crash window that load() recovers
            //  from -- which is exactly why the staged file must still be on
            //  disk at this point, and is.
            return dst.exists() && dst.delete() && src.renameTo(dst);
        } catch (RuntimeException e) {
            return false;       // no java.io.File behind this handle
        }
    }

    /**
     * The fallback: copy the staged file over the live one, <b>verify the copy,
     * and only then drop the staged file</b>.
     *
     * <p>The ordering is the whole point. A copy is not atomic: the process can
     * die with the live file half-overwritten. If the staged file had already
     * been deleted at that moment there would be nothing left to recover from,
     * and the player would lose the save. So the staged file is the last thing
     * to go, after a re-read has proved the copy actually landed — and if the
     * copy failed or produced something unloadable, it is not dropped at all and
     * {@link #load()} promotes it on the next start.
     *
     * <p>Package-private so a test can drive this path directly: the rename
     * above succeeds on every filesystem CI runs on, so the fallback would
     * otherwise never be exercised.
     */
    boolean copyStagedOver(FileHandle file, FileHandle temp) {
        try {
            temp.copyTo(file);
        } catch (RuntimeException e) {
            logError("could not copy the staged save into place (" + e.getMessage() + ")");
            return false;       // staged file deliberately left where it is
        }
        if (!isUsableSave(file)) {
            logError("the copied save did not read back correctly; keeping the "
                    + "staged copy for recovery");
            return false;
        }
        discardStaged();
        return true;
    }

    private void discardStaged() {
        FileHandle temp = stagingHandle();
        if (temp != null && temp.exists()) {
            try {
                temp.delete();
            } catch (RuntimeException e) {
                // a leftover temp file is harmless: load() only prefers it when
                // the live save is unreadable, and validates it first
            }
        }
    }

    /** Deletes the save. Used by tests and by a future "reset progress". */
    public void delete() {
        FileHandle file = fileHandle();
        if (file != null && file.exists()) {
            file.delete();
        }
        // otherwise the next load would "recover" the save just deleted
        discardStaged();
    }

    /** Where a save is staged before it replaces the live one. */
    public String stagingPath() {
        return path + STAGING_SUFFIX;
    }

    public String path() {
        return path;
    }

    /** Why the last {@link #load()} produced what it did. Empty when normal. */
    public String lastLoadNote() {
        return lastLoadNote;
    }

    private FileHandle stagingHandle() {
        try {
            return Gdx.files != null ? Gdx.files.local(stagingPath()) : null;
        } catch (RuntimeException e) {
            return null;
        }
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
