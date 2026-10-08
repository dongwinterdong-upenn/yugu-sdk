package com.shengzhiai.yugu.stream

import com.shengzhiai.yugu.AudioBufferPolicy
import com.shengzhiai.yugu.AudioPrecheckMode
import com.shengzhiai.yugu.Auth
import com.shengzhiai.yugu.EvalResult
import com.shengzhiai.yugu.LocalWarning
import com.shengzhiai.yugu.Log
import com.shengzhiai.yugu.PartialResult
import com.shengzhiai.yugu.ReconnectPolicy
import com.shengzhiai.yugu.ResultParser
import com.shengzhiai.yugu.SessionState
import com.shengzhiai.yugu.Signer
import com.shengzhiai.yugu.StreamListener
import com.shengzhiai.yugu.StreamSession
import com.shengzhiai.yugu.YuguErrors
import com.shengzhiai.yugu.YuguException
import com.shengzhiai.yugu.audio.AudioPrecheck
import com.shengzhiai.yugu.audio.PcmStats
import com.shengzhiai.yugu.internal.ClientConfig
import com.shengzhiai.yugu.internal.HttpEngine
import com.shengzhiai.yugu.internal.IdempotencyKeys
import com.shengzhiai.yugu.internal.JObj
import com.shengzhiai.yugu.internal.Json
import com.shengzhiai.yugu.internal.Lifecycle
import com.shengzhiai.yugu.internal.SerialExecutor
import com.shengzhiai.yugu.internal.Shared
import com.shengzhiai.yugu.invalidArgument
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.IOException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * One streaming evaluation (DESIGN 2.5): state machine, reconnect with REPLAY, DROP or FAIL,
 * handshake and result timeouts, serial callback delivery.
 *
 * Every state change and callback is decided under [lock] and enqueued, in that order, to a
 * serial dispatcher, so callbacks never overlap and arrive in the order the engine decided them.
 * [publicState] is updated by the dispatcher right before onStateChanged runs, which keeps
 * getState() equal to the last announced state.
 */
internal class WsSession(
    private val cfg: ClientConfig,
    private val log: Log,
    private val wsClient: OkHttpClient,
    private val lifecycle: Lifecycle,
    private val path: String,
    private val startFrame: Map<String, Any?>,
    private val listener: StreamListener,
    override val idempotencyKey: String?,
    private val reconnect: ReconnectPolicy,
    private val bufferPolicy: AudioBufferPolicy,
    private val resultTimeoutMs: Long,
    private val extraQuery: Map<String, String>,
) : StreamSession, Lifecycle.Cancellable {

    override val sessionId: String = "s" + IdempotencyKeys.generate().substring(0, 12)

    private val lock = Any()
    private val dispatcher = SerialExecutor(cfg.callbackExecutor)

    @Volatile
    private var publicState = SessionState.IDLE

    // ---- guarded by lock
    private var state = SessionState.IDLE
    private var ws: WebSocket? = null
    private var connId = 0
    private var connectedNotified = false
    private var startedNotified = false
    private var everStarted = false
    private var reconnecting = false
    /** Failed attempts in the current outage, reset when a reconnect reaches STARTED. */
    private var consecutiveAttempts = 0
    /** All reconnects of the session. */
    private var reconnectAttempts = 0
    private var endRequested = false
    private var endSent = false
    private val replay = ArrayList<ByteArray>()
    private var replayBytes = 0L
    private var replayOverflow = false
    private val queue = ArrayList<ByteArray>()
    private var droppedBytes = 0L
    private val stats = PcmStats()
    private var totalBytes = 0L
    private val localWarnings = ArrayList<LocalWarning>()
    private var handshakeTimer: ScheduledFuture<*>? = null
    private var resultTimer: ScheduledFuture<*>? = null
    private var reconnectTimer: ScheduledFuture<*>? = null
    private var warnedIgnoredAudio = false

    // ------------------------------------------------------------------------- public API

    override fun getState(): SessionState = publicState

    override fun isActive(): Boolean = !publicState.isTerminal

    override fun getReconnectCount(): Int = synchronized(lock) { reconnectAttempts }

    override fun sendAudio(pcm: ByteArray): Boolean = sendAudio(pcm, 0, pcm.size)

    override fun sendAudio(pcm: ByteArray, offset: Int, length: Int): Boolean {
        if (offset < 0 || length < 0 || offset + length > pcm.size) throw invalidArgument("offset 或 length 越界")
        synchronized(lock) {
            if (state.isTerminal || endRequested) {
                if (!warnedIgnoredAudio) {
                    warnedIgnoredAudio = true
                    log.w("[$sessionId] 会话已结束或已调用 end()，后续音频被忽略")
                }
                return false
            }
            // The platform closes frames above 128 KB with 1009, so large chunks go out as several frames.
            var pos = offset
            val end = offset + length
            while (pos < end) {
                val n = minOf(MAX_FRAME_BYTES, end - pos)
                if (!acceptChunkLocked(pcm.copyOfRange(pos, pos + n))) return false
                pos += n
            }
            return true
        }
    }

    /** Buffers, sends or drops one frame. Returns false when the session failed meanwhile. */
    private fun acceptChunkLocked(chunk: ByteArray): Boolean {
        val length = chunk.size
        stats.add(chunk)
        totalBytes += length
        if (bufferPolicy == AudioBufferPolicy.REPLAY && !replayOverflow) {
            if (replayBytes + length > cfg.maxReplayBytes) {
                replayOverflow = true
                replay.clear()
                replayBytes = 0
                log.w("[$sessionId] 重放缓冲超过 ${cfg.maxReplayBytes} 字节，之后若需重连会话将以 90008 失败")
                if (reconnecting && everStarted) {
                    // the pending reconnect can no longer deliver the whole session audio
                    failLocked(
                        YuguErrors.local(90008, "重连期间重放缓冲超过 ${cfg.maxReplayBytes} 字节"),
                        1000, "replay buffer overflow",
                    )
                    return false
                }
            } else {
                replay.add(chunk)
                replayBytes += length
            }
        }
        when {
            state == SessionState.STARTED -> ws?.send(chunk.toByteString())
            bufferPolicy == AudioBufferPolicy.DROP && reconnecting -> droppedBytes += length
            else -> queue.add(chunk)
        }
        return true
    }

    override fun end() {
        synchronized(lock) {
            if (state.isTerminal || endRequested) return
            endRequested = true
            if (cfg.audioPrecheck != AudioPrecheckMode.OFF) {
                val findings = AudioPrecheck.checkPcm(stats, totalBytes, STREAM_SAMPLE_RATE, 1)
                val reject = if (cfg.audioPrecheck == AudioPrecheckMode.REJECT) {
                    findings.firstOrNull { AudioPrecheck.rejectable(it.code) }
                } else {
                    null
                }
                for (f in findings) {
                    if (f === reject) continue
                    localWarnings.add(f)
                    post { listener.onWarning(f) }
                }
                if (reject != null) {
                    failLocked(YuguErrors.local(reject.code, reject.message), 1000, "audio rejected")
                    return
                }
            }
            if (state == SessionState.STARTED) sendEndLocked()
        }
    }

    override fun cancel() {
        synchronized(lock) {
            if (state.isTerminal) return
            log.i("[$sessionId] cancel")
            cancelTimersLocked()
            transitionLocked(SessionState.CANCELLED)
            closeLocked(1000, "cancelled")
        }
    }

    override fun close() {
        cancel()
    }

    // ------------------------------------------------------------------------- lifecycle

    fun start() {
        lifecycle.register(this)
        synchronized(lock) {
            transitionLocked(SessionState.CONNECTING)
            connectLocked()
        }
    }

    private fun connectLocked() {
        val id = ++connId
        val url = buildUrl()
        log.i("[$sessionId] connect ${Log.redactUrl(url.toString())} reconnects=$reconnectAttempts")
        val request = Request.Builder().url(url).header("User-Agent", cfg.userAgent).build()
        ws = wsClient.newWebSocket(request, Listener(id))
        handshakeTimer?.cancel(false)
        // Until the upgrade completes OkHttp bounds each phase itself and may need one connect
        // timeout per address before falling back, for example from a silent IPv6 address to IPv4.
        handshakeTimer = schedule(cfg.connectTimeoutMs * OPEN_TIMEOUT_FACTOR) { onHandshakeTimeout(id, opened = false) }
    }

    /** Transport open: from here the server must send connected and started within connectTimeoutMs. */
    private fun onTransportOpen(id: Int) {
        synchronized(lock) {
            if (id != connId || state != SessionState.CONNECTING) return
            handshakeTimer?.cancel(false)
            handshakeTimer = schedule(cfg.connectTimeoutMs) { onHandshakeTimeout(id, opened = true) }
        }
    }

    internal fun buildUrl(): HttpUrl {
        val q = LinkedHashMap<String, String>()
        for ((k, v) in extraQuery) q[k] = v
        idempotencyKey?.let { q["idempotencyKey"] = it }
        when (val a = cfg.auth) {
            is Auth.Token -> q["token"] = a.token
            is Auth.Signature -> {
                q["appKey"] = a.appKey
                q["timestamp"] = (System.currentTimeMillis() / 1000).toString()
                q["nonce"] = IdempotencyKeys.generate().substring(0, 16)
                q["signature"] = Signer.sign(q, a.secretKey)
            }
        }
        val base = cfg.wsBaseUrl.trimEnd('/')
            .replaceFirst(Regex("^wss://", RegexOption.IGNORE_CASE), "https://")
            .replaceFirst(Regex("^ws://", RegexOption.IGNORE_CASE), "http://")
        val b = (base + path).toHttpUrl().newBuilder()
        for ((k, v) in q) b.addQueryParameter(k, v)
        return b.build()
    }

    // ------------------------------------------------------------------------- transport events

    private inner class Listener(private val id: Int) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            log.d { "[$sessionId] open conn=$id" }
            onTransportOpen(id)
        }

        override fun onMessage(webSocket: WebSocket, text: String) = onText(id, text)

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            log.d { "[$sessionId] ignored binary frame of ${bytes.size} bytes" }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            onRemoteClose(id, code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = onRemoteClose(id, code, reason)

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val error = if (response != null && response.code != 101) {
                val body = try {
                    response.peekBody(4096).string()
                } catch (e: Exception) {
                    null
                }
                YuguErrors.fromHttp(response.code, body, HttpEngine.retryAfterMs(response.headers))
            } else {
                YuguErrors.fromIOException(t as? IOException ?: IOException(t.toString(), t), cancelled = false)
            }
            onTransportFailure(id, error, 1006, t.message ?: t.javaClass.simpleName)
        }
    }

    private fun onText(id: Int, text: String) {
        val obj = JObj.parseOrNull(text)
        synchronized(lock) {
            if (id != connId || state.isTerminal) return
            if (obj == null) {
                failLocked(YuguErrors.local(90005, "无法解析的帧：${text.take(200)}"), 1000, "protocol error")
                return
            }
            when (obj.str("event")) {
                "connected" -> onConnectedLocked()
                "started" -> onStartedLocked()
                "pong" -> Unit
                "error" -> onErrorFrameLocked(text)
                "result" -> onFinalLocked(obj, text)
                else -> {
                    val eof = obj.int("eof")
                    when {
                        eof == 0 -> {
                            val partial = PartialResult(obj.obj("result")?.long("bytes"), obj.map)
                            post { listener.onPartial(partial) }
                        }
                        eof == 1 || obj.has("result") -> onFinalLocked(obj, text)
                        else -> log.d { "[$sessionId] ignored frame ${text.take(120)}" }
                    }
                }
            }
        }
    }

    private fun onConnectedLocked() {
        if (state != SessionState.CONNECTING) return
        transitionLocked(SessionState.CONNECTED)
        if (!connectedNotified) {
            connectedNotified = true
            post { listener.onConnected() }
        }
        val frame = LinkedHashMap(startFrame)
        idempotencyKey?.let { frame["idempotencyKey"] = it }
        ws?.send(Json.write(frame))
    }

    private fun onStartedLocked() {
        if (state != SessionState.CONNECTED && state != SessionState.CONNECTING) return // duplicate started
        handshakeTimer?.cancel(false)
        handshakeTimer = null
        transitionLocked(SessionState.STARTED)
        if (!startedNotified) {
            startedNotified = true
            post { listener.onStarted() }
        }
        val socket = ws ?: return
        if (bufferPolicy == AudioBufferPolicy.REPLAY && !replayOverflow && everStarted) {
            for (c in replay) socket.send(c.toByteString())
        } else {
            for (c in queue) socket.send(c.toByteString())
        }
        queue.clear()
        everStarted = true
        if (reconnecting) {
            reconnecting = false
            val dropped = droppedBytes
            droppedBytes = 0
            val attempt = consecutiveAttempts
            consecutiveAttempts = 0
            log.i("[$sessionId] reconnected attempt=$attempt replayBytes=$replayBytes dropped=$dropped")
            post {
                listener.onReconnected(attempt, dropped)
                cfg.eventListener.onReconnect(sessionId, attempt, true)
            }
        }
        if (endRequested && !endSent) sendEndLocked()
    }

    private fun sendEndLocked() {
        val socket = ws ?: return
        socket.send(END_FRAME)
        endSent = true
        transitionLocked(SessionState.ENDING)
        resultTimer?.cancel(false)
        val id = connId
        resultTimer = schedule(resultTimeoutMs) { onResultTimeout(id) }
    }

    private fun onFinalLocked(obj: JObj, text: String) {
        cancelTimersLocked()
        val result: EvalResult = try {
            ResultParser.evalResult(obj, text, idempotencyKey, obj.bool("replayed") == true, ArrayList(localWarnings))
        } catch (e: RuntimeException) {
            failLocked(YuguErrors.local(90005, "终评帧无法解析", e), 1000, "protocol error")
            return
        }
        log.i("[$sessionId] result recordId=${result.recordId} replayed=${result.replayed} reconnects=$reconnectAttempts")
        transitionLocked(SessionState.COMPLETED)
        post { listener.onResult(result) }
        closeLocked(1000, "completed")
    }

    private fun onErrorFrameLocked(text: String) {
        val error = YuguErrors.fromErrorFrame(text)
        log.w("[$sessionId] server error frame code=${error.code} retryable=${error.retryable}: ${error.message}")
        if (error.retryable) onTransportFailureLocked(error, 1000, "server error ${error.code}")
        else failLocked(error, 1000, "server error ${error.code}")
    }

    private fun onRemoteClose(id: Int, code: Int, reason: String) {
        synchronized(lock) {
            if (id != connId || state.isTerminal || state == SessionState.RECONNECTING) return
            when {
                code == 1000 ->
                    failLocked(YuguErrors.local(90005, "服务端在终评前关闭了连接，code=1000 $reason".trim()), code, reason)
                code in PROTOCOL_CLOSE_CODES || code in 4000..4999 ->
                    // the peer rejected what it received, a new connection would be rejected the same way
                    failLocked(YuguErrors.local(90005, "连接因协议错误关闭，code=$code $reason".trim()), code, reason)
                else ->
                    onTransportFailureLocked(YuguErrors.local(90001, "连接异常关闭，code=$code $reason".trim()), code, reason)
            }
        }
    }

    private fun onTransportFailure(id: Int, error: YuguException, code: Int, reason: String) {
        synchronized(lock) {
            if (id != connId || state.isTerminal || state == SessionState.RECONNECTING) return
            onTransportFailureLocked(error, code, reason)
        }
    }

    private fun onHandshakeTimeout(id: Int, opened: Boolean) {
        synchronized(lock) {
            if (id != connId || (state != SessionState.CONNECTING && state != SessionState.CONNECTED)) return
            val message = if (opened) {
                "连接打开后 ${cfg.connectTimeoutMs} ms 内未收到开始帧"
            } else {
                "${cfg.connectTimeoutMs * OPEN_TIMEOUT_FACTOR} ms 内未建立连接"
            }
            onTransportFailureLocked(YuguErrors.local(90002, message), 1000, "handshake timeout")
        }
    }

    private fun onResultTimeout(id: Int) {
        synchronized(lock) {
            if (id != connId || state != SessionState.ENDING) return
            onTransportFailureLocked(YuguErrors.local(90007, "结束后 $resultTimeoutMs ms 未收到终评"), 1000, "result timeout")
        }
    }

    /** Decides between reconnect and FAILED. Caller holds [lock]. */
    private fun onTransportFailureLocked(error: YuguException, code: Int, reason: String) {
        if (state.isTerminal) return
        cancelTimersLocked()
        abandonSocketLocked()
        if (reconnecting) {
            val failedAttempt = consecutiveAttempts
            post { cfg.eventListener.onReconnect(sessionId, failedAttempt, false) }
        }
        val fatal: YuguException? = when {
            !error.retryable -> error
            bufferPolicy == AudioBufferPolicy.FAIL -> error
            !reconnect.enabled -> error
            idempotencyKey == null -> {
                log.d("[$sessionId] 没有幂等键，不重连，避免重复计费")
                error
            }
            bufferPolicy == AudioBufferPolicy.DROP && endSent -> error
            bufferPolicy == AudioBufferPolicy.REPLAY && replayOverflow ->
                YuguErrors.local(90008, "重放缓冲超过 ${cfg.maxReplayBytes} 字节，无法重连", error)
            consecutiveAttempts >= reconnect.maxAttempts ->
                YuguErrors.local(90006, "连续重连 ${reconnect.maxAttempts} 次仍失败：${error.message}", error)
            reconnectAttempts >= reconnect.maxTotalAttempts ->
                YuguErrors.local(90006, "本次会话已重连 $reconnectAttempts 次，达到上限：${error.message}", error)
            else -> null
        }
        if (fatal != null) {
            failLocked(fatal, code, reason)
            return
        }
        consecutiveAttempts++
        reconnectAttempts++
        val attempt = consecutiveAttempts
        val delay = reconnect.delayMs(attempt, cfg.random)
        if (!reconnecting) {
            reconnecting = true
            droppedBytes = 0
        }
        endSent = false
        transitionLocked(SessionState.RECONNECTING)
        log.w("[$sessionId] reconnect $attempt/${reconnect.maxAttempts} in $delay ms: ${HttpEngine.describe(error)} ${error.message}")
        post { listener.onReconnecting(attempt, delay, error) }
        reconnectTimer = schedule(delay) {
            synchronized(lock) {
                if (state == SessionState.RECONNECTING) {
                    transitionLocked(SessionState.CONNECTING)
                    connectLocked()
                }
            }
        }
    }

    private fun failLocked(error: YuguException, code: Int, reason: String) {
        if (state.isTerminal) return
        cancelTimersLocked()
        error.withContext(idempotencyKey, reconnectAttempts + 1)
        log.w("[$sessionId] failed: $error")
        transitionLocked(SessionState.FAILED)
        post { listener.onError(error) }
        closeLocked(code, reason)
    }

    /** Tears the transport down and announces CLOSED, always the last callback. */
    private fun closeLocked(code: Int, reason: String) {
        val socket = ws
        abandonSocketLocked(closeGracefully = socket != null)
        queue.clear()
        replay.clear()
        transitionLocked(SessionState.CLOSED)
        post { listener.onClosed(code, reason) }
        lifecycle.unregister(this)
    }

    private fun abandonSocketLocked(closeGracefully: Boolean = false) {
        val socket = ws ?: return
        ws = null
        connId++ // late callbacks of the old socket are ignored
        if (closeGracefully && state.isTerminal) {
            try {
                socket.close(1000, null)
            } catch (e: RuntimeException) {
                socket.cancel()
            }
            schedule(CLOSE_GRACE_MS) { socket.cancel() }
        } else {
            socket.cancel()
        }
    }

    private fun cancelTimersLocked() {
        handshakeTimer?.cancel(false)
        resultTimer?.cancel(false)
        reconnectTimer?.cancel(false)
        handshakeTimer = null
        resultTimer = null
        reconnectTimer = null
    }

    private fun transitionLocked(next: SessionState) {
        val old = state
        if (old == next) return
        state = next
        log.d { "[$sessionId] $old -> $next" }
        post {
            publicState = next
            listener.onStateChanged(old, next)
            cfg.eventListener.onSessionStateChanged(sessionId, old, next)
        }
    }

    private fun post(block: () -> Unit) {
        dispatcher.execute {
            try {
                block()
            } catch (t: Throwable) {
                log.e("[$sessionId] 回调抛出异常，已忽略", t)
            }
        }
    }

    private fun schedule(delayMs: Long, block: () -> Unit): ScheduledFuture<*> =
        Shared.scheduler.schedule({
            try {
                block()
            } catch (t: Throwable) {
                log.e("[$sessionId] 定时任务异常", t)
            }
        }, delayMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)

    internal companion object {
        /** Bound for connect and upgrade, in units of connectTimeoutMs: two addresses plus the upgrade. */
        const val OPEN_TIMEOUT_FACTOR = 3L

        /** Largest binary frame sent, the platform closes frames above 128 KB with 1009. */
        const val MAX_FRAME_BYTES = 32_000

        /** Close codes that end a session at once with 90005 (DESIGN 2.5), besides 4000 to 4999. */
        val PROTOCOL_CLOSE_CODES = setOf(1002, 1003, 1007, 1008, 1009, 1010)

        const val END_FRAME = "{\"cmd\":\"end\"}"
        const val STREAM_SAMPLE_RATE = 16_000
        const val CLOSE_GRACE_MS = 3_000L
    }
}
