package com.stkouyu;

import com.shengzhiai.yugu.stcompat.internal.CancelToken;
import com.shengzhiai.yugu.stcompat.internal.CompatConfig;
import com.shengzhiai.yugu.stcompat.internal.ErrorTable;
import com.shengzhiai.yugu.stcompat.internal.HttpTransport;
import com.shengzhiai.yugu.stcompat.internal.Multipart;
import com.shengzhiai.yugu.stcompat.internal.UrlConnectionTransport;
import com.shengzhiai.yugu.stcompat.internal.WsClient;
import com.shengzhiai.yugu.stcompat.testing.CompatTestBase;
import com.shengzhiai.yugu.stcompat.testing.Events;
import com.shengzhiai.yugu.stcompat.testing.Fixtures;
import com.shengzhiai.yugu.stcompat.testing.Main;
import com.stkouyu.setting.EngineSetting;
import com.stkouyu.setting.RecordSetting;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * End-to-end cases against the real platform with sandbox keys (acceptance A-05-2), written the
 * way a Shengtong customer writes them: initEngine with the keys, the server address set through
 * EngineSetting.setServerAddress, startRecord fed through feed(), the public onScore JSON.
 *
 * <p>Runs only when YUGU_SANDBOX_APPKEY and YUGU_SANDBOX_SECRET are set (YUGU_SANDBOX_BASE
 * defaults to https://open.shengzhiai.com); otherwise every case is skipped, so push builds never
 * call the platform. At most 9 platform calls per run (REST requests and realtime connections):
 * one retry per evaluation and a counting transport and connector that refuse further calls. Key
 * values are never printed: failure messages carry errId, error and recordId only, never the
 * envelope (it contains applicationId). Since 2.1.1 sessions stream over the realtime channel by
 * default; the cases that inspect the REST form switch streaming upload off.
 */
@RunWith(RobolectricTestRunner.class)
public class SandboxIntegrationTest extends CompatTestBase {
    static final int MAX_CALLS = 9;
    private static final String APP_KEY = env("YUGU_SANDBOX_APPKEY");
    private static final String SECRET = env("YUGU_SANDBOX_SECRET");
    private static final String BASE = env("YUGU_SANDBOX_BASE") != null ? env("YUGU_SANDBOX_BASE") : "https://open.shengzhiai.com";
    private static final AtomicInteger CALLS = new AtomicInteger();

    private final Events ev = new Events();
    private final List<HttpTransport.Request> requests = Collections.synchronizedList(new ArrayList<HttpTransport.Request>());
    private final AtomicInteger wsConnects = new AtomicInteger();
    /** idempotencyKey of every realtime connection, from the handshake query (never the signature). */
    private final List<String> wsKeys = Collections.synchronizedList(new ArrayList<String>());
    private final List<String> logLines = Collections.synchronizedList(new ArrayList<String>());

    private static String env(String name) {
        String v = System.getenv(name);
        return v == null || v.trim().isEmpty() ? null : v.trim();
    }

    @Before
    public void sandboxKeysRequired() {
        Assume.assumeTrue("YUGU_SANDBOX_APPKEY and YUGU_SANDBOX_SECRET are not set: the sandbox cases call the real "
                + "platform and are skipped", APP_KEY != null && SECRET != null);
        CompatConfig.setSleeper(null);
        final HttpTransport real = new UrlConnectionTransport();
        CompatConfig.setTransport(new HttpTransport() {
            @Override
            public Response execute(Request request, CancelToken token) throws IOException {
                if (CALLS.incrementAndGet() > MAX_CALLS) {
                    throw new IOException("sandbox call budget of " + MAX_CALLS + " platform calls per run is used up");
                }
                requests.add(request);
                return real.execute(request, token);
            }
        });
        // realtime connections count against the same budget
        CompatConfig.setWsConnector((url, headers, connectTimeoutMs, readTimeoutMs, listener) -> {
            if (CALLS.incrementAndGet() > MAX_CALLS) {
                throw new IOException("sandbox call budget of " + MAX_CALLS + " platform calls per run is used up");
            }
            wsConnects.incrementAndGet();
            java.util.regex.Matcher k = java.util.regex.Pattern.compile("[?&]idempotencyKey=([0-9a-f]{32})").matcher(url);
            wsKeys.add(k.find() ? k.group(1) : "");
            return WsClient.CONNECTOR.connect(url, headers, connectTimeoutMs, readTimeoutMs, listener);
        });
        // one retry at most per evaluation keeps the run within the call budget
        YuguCompat.setRetryPolicy(1, 500, 2.0, 2000, 0.3);
    }

    @AfterClass
    public static void callBudget() {
        System.out.println("sandbox: platform calls in this run: " + CALLS.get() + " of at most " + MAX_CALLS);
        assertTrue("more platform calls than the budget", CALLS.get() <= MAX_CALLS);
    }

    private SkEgnManager init(String secret) {
        return init(APP_KEY, secret);
    }

    private SkEgnManager init(String appKey, String secret) {
        SkEgnManager m = SkEgnManager.getInstance(app);
        EngineSetting es = EngineSetting.getInstance(app).setServerAddress(BASE).setOnInitEngineListener(ev.init);
        m.initEngine(appKey, secret, "sandbox-android-stcompat", es);
        Main.idle();
        assertTrue("initEngine did not succeed", ev.has("onInitEngineSuccess"));
        return m;
    }

    /** Stream mode: the whole fixture file goes through feed(), then stopRecord. */
    private JSONObject evaluate(SkEgnManager m, RecordSetting s) {
        s.setIsStream(true);
        m.startRecord(s, ev.recorder);
        m.feed(Fixtures.bytes("spec/fixtures/audio/zh_short.wav"));
        m.stopRecord();
        Main.await("onScore from the sandbox", 180000, () -> ev.json != null);
        assertTrue("callbacks off the main thread", ev.offMain.isEmpty());
        try {
            return new JSONObject(ev.json);
        } catch (JSONException e) {
            throw new AssertionError("onScore JSON does not parse");
        }
    }

    /** errId, error and recordId only; the envelope also carries applicationId, which is never printed. */
    private static String describe(JSONObject env) {
        return "errId=" + env.opt("errId") + " error=" + env.opt("error") + " recordId=" + env.opt("recordId");
    }

    /** Shape of a result for failure messages: key names and array lengths, no values. */
    private static String shape(JSONObject result) {
        StringBuilder sb = new StringBuilder();
        java.util.Iterator<String> keys = result.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            Object v = result.opt(k);
            sb.append(k);
            if (v instanceof JSONArray) {
                sb.append('[').append(((JSONArray) v).length()).append(']');
            }
            sb.append(' ');
        }
        return sb.toString().trim();
    }

    /** Every sentences[].details[] item reads like the Shengtong sample: overall on the item. */
    private static int checkDetails(JSONObject result) throws JSONException {
        JSONArray sentences = result.optJSONArray("sentences");
        int details = 0;
        for (int i = 0; sentences != null && i < sentences.length(); i++) {
            JSONArray d = sentences.getJSONObject(i).optJSONArray("details");
            for (int j = 0; d != null && j < d.length(); j++) {
                JSONObject item = d.getJSONObject(j);
                int overall = item.getInt("overall");
                assertEquals(item.getJSONObject("scores").getInt("overall"), overall);
                assertNotNull(item.getString("word"));
                details++;
            }
        }
        return details;
    }

    private void assertSuccessEnvelope(JSONObject r) throws JSONException {
        assertFalse("platform answered with an error: " + describe(r), r.has("errId"));
        assertTrue("tokenId", r.getString("tokenId").matches("[0-9a-f]{32}"));
        assertFalse("recordId", r.getString("recordId").isEmpty());
        assertTrue("applicationId is not the appKey", APP_KEY.equals(r.getString("applicationId")));
        assertEquals(1, r.getInt("eof"));
        assertTrue("dtLastResponse", r.has("dtLastResponse"));
        Object overall = r.getJSONObject("result").get("overall");
        assertTrue("result.overall is not numeric", overall instanceof Number);
        String key = requests.isEmpty() ? wsKeys.get(0) : requests.get(0).headers.get("Idempotency-Key");
        assertEquals(r.getString("tokenId"), key);
        assertFalse("the platform's timing object never reaches the app", r.has("timing"));
    }

    /** SDK log lines from now on, at the given level. */
    private void captureLog(int level) {
        YuguCompat.setLogLevel(level);
        com.shengzhiai.yugu.stcompat.internal.YLog.setSink((l, tag, msg, err) -> logLines.add(msg));
    }

    /** The INFO summary line of an evaluation. */
    private String summary(String token) {
        synchronized (logLines) {
            for (String l : logLines) {
                if (l.startsWith("timing tokenId=" + token + " ")) {
                    return l;
                }
            }
        }
        throw new AssertionError("no timing line for the evaluation");
    }

    private static long number(String line, String name) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(" " + java.util.regex.Pattern.quote(name) + "=(\\d+)").matcher(line);
        assertTrue(name + " in " + line, m.find());
        return Long.parseLong(m.group(1));
    }

    /** Case 1: sent.eval.cn, zh_short.wav, refText 今天天气很好. */
    @Test
    public void sentenceEvaluation() throws Exception {
        SkEgnManager m = init(SECRET);
        JSONObject r = evaluate(m, new RecordSetting(CoreType.CN_SENT_EVAL, "今天天气很好"));
        assertSuccessEnvelope(r);
        JSONObject result = r.getJSONObject("result");
        double overall = result.getDouble("overall");
        assertTrue(overall >= 0 && overall <= 100);
        assertTrue(new File(m.getLastRecordPath()).isFile());
        int details = checkDetails(result);
        assertTrue("no sentences[].details[] in the sentence result: " + shape(result), details > 0);
        JSONArray words = result.optJSONArray("words");
        System.out.println("sandbox sent.eval.cn: overall=" + overall + ", words=" + (words == null ? 0 : words.length())
                + ", details=" + details + ", attempts=" + requests.size());
    }

    /**
     * Case 2: para.eval.cn with the paragraph text of the platform fixtures; details[] carry
     * Shengtong-style overall.
     */
    @Test
    public void paragraphWordDetailsInShengtongShape() throws Exception {
        YuguCompat.setStreamUpload(false);
        SkEgnManager m = init(SECRET);
        JSONObject r = evaluate(m, new RecordSetting(CoreType.CN_PARA_EVAL, "今天天气很好。我们一起去公园散步。"));
        assertSuccessEnvelope(r);
        boolean wordScoresAsked = false;
        for (Multipart.Part p : requests.get(0).body.parts()) {
            wordScoresAsked |= "paragraph_need_word_score".equals(p.name) && "1".equals(p.value);
        }
        assertTrue("paragraph_need_word_score=1 was not sent", wordScoresAsked);
        JSONObject result = r.getJSONObject("result");
        JSONArray sentences = result.getJSONArray("sentences");
        assertTrue("no sentences in the paragraph result: " + shape(result), sentences.length() > 0);
        int details = checkDetails(result);
        assertTrue("no details in the paragraph result: " + shape(result), details > 0);
        System.out.println("sandbox para.eval.cn: overall=" + r.getJSONObject("result").get("overall")
                + ", sentences=" + sentences.length() + ", details=" + details + ", attempts=" + requests.size());
    }

    /** Case 3: an unknown appKey fails authentication, costs no evaluation and is not retried. */
    @Test
    public void unknownAppKeyIsAnAuthError() throws Exception {
        String tag = Long.toHexString(System.nanoTime());
        SkEgnManager m = init("sandbox-unknown-" + tag, "not-a-secret-" + tag);
        JSONObject r = evaluate(m, new RecordSetting(CoreType.CN_SENT_EVAL, "今天天气很好"));
        assertTrue("expected an error JSON", r.has("errId"));
        assertFalse(r.has("result"));
        int errId = r.getInt("errId");
        ErrorTable.Entry e = ErrorTable.ERRORS.get(errId);
        assertNotNull("errId " + errId + " is not a platform code", e);
        assertEquals("errId " + errId + " is not an authentication code", "AUTH", e.category);
        assertFalse(e.retryable);
        assertEquals("an authentication error must not be retried", 1, requests.size());
        assertEquals(1, r.getInt("eof"));
        System.out.println("sandbox unknown appKey: errId=" + errId + " (" + e.name + "), error=" + r.optString("error")
                + ", attempts=" + requests.size());
    }

    /**
     * Case 4: sent.eval.cn with setNeedAttachAudioUrlInResult(true): the onScore JSON carries the
     * platform's audioUrl, the recording also stays local. Only the host is printed, never the link.
     */
    @Test
    public void attachAudioUrlIsReturned() throws Exception {
        YuguCompat.setStreamUpload(false);
        SkEgnManager m = init(SECRET);
        JSONObject r = evaluate(m, new RecordSetting(CoreType.CN_SENT_EVAL, "今天天气很好").setNeedAttachAudioUrlInResult(true));
        assertSuccessEnvelope(r);
        boolean attachAsked = false;
        for (Multipart.Part p : requests.get(0).body.parts()) {
            attachAsked |= "attachAudioUrl".equals(p.name) && "1".equals(p.value);
        }
        assertTrue("attachAudioUrl=1 was not sent", attachAsked);
        assertTrue("no audioUrl in the onScore JSON: " + shape(r), r.has("audioUrl"));
        String url = r.getString("audioUrl");
        assertTrue("audioUrl is not an https link", url.startsWith("https://"));
        assertTrue(new File(m.getLastRecordPath()).isFile());
        System.out.println("sandbox attachAudioUrl: audioUrl host=" + new java.net.URI(url).getHost()
                + ", attempts=" + requests.size());
    }

    /**
     * Case 5: realtime_feedback 1 with the customer sentence (zh_progress.wav) fed at real-time
     * pace through setIsStream(true), as a Shengtong integrator does: eof 0 intermediate results
     * with the newly read characters arrive before the one eof 1 result, over the realtime channel.
     */
    @Test
    public void realtimeFeedbackWithTheCustomerSentence() throws Exception {
        String refText = "我是中国人，我是中国人。我们都是中国人。中华民族是一家人。";
        String units = "我是中国人我是中国人我们都是中国人中华民族是一家人";
        SkEgnManager m = init(SECRET);
        RecordSetting s = new RecordSetting(CoreType.CN_SENT_EVAL, refText);
        s.setRealtime_feedback(1);
        s.setIsStream(true);
        m.startRecord(s, ev.recorder);
        byte[] pcm = Fixtures.pcm("spec/fixtures/audio/zh_progress.wav");
        long t0 = System.nanoTime();
        for (int i = 0; i < pcm.length; i += 3200) {
            int n = Math.min(3200, pcm.length - i);
            byte[] chunk = new byte[n];
            System.arraycopy(pcm, i, chunk, 0, n);
            m.feed(chunk, n);
            // real time: 3200 bytes are 100 ms of 16 kHz 16 bit mono
            long due = t0 + (i + n) / 32L * 1000000L;
            long wait = (due - System.nanoTime()) / 1000000L;
            if (wait > 0) {
                Thread.sleep(wait);
            }
            Main.idle();
        }
        int whileReading = ev.intermediate().size();
        m.stopRecord();
        Main.await("final onScore from the sandbox", 180000, () -> ev.json != null);
        Main.settle(50);
        assertTrue("callbacks off the main thread", ev.offMain.isEmpty());
        JSONObject r = new JSONObject(ev.json);
        assertFalse("platform answered with an error: " + describe(r), r.has("errId"));
        assertEquals(1, r.getInt("eof"));
        assertTrue(r.getJSONObject("result").get("overall") instanceof Number);
        assertEquals("exactly one final", 1, ev.count("onScore"));
        assertEquals("the final is the last callback", "onScore", ev.log.get(ev.log.size() - 1));
        List<JSONObject> mid = ev.intermediate();
        assertTrue("no intermediate result with words before the final", mid.size() > 0);
        StringBuilder read = new StringBuilder();
        for (JSONObject o : mid) {
            assertEquals(0, o.getInt("eof"));
            assertEquals(r.getString("tokenId"), o.getString("tokenId"));
            assertEquals("", o.getString("recordId"));
            JSONObject res = o.getJSONObject("result");
            JSONArray w = res.getJSONArray("words");
            assertTrue(w.length() > 0);
            for (int i = 0; i < w.length(); i++) {
                read.append(w.getJSONObject(i).getString("word"));
            }
            assertEquals(read.length(), res.getInt("readUnits"));
            assertEquals(25, res.getJSONArray("progress").length());
        }
        assertTrue("newly read words in reference order: " + read, units.startsWith(read.toString()));
        assertTrue("realtime channel used", wsConnects.get() >= 1);
        assertEquals("no REST upload", 0, requests.size());
        System.out.println("sandbox realtime_feedback: intermediate results=" + mid.size() + " (" + whileReading
                + " while reading), words read=" + read.length() + " of 25, final overall="
                + r.getJSONObject("result").get("overall") + ", connections=" + wsConnects.get());
    }

    /**
     * Case 6 (SDK 2.1.1): the streaming default at real-time pace with the customer sentence, log
     * level DEBUG. The final frame comes compressed with permessage-deflate, the platform's timing
     * object arrives (timing=1 in the parameter frame) and is removed before onScore; the summary
     * line is printed (tokenId, times and sizes only).
     */
    @Test
    public void streamingDefaultCompressedFinalAndServerTiming() throws Exception {
        captureLog(YuguCompat.LOG_DEBUG);
        SkEgnManager m = init(SECRET);
        RecordSetting s = new RecordSetting(CoreType.CN_SENT_EVAL, "我是中国人，我是中国人。我们都是中国人。中华民族是一家人。");
        s.setIsStream(true);
        m.startRecord(s, ev.recorder);
        byte[] pcm = Fixtures.pcm("spec/fixtures/audio/zh_progress.wav");
        long t0 = System.nanoTime();
        for (int i = 0; i < pcm.length; i += 3200) {
            int n = Math.min(3200, pcm.length - i);
            byte[] chunk = new byte[n];
            System.arraycopy(pcm, i, chunk, 0, n);
            m.feed(chunk, n);
            long wait = (t0 + (i + n) / 32L * 1000000L - System.nanoTime()) / 1000000L;
            if (wait > 0) {
                Thread.sleep(wait);
            }
            Main.idle();
        }
        m.stopRecord();
        Main.await("final onScore from the sandbox", 180000, () -> ev.json != null);
        JSONObject r = new JSONObject(ev.json);
        assertSuccessEnvelope(r);
        assertEquals("no intermediate results without realtime_feedback", 0, ev.intermediate().size());
        assertEquals("no REST upload", 0, requests.size());
        String line = summary(r.getString("tokenId"));
        System.out.println("sandbox streaming default: " + line);
        assertTrue(line, line.contains(" mode=ws "));
        assertTrue("the final frame did not come compressed: " + line, line.contains(" deflate"));
        long finalBytes = number(line, "final");
        long wire = number(line, "wire");
        assertTrue(line, wire < finalBytes);
        assertTrue("no timing object in the final frame: " + line, line.contains(" server.total="));
        System.out.println("sandbox final frame: " + finalBytes + " bytes, " + wire + " bytes compressed on the wire ("
                + (wire * 100 / finalBytes) + " %), upload after stop " + number(line, "upload") + " ms");
        synchronized (logLines) {
            for (String l : logLines) {
                if (l.contains("engineStages") || l.contains(" ws.handshake")) {
                    System.out.println("sandbox debug: " + l);
                }
            }
        }
    }

    /**
     * Case 7 (SDK 2.1.1): streaming upload switched off, log level DEBUG: the REST answer carries
     * Server-Timing and the timing object (form field timing=1), neither reaches onScore.
     */
    @Test
    public void restUploadCarriesServerTimingWhenStreamingIsOff() throws Exception {
        captureLog(YuguCompat.LOG_DEBUG);
        YuguCompat.setStreamUpload(false);
        SkEgnManager m = init(SECRET);
        JSONObject r = evaluate(m, new RecordSetting(CoreType.CN_SENT_EVAL, "今天天气很好"));
        assertSuccessEnvelope(r);
        boolean timingAsked = false;
        for (Multipart.Part p : requests.get(0).body.parts()) {
            timingAsked |= "timing".equals(p.name) && "1".equals(p.value);
        }
        assertTrue("timing=1 was not sent", timingAsked);
        assertEquals(0, wsConnects.get());
        String line = summary(r.getString("tokenId"));
        System.out.println("sandbox REST: " + line);
        assertTrue(line, line.contains(" mode=rest "));
        assertTrue("no server timing: " + line, line.contains(" server.total="));
        boolean header = false;
        synchronized (logLines) {
            for (String l : logLines) {
                if (l.contains(" rest.headers status=200")) {
                    header |= l.contains("server-timing=\"total;dur=");
                    System.out.println("sandbox debug: " + l);
                }
                if (l.contains(" rest.body ") || l.contains("engineStages")) {
                    System.out.println("sandbox debug: " + l);
                }
            }
        }
        assertTrue("no Server-Timing header on the REST answer", header);
    }
}
