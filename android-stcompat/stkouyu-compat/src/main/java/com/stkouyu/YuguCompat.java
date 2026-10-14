// SPDX-License-Identifier: Apache-2.0
package com.stkouyu;

import com.shengzhiai.yugu.stcompat.internal.CompatConfig;
import com.shengzhiai.yugu.stcompat.internal.CompatEngine;
import com.shengzhiai.yugu.stcompat.internal.RetryPolicy;
import com.shengzhiai.yugu.stcompat.internal.YLog;

/**
 * Configuration hooks of the Yugu compat layer: the only public class of {@code com.stkouyu}
 * that the Shengtong jar does not have. Apps written for the Shengtong SDK do not need it.
 * Speech synthesis and report lookup are in {@code com.shengzhiai.yugu.stcompat.TtsClient} and
 * {@code com.shengzhiai.yugu.stcompat.ReportClient}.
 */
public final class YuguCompat {
    /** Version of the compat layer. */
    public static final String VERSION = "2.3.0";

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
     * TtsClient and ReportClient created without their own base URL use it too.
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

    /**
     * SDK log level, {@link #LOG_OFF} to {@link #LOG_DEBUG}; wins over the EngineSetting log options.
     * Default WARN. At INFO every evaluation logs one timing line, at DEBUG every stage.
     */
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

    /**
     * Streaming upload (default true): recordings and {@code setIsStream(true)} sessions send their
     * audio over the platform's compat WebSocket while recording, whatever realtime_feedback is, and
     * the result arrives shortly after stop. false uploads the whole recording after stop as in
     * 2.1.0; realtime_feedback 1 still streams then. existsAudioTrans always uploads the file.
     * Applies to sessions started afterwards.
     */
    public static void setStreamUpload(boolean enabled) {
        CompatConfig.setStreamUpload(enabled);
    }

    public static boolean isStreamUpload() {
        return CompatConfig.streamUpload();
    }

    /**
     * What an unsupported coreType does (SDK 2.2.0), for example {@code choice.rec}, {@code open.eval},
     * {@code asr.rec}, {@code asr.eval}, {@code align.eval} or a French, Japanese or Korean kernel.
     *
     * <p>false (default): as before, the error JSON with errId 60003 and {@code error}
     * {@code 不支持的评测内核：<coreType>} arrives through the callback ({@code onScore},
     * {@code onRecordEnd(json)} or {@code skegn_callback}). true: every {@code startRecord} and
     * {@code existsAudioTrans} overload and {@code SkEgn.skegn_start} throw
     * {@link IllegalArgumentException} with that same text instead, before any other check, so also
     * when the engine is not initialised or busy; no callback follows. Either way one WARN line names
     * the coreType and errId 60003, and there is no network request and no microphone. Every other
     * error keeps its callback.
     */
    public static void setThrowOnUnsupportedCoreType(boolean enabled) {
        CompatConfig.setThrowOnUnsupportedCoreType(enabled);
    }

    /** Whether an unsupported coreType throws, see {@link #setThrowOnUnsupportedCoreType(boolean)}. */
    public static boolean isThrowOnUnsupportedCoreType() {
        return CompatConfig.throwOnUnsupportedCoreType();
    }

    /**
     * Pipelined evaluation (SDK 2.3.0), for reading a group of items in a row: the next item can be
     * recorded while the results of earlier ones are still on their way.
     *
     * <p>0 (default): one evaluation at a time as in the Shengtong SDK; until the previous result was
     * delivered, {@code startRecord} answers {@code onStartRecordFail("engine is busy")} or errId 60008.
     * n &gt; 0: a new {@code startRecord} or {@code existsAudioTrans} is refused the same way only while
     * a recording runs or is paused, or while more than n sessions wait for their result; so a new
     * recording may run while up to n earlier sessions are still evaluated. Every session keeps its
     * own tokenId, listener, record file and network connection (a recordName still used by a waiting
     * session becomes {@code <name>-<tokenId>.wav}); each result goes to the listener
     * passed to its own start, on the main thread, in the order the evaluations finish. The result
     * JSON carries {@code tokenId} and {@code recordId} to tell the evaluations apart.
     * {@code getEngineStatus()} follows the recording: after a stop it is IDLE again as soon as the
     * stop is handled, before {@code onRecordEnd}, while that evaluation goes on.
     * {@code cancel()} and {@code recycle()} end the recording and every waiting session, none of
     * them calls back afterwards.
     *
     * <p>Values are clamped to 0..16, negative means 0. Read when a recording or file evaluation
     * starts. A change logs one INFO line.
     */
    public static void setMaxPendingEvaluations(int n) {
        CompatConfig.setMaxPendingEvaluations(n);
    }

    /** The pipelined evaluation switch, see {@link #setMaxPendingEvaluations(int)}; 0 when off. */
    public static int getMaxPendingEvaluations() {
        return CompatConfig.maxPendingEvaluations();
    }

    /**
     * Sessions whose recording ended (and file evaluations) that have not delivered their final
     * result yet, for example to show progress while a group is evaluated (SDK 2.3.0). A session
     * stops counting right before its final callback runs, and cancel or recycle set it to 0.
     */
    public static int pendingEvaluations() {
        return CompatEngine.pendingCount();
    }

    /** Version string, {@code yugu-stkouyu-compat/2.3.0 (api 1.0.62)}. */
    public static String getVersion() {
        return CompatConfig.SDK_VERSION;
    }

    /**
     * Restores base URL, log level, retry policy, timeouts, streaming upload, the exception for an
     * unsupported coreType and pipelined evaluation (off) to their defaults.
     */
    public static void reset() {
        CompatConfig.setBaseUrlOverride(null);
        CompatConfig.setRetryPolicy(null);
        CompatConfig.setTimeouts(0, 0, 0);
        CompatConfig.setStreamUpload(true);
        CompatConfig.setThrowOnUnsupportedCoreType(false);
        CompatConfig.setMaxPendingEvaluations(0);
        YLog.resetLevel();
    }
}
