package com.shengzhiai.yugu;

/** Log levels, from silent to most verbose. The default is {@link #WARN}. */
public enum LogLevel {
    /** Nothing is logged. */
    OFF,
    /** SDK internal problems, for example a listener that threw. */
    ERROR,
    /** Retries, reconnects, precheck warnings and stream failures (default). */
    WARN,
    /** Session state changes and call summaries. */
    INFO,
    /** Every request, response and frame type, with secrets redacted. */
    DEBUG;

    /**
     * @param messageLevel level of a message
     * @return true when a message of that level passes this threshold
     */
    public boolean allows(LogLevel messageLevel) {
        return this != OFF && messageLevel != OFF && messageLevel.ordinal() <= ordinal();
    }
}
