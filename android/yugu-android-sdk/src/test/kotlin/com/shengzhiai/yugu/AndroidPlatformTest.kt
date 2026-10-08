package com.shengzhiai.yugu

import android.Manifest
import android.media.AudioRecord
import android.os.Looper
import com.shengzhiai.yugu.audio.AudioRecordSource
import com.shengzhiai.yugu.audio.Recorder
import com.shengzhiai.yugu.internal.MainThreadExecutor
import com.shengzhiai.yugu.internal.Platform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/** Android framework glue on Robolectric: main looper delivery, Logcat, AudioRecord, permission check. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class AndroidPlatformTest {

    private val direct = Executor { it.run() }

    @Test
    fun callbacksDefaultToTheMainLooper() {
        val executor = Platform.mainExecutorOrNull()
        assertSame(MainThreadExecutor, executor)
        assertSame(MainThreadExecutor, Platform.defaultCallbackExecutor())
        assertTrue(Platform.isMainThread())
        val ran = AtomicBoolean()
        executor!!.execute { ran.set(Looper.myLooper() == Looper.getMainLooper()) }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(ran.get())
    }

    @Test
    fun logcatLoggerWritesEachLevel() {
        ShadowLog.clear()
        val l = LogcatLogger()
        l.log(LogLevel.ERROR, "YuguSDK", "e1", IllegalStateException("boom"))
        l.log(LogLevel.WARN, "YuguSDK", "w1", null)
        l.log(LogLevel.INFO, "YuguSDK", "i1", null)
        l.log(LogLevel.DEBUG, "YuguSDK", "d1", null)
        val logs = ShadowLog.getLogsForTag("YuguSDK")
        assertEquals(listOf(android.util.Log.ERROR, android.util.Log.WARN, android.util.Log.INFO, android.util.Log.DEBUG), logs.map { it.type })
        assertTrue(logs[0].msg.startsWith("e1\n") && logs[0].msg.contains("IllegalStateException"))
        assertEquals("w1", logs[1].msg)
    }

    @Test
    fun clientUsesLogcatByDefault() {
        ShadowLog.clear()
        YuguClient.builder().auth(Auth.appKey("ak_robolectric", "secret")).logLevel(LogLevel.INFO).build().close()
        val logs = ShadowLog.getLogsForTag("YuguSDK").map { it.msg }
        assertTrue(logs.toString(), logs.any { it.contains("client created") && it.contains("ak_r***") })
        assertTrue(logs.none { it.contains("secret") })
    }

    @Test
    fun blockingCallOnTheMainThreadIsLogged() {
        val server = okhttp3.mockwebserver.MockWebServer()
        server.start()
        try {
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody(TestEnv.fixtureText("platform/native_evaluate_sentence_zh.json")))
            val logger = CapturingLogger()
            YuguClient.builder().baseUrl(server.url("/").toString()).auth(Auth.token("t")).logger(logger).build().use {
                it.evaluate(EvaluateConfig(CoreType.SENTENCE, "今天天气很好"), TestEnv.fixtureBytes("audio/zh_short.wav"))
            }
            assertTrue(logger.text(), logger.text().contains("在主线程调用会被系统拒绝"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun audioRecordSourceStartsReadsAndReleases() {
        val src = AudioRecordSource(16_000, 640)
        src.start()
        val buf = ByteArray(640)
        assertEquals(640, src.read(buf, 0, 640))
        src.stop()
        src.start() // resume after pause
        src.release()
        src.release()
        src.stop()
        assertEquals(AudioRecord.ERROR_INVALID_OPERATION, src.read(buf, 0, 640))
    }

    @Test
    fun recorderChecksPermissionBeforeTouchingTheMicrophone() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        val denied = Recorder(app, callbackExecutor = direct)
        try {
            denied.start()
            fail("started without permission")
        } catch (e: PermissionException) {
            assertEquals(90201, e.code)
        }
        assertEquals(Recorder.State.IDLE, denied.getState())
        denied.release()

        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val r = Recorder(app, callbackExecutor = direct)
        val states = CopyOnWriteArrayList<String>()
        val frames = CopyOnWriteArrayList<Int>()
        r.setListener(object : Recorder.Listener {
            override fun onStateChanged(oldState: Recorder.State, newState: Recorder.State) {
                states.add("$oldState->$newState")
            }

            override fun onFrame(pcm: ByteArray) {
                frames.add(pcm.size)
            }
        })
        r.start()
        val end = System.nanoTime() + 5_000_000_000L
        while (frames.size < 5 && System.nanoTime() < end) Thread.sleep(5)
        r.stop()
        r.release()
        assertTrue(frames.size >= 5)
        assertTrue(frames.all { it == 640 })
        assertEquals(listOf("IDLE->RECORDING", "RECORDING->STOPPED", "STOPPED->RELEASED"), states)
        assertNotNull(r.toWav())
    }
}
