package com.stkouyu;

import com.shengzhiai.yugu.stcompat.internal.CompatConfig;
import com.shengzhiai.yugu.stcompat.internal.CompatEngine;
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * SDK 2.3.0 pipelined evaluation over the real socket client and HTTP stack against
 * tools/mock-server: three stream sessions back to back run on three connections at once and the
 * platform delays the first two results (ws-delay-result), so the results arrive in completion
 * order; a session whose channel is refused (ws-refuse) is uploaded over REST, slowly
 * (slow:1500), while the next session records. Every evaluation is billed once under its own key.
 */
@RunWith(RobolectricTestRunner.class)
public class PipelinedMockIntegrationTest extends CompatTestBase {
    static final String[] TEXTS = PipelinedEvaluationTest.TEXTS;
    static final byte[] PCM = Fixtures.pcm("spec/fixtures/audio/zh_short.wav");

    private static MockServer server;

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

    private static RecordSetting stream(int i) {
        RecordSetting s = new RecordSetting(CoreType.CN_SENT_EVAL, TEXTS[i]);
        s.setIsStream(true);
        return s;
    }

    private static void feed(SkEgnManager m) {
        for (int i = 0; i < PCM.length; i += 3200) {
            m.feed(Arrays.copyOfRange(PCM, i, Math.min(PCM.length, i + 3200)));
        }
    }

    private String token() {
        return CompatEngine.get(app).currentTokenId();
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

    private static void awaitEntries(String method, int n) {
        Main.await(n + " " + method + " entries on the mock platform", 10000, () -> {
            try {
                return entries(method).size() >= n;
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
    }

    @Test
    public void threeSessionsOnThreeConnectionsFinishInCompletionOrder() throws Exception {
        YuguCompat.setMaxPendingEvaluations(3);
        server.fault("/sent.eval.cn", "ws-delay-result:4000");
        server.fault("/sent.eval.cn", "ws-delay-result:2000");
        SkEgnManager m = init();
        final Events[] ev = {new Events(), new Events(), new Events()};
        final String[] token = new String[3];
        final List<Integer> finals = Collections.synchronizedList(new ArrayList<Integer>());
        for (int i = 0; i < 3; i++) {
            final int k = i;
            m.startRecord(stream(i), new PipelinedEvaluationTest.Forwarding(ev[i]) {
                @Override
                public void onScore(String json) {
                    if (Events.isFinal(json)) {
                        finals.add(k);
                    }
                    super.onScore(json);
                }
            });
            token[i] = token();
            // this channel is open before the next session starts, so the queued faults reach A and B
            awaitEntries("WS", i + 1);
            feed(m);
            m.stopRecord();
            Main.await("onRecordEnd " + i, 10000, () -> ev[k].has("onRecordEnd"));
            assertEquals(SkEgnManager.engine_status.IDLE, m.getEngineStatus());
        }
        assertEquals(3, YuguCompat.pendingEvaluations());
        Main.await("three finals", 30000, () -> finals.size() == 3);
        Main.settle(50);
        assertEquals("C, then B, then A", Arrays.asList(2, 1, 0), finals);
        assertEquals(0, YuguCompat.pendingEvaluations());
        HashSet<String> recordIds = new HashSet<>();
        for (int i = 0; i < 3; i++) {
            Events e = ev[i];
            assertTrue("callbacks off the main thread: " + e.offMain, e.offMain.isEmpty());
            assertEquals(Arrays.asList("onStart", "onRecordEnd", "onScore"), e.log);
            JSONObject r = e.result();
            assertEquals(token[i], r.getString("tokenId"));
            assertEquals(TEXTS[i], r.getString("refText"));
            assertTrue(r.getString("recordId").startsWith("eval_"));
            recordIds.add(r.getString("recordId"));
            assertTrue(r.getJSONObject("result").has("overall"));
        }
        assertEquals("three records", 3, recordIds.size());
        List<JSONObject> wsLog = entries("WS");
        assertEquals(3, wsLog.size());
        for (int i = 0; i < 3; i++) {
            assertEquals(token[i], wsLog.get(i).getString("idempotencyKey"));
        }
        assertEquals("ws-delay-result:4000", wsLog.get(0).getString("fault"));
        assertEquals("ws-delay-result:2000", wsLog.get(1).getString("fault"));
        assertTrue(wsLog.get(2).isNull("fault"));
        assertTrue("no REST upload", entries("POST").isEmpty());
        JSONObject billing = server.billing();
        assertEquals(3, billing.getInt("billed"));
        for (String t : token) {
            assertEquals("billed once: " + t, 1, billing.getJSONObject("byKey").getInt(t));
        }
    }

    @Test
    public void aRefusedChannelIsUploadedOverRestWhileTheNextSessionRecords() throws Exception {
        YuguCompat.setMaxPendingEvaluations(1);
        server.fault("/sent.eval.cn", "ws-refuse");
        server.fault("/sent.eval.cn", "slow:1500");
        SkEgnManager m = init();
        Events a = new Events();
        m.startRecord(stream(0), a.recorder);
        String tokenA = token();
        awaitEntries("WS", 1);
        feed(m);
        m.stopRecord();
        // A's upload reached the platform, which takes 1.5 s for it
        awaitEntries("POST", 1);
        JSONObject post = entries("POST").get(0);
        assertEquals(tokenA, post.getString("idempotencyKey"));
        assertEquals("slow:1500", post.getString("fault"));
        Events b = new Events();
        m.startRecord(stream(1), b.recorder);
        String tokenB = token();
        Main.idle();
        assertTrue(b.has("onStart"));
        awaitEntries("WS", 2);
        feed(m);
        Main.await("A's result over REST", 15000, () -> a.json != null);
        assertEquals("A arrives while B still records", SkEgnManager.engine_status.RECORDING, m.getEngineStatus());
        assertEquals(tokenA, a.result().getString("tokenId"));
        assertTrue(a.result().getJSONObject("result").has("overall"));
        assertFalse(b.has("onRecordEnd"));
        m.stopRecord();
        Main.await("B's result", 15000, () -> b.json != null);
        assertEquals(tokenB, b.result().getString("tokenId"));
        assertTrue(b.result().getJSONObject("result").has("overall"));
        assertEquals(0, YuguCompat.pendingEvaluations());
        List<JSONObject> wsLog = entries("WS");
        assertEquals(503, wsLog.get(0).getInt("rejected"));
        assertEquals(tokenB, wsLog.get(1).getString("idempotencyKey"));
        assertEquals("one REST upload, A's", 1, entries("POST").size());
        JSONObject billing = server.billing();
        assertEquals(2, billing.getInt("billed"));
        assertEquals(1, billing.getJSONObject("byKey").getInt(tokenA));
        assertEquals(1, billing.getJSONObject("byKey").getInt(tokenB));
        for (Events e : new Events[] {a, b}) {
            assertTrue(e.offMain.isEmpty());
            assertEquals(Arrays.asList("onStart", "onRecordEnd", "onScore"), e.log);
        }
    }
}
