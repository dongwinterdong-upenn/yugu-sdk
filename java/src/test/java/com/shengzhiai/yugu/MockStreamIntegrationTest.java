package com.shengzhiai.yugu;

import com.fasterxml.jackson.databind.JsonNode;
import com.shengzhiai.yugu.errors.InvalidParameterException;
import com.shengzhiai.yugu.errors.NetworkException;
import com.shengzhiai.yugu.errors.PermissionException;
import com.shengzhiai.yugu.errors.RequestTimeoutException;
import com.shengzhiai.yugu.errors.ServerException;
import com.shengzhiai.yugu.model.CompatConfig;
import com.shengzhiai.yugu.model.EvalResult;
import com.shengzhiai.yugu.model.EvaluateConfig;
import com.shengzhiai.yugu.testing.Fixtures;
import com.shengzhiai.yugu.testing.MockServer;
import com.shengzhiai.yugu.testing.MockServerExtension;
import com.shengzhiai.yugu.testing.RecordingEvents;
import com.shengzhiai.yugu.testing.RecordingListener;
import com.shengzhiai.yugu.testing.TcpProxy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-time link resilience against the Node mock (acceptance V-04): the server kills the socket,
 * closes with 1011, goes silent, refuses the handshake or sends error frames; the network switch is
 * simulated by resetting every socket in a TCP proxy. Every test bounds the wait for the terminal
 * callback, so a silent hang fails the test.
 */
@ExtendWith(MockServerExtension.class)
class MockStreamIntegrationTest {
    static final byte[] PCM = Fixtures.pcm("zh_short.wav");
    static final String NATIVE = "/api/v1/ws/evaluate";
    static final long TERMINAL_BOUND_MS = 15_000;

    MockServer mock;
    RecordingEvents events;

    @BeforeEach
    void reset() {
        mock = MockServer.get();
        mock.reset();
        events = new RecordingEvents();
    }

    YuguClient client(Consumer<YuguClient.Builder> cfg) {
        YuguClient.Builder b = YuguClient.builder().apiKey(MockServer.APP_KEY, MockServer.SECRET).baseUrl(mock.baseUrl())
                .eventListener(events);
        cfg.accept(b);
        return b.build();
    }

    static EvaluateConfig sentence() {
        return new EvaluateConfig(EvaluateConfig.CORE_SENTENCE, "今天天气很好", "zh-CN");
    }

    static void send(StreamSession s, byte[] pcm) {
        for (byte[] f : Fixtures.frames(pcm, 640)) {
            s.sendAudio(f);
        }
    }

    static StreamSession open(YuguClient c, RecordingListener l, StreamOptions o) {
        StreamSession s = c.streamEvaluate(sentence(), l, o);
        l.session = s;
        return s;
    }

    static void awaitTerminal(RecordingListener l) throws InterruptedException {
        assertTrue(l.awaitClosed(TERMINAL_BOUND_MS), "silent hang: no onClosed within " + TERMINAL_BOUND_MS + " ms, events " + l.events);
        l.assertGuarantees();
    }

    static void awaitEvent(RecordingListener l, String event) throws InterruptedException {
        long end = System.currentTimeMillis() + TERMINAL_BOUND_MS;
        while (!l.events.contains(event)) {
            if (System.currentTimeMillis() > end) {
                throw new AssertionError("no " + event + " in " + l.events);
            }
            Thread.sleep(5);
        }
    }

    /** The expected events occur in this order (other events may come in between). */
    static void assertInOrder(List<String> events, String... expected) {
        int i = 0;
        for (String e : events) {
            if (i < expected.length && e.equals(expected[i])) {
                i++;
            }
        }
        assertEquals(expected.length, i, "expected in order " + Arrays.toString(expected) + " within " + events);
    }

    List<JsonNode> wsLog(String path) {
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode e : mock.log()) {
            if ("WS".equals(e.get("method").asText()) && path.equals(e.get("path").asText())) {
                out.add(e);
            }
        }
        return out;
    }

    void assertBilledOnce(String key) {
        JsonNode b = mock.billing();
        assertEquals(1, b.get("billed").asInt(), b.toString());
        assertEquals(1, b.get("byKey").get(key).asInt(), b.toString());
    }

    /** Reconnect sequence shared by the REPLAY scenarios. */
    static final String[] RECONNECT_ORDER = {"state:CONNECTING", "state:CONNECTED", "connected", "state:STARTED", "started",
        "state:RECONNECTING", "reconnecting:1", "state:CONNECTING", "state:CONNECTED", "connected", "state:STARTED", "started",
        "reconnected:1", "state:COMPLETED", "result", "state:CLOSED", "closed:1000"};

    // ------------------------------------------------------------------ server side interruptions

    @Test
    void v04_serverKillsTheSocket_replayReconnectYieldsOneResult() throws Exception {
        mock.faults(NATIVE, "ws-kill-after:5");
        RecordingListener l = new RecordingListener();
        StreamSession s;
        try (YuguClient c = client(b -> { })) {
            s = open(c, l, null);
            send(s, PCM);
            s.end();
            awaitTerminal(l);
        }
        assertNotNull(l.result, l.events.toString());
        assertNull(l.error);
        assertInOrder(l.events, RECONNECT_ORDER);
        assertSame(NetworkException.class, l.reconnectCauses.get(0).getClass());
        assertEquals(0, l.reconnected.get(0)[1]);
        assertEquals(2, l.result.getAttempts());
        assertEquals(s.getIdempotencyKey(), l.result.getIdempotencyKey());
        List<JsonNode> log = wsLog(NATIVE);
        assertEquals(2, log.size());
        assertEquals("ws-kill-after:5", log.get(0).get("fault").asText());
        assertTrue(log.get(1).get("fault").isNull());
        assertEquals(s.getIdempotencyKey(), log.get(0).get("idempotencyKey").asText());
        assertEquals(s.getIdempotencyKey(), log.get(1).get("idempotencyKey").asText());
        assertBilledOnce(s.getIdempotencyKey());
        assertEquals(PCM.length, mock.billing().get("records").get(0).get("bytes").asInt(), "the whole audio was replayed");
        assertTrue(events.events.contains("reconnect:1:true"));
    }

    @Test
    void v04_serverCloses1011_replayReconnectYieldsOneResult() throws Exception {
        mock.faults(NATIVE, "ws-close-after:5");
        RecordingListener l = new RecordingListener();
        StreamSession s;
        try (YuguClient c = client(b -> { })) {
            s = open(c, l, null);
            send(s, PCM);
            s.end();
            awaitTerminal(l);
        }
        assertNotNull(l.result, l.events.toString());
        assertInOrder(l.events, RECONNECT_ORDER);
        assertTrue(l.reconnectCauses.get(0).getRawMessage().contains("1011"), l.reconnectCauses.get(0).getRawMessage());
        assertBilledOnce(s.getIdempotencyKey());
    }

    @Test
    void v04_silentServer_resultTimeoutReconnects() throws Exception {
        mock.faults(NATIVE, "ws-silent:3");
        RecordingListener l = new RecordingListener();
        StreamSession s;
        try (YuguClient c = client(b -> { })) {
            s = open(c, l, StreamOptions.builder().resultTimeoutMs(1500).build());
            send(s, PCM);
            s.end();
            awaitTerminal(l);
        }
        assertNotNull(l.result, l.events.toString());
        assertSame(RequestTimeoutException.class, l.reconnectCauses.get(0).getClass());
        assertEquals(90007, l.reconnectCauses.get(0).getCode());
        assertInOrder(l.events, "state:ENDING", "state:RECONNECTING", "reconnecting:1", "reconnected:1", "state:ENDING",
                "result", "closed:1000");
        assertBilledOnce(s.getIdempotencyKey());
    }

    @Test
    void v04_failPolicyReportsTheTransportError() throws Exception {
        mock.faults(NATIVE, "ws-kill-after:5");
        RecordingListener l = new RecordingListener();
        try (YuguClient c = client(b -> b.audioBufferPolicy(AudioBufferPolicy.FAIL))) {
            StreamSession s = open(c, l, null);
            send(s, PCM);
            s.end();
            awaitTerminal(l);
        }
        assertNull(l.result);
        assertSame(NetworkException.class, l.error.getClass());
        assertEquals(90001, l.error.getCode());
        assertEquals(0, l.count("reconnecting"));
        assertInOrder(l.events, "state:STARTED", "state:FAILED", "error:90001", "state:CLOSED", "closed:1006");
        assertEquals(1, wsLog(NATIVE).size());
        assertEquals(0, mock.billing().get("billed").asInt());
    }

    @Test
    void v04_retryableErrorFrameReconnectsOthersFail() throws Exception {
        mock.faults(NATIVE, "ws-error:50200");
        RecordingListener l = new RecordingListener();
        try (YuguClient c = client(b -> { })) {
            StreamSession s = open(c, l, null);
            send(s, PCM);
            s.end();
            awaitTerminal(l);
            assertNotNull(l.result, l.events.toString());
            assertSame(ServerException.class, l.reconnectCauses.get(0).getClass());
            assertEquals(50200, l.reconnectCauses.get(0).getCode());
            assertBilledOnce(s.getIdempotencyKey());

            mock.reset();
            mock.faults(NATIVE, "ws-error:40001");
            RecordingListener l2 = new RecordingListener();
            StreamSession s2 = open(c, l2, null);
            send(s2, PCM);
            s2.end();
            awaitTerminal(l2);
            assertSame(InvalidParameterException.class, l2.error.getClass());
            assertEquals(40001, l2.error.getCode());
            assertEquals(0, l2.count("reconnecting"));
        }
    }

    @Test
    void v04_refusedHandshakeIsRetried() throws Exception {
        mock.faults(NATIVE, "ws-refuse");
        RecordingListener l = new RecordingListener();
        try (YuguClient c = client(b -> { })) {
            StreamSession s = open(c, l, null);
            send(s, PCM);
            s.end();
            awaitTerminal(l);
        }
        assertNotNull(l.result, l.events.toString());
        assertEquals(503, l.reconnectCauses.get(0).getHttpStatus());
        assertInOrder(l.events, "state:CONNECTING", "state:RECONNECTING", "reconnecting:1", "state:CONNECTING", "connected",
                "started", "reconnected:1", "result", "closed:1000");
    }

    @Test
    void v04_reconnectExhaustedIsReportedNotSwallowed() throws Exception {
        mock.faults(NATIVE, "ws-refuse", "ws-refuse", "ws-refuse", "ws-refuse");
        RecordingListener l = new RecordingListener();
        long t0 = System.currentTimeMillis();
        // 3 attempts keep the test short; the default of 8 is exercised by NetworkOutageSlowTest
        try (YuguClient c = client(b -> b.reconnect(ReconnectPolicy.builder().maxAttempts(3).build()))) {
            StreamSession s = open(c, l, null);
            send(s, PCM);
            s.end();
            awaitTerminal(l);
        }
        assertNull(l.result);
        assertEquals(90006, l.error.getCode());
        assertSame(NetworkException.class, l.error.getClass());
        assertTrue(l.error.getCause() instanceof ServerException, String.valueOf(l.error.getCause()));
        assertEquals(List.of("reconnecting:1", "reconnecting:2", "reconnecting:3", "error:90006", "closed:1006"), l.callbacks());
        assertTrue(System.currentTimeMillis() - t0 < 10_000);
        assertEquals(0, mock.billing().get("billed").asInt());
    }

    @Test
    void v04_badSignatureFailsWithoutReconnect() throws Exception {
        RecordingListener l = new RecordingListener();
        try (YuguClient c = YuguClient.builder().apiKey(MockServer.APP_KEY, "wrong-secret").baseUrl(mock.baseUrl()).build()) {
            open(c, l, null);
            awaitTerminal(l);
        }
        assertSame(PermissionException.class, l.error.getClass());
        assertEquals(403, l.error.getHttpStatus());
        assertEquals(0, l.count("reconnecting"));
        assertEquals(List.of("CONNECTING", "FAILED", "CLOSED"), l.states());
    }

    // ------------------------------------------------------------------ buffer policies and other paths

    @Test
    void dropPolicyScoresOnlyAudioAfterTheReconnect() throws Exception {
        mock.faults(NATIVE, "ws-kill-after:4");
        byte[] first = Arrays.copyOf(PCM, 640 * 20);
        byte[] second = Arrays.copyOfRange(PCM, 640 * 20, PCM.length);
        RecordingListener l = new RecordingListener();
        try (YuguClient c = client(b -> b.audioBufferPolicy(AudioBufferPolicy.DROP))) {
            StreamSession s = open(c, l, null);
            send(s, first);
            awaitEvent(l, "reconnected:1");
            send(s, second);
            s.end();
            awaitTerminal(l);
        }
        assertNotNull(l.result, l.events.toString());
        assertEquals(first.length, l.reconnected.get(0)[1]);
        JsonNode records = mock.billing().get("records");
        assertEquals(1, records.size());
        assertEquals(second.length, records.get(0).get("bytes").asInt(), "the new server session scored only the later audio");
    }

    @Test
    void compatStreamWithProgressFrames() throws Exception {
        RecordingListener l = new RecordingListener();
        StreamSession s;
        try (YuguClient c = client(b -> { })) {
            s = c.streamEvaluateCompat(new CompatConfig("sent.eval.cn", "今天天气很好").language("zh-CN").realtimeFeedback(true), l);
            l.session = s;
            send(s, PCM);
            s.end();
            awaitTerminal(l);
        }
        assertNotNull(l.result, l.events.toString());
        assertEquals("sent.eval.cn", l.result.getCoreType());
        assertTrue(l.partials.size() >= 3, "progress frames: " + l.partials.size());
        assertEquals(16000, l.partials.get(0).getBytes());
        assertEquals("ws-compat", mock.billing().get("records").get(0).get("op").asText());
        assertBilledOnce(s.getIdempotencyKey());
    }

    @Test
    void sameKeyTwiceReplaysTheFirstResult() throws Exception {
        String key = "ws-" + UUID.randomUUID().toString().replace("-", "");
        List<EvalResult> results = new ArrayList<>();
        try (YuguClient c = client(b -> { })) {
            for (int i = 0; i < 2; i++) {
                RecordingListener l = new RecordingListener();
                StreamSession s = open(c, l, StreamOptions.builder().idempotencyKey(key).build());
                send(s, PCM);
                s.end();
                awaitTerminal(l);
                results.add(l.result);
            }
        }
        assertFalse(results.get(0).isReplayed());
        assertTrue(results.get(1).isReplayed());
        assertEquals(results.get(0).getRecordId(), results.get(1).getRecordId());
        assertBilledOnce(key);
    }

    @Test
    void v04_networkSwitchResetsTheSocket() throws Exception {
        RecordingListener l = new RecordingListener();
        StreamSession s;
        try (TcpProxy proxy = new TcpProxy(mock.port());
             YuguClient c = client(b -> b.wsBaseUrl("ws://127.0.0.1:" + proxy.port()))) {
            s = open(c, l, null);
            send(s, Arrays.copyOf(PCM, 640 * 30));
            awaitEvent(l, "started");
            Thread.sleep(100);
            proxy.resetAll();
            send(s, Arrays.copyOfRange(PCM, 640 * 30, PCM.length));
            s.end();
            awaitTerminal(l);
        }
        assertNotNull(l.result, l.events.toString());
        assertSame(NetworkException.class, l.reconnectCauses.get(0).getClass());
        assertInOrder(l.events, RECONNECT_ORDER);
        assertBilledOnce(s.getIdempotencyKey());
        assertEquals(PCM.length, mock.billing().get("records").get(0).get("bytes").asInt());
    }

    @Test
    void oneLargeChunkIsSplitBelowTheMock128KbFrameLimit() throws Exception {
        byte[] three = new byte[PCM.length * 3];
        for (int i = 0; i < 3; i++) {
            System.arraycopy(PCM, 0, three, i * PCM.length, PCM.length);
        }
        assertTrue(three.length > 128 * 1024);
        RecordingListener l = new RecordingListener();
        StreamSession s;
        try (YuguClient c = client(b -> { })) {
            s = open(c, l, null);
            s.sendAudio(three);
            s.end();
            awaitTerminal(l);
        }
        assertNotNull(l.result, l.events.toString());
        assertEquals(0, l.count("reconnecting"), "no 1009 close");
        assertEquals(three.length, mock.billing().get("records").get(0).get("bytes").asInt());
        assertBilledOnce(s.getIdempotencyKey());
    }

    @Test
    void slowServerProcessingWithTheSameKeyIsAnsweredOnce() throws Exception {
        String key = "ws-slow-" + UUID.randomUUID().toString().replace("-", "");
        mock.faults(NATIVE, "ws-delay-result:2500");
        List<RecordingListener> ls = new ArrayList<>();
        try (YuguClient c = client(b -> { })) {
            for (int i = 0; i < 2; i++) {
                RecordingListener l = new RecordingListener();
                ls.add(l);
                StreamSession s = open(c, l, StreamOptions.builder().idempotencyKey(key).build());
                send(s, PCM);
                s.end();
            }
            for (RecordingListener l : ls) {
                awaitTerminal(l);
                assertNotNull(l.result, l.events.toString());
            }
        }
        assertEquals(ls.get(0).result.getRecordId(), ls.get(1).result.getRecordId());
        assertTrue(ls.get(0).result.isReplayed() ^ ls.get(1).result.isReplayed(), "one fresh result, one replay");
        assertBilledOnce(key);
    }

    @Test
    void protocolPingsKeepTheConnectionAlive() throws Exception {
        RecordingListener l = new RecordingListener();
        try (YuguClient c = client(b -> b.pingIntervalMs(200).pongTimeoutMs(600))) {
            StreamSession s = open(c, l, null);
            awaitEvent(l, "started");
            Thread.sleep(1500);
            assertEquals(SessionState.STARTED, s.getState(), "pongs answer the pings; no false heartbeat timeout");
            assertEquals(0, l.count("reconnecting"));
            send(s, PCM);
            s.end();
            awaitTerminal(l);
        }
        assertNotNull(l.result);
    }
}
