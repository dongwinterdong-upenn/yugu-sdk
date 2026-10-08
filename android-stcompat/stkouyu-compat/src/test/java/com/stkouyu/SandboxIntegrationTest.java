package com.stkouyu;

import com.shengzhiai.yugu.stcompat.internal.CancelToken;
import com.shengzhiai.yugu.stcompat.internal.CompatConfig;
import com.shengzhiai.yugu.stcompat.internal.ErrorTable;
import com.shengzhiai.yugu.stcompat.internal.HttpTransport;
import com.shengzhiai.yugu.stcompat.internal.Multipart;
import com.shengzhiai.yugu.stcompat.internal.UrlConnectionTransport;
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
import org.junit.Ignore;
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
 * call the platform. At most 6 platform calls per run: one retry per evaluation and a counting
 * transport that refuses further calls. Key values are never printed: failure messages carry
 * errId, error and recordId only, never the envelope (it contains applicationId).
 */
@RunWith(RobolectricTestRunner.class)
public class SandboxIntegrationTest extends CompatTestBase {
    static final int MAX_CALLS = 6;
    private static final String APP_KEY = env("YUGU_SANDBOX_APPKEY");
    private static final String SECRET = env("YUGU_SANDBOX_SECRET");
    private static final String BASE = env("YUGU_SANDBOX_BASE") != null ? env("YUGU_SANDBOX_BASE") : "https://open.shengzhiai.com";
    private static final AtomicInteger CALLS = new AtomicInteger();

    private final Events ev = new Events();
    private final List<HttpTransport.Request> requests = Collections.synchronizedList(new ArrayList<HttpTransport.Request>());

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
        assertEquals(r.getString("tokenId"), requests.get(0).headers.get("Idempotency-Key"));
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
     * Shengtong-style overall. A single-sentence paragraph comes back without sentences.
     */
    @Test
    public void paragraphWordDetailsInShengtongShape() throws Exception {
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
     * Expected platform behaviour that does not hold yet: on 2026-10-08 the sandbox evaluated a
     * POST /{coreType} signed with a wrong secret instead of rejecting it. Enable when fixed.
     */
    @Ignore("platform defect reported 2026-10-08: POST /{coreType} accepts a wrong X-Signature")
    @Test
    public void wrongSecretIsRejected() throws Exception {
        SkEgnManager m = init("not-the-sandbox-secret-" + Long.toHexString(System.nanoTime()));
        JSONObject r = evaluate(m, new RecordSetting(CoreType.CN_SENT_EVAL, "今天天气很好"));
        assertTrue("a wrong signature was accepted: " + describe(r), r.has("errId"));
        assertEquals("AUTH", ErrorTable.ERRORS.get(r.getInt("errId")).category);
        assertEquals(1, requests.size());
    }
}
