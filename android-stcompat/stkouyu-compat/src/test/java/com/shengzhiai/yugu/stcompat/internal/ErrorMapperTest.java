package com.shengzhiai.yugu.stcompat.internal;

import com.shengzhiai.yugu.stcompat.testing.FakeTransport;
import com.shengzhiai.yugu.stcompat.testing.Fixtures;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.security.cert.CertificateException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Acceptance 6.6: every entry of spec/errors.json, through the real retry loop. */
@RunWith(RobolectricTestRunner.class)
public class ErrorMapperTest {
    private static CompatClient.Request request() {
        CompatClient.Request r = new CompatClient.Request();
        r.baseUrl = "http://test";
        r.coreType = "sent.eval";
        r.appKey = "ak";
        r.secretKey = "sk";
        r.idempotencyKey = Codec.newTokenId();
        r.fields.put("refText", "hi");
        r.audioBytes = new byte[100];
        return r;
    }

    private static CompatClient client(FakeTransport t) {
        return new CompatClient(t, new Random(1), new CompatClient.Sleeper() {
            @Override
            public boolean sleep(CancelToken token, long ms) {
                return true;
            }
        });
    }

    @Test
    public void everyPlatformErrorCode() throws Exception {
        JSONObject spec = new JSONObject(Fixtures.text("spec/errors.json"));
        JSONArray errors = spec.getJSONArray("errors");
        assertEquals(36, errors.length());
        for (int i = 0; i < errors.length(); i++) {
            JSONObject e = errors.getJSONObject(i);
            int code = e.getInt("code");
            int http = e.getInt("http");
            boolean retryable = e.getBoolean("retryable");
            FakeTransport t = new FakeTransport();
            for (int k = 0; k < 3; k++) {
                t.respond(http, FakeTransport.error(code, e.getString("message")));
            }
            try {
                client(t).execute(request(), new CancelToken());
                fail("code " + code + " did not fail");
            } catch (CompatError err) {
                assertEquals("code " + code, code, err.code);
                assertEquals("code " + code, http, err.httpStatus);
                assertEquals("retryable " + code, retryable, err.retryable);
                assertEquals("retryable " + code, retryable, ErrorMapper.isRetryable(code, http));
                assertEquals("attempts " + code, retryable ? 3 : 1, err.getAttempts());
                assertEquals("calls " + code, retryable ? 3 : 1, t.count());
                assertEquals("errId " + code, retryable ? 20009 : code, ErrorMapper.errId(err));
                String msg = ErrorMapper.message(err, ErrorMapper.errId(err));
                assertTrue(msg, retryable ? msg.contains("HTTP " + http) : msg.equals(e.getString("message")));
                ErrorTable.Entry entry = ErrorTable.ERRORS.get(code);
                assertEquals(e.getString("category"), entry.category);
                assertEquals(e.getString("name"), entry.name);
            }
        }
    }

    @Test
    public void warningsAreNotErrors() throws Exception {
        JSONObject spec = new JSONObject(Fixtures.text("spec/errors.json"));
        JSONArray w = spec.getJSONArray("warnings");
        for (int i = 0; i < w.length(); i++) {
            JSONObject e = w.getJSONObject(i);
            int code = e.getInt("code");
            ErrorTable.Entry entry = ErrorTable.WARNINGS.get(code);
            assertEquals("AUDIO", entry.category);
            assertEquals(e.getBoolean("retryable"), entry.retryable);
            // a 200 result that carries the warning is a success
            FakeTransport t = new FakeTransport();
            t.respond(200, "{\"recordId\":\"eval_w\",\"eof\":1,\"result\":{\"overall\":0,\"warning\":[{\"code\":" + code
                    + ",\"message\":\"" + e.getString("message") + "\"}]}}");
            CompatClient.Result r = client(t).execute(request(), new CancelToken());
            assertEquals(1, r.attempts);
        }
    }

    /** DESIGN 2.4: 1004 and 1005 are user errors in an error body and audio warnings in result.warning. */
    @Test
    public void codesInBothNamespacesAreResolvedByContext() throws Exception {
        for (int code : new int[] {1004, 1005}) {
            FakeTransport t = new FakeTransport();
            t.respond(400, FakeTransport.error(code, "user"));
            try {
                client(t).execute(request(), new CancelToken());
                fail();
            } catch (CompatError err) {
                assertEquals("PERMISSION", ErrorTable.ERRORS.get(code).category);
                assertFalse(err.retryable);
                assertEquals(code, ErrorMapper.errId(err));
                assertEquals(1, t.count());
            }
            FakeTransport ok = new FakeTransport();
            ok.respond(200, "{\"recordId\":\"eval_x\",\"eof\":1,\"result\":{\"overall\":50,\"warning\":[{\"code\":" + code
                    + ",\"message\":\"noisy\"}]}}");
            assertEquals(1, client(ok).execute(request(), new CancelToken()).attempts);
            assertEquals("AUDIO", ErrorTable.WARNINGS.get(code).category);
        }
        // statuses missing from httpFallback map by class
        assertFalse(ErrorMapper.isRetryable(0, 418));
        assertFalse(ErrorMapper.isRetryable(0, 505));
        assertTrue(ErrorMapper.isRetryable(0, 503));
        assertFalse(ErrorMapper.isRetryable(0, 302));
    }

    @Test
    public void everyLocalCode() throws Exception {
        JSONObject spec = new JSONObject(Fixtures.text("spec/errors.json"));
        JSONArray local = spec.getJSONArray("local");
        for (int i = 0; i < local.length(); i++) {
            JSONObject e = local.getJSONObject(i);
            int code = e.getInt("code");
            CompatError err = CompatError.local(code, "x", null);
            assertEquals("local " + code, e.getBoolean("retryable"), err.retryable);
            assertEquals("local " + code, e.getBoolean("retryable"), ErrorMapper.isRetryable(code, 0));
            assertEquals("local " + code, e.getBoolean("retryable") ? 20009 : code, ErrorMapper.errId(err));
            assertEquals(e.getString("category"), ErrorTable.LOCAL.get(code).category);
            assertTrue(err.describe().contains(ErrorTable.LOCAL.get(code).name));
        }
        assertTrue(ErrorMapper.isRetryable(90001, 0));
        assertTrue(ErrorMapper.isRetryable(90002, 0));
        assertTrue(ErrorMapper.isRetryable(90007, 0));
        assertFalse(ErrorMapper.isRetryable(90005, 0));
    }

    @Test
    public void httpFallbackWithoutBusinessCode() throws Exception {
        JSONObject spec = new JSONObject(Fixtures.text("spec/errors.json"));
        JSONObject fallback = spec.getJSONObject("httpFallback");
        JSONArray retryableHttp = spec.getJSONArray("retryableHttp");
        java.util.Set<Integer> retry = new java.util.HashSet<>();
        for (int i = 0; i < retryableHttp.length(); i++) {
            retry.add(retryableHttp.getInt(i));
        }
        java.util.Iterator<String> it = fallback.keys();
        while (it.hasNext()) {
            int status = Integer.parseInt(it.next());
            FakeTransport t = new FakeTransport();
            for (int k = 0; k < 3; k++) {
                t.respond(status, "<html><body>" + status + "</body></html>");
            }
            try {
                client(t).execute(request(), new CancelToken());
                fail();
            } catch (CompatError err) {
                assertEquals("status " + status, retry.contains(status), err.retryable);
                assertEquals(0, err.code);
                int errId = ErrorMapper.errId(err);
                if (retry.contains(status)) {
                    assertEquals(20009, errId);
                    assertEquals(3, t.count());
                } else {
                    assertEquals(1, t.count());
                    assertTrue("status " + status + " errId " + errId, errId >= 40000);
                }
            }
        }
        assertEquals(40100, ErrorMapper.httpFallbackCode(401));
        assertEquals(40300, ErrorMapper.httpFallbackCode(403));
        assertEquals(40400, ErrorMapper.httpFallbackCode(404));
        assertEquals(40900, ErrorMapper.httpFallbackCode(409));
        assertEquals(40001, ErrorMapper.httpFallbackCode(400));
        assertEquals(40001, ErrorMapper.httpFallbackCode(415));
        assertEquals(50010, ErrorMapper.httpFallbackCode(501));
        assertEquals(50000, ErrorMapper.httpFallbackCode(500));
        assertEquals(50200, ErrorMapper.httpFallbackCode(502));
        assertEquals(42900, ErrorMapper.httpFallbackCode(429));
        assertEquals(20009, ErrorMapper.httpFallbackCode(408));
        assertEquals(40001, ErrorMapper.httpFallbackCode(418));
        assertEquals(50000, ErrorMapper.httpFallbackCode(599));
        assertEquals(90005, ErrorMapper.httpFallbackCode(302));
    }

    @Test
    public void bodyShapes() {
        Map<String, String> none = Collections.emptyMap();
        CompatError fastapi = ErrorMapper.fromResponse(new HttpTransport.Response(401, none, "{\"detail\":\"[2001] missing triple\"}"));
        assertEquals(2001, fastapi.code);
        assertEquals("[2001] missing triple", fastapi.getMessage());
        CompatError list = ErrorMapper.fromResponse(new HttpTransport.Response(422, none, "{\"detail\":[{\"msg\":\"field required\"}]}"));
        assertEquals(0, list.code);
        assertTrue(list.getMessage().contains("field required"));
        assertEquals(40001, ErrorMapper.errId(list));
        CompatError st = ErrorMapper.fromResponse(new HttpTransport.Response(400, none, "{\"errId\":40001,\"error\":\"bad\"}"));
        assertEquals(40001, st.code);
        assertEquals("bad", st.getMessage());
        CompatError plain = ErrorMapper.fromResponse(new HttpTransport.Response(503, none, "Service Unavailable"));
        assertEquals("HTTP 503", plain.getMessage());
        assertTrue(plain.retryable);
        String big = new String(new char[5000]).replace('\0', 'x');
        CompatError longBody = ErrorMapper.fromResponse(new HttpTransport.Response(500, none, big));
        assertEquals(4096, longBody.rawBody.length());
        CompatError unknownCode = ErrorMapper.fromResponse(new HttpTransport.Response(503, none, FakeTransport.error(77777, "?")));
        assertTrue(unknownCode.retryable);
        assertEquals(20009, ErrorMapper.errId(unknownCode));
        CompatError unknown4xx = ErrorMapper.fromResponse(new HttpTransport.Response(400, none, FakeTransport.error(77777, "?")));
        assertFalse(unknown4xx.retryable);
        assertEquals(77777, ErrorMapper.errId(unknown4xx));
        Map<String, String> h = new HashMap<>();
        h.put("Retry-After", "2");
        CompatError ra = ErrorMapper.fromResponse(new HttpTransport.Response(429, h, FakeTransport.error(42900, "排队超时")));
        assertEquals(2000, ra.retryAfterMs);
        assertTrue(ra.describe().contains("HTTP 429 code=42900"));
    }

    @Test
    public void retryAfterParsing() {
        assertEquals(-1, ErrorMapper.parseRetryAfter(null, 0));
        assertEquals(-1, ErrorMapper.parseRetryAfter(" ", 0));
        assertEquals(30000, ErrorMapper.parseRetryAfter("30", 0));
        assertEquals(-1, ErrorMapper.parseRetryAfter("-3", 0));
        assertEquals(-1, ErrorMapper.parseRetryAfter("soon", 0));
        long now = 1791504000000L; // 2026-10-09T00:00:00Z
        assertEquals(5000, ErrorMapper.parseRetryAfter("Fri, 09 Oct 2026 00:00:05 GMT", now));
        assertEquals(0, ErrorMapper.parseRetryAfter("Thu, 01 Jan 2026 00:00:00 GMT", now));
    }

    @Test
    public void transportExceptions() {
        assertEquals(90002, ErrorMapper.fromException(new SocketTimeoutException("Read timed out")).code);
        assertEquals(90002, ErrorMapper.fromException(new java.io.InterruptedIOException("timed out")).code);
        assertEquals(90001, ErrorMapper.fromException(new java.io.InterruptedIOException("interrupted")).code);
        assertEquals(90001, ErrorMapper.fromException(new ConnectException("refused")).code);
        assertEquals(90001, ErrorMapper.fromException(new UnknownHostException("x")).code);
        assertEquals(90001, ErrorMapper.fromException(new IOException()).code);
        assertEquals(90011, ErrorMapper.fromException(new SSLPeerUnverifiedException("x")).code);
        SSLHandshakeException cert = new SSLHandshakeException("bad cert");
        cert.initCause(new CertificateException("expired"));
        assertEquals(90011, ErrorMapper.fromException(cert).code);
        assertFalse(ErrorMapper.fromException(cert).retryable);
        assertEquals(90001, ErrorMapper.fromException(new SSLHandshakeException("reset")).code);
        assertTrue(ErrorMapper.fromException(new ConnectException("refused")).retryable);
    }
}
