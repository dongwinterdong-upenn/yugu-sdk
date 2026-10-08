package com.shengzhiai.yugu.stcompat.internal;

import com.shengzhiai.yugu.stcompat.testing.Fixtures;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** vad_status 0/1/2, seek, ref_length and sound_intensity 0..100. */
public class EnergyVadTest {
    @Test
    public void silenceStaysZero() {
        EnergyVad v = new EnergyVad(null, null);
        assertEquals(0, v.process(Fixtures.silence(2000), 0, 64000));
        assertEquals(0, v.intensity());
    }

    @Test
    public void speechThenSilenceEndsAfterSeek() {
        EnergyVad v = new EnergyVad(30, null);
        assertEquals(1, v.process(Fixtures.tone(300, 8000), 0, 9600));
        assertTrue(v.intensity() > 60);
        byte[] s = Fixtures.silence(290);
        assertEquals(1, v.process(s, 0, s.length));
        byte[] more = Fixtures.silence(20);
        assertEquals(2, v.process(more, 0, more.length));
        assertEquals(2, v.status());
        // stays ended
        byte[] t = Fixtures.tone(100, 8000);
        assertEquals(2, v.process(t, 0, t.length));
    }

    @Test
    public void defaultSeekIs600ms() {
        EnergyVad v = new EnergyVad(0, null);
        byte[] t = Fixtures.tone(100, 8000);
        v.process(t, 0, t.length);
        byte[] s = Fixtures.silence(590);
        assertEquals(1, v.process(s, 0, s.length));
        byte[] s2 = Fixtures.silence(10);
        assertEquals(2, v.process(s2, 0, s2.length));
    }

    @Test
    public void refLengthKeepsSpeaking() {
        EnergyVad v = new EnergyVad(10, 100);
        byte[] t = Fixtures.tone(50, 8000);
        v.process(t, 0, t.length);
        byte[] s = Fixtures.silence(500);
        assertEquals(1, v.process(s, 0, s.length));
        byte[] s2 = Fixtures.silence(600);
        assertEquals(2, v.process(s2, 0, s2.length));
    }

    @Test
    public void shortNoiseDoesNotStartSpeech() {
        EnergyVad v = new EnergyVad(null, null);
        byte[] blip = Fixtures.tone(20, 8000);
        byte[] s = Fixtures.silence(100);
        byte[] all = Fixtures.concat(blip, s, blip, s);
        assertEquals(0, v.process(all, 0, all.length));
    }

    @Test
    public void framesSplitAcrossChunks() {
        EnergyVad v = new EnergyVad(null, null);
        byte[] t = Fixtures.tone(100, 8000);
        int status = 0;
        for (int i = 0; i < t.length; i += 7) {
            status = v.process(t, i, Math.min(7, t.length - i));
        }
        assertEquals(1, status);
    }

    @Test
    public void fixtureSpeechIsDetected() {
        byte[] pcm = Fixtures.pcm("spec/fixtures/audio/zh_short.wav");
        EnergyVad v = new EnergyVad(null, null);
        v.process(pcm, 0, pcm.length);
        assertTrue(v.status() >= 1);
        assertTrue(v.takePeakIntensity() > 30);
        assertEquals(0, v.takePeakIntensity());
        byte[] silent = Fixtures.pcm("spec/fixtures/audio/silent.wav");
        EnergyVad q = new EnergyVad(null, null);
        assertEquals(0, q.process(silent, 0, silent.length));
    }

    @Test
    public void intensityScale() {
        assertEquals(0, EnergyVad.intensityOf(0));
        assertEquals(0, EnergyVad.intensityOf(10));
        assertEquals(100, EnergyVad.intensityOf(32767));
        assertEquals(100, EnergyVad.intensityOf(40000));
        assertEquals(50, EnergyVad.intensityOf(32768 * Math.pow(10, -30 / 20.0)));
        assertEquals(0.0, EnergyVad.rms(new byte[1], 0, 1), 0);
    }
}
