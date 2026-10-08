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
        boolean stopped;
        boolean finished;
        int invalidErrId;

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
            String coreType = request.optString("coreType", "").trim();
            String refText = request.optString("refText", null);
            RecordSetting probe = new RecordSetting(coreType, refText);
            probe.setRefPinyin(request.optString("refPinyin", null));
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
            e.session = s;
            int n = Math.min(idOut.length, s.idBytes.length);
            System.arraycopy(s.idBytes, 0, idOut, 0, n);
            for (int i = n; i < idOut.length; i++) {
                idOut[i] = 0;
            }
            if (old == null || old.finished || !old.stopped) {
                lastError = NONE;
            }
            return 0;
        }
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
            s.audio.write(data, 0, Math.min(size, data.length));
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
            if (s == null || s.finished) {
                return fail(SGN_FUNCTION_STOP_BEFORE_START);
            }
            if (s.stopped) {
                return fail(SGN_FUNCTION_STOP_AND_STOP);
            }
            s.stopped = true;
        }
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
        if (err == 0) {
            if (audio.length == 0) {
                err = CompatErrIds.AUDIO_EMPTY;
            } else if (upload.length > CompatConfig.MAX_AUDIO_BYTES) {
                err = CompatErrIds.AUDIO_TOO_LARGE;
            } else if ("audio/wav".equals(contentType)) {
                Wav.Info info = Wav.parse(upload, upload.length, upload.length);
                if (info != null && info.durationSeconds() < 1.0) {
                    err = info.dataLength == 0 ? CompatErrIds.AUDIO_EMPTY : CompatErrIds.AUDIO_TOO_SHORT;
                } else if (info != null && info.durationSeconds() > CompatConfig.MAX_AUDIO_SECONDS) {
                    err = CompatErrIds.AUDIO_TOO_LARGE;
                }
            }
        }
        String json;
        if (err != 0) {
            json = Envelope.error(s.tokenId, err, CompatErrIds.message(err), e.appKey);
        } else {
            CompatClient.Request req = new CompatClient.Request();
            req.baseUrl = ServerAddress.effectiveBase(e.baseUrl);
            req.coreType = s.coreType;
            req.appKey = e.appKey;
            req.secretKey = e.secretKey;
            req.idempotencyKey = s.tokenId;
            req.fields = s.fields;
            req.audioBytes = upload;
            req.audioFilename = s.tokenId + "." + ext;
            req.audioContentType = contentType;
            req.policy = CompatConfig.retryPolicy();
            req.totalTimeoutMs = CompatConfig.totalTimeoutMs();
            req.connectTimeoutMs = CompatConfig.connectTimeoutMs() > 0 ? CompatConfig.connectTimeoutMs() : e.connectTimeoutMs;
            req.readTimeoutMs = CompatConfig.readTimeoutMs() > 0 ? CompatConfig.readTimeoutMs() : e.readTimeoutMs;
            try {
                CompatClient.Result r = CompatClient.fromConfig().execute(req, s.token);
                synchronized (e) {
                    e.uploaded += upload.length;
                    e.downloaded += r.body.length();
                }
                String params = s.needParams ? Envelope.params(e.appKey, s.userId, System.currentTimeMillis(),
                        s.audioType, s.sampleRate, s.channel, s.coreType, s.tokenId, s.fields) : null;
                try {
                    json = Envelope.success(s.tokenId, e.appKey, s.userId, s.refText, r.body, params,
                            System.currentTimeMillis());
                } catch (IllegalArgumentException ex) {
                    json = Envelope.error(s.tokenId, ErrorTable.PROTOCOL_ERROR, "invalid platform response", e.appKey);
                }
            } catch (CompatError ex) {
                if (ex.isCancelled()) {
                    return;
                }
                int errId = ErrorMapper.errId(ex);
                json = Envelope.error(s.tokenId, errId, ErrorMapper.message(ex, errId), e.appKey);
            }
        }
        deliver(e, s, json);
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
