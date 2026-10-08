package com.shengzhiai.yugu;

import com.shengzhiai.yugu.errors.YuguException;

/**
 * Metrics hooks (requirement C-01). All methods are optional. They run on SDK threads and must
 * return quickly; exceptions thrown by them are logged and ignored.
 *
 * <p>Operation names: {@code evaluate}, {@code evaluateCompat}, {@code tts}, {@code getReport}.
 */
public interface EventListener {
    /**
     * Before each HTTP attempt.
     *
     * @param op      operation
     * @param method  HTTP method
     * @param path    request path
     * @param attempt attempt number, from 1
     */
    default void onRequestStart(String op, String method, String path, int attempt) {
    }

    /**
     * Once per logical call, after the last attempt.
     *
     * @param op         operation
     * @param httpStatus status of the last response, 0 when none
     * @param latencyMs  total time of the call including retries and waits
     * @param attempts   attempts made
     * @param error      final error, null on success
     */
    default void onRequestEnd(String op, int httpStatus, long latencyMs, int attempts, YuguException error) {
    }

    /**
     * Before waiting for a retry.
     *
     * @param op      operation
     * @param attempt number of the attempt that failed, from 1
     * @param delayMs wait before the next attempt
     * @param error   error of the failed attempt
     */
    default void onRetry(String op, int attempt, long delayMs, YuguException error) {
    }

    /**
     * On every state change of a stream session.
     *
     * @param sessionId session id
     * @param oldState  previous state
     * @param newState  new state
     */
    default void onSessionStateChanged(String sessionId, SessionState oldState, SessionState newState) {
    }

    /**
     * After each reconnect attempt of a stream session.
     *
     * @param sessionId session id
     * @param attempt   reconnect attempt, from 1
     * @param succeeded true when the new server session started
     */
    default void onReconnect(String sessionId, int attempt, boolean succeeded) {
    }
}
