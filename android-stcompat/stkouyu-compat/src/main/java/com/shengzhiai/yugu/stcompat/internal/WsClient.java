// SPDX-License-Identifier: Apache-2.0
package com.shengzhiai.yugu.stcompat.internal;

import android.annotation.SuppressLint;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * RFC 6455 WebSocket client of the compat realtime channel (DESIGN 14.3), part of this AAR so the
 * drop-in keeps zero runtime dependencies.
 *
 * <ul>
 * <li>ws and wss; every address the host resolves to is tried with the connect timeout</li>
 * <li>TLS through an SSLSocketFactory ({@link HttpsURLConnection#getDefaultSSLSocketFactory()} by
 * default) with SNI for host names; the certificate name is checked with
 * {@link HttpsURLConnection#getDefaultHostnameVerifier()}. The default verifier of a plain JVM
 * rejects every name (HttpsURLConnection checks names itself before asking it), so there the TLS
 * handshake checks the name instead (endpoint identification HTTPS)</li>
 * <li>opening handshake with the Sec-WebSocket-Accept check; an extension or subprotocol that was
 * not offered fails the handshake</li>
 * <li>permessage-deflate (RFC 7692, SDK 2.1.1): offered as
 * {@code permessage-deflate; client_max_window_bits} unless {@link Options#offerDeflate} is false.
 * The answer may carry server_no_context_takeover, client_no_context_takeover,
 * server_max_window_bits and client_max_window_bits; any other parameter, value or extension fails
 * the handshake. Inbound messages with RSV1 are inflated with a raw {@link Inflater} plus the
 * {@code 00 00 ff ff} tail; the inflater keeps its window from message to message unless the
 * server_no_context_takeover parameter was agreed. Client messages stay uncompressed (RSV1 0),
 * which every agreed parameter allows</li>
 * <li>client frames masked with a fresh random key; text, binary and continuation frames, ping
 * answered with pong, close handshake</li>
 * <li>an inbound frame or message larger than {@link #setMaxMessageBytes} (128 KB by default) closes
 * the connection with 1009, compressed on the wire as well as inflated: inflation stops as soon as
 * the output passes the cap, so a small message that would inflate to gigabytes (an inflation bomb)
 * costs at most the cap in memory. Invalid UTF-8 text and undecodable compressed data close with
 * 1007, any other protocol violation with 1002</li>
 * </ul>
 *
 * Listener callbacks run on one reader thread. A connection ends with exactly one onClosed or
 * onFailure, unless {@link #close} or {@link #abort} was called first; then it ends silently.
 */
public final class WsClient implements WsConnection {
    public static final int OP_CONTINUATION = 0x0;
    public static final int OP_TEXT = 0x1;
    public static final int OP_BINARY = 0x2;
    public static final int OP_CLOSE = 0x8;
    public static final int OP_PING = 0x9;
    public static final int OP_PONG = 0xA;

    /** Default cap of inbound frames and messages: the platform's frame limit (DESIGN 14.3). */
    public static final int DEFAULT_MAX_MESSAGE_BYTES = 128 * 1024;
    /** Status line and headers of the handshake answer. */
    static final int MAX_HANDSHAKE_BYTES = 16 * 1024;
    /** How long a client close waits for the server's close frame. */
    static final long CLOSE_WAIT_MS = 1000;
    static final String ACCEPT_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    /** Class of the JDK's default HostnameVerifier, which answers false for every name. */
    static final String JDK_DEFAULT_VERIFIER = "javax.net.ssl.HttpsURLConnection$DefaultHostnameVerifier";
    /** Extension offer: the server may answer with a client_max_window_bits value, which an uncompressed sender meets. */
    static final String DEFLATE_OFFER = "permessage-deflate; client_max_window_bits";
    static final String DEFLATE = "permessage-deflate";
    /** Appended to a compressed message before inflating it (RFC 7692 7.2.2). */
    static final byte[] DEFLATE_TAIL = {0x00, 0x00, (byte) 0xFF, (byte) 0xFF};
    /** Inflater output step; the cap check runs after every step. */
    static final int INFLATE_STEP = 16 * 1024;

    /**
     * {@link Options#hostnameVerifier} value that leaves the name check to the TLS handshake
     * (endpoint identification HTTPS), as done for the JDK's default verifier. Never called.
     */
    public static final HostnameVerifier HANDSHAKE_CHECKS_NAME = new HostnameVerifier() {
        @Override
        public boolean verify(String hostname, javax.net.ssl.SSLSession session) {
            return false;
        }
    };

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final byte[] EMPTY = new byte[0];

    /** The server answered the opening handshake with a status other than 101. */
    public static final class HandshakeException extends IOException {
        private static final long serialVersionUID = 1L;
        public final int status;
        /** Retry-After of the answer in ms, -1 when absent. */
        public final long retryAfterMs;

        HandshakeException(int status, long retryAfterMs) {
            super("WebSocket handshake answered HTTP " + status);
            this.status = status;
            this.retryAfterMs = retryAfterMs;
        }
    }

    /** The peer violated the protocol; the connection was closed with {@link #closeCode}. */
    public static final class ProtocolError extends IOException {
        private static final long serialVersionUID = 1L;
        public final int closeCode;

        ProtocolError(int closeCode, String message) {
            super(message);
            this.closeCode = closeCode;
        }
    }

    /** Resolves a host to every address; replaced in tests. */
    public interface Resolver {
        InetAddress[] resolve(String host) throws IOException;
    }

    public static final Resolver SYSTEM_RESOLVER = new Resolver() {
        @Override
        public InetAddress[] resolve(String host) throws IOException {
            return InetAddress.getAllByName(host);
        }
    };

    /** Connection options. */
    public static final class Options {
        /** ws or wss URL with path and query. */
        public String url;
        public final Map<String, String> headers = new LinkedHashMap<String, String>();
        public int connectTimeoutMs = CompatConfig.DEFAULT_CONNECT_TIMEOUT_MS;
        public int readTimeoutMs = CompatConfig.DEFAULT_READ_TIMEOUT_MS;
        /** null: {@link HttpsURLConnection#getDefaultSSLSocketFactory()}. */
        public SSLSocketFactory sslSocketFactory;
        /** null: {@link HttpsURLConnection#getDefaultHostnameVerifier()}. */
        public HostnameVerifier hostnameVerifier;
        /** null: {@link #SYSTEM_RESOLVER}. */
        public Resolver resolver;
        public int maxMessageBytes = DEFAULT_MAX_MESSAGE_BYTES;
        /** Offer permessage-deflate in the handshake (default); false sends no extension offer. */
        public boolean offerDeflate = true;
    }

    /** permessage-deflate parameters agreed in the handshake (RFC 7692 7.1). */
    static final class Deflate {
        boolean serverNoContextTakeover;
        boolean clientNoContextTakeover;
        /** -1 when the answer has no server_max_window_bits. */
        int serverMaxWindowBits = -1;
        /** -1 when the answer has no client_max_window_bits. */
        int clientMaxWindowBits = -1;

        /** The agreed extension with its parameters, as the server answered them. */
        String describe() {
            StringBuilder sb = new StringBuilder(DEFLATE);
            if (serverNoContextTakeover) {
                sb.append("; server_no_context_takeover");
            }
            if (clientNoContextTakeover) {
                sb.append("; client_no_context_takeover");
            }
            if (serverMaxWindowBits > 0) {
                sb.append("; server_max_window_bits=").append(serverMaxWindowBits);
            }
            if (clientMaxWindowBits > 0) {
                sb.append("; client_max_window_bits=").append(clientMaxWindowBits);
            }
            return sb.toString();
        }
    }

    /** Connector of {@link CompatConfig#wsConnector()} by default. */
    public static final Connector CONNECTOR = new Connector() {
        @Override
        public WsConnection connect(String url, Map<String, String> headers, int connectTimeoutMs, int readTimeoutMs,
                                    Listener listener) throws IOException {
            Options o = new Options();
            o.url = url;
            if (headers != null) {
                o.headers.putAll(headers);
            }
            o.connectTimeoutMs = connectTimeoutMs;
            o.readTimeoutMs = readTimeoutMs;
            return WsClient.connect(o, listener);
        }
    };

    private final Socket raw;
    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final Listener listener;
    /** Agreed permessage-deflate parameters, null when no extension was agreed. */
    private final Deflate deflate;
    private final Object writeLock = new Object();
    private final CountDownLatch readerDone = new CountDownLatch(1);
    private volatile int maxMessageBytes;
    /** close or abort was called: no further callbacks. */
    private volatile boolean silenced;
    /** The data message being delivered (reader thread writes, {@link #inbound()} reads). */
    private volatile Inbound inbound;
    /** Guarded by writeLock. */
    private boolean closeSent;
    private Thread reader;
    // reader thread only
    private ByteArrayOutputStream fragments;
    private int fragmentsOpcode;
    private int serverCloseCode;
    private String serverCloseReason;
    /** The current message started with RSV1 set. */
    private boolean messageCompressed;
    private long messageStartNanos;
    /** Created with the first compressed message, ended when the reader stops. */
    private Inflater inflater;
    private byte[] inflateBuffer;

    private WsClient(Socket raw, Socket socket, InputStream in, OutputStream out, Listener listener, int maxMessageBytes,
                     Deflate deflate) {
        this.raw = raw;
        this.socket = socket;
        this.in = in;
        this.out = out;
        this.listener = listener;
        this.maxMessageBytes = maxMessageBytes;
        this.deflate = deflate;
    }

    // ================================================================ connect

    /**
     * Connects, completes the opening handshake and starts the reader thread.
     *
     * @throws HandshakeException when the server answers with a status other than 101
     * @throws ProtocolError when the answer is not a valid WebSocket handshake
     * @throws IOException on connect, TLS or I/O failure
     */
    public static WsClient connect(Options o, Listener listener) throws IOException {
        Target t = Target.parse(o.url);
        int connectTimeout = Math.max(1, o.connectTimeoutMs);
        Socket raw = openSocket(t, o.resolver != null ? o.resolver : SYSTEM_RESOLVER, connectTimeout);
        Socket s = raw;
        boolean ok = false;
        try {
            raw.setTcpNoDelay(true);
            // TLS and the handshake answer are bounded by the connect timeout, frames by the read timeout
            raw.setSoTimeout(connectTimeout);
            if (t.secure) {
                s = tls(raw, t, o);
            }
            InputStream in = new BufferedInputStream(s.getInputStream(), 16 * 1024);
            OutputStream out = s.getOutputStream();
            String key = Codec.base64(randomBytes(16));
            out.write(requestHead(t, key, o.headers, o.offerDeflate).getBytes(Codec.UTF_8));
            out.flush();
            Deflate deflate = readHandshakeAnswer(in, key, o.offerDeflate);
            s.setSoTimeout(Math.max(1, o.readTimeoutMs));
            WsClient c = new WsClient(raw, s, in, out, listener, o.maxMessageBytes > 0 ? o.maxMessageBytes
                    : DEFAULT_MAX_MESSAGE_BYTES, deflate);
            c.startReader();
            ok = true;
            return c;
        } finally {
            if (!ok) {
                closeQuietly(s);
                closeQuietly(raw);
            }
        }
    }

    /** Scheme, host, port and request target of a ws or wss URL. */
    static final class Target {
        final boolean secure;
        final String host;
        final int port;
        final String resource;
        final String hostHeader;

        private Target(boolean secure, String host, int port, String resource, String hostHeader) {
            this.secure = secure;
            this.host = host;
            this.port = port;
            this.resource = resource;
            this.hostHeader = hostHeader;
        }

        /** Never puts the URL into an exception message: its query carries the signature. */
        static Target parse(String url) throws IOException {
            URI u;
            try {
                u = new URI(url);
            } catch (URISyntaxException e) {
                throw new IOException("invalid WebSocket URL");
            }
            String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
            boolean secure;
            if ("wss".equals(scheme)) {
                secure = true;
            } else if ("ws".equals(scheme)) {
                secure = false;
            } else {
                throw new IOException("WebSocket URL needs the ws or wss scheme, not '" + scheme + "'");
            }
            String host = u.getHost();
            if (host == null || host.isEmpty()) {
                throw new IOException("WebSocket URL has no host");
            }
            if (host.startsWith("[") && host.endsWith("]")) {
                host = host.substring(1, host.length() - 1);
            }
            int defaultPort = secure ? 443 : 80;
            int port = u.getPort() > 0 ? u.getPort() : defaultPort;
            String path = u.getRawPath();
            if (path == null || path.isEmpty()) {
                path = "/";
            }
            String query = u.getRawQuery();
            String hostHeader = (host.indexOf(':') >= 0 ? "[" + host + "]" : host) + (port == defaultPort ? "" : ":" + port);
            return new Target(secure, host, port, query == null ? path : path + "?" + query, hostHeader);
        }
    }

    /** Tries every resolved address in order with the connect timeout; throws the last failure. */
    static Socket openSocket(Target t, Resolver resolver, int connectTimeoutMs) throws IOException {
        InetAddress[] addresses = resolver.resolve(t.host);
        if (addresses == null || addresses.length == 0) {
            throw new UnknownHostException(t.host);
        }
        IOException last = null;
        for (InetAddress a : addresses) {
            Socket s = new Socket();
            try {
                s.connect(new InetSocketAddress(a, t.port), connectTimeoutMs);
                return s;
            } catch (IOException e) {
                closeQuietly(s);
                if (last != null) {
                    e.addSuppressed(last);
                }
                last = e;
                YLog.d("WebSocket connect to " + a.getHostAddress() + " port " + t.port + " failed: " + e);
            }
        }
        throw last;
    }

    private static SSLSocket tls(Socket raw, Target t, Options o) throws IOException {
        SSLSocketFactory f = o.sslSocketFactory != null ? o.sslSocketFactory : HttpsURLConnection.getDefaultSSLSocketFactory();
        SSLSocket s = (SSLSocket) f.createSocket(raw, t.host, t.port, true);
        s.setSoTimeout(raw.getSoTimeout());
        HostnameVerifier v = o.hostnameVerifier != null ? o.hostnameVerifier : HttpsURLConnection.getDefaultHostnameVerifier();
        boolean handshakeChecksName = v == null || v == HANDSHAKE_CHECKS_NAME || JDK_DEFAULT_VERIFIER.equals(v.getClass().getName());
        if (!configureTls(s, t.host, handshakeChecksName)) {
            throw new SSLPeerUnverifiedException("no way to check the certificate name of " + t.host);
        }
        s.startHandshake();
        if (!handshakeChecksName && !v.verify(t.host, s.getSession())) {
            throw new SSLPeerUnverifiedException("certificate does not match " + t.host);
        }
        return s;
    }

    /**
     * SNI for host names (SNIHostName on Android 7 and later and on the JVM, setHostname of the
     * platform socket on Android 5 and 6) and, when asked, endpoint identification HTTPS. Returns
     * false only when endpoint identification was asked for and is not available.
     */
    @SuppressLint("NewApi")
    static boolean configureTls(SSLSocket s, String host, boolean endpointIdentification) {
        SSLParameters p = s.getSSLParameters();
        boolean sniSet = false;
        if (isSniHostname(host)) {
            try {
                p.setServerNames(Collections.<SNIServerName>singletonList(new SNIHostName(host)));
                sniSet = true;
            } catch (NoClassDefFoundError | NoSuchMethodError | IllegalArgumentException e) {
                // Android 5 and 6 have no SNIHostName; names SNI rejects are sent without SNI
            }
            if (!sniSet) {
                try {
                    s.getClass().getMethod("setHostname", String.class).invoke(s, host);
                } catch (Exception | LinkageError ignored) {
                    // this socket implementation sends no SNI
                }
            }
        }
        boolean identification = false;
        if (endpointIdentification) {
            try {
                p.setEndpointIdentificationAlgorithm("HTTPS");
                identification = true;
            } catch (NoSuchMethodError e) {
                identification = false;
            }
        }
        if (sniSet || identification) {
            s.setSSLParameters(p);
        }
        return !endpointIdentification || identification;
    }

    /** True for a DNS name; IP literals get no SNI. */
    static boolean isSniHostname(String host) {
        if (host == null || host.isEmpty() || host.indexOf(':') >= 0) {
            return false;
        }
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            if (c != '.' && (c < '0' || c > '9')) {
                return true;
            }
        }
        return false;
    }

    static String requestHead(Target t, String key, Map<String, String> headers) throws IOException {
        return requestHead(t, key, headers, false);
    }

    static String requestHead(Target t, String key, Map<String, String> headers, boolean offerDeflate) throws IOException {
        StringBuilder sb = new StringBuilder(512);
        sb.append("GET ").append(t.resource).append(" HTTP/1.1\r\n");
        sb.append("Host: ").append(t.hostHeader).append("\r\n");
        sb.append("Upgrade: websocket\r\n");
        sb.append("Connection: Upgrade\r\n");
        sb.append("Sec-WebSocket-Key: ").append(key).append("\r\n");
        sb.append("Sec-WebSocket-Version: 13\r\n");
        if (offerDeflate) {
            sb.append("Sec-WebSocket-Extensions: ").append(DEFLATE_OFFER).append("\r\n");
        }
        if (headers != null) {
            for (Map.Entry<String, String> h : headers.entrySet()) {
                String name = h.getKey();
                String value = h.getValue() == null ? "" : h.getValue();
                if (name == null || name.isEmpty() || hasLineBreak(name) || hasLineBreak(value)) {
                    throw new IOException("invalid handshake header " + name);
                }
                sb.append(name).append(": ").append(value).append("\r\n");
            }
        }
        return sb.append("\r\n").toString();
    }

    private static boolean hasLineBreak(String s) {
        return s.indexOf('\r') >= 0 || s.indexOf('\n') >= 0;
    }

    /**
     * Reads the status line and headers of the answer and checks them. Frames that follow stay in
     * {@code in}. Returns the agreed permessage-deflate parameters, null when no extension was agreed.
     */
    static Deflate readHandshakeAnswer(InputStream in, String key, boolean offeredDeflate) throws IOException {
        int[] budget = {MAX_HANDSHAKE_BYTES};
        String status = readLine(in, budget);
        String[] parts = status.split(" ", 3);
        int code = -1;
        if (parts.length >= 2 && parts[0].startsWith("HTTP/")) {
            try {
                code = Integer.parseInt(parts[1].trim());
            } catch (NumberFormatException ignored) {
                code = -1;
            }
        }
        if (code < 0) {
            throw new ProtocolError(1002, "the WebSocket handshake answer is not HTTP");
        }
        Map<String, String> h = new LinkedHashMap<String, String>();
        while (true) {
            String line = readLine(in, budget);
            if (line.isEmpty()) {
                break;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            String prev = h.get(name);
            h.put(name, prev == null ? value : prev + ", " + value);
        }
        if (code != 101) {
            throw new HandshakeException(code, ErrorMapper.parseRetryAfter(h.get("retry-after"), System.currentTimeMillis()));
        }
        if (!"websocket".equalsIgnoreCase(h.get("upgrade"))) {
            throw new ProtocolError(1002, "the handshake answer has no Upgrade: websocket");
        }
        if (!hasToken(h.get("connection"), "upgrade")) {
            throw new ProtocolError(1002, "the handshake answer has no Connection: Upgrade");
        }
        if (!acceptFor(key).equals(h.get("sec-websocket-accept"))) {
            throw new ProtocolError(1002, "Sec-WebSocket-Accept does not match the key");
        }
        if (h.containsKey("sec-websocket-protocol")) {
            throw new ProtocolError(1002, "the server selected a subprotocol that was not offered");
        }
        String extensions = h.get("sec-websocket-extensions");
        if (extensions == null) {
            return null;
        }
        if (!offeredDeflate) {
            throw new ProtocolError(1002, "the server selected an extension that was not offered");
        }
        return negotiateDeflate(extensions);
    }

    /**
     * Checks the Sec-WebSocket-Extensions answer to {@link #DEFLATE_OFFER} (RFC 7692 7.1): exactly one
     * permessage-deflate element, each parameter at most once, the context takeover parameters
     * without value, the window bits with a value from 8 to 15 without leading zeros.
     */
    static Deflate negotiateDeflate(String header) throws ProtocolError {
        List<String> elements = splitOutsideQuotes(header, ',');
        if (elements.size() != 1) {
            throw new ProtocolError(1002, elements.isEmpty() ? "empty Sec-WebSocket-Extensions answer"
                    : "the server selected more than one extension");
        }
        List<String> parts = splitOutsideQuotes(elements.get(0), ';');
        if (parts.isEmpty() || !DEFLATE.equalsIgnoreCase(parts.get(0))) {
            throw new ProtocolError(1002, "the server selected an extension that was not offered");
        }
        Deflate d = new Deflate();
        Set<String> seen = new HashSet<String>();
        for (int i = 1; i < parts.size(); i++) {
            String p = parts.get(i);
            int eq = p.indexOf('=');
            String name = (eq < 0 ? p : p.substring(0, eq)).trim().toLowerCase(Locale.ROOT);
            String value = eq < 0 ? null : unquoteParam(p.substring(eq + 1).trim());
            if (!seen.add(name)) {
                throw new ProtocolError(1002, "permessage-deflate parameter " + name + " appears twice");
            }
            if ("server_no_context_takeover".equals(name) || "client_no_context_takeover".equals(name)) {
                if (value != null) {
                    throw new ProtocolError(1002, "permessage-deflate parameter " + name + " takes no value");
                }
                if (name.startsWith("server")) {
                    d.serverNoContextTakeover = true;
                } else {
                    d.clientNoContextTakeover = true;
                }
            } else if ("server_max_window_bits".equals(name)) {
                d.serverMaxWindowBits = windowBits(name, value);
            } else if ("client_max_window_bits".equals(name)) {
                // offered without a value: any value from 8 to 15 is acceptable to an uncompressed sender
                d.clientMaxWindowBits = windowBits(name, value);
            } else {
                throw new ProtocolError(1002, "unknown permessage-deflate parameter " + name);
            }
        }
        return d;
    }

    private static int windowBits(String name, String value) throws ProtocolError {
        if (value == null || !value.matches("[1-9][0-9]?")) {
            throw new ProtocolError(1002, "invalid value of permessage-deflate parameter " + name);
        }
        int bits = Integer.parseInt(value);
        if (bits < 8 || bits > 15) {
            throw new ProtocolError(1002, "invalid value of permessage-deflate parameter " + name);
        }
        return bits;
    }

    /** Value of an extension parameter: a token, or a quoted string whose content is used. */
    private static String unquoteParam(String v) throws ProtocolError {
        if (v.length() >= 2 && v.charAt(0) == '"' && v.charAt(v.length() - 1) == '"') {
            StringBuilder sb = new StringBuilder(v.length());
            for (int i = 1; i < v.length() - 1; i++) {
                char c = v.charAt(i);
                if (c == '\\' && i + 1 < v.length() - 1) {
                    c = v.charAt(++i);
                }
                sb.append(c);
            }
            return sb.toString();
        }
        if (v.indexOf('"') >= 0) {
            throw new ProtocolError(1002, "malformed Sec-WebSocket-Extensions answer");
        }
        return v;
    }

    /** Trimmed pieces of {@code s} split at {@code sep} outside quoted strings; an empty piece is malformed. */
    static List<String> splitOutsideQuotes(String s, char sep) throws ProtocolError {
        List<String> out = new ArrayList<String>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quoted && c == '\\' && i + 1 < s.length()) {
                cur.append(c).append(s.charAt(++i));
                continue;
            }
            if (c == '"') {
                quoted = !quoted;
            }
            if (c == sep && !quoted) {
                out.add(cur.toString().trim());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        if (quoted) {
            throw new ProtocolError(1002, "malformed Sec-WebSocket-Extensions answer");
        }
        String last = cur.toString().trim();
        if (!last.isEmpty() || !out.isEmpty()) {
            out.add(last);
        }
        for (String piece : out) {
            if (piece.isEmpty()) {
                throw new ProtocolError(1002, "malformed Sec-WebSocket-Extensions answer");
            }
        }
        return out;
    }

    private static String readLine(InputStream in, int[] budget) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(80);
        while (true) {
            int c = in.read();
            if (c < 0) {
                throw new EOFException("connection closed during the WebSocket handshake");
            }
            if (--budget[0] < 0) {
                throw new ProtocolError(1002, "the WebSocket handshake answer exceeds " + MAX_HANDSHAKE_BYTES + " bytes");
            }
            if (c == '\n') {
                break;
            }
            b.write(c);
        }
        byte[] a = b.toByteArray();
        int n = a.length > 0 && a[a.length - 1] == '\r' ? a.length - 1 : a.length;
        return new String(a, 0, n, Codec.US_ASCII);
    }

    private static boolean hasToken(String value, String token) {
        if (value == null) {
            return false;
        }
        for (String t : value.split(",")) {
            if (token.equalsIgnoreCase(t.trim())) {
                return true;
            }
        }
        return false;
    }

    /** Sec-WebSocket-Accept for a key: Base64(SHA-1(key + GUID)). */
    public static String acceptFor(String key) {
        return Codec.base64(Codec.digest("SHA-1", (key + ACCEPT_GUID).getBytes(Codec.US_ASCII)));
    }

    // ================================================================ reading

    private void startReader() {
        reader = new Thread(new Runnable() {
            @Override
            public void run() {
                readLoop();
            }
        }, "yugu-stcompat-ws-reader");
        reader.setDaemon(true);
        reader.start();
    }

    private void readLoop() {
        IOException failure = null;
        try {
            readFrames();
        } catch (ProtocolError e) {
            failure = e;
            sendCloseQuietly(e.closeCode, e.getMessage());
        } catch (IOException e) {
            failure = e;
        } catch (RuntimeException e) {
            failure = new IOException("WebSocket reader failed: " + e, e);
        } finally {
            closeQuietly(socket);
            closeQuietly(raw);
            if (inflater != null) {
                inflater.end();
                inflater = null;
            }
            readerDone.countDown();
        }
        if (silenced) {
            return;
        }
        try {
            if (failure == null) {
                listener.onClosed(serverCloseCode, serverCloseReason);
            } else {
                listener.onFailure(failure);
            }
        } catch (RuntimeException e) {
            YLog.w("WebSocket listener failed", e);
        }
    }

    /** Reads frames until the server's close frame; throws on failure. */
    private void readFrames() throws IOException {
        while (true) {
            int b0 = readByte();
            long frameStart = System.nanoTime();
            int b1 = readByte();
            boolean fin = (b0 & 0x80) != 0;
            boolean rsv1 = (b0 & 0x40) != 0;
            int op = b0 & 0x0F;
            if ((b0 & 0x30) != 0 || (rsv1 && deflate == null)) {
                throw new ProtocolError(1002, "reserved bits set without a negotiated extension");
            }
            if ((b1 & 0x80) != 0) {
                throw new ProtocolError(1002, "the server masked a frame");
            }
            long len = b1 & 0x7F;
            if (len == 126) {
                len = (readByte() << 8) | readByte();
            } else if (len == 127) {
                len = 0;
                for (int i = 0; i < 8; i++) {
                    len = (len << 8) | readByte();
                }
                if (len < 0) {
                    throw new ProtocolError(1002, "frame length out of range");
                }
            }
            boolean control = (op & 0x08) != 0;
            if (control) {
                if (op != OP_CLOSE && op != OP_PING && op != OP_PONG) {
                    throw new ProtocolError(1002, "unknown control opcode " + op);
                }
                if (!fin) {
                    throw new ProtocolError(1002, "fragmented control frame");
                }
                if (len > 125) {
                    throw new ProtocolError(1002, "control frame longer than 125 bytes");
                }
                if (rsv1) {
                    throw new ProtocolError(1002, "compressed control frame");
                }
            } else {
                if (op != OP_CONTINUATION && op != OP_TEXT && op != OP_BINARY) {
                    throw new ProtocolError(1002, "unknown opcode " + op);
                }
                if (op == OP_CONTINUATION && fragments == null) {
                    throw new ProtocolError(1002, "continuation frame without a message");
                }
                if (op != OP_CONTINUATION && fragments != null) {
                    throw new ProtocolError(1002, "new message before the fragmented one ended");
                }
                if (op == OP_CONTINUATION && rsv1) {
                    // RFC 7692 6.1: only the first frame of a message carries the compression bit
                    throw new ProtocolError(1002, "compression bit on a continuation frame");
                }
                long total = len + (op == OP_CONTINUATION ? fragments.size() : 0);
                int max = maxMessageBytes;
                if (total > max) {
                    throw new ProtocolError(1009, "inbound message of more than " + max + " bytes");
                }
                if (op != OP_CONTINUATION) {
                    messageStartNanos = frameStart;
                    messageCompressed = rsv1;
                }
            }
            byte[] payload = readFully((int) len);
            switch (op) {
                case OP_PING:
                    sendFrame(OP_PONG, payload, 0, payload.length);
                    break;
                case OP_PONG:
                    break;
                case OP_CLOSE:
                    onCloseFrame(payload);
                    return;
                case OP_CONTINUATION:
                    fragments.write(payload, 0, payload.length);
                    if (fin) {
                        byte[] message = fragments.toByteArray();
                        int messageOp = fragmentsOpcode;
                        fragments = null;
                        complete(messageOp, message);
                    }
                    break;
                default:
                    if (fin) {
                        complete(op, payload);
                    } else {
                        fragments = new ByteArrayOutputStream(Math.max(256, payload.length * 2));
                        fragments.write(payload, 0, payload.length);
                        fragmentsOpcode = op;
                    }
                    break;
            }
        }
    }

    private void onCloseFrame(byte[] payload) throws IOException {
        int code = 1005;
        String reason = "";
        if (payload.length == 1) {
            throw new ProtocolError(1002, "close frame with a 1 byte payload");
        }
        if (payload.length >= 2) {
            code = ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF);
            if (!isValidCloseCode(code)) {
                throw new ProtocolError(1002, "invalid close code " + code);
            }
            reason = utf8(payload, 2, payload.length - 2);
        }
        serverCloseCode = code;
        serverCloseReason = reason;
        // answer the close unless this side closed first
        sendCloseQuietly(code == 1005 ? -1 : code, null);
    }

    /** Close codes a peer may send (RFC 6455 7.4, IANA registry). */
    static boolean isValidCloseCode(int code) {
        return (code >= 1000 && code <= 1003) || (code >= 1007 && code <= 1014) || (code >= 3000 && code <= 4999);
    }

    /** A whole data message arrived: inflate it when it was compressed, then deliver it. */
    private void complete(int op, byte[] wire) throws IOException {
        long done = System.nanoTime();
        byte[] message = messageCompressed ? inflate(wire) : wire;
        inbound = new Inbound(messageStartNanos, done, wire.length, message.length, messageCompressed);
        deliver(op, message);
    }

    /**
     * Inflates one message (RFC 7692 7.2.2): the payload plus {@link #DEFLATE_TAIL} through a raw
     * inflater. The inflater keeps its window for the next message unless the server agreed to
     * server_no_context_takeover; a message ending in a final block starts a new stream next time.
     * Stops with 1009 as soon as the output passes the inbound cap and with 1007 on data that does
     * not inflate.
     */
    private byte[] inflate(byte[] wire) throws ProtocolError {
        int max = maxMessageBytes;
        Inflater inf = inflater;
        if (inf == null) {
            inf = new Inflater(true);
            inflater = inf;
            inflateBuffer = new byte[INFLATE_STEP];
        } else if (deflate.serverNoContextTakeover) {
            inf.reset();
        }
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream(
                (int) Math.min(max, Math.max(256L, wire.length * 4L)));
        inflateInto(inf, wire, outBytes, max);
        if (!inf.finished()) {
            inflateInto(inf, DEFLATE_TAIL, outBytes, max);
        }
        if (inf.finished()) {
            inf.reset();
        }
        return outBytes.toByteArray();
    }

    private void inflateInto(Inflater inf, byte[] input, ByteArrayOutputStream outBytes, int max) throws ProtocolError {
        inf.setInput(input, 0, input.length);
        byte[] buf = inflateBuffer;
        while (!inf.finished()) {
            int n;
            try {
                n = inf.inflate(buf, 0, buf.length);
            } catch (DataFormatException e) {
                throw new ProtocolError(1007, "invalid compressed message");
            }
            if (n == 0) {
                if (inf.needsDictionary()) {
                    throw new ProtocolError(1007, "invalid compressed message");
                }
                // needsInput: this part is consumed
                return;
            }
            if (outBytes.size() + n > max) {
                throw new ProtocolError(1009, "inflated message of more than " + max + " bytes");
            }
            outBytes.write(buf, 0, n);
        }
    }

    private void deliver(int op, byte[] message) throws IOException {
        if (silenced) {
            return;
        }
        if (op == OP_TEXT) {
            String text = utf8(message, 0, message.length);
            try {
                listener.onText(text);
            } catch (RuntimeException e) {
                YLog.w("WebSocket listener failed on a text message", e);
            }
        } else {
            try {
                listener.onBinary(message);
            } catch (RuntimeException e) {
                YLog.w("WebSocket listener failed on a binary message", e);
            }
        }
    }

    /** Strict UTF-8; invalid text closes the connection with 1007. */
    static String utf8(byte[] b, int off, int len) throws ProtocolError {
        try {
            return Codec.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(b, off, len))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new ProtocolError(1007, "invalid UTF-8 in a text message");
        }
    }

    private int readByte() throws IOException {
        int b = in.read();
        if (b < 0) {
            throw new EOFException("connection closed without a close frame");
        }
        return b;
    }

    private byte[] readFully(int len) throws IOException {
        if (len == 0) {
            return EMPTY;
        }
        byte[] b = new byte[len];
        int n = 0;
        while (n < len) {
            int r = in.read(b, n, len - n);
            if (r < 0) {
                throw new EOFException("connection closed inside a frame");
            }
            n += r;
        }
        return b;
    }

    // ================================================================ writing

    @Override
    public void sendText(String text) throws IOException {
        byte[] b = text.getBytes(Codec.UTF_8);
        sendFrame(OP_TEXT, b, 0, b.length);
    }

    @Override
    public void sendBinary(byte[] data, int offset, int length) throws IOException {
        if (offset < 0 || length < 0 || offset + length > data.length) {
            throw new IllegalArgumentException("bad range");
        }
        sendFrame(OP_BINARY, data, offset, length);
    }

    @Override
    public void ping() throws IOException {
        sendFrame(OP_PING, EMPTY, 0, 0);
    }

    @Override
    public void setMaxMessageBytes(int bytes) {
        maxMessageBytes = bytes > 0 ? bytes : DEFAULT_MAX_MESSAGE_BYTES;
    }

    @Override
    public Inbound inbound() {
        return inbound;
    }

    @Override
    public String extensions() {
        return deflate == null ? "" : deflate.describe();
    }

    private void sendFrame(int op, byte[] data, int off, int len) throws IOException {
        synchronized (writeLock) {
            if (closeSent) {
                throw new IOException("the WebSocket is closing");
            }
            out.write(encodeFrame(op, data, off, len, randomBytes(4)));
            out.flush();
        }
    }

    /** Sends a close frame once; {@code code} -1 sends an empty close frame. Errors are ignored. */
    private void sendCloseQuietly(int code, String reason) {
        synchronized (writeLock) {
            if (closeSent) {
                return;
            }
            closeSent = true;
            try {
                byte[] payload = closePayload(code, reason);
                out.write(encodeFrame(OP_CLOSE, payload, 0, payload.length, randomBytes(4)));
                out.flush();
            } catch (IOException e) {
                YLog.d("WebSocket close frame not sent: " + e);
            }
        }
    }

    static byte[] closePayload(int code, String reason) {
        if (code < 0) {
            return EMPTY;
        }
        byte[] r = reason == null ? EMPTY : reason.getBytes(Codec.UTF_8);
        int n = Math.min(r.length, 123);
        while (n > 0 && n < r.length && (r[n] & 0xC0) == 0x80) {
            n--; // never cut a UTF-8 sequence
        }
        byte[] p = new byte[2 + n];
        p[0] = (byte) (code >> 8);
        p[1] = (byte) code;
        System.arraycopy(r, 0, p, 2, n);
        return p;
    }

    /** One final (FIN) client frame: masked payload with the 7, 16 or 64 bit length form. */
    static byte[] encodeFrame(int op, byte[] data, int off, int len, byte[] mask) {
        int header = len < 126 ? 2 : (len <= 0xFFFF ? 4 : 10);
        byte[] f = new byte[header + 4 + len];
        f[0] = (byte) (0x80 | op);
        if (len < 126) {
            f[1] = (byte) (0x80 | len);
        } else if (len <= 0xFFFF) {
            f[1] = (byte) (0x80 | 126);
            f[2] = (byte) (len >>> 8);
            f[3] = (byte) len;
        } else {
            f[1] = (byte) (0x80 | 127);
            long l = len;
            for (int i = 0; i < 8; i++) {
                f[2 + i] = (byte) (l >>> (56 - 8 * i));
            }
        }
        System.arraycopy(mask, 0, f, header, 4);
        for (int i = 0; i < len; i++) {
            f[header + 4 + i] = (byte) (data[off + i] ^ mask[i & 3]);
        }
        return f;
    }

    static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        RANDOM.nextBytes(b);
        return b;
    }

    // ================================================================ closing

    /**
     * Sends a close frame, waits up to {@link #CLOSE_WAIT_MS} for the server's close frame, then
     * closes the socket. No callbacks afterwards. Blocking: call it off the main thread.
     */
    @Override
    public void close(int code, String reason) {
        silenced = true;
        sendCloseQuietly(code, reason);
        if (Thread.currentThread() != reader) {
            try {
                readerDone.await(CLOSE_WAIT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        closeQuietly(socket);
        closeQuietly(raw);
    }

    /** Closes the TCP socket at once (no close frame); a blocked read or write fails. No callbacks afterwards. */
    @Override
    public void abort() {
        silenced = true;
        closeQuietly(raw);
        closeQuietly(socket);
    }

    /** True once the reader thread has ended. */
    boolean isReaderDone() {
        return readerDone.getCount() == 0;
    }

    private static void closeQuietly(Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {
                // closing anyway
            } catch (RuntimeException ignored) {
                // some socket implementations throw on close after a failure
            }
        }
    }
}
