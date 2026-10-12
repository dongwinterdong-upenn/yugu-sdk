// SPDX-License-Identifier: Apache-2.0
package com.stkouyu.listener;

/**
 * Simplified callbacks, delivered on the main thread. {@link #onRecordEnd(String)} carries the result
 * or error JSON. There is no callback for the eof 0 intermediate results of realtime_feedback; they go
 * to {@link OnRecorderListener#onScore(String)} only.
 */
public interface OnRecordListener {
    void onRecordStart();

    void onRecording(int vadStatus, int soundIntensity);

    void onRecordEnd(String json);
}
