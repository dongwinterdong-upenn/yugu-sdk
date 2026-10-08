package com.shengzhiai.yugu.audio;

import com.shengzhiai.yugu.errors.InvalidParameterException;
import com.shengzhiai.yugu.model.Warning;
import com.shengzhiai.yugu.testing.Fixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Audio precheck (DESIGN 2.9) on the shared WAV fixtures and synthetic audio. */
class AudioPrecheckTest {

    @Test
    void cleanSpeechHasNoIssues() {
        AudioAnalysis a = AudioPrecheck.analyze(Fixtures.wav("zh_short.wav"));
        assertEquals(AudioFormat.WAV, a.getFormat());
        assertEquals(16000, a.getSampleRate());
        assertEquals(1, a.getChannels());
        assertEquals(16, a.getBitsPerSample());
        assertEquals(1.92, a.getDurationSeconds(), 0.001);
        assertTrue(a.isLevelsMeasured());
        assertEquals(31086, a.getPeak());
        assertEquals(4343.4, a.getRms(), 0.5);
        assertEquals(-17.55, a.getRmsDbfs(), 0.05);
        assertTrue(a.getIssues().isEmpty(), a.toString());
        assertTrue(AudioPrecheck.analyze(Fixtures.wav("en_apple.wav")).getIssues().isEmpty());
    }

    @Test
    void silentFixtureIs90103AndRejecting() {
        AudioAnalysis a = AudioPrecheck.analyze(Fixtures.wav("silent.wav"));
        assertTrue(a.hasIssue(90103), a.toString());
        assertFalse(a.hasIssue(90104), "silence is not reported twice");
        assertEquals(1, a.getRejectingIssues().size());
        assertEquals(Warning.Source.LOCAL, a.getIssues().get(0).getSource());
        assertNull(a.getIssues().get(0).getWarningCode());
        assertEquals(Double.NEGATIVE_INFINITY, a.getRmsDbfs());
    }

    @Test
    void lowVolumeFixtureIs90104WarningOnly() {
        AudioAnalysis a = AudioPrecheck.analyze(Fixtures.wav("low_volume.wav"));
        assertTrue(a.hasIssue(90104), a.toString());
        assertFalse(a.hasIssue(90103));
        assertTrue(a.getRejectingIssues().isEmpty(), "90104 never rejects");
        assertTrue(a.getRmsDbfs() < -45);
        assertTrue(a.getIssues().get(0).getMessage().contains("dBFS"));
    }

    @Test
    void shortLongAndUnsupported() {
        AudioAnalysis shortA = AudioPrecheck.analyze(WavInfo.wrapPcm16(Fixtures.sine(16000, 0.5, 8000), 16000, 1));
        assertTrue(shortA.hasIssue(90101), shortA.toString());
        AudioAnalysis rate8k = AudioPrecheck.analyze(WavInfo.wrapPcm16(Fixtures.sine(8000, 2, 8000), 8000, 1));
        assertTrue(rate8k.hasIssue(90105), rate8k.toString());
        assertFalse(rate8k.hasIssue(90101));
        byte[] eightBit = wav(1, 1, 16000, 8, new byte[32000]);
        AudioAnalysis e = AudioPrecheck.analyze(eightBit);
        assertTrue(e.hasIssue(90105));
        assertFalse(e.isLevelsMeasured());
        byte[] floatWav = wav(3, 1, 16000, 32, new byte[128000]);
        assertTrue(AudioPrecheck.analyze(floatWav).hasIssue(90105));
        // 301 s of 16 kHz mono is 9.6 MB: too long but not too large
        AudioAnalysis longA = AudioPrecheck.analyzePcm(new byte[16000 * 2 * 301], 16000, 1);
        assertTrue(longA.hasIssue(90102), longA.toString());
        // 48 kHz mono, 120 s, 11 MB: fine for an upload (limit 50 MB), too large for one stream (limit 10 MB)
        byte[] elevenMb = WavInfo.wrapPcm16(Fixtures.sine(48000, 120, 3000), 48000, 1);
        AudioAnalysis upload = AudioPrecheck.analyze(elevenMb);
        assertTrue(upload.getIssues().isEmpty(), upload.toString());
        AudioPrecheck.Accumulator stream = AudioPrecheck.accumulator(16000);
        for (int i = 0; i < elevenMb.length; i += 640) {
            stream.add(elevenMb, i, Math.min(640, elevenMb.length - i));
        }
        AudioAnalysis streamed = stream.finish();
        assertEquals(120.0, streamed.getDurationSeconds(), 0.01);
        assertTrue(streamed.hasIssue(90102), streamed.toString());
        assertTrue(streamed.getIssues().get(0).getMessage().contains("limit 10 MB"), streamed.toString());
        // 48 kHz stereo, 278 s, 51 MB: below 300 s but above the 50 MB upload limit
        byte[] big = WavInfo.wrapPcm16(new byte[51 * 1024 * 1024], 48000, 2);
        AudioAnalysis b = AudioPrecheck.analyze(big);
        assertTrue(b.getDurationSeconds() < 300, b.toString());
        assertTrue(b.hasIssue(90102), b.toString());
        assertTrue(b.getRejectingIssues().stream().anyMatch(w -> w.getCode() == 90102 && w.getMessage().contains("limit 50 MB")),
                b.toString());
    }

    @Test
    void otherContainersOnlyGetTheSizeCheck() {
        byte[] mp3 = new byte[2000];
        mp3[0] = 'I';
        mp3[1] = 'D';
        mp3[2] = '3';
        AudioAnalysis a = AudioPrecheck.analyze(mp3);
        assertEquals(AudioFormat.MP3, a.getFormat());
        assertTrue(a.getIssues().isEmpty());
        assertEquals(-1, a.getDurationSeconds());
        byte[] sync = {(byte) 0xFF, (byte) 0xFB, 0, 0};
        assertEquals(AudioFormat.MP3, AudioPrecheck.detect(sync));
        assertEquals(50L * 1024 * 1024, AudioPrecheck.MAX_UPLOAD_BYTES);
        assertEquals(10L * 1024 * 1024, AudioPrecheck.MAX_STREAM_BYTES);
        byte[] huge = new byte[(int) AudioPrecheck.MAX_UPLOAD_BYTES + 1];
        AudioAnalysis h = AudioPrecheck.analyze(huge);
        assertEquals(AudioFormat.OTHER, h.getFormat());
        assertTrue(h.hasIssue(90102));
        byte[] twelve = new byte[12 * 1024 * 1024];
        assertTrue(AudioPrecheck.analyze(twelve).getIssues().isEmpty(), "12 MB is a valid upload");
        assertTrue(AudioPrecheck.analyze(twelve, AudioPrecheck.MAX_STREAM_BYTES).hasIssue(90102));
        assertEquals(AudioFormat.OTHER, AudioPrecheck.detect(null));
    }

    @Test
    void brokenWavHeader() {
        byte[] b = Arrays.copyOf(Fixtures.wav("zh_short.wav"), 30);
        AudioAnalysis a = AudioPrecheck.analyze(b);
        assertTrue(a.hasIssue(90105), a.toString());
        assertNull(WavInfo.parse(b, b.length));
        assertNull(WavInfo.parse(new byte[4], 4));
    }

    @Test
    void wavInfoAndWrap() {
        byte[] w = Fixtures.wav("zh_short.wav");
        WavInfo info = WavInfo.parse(w, w.length);
        assertNotNull(info);
        assertEquals(78, info.getDataOffset(), "the fixtures carry a LIST chunk before the data chunk");
        assertEquals(61440, info.getDataLength());
        assertTrue(info.isPcm16());
        assertEquals(32000, info.getByteRate());
        assertEquals(WavInfo.FORMAT_PCM, info.getFormatTag());
        assertTrue(info.toString().contains("sampleRate=16000"));
        byte[] pcm = Fixtures.pcm("zh_short.wav");
        byte[] wrapped = WavInfo.wrapPcm16(pcm, 16000, 1);
        WavInfo again = WavInfo.parse(wrapped, wrapped.length);
        assertEquals(44, again.getDataOffset());
        assertEquals(pcm.length, again.getDataLength());
        assertArrayEquals(pcm, Arrays.copyOfRange(wrapped, 44, wrapped.length));
        // streaming writers put 0xFFFFFFFF as data size: clamped to the available bytes
        ByteBuffer.wrap(wrapped).order(ByteOrder.LITTLE_ENDIAN).putInt(40, -1);
        assertEquals(pcm.length, WavInfo.parse(wrapped, wrapped.length).getDataLength());
        // WAVE_FORMAT_EXTENSIBLE with PCM sub format
        byte[] ext = extensibleWav(pcm);
        WavInfo e = WavInfo.parse(ext, ext.length);
        assertTrue(e.isPcm16(), e.toString());
        assertTrue(AudioPrecheck.analyze(ext).getIssues().isEmpty());
    }

    @Test
    void streamAccumulatorMatchesWholeFileAnalysis() {
        byte[] w = Fixtures.wav("low_volume.wav");
        AudioPrecheck.Accumulator acc = AudioPrecheck.accumulator(16000);
        for (int i = 0; i < w.length; i += 7) {
            acc.add(w, i, Math.min(7, w.length - i));
        }
        AudioAnalysis s = acc.finish();
        AudioAnalysis whole = AudioPrecheck.analyze(w);
        assertEquals(AudioFormat.WAV, s.getFormat());
        assertEquals(whole.getPeak(), s.getPeak());
        assertEquals(whole.getRms(), s.getRms(), 1e-9);
        assertEquals(whole.getDurationSeconds(), s.getDurationSeconds(), 1e-9);
        assertTrue(s.hasIssue(90104));
        assertEquals(w.length, acc.getTotalBytes());

        AudioPrecheck.Accumulator raw = AudioPrecheck.accumulator(16000);
        for (byte[] f : Fixtures.frames(Fixtures.pcm("silent.wav"), 640)) {
            raw.add(f);
        }
        AudioAnalysis rs = raw.finish();
        assertEquals(AudioFormat.PCM, rs.getFormat());
        assertEquals(2.5, rs.getDurationSeconds(), 1e-9);
        assertTrue(rs.hasIssue(90103));

        AudioPrecheck.Accumulator tiny = AudioPrecheck.accumulator(16000);
        tiny.add(new byte[]{1, 2, 3});
        AudioAnalysis t = tiny.finish();
        assertTrue(t.hasIssue(90101));
        AudioAnalysis empty = AudioPrecheck.accumulator(16000).finish();
        assertTrue(empty.hasIssue(90103) && empty.hasIssue(90101), empty.toString());
    }

    @Test
    void audioSources(@TempDir Path dir) throws IOException {
        byte[] w = Fixtures.wav("zh_short.wav");
        Path f = dir.resolve("my clip.wav");
        Files.write(f, w);
        AudioSource.Loaded fromPath = AudioSource.of(f).load();
        assertEquals("my clip.wav", fromPath.getFileName());
        assertEquals("audio/wav", fromPath.getContentType());
        assertArrayEquals(w, fromPath.getData());
        assertEquals(AudioFormat.WAV, fromPath.getFormat());
        assertArrayEquals(w, AudioSource.of(f.toFile()).load().getData());
        InputStream in = new ByteArrayInputStream(w);
        AudioSource.Loaded fromStream = AudioSource.of(in).load();
        assertEquals("audio.wav", fromStream.getFileName());
        assertArrayEquals(w, fromStream.getData());
        AudioSource.Loaded fromBytes = AudioSource.of(w).withFileName("x.wav").withContentType("audio/x-wav").load();
        assertEquals("x.wav", fromBytes.getFileName());
        assertEquals("audio/x-wav", fromBytes.getContentType());
        AudioSource.Loaded pcm = AudioSource.pcm(Fixtures.pcm("zh_short.wav"), 16000).load();
        assertEquals(AudioFormat.PCM, pcm.getFormat());
        assertTrue(WavInfo.isWav(pcm.getData()), "PCM is wrapped into WAV for upload");
        assertTrue(pcm.analyze().getIssues().isEmpty());
        byte[] mp3 = {'I', 'D', '3', 4, 0};
        AudioSource.Loaded m = AudioSource.of(mp3).load();
        assertEquals("audio.mp3", m.getFileName());
        assertEquals("audio/mpeg", m.getContentType());
        assertEquals("application/octet-stream", AudioSource.of(new byte[]{9, 9, 9}).load().getContentType());

        assertThrows(InvalidParameterException.class, () -> AudioSource.of(dir.resolve("missing.wav")).load());
        assertThrows(InvalidParameterException.class, () -> AudioSource.of(dir).load());
        assertThrows(InvalidParameterException.class, () -> AudioSource.of(new byte[0]).load());
        assertThrows(InvalidParameterException.class, () -> AudioSource.of(new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("broken");
            }
        }).load());
        assertThrows(NullPointerException.class, () -> AudioSource.of((byte[]) null));
    }

    private static byte[] wav(int format, int channels, int rate, int bits, byte[] data) {
        ByteBuffer b = ByteBuffer.allocate(44 + data.length).order(ByteOrder.LITTLE_ENDIAN);
        b.put(new byte[]{'R', 'I', 'F', 'F'}).putInt(36 + data.length).put(new byte[]{'W', 'A', 'V', 'E'});
        b.put(new byte[]{'f', 'm', 't', ' '}).putInt(16).putShort((short) format).putShort((short) channels)
                .putInt(rate).putInt(rate * channels * bits / 8).putShort((short) (channels * bits / 8)).putShort((short) bits);
        b.put(new byte[]{'d', 'a', 't', 'a'}).putInt(data.length).put(data);
        return b.array();
    }

    private static byte[] extensibleWav(byte[] pcm) {
        ByteBuffer b = ByteBuffer.allocate(12 + 8 + 40 + 8 + pcm.length).order(ByteOrder.LITTLE_ENDIAN);
        b.put(new byte[]{'R', 'I', 'F', 'F'}).putInt(4 + 8 + 40 + 8 + pcm.length).put(new byte[]{'W', 'A', 'V', 'E'});
        b.put(new byte[]{'f', 'm', 't', ' '}).putInt(40).putShort((short) WavInfo.FORMAT_EXTENSIBLE).putShort((short) 1)
                .putInt(16000).putInt(32000).putShort((short) 2).putShort((short) 16)
                .putShort((short) 22).putShort((short) 16).putInt(4)
                .putShort((short) 1).put(new byte[14]);
        b.put(new byte[]{'d', 'a', 't', 'a'}).putInt(pcm.length).put(pcm);
        return b.array();
    }
}
