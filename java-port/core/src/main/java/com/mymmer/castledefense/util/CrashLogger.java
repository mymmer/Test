package com.mymmer.castledefense.util;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * The Java form of the Python crash log.
 *
 * <p>Same purpose, same promise: when something blows up — especially in the
 * late-game boss paths that are painful to reproduce — the whole stack trace
 * lands in a file the player can send, together with a one-line description of
 * what the game was doing at the time.
 *
 * <p>Differences forced by the platform: the file goes to {@code Gdx.files
 * .local} (an app's asset folder is read-only on Android), and rotation is done
 * by hand rather than by {@code logging.handlers} — one backup, half a megabyte,
 * which is plenty for the last few sessions.
 *
 * <p>Nothing here may throw. A logger that crashes while reporting a crash is
 * worse than no logger, so every file operation is guarded and failures fall
 * back to the platform log.
 */
public final class CrashLogger {

    public static final String DEFAULT_PATH = "castle-defense/logs/game_errors.log";
    private static final long MAX_BYTES = 512L * 1024L;
    private static final String TAG = "CastleDefense";

    /** Supplies the "what was happening" line. Kept tiny and failure-proof. */
    public interface ContextProvider {
        String describe();
    }

    private final String path;
    private final SimpleDateFormat stamp =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT);

    private ContextProvider context;

    public CrashLogger() {
        this(DEFAULT_PATH);
    }

    public CrashLogger(String path) {
        this.path = path;
    }

    /** Registers the source of the state snapshot written beside a crash. */
    public void setContextProvider(ContextProvider provider) {
        this.context = provider;
    }

    public String path() {
        return path;
    }

    /**
     * Installs a handler for exceptions that escape any thread.
     *
     * <p>The libGDX equivalent of wrapping {@code main()} in {@code try/except}:
     * the render thread's exceptions come through here, get written with their
     * full trace, and are then handed to whatever handler was already in place
     * so the platform still knows the app died.
     */
    public void installGlobalHandler() {
        final Thread.UncaughtExceptionHandler previous =
                Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable throwable) {
                logCrash(throwable, "thread " + thread.getName());
                if (previous != null) {
                    previous.uncaughtException(thread, throwable);
                }
            }
        });
    }

    /** Records a fatal error with its full stack trace and the game's state. */
    public void logCrash(Throwable throwable, String where) {
        String trace = stackTraceOf(throwable);
        String state = describeState();
        StringBuilder sb = new StringBuilder(trace.length() + 256);
        sb.append(stamp.format(new Date())).append("  CRITICAL  unhandled exception in ")
                .append(where).append('\n')
                .append(trace).append('\n')
                .append(stamp.format(new Date())).append("  CRITICAL  state at crash: ")
                .append(state).append('\n');
        String entry = sb.toString();

        // console first: it is the one output that cannot fail
        if (Gdx.app != null) {
            Gdx.app.error(TAG, "--- CRASH in " + where + " ---\n" + entry);
        } else {
            System.err.println(entry);
        }
        append(entry);
    }

    /** Records a non-fatal note — a failed skin load, a save that would not write. */
    public void logWarning(String message) {
        append(stamp.format(new Date()) + "  WARNING   " + message + "\n");
    }

    public void logInfo(String message) {
        append(stamp.format(new Date()) + "  INFO      " + message + "\n");
    }

    private String describeState() {
        if (context == null) {
            return "no context provider registered";
        }
        try {
            String s = context.describe();
            return s != null ? s : "context provider returned null";
        } catch (RuntimeException e) {
            // never crash inside the crash handler
            return "state unavailable (" + e + ")";
        }
    }

    private static String stackTraceOf(Throwable throwable) {
        if (throwable == null) {
            return "(no throwable)";
        }
        StringWriter sw = new StringWriter();
        throwable.printStackTrace(new PrintWriter(sw));
        return sw.toString().trim();
    }

    private void append(String entry) {
        try {
            if (Gdx.files == null) {
                return;
            }
            FileHandle file = Gdx.files.local(path);
            rotateIfNeeded(file);
            file.writeString(entry, true, "UTF-8");
        } catch (RuntimeException e) {
            if (Gdx.app != null) {
                Gdx.app.error(TAG, "could not write the crash log: " + e.getMessage());
            }
        }
    }

    private void rotateIfNeeded(FileHandle file) {
        try {
            if (!file.exists() || file.length() < MAX_BYTES) {
                return;
            }
            FileHandle backup = Gdx.files.local(path + ".1");
            if (backup.exists()) {
                backup.delete();
            }
            file.moveTo(backup);
        } catch (RuntimeException e) {
            // rotation is a nicety; losing it must not lose the log entry
            if (Gdx.app != null) {
                Gdx.app.error(TAG, "log rotation failed: " + e.getMessage());
            }
        }
    }
}
