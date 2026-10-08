package com.shengzhiai.yugu.stcompat.internal;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class MultipartTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void textPartsHaveNoFilenameAndAudioPartHasOne() throws Exception {
        File audio = tmp.newFile("a.wav");
        Files.write(audio.toPath(), new byte[] {1, 2, 3, 4, 5});
        Multipart mp = new Multipart("BOUNDARY")
                .addText("refText", "今天天气很好")
                .addText("x", "a\"b")
                .addFile("audio", "t.wav", "audio/wav", audio);
        byte[] body = mp.toByteArray();
        assertEquals(mp.contentLength(), body.length);
        String text = new String(body, StandardCharsets.UTF_8);
        assertTrue(text.startsWith("--BOUNDARY\r\nContent-Disposition: form-data; name=\"refText\"\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\n\r\n今天天气很好\r\n"));
        assertTrue(text.contains("name=\"x\""));
        assertTrue(text.contains("Content-Disposition: form-data; name=\"audio\"; filename=\"t.wav\"\r\n"
                + "Content-Type: audio/wav\r\n\r\n"));
        assertTrue(text.endsWith("\r\n--BOUNDARY--\r\n"));
        assertEquals("multipart/form-data; boundary=BOUNDARY", mp.contentType());
        assertEquals(3, mp.parts().size());
        assertFalse(mp.parts().get(0).isFile());
        assertTrue(mp.parts().get(2).isFile());
    }

    @Test
    public void bytesPartAndRandomBoundary() throws Exception {
        Multipart mp = new Multipart().addBytes("audio", "q\"x.wav", "audio/wav", new byte[] {9, 9});
        assertTrue(mp.boundary().startsWith("----YuguStCompat"));
        byte[] body = mp.toByteArray();
        assertEquals(mp.contentLength(), body.length);
        assertTrue(new String(body, StandardCharsets.ISO_8859_1).contains("filename=\"q%22x.wav\""));
    }

    @Test
    public void detectsFileShrinkingDuringUpload() throws Exception {
        final File audio = tmp.newFile("b.wav");
        Files.write(audio.toPath(), new byte[100000]);
        Multipart mp = new Multipart("B").addFile("audio", "b.wav", "audio/wav", audio);
        long len = mp.contentLength();
        assertTrue(len > 100000);
        OutputStream truncating = new ByteArrayOutputStream() {
            boolean done;

            @Override
            public synchronized void write(byte[] b, int off, int n) {
                super.write(b, off, n);
                if (!done) {
                    done = true;
                    try {
                        new java.io.RandomAccessFile(audio, "rw").setLength(10);
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                }
            }
        };
        try {
            mp.writeTo(truncating);
            fail("expected IOException");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("changed"));
        }
        // a file that grows after it was added is detected too
        File grow = tmp.newFile("c.wav");
        Files.write(grow.toPath(), new byte[10]);
        Multipart mp2 = new Multipart("B").addFile("audio", "c.wav", "audio/wav", grow);
        Files.write(grow.toPath(), new byte[20]);
        try {
            mp2.writeTo(new ByteArrayOutputStream());
            fail("expected IOException");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("changed"));
        }
    }
}
