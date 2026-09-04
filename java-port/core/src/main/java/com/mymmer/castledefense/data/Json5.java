package com.mymmer.castledefense.data;

import com.badlogic.gdx.utils.JsonValue;

/**
 * Strict field accessors for {@link JsonValue}.
 *
 * <p>libGDX's own getters return silent defaults for a missing field, which is
 * exactly wrong for content: a typo'd key would become a zero-health enemy that
 * nobody notices until a parity test fails. Everything here either returns the
 * value or throws {@link DataException} naming the file, the object and the
 * field.
 *
 * <p>Optional fields are explicit: {@code optFloat(…, default)} says in the
 * source that the omission is intended.
 */
public final class Json5 {

    private Json5() {
    }

    public static JsonValue child(JsonValue parent, String name, String where) {
        JsonValue v = parent.get(name);
        if (v == null) {
            throw new DataException(where + ": missing object '" + name + "'");
        }
        return v;
    }

    public static String string(JsonValue v, String name, String where) {
        require(v, name, where);
        String s = v.getString(name);
        if (s == null || s.trim().isEmpty()) {
            throw new DataException(where + ": '" + name + "' must not be blank");
        }
        return s;
    }

    public static float number(JsonValue v, String name, String where) {
        require(v, name, where);
        JsonValue f = v.get(name);
        if (!f.isNumber()) {
            throw new DataException(where + ": '" + name + "' must be a number, got "
                    + f.type());
        }
        return f.asFloat();
    }

    public static float optNumber(JsonValue v, String name, float fallback) {
        JsonValue f = v.get(name);
        return f != null && f.isNumber() ? f.asFloat() : fallback;
    }

    /**
     * A <b>time-domain</b> number: seconds, as a {@code double}.
     *
     * <p>Separate from {@link #number} so that a duration is visibly a duration
     * at the call site and cannot quietly be parsed into a float. Every
     * interval, delay, lifetime and cooldown in the data files comes through
     * here. See {@code SIMULATION.md}, "Gameplay time is double".
     */
    public static double seconds(JsonValue v, String name, String where) {
        require(v, name, where);
        JsonValue f = v.get(name);
        if (!f.isNumber()) {
            throw new DataException(where + ": '" + name + "' must be a number of seconds, got "
                    + f.type());
        }
        return f.asDouble();
    }

    /** {@link #seconds} with a fallback, for optional durations. */
    public static double optSeconds(JsonValue v, String name, double fallback) {
        JsonValue f = v.get(name);
        return f != null && f.isNumber() ? f.asDouble() : fallback;
    }

    public static boolean bool(JsonValue v, String name, String where) {
        require(v, name, where);
        JsonValue f = v.get(name);
        if (!f.isBoolean()) {
            throw new DataException(where + ": '" + name + "' must be true or false");
        }
        return f.asBoolean();
    }

    public static boolean optBool(JsonValue v, String name, boolean fallback) {
        JsonValue f = v.get(name);
        return f != null && f.isBoolean() ? f.asBoolean() : fallback;
    }

    public static String optString(JsonValue v, String name, String fallback) {
        JsonValue f = v.get(name);
        return f != null && f.isString() ? f.asString() : fallback;
    }

    public static int optInt(JsonValue v, String name, int fallback) {
        JsonValue f = v.get(name);
        return f != null && f.isNumber() ? f.asInt() : fallback;
    }

    /** A value in [lo, hi], or a {@link DataException} explaining the range. */
    public static float inRange(float value, float lo, float hi, String name, String where) {
        if (!(value >= lo) || !(value <= hi) || Float.isNaN(value)) {
            throw new DataException(where + ": '" + name + "' must be between " + lo
                    + " and " + hi + ", got " + value);
        }
        return value;
    }

    private static void require(JsonValue v, String name, String where) {
        if (v.get(name) == null) {
            throw new DataException(where + ": missing required field '" + name + "'");
        }
    }
}
