package com.stkouyu;

import com.shengzhiai.yugu.stcompat.internal.CompatConfig;
import com.shengzhiai.yugu.stcompat.internal.CompatEngine;
import com.shengzhiai.yugu.stcompat.internal.Envelope;
import com.shengzhiai.yugu.stcompat.internal.RawJson;
import com.shengzhiai.yugu.stcompat.testing.CompatTestBase;
import com.shengzhiai.yugu.stcompat.testing.Events;
import com.shengzhiai.yugu.stcompat.testing.FakeAudio;
import com.shengzhiai.yugu.stcompat.testing.FakeTransport;
import com.shengzhiai.yugu.stcompat.testing.FakeWs;
import com.shengzhiai.yugu.stcompat.testing.Fixtures;
import com.shengzhiai.yugu.stcompat.testing.Main;
import com.shengzhiai.yugu.stcompat.testing.ScriptedPlatform;
import com.stkouyu.setting.RecordSetting;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * realtime_feedback of SkEgnManager and SkEgn on a scripted realtime channel (DESIGN 14.3, BRIEF
 * change 2026-10-12): eof 0 intermediate results with the newly read words, then exactly one final
 * result built like the REST one, all on the main thread; REST fallback before end; reconnect and
 * autoRetry after end.
 */
@RunWith(RobolectricTestRunner.class)
public class RealtimeFeedbackTest extends CompatTestBase {
    static final String CUSTOMER = "我是中国人，我是中国人。我们都是中国人。中华民族是一家人。";
    static final String[] UNITS = ScriptedPlatform.chineseUnits(CUSTOMER);
    /** PCM bytes of zh_progress.wav (its WAV header is 78 bytes). */
    static final int PCM_BYTES = Fixtures.pcm("spec/fixtures/audio/zh_progress.wav").length;
    static final String[] ENVELOPE_KEYS = {"tokenId", "recordId", "applicationId", "userId", "refText", "eof", "dtLastResponse", "result"};

    private final Events ev = new Events();
    private FakeWs ws;
    private ScriptedPlatform platform;

    @Before
    public void realtimeChannel() {
        ws = new FakeWs();
        platform = new ScriptedPlatform(UNITS);
        ws.script(platform);
        CompatConfig.setWsConnector(ws);
    }

    private static RecordSetting realtime(boolean stream) {
        RecordSetting s = new RecordSetting(CoreType.CN_SENT_EVAL, CUSTOMER);
        s.setRealtime_feedback(1);
        s.setIsStream(stream);
        return s;
    }

    private static byte[] progressPcm() {
        return Fixtures.pcm("spec/fixtures/audio/zh_progress.wav");
    }

    private static void feedInChunks(SkEgnManager m, byte[] pcm, int chunk) {
        for (int i = 0; i < pcm.length; i += chunk) {
            int n = Math.min(chunk, pcm.length - i);
            byte[] b = new byte[n];
            System.arraycopy(pcm, i, b, 0, n);
            m.feed(b, n);
        }
    }

    private void awaitFinal() {
        Main.await("final onScore", 10000, () -> ev.json != null);
    }

    private static String concatWords(List<JSONObject> intermediate) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (JSONObject o : intermediate) {
            JSONArray w = o.getJSONObject("result").getJSONArray("words");
            assertTrue("eof 0 only with newly read words", w.length() > 0);
            for (int i = 0; i < w.length(); i++) {
                sb.append(w.getJSONObject(i).getString("word"));
            }
        }
        return sb.toString();
    }

    private static void assertKeyOrder(JSONObject o) {
        Iterator<String> keys = o.keys();
        for (String k : ENVELOPE_KEYS) {
            assertEquals(k, keys.next());
        }
    }

    // ================================================================ SkEgnManager

    @Test
    public void intermediateResultsThenExactlyOneFinalOnTheMainThread() throws Exception {
        SkEgnManager m = initManager(null);
        RecordSetting s = realtime(true);
        s.setNeedRequestParamsInResult(true);
        m.startRecord(s, ev.recorder);
        String token = CompatEngine.get(app).currentTokenId();
        feedInChunks(m, progressPcm(), 3200);
        Main.await("intermediate results while reading", 10000, () -> ev.intermediate().size() >= 3);
        assertEquals(SkEgnManager.engine_status.RECORDING, m.getEngineStatus());
        m.stopRecord();
        awaitFinal();
        Main.settle(50);
        assertTrue("callbacks off the main thread: " + ev.offMain, ev.offMain.isEmpty());
        // order: onStart, eof 0 ..., onRecordEnd, eof 0 (the last recognition after end), final
        assertEquals("onStart", ev.log.get(0));
        assertEquals("onScore", ev.log.get(ev.log.size() - 1));
        assertEquals(1, ev.count("onScore"));
        assertEquals(1, ev.count("onRecordEnd"));
        int recordEnd = ev.log.indexOf("onRecordEnd");
        assertTrue(ev.log.subList(1, recordEnd).contains("onScore(eof 0)"));
        assertEquals("onScore(eof 0)", ev.log.get(ev.log.size() - 2));
        List<JSONObject> mid = ev.intermediate();
        assertEquals("every progress frame had new words", platform.sentProgress.size(), mid.size());
        int read = PCM_BYTES / 8000 + 1;
        assertEquals(String.join("", java.util.Arrays.copyOf(UNITS, read)), concatWords(mid));
        for (JSONObject o : mid) {
            assertKeyOrder(o);
            assertEquals(token, o.getString("tokenId"));
            assertEquals("", o.getString("recordId"));
            assertEquals("test-app-key", o.getString("applicationId"));
            assertEquals("user-1", o.getString("userId"));
            assertEquals(CUSTOMER, o.getString("refText"));
            assertEquals(0, o.getInt("eof"));
            JSONObject r = o.getJSONObject("result");
            assertEquals(25, r.getInt("totalUnits"));
            assertEquals(25, r.getJSONArray("progress").length());
            assertEquals("1.0.0", r.getString("kernel_version"));
            assertFalse("no params or audioUrl before the final", o.has("params") || o.has("audioUrl"));
        }
        JSONObject fin = ev.result();
        assertKeyOrder(fin);
        assertEquals(token, fin.getString("tokenId"));
        assertEquals(1, fin.getInt("eof"));
        assertTrue(fin.getString("recordId").startsWith("eval_"));
        assertEquals(94.6, fin.getJSONObject("result").getDouble("overall"), 1e-9);
        assertTrue("params as on the REST path", fin.has("params"));
        assertEquals(0, transport.count());
        FakeWs.Conn c = ws.get(0);
        assertEquals(token, c.query().get("idempotencyKey"));
        assertEquals("wss://open.shengzhiai.com/sent.eval.cn", c.path());
        JSONObject param = new JSONObject(c.paramFrame());
        assertEquals(CUSTOMER, param.getString("refText"));
        assertEquals(token, param.getString("idempotencyKey"));
        assertTrue(param.getBoolean("realtime_feedback"));
        assertTrue(c.endReceived());
        assertTrue(java.util.Arrays.equals(progressPcm(), c.audio()));
        assertEquals(PCM_BYTES, c.audio().length);
        for (int size : c.binarySizes) {
            assertTrue(size <= 32000);
        }
        // the recording is kept like on the REST path
        assertEquals(PCM_BYTES + 44, new java.io.File(m.getLastRecordPath()).length());
        assertEquals(SkEgnManager.engine_status.STOP, m.getEngineStatus());
    }

    @Test
    public void recordedPlatformFramesBecomeIntermediateResults() throws Exception {
        String[][] cases = {
            {"ws_compat_sent_eval_cn_progress_frames.json", CoreType.CN_SENT_EVAL, CUSTOMER},
            {"ws_compat_sent_eval_cn_progress_misread_frames.json", CoreType.CN_SENT_EVAL, CUSTOMER},
            {"ws_compat_sent_eval_progress_frames.json", CoreType.EN_SENT_EVAL,
                "Nice to see you again. I am a student, and I like reading books every day."},
        };
        SkEgnManager m = initManager(null);
        for (String[] c : cases) {
            JSONArray frames = new JSONArray(Fixtures.text("spec/fixtures/platform/" + c[0]));
            List<String> progressFrames = new ArrayList<>();
            List<JSONObject> withWords = new ArrayList<>();
            String finalFrame = null;
            for (int i = 0; i < frames.length(); i++) {
                JSONObject f = frames.getJSONObject(i).getJSONObject("frame");
                if (f.has("event")) {
                    continue;
                }
                if (f.optInt("eof", 1) == 0) {
                    progressFrames.add(f.toString());
                    if (f.getJSONObject("result").getJSONArray("words").length() > 0) {
                        withWords.add(f);
                    }
                } else {
                    finalFrame = f.toString();
                }
            }
            final String body = finalFrame;
            ws.script(new FakeWs.Script() {
                @Override
                public void onText(FakeWs.Conn conn, String text) {
                    if (FakeWs.END.equals(text)) {
                        conn.serverText(body);
                    }
                }

                @Override
                public void onBinary(FakeWs.Conn conn, byte[] data) {
                }
            });
            Events e = new Events();
            RecordSetting s = new RecordSetting(c[1], c[2]);
            s.setRealtime_feedback(1);
            s.setIsStream(true);
            int before = ws.count();
            m.startRecord(s, e.recorder);
            m.feed(Fixtures.tone(1500, 6000));
            Main.await("connected", 5000, () -> ws.count() == before + 1 && ws.last().paramFrame() != null);
            for (String f : progressFrames) {
                ws.last().serverText(f);
            }
            m.stopRecord();
            Main.await("final of " + c[0], 10000, () -> e.json != null);
            List<JSONObject> mid = e.intermediate();
            assertEquals(c[0], withWords.size(), mid.size());
            StringBuilder words = new StringBuilder();
            for (int i = 0; i < mid.size(); i++) {
                JSONObject delivered = mid.get(i);
                assertEquals("", delivered.getString("recordId"));
                assertEquals(c[0] + " result passed through", withWords.get(i).getJSONObject("result").toString(),
                        delivered.getJSONObject("result").toString());
                JSONArray w = delivered.getJSONObject("result").getJSONArray("words");
                for (int j = 0; j < w.length(); j++) {
                    words.append(w.getJSONObject(j).getString("word")).append(' ');
                }
            }
            JSONObject last = withWords.get(withWords.size() - 1).getJSONObject("result");
            JSONArray prog = last.getJSONArray("progress");
            StringBuilder expected = new StringBuilder();
            for (int i = 0; i < last.getInt("readUnits"); i++) {
                expected.append(prog.getJSONObject(i).getString("word")).append(' ');
            }
            assertEquals(c[0], expected.toString(), words.toString());
            JSONObject fin = e.result();
            assertEquals(1, fin.getInt("eof"));
            JSONObject platformResult = new JSONObject(RawJson.members(body).get("result"));
            assertEquals(platformResult.getDouble("overall"), fin.getJSONObject("result").getDouble("overall"), 1e-9);
            assertEquals(new JSONObject(body).getString("recordId"), fin.getString("recordId"));
            // exactly the envelope of the REST path for the same body
            JSONObject rest = new JSONObject(Envelope.success(fin.getString("tokenId"), "test-app-key", "user-1", c[2], body,
                    null, 0));
            rest.remove("dtLastResponse");
            fin.remove("dtLastResponse");
            assertEquals(rest.toString(), fin.toString());
        }
        assertEquals(0, transport.count());
    }

    @Test
    public void misreadUnitsAreFlaggedInWordsAndProgress() throws Exception {
        platform.mismatch = 1;
        SkEgnManager m = initManager(null);
        m.startRecord(realtime(true), ev.recorder);
        feedInChunks(m, progressPcm(), 16000);
        m.stopRecord();
        awaitFinal();
        JSONObject first = ev.intermediate().get(0).getJSONObject("result");
        JSONObject w1 = first.getJSONArray("words").getJSONObject(1);
        assertEquals("是", w1.getString("word"));
        assertEquals(1, w1.getJSONObject("scores").getInt("skipped"));
        assertEquals(0, w1.getJSONObject("scores").getInt("overall"));
        JSONObject p1 = first.getJSONArray("progress").getJSONObject(1);
        assertTrue(p1.getBoolean("read"));
        assertFalse(p1.getBoolean("matched"));
        assertEquals(first.getInt("readUnits") - 1, first.getInt("matchedUnits"));
    }

    @Test
    public void framesWithoutProgressAreNotDelivered() throws Exception {
        platform.progressOff = true;
        SkEgnManager m = initManager(null);
        m.startRecord(realtime(true), ev.recorder);
        feedInChunks(m, progressPcm(), 16000);
        m.stopRecord();
        awaitFinal();
        Main.settle(50);
        assertEquals(Collections.emptyList(), ev.intermediate());
        assertEquals(1, ev.scores.size());
    }

    @Test
    public void refusedChannelFallsBackToRestWithTheSameTokenId() throws Exception {
        CompatConfig.setWsConnector(FakeWs.REFUSING);
        SkEgnManager m = initManager(null);
        m.startRecord(realtime(true), ev.recorder);
        String token = CompatEngine.get(app).currentTokenId();
        m.feed(Fixtures.bytes("spec/fixtures/audio/zh_short.wav"));
        m.stopRecord();
        awaitFinal();
        assertEquals(1, ev.scores.size());
        assertEquals(token, ev.result().getString("tokenId"));
        assertEquals(1, transport.count());
        FakeTransport.Call call = transport.calls().get(0);
        assertEquals(token, call.header("Idempotency-Key"));
        assertFalse(call.bodyText().contains("realtime_feedback"));
        assertEquals("https://open.shengzhiai.com/sent.eval.cn", call.request.url);
    }

    @Test
    public void channelLostBeforeEndFinishesOverRest() throws Exception {
        SkEgnManager m = initManager(null);
        m.startRecord(realtime(true), ev.recorder);
        feedInChunks(m, progressPcm(), 32000);
        Main.await("progress", 5000, () -> ev.intermediate().size() >= 2);
        ws.get(0).serverFail(new java.net.SocketException("Connection reset"));
        m.stopRecord();
        awaitFinal();
        assertEquals(1, transport.count());
        assertEquals(ev.result().getString("tokenId"), transport.calls().get(0).header("Idempotency-Key"));
        assertFalse(ws.get(0).endReceived());
        assertEquals(1, ev.count("onScore"));
    }

    @Test
    public void lostAfterEndReconnectsAndKeepsEachWordOnce() throws Exception {
        platform.failingEnds = 1;
        SkEgnManager m = initManager(null);
        m.startRecord(realtime(true), ev.recorder);
        feedInChunks(m, progressPcm(), 6400);
        m.stopRecord();
        awaitFinal();
        assertEquals(2, ws.count());
        assertEquals(ws.get(0).query().get("idempotencyKey"), ws.get(1).query().get("idempotencyKey"));
        assertTrue(java.util.Arrays.equals(ws.get(0).audio(), ws.get(1).audio()));
        int read = PCM_BYTES / 8000 + 1;
        assertEquals("replayed units are not delivered twice", String.join("", java.util.Arrays.copyOf(UNITS, read)),
                concatWords(ev.intermediate()));
        assertEquals(1, ev.count("onScore"));
        assertEquals(0, transport.count());
    }

    @Test
    public void exhaustedReconnectsAre20009AndAutoRetryResubmits() throws Exception {
        platform.failingEnds = 3;
        SkEgnManager m = initManager(null);
        m.startRecord(realtime(true), ev.recorder);
        m.feed(Fixtures.bytes("spec/fixtures/audio/zh_short.wav"));
        m.stopRecord();
        awaitFinal();
        assertEquals(20009, ev.result().getInt("errId"));
        assertEquals(1, ev.result().getInt("eof"));
        assertTrue(ev.result().getString("error").contains("(attempts 3)"));
        assertEquals(3, ws.count());

        Events e2 = new Events();
        platform.failingEnds = platform.ends.get() + 3;
        RecordSetting s = realtime(true);
        s.setAutoRetry(true);
        m.startRecord(s, e2.recorder);
        m.feed(Fixtures.bytes("spec/fixtures/audio/zh_short.wav"));
        m.stopRecord();
        Main.await("final", 10000, () -> e2.json != null);
        assertTrue(e2.json, e2.result().has("result"));
        assertEquals("3 failed connections, then the autoRetry submission", 7, ws.count());
        String key = ws.get(3).query().get("idempotencyKey");
        for (int i = 4; i < 7; i++) {
            assertEquals(key, ws.get(i).query().get("idempotencyKey"));
        }
        assertEquals(0, transport.count());
    }

    @Test
    public void shortAudioIsRejectedLocallyWithoutEnd() throws Exception {
        SkEgnManager m = initManager(null);
        m.startRecord(realtime(true), ev.recorder);
        m.feed(Fixtures.tone(500, 6000));
        Main.await("connected", 5000, () -> ws.count() == 1 && ws.get(0).paramFrame() != null);
        m.stopRecord();
        awaitFinal();
        assertEquals(60005, ev.result().getInt("errId"));
        Main.await("channel dropped", 5000, () -> ws.get(0).aborted);
        assertFalse(ws.get(0).endReceived());
        assertEquals(0, transport.count());
    }

    @Test
    public void onRecordListenerGetsTheFinalResultOnly() throws Exception {
        SkEgnManager m = initManager(null);
        RecordSetting s = realtime(true);
        m.startRecord(s, ev.record);
        feedInChunks(m, progressPcm(), 16000);
        m.stopRecord();
        Main.await("final", 10000, () -> ev.json != null);
        Main.settle(50);
        assertFalse(platform.sentProgress.isEmpty());
        assertEquals(1, ev.scores.size());
        assertEquals(1, ev.count("onRecordEnd(json)"));
        assertTrue(ev.result().has("result"));
    }

    @Test
    public void cancelClosesTheChannelWithoutCallbacks() throws Exception {
        SkEgnManager m = initManager(null);
        m.startRecord(realtime(true), ev.recorder);
        feedInChunks(m, progressPcm(), 32000);
        Main.await("connected", 5000, () -> ws.count() == 1 && !ws.get(0).binarySizes.isEmpty());
        m.cancle();
        Main.await("aborted", 5000, () -> ws.get(0).aborted);
        int before = ev.scores.size();
        ws.get(0).serverText(ScriptedPlatform.progressFrame(UNITS, 0, 25, -1, 999999));
        Main.settle(100);
        assertEquals(before, ev.scores.size());
        assertFalse(ev.has("onScore"));
        assertFalse(ws.get(0).endReceived());
        assertEquals(SkEgnManager.engine_status.IDLE, m.getEngineStatus());
        // the next question works
        Events e2 = new Events();
        m.startRecord(realtime(true), e2.recorder);
        m.feed(Fixtures.bytes("spec/fixtures/audio/zh_short.wav"));
        m.stopRecord();
        Main.await("final", 10000, () -> e2.json != null);
        assertTrue(e2.result().has("result"));
    }

    @Test
    public void recycleReleasesTheChannel() {
        SkEgnManager m = initManager(null);
        m.startRecord(realtime(true), ev.recorder);
        m.feed(Fixtures.tone(500, 6000));
        Main.await("connected", 5000, () -> ws.count() == 1 && ws.get(0).paramFrame() != null);
        m.recycle();
        Main.await("aborted", 5000, () -> ws.get(0).aborted);
        Main.settle(50);
        assertNull(ev.json);
    }

    @Test
    public void microphoneRecordingStreamsAndEndsAtTheDuration() throws Exception {
        FakeAudio audio = new FakeAudio(Fixtures.tone(1200, 6000)).endWithNothing();
        CompatConfig.setAudioInputFactory(audio);
        SkEgnManager m = initManager(null);
        RecordSetting s = realtime(false);
        s.setDuration(1000);
        m.startRecord(s, ev.recorder);
        Main.await("audio streamed", 5000, () -> ws.count() == 1 && ws.get(0).audio().length == 38400);
        Main.await("progress", 5000, () -> ev.intermediate().size() >= 2);
        Main.idleFor(1000);
        awaitFinal();
        assertTrue(ws.get(0).endReceived());
        assertTrue(ev.result().has("result"));
        assertTrue(ev.log.indexOf("onRecordEnd") < ev.log.lastIndexOf("onScore"));
        assertTrue(ev.offMain.isEmpty());
    }

    @Test
    public void existsAudioTransStaysOnRest() throws Exception {
        java.io.File f = Fixtures.file("spec/fixtures/audio/zh_short.wav");
        SkEgnManager m = initManager(null);
        RecordSetting s = new RecordSetting(CoreType.CN_SENT_EVAL, "今天天气很好");
        s.setRealtime_feedback(1);
        s.setAudioPath(f.getAbsolutePath());
        m.existsAudioTrans(s, ev.recorder);
        awaitFinal();
        assertEquals(0, ws.count());
        assertEquals(1, transport.count());
    }

    /**
     * 2.1.1: other values of realtime_feedback stream as well, without the realtime_feedback key and
     * without intermediate results, even when the platform sends progress frames; with streaming
     * upload switched off they go over REST after stop as in 2.1.0.
     */
    @Test
    public void otherValuesOfRealtimeFeedbackStreamWithoutProgress() throws Exception {
        SkEgnManager m = initManager(null);
        RecordSetting s = new RecordSetting(CoreType.CN_SENT_EVAL, CUSTOMER);
        s.setRealtime_feedback(0);
        s.setIsStream(true);
        m.startRecord(s, ev.recorder);
        feedInChunks(m, progressPcm(), 16000);
        m.stopRecord();
        awaitFinal();
        Main.settle(50);
        assertEquals(1, ws.count());
        assertEquals(0, transport.count());
        assertFalse(new JSONObject(ws.get(0).paramFrame()).has("realtime_feedback"));
        assertFalse("the scripted platform sent progress frames", platform.sentProgress.isEmpty());
        assertEquals(Collections.emptyList(), ev.intermediate());
        assertEquals(1, ev.scores.size());

        YuguCompat.setStreamUpload(false);
        Events e2 = new Events();
        m.startRecord(s, e2.recorder);
        m.feed(Fixtures.bytes("spec/fixtures/audio/zh_short.wav"));
        m.stopRecord();
        Main.await("final over REST", 10000, () -> e2.json != null);
        assertEquals(1, ws.count());
        assertEquals(1, transport.count());
    }

    // ================================================================ SkEgn emulation

    private static final String CFG = "{\"appKey\":\"ak\",\"secretKey\":\"sk\",\"cloud\":{\"server\":\"wss://api.example.com\"}}";

    private static String param(String audioType, Object realtime) {
        return "{\"coreProvideType\":\"cloud\",\"app\":{\"userId\":\"u9\"},\"audio\":{\"audioType\":\"" + audioType
                + "\",\"sampleRate\":16000,\"channel\":1,\"sampleBytes\":2},\"request\":{\"coreType\":\"sent.eval.cn\",\"refText\":\""
                + CUSTOMER + "\",\"realtime_feedback\":" + realtime + "}}";
    }

    private static final class Callback implements SkEgn.skegn_callback {
        final List<String> jsons = Collections.synchronizedList(new ArrayList<String>());
        final List<String> ids = Collections.synchronizedList(new ArrayList<String>());
        volatile boolean offMain;

        @Override
        public int run(byte[] id, int type, byte[] data, int size) {
            if (!Main.onMain()) {
                offMain = true;
            }
            ids.add(new String(id, StandardCharsets.US_ASCII).trim());
            jsons.add(new String(data, 0, size, StandardCharsets.UTF_8));
            return 0;
        }

        boolean hasFinal() {
            synchronized (jsons) {
                for (String j : jsons) {
                    if (Events.isFinal(j)) {
                        return true;
                    }
                }
                return false;
            }
        }
    }

    @Test
    public void skegnDeliversIntermediateResultsThroughTheCallback() throws Exception {
        long e = SkEgn.skegn_new(CFG, app);
        Callback cb = new Callback();
        byte[] id = new byte[64];
        assertEquals(0, SkEgn.skegn_start(e, param("wav", 1), id, cb, app));
        String token = new String(id, StandardCharsets.US_ASCII).trim();
        byte[] pcm = progressPcm();
        for (int i = 0; i < pcm.length; i += 3200) {
            int n = Math.min(3200, pcm.length - i);
            byte[] b = new byte[n];
            System.arraycopy(pcm, i, b, 0, n);
            assertEquals(0, SkEgn.skegn_feed(e, b, n));
        }
        assertEquals(0, SkEgn.skegn_stop(e));
        Main.await("final through the callback", 10000, cb::hasFinal);
        assertFalse(cb.offMain);
        String last = cb.jsons.get(cb.jsons.size() - 1);
        assertTrue(Events.isFinal(last));
        assertTrue(cb.jsons.size() > 2);
        StringBuilder words = new StringBuilder();
        for (int i = 0; i < cb.jsons.size() - 1; i++) {
            JSONObject o = new JSONObject(cb.jsons.get(i));
            assertEquals(0, o.getInt("eof"));
            assertEquals(token, o.getString("tokenId"));
            assertEquals(token, cb.ids.get(i));
            assertEquals("u9", o.getString("userId"));
            JSONArray w = o.getJSONObject("result").getJSONArray("words");
            for (int j = 0; j < w.length(); j++) {
                words.append(w.getJSONObject(j).getString("word"));
            }
        }
        assertEquals(String.join("", java.util.Arrays.copyOf(UNITS, PCM_BYTES / 8000 + 1)), words.toString());
        JSONObject fin = new JSONObject(last);
        assertEquals(94.6, fin.getJSONObject("result").getDouble("overall"), 1e-9);
        FakeWs.Conn c = ws.get(0);
        assertEquals("wss://api.example.com/sent.eval.cn", c.path());
        assertEquals("ak", c.query().get("appKey"));
        assertFalse(new JSONObject(c.paramFrame()).has("getParam"));
        assertEquals(0, transport.count());
        byte[] traffic = new byte[128];
        int n = SkEgn.skegn_opt(e, SkEgn.SKEGN_OPT_GET_TRAFFIC, traffic, traffic.length);
        assertTrue(new String(traffic, 0, n, StandardCharsets.UTF_8).startsWith("{\"upload\":" + PCM_BYTES + ","));
        SkEgn.skegn_delete(e);
    }

    @Test
    public void skegnRealtimeFlagsAndAudioFormats() throws Exception {
        long e = SkEgn.skegn_new(CFG, app);
        Object[] on = {"true", "\"1\"", "\"true\"", "1.0"};
        for (Object v : on) {
            Callback cb = new Callback();
            int before = ws.count();
            assertEquals(0, SkEgn.skegn_start(e, param("pcm", v), new byte[64], cb, app));
            Main.await("connected for " + v, 5000, () -> ws.count() == before + 1);
            SkEgn.skegn_cancel(e);
        }
        Object[] off = {"0", "false", "\"no\"", "null", "2"};
        for (Object v : off) {
            int before = ws.count();
            assertEquals(0, SkEgn.skegn_start(e, param("wav", v), new byte[64], new Callback(), app));
            Main.settle(30);
            assertEquals("realtime_feedback " + v, before, ws.count());
            SkEgn.skegn_cancel(e);
        }
        // progress needs 16 kHz 16 bit mono PCM: other audio is evaluated over REST
        int before = ws.count();
        Callback cb = new Callback();
        assertEquals(0, SkEgn.skegn_start(e, param("mp3", 1), new byte[64], cb, app));
        SkEgn.skegn_feed(e, new byte[] {(byte) 0xFF, (byte) 0xFB, 0x10, 0x00}, 4);
        SkEgn.skegn_stop(e);
        Main.await("final", 5000, cb::hasFinal);
        assertEquals(before, ws.count());
        assertEquals(1, transport.count());
        String eightKhz = param("wav", 1).replace("\"sampleRate\":16000", "\"sampleRate\":8000");
        assertEquals(0, SkEgn.skegn_start(e, eightKhz, new byte[64], new Callback(), app));
        SkEgn.skegn_cancel(e);
        assertEquals(0, SkEgn.skegn_start(e, param("wav", 1).replace("\"channel\":1", "\"channel\":2"), new byte[64],
                new Callback(), app));
        SkEgn.skegn_cancel(e);
        Main.settle(30);
        assertEquals(before, ws.count());
        SkEgn.skegn_delete(e);
    }

    @Test
    public void skegnFallbackRejectionAndCancel() throws Exception {
        long e = SkEgn.skegn_new(CFG, app);
        // refused channel: REST with the same tokenId
        ws.refuseNext(new java.net.ConnectException("refused"));
        Callback cb = new Callback();
        byte[] id = new byte[64];
        SkEgn.skegn_start(e, param("wav", 1), id, cb, app);
        SkEgn.skegn_feed(e, Fixtures.bytes("spec/fixtures/audio/zh_short.wav"), 61518);
        SkEgn.skegn_stop(e);
        Main.await("final", 10000, cb::hasFinal);
        assertEquals(1, transport.count());
        assertEquals(new String(id, StandardCharsets.US_ASCII).trim(), transport.calls().get(0).header("Idempotency-Key"));
        // too short: local error, the channel is dropped without end
        Callback shortCb = new Callback();
        SkEgn.skegn_start(e, param("wav", 1), new byte[64], shortCb, app);
        SkEgn.skegn_feed(e, Fixtures.tone(300, 6000), 9600);
        Main.await("connected", 5000, () -> ws.last() != null && ws.last().paramFrame() != null);
        FakeWs.Conn shortConn = ws.last();
        SkEgn.skegn_stop(e);
        Main.await("error", 5000, shortCb::hasFinal);
        assertEquals(60005, new JSONObject(shortCb.jsons.get(0)).getInt("errId"));
        Main.await("dropped", 5000, () -> shortConn.aborted);
        assertFalse(shortConn.endReceived());
        // cancel: no callback, channel dropped
        Callback cancelled = new Callback();
        SkEgn.skegn_start(e, param("wav", 1), new byte[64], cancelled, app);
        SkEgn.skegn_feed(e, progressPcm(), 64000);
        Main.await("connected", 5000, () -> ws.last() != shortConn && ws.last().paramFrame() != null);
        FakeWs.Conn conn = ws.last();
        Main.await("progress", 5000, () -> !cancelled.jsons.isEmpty());
        SkEgn.skegn_cancel(e);
        Main.await("aborted", 5000, () -> conn.aborted);
        int n = cancelled.jsons.size();
        conn.serverText(ScriptedPlatform.progressFrame(UNITS, 0, 25, -1, 1));
        Main.settle(50);
        assertEquals(n, cancelled.jsons.size());
        // after end the realtime channel decides: an error there is the answer, no REST
        platform.failingEnds = platform.ends.get() + 3;
        Callback failed = new Callback();
        SkEgn.skegn_start(e, param("wav", 1), new byte[64], failed, app);
        SkEgn.skegn_feed(e, Fixtures.bytes("spec/fixtures/audio/zh_short.wav"), 61518);
        SkEgn.skegn_stop(e);
        Main.await("error", 10000, failed::hasFinal);
        String last = failed.jsons.get(failed.jsons.size() - 1);
        assertEquals(20009, new JSONObject(last).getInt("errId"));
        assertEquals(1, transport.count());
        SkEgn.skegn_delete(e);
    }
}
