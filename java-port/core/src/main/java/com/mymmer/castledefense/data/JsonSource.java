package com.mymmer.castledefense.data;

import com.badlogic.gdx.utils.JsonValue;

/**
 * Where JSON documents come from.
 *
 * <p>An interface so tests can supply documents inline instead of writing files,
 * and so a future source (a downloaded balance patch, a mod folder) can be
 * slotted in without touching the parsers.
 */
public interface JsonSource {

    /**
     * Reads and parses one document.
     *
     * @param path path relative to the assets root, e.g. {@code "data/difficulties.json"}
     * @throws DataException if the file is missing or is not valid JSON
     */
    JsonValue read(String path);

    /** True when the document exists. */
    boolean exists(String path);
}
