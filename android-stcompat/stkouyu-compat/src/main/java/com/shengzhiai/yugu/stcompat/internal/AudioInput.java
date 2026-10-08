// SPDX-License-Identifier: Apache-2.0
package com.shengzhiai.yugu.stcompat.internal;

import android.content.Context;

/** Microphone source of 16 kHz mono PCM16. */
public interface AudioInput {

    /** Opens microphones; tests install their own factory through {@link CompatConfig}. */
    interface Factory {
        /**
         * @param audioSource {@code MediaRecorder.AudioSource} value, null for the default (MIC)
         * @throws AudioInputException when the permission is missing or the device is busy
         */
        AudioInput open(Context context, Integer audioSource) throws AudioInputException;
    }

    /** Microphone could not be opened or started (errId 60004). */
    final class AudioInputException extends Exception {
        private static final long serialVersionUID = 1L;

        public AudioInputException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    void start() throws AudioInputException;

    /** Blocking read; returns bytes read, 0 when nothing is available, negative on error. */
    int read(byte[] buffer, int offset, int length);

    /** Stops capturing and unblocks a pending read. Idempotent. */
    void stop();

    /** Releases the device. Idempotent. */
    void release();
}
