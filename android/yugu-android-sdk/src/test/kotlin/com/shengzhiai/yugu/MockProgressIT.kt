package com.shengzhiai.yugu

import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.util.Random

/**
 * Reading progress (2.1.0, CONTRACT 5.5) against tools/mock-server: progress on the compat and the
 * native channel, the faults progress-mismatch:<i> and progress-off, and a server kill in the middle
 * of a round after which the replayed audio restarts the server progress from zero.
 *
 * The mock marks one unit read and matched per 8000 bytes, sends a progress frame per 16000 bytes and
 * one more frame with one more unit after end, right before the final result.
 */
class MockProgressIT {

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
        private const val SENTENCE = "我是中国人，我是中国人。我们都是中国人。中华民族是一家人。"
        private val UNITS = "我是中国人我是中国人我们都是中国人中华民族是一家人".map { it.toString() }
    }

    private val m get() = mock!!
    private val logger = CapturingLogger()
    private val pcm = TestEnv.fixturePcm("audio/zh_progress.wav")
    private val nativeConfig = EvaluateConfig(CoreType.SENTENCE, SENTENCE, Language.ZH_CN, realtimeFeedback = true)
    private val compatConfig = CompatConfig(CompatCoreType.SENT_EVAL_CN, SENTENCE, Language.ZH_CN, realtimeFeedback = true)

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

    private fun client() = YuguClient.builder()
        .baseUrl(m.baseUrl)
        .wsBaseUrl(m.wsUrl)
        .auth(Auth.appKey("mock-app-key", "mock-secret-key"))
        .logger(logger)
        .logLevel(LogLevel.DEBUG)
        .reconnectPolicy(ReconnectPolicy(maxAttempts = 3, initialDelayMs = 100, maxDelayMs = 400, jitter = 0.3))
        .random(Random(5))
        .callbackExecutor(TestEnv.callbackThread())
        .build()

    /** Streams the customer sentence through one session, waits for onClosed and checks the guarantees. */
    private fun run(frameDelayMs: Long = 1, open: (YuguClient, RecordingListener) -> StreamSession): Pair<StreamSession, RecordingListener> {
        client().use { c ->
            val l = RecordingListener()
            val s = open(c, l)
            l.session = s
            streamPcm(s, pcm, frameDelayMs)
            s.end()
            assertTrue("terminal callback within 20 s", l.awaitClosed(20))
            assertTrue("violations ${l.violations}", l.violations.isEmpty())
            assertEquals("onClosed last: ${l.events}", "closed", l.events.last().substringBefore(':'))
            assertNotNull("result expected, error=${l.error}", l.result)
            return s to l
        }
    }

    /** readUnits the mock reports for [bytes] of audio: 2 more per 16000 bytes, then one more after end. */
    private fun expectedReadUnits(bytes: Int): List<Int> =
        (1..bytes / 16000).map { minOf(UNITS.size, 2 * it) } + minOf(UNITS.size, bytes / 8000 + 1)

    private fun assertProgress(l: RecordingListener, compat: Boolean, mismatch: Int = -1) {
        assertEquals(expectedReadUnits(pcm.size), l.partials.map { it.readUnits })
        var shengtongItems = 0
        for (p in l.partials) {
            assertTrue(p.hasProgress)
            assertEquals(UNITS.size, p.totalUnits)
            assertEquals(UNITS, p.progress.map { it.word })
            assertEquals(p.progress.count { it.read }, p.readUnits)
            assertEquals(p.progress.count { it.matched }, p.matchedUnits)
            for ((i, w) in p.progress.withIndex()) {
                assertEquals(i < p.readUnits, w.read)
                assertEquals(i < p.readUnits && i != mismatch, w.matched)
            }
            @Suppress("UNCHECKED_CAST")
            val result = p.raw["result"] as Map<String, Any?>
            if (compat) {
                // raw keeps the Shengtong-shaped increment; the per-unit state above came from result.progress
                assertEquals("", p.raw["recordId"])
                shengtongItems += (result["words"] as List<*>).size
            } else {
                assertTrue("native frames carry no words", !result.containsKey("words"))
            }
        }
        if (compat) assertEquals(l.partials.last().readUnits, shengtongItems)
        // the last progress frame came after end() and before the final result
        val ending = l.events.indexOf("state:STARTED->ENDING")
        val lastPartial = l.events.lastIndexOf("partial")
        assertTrue("progress after end: ${l.events}", ending in 0 until lastPartial)
        assertTrue(lastPartial < l.events.indexOf("result"))
    }

    @Test
    fun compatChannelReportsReadingProgress() {
        val (s, l) = run { c, l -> c.streamEvaluateCompat(compatConfig, l) }
        assertProgress(l, compat = true)
        assertEquals("sent.eval.cn", l.result!!.coreType)
        assertEquals(1, m.billing().objList("records").count { it.str("idemKey") == s.idempotencyKey })
    }

    @Test
    fun nativeChannelReportsReadingProgressWhenRequested() {
        val (_, l) = run { c, l -> c.streamEvaluate(nativeConfig, l) }
        assertProgress(l, compat = false)
        assertEquals(minOf(UNITS.size, pcm.size / 8000 + 1), l.partials.last().readUnits)
    }

    @Test
    fun nativeChannelWithoutRealtimeFeedbackSendsNoPartials() {
        val (_, l) = run { c, l -> c.streamEvaluate(nativeConfig.copy(realtimeFeedback = false), l) }
        assertTrue("partials ${l.partials}", l.partials.isEmpty())
    }

    @Test
    fun progressMismatchMarksOneUnitReadButNotMatched() {
        val options = SessionOptions(extraQuery = mapOf("mockFault" to "progress-mismatch:7"))
        val (_, l) = run { c, l -> c.streamEvaluateCompat(compatConfig, l, options) }
        assertProgress(l, compat = true, mismatch = 7)
        val last = l.partials.last()
        assertEquals(ReadingWord("中", read = true, matched = false), last.progress[7])
        assertEquals(last.readUnits - 1, last.matchedUnits)
    }

    @Test
    fun progressOffDeliversBytesOnlyPartials() {
        val options = SessionOptions(extraQuery = mapOf("mockFault" to "progress-off"))
        val (_, l) = run { c, l -> c.streamEvaluate(nativeConfig, l, options) }
        assertEquals((1..pcm.size / 16000).map { 16000L * it } + pcm.size.toLong(), l.partials.map { it.bytes })
        assertTrue(l.partials.all { !it.hasProgress && it.progress.isEmpty() && it.readUnits == -1 && it.matchedUnits == -1 && it.totalUnits == -1 })
    }

    @Test
    fun serverKillMidRoundDeliversNoRegressingProgress() {
        // killed on its 120th inbound frame (start frame plus 119 audio frames), progress had reached 8 units;
        // the reconnect replays the audio and the new server session reports progress from zero again
        m.queueFaults(NATIVE to "ws-kill-after:120")
        val (s, l) = run(frameDelayMs = 2) { c, l -> c.streamEvaluate(nativeConfig, l) }
        assertEquals(1, s.getReconnectCount())
        assertEquals(1, l.reconnected.size)
        val shown = l.partials.map { it.readUnits to it.matchedUnits }
        assertTrue(l.partials.all { it.hasProgress })
        assertTrue("no regressing partial: $shown", shown.zipWithNext().all { (a, b) -> b.first >= a.first && b.second >= a.second })
        val beforeDrop = l.events.subList(0, l.events.indexOf("reconnecting:1")).count { it == "partial" }
        assertTrue("progress shown before the drop: $shown", beforeDrop >= 1 && l.partials[beforeDrop - 1].readUnits >= 4)
        // the replayed progress below that level was held back and logged at DEBUG
        assertTrue(logger.lines.any { it.startsWith("DEBUG ") && it.contains("dropped progress readUnits=2 ") })
        assertEquals(minOf(UNITS.size, pcm.size / 8000 + 1), l.partials.last().readUnits)
        // REPLAY: one bill that covers every byte
        val bills = m.billing().objList("records").filter { it.str("idemKey") == s.idempotencyKey }
        assertEquals(1, bills.size)
        assertEquals(pcm.size.toLong(), bills[0].long("bytes"))
    }
}
