package com.shengzhiai.yugu

import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.ByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** B-05 lifecycle, async API, C-01 logging with redaction. */
class LifecycleTest {

    private lateinit var server: MockWebServer
    private val ok by lazy { TestEnv.fixtureText("platform/native_evaluate_sentence_zh.json") }
    private val resultFrame by lazy { "{\"event\":\"result\"," + ok.trim().removePrefix("{") }
    private val wav = TestEnv.fixtureBytes("audio/zh_short.wav")
    private val pcm = TestEnv.fixturePcm("audio/zh_short.wav")
    private val config = EvaluateConfig(CoreType.SENTENCE, "今天天气很好", Language.ZH_CN)
    private val callbacks = TestEnv.callbackThread()

    private fun wsPeer() = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send("""{"event":"connected"}""")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (text.contains("\"cmd\":\"end\"")) webSocket.send(resultFrame) else webSocket.send("""{"event":"started"}""")
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {}

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path!!.startsWith("/api/v1/ws/") -> MockResponse().withWebSocketUpgrade(wsPeer())
                request.path!!.startsWith("/api/v1/tts/") -> MockResponse().setBody(TestEnv.fixtureText("platform/tts_generate.json"))
                request.path!!.startsWith("/api/v1/report/") -> MockResponse().setBody("""{"code":0,"data":{"overall":90}}""")
                request.path!!.startsWith("/sent.eval.cn") -> MockResponse().setBody(TestEnv.fixtureText("platform/compat_sent.eval.cn.json"))
                request.path!!.startsWith("/slow") -> MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                request.getHeader("X-Test") == "fail" -> MockResponse().setResponseCode(400).setBody("""{"code":40001,"message":"bad"}""")
                else -> MockResponse().setBody(ok)
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun builder(logger: YuguLogger = CapturingLogger(), auth: Auth = Auth.appKey("mock-app-key", "mock-secret-key")) =
        YuguClient.builder()
            .baseUrl(server.url("/").toString())
            .wsBaseUrl("ws://127.0.0.1:${server.port}")
            .auth(auth)
            .logger(logger)
            .callbackExecutor(callbacks)

    private fun cycle() {
        val c = builder().build()
        c.evaluate(config, wav)
        val l = RecordingListener()
        val s = c.streamEvaluate(config, l)
        l.session = s
        streamPcm(s, pcm.copyOf(32000))
        s.end()
        assertTrue(l.awaitClosed(10))
        assertNotNull(l.result)
        assertTrue(l.violations.isEmpty())
        c.close()
        c.close()
        assertEquals(0, c.activeSessionCount())
        assertEquals(0, c.activeCallCount())
    }

    private fun threadNames(): List<String> = Thread.getAllStackTraces().keys.filter { it.isAlive }.map { it.name }.sorted()

    /**
     * Threads of process-wide pools that are shared by every client and keep idle threads for up
     * to 60 s: OkHttp's dispatcher and task runner, Okio's watchdog, MockWebServer's workers.
     */
    private fun isSharedPool(name: String): Boolean =
        name.startsWith("OkHttp ") || name.startsWith("Okio ") || name.startsWith("MockWebServer") || name.endsWith("workers") ||
            name.contains(" workers Thread ")

    @Test
    fun hundredCreateUseReleaseCyclesDoNotAccumulate() {
        repeat(20) { cycle() }
        System.gc()
        val rt = Runtime.getRuntime()
        val before = threadNames()
        val heapBefore = rt.totalMemory() - rt.freeMemory()
        repeat(100) { cycle() }
        Thread.sleep(500)
        val after = threadNames()
        val ownBefore = before.filterNot(::isSharedPool)
        val ownAfter = after.filterNot(::isSharedPool)
        // no thread per client: everything outside the shared pools is unchanged
        assertTrue("non-pool threads grew: before=$ownBefore after=$ownAfter", ownAfter.size - ownBefore.size <= 2)
        assertTrue("SDK threads $after", after.count { it.startsWith("yugu-") } <= 3)
        // the shared pools stay bounded by concurrency, far below one thread per cycle
        assertTrue("pool threads $after", after.count(::isSharedPool) < 60)
        System.gc()
        Thread.sleep(200)
        System.gc()
        val heapAfter = rt.totalMemory() - rt.freeMemory()
        assertTrue("heap grew from $heapBefore to $heapAfter", heapAfter - heapBefore < 64L * 1024 * 1024)
    }

    @Test
    fun asyncSuccessFailureAndCancel() {
        builder().build().use { c ->
            val done = CountDownLatch(4)
            val got = AtomicReference<EvalResult>()
            val thread = AtomicReference<String>()
            val call = c.evaluateAsync(config, AudioInput.fromBytes(wav), object : YuguCallback<EvalResult> {
                override fun onSuccess(result: EvalResult) {
                    got.set(result)
                    thread.set(Thread.currentThread().name)
                    done.countDown()
                }

                override fun onFailure(error: YuguException) {}
            })
            c.ttsAsync(TtsRequest("你好"), object : YuguCallback<TtsResult> {
                override fun onSuccess(result: TtsResult) {
                    done.countDown()
                }

                override fun onFailure(error: YuguException) {}
            })
            c.getReportAsync("eval_1", object : YuguCallback<ReportResult> {
                override fun onSuccess(result: ReportResult) {
                    assertEquals(90.0, result.overall!!, 0.0)
                    done.countDown()
                }

                override fun onFailure(error: YuguException) {}
            })
            c.evaluateCompatAsync(CompatConfig(CompatCoreType.SENT_EVAL_CN, "今天天气很好"), AudioInput.fromBytes(wav), object : YuguCallback<EvalResult> {
                override fun onSuccess(result: EvalResult) {
                    done.countDown()
                }

                override fun onFailure(error: YuguException) {}
            })
            assertTrue(done.await(10, TimeUnit.SECONDS))
            assertEquals("test-callback", thread.get())
            assertEquals(93.7, got.get().overall!!, 1e-9)
            val deadline = System.nanoTime() + 2_000_000_000L
            while (!call.isDone && System.nanoTime() < deadline) Thread.sleep(5)
            assertTrue(call.isDone)
            assertFalse(call.isCancelled)

            val failed = CountDownLatch(1)
            val err = AtomicReference<YuguException>()
            c.evaluateAsync(EvaluateConfig("bad", "x"), AudioInput.fromBytes(wav), object : YuguCallback<EvalResult> {
                override fun onSuccess(result: EvalResult) {}
                override fun onFailure(error: YuguException) {
                    err.set(error)
                    failed.countDown()
                }
            })
            assertTrue(failed.await(5, TimeUnit.SECONDS))
            assertEquals(90010, err.get().code)
        }
        // cancellation of a hanging async call
        YuguClient.builder().baseUrl(server.url("/slow/").toString()).auth(Auth.token("t")).logger(CapturingLogger())
            .callbackExecutor(callbacks).build().use { c ->
                val failed = CountDownLatch(1)
                val err = AtomicReference<YuguException>()
                val call = c.getReportAsync("eval_1", object : YuguCallback<ReportResult> {
                    override fun onSuccess(result: ReportResult) {}
                    override fun onFailure(error: YuguException) {
                        err.set(error)
                        failed.countDown()
                    }
                })
                Thread.sleep(200)
                call.cancel()
                call.cancel()
                assertTrue(call.isCancelled)
                assertTrue(failed.await(5, TimeUnit.SECONDS))
                assertTrue(err.get() is RequestCancelledException)
            }
    }

    @Test
    fun logsNeverContainSecretsAndMaskTheAppKey() {
        val logger = CapturingLogger()
        val secret = "s3cr3t-very-private"
        val appKey = "ak_live_12345678"
        builder(logger, Auth.appKey(appKey, secret)).logLevel(LogLevel.DEBUG).build().use { c ->
            c.evaluate(config, wav)
            c.tts(TtsRequest("你好"))
            val l = RecordingListener()
            val s = c.streamEvaluate(config, l)
            streamPcm(s, pcm.copyOf(32000))
            s.end()
            assertTrue(l.awaitClosed(10))
        }
        val token = "eyJhbGciOiJIUzI1NiJ9.secret-payload.sig"
        builder(logger, Auth.token(token)).logLevel(LogLevel.DEBUG).build().use { c ->
            c.evaluate(config, wav)
            val l = RecordingListener()
            val s = c.streamEvaluate(config, l)
            s.end()
            assertTrue(l.awaitClosed(10))
        }
        val text = logger.text()
        assertTrue(text.isNotEmpty())
        assertFalse("secret leaked", text.contains(secret))
        assertFalse("token leaked", text.contains(token))
        assertFalse("appKey leaked", text.contains(appKey))
        assertTrue(text.contains("ak_l***"))
        assertFalse("signature leaked", Regex("signature=[A-Za-z0-9+/%]{8,}").containsMatchIn(text))
        assertTrue(text.contains("signature=***"))
        assertTrue(text.contains("token=***"))
        assertEquals(
            "wss://h/p?appKey=ak_l***&timestamp=1&signature=***&token=***&secretKey=***",
            Log.redactUrl("wss://h/p?appKey=ak_live_1&timestamp=1&signature=abc%2B&token=xyz&secretKey=k"),
        )
    }

    @Test
    fun logLevelsFilter() {
        val warn = CapturingLogger()
        builder(warn).logLevel(LogLevel.WARN).build().use { it.evaluate(config, wav) }
        assertTrue(warn.lines.none { it.startsWith("DEBUG") || it.startsWith("INFO") })
        val off = CapturingLogger()
        builder(off).logLevel(LogLevel.OFF).build().use { it.evaluate(config, wav) }
        assertTrue(off.lines.isEmpty())
        assertTrue(LogLevel.DEBUG.allows(LogLevel.ERROR))
        assertFalse(LogLevel.ERROR.allows(LogLevel.WARN))
        assertFalse(LogLevel.OFF.allows(LogLevel.ERROR))
        assertFalse(LogLevel.DEBUG.allows(LogLevel.OFF))
        // a throwing logger never breaks a call
        builder({ _, _, _, _ -> throw IllegalStateException("broken logger") }).logLevel(LogLevel.DEBUG).build().use { it.evaluate(config, wav) }
        // default logcat logger on the JVM stub does not throw either
        LogcatLogger().log(LogLevel.ERROR, "t", "m", RuntimeException("x"))
        LogcatLogger().log(LogLevel.DEBUG, "t", "m", null)
    }

    @Test
    fun defaultsAndVersion() {
        assertEquals("2.0.0", YuguVersion.VERSION)
        assertEquals("yugu-android-sdk/2.0.0", YuguVersion.USER_AGENT)
        assertEquals("https://open.shengzhiai.com", YuguClient.DEFAULT_BASE_URL)
        assertEquals("wss://open.shengzhiai.com", YuguClient.DEFAULT_WS_BASE_URL)
        val p = RetryPolicy.DEFAULT
        assertEquals(2, p.maxRetries)
        assertEquals(200, p.initialDelayMs)
        assertEquals(2.0, p.multiplier, 0.0)
        assertEquals(4000, p.maxDelayMs)
        assertEquals(0.3, p.jitter, 0.0)
        assertTrue(p.respectRetryAfter)
        assertEquals(30_000, p.maxRetryAfterMs)
        val r = ReconnectPolicy.DEFAULT
        assertTrue(r.enabled)
        assertEquals(8, r.maxAttempts)
        assertEquals(500, r.initialDelayMs)
        assertEquals(4000, r.maxDelayMs)
        assertEquals(0.3, r.jitter, 0.0)
        val b = YuguClient.builder()
        assertEquals(10_000, b.connectTimeoutMs)
        assertEquals(120_000, b.readTimeoutMs)
        assertEquals(300_000, b.totalTimeoutMs)
        assertTrue(b.autoIdempotencyKey)
        assertEquals(LogLevel.WARN, b.logLevel)
        assertEquals(AudioPrecheckMode.WARN, b.audioPrecheck)
        assertEquals(AudioBufferPolicy.REPLAY, b.audioBufferPolicy)
        assertEquals(300_000, b.resultTimeoutMs)
        assertEquals(15_000, b.pingIntervalMs)
        assertEquals(10L * 1024 * 1024, b.maxReplayBytes)
        assertEquals(RequestOptions.DEFAULT.idempotencyKey, null)
        assertTrue(SessionOptions.DEFAULT.extraQuery.isEmpty())
        // the default client builds without network access
        YuguClient.builder().auth(Auth.token("t")).okHttpClient(okhttp3.OkHttpClient()).userAgent("ua/1").build().close()
    }
}
