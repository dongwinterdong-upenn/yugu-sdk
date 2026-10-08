package com.shengzhiai.yugu

import com.shengzhiai.yugu.internal.JObj
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.util.Random

/**
 * Integration tests against tools/mock-server (DESIGN 9 and 10):
 * V-02 idempotency and billing, V-03 retry decisions, signing accepted by the platform rules.
 */
class MockRestIT {

    companion object {
        private var mock: MockPlatform? = null

        @BeforeClass
        @JvmStatic
        fun startMock() {
            mock = MockPlatform.start()
        }

        @AfterClass
        @JvmStatic
        fun stopMock() {
            mock?.close()
        }
    }

    private val m get() = mock!!
    private val logger = CapturingLogger()
    private val events = RecordingEvents()
    private val wav = TestEnv.fixtureBytes("audio/zh_short.wav")
    private val config = EvaluateConfig(CoreType.SENTENCE, "今天天气很好", Language.ZH_CN, includeReport = true)

    @get:Rule
    val dumpLogsOnFailure = object : TestWatcher() {
        override fun failed(e: Throwable?, description: Description?) {
            println("---- SDK log of ${description?.methodName}\n${logger.text()}")
        }
    }

    @Before
    fun reset() {
        m.reset()
    }

    private fun builder(seed: Long = 5) = YuguClient.builder()
        .baseUrl(m.baseUrl)
        .wsBaseUrl(m.wsUrl)
        .auth(Auth.appKey("mock-app-key", "mock-secret-key"))
        .logger(logger)
        .logLevel(LogLevel.DEBUG)
        .eventListener(events)
        .random(Random(seed))
        .callbackExecutor(TestEnv.callbackThread())

    private fun evaluateEntries() = m.log().filter { it.str("path") == "/api/v1/evaluate" }

    // ------------------------------------------------------------------------- V-02

    @Test
    fun v02_threeSubmissionsSameKeyOneReadTimeoutBillsOnce() {
        // the first request is registered for idempotency, then processed slower than the read timeout
        val key = "v02-" + System.nanoTime()
        m.queueFaults("/api/v1/evaluate" to "slow:1500")
        builder().readTimeoutMs(800).build().use { c ->
            val first = c.evaluate(config, wav, RequestOptions(idempotencyKey = key))
            val second = c.evaluate(config, wav, RequestOptions(idempotencyKey = key))
            val third = c.evaluate(config, wav, RequestOptions(idempotencyKey = key))
            assertEquals(key, first.idempotencyKey)
            assertEquals(first.recordId, second.recordId)
            assertEquals(first.recordId, third.recordId)
            assertTrue("the retry waited for the in-flight first request and got its replay", first.replayed)
            assertTrue(second.replayed)
            assertTrue(third.replayed)
            assertEquals("one internal retry after the read timeout", 1, events.retries.size)
            assertTrue(logger.text().contains("TIMEOUT code=90002"))
        }
        val bill = m.billing()
        assertEquals("billing records", 1, bill.int("billed"))
        assertEquals(1, bill.obj("byKey")!!.int(key))
        val entries = evaluateEntries()
        assertEquals(4, entries.size)
        assertTrue("every attempt carried the same key", entries.all { it.str("idempotencyKey") == key })
        assertEquals("slow:1500", entries[0].str("fault"))
    }

    @Test
    fun v02_delayedBeforeRegistrationAlsoBillsOnce() {
        val key = "v02d-" + System.nanoTime()
        m.queueFaults("/api/v1/evaluate" to "delay:1500")
        builder().readTimeoutMs(800).build().use { c ->
            repeat(3) { c.evaluate(config, wav, RequestOptions(idempotencyKey = key)) }
        }
        Thread.sleep(1200) // the delayed first attempt reaches the guard last and replays
        assertEquals(1, m.billing().int("billed"))
        assertTrue(evaluateEntries().all { it.str("idempotencyKey") == key })
    }

    // ------------------------------------------------------------------------- V-03

    @Test
    fun v03a_http500IsRetriedWithSameKeyAndExpectedBackoff() {
        m.queueFaults("/api/v1/evaluate" to "status:500")
        val expected = RetryPolicy.DEFAULT.delayMs(1, Random(5))
        builder().build().use { c ->
            val r = c.evaluate(config, wav)
            assertNotNull(r.recordId)
            assertEquals(listOf(1 to expected), events.retries)
            val entries = evaluateEntries()
            assertEquals(2, entries.size)
            assertEquals(r.idempotencyKey, entries[0].str("idempotencyKey"))
            assertEquals(r.idempotencyKey, entries[1].str("idempotencyKey"))
            val gap = entries[1].long("t")!! - entries[0].long("t")!!
            // lower bound is the backoff, the upper bound only guards against a stuck client on a loaded host
            assertTrue("gap $gap ms for delay $expected ms", gap >= expected - 5 && gap < expected + 2000)
            assertTrue(expected in 140..260)
        }
        assertEquals(1, m.billing().int("billed"))
    }

    @Test
    fun v03b_readTimeoutIsRetriedWithSameKey() {
        m.queueFaults("/api/v1/evaluate" to "delay:1200")
        builder().readTimeoutMs(500).build().use { c ->
            val r = c.evaluate(config, wav)
            val entries = evaluateEntries()
            assertEquals(2, entries.size)
            assertTrue(entries.all { it.str("idempotencyKey") == r.idempotencyKey })
            assertEquals(1, events.retries.size)
        }
        Thread.sleep(1000)
        assertEquals(1, m.billing().int("billed"))
    }

    @Test
    fun v03c_http429RespectsRetryAfterWithSameKey() {
        m.queueFaults("/api/v1/evaluate" to "status:429:code=42900:retryAfter=1")
        builder().build().use { c ->
            val r = c.evaluate(config, wav)
            val entries = evaluateEntries()
            assertEquals(2, entries.size)
            assertTrue(entries.all { it.str("idempotencyKey") == r.idempotencyKey })
            assertEquals(1000L, events.retries[0].second)
            val gap = entries[1].long("t")!! - entries[0].long("t")!!
            assertTrue("gap $gap ms", gap >= 995)
        }
    }

    @Test
    fun v03d_http400IsNotRetried() {
        m.queueFaults("/api/v1/evaluate" to "status:400:code=40001")
        builder().build().use { c ->
            try {
                c.evaluate(config, wav)
                fail("400 must throw")
            } catch (e: InvalidParameterException) {
                assertEquals(40001, e.code)
                assertEquals(400, e.httpStatus)
                assertEquals(1, e.attempts)
                assertFalse(e.retryable)
            }
        }
        assertEquals(1, evaluateEntries().size)
        assertTrue(events.retries.isEmpty())
        assertEquals(0, m.billing().int("billed"))
    }

    @Test
    fun v03e_retriesStopAtTheConfiguredCount() {
        m.queueFaults("/api/v1/evaluate" to "status:503", "/api/v1/evaluate" to "status:503", "/api/v1/evaluate" to "status:503")
        builder().build().use { c ->
            try {
                c.evaluate(config, wav)
                fail()
            } catch (e: ServerException) {
                // the mock answers 503 with body code 50000: the body code decides the category
                assertEquals(50000, e.code)
                assertEquals(503, e.httpStatus)
                assertEquals(ErrorCategory.SERVER, e.category)
                assertEquals(3, e.attempts)
            }
        }
        val entries = evaluateEntries()
        assertEquals(3, entries.size)
        assertEquals(1, entries.map { it.str("idempotencyKey") }.toSet().size)
        assertEquals(listOf(1, 2), events.retries.map { it.first })
    }

    @Test
    fun droppedConnectionIsRetried() {
        m.queueFaults("/api/v1/evaluate" to "drop")
        builder().build().use { c ->
            assertNotNull(c.evaluate(config, wav).recordId)
        }
        assertEquals(2, evaluateEntries().size)
        assertTrue(logger.text().contains("NETWORK code=90001"))
    }

    @Test
    fun fastApiDetailErrorFromUpstream() {
        m.queueFaults("/api/v1/evaluate" to "status:502:detail=%5B2001%5D%20missing%20auth")
        builder().build().use { c ->
            try {
                c.evaluate(config, wav)
                fail()
            } catch (e: AuthException) {
                assertEquals(2001, e.code)
                assertEquals(502, e.httpStatus)
                assertEquals("[2001] missing auth", e.message)
            }
        }
    }

    @Test
    fun hangIsCutByTotalTimeout() {
        m.queueFaults("/api/v1/evaluate" to "hang")
        builder().totalTimeoutMs(1500).build().use { c ->
            val t0 = System.nanoTime()
            try {
                c.evaluate(config, wav)
                fail()
            } catch (e: RequestTimeoutException) {
                assertEquals(90002, e.code)
            }
            assertTrue((System.nanoTime() - t0) / 1_000_000 < 4000)
        }
    }

    // ------------------------------------------------------------------------- per mode and auth

    @Test
    fun everyNativeModeIsAcceptedAndParsed() {
        val modes = listOf(
            EvaluateConfig(CoreType.WORD, "apple", Language.EN_US),
            EvaluateConfig(CoreType.SENTENCE, "The quick brown fox jumps over the lazy dog.", Language.EN_US),
            EvaluateConfig(CoreType.PASSAGE, "今天天气很好。我们一起去公园散步。", Language.ZH_CN, paragraphNeedWordScore = 1),
            EvaluateConfig(CoreType.CONNECTED, "I want to eat an apple.", Language.EN_US),
            EvaluateConfig(CoreType.OPEN, "介绍一下自己", Language.ZH_CN, taskType = TaskType.FREE),
            EvaluateConfig(CoreType.ALPHA, "A B C", Language.EN_US),
            EvaluateConfig(CoreType.PINYIN, "今天天气很好", Language.ZH_CN, refPinyin = "jin1 tian1 tian1 qi4 hen3 hao3"),
        )
        builder().build().use { c ->
            for (cfg in modes) {
                val r = c.evaluate(cfg, wav)
                assertNotNull("overall of ${cfg.coreType}", r.overall)
                when (cfg.coreType) {
                    CoreType.CONNECTED -> assertNotNull(r.connected)
                    CoreType.OPEN -> assertNotNull(r.open!!.content)
                    CoreType.PASSAGE -> assertTrue(r.sentences[0].words.isNotEmpty())
                    else -> assertTrue(r.words.isNotEmpty())
                }
            }
        }
        val configs = evaluateEntries().map { it.obj("config")!!.str("coreType") }
        assertEquals(modes.map { it.coreType }, configs)
    }

    @Test
    fun compatTtsAndReportAgainstPlatformRules() {
        builder().build().use { c ->
            val compat = c.evaluateCompat(CompatConfig(CompatCoreType.SENT_EVAL_CN, "今天天气很好", Language.ZH_CN, params = mapOf("agegroup" to "2")), wav)
            assertEquals(1, compat.eof)
            assertNotNull(compat.overall)
            val tts = c.tts(TtsRequest("你好世界", speed = 55))
            assertTrue(tts.resolvedUrl.startsWith(m.baseUrl + "/tts/audio/"))
            val report = c.getReport(compat.recordId!!)
            assertEquals(93.7, report.overall!!, 1e-9)
            try {
                c.getReport("unknown_1")
                fail()
            } catch (e: InvalidParameterException) {
                assertEquals(40001, e.code)
            }
            try {
                c.evaluateCompat(CompatConfig(CompatCoreType.PINYIN, "重庆", refPinyin = "chong2 qing4"), wav)
            } catch (e: YuguException) {
                fail("pinyin with refPinyin must pass: $e")
            }
        }
        val log = m.log()
        assertTrue(log.any { it.str("path") == "/sent.eval.cn" && it.str("idempotencyKey") != null })
        assertTrue(log.any { it.str("path") == "/api/v1/tts/generate" && it.str("idempotencyKey") != null })
        assertNull(log.first { it.str("path")!!.startsWith("/api/v1/report/") }.str("idempotencyKey"))
        assertTrue(log.all { it.str("userAgent") == "yugu-android-sdk/2.0.0" })
        assertEquals(3, m.billing().int("billed"))
    }

    @Test
    fun tokenAuthAndBadCredentials() {
        YuguClient.builder().baseUrl(m.baseUrl).auth(Auth.token("mock-jwt-token")).logger(logger).build().use { c ->
            assertNotNull(c.evaluate(config, wav).recordId)
        }
        YuguClient.builder().baseUrl(m.baseUrl).auth(Auth.appKey("mock-app-key", "wrong-secret")).logger(logger).build().use { c ->
            try {
                c.evaluate(config, wav)
                fail()
            } catch (e: AuthException) {
                assertEquals(2003, e.code)
                assertEquals(1, e.attempts)
            }
        }
        YuguClient.builder().baseUrl(m.baseUrl).auth(Auth.appKey("unknown-key", "x")).logger(logger).build().use { c ->
            try {
                c.tts(TtsRequest("你好"))
                fail()
            } catch (e: AuthException) {
                assertEquals(2010, e.code)
            }
        }
    }

    @Test
    fun keyReusedForADifferentRequestIsAConflict() {
        val key = "reuse-" + System.nanoTime()
        builder().build().use { c ->
            c.evaluate(config, wav, RequestOptions(idempotencyKey = key))
            try {
                c.evaluate(config.copy(referenceText = "另一句话"), wav, RequestOptions(idempotencyKey = key))
                fail()
            } catch (e: ConflictException) {
                assertEquals(40903, e.code)
                assertFalse(e.retryable)
                assertEquals(key, e.idempotencyKey)
            }
        }
    }

    @Test
    fun concurrentSameKeyWaitsAndReplays() {
        val key = "inflight-" + System.nanoTime()
        m.setProcessingMs(800)
        try {
            builder().build().use { c ->
                val results = java.util.concurrent.CopyOnWriteArrayList<EvalResult>()
                val threads = (1..2).map {
                    Thread { results.add(c.evaluate(config, wav, RequestOptions(idempotencyKey = key))) }.apply { start() }
                }
                threads.forEach { it.join(10_000) }
                assertEquals(2, results.size)
                assertEquals(1, results.map { it.recordId }.toSet().size)
                assertEquals(1, results.count { it.replayed })
            }
            assertEquals(1, m.billing().int("billed"))
        } finally {
            m.setProcessingMs(50)
        }
    }

    @Test
    fun mockLogShowsConfigPartAsJson() {
        builder().build().use { it.evaluate(config, wav) }
        val e = evaluateEntries().single()
        assertTrue(e.str("contentType")!!.startsWith("multipart/form-data; boundary="))
        val cfg: JObj = e.obj("config")!!
        assertEquals("sentence", cfg.str("coreType"))
        assertEquals(true, cfg.bool("includeReport"))
    }
}
