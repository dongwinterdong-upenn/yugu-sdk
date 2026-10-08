// SPDX-License-Identifier: Apache-2.0
package com.stkouyu.util;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;

/**
 * Countdown on the main thread that can be paused: {@link #start()} starts or resumes,
 * {@link #stop()} pauses, {@link #cancel()} ends it without {@link #onFinish(long)}.
 * {@code onTick(millisUntilFinished)} runs every interval, {@code onFinish(elapsedMillis)} once at
 * the end.
 */
public abstract class CountDownTimer {
    private static final int MSG = 1;

    private final long millisInFuture;
    private final long interval;
    private long startedAt = -1;
    private long pausedElapsed;
    private boolean running;
    private boolean cancelled;
    private boolean finished;

    private final Handler handler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            step();
        }
    };

    public CountDownTimer(long millisInFuture, long countDownInterval) {
        this.millisInFuture = millisInFuture;
        this.interval = countDownInterval > 0 ? countDownInterval : 100;
    }

    public final synchronized void cancel() {
        cancelled = true;
        running = false;
        handler.removeMessages(MSG);
    }

    public final synchronized boolean isCancelled() {
        return cancelled;
    }

    /** Pauses; {@link #start()} resumes. */
    public final synchronized CountDownTimer stop() {
        if (running) {
            pausedElapsed = elapsedLocked();
            running = false;
            handler.removeMessages(MSG);
        }
        return this;
    }

    /** Starts, or resumes after {@link #stop()}. */
    public final synchronized CountDownTimer start() {
        if (running || finished) {
            return this;
        }
        cancelled = false;
        if (millisInFuture <= 0) {
            finished = true;
            handler.post(new Runnable() {
                @Override
                public void run() {
                    onFinish(0);
                }
            });
            return this;
        }
        startedAt = SystemClock.uptimeMillis() - pausedElapsed;
        running = true;
        handler.sendMessageDelayed(handler.obtainMessage(MSG), Math.min(interval, millisInFuture - pausedElapsed));
        return this;
    }

    /** Elapsed counting time in ms (paused time excluded). */
    public final synchronized long getNowTime() {
        return elapsedLocked();
    }

    private long elapsedLocked() {
        if (!running) {
            return pausedElapsed;
        }
        return SystemClock.uptimeMillis() - startedAt;
    }

    private void step() {
        long left;
        long elapsed;
        synchronized (this) {
            if (!running || cancelled) {
                return;
            }
            elapsed = elapsedLocked();
            left = millisInFuture - elapsed;
            if (left <= 0) {
                running = false;
                finished = true;
            } else {
                handler.sendMessageDelayed(handler.obtainMessage(MSG), Math.min(interval, left));
            }
        }
        if (left <= 0) {
            onFinish(Math.min(elapsed, millisInFuture));
        } else {
            onTick(left);
        }
    }

    public abstract void onTick(long millisUntilFinished);

    public abstract void onFinish(long elapsedMillis);
}
