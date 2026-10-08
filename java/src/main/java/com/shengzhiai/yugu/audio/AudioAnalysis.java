package com.shengzhiai.yugu.audio;

import com.shengzhiai.yugu.model.Warning;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Result of the local audio precheck: detected format, levels and the findings (local codes 90101
 * to 90105).
 */
public final class AudioAnalysis {
    private final AudioFormat format;
    private final long sizeBytes;
    private final int sampleRate;
    private final int channels;
    private final int bitsPerSample;
    private final double durationSeconds;
    private final boolean levelsMeasured;
    private final int peak;
    private final double rms;
    private final List<Warning> issues;

    AudioAnalysis(AudioFormat format, long sizeBytes, int sampleRate, int channels, int bitsPerSample,
                  double durationSeconds, boolean levelsMeasured, int peak, double rms, List<Warning> issues) {
        this.format = format;
        this.sizeBytes = sizeBytes;
        this.sampleRate = sampleRate;
        this.channels = channels;
        this.bitsPerSample = bitsPerSample;
        this.durationSeconds = durationSeconds;
        this.levelsMeasured = levelsMeasured;
        this.peak = peak;
        this.rms = rms;
        this.issues = Collections.unmodifiableList(new ArrayList<>(issues));
    }

    /** @return detected container */
    public AudioFormat getFormat() {
        return format;
    }

    /** @return payload size in bytes */
    public long getSizeBytes() {
        return sizeBytes;
    }

    /** @return sample rate, 0 when unknown */
    public int getSampleRate() {
        return sampleRate;
    }

    /** @return channels, 0 when unknown */
    public int getChannels() {
        return channels;
    }

    /** @return bits per sample, 0 when unknown */
    public int getBitsPerSample() {
        return bitsPerSample;
    }

    /** @return duration in seconds, -1 when unknown (compressed formats) */
    public double getDurationSeconds() {
        return durationSeconds;
    }

    /** @return true when peak and RMS were measured (16 bit PCM only) */
    public boolean isLevelsMeasured() {
        return levelsMeasured;
    }

    /** @return absolute peak of the 16 bit samples */
    public int getPeak() {
        return peak;
    }

    /** @return RMS of the 16 bit samples */
    public double getRms() {
        return rms;
    }

    /** @return RMS in dBFS, negative infinity for digital silence */
    public double getRmsDbfs() {
        return rms <= 0 ? Double.NEGATIVE_INFINITY : 20 * Math.log10(rms / 32768.0);
    }

    /** @return every finding, never null */
    public List<Warning> getIssues() {
        return issues;
    }

    /** @return findings that make {@code REJECT} mode throw before upload: 90101, 90102, 90103, 90105 */
    public List<Warning> getRejectingIssues() {
        List<Warning> out = new ArrayList<>();
        for (Warning w : issues) {
            if (AudioPrecheck.isRejecting(w.getCode())) {
                out.add(w);
            }
        }
        return out;
    }

    /**
     * @param code local code
     * @return true when the analysis found this issue
     */
    public boolean hasIssue(int code) {
        for (Warning w : issues) {
            if (w.getCode() == code) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return "AudioAnalysis{format=" + format + ", size=" + sizeBytes + ", sampleRate=" + sampleRate
                + ", channels=" + channels + ", bits=" + bitsPerSample + ", duration=" + durationSeconds
                + ", peak=" + peak + ", rms=" + Math.round(rms * 10) / 10.0 + ", issues=" + issues + '}';
    }
}
