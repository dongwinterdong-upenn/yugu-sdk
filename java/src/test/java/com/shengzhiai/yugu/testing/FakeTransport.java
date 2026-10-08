package com.shengzhiai.yugu.testing;

import com.shengzhiai.yugu.internal.WsTransport;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Scripted WebSocket transport. By default every connection behaves like a healthy platform:
 * "connected" on open, "started" after the start frame, the result after the end frame.
 */
public final class FakeTransport implements WsTransport {
    public static final String CONNECTED = "{\"event\":\"connected\",\"message\":\"stream channel ready\"}";
    public static final String STARTED = "{\"event\":\"started\"}";
    public static final String RESULT = "{\"event\":\"result\",\"recordId\":\"eval_fake\",\"eof\":1,"
            + "\"result\":{\"overall\":91.5,\"fluency\":90,\"warning\":[],\"words\":[{\"word\":\"今\",\"scores\":{\"overall\":88}}]},"
            + "\"warnings\":[]}";

    /** Decides what connection number {@code n} (from 1) does. */
    public volatile Function<Integer, Script> scripts = n -> new Script();
    public final List<Conn> connections = new CopyOnWriteArrayList<>();
    private final LinkedBlockingQueue<Conn> opened = new LinkedBlockingQueue<>();

    /** Behaviour of one connection. */
    public static final class Script {
        public Throwable connectError;
        public boolean neverOpen;
        public boolean sendConnected = true;
        public boolean answerStart = true;
        public String resultFrame = RESULT;
        public boolean answerEnd = true;
        public boolean autoPong = true;
        /** Fail the connection after this many binary frames, -1 for never. */
        public int failAfterBinary = -1;
        /** Close code sent right after "started", 0 for none: a server that accepts and then drops. */
        public int closeAfterStart;

        public Script connectError(Throwable t) {
            this.connectError = t;
            return this;
        }

        public Script silentAfterStart() {
            this.answerEnd = false;
            return this;
        }
    }

    @Override
    public CompletableFuture<Void> connect(URI uri, Map<String, String> headers, Duration timeout, Listener listener) {
        int n = connections.size() + 1;
        Script s = scripts.apply(n);
        Conn c = new Conn(n, uri, headers, listener, s);
        connections.add(c);
        if (s.connectError != null) {
            CompletableFuture<Void> f = new CompletableFuture<>();
            CompletableFuture.runAsync(() -> f.completeExceptionally(s.connectError));
            return f;
        }
        if (s.neverOpen) {
            return new CompletableFuture<>();
        }
        return CompletableFuture.runAsync(() -> {
            listener.onOpen(c);
            opened.add(c);
            if (s.sendConnected) {
                listener.onText(CONNECTED);
            }
        });
    }

    public Conn awaitConnection(int n, long timeoutMs) throws InterruptedException {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            if (connections.size() >= n && connections.get(n - 1).open) {
                return connections.get(n - 1);
            }
            Thread.sleep(5);
        }
        throw new AssertionError("connection " + n + " not opened within " + timeoutMs + " ms, have " + connections.size());
    }

    public Conn last() {
        return connections.get(connections.size() - 1);
    }

    /** One fake connection. */
    public static final class Conn implements Connection {
        public final int number;
        public final URI uri;
        public final Map<String, String> headers;
        public final Listener listener;
        public final Script script;
        public final List<String> texts = new CopyOnWriteArrayList<>();
        public final List<byte[]> binaries = new CopyOnWriteArrayList<>();
        /** Every sent frame in order: {@code T:<text>} or {@code B:<length>}. */
        public final List<String> sequence = new CopyOnWriteArrayList<>();
        public volatile int pings;
        public volatile boolean open;
        public volatile boolean aborted;
        public volatile Integer closeCode;

        Conn(int number, URI uri, Map<String, String> headers, Listener listener, Script script) {
            this.number = number;
            this.uri = uri;
            this.headers = headers;
            this.listener = listener;
            this.script = script;
            this.open = true;
        }

        @Override
        public CompletableFuture<Void> sendText(String text) {
            texts.add(text);
            sequence.add("T:" + text);
            if (text.contains("\"cmd\":\"end\"")) {
                if (script.answerEnd && script.resultFrame != null) {
                    serverSendAsync(script.resultFrame);
                }
            } else if (texts.size() == 1 && script.answerStart) {
                if (script.closeAfterStart != 0) {
                    int code = script.closeAfterStart;
                    CompletableFuture.runAsync(() -> {
                        listener.onText(STARTED);
                        listener.onClose(code, "dropped after start");
                    });
                } else {
                    serverSendAsync(STARTED);
                }
            }
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> sendBinary(byte[] data) {
            binaries.add(data);
            sequence.add("B:" + data.length);
            if (script.failAfterBinary >= 0 && binaries.size() == script.failAfterBinary) {
                CompletableFuture.runAsync(() -> listener.onError(new java.io.IOException("connection reset by fake")));
            }
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> sendPing() {
            pings++;
            if (script.autoPong) {
                CompletableFuture.runAsync(listener::onPong);
            }
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> close(int code, String reason) {
            closeCode = code;
            open = false;
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void abort() {
            aborted = true;
            open = false;
        }

        public void serverSend(String json) {
            listener.onText(json);
        }

        public void serverSendAsync(String json) {
            CompletableFuture.runAsync(() -> listener.onText(json));
        }

        public void serverClose(int code, String reason) {
            listener.onClose(code, reason);
        }

        public void fail(Throwable t) {
            listener.onError(t);
        }

        public long audioBytes() {
            long n = 0;
            for (byte[] b : binaries) {
                n += b.length;
            }
            return n;
        }

        /** Throws when audio was sent after the end frame on this connection. */
        public void assertNoAudioAfterEnd() {
            boolean ended = false;
            for (String f : sequence) {
                if (f.startsWith("T:") && f.contains("\"cmd\":\"end\"")) {
                    ended = true;
                } else if (ended && f.startsWith("B:")) {
                    throw new AssertionError("audio after the end frame on connection " + number + ": " + sequence);
                }
            }
        }

        public List<String> textsCopy() {
            return Collections.unmodifiableList(new ArrayList<>(texts));
        }
    }

    public static void sleep(long ms) {
        try {
            TimeUnit.MILLISECONDS.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
