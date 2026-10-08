package com.shengzhiai.yugu.stcompat.testing;

import android.content.Context;

import com.shengzhiai.yugu.stcompat.internal.AudioInput;

import java.util.concurrent.atomic.AtomicInteger;

/** Fake microphone: serves the given PCM, then silence (or nothing), with optional real-time pacing. */
public final class FakeAudio implements AudioInput.Factory {
    public final AtomicInteger opened = new AtomicInteger();
    public final AtomicInteger started = new AtomicInteger();
    public final AtomicInteger stopped = new AtomicInteger();
    public final AtomicInteger released = new AtomicInteger();
    public volatile Integer lastSource;
    private final byte[] pcm;
    private volatile boolean failOpen;
    private volatile boolean failStart;
    private volatile boolean silenceAfter = true;
    private volatile long pacingMsPerChunk;
    private volatile int readError;

    public FakeAudio(byte[] pcm) {
        this.pcm = pcm == null ? new byte[0] : pcm;
    }

    public FakeAudio failOpen() {
        failOpen = true;
        return this;
    }

    public FakeAudio failStart() {
        failStart = true;
        return this;
    }

    /** After the data: return 0 (nothing) instead of silence. */
    public FakeAudio endWithNothing() {
        silenceAfter = false;
        return this;
    }

    /** Sleeps this long per read, roughly real time for 50 ms chunks. */
    public FakeAudio pacing(long ms) {
        pacingMsPerChunk = ms;
        return this;
    }

    /** read returns this negative error once the data is consumed. */
    public FakeAudio readErrorAfterData(int code) {
        readError = code;
        return this;
    }

    @Override
    public AudioInput open(Context context, Integer audioSource) throws AudioInput.AudioInputException {
        lastSource = audioSource;
        if (failOpen) {
            throw new AudioInput.AudioInputException("fake microphone denied", null);
        }
        opened.incrementAndGet();
        return new Input();
    }

    private final class Input implements AudioInput {
        private int pos;
        private volatile boolean running;
        private boolean released;

        @Override
        public void start() throws AudioInputException {
            if (failStart) {
                throw new AudioInputException("fake microphone busy", null);
            }
            started.incrementAndGet();
            running = true;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            if (pacingMsPerChunk > 0) {
                try {
                    Thread.sleep(pacingMsPerChunk);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (!running) {
                return 0;
            }
            synchronized (this) {
                if (pos < pcm.length) {
                    int n = Math.min(length, pcm.length - pos);
                    System.arraycopy(pcm, pos, buffer, offset, n);
                    pos += n;
                    return n;
                }
            }
            if (readError < 0) {
                return readError;
            }
            if (silenceAfter) {
                java.util.Arrays.fill(buffer, offset, offset + length, (byte) 0);
                try {
                    Thread.sleep(2);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return length;
            }
            try {
                Thread.sleep(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return 0;
        }

        @Override
        public void stop() {
            if (running) {
                stopped.incrementAndGet();
            }
            running = false;
        }

        @Override
        public synchronized void release() {
            if (!released) {
                released = true;
                FakeAudio.this.released.incrementAndGet();
            }
            running = false;
        }
    }
}
