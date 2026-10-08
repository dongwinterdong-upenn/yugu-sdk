package com.shengzhiai.yugu;

import com.shengzhiai.yugu.errors.IllegalSessionStateException;
import com.shengzhiai.yugu.model.EvaluateConfig;
import com.shengzhiai.yugu.testing.FakeTransport;
import com.shengzhiai.yugu.testing.Fixtures;
import com.shengzhiai.yugu.testing.RecordingListener;
import com.shengzhiai.yugu.testing.StubServer;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Lifecycle (requirement B-05): close is idempotent, nothing leaks across 100 create, use, close cycles. */
class LifecycleTest {

    static long sdkThreads() {
        Set<Thread> all = Thread.getAllStackTraces().keySet();
        return all.stream().filter(t -> t.getName().startsWith("yugu-") && t.isAlive()).count();
    }

    @Test
    void hundredCreateUseCloseCyclesLeaveNoSdkThreads() throws Exception {
        long before = sdkThreads();
        try (StubServer stub = new StubServer()) {
            stub.always(new StubServer.Resp(200, Fixtures.text("fixtures/platform/native_evaluate_sentence_zh.json")));
            for (int i = 0; i < 100; i++) {
                FakeTransport fake = new FakeTransport();
                YuguClient.Builder b = YuguClient.builder().apiKey("k", "s").baseUrl(stub.baseUrl()).wsBaseUrl("ws://fake");
                TestHooks.transport(b, fake);
                try (YuguClient c = b.build()) {
                    assertNotNull(c.evaluate(Fixtures.wav("zh_short.wav"), new EvaluateConfig("sentence", "今天天气很好", "zh-CN")));
                    RecordingListener l = new RecordingListener();
                    StreamSession s = c.streamEvaluate(new EvaluateConfig("sentence", "今天天气很好", "zh-CN"), l);
                    s.sendAudio(new byte[640 * 50]);
                    s.end();
                    assertTrue(l.awaitClosed(5000));
                    l.assertGuarantees();
                }
            }
        }
        long deadline = System.currentTimeMillis() + 10_000;
        while (sdkThreads() > before && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertEquals(before, sdkThreads(), "SDK threads left after 100 closed clients");
    }

    @Test
    void closeIsIdempotentAndLaterCallsThrow90004() {
        YuguClient c = YuguClient.builder().token("t").baseUrl("http://127.0.0.1:9").build();
        c.close();
        c.close();
        assertTrue(c.isClosed());
        IllegalSessionStateException e = assertThrows(IllegalSessionStateException.class,
                () -> c.evaluate(new byte[]{1}, new EvaluateConfig("sentence", "x", "zh-CN")));
        assertEquals(90004, e.getCode());
        assertThrows(IllegalSessionStateException.class,
                () -> c.streamEvaluate(new EvaluateConfig("sentence", "x", "zh-CN"), new RecordingListener()));
        assertEquals(0, c.getOpenSessionCount());
    }

    @Test
    void createWithOptions() {
        ClientOptions o = ClientOptions.builder().token("t").baseUrl("http://127.0.0.1:9").build();
        try (YuguClient c = YuguClient.create(o)) {
            assertEquals(o, c.getOptions());
        }
    }
}
