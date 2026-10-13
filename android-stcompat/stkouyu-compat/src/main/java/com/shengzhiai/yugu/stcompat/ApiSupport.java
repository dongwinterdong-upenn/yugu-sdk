// SPDX-License-Identifier: Apache-2.0
package com.shengzhiai.yugu.stcompat;

import com.shengzhiai.yugu.stcompat.internal.CompatClient;
import com.shengzhiai.yugu.stcompat.internal.CompatConfig;
import com.shengzhiai.yugu.stcompat.internal.CompatEngine;
import com.shengzhiai.yugu.stcompat.internal.CompatErrIds;
import com.shengzhiai.yugu.stcompat.internal.CompatError;
import com.shengzhiai.yugu.stcompat.internal.ErrorMapper;
import com.shengzhiai.yugu.stcompat.internal.ErrorTable;
import com.shengzhiai.yugu.stcompat.internal.MainThread;
import com.shengzhiai.yugu.stcompat.internal.ServerAddress;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared plumbing of {@link TtsClient} and {@link ReportClient}: the account the calls are signed
 * with, the request of {@link CompatClient#executeApi} (the HTTP stack, signing and retry rules of the
 * compat evaluations), error mapping, the worker threads of the async calls and the main thread rule.
 */
final class ApiSupport {
    /** Text of a call without an initialised engine and without explicit credentials (errId 60007). */
    static final String NOT_INITIALIZED = CompatErrIds.message(CompatErrIds.ENGINE_NOT_INITIALIZED);

    private static final Object EXECUTOR_LOCK = new Object();
    private static ExecutorService executor;

    private ApiSupport() {
    }

    /** Credentials, base and timeouts one client signs and sends with. */
    static final class Account {
        final String appKey;
        final String secretKey;
        /** Base given to create, null to follow YuguCompat.setBaseUrl and the engine's address. */
        final String explicitBase;
        /** EngineSetting address of the engine the client was created from, null for none. */
        final String engineBase;
        final int connectTimeoutSec;
        final int serverTimeoutSec;

        Account(String appKey, String secretKey, String explicitBase, String engineBase, int connectTimeoutSec,
                int serverTimeoutSec) {
            this.appKey = appKey;
            this.secretKey = secretKey;
            this.explicitBase = explicitBase;
            this.engineBase = engineBase;
            this.connectTimeoutSec = connectTimeoutSec;
            this.serverTimeoutSec = serverTimeoutSec;
        }

        /** Platform base of the next call: the explicit one, else YuguCompat.setBaseUrl, the engine's, the default. */
        String baseUrl() {
            return explicitBase != null ? explicitBase : ServerAddress.effectiveBase(engineBase);
        }
    }

    /**
     * The account of the engine initialised through SkEgnManager.initEngine.
     *
     * @throws IllegalStateException {@code 引擎未初始化} when no engine is initialised
     */
    static Account engineAccount() {
        CompatEngine.Account a = CompatEngine.currentAccount();
        if (a == null) {
            throw new IllegalStateException(NOT_INITIALIZED);
        }
        return new Account(a.appKey, a.secretKey, null, a.baseUrl, a.connectTimeoutSec, a.serverTimeoutSec);
    }

    /**
     * Explicit credentials; a blank base follows YuguCompat.setBaseUrl, else the default server.
     *
     * @throws IllegalArgumentException for a blank appKey or secretKey, or a base without http, https, ws or wss scheme
     */
    static Account explicitAccount(String appKey, String secretKey, String baseUrl) {
        if (appKey == null || appKey.trim().isEmpty()) {
            throw new IllegalArgumentException("appKey 不能为空");
        }
        if (secretKey == null || secretKey.trim().isEmpty()) {
            throw new IllegalArgumentException("secretKey 不能为空");
        }
        String base = baseUrl == null || baseUrl.trim().isEmpty() ? null : ServerAddress.normalizeBase(baseUrl.trim());
        return new Account(appKey.trim(), secretKey.trim(), base, null, 0, 0);
    }

    /** A JSON call with the account's credentials and the timeouts and retry policy of YuguCompat. */
    static CompatClient.ApiRequest request(Account a, String method, String path) {
        CompatClient.ApiRequest r = new CompatClient.ApiRequest();
        r.method = method;
        r.baseUrl = a.baseUrl();
        r.path = path;
        r.appKey = a.appKey;
        r.secretKey = a.secretKey;
        int connect = CompatConfig.connectTimeoutMs();
        r.connectTimeoutMs = connect > 0 ? connect
                : a.connectTimeoutSec > 0 ? a.connectTimeoutSec * 1000 : CompatConfig.DEFAULT_CONNECT_TIMEOUT_MS;
        int read = CompatConfig.readTimeoutMs();
        r.readTimeoutMs = read > 0 ? read
                : a.serverTimeoutSec > 0 ? a.serverTimeoutSec * 1000 : CompatConfig.DEFAULT_READ_TIMEOUT_MS;
        r.totalTimeoutMs = CompatConfig.totalTimeoutMs();
        r.policy = CompatConfig.retryPolicy();
        return r;
    }

    /**
     * The public exception of a failed call. Code: the errId rule of the compat error JSON, 20009 for a
     * temporary failure after the retries, else the platform code (or the local one, 90005 for an
     * unreadable answer).
     */
    static YuguApiException exception(CompatError e) {
        int code = ErrorMapper.errId(e);
        String message;
        if (code == CompatErrIds.RETRYABLE) {
            StringBuilder sb = new StringBuilder("网络或服务端临时故障，重试已用尽: ").append(e.describe());
            if (e.getMessage() != null && !e.getMessage().isEmpty()) {
                sb.append(' ').append(e.getMessage());
            }
            if (e.getAttempts() > 0) {
                sb.append(" (attempts ").append(e.getAttempts()).append(')');
            }
            message = sb.toString();
        } else {
            message = e.getMessage() != null && !e.getMessage().isEmpty() ? e.getMessage() : CompatErrIds.message(code);
        }
        return new YuguApiException(code, e.httpStatus, message, e);
    }

    /** An answer that passed the envelope check but cannot be read (90005). */
    static YuguApiException unreadable(String what, Throwable cause) {
        return new YuguApiException(ErrorTable.PROTOCOL_ERROR, 200, what, cause);
    }

    /**
     * Blocking calls refuse the main thread.
     *
     * @throws IllegalStateException on the main thread
     */
    static void requireWorkerThread(String call, String async) {
        if (MainThread.isMainThread()) {
            throw new IllegalStateException(call + " 会阻塞等待网络，不能在主线程调用，请在工作线程调用或改用 " + async);
        }
    }

    /** Worker threads of the async calls, created on first use; daemon threads, idle ones end after 60 s. */
    static ExecutorService executor() {
        synchronized (EXECUTOR_LOCK) {
            if (executor == null) {
                executor = Executors.newCachedThreadPool(new ThreadFactory() {
                    private final AtomicInteger n = new AtomicInteger();

                    @Override
                    public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, "yugu-stcompat-api-" + n.incrementAndGet());
                        t.setDaemon(true);
                        return t;
                    }
                });
            }
            return executor;
        }
    }

    /** One path segment, percent-encoded (spaces as %20). */
    static String pathSegment(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 not available", e);
        }
    }

    /**
     * Playable address of a synthesised file (CONTRACT 4.1): absolute URLs unchanged, {@code /audio/...}
     * under {@code <base>/tts}, other paths under the base; null stays null.
     */
    static String resolveAudioUrl(String base, String url) {
        if (url == null || url.isEmpty()) {
            return url;
        }
        String lower = url.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            return url;
        }
        if (url.startsWith("/audio/")) {
            return base + "/tts" + url;
        }
        return base + (url.startsWith("/") ? "" : "/") + url;
    }

    /** Milliseconds on the monotonic clock. */
    static long nowMs() {
        return System.nanoTime() / 1000000L;
    }
}
