package com.shengzhiai.yugu.stcompat;

import com.shengzhiai.yugu.stcompat.internal.Signer;
import com.shengzhiai.yugu.stcompat.internal.YLog;
import com.shengzhiai.yugu.stcompat.testing.CompatTestBase;
import com.shengzhiai.yugu.stcompat.testing.FakeTransport;
import com.shengzhiai.yugu.stcompat.testing.Main;
import com.stkouyu.SkEgnManager;
import com.stkouyu.YuguCompat;
import com.stkouyu.setting.EngineSetting;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** TtsClient (SDK 2.2.0) on the fake transport: request, signing, retries, errors, threads, log. */
@RunWith(RobolectricTestRunner.class)
public class TtsClientTest extends CompatTestBase {
    static final String OK = "{\"code\":0,\"message\":\"success\",\"data\":{\"audioUrl\":\"/audio/tts_1.mp3\","
            + "\"duration\":\"1.348\",\"format\":\"mp3\",\"warnings\":[]},\"timestamp\":1791886000000}";

    private final List<String> lines = Collections.synchronizedList(new ArrayList<String>());

    @Before
    public void capture() {
        YuguCompat.setLogLevel(YuguCompat.LOG_DEBUG);
        YLog.setSink((level, tag, msg, err) -> lines.add(level + " " + msg));
    }

    /** Runs a blocking call on a worker thread and rethrows what it threw. */
    static <T> T offMain(Callable<T> call) throws Exception {
        ExecutorService ex = Executors.newSingleThreadExecutor();
        try {
            return ex.submit(call).get(60, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Exception) {
                throw (Exception) e.getCause();
            }
            throw e;
        } finally {
            ex.shutdownNow();
        }
    }

    static YuguApiException apiError(Callable<?> call) throws Exception {
        try {
            offMain(call);
        } catch (YuguApiException e) {
            return e;
        }
        throw new AssertionError("no YuguApiException");
    }

    private List<String> linesStartingWith(String prefix) {
        List<String> out = new ArrayList<>();
        synchronized (lines) {
            for (String l : lines) {
                if (l.startsWith(prefix)) {
                    out.add(l);
                }
            }
        }
        return out;
    }

    // ================================================================ construction

    @Test
    public void createNeedsAnInitialisedEngineOrCredentials() {
        try {
            TtsClient.create();
            fail();
        } catch (IllegalStateException expected) {
            assertEquals("引擎未初始化", expected.getMessage());
        }
        for (String[] bad : new String[][] {{null, "s"}, {" ", "s"}, {"k", null}, {"k", ""}}) {
            try {
                TtsClient.create(bad[0], bad[1]);
                fail();
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().endsWith("不能为空"));
            }
        }
        try {
            TtsClient.create("k", "s", "open.shengzhiai.com");
            fail();
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("scheme"));
        }
        assertEquals("https://open.shengzhiai.com", TtsClient.create("k", "s").getBaseUrl());
        assertEquals("https://open.shengzhiai.com", TtsClient.create("k", "s", " ").getBaseUrl());
        assertEquals("https://sandbox.example.com", TtsClient.create("k", "s", "wss://sandbox.example.com/").getBaseUrl());
        YuguCompat.setBaseUrl("http://10.1.1.1:9000");
        assertEquals("the default server follows YuguCompat.setBaseUrl", "http://10.1.1.1:9000",
                TtsClient.create("k", "s").getBaseUrl());
        assertEquals("an explicit base wins", "https://x.example.com",
                TtsClient.create("k", "s", "https://x.example.com").getBaseUrl());
    }

    @Test
    public void createFromTheEngineUsesItsKeysAddressAndTimeouts() throws Exception {
        SkEgnManager m = SkEgnManager.getInstance(app);
        EngineSetting es = EngineSetting.getInstance(app);
        es.setServerAddress("http://10.0.0.1:8080/");
        es.setConnectTimeout(4);
        es.setServerTimeout(9);
        m.initEngine("engine-key", "engine-secret", "u", es);
        Main.idle();
        TtsClient c = TtsClient.create();
        assertEquals("http://10.0.0.1:8080", c.getBaseUrl());
        transport.respond(200, OK);
        offMain(() -> c.tts(new TtsRequest("你好"), null));
        FakeTransport.Call call = transport.calls().get(0);
        assertEquals("http://10.0.0.1:8080/api/v1/tts/generate", call.request.url);
        assertEquals("engine-key", call.header("X-App-Key"));
        assertEquals(4000, call.request.connectTimeoutMs);
        assertEquals(9000, call.request.readTimeoutMs);
        // YuguCompat wins over the engine's address and timeouts, also for a client created before
        YuguCompat.setBaseUrl("https://sandbox.example.com");
        YuguCompat.setTimeouts(1500, 2500, 60000);
        transport.respond(200, OK);
        offMain(() -> c.tts(new TtsRequest("你好"), null));
        call = transport.calls().get(1);
        assertEquals("https://sandbox.example.com/api/v1/tts/generate", call.request.url);
        assertEquals(1500, call.request.connectTimeoutMs);
        assertEquals(2500, call.request.readTimeoutMs);
        // the client keeps its keys after recycle
        m.recycle();
        transport.respond(200, OK);
        offMain(() -> c.tts(new TtsRequest("你好"), null));
        assertEquals("engine-key", transport.calls().get(2).header("X-App-Key"));
        try {
            TtsClient.create();
            fail();
        } catch (IllegalStateException expected) {
            assertEquals("引擎未初始化", expected.getMessage());
        }
    }

    // ================================================================ request

    @Test
    public void bodyCarriesOnlyTheFieldsSetAndIsSignedWithThem() throws Exception {
        transport.respond(200, OK);
        TtsClient c = TtsClient.create("ak", "sk");
        TtsRequest req = new TtsRequest("“床前”明月光\n\"x\"\\").setLanguage("zh-CN").setVoice("xiaoyan").setFormat("mp3")
                .setSpeed(55).setPitch(0).setVolume(100).setSampleRate(16000).setEmotion("warm").setStyle("")
                .setPinyin("chuang2 qian2").setGenre("classical_poem");
        TtsResult r = offMain(() -> c.tts(req, null));
        FakeTransport.Call call = transport.calls().get(0);
        assertEquals("POST", call.request.method);
        assertEquals("https://open.shengzhiai.com/api/v1/tts/generate", call.request.url);
        assertEquals("application/json; charset=utf-8", call.request.bytesContentType);
        assertNull("no multipart body", call.request.body);
        JSONObject body = new JSONObject(call.bodyText());
        List<String> keys = new ArrayList<>();
        for (Iterator<String> it = body.keys(); it.hasNext(); ) {
            keys.add(it.next());
        }
        assertEquals("only what is set, empty style left out", java.util.Arrays.asList("text", "language", "voice", "format",
                "speed", "pitch", "volume", "sampleRate", "emotion", "pinyin", "genre"), keys);
        assertEquals(req.getText(), body.getString("text"));
        assertEquals(55, body.get("speed"));
        assertEquals(0, body.get("pitch"));
        assertEquals("numbers are JSON numbers", Integer.class, body.get("sampleRate").getClass());
        Map<String, String> signed = new LinkedHashMap<>();
        signed.put("text", req.getText());
        signed.put("language", "zh-CN");
        signed.put("voice", "xiaoyan");
        signed.put("format", "mp3");
        signed.put("speed", "55");
        signed.put("pitch", "0");
        signed.put("volume", "100");
        signed.put("sampleRate", "16000");
        signed.put("emotion", "warm");
        signed.put("pinyin", "chuang2 qian2");
        signed.put("genre", "classical_poem");
        assertEquals("CONTRACT 2.3: the top-level scalar fields", Signer.sign(signed, "sk"), call.header("X-Signature"));
        assertEquals("ak", call.header("X-App-Key"));
        assertTrue(call.header("X-Timestamp").matches("\\d{10}"));
        assertTrue(call.header("X-Nonce").matches("[0-9a-f]{32}"));
        assertEquals("yugu-stkouyu-compat/2.2.0", call.header("User-Agent"));
        assertEquals("yugu-stkouyu-compat/2.2.0", call.header("X-Yugu-SDK"));
        assertEquals("application/json", call.header("Accept"));
        String key = call.header("Idempotency-Key");
        assertTrue(key, key.matches("[0-9a-f]{32}"));
        assertEquals(key, r.getIdempotencyKey());
        // the result
        assertEquals("/audio/tts_1.mp3", r.getAudioUrl());
        assertEquals("https://open.shengzhiai.com/tts/audio/tts_1.mp3", r.getAbsoluteUrl());
        assertEquals("1.348", r.getDuration());
        assertEquals(1.348, r.getDurationSeconds(), 1e-9);
        assertEquals("mp3", r.getFormat());
        assertEquals(1, r.getAttempts());
        assertFalse(r.isReplayed());
        assertEquals(OK, r.getRawJson());
        assertTrue(r.toString().contains("tts_1.mp3"));
        // a minimal request sends the text only; every call has its own key
        transport.respond(200, OK);
        offMain(() -> c.tts(new TtsRequest().setText("hi"), new TtsOptions()));
        FakeTransport.Call second = transport.calls().get(1);
        assertEquals("{\"text\":\"hi\"}", second.bodyText());
        Map<String, String> only = new HashMap<>();
        only.put("text", "hi");
        assertEquals(Signer.sign(only, "sk"), second.header("X-Signature"));
        assertFalse(key.equals(second.header("Idempotency-Key")));
    }

    @Test
    public void retriesReuseTheIdempotencyKeyAndTheGivenKeyIsUsed() throws Exception {
        transport.respond(503, FakeTransport.error(50200, "上游评测服务暂不可用"));
        transport.fail(new SocketTimeoutException("Read timed out"));
        transport.respond(200, OK, Collections.singletonMap("Idempotency-Replayed", "true"));
        TtsClient c = TtsClient.create("ak", "sk");
        TtsResult r = offMain(() -> c.tts(new TtsRequest("你好"), new TtsOptions().setIdempotencyKey("order-42")));
        assertEquals(3, r.getAttempts());
        assertTrue(r.isReplayed());
        assertEquals("order-42", r.getIdempotencyKey());
        Set<String> nonces = new HashSet<>();
        for (FakeTransport.Call call : transport.calls()) {
            assertEquals("order-42", call.header("Idempotency-Key"));
            nonces.add(call.header("X-Nonce"));
        }
        assertEquals(3, nonces.size());
        assertEquals(2, sleeps.size());
        assertEquals(2, linesStartingWith("2 retry ").size());
        for (String bad : new String[] {"", "has space", "中文", new String(new char[201]).replace('\0', 'a')}) {
            try {
                new TtsOptions().setIdempotencyKey(bad);
                fail(bad);
            } catch (IllegalArgumentException expected) {
                // ok
            }
        }
        assertNull(new TtsOptions().setIdempotencyKey(null).getIdempotencyKey());
        assertEquals("~", new TtsOptions().setIdempotencyKey("~").getIdempotencyKey());
    }

    @Test
    public void argumentsAndThreads() throws Exception {
        TtsClient c = TtsClient.create("ak", "sk");
        try {
            c.tts(new TtsRequest("你好"), null);
            fail();
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("主线程") && expected.getMessage().contains("ttsAsync"));
        }
        for (TtsRequest bad : new TtsRequest[] {null, new TtsRequest(), new TtsRequest(" \n")}) {
            try {
                offMain(() -> c.tts(bad, null));
                fail();
            } catch (IllegalArgumentException expected) {
                // ok
            }
            try {
                c.ttsAsync(bad, null, new Recorder());
                fail();
            } catch (IllegalArgumentException expected) {
                // ok
            }
        }
        try {
            c.ttsAsync(new TtsRequest("你好"), null, null);
            fail();
        } catch (IllegalArgumentException expected) {
            assertEquals("callback 不能为空", expected.getMessage());
        }
        assertEquals("nothing was sent", 0, transport.count());
    }

    /** Records the async outcome and the thread it arrived on. */
    static final class Recorder implements TtsClient.Callback {
        volatile TtsResult result;
        volatile YuguApiException error;
        volatile int calls;
        volatile boolean onMain = true;

        @Override
        public void onSuccess(TtsResult r) {
            onMain &= Main.onMain();
            calls++;
            result = r;
        }

        @Override
        public void onFailure(YuguApiException e) {
            onMain &= Main.onMain();
            calls++;
            error = e;
        }
    }

    @Test
    public void asyncDeliversOnTheMainThread() throws Exception {
        TtsClient c = TtsClient.create("ak", "sk");
        transport.respond(200, OK);
        Recorder ok = new Recorder();
        c.ttsAsync(new TtsRequest("你好"), null, ok);
        Main.await("onSuccess", 5000, () -> ok.calls > 0);
        assertTrue(ok.onMain);
        assertEquals("/audio/tts_1.mp3", ok.result.getAudioUrl());
        transport.respond(400, FakeTransport.error(40001, "text 长度不能超过1000个字符"));
        Recorder bad = new Recorder();
        c.ttsAsync(new TtsRequest("你好"), null, bad);
        Main.await("onFailure", 5000, () -> bad.calls > 0);
        assertTrue(bad.onMain);
        assertEquals(40001, bad.error.getCode());
        assertEquals(400, bad.error.getHttpStatus());
        assertEquals("text 长度不能超过1000个字符", bad.error.getMessage());
        Main.settle(30);
        assertEquals(1, ok.calls);
        assertEquals(1, bad.calls);
    }

    // ================================================================ errors

    @Test
    public void platformAndNetworkErrorsBecomeYuguApiException() throws Exception {
        TtsClient c = TtsClient.create("ak", "sk");
        transport.respond(401, FakeTransport.error(2003, "签名验证失败"));
        YuguApiException e = apiError(() -> c.tts(new TtsRequest("你好"), null));
        assertEquals(2003, e.getCode());
        assertEquals(401, e.getHttpStatus());
        assertEquals("签名验证失败", e.getMessage());
        assertTrue(e.toString().contains("code=2003"));
        assertEquals("not retried", 1, transport.count());

        transport.respond(200, FakeTransport.error(40300, "无权限"));
        e = apiError(() -> c.tts(new TtsRequest("你好"), null));
        assertEquals("a code in a 200 answer", 40300, e.getCode());
        assertEquals(200, e.getHttpStatus());

        for (int i = 0; i < 3; i++) {
            transport.fail(new ConnectException("Connection refused"));
        }
        e = apiError(() -> c.tts(new TtsRequest("你好"), null));
        assertEquals("network failure after the retries", 20009, e.getCode());
        assertEquals(0, e.getHttpStatus());
        assertTrue(e.getMessage(), e.getMessage().contains("NETWORK_ERROR") && e.getMessage().contains("(attempts 3)"));

        for (int i = 0; i < 3; i++) {
            transport.respond(429, FakeTransport.error(42900, "排队超时"));
        }
        e = apiError(() -> c.tts(new TtsRequest("你好"), null));
        assertEquals(20009, e.getCode());
        assertEquals(429, e.getHttpStatus());
        assertTrue(e.getMessage(), e.getMessage().contains("code=42900"));

        transport.respond(200, "<html>proxy</html>");
        e = apiError(() -> c.tts(new TtsRequest("你好"), null));
        assertEquals(90005, e.getCode());
        transport.respond(200, "{\"code\":0,\"message\":\"success\"}");
        e = apiError(() -> c.tts(new TtsRequest("你好"), null));
        assertEquals("no data object", 90005, e.getCode());
        transport.respond(404, "<html>not found</html>");
        e = apiError(() -> c.tts(new TtsRequest("你好"), null));
        assertEquals("HTTP status without code", 40400, e.getCode());
        transport.fail(new IOException("x", new java.security.cert.CertificateException("bad cert")));
        e = apiError(() -> c.tts(new TtsRequest("你好"), null));
        assertEquals(90011, e.getCode());
        // apps can build one for their own tests
        YuguApiException own = new YuguApiException(40001, 400, "评测记录不存在");
        assertEquals(40001, own.getCode());
        assertEquals(400, own.getHttpStatus());
        assertNull(own.getCause());
        assertTrue(own instanceof RuntimeException);
    }

    @Test
    public void urlsAreResolvedAgainstTheBase() throws Exception {
        TtsClient c = TtsClient.create("ak", "sk", "http://127.0.0.1:8080/");
        String[][] cases = {
            {"/audio/a.wav", "http://127.0.0.1:8080/tts/audio/a.wav"},
            {"https://cdn.example.com/a.mp3", "https://cdn.example.com/a.mp3"},
            {"HTTP://cdn.example.com/b.mp3", "HTTP://cdn.example.com/b.mp3"},
            {"/files/c.mp3", "http://127.0.0.1:8080/files/c.mp3"},
            {"d.mp3", "http://127.0.0.1:8080/d.mp3"},
        };
        for (String[] k : cases) {
            transport.respond(200, "{\"code\":0,\"data\":{\"audioUrl\":\"" + k[0] + "\",\"duration\":2.5}}");
            TtsResult r = offMain(() -> c.tts(new TtsRequest("hi"), null));
            assertEquals(k[0], r.getAudioUrl());
            assertEquals(k[1], r.getAbsoluteUrl());
            assertEquals("2.5", r.getDuration());
            assertEquals(2.5, r.getDurationSeconds(), 1e-9);
            assertNull(r.getFormat());
        }
        transport.respond(200, "{\"code\":0,\"data\":{\"duration\":\"n/a\"}}");
        TtsResult r = offMain(() -> c.tts(new TtsRequest("hi"), null));
        assertNull(r.getAudioUrl());
        assertNull(r.getAbsoluteUrl());
        assertEquals(-1, r.getDurationSeconds(), 1e-9);
        transport.respond(200, "{\"code\":0,\"data\":{}}");
        assertEquals(-1, offMain(() -> c.tts(new TtsRequest("hi"), null)).getDurationSeconds(), 1e-9);
    }

    // ================================================================ log

    @Test
    public void oneInfoLinePerCallWithoutSecretsOrText() throws Exception {
        TtsClient c = TtsClient.create("ak-tts-0001", "sk-tts-secret");
        transport.respond(200, OK);
        offMain(() -> c.tts(new TtsRequest("机密的课文"), null));
        transport.respond(400, FakeTransport.error(40001, "参数错误"));
        apiError(() -> c.tts(new TtsRequest("机密的课文"), null));
        List<String> info = linesStartingWith("3 tts ");
        assertEquals(String.valueOf(lines), 2, info.size());
        assertTrue(info.get(0), info.get(0).matches("3 tts status=ok attempts=1 ms=\\d+"));
        assertTrue(info.get(1), info.get(1).matches("3 tts status=error code=40001 http=400 attempts=1 ms=\\d+"));
        String signature = transport.calls().get(0).header("X-Signature");
        synchronized (lines) {
            for (String l : lines) {
                assertFalse(l, l.contains("sk-tts-secret") || l.contains("机密") || l.contains(signature)
                        || l.contains("ak-tts-0001"));
            }
        }
        assertEquals("DEBUG names the attempt", 2, linesStartingWith("4 POST https://open.shengzhiai.com/api/v1/tts/generate attempt 1/3 key=").size());
    }
}
