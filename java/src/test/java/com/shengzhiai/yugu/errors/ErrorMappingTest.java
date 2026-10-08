package com.shengzhiai.yugu.errors;

import com.fasterxml.jackson.databind.JsonNode;
import com.shengzhiai.yugu.model.EvalResult;
import com.shengzhiai.yugu.testing.Fixtures;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import javax.net.ssl.SSLHandshakeException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Acceptance 6.6: every error, warning and local code of spec/errors.json maps to the expected
 * category, retryable flag and exception class; coverage of the table is 100 %.
 */
class ErrorMappingTest {
    private static final JsonNode SPEC = Fixtures.json("errors.json");

    static Class<? extends YuguException> expectedClass(String category) {
        switch (category) {
            case "NETWORK":
                return NetworkException.class;
            case "TIMEOUT":
                return RequestTimeoutException.class;
            case "AUTH":
                return AuthException.class;
            case "PERMISSION":
                return PermissionException.class;
            case "INVALID_PARAM":
                return InvalidParameterException.class;
            case "NOT_FOUND":
                return NotFoundException.class;
            case "CONFLICT":
                return ConflictException.class;
            case "RATE_LIMIT":
                return RateLimitException.class;
            case "QUOTA":
                return QuotaExceededException.class;
            case "SERVER":
            case "UPSTREAM":
                return ServerException.class;
            case "AUDIO":
                return AudioQualityException.class;
            case "STATE":
                return IllegalSessionStateException.class;
            case "CANCELLED":
                return RequestCancelledException.class;
            case "PROTOCOL":
                return ProtocolViolationException.class;
            default:
                return YuguException.class;
        }
    }

    private static void assertEntry(JsonNode entry, YuguException e) {
        String cat = entry.get("category").asText();
        int code = entry.get("code").asInt();
        assertEquals(code, e.getCode(), "code kept");
        assertEquals(ErrorCategory.valueOf(cat), e.getCategory(), "category of " + code);
        assertEquals(entry.get("retryable").asBoolean(), e.isRetryable(), "retryable of " + code);
        assertEquals(entry.get("retryable").asBoolean(), YuguErrors.isRetryable(e), "isRetryable of " + code);
        assertSame(expectedClass(cat), e.getClass(), "class of " + code);
        assertEquals(entry.get("name").asText(), e.getCodeName(), "name of " + code);
    }

    @TestFactory
    List<DynamicTest> everyPlatformErrorCode() {
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode entry : SPEC.get("errors")) {
            int code = entry.get("code").asInt();
            tests.add(DynamicTest.dynamicTest("error " + code + " " + entry.get("name").asText(), () -> {
                // from the code alone
                assertEntry(entry, YuguErrors.fromCode(code));
                // from a real platform error body with the typical HTTP status
                int http = entry.get("http").asInt();
                String body = "{\"code\":" + code + ",\"message\":\"m" + code + "\",\"timestamp\":1791447692272}";
                YuguException e = YuguErrors.fromHttpResponse(http, body, -1, "key-1", 2);
                assertEntry(entry, e);
                assertEquals(http, e.getHttpStatus());
                assertEquals("m" + code, e.getRawMessage());
                assertEquals(body, e.getRawBody());
                assertEquals("key-1", e.getIdempotencyKey());
                assertEquals(2, e.getAttempts());
                assertEquals(CodeKind.ERROR, e.getCodeKind());
                // the generated table agrees with the JSON
                ErrorTable.Entry t = ErrorTable.ERRORS.get(code);
                assertNotNull(t, "generated table has " + code);
                assertEquals(entry.get("category").asText(), t.category);
                assertEquals(entry.get("retryable").asBoolean(), t.retryable);
                assertEquals(http, t.http);
                // the code wins over the HTTP status: same mapping with an unrelated status
                assertEntry(entry, YuguErrors.fromHttpResponse(500, body));
            }));
        }
        return tests;
    }

    @TestFactory
    List<DynamicTest> everyWarningCode() {
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode entry : SPEC.get("warnings")) {
            int code = entry.get("code").asInt();
            tests.add(DynamicTest.dynamicTest("warning " + code + " " + entry.get("name").asText(), () -> {
                AudioQualityException e = YuguErrors.fromWarning(code);
                assertEntry(entry, e);
                assertEquals(CodeKind.WARNING, e.getCodeKind());
                WarningCode w = WarningCode.fromCode(code);
                assertNotNull(w, "WarningCode enumerates " + code);
                assertEquals(entry.get("name").asText(), w.name());
                assertEquals(entry.get("retryable").asBoolean(), w.isRetryable());
                assertEquals(entry.get("message").asText(), w.getMessage());
                ErrorTable.Entry t = ErrorTable.WARNINGS.get(code);
                assertEquals(entry.get("category").asText(), t.category);
                if (!ErrorTable.ERRORS.containsKey(code)) {
                    // DESIGN 2.4: YuguErrors.fromCode(1001) builds an AudioQualityException
                    assertEntry(entry, YuguErrors.fromCode(code));
                }
            }));
        }
        return tests;
    }

    @TestFactory
    List<DynamicTest> everyLocalCode() {
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode entry : SPEC.get("local")) {
            int code = entry.get("code").asInt();
            tests.add(DynamicTest.dynamicTest("local " + code + " " + entry.get("name").asText(), () -> {
                assertEntry(entry, YuguErrors.fromCode(code));
                YuguException e = YuguErrors.local(code, null, null);
                assertEntry(entry, e);
                assertEquals(CodeKind.LOCAL, e.getCodeKind());
                assertEquals(0, e.getHttpStatus());
                assertEquals(entry.get("message").asText(), e.getRawMessage());
            }));
        }
        return tests;
    }

    @Test
    void localRetryRulesMatchDesign() {
        Set<Integer> retryable = new HashSet<>();
        for (JsonNode entry : SPEC.get("local")) {
            if (YuguErrors.fromCode(entry.get("code").asInt()).isRetryable()) {
                retryable.add(entry.get("code").asInt());
            }
        }
        assertEquals(Set.of(90001, 90002, 90007), retryable);
    }

    @Test
    void codesPresentAsErrorAndWarningPreferTheErrorTable() {
        // 1004 and 1005 are USER_DISABLED/USER_LOCKED errors and AUDIO_NOISY/AUDIO_INCOMPLETE warnings
        for (int code : new int[]{1004, 1005}) {
            assertTrue(ErrorTable.ERRORS.containsKey(code) && ErrorTable.WARNINGS.containsKey(code));
            assertSame(PermissionException.class, YuguErrors.fromCode(code).getClass());
            assertSame(AudioQualityException.class, YuguErrors.fromWarning(code).getClass());
        }
    }

    @Test
    void categoriesMatchSpec() {
        List<String> spec = new ArrayList<>();
        SPEC.get("categories").forEach(n -> spec.add(n.asText()));
        List<String> mine = new ArrayList<>();
        for (ErrorCategory c : ErrorCategory.values()) {
            mine.add(c.name());
        }
        assertEquals(spec, mine);
        for (ErrorCategory c : ErrorCategory.values()) {
            YuguException e = YuguErrors.create(ErrorDetails.builder().category(c).message("x").build());
            assertSame(YuguErrors.exceptionClassFor(c), e.getClass());
            assertSame(expectedClass(c.name()), e.getClass());
        }
        assertEquals(ErrorCategory.UNKNOWN, ErrorCategory.parse(null));
        assertEquals(ErrorCategory.UNKNOWN, ErrorCategory.parse("nope"));
        assertEquals(ErrorCategory.AUTH, ErrorCategory.parse(" auth "));
    }

    @Test
    void warningEnumMatchesTableExactly() {
        assertEquals(ErrorTable.WARNINGS.size(), WarningCode.values().length);
        for (WarningCode w : WarningCode.values()) {
            assertEquals(w.name(), ErrorTable.WARNINGS.get(w.getCode()).name);
        }
        assertNull(WarningCode.fromCode(4242));
    }

    @TestFactory
    List<DynamicTest> httpFallbackWithoutCode() {
        List<DynamicTest> tests = new ArrayList<>();
        Set<Integer> retryableHttp = new HashSet<>();
        SPEC.get("retryableHttp").forEach(n -> retryableHttp.add(n.asInt()));
        SPEC.get("httpFallback").fields().forEachRemaining(f -> {
            int status = Integer.parseInt(f.getKey());
            tests.add(DynamicTest.dynamicTest("HTTP " + status, () -> {
                YuguException e = YuguErrors.fromHttpResponse(status, null);
                assertEquals(ErrorCategory.valueOf(f.getValue().asText()), e.getCategory());
                assertEquals(retryableHttp.contains(status), e.isRetryable());
                assertSame(expectedClass(f.getValue().asText()), e.getClass());
                assertEquals(0, e.getCode());
                assertEquals(CodeKind.NONE, e.getCodeKind());
                assertEquals("HTTP " + status, e.getRawMessage());
                assertEquals(retryableHttp.contains(status), YuguErrors.isRetryableHttpStatus(status));
            }));
        });
        return tests;
    }

    @Test
    void unlistedStatusAndCodes() {
        YuguException teapot = YuguErrors.fromHttpResponse(418, "{}");
        assertEquals(ErrorCategory.UNKNOWN, teapot.getCategory());
        assertSame(YuguException.class, teapot.getClass());
        assertFalse(teapot.isRetryable());
        // unknown business code: category and retry decision fall back to the HTTP status (rule 3)
        YuguException unknown = YuguErrors.fromHttpResponse(503, "{\"code\":77777,\"message\":\"new\"}");
        assertEquals(77777, unknown.getCode());
        assertEquals(CodeKind.UNLISTED, unknown.getCodeKind());
        assertEquals(ErrorCategory.UPSTREAM, unknown.getCategory());
        assertTrue(unknown.isRetryable());
        assertNull(unknown.getCodeName());
        YuguException unknown400 = YuguErrors.fromHttpResponse(400, "{\"code\":77777}");
        assertFalse(unknown400.isRetryable());
        assertEquals(ErrorCategory.UNKNOWN, YuguErrors.categoryOf(77777));
        assertEquals(ErrorCategory.UNKNOWN, YuguErrors.fromCode(77777).getCategory());
    }

    @Test
    void fastApiAndEngineBodies() {
        YuguException d = YuguErrors.fromHttpResponse(422, "{\"detail\":\"refText missing\"}");
        assertSame(InvalidParameterException.class, d.getClass());
        assertEquals("refText missing", d.getRawMessage());
        YuguException arr = YuguErrors.fromHttpResponse(422,
                "{\"detail\":[{\"loc\":[\"body\",\"x\"],\"msg\":\"field required\",\"type\":\"value_error.missing\"},\"plain\"]}");
        assertSame(InvalidParameterException.class, arr.getClass());
        assertTrue(arr.getRawMessage().contains("field required") && arr.getRawMessage().contains("plain"), arr.getRawMessage());
        YuguException eng = YuguErrors.fromHttpResponse(500, "{\"detail\":\"[2001] missing appKey triplet\"}");
        assertSame(AuthException.class, eng.getClass());
        assertEquals(2001, eng.getCode());
        assertFalse(eng.isRetryable());
        YuguException eng2 = YuguErrors.fromHttpResponse(403, "{\"detail\":\"[2003] bad signature\"}");
        assertEquals(2003, eng2.getCode());
        YuguException engUnknown = YuguErrors.fromHttpResponse(502, "{\"detail\":\"[9] engine busy\"}");
        assertSame(ServerException.class, engUnknown.getClass());
        assertTrue(((ServerException) engUnknown).isUpstream());
        assertTrue(engUnknown.isRetryable());
        YuguException objDetail = YuguErrors.fromHttpResponse(400, "{\"detail\":{\"x\":1}}");
        assertEquals("{\"x\":1}", objDetail.getRawMessage());
        YuguException html = YuguErrors.fromHttpResponse(502, "<html><body>502 Bad Gateway</body></html>");
        assertSame(ServerException.class, html.getClass());
        assertTrue(html.isRetryable());
        assertEquals("HTTP 502", html.getRawMessage());
        YuguException stringCode = YuguErrors.fromHttpResponse(409, "{\"code\":\"40901\",\"msg\":\"busy\",\"data\":{\"recordId\":\"eval_1\"}}");
        assertSame(ConflictException.class, stringCode.getClass());
        assertTrue(stringCode.isRetryable());
        assertEquals("busy", stringCode.getRawMessage());
        assertEquals("eval_1", stringCode.getRecordId());
        YuguException errId = YuguErrors.fromHttpResponse(400, "{\"errId\":40001,\"error\":\"bad\",\"recordId\":\"r\"}");
        assertSame(InvalidParameterException.class, errId.getClass());
        assertEquals("r", errId.getRecordId());
        YuguException array = YuguErrors.fromHttpResponse(500, "[1,2]");
        assertSame(ServerException.class, array.getClass());
    }

    @Test
    void realPlatformErrorFixtures() {
        JsonNode sig = Fixtures.json("fixtures/platform/error_native_bad_signature.json");
        YuguException e = YuguErrors.fromHttpResponse(sig.get("status").asInt(), sig.get("body").asText());
        assertSame(AuthException.class, e.getClass());
        assertEquals(2003, e.getCode());
        assertEquals(401, e.getHttpStatus());
        assertFalse(e.isRetryable());
        JsonNode pin = Fixtures.json("fixtures/platform/error_compat_pinyin_missing_refpinyin.json");
        YuguException p = YuguErrors.fromHttpResponse(pin.get("status").asInt(), pin.get("body").asText());
        assertSame(InvalidParameterException.class, p.getClass());
        assertEquals(40001, p.getCode());
        assertTrue(p.getRawMessage().contains("refPinyin"));
    }

    @Test
    void wsErrorFrames() {
        YuguException up = YuguErrors.fromWsError(50200, "mock upstream error", "{\"event\":\"error\",\"code\":50200}", "k", 1);
        assertSame(ServerException.class, up.getClass());
        assertTrue(up.isRetryable());
        assertEquals("k", up.getIdempotencyKey());
        YuguException conflict = YuguErrors.fromWsError(40901, null, "{}", "k", 2);
        assertSame(ConflictException.class, conflict.getClass());
        assertTrue(conflict.isRetryable());
        assertEquals(ErrorTable.ERRORS.get(40901).message, conflict.getRawMessage());
        YuguException plain = YuguErrors.fromWsError(0, "unknown cmd", "{\"event\":\"error\",\"message\":\"unknown cmd\"}", "k", 1);
        assertSame(ServerException.class, plain.getClass());
        assertFalse(plain.isRetryable());
        YuguException auth = YuguErrors.fromWsError(0, "[2003] signature mismatch", "{}", null, 1);
        assertSame(AuthException.class, auth.getClass());
        assertEquals(2003, auth.getCode());
        YuguException empty = YuguErrors.fromWsError(0, "", "{}", null, 1);
        assertEquals("server error frame", empty.getRawMessage());
    }

    @Test
    void lowLevelFailures() {
        assertLocal(YuguErrors.fromThrowable(new HttpConnectTimeoutException("x")), 90002, RequestTimeoutException.class, true);
        assertLocal(YuguErrors.fromThrowable(new HttpTimeoutException("x")), 90002, RequestTimeoutException.class, true);
        assertLocal(YuguErrors.fromThrowable(new SocketTimeoutException("x")), 90002, RequestTimeoutException.class, true);
        assertLocal(YuguErrors.fromThrowable(new ConnectException("refused")), 90001, NetworkException.class, true);
        assertLocal(YuguErrors.fromThrowable(new IOException()), 90001, NetworkException.class, true);
        assertLocal(YuguErrors.fromThrowable(new SSLHandshakeException("bad cert")), 90011, NetworkException.class, false);
        assertLocal(YuguErrors.fromThrowable(new CancellationException()), 90003, RequestCancelledException.class, false);
        assertLocal(YuguErrors.fromThrowable(new InterruptedException()), 90003, RequestCancelledException.class, false);
        assertLocal(YuguErrors.fromThrowable(new IllegalArgumentException("bad uri")), 90010, InvalidParameterException.class, false);
        assertLocal(YuguErrors.fromThrowable(new CompletionException(new ConnectException("x"))), 90001, NetworkException.class, true);
        YuguException other = YuguErrors.fromThrowable(new IllegalStateException("odd"), "k", 3);
        assertSame(YuguException.class, other.getClass());
        assertEquals(ErrorCategory.UNKNOWN, other.getCategory());
        assertFalse(other.isRetryable());
        assertEquals(3, other.getAttempts());
        YuguException same = YuguErrors.fromCode(40001);
        assertSame(same, YuguErrors.fromThrowable(new CompletionException(same)));
        assertTrue(YuguErrors.isRetryable(new IOException("reset")));
        assertTrue(YuguErrors.isRetryable(new CompletionException(new HttpTimeoutException("t"))));
        assertFalse(YuguErrors.isRetryable(new IllegalStateException()));
        assertFalse(YuguErrors.isRetryable(null));
        assertThrows(IllegalArgumentException.class, () -> YuguErrors.local(40001, null, null));
    }

    private static void assertLocal(YuguException e, int code, Class<?> type, boolean retryable) {
        assertEquals(code, e.getCode());
        assertSame(type, e.getClass());
        assertEquals(retryable, e.isRetryable());
        assertEquals(CodeKind.LOCAL, e.getCodeKind());
    }

    @Test
    void contextFieldsAndMessages() {
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 6000; i++) {
            big.append('x');
        }
        YuguException e = YuguErrors.fromHttpResponse(429, big.toString(), 1500, "key-9", 3);
        assertEquals(4096, e.getRawBody().length());
        assertEquals(1500, e.getRetryAfterMs());
        assertSame(RateLimitException.class, e.getClass());
        assertTrue(e.getMessage().contains("category=RATE_LIMIT"), e.getMessage());
        assertTrue(e.getMessage().contains("httpStatus=429"), e.getMessage());
        assertTrue(e.getMessage().contains("attempts=3"), e.getMessage());
        assertTrue(e.getMessage().contains("idempotencyKey=key-9"), e.getMessage());
        YuguException copy = YuguErrors.create(e.toDetails());
        assertEquals(e.getCode(), copy.getCode());
        assertEquals(e.getRetryAfterMs(), copy.getRetryAfterMs());
        assertEquals(e.getRawBody(), copy.getRawBody());
        ErrorDetails d = e.toDetails();
        assertEquals(ErrorCategory.RATE_LIMIT, d.getCategory());
        assertEquals(429, d.getHttpStatus());
        assertEquals(CodeKind.NONE, d.getCodeKind());
        assertEquals("HTTP 429", d.getMessage());
        assertEquals(0, d.getCode());
        YuguException blank = YuguErrors.create(ErrorDetails.builder().category(ErrorCategory.STATE).build());
        assertTrue(blank.getMessage().startsWith("STATE"), blank.getMessage());
        assertNull(YuguErrors.create(ErrorDetails.builder().build()).getRawBody());
        assertEquals(SPEC.get("httpFallback").size(), YuguErrors.httpFallbackTable().size());
    }

    @Test
    void strictAudioExceptionCarriesResult() {
        EvalResult r = new EvalResult();
        r.setRecordId("eval_x");
        r.setIdempotencyKey("k");
        AudioQualityException e = YuguErrors.fromWarning(1001, null, r);
        assertSame(r, e.getResult());
        assertEquals("eval_x", e.getRecordId());
        assertEquals("k", e.getIdempotencyKey());
        assertFalse(e.isRetryable());
        assertNull(YuguErrors.fromWarning(1002).getResult());
        AudioQualityException unknown = YuguErrors.fromWarning(1077);
        assertEquals(CodeKind.UNLISTED, unknown.getCodeKind());
    }
}
