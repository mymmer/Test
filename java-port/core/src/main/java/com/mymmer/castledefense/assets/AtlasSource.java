package com.mymmer.castledefense.assets;

import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.utils.ObjectSet;

/**
 * Where atlases come from.
 *
 * <p>An interface for one reason: loading a {@code TextureAtlas} needs a GL
 * context, and the skin system's decisions — which atlas to load, what to do
 * when a region is missing, when to unload the previous skin — must be testable
 * without one. The production implementation wraps {@code AssetManager}; tests
 * supply a fake with a known region list.
 */
public interface AtlasSource {

    /** Loads (or reuses) an atlas and blocks until it is ready. */
    void load(String atlasPath);

    boolean isLoaded(String atlasPath);

    /** Region names the atlas provides. Empty when it is not loaded. */
    ObjectSet<String> regionNames(String atlasPath);

    /** A region, or null when the atlas has no such region. */
    TextureRegion region(String atlasPath, String name);

    /** Releases the atlas and its texture. Safe to call when not loaded. */
    void unload(String atlasPath);
}
