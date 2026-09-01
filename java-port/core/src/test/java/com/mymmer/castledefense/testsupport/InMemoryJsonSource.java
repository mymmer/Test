package com.mymmer.castledefense.testsupport;

import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import com.badlogic.gdx.utils.ObjectMap;
import com.mymmer.castledefense.data.DataException;
import com.mymmer.castledefense.data.JsonSource;

/** A {@link JsonSource} backed by strings, so tests need no files. */
public final class InMemoryJsonSource implements JsonSource {

    private final ObjectMap<String, String> documents = new ObjectMap<>();
    private final JsonReader reader = new JsonReader();

    public InMemoryJsonSource put(String path, String json) {
        documents.put(path, json);
        return this;
    }

    @Override
    public JsonValue read(String path) {
        String doc = documents.get(path);
        if (doc == null) {
            throw new DataException("data file not found: " + path);
        }
        try {
            return reader.parse(doc);
        } catch (RuntimeException e) {
            throw new DataException("malformed JSON in " + path, e);
        }
    }

    @Override
    public boolean exists(String path) {
        return documents.containsKey(path);
    }
}
