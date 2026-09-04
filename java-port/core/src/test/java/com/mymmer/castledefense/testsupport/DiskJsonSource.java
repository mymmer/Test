package com.mymmer.castledefense.testsupport;

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
 * <p>The same reader the enemy, defence and boss tests each had privately; a run
 * needs all three tables at once, so it lives here where every package can see
 * it. Plain {@code java.io} rather than {@code Gdx.files}: these are pure
 * gameplay tests and booting a headless application for them would add a shared,
 * order-dependent global for no benefit.
 *
 * <p>The Gradle test task sets {@code workingDir} to {@code java-port/assets},
 * so asset-relative paths resolve unchanged — which means a typo in a shipped
 * data file fails the build rather than a fixture.
 */
public final class DiskJsonSource implements JsonSource {

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
