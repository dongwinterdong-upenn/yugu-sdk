package com.shengzhiai.yugu

/**
 * 日志输出接口。SDK 不会把 secretKey，签名值，token 与音频字节写进日志，appKey 只输出前 4 位加 `***`。
 */
public fun interface YuguLogger {
    /** 输出一条日志，[error] 可为 null。 */
    public fun log(level: LogLevel, tag: String, message: String, error: Throwable?)
}

/** 默认日志输出，写入 Logcat。 */
public class LogcatLogger : YuguLogger {
    override fun log(level: LogLevel, tag: String, message: String, error: Throwable?) {
        val priority = when (level) {
            LogLevel.ERROR -> android.util.Log.ERROR
            LogLevel.WARN -> android.util.Log.WARN
            LogLevel.INFO -> android.util.Log.INFO
            else -> android.util.Log.DEBUG
        }
        val text = if (error != null) message + '\n' + android.util.Log.getStackTraceString(error) else message
        android.util.Log.println(priority, tag, text)
    }
}

/**
 * 指标回调，全部方法可选。
 *
 * - [onRequestStart]：REST 每次尝试发出前回调一次，`attempt` 从 1 开始。
 * - [onRequestEnd]：REST 每个逻辑调用结束时回调一次，`latencyMs` 为含重试的总耗时，
 *   `attempts` 为总尝试次数，失败时 `error` 非空。
 * - [onRetry]：每次重试前回调，`attempt` 为第几次重试。
 * - [onSessionStateChanged]：实时会话状态变化。
 * - [onReconnect]：实时会话每次重连的结果。
 *
 * 回调在调用线程或会话回调线程上执行，应尽快返回。回调抛出的异常会被 SDK 捕获并记日志。
 */
public interface YuguEventListener {
    public fun onRequestStart(op: String, method: String, path: String, attempt: Int) {}
    public fun onRequestEnd(op: String, httpStatus: Int, latencyMs: Long, attempts: Int, error: YuguException?) {}
    public fun onRetry(op: String, attempt: Int, delayMs: Long, error: YuguException) {}
    public fun onSessionStateChanged(sessionId: String, oldState: SessionState, newState: SessionState) {}
    public fun onReconnect(sessionId: String, attempt: Int, succeeded: Boolean) {}

    public companion object {
        /** 什么都不做的实现。 */
        @JvmField
        public val NONE: YuguEventListener = object : YuguEventListener {}
    }
}

/** Level filter, sink isolation and redaction helpers. */
internal class Log(private val level: LogLevel, private val sink: YuguLogger) {

    fun enabled(l: LogLevel): Boolean = level.allows(l)

    fun e(msg: String, t: Throwable? = null) = emit(LogLevel.ERROR, msg, t)
    fun w(msg: String, t: Throwable? = null) = emit(LogLevel.WARN, msg, t)
    fun i(msg: String) = emit(LogLevel.INFO, msg, null)
    fun d(msg: String) = emit(LogLevel.DEBUG, msg, null)

    fun d(block: () -> String) {
        if (enabled(LogLevel.DEBUG)) emit(LogLevel.DEBUG, block(), null)
    }

    fun emit(l: LogLevel, msg: String, t: Throwable?) {
        if (!level.allows(l)) return
        try {
            sink.log(l, TAG, msg, t)
        } catch (_: Throwable) {
            // a broken logger must never break a call
        }
    }

    companion object {
        const val TAG = "YuguSDK"

        private val SECRET_QUERY = Regex("([?&](?:signature|token|secretKey)=)[^&#\\s]*", RegexOption.IGNORE_CASE)
        private val APPKEY_QUERY = Regex("([?&]appKey=)([^&#\\s]*)")

        /** Masks credentials in a URL: signature and token become ***, appKey keeps 4 chars. */
        fun redactUrl(url: String): String {
            val noSecrets = SECRET_QUERY.replace(url) { it.groupValues[1] + "***" }
            return APPKEY_QUERY.replace(noSecrets) { it.groupValues[1] + Auth.maskAppKey(it.groupValues[2]) }
        }
    }
}
