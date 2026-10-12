package com.shengzhiai.yugu.stcompat.testing;

import com.shengzhiai.yugu.stcompat.internal.WsConnection;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Scripted realtime connector: records every handshake and client frame and lets the test (or a
 * {@link Script}) play the platform side through {@link Conn#serverText} and friends.
 */
public final class FakeWs implements WsConnection.Connector {
    public static final String END = "{\"cmd\":\"end\"}";

    /** Refuses every connection, like a network without WebSocket access. */
    public static final WsConnection.Connector REFUSING = new WsConnection.Connector() {
        @Override
        public WsConnection connect(String url, Map<String, String> headers, int connectTimeoutMs, int readTimeoutMs,
                                    WsConnection.Listener listener) throws IOException {
            throw new ConnectException("realtime channel refused (unit test default)");
        }
    };

    /** Platform behaviour called on the client's sending thread. */
    public interface Script {
        void onText(Conn c, String text);

        void onBinary(Conn c, byte[] data);
    }

    public final List<Conn> connections = Collections.synchronizedList(new ArrayList<Conn>());
    private final Deque<IOException> refusals = new ArrayDeque<>();
    public volatile Script script;

    /** The next connect throws {@code e} (queued, one per connect). */
    public synchronized FakeWs refuseNext(IOException e) {
        refusals.add(e);
        return this;
    }

    public FakeWs script(Script s) {
        this.script = s;
        return this;
    }

    public Conn last() {
        synchronized (connections) {
            return connections.isEmpty() ? null : connections.get(connections.size() - 1);
        }
    }

    public Conn get(int i) {
        return connections.get(i);
    }

    public int count() {
        return connections.size();
    }

    @Override
    public WsConnection connect(String url, Map<String, String> headers, int connectTimeoutMs, int readTimeoutMs,
                                WsConnection.Listener listener) throws IOException {
        IOException refuse;
        synchronized (this) {
            refuse = refusals.poll();
        }
        Conn c = new Conn(url, headers, connectTimeoutMs, readTimeoutMs, listener);
        connections.add(c);
        if (refuse != null) {
            c.refused = true;
            throw refuse;
        }
        return c;
    }

    /** One fake connection. */
    public final class Conn implements WsConnection {
        public final String url;
        public final Map<String, String> headers;
        public final int connectTimeoutMs;
        public final int readTimeoutMs;
        public final List<String> texts = Collections.synchronizedList(new ArrayList<String>());
        public final List<Integer> binarySizes = Collections.synchronizedList(new ArrayList<Integer>());
        public final AtomicInteger pings = new AtomicInteger();
        private final ByteArrayOutputStream binary = new ByteArrayOutputStream();
        private final WsConnection.Listener listener;
        public volatile boolean refused;
        public volatile boolean closed;
        public volatile boolean aborted;
        public volatile int closeCode;
        public volatile int maxMessageBytes;
        /** When set, sends fail with this exception. */
        public volatile IOException failSends;

        Conn(String url, Map<String, String> headers, int connectTimeoutMs, int readTimeoutMs, WsConnection.Listener listener) {
            this.url = url;
            this.headers = new LinkedHashMap<>(headers);
            this.connectTimeoutMs = connectTimeoutMs;
            this.readTimeoutMs = readTimeoutMs;
            this.listener = listener;
        }

        @Override
        public void sendText(String text) throws IOException {
            check();
            texts.add(text);
            Script s = script;
            if (s != null) {
                s.onText(this, text);
            }
        }

        @Override
        public void sendBinary(byte[] data, int offset, int length) throws IOException {
            check();
            byte[] copy = new byte[length];
            System.arraycopy(data, offset, copy, 0, length);
            synchronized (binary) {
                binary.write(copy, 0, length);
            }
            binarySizes.add(length);
            Script s = script;
            if (s != null) {
                s.onBinary(this, copy);
            }
        }

        @Override
        public void ping() throws IOException {
            check();
            pings.incrementAndGet();
        }

        @Override
        public void setMaxMessageBytes(int bytes) {
            maxMessageBytes = bytes;
        }

        @Override
        public void close(int code, String reason) {
            closeCode = code;
            closed = true;
        }

        @Override
        public void abort() {
            aborted = true;
        }

        private void check() throws IOException {
            IOException f = failSends;
            if (f != null) {
                throw f;
            }
            if (closed || aborted) {
                throw new IOException("fake connection closed");
            }
        }

        public boolean isOpen() {
            return !closed && !aborted;
        }

        // ------------------------------------------------------------ platform side

        public void serverText(String text) {
            listener.onText(text);
        }

        public void serverBinary(byte[] data) {
            listener.onBinary(data);
        }

        public void serverClose(int code, String reason) {
            listener.onClosed(code, reason);
        }

        public void serverFail(IOException e) {
            listener.onFailure(e);
        }

        // ------------------------------------------------------------ inspection

        public byte[] audio() {
            synchronized (binary) {
                return binary.toByteArray();
            }
        }

        public boolean endReceived() {
            synchronized (texts) {
                return texts.contains(END);
            }
        }

        public String paramFrame() {
            synchronized (texts) {
                return texts.isEmpty() ? null : texts.get(0);
            }
        }

        /** Decoded query parameters of the handshake URL. */
        public Map<String, String> query() {
            Map<String, String> q = new LinkedHashMap<>();
            int i = url.indexOf('?');
            if (i < 0) {
                return q;
            }
            for (String pair : url.substring(i + 1).split("&")) {
                int eq = pair.indexOf('=');
                try {
                    q.put(URLDecoder.decode(pair.substring(0, eq), "UTF-8"), URLDecoder.decode(pair.substring(eq + 1), "UTF-8"));
                } catch (java.io.UnsupportedEncodingException e) {
                    throw new IllegalStateException(e);
                }
            }
            return q;
        }

        /** URL without the query. */
        public String path() {
            int i = url.indexOf('?');
            return i < 0 ? url : url.substring(0, i);
        }

        public String text(int i) {
            return texts.get(i);
        }

        @Override
        public String toString() {
            return "Conn{" + path() + ", texts=" + texts.size() + ", audio=" + audio().length + ", closed=" + closed
                    + ", aborted=" + aborted + "}";
        }
    }

    /** Charset helper for tests. */
    public static String utf8(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }
}
