// SPDX-License-Identifier: Apache-2.0
package com.stkouyu.listener;

/** Simplified callbacks, delivered on the main thread. {@link #onRecordEnd(String)} carries the result or error JSON. */
public interface OnRecordListener {
    void onRecordStart();

    void onRecording(int vadStatus, int soundIntensity);

    void onRecordEnd(String json);
}
