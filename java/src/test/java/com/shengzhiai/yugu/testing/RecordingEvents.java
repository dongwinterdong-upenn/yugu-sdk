package com.shengzhiai.yugu.testing;

import com.shengzhiai.yugu.EventListener;
import com.shengzhiai.yugu.SessionState;
import com.shengzhiai.yugu.errors.YuguException;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Records metrics events. */
public final class RecordingEvents implements EventListener {
    public final List<String> events = new CopyOnWriteArrayList<>();
    public final List<Long> retryDelays = new CopyOnWriteArrayList<>();
    public final List<YuguException> retryErrors = new CopyOnWriteArrayList<>();
    public volatile int lastAttempts;
    public volatile int lastStatus;
    public volatile YuguException lastError;

    @Override
    public void onRequestStart(String op, String method, String path, int attempt) {
        events.add("start:" + op + ":" + method + ":" + path + ":" + attempt);
    }

    @Override
    public void onRequestEnd(String op, int httpStatus, long latencyMs, int attempts, YuguException error) {
        events.add("end:" + op + ":" + httpStatus + ":" + attempts + ":" + (error == null ? "ok" : error.getCode()));
        lastAttempts = attempts;
        lastStatus = httpStatus;
        lastError = error;
    }

    @Override
    public void onRetry(String op, int attempt, long delayMs, YuguException error) {
        events.add("retry:" + op + ":" + attempt);
        retryDelays.add(delayMs);
        retryErrors.add(error);
    }

    @Override
    public void onSessionStateChanged(String sessionId, SessionState oldState, SessionState newState) {
        events.add("session:" + newState);
    }

    @Override
    public void onReconnect(String sessionId, int attempt, boolean succeeded) {
        events.add("reconnect:" + attempt + ":" + succeeded);
    }

    public long count(String prefix) {
        return events.stream().filter(e -> e.startsWith(prefix)).count();
    }
}
