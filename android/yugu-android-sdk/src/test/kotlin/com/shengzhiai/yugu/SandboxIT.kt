package com.shengzhiai.yugu

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test

/**
 * End-to-end tests against the sandbox tenant (DESIGN 10, acceptance A-05-2, SANDBOX.md). They run
 * only when YUGU_SANDBOX_APPKEY and YUGU_SANDBOX_SECRET are set, which the nightly CI does; every
 * other run skips them, so local and push builds never call the platform. YUGU_SANDBOX_BASE is the
 * REST base, the WebSocket base is derived from it. Each run uses 6 sandbox calls.
 */
class SandboxIT {

    private val appKey = System.getenv("YUGU_SANDBOX_APPKEY")
    private val secret = System.getenv("YUGU_SANDBOX_SECRET")
    private val baseUrl = (System.getenv("YUGU_SANDBOX_BASE") ?: YuguClient.DEFAULT_BASE_URL).trimEnd('/')
    private val wsUrl = baseUrl.replaceFirst(Regex("^https://"), "wss://").replaceFirst(Regex("^http://"), "ws://")
    private val wav = TestEnv.fixtureBytes("audio/zh_short.wav")
    private val config = EvaluateConfig(CoreType.SENTENCE, "今天天气很好", Language.ZH_CN)

    @Before
    fun requireSandboxKeys() {
        Assume.assumeTrue("sandbox keys not set", !appKey.isNullOrBlank() && !secret.isNullOrBlank())
    }

    private fun client() = YuguClient.builder()
        .baseUrl(baseUrl)
        .wsBaseUrl(wsUrl)
        .auth(Auth.appKey(appKey!!, secret!!))
        .logger(CapturingLogger())
        .callbackExecutor(TestEnv.callbackThread())
        .build()

    @Test
    fun evaluateTwiceWithOneKeyIsReplayed() {
        client().use { c ->
            val key = "sdk-android-sandbox-" + System.currentTimeMillis()
            val first = c.evaluate(config, wav, RequestOptions(idempotencyKey = key))
            assertNotNull(first.overall)
            val second = c.evaluate(config, wav, RequestOptions(idempotencyKey = key))
            assertTrue(second.replayed)
        }
    }

    @Test
    fun compatEvaluate() {
        client().use { c ->
            assertNotNull(c.evaluateCompat(CompatConfig(CompatCoreType.SENT_EVAL_CN, "今天天气很好", Language.ZH_CN), wav).overall)
        }
    }

    @Test
    fun streamEvaluate() {
        client().use { c ->
            val l = RecordingListener()
            val s = c.streamEvaluate(config, l)
            l.session = s
            streamPcm(s, TestEnv.fixturePcm("audio/zh_short.wav"), 20)
            s.end()
            assertTrue(l.awaitClosed(60))
            assertTrue("violations ${l.violations}", l.violations.isEmpty())
            assertNotNull("error ${l.error}", l.result)
        }
    }

    // reading progress (2.1.0): the customer sentence at real-time pace, one sandbox call per channel

    private val progressText = "我是中国人，我是中国人。我们都是中国人。中华民族是一家人。"

    private fun streamWithProgress(open: (YuguClient, RecordingListener) -> StreamSession) {
        client().use { c ->
            val l = RecordingListener()
            val s = open(c, l)
            l.session = s
            streamPcm(s, TestEnv.fixturePcm("audio/zh_progress.wav"), 20)
            s.end()
            assertTrue(l.awaitClosed(60))
            assertTrue("violations ${l.violations}", l.violations.isEmpty())
            assertNotNull("error ${l.error}", l.result)
            val progress = l.partials.filter { it.hasProgress }
            assertTrue("progress partials: ${l.partials}", progress.isNotEmpty())
            assertTrue(progress.all { p -> p.totalUnits == 25 && p.progress.joinToString("") { it.word } == "我是中国人我是中国人我们都是中国人中华民族是一家人" })
            assertTrue(progress.zipWithNext().all { (a, b) -> b.readUnits >= a.readUnits && b.matchedUnits >= a.matchedUnits })
            assertTrue("most of the sentence read: ${progress.last()}", progress.last().readUnits >= 20)
        }
    }

    @Test
    fun nativeStreamWithReadingProgress() = streamWithProgress { c, l ->
        c.streamEvaluate(EvaluateConfig(CoreType.SENTENCE, progressText, Language.ZH_CN, realtimeFeedback = true), l)
    }

    @Test
    fun compatStreamWithReadingProgress() = streamWithProgress { c, l ->
        c.streamEvaluateCompat(CompatConfig(CompatCoreType.SENT_EVAL_CN, progressText, Language.ZH_CN, realtimeFeedback = true), l)
    }
}
