// SPDX-License-Identifier: Apache-2.0
package com.stkouyu.listener;

/** Receives a copy of each captured PCM chunk (16 kHz, mono, 16 bit little endian) on the main thread. */
public interface OnRecordBufferListener {
    void onRecordBuffer(byte[] buffer, int size);
}
