package com.shengzhiai.yugu.stcompat.internal;

import com.shengzhiai.yugu.stcompat.testing.FakeWs;
import com.shengzhiai.yugu.stcompat.testing.Fixtures;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.EOFException;
import java.io.IOException;
import java.net.ConnectException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** The realtime round against a scripted platform (DESIGN 14.3, BRIEF change 2026-10-12). Robolectric for org.json. */
@RunWith(RobolectricTestRunner.class)
public class RealtimeRoundTest {
    static final String TOKEN = "0123456789abcdef0123456789abcdef";
    static final String[] UNITS = "我是中国人我是中国人我们都是中国人中华民族是一家人".split("");
    static final String FINAL = Fixtures.text("spec/fixtures/platform/compat_sent.eval.cn.json").trim();

    private FakeWs ws;
    private final List<String> progress = Collections.synchronizedList(new ArrayList<String>());
    private final List<String> recordIds = Collections.synchronizedList(new ArrayList<String>());
    private final List<Long> sleeps = Collections.synchronizedList(new ArrayList<Long>());

    @Before
    public void setUp() {
        CompatConfig.reset();
        ws = new FakeWs();
        CompatConfig.setWsConnector(ws);
        CompatConfig.setRandom(new Random(7));
        CompatConfig.setSleeper((token, ms) -> {
            sleeps.add(ms);
            return !token.isCancelled();
        });
    }

    @After
    public void tearDown() {
        CompatConfig.reset();
    }

    private RealtimeRound.Config config() {
        RealtimeRound.Config c = new RealtimeRound.Config();
        c.baseUrl = "https://open.example.com/";
        c.coreType = "sent.eval.cn";
        c.appKey = "ak_test";
        c.secretKey = "test_secret_key_123";
        c.tokenId = TOKEN;
        c.fields = new LinkedHashMap<>();
        c.fields.put("refText", "我是中国人，我是中国人。我们都是中国人。中华民族是一家人。");
        c.fields.put("attachAudioUrl", "1");
        c.connectTimeoutMs = 3000;
        c.readTimeoutMs = 60000;
        c.totalTimeoutMs = 30000;
        return c;
    }

    private RealtimeRound round(RealtimeRound.Config c, CancelToken token) {
        return new RealtimeRound(c, (result, recordId) -> {
            progress.add(result);
            recordIds.add(recordId);
        }, token);
    }

    private RealtimeRound round() {
        return round(config(), new CancelToken());
    }

    static void await(String what, BooleanSupplier condition) {
        long end = System.currentTimeMillis() + 5000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > end) {
                fail("timed out waiting for " + what);
            }
            try {
                Thread.sleep(2);
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        }
    }

    /** A compat progress frame like the platform's: Shengtong-shaped words for units [from, read). */
    static String progressFrame(int from, int read, int bad) {
        StringBuilder words = new StringBuilder();
        for (int i = from; i < read; i++) {
            boolean ok = i != bad;
            int s = ok ? 100 : 0;
            if (words.length() > 0) {
                words.append(',');
            }
            words.append("{\"charType\":0,\"scores\":{\"pronunciation\":").append(s).append(",\"skipped\":").append(ok ? 0 : 1)
                    .append(",\"tone\":").append(s).append(",\"overall\":").append(s).append(",\"overall_pron\":").append(s)
                    .append("},\"word\":\"").append(UNITS[i]).append("\",\"word_parts\":[{\"beginIndex\":0,\"charType\":0,")
                    .append("\"endIndex\":0,\"part\":\"").append(UNITS[i]).append("\"}]}");
        }
        StringBuilder prog = new StringBuilder();
        int matched = 0;
        for (int i = 0; i < UNITS.length; i++) {
            boolean r = i < read;
            boolean m = r && i != bad;
            matched += m ? 1 : 0;
            if (i > 0) {
                prog.append(',');
            }
            prog.append("{\"word\":\"").append(UNITS[i]).append("\",\"read\":").append(r).append(",\"matched\":").append(m).append('}');
        }
        return "{\"eof\":0,\"recordId\":\"\",\"result\":{\"kernel_version\":\"1.0.0\",\"resource_version\":\"1.0.0\",\"words\":["
                + words + "],\"bytes\":" + (read * 8000) + ",\"readUnits\":" + read + ",\"matchedUnits\":" + matched
                + ",\"totalUnits\":" + UNITS.length + ",\"progress\":[" + prog + "]}}";
    }

    /** Words of the delivered progress, concatenated. */
    private String deliveredWords() throws Exception {
        StringBuilder sb = new StringBuilder();
        synchronized (progress) {
            for (String p : progress) {
                JSONArray w = new JSONObject(p).getJSONArray("words");
                assertTrue("delivered progress has words", w.length() > 0);
                for (int i = 0; i < w.length(); i++) {
                    sb.append(w.getJSONObject(i).getString("word"));
                }
            }
        }
        return sb.toString();
    }

    private static String units(int from, int to) {
        return String.join("", Arrays.copyOfRange(UNITS, from, to));
    }

    /** Answers end with optional progress frames, then the final body. */
    private static final class Platform implements FakeWs.Script {
        final AtomicInteger ends = new AtomicInteger();
        final List<String> beforeFinal = new ArrayList<>();
        volatile int failingEnds;
        volatile String finalBody = FINAL;
        volatile String[] endAnswers;

        @Override
        public void onText(FakeWs.Conn c, String text) {
            if (!FakeWs.END.equals(text)) {
                c.serverText("{\"event\":\"started\",\"coreType\":\"sent.eval.cn\"}");
                return;
            }
            int n = ends.incrementAndGet();
            if (endAnswers != null && n <= endAnswers.length) {
                String a = endAnswers[n - 1];
                if (a.startsWith("fail")) {
                    c.serverFail(new EOFException("connection reset after end"));
                } else if (a.startsWith("close:")) {
                    c.serverClose(Integer.parseInt(a.substring(6)), "bye");
                } else if (a.startsWith("silent")) {
                    return;
                } else {
                    c.serverText(a);
                }
                return;
            }
            if (n <= failingEnds) {
                c.serverFail(new EOFException("connection reset after end"));
                return;
            }
            for (String p : beforeFinal) {
                c.serverText(p);
            }
            c.serverText(finalBody);
        }

        @Override
        public void onBinary(FakeWs.Conn c, byte[] data) {
        }
    }

    // ================================================================ wire format

    @Test
    public void parameterFrameCarriesTheRestFieldsAsStrings() throws Exception {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("refText", "北京\"你好\"");
        f.put("paragraph_need_word_score", "1");
        f.put("scale", "100");
        f.put("empty", "");
        f.put("realtime_feedback", "0");
        f.put("idempotencyKey", "other");
        f.put("cmd", "end");
        f.put("end", "true");
        f.put("data", "AAAA");
        String frame = RealtimeRound.paramFrame(f, TOKEN);
        JSONObject j = new JSONObject(frame);
        assertEquals("北京\"你好\"", j.getString("refText"));
        assertEquals("1", j.get("paragraph_need_word_score"));
        assertEquals("100", j.get("scale"));
        assertEquals(Boolean.TRUE, j.get("realtime_feedback"));
        assertEquals(TOKEN, j.getString("idempotencyKey"));
        assertFalse(j.has("empty") || j.has("cmd") || j.has("end") || j.has("data"));
        assertEquals(5, j.length());
        assertEquals("{\"realtime_feedback\":true,\"idempotencyKey\":\"k\"}", RealtimeRound.paramFrame(null, "k"));
    }

    /** SDK 2.1.1: realtime_feedback only with progress, timing as the number 1 when asked for. */
    @Test
    public void parameterFrameWithoutProgressAndWithTiming() throws Exception {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("refText", "x");
        f.put("timing", "0");
        String plain = RealtimeRound.paramFrame(f, TOKEN, false, false);
        JSONObject p = new JSONObject(plain);
        assertFalse(p.has("realtime_feedback"));
        assertEquals("a caller's own field passes as before", "0", p.get("timing"));
        String timed = RealtimeRound.paramFrame(f, TOKEN, false, true);
        JSONObject t = new JSONObject(timed);
        assertEquals(1, t.get("timing"));
        assertEquals("one timing key", timed.indexOf("\"timing\""), timed.lastIndexOf("\"timing\""));
        assertFalse(t.has("realtime_feedback"));
        assertEquals(TOKEN, t.getString("idempotencyKey"));
        assertEquals("{\"realtime_feedback\":true,\"timing\":1,\"idempotencyKey\":\"k\"}",
                RealtimeRound.paramFrame(null, "k", true, true));
        assertEquals("{\"idempotencyKey\":\"k\"}", RealtimeRound.paramFrame(null, "k", false, false));
    }

    @Test
    public void withoutRealtimeFeedbackProgressIsNeitherAskedForNorDelivered() throws Exception {
        Platform platform = new Platform();
        platform.beforeFinal.add(progressFrame(0, 5, -1));
        ws.script(platform);
        RealtimeRound.Config cfg = config();
        cfg.realtimeFeedback = false;
        RealtimeRound r = round(cfg, new CancelToken());
        r.start();
        r.audio(new byte[6400], 0, 6400);
        await("connected", () -> ws.count() == 1 && ws.get(0).texts.size() == 1);
        ws.get(0).serverText(progressFrame(0, 3, -1));
        RealtimeRound.Outcome o = r.finish();
        assertEquals(RealtimeRound.Outcome.RESULT, o.kind);
        assertEquals(FINAL, o.body);
        assertTrue("no progress for the app: " + progress, progress.isEmpty());
        JSONObject param = new JSONObject(ws.get(0).paramFrame());
        assertFalse(param.has("realtime_feedback"));
        assertFalse(param.has("timing"));
        assertEquals("1", param.getString("attachAudioUrl"));
    }

    @Test
    public void phasesOfTheRoundAreMarked() throws Exception {
        final long wire = 1234;
        ws.script(new FakeWs.Script() {
            @Override
            public void onText(FakeWs.Conn c, String text) {
                if (FakeWs.END.equals(text)) {
                    // as if the final frame came compressed
                    c.nextWireBytes = wire;
                    c.serverText(FINAL);
                }
            }

            @Override
            public void onBinary(FakeWs.Conn c, byte[] data) {
            }
        });
        RealtimeRound.Config cfg = config();
        cfg.timing = true;
        cfg.phases = new EvalTiming(TOKEN);
        cfg.phases.streaming();
        RealtimeRound r = round(cfg, new CancelToken());
        r.start();
        await("connected", () -> ws.count() == 1 && ws.get(0).texts.size() == 1);
        assertEquals(1, new JSONObject(ws.get(0).paramFrame()).getInt("timing"));
        r.audio(new byte[64000], 0, 64000);
        cfg.phases.stop();
        RealtimeRound.Outcome o = r.finish();
        assertEquals(RealtimeRound.Outcome.RESULT, o.kind);
        cfg.phases.parsed();
        cfg.phases.delivered();
        String line = cfg.phases.summary();
        assertTrue(line, line.startsWith("timing tokenId=" + TOKEN + " mode=ws "));
        int bytes = FINAL.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        assertTrue(line, line.contains(" final=" + bytes + "B wire=" + wire + "B deflate"));
        assertTrue(line, line.matches(".* afterStop=\\d+ms upload=\\d+ms wait=\\d+ms download=\\d+ms parse=\\d+ms deliver=\\d+ms"
                + " handshake=\\d+ms queued=\\d+B .*"));
        assertFalse("one connection", line.contains("connections="));
    }

    @Test
    public void wsBaseFollowsTheRestBase() {
        assertEquals("wss://open.shengzhiai.com", RealtimeRound.wsBase("https://open.shengzhiai.com"));
        assertEquals("wss://open.shengzhiai.com", RealtimeRound.wsBase("HTTPS://open.shengzhiai.com/"));
        assertEquals("ws://127.0.0.1:8080/prefix", RealtimeRound.wsBase("http://127.0.0.1:8080/prefix//"));
        assertEquals("wss://h.example.com", RealtimeRound.wsBase("wss://h.example.com"));
        assertEquals("ws://h.example.com", RealtimeRound.wsBase("ws://h.example.com"));
        try {
            RealtimeRound.wsBase("ftp://x");
            fail();
        } catch (IllegalArgumentException expected) {
            // no scheme for the channel
        }
    }

    @Test
    public void handshakeUrlMatchesTheSharedVector() throws Exception {
        JSONObject vectors = new JSONObject(Fixtures.text("spec/fixtures/sign/vectors.json"));
        JSONObject c = null;
        JSONArray cases = vectors.getJSONArray("cases");
        for (int i = 0; i < cases.length(); i++) {
            if ("ws_handshake_query".equals(cases.getJSONObject(i).getString("name"))) {
                c = cases.getJSONObject(i);
            }
        }
        assertNotNull(c);
        JSONObject p = c.getJSONObject("params");
        Map<String, String> q = new LinkedHashMap<>();
        q.put("appKey", p.getString("appKey"));
        q.put("timestamp", p.getString("timestamp"));
        q.put("nonce", p.getString("nonce"));
        q.put("idempotencyKey", p.getString("idempotencyKey"));
        String url = RealtimeRound.handshakeUrl("https://open.shengzhiai.com", "sent.eval.cn", q, c.getString("secret"));
        assertEquals("wss://open.shengzhiai.com/sent.eval.cn?appKey=ak_test&timestamp=1791447600&nonce=0123456789abcdef"
                + "&idempotencyKey=3f0b6a1c9d2e4f5a8b7c6d5e4f3a2b1c&signature="
                + RealtimeRound.percentEncode(c.getString("signature")), url);
        assertTrue(url.endsWith("&signature=%2FnQKY6YLDY1o9QsZphE5etDBKxhUMyIjgqFEOlOFx8w%3D"));
        // every other query parameter is signed, an old signature is replaced
        q.put("mockFault", "progress-off");
        q.put("signature", "stale");
        String withExtra = RealtimeRound.handshakeUrl("http://127.0.0.1:1", "sent.eval.cn", q, "s");
        Map<String, String> decoded = new LinkedHashMap<>();
        for (String pair : withExtra.substring(withExtra.indexOf('?') + 1).split("&")) {
            String[] kv = pair.split("=", 2);
            decoded.put(java.net.URLDecoder.decode(kv[0], "UTF-8"), java.net.URLDecoder.decode(kv[1], "UTF-8"));
        }
        String sig = decoded.remove("signature");
        assertEquals(Signer.sign(decoded, "s"), sig);
        assertTrue(withExtra.startsWith("ws://127.0.0.1:1/sent.eval.cn?"));
    }

    @Test
    public void percentEncodingKeepsOnlyUnreservedCharacters() {
        assertEquals("aZ09-._~", RealtimeRound.percentEncode("aZ09-._~"));
        assertEquals("%2B%2F%3D%20%26%3F%25", RealtimeRound.percentEncode("+/= &?%"));
        assertEquals("%E4%B8%AD", RealtimeRound.percentEncode("中"));
    }

    @Test
    public void framesAreClassified() {
        RealtimeRound.Frame p = RealtimeRound.Frame.parse(progressFrame(2, 5, -1));
        assertEquals(RealtimeRound.Frame.PROGRESS, p.kind);
        assertEquals(3, p.words.size());
        assertEquals(5, p.readUnits);
        assertEquals("", p.recordId);
        RealtimeRound.Frame bytesOnly = RealtimeRound.Frame.parse("{\"eof\":0,\"result\":{\"bytes\":16000}}");
        assertEquals(RealtimeRound.Frame.PROGRESS, bytesOnly.kind);
        assertTrue(bytesOnly.words.isEmpty());
        assertEquals(-1, bytesOnly.readUnits);
        RealtimeRound.Frame fin = RealtimeRound.Frame.parse(FINAL);
        assertEquals(RealtimeRound.Frame.FINAL, fin.kind);
        assertFalse(fin.replayed);
        assertTrue(RealtimeRound.Frame.parse("{\"recordId\":\"r\",\"eof\":1,\"result\":{},\"replayed\":true}").replayed);
        RealtimeRound.Frame err = RealtimeRound.Frame.parse("{\"event\":\"error\",\"code\":50200,\"message\":\"upstream\"}");
        assertEquals(RealtimeRound.Frame.ERROR, err.kind);
        assertEquals(50200, err.code);
        assertEquals("upstream", err.message);
        RealtimeRound.Frame bare = RealtimeRound.Frame.parse("{\"event\":\"error\"}");
        assertEquals(0, bare.code);
        assertNull(bare.message);
        assertEquals(RealtimeRound.Frame.EVENT, RealtimeRound.Frame.parse("{\"event\":\"started\",\"coreType\":\"x\"}").kind);
        assertEquals(RealtimeRound.Frame.OTHER, RealtimeRound.Frame.parse("not json").kind);
        assertEquals(RealtimeRound.Frame.OTHER, RealtimeRound.Frame.parse("{\"eof\":0,\"result\":[1]}").kind);
        assertEquals(RealtimeRound.Frame.OTHER, RealtimeRound.Frame.parse("{\"eof\":1,\"result\":null}").kind);
        assertEquals(RealtimeRound.Frame.OTHER, RealtimeRound.Frame.parse("{\"hello\":1}").kind);
        RealtimeRound.Frame odd = RealtimeRound.Frame.parse("{\"eof\":0,\"recordId\":7,\"result\":{\"words\":{\"a\":1}}}");
        assertEquals(RealtimeRound.Frame.PROGRESS, odd.kind);
        assertTrue(odd.words.isEmpty());
        assertEquals("", odd.recordId);
    }

    @Test
    public void wordsAreTrimmedInPlace() throws Exception {
        String frame = progressFrame(2, 5, -1);
        RealtimeRound.Frame f = RealtimeRound.Frame.parse(frame);
        String full = new JSONObject(frame).getJSONObject("result").toString();
        assertEquals(new JSONObject(full).toString(), new JSONObject(f.resultWithWordsFrom(0)).toString());
        JSONObject trimmed = new JSONObject(f.resultWithWordsFrom(2));
        assertEquals(1, trimmed.getJSONArray("words").length());
        assertEquals("人", trimmed.getJSONArray("words").getJSONObject(0).getString("word"));
        assertEquals(5, trimmed.getInt("readUnits"));
        assertEquals(25, trimmed.getJSONArray("progress").length());
        JSONArray two = new JSONObject(f.resultWithWordsFrom(1)).getJSONArray("words");
        assertEquals(2, two.length());
        assertEquals("国", two.getJSONObject(0).getString("word"));
        assertEquals("人", two.getJSONObject(1).getString("word"));
        assertEquals(0, new JSONObject(f.resultWithWordsFrom(3)).getJSONArray("words").length());
    }

    // ================================================================ streaming and result

    @Test
    public void streamsParametersAudioAndEndThenReturnsTheFinalBody() throws Exception {
        Platform platform = new Platform();
        ws.script(platform);
        RealtimeRound r = round();
        byte[] big = new byte[70000];
        new Random(3).nextBytes(big);
        r.audio(big, 0, 1000);
        r.start();
        r.audio(big, 1000, 69000);
        r.audio(big, 0, 0);
        r.audio(null, 0, 10);
        await("audio sent", () -> ws.count() == 1 && ws.get(0).binarySizes.size() == 4);
        RealtimeRound.Outcome o = r.finish();
        assertEquals(RealtimeRound.Outcome.RESULT, o.kind);
        assertEquals(FINAL, o.body);
        assertEquals(1, o.connections);
        assertFalse(o.replayed);
        FakeWs.Conn c = ws.get(0);
        assertEquals("wss://open.example.com/sent.eval.cn", c.path());
        Map<String, String> q = c.query();
        assertEquals(Arrays.asList("appKey", "timestamp", "nonce", "idempotencyKey", "signature"), new ArrayList<>(q.keySet()));
        assertEquals("ak_test", q.get("appKey"));
        assertEquals(TOKEN, q.get("idempotencyKey"));
        assertTrue(Math.abs(System.currentTimeMillis() / 1000L - Long.parseLong(q.get("timestamp"))) < 10);
        assertTrue(q.get("nonce").matches("[0-9a-f]{16}"));
        String sig = q.remove("signature");
        assertEquals(Signer.sign(q, "test_secret_key_123"), sig);
        assertEquals(CompatConfig.USER_AGENT, c.headers.get("User-Agent"));
        assertEquals(CompatConfig.USER_AGENT, c.headers.get("X-Yugu-SDK"));
        assertEquals(3000, c.connectTimeoutMs);
        assertEquals(60000, c.readTimeoutMs);
        assertEquals(Arrays.asList(1000, 32000, 32000, 5000), c.binarySizes);
        assertArrayEquals(big, c.audio());
        JSONObject param = new JSONObject(c.paramFrame());
        assertEquals("1", param.getString("attachAudioUrl"));
        assertTrue(param.getBoolean("realtime_feedback"));
        assertEquals(2, c.texts.size());
        assertEquals(FakeWs.END, c.text(1));
        assertEquals("the final frame may be large", RealtimeRound.FINAL_MAX_MESSAGE_BYTES, c.maxMessageBytes);
        await("graceful close", () -> c.closed);
        assertEquals(1000, c.closeCode);
        assertEquals(RealtimeRound.Outcome.RESULT, r.finish().kind);
        r.cancel();
        r.cancel();
    }

    @Test
    public void progressIsDeliveredOncePerUnitAndBytesOnlyFramesAreNot() throws Exception {
        Platform platform = new Platform();
        platform.beforeFinal.add(progressFrame(20, 25, -1));
        platform.beforeFinal.add("{\"eof\":0,\"result\":{\"bytes\":200000}}");
        ws.script(platform);
        RealtimeRound r = round();
        r.start();
        await("connected", () -> ws.count() == 1 && ws.get(0).texts.size() == 1);
        FakeWs.Conn c = ws.get(0);
        c.serverText("{\"eof\":0,\"result\":{\"bytes\":16000}}");
        c.serverText(progressFrame(0, 0, -1));
        c.serverText(progressFrame(0, 2, -1));
        c.serverText(progressFrame(2, 2, -1));
        c.serverText(progressFrame(2, 10, 7));
        c.serverText("garbage");
        c.serverText("{\"event\":\"pong\",\"ts\":1}");
        c.serverBinary(new byte[] {1});
        c.serverText(progressFrame(10, 20, -1));
        assertEquals(3, progress.size());
        RealtimeRound.Outcome o = r.finish();
        assertEquals(RealtimeRound.Outcome.RESULT, o.kind);
        assertEquals(4, progress.size());
        assertEquals(String.join("", UNITS), deliveredWords());
        assertEquals(Arrays.asList("", "", "", ""), recordIds);
        JSONObject third = new JSONObject(progress.get(1));
        assertEquals(10, third.getInt("readUnits"));
        assertEquals(9, third.getInt("matchedUnits"));
        JSONObject unmatched = third.getJSONArray("words").getJSONObject(5);
        assertEquals("中", unmatched.getString("word"));
        assertEquals(1, unmatched.getJSONObject("scores").getInt("skipped"));
        assertFalse(third.getJSONArray("progress").getJSONObject(7).getBoolean("matched"));
        // after the final nothing is delivered
        c.serverText(progressFrame(0, 25, -1));
        assertEquals(4, progress.size());
    }

    @Test
    public void finalFrameBeforeEndIsIgnored() throws Exception {
        Platform platform = new Platform();
        ws.script(platform);
        RealtimeRound r = round();
        r.start();
        await("connected", () -> ws.count() == 1 && ws.get(0).texts.size() == 1);
        ws.get(0).serverText("{\"recordId\":\"early\",\"eof\":1,\"result\":{\"overall\":1}}");
        RealtimeRound.Outcome o = r.finish();
        assertEquals(RealtimeRound.Outcome.RESULT, o.kind);
        assertEquals(FINAL, o.body);
    }

    @Test
    public void protocolPingWhenIdle() throws Exception {
        RealtimeRound.Config cfg = config();
        cfg.readTimeoutMs = 2000;
        RealtimeRound r = round(cfg, new CancelToken());
        assertEquals(1000, r.pingIntervalMs());
        RealtimeRound.Config slow = config();
        slow.readTimeoutMs = 600000;
        assertEquals(RealtimeRound.PING_INTERVAL_MS, round(slow, new CancelToken()).pingIntervalMs());
        ws.script(new Platform());
        r.start();
        await("idle ping", () -> ws.count() == 1 && ws.get(0).pings.get() >= 1);
        assertEquals(RealtimeRound.Outcome.RESULT, r.finish().kind);
    }

    // ================================================================ failure before end

    @Test
    public void refusedConnectionFallsBackToRest() {
        ws.refuseNext(new ConnectException("refused"));
        RealtimeRound r = round();
        r.start();
        r.audio(new byte[3200], 0, 3200);
        RealtimeRound.Outcome o = r.finish();
        assertEquals(RealtimeRound.Outcome.FALLBACK, o.kind);
        assertTrue(o.reason, o.reason.contains("refused"));
        r.audio(new byte[10], 0, 10);
        assertEquals(RealtimeRound.Outcome.FALLBACK, r.finish().kind);
        assertEquals(1, ws.count());
    }

    @Test
    public void handshakeRefusalFallsBackToo() {
        ws.refuseNext(new WsClient.HandshakeException(503, 1000));
        RealtimeRound r = round();
        r.start();
        assertEquals(RealtimeRound.Outcome.FALLBACK, r.finish().kind);
    }

    @Test
    public void errorFrameBeforeEndFallsBackAndClosesTheSocket() {
        RealtimeRound r = round();
        r.start();
        await("connected", () -> ws.count() == 1 && ws.get(0).texts.size() == 1);
        FakeWs.Conn c = ws.get(0);
        c.serverText("{\"event\":\"error\",\"message\":\"audio exceeds 10MB; send parameters to retry\"}");
        RealtimeRound.Outcome o = r.finish();
        assertEquals(RealtimeRound.Outcome.FALLBACK, o.kind);
        assertTrue(o.reason.contains("10MB"));
        await("closed", () -> c.closed);
        assertFalse("no end on a failed channel", c.endReceived());
        // late frames of the dropped connection change nothing
        c.serverText(progressFrame(0, 5, -1));
        assertTrue(progress.isEmpty());
    }

    @Test
    public void transportFailureOrServerCloseBeforeEndFallsBack() {
        RealtimeRound r = round();
        r.start();
        await("connected", () -> ws.count() == 1 && ws.get(0).texts.size() == 1);
        ws.get(0).serverFail(new java.net.SocketException("reset"));
        assertEquals(RealtimeRound.Outcome.FALLBACK, r.finish().kind);
        RealtimeRound r2 = round();
        r2.start();
        await("connected", () -> ws.count() == 2 && ws.get(1).texts.size() == 1);
        ws.get(1).serverClose(1011, "server error");
        RealtimeRound.Outcome o = r2.finish();
        assertEquals(RealtimeRound.Outcome.FALLBACK, o.kind);
        assertTrue(o.reason.contains("1011"));
    }

    @Test
    public void sendFailureBeforeEndFallsBack() {
        RealtimeRound r = round();
        r.start();
        await("connected", () -> ws.count() == 1 && ws.get(0).texts.size() == 1);
        ws.get(0).failSends = new java.net.SocketException("broken pipe");
        r.audio(new byte[640], 0, 640);
        RealtimeRound.Outcome o = r.finish();
        assertEquals(RealtimeRound.Outcome.FALLBACK, o.kind);
        assertTrue(o.reason.contains("broken pipe"));
    }

    @Test
    public void moreThanTenMegabytesFallsBack() {
        RealtimeRound r = round();
        byte[] chunk = new byte[1024 * 1024];
        for (int i = 0; i < 10; i++) {
            r.audio(chunk, 0, chunk.length);
        }
        r.audio(chunk, 0, 1);
        RealtimeRound.Outcome o = r.finish();
        assertEquals(RealtimeRound.Outcome.FALLBACK, o.kind);
        assertTrue(o.reason.contains("bytes of audio"));
        assertEquals("never started, never connected", 0, ws.count());
    }

    @Test
    public void finishWithoutStartFallsBack() {
        RealtimeRound r = round();
        RealtimeRound.Outcome o = r.finish();
        assertEquals(RealtimeRound.Outcome.FALLBACK, o.kind);
        assertEquals(0, o.connections);
        r.start();
        assertEquals(0, ws.count());
    }

    // ================================================================ failure after end

    @Test
    public void failureAfterEndReplaysEverythingOnANewConnection() throws Exception {
        Platform platform = new Platform();
        platform.failingEnds = 1;
        ws.script(new FakeWs.Script() {
            @Override
            public void onText(FakeWs.Conn c, String text) {
                if (FakeWs.END.equals(text) && ws.count() == 2) {
                    // the new session restarts progress from unit 0 while the audio is replayed
                    c.serverText(progressFrame(0, 4, -1));
                    c.serverText(progressFrame(4, 6, -1));
                    c.serverText(progressFrame(6, 7, -1));
                }
                platform.onText(c, text);
            }

            @Override
            public void onBinary(FakeWs.Conn c, byte[] data) {
            }
        });
        RealtimeRound r = round();
        r.start();
        byte[] pcm = new byte[45000];
        new Random(5).nextBytes(pcm);
        r.audio(pcm, 0, pcm.length);
        r.audio(pcm, 0, 640);
        await("audio", () -> ws.count() == 1 && ws.get(0).binarySizes.size() == 3);
        ws.get(0).serverText(progressFrame(0, 3, -1));
        ws.get(0).serverText(progressFrame(3, 5, -1));
        RealtimeRound.Outcome o = r.finish();
        assertEquals(RealtimeRound.Outcome.RESULT, o.kind);
        assertEquals(2, o.connections);
        assertEquals(2, ws.count());
        FakeWs.Conn first = ws.get(0);
        FakeWs.Conn second = ws.get(1);
        await("the failed connection is dropped", () -> first.aborted);
        assertEquals(first.paramFrame(), second.paramFrame());
        assertArrayEquals(first.audio(), second.audio());
        assertEquals(first.binarySizes, second.binarySizes);
        assertTrue(second.endReceived());
        assertEquals(TOKEN, second.query().get("idempotencyKey"));
        assertFalse("fresh nonce per handshake", first.query().get("nonce").equals(second.query().get("nonce")));
        assertEquals(RealtimeRound.FINAL_MAX_MESSAGE_BYTES, second.maxMessageBytes);
        assertEquals(1, sleeps.size());
        // units 0 to 4 were delivered before the reconnect; 5 and 6 come from the replay, nothing twice
        assertEquals(units(0, 7), deliveredWords());
        assertEquals(4, progress.size());
        // the replayed frame for units 4 and 5 is trimmed to unit 5
        JSONObject trimmed = new JSONObject(progress.get(2));
        assertEquals(1, trimmed.getJSONArray("words").length());
        assertEquals(UNITS[5], trimmed.getJSONArray("words").getJSONObject(0).getString("word"));
        assertEquals(6, trimmed.getInt("readUnits"));
    }

    @Test
    public void reconnectsAreLimitedThenRetryableFailuresAre20009() {
        Platform platform = new Platform();
        platform.failingEnds = 10;
        ws.script(platform);
        RealtimeRound r = round();
        r.start();
        r.audio(new byte[32000], 0, 32000);
        RealtimeRound.Outcome o = r.finish();
        assertEquals(RealtimeRound.Outcome.ERROR, o.kind);
        assertEquals(1 + RealtimeRound.MAX_RECONNECTS, o.connections);
        assertEquals(3, ws.count());
        assertEquals(2, sleeps.size());
        assertTrue(o.error.retryable);
        assertEquals(CompatErrIds.RETRYABLE, ErrorMapper.errId(o.error));
        assertEquals(3, o.error.getAttempts());
        assertTrue(ErrorMapper.message(o.error, 20009).contains("(attempts 3)"));
        // autoRetry: the same round on a new connection, with a fresh reconnect budget
        platform.failingEnds = 0;
        RealtimeRound.Outcome again = r.resubmit();
        assertEquals(RealtimeRound.Outcome.RESULT, again.kind);
        assertEquals(4, ws.count());
        assertArrayEquals(ws.get(0).audio(), ws.get(3).audio());
        assertEquals(RealtimeRound.Outcome.RESULT, r.resubmit().kind);
        assertEquals("a result is not resubmitted", 4, ws.count());
    }

    @Test
    public void errorFramesAfterEnd() {
        Platform platform = new Platform();
        platform.endAnswers = new String[] {"{\"event\":\"error\",\"code\":50200,\"message\":\"upstream busy\"}"};
        ws.script(platform);
        RealtimeRound r = round();
        r.start();
        r.audio(new byte[640], 0, 640);
        RealtimeRound.Outcome o = r.finish();
        assertEquals("a retryable error frame reconnects", RealtimeRound.Outcome.RESULT, o.kind);
        assertEquals(2, o.connections);

        Platform p2 = new Platform();
        p2.endAnswers = new String[] {"{\"event\":\"error\",\"code\":40300,\"message\":\"refText rejected\"}"};
        ws.script(p2);
        RealtimeRound r2 = round();
        r2.start();
        RealtimeRound.Outcome o2 = r2.finish();
        assertEquals(RealtimeRound.Outcome.ERROR, o2.kind);
        assertEquals(40300, ErrorMapper.errId(o2.error));
        assertEquals("refText rejected", ErrorMapper.message(o2.error, 40300));
        assertEquals(1, o2.connections);

        Platform p3 = new Platform();
        p3.endAnswers = new String[] {"{\"event\":\"error\",\"message\":\"NullPointerException\"}"};
        ws.script(p3);
        RealtimeRound r3 = round();
        r3.start();
        RealtimeRound.Outcome o3 = r3.finish();
        assertEquals(RealtimeRound.Outcome.ERROR, o3.kind);
        assertEquals(ErrorTable.INTERNAL_ERROR, ErrorMapper.errId(o3.error));
        assertEquals(1, o3.connections);
    }

    @Test
    public void closeCodesAfterEnd() {
        Platform platform = new Platform();
        platform.endAnswers = new String[] {"close:1008"};
        ws.script(platform);
        RealtimeRound r = round();
        r.start();
        RealtimeRound.Outcome o = r.finish();
        assertEquals(RealtimeRound.Outcome.ERROR, o.kind);
        assertEquals(ErrorTable.PROTOCOL_ERROR, ErrorMapper.errId(o.error));
        assertEquals(1, o.connections);

        Platform p2 = new Platform();
        p2.endAnswers = new String[] {"close:1011", "close:1012"};
        ws.script(p2);
        RealtimeRound r2 = round();
        r2.start();
        RealtimeRound.Outcome o2 = r2.finish();
        assertEquals(RealtimeRound.Outcome.RESULT, o2.kind);
        assertEquals(3, o2.connections);
    }

    @Test
    public void reconnectHandshakeOutcomes() {
        Platform platform = new Platform();
        platform.failingEnds = 1;
        ws.script(platform);
        RealtimeRound r = round();
        r.start();
        await("connected", () -> ws.count() == 1);
        ws.refuseNext(new WsClient.HandshakeException(403, -1));
        RealtimeRound.Outcome o = r.finish();
        assertEquals(RealtimeRound.Outcome.ERROR, o.kind);
        assertEquals("a refused handshake is not retried", 40300, ErrorMapper.errId(o.error));
        assertEquals(2, ws.count());

        Platform p2 = new Platform();
        p2.failingEnds = 1;
        ws.script(p2);
        RealtimeRound r2 = round();
        r2.start();
        await("connected", () -> ws.count() == 3);
        ws.refuseNext(new WsClient.HandshakeException(503, 1500));
        RealtimeRound.Outcome o2 = r2.finish();
        assertEquals(RealtimeRound.Outcome.RESULT, o2.kind);
        assertEquals(5, ws.count());
        assertTrue("Retry-After is honoured: " + sleeps, sleeps.get(sleeps.size() - 1) >= 1500);
    }

    @Test
    public void noResultBeforeTheDeadline() {
        Platform platform = new Platform();
        platform.endAnswers = new String[] {"silent", "silent", "silent"};
        ws.script(platform);
        RealtimeRound.Config cfg = config();
        cfg.totalTimeoutMs = 300;
        RealtimeRound r = round(cfg, new CancelToken());
        r.start();
        long t0 = System.nanoTime();
        RealtimeRound.Outcome o = r.finish();
        long ms = (System.nanoTime() - t0) / 1000000L;
        assertEquals(RealtimeRound.Outcome.ERROR, o.kind);
        assertEquals(ErrorTable.TIMEOUT, o.error.code);
        assertEquals(CompatErrIds.RETRYABLE, ErrorMapper.errId(o.error));
        assertTrue("bounded by the deadline: " + ms, ms < 3000);
    }

    @Test
    public void sendFailureOfEndIsAFailureAfterEnd() {
        Platform platform = new Platform();
        ws.script(new FakeWs.Script() {
            @Override
            public void onText(FakeWs.Conn c, String text) {
                platform.onText(c, text);
            }

            @Override
            public void onBinary(FakeWs.Conn c, byte[] data) {
                if (ws.count() == 1) {
                    c.failSends = new java.net.SocketException("reset while sending end");
                }
            }
        });
        RealtimeRound r = round();
        r.start();
        r.audio(new byte[640], 0, 640);
        await("audio", () -> ws.count() == 1 && ws.get(0).binarySizes.size() == 1);
        RealtimeRound.Outcome o = r.finish();
        assertEquals(RealtimeRound.Outcome.RESULT, o.kind);
        assertEquals("after end the round never falls back to REST", 2, o.connections);
    }

    // ================================================================ cancel

    @Test
    public void cancelWhileStreamingClosesWithoutEnd() {
        RealtimeRound r = round();
        r.start();
        r.audio(new byte[640], 0, 640);
        await("connected", () -> ws.count() == 1 && ws.get(0).binarySizes.size() == 1);
        r.cancel();
        await("aborted", () -> ws.get(0).aborted);
        assertFalse(ws.get(0).endReceived());
        assertEquals(RealtimeRound.Outcome.CANCELLED, r.finish().kind);
        r.audio(new byte[640], 0, 640);
        ws.get(0).serverText(progressFrame(0, 5, -1));
        assertTrue(progress.isEmpty());
    }

    @Test
    public void cancelTokenCancelsTheRound() {
        CancelToken token = new CancelToken();
        Platform platform = new Platform();
        platform.endAnswers = new String[] {"silent"};
        ws.script(platform);
        RealtimeRound r = round(config(), token);
        r.start();
        await("connected", () -> ws.count() == 1);
        Thread canceller = new Thread(() -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException ignored) {
                // test
            }
            token.cancel();
        });
        canceller.start();
        RealtimeRound.Outcome o = r.finish();
        assertEquals(RealtimeRound.Outcome.CANCELLED, o.kind);
        await("aborted", () -> ws.get(0).aborted);
        CancelToken cancelled = new CancelToken();
        cancelled.cancel();
        RealtimeRound late = round(config(), cancelled);
        late.start();
        assertEquals(RealtimeRound.Outcome.CANCELLED, late.finish().kind);
        assertEquals(1, ws.count());
    }

    @Test
    public void cancelDuringReconnectBackOff() {
        CancelToken token = new CancelToken();
        Platform platform = new Platform();
        platform.failingEnds = 1;
        ws.script(platform);
        CompatConfig.setSleeper((t, ms) -> {
            t.cancel();
            return false;
        });
        RealtimeRound r = round(config(), token);
        r.start();
        RealtimeRound.Outcome o = r.finish();
        assertEquals(RealtimeRound.Outcome.CANCELLED, o.kind);
        assertEquals(1, ws.count());
    }
}
