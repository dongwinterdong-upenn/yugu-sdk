package com.shengzhiai.yugu.internal;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * WebSocket transport used by stream sessions. The default implementation is {@link JdkWsTransport};
 * tests plug in a scripted fake.
 */
public interface WsTransport {
    /**
     * Opens a connection. {@link Listener#onOpen(Connection)} is called before any other listener
     * method; a failed handshake completes the returned future exceptionally and calls nothing else.
     *
     * @param uri            ws or wss URI including the query
     * @param headers        extra handshake headers
     * @param connectTimeout handshake timeout
     * @param listener       receives events of this connection, one at a time
     * @return completes when the handshake finished
     */
    CompletableFuture<Void> connect(URI uri, Map<String, String> headers, Duration connectTimeout, Listener listener);

    /** Events of one connection. */
    interface Listener {
        /** @param connection the open connection */
        void onOpen(Connection connection);

        /** @param text complete text message */
        void onText(String text);

        /** A pong arrived. */
        void onPong();

        /**
         * The server closed the connection.
         *
         * @param code   close code
         * @param reason reason
         */
        void onClose(int code, String reason);

        /** @param error the connection failed */
        void onError(Throwable error);
    }

    /** An open connection. Sends of text and binary messages are queued in order. */
    interface Connection {
        /** @param text message @return completes when sent */
        CompletableFuture<Void> sendText(String text);

        /** @param data message, not modified afterwards @return completes when sent */
        CompletableFuture<Void> sendBinary(byte[] data);

        /** @return completes when the ping was sent */
        CompletableFuture<Void> sendPing();

        /**
         * Sends a close frame after the queued messages.
         *
         * @param code   close code
         * @param reason reason
         * @return completes when the close frame was sent
         */
        CompletableFuture<Void> close(int code, String reason);

        /** Drops the connection at once. */
        void abort();
    }
}
