package com.shengzhiai.yugu.internal;

import com.shengzhiai.yugu.LogLevel;
import com.shengzhiai.yugu.YuguLogger;

import java.io.PrintStream;
import java.time.Instant;

/** Level filter in front of the configured sink. Sink failures are swallowed. */
public final class Log {
    private final LogLevel level;
    private final YuguLogger sink;

    /**
     * @param level threshold
     * @param sink  sink, null for standard error
     */
    public Log(LogLevel level, YuguLogger sink) {
        this.level = level == null ? LogLevel.WARN : level;
        this.sink = sink == null ? Log::console : sink;
    }

    /** @return true when DEBUG messages are written */
    public boolean isDebug() {
        return level.allows(LogLevel.DEBUG);
    }

    /** @return true when INFO messages are written */
    public boolean isInfo() {
        return level.allows(LogLevel.INFO);
    }

    /**
     * @param l   level
     * @param tag tag
     * @param msg message
     * @param err error or null
     */
    public void log(LogLevel l, String tag, String msg, Throwable err) {
        if (!level.allows(l)) {
            return;
        }
        try {
            sink.log(l, tag, msg, err);
        } catch (RuntimeException ignored) {
            // a broken sink must not break the SDK
        }
    }

    /** @param tag tag @param msg message */
    public void debug(String tag, String msg) {
        log(LogLevel.DEBUG, tag, msg, null);
    }

    /** @param tag tag @param msg message */
    public void info(String tag, String msg) {
        log(LogLevel.INFO, tag, msg, null);
    }

    /** @param tag tag @param msg message @param err error or null */
    public void warn(String tag, String msg, Throwable err) {
        log(LogLevel.WARN, tag, msg, err);
    }

    /** @param tag tag @param msg message @param err error or null */
    public void error(String tag, String msg, Throwable err) {
        log(LogLevel.ERROR, tag, msg, err);
    }

    static void console(LogLevel level, String tag, String message, Throwable error) {
        PrintStream out = System.err;
        StringBuilder sb = new StringBuilder(96);
        sb.append(Instant.now()).append(' ').append(level).append(" [yugu/").append(tag).append("] ").append(message);
        if (error != null) {
            sb.append(": ").append(error);
        }
        out.println(sb);
    }
}
