// SPDX-License-Identifier: Apache-2.0
package com.shengzhiai.yugu.stcompat.internal;

/**
 * Local energy VAD on 16 kHz mono PCM16, analysed in 10 ms frames.
 *
 * <ul>
 * <li>status 0: no speech yet; 1: speaking; 2: speech ended</li>
 * <li>speech starts after {@link #START_FRAMES} consecutive voiced frames (RMS at or above the threshold)</li>
 * <li>speech ends after {@code seek} silent frames (10 ms units, default 60 = 600 ms); during the first
 * {@code refLength} frames after speech started the status stays 1</li>
 * <li>sound intensity 0 to 100 maps the frame RMS from -60 dBFS to 0 dBFS</li>
 * </ul>
 */
public final class EnergyVad {
    public static final int FRAME_BYTES = 320;
    public static final int START_FRAMES = 3;
    public static final int DEFAULT_SEEK = 60;
    /** RMS on the 16 bit scale counted as voice (about -38 dBFS). */
    public static final double DEFAULT_THRESHOLD = 400.0;

    private final int seekFrames;
    private final int refLengthFrames;
    private final double threshold;
    private final byte[] carry = new byte[FRAME_BYTES];
    private int carryLen;
    private int status;
    private int voicedRun;
    private int silentRun;
    private int speechFrames;
    private int intensity;
    private int peakIntensity;

    public EnergyVad(Integer seek, Integer refLength) {
        this(seek, refLength, DEFAULT_THRESHOLD);
    }

    public EnergyVad(Integer seek, Integer refLength, double threshold) {
        this.seekFrames = seek != null && seek > 0 ? seek : DEFAULT_SEEK;
        this.refLengthFrames = refLength != null && refLength > 0 ? refLength : 0;
        this.threshold = threshold;
    }

    public synchronized int status() {
        return status;
    }

    /** Intensity of the last complete frame. */
    public synchronized int intensity() {
        return intensity;
    }

    /** Highest intensity since the last call, then reset. */
    public synchronized int takePeakIntensity() {
        int p = peakIntensity;
        peakIntensity = 0;
        return p;
    }

    /** Analyses PCM16 little endian bytes; returns the status after the last complete frame. */
    public synchronized int process(byte[] data, int off, int len) {
        int pos = off;
        int end = off + len;
        if (carryLen > 0) {
            int take = Math.min(FRAME_BYTES - carryLen, len);
            System.arraycopy(data, pos, carry, carryLen, take);
            carryLen += take;
            pos += take;
            if (carryLen == FRAME_BYTES) {
                frame(carry, 0);
                carryLen = 0;
            }
        }
        while (pos + FRAME_BYTES <= end) {
            frame(data, pos);
            pos += FRAME_BYTES;
        }
        if (pos < end) {
            carryLen = end - pos;
            System.arraycopy(data, pos, carry, 0, carryLen);
        }
        return status;
    }

    private void frame(byte[] b, int off) {
        double rms = rms(b, off, FRAME_BYTES);
        intensity = intensityOf(rms);
        if (intensity > peakIntensity) {
            peakIntensity = intensity;
        }
        boolean voiced = rms >= threshold;
        if (status == 0) {
            voicedRun = voiced ? voicedRun + 1 : 0;
            if (voicedRun >= START_FRAMES) {
                status = 1;
                speechFrames = voicedRun;
                silentRun = 0;
            }
        } else if (status == 1) {
            speechFrames++;
            silentRun = voiced ? 0 : silentRun + 1;
            if (silentRun >= seekFrames && speechFrames > refLengthFrames) {
                status = 2;
            }
        }
    }

    /** RMS of PCM16 little endian samples. */
    public static double rms(byte[] b, int off, int len) {
        int samples = len / 2;
        if (samples == 0) {
            return 0;
        }
        double sum = 0;
        for (int i = 0; i < samples; i++) {
            int s = (short) ((b[off + 2 * i] & 0xFF) | (b[off + 2 * i + 1] << 8));
            sum += (double) s * s;
        }
        return Math.sqrt(sum / samples);
    }

    /** 0 to 100 for -60 dBFS to 0 dBFS. */
    public static int intensityOf(double rms) {
        if (rms <= 0) {
            return 0;
        }
        double dbfs = 20.0 * Math.log10(rms / 32768.0);
        double v = (dbfs + 60.0) * 100.0 / 60.0;
        if (v < 0) {
            return 0;
        }
        return v > 100 ? 100 : (int) Math.round(v);
    }
}
