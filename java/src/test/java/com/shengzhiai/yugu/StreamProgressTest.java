package com.shengzhiai.yugu;

import com.fasterxml.jackson.databind.JsonNode;
import com.shengzhiai.yugu.internal.Json;
import com.shengzhiai.yugu.model.CompatConfig;
import com.shengzhiai.yugu.model.EvaluateConfig;
import com.shengzhiai.yugu.model.ReadingWord;
import com.shengzhiai.yugu.model.StreamPartial;
import com.shengzhiai.yugu.testing.CapturingLogger;
import com.shengzhiai.yugu.testing.FakeTransport;
import com.shengzhiai.yugu.testing.Fixtures;
import com.shengzhiai.yugu.testing.RecordingListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reading progress of 2.1.0 on a scripted transport: the native start frame option, delivery of the
 * recorded platform frames on both channels, delivery while ENDING and the reconnect rule (REPLAY: a
 * progress partial is delivered only when readUnits and matchedUnits are both at least the last
 * delivered ones; DROP: the remembered progress is reset at every successful reconnect).
 */
class StreamProgressTest {
    static final ReconnectPolicy FAST = ReconnectPolicy.builder().initialDelayMs(10).maxDelayMs(20).build();
    static final byte[] PCM = Fixtures.pcm("zh_short.wav");
    static final String REF_ZH = "我是中国人，我是中国人。我们都是中国人。中华民族是一家人。";
    static final String DROPPED = "progress partial dropped";

    FakeTransport fake;
    CapturingLogger logs;
    List<YuguClient> clients = new ArrayList<>();

    @BeforeEach
    void setUp() {
        fake = new FakeTransport();
        logs = new CapturingLogger();
    }

    @AfterEach
    void tearDown() {
        clients.forEach(YuguClient::close);
    }

    YuguClient client(Consumer<YuguClient.Builder> cfg) {
        YuguClient.Builder b = YuguClient.builder().apiKey("mock-app-key", "mock-secret-key")
                .baseUrl("http://127.0.0.1:9").wsBaseUrl("ws://fake.test").logger(logs).logLevel(LogLevel.DEBUG).reconnect(FAST);
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

    static void sendAll(StreamSession s, byte[] pcm) {
        for (byte[] f : Fixtures.frames(pcm, 640)) {
            s.sendAudio(f);
        }
    }

    void await(RecordingListener l) throws InterruptedException {
        assertTrue(l.awaitClosed(10_000), "no terminal callback within 10 s: " + l.events);
        l.assertGuarantees();
    }

    static void awaitEvent(RecordingListener l, String event) throws InterruptedException {
        awaitCount(l, event, 1);
    }

    static void awaitCount(RecordingListener l, String event, long n) throws InterruptedException {
        long end = System.currentTimeMillis() + 5000;
        while (l.events.stream().filter(event::equals).count() < n) {
            if (System.currentTimeMillis() > end) {
                throw new AssertionError("expected " + n + " x " + event + " in " + l.events);
            }
            Thread.sleep(2);
        }
    }

    static void awaitPartials(RecordingListener l, int n) throws InterruptedException {
        long end = System.currentTimeMillis() + 5000;
        while (l.partials.size() < n) {
            if (System.currentTimeMillis() > end) {
                throw new AssertionError("expected " + n + " partials, got " + l.partials);
            }
            Thread.sleep(2);
        }
    }

    /** Recorded frames of a fixture: index 0 and 1 are connected and started, the last is the final result. */
    static List<String> frames(String fixture) {
        List<String> out = new ArrayList<>();
        for (JsonNode item : Fixtures.json("fixtures/platform/" + fixture)) {
            out.add(item.get("frame").toString());
        }
        return out;
    }

    /** A native-shaped progress frame; units are named w0, w1, ... */
    static String progress(long bytes, int read, int matched, int total) {
        StringBuilder units = new StringBuilder();
        for (int i = 0; i < total; i++) {
            units.append(i == 0 ? "" : ",").append("{\"word\":\"w").append(i).append("\",\"read\":").append(i < read)
                    .append(",\"matched\":").append(i < matched).append('}');
        }
        return "{\"eof\":0,\"result\":{\"bytes\":" + bytes + ",\"readUnits\":" + read + ",\"matchedUnits\":" + matched
                + ",\"totalUnits\":" + total + ",\"progress\":[" + units + "]}}";
    }

    static List<Integer> readUnits(List<StreamPartial> ps) {
        List<Integer> out = new ArrayList<>();
        for (StreamPartial p : ps) {
            out.add(p.getReadUnits());
        }
        return out;
    }

    static void assertNeverBack(List<StreamPartial> ps) {
        int read = -1;
        int matched = -1;
        for (StreamPartial p : ps) {
            if (p.hasProgress()) {
                assertTrue(p.getReadUnits() >= read && p.getMatchedUnits() >= matched, "progress went back at " + p + " in " + ps);
                read = p.getReadUnits();
                matched = p.getMatchedUnits();
            }
        }
    }

    // ------------------------------------------------------------------ request side

    @Test
    void nativeStartFrameCarriesRealtimeFeedbackOnlyWhenEnabled() throws Exception {
        YuguClient c = client();
        String[] expected = {
            "{\"cmd\":\"start\",\"coreType\":\"sentence\",\"referenceText\":\"今天天气很好\",\"language\":\"zh-CN\",\"idempotencyKey\":\"k-off\"}",
            "{\"cmd\":\"start\",\"coreType\":\"sentence\",\"referenceText\":\"今天天气很好\",\"language\":\"zh-CN\",\"idempotencyKey\":\"k-false\"}",
            "{\"cmd\":\"start\",\"coreType\":\"sentence\",\"referenceText\":\"今天天气很好\",\"language\":\"zh-CN\",\"realtimeFeedback\":true,"
                + "\"idempotencyKey\":\"k-on\"}",
        };
        EvaluateConfig[] configs = {sentence(), sentence().realtimeFeedback(false), sentence().realtimeFeedback(true)};
        String[] keys = {"k-off", "k-false", "k-on"};
        for (int i = 0; i < configs.length; i++) {
            RecordingListener l = new RecordingListener();
            StreamSession s = c.streamEvaluate(configs[i], l, StreamOptions.builder().idempotencyKey(keys[i]).build());
            l.session = s;
            sendAll(s, PCM);
            s.end();
            await(l);
            assertEquals(expected[i], fake.connections.get(i).texts.get(0), "byte-identical to 2.0.0 unless enabled");
        }
        assertFalse(sentence().isRealtimeFeedback());
        assertTrue(sentence().realtimeFeedback(true).isRealtimeFeedback());
        // REST: the flag never enters the signed config part
        String rest = Json.write(sentence().realtimeFeedback(true));
        assertEquals("{\"coreType\":\"sentence\",\"referenceText\":\"今天天气很好\",\"language\":\"zh-CN\"}", rest);
    }

    @Test
    void compatParameterFrameCarriesRealtimeFeedbackOnlyWhenEnabled() throws Exception {
        YuguClient c = client();
        for (boolean on : new boolean[]{false, true}) {
            RecordingListener l = new RecordingListener();
            StreamSession s = c.streamEvaluateCompat(new CompatConfig("sent.eval.cn", REF_ZH).realtimeFeedback(on), l);
            l.session = s;
            sendAll(s, PCM);
            s.end();
            await(l);
            JsonNode param = Json.read(fake.last().texts.get(0));
            assertEquals(on, param.has("realtime_feedback"), param.toString());
            assertFalse(param.has("realtimeFeedback"), "the compat channel keeps its own field name");
            assertFalse(new CompatConfig("sent.eval.cn", REF_ZH).realtimeFeedback(on).toFormFields().containsKey("realtime_feedback"));
        }
    }

    // ------------------------------------------------------------------ recorded platform frames

    @Test
    void recordedFramesAreDeliveredOnBothChannels() throws Exception {
        String[][] cases = {
            {"ws_compat_sent_eval_cn_progress_frames.json", "sent.eval.cn", REF_ZH},
            {"ws_compat_sent_eval_cn_progress_misread_frames.json", "sent.eval.cn", REF_ZH},
            {"ws_compat_sent_eval_progress_frames.json", "sent.eval", "Nice to see you again. I am a student, and I like reading books every day."},
            {"ws_native_sentence_progress_frames.json", null, REF_ZH},
        };
        YuguClient c = client();
        for (String[] cs : cases) {
            List<String> rec = frames(cs[0]);
            fake.scripts = n -> {
                FakeTransport.Script sc = new FakeTransport.Script();
                sc.answerEnd = false;
                return sc;
            };
            RecordingListener l = new RecordingListener();
            StreamSession s = cs[1] == null
                    ? c.streamEvaluate(new EvaluateConfig("sentence", cs[2], "zh-CN").realtimeFeedback(true), l)
                    : c.streamEvaluateCompat(new CompatConfig(cs[1], cs[2]).realtimeFeedback(true), l);
            l.session = s;
            awaitEvent(l, "started");
            FakeTransport.Conn conn = fake.last();
            List<String> partials = rec.subList(2, rec.size() - 1);
            int half = partials.size() / 2;
            for (String f : partials.subList(0, half)) {
                conn.serverSend(f);
            }
            sendAll(s, PCM);
            s.end();
            awaitEvent(l, "state:ENDING");
            // the platform keeps sending progress after end until the final frame
            for (String f : partials.subList(half, partials.size())) {
                conn.serverSend(f);
            }
            conn.serverSend(rec.get(rec.size() - 1));
            await(l);
            assertNotNull(l.result, cs[0] + " " + l.events);
            assertEquals(partials.size(), l.partials.size(), cs[0] + ": every recorded progress frame is delivered");
            for (int i = 0; i < partials.size(); i++) {
                JsonNode want = Json.read(partials.get(i));
                StreamPartial got = l.partials.get(i);
                assertEquals(want, got.getRaw(), cs[0] + ": the raw frame is passed on unchanged, Shengtong fields included");
                assertTrue(got.hasProgress(), cs[0]);
                assertEquals(want.path("result").get("bytes").asLong(), got.getBytes(), cs[0]);
                assertEquals(want.path("result").get("readUnits").asInt(), got.getReadUnits(), cs[0]);
                assertEquals(want.path("result").get("matchedUnits").asInt(), got.getMatchedUnits(), cs[0]);
                assertEquals(want.path("result").get("totalUnits").asInt(), got.getProgress().size(), cs[0]);
            }
            List<String> cb = l.callbacks();
            assertEquals(List.of("result", "closed:1000"), cb.subList(cb.size() - 2, cb.size()), "the final frame is the last");
            int ending = l.events.indexOf("state:ENDING");
            assertEquals(partials.size() - half, l.events.subList(ending, l.events.size()).stream().filter("partial"::equals).count(),
                    cs[0] + ": partials while ENDING");
            assertEquals(Json.read(rec.get(rec.size() - 1)).path("result").path("overall").asDouble(), l.result.getOverall());
            assertTrue(logs.matching(DROPPED).isEmpty(), "nothing dropped without a reconnect");
        }
    }

    // ------------------------------------------------------------------ reconnect rule

    @Test
    void replayProgressNeverGoesBackAfterAReconnect() throws Exception {
        List<String> rec = frames("ws_compat_sent_eval_cn_progress_frames.json");
        // readUnits of rec[2..21]: 0 0 0 0 2 5 10 10 10 10 15 15 17 18 21 21 21 21 24 25
        List<String> round = rec.subList(2, rec.size() - 1);
        fake.scripts = n -> {
            FakeTransport.Script sc = new FakeTransport.Script();
            sc.answerEnd = n > 1;
            return sc;
        };
        RecordingListener l = new RecordingListener();
        StreamSession s = client().streamEvaluateCompat(new CompatConfig("sent.eval.cn", REF_ZH).realtimeFeedback(true), l);
        l.session = s;
        assertEquals(AudioBufferPolicy.REPLAY, s.getBufferPolicy());
        awaitEvent(l, "started");
        FakeTransport.Conn first = fake.connections.get(0);
        for (String f : round.subList(0, 7)) {
            first.serverSend(f);
        }
        sendAll(s, PCM);
        awaitPartials(l, 7);
        first.fail(new IOException("connection reset"));
        awaitEvent(l, "reconnected:1");
        // the new server session computes the progress again from zero while the audio is replayed
        FakeTransport.Conn second = fake.connections.get(1);
        second.serverSend("{\"eof\":0,\"result\":{\"bytes\":640}}");
        for (String f : round) {
            second.serverSend(f);
        }
        second.serverSend(progress(180000, 25, 24, 25));
        second.serverSend("{\"eof\":0,\"result\":{\"bytes\":180640}}");
        s.end();
        await(l);
        assertNotNull(l.result, l.events.toString());

        assertEquals(List.of(0, 0, 0, 0, 2, 5, 10, -1, 10, 10, 10, 10, 15, 15, 17, 18, 21, 21, 21, 21, 24, 25, -1),
                readUnits(l.partials));
        assertNeverBack(l.partials);
        int reconnected = l.callbacks().indexOf("reconnected:1");
        assertEquals(7, l.callbacks().subList(0, reconnected).stream().filter("partial"::equals).count());
        StreamPartial bytesOnly = l.partials.get(7);
        assertFalse(bytesOnly.hasProgress(), "frames without progress are delivered as before");
        assertEquals(640, bytesOnly.getBytes());
        assertEquals(10, l.partials.get(8).getReadUnits(), "equal progress is delivered");
        assertEquals(180640, l.partials.get(l.partials.size() - 1).getBytes());
        // six replayed frames below 10 units and the frame whose matchedUnits fell from 25 to 24
        List<String> dropped = logs.matching(DROPPED);
        assertEquals(7, dropped.size(), logs.all());
        assertTrue(dropped.get(0).startsWith("DEBUG stream "), dropped.get(0));
        assertTrue(dropped.get(6).contains("readUnits 25, matchedUnits 24 behind the delivered 25, 25"), dropped.get(6));
    }

    @Test
    void dropPolicyStartsTheProgressAgainAtEverySuccessfulReconnect() throws Exception {
        fake.scripts = n -> {
            FakeTransport.Script sc = new FakeTransport.Script();
            sc.answerEnd = n > 2;
            return sc;
        };
        RecordingListener l = new RecordingListener();
        StreamSession s = client(b -> b.audioBufferPolicy(AudioBufferPolicy.DROP))
                .streamEvaluate(sentence().realtimeFeedback(true), l);
        l.session = s;
        awaitEvent(l, "started");
        byte[] part = Arrays.copyOf(PCM, 640 * 10);
        FakeTransport.Conn first = fake.connections.get(0);
        sendAll(s, part);
        first.serverSend(progress(16000, 6, 6, 25));
        first.serverSend(progress(32000, 10, 10, 25));
        awaitPartials(l, 2);
        first.fail(new IOException("connection reset"));
        awaitCount(l, "reconnected:1", 1);
        // the new server session scores only the audio sent from now on; its progress starts again
        FakeTransport.Conn second = fake.connections.get(1);
        sendAll(s, part);
        second.serverSend(progress(16000, 2, 2, 25));
        second.serverSend(progress(32000, 4, 4, 25));
        second.serverSend(progress(48000, 3, 3, 25));   // behind the 4 of this server session: dropped
        awaitPartials(l, 4);
        second.fail(new IOException("connection reset again"));
        awaitCount(l, "reconnected:1", 2);
        FakeTransport.Conn third = fake.connections.get(2);
        sendAll(s, part);
        third.serverSend(progress(16000, 1, 1, 25));
        third.serverSend(progress(32000, 5, 4, 25));
        s.end();
        await(l);
        assertNotNull(l.result, l.events.toString());
        assertEquals(List.of(6, 10, 2, 4, 1, 5), readUnits(l.partials));
        assertEquals(1, logs.matching(DROPPED).size(), logs.all());
        assertEquals(2, l.reconnected.size());
        assertEquals(part.length, l.reconnected.get(0)[1], "DROP reports the audio the result does not cover");
    }

    @Test
    void eitherCountBehindDropsTheFrame() throws Exception {
        fake.scripts = n -> new FakeTransport.Script().silentAfterStart();
        RecordingListener l = new RecordingListener();
        StreamSession s = client().streamEvaluate(sentence().realtimeFeedback(true), l);
        l.session = s;
        awaitEvent(l, "started");
        FakeTransport.Conn conn = fake.connections.get(0);
        conn.serverSend(progress(16000, 4, 3, 6));
        conn.serverSend(progress(32000, 3, 3, 6));   // read behind: dropped
        conn.serverSend(progress(48000, 5, 2, 6));   // matched behind: dropped
        conn.serverSend(progress(64000, 4, 3, 6));   // equal: delivered
        conn.serverSend(progress(80000, 6, 5, 6));
        sendAll(s, PCM);
        s.end();
        awaitEvent(l, "state:ENDING");
        conn.serverSend(progress(96000, 6, 6, 6));   // after end, before the final frame: delivered
        conn.serverSend(FakeTransport.RESULT);
        await(l);
        assertEquals(List.of(4, 4, 6, 6), readUnits(l.partials));
        assertEquals(2, logs.matching(DROPPED).size(), logs.all());
        List<String> ev = l.events;
        assertTrue(ev.indexOf("state:ENDING") < ev.lastIndexOf("partial") && ev.lastIndexOf("partial") < ev.indexOf("result"), ev.toString());
        assertEquals(new ReadingWord("w5", true, true), l.partials.get(3).getProgress().get(5));
    }

    @Test
    void eachSessionStartsWithoutProgressMemory() throws Exception {
        fake.scripts = n -> new FakeTransport.Script().silentAfterStart();
        YuguClient c = client();
        for (int round = 0; round < 2; round++) {
            RecordingListener l = new RecordingListener();
            StreamSession s = c.streamEvaluate(sentence().realtimeFeedback(true), l);
            l.session = s;
            awaitEvent(l, "started");
            FakeTransport.Conn conn = fake.last();
            conn.serverSend(progress(16000, 2, 2, 6));
            conn.serverSend(progress(32000, 6, 6, 6));
            sendAll(s, PCM);
            s.end();
            awaitEvent(l, "state:ENDING");
            conn.serverSend(FakeTransport.RESULT);
            await(l);
            assertEquals(List.of(2, 6), readUnits(l.partials), "session " + round + " delivers its first low progress");
        }
        assertTrue(logs.matching(DROPPED).isEmpty(), logs.all());
    }

    @Test
    void framesWithoutProgressAreDeliveredWithBytes() throws Exception {
        fake.scripts = n -> new FakeTransport.Script().silentAfterStart();
        RecordingListener l = new RecordingListener();
        StreamSession s = client().streamEvaluateCompat(new CompatConfig("sent.eval.cn", REF_ZH).realtimeFeedback(true), l);
        l.session = s;
        awaitEvent(l, "started");
        FakeTransport.Conn conn = fake.connections.get(0);
        conn.serverSend("{\"eof\":0,\"result\":{\"bytes\":16000}}");
        conn.serverSend(progress(32000, 3, 3, 25));
        conn.serverSend("{\"eof\":0,\"result\":{\"bytes\":48000}}");
        conn.serverSend("{\"eof\":0,\"result\":{}}");
        sendAll(s, PCM);
        s.end();
        awaitEvent(l, "state:ENDING");
        conn.serverSend("{\"eof\":0,\"result\":{\"bytes\":61474}}");
        conn.serverSend(FakeTransport.RESULT);
        await(l);
        assertEquals(5, l.partials.size(), l.partials.toString());
        long[] bytes = {16000, 32000, 48000, -1, 61474};
        for (int i = 0; i < bytes.length; i++) {
            StreamPartial p = l.partials.get(i);
            assertEquals(bytes[i], p.getBytes());
            assertEquals(i == 1, p.hasProgress(), p.toString());
            if (i != 1) {
                assertEquals(-1, p.getReadUnits());
                assertEquals(-1, p.getMatchedUnits());
                assertEquals(-1, p.getTotalUnits());
                assertTrue(p.getProgress().isEmpty());
            }
        }
        assertNull(l.error);
    }
}
