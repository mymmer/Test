package com.mymmer.castledefense.platform;

/**
 * A platform that can do nothing at all.
 *
 * <p>The desktop default, the test default, and the safety net if a launcher
 * ever forgets to supply one. Every method is a well-behaved no-op, which is
 * what lets the rule "nothing in the game may depend on platform services" be
 * true rather than aspirational.
 */
public class NoOpPlatformServices implements PlatformServices {

    private boolean hapticsEnabled = true;
    private SafeAreaInsets insets = SafeAreaInsets.NONE;

    @Override
    public void vibrate(HapticEvent event) {
        // nothing to buzz
    }

    @Override
    public boolean hapticsAvailable() {
        return false;
    }

    @Override
    public void setHapticsEnabled(boolean enabled) {
        hapticsEnabled = enabled;
    }

    public boolean isHapticsEnabled() {
        return hapticsEnabled;
    }

    @Override
    public boolean openUrl(String url) {
        return false;
    }

    @Override
    public boolean share(String subject, String text) {
        return false;
    }

    @Override
    public SafeAreaInsets safeAreaInsets() {
        return insets;
    }

    /** Lets the desktop launcher imitate a phone's cutout while testing layout. */
    public void setSafeAreaInsets(SafeAreaInsets insets) {
        this.insets = insets != null ? insets : SafeAreaInsets.NONE;
    }

    @Override
    public String deviceDescription() {
        return System.getProperty("os.name", "unknown") + " / desktop";
    }
}
