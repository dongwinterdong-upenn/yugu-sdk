package com.shengzhiai.yugu.stcompat;

import com.shengzhiai.yugu.stcompat.internal.CancelToken;
import com.shengzhiai.yugu.stcompat.internal.CompatClient;
import com.shengzhiai.yugu.stcompat.internal.CompatConfig;
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

import java.net.ConnectException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import static com.shengzhiai.yugu.stcompat.TtsClientTest.apiError;
import static com.shengzhiai.yugu.stcompat.TtsClientTest.offMain;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * ReportClient (SDK 2.2.0) on the fake transport: lookup, parsing, waiting for the final draft
 * (through pending and, SDK 2.3.0, partial), errors, log.
 */
@RunWith(RobolectricTestRunner.class)
public class ReportClientTest extends CompatTestBase {
    static final String FINAL_DATA = "{\"recordId\":\"eval_3fb45f4c8e71\",\"score\":{\"overall\":88.5,\"integrity\":100,"
            + "\"feedback\":\"好\"},\"report\":{\"source\":\"llm\",\"summary\":\"整体读得流畅。\",\"dimensions\":{\"accuracy\":\"准\"},"
            + "\"dimensionSuggestions\":{\"accuracy\":[\"注意后鼻音\"]},\"suggestions\":[\"注意后鼻音\",7,null,\"放慢语速\"],"
            + "\"skillInsights\":[{\"aspect\":\"情景再现\",\"observation\":\"读出了画面感。\",\"quote\":\"白毛浮绿水\","
            + "\"confidence\":\"medium\"}]},\"reportStatus\":\"final\",\"timing\":{\"receivedAt\":1791886000000,"
            + "\"totalMs\":2610,\"engineMs\":2420,\"platformMs\":190,\"audioBytes\":96044,\"channel\":\"ws\"}}";

    private final List<String> lines = Collections.synchronizedList(new ArrayList<String>());

    @Before
    public void capture() {
        YuguCompat.setLogLevel(YuguCompat.LOG_INFO);
        YLog.setSink((level, tag, msg, err) -> lines.add(level + " " + msg));
    }

    static String answer(String data) {
        return "{\"code\":0,\"message\":\"success\",\"data\":" + data + ",\"timestamp\":1791886000000}";
    }

    static String status(String recordId, String reportStatus) {
        return answer("{\"recordId\":\"" + recordId + "\",\"score\":{\"overall\":70},\"report\":{\"summary\":\"s-" + reportStatus
                + "\",\"suggestions\":[]},\"reportStatus\":\"" + reportStatus + "\"}");
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

    @Test
    public void lookupIsAnEmptySignedGetAndTheAnswerIsParsed() throws Exception {
        transport.respond(200, answer(FINAL_DATA));
        ReportClient c = ReportClient.create("ak", "sk");
        ReportResult r = offMain(() -> c.getReport(" eval_3fb45f4c8e71 ", null));
        FakeTransport.Call call = transport.calls().get(0);
        assertEquals("GET", call.request.method);
        assertEquals("https://open.shengzhiai.com/api/v1/report/eval_3fb45f4c8e71", call.request.url);
        assertNull(call.request.body);
        assertNull(call.request.bytes);
        assertNull("a read carries no key", call.header("Idempotency-Key"));
        assertEquals("CONTRACT 2.3: the empty set", Signer.sign(new HashMap<String, String>(), "sk"), call.header("X-Signature"));
        assertEquals("ak", call.header("X-App-Key"));
        assertEquals("eval_3fb45f4c8e71", r.getRecordId());
        assertEquals("final", r.getReportStatus());
        assertTrue(r.isFinal());
        assertFalse(r.isPending());
        assertEquals("整体读得流畅。", r.getSummary());
        assertEquals("strings only, in order", Arrays.asList("注意后鼻音", "放慢语速"), r.getSuggestions());
        assertEquals(88.5, r.getOverall(), 1e-9);
        assertEquals("llm", r.getReport().getString("source"));
        assertEquals("情景再现", r.getReport().getJSONArray("skillInsights").getJSONObject(0).getString("aspect"));
        assertEquals("medium", r.getReport().getJSONArray("skillInsights").getJSONObject(0).getString("confidence"));
        assertEquals("准", r.getReport().getJSONObject("dimensions").getString("accuracy"));
        assertEquals("好", r.getScore().getString("feedback"));
        assertEquals("ws", r.getTiming().getString("channel"));
        assertEquals(2610, r.getTiming().getInt("totalMs"));
        assertEquals(FINAL_DATA, r.getDataJson());
        assertEquals(1, r.getLookups());
        assertTrue(r.toString(), r.toString().contains("reportStatus=final") && !r.toString().contains("流畅"));
        try {
            r.getSuggestions().add("x");
            fail();
        } catch (UnsupportedOperationException expected) {
            // read only
        }
    }

    @Test
    public void sparseAnswersAndOddValues() throws Exception {
        ReportClient c = ReportClient.create("ak", "sk", "http://10.0.0.2:81");
        transport.respond(200, answer("{\"overall\":\"91.5\",\"report\":{\"summary\":null,\"suggestions\":\"x\"},"
                + "\"reportStatus\":null}"));
        ReportResult r = offMain(() -> c.getReport("eval x/1", new ReportOptions()));
        assertEquals("http://10.0.0.2:81/api/v1/report/eval%20x%2F1", transport.calls().get(0).request.url);
        assertEquals("the asked recordId when the answer has none", "eval x/1", r.getRecordId());
        assertNull(r.getReportStatus());
        assertFalse(r.isFinal());
        assertFalse(r.isPending());
        assertNull(r.getSummary());
        assertTrue(r.getSuggestions().isEmpty());
        assertEquals("data.overall when score has none", 91.5, r.getOverall(), 1e-9);
        assertNull(r.getScore());
        assertNull(r.getTiming());
        transport.respond(200, answer("{\"recordId\":\"\",\"score\":{\"overall\":\"n/a\"},\"overall\":true}"));
        ReportResult r2 = offMain(() -> c.getReport("eval_2", null));
        assertEquals("eval_2", r2.getRecordId());
        assertNull(r2.getOverall());
        assertNull(r2.getReport());
        transport.respond(200, answer("{\"score\":{\"overall\":\"abc\"},\"reportStatus\":\"none\"}"));
        ReportResult r3 = offMain(() -> c.getReport("eval_3", null));
        assertNull(r3.getOverall());
        assertEquals(ReportResult.STATUS_NONE, r3.getReportStatus());
        assertEquals(ReportResult.STATUS_INSTANT, "instant");
    }

    @Test
    public void waitForFinalLooksUpEverySecondWhilePending() throws Exception {
        transport.respond(200, status("eval_p", "pending"));
        transport.respond(200, status("eval_p", "pending"));
        transport.respond(200, status("eval_p", "final"));
        ReportClient c = ReportClient.create("ak", "sk");
        ReportResult r = offMain(() -> c.getReport("eval_p", new ReportOptions().setWaitForFinalMs(20000)));
        assertTrue(r.isFinal());
        assertEquals("s-final", r.getSummary());
        assertEquals(3, r.getLookups());
        assertEquals(3, transport.count());
        assertEquals("two pauses between three lookups", 2, sleeps.size());
        for (long ms : sleeps) {
            assertTrue("the rest of one second after the lookup: " + ms, ms > 500 && ms <= 1000);
        }
        List<String> info = linesStartingWith("3 report ");
        assertEquals(String.valueOf(lines), 1, info.size());
        assertTrue(info.get(0), info.get(0).matches("3 report recordId=eval_p status=final lookups=3 attempts=3 ms=\\d+"));
    }

    @Test
    public void pendingIsANormalResultWithoutWaitingOrWhenTheTimeIsUsedUp() throws Exception {
        ReportClient c = ReportClient.create("ak", "sk");
        transport.defaultBody(status("eval_p", "pending"));
        ReportResult once = offMain(() -> c.getReport("eval_p", null));
        assertTrue(once.isPending());
        assertEquals("s-pending", once.getSummary());
        assertEquals(1, transport.count());
        ReportResult zero = offMain(() -> c.getReport("eval_p", new ReportOptions().setWaitForFinalMs(0)));
        assertEquals(1, zero.getLookups());
        // 2500 ms: lookups at 0, 1000 and 2000 ms, the next one would start after the time
        ReportResult waited = offMain(() -> c.getReport("eval_p", new ReportOptions().setWaitForFinalMs(2500)));
        assertTrue(waited.isPending());
        assertEquals(3, waited.getLookups());
        assertEquals(5, transport.count());
        // less than a second: one lookup
        assertEquals(1, offMain(() -> c.getReport("eval_p", new ReportOptions().setWaitForFinalMs(999))).getLookups());
        // other statuses end the wait at once
        transport.respond(200, status("eval_i", "instant"));
        ReportResult instant = offMain(() -> c.getReport("eval_i", new ReportOptions().setWaitForFinalMs(20000)));
        assertEquals("instant", instant.getReportStatus());
        assertEquals(1, instant.getLookups());
        try {
            new ReportOptions().setWaitForFinalMs(-1);
            fail();
        } catch (IllegalArgumentException expected) {
            // ok
        }
        assertEquals(15000, new ReportOptions().setWaitForFinalMs(15000).getWaitForFinalMs());
    }

    /** SDK 2.3.0: partial (summary and suggestions ready, comments per dimension not yet) keeps the wait going. */
    @Test
    public void waitForFinalPollsThroughPartialAndStopsAtFinal() throws Exception {
        transport.respond(200, status("eval_q", "pending"));
        transport.respond(200, status("eval_q", "partial"));
        transport.respond(200, status("eval_q", "partial"));
        transport.respond(200, status("eval_q", "final"));
        transport.respond(200, status("eval_q", "pending"));
        ReportClient c = ReportClient.create("ak", "sk");
        ReportResult r = offMain(() -> c.getReport("eval_q", new ReportOptions().setWaitForFinalMs(20000)));
        assertTrue(r.isFinal());
        assertEquals("final", r.getReportStatus());
        assertEquals("s-final", r.getSummary());
        assertEquals(4, r.getLookups());
        assertEquals("no lookup after final", 4, transport.count());
        assertEquals("three pauses between four lookups", 3, sleeps.size());
        List<String> info = linesStartingWith("3 report ");
        assertEquals(String.valueOf(lines), 1, info.size());
        assertTrue(info.get(0), info.get(0).matches("3 report recordId=eval_q status=final lookups=4 attempts=4 ms=\\d+"));
    }

    @Test
    public void partialIsNotFinalAndIsReturnedWhenTheTimeIsUsedUp() throws Exception {
        ReportClient c = ReportClient.create("ak", "sk");
        transport.defaultBody(status("eval_q", "partial"));
        ReportResult once = offMain(() -> c.getReport("eval_q", null));
        assertEquals("partial", once.getReportStatus());
        assertFalse(once.isFinal());
        assertFalse(once.isPending());
        assertEquals("s-partial", once.getSummary());
        assertEquals(1, once.getLookups());
        // 2500 ms: lookups at 0, 1000 and 2000 ms, then the latest partial answer
        ReportResult waited = offMain(() -> c.getReport("eval_q", new ReportOptions().setWaitForFinalMs(2500)));
        assertEquals("partial", waited.getReportStatus());
        assertFalse(waited.isFinal());
        assertEquals(3, waited.getLookups());
        assertEquals(4, transport.count());
        List<String> info = linesStartingWith("3 report recordId=eval_q status=partial lookups=3 ");
        assertEquals(String.valueOf(lines), 1, info.size());
        // a partial answer then a status that is neither pending nor partial ends the wait
        transport.respond(200, status("eval_i", "partial"));
        transport.respond(200, status("eval_i", "instant"));
        ReportResult instant = offMain(() -> c.getReport("eval_i", new ReportOptions().setWaitForFinalMs(20000)));
        assertEquals("instant", instant.getReportStatus());
        assertEquals(2, instant.getLookups());
        // the asynchronous call waits the same way
        transport.respond(200, status("eval_a", "partial"));
        transport.respond(200, status("eval_a", "final"));
        Recorder rec = new Recorder();
        c.getReportAsync("eval_a", new ReportOptions().setWaitForFinalMs(3000), rec);
        Main.await("onSuccess", 5000, () -> rec.calls > 0);
        assertTrue(rec.onMain);
        assertTrue(rec.result.isFinal());
        assertEquals(2, rec.result.getLookups());
    }

    @Test
    public void realPausesWithTheRealSleeper() throws Exception {
        CompatConfig.setSleeper(null);
        transport.respond(200, status("eval_p", "pending"));
        transport.respond(200, status("eval_p", "final"));
        ReportClient c = ReportClient.create("ak", "sk");
        long t0 = System.nanoTime();
        ReportResult r = offMain(() -> c.getReport("eval_p", new ReportOptions().setWaitForFinalMs(5000)));
        long ms = (System.nanoTime() - t0) / 1000000L;
        assertTrue(r.isFinal());
        assertTrue("the second lookup starts about 1000 ms after the first: " + ms, ms >= 950 && ms < 4000);
    }

    @Test
    public void aFailedLaterLookupReturnsTheLatestAnswer() throws Exception {
        transport.respond(200, status("eval_p", "pending"));
        for (int i = 0; i < 3; i++) {
            transport.fail(new ConnectException("Connection refused"));
        }
        ReportClient c = ReportClient.create("ak", "sk");
        ReportResult r = offMain(() -> c.getReport("eval_p", new ReportOptions().setWaitForFinalMs(10000)));
        assertTrue(r.isPending());
        assertEquals(2, r.getLookups());
        List<String> warn = linesStartingWith("2 report recordId=eval_p lookup 2 failed with code 20009");
        assertEquals(String.valueOf(lines), 1, warn.size());
        List<String> info = linesStartingWith("3 report ");
        assertEquals(1, info.size());
        assertTrue(info.get(0), info.get(0).matches(
                "3 report recordId=eval_p status=pending lookups=2 attempts=4 ms=\\d+ lastError=20009"));
    }

    @Test
    public void aFailedFirstLookupThrows() throws Exception {
        ReportClient c = ReportClient.create("ak", "sk");
        transport.respond(400, FakeTransport.error(40001, "评测记录不存在: eval_none"));
        YuguApiException e = apiError(() -> c.getReport("eval_none", new ReportOptions().setWaitForFinalMs(5000)));
        assertEquals(40001, e.getCode());
        assertEquals(400, e.getHttpStatus());
        assertEquals("评测记录不存在: eval_none", e.getMessage());
        assertEquals("not retried", 1, transport.count());
        List<String> info = linesStartingWith("3 report ");
        assertTrue(info.get(0), info.get(0).matches(
                "3 report recordId=eval_none status=error code=40001 http=400 lookups=1 attempts=1 ms=\\d+"));
        transport.respond(401, FakeTransport.error(2010, "api key 不存在"));
        assertEquals(2010, apiError(() -> c.getReport("eval_x", null)).getCode());
        transport.respond(500, FakeTransport.error(50000, "boom"));
        transport.respond(200, answer(FINAL_DATA));
        ReportResult retried = offMain(() -> c.getReport("eval_3fb45f4c8e71", null));
        assertEquals("retried like every call", "eval_3fb45f4c8e71", retried.getRecordId());
        transport.respond(200, "{\"code\":0,\"data\":[1]}");
        assertEquals(90005, apiError(() -> c.getReport("eval_x", null)).getCode());
    }

    @Test
    public void argumentsThreadsAndTheEngineAccount() throws Exception {
        try {
            ReportClient.create();
            fail();
        } catch (IllegalStateException expected) {
            assertEquals("引擎未初始化", expected.getMessage());
        }
        SkEgnManager m = SkEgnManager.getInstance(app);
        m.initEngine("engine-key", "engine-secret", "u", EngineSetting.getInstance(app).setServerAddress("ws://10.9.9.9:8080"));
        Main.idle();
        ReportClient c = ReportClient.create();
        assertEquals("http://10.9.9.9:8080", c.getBaseUrl());
        try {
            c.getReport("eval_1", null);
            fail();
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("getReportAsync"));
        }
        for (String bad : new String[] {null, "", "  "}) {
            try {
                offMain(() -> c.getReport(bad, null));
                fail();
            } catch (IllegalArgumentException expected) {
                assertEquals("recordId 不能为空", expected.getMessage());
            }
        }
        try {
            c.getReportAsync("eval_1", null, null);
            fail();
        } catch (IllegalArgumentException expected) {
            // ok
        }
        assertEquals(0, transport.count());
        transport.respond(200, answer(FINAL_DATA));
        offMain(() -> c.getReport("eval_1", null));
        FakeTransport.Call call = transport.calls().get(0);
        assertEquals("http://10.9.9.9:8080/api/v1/report/eval_1", call.request.url);
        assertEquals("engine-key", call.header("X-App-Key"));
        assertEquals(Signer.sign(new HashMap<String, String>(), "engine-secret"), call.header("X-Signature"));
        assertEquals(ReportClient.create("k", "s", "https://a.example.com").getBaseUrl(), "https://a.example.com");
    }

    /** Records the async outcome and the thread it arrived on. */
    static final class Recorder implements ReportClient.Callback {
        volatile ReportResult result;
        volatile YuguApiException error;
        volatile int calls;
        volatile boolean onMain = true;

        @Override
        public void onSuccess(ReportResult r) {
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
        ReportClient c = ReportClient.create("ak", "sk");
        transport.respond(200, status("eval_p", "pending"));
        transport.respond(200, status("eval_p", "final"));
        Recorder ok = new Recorder();
        c.getReportAsync("eval_p", new ReportOptions().setWaitForFinalMs(3000), ok);
        Main.await("onSuccess", 5000, () -> ok.calls > 0);
        assertTrue(ok.onMain);
        assertTrue(ok.result.isFinal());
        assertEquals(2, ok.result.getLookups());
        transport.respond(400, FakeTransport.error(40001, "评测记录不存在: abc"));
        Recorder bad = new Recorder();
        c.getReportAsync("abc", null, bad);
        Main.await("onFailure", 5000, () -> bad.calls > 0);
        assertTrue(bad.onMain);
        assertEquals(40001, bad.error.getCode());
        Main.settle(30);
        assertEquals(1, ok.calls);
        assertEquals(1, bad.calls);
    }

    @Test
    public void executeApiChecksTheEnvelope() throws Exception {
        CompatClient client = CompatClient.fromConfig();
        CompatClient.ApiRequest req = new CompatClient.ApiRequest();
        req.baseUrl = "https://h.example.com/";
        req.path = "/api/v1/report/x";
        req.appKey = "ak";
        req.secretKey = "sk";
        assertEquals("https://h.example.com/api/v1/report/x", req.url());
        transport.respond(200, answer("{}"));
        CompatClient.Result r = client.executeApi(req, null);
        assertEquals(1, r.attempts);
        assertSame(transport.calls().get(0).request.headers.get("Idempotency-Key"), null);
        transport.respond(200, "{\"code\":\"0\",\"data\":{\"a\":1}}");
        assertEquals("{\"a\":1}", new JSONObject(client.executeApi(req, new CancelToken()).body).getJSONObject("data").toString());
    }
}
