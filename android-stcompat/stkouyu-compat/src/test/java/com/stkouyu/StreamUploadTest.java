package com.stkouyu;

import com.shengzhiai.yugu.stcompat.internal.CompatConfig;
import com.shengzhiai.yugu.stcompat.internal.CompatEngine;
import com.shengzhiai.yugu.stcompat.internal.Envelope;
import com.shengzhiai.yugu.stcompat.internal.LogFileSink;
import com.shengzhiai.yugu.stcompat.internal.RawJson;
import com.shengzhiai.yugu.stcompat.internal.Wav;
import com.shengzhiai.yugu.stcompat.internal.YLog;
import com.shengzhiai.yugu.stcompat.testing.CompatTestBase;
import com.shengzhiai.yugu.stcompat.testing.Events;
import com.shengzhiai.yugu.stcompat.testing.FakeAudio;
import com.shengzhiai.yugu.stcompat.testing.FakeTransport;
import com.shengzhiai.yugu.stcompat.testing.FakeWs;
import com.shengzhiai.yugu.stcompat.testing.Fixtures;
import com.shengzhiai.yugu.stcompat.testing.Main;
import com.shengzhiai.yugu.stcompat.testing.ScriptedPlatform;
import com.stkouyu.setting.EngineSetting;
import com.stkouyu.setting.RecordSetting;
import com.stkouyu.util.AiUtil;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * SDK 2.1.1 on SkEgnManager: recordings and stream sessions stream over the compat realtime channel
 * by default (YuguCompat.setStreamUpload(false) restores the upload after stop), timing=1 only at
 * INFO or DEBUG, the platform's timing object never reaches the app, and one INFO line per
 * evaluation without secrets.
 */
@RunWith(RobolectricTestRunner.class)
public class StreamUploadTest extends CompatTestBase {
    static final String TEXT = "今天天气很好";
    static final String FINAL = Fixtures.text("spec/fixtures/platform/compat_sent.eval.cn.json").trim();
    static final String TIMING = "{\"receivedAt\":1791800000123,\"totalMs\":5321,\"engineMs\":4100,\"platformMs\":1221,"
            + "\"engineStages\":{\"text_frontend_ms\":1909.3,\"asr_ms\":595.0},\"audioBytes\":61440,"
            + "\"wsFirstAudioAt\":1791799990000}";
    /** The final body as the platform sends it when the request had timing=1. */
    static final String FINAL_WITH_TIMING = FINAL.substring(0, FINAL.length() - 1) + ",\"timing\":" + TIMING + "}";
    static final byte[] PCM = Fixtures.pcm("spec/fixtures/audio/zh_short.wav");

    private final Events ev = new Events();
    private final List<String> lines = Collections.synchronizedList(new ArrayList<String>());
    private FakeWs ws;
    private ScriptedPlatform platform;

    @Before
    public void channel() {
        ws = new FakeWs();
        platform = new ScriptedPlatform(ScriptedPlatform.chineseUnits(TEXT));
        ws.script(platform);
        CompatConfig.setWsConnector(ws);
        YLog.setSink((level, tag, msg, err) -> lines.add(level + " " + msg));
    }

    private static RecordSetting sent() {
        return new RecordSetting(CoreType.CN_SENT_EVAL, TEXT);
    }

    private void feedFixture(SkEgnManager m) {
        byte[] wav = Fixtures.bytes("spec/fixtures/audio/zh_short.wav");
        for (int i = 0; i < wav.length; i += 3200) {
            m.feed(Arrays.copyOfRange(wav, i, Math.min(wav.length, i + 3200)));
        }
    }

    private JSONObject streamSession(SkEgnManager m, RecordSetting s, Events e) {
        s.setIsStream(true);
        m.startRecord(s, e.recorder);
        feedFixture(m);
        m.stopRecord();
        Main.await("final onScore", 10000, () -> e.json != null);
        Main.settle(30);
        return e.result();
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

    private String summary(String token) {
        List<String> s = linesStartingWith("3 timing tokenId=" + token + " ");
        assertEquals("one summary line per evaluation: " + lines, 1, s.size());
        return s.get(0).substring(2);
    }

    // ================================================================ streaming by default

    @Test
    public void microphoneRecordingStreamsByDefault() throws Exception {
        FakeAudio audio = new FakeAudio(PCM).endWithNothing();
        CompatConfig.setAudioInputFactory(audio);
        assertTrue(YuguCompat.isStreamUpload());
        SkEgnManager m = initManager(null);
        m.startRecord(sent(), ev.recorder);
        String token = CompatEngine.get(app).currentTokenId();
        Main.await("audio streamed while recording", 5000,
                () -> ws.count() == 1 && ws.get(0).audio().length == PCM.length);
        assertEquals(SkEgnManager.engine_status.RECORDING, m.getEngineStatus());
        assertFalse("no end before stop", ws.get(0).endReceived());
        m.stopRecord();
        Main.await("final onScore", 10000, () -> ev.json != null);
        Main.settle(30);
        assertEquals(Arrays.asList("onStart", "onRecordEnd", "onScore"), ev.log);
        assertTrue(ev.offMain.isEmpty());
        FakeWs.Conn c = ws.get(0);
        assertTrue(c.endReceived());
        assertEquals(0, transport.count());
        JSONObject param = new JSONObject(c.paramFrame());
        assertFalse(param.has("realtime_feedback"));
        assertFalse("WARN level: no timing", param.has("timing"));
        assertEquals(TEXT, param.getString("refText"));
        assertEquals(token, param.getString("idempotencyKey"));
        assertEquals(token, c.query().get("idempotencyKey"));
        assertFalse("progress frames were sent", platform.sentProgress.isEmpty());
        assertTrue("no intermediate results without realtime_feedback", ev.intermediate().isEmpty());
        JSONObject r = ev.result();
        assertEquals(token, r.getString("tokenId"));
        assertEquals(94.6, r.getJSONObject("result").getDouble("overall"), 1e-9);
        // the WAV file is written as before, with exactly the streamed audio
        File f = new File(m.getLastRecordPath());
        Wav.Info info = Wav.parse(f);
        assertEquals(PCM.length, info.dataLength);
        byte[] fileBytes = Files.readAllBytes(f.toPath());
        assertArrayEquals(c.audio(), Arrays.copyOfRange(fileBytes, (int) info.dataOffset, fileBytes.length));
        // the same envelope as the REST path for the same body
        JSONObject rest = new JSONObject(Envelope.success(token, "test-app-key", "user-1", TEXT, FINAL, null, 0));
        rest.remove("dtLastResponse");
        r.remove("dtLastResponse");
        assertEquals(rest.toString(), r.toString());
    }

    @Test
    public void callerFedStreamStreamsByDefault() throws Exception {
        SkEgnManager m = initManager(null);
        JSONObject r = streamSession(m, sent(), ev);
        assertEquals(1, ws.count());
        assertEquals(0, transport.count());
        assertArrayEquals("the WAV header of the fed file is skipped", PCM, ws.get(0).audio());
        assertTrue(ws.get(0).endReceived());
        assertEquals(1, ev.scores.size());
        assertEquals(94.6, r.getJSONObject("result").getDouble("overall"), 1e-9);
        assertTrue(linesStartingWith("2 realtime channel failed").isEmpty());
    }

    @Test
    public void streamUploadOffUploadsAfterStopAsIn210() throws Exception {
        YuguCompat.setStreamUpload(false);
        assertFalse(YuguCompat.isStreamUpload());
        SkEgnManager m = initManager(null);
        JSONObject r = streamSession(m, sent(), ev);
        assertEquals(0, ws.count());
        assertEquals(1, transport.count());
        assertEquals(r.getString("tokenId"), transport.calls().get(0).header("Idempotency-Key"));
        assertFalse(transport.calls().get(0).bodyText().contains("name=\"timing\""));
        // realtime_feedback 1 still streams with progress, as in 2.1.0
        Events e2 = new Events();
        RecordSetting rt = new RecordSetting(CoreType.CN_SENT_EVAL, TEXT);
        rt.setRealtime_feedback(1);
        rt.setIsStream(true);
        m.startRecord(rt, e2.recorder);
        byte[] pcm = Fixtures.pcm("spec/fixtures/audio/zh_progress.wav");
        for (int i = 0; i < pcm.length; i += 16000) {
            m.feed(Arrays.copyOfRange(pcm, i, Math.min(pcm.length, i + 16000)));
        }
        m.stopRecord();
        Main.await("final", 10000, () -> e2.json != null);
        assertEquals(1, ws.count());
        assertTrue(new JSONObject(ws.get(0).paramFrame()).getBoolean("realtime_feedback"));
        assertFalse(e2.intermediate().isEmpty());
        assertEquals(1, transport.count());
        YuguCompat.reset();
        assertTrue(YuguCompat.isStreamUpload());
    }

    @Test
    public void channelFailureBeforeEndFallsBackWithTheSameTokenId() throws Exception {
        CompatConfig.setWsConnector(FakeWs.REFUSING);
        YuguCompat.setLogLevel(YuguCompat.LOG_INFO);
        SkEgnManager m = initManager(null);
        JSONObject r = streamSession(m, sent(), ev);
        String token = r.getString("tokenId");
        assertEquals(1, transport.count());
        FakeTransport.Call call = transport.calls().get(0);
        assertEquals(token, call.header("Idempotency-Key"));
        assertTrue(r.has("result"));
        List<String> warn = linesStartingWith("2 realtime channel failed before end");
        assertEquals(1, warn.size());
        assertTrue(warn.get(0), warn.get(0).contains("realtime channel refused") && warn.get(0).contains(token));
        String line = summary(token);
        assertTrue(line, line.contains(" mode=ws>rest "));
        assertTrue(line, line.contains(" attempts=1 "));
        assertTrue(line, line.endsWith("fallback=\"cannot open the realtime channel: ConnectException realtime channel refused (unit test default)\""));
        assertTrue("timing=1 also on the fallback upload", call.bodyText().contains(
                "name=\"timing\"\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\n1\r\n"));
    }

    @Test
    public void existsAudioTransKeepsTheFileUpload() throws Exception {
        SkEgnManager m = initManager(null);
        RecordSetting s = sent();
        s.setAudioPath(Fixtures.file("spec/fixtures/audio/zh_short.wav").getAbsolutePath());
        YuguCompat.setLogLevel(YuguCompat.LOG_INFO);
        m.existsAudioTrans(s, ev.recorder);
        Main.await("final", 10000, () -> ev.json != null);
        assertEquals(0, ws.count());
        assertEquals(1, transport.count());
        String line = summary(ev.result().getString("tokenId"));
        assertTrue(line, line.contains(" mode=rest audio=1.92s afterStop="));
    }

    // ================================================================ timing=1 and its removal

    @Test
    public void timingIsAskedForOnlyAtInfoOrDebug() throws Exception {
        SkEgnManager m = initManager(null);
        streamSession(m, sent(), ev);
        assertFalse(new JSONObject(ws.get(0).paramFrame()).has("timing"));

        YuguCompat.setLogLevel(YuguCompat.LOG_INFO);
        Events e2 = new Events();
        streamSession(m, sent().setNeedRequestParamsInResult(true), e2);
        JSONObject param = new JSONObject(ws.get(1).paramFrame());
        assertEquals(1, param.get("timing"));
        assertFalse("params show the fields of 2.1.0", e2.result().getJSONObject("params").getJSONObject("request").has("timing"));

        YuguCompat.setStreamUpload(false);
        Events e3 = new Events();
        streamSession(m, sent(), e3);
        assertTrue(transport.calls().get(0).bodyText().contains(
                "name=\"timing\"\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\n1\r\n"));

        YuguCompat.setLogLevel(YuguCompat.LOG_WARN);
        Events e4 = new Events();
        streamSession(m, sent(), e4);
        assertFalse(transport.calls().get(1).bodyText().contains("name=\"timing\""));
        assertEquals(2, ws.count());
    }

    @Test
    public void theTimingObjectNeverReachesTheApp() throws Exception {
        YuguCompat.setLogLevel(YuguCompat.LOG_DEBUG);
        platform.finalBody = FINAL_WITH_TIMING;
        SkEgnManager m = initManager(null);
        JSONObject r = streamSession(m, sent(), ev);
        assertWithoutTiming(ev.json, r.getString("tokenId"));
        assertTrue(summary(r.getString("tokenId")).contains(" server.total=5321ms server.engine=4100ms server.platform=1221ms"));

        YuguCompat.setStreamUpload(false);
        Map<String, String> h = new HashMap<>();
        h.put("Server-Timing", "total;dur=5321, engine;dur=4100, platform;dur=1221");
        transport.respond(200, FINAL_WITH_TIMING, h);
        Events e2 = new Events();
        JSONObject r2 = streamSession(m, sent(), e2);
        assertWithoutTiming(e2.json, r2.getString("tokenId"));
    }

    private static void assertWithoutTiming(String json, String token) throws Exception {
        assertFalse(json, RawJson.members(json).containsKey("timing"));
        assertFalse(json, json.contains("engineStages") || json.contains("wsFirstAudioAt"));
        JSONObject expected = new JSONObject(Envelope.success(token, "test-app-key", "user-1", TEXT, FINAL, null, 0));
        JSONObject got = new JSONObject(json);
        expected.remove("dtLastResponse");
        got.remove("dtLastResponse");
        assertEquals("the same JSON as without timing", expected.toString(), got.toString());
    }

    // ================================================================ the log

    @Test
    public void summaryLineAndStagesWithoutSecrets() throws Exception {
        YuguCompat.setLogLevel(YuguCompat.LOG_DEBUG);
        final String body = FINAL_WITH_TIMING;
        ws.script(new FakeWs.Script() {
            @Override
            public void onText(FakeWs.Conn c, String text) {
                if (FakeWs.END.equals(text)) {
                    c.nextWireBytes = 2345;
                    c.serverText(body);
                }
            }

            @Override
            public void onBinary(FakeWs.Conn c, byte[] data) {
            }
        });
        SkEgnManager m = initManager(null);
        JSONObject r = streamSession(m, sent(), ev);
        String token = r.getString("tokenId");
        String line = summary(token);
        int finalBytes = body.getBytes(StandardCharsets.UTF_8).length;
        Matcher x = Pattern.compile("timing tokenId=" + token + " mode=ws audio=1\\.92s afterStop=(\\d+)ms upload=(\\d+)ms"
                + " wait=(\\d+)ms download=(\\d+)ms parse=(\\d+)ms deliver=(\\d+)ms handshake=\\d+ms queued=\\d+B"
                + " final=" + finalBytes + "B wire=2345B deflate server.total=5321ms server.engine=4100ms"
                + " server.platform=1221ms").matcher(line);
        assertTrue(line, x.matches());
        long sum = 0;
        for (int i = 2; i <= 6; i++) {
            sum += Long.parseLong(x.group(i));
        }
        assertTrue("the parts add up to the time after stop: " + line, sum <= Long.parseLong(x.group(1)) + 5);
        assertTrue(lines.contains("4 timing tokenId=" + token + " engineStages text_frontend_ms=1909.3 asr_ms=595.0"));
        for (String stage : new String[] {"stop", "ws.connect", "ws.handshake no extension", "ws.param", "ws.audio.first",
                "ws.end.queued queued=", "ws.end", "ws.final.first", "ws.final bytes=" + finalBytes + " wire=2345 deflate",
                "parsed", "delivered"}) {
            boolean found = false;
            for (String l : linesStartingWith("4 stage tokenId=" + token + " +")) {
                found |= l.matches("4 stage tokenId=" + token + " \\+\\d+ms " + Pattern.quote(stage) + ".*");
            }
            assertTrue("stage " + stage + " in " + lines, found);
        }
        synchronized (lines) {
            for (String l : lines) {
                assertFalse(l, l.contains("test-secret"));
                assertFalse(l, l.contains("signature") || l.contains("Signature"));
                assertFalse(l, l.contains("test-app-key"));
                assertTrue("short lines, no audio: " + l.length(), l.length() < 600);
            }
        }
    }

    @Test
    public void restAndErrorSummaries() throws Exception {
        YuguCompat.setLogLevel(YuguCompat.LOG_INFO);
        YuguCompat.setStreamUpload(false);
        Map<String, String> h = new HashMap<>();
        h.put("Server-Timing", "total;dur=812, engine;dur=700, platform;dur=112");
        transport.respond(200, FINAL, h);
        SkEgnManager m = initManager(null);
        JSONObject r = streamSession(m, sent(), ev);
        String line = summary(r.getString("tokenId"));
        assertTrue(line, line.contains(" mode=rest audio=1.92s "));
        assertTrue(line, line.contains(" attempts=1 "));
        assertTrue(line, line.endsWith(" server.total=812ms server.engine=700ms server.platform=112ms"));
        transport.respond(400, FakeTransport.error(40001, "bad"));
        Events e2 = new Events();
        JSONObject err = streamSession(m, sent(), e2);
        assertEquals(40001, err.getInt("errId"));
        assertTrue(summary(err.getString("tokenId")).endsWith(" errId=40001"));
    }

    @Test
    public void engineSettingLogOptionsTakeEffectAlsoAfterInit() throws Exception {
        SkEgnManager m = initManager(null);
        assertEquals(YuguCompat.LOG_WARN, YuguCompat.getLogLevel());
        EngineSetting es = EngineSetting.getInstance(app);
        es.setSDKLogEnabled(true);
        assertEquals("logLevel 1 is warn", YuguCompat.LOG_WARN, YuguCompat.getLogLevel());
        es.setLogLevel(2);
        assertEquals(YuguCompat.LOG_INFO, YuguCompat.getLogLevel());
        es.setEnableSaveLogCatToFile(true);
        JSONObject r = streamSession(m, sent(), ev);
        assertEquals("INFO asks for timing", 1, new JSONObject(ws.get(0).paramFrame()).get("timing"));
        File log = new File(new File(AiUtil.externalFilesDir(app), "log"), "stkouyu_sdk.log");
        LogFileSink.uninstall();
        String text = new String(Files.readAllBytes(log.toPath()), StandardCharsets.UTF_8);
        assertTrue(text, text.contains(" I/YuguStCompat: timing tokenId=" + r.getString("tokenId") + " mode=ws "));
        es.setLogLevel(3);
        assertEquals(YuguCompat.LOG_DEBUG, YuguCompat.getLogLevel());
        es.setSDKLogEnabled(false);
        assertEquals("switched off: back to WARN", YuguCompat.LOG_WARN, YuguCompat.getLogLevel());
        es.setSDKLogEnabled(true);
        YuguCompat.setLogLevel(YuguCompat.LOG_ERROR);
        es.setLogLevel(3);
        assertEquals("YuguCompat.setLogLevel wins", YuguCompat.LOG_ERROR, YuguCompat.getLogLevel());
        es.setSDKLogEnabled(false);
        assertEquals(YuguCompat.LOG_ERROR, YuguCompat.getLogLevel());
    }

    @Test
    public void skegnAsksForTimingAndRemovesIt() throws Exception {
        YuguCompat.setLogLevel(YuguCompat.LOG_INFO);
        transport.defaultBody(FINAL_WITH_TIMING);
        long e = SkEgn.skegn_new("{\"appKey\":\"ak-skegn\",\"secretKey\":\"sk-skegn\"}", app);
        final String[] got = new String[1];
        byte[] id = new byte[64];
        SkEgn.skegn_start(e, "{\"app\":{\"userId\":\"u\"},\"audio\":{\"audioType\":\"wav\",\"sampleRate\":16000,\"channel\":1,"
                + "\"sampleBytes\":2},\"request\":{\"coreType\":\"sent.eval.cn\",\"refText\":\"" + TEXT + "\"}}", id,
                (tid, type, data, size) -> {
                    got[0] = new String(data, 0, size, StandardCharsets.UTF_8);
                    return 0;
                }, app);
        SkEgn.skegn_feed(e, Fixtures.bytes("spec/fixtures/audio/zh_short.wav"), 61518);
        SkEgn.skegn_stop(e);
        Main.await("callback", 10000, () -> got[0] != null);
        String token = new String(id, StandardCharsets.US_ASCII).trim();
        assertEquals("SkEgn without realtime_feedback uploads after skegn_stop", 0, ws.count());
        assertTrue(transport.calls().get(0).bodyText().contains("name=\"timing\""));
        assertFalse(got[0], RawJson.members(got[0]).containsKey("timing"));
        String line = summary(token);
        assertTrue(line, line.contains(" mode=rest audio=1.92s ") && line.contains(" server.total=5321ms"));
        for (String l : lines) {
            assertFalse(l, l.contains("sk-skegn") || l.contains("ak-skegn"));
        }
        assertNotNull(new JSONObject(got[0]).getJSONObject("result"));
        SkEgn.skegn_delete(e);
    }
}
