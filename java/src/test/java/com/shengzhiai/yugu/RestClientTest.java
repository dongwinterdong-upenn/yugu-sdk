package com.shengzhiai.yugu;

import com.shengzhiai.yugu.audio.AudioSource;
import com.shengzhiai.yugu.errors.AudioQualityException;
import com.shengzhiai.yugu.errors.AuthException;
import com.shengzhiai.yugu.errors.IllegalSessionStateException;
import com.shengzhiai.yugu.errors.InvalidParameterException;
import com.shengzhiai.yugu.errors.NetworkException;
import com.shengzhiai.yugu.errors.ProtocolViolationException;
import com.shengzhiai.yugu.errors.QuotaExceededException;
import com.shengzhiai.yugu.errors.RateLimitException;
import com.shengzhiai.yugu.errors.RequestCancelledException;
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
import com.shengzhiai.yugu.testing.RecordingEvents;
import com.shengzhiai.yugu.testing.StubServer;
import com.shengzhiai.yugu.testing.StubServer.Resp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** REST behaviour against an in-JVM stub: idempotency, retries, timeouts, signing and request layout. */
class RestClientTest {
    static final String APP_KEY = "mock-app-key";
    static final String SECRET = "mock-secret-key";
    static final String OK = Fixtures.text("fixtures/platform/native_evaluate_sentence_zh.json");
    static final RetryPolicy FAST = RetryPolicy.builder().initialDelayMs(20).maxDelayMs(50).build();

    StubServer stub;
    CapturingLogger logs;
    RecordingEvents events;

    @BeforeEach
    void start() {
        stub = new StubServer();
        logs = new CapturingLogger();
        events = new RecordingEvents();
    }

    @AfterEach
    void stop() {
        stub.close();
    }

    YuguClient.Builder builder() {
        return YuguClient.builder().apiKey(APP_KEY, SECRET).baseUrl(stub.baseUrl()).logger(logs).eventListener(events);
    }

    static EvaluateConfig sentence() {
        return new EvaluateConfig(EvaluateConfig.CORE_SENTENCE, "今天天气很好", "zh-CN");
    }

    /** Minimal multipart parser for assertions. */
    static List<Map<String, Object>> parts(StubServer.Req req) {
        String ct = req.header("Content-Type");
        assertTrue(ct.startsWith("multipart/form-data; boundary="), ct);
        String boundary = "--" + ct.substring(ct.indexOf("boundary=") + 9);
        String body = new String(req.body, StandardCharsets.ISO_8859_1);
        List<Map<String, Object>> out = new ArrayList<>();
        String[] chunks = body.split(java.util.regex.Pattern.quote(boundary));
        for (String c : chunks) {
            if (c.isEmpty() || c.startsWith("--")) {
                continue;
            }
            String p = c.substring(2, c.length() - 2);
            int split = p.indexOf("\r\n\r\n");
            Map<String, Object> m = new LinkedHashMap<>();
            for (String h : p.substring(0, split).split("\r\n")) {
                int k = h.indexOf(':');
                m.put(h.substring(0, k).toLowerCase(), new String(h.substring(k + 1).trim().getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8));
            }
            m.put("data", p.substring(split + 4).getBytes(StandardCharsets.ISO_8859_1));
            out.add(m);
        }
        return out;
    }

    static String text(Map<String, Object> part) {
        return new String((byte[]) part.get("data"), StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ request layout and signing

    @Test
    void evaluateSendsSignedMultipartWithIdempotencyKey() {
        stub.enqueue(200, OK);
        byte[] wav = Fixtures.wav("zh_short.wav");
        EvalResult r;
        try (YuguClient c = builder().build()) {
            r = c.evaluate(wav, sentence().includeReport(true));
        }
        assertEquals(93.7, r.getOverall());
        StubServer.Req req = stub.requests().get(0);
        assertEquals("POST", req.method);
        assertEquals("/api/v1/evaluate", req.path);
        String key = req.header("Idempotency-Key");
        assertTrue(key.matches("[0-9a-f]{32}"), key);
        assertEquals(key, r.getIdempotencyKey());
        assertEquals(1, r.getAttempts());
        assertFalse(r.isReplayed());
        assertEquals("yugu-java-sdk/2.0.0", req.header("User-Agent"));
        assertEquals(APP_KEY, req.header("X-App-Key"));
        assertTrue(Math.abs(Long.parseLong(req.header("X-Timestamp")) - System.currentTimeMillis() / 1000) < 60);
        assertTrue(req.header("X-Nonce").matches("[0-9a-f]{16}"));
        List<Map<String, Object>> parts = parts(req);
        assertEquals(2, parts.size());
        Map<String, Object> config = parts.get(0);
        assertEquals("form-data; name=\"config\"", config.get("content-disposition"), "config part has no filename");
        assertEquals("application/json; charset=utf-8", config.get("content-type"));
        String configText = text(config);
        assertEquals("{\"coreType\":\"sentence\",\"referenceText\":\"今天天气很好\",\"language\":\"zh-CN\",\"includeReport\":true}", configText);
        assertEquals(Signer.sign(Map.of("config", configText), SECRET), req.header("X-Signature"));
        Map<String, Object> audio = parts.get(1);
        assertEquals("form-data; name=\"audio\"; filename=\"audio.wav\"", audio.get("content-disposition"));
        assertEquals("audio/wav", audio.get("content-type"));
        assertArrayEquals(wav, (byte[]) audio.get("data"));
    }

    @Test
    void everyInputKindUploadsTheSameAudio(@TempDir Path dir) throws Exception {
        byte[] wav = Fixtures.wav("zh_short.wav");
        Path p = dir.resolve("clip.wav");
        Files.write(p, wav);
        stub.always(new Resp(200, OK));
        try (YuguClient c = builder().build()) {
            c.evaluate(p, sentence());
            c.evaluate(p.toFile(), sentence());
            c.evaluate(new ByteArrayInputStream(wav), sentence());
            c.evaluate(AudioSource.of(wav), sentence());
            c.evaluate(wav, sentence(), RequestOptions.none());
            c.evaluate(p, sentence(), RequestOptions.none());
            c.evaluate(p.toFile(), sentence(), RequestOptions.none());
            c.evaluate(new ByteArrayInputStream(wav), sentence(), RequestOptions.none());
            c.evaluate(AudioSource.pcm(Fixtures.pcm("zh_short.wav"), 16000), sentence());
        }
        List<StubServer.Req> reqs = stub.requests();
        assertEquals(9, reqs.size());
        for (int i = 0; i < 8; i++) {
            assertArrayEquals(wav, (byte[]) parts(reqs.get(i)).get(1).get("data"), "request " + i);
        }
        assertEquals("form-data; name=\"audio\"; filename=\"clip.wav\"", parts(reqs.get(0)).get(1).get("content-disposition"));
        byte[] wrapped = (byte[]) parts(reqs.get(8)).get(1).get("data");
        assertArrayEquals(Fixtures.pcm("zh_short.wav"), Arrays.copyOfRange(wrapped, 44, wrapped.length));
        assertEquals(9, reqs.stream().map(r -> r.header("Idempotency-Key")).distinct().count(), "one key per logical call");
    }

    @Test
    void openQuestionWithImagePart() {
        stub.enqueue(200, OK);
        try (YuguClient c = builder().build()) {
            c.evaluate(AudioSource.of(Fixtures.wav("zh_short.wav")), AudioSource.of(new byte[]{(byte) 0xFF, (byte) 0xD8, 1}).withFileName("pic.jpg"),
                    new EvaluateConfig(EvaluateConfig.CORE_OPEN, "描述图片", "zh-CN").taskType("picture"), null);
        }
        List<Map<String, Object>> parts = parts(stub.requests().get(0));
        assertEquals(3, parts.size());
        assertEquals("form-data; name=\"image\"; filename=\"pic.jpg\"", parts.get(2).get("content-disposition"));
    }

    @Test
    void compatSignsTheFormFields() {
        stub.enqueue(200, Fixtures.text("fixtures/platform/compat_sent.eval.json"));
        EvalResult r;
        try (YuguClient c = builder().build()) {
            r = c.evaluateCompat(Fixtures.wav("en_apple.wav"), new CompatConfig("sent.eval", "How are you").language("en-US")
                    .param("paragraph_need_word_score", 1).refPinyin(""));
        }
        assertEquals(93.9, r.getOverall());
        StubServer.Req req = stub.requests().get(0);
        assertEquals("/sent.eval", req.path);
        List<Map<String, Object>> parts = parts(req);
        Map<String, String> fields = new LinkedHashMap<>();
        for (Map<String, Object> p : parts) {
            if (!String.valueOf(p.get("content-disposition")).contains("filename")) {
                assertNull(p.get("content-type"), "text fields are plain form fields");
                String cd = (String) p.get("content-disposition");
                fields.put(cd.substring(cd.indexOf("name=\"") + 6, cd.length() - 1), text(p));
            }
        }
        assertEquals(Map.of("refText", "How are you", "language", "en-US", "paragraph_need_word_score", "1"), fields);
        // matches the shared vector compat_form
        assertEquals("YVajpFF/THZKqoMEJ1YgumyNifcelD4k6SgggXMZKao=", Signer.sign(fields, "test_secret_key_123"));
        assertEquals(Signer.sign(fields, SECRET), req.header("X-Signature"));
        assertTrue(String.valueOf(parts.get(parts.size() - 1).get("content-disposition")).contains("filename=\"audio.wav\""));
    }

    @Test
    void compatInputKindsAndValidation(@TempDir Path dir) throws Exception {
        Path p = dir.resolve("a.wav");
        Files.write(p, Fixtures.wav("zh_short.wav"));
        stub.always(new Resp(200, Fixtures.text("fixtures/platform/compat_sent.eval.cn.json")));
        try (YuguClient c = builder().build()) {
            CompatConfig cfg = new CompatConfig("sent.eval.cn", "今天天气很好").language("zh-CN");
            c.evaluateCompat(p, cfg);
            c.evaluateCompat(p.toFile(), cfg);
            c.evaluateCompat(new ByteArrayInputStream(Fixtures.wav("zh_short.wav")), cfg);
            c.evaluateCompat(p, cfg, null);
            c.evaluateCompat(p.toFile(), cfg, null);
            c.evaluateCompat(new ByteArrayInputStream(Fixtures.wav("zh_short.wav")), cfg, null);
            c.evaluateCompat(Fixtures.wav("zh_short.wav"), cfg, null);
            assertThrows(InvalidParameterException.class, () -> c.evaluateCompat(p, new CompatConfig("asr.rec", "x")));
            assertThrows(InvalidParameterException.class, () -> c.evaluateCompat(p, new CompatConfig()));
            assertThrows(InvalidParameterException.class, () -> c.evaluateCompat((AudioSource) null, cfg, null));
        }
        assertEquals(7, stub.requests().size());
    }

    @Test
    void ttsBodyAndSignatureUseTheSameLiterals() {
        stub.enqueue(200, Fixtures.text("fixtures/platform/tts_generate.json"));
        TtsResult t;
        try (YuguClient c = builder().build()) {
            t = c.tts(new TtsRequest("你好世界", "zh-CN", "xiaoyan").format("mp3").speed(50).pitch(50).volume(50));
        }
        StubServer.Req req = stub.requests().get(0);
        assertEquals("/api/v1/tts/generate", req.path);
        assertEquals("application/json; charset=utf-8", req.header("Content-Type"));
        assertEquals("{\"text\":\"你好世界\",\"language\":\"zh-CN\",\"voice\":\"xiaoyan\",\"format\":\"mp3\",\"speed\":50,\"pitch\":50,\"volume\":50}",
                req.bodyText());
        // matches the shared vector tts_json_body
        Map<String, String> sign = Map.of("text", "你好世界", "language", "zh-CN", "voice", "xiaoyan", "format", "mp3",
                "speed", "50", "pitch", "50", "volume", "50");
        assertEquals("OwKwMSazwAh1jmF/ro/dSSgMuJCt8RAFkmf7uZ9cEK4=", Signer.sign(sign, "test_secret_key_123"));
        assertEquals(Signer.sign(sign, SECRET), req.header("X-Signature"));
        assertNotNull(req.header("Idempotency-Key"));
        assertEquals(req.header("Idempotency-Key"), t.getIdempotencyKey());
        assertEquals("mp3", t.getFormat());
        assertTrue(t.toString().contains("tts-local"));
    }

    @Test
    void ttsErrorsAndValidation() {
        stub.enqueue(200, "{\"code\":42903,\"message\":\"sandbox limit\"}");
        stub.enqueue(200, "{\"code\":0,\"data\":null}");
        try (YuguClient c = builder().retry(FAST).build()) {
            QuotaExceededException q = assertThrows(QuotaExceededException.class, () -> c.tts(new TtsRequest().text("x")));
            assertEquals(42903, q.getCode());
            assertFalse(q.isRetryable());
            assertThrows(ProtocolViolationException.class, () -> c.tts(new TtsRequest().text("x")));
            assertThrows(InvalidParameterException.class, () -> c.tts(new TtsRequest()));
            assertThrows(InvalidParameterException.class, () -> c.tts(null, null));
        }
        assertEquals(2, stub.requests().size());
    }

    @Test
    void reportSignsTheEmptySetAndIsRetriedWithoutKey() {
        stub.enqueue(502, "{\"detail\":\"gateway\"}");
        stub.enqueue(200, "{\"code\":0,\"data\":{\"recordId\":\"eval/1 x\",\"overall\":93.7}}");
        ReportResult rep;
        try (YuguClient c = builder().retry(FAST).build()) {
            rep = c.getReport("eval/1 x");
            assertThrows(InvalidParameterException.class, () -> c.getReport(" "));
        }
        assertEquals(93.7, rep.getOverall());
        assertEquals("eval/1 x", rep.getRecordId());
        assertEquals(2, rep.getAttempts());
        assertNotNull(rep.getRaw());
        assertTrue(rep.toString().contains("93.7"));
        List<StubServer.Req> reqs = stub.requests();
        assertEquals(2, reqs.size());
        assertEquals("/api/v1/report/eval%2F1%20x", reqs.get(0).rawPath);
        assertEquals("GET", reqs.get(0).method);
        assertNull(reqs.get(0).header("Idempotency-Key"));
        assertEquals(Signer.sign(Map.of(), SECRET), reqs.get(0).header("X-Signature"));
    }

    @Test
    void tokenAuthUsesBearer() {
        stub.enqueue(200, OK);
        try (YuguClient c = YuguClient.builder().token("mock-jwt-token").baseUrl(stub.baseUrl()).build()) {
            c.evaluate(Fixtures.wav("zh_short.wav"), sentence());
        }
        StubServer.Req req = stub.requests().get(0);
        assertEquals("Bearer mock-jwt-token", req.header("Authorization"));
        assertNull(req.header("X-Signature"));
        assertNull(req.header("X-App-Key"));
    }

    @Test
    void replayHeaderSetsReplayed() {
        stub.enqueue(new Resp(200, OK).header("Idempotency-Replayed", "true"));
        try (YuguClient c = builder().build()) {
            assertTrue(c.evaluate(Fixtures.wav("zh_short.wav"), sentence()).isReplayed());
        }
    }

    // ------------------------------------------------------------------ retry and idempotency

    @Test
    void retriesReuseTheSameKeyAndAreObservable() {
        stub.enqueue(503, "{\"code\":50200,\"message\":\"upstream down\"}");
        stub.enqueue(200, OK);
        EvalResult r;
        try (YuguClient c = TestHooks.random(builder(), new java.util.Random(1)).build()) {
            r = c.evaluate(Fixtures.wav("zh_short.wav"), sentence());
        }
        List<StubServer.Req> reqs = stub.requests();
        assertEquals(2, reqs.size());
        assertEquals(reqs.get(0).header("Idempotency-Key"), reqs.get(1).header("Idempotency-Key"));
        assertNotEquals(reqs.get(0).header("X-Nonce"), reqs.get(1).header("X-Nonce"), "each attempt is signed afresh");
        assertEquals(2, r.getAttempts());
        assertEquals(1, events.retryDelays.size());
        long delay = events.retryDelays.get(0);
        assertTrue(delay >= 140 && delay <= 260, "delay " + delay);
        assertTrue(reqs.get(1).atMs - reqs.get(0).atMs >= delay - 5, "waited the announced delay");
        assertEquals(50200, events.retryErrors.get(0).getCode());
        List<String> warn = logs.matching("retry 1/2 in " + delay + " ms: HTTP 503 code=50200");
        assertEquals(1, warn.size(), logs.all());
        assertTrue(warn.get(0).startsWith("WARN http"));
        assertEquals(List.of("start:evaluate:POST:/api/v1/evaluate:1", "retry:evaluate:1",
                "start:evaluate:POST:/api/v1/evaluate:2", "end:evaluate:200:2:ok"), events.events);
    }

    @Test
    void exhaustedRetriesThrowTheLastErrorWithContext() {
        stub.always(new Resp(500, "{\"code\":50000,\"message\":\"boom\"}"));
        String key = "order-42-attempt";
        ServerException e;
        try (YuguClient c = builder().retry(FAST).build()) {
            e = assertThrows(ServerException.class,
                    () -> c.evaluate(Fixtures.wav("zh_short.wav"), sentence(), RequestOptions.withIdempotencyKey(key)));
        }
        assertEquals(3, e.getAttempts());
        assertEquals(key, e.getIdempotencyKey());
        assertEquals(50000, e.getCode());
        assertEquals(500, e.getHttpStatus());
        assertTrue(e.isRetryable());
        assertEquals("{\"code\":50000,\"message\":\"boom\"}", e.getRawBody());
        assertEquals(3, stub.requests().size());
        assertTrue(stub.requests().stream().allMatch(r -> key.equals(r.header("Idempotency-Key"))));
        assertEquals(3, events.lastAttempts);
        assertEquals(500, events.lastStatus);
        assertSame(e, events.lastError);
    }

    @Test
    void nonRetryableErrorsAreNotRetried() {
        stub.enqueue(400, "{\"code\":40001,\"message\":\"referenceText 不能为空\"}");
        stub.enqueue(401, "{\"code\":2003,\"message\":\"签名验证失败\"}");
        try (YuguClient c = builder().build()) {
            InvalidParameterException e = assertThrows(InvalidParameterException.class,
                    () -> c.evaluate(Fixtures.wav("zh_short.wav"), sentence()));
            assertEquals(1, e.getAttempts());
            assertEquals(40001, e.getCode());
            AuthException a = assertThrows(AuthException.class, () -> c.evaluate(Fixtures.wav("zh_short.wav"), sentence()));
            assertEquals(2003, a.getCode());
        }
        assertEquals(2, stub.requests().size());
        assertEquals(0, events.retryDelays.size());
    }

    @Test
    void retryAfterIsHonoured() {
        stub.enqueue(new Resp(429, "{\"code\":42900,\"message\":\"排队超时\"}").header("Retry-After", "0.4"));
        stub.enqueue(200, OK);
        try (YuguClient c = builder().build()) {
            c.evaluate(Fixtures.wav("zh_short.wav"), sentence());
        }
        List<StubServer.Req> reqs = stub.requests();
        assertEquals(400, events.retryDelays.get(0));
        assertTrue(reqs.get(1).atMs - reqs.get(0).atMs >= 395, "waited Retry-After");
        assertSame(RateLimitException.class, events.retryErrors.get(0).getClass());
        assertEquals(400, events.retryErrors.get(0).getRetryAfterMs());
    }

    @Test
    void totalTimeoutBoundsRetries() {
        stub.always(new Resp(429, "{\"code\":42900}").header("Retry-After", "5"));
        long t0 = System.nanoTime();
        try (YuguClient c = builder().build()) {
            RateLimitException e = assertThrows(RateLimitException.class, () -> c.evaluate(Fixtures.wav("zh_short.wav"), sentence(),
                    RequestOptions.builder().totalTimeoutMs(1500).build()));
            assertEquals(1, e.getAttempts(), "a wait past the deadline is not started");
        }
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0) < 1500);
    }

    @Test
    void readTimeoutIsRetriedWithTheSameKey() {
        stub.enqueue(new Resp(200, OK).delay(1500));
        stub.enqueue(200, OK);
        EvalResult r;
        try (YuguClient c = builder().retry(FAST).readTimeoutMs(300).build()) {
            r = c.evaluate(Fixtures.wav("zh_short.wav"), sentence());
        }
        assertEquals(2, r.getAttempts());
        assertSame(RequestTimeoutException.class, events.retryErrors.get(0).getClass());
        assertEquals(90002, events.retryErrors.get(0).getCode());
        List<StubServer.Req> reqs = stub.requests();
        assertEquals(reqs.get(0).header("Idempotency-Key"), reqs.get(1).header("Idempotency-Key"));
    }

    @Test
    void readTimeoutOverrideAndExhaustion() {
        stub.always(new Resp(200, OK).delay(800));
        try (YuguClient c = builder().retry(RetryPolicy.builder().maxRetries(1).initialDelayMs(10).build()).build()) {
            RequestTimeoutException e = assertThrows(RequestTimeoutException.class, () -> c.evaluate(Fixtures.wav("zh_short.wav"),
                    sentence(), RequestOptions.builder().readTimeoutMs(150).build()));
            assertEquals(2, e.getAttempts());
            assertTrue(e.isRetryable());
        }
    }

    @Test
    void writesWithoutKeyAreNotRetried() {
        stub.enqueue(503, "{\"code\":50200}");
        try (YuguClient c = builder().autoIdempotencyKey(false).logLevel(LogLevel.DEBUG).build()) {
            assertThrows(ServerException.class, () -> c.evaluate(Fixtures.wav("zh_short.wav"), sentence()));
        }
        assertEquals(1, stub.requests().size());
        assertNull(stub.requests().get(0).header("Idempotency-Key"));
        assertFalse(logs.matching("no idempotency key, retries disabled").isEmpty(), logs.all());
    }

    @Test
    void callerKeyIsValidatedBeforeAnyIo() {
        try (YuguClient c = builder().build()) {
            YuguException e = assertThrows(InvalidParameterException.class, () -> c.evaluate(Fixtures.wav("zh_short.wav"), sentence(),
                    RequestOptions.withIdempotencyKey("bad key with spaces")));
            assertEquals(90010, e.getCode());
            assertThrows(InvalidParameterException.class, () -> c.evaluate(Fixtures.wav("zh_short.wav"), new EvaluateConfig()));
            assertThrows(InvalidParameterException.class, () -> c.evaluate((AudioSource) null, sentence()));
            assertThrows(InvalidParameterException.class, () -> c.evaluate(new byte[0], sentence()));
            assertThrows(InvalidParameterException.class, () -> c.evaluate(Fixtures.wav("zh_short.wav"), sentence(),
                    RequestOptions.builder().readTimeoutMs(0).build()));
        }
        assertEquals(0, stub.requests().size());
    }

    @Test
    void envelopeErrorsAndProtocolErrors() {
        stub.enqueue(200, "{\"code\":50000,\"message\":\"busy\"}");
        stub.enqueue(200, OK);
        stub.enqueue(200, "<html>oops</html>");
        stub.enqueue(200, "{\"recordId\":\"x\"}");
        try (YuguClient c = builder().retry(FAST).build()) {
            EvalResult r = c.evaluate(Fixtures.wav("zh_short.wav"), sentence());
            assertEquals(2, r.getAttempts(), "a retryable code in a 200 envelope is retried");
            ProtocolViolationException p = assertThrows(ProtocolViolationException.class, () -> c.evaluate(Fixtures.wav("zh_short.wav"), sentence()));
            assertEquals(90005, p.getCode());
            assertEquals(200, p.getHttpStatus());
            assertEquals("<html>oops</html>", p.getRawBody());
            assertNotNull(p.getIdempotencyKey());
            assertThrows(ProtocolViolationException.class, () -> c.evaluate(Fixtures.wav("zh_short.wav"), sentence()));
        }
        assertEquals(4, stub.requests().size(), "protocol errors are not retried");
    }

    @Test
    void networkFailuresAreRetried() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        try (YuguClient c = YuguClient.builder().apiKey(APP_KEY, SECRET).baseUrl("http://127.0.0.1:" + port).retry(FAST)
                .eventListener(events).logger(logs).build()) {
            NetworkException e = assertThrows(NetworkException.class, () -> c.evaluate(Fixtures.wav("zh_short.wav"), sentence()));
            assertEquals(90001, e.getCode());
            assertEquals(3, e.getAttempts());
            assertTrue(e.isRetryable());
        }
        assertEquals(2, events.retryDelays.size());
    }

    @Test
    void droppedConnectionIsRetried() {
        stub.enqueue(Resp.drop());
        stub.enqueue(200, OK);
        try (YuguClient c = builder().retry(FAST).build()) {
            assertEquals(2, c.evaluate(Fixtures.wav("zh_short.wav"), sentence()).getAttempts());
        }
        assertSame(NetworkException.class, events.retryErrors.get(0).getClass());
    }

    // ------------------------------------------------------------------ cancellation and lifecycle

    @Test
    void cancellationEndsTheRetryWait() throws Exception {
        stub.always(new Resp(503, "{\"code\":50200}").header("Retry-After", "10"));
        CancellationToken token = new CancellationToken();
        try (YuguClient c = builder().build()) {
            CompletableFuture<Object> f = CompletableFuture.supplyAsync(() -> {
                try {
                    return c.evaluate(Fixtures.wav("zh_short.wav"), sentence(), RequestOptions.builder().cancellation(token).build());
                } catch (YuguException e) {
                    return e;
                }
            });
            long until = System.currentTimeMillis() + 5000;
            while (events.retryDelays.isEmpty() && System.currentTimeMillis() < until) {
                Thread.sleep(10);
            }
            long t0 = System.nanoTime();
            token.cancel();
            Object out = f.get(5, TimeUnit.SECONDS);
            assertSame(RequestCancelledException.class, out.getClass());
            assertEquals(90003, ((YuguException) out).getCode());
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0) < 2000);
        }
        assertEquals(1, stub.requests().size());
    }

    @Test
    void cancellationAbortsTheRunningAttempt() throws Exception {
        stub.always(new Resp(200, OK).delay(3000));
        CancellationToken token = new CancellationToken();
        try (YuguClient c = builder().build()) {
            CompletableFuture<Object> f = CompletableFuture.supplyAsync(() -> {
                try {
                    return c.evaluate(Fixtures.wav("zh_short.wav"), sentence(), RequestOptions.builder().cancellation(token).build());
                } catch (YuguException e) {
                    return e;
                }
            });
            while (stub.requests().isEmpty()) {
                Thread.sleep(5);
            }
            token.cancel();
            assertSame(RequestCancelledException.class, f.get(2, TimeUnit.SECONDS).getClass());
            CancellationToken pre = new CancellationToken();
            pre.cancel();
            assertThrows(RequestCancelledException.class, () -> c.evaluate(Fixtures.wav("zh_short.wav"), sentence(),
                    RequestOptions.builder().cancellation(pre).build()));
        }
    }

    @Test
    void closeAbortsRunningCallsAndRejectsNewOnes() throws Exception {
        stub.always(new Resp(200, OK).delay(3000));
        YuguClient c = builder().build();
        CompletableFuture<Object> f = CompletableFuture.supplyAsync(() -> {
            try {
                return c.evaluate(Fixtures.wav("zh_short.wav"), sentence());
            } catch (YuguException e) {
                return e;
            }
        });
        while (stub.requests().isEmpty()) {
            Thread.sleep(5);
        }
        c.close();
        c.close();
        Object out = f.get(3, TimeUnit.SECONDS);
        assertSame(IllegalSessionStateException.class, out.getClass());
        assertEquals(90004, ((YuguException) out).getCode());
        assertTrue(c.isClosed());
        int before = stub.requests().size();
        YuguException e = assertThrows(IllegalSessionStateException.class, () -> c.evaluate(Fixtures.wav("zh_short.wav"), sentence()));
        assertEquals(90004, e.getCode());
        assertThrows(IllegalSessionStateException.class, () -> c.tts(new TtsRequest().text("x")));
        assertThrows(IllegalSessionStateException.class, () -> c.getReport("eval_1"));
        assertThrows(IllegalSessionStateException.class, () -> c.evaluateCompat(new byte[]{1}, new CompatConfig("sent.eval", "x")));
        assertEquals(before, stub.requests().size());
    }

    // ------------------------------------------------------------------ audio checks and logging

    @Test
    void precheckRejectThrowsBeforeUpload() {
        try (YuguClient c = builder().audioPrecheck(AudioPrecheckMode.REJECT).build()) {
            AudioQualityException e = assertThrows(AudioQualityException.class, () -> c.evaluate(Fixtures.wav("silent.wav"), sentence()));
            assertEquals(90103, e.getCode());
            assertFalse(e.isRetryable());
            assertNull(e.getResult());
        }
        assertEquals(0, stub.requests().size(), "nothing uploaded, nothing billed");
    }

    @Test
    void precheckWarnUploadsAndReportsLocalWarnings() {
        stub.always(new Resp(200, OK));
        try (YuguClient c = builder().build()) {
            EvalResult r = c.evaluate(Fixtures.wav("low_volume.wav"), sentence());
            assertEquals(1, r.getLocalWarnings().size());
            assertEquals(90104, r.getLocalWarnings().get(0).getCode());
            assertTrue(c.evaluate(Fixtures.wav("zh_short.wav"), sentence()).getLocalWarnings().isEmpty());
        }
        try (YuguClient c = builder().audioPrecheck(AudioPrecheckMode.REJECT).build()) {
            assertEquals(90104, c.evaluate(Fixtures.wav("low_volume.wav"), sentence()).getLocalWarnings().get(0).getCode(),
                    "low volume never rejects");
        }
        try (YuguClient c = builder().audioPrecheck(AudioPrecheckMode.OFF).build()) {
            assertTrue(c.evaluate(Fixtures.wav("silent.wav"), sentence()).getLocalWarnings().isEmpty());
        }
        assertEquals(4, stub.requests().size());
        assertFalse(logs.matching("WARN precheck 90104").isEmpty(), logs.all());
    }

    @Test
    void strictAudioThrowsForWarning1001Only() {
        String with1001 = OK.replace("\"warnings\": []", "\"warnings\": [1001]").replace("\"warnings\":[]", "\"warnings\":[1001]");
        assertNotEquals(OK, with1001, "fixture rewritten with warning 1001");
        stub.always(new Resp(200, with1001));
        try (YuguClient c = builder().strictAudio(true).build()) {
            AudioQualityException e = assertThrows(AudioQualityException.class, () -> c.evaluate(Fixtures.wav("zh_short.wav"), sentence()));
            assertEquals(1001, e.getCode());
            assertNotNull(e.getResult());
            assertEquals("eval_3fb45f4c8e71", e.getRecordId());
        }
        try (YuguClient c = builder().build()) {
            assertEquals(java.util.List.of(1001), c.evaluate(Fixtures.wav("zh_short.wav"), sentence()).getWarningCodes());
        }
        assertEquals(2, stub.requests().size(), "a warning is not retried");
    }

    @Test
    void debugLogsNeverContainSecrets() {
        stub.enqueue(503, "{\"code\":50200}");
        stub.enqueue(200, OK);
        String signature;
        try (YuguClient c = builder().logLevel(LogLevel.DEBUG).build()) {
            c.evaluate(Fixtures.wav("zh_short.wav"), sentence());
            signature = stub.requests().get(0).header("X-Signature");
        }
        try (YuguClient c = YuguClient.builder().token("mock-jwt-token").baseUrl(stub.baseUrl()).logger(logs).logLevel(LogLevel.DEBUG).build()) {
            stub.enqueue(200, OK);
            c.evaluate(Fixtures.wav("zh_short.wav"), sentence());
        }
        String all = logs.all();
        assertTrue(all.contains("X-App-Key=mock***"), all);
        assertTrue(all.contains("X-Signature=***"), all);
        assertTrue(all.contains("Authorization=Bearer ***"), all);
        assertTrue(all.contains("INFO http evaluate 200"), all);
        assertFalse(all.contains(SECRET), "secret key logged");
        assertFalse(all.contains(signature), "signature logged");
        assertFalse(all.contains("mock-jwt-token"), "token logged");
        assertFalse(all.contains("mock-app-key"), "full app key logged");
    }

    @Test
    void brokenEventListenerDoesNotBreakCalls() {
        stub.always(new Resp(200, OK));
        EventListener broken = new EventListener() {
            @Override
            public void onRequestStart(String op, String method, String path, int attempt) {
                throw new IllegalStateException("x");
            }

            @Override
            public void onRequestEnd(String op, int httpStatus, long latencyMs, int attempts, YuguException error) {
                throw new IllegalStateException("y");
            }
        };
        try (YuguClient c = YuguClient.builder().apiKey(APP_KEY, SECRET).baseUrl(stub.baseUrl()).eventListener(broken).logger(logs).build()) {
            assertEquals(93.7, c.evaluate(Fixtures.wav("zh_short.wav"), sentence()).getOverall());
        }
        assertFalse(logs.matching("eventListener.onRequestStart threw").isEmpty());
    }
}
