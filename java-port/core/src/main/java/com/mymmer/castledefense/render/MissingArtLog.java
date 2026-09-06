package com.mymmer.castledefense.render;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.utils.ObjectSet;
import com.mymmer.castledefense.assets.AnimationState;
import com.mymmer.castledefense.assets.VisualId;

/**
 * Says what artwork is missing — once, not sixty times a second.
 *
 * <p>A renderer that logs a missing region from inside a draw method produces
 * one line per entity per frame, which on a busy wave is thousands of lines a
 * second. That is not a diagnostic; it is a way of hiding every other message in
 * the log and slowing the frame down while doing it.
 *
 * <p>So each distinct combination of <em>skin, visual and animation state</em> is
 * reported exactly once, with everything needed to act on it:
 *
 * <pre>
 *   [art] skin 'nordic' has no artwork for gargoyle/ATTACK -- drawing it procedurally
 * </pre>
 *
 * <p>The set is cleared when a skin is loaded, so switching skins reports the new
 * skin's gaps rather than staying silent because the old one had the same ones.
 */
public final class MissingArtLog {

    private static final ObjectSet<String> REPORTED = new ObjectSet<>();

    private MissingArtLog() {
    }

    /** Reports one gap, the first time it is seen. */
    public static void warnOnce(String skinId, VisualId id, AnimationState state) {
        String key = skinId + "/" + id.key() + "/" + (state == null ? "-" : state.name());
        if (!REPORTED.add(key)) {
            return;
        }
        String message = "[art] skin '" + skinId + "' has no artwork for "
                + id.key() + "/" + (state == null ? "any state" : state.name())
                + " -- drawing it procedurally";
        if (Gdx.app != null) {
            Gdx.app.log("CastleDefense", message);
        } else {
            System.out.println(message);
        }
    }

    /** Called when a skin is loaded, so the new skin's gaps are reported too. */
    public static void reset() {
        REPORTED.clear();
    }

    /** How many distinct gaps have been reported. For tests and the overlay. */
    public static int reportedCount() {
        return REPORTED.size;
    }

    /** Whether a particular gap has been reported. For tests. */
    public static boolean hasReported(String skinId, VisualId id, AnimationState state) {
        return REPORTED.contains(skinId + "/" + id.key() + "/"
                + (state == null ? "-" : state.name()));
    }
}
