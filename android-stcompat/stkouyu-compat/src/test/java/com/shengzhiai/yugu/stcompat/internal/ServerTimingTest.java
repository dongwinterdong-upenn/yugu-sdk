package com.shengzhiai.yugu.stcompat.internal;

import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Server timing of the platform (SDK 2.1.1): header and body parsing, removal from the result, the REST summary line. */
public class ServerTimingTest {
    static final String TIMING = "{\"receivedAt\":1791800000123,\"totalMs\":5321,\"engineMs\":4100,\"platformMs\":1221,"
            + "\"engineStages\":{\"text_frontend_ms\":1909.3,\"asr_ms\":595.0,\"nested\":{\"x\":1}},\"audioBytes\":528000,"
            + "\"wsFirstAudioAt\":1791799990000,\"futureKey\":\"ignored\"}";

    private final List<String> lines = Collections.synchronizedList(new ArrayList<String>());

    @After
    public void tearDown() {
        YLog.setSink(null);
        YLog.resetLevel();
    }

    private void captureAt(int level) {
        YLog.setLevelPinned(level);
        YLog.setSink((l, tag, msg, err) -> lines.add(l + " " + msg));
    }

    @Test
    public void headerValues() {
        ServerTiming t = ServerTiming.fromHeader("total;dur=5321, engine;dur=4100, platform;dur=1221");
        assertEquals(5321, t.totalMs);
        assertEquals(4100, t.engineMs);
        assertEquals(1221, t.platformMs);
        assertEquals("server.total=5321ms server.engine=4100ms server.platform=1221ms", t.describe());
        ServerTiming odd = ServerTiming.fromHeader(" Total ; desc=\"a, b;c\" ; DUR=12.6 ,cache;dur=1,engine;dur=\"3\"");
        assertEquals(13, odd.totalMs);
        assertEquals(3, odd.engineMs);
        assertEquals(-1, odd.platformMs);
        assertEquals("server.total=13ms server.engine=3ms", odd.describe());
        assertNull(ServerTiming.fromHeader(null));
        assertNull(ServerTiming.fromHeader(" "));
        assertNull(ServerTiming.fromHeader("cache;dur=1, db;dur=2"));
        assertEquals(-1, ServerTiming.fromHeader("total").totalMs);
        assertEquals(-1, ServerTiming.fromHeader("total;dur=abc").totalMs);
    }

    @Test
    public void bodyObject() {
        ServerTiming t = ServerTiming.fromObject(TIMING);
        assertEquals(1791800000123L, t.receivedAt);
        assertEquals(5321, t.totalMs);
        assertEquals(4100, t.engineMs);
        assertEquals(1221, t.platformMs);
        assertEquals(528000, t.audioBytes);
        assertEquals(1791799990000L, t.wsFirstAudioAt);
        assertEquals("text_frontend_ms=1909.3 asr_ms=595.0", t.describeStages());
        ServerTiming bare = ServerTiming.fromObject("{\"totalMs\":\"10\",\"engineMs\":4}");
        assertEquals(10, bare.totalMs);
        assertEquals("platform is total minus engine when absent", 6, bare.platformMs);
        assertEquals(-1, bare.wsFirstAudioAt);
        assertEquals("", bare.describeStages());
        assertEquals("", ServerTiming.fromObject("{\"engineStages\":[1]}").describeStages());
        assertEquals("", ServerTiming.fromObject("{\"engineStages\":{\"a\":}}").describeStages());
        assertNull(ServerTiming.fromObject("null"));
        assertNull(ServerTiming.fromObject(null));
        assertEquals(-1, ServerTiming.number("NaN"));
        assertEquals(-1, ServerTiming.number("true"));
    }

    @Test
    public void splitRemovesOnlyTheTopLevelTiming() {
        String result = "{\"overall\":94.6,\"timing\":\"engine's own\",\"words\":[{\"word\":\"今\"}]}";
        String body = "{\"recordId\":\"eval_1\",\"eof\":1,\"result\":" + result + ",\"timing\":" + TIMING + ",\"audioUrl\":\"u\"}";
        ServerTiming.Split s = ServerTiming.split(body);
        assertEquals("{\"recordId\":\"eval_1\",\"eof\":1,\"result\":" + result + ",\"audioUrl\":\"u\"}", s.body);
        assertEquals(5321, s.timing.totalMs);
        String first = "{ \"timing\" : {\"totalMs\":1} ,\n \"result\":{\"overall\":1} }";
        assertEquals("{ \"result\":{\"overall\":1} }", ServerTiming.split(first).body);
        String last = "{\"result\":{},\"timing\":{}}";
        assertEquals("{\"result\":{}}", ServerTiming.split(last).body);
        String plain = "{\"recordId\":\"x\",\"result\":{}}";
        assertSame(plain, ServerTiming.split(plain).body);
        assertNull(ServerTiming.split(plain).timing);
        assertEquals("not JSON: left for the caller", "oops", ServerTiming.split("oops").body);
        assertNull(ServerTiming.split("{\"result\":{},\"timing\":7}").timing);
    }

    @Test
    public void removeMemberKeepsTheRestByteForByte() {
        assertEquals("{}", RawJson.removeMember("{\"timing\":1}", "timing"));
        assertEquals(" {  } ", RawJson.removeMember(" { \"timing\" : [1,2] } ", "timing"));
        assertEquals("{\"a\":1 , \"c\":3}", RawJson.removeMember("{\"a\":1 , \"timing\":2 , \"c\":3}", "timing"));
        assertEquals("{\"a\":1}", RawJson.removeMember("{\"timing\":0,\"a\":1,\"timing\":2}", "timing"));
        assertEquals("{}", RawJson.removeMember("{}", "timing"));
        assertEquals("{\"x\":\"timing\"}", RawJson.removeMember("{\"x\":\"timing\"}", "timing"));
        for (String bad : new String[] {"[1]", "{\"a\":1} x", "{\"a\" 1}", "{\"a\":1;\"b\":2}"}) {
            try {
                RawJson.removeMember(bad, "a");
                fail(bad);
            } catch (IllegalArgumentException expected) {
                // not an object
            }
        }
        try {
            RawJson.removeMember(null, "a");
            fail();
        } catch (IllegalArgumentException expected) {
            // null
        }
    }

    @Test
    public void requestFieldsGainTimingOnlyWhenAsked() {
        LinkedHashMap<String, String> f = new LinkedHashMap<>();
        f.put("refText", "x");
        assertSame(f, ServerTiming.requestFields(f, false));
        LinkedHashMap<String, String> t = ServerTiming.requestFields(f, true);
        assertEquals("1", t.get("timing"));
        assertFalse("the session's own fields stay as they are", f.containsKey("timing"));
        YLog.resetLevel();
        assertFalse("WARN by default", ServerTiming.wanted());
        YLog.setLevelPinned(YLog.INFO);
        assertTrue(ServerTiming.wanted());
        YLog.setLevelPinned(YLog.DEBUG);
        assertTrue(ServerTiming.wanted());
        YLog.setLevelPinned(YLog.ERROR);
        assertFalse(ServerTiming.wanted());
    }

    @Test
    public void restSummaryWithMetricsAndHeader() throws Exception {
        captureAt(YLog.DEBUG);
        EvalTiming t = new EvalTiming("0123456789abcdef0123456789abcdef");
        t.stop();
        t.audioFile(61518, 1.92);
        t.restAttempt(1);
        LinkedHashMap<String, String> h = new LinkedHashMap<>();
        h.put("Server-Timing", "total;dur=812, engine;dur=700, platform;dur=112");
        t.restResponse(new HttpTransport.Response(503, h, "", metrics(10, 0)));
        t.restAttempt(2);
        Thread.sleep(2);
        HttpTransport.Metrics m = metrics(397244, 120344);
        m.bodyBytes = 1534212;
        m.contentEncoding = "gzip";
        t.restResponse(new HttpTransport.Response(200, h, "{}", m));
        t.serverBody(null);
        t.parsed();
        t.delivered();
        String line = t.summary();
        assertTrue(line, line.matches("timing tokenId=0123456789abcdef0123456789abcdef mode=rest audio=1\\.92s afterStop=\\d+ms"
                + " upload=\\d+ms wait=\\d+ms download=\\d+ms parse=\\d+ms deliver=\\d+ms attempts=2 sent=397244B"
                + " body=1534212B wire=120344B gzip server.total=812ms server.engine=700ms server.platform=112ms"));
        t.log();
        assertTrue(String.valueOf(lines), lines.contains("3 " + line));
        assertTrue(String.valueOf(lines), anyStartsWith("4 stage tokenId=0123456789abcdef0123456789abcdef +"));
        assertTrue(String.valueOf(lines), anyContains(" rest.headers status=200 server-timing=\"total;dur=812, engine;dur=700, platform;dur=112\""));
        assertTrue(String.valueOf(lines), anyContains(" rest.body bytes=1534212 wire=120344 gzip"));
        assertFalse("no engine stages without the timing object", anyContains("engineStages"));
    }

    @Test
    public void errorAndFallbackSummaryAndUnknownValues() {
        captureAt(YLog.INFO);
        EvalTiming t = new EvalTiming("tok");
        t.streaming();
        t.fallback("cannot open the realtime channel: ConnectException refused");
        t.restAttempt(1);
        t.restResponse(new HttpTransport.Response(400, new LinkedHashMap<String, String>(), "{\"code\":40001}"));
        t.error(40001);
        t.delivered();
        String line = t.summary();
        assertTrue(line, line.startsWith("timing tokenId=tok mode=ws>rest audio=- afterStop=- upload=- wait=- download="));
        assertTrue(line, line.contains(" parse=- deliver=- attempts=1 sent=- body=14B wire=- errId=40001 fallback=\"cannot open"));
        t.log();
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).startsWith("3 timing tokenId=tok "));
        // at WARN nothing is logged
        lines.clear();
        YLog.setLevelPinned(YLog.WARN);
        t.log();
        t.stop();
        assertTrue(lines.isEmpty());
    }

    @Test
    public void wsSummaryWithServerBodyTimingAndStagesAtDebug() {
        captureAt(YLog.DEBUG);
        EvalTiming t = new EvalTiming("tok");
        t.streaming();
        t.wsConnectStart();
        t.wsHandshakeDone("permessage-deflate; client_max_window_bits=15");
        t.wsParamSent();
        t.wsParamSent();
        t.wsAudioSent();
        t.wsAudioSent();
        t.audioPcm(64000);
        t.stop();
        t.wsEndQueued(6400);
        t.wsEndQueued(1);
        t.wsEndSent();
        t.wsEndSent();
        long now = System.nanoTime();
        t.wsFinal(new WsConnection.Inbound(now, now + 3000000L, 6120, 41210, true), 41000);
        t.wsConnectStart();
        t.serverBody(ServerTiming.fromObject(TIMING));
        t.replayed(true);
        t.parsed();
        t.delivered();
        String line = t.summary();
        assertTrue(line, line.matches("timing tokenId=tok mode=ws audio=2\\.00s afterStop=\\d+ms upload=\\d+ms wait=\\d+ms"
                + " download=3ms parse=\\d+ms deliver=\\d+ms handshake=\\d+ms queued=6400B final=41210B wire=6120B deflate"
                + " connections=2 server.total=5321ms server.engine=4100ms server.platform=1221ms replayed"));
        t.log();
        assertTrue(lines.contains("4 timing tokenId=tok engineStages text_frontend_ms=1909.3 asr_ms=595.0"));
        assertTrue(String.valueOf(lines), anyContains(" ws.handshake permessage-deflate; client_max_window_bits=15"));
        assertTrue(String.valueOf(lines), anyContains(" ws.end.queued queued=6400B"));
        assertTrue(String.valueOf(lines), anyContains(" ws.final bytes=41210 wire=6120 deflate"));
        // without arrival facts the text size stands for both
        EvalTiming u = new EvalTiming("u");
        u.streaming();
        u.wsHandshakeDone("");
        u.wsFinal(null, 99);
        assertTrue(u.summary(), u.summary().contains(" final=99B wire=99B"));
        assertFalse(u.summary().contains("deflate"));
        assertTrue(String.valueOf(lines), anyContains(" ws.handshake no extension"));
    }

    private static HttpTransport.Metrics metrics(long sent, long wire) {
        HttpTransport.Metrics m = new HttpTransport.Metrics();
        long now = System.nanoTime();
        m.startNanos = now;
        m.uploadDoneNanos = now + 1000000L;
        m.headersNanos = now + 2000000L;
        m.bodyDoneNanos = now + 3000000L;
        m.uploadBytes = sent;
        m.wireBytes = wire;
        m.bodyBytes = wire;
        return m;
    }

    private boolean anyStartsWith(String prefix) {
        synchronized (lines) {
            for (String l : lines) {
                if (l.startsWith(prefix)) {
                    return true;
                }
            }
            return false;
        }
    }

    private boolean anyContains(String part) {
        synchronized (lines) {
            for (String l : lines) {
                if (l.contains(part)) {
                    return true;
                }
            }
            return false;
        }
    }
}
