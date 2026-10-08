package com.stkouyu;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import com.shengzhiai.yugu.stcompat.internal.CompatConfig;
import com.shengzhiai.yugu.stcompat.internal.Wav;
import com.shengzhiai.yugu.stcompat.testing.CompatTestBase;
import com.shengzhiai.yugu.stcompat.testing.FakeAudio;
import com.shengzhiai.yugu.stcompat.testing.Fixtures;
import com.shengzhiai.yugu.stcompat.testing.Main;
import com.stkouyu.listener.OnSTRecorderListener;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.shadows.ShadowMediaPlayer;
import org.robolectric.shadows.util.DataSource;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class STRecorderTest extends CompatTestBase {
    private final List<Integer> messages = Collections.synchronizedList(new ArrayList<Integer>());
    private final Handler handler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            messages.add(msg.what);
        }
    };

    @After
    public void deleteRecorder() {
        STRecorder.getInstance().delete();
    }

    @Test
    public void recordsWavAndDeliversChunks() throws Exception {
        FakeAudio audio = new FakeAudio(Fixtures.tone(500, 6000)).endWithNothing();
        CompatConfig.setAudioInputFactory(audio);
        STRecorder r = STRecorder.getInstance(app, app.getCacheDir().getAbsolutePath(), 44100, 1);
        assertSame(r, STRecorder.getInstance(app));
        assertSame(r, STRecorder.getInstance(app, null));
        assertSame(r, STRecorder.getInstance(app, null, 16000));
        r.setHandler(handler);
        final AtomicInteger started = new AtomicInteger();
        r.setOnSTRecorderListener(new OnSTRecorderListener() {
            @Override
            public void onStart() {
                started.incrementAndGet();
            }
        });
        final AtomicInteger bytes = new AtomicInteger();
        File out = new File(app.getCacheDir(), "st/one.wav");
        r.start(out.getAbsolutePath(), (data, size) -> bytes.addAndGet(size));
        assertTrue(r.isRecording);
        r.start(null, null);
        Main.await("chunks", 5000, () -> bytes.get() >= 16000);
        r.pause();
        assertTrue(r.mIsRecordPaused);
        r.pause();
        r.restart();
        assertTrue(r.mIsReStartRecord);
        assertFalse(r.mIsRecordPaused);
        r.restart();
        r.stop();
        r.stop();
        assertFalse(r.isRecording);
        Main.idle();
        assertEquals(1, started.get());
        assertTrue(messages.contains(SkEgnManager.CODE_RECORDER_START));
        assertTrue(messages.contains(SkEgnManager.CODE_RECORDER_ONSTARTRECORDFAIL));
        assertTrue(messages.contains(SkEgnManager.CODE_RECORDER_PAUSE));
        assertTrue(messages.contains(SkEgnManager.CODE_RECORDER_END));
        Wav.Info info = Wav.parse(out);
        assertEquals(16000, info.dataLength);
        assertEquals(Integer.valueOf(1), audio.lastSource);
    }

    @Test
    public void muteCancelAndDefaultPaths() throws Exception {
        CompatConfig.setAudioInputFactory(new FakeAudio(Fixtures.tone(300, 8000)).endWithNothing());
        STRecorder r = STRecorder.getInstance(app);
        r.setbMute(true);
        final List<Integer> peaks = Collections.synchronizedList(new ArrayList<Integer>());
        r.start(null, (data, size) -> {
            int max = 0;
            for (int i = 0; i < size; i++) {
                max = Math.max(max, Math.abs(data[i]));
            }
            peaks.add(max);
        });
        Main.await("chunks", 5000, () -> peaks.size() >= 2);
        r.setbMute(false);
        assertEquals(Integer.valueOf(0), peaks.get(0));
        r.cancel();
        File dir = new File(com.stkouyu.util.AiUtil.externalFilesDir(app), "record");
        File[] left = dir.listFiles();
        assertTrue(left == null || left.length == 0);
        File mp3 = new File(app.getCacheDir(), "m.mp3");
        r.setMp3Path(mp3.getAbsolutePath());
        r.start(null, null);
        Main.settle(50);
        r.stop();
        assertNotNull(Wav.parse(mp3));
        r.cancel();
        assertTrue(mp3.isFile());
    }

    @Test
    public void startFailures() {
        CompatConfig.setAudioInputFactory(new FakeAudio(null).failOpen());
        STRecorder r = STRecorder.getInstance(app);
        r.setHandler(handler);
        r.start(new File(app.getCacheDir(), "x.wav").getAbsolutePath(), null);
        assertFalse(r.isRecording);
        assertFalse(r.activeRecorder());
        r.start("/proc/forbidden/x.wav", null);
        CompatConfig.setAudioInputFactory(new FakeAudio(null).failStart());
        r.start(new File(app.getCacheDir(), "y.wav").getAbsolutePath(), null);
        Main.idle();
        assertEquals(3, Collections.frequency(messages, SkEgnManager.CODE_RECORDER_ONSTARTRECORDFAIL));
        assertTrue(r.releaseRecorder());
    }

    @Test
    public void activeRecorderIsReused() {
        FakeAudio audio = new FakeAudio(Fixtures.tone(100, 8000));
        CompatConfig.setAudioInputFactory(audio);
        STRecorder r = STRecorder.getInstance(app);
        assertTrue(r.activeRecorder());
        assertTrue(r.activeRecorder());
        r.start(null, null);
        assertEquals(1, audio.opened.get());
        assertTrue(r.activeRecorder());
        r.stop();
        assertTrue(r.activeRecorder());
        assertTrue(r.releaseRecorder());
        assertTrue(r.releaseRecorder());
        r.releasePlayer();
        r.finalize();
    }

    @Test
    public void playbackThroughPlayBackTask() throws Exception {
        STRecorder r = STRecorder.getInstance(app);
        File f = Fixtures.wavFile(app.getCacheDir(), "p.wav", new byte[32000]);
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(f.getAbsolutePath()), new ShadowMediaPlayer.MediaInfo(200, 0));
        r.playWithPath(f.getAbsolutePath(), handler);
        Main.await("play start", 5000, () -> messages.contains(SkEgnManager.CODE_PLAY_START));
        assertNotNull(r.mPlayBackTask);
        Main.idleFor(500);
        Main.await("play end", 5000, () -> messages.contains(SkEgnManager.CODE_PLAY_END));
        Main.settle(100);
        r.playback(null);
        Main.await("fail", 5000, () -> messages.contains(SkEgnManager.CODE_PLAY_START_FAIL));
        r.playWithPath(f.getAbsolutePath(), null);
        Main.await("second start", 5000, () -> Collections.frequency(messages, SkEgnManager.CODE_PLAY_START) == 2);
        r.stopPlay();
        r.stopPlay();
        Main.settle(100);
        assertNull(r.mPlayBackTask);
        // playBackFile on the main thread starts playback and returns
        assertNull(r.playBackFile(f.getAbsolutePath()));
        Main.idle();
        r.stopPlay();
        STRecorder.PlayBackTask t = r.new PlayBackTask();
        assertNull(t.doInBackground());
        assertNull(t.doInBackground((String[]) null));
        t.onPostExecute(null);
        t.onCancelled();
    }

    @Test
    public void deleteForgetsTheInstance() {
        STRecorder a = STRecorder.getInstance();
        a.delete();
        STRecorder b = STRecorder.getInstance();
        assertNotSame(a, b);
    }
}
