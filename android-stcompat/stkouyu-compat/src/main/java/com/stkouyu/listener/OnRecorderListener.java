// SPDX-License-Identifier: Apache-2.0
package com.stkouyu.listener;

/**
 * Recording and scoring callbacks, delivered on the main thread. A session ends with exactly one
 * {@link #onScore(String)} with {@code "eof":1} (result or error JSON), unless it was cancelled or
 * the start failed ({@link #onStartRecordFail(String)}). With {@code RecordSetting.setRealtime_feedback(1)}
 * {@link #onScore(String)} is also called with {@code "eof":0} intermediate results while the
 * learner reads, always before the final one.
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

    /**
     * Result JSON or error JSON with {@code "eof":1}; with realtime_feedback also intermediate
     * results with {@code "eof":0}, whose {@code result.words} lists the newly read words of the
     * reference text and {@code result.progress} every unit with {@code read} and {@code matched}.
     */
    public abstract void onScore(String json);
}
