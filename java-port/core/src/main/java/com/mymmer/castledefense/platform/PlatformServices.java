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

    /**
     * Extra reach, in world units, when acquiring a grab by touch.
     *
     * <p>Zero on a mouse, because a mouse points at a pixel. A fingertip does
     * not: a Scout's grab box is 42x50 world units, which on a 3040x1440 phone
     * is 24 x 29 dp -- half Android's 48 dp minimum target and smaller than the
     * contact patch of the finger pressing it. Isolated mobs are therefore
     * genuinely hard to hit, which is measured in ANDROID_DEVICE.md.
     *
     * <p>This is an <b>acquisition</b> tolerance and nothing else. Gameplay
     * hitboxes, collision, damage and mass are untouched: it only widens the
     * search when an exact press found nothing, so the same press that would
     * have failed now finds the mob the player was plainly aiming at.
     */
    default float touchGrabTolerance() {
        return 0f;
    }

    /** Something identifiable for a crash log, e.g. "Pixel 7 / Android 14". */
    String deviceDescription();
}
