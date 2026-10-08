package com.shengzhiai.yugu

import com.shengzhiai.yugu.audio.AudioPrecheck
import com.shengzhiai.yugu.internal.ClientConfig
import com.shengzhiai.yugu.internal.HttpEngine
import com.shengzhiai.yugu.internal.HttpRequestSpec
import com.shengzhiai.yugu.internal.HttpResponseData
import com.shengzhiai.yugu.internal.IdempotencyKeys
import com.shengzhiai.yugu.internal.JObj
import com.shengzhiai.yugu.internal.Json
import com.shengzhiai.yugu.internal.Lifecycle
import com.shengzhiai.yugu.internal.Platform
import com.shengzhiai.yugu.internal.SerialExecutor
import com.shengzhiai.yugu.internal.Shared
import com.shengzhiai.yugu.stream.WsSession
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Random
import java.util.concurrent.Executor
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * 优谷雅言语音评测客户端。
 *
 * 能力：整段评测 [evaluate]，声通兼容整段评测 [evaluateCompat]，语音合成 [tts]，报告查询 [getReport]，
 * 原生实时评测 [streamEvaluate]，声通兼容实时评测 [streamEvaluateCompat]。
 *
 * REST 方法为阻塞调用，不能在主线程调用，可在后台线程调用，或改用对应的 `Async` 方法。
 * 客户端线程安全，建议整个应用共用一个实例，不再使用时调用 [close]。
 *
 * ```
 * val client = YuguClient.builder()
 *     .auth(Auth.appKey("ak_xxx", "sk_xxx"))
 *     .build()
 * ```
 */
public class YuguClient private constructor(builder: Builder) : Closeable {

    private val cfg: ClientConfig
    private val log: Log
    private val lifecycle = Lifecycle()
    private val restClient: OkHttpClient
    private val wsClient: OkHttpClient
    private val engine: HttpEngine

    init {
        val auth = builder.auth ?: throw invalidArgument("auth 必填：Auth.appKey(appKey, secretKey) 或 Auth.token(jwt)")
        val baseUrl = builder.baseUrl.trimEnd('/')
        if (baseUrl.toHttpUrlOrNull() == null) throw invalidArgument("baseUrl 不是合法的 http 或 https 地址：$baseUrl")
        val wsBase = builder.wsBaseUrl.trimEnd('/')
        val wsAsHttp = wsBase.replaceFirst(Regex("^wss://", RegexOption.IGNORE_CASE), "https://")
            .replaceFirst(Regex("^ws://", RegexOption.IGNORE_CASE), "http://")
        if (wsAsHttp == wsBase || wsAsHttp.toHttpUrlOrNull() == null) {
            throw invalidArgument("wsBaseUrl 须以 ws:// 或 wss:// 开头：$wsBase")
        }
        cfg = ClientConfig(
            baseUrl = baseUrl,
            wsBaseUrl = wsBase,
            auth = auth,
            connectTimeoutMs = builder.connectTimeoutMs,
            readTimeoutMs = builder.readTimeoutMs,
            totalTimeoutMs = builder.totalTimeoutMs,
            retryPolicy = builder.retryPolicy,
            autoIdempotencyKey = builder.autoIdempotencyKey,
            eventListener = builder.eventListener,
            audioPrecheck = builder.audioPrecheck,
            strictAudio = builder.strictAudio,
            userAgent = builder.userAgent,
            reconnectPolicy = builder.reconnectPolicy,
            audioBufferPolicy = builder.audioBufferPolicy,
            resultTimeoutMs = builder.resultTimeoutMs,
            pingIntervalMs = builder.pingIntervalMs,
            maxReplayBytes = builder.maxReplayBytes,
            callbackExecutor = builder.callbackExecutor ?: Platform.defaultCallbackExecutor(),
            random = builder.random ?: SecureRandom(),
        )
        log = Log(builder.logLevel, builder.logger ?: LogcatLogger())
        val base = builder.okHttpClient ?: Shared.okHttp
        // retryOnConnectionFailure stays on: it is what lets OkHttp try the next address of a host
        // when connecting to one fails, for example an unreachable IPv6 address before a working
        // IPv4 one. Request bodies are one-shot (HttpEngine), so OkHttp only re-sends a request when
        // nothing was sent yet; every resend after sending started is an SDK retry.
        restClient = base.newBuilder()
            .connectTimeout(cfg.connectTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(cfg.readTimeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(cfg.readTimeoutMs, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build()
        wsClient = base.newBuilder()
            .connectTimeout(cfg.connectTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(cfg.connectTimeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(cfg.connectTimeoutMs, TimeUnit.MILLISECONDS)
            .pingInterval(cfg.pingIntervalMs, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build()
        engine = HttpEngine(cfg, log, restClient, lifecycle)
        log.i("client created base=$baseUrl ws=$wsBase auth=$auth ua=${cfg.userAgent}")
    }

    /** 客户端是否已关闭。 */
    public val isClosed: Boolean get() = lifecycle.closed

    // ------------------------------------------------------------------------- evaluate

    /**
     * 原生整段评测 `POST /api/v1/evaluate`。
     *
     * 请求为 multipart：`audio` 文件段，可选 `image` 文件段，`config` 段不带文件名，
     * `Content-Type: application/json; charset=utf-8`。签名参数为 `{config: config 段原文}`。
     * 自动带 `Idempotency-Key`，重试复用同一个键。
     *
     * @throws AudioQualityException 预检为 REJECT 且音频不合格，或开启 `strictAudio` 且结果带 1001 警告。
     */
    @JvmOverloads
    public fun evaluate(
        config: EvaluateConfig,
        audio: AudioInput,
        options: RequestOptions = RequestOptions.DEFAULT,
        image: ImageInput? = null,
    ): EvalResult {
        lifecycle.ensureOpen()
        config.validate()
        IdempotencyKeys.resolve(options.idempotencyKey, true)
        val warnings = precheck(audio)
        val upload = audio.uploadBytes()
        val configJson = config.toJson()
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("audio", audio.fileName, upload.toRequestBody(audio.contentType().toMediaType()))
            .apply {
                if (image != null) addFormDataPart("image", image.fileName, image.bytes.toRequestBody(image.contentType.toMediaType()))
            }
            .addFormDataPart("config", null, configJson.toRequestBody(JSON_UTF8))
            .build()
        val spec = HttpRequestSpec("evaluate", "POST", "/api/v1/evaluate", body, mapOf("config" to configJson), write = true)
        return engine.execute(spec, options) { resp, key -> parseEval(resp, key, warnings) }
    }

    /** 原生整段评测，音频为字节数组。 */
    @JvmOverloads
    public fun evaluate(config: EvaluateConfig, audio: ByteArray, options: RequestOptions = RequestOptions.DEFAULT): EvalResult =
        evaluate(config, AudioInput.fromBytes(audio), options)

    /** 原生整段评测，音频为文件。 */
    @JvmOverloads
    public fun evaluate(config: EvaluateConfig, audio: File, options: RequestOptions = RequestOptions.DEFAULT): EvalResult =
        evaluate(config, AudioInput.fromFile(audio), options)

    /** 原生整段评测，音频为输入流，流由调用方关闭。 */
    @JvmOverloads
    public fun evaluate(config: EvaluateConfig, audio: InputStream, options: RequestOptions = RequestOptions.DEFAULT): EvalResult =
        evaluate(config, AudioInput.fromStream(audio), options)

    /**
     * 声通兼容整段评测 `POST /{coreType}`。表单字段为 `refText`，`language`，`refPinyin` 与 [CompatConfig.params]，
     * 签名参数为实际发送的文本字段，音频文件段不参与签名。
     */
    @JvmOverloads
    public fun evaluateCompat(config: CompatConfig, audio: AudioInput, options: RequestOptions = RequestOptions.DEFAULT): EvalResult {
        lifecycle.ensureOpen()
        config.validate()
        IdempotencyKeys.resolve(options.idempotencyKey, true)
        val warnings = precheck(audio)
        val fields = config.formFields()
        val upload = audio.uploadBytes()
        val mb = MultipartBody.Builder().setType(MultipartBody.FORM)
        mb.addFormDataPart("audio", audio.fileName, upload.toRequestBody(audio.contentType().toMediaType()))
        for ((k, v) in fields) mb.addFormDataPart(k, v)
        val spec = HttpRequestSpec("evaluateCompat", "POST", "/" + config.coreType, mb.build(), fields, write = true)
        return engine.execute(spec, options) { resp, key -> parseEval(resp, key, warnings) }
    }

    /** 声通兼容整段评测，音频为字节数组。 */
    @JvmOverloads
    public fun evaluateCompat(config: CompatConfig, audio: ByteArray, options: RequestOptions = RequestOptions.DEFAULT): EvalResult =
        evaluateCompat(config, AudioInput.fromBytes(audio), options)

    // ------------------------------------------------------------------------- tts and report

    /**
     * 语音合成 `POST /api/v1/tts/generate`。签名参数为请求体顶层非空标量字段，
     * 值与请求体里的文本一致。自动带 `Idempotency-Key`。
     */
    @JvmOverloads
    public fun tts(request: TtsRequest, options: RequestOptions = RequestOptions.DEFAULT): TtsResult {
        lifecycle.ensureOpen()
        request.validate()
        val bodyMap = request.body()
        val json = Json.write(bodyMap)
        val signParams = LinkedHashMap<String, String>()
        for ((k, v) in bodyMap) {
            when (v) {
                is String -> signParams[k] = v
                is Number -> signParams[k] = Json.formatNumber(v)
                is Boolean -> signParams[k] = v.toString()
            }
        }
        val spec = HttpRequestSpec("tts", "POST", "/api/v1/tts/generate", json.toRequestBody(JSON_UTF8), signParams, write = true)
        return engine.execute(spec, options) { resp, key ->
            val data = envelopeData(resp)
            val audioUrl = data.str("audioUrl") ?: ""
            TtsResult(
                audioUrl = audioUrl,
                resolvedUrl = resolveTtsUrl(audioUrl),
                duration = data.str("duration"),
                durationSeconds = data.double("duration"),
                format = data.str("format"),
                idempotencyKey = key,
                replayed = resp.replayed,
                raw = data.map,
                rawJson = resp.body,
            )
        }
    }

    /** 报告查询 `GET /api/v1/report/{recordId}`。签名参数为空集，可重试，不带幂等键。 */
    @JvmOverloads
    public fun getReport(recordId: String, options: RequestOptions = RequestOptions.DEFAULT): ReportResult {
        lifecycle.ensureOpen()
        if (recordId.isBlank()) throw invalidArgument("recordId 不能为空")
        val path = "/api/v1/report/" + URLEncoder.encode(recordId, "UTF-8").replace("+", "%20")
        val spec = HttpRequestSpec("getReport", "GET", path, null, emptyMap(), write = false)
        return engine.execute(spec, options) { resp, _ ->
            val data = envelopeData(resp)
            ReportResult(recordId, data.double("overall"), data.map, resp.body)
        }
    }

    // ------------------------------------------------------------------------- streaming

    /**
     * 原生实时评测 `WS /api/v1/ws/evaluate`。连接后自动发送开始帧
     * `{"cmd":"start", ...config, "idempotencyKey": ...}`，音频以二进制帧发送。
     * 返回时会话已开始连接，状态变化与结果通过 [listener] 回调。
     */
    @JvmOverloads
    public fun streamEvaluate(
        config: EvaluateConfig,
        listener: StreamListener,
        options: SessionOptions = SessionOptions.DEFAULT,
    ): StreamSession {
        lifecycle.ensureOpen()
        config.validate()
        val frame = LinkedHashMap<String, Any?>()
        frame["cmd"] = "start"
        frame.putAll(config.toMap())
        return openSession("/api/v1/ws/evaluate", frame, listener, options)
    }

    /**
     * 声通兼容实时评测 `WS /{coreType}`。连接后自动发送参数帧
     * `{"refText": ..., "language": ..., "idempotencyKey": ...}`。
     */
    @JvmOverloads
    public fun streamEvaluateCompat(
        config: CompatConfig,
        listener: StreamListener,
        options: SessionOptions = SessionOptions.DEFAULT,
    ): StreamSession {
        lifecycle.ensureOpen()
        config.validate()
        return openSession("/" + config.coreType, config.paramFrame(), listener, options)
    }

    private fun openSession(path: String, frame: Map<String, Any?>, listener: StreamListener, options: SessionOptions): StreamSession {
        val key = IdempotencyKeys.resolve(options.idempotencyKey, cfg.autoIdempotencyKey)
        options.resultTimeoutMs?.let { if (it <= 0) throw invalidArgument("resultTimeoutMs 必须为正数") }
        val session = WsSession(
            cfg = cfg,
            log = log,
            wsClient = wsClient,
            lifecycle = lifecycle,
            path = path,
            startFrame = frame,
            listener = listener,
            idempotencyKey = key,
            reconnect = options.reconnectPolicy ?: cfg.reconnectPolicy,
            bufferPolicy = options.audioBufferPolicy ?: cfg.audioBufferPolicy,
            resultTimeoutMs = options.resultTimeoutMs ?: cfg.resultTimeoutMs,
            extraQuery = options.extraQuery,
        )
        session.start()
        return session
    }

    // ------------------------------------------------------------------------- async API

    /** [evaluate] 的异步版本，回调在 `callbackExecutor` 上执行。 */
    @JvmOverloads
    public fun evaluateAsync(
        config: EvaluateConfig,
        audio: AudioInput,
        callback: YuguCallback<EvalResult>,
        options: RequestOptions = RequestOptions.DEFAULT,
    ): YuguCall = async(callback, options) { evaluate(config, audio, it) }

    /** [evaluateCompat] 的异步版本。 */
    @JvmOverloads
    public fun evaluateCompatAsync(
        config: CompatConfig,
        audio: AudioInput,
        callback: YuguCallback<EvalResult>,
        options: RequestOptions = RequestOptions.DEFAULT,
    ): YuguCall = async(callback, options) { evaluateCompat(config, audio, it) }

    /** [tts] 的异步版本。 */
    @JvmOverloads
    public fun ttsAsync(request: TtsRequest, callback: YuguCallback<TtsResult>, options: RequestOptions = RequestOptions.DEFAULT): YuguCall =
        async(callback, options) { tts(request, it) }

    /** [getReport] 的异步版本。 */
    @JvmOverloads
    public fun getReportAsync(recordId: String, callback: YuguCallback<ReportResult>, options: RequestOptions = RequestOptions.DEFAULT): YuguCall =
        async(callback, options) { getReport(recordId, it) }

    private fun <T> async(callback: YuguCallback<T>, options: RequestOptions, block: (RequestOptions) -> T): YuguCall {
        lifecycle.ensureOpen()
        val token = options.cancellationToken ?: CancellationToken()
        val opts = RequestOptions(options.idempotencyKey, options.totalTimeoutMs, options.readTimeoutMs, options.retryPolicy, token)
        val deliver = SerialExecutor(cfg.callbackExecutor)
        val call = AsyncCall(token)
        call.future = Shared.asyncExecutor.submit {
            val outcome: () -> Unit = try {
                val value = block(opts)
                val r: () -> Unit = { callback.onSuccess(value) }
                r
            } catch (e: YuguException) {
                val r: () -> Unit = { callback.onFailure(e) }
                r
            } catch (e: RuntimeException) {
                val wrapped = YuguException(ErrorCategory.UNKNOWN, 0, 0, e.toString(), false, cause = e)
                val r: () -> Unit = { callback.onFailure(wrapped) }
                r
            }
            call.done = true
            deliver.execute {
                try {
                    outcome()
                } catch (t: Throwable) {
                    log.e("异步回调抛出异常，已忽略", t)
                }
            }
        }
        return call
    }

    private class AsyncCall(private val token: CancellationToken) : YuguCall {
        @Volatile
        var future: Future<*>? = null

        @Volatile
        var done = false

        override fun cancel() = token.cancel()
        override val isCancelled: Boolean get() = token.isCancelled
        override val isDone: Boolean get() = done
    }

    // ------------------------------------------------------------------------- lifecycle

    /**
     * 关闭客户端，可重复调用。会取消进行中的实时会话与请求，此后再调用任何方法抛
     * [IllegalSessionStateException]，错误码 90004。
     */
    override fun close() {
        if (lifecycle.close()) log.i("client closed")
    }

    // ------------------------------------------------------------------------- helpers

    private fun precheck(audio: AudioInput): List<LocalWarning> {
        if (cfg.audioPrecheck == AudioPrecheckMode.OFF) return emptyList()
        val findings = AudioPrecheck.checkUpload(audio.bytes(), audio.isRawPcm, audio.pcmSampleRate, audio.pcmChannels)
        if (cfg.audioPrecheck == AudioPrecheckMode.REJECT) {
            findings.firstOrNull { AudioPrecheck.rejectable(it.code) }?.let { throw YuguErrors.local(it.code, it.message) }
        }
        for (f in findings) log.w("音频预检：${f.code} ${f.message}")
        return findings
    }

    private fun parseEval(resp: HttpResponseData, key: String?, localWarnings: List<LocalWarning>): EvalResult {
        val obj = JObj.parseOrNull(resp.body)
            ?: throw YuguErrors.local(90005, "评测响应不是 JSON 对象", null).also { it.withContext(key, 0) }
        if (!obj.has("result") && !obj.has("recordId") && (obj.int("code") ?: 0) != 0) {
            throw YuguErrors.fromHttp(resp.status, resp.body)
        }
        val result = ResultParser.evalResult(obj, resp.body, key, resp.replayed, localWarnings)
        if (cfg.strictAudio && result.hasWarning(WarningCode.NO_VALID_AUDIO)) {
            val w = result.warnings.first { it.code == WarningCode.NO_VALID_AUDIO.code }
            throw YuguErrors.fromWarning(w.code, w.message).also { it.recordId = result.recordId }
        }
        return result
    }

    private fun envelopeData(resp: HttpResponseData): JObj {
        val obj = JObj.parseOrNull(resp.body) ?: throw YuguErrors.local(90005, "响应不是 JSON 对象")
        val code = obj.int("code") ?: 0
        if (code != 0) throw YuguErrors.fromHttp(resp.status, resp.body)
        return obj.obj("data") ?: JObj(emptyMap())
    }

    private fun resolveTtsUrl(audioUrl: String): String = when {
        audioUrl.isEmpty() -> audioUrl
        audioUrl.startsWith("http://") || audioUrl.startsWith("https://") -> audioUrl
        audioUrl.startsWith("/audio/") -> cfg.baseUrl + "/tts" + audioUrl
        audioUrl.startsWith("/") -> cfg.baseUrl + audioUrl
        else -> cfg.baseUrl + "/" + audioUrl
    }

    internal fun activeSessionCount(): Int = lifecycle.activeSessions()

    internal fun activeCallCount(): Int = lifecycle.activeCalls()

    /**
     * 客户端构建器，各项默认值见 README 的客户端选项一节。
     */
    public class Builder {
        internal var baseUrl: String = DEFAULT_BASE_URL
        internal var wsBaseUrl: String = DEFAULT_WS_BASE_URL
        internal var auth: Auth? = null
        internal var connectTimeoutMs: Long = 10_000
        internal var readTimeoutMs: Long = 120_000
        internal var totalTimeoutMs: Long = 300_000
        internal var retryPolicy: RetryPolicy = RetryPolicy.DEFAULT
        internal var autoIdempotencyKey: Boolean = true
        internal var logLevel: LogLevel = LogLevel.WARN
        internal var logger: YuguLogger? = null
        internal var eventListener: YuguEventListener = YuguEventListener.NONE
        internal var audioPrecheck: AudioPrecheckMode = AudioPrecheckMode.WARN
        internal var strictAudio: Boolean = false
        internal var userAgent: String = YuguVersion.USER_AGENT
        internal var reconnectPolicy: ReconnectPolicy = ReconnectPolicy.DEFAULT
        internal var audioBufferPolicy: AudioBufferPolicy = AudioBufferPolicy.REPLAY
        internal var resultTimeoutMs: Long = 300_000
        internal var pingIntervalMs: Long = 15_000
        internal var maxReplayBytes: Long = 10L * 1024 * 1024
        internal var callbackExecutor: Executor? = null
        internal var okHttpClient: OkHttpClient? = null
        internal var random: Random? = null

        /** REST 基址，默认 `https://open.shengzhiai.com`。 */
        public fun baseUrl(url: String): Builder = apply { baseUrl = url }

        /** 实时评测基址，默认 `wss://open.shengzhiai.com`。 */
        public fun wsBaseUrl(url: String): Builder = apply { wsBaseUrl = url }

        /** 鉴权方式，必填。 */
        public fun auth(auth: Auth): Builder = apply { this.auth = auth }

        /**
         * 每个地址的 TCP 加 TLS 建连超时，默认 10000 毫秒。一个地址连不上时改连域名的下一个地址。
         * 也是实时评测连接打开后等待开始帧的时限，实时评测建连与升级以 3 倍为上限。
         */
        public fun connectTimeoutMs(ms: Long): Builder = apply { connectTimeoutMs = positive(ms, "connectTimeoutMs") }

        /** 单次 REST 尝试的读取超时，默认 120000 毫秒。 */
        public fun readTimeoutMs(ms: Long): Builder = apply { readTimeoutMs = positive(ms, "readTimeoutMs") }

        /** 一次逻辑调用含全部重试与等待的总时限，默认 300000 毫秒。 */
        public fun totalTimeoutMs(ms: Long): Builder = apply { totalTimeoutMs = positive(ms, "totalTimeoutMs") }

        /** REST 重试策略。 */
        public fun retryPolicy(policy: RetryPolicy): Builder = apply { retryPolicy = policy }

        /** 调用方未指定幂等键时是否自动生成，默认 true。关闭后未带键的写请求不重试，实时会话不重连。 */
        public fun autoIdempotencyKey(enabled: Boolean): Builder = apply { autoIdempotencyKey = enabled }

        /** 日志级别，默认 WARN。 */
        public fun logLevel(level: LogLevel): Builder = apply { logLevel = level }

        /** 日志输出，默认写 Logcat。 */
        public fun logger(logger: YuguLogger): Builder = apply { this.logger = logger }

        /** 指标回调。 */
        public fun eventListener(listener: YuguEventListener): Builder = apply { eventListener = listener }

        /** 本地音频预检方式，默认 WARN。 */
        public fun audioPrecheck(mode: AudioPrecheckMode): Builder = apply { audioPrecheck = mode }

        /** 为 true 时整段评测结果带 1001 警告即抛 [AudioQualityException]，默认 false。 */
        public fun strictAudio(enabled: Boolean): Builder = apply { strictAudio = enabled }

        /** `User-Agent`，默认 `yugu-android-sdk/2.0.0`。 */
        public fun userAgent(ua: String): Builder = apply { userAgent = ua }

        /** 实时评测重连策略。 */
        public fun reconnectPolicy(policy: ReconnectPolicy): Builder = apply { reconnectPolicy = policy }

        /** 实时评测断线期间的音频处理方式，默认 REPLAY。 */
        public fun audioBufferPolicy(policy: AudioBufferPolicy): Builder = apply { audioBufferPolicy = policy }

        /** 调用 `end()` 后等待终评的时限，默认 300000 毫秒。 */
        public fun resultTimeoutMs(ms: Long): Builder = apply { resultTimeoutMs = positive(ms, "resultTimeoutMs") }

        /** 实时评测心跳间隔，默认 15000 毫秒。 */
        public fun pingIntervalMs(ms: Long): Builder = apply { pingIntervalMs = positive(ms, "pingIntervalMs") }

        /** REPLAY 重放缓冲上限，默认 10 MB。 */
        public fun maxReplayBytes(bytes: Long): Builder = apply { maxReplayBytes = positive(bytes, "maxReplayBytes") }

        /** 回调线程，默认主线程。 */
        public fun callbackExecutor(executor: Executor): Builder = apply { callbackExecutor = executor }

        /** 自定义 OkHttpClient，例如代理与证书设置。SDK 基于它派生自己的超时设置，不会关闭它。 */
        public fun okHttpClient(client: OkHttpClient): Builder = apply { okHttpClient = client }

        /** 抖动用的随机数源，测试时可传入固定种子。 */
        public fun random(random: Random): Builder = apply { this.random = random }

        /** 创建客户端。 */
        public fun build(): YuguClient = YuguClient(this)

        private fun positive(v: Long, name: String): Long {
            if (v <= 0) throw invalidArgument("$name 必须为正数")
            return v
        }
    }

    public companion object {
        /** 默认 REST 基址。 */
        public const val DEFAULT_BASE_URL: String = "https://open.shengzhiai.com"

        /** 默认实时评测基址。 */
        public const val DEFAULT_WS_BASE_URL: String = "wss://open.shengzhiai.com"

        private val JSON_UTF8 = "application/json; charset=utf-8".toMediaType()

        /** 创建构建器。 */
        @JvmStatic
        public fun builder(): Builder = Builder()
    }
}

/** 异步调用的回调，在客户端的 `callbackExecutor` 上执行。 */
public interface YuguCallback<T> {
    public fun onSuccess(result: T)
    public fun onFailure(error: YuguException)
}

/** 异步调用句柄。 */
public interface YuguCall {
    /** 取消，可重复调用。取消后回调 `onFailure`，错误码 90003。 */
    public fun cancel()
    public val isCancelled: Boolean
    public val isDone: Boolean
}
