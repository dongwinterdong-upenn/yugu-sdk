package com.stkouyu;

import com.shengzhiai.yugu.stcompat.internal.CompatConfig;
import com.shengzhiai.yugu.stcompat.internal.CompatErrIds;
import com.shengzhiai.yugu.stcompat.internal.SkEgnEmulator;
import com.shengzhiai.yugu.stcompat.internal.YLog;
import com.shengzhiai.yugu.stcompat.testing.CompatTestBase;
import com.shengzhiai.yugu.stcompat.testing.Events;
import com.shengzhiai.yugu.stcompat.testing.FakeAudio;
import com.shengzhiai.yugu.stcompat.testing.Fixtures;
import com.shengzhiai.yugu.stcompat.testing.Main;
import com.stkouyu.setting.RecordSetting;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * SDK 2.2.0, unsupported coreType: the 60003 text names the kernel, every entry point logs one WARN
 * line, SkEgn answers at skegn_start, and YuguCompat.setThrowOnUnsupportedCoreType(true) throws
 * IllegalArgumentException from every startRecord and existsAudioTrans overload and from skegn_start.
 */
@RunWith(RobolectricTestRunner.class)
public class UnsupportedCoreTypeTest extends CompatTestBase {
    private static final String CFG = "{\"appKey\":\"k\",\"secretKey\":\"s\"}";

    private final List<String> lines = Collections.synchronizedList(new ArrayList<String>());
    private FakeAudio audio;

    /** One SkEgnManager entry point, called with a setting and the listeners of an Events. */
    private interface Entry {
        void call(SkEgnManager m, RecordSetting s, Events e);
    }

    private static final String[] NAMES = {
        "startRecord(RecordSetting, OnRecorderListener)", "startRecord(RecordSetting, OnRecordListener)",
        "startRecord(String, String, int, OnRecorderListener)", "startRecord(String, String, int, OnRecordListener)",
        "existsAudioTrans(RecordSetting, OnRecorderListener)", "existsAudioTrans(RecordSetting, OnRecordListener)",
        "existsAudioTrans(RecordSetting, OnRecorderListener, Integer, Integer)",
        "existsAudioTrans(RecordSetting, OnRecordListener, Integer, Integer)"};

    /** Unsupported kernels of the Shengtong SDK, one per entry point. */
    private static final String[] KERNELS = {CoreType.EN_OPEN_EVAL, CoreType.EN_CHOICE_REC, CoreType.EN_ASR_REC,
        CoreType.EN_ALIGN_EVAL, CoreType.EN_ASR_EVAL, CoreType.FR_SENT_EVAL, CoreType.JP_WORD_EVAL, CoreType.KR_PARA_EVAL};

    @SuppressWarnings("deprecation")
    private static final Entry[] ENTRIES = {
        (m, s, e) -> m.startRecord(s, e.recorder),
        (m, s, e) -> m.startRecord(s, e.record),
        (m, s, e) -> m.startRecord("hello", s.getCoreType(), QType.QTYPE_SENTENCE_ALOUD, e.recorder),
        (m, s, e) -> m.startRecord(s.getCoreType(), "hello", QType.QTYPE_SENTENCE_ALOUD, e.record),
        (m, s, e) -> m.existsAudioTrans(s, e.recorder),
        (m, s, e) -> m.existsAudioTrans(s, e.record),
        (m, s, e) -> m.existsAudioTrans(s, e.recorder, 1024, 30),
        (m, s, e) -> m.existsAudioTrans(s, e.record, 1024, 30),
    };

    private final List<String> results = Collections.synchronizedList(new ArrayList<String>());
    private final List<String> ids = Collections.synchronizedList(new ArrayList<String>());
    private final SkEgn.skegn_callback cb = (id, type, data, size) -> {
        ids.add(new String(id, StandardCharsets.US_ASCII).trim());
        results.add(new String(data, 0, size, StandardCharsets.UTF_8) + (Main.onMain() ? "" : " OFF MAIN"));
        return 0;
    };

    @Before
    public void capture() {
        audio = new FakeAudio(Fixtures.tone(1500, 5000));
        CompatConfig.setAudioInputFactory(audio);
        YLog.setSink((level, tag, msg, err) -> lines.add(level + " " + msg));
    }

    private static RecordSetting setting(String coreType) {
        RecordSetting s = new RecordSetting(coreType, "hello");
        s.setAudioPath(Fixtures.file("spec/fixtures/audio/en_apple.wav").getAbsolutePath());
        return s;
    }

    private static String param(String coreType) {
        return "{\"app\":{\"userId\":\"u\"},\"audio\":{\"audioType\":\"wav\",\"sampleRate\":16000,\"channel\":1,"
                + "\"sampleBytes\":2},\"request\":{\"coreType\":\"" + coreType + "\",\"refText\":\"hello\"}}";
    }

    private List<String> warnLines(String part) {
        List<String> out = new ArrayList<>();
        synchronized (lines) {
            for (String l : lines) {
                if (l.startsWith(YLog.WARN + " ") && l.contains(part)) {
                    out.add(l);
                }
            }
        }
        return out;
    }

    private static void assertErrorJson(String json, String coreType, String appKey) throws Exception {
        JSONObject o = new JSONObject(json);
        List<String> keys = new ArrayList<>();
        for (Iterator<String> it = o.keys(); it.hasNext(); ) {
            keys.add(it.next());
        }
        assertEquals("the rest of the error JSON is unchanged", Arrays.asList("tokenId", "errId", "error", "eof", "applicationId"),
                keys);
        assertTrue(o.getString("tokenId").matches("[0-9a-f]{32}"));
        assertEquals(60003, o.getInt("errId"));
        assertEquals("不支持的评测内核：" + coreType, o.getString("error"));
        assertEquals(1, o.getInt("eof"));
        assertEquals(appKey, o.getString("applicationId"));
    }

    // ================================================================ the text

    @Test
    public void textNamesTheTrimmedCoreTypeOrNotGiven() {
        assertEquals("不支持的评测内核", CompatErrIds.message(60003));
        assertEquals("不支持的评测内核：open.eval", CompatErrIds.unsupportedCoreType("open.eval"));
        assertEquals("不支持的评测内核：choice.rec", CompatErrIds.unsupportedCoreType("  choice.rec\t"));
        assertEquals("不支持的评测内核：未指定", CompatErrIds.unsupportedCoreType(null));
        assertEquals("不支持的评测内核：未指定", CompatErrIds.unsupportedCoreType(""));
        assertEquals("不支持的评测内核：未指定", CompatErrIds.unsupportedCoreType(" \n "));
    }

    // ================================================================ switch off: callbacks as before

    @Test
    public void everyManagerEntryPointCallsBackWithTheTextAndOneWarnLine() throws Exception {
        assertFalse("off by default", YuguCompat.isThrowOnUnsupportedCoreType());
        SkEgnManager m = initManager(null);
        for (int i = 0; i < ENTRIES.length; i++) {
            Events e = new Events();
            lines.clear();
            ENTRIES[i].call(m, setting(KERNELS[i]), e);
            Main.idle();
            assertTrue(NAMES[i] + ": " + e.log, e.json != null);
            assertErrorJson(e.json, KERNELS[i], "test-app-key");
            assertEquals(NAMES[i] + " gets nothing else: " + e.log, 1, e.log.size());
            assertTrue(NAMES[i], e.offMain.isEmpty());
            List<String> warn = warnLines("errId 60003");
            assertEquals(NAMES[i] + ": " + lines, 1, warn.size());
            assertTrue(warn.get(0), warn.get(0).contains("rejected (errId 60003): 不支持的评测内核：" + KERNELS[i]));
            assertTrue(warn.get(0), warn.get(0).startsWith("2 " + (i < 4 ? "startRecord" : "existsAudioTrans") + " "));
        }
        assertEquals("no network", 0, transport.count());
        assertEquals("no microphone", 0, audio.opened.get());
        assertEquals(SkEgnManager.engine_status.IDLE, m.getEngineStatus());
    }

    @Test
    public void blankOrMissingCoreTypeIsNamedNotGiven() throws Exception {
        SkEgnManager m = initManager(null);
        Events e = new Events();
        m.startRecord((RecordSetting) null, e.recorder);
        Main.idle();
        assertErrorJson(e.json, "未指定", "test-app-key");
        Events e2 = new Events();
        m.existsAudioTrans(new RecordSetting("   ", "hello"), e2.record);
        Main.idle();
        assertErrorJson(e2.json, "未指定", "test-app-key");
        assertEquals(2, warnLines("不支持的评测内核：未指定").size());
        // the order of the checks is as before: a missing engine wins over the coreType
        TestHooks.resetManager();
        Events e3 = new Events();
        SkEgnManager.getInstance(app).startRecord(setting(CoreType.EN_OPEN_EVAL), e3.recorder);
        Main.idle();
        assertEquals(60007, e3.result().getInt("errId"));
    }

    @Test
    public void initParamsNamesTheCoreType() {
        SkEgnManager m = initManager(null);
        m.initParams(setting(CoreType.EN_ASR_REC));
        assertEquals(1, warnLines("initParams: setting would be rejected with errId 60003: 不支持的评测内核：asr.rec").size());
    }

    // ================================================================ switch on: IllegalArgumentException

    @Test
    public void switchOnThrowsFromEveryManagerEntryPoint() throws Exception {
        YuguCompat.setThrowOnUnsupportedCoreType(true);
        assertTrue(YuguCompat.isThrowOnUnsupportedCoreType());
        SkEgnManager m = initManager(null);
        List<Events> all = new ArrayList<>();
        for (int i = 0; i < ENTRIES.length; i++) {
            Events e = new Events();
            all.add(e);
            lines.clear();
            try {
                ENTRIES[i].call(m, setting(KERNELS[i]), e);
                fail(NAMES[i] + " did not throw");
            } catch (IllegalArgumentException expected) {
                assertEquals(NAMES[i], "不支持的评测内核：" + KERNELS[i], expected.getMessage());
            }
            List<String> warn = warnLines("errId 60003");
            assertEquals(NAMES[i] + ": " + lines, 1, warn.size());
            assertTrue(warn.get(0), warn.get(0).contains(KERNELS[i]) && warn.get(0).contains("IllegalArgumentException"));
        }
        Main.settle(50);
        for (int i = 0; i < all.size(); i++) {
            assertTrue(NAMES[i] + " must not call back: " + all.get(i).log, all.get(i).log.isEmpty());
        }
        assertEquals("no network", 0, transport.count());
        assertEquals("no microphone", 0, audio.opened.get());
        assertEquals(SkEgnManager.engine_status.IDLE, m.getEngineStatus());
        // a missing coreType too
        try {
            m.startRecord((RecordSetting) null, new Events().recorder);
            fail();
        } catch (IllegalArgumentException expected) {
            assertEquals("不支持的评测内核：未指定", expected.getMessage());
        }
    }

    @Test
    public void switchOnThrowsBeforeTheStateChecks() throws Exception {
        YuguCompat.setThrowOnUnsupportedCoreType(true);
        // not initialised: the argument is checked first
        SkEgnManager fresh = SkEgnManager.getInstance(app);
        Events e = new Events();
        try {
            fresh.startRecord(setting(CoreType.EN_OPEN_EVAL), e.recorder);
            fail();
        } catch (IllegalArgumentException expected) {
            assertEquals("不支持的评测内核：open.eval", expected.getMessage());
        }
        try {
            fresh.existsAudioTrans(setting(CoreType.EN_OPEN_EVAL), e.record);
            fail();
        } catch (IllegalArgumentException expected) {
            // ok
        }
        Main.idle();
        assertTrue(e.log.isEmpty());
        // busy: the running session is not touched
        SkEgnManager m = initManager(null);
        Events running = new Events();
        RecordSetting stream = new RecordSetting(CoreType.CN_SENT_EVAL, "今天天气很好");
        stream.setIsStream(true);
        m.startRecord(stream, running.recorder);
        Main.idle();
        assertEquals(SkEgnManager.engine_status.RECORDING, m.getEngineStatus());
        try {
            m.startRecord(setting(CoreType.KR_SENT_EVAL), new Events().recorder);
            fail();
        } catch (IllegalArgumentException expected) {
            assertEquals("不支持的评测内核：sent.eval.kr", expected.getMessage());
        }
        assertEquals(SkEgnManager.engine_status.RECORDING, m.getEngineStatus());
        m.feed(Fixtures.pcm("spec/fixtures/audio/zh_short.wav"));
        m.stopRecord();
        Main.await("the running session ends normally", 10000, () -> running.json != null);
        assertEquals(94.6, running.result().getJSONObject("result").getDouble("overall"), 1e-9);
    }

    @Test
    public void switchOnLeavesEveryOtherErrorOnItsCallback() throws Exception {
        YuguCompat.setThrowOnUnsupportedCoreType(true);
        SkEgnManager fresh = SkEgnManager.getInstance(app);
        Events notInit = new Events();
        fresh.startRecord(setting(CoreType.CN_SENT_EVAL), notInit.recorder);
        Main.idle();
        assertEquals(60007, notInit.result().getInt("errId"));
        SkEgnManager m = initManager(null);
        Events emptyText = new Events();
        m.startRecord(new RecordSetting(CoreType.EN_SENT_EVAL, ""), emptyText.record);
        Main.idle();
        assertEquals(60006, emptyText.result().getInt("errId"));
        assertEquals("refText 为空", emptyText.result().getString("error"));
        Events missingFile = new Events();
        RecordSetting s = new RecordSetting(CoreType.EN_WORD_EVAL, "apple");
        s.setAudioPath("/no/such/file.wav");
        m.existsAudioTrans(s, missingFile.recorder);
        Main.await("60001", 5000, () -> missingFile.json != null);
        assertEquals(60001, missingFile.result().getInt("errId"));
        // initParams only reports, it never throws
        m.initParams(setting(CoreType.EN_OPEN_EVAL));
    }

    @Test
    public void switchFollowsResetAndConfig() {
        YuguCompat.setThrowOnUnsupportedCoreType(true);
        assertTrue(CompatConfig.throwOnUnsupportedCoreType());
        YuguCompat.reset();
        assertFalse(YuguCompat.isThrowOnUnsupportedCoreType());
        YuguCompat.setThrowOnUnsupportedCoreType(true);
        CompatConfig.reset();
        assertFalse(YuguCompat.isThrowOnUnsupportedCoreType());
    }

    // ================================================================ SkEgn

    @Test
    public void skegnStartAnswersAtOnceAndFeedAndStopFailLikeIos() throws Exception {
        long e = SkEgn.skegn_new(CFG, app);
        byte[] id = new byte[64];
        assertEquals(0, SkEgn.skegn_start(e, param(CoreType.EN_OPEN_EVAL), id, cb, app));
        assertEquals(SkEgnEmulator.NONE, SkEgn.skegn_get_last_error());
        String token = new String(id, StandardCharsets.US_ASCII).trim();
        assertTrue(token.matches("[0-9a-f]{32}"));
        List<String> warn = warnLines("errId 60003");
        assertEquals(String.valueOf(lines), 1, warn.size());
        assertEquals("2 skegn_start rejected (errId 60003): 不支持的评测内核：open.eval", warn.get(0));
        // before the callback ran
        assertTrue(results.isEmpty());
        assertEquals(-1, SkEgn.skegn_feed(e, new byte[640], 640));
        assertEquals(SkEgnEmulator.SGN_FUNCTION_FEED_BEFORE_START, SkEgn.skegn_get_last_error());
        assertEquals(-1, SkEgn.skegn_stop(e));
        assertEquals(SkEgnEmulator.SGN_FUNCTION_STOP_AND_STOP, SkEgn.skegn_get_last_error());
        // posted to the main thread, no skegn_stop needed
        Main.idle();
        assertEquals(1, results.size());
        assertErrorJson(results.get(0), "open.eval", "k");
        assertEquals(token, ids.get(0));
        assertEquals(token, new JSONObject(results.get(0)).getString("tokenId"));
        // after the callback the same failures
        assertEquals(-1, SkEgn.skegn_feed(e, new byte[640], 640));
        assertEquals(SkEgnEmulator.SGN_FUNCTION_FEED_BEFORE_START, SkEgn.skegn_get_last_error());
        assertEquals(-1, SkEgn.skegn_stop(e));
        assertEquals(SkEgnEmulator.SGN_FUNCTION_STOP_AND_STOP, SkEgn.skegn_get_last_error());
        Main.settle(50);
        assertEquals("one callback only", 1, results.size());
        assertEquals("no network", 0, transport.count());
        // the next session runs normally
        assertEquals(0, SkEgn.skegn_start(e, param(CoreType.EN_WORD_EVAL), id, cb, app));
        assertEquals(SkEgnEmulator.NONE, SkEgn.skegn_get_last_error());
        byte[] wav = Fixtures.bytes("spec/fixtures/audio/en_apple.wav");
        assertEquals(0, SkEgn.skegn_feed(e, wav, wav.length));
        assertEquals(0, SkEgn.skegn_stop(e));
        Main.await("second callback", 5000, () -> results.size() == 2);
        assertTrue(results.get(1), new JSONObject(results.get(1)).has("result"));
        assertEquals(1, transport.count());
        // a blank coreType is named too, and cancel drops a pending answer
        assertEquals(0, SkEgn.skegn_start(e, param("  "), id, cb, app));
        assertEquals(0, SkEgn.skegn_cancel(e));
        Main.settle(50);
        assertEquals(2, results.size());
        assertEquals(1, warnLines("不支持的评测内核：未指定").size());
        SkEgn.skegn_delete(e);
    }

    @Test
    public void skegnRejectionLogsTheTimingLineWithoutRecordId() {
        YuguCompat.setLogLevel(YuguCompat.LOG_INFO);
        long e = SkEgn.skegn_new(CFG, app);
        byte[] id = new byte[64];
        SkEgn.skegn_start(e, param(CoreType.EN_CHOICE_REC), id, cb, app);
        Main.idle();
        String token = new String(id, StandardCharsets.US_ASCII).trim();
        List<String> timing = new ArrayList<>();
        synchronized (lines) {
            for (String l : lines) {
                if (l.startsWith("3 timing tokenId=" + token + " ")) {
                    timing.add(l);
                }
            }
        }
        assertEquals(String.valueOf(lines), 1, timing.size());
        assertTrue(timing.get(0), timing.get(0).startsWith("3 timing tokenId=" + token + " recordId=- mode=rest "));
        assertTrue(timing.get(0), timing.get(0).endsWith(" errId=60003"));
        SkEgn.skegn_delete(e);
    }

    @Test
    public void skegnSwitchOnThrowsAndChangesNothing() throws Exception {
        YuguCompat.setThrowOnUnsupportedCoreType(true);
        long e = SkEgn.skegn_new(CFG, app);
        byte[] id = new byte[64];
        // the C checks of handle, callback, id and param still come first
        assertEquals(-1, SkEgn.skegn_start(e, param(CoreType.EN_OPEN_EVAL), id, null, app));
        assertEquals(SkEgnEmulator.SGN_CALLBACK_IS_NULL, SkEgn.skegn_get_last_error());
        assertEquals(-1, SkEgn.skegn_start(e, "{\"request\":", id, cb, app));
        assertEquals(SkEgnEmulator.SGN_BAD_PRAMA, SkEgn.skegn_get_last_error());
        // a session waiting for its result is not cancelled by the rejected start
        transport.delay(300);
        assertEquals(0, SkEgn.skegn_start(e, param(CoreType.EN_WORD_EVAL), id, cb, app));
        byte[] wav = Fixtures.bytes("spec/fixtures/audio/en_apple.wav");
        SkEgn.skegn_feed(e, wav, wav.length);
        assertEquals(0, SkEgn.skegn_stop(e));
        lines.clear();
        try {
            SkEgn.skegn_start(e, param(CoreType.JP_SENT_EVAL), new byte[64], cb, app);
            fail();
        } catch (IllegalArgumentException expected) {
            assertEquals("不支持的评测内核：sent.eval.jp", expected.getMessage());
        }
        assertEquals(1, warnLines("skegn_start rejected (errId 60003): 不支持的评测内核：sent.eval.jp").size());
        Main.await("the waiting session still answers", 5000, () -> results.size() == 1);
        assertTrue(results.get(0), new JSONObject(results.get(0)).has("result"));
        Main.settle(50);
        assertEquals(1, results.size());
        assertEquals("feed after the result fails as before", -1, SkEgn.skegn_feed(e, new byte[2], 2));
        SkEgn.skegn_delete(e);
    }
}
