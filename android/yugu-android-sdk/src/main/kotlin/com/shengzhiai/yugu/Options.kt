package com.shengzhiai.yugu

import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * 鉴权方式，二选一。
 *
 * - [Token]：`Authorization: Bearer <jwt>`，实时评测握手用 `?token=`。
 * - [Signature]：appKey 加 secretKey 签名，REST 走 `X-App-Key`，`X-Timestamp`，`X-Nonce`，`X-Signature`
 *   请求头，实时评测握手走 query 参数。
 */
public sealed class Auth {
    /** JWT 鉴权。 */
    public class Token(public val token: String) : Auth() {
        init {
            require(token.isNotBlank()) { "token 不能为空" }
        }

        override fun toString(): String = "Auth.Token(***)"
    }

    /** appKey 加 secretKey 签名鉴权。secretKey 只用于本地计算签名，不会发送也不会写日志。 */
    public class Signature(public val appKey: String, public val secretKey: String) : Auth() {
        init {
            require(appKey.isNotBlank()) { "appKey 不能为空" }
            require(secretKey.isNotBlank()) { "secretKey 不能为空" }
        }

        override fun toString(): String = "Auth.Signature(appKey=${maskAppKey(appKey)})"
    }

    public companion object {
        /** JWT 鉴权。 */
        @JvmStatic
        public fun token(token: String): Auth = Token(token)

        /** appKey 加 secretKey 签名鉴权。 */
        @JvmStatic
        public fun appKey(appKey: String, secretKey: String): Auth = Signature(appKey, secretKey)

        internal fun maskAppKey(appKey: String): String = appKey.take(4) + "***"
    }
}

/**
 * REST 重试策略。第 n 次重试前的等待时长：
 *
 * ```
 * delay(n) = min(maxDelayMs, initialDelayMs * multiplier^(n-1)) * (1 + U(-jitter, +jitter))
 * ```
 *
 * 响应带 `Retry-After` 且 [respectRetryAfter] 为 true 时取
 * `max(delay, min(retryAfterMs, maxRetryAfterMs))`。默认值给出约 200 ms 与 400 ms 两次重试，各自上下浮动 30%。
 */
public data class RetryPolicy @JvmOverloads constructor(
    /** 最多重试次数，不含首次请求。默认 2，即最多 3 次尝试。 */
    val maxRetries: Int = 2,
    val initialDelayMs: Long = 200,
    val multiplier: Double = 2.0,
    val maxDelayMs: Long = 4000,
    /** 抖动比例，0.3 表示上下浮动 30%。 */
    val jitter: Double = 0.3,
    val respectRetryAfter: Boolean = true,
    val maxRetryAfterMs: Long = 30_000,
) {
    init {
        require(maxRetries >= 0) { "maxRetries 不能为负数" }
        require(initialDelayMs >= 0 && maxDelayMs >= 0) { "等待时长不能为负数" }
        require(multiplier >= 1.0) { "multiplier 不能小于 1" }
        require(jitter in 0.0..1.0) { "jitter 取值 0 到 1" }
        require(maxRetryAfterMs >= 0) { "maxRetryAfterMs 不能为负数" }
    }

    /** 第 [retry] 次重试的基础等待时长，不含抖动，[retry] 从 1 开始。 */
    public fun baseDelayMs(retry: Int): Long = Backoff.base(retry, initialDelayMs, multiplier, maxDelayMs)

    /** 第 [retry] 次重试的等待时长，含抖动与 `Retry-After`。 */
    @JvmOverloads
    public fun delayMs(retry: Int, random: Random, retryAfterMs: Long? = null): Long {
        var d = Backoff.jittered(baseDelayMs(retry), jitter, random)
        if (respectRetryAfter && retryAfterMs != null && retryAfterMs > 0) {
            d = maxOf(d, min(retryAfterMs, maxRetryAfterMs))
        }
        return d
    }

    public companion object {
        /** 默认策略。 */
        @JvmField
        public val DEFAULT: RetryPolicy = RetryPolicy()

        /** 不重试。 */
        @JvmField
        public val NONE: RetryPolicy = RetryPolicy(maxRetries = 0)
    }
}

/**
 * 实时评测的自动重连策略。等待时长公式与 [RetryPolicy] 相同，默认依次等待约 0.5，1，2，4，4，4，4，4 秒，
 * 合计约 23 秒，可以扛过 10 秒左右的断网。
 *
 * [maxAttempts] 为连续重连失败的次数上限，计满后会话以 90006 失败，重连成功后重新计数。
 * 为保证会话一定结束，一次会话的重连总次数另有上限，为 [maxAttempts] 的 3 倍，默认 24 次。
 */
public data class ReconnectPolicy @JvmOverloads constructor(
    val enabled: Boolean = true,
    val maxAttempts: Int = 8,
    val initialDelayMs: Long = 500,
    val multiplier: Double = 2.0,
    val maxDelayMs: Long = 4000,
    val jitter: Double = 0.3,
) {
    init {
        require(maxAttempts >= 0) { "maxAttempts 不能为负数" }
        require(initialDelayMs >= 0 && maxDelayMs >= 0) { "等待时长不能为负数" }
        require(multiplier >= 1.0) { "multiplier 不能小于 1" }
        require(jitter in 0.0..1.0) { "jitter 取值 0 到 1" }
    }

    /** 第 [attempt] 次重连的基础等待时长，不含抖动。 */
    public fun baseDelayMs(attempt: Int): Long = Backoff.base(attempt, initialDelayMs, multiplier, maxDelayMs)

    /** 第 [attempt] 次重连的等待时长，含抖动。 */
    public fun delayMs(attempt: Int, random: Random): Long = Backoff.jittered(baseDelayMs(attempt), jitter, random)

    /** 一次会话内重连总次数的上限，为 [maxAttempts] 的 3 倍。 */
    public val maxTotalAttempts: Int get() = maxAttempts * 3

    public companion object {
        /** 默认策略。 */
        @JvmField
        public val DEFAULT: ReconnectPolicy = ReconnectPolicy()

        /** 关闭自动重连。 */
        @JvmField
        public val DISABLED: ReconnectPolicy = ReconnectPolicy(enabled = false)
    }
}

internal object Backoff {
    fun base(n: Int, initial: Long, multiplier: Double, max: Long): Long {
        require(n >= 1) { "retry index starts at 1" }
        val raw = initial.toDouble() * multiplier.pow((n - 1).toDouble())
        return if (raw >= max.toDouble()) max else raw.roundToLong()
    }

    /** base * (1 + U(-jitter, +jitter)) */
    fun jittered(base: Long, jitter: Double, random: Random): Long {
        if (jitter == 0.0 || base == 0L) return base
        val u = (random.nextDouble() * 2.0 - 1.0) * jitter
        return (base * (1.0 + u)).roundToLong().coerceAtLeast(0)
    }
}

/** 实时评测断线期间音频的处理方式。 */
public enum class AudioBufferPolicy {
    /**
     * 默认。保留本次会话已发送的全部音频，上限 10 MB。重连后用同一组参数与同一幂等键开新会话，
     * 重发开始帧，重放缓冲，补发断线期间排队的音频，已调用过 `end()` 时再发结束帧。
     * 缓冲超过上限后，下一次需要重连时会话以 90008 失败。
     */
    REPLAY,

    /** 不缓冲。断线期间送入的音频丢弃，新会话只评测重连后送入的音频。 */
    DROP,

    /** 不重连。传输层故障直接进入 FAILED。 */
    FAIL,
}

/** 本地音频预检方式。 */
public enum class AudioPrecheckMode {
    /** 不预检。 */
    OFF,

    /** 预检结果只作为警告写进结果的 `localWarnings`。 */
    WARN,

    /** 时长过短，过长，全程静音，格式不支持时在上传前抛 [AudioQualityException]，音量过低仍只警告。 */
    REJECT,
}

/** 日志级别，默认 [WARN]。 */
public enum class LogLevel {
    OFF, ERROR, WARN, INFO, DEBUG;

    internal fun allows(level: LogLevel): Boolean = this != OFF && level != OFF && level.ordinal <= ordinal
}

/**
 * 取消令牌。传给 [RequestOptions] 后，在任何线程调用 [cancel] 都会中止对应调用，
 * 调用方得到 [RequestCancelledException]，错误码 90003。
 */
public class CancellationToken {
    private val lock = Any()
    private val latch = CountDownLatch(1)
    private val listeners = ArrayList<() -> Unit>()

    /** 是否已取消。 */
    public val isCancelled: Boolean get() = latch.count == 0L

    /** 取消，可重复调用。 */
    public fun cancel() {
        val toRun: List<() -> Unit>
        synchronized(lock) {
            if (latch.count == 0L) return
            latch.countDown()
            toRun = ArrayList(listeners)
            listeners.clear()
        }
        for (l in toRun) {
            try {
                l()
            } catch (_: Throwable) {
                // listeners are internal and must not break cancellation
            }
        }
    }

    internal fun register(listener: () -> Unit): () -> Unit {
        var runNow = false
        synchronized(lock) {
            if (latch.count == 0L) runNow = true else listeners.add(listener)
        }
        if (runNow) listener()
        return { synchronized(lock) { listeners.remove(listener) } }
    }

    /** Waits up to [ms]. Returns true when cancelled before or during the wait. */
    internal fun await(ms: Long): Boolean = if (ms <= 0) isCancelled else latch.await(ms, TimeUnit.MILLISECONDS)
}

/**
 * 单次 REST 调用的选项。
 *
 * - [idempotencyKey]：调用方指定的幂等键，须匹配 `^[\x21-\x7E]{1,200}$`，否则在发请求前抛 90010。
 *   不指定时 SDK 自动生成 32 位小写十六进制键，同一次调用的全部重试复用同一个键。
 * - [totalTimeoutMs]：覆盖客户端的总时限，含全部重试与等待。
 * - [readTimeoutMs]：覆盖客户端的单次读取超时。
 * - [retryPolicy]：覆盖客户端的重试策略。
 * - [cancellationToken]：取消令牌。
 */
public class RequestOptions @JvmOverloads constructor(
    public val idempotencyKey: String? = null,
    public val totalTimeoutMs: Long? = null,
    public val readTimeoutMs: Long? = null,
    public val retryPolicy: RetryPolicy? = null,
    public val cancellationToken: CancellationToken? = null,
) {
    public companion object {
        /** 全部取客户端默认值。 */
        @JvmField
        public val DEFAULT: RequestOptions = RequestOptions()
    }
}

/**
 * 单个实时评测会话的选项，未指定的项取客户端默认值。
 *
 * [extraQuery] 为额外的握手 query 参数，会一并参与签名。
 */
public class SessionOptions @JvmOverloads constructor(
    public val idempotencyKey: String? = null,
    public val reconnectPolicy: ReconnectPolicy? = null,
    public val audioBufferPolicy: AudioBufferPolicy? = null,
    public val resultTimeoutMs: Long? = null,
    public val extraQuery: Map<String, String> = emptyMap(),
) {
    public companion object {
        /** 全部取客户端默认值。 */
        @JvmField
        public val DEFAULT: SessionOptions = SessionOptions()
    }
}
