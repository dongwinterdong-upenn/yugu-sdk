package com.shengzhiai.yugu.audio;

import com.shengzhiai.yugu.errors.ErrorTable;
import com.shengzhiai.yugu.errors.YuguErrors;
import com.shengzhiai.yugu.errors.YuguException;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Audio supplied by the caller (requirement C-06): bytes, a file, a path, a stream or raw PCM.
 * The SDK reads the audio once per call, so retries upload the same bytes.
 *
 * <p>Streams are read to the end but not closed; the caller owns them.
 */
public final class AudioSource {
    /** Upper bound for audio read into memory, larger inputs are rejected before any I/O. */
    public static final long MAX_READ_BYTES = 64L * 1024 * 1024;

    private enum Kind { BYTES, FILE, STREAM, PCM }

    private final Kind kind;
    private final byte[] bytes;
    private final Path path;
    private final InputStream stream;
    private final int sampleRate;
    private final int channels;
    private final String fileName;
    private final String contentType;

    private AudioSource(Kind kind, byte[] bytes, Path path, InputStream stream, int sampleRate, int channels,
                        String fileName, String contentType) {
        this.kind = kind;
        this.bytes = bytes;
        this.path = path;
        this.stream = stream;
        this.sampleRate = sampleRate;
        this.channels = channels;
        this.fileName = fileName;
        this.contentType = contentType;
    }

    /**
     * @param audio WAV or MP3 bytes
     * @return source
     */
    public static AudioSource of(byte[] audio) {
        return new AudioSource(Kind.BYTES, Objects.requireNonNull(audio, "audio"), null, null, 0, 0, null, null);
    }

    /**
     * @param file WAV or MP3 file
     * @return source
     */
    public static AudioSource of(File file) {
        return of(Objects.requireNonNull(file, "file").toPath());
    }

    /**
     * @param path WAV or MP3 file
     * @return source
     */
    public static AudioSource of(Path path) {
        Objects.requireNonNull(path, "path");
        Path name = path.getFileName();
        return new AudioSource(Kind.FILE, null, path, null, 0, 0, name == null ? null : name.toString(), null);
    }

    /**
     * @param in stream with WAV or MP3 content; read to the end, not closed
     * @return source
     */
    public static AudioSource of(InputStream in) {
        return new AudioSource(Kind.STREAM, null, null, Objects.requireNonNull(in, "in"), 0, 0, null, null);
    }

    /**
     * Raw 16 bit little endian mono PCM. The SDK wraps it into a WAV container for upload.
     *
     * @param pcm        samples
     * @param sampleRate sample rate in Hz, 16000 recommended
     * @return source
     */
    public static AudioSource pcm(byte[] pcm, int sampleRate) {
        return new AudioSource(Kind.PCM, Objects.requireNonNull(pcm, "pcm"), null, null, sampleRate, 1, "audio.wav", "audio/wav");
    }

    /**
     * @param name file name for the multipart part
     * @return a copy with the file name
     */
    public AudioSource withFileName(String name) {
        return new AudioSource(kind, bytes, path, stream, sampleRate, channels, name, contentType);
    }

    /**
     * @param type content type of the multipart part
     * @return a copy with the content type
     */
    public AudioSource withContentType(String type) {
        return new AudioSource(kind, bytes, path, stream, sampleRate, channels, fileName, type);
    }

    /**
     * Reads the audio. Called once per logical call.
     *
     * @return loaded audio
     * @throws YuguException {@code InvalidParameterException} 90010 when the file is missing,
     *                       unreadable, empty or larger than {@link #MAX_READ_BYTES}
     */
    public Loaded load() throws YuguException {
        byte[] data;
        switch (kind) {
            case FILE:
                try {
                    if (!Files.isRegularFile(path)) {
                        throw invalid("audio file not found: " + path, null);
                    }
                    if (Files.size(path) > MAX_READ_BYTES) {
                        throw invalid("audio file larger than " + MAX_READ_BYTES + " bytes: " + path, null);
                    }
                    data = Files.readAllBytes(path);
                } catch (NoSuchFileException e) {
                    throw invalid("audio file not found: " + path, e);
                } catch (IOException e) {
                    throw invalid("audio file not readable: " + path + ": " + e.getMessage(), e);
                }
                break;
            case STREAM:
                data = readAll(stream);
                break;
            case PCM:
                AudioAnalysis pcmAnalysis = AudioPrecheck.analyzePcm(bytes, sampleRate, channels);
                return new Loaded(WavInfo.wrapPcm16(bytes, sampleRate, channels), fileName, contentType,
                        AudioFormat.PCM, pcmAnalysis);
            default:
                data = bytes;
        }
        if (data.length == 0) {
            throw invalid("audio is empty", null);
        }
        AudioFormat f = AudioPrecheck.detect(data);
        String name = fileName != null ? fileName : f == AudioFormat.MP3 ? "audio.mp3" : "audio.wav";
        String type = contentType != null ? contentType
                : f == AudioFormat.WAV ? "audio/wav" : f == AudioFormat.MP3 ? "audio/mpeg" : "application/octet-stream";
        return new Loaded(data, name, type, f, null);
    }

    private static byte[] readAll(InputStream in) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
            byte[] buf = new byte[16 * 1024];
            long total = 0;
            int n;
            while ((n = in.read(buf)) >= 0) {
                total += n;
                if (total > MAX_READ_BYTES) {
                    throw invalid("audio stream larger than " + MAX_READ_BYTES + " bytes", null);
                }
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw invalid("audio stream not readable: " + e.getMessage(), e);
        }
    }

    private static YuguException invalid(String message, Throwable cause) {
        return YuguErrors.local(ErrorTable.INVALID_ARGUMENT, message, cause);
    }

    /** Audio read into memory, ready for upload. */
    public static final class Loaded {
        private final byte[] data;
        private final String fileName;
        private final String contentType;
        private final AudioFormat format;
        private final AudioAnalysis pcmAnalysis;

        Loaded(byte[] data, String fileName, String contentType, AudioFormat format, AudioAnalysis pcmAnalysis) {
            this.data = data;
            this.fileName = fileName;
            this.contentType = contentType;
            this.format = format;
            this.pcmAnalysis = pcmAnalysis;
        }

        /** @return bytes to upload */
        public byte[] getData() {
            return data;
        }

        /** @return multipart file name */
        public String getFileName() {
            return fileName;
        }

        /** @return multipart content type */
        public String getContentType() {
            return contentType;
        }

        /** @return format the caller supplied */
        public AudioFormat getFormat() {
            return format;
        }

        /** @return precheck analysis of the payload */
        public AudioAnalysis analyze() {
            return pcmAnalysis != null ? pcmAnalysis : AudioPrecheck.analyze(data);
        }
    }
}
