// SPDX-License-Identifier: Apache-2.0
package com.shengzhiai.yugu.stcompat.internal;

import java.util.Random;

/**
 * Process-wide configuration of the compat layer, set through {@code com.stkouyu.YuguCompat}
 * (base URL, log level, retry policy, timeouts) and by tests (transport, audio input, sleeper).
 */
public final class CompatConfig {
    public static final String VERSION = "2.0.0";
    public static final String API_VERSION = "1.0.62";
    public static final String SDK_VERSION = "yugu-stkouyu-compat/" + VERSION + " (api " + API_VERSION + ")";
    public static final String USER_AGENT = "yugu-stkouyu-compat/" + VERSION;
    public static final String DEFAULT_BASE_URL = "https://open.shengzhiai.com";

    public static final int DEFAULT_CONNECT_TIMEOUT_MS = 10000;
    public static final int DEFAULT_READ_TIMEOUT_MS = 120000;
    public static final long DEFAULT_TOTAL_TIMEOUT_MS = 300000;
    /** Largest upload accepted by the platform REST endpoint; larger audio is rejected locally with errId 60009. */
    public static final long MAX_AUDIO_BYTES = 50L * 1024 * 1024;
    /** Longest audio evaluated: recordings stop here, longer WAV and PCM is rejected locally with errId 60009. */
    public static final int MAX_AUDIO_SECONDS = 300;

    private static volatile String baseUrlOverride;
    private static volatile RetryPolicy retryPolicy = RetryPolicy.DEFAULT;
    private static volatile int connectTimeoutMs = -1;
    private static volatile int readTimeoutMs = -1;
    private static volatile long totalTimeoutMs = DEFAULT_TOTAL_TIMEOUT_MS;
    private static volatile HttpTransport transport = new UrlConnectionTransport();
    private static volatile AudioInput.Factory audioInputFactory = AudioRecordInput.FACTORY;
    private static volatile Random random = new Random();
    private static volatile CompatClient.Sleeper sleeper = CompatClient.REAL_SLEEPER;

    private CompatConfig() {
    }

    public static String baseUrlOverride() {
        return baseUrlOverride;
    }

    public static void setBaseUrlOverride(String url) {
        if (url == null || url.trim().isEmpty()) {
            baseUrlOverride = null;
            return;
        }
        baseUrlOverride = ServerAddress.normalizeBase(url.trim());
    }

    public static RetryPolicy retryPolicy() {
        return retryPolicy;
    }

    public static void setRetryPolicy(RetryPolicy p) {
        retryPolicy = p == null ? RetryPolicy.DEFAULT : p;
    }

    /** Connect timeout override in ms, -1 when EngineSetting decides. */
    public static int connectTimeoutMs() {
        return connectTimeoutMs;
    }

    /** Read timeout override in ms, -1 when EngineSetting and RecordSetting decide. */
    public static int readTimeoutMs() {
        return readTimeoutMs;
    }

    public static long totalTimeoutMs() {
        return totalTimeoutMs;
    }

    public static void setTimeouts(int connectMs, int readMs, long totalMs) {
        connectTimeoutMs = connectMs > 0 ? connectMs : -1;
        readTimeoutMs = readMs > 0 ? readMs : -1;
        totalTimeoutMs = totalMs > 0 ? totalMs : DEFAULT_TOTAL_TIMEOUT_MS;
    }

    public static HttpTransport transport() {
        return transport;
    }

    public static void setTransport(HttpTransport t) {
        transport = t == null ? new UrlConnectionTransport() : t;
    }

    public static AudioInput.Factory audioInputFactory() {
        return audioInputFactory;
    }

    public static void setAudioInputFactory(AudioInput.Factory f) {
        audioInputFactory = f == null ? AudioRecordInput.FACTORY : f;
    }

    public static Random random() {
        return random;
    }

    public static void setRandom(Random r) {
        random = r == null ? new Random() : r;
    }

    public static CompatClient.Sleeper sleeper() {
        return sleeper;
    }

    public static void setSleeper(CompatClient.Sleeper s) {
        sleeper = s == null ? CompatClient.REAL_SLEEPER : s;
    }

    /** Restores every default. */
    public static void reset() {
        baseUrlOverride = null;
        retryPolicy = RetryPolicy.DEFAULT;
        connectTimeoutMs = -1;
        readTimeoutMs = -1;
        totalTimeoutMs = DEFAULT_TOTAL_TIMEOUT_MS;
        transport = new UrlConnectionTransport();
        audioInputFactory = AudioRecordInput.FACTORY;
        random = new Random();
        sleeper = CompatClient.REAL_SLEEPER;
        YLog.resetLevel();
    }
}
