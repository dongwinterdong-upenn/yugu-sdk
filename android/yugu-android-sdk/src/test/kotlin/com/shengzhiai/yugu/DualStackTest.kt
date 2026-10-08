package com.shengzhiai.yugu

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.ByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Random
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Dual-stack hosts: the first address refuses connections (IPv6 ::1, nothing listens there) and the
 * second works (IPv4 127.0.0.1). This is what phones with broken IPv6 see. OkHttp must fall back to
 * the next address inside one SDK attempt, without burning an SDK retry, while requests whose
 * sending started are only ever re-sent by the SDK itself (counted, same key, fresh nonce).
 */
class DualStackTest {

    private lateinit var server: MockWebServer
    private val logger = CapturingLogger()
    private val events = RecordingEvents()
    private val wav = TestEnv.fixtureBytes("audio/zh_short.wav")
    private val pcm = TestEnv.fixturePcm("audio/zh_short.wav")
    private val config = EvaluateConfig(CoreType.SENTENCE, "今天天气很好", Language.ZH_CN)
    private val ok by lazy { TestEnv.fixtureText("platform/native_evaluate_sentence_zh.json") }
    private val resultFrame by lazy { "{\"event\":\"result\"," + ok.trim().removePrefix("{") }

    /** Resolves [HOST] to a refusing IPv6 address first and the working IPv4 address second. */
    private val ipv6First = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            if (hostname == HOST) listOf(InetAddress.getByName("::1"), InetAddress.getByName("127.0.0.1")) else Dns.SYSTEM.lookup(hostname)
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0) // IPv4 only
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /** Every request OkHttp writes to a connection, including any resend OkHttp makes on its own. */
    private val sent = CopyOnWriteArrayList<Request>()

    private fun builder(host: String = HOST) = YuguClient.builder()
        .baseUrl("http://$host:${server.port}")
        .wsBaseUrl("ws://$host:${server.port}")
        .auth(Auth.appKey("mock-app-key", "mock-secret-key"))
        .okHttpClient(
            OkHttpClient.Builder()
                .dns(ipv6First)
                .addNetworkInterceptor { chain -> sent.add(chain.request()); chain.proceed(chain.request()) }
                .build(),
        )
        .logger(logger)
        .logLevel(LogLevel.DEBUG)
        .eventListener(events)
        .random(Random(3))
        .callbackExecutor(TestEnv.callbackThread())

    @Test
    fun refusedIpv6FallsBackToIpv4WithinOneAttempt() {
        server.enqueue(MockResponse().setBody(ok))
        builder().build().use { c ->
            val r = c.evaluate(config, wav)
            assertEquals("eval_3fb45f4c8e71", r.recordId)
        }
        assertEquals(listOf("evaluate POST /api/v1/evaluate 1"), events.starts)
        assertTrue("no SDK retry: ${events.retries}", events.retries.isEmpty())
        assertEquals(listOf("evaluate 200 1 null"), events.ends)
        assertEquals(1, server.requestCount)
        assertEquals("sent once, the refused address was only a connect attempt", 1, sent.size)
        val req = server.takeRequest()
        assertTrue(req.getHeader("Idempotency-Key")!!.matches(Regex("[0-9a-f]{32}")))
    }

    @Test
    fun reportTtsAndCompatFallBackToo() {
        server.enqueue(MockResponse().setBody("""{"code":0,"data":{"overall":90}}"""))
        server.enqueue(MockResponse().setBody(TestEnv.fixtureText("platform/tts_generate.json")))
        server.enqueue(MockResponse().setBody(TestEnv.fixtureText("platform/compat_sent.eval.cn.json")))
        builder().build().use { c ->
            assertEquals(90.0, c.getReport("eval_1").overall!!, 0.0)
            assertEquals("mp3", c.tts(TtsRequest("你好")).format)
            assertEquals("sent.eval.cn", c.evaluateCompat(CompatConfig(CompatCoreType.SENT_EVAL_CN, "今天天气很好"), wav).coreType)
        }
        assertEquals(3, events.starts.size)
        assertTrue(events.retries.isEmpty())
        assertEquals(3, server.requestCount)
    }

    private fun wsPeer() = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send("""{"event":"connected"}""")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            webSocket.send(if (text.contains("\"cmd\":\"end\"")) resultFrame else """{"event":"started"}""")
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {}

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }
    }

    private fun streamOnce(c: YuguClient): RecordingListener {
        val l = RecordingListener()
        val s = c.streamEvaluate(config, l)
        l.session = s
        streamPcm(s, pcm)
        s.end()
        assertTrue(l.awaitClosed(15))
        assertTrue("violations ${l.violations}", l.violations.isEmpty())
        assertNotNull("error ${l.error}", l.result)
        assertTrue("no reconnect: ${l.reconnecting.map { it.second }}", l.reconnecting.isEmpty())
        assertEquals(0, s.getReconnectCount())
        return l
    }

    @Test
    fun websocketHandshakeFallsBackToIpv4() {
        server.enqueue(MockResponse().withWebSocketUpgrade(wsPeer()))
        builder().build().use { streamOnce(it) }
        assertEquals(1, server.requestCount)
    }

    /**
     * A listener on [::1]:port whose accept queue is full drops new SYNs, so connecting there times
     * out like a silent IPv6 path. Returns null when the platform cannot build that (no IPv6 sockets).
     */
    private fun silentIpv6(port: Int): Closeable? {
        val v6 = InetAddress.getByName("::1")
        val listener = try {
            ServerSocket(port, 1, v6)
        } catch (e: IOException) {
            return null
        }
        val fillers = ArrayList<Socket>()
        repeat(4) {
            val f = Socket()
            try {
                f.connect(InetSocketAddress(v6, port), 300)
                fillers.add(f)
            } catch (e: IOException) {
                f.close()
            }
        }
        val silent = Socket().use { probe ->
            try {
                probe.connect(InetSocketAddress(v6, port), 500)
                false
            } catch (e: SocketTimeoutException) {
                true
            } catch (e: IOException) {
                false
            }
        }
        val hole = Closeable {
            fillers.forEach { it.close() }
            listener.close()
        }
        if (!silent) {
            hole.close()
            return null
        }
        return hole
    }

    @Test
    fun silentIpv6CostsOneConnectTimeoutThenIpv4Works() {
        val hole = silentIpv6(server.port)
        Assume.assumeTrue("IPv6 loopback with a full accept queue is not available here", hole != null)
        hole!!.use {
            server.enqueue(MockResponse().setBody(ok))
            server.enqueue(MockResponse().withWebSocketUpgrade(wsPeer()))
            builder().connectTimeoutMs(1000).build().use { c ->
                val t0 = System.nanoTime()
                c.evaluate(config, wav)
                val restMs = (System.nanoTime() - t0) / 1_000_000
                assertTrue("REST waited for the IPv6 connect timeout before IPv4: $restMs ms", restMs >= 900)
                assertEquals(1, events.starts.size)
                assertTrue(events.retries.isEmpty())
                // the WebSocket handshake bound leaves room for the same fallback
                val w0 = System.nanoTime()
                streamOnce(c)
                assertTrue((System.nanoTime() - w0) / 1_000_000 >= 900)
            }
        }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun okHttpNeverResendsAPostWhoseSendingStarted() {
        // The server closes the connection after the first response. The second call goes out on the
        // stale pooled connection and fails after sending: OkHttp must not re-send the POST on its own,
        // the SDK retries it as a counted attempt with the same key and a fresh nonce.
        server.enqueue(MockResponse().setBody(ok).setSocketPolicy(SocketPolicy.DISCONNECT_AT_END))
        server.enqueue(MockResponse().setBody(ok))
        builder(host = "127.0.0.1").build().use { c ->
            c.evaluate(config, wav)
            Thread.sleep(200) // let the server-side close arrive
            val second = c.evaluate(config, wav)
            assertNotNull(second.recordId)
        }
        assertEquals(
            listOf("evaluate POST /api/v1/evaluate 1", "evaluate POST /api/v1/evaluate 1", "evaluate POST /api/v1/evaluate 2"),
            events.starts,
        )
        assertEquals("the stale attempt is an SDK retry", 1, events.retries.size)
        assertTrue(logger.text().contains("NETWORK code=90001"))
        // first call: 1 send; second call: the stale send plus the SDK retry, nothing re-sent by OkHttp
        assertEquals(3, sent.size)
        val (stale, retry) = sent[1] to sent[2]
        assertEquals(stale.header("Idempotency-Key"), retry.header("Idempotency-Key"))
        assertNotEquals(stale.header("X-Nonce"), retry.header("X-Nonce"))
        assertEquals(2, server.requestCount)
    }

    private companion object {
        const val HOST = "dualstack.yugu.test"
    }
}
