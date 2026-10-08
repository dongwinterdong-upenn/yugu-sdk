// SPDX-License-Identifier: Apache-2.0
package com.stkouyu.lame;

import com.shengzhiai.yugu.stcompat.internal.YLog;

/**
 * MP3 encoder entry points of the Shengtong SDK. No MP3 encoder is bundled with the compat layer:
 * every method is a no-op, the int methods return -1, and the first call logs one WARN line.
 * Recordings are WAV: the default file name is {@code <tokenId>.wav}, and an explicit recordName
 * ending in .mp3 is kept as given while the file holds WAV data.
 */
public class SimpleLame {
    private static void unsupported() {
        YLog.warnOnce("lame", "SimpleLame: no MP3 encoder is bundled, recordings are stored as WAV");
    }

    public static void init(int sampleRate, int channel, int mode, int quality, int vbr) {
        unsupported();
    }

    public static int encodeInterleaved(short[] pcm, int samples, byte[] mp3buf) {
        unsupported();
        return -1;
    }

    public static int encode(short[] left, short[] right, int samples, byte[] mp3buf) {
        unsupported();
        return -1;
    }

    public static int flush(byte[] mp3buf) {
        unsupported();
        return -1;
    }

    public static void tags(String tag) {
        unsupported();
    }

    public static void close() {
        unsupported();
    }
}
