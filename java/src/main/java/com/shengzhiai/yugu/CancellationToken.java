package com.shengzhiai.yugu;

import java.util.ArrayList;
import java.util.List;

/**
 * Cancels a REST call from another thread: the running attempt is aborted, a pending retry wait
 * ends, and the call throws {@code RequestCancelledException} (90003).
 *
 * <pre>{@code
 * CancellationToken token = new CancellationToken();
 * executor.submit(() -> client.evaluate(audio, config, RequestOptions.builder().cancellation(token).build()));
 * token.cancel();
 * }</pre>
 */
public final class CancellationToken {
    private final List<Runnable> callbacks = new ArrayList<>();
    private boolean cancelled;

    /** Cancels; idempotent. */
    public void cancel() {
        List<Runnable> run;
        synchronized (this) {
            if (cancelled) {
                return;
            }
            cancelled = true;
            run = new ArrayList<>(callbacks);
            callbacks.clear();
        }
        for (Runnable r : run) {
            try {
                r.run();
            } catch (RuntimeException ignored) {
                // a callback must not prevent the others from running
            }
        }
    }

    /** @return true after {@link #cancel()} */
    public synchronized boolean isCancelled() {
        return cancelled;
    }

    /**
     * Registers a callback that runs on cancel, or immediately when already cancelled. SDK internal.
     *
     * @param r callback
     * @return a handle that unregisters the callback
     */
    public Runnable onCancel(Runnable r) {
        boolean runNow;
        synchronized (this) {
            runNow = cancelled;
            if (!runNow) {
                callbacks.add(r);
            }
        }
        if (runNow) {
            r.run();
            return () -> { };
        }
        return () -> {
            synchronized (CancellationToken.this) {
                callbacks.remove(r);
            }
        };
    }
}
