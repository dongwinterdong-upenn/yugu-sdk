package com.shengzhiai.yugu;

import com.shengzhiai.yugu.errors.InvalidParameterException;
import com.shengzhiai.yugu.errors.YuguException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientOptionsTest {

    @Test
    void defaultsMatchDesignTable() {
        ClientOptions o = ClientOptions.builder().apiKey("mock-app-key", "mock-secret-key").build();
        assertEquals("https://open.shengzhiai.com", o.getBaseUrl());
        assertEquals("wss://open.shengzhiai.com", o.getWsBaseUrl());
        assertEquals(10000, o.getConnectTimeoutMs());
        assertEquals(120000, o.getReadTimeoutMs());
        assertEquals(300000, o.getTotalTimeoutMs());
        assertSame(RetryPolicy.defaults(), o.getRetry());
        assertTrue(o.isAutoIdempotencyKey());
        assertEquals(LogLevel.WARN, o.getLogLevel());
        assertNull(o.getLogger());
        assertNull(o.getEventListener());
        assertEquals(AudioPrecheckMode.WARN, o.getAudioPrecheck());
        assertFalse(o.isStrictAudio());
        assertEquals("yugu-java-sdk/2.0.0", o.getUserAgent());
        assertSame(ReconnectPolicy.defaults(), o.getReconnect());
        assertEquals(AudioBufferPolicy.REPLAY, o.getAudioBufferPolicy());
        assertEquals(15000, o.getPingIntervalMs());
        assertEquals(30000, o.getPongTimeoutMs());
        assertEquals(300000, o.getResultTimeoutMs());
        assertFalse(o.isTokenAuth());
        assertEquals("mock-app-key", o.getAppKey());
    }

    @Test
    void versionMatchesTheMavenProject() {
        String pomVersion = System.getProperty("yugu.project.version");
        if (pomVersion != null) {
            assertEquals(pomVersion, YuguClient.VERSION);
        }
        assertEquals("yugu-java-sdk/" + YuguClient.VERSION, YuguClient.USER_AGENT);
    }

    @Test
    void wsBaseIsDerivedFromBaseUrlWhenNotSet() {
        assertEquals("ws://127.0.0.1:18900", ClientOptions.builder().token("t").baseUrl("http://127.0.0.1:18900/").build().getWsBaseUrl());
        assertEquals("wss://sandbox.example.com/x", ClientOptions.builder().token("t").baseUrl("https://sandbox.example.com/x").build().getWsBaseUrl());
        assertEquals("ws://other:1", ClientOptions.builder().token("t").baseUrl("http://a").wsBaseUrl("ws://other:1///").build().getWsBaseUrl());
        assertEquals("http://a", ClientOptions.builder().token("t").baseUrl("http://a/").build().getBaseUrl());
    }

    @Test
    void validationHappensAtBuild() {
        YuguException e = assertThrows(InvalidParameterException.class, () -> ClientOptions.builder().build());
        assertEquals(90010, e.getCode());
        assertThrows(InvalidParameterException.class, () -> ClientOptions.builder().apiKey("a", "").build());
        assertThrows(InvalidParameterException.class, () -> ClientOptions.builder().token("t").baseUrl("ftp://x").build());
        assertThrows(InvalidParameterException.class, () -> ClientOptions.builder().token("t").baseUrl("not a url").build());
        assertThrows(InvalidParameterException.class, () -> ClientOptions.builder().token("t").wsBaseUrl("http://x").build());
        assertThrows(InvalidParameterException.class, () -> ClientOptions.builder().token("t").readTimeoutMs(0).build());
        assertThrows(InvalidParameterException.class, () -> ClientOptions.builder().token("t").pongTimeoutMs(-1).build());
    }

    @Test
    void keyAuthWinsOverTokenAndSecretsNeverPrint() {
        ClientOptions o = ClientOptions.builder().token("jwt-secret").apiKey("mock-app-key", "mock-secret-key").build();
        assertFalse(o.isTokenAuth());
        assertNull(o.getToken());
        String s = o.toString();
        assertFalse(s.contains("mock-secret-key"), s);
        assertFalse(s.contains("jwt-secret"), s);
        assertTrue(s.contains("appKey(mock***)"), s);
        ClientOptions t = ClientOptions.builder().token("jwt-secret").build();
        assertTrue(t.isTokenAuth());
        assertTrue(t.toString().contains("token(***)"));
        assertFalse(t.toString().contains("jwt-secret"));
    }

    @Test
    void everySetterIsApplied() {
        EventListener events = new EventListener() {
        };
        YuguLogger logger = (l, t, m, e) -> { };
        ClientOptions o = ClientOptions.builder().apiKey("k", "s").baseUrl("http://x").wsBaseUrl("ws://y")
                .connectTimeoutMs(1).readTimeoutMs(2).totalTimeoutMs(3).retry(RetryPolicy.none()).autoIdempotencyKey(false)
                .logLevel(LogLevel.DEBUG).logger(logger).eventListener(events).audioPrecheck(AudioPrecheckMode.REJECT)
                .strictAudio(true).userAgent("ua/1").reconnect(ReconnectPolicy.disabled()).audioBufferPolicy(AudioBufferPolicy.DROP)
                .pingIntervalMs(4).pongTimeoutMs(5).resultTimeoutMs(6).build();
        ClientOptions c = o.toBuilder().build();
        for (ClientOptions x : new ClientOptions[]{o, c}) {
            assertEquals("http://x", x.getBaseUrl());
            assertEquals("ws://y", x.getWsBaseUrl());
            assertEquals(1, x.getConnectTimeoutMs());
            assertEquals(2, x.getReadTimeoutMs());
            assertEquals(3, x.getTotalTimeoutMs());
            assertSame(RetryPolicy.none(), x.getRetry());
            assertFalse(x.isAutoIdempotencyKey());
            assertEquals(LogLevel.DEBUG, x.getLogLevel());
            assertSame(logger, x.getLogger());
            assertSame(events, x.getEventListener());
            assertEquals(AudioPrecheckMode.REJECT, x.getAudioPrecheck());
            assertTrue(x.isStrictAudio());
            assertEquals("ua/1", x.getUserAgent());
            assertFalse(x.getReconnect().isEnabled());
            assertEquals(AudioBufferPolicy.DROP, x.getAudioBufferPolicy());
            assertEquals(4, x.getPingIntervalMs());
            assertEquals(5, x.getPongTimeoutMs());
            assertEquals(6, x.getResultTimeoutMs());
        }
        ClientOptions viaClientBuilder = YuguClient.builder().token("t").baseUrl("http://z").buildOptions();
        assertEquals("http://z", viaClientBuilder.getBaseUrl());
    }

    @Test
    void requestAndStreamOptions() {
        CancellationToken token = new CancellationToken();
        RequestOptions r = RequestOptions.builder().idempotencyKey("k").totalTimeoutMs(1).readTimeoutMs(2)
                .retry(RetryPolicy.none()).cancellation(token).build();
        assertEquals("k", r.getIdempotencyKey());
        assertEquals(1L, r.getTotalTimeoutMs());
        assertEquals(2L, r.getReadTimeoutMs());
        assertSame(RetryPolicy.none(), r.getRetry());
        assertSame(token, r.getCancellation());
        assertEquals("x", RequestOptions.withIdempotencyKey("x").getIdempotencyKey());
        assertNull(RequestOptions.none().getIdempotencyKey());
        StreamOptions s = StreamOptions.builder().idempotencyKey("s").reconnect(ReconnectPolicy.disabled())
                .bufferPolicy(AudioBufferPolicy.FAIL).resultTimeoutMs(9).replayBufferBytes(10).build();
        assertEquals("s", s.getIdempotencyKey());
        assertFalse(s.getReconnect().isEnabled());
        assertEquals(AudioBufferPolicy.FAIL, s.getBufferPolicy());
        assertEquals(9L, s.getResultTimeoutMs());
        assertEquals(10L, s.getReplayBufferBytes());
        assertNull(StreamOptions.none().getBufferPolicy());
    }

    @Test
    void cancellationToken() {
        CancellationToken t = new CancellationToken();
        int[] calls = new int[2];
        Runnable unregister = t.onCancel(() -> calls[0]++);
        t.onCancel(() -> {
            throw new IllegalStateException("ignored");
        });
        t.onCancel(() -> calls[1]++);
        unregister.run();
        assertFalse(t.isCancelled());
        t.cancel();
        t.cancel();
        assertTrue(t.isCancelled());
        assertEquals(0, calls[0]);
        assertEquals(1, calls[1]);
        t.onCancel(() -> calls[0]++).run();
        assertEquals(1, calls[0], "runs at once when already cancelled");
    }

    @Test
    void sessionStateTerminality() {
        assertFalse(SessionState.STARTED.isTerminal());
        assertFalse(SessionState.RECONNECTING.isTerminal());
        assertTrue(SessionState.COMPLETED.isTerminal());
        assertTrue(SessionState.FAILED.isTerminal());
        assertTrue(SessionState.CANCELLED.isTerminal());
        assertTrue(SessionState.CLOSED.isTerminal());
    }
}
