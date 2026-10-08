package com.shengzhiai.yugu;

/**
 * States of a {@link StreamSession}.
 *
 * <pre>
 * IDLE -&gt; CONNECTING -&gt; CONNECTED (server "connected") -&gt; STARTED (server "started")
 * STARTED -&gt; ENDING (end sent) -&gt; COMPLETED (final result) -&gt; CLOSED
 * CONNECTING|CONNECTED|STARTED|ENDING -&gt; RECONNECTING -&gt; CONNECTING ...
 * any non-terminal state -&gt; FAILED -&gt; CLOSED
 * any non-terminal state -&gt; CANCELLED -&gt; CLOSED
 * </pre>
 */
public enum SessionState {
    /** Created, not connected yet. */
    IDLE,
    /** Opening the WebSocket. */
    CONNECTING,
    /** Server sent "connected", start frame sent. */
    CONNECTED,
    /** Server sent "started", audio flows. */
    STARTED,
    /** End frame sent, waiting for the final result. */
    ENDING,
    /** Connection lost, waiting to reconnect. */
    RECONNECTING,
    /** Final result delivered. */
    COMPLETED,
    /** Error delivered. */
    FAILED,
    /** Cancelled by the caller or by closing the client. */
    CANCELLED,
    /** Connection released; always the last state. */
    CLOSED;

    /** @return true for COMPLETED, FAILED, CANCELLED and CLOSED */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED || this == CLOSED;
    }
}
