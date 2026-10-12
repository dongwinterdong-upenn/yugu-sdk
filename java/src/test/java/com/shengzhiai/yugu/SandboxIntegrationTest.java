package com.shengzhiai.yugu;

import com.shengzhiai.yugu.model.EvalResult;
import com.shengzhiai.yugu.model.EvaluateConfig;
import com.shengzhiai.yugu.model.ReadingWord;
import com.shengzhiai.yugu.model.StreamPartial;
import com.shengzhiai.yugu.testing.Fixtures;
import com.shengzhiai.yugu.testing.RecordingListener;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End to end against the sandbox tenant (DESIGN 10). Runs only when {@code YUGU_SANDBOX_APPKEY} and
 * {@code YUGU_SANDBOX_SECRET} are set, for example in the nightly CI; {@code YUGU_SANDBOX_BASE}, as in
 * SANDBOX.md and the other platforms, overrides the default base ({@code YUGU_SANDBOX_BASE_URL} is still
 * read when it is unset). Each run uses 4 of the 200 daily sandbox calls.
 */
@Tag("sandbox")
@EnabledIfEnvironmentVariable(named = "YUGU_SANDBOX_APPKEY", matches = ".+")
@EnabledIfEnvironmentVariable(named = "YUGU_SANDBOX_SECRET", matches = ".+")
class SandboxIntegrationTest {

    static YuguClient client() {
        YuguClient.Builder b = YuguClient.builder()
                .apiKey(System.getenv("YUGU_SANDBOX_APPKEY"), System.getenv("YUGU_SANDBOX_SECRET"));
        String base = System.getenv("YUGU_SANDBOX_BASE");
        if (base == null || base.isEmpty()) {
            base = System.getenv("YUGU_SANDBOX_BASE_URL");
        }
        if (base != null && !base.isEmpty()) {
            b.baseUrl(base);
        }
        return b.build();
    }

    @Test
    void evaluateTwiceWithTheSameKeyIsBilledOnce() {
        String key = "sandbox-java-" + System.currentTimeMillis();
        try (YuguClient c = client()) {
            EvalResult a = c.evaluate(Fixtures.wav("zh_short.wav"), new EvaluateConfig("sentence", "今天天气很好", "zh-CN"),
                    RequestOptions.withIdempotencyKey(key));
            EvalResult b = c.evaluate(Fixtures.wav("zh_short.wav"), new EvaluateConfig("sentence", "今天天气很好", "zh-CN"),
                    RequestOptions.withIdempotencyKey(key));
            assertNotNull(a.getOverall());
            assertTrue(b.isReplayed());
            assertEquals(a.getRecordId(), b.getRecordId());
        }
    }

    @Test
    void streamEndToEnd() throws Exception {
        try (YuguClient c = client()) {
            RecordingListener l = new RecordingListener();
            StreamSession s = c.streamEvaluate(new EvaluateConfig("sentence", "今天天气很好", "zh-CN"), l);
            for (byte[] f : Fixtures.frames(Fixtures.pcm("zh_short.wav"), 640)) {
                s.sendAudio(f);
            }
            s.end();
            assertTrue(l.awaitClosed(60_000));
            l.assertGuarantees();
            assertNotNull(l.result, String.valueOf(l.error));
        }
    }

    /** The customer sentence streamed at real-time pace with reading progress (2.1.0, CONTRACT 5.5). */
    @Test
    void streamReadingProgressAtRealTimePace() throws Exception {
        String ref = "我是中国人，我是中国人。我们都是中国人。中华民族是一家人。";
        RecordingListener l = new RecordingListener();
        try (YuguClient c = client()) {
            StreamSession s = c.streamEvaluate(new EvaluateConfig("sentence", ref, "zh-CN").realtimeFeedback(true), l);
            l.session = s;
            List<byte[]> frames = Fixtures.frames(Fixtures.pcm("zh_progress.wav"), 640);
            long t0 = System.nanoTime();
            for (int i = 0; i < frames.size(); i++) {
                long wait = t0 + TimeUnit.MILLISECONDS.toNanos(20L * i) - System.nanoTime();
                if (wait > 0) {
                    TimeUnit.NANOSECONDS.sleep(wait);
                }
                s.sendAudio(frames.get(i));
            }
            s.end();
            assertTrue(l.awaitClosed(60_000));
            l.assertGuarantees();
            assertNotNull(l.result, String.valueOf(l.error));
        }
        List<StreamPartial> progress = l.partials.stream().filter(StreamPartial::hasProgress).collect(Collectors.toList());
        assertFalse(progress.isEmpty(), "no reading progress in " + l.partials);
        int read = -1;
        int matched = -1;
        for (StreamPartial p : progress) {
            assertEquals(25, p.getTotalUnits(), p.toString());
            assertEquals(25, p.getProgress().size(), p.toString());
            assertFalse(p.getRaw().path("result").has("words"), "native progress frames carry no Shengtong words");
            assertTrue(p.getReadUnits() >= read && p.getMatchedUnits() >= matched, "progress went back at " + p);
            read = p.getReadUnits();
            matched = p.getMatchedUnits();
        }
        StreamPartial last = progress.get(progress.size() - 1);
        assertEquals("我是中国人我是中国人我们都是中国人中华民族是一家人",
                last.getProgress().stream().map(ReadingWord::getWord).collect(Collectors.joining()));
        assertTrue(last.getReadUnits() >= 20, "the whole sentence was read: " + last);
        System.out.println("sandbox progress: " + l.partials.size() + " partials, " + progress.size() + " with progress, last "
                + last + ", partial after end " + (l.events.lastIndexOf("partial") > l.events.indexOf("state:ENDING"))
                + ", overall " + l.result.getOverall() + ", recordId " + l.result.getRecordId());
    }
}
