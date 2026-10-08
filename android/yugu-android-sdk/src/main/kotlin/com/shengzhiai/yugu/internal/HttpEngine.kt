package com.shengzhiai.yugu.internal

import com.shengzhiai.yugu.AudioBufferPolicy
import com.shengzhiai.yugu.AudioPrecheckMode
import com.shengzhiai.yugu.Auth
import com.shengzhiai.yugu.CancellationToken
import com.shengzhiai.yugu.Log
import com.shengzhiai.yugu.ReconnectPolicy
import com.shengzhiai.yugu.RequestOptions
import com.shengzhiai.yugu.RetryPolicy
import com.shengzhiai.yugu.Signer
import com.shengzhiai.yugu.YuguErrors
import com.shengzhiai.yugu.YuguEventListener
import com.shengzhiai.yugu.YuguException
import okhttp3.Call
import okhttp3.Headers
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Locale
import java.util.Random
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/** Resolved client settings (DESIGN 2.1). */
internal class ClientConfig(
    val baseUrl: String,
    val wsBaseUrl: String,
    val auth: Auth,
    val connectTimeoutMs: Long,
    val readTimeoutMs: Long,
    val totalTimeoutMs: Long,
    val retryPolicy: RetryPolicy,
    val autoIdempotencyKey: Boolean,
    val eventListener: YuguEventListener,
    val audioPrecheck: AudioPrecheckMode,
    val strictAudio: Boolean,
    val userAgent: String,
    val reconnectPolicy: ReconnectPolicy,
    val audioBufferPolicy: AudioBufferPolicy,
    val resultTimeoutMs: Long,
    val pingIntervalMs: Long,
    val maxReplayBytes: Long,
    val callbackExecutor: Executor,
    val random: Random,
)

/** Idempotency key rules (DESIGN 2.2). */
internal object IdempotencyKeys {
    private val ALLOWED = Regex("^[\\x21-\\x7E]{1,200}$")

    fun generate(): String = UUID.randomUUID().toString().replace("-", "")

    fun isValid(key: String): Boolean = ALLOWED.matches(key)

    /** Caller key validated before any I/O, or a generated key, or null when auto keys are off. */
    fun resolve(callerKey: String?, auto: Boolean): String? {
        if (callerKey != null) {
            if (!isValid(callerKey)) {
                throw YuguErrors.local(90010, "幂等键须为 1 到 200 个可见 ASCII 字符")
            }
            return callerKey
        }
        return if (auto) generate() else null
    }
}

/**
 * Tracks what a client owns so close() can cancel it: in-flight REST calls and open sessions.
 */
internal class Lifecycle {
    @Volatile
    var closed = false
        private set
    private val calls = Collections.newSetFromMap(IdentityHashMap<CallToken, Boolean>())
    private val sessions = Collections.newSetFromMap(IdentityHashMap<Cancellable, Boolean>())

    interface Cancellable {
        fun cancel()
    }

    fun ensureOpen() {
        if (closed) throw YuguErrors.local(90004)
    }

    @Synchronized
    fun register(token: CallToken) {
        if (closed) throw YuguErrors.local(90004)
        calls.add(token)
    }

    @Synchronized
    fun unregister(token: CallToken) {
        calls.remove(token)
    }

    @Synchronized
    fun register(s: Cancellable) {
        if (closed) throw YuguErrors.local(90004)
        sessions.add(s)
    }

    @Synchronized
    fun unregister(s: Cancellable) {
        sessions.remove(s)
    }

    @Synchronized
    fun activeSessions(): Int = sessions.size

    @Synchronized
    fun activeCalls(): Int = calls.size

    /** Returns false when already closed. */
    fun close(): Boolean {
        val toCancelCalls: List<CallToken>
        val toCancelSessions: List<Cancellable>
        synchronized(this) {
            if (closed) return false
            closed = true
            toCancelCalls = ArrayList(calls)
            toCancelSessions = ArrayList(sessions)
            calls.clear()
            sessions.clear()
        }
        for (s in toCancelSessions) s.cancel()
        for (c in toCancelCalls) c.cancel(CallToken.Reason.CLIENT_CLOSED)
        return true
    }
}

/** Cancellation state of one logical REST call: caller token plus client close. */
internal class CallToken(userToken: CancellationToken?) {
    enum class Reason { USER, CLIENT_CLOSED }

    private val signal = CancellationToken()

    @Volatile
    var reason: Reason? = null
        private set

    @Volatile
    private var call: Call? = null
    private val unregisterUser: (() -> Unit)? = userToken?.register { cancel(Reason.USER) }

    val cancelled: Boolean get() = reason != null

    fun cancel(r: Reason) {
        if (reason == null) reason = r
        call?.cancel()
        signal.cancel()
    }

    fun attach(c: Call) {
        call = c
        if (cancelled) c.cancel()
    }

    /** Sleeps [ms]; returns true when cancelled meanwhile. */
    fun sleep(ms: Long): Boolean = try {
        signal.await(ms)
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        cancel(Reason.USER)
        true
    }

    fun dispose() {
        unregisterUser?.invoke()
    }

    fun error(cause: Throwable? = null): YuguException = when (reason) {
        Reason.CLIENT_CLOSED -> YuguErrors.local(90004, "客户端已关闭，调用被取消", cause)
        else -> YuguErrors.local(90003, cause = cause)
    }
}

/** One REST request description. */
internal class HttpRequestSpec(
    val op: String,
    val method: String,
    val path: String,
    val body: RequestBody?,
    /** Business parameters that are signed (DESIGN 5.2). */
    val signParams: Map<String, String>,
    /** Write calls carry an Idempotency-Key and are retried only with a key. */
    val write: Boolean,
)

/**
 * Marks a request body one-shot for OkHttp. OkHttp then never re-sends the request on its own once
 * sending started, it only moves on to the next address of the host when connecting failed. Resends
 * after sending started are SDK retries: counted, reported through onRetry, same Idempotency-Key,
 * fresh nonce. The wrapped body itself can be written again by the next SDK attempt.
 */
internal class OneShotBody(private val delegate: RequestBody) : RequestBody() {
    override fun contentType(): MediaType? = delegate.contentType()

    override fun contentLength(): Long = delegate.contentLength()

    override fun writeTo(sink: BufferedSink) = delegate.writeTo(sink)

    override fun isOneShot(): Boolean = true
}

internal class HttpResponseData(val status: Int, val body: String, val headers: Headers) {
    val replayed: Boolean get() = headers["Idempotency-Replayed"].equals("true", ignoreCase = true)
}

/**
 * REST execution with retries (DESIGN 2.3): same idempotency key on every attempt, fresh
 * timestamp and nonce per attempt, backoff with jitter, Retry-After, total deadline.
 */
internal class HttpEngine(
    private val cfg: ClientConfig,
    private val log: Log,
    private val client: OkHttpClient,
    private val lifecycle: Lifecycle,
) {
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L }

    fun <T> execute(spec: HttpRequestSpec, options: RequestOptions, parse: (HttpResponseData, String?) -> T): T {
        lifecycle.ensureOpen()
        if (Platform.isMainThread()) {
            log.w("${spec.op} 是阻塞调用，在主线程调用会被系统拒绝，请在后台线程调用或改用 ${spec.op}Async")
        }
        val policy = options.retryPolicy ?: cfg.retryPolicy
        val totalTimeout = options.totalTimeoutMs ?: cfg.totalTimeoutMs
        val readTimeout = options.readTimeoutMs ?: cfg.readTimeoutMs
        val key = if (spec.write) IdempotencyKeys.resolve(options.idempotencyKey, cfg.autoIdempotencyKey) else null
        val retryAllowed = !spec.write || key != null
        if (!retryAllowed) log.d("${spec.op}: autoIdempotencyKey 已关闭且未指定幂等键，写请求不重试")
        val signature = (cfg.auth as? Auth.Signature)?.let { Signer.sign(spec.signParams, it.secretKey) }
        val http = if (readTimeout == cfg.readTimeoutMs) client else client.newBuilder().readTimeout(readTimeout, TimeUnit.MILLISECONDS).build()

        val token = CallToken(options.cancellationToken)
        lifecycle.register(token)
        val start = clock()
        val deadline = start + totalTimeout
        var attempt = 0
        try {
            while (true) {
                attempt++
                if (token.cancelled) throw finish(spec, token.error(), key, attempt - 1, start)
                val remaining = deadline - clock()
                safeListener { cfg.eventListener.onRequestStart(spec.op, spec.method, spec.path, attempt) }
                log.d { "${spec.op} attempt $attempt ${spec.method} ${spec.path} idempotencyKey=$key" }
                var error: YuguException = try {
                    val resp = executeOnce(http, spec, key, signature, remaining.coerceAtLeast(1), token)
                    if (resp.status in 200..299) {
                        val value = parse(resp, key)
                        safeListener { cfg.eventListener.onRequestEnd(spec.op, resp.status, clock() - start, attempt, null) }
                        log.d { "${spec.op} ok status=${resp.status} attempts=$attempt replayed=${resp.replayed}" }
                        return value
                    }
                    YuguErrors.fromHttp(resp.status, resp.body, retryAfterMs(resp.headers))
                } catch (e: YuguException) {
                    e
                } catch (e: IOException) {
                    YuguErrors.fromIOException(e, cancelled = false)
                }
                if (token.cancelled) {
                    if (error.code != CANCELLED_CODE && error.code != CLOSED_CODE) error = token.error(error)
                    throw finish(spec, error, key, attempt, start)
                }
                error.withContext(key, attempt)
                val canRetry = retryAllowed && attempt <= policy.maxRetries && YuguErrors.isRetryable(error)
                if (!canRetry) throw finish(spec, error, key, attempt, start)
                val delay = policy.delayMs(attempt, cfg.random, error.retryAfterMs)
                if (clock() + delay > deadline) {
                    log.w("${spec.op}: 总时限 ${totalTimeout} ms 内不足以再次重试，停止重试：${describe(error)}")
                    throw finish(spec, error, key, attempt, start)
                }
                log.w("retry $attempt/${policy.maxRetries} in $delay ms: ${describe(error)}")
                safeListener { cfg.eventListener.onRetry(spec.op, attempt, delay, error) }
                if (token.sleep(delay)) throw finish(spec, token.error(error), key, attempt, start)
            }
        } finally {
            token.dispose()
            lifecycle.unregister(token)
        }
    }

    private fun finish(spec: HttpRequestSpec, error: YuguException, key: String?, attempts: Int, start: Long): YuguException {
        error.withContext(key, attempts)
        safeListener { cfg.eventListener.onRequestEnd(spec.op, error.httpStatus, clock() - start, attempts, error) }
        log.i("${spec.op} failed after $attempts attempt(s): $error")
        return error
    }

    private fun executeOnce(
        http: OkHttpClient,
        spec: HttpRequestSpec,
        key: String?,
        signature: String?,
        remainingMs: Long,
        token: CallToken,
    ): HttpResponseData {
        val b = Request.Builder()
            .url(cfg.baseUrl.trimEnd('/') + spec.path)
            .header("User-Agent", cfg.userAgent)
            .header("Accept", "application/json")
        when (val a = cfg.auth) {
            is Auth.Token -> b.header("Authorization", "Bearer " + a.token)
            is Auth.Signature -> {
                b.header("X-App-Key", a.appKey)
                b.header("X-Timestamp", (System.currentTimeMillis() / 1000).toString())
                b.header("X-Nonce", IdempotencyKeys.generate())
                b.header("X-Signature", signature ?: "")
            }
        }
        if (key != null) b.header("Idempotency-Key", key)
        if (spec.method == "GET") b.get() else b.method(spec.method, spec.body?.let { OneShotBody(it) })
        val call = http.newCall(b.build())
        call.timeout().timeout(remainingMs, TimeUnit.MILLISECONDS)
        token.attach(call)
        call.execute().use { resp ->
            val text = resp.body?.string() ?: ""
            return HttpResponseData(resp.code, text, resp.headers)
        }
    }

    private fun safeListener(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            log.w("eventListener 抛出异常，已忽略", t)
        }
    }

    companion object {
        private const val CANCELLED_CODE = 90003
        private const val CLOSED_CODE = 90004

        fun describe(e: YuguException): String =
            if (e.httpStatus > 0) "HTTP ${e.httpStatus} code=${e.code}" else "${e.category} code=${e.code}"

        /** Retry-After as delta seconds (decimals allowed) or HTTP-date, in milliseconds. */
        fun retryAfterMs(headers: Headers): Long? {
            val v = headers["Retry-After"]?.trim() ?: return null
            v.toDoubleOrNull()?.let { return if (it >= 0) (it * 1000).toLong() else null }
            return try {
                val fmt = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
                val at = fmt.parse(v)?.time ?: return null
                (at - System.currentTimeMillis()).coerceAtLeast(0)
            } catch (e: Exception) {
                null
            }
        }
    }
}
