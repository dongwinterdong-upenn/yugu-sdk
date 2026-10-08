package com.shengzhiai.yugu.testing;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** In-JVM HTTP server with scripted responses for REST unit tests. */
public final class StubServer implements AutoCloseable {
    /** A received request. */
    public static final class Req {
        public final String method;
        public final String path;
        public final String rawPath;
        public final Map<String, String> headers;
        public final byte[] body;
        public final long atMs;

        Req(String method, String path, String rawPath, Map<String, String> headers, byte[] body) {
            this.method = method;
            this.path = path;
            this.rawPath = rawPath;
            this.headers = headers;
            this.body = body;
            this.atMs = System.currentTimeMillis();
        }

        public String header(String name) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                if (e.getKey().equalsIgnoreCase(name)) {
                    return e.getValue();
                }
            }
            return null;
        }

        public String bodyText() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    /** A scripted response. */
    public static final class Resp {
        final int status;
        final String body;
        final Map<String, String> headers = new LinkedHashMap<>();
        long delayMs;
        boolean drop;

        public Resp(int status, String body) {
            this.status = status;
            this.body = body;
        }

        public Resp header(String k, String v) {
            headers.put(k, v);
            return this;
        }

        public Resp delay(long ms) {
            this.delayMs = ms;
            return this;
        }

        public static Resp drop() {
            Resp r = new Resp(0, "");
            r.drop = true;
            return r;
        }
    }

    private final HttpServer server;
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "stub-server");
        t.setDaemon(true);
        return t;
    });
    private final Deque<Resp> script = new ArrayDeque<>();
    private final List<Req> requests = Collections.synchronizedList(new ArrayList<>());
    private volatile Resp fallback = new Resp(200, "{}");

    public StubServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/", this::handle);
        server.setExecutor(pool);
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public synchronized StubServer enqueue(Resp r) {
        script.add(r);
        return this;
    }

    public StubServer enqueue(int status, String body) {
        return enqueue(new Resp(status, body));
    }

    public StubServer always(Resp r) {
        fallback = r;
        return this;
    }

    public List<Req> requests() {
        synchronized (requests) {
            return new ArrayList<>(requests);
        }
    }

    private synchronized Resp next() {
        Resp r = script.poll();
        return r != null ? r : fallback;
    }

    private void handle(HttpExchange ex) throws IOException {
        byte[] body;
        try (InputStream in = ex.getRequestBody()) {
            body = in.readAllBytes();
        }
        Map<String, String> headers = new LinkedHashMap<>();
        ex.getRequestHeaders().forEach((k, v) -> headers.put(k, String.join(",", v)));
        requests.add(new Req(ex.getRequestMethod(), ex.getRequestURI().getPath(), ex.getRequestURI().getRawPath(), headers, body));
        Resp r = next();
        if (r.delayMs > 0) {
            try {
                Thread.sleep(r.delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (r.drop) {
            ex.getHttpContext().getServer();
            ex.close();
            return;
        }
        byte[] out = r.body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        r.headers.forEach((k, v) -> ex.getResponseHeaders().add(k, v));
        try {
            ex.sendResponseHeaders(r.status, out.length == 0 ? -1 : out.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(out);
            }
        } catch (IOException ignored) {
            // client went away, for example after a read timeout
        } finally {
            ex.close();
        }
    }

    @Override
    public void close() {
        server.stop(0);
        pool.shutdownNow();
    }
}
