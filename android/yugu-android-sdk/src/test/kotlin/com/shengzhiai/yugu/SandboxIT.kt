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
 * REST base, the WebSocket base is derived from it. Each run uses 4 sandbox calls.
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
}
