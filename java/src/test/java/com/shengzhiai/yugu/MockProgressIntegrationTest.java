package com.shengzhiai.yugu;

import com.fasterxml.jackson.databind.JsonNode;
import com.shengzhiai.yugu.model.CompatConfig;
import com.shengzhiai.yugu.model.EvaluateConfig;
import com.shengzhiai.yugu.model.ReadingWord;
import com.shengzhiai.yugu.model.StreamPartial;
import com.shengzhiai.yugu.testing.CapturingLogger;
import com.shengzhiai.yugu.testing.Fixtures;
import com.shengzhiai.yugu.testing.MockServer;
import com.shengzhiai.yugu.testing.MockServerExtension;
import com.shengzhiai.yugu.testing.RecordingListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reading progress of 2.1.0 against the Node mock: progress frames on the native and the compat channel
 * when realtime feedback is requested (one unit read and matched per 8000 bytes, one frame per 16000 bytes,
 * one more frame after end before the final; compat frames also carry the Shengtong-shaped increment),
 * the faults {@code progress-mismatch:<i>} and {@code progress-off}, and reconnects with the REPLAY and
 * the DROP buffer policy. The faults are queued with {@code POST /__mock/faults}; the mock applies a
 * queued fault to the next WebSocket connection of the path exactly like the {@code mockFault} query.
 */
@ExtendWith(MockServerExtension.class)
class MockProgressIntegrationTest {
    static final String NATIVE = "/api/v1/ws/evaluate";
    static final String COMPAT_ZH = "/sent.eval.cn";
    static final String REF_ZH = "我是中国人，我是中国人。我们都是中国人。中华民族是一家人。";
    static final List<String> UNITS_ZH = Arrays.asList("我是中国人我是中国人我们都是中国人中华民族是一家人".split(""));
    static final String REF_EN = "Nice to see you again. I am a student, and I like reading books every day.";
    static final List<String> UNITS_EN = List.of("Nice", "to", "see", "you", "again", "I", "am", "a", "student", "and",
            "I", "like", "reading", "books", "every", "day");
    static final byte[] PCM_ZH = Fixtures.pcm("zh_progress.wav");
    static final byte[] PCM_EN = Fixtures.pcm("en_progress.wav");
    static final long TERMINAL_BOUND_MS = 15_000;
    static final String DROPPED = "progress partial dropped";

    MockServer mock;
    CapturingLogger logs;

    @BeforeEach
    void reset() {
        mock = MockServer.get();
        mock.reset();
        logs = new CapturingLogger();
    }

    YuguClient client(AudioBufferPolicy policy) {
        return YuguClient.builder().apiKey(MockServer.APP_KEY, MockServer.SECRET).baseUrl(mock.baseUrl())
                .logLevel(LogLevel.DEBUG).logger(logs).audioBufferPolicy(policy).build();
    }

    static EvaluateConfig nativeZh() {
        return new EvaluateConfig(EvaluateConfig.CORE_SENTENCE, REF_ZH, "zh-CN");
    }

    static void send(StreamSession s, List<byte[]> frames) {
        for (byte[] f : frames) {
            s.sendAudio(f);
        }
    }

    static void awaitTerminal(RecordingListener l) throws InterruptedException {
        assertTrue(l.awaitClosed(TERMINAL_BOUND_MS), "silent hang: no onClosed within " + TERMINAL_BOUND_MS + " ms, events " + l.events);
        l.assertGuarantees();
    }

    static void awaitPartials(RecordingListener l, int n) throws InterruptedException {
        long end = System.currentTimeMillis() + TERMINAL_BOUND_MS;
        while (l.partials.size() < n) {
            assertTrue(System.currentTimeMillis() < end, "expected " + n + " partials: " + l.events);
            Thread.sleep(5);
        }
    }

    static void awaitEvent(RecordingListener l, String event) throws InterruptedException {
        long end = System.currentTimeMillis() + TERMINAL_BOUND_MS;
        while (!l.events.contains(event)) {
            assertTrue(System.currentTimeMillis() < end, "no " + event + " in " + l.events);
            Thread.sleep(5);
        }
    }

    /** Opens a session, sends the PCM in 20 ms frames, ends it and waits for onClosed. */
    RecordingListener run(Function<YuguClient, Function<RecordingListener, StreamSession>> open, byte[] pcm) throws Exception {
        RecordingListener l = new RecordingListener();
        try (YuguClient c = client(AudioBufferPolicy.REPLAY)) {
            StreamSession s = open.apply(c).apply(l);
            l.session = s;
            send(s, Fixtures.frames(pcm, 640));
            s.end();
            awaitTerminal(l);
            assertNotNull(l.result, l.events.toString());
            JsonNode b = mock.billing();
            assertEquals(1, b.get("billed").asInt(), b.toString());
            assertEquals(1, b.get("byKey").get(s.getIdempotencyKey()).asInt(), b.toString());
        }
        return l;
    }

    static List<String> units(StreamPartial p) {
        return p.getProgress().stream().map(ReadingWord::getWord).collect(Collectors.toList());
    }

    static List<Integer> readUnits(List<StreamPartial> ps) {
        return ps.stream().map(StreamPartial::getReadUnits).collect(Collectors.toList());
    }

    /**
     * The partials the mock sends for {@code pcmLength} bytes sent in 640 byte frames: one per 16000 bytes
     * with {@code floor(bytes / 8000)} units read, then one after end with one unit more. Unit
     * {@code mismatch} (-1 for none) is read but never matched.
     */
    static void assertMockRound(String what, List<StreamPartial> ps, int pcmLength, List<String> expectedUnits, int mismatch) {
        int total = expectedUnits.size();
        int frames = pcmLength / 16000;
        assertEquals(frames + 1, ps.size(), what + " " + ps);
        for (int i = 0; i < ps.size(); i++) {
            StreamPartial p = ps.get(i);
            boolean afterEnd = i == frames;
            long bytes = afterEnd ? pcmLength : 16000L * (i + 1);
            int read = (int) Math.min(total, bytes / 8000 + (afterEnd ? 1 : 0));
            String at = what + " partial " + i + " " + p;
            assertTrue(p.hasProgress(), at);
            assertEquals(bytes, p.getBytes(), at);
            assertEquals(total, p.getTotalUnits(), at);
            assertEquals(expectedUnits, units(p), at);
            assertEquals(read, p.getReadUnits(), at);
            assertEquals(mismatch >= 0 && mismatch < read ? read - 1 : read, p.getMatchedUnits(), at);
            for (int j = 0; j < total; j++) {
                ReadingWord w = p.getProgress().get(j);
                assertEquals(j < read, w.isRead(), at + " unit " + j);
                assertEquals(j < read && j != mismatch, w.isMatched(), at + " unit " + j);
            }
        }
    }

    /**
     * Compat frames pass the Shengtong-shaped increment through untouched: {@code recordId} "", and
     * {@code result.words} covering units {@code [readUnits - words.length, readUnits)}; within one server
     * session the increments add up to the units read.
     */
    static void assertShengtongIncrements(String what, List<StreamPartial> ps) {
        StringBuilder concatenated = new StringBuilder();
        for (StreamPartial p : ps) {
            JsonNode raw = p.getRaw();
            assertEquals("", raw.path("recordId").asText("absent"), what);
            JsonNode words = raw.path("result").path("words");
            assertTrue(words.isArray(), what + " " + raw);
            int from = p.getReadUnits() - words.size();
            for (int j = 0; j < words.size(); j++) {
                ReadingWord unit = p.getProgress().get(from + j);
                assertEquals(unit.getWord(), words.get(j).get("word").asText(), what + " " + p);
                assertEquals(unit.isMatched() ? 0 : 1, words.get(j).path("scores").path("skipped").asInt(), what + " " + p);
                concatenated.append(unit.getWord()).append(' ');
            }
        }
        StreamPartial last = ps.get(ps.size() - 1);
        String read = last.getProgress().subList(0, last.getReadUnits()).stream().map(u -> u.getWord() + " ").collect(Collectors.joining());
        assertEquals(read, concatenated.toString(), what);
    }

    /** The last partial (the recognition of the last audio) arrives after end() and before the result. */
    static void assertLastPartialWhileEnding(RecordingListener l) {
        List<String> ev = l.events;
        int ending = ev.indexOf("state:ENDING");
        int last = ev.lastIndexOf("partial");
        assertTrue(ending >= 0 && ending < last && last < ev.indexOf("result"), ev.toString());
    }

    // ------------------------------------------------------------------ progress on both channels

    @Test
    void compatChineseProgress() throws Exception {
        RecordingListener l = run(c -> lst -> c.streamEvaluateCompat(new CompatConfig("sent.eval.cn", REF_ZH).realtimeFeedback(true), lst),
                PCM_ZH);
        assertMockRound("compat zh", l.partials, PCM_ZH.length, UNITS_ZH, -1);
        assertShengtongIncrements("compat zh", l.partials);
        assertLastPartialWhileEnding(l);
        assertEquals("sent.eval.cn", l.result.getCoreType());
        assertTrue(logs.matching(DROPPED).isEmpty(), logs.all());
    }

    @Test
    void compatEnglishProgressCountsWords() throws Exception {
        RecordingListener l = run(c -> lst -> c.streamEvaluateCompat(new CompatConfig("sent.eval", REF_EN).realtimeFeedback(true), lst),
                PCM_EN);
        assertMockRound("compat en", l.partials, PCM_EN.length, UNITS_EN, -1);
        assertShengtongIncrements("compat en", l.partials);
        assertEquals(16, l.partials.get(l.partials.size() - 1).getReadUnits());
    }

    @Test
    void nativeProgress() throws Exception {
        RecordingListener l = run(c -> lst -> c.streamEvaluate(nativeZh().realtimeFeedback(true), lst), PCM_ZH);
        assertMockRound("native", l.partials, PCM_ZH.length, UNITS_ZH, -1);
        for (StreamPartial p : l.partials) {
            assertFalse(p.getRaw().path("result").has("words"), "native frames carry no Shengtong words: " + p.getRaw());
        }
        assertLastPartialWhileEnding(l);
        assertEquals("ws-native", mock.billing().get("records").get(0).get("op").asText());
    }

    @Test
    void withoutRealtimeFeedbackNoPartialsArrive() throws Exception {
        RecordingListener n = run(c -> lst -> c.streamEvaluate(nativeZh(), lst), PCM_ZH);
        assertTrue(n.partials.isEmpty(), n.partials.toString());
        mock.reset();
        RecordingListener cp = run(c -> lst -> c.streamEvaluateCompat(new CompatConfig("sent.eval.cn", REF_ZH), lst), PCM_ZH);
        assertTrue(cp.partials.isEmpty(), cp.partials.toString());
    }

    // ------------------------------------------------------------------ faults

    @Test
    void progressMismatchLeavesTheUnitReadButNotMatched() throws Exception {
        mock.faults(COMPAT_ZH, "progress-mismatch:3");
        RecordingListener l = run(c -> lst -> c.streamEvaluateCompat(new CompatConfig("sent.eval.cn", REF_ZH).realtimeFeedback(true), lst),
                PCM_ZH);
        assertMockRound("compat mismatch 3", l.partials, PCM_ZH.length, UNITS_ZH, 3);
        assertShengtongIncrements("compat mismatch 3", l.partials);
        StreamPartial last = l.partials.get(l.partials.size() - 1);
        assertEquals(new ReadingWord("国", true, false), last.getProgress().get(3));
        assertEquals(last.getReadUnits() - 1, last.getMatchedUnits());

        mock.reset();
        mock.faults(NATIVE, "progress-mismatch:0");
        RecordingListener n = run(c -> lst -> c.streamEvaluate(nativeZh().realtimeFeedback(true), lst), PCM_ZH);
        assertMockRound("native mismatch 0", n.partials, PCM_ZH.length, UNITS_ZH, 0);
        assertTrue(logs.matching(DROPPED).isEmpty(), "a mismatch never makes the progress go back: " + logs.all());
    }

    @Test
    void progressOffDeliversFramesWithBytesOnly() throws Exception {
        for (String path : new String[]{NATIVE, COMPAT_ZH}) {
            mock.reset();
            mock.faults(path, "progress-off");
            RecordingListener l = run(c -> lst -> NATIVE.equals(path)
                    ? c.streamEvaluate(nativeZh().realtimeFeedback(true), lst)
                    : c.streamEvaluateCompat(new CompatConfig("sent.eval.cn", REF_ZH).realtimeFeedback(true), lst), PCM_ZH);
            int frames = PCM_ZH.length / 16000;
            assertEquals(frames + 1, l.partials.size(), path + " " + l.partials);
            for (int i = 0; i < l.partials.size(); i++) {
                StreamPartial p = l.partials.get(i);
                assertFalse(p.hasProgress(), path + " " + p);
                assertEquals(i == frames ? PCM_ZH.length : 16000L * (i + 1), p.getBytes(), path + " " + p);
                assertEquals(-1, p.getReadUnits());
                assertEquals(-1, p.getMatchedUnits());
                assertEquals(-1, p.getTotalUnits());
                assertTrue(p.getProgress().isEmpty());
                assertEquals(1, p.getRaw().path("result").size(), "the 2.0.0 shape: only bytes, " + p.getRaw());
            }
            assertLastPartialWhileEnding(l);
        }
    }

    // ------------------------------------------------------------------ reconnect

    @Test
    void replayReconnectNeverDeliversARegressingPartial() throws Exception {
        // the mock reads the start frame and 75 audio frames (48000 bytes, progress 2, 4, 6 units) and
        // kills the socket on the 76th audio frame; the new server session gets the whole audio again
        // and sends 2 and 4 again before it catches up
        mock.faults(NATIVE, "ws-kill-after:77");
        List<byte[]> frames = Fixtures.frames(PCM_ZH, 640);
        RecordingListener l = new RecordingListener();
        StreamSession s;
        try (YuguClient c = client(AudioBufferPolicy.REPLAY)) {
            s = c.streamEvaluate(nativeZh().realtimeFeedback(true), l);
            l.session = s;
            send(s, frames.subList(0, 75));
            awaitPartials(l, 3);
            send(s, frames.subList(75, frames.size()));
            s.end();
            awaitTerminal(l);
        }
        assertNotNull(l.result, l.events.toString());
        assertEquals(1, l.count("reconnected:1"), l.events.toString());
        assertEquals(2, l.result.getAttempts());
        for (StreamPartial p : l.partials) {
            assertTrue(p.hasProgress(), p.toString());
            assertEquals(p.getReadUnits(), p.getMatchedUnits(), p.toString());
        }
        // first server session 2, 4, 6; second server session 2 and 4 dropped, then 6 up to 18, 19 after end
        assertEquals(List.of(2, 4, 6, 6, 8, 10, 12, 14, 16, 18, 19), readUnits(l.partials), l.events.toString());
        List<String> cb = l.callbacks();
        assertEquals(3, cb.subList(0, cb.indexOf("reconnected:1")).stream().filter("partial"::equals).count(), cb.toString());
        assertEquals(2, logs.matching(DROPPED).size(), String.join("\n", logs.matching("progress")));

        List<JsonNode> ws = new ArrayList<>();
        for (JsonNode e : mock.log()) {
            if ("WS".equals(e.get("method").asText())) {
                ws.add(e);
            }
        }
        assertEquals(2, ws.size());
        assertEquals("ws-kill-after:77", ws.get(0).get("fault").asText());
        JsonNode b = mock.billing();
        assertEquals(1, b.get("billed").asInt(), b.toString());
        assertEquals(1, b.get("byKey").get(s.getIdempotencyKey()).asInt(), b.toString());
        assertEquals(PCM_ZH.length, b.get("records").get(0).get("bytes").asInt(), "the whole audio was replayed");
    }

    @Test
    void dropReconnectStartsTheProgressAgain() throws Exception {
        // same kill as above, but DROP keeps no audio: the new server session scores only the audio sent
        // after the reconnect, its progress starts from zero and every frame of it is delivered
        mock.faults(COMPAT_ZH, "ws-kill-after:77");
        List<byte[]> frames = Fixtures.frames(PCM_ZH, 640);
        RecordingListener l = new RecordingListener();
        StreamSession s;
        try (YuguClient c = client(AudioBufferPolicy.DROP)) {
            s = c.streamEvaluateCompat(new CompatConfig("sent.eval.cn", REF_ZH).realtimeFeedback(true), l);
            l.session = s;
            send(s, frames.subList(0, 75));
            awaitPartials(l, 3);
            send(s, frames.subList(75, 76));
            awaitEvent(l, "reconnected:1");
            send(s, frames.subList(76, frames.size()));
            s.end();
            awaitTerminal(l);
        }
        assertNotNull(l.result, l.events.toString());
        int later = PCM_ZH.length - 76 * 640;
        assertEquals(76 * 640, l.reconnected.get(0)[1], "droppedBytes");
        // 2, 4, 6 from the first server session; 2 up to 12 and 13 after end from the second
        assertEquals(List.of(2, 4, 6, 2, 4, 6, 8, 10, 12, 13), readUnits(l.partials), l.events.toString());
        assertEquals(later / 16000 + 1, l.partials.size() - 3);
        assertTrue(logs.matching(DROPPED).isEmpty(), logs.all());
        // the Shengtong increments of the second server session start again at the first unit
        assertShengtongIncrements("drop, second server session", l.partials.subList(3, l.partials.size()));
        JsonNode b = mock.billing();
        assertEquals(1, b.get("billed").asInt(), b.toString());
        assertEquals(later, b.get("records").get(0).get("bytes").asInt(), "the new server session scored only the later audio");
    }
}
