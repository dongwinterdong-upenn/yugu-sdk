package com.shengzhiai.yugu.audio;

import com.shengzhiai.yugu.errors.ErrorTable;
import com.shengzhiai.yugu.model.Warning;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Local audio precheck (requirement C-04). It catches audio the platform would score 0 or reject
 * before anything is uploaded or billed.
 *
 * <table>
 *   <caption>Findings</caption>
 *   <tr><th>Code</th><th>Condition</th><th>WARN mode</th><th>REJECT mode</th></tr>
 *   <tr><td>90101</td><td>duration below 1.0 s</td><td>warning</td><td>throw</td></tr>
 *   <tr><td>90102</td><td>duration above 300 s, or size above 50 MB for an upload and 10 MB for a stream</td><td>warning</td><td>throw</td></tr>
 *   <tr><td>90103</td><td>silent: peak below 200 and RMS below 30</td><td>warning</td><td>throw</td></tr>
 *   <tr><td>90104</td><td>RMS below -45 dBFS</td><td>warning</td><td>warning</td></tr>
 *   <tr><td>90105</td><td>not 16 bit PCM or sample rate below 16000</td><td>warning</td><td>throw</td></tr>
 * </table>
 *
 * <p>WAV and raw PCM get every check; other containers such as MP3 get only the size check. The
 * silence thresholds equal the platform silence guard.
 */
public final class AudioPrecheck {
    /** Shortest accepted duration in seconds. */
    public static final double MIN_DURATION_SECONDS = 1.0;
    /** Longest accepted duration in seconds. */
    public static final double MAX_DURATION_SECONDS = 300.0;
    /** Largest accepted upload of {@code evaluate} and {@code evaluateCompat}, 50 MB, the platform limit. */
    public static final long MAX_UPLOAD_BYTES = 50L * 1024 * 1024;
    /** Largest accepted audio of one stream session, 10 MB. */
    public static final long MAX_STREAM_BYTES = 10L * 1024 * 1024;
    /** Silence: peak below this value ... */
    public static final int SILENT_PEAK = 200;
    /** ... and RMS below this value, on 16 bit samples. */
    public static final double SILENT_RMS = 30;
    /** Low volume: RMS below this level in dBFS. */
    public static final double LOW_VOLUME_DBFS = -45;
    /** Lowest accepted sample rate. */
    public static final int MIN_SAMPLE_RATE = 16000;

    private AudioPrecheck() {
    }

    /**
     * @param code local code
     * @return true when {@code REJECT} mode throws for this finding
     */
    public static boolean isRejecting(int code) {
        return code == ErrorTable.AUDIO_TOO_SHORT || code == ErrorTable.AUDIO_TOO_LONG
                || code == ErrorTable.AUDIO_SILENT || code == ErrorTable.AUDIO_FORMAT_UNSUPPORTED;
    }

    /**
     * @param data payload
     * @return detected container; bytes that are neither WAV nor MP3 are {@link AudioFormat#OTHER}
     */
    public static AudioFormat detect(byte[] data) {
        if (WavInfo.isWav(data)) {
            return AudioFormat.WAV;
        }
        if (data != null && data.length >= 3 && data[0] == 'I' && data[1] == 'D' && data[2] == '3') {
            return AudioFormat.MP3;
        }
        if (data != null && data.length >= 2 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xE0) == 0xE0) {
            return AudioFormat.MP3;
        }
        return AudioFormat.OTHER;
    }

    /**
     * Analyses a complete upload with the upload size limit {@link #MAX_UPLOAD_BYTES}. WAV files get
     * every check, other containers the size check.
     *
     * @param data payload
     * @return analysis
     */
    public static AudioAnalysis analyze(byte[] data) {
        return analyze(data, MAX_UPLOAD_BYTES);
    }

    /**
     * @param data     payload
     * @param maxBytes size limit for finding 90102
     * @return analysis
     */
    public static AudioAnalysis analyze(byte[] data, long maxBytes) {
        AudioFormat f = detect(data);
        long size = data == null ? 0 : data.length;
        if (f != AudioFormat.WAV) {
            List<Warning> issues = new ArrayList<>();
            if (size > maxBytes) {
                issues.add(tooLarge(size, maxBytes));
            }
            return new AudioAnalysis(f, size, 0, 0, 0, -1, false, 0, 0, issues);
        }
        WavInfo wav = WavInfo.parse(data, data.length);
        if (wav == null) {
            List<Warning> issues = new ArrayList<>();
            issues.add(issue(ErrorTable.AUDIO_FORMAT_UNSUPPORTED, "broken WAV header"));
            if (size > maxBytes) {
                issues.add(tooLarge(size, maxBytes));
            }
            return new AudioAnalysis(AudioFormat.WAV, size, 0, 0, 0, -1, false, 0, 0, issues);
        }
        Levels lv = new Levels();
        if (wav.isPcm16()) {
            lv.add(data, wav.getDataOffset(), wav.getDataLength());
        }
        return build(AudioFormat.WAV, size, maxBytes, wav.getSampleRate(), wav.getChannels(), wav.getBitsPerSample(),
                wav.isPcm16(), wav.getDurationSeconds(), lv);
    }

    /**
     * Analyses raw 16 bit little endian PCM samples of an upload, size limit {@link #MAX_UPLOAD_BYTES}.
     *
     * @param pcm        samples
     * @param sampleRate sample rate in Hz
     * @param channels   channel count
     * @return analysis
     */
    public static AudioAnalysis analyzePcm(byte[] pcm, int sampleRate, int channels) {
        Levels lv = new Levels();
        lv.add(pcm, 0, pcm.length);
        int ch = Math.max(1, channels);
        double duration = sampleRate <= 0 ? 0 : pcm.length / (double) (sampleRate * ch * 2);
        return build(AudioFormat.PCM, pcm.length, MAX_UPLOAD_BYTES, sampleRate, ch, 16, true, duration, lv);
    }

    /**
     * Incremental analysis for streams: feed every chunk, call {@link Accumulator#finish()} at
     * {@code end()}. A WAV header at the start of the stream is recognised; otherwise the bytes are
     * taken as 16 bit mono PCM at the given sample rate. The size limit is {@link #MAX_STREAM_BYTES}.
     *
     * @param sampleRate sample rate of raw PCM
     * @return a new accumulator
     */
    public static Accumulator accumulator(int sampleRate) {
        return new Accumulator(sampleRate);
    }

    static AudioAnalysis build(AudioFormat format, long size, long maxBytes, int sampleRate, int channels, int bits,
                               boolean pcm16, double duration, Levels lv) {
        List<Warning> issues = new ArrayList<>();
        if (!pcm16 || sampleRate < MIN_SAMPLE_RATE) {
            issues.add(issue(ErrorTable.AUDIO_FORMAT_UNSUPPORTED,
                    String.format(Locale.ROOT, "%d bit, %d Hz", bits, sampleRate)));
        }
        if (duration >= 0 && duration < MIN_DURATION_SECONDS) {
            issues.add(issue(ErrorTable.AUDIO_TOO_SHORT, String.format(Locale.ROOT, "duration %.2f s", duration)));
        }
        if (duration > MAX_DURATION_SECONDS) {
            issues.add(issue(ErrorTable.AUDIO_TOO_LONG, String.format(Locale.ROOT, "duration %.1f s", duration)));
        } else if (size > maxBytes) {
            issues.add(tooLarge(size, maxBytes));
        }
        boolean measured = pcm16 && lv.samples > 0;
        double rms = lv.rms();
        if (measured) {
            if (lv.peak < SILENT_PEAK && rms < SILENT_RMS) {
                issues.add(issue(ErrorTable.AUDIO_SILENT, String.format(Locale.ROOT, "peak %d, rms %.1f", lv.peak, rms)));
            } else {
                double dbfs = rms <= 0 ? Double.NEGATIVE_INFINITY : 20 * Math.log10(rms / 32768.0);
                if (dbfs < LOW_VOLUME_DBFS) {
                    issues.add(issue(ErrorTable.AUDIO_LOW_VOLUME, String.format(Locale.ROOT, "rms %.1f dBFS", dbfs)));
                }
            }
        } else if (pcm16 && lv.samples == 0) {
            issues.add(issue(ErrorTable.AUDIO_SILENT, "no samples"));
        }
        return new AudioAnalysis(format, size, sampleRate, channels, bits, duration, measured, lv.peak, rms, issues);
    }

    static Warning tooLarge(long size, long maxBytes) {
        return issue(ErrorTable.AUDIO_TOO_LONG, String.format(Locale.ROOT, "size %d bytes, limit %d MB",
                size, maxBytes / (1024 * 1024)));
    }

    static Warning issue(int code, String detail) {
        ErrorTable.Entry e = ErrorTable.LOCAL.get(code);
        return new Warning(code, e.message + " (" + detail + ")", Warning.Source.LOCAL);
    }

    /** Running peak and RMS of 16 bit little endian samples. */
    static final class Levels {
        long samples;
        int peak;
        double sumSquares;
        private int carry = -1;

        void add(byte[] b, int off, int len) {
            int i = off;
            int end = off + len;
            if (carry >= 0 && i < end) {
                sample((short) ((carry & 0xFF) | (b[i] << 8)));
                carry = -1;
                i++;
            }
            for (; i + 1 < end; i += 2) {
                sample((short) ((b[i] & 0xFF) | (b[i + 1] << 8)));
            }
            if (i < end) {
                carry = b[i] & 0xFF;
            }
        }

        private void sample(short s) {
            int a = Math.abs((int) s);
            if (a > peak) {
                peak = a;
            }
            sumSquares += (double) s * s;
            samples++;
        }

        double rms() {
            return samples == 0 ? 0 : Math.sqrt(sumSquares / samples);
        }
    }

    /** Incremental precheck for streamed audio. Not thread safe. */
    public static final class Accumulator {
        private static final int HEADER_PROBE = 4096;
        private final int sampleRate;
        private final Levels levels = new Levels();
        private byte[] head = new byte[0];
        private boolean decided;
        private WavInfo wav;
        private long totalBytes;
        private long sampleBytes;

        Accumulator(int sampleRate) {
            this.sampleRate = sampleRate;
        }

        /**
         * @param chunk audio bytes
         */
        public void add(byte[] chunk) {
            add(chunk, 0, chunk.length);
        }

        /**
         * @param chunk  buffer
         * @param offset start
         * @param length byte count
         */
        public void add(byte[] chunk, int offset, int length) {
            totalBytes += length;
            if (decided) {
                feed(chunk, offset, length);
                return;
            }
            byte[] n = new byte[head.length + length];
            System.arraycopy(head, 0, n, 0, head.length);
            System.arraycopy(chunk, offset, n, head.length, length);
            head = n;
            decide(false);
        }

        private void decide(boolean force) {
            if (head.length < 12 && !force) {
                return;
            }
            if (WavInfo.isWav(head)) {
                WavInfo w = WavInfo.parse(head, head.length);
                if (w != null) {
                    wav = w;
                    decided = true;
                    feed(head, w.getDataOffset(), head.length - w.getDataOffset());
                    head = new byte[0];
                    return;
                }
                if (head.length < HEADER_PROBE && !force) {
                    return;
                }
            }
            decided = true;
            byte[] h = head;
            head = new byte[0];
            feed(h, 0, h.length);
        }

        private void feed(byte[] b, int off, int len) {
            sampleBytes += len;
            if (wav == null || wav.isPcm16()) {
                levels.add(b, off, len);
            }
        }

        /** @return bytes fed so far */
        public long getTotalBytes() {
            return totalBytes;
        }

        /** @return analysis of everything fed so far */
        public AudioAnalysis finish() {
            if (!decided) {
                decide(true);
            }
            if (wav != null) {
                int rate = wav.getByteRate() > 0 ? wav.getByteRate()
                        : wav.getSampleRate() * Math.max(1, wav.getChannels()) * Math.max(1, wav.getBitsPerSample() / 8);
                double duration = rate <= 0 ? 0 : sampleBytes / (double) rate;
                return build(AudioFormat.WAV, totalBytes, MAX_STREAM_BYTES, wav.getSampleRate(), wav.getChannels(),
                        wav.getBitsPerSample(), wav.isPcm16(), duration, levels);
            }
            double duration = sampleRate <= 0 ? 0 : sampleBytes / (double) (sampleRate * 2);
            return build(AudioFormat.PCM, totalBytes, MAX_STREAM_BYTES, sampleRate, 1, 16, true, duration, levels);
        }
    }
}
