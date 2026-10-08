package com.shengzhiai.yugu.stcompat.internal;

import com.shengzhiai.yugu.stcompat.testing.Fixtures;

import org.junit.Test;

import java.io.ByteArrayOutputStream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class WavStreamStripperTest {
    @Test
    public void rawPcmPassesThrough() {
        WavStreamStripper s = new WavStreamStripper();
        byte[] pcm = Fixtures.tone(20, 1000);
        assertArrayEquals(pcm, s.feed(pcm, pcm.length));
        assertArrayEquals(new byte[] {1, 2}, s.feed(new byte[] {1, 2, 3}, 2));
        assertEquals(0, s.flush().length);
    }

    @Test
    public void wavHeaderIsDroppedEvenWhenSplit() throws Exception {
        byte[] wav = Fixtures.bytes("spec/fixtures/audio/zh_short.wav");
        byte[] pcm = Fixtures.pcm("spec/fixtures/audio/zh_short.wav");
        WavStreamStripper s = new WavStreamStripper();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < wav.length; i += 5) {
            int n = Math.min(5, wav.length - i);
            byte[] chunk = new byte[n];
            System.arraycopy(wav, i, chunk, 0, n);
            out.write(s.feed(chunk, n));
        }
        out.write(s.flush());
        assertArrayEquals(pcm, out.toByteArray());
    }

    @Test
    public void shortStreams() {
        WavStreamStripper a = new WavStreamStripper();
        assertEquals(0, a.feed("RIF".getBytes(), 3).length);
        assertEquals(3, a.flush().length);
        WavStreamStripper a2 = new WavStreamStripper();
        assertEquals(0, a2.feed("RIFFxx".getBytes(), 6).length);
        assertEquals(0, a2.flush().length);
        WavStreamStripper b = new WavStreamStripper();
        assertArrayEquals(new byte[] {7, 8}, b.feed(new byte[] {7, 8}, 2));
        WavStreamStripper c = new WavStreamStripper();
        assertEquals(0, c.feed("RIFFxxxxWAVE".getBytes(), 12).length);
        byte[] junk = new byte[5000];
        assertEquals(12 + 5000, c.feed(junk, junk.length).length);
        WavStreamStripper d = new WavStreamStripper();
        assertArrayEquals("RIFFxxxxABCD".getBytes(), d.feed("RIFFxxxxABCD".getBytes(), 12));
    }
}
