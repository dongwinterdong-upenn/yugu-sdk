package com.shengzhiai.yugu

import com.shengzhiai.yugu.errors.ErrorTable
import com.shengzhiai.yugu.internal.JObj
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.security.cert.CertificateException
import javax.net.ssl.SSLPeerUnverifiedException

/** 错误类别，与 `spec/errors.json` 的 `categories` 一致。 */
public enum class ErrorCategory {
    NETWORK, TIMEOUT, AUTH, PERMISSION, INVALID_PARAM, NOT_FOUND, CONFLICT, RATE_LIMIT, QUOTA,
    SERVER, UPSTREAM, AUDIO, STATE, CANCELLED, PROTOCOL, UNKNOWN;

    public companion object {
        /** 按名称取类别，名称不认识时返回 [UNKNOWN]。 */
        @JvmStatic
        public fun of(name: String?): ErrorCategory = values().firstOrNull { it.name == name } ?: UNKNOWN
    }
}

/**
 * SDK 全部错误的基类。
 *
 * `code` 为平台错误码，警告码或 SDK 本地错误码，没有错误码时为 0。`httpStatus` 没有 HTTP 响应时为 0。
 * `retryable` 由 [YuguErrors.isRetryable] 的同一套规则算出，SDK 内部重试与重连也按它判断。
 * `attempts` 为该逻辑调用实际发出的请求次数，`rawBody` 为原始响应体，最多保留 4 KB。
 */
public open class YuguException @JvmOverloads constructor(
    public val category: ErrorCategory,
    public val code: Int,
    public val httpStatus: Int,
    message: String,
    public val retryable: Boolean,
    rawBody: String? = null,
    cause: Throwable? = null,
    public val retryAfterMs: Long? = null,
) : RuntimeException(message, cause) {

    /** 原始响应体，按 UTF-8 截断到 4096 字节。 */
    public val rawBody: String? = rawBody?.let { truncateUtf8(it, RAW_BODY_LIMIT) }

    /** 该调用使用的幂等键。 */
    public var idempotencyKey: String? = null
        internal set

    /** 已知时为评测记录号。 */
    public var recordId: String? = null
        internal set

    /** 实际发出的请求次数。 */
    public var attempts: Int = 0
        internal set

    override fun toString(): String {
        val sb = StringBuilder(javaClass.simpleName).append(": ").append(message)
        sb.append(" [category=").append(category).append(" code=").append(code)
        if (httpStatus > 0) sb.append(" http=").append(httpStatus)
        sb.append(" retryable=").append(retryable)
        if (attempts > 0) sb.append(" attempts=").append(attempts)
        idempotencyKey?.let { sb.append(" idempotencyKey=").append(it) }
        recordId?.let { sb.append(" recordId=").append(it) }
        return sb.append(']').toString()
    }

    internal fun withContext(key: String?, attempts: Int, recordId: String? = null): YuguException {
        if (key != null) this.idempotencyKey = key
        if (attempts > 0) this.attempts = attempts
        if (recordId != null) this.recordId = recordId
        return this
    }

    public companion object {
        /** `rawBody` 的上限字节数。 */
        public const val RAW_BODY_LIMIT: Int = 4096

        internal fun truncateUtf8(s: String, limit: Int): String {
            var bytes = 0
            var i = 0
            while (i < s.length) {
                val c = s[i]
                val n = when {
                    c.code < 0x80 -> 1
                    c.code < 0x800 -> 2
                    Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1]) -> 4
                    else -> 3
                }
                if (bytes + n > limit) return s.substring(0, i)
                bytes += n
                i += if (n == 4) 2 else 1
            }
            return s
        }
    }
}

/** 网络错误：连接失败，连接被重置，证书校验失败，实时连接重连用尽。 */
public class NetworkException @JvmOverloads constructor(
    code: Int, httpStatus: Int, message: String, retryable: Boolean,
    rawBody: String? = null, cause: Throwable? = null, retryAfterMs: Long? = null,
) : YuguException(ErrorCategory.NETWORK, code, httpStatus, message, retryable, rawBody, cause, retryAfterMs)

/** 超时：连接超时，读取超时，等待终评结果超时，HTTP 408。 */
public class RequestTimeoutException @JvmOverloads constructor(
    code: Int, httpStatus: Int, message: String, retryable: Boolean,
    rawBody: String? = null, cause: Throwable? = null, retryAfterMs: Long? = null,
) : YuguException(ErrorCategory.TIMEOUT, code, httpStatus, message, retryable, rawBody, cause, retryAfterMs)

/** 鉴权失败：凭据缺失，签名不符，token 失效，API Key 不存在或已禁用。 */
public class AuthException @JvmOverloads constructor(
    code: Int, httpStatus: Int, message: String, retryable: Boolean,
    rawBody: String? = null, cause: Throwable? = null, retryAfterMs: Long? = null,
) : YuguException(ErrorCategory.AUTH, code, httpStatus, message, retryable, rawBody, cause, retryAfterMs)

/** 无权限：coreType 未授权，账户或用户被禁用，没有麦克风权限。 */
public class PermissionException @JvmOverloads constructor(
    code: Int, httpStatus: Int, message: String, retryable: Boolean,
    rawBody: String? = null, cause: Throwable? = null, retryAfterMs: Long? = null,
) : YuguException(ErrorCategory.PERMISSION, code, httpStatus, message, retryable, rawBody, cause, retryAfterMs)

/** 参数错误：平台校验失败或 SDK 在发请求前发现的参数问题。 */
public class InvalidParameterException @JvmOverloads constructor(
    code: Int, httpStatus: Int, message: String, retryable: Boolean,
    rawBody: String? = null, cause: Throwable? = null, retryAfterMs: Long? = null,
) : YuguException(ErrorCategory.INVALID_PARAM, code, httpStatus, message, retryable, rawBody, cause, retryAfterMs)

/** 资源不存在。 */
public class NotFoundException @JvmOverloads constructor(
    code: Int, httpStatus: Int, message: String, retryable: Boolean,
    rawBody: String? = null, cause: Throwable? = null, retryAfterMs: Long? = null,
) : YuguException(ErrorCategory.NOT_FOUND, code, httpStatus, message, retryable, rawBody, cause, retryAfterMs)

/** 冲突：幂等键仍在处理中，幂等键用于了不同请求，账目并发冲突。 */
public class ConflictException @JvmOverloads constructor(
    code: Int, httpStatus: Int, message: String, retryable: Boolean,
    rawBody: String? = null, cause: Throwable? = null, retryAfterMs: Long? = null,
) : YuguException(ErrorCategory.CONFLICT, code, httpStatus, message, retryable, rawBody, cause, retryAfterMs)

/** 限流：请求过多，排队超时，并发超限。`retryAfterMs` 为服务端建议的等待时长。 */
public class RateLimitException @JvmOverloads constructor(
    code: Int, httpStatus: Int, message: String, retryable: Boolean,
    rawBody: String? = null, cause: Throwable? = null, retryAfterMs: Long? = null,
) : YuguException(ErrorCategory.RATE_LIMIT, code, httpStatus, message, retryable, rawBody, cause, retryAfterMs)

/** 额度不足：套餐额度，账户余额，试用与沙箱的每日上限。 */
public class QuotaExceededException @JvmOverloads constructor(
    code: Int, httpStatus: Int, message: String, retryable: Boolean,
    rawBody: String? = null, cause: Throwable? = null, retryAfterMs: Long? = null,
) : YuguException(ErrorCategory.QUOTA, code, httpStatus, message, retryable, rawBody, cause, retryAfterMs)

/** 服务端错误，类别为 [ErrorCategory.SERVER] 或 [ErrorCategory.UPSTREAM]。 */
public class ServerException @JvmOverloads constructor(
    category: ErrorCategory, code: Int, httpStatus: Int, message: String, retryable: Boolean,
    rawBody: String? = null, cause: Throwable? = null, retryAfterMs: Long? = null,
) : YuguException(category, code, httpStatus, message, retryable, rawBody, cause, retryAfterMs)

/** 音频质量问题：本地预检未通过，或开启 `strictAudio` 后结果带 1001 警告。 */
public class AudioQualityException @JvmOverloads constructor(
    code: Int, httpStatus: Int, message: String, retryable: Boolean,
    rawBody: String? = null, cause: Throwable? = null, retryAfterMs: Long? = null,
) : YuguException(ErrorCategory.AUDIO, code, httpStatus, message, retryable, rawBody, cause, retryAfterMs)

/** 状态错误：客户端已关闭，会话状态不允许该操作，录音器不可用，重放缓冲溢出。 */
public class IllegalSessionStateException @JvmOverloads constructor(
    code: Int, httpStatus: Int, message: String, retryable: Boolean,
    rawBody: String? = null, cause: Throwable? = null, retryAfterMs: Long? = null,
) : YuguException(ErrorCategory.STATE, code, httpStatus, message, retryable, rawBody, cause, retryAfterMs)

/** 调用方取消了请求。 */
public class RequestCancelledException @JvmOverloads constructor(
    code: Int, httpStatus: Int, message: String, retryable: Boolean,
    rawBody: String? = null, cause: Throwable? = null, retryAfterMs: Long? = null,
) : YuguException(ErrorCategory.CANCELLED, code, httpStatus, message, retryable, rawBody, cause, retryAfterMs)

/** 协议错误：响应或帧无法解析。 */
public class ProtocolViolationException @JvmOverloads constructor(
    code: Int, httpStatus: Int, message: String, retryable: Boolean,
    rawBody: String? = null, cause: Throwable? = null, retryAfterMs: Long? = null,
) : YuguException(ErrorCategory.PROTOCOL, code, httpStatus, message, retryable, rawBody, cause, retryAfterMs)

/** 音频质量警告码，与 `spec/errors.json` 的 `warnings` 一致。 */
public enum class WarningCode(public val code: Int, public val defaultMessage: String) {
    NO_VALID_AUDIO(1001, "No valid audio detected!"),
    VOLUME_TOO_LOW(1002, "Audio volume too low!"),
    VOLUME_TOO_HIGH(1003, "Audio volume too high!"),
    AUDIO_NOISY(1004, "Audio noisy!"),
    AUDIO_INCOMPLETE(1005, "Audio not complete!"),
    SCORER_DEGRADED(1009, "scorer degraded");

    public companion object {
        /** 按警告码取枚举，不认识的码返回 null。 */
        @JvmStatic
        public fun fromCode(code: Int): WarningCode? = values().firstOrNull { it.code == code }
    }
}

/** 评测结果里的一条警告，形态为 `{code, message}`。 */
public data class Warning(public val code: Int, public val message: String) {
    /** 对应的枚举，不认识的码为 null。 */
    public val warningCode: WarningCode? get() = WarningCode.fromCode(code)
}

/** 本地音频预检给出的一条警告，码为 90101 到 90105。 */
public data class LocalWarning(public val code: Int, public val message: String)

/**
 * 错误码映射与可重试判定，全部基于生成的 [ErrorTable]。
 *
 * 错误码按场景查表，不只看数字：错误响应体与错误帧里的 `code` 查错误码表，结果里的警告查警告码表，
 * 90000 起的本地码查本地码表。1004 与 1005 在错误码表里是账户状态，在警告码表里是音频问题。
 * 不在兜底表里的 HTTP 状态按类别归类：其余 4xx 为 `INVALID_PARAM`，其余 5xx 为 `SERVER`，其他为 `UNKNOWN`。
 *
 * 可重试判定规则，按顺序第一条命中为准：
 * 1. 本地码 90001，90002，90007 可重试，其余本地码不可重试。
 * 2. 有业务错误码时取错误码表里的 `retryable`。
 * 3. 业务错误码缺失或不认识时，HTTP 408，425，429，500，502，503，504 可重试，其余不可重试。
 */
public object YuguErrors {

    private val BRACKET_CODE = Regex("^\\s*\\[(\\d{1,6})]")

    /**
     * 按错误码构造对应类型的异常：本地码查本地码表，其余查错误码表，1004 与 1005 因此是账户状态错误。
     * 只出现在警告码表里的码，例如 1001，构造 [AudioQualityException]。
     */
    @JvmStatic
    @JvmOverloads
    public fun fromCode(code: Int, message: String? = null, httpStatus: Int = 0): YuguException {
        if (code in LOCAL_RANGE) {
            val e = local(code, message)
            return if (httpStatus == 0) e else create(e.category, code, httpStatus, e.message ?: "", e.retryable)
        }
        ErrorTable.ERRORS[code]?.let { entry ->
            return create(ErrorCategory.of(entry.category), code, httpStatus, message ?: entry.message, entry.retryable)
        }
        if (ErrorTable.WARNINGS.containsKey(code)) return fromWarning(code, message)
        return create(httpCategory(httpStatus), code, httpStatus, message ?: "error $code", httpStatus in ErrorTable.RETRYABLE_HTTP)
    }

    /** 按警告码表构造 [AudioQualityException]，供需要把警告当异常处理的调用方使用。 */
    @JvmStatic
    @JvmOverloads
    public fun fromWarning(code: Int, message: String? = null): AudioQualityException {
        val entry = ErrorTable.WARNINGS[code]
        return AudioQualityException(code, 0, message ?: entry?.message ?: "warning $code", entry?.retryable ?: false)
    }

    /** 按本地码表构造异常。 */
    @JvmStatic
    @JvmOverloads
    public fun local(code: Int, message: String? = null, cause: Throwable? = null): YuguException {
        val entry = ErrorTable.LOCAL[code]
        val category = entry?.let { ErrorCategory.of(it.category) } ?: ErrorCategory.UNKNOWN
        return create(category, code, 0, message ?: entry?.message ?: "local error $code", entry?.retryable ?: false, cause = cause)
    }

    /**
     * 把 HTTP 错误响应映射为异常。顺序：响应体非零 `code` 查错误码表，FastAPI 形态 `detail` 里的
     * `[2001]` 前缀，最后按 HTTP 状态兜底。错误码不在错误码表里时保留原码，类别与可重试按 HTTP 状态判定。
     */
    @JvmStatic
    @JvmOverloads
    public fun fromHttp(httpStatus: Int, body: String?, retryAfterMs: Long? = null): YuguException {
        val parsed = parseBody(body)
        val code = parsed.code ?: 0
        val entry = if (code != 0) ErrorTable.ERRORS[code] else null
        val category = entry?.let { ErrorCategory.of(it.category) } ?: httpCategory(httpStatus)
        val message = parsed.message?.takeIf { it.isNotBlank() } ?: entry?.message ?: "HTTP $httpStatus"
        val e = create(category, code, httpStatus, message, isRetryable(code, httpStatus), body, null, retryAfterMs)
        parsed.recordId?.let { e.recordId = it }
        return e
    }

    /** 把实时评测的错误帧 `{"event":"error","code":N,"message":"..."}` 映射为异常，`code` 查错误码表。 */
    @JvmStatic
    public fun fromErrorFrame(frameJson: String): YuguException {
        val parsed = parseBody(frameJson)
        val code = parsed.code ?: 0
        val entry = if (code != 0) ErrorTable.ERRORS[code] else null
        val category = entry?.let { ErrorCategory.of(it.category) } ?: ErrorCategory.UNKNOWN
        val message = parsed.message?.takeIf { it.isNotBlank() } ?: entry?.message ?: "error frame"
        return create(category, code, 0, message, isRetryable(code, 0), frameJson)
    }

    /** 公开的可重试判定，SDK 的重试循环与实时重连共用。 */
    @JvmStatic
    public fun isRetryable(error: Throwable?): Boolean = when (error) {
        null -> false
        is YuguException -> error.retryable
        is IOException -> fromIOException(error, cancelled = false).retryable
        else -> false
    }

    /** 按错误码与 HTTP 状态计算可重试判定，`code` 为 0 表示没有错误码，错误码查错误码表。 */
    @JvmStatic
    public fun isRetryable(code: Int, httpStatus: Int): Boolean {
        if (code in LOCAL_RANGE) return ErrorTable.LOCAL[code]?.retryable ?: false
        if (code != 0) ErrorTable.ERRORS[code]?.let { return it.retryable }
        return httpStatus in ErrorTable.RETRYABLE_HTTP
    }

    /** 错误码所属类别，查表顺序与 [fromCode] 相同，不认识的码返回 null。 */
    @JvmStatic
    public fun categoryOf(code: Int): ErrorCategory? {
        val entry = if (code in LOCAL_RANGE) ErrorTable.LOCAL[code] else ErrorTable.ERRORS[code] ?: ErrorTable.WARNINGS[code]
        return entry?.let { ErrorCategory.of(it.category) }
    }

    private val LOCAL_RANGE = 90000..90999

    /** HTTP status category: the fallback table, then by class (4xx, 5xx), else UNKNOWN. */
    internal fun httpCategory(httpStatus: Int): ErrorCategory {
        ErrorTable.HTTP_FALLBACK[httpStatus]?.let { return ErrorCategory.of(it) }
        return when (httpStatus) {
            in 400..499 -> ErrorCategory.INVALID_PARAM
            in 500..599 -> ErrorCategory.SERVER
            else -> ErrorCategory.UNKNOWN
        }
    }

    internal fun fromIOException(e: IOException, cancelled: Boolean): YuguException = when {
        cancelled -> local(90003, cause = e)
        e is SocketTimeoutException -> local(90002, "连接或读取超时：${e.message ?: "timeout"}", e)
        e is InterruptedIOException && e.message == "timeout" -> local(90002, "请求超过时限", e)
        isTlsFailure(e) -> local(90011, "证书校验失败：${e.message}", e)
        else -> local(90001, "网络错误：${e.javaClass.simpleName} ${e.message ?: ""}".trim(), e)
    }

    private fun isTlsFailure(e: Throwable): Boolean {
        var t: Throwable? = e
        var depth = 0
        while (t != null && depth < 8) {
            if (t is SSLPeerUnverifiedException || t is CertificateException) return true
            t = t.cause
            depth++
        }
        return false
    }

    internal class ParsedBody(val code: Int?, val message: String?, val recordId: String?)

    internal fun parseBody(body: String?): ParsedBody {
        val obj = JObj.parseOrNull(body) ?: return ParsedBody(null, body?.trim()?.take(200)?.takeIf { looksLikeText(it) }, null)
        var code = obj.int("code")?.takeIf { it != 0 }
        var message = obj.str("message") ?: obj.str("msg") ?: obj.str("error")
        when (val detail = obj.raw("detail")) {
            is String -> {
                if (message == null) message = detail
                if (code == null) BRACKET_CODE.find(detail)?.let { code = it.groupValues[1].toInt() }
            }
            is List<*> -> if (message == null) {
                message = detail.joinToString("；") { item ->
                    @Suppress("UNCHECKED_CAST")
                    ((item as? Map<String, Any?>)?.get("msg") ?: item).toString()
                }
            }
        }
        return ParsedBody(code, message, obj.str("recordId"))
    }

    private fun looksLikeText(s: String): Boolean = s.isNotEmpty() && !s.startsWith("<")

    internal fun create(
        category: ErrorCategory,
        code: Int,
        httpStatus: Int,
        message: String,
        retryable: Boolean,
        rawBody: String? = null,
        cause: Throwable? = null,
        retryAfterMs: Long? = null,
    ): YuguException = when (category) {
        ErrorCategory.NETWORK -> NetworkException(code, httpStatus, message, retryable, rawBody, cause, retryAfterMs)
        ErrorCategory.TIMEOUT -> RequestTimeoutException(code, httpStatus, message, retryable, rawBody, cause, retryAfterMs)
        ErrorCategory.AUTH -> AuthException(code, httpStatus, message, retryable, rawBody, cause, retryAfterMs)
        ErrorCategory.PERMISSION -> PermissionException(code, httpStatus, message, retryable, rawBody, cause, retryAfterMs)
        ErrorCategory.INVALID_PARAM -> InvalidParameterException(code, httpStatus, message, retryable, rawBody, cause, retryAfterMs)
        ErrorCategory.NOT_FOUND -> NotFoundException(code, httpStatus, message, retryable, rawBody, cause, retryAfterMs)
        ErrorCategory.CONFLICT -> ConflictException(code, httpStatus, message, retryable, rawBody, cause, retryAfterMs)
        ErrorCategory.RATE_LIMIT -> RateLimitException(code, httpStatus, message, retryable, rawBody, cause, retryAfterMs)
        ErrorCategory.QUOTA -> QuotaExceededException(code, httpStatus, message, retryable, rawBody, cause, retryAfterMs)
        ErrorCategory.SERVER, ErrorCategory.UPSTREAM ->
            ServerException(category, code, httpStatus, message, retryable, rawBody, cause, retryAfterMs)
        ErrorCategory.AUDIO -> AudioQualityException(code, httpStatus, message, retryable, rawBody, cause, retryAfterMs)
        ErrorCategory.STATE -> IllegalSessionStateException(code, httpStatus, message, retryable, rawBody, cause, retryAfterMs)
        ErrorCategory.CANCELLED -> RequestCancelledException(code, httpStatus, message, retryable, rawBody, cause, retryAfterMs)
        ErrorCategory.PROTOCOL -> ProtocolViolationException(code, httpStatus, message, retryable, rawBody, cause, retryAfterMs)
        ErrorCategory.UNKNOWN -> YuguException(ErrorCategory.UNKNOWN, code, httpStatus, message, retryable, rawBody, cause, retryAfterMs)
    }
}
