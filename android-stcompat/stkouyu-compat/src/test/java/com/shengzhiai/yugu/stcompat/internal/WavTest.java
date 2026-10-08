package com.shengzhiai.yugu.stcompat.internal;

import com.shengzhiai.yugu.stcompat.testing.Fixtures;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class WavTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void headerLayout() {
        byte[] h = Wav.header(16000, 1, 16, 32000);
        assertEquals(44, h.length);
        assertEquals("RIFF", new String(h, 0, 4));
        assertEquals("WAVE", new String(h, 8, 4));
        assertEquals("fmt ", new String(h, 12, 4));
        assertEquals("data", new String(h, 36, 4));
        Wav.Info info = Wav.parse(h, h.length, 44 + 32000);
        assertNotNull(info);
        assertTrue(info.isPcm16());
        assertEquals(16000, info.sampleRate);
        assertEquals(1, info.channels);
        assertEquals(44, info.dataOffset);
        assertEquals(32000, info.dataLength);
        assertEquals(1.0, info.durationSeconds(), 1e-9);
    }

    @Test
    public void fixtureWithExtraChunksBeforeData() throws IOException {
        Wav.Info info = Wav.parse(Fixtures.file("spec/fixtures/audio/zh_short.wav"));
        assertNotNull(info);
        assertEquals(78, info.dataOffset);
        assertEquals(61440, info.dataLength);
        assertEquals(1.92, info.durationSeconds(), 1e-9);
        Wav.Info apple = Wav.parse(Fixtures.file("spec/fixtures/audio/en_apple.wav"));
        assertEquals(2.48, apple.durationSeconds(), 0.01);
    }

    @Test
    public void notWav() throws IOException {
        assertNull(Wav.parse(new byte[] {1, 2, 3}, 3, 3));
        File f = tmp.newFile("x.bin");
        Files.write(f.toPath(), "RIFFxxxxWAVEjunkjunk".getBytes());
        assertNull(Wav.parse(f));
        // data chunk before fmt chunk is not accepted
        byte[] b = Wav.header(16000, 1, 16, 0);
        System.arraycopy("data".getBytes(), 0, b, 12, 4);
        assertNull(Wav.parse(b, b.length, b.length));
    }

    @Test
    public void streamingHeaderLengthIsTakenFromFile() {
        byte[] h = Wav.header(16000, 1, 16, 0);
        Wav.Info info = Wav.parse(h, h.length, 44 + 3200);
        assertEquals(3200, info.dataLength);
        Wav.Info truncated = Wav.parse(Wav.header(16000, 1, 16, 99999), 44, 44 + 10);
        assertEquals(10, truncated.dataLength);
    }

    @Test
    public void oddChunkIsPadded() {
        byte[] head = Wav.header(16000, 1, 16, 4);
        byte[] list = {'L', 'I', 'S', 'T', 3, 0, 0, 0, 'a', 'b', 'c', 0};
        byte[] b = new byte[head.length + list.length];
        System.arraycopy(head, 0, b, 0, 36);
        System.arraycopy(list, 0, b, 36, list.length);
        System.arraycopy(head, 36, b, 36 + list.length, 8);
        Wav.Info info = Wav.parse(b, b.length, b.length + 4);
        assertNotNull(info);
        assertEquals(36 + list.length + 8, info.dataOffset);
    }

    @Test
    public void writerPatchesSizes() throws IOException {
        File f = new File(tmp.getRoot(), "sub/dir/rec.wav");
        Wav.Writer w = new Wav.Writer(f, 16000, 1);
        w.write(new byte[] {1, 2, 3, 4}, 0, 4);
        w.write(new byte[] {5, 6}, 0, 2);
        w.write(new byte[0], 0, 0);
        assertEquals(6, w.dataLength());
        assertEquals(f, w.file());
        w.close();
        w.close();
        w.write(new byte[] {7}, 0, 1);
        byte[] all = Files.readAllBytes(f.toPath());
        assertEquals(50, all.length);
        Wav.Info info = Wav.parse(all, all.length, all.length);
        assertEquals(6, info.dataLength);
        assertArrayEquals(Wav.header(16000, 1, 16, 6), java.util.Arrays.copyOf(all, 44));
    }

    @Test
    public void wrapPcm() throws IOException {
        File pcm = tmp.newFile("a.pcm");
        Files.write(pcm.toPath(), new byte[640]);
        File wav = new File(tmp.getRoot(), "a.wav");
        Wav.wrapPcm(pcm, wav, 16000, 1);
        Wav.Info info = Wav.parse(wav);
        assertEquals(640, info.dataLength);
        assertFalse(new Wav.Info(3, 1, 16000, 32, 44, 4).isPcm16());
        assertEquals(0, new Wav.Info(1, 0, 0, 16, 44, 4).durationSeconds(), 0);
    }
}
