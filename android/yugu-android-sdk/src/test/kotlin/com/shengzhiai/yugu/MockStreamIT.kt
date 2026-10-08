package com.shengzhiai.yugu

import com.shengzhiai.yugu.internal.JObj
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.util.Random
import java.util.concurrent.TimeUnit

/**
 * V-04 streaming resilience against tools/mock-server: server kill, abnormal close, silent server,
 * refused handshake, retryable error frames, client network drop of 10 s and network switch.
 * Every test asserts the terminal callback arrives within a bound and onClosed is last.
 */
class MockStreamIT {

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

        private const val NATIVE = "/api/v1/ws/evaluate"
    }

    private val m get() = mock!!
    private val logger = CapturingLogger()
    private val events = RecordingEvents()
    private val pcm = TestEnv.fixturePcm("audio/zh_short.wav")
    private val config = EvaluateConfig(CoreType.SENTENCE, "今天天气很好", Language.ZH_CN)
    private val fastReconnect = ReconnectPolicy(maxAttempts = 3, initialDelayMs = 100, maxDelayMs = 400, jitter = 0.3)

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

    private fun builder(wsUrl: String = m.wsUrl) = YuguClient.builder()
        .baseUrl(m.baseUrl)
        .wsBaseUrl(wsUrl)
        .auth(Auth.appKey("mock-app-key", "mock-secret-key"))
        .logger(logger)
        .logLevel(LogLevel.DEBUG)
        .eventListener(events)
        .reconnectPolicy(fastReconnect)
        .random(Random(3))
        .callbackExecutor(TestEnv.callbackThread())

    private fun run(
        c: YuguClient,
        options: SessionOptions = SessionOptions.DEFAULT,
        frameDelayMs: Long = 2,
        audio: ByteArray = pcm,
        during: ((Int) -> Unit)? = null,
    ): Pair<StreamSession, RecordingListener> {
        val l = RecordingListener()
        val s = c.streamEvaluate(config, l, options)
        l.session = s
        streamPcm(s, audio, frameDelayMs, during)
        s.end()
        return s to l
    }

    private fun assertOrdered(l: RecordingListener) {
        assertTrue("violations ${l.violations}", l.violations.isEmpty())
        assertEquals("onClosed last: ${l.events}", "closed", l.events.last().substringBefore(':'))
        assertEquals(1, l.terminalCallbacks())
    }

    private fun billingFor(key: String?): List<JObj> = m.billing().objList("records").filter { it.str("idemKey") == key }

    private fun assertReplayedOnceWithAllAudio(s: StreamSession, l: RecordingListener) {
        assertNotNull("result expected, error=${l.error}", l.result)
        assertNull(l.error)
        val bills = billingFor(s.idempotencyKey)
        assertEquals("billed once", 1, bills.size)
        assertEquals("REPLAY delivered every byte to the new session", pcm.size.toLong(), bills[0].long("bytes"))
        val ws = m.log().filter { it.str("method") == "WS" }
        assertTrue(ws.size >= 2)
        assertTrue("same key on every connection", ws.all { it.str("idempotencyKey") == s.idempotencyKey })
        val st = l.states()
        val reconnectAt = st.indexOfFirst { it.endsWith("->RECONNECTING") }
        assertTrue("reconnect announced: $st", reconnectAt >= 0)
        val i1 = l.events.indexOf("reconnecting:1")
        val i2 = l.events.indexOfFirst { it.startsWith("reconnected:") }
        val i3 = l.events.indexOf("result")
        assertTrue("order reconnecting < reconnected < result: ${l.events}", i1 in 0 until i2 && i2 < i3)
        assertEquals(listOf("COMPLETED->CLOSED"), st.takeLast(1))
    }

    @Test
    fun v04_serverKillsConnection_replayYieldsOneResult() {
        m.queueFaults(NATIVE to "ws-kill-after:20")
        builder().build().use { c ->
            val (s, l) = run(c)
            assertTrue("terminal callback within 20 s", l.awaitClosed(20))
            assertOrdered(l)
            assertReplayedOnceWithAllAudio(s, l)
            assertTrue(l.reconnecting[0].second is NetworkException)
            assertEquals(1, s.getReconnectCount())
        }
    }

    @Test
    fun v04_abnormalClose1011_replayYieldsOneResult() {
        m.queueFaults(NATIVE to "ws-close-after:30")
        builder().build().use { c ->
            val (s, l) = run(c)
            assertTrue(l.awaitClosed(20))
            assertOrdered(l)
            assertReplayedOnceWithAllAudio(s, l)
            assertTrue(l.reconnecting[0].second.message!!.contains("1011"))
        }
    }

    @Test
    fun v04_silentServer_resultTimeoutThenReplay() {
        m.queueFaults(NATIVE to "ws-silent:40")
        builder().resultTimeoutMs(1500).build().use { c ->
            val (s, l) = run(c)
            assertTrue(l.awaitClosed(20))
            assertOrdered(l)
            assertReplayedOnceWithAllAudio(s, l)
            assertEquals(90007, l.reconnecting[0].second.code)
        }
    }

    @Test
    fun v04_silentServer_failPolicyReportsTimeout() {
        m.queueFaults(NATIVE to "ws-silent:40")
        builder().resultTimeoutMs(1000).audioBufferPolicy(AudioBufferPolicy.FAIL).build().use { c ->
            val t0 = System.nanoTime()
            val (_, l) = run(c)
            assertTrue(l.awaitClosed(10))
            assertOrdered(l)
            assertEquals(90007, l.error!!.code)
            assertTrue(l.error is RequestTimeoutException)
            assertTrue((System.nanoTime() - t0) / 1_000_000 < 6000)
            assertTrue(l.reconnecting.isEmpty())
        }
        assertEquals(0, m.billing().int("billed"))
    }

    @Test
    fun v04_failPolicy_killYieldsOnError() {
        m.queueFaults(NATIVE to "ws-kill-after:10")
        builder().audioBufferPolicy(AudioBufferPolicy.FAIL).build().use { c ->
            val (s, l) = run(c)
            assertTrue(l.awaitClosed(10))
            assertOrdered(l)
            assertTrue(l.error is NetworkException)
            assertEquals(90001, l.error!!.code)
            assertEquals(s.idempotencyKey, l.error!!.idempotencyKey)
            assertTrue(l.reconnecting.isEmpty())
            assertEquals(listOf("FAILED->CLOSED"), l.states().takeLast(1))
            assertEquals(1006, l.closedCode)
        }
        assertEquals(0, m.billing().int("billed"))
    }

    @Test
    fun v04_dropPolicy_newSessionScoresOnlyLaterAudio() {
        m.queueFaults(NATIVE to "ws-kill-after:20")
        builder().audioBufferPolicy(AudioBufferPolicy.DROP).build().use { c ->
            val (s, l) = run(c, frameDelayMs = 5)
            assertTrue(l.awaitClosed(20))
            assertOrdered(l)
            assertNotNull(l.result)
            val bills = billingFor(s.idempotencyKey)
            assertEquals(1, bills.size)
            assertTrue("DROP scores less than everything", bills[0].long("bytes")!! < pcm.size)
            assertEquals(1, l.reconnected.size)
        }
    }

    @Test
    fun v04_reconnectExhaustedYieldsOnError() {
        builder().build().use { c ->
            // every connection is killed on its first frame, so no reconnect attempt reaches started
            val (_, l) = run(c, SessionOptions(extraQuery = mapOf("mockFault" to "ws-kill-after:1")))
            assertTrue(l.awaitClosed(20))
            assertOrdered(l)
            assertEquals(90006, l.error!!.code)
            assertFalse(l.error!!.retryable)
            assertEquals(listOf(1, 2, 3), l.reconnecting.map { it.first })
            assertTrue(l.reconnected.isEmpty())
            assertEquals(listOf("1:false", "2:false", "3:false"), events.reconnects)
        }
        assertEquals(4, m.log().count { it.str("method") == "WS" })
        assertEquals(0, m.billing().int("billed"))
    }

    @Test
    fun v04_retryableErrorFrameReconnects() {
        m.queueFaults(NATIVE to "ws-error:50200")
        builder().build().use { c ->
            val (s, l) = run(c)
            assertTrue(l.awaitClosed(20))
            assertOrdered(l)
            assertReplayedOnceWithAllAudio(s, l)
            assertEquals(50200, l.reconnecting[0].second.code)
        }
    }

    @Test
    fun v04_nonRetryableErrorFrameFails() {
        m.queueFaults(NATIVE to "ws-error:40001")
        builder().build().use { c ->
            val (_, l) = run(c)
            assertTrue(l.awaitClosed(10))
            assertOrdered(l)
            assertTrue(l.error is InvalidParameterException)
            assertTrue(l.reconnecting.isEmpty())
        }
    }

    @Test
    fun v04_refusedHandshakeIsRetried() {
        m.queueFaults(NATIVE to "ws-refuse")
        builder().build().use { c ->
            val (s, l) = run(c)
            assertTrue(l.awaitClosed(20))
            assertOrdered(l)
            assertNotNull(l.result)
            assertEquals(503, l.reconnecting[0].second.httpStatus)
            assertEquals(1, billingFor(s.idempotencyKey).size)
        }
    }

    @Test
    fun v04_badSignatureIsFatalWithoutReconnect() {
        builder().auth(Auth.appKey("mock-app-key", "wrong")).build().use { c ->
            val (_, l) = run(c)
            assertTrue(l.awaitClosed(10))
            assertOrdered(l)
            assertTrue(l.error is PermissionException)
            assertEquals(403, l.error!!.httpStatus)
            assertTrue(l.reconnecting.isEmpty())
        }
    }

    @Test
    fun v04_delayedResultWithinTimeout() {
        m.queueFaults(NATIVE to "ws-delay-result:1000")
        builder().resultTimeoutMs(5000).build().use { c ->
            val (_, l) = run(c)
            assertTrue(l.awaitClosed(10))
            assertOrdered(l)
            assertNotNull(l.result)
            assertTrue(l.reconnecting.isEmpty())
        }
    }

    @Test
    fun v04_clientNetworkDrop10sWithDefaultSettings() {
        // Acceptance 6.4 (a): the network is gone for 10 s. Default reconnect policy, heartbeat and timeouts.
        val proxy = ChaosProxy(m.port)
        try {
            YuguClient.builder()
                .baseUrl(m.baseUrl)
                .wsBaseUrl("ws://127.0.0.1:${proxy.port}")
                .auth(Auth.appKey("mock-app-key", "mock-secret-key"))
                .logger(logger)
                .logLevel(LogLevel.DEBUG)
                .eventListener(events)
                .callbackExecutor(TestEnv.callbackThread())
                .build().use { c ->
                    val dropMs = (System.getProperty("yugu.networkDropMs") ?: "10000").toLong()
                    val t0 = System.nanoTime()
                    val (s, l) = run(c, frameDelayMs = 20) { frame ->
                        if (frame == 30) {
                            proxy.down()
                            Thread {
                                Thread.sleep(dropMs)
                                proxy.up()
                            }.start()
                        }
                    }
                    assertTrue("terminal callback within the bound", l.awaitClosed(dropMs / 1000 + 40))
                    val ms = (System.nanoTime() - t0) / 1_000_000
                    assertOrdered(l)
                    assertTrue("session outlived the drop: $ms ms", ms >= dropMs)
                    assertReplayedOnceWithAllAudio(s, l)
                    assertTrue("several attempts failed while the network was down", l.reconnecting.size >= 3)
                    assertTrue(l.reconnecting.size <= ReconnectPolicy.DEFAULT.maxAttempts)
                    assertEquals(l.reconnecting.size, l.reconnected.single().first)
                }
        } finally {
            proxy.close()
        }
    }

    @Test
    fun v04_networkStallDetectedByHeartbeat() {
        val proxy = ChaosProxy(m.port)
        try {
            builder("ws://127.0.0.1:${proxy.port}")
                .pingIntervalMs(1000)
                .connectTimeoutMs(1500)
                .reconnectPolicy(ReconnectPolicy(maxAttempts = 8, initialDelayMs = 500, maxDelayMs = 2000, jitter = 0.3))
                .build().use { c ->
                    val dropMs = (System.getProperty("yugu.networkDropMs") ?: "10000").toLong()
                    val t0 = System.nanoTime()
                    val (s, l) = run(c, frameDelayMs = 20) { frame ->
                        if (frame == 30) {
                            proxy.mode = ChaosProxy.Mode.BLACKHOLE
                            Thread {
                                Thread.sleep(dropMs)
                                proxy.mode = ChaosProxy.Mode.FORWARD
                            }.start()
                        }
                    }
                    assertTrue("terminal callback within the bound", l.awaitClosed(dropMs / 1000 + 30))
                    val ms = (System.nanoTime() - t0) / 1_000_000
                    assertOrdered(l)
                    assertTrue("session outlived the drop: $ms ms", ms >= dropMs)
                    assertReplayedOnceWithAllAudio(s, l)
                    assertTrue(l.reconnecting.size >= 2)
                    assertTrue("heartbeat or handshake timeouts: ${l.reconnecting.map { it.second.code }}", l.reconnecting.all { it.second.retryable })
                }
        } finally {
            proxy.close()
        }
    }

    @Test
    fun v04_networkSwitchResetsSocket() {
        val proxy = ChaosProxy(m.port)
        try {
            builder("ws://127.0.0.1:${proxy.port}").build().use { c ->
                val (s, l) = run(c, frameDelayMs = 10) { frame -> if (frame == 40) proxy.resetAll() }
                assertTrue(l.awaitClosed(20))
                assertOrdered(l)
                assertReplayedOnceWithAllAudio(s, l)
                assertTrue(l.reconnecting[0].second is NetworkException)
            }
        } finally {
            proxy.close()
        }
    }

    @Test
    fun oneHugeChunkIsSplitSoThePlatformAcceptsIt() {
        // the platform closes frames above 128 KB with 1009, sendAudio splits them into 32000-byte frames
        val huge = pcm + pcm + pcm + pcm
        builder().build().use { c ->
            val l = RecordingListener()
            val s = c.streamEvaluate(config, l)
            l.session = s
            assertTrue(s.sendAudio(huge))
            s.end()
            assertTrue(l.awaitClosed(15))
            assertOrdered(l)
            assertNotNull("error ${l.error}", l.result)
            assertEquals(huge.size.toLong(), billingFor(s.idempotencyKey).single().long("bytes"))
        }
    }

    @Test
    fun sameKeyAcrossSessionsReplaysWithoutBilling() {
        val key = "ws-replay-" + System.nanoTime()
        builder().build().use { c ->
            val (_, a) = run(c, SessionOptions(idempotencyKey = key))
            assertTrue(a.awaitClosed(10))
            val (_, b) = run(c, SessionOptions(idempotencyKey = key))
            assertTrue(b.awaitClosed(10))
            assertFalse(a.result!!.replayed)
            assertTrue(b.result!!.replayed)
            assertEquals(a.result!!.recordId, b.result!!.recordId)
        }
        assertEquals(1, m.billing().int("billed"))
    }

    @Test
    fun compatStreamWithProgressFrames() {
        builder().build().use { c ->
            val l = RecordingListener()
            val s = c.streamEvaluateCompat(CompatConfig(CompatCoreType.SENT_EVAL_CN, "今天天气很好", Language.ZH_CN, realtimeFeedback = true), l)
            l.session = s
            streamPcm(s, pcm, 1)
            s.end()
            assertTrue(l.awaitClosed(10))
            assertOrdered(l)
            assertTrue(l.partials.size >= 3)
            assertEquals(16000L, l.partials[0].bytes)
            assertEquals("sent.eval.cn", l.result!!.coreType)
            val bills = billingFor(s.idempotencyKey)
            assertEquals("ws-compat", bills.single().str("op"))
        }
    }

    @Test
    fun recorderStreamsIntoALiveSession() {
        // Recorder with a fake microphone feeding the real mock session.
        val src = RecorderTest.FakeSource(pcm)
        val rec = com.shengzhiai.yugu.audio.Recorder({ src }, com.shengzhiai.yugu.audio.RecorderConfig(), null, TestEnv.callbackThread())
        builder().build().use { c ->
            val l = RecordingListener()
            val s = c.streamEvaluate(config, l)
            l.session = s
            assertTrue(l.started.await(5, TimeUnit.SECONDS))
            rec.start(s)
            val end = System.nanoTime() + 5_000_000_000L
            while (rec.pcm().size < 32000 && System.nanoTime() < end) Thread.sleep(5)
            rec.stop()
            s.end()
            assertTrue(l.awaitClosed(10))
            assertOrdered(l)
            assertNotNull(l.result)
            val bills = billingFor(s.idempotencyKey)
            assertEquals(rec.pcm().size.toLong(), bills.single().long("bytes"))
            rec.release()
            assertTrue(src.releases.get() >= 1)
        }
    }
}
