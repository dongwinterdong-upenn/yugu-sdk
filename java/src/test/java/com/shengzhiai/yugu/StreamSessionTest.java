package com.shengzhiai.yugu;

import com.fasterxml.jackson.databind.JsonNode;
import com.shengzhiai.yugu.errors.AudioQualityException;
import com.shengzhiai.yugu.errors.AuthException;
import com.shengzhiai.yugu.errors.ErrorDetails;
import com.shengzhiai.yugu.errors.ErrorCategory;
import com.shengzhiai.yugu.errors.IllegalSessionStateException;
import com.shengzhiai.yugu.errors.InvalidParameterException;
import com.shengzhiai.yugu.errors.NetworkException;
import com.shengzhiai.yugu.errors.ProtocolViolationException;
import com.shengzhiai.yugu.errors.RequestCancelledException;
import com.shengzhiai.yugu.errors.RequestTimeoutException;
import com.shengzhiai.yugu.errors.YuguErrors;
import com.shengzhiai.yugu.errors.YuguException;
import com.shengzhiai.yugu.internal.Json;
import com.shengzhiai.yugu.internal.PercentEncoding;
import com.shengzhiai.yugu.model.CompatConfig;
import com.shengzhiai.yugu.model.EvalResult;
import com.shengzhiai.yugu.model.EvaluateConfig;
import com.shengzhiai.yugu.testing.CapturingLogger;
import com.shengzhiai.yugu.testing.FakeTransport;
import com.shengzhiai.yugu.testing.Fixtures;
import com.shengzhiai.yugu.testing.RecordingEvents;
import com.shengzhiai.yugu.testing.RecordingListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** State machine, listener guarantees, buffer policies and lifecycle of stream sessions on a scripted transport. */
class StreamSessionTest {
    static final ReconnectPolicy FAST = ReconnectPolicy.builder().initialDelayMs(10).maxDelayMs(20).build();
    static final byte[] PCM = Fixtures.pcm("zh_short.wav");

    FakeTransport fake;
    CapturingLogger logs;
    RecordingEvents events;
    List<YuguClient> clients = new ArrayList<>();

    @BeforeEach
    void setUp() {
        fake = new FakeTransport();
        logs = new CapturingLogger();
        events = new RecordingEvents();
    }

    @AfterEach
    void tearDown() {
        clients.forEach(YuguClient::close);
    }

    YuguClient client(Consumer<YuguClient.Builder> cfg) {
        YuguClient.Builder b = YuguClient.builder().apiKey("mock-app-key", "mock-secret-key")
                .baseUrl("http://127.0.0.1:9").wsBaseUrl("ws://fake.test").logger(logs).eventListener(events).reconnect(FAST);
        TestHooks.transport(b, fake);
        cfg.accept(b);
        YuguClient c = b.build();
        clients.add(c);
        return c;
    }

    YuguClient client() {
        return client(b -> { });
    }

    static EvaluateConfig sentence() {
        return new EvaluateConfig(EvaluateConfig.CORE_SENTENCE, "今天天气很好", "zh-CN");
    }

    StreamSession open(YuguClient c, RecordingListener l, StreamOptions o) {
        StreamSession s = c.streamEvaluate(sentence(), l, o);
        l.session = s;
        return s;
    }

    static void sendAll(StreamSession s, byte[] pcm) {
        for (byte[] f : Fixtures.frames(pcm, 640)) {
            s.sendAudio(f);
        }
    }

    void await(RecordingListener l) throws InterruptedException {
        assertTrue(l.awaitClosed(10_000), "no terminal callback within 10 s: " + l.events);
        l.assertGuarantees();
        for (FakeTransport.Conn c : fake.connections) {
            c.assertNoAudioAfterEnd();
        }
    }

    static void awaitEvent(RecordingListener l, String event) throws InterruptedException {
        long end = System.currentTimeMillis() + 5000;
        while (!l.events.contains(event)) {
            if (System.currentTimeMillis() > end) {
                throw new AssertionError("no " + event + " in " + l.events);
            }
            Thread.sleep(2);
        }
    }

    static Map<String, String> query(FakeTransport.Conn c) {
        Map<String, String> q = new LinkedHashMap<>();
        for (String p : c.uri.getRawQuery().split("&")) {
            int eq = p.indexOf('=');
            q.put(p.substring(0, eq), PercentEncoding.decode(p.substring(eq + 1)));
        }
        return q;
    }

    static long totalBytes(FakeTransport.Conn c) {
        return c.audioBytes();
    }

    static byte[] concat(List<byte[]> chunks) {
        int n = 0;
        for (byte[] b : chunks) {
            n += b.length;
        }
        byte[] out = new byte[n];
        int o = 0;
        for (byte[] b : chunks) {
            System.arraycopy(b, 0, out, o, b.length);
            o += b.length;
        }
        return out;
    }

    // ------------------------------------------------------------------ happy paths and frames

    @Test
    void nativeHappyPathStatesFramesAndHandshake() throws Exception {
        YuguClient c = client();
        RecordingListener l = new RecordingListener();
        StreamSession s = open(c, l, null);
        assertTrue(s.getSessionId().startsWith("ws-"));
        sendAll(s, PCM);
        s.end();
        s.end();
        EvalResult r = s.result().get(10, TimeUnit.SECONDS);
        await(l);
        assertEquals(List.of("CONNECTING", "CONNECTED", "STARTED", "ENDING", "COMPLETED", "CLOSED"), l.states());
        assertEquals(List.of("connected", "started", "result", "closed:1000"), l.callbacks());
        assertEquals(91.5, r.getOverall());
        assertEquals(s.getIdempotencyKey(), r.getIdempotencyKey());
        assertEquals(1, r.getAttempts());
        assertEquals(SessionState.CLOSED, s.getState());
        assertFalse(s.isActive());
        FakeTransport.Conn conn = fake.connections.get(0);
        assertEquals("/api/v1/ws/evaluate", conn.uri.getPath());
        assertEquals("ws", conn.uri.getScheme());
        Map<String, String> q = query(conn);
        assertEquals("mock-app-key", q.get("appKey"));
        assertEquals(s.getIdempotencyKey(), q.get("idempotencyKey"));
        assertTrue(s.getIdempotencyKey().matches("[0-9a-f]{32}"));
        String sig = q.remove("signature");
        assertEquals(Signer.sign(q, "mock-secret-key"), sig, "every query parameter except signature is signed");
        assertEquals("yugu-java-sdk/2.0.0", conn.headers.get("User-Agent"));
        JsonNode start = Json.read(conn.texts.get(0));
        assertEquals("start", start.get("cmd").asText());
        assertEquals("sentence", start.get("coreType").asText());
        assertEquals("今天天气很好", start.get("referenceText").asText());
        assertEquals("zh-CN", start.get("language").asText());
        assertEquals(s.getIdempotencyKey(), start.get("idempotencyKey").asText());
        assertEquals("{\"cmd\":\"end\"}", conn.texts.get(conn.texts.size() - 1));
        assertEquals(2, conn.texts.size());
        assertArrayEquals(PCM, concat(conn.binaries));
        assertEquals(1000, conn.closeCode);
        assertEquals(0, c.getOpenSessionCount());
        assertTrue(events.events.contains("session:COMPLETED"));
    }

    @Test
    void audioAndEndBeforeStartAreQueuedInOrder() throws Exception {
        for (AudioBufferPolicy policy : AudioBufferPolicy.values()) {
            fake = new FakeTransport();
            fake.scripts = n -> {
                FakeTransport.Script sc = new FakeTransport.Script();
                sc.answerStart = false;
                return sc;
            };
            YuguClient c = client(b -> b.audioBufferPolicy(policy));
            RecordingListener l = new RecordingListener();
            StreamSession s = open(c, l, null);
            sendAll(s, PCM);
            s.end();
            FakeTransport.Conn conn = fake.awaitConnection(1, 3000);
            awaitEvent(l, "connected");
            Thread.sleep(30);
            assertEquals(0, conn.binaries.size(), "nothing is sent before started");
            conn.serverSend(FakeTransport.STARTED);
            await(l);
            assertNotNull(l.result, policy + " " + l.events);
            assertArrayEquals(PCM, concat(conn.binaries), policy.name());
            assertEquals("{\"cmd\":\"end\"}", conn.texts.get(conn.texts.size() - 1));
            assertEquals(policy, s.getBufferPolicy());
        }
    }

    @Test
    void endFrameNeverOvertakesQueuedAudio() throws Exception {
        // "started" races with sendAudio() and end(): the end frame must still follow every chunk
        YuguClient c = client();
        java.util.Random rnd = new java.util.Random(3);
        byte[] audio = java.util.Arrays.copyOf(PCM, 640 * 40);
        for (int i = 0; i < 40; i++) {
            fake.scripts = n -> {
                FakeTransport.Script sc = new FakeTransport.Script();
                sc.answerStart = false;
                return sc;
            };
            int before = fake.connections.size();
            RecordingListener l = new RecordingListener();
            StreamSession s = open(c, l, null);
            FakeTransport.Conn conn = fake.awaitConnection(before + 1, 3000);
            awaitEvent(l, "connected");
            long pause = rnd.nextInt(300);
            Thread starter = new Thread(() -> {
                long until = System.nanoTime() + pause * 1000;
                while (System.nanoTime() < until) {
                    Thread.onSpinWait();
                }
                conn.serverSend(FakeTransport.STARTED);
            });
            starter.start();
            sendAll(s, audio);
            s.end();
            starter.join();
            await(l);
            assertNotNull(l.result, l.events.toString());
            assertArrayEquals(audio, concat(conn.binaries), "iteration " + i);
        }
    }

    @Test
    void compatStreamWithProgressFrames() throws Exception {
        fake.scripts = n -> {
            FakeTransport.Script sc = new FakeTransport.Script();
            sc.resultFrame = Fixtures.json("fixtures/platform/ws_compat_sent_eval_cn_frames.json").get(3).get("frame").toString();
            return sc;
        };
        YuguClient c = client();
        RecordingListener l = new RecordingListener();
        StreamSession s = c.streamEvaluateCompat(new CompatConfig("sent.eval.cn", "今天天气很好").language("zh-CN")
                .realtimeFeedback(true).param("agegroup", 2), l);
        l.session = s;
        FakeTransport.Conn conn = fake.awaitConnection(1, 3000);
        awaitEvent(l, "started");
        conn.serverSend("{\"eof\":0,\"result\":{\"bytes\":16000}}");
        conn.serverSend("{\"event\":\"pong\",\"ts\":1}");
        conn.serverSend("{\"event\":\"started\",\"coreType\":\"sent.eval.cn\"}");
        conn.serverSend("{\"event\":\"connected\"}");
        conn.serverSend("{\"event\":\"something-new\"}");
        conn.serverSend("{\"note\":\"no event\"}");
        sendAll(s, PCM);
        s.end();
        await(l);
        assertEquals("/sent.eval.cn", conn.uri.getPath());
        JsonNode param = Json.read(conn.texts.get(0));
        assertNull(param.get("cmd"), "compat parameter frame has no cmd");
        assertEquals("今天天气很好", param.get("refText").asText());
        assertEquals("zh-CN", param.get("language").asText());
        assertTrue(param.get("realtime_feedback").asBoolean());
        assertEquals(2, param.get("agegroup").asInt());
        assertEquals(s.getIdempotencyKey(), param.get("idempotencyKey").asText());
        assertEquals(1, l.partials.size());
        assertEquals(16000, l.partials.get(0).getBytes());
        assertEquals(94.6, l.result.getOverall());
        assertEquals("eval_a5ddd0c68840", l.result.getRecordId());
        assertEquals(List.of("connected", "started", "partial", "result", "closed:1000"), l.callbacks());
    }

    @Test
    void tokenAuthAndNoKeyMode() throws Exception {
        YuguClient c = client(b -> b.token("mock-jwt-token").apiKey(null, null).autoIdempotencyKey(false));
        RecordingListener l = new RecordingListener();
        StreamSession s = open(c, l, null);
        sendAll(s, PCM);
        s.end();
        await(l);
        Map<String, String> q = query(fake.connections.get(0));
        assertEquals("mock-jwt-token", q.get("token"));
        assertFalse(q.containsKey("signature"));
        assertFalse(q.containsKey("idempotencyKey"));
        assertNull(s.getIdempotencyKey());
        assertNull(Json.read(fake.connections.get(0).texts.get(0)).get("idempotencyKey"));
    }

    @Test
    void sessionWithoutKeyDoesNotReconnect() throws Exception {
        fake.scripts = n -> {
            FakeTransport.Script sc = new FakeTransport.Script();
            sc.failAfterBinary = 3;
            return sc;
        };
        YuguClient c = client(b -> b.autoIdempotencyKey(false));
        RecordingListener l = new RecordingListener();
        StreamSession s = open(c, l, null);
        sendAll(s, PCM);
        await(l);
        assertSame(NetworkException.class, l.error.getClass());
        assertEquals(1, fake.connections.size());
    }

    // ------------------------------------------------------------------ reconnect and buffer policies

    @Test
    void replayReconnectSendsTheWholeAudioAgainWithTheSameKey() throws Exception {
        fake.scripts = n -> {
            FakeTransport.Script sc = new FakeTransport.Script();
            if (n == 1) {
                sc.failAfterBinary = 10;
            }
            return sc;
        };
        YuguClient c = client();
        RecordingListener l = new RecordingListener();
        StreamSession s = open(c, l, null);
        sendAll(s, PCM);
        awaitEvent(l, "reconnected:1");
        s.end();
        await(l);
        assertNotNull(l.result, l.events.toString());
        assertEquals(2, fake.connections.size());
        FakeTransport.Conn second = fake.connections.get(1);
        assertArrayEquals(PCM, concat(second.binaries), "REPLAY resends every byte to the new server session");
        assertEquals(second.texts.get(0), fake.connections.get(0).texts.get(0), "same start frame, same key");
        Map<String, String> q1 = query(fake.connections.get(0));
        Map<String, String> q2 = query(second);
        assertEquals(q1.get("idempotencyKey"), q2.get("idempotencyKey"));
        assertNotEquals(q1.get("nonce"), q2.get("nonce"));
        assertEquals(List.of("CONNECTING", "CONNECTED", "STARTED", "RECONNECTING", "CONNECTING", "CONNECTED", "STARTED",
                "ENDING", "COMPLETED", "CLOSED"), l.states());
        assertEquals(List.of("connected", "started", "reconnecting:1", "connected", "started", "reconnected:1", "result",
                "closed:1000"), l.callbacks());
        assertEquals(0, l.reconnected.get(0)[1], "nothing dropped with REPLAY");
        assertSame(NetworkException.class, l.reconnectCauses.get(0).getClass());
        assertEquals(2, l.result.getAttempts());
        assertTrue(events.events.contains("reconnect:1:true"));
        assertFalse(logs.matching("reconnect 1/8 in").isEmpty(), logs.all());
    }

    @Test
    void replayAfterEndResendsAudioAndEnd() throws Exception {
        fake.scripts = n -> n == 1 ? new FakeTransport.Script().silentAfterStart() : new FakeTransport.Script();
        RecordingListener l = new RecordingListener();
        StreamSession s = open(client(), l, null);
        sendAll(s, PCM);
        s.end();
        awaitEvent(l, "state:ENDING");
        fake.connections.get(0).fail(new IOException("connection reset"));
        await(l);
        assertNotNull(l.result, l.events.toString());
        FakeTransport.Conn second = fake.connections.get(1);
        assertArrayEquals(PCM, concat(second.binaries));
        assertEquals(2, second.texts.size(), "start frame and end frame again");
        assertEquals("{\"cmd\":\"end\"}", second.texts.get(1));
        assertEquals(List.of("CONNECTING", "CONNECTED", "STARTED", "ENDING", "RECONNECTING", "CONNECTING", "CONNECTED",
                "STARTED", "ENDING", "COMPLETED", "CLOSED"), l.states());
    }

    @Test
    void dropPolicyScoresOnlyAudioAfterTheReconnect() throws Exception {
        fake.scripts = n -> {
            FakeTransport.Script sc = new FakeTransport.Script();
            if (n == 1) {
                sc.failAfterBinary = 5;
            }
            return sc;
        };
        YuguClient c = client(b -> b.audioBufferPolicy(AudioBufferPolicy.DROP));
        RecordingListener l = new RecordingListener();
        StreamSession s = open(c, l, null);
        byte[] first = java.util.Arrays.copyOfRange(PCM, 0, 640 * 20);
        byte[] second = java.util.Arrays.copyOfRange(PCM, 640 * 20, PCM.length);
        sendAll(s, first);
        awaitEvent(l, "reconnected:1");
        sendAll(s, second);
        s.end();
        await(l);
        assertNotNull(l.result, l.events.toString());
        assertArrayEquals(second, concat(fake.connections.get(1).binaries));
        assertEquals(first.length, l.reconnected.get(0)[1], "droppedBytes reports the audio the result does not cover");
    }

    @Test
    void dropPolicyFailsWhenTheConnectionBreaksAfterEnd() throws Exception {
        fake.scripts = n -> new FakeTransport.Script().silentAfterStart();
        YuguClient c = client(b -> b.audioBufferPolicy(AudioBufferPolicy.DROP));
        RecordingListener l = new RecordingListener();
        StreamSession s = open(c, l, null);
        sendAll(s, PCM);
        s.end();
        awaitEvent(l, "state:ENDING");
        fake.connections.get(0).fail(new IOException("reset"));
        await(l);
        assertSame(NetworkException.class, l.error.getClass());
        assertEquals(1, fake.connections.size());
        ExecutionException ee = assertThrows(ExecutionException.class, () -> s.result().get(1, TimeUnit.SECONDS));
        assertSame(l.error, ee.getCause());
    }

    @Test
    void dropPolicyWithoutNewAudioFailsLocally() throws Exception {
        fake.scripts = n -> {
            FakeTransport.Script sc = new FakeTransport.Script();
            if (n == 1) {
                sc.failAfterBinary = 2;
            }
            return sc;
        };
        YuguClient c = client(b -> b.audioBufferPolicy(AudioBufferPolicy.DROP));
        RecordingListener l = new RecordingListener();
        StreamSession s = open(c, l, null);
        sendAll(s, java.util.Arrays.copyOf(PCM, 640 * 5));
        awaitEvent(l, "reconnected:1");
        s.end();
        await(l);
        assertSame(IllegalSessionStateException.class, l.error.getClass());
        assertEquals(90009, l.error.getCode());
    }

    @Test
    void failPolicyFailsAtOnce() throws Exception {
        fake.scripts = n -> {
            FakeTransport.Script sc = new FakeTransport.Script();
            sc.failAfterBinary = 3;
            return sc;
        };
        YuguClient c = client(b -> b.audioBufferPolicy(AudioBufferPolicy.FAIL));
        RecordingListener l = new RecordingListener();
        StreamSession s = open(c, l, null);
        sendAll(s, PCM);
        s.end();
        await(l);
        assertSame(NetworkException.class, l.error.getClass());
        assertEquals(90001, l.error.getCode());
        assertEquals(1, fake.connections.size());
        assertEquals(0, l.count("reconnecting"));
        assertEquals("closed:1006", l.events.get(l.events.size() - 1));
        List<String> st = l.states();
        assertEquals(List.of("CONNECTING", "CONNECTED", "STARTED"), st.subList(0, 3));
        assertEquals(List.of("FAILED", "CLOSED"), st.subList(st.size() - 2, st.size()));
        assertFalse(st.contains("RECONNECTING"));
    }

    @Test
    void reconnectExhaustedReportsTheLastCause() throws Exception {
        fake.scripts = n -> {
            FakeTransport.Script sc = new FakeTransport.Script();
            if (n == 1) {
                sc.failAfterBinary = 2;
            } else {
                sc.connectError = new java.net.ConnectException("refused " + n);
            }
            return sc;
        };
        YuguClient c = client(b -> b.reconnect(FAST.toBuilder().maxAttempts(3).build()));
        RecordingListener l = new RecordingListener();
        StreamSession s = open(c, l, null);
        sendAll(s, PCM);
        await(l);
        assertEquals(4, fake.connections.size(), "1 connection + 3 reconnect attempts");
        assertEquals(90006, l.error.getCode());
        assertSame(NetworkException.class, l.error.getClass());
        assertFalse(l.error.isRetryable());
        assertTrue(l.error.getCause() instanceof NetworkException);
        assertTrue(l.error.getCause().getMessage().contains("refused 4"), l.error.getCause().getMessage());
        assertEquals(List.of("connected", "started", "reconnecting:1", "reconnecting:2", "reconnecting:3", "error:90006",
                "closed:1006"), l.callbacks());
        assertEquals(List.of("reconnect:1:false", "reconnect:2:false", "reconnect:3:false"),
                events.events.stream().filter(e -> e.startsWith("reconnect:")).collect(java.util.stream.Collectors.toList()));
        assertEquals(s.getIdempotencyKey(), l.error.getIdempotencyKey());
    }

    @Test
    void nonRetryableHandshakeFailureFailsWithoutReconnect() throws Exception {
        YuguException auth = YuguErrors.create(ErrorDetails.builder().category(ErrorCategory.AUTH).httpStatus(401)
                .message("WebSocket handshake rejected").build());
        fake.scripts = n -> new FakeTransport.Script().connectError(auth);
        YuguClient c = client();
        RecordingListener l = new RecordingListener();
        open(c, l, null);
        await(l);
        assertSame(AuthException.class, l.error.getClass());
        assertEquals(1, fake.connections.size());
        assertEquals(List.of("CONNECTING", "FAILED", "CLOSED"), l.states());
    }

    @Test
    void errorFramesRetryableReconnectOthersFail() throws Exception {
        fake.scripts = n -> {
            FakeTransport.Script sc = new FakeTransport.Script();
            if (n == 1) {
                sc.resultFrame = "{\"event\":\"error\",\"code\":50200,\"message\":\"mock upstream error\"}";
            }
            return sc;
        };
        YuguClient c = client();
        RecordingListener l = new RecordingListener();
        StreamSession s = open(c, l, null);
        sendAll(s, PCM);
        s.end();
        await(l);
        assertNotNull(l.result);
        assertEquals(50200, l.reconnectCauses.get(0).getCode());
        assertArrayEquals(PCM, concat(fake.connections.get(1).binaries));

        fake = new FakeTransport();
        fake.scripts = n -> {
            FakeTransport.Script sc = new FakeTransport.Script();
            sc.resultFrame = "{\"event\":\"error\",\"code\":40001,\"message\":\"referenceText 不能为空\"}";
            return sc;
        };
        RecordingListener l2 = new RecordingListener();
        StreamSession s2 = open(client(), l2, null);
        sendAll(s2, PCM);
        s2.end();
        await(l2);
        assertSame(InvalidParameterException.class, l2.error.getClass());
        assertEquals(1, fake.connections.size());
        assertNotNull(l2.error.getRawBody());
        assertEquals("closed:1000", l2.events.get(l2.events.size() - 1));
    }

    @Test
    void abnormalCloseReconnectsNormalCloseBeforeResultFails() throws Exception {
        fake.scripts = n -> new FakeTransport.Script().silentAfterStart();
        YuguClient c = client();
        RecordingListener l = new RecordingListener();
        StreamSession s = open(c, l, null);
        awaitEvent(l, "started");
        fake.connections.get(0).serverClose(1011, "mock server error");
        awaitEvent(l, "reconnected:1");
        assertTrue(l.reconnectCauses.get(0).getRawMessage().contains("1011"));
        fake.connections.get(1).serverClose(1000, "bye");
        await(l);
        assertSame(ProtocolViolationException.class, l.error.getClass());
        assertEquals("closed:1000", l.events.get(l.events.size() - 1));
        assertTrue(s.awaitClosed(1, TimeUnit.SECONDS));
    }

    @Test
    void largeChunksAreSplitIntoFramesBelowThePlatformLimit() throws Exception {
        byte[] big = new byte[200_000];
        new java.util.Random(5).nextBytes(big);
        RecordingListener l = new RecordingListener();
        StreamSession s = open(client(), l, null);
        s.sendAudio(big);
        s.sendAudio(big, 10, 40_000);
        s.end();
        await(l);
        FakeTransport.Conn conn = fake.connections.get(0);
        for (byte[] f : conn.binaries) {
            assertTrue(f.length <= StreamSession.MAX_FRAME_BYTES, "frame of " + f.length + " bytes");
        }
        byte[] expected = new byte[240_000];
        System.arraycopy(big, 0, expected, 0, 200_000);
        System.arraycopy(big, 10, expected, 200_000, 40_000);
        assertArrayEquals(expected, concat(conn.binaries));
        assertEquals(9, conn.binaries.size(), "7 frames of at most 32000 bytes for 200000 bytes, 2 for 40000");
        assertEquals(32000, conn.binaries.get(0).length);
    }

    @Test
    void closeCodesThatReconnectingCannotFixFailAtOnce() throws Exception {
        for (int code : new int[]{1002, 1003, 1007, 1008, 1009, 1010, 4000, 4001, 4403, 4999}) {
            fake = new FakeTransport();
            fake.scripts = n -> new FakeTransport.Script().silentAfterStart();
            RecordingListener l = new RecordingListener();
            open(client(), l, null);
            awaitEvent(l, "started");
            fake.connections.get(0).serverClose(code, "rejected");
            await(l);
            assertSame(ProtocolViolationException.class, l.error.getClass(), code + " " + l.events);
            assertTrue(l.error.getRawMessage().contains(String.valueOf(code)));
            assertEquals(1, fake.connections.size(), "no reconnect after " + code);
            assertEquals("closed:" + code, l.events.get(l.events.size() - 1));
        }
        for (int code : new int[]{1001, 1006, 1011, 1012, 1013, 1014, 3000, 3999}) {
            assertFalse(StreamSession.isPermanentClose(code), String.valueOf(code));
        }
    }

    @Test
    void sessionReconnectsAtMostThreeTimesMaxAttemptsInTotal() throws Exception {
        // every connection is accepted and dropped right after "started": each reconnect succeeds, so the
        // consecutive counter keeps resetting; only the per session cap ends the loop
        fake.scripts = n -> {
            FakeTransport.Script sc = new FakeTransport.Script();
            sc.closeAfterStart = 1011;
            return sc;
        };
        RecordingListener l = new RecordingListener();
        StreamSession s = open(client(), l, null);
        sendAll(s, java.util.Arrays.copyOf(PCM, 640 * 10));
        await(l);
        assertEquals(8, FAST.getMaxAttempts(), "default maxAttempts");
        assertEquals(25, fake.connections.size(), "1 connection + 24 reconnects");
        assertEquals(24, l.count("reconnecting:1"), "every reconnect was the first of its outage");
        assertEquals(24, l.count("reconnected:1"));
        assertEquals(90006, l.error.getCode());
        assertSame(NetworkException.class, l.error.getClass());
        assertTrue(l.error.getRawMessage().contains("reconnect limit of 24"), l.error.getRawMessage());
        assertTrue(l.error.getCause().getMessage().contains("1011"), String.valueOf(l.error.getCause()));
        assertEquals(24, events.count("reconnect:1:true"));

        fake = new FakeTransport();
        fake.scripts = n -> {
            FakeTransport.Script sc = new FakeTransport.Script();
            sc.closeAfterStart = 1001;
            return sc;
        };
        RecordingListener l2 = new RecordingListener();
        open(client(b -> b.reconnect(FAST.toBuilder().maxAttempts(2).build())), l2, null);
        await(l2);
        assertEquals(7, fake.connections.size(), "3 x 2 reconnects");
        assertEquals(90006, l2.error.getCode());
    }

    @Test
    void resultTimeoutTriggersReconnectOrFails() throws Exception {
        fake.scripts = n -> {
            FakeTransport.Script sc = new FakeTransport.Script();
            if (n == 1) {
                sc.answerEnd = false;
            }
            return sc;
        };
        YuguClient c = client();
        RecordingListener l = new RecordingListener();
        StreamSession s = open(c, l, StreamOptions.builder().resultTimeoutMs(200).build());
        sendAll(s, PCM);
        s.end();
        await(l);
        assertNotNull(l.result);
        assertEquals(90007, l.reconnectCauses.get(0).getCode());
        assertSame(RequestTimeoutException.class, l.reconnectCauses.get(0).getClass());

        fake = new FakeTransport();
        fake.scripts = n -> new FakeTransport.Script().silentAfterStart();
        RecordingListener l2 = new RecordingListener();
        StreamSession s2 = open(client(), l2, StreamOptions.builder().resultTimeoutMs(150).reconnect(ReconnectPolicy.disabled()).build());
        sendAll(s2, PCM);
        s2.end();
        await(l2);
        assertEquals(90007, l2.error.getCode());
    }

    @Test
    void missingPongIsATransportFailure() throws Exception {
        fake.scripts = n -> {
            FakeTransport.Script sc = new FakeTransport.Script();
            sc.autoPong = n > 1;
            return sc;
        };
        YuguClient c = client(b -> b.pingIntervalMs(30).pongTimeoutMs(150));
        RecordingListener l = new RecordingListener();
        StreamSession s = open(c, l, null);
        awaitEvent(l, "reconnected:1");
        assertEquals(90002, l.reconnectCauses.get(0).getCode());
        assertTrue(l.reconnectCauses.get(0).getRawMessage().contains("pong"));
        assertTrue(fake.connections.get(0).pings >= 1);
        Thread.sleep(400);
        assertEquals(2, fake.connections.size(), "pongs keep the second connection alive");
        assertTrue(fake.connections.get(1).pings >= 3);
        sendAll(s, PCM);
        s.end();
        await(l);
        assertNotNull(l.result);
    }

    @Test
    void handshakeTimeoutReconnects() throws Exception {
        fake.scripts = n -> {
            FakeTransport.Script sc = new FakeTransport.Script();
            if (n == 1) {
                sc.sendConnected = false;
            } else if (n == 2) {
                sc.neverOpen = true;
            }
            return sc;
        };
        YuguClient c = client(b -> b.connectTimeoutMs(150));
        RecordingListener l = new RecordingListener();
        StreamSession s = open(c, l, null);
        sendAll(s, PCM);
        s.end();
        await(l);
        assertNotNull(l.result, l.events.toString());
        assertEquals(3, fake.connections.size());
        assertEquals(90002, l.reconnectCauses.get(0).getCode());
        assertEquals(90002, l.reconnectCauses.get(1).getCode());
        assertEquals(List.of("reconnecting:1", "reconnecting:2", "connected", "started", "reconnected:2", "result", "closed:1000"),
                l.callbacks());
    }

    @Test
    void replayOverflowFailsTheNextReconnect() throws Exception {
        fake.scripts = n -> {
            FakeTransport.Script sc = new FakeTransport.Script();
            sc.failAfterBinary = 30;
            return sc;
        };
        YuguClient c = client();
        RecordingListener l = new RecordingListener();
        StreamSession s = open(c, l, StreamOptions.builder().replayBufferBytes(640 * 10).build());
        awaitEvent(l, "started");
        sendAll(s, PCM);
        await(l);
        assertEquals(90008, l.error.getCode());
        assertSame(IllegalSessionStateException.class, l.error.getClass());
        assertFalse(logs.matching("replay buffer exceeded").isEmpty());

        fake = new FakeTransport();
        fake.scripts = n -> {
            FakeTransport.Script sc = new FakeTransport.Script();
            sc.answerStart = false;
            return sc;
        };
        RecordingListener l2 = new RecordingListener();
        StreamSession s2 = open(client(), l2, StreamOptions.builder().replayBufferBytes(1000).build());
        s2.sendAudio(new byte[800]);
        s2.sendAudio(new byte[800]);
        await(l2);
        assertEquals(90008, l2.error.getCode(), "overflow before the session started");

        fake = new FakeTransport();
        fake.scripts = n -> {
            FakeTransport.Script sc = new FakeTransport.Script();
            sc.answerStart = false;
            return sc;
        };
        RecordingListener l3 = new RecordingListener();
        StreamSession s3 = open(client(b -> b.audioBufferPolicy(AudioBufferPolicy.FAIL)), l3,
                StreamOptions.builder().replayBufferBytes(1000).build());
        s3.sendAudio(new byte[800]);
        s3.sendAudio(new byte[800]);
        await(l3);
        assertEquals(90008, l3.error.getCode());
    }

    @Test
    void replayOverflowWhileReconnectingFailsInsteadOfLosingAudio() throws Exception {
        fake.scripts = n -> {
            FakeTransport.Script sc = new FakeTransport.Script();
            if (n == 1) {
                sc.failAfterBinary = 2;
            }
            return sc;
        };
        RecordingListener l = new RecordingListener();
        StreamSession s = open(client(b -> b.reconnect(ReconnectPolicy.builder().initialDelayMs(2000).jitter(0).build())), l,
                StreamOptions.builder().replayBufferBytes(640 * 6).build());
        awaitEvent(l, "started");
        s.sendAudio(new byte[640]);
        s.sendAudio(new byte[640]);
        awaitEvent(l, "reconnecting:1");
        for (int i = 0; i < 6; i++) {
            s.sendAudio(new byte[640]);
        }
        await(l);
        assertEquals(90008, l.error.getCode(), l.events.toString());
        assertEquals(1, fake.connections.size(), "failed at once, no reconnect with partial audio");
    }

    // ------------------------------------------------------------------ precheck, strict audio, protocol

    @Test
    void precheckAtEnd() throws Exception {
        YuguClient reject = client(b -> b.audioPrecheck(AudioPrecheckMode.REJECT));
        RecordingListener l = new RecordingListener();
        StreamSession s = open(reject, l, null);
        sendAll(s, Fixtures.pcm("silent.wav"));
        s.end();
        await(l);
        assertSame(AudioQualityException.class, l.error.getClass());
        assertEquals(90103, l.error.getCode());
        assertTrue(fake.connections.get(0).texts.stream().noneMatch(t -> t.contains("\"end\"")),
                "no end frame: nothing is scored or billed");

        RecordingListener lw = new RecordingListener();
        StreamSession sw = open(client(), lw, null);
        sendAll(sw, Fixtures.pcm("low_volume.wav"));
        sw.end();
        await(lw);
        List<String> cb = lw.callbacks();
        assertEquals(1, lw.count("warning:90104"), cb.toString());
        assertTrue(cb.indexOf("warning:90104") < cb.indexOf("result"), cb.toString());
        assertEquals(List.of("result", "closed:1000"), cb.subList(cb.size() - 2, cb.size()));
        assertEquals(90104, lw.result.getLocalWarnings().get(0).getCode());

        RecordingListener lo = new RecordingListener();
        StreamSession so = open(client(b -> b.audioPrecheck(AudioPrecheckMode.OFF)), lo, null);
        sendAll(so, Fixtures.pcm("silent.wav"));
        so.end();
        await(lo);
        assertTrue(lo.result.getLocalWarnings().isEmpty());
    }

    @Test
    void strictAudioTurnsWarning1001IntoAnError() throws Exception {
        fake.scripts = n -> {
            FakeTransport.Script sc = new FakeTransport.Script();
            sc.resultFrame = FakeTransport.RESULT.replace("\"warnings\":[]", "\"warnings\":[1001]");
            return sc;
        };
        RecordingListener l = new RecordingListener();
        StreamSession s = open(client(b -> b.strictAudio(true)), l, null);
        sendAll(s, PCM);
        s.end();
        await(l);
        assertSame(AudioQualityException.class, l.error.getClass());
        assertEquals(1001, l.error.getCode());
        assertNotNull(((AudioQualityException) l.error).getResult());
    }

    @Test
    void garbledFramesAndBadResultsFail() throws Exception {
        fake.scripts = n -> new FakeTransport.Script().silentAfterStart();
        RecordingListener l = new RecordingListener();
        open(client(), l, null);
        awaitEvent(l, "started");
        fake.connections.get(0).serverSend("not json at all");
        await(l);
        assertSame(ProtocolViolationException.class, l.error.getClass());
        assertEquals("not json at all", l.error.getRawBody());

        fake = new FakeTransport();
        fake.scripts = n -> {
            FakeTransport.Script sc = new FakeTransport.Script();
            sc.resultFrame = "{\"event\":\"result\",\"eof\":1}";
            return sc;
        };
        RecordingListener l2 = new RecordingListener();
        StreamSession s2 = open(client(), l2, null);
        sendAll(s2, PCM);
        s2.end();
        await(l2);
        assertSame(ProtocolViolationException.class, l2.error.getClass());
    }

    // ------------------------------------------------------------------ lifecycle

    @Test
    void cancelDeliversNeitherResultNorError() throws Exception {
        fake.scripts = n -> new FakeTransport.Script().silentAfterStart();
        RecordingListener l = new RecordingListener();
        StreamSession s = open(client(), l, null);
        awaitEvent(l, "started");
        s.cancel();
        s.cancel();
        s.close();
        await(l);
        assertEquals(List.of("CONNECTING", "CONNECTED", "STARTED", "CANCELLED", "CLOSED"), l.states());
        assertEquals("closed:1000", l.events.get(l.events.size() - 1));
        ExecutionException e = assertThrows(ExecutionException.class, () -> s.result().get(1, TimeUnit.SECONDS));
        assertSame(RequestCancelledException.class, e.getCause().getClass());
        assertEquals(1000, fake.connections.get(0).closeCode);
        s.sendAudio(new byte[10]);
        s.end();
        assertEquals(SessionState.CLOSED, s.getState());
        assertTrue(s.toString().contains("CLOSED"));
    }

    @Test
    void cancelWhileReconnectingStopsReconnecting() throws Exception {
        fake.scripts = n -> {
            FakeTransport.Script sc = new FakeTransport.Script();
            sc.failAfterBinary = 1;
            return sc;
        };
        RecordingListener l = new RecordingListener();
        StreamSession s = open(client(b -> b.reconnect(ReconnectPolicy.builder().initialDelayMs(400).jitter(0).build())), l, null);
        s.sendAudio(new byte[640]);
        awaitEvent(l, "reconnecting:1");
        assertEquals(SessionState.RECONNECTING, s.getState());
        s.cancel();
        await(l);
        Thread.sleep(600);
        assertEquals(1, fake.connections.size());
    }

    @Test
    void clientCloseCancelsOpenSessions() throws Exception {
        fake.scripts = n -> new FakeTransport.Script().silentAfterStart();
        YuguClient c = client();
        RecordingListener a = new RecordingListener();
        RecordingListener b = new RecordingListener();
        open(c, a, null);
        open(c, b, null);
        awaitEvent(a, "started");
        awaitEvent(b, "started");
        assertEquals(2, c.getOpenSessionCount());
        c.close();
        assertTrue(a.awaitClosed(1000) && b.awaitClosed(1000), "close waits for the sessions");
        a.assertGuarantees();
        b.assertGuarantees();
        assertTrue(a.states().contains("CANCELLED"));
        assertEquals(0, c.getOpenSessionCount());
        assertThrows(IllegalSessionStateException.class, () -> c.streamEvaluate(sentence(), new RecordingListener()));
    }

    @Test
    void closingTheClientFromACallbackDoesNotDeadlock() throws Exception {
        fake.scripts = n -> new FakeTransport.Script().silentAfterStart();
        YuguClient c = client();
        RecordingListener l = new RecordingListener();
        l.onStartedHook = c::close;
        open(c, l, null);
        await(l);
        assertTrue(l.states().contains("CANCELLED"));
    }

    @Test
    void argumentAndStateChecks() throws Exception {
        YuguClient c = client();
        assertThrows(InvalidParameterException.class, () -> c.streamEvaluate(new EvaluateConfig("sentence", "", "zh-CN"), new RecordingListener()));
        assertThrows(InvalidParameterException.class, () -> c.streamEvaluate(sentence(), null));
        assertThrows(InvalidParameterException.class, () -> c.streamEvaluateCompat(new CompatConfig("open.eval", "x"), new RecordingListener()));
        assertThrows(InvalidParameterException.class, () -> c.streamEvaluate(sentence(), new RecordingListener(),
                StreamOptions.builder().idempotencyKey("bad key").build()));
        assertThrows(InvalidParameterException.class, () -> c.streamEvaluate(sentence(), new RecordingListener(),
                StreamOptions.builder().resultTimeoutMs(0).build()));
        RecordingListener l = new RecordingListener();
        StreamSession s = open(c, l, StreamOptions.builder().idempotencyKey("caller-key-1").build());
        assertEquals("caller-key-1", s.getIdempotencyKey());
        assertThrows(InvalidParameterException.class, () -> s.sendAudio(new byte[0]));
        assertThrows(InvalidParameterException.class, () -> s.sendAudio(null));
        assertThrows(InvalidParameterException.class, () -> s.sendAudio(new byte[4], 3, 4));
        s.sendAudio(PCM, 0, 640);
        s.end();
        YuguException e = assertThrows(IllegalSessionStateException.class, () -> s.sendAudio(new byte[640]));
        assertEquals(90009, e.getCode());
        await(l);
        assertEquals("caller-key-1", query(fake.connections.get(0)).get("idempotencyKey"));
    }

    @Test
    void throwingListenerStillGetsOrderedTerminalCallbacks() throws Exception {
        List<String> seen = new ArrayList<>();
        java.util.concurrent.CountDownLatch closed = new java.util.concurrent.CountDownLatch(1);
        StreamListener bad = new StreamListener() {
            @Override
            public void onStateChanged(SessionState oldState, SessionState newState) {
                seen.add(newState.name());
                throw new IllegalStateException("listener bug");
            }

            @Override
            public void onResult(EvalResult result) {
                seen.add("result");
                throw new IllegalStateException("listener bug");
            }

            @Override
            public void onError(YuguException error) {
                seen.add("error");
            }

            @Override
            public void onClosed(int code, String reason) {
                seen.add("closed");
                closed.countDown();
            }
        };
        StreamSession s = client().streamEvaluate(sentence(), bad);
        sendAll(s, PCM);
        s.end();
        assertTrue(closed.await(5, TimeUnit.SECONDS));
        assertEquals(List.of("CONNECTING", "CONNECTED", "STARTED", "ENDING", "COMPLETED", "result", "CLOSED", "closed"), seen);
        assertFalse(logs.matching("listener.onStateChanged threw").isEmpty());
    }

    @Test
    void manySessionsOnOneClientStayIsolated() throws Exception {
        YuguClient c = client();
        List<RecordingListener> ls = new ArrayList<>();
        List<StreamSession> ss = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            RecordingListener l = new RecordingListener();
            ls.add(l);
            ss.add(open(c, l, null));
        }
        for (StreamSession s : ss) {
            sendAll(s, java.util.Arrays.copyOf(PCM, 640 * 50));
            s.end();
        }
        for (RecordingListener l : ls) {
            await(l);
            assertNotNull(l.result);
        }
        assertEquals(20, ss.stream().map(StreamSession::getIdempotencyKey).distinct().count());
        assertEquals(0, c.getOpenSessionCount());
    }
}
