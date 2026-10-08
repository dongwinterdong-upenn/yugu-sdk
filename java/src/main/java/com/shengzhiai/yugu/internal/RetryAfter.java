package com.shengzhiai.yugu.internal;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/** Parses {@code Retry-After}: delta seconds (integer or decimal) or an HTTP date. */
public final class RetryAfter {
    private RetryAfter() {
    }

    /**
     * @param value header value, may be null
     * @param nowMs current time in epoch milliseconds, for HTTP dates
     * @return delay in milliseconds, -1 when absent or unparseable
     */
    public static long parseMs(String value, long nowMs) {
        if (value == null) {
            return -1;
        }
        String v = value.trim();
        if (v.isEmpty()) {
            return -1;
        }
        try {
            double s = Double.parseDouble(v);
            if (s < 0 || Double.isNaN(s) || Double.isInfinite(s)) {
                return -1;
            }
            return Math.round(s * 1000);
        } catch (NumberFormatException ignored) {
            // not a number, try the HTTP date form
        }
        try {
            ZonedDateTime t = ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME);
            return Math.max(0, t.toInstant().toEpochMilli() - nowMs);
        } catch (RuntimeException e) {
            return -1;
        }
    }
}
