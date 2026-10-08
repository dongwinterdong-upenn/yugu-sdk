package com.shengzhiai.yugu

import com.shengzhiai.yugu.audio.PcmSource
import com.shengzhiai.yugu.audio.Recorder
import com.shengzhiai.yugu.audio.RecorderConfig
import com.shengzhiai.yugu.audio.WavFormat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Recorder state machine, microphone release on every path, streaming into a session (B-05). */
class RecorderTest {

    /** Fake microphone: replays [pcm] in a loop at about 20x real time. */
    class FakeSource(
        private val pcm: ByteArray,
        private val failOnStart: YuguException? = null,
        private val failAfterReads: Int = -1,
        private val failOnResume: Boolean = false,
    ) : PcmSource {
        val starts = AtomicInteger()
        val stops = AtomicInteger()
        val releases = AtomicInteger()
        val reads = AtomicInteger()

        @Volatile
        private var running = false
        private var pos = 0

        override fun start() {
            if (failOnStart != null) throw failOnStart
            if (failOnResume && starts.get() >= 1) throw YuguErrors.local(90202, "busy on resume")
            starts.incrementAndGet()
            running = true
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (!running) {
                Thread.sleep(2)
                return 0
            }
            if (failAfterReads >= 0 && reads.get() >= failAfterReads) return -3
            reads.incrementAndGet()
            Thread.sleep(1)
            for (i in 0 until length) {
                buffer[offset + i] = pcm[pos]
                pos = (pos + 1) % pcm.size
            }
            return length
        }

        override fun stop() {
            running = false
            stops.incrementAndGet()
        }

        override fun release() {
            running = false
            releases.incrementAndGet()
        }
    }

    private val pcm = TestEnv.fixturePcm("audio/zh_short.wav")
    private val sources = CopyOnWriteArrayList<FakeSource>()
    private val callbacks = TestEnv.callbackThread()

    private fun recorder(
        config: RecorderConfig = RecorderConfig(),
        make: () -> FakeSource = { FakeSource(pcm) },
    ): Recorder = Recorder({ make().also { sources.add(it) } }, config, null, callbacks)

    class Events : Recorder.Listener {
        val states: MutableList<String> = CopyOnWriteArrayList()
        val frames = AtomicInteger()
        val sizes: MutableSet<Int> = java.util.concurrent.ConcurrentHashMap.newKeySet()
        val levels: MutableList<Int> = CopyOnWriteArrayList()
        val errors: MutableList<YuguException> = CopyOnWriteArrayList()
        val error = CountDownLatch(1)

        override fun onStateChanged(oldState: Recorder.State, newState: Recorder.State) {
            states.add("$oldState->$newState")
        }

        override fun onFrame(pcm: ByteArray) {
            frames.incrementAndGet()
            sizes.add(pcm.size)
        }

        override fun onLevel(level: Int) {
            levels.add(level)
        }

        override fun onError(error: YuguException) {
            errors.add(error)
            this.error.countDown()
        }
    }

    private fun waitFor(timeoutMs: Long = 5000, cond: () -> Boolean) {
        val end = System.nanoTime() + timeoutMs * 1_000_000
        while (!cond()) {
            if (System.nanoTime() > end) fail("condition not reached in $timeoutMs ms")
            Thread.sleep(5)
        }
    }

    @Test
    fun fullLifecycleAndMicrophoneRelease() {
        val r = recorder()
        val ev = Events()
        r.setListener(ev)
        assertEquals(Recorder.State.IDLE, r.getState())
        r.start()
        assertEquals(Recorder.State.RECORDING, r.getState())
        waitFor { ev.frames.get() >= 5 }
        r.pause()
        assertEquals(Recorder.State.PAUSED, r.getState())
        Thread.sleep(50)
        val pausedFrames = ev.frames.get()
        Thread.sleep(100)
        assertEquals("no frames while paused", pausedFrames, ev.frames.get())
        r.resume()
        waitFor { ev.frames.get() >= pausedFrames + 5 }
        r.stop()
        assertEquals(Recorder.State.STOPPED, r.getState())
        assertEquals(1, sources.size)
        waitFor { sources[0].releases.get() >= 1 }
        assertEquals(2, sources[0].starts.get())
        val bytes = r.pcm()
        assertEquals(0, bytes.size % 640)
        assertEquals(bytes.size.toLong() * 1000 / 32000, r.durationMs())
        assertEquals(setOf(640), ev.sizes)
        assertTrue(ev.levels.all { it in 0..100 })
        // a second take reuses the recorder with a new microphone instance
        r.start()
        waitFor { r.pcm().size >= 640 * 3 }
        r.stop()
        assertEquals(2, sources.size)
        waitFor { sources[1].releases.get() >= 1 }
        r.release()
        r.release()
        r.close()
        assertEquals(Recorder.State.RELEASED, r.getState())
        waitFor { ev.states.size >= 7 }
        assertEquals(
            listOf(
                "IDLE->RECORDING", "RECORDING->PAUSED", "PAUSED->RECORDING", "RECORDING->STOPPED",
                "STOPPED->RECORDING", "RECORDING->STOPPED", "STOPPED->RELEASED",
            ),
            ev.states,
        )
        try {
            r.start()
            fail()
        } catch (e: IllegalSessionStateException) {
            assertEquals(90009, e.code)
        }
        // stop and pause on a released recorder are harmless no-ops
        r.stop()
        r.pause()
        r.resume()
    }

    @Test
    fun recordedAudioRoundTripsAsWav() {
        val r = recorder()
        r.start()
        waitFor { r.pcm().size >= 640 * 10 }
        r.stop()
        val wav = r.toWav()
        val info = WavFormat.parse(wav)!!
        assertEquals(16000, info.sampleRate)
        assertArrayEquals(r.pcm(), WavFormat.pcmData(wav))
        assertArrayEquals(pcm.copyOf(640 * 10), r.pcm().copyOf(640 * 10))
        val f = File.createTempFile("rec", ".wav")
        try {
            r.writeWav(f)
            assertArrayEquals(wav, f.readBytes())
        } finally {
            f.delete()
            r.release()
        }
    }

    @Test
    fun startWhileRecordingIsRejected() {
        val r = recorder()
        r.start()
        try {
            r.start()
            fail()
        } catch (e: IllegalSessionStateException) {
            assertEquals(90009, e.code)
        }
        r.release()
        waitFor { sources[0].releases.get() >= 1 }
    }

    @Test
    fun microphoneUnavailableOnStartIsReleased() {
        val r = recorder { FakeSource(pcm, failOnStart = YuguErrors.local(90202)) }
        try {
            r.start()
            fail()
        } catch (e: IllegalSessionStateException) {
            assertEquals(90202, e.code)
        }
        assertEquals(Recorder.State.IDLE, r.getState())
        assertEquals(1, sources[0].releases.get())
        val denied = recorder { FakeSource(pcm, failOnStart = YuguErrors.local(90201)) }
        try {
            denied.start()
            fail()
        } catch (e: PermissionException) {
            assertEquals(90201, e.code)
        }
        r.release()
        denied.release()
    }

    @Test
    fun runtimeFailureOnStartIsMappedAndReleased() {
        val src = object : PcmSource {
            var released = false
            override fun start() = throw IllegalStateException("hw")
            override fun read(buffer: ByteArray, offset: Int, length: Int) = 0
            override fun stop() {}
            override fun release() {
                released = true
            }
        }
        val r = Recorder({ src }, RecorderConfig(), null, callbacks)
        try {
            r.start()
            fail()
        } catch (e: IllegalSessionStateException) {
            assertEquals(90202, e.code)
        }
        assertTrue(src.released)
    }

    @Test
    fun readErrorStopsAndReleases() {
        val r = recorder { FakeSource(pcm, failAfterReads = 3) }
        val ev = Events()
        r.setListener(ev)
        r.start()
        assertTrue(ev.error.await(5, TimeUnit.SECONDS))
        assertEquals(90203, ev.errors[0].code)
        waitFor { r.getState() == Recorder.State.STOPPED }
        waitFor { sources[0].releases.get() >= 1 }
        // the recorder can start again after an error
        r.release()
    }

    @Test
    fun resumeFailureReleasesMicrophone() {
        val r = recorder { FakeSource(pcm, failOnResume = true) }
        val ev = Events()
        r.setListener(ev)
        r.start()
        waitFor { ev.frames.get() >= 2 }
        r.pause()
        try {
            r.resume()
            fail()
        } catch (e: IllegalSessionStateException) {
            assertEquals(90202, e.code)
        }
        waitFor { r.getState() == Recorder.State.STOPPED }
        waitFor { sources[0].releases.get() >= 1 }
        assertTrue(ev.error.await(5, TimeUnit.SECONDS))
        r.release()
    }

    @Test
    fun maxDurationStopsAutomatically() {
        val r = recorder(RecorderConfig(maxDurationMs = 100))
        r.start()
        waitFor { r.getState() == Recorder.State.STOPPED }
        assertEquals(3200, r.pcm().size)
        waitFor { sources[0].releases.get() >= 1 }
        r.release()
    }

    @Test
    fun keepPcmFalseKeepsNothing() {
        val r = recorder(RecorderConfig(keepPcm = false))
        val ev = Events()
        r.setListener(ev)
        r.start()
        waitFor { ev.frames.get() >= 3 }
        r.stop()
        assertEquals(0, r.pcm().size)
        assertTrue(r.durationMs() > 0)
        r.release()
    }

    @Test
    fun listenerRemovalStopsCallbacks() {
        val r = recorder()
        val ev = Events()
        r.setListener(ev)
        r.start()
        waitFor { ev.frames.get() >= 2 }
        r.removeListener()
        Thread.sleep(30)
        val n = ev.frames.get()
        Thread.sleep(100)
        assertEquals(n, ev.frames.get())
        r.setListener(ev)
        waitFor { ev.frames.get() > n }
        r.setListener(null)
        r.release()
    }

    @Test
    fun stopFromTheCaptureThreadDoesNotDeadlock() {
        val r = recorder()
        val stopped = CountDownLatch(1)
        r.setListener(object : Recorder.Listener {
            override fun onFrame(pcm: ByteArray) {
                r.stop()
                stopped.countDown()
            }
        })
        r.start()
        assertTrue(stopped.await(5, TimeUnit.SECONDS))
        waitFor { sources[0].releases.get() >= 1 }
        assertEquals(Recorder.State.STOPPED, r.getState())
        r.release()
    }

    @Test
    fun listenerExceptionsDoNotStopCapture() {
        val r = recorder()
        val n = AtomicInteger()
        r.setListener(object : Recorder.Listener {
            override fun onFrame(pcm: ByteArray) {
                n.incrementAndGet()
                throw IllegalStateException("bug")
            }

            override fun onStateChanged(oldState: Recorder.State, newState: Recorder.State) = throw IllegalStateException("bug")
        })
        r.start()
        waitFor { n.get() >= 5 }
        r.release()
    }

    /** StreamSession stand-in that records frames. */
    class FakeSession(private val acceptFrames: Int = Int.MAX_VALUE) : StreamSession {
        val frames: MutableList<ByteArray> = CopyOnWriteArrayList()
        override val sessionId = "fake"
        override val idempotencyKey: String? = null
        override fun getState() = SessionState.STARTED
        override fun isActive() = true
        override fun getReconnectCount() = 0
        override fun sendAudio(pcm: ByteArray) = sendAudio(pcm, 0, pcm.size)
        override fun sendAudio(pcm: ByteArray, offset: Int, length: Int): Boolean {
            if (frames.size >= acceptFrames) return false
            frames.add(pcm.copyOfRange(offset, offset + length))
            return true
        }

        override fun end() {}
        override fun cancel() {}
        override fun close() {}
    }

    @Test
    fun streamsFramesIntoASession() {
        val r = recorder()
        val s = FakeSession()
        r.start(s)
        waitFor { s.frames.size >= 20 }
        r.stop()
        assertTrue(s.frames.all { it.size == 640 })
        val sent = s.frames.fold(ByteArray(0)) { acc, f -> acc + f }
        assertArrayEquals(r.pcm().copyOf(sent.size), sent)
        r.release()
    }

    @Test
    fun forwardingStopsWhenTheSessionRefuses() {
        val r = recorder()
        val s = FakeSession(acceptFrames = 3)
        r.start(s)
        waitFor { r.pcm().size >= 640 * 10 }
        r.stop()
        assertEquals(3, s.frames.size)
        r.release()
    }

    @Test
    fun hundredCyclesReleaseEveryMicrophoneWithoutThreadGrowth() {
        val before = Thread.activeCount()
        repeat(100) {
            val r = recorder()
            r.setListener(Events())
            r.start()
            waitFor { r.pcm().size >= 640 }
            r.stop()
            r.release()
        }
        waitFor { sources.all { it.releases.get() >= 1 } }
        assertEquals(100, sources.size)
        waitFor(10_000) { Thread.activeCount() <= before + 4 }
    }

    @Test
    fun levelMapping() {
        assertEquals(0, Recorder.level(ByteArray(640)))
        assertEquals(0, Recorder.level(ByteArray(0)))
        val loud = ByteArray(640) { i -> if (i % 2 == 1) 0x7F else 0xFF.toByte() }
        assertEquals(100, Recorder.level(loud))
        val mid = ByteArray(640)
        for (i in 0 until 320) {
            mid[2 * i] = (1000 and 0xFF).toByte()
            mid[2 * i + 1] = (1000 shr 8).toByte()
        }
        val lv = Recorder.level(mid) // 1000 is about -30 dBFS
        assertTrue("level $lv", lv in 45..55)
    }

    @Test
    fun configValidation() {
        for (block in listOf({ RecorderConfig(sampleRate = 100) }, { RecorderConfig(frameBytes = 641) }, { RecorderConfig(maxDurationMs = 0) })) {
            try {
                block()
                fail()
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
    }
}
