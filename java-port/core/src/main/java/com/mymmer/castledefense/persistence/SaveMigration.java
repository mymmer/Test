package com.mymmer.castledefense.persistence;

import com.badlogic.gdx.utils.JsonValue;

/**
 * One step in the save-format upgrade chain: v(n) → v(n+1).
 *
 * <p>Migrations are registered in order and applied in sequence, so a save from
 * any older version reaches the current one by walking the chain rather than by
 * a pile of special cases. A future update must never silently invalidate a
 * player's progress.
 */
public interface SaveMigration {

    /** The version this step upgrades from. */
    int fromVersion();

    /** The version this step produces — always {@code fromVersion() + 1}. */
    int toVersion();

    /**
     * Rewrites the document in place (or returns a new one).
     *
     * @param root the parsed save at {@link #fromVersion()}
     * @return the same document, now shaped for {@link #toVersion()}
     */
    JsonValue migrate(JsonValue root);
}
