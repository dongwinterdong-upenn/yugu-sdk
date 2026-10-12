// SPDX-License-Identifier: Apache-2.0
package com.shengzhiai.yugu.stcompat.internal;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Cancellation for one logical call: aborts the HTTP attempt in flight (by closing its
 * connection), closes registered resources such as the realtime round, and wakes up retry
 * back-off sleeps.
 */
public final class CancelToken {
    private final Object lock = new Object();
    private boolean cancelled;
    private Closeable active;
    private List<Closeable> onCancel;

    public boolean isCancelled() {
        synchronized (lock) {
            return cancelled;
        }
    }

    public void cancel() {
        Closeable c;
        List<Closeable> others;
        synchronized (lock) {
            if (cancelled) {
                return;
            }
            cancelled = true;
            c = active;
            active = null;
            others = onCancel;
            onCancel = null;
            lock.notifyAll();
        }
        closeQuietly(c);
        if (others != null) {
            for (Closeable o : others) {
                closeQuietly(o);
            }
        }
    }

    /**
     * Registers a resource closed by {@link #cancel()}, independent of the attempt slot of
     * {@link #attach}. Closes it at once and returns false when already cancelled.
     */
    public boolean onCancel(Closeable c) {
        synchronized (lock) {
            if (!cancelled) {
                if (onCancel == null) {
                    onCancel = new ArrayList<Closeable>(2);
                }
                onCancel.add(c);
                return true;
            }
        }
        closeQuietly(c);
        return false;
    }

    /** Registers the resource of the attempt in flight. Closes it at once when already cancelled. */
    public boolean attach(Closeable c) {
        synchronized (lock) {
            if (!cancelled) {
                active = c;
                return true;
            }
        }
        closeQuietly(c);
        return false;
    }

    public void detach(Closeable c) {
        synchronized (lock) {
            if (active == c) {
                active = null;
            }
        }
    }

    /** Sleeps up to {@code ms}; returns false when cancelled before or during the sleep. */
    public boolean sleep(long ms) {
        long end = System.nanoTime() + ms * 1000000L;
        synchronized (lock) {
            while (!cancelled) {
                long left = (end - System.nanoTime()) / 1000000L;
                if (left <= 0) {
                    return true;
                }
                try {
                    lock.wait(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return false;
        }
    }

    private static void closeQuietly(Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {
                // closing to abort, nothing to report
            } catch (RuntimeException ignored) {
                // HttpURLConnection.disconnect may throw on some devices
            }
        }
    }
}
