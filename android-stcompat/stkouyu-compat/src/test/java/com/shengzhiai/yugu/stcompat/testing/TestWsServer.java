package com.shengzhiai.yugu.stcompat.testing;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;

/**
 * In-process WebSocket server for client tests, written independently of the client: every
 * accepted connection becomes a {@link Peer} that the test drives byte by byte, so well-formed and
 * malformed answers and frames can be produced.
 */
public final class TestWsServer implements AutoCloseable {
    public static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private final ServerSocket server;
    private final LinkedBlockingQueue<Peer> peers = new LinkedBlockingQueue<>();

    public TestWsServer() throws IOException {
        this(null);
    }

    /** Plain server, or TLS with {@code tls}; listens on 127.0.0.1 only. */
    public TestWsServer(SSLContext tls) throws IOException {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        server = tls == null ? new ServerSocket(0, 50, loopback)
                : tls.getServerSocketFactory().createServerSocket(0, 50, loopback);
        Thread acceptor = new Thread(() -> {
            while (!server.isClosed()) {
                try {
                    Socket s = server.accept();
                    s.setSoTimeout(10000);
                    if (s instanceof javax.net.ssl.SSLSocket) {
                        // the server side of the TLS handshake runs at once on its own thread (also for
                        // clients that give up in it); the peer is ready once the handshake is done
                        Thread hs = new Thread(() -> {
                            try {
                                ((javax.net.ssl.SSLSocket) s).startHandshake();
                                peers.add(new Peer(s));
                            } catch (IOException e) {
                                try {
                                    s.close();
                                } catch (IOException ignored) {
                                    // test cleanup
                                }
                            }
                        }, "test-ws-tls");
                        hs.setDaemon(true);
                        hs.start();
                    } else {
                        peers.add(new Peer(s));
                    }
                } catch (IOException e) {
                    return;
                }
            }
        }, "test-ws-acceptor");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    public int port() {
        return server.getLocalPort();
    }

    /** Next accepted connection. */
    public Peer next() throws InterruptedException {
        Peer p = peers.poll(10, TimeUnit.SECONDS);
        if (p == null) {
            throw new AssertionError("no connection within 10 s");
        }
        return p;
    }

    /** Connections accepted and not yet taken. */
    public int pending() {
        return peers.size();
    }

    @Override
    public void close() {
        try {
            server.close();
        } catch (IOException ignored) {
            // test cleanup
        }
        for (Peer p : peers) {
            p.close();
        }
    }

    public static String accept(String key) {
        try {
            byte[] sha1 = MessageDigest.getInstance("SHA-1").digest((key + GUID).getBytes(StandardCharsets.US_ASCII));
            return Base64.getEncoder().encodeToString(sha1);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Header of a server frame with an explicit length field, then the payload as given. */
    public static byte[] frame(int b0, long length, byte[] mask, byte[] payload) {
        ByteArrayOutputStream f = new ByteArrayOutputStream();
        f.write(b0);
        int maskBit = mask != null ? 0x80 : 0;
        if (length < 126) {
            f.write(maskBit | (int) length);
        } else if (length <= 0xFFFF) {
            f.write(maskBit | 126);
            f.write((int) (length >>> 8) & 0xFF);
            f.write((int) length & 0xFF);
        } else {
            f.write(maskBit | 127);
            for (int i = 7; i >= 0; i--) {
                f.write((int) (length >>> (8 * i)) & 0xFF);
            }
        }
        if (mask != null) {
            f.write(mask, 0, 4);
        }
        if (payload != null) {
            for (int i = 0; i < payload.length; i++) {
                f.write(mask != null ? payload[i] ^ mask[i & 3] : payload[i]);
            }
        }
        return f.toByteArray();
    }

    /** A frame read from the client. */
    public static final class Frame {
        public boolean fin;
        public int rsv;
        public int opcode;
        public boolean masked;
        public byte[] mask;
        public int lengthBytes;
        public byte[] payload;

        public String text() {
            return new String(payload, StandardCharsets.UTF_8);
        }

        public int closeCode() {
            return payload.length >= 2 ? ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF) : 1005;
        }

        public String closeReason() {
            return payload.length > 2 ? new String(payload, 2, payload.length - 2, StandardCharsets.UTF_8) : "";
        }
    }

    /** One accepted connection, driven by the test. */
    public static final class Peer {
        public final Socket socket;
        private final InputStream in;
        private final OutputStream out;
        public String requestLine;
        public final Map<String, String> headers = new LinkedHashMap<>();

        Peer(Socket socket) throws IOException {
            this.socket = socket;
            this.in = new BufferedInputStream(socket.getInputStream());
            this.out = socket.getOutputStream();
        }

        /** Reads the opening handshake request. */
        public Peer readRequest() throws IOException {
            requestLine = readLine();
            while (true) {
                String line = readLine();
                if (line.isEmpty()) {
                    return this;
                }
                int c = line.indexOf(':');
                headers.put(line.substring(0, c).trim().toLowerCase(Locale.ROOT), line.substring(c + 1).trim());
            }
        }

        public String key() {
            return headers.get("sec-websocket-key");
        }

        /** 101 with the right accept and any extra header lines. */
        public Peer accept101(String... extraLines) throws IOException {
            StringBuilder sb = new StringBuilder("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n");
            sb.append("Sec-WebSocket-Accept: ").append(accept(key())).append("\r\n");
            for (String l : extraLines) {
                sb.append(l).append("\r\n");
            }
            writeAscii(sb.append("\r\n").toString());
            return this;
        }

        /** readRequest then accept101. */
        public Peer handshake() throws IOException {
            return readRequest().accept101();
        }

        public void writeAscii(String s) throws IOException {
            write(s.getBytes(StandardCharsets.US_ASCII));
        }

        public void write(byte[] b) throws IOException {
            out.write(b);
            out.flush();
        }

        public void text(String s) throws IOException {
            write(frame(0x81, s.getBytes(StandardCharsets.UTF_8).length, null, s.getBytes(StandardCharsets.UTF_8)));
        }

        public void send(boolean fin, int opcode, byte[] payload) throws IOException {
            write(frame((fin ? 0x80 : 0) | opcode, payload.length, null, payload));
        }

        public void close(int code, String reason) throws IOException {
            byte[] r = reason.getBytes(StandardCharsets.UTF_8);
            byte[] p = new byte[2 + r.length];
            p[0] = (byte) (code >> 8);
            p[1] = (byte) code;
            System.arraycopy(r, 0, p, 2, r.length);
            send(true, 0x8, p);
        }

        /** Reads one client frame; the payload is unmasked. */
        public Frame readFrame() throws IOException {
            Frame f = new Frame();
            int b0 = readByte();
            int b1 = readByte();
            f.fin = (b0 & 0x80) != 0;
            f.rsv = b0 & 0x70;
            f.opcode = b0 & 0x0F;
            f.masked = (b1 & 0x80) != 0;
            long len = b1 & 0x7F;
            f.lengthBytes = 0;
            if (len == 126) {
                len = (readByte() << 8) | readByte();
                f.lengthBytes = 2;
            } else if (len == 127) {
                len = 0;
                for (int i = 0; i < 8; i++) {
                    len = (len << 8) | readByte();
                }
                f.lengthBytes = 8;
            }
            if (f.masked) {
                f.mask = new byte[4];
                for (int i = 0; i < 4; i++) {
                    f.mask[i] = (byte) readByte();
                }
            }
            f.payload = new byte[(int) len];
            for (int i = 0; i < len; i++) {
                int b = readByte();
                f.payload[i] = (byte) (f.masked ? b ^ f.mask[i & 3] : b);
            }
            return f;
        }

        /** True when the client closed the connection (EOF) within the socket timeout. */
        public boolean eof() throws IOException {
            try {
                return in.read() < 0;
            } catch (java.net.SocketException e) {
                return true;
            }
        }

        private int readByte() throws IOException {
            int b = in.read();
            if (b < 0) {
                throw new EOFException("client closed");
            }
            return b;
        }

        private String readLine() throws IOException {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            while (true) {
                int c = readByte();
                if (c == '\n') {
                    break;
                }
                if (c != '\r') {
                    b.write(c);
                }
            }
            return new String(b.toByteArray(), StandardCharsets.UTF_8);
        }

        public void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
                // test cleanup
            }
        }
    }
}
