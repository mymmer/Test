package com.mymmer.castledefense.enemy;

import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import com.mymmer.castledefense.data.DataException;
import com.mymmer.castledefense.data.JsonSource;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Reads the shipped data files straight off disk, with no libGDX backend.
 *
 * <p>Same rationale as the defence tests' reader: the enemy tests are pure
 * gameplay, and booting a headless application for them would add a shared,
 * order-dependent global for no benefit. The Gradle test task sets
 * {@code workingDir} to {@code java-port/assets}, so asset-relative paths
 * resolve unchanged — and reading the real file means a typo in
 * {@code enemies.json} fails the build.
 */
final class EnemyJson implements JsonSource {

    private final JsonReader reader = new JsonReader();

    @Override
    public JsonValue read(String path) {
        File file = new File(path);
        if (!file.isFile()) {
            throw new DataException("data file not found: " + file.getAbsolutePath());
        }
        try {
            return reader.parse(new String(
                    Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            throw new DataException("could not read " + path, e);
        }
    }

    @Override
    public boolean exists(String path) {
        return new File(path).isFile();
    }
}
