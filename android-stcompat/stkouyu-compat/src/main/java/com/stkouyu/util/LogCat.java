// SPDX-License-Identifier: Apache-2.0
package com.stkouyu.util;

import android.content.Context;

import com.shengzhiai.yugu.stcompat.internal.LogFileSink;
import com.shengzhiai.yugu.stcompat.internal.YLog;

import java.io.File;

/**
 * SDK log capture of the Shengtong SDK. The compat layer writes its own log lines to a file on the
 * device; uploading logs is not available (pushLog and pushLogManually log one WARN line).
 */
public class LogCat {
    static final String DEFAULT_FILE = "stkouyu_sdk.log";

    /** Stops writing the log file. */
    public static void destorytLogCat() {
        LogFileSink.uninstall();
    }

    /** Writes SDK logs to {@code <dir>/stkouyu_sdk.log}; a null dir uses {@code <externalFilesDir>/log}. */
    public static void initLogCat(Context context, String dir) {
        initLogCat(context, dir, DEFAULT_FILE);
    }

    public static void initLogCat(Context context, String dir, String fileName) {
        File d = dir != null && !dir.trim().isEmpty() ? new File(dir.trim())
                : new File(AiUtil.externalFilesDir(context), "log");
        String name = fileName != null && !fileName.trim().isEmpty() ? fileName.trim() : DEFAULT_FILE;
        LogFileSink.install(new File(d, name));
    }

    public static void pushLogManually(Context context) {
        YLog.warnOnce("pushLog", "log upload is not available in the compat layer; logs stay on the device");
    }

    public static void pushLog(Context context) {
        YLog.warnOnce("pushLog", "log upload is not available in the compat layer; logs stay on the device");
    }
}
