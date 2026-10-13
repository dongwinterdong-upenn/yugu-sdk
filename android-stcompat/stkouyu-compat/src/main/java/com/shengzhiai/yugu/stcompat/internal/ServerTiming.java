// SPDX-License-Identifier: Apache-2.0
package com.shengzhiai.yugu.stcompat.internal;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Server side timing of one compat evaluation (platform change of 2026-10-13, SDK 2.1.1):
 *
 * <ul>
 * <li>every successful compat REST answer carries
 * {@code Server-Timing: total;dur=<ms>, engine;dur=<ms>, platform;dur=<ms>}</li>
 * <li>with the form field {@code timing=1} (REST) or {@code "timing": 1} in the parameter frame
 * (WebSocket) the result gains the top-level object
 * {@code "timing":{"receivedAt","totalMs","engineMs","platformMs","engineStages":{...},"audioBytes","wsFirstAudioAt"}};
 * engineStages and wsFirstAudioAt may be absent, unknown keys are ignored</li>
 * </ul>
 *
 * The SDK asks for the object only while its log level is INFO or DEBUG ({@link #wanted()}) and
 * always removes it ({@link #split}) before the result reaches the app, so apps see the result
 * JSON of 2.1.0.
 */
public final class ServerTiming {
    /** Form field, parameter frame key and result member. */
    public static final String KEY = "timing";

    /** Platform processing from the received request (REST) or end frame (WebSocket) to the result, -1 when unknown. */
    public long totalMs = -1;
    /** The call to the evaluation engine, -1 when unknown. */
    public long engineMs = -1;
    /** total minus engine, -1 when unknown. */
    public long platformMs = -1;
    /** Platform epoch ms when processing started, -1 when unknown. */
    public long receivedAt = -1;
    /** Audio bytes the platform received, -1 when unknown. */
    public long audioBytes = -1;
    /** Platform epoch ms of the first audio frame (WebSocket only), -1 when unknown. */
    public long wsFirstAudioAt = -1;
    /** engineStages as sent: stage name to the raw number text, in platform order. */
    public final LinkedHashMap<String, String> engineStages = new LinkedHashMap<String, String>();

    /** A platform body without its timing member, and that member parsed (null when absent or unreadable). */
    public static final class Split {
        public final String body;
        public final ServerTiming timing;

        Split(String body, ServerTiming timing) {
            this.body = body;
            this.timing = timing;
        }
    }

    /** True while the SDK log level is INFO or DEBUG: only then requests carry {@code timing=1}. */
    public static boolean wanted() {
        return YLog.isLoggable(YLog.INFO);
    }

    /** {@code fields}, or a copy with {@code timing=1} added when {@code timing} is true. */
    public static LinkedHashMap<String, String> requestFields(LinkedHashMap<String, String> fields, boolean timing) {
        if (!timing) {
            return fields;
        }
        LinkedHashMap<String, String> f = new LinkedHashMap<String, String>(fields);
        f.put(KEY, "1");
        return f;
    }

    /**
     * Removes the top-level {@code timing} member of a platform body. Everything else stays byte for
     * byte; a body that is not a JSON object is returned unchanged for the caller to reject.
     */
    public static Split split(String platformBody) {
        Map<String, String> m;
        try {
            m = RawJson.members(platformBody);
        } catch (IllegalArgumentException e) {
            return new Split(platformBody, null);
        }
        String raw = m.get(KEY);
        if (raw == null) {
            return new Split(platformBody, null);
        }
        return new Split(RawJson.removeMember(platformBody, KEY), fromObject(raw));
    }

    /** Parses the raw text of a timing object; null when it is not a JSON object. */
    public static ServerTiming fromObject(String raw) {
        String text = raw == null ? "" : raw.trim();
        Map<String, String> m;
        try {
            m = RawJson.members(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
        ServerTiming t = new ServerTiming();
        t.totalMs = number(m.get("totalMs"));
        t.engineMs = number(m.get("engineMs"));
        t.platformMs = number(m.get("platformMs"));
        t.receivedAt = number(m.get("receivedAt"));
        t.audioBytes = number(m.get("audioBytes"));
        t.wsFirstAudioAt = number(m.get("wsFirstAudioAt"));
        String stages = m.get("engineStages");
        if (stages != null && stages.trim().startsWith("{")) {
            try {
                for (Map.Entry<String, String> e : RawJson.members(stages.trim()).entrySet()) {
                    String v = e.getValue().trim();
                    if (!v.startsWith("{") && !v.startsWith("[")) {
                        t.engineStages.put(e.getKey(), v);
                    }
                }
            } catch (IllegalArgumentException ignored) {
                // stages are informational
            }
        }
        if (t.platformMs < 0 && t.totalMs >= 0 && t.engineMs >= 0) {
            t.platformMs = Math.max(0, t.totalMs - t.engineMs);
        }
        return t;
    }

    /**
     * Parses a Server-Timing header value ({@code name;dur=1.5;desc="x", ...}); null when the value
     * is absent or names none of total, engine and platform.
     */
    public static ServerTiming fromHeader(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        ServerTiming t = new ServerTiming();
        boolean any = false;
        for (String metric : split(value, ',')) {
            java.util.List<String> parts = split(metric, ';');
            if (parts.isEmpty()) {
                continue;
            }
            String name = parts.get(0).trim().toLowerCase(Locale.ROOT);
            long dur = -1;
            for (int i = 1; i < parts.size(); i++) {
                String p = parts.get(i).trim();
                int eq = p.indexOf('=');
                if (eq > 0 && "dur".equalsIgnoreCase(p.substring(0, eq).trim())) {
                    dur = number(unquote(p.substring(eq + 1).trim()));
                }
            }
            if ("total".equals(name)) {
                t.totalMs = dur;
                any = true;
            } else if ("engine".equals(name)) {
                t.engineMs = dur;
                any = true;
            } else if ("platform".equals(name)) {
                t.platformMs = dur;
                any = true;
            }
        }
        return any ? t : null;
    }

    /** {@code server.total=..ms server.engine=..ms server.platform=..ms}, unknown values left out. */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        append(sb, "server.total", totalMs);
        append(sb, "server.engine", engineMs);
        append(sb, "server.platform", platformMs);
        return sb.toString().trim();
    }

    /** {@code name=value} pairs of engineStages in platform order, "" when none. */
    public String describeStages() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : engineStages.entrySet()) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    private static void append(StringBuilder sb, String name, long ms) {
        if (ms >= 0) {
            sb.append(' ').append(name).append('=').append(ms).append("ms");
        }
    }

    /** Rounded value of a raw JSON number or numeric string, -1 when absent or not a number. */
    static long number(String raw) {
        if (raw == null) {
            return -1;
        }
        String t = RawJson.isString(raw) ? RawJson.unquote(raw) : raw.trim();
        try {
            double d = Double.parseDouble(t);
            return Double.isNaN(d) || Double.isInfinite(d) ? -1 : Math.round(d);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String unquote(String v) {
        return v.length() >= 2 && v.charAt(0) == '"' && v.charAt(v.length() - 1) == '"' ? v.substring(1, v.length() - 1) : v;
    }

    /** Pieces of {@code s} split at {@code sep} outside double quotes. */
    private static java.util.List<String> split(String s, char sep) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quoted && c == '\\' && i + 1 < s.length()) {
                cur.append(c).append(s.charAt(++i));
                continue;
            }
            if (c == '"') {
                quoted = !quoted;
            }
            if (c == sep && !quoted) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out;
    }
}
