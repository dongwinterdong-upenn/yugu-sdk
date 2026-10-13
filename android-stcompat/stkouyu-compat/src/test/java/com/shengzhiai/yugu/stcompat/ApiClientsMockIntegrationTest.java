package com.shengzhiai.yugu.stcompat;

import com.shengzhiai.yugu.stcompat.internal.CancelToken;
import com.shengzhiai.yugu.stcompat.internal.Codec;
import com.shengzhiai.yugu.stcompat.internal.CompatClient;
import com.shengzhiai.yugu.stcompat.internal.CompatConfig;
import com.shengzhiai.yugu.stcompat.internal.YLog;
import com.shengzhiai.yugu.stcompat.testing.CompatTestBase;
import com.shengzhiai.yugu.stcompat.testing.Main;
import com.shengzhiai.yugu.stcompat.testing.MockServer;
import com.stkouyu.SkEgnManager;
import com.stkouyu.YuguCompat;
import com.stkouyu.setting.EngineSetting;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static com.shengzhiai.yugu.stcompat.TtsClientTest.apiError;
import static com.shengzhiai.yugu.stcompat.TtsClientTest.offMain;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * TtsClient and ReportClient against tools/mock-server: real HTTP, the platform's signature check
 * (TTS: top-level scalar fields, report: empty set), idempotency and billing, the report shape of
 * 2026-10-13 and its pending drafts (a recordId containing "pending" is pending for its first two
 * lookups, counted by the mock across resets, so every test uses a fresh recordId).
 */
@RunWith(RobolectricTestRunner.class)
public class ApiClientsMockIntegrationTest extends CompatTestBase {
    private static MockServer server;

    private final List<String> lines = Collections.synchronizedList(new ArrayList<String>());

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
        YuguCompat.setLogLevel(YuguCompat.LOG_INFO);
        YLog.setSink((level, tag, msg, err) -> lines.add(level + " " + msg));
    }

    private static String freshId(String kind) {
        return "eval_" + kind + Codec.randomHex(6);
    }

    private TtsClient tts() {
        return TtsClient.create(MockServer.APP_KEY, MockServer.SECRET, server.baseUrl());
    }

    private ReportClient reports() {
        return ReportClient.create(MockServer.APP_KEY, MockServer.SECRET, server.baseUrl());
    }

    private List<JSONObject> log(String method, String path) throws Exception {
        JSONArray a = server.log();
        List<JSONObject> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.getJSONObject(i);
            if (method.equals(o.optString("method")) && o.optString("path").startsWith(path)) {
                out.add(o);
            }
        }
        return out;
    }

    // ================================================================ TTS

    @Test
    public void ttsIsSignedWithTheScalarFieldsAndResolved() throws Exception {
        TtsRequest req = new TtsRequest("床前明月光，疑是地上霜。").setLanguage("zh-CN").setVoice("xiaoyan").setFormat("wav")
                .setSpeed(60).setPitch(50).setVolume(80).setSampleRate(16000).setEmotion("warm").setStyle("温柔地朗读")
                .setPinyin("chuang2 qian2 ming2 yue4 guang1 yi2 shi4 di4 shang4 shuang1").setGenre("classical_poem");
        TtsResult r = offMain(() -> tts().tts(req, null));
        assertTrue(r.getAudioUrl(), r.getAudioUrl().matches("/audio/mock-\\d+\\.mp3"));
        assertEquals(server.baseUrl() + "/tts" + r.getAudioUrl(), r.getAbsoluteUrl());
        assertEquals("1.348", r.getDuration());
        assertEquals("wav", r.getFormat());
        assertEquals(1, r.getAttempts());
        assertFalse(r.isReplayed());
        assertEquals(0, new JSONObject(r.getRawJson()).getInt("code"));
        List<JSONObject> posts = log("POST", "/api/v1/tts/generate");
        assertEquals(1, posts.size());
        assertEquals(r.getIdempotencyKey(), posts.get(0).getString("idempotencyKey"));
        assertEquals("application/json; charset=utf-8", posts.get(0).getString("contentType"));
        assertEquals("yugu-stkouyu-compat/2.2.0", posts.get(0).getString("userAgent"));
        JSONObject billing = server.billing();
        assertEquals(1, billing.getInt("billed"));
        assertEquals("tts", billing.getJSONArray("records").getJSONObject(0).getString("op"));
    }

    @Test
    public void ttsRetryKeepsTheKeyAndIsBilledOnce() throws Exception {
        server.fault("/api/v1/tts/generate", "status:503");
        TtsResult r = offMain(() -> tts().tts(new TtsRequest("你好"), null));
        assertEquals(2, r.getAttempts());
        List<JSONObject> posts = log("POST", "/api/v1/tts/generate");
        assertEquals(2, posts.size());
        assertEquals("status:503", posts.get(0).getString("fault"));
        for (JSONObject p : posts) {
            assertEquals(r.getIdempotencyKey(), p.getString("idempotencyKey"));
        }
        JSONObject billing = server.billing();
        assertEquals(1, billing.getInt("billed"));
        assertEquals(1, billing.getJSONObject("byKey").getInt(r.getIdempotencyKey()));
    }

    @Test
    public void ttsWithTheSameKeyIsReplayed() throws Exception {
        TtsOptions o = new TtsOptions().setIdempotencyKey("lesson-7-sentence-3");
        TtsResult first = offMain(() -> tts().tts(new TtsRequest("你好").setVoice("xiaofeng"), o));
        TtsResult second = offMain(() -> tts().tts(new TtsRequest("你好").setVoice("xiaofeng"), o));
        assertFalse(first.isReplayed());
        assertTrue(second.isReplayed());
        assertEquals(first.getAudioUrl(), second.getAudioUrl());
        assertEquals(1, server.billing().getInt("billed"));
        // the same key for another request is a conflict the platform reports
        YuguApiException e = apiError(() -> tts().tts(new TtsRequest("再见"), o));
        assertEquals(40903, e.getCode());
        assertEquals(409, e.getHttpStatus());
    }

    @Test
    public void ttsErrorsCarryThePlatformCode() throws Exception {
        TtsClient wrong = TtsClient.create(MockServer.APP_KEY, "not-the-secret", server.baseUrl());
        YuguApiException e = apiError(() -> wrong.tts(new TtsRequest("你好"), null));
        assertEquals(2003, e.getCode());
        assertEquals(401, e.getHttpStatus());
        assertEquals("签名验证失败", e.getMessage());
        TtsClient unknown = TtsClient.create("no-such-key", "x", server.baseUrl());
        assertEquals(2010, apiError(() -> unknown.tts(new TtsRequest("你好"), null)).getCode());
        assertEquals("authentication errors are not retried", 2, log("POST", "/api/v1/tts/generate").size());
    }

    @Test
    public void networkFailureIs20009() throws Exception {
        CompatConfig.setSleeper((token, ms) -> !token.isCancelled());
        TtsClient closed = TtsClient.create(MockServer.APP_KEY, MockServer.SECRET, "http://127.0.0.1:1");
        YuguApiException e = apiError(() -> closed.tts(new TtsRequest("你好"), null));
        assertEquals(20009, e.getCode());
        assertEquals(0, e.getHttpStatus());
        assertTrue(e.getMessage(), e.getMessage().contains("(attempts 3)"));
        ReportClient closedReports = ReportClient.create(MockServer.APP_KEY, MockServer.SECRET, "http://127.0.0.1:1");
        assertEquals(20009, apiError(() -> closedReports.getReport("eval_1", null)).getCode());
    }

    // ================================================================ report

    @Test
    public void reportOfTheNewShape() throws Exception {
        String id = freshId("");
        ReportResult r = offMain(() -> reports().getReport(id, null));
        assertEquals(id, r.getRecordId());
        assertEquals("final", r.getReportStatus());
        assertTrue(r.isFinal());
        assertEquals("mock report", r.getSummary());
        assertEquals(Collections.singletonList("“清”的后鼻音收得再完整一些。"), r.getSuggestions());
        assertEquals(93.7, r.getOverall(), 1e-9);
        assertEquals(100, r.getScore().getInt("integrity"));
        assertEquals("读得完整。", r.getReport().getJSONObject("dimensions").getString("integrity"));
        assertEquals("“清”的后鼻音收得再完整一些。",
                r.getReport().getJSONObject("dimensionSuggestions").getJSONArray("accuracy").getString(0));
        JSONObject insight = r.getReport().getJSONArray("skillInsights").getJSONObject(0);
        assertEquals("情景再现", insight.getString("aspect"));
        assertEquals("白毛浮绿水", insight.getString("quote"));
        assertEquals("medium", insight.getString("confidence"));
        assertEquals("rest", r.getTiming().getString("channel"));
        assertEquals(2610, r.getTiming().getLong("totalMs"));
        assertEquals(id, new JSONObject(r.getDataJson()).getString("recordId"));
        List<JSONObject> gets = log("GET", "/api/v1/report/");
        assertEquals(1, gets.size());
        assertFalse("a read carries no key", gets.get(0).has("idempotencyKey") && !gets.get(0).isNull("idempotencyKey"));
        assertEquals("a lookup is not billed", 0, server.billing().getInt("billed"));
        List<String> info = new ArrayList<>();
        synchronized (lines) {
            for (String l : lines) {
                if (l.startsWith("3 report ")) {
                    info.add(l);
                }
                assertFalse(l, l.contains("mock report") || l.contains(MockServer.SECRET));
            }
        }
        assertEquals(1, info.size());
        assertTrue(info.get(0), info.get(0).matches("3 report recordId=" + id + " status=final lookups=1 attempts=1 ms=\\d+"));
    }

    @Test
    public void waitForFinalPollsUntilTheAiDraftArrives() throws Exception {
        String id = freshId("pending_");
        long t0 = System.nanoTime();
        ReportResult r = offMain(() -> reports().getReport(id, new ReportOptions().setWaitForFinalMs(10000)));
        long ms = (System.nanoTime() - t0) / 1000000L;
        assertTrue(r.isFinal());
        assertEquals("mock report", r.getSummary());
        assertEquals(3, r.getLookups());
        assertEquals(3, log("GET", "/api/v1/report/" + id).size());
        assertTrue("lookups one second apart: " + ms, ms >= 1900 && ms < 8000);
    }

    @Test
    public void pendingWithoutWaitingOrWhenTheTimeRunsOut() throws Exception {
        String id = freshId("pending_");
        ReportResult once = offMain(() -> reports().getReport(id, null));
        assertEquals("pending", once.getReportStatus());
        assertTrue(once.isPending());
        assertFalse(once.isFinal());
        assertEquals("即时稿：整体读得流畅。", once.getSummary());
        assertEquals(Arrays.asList("注意后鼻音。"), once.getSuggestions());
        assertEquals(1, once.getLookups());
        String other = freshId("pending_");
        ReportResult shortWait = offMain(() -> reports().getReport(other, new ReportOptions().setWaitForFinalMs(1500)));
        assertTrue("still pending is a normal result", shortWait.isPending());
        assertEquals(2, shortWait.getLookups());
    }

    @Test
    public void unknownRecordIs40001() throws Exception {
        YuguApiException e = apiError(() -> reports().getReport("abc", new ReportOptions().setWaitForFinalMs(3000)));
        assertEquals(40001, e.getCode());
        assertEquals(400, e.getHttpStatus());
        assertEquals("评测记录不存在: abc", e.getMessage());
        assertEquals(1, log("GET", "/api/v1/report/").size());
    }

    @Test
    public void clientsFromTheInitialisedEngine() throws Exception {
        SkEgnManager m = SkEgnManager.getInstance(app);
        m.initEngine(MockServer.APP_KEY, MockServer.SECRET, "it-user", EngineSetting.getInstance(app).setServerAddress(server.baseUrl()));
        Main.idle();
        String id = freshId("");
        assertEquals(server.baseUrl(), ReportClient.create().getBaseUrl());
        ReportResult r = offMain(() -> ReportClient.create().getReport(id, null));
        assertTrue(r.isFinal());
        TtsResult t = offMain(() -> TtsClient.create().tts(new TtsRequest("hello").setLanguage("en-US").setVoice("female"), null));
        assertEquals(server.baseUrl() + "/tts" + t.getAudioUrl(), t.getAbsoluteUrl());
    }

    @Test
    public void asyncCallsAgainstTheMock() throws Exception {
        ReportClientTest.Recorder report = new ReportClientTest.Recorder();
        reports().getReportAsync(freshId("pending_"), new ReportOptions().setWaitForFinalMs(5000), report);
        TtsClientTest.Recorder speech = new TtsClientTest.Recorder();
        tts().ttsAsync(new TtsRequest("你好"), null, speech);
        Main.await("both callbacks", 15000, () -> report.calls > 0 && speech.calls > 0);
        assertTrue(report.onMain && speech.onMain);
        assertTrue(report.result.isFinal());
        assertEquals(3, report.result.getLookups());
        assertTrue(speech.result.getAbsoluteUrl().startsWith(server.baseUrl() + "/tts/audio/"));
    }

    @Test
    public void signatureOfTheEmptySetIsWhatThePlatformChecks() throws Exception {
        // the same call through the internal client: GET with the empty signed set
        CompatClient.ApiRequest req = new CompatClient.ApiRequest();
        req.baseUrl = server.baseUrl();
        req.path = "/api/v1/report/" + freshId("");
        req.appKey = MockServer.APP_KEY;
        req.secretKey = MockServer.SECRET;
        CompatClient.Result r = CompatClient.fromConfig().executeApi(req, new CancelToken());
        assertEquals("final", new JSONObject(r.body).getJSONObject("data").getString("reportStatus"));
    }
}
