package com.shengzhiai.yugu.stcompat.testing;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * The Node mock platform of tools/mock-server, started on a free port:
 * {@code node server.mjs --port 0} prints {@code {"port":N}}. Credentials mock-app-key / mock-secret-key.
 */
public final class MockServer implements AutoCloseable {
    public static final String APP_KEY = "mock-app-key";
    public static final String SECRET = "mock-secret-key";

    private final Process process;
    public final int port;

    private MockServer(Process process, int port) {
        this.process = process;
        this.port = port;
    }

    public static MockServer start() throws IOException {
        File server = new File(Fixtures.repoRoot(), "tools/mock-server/server.mjs");
        if (!server.isFile()) {
            throw new IOException("mock server not found: " + server);
        }
        String node = System.getProperty("yugu.node", "node");
        ProcessBuilder pb = new ProcessBuilder(node, server.getAbsolutePath(), "--port", "0", "--processing-ms", "20");
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        final Process p = pb.start();
        // never leave a node process behind, even when the test JVM is torn down abruptly
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                p.destroyForcibly();
            }
        }));
        BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
        String line = r.readLine();
        if (line == null) {
            p.destroy();
            throw new IOException("mock server did not start (is node installed and tools/mock-server/node_modules present?)");
        }
        try {
            int port = new JSONObject(line.trim()).getInt("port");
            return new MockServer(p, port);
        } catch (Exception e) {
            p.destroy();
            throw new IOException("unexpected mock server output: " + line, e);
        }
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    public void reset() throws IOException {
        request("POST", "/__mock/reset", "{}");
    }

    /** Queues faults, for example {@code fault("/sent.eval.cn", "status:500")}. */
    public void fault(String match, String fault) throws IOException {
        request("POST", "/__mock/faults", "{\"faults\":[{\"match\":\"" + match + "\",\"fault\":\"" + fault + "\"}]}");
    }

    public JSONArray log() throws Exception {
        return new JSONArray(request("GET", "/__mock/log", null));
    }

    public JSONObject billing() throws Exception {
        return new JSONObject(request("GET", "/__mock/billing", null));
    }

    public String request(String method, String path, String body) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(baseUrl() + path).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(5000);
        c.setReadTimeout(10000);
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream os = c.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        if (in != null) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            in.close();
        }
        c.disconnect();
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
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
