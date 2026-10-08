// SPDX-License-Identifier: Apache-2.0
package com.shengzhiai.yugu.stcompat.internal;

/**
 * Retry policy shared by every Yugu SDK (DESIGN 2.3). Defaults: maxRetries 2 (3 attempts),
 * initialDelayMs 200, multiplier 2.0, maxDelayMs 4000, jitter 0.3, respectRetryAfter true,
 * maxRetryAfterMs 30000.
 *
 * <pre>
 * delay(n) = min(maxDelayMs, initialDelayMs * multiplier^(n-1)) * (1 + U(-jitter, +jitter))   n = 1..maxRetries
 * if respectRetryAfter and response has Retry-After: delay = max(delay, min(retryAfterMs, maxRetryAfterMs))
 * </pre>
 */
public final class RetryPolicy {
    public static final RetryPolicy DEFAULT = new RetryPolicy(2, 200, 2.0, 4000, 0.3, true, 30000);

    public final int maxRetries;
    public final long initialDelayMs;
    public final double multiplier;
    public final long maxDelayMs;
    public final double jitter;
    public final boolean respectRetryAfter;
    public final long maxRetryAfterMs;

    public RetryPolicy(int maxRetries, long initialDelayMs, double multiplier, long maxDelayMs, double jitter,
                       boolean respectRetryAfter, long maxRetryAfterMs) {
        if (maxRetries < 0 || initialDelayMs < 0 || multiplier < 1.0 || maxDelayMs < 0 || jitter < 0 || jitter >= 1.0
                || maxRetryAfterMs < 0) {
            throw new IllegalArgumentException("invalid retry policy");
        }
        this.maxRetries = maxRetries;
        this.initialDelayMs = initialDelayMs;
        this.multiplier = multiplier;
        this.maxDelayMs = maxDelayMs;
        this.jitter = jitter;
        this.respectRetryAfter = respectRetryAfter;
        this.maxRetryAfterMs = maxRetryAfterMs;
    }

    /** Delay without jitter before retry {@code n} (1-based). */
    public double baseDelayMs(int n) {
        return Math.min((double) maxDelayMs, initialDelayMs * Math.pow(multiplier, n - 1));
    }

    /**
     * Delay before retry {@code n} (1-based).
     *
     * @param u uniform random number in [0, 1), mapped to U(-jitter, +jitter)
     * @param retryAfterMs Retry-After of the failed response in ms, negative when absent
     */
    public long delayMs(int n, double u, long retryAfterMs) {
        double factor = 1.0 + (u * 2.0 - 1.0) * jitter;
        long delay = Math.max(0L, Math.round(baseDelayMs(n) * factor));
        if (respectRetryAfter && retryAfterMs >= 0) {
            delay = Math.max(delay, Math.min(retryAfterMs, maxRetryAfterMs));
        }
        return delay;
    }
}
