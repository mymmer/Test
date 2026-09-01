package com.mymmer.castledefense.data;

/**
 * A data file is missing, malformed, or describes something impossible.
 *
 * <p>Thrown at load time, never mid-frame. Content problems must surface as a
 * loud failure during startup on a developer build rather than as a subtle
 * gameplay difference three waves in.
 */
public class DataException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DataException(String message) {
        super(message);
    }

    public DataException(String message, Throwable cause) {
        super(message, cause);
    }
}
