package com.shengzhiai.yugu

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** DESIGN 2.2 and 2.3: idempotency keys, retry decisions, backoff math, deadlines. */
class RetryTest {

    private lateinit var server: MockWebServer
    private val wav = TestEnv.fixtureBytes("audio/zh_short.wav")
    private val config = EvaluateConfig(CoreType.SENTENCE, "今天天气很好", Language.ZH_CN)
    private val okBody by lazy { TestEnv.fixtureText("platform/native_evaluate_sentence_zh.json") }
    private val logger = CapturingLogger()
    private val events = RecordingEvents()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun builder(seed: Long = 7) = YuguClient.builder()
        .baseUrl(server.url("/").toString())
        .auth(Auth.appKey("mock-app-key", "mock-secret-key"))
        .logger(logger)
        .logLevel(LogLevel.DEBUG)
        .eventListener(events)
        .random(Random(seed))
        .callbackExecutor { it.run() }

    // ------------------------------------------------------------------------- backoff math

    @Test
    fun delayFormulaWithSeededJitter() {
        val p = RetryPolicy()
        assertEquals(200, p.baseDelayMs(1))
        assertEquals(400, p.baseDelayMs(2))
        assertEquals(800, p.baseDelayMs(3))
        assertEquals(4000, p.baseDelayMs(6))
        assertEquals(4000, p.baseDelayMs(30))
        val r1 = Random(42)
        val r2 = Random(42)
        for (n in 1..5) {
            val u = (r2.nextDouble() * 2 - 1) * 0.3
            val expected = Math.round(p.baseDelayMs(n) * (1 + u))
            assertEquals("delay($n)", expected, p.delayMs(n, r1))
        }
        val r = Random(1)
        repeat(1000) {
            val d1 = p.delayMs(1, r)
            assertTrue("delay(1)=$d1 within 200 +- 30%", d1 in 140..260)
            val d2 = p.delayMs(2, r)
            assertTrue("delay(2)=$d2 within 400 +- 30%", d2 in 280..520)
        }
        assertEquals(200, RetryPolicy(jitter = 0.0).delayMs(1, Random()))
    }

    @Test
    fun retryAfterIsRespectedAndCapped() {
        val p = RetryPolicy(jitter = 0.0)
        assertEquals(1000, p.delayMs(1, Random(), 1000))
        assertEquals(400, p.delayMs(2, Random(), 100))
        assertEquals(30_000, p.delayMs(1, Random(), 120_000))
        assertEquals(200, p.copy(respectRetryAfter = false).delayMs(1, Random(), 5000))
        assertEquals(200, p.delayMs(1, Random(), null))
    }

    @Test
    fun reconnectPolicyFormula() {
        val p = ReconnectPolicy()
        assertEquals(8, p.maxAttempts)
        assertEquals(24, p.maxTotalAttempts)
        assertEquals(listOf(500L, 1000L, 2000L, 4000L, 4000L, 4000L, 4000L, 4000L), (1..p.maxAttempts).map { p.baseDelayMs(it) })
        assertEquals("the default rides out a 10 s drop", 23_500L, (1..p.maxAttempts).sumOf { p.baseDelayMs(it) })
        assertEquals(4000, p.baseDelayMs(9))
        val d = p.delayMs(1, Random(3))
        assertTrue(d in 350..650)
        assertFalse(ReconnectPolicy.DISABLED.enabled)
    }

    @Test
    fun policiesRejectNonsense() {
        for (block in listOf<() -> Unit>(
            { RetryPolicy(maxRetries = -1) },
            { RetryPolicy(multiplier = 0.5) },
            { RetryPolicy(jitter = 1.5) },
            { RetryPolicy(initialDelayMs = -1) },
            { RetryPolicy(maxRetryAfterMs = -1) },
            { ReconnectPolicy(maxAttempts = -1) },
            { ReconnectPolicy(jitter = -0.1) },
            { ReconnectPolicy(multiplier = 0.0) },
            { ReconnectPolicy(maxDelayMs = -5) },
            { Backoff.base(0, 1, 2.0, 3) },
        )) {
            try {
                block()
                fail("accepted")
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
    }

    // ------------------------------------------------------------------------- idempotency

    @Test
    fun autoKeyIs32LowercaseHexAndReusedOnEveryRetry() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"code":50000,"message":"boom"}"""))
        server.enqueue(MockResponse().setResponseCode(503).setBody("busy"))
        server.enqueue(MockResponse().setBody(okBody).setHeader("Idempotency-Replayed", "true"))
        builder().build().use { c ->
            val r = c.evaluate(config, wav)
            assertTrue(r.replayed)
            assertEquals(93.7, r.overall!!, 1e-9)
            val reqs = (1..3).map { server.takeRequest() }
            val keys = reqs.map { it.getHeader("Idempotency-Key")!! }
            assertEquals(1, keys.toSet().size)
            assertTrue(keys[0].matches(Regex("^[0-9a-f]{32}$")))
            assertEquals(keys[0], r.idempotencyKey)
            // fresh nonce and timestamp header per attempt, same signature
            assertEquals(3, reqs.map { it.getHeader("X-Nonce") }.toSet().size)
            assertEquals(1, reqs.map { it.getHeader("X-Signature") }.toSet().size)
            assertEquals(listOf(1, 2), events.retries.map { it.first })
            assertTrue(logger.text().contains("retry 1/2 in "))
            assertTrue(logger.text().contains(": HTTP 500 code=50000"))
            assertTrue(logger.text().contains("retry 2/2 in "))
            assertTrue(logger.text().contains(": HTTP 503 code=0"))
            assertEquals(listOf("evaluate 200 3 null"), events.ends)
            assertEquals(3, events.starts.size)
        }
    }

    @Test
    fun callerKeyIsUsedAndValidatedBeforeAnyIo() {
        builder().build().use { c ->
            server.enqueue(MockResponse().setBody(okBody))
            val r = c.evaluate(config, wav, RequestOptions(idempotencyKey = "order-42:attempt"))
            assertEquals("order-42:attempt", r.idempotencyKey)
            assertEquals("order-42:attempt", server.takeRequest().getHeader("Idempotency-Key"))
            for (bad in listOf("", "has space", "中文键", "x".repeat(201), "tab\tkey")) {
                try {
                    c.evaluate(config, wav, RequestOptions(idempotencyKey = bad))
                    fail("accepted key '$bad'")
                } catch (e: InvalidParameterException) {
                    assertEquals(90010, e.code)
                }
            }
            assertEquals(1, server.requestCount)
            // 200 visible ASCII chars is the upper bound
            server.enqueue(MockResponse().setBody(okBody))
            c.evaluate(config, wav, RequestOptions(idempotencyKey = "~".repeat(200)))
        }
    }

    @Test
    fun noKeyMeansNoRetryForWrites() {
        server.enqueue(MockResponse().setResponseCode(503))
        builder().autoIdempotencyKey(false).build().use { c ->
            try {
                c.evaluate(config, wav)
                fail("expected failure")
            } catch (e: ServerException) {
                assertEquals(1, e.attempts)
                assertNull(e.idempotencyKey)
            }
            assertNull(server.takeRequest().getHeader("Idempotency-Key"))
            assertTrue(logger.text().contains("写请求不重试"))
        }
    }

    // ------------------------------------------------------------------------- retry decisions

    @Test
    fun badRequestIsNeverRetried() {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"code":40001,"message":"refText 不能为空"}"""))
        builder().build().use { c ->
            try {
                c.evaluate(config, wav)
                fail()
            } catch (e: InvalidParameterException) {
                assertEquals(40001, e.code)
                assertEquals(1, e.attempts)
                assertFalse(e.retryable)
            }
            assertEquals(1, server.requestCount)
            assertTrue(events.retries.isEmpty())
        }
    }

    @Test
    fun retriesStopAfterMaxRetriesAndRecordAttempts() {
        repeat(3) { server.enqueue(MockResponse().setResponseCode(502).setBody("""{"code":50200,"message":"upstream"}""")) }
        builder().build().use { c ->
            try {
                c.evaluate(config, wav)
                fail()
            } catch (e: ServerException) {
                assertEquals(50200, e.code)
                assertEquals(ErrorCategory.UPSTREAM, e.category)
                assertEquals(3, e.attempts)
            }
            assertEquals(3, server.requestCount)
        }
        repeat(2) { server.enqueue(MockResponse().setResponseCode(500)) }
        builder().retryPolicy(RetryPolicy(maxRetries = 1, initialDelayMs = 10)).build().use { c ->
            try {
                c.evaluate(config, wav)
                fail()
            } catch (e: ServerException) {
                assertEquals(2, e.attempts)
            }
        }
    }

    @Test
    fun rateLimitHonoursRetryAfter() {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "1").setBody("""{"code":42900,"message":"排队超时"}"""))
        server.enqueue(MockResponse().setBody(okBody))
        builder().build().use { c ->
            val t0 = System.nanoTime()
            c.evaluate(config, wav)
            val ms = (System.nanoTime() - t0) / 1_000_000
            assertTrue("waited $ms ms", ms >= 1000)
            assertEquals(1, events.retries.size)
            assertEquals(1000L, events.retries[0].second)
        }
    }

    @Test
    fun readTimeoutIsRetriedWithSameKey() {
        server.enqueue(MockResponse().setBody(okBody).setHeadersDelay(1500, TimeUnit.MILLISECONDS))
        server.enqueue(MockResponse().setBody(okBody).setHeader("Idempotency-Replayed", "true"))
        builder().readTimeoutMs(300).build().use { c ->
            val r = c.evaluate(config, wav)
            assertTrue(r.replayed)
            val k1 = server.takeRequest().getHeader("Idempotency-Key")
            val k2 = server.takeRequest().getHeader("Idempotency-Key")
            assertEquals(k1, k2)
            assertTrue(logger.text().contains("TIMEOUT code=90002"))
        }
    }

    @Test
    fun perCallReadTimeoutOverride() {
        server.enqueue(MockResponse().setBody(okBody).setHeadersDelay(800, TimeUnit.MILLISECONDS))
        builder().readTimeoutMs(200).build().use { c ->
            c.evaluate(config, wav, RequestOptions(readTimeoutMs = 3000, retryPolicy = RetryPolicy.NONE))
        }
    }

    @Test
    fun connectionDropIsANetworkErrorAndRetried() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        server.enqueue(MockResponse().setBody(okBody))
        builder().build().use { c ->
            c.evaluate(config, wav)
            assertTrue(logger.text().contains("NETWORK code=90001"))
        }
    }

    @Test
    fun totalTimeoutStopsRetrying() {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "5").setBody("""{"code":42900}"""))
        builder().totalTimeoutMs(1500).build().use { c ->
            val t0 = System.nanoTime()
            try {
                c.evaluate(config, wav)
                fail()
            } catch (e: RateLimitException) {
                assertEquals(1, e.attempts)
                assertEquals(5000L, e.retryAfterMs)
            }
            assertTrue((System.nanoTime() - t0) / 1_000_000 < 1500)
            assertTrue(logger.text().contains("总时限"))
        }
    }

    @Test
    fun totalTimeoutCutsAHangingAttempt() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        builder().totalTimeoutMs(700).readTimeoutMs(60_000).build().use { c ->
            val t0 = System.nanoTime()
            try {
                c.evaluate(config, wav)
                fail()
            } catch (e: RequestTimeoutException) {
                assertEquals(90002, e.code)
            }
            val ms = (System.nanoTime() - t0) / 1_000_000
            assertTrue("took $ms ms", ms in 600..5000)
        }
    }

    @Test
    fun getReportRetriesWithoutKeyAndSignsEmptySet() {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setBody("""{"code":0,"message":"success","data":{"recordId":"eval_abc","overall":93.7}}"""))
        builder().build().use { c ->
            val r = c.getReport("eval_abc")
            assertEquals(93.7, r.overall!!, 1e-9)
            assertEquals("eval_abc", r.recordId)
            val req = server.takeRequest()
            assertEquals("/api/v1/report/eval_abc", req.path)
            assertNull(req.getHeader("Idempotency-Key"))
            assertEquals(Signer.sign(emptyMap<String, String>(), "mock-secret-key"), req.getHeader("X-Signature"))
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun cancellationTokenStopsTheRetryWait() {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "20").setBody("""{"code":42900}"""))
        builder().build().use { c ->
            val token = CancellationToken()
            Thread {
                Thread.sleep(300)
                token.cancel()
            }.start()
            val t0 = System.nanoTime()
            try {
                c.evaluate(config, wav, RequestOptions(cancellationToken = token))
                fail()
            } catch (e: RequestCancelledException) {
                assertEquals(90003, e.code)
                assertFalse(e.retryable)
            }
            assertTrue((System.nanoTime() - t0) / 1_000_000 < 5000)
            assertTrue(token.isCancelled)
            token.cancel() // idempotent
        }
    }

    @Test
    fun cancellationAbortsAnInFlightCall() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        builder().build().use { c ->
            val token = CancellationToken()
            Thread {
                Thread.sleep(300)
                token.cancel()
            }.start()
            try {
                c.evaluate(config, wav, RequestOptions(cancellationToken = token))
                fail()
            } catch (e: RequestCancelledException) {
                assertEquals(1, e.attempts)
            }
        }
    }

    @Test
    fun preCancelledTokenSendsNothing() {
        builder().build().use { c ->
            val token = CancellationToken().apply { cancel() }
            try {
                c.evaluate(config, wav, RequestOptions(cancellationToken = token))
                fail()
            } catch (e: RequestCancelledException) {
                assertEquals(0, e.attempts)
            }
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun clientCloseAbortsInFlightCallWith90004() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val c = builder().build()
        val error = AtomicReference<Throwable>()
        val done = CountDownLatch(1)
        Thread {
            try {
                c.evaluate(config, wav)
            } catch (t: Throwable) {
                error.set(t)
            }
            done.countDown()
        }.start()
        server.takeRequest(5, TimeUnit.SECONDS)
        c.close()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        val e = error.get() as IllegalSessionStateException
        assertEquals(90004, e.code)
        assertEquals(0, c.activeCallCount())
    }

    @Test
    fun listenerExceptionsDoNotBreakCalls() {
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setBody(okBody))
        val boom = object : YuguEventListener {
            override fun onRequestStart(op: String, method: String, path: String, attempt: Int) = throw IllegalStateException("x")
            override fun onRetry(op: String, attempt: Int, delayMs: Long, error: YuguException) = throw IllegalStateException("y")
        }
        builder().eventListener(boom).build().use { c ->
            assertNotEquals(null, c.evaluate(config, wav).recordId)
            assertTrue(logger.text().contains("eventListener"))
        }
    }

    @Test
    fun retryAfterHeaderForms() {
        fun h(v: String) = okhttp3.Headers.headersOf("Retry-After", v)
        assertEquals(2000L, com.shengzhiai.yugu.internal.HttpEngine.retryAfterMs(h("2")))
        assertEquals(1500L, com.shengzhiai.yugu.internal.HttpEngine.retryAfterMs(h("1.5")))
        assertNull(com.shengzhiai.yugu.internal.HttpEngine.retryAfterMs(h("-1")))
        assertNull(com.shengzhiai.yugu.internal.HttpEngine.retryAfterMs(h("soon")))
        assertNull(com.shengzhiai.yugu.internal.HttpEngine.retryAfterMs(okhttp3.Headers.headersOf()))
        val fmt = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US)
        fmt.timeZone = java.util.TimeZone.getTimeZone("GMT")
        val future = fmt.format(java.util.Date(System.currentTimeMillis() + 10_000))
        val ms = com.shengzhiai.yugu.internal.HttpEngine.retryAfterMs(h(future))!!
        assertTrue("http-date gives $ms", ms in 5_000..11_000)
    }
}
