package com.mymmer.castledefense.data;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;

/** Reads JSON from the packaged assets via {@code Gdx.files.internal}. */
public final class AssetJsonSource implements JsonSource {

    private final JsonReader reader = new JsonReader();

    @Override
    public JsonValue read(String path) {
        FileHandle handle = Gdx.files.internal(path);
        if (!handle.exists()) {
            throw new DataException("data file not found: " + path);
        }
        try {
            return reader.parse(handle);
        } catch (RuntimeException e) {
            throw new DataException("malformed JSON in " + path, e);
        }
    }

    @Override
    public boolean exists(String path) {
        return Gdx.files.internal(path).exists();
    }
}
