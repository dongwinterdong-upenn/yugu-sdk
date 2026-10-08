package com.shengzhiai.yugu.internal;

import java.util.ArrayDeque;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/**
 * Runs tasks one at a time, in submission order, on a shared pool. Every event of one stream session
 * goes through one instance, so the callbacks of a session never run concurrently.
 */
public final class SerialExecutor implements Executor {
    private final Executor backing;
    private final Consumer<Throwable> onError;
    private final ArrayDeque<Runnable> queue = new ArrayDeque<>();
    private boolean running;

    /**
     * @param backing pool that runs the tasks
     * @param onError receives exceptions thrown by tasks
     */
    public SerialExecutor(Executor backing, Consumer<Throwable> onError) {
        this.backing = backing;
        this.onError = onError;
    }

    @Override
    public void execute(Runnable task) {
        boolean start;
        synchronized (queue) {
            queue.add(task);
            start = !running;
            if (start) {
                running = true;
            }
        }
        if (start) {
            try {
                backing.execute(this::drain);
            } catch (RejectedExecutionException e) {
                // pool shut down: run inline so queued work, such as the final callbacks, still happens
                drain();
            }
        }
    }

    private void drain() {
        while (true) {
            Runnable next;
            synchronized (queue) {
                next = queue.poll();
                if (next == null) {
                    running = false;
                    return;
                }
            }
            try {
                next.run();
            } catch (Throwable t) {
                onError.accept(t);
            }
        }
    }
}
