package com.mymmer.castledefense.testsupport;

import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.ObjectMap;
import com.badlogic.gdx.utils.ObjectSet;
import com.mymmer.castledefense.assets.AtlasSource;

/**
 * An {@link AtlasSource} with no textures in it.
 *
 * <p>Loading a real atlas needs a GL context; the skin system's decisions do
 * not. This records what was loaded and unloaded so tests can assert that a
 * switch releases the previous atlas before installing the next.
 */
public final class FakeAtlasSource implements AtlasSource {

    private final ObjectMap<String, ObjectSet<String>> available = new ObjectMap<>();
    private final ObjectSet<String> loaded = new ObjectSet<>();
    private final Array<String> loadLog = new Array<>();
    private final Array<String> unloadLog = new Array<>();
    private String failOnLoad;

    /** Declares an atlas and the regions it contains. */
    public FakeAtlasSource withAtlas(String path, String... regions) {
        ObjectSet<String> set = new ObjectSet<>();
        for (String r : regions) {
            set.add(r);
        }
        available.put(path, set);
        return this;
    }

    /** Makes one atlas throw when loaded, imitating a corrupt file. */
    public FakeAtlasSource failing(String path) {
        failOnLoad = path;
        return this;
    }

    @Override
    public void load(String atlasPath) {
        loadLog.add(atlasPath);
        if (atlasPath.equals(failOnLoad)) {
            throw new IllegalStateException("simulated atlas failure");
        }
        if (!available.containsKey(atlasPath)) {
            throw new IllegalStateException("no such atlas: " + atlasPath);
        }
        loaded.add(atlasPath);
    }

    @Override
    public boolean isLoaded(String atlasPath) {
        return loaded.contains(atlasPath);
    }

    @Override
    public ObjectSet<String> regionNames(String atlasPath) {
        ObjectSet<String> set = available.get(atlasPath);
        return isLoaded(atlasPath) && set != null ? set : new ObjectSet<String>();
    }

    @Override
    public TextureRegion region(String atlasPath, String name) {
        return null;        // no GL, no regions -- the skin system never needs one
    }

    @Override
    public void unload(String atlasPath) {
        unloadLog.add(atlasPath);
        loaded.remove(atlasPath);
    }

    public Array<String> loadLog() {
        return loadLog;
    }

    public Array<String> unloadLog() {
        return unloadLog;
    }

    public int loadedCount() {
        return loaded.size;
    }
}
