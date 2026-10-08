package com.shengzhiai.yugu;

/**
 * What a stream does with audio when the connection breaks. The platform cannot resume a server
 * session, so every reconnect opens a new server session with the same parameters and the same
 * idempotency key.
 */
public enum AudioBufferPolicy {
    /**
     * Default. Every audio byte of the session is kept, at most 10 MB. After a reconnect the SDK
     * sends the start frame again, replays the buffer, flushes audio queued while reconnecting and
     * sends end again if {@code end()} was already called. The result covers the whole audio.
     * Beyond 10 MB the next reconnect fails with 90008.
     */
    REPLAY,
    /**
     * No buffer. Audio sent while reconnecting is discarded and the new server session scores only
     * audio sent after the reconnect. {@code onReconnected} reports the bytes the result does not
     * cover. A connection lost after {@code end()} fails the session, since nothing is left to score.
     */
    DROP,
    /** No reconnect: a transport failure fails the session at once. */
    FAIL
}
