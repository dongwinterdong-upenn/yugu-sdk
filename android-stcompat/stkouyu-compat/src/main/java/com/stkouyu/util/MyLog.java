// SPDX-License-Identifier: Apache-2.0
package com.stkouyu.util;

import android.content.Context;
import android.util.Log;

import java.io.File;

/** Logging helper of the Shengtong SDK on {@link Log}; {@link #init(Context, boolean)} switches it on or off. */
public class MyLog {
    /** Log directory, set by {@link #init(Context, boolean)} to {@code <externalFilesDir>/log}. */
    public static String MYLOG_PATH_SDCARD_DIR;

    private static volatile boolean enabled = true;

    public Context context;

    public static void w(String tag, Object msg) {
        w(tag, String.valueOf(msg));
    }

    public static void e(String tag, Object msg) {
        e(tag, String.valueOf(msg));
    }

    public static void d(String tag, Object msg) {
        d(tag, String.valueOf(msg));
    }

    public static void i(String tag, Object msg) {
        i(tag, String.valueOf(msg));
    }

    public static void v(String tag, Object msg) {
        v(tag, String.valueOf(msg));
    }

    public static void w(String tag, String msg) {
        if (enabled) {
            Log.w(tag, String.valueOf(msg));
        }
    }

    public static void e(String tag, String msg) {
        if (enabled) {
            Log.e(tag, String.valueOf(msg));
        }
    }

    public static void e(String tag, String msg, Throwable t) {
        if (enabled) {
            Log.e(tag, String.valueOf(msg), t);
        }
    }

    public static void d(String tag, String msg) {
        if (enabled) {
            Log.d(tag, String.valueOf(msg));
        }
    }

    public static void i(String tag, String msg) {
        if (enabled) {
            Log.i(tag, String.valueOf(msg));
        }
    }

    public static void v(String tag, String msg) {
        if (enabled) {
            Log.v(tag, String.valueOf(msg));
        }
    }

    public static void init(Context context, boolean enable) {
        enabled = enable;
        if (context != null) {
            MYLOG_PATH_SDCARD_DIR = new File(AiUtil.externalFilesDir(context), "log").getAbsolutePath();
        }
    }
}
