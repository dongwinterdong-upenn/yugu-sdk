// SPDX-License-Identifier: Apache-2.0
package com.shengzhiai.yugu.stcompat.internal;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Process;

/** {@link AudioInput} on {@link AudioRecord}: 16 kHz, mono, PCM 16 bit. */
public final class AudioRecordInput implements AudioInput {
    public static final Factory FACTORY = new Factory() {
        @Override
        public AudioInput open(Context context, Integer audioSource) throws AudioInputException {
            return AudioRecordInput.open(context, audioSource);
        }
    };

    private final AudioRecord record;
    private boolean released;

    private AudioRecordInput(AudioRecord record) {
        this.record = record;
    }

    static AudioRecordInput open(Context context, Integer audioSource) throws AudioInputException {
        if (context != null && context.checkPermission(Manifest.permission.RECORD_AUDIO, Process.myPid(),
                Process.myUid()) != PackageManager.PERMISSION_GRANTED) {
            throw new AudioInputException("RECORD_AUDIO permission not granted", null);
        }
        int min = AudioRecord.getMinBufferSize(Wav.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        int size = Math.max(min > 0 ? min * 2 : 0, Wav.BYTES_PER_SECOND / 5);
        int source = audioSource != null ? audioSource : MediaRecorder.AudioSource.MIC;
        AudioRecord r;
        try {
            r = new AudioRecord(source, Wav.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, size);
        } catch (SecurityException e) {
            throw new AudioInputException("RECORD_AUDIO permission denied", e);
        } catch (IllegalArgumentException e) {
            throw new AudioInputException("unsupported audio source " + source, e);
        }
        if (r.getState() != AudioRecord.STATE_INITIALIZED) {
            r.release();
            throw new AudioInputException("microphone unavailable (AudioRecord not initialized)", null);
        }
        return new AudioRecordInput(r);
    }

    @Override
    public synchronized void start() throws AudioInputException {
        try {
            record.startRecording();
        } catch (IllegalStateException e) {
            throw new AudioInputException("microphone unavailable: " + e.getMessage(), e);
        }
        if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
            throw new AudioInputException("microphone is used by another app", null);
        }
    }

    @Override
    public int read(byte[] buffer, int offset, int length) {
        return record.read(buffer, offset, length);
    }

    @Override
    public synchronized void stop() {
        if (released) {
            return;
        }
        try {
            if (record.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                record.stop();
            }
        } catch (IllegalStateException ignored) {
            // already stopped
        }
    }

    @Override
    public synchronized void release() {
        if (released) {
            return;
        }
        released = true;
        stop();
        record.release();
    }
}
