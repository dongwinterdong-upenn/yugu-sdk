package com.shengzhiai.yugu.audio;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** Parsed RIFF WAVE header. */
public final class WavInfo {
    /** WAVE_FORMAT_PCM. */
    public static final int FORMAT_PCM = 1;
    /** WAVE_FORMAT_EXTENSIBLE; the sub format decides whether it is PCM. */
    public static final int FORMAT_EXTENSIBLE = 0xFFFE;

    private final int formatTag;
    private final int channels;
    private final int sampleRate;
    private final int byteRate;
    private final int bitsPerSample;
    private final int dataOffset;
    private final int dataLength;

    private WavInfo(int formatTag, int channels, int sampleRate, int byteRate, int bitsPerSample, int dataOffset, int dataLength) {
        this.formatTag = formatTag;
        this.channels = channels;
        this.sampleRate = sampleRate;
        this.byteRate = byteRate;
        this.bitsPerSample = bitsPerSample;
        this.dataOffset = dataOffset;
        this.dataLength = dataLength;
    }

    /**
     * @param data bytes
     * @return true when the bytes start with a RIFF WAVE header
     */
    public static boolean isWav(byte[] data) {
        return data != null && data.length >= 12
                && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
                && data[8] == 'W' && data[9] == 'A' && data[10] == 'V' && data[11] == 'E';
    }

    /**
     * Parses the header. Chunks other than {@code fmt } and {@code data} (LIST, fact) are skipped.
     * A data size larger than the file (streaming writers) is clamped to the available bytes.
     *
     * @param data   bytes starting with the RIFF header
     * @param length number of valid bytes in {@code data}
     * @return header, or null when the bytes are not a complete WAVE header
     */
    public static WavInfo parse(byte[] data, int length) {
        if (data == null || length < 12 || !isWav(data)) {
            return null;
        }
        ByteBuffer b = ByteBuffer.wrap(data, 0, length).order(ByteOrder.LITTLE_ENDIAN);
        int pos = 12;
        int formatTag = -1;
        int channels = 0;
        int sampleRate = 0;
        int byteRate = 0;
        int bits = 0;
        while (pos + 8 <= length) {
            String id = new String(data, pos, 4, StandardCharsets.US_ASCII);
            long size = b.getInt(pos + 4) & 0xFFFFFFFFL;
            int body = pos + 8;
            if ("fmt ".equals(id)) {
                if (body + 16 > length) {
                    return null;
                }
                formatTag = b.getShort(body) & 0xFFFF;
                channels = b.getShort(body + 2) & 0xFFFF;
                sampleRate = b.getInt(body + 4);
                byteRate = b.getInt(body + 8);
                bits = b.getShort(body + 14) & 0xFFFF;
                if (formatTag == FORMAT_EXTENSIBLE && size >= 40 && body + 26 <= length) {
                    // Sub format GUID starts at offset 24; its first two bytes are the format tag.
                    formatTag = (b.getShort(body + 24) & 0xFFFF) == FORMAT_PCM ? FORMAT_PCM : FORMAT_EXTENSIBLE;
                }
            } else if ("data".equals(id)) {
                if (formatTag < 0) {
                    return null;
                }
                long avail = length - (long) body;
                int dataLen = (int) Math.max(0, Math.min(size, avail));
                return new WavInfo(formatTag, channels, sampleRate, byteRate, bits, body, dataLen);
            }
            long next = body + size + (size & 1);
            if (next > Integer.MAX_VALUE) {
                return null;
            }
            pos = (int) next;
        }
        return null;
    }

    /** @return format tag, 1 for PCM */
    public int getFormatTag() {
        return formatTag;
    }

    /** @return channel count */
    public int getChannels() {
        return channels;
    }

    /** @return sample rate in Hz */
    public int getSampleRate() {
        return sampleRate;
    }

    /** @return bytes per second */
    public int getByteRate() {
        return byteRate;
    }

    /** @return bits per sample */
    public int getBitsPerSample() {
        return bitsPerSample;
    }

    /** @return offset of the first sample byte */
    public int getDataOffset() {
        return dataOffset;
    }

    /** @return number of sample bytes */
    public int getDataLength() {
        return dataLength;
    }

    /** @return true for 16 bit PCM */
    public boolean isPcm16() {
        return formatTag == FORMAT_PCM && bitsPerSample == 16;
    }

    /** @return duration in seconds computed from the byte rate */
    public double getDurationSeconds() {
        int rate = byteRate > 0 ? byteRate : sampleRate * Math.max(1, channels) * Math.max(1, bitsPerSample / 8);
        return rate <= 0 ? 0 : dataLength / (double) rate;
    }

    /**
     * Builds a WAV file around 16 bit little endian PCM samples.
     *
     * @param pcm        samples
     * @param sampleRate sample rate in Hz
     * @param channels   channel count
     * @return WAV bytes with a 44 byte header
     */
    public static byte[] wrapPcm16(byte[] pcm, int sampleRate, int channels) {
        int dataLen = pcm.length;
        ByteBuffer b = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        b.putInt(36 + dataLen);
        b.put("WAVE".getBytes(StandardCharsets.US_ASCII));
        b.put("fmt ".getBytes(StandardCharsets.US_ASCII));
        b.putInt(16);
        b.putShort((short) FORMAT_PCM);
        b.putShort((short) channels);
        b.putInt(sampleRate);
        b.putInt(sampleRate * channels * 2);
        b.putShort((short) (channels * 2));
        b.putShort((short) 16);
        b.put("data".getBytes(StandardCharsets.US_ASCII));
        b.putInt(dataLen);
        b.put(pcm);
        return b.array();
    }

    @Override
    public String toString() {
        return "WavInfo{format=" + formatTag + ", channels=" + channels + ", sampleRate=" + sampleRate
                + ", bits=" + bitsPerSample + ", dataLength=" + dataLength + '}';
    }
}
