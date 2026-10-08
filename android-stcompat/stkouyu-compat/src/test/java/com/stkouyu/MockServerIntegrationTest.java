package com.stkouyu;

import com.shengzhiai.yugu.stcompat.internal.CompatConfig;
import com.shengzhiai.yugu.stcompat.testing.CompatTestBase;
import com.shengzhiai.yugu.stcompat.testing.Events;
import com.shengzhiai.yugu.stcompat.testing.Fixtures;
import com.shengzhiai.yugu.stcompat.testing.Main;
import com.shengzhiai.yugu.stcompat.testing.MockServer;
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
import org.robolectric.shadows.ShadowAudioRecord;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Integration tests against tools/mock-server (same signature and idempotency rules as the
 * platform): real HTTP, real retry back-off, real multipart parsing and signature verification.
 */
@RunWith(RobolectricTestRunner.class)
public class MockServerIntegrationTest extends CompatTestBase {
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
        CompatConfig.setSleeper(null);
        server.reset();
        YuguCompat.setBaseUrl(server.baseUrl());
    }

    private SkEgnManager init(String secret) {
        SkEgnManager m = SkEgnManager.getInstance(app);
        m.initEngine(MockServer.APP_KEY, secret, "it-user", EngineSetting.getInstance(app));
        Main.idle();
        return m;
    }

    private static double fixtureOverall(String name) throws Exception {
        return new JSONObject(Fixtures.text("spec/fixtures/platform/" + name)).getJSONObject("result").getDouble("overall");
    }

    /** Stream mode: feed zh_short.wav, stop, evaluate over the mock platform. */
    private JSONObject streamEvaluate(SkEgnManager m, RecordSetting s, Events e) throws Exception {
        s.setIsStream(true);
        m.startRecord(s, e.recorder);
        byte[] wav = Fixtures.bytes("spec/fixtures/audio/zh_short.wav");
        for (int i = 0; i < wav.length; i += 640) {
            int n = Math.min(640, wav.length - i);
            byte[] chunk = new byte[n];
            System.arraycopy(wav, i, chunk, 0, n);
            m.feed(chunk, n);
        }
        m.stopRecord();
        Main.await("onScore", 30000, () -> e.json != null);
        assertTrue(e.offMain.isEmpty());
        return e.result();
    }

    @Test
    public void streamFeedOfFixtureGivesTheEnvelope() throws Exception {
        SkEgnManager m = init(MockServer.SECRET);
        JSONObject r = streamEvaluate(m, new RecordSetting(CoreType.CN_SENT_EVAL, "今天天气很好"), ev);
        String token = r.getString("tokenId");
        assertTrue(token.matches("[0-9a-f]{32}"));
        assertTrue(r.getString("recordId").startsWith("eval_"));
        assertEquals(MockServer.APP_KEY, r.getString("applicationId"));
        assertEquals("it-user", r.getString("userId"));
        assertEquals("今天天气很好", r.getString("refText"));
        assertEquals(1, r.getInt("eof"));
        assertTrue(r.has("dtLastResponse"));
        assertEquals(fixtureOverall("compat_sent.eval.cn.json"), r.getJSONObject("result").getDouble("overall"), 1e-9);
        assertTrue(r.getJSONObject("result").getJSONArray("words").length() > 0);
        JSONArray log = server.log();
        assertEquals(1, log.length());
        JSONObject req = log.getJSONObject(0);
        assertEquals("/sent.eval.cn", req.getString("path"));
        assertEquals(token, req.getString("idempotencyKey"));
        assertEquals("今天天气很好", req.getJSONObject("fields").getString("refText"));
        assertEquals("yugu-stkouyu-compat/2.0.0", req.getString("userAgent"));
        JSONObject billing = server.billing();
        assertEquals(1, billing.getInt("billed"));
        assertEquals(1, billing.getJSONObject("byKey").getInt(token));
    }

    @Test
    public void existsAudioTransOfWavFile() throws Exception {
        SkEgnManager m = init(MockServer.SECRET);
        RecordSetting s = new RecordSetting(CoreType.EN_WORD_EVAL, "apple");
        s.setAudioPath(Fixtures.file("spec/fixtures/audio/en_apple.wav").getAbsolutePath());
        s.setNeedPhonemeOutputInWord(true);
        m.existsAudioTrans(s, ev.record);
        Main.await("onRecordEnd(json)", 30000, () -> ev.json != null);
        JSONObject r = ev.result();
        assertEquals(fixtureOverall("compat_word.eval.json"), r.getJSONObject("result").getDouble("overall"), 1e-9);
        JSONObject req = server.log().getJSONObject(0);
        assertEquals("/word.eval", req.getString("path"));
        assertEquals("1", req.getJSONObject("fields").getString("phoneme_output"));
    }

    @Test
    public void injected500ThenSuccessBillsOnceWithTheSameKey() throws Exception {
        server.fault("/sent.eval.cn", "status:500");
        SkEgnManager m = init(MockServer.SECRET);
        JSONObject r = streamEvaluate(m, new RecordSetting(CoreType.CN_SENT_EVAL, "今天天气很好"), ev);
        String token = r.getString("tokenId");
        assertTrue(r.has("result"));
        JSONArray log = server.log();
        assertEquals(2, log.length());
        assertEquals("status:500", log.getJSONObject(0).getString("fault"));
        for (int i = 0; i < log.length(); i++) {
            assertEquals(token, log.getJSONObject(i).getString("idempotencyKey"));
        }
        JSONObject billing = server.billing();
        assertEquals(1, billing.getInt("billed"));
        assertEquals(1, billing.getJSONObject("byKey").getInt(token));
    }

    @Test
    public void injected400IsNotRetried() throws Exception {
        server.fault("/sent.eval.cn", "status:400:code=40001");
        SkEgnManager m = init(MockServer.SECRET);
        JSONObject r = streamEvaluate(m, new RecordSetting(CoreType.CN_SENT_EVAL, "今天天气很好"), ev);
        assertEquals(40001, r.getInt("errId"));
        assertEquals(1, r.getInt("eof"));
        assertEquals(1, server.log().length());
        assertEquals(0, server.billing().getInt("billed"));
    }

    @Test
    public void unsupportedCoreTypeNeedsNoNetwork() throws Exception {
        SkEgnManager m = init(MockServer.SECRET);
        m.startRecord(new RecordSetting(CoreType.FR_SENT_EVAL, "bonjour"), ev.recorder);
        Main.await("onScore", 5000, () -> ev.json != null);
        assertEquals(60003, ev.result().getInt("errId"));
        Main.settle(100);
        assertEquals(0, server.log().length());
    }

    @Test
    public void rateLimitHonoursRetryAfter() throws Exception {
        server.fault("/sent.eval.cn", "status:429:code=42900:retryAfter=1");
        SkEgnManager m = init(MockServer.SECRET);
        long t0 = System.currentTimeMillis();
        JSONObject r = streamEvaluate(m, new RecordSetting(CoreType.CN_SENT_EVAL, "今天天气很好"), ev);
        assertTrue(r.has("result"));
        assertTrue(System.currentTimeMillis() - t0 >= 1000);
        assertEquals(2, server.log().length());
    }

    @Test
    public void readTimeoutRetryReplaysAndBillsOnce() throws Exception {
        server.fault("/sent.eval.cn", "delay:1500");
        YuguCompat.setTimeouts(5000, 800, 60000);
        SkEgnManager m = init(MockServer.SECRET);
        JSONObject r = streamEvaluate(m, new RecordSetting(CoreType.CN_SENT_EVAL, "今天天气很好"), ev);
        String token = r.getString("tokenId");
        assertTrue(r.toString(), r.has("result"));
        JSONArray log = server.log();
        assertEquals(2, log.length());
        assertEquals(token, log.getJSONObject(0).getString("idempotencyKey"));
        assertEquals(token, log.getJSONObject(1).getString("idempotencyKey"));
        // the abandoned first attempt finishes later on the server and is answered from the replay store
        Main.await("one attempt replayed", 5000, () -> {
            try {
                JSONArray l = server.log();
                return l.getJSONObject(0).optBoolean("replayed") || l.getJSONObject(1).optBoolean("replayed");
            } catch (Exception e) {
                return false;
            }
        });
        JSONObject billing = server.billing();
        assertEquals(1, billing.getInt("billed"));
        assertEquals(1, billing.getJSONObject("byKey").getInt(token));
    }

    /**
     * slow: the first attempt registers the key, then the server is slow; the client times out and
     * retries with the same key, the server holds the retry until the first result exists and
     * replays it. One evaluation is billed.
     */
    @Test
    public void inFlightRetryWaitsForTheFirstResultAndIsReplayed() throws Exception {
        server.fault("/sent.eval.cn", "slow:1500");
        YuguCompat.setTimeouts(5000, 800, 60000);
        SkEgnManager m = init(MockServer.SECRET);
        JSONObject r = streamEvaluate(m, new RecordSetting(CoreType.CN_SENT_EVAL, "今天天气很好"), ev);
        String token = r.getString("tokenId");
        assertTrue(r.toString(), r.has("result"));
        JSONArray log = server.log();
        assertEquals(2, log.length());
        assertEquals(token, log.getJSONObject(0).getString("idempotencyKey"));
        assertEquals(token, log.getJSONObject(1).getString("idempotencyKey"));
        assertTrue(log.getJSONObject(1).optBoolean("replayed"));
        assertEquals(1, server.billing().getInt("billed"));
    }

    @Test
    public void droppedConnectionIsRetried() throws Exception {
        server.fault("/sent.eval.cn", "drop");
        SkEgnManager m = init(MockServer.SECRET);
        JSONObject r = streamEvaluate(m, new RecordSetting(CoreType.CN_SENT_EVAL, "今天天气很好"), ev);
        assertTrue(r.has("result"));
        assertEquals(2, server.log().length());
    }

    @Test
    public void exhaustedRetriesAre20009AndAutoRetryRecovers() throws Exception {
        for (int i = 0; i < 3; i++) {
            server.fault("/sent.eval.cn", "status:503");
        }
        SkEgnManager m = init(MockServer.SECRET);
        JSONObject r = streamEvaluate(m, new RecordSetting(CoreType.CN_SENT_EVAL, "今天天气很好"), ev);
        assertEquals(20009, r.getInt("errId"));
        assertEquals(3, server.log().length());
        server.reset();
        for (int i = 0; i < 3; i++) {
            server.fault("/sent.eval.cn", "status:500");
        }
        Events e2 = new Events();
        RecordSetting s = new RecordSetting(CoreType.CN_SENT_EVAL, "今天天气很好");
        s.setAutoRetry(true);
        JSONObject r2 = streamEvaluate(m, s, e2);
        assertTrue(r2.has("result"));
        JSONArray log = server.log();
        assertEquals(4, log.length());
        for (int i = 0; i < 4; i++) {
            assertEquals(r2.getString("tokenId"), log.getJSONObject(i).getString("idempotencyKey"));
        }
        assertEquals(1, server.billing().getInt("billed"));
    }

    @Test
    public void wrongSecretIsASignatureError() throws Exception {
        SkEgnManager m = init("not-the-secret");
        JSONObject r = streamEvaluate(m, new RecordSetting(CoreType.CN_SENT_EVAL, "今天天气很好"), ev);
        assertEquals(2003, r.getInt("errId"));
        assertEquals(1, server.log().length());
    }

    @Test
    public void cancelAbortsAHangingRequest() throws Exception {
        server.fault("/sent.eval.cn", "hang");
        SkEgnManager m = init(MockServer.SECRET);
        RecordSetting s = new RecordSetting(CoreType.CN_SENT_EVAL, "今天天气很好");
        s.setIsStream(true);
        m.startRecord(s, ev.recorder);
        m.feed(Fixtures.bytes("spec/fixtures/audio/zh_short.wav"));
        m.stopRecord();
        Main.await("request received", 10000, () -> {
            try {
                return server.log().length() == 1;
            } catch (Exception e) {
                return false;
            }
        });
        m.cancel();
        Main.settle(300);
        assertFalse(ev.has("onScore"));
        assertEquals(SkEgnManager.engine_status.IDLE, m.getEngineStatus());
    }

    @Test
    public void skEgnEmulationAgainstTheMock() throws Exception {
        YuguCompat.setBaseUrl(null);
        long e = SkEgn.skegn_new("{\"appKey\":\"mock-app-key\",\"secretKey\":\"mock-secret-key\",\"cloud\":{\"server\":\""
                + server.baseUrl() + "\"}}", app);
        final String[] got = new String[1];
        byte[] id = new byte[64];
        SkEgn.skegn_start(e, "{\"app\":{\"userId\":\"u\"},\"audio\":{\"audioType\":\"wav\",\"sampleRate\":16000,\"channel\":1,"
                + "\"sampleBytes\":2},\"request\":{\"coreType\":\"para.eval.cn\",\"refText\":\"今天天气很好。我们一起去公园散步。\"}}",
                id, (tid, type, data, size) -> {
                    got[0] = new String(data, 0, size, StandardCharsets.UTF_8);
                    return 0;
                }, app);
        SkEgn.skegn_feed(e, Fixtures.bytes("spec/fixtures/audio/zh_short.wav"), 61518);
        SkEgn.skegn_stop(e);
        Main.await("callback", 30000, () -> got[0] != null);
        JSONObject r = new JSONObject(got[0]);
        assertEquals(fixtureOverall("compat_para.eval.cn.json"), r.getJSONObject("result").getDouble("overall"), 1e-9);
        assertEquals("/para.eval.cn", server.log().getJSONObject(0).getString("path"));
        assertEquals("1", server.log().getJSONObject(0).getJSONObject("fields").getString("paragraph_need_word_score"));
        SkEgn.skegn_delete(e);
    }

    @Test
    public void microphoneRecordingWithShadowAudioRecord() throws Exception {
        final byte[] pcm = Fixtures.pcm("spec/fixtures/audio/zh_short.wav");
        final int[] pos = {0};
        ShadowAudioRecord.setSourceProvider(audioRecord -> new ShadowAudioRecord.AudioRecordSource() {
            @Override
            public int readInByteArray(byte[] audioData, int offsetInBytes, int sizeInBytes, boolean isBlocking) {
                synchronized (pos) {
                    int n = Math.min(sizeInBytes, pcm.length - pos[0]);
                    if (n <= 0) {
                        return 0;
                    }
                    System.arraycopy(pcm, pos[0], audioData, offsetInBytes, n);
                    pos[0] += n;
                    return n;
                }
            }

            @Override
            public int readInShortArray(short[] a, int o, int s, boolean b) {
                return 0;
            }

            @Override
            public int readInFloatArray(float[] a, int o, int s, boolean b) {
                return 0;
            }

            @Override
            public int readInDirectBuffer(ByteBuffer buffer, int sizeInBytes, boolean isBlocking) {
                return 0;
            }
        });
        CompatConfig.setAudioInputFactory(null);
        SkEgnManager m = init(MockServer.SECRET);
        assertTrue(m.activeMic());
        assertTrue(m.releaseMic());
        m.startRecord(new RecordSetting(CoreType.CN_SENT_EVAL, "今天天气很好"), ev.recorder);
        Main.await("all audio read", 10000, () -> {
            synchronized (pos) {
                return pos[0] >= pcm.length;
            }
        });
        Main.settle(100);
        m.stopRecord();
        Main.await("onScore", 30000, () -> ev.json != null);
        JSONObject r = ev.result();
        assertEquals(fixtureOverall("compat_sent.eval.cn.json"), r.getJSONObject("result").getDouble("overall"), 1e-9);
        assertTrue(ev.offMain.isEmpty());
        ShadowAudioRecord.clearSource();
    }
}
