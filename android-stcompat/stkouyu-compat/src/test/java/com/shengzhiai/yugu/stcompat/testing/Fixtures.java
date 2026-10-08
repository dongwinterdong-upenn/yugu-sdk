package com.shengzhiai.yugu.stcompat.testing;

import com.shengzhiai.yugu.stcompat.internal.Wav;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Shared fixtures of the monorepo (spec/fixtures, spec/errors.json, tools/mock-server). */
public final class Fixtures {
    private Fixtures() {
    }

    public static File repoRoot() {
        String p = System.getProperty("yugu.repoRoot");
        if (p == null) {
            p = new File(System.getProperty("user.dir"), "../..").getAbsolutePath();
        }
        return new File(p);
    }

    public static File file(String rel) {
        File f = new File(repoRoot(), rel);
        if (!f.isFile()) {
            throw new IllegalStateException("fixture missing: " + f);
        }
        return f;
    }

    public static byte[] bytes(String rel) {
        try {
            return Files.readAllBytes(file(rel).toPath());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String text(String rel) {
        return new String(bytes(rel), StandardCharsets.UTF_8);
    }

    /** PCM data of a fixture WAV file. */
    public static byte[] pcm(String rel) {
        byte[] wav = bytes(rel);
        Wav.Info info = Wav.parse(wav, wav.length, wav.length);
        if (info == null) {
            throw new IllegalStateException("not a WAV fixture: " + rel);
        }
        byte[] out = new byte[(int) info.dataLength];
        System.arraycopy(wav, (int) info.dataOffset, out, 0, out.length);
        return out;
    }

    /** PCM16 tone (speech-like energy) of the given length. */
    public static byte[] tone(int millis, int amplitude) {
        int samples = millis * 16;
        byte[] b = new byte[samples * 2];
        for (int i = 0; i < samples; i++) {
            short s = (short) (amplitude * Math.sin(2 * Math.PI * 440 * i / 16000.0));
            b[2 * i] = (byte) s;
            b[2 * i + 1] = (byte) (s >> 8);
        }
        return b;
    }

    public static byte[] silence(int millis) {
        return new byte[millis * 32];
    }

    public static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) {
            n += p.length;
        }
        byte[] out = new byte[n];
        int o = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, o, p.length);
            o += p.length;
        }
        return out;
    }

    /** A WAV file of the given PCM in {@code dir}. */
    public static File wavFile(File dir, String name, byte[] pcm) throws IOException {
        File f = new File(dir, name);
        byte[] h = Wav.header(16000, 1, 16, pcm.length);
        byte[] all = concat(h, pcm);
        Files.write(f.toPath(), all);
        return f;
    }
}
