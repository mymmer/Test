package com.mymmer.castledefense.platform;

/**
 * The only door from game code to the device.
 *
 * <p>It exists from the start so Android APIs never leak into gameplay later —
 * the point at which that becomes painful is exactly the point at which nobody
 * wants to fix it. Desktop supplies a no-op implementation; Android supplies a
 * real one; tests supply a recording one.
 *
 * <p>Deliberately small. Nothing here is required for the game to run: every
 * method may do nothing, and the game must behave identically. Google Play,
 * achievements and monetisation are <em>not</em> here and will not be added
 * without an explicit request.
 */
public interface PlatformServices {

    /** Short haptic feedback. May be ignored entirely. */
    void vibrate(HapticEvent event);

    /** True when the device can vibrate and the player has not disabled it. */
    boolean hapticsAvailable();

    /** Turns haptics on or off; persisted by the caller, not here. */
    void setHapticsEnabled(boolean enabled);

    /** Opens a URL in the platform browser. Returns false if it could not. */
    boolean openUrl(String url);

    /** Offers text to the platform share sheet. Returns false if unsupported. */
    boolean share(String subject, String text);

    /** Screen edges the UI must avoid. Never null. */
    SafeAreaInsets safeAreaInsets();

    /** Something identifiable for a crash log, e.g. "Pixel 7 / Android 14". */
    String deviceDescription();
}
