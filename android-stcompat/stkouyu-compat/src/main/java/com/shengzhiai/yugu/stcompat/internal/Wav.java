// SPDX-License-Identifier: Apache-2.0
package com.shengzhiai.yugu.stcompat.internal;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;

/** WAV (RIFF, PCM) header writing and parsing. */
public final class Wav {
    public static final int HEADER_SIZE = 44;
    public static final int SAMPLE_RATE = 16000;
    public static final int CHANNELS = 1;
    public static final int BITS = 16;
    /** Bytes per second of 16 kHz mono 16 bit PCM. */
    public static final int BYTES_PER_SECOND = SAMPLE_RATE * CHANNELS * BITS / 8;

    private Wav() {
    }

    /** Canonical 44 byte PCM header. */
    public static byte[] header(int sampleRate, int channels, int bits, long dataLength) {
        byte[] h = new byte[HEADER_SIZE];
        int byteRate = sampleRate * channels * bits / 8;
        int blockAlign = channels * bits / 8;
        putAscii(h, 0, "RIFF");
        putInt(h, 4, (int) Math.min(0xFFFFFFFFL, 36 + dataLength));
        putAscii(h, 8, "WAVE");
        putAscii(h, 12, "fmt ");
        putInt(h, 16, 16);
        putShort(h, 20, 1);
        putShort(h, 22, channels);
        putInt(h, 24, sampleRate);
        putInt(h, 28, byteRate);
        putShort(h, 32, blockAlign);
        putShort(h, 34, bits);
        putAscii(h, 36, "data");
        putInt(h, 40, (int) Math.min(0xFFFFFFFFL, dataLength));
        return h;
    }

    private static void putAscii(byte[] b, int off, String s) {
        for (int i = 0; i < s.length(); i++) {
            b[off + i] = (byte) s.charAt(i);
        }
    }

    private static void putInt(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >> 8);
        b[off + 2] = (byte) (v >> 16);
        b[off + 3] = (byte) (v >> 24);
    }

    private static void putShort(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >> 8);
    }

    private static int getInt(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8) | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
    }

    private static int getShort(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    /** Parsed header. */
    public static final class Info {
        public final int format;
        public final int channels;
        public final int sampleRate;
        public final int bits;
        public final long dataOffset;
        public final long dataLength;

        Info(int format, int channels, int sampleRate, int bits, long dataOffset, long dataLength) {
            this.format = format;
            this.channels = channels;
            this.sampleRate = sampleRate;
            this.bits = bits;
            this.dataOffset = dataOffset;
            this.dataLength = dataLength;
        }

        public boolean isPcm16() {
            return format == 1 && bits == 16;
        }

        public double durationSeconds() {
            int bytesPerSecond = sampleRate * channels * Math.max(1, bits / 8);
            return bytesPerSecond <= 0 ? 0 : (double) dataLength / bytesPerSecond;
        }
    }

    /**
     * Parses a RIFF/WAVE header, walking chunks until {@code data} (headers longer than 44 bytes
     * with LIST or fact chunks are common). Returns null when the bytes are not a WAV header.
     *
     * @param totalLength length of the whole file, used to clamp the data length
     */
    public static Info parse(byte[] head, int len, long totalLength) {
        if (len < 12 || !"RIFF".equals(ascii(head, 0, 4)) || !"WAVE".equals(ascii(head, 8, 4))) {
            return null;
        }
        int pos = 12;
        int format = -1;
        int channels = 0;
        int rate = 0;
        int bits = 0;
        while (pos + 8 <= len) {
            String id = ascii(head, pos, 4);
            long size = getInt(head, pos + 4) & 0xFFFFFFFFL;
            int body = pos + 8;
            if ("fmt ".equals(id) && body + 16 <= len) {
                format = getShort(head, body);
                channels = getShort(head, body + 2);
                rate = getInt(head, body + 4);
                bits = getShort(head, body + 14);
            } else if ("data".equals(id)) {
                if (format < 0) {
                    return null;
                }
                long available = Math.max(0, totalLength - body);
                long dataLen = (size == 0 || size == 0xFFFFFFFFL || size > available) ? available : size;
                return new Info(format, channels, rate, bits, body, dataLen);
            }
            pos = body + (int) Math.min(size + (size & 1), Integer.MAX_VALUE - body);
        }
        return null;
    }

    /** Parses the header of a file, or returns null when it is not a WAV file. */
    public static Info parse(File f) throws IOException {
        InputStream in = new FileInputStream(f);
        try {
            byte[] head = new byte[64 * 1024];
            int n = 0;
            int r;
            while (n < head.length && (r = in.read(head, n, head.length - n)) > 0) {
                n += r;
            }
            return parse(head, n, f.length());
        } finally {
            in.close();
        }
    }

    private static String ascii(byte[] b, int off, int len) {
        if (off + len > b.length) {
            return "";
        }
        return new String(b, off, len, Codec.US_ASCII);
    }

    /** Wraps a headerless PCM file into a WAV file. */
    public static void wrapPcm(File pcm, File wav, int sampleRate, int channels) throws IOException {
        InputStream in = new FileInputStream(pcm);
        OutputStream out = new FileOutputStream(wav);
        try {
            out.write(header(sampleRate, channels, BITS, pcm.length()));
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        } finally {
            in.close();
            out.close();
        }
    }

    /** Streaming writer: header first, sizes patched on close. */
    public static final class Writer implements Closeable {
        private final File file;
        private final RandomAccessFile raf;
        private final int sampleRate;
        private final int channels;
        private long dataLength;
        private boolean closed;

        public Writer(File file, int sampleRate, int channels) throws IOException {
            File dir = file.getAbsoluteFile().getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
                throw new IOException("cannot create " + dir);
            }
            this.file = file;
            this.sampleRate = sampleRate;
            this.channels = channels;
            this.raf = new RandomAccessFile(file, "rw");
            raf.setLength(0);
            raf.write(header(sampleRate, channels, BITS, 0));
        }

        public File file() {
            return file;
        }

        public synchronized long dataLength() {
            return dataLength;
        }

        public synchronized void write(byte[] data, int off, int len) throws IOException {
            if (closed || len <= 0) {
                return;
            }
            raf.write(data, off, len);
            dataLength += len;
        }

        @Override
        public synchronized void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            try {
                raf.seek(0);
                raf.write(header(sampleRate, channels, BITS, dataLength));
            } finally {
                raf.close();
            }
        }
    }
}
