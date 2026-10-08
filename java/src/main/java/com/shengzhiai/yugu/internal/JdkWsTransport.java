package com.shengzhiai.yugu.internal;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** {@link WsTransport} on {@code java.net.http.WebSocket}. */
public final class JdkWsTransport implements WsTransport {
    private final HttpClient http;

    /** @param http client that opens the connections */
    public JdkWsTransport(HttpClient http) {
        this.http = http;
    }

    @Override
    public CompletableFuture<Void> connect(URI uri, Map<String, String> headers, Duration connectTimeout, Listener listener) {
        WebSocket.Builder b = http.newWebSocketBuilder().connectTimeout(connectTimeout);
        for (Map.Entry<String, String> h : headers.entrySet()) {
            b.header(h.getKey(), h.getValue());
        }
        Conn conn = new Conn(listener);
        CompletableFuture<Void> f = new CompletableFuture<>();
        try {
            b.buildAsync(uri, conn).whenComplete((ws, err) -> {
                if (err != null) {
                    f.completeExceptionally(err);
                } else {
                    f.complete(null);
                }
            });
        } catch (RuntimeException e) {
            f.completeExceptionally(e);
        }
        return f;
    }

    /** One JDK WebSocket and its ordered send queue. */
    static final class Conn implements WebSocket.Listener, Connection {
        private final Listener listener;
        private final StringBuilder text = new StringBuilder();
        private volatile WebSocket ws;
        private CompletableFuture<?> chain = CompletableFuture.completedFuture(null);
        private final java.util.concurrent.atomic.AtomicBoolean failed = new java.util.concurrent.atomic.AtomicBoolean();

        Conn(Listener listener) {
            this.listener = listener;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            this.ws = webSocket;
            webSocket.request(Long.MAX_VALUE);
            listener.onOpen(this);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            text.append(data);
            if (last) {
                String s = text.toString();
                text.setLength(0);
                listener.onText(s);
            }
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            return null;
        }

        @Override
        public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
            return null;
        }

        @Override
        public CompletionStage<?> onPong(WebSocket webSocket, ByteBuffer message) {
            listener.onPong();
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            listener.onClose(statusCode, reason);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            fail(error);
        }

        private void fail(Throwable error) {
            if (failed.compareAndSet(false, true)) {
                listener.onError(error);
            }
        }

        private synchronized CompletableFuture<Void> enqueue(java.util.function.Supplier<CompletableFuture<WebSocket>> send) {
            CompletableFuture<Void> done = new CompletableFuture<>();
            chain = chain.handle((v, e) -> null).thenCompose(v -> {
                if (failed.get()) {
                    done.completeExceptionally(new java.io.IOException("connection failed"));
                    return CompletableFuture.completedFuture(null);
                }
                CompletableFuture<WebSocket> s;
                try {
                    s = send.get();
                } catch (RuntimeException e) {
                    done.completeExceptionally(e);
                    fail(e);
                    return CompletableFuture.completedFuture(null);
                }
                return s.handle((w, e) -> {
                    if (e != null) {
                        done.completeExceptionally(e);
                        fail(e);
                    } else {
                        done.complete(null);
                    }
                    return null;
                });
            });
            return done;
        }

        @Override
        public CompletableFuture<Void> sendText(String s) {
            return enqueue(() -> ws.sendText(s, true));
        }

        @Override
        public CompletableFuture<Void> sendBinary(byte[] data) {
            return enqueue(() -> ws.sendBinary(ByteBuffer.wrap(data), true));
        }

        @Override
        public CompletableFuture<Void> sendPing() {
            CompletableFuture<Void> done = new CompletableFuture<>();
            try {
                ws.sendPing(ByteBuffer.allocate(0)).whenComplete((w, e) -> {
                    if (e != null) {
                        done.completeExceptionally(e);
                    } else {
                        done.complete(null);
                    }
                });
            } catch (RuntimeException e) {
                done.completeExceptionally(e);
            }
            return done;
        }

        @Override
        public CompletableFuture<Void> close(int code, String reason) {
            CompletableFuture<Void> done = new CompletableFuture<>();
            synchronized (this) {
                chain = chain.handle((v, e) -> null).thenCompose(v -> {
                    try {
                        return ws.sendClose(code, reason == null ? "" : reason).handle((w, e) -> {
                            done.complete(null);
                            return null;
                        });
                    } catch (RuntimeException e) {
                        done.complete(null);
                        return CompletableFuture.completedFuture(null);
                    }
                });
            }
            return done;
        }

        @Override
        public void abort() {
            WebSocket w = ws;
            if (w != null) {
                w.abort();
            }
        }
    }
}
