package com.shengzhiai.yugu.stcompat.internal;

import com.shengzhiai.yugu.stcompat.testing.TestWsServer;

import org.junit.After;
import org.junit.Test;

import java.io.EOFException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** RFC 6455 client behaviour against an in-process server that is written independently of it. */
public class WsClientTest {
    private final List<AutoCloseable> cleanup = new ArrayList<>();

    @After
    public void tearDown() throws Exception {
        for (AutoCloseable c : cleanup) {
            c.close();
        }
    }

    /** Records the callbacks of one connection. */
    static final class Recorder implements WsConnection.Listener {
        final LinkedBlockingQueue<String> texts = new LinkedBlockingQueue<>();
        final LinkedBlockingQueue<byte[]> binaries = new LinkedBlockingQueue<>();
        final CountDownLatch ended = new CountDownLatch(1);
        final AtomicInteger terminal = new AtomicInteger();
        volatile int closeCode = -1;
        volatile String closeReason;
        volatile IOException failure;

        @Override
        public void onText(String text) {
            texts.add(text);
        }

        @Override
        public void onBinary(byte[] data) {
            binaries.add(data);
        }

        @Override
        public void onClosed(int code, String reason) {
            closeCode = code;
            closeReason = reason;
            terminal.incrementAndGet();
            ended.countDown();
        }

        @Override
        public void onFailure(IOException error) {
            failure = error;
            terminal.incrementAndGet();
            ended.countDown();
        }

        String nextText() throws InterruptedException {
            String t = texts.poll(5, TimeUnit.SECONDS);
            assertNotNull("no text message", t);
            return t;
        }

        void awaitEnd() throws InterruptedException {
            assertTrue("connection did not end", ended.await(5, TimeUnit.SECONDS));
        }

        int failureCode() {
            assertTrue("expected a protocol error, got " + failure, failure instanceof WsClient.ProtocolError);
            return ((WsClient.ProtocolError) failure).closeCode;
        }
    }

    private TestWsServer server() throws IOException {
        TestWsServer s = new TestWsServer();
        cleanup.add(s);
        return s;
    }

    private static WsClient.Options options(TestWsServer s, String path) {
        WsClient.Options o = new WsClient.Options();
        o.url = "ws://127.0.0.1:" + s.port() + path;
        o.connectTimeoutMs = 5000;
        o.readTimeoutMs = 5000;
        return o;
    }

    private static FutureTask<WsClient> connectAsync(WsClient.Options o, WsConnection.Listener l) {
        FutureTask<WsClient> f = new FutureTask<>(() -> WsClient.connect(o, l));
        Thread t = new Thread(f, "test-ws-connect");
        t.setDaemon(true);
        t.start();
        return f;
    }

    /** Connected client and its server side. */
    private static final class Link {
        WsClient client;
        TestWsServer.Peer peer;
    }

    private Link open(TestWsServer s, Recorder r) throws Exception {
        FutureTask<WsClient> f = connectAsync(options(s, "/sent.eval.cn"), r);
        Link l = new Link();
        l.peer = s.next().handshake();
        l.client = f.get(5, TimeUnit.SECONDS);
        WsClient c = l.client;
        cleanup.add(c::abort);
        return l;
    }

    private static IOException connectFailure(FutureTask<WsClient> f) throws Exception {
        try {
            f.get(5, TimeUnit.SECONDS);
            fail("connect should fail");
            return null;
        } catch (java.util.concurrent.ExecutionException e) {
            assertTrue(String.valueOf(e.getCause()), e.getCause() instanceof IOException);
            return (IOException) e.getCause();
        }
    }

    // ================================================================ handshake

    @Test
    public void acceptMatchesTheRfcExample() {
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", WsClient.acceptFor("dGhlIHNhbXBsZSBub25jZQ=="));
        assertEquals(TestWsServer.accept("x3JJHMbDL1EzLkh9GBhXDw=="), WsClient.acceptFor("x3JJHMbDL1EzLkh9GBhXDw=="));
    }

    @Test
    public void handshakeRequestAndFirstMessage() throws Exception {
        TestWsServer s = server();
        Recorder r = new Recorder();
        WsClient.Options o = options(s, "/sent.eval.cn?appKey=ak&signature=%2Fx%3D");
        o.headers.put("User-Agent", "yugu-test/1");
        FutureTask<WsClient> f = connectAsync(o, r);
        TestWsServer.Peer p = s.next().readRequest();
        assertEquals("GET /sent.eval.cn?appKey=ak&signature=%2Fx%3D HTTP/1.1", p.requestLine);
        assertEquals("127.0.0.1:" + s.port(), p.headers.get("host"));
        assertEquals("websocket", p.headers.get("upgrade"));
        assertEquals("Upgrade", p.headers.get("connection"));
        assertEquals("13", p.headers.get("sec-websocket-version"));
        assertEquals(16, Base64.getDecoder().decode(p.key()).length);
        assertEquals("yugu-test/1", p.headers.get("user-agent"));
        assertEquals("permessage-deflate; client_max_window_bits", p.headers.get("sec-websocket-extensions"));
        // the answer and the first frame arrive in one write
        byte[] answer = ("HTTP/1.1 101 Switching Protocols\r\nUpgrade: WebSocket\r\nConnection: keep-alive, Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + TestWsServer.accept(p.key()) + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
        byte[] first = TestWsServer.frame(0x81, 9, null, "connected".getBytes(StandardCharsets.UTF_8));
        byte[] both = Arrays.copyOf(answer, answer.length + first.length);
        System.arraycopy(first, 0, both, answer.length, first.length);
        p.write(both);
        WsClient c = f.get(5, TimeUnit.SECONDS);
        cleanup.add(c::abort);
        assertEquals("connected", r.nextText());
        assertEquals("no extension agreed", "", c.extensions());
        WsConnection.Inbound in = c.inbound();
        assertFalse(in.compressed);
        assertEquals(9, in.wireBytes);
        assertEquals(9, in.dataBytes);
        assertTrue(in.completeNanos >= in.firstByteNanos);
    }

    @Test
    public void withoutTheOfferNoExtensionIsRequested() throws Exception {
        TestWsServer s = server();
        WsClient.Options o = options(s, "/x");
        o.offerDeflate = false;
        FutureTask<WsClient> f = connectAsync(o, new Recorder());
        TestWsServer.Peer p = s.next().readRequest();
        assertFalse(p.headers.containsKey("sec-websocket-extensions"));
        p.accept101();
        cleanup.add(f.get(5, TimeUnit.SECONDS)::abort);
    }

    @Test
    public void hostHeaderAndTargetForms() throws Exception {
        WsClient.Target t = WsClient.Target.parse("wss://open.shengzhiai.com/sent.eval.cn?a=1");
        assertTrue(t.secure);
        assertEquals(443, t.port);
        assertEquals("open.shengzhiai.com", t.hostHeader);
        assertEquals("/sent.eval.cn?a=1", t.resource);
        WsClient.Target v6 = WsClient.Target.parse("ws://[::1]:8080");
        assertFalse(v6.secure);
        assertEquals("::1", v6.host);
        assertEquals("[::1]:8080", v6.hostHeader);
        assertEquals("/", v6.resource);
        assertEquals("example.com", WsClient.Target.parse("WS://example.com:80/x").hostHeader);
        for (String bad : new String[] {"http://example.com/x", "ws:///x", "ws://exa mple.com/x"}) {
            try {
                WsClient.Target.parse(bad);
                fail(bad);
            } catch (IOException expected) {
                assertFalse("the URL must not appear in errors", expected.getMessage().contains("/x"));
            }
        }
    }

    @Test(expected = IOException.class)
    public void headersWithLineBreaksAreRefused() throws Exception {
        java.util.Map<String, String> h = new java.util.LinkedHashMap<>();
        h.put("X-Evil", "a\r\nInjected: 1");
        WsClient.requestHead(WsClient.Target.parse("ws://example.com/"), "k", h);
    }

    @Test
    public void wrongAcceptFailsTheHandshake() throws Exception {
        TestWsServer s = server();
        FutureTask<WsClient> f = connectAsync(options(s, "/x"), new Recorder());
        TestWsServer.Peer p = s.next().readRequest();
        p.writeAscii("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + TestWsServer.accept("not-the-key") + "\r\n\r\n");
        IOException e = connectFailure(f);
        assertTrue(e instanceof WsClient.ProtocolError);
        assertTrue(e.getMessage().contains("Sec-WebSocket-Accept"));
        assertTrue("the client closes the socket", p.eof());
    }

    @Test
    public void refusedHandshakeCarriesStatusAndRetryAfter() throws Exception {
        TestWsServer s = server();
        FutureTask<WsClient> f = connectAsync(options(s, "/x"), new Recorder());
        s.next().readRequest().writeAscii("HTTP/1.1 503 Service Unavailable\r\nRetry-After: 2\r\nContent-Length: 0\r\n\r\n");
        IOException e = connectFailure(f);
        assertTrue(e instanceof WsClient.HandshakeException);
        assertEquals(503, ((WsClient.HandshakeException) e).status);
        assertEquals(2000, ((WsClient.HandshakeException) e).retryAfterMs);
        FutureTask<WsClient> f2 = connectAsync(options(s, "/x"), new Recorder());
        s.next().readRequest().writeAscii("HTTP/1.1 403 Forbidden\r\n\r\n");
        IOException e2 = connectFailure(f2);
        assertEquals(403, ((WsClient.HandshakeException) e2).status);
        assertEquals(-1, ((WsClient.HandshakeException) e2).retryAfterMs);
    }

    @Test
    public void invalidAnswersFailTheHandshake() throws Exception {
        String[] answers = {
            "SSH-2.0-OpenSSH\r\n\r\n",
            "HTTP/1.1 abc Weird\r\n\r\n",
            "HTTP/1.1 101 Switching Protocols\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: %s\r\n\r\n",
            "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nSec-WebSocket-Accept: %s\r\n\r\n",
            "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: %s\r\n"
                    + "Sec-WebSocket-Protocol: chat\r\n\r\n",
        };
        TestWsServer s = server();
        for (String a : answers) {
            FutureTask<WsClient> f = connectAsync(options(s, "/x"), new Recorder());
            TestWsServer.Peer p = s.next().readRequest();
            p.writeAscii(a.contains("%s") ? String.format(a, TestWsServer.accept(p.key())) : a);
            assertTrue(a, connectFailure(f) instanceof WsClient.ProtocolError);
        }
        // an extension the client did not offer still fails the handshake
        WsClient.Options o = options(s, "/x");
        o.offerDeflate = false;
        FutureTask<WsClient> f = connectAsync(o, new Recorder());
        TestWsServer.Peer p = s.next().readRequest();
        p.accept101("Sec-WebSocket-Extensions: permessage-deflate");
        IOException e = connectFailure(f);
        assertTrue(e instanceof WsClient.ProtocolError);
        assertTrue(e.getMessage().contains("not offered"));
        assertTrue("the client closes the socket", p.eof());
    }

    @Test
    public void answerTooLargeOrCutOff() throws Exception {
        TestWsServer s = server();
        FutureTask<WsClient> f = connectAsync(options(s, "/x"), new Recorder());
        TestWsServer.Peer p = s.next().readRequest();
        StringBuilder big = new StringBuilder("HTTP/1.1 101 Switching Protocols\r\n");
        while (big.length() < WsClient.MAX_HANDSHAKE_BYTES + 100) {
            big.append("X-Filler: 0123456789012345678901234567890123456789\r\n");
        }
        p.writeAscii(big.toString());
        assertTrue(connectFailure(f) instanceof WsClient.ProtocolError);
        FutureTask<WsClient> f2 = connectAsync(options(s, "/x"), new Recorder());
        TestWsServer.Peer p2 = s.next().readRequest();
        p2.writeAscii("HTTP/1.1 101 Switching");
        p2.close();
        assertTrue(connectFailure(f2) instanceof EOFException);
    }

    @Test
    public void handshakeAnswerTimesOutWithTheConnectTimeout() throws Exception {
        TestWsServer s = server();
        WsClient.Options o = options(s, "/x");
        o.connectTimeoutMs = 300;
        long t0 = System.nanoTime();
        FutureTask<WsClient> f = connectAsync(o, new Recorder());
        s.next().readRequest();
        assertTrue(connectFailure(f) instanceof SocketTimeoutException);
        assertTrue((System.nanoTime() - t0) / 1000000L < 4000);
    }

    // ================================================================ addresses

    @Test
    public void everyResolvedAddressIsTried() throws Exception {
        TestWsServer s = server();
        Recorder r = new Recorder();
        WsClient.Options o = options(s, "/x");
        o.url = "ws://yugu.test:" + s.port() + "/x";
        List<String> asked = new ArrayList<>();
        // nothing listens on 127.0.0.2 at this port: refused, then 127.0.0.1 is tried
        o.resolver = host -> {
            asked.add(host);
            return new InetAddress[] {InetAddress.getByName("127.0.0.2"), InetAddress.getByName("127.0.0.1")};
        };
        FutureTask<WsClient> f = connectAsync(o, r);
        TestWsServer.Peer p = s.next().readRequest();
        assertEquals("yugu.test:" + s.port(), p.headers.get("host"));
        p.accept101();
        WsClient c = f.get(5, TimeUnit.SECONDS);
        cleanup.add(c::abort);
        assertEquals(Arrays.asList("yugu.test"), asked);
    }

    @Test
    public void allAddressesFailing() throws Exception {
        int port;
        try (java.net.ServerSocket ss = new java.net.ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            port = ss.getLocalPort();
        }
        WsClient.Options o = new WsClient.Options();
        o.url = "ws://yugu.test:" + port + "/x";
        o.connectTimeoutMs = 2000;
        o.resolver = host -> new InetAddress[] {InetAddress.getByName("127.0.0.2"), InetAddress.getByName("127.0.0.1")};
        try {
            WsClient.connect(o, new Recorder());
            fail();
        } catch (ConnectException e) {
            assertEquals(1, e.getSuppressed().length);
        }
        o.resolver = host -> new InetAddress[0];
        try {
            WsClient.connect(o, new Recorder());
            fail();
        } catch (java.net.UnknownHostException expected) {
            assertEquals("yugu.test", expected.getMessage());
        }
    }

    // ================================================================ frames

    @Test
    public void clientFramesAreMaskedWithEveryLengthForm() throws Exception {
        TestWsServer s = server();
        Link l = open(s, new Recorder());
        l.client.sendText("你好");
        TestWsServer.Frame t = l.peer.readFrame();
        assertTrue(t.fin);
        assertTrue(t.masked);
        assertEquals(WsClient.OP_TEXT, t.opcode);
        assertEquals("你好", t.text());
        int[] sizes = {0, 1, 125, 126, 32000, 65535, 65536};
        byte[] lastMask = t.mask;
        int differentMasks = 0;
        for (int n : sizes) {
            byte[] data = new byte[n];
            for (int i = 0; i < n; i++) {
                data[i] = (byte) (i * 31 + 7);
            }
            l.client.sendBinary(data, 0, n);
            TestWsServer.Frame b = l.peer.readFrame();
            assertTrue(b.masked);
            assertEquals(0, b.rsv);
            assertEquals(WsClient.OP_BINARY, b.opcode);
            assertEquals("length form of " + n, n < 126 ? 0 : (n <= 0xFFFF ? 2 : 8), b.lengthBytes);
            assertArrayEquals(data, b.payload);
            if (!Arrays.equals(lastMask, b.mask)) {
                differentMasks++;
            }
            lastMask = b.mask;
        }
        assertTrue("a fresh mask per frame", differentMasks >= sizes.length - 1);
        byte[] slice = "xxABCyy".getBytes(StandardCharsets.US_ASCII);
        l.client.sendBinary(slice, 2, 3);
        assertEquals("ABC", l.peer.readFrame().text());
        l.client.ping();
        TestWsServer.Frame ping = l.peer.readFrame();
        assertEquals(WsClient.OP_PING, ping.opcode);
        assertEquals(0, ping.payload.length);
        try {
            l.client.sendBinary(slice, 5, 3);
            fail();
        } catch (IllegalArgumentException expected) {
            // bad range
        }
    }

    @Test
    public void fragmentedMessageWithInterleavedPing() throws Exception {
        TestWsServer s = server();
        Recorder r = new Recorder();
        Link l = open(s, r);
        byte[] all = "我是中国人".getBytes(StandardCharsets.UTF_8);
        // the first fragment ends inside the UTF-8 sequence of 是
        l.peer.send(false, WsClient.OP_TEXT, Arrays.copyOfRange(all, 0, 4));
        l.peer.send(true, WsClient.OP_PING, "p1".getBytes(StandardCharsets.US_ASCII));
        l.peer.send(false, WsClient.OP_CONTINUATION, Arrays.copyOfRange(all, 4, 10));
        l.peer.send(true, WsClient.OP_CONTINUATION, Arrays.copyOfRange(all, 10, all.length));
        TestWsServer.Frame pong = l.peer.readFrame();
        assertEquals(WsClient.OP_PONG, pong.opcode);
        assertTrue(pong.masked);
        assertEquals("p1", pong.text());
        assertEquals("我是中国人", r.nextText());
        l.peer.send(false, WsClient.OP_BINARY, new byte[] {1, 2});
        l.peer.send(true, WsClient.OP_CONTINUATION, new byte[] {3});
        assertArrayEquals(new byte[] {1, 2, 3}, r.binaries.poll(5, TimeUnit.SECONDS));
        l.peer.send(true, WsClient.OP_PONG, new byte[0]);
        l.peer.text("after pong");
        assertEquals("after pong", r.nextText());
        assertEquals(0, r.terminal.get());
    }

    @Test
    public void serverCloseIsAnsweredAndReported() throws Exception {
        TestWsServer s = server();
        Recorder r = new Recorder();
        Link l = open(s, r);
        l.peer.close(1001, "going away 再见");
        TestWsServer.Frame echo = l.peer.readFrame();
        assertEquals(WsClient.OP_CLOSE, echo.opcode);
        assertTrue(echo.masked);
        assertEquals(1001, echo.closeCode());
        assertTrue(l.peer.eof());
        r.awaitEnd();
        assertEquals(1001, r.closeCode);
        assertEquals("going away 再见", r.closeReason);
        assertEquals(1, r.terminal.get());
        try {
            l.client.sendText("late");
            fail("sending after the close handshake");
        } catch (IOException expected) {
            // closing
        }
    }

    @Test
    public void emptyServerCloseIsAnsweredEmpty() throws Exception {
        TestWsServer s = server();
        Recorder r = new Recorder();
        Link l = open(s, r);
        l.peer.send(true, WsClient.OP_CLOSE, new byte[0]);
        TestWsServer.Frame echo = l.peer.readFrame();
        assertEquals(WsClient.OP_CLOSE, echo.opcode);
        assertEquals(0, echo.payload.length);
        r.awaitEnd();
        assertEquals(1005, r.closeCode);
        assertEquals("", r.closeReason);
    }

    @Test
    public void clientCloseHandshakeIsSilent() throws Exception {
        TestWsServer s = server();
        Recorder r = new Recorder();
        Link l = open(s, r);
        Thread answer = new Thread(() -> {
            try {
                TestWsServer.Frame f = l.peer.readFrame();
                assertEquals(WsClient.OP_CLOSE, f.opcode);
                assertEquals(1000, f.closeCode());
                assertEquals("done", f.closeReason());
                l.peer.close(1000, "");
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        });
        answer.start();
        long t0 = System.nanoTime();
        l.client.close(1000, "done");
        long ms = (System.nanoTime() - t0) / 1000000L;
        answer.join(5000);
        assertTrue("close returns when the server answered, took " + ms + " ms", ms < WsClient.CLOSE_WAIT_MS);
        assertTrue(l.client.isReaderDone());
        Thread.sleep(50);
        assertEquals("no callbacks after close", 0, r.terminal.get());
        l.client.close(1000, "again");
        l.client.abort();
    }

    @Test
    public void clientCloseWithoutAnswerWaitsBoundedly() throws Exception {
        TestWsServer s = server();
        Link l = open(s, new Recorder());
        long t0 = System.nanoTime();
        l.client.close(1000, null);
        long ms = (System.nanoTime() - t0) / 1000000L;
        assertTrue(ms >= WsClient.CLOSE_WAIT_MS - 50 && ms < WsClient.CLOSE_WAIT_MS + 2000);
        TestWsServer.Frame f = l.peer.readFrame();
        assertEquals(1000, f.closeCode());
        assertEquals("", f.closeReason());
        assertTrue(l.peer.eof());
    }

    @Test
    public void abortIsSilent() throws Exception {
        TestWsServer s = server();
        Recorder r = new Recorder();
        Link l = open(s, r);
        l.client.abort();
        assertTrue(l.peer.eof());
        Thread.sleep(100);
        assertEquals(0, r.terminal.get());
        assertTrue(r.texts.isEmpty());
    }

    // ================================================================ limits and violations

    @Test
    public void oversizeFrameClosesWith1009() throws Exception {
        TestWsServer s = server();
        Recorder r = new Recorder();
        Link l = open(s, r);
        // only the header: the client must refuse before reading or allocating the payload
        l.peer.write(TestWsServer.frame(0x81, WsClient.DEFAULT_MAX_MESSAGE_BYTES + 1, null, null));
        TestWsServer.Frame close = l.peer.readFrame();
        assertEquals(WsClient.OP_CLOSE, close.opcode);
        assertEquals(1009, close.closeCode());
        r.awaitEnd();
        assertEquals(1009, r.failureCode());
        assertEquals(1, r.terminal.get());
    }

    @Test
    public void framesUpToTheCapPassAndTheCapCanBeRaised() throws Exception {
        TestWsServer s = server();
        Recorder r = new Recorder();
        Link l = open(s, r);
        char[] big = new char[WsClient.DEFAULT_MAX_MESSAGE_BYTES];
        Arrays.fill(big, 'a');
        l.peer.text(new String(big));
        assertEquals(WsClient.DEFAULT_MAX_MESSAGE_BYTES, r.nextText().length());
        l.client.setMaxMessageBytes(RealtimeRound.FINAL_MAX_MESSAGE_BYTES);
        char[] bigger = new char[300 * 1024];
        Arrays.fill(bigger, 'b');
        l.peer.text(new String(bigger));
        assertEquals(bigger.length, r.nextText().length());
        l.client.setMaxMessageBytes(0);
        l.peer.write(TestWsServer.frame(0x82, WsClient.DEFAULT_MAX_MESSAGE_BYTES + 1, null, null));
        r.awaitEnd();
        assertEquals(1009, r.failureCode());
    }

    @Test
    public void oversizeFragmentedMessageClosesWith1009() throws Exception {
        TestWsServer s = server();
        Recorder r = new Recorder();
        Link l = open(s, r);
        l.client.setMaxMessageBytes(1000);
        l.peer.send(false, WsClient.OP_TEXT, new byte[600]);
        l.peer.send(false, WsClient.OP_CONTINUATION, new byte[401]);
        assertEquals(1009, l.peer.readFrame().closeCode());
        r.awaitEnd();
        assertEquals(1009, r.failureCode());
    }

    @Test
    public void protocolViolationsCloseWith1002Or1007() throws Exception {
        byte[] mask = {1, 2, 3, 4};
        byte[][] violations = {
            TestWsServer.frame(0x81, 2, mask, "hi".getBytes(StandardCharsets.US_ASCII)),          // masked server frame
            TestWsServer.frame(0xC1, 2, null, "hi".getBytes(StandardCharsets.US_ASCII)),          // RSV1 without extension
            TestWsServer.frame(0x83, 0, null, new byte[0]),                                       // reserved data opcode
            TestWsServer.frame(0x8B, 0, null, new byte[0]),                                       // reserved control opcode
            TestWsServer.frame(0x09, 0, null, new byte[0]),                                       // fragmented ping
            TestWsServer.frame(0x89, 126, null, new byte[126]),                                   // ping longer than 125
            TestWsServer.frame(0x80, 1, null, new byte[] {'x'}),                                  // continuation without start
            TestWsServer.frame(0x88, 1, null, new byte[] {3}),                                    // 1 byte close payload
            TestWsServer.frame(0x88, 2, null, new byte[] {0x03, (byte) 0xEC}),                    // close code 1004
            TestWsServer.frame(0x88, 2, null, new byte[] {0x03, (byte) 0xED}),                    // close code 1005
            TestWsServer.frame(0x81, 2, null, new byte[] {(byte) 0xC3, 0x28}),                    // invalid UTF-8
            TestWsServer.frame(0x88, 4, null, new byte[] {0x03, (byte) 0xE8, (byte) 0xFF, 0x00}), // invalid UTF-8 reason
            TestWsServer.frame(0x82, 0x7FFFFFFFFFFFFFFFL, null, null),                            // absurd length
        };
        int[] codes = {1002, 1002, 1002, 1002, 1002, 1002, 1002, 1002, 1002, 1002, 1007, 1007, 1009};
        TestWsServer s = server();
        for (int i = 0; i < violations.length; i++) {
            Recorder r = new Recorder();
            Link l = open(s, r);
            l.peer.write(violations[i]);
            TestWsServer.Frame close = l.peer.readFrame();
            assertEquals("violation " + i, WsClient.OP_CLOSE, close.opcode);
            assertEquals("violation " + i, codes[i], close.closeCode());
            r.awaitEnd();
            assertEquals("violation " + i, codes[i], r.failureCode());
        }
        // a negative 64 bit length
        Recorder r = new Recorder();
        Link l = open(s, r);
        byte[] f = {(byte) 0x82, 127, (byte) 0x80, 0, 0, 0, 0, 0, 0, 0};
        l.peer.write(f);
        r.awaitEnd();
        assertEquals(1002, r.failureCode());
    }

    @Test
    public void newMessageInsideAFragmentedOneIs1002() throws Exception {
        TestWsServer s = server();
        Recorder r = new Recorder();
        Link l = open(s, r);
        l.peer.send(false, WsClient.OP_TEXT, "a".getBytes(StandardCharsets.US_ASCII));
        l.peer.send(true, WsClient.OP_TEXT, "b".getBytes(StandardCharsets.US_ASCII));
        assertEquals(1002, l.peer.readFrame().closeCode());
        r.awaitEnd();
        assertEquals(1002, r.failureCode());
    }

    @Test
    public void readTimeoutAndEofAreFailures() throws Exception {
        TestWsServer s = server();
        Recorder r = new Recorder();
        WsClient.Options o = options(s, "/x");
        o.readTimeoutMs = 300;
        FutureTask<WsClient> f = connectAsync(o, r);
        s.next().handshake();
        cleanup.add(f.get(5, TimeUnit.SECONDS)::abort);
        r.awaitEnd();
        assertTrue(String.valueOf(r.failure), r.failure instanceof SocketTimeoutException);

        Recorder r2 = new Recorder();
        Link l = open(s, r2);
        l.peer.write(new byte[] {(byte) 0x81, 10, 'a'});
        l.peer.close();
        r2.awaitEnd();
        assertTrue(String.valueOf(r2.failure), r2.failure instanceof EOFException);

        Recorder r3 = new Recorder();
        Link l3 = open(s, r3);
        l3.peer.close();
        r3.awaitEnd();
        assertTrue(r3.failure instanceof EOFException);
    }

    @Test
    public void listenerExceptionsDoNotKillTheConnection() throws Exception {
        TestWsServer s = server();
        final List<String> got = new ArrayList<>();
        Recorder end = new Recorder();
        WsConnection.Listener l = new WsConnection.Listener() {
            @Override
            public void onText(String text) {
                got.add(text);
                if ("boom".equals(text)) {
                    throw new IllegalStateException("app bug");
                }
            }

            @Override
            public void onBinary(byte[] data) {
                throw new IllegalStateException("app bug");
            }

            @Override
            public void onClosed(int code, String reason) {
                end.onClosed(code, reason);
                throw new IllegalStateException("app bug");
            }

            @Override
            public void onFailure(IOException error) {
                end.onFailure(error);
            }
        };
        FutureTask<WsClient> f = connectAsync(options(s, "/x"), l);
        TestWsServer.Peer p = s.next().handshake();
        cleanup.add(f.get(5, TimeUnit.SECONDS)::abort);
        p.text("boom");
        p.send(true, WsClient.OP_BINARY, new byte[] {1});
        p.text("still here");
        p.close(1000, "");
        end.awaitEnd();
        assertEquals(Arrays.asList("boom", "still here"), got);
        assertEquals(1000, end.closeCode);
    }

    // ================================================================ permessage-deflate (RFC 7692)

    /** Connects with the deflate offer and answers it with {@code answer}. */
    private Link openDeflate(TestWsServer s, WsConnection.Listener r, String answer) throws Exception {
        FutureTask<WsClient> f = connectAsync(options(s, "/sent.eval.cn"), r);
        Link l = new Link();
        l.peer = s.next().readRequest();
        assertEquals("permessage-deflate; client_max_window_bits", l.peer.headers.get("sec-websocket-extensions"));
        l.peer.accept101("Sec-WebSocket-Extensions: " + answer);
        l.client = f.get(5, TimeUnit.SECONDS);
        cleanup.add(l.client::abort);
        return l;
    }

    /** JSON shaped like a final frame: word items with scores, roughly {@code bytes} long. */
    static String finalLike(int bytes) {
        StringBuilder sb = new StringBuilder(bytes + 200).append("{\"recordId\":\"eval_1\",\"eof\":1,\"result\":{\"words\":[");
        String chars = "我是中国人们都华民族一家今天气很好";
        for (int i = 0; sb.length() < bytes; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"word\":\"").append(chars.charAt(i % chars.length())).append("\",\"scores\":{\"overall\":")
                    .append(60 + (i * 7) % 40).append(",\"pronunciation\":").append(55 + (i * 13) % 45)
                    .append(",\"tone\":").append(i % 3 == 0 ? 100 : 80).append("},\"charType\":0}");
        }
        return sb.append("]}}").toString();
    }

    /** JSON with random scores: compresses only moderately on its own. */
    static String randomJson(int bytes) {
        java.util.Random rnd = new java.util.Random(11);
        StringBuilder sb = new StringBuilder(bytes + 100).append("{\"scores\":[");
        for (int i = 0; sb.length() < bytes; i++) {
            sb.append(i > 0 ? "," : "").append(rnd.nextInt(1000000));
        }
        return sb.append("]}").toString();
    }

    private static String repeat(char c, int n) {
        char[] a = new char[n];
        Arrays.fill(a, c);
        return new String(a);
    }

    @Test
    public void compressedMessagesWithContextTakeover() throws Exception {
        TestWsServer s = server();
        Recorder r = new Recorder();
        // the answer of the platform's Tomcat to the offer
        Link l = openDeflate(s, r, "permessage-deflate;client_max_window_bits=15");
        assertEquals("permessage-deflate; client_max_window_bits=15", l.client.extensions());
        l.client.setMaxMessageBytes(RealtimeRound.FINAL_MAX_MESSAGE_BYTES);
        TestWsServer.Compressor c = new TestWsServer.Compressor(true);
        // a final frame of a long paragraph: 1.5 MB of JSON
        String big = finalLike(1500 * 1024);
        int wire1 = l.peer.compressedText(c, big);
        assertEquals(big, r.nextText());
        WsConnection.Inbound in1 = l.client.inbound();
        assertTrue(in1.compressed);
        assertEquals(wire1, in1.wireBytes);
        assertEquals(big.getBytes(StandardCharsets.UTF_8).length, in1.dataBytes);
        assertTrue("1.5 MB compresses well: " + wire1, wire1 * 8 < in1.dataBytes);
        assertTrue(in1.completeNanos >= in1.firstByteNanos);
        // the same message again is a back reference into the window the client kept
        String small = randomJson(3000);
        int first = l.peer.compressedText(c, small);
        assertEquals(small, r.nextText());
        int again = l.peer.compressedText(c, small);
        assertEquals(small, r.nextText());
        assertTrue("context takeover: " + again + " bytes after " + first, again * 10 < first);
        assertEquals(again, l.client.inbound().wireBytes);
        // uncompressed messages may come between compressed ones
        l.peer.text("plain");
        assertEquals("plain", r.nextText());
        assertFalse(l.client.inbound().compressed);
        // fragmented: the compression bit on the first frame only, a ping in between
        String zh = "我是中国人，我是中国人。我们都是中国人。中华民族是一家人。";
        byte[] p = c.compress(zh);
        l.peer.sendCompressed(false, WsClient.OP_TEXT, Arrays.copyOfRange(p, 0, 3));
        l.peer.send(true, WsClient.OP_PING, "p".getBytes(StandardCharsets.US_ASCII));
        l.peer.send(false, WsClient.OP_CONTINUATION, Arrays.copyOfRange(p, 3, 7));
        l.peer.send(true, WsClient.OP_CONTINUATION, Arrays.copyOfRange(p, 7, p.length));
        assertEquals(WsClient.OP_PONG, l.peer.readFrame().opcode);
        assertEquals(zh, r.nextText());
        assertEquals(p.length, l.client.inbound().wireBytes);
        byte[] bin = new byte[5000];
        for (int i = 0; i < bin.length; i++) {
            bin[i] = (byte) (i % 7);
        }
        l.peer.sendCompressed(true, WsClient.OP_BINARY, c.compress(bin));
        assertArrayEquals(bin, r.binaries.poll(5, TimeUnit.SECONDS));
        // client frames stay uncompressed
        l.client.sendText("{\"cmd\":\"end\"}");
        TestWsServer.Frame out = l.peer.readFrame();
        assertEquals(0, out.rsv);
        assertEquals("{\"cmd\":\"end\"}", out.text());
        assertEquals(0, r.terminal.get());
        c.end();
    }

    @Test
    public void serverNoContextTakeoverStartsEveryMessageWithAnEmptyWindow() throws Exception {
        TestWsServer s = server();
        Recorder r = new Recorder();
        Link l = openDeflate(s, r, "permessage-deflate; server_no_context_takeover; client_max_window_bits=15");
        assertTrue(l.client.extensions().contains("server_no_context_takeover"));
        TestWsServer.Compressor fresh = new TestWsServer.Compressor(false);
        String text = finalLike(4000);
        for (int i = 0; i < 3; i++) {
            int wire = l.peer.compressedText(fresh, text);
            assertEquals(text, r.nextText());
            assertTrue("each message on its own: " + wire, wire > 100);
        }
        // a server that breaks its word and refers to the previous message cannot be decoded,
        // which shows the client really started from an empty window
        TestWsServer.Compressor shared = new TestWsServer.Compressor(true);
        l.peer.compressedText(shared, text);
        assertEquals(text, r.nextText());
        l.peer.compressedText(shared, text);
        assertEquals(1007, l.peer.readFrame().closeCode());
        r.awaitEnd();
        assertEquals(1007, r.failureCode());
        fresh.end();
        shared.end();
    }

    /**
     * RFC 7692 7.2.3.4: a sender may end each message with a final block (BFINAL set) instead of
     * a sync flush; the next message is then a new DEFLATE stream and the client starts over.
     */
    @Test
    public void messagesEndingInAFinalBlock() throws Exception {
        TestWsServer s = server();
        Recorder r = new Recorder();
        Link l = openDeflate(s, r, "permessage-deflate");
        for (String text : new String[] {finalLike(5000), randomJson(2000), "短"}) {
            java.util.zip.Deflater d = new java.util.zip.Deflater(java.util.zip.Deflater.BEST_SPEED, true);
            d.setInput(text.getBytes(StandardCharsets.UTF_8));
            d.finish();
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            while (!d.finished()) {
                out.write(buf, 0, d.deflate(buf));
            }
            d.end();
            l.peer.sendCompressed(true, WsClient.OP_TEXT, out.toByteArray());
            assertEquals(text, r.nextText());
        }
        assertEquals(0, r.terminal.get());
    }

    @Test
    public void deflateAnswersAreChecked() throws Exception {
        String[] good = {
            "permessage-deflate",
            "permessage-deflate; client_max_window_bits=8",
            "PerMessage-Deflate ; server_no_context_takeover ; client_no_context_takeover; server_max_window_bits=10;"
                    + " client_max_window_bits=15",
            "permessage-deflate; server_max_window_bits=\"12\"",
            "permessage-deflate; server_max_window_bits=\"1\\3\"",
        };
        String[] described = {
            "permessage-deflate",
            "permessage-deflate; client_max_window_bits=8",
            "permessage-deflate; server_no_context_takeover; client_no_context_takeover; server_max_window_bits=10;"
                    + " client_max_window_bits=15",
            "permessage-deflate; server_max_window_bits=12",
            "permessage-deflate; server_max_window_bits=13",
        };
        TestWsServer s = server();
        for (int i = 0; i < good.length; i++) {
            Link l = openDeflate(s, new Recorder(), good[i]);
            assertEquals(described[i], l.client.extensions());
        }
        String[] bad = {
            "x-webkit-deflate-frame",
            "permessage-deflate, permessage-deflate",
            "permessage-deflate; client_max_window_bits",
            "permessage-deflate; server_max_window_bits=7",
            "permessage-deflate; server_max_window_bits=16",
            "permessage-deflate; server_max_window_bits=08",
            "permessage-deflate; server_max_window_bits=1x",
            "permessage-deflate; server_no_context_takeover=1",
            "permessage-deflate; client_no_context_takeover; client_no_context_takeover",
            "permessage-deflate; mystery=1",
            "permessage-deflate;",
            "permessage-deflate; server_max_window_bits=\"12",
            "permessage-deflate; server_max_window_bits=1\"2\"",
            "",
        };
        for (String b : bad) {
            FutureTask<WsClient> f = connectAsync(options(s, "/x"), new Recorder());
            TestWsServer.Peer p = s.next().readRequest();
            p.accept101("Sec-WebSocket-Extensions: " + b);
            IOException e = connectFailure(f);
            assertTrue(b + ": " + e, e instanceof WsClient.ProtocolError);
            assertTrue("the client closes the socket", p.eof());
        }
        // two header lines are one list: two extensions
        FutureTask<WsClient> f = connectAsync(options(s, "/x"), new Recorder());
        TestWsServer.Peer p = s.next().readRequest();
        p.accept101("Sec-WebSocket-Extensions: permessage-deflate", "Sec-WebSocket-Extensions: permessage-deflate");
        assertTrue(connectFailure(f) instanceof WsClient.ProtocolError);
    }

    @Test
    public void compressionViolationsCloseWith1002Or1007() throws Exception {
        TestWsServer.Compressor c = new TestWsServer.Compressor(false);
        byte[][] violations = {
            TestWsServer.frame(0xC9, 0, null, new byte[0]),                                 // compressed ping
            TestWsServer.frame(0xA1, 1, null, new byte[] {'x'}),                            // RSV2
            TestWsServer.frame(0x91, 1, null, new byte[] {'x'}),                            // RSV3
            TestWsServer.frame(0xC1, 4, null, new byte[] {(byte) 0xFF, 1, 2, 3}),           // reserved block type
            TestWsServer.frame(0xC1, 1, null, new byte[] {0}),                              // empty message (RFC 7692 7.2.3.6) ...
        };
        // ... is fine, so the last one is followed by text that inflates to invalid UTF-8
        byte[] badUtf8 = c.compress(new byte[] {(byte) 0xC3, 0x28});
        int[] codes = {1002, 1002, 1002, 1007, 1007};
        TestWsServer s = server();
        for (int i = 0; i < violations.length; i++) {
            Recorder r = new Recorder();
            Link l = openDeflate(s, r, "permessage-deflate");
            l.peer.write(violations[i]);
            if (i == violations.length - 1) {
                assertEquals("an empty compressed message is an empty text", "", r.nextText());
                l.peer.sendCompressed(true, WsClient.OP_TEXT, badUtf8);
            }
            TestWsServer.Frame close = l.peer.readFrame();
            assertEquals("violation " + i, WsClient.OP_CLOSE, close.opcode);
            assertEquals("violation " + i, codes[i], close.closeCode());
            r.awaitEnd();
            assertEquals("violation " + i, codes[i], r.failureCode());
        }
        // the compression bit on a continuation frame
        Recorder r = new Recorder();
        Link l = openDeflate(s, r, "permessage-deflate");
        byte[] p = c.compress("continued");
        l.peer.sendCompressed(false, WsClient.OP_TEXT, Arrays.copyOfRange(p, 0, 2));
        l.peer.sendCompressed(true, WsClient.OP_CONTINUATION, Arrays.copyOfRange(p, 2, p.length));
        assertEquals(1002, l.peer.readFrame().closeCode());
        r.awaitEnd();
        assertEquals(1002, r.failureCode());
        c.end();
    }

    @Test
    public void capsApplyToTheInflatedSizeAndStopInflationBombsEarly() throws Exception {
        TestWsServer s = server();
        TestWsServer.Compressor c = new TestWsServer.Compressor(false);
        Recorder r = new Recorder();
        Link l = openDeflate(s, r, "permessage-deflate");
        l.client.setMaxMessageBytes(1000);
        String exact = repeat('a', 1000);
        l.peer.compressedText(c, exact);
        assertEquals("exactly the cap passes", exact, r.nextText());
        int wire = l.peer.compressedText(c, repeat('a', 1001));
        assertTrue("small on the wire: " + wire, wire < 50);
        assertEquals(1009, l.peer.readFrame().closeCode());
        r.awaitEnd();
        assertEquals(1009, r.failureCode());
        assertTrue(r.failure.getMessage().contains("inflated"));

        // 64 MB of zeros are about 64 KB on the wire, below the 128 KB cap of the streaming phase:
        // inflation stops at the cap instead of filling the memory
        Recorder r2 = new Recorder();
        Link l2 = openDeflate(s, r2, "permessage-deflate");
        byte[] bomb = c.compress(new byte[64 * 1024 * 1024]);
        assertTrue(bomb.length < WsClient.DEFAULT_MAX_MESSAGE_BYTES);
        long t0 = System.nanoTime();
        l2.peer.sendCompressed(true, WsClient.OP_BINARY, bomb);
        assertEquals(1009, l2.peer.readFrame().closeCode());
        r2.awaitEnd();
        assertEquals(1009, r2.failureCode());
        assertTrue(r2.binaries.isEmpty());
        assertTrue("stopped early", (System.nanoTime() - t0) / 1000000L < 3000);
        c.end();
    }

    // ================================================================ helpers

    @Test
    public void closePayloadKeepsUtf8Whole() {
        assertEquals(0, WsClient.closePayload(-1, "x").length);
        assertArrayEquals(new byte[] {0x03, (byte) 0xE8}, WsClient.closePayload(1000, null));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            sb.append('中');
        }
        byte[] p = WsClient.closePayload(1011, sb.toString());
        assertTrue(p.length <= 125);
        assertEquals(2 + 41 * 3, p.length);
        assertEquals(41, new String(p, 2, p.length - 2, StandardCharsets.UTF_8).length());
    }

    @Test
    public void sniOnlyForHostNames() {
        assertTrue(WsClient.isSniHostname("open.shengzhiai.com"));
        assertTrue(WsClient.isSniHostname("localhost"));
        assertFalse(WsClient.isSniHostname("127.0.0.1"));
        assertFalse(WsClient.isSniHostname("::1"));
        assertFalse(WsClient.isSniHostname(""));
        assertFalse(WsClient.isSniHostname(null));
    }

    @Test
    public void validCloseCodes() {
        for (int c : new int[] {1000, 1001, 1002, 1003, 1007, 1011, 1014, 3000, 4999}) {
            assertTrue(String.valueOf(c), WsClient.isValidCloseCode(c));
        }
        for (int c : new int[] {0, 999, 1004, 1005, 1006, 1015, 2999, 5000}) {
            assertFalse(String.valueOf(c), WsClient.isValidCloseCode(c));
        }
    }

    @Test
    public void connectorPassesTheOptions() throws Exception {
        TestWsServer s = server();
        Recorder r = new Recorder();
        java.util.Map<String, String> h = new java.util.LinkedHashMap<>();
        h.put("X-Yugu-SDK", "yugu-stkouyu-compat/test");
        FutureTask<WsConnection> f = new FutureTask<>(() -> WsClient.CONNECTOR.connect(
                "ws://127.0.0.1:" + s.port() + "/sent.eval.cn?a=b", h, 3000, 400, r));
        new Thread(f).start();
        TestWsServer.Peer p = s.next().readRequest();
        assertEquals("yugu-stkouyu-compat/test", p.headers.get("x-yugu-sdk"));
        p.accept101();
        WsConnection c = f.get(5, TimeUnit.SECONDS);
        cleanup.add(c::abort);
        r.awaitEnd();
        assertTrue("read timeout 400 ms", r.failure instanceof SocketTimeoutException);
    }
}
