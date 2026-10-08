package com.shengzhiai.yugu

import com.shengzhiai.yugu.errors.ErrorCodes
import com.shengzhiai.yugu.errors.ErrorTable
import com.shengzhiai.yugu.internal.JObj
import com.shengzhiai.yugu.internal.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * Acceptance 6.6: every error code and warning code of spec/errors.json maps to the right
 * exception type, keeps the original code, and its retry decision matches the retry policy.
 */
class ErrorMappingTest {

    private val spec = JObj.parseOrNull(TestEnv.repoRoot.resolve("spec/errors.json").readText())!!
    private lateinit var server: MockWebServer
    private val wav = TestEnv.fixtureBytes("audio/zh_short.wav")
    private val config = EvaluateConfig(CoreType.SENTENCE, "今天天气很好", Language.ZH_CN)
    private val fastRetry = RetryPolicy(maxRetries = 2, initialDelayMs = 1, maxDelayMs = 1, jitter = 0.0, respectRetryAfter = false)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client(strictAudio: Boolean = false) = YuguClient.builder()
        .baseUrl(server.url("/").toString())
        .wsBaseUrl("ws://127.0.0.1:${server.port}")
        .auth(Auth.appKey("mock-app-key", "mock-secret-key"))
        .retryPolicy(fastRetry)
        .strictAudio(strictAudio)
        .logger(CapturingLogger())
        .callbackExecutor { it.run() }
        .build()

    private fun expectedClass(category: String): Class<out YuguException> = when (category) {
        "NETWORK" -> NetworkException::class.java
        "TIMEOUT" -> RequestTimeoutException::class.java
        "AUTH" -> AuthException::class.java
        "PERMISSION" -> PermissionException::class.java
        "INVALID_PARAM" -> InvalidParameterException::class.java
        "NOT_FOUND" -> NotFoundException::class.java
        "CONFLICT" -> ConflictException::class.java
        "RATE_LIMIT" -> RateLimitException::class.java
        "QUOTA" -> QuotaExceededException::class.java
        "SERVER", "UPSTREAM" -> ServerException::class.java
        "AUDIO" -> AudioQualityException::class.java
        "STATE" -> IllegalSessionStateException::class.java
        "CANCELLED" -> RequestCancelledException::class.java
        "PROTOCOL" -> ProtocolViolationException::class.java
        else -> YuguException::class.java
    }

    @Test
    fun everyPlatformErrorCodeThrownByEvaluateWithMatchingTypeAndRetry() {
        val errors = spec.objList("errors")
        assertEquals(ErrorTable.ERRORS.size, errors.size)
        client().use { c ->
            for (e in errors) {
                val code = e.int("code")!!
                val http = e.int("http")!!
                val retryable = e.bool("retryable")!!
                val message = "样例 " + e.str("message")
                val body = Json.write(mapOf("code" to code, "message" to message, "timestamp" to 1791447692255L))
                val attemptsExpected = if (retryable) 3 else 1
                repeat(attemptsExpected) { server.enqueue(MockResponse().setResponseCode(http).setBody(body)) }
                val before = server.requestCount
                try {
                    c.evaluate(config, wav)
                    fail("code $code did not throw")
                } catch (ex: YuguException) {
                    assertEquals("type of $code", expectedClass(e.str("category")!!), ex.javaClass)
                    assertEquals("category of $code", e.str("category"), ex.category.name)
                    assertEquals("code kept for $code", code, ex.code)
                    assertEquals("http kept for $code", http, ex.httpStatus)
                    assertEquals("retryable of $code", retryable, ex.retryable)
                    assertEquals("isRetryable of $code", retryable, YuguErrors.isRetryable(ex))
                    assertEquals("message of $code", message, ex.message)
                    assertEquals("attempts of $code", attemptsExpected, ex.attempts)
                    assertNotNull("idempotency key on $code", ex.idempotencyKey)
                    assertTrue("raw body kept for $code", ex.rawBody!!.contains("\"code\":$code"))
                }
                assertEquals("requests sent for $code", attemptsExpected, server.requestCount - before)
                val keys = (0 until attemptsExpected).map { server.takeRequest().getHeader("Idempotency-Key") }
                assertEquals("same key on every attempt for $code", 1, keys.toSet().size)
                // fromCode builds the same type without an HTTP response.
                val fc = YuguErrors.fromCode(code)
                assertEquals(expectedClass(e.str("category")!!), fc.javaClass)
                assertEquals(retryable, fc.retryable)
                assertEquals(e.str("message"), fc.message)
                assertEquals(0, fc.httpStatus)
            }
        }
    }

    @Test
    fun everyWarningCodeIsListedNotThrownAndBuildsAudioQualityException() {
        val warnings = spec.objList("warnings")
        assertEquals(ErrorTable.WARNINGS.size, warnings.size)
        assertEquals(warnings.map { it.int("code") }.toSet(), WarningCode.values().map { it.code }.toSet())
        client().use { c ->
            for (w in warnings) {
                val code = w.int("code")!!
                val result = JObj.parseOrNull(TestEnv.fixtureText("platform/native_evaluate_sentence_zh.json"))!!.map.toMutableMap()
                @Suppress("UNCHECKED_CAST")
                val inner = (result["result"] as Map<String, Any?>).toMutableMap()
                inner["warning"] = listOf(mapOf("code" to code, "message" to w.str("message")))
                result["result"] = inner
                server.enqueue(MockResponse().setBody(Json.write(result)))
                val r = c.evaluate(config, wav)
                assertEquals(listOf(code), r.warnings.map { it.code })
                assertEquals(w.str("message"), r.warnings[0].message)
                assertEquals(WarningCode.fromCode(code), r.warnings[0].warningCode)
                assertEquals(w.str("message"), WarningCode.fromCode(code)!!.defaultMessage)
                val ex = YuguErrors.fromWarning(code)
                assertEquals(AudioQualityException::class.java, ex.javaClass)
                assertEquals(code, ex.code)
                assertEquals(w.bool("retryable"), ex.retryable)
                assertEquals(w.bool("retryable"), YuguErrors.isRetryable(ex))
            }
        }
        // Codes only in the warning table build AudioQualityException through fromCode as well.
        assertEquals(AudioQualityException::class.java, YuguErrors.fromCode(1001).javaClass)
        // 1004 and 1005 exist in both tables: fromCode follows the error table, fromWarning the warning table.
        assertEquals(PermissionException::class.java, YuguErrors.fromCode(1004).javaClass)
        assertEquals(AudioQualityException::class.java, YuguErrors.fromWarning(1004).javaClass)
        assertEquals(WarningCode.AUDIO_NOISY, WarningCode.fromCode(1004))
        assertEquals(PermissionException::class.java, YuguErrors.fromCode(1005).javaClass)
        assertEquals(AudioQualityException::class.java, YuguErrors.fromWarning(1005).javaClass)
        // a warning-only code keeps the flag of the warning table through fromCode
        assertTrue(YuguErrors.fromCode(1009).retryable)
        assertEquals(ErrorCategory.PERMISSION, YuguErrors.categoryOf(1004))
        assertEquals(ErrorCategory.AUDIO, YuguErrors.categoryOf(1001))
        assertEquals(ErrorCategory.NETWORK, YuguErrors.categoryOf(90001))
        assertEquals(null, YuguErrors.categoryOf(77))
    }

    @Test
    fun errorBodiesUseTheErrorTableOnly() {
        // 1004 in an error body is the account error, never the audio warning
        val disabled = YuguErrors.fromHttp(400, """{"code":1004,"message":"用户已禁用"}""")
        assertEquals(PermissionException::class.java, disabled.javaClass)
        assertEquals(1004, disabled.code)
        // a warning-only code in an error body is an unknown business code: HTTP decides, the code is kept
        val w = YuguErrors.fromHttp(400, """{"code":1001,"message":"x"}""")
        assertEquals(InvalidParameterException::class.java, w.javaClass)
        assertEquals(1001, w.code)
        assertFalse(w.retryable)
        val w2 = YuguErrors.fromHttp(500, """{"code":1009,"message":"x"}""")
        assertEquals(ErrorCategory.SERVER, w2.category)
        assertTrue(w2.retryable)
        // error frames follow the same rule
        assertEquals(PermissionException::class.java, YuguErrors.fromErrorFrame("""{"event":"error","code":1005,"message":"locked"}""").javaClass)
        val frame = YuguErrors.fromErrorFrame("""{"event":"error","code":1009,"message":"degraded"}""")
        assertEquals(ErrorCategory.UNKNOWN, frame.category)
        assertFalse(frame.retryable)
        // local codes through fromCode keep the local table
        val local = YuguErrors.fromCode(90002, null, 0)
        assertEquals(RequestTimeoutException::class.java, local.javaClass)
        assertTrue(local.retryable)
        assertEquals(504, YuguErrors.fromCode(90002, null, 504).httpStatus)
        // an unknown code without HTTP status is UNKNOWN and not retryable
        val unknown = YuguErrors.fromCode(12345)
        assertEquals(ErrorCategory.UNKNOWN, unknown.category)
        assertFalse(unknown.retryable)
        assertEquals(ErrorCategory.UPSTREAM, YuguErrors.fromCode(12345, httpStatus = 503).category)
    }

    @Test
    fun warningsAcceptIntegerFormAndTopLevelList() {
        client().use { c ->
            server.enqueue(MockResponse().setBody("""{"recordId":"eval_1","eof":1,"result":{"overall":50,"warning":[1002]},"warnings":[1002,"1003",{"code":1009}]}"""))
            val r = c.evaluate(config, wav)
            assertEquals(listOf(1002, 1003, 1009), r.warnings.map { it.code })
            assertEquals("Audio volume too low!", r.warnings[0].message)
            assertTrue(r.hasWarning(WarningCode.SCORER_DEGRADED))
        }
    }

    @Test
    fun strictAudioThrowsOnlyForNoValidAudio() {
        client(strictAudio = true).use { c ->
            server.enqueue(MockResponse().setBody("""{"recordId":"eval_s","eof":1,"result":{"overall":0,"warning":[{"code":1001,"message":"No valid audio detected!"}]}}"""))
            try {
                c.evaluate(config, wav)
                fail("strictAudio did not throw")
            } catch (e: AudioQualityException) {
                assertEquals(1001, e.code)
                assertEquals("eval_s", e.recordId)
                assertFalse(e.retryable)
                assertEquals(1, e.attempts)
            }
            server.enqueue(MockResponse().setBody("""{"recordId":"eval_t","eof":1,"result":{"overall":40,"warning":[{"code":1002,"message":"low"}]}}"""))
            assertEquals(listOf(1002), c.evaluate(config, wav).warnings.map { it.code })
        }
    }

    @Test
    fun everyLocalCodeHasItsCategoryAndRetryFlag() {
        val local = spec.objList("local")
        assertEquals(ErrorTable.LOCAL.size, local.size)
        for (l in local) {
            val code = l.int("code")!!
            val e = YuguErrors.local(code)
            assertEquals(expectedClass(l.str("category")!!), e.javaClass)
            assertEquals(l.bool("retryable"), e.retryable)
            assertEquals(l.str("message"), e.message)
            assertEquals(0, e.httpStatus)
        }
        // DESIGN 2.3 rule 1: only 90001, 90002, 90007 are retryable.
        assertEquals(setOf(90001, 90002, 90007), local.filter { it.bool("retryable") == true }.map { it.int("code") }.toSet())
        assertFalse(YuguErrors.isRetryable(90999, 503))
    }

    @Test
    fun httpFallbackForEveryStatusWithoutBusinessCode() {
        val fallback = spec.obj("httpFallback")!!
        val retryableHttp = spec.list("retryableHttp")!!.map { (it as Number).toInt() }.toSet()
        for (status in fallback.map.keys) {
            val s = status.toInt()
            val e = YuguErrors.fromHttp(s, "<html><body>$s</body></html>")
            assertEquals("category of HTTP $s", fallback.str(status), e.category.name)
            assertEquals("retryable of HTTP $s", s in retryableHttp, e.retryable)
            assertEquals(0, e.code)
            assertEquals(s, e.httpStatus)
            assertEquals("HTTP $s", e.message)
        }
        // statuses missing from httpFallback map by class (DESIGN 2.4)
        for ((status, category, retryable) in listOf(
            Triple(418, ErrorCategory.INVALID_PARAM, false),
            Triple(451, ErrorCategory.INVALID_PARAM, false),
            Triple(505, ErrorCategory.SERVER, false),
            Triple(599, ErrorCategory.SERVER, false),
            Triple(302, ErrorCategory.UNKNOWN, false),
            Triple(600, ErrorCategory.UNKNOWN, false),
        )) {
            val e = YuguErrors.fromHttp(status, "")
            assertEquals("category of HTTP $status", category, e.category)
            assertEquals("retryable of HTTP $status", retryable, e.retryable)
        }
        assertEquals(InvalidParameterException::class.java, YuguErrors.fromHttp(418, "").javaClass)
        assertEquals(ServerException::class.java, YuguErrors.fromHttp(505, "").javaClass)
        assertEquals(YuguException::class.java, YuguErrors.fromHttp(302, "").javaClass)
        // Unknown business code: category and retry decision from the HTTP status, code kept.
        val unknownCode = YuguErrors.fromHttp(503, """{"code":59999,"message":"x"}""")
        assertEquals(59999, unknownCode.code)
        assertEquals(ErrorCategory.UPSTREAM, unknownCode.category)
        assertTrue(unknownCode.retryable)
    }

    @Test
    fun fastApiDetailShapes() {
        val auth = YuguErrors.fromHttp(400, """{"detail":"[2001] 缺少鉴权三元组"}""")
        assertEquals(AuthException::class.java, auth.javaClass)
        assertEquals(2001, auth.code)
        assertEquals("[2001] 缺少鉴权三元组", auth.message)
        assertEquals(AuthException::class.java, YuguErrors.fromHttp(401, """{"detail":"[2003] appKey 不存在"}""").javaClass)
        val validation = YuguErrors.fromHttp(422, """{"detail":[{"loc":["body","text"],"msg":"field required"},{"msg":"too long"}]}""")
        assertEquals(InvalidParameterException::class.java, validation.javaClass)
        assertTrue(validation.message!!.contains("field required"))
        assertTrue(validation.message!!.contains("too long"))
        val upstream = YuguErrors.fromHttp(502, """{"detail":"[9001] engine busy"}""")
        assertEquals(ErrorCategory.UPSTREAM, upstream.category)
        assertEquals(9001, upstream.code)
        assertTrue(upstream.retryable)
        val plain = YuguErrors.fromHttp(502, """{"detail":"bad gateway"}""")
        assertEquals(0, plain.code)
        assertEquals("bad gateway", plain.message)
    }

    @Test
    fun recordIdFromErrorBody() {
        val e = YuguErrors.fromHttp(500, """{"code":50000,"message":"boom","recordId":"eval_x"}""")
        assertEquals("eval_x", e.recordId)
    }

    @Test
    fun wsErrorFrames() {
        val retryable = YuguErrors.fromErrorFrame("""{"event":"error","code":50200,"message":"上游忙"}""")
        assertEquals(ServerException::class.java, retryable.javaClass)
        assertEquals(ErrorCategory.UPSTREAM, retryable.category)
        assertTrue(retryable.retryable)
        assertEquals("上游忙", retryable.message)
        val conflict = YuguErrors.fromErrorFrame("""{"event":"error","code":40901,"message":"in progress"}""")
        assertTrue(conflict is ConflictException && conflict.retryable)
        val noCode = YuguErrors.fromErrorFrame("""{"event":"error","message":"no audio"}""")
        assertEquals(ErrorCategory.UNKNOWN, noCode.category)
        assertFalse(noCode.retryable)
        assertEquals(0, noCode.code)
        val bad = YuguErrors.fromErrorFrame("""{"event":"error","code":40001}""")
        assertEquals(InvalidParameterException::class.java, bad.javaClass)
        assertEquals("请求参数校验失败", bad.message)
    }

    @Test
    fun ioExceptionsMapToLocalCodes() {
        val timeout = YuguErrors.fromIOException(SocketTimeoutException("read timed out"), cancelled = false)
        assertTrue(timeout is RequestTimeoutException && timeout.code == ErrorCodes.TIMEOUT && timeout.retryable)
        val callTimeout = YuguErrors.fromIOException(java.io.InterruptedIOException("timeout"), cancelled = false)
        assertEquals(ErrorCodes.TIMEOUT, callTimeout.code)
        val net = YuguErrors.fromIOException(ConnectException("refused"), cancelled = false)
        assertTrue(net is NetworkException && net.code == ErrorCodes.NETWORK_ERROR && net.retryable)
        val tls = YuguErrors.fromIOException(SSLPeerUnverifiedException("bad cert"), cancelled = false)
        assertTrue(tls is NetworkException && tls.code == ErrorCodes.TLS_ERROR && !tls.retryable)
        val tls2 = YuguErrors.fromIOException(SSLHandshakeException("x").apply { initCause(CertificateException("expired")) }, cancelled = false)
        assertEquals(ErrorCodes.TLS_ERROR, tls2.code)
        val cancelled = YuguErrors.fromIOException(IOException("Canceled"), cancelled = true)
        assertTrue(cancelled is RequestCancelledException && !cancelled.retryable)
        assertTrue(YuguErrors.isRetryable(SocketTimeoutException()))
        assertFalse(YuguErrors.isRetryable(IllegalStateException()))
        assertFalse(YuguErrors.isRetryable(null))
    }

    @Test
    fun generatedTableMatchesErrorsJsonExactly() {
        fun check(list: List<JObj>, table: Map<Int, ErrorTable.Entry>, http: Boolean) {
            assertEquals(list.size, table.size)
            for (e in list) {
                val t = table[e.int("code")]!!
                assertEquals(e.str("name"), t.name)
                assertEquals(e.str("category"), t.category)
                assertEquals(e.bool("retryable"), t.retryable)
                assertEquals(e.str("message"), t.message)
                if (http) assertEquals(e.int("http"), t.http)
            }
        }
        check(spec.objList("errors"), ErrorTable.ERRORS, true)
        check(spec.objList("warnings"), ErrorTable.WARNINGS, false)
        check(spec.objList("local"), ErrorTable.LOCAL, false)
        assertEquals(spec.list("retryableHttp")!!.map { (it as Number).toInt() }.toSet(), ErrorTable.RETRYABLE_HTTP)
        assertEquals(spec.list("categories")!!.map { it as String }, ErrorCategory.values().map { it.name })
        assertSame(ErrorCategory.UNKNOWN, ErrorCategory.of("nope"))
        assertEquals(null, YuguErrors.categoryOf(123456))
        assertEquals(ErrorCategory.QUOTA, YuguErrors.categoryOf(40902))
    }

    @Test
    fun rawBodyIsTruncatedTo4KbOnCharacterBoundary() {
        val body = "{\"code\":50000,\"message\":\"" + "中".repeat(3000) + "\"}"
        val e = YuguErrors.fromHttp(500, body)
        val bytes = e.rawBody!!.toByteArray(Charsets.UTF_8).size
        assertTrue("raw body $bytes bytes", bytes <= YuguException.RAW_BODY_LIMIT && bytes > 4000)
        assertEquals("😀😀", YuguException.truncateUtf8("😀😀😀", 8))
        assertEquals("ab", YuguException.truncateUtf8("ab", 4096))
        val s = e.toString()
        assertTrue(s.contains("ServerException") && s.contains("code=50000") && s.contains("http=500") && s.contains("retryable=true"))
    }
}
