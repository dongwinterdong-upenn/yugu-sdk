// SPDX-License-Identifier: Apache-2.0
package com.shengzhiai.yugu.stcompat.internal;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Appends SDK log lines to a file (LogCat util and EngineSetting.setEnableSaveLogCatToFile). */
public final class LogFileSink implements YLog.Sink {
    /** Files are rotated (truncated) beyond this size. */
    static final long MAX_BYTES = 2L * 1024 * 1024;
    private static final String[] LEVELS = {"OFF", "E", "W", "I", "D"};

    private final File file;
    private OutputStream out;

    public LogFileSink(File file) throws IOException {
        this.file = file;
        File dir = file.getAbsoluteFile().getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("cannot create " + dir);
        }
        this.out = new FileOutputStream(file, file.length() < MAX_BYTES);
    }

    public File file() {
        return file;
    }

    /** Installs a sink writing to {@code file}; returns false when the file cannot be opened. */
    public static synchronized boolean install(File file) {
        uninstall();
        try {
            YLog.setSink(new LogFileSink(file));
            return true;
        } catch (IOException e) {
            YLog.w("cannot open log file " + file + ": " + e.getMessage());
            return false;
        }
    }

    public static synchronized void uninstall() {
        YLog.Sink s = YLog.getSink();
        YLog.setSink(null);
        if (s instanceof LogFileSink) {
            ((LogFileSink) s).close();
        }
    }

    @Override
    public synchronized void log(int level, String tag, String message, Throwable error) {
        if (out == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date()))
                .append(' ').append(level >= 0 && level < LEVELS.length ? LEVELS[level] : "?")
                .append('/').append(tag).append(": ").append(message).append('\n');
        if (error != null) {
            StringWriter sw = new StringWriter();
            error.printStackTrace(new PrintWriter(sw));
            sb.append(sw).append('\n');
        }
        try {
            out.write(sb.toString().getBytes(Codec.UTF_8));
            out.flush();
        } catch (IOException e) {
            close();
        }
    }

    public synchronized void close() {
        if (out != null) {
            try {
                out.close();
            } catch (IOException ignored) {
                // closing a log file
            }
            out = null;
        }
    }
}
