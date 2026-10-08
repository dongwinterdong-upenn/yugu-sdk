package com.shengzhiai.yugu.stcompat.testing;

import com.shengzhiai.yugu.stcompat.internal.CancelToken;
import com.shengzhiai.yugu.stcompat.internal.HttpTransport;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Scripted {@link HttpTransport}: returns queued responses (or throws queued exceptions) and records requests. */
public final class FakeTransport implements HttpTransport {
    /** A recorded request with its body. */
    public static final class Call {
        public final Request request;
        public final byte[] body;

        Call(Request request, byte[] body) {
            this.request = request;
            this.body = body;
        }

        public String header(String name) {
            return request.headers.get(name);
        }

        public String bodyText() {
            return new String(body, java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private final Deque<Object> script = new ArrayDeque<>();
    private final List<Call> calls = Collections.synchronizedList(new ArrayList<Call>());
    private volatile String defaultBody = Fixtures.text("spec/fixtures/platform/compat_sent.eval.cn.json");
    private volatile long delayMs;

    public synchronized FakeTransport respond(int status, String body) {
        return respond(status, body, null);
    }

    public synchronized FakeTransport respond(int status, String body, Map<String, String> headers) {
        script.add(new Response(status, headers == null ? new HashMap<String, String>() : headers, body));
        return this;
    }

    public synchronized FakeTransport fail(IOException e) {
        script.add(e);
        return this;
    }

    /** Body returned when the script is empty (HTTP 200). */
    public FakeTransport defaultBody(String body) {
        this.defaultBody = body;
        return this;
    }

    /** Real-time delay of every call (to test cancellation in flight). */
    public FakeTransport delay(long ms) {
        this.delayMs = ms;
        return this;
    }

    public List<Call> calls() {
        synchronized (calls) {
            return new ArrayList<>(calls);
        }
    }

    public int count() {
        return calls.size();
    }

    @Override
    public Response execute(Request request, CancelToken token) throws IOException {
        byte[] body = request.body == null ? new byte[0] : request.body.toByteArray();
        calls.add(new Call(request, body));
        if (delayMs > 0) {
            if (token != null && !token.sleep(delayMs)) {
                throw new IOException("cancelled");
            }
        }
        Object next;
        synchronized (this) {
            next = script.poll();
        }
        if (next instanceof IOException) {
            throw (IOException) next;
        }
        if (next instanceof Response) {
            return (Response) next;
        }
        return new Response(200, new HashMap<String, String>(), defaultBody);
    }

    public static String error(int code, String message) {
        return "{\"code\":" + code + ",\"message\":\"" + message + "\",\"timestamp\":1791447692255}";
    }
}
