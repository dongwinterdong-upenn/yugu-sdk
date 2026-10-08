package com.shengzhiai.yugu.stcompat.internal;

import com.shengzhiai.yugu.stcompat.testing.FakeTransport;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** DESIGN 2.2 and 2.3 on the compat REST call. */
@RunWith(RobolectricTestRunner.class)
public class CompatClientTest {
    private final List<Long> sleeps = new ArrayList<>();

    private CompatClient client(FakeTransport t) {
        return new CompatClient(t, new Random(7), new CompatClient.Sleeper() {
            @Override
            public boolean sleep(CancelToken token, long ms) {
                sleeps.add(ms);
                return !token.isCancelled();
            }
        });
    }

    private static CompatClient.Request request() {
        CompatClient.Request r = new CompatClient.Request();
        r.baseUrl = "https://open.shengzhiai.com/";
        r.coreType = "sent.eval.cn";
        r.appKey = "app";
        r.secretKey = "secret";
        r.idempotencyKey = "0123456789abcdef0123456789abcdef";
        r.fields.put("refText", "北京你好");
        r.fields.put("language", "zh-CN");
        r.fields.put("empty", "");
        r.audioBytes = new byte[] {1, 2, 3};
        r.audioFilename = "a.wav";
        return r;
    }

    @Test
    public void retriesReuseTheIdempotencyKeyWithFreshNonce() throws Exception {
        FakeTransport t = new FakeTransport();
        t.respond(500, FakeTransport.error(50000, "boom"));
        t.fail(new SocketTimeoutException("Read timed out"));
        CompatClient.Result r = client(t).execute(request(), new CancelToken());
        assertEquals(3, r.attempts);
        assertFalse(r.replayed);
        assertEquals(3, t.count());
        Set<String> nonces = new HashSet<>();
        for (FakeTransport.Call c : t.calls()) {
            assertEquals("0123456789abcdef0123456789abcdef", c.header("Idempotency-Key"));
            assertEquals("app", c.header("X-App-Key"));
            assertEquals(Signer.sign(request().fields, "secret"), c.header("X-Signature"));
            assertTrue(c.header("X-Timestamp").matches("\\d{10}"));
            assertEquals("yugu-stkouyu-compat/2.0.0", c.header("User-Agent"));
            assertEquals("yugu-stkouyu-compat/2.0.0", c.header("X-Yugu-SDK"));
            nonces.add(c.header("X-Nonce"));
            assertEquals("https://open.shengzhiai.com/sent.eval.cn", c.request.url);
            assertEquals("POST", c.request.method);
            String body = c.bodyText();
            assertTrue(body.contains("name=\"refText\""));
            assertTrue(body.contains("北京你好"));
            assertFalse(body.contains("name=\"empty\""));
            assertTrue(body.contains("name=\"audio\"; filename=\"a.wav\"\r\nContent-Type: audio/wav"));
        }
        assertEquals(3, nonces.size());
        assertEquals(2, sleeps.size());
        assertTrue(sleeps.get(0) >= 140 && sleeps.get(0) <= 260);
        assertTrue(sleeps.get(1) >= 280 && sleeps.get(1) <= 520);
    }

    @Test
    public void signatureCoversNonEmptyFieldsOnly() {
        Map<String, String> f = new HashMap<>();
        f.put("refText", "How are you");
        f.put("language", "en-US");
        f.put("paragraph_need_word_score", "1");
        f.put("refPinyin", "");
        assertEquals("YVajpFF/THZKqoMEJ1YgumyNifcelD4k6SgggXMZKao=", Signer.sign(f, "test_secret_key_123"));
    }

    @Test
    public void nonRetryableStopsAtOnce() {
        FakeTransport t = new FakeTransport();
        t.respond(400, FakeTransport.error(40001, "bad"));
        try {
            client(t).execute(request(), new CancelToken());
            fail();
        } catch (CompatError e) {
            assertEquals(40001, e.code);
            assertEquals(1, e.getAttempts());
        }
        assertEquals(1, t.count());
        assertTrue(sleeps.isEmpty());
    }

    @Test
    public void retryAfterIsHonoured() throws Exception {
        FakeTransport t = new FakeTransport();
        Map<String, String> h = new HashMap<>();
        h.put("Retry-After", "1");
        t.respond(429, FakeTransport.error(42900, "排队超时"), h);
        client(t).execute(request(), new CancelToken());
        assertEquals(1, sleeps.size());
        assertEquals(1000L, (long) sleeps.get(0));
    }

    @Test
    public void exhaustedRetriesThrowTheLastError() {
        FakeTransport t = new FakeTransport();
        t.respond(502, FakeTransport.error(50200, "up"));
        t.respond(503, "");
        t.respond(504, "");
        try {
            client(t).execute(request(), new CancelToken());
            fail();
        } catch (CompatError e) {
            assertEquals(504, e.httpStatus);
            assertEquals(3, e.getAttempts());
            assertEquals(20009, ErrorMapper.errId(e));
        }
    }

    @Test
    public void totalDeadlineStopsRetries() {
        FakeTransport t = new FakeTransport();
        t.respond(500, "");
        t.respond(500, "");
        CompatClient.Request r = request();
        r.totalTimeoutMs = 50;
        try {
            client(t).execute(r, new CancelToken());
            fail();
        } catch (CompatError e) {
            assertEquals(1, e.getAttempts());
        }
        assertEquals(1, t.count());
    }

    @Test
    public void cancelledDuringBackoff() {
        FakeTransport t = new FakeTransport();
        t.respond(500, "");
        final CancelToken token = new CancelToken();
        CompatClient c = new CompatClient(t, new Random(1), new CompatClient.Sleeper() {
            @Override
            public boolean sleep(CancelToken tk, long ms) {
                tk.cancel();
                return tk.sleep(ms);
            }
        });
        try {
            c.execute(request(), token);
            fail();
        } catch (CompatError e) {
            assertTrue(e.isCancelled());
        }
        assertEquals(1, t.count());
    }

    @Test
    public void cancelledBeforeStart() {
        CancelToken token = new CancelToken();
        token.cancel();
        token.cancel();
        FakeTransport t = new FakeTransport();
        try {
            client(t).execute(request(), token);
            fail();
        } catch (CompatError e) {
            assertTrue(e.isCancelled());
            assertEquals(0, e.getAttempts());
        }
        assertEquals(0, t.count());
    }

    @Test
    public void cancelledInFlight() throws Exception {
        final FakeTransport t = new FakeTransport().delay(5000);
        final CancelToken token = new CancelToken();
        Thread th = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ignored) {
                    // test
                }
                token.cancel();
            }
        });
        th.start();
        long t0 = System.currentTimeMillis();
        try {
            client(t).execute(request(), token);
            fail();
        } catch (CompatError e) {
            assertTrue(e.isCancelled());
        }
        assertTrue(System.currentTimeMillis() - t0 < 3000);
        th.join();
    }

    @Test
    public void successBodyMustHaveResult() throws Exception {
        FakeTransport t = new FakeTransport();
        t.respond(200, "not json");
        try {
            client(t).execute(request(), new CancelToken());
            fail();
        } catch (CompatError e) {
            assertEquals(90005, e.code);
            assertFalse(e.retryable);
            assertEquals(90005, ErrorMapper.errId(e));
        }
        FakeTransport t2 = new FakeTransport();
        t2.respond(200, "{\"code\":40300,\"message\":\"no\"}");
        try {
            client(t2).execute(request(), new CancelToken());
            fail();
        } catch (CompatError e) {
            assertEquals(40300, e.code);
        }
        FakeTransport t3 = new FakeTransport();
        t3.respond(200, "{\"recordId\":\"x\",\"result\":null}");
        try {
            client(t3).execute(request(), new CancelToken());
            fail();
        } catch (CompatError e) {
            assertEquals(90005, e.code);
        }
        FakeTransport t4 = new FakeTransport();
        t4.respond(200, "{\"errId\":20009,\"error\":\"engine\"}");
        try {
            client(t4).execute(request(), new CancelToken());
            fail();
        } catch (CompatError e) {
            assertEquals(20009, e.code);
        }
    }

    @Test
    public void replayHeaderIsReported() throws Exception {
        FakeTransport t = new FakeTransport();
        Map<String, String> h = new HashMap<>();
        h.put("Idempotency-Replayed", "true");
        t.respond(200, "{\"recordId\":\"eval_1\",\"eof\":1,\"result\":{\"overall\":1}}", h);
        CompatClient.Result r = client(t).execute(request(), null);
        assertTrue(r.replayed);
        assertNotNull(r.body);
    }

    @Test
    public void fileUploadAndNoRetryPolicy() throws Exception {
        java.io.File f = java.io.File.createTempFile("ccx", ".wav");
        f.deleteOnExit();
        java.nio.file.Files.write(f.toPath(), new byte[] {5, 6});
        FakeTransport t = new FakeTransport();
        t.respond(500, "");
        CompatClient.Request r = request();
        r.audioBytes = null;
        r.audioFile = f;
        r.audioFilename = null;
        r.policy = new RetryPolicy(0, 0, 1.0, 0, 0, true, 0);
        try {
            client(t).execute(r, new CancelToken());
            fail();
        } catch (CompatError e) {
            assertEquals(1, e.getAttempts());
        }
        assertTrue(t.calls().get(0).bodyText().contains("filename=\"0123456789abcdef0123456789abcdef.wav\""));
        CompatClient.Request r2 = request();
        r2.audioBytes = null;
        r2.policy = null;
        client(new FakeTransport()).execute(r2, new CancelToken());
        assertEquals("http://x/word.eval", url("http://x", "word.eval"));
    }

    private static String url(String base, String ct) {
        CompatClient.Request r = new CompatClient.Request();
        r.baseUrl = base;
        r.coreType = ct;
        return r.url();
    }

    @Test
    public void sleeperOfConfig() {
        CancelToken t = new CancelToken();
        assertTrue(CompatClient.REAL_SLEEPER.sleep(t, 1));
        t.cancel();
        assertFalse(CompatClient.REAL_SLEEPER.sleep(t, 1000));
        IOException e = new IOException("x");
        assertNotNull(e);
    }
}
