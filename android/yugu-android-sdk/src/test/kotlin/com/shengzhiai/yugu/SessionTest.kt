package com.shengzhiai.yugu

import com.shengzhiai.yugu.internal.JObj
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Random
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Session state machine, frames, handshake and buffer policies against a scripted WS peer. */
class SessionTest {

    private lateinit var server: MockWebServer
    private val logger = CapturingLogger()
    private val events = RecordingEvents()
    private val pcm = TestEnv.fixturePcm("audio/zh_short.wav")
    private val config = EvaluateConfig(CoreType.SENTENCE, "今天天气很好", Language.ZH_CN)
    private val resultFrame by lazy {
        val r = TestEnv.fixtureText("platform/native_evaluate_sentence_zh.json")
        "{\"event\":\"result\"," + r.trim().removePrefix("{")
    }
    private val fast = ReconnectPolicy(maxAttempts = 3, initialDelayMs = 30, maxDelayMs = 60, jitter = 0.0)

    /** Scripted server side of one WebSocket connection. */
    open inner class Peer(
        private val greet: Boolean = true,
        private val answerStart: Boolean = true,
        val onEnd: (Peer) -> Unit = { it.send(resultFrame) },
        val onBinary: (Peer, Int) -> Unit = { _, _ -> },
        val onStart: (Peer) -> Unit = {},
    ) : WebSocketListener() {
        @Volatile
        lateinit var ws: WebSocket
        val texts: MutableList<String> = CopyOnWriteArrayList()
        val audio = ByteArrayOutputStream()
        val opened = CountDownLatch(1)
        val closed = CountDownLatch(1)

        @Volatile
        var closeCode = -1

        fun send(text: String) {
            ws.send(text)
        }

        fun bytes(): ByteArray = synchronized(audio) { audio.toByteArray() }

        override fun onOpen(webSocket: WebSocket, response: Response) {
            ws = webSocket
            opened.countDown()
            if (greet) webSocket.send("""{"event":"connected","message":"stream channel ready"}""")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            texts.add(text)
            when {
                text.contains("\"cmd\":\"end\"") -> onEnd(this)
                texts.size == 1 -> {
                    onStart(this)
                    if (answerStart) webSocket.send("""{"event":"started"}""")
                }
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            val n = synchronized(audio) {
                audio.write(bytes.toByteArray())
                audio.size()
            }
            onBinary(this, n)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            closeCode = code
            webSocket.close(1000, null)
            closed.countDown()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            closed.countDown()
        }
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun builder() = YuguClient.builder()
        .baseUrl(server.url("/").toString())
        .wsBaseUrl("ws://127.0.0.1:${server.port}")
        .auth(Auth.appKey("mock-app-key", "mock-secret-key"))
        .logger(logger)
        .logLevel(LogLevel.DEBUG)
        .eventListener(events)
        .reconnectPolicy(fast)
        .random(Random(11))
        .callbackExecutor(TestEnv.callbackThread())

    private fun peer(p: Peer): Peer {
        server.enqueue(MockResponse().withWebSocketUpgrade(p))
        return p
    }

    private fun open(c: YuguClient, l: RecordingListener, options: SessionOptions = SessionOptions.DEFAULT): StreamSession {
        val s = c.streamEvaluate(config, l, options)
        l.session = s
        return s
    }

    private fun assertClean(l: RecordingListener) {
        assertTrue("violations: ${l.violations}", l.violations.isEmpty())
        assertEquals("closed must be last: ${l.events}", "closed", l.events.last().substringBefore(':'))
    }

    @Test
    fun normalSessionEventOrderFramesAndSignedHandshake() {
        val p = peer(Peer())
        builder().build().use { c ->
            val l = RecordingListener()
            val s = open(c, l)
            assertTrue(l.started.await(5, TimeUnit.SECONDS))
            streamPcm(s, pcm)
            s.end()
            assertTrue(l.awaitClosed(10))
            assertEquals(
                listOf(
                    "state:IDLE->CONNECTING", "state:CONNECTING->CONNECTED", "connected", "state:CONNECTED->STARTED",
                    "started", "state:STARTED->ENDING", "state:ENDING->COMPLETED", "result", "state:COMPLETED->CLOSED", "closed:1000",
                ),
                l.events,
            )
            assertClean(l)
            assertEquals(SessionState.CLOSED, s.getState())
            assertFalse(s.isActive())
            assertEquals(0, s.getReconnectCount())
            val r = l.result!!
            assertEquals("eval_3fb45f4c8e71", r.recordId)
            assertEquals(s.idempotencyKey, r.idempotencyKey)
            assertArrayEquals(pcm, p.bytes())
            // start frame
            val start = JObj.parseOrNull(p.texts[0])!!
            assertEquals("start", start.str("cmd"))
            assertEquals("sentence", start.str("coreType"))
            assertEquals("今天天气很好", start.str("referenceText"))
            assertEquals("zh-CN", start.str("language"))
            assertEquals(s.idempotencyKey, start.str("idempotencyKey"))
            assertTrue(s.idempotencyKey!!.matches(Regex("[0-9a-f]{32}")))
            assertEquals("{\"cmd\":\"end\"}", p.texts.last())
            // handshake query is signed over every parameter except signature
            val url = ("http://x" + server.takeRequest().path).toHttpUrl()
            assertEquals("/api/v1/ws/evaluate", url.encodedPath)
            val q = url.queryParameterNames.associateWith { url.queryParameter(it)!! }
            assertEquals(setOf("idempotencyKey", "appKey", "timestamp", "nonce", "signature"), q.keys)
            assertEquals(s.idempotencyKey, q["idempotencyKey"])
            assertEquals(Signer.sign(q - "signature", "mock-secret-key"), q["signature"])
            assertEquals(1000, p.closeCode)
            assertEquals(
                listOf("IDLE->CONNECTING", "CONNECTING->CONNECTED", "CONNECTED->STARTED", "STARTED->ENDING", "ENDING->COMPLETED", "COMPLETED->CLOSED"),
                events.sessionStates,
            )
            assertEquals(0, c.activeSessionCount())
        }
    }

    @Test
    fun audioSentBeforeStartedIsQueuedAndFlushedInOrder() {
        val p = peer(Peer())
        builder().build().use { c ->
            val l = RecordingListener()
            val s = open(c, l)
            streamPcm(s, pcm) // before started
            s.end() // also before started: sent after the flush
            assertTrue(l.awaitClosed(10))
            assertClean(l)
            assertArrayEquals(pcm, p.bytes())
            assertNotNull(l.result)
        }
    }

    @Test
    fun compatSessionWithPartialFrames() {
        val compatResult = TestEnv.fixtureText("platform/compat_sent.eval.cn.json")
        val p = peer(Peer(onBinary = { peer, n -> if (n % 16000 < 640) peer.send("""{"eof":0,"result":{"bytes":$n}}""") }, onEnd = { it.send(compatResult) }))
        builder().build().use { c ->
            val l = RecordingListener()
            val s = c.streamEvaluateCompat(CompatConfig(CompatCoreType.SENT_EVAL_CN, "今天天气很好", Language.ZH_CN, realtimeFeedback = true), l)
            l.session = s
            assertTrue(l.started.await(5, TimeUnit.SECONDS))
            streamPcm(s, pcm)
            s.end()
            assertTrue(l.awaitClosed(10))
            assertClean(l)
            assertTrue(l.partials.isNotEmpty())
            assertTrue(l.partials[0].bytes!! >= 16000)
            assertEquals("sent.eval.cn", l.result!!.coreType)
            val param = JObj.parseOrNull(p.texts[0])!!
            assertEquals("今天天气很好", param.str("refText"))
            assertEquals(true, param.bool("realtime_feedback"))
            assertEquals(s.idempotencyKey, param.str("idempotencyKey"))
            assertNull(param.str("cmd"))
            assertEquals("/sent.eval.cn", ("http://x" + server.takeRequest().path).toHttpUrl().encodedPath)
        }
    }

    @Test
    fun duplicateStartedAndUnknownFramesAreIgnored() {
        peer(Peer(onStart = { it.send("""{"event":"started"}""") }, onEnd = {
            it.send("""{"event":"pong","ts":1}""")
            it.send("""{"something":"else"}""")
            it.send(resultFrame)
        }))
        builder().build().use { c ->
            val l = RecordingListener()
            val s = open(c, l)
            streamPcm(s, pcm)
            s.end()
            assertTrue(l.awaitClosed(10))
            assertClean(l)
            assertEquals(1, l.events.count { it == "started" })
            assertNotNull(l.result)
        }
    }

    @Test
    fun nonRetryableErrorFrameFailsWithoutReconnect() {
        peer(Peer(onEnd = { it.send("""{"event":"error","code":40001,"message":"referenceText 不能为空"}""") }))
        builder().build().use { c ->
            val l = RecordingListener()
            val s = open(c, l)
            streamPcm(s, pcm)
            s.end()
            assertTrue(l.awaitClosed(10))
            assertClean(l)
            val e = l.error as InvalidParameterException
            assertEquals(40001, e.code)
            assertEquals(s.idempotencyKey, e.idempotencyKey)
            assertTrue(l.reconnecting.isEmpty())
            assertEquals(listOf("STARTED->ENDING", "ENDING->FAILED", "FAILED->CLOSED"), l.states().takeLast(3))
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun errorFrameWithoutCodeIsTerminal() {
        peer(Peer(onEnd = { it.send("""{"event":"error","message":"no audio"}""") }))
        builder().build().use { c ->
            val l = RecordingListener()
            val s = open(c, l)
            streamPcm(s, pcm)
            s.end()
            assertTrue(l.awaitClosed(10))
            assertEquals(ErrorCategory.UNKNOWN, l.error!!.category)
            assertEquals("no audio", l.error!!.message)
        }
    }

    @Test
    fun retryableErrorFrameReconnectsAndReplaysEverything() {
        val first = peer(Peer(onEnd = { it.send("""{"event":"error","code":50200,"message":"上游评测服务暂不可用"}""") }))
        val second = peer(Peer())
        builder().build().use { c ->
            val l = RecordingListener()
            val s = open(c, l)
            assertTrue(l.started.await(5, TimeUnit.SECONDS))
            streamPcm(s, pcm)
            s.end()
            assertTrue(l.awaitClosed(10))
            assertClean(l)
            assertNotNull(l.result)
            assertEquals(1, l.reconnecting.size)
            assertEquals(50200, l.reconnecting[0].second.code)
            assertEquals(listOf(1 to 0L), l.reconnected)
            assertEquals(1, l.events.count { it == "started" })
            assertEquals(1, l.events.count { it == "connected" })
            assertArrayEquals(pcm, first.bytes())
            assertArrayEquals("replay sends the whole buffer", pcm, second.bytes())
            assertEquals(s.idempotencyKey, JObj.parseOrNull(second.texts[0])!!.str("idempotencyKey"))
            assertEquals("{\"cmd\":\"end\"}", second.texts.last())
            assertEquals(1, s.getReconnectCount())
            val states = l.states()
            assertTrue(states.containsAll(listOf("ENDING->RECONNECTING", "RECONNECTING->CONNECTING", "CONNECTING->CONNECTED", "CONNECTED->STARTED")))
            assertEquals(listOf("1:true"), events.reconnects)
            val q1 = ("http://x" + server.takeRequest().path).toHttpUrl()
            val q2 = ("http://x" + server.takeRequest().path).toHttpUrl()
            assertEquals(q1.queryParameter("idempotencyKey"), q2.queryParameter("idempotencyKey"))
            assertTrue(q1.queryParameter("nonce") != q2.queryParameter("nonce"))
        }
    }

    @Test
    fun serverClose1000BeforeResultIsAFailure() {
        peer(Peer(onEnd = { it.ws.close(1000, "bye") }))
        builder().build().use { c ->
            val l = RecordingListener()
            val s = open(c, l)
            streamPcm(s, pcm)
            s.end()
            assertTrue(l.awaitClosed(10))
            assertClean(l)
            assertTrue(l.error is ProtocolViolationException)
            assertEquals(90005, l.error!!.code)
            assertFalse(l.error!!.retryable)
            assertTrue(l.reconnecting.isEmpty())
            assertEquals(1000, l.closedCode)
        }
    }

    @Test
    fun protocolCloseCodesEndTheSessionAtOnce() {
        val codes = listOf(1002, 1003, 1007, 1008, 1009, 1010, 4000, 4401, 4999)
        builder().build().use { c ->
            for (code in codes) {
                peer(Peer(onEnd = { it.ws.close(code, "rejected") }))
                val l = RecordingListener()
                val s = open(c, l)
                streamPcm(s, pcm)
                s.end()
                assertTrue("close $code", l.awaitClosed(10))
                assertClean(l)
                assertEquals("close $code", 90005, l.error!!.code)
                assertTrue("close $code must not reconnect", l.reconnecting.isEmpty())
                assertEquals(code, l.closedCode)
            }
        }
        assertEquals(codes.size, server.requestCount)
    }

    @Test
    fun goingAwayAndServerErrorClosesReconnect() {
        for (code in listOf(1001, 1011, 3000)) {
            peer(Peer(onEnd = { it.ws.close(code, "x") }))
            peer(Peer())
        }
        builder().build().use { c ->
            repeat(3) {
                val l = RecordingListener()
                val s = open(c, l)
                streamPcm(s, pcm)
                s.end()
                assertTrue(l.awaitClosed(10))
                assertClean(l)
                assertNotNull("error ${l.error}", l.result)
                assertEquals(1, l.reconnecting.size)
            }
        }
    }

    @Test
    fun largeChunksAreSentAsFramesOfAtMost32000Bytes() {
        val sizes = CopyOnWriteArrayList<Int>()
        var last = 0
        val p = peer(Peer(onBinary = { _, n ->
            sizes.add(n - last)
            last = n
        }))
        builder().build().use { c ->
            val l = RecordingListener()
            val s = open(c, l)
            assertTrue(l.started.await(5, TimeUnit.SECONDS))
            val big = ByteArray(100_000) { (it % 251).toByte() }
            assertTrue(s.sendAudio(big))
            s.end()
            assertTrue(l.awaitClosed(10))
            assertClean(l)
            assertEquals(listOf(32_000, 32_000, 32_000, 4_000), sizes)
            assertArrayEquals(big, p.bytes())
        }
    }

    @Test
    fun abnormalCloseReconnects() {
        peer(Peer(onEnd = { it.ws.close(1011, "internal") }))
        peer(Peer())
        builder().build().use { c ->
            val l = RecordingListener()
            val s = open(c, l)
            streamPcm(s, pcm)
            s.end()
            assertTrue(l.awaitClosed(10))
            assertClean(l)
            assertNotNull(l.result)
            assertTrue(l.reconnecting[0].second.message!!.contains("1011"))
        }
    }

    @Test
    fun unparseableFrameIsAProtocolError() {
        peer(Peer(onEnd = { it.send("not json") }))
        builder().build().use { c ->
            val l = RecordingListener()
            val s = open(c, l)
            streamPcm(s, pcm)
            s.end()
            assertTrue(l.awaitClosed(10))
            assertTrue(l.error is ProtocolViolationException)
            assertEquals(90005, l.error!!.code)
        }
    }

    @Test
    fun cancelDeliversNeitherResultNorErrorAndIsIdempotent() {
        val p = peer(Peer(onEnd = { /* never answers */ }))
        builder().build().use { c ->
            val l = RecordingListener()
            val s = open(c, l)
            assertTrue(l.started.await(5, TimeUnit.SECONDS))
            streamPcm(s, pcm)
            s.cancel()
            s.cancel()
            s.close()
            assertTrue(l.awaitClosed(5))
            assertClean(l)
            assertNull(l.result)
            assertNull(l.error)
            assertEquals(0, l.terminalCallbacks())
            assertEquals(listOf("STARTED->CANCELLED", "CANCELLED->CLOSED"), l.states().takeLast(2))
            assertFalse(s.sendAudio(pcm))
            s.end() // no-op
            assertEquals(SessionState.CLOSED, s.getState())
            assertTrue(p.closed.await(5, TimeUnit.SECONDS))
            assertEquals(1000, p.closeCode)
        }
    }

    @Test
    fun audioAfterEndIsRefusedAndEndIsIdempotent() {
        peer(Peer())
        builder().build().use { c ->
            val l = RecordingListener()
            val s = open(c, l)
            assertTrue(l.started.await(5, TimeUnit.SECONDS))
            assertTrue(s.sendAudio(pcm, 0, 0))
            streamPcm(s, pcm)
            s.end()
            s.end()
            assertFalse(s.sendAudio(pcm))
            assertFalse(s.sendAudio(pcm))
            assertTrue(l.awaitClosed(10))
            assertEquals(1, logger.lines.count { it.contains("后续音频被忽略") })
            try {
                s.sendAudio(pcm, 10, pcm.size)
                fail()
            } catch (e: InvalidParameterException) {
                assertEquals(90010, e.code)
            }
        }
    }

    @Test
    fun resultTimeoutWithFailPolicy() {
        peer(Peer(onEnd = { /* silent */ }))
        builder().audioBufferPolicy(AudioBufferPolicy.FAIL).resultTimeoutMs(400).build().use { c ->
            val l = RecordingListener()
            val s = open(c, l)
            streamPcm(s, pcm)
            s.end()
            assertTrue(l.awaitClosed(10))
            assertClean(l)
            val e = l.error as RequestTimeoutException
            assertEquals(90007, e.code)
            assertTrue(e.retryable)
            assertTrue(l.reconnecting.isEmpty())
        }
    }

    @Test
    fun handshakeTimeoutAndReconnectExhaustion() {
        repeat(3) { peer(Peer(greet = false)) }
        builder().connectTimeoutMs(300).reconnectPolicy(fast.copy(maxAttempts = 2)).build().use { c ->
            val l = RecordingListener()
            open(c, l)
            assertTrue(l.awaitClosed(10))
            assertClean(l)
            val e = l.error!!
            assertEquals(90006, e.code)
            assertEquals(ErrorCategory.NETWORK, e.category)
            assertEquals(90002, (e.cause as YuguException).code)
            assertEquals(listOf(1, 2), l.reconnecting.map { it.first })
            assertEquals(3, e.attempts)
            assertEquals(listOf("1:false", "2:false"), events.reconnects)
            assertEquals(3, server.requestCount)
        }
    }

    @Test
    fun startFrameUnansweredTimesOut() {
        peer(Peer(answerStart = false))
        builder().connectTimeoutMs(300).audioBufferPolicy(AudioBufferPolicy.FAIL).build().use { c ->
            val l = RecordingListener()
            open(c, l)
            assertTrue(l.awaitClosed(10))
            assertEquals(90002, l.error!!.code)
            assertEquals(listOf("IDLE->CONNECTING", "CONNECTING->CONNECTED", "CONNECTED->FAILED", "FAILED->CLOSED"), l.states())
        }
    }

    @Test
    fun rejectedHandshakeIsFatal() {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"code":40300,"message":"forbidden"}"""))
        builder().build().use { c ->
            val l = RecordingListener()
            open(c, l)
            assertTrue(l.awaitClosed(10))
            val e = l.error as PermissionException
            assertEquals(403, e.httpStatus)
            assertEquals(40300, e.code)
            assertTrue(l.reconnecting.isEmpty())
            assertEquals(1006, l.closedCode)
        }
    }

    @Test
    fun serviceUnavailableHandshakeIsRetried() {
        server.enqueue(MockResponse().setResponseCode(503))
        peer(Peer())
        builder().build().use { c ->
            val l = RecordingListener()
            val s = open(c, l)
            streamPcm(s, pcm)
            s.end()
            assertTrue(l.awaitClosed(10))
            assertClean(l)
            assertNotNull(l.result)
            assertEquals(503, l.reconnecting[0].second.httpStatus)
            assertEquals(1, l.events.count { it == "connected" })
        }
    }

    @Test
    fun precheckRejectAtEndFailsBeforeEndFrame() {
        val p = peer(Peer())
        builder().audioPrecheck(AudioPrecheckMode.REJECT).build().use { c ->
            val l = RecordingListener()
            val s = open(c, l)
            assertTrue(l.started.await(5, TimeUnit.SECONDS))
            streamPcm(s, pcm.copyOf(16000)) // 0.5 s
            s.end()
            assertTrue(l.awaitClosed(10))
            assertClean(l)
            val e = l.error as AudioQualityException
            assertEquals(90101, e.code)
            assertFalse(p.texts.any { it.contains("\"end\"") })
        }
    }

    @Test
    fun precheckWarnAtEndReportsWarnings() {
        peer(Peer())
        builder().build().use { c ->
            val l = RecordingListener()
            val s = open(c, l)
            streamPcm(s, ByteArray(48000)) // 1.5 s of silence
            s.end()
            assertTrue(l.awaitClosed(10))
            assertClean(l)
            assertEquals(listOf(90103), l.warnings.map { it.code })
            assertEquals(listOf(90103), l.result!!.localWarnings.map { it.code })
            assertTrue(l.events.indexOf("warning:90103") < l.events.indexOf("result"))
        }
    }

    @Test
    fun precheckOffSkipsChecks() {
        peer(Peer())
        builder().audioPrecheck(AudioPrecheckMode.OFF).build().use { c ->
            val l = RecordingListener()
            val s = open(c, l)
            streamPcm(s, ByteArray(640))
            s.end()
            assertTrue(l.awaitClosed(10))
            assertTrue(l.warnings.isEmpty())
            assertNotNull(l.result)
        }
    }

    @Test
    fun clientCloseCancelsOpenSessions() {
        peer(Peer(onEnd = { }))
        val c = builder().build()
        val l = RecordingListener()
        val s = open(c, l)
        assertTrue(l.started.await(5, TimeUnit.SECONDS))
        assertEquals(1, c.activeSessionCount())
        c.close()
        c.close()
        assertTrue(c.isClosed)
        assertTrue(l.awaitClosed(5))
        assertClean(l)
        assertEquals(0, l.terminalCallbacks())
        assertEquals(SessionState.CLOSED, s.getState())
        assertEquals(0, c.activeSessionCount())
        for (block in listOf<() -> Unit>(
            { c.streamEvaluate(config, RecordingListener()) },
            { c.evaluate(config, pcm) },
            { c.tts(TtsRequest("x")) },
            { c.getReport("eval_x") },
            { c.evaluateAsync(config, AudioInput.fromBytes(pcm), object : YuguCallback<EvalResult> {
                override fun onSuccess(result: EvalResult) {}
                override fun onFailure(error: YuguException) {}
            }) },
        )) {
            try {
                block()
                fail("call after close accepted")
            } catch (e: IllegalSessionStateException) {
                assertEquals(90004, e.code)
            }
        }
    }

    @Test
    fun withoutKeyNoReconnect() {
        peer(Peer(onEnd = { it.ws.close(1011, "x") }))
        builder().autoIdempotencyKey(false).build().use { c ->
            val l = RecordingListener()
            val s = open(c, l)
            assertNull(s.idempotencyKey)
            streamPcm(s, pcm)
            s.end()
            assertTrue(l.awaitClosed(10))
            assertEquals(90001, l.error!!.code)
            assertTrue(l.reconnecting.isEmpty())
            val url = ("http://x" + server.takeRequest().path).toHttpUrl()
            assertNull(url.queryParameter("idempotencyKey"))
            assertTrue(logger.text().contains("没有幂等键，不重连"))
        }
    }

    @Test
    fun tokenAuthAndSignedExtraQuery() {
        peer(Peer())
        YuguClient.builder().wsBaseUrl("ws://127.0.0.1:${server.port}/").auth(Auth.token("jwt-1")).logger(logger)
            .callbackExecutor(TestEnv.callbackThread()).build().use { c ->
                val l = RecordingListener()
                val s = c.streamEvaluate(config, l, SessionOptions(idempotencyKey = "my-key", extraQuery = mapOf("mockFault" to "none")))
                l.session = s
                assertEquals("my-key", s.idempotencyKey)
                streamPcm(s, pcm)
                s.end()
                assertTrue(l.awaitClosed(10))
                val url = ("http://x" + server.takeRequest().path).toHttpUrl()
                assertEquals("jwt-1", url.queryParameter("token"))
                assertEquals("my-key", url.queryParameter("idempotencyKey"))
                assertEquals("none", url.queryParameter("mockFault"))
                assertNull(url.queryParameter("signature"))
            }
        peer(Peer())
        builder().build().use { c ->
            val l = RecordingListener()
            val s = c.streamEvaluate(config, l, SessionOptions(extraQuery = mapOf("mockFault" to "ws-kill-after:3")))
            l.session = s
            streamPcm(s, pcm)
            s.end()
            assertTrue(l.awaitClosed(10))
            val url = ("http://x" + server.takeRequest().path).toHttpUrl()
            val q = url.queryParameterNames.associateWith { url.queryParameter(it)!! }
            assertEquals("ws-kill-after:3", q["mockFault"])
            assertEquals(Signer.sign(q - "signature", "mock-secret-key"), q["signature"])
        }
        try {
            builder().build().use { it.streamEvaluate(config, RecordingListener(), SessionOptions(idempotencyKey = "bad key")) }
            fail()
        } catch (e: InvalidParameterException) {
            assertEquals(90010, e.code)
        }
        try {
            builder().build().use { it.streamEvaluate(config, RecordingListener(), SessionOptions(resultTimeoutMs = 0)) }
            fail()
        } catch (e: InvalidParameterException) {
            assertEquals(90010, e.code)
        }
    }

    @Test
    fun replayOverflowFailsTheNextReconnect() {
        peer(Peer(onEnd = { it.ws.close(1011, "x") }))
        builder().maxReplayBytes(2000).build().use { c ->
            val l = RecordingListener()
            val s = open(c, l)
            assertTrue(l.started.await(5, TimeUnit.SECONDS))
            streamPcm(s, pcm.copyOf(6400))
            s.end()
            assertTrue(l.awaitClosed(10))
            assertClean(l)
            val e = l.error as IllegalSessionStateException
            assertEquals(90008, e.code)
            assertTrue(logger.text().contains("重放缓冲超过"))
        }
    }

    @Test
    fun consecutiveCounterResetsAfterASuccessfulReconnect() {
        // two separate drops, each recovered on the first attempt, with maxAttempts = 1
        peer(Peer(onBinary = { p, n -> if (n >= 3200) p.ws.close(1011, "drop 1") }))
        peer(Peer(onBinary = { p, n -> if (n >= 6400) p.ws.close(1011, "drop 2") }))
        val last = peer(Peer())
        builder().reconnectPolicy(fast.copy(maxAttempts = 1)).build().use { c ->
            val l = RecordingListener()
            val s = open(c, l)
            streamPcm(s, pcm, 3)
            s.end()
            assertTrue(l.awaitClosed(10))
            assertClean(l)
            assertNotNull("error ${l.error}", l.result)
            assertEquals(listOf(1, 1), l.reconnecting.map { it.first })
            assertEquals(listOf(1, 1), l.reconnected.map { it.first })
            assertEquals(2, s.getReconnectCount())
            assertArrayEquals(pcm, last.bytes())
        }
    }

    @Test
    fun totalReconnectBoundEndsAFlappingSession() {
        // every connection starts fine and is dropped on the first audio frame; bound is 3 x maxAttempts
        repeat(6) { peer(Peer(onBinary = { p, _ -> p.ws.close(1011, "flap") })) }
        builder().reconnectPolicy(fast.copy(maxAttempts = 1)).build().use { c ->
            val l = RecordingListener()
            val s = open(c, l)
            streamPcm(s, pcm.copyOf(6400))
            s.end()
            assertTrue(l.awaitClosed(10))
            assertClean(l)
            assertEquals(90006, l.error!!.code)
            assertEquals(3, s.getReconnectCount())
            assertEquals(3, l.reconnected.size)
            assertTrue(l.error!!.message!!.contains("上限"))
            assertEquals(4, server.requestCount)
        }
    }

    @Test
    fun replayOverflowWhileReconnectingFailsAtOnce() {
        peer(Peer(onBinary = { p, n -> if (n >= 3200) p.ws.close(1011, "x") }))
        builder().maxReplayBytes(4000).reconnectPolicy(fast.copy(initialDelayMs = 400, maxDelayMs = 400)).build().use { c ->
            val l = RecordingListener()
            val s = open(c, l)
            assertTrue(l.started.await(5, TimeUnit.SECONDS))
            streamPcm(s, pcm.copyOf(3200))
            val deadline = System.nanoTime() + 5_000_000_000L
            while (s.getReconnectCount() == 0 && System.nanoTime() < deadline) Thread.sleep(10)
            streamPcm(s, pcm.copyOfRange(3200, 6400)) // pushes the buffer past 4000 bytes during the reconnect
            assertTrue(l.awaitClosed(10))
            assertClean(l)
            assertEquals(90008, l.error!!.code)
            assertTrue(l.reconnected.isEmpty())
            assertFalse(s.sendAudio(pcm))
        }
    }

    @Test
    fun dropPolicyDiscardsAudioWhileReconnecting() {
        val gate = CountDownLatch(1)
        // first connection dies after 3200 bytes of audio, before end
        val first = peer(Peer(onBinary = { p, n -> if (n >= 3200) p.ws.close(1011, "x") }))
        val second = peer(Peer(onStart = { gate.countDown() }))
        builder().audioBufferPolicy(AudioBufferPolicy.DROP).reconnectPolicy(fast.copy(initialDelayMs = 300, maxDelayMs = 300)).build().use { c ->
            val l = RecordingListener()
            val s = open(c, l)
            assertTrue(l.started.await(5, TimeUnit.SECONDS))
            streamPcm(s, pcm.copyOf(3200))
            // wait until the drop is noticed, then send audio that must be discarded
            val deadline = System.nanoTime() + 5_000_000_000L
            while (s.getReconnectCount() == 0 && System.nanoTime() < deadline) Thread.sleep(10)
            streamPcm(s, pcm.copyOfRange(3200, 9600))
            assertTrue(gate.await(5, TimeUnit.SECONDS))
            // onReconnected is announced once the new session reached STARTED
            val started = System.nanoTime() + 5_000_000_000L
            while (l.reconnected.isEmpty() && System.nanoTime() < started) Thread.sleep(5)
            streamPcm(s, pcm.copyOfRange(9600, pcm.size))
            s.end()
            assertTrue(l.awaitClosed(10))
            assertClean(l)
            assertNotNull(l.result)
            assertEquals(3200, first.bytes().size)
            assertEquals(1, l.reconnected.size)
            assertEquals(6400L, l.reconnected[0].second)
            assertArrayEquals(pcm.copyOfRange(9600, pcm.size), second.bytes())
        }
    }

    @Test
    fun dropPolicyFailsWhenTheLinkDiesAfterEnd() {
        peer(Peer(onEnd = { it.ws.close(1011, "x") }))
        builder().audioBufferPolicy(AudioBufferPolicy.DROP).build().use { c ->
            val l = RecordingListener()
            val s = open(c, l)
            streamPcm(s, pcm)
            s.end()
            assertTrue(l.awaitClosed(10))
            assertEquals(90001, l.error!!.code)
            assertTrue(l.reconnecting.isEmpty())
        }
    }

    @Test
    fun reconnectDisabledFailsFast() {
        peer(Peer(onEnd = { it.ws.close(1011, "x") }))
        builder().reconnectPolicy(ReconnectPolicy.DISABLED).build().use { c ->
            val l = RecordingListener()
            val s = open(c, l)
            streamPcm(s, pcm)
            s.end()
            assertTrue(l.awaitClosed(10))
            assertEquals(90001, l.error!!.code)
            assertTrue(l.reconnecting.isEmpty())
        }
    }

    @Test
    fun listenerExceptionsDoNotBreakTheSession() {
        peer(Peer())
        builder().build().use { c ->
            val done = CountDownLatch(1)
            val bad = object : StreamListener {
                override fun onStateChanged(oldState: SessionState, newState: SessionState) = throw IllegalStateException("boom")
                override fun onResult(result: EvalResult) = throw IllegalStateException("boom")
                override fun onError(error: YuguException) {}
                override fun onClosed(code: Int, reason: String) {
                    done.countDown()
                }
            }
            val s = c.streamEvaluate(config, bad)
            streamPcm(s, pcm)
            s.end()
            assertTrue(done.await(10, TimeUnit.SECONDS))
            assertTrue(logger.text().contains("回调抛出异常"))
        }
    }

    @Test
    fun javaStyleListenerNeedsOnlyRequiredMethods() {
        peer(Peer())
        builder().build().use { c ->
            val got = CountDownLatch(1)
            val s = c.streamEvaluate(config, object : StreamListener {
                override fun onResult(result: EvalResult) {
                    got.countDown()
                }

                override fun onError(error: YuguException) {}
            })
            streamPcm(s, pcm)
            s.end()
            assertTrue(got.await(10, TimeUnit.SECONDS))
        }
    }
}
