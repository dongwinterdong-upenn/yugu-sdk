package com.shengzhiai.yugu.testing;

import com.shengzhiai.yugu.SessionState;
import com.shengzhiai.yugu.StreamListener;
import com.shengzhiai.yugu.StreamSession;
import com.shengzhiai.yugu.errors.YuguException;
import com.shengzhiai.yugu.model.EvalResult;
import com.shengzhiai.yugu.model.StreamPartial;
import com.shengzhiai.yugu.model.Warning;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Records every callback in order and checks the listener guarantees: no concurrent callbacks,
 * getState() equals the announced state, onClosed last, one terminal callback.
 */
public final class RecordingListener implements StreamListener {
    public final List<String> events = new CopyOnWriteArrayList<>();
    public final List<StreamPartial> partials = new CopyOnWriteArrayList<>();
    public final List<Warning> warnings = new CopyOnWriteArrayList<>();
    public final List<YuguException> reconnectCauses = new CopyOnWriteArrayList<>();
    public final List<long[]> reconnected = new CopyOnWriteArrayList<>();
    public final AtomicBoolean overlap = new AtomicBoolean();
    public final AtomicBoolean stateMismatch = new AtomicBoolean();
    public final AtomicBoolean afterClosed = new AtomicBoolean();
    public final CountDownLatch closed = new CountDownLatch(1);
    private final AtomicInteger inside = new AtomicInteger();
    public volatile StreamSession session;
    public volatile EvalResult result;
    public volatile YuguException error;
    public volatile int closeCode;
    public volatile Runnable onStartedHook;

    private void enter(String e) {
        if (inside.incrementAndGet() > 1) {
            overlap.set(true);
        }
        if (closed.getCount() == 0) {
            afterClosed.set(true);
        }
        events.add(e);
    }

    private void exit() {
        inside.decrementAndGet();
    }

    @Override
    public void onStateChanged(SessionState oldState, SessionState newState) {
        enter("state:" + newState);
        StreamSession s = session;
        if (s != null && s.getState() != newState) {
            stateMismatch.set(true);
        }
        exit();
    }

    @Override
    public void onConnected() {
        enter("connected");
        exit();
    }

    @Override
    public void onStarted() {
        enter("started");
        Runnable h = onStartedHook;
        exit();
        if (h != null) {
            h.run();
        }
    }

    @Override
    public void onPartial(StreamPartial partial) {
        enter("partial");
        partials.add(partial);
        exit();
    }

    @Override
    public void onReconnecting(int attempt, long delayMs, YuguException cause) {
        enter("reconnecting:" + attempt);
        reconnectCauses.add(cause);
        exit();
    }

    @Override
    public void onReconnected(int attempt, long droppedBytes) {
        enter("reconnected:" + attempt);
        reconnected.add(new long[]{attempt, droppedBytes});
        exit();
    }

    @Override
    public void onWarning(Warning warning) {
        enter("warning:" + warning.getCode());
        warnings.add(warning);
        exit();
    }

    @Override
    public void onResult(EvalResult r) {
        enter("result");
        result = r;
        exit();
    }

    @Override
    public void onError(YuguException e) {
        enter("error:" + e.getCode());
        error = e;
        exit();
    }

    @Override
    public void onClosed(int code, String reason) {
        enter("closed:" + code);
        closeCode = code;
        exit();
        closed.countDown();
    }

    public boolean awaitClosed(long ms) throws InterruptedException {
        return closed.await(ms, TimeUnit.MILLISECONDS);
    }

    public List<String> states() {
        List<String> out = new ArrayList<>();
        for (String e : events) {
            if (e.startsWith("state:")) {
                out.add(e.substring(6));
            }
        }
        return out;
    }

    /** Events without state changes. */
    public List<String> callbacks() {
        List<String> out = new ArrayList<>();
        for (String e : events) {
            if (!e.startsWith("state:")) {
                out.add(e);
            }
        }
        return Collections.unmodifiableList(out);
    }

    public long count(String prefix) {
        return events.stream().filter(e -> e.startsWith(prefix)).count();
    }

    /** Asserts the listener guarantees that hold for every session. */
    public void assertGuarantees() {
        if (overlap.get()) {
            throw new AssertionError("callbacks overlapped: " + events);
        }
        if (stateMismatch.get()) {
            throw new AssertionError("getState() differed from the announced state: " + events);
        }
        if (afterClosed.get()) {
            throw new AssertionError("callback after onClosed: " + events);
        }
        if (events.isEmpty() || !events.get(events.size() - 1).startsWith("closed:")) {
            throw new AssertionError("onClosed is not the last callback: " + events);
        }
        long terminal = count("result") + count("error:");
        boolean cancelled = events.contains("state:CANCELLED");
        if (cancelled ? terminal != 0 : terminal != 1) {
            throw new AssertionError("expected " + (cancelled ? 0 : 1) + " terminal callback, got " + terminal + ": " + events);
        }
    }
}
