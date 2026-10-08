package com.shengzhiai.yugu.stcompat.internal;

import com.shengzhiai.yugu.stcompat.testing.Fixtures;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Local pre-check errIds 60001, 60002, 60005, 60009 and upload content types. */
public class AudioFilesTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File file(String name, byte[] content) throws Exception {
        File f = new File(tmp.getRoot(), name);
        Files.write(f.toPath(), content);
        return f;
    }

    @Test
    public void kinds() throws Exception {
        assertEquals("audio/wav", AudioFiles.kind(Fixtures.file("spec/fixtures/audio/zh_short.wav"), "wav").contentType);
        assertEquals("audio/ogg", AudioFiles.kind(file("a.ogg", "OggS\0\0\0\0\0\0\0\0".getBytes()), null).contentType);
        assertEquals("audio/amr", AudioFiles.kind(file("a.amr", "#!AMR\n\0\0\0\0\0\0".getBytes()), null).contentType);
        assertEquals("audio/flac", AudioFiles.kind(file("a.flac", "fLaC\0\0\0\0\0\0\0\0".getBytes()), null).contentType);
        assertEquals("video/x-flv", AudioFiles.kind(file("a.flv", "FLV\1\0\0\0\0\0\0\0\0".getBytes()), null).contentType);
        assertEquals("audio/mp4", AudioFiles.kind(file("a.m4a", "\0\0\0\u0018ftypM4A \0\0".getBytes()), null).contentType);
        assertEquals("audio/mpeg", AudioFiles.kind(file("a.mp3", "ID3\3\0\0\0\0\0\0\0\0".getBytes()), null).contentType);
        assertEquals("audio/mpeg", AudioFiles.kind(file("b.bin", new byte[] {(byte) 0xFF, (byte) 0xFB, 0, 0}), null).contentType);
        assertEquals("audio/aac", AudioFiles.kind(file("c.bin", new byte[] {(byte) 0xFF, (byte) 0xF1, 0, 0}), null).contentType);
        AudioFiles.Kind pcm = AudioFiles.kind(file("d.pcm", new byte[64]), null);
        assertTrue(pcm.rawPcm);
        assertTrue(AudioFiles.kind(file("e.raw", new byte[64]), "raw").rawPcm);
        assertTrue(AudioFiles.kind(file("f.wav", new byte[64]), "wav").rawPcm);
        assertTrue(AudioFiles.kind(file("g", new byte[64]), "wav").rawPcm);
        assertEquals("audio/mpeg", AudioFiles.kind(file("h.mp3", new byte[64]), null).contentType);
        assertEquals("audio/mpeg", AudioFiles.kind(file("i.dat", new byte[64]), "mp3").contentType);
        AudioFiles.Kind other = AudioFiles.kind(file("j.opus", new byte[64]), "opus");
        assertEquals("application/octet-stream", other.contentType);
        assertEquals("opus", other.extension);
        assertFalse(other.rawPcm);
        assertEquals("bin", AudioFiles.kind(file("k", new byte[2]), null).extension);
    }

    @Test
    public void precheck() throws Exception {
        assertEquals(60001, AudioFiles.precheck(null, true));
        assertEquals(60001, AudioFiles.precheck(new File(tmp.getRoot(), "missing.wav"), true));
        assertEquals(60001, AudioFiles.precheck(tmp.getRoot(), true));
        assertEquals(60002, AudioFiles.precheck(file("empty.wav", new byte[0]), true));
        assertEquals(60002, AudioFiles.precheck(file("hdr.wav", com.shengzhiai.yugu.stcompat.internal.Wav.header(16000, 1, 16, 0)), true));
        assertEquals(60005, AudioFiles.precheck(Fixtures.wavFile(tmp.getRoot(), "short.wav", new byte[31998]), true));
        assertEquals(0, AudioFiles.precheck(Fixtures.wavFile(tmp.getRoot(), "ok.wav", new byte[32000]), true));
        assertEquals(0, AudioFiles.precheck(file("short.mp3", new byte[10]), false));
        // the REST limit of the platform is 50 MB
        File ten = new File(tmp.getRoot(), "ten.mp3");
        try (RandomAccessFile raf = new RandomAccessFile(ten, "rw")) {
            raf.setLength(10L * 1024 * 1024 + 1);
        }
        assertEquals(0, AudioFiles.precheck(ten, false));
        File big = new File(tmp.getRoot(), "big.mp3");
        try (RandomAccessFile raf = new RandomAccessFile(big, "rw")) {
            raf.setLength(50L * 1024 * 1024 + 1);
        }
        assertEquals(60009, AudioFiles.precheck(big, false));
        File exact = new File(tmp.getRoot(), "exact.mp3");
        try (RandomAccessFile raf = new RandomAccessFile(exact, "rw")) {
            raf.setLength(50L * 1024 * 1024);
        }
        assertEquals(0, AudioFiles.precheck(exact, false));
        // the duration limit is 300 s
        assertEquals(60009, AudioFiles.precheck(sparseWav("long.wav", 301), true));
        assertEquals(0, AudioFiles.precheck(sparseWav("max.wav", 300), true));
    }

    private File sparseWav(String name, int seconds) throws Exception {
        long data = seconds * 32000L;
        File f = new File(tmp.getRoot(), name);
        try (RandomAccessFile raf = new RandomAccessFile(f, "rw")) {
            raf.write(Wav.header(16000, 1, 16, data));
            raf.setLength(44 + data);
        }
        return f;
    }
}
