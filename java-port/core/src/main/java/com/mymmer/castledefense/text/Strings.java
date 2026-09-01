package com.mymmer.castledefense.text;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.I18NBundle;
import java.util.Locale;

/**
 * Every piece of text the player reads.
 *
 * <p>The foundation for translation, kept as thin as it can be: one lookup by
 * key, backed by libGDX's {@link I18NBundle}. The game ships in English and
 * there is no language picker yet — what matters now is that UI code says
 * {@code Strings.get("menu.play")} rather than {@code "PLAY"}, so adding a
 * language later is a new {@code .properties} file rather than a hunt through
 * the source.
 *
 * <p>Deliberately not over-built: no pluralisation rules, no gender handling, no
 * runtime language switching. Those can be added when there is a second
 * language to justify them.
 *
 * <p>A missing key returns {@code !key!} rather than throwing. A half-translated
 * build should look wrong, not crash.
 */
public final class Strings {

    private static final String BUNDLE_PATH = "i18n/strings";

    private static I18NBundle bundle;
    private static Locale locale = Locale.ENGLISH;

    private Strings() {
    }

    /** Loads the bundle for a locale. Falls back to keys if it cannot. */
    public static void load(Locale requested) {
        locale = requested != null ? requested : Locale.ENGLISH;
        try {
            FileHandle handle = Gdx.files.internal(BUNDLE_PATH);
            bundle = I18NBundle.createBundle(handle, locale);
        } catch (RuntimeException e) {
            bundle = null;
            if (Gdx.app != null) {
                Gdx.app.error("Strings", "no string bundle for " + locale
                        + " (" + e.getMessage() + "); showing raw keys");
            }
        }
    }

    /** Loads the platform's default locale. */
    public static void load() {
        load(Locale.getDefault());
    }

    /** Drops the bundle — used by tests to check the fallback behaviour. */
    public static void unload() {
        bundle = null;
    }

    public static boolean isLoaded() {
        return bundle != null;
    }

    public static Locale locale() {
        return locale;
    }

    /** The text for a key, or {@code !key!} when it is missing. */
    public static String get(String key) {
        if (bundle == null) {
            return "!" + key + "!";
        }
        try {
            return bundle.get(key);
        } catch (RuntimeException missing) {
            return "!" + key + "!";
        }
    }

    /** The text for a key with {0}-style arguments filled in. */
    public static String format(String key, Object... args) {
        if (bundle == null) {
            return "!" + key + "!";
        }
        try {
            return bundle.format(key, args);
        } catch (RuntimeException missing) {
            return "!" + key + "!";
        }
    }
}
