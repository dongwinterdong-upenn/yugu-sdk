// SPDX-License-Identifier: Apache-2.0
package com.shengzhiai.yugu.stcompat.internal;

/**
 * Capture thread reading an {@link AudioInput} in 50 ms chunks. Pause stops the device (the
 * microphone indicator goes off) and resume starts it again. The device is released by the
 * capture thread itself once the loop has ended, so a blocking read never races a release.
 */
public final class Capture {
    /** Chunk size: 50 ms of 16 kHz mono PCM16. */
    public static final int CHUNK_BYTES = Wav.BYTES_PER_SECOND / 20;

    /** Receives chunks on the capture thread. */
    public interface Sink {
        void onPcm(byte[] data, int length);

        void onCaptureError(String reason);
    }

    private final AudioInput input;
    private final Sink sink;
    private final Thread thread;
    private final Object lock = new Object();
    private boolean running = true;
    private boolean paused;
    private volatile boolean mute;
    private boolean finished;

    public Capture(AudioInput input, Sink sink, String name) {
        this.input = input;
        this.sink = sink;
        this.thread = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, name);
        this.thread.setDaemon(true);
    }

    /** Starts the device and the thread; releases the device when it cannot be started. */
    public void start() throws AudioInput.AudioInputException {
        startDevice();
        startThread();
    }

    /** Starts the device only; audio is buffered by the device until {@link #startThread()}. */
    public void startDevice() throws AudioInput.AudioInputException {
        try {
            input.start();
        } catch (AudioInput.AudioInputException e) {
            input.release();
            synchronized (lock) {
                running = false;
                finished = true;
                lock.notifyAll();
            }
            throw e;
        }
    }

    /** Starts reading on the capture thread. */
    public void startThread() {
        thread.start();
    }

    public void setMute(boolean m) {
        mute = m;
    }

    public void pause() {
        synchronized (lock) {
            if (!running || paused) {
                return;
            }
            paused = true;
        }
        input.stop();
    }

    /** Resumes after {@link #pause()}; returns false when the device cannot be restarted. */
    public boolean resume() {
        synchronized (lock) {
            if (!running || !paused) {
                return running;
            }
        }
        try {
            input.start();
        } catch (AudioInput.AudioInputException e) {
            YLog.w("microphone could not be restarted: " + e.getMessage());
            return false;
        }
        synchronized (lock) {
            paused = false;
            lock.notifyAll();
        }
        return true;
    }

    /** Requests the end of capture; does not block. */
    public void stop() {
        synchronized (lock) {
            if (!running) {
                return;
            }
            running = false;
            lock.notifyAll();
        }
        input.stop();
    }

    /** Waits for the capture thread to finish; returns false on timeout. */
    public boolean await(long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        synchronized (lock) {
            while (!finished) {
                long left = end - System.currentTimeMillis();
                if (left <= 0) {
                    return false;
                }
                try {
                    lock.wait(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return true;
    }

    private void loop() {
        byte[] buf = new byte[CHUNK_BYTES];
        try {
            while (true) {
                synchronized (lock) {
                    while (running && paused) {
                        try {
                            lock.wait(200);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            running = false;
                        }
                    }
                    if (!running) {
                        break;
                    }
                }
                int n = input.read(buf, 0, buf.length);
                if (n < 0) {
                    boolean active;
                    synchronized (lock) {
                        active = running && !paused;
                    }
                    if (active) {
                        sink.onCaptureError("microphone read failed with code " + n);
                        break;
                    }
                    continue;
                }
                if (n == 0) {
                    sleepQuietly();
                    continue;
                }
                synchronized (lock) {
                    if (paused && running) {
                        continue;
                    }
                }
                if (mute) {
                    java.util.Arrays.fill(buf, 0, n, (byte) 0);
                }
                sink.onPcm(buf, n);
            }
        } catch (RuntimeException e) {
            YLog.e("capture failed", e);
            sink.onCaptureError("capture failed: " + e.getMessage());
        } finally {
            input.release();
            synchronized (lock) {
                running = false;
                finished = true;
                lock.notifyAll();
            }
        }
    }

    private static void sleepQuietly() {
        try {
            Thread.sleep(5);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
