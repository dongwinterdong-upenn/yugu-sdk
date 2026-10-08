package com.stkouyu;

import android.Manifest;

import com.shengzhiai.yugu.stcompat.internal.CompatConfig;
import com.shengzhiai.yugu.stcompat.internal.Wav;
import com.shengzhiai.yugu.stcompat.testing.CompatTestBase;
import com.shengzhiai.yugu.stcompat.testing.Events;
import com.shengzhiai.yugu.stcompat.testing.FakeAudio;
import com.shengzhiai.yugu.stcompat.testing.FakeTransport;
import com.shengzhiai.yugu.stcompat.testing.Fixtures;
import com.shengzhiai.yugu.stcompat.testing.Main;
import com.stkouyu.setting.EngineSetting;
import com.stkouyu.setting.RecordSetting;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

/** DESIGN 6 behaviour of SkEgnManager on a fake transport and a fake microphone. */
@RunWith(RobolectricTestRunner.class)
public class SkEgnManagerTest extends CompatTestBase {
    private final Events ev = new Events();
    private FakeAudio audio;

    private void mic(byte[] pcm) {
        audio = new FakeAudio(pcm);
        CompatConfig.setAudioInputFactory(audio);
    }

    private static RecordSetting sent() {
        return new RecordSetting(CoreType.CN_SENT_EVAL, "今天天气很好");
    }

    private void awaitScore() {
        Main.await("onScore", 10000, () -> ev.json != null);
    }

    private void assertMainThreadOnly() {
        assertTrue("callbacks off the main thread: " + ev.offMain, ev.offMain.isEmpty());
    }

    // ---------------------------------------------------------------- init

    @Test
    public void initPostsCallbacksOnMainThread() {
        SkEgnManager m = initManager(ev);
        assertEquals(Arrays.asList("onStartInitEngine", "onInitEngineSuccess"), ev.log);
        assertNotEquals(0L, SkEgnManager.engine);
        assertEquals("cloud", m.getCurrentEngineType());
        assertEquals("yugu-stkouyu-compat/2.0.0 (api 1.0.62)", m.getSDKVersion());
        assertEquals(SkEgnManager.engine_status.IDLE, m.getEngineStatus());
        assertMainThreadOnly();
        m.recycle();
        assertEquals(0L, SkEgnManager.engine);
    }

    @Test
    public void initFailures() {
        SkEgnManager m = SkEgnManager.getInstance(app);
        EngineSetting es = EngineSetting.getInstance(app).setOnInitEngineListener(ev.init);
        m.initEngine("", "s", "u", es);
        Main.idle();
        assertEquals(Arrays.asList("onStartInitEngine", "onInitEngineFailed"), ev.log);
        assertEquals("appKey is empty", ev.startFail);
        assertEquals(0L, SkEgnManager.engine);
        m.initEngine("a", " ", "u", es);
        Main.idle();
        assertEquals("secretKey is empty", ev.startFail);
        es.setServerAddress("ftp://example.com");
        m.initEngine("a", "s", "u", es);
        Main.idle();
        assertTrue(ev.startFail.contains("scheme"));
        es.setServerAddress(AppConfig.CLOUD_SERVER_ADDRESS);
        m.initEngine("a", "s", "u", null);
        Main.idle();
        assertEquals("onInitEngineSuccess", ev.log.get(ev.log.size() - 1));
        m.startRecord(sent(), ev.recorder);
        Main.idle();
        assertTrue(ev.has("onStart"));
        m.cancel();
    }

    @Test
    public void initVariantsAndEngineTypes() {
        SkEgnManager m = SkEgnManager.getInstance(app);
        EngineSetting.getInstance(app).setOnInitEngineListener(ev.init);
        m.initCloudEngine("a", "s", "u");
        m.initNativeEngine("a", "s", "u");
        m.initNativeEngine("a", "s", "u", EngineSetting.getInstance(app).getDefaultNativeInstance());
        EngineSetting multi = EngineSetting.getInstance(app);
        multi.setEngineType(EngineType.ENGINE_MULTI);
        m.initEngine("a", "s", "u", multi);
        m.initCloudEngine("a", "s", "u", multi.getDefaultCloudInstance());
        Main.idle();
        assertEquals(5, ev.count("onInitEngineSuccess"));
        assertEquals("cloud", m.getCurrentEngineType());
        assertNotNull(SkEgnManager.getInstance(null));
    }

    @Test
    public void clearInitListenerDropsPendingCallbacks() {
        SkEgnManager m = SkEgnManager.getInstance(app);
        m.initEngine("a", "s", "u", EngineSetting.getInstance(app).setOnInitEngineListener(ev.init));
        m.clearInitListener();
        Main.idle();
        assertTrue(ev.log.isEmpty());
    }

    @Test
    public void notInitialisedAnswers60007() throws Exception {
        SkEgnManager m = SkEgnManager.getInstance(app);
        m.startRecord(sent(), ev.recorder);
        awaitScore();
        assertEquals(60007, ev.result().getInt("errId"));
        assertEquals(0, transport.count());
        Events e2 = new Events();
        m.existsAudioTrans(sent(), e2.record);
        Main.idle();
        assertEquals(60007, e2.result().getInt("errId"));
    }

    // ---------------------------------------------------------------- recording

    @Test
    public void recordStopScore() throws Exception {
        mic(Fixtures.pcm("spec/fixtures/audio/zh_short.wav"));
        SkEgnManager m = initManager(null);
        m.setOnRecordBufferListener(ev.buffer);
        m.startRecord(sent(), ev.recorder);
        assertEquals(SkEgnManager.engine_status.RECORDING, m.getEngineStatus());
        Main.idle();
        assertEquals(Arrays.asList("onStart"), ev.log);
        Main.await("audio captured", 5000, () -> ev.bufferBytes >= 61440);
        m.stopRecord();
        assertEquals(SkEgnManager.engine_status.STOP, m.getEngineStatus());
        awaitScore();
        assertEquals(Arrays.asList("onStart", "onRecordEnd", "onScore"), ev.log);
        JSONObject r = ev.result();
        assertTrue(r.getString("tokenId").matches("[0-9a-f]{32}"));
        assertEquals("eval_7954d149c40a", r.getString("recordId"));
        assertEquals("test-app-key", r.getString("applicationId"));
        assertEquals("user-1", r.getString("userId"));
        assertEquals("今天天气很好", r.getString("refText"));
        assertEquals(1, r.getInt("eof"));
        assertEquals(94.6, r.getJSONObject("result").getDouble("overall"), 1e-9);
        FakeTransport.Call call = transport.calls().get(0);
        assertEquals(r.getString("tokenId"), call.header("Idempotency-Key"));
        assertEquals("https://open.shengzhiai.com/sent.eval.cn", call.request.url);
        assertEquals("test-app-key", call.header("X-App-Key"));
        assertTrue(call.bodyText().contains("filename=\"" + r.getString("tokenId") + ".wav\""));
        String path = m.getLastRecordPath();
        assertTrue(path.endsWith("/record/" + r.getString("tokenId") + ".wav"));
        Wav.Info info = Wav.parse(new File(path));
        assertTrue(info.dataLength >= 61440);
        assertEquals(SkEgnManager.engine_status.STOP, m.getEngineStatus());
        assertMainThreadOnly();
        assertEquals(1, audio.released.get());
        // the next recording may start once the result is delivered
        Events next = new Events();
        m.startRecord(sent(), next.recorder);
        Main.idle();
        assertTrue(next.has("onStart"));
        m.cancel();
    }

    @Test
    public void busyEngine() throws Exception {
        mic(Fixtures.tone(500, 6000));
        transport.delay(300);
        SkEgnManager m = initManager(null);
        m.startRecord(sent(), ev.recorder);
        Events second = new Events();
        m.startRecord(sent(), second.recorder);
        Main.idle();
        assertEquals("engine is busy", second.startFail);
        Events third = new Events();
        m.startRecord(sent(), third.record);
        Main.idle();
        assertEquals(60008, third.result().getInt("errId"));
        Events fourth = new Events();
        m.existsAudioTrans(sent(), fourth.recorder);
        Main.idle();
        assertEquals(60008, fourth.result().getInt("errId"));
        m.stopRecord();
        // still busy while the evaluation is in flight
        Events fifth = new Events();
        m.startRecord(sent(), fifth.recorder);
        Main.idle();
        assertEquals("engine is busy", fifth.startFail);
        awaitScore();
        assertMainThreadOnly();
    }

    @Test
    public void invalidSettingsNeverReachTheNetwork() throws Exception {
        mic(null);
        SkEgnManager m = initManager(null);
        m.startRecord(new RecordSetting(CoreType.EN_OPEN_EVAL, "x"), ev.recorder);
        Main.idle();
        assertEquals(60003, ev.result().getInt("errId"));
        Events e2 = new Events();
        m.startRecord(new RecordSetting(CoreType.EN_SENT_EVAL, ""), e2.record);
        Main.idle();
        assertEquals(60006, e2.result().getInt("errId"));
        Events e3 = new Events();
        m.existsAudioTrans(new RecordSetting(CoreType.KR_SENT_EVAL, "x"), e3.record);
        Main.idle();
        assertEquals(60003, e3.result().getInt("errId"));
        assertEquals(0, transport.count());
        assertEquals(0, audio.opened.get());
        assertEquals(SkEgnManager.engine_status.IDLE, m.getEngineStatus());
        m.startRecord((RecordSetting) null, ev.recorder);
        Main.idle();
        assertEquals(60003, ev.result().getInt("errId"));
    }

    @Test
    public void microphoneFailures() throws Exception {
        audio = new FakeAudio(null).failOpen();
        CompatConfig.setAudioInputFactory(audio);
        SkEgnManager m = initManager(null);
        m.startRecord(sent(), ev.recorder);
        Main.idle();
        assertTrue(ev.startFail.contains("microphone"));
        assertEquals(SkEgnManager.engine_status.IDLE, m.getEngineStatus());
        Events e2 = new Events();
        m.startRecord(sent(), e2.record);
        Main.idle();
        assertEquals(60004, e2.result().getInt("errId"));
        FakeAudio busy = new FakeAudio(null).failStart();
        CompatConfig.setAudioInputFactory(busy);
        Events e3 = new Events();
        m.startRecord(sent(), e3.recorder);
        Main.idle();
        assertTrue(e3.startFail.contains("microphone"));
        assertEquals("a device that cannot start is released", 1, busy.released.get());
        assertEquals(SkEgnManager.engine_status.IDLE, m.getEngineStatus());
        assertTrue(m.activeMic());
        assertTrue(m.releaseMic());
        CompatConfig.setAudioInputFactory(new FakeAudio(null).failOpen());
        assertFalse(m.activeMic());
        assertEquals(0, transport.count());
    }

    @Test
    public void permissionDeniedWithRealAudioRecord() {
        CompatConfig.setAudioInputFactory(null);
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO);
        SkEgnManager m = initManager(null);
        m.startRecord(sent(), ev.recorder);
        Main.idle();
        assertTrue(ev.startFail, ev.startFail.contains("RECORD_AUDIO"));
        assertFalse(m.activeMic());
    }

    @Test
    public void captureErrorEndsWithErrorJson() throws Exception {
        audio = new FakeAudio(Fixtures.tone(200, 5000)).readErrorAfterData(-3);
        CompatConfig.setAudioInputFactory(audio);
        SkEgnManager m = initManager(null);
        m.startRecord(sent(), ev.recorder);
        awaitScore();
        assertEquals(60004, ev.result().getInt("errId"));
        assertEquals(0, transport.count());
    }

    @Test
    public void pauseAndRestart() throws Exception {
        mic(Fixtures.tone(300, 6000));
        SkEgnManager m = initManager(null);
        m.pauseRecord();
        m.restartRecord();
        m.startRecord(sent(), ev.recorder);
        Main.idle();
        m.restartRecord();
        m.pauseRecord();
        assertEquals(SkEgnManager.engine_status.PAUSED, m.getEngineStatus());
        Main.idle();
        assertTrue(ev.has("onPause"));
        m.pauseRecord();
        m.restartRecord();
        assertEquals(SkEgnManager.engine_status.RECORDING, m.getEngineStatus());
        assertTrue(audio.started.get() >= 2);
        m.pauseRecord();
        m.stopRecord();
        awaitScore();
        assertEquals(2, ev.count("onPause"));
        assertEquals("onScore", ev.log.get(ev.log.size() - 1));
        assertMainThreadOnly();
    }

    @Test
    public void cancelIsSilentAndIdempotent() throws Exception {
        mic(Fixtures.tone(300, 6000));
        SkEgnManager m = initManager(null);
        m.cancel();
        m.startRecord(sent(), ev.recorder);
        Main.idle();
        m.cancel();
        m.cancle();
        assertEquals(SkEgnManager.engine_status.IDLE, m.getEngineStatus());
        Main.settle(200);
        assertEquals(Arrays.asList("onStart"), ev.log);
        assertEquals(0, transport.count());
        // cancel while the upload is in flight
        transport.delay(5000);
        Events e2 = new Events();
        m.startRecord(sent(), e2.recorder);
        Main.settle(100);
        m.stopRecord();
        Main.await("upload started", 5000, () -> transport.count() == 1);
        long t0 = System.currentTimeMillis();
        m.cancle();
        Main.settle(300);
        assertTrue(System.currentTimeMillis() - t0 < 3000);
        assertFalse(e2.has("onScore"));
        assertEquals(SkEgnManager.engine_status.IDLE, m.getEngineStatus());
    }

    @Test
    public void recycleIsIdempotent() throws Exception {
        mic(Fixtures.tone(300, 6000));
        SkEgnManager m = initManager(null);
        m.activeMic();
        m.setPlayerListener(ev.player);
        m.startRecord(sent(), ev.recorder);
        Main.idle();
        m.recycle();
        m.recycle();
        assertEquals(SkEgnManager.engine_status.IDLE, m.getEngineStatus());
        assertEquals(0L, SkEgnManager.engine);
        Main.settle(100);
        assertFalse(ev.has("onScore"));
        Events e2 = new Events();
        m.startRecord(sent(), e2.recorder);
        Main.idle();
        assertEquals(60007, e2.result().getInt("errId"));
        // usable again after a new init
        initManager(null);
        Events e3 = new Events();
        m.startRecord(sent(), e3.recorder);
        Main.idle();
        assertTrue(e3.has("onStart"));
        m.cancel();
    }

    @Test
    public void clearActivityListenerDropsCallbacks() throws Exception {
        mic(Fixtures.tone(300, 6000));
        SkEgnManager m = initManager(null);
        m.startRecord(sent(), ev.recorder);
        Main.settle(100);
        m.clearActivityListener();
        m.stopRecord();
        Main.await("result handled", 5000, () -> transport.count() == 1);
        Main.settle(200);
        assertEquals(Arrays.asList("onStart"), ev.log);
        assertEquals(SkEgnManager.engine_status.STOP, m.getEngineStatus());
        // not busy any more
        Events e2 = new Events();
        m.startRecord(sent(), e2.recorder);
        Main.idle();
        assertTrue(e2.has("onStart"));
        m.cancel();
    }

    @Test
    public void setOnRecorderListenerReplacesTheListener() throws Exception {
        mic(Fixtures.tone(300, 6000));
        SkEgnManager m = initManager(null);
        Events first = new Events();
        m.startRecord(sent(), first.recorder);
        Main.idle();
        m.setOnRecorderListener(ev.recorder);
        m.stopRecord();
        awaitScore();
        assertTrue(first.has("onStart"));
        assertFalse(first.has("onScore"));
        // the default listener serves a start without listener
        Events e3 = new Events();
        m.setOnRecorderListener(e3.recorder);
        m.startRecord(sent(), (com.stkouyu.listener.OnRecorderListener) null);
        Main.idle();
        assertTrue(e3.has("onStart"));
        m.cancel();
    }

    @Test
    public void durationTicksAndAutoStop() throws Exception {
        mic(Fixtures.tone(3000, 6000));
        SkEgnManager m = initManager(null);
        RecordSetting s = sent();
        s.setDuration(1000);
        s.setDurationInterval(200);
        m.startRecord(s, ev.recorder);
        Main.idle();
        for (int i = 0; i < 4; i++) {
            Main.idleFor(200);
        }
        assertEquals(4, ev.ticks.size());
        assertEquals(800, ev.ticks.get(0)[0]);
        assertEquals(80.0, ev.tickPercents.get(0)[0], 1e-9);
        assertEquals(200, ev.ticks.get(3)[0]);
        assertEquals(SkEgnManager.engine_status.RECORDING, m.getEngineStatus());
        Main.idleFor(200);
        assertEquals(0, ev.ticks.get(4)[0]);
        assertEquals(0.0, ev.tickPercents.get(4)[0], 0);
        assertEquals(SkEgnManager.engine_status.STOP, m.getEngineStatus());
        awaitScore();
        assertEquals("onScore", ev.log.get(ev.log.size() - 1));
        assertMainThreadOnly();
    }

    @Test
    public void ticksPauseWithTheRecording() throws Exception {
        mic(Fixtures.tone(3000, 6000));
        SkEgnManager m = initManager(null);
        RecordSetting s = sent();
        s.setDuration(1000);
        m.startRecord(s, ev.recorder);
        Main.idle();
        Main.idleFor(300);
        assertEquals(3, ev.ticks.size());
        m.pauseRecord();
        Main.idle();
        Main.idleFor(5000);
        assertEquals(3, ev.ticks.size());
        m.restartRecord();
        Main.idleFor(100);
        assertEquals(600, ev.ticks.get(3)[0]);
        m.stopRecord();
        awaitScore();
    }

    @Test
    public void vadEndsTheRecording() throws Exception {
        mic(Fixtures.concat(Fixtures.tone(600, 8000), Fixtures.silence(1200)));
        SkEgnManager m = initManager(null);
        RecordSetting s = sent();
        s.setVADEnabled(true);
        s.setSeek(30);
        m.startRecord(s, ev.recorder);
        awaitScore();
        assertTrue(ev.has("onRecordEnd"));
        boolean speaking = false;
        boolean ended = false;
        for (int[] r : ev.recording) {
            speaking |= r[0] == 1;
            ended |= r[0] == 2;
            assertTrue(r[1] >= 0 && r[1] <= 100);
        }
        assertTrue(speaking);
        assertTrue(ended);
        assertMainThreadOnly();
    }

    @Test
    public void forceRecordIgnoresVadEnd() throws Exception {
        mic(Fixtures.concat(Fixtures.tone(400, 8000), Fixtures.silence(1500)));
        SkEgnManager m = SkEgnManager.getInstance(app);
        m.initEngine("k", "s", "u", EngineSetting.getInstance(app).setVADEnabled(true));
        RecordSetting s = sent();
        s.setForceRecord(true);
        s.setSeek(20);
        m.startRecord(s, ev.recorder);
        Main.await("vad end reported", 5000, () -> {
            synchronized (ev.recording) {
                for (int[] r : ev.recording) {
                    if (r[0] == 2) {
                        return true;
                    }
                }
                return false;
            }
        });
        Main.settle(100);
        assertEquals(SkEgnManager.engine_status.RECORDING, m.getEngineStatus());
        m.stopRecord();
        awaitScore();
    }

    @Test
    public void soundIntensityWithoutVad() throws Exception {
        mic(Fixtures.tone(1000, 8000));
        SkEgnManager m = initManager(null);
        RecordSetting s = sent().setNeedSoundIntensity(true);
        Events rec = new Events();
        m.startRecord(s, rec.record);
        // one report per 100 ms of audio: wait for more than 1 s
        Main.await("recording reports", 5000, () -> rec.recording.size() >= 12);
        m.stopRecord();
        Main.await("onRecordEnd(json)", 5000, () -> rec.json != null);
        assertEquals(Arrays.asList("onRecordStart"), rec.log.subList(0, 1));
        assertEquals("onRecordEnd(json)", rec.log.get(rec.log.size() - 1));
        assertEquals(94.6, rec.result().getJSONObject("result").getDouble("overall"), 1e-9);
    }

    @Test
    public void recordingStopsAtThe300SecondLimit() throws Exception {
        mic(new byte[310 * 32000]);
        SkEgnManager m = initManager(null);
        m.startRecord(sent(), ev.recorder);
        Main.await("onScore", 30000, () -> ev.json != null);
        File f = new File(m.getLastRecordPath());
        assertEquals(44 + 300L * 32000, f.length());
        assertEquals(300.0, Wav.parse(f).durationSeconds(), 1e-9);
        assertEquals(1, transport.count());
        assertEquals(94.6, ev.result().getJSONObject("result").getDouble("overall"), 1e-9);
    }

    // ---------------------------------------------------------------- stream mode

    @Test
    public void streamModeFeedsCallerAudio() throws Exception {
        mic(null);
        SkEgnManager m = initManager(null);
        m.feed(new byte[10]);
        RecordSetting s = sent();
        s.setIsStream(true);
        m.startRecord(s, ev.recorder);
        byte[] wav = Fixtures.bytes("spec/fixtures/audio/zh_short.wav");
        for (int i = 0; i < wav.length; i += 3200) {
            int n = Math.min(3200, wav.length - i);
            byte[] chunk = new byte[n];
            System.arraycopy(wav, i, chunk, 0, n);
            m.feed(chunk, n);
        }
        m.feed(null);
        m.feed(new byte[4], 0);
        m.stopRecord();
        m.feed(new byte[100]);
        awaitScore();
        assertEquals(0, audio.opened.get());
        JSONObject r = ev.result();
        assertEquals(94.6, r.getJSONObject("result").getDouble("overall"), 1e-9);
        Wav.Info info = Wav.parse(new File(m.getLastRecordPath()));
        assertEquals(61440, info.dataLength);
        byte[] body = transport.calls().get(0).body;
        assertTrue(body.length > 61440 + 44);
    }

    @Test
    public void streamPauseDropsAudioAndEmptyStreamIs60002() throws Exception {
        SkEgnManager m = initManager(null);
        RecordSetting s = sent();
        s.setIsStream(true);
        m.startRecord(s, ev.recorder);
        m.pauseRecord();
        m.feed(Fixtures.tone(2000, 5000));
        m.restartRecord();
        m.stopRecord();
        awaitScore();
        assertEquals(60002, ev.result().getInt("errId"));
        Events e2 = new Events();
        m.startRecord(s, e2.recorder);
        m.feed(Fixtures.tone(500, 5000));
        m.stopRecord();
        Main.await("short", 5000, () -> e2.json != null);
        assertEquals(60005, e2.result().getInt("errId"));
        assertEquals(0, transport.count());
    }

    // ---------------------------------------------------------------- files

    @Test
    public void existsAudioTransWithBothListenerTypes() throws Exception {
        SkEgnManager m = initManager(null);
        RecordSetting s = new RecordSetting(CoreType.EN_WORD_EVAL, "apple");
        s.setAudioPath(Fixtures.file("spec/fixtures/audio/en_apple.wav").getAbsolutePath());
        transport.defaultBody(Fixtures.text("spec/fixtures/platform/compat_word.eval.json"));
        m.existsAudioTrans(s, ev.record);
        assertEquals(SkEgnManager.engine_status.STOP, m.getEngineStatus());
        awaitScore();
        assertEquals(Arrays.asList("onRecordStart", "onRecordEnd(json)"), ev.log);
        assertEquals(65, ev.result().getJSONObject("result").getInt("overall"));
        assertEquals(s.getAudioPath(), m.getLastRecordPath());
        Events e2 = new Events();
        m.existsAudioTrans(s, e2.recorder, 4096, 30);
        Main.await("onScore", 5000, () -> e2.json != null);
        assertEquals(Arrays.asList("onStart", "onRecordEnd", "onScore"), e2.log);
        assertEquals(30000, transport.calls().get(1).request.readTimeoutMs);
        Events e3 = new Events();
        m.existsAudioTrans(s, e3.record, null, 5000);
        Main.await("onRecordEnd", 5000, () -> e3.json != null);
        assertEquals(5000, transport.calls().get(2).request.readTimeoutMs);
        Events e4 = new Events();
        m.existsAudioTrans(s, e4.recorder);
        Main.await("onScore", 5000, () -> e4.json != null);
        assertMainThreadOnly();
    }

    @Test
    public void existsAudioTransLocalChecks() throws Exception {
        SkEgnManager m = initManager(null);
        File dir = app.getCacheDir();
        int[][] cases = new int[5][];
        String[] paths = new String[5];
        paths[0] = null;
        paths[1] = new File(dir, "missing.wav").getAbsolutePath();
        File empty = new File(dir, "empty.wav");
        Files.write(empty.toPath(), new byte[0]);
        paths[2] = empty.getAbsolutePath();
        paths[3] = Fixtures.wavFile(dir, "short.wav", new byte[16000]).getAbsolutePath();
        File big = new File(dir, "big.mp3");
        try (RandomAccessFile raf = new RandomAccessFile(big, "rw")) {
            raf.setLength(50L * 1024 * 1024 + 10);
        }
        paths[4] = big.getAbsolutePath();
        File longWav = new File(dir, "long.wav");
        try (RandomAccessFile raf = new RandomAccessFile(longWav, "rw")) {
            raf.write(Wav.header(16000, 1, 16, 301L * 32000));
            raf.setLength(44 + 301L * 32000);
        }
        paths = java.util.Arrays.copyOf(paths, 6);
        cases = java.util.Arrays.copyOf(cases, 6);
        paths[5] = longWav.getAbsolutePath();
        int[] expected = {60001, 60001, 60002, 60005, 60009, 60009};
        for (int i = 0; i < paths.length; i++) {
            Events e = new Events();
            RecordSetting s = sent();
            s.setAudioPath(paths[i]);
            m.existsAudioTrans(s, e.record);
            Main.await("result " + i, 5000, () -> e.json != null);
            assertEquals("case " + i, expected[i], e.result().getInt("errId"));
            cases[i] = new int[] {e.result().getInt("errId")};
        }
        assertEquals(0, transport.count());
    }

    @Test
    public void existsAudioTransUploadsOtherFormats() throws Exception {
        SkEgnManager m = initManager(null);
        File dir = app.getCacheDir();
        File mp3 = new File(dir, "a.mp3");
        byte[] mp3Bytes = new byte[40000];
        mp3Bytes[0] = 'I';
        mp3Bytes[1] = 'D';
        mp3Bytes[2] = '3';
        Files.write(mp3.toPath(), mp3Bytes);
        RecordSetting s = sent();
        s.setAudioPath(mp3.getAbsolutePath());
        m.existsAudioTrans(s, ev.record);
        awaitScore();
        assertTrue(transport.calls().get(0).bodyText().contains("Content-Type: audio/mpeg"));
        File pcm = new File(dir, "a.pcm");
        Files.write(pcm.toPath(), Fixtures.pcm("spec/fixtures/audio/zh_short.wav"));
        Events e2 = new Events();
        RecordSetting p = sent();
        p.setAudioPath(pcm.getAbsolutePath());
        m.existsAudioTrans(p, e2.record);
        Main.await("pcm", 5000, () -> e2.json != null);
        assertEquals(94.6, e2.result().getJSONObject("result").getDouble("overall"), 1e-9);
        String body = transport.calls().get(1).bodyText();
        assertTrue(body.contains("Content-Type: audio/wav"));
        assertTrue(body.contains("RIFF"));
    }

    @Test
    public void recordFileLocations() throws Exception {
        mic(Fixtures.tone(1200, 5000));
        SkEgnManager m = initManager(null);
        File dir = new File(app.getCacheDir(), "rec");
        RecordSetting s = sent();
        s.setRecordFilePath(dir.getAbsolutePath());
        s.setRecordName("q1.mp3");
        s.setAudioType(AudioType.MP3);
        m.startRecord(s, ev.recorder);
        Main.settle(100);
        m.stopRecord();
        awaitScore();
        File f = new File(dir, "q1.mp3");
        assertEquals(f.getAbsolutePath(), m.getLastRecordPath());
        assertNotNull(Wav.parse(f));
        Events e2 = new Events();
        RecordSetting s2 = sent();
        File full = new File(dir, "full.wav");
        s2.setRecordFilePath(full.getAbsolutePath());
        m.startRecord(s2, e2.recorder);
        Main.settle(50);
        m.stopRecord();
        Main.await("second", 5000, () -> e2.json != null);
        assertEquals(full.getAbsolutePath(), m.getLastRecordPath());
        Events e3 = new Events();
        RecordSetting s3 = sent();
        s3.setRecordFilePath("/proc/forbidden-dir");
        m.startRecord(s3, e3.recorder);
        Main.idle();
        assertNotNull(e3.startFail);
    }

    // ---------------------------------------------------------------- retries and options

    @Test
    public void autoRetryResubmitsWithTheSameTokenId() throws Exception {
        mic(Fixtures.tone(1200, 5000));
        for (int i = 0; i < 3; i++) {
            transport.respond(500, FakeTransport.error(50000, "busy"));
        }
        SkEgnManager m = initManager(null);
        RecordSetting s = sent();
        s.setAutoRetry(true);
        m.startRecord(s, ev.recorder);
        Main.settle(80);
        m.stopRecord();
        awaitScore();
        assertEquals(94.6, ev.result().getJSONObject("result").getDouble("overall"), 1e-9);
        List<FakeTransport.Call> calls = transport.calls();
        assertEquals(4, calls.size());
        for (FakeTransport.Call c : calls) {
            assertEquals(ev.result().getString("tokenId"), c.header("Idempotency-Key"));
        }
        assertEquals(3, sleeps.size());
    }

    @Test
    public void withoutAutoRetryRetryableFailuresAre20009() throws Exception {
        mic(Fixtures.tone(1200, 5000));
        for (int i = 0; i < 12; i++) {
            transport.respond(503, "");
        }
        SkEgnManager m = initManager(null);
        m.startRecord(sent(), ev.recorder);
        Main.settle(80);
        m.stopRecord();
        awaitScore();
        assertEquals(20009, ev.result().getInt("errId"));
        assertTrue(ev.result().getString("error").contains("HTTP 503"));
        assertEquals(3, transport.count());
        // autoRetry gives up after 2 extra submissions
        Events e2 = new Events();
        RecordSetting s = sent();
        s.setAutoRetry(true);
        s.setIsStream(true);
        m.startRecord(s, e2.recorder);
        m.feed(Fixtures.tone(1200, 5000));
        m.stopRecord();
        Main.await("second", 5000, () -> e2.json != null);
        assertEquals(20009, e2.result().getInt("errId"));
        assertEquals(12, transport.count());
        assertEquals(2 + (2 + 1 + 2 + 1 + 2), sleeps.size());
    }

    @Test
    public void autoRetryHonoursCustomErrIds() throws Exception {
        for (int i = 0; i < 3; i++) {
            transport.respond(400, FakeTransport.error(40001, "bad"));
        }
        SkEgnManager m = initManager(null);
        RecordSetting s = sent();
        s.setIsStream(true);
        s.setAutoRetry(true);
        s.setErrIds(Arrays.asList(" 40001 ", null));
        m.startRecord(s, ev.recorder);
        m.feed(Fixtures.tone(1200, 5000));
        m.stopRecord();
        awaitScore();
        assertEquals(40001, ev.result().getInt("errId"));
        assertEquals(3, transport.count());
        Events e2 = new Events();
        s.setErrIds(null);
        transport.respond(400, FakeTransport.error(40001, "bad"));
        m.startRecord(s, e2.recorder);
        m.feed(Fixtures.tone(1200, 5000));
        m.stopRecord();
        Main.await("second", 5000, () -> e2.json != null);
        assertEquals(4, transport.count());
    }

    @Test
    public void paramsAndAudioUrlInResult() throws Exception {
        transport.defaultBody("{\"recordId\":\"eval_9\",\"eof\":1,\"result\":{\"overall\":88,\"audioUrl\":\"https://cdn.example.com/a.mp3\"}}");
        SkEgnManager m = initManager(null);
        RecordSetting s = sent().setNeedRequestParamsInResult(true).setNeedAttachAudioUrlInResult(true);
        s.setIsStream(true);
        m.startRecord(s, ev.recorder);
        m.feed(Fixtures.tone(1200, 5000));
        m.stopRecord();
        awaitScore();
        JSONObject r = ev.result();
        assertEquals("https://cdn.example.com/a.mp3", r.getString("audioUrl"));
        JSONObject p = r.getJSONObject("params");
        assertEquals("1", p.getJSONObject("request").getString("attachAudioUrl"));
        assertEquals("sent.eval.cn", p.getJSONObject("request").getString("coreType"));
        assertTrue(transport.calls().get(0).bodyText().contains("name=\"attachAudioUrl\""));
    }

    /** Production returns no audioUrl on the compat path: the envelope has none, the recording stays local. */
    @Test
    public void attachAudioUrlIsAbsentOnTheCompatPath() throws Exception {
        transport.defaultBody(Fixtures.text("spec/fixtures/platform/compat_sent.eval.cn_attach_audio_url.json"));
        mic(Fixtures.tone(1200, 5000));
        SkEgnManager m = initManager(null);
        m.startRecord(sent().setNeedAttachAudioUrlInResult(true), ev.recorder);
        Main.settle(80);
        m.stopRecord();
        awaitScore();
        assertFalse(ev.result().has("audioUrl"));
        assertTrue(transport.calls().get(0).bodyText().contains("name=\"attachAudioUrl\""));
        assertTrue(new File(m.getLastRecordPath()).isFile());
    }

    /** Paragraph: word scores always requested, details[] get Shengtong-style overall and pronunciation. */
    @Test
    public void paragraphResultHasShengtongStyleDetails() throws Exception {
        transport.defaultBody(Fixtures.text("spec/fixtures/platform/compat_para.eval.cn_word_detail.json"));
        SkEgnManager m = initManager(null);
        RecordSetting s = new RecordSetting(CoreType.CN_PARA_EVAL, "今天天气很好。我们一起去公园散步。");
        s.setIsStream(true);
        m.startRecord(s, ev.recorder);
        m.feed(Fixtures.tone(1200, 5000));
        m.stopRecord();
        awaitScore();
        assertTrue(transport.calls().get(0).bodyText().contains(
                "name=\"paragraph_need_word_score\"\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\n1\r\n"));
        org.json.JSONArray sentences = ev.result().getJSONObject("result").getJSONArray("sentences");
        for (int i = 0; i < sentences.length(); i++) {
            org.json.JSONArray details = sentences.getJSONObject(i).getJSONArray("details");
            for (int j = 0; j < details.length(); j++) {
                JSONObject d = details.getJSONObject(j);
                assertEquals(d.getJSONObject("scores").getInt("overall"), d.getInt("overall"));
            }
        }
    }

    @Test
    public void serverAddressAndTimeouts() throws Exception {
        SkEgnManager m = SkEgnManager.getInstance(app);
        EngineSetting es = EngineSetting.getInstance(app).setServerAddress("wss://eval.example.com/").setConnectTimeout(5)
                .setServerTimeout(7);
        m.initEngine("k", "s", null, es);
        RecordSetting s = sent();
        s.setIsStream(true);
        m.startRecord(s, ev.recorder);
        m.feed(Fixtures.tone(1200, 5000));
        m.stopRecord();
        awaitScore();
        FakeTransport.Call c = transport.calls().get(0);
        assertEquals("https://eval.example.com/sent.eval.cn", c.request.url);
        assertEquals(5000, c.request.connectTimeoutMs);
        assertEquals(7000, c.request.readTimeoutMs);
        assertEquals("", ev.result().getString("userId"));
        YuguCompat.setBaseUrl("http://127.0.0.1:1");
        YuguCompat.setTimeouts(1500, 2500, 60000);
        Events e2 = new Events();
        s.setServerTimeout(3);
        m.startRecord(s, e2.recorder);
        m.feed(Fixtures.tone(1200, 5000));
        m.stopRecord();
        Main.await("second", 5000, () -> e2.json != null);
        FakeTransport.Call c2 = transport.calls().get(1);
        assertEquals("http://127.0.0.1:1/sent.eval.cn", c2.request.url);
        assertEquals(1500, c2.request.connectTimeoutMs);
        assertEquals(3000, c2.request.readTimeoutMs);
        Events e3 = new Events();
        RecordSetting s3 = sent();
        s3.setIsStream(true);
        m.startRecord(s3, e3.recorder);
        m.feed(Fixtures.tone(1200, 5000));
        m.stopRecord();
        Main.await("third", 5000, () -> e3.json != null);
        assertEquals(2500, transport.calls().get(2).request.readTimeoutMs);
    }

    @Test
    public void protocolErrorFromPlatform() throws Exception {
        transport.respond(200, "{\"recordId\":\"x\",\"result\":[1,2,");
        SkEgnManager m = initManager(null);
        RecordSetting s = sent();
        s.setIsStream(true);
        m.startRecord(s, ev.recorder);
        m.feed(Fixtures.tone(1200, 5000));
        m.stopRecord();
        awaitScore();
        assertEquals(90005, ev.result().getInt("errId"));
    }

    // ---------------------------------------------------------------- microphone, playback, provision

    @Test
    public void activeAndReleaseMic() throws Exception {
        mic(Fixtures.tone(600, 5000));
        SkEgnManager m = initManager(null);
        assertTrue(m.releaseMic());
        assertTrue(m.activeMic());
        assertTrue(m.activeMic());
        assertEquals(1, audio.opened.get());
        m.startRecord(sent(), ev.recorder);
        Main.idle();
        assertEquals(1, audio.opened.get());
        assertTrue(m.activeMic());
        assertFalse(m.releaseMic());
        m.stopRecord();
        awaitScore();
        assertTrue(m.releaseMic());
        assertTrue(m.releaseMic());
        assertTrue(m.activeMic());
        RecordSetting other = sent();
        other.setAudioSource(android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION);
        Events e2 = new Events();
        m.startRecord(other, e2.recorder);
        Main.idle();
        assertEquals(Integer.valueOf(android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION), audio.lastSource);
        m.cancel();
        m.recycle();
        Main.await("all microphones released", 5000, () -> audio.released.get() == 3);
    }

    @Test
    public void playbackCallbacks() throws Exception {
        SkEgnManager m = initManager(null);
        m.setPlayerListener(ev.player);
        m.playback();
        Main.idle();
        assertEquals("onPlayStartFail", ev.log.get(0));
        File f = Fixtures.wavFile(app.getCacheDir(), "play.wav", new byte[32000]);
        org.robolectric.shadows.ShadowMediaPlayer.addMediaInfo(
                org.robolectric.shadows.util.DataSource.toDataSource(f.getAbsolutePath()),
                new org.robolectric.shadows.ShadowMediaPlayer.MediaInfo(100, 0));
        m.playWithPath(f.getAbsolutePath());
        Main.idle();
        assertTrue(ev.has("onPlayStart"));
        Main.idleFor(500);
        assertTrue(ev.has("onPlayEnd"));
        m.playWithPath(f.getAbsolutePath());
        Main.idle();
        m.stopPlay();
        m.stopPlay();
        Main.idle();
        assertEquals(2, ev.count("onPlayEnd"));
        m.playWithPath("/no/such/file.wav");
        Main.idle();
        assertEquals(2, ev.count("onPlayStartFail"));
        assertMainThreadOnly();
    }

    @Test
    public void provisionAndParams() {
        SkEgnManager m = initManager(null);
        final String[] got = new String[2];
        assertTrue(m.inquireProvision(msg -> got[0] = msg));
        assertTrue(m.inquireProvision("/sdcard/x.provision", msg -> got[1] = msg));
        assertTrue(m.inquireProvision(null));
        Main.idle();
        assertTrue(got[0].contains("\"provision\":\"cloud\""));
        assertEquals(got[0], got[1]);
        assertTrue(m.updateProvision("k", "s"));
        assertTrue(m.updateProvision("/p", "k", "s"));
        m.initParams(null);
        m.initParams(sent());
        m.initParams(new RecordSetting(CoreType.EN_OPEN_EVAL, "x"));
        YuguCompat.setLogLevel(YuguCompat.LOG_DEBUG);
        m.initParams(sent());
        assertNull(m.getLastRecordPath());
    }

    @Test
    public void quickStartAcceptsEitherOrder() throws Exception {
        SkEgnManager m = initManager(null);
        m.startRecord("今天天气很好", CoreType.CN_SENT_EVAL, QType.QTYPE_EMPTY, ev.recorder);
        Main.idle();
        m.cancel();
        Events e2 = new Events();
        m.startRecord(CoreType.CN_SENT_EVAL, "今天天气很好", 0, e2.record);
        Main.idle();
        assertTrue(e2.has("onRecordStart"));
        m.cancel();
        assertTrue(ev.has("onStart"));
    }

    @Test
    public void muteMusicRequestsAudioFocus() throws Exception {
        mic(Fixtures.tone(600, 5000));
        SkEgnManager m = initManager(null);
        RecordSetting s = sent();
        s.setMuteMusic(true);
        m.startRecord(s, ev.recorder);
        Main.settle(100);
        m.stopRecord();
        awaitScore();
        assertEquals(94.6, ev.result().getJSONObject("result").getDouble("overall"), 1e-9);
    }

    @Test
    public void sdkLogSettings() {
        SkEgnManager m = SkEgnManager.getInstance(app);
        EngineSetting es = EngineSetting.getInstance(app).setSDKLogEnabled(true);
        es.setLogLevel(3);
        es.setEnableSaveLogCatToFile(true);
        es.setEnableUploadLog(true);
        m.initEngine("k", "s", "u", es);
        assertEquals(YuguCompat.LOG_DEBUG, YuguCompat.getLogLevel());
        es.setLogLevel(0);
        m.initEngine("k", "s", "u", es);
        assertEquals(YuguCompat.LOG_ERROR, YuguCompat.getLogLevel());
        YuguCompat.setLogLevel(YuguCompat.LOG_OFF);
        es.setLogLevel(2);
        m.initEngine("k", "s", "u", es);
        assertEquals(YuguCompat.LOG_OFF, YuguCompat.getLogLevel());
        File log = new File(new File(com.stkouyu.util.AiUtil.externalFilesDir(app), "log"), "stkouyu_sdk.log");
        assertTrue(log.isFile());
        com.stkouyu.util.LogCat.destorytLogCat();
    }
}
