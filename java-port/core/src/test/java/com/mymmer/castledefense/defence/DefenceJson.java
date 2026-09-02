package com.mymmer.castledefense.defence;

import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import com.mymmer.castledefense.data.DataException;
import com.mymmer.castledefense.data.JsonSource;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Reads the real shipped data files straight off disk.
 *
 * <p>Plain {@code java.io} rather than {@code Gdx.files}, so the defence tests
 * need no headless application at all — they are pure gameplay and booting a
 * libGDX backend for them would only add a shared, order-dependent global. The
 * Gradle test task sets {@code workingDir} to {@code java-port/assets}, so the
 * asset-relative paths the game uses resolve unchanged.
 *
 * <p>Reading the shipped file rather than an inline string is the point: it
 * means a typo in {@code defences.json} fails the build.
 */
final class DefenceJson implements JsonSource {

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
