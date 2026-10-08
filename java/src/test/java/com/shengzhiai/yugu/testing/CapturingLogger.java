package com.shengzhiai.yugu.testing;

import com.shengzhiai.yugu.LogLevel;
import com.shengzhiai.yugu.YuguLogger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Collects log lines. */
public final class CapturingLogger implements YuguLogger {
    public final List<String> lines = new CopyOnWriteArrayList<>();

    @Override
    public void log(LogLevel level, String tag, String message, Throwable error) {
        lines.add(level + " " + tag + " " + message + (error == null ? "" : " :: " + error));
    }

    public List<String> matching(String part) {
        List<String> out = new ArrayList<>();
        for (String l : lines) {
            if (l.contains(part)) {
                out.add(l);
            }
        }
        return out;
    }

    public String all() {
        return String.join("\n", lines);
    }
}
