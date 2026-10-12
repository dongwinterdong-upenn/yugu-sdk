package com.shengzhiai.yugu;

import com.shengzhiai.yugu.errors.YuguException;
import com.shengzhiai.yugu.model.EvalResult;
import com.shengzhiai.yugu.model.StreamPartial;
import com.shengzhiai.yugu.model.Warning;

/**
 * Callbacks of a {@link StreamSession}. Only {@link #onResult} and {@link #onError} must be
 * implemented.
 *
 * <p>Guarantees:
 * <ul>
 *   <li>exactly one of {@code onResult} or {@code onError} per session, unless {@code cancel()} came
 *       first, then neither;</li>
 *   <li>{@code onClosed} is always the last callback;</li>
 *   <li>callbacks of one session never run concurrently and arrive in order;</li>
 *   <li>{@link StreamSession#getState()} equals the state announced by the last {@code onStateChanged}.</li>
 * </ul>
 *
 * <p>Order on a reconnect: {@code onStateChanged(RECONNECTING)}, {@code onReconnecting}, then
 * {@code CONNECTING}, {@code CONNECTED}, {@code onConnected}, {@code STARTED}, {@code onStarted},
 * {@code onReconnected}. Callbacks run on SDK threads; do not block them for long.
 */
public interface StreamListener {
    /**
     * @param oldState previous state
     * @param newState new state
     */
    default void onStateChanged(SessionState oldState, SessionState newState) {
    }

    /** The server accepted the connection (each connection, reconnects included). */
    default void onConnected() {
    }

    /** The server session started; audio flows (each connection, reconnects included). */
    default void onStarted() {
    }

    /**
     * Intermediate frame of a stream that requested realtime feedback, native
     * ({@code EvaluateConfig.realtimeFeedback}) or compat ({@code CompatConfig.realtimeFeedback}): the
     * reading progress while audio arrives, see {@link StreamPartial}. Frames also arrive in ENDING,
     * after {@code end()}, until the final result, which always comes last.
     *
     * <p>After a reconnect the new server session computes the progress again from zero. With the
     * {@code REPLAY} buffer policy it catches up while the audio is replayed, and a frame with progress is
     * delivered only when its {@code readUnits} and {@code matchedUnits} are both at least those of the
     * last delivered frame of this session, so the progress shown never goes back; dropped frames are
     * logged at DEBUG. With {@code DROP} the new server session scores only the later audio, so the
     * remembered progress is reset at every successful reconnect and the progress starts again from zero.
     * Frames without progress are always delivered.
     *
     * @param partial progress
     */
    default void onPartial(StreamPartial partial) {
    }

    /**
     * The connection was lost and a reconnect is scheduled.
     *
     * @param attempt reconnect attempt, from 1
     * @param delayMs wait before connecting again
     * @param cause   why the connection was lost
     */
    default void onReconnecting(int attempt, long delayMs, YuguException cause) {
    }

    /**
     * A reconnect succeeded and the new server session started.
     *
     * @param attempt      reconnect attempt that succeeded
     * @param droppedBytes audio bytes the result will not cover: 0 with {@code REPLAY}, the audio
     *                     sent before the new server session with {@code DROP}
     */
    default void onReconnected(int attempt, long droppedBytes) {
    }

    /**
     * Finding of the local audio precheck at {@code end()} in WARN mode.
     *
     * @param warning finding, codes 90101 to 90105
     */
    default void onWarning(Warning warning) {
    }

    /**
     * Final result.
     *
     * @param result result
     */
    void onResult(EvalResult result);

    /**
     * Terminal error: the server rejected the session, the reconnect attempts were exhausted
     * (90006), the result timed out with reconnects disabled, the precheck rejected the audio, and so on.
     *
     * @param error error
     */
    void onError(YuguException error);

    /**
     * Last callback of the session.
     *
     * @param code   WebSocket close code: 1000 after a result or cancel, the server code when the
     *               server closed the connection, 1006 when the connection was lost
     * @param reason reason
     */
    default void onClosed(int code, String reason) {
    }
}
