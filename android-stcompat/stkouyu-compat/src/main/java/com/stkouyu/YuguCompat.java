// SPDX-License-Identifier: Apache-2.0
package com.stkouyu;

import com.shengzhiai.yugu.stcompat.internal.CompatConfig;
import com.shengzhiai.yugu.stcompat.internal.RetryPolicy;
import com.shengzhiai.yugu.stcompat.internal.YLog;

/**
 * Configuration hooks of the Yugu compat layer: the only public class of {@code com.stkouyu}
 * that the Shengtong jar does not have. Apps written for the Shengtong SDK do not need it.
 */
public final class YuguCompat {
    /** Version of the compat layer. */
    public static final String VERSION = "2.0.0";

    public static final int LOG_OFF = 0;
    public static final int LOG_ERROR = 1;
    public static final int LOG_WARN = 2;
    public static final int LOG_INFO = 3;
    public static final int LOG_DEBUG = 4;

    private YuguCompat() {
    }

    /**
     * Platform base URL, for example a sandbox or a private deployment. Wins over
     * {@code EngineSetting.setServerAddress}. null restores the default {@code https://open.shengzhiai.com}.
     *
     * @throws IllegalArgumentException when the URL has no http, https, ws or wss scheme
     */
    public static void setBaseUrl(String baseUrl) {
        CompatConfig.setBaseUrlOverride(baseUrl);
    }

    /** Base URL override, or null when the EngineSetting address (or the default) is used. */
    public static String getBaseUrl() {
        return CompatConfig.baseUrlOverride();
    }

    /** SDK log level, {@link #LOG_OFF} to {@link #LOG_DEBUG}; wins over the EngineSetting log options. Default WARN. */
    public static void setLogLevel(int level) {
        YLog.setLevelPinned(level);
    }

    public static int getLogLevel() {
        return YLog.getLevel();
    }

    /**
     * Retry policy of every HTTP call (shared by all Yugu SDKs). Defaults: 2 retries, 200 ms initial
     * delay, multiplier 2.0, 4000 ms maximum delay, jitter 0.3. Retries reuse the tokenId as
     * Idempotency-Key, so a retried evaluation is billed once.
     */
    public static void setRetryPolicy(int maxRetries, long initialDelayMs, double multiplier, long maxDelayMs,
                                      double jitter) {
        CompatConfig.setRetryPolicy(new RetryPolicy(maxRetries, initialDelayMs, multiplier, maxDelayMs, jitter,
                true, 30000));
    }

    /**
     * Timeouts in ms: connect (default 10000 or EngineSetting.setConnectTimeout), read per attempt
     * (default 120000 or EngineSetting.setServerTimeout) and total per evaluation including retries
     * (default 300000). Values of 0 or less restore the default.
     */
    public static void setTimeouts(int connectTimeoutMs, int readTimeoutMs, long totalTimeoutMs) {
        CompatConfig.setTimeouts(connectTimeoutMs, readTimeoutMs, totalTimeoutMs);
    }

    /** Version string, {@code yugu-stkouyu-compat/2.0.0 (api 1.0.62)}. */
    public static String getVersion() {
        return CompatConfig.SDK_VERSION;
    }

    /** Restores base URL, log level, retry policy and timeouts to their defaults. */
    public static void reset() {
        CompatConfig.setBaseUrlOverride(null);
        CompatConfig.setRetryPolicy(null);
        CompatConfig.setTimeouts(0, 0, 0);
        YLog.resetLevel();
    }
}
