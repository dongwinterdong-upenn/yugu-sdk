package com.shengzhiai.yugu.stcompat.internal;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The REST transport against a minimal HTTP server on a raw socket: gzip answers are decompressed
 * by the SDK, so the bytes on the wire and after decompression are both known (SDK 2.1.1).
 */
public class UrlConnectionTransportTest {
    /** Answers one request per connection with the queued raw response. */
    static final class Server implements AutoCloseable {
        final ServerSocket socket;
        final LinkedBlockingQueue<byte[]> answers = new LinkedBlockingQueue<>();
        final LinkedBlockingQueue<Map<String, String>> requestHeaders = new LinkedBlockingQueue<>();
        final LinkedBlockingQueue<byte[]> requestBodies = new LinkedBlockingQueue<>();

        Server() throws IOException {
            socket = new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"));
            Thread t = new Thread(() -> {
                while (!socket.isClosed()) {
                    try (Socket s = socket.accept()) {
                        s.setSoTimeout(10000);
                        serve(s);
                    } catch (IOException e) {
                        return;
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }, "test-http");
            t.setDaemon(true);
            t.start();
        }

        private void serve(Socket s) throws IOException, InterruptedException {
            InputStream in = new BufferedInputStream(s.getInputStream());
            Map<String, String> headers = new LinkedHashMap<>();
            String line = readLine(in);
            while (!(line = readLine(in)).isEmpty()) {
                int c = line.indexOf(':');
                headers.put(line.substring(0, c).trim().toLowerCase(Locale.ROOT), line.substring(c + 1).trim());
            }
            int length = headers.containsKey("content-length") ? Integer.parseInt(headers.get("content-length")) : 0;
            byte[] body = new byte[length];
            int n = 0;
            while (n < length) {
                int r = in.read(body, n, length - n);
                if (r < 0) {
                    break;
                }
                n += r;
            }
            requestHeaders.add(headers);
            requestBodies.add(body);
            byte[] answer = answers.poll(10, TimeUnit.SECONDS);
            OutputStream out = s.getOutputStream();
            out.write(answer);
            out.flush();
        }

        private static String readLine(InputStream in) throws IOException {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            int c;
            while ((c = in.read()) >= 0 && c != '\n') {
                if (c != '\r') {
                    b.write(c);
                }
            }
            return new String(b.toByteArray(), StandardCharsets.US_ASCII);
        }

        String url() {
            return "http://127.0.0.1:" + socket.getLocalPort() + "/sent.eval.cn";
        }

        void answer(int status, String extraHeaders, byte[] body) {
            ByteArrayOutputStream r = new ByteArrayOutputStream();
            String head = "HTTP/1.1 " + status + " X\r\nContent-Type: application/json\r\n" + extraHeaders
                    + "Content-Length: " + body.length + "\r\nConnection: close\r\n\r\n";
            r.write(head.getBytes(StandardCharsets.US_ASCII), 0, head.length());
            r.write(body, 0, body.length);
            answers.add(r.toByteArray());
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    private Server server;

    @Before
    public void start() throws IOException {
        server = new Server();
    }

    @After
    public void stop() throws IOException {
        server.close();
    }

    static byte[] gzip(byte[] data) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (GZIPOutputStream g = new GZIPOutputStream(b)) {
            g.write(data);
        }
        return b.toByteArray();
    }

    private HttpTransport.Request request(Map<String, String> headers) {
        Multipart mp = new Multipart("b0undary").addText("refText", "今天天气很好").addBytes("audio", "a.wav", "audio/wav",
                new byte[3000]);
        return new HttpTransport.Request("POST", server.url(), headers, mp, 5000, 5000);
    }

    @Test
    public void gzipAnswersAreDecompressedAndMeasured() throws Exception {
        String json = WsClientTest.finalLike(200 * 1024);
        byte[] raw = json.getBytes(StandardCharsets.UTF_8);
        byte[] packed = gzip(raw);
        server.answer(200, "Content-Encoding: gzip\r\nServer-Timing: total;dur=5\r\nServer-Timing: engine;dur=3, platform;dur=2\r\n",
                packed);
        Map<String, String> h = new LinkedHashMap<>();
        h.put("X-App-Key", "app");
        HttpTransport.Request req = request(h);
        HttpTransport.Response r = new UrlConnectionTransport().execute(req, new CancelToken());
        assertEquals(200, r.status);
        assertEquals(json, r.body);
        assertEquals("gzip", server.requestHeaders.poll(5, TimeUnit.SECONDS).get("accept-encoding"));
        assertEquals(req.body.contentLength(), server.requestBodies.poll(5, TimeUnit.SECONDS).length);
        HttpTransport.Metrics m = r.metrics;
        assertNotNull(m);
        assertEquals("gzip", m.contentEncoding);
        assertEquals(packed.length, m.wireBytes);
        assertEquals(raw.length, m.bodyBytes);
        assertEquals(req.body.contentLength(), m.uploadBytes);
        assertTrue(m.startNanos > 0 && m.uploadDoneNanos >= m.startNanos && m.headersNanos >= m.uploadDoneNanos
                && m.bodyDoneNanos >= m.headersNanos);
        // several header lines form one list (the JDK lists them in reverse order, the names decide)
        String joined = r.header("Server-Timing");
        assertTrue(joined, joined.contains("total;dur=5") && joined.contains("engine;dur=3, platform;dur=2"));
        ServerTiming st = ServerTiming.fromHeader(joined);
        assertEquals(5, st.totalMs);
        assertEquals(3, st.engineMs);
        assertEquals(2, st.platformMs);
    }

    @Test
    public void identityErrorAndEmptyGzipAnswers() throws Exception {
        byte[] plain = "{\"recordId\":\"x\",\"result\":{}}".getBytes(StandardCharsets.UTF_8);
        server.answer(200, "", plain);
        HttpTransport.Response r = new UrlConnectionTransport().execute(request(new LinkedHashMap<String, String>()), null);
        assertEquals(new String(plain, StandardCharsets.UTF_8), r.body);
        assertNull(r.metrics.contentEncoding);
        assertEquals(plain.length, r.metrics.wireBytes);
        assertEquals(plain.length, r.metrics.bodyBytes);

        byte[] error = "{\"code\":40001,\"message\":\"参数错误\"}".getBytes(StandardCharsets.UTF_8);
        server.answer(400, "Content-Encoding: gzip\r\n", gzip(error));
        HttpTransport.Response e = new UrlConnectionTransport().execute(request(new LinkedHashMap<String, String>()), null);
        assertEquals(400, e.status);
        assertEquals(new String(error, StandardCharsets.UTF_8), e.body);
        assertEquals(40001, ErrorMapper.fromResponse(e).code);

        server.answer(200, "Content-Encoding: gzip\r\n", new byte[0]);
        HttpTransport.Response empty = new UrlConnectionTransport().execute(request(new LinkedHashMap<String, String>()), null);
        assertEquals("", empty.body);
        assertEquals(0, empty.metrics.wireBytes);

        // a caller's own Accept-Encoding is kept
        server.answer(200, "", plain);
        Map<String, String> own = new LinkedHashMap<>();
        own.put("Accept-Encoding", "identity");
        new UrlConnectionTransport().execute(request(own), null);
        for (int i = 0; i < 3; i++) {
            server.requestHeaders.poll(5, TimeUnit.SECONDS);
        }
        assertEquals("identity", server.requestHeaders.poll(5, TimeUnit.SECONDS).get("accept-encoding"));
    }

    @Test
    public void decompressionStopsAtTheBodyCap() throws Exception {
        byte[] packed = gzip(new byte[UrlConnectionTransport.MAX_BODY + 1024 * 1024]);
        assertTrue(packed.length < 64 * 1024);
        server.answer(200, "Content-Encoding: gzip\r\n", packed);
        HttpTransport.Response r = new UrlConnectionTransport().execute(request(new LinkedHashMap<String, String>()), null);
        assertEquals(UrlConnectionTransport.MAX_BODY, r.metrics.bodyBytes);
        assertTrue(r.metrics.wireBytes <= packed.length);
    }
}
