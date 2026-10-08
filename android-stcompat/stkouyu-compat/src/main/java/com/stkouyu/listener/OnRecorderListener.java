// SPDX-License-Identifier: Apache-2.0
package com.stkouyu.listener;

/**
 * Recording and scoring callbacks, delivered on the main thread. A session ends with exactly one
 * {@link #onScore(String)} (result or error JSON), unless it was cancelled or the start failed
 * ({@link #onStartRecordFail(String)}).
 */
public abstract class OnRecorderListener {
    public abstract void onStart();

    public abstract void onStartRecordFail(String reason);

    public abstract void onPause();

    /**
     * Countdown tick, every durationInterval ms (default 100) when a duration is set.
     *
     * @param millisUntilFinished remaining recording time in ms
     * @param percentUntilFinished remaining share of the duration, 100 down to 0
     */
    public abstract void onTick(long millisUntilFinished, double percentUntilFinished);

    public abstract void onRecordEnd();

    /**
     * Voice activity and loudness while recording.
     *
     * @param vadStatus 0 not speaking yet, 1 speaking, 2 speech ended
     * @param soundIntensity loudness 0 to 100
     */
    public abstract void onRecording(int vadStatus, int soundIntensity);

    public abstract void onScore(String json);
}
