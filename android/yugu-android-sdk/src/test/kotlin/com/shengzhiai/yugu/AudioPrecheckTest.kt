package com.shengzhiai.yugu

import com.shengzhiai.yugu.audio.AudioPrecheck
import com.shengzhiai.yugu.audio.PcmStats
import com.shengzhiai.yugu.audio.WavFormat
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** DESIGN 2.9 audio precheck and WAV handling. */
class AudioPrecheckTest {

    private lateinit var server: MockWebServer
    private val ok by lazy { TestEnv.fixtureText("platform/native_evaluate_sentence_zh.json") }
    private val config = EvaluateConfig(CoreType.SENTENCE, "今天天气很好", Language.ZH_CN)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client(mode: AudioPrecheckMode) = YuguClient.builder()
        .baseUrl(server.url("/").toString())
        .auth(Auth.appKey("mock-app-key", "mock-secret-key"))
        .audioPrecheck(mode)
        .logger(CapturingLogger())
        .callbackExecutor { it.run() }
        .build()

    private fun codes(bytes: ByteArray) = AudioPrecheck.checkUpload(bytes, false, 0, 0).map { it.code }

    /** WAV with arbitrary format fields. */
    private fun wav(pcm: ByteArray, rate: Int = 16000, channels: Int = 1, bits: Int = 16, tag: Int = 1): ByteArray {
        val b = ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(tag.toShort()).putShort(channels.toShort()).putInt(rate)
            .putInt(rate * channels * bits / 8).putShort((channels * bits / 8).toShort()).putShort(bits.toShort())
        b.put("data".toByteArray()).putInt(pcm.size).put(pcm)
        return b.array()
    }

    private fun tone(seconds: Double, amplitude: Int, rate: Int = 16000): ByteArray {
        val n = (seconds * rate).toInt()
        val b = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) b.putShort((amplitude * Math.sin(2 * Math.PI * 440 * i / rate)).toInt().toShort())
        return b.array()
    }

    @Test
    fun fixtureFilesClassifyAsExpected() {
        assertEquals(emptyList<Int>(), codes(TestEnv.fixtureBytes("audio/zh_short.wav")))
        assertEquals(emptyList<Int>(), codes(TestEnv.fixtureBytes("audio/en_apple.wav")))
        assertEquals(listOf(90104), codes(TestEnv.fixtureBytes("audio/low_volume.wav")))
        assertEquals(listOf(90103), codes(TestEnv.fixtureBytes("audio/silent.wav")))
        for (f in listOf("en_abc.wav", "en_fox.wav", "zh_para.wav", "g9_zh_selfintro.wav")) {
            assertEquals(f, emptyList<Int>(), codes(TestEnv.fixtureBytes("audio/$f")))
        }
    }

    @Test
    fun thresholds() {
        assertEquals(listOf(90101), codes(wav(tone(0.5, 8000))))
        assertEquals(emptyList<Int>(), codes(wav(tone(1.0, 8000))))
        assertEquals(listOf(90105), codes(wav(tone(1.5, 8000, 8000), rate = 8000)))
        assertEquals(listOf(90105), codes(wav(ByteArray(48000), bits = 8)))
        assertEquals(listOf(90105), codes(wav(ByteArray(48000), tag = 3, bits = 32)))
        assertEquals(listOf(90105), codes("RIFF\u0000\u0000\u0000\u0000WAVEjunk".toByteArray()))
        // silent: peak below 200 and RMS below 30
        assertEquals(listOf(90103), codes(wav(tone(2.0, 40))))
        // peak below 200 but RMS above 30 is not silent, its level is still below -45 dBFS
        assertEquals(listOf(90104), codes(wav(tone(2.0, 150))))
        // peak above 200 but RMS below -45 dBFS (about 184): low volume
        assertEquals(listOf(90104), codes(wav(tone(2.0, 250))))
        assertEquals(emptyList<Int>(), codes(wav(tone(2.0, 400))))
        // longer than 300 s
        val long = AudioPrecheck.checkPcm(PcmStats().apply { add(tone(0.1, 8000)) }, 301L * 32000, 16000, 1)
        assertEquals(listOf(90102), long.map { it.code })
        // REST uploads: bigger than 50 MB, any container. 10 MB is fine for an upload.
        assertEquals(listOf(90102), codes(ByteArray(50 * 1024 * 1024 + 1)))
        assertEquals(emptyList<Int>(), codes(ByteArray(10 * 1024 * 1024 + 1)))
        // one streaming round: more than 10 MB
        val stream = AudioPrecheck.checkPcm(PcmStats().apply { add(tone(0.1, 8000)) }, 10L * 1024 * 1024 + 2, 16000, 1)
        assertEquals(listOf(90102), stream.map { it.code })
        assertTrue(stream[0].message.contains("10 MB"))
        // other containers get only the size check
        assertEquals(emptyList<Int>(), codes(byteArrayOf(0x49, 0x44, 0x33, 1, 2, 3)))
        assertTrue(AudioPrecheck.rejectable(90101))
        assertTrue(!AudioPrecheck.rejectable(90104))
    }

    @Test
    fun rawPcmAndOddChunks() {
        val pcm = tone(1.2, 8000)
        assertEquals(emptyList<Int>(), AudioPrecheck.checkUpload(pcm, true, 16000, 1).map { it.code })
        assertEquals(listOf(90105), AudioPrecheck.checkUpload(pcm, true, 8000, 1).map { it.code })
        val whole = PcmStats().apply { add(pcm) }
        val split = PcmStats()
        var i = 0
        while (i < pcm.size) {
            val n = minOf(333, pcm.size - i)
            split.add(pcm, i, n)
            i += n
        }
        assertEquals(whole.samples, split.samples)
        assertEquals(whole.peak, split.peak)
        assertEquals(whole.rms, split.rms, 1e-6)
        assertEquals(Double.NEGATIVE_INFINITY, PcmStats().dbfs, 0.0)
    }

    @Test
    fun warnModeUploadsAndReportsLocalWarnings() {
        server.enqueue(MockResponse().setBody(ok))
        client(AudioPrecheckMode.WARN).use { c ->
            val r = c.evaluate(config, TestEnv.fixtureBytes("audio/low_volume.wav"))
            assertEquals(listOf(90104), r.localWarnings.map { it.code })
            assertTrue(r.localWarnings[0].message.startsWith("音量过低"))
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun rejectModeThrowsBeforeUpload() {
        client(AudioPrecheckMode.REJECT).use { c ->
            for ((file, code) in listOf("audio/silent.wav" to 90103)) {
                try {
                    c.evaluate(config, TestEnv.fixtureBytes(file))
                    fail("$file accepted")
                } catch (e: AudioQualityException) {
                    assertEquals(code, e.code)
                    assertEquals(0, e.attempts)
                }
            }
            try {
                c.evaluate(config, AudioInput.fromPcm(tone(0.3, 8000)))
                fail()
            } catch (e: AudioQualityException) {
                assertEquals(90101, e.code)
            }
            try {
                c.evaluateCompat(CompatConfig(CompatCoreType.SENT_EVAL_CN, "今天"), wav(tone(2.0, 8000, 8000), rate = 8000))
                fail()
            } catch (e: AudioQualityException) {
                assertEquals(90105, e.code)
            }
            // low volume is only a warning even in REJECT mode
            server.enqueue(MockResponse().setBody(ok))
            assertEquals(listOf(90104), c.evaluate(config, TestEnv.fixtureBytes("audio/low_volume.wav")).localWarnings.map { it.code })
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun offModeSkipsEverything() {
        server.enqueue(MockResponse().setBody(ok))
        client(AudioPrecheckMode.OFF).use { c ->
            assertTrue(c.evaluate(config, TestEnv.fixtureBytes("audio/silent.wav")).localWarnings.isEmpty())
        }
    }

    @Test
    fun wavParsing() {
        val fixture = TestEnv.fixtureBytes("audio/zh_short.wav")
        val info = WavFormat.parse(fixture)!!
        assertEquals(16000, info.sampleRate)
        assertEquals(1, info.channels)
        assertEquals(16, info.bitsPerSample)
        assertEquals(78, info.dataOffset) // a LIST chunk precedes data
        assertEquals(61440, info.dataLength)
        assertEquals(1920, info.durationMs)
        assertTrue(info.isPcm16)
        val pcm = TestEnv.fixturePcm("audio/zh_short.wav")
        val rebuilt = WavFormat.fromPcm(pcm)
        assertEquals(44 + pcm.size, rebuilt.size)
        assertArrayEquals(pcm, WavFormat.pcmData(rebuilt))
        val reparsed = WavFormat.parse(rebuilt)!!
        assertEquals(44, reparsed.dataOffset)
        assertNull(WavFormat.parse(byteArrayOf(1, 2, 3)))
        assertNull(WavFormat.pcmData(byteArrayOf(1, 2, 3)))
        // streaming writers leave the data size at zero
        val streaming = rebuilt.copyOf()
        streaming[40] = 0; streaming[41] = 0; streaming[42] = 0; streaming[43] = 0
        assertEquals(pcm.size, WavFormat.parse(streaming)!!.dataLength)
        // data before fmt is malformed
        val noFmt = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN).put("RIFF".toByteArray()).putInt(12)
            .put("WAVE".toByteArray()).put("data".toByteArray()).putInt(0).array()
        assertNull(WavFormat.parse(noFmt))
        // WAVE_FORMAT_EXTENSIBLE with PCM sub format counts as PCM
        val ext = ByteBuffer.allocate(68 + 64).order(ByteOrder.LITTLE_ENDIAN)
        ext.put("RIFF".toByteArray()).putInt(60 + 64).put("WAVE".toByteArray())
        ext.put("fmt ".toByteArray()).putInt(40).putShort(0xFFFE.toShort()).putShort(1).putInt(16000).putInt(32000)
            .putShort(2).putShort(16).putShort(22).putShort(16).putInt(4).putShort(1).put(ByteArray(14))
        ext.put("data".toByteArray()).putInt(64).put(ByteArray(64))
        val extInfo = WavFormat.parse(ext.array())!!
        assertTrue(extInfo.isPcm16)
    }
}
