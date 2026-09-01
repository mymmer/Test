package com.mymmer.castledefense.lwjgl3;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Net;
import com.mymmer.castledefense.platform.HapticEvent;
import com.mymmer.castledefense.platform.NoOpPlatformServices;

/**
 * Desktop platform services.
 *
 * <p>Almost everything a phone offers has no desktop equivalent, so this mostly
 * inherits the no-op behaviour. What it does add is the one thing that makes the
 * desktop loop useful for mobile work: simulated safe-area insets, so a notch
 * can be laid out for without installing an Android build (Phase 10 wires the
 * launcher flag that sets them).
 */
public final class DesktopPlatformServices extends NoOpPlatformServices {

    @Override
    public void vibrate(HapticEvent event) {
        // a desk does not buzz; logged at debug level only so it is visible
        // when checking that the events fire at the right moments
        if (Gdx.app != null) {
            Gdx.app.debug("Haptics", "would vibrate: " + event
                    + " (" + event.suggestedMillis() + "ms)");
        }
    }

    @Override
    public boolean openUrl(String url) {
        Net net = Gdx.net;
        return net != null && net.openURI(url);
    }

    @Override
    public String deviceDescription() {
        return System.getProperty("os.name") + " " + System.getProperty("os.version")
                + " / jvm " + System.getProperty("java.version") + " / desktop";
    }
}
