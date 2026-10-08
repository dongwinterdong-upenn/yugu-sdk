package com.shengzhiai.yugu

import java.io.Closeable

/**
 * 实时评测会话状态。
 *
 * ```
 * IDLE -> CONNECTING -> CONNECTED -> STARTED -> ENDING -> COMPLETED -> CLOSED
 * CONNECTING, CONNECTED, STARTED, ENDING -> RECONNECTING -> CONNECTING
 * 任一非终态 -> FAILED -> CLOSED
 * 任一非终态 -> CANCELLED -> CLOSED
 * ```
 */
public enum class SessionState {
    IDLE, CONNECTING, CONNECTED, STARTED, ENDING, RECONNECTING, COMPLETED, FAILED, CANCELLED, CLOSED;

    /** 是否终态：COMPLETED，FAILED，CANCELLED，CLOSED。 */
    public val isTerminal: Boolean
        get() = this == COMPLETED || this == FAILED || this == CANCELLED || this == CLOSED
}

/**
 * 实时评测回调。除 [onResult] 与 [onError] 外全部可选。
 *
 * 保证：
 * - 每个会话恰好回调一次 [onResult] 或 [onError]，先调用了 `cancel()` 时两者都不回调。
 * - [onClosed] 总是最后一个回调。
 * - 同一会话的回调不会并发执行，默认在主线程执行，可用客户端选项 `callbackExecutor` 改到其他线程。
 * - 回调里 `getState()` 返回的值等于最近一次 [onStateChanged] 通知的新状态。
 * - [onConnected] 与 [onStarted] 只在首次连接时回调，重连成功回调 [onReconnected]。
 */
public interface StreamListener {
    public fun onStateChanged(oldState: SessionState, newState: SessionState) {}

    /** 收到服务端 `connected`。 */
    public fun onConnected() {}

    /** 收到服务端 `started`，可以开始送音频，此前送入的音频已排队。 */
    public fun onStarted() {}

    /** 兼容实时评测开启 `realtimeFeedback` 后的进度中间帧。 */
    public fun onPartial(partial: PartialResult) {}

    /** 连接中断，[delayMs] 毫秒后进行第 [attempt] 次重连。 */
    public fun onReconnecting(attempt: Int, delayMs: Long, cause: YuguException) {}

    /** 第 [attempt] 次重连成功。DROP 策略下 [droppedBytes] 为断线期间丢弃的音频字节数。 */
    public fun onReconnected(attempt: Int, droppedBytes: Long) {}

    /** 结束时本地预检给出的警告。 */
    public fun onWarning(warning: LocalWarning) {}

    /** 终评结果。 */
    public fun onResult(result: EvalResult)

    /** 会话失败，重连用尽时错误码为 90006。 */
    public fun onError(error: YuguException)

    /** 连接已关闭，总是最后一个回调。 */
    public fun onClosed(code: Int, reason: String) {}
}

/**
 * 实时评测会话。音频为 16 kHz，16 位，单声道 PCM，推荐每帧 640 字节，即 20 毫秒。
 *
 * - [sendAudio]：任何线程可调用。连接建立前送入的音频排队，开始后按顺序发出，超过 32000 字节的块拆成多帧发送。
 *   已调用 [end] 或会话已结束时返回 false，音频不会发出。
 * - [end]：通知服务端结束并等待终评，可重复调用。
 * - [cancel]：立即结束，不再回调 `onResult` 与 `onError`，可重复调用。
 * - [close]：会话未结束时等同 [cancel]，可重复调用。
 */
public interface StreamSession : Closeable {
    /** SDK 生成的会话编号，用于日志与指标。 */
    public val sessionId: String

    /** 本会话使用的幂等键，重连沿用同一个键。关闭自动幂等键且未指定时为 null。 */
    public val idempotencyKey: String?

    /** 当前状态，与最近一次 `onStateChanged` 通知的新状态一致。 */
    public fun getState(): SessionState

    /** 会话是否仍在进行，即状态不是终态。 */
    public fun isActive(): Boolean

    /** 已发生的重连次数。 */
    public fun getReconnectCount(): Int

    /** 送一段音频。 */
    public fun sendAudio(pcm: ByteArray): Boolean

    /** 送一段音频的一部分。 */
    public fun sendAudio(pcm: ByteArray, offset: Int, length: Int): Boolean

    /** 结束送音频，等待终评。 */
    public fun end()

    /** 取消会话。 */
    public fun cancel()

    /** 关闭会话。 */
    override fun close()
}
