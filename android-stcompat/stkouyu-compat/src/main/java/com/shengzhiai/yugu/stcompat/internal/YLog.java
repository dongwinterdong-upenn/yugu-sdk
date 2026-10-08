// SPDX-License-Identifier: Apache-2.0
package com.shengzhiai.yugu.stcompat.internal;

import android.util.Log;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * SDK logger. Levels OFF, ERROR, WARN (default), INFO, DEBUG. Never receives secretKey, signature
 * or audio bytes; appKey is masked with {@link #maskKey(String)}.
 */
public final class YLog {
    public static final int OFF = 0;
    public static final int ERROR = 1;
    public static final int WARN = 2;
    public static final int INFO = 3;
    public static final int DEBUG = 4;

    public static final String TAG = "YuguStCompat";

    /** Optional extra sink (LogCat util writes SDK log lines to a file). */
    public interface Sink {
        void log(int level, String tag, String message, Throwable error);
    }

    private static volatile int level = WARN;
    private static volatile boolean levelPinned;
    private static volatile Sink sink;
    private static final Set<String> ONCE = Collections.synchronizedSet(new HashSet<String>());

    private YLog() {
    }

    public static int getLevel() {
        return level;
    }

    /** Level set by {@code YuguCompat.setLogLevel}; wins over EngineSetting log options. */
    public static void setLevelPinned(int newLevel) {
        level = clamp(newLevel);
        levelPinned = true;
    }

    /** Level derived from EngineSetting; ignored while a pinned level is set. */
    public static void setLevelFromSettings(int newLevel) {
        if (!levelPinned) {
            level = clamp(newLevel);
        }
    }

    public static void resetLevel() {
        level = WARN;
        levelPinned = false;
        ONCE.clear();
    }

    public static void setSink(Sink s) {
        sink = s;
    }

    public static Sink getSink() {
        return sink;
    }

    public static boolean isLoggable(int l) {
        return l != OFF && l <= level;
    }

    public static void e(String msg) {
        log(ERROR, msg, null);
    }

    public static void e(String msg, Throwable t) {
        log(ERROR, msg, t);
    }

    public static void w(String msg) {
        log(WARN, msg, null);
    }

    public static void w(String msg, Throwable t) {
        log(WARN, msg, t);
    }

    public static void i(String msg) {
        log(INFO, msg, null);
    }

    public static void d(String msg) {
        log(DEBUG, msg, null);
    }

    /** Logs a WARN line once per process for the given key. */
    public static void warnOnce(String key, String msg) {
        if (ONCE.add(key)) {
            log(WARN, msg, null);
        }
    }

    static void log(int l, String msg, Throwable t) {
        if (!isLoggable(l)) {
            return;
        }
        try {
            switch (l) {
                case ERROR:
                    Log.e(TAG, msg, t);
                    break;
                case WARN:
                    Log.w(TAG, msg, t);
                    break;
                case INFO:
                    Log.i(TAG, msg, t);
                    break;
                default:
                    Log.d(TAG, msg, t);
                    break;
            }
        } catch (RuntimeException ignored) {
            // android.util.Log is a stub on a plain JVM; logging must never break the SDK.
        }
        Sink s = sink;
        if (s != null) {
            try {
                s.log(l, TAG, msg, t);
            } catch (RuntimeException ignored) {
                // sink failures are not the caller's problem
            }
        }
    }

    /** First 4 characters of an appKey followed by {@code ***}. */
    public static String maskKey(String appKey) {
        if (appKey == null) {
            return "null";
        }
        return (appKey.length() <= 4 ? appKey : appKey.substring(0, 4)) + "***";
    }

    private static int clamp(int l) {
        return l < OFF ? OFF : (l > DEBUG ? DEBUG : l);
    }
}
