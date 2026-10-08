// SPDX-License-Identifier: Apache-2.0
package com.shengzhiai.yugu.stcompat.internal;

import java.io.ByteArrayOutputStream;

/**
 * Stream mode accepts raw PCM16. When the fed data starts with a RIFF/WAVE header (an app
 * feeding a WAV file), the header is dropped so the PCM is not scored as noise.
 */
public final class WavStreamStripper {
    private static final int MAX_HEADER = 4096;

    private ByteArrayOutputStream pending = new ByteArrayOutputStream();
    private boolean decided;

    /** Returns the PCM part of {@code data[0..len)}, possibly empty while a header is buffered. */
    public byte[] feed(byte[] data, int len) {
        if (decided) {
            return copy(data, len);
        }
        pending.write(data, 0, len);
        byte[] all = pending.toByteArray();
        if (all.length < 12) {
            if (!startsLikeRiff(all)) {
                return finish(all, 0);
            }
            return new byte[0];
        }
        if (!(ascii(all, 0, 4).equals("RIFF") && ascii(all, 8, 4).equals("WAVE"))) {
            return finish(all, 0);
        }
        Wav.Info info = Wav.parse(all, all.length, Long.MAX_VALUE / 2);
        if (info != null) {
            return finish(all, (int) info.dataOffset);
        }
        if (all.length >= MAX_HEADER) {
            return finish(all, 0);
        }
        return new byte[0];
    }

    /** Bytes still buffered while undecided (a stream shorter than a header). */
    public byte[] flush() {
        if (decided) {
            return new byte[0];
        }
        byte[] all = pending.toByteArray();
        boolean riff = all.length >= 4 && ascii(all, 0, 4).equals("RIFF");
        return finish(all, riff ? all.length : 0);
    }

    private byte[] finish(byte[] all, int skip) {
        decided = true;
        pending = null;
        int n = Math.max(0, all.length - skip);
        byte[] out = new byte[n];
        System.arraycopy(all, Math.min(skip, all.length), out, 0, n);
        return out;
    }

    private static boolean startsLikeRiff(byte[] b) {
        String r = "RIFF";
        for (int i = 0; i < Math.min(4, b.length); i++) {
            if (b[i] != r.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static byte[] copy(byte[] data, int len) {
        byte[] out = new byte[len];
        System.arraycopy(data, 0, out, 0, len);
        return out;
    }

    private static String ascii(byte[] b, int off, int len) {
        return new String(b, off, len, Codec.US_ASCII);
    }
}
