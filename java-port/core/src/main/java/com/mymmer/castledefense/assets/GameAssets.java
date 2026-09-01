package com.mymmer.castledefense.assets;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.assets.AssetManager;
import com.badlogic.gdx.graphics.g2d.TextureAtlas;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.ObjectSet;

/**
 * The single owner of every loaded GPU resource.
 *
 * <p>One {@link AssetManager} for the whole game, created once and disposed
 * once. Nothing else in the codebase may construct a {@code Texture} — that rule
 * is what stops textures being created inside a render loop, which is the
 * classic way a libGDX game leaks memory on Android.
 *
 * <p>Only what is needed is kept resident: shared core assets, the active skin,
 * and the UI. Switching skins unloads the old atlas before loading the new one,
 * so two skins are never resident at once.
 */
public final class GameAssets implements AtlasSource, Disposable {

    private static final String TAG = "GameAssets";

    private final AssetManager manager;

    public GameAssets() {
        this(new AssetManager());
    }

    /** Injection point for tests that want to observe the manager. */
    public GameAssets(AssetManager manager) {
        this.manager = manager;
    }

    public AssetManager manager() {
        return manager;
    }

    @Override
    public void load(String atlasPath) {
        if (atlasPath == null || atlasPath.isEmpty()) {
            return;
        }
        if (manager.isLoaded(atlasPath, TextureAtlas.class)) {
            return;
        }
        manager.load(atlasPath, TextureAtlas.class);
        manager.finishLoadingAsset(atlasPath);
        log("loaded atlas " + atlasPath);
    }

    @Override
    public boolean isLoaded(String atlasPath) {
        return atlasPath != null && !atlasPath.isEmpty()
                && manager.isLoaded(atlasPath, TextureAtlas.class);
    }

    @Override
    public ObjectSet<String> regionNames(String atlasPath) {
        ObjectSet<String> names = new ObjectSet<>();
        if (!isLoaded(atlasPath)) {
            return names;
        }
        TextureAtlas atlas = manager.get(atlasPath, TextureAtlas.class);
        for (TextureAtlas.AtlasRegion region : atlas.getRegions()) {
            names.add(region.name);
        }
        return names;
    }

    @Override
    public TextureRegion region(String atlasPath, String name) {
        if (!isLoaded(atlasPath)) {
            return null;
        }
        return manager.get(atlasPath, TextureAtlas.class).findRegion(name);
    }

    @Override
    public void unload(String atlasPath) {
        if (isLoaded(atlasPath)) {
            manager.unload(atlasPath);
            log("unloaded atlas " + atlasPath);
        }
    }

    /** Progress of any background loading, 0..1. */
    public float progress() {
        return manager.getProgress();
    }

    /** Advances background loading; returns true when everything is ready. */
    public boolean update() {
        return manager.update();
    }

    @Override
    public void dispose() {
        manager.dispose();
    }

    private void log(String message) {
        if (Gdx.app != null) {
            Gdx.app.log(TAG, message);
        }
    }
}
