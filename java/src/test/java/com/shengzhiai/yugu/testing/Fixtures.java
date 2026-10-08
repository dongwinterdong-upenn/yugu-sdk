package com.shengzhiai.yugu.testing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shengzhiai.yugu.audio.WavInfo;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Shared fixtures of the monorepo: spec/errors.json, spec/fixtures/... */
public final class Fixtures {
    private static final ObjectMapper JSON = new ObjectMapper();

    private Fixtures() {
    }

    /** @return the spec directory of the monorepo */
    public static Path specDir() {
        String p = System.getProperty("yugu.fixtures");
        Path dir = p != null ? Paths.get(p) : Paths.get("..", "spec");
        return dir.toAbsolutePath().normalize();
    }

    public static Path path(String rel) {
        return specDir().resolve(rel);
    }

    public static byte[] bytes(String rel) {
        try {
            return Files.readAllBytes(path(rel));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static String text(String rel) {
        return new String(bytes(rel), StandardCharsets.UTF_8);
    }

    public static JsonNode json(String rel) {
        try {
            return JSON.readTree(bytes(rel));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static byte[] wav(String name) {
        return bytes("fixtures/audio/" + name);
    }

    public static Path wavPath(String name) {
        return path("fixtures/audio/" + name);
    }

    /** @return the PCM samples of a WAV fixture */
    public static byte[] pcm(String name) {
        byte[] w = wav(name);
        WavInfo info = WavInfo.parse(w, w.length);
        return Arrays.copyOfRange(w, info.getDataOffset(), info.getDataOffset() + info.getDataLength());
    }

    public static List<byte[]> frames(byte[] pcm, int size) {
        List<byte[]> out = new ArrayList<>();
        for (int i = 0; i < pcm.length; i += size) {
            out.add(Arrays.copyOfRange(pcm, i, Math.min(pcm.length, i + size)));
        }
        return out;
    }

    /** Synthetic 16 bit mono PCM: a sine of the given amplitude. */
    public static byte[] sine(int sampleRate, double seconds, int amplitude) {
        int n = (int) (sampleRate * seconds);
        byte[] b = new byte[n * 2];
        for (int i = 0; i < n; i++) {
            short s = (short) Math.round(amplitude * Math.sin(2 * Math.PI * 440 * i / sampleRate));
            b[2 * i] = (byte) s;
            b[2 * i + 1] = (byte) (s >> 8);
        }
        return b;
    }
}
