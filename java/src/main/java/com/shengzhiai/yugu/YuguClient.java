package com.shengzhiai.yugu;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.shengzhiai.yugu.audio.AudioAnalysis;
import com.shengzhiai.yugu.audio.AudioSource;
import com.shengzhiai.yugu.errors.AudioQualityException;
import com.shengzhiai.yugu.errors.ErrorTable;
import com.shengzhiai.yugu.errors.WarningCode;
import com.shengzhiai.yugu.errors.YuguErrors;
import com.shengzhiai.yugu.errors.YuguException;
import com.shengzhiai.yugu.internal.Auth;
import com.shengzhiai.yugu.internal.DaemonThreadFactory;
import com.shengzhiai.yugu.internal.HttpEngine;
import com.shengzhiai.yugu.internal.JdkWsTransport;
import com.shengzhiai.yugu.internal.Json;
import com.shengzhiai.yugu.internal.Log;
import com.shengzhiai.yugu.internal.Multipart;
import com.shengzhiai.yugu.internal.PercentEncoding;
import com.shengzhiai.yugu.internal.ResultParser;
import com.shengzhiai.yugu.internal.WsTransport;
import com.shengzhiai.yugu.model.CompatConfig;
import com.shengzhiai.yugu.model.EvalResult;
import com.shengzhiai.yugu.model.EvaluateConfig;
import com.shengzhiai.yugu.model.ReportResult;
import com.shengzhiai.yugu.model.TtsRequest;
import com.shengzhiai.yugu.model.TtsResult;
import com.shengzhiai.yugu.model.Warning;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Client of the Yugu speech evaluation platform. Thread safe; create one per application and close
 * it when done.
 *
 * <pre>{@code
 * try (YuguClient client = YuguClient.builder().apiKey(appKey, secretKey).build()) {
 *     EvalResult r = client.evaluate(Path.of("audio.wav"),
 *             new EvaluateConfig(EvaluateConfig.CORE_SENTENCE, "今天天气很好", "zh-CN"));
 *     System.out.println(r.getOverall());
 * }
 * }</pre>
 *
 * <p>Write calls ({@code evaluate}, {@code evaluateCompat}, {@code tts}) carry an
 * {@code Idempotency-Key}; retries reuse it, so the platform bills a submission once. Errors are
 * {@link YuguException} subclasses. After {@link #close()} every call throws
 * {@code IllegalSessionStateException} (90004).
 */
public final class YuguClient implements AutoCloseable {
    /** SDK version. */
    public static final String VERSION = "2.0.0";
    /** Default User-Agent. */
    public static final String USER_AGENT = "yugu-java-sdk/" + VERSION;
    /** Core types of the native endpoints. */
    public static final List<String> NATIVE_CORE_TYPES = Collections.unmodifiableList(Arrays.asList(
            "word", "sentence", "passage", "connected", "open", "alpha", "pinyin"));
    /** Core types of the compat endpoints. */
    public static final List<String> COMPAT_CORE_TYPES = CompatConfig.CORE_TYPES;

    private static final String TAG = "client";
    private static final AtomicInteger INSTANCES = new AtomicInteger();

    private final ClientOptions options;
    private final Log log;
    private final String threadPrefix;
    private final ExecutorService httpExecutor;
    private final ExecutorService callbackPool;
    private final ScheduledExecutorService scheduler;
    private final HttpClient http;
    private final HttpEngine engine;
    private final StreamContext streamContext;
    private final Set<StreamSession> sessions = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * @param options configuration
     */
    public YuguClient(ClientOptions options) {
        this.options = options;
        this.log = new Log(options.getLogLevel(), options.getLogger());
        this.threadPrefix = "yugu-" + INSTANCES.incrementAndGet();
        this.httpExecutor = Executors.newCachedThreadPool(new DaemonThreadFactory(threadPrefix + "-http"));
        this.callbackPool = Executors.newCachedThreadPool(new DaemonThreadFactory(threadPrefix + "-callback"));
        this.scheduler = Executors.newSingleThreadScheduledExecutor(new DaemonThreadFactory(threadPrefix + "-timer"));
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(options.getConnectTimeoutMs()))
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .executor(httpExecutor)
                .build();
        Random random = options.getRandom() != null ? options.getRandom() : new Random();
        Auth auth = new Auth(options.getAppKey(), options.getSecretKey(), options.getToken());
        this.engine = new HttpEngine(http,
                new HttpEngine.Settings(options.getBaseUrl(), options.getUserAgent(), options.getReadTimeoutMs(),
                        options.getTotalTimeoutMs(), options.getRetry(), options.isAutoIdempotencyKey()),
                auth, log, options.getEventListener(), random);
        WsTransport transport = options.getTransport() != null ? options.getTransport() : new JdkWsTransport(http);
        this.streamContext = new StreamContext(options, transport, auth, scheduler, callbackPool, log,
                options.getEventListener(), random, sessions::remove);
        log.debug(TAG, "created " + options);
    }

    /**
     * @param options configuration
     * @return a new client
     */
    public static YuguClient create(ClientOptions options) {
        return new YuguClient(options);
    }

    /** @return a builder with the defaults */
    public static Builder builder() {
        return new Builder();
    }

    /** @return configuration */
    public ClientOptions getOptions() {
        return options;
    }

    // ======================================================================== native evaluation

    /**
     * Evaluates WAV or MP3 bytes with the native endpoint {@code POST /api/v1/evaluate}.
     *
     * @param audio  audio bytes
     * @param config parameters, coreType and referenceText required
     * @return result
     * @throws YuguException on failure, see the subclasses
     */
    public EvalResult evaluate(byte[] audio, EvaluateConfig config) throws YuguException {
        return evaluate(AudioSource.of(audio), null, config, null);
    }

    /**
     * @param audio   audio bytes
     * @param config  parameters
     * @param options per call options
     * @return result
     * @throws YuguException on failure
     */
    public EvalResult evaluate(byte[] audio, EvaluateConfig config, RequestOptions options) throws YuguException {
        return evaluate(AudioSource.of(audio), null, config, options);
    }

    /**
     * @param audio  audio file
     * @param config parameters
     * @return result
     * @throws YuguException on failure
     */
    public EvalResult evaluate(File audio, EvaluateConfig config) throws YuguException {
        return evaluate(AudioSource.of(audio), null, config, null);
    }

    /**
     * @param audio   audio file
     * @param config  parameters
     * @param options per call options
     * @return result
     * @throws YuguException on failure
     */
    public EvalResult evaluate(File audio, EvaluateConfig config, RequestOptions options) throws YuguException {
        return evaluate(AudioSource.of(audio), null, config, options);
    }

    /**
     * @param audio  audio file
     * @param config parameters
     * @return result
     * @throws YuguException on failure
     */
    public EvalResult evaluate(Path audio, EvaluateConfig config) throws YuguException {
        return evaluate(AudioSource.of(audio), null, config, null);
    }

    /**
     * @param audio   audio file
     * @param config  parameters
     * @param options per call options
     * @return result
     * @throws YuguException on failure
     */
    public EvalResult evaluate(Path audio, EvaluateConfig config, RequestOptions options) throws YuguException {
        return evaluate(AudioSource.of(audio), null, config, options);
    }

    /**
     * @param audio  stream with WAV or MP3 content, read to the end and not closed
     * @param config parameters
     * @return result
     * @throws YuguException on failure
     */
    public EvalResult evaluate(InputStream audio, EvaluateConfig config) throws YuguException {
        return evaluate(AudioSource.of(audio), null, config, null);
    }

    /**
     * @param audio   stream with WAV or MP3 content, read to the end and not closed
     * @param config  parameters
     * @param options per call options
     * @return result
     * @throws YuguException on failure
     */
    public EvalResult evaluate(InputStream audio, EvaluateConfig config, RequestOptions options) throws YuguException {
        return evaluate(AudioSource.of(audio), null, config, options);
    }

    /**
     * @param audio  audio source, for example {@link AudioSource#pcm(byte[], int)}
     * @param config parameters
     * @return result
     * @throws YuguException on failure
     */
    public EvalResult evaluate(AudioSource audio, EvaluateConfig config) throws YuguException {
        return evaluate(audio, null, config, null);
    }

    /**
     * Full form: audio, optional image for open questions with {@code taskType=picture}, parameters
     * and per call options.
     *
     * @param audio   audio source
     * @param image   image source, may be null
     * @param config  parameters
     * @param options per call options, may be null
     * @return result
     * @throws YuguException on failure
     */
    public EvalResult evaluate(AudioSource audio, AudioSource image, EvaluateConfig config, RequestOptions options)
            throws YuguException {
        ensureOpen();
        if (audio == null) {
            throw invalid("audio is required");
        }
        if (config == null || isEmpty(config.getCoreType()) || isEmpty(config.getReferenceText())) {
            throw invalid("config.coreType and config.referenceText are required");
        }
        AudioSource.Loaded a = audio.load();
        List<Warning> local = precheck(a);
        String configJson = Json.write(config);
        Multipart.Builder mb = Multipart.builder()
                .json("config", configJson)
                .file("audio", a.getFileName(), a.getContentType(), a.getData());
        if (image != null) {
            AudioSource.Loaded img = image.load();
            mb.file("image", img.getFileName() == null || img.getFileName().startsWith("audio.") ? "image.jpg" : img.getFileName(),
                    "application/octet-stream", img.getData());
        }
        Multipart body = mb.build();
        Map<String, String> sign = Collections.singletonMap("config", configJson);
        return engine.execute(new MultipartCall("evaluate", "/api/v1/evaluate", body, sign, local), options);
    }

    // ======================================================================== compat evaluation

    /**
     * Evaluates with the Shengtong compatible endpoint {@code POST /{coreType}}.
     *
     * @param audio  audio bytes
     * @param config compat parameters
     * @return result
     * @throws YuguException on failure
     */
    public EvalResult evaluateCompat(byte[] audio, CompatConfig config) throws YuguException {
        return evaluateCompat(AudioSource.of(audio), config, null);
    }

    /**
     * @param audio   audio bytes
     * @param config  compat parameters
     * @param options per call options
     * @return result
     * @throws YuguException on failure
     */
    public EvalResult evaluateCompat(byte[] audio, CompatConfig config, RequestOptions options) throws YuguException {
        return evaluateCompat(AudioSource.of(audio), config, options);
    }

    /**
     * @param audio  audio file
     * @param config compat parameters
     * @return result
     * @throws YuguException on failure
     */
    public EvalResult evaluateCompat(File audio, CompatConfig config) throws YuguException {
        return evaluateCompat(AudioSource.of(audio), config, null);
    }

    /**
     * @param audio   audio file
     * @param config  compat parameters
     * @param options per call options
     * @return result
     * @throws YuguException on failure
     */
    public EvalResult evaluateCompat(File audio, CompatConfig config, RequestOptions options) throws YuguException {
        return evaluateCompat(AudioSource.of(audio), config, options);
    }

    /**
     * @param audio  audio file
     * @param config compat parameters
     * @return result
     * @throws YuguException on failure
     */
    public EvalResult evaluateCompat(Path audio, CompatConfig config) throws YuguException {
        return evaluateCompat(AudioSource.of(audio), config, null);
    }

    /**
     * @param audio   audio file
     * @param config  compat parameters
     * @param options per call options
     * @return result
     * @throws YuguException on failure
     */
    public EvalResult evaluateCompat(Path audio, CompatConfig config, RequestOptions options) throws YuguException {
        return evaluateCompat(AudioSource.of(audio), config, options);
    }

    /**
     * @param audio  stream, read to the end and not closed
     * @param config compat parameters
     * @return result
     * @throws YuguException on failure
     */
    public EvalResult evaluateCompat(InputStream audio, CompatConfig config) throws YuguException {
        return evaluateCompat(AudioSource.of(audio), config, null);
    }

    /**
     * @param audio   stream, read to the end and not closed
     * @param config  compat parameters
     * @param options per call options
     * @return result
     * @throws YuguException on failure
     */
    public EvalResult evaluateCompat(InputStream audio, CompatConfig config, RequestOptions options) throws YuguException {
        return evaluateCompat(AudioSource.of(audio), config, options);
    }

    /**
     * @param audio   audio source
     * @param config  compat parameters
     * @param options per call options, may be null
     * @return result
     * @throws YuguException on failure
     */
    public EvalResult evaluateCompat(AudioSource audio, CompatConfig config, RequestOptions options) throws YuguException {
        ensureOpen();
        if (audio == null) {
            throw invalid("audio is required");
        }
        String coreType = checkCompatCoreType(config);
        AudioSource.Loaded a = audio.load();
        List<Warning> local = precheck(a);
        Map<String, String> fields = config.toFormFields();
        Multipart.Builder mb = Multipart.builder();
        for (Map.Entry<String, String> f : fields.entrySet()) {
            mb.field(f.getKey(), f.getValue());
        }
        mb.file("audio", a.getFileName(), a.getContentType(), a.getData());
        return engine.execute(new MultipartCall("evaluateCompat", "/" + coreType, mb.build(), fields, local), options);
    }

    // ======================================================================== tts and report

    /**
     * Synthesises speech with {@code POST /api/v1/tts/generate}.
     *
     * @param request request, text required
     * @return result with the audio URL
     * @throws YuguException on failure
     */
    public TtsResult tts(TtsRequest request) throws YuguException {
        return tts(request, null);
    }

    /**
     * @param request request
     * @param options per call options, may be null
     * @return result
     * @throws YuguException on failure
     */
    public TtsResult tts(TtsRequest request, RequestOptions options) throws YuguException {
        ensureOpen();
        if (request == null || isEmpty(request.getText())) {
            throw invalid("tts text is required");
        }
        Map<String, Object> fields = request.toBody();
        byte[] body = Json.writePlainObject(fields).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Map<String, String> sign = Json.scalarFields(fields);
        return engine.execute(new HttpEngine.Call<TtsResult>() {
            @Override
            public String op() {
                return "tts";
            }

            @Override
            public String method() {
                return "POST";
            }

            @Override
            public String path() {
                return "/api/v1/tts/generate";
            }

            @Override
            public boolean isWrite() {
                return true;
            }

            @Override
            public byte[] body() {
                return body;
            }

            @Override
            public String contentType() {
                return "application/json; charset=utf-8";
            }

            @Override
            public Map<String, String> signParams() {
                return sign;
            }

            @Override
            public TtsResult parse(HttpEngine.Response response, String key, int attempts) {
                JsonNode root = readEnvelope(response, key, attempts);
                JsonNode data = root.get("data");
                if (data == null || !data.isObject()) {
                    throw protocol("tts response has no data object", response, key, attempts);
                }
                TtsResult r = ResultParser.parseTts(data, YuguClient.this.options.getBaseUrl());
                r.setRaw(root);
                r.setIdempotencyKey(key);
                r.setReplayed(response.replayed());
                r.setAttempts(attempts);
                return r;
            }
        }, options);
    }

    /**
     * Queries the report of a record with {@code GET /api/v1/report/{recordId}}. Naturally idempotent,
     * retried without a key.
     *
     * @param recordId record id from {@link EvalResult#getRecordId()}
     * @return report
     * @throws YuguException on failure
     */
    public ReportResult getReport(String recordId) throws YuguException {
        return getReport(recordId, null);
    }

    /**
     * @param recordId record id
     * @param options  per call options, may be null
     * @return report
     * @throws YuguException on failure
     */
    public ReportResult getReport(String recordId, RequestOptions options) throws YuguException {
        ensureOpen();
        if (isEmpty(recordId)) {
            throw invalid("recordId is required");
        }
        String path = "/api/v1/report/" + PercentEncoding.encode(recordId);
        return engine.execute(new HttpEngine.Call<ReportResult>() {
            @Override
            public String op() {
                return "getReport";
            }

            @Override
            public String method() {
                return "GET";
            }

            @Override
            public String path() {
                return path;
            }

            @Override
            public boolean isWrite() {
                return false;
            }

            @Override
            public byte[] body() {
                return null;
            }

            @Override
            public String contentType() {
                return null;
            }

            @Override
            public Map<String, String> signParams() {
                return Collections.emptyMap();
            }

            @Override
            public ReportResult parse(HttpEngine.Response response, String key, int attempts) {
                JsonNode root = readEnvelope(response, key, attempts);
                return new ReportResult(recordId, root.get("data"), root, attempts);
            }
        }, options);
    }

    // ======================================================================== streams

    /**
     * Opens a native stream {@code wss://host/api/v1/ws/evaluate}. Returns at once; the session
     * connects in the background and queues audio until the server session started.
     *
     * @param config   parameters, coreType and referenceText required
     * @param listener callbacks
     * @return session
     * @throws YuguException {@code InvalidParameterException} for bad arguments, 90004 after close
     */
    public StreamSession streamEvaluate(EvaluateConfig config, StreamListener listener) throws YuguException {
        return streamEvaluate(config, listener, null);
    }

    /**
     * @param config   parameters
     * @param listener callbacks
     * @param options  per session options, may be null
     * @return session
     * @throws YuguException for bad arguments or after close
     */
    public StreamSession streamEvaluate(EvaluateConfig config, StreamListener listener, StreamOptions options)
            throws YuguException {
        ensureOpen();
        if (config == null || isEmpty(config.getCoreType()) || isEmpty(config.getReferenceText())) {
            throw invalid("config.coreType and config.referenceText are required");
        }
        Map<String, Object> fields = Json.MAPPER.convertValue(config, new TypeReference<LinkedHashMap<String, Object>>() {
        });
        return open(StreamSession.Kind.NATIVE, "/api/v1/ws/evaluate", fields, listener, options);
    }

    /**
     * Opens a compat stream {@code wss://host/{coreType}}.
     *
     * @param config   compat parameters
     * @param listener callbacks
     * @return session
     * @throws YuguException for bad arguments or after close
     */
    public StreamSession streamEvaluateCompat(CompatConfig config, StreamListener listener) throws YuguException {
        return streamEvaluateCompat(config, listener, null);
    }

    /**
     * @param config   compat parameters
     * @param listener callbacks
     * @param options  per session options, may be null
     * @return session
     * @throws YuguException for bad arguments or after close
     */
    public StreamSession streamEvaluateCompat(CompatConfig config, StreamListener listener, StreamOptions options)
            throws YuguException {
        ensureOpen();
        String coreType = checkCompatCoreType(config);
        return open(StreamSession.Kind.COMPAT, "/" + coreType, config.toParameterFrame(), listener, options);
    }

    private StreamSession open(StreamSession.Kind kind, String path, Map<String, Object> fields, StreamListener listener,
                               StreamOptions options) {
        if (listener == null) {
            throw invalid("listener is required");
        }
        StreamSession s = new StreamSession(streamContext, kind, path, fields, listener, options);
        sessions.add(s);
        if (closed.get()) {
            sessions.remove(s);
            throw closedError();
        }
        s.start();
        return s;
    }

    // ======================================================================== lifecycle

    /** @return true after {@link #close()} */
    public boolean isClosed() {
        return closed.get();
    }

    /** @return number of stream sessions that have not reached CLOSED */
    public int getOpenSessionCount() {
        return sessions.size();
    }

    /**
     * Closes the client: cancels open stream sessions (they end with CANCELLED and onClosed), aborts
     * running REST attempts (they throw 90004), and releases the threads. Idempotent.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        log.debug(TAG, "closing");
        engine.close();
        List<StreamSession> open = new ArrayList<>(sessions);
        for (StreamSession s : open) {
            s.cancel();
        }
        boolean onOwnThread = Thread.currentThread().getName().startsWith(threadPrefix + "-");
        if (!onOwnThread) {
            for (StreamSession s : open) {
                try {
                    s.awaitClosed(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        scheduler.shutdownNow();
        callbackPool.shutdown();
        httpExecutor.shutdown();
        shutdownHttpClient();
    }

    private void shutdownHttpClient() {
        // Java 21 added HttpClient.shutdownNow(); call it when present, the threads end at once.
        try {
            Method m = HttpClient.class.getMethod("shutdownNow");
            m.invoke(http);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Java 11 to 20: the selector thread ends when the client is collected
        }
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw closedError();
        }
    }

    private static YuguException closedError() {
        return YuguErrors.local(ErrorTable.CLIENT_CLOSED, "client is closed", null);
    }

    // ======================================================================== helpers

    private List<Warning> precheck(AudioSource.Loaded a) {
        AudioPrecheckMode mode = options.getAudioPrecheck();
        if (mode == AudioPrecheckMode.OFF) {
            return Collections.emptyList();
        }
        AudioAnalysis an = a.analyze();
        if (mode == AudioPrecheckMode.REJECT && !an.getRejectingIssues().isEmpty()) {
            Warning w = an.getRejectingIssues().get(0);
            log.warn("precheck", "rejected before upload: " + w.getCode() + " " + w.getMessage(), null);
            throw YuguErrors.local(w.getCode(), w.getMessage(), null);
        }
        for (Warning w : an.getIssues()) {
            log.warn("precheck", w.getCode() + " " + w.getMessage(), null);
        }
        return an.getIssues();
    }

    private String checkCompatCoreType(CompatConfig config) {
        if (config == null || isEmpty(config.getCoreType())) {
            throw invalid("config.coreType is required");
        }
        if (!COMPAT_CORE_TYPES.contains(config.getCoreType())) {
            throw invalid("unknown compat coreType " + config.getCoreType() + ", expected one of " + COMPAT_CORE_TYPES);
        }
        return config.getCoreType();
    }

    private static boolean isEmpty(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static YuguException invalid(String message) {
        return YuguErrors.local(ErrorTable.INVALID_ARGUMENT, message, null);
    }

    private static YuguException protocol(String message, HttpEngine.Response response, String key, int attempts) {
        return YuguErrors.create(YuguErrors.local(ErrorTable.PROTOCOL_ERROR, message, null).toDetails().toBuilder()
                .httpStatus(response.status()).rawBody(response.bodyText()).idempotencyKey(key).attempts(attempts).build());
    }

    /** Parses a 2xx body and maps a non-zero {@code code} without a result to an error. */
    private static JsonNode readJson(HttpEngine.Response response, String key, int attempts) {
        try {
            return Json.read(response.body());
        } catch (YuguException e) {
            throw protocol(e.getRawMessage(), response, key, attempts);
        }
    }

    private static JsonNode readEnvelope(HttpEngine.Response response, String key, int attempts) {
        JsonNode root = readJson(response, key, attempts);
        JsonNode code = root.get("code");
        if (code != null && code.isNumber() && code.asInt() != 0) {
            throw YuguErrors.fromHttpResponse(response.status(), response.bodyText(), -1, key, attempts);
        }
        return root;
    }

    /** Multipart evaluation call, native or compat. */
    private final class MultipartCall implements HttpEngine.Call<EvalResult> {
        private final String op;
        private final String path;
        private final Multipart body;
        private final Map<String, String> sign;
        private final List<Warning> localWarnings;

        MultipartCall(String op, String path, Multipart body, Map<String, String> sign, List<Warning> localWarnings) {
            this.op = op;
            this.path = path;
            this.body = body;
            this.sign = sign;
            this.localWarnings = localWarnings;
        }

        @Override
        public String op() {
            return op;
        }

        @Override
        public String method() {
            return "POST";
        }

        @Override
        public String path() {
            return path;
        }

        @Override
        public boolean isWrite() {
            return true;
        }

        @Override
        public byte[] body() {
            return body.body();
        }

        @Override
        public String contentType() {
            return body.contentType();
        }

        @Override
        public Map<String, String> signParams() {
            return sign;
        }

        @Override
        public EvalResult parse(HttpEngine.Response response, String key, int attempts) {
            JsonNode root = readJson(response, key, attempts);
            JsonNode code = root.get("code");
            if (code != null && code.isNumber() && code.asInt() != 0 && !root.has("result")) {
                throw YuguErrors.fromHttpResponse(response.status(), response.bodyText(), -1, key, attempts);
            }
            EvalResult r;
            try {
                r = ResultParser.parseEvaluation(root);
            } catch (YuguException e) {
                throw protocol(e.getRawMessage(), response, key, attempts);
            }
            r.setIdempotencyKey(key);
            r.setReplayed(r.isReplayed() || response.replayed());
            r.setAttempts(attempts);
            r.setLocalWarnings(localWarnings);
            if (options.isStrictAudio() && r.hasWarning(WarningCode.NO_VALID_AUDIO)) {
                AudioQualityException e = YuguErrors.fromWarning(WarningCode.NO_VALID_AUDIO.getCode(), null, r);
                throw e;
            }
            return r;
        }
    }

    /** Builder of {@link YuguClient}; every setter of {@link ClientOptions.BaseBuilder} is available. */
    public static final class Builder extends ClientOptions.BaseBuilder<Builder> {
        Builder() {
        }

        /** @return the options this builder would create */
        public ClientOptions buildOptions() {
            return super.buildOptions();
        }

        /** @return a new client */
        public YuguClient build() {
            return new YuguClient(buildOptions());
        }
    }
}
