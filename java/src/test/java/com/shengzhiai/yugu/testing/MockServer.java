package com.shengzhiai.yugu.testing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * The Node mock platform (tools/mock-server/server.mjs), started once per test JVM with
 * {@code node server.mjs --port 0} and stopped when the test plan ends.
 */
public final class MockServer {
    public static final String APP_KEY = "mock-app-key";
    public static final String SECRET = "mock-secret-key";
    public static final String TOKEN = "mock-jwt-token";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static MockServer instance;

    private final Process process;
    private final int port;
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    private MockServer(Process process, int port) {
        this.process = process;
        this.port = port;
    }

    public static synchronized MockServer get() {
        if (instance == null) {
            instance = start();
            MockServer s = instance;
            Runtime.getRuntime().addShutdownHook(new Thread(s::stop, "mock-server-stop"));
        }
        return instance;
    }

    private static MockServer start() {
        String script = System.getProperty("yugu.mock.server");
        Path p = script != null ? Paths.get(script) : Paths.get("..", "tools", "mock-server", "server.mjs");
        ProcessBuilder pb = new ProcessBuilder("node", p.toAbsolutePath().normalize().toString(), "--port", "0",
                "--processing-ms", "50");
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        Process proc;
        try {
            proc = pb.start();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot start the mock server, is node on PATH? " + p, e);
        }
        BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8));
        String line;
        try {
            line = CompletableFuture.supplyAsync(() -> {
                try {
                    return r.readLine();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }).get(20, TimeUnit.SECONDS);
        } catch (Exception e) {
            proc.destroyForcibly();
            throw new IllegalStateException("mock server did not print its port", e);
        }
        if (line == null) {
            proc.destroyForcibly();
            throw new IllegalStateException("mock server exited before printing its port");
        }
        int port;
        try {
            port = JSON.readTree(line).get("port").asInt();
        } catch (IOException e) {
            proc.destroyForcibly();
            throw new IllegalStateException("unexpected first line from the mock server: " + line, e);
        }
        Thread drain = new Thread(() -> {
            try {
                while (r.readLine() != null) {
                    // discard
                }
            } catch (IOException ignored) {
                // process ended
            }
        }, "mock-server-stdout");
        drain.setDaemon(true);
        drain.start();
        return new MockServer(proc, port);
    }

    public int port() {
        return port;
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    public String wsBaseUrl() {
        return "ws://127.0.0.1:" + port;
    }

    public boolean isAlive() {
        return process.isAlive();
    }

    public void stop() {
        if (process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }
    }

    public void reset() {
        post("/__mock/reset", "{}");
    }

    /** Queues faults matched by path prefix, one consumed per matching request or WS connection. */
    public void faults(String match, String... faults) {
        ObjectNode body = JSON.createObjectNode();
        ArrayNode arr = body.putArray("faults");
        for (String f : faults) {
            ObjectNode o = arr.addObject();
            o.put("match", match);
            o.put("fault", f);
        }
        post("/__mock/faults", body.toString());
    }

    public JsonNode log() {
        return get("/__mock/log");
    }

    public JsonNode billing() {
        return get("/__mock/billing");
    }

    private JsonNode get(String path) {
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(baseUrl() + path))
                    .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
            return JSON.readTree(r.body());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private void post(String path, String json) {
        try {
            http.send(HttpRequest.newBuilder(URI.create(baseUrl() + path)).timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
