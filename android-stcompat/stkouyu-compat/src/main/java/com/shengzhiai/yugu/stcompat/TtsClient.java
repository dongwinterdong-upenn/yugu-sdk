// SPDX-License-Identifier: Apache-2.0
package com.shengzhiai.yugu.stcompat;

import com.shengzhiai.yugu.stcompat.internal.CancelToken;
import com.shengzhiai.yugu.stcompat.internal.Codec;
import com.shengzhiai.yugu.stcompat.internal.CompatClient;
import com.shengzhiai.yugu.stcompat.internal.CompatError;
import com.shengzhiai.yugu.stcompat.internal.MainThread;
import com.shengzhiai.yugu.stcompat.internal.RawJson;
import com.shengzhiai.yugu.stcompat.internal.YLog;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Speech synthesis {@code POST /api/v1/tts/generate} for apps on the Shengtong drop-in (SDK 2.2.0),
 * signed with the same appKey and secretKey as the evaluations and sent with the same HTTP stack,
 * timeouts and retry policy ({@code YuguCompat.setTimeouts}, {@code YuguCompat.setRetryPolicy}).
 *
 * <pre>
 * TtsClient tts = TtsClient.create();                    // after SkEgnManager.initEngine
 * tts.ttsAsync(new TtsRequest("床前明月光").setVoice("xiaoyan"), null, new TtsClient.Callback() {
 *     public void onSuccess(TtsResult r) { play(r.getAbsoluteUrl()); }
 *     public void onFailure(YuguApiException e) { show(e.getCode() + " " + e.getMessage()); }
 * });
 * </pre>
 *
 * Every call carries an Idempotency-Key (new per call unless {@link TtsOptions#setIdempotencyKey}),
 * reused by its retries, so a retried call is synthesised and billed once. One INFO log line per call
 * gives the status, the attempts and the milliseconds; text, secretKey and signatures are never logged.
 */
public final class TtsClient {
    static final String PATH = "/api/v1/tts/generate";

    /** Result of {@link #ttsAsync}, on the main thread; exactly one of the two methods runs. */
    public interface Callback {
        void onSuccess(TtsResult result);

        void onFailure(YuguApiException error);
    }

    private final ApiSupport.Account account;

    private TtsClient(ApiSupport.Account account) {
        this.account = account;
    }

    /**
     * Client with the appKey, secretKey, server address and timeouts of the engine initialised through
     * {@code SkEgnManager.initEngine}.
     *
     * @throws IllegalStateException {@code 引擎未初始化} when no engine is initialised
     */
    public static TtsClient create() {
        return new TtsClient(ApiSupport.engineAccount());
    }

    /**
     * Client with explicit credentials on the default server ({@code YuguCompat.setBaseUrl} when set,
     * otherwise {@code https://open.shengzhiai.com}).
     *
     * @throws IllegalArgumentException for a blank appKey or secretKey
     */
    public static TtsClient create(String appKey, String secretKey) {
        return create(appKey, secretKey, null);
    }

    /**
     * Client with explicit credentials and platform base URL; a null or blank base URL is the default
     * server.
     *
     * @throws IllegalArgumentException for a blank appKey or secretKey, or a base URL without http,
     *         https, ws or wss scheme
     */
    public static TtsClient create(String appKey, String secretKey, String baseUrl) {
        return new TtsClient(ApiSupport.explicitAccount(appKey, secretKey, baseUrl));
    }

    /** Platform base URL of the next call, without trailing slash. */
    public String getBaseUrl() {
        return account.baseUrl();
    }

    /**
     * Synthesises speech and waits for the answer. Blocks on the network: call it on a worker thread,
     * or use {@link #ttsAsync}.
     *
     * @param request text required; null and empty fields are not sent
     * @param options null for the defaults
     * @throws IllegalStateException on the main thread
     * @throws IllegalArgumentException for a missing request or a blank text
     * @throws YuguApiException when the platform or the network fails
     */
    public TtsResult tts(TtsRequest request, TtsOptions options) {
        ApiSupport.requireWorkerThread("TtsClient.tts", "ttsAsync");
        return execute(prepare(request, options));
    }

    /**
     * Synthesises speech on a worker thread and delivers the result to {@code callback} on the main
     * thread. Argument errors throw at once, as in {@link #tts}.
     *
     * @throws IllegalArgumentException for a missing request or callback, or a blank text
     */
    public void ttsAsync(TtsRequest request, TtsOptions options, final Callback callback) {
        if (callback == null) {
            throw new IllegalArgumentException("callback 不能为空");
        }
        final CompatClient.ApiRequest req = prepare(request, options);
        ApiSupport.executor().execute(new Runnable() {
            @Override
            public void run() {
                TtsResult result = null;
                YuguApiException error = null;
                try {
                    result = execute(req);
                } catch (YuguApiException e) {
                    error = e;
                } catch (RuntimeException e) {
                    error = ApiSupport.unreadable("tts failed: " + e, e);
                }
                final TtsResult r = result;
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

    /** Validates the arguments and builds the call: JSON body, signed fields, Idempotency-Key. */
    private CompatClient.ApiRequest prepare(TtsRequest request, TtsOptions options) {
        if (request == null) {
            throw new IllegalArgumentException("request 不能为空");
        }
        if (Codec.isBlank(request.getText())) {
            throw new IllegalArgumentException("text 不能为空");
        }
        LinkedHashMap<String, Object> body = new LinkedHashMap<String, Object>();
        put(body, "text", request.getText());
        put(body, "language", request.getLanguage());
        put(body, "voice", request.getVoice());
        put(body, "format", request.getFormat());
        put(body, "speed", request.getSpeed());
        put(body, "pitch", request.getPitch());
        put(body, "volume", request.getVolume());
        put(body, "sampleRate", request.getSampleRate());
        put(body, "emotion", request.getEmotion());
        put(body, "style", request.getStyle());
        put(body, "pinyin", request.getPinyin());
        put(body, "genre", request.getGenre());
        StringBuilder json = new StringBuilder(64 + request.getText().length());
        // CONTRACT 2.3: the top-level non-empty scalar fields as text, numbers in plain decimal
        LinkedHashMap<String, String> signed = new LinkedHashMap<String, String>();
        json.append('{');
        for (Map.Entry<String, Object> e : body.entrySet()) {
            if (json.length() > 1) {
                json.append(',');
            }
            Object v = e.getValue();
            boolean number = v instanceof Integer;
            String text = String.valueOf(v);
            json.append(RawJson.quote(e.getKey())).append(':').append(number ? text : RawJson.quote(text));
            signed.put(e.getKey(), text);
        }
        json.append('}');
        CompatClient.ApiRequest req = ApiSupport.request(account, "POST", PATH);
        req.json = json.toString().getBytes(Codec.UTF_8);
        req.signed = signed;
        String key = options != null ? options.getIdempotencyKey() : null;
        req.idempotencyKey = key != null ? key : Codec.newTokenId();
        return req;
    }

    private static void put(Map<String, Object> m, String key, Object value) {
        if (value instanceof String && ((String) value).isEmpty()) {
            return;
        }
        if (value != null) {
            m.put(key, value);
        }
    }

    private TtsResult execute(CompatClient.ApiRequest req) {
        long t0 = ApiSupport.nowMs();
        CompatClient.Result r;
        try {
            r = CompatClient.fromConfig().executeApi(req, new CancelToken());
        } catch (CompatError e) {
            YuguApiException x = ApiSupport.exception(e);
            YLog.i("tts status=error code=" + x.getCode() + " http=" + x.getHttpStatus() + " attempts=" + e.getAttempts()
                    + " ms=" + (ApiSupport.nowMs() - t0));
            throw x;
        }
        TtsResult result;
        try {
            JSONObject data = new JSONObject(RawJson.members(r.body.trim()).get("data"));
            String audioUrl = text(data, "audioUrl");
            Object d = data.opt("duration");
            String duration = d instanceof String ? (String) d : d instanceof Number ? String.valueOf(d) : null;
            result = new TtsResult(audioUrl, ApiSupport.resolveAudioUrl(req.baseUrl, audioUrl), duration,
                    text(data, "format"), req.idempotencyKey, r.replayed, r.attempts, r.body);
        } catch (JSONException e) {
            YuguApiException x = ApiSupport.unreadable("tts response data is not readable: " + e.getMessage(), e);
            YLog.i("tts status=error code=" + x.getCode() + " http=" + x.getHttpStatus() + " attempts=" + r.attempts
                    + " ms=" + (ApiSupport.nowMs() - t0));
            throw x;
        }
        YLog.i("tts status=ok attempts=" + r.attempts + (r.replayed ? " replayed" : "") + " ms=" + (ApiSupport.nowMs() - t0));
        return result;
    }

    private static String text(JSONObject o, String key) {
        Object v = o.opt(key);
        return v instanceof String ? (String) v : null;
    }
}
