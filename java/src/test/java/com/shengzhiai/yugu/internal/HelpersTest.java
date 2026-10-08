package com.shengzhiai.yugu.internal;

import com.shengzhiai.yugu.LogLevel;
import com.shengzhiai.yugu.errors.InvalidParameterException;
import com.shengzhiai.yugu.testing.CapturingLogger;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HelpersTest {

    @Test
    void jsonBodyUsesPlainNumbersAndSignsTheSameLiterals() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("text", "你好世界");
        m.put("speed", 50);
        m.put("ratio", 0.2);
        m.put("whole", 50.0);
        m.put("tiny", 1e-7);
        m.put("big", new BigDecimal("1E+3"));
        m.put("bi", BigInteger.TEN);
        m.put("flag", true);
        m.put("style", null);
        m.put("nested", Map.of("a", 1));
        m.put("list", List.of(1, "x"));
        m.put("obj", new StringBuilder("sb"));
        String json = Json.writePlainObject(m);
        assertEquals("{\"text\":\"你好世界\",\"speed\":50,\"ratio\":0.2,\"whole\":50,\"tiny\":0.0000001,\"big\":1000,"
                + "\"bi\":10,\"flag\":true,\"style\":null,\"nested\":{\"a\":1},\"list\":[1,\"x\"],\"obj\":\"sb\"}", json);
        Map<String, String> sign = Json.scalarFields(m);
        assertEquals("50", sign.get("speed"));
        assertEquals("0.2", sign.get("ratio"));
        assertEquals("50", sign.get("whole"));
        assertEquals("0.0000001", sign.get("tiny"));
        assertEquals("true", sign.get("flag"));
        assertFalse(sign.containsKey("style"));
        assertFalse(sign.containsKey("nested"));
        assertFalse(sign.containsKey("list"));
        assertEquals("0", Json.plainNumber(0.0));
        assertEquals("-3.5", Json.plainNumber(-3.5f));
        assertThrows(InvalidParameterException.class, () -> Json.plainNumber(Double.NaN));
        assertThrows(InvalidParameterException.class, () -> Json.write(new Object() {
            @SuppressWarnings("unused")
            public Object getSelf() {
                return this;
            }
        }));
    }

    @Test
    void percentEncodingRoundTrip() {
        assertEquals("a%2Bb%2F%3D%20%E4%B8%AD~._-", PercentEncoding.encode("a+b/= 中~._-"));
        assertEquals("a+b/= 中~._-", PercentEncoding.decode("a%2Bb%2F%3D%20%E4%B8%AD~._-"));
        assertEquals("%zz+", PercentEncoding.decode("%zz+"));
        assertEquals("%4", PercentEncoding.decode("%4"));
        assertEquals("😀x", PercentEncoding.decode("😀x"));
    }

    @Test
    void redaction() {
        assertEquals("mock***", Redact.appKey("mock-app-key"));
        assertEquals("***", Redact.appKey("abcd"));
        assertEquals("***", Redact.appKey(null));
        assertEquals("***", Redact.header("X-Signature", "sig"));
        assertEquals("Bearer ***", Redact.header("authorization", "Bearer abc"));
        assertEquals("***", Redact.header("Authorization", "Basic abc"));
        assertEquals("mock***", Redact.header("X-App-Key", "mock-app-key"));
        assertEquals("1", Redact.header("X-Timestamp", "1"));
        Map<String, String> h = new LinkedHashMap<>();
        h.put("X-Signature", "s3cr3t");
        h.put("Idempotency-Key", "k");
        assertEquals("{Idempotency-Key=k, X-Signature=***}", Redact.headers(h).toString());
        String url = "wss://h/p?appKey=mock-app-key&timestamp=1&signature=abc%2B&token=t&idempotencyKey=k&flag";
        assertEquals("wss://h/p?appKey=mock***&timestamp=1&signature=***&token=***&idempotencyKey=k&flag=", Redact.url(url));
        assertEquals("wss://h/p", Redact.url("wss://h/p"));
    }

    @Test
    void retryAfterForms() {
        assertEquals(1000, RetryAfter.parseMs("1", 0));
        assertEquals(1500, RetryAfter.parseMs(" 1.5 ", 0));
        assertEquals(-1, RetryAfter.parseMs(null, 0));
        assertEquals(-1, RetryAfter.parseMs("", 0));
        assertEquals(-1, RetryAfter.parseMs("-3", 0));
        assertEquals(-1, RetryAfter.parseMs("soon", 0));
        ZonedDateTime now = ZonedDateTime.of(2026, 10, 8, 12, 0, 0, 0, ZoneOffset.UTC);
        String date = now.plusSeconds(5).format(DateTimeFormatter.RFC_1123_DATE_TIME);
        assertEquals(5000, RetryAfter.parseMs(date, now.toInstant().toEpochMilli()));
        assertEquals(0, RetryAfter.parseMs(now.minusSeconds(5).format(DateTimeFormatter.RFC_1123_DATE_TIME), now.toInstant().toEpochMilli()));
    }

    @Test
    void idempotencyKeys() {
        String k = Ids.newIdempotencyKey();
        assertTrue(k.matches("[0-9a-f]{32}"), k);
        assertNotEquals(k, Ids.newIdempotencyKey());
        assertTrue(Ids.nonce().matches("[0-9a-f]{16}"));
        assertTrue(Ids.sessionId().matches("ws-[0-9a-f]{8}"));
        assertEquals("caller-key_1:/", Ids.validateIdempotencyKey("caller-key_1:/"));
        StringBuilder max = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            max.append('k');
        }
        assertEquals(max.toString(), Ids.validateIdempotencyKey(max.toString()));
        assertThrows(InvalidParameterException.class, () -> Ids.validateIdempotencyKey(max + "k"));
        assertThrows(InvalidParameterException.class, () -> Ids.validateIdempotencyKey(""));
        assertThrows(InvalidParameterException.class, () -> Ids.validateIdempotencyKey("has space"));
        assertThrows(InvalidParameterException.class, () -> Ids.validateIdempotencyKey("中文"));
        assertThrows(InvalidParameterException.class, () -> Ids.validateIdempotencyKey(null));
        assertNull(Ids.resolve(null, false));
        assertTrue(Ids.resolve(null, true).matches("[0-9a-f]{32}"));
        assertEquals("mine", Ids.resolve("mine", false));
    }

    @Test
    void serialExecutorRunsOneTaskAtATimeInOrder() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        SerialExecutor s = new SerialExecutor(pool, errors::add);
        AtomicInteger inside = new AtomicInteger();
        AtomicBoolean overlap = new AtomicBoolean();
        List<Integer> order = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch done = new CountDownLatch(1000);
        for (int i = 0; i < 1000; i++) {
            int n = i;
            s.execute(() -> {
                if (inside.incrementAndGet() > 1) {
                    overlap.set(true);
                }
                order.add(n);
                inside.decrementAndGet();
                done.countDown();
            });
        }
        s.execute(() -> {
            throw new IllegalStateException("boom");
        });
        assertTrue(done.await(10, TimeUnit.SECONDS));
        pool.shutdown();
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        assertFalse(overlap.get());
        for (int i = 0; i < 1000; i++) {
            assertEquals(i, order.get(i));
        }
        assertEquals(1, errors.size());
        // a shut down pool: the task still runs, on the calling thread
        AtomicBoolean ran = new AtomicBoolean();
        s.execute(() -> ran.set(true));
        assertTrue(ran.get());
    }

    @Test
    void logLevelsAndSinkFailures() {
        CapturingLogger sink = new CapturingLogger();
        Log warn = new Log(LogLevel.WARN, sink);
        warn.debug("t", "d");
        warn.info("t", "i");
        warn.warn("t", "w", null);
        warn.error("t", "e", new RuntimeException("x"));
        assertEquals(2, sink.lines.size());
        assertFalse(warn.isDebug());
        assertFalse(warn.isInfo());
        Log off = new Log(LogLevel.OFF, sink);
        off.error("t", "never", null);
        assertEquals(2, sink.lines.size());
        Log debug = new Log(LogLevel.DEBUG, (l, t, m, e) -> {
            throw new IllegalStateException("broken sink");
        });
        debug.debug("t", "swallowed");
        assertTrue(debug.isDebug());
        assertTrue(LogLevel.DEBUG.allows(LogLevel.ERROR));
        assertFalse(LogLevel.ERROR.allows(LogLevel.WARN));
        assertFalse(LogLevel.OFF.allows(LogLevel.ERROR));
        assertFalse(LogLevel.DEBUG.allows(LogLevel.OFF));
        PrintStream old = System.err;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        System.setErr(new PrintStream(buf, true, StandardCharsets.UTF_8));
        try {
            new Log(null, null).warn("http", "retry 1/2 in 231 ms: HTTP 503 code=50200", new RuntimeException("cause"));
        } finally {
            System.setErr(old);
        }
        String line = buf.toString(StandardCharsets.UTF_8);
        assertTrue(line.contains("WARN [yugu/http] retry 1/2 in 231 ms: HTTP 503 code=50200: java.lang.RuntimeException: cause"), line);
    }

    @Test
    void daemonThreads() throws Exception {
        Thread t = new DaemonThreadFactory("yugu-test").newThread(() -> { });
        assertTrue(t.isDaemon());
        assertEquals("yugu-test-1", t.getName());
    }
}
