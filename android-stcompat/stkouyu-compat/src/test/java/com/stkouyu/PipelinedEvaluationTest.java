package com.stkouyu;

import android.content.Context;
import android.media.AudioManager;

import com.shengzhiai.yugu.stcompat.internal.CancelToken;
import com.shengzhiai.yugu.stcompat.internal.CompatConfig;
import com.shengzhiai.yugu.stcompat.internal.CompatEngine;
import com.shengzhiai.yugu.stcompat.internal.HttpTransport;
import com.shengzhiai.yugu.stcompat.internal.Wav;
import com.shengzhiai.yugu.stcompat.internal.YLog;
import com.shengzhiai.yugu.stcompat.testing.CompatTestBase;
import com.shengzhiai.yugu.stcompat.testing.Events;
import com.shengzhiai.yugu.stcompat.testing.FakeAudio;
import com.shengzhiai.yugu.stcompat.testing.FakeWs;
import com.shengzhiai.yugu.stcompat.testing.Fixtures;
import com.shengzhiai.yugu.stcompat.testing.Main;
import com.shengzhiai.yugu.stcompat.testing.ScriptedPlatform;
import com.stkouyu.listener.OnRecorderListener;
import com.stkouyu.setting.RecordSetting;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.shadows.ShadowAudioManager;

import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

/**
 * SDK 2.3.0 pipelined evaluation ({@code YuguCompat.setMaxPendingEvaluations}) on a realtime channel
 * whose final results the test releases one by one ({@link HeldPlatform}) and a REST transport that
 * holds every answer until the test releases it ({@link GatedTransport}): off by default one
 * evaluation at a time exactly as before; on, a new recording runs beside up to n pending sessions,
 * every result reaches the listener of its own start in completion order; the cap; cancel, recycle
 * and clearActivityListener; the REST fallback of a pending session; file evaluations and the
 * STRecorder path; the status, the pending count, the audio focus and the log lines.
 */
@RunWith(RobolectricTestRunner.class)
public class PipelinedEvaluationTest extends CompatTestBase {
    /** Reference texts of three items, long enough for a late progress unit after the audio. */
    static final String[] TEXTS = {"今天天气很好我们一起去公园散步", "春天来了小草从地里钻出来", "我是中国人我们都是中国人"};
    static final String[] RECORD_IDS = {"eval_pipe_a", "eval_pipe_b", "eval_pipe_c"};
    /** 1.92 s of 16 kHz mono PCM16. */
    static final byte[] PCM = Fixtures.pcm("spec/fixtures/audio/zh_short.wav");
    static final String FINAL = Fixtures.text("spec/fixtures/platform/compat_sent.eval.cn.json").trim();

    private final List<String> lines = Collections.synchronizedList(new ArrayList<String>());
    private FakeWs ws;
    private HeldPlatform platform;
    private SkEgnManager m;

    @Before
    public void heldChannel() {
        ws = new FakeWs();
        platform = new HeldPlatform();
        ws.script(platform);
        CompatConfig.setWsConnector(ws);
        YuguCompat.setLogLevel(YuguCompat.LOG_INFO);
        YLog.setSink((level, tag, msg, err) -> lines.add(level + " " + msg));
    }

    @After
    public void releaseRecorder() {
        STRecorder.getInstance().delete();
    }

    // ================================================================ helpers

    /** The fixture result with its own recordId. */
    static String finalBody(String recordId) {
        return FINAL.replace("\"eval_7954d149c40a\"", "\"" + recordId + "\"");
    }

    private static RecordSetting item(int i) {
        return new RecordSetting(CoreType.CN_SENT_EVAL, TEXTS[i]);
    }

    private static RecordSetting stream(int i) {
        RecordSetting s = item(i);
        s.setIsStream(true);
        return s;
    }

    private String token() {
        return CompatEngine.get(app).currentTokenId();
    }

    /** Feeds the fixture into the stream session and waits until round {@code r} has received it. */
    private void feedAll(int r) {
        for (int i = 0; i < PCM.length; i += 3200) {
            m.feed(Arrays.copyOfRange(PCM, i, Math.min(PCM.length, i + 3200)));
        }
        awaitAudio(r, PCM.length);
    }

    private void awaitAudio(final int r, final long bytes) {
        Main.await("audio of round " + r, 10000, () -> platform.count() > r && platform.round(r).bytes() == bytes);
    }

    /** Stops the recording and waits until round {@code r} received end and {@code e} got onRecordEnd. */
    private void stopAndAwaitEnd(final int r, final Events e) {
        m.stopRecord();
        Main.await("end of round " + r, 10000, () -> platform.round(r).ended && e.has("onRecordEnd"));
    }

    private void awaitFinal(final Events e) {
        Main.await("final result", 10000, () -> e.json != null);
    }

    private static void assertMain(Events e) {
        assertTrue("callbacks off the main thread: " + e.offMain, e.offMain.isEmpty());
    }

    private List<String> linesStartingWith(String prefix) {
        List<String> out = new ArrayList<>();
        synchronized (lines) {
            for (String l : lines) {
                if (l.startsWith(prefix)) {
                    out.add(l);
                }
            }
        }
        return out;
    }

    private List<String> linesContaining(String part) {
        List<String> out = new ArrayList<>();
        synchronized (lines) {
            for (String l : lines) {
                if (l.contains(part)) {
                    out.add(l);
                }
            }
        }
        return out;
    }

    /** No key, signature, reference text or result text in any log line. */
    private void assertLogsClean() {
        synchronized (lines) {
            for (String l : lines) {
                assertFalse(l, l.contains("test-secret") || l.contains("test-app-key"));
                assertFalse(l, l.contains("signature") || l.contains("Signature"));
                assertFalse(l, l.contains("\"overall\"") || l.contains("\"result\""));
                for (String t : TEXTS) {
                    assertFalse(l, l.contains(t));
                }
            }
        }
    }

    // ================================================================ off by default

    /** Default 0: start A, stop, start B before A's result: B is refused exactly as in 2.2.0, every overload. */
    @Test
    public void offByDefaultTheNextStartIsRefusedUntilTheResultAsBefore() throws Exception {
        assertEquals(0, YuguCompat.getMaxPendingEvaluations());
        assertEquals("no engine yet", 0, YuguCompat.pendingEvaluations());
        m = initManager(null);
        Events a = new Events();
        m.startRecord(stream(0), a.recorder);
        String tokenA = token();
        assertEquals(0, YuguCompat.pendingEvaluations());
        feedAll(0);
        stopAndAwaitEnd(0, a);
        assertEquals("one evaluation at a time: STOP until the next start", SkEgnManager.engine_status.STOP, m.getEngineStatus());
        assertEquals(1, YuguCompat.pendingEvaluations());

        Events b = new Events();
        m.startRecord(stream(1), b.recorder);
        Events quick = new Events();
        m.startRecord(TEXTS[1], CoreType.CN_SENT_EVAL, QType.QTYPE_EMPTY, quick.recorder);
        Events record = new Events();
        m.startRecord(stream(1), record.record);
        Events quickRecord = new Events();
        m.startRecord(CoreType.CN_SENT_EVAL, TEXTS[1], 0, quickRecord.record);
        RecordSetting file = item(1);
        file.setAudioPath(Fixtures.file("spec/fixtures/audio/zh_short.wav").getAbsolutePath());
        Events f1 = new Events();
        m.existsAudioTrans(file, f1.recorder);
        Events f2 = new Events();
        m.existsAudioTrans(file, f2.recorder, 4096, 30);
        Events f3 = new Events();
        m.existsAudioTrans(file, f3.record);
        Events f4 = new Events();
        m.existsAudioTrans(file, f4.record, null, 5000);
        Main.idle();
        for (Events e : new Events[] {b, quick}) {
            assertEquals("engine is busy", e.startFail);
            assertEquals(Collections.singletonList("onStartRecordFail"), e.log);
        }
        for (Events e : new Events[] {record, quickRecord, f3, f4}) {
            assertEquals(60008, e.result().getInt("errId"));
            assertEquals("引擎正忙，上一次评测未结束", e.result().getString("error"));
            assertEquals(Collections.singletonList("onRecordEnd(json)"), e.log);
        }
        for (Events e : new Events[] {f1, f2}) {
            assertEquals(60008, e.result().getInt("errId"));
            assertEquals(Collections.singletonList("onScore"), e.log);
        }
        assertEquals("the refused starts open no channel", 1, ws.count());
        assertEquals(0, transport.count());
        assertEquals(tokenA, token());
        assertEquals(1, YuguCompat.pendingEvaluations());

        platform.answer(0, RECORD_IDS[0]);
        awaitFinal(a);
        assertEquals(tokenA, a.result().getString("tokenId"));
        assertEquals(RECORD_IDS[0], a.result().getString("recordId"));
        assertEquals(Arrays.asList("onStart", "onRecordEnd", "onScore"), a.log);
        assertEquals(0, YuguCompat.pendingEvaluations());
        assertEquals("unchanged: STOP after the result", SkEgnManager.engine_status.STOP, m.getEngineStatus());
        assertMain(a);

        // the next start works once the result was delivered, as before
        Events next = new Events();
        m.startRecord(stream(1), next.recorder);
        Main.idle();
        assertTrue(next.has("onStart"));
        assertEquals(SkEgnManager.engine_status.RECORDING, m.getEngineStatus());
        m.cancel();
        assertEquals(0, YuguCompat.pendingEvaluations());

        assertEquals(String.valueOf(lines), 4, linesStartingWith("2 startRecord rejected: engine is busy (STOP)").size());
        assertEquals(4, linesStartingWith("2 existsAudioTrans rejected (errId 60008): ").size());
        assertTrue("no pipelined lines when off: " + lines, linesContaining("pipelined").isEmpty());
        assertEquals(1, linesStartingWith("3 timing tokenId=" + tokenA + " ").size());
    }

    // ================================================================ on: back to back, completion order

    /** Forwards every callback to the events of one item. */
    static class Forwarding extends OnRecorderListener {
        final Events e;

        Forwarding(Events e) {
            this.e = e;
        }

        @Override
        public void onStart() {
            e.recorder.onStart();
        }

        @Override
        public void onStartRecordFail(String reason) {
            e.recorder.onStartRecordFail(reason);
        }

        @Override
        public void onPause() {
            e.recorder.onPause();
        }

        @Override
        public void onTick(long millisUntilFinished, double percentUntilFinished) {
            e.recorder.onTick(millisUntilFinished, percentUntilFinished);
        }

        @Override
        public void onRecordEnd() {
            e.recorder.onRecordEnd();
        }

        @Override
        public void onRecording(int vadStatus, int soundIntensity) {
            e.recorder.onRecording(vadStatus, soundIntensity);
        }

        @Override
        public void onScore(String json) {
            e.recorder.onScore(json);
        }
    }

    /**
     * n = 3, the README pattern: three microphone recordings back to back, each next item started
     * from the onRecordEnd of the previous one. The platform answers C first, then A, then B: every
     * final result arrives once, at the listener passed to its own start, with its own tokenId, in
     * completion order; intermediate results never cross over, also a late one of A while B records.
     */
    @Test
    public void threeRecordingsBackToBackDeliverInCompletionOrderToTheirOwnListeners() throws Exception {
        CompatConfig.setAudioInputFactory(new FakeAudio(PCM).endWithNothing());
        YuguCompat.setMaxPendingEvaluations(3);
        m = initManager(null);
        final Events[] ev = {new Events(), new Events(), new Events()};
        final String[] token = new String[3];
        final SkEgnManager.engine_status[] statusAtRecordEnd = new SkEgnManager.engine_status[3];
        final int[] pendingAtRecordEnd = new int[3];
        final List<Integer> finals = Collections.synchronizedList(new ArrayList<Integer>());
        final OnRecorderListener[] listener = new OnRecorderListener[3];
        for (int i = 0; i < 3; i++) {
            final int k = i;
            listener[i] = new Forwarding(ev[i]) {
                @Override
                public void onRecordEnd() {
                    super.onRecordEnd();
                    statusAtRecordEnd[k] = m.getEngineStatus();
                    pendingAtRecordEnd[k] = YuguCompat.pendingEvaluations();
                    if (k < 2) {
                        // the stop handler starts the next item while this one is still evaluated
                        RecordSetting s = item(k + 1);
                        s.setRealtime_feedback(k + 1 == 1 ? 1 : 0);
                        m.startRecord(s, listener[k + 1]);
                        token[k + 1] = token();
                    }
                }

                @Override
                public void onScore(String json) {
                    if (Events.isFinal(json)) {
                        finals.add(k);
                    }
                    super.onScore(json);
                }
            };
        }
        RecordSetting first = item(0);
        first.setRealtime_feedback(1);
        m.startRecord(first, listener[0]);
        token[0] = token();
        for (int i = 0; i < 3; i++) {
            final int k = i;
            awaitAudio(i, PCM.length);
            Main.await("onStart " + i, 5000, () -> ev[k].has("onStart"));
            assertEquals(SkEgnManager.engine_status.RECORDING, m.getEngineStatus());
            assertEquals("earlier items still wait for their result", i, YuguCompat.pendingEvaluations());
            if (i == 1) {
                // A is pending while B records: A's last recognition still reaches A only
                Main.await("A's progress", 5000, () -> ev[0].intermediate().size() == 3);
                Main.await("B's progress", 5000, () -> ev[1].intermediate().size() == 3);
                platform.lateProgress(0);
                Main.await("A's late progress", 5000, () -> ev[0].intermediate().size() == 4);
                assertEquals(SkEgnManager.engine_status.RECORDING, m.getEngineStatus());
                assertEquals(3, ev[1].intermediate().size());
            }
            stopAndAwaitEnd(i, ev[i]);
        }
        for (int i = 0; i < 3; i++) {
            assertEquals("IDLE once the stop is handled, before onRecordEnd", SkEgnManager.engine_status.IDLE,
                    statusAtRecordEnd[i]);
            assertEquals("the session itself is pending in its onRecordEnd", i + 1, pendingAtRecordEnd[i]);
        }
        Main.await("next start", 5000, () -> token[2] != null);
        assertEquals(SkEgnManager.engine_status.IDLE, m.getEngineStatus());
        assertEquals(3, YuguCompat.pendingEvaluations());
        assertEquals(3, new HashSet<>(Arrays.asList(token)).size());
        for (Events e : ev) {
            assertNull("no final yet", e.json);
        }

        platform.answer(2, RECORD_IDS[2]);
        awaitFinal(ev[2]);
        Main.settle(30);
        assertNull(ev[0].json);
        assertNull(ev[1].json);
        assertEquals(2, YuguCompat.pendingEvaluations());
        platform.answer(0, RECORD_IDS[0]);
        awaitFinal(ev[0]);
        assertEquals(1, YuguCompat.pendingEvaluations());
        platform.answer(1, RECORD_IDS[1]);
        awaitFinal(ev[1]);
        Main.settle(30);
        assertEquals(0, YuguCompat.pendingEvaluations());
        assertEquals("completion order, not start order", Arrays.asList(2, 0, 1), finals);

        for (int i = 0; i < 3; i++) {
            Events e = ev[i];
            assertMain(e);
            assertEquals("exactly one final", 1, e.count("onScore"));
            assertEquals(1, e.count("onStart"));
            assertEquals(1, e.count("onRecordEnd"));
            assertEquals("onStart", e.log.get(0));
            assertEquals("the final is the last callback", "onScore", e.log.get(e.log.size() - 1));
            JSONObject r = e.result();
            assertEquals(token[i], r.getString("tokenId"));
            assertEquals(RECORD_IDS[i], r.getString("recordId"));
            assertEquals(TEXTS[i], r.getString("refText"));
            assertEquals(94.6, r.getJSONObject("result").getDouble("overall"), 1e-9);
            for (JSONObject o : e.intermediate()) {
                assertEquals("no cross-talk", token[i], o.getString("tokenId"));
                assertEquals(TEXTS[i], o.getString("refText"));
            }
            // its own connection, key and audio
            HeldPlatform.Round round = platform.round(i);
            assertEquals(token[i], round.conn.query().get("idempotencyKey"));
            assertEquals(token[i], new JSONObject(round.conn.paramFrame()).getString("idempotencyKey"));
            assertEquals(TEXTS[i], round.refText);
            assertArrayEquals(PCM, round.conn.audio());
            // its own record file
            File f = new File(new File(com.stkouyu.util.AiUtil.externalFilesDir(app), "record"), token[i] + ".wav");
            assertEquals(PCM.length, Wav.parse(f).dataLength);
            assertEquals("one timing line per session", 1, linesStartingWith("3 timing tokenId=" + token[i] + " ").size());
            assertEquals(String.valueOf(lines), 1, linesStartingWith(
                    "3 pipelined evaluation tokenId=" + token[i] + " pending=" + i + " maxPendingEvaluations=3").size());
        }
        assertEquals("realtime_feedback on A and B only", 4, ev[0].intermediate().size());
        assertEquals(3, ev[1].intermediate().size());
        assertTrue(ev[2].intermediate().isEmpty());
        assertEquals(0, transport.count());
        assertEquals(3, ws.count());
        assertEquals(1, linesStartingWith("3 pipelined evaluation on: maxPendingEvaluations=3 (was 0)").size());
        assertLogsClean();
    }

    // ================================================================ the cap

    /**
     * n = 1: B records beside pending A; a third session is refused while one is pending and one
     * recording, and still while two are pending; accepted again after a result.
     */
    @Test
    public void capOfOneLetsOneRecordingRunBesideOnePendingSession() throws Exception {
        YuguCompat.setMaxPendingEvaluations(1);
        m = initManager(null);
        Events a = new Events();
        m.startRecord(stream(0), a.recorder);
        feedAll(0);
        stopAndAwaitEnd(0, a);
        assertEquals(SkEgnManager.engine_status.IDLE, m.getEngineStatus());
        Events b = new Events();
        m.startRecord(stream(1), b.recorder);
        String tokenB = token();
        Main.idle();
        assertTrue("one pending: B starts at once", b.has("onStart"));
        assertEquals(SkEgnManager.engine_status.RECORDING, m.getEngineStatus());

        Events c1 = new Events();
        m.startRecord(stream(2), c1.recorder);
        Events c2 = new Events();
        m.startRecord(stream(2), c2.record);
        Events c3 = new Events();
        RecordSetting file = item(2);
        file.setAudioPath(Fixtures.file("spec/fixtures/audio/zh_short.wav").getAbsolutePath());
        m.existsAudioTrans(file, c3.recorder);
        Main.idle();
        assertEquals("refused while B records", "engine is busy", c1.startFail);
        assertEquals(60008, c2.result().getInt("errId"));
        assertEquals(60008, c3.result().getInt("errId"));
        assertEquals("both listener styles", 2,
                linesStartingWith("2 startRecord rejected: engine is busy (RECORDING, pending=1 maxPendingEvaluations=1)").size());

        feedAll(1);
        stopAndAwaitEnd(1, b);
        assertEquals(2, YuguCompat.pendingEvaluations());
        Events c4 = new Events();
        m.startRecord(stream(2), c4.recorder);
        Events c5 = new Events();
        m.existsAudioTrans(file, c5.record);
        Main.idle();
        assertEquals("two pending with a cap of one", "engine is busy", c4.startFail);
        assertEquals(60008, c5.result().getInt("errId"));
        assertEquals(1, linesStartingWith("2 startRecord rejected: engine is busy (IDLE, pending=2 maxPendingEvaluations=1)").size());
        assertEquals(2, ws.count());
        assertEquals(0, transport.count());

        platform.answer(0, RECORD_IDS[0]);
        awaitFinal(a);
        assertEquals(1, YuguCompat.pendingEvaluations());
        Events c = new Events();
        m.startRecord(stream(2), c.recorder);
        String tokenC = token();
        Main.idle();
        assertTrue("accepted again after a result", c.has("onStart"));
        feedAll(2);
        stopAndAwaitEnd(2, c);
        platform.answer(2, RECORD_IDS[2]);
        platform.answer(1, RECORD_IDS[1]);
        awaitFinal(c);
        awaitFinal(b);
        assertEquals(tokenB, b.result().getString("tokenId"));
        assertEquals(RECORD_IDS[1], b.result().getString("recordId"));
        assertEquals(tokenC, c.result().getString("tokenId"));
        assertEquals(RECORD_IDS[2], c.result().getString("recordId"));
        assertEquals(0, YuguCompat.pendingEvaluations());
        for (Events e : new Events[] {a, b, c}) {
            assertEquals(1, e.count("onScore"));
            assertMain(e);
        }
    }

    /**
     * Pipelined: a paused recording refuses a new start like a running one; a microphone that breaks
     * while recording ends with 60004 as before, the status is IDLE again and its slot is free.
     */
    @Test
    public void pausedOrBrokenRecordingsInPipelinedMode() throws Exception {
        YuguCompat.setMaxPendingEvaluations(2);
        m = initManager(null);
        Events a = new Events();
        m.startRecord(stream(0), a.recorder);
        m.pauseRecord();
        assertEquals(SkEgnManager.engine_status.PAUSED, m.getEngineStatus());
        Events b = new Events();
        m.startRecord(stream(1), b.recorder);
        Main.idle();
        assertEquals("engine is busy", b.startFail);
        assertEquals(1, linesStartingWith(
                "2 startRecord rejected: engine is busy (PAUSED, pending=0 maxPendingEvaluations=2)").size());
        m.restartRecord();
        feedAll(0);
        stopAndAwaitEnd(0, a);

        CompatConfig.setAudioInputFactory(new FakeAudio(Fixtures.tone(200, 5000)).readErrorAfterData(-3));
        Events c = new Events();
        m.startRecord(item(1), c.recorder);
        String tokenC = token();
        awaitFinal(c);
        assertEquals(60004, c.result().getInt("errId"));
        assertEquals(tokenC, c.result().getString("tokenId"));
        assertEquals(SkEgnManager.engine_status.IDLE, m.getEngineStatus());
        assertEquals("A still waits for its result", 1, YuguCompat.pendingEvaluations());
        assertFalse("no onRecordEnd after a broken microphone, as before", c.has("onRecordEnd"));
        platform.answer(0, RECORD_IDS[0]);
        awaitFinal(a);
        assertEquals(0, YuguCompat.pendingEvaluations());
        assertEquals(1, a.count("onScore"));
        assertEquals(1, c.count("onScore"));
        assertMain(a);
        assertMain(c);
    }

    /** Switching back to 0 while sessions are pending: one at a time again, the next start waits for all. */
    @Test
    public void switchingOffWaitsForEveryPendingResult() throws Exception {
        YuguCompat.setMaxPendingEvaluations(2);
        m = initManager(null);
        Events a = new Events();
        m.startRecord(stream(0), a.recorder);
        feedAll(0);
        stopAndAwaitEnd(0, a);
        Events b = new Events();
        m.startRecord(stream(1), b.recorder);
        feedAll(1);
        stopAndAwaitEnd(1, b);
        YuguCompat.setMaxPendingEvaluations(0);
        assertEquals(1, linesStartingWith("3 pipelined evaluation off: maxPendingEvaluations=0 (was 2)").size());
        Events c1 = new Events();
        m.startRecord(stream(2), c1.recorder);
        Main.idle();
        assertEquals("engine is busy", c1.startFail);
        platform.answer(1, RECORD_IDS[1]);
        awaitFinal(b);
        Events c2 = new Events();
        m.startRecord(stream(2), c2.record);
        Main.idle();
        assertEquals("A still waits for its result", 60008, c2.result().getInt("errId"));
        platform.answer(0, RECORD_IDS[0]);
        awaitFinal(a);
        Events c = new Events();
        m.startRecord(stream(2), c.recorder);
        Main.idle();
        assertTrue(c.has("onStart"));
        feedAll(2);
        stopAndAwaitEnd(2, c);
        assertEquals("not pipelined: STOP as before", SkEgnManager.engine_status.STOP, m.getEngineStatus());
        platform.answer(2, RECORD_IDS[2]);
        awaitFinal(c);
        assertEquals(SkEgnManager.engine_status.STOP, m.getEngineStatus());
        assertEquals(0, YuguCompat.pendingEvaluations());
    }

    // ================================================================ cancel, recycle, listeners

    /** n = 3: A and B pending, C recording; cancel: nothing calls back afterwards, count 0, the next start works. */
    @Test
    public void cancelEndsTheRecordingAndEveryPendingSessionSilently() throws Exception {
        YuguCompat.setMaxPendingEvaluations(3);
        m = initManager(null);
        Events[] ev = {new Events(), new Events(), new Events()};
        String[] token = new String[3];
        for (int i = 0; i < 3; i++) {
            RecordSetting s = stream(i);
            s.setRealtime_feedback(1);
            m.startRecord(s, ev[i].recorder);
            token[i] = token();
            feedAll(i);
            if (i < 2) {
                stopAndAwaitEnd(i, ev[i]);
            }
        }
        final Events c = ev[2];
        Main.await("C's progress", 5000, () -> c.intermediate().size() == 3);
        assertEquals(SkEgnManager.engine_status.RECORDING, m.getEngineStatus());
        assertEquals(2, YuguCompat.pendingEvaluations());
        int[] before = {ev[0].log.size(), ev[1].log.size(), ev[2].log.size()};

        m.cancel();
        assertEquals(SkEgnManager.engine_status.IDLE, m.getEngineStatus());
        assertEquals(0, YuguCompat.pendingEvaluations());
        // the platform answers anyway: nothing reaches the app
        platform.answer(0, RECORD_IDS[0]);
        platform.answer(1, RECORD_IDS[1]);
        platform.lateProgress(2);
        m.stopRecord();
        Main.settle(300);
        for (int i = 0; i < 3; i++) {
            assertEquals("no callback after cancel: " + ev[i].log, before[i], ev[i].log.size());
            assertNull(ev[i].json);
            final int k = i;
            Main.await("channel " + i + " closed", 5000, () -> ws.get(k).aborted || ws.get(k).closed);
            assertEquals(1, linesStartingWith("3 session cancelled tokenId=" + token[i]).size());
        }
        assertFalse("C never sent end", platform.round(2).ended);
        assertEquals("no REST upload", 0, transport.count());

        Events d = new Events();
        m.startRecord(stream(0), d.recorder);
        String tokenD = token();
        Main.idle();
        assertTrue(d.has("onStart"));
        feedAll(3);
        stopAndAwaitEnd(3, d);
        platform.answer(3, "eval_pipe_d");
        awaitFinal(d);
        assertEquals(tokenD, d.result().getString("tokenId"));
        assertEquals(0, YuguCompat.pendingEvaluations());
        assertMain(d);
    }

    /** recycle with a pending and a recording session: no callbacks, count 0, init again before the next use. */
    @Test
    public void recycleEndsPendingSessionsToo() throws Exception {
        YuguCompat.setMaxPendingEvaluations(2);
        m = initManager(null);
        Events a = new Events();
        m.startRecord(stream(0), a.recorder);
        feedAll(0);
        stopAndAwaitEnd(0, a);
        Events b = new Events();
        m.startRecord(stream(1), b.recorder);
        feedAll(1);
        int[] before = {a.log.size(), b.log.size()};
        m.recycle();
        assertEquals(0L, SkEgnManager.engine);
        assertEquals(SkEgnManager.engine_status.IDLE, m.getEngineStatus());
        assertEquals(0, YuguCompat.pendingEvaluations());
        platform.answer(0, RECORD_IDS[0]);
        Main.settle(200);
        assertEquals(before[0], a.log.size());
        assertEquals(before[1], b.log.size());
        Events e = new Events();
        m.startRecord(stream(2), e.recorder);
        Main.idle();
        assertEquals(60007, e.result().getInt("errId"));
        m = initManager(null);
        Events f = new Events();
        m.startRecord(stream(2), f.recorder);
        Main.idle();
        assertTrue(f.has("onStart"));
        m.cancel();
    }

    /** clearActivityListener drops the listeners of pending sessions too; their results still free the slot. */
    @Test
    public void clearActivityListenerAlsoDropsPendingListeners() throws Exception {
        YuguCompat.setMaxPendingEvaluations(2);
        m = initManager(null);
        Events a = new Events();
        m.startRecord(stream(0), a.recorder);
        feedAll(0);
        stopAndAwaitEnd(0, a);
        Events b = new Events();
        m.startRecord(stream(1), b.recorder);
        feedAll(1);
        int[] before = {a.log.size(), b.log.size()};
        m.clearActivityListener();
        m.stopRecord();
        Main.await("B's end", 5000, () -> platform.round(1).ended);
        platform.answer(1, RECORD_IDS[1]);
        platform.answer(0, RECORD_IDS[0]);
        Main.await("both results handled", 5000, () -> YuguCompat.pendingEvaluations() == 0);
        Main.settle(50);
        assertEquals(before[0], a.log.size());
        assertEquals(before[1], b.log.size());
        Events c = new Events();
        m.startRecord(stream(2), c.recorder);
        Main.idle();
        assertTrue(c.has("onStart"));
        m.cancel();
    }

    // ================================================================ REST fallback of a pending session

    /**
     * n = 1: A's channel fails before its end frame went out while A is pending and B records; A is
     * uploaded over REST with its own tokenId and delivered while B goes on; B is unaffected.
     */
    @Test
    public void aPendingSessionWhoseChannelFailsFallsBackToRestAndStillDelivers() throws Exception {
        GatedTransport rest = new GatedTransport();
        CompatConfig.setTransport(rest);
        YuguCompat.setMaxPendingEvaluations(1);
        m = initManager(null);
        Events a = new Events();
        m.startRecord(stream(0), a.recorder);
        String tokenA = token();
        int head = (PCM.length / 3200 - 2) * 3200;
        for (int i = 0; i < head; i += 3200) {
            m.feed(Arrays.copyOfRange(PCM, i, i + 3200));
        }
        awaitAudio(0, head);
        // the next audio frame of A stays on the client's sending thread: end cannot go out yet
        HeldPlatform.Round roundA = platform.round(0);
        roundA.audioGate = new CountDownLatch(1);
        m.feed(Arrays.copyOfRange(PCM, head, PCM.length));
        assertTrue("A's last audio frame is held", roundA.audioHeld.await(5, TimeUnit.SECONDS));
        m.stopRecord();
        Main.await("A's onRecordEnd", 5000, () -> a.has("onRecordEnd"));
        assertEquals(SkEgnManager.engine_status.IDLE, m.getEngineStatus());
        assertEquals(1, YuguCompat.pendingEvaluations());

        Events b = new Events();
        m.startRecord(stream(1), b.recorder);
        String tokenB = token();
        Main.idle();
        assertTrue(b.has("onStart"));
        // A's channel breaks while A is pending and B records
        roundA.conn.serverFail(new EOFException("connection reset by peer"));
        roundA.audioGate.countDown();
        Main.await("A's REST upload", 5000, () -> rest.count() == 1);
        assertEquals(tokenA, rest.key(0));
        assertTrue(rest.request(0).url.endsWith("/sent.eval.cn"));
        byte[] body = rest.request(0).body.toByteArray();
        assertTrue("the whole recording is uploaded", body.length > PCM.length + Wav.HEADER_SIZE);
        assertFalse(roundA.ended);
        assertEquals(1, linesStartingWith("2 realtime channel failed before end").size());

        feedAll(1);
        assertEquals(SkEgnManager.engine_status.RECORDING, m.getEngineStatus());
        rest.release(0, "eval_pipe_rest");
        awaitFinal(a);
        assertEquals("delivered while B still records", SkEgnManager.engine_status.RECORDING, m.getEngineStatus());
        assertEquals(tokenA, a.result().getString("tokenId"));
        assertEquals("eval_pipe_rest", a.result().getString("recordId"));
        assertEquals(TEXTS[0], a.result().getString("refText"));
        assertEquals(0, YuguCompat.pendingEvaluations());
        assertTrue(linesStartingWith("3 timing tokenId=" + tokenA + " ").get(0).contains(" mode=ws>rest "));

        stopAndAwaitEnd(1, b);
        platform.answer(1, RECORD_IDS[1]);
        awaitFinal(b);
        assertEquals(tokenB, b.result().getString("tokenId"));
        assertEquals(RECORD_IDS[1], b.result().getString("recordId"));
        assertTrue(linesStartingWith("3 timing tokenId=" + tokenB + " ").get(0).contains(" mode=ws "));
        assertEquals("B never touched REST", 1, rest.count());
        for (Events e : new Events[] {a, b}) {
            assertEquals(1, e.count("onScore"));
            assertEquals("onScore", e.log.get(e.log.size() - 1));
            assertMain(e);
        }
    }

    private static int indexOf(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= hay.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    /**
     * A fixed recordName, fine one at a time: the next recording goes to its own file instead of
     * truncating the file a pending session still uploads; once that result is in, the name is free.
     */
    @Test
    public void aFixedRecordNameNeverOverwritesAPendingRecording() throws Exception {
        GatedTransport rest = new GatedTransport();
        CompatConfig.setTransport(rest);
        YuguCompat.setStreamUpload(false);
        YuguCompat.setMaxPendingEvaluations(2);
        m = initManager(null);
        File dir = new File(app.getCacheDir(), "fixed");
        File fixed = new File(dir, "answer.wav");
        Events a = new Events();
        RecordSetting sa = stream(0);
        sa.setRecordFilePath(dir.getAbsolutePath());
        sa.setRecordName("answer.wav");
        m.startRecord(sa, a.recorder);
        String tokenA = token();
        assertEquals(fixed.getAbsolutePath(), m.getLastRecordPath());
        m.feed(PCM);
        m.stopRecord();
        Main.await("A's upload", 5000, () -> a.has("onRecordEnd") && rest.count() == 1);
        assertEquals(tokenA, rest.key(0));

        Events b = new Events();
        RecordSetting sb = stream(1);
        sb.setRecordFilePath(dir.getAbsolutePath());
        sb.setRecordName("answer.wav");
        m.startRecord(sb, b.recorder);
        String tokenB = token();
        File own = new File(dir, "answer-" + tokenB + ".wav");
        assertEquals("B records to its own file", own.getAbsolutePath(), m.getLastRecordPath());
        byte[] tone = Fixtures.tone(1500, 6000);
        m.feed(tone);
        m.stopRecord();
        Main.await("B's upload", 5000, () -> b.has("onRecordEnd") && rest.count() == 2);
        assertEquals(1, linesStartingWith("2 record file answer.wav still belongs to an evaluation waiting for its result; tokenId "
                + tokenB + " records to answer-" + tokenB + ".wav").size());
        // A's file and A's upload, read only now, still hold A's audio
        Wav.Info ia = Wav.parse(fixed);
        assertEquals(PCM.length, ia.dataLength);
        byte[] fileA = java.nio.file.Files.readAllBytes(fixed.toPath());
        assertArrayEquals(PCM, Arrays.copyOfRange(fileA, (int) ia.dataOffset, fileA.length));
        byte[] bodyA = rest.request(0).body.toByteArray();
        assertTrue(indexOf(bodyA, Arrays.copyOfRange(PCM, PCM.length - 4096, PCM.length)) > 0);
        assertEquals(tone.length, Wav.parse(own).dataLength);

        rest.release(0, "eval_fixed_a");
        awaitFinal(a);
        assertEquals(tokenA, a.result().getString("tokenId"));
        // answer.wav is free again while B still waits for its result
        Events c = new Events();
        RecordSetting sc = stream(2);
        sc.setRecordFilePath(dir.getAbsolutePath());
        sc.setRecordName("answer.wav");
        m.startRecord(sc, c.recorder);
        assertEquals(fixed.getAbsolutePath(), m.getLastRecordPath());
        m.cancel();
        assertEquals(0, YuguCompat.pendingEvaluations());
        assertEquals("B's own file stays", tone.length, Wav.parse(own).dataLength);
    }

    // ================================================================ file evaluations and STRecorder

    /** existsAudioTrans counts as a pending session the same way; the status follows recordings only. */
    @Test
    public void fileEvaluationsCountAsPendingSessions() throws Exception {
        GatedTransport rest = new GatedTransport();
        CompatConfig.setTransport(rest);
        YuguCompat.setMaxPendingEvaluations(2);
        m = initManager(null);
        RecordSetting file = item(0);
        file.setAudioPath(Fixtures.file("spec/fixtures/audio/zh_short.wav").getAbsolutePath());
        Events f1 = new Events();
        m.existsAudioTrans(file, f1.recorder);
        String token1 = token();
        assertEquals("a file evaluation is no recording", SkEgnManager.engine_status.IDLE, m.getEngineStatus());
        Events f2 = new Events();
        m.existsAudioTrans(file, f2.record, null, 30);
        String token2 = token();
        Main.idle();
        assertEquals(Arrays.asList("onStart", "onRecordEnd"), f1.log);
        assertEquals(Collections.singletonList("onRecordStart"), f2.log);
        assertEquals(2, YuguCompat.pendingEvaluations());
        Main.await("two uploads", 5000, () -> rest.count() == 2);

        Events c = new Events();
        m.startRecord(stream(1), c.recorder);
        String tokenC = token();
        Main.idle();
        assertTrue("two pending, cap two: the recording starts", c.has("onStart"));
        Events f3 = new Events();
        m.existsAudioTrans(file, f3.recorder);
        Main.idle();
        assertEquals("refused while C records", 60008, f3.result().getInt("errId"));
        feedAll(0);
        stopAndAwaitEnd(0, c);
        assertEquals(3, YuguCompat.pendingEvaluations());
        Events f4 = new Events();
        m.existsAudioTrans(file, f4.recorder);
        Main.idle();
        assertEquals("three pending, cap two", 60008, f4.result().getInt("errId"));

        rest.release(rest.indexOf(token2), "eval_pipe_f2");
        Main.await("f2", 5000, () -> f2.json != null);
        assertEquals(token2, f2.result().getString("tokenId"));
        assertEquals("eval_pipe_f2", f2.result().getString("recordId"));
        assertEquals(Arrays.asList("onRecordStart", "onRecordEnd(json)"), f2.log);
        Events f5 = new Events();
        m.existsAudioTrans(file, f5.recorder);
        String token5 = token();
        Main.idle();
        assertTrue("accepted again after a result", f5.has("onStart"));
        assertEquals(SkEgnManager.engine_status.IDLE, m.getEngineStatus());
        assertEquals(3, YuguCompat.pendingEvaluations());
        Main.await("third upload", 5000, () -> rest.count() == 3);
        assertEquals(30000, rest.request(rest.indexOf(token2)).readTimeoutMs);

        platform.answer(0, "eval_pipe_c");
        rest.release(rest.indexOf(token5), "eval_pipe_f5");
        rest.release(rest.indexOf(token1), "eval_pipe_f1");
        awaitFinal(c);
        awaitFinal(f5);
        awaitFinal(f1);
        assertEquals(tokenC, c.result().getString("tokenId"));
        assertEquals("eval_pipe_c", c.result().getString("recordId"));
        assertEquals(token5, f5.result().getString("tokenId"));
        assertEquals("eval_pipe_f5", f5.result().getString("recordId"));
        assertEquals(token1, f1.result().getString("tokenId"));
        assertEquals("eval_pipe_f1", f1.result().getString("recordId"));
        assertEquals(0, YuguCompat.pendingEvaluations());
        assertEquals(1, linesStartingWith("3 pipelined evaluation tokenId=" + token1 + " pending=0 maxPendingEvaluations=2 (existsAudioTrans)").size());
        assertEquals(1, linesStartingWith("3 pipelined evaluation tokenId=" + token2 + " pending=1 maxPendingEvaluations=2 (existsAudioTrans)").size());
        for (Events e : new Events[] {f1, f2, c, f5}) {
            assertMain(e);
        }
    }

    private File recordWithStRecorder(STRecorder r, String name) {
        final AtomicInteger bytes = new AtomicInteger();
        File out = new File(app.getCacheDir(), "pipe/" + name);
        r.start(out.getAbsolutePath(), (data, size) -> bytes.addAndGet(size));
        Main.await("STRecorder audio", 5000, () -> bytes.get() >= PCM.length);
        r.stop();
        return out;
    }

    /**
     * The STRecorder path: the app records with STRecorder and evaluates each file with
     * existsAudioTrans. Off: the second file waits for the first result as before. On: STRecorder
     * records the next item while the previous file is evaluated, results in completion order.
     */
    @Test
    public void stRecorderFilesEvaluatedBackToBack() throws Exception {
        GatedTransport rest = new GatedTransport();
        CompatConfig.setTransport(rest);
        CompatConfig.setAudioInputFactory(new FakeAudio(PCM).endWithNothing());
        m = initManager(null);
        STRecorder r = STRecorder.getInstance(app);
        RecordSetting s1 = item(0);
        s1.setAudioPath(recordWithStRecorder(r, "one.wav").getAbsolutePath());
        Events e1 = new Events();
        m.existsAudioTrans(s1, e1.recorder);
        File two = recordWithStRecorder(r, "two.wav");
        RecordSetting s2 = item(1);
        s2.setAudioPath(two.getAbsolutePath());
        Events refused = new Events();
        m.existsAudioTrans(s2, refused.recorder);
        Events refused2 = new Events();
        m.existsAudioTrans(s2, refused2.record);
        Main.idle();
        assertEquals("off: as in 2.2.0", 60008, refused.result().getInt("errId"));
        assertEquals(60008, refused2.result().getInt("errId"));
        Main.await("first upload", 5000, () -> rest.count() == 1);
        rest.release(0, "eval_st_1");
        awaitFinal(e1);
        Events e2 = new Events();
        m.existsAudioTrans(s2, e2.recorder);
        Main.await("second upload", 5000, () -> rest.count() == 2);
        rest.release(1, "eval_st_2");
        awaitFinal(e2);
        assertEquals("eval_st_2", e2.result().getString("recordId"));

        YuguCompat.setMaxPendingEvaluations(1);
        RecordSetting s3 = item(0);
        s3.setAudioPath(recordWithStRecorder(r, "three.wav").getAbsolutePath());
        Events e3 = new Events();
        m.existsAudioTrans(s3, e3.recorder);
        String token3 = token();
        RecordSetting s4 = item(1);
        s4.setAudioPath(recordWithStRecorder(r, "four.wav").getAbsolutePath());
        Events e4 = new Events();
        m.existsAudioTrans(s4, e4.record);
        String token4 = token();
        Main.await("two more uploads", 5000, () -> rest.count() == 4);
        assertEquals(2, YuguCompat.pendingEvaluations());
        rest.release(rest.indexOf(token4), "eval_st_4");
        awaitFinal(e4);
        assertNull(e3.json);
        rest.release(rest.indexOf(token3), "eval_st_3");
        awaitFinal(e3);
        assertEquals(token3, e3.result().getString("tokenId"));
        assertEquals(TEXTS[0], e3.result().getString("refText"));
        assertEquals(token4, e4.result().getString("tokenId"));
        assertEquals(TEXTS[1], e4.result().getString("refText"));
        assertEquals(0, YuguCompat.pendingEvaluations());
    }

    // ================================================================ audio focus, switch

    /**
     * muteMusic on two overlapping sessions: A's stop is handled after B took the focus again, so A
     * must not give it back while B records; B, the last one, gives it back.
     */
    @Test
    public void musicStaysMutedWhileTheNextRecordingRuns() throws Exception {
        YuguCompat.setMaxPendingEvaluations(1);
        YuguCompat.setStreamUpload(false);
        // every read takes 400 ms: A's capture thread ends well after B has started
        CompatConfig.setAudioInputFactory(new FakeAudio(Fixtures.tone(2000, 6000)).pacing(400));
        ShadowAudioManager am = shadowOf((AudioManager) app.getSystemService(Context.AUDIO_SERVICE));
        m = initManager(null);
        Events a = new Events();
        RecordSetting sa = item(0);
        sa.setMuteMusic(true);
        m.startRecord(sa, a.recorder);
        // A's capture thread is inside its first read, which returns about 300 ms after the stop
        Main.settle(100);
        assertNotNull(am.getLastAudioFocusRequest());
        m.stopRecord();
        Events b = new Events();
        RecordSetting sb = item(1);
        sb.setMuteMusic(true);
        m.startRecord(sb, b.recorder);
        String tokenB = token();
        Main.idle();
        assertTrue(b.has("onStart"));
        awaitFinal(a);
        assertTrue(a.has("onRecordEnd"));
        List<String> order = new ArrayList<>();
        synchronized (lines) {
            for (String l : lines) {
                if (l.startsWith("3 recording ended tokenId=") || l.startsWith("3 recording started tokenId=" + tokenB)) {
                    order.add(l.substring(2, 17));
                }
            }
        }
        assertEquals("A's stop was handled after B started", Arrays.asList("recording start", "recording ended"), order);
        assertNull("A gave the music back while B records", am.getLastAbandonedAudioFocusListener());
        m.stopRecord();
        awaitFinal(b);
        assertNotNull("the last session gives the music back", am.getLastAbandonedAudioFocusListener());
    }

    @Test
    public void theSwitchIsClampedAndEveryChangeLogsOneInfoLine() {
        assertEquals(0, YuguCompat.getMaxPendingEvaluations());
        YuguCompat.setMaxPendingEvaluations(3);
        assertEquals(3, YuguCompat.getMaxPendingEvaluations());
        YuguCompat.setMaxPendingEvaluations(3);
        YuguCompat.setMaxPendingEvaluations(99);
        assertEquals(16, YuguCompat.getMaxPendingEvaluations());
        YuguCompat.setMaxPendingEvaluations(16);
        YuguCompat.setMaxPendingEvaluations(-4);
        assertEquals(0, YuguCompat.getMaxPendingEvaluations());
        YuguCompat.setMaxPendingEvaluations(-1);
        YuguCompat.setMaxPendingEvaluations(2);
        YuguCompat.reset();
        assertEquals(0, YuguCompat.getMaxPendingEvaluations());
        List<String> changes = linesContaining("maxPendingEvaluations");
        assertEquals(Arrays.asList(
                "3 pipelined evaluation on: maxPendingEvaluations=3 (was 0)",
                "3 pipelined evaluation on: maxPendingEvaluations=16 (was 3)",
                "3 pipelined evaluation off: maxPendingEvaluations=0 (was 16)",
                "3 pipelined evaluation on: maxPendingEvaluations=2 (was 0)",
                "3 pipelined evaluation off: maxPendingEvaluations=0 (was 2)"), changes);
        assertEquals(0, YuguCompat.pendingEvaluations());
    }

    // ================================================================ test platform

    /**
     * Compat realtime channel that holds every final result until {@link #answer}: STARTED for the
     * parameter frame, for realtime_feedback rounds a progress frame per 16000 bytes (one unit per
     * 8000 bytes, as {@link ScriptedPlatform}), and nothing at end.
     */
    static final class HeldPlatform implements FakeWs.Script {
        /** One connection. */
        static final class Round {
            final FakeWs.Conn conn;
            final String refText;
            final boolean realtime;
            final String[] units;
            private long bytes;
            private long lastProgress;
            private int reported;
            volatile boolean ended;
            /** When set, audio frames wait for it on the client's sending thread. */
            volatile CountDownLatch audioGate;
            final CountDownLatch audioHeld = new CountDownLatch(1);

            Round(FakeWs.Conn conn, JSONObject param) {
                this.conn = conn;
                this.refText = param.optString("refText", "");
                this.realtime = param.optBoolean("realtime_feedback", false);
                this.units = ScriptedPlatform.chineseUnits(refText);
            }

            synchronized long bytes() {
                return bytes;
            }
        }

        private final List<Round> rounds = Collections.synchronizedList(new ArrayList<Round>());
        private final Map<FakeWs.Conn, Round> byConn = Collections.synchronizedMap(new IdentityHashMap<FakeWs.Conn, Round>());

        int count() {
            return rounds.size();
        }

        Round round(int i) {
            return rounds.get(i);
        }

        @Override
        public void onText(FakeWs.Conn c, String text) {
            if (FakeWs.END.equals(text)) {
                byConn.get(c).ended = true;
                return;
            }
            Round r;
            try {
                r = new Round(c, new JSONObject(text));
            } catch (JSONException e) {
                throw new AssertionError(e);
            }
            byConn.put(c, r);
            rounds.add(r);
            c.serverText(ScriptedPlatform.STARTED);
        }

        @Override
        public void onBinary(FakeWs.Conn c, byte[] data) {
            Round r = byConn.get(c);
            CountDownLatch gate = r.audioGate;
            if (gate != null) {
                r.audioHeld.countDown();
                try {
                    gate.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            String frame = null;
            synchronized (r) {
                r.bytes += data.length;
                if (r.realtime && r.bytes - r.lastProgress >= 16000) {
                    r.lastProgress = r.bytes;
                    int read = (int) Math.min(r.units.length, r.bytes / 8000);
                    frame = ScriptedPlatform.progressFrame(r.units, r.reported, read, -1, r.bytes);
                    r.reported = read;
                }
            }
            if (frame != null) {
                c.serverText(frame);
            }
        }

        /** One more unit read, as the platform's recognition of the last audio. */
        void lateProgress(int i) {
            Round r = round(i);
            String frame;
            synchronized (r) {
                int read = Math.min(r.units.length, r.reported + 1);
                frame = ScriptedPlatform.progressFrame(r.units, r.reported, read, -1, r.bytes);
                r.reported = read;
            }
            r.conn.serverText(frame);
        }

        /** The final result of round {@code i}: the fixture with its own recordId. */
        void answer(int i, String recordId) {
            round(i).conn.serverText(finalBody(recordId));
        }
    }

    /** REST transport that holds every call until {@link #release}; a cancelled call ends at once. */
    static final class GatedTransport implements HttpTransport {
        private final List<Request> requests = new ArrayList<>();
        private final List<CountDownLatch> gates = new ArrayList<>();
        private final Map<Integer, String> bodies = new HashMap<>();

        @Override
        public Response execute(Request request, CancelToken token) throws IOException {
            CountDownLatch gate = new CountDownLatch(1);
            int i;
            synchronized (this) {
                i = requests.size();
                requests.add(request);
                gates.add(gate);
            }
            while (gate.getCount() > 0) {
                if (token != null && !token.sleep(5)) {
                    throw new IOException("cancelled");
                }
            }
            String body;
            synchronized (this) {
                body = bodies.get(i);
            }
            return new Response(200, new HashMap<String, String>(), body != null ? body : FINAL);
        }

        synchronized int count() {
            return requests.size();
        }

        synchronized Request request(int i) {
            return requests.get(i);
        }

        synchronized String key(int i) {
            return requests.get(i).headers.get("Idempotency-Key");
        }

        synchronized int indexOf(String idempotencyKey) {
            for (int i = 0; i < requests.size(); i++) {
                if (idempotencyKey.equals(requests.get(i).headers.get("Idempotency-Key"))) {
                    return i;
                }
            }
            throw new AssertionError("no call with key " + idempotencyKey);
        }

        void release(int i, String recordId) {
            CountDownLatch gate;
            synchronized (this) {
                bodies.put(i, finalBody(recordId));
                gate = gates.get(i);
            }
            gate.countDown();
        }
    }
}
