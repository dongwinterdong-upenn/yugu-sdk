package com.shengzhiai.yugu.internal;

import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Redaction for logs: secrets, signatures and tokens never reach a log line. */
public final class Redact {
    private Redact() {
    }

    /**
     * @param appKey app key
     * @return the first 4 characters followed by {@code ***}; {@code ***} for short or null keys
     */
    public static String appKey(String appKey) {
        if (appKey == null || appKey.length() <= 4) {
            return "***";
        }
        return appKey.substring(0, 4) + "***";
    }

    /**
     * Redacts a header value by name.
     *
     * @param name  header name
     * @param value header value
     * @return safe value
     */
    public static String header(String name, String value) {
        String n = name.toLowerCase(Locale.ROOT);
        switch (n) {
            case "x-signature":
                return "***";
            case "authorization":
                return value != null && value.regionMatches(true, 0, "Bearer ", 0, 7) ? "Bearer ***" : "***";
            case "x-app-key":
                return appKey(value);
            default:
                return value;
        }
    }

    /**
     * @param headers headers
     * @return redacted copy, sorted by name
     */
    public static Map<String, String> headers(Map<String, String> headers) {
        Map<String, String> out = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Map.Entry<String, String> e : headers.entrySet()) {
            out.put(e.getKey(), header(e.getKey(), e.getValue()));
        }
        return out;
    }

    /**
     * Redacts the query of a URL: {@code signature} and {@code token} become {@code ***}, {@code appKey}
     * keeps its first 4 characters.
     *
     * @param url URL
     * @return safe URL
     */
    public static String url(String url) {
        int q = url.indexOf('?');
        if (q < 0) {
            return url;
        }
        StringBuilder sb = new StringBuilder(url.substring(0, q + 1));
        String[] parts = url.substring(q + 1).split("&");
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            int eq = p.indexOf('=');
            String k = eq < 0 ? p : p.substring(0, eq);
            String v = eq < 0 ? "" : p.substring(eq + 1);
            if (i > 0) {
                sb.append('&');
            }
            sb.append(k).append('=');
            if ("signature".equals(k) || "token".equals(k)) {
                sb.append("***");
            } else if ("appKey".equals(k)) {
                sb.append(appKey(PercentEncoding.decode(v)));
            } else {
                sb.append(v);
            }
        }
        return sb.toString();
    }
}
