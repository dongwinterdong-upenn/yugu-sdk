package com.shengzhiai.yugu.errors;

/**
 * Error categories shared by every Yugu SDK (spec/errors.json {@code categories}).
 *
 * <p>Each category maps to one exception class, see {@link YuguErrors#exceptionClassFor(ErrorCategory)}.
 */
public enum ErrorCategory {
    /** Connection failed, was reset or reconnect attempts were exhausted. */
    NETWORK,
    /** Connect, read or result timeout. */
    TIMEOUT,
    /** Missing or invalid credentials, bad signature, expired token. */
    AUTH,
    /** Credentials are valid but not allowed to do this. */
    PERMISSION,
    /** Request parameters were rejected. */
    INVALID_PARAM,
    /** Resource does not exist. */
    NOT_FOUND,
    /** Conflict, for example an idempotency key still in progress or reused. */
    CONFLICT,
    /** Rate or concurrency limit, retry later. */
    RATE_LIMIT,
    /** Quota or balance exhausted. */
    QUOTA,
    /** Platform internal error. */
    SERVER,
    /** Upstream scoring service unavailable. */
    UPSTREAM,
    /** Audio quality problem found by the platform or by the local precheck. */
    AUDIO,
    /** Operation not allowed in the current client or session state. */
    STATE,
    /** The caller cancelled the request or session. */
    CANCELLED,
    /** Response or frame could not be understood. */
    PROTOCOL,
    /** Anything else. */
    UNKNOWN;

    /**
     * Parses a category name as used in spec/errors.json.
     *
     * @param name category name, case insensitive
     * @return the category, {@link #UNKNOWN} for null or unknown names
     */
    public static ErrorCategory parse(String name) {
        if (name == null) {
            return UNKNOWN;
        }
        for (ErrorCategory c : values()) {
            if (c.name().equalsIgnoreCase(name.trim())) {
                return c;
            }
        }
        return UNKNOWN;
    }
}
