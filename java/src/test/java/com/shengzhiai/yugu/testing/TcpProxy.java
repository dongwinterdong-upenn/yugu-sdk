package com.shengzhiai.yugu.testing;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TCP proxy in front of the mock server that can simulate a network outage (bytes silently lost in
 * both directions, new connections hang) and a network switch (every connection reset).
 */
public final class TcpProxy implements AutoCloseable {
    private final ServerSocket server;
    private final int upstreamPort;
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private volatile boolean blackhole;
    private volatile boolean refuse;
    private volatile boolean closed;

    public TcpProxy(int upstreamPort) throws IOException {
        this.upstreamPort = upstreamPort;
        this.server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread t = new Thread(this::acceptLoop, "tcp-proxy-accept");
        t.setDaemon(true);
        t.start();
    }

    public int port() {
        return server.getLocalPort();
    }

    /** Outage: data is dropped silently and new connections are accepted but never answered. */
    public void blackhole() {
        blackhole = true;
    }

    /**
     * Network down, the way a lost interface or route looks to the client: open connections are reset
     * at once and new connections are refused until {@link #restore()}.
     */
    public void dropNetwork() {
        refuse = true;
        resetAll();
    }

    /** End of the outage; connections held during the outage are reset. */
    public void restore() {
        blackhole = false;
        refuse = false;
    }

    /** Network switch: every open connection is reset (RST). */
    public void resetAll() {
        for (Socket s : sockets) {
            reset(s);
        }
    }

    public int openConnections() {
        return sockets.size();
    }

    private void acceptLoop() {
        while (!closed) {
            Socket client;
            try {
                client = server.accept();
            } catch (IOException e) {
                return;
            }
            if (refuse) {
                reset(client);
                continue;
            }
            sockets.add(client);
            if (blackhole) {
                // hold the socket without answering; reset once the outage ends
                Thread h = new Thread(() -> {
                    while (blackhole && !closed && !client.isClosed()) {
                        FakeTransport.sleep(20);
                    }
                    reset(client);
                }, "tcp-proxy-hold");
                h.setDaemon(true);
                h.start();
                continue;
            }
            Socket upstream;
            try {
                upstream = new Socket(InetAddress.getLoopbackAddress(), upstreamPort);
            } catch (IOException e) {
                reset(client);
                continue;
            }
            sockets.add(upstream);
            pump(client, upstream);
            pump(upstream, client);
        }
    }

    private void pump(Socket from, Socket to) {
        Thread t = new Thread(() -> {
            byte[] buf = new byte[16 * 1024];
            try {
                InputStream in = from.getInputStream();
                OutputStream out = to.getOutputStream();
                int n;
                while ((n = in.read(buf)) >= 0) {
                    if (!blackhole) {
                        out.write(buf, 0, n);
                        out.flush();
                    }
                }
            } catch (IOException ignored) {
                // closed or reset
            } finally {
                close(from);
                close(to);
            }
        }, "tcp-proxy-pump");
        t.setDaemon(true);
        t.start();
    }

    private void reset(Socket s) {
        try {
            s.setSoLinger(true, 0);
        } catch (IOException ignored) {
            // already closed
        }
        close(s);
    }

    private void close(Socket s) {
        sockets.remove(s);
        try {
            s.close();
        } catch (IOException ignored) {
            // ignore
        }
    }

    @Override
    public void close() {
        closed = true;
        try {
            server.close();
        } catch (IOException ignored) {
            // ignore
        }
        for (Socket s : sockets) {
            close(s);
        }
    }
}
