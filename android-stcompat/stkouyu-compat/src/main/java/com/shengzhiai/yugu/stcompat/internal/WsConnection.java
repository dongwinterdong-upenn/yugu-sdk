// SPDX-License-Identifier: Apache-2.0
package com.shengzhiai.yugu.stcompat.internal;

import java.io.IOException;
import java.util.Map;

/**
 * One WebSocket connection of the compat realtime channel. {@link WsClient} is the socket
 * implementation; tests install scripted connections through {@link CompatConfig#setWsConnector}.
 * Sends may come from any thread; listener callbacks arrive on the connection's reader thread.
 */
public interface WsConnection {

    /** Events of one connection, delivered on its reader thread. */
    interface Listener {
        /** A complete text message (continuation frames already joined). */
        void onText(String text);

        /** A complete binary message. */
        void onBinary(byte[] data);

        /** The server closed the connection with a close frame. Last callback of the connection. */
        void onClosed(int code, String reason);

        /** Transport failure, read timeout or protocol violation. Last callback of the connection. */
        void onFailure(IOException error);
    }

    /** Opens connections; {@link WsClient#CONNECTOR} by default. */
    interface Connector {
        /**
         * Connects and completes the opening handshake.
         *
         * @param url ws or wss URL with path and query
         * @param headers extra request headers of the handshake
         * @param connectTimeoutMs TCP connect timeout per resolved address, also bounds TLS and the handshake answer
         * @param readTimeoutMs read timeout of every later read
         * @throws IOException when the connection or the handshake fails
         */
        WsConnection connect(String url, Map<String, String> headers, int connectTimeoutMs, int readTimeoutMs,
                             Listener listener) throws IOException;
    }

    /** Arrival facts of one inbound data message, for the timing log (SDK 2.1.1). */
    final class Inbound {
        /** {@link System#nanoTime()} when the first frame header of the message arrived. */
        public final long firstByteNanos;
        /** {@link System#nanoTime()} when the last payload byte had been read, before inflation. */
        public final long completeNanos;
        /** Payload bytes as received: compressed when {@link #compressed}. */
        public final long wireBytes;
        /** Bytes of the message delivered to the listener, after inflation. */
        public final long dataBytes;
        /** The message was compressed with permessage-deflate (RFC 7692). */
        public final boolean compressed;

        public Inbound(long firstByteNanos, long completeNanos, long wireBytes, long dataBytes, boolean compressed) {
            this.firstByteNanos = firstByteNanos;
            this.completeNanos = completeNanos;
            this.wireBytes = wireBytes;
            this.dataBytes = dataBytes;
            this.compressed = compressed;
        }
    }

    void sendText(String text) throws IOException;

    void sendBinary(byte[] data, int offset, int length) throws IOException;

    /** Sends a protocol ping; the peer answers with a pong. */
    void ping() throws IOException;

    /**
     * Largest inbound frame and message accepted from now on, compressed and inflated; larger ones
     * close the connection with 1009.
     */
    void setMaxMessageBytes(int bytes);

    /**
     * The data message being delivered: valid inside {@link Listener#onText} and
     * {@link Listener#onBinary} on the reader thread, null when the implementation does not track it.
     */
    Inbound inbound();

    /** Extension agreed in the opening handshake, for example {@code permessage-deflate}; "" for none. */
    String extensions();

    /** Close handshake with {@code code}, bounded wait for the answer. No listener callbacks afterwards. Idempotent. */
    void close(int code, String reason);

    /** Drops the connection at once. No listener callbacks afterwards. Idempotent. */
    void abort();
}
