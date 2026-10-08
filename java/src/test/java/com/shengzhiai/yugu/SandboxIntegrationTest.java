package com.shengzhiai.yugu;

import com.shengzhiai.yugu.model.EvalResult;
import com.shengzhiai.yugu.model.EvaluateConfig;
import com.shengzhiai.yugu.testing.Fixtures;
import com.shengzhiai.yugu.testing.RecordingListener;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End to end against the sandbox tenant (DESIGN 10). Runs only when {@code YUGU_SANDBOX_APPKEY} and
 * {@code YUGU_SANDBOX_SECRET} are set, for example in the nightly CI; {@code YUGU_SANDBOX_BASE}, as in
 * SANDBOX.md and the other platforms, overrides the default base ({@code YUGU_SANDBOX_BASE_URL} is still
 * read when it is unset). Each run uses 3 of the 200 daily sandbox calls.
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
}
