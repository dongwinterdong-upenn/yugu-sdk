package com.shengzhiai.yugu;

/**
 * Log sink. The SDK never passes secret keys, signatures, tokens or audio bytes; app keys appear as
 * their first four characters followed by {@code ***}.
 *
 * <p>Bridge to SLF4J in one line:
 * <pre>{@code
 * Logger slf = LoggerFactory.getLogger("yugu");
 * YuguLogger sink = (level, tag, message, error) -> slf.info("[{}] {}", tag, message, error);
 * }</pre>
 */
@FunctionalInterface
public interface YuguLogger {
    /**
     * @param level   message level, never {@link LogLevel#OFF}
     * @param tag     component: client, http, stream or precheck
     * @param message message
     * @param error   related error, may be null
     */
    void log(LogLevel level, String tag, String message, Throwable error);
}
