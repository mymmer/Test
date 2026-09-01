package com.mymmer.castledefense.android;

import android.os.Bundle;
import com.badlogic.gdx.backends.android.AndroidApplication;
import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration;
import com.mymmer.castledefense.CastleDefenseGame;

/**
 * Android entry point.
 *
 * <p>The only Android-aware code in the project besides the platform services
 * that arrive in Phase 3. It constructs the same {@link CastleDefenseGame} the
 * desktop launcher does — the game itself cannot tell which one started it.
 *
 * <p>Back-button handling (playing → pause, submenu → parent, menu → platform
 * default) is a Phase 10 item; until there are game states to move between,
 * the platform default applies.
 */
public class AndroidLauncher extends AndroidApplication {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        AndroidApplicationConfiguration config = new AndroidApplicationConfiguration();
        config.useImmersiveMode = true;     // hide the nav bar; more world, fewer mis-taps
        config.useAccelerometer = false;
        config.useCompass = false;
        config.useGyroscope = false;
        config.numSamples = 0;              // MSAA off: fill rate matters more on mobile
        config.useWakelock = true;

        initialize(new CastleDefenseGame(new AndroidPlatformServices(this)), config);
    }
}
