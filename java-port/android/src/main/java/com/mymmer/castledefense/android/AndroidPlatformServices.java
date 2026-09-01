package com.mymmer.castledefense.android;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.view.DisplayCutout;
import android.view.View;
import android.view.WindowInsets;
import com.mymmer.castledefense.platform.HapticEvent;
import com.mymmer.castledefense.platform.PlatformServices;
import com.mymmer.castledefense.platform.SafeAreaInsets;

/**
 * Android platform services: the only class in the project that touches
 * {@code android.*} beyond the launcher itself.
 *
 * <p>Everything is defensive. A device with no vibrator, a share intent nothing
 * can handle, an OS version without {@code DisplayCutout} — each must degrade to
 * "did nothing" rather than to an exception, because none of it is required for
 * the game to be playable.
 */
public final class AndroidPlatformServices implements PlatformServices {

    private final Activity activity;
    private final Vibrator vibrator;
    private boolean hapticsEnabled = true;

    public AndroidPlatformServices(Activity activity) {
        this.activity = activity;
        this.vibrator = resolveVibrator(activity);
    }

    private static Vibrator resolveVibrator(Context context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                VibratorManager manager =
                        (VibratorManager) context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                return manager != null ? manager.getDefaultVibrator() : null;
            }
            return (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public void vibrate(HapticEvent event) {
        if (!hapticsAvailable() || event == null) {
            return;
        }
        try {
            int millis = event.suggestedMillis();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(
                        millis, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vibrator.vibrate(millis);
            }
        } catch (RuntimeException ignored) {
            // a buzz is never worth an exception
        }
    }

    @Override
    public boolean hapticsAvailable() {
        return hapticsEnabled && vibrator != null && vibrator.hasVibrator();
    }

    @Override
    public void setHapticsEnabled(boolean enabled) {
        hapticsEnabled = enabled;
    }

    @Override
    public boolean openUrl(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public boolean share(String subject, String text) {
        try {
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/plain");
            if (subject != null) {
                intent.putExtra(Intent.EXTRA_SUBJECT, subject);
            }
            intent.putExtra(Intent.EXTRA_TEXT, text);
            activity.startActivity(Intent.createChooser(intent, subject));
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public SafeAreaInsets safeAreaInsets() {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                return SafeAreaInsets.NONE;     // no cutouts before Android 9
            }
            View root = activity.getWindow().getDecorView();
            WindowInsets insets = root.getRootWindowInsets();
            if (insets == null) {
                return SafeAreaInsets.NONE;
            }
            DisplayCutout cutout = insets.getDisplayCutout();
            if (cutout == null) {
                return SafeAreaInsets.NONE;
            }
            return new SafeAreaInsets(
                    cutout.getSafeInsetLeft(), cutout.getSafeInsetRight(),
                    cutout.getSafeInsetTop(), cutout.getSafeInsetBottom());
        } catch (RuntimeException e) {
            return SafeAreaInsets.NONE;
        }
    }

    @Override
    public String deviceDescription() {
        return Build.MANUFACTURER + " " + Build.MODEL
                + " / Android " + Build.VERSION.RELEASE
                + " (API " + Build.VERSION.SDK_INT + ")";
    }
}
