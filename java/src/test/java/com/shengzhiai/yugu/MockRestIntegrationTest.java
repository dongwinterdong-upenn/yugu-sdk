package com.shengzhiai.yugu;

import com.fasterxml.jackson.databind.JsonNode;
import com.shengzhiai.yugu.errors.AuthException;
import com.shengzhiai.yugu.errors.InvalidParameterException;
import com.shengzhiai.yugu.errors.RateLimitException;
import com.shengzhiai.yugu.errors.RequestTimeoutException;
import com.shengzhiai.yugu.errors.ServerException;
import com.shengzhiai.yugu.errors.YuguException;
import com.shengzhiai.yugu.model.CompatConfig;
import com.shengzhiai.yugu.model.EvalResult;
import com.shengzhiai.yugu.model.EvaluateConfig;
import com.shengzhiai.yugu.model.ReportResult;
import com.shengzhiai.yugu.model.TtsRequest;
import com.shengzhiai.yugu.model.TtsResult;
import com.shengzhiai.yugu.testing.CapturingLogger;
import com.shengzhiai.yugu.testing.Fixtures;
import com.shengzhiai.yugu.testing.MockServer;
import com.shengzhiai.yugu.testing.MockServerExtension;
import com.shengzhiai.yugu.testing.RecordingEvents;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REST integration against the Node mock platform (tools/mock-server): acceptance V-02 (idempotency
 * and billing) and V-03 (retry strategy), plus every REST call with real signature verification.
 */
@ExtendWith(MockServerExtension.class)
class MockRestIntegrationTest {
    static final byte[] WAV = Fixtures.wav("zh_short.wav");

    MockServer mock;
    RecordingEvents events;
    CapturingLogger logs;

    @BeforeEach
    void reset() {
        mock = MockServer.get();
        mock.reset();
        events = new RecordingEvents();
        logs = new CapturingLogger();
    }

    YuguClient client(Consumer<YuguClient.Builder> cfg) {
        YuguClient.Builder b = YuguClient.builder().apiKey(MockServer.APP_KEY, MockServer.SECRET).baseUrl(mock.baseUrl())
                .eventListener(events).logger(logs);
        cfg.accept(b);
        return b.build();
    }

    static EvaluateConfig sentence() {
        return new EvaluateConfig(EvaluateConfig.CORE_SENTENCE, "今天天气很好", "zh-CN");
    }

    static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().replace("-", "");
    }

    List<JsonNode> logEntries(String path) {
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode e : mock.log()) {
            if (path.equals(e.get("path").asText())) {
                out.add(e);
            }
        }
        return out;
    }

    /** Waits until the mock answered every request, including attempts the client already gave up on. */
    static void settle(long ms) throws InterruptedException {
        Thread.sleep(ms);
    }

    // ------------------------------------------------------------------ V-02

    @Test
    void v02_sameKeyThreeTimesWithAReadTimeoutBillsOnce() throws Exception {
        String key = key("v02");
        mock.faults("/api/v1/evaluate", "delay:1500");
        List<EvalResult> results = new ArrayList<>();
        try (YuguClient c = client(b -> b.readTimeoutMs(500))) {
            for (int i = 0; i < 3; i++) {
                results.add(c.evaluate(WAV, sentence(), RequestOptions.withIdempotencyKey(key)));
            }
        }
        settle(1500);
        // submission 1: the first attempt hit the injected read timeout, the retry succeeded with the same key
        assertEquals(2, results.get(0).getAttempts());
        assertSame(RequestTimeoutException.class, events.retryErrors.get(0).getClass());
        assertFalse(results.get(0).isReplayed());
        // submissions 2 and 3 are answered from the idempotency store
        assertTrue(results.get(1).isReplayed());
        assertTrue(results.get(2).isReplayed());
        assertEquals(results.get(0).getRecordId(), results.get(1).getRecordId());
        assertEquals(results.get(0).getRecordId(), results.get(2).getRecordId());
        List<JsonNode> log = logEntries("/api/v1/evaluate");
        assertEquals(4, log.size(), "3 submissions, one of them retried once");
        for (JsonNode e : log) {
            assertEquals(key, e.get("idempotencyKey").asText());
        }
        JsonNode billing = mock.billing();
        assertEquals(1, billing.get("billed").asInt(), billing.toString());
        assertEquals(1, billing.get("byKey").get(key).asInt());
    }

    @Test
    void v02_generatedKeysAreReusedAcrossRetries() throws Exception {
        mock.faults("/api/v1/evaluate", "status:503", "drop");
        EvalResult r;
        try (YuguClient c = client(b -> { })) {
            r = c.evaluate(WAV, sentence());
        }
        assertEquals(3, r.getAttempts());
        List<JsonNode> log = logEntries("/api/v1/evaluate");
        assertEquals(3, log.size());
        String key = log.get(0).get("idempotencyKey").asText();
        assertTrue(key.matches("[0-9a-f]{32}"));
        assertEquals(r.getIdempotencyKey(), key);
        for (JsonNode e : log) {
            assertEquals(key, e.get("idempotencyKey").asText(), "a retry never generates a new key");
        }
        assertEquals(1, mock.billing().get("billed").asInt());
    }

    @Test
    void v02_inProgressConflictIsRetriedAndReplayed() throws Exception {
        String key = key("v02-409");
        // the first request registers the key and then processes for 4 s; the mock waits 3 s for it
        mock.faults("/api/v1/evaluate", "slow:4000");
        try (YuguClient c = client(b -> { })) {
            CompletableFuture<EvalResult> first = CompletableFuture.supplyAsync(
                    () -> c.evaluate(WAV, sentence(), RequestOptions.withIdempotencyKey(key)));
            Thread.sleep(300);
            EvalResult second = c.evaluate(WAV, sentence(), RequestOptions.withIdempotencyKey(key));
            EvalResult one = first.get(20, TimeUnit.SECONDS);
            assertFalse(one.isReplayed());
            assertTrue(second.isReplayed());
            assertEquals(one.getRecordId(), second.getRecordId());
            assertEquals(2, second.getAttempts(), "409 40901 is retryable and honours Retry-After");
            assertEquals(40901, events.retryErrors.get(0).getCode());
            assertTrue(events.retryDelays.get(0) >= 1000);
        }
        assertEquals(1, mock.billing().get("billed").asInt());
    }

    @Test
    void compatRestRequiresKeyAuthentication() throws Exception {
        try (YuguClient c = YuguClient.builder().token(MockServer.TOKEN).baseUrl(mock.baseUrl()).build()) {
            AuthException e = assertThrows(AuthException.class,
                    () -> c.evaluateCompat(WAV, new CompatConfig("sent.eval.cn", "今天天气很好")));
            assertEquals(40100, e.getCode());
            assertEquals(1, e.getAttempts());
        }
        assertEquals(0, mock.billing().get("billed").asInt());
    }

    // ------------------------------------------------------------------ V-03

    @Test
    void v03a_http500IsRetriedWithTheSameKeyAndExpectedBackoff() throws Exception {
        mock.faults("/api/v1/evaluate", "status:500", "status:500");
        EvalResult r;
        try (YuguClient c = client(b -> { })) {
            r = c.evaluate(WAV, sentence());
        }
        assertEquals(93.7, r.getOverall());
        assertEquals(3, r.getAttempts());
        assertEquals(2, events.retryDelays.size());
        long d1 = events.retryDelays.get(0);
        long d2 = events.retryDelays.get(1);
        assertTrue(d1 >= 140 && d1 <= 260, "first backoff 200 ms +-30 %: " + d1);
        assertTrue(d2 >= 280 && d2 <= 520, "second backoff 400 ms +-30 %: " + d2);
        List<JsonNode> log = logEntries("/api/v1/evaluate");
        assertEquals(3, log.size());
        assertEquals(log.get(0).get("idempotencyKey").asText(), log.get(2).get("idempotencyKey").asText());
        long gap1 = log.get(1).get("t").asLong() - log.get(0).get("t").asLong();
        long gap2 = log.get(2).get("t").asLong() - log.get(1).get("t").asLong();
        assertTrue(gap1 >= d1 - 5, "gap " + gap1 + " >= delay " + d1);
        assertTrue(gap2 >= d2 - 5, "gap " + gap2 + " >= delay " + d2);
        assertEquals(1, mock.billing().get("billed").asInt());
        assertEquals(2, logs.matching("WARN http retry ").size(), logs.all());
    }

    @Test
    void v03a_exhaustedRetriesThrowServerException() throws Exception {
        mock.faults("/api/v1/evaluate", "status:500", "status:500", "status:500");
        String key = key("v03");
        try (YuguClient c = client(b -> { })) {
            ServerException e = assertThrows(ServerException.class,
                    () -> c.evaluate(WAV, sentence(), RequestOptions.withIdempotencyKey(key)));
            assertEquals(3, e.getAttempts());
            assertEquals(50000, e.getCode());
            assertEquals(500, e.getHttpStatus());
            assertEquals(key, e.getIdempotencyKey());
            assertTrue(e.isRetryable());
        }
        assertEquals(3, logEntries("/api/v1/evaluate").size());
        assertEquals(0, mock.billing().get("billed").asInt(), "failed attempts are not billed");
    }

    @Test
    void v03b_readTimeoutIsRetriedWithTheSameKey() throws Exception {
        mock.faults("/api/v1/evaluate", "delay:1500");
        EvalResult r;
        try (YuguClient c = client(b -> b.readTimeoutMs(400))) {
            r = c.evaluate(WAV, sentence());
        }
        settle(1300);
        assertEquals(2, r.getAttempts());
        assertEquals(90002, events.retryErrors.get(0).getCode());
        List<JsonNode> log = logEntries("/api/v1/evaluate");
        assertEquals(2, log.size());
        assertEquals(log.get(0).get("idempotencyKey").asText(), log.get(1).get("idempotencyKey").asText());
        assertEquals(1, mock.billing().get("billed").asInt());
    }

    @Test
    void v03c_http429IsRetriedAfterRetryAfter() throws Exception {
        mock.faults("/api/v1/evaluate", "status:429:retryAfter=1");
        EvalResult r;
        try (YuguClient c = client(b -> { })) {
            r = c.evaluate(WAV, sentence());
        }
        assertEquals(2, r.getAttempts());
        assertSame(RateLimitException.class, events.retryErrors.get(0).getClass());
        assertEquals(42900, events.retryErrors.get(0).getCode());
        assertEquals(1000, events.retryDelays.get(0));
        List<JsonNode> log = logEntries("/api/v1/evaluate");
        assertTrue(log.get(1).get("t").asLong() - log.get(0).get("t").asLong() >= 995);
        assertEquals(log.get(0).get("idempotencyKey").asText(), log.get(1).get("idempotencyKey").asText());
    }

    @Test
    void v03d_http400IsNotRetried() throws Exception {
        mock.faults("/api/v1/evaluate", "status:400:code=40001");
        try (YuguClient c = client(b -> { })) {
            InvalidParameterException e = assertThrows(InvalidParameterException.class, () -> c.evaluate(WAV, sentence()));
            assertEquals(40001, e.getCode());
            assertEquals(1, e.getAttempts());
            assertFalse(e.isRetryable());
        }
        assertEquals(1, logEntries("/api/v1/evaluate").size());
        assertTrue(events.retryDelays.isEmpty());
        assertEquals(0, logs.matching("retry").size());
    }

    @Test
    void v03_fastApiDetailAndHtmlErrors() throws Exception {
        mock.faults("/api/v1/evaluate", "status:502:detail=engine%20down", "status:503:raw=html");
        try (YuguClient c = client(b -> { })) {
            EvalResult r = c.evaluate(WAV, sentence());
            assertEquals(3, r.getAttempts());
        }
        assertEquals("engine down", events.retryErrors.get(0).getRawMessage());
        assertEquals(502, events.retryErrors.get(0).getHttpStatus());
        assertEquals(503, events.retryErrors.get(1).getHttpStatus());
    }

    @Test
    void writeCallsWithoutKeyAreNotRetried() throws Exception {
        mock.faults("/api/v1/evaluate", "status:500");
        try (YuguClient c = client(b -> b.autoIdempotencyKey(false))) {
            assertThrows(ServerException.class, () -> c.evaluate(WAV, sentence()));
        }
        List<JsonNode> log = logEntries("/api/v1/evaluate");
        assertEquals(1, log.size());
        assertTrue(log.get(0).get("idempotencyKey").isNull());
    }

    // ------------------------------------------------------------------ every REST call with real verification

    @Test
    void allCallsPassTheMockSignatureCheck() throws Exception {
        try (YuguClient c = client(b -> { })) {
            EvalResult nat = c.evaluate(Fixtures.wavPath("zh_short.wav"), sentence().includeReport(true).slack(0.2).scale(100));
            assertEquals(93.7, nat.getOverall());
            EvalResult compat = c.evaluateCompat(WAV, new CompatConfig("sent.eval.cn", "今天天气很好").language("zh-CN")
                    .param("paragraph_need_word_score", 1));
            assertEquals("sent.eval.cn", compat.getCoreType());
            EvalResult word = c.evaluateCompat(Fixtures.wav("en_apple.wav"), new CompatConfig("word.eval", "apple"));
            assertEquals("apple", word.getWords().get(0).getWord());
            TtsResult t = c.tts(new TtsRequest("你好世界", "zh-CN", "xiaoyan").format("mp3").speed(50).pitch(50).volume(50));
            assertTrue(t.getAudioUrl().startsWith("/audio/mock-"), t.getAudioUrl());
            assertEquals(mock.baseUrl() + "/tts" + t.getAudioUrl(), t.getAbsoluteUrl());
            ReportResult rep = c.getReport(nat.getRecordId());
            assertEquals(nat.getRecordId(), rep.getData().get("recordId").asText());
            assertEquals(93.7, rep.getOverall());
        }
        assertEquals(4, mock.billing().get("billed").asInt(), "evaluate, 2 compat, tts");
    }

    @Test
    void platformErrorsMapToTypedExceptions() throws Exception {
        try (YuguClient c = client(b -> { })) {
            InvalidParameterException e = assertThrows(InvalidParameterException.class,
                    () -> c.evaluateCompat(WAV, new CompatConfig("pinyin", "重庆")));
            assertEquals(40001, e.getCode());
            assertTrue(e.getRawMessage().contains("refPinyin"));
            YuguException missing = assertThrows(InvalidParameterException.class, () -> c.getReport("unknown-record"));
            assertEquals(40001, missing.getCode());
        }
        try (YuguClient bad = YuguClient.builder().apiKey(MockServer.APP_KEY, "wrong-secret").baseUrl(mock.baseUrl()).build()) {
            AuthException a = assertThrows(AuthException.class, () -> bad.evaluate(WAV, sentence()));
            assertEquals(2003, a.getCode());
            assertEquals(401, a.getHttpStatus());
            assertEquals(1, a.getAttempts(), "auth errors are not retried");
        }
        try (YuguClient unknownKey = YuguClient.builder().apiKey("nope-key", "x").baseUrl(mock.baseUrl()).build()) {
            AuthException a = assertThrows(AuthException.class, () -> unknownKey.tts(new TtsRequest().text("x")));
            assertEquals(2010, a.getCode());
        }
    }

    @Test
    void tokenAuthAndUserAgent() throws Exception {
        try (YuguClient c = YuguClient.builder().token(MockServer.TOKEN).baseUrl(mock.baseUrl()).build()) {
            assertNotNull(c.evaluate(WAV, sentence()).getRecordId());
        }
        JsonNode entry = logEntries("/api/v1/evaluate").get(0);
        assertEquals("yugu-java-sdk/2.0.0", entry.get("userAgent").asText());
        assertTrue(entry.get("contentType").asText().startsWith("multipart/form-data; boundary="));
        assertNull(entry.get("fault").textValue());
    }
}
