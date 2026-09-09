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

    /**
     * Bumped whenever Android hands the window a new set of insets.
     *
     * <p>Volatile because the listener runs on the main thread and the game
     * reads it on the GL thread. It exists so the reading side can be free: the
     * result is computed once per actual change rather than once per frame.
     */
    private volatile int insetsGeneration = 1;
    private volatile int computedGeneration;
    private volatile SafeAreaInsets computed = SafeAreaInsets.NONE;

    /**
     * Watches for inset changes so the layout can follow them.
     *
     * <p>Insets are not fixed for the life of a window. They change when the
     * immersive bars are swiped in or time out, on rotation, on a fold, and
     * after a resume — and a layout computed once at startup is wrong from the
     * first of those onwards. Registered by the launcher once the decor view
     * exists, because before that there is nothing to listen to.
     */
    void watchInsets() {
        try {
            View root = activity.getWindow().getDecorView();
            root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
                @Override
                public WindowInsets onApplyWindowInsets(View v, WindowInsets in) {
                    insetsGeneration++;
                    //  Pass it on unchanged: this is an observer, not a
                    //  consumer, and swallowing insets here would break the
                    //  decor view's own layout.
                    return v.onApplyWindowInsets(in);
                }
            });
            root.requestApplyInsets();
        } catch (RuntimeException e) {                       // noqa
            //  No listener is survivable: the first read still computes, and
            //  resize() re-reads on every orientation change.
        }
    }

    /** Forces the next read to recompute — called on resume. */
    void invalidateInsets() {
        insetsGeneration++;
    }

    /**
     * The two inset families, kept apart.
     *
     * <p><b>Obscuring</b> — system bars and the display cutout. Something is
     * drawn over these pixels, so nothing the player must see or press goes
     * there. In immersive mode the bars report zero, which is correct: hidden
     * bars cover nothing.
     *
     * <p><b>Gesture</b> — {@code mandatorySystemGestures}, the strips the system
     * takes a touch from and that an app is <em>not</em> allowed to opt out of.
     * The full {@code systemGestures} region is deliberately not used: it is
     * larger, it includes the back-swipe edges an app may exclude, and treating
     * it as unusable would give away far more of the screen than the platform
     * actually claims.
     *
     * <p>Every accessor is version-gated. {@code getRootWindowInsets} arrives at
     * API 23, cutouts at 28, gesture insets at 29, and the typed {@code
     * getInsets} at 30; minSdk here is 21, so each is asked for only where it
     * exists and its absence simply contributes nothing.
     */
    @Override
    @SuppressWarnings("deprecation")
    public SafeAreaInsets safeAreaInsets() {
        int generation = insetsGeneration;
        if (generation == computedGeneration) {
            return computed;
        }
        SafeAreaInsets result = readInsets();
        computed = result;
        computedGeneration = generation;
        return result;
    }

    @SuppressWarnings("deprecation")
    private SafeAreaInsets readInsets() {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                //  getRootWindowInsets is API 23. Below it there are no
                //  cutouts and no gesture navigation either, so nothing is lost.
                return SafeAreaInsets.NONE;
            }
            View root = activity.getWindow().getDecorView();
            WindowInsets insets = root.getRootWindowInsets();
            if (insets == null) {
                return SafeAreaInsets.NONE;
            }

            int left = 0;
            int right = 0;
            int top = 0;
            int bottom = 0;
            int gestureLeft = 0;
            int gestureRight = 0;
            int gestureTop = 0;
            int gestureBottom = 0;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Insets bars =
                        insets.getInsets(WindowInsets.Type.systemBars());
                left = bars.left;
                right = bars.right;
                top = bars.top;
                bottom = bars.bottom;
                android.graphics.Insets gest = insets.getInsets(
                        WindowInsets.Type.mandatorySystemGestures());
                gestureLeft = gest.left;
                gestureRight = gest.right;
                gestureTop = gest.top;
                gestureBottom = gest.bottom;
            } else {
                left = insets.getSystemWindowInsetLeft();
                right = insets.getSystemWindowInsetRight();
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    android.graphics.Insets gest =
                            insets.getMandatorySystemGestureInsets();
                    gestureLeft = gest.left;
                    gestureRight = gest.right;
                    gestureTop = gest.top;
                    gestureBottom = gest.bottom;
                }
            }

            //  The cutout is an obscuring inset and is merged with the bars
            //  rather than replacing them: a phone can have both.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                DisplayCutout cutout = insets.getDisplayCutout();
                if (cutout != null) {
                    left = Math.max(left, cutout.getSafeInsetLeft());
                    right = Math.max(right, cutout.getSafeInsetRight());
                    top = Math.max(top, cutout.getSafeInsetTop());
                    bottom = Math.max(bottom, cutout.getSafeInsetBottom());
                }
            }

            return new SafeAreaInsets(left, right, top, bottom,
                    gestureLeft, gestureRight, gestureTop, gestureBottom);
        } catch (RuntimeException e) {
            return SafeAreaInsets.NONE;
        }
    }

    /**
     * 18 world units -- about 36 physical pixels, or 1.9 mm, on a 3040x1440
     * phone.
     *
     * <p>Chosen to bridge a fingertip's aiming error without reaching anything
     * the player can see they missed: it is under half a Scout's body width, so
     * a press still has to land on or beside the mob, never on the next one
     * along.
     */
    @Override
    public float touchGrabTolerance() {
        return 18f;
    }

    @Override
    public String deviceDescription() {
        return Build.MANUFACTURER + " " + Build.MODEL
                + " / Android " + Build.VERSION.RELEASE
                + " (API " + Build.VERSION.SDK_INT + ")";
    }
}
