package com.shengzhiai.yugu;

import com.fasterxml.jackson.databind.JsonNode;
import com.shengzhiai.yugu.errors.NetworkException;
import com.shengzhiai.yugu.errors.RequestTimeoutException;
import com.shengzhiai.yugu.model.EvaluateConfig;
import com.shengzhiai.yugu.testing.Fixtures;
import com.shengzhiai.yugu.testing.MockServer;
import com.shengzhiai.yugu.testing.MockServerExtension;
import com.shengzhiai.yugu.testing.RecordingListener;
import com.shengzhiai.yugu.testing.TcpProxy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Acceptance V-04 (a): the client network drops for 10 s in the middle of a stream. Two outage models:
 * the network goes down (connections reset, new ones refused) and a silent dead link (bytes lost in
 * both directions, new connections hang). Tagged slow: run with {@code ./mvnw verify -Pslow}.
 */
@Tag("slow")
@ExtendWith(MockServerExtension.class)
class NetworkOutageSlowTest {
    static final byte[] PCM = Fixtures.pcm("zh_short.wav");
    static final long OUTAGE_MS = 10_000;

    MockServer mock;

    @BeforeEach
    void reset() {
        mock = MockServer.get();
        mock.reset();
    }

    static void send(StreamSession s, byte[] pcm) {
        for (byte[] f : Fixtures.frames(pcm, 640)) {
            s.sendAudio(f);
        }
    }

    static void awaitEvent(RecordingListener l, String event, long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (!l.events.contains(event)) {
            if (System.currentTimeMillis() > end) {
                throw new AssertionError("no " + event + " in " + l.events);
            }
            Thread.sleep(10);
        }
    }

    @Test
    void outageOf10sWithDefaultSettingsRecovers() throws Exception {
        RecordingListener l = new RecordingListener();
        long t0;
        StreamSession s;
        try (TcpProxy proxy = new TcpProxy(mock.port());
             YuguClient c = YuguClient.builder().apiKey(MockServer.APP_KEY, MockServer.SECRET).baseUrl(mock.baseUrl())
                     .wsBaseUrl("ws://127.0.0.1:" + proxy.port()).build()) {
            // default settings only: 8 reconnect attempts, REPLAY, protocol ping 15 s, pong timeout 30 s
            assertEquals(8, c.getOptions().getReconnect().getMaxAttempts());
            assertEquals(AudioBufferPolicy.REPLAY, c.getOptions().getAudioBufferPolicy());
            s = c.streamEvaluate(new EvaluateConfig("sentence", "今天天气很好", "zh-CN"), l);
            l.session = s;
            awaitEvent(l, "started", 5000);
            send(s, Arrays.copyOf(PCM, PCM.length / 2));
            t0 = System.currentTimeMillis();
            proxy.dropNetwork();
            send(s, Arrays.copyOfRange(PCM, PCM.length / 2, PCM.length));
            s.end();
            awaitEvent(l, "reconnecting:1", 5000);
            assertSame(NetworkException.class, l.reconnectCauses.get(0).getClass());
            Thread.sleep(Math.max(0, OUTAGE_MS - (System.currentTimeMillis() - t0)));
            proxy.restore();
            assertTrue(l.awaitClosed(30_000), "silent hang after the outage: " + l.events);
        }
        l.assertGuarantees();
        assertNotNull(l.result, l.events.toString());
        assertNull(l.error);
        assertTrue(System.currentTimeMillis() - t0 >= OUTAGE_MS);
        // attempts 1 to 4 fall into the outage (at most 0.65 + 1.3 + 2.6 + 5.2 s), a later one succeeds
        assertTrue(l.count("reconnecting:") >= 4, l.events.toString());
        long attempt = l.reconnected.get(0)[0];
        assertTrue(attempt >= 4 && attempt <= 8, "recovered on attempt " + attempt + ": " + l.events);
        assertEquals(0, l.reconnected.get(0)[1], "REPLAY drops nothing");
        JsonNode billing = mock.billing();
        assertEquals(1, billing.get("billed").asInt(), billing.toString());
        assertEquals(PCM.length, billing.get("records").get(0).get("bytes").asInt(), "the whole audio was scored");
    }

    @Test
    void outageOf10sIsBridgedByReconnectAndReplay() throws Exception {
        RecordingListener l = new RecordingListener();
        long t0;
        StreamSession s;
        try (TcpProxy proxy = new TcpProxy(mock.port());
             YuguClient c = YuguClient.builder().apiKey(MockServer.APP_KEY, MockServer.SECRET).baseUrl(mock.baseUrl())
                     .wsBaseUrl("ws://127.0.0.1:" + proxy.port())
                     .pingIntervalMs(1000).pongTimeoutMs(3000).connectTimeoutMs(1500)
                     .build()) {
            // a silent dead link is only noticed by the heartbeat, so the test shortens it; reconnect uses the defaults
            s = c.streamEvaluate(new EvaluateConfig("sentence", "今天天气很好", "zh-CN"), l);
            l.session = s;
            awaitEvent(l, "started", 5000);
            send(s, Arrays.copyOf(PCM, PCM.length / 2));
            t0 = System.currentTimeMillis();
            proxy.blackhole();
            // audio and end() during the outage are buffered (REPLAY) and sent after the reconnect
            send(s, Arrays.copyOfRange(PCM, PCM.length / 2, PCM.length));
            s.end();
            awaitEvent(l, "reconnecting:1", 6000);
            assertSame(RequestTimeoutException.class, l.reconnectCauses.get(0).getClass(), "detected by the missing pong");
            Thread.sleep(Math.max(0, OUTAGE_MS - (System.currentTimeMillis() - t0)));
            proxy.restore();
            assertTrue(l.awaitClosed(30_000), "silent hang after the outage: " + l.events);
        }
        l.assertGuarantees();
        assertNotNull(l.result, l.events.toString());
        assertNull(l.error);
        assertTrue(System.currentTimeMillis() - t0 >= OUTAGE_MS);
        assertTrue(l.count("reconnecting:") >= 2, "several attempts during the outage: " + l.events);
        assertTrue(l.events.stream().anyMatch(e -> e.startsWith("reconnected:")), l.events.toString());
        JsonNode billing = mock.billing();
        assertEquals(1, billing.get("billed").asInt(), billing.toString());
        assertEquals(PCM.length, billing.get("records").get(0).get("bytes").asInt(), "the whole audio was scored");
    }

    @Test
    void outageLongerThanTheReconnectBudgetIsReportedAsReconnectFailure() throws Exception {
        RecordingListener l = new RecordingListener();
        long t0;
        try (TcpProxy proxy = new TcpProxy(mock.port());
             YuguClient c = YuguClient.builder().apiKey(MockServer.APP_KEY, MockServer.SECRET).baseUrl(mock.baseUrl())
                     .wsBaseUrl("ws://127.0.0.1:" + proxy.port())
                     .pingIntervalMs(1000).pongTimeoutMs(3000).connectTimeoutMs(1500)
                     .reconnect(ReconnectPolicy.builder().maxAttempts(2).build())
                     .build()) {
            StreamSession s = c.streamEvaluate(new EvaluateConfig("sentence", "今天天气很好", "zh-CN"), l);
            l.session = s;
            awaitEvent(l, "started", 5000);
            send(s, PCM);
            t0 = System.currentTimeMillis();
            proxy.blackhole();
            assertTrue(l.awaitClosed(OUTAGE_MS + 5000), "no terminal callback during the outage: " + l.events);
            proxy.restore();
        }
        l.assertGuarantees();
        assertNull(l.result);
        assertEquals(90006, l.error.getCode());
        assertSame(NetworkException.class, l.error.getClass());
        assertTrue(System.currentTimeMillis() - t0 < OUTAGE_MS + 5000);
        assertEquals(Arrays.asList("connected", "started", "reconnecting:1", "reconnecting:2", "error:90006", "closed:1006"), l.callbacks());
    }
}
