package com.shengzhiai.yugu

import com.shengzhiai.yugu.audio.WavFormat
import com.shengzhiai.yugu.internal.JObj
import com.shengzhiai.yugu.internal.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assume
import java.io.BufferedReader
import java.io.Closeable
import java.io.File
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Repository paths and fixtures. */
object TestEnv {
    val repoRoot: File = File(
        System.getProperty("yugu.repoRoot")
            ?: generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "spec/errors.json").isFile }.path,
    )

    fun fixture(rel: String): File = File(repoRoot, "spec/fixtures/$rel")
    fun fixtureText(rel: String): String = fixture(rel).readText(Charsets.UTF_8)
    fun fixtureBytes(rel: String): ByteArray = fixture(rel).readBytes()

    /** PCM of a fixture WAV. */
    fun fixturePcm(rel: String): ByteArray = WavFormat.pcmData(fixtureBytes(rel))!!

    val nodeBinary: String? by lazy {
        System.getProperty("yugu.node")?.takeIf { File(it).canExecute() }
            ?: (System.getenv("PATH") ?: "").split(File.pathSeparator).map { File(it, "node") }.firstOrNull { it.canExecute() }?.path
            ?: File(System.getProperty("user.home"), ".local/node/bin/node").takeIf { it.canExecute() }?.path
    }

    val requireMock: Boolean get() = System.getProperty("yugu.requireMock").let { it == "1" || it == "true" }

    /** One serial callback thread, like the Android main thread. */
    fun callbackThread(): Executor = Executors.newSingleThreadExecutor { r -> Thread(r, "test-callback").apply { isDaemon = true } }
}

/** Logger that keeps every line, for redaction and retry log assertions. */
class CapturingLogger : YuguLogger {
    val lines: MutableList<String> = CopyOnWriteArrayList()

    override fun log(level: LogLevel, tag: String, message: String, error: Throwable?) {
        lines.add("$level $tag $message" + (error?.let { " | $it" } ?: ""))
    }

    fun text(): String = lines.joinToString("\n")
}

/** Records EventListener calls. */
class RecordingEvents : YuguEventListener {
    val starts: MutableList<String> = CopyOnWriteArrayList()
    val ends: MutableList<String> = CopyOnWriteArrayList()
    val retries: MutableList<Pair<Int, Long>> = CopyOnWriteArrayList()
    val sessionStates: MutableList<String> = CopyOnWriteArrayList()
    val reconnects: MutableList<String> = CopyOnWriteArrayList()

    override fun onRequestStart(op: String, method: String, path: String, attempt: Int) {
        starts.add("$op $method $path $attempt")
    }

    override fun onRequestEnd(op: String, httpStatus: Int, latencyMs: Long, attempts: Int, error: YuguException?) {
        ends.add("$op $httpStatus $attempts ${error?.code}")
    }

    override fun onRetry(op: String, attempt: Int, delayMs: Long, error: YuguException) {
        retries.add(attempt to delayMs)
    }

    override fun onSessionStateChanged(sessionId: String, oldState: SessionState, newState: SessionState) {
        sessionStates.add("$oldState->$newState")
    }

    override fun onReconnect(sessionId: String, attempt: Int, succeeded: Boolean) {
        reconnects.add("$attempt:$succeeded")
    }
}

/**
 * Stream listener that records events and checks the listener guarantees: no concurrent
 * callbacks, getState() equal to the announced state, onClosed last, one terminal callback.
 */
class RecordingListener : StreamListener {
    @Volatile
    var session: StreamSession? = null
    val events: MutableList<String> = CopyOnWriteArrayList()
    val violations: MutableList<String> = CopyOnWriteArrayList()
    val reconnecting: MutableList<Pair<Int, YuguException>> = CopyOnWriteArrayList()
    val reconnected: MutableList<Pair<Int, Long>> = CopyOnWriteArrayList()
    val partials: MutableList<PartialResult> = CopyOnWriteArrayList()
    val warnings: MutableList<LocalWarning> = CopyOnWriteArrayList()

    @Volatile
    var result: EvalResult? = null

    @Volatile
    var error: YuguException? = null

    @Volatile
    var closedCode: Int = -1

    @Volatile
    var closedReason: String? = null
    val started = CountDownLatch(1)
    val closed = CountDownLatch(1)
    private val inCallback = AtomicInteger()
    private val terminalCount = AtomicInteger()

    private inline fun cb(name: String, block: () -> Unit) {
        if (inCallback.incrementAndGet() > 1) violations.add("concurrent callback $name")
        if (closed.count == 0L) violations.add("$name after onClosed")
        try {
            block()
        } finally {
            inCallback.decrementAndGet()
        }
    }

    override fun onStateChanged(oldState: SessionState, newState: SessionState) = cb("state") {
        events.add("state:$oldState->$newState")
        session?.let { if (it.getState() != newState) violations.add("getState()=${it.getState()} inside onStateChanged($newState)") }
    }

    override fun onConnected() = cb("connected") { events.add("connected") }

    override fun onStarted() = cb("started") {
        events.add("started")
        started.countDown()
    }

    override fun onPartial(partial: PartialResult) = cb("partial") {
        partials.add(partial)
        events.add("partial")
    }

    override fun onReconnecting(attempt: Int, delayMs: Long, cause: YuguException) = cb("reconnecting") {
        reconnecting.add(attempt to cause)
        events.add("reconnecting:$attempt")
    }

    override fun onReconnected(attempt: Int, droppedBytes: Long) = cb("reconnected") {
        reconnected.add(attempt to droppedBytes)
        events.add("reconnected:$attempt")
    }

    override fun onWarning(warning: LocalWarning) = cb("warning") {
        warnings.add(warning)
        events.add("warning:${warning.code}")
    }

    override fun onResult(result: EvalResult) = cb("result") {
        if (terminalCount.incrementAndGet() > 1) violations.add("second terminal callback")
        session?.let { if (it.getState() != SessionState.COMPLETED) violations.add("state ${it.getState()} in onResult") }
        this.result = result
        events.add("result")
    }

    override fun onError(error: YuguException) = cb("error") {
        if (terminalCount.incrementAndGet() > 1) violations.add("second terminal callback")
        this.error = error
        events.add("error:${error.code}")
    }

    override fun onClosed(code: Int, reason: String) = cb("closed") {
        closedCode = code
        closedReason = reason
        events.add("closed:$code")
        closed.countDown()
    }

    fun awaitClosed(seconds: Long): Boolean = closed.await(seconds, TimeUnit.SECONDS)

    /** State sequence without the "state:" prefix. */
    fun states(): List<String> = events.filter { it.startsWith("state:") }.map { it.removePrefix("state:") }

    fun terminalCallbacks(): Int = terminalCount.get()
}

/** The Node mock platform (tools/mock-server), started per test class. */
internal class MockPlatform private constructor(private val process: Process, val port: Int) : Closeable {
    val baseUrl: String get() = "http://127.0.0.1:$port"
    val wsUrl: String get() = "ws://127.0.0.1:$port"
    private val http = OkHttpClient()

    fun reset() {
        post("/__mock/reset", "{}")
    }

    /** Queues faults, each pair is path prefix to fault. */
    fun queueFaults(vararg faults: Pair<String, String>) {
        val list = faults.map { mapOf("match" to it.first, "fault" to it.second) }
        post("/__mock/faults", Json.write(mapOf("faults" to list)))
    }

    fun setProcessingMs(ms: Int) {
        post("/__mock/faults", Json.write(mapOf("faults" to emptyList<Any>(), "processingMs" to ms)))
    }

    @Suppress("UNCHECKED_CAST")
    fun log(): List<JObj> = (Json.parse(get("/__mock/log")) as List<Any?>).map { JObj(it as Map<String, Any?>) }

    fun billing(): JObj = JObj.parseOrNull(get("/__mock/billing"))!!

    private fun get(path: String): String =
        http.newCall(Request.Builder().url(baseUrl + path).build()).execute().use { it.body!!.string() }

    private fun post(path: String, json: String): String =
        http.newCall(Request.Builder().url(baseUrl + path).post(json.toRequestBody("application/json".toMediaType())).build())
            .execute().use { it.body!!.string() }

    override fun close() {
        process.destroy()
        if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
    }

    companion object {
        /** Starts the mock or skips the test class when node is missing and not required. */
        fun start(processingMs: Int = 50, env: Map<String, String> = emptyMap()): MockPlatform {
            val node = TestEnv.nodeBinary
            val server = File(TestEnv.repoRoot, "tools/mock-server/server.mjs")
            val modules = File(TestEnv.repoRoot, "tools/mock-server/node_modules/ws")
            val available = node != null && server.isFile && modules.isDirectory
            if (TestEnv.requireMock && !available) {
                throw AssertionError("mock server required but unavailable: node=$node server=$server ws=$modules")
            }
            Assume.assumeTrue("node or tools/mock-server/node_modules missing", available)
            val pb = ProcessBuilder(node, server.path, "--port", "0", "--processing-ms", processingMs.toString())
                .redirectErrorStream(true)
            pb.environment().putAll(env)
            val p = pb.start()
            Runtime.getRuntime().addShutdownHook(Thread { if (p.isAlive) p.destroyForcibly() })
            val reader = BufferedReader(InputStreamReader(p.inputStream, Charsets.UTF_8))
            val line = reader.readLine() ?: throw AssertionError("mock server printed nothing")
            val port = JObj.parseOrNull(line)?.int("port") ?: throw AssertionError("unexpected mock output: $line")
            Thread({
                try {
                    while (reader.readLine() != null) { /* drain */ }
                } catch (_: Exception) {
                }
            }, "mock-stdout").apply { isDaemon = true }.start()
            return MockPlatform(p, port)
        }
    }
}

/**
 * TCP proxy between the SDK and the mock. Simulates a stalled network (BLACKHOLE: nothing is
 * forwarded, new connections hang), a network that is down (REFUSE: new connections are reset at
 * once, like Android does while the interface is gone) and a network switch (reset every connection).
 */
class ChaosProxy(private val targetPort: Int) : Closeable {
    enum class Mode { FORWARD, BLACKHOLE, REFUSE }

    /** Back to FORWARD after a drop: connections that lived through the drop are dead, as on a real network. */
    @Volatile
    var mode = Mode.FORWARD
        set(value) {
            val wasDropped = field == Mode.BLACKHOLE
            field = value
            if (value == Mode.FORWARD && wasDropped) {
                resetAll()
                closeHeld()
            }
        }
    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    val port: Int get() = server.localPort
    private val active: MutableSet<Socket> = Collections.synchronizedSet(HashSet())
    private val held: MutableList<Socket> = Collections.synchronizedList(ArrayList())

    @Volatile
    private var running = true

    init {
        Thread({ acceptLoop() }, "chaos-accept").apply { isDaemon = true }.start()
    }

    private fun acceptLoop() {
        while (running) {
            val client = try {
                server.accept()
            } catch (e: Exception) {
                return
            }
            if (mode == Mode.BLACKHOLE) {
                held.add(client)
                continue
            }
            if (mode == Mode.REFUSE) {
                try {
                    client.setSoLinger(true, 0)
                } catch (_: Exception) {
                }
                closeQuietly(client)
                continue
            }
            try {
                val upstream = Socket(InetAddress.getLoopbackAddress(), targetPort)
                active.add(client)
                active.add(upstream)
                pump(client, upstream)
                pump(upstream, client)
            } catch (e: Exception) {
                client.close()
            }
        }
    }

    private fun pump(from: Socket, to: Socket) {
        Thread({
            val buf = ByteArray(8192)
            try {
                val input = from.getInputStream()
                val output = to.getOutputStream()
                while (running) {
                    if (mode == Mode.BLACKHOLE) {
                        Thread.sleep(20)
                        continue
                    }
                    val n = input.read(buf)
                    if (n < 0) break
                    if (mode == Mode.BLACKHOLE) continue // lost in transit
                    output.write(buf, 0, n)
                    output.flush()
                }
            } catch (_: Exception) {
            } finally {
                closeQuietly(from)
                closeQuietly(to)
            }
        }, "chaos-pump").apply { isDaemon = true }.start()
    }

    /** Network gone: open connections are reset and new ones fail until [up]. */
    fun down() {
        mode = Mode.REFUSE
        resetAll()
    }

    /** Network back. */
    fun up() {
        mode = Mode.FORWARD
    }

    /** Network switch: every open connection is reset. */
    fun resetAll() {
        val all = synchronized(active) { ArrayList(active).also { active.clear() } }
        for (s in all) {
            try {
                s.setSoLinger(true, 0)
            } catch (_: Exception) {
            }
            closeQuietly(s)
        }
    }

    private fun closeHeld() {
        val all = synchronized(held) { ArrayList(held).also { held.clear() } }
        all.forEach { closeQuietly(it) }
    }

    private fun closeQuietly(s: Socket) {
        try {
            s.close()
        } catch (_: Exception) {
        }
        active.remove(s)
    }

    override fun close() {
        running = false
        try {
            server.close()
        } catch (_: Exception) {
        }
        resetAll()
        closeHeld()
    }
}

/** Sends PCM in 640-byte frames with a pause between frames, like a live microphone. */
fun streamPcm(session: StreamSession, pcm: ByteArray, frameDelayMs: Long = 0, onFrame: ((Int) -> Unit)? = null): Int {
    var i = 0
    var frames = 0
    while (i < pcm.size) {
        val n = minOf(640, pcm.size - i)
        session.sendAudio(pcm, i, n)
        i += n
        frames++
        onFrame?.invoke(frames)
        if (frameDelayMs > 0) Thread.sleep(frameDelayMs)
    }
    return frames
}
