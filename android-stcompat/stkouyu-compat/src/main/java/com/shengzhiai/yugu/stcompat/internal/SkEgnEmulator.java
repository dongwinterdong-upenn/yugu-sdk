// SPDX-License-Identifier: Apache-2.0
package com.shengzhiai.yugu.stcompat.internal;

import android.content.Context;

import com.stkouyu.SkEgn;
import com.stkouyu.setting.RecordSetting;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Emulation of the skegn C API behind {@code com.stkouyu.SkEgn} (DESIGN 6): engines are handle
 * ids, {@code skegn_start} takes the Shengtong parameter JSON
 * {@code {coreProvideType, app:{userId}, audio:{audioType, sampleRate, channel, sampleBytes},
 * request:{coreType, refText, ...}}}, {@code skegn_feed} appends audio, {@code skegn_stop}
 * evaluates over compat REST and delivers the envelope through
 * {@code skegn_callback.run(id, SKEGN_MESSAGE_TYPE_JSON, data, size)} on the main thread.
 * Error numbers follow the public skegn_errno.h.
 *
 * <p>With {@code request.realtime_feedback} 1 and 16 kHz 16 bit mono audio the session streams over
 * the compat realtime channel like {@code SkEgnManager} (DESIGN 14.3): intermediate results with
 * eof 0 arrive through the same callback before the final result. Other sessions upload after
 * skegn_stop as in 2.1.0. SDK 2.1.1 adds the timing log of {@link EvalTiming}: timing=1 at INFO and
 * DEBUG, the platform's timing object removed before the callback.
 *
 * <p>Unsupported coreType (SDK 2.2.0, same as the iOS layer): skegn_start returns 0, logs one WARN
 * line and posts the error JSON with errId 60003 at once; the session counts as stopped, so
 * skegn_feed fails with {@link #SGN_FUNCTION_FEED_BEFORE_START} and skegn_stop with
 * {@link #SGN_FUNCTION_STOP_AND_STOP}, also after the callback. With
 * {@code YuguCompat.setThrowOnUnsupportedCoreType(true)} skegn_start throws IllegalArgumentException
 * instead, after the handle, callback, id and param checks and before anything changes.
 */
public final class SkEgnEmulator {
    public static final int NONE = 0;
    public static final int SGN_BADCFG = 1;
    public static final int SGN_PARAMETER_WRONG = 4;
    public static final int SGN_FUNCTION_FEED_BEFORE_START = 21;
    public static final int SGN_FUNCTION_WAIT_FOR_CALLBACK = 22;
    public static final int SGN_FUNCTION_START_AND_START = 23;
    public static final int SGN_FUNCTION_STOP_AND_STOP = 24;
    public static final int SGN_FUNCTION_STOP_BEFORE_START = 25;
    public static final int SGN_BAD_PRAMA = 26;
    public static final int SGN_ENGINE_IS_NULL = 31;
    public static final int SGN_ID_IS_NULL = 32;
    public static final int SGN_CALLBACK_IS_NULL = 33;

    static final String PROVISION_JSON = "{\"provision\":\"cloud\",\"message\":\"cloud mode, no provision file needed\"}";

    private static final Map<Long, Engine> ENGINES = new HashMap<Long, Engine>();
    private static final AtomicLong NEXT_ID = new AtomicLong(1);
    private static volatile int lastError = NONE;

    private SkEgnEmulator() {
    }

    static final class Engine {
        final long id;
        final String appKey;
        final String secretKey;
        final String baseUrl;
        final int connectTimeoutMs;
        final int readTimeoutMs;
        final Context context;
        Session session;
        long uploaded;
        long downloaded;

        Engine(long id, String appKey, String secretKey, String baseUrl, int connectTimeoutMs, int readTimeoutMs,
               Context context) {
            this.id = id;
            this.appKey = appKey;
            this.secretKey = secretKey;
            this.baseUrl = baseUrl;
            this.connectTimeoutMs = connectTimeoutMs;
            this.readTimeoutMs = readTimeoutMs;
            this.context = context;
        }
    }

    static final class Session {
        final String tokenId;
        final byte[] idBytes;
        final String coreType;
        final String refText;
        final String userId;
        final String audioType;
        final int sampleRate;
        final int channel;
        final boolean needParams;
        final LinkedHashMap<String, String> fields;
        final SkEgn.skegn_callback callback;
        final ByteArrayOutputStream audio = new ByteArrayOutputStream();
        final CancelToken token = new CancelToken();
        /** The log level was INFO or DEBUG at skegn_start: requests carry timing=1. */
        final boolean timing = ServerTiming.wanted();
        final EvalTiming phases;
        boolean stopped;
        boolean finished;
        int invalidErrId;
        /** Unsupported coreType: answered with errId 60003 at skegn_start, feed and stop fail like on iOS. */
        boolean rejected;
        /** Realtime round when the request asks for realtime_feedback, else null. */
        RealtimeRound round;

        Session(String tokenId, String coreType, String refText, String userId, String audioType, int sampleRate,
                int channel, boolean needParams, LinkedHashMap<String, String> fields, SkEgn.skegn_callback callback) {
            this.tokenId = tokenId;
            this.idBytes = tokenId.getBytes(Codec.US_ASCII);
            this.coreType = coreType;
            this.refText = refText;
            this.userId = userId;
            this.audioType = audioType;
            this.sampleRate = sampleRate;
            this.channel = channel;
            this.needParams = needParams;
            this.fields = fields;
            this.callback = callback;
            this.phases = new EvalTiming(tokenId);
        }
    }

    private static int fail(int err) {
        lastError = err;
        return -1;
    }

    public static int lastError() {
        return lastError;
    }

    /** skegn_new: cfg {@code {appKey, secretKey, cloud:{server, connectTimeout, serverTimeout}}}; 0 on failure. */
    public static long create(String cfg, Object androidContext) {
        JSONObject c;
        try {
            c = new JSONObject(cfg == null ? "" : cfg);
        } catch (JSONException e) {
            lastError = SGN_BADCFG;
            return 0;
        }
        String appKey = c.optString("appKey", "").trim();
        String secretKey = c.optString("secretKey", "").trim();
        if (appKey.isEmpty() || secretKey.isEmpty()) {
            lastError = SGN_BADCFG;
            return 0;
        }
        JSONObject cloud = c.optJSONObject("cloud");
        String server = cloud != null ? cloud.optString("server", "") : c.optString("server", "");
        String base;
        try {
            base = ServerAddress.resolve(server);
        } catch (IllegalArgumentException e) {
            lastError = SGN_BADCFG;
            return 0;
        }
        int connect = cloud != null ? cloud.optInt("connectTimeout", 0) : 0;
        int serverTimeout = cloud != null ? cloud.optInt("serverTimeout", 0) : 0;
        long id = NEXT_ID.getAndIncrement();
        Context ctx = androidContext instanceof Context ? (Context) androidContext : null;
        Engine e = new Engine(id, appKey, secretKey, base,
                connect > 0 ? connect * 1000 : CompatConfig.DEFAULT_CONNECT_TIMEOUT_MS,
                serverTimeout > 0 ? serverTimeout * 1000 : CompatConfig.DEFAULT_READ_TIMEOUT_MS, ctx);
        synchronized (ENGINES) {
            ENGINES.put(id, e);
        }
        lastError = NONE;
        YLog.i("skegn_new handle " + id + " appKey=" + YLog.maskKey(appKey));
        return id;
    }

    private static Engine engine(long id) {
        synchronized (ENGINES) {
            return ENGINES.get(id);
        }
    }

    public static int delete(long id) {
        Engine e;
        synchronized (ENGINES) {
            e = ENGINES.remove(id);
        }
        if (e == null) {
            return fail(SGN_ENGINE_IS_NULL);
        }
        synchronized (e) {
            if (e.session != null) {
                e.session.token.cancel();
                e.session = null;
            }
        }
        lastError = NONE;
        return 0;
    }

    public static int start(long id, String param, byte[] idOut, SkEgn.skegn_callback callback, Object ctx) {
        Engine e = engine(id);
        if (e == null) {
            return fail(SGN_ENGINE_IS_NULL);
        }
        if (callback == null) {
            return fail(SGN_CALLBACK_IS_NULL);
        }
        if (idOut == null) {
            return fail(SGN_ID_IS_NULL);
        }
        JSONObject p;
        try {
            p = new JSONObject(param == null ? "" : param);
        } catch (JSONException ex) {
            return fail(SGN_BAD_PRAMA);
        }
        JSONObject request = p.optJSONObject("request");
        if (request == null) {
            return fail(SGN_BAD_PRAMA);
        }
        String coreType = request.optString("coreType", "").trim();
        String refText = request.optString("refText", null);
        RecordSetting probe = new RecordSetting(coreType, refText);
        probe.setRefPinyin(request.optString("refPinyin", null));
        // before anything changes: the engine's session stays as it is when this throws
        FormMapper.throwIfUnsupported("skegn_start", probe);
        final Session rejected;
        synchronized (e) {
            Session old = e.session;
            if (old != null && !old.finished) {
                if (!old.stopped) {
                    return fail(SGN_FUNCTION_START_AND_START);
                }
                old.token.cancel();
                lastError = SGN_FUNCTION_WAIT_FOR_CALLBACK;
            }
            JSONObject app = p.optJSONObject("app");
            JSONObject audio = p.optJSONObject("audio");
            LinkedHashMap<String, String> fields = requestFields(request);
            if (FormMapper.isParagraph(coreType)) {
                fields.put("paragraph_need_word_score", "1");
            }
            String tokenId = Codec.newTokenId();
            Session s = new Session(tokenId, coreType, refText, app != null ? app.optString("userId", "") : "",
                    audio != null ? audio.optString("audioType", "wav") : "wav",
                    audio != null ? audio.optInt("sampleRate", Wav.SAMPLE_RATE) : Wav.SAMPLE_RATE,
                    audio != null ? audio.optInt("channel", Wav.CHANNELS) : Wav.CHANNELS,
                    request.optInt("getParam", 0) == 1 || request.optBoolean("getParam", false), fields, callback);
            s.invalidErrId = FormMapper.validate(probe);
            if (s.invalidErrId == CompatErrIds.CORE_TYPE_UNSUPPORTED) {
                // answered at once without network I/O; feed and stop fail from now on (iOS skegn.m)
                s.rejected = true;
                s.stopped = true;
            } else if (s.invalidErrId == 0 && realtimeRequested(request)) {
                int sampleBytes = audio != null ? audio.optInt("sampleBytes", 2) : 2;
                if (isPcm16kMono(s.audioType, s.sampleRate, s.channel, sampleBytes)) {
                    s.round = new RealtimeRound(roundConfig(e, s), progressListener(e, s), s.token);
                    s.phases.streaming();
                } else {
                    YLog.i("realtime_feedback needs 16 kHz 16 bit mono PCM (audioType " + s.audioType + ", sampleRate "
                            + s.sampleRate + ", channel " + s.channel + "); evaluated over REST without intermediate results");
                }
            }
            e.session = s;
            int n = Math.min(idOut.length, s.idBytes.length);
            System.arraycopy(s.idBytes, 0, idOut, 0, n);
            for (int i = n; i < idOut.length; i++) {
                idOut[i] = 0;
            }
            if (old == null || old.finished || !old.stopped) {
                lastError = NONE;
            }
            if (s.round != null) {
                s.round.start();
            }
            rejected = s.rejected ? s : null;
        }
        if (rejected != null) {
            String message = FormMapper.rejection(probe, CompatErrIds.CORE_TYPE_UNSUPPORTED);
            YLog.w("skegn_start rejected (errId " + CompatErrIds.CORE_TYPE_UNSUPPORTED + "): " + message);
            rejected.phases.error(CompatErrIds.CORE_TYPE_UNSUPPORTED);
            deliver(e, rejected, Envelope.error(rejected.tokenId, CompatErrIds.CORE_TYPE_UNSUPPORTED, message, e.appKey));
        }
        return 0;
    }

    /** realtime_feedback of the request: 1, true, "1" or "true". */
    static boolean realtimeRequested(JSONObject request) {
        Object v = request.opt("realtime_feedback");
        if (v instanceof Boolean) {
            return (Boolean) v;
        }
        if (v instanceof Number) {
            return ((Number) v).doubleValue() == 1.0;
        }
        if (v instanceof String) {
            String t = ((String) v).trim();
            return "1".equals(t) || "true".equalsIgnoreCase(t);
        }
        return false;
    }

    /** The platform tracks reading progress on 16 kHz 16 bit mono PCM (with or without a WAV header) only. */
    static boolean isPcm16kMono(String audioType, int sampleRate, int channel, int sampleBytes) {
        String t = audioType == null ? "wav" : audioType.trim().toLowerCase(java.util.Locale.ROOT);
        return (t.equals("wav") || t.equals("pcm") || t.equals("raw")) && sampleRate == Wav.SAMPLE_RATE
                && channel == Wav.CHANNELS && sampleBytes == 2;
    }

    private static RealtimeRound.Config roundConfig(Engine e, Session s) {
        RealtimeRound.Config c = new RealtimeRound.Config();
        c.baseUrl = ServerAddress.effectiveBase(e.baseUrl);
        c.coreType = s.coreType;
        c.appKey = e.appKey;
        c.secretKey = e.secretKey;
        c.tokenId = s.tokenId;
        c.fields = s.fields;
        c.connectTimeoutMs = CompatConfig.connectTimeoutMs() > 0 ? CompatConfig.connectTimeoutMs() : e.connectTimeoutMs;
        c.readTimeoutMs = CompatConfig.readTimeoutMs() > 0 ? CompatConfig.readTimeoutMs() : e.readTimeoutMs;
        c.totalTimeoutMs = CompatConfig.totalTimeoutMs();
        c.realtimeFeedback = true;
        c.timing = s.timing;
        c.phases = s.phases;
        return c;
    }

    /** Intermediate results through skegn_callback with eof 0, on the main thread, until the final one. */
    private static RealtimeRound.Listener progressListener(final Engine e, final Session s) {
        return new RealtimeRound.Listener() {
            @Override
            public void onProgress(String resultJson, String recordId) {
                final String json = Envelope.progress(s.tokenId, recordId, e.appKey, s.userId, s.refText, resultJson,
                        System.currentTimeMillis());
                MainThread.post(new Runnable() {
                    @Override
                    public void run() {
                        synchronized (e) {
                            if (s.token.isCancelled() || s.finished) {
                                return;
                            }
                        }
                        byte[] data = json.getBytes(Codec.UTF_8);
                        s.callback.run(s.idBytes, SkEgn.SKEGN_MESSAGE_TYPE_JSON, data, data.length);
                    }
                });
            }
        };
    }

    /** Request members become form fields with the same names; coreType is the path. */
    static LinkedHashMap<String, String> requestFields(JSONObject request) {
        LinkedHashMap<String, String> f = new LinkedHashMap<String, String>();
        Iterator<String> keys = request.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            if ("coreType".equals(k) || "tokenId".equals(k) || "getParam".equals(k) || "realtime_feedback".equals(k)) {
                continue;
            }
            Object v = request.opt(k);
            if (v == null || v == JSONObject.NULL) {
                continue;
            }
            String text;
            if (v instanceof Boolean) {
                text = ((Boolean) v) ? "1" : "0";
            } else if (v instanceof JSONObject || v instanceof JSONArray) {
                text = v.toString();
            } else {
                text = FormMapper.valueOf(v);
            }
            if (!text.isEmpty()) {
                f.put(k, text);
            }
        }
        return f;
    }

    public static int feed(long id, byte[] data, int size) {
        Engine e = engine(id);
        if (e == null) {
            return fail(SGN_ENGINE_IS_NULL);
        }
        synchronized (e) {
            Session s = e.session;
            if (s == null || s.finished) {
                return fail(SGN_FUNCTION_FEED_BEFORE_START);
            }
            if (s.stopped) {
                return fail(SGN_FUNCTION_FEED_BEFORE_START);
            }
            if (data == null || size <= 0) {
                return 0;
            }
            int n = Math.min(size, data.length);
            s.audio.write(data, 0, n);
            if (s.round != null) {
                s.round.audio(data, 0, n);
            }
            return 0;
        }
    }

    public static int stop(long id) {
        final Engine e = engine(id);
        if (e == null) {
            return fail(SGN_ENGINE_IS_NULL);
        }
        final Session s;
        synchronized (e) {
            s = e.session;
            if (s != null && s.rejected) {
                // as the iOS layer: the session was answered at skegn_start, before and after its callback
                return fail(SGN_FUNCTION_STOP_AND_STOP);
            }
            if (s == null || s.finished) {
                return fail(SGN_FUNCTION_STOP_BEFORE_START);
            }
            if (s.stopped) {
                return fail(SGN_FUNCTION_STOP_AND_STOP);
            }
            s.stopped = true;
        }
        s.phases.stop();
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                evaluate(e, s);
            }
        }, "yugu-stcompat-skegn");
        t.setDaemon(true);
        t.start();
        return 0;
    }

    public static int cancel(long id) {
        Engine e = engine(id);
        if (e == null) {
            return fail(SGN_ENGINE_IS_NULL);
        }
        synchronized (e) {
            if (e.session != null) {
                e.session.token.cancel();
                e.session.finished = true;
                e.session = null;
            }
        }
        return 0;
    }

    private static void evaluate(Engine e, Session s) {
        byte[] audio = s.audio.toByteArray();
        boolean isWav = audio.length >= 12 && "RIFF".equals(new String(audio, 0, 4, Codec.US_ASCII));
        String type = s.audioType == null ? "wav" : s.audioType.toLowerCase(java.util.Locale.ROOT);
        byte[] upload;
        String contentType;
        String ext;
        if (isWav) {
            upload = audio;
            contentType = "audio/wav";
            ext = "wav";
        } else if (type.equals("wav") || type.equals("pcm") || type.equals("raw")) {
            upload = new byte[Wav.HEADER_SIZE + audio.length];
            System.arraycopy(Wav.header(s.sampleRate, s.channel, Wav.BITS, audio.length), 0, upload, 0, Wav.HEADER_SIZE);
            System.arraycopy(audio, 0, upload, Wav.HEADER_SIZE, audio.length);
            contentType = "audio/wav";
            ext = "wav";
        } else {
            upload = audio;
            contentType = type.equals("mp3") ? "audio/mpeg" : "application/octet-stream";
            ext = type;
        }
        int err = s.invalidErrId;
        Wav.Info wav = "audio/wav".equals(contentType) ? Wav.parse(upload, upload.length, upload.length) : null;
        s.phases.audioFile(upload.length, wav != null ? wav.durationSeconds() : -1);
        if (err == 0) {
            if (audio.length == 0) {
                err = CompatErrIds.AUDIO_EMPTY;
            } else if (upload.length > CompatConfig.MAX_AUDIO_BYTES) {
                err = CompatErrIds.AUDIO_TOO_LARGE;
            } else if (wav != null && wav.durationSeconds() < 1.0) {
                err = wav.dataLength == 0 ? CompatErrIds.AUDIO_EMPTY : CompatErrIds.AUDIO_TOO_SHORT;
            } else if (wav != null && wav.durationSeconds() > CompatConfig.MAX_AUDIO_SECONDS) {
                err = CompatErrIds.AUDIO_TOO_LARGE;
            }
        }
        String json = null;
        if (err != 0) {
            if (s.round != null) {
                // no end: nothing is evaluated or billed
                s.round.cancel();
            }
            s.phases.error(err);
            json = Envelope.error(s.tokenId, err, CompatErrIds.message(err), e.appKey);
        } else if (s.round != null) {
            RealtimeRound.Outcome o = s.round.finish();
            s.round.cancel();
            if (o.kind == RealtimeRound.Outcome.CANCELLED) {
                return;
            }
            if (o.kind == RealtimeRound.Outcome.RESULT) {
                s.phases.replayed(o.replayed);
                json = successJson(e, s, o.body, audio.length);
            } else if (o.kind == RealtimeRound.Outcome.ERROR) {
                int errId = ErrorMapper.errId(o.error);
                s.phases.error(errId);
                json = Envelope.error(s.tokenId, errId, ErrorMapper.message(o.error, errId), e.appKey);
            } else {
                // FALLBACK: the channel failed before end, evaluate over REST below with the same tokenId
                s.phases.fallback(o.reason);
            }
        }
        if (json == null) {
            CompatClient.Request req = new CompatClient.Request();
            req.baseUrl = ServerAddress.effectiveBase(e.baseUrl);
            req.coreType = s.coreType;
            req.appKey = e.appKey;
            req.secretKey = e.secretKey;
            req.idempotencyKey = s.tokenId;
            req.fields = ServerTiming.requestFields(s.fields, s.timing);
            req.phases = s.phases;
            req.audioBytes = upload;
            req.audioFilename = s.tokenId + "." + ext;
            req.audioContentType = contentType;
            req.policy = CompatConfig.retryPolicy();
            req.totalTimeoutMs = CompatConfig.totalTimeoutMs();
            req.connectTimeoutMs = CompatConfig.connectTimeoutMs() > 0 ? CompatConfig.connectTimeoutMs() : e.connectTimeoutMs;
            req.readTimeoutMs = CompatConfig.readTimeoutMs() > 0 ? CompatConfig.readTimeoutMs() : e.readTimeoutMs;
            try {
                CompatClient.Result r = CompatClient.fromConfig().execute(req, s.token);
                s.phases.replayed(r.replayed);
                json = successJson(e, s, r.body, upload.length);
            } catch (CompatError ex) {
                if (ex.isCancelled()) {
                    return;
                }
                int errId = ErrorMapper.errId(ex);
                s.phases.error(errId);
                json = Envelope.error(s.tokenId, errId, ErrorMapper.message(ex, errId), e.appKey);
            }
        }
        deliver(e, s, json);
    }

    /**
     * Final envelope of a platform body; the REST answer and the realtime final frame share it. The
     * platform's timing object goes to the timing log, never into the callback JSON.
     */
    private static String successJson(Engine e, Session s, String body, int uploadBytes) {
        synchronized (e) {
            e.uploaded += uploadBytes;
            e.downloaded += body.length();
        }
        ServerTiming.Split split = ServerTiming.split(body);
        s.phases.serverBody(split.timing);
        String params = s.needParams ? Envelope.params(e.appKey, s.userId, System.currentTimeMillis(),
                s.audioType, s.sampleRate, s.channel, s.coreType, s.tokenId, s.fields) : null;
        try {
            String json = Envelope.success(s.tokenId, e.appKey, s.userId, s.refText, split.body, params,
                    System.currentTimeMillis());
            s.phases.recordId(Envelope.recordIdOf(split.body));
            s.phases.parsed();
            return json;
        } catch (IllegalArgumentException ex) {
            s.phases.error(ErrorTable.PROTOCOL_ERROR);
            return Envelope.error(s.tokenId, ErrorTable.PROTOCOL_ERROR, "invalid platform response", e.appKey);
        }
    }

    private static void deliver(final Engine e, final Session s, final String json) {
        MainThread.post(new Runnable() {
            @Override
            public void run() {
                synchronized (e) {
                    if (s.token.isCancelled() || s.finished) {
                        return;
                    }
                    s.finished = true;
                }
                s.phases.delivered();
                s.phases.log();
                byte[] data = json.getBytes(Codec.UTF_8);
                s.callback.run(s.idBytes, SkEgn.SKEGN_MESSAGE_TYPE_JSON, data, data.length);
            }
        });
    }

    /** skegn_opt: writes the answer into {@code data} and returns the number of bytes written. */
    public static int opt(long id, int opt, byte[] data, int size) {
        Engine e = engine(id);
        String answer;
        if (opt == SkEgn.SKEGN_OPT_GET_VERSION) {
            answer = CompatConfig.SDK_VERSION;
        } else if (opt == SkEgn.SKEGN_OPT_GET_MODULES) {
            answer = "{\"modules\":[\"cloud\"]}";
        } else if (opt == SkEgn.SKEGN_OPT_GET_TRAFFIC) {
            long up = 0;
            long down = 0;
            if (e != null) {
                synchronized (e) {
                    up = e.uploaded;
                    down = e.downloaded;
                }
            }
            answer = "{\"upload\":" + up + ",\"download\":" + down + "}";
        } else if (opt == SkEgn.SKEGN_OPT_SET_WIFI_STATUS) {
            return 0;
        } else if (opt == SkEgn.SKEGN_OPT_GET_PROVISION) {
            answer = PROVISION_JSON;
        } else if (opt == SkEgn.SKEGN_OPT_GET_SERIAL_NUMBER) {
            answer = "{\"serialNumber\":" + RawJson.quote(DeviceId.get(e != null ? e.context : null)) + "}";
        } else {
            return fail(SGN_PARAMETER_WRONG);
        }
        return write(answer, data, size);
    }

    private static int write(String answer, byte[] data, int size) {
        if (data == null) {
            return fail(SGN_PARAMETER_WRONG);
        }
        byte[] b = answer.getBytes(Codec.UTF_8);
        int room = Math.min(size > 0 ? size : data.length, data.length);
        int n = Math.min(b.length, room);
        System.arraycopy(b, 0, data, 0, n);
        if (n < room) {
            data[n] = 0;
        }
        return n;
    }

    public static int deviceId(byte[] out, Object ctx) {
        if (out == null) {
            return fail(SGN_PARAMETER_WRONG);
        }
        String id = DeviceId.get(ctx instanceof Context ? (Context) ctx : null);
        write(id, out, out.length);
        return 0;
    }

    public static int inquireProvision(String provisionPath, final SkEgn.skegn_callback cb, Object ctx) {
        if (cb == null) {
            return fail(SGN_CALLBACK_IS_NULL);
        }
        MainThread.post(new Runnable() {
            @Override
            public void run() {
                byte[] data = PROVISION_JSON.getBytes(Codec.UTF_8);
                cb.run(new byte[0], SkEgn.SKEGN_MESSAGE_TYPE_JSON, data, data.length);
            }
        });
        return 0;
    }

    /** Test hook. */
    public static void resetForTests() {
        synchronized (ENGINES) {
            for (Engine e : ENGINES.values()) {
                synchronized (e) {
                    if (e.session != null) {
                        e.session.token.cancel();
                    }
                }
            }
            ENGINES.clear();
        }
        lastError = NONE;
    }
}
