package com.shengzhiai.yugu

import com.shengzhiai.yugu.internal.JObj
import com.shengzhiai.yugu.internal.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

/** Multipart assembly, part Content-Types, signing rules per call (DESIGN 5.2). */
class RequestBuildingTest {

    private lateinit var server: MockWebServer
    private val wav = TestEnv.fixtureBytes("audio/zh_short.wav")
    private val okBody by lazy { TestEnv.fixtureText("platform/native_evaluate_sentence_zh.json") }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client(auth: Auth = Auth.appKey("mock-app-key", "mock-secret-key")) = YuguClient.builder()
        .baseUrl(server.url("/").toString())
        .auth(auth)
        .logger(CapturingLogger())
        .callbackExecutor { it.run() }
        .build()

    /** Minimal multipart parser for assertions. */
    class Part(val headers: Map<String, String>, val body: ByteArray) {
        val disposition get() = headers["content-disposition"] ?: ""
        val contentType get() = headers["content-type"]
        val name get() = Regex("name=\"([^\"]*)\"").find(disposition)?.groupValues?.get(1)
        val filename get() = Regex("filename=\"([^\"]*)\"").find(disposition)?.groupValues?.get(1)
        val text get() = String(body, Charsets.UTF_8)
    }

    private fun parts(req: RecordedRequest): List<Part> {
        val ct = req.getHeader("Content-Type")!!
        assertTrue(ct, ct.startsWith("multipart/form-data; boundary="))
        val boundary = ("--" + ct.substringAfter("boundary=")).toByteArray()
        val body = req.body.readByteArray()
        val out = ArrayList<Part>()
        var pos = indexOf(body, boundary, 0)
        while (pos >= 0) {
            pos += boundary.size
            if (body[pos] == '-'.code.toByte() && body[pos + 1] == '-'.code.toByte()) break
            pos += 2 // CRLF
            val headerEnd = indexOf(body, "\r\n\r\n".toByteArray(), pos)
            val headers = String(body, pos, headerEnd - pos, Charsets.UTF_8).split("\r\n")
                .associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
            val next = indexOf(body, boundary, headerEnd + 4)
            out.add(Part(headers, body.copyOfRange(headerEnd + 4, next - 2)))
            pos = next
        }
        return out
    }

    private fun indexOf(hay: ByteArray, needle: ByteArray, from: Int): Int {
        outer@ for (i in from..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    @Test
    fun evaluateMultipartAndConfigSignature() {
        server.enqueue(MockResponse().setBody(okBody))
        val config = EvaluateConfig(
            coreType = CoreType.SENTENCE, referenceText = "今天天气很好", language = Language.ZH_CN,
            includeReport = true, includeStandardAudio = false, includeAsrText = true, slack = 0.2, scale = 100,
            precision = 1.0, agegroup = 3, toneWeight = 0.25, refPinyin = "jin1 tian1", phonemeOutput = true,
            taskType = null, paragraphNeedWordScore = 1, extra = mapOf("future" to "x", "coreType" to "ignored"),
        )
        client().use { it.evaluate(config, wav) }
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/v1/evaluate", req.path)
        assertEquals(YuguVersion.USER_AGENT, req.getHeader("User-Agent"))
        assertEquals("yugu-android-sdk/2.0.0", req.getHeader("User-Agent"))
        assertEquals("mock-app-key", req.getHeader("X-App-Key"))
        val ts = req.getHeader("X-Timestamp")!!.toLong()
        assertTrue(Math.abs(System.currentTimeMillis() / 1000 - ts) < 5)
        assertTrue(req.getHeader("X-Nonce")!!.matches(Regex("[0-9a-f]{32}")))
        val ps = parts(req)
        assertEquals(listOf("audio", "config"), ps.map { it.name })
        val audio = ps[0]
        assertEquals("audio.wav", audio.filename)
        assertEquals("audio/wav", audio.contentType)
        assertArrayEquals(wav, audio.body)
        val cfg = ps[1]
        assertNull("config part must not carry a filename", cfg.filename)
        assertEquals("form-data; name=\"config\"", cfg.disposition)
        assertEquals("application/json; charset=utf-8", cfg.contentType)
        assertEquals(
            "{\"coreType\":\"sentence\",\"referenceText\":\"今天天气很好\",\"language\":\"zh-CN\",\"includeReport\":true," +
                "\"includeStandardAudio\":false,\"includeAsrText\":true,\"slack\":0.2,\"scale\":100,\"precision\":1,\"agegroup\":3," +
                "\"toneWeight\":0.25,\"refPinyin\":\"jin1 tian1\",\"phonemeOutput\":true,\"paragraphNeedWordScore\":1,\"future\":\"x\"}",
            cfg.text,
        )
        assertEquals(config.toJson(), cfg.text)
        // signed set is exactly {config: exact part text}
        assertEquals(Signer.sign(mapOf("config" to cfg.text), "mock-secret-key"), req.getHeader("X-Signature"))
    }

    @Test
    fun imagePartForPictureTasks() {
        server.enqueue(MockResponse().setBody(okBody))
        val jpg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3)
        client().use {
            it.evaluate(
                EvaluateConfig(CoreType.OPEN, "描述图片", taskType = TaskType.PICTURE),
                AudioInput.fromBytes(wav, "a.wav"),
                RequestOptions.DEFAULT,
                ImageInput(jpg),
            )
        }
        val ps = parts(server.takeRequest())
        assertEquals(listOf("audio", "image", "config"), ps.map { it.name })
        assertEquals("image.jpg", ps[1].filename)
        assertEquals("image/jpeg", ps[1].contentType)
        assertArrayEquals(jpg, ps[1].body)
    }

    @Test
    fun tokenAuthUsesBearerHeaderOnly() {
        server.enqueue(MockResponse().setBody(okBody))
        client(Auth.token("jwt-abc")).use { it.evaluate(EvaluateConfig(CoreType.WORD, "apple"), wav) }
        val req = server.takeRequest()
        assertEquals("Bearer jwt-abc", req.getHeader("Authorization"))
        assertNull(req.getHeader("X-Signature"))
        assertNull(req.getHeader("X-App-Key"))
    }

    @Test
    fun compatFormFieldsAreSignedAudioIsNot() {
        server.enqueue(MockResponse().setBody(TestEnv.fixtureText("platform/compat_sent.eval.cn.json")))
        val cfg = CompatConfig(
            CompatCoreType.SENT_EVAL_CN, "今天天气很好", Language.ZH_CN, refPinyin = "",
            params = mapOf("paragraph_need_word_score" to "1", "agegroup" to "2", "empty" to ""),
        )
        val r = client().use { it.evaluateCompat(cfg, wav) }
        assertEquals("sent.eval.cn", r.coreType)
        val req = server.takeRequest()
        assertEquals("/sent.eval.cn", req.path)
        assertTrue(req.getHeader("Idempotency-Key")!!.matches(Regex("[0-9a-f]{32}")))
        val ps = parts(req)
        assertEquals(listOf("audio", "refText", "language", "paragraph_need_word_score", "agegroup"), ps.map { it.name })
        val fields = ps.drop(1).associate { it.name!! to it.text }
        assertNull(ps[1].filename)
        assertEquals(
            Signer.sign(fields, "mock-secret-key"),
            req.getHeader("X-Signature"),
        )
        assertEquals("agegroup=2&language=zh-CN&paragraph_need_word_score=1&refText=今天天气很好", Signer.buildPayload(fields))
    }

    @Test
    fun ttsBodyAndSignatureFollowJsonText() {
        server.enqueue(MockResponse().setBody(TestEnv.fixtureText("platform/tts_generate.json")))
        val r = client().use { it.tts(TtsRequest("你好世界", speed = 60)) }
        assertEquals("https://ygyx.dragonai.tech/tts-local/0c02b59749694e518fa8c6c6a78c2377.mp3", r.audioUrl)
        assertEquals(r.audioUrl, r.resolvedUrl)
        assertEquals("1.348", r.duration)
        assertEquals(1.348, r.durationSeconds!!, 1e-9)
        assertEquals("mp3", r.format)
        assertFalse(r.replayed)
        val req = server.takeRequest()
        assertEquals("/api/v1/tts/generate", req.path)
        assertEquals("application/json; charset=utf-8", req.getHeader("Content-Type"))
        val body = req.body.readUtf8()
        assertEquals("{\"text\":\"你好世界\",\"language\":\"zh-CN\",\"voice\":\"xiaoyan\",\"format\":\"mp3\",\"speed\":60,\"pitch\":50,\"volume\":50}", body)
        val signed = mapOf("text" to "你好世界", "language" to "zh-CN", "voice" to "xiaoyan", "format" to "mp3", "speed" to "60", "pitch" to "50", "volume" to "50")
        assertEquals(Signer.sign(signed, "mock-secret-key"), req.getHeader("X-Signature"))
        assertTrue(req.getHeader("Idempotency-Key")!!.isNotEmpty())
    }

    @Test
    fun ttsVectorMatchesSharedFixture() {
        // the tts_json_body vector: style null is not signed
        val v = JObj.parseOrNull(TestEnv.fixtureText("sign/vectors.json"))!!.objList("cases").first { it.str("name") == "tts_json_body" }
        val body = TtsRequest("你好世界", "zh-CN", "xiaoyan", "mp3", 50, 50, 50, null).body()
        val signed = body.mapValues { (_, value) -> if (value is Number) Json.formatNumber(value) else value.toString() }
        assertEquals(v.str("payload"), Signer.buildPayload(signed))
        assertEquals(v.str("signature"), Signer.sign(signed, v.str("secret")!!))
    }

    @Test
    fun ttsRelativeUrlsResolveAgainstBase() {
        server.enqueue(MockResponse().setBody("""{"code":0,"data":{"audioUrl":"/audio/x.mp3","duration":"2.5","format":"mp3"}}"""))
        server.enqueue(MockResponse().setBody("""{"code":0,"data":{"audioUrl":"/other/y.mp3"}}"""))
        server.enqueue(MockResponse().setBody("""{"code":0,"data":{"audioUrl":"z.mp3"}}"""))
        server.enqueue(MockResponse().setBody("""{"code":0,"data":{}}"""))
        client().use { c ->
            val base = server.url("/").toString().trimEnd('/')
            assertEquals("$base/tts/audio/x.mp3", c.tts(TtsRequest("a")).resolvedUrl)
            assertEquals("$base/other/y.mp3", c.tts(TtsRequest("b")).resolvedUrl)
            assertEquals("$base/z.mp3", c.tts(TtsRequest("c")).resolvedUrl)
            assertEquals("", c.tts(TtsRequest("d")).resolvedUrl)
        }
    }

    @Test
    fun envelopeErrorCodeInSuccessfulHttpIsAnError() {
        server.enqueue(MockResponse().setBody("""{"code":42902,"message":"试用层 AI 报告达到每日上限"}"""))
        client().use { c ->
            try {
                c.getReport("eval_1")
                fail()
            } catch (e: QuotaExceededException) {
                assertEquals(42902, e.code)
                assertEquals(200, e.httpStatus)
            }
        }
    }

    @Test
    fun nonJsonSuccessIsAProtocolError() {
        server.enqueue(MockResponse().setBody("<html>ok</html>"))
        server.enqueue(MockResponse().setBody("""{"code":40001,"message":"bad"}"""))
        client().use { c ->
            try {
                c.evaluate(EvaluateConfig(CoreType.WORD, "apple"), wav)
                fail()
            } catch (e: ProtocolViolationException) {
                assertEquals(90005, e.code)
                assertFalse(e.retryable)
            }
            try {
                c.evaluate(EvaluateConfig(CoreType.WORD, "apple"), wav)
                fail()
            } catch (e: InvalidParameterException) {
                assertEquals(40001, e.code)
            }
        }
    }

    @Test
    fun audioInputsFromFileStreamAndPcm() {
        repeat(4) { server.enqueue(MockResponse().setBody(okBody)) }
        val pcm = TestEnv.fixturePcm("audio/zh_short.wav")
        val tmp = File.createTempFile("yugu", ".wav").apply { writeBytes(wav) }
        try {
            client().use { c ->
                val cfg = EvaluateConfig(CoreType.SENTENCE, "今天天气很好")
                c.evaluate(cfg, tmp)
                c.evaluate(cfg, ByteArrayInputStream(wav))
                c.evaluate(cfg, AudioInput.fromPcm(pcm))
                c.evaluate(cfg, AudioInput.fromBytes(wav, "clip.mp3", "audio/mpeg"))
            }
        } finally {
            tmp.delete()
        }
        val fromFile = parts(server.takeRequest())[0]
        assertEquals(tmp.name, fromFile.filename)
        assertArrayEquals(wav, fromFile.body)
        assertArrayEquals(wav, parts(server.takeRequest())[0].body)
        val fromPcm = parts(server.takeRequest())[0]
        assertEquals("audio/wav", fromPcm.contentType)
        assertEquals(44 + pcm.size, fromPcm.body.size)
        assertArrayEquals(pcm, fromPcm.body.copyOfRange(44, fromPcm.body.size))
        val declared = parts(server.takeRequest())[0]
        assertEquals("clip.mp3", declared.filename)
        assertEquals("audio/mpeg", declared.contentType)
    }

    @Test
    fun contentTypeInference() {
        assertEquals("audio/mpeg", AudioInput.contentTypeForName("a.MP3"))
        assertEquals("audio/mp4", AudioInput.contentTypeForName("a.m4a"))
        assertEquals("audio/ogg", AudioInput.contentTypeForName("a.opus"))
        assertEquals("audio/amr", AudioInput.contentTypeForName("a.amr"))
        assertEquals("audio/flac", AudioInput.contentTypeForName("a.flac"))
        assertEquals("audio/wav", AudioInput.contentTypeForName("a.wav"))
        assertEquals("application/octet-stream", AudioInput.contentTypeForName("noext"))
        assertEquals("audio/wav", AudioInput.fromBytes(wav, "no-extension").contentType())
        assertEquals("audio/mpeg", AudioInput.fromBytes(byteArrayOf(1, 2, 3), "x.mp3").contentType())
    }

    @Test
    fun invalidInputsFailBeforeIo() {
        client().use { c ->
            val cases = listOf<() -> Unit>(
                { c.evaluate(EvaluateConfig("sentense", "x"), wav) },
                { c.evaluate(EvaluateConfig(CoreType.SENTENCE, "  "), wav) },
                { c.evaluate(EvaluateConfig(CoreType.SENTENCE, "x".repeat(1001)), wav) },
                { c.evaluate(EvaluateConfig(CoreType.SENTENCE, "x", slack = 2.0), wav) },
                { c.evaluate(EvaluateConfig(CoreType.SENTENCE, "x", scale = 0), wav) },
                { c.evaluate(EvaluateConfig(CoreType.SENTENCE, "x", precision = 0.0), wav) },
                { c.evaluate(EvaluateConfig(CoreType.SENTENCE, "x", toneWeight = Double.NaN), wav) },
                { c.evaluate(EvaluateConfig(CoreType.SENTENCE, "x"), ByteArray(0)) },
                { c.evaluate(EvaluateConfig(CoreType.SENTENCE, "x"), File("/nonexistent/a.wav")) },
                { c.evaluate(EvaluateConfig(CoreType.SENTENCE, "x"), ByteArrayInputStream(ByteArray(0))) },
                { c.evaluateCompat(CompatConfig("sent.evaluate", "x"), wav) },
                { c.evaluateCompat(CompatConfig(CompatCoreType.SENT_EVAL, ""), wav) },
                { c.evaluateCompat(CompatConfig(CompatCoreType.PINYIN, "重庆"), wav) },
                { c.tts(TtsRequest("")) },
                { c.tts(TtsRequest("x", speed = 101)) },
                { c.getReport(" ") },
                { AudioInput.fromPcm(ByteArray(0)) },
                { AudioInput.fromPcm(ByteArray(2), sampleRate = 0) },
                { ImageInput.fromFile(File("/nonexistent.jpg")) },
            )
            for ((i, block) in cases.withIndex()) {
                try {
                    block()
                    fail("case $i accepted")
                } catch (e: InvalidParameterException) {
                    assertEquals("case $i", 90010, e.code)
                }
            }
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun builderValidatesUrlsAndAuth() {
        val cases = listOf<() -> Unit>(
            { YuguClient.builder().build() },
            { YuguClient.builder().auth(Auth.token("t")).baseUrl("ftp://x").build() },
            { YuguClient.builder().auth(Auth.token("t")).wsBaseUrl("https://x").build() },
            { YuguClient.builder().auth(Auth.token("t")).connectTimeoutMs(0) },
        )
        for ((i, block) in cases.withIndex()) {
            try {
                block()
                fail("case $i accepted")
            } catch (e: InvalidParameterException) {
                assertEquals(90010, e.code)
            }
        }
        for (block in listOf({ Auth.token(" ") }, { Auth.appKey("", "s") }, { Auth.appKey("a", "") })) {
            try {
                block()
                fail()
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
        assertEquals("Auth.Signature(appKey=mock***)", Auth.appKey("mock-app-key", "secret").toString())
        assertEquals("Auth.Token(***)", Auth.token("abc").toString())
    }

    @Test
    fun javaStyleBuildersProduceSameConfig() {
        val built = EvaluateConfig.builder(CoreType.PASSAGE, "text")
            .language(Language.EN_US).includeReport(true).includeStandardAudio(true).includeAsrText(false)
            .slack(0.1).scale(10).precision(0.5).agegroup(2).toneWeight(0.3).refPinyin("p")
            .phonemeOutput(true).taskType(TaskType.FREE).paragraphNeedWordScore(1).extra("k", 1)
            .build()
        val direct = EvaluateConfig(
            CoreType.PASSAGE, "text", Language.EN_US, true, true, false, 0.1, 10, 0.5, 2, 0.3, "p", true,
            TaskType.FREE, 1, mapOf("k" to 1),
        )
        assertEquals(direct, built)
        assertEquals(direct.toJson(), built.toJson())
    }
}
