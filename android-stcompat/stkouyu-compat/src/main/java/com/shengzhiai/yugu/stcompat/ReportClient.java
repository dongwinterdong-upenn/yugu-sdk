// SPDX-License-Identifier: Apache-2.0
package com.shengzhiai.yugu.stcompat;

import com.shengzhiai.yugu.stcompat.internal.CancelToken;
import com.shengzhiai.yugu.stcompat.internal.Codec;
import com.shengzhiai.yugu.stcompat.internal.CompatClient;
import com.shengzhiai.yugu.stcompat.internal.CompatConfig;
import com.shengzhiai.yugu.stcompat.internal.CompatError;
import com.shengzhiai.yugu.stcompat.internal.MainThread;
import com.shengzhiai.yugu.stcompat.internal.RawJson;
import com.shengzhiai.yugu.stcompat.internal.YLog;

import org.json.JSONException;

/**
 * Report lookup {@code GET /api/v1/report/{recordId}} for apps on the Shengtong drop-in (SDK 2.2.0):
 * the AI report the website shows (overall summary, comments and suggestions per dimension, skill
 * insights with confidence, numbered suggestions) for the {@code recordId} of an evaluation result.
 * Records of compat evaluations belong to the owner of the appKey, so the same appKey and secretKey
 * as the evaluations sign the lookup. Same HTTP stack, timeouts and retry policy as the evaluations.
 *
 * <pre>
 * ReportClient reports = ReportClient.create();          // after SkEgnManager.initEngine
 * reports.getReportAsync(recordId, new ReportOptions().setWaitForFinalMs(15000), new ReportClient.Callback() {
 *     public void onSuccess(ReportResult r) { show(r.getSummary(), r.getSuggestions(), r.isFinal()); }
 *     public void onFailure(YuguApiException e) { show(e.getCode() + " " + e.getMessage()); }
 * });
 * </pre>
 *
 * One INFO log line per call gives the recordId, the reportStatus, the lookups, the attempts and the
 * milliseconds; report text, secretKey and signatures are never logged.
 */
public final class ReportClient {
    static final String PATH = "/api/v1/report/";
    /** Pause between the starts of two lookups while the answer is pending. */
    static final long POLL_INTERVAL_MS = 1000;

    /** Result of {@link #getReportAsync}, on the main thread; exactly one of the two methods runs. */
    public interface Callback {
        void onSuccess(ReportResult result);

        void onFailure(YuguApiException error);
    }

    private final ApiSupport.Account account;

    private ReportClient(ApiSupport.Account account) {
        this.account = account;
    }

    /**
     * Client with the appKey, secretKey, server address and timeouts of the engine initialised through
     * {@code SkEgnManager.initEngine}.
     *
     * @throws IllegalStateException {@code 引擎未初始化} when no engine is initialised
     */
    public static ReportClient create() {
        return new ReportClient(ApiSupport.engineAccount());
    }

    /**
     * Client with explicit credentials on the default server ({@code YuguCompat.setBaseUrl} when set,
     * otherwise {@code https://open.shengzhiai.com}).
     *
     * @throws IllegalArgumentException for a blank appKey or secretKey
     */
    public static ReportClient create(String appKey, String secretKey) {
        return create(appKey, secretKey, null);
    }

    /**
     * Client with explicit credentials and platform base URL; a null or blank base URL is the default
     * server.
     *
     * @throws IllegalArgumentException for a blank appKey or secretKey, or a base URL without http,
     *         https, ws or wss scheme
     */
    public static ReportClient create(String appKey, String secretKey, String baseUrl) {
        return new ReportClient(ApiSupport.explicitAccount(appKey, secretKey, baseUrl));
    }

    /** Platform base URL of the next call, without trailing slash. */
    public String getBaseUrl() {
        return account.baseUrl();
    }

    /**
     * Looks up the report of an evaluation and waits for the answer, see
     * {@link ReportOptions#setWaitForFinalMs} for waiting for the AI final draft. A pending or
     * partial answer is a normal result. When a lookup after the first one fails, the latest answer
     * is returned and a WARN line names the error. Blocks on the network: call it on a worker thread,
     * or use {@link #getReportAsync}.
     *
     * @param recordId {@code recordId} of the evaluation result
     * @param options null for the defaults
     * @throws IllegalStateException on the main thread
     * @throws IllegalArgumentException for a blank recordId
     * @throws YuguApiException when the platform or the network fails, for example 40001
     *         {@code 评测记录不存在} for an unknown recordId
     */
    public ReportResult getReport(String recordId, ReportOptions options) {
        ApiSupport.requireWorkerThread("ReportClient.getReport", "getReportAsync");
        return lookUp(checked(recordId), options == null ? 0 : options.getWaitForFinalMs());
    }

    /**
     * {@link #getReport} on a worker thread; the result arrives at {@code callback} on the main
     * thread. Argument errors throw at once.
     *
     * @throws IllegalArgumentException for a blank recordId or a missing callback
     */
    public void getReportAsync(String recordId, ReportOptions options, final Callback callback) {
        if (callback == null) {
            throw new IllegalArgumentException("callback 不能为空");
        }
        final String id = checked(recordId);
        final long waitMs = options == null ? 0 : options.getWaitForFinalMs();
        ApiSupport.executor().execute(new Runnable() {
            @Override
            public void run() {
                ReportResult result = null;
                YuguApiException error = null;
                try {
                    result = lookUp(id, waitMs);
                } catch (YuguApiException e) {
                    error = e;
                } catch (RuntimeException e) {
                    error = ApiSupport.unreadable("report lookup failed: " + e, e);
                }
                final ReportResult r = result;
                final YuguApiException x = error;
                MainThread.post(new Runnable() {
                    @Override
                    public void run() {
                        if (x != null) {
                            callback.onFailure(x);
                        } else {
                            callback.onSuccess(r);
                        }
                    }
                });
            }
        });
    }

    private static String checked(String recordId) {
        if (Codec.isBlank(recordId)) {
            throw new IllegalArgumentException("recordId 不能为空");
        }
        return recordId.trim();
    }

    /**
     * One lookup, then while the answer is pending or partial (SDK 2.3.0) and {@code waitMs} allows,
     * another one every {@link #POLL_INTERVAL_MS}. The clock also counts the pauses of the configured
     * sleeper, so a sleeper that returns early (tests) cannot make the wait endless.
     */
    private ReportResult lookUp(String recordId, long waitMs) {
        String path = PATH + ApiSupport.pathSegment(recordId);
        CompatClient client = CompatClient.fromConfig();
        CancelToken token = new CancelToken();
        long t0 = ApiSupport.nowMs();
        long skipped = 0;
        int lookups = 0;
        int attempts = 0;
        ReportResult latest = null;
        YuguApiException lastError = null;
        while (true) {
            long started = ApiSupport.nowMs() + skipped;
            lookups++;
            YuguApiException failure = null;
            ReportResult answer = null;
            try {
                CompatClient.Result r = client.executeApi(ApiSupport.request(account, "GET", path), token);
                attempts += r.attempts;
                answer = ReportResult.parse(recordId, RawJson.members(r.body.trim()).get("data"), lookups);
            } catch (CompatError e) {
                attempts += e.getAttempts();
                failure = ApiSupport.exception(e);
            } catch (JSONException e) {
                failure = ApiSupport.unreadable("report data is not readable: " + e.getMessage(), e);
            }
            if (failure != null) {
                if (latest == null) {
                    YLog.i("report recordId=" + recordId + " status=error code=" + failure.getCode() + " http="
                            + failure.getHttpStatus() + " lookups=" + lookups + " attempts=" + attempts + " ms="
                            + (ApiSupport.nowMs() - t0));
                    throw failure;
                }
                lastError = failure;
                YLog.w("report recordId=" + recordId + " lookup " + lookups + " failed with code " + failure.getCode()
                        + " while waiting for the final draft; the latest answer is returned");
                latest.setLookups(lookups);
                break;
            }
            latest = answer;
            if (waitMs <= 0 || !latest.awaitingFinal()) {
                break;
            }
            long next = started + POLL_INTERVAL_MS;
            if (next - t0 > waitMs) {
                break;
            }
            long pause = next - (ApiSupport.nowMs() + skipped);
            if (pause > 0) {
                long before = ApiSupport.nowMs();
                if (!CompatConfig.sleeper().sleep(token, pause)) {
                    break;
                }
                long slept = ApiSupport.nowMs() - before;
                if (slept < pause) {
                    skipped += pause - slept;
                }
            }
        }
        YLog.i("report recordId=" + recordId + " status=" + (latest.getReportStatus() != null ? latest.getReportStatus() : "-")
                + " lookups=" + lookups + " attempts=" + attempts + " ms=" + (ApiSupport.nowMs() - t0)
                + (lastError != null ? " lastError=" + lastError.getCode() : ""));
        return latest;
    }
}
