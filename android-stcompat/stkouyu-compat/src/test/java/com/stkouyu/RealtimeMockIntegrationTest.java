package com.stkouyu;

import com.shengzhiai.yugu.stcompat.internal.CompatConfig;
import com.shengzhiai.yugu.stcompat.internal.CompatEngine;
import com.shengzhiai.yugu.stcompat.testing.CompatTestBase;
import com.shengzhiai.yugu.stcompat.testing.Events;
import com.shengzhiai.yugu.stcompat.testing.FakeAudio;
import com.shengzhiai.yugu.stcompat.testing.Fixtures;
import com.shengzhiai.yugu.stcompat.testing.Main;
import com.shengzhiai.yugu.stcompat.testing.MockServer;
import com.shengzhiai.yugu.stcompat.testing.ScriptedPlatform;
import com.stkouyu.setting.EngineSetting;
import com.stkouyu.setting.RecordSetting;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * realtime_feedback over the real socket client against tools/mock-server (DESIGN 14.3): progress
 * frames, REST fallback before end (ws-refuse, ws-kill-after), reconnect after end with the same
 * idempotency key and one billed evaluation, error frames, progress-mismatch and progress-off.
 */
@RunWith(RobolectricTestRunner.class)
public class RealtimeMockIntegrationTest extends CompatTestBase {
    static final String CUSTOMER = "我是中国人，我是中国人。我们都是中国人。中华民族是一家人。";
    static final String[] UNITS = ScriptedPlatform.chineseUnits(CUSTOMER);
    /** PCM bytes of zh_progress.wav (its WAV header is 78 bytes). */
    static final int PCM_BYTES = Fixtures.pcm("spec/fixtures/audio/zh_progress.wav").length;
    /** 18 units by the mock's 8000 bytes each, one more after end. */
    static final int READ_AT_END = PCM_BYTES / 8000 + 1;

    private static MockServer server;
    private final Events ev = new Events();

    @BeforeClass
    public static void startServer() throws Exception {
        server = MockServer.start();
    }

    @AfterClass
    public static void stopServer() {
        if (server != null) {
            server.close();
        }
    }

    @Before
    public void realNetwork() throws Exception {
        CompatConfig.setTransport(null);
        CompatConfig.setWsConnector(null);
        CompatConfig.setSleeper(null);
        server.reset();
        YuguCompat.setBaseUrl(server.baseUrl());
    }

    private SkEgnManager init() {
        SkEgnManager m = SkEgnManager.getInstance(app);
        m.initEngine(MockServer.APP_KEY, MockServer.SECRET, "it-user", EngineSetting.getInstance(app));
        Main.idle();
        return m;
    }

    private static RecordSetting realtime() {
        RecordSetting s = new RecordSetting(CoreType.CN_SENT_EVAL, CUSTOMER);
        s.setRealtime_feedback(1);
        s.setIsStream(true);
        return s;
    }

    /** Streams zh_progress.wav through feed() in chunks of {@code chunk} bytes, stops, waits for the final. */
    private JSONObject stream(SkEgnManager m, RecordSetting s, Events e, int chunk) {
        m.startRecord(s, e.recorder);
        byte[] pcm = Fixtures.pcm("spec/fixtures/audio/zh_progress.wav");
        for (int i = 0; i < pcm.length; i += chunk) {
            int n = Math.min(chunk, pcm.length - i);
            byte[] b = new byte[n];
            System.arraycopy(pcm, i, b, 0, n);
            m.feed(b, n);
        }
        m.stopRecord();
        Main.await("final onScore", 30000, () -> e.json != null);
        Main.settle(50);
        assertTrue("callbacks off the main thread: " + e.offMain, e.offMain.isEmpty());
        assertEquals("exactly one final", 1, e.count("onScore"));
        assertEquals("the final is the last callback", "onScore", e.log.get(e.log.size() - 1));
        return e.result();
    }

    private static String words(List<JSONObject> mid) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (JSONObject o : mid) {
            assertEquals(0, o.getInt("eof"));
            assertEquals("", o.getString("recordId"));
            JSONArray w = o.getJSONObject("result").getJSONArray("words");
            assertTrue(w.length() > 0);
            for (int i = 0; i < w.length(); i++) {
                sb.append(w.getJSONObject(i).getString("word"));
            }
        }
        return sb.toString();
    }

    private static String units(int n) {
        return String.join("", Arrays.copyOf(UNITS, n));
    }

    private static List<JSONObject> entries(String method) throws Exception {
        JSONArray log = server.log();
        List<JSONObject> out = new ArrayList<>();
        for (int i = 0; i < log.length(); i++) {
            if (method.equals(log.getJSONObject(i).getString("method"))) {
                out.add(log.getJSONObject(i));
            }
        }
        return out;
    }

    private static double fixtureOverall() throws Exception {
        return new JSONObject(Fixtures.text("spec/fixtures/platform/compat_sent.eval.cn.json")).getJSONObject("result")
                .getDouble("overall");
    }

    @Test
    public void progressThenOneFinalOverTheRealtimeChannel() throws Exception {
        SkEgnManager m = init();
        RecordSetting s = realtime();
        s.setNeedAttachAudioUrlInResult(true);
        JSONObject fin = stream(m, s, ev, 3200);
        String token = fin.getString("tokenId");
        List<JSONObject> mid = ev.intermediate();
        assertEquals("9 progress frames while streaming, 1 after end", 10, mid.size());
        assertEquals(units(READ_AT_END), words(mid));
        for (JSONObject o : mid) {
            assertEquals(token, o.getString("tokenId"));
            assertEquals(25, o.getJSONObject("result").getJSONArray("progress").length());
        }
        assertEquals(1, fin.getInt("eof"));
        assertEquals(fixtureOverall(), fin.getJSONObject("result").getDouble("overall"), 1e-9);
        assertTrue("audioUrl of the realtime final, as on REST", fin.getString("audioUrl").startsWith("http://127.0.0.1:"));
        List<JSONObject> ws = entries("WS");
        assertEquals(1, ws.size());
        assertEquals("/sent.eval.cn", ws.get(0).getString("path"));
        assertEquals(token, ws.get(0).getString("idempotencyKey"));
        assertTrue(entries("POST").isEmpty());
        JSONObject billing = server.billing();
        assertEquals(1, billing.getInt("billed"));
        assertEquals("ws-compat", billing.getJSONArray("records").getJSONObject(0).getString("op"));
        assertEquals(PCM_BYTES, billing.getJSONArray("records").getJSONObject(0).getInt("bytes"));
    }

    /**
     * SDK 2.1.1 default: without realtime_feedback the recording still streams over the real socket
     * client, without progress; the mock agrees no extension, so the final frame comes uncompressed.
     */
    @Test
    public void streamingByDefaultWithoutRealtimeFeedback() throws Exception {
        YuguCompat.setLogLevel(YuguCompat.LOG_INFO);
        final List<String> lines = Collections.synchronizedList(new ArrayList<String>());
        com.shengzhiai.yugu.stcompat.internal.YLog.setSink((level, tag, msg, err) -> lines.add(msg));
        SkEgnManager m = init();
        RecordSetting s = new RecordSetting(CoreType.CN_SENT_EVAL, CUSTOMER);
        s.setIsStream(true);
        JSONObject fin = stream(m, s, ev, 3200);
        String token = fin.getString("tokenId");
        assertTrue(ev.intermediate().isEmpty());
        assertEquals(fixtureOverall(), fin.getJSONObject("result").getDouble("overall"), 1e-9);
        List<JSONObject> ws = entries("WS");
        assertEquals(1, ws.size());
        assertEquals(token, ws.get(0).getString("idempotencyKey"));
        assertTrue(entries("POST").isEmpty());
        JSONObject billing = server.billing();
        assertEquals(1, billing.getInt("billed"));
        assertEquals("ws-compat", billing.getJSONArray("records").getJSONObject(0).getString("op"));
        assertEquals(PCM_BYTES, billing.getJSONArray("records").getJSONObject(0).getInt("bytes"));
        String summary = null;
        synchronized (lines) {
            for (String l : lines) {
                if (l.startsWith("timing tokenId=" + token + " ")) {
                    summary = l;
                }
            }
        }
        assertTrue(String.valueOf(lines), summary != null && summary.contains(" mode=ws "));
        assertTrue("2.2.0: the platform's recordId follows the tokenId: " + summary,
                summary.startsWith("timing tokenId=" + token + " recordId=" + fin.getString("recordId") + " mode=ws "));
        assertTrue(fin.getString("recordId").startsWith("eval_"));
        assertFalse("the mock agrees no permessage-deflate: " + summary, summary.contains("deflate"));
        Matcher sizes = Pattern.compile(".* final=(\\d+)B wire=(\\d+)B.*").matcher(summary);
        assertTrue(summary, sizes.matches());
        assertEquals(sizes.group(1), sizes.group(2));
    }

    @Test
    public void refusedHandshakeFallsBackToRestWithTheSameKey() throws Exception {
        server.fault("/sent.eval.cn", "ws-refuse");
        SkEgnManager m = init();
        JSONObject fin = stream(m, realtime(), ev, 3200);
        String token = fin.getString("tokenId");
        assertTrue(ev.intermediate().isEmpty());
        assertEquals(fixtureOverall(), fin.getJSONObject("result").getDouble("overall"), 1e-9);
        List<JSONObject> ws = entries("WS");
        assertEquals(1, ws.size());
        assertEquals(503, ws.get(0).getInt("rejected"));
        List<JSONObject> post = entries("POST");
        assertEquals(1, post.size());
        assertEquals(token, post.get(0).getString("idempotencyKey"));
        assertFalse(post.get(0).getJSONObject("fields").has("realtime_feedback"));
        JSONObject billing = server.billing();
        assertEquals(1, billing.getInt("billed"));
        assertEquals("compat", billing.getJSONArray("records").getJSONObject(0).getString("op"));
    }

    @Test
    public void socketKilledBeforeEndFallsBackToRest() throws Exception {
        // parameter frame and the first audio frame pass, the second audio frame kills the socket
        server.fault("/sent.eval.cn", "ws-kill-after:3");
        SkEgnManager m = init();
        RecordSetting s = realtime();
        m.startRecord(s, ev.recorder);
        byte[] pcm = Fixtures.pcm("spec/fixtures/audio/zh_progress.wav");
        m.feed(Arrays.copyOfRange(pcm, 0, 3200));
        m.feed(Arrays.copyOfRange(pcm, 3200, 6400));
        // the drop is seen while streaming, long before end
        Main.settle(500);
        m.feed(Arrays.copyOfRange(pcm, 6400, pcm.length));
        m.stopRecord();
        Main.await("final onScore", 30000, () -> ev.json != null);
        JSONObject fin = ev.result();
        assertEquals(1, ev.scores.size());
        String token = fin.getString("tokenId");
        assertTrue(fin.has("result"));
        assertEquals(1, entries("WS").size());
        List<JSONObject> post = entries("POST");
        assertEquals(1, post.size());
        assertEquals(token, post.get(0).getString("idempotencyKey"));
        assertEquals(1, server.billing().getInt("billed"));
    }

    @Test
    public void socketKilledAtEndReconnectsWithTheSameKeyAndBillsOnce() throws Exception {
        // 32000 byte chunks: parameter frame, 5 audio frames, end; the 7th frame kills the socket
        server.fault("/sent.eval.cn", "ws-kill-after:7");
        SkEgnManager m = init();
        JSONObject fin = stream(m, realtime(), ev, 32000);
        String token = fin.getString("tokenId");
        assertTrue(fin.toString(), fin.has("result"));
        List<JSONObject> ws = entries("WS");
        assertEquals(2, ws.size());
        assertEquals(token, ws.get(0).getString("idempotencyKey"));
        assertEquals(token, ws.get(1).getString("idempotencyKey"));
        assertEquals("ws-kill-after:7", ws.get(0).getString("fault"));
        assertTrue(entries("POST").isEmpty());
        JSONObject billing = server.billing();
        assertEquals(1, billing.getInt("billed"));
        assertEquals(1, billing.getJSONObject("byKey").getInt(token));
        assertEquals("replayed units are not delivered twice", units(READ_AT_END), words(ev.intermediate()));
    }

    @Test
    public void reconnectsExhaustedAre20009() throws Exception {
        for (int i = 0; i < 3; i++) {
            server.fault("/sent.eval.cn", "ws-kill-after:7");
        }
        SkEgnManager m = init();
        JSONObject fin = stream(m, realtime(), ev, 32000);
        assertEquals(20009, fin.getInt("errId"));
        assertEquals(1, fin.getInt("eof"));
        assertEquals(3, entries("WS").size());
        assertTrue(entries("POST").isEmpty());
        assertEquals(0, server.billing().getInt("billed"));
    }

    @Test
    public void errorFramesAfterEnd() throws Exception {
        server.fault("/sent.eval.cn", "ws-error:50200");
        SkEgnManager m = init();
        JSONObject fin = stream(m, realtime(), ev, 32000);
        assertTrue("a retryable error frame reconnects", fin.has("result"));
        assertEquals(2, entries("WS").size());
        assertEquals(1, server.billing().getInt("billed"));

        server.reset();
        server.fault("/sent.eval.cn", "ws-error:40001");
        Events e2 = new Events();
        JSONObject err = stream(m, realtime(), e2, 32000);
        assertEquals(40001, err.getInt("errId"));
        assertEquals(1, entries("WS").size());
        assertTrue(entries("POST").isEmpty());
    }

    @Test
    public void progressMismatchAndProgressOff() throws Exception {
        server.fault("/sent.eval.cn", "progress-mismatch:1");
        SkEgnManager m = init();
        stream(m, realtime(), ev, 16000);
        JSONObject first = ev.intermediate().get(0).getJSONObject("result");
        JSONObject w = first.getJSONArray("words").getJSONObject(1);
        assertEquals("是", w.getString("word"));
        assertEquals(1, w.getJSONObject("scores").getInt("skipped"));
        JSONObject p = first.getJSONArray("progress").getJSONObject(1);
        assertTrue(p.getBoolean("read"));
        assertFalse(p.getBoolean("matched"));
        assertEquals(first.getInt("readUnits") - 1, first.getInt("matchedUnits"));

        server.reset();
        server.fault("/sent.eval.cn", "progress-off");
        Events e2 = new Events();
        JSONObject fin = stream(m, realtime(), e2, 16000);
        assertTrue(fin.has("result"));
        assertEquals("frames with bytes only are not delivered", Collections.emptyList(), e2.intermediate());
        assertEquals(1, e2.scores.size());
    }

    @Test
    public void cancelDuringStreamingEvaluatesNothing() throws Exception {
        SkEgnManager m = init();
        m.startRecord(realtime(), ev.recorder);
        byte[] pcm = Fixtures.pcm("spec/fixtures/audio/zh_progress.wav");
        m.feed(Arrays.copyOf(pcm, 64000));
        Main.await("progress", 10000, () -> !ev.intermediate().isEmpty());
        m.cancel();
        Main.settle(300);
        assertFalse(ev.has("onScore"));
        assertEquals(SkEgnManager.engine_status.IDLE, m.getEngineStatus());
        assertEquals(0, server.billing().getInt("billed"));
        assertTrue(entries("POST").isEmpty());
    }

    @Test
    public void microphoneRecordingOverTheRealtimeChannel() throws Exception {
        FakeAudio audio = new FakeAudio(Fixtures.pcm("spec/fixtures/audio/zh_progress.wav")).endWithNothing();
        CompatConfig.setAudioInputFactory(audio);
        SkEgnManager m = init();
        RecordSetting s = new RecordSetting(CoreType.CN_SENT_EVAL, CUSTOMER);
        s.setRealtime_feedback(1);
        m.startRecord(s, ev.recorder);
        String token = CompatEngine.get(app).currentTokenId();
        Main.await("progress while recording", 10000, () -> ev.intermediate().size() >= 9);
        m.stopRecord();
        Main.await("final", 30000, () -> ev.json != null);
        assertEquals(token, ev.result().getString("tokenId"));
        assertEquals(units(READ_AT_END), words(ev.intermediate()));
        assertEquals(1, server.billing().getInt("billed"));
        assertTrue(ev.offMain.isEmpty());
    }

    @Test
    public void skegnEmulationAgainstTheMock() throws Exception {
        YuguCompat.setBaseUrl(null);
        long e = SkEgn.skegn_new("{\"appKey\":\"mock-app-key\",\"secretKey\":\"mock-secret-key\",\"cloud\":{\"server\":\""
                + server.baseUrl() + "\"}}", app);
        final List<String> got = Collections.synchronizedList(new ArrayList<String>());
        byte[] id = new byte[64];
        SkEgn.skegn_start(e, "{\"app\":{\"userId\":\"u\"},\"audio\":{\"audioType\":\"wav\",\"sampleRate\":16000,\"channel\":1,"
                + "\"sampleBytes\":2},\"request\":{\"coreType\":\"sent.eval.cn\",\"refText\":\"" + CUSTOMER
                + "\",\"realtime_feedback\":1}}", id, (tid, type, data, size) -> {
                    got.add(new String(data, 0, size, StandardCharsets.UTF_8));
                    return 0;
                }, app);
        byte[] wav = Fixtures.bytes("spec/fixtures/audio/zh_progress.wav");
        for (int i = 0; i < wav.length; i += 6400) {
            int n = Math.min(6400, wav.length - i);
            SkEgn.skegn_feed(e, Arrays.copyOfRange(wav, i, i + n), n);
        }
        SkEgn.skegn_stop(e);
        Main.await("final", 30000, () -> {
            synchronized (got) {
                return !got.isEmpty() && Events.isFinal(got.get(got.size() - 1));
            }
        });
        String token = new String(id, StandardCharsets.US_ASCII).trim();
        StringBuilder words = new StringBuilder();
        for (int i = 0; i < got.size() - 1; i++) {
            JSONObject o = new JSONObject(got.get(i));
            assertEquals(0, o.getInt("eof"));
            assertEquals(token, o.getString("tokenId"));
            JSONArray w = o.getJSONObject("result").getJSONArray("words");
            for (int j = 0; j < w.length(); j++) {
                words.append(w.getJSONObject(j).getString("word"));
            }
        }
        assertTrue("the WAV file is streamed as fed, header included", words.length() >= 18);
        assertEquals(units(words.length()), words.toString());
        JSONObject fin = new JSONObject(got.get(got.size() - 1));
        assertEquals(fixtureOverall(), fin.getJSONObject("result").getDouble("overall"), 1e-9);
        List<JSONObject> ws = entries("WS");
        assertEquals(1, ws.size());
        assertEquals(token, ws.get(0).getString("idempotencyKey"));
        assertEquals(1, server.billing().getInt("billed"));
        SkEgn.skegn_delete(e);
    }
}
