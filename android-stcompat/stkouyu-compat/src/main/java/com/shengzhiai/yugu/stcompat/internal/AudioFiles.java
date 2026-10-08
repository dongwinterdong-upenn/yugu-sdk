// SPDX-License-Identifier: Apache-2.0
package com.shengzhiai.yugu.stcompat.internal;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/** Audio file checks before upload: existence, size, WAV duration, and the part Content-Type. */
public final class AudioFiles {
    private AudioFiles() {
    }

    /** Content type and file extension of an audio file. */
    public static final class Kind {
        public final String contentType;
        public final String extension;
        /** Headerless PCM that must be wrapped into WAV before upload. */
        public final boolean rawPcm;

        Kind(String contentType, String extension, boolean rawPcm) {
            this.contentType = contentType;
            this.extension = extension;
            this.rawPcm = rawPcm;
        }
    }

    /** Detects the type from magic bytes, then the extension, then the requested audioType. */
    public static Kind kind(File f, String audioType) throws IOException {
        byte[] h = new byte[12];
        int n = 0;
        InputStream in = new FileInputStream(f);
        try {
            int r;
            while (n < h.length && (r = in.read(h, n, h.length - n)) > 0) {
                n += r;
            }
        } finally {
            in.close();
        }
        if (n >= 12 && ascii(h, 0, 4).equals("RIFF") && ascii(h, 8, 4).equals("WAVE")) {
            return new Kind("audio/wav", "wav", false);
        }
        if (n >= 4 && ascii(h, 0, 4).equals("OggS")) {
            return new Kind("audio/ogg", "ogg", false);
        }
        if (n >= 5 && ascii(h, 0, 5).equals("#!AMR")) {
            return new Kind("audio/amr", "amr", false);
        }
        if (n >= 4 && ascii(h, 0, 4).equals("fLaC")) {
            return new Kind("audio/flac", "flac", false);
        }
        if (n >= 3 && ascii(h, 0, 3).equals("FLV")) {
            return new Kind("video/x-flv", "flv", false);
        }
        if (n >= 8 && ascii(h, 4, 4).equals("ftyp")) {
            return new Kind("audio/mp4", "m4a", false);
        }
        if (n >= 3 && ascii(h, 0, 3).equals("ID3")) {
            return new Kind("audio/mpeg", "mp3", false);
        }
        if (n >= 2 && (h[0] & 0xFF) == 0xFF && (h[1] & 0xF6) == 0xF0) {
            return new Kind("audio/aac", "aac", false);
        }
        if (n >= 2 && (h[0] & 0xFF) == 0xFF && (h[1] & 0xE0) == 0xE0) {
            return new Kind("audio/mpeg", "mp3", false);
        }
        String ext = extension(f.getName());
        String type = audioType == null ? "" : audioType.trim().toLowerCase(Locale.ROOT);
        if (ext.equals("pcm") || ext.equals("raw") || type.equals("pcm") || type.equals("raw")) {
            return new Kind("audio/wav", "wav", true);
        }
        if (ext.equals("mp3") || type.equals("mp3")) {
            return new Kind("audio/mpeg", "mp3", false);
        }
        if (ext.equals("wav") || ext.isEmpty() && type.equals("wav")) {
            // a .wav name without a RIFF header is raw PCM
            return new Kind("audio/wav", "wav", true);
        }
        return new Kind("application/octet-stream", ext.isEmpty() ? "bin" : ext, false);
    }

    /**
     * errId of the local pre-check, 0 when the file may be uploaded: 60001 missing or unreadable,
     * 60002 empty, 60005 WAV shorter than 1 s, 60009 larger than 50 MB or WAV longer than 300 s.
     */
    public static int precheck(File f, boolean wav) {
        if (f == null || !f.isFile() || !f.canRead()) {
            return CompatErrIds.AUDIO_FILE_MISSING;
        }
        long size = f.length();
        if (size == 0) {
            return CompatErrIds.AUDIO_EMPTY;
        }
        if (size > CompatConfig.MAX_AUDIO_BYTES) {
            return CompatErrIds.AUDIO_TOO_LARGE;
        }
        if (wav) {
            Wav.Info info;
            try {
                info = Wav.parse(f);
            } catch (IOException e) {
                return CompatErrIds.AUDIO_FILE_MISSING;
            }
            if (info != null) {
                if (info.dataLength == 0) {
                    return CompatErrIds.AUDIO_EMPTY;
                }
                if (info.durationSeconds() < 1.0) {
                    return CompatErrIds.AUDIO_TOO_SHORT;
                }
                if (info.durationSeconds() > CompatConfig.MAX_AUDIO_SECONDS) {
                    return CompatErrIds.AUDIO_TOO_LARGE;
                }
            }
        }
        return 0;
    }

    static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String ascii(byte[] b, int off, int len) {
        return new String(b, off, len, Codec.US_ASCII);
    }
}
