// SPDX-License-Identifier: Apache-2.0
package com.stkouyu;

import android.content.Context;

import com.shengzhiai.yugu.stcompat.internal.CompatConfig;
import com.shengzhiai.yugu.stcompat.internal.CompatEngine;
import com.shengzhiai.yugu.stcompat.internal.CoreTypes;
import com.shengzhiai.yugu.stcompat.internal.FormMapper;
import com.shengzhiai.yugu.stcompat.internal.MainThread;
import com.shengzhiai.yugu.stcompat.internal.YLog;
import com.stkouyu.listener.OnPlayerListener;
import com.stkouyu.listener.OnRecordBufferListener;
import com.stkouyu.listener.OnRecordListener;
import com.stkouyu.listener.OnRecorderListener;
import com.stkouyu.setting.EngineSetting;
import com.stkouyu.setting.RecordSetting;

/**
 * Engine manager with the API of the Shengtong 17kouyu SDK, evaluating on the Yugu cloud platform.
 *
 * <pre>
 * SkEgnManager m = SkEgnManager.getInstance(context);
 * EngineSetting es = EngineSetting.getInstance(context).setOnInitEngineListener(initListener);
 * m.initEngine(appKey, secretKey, userId, es);
 * m.startRecord(new RecordSetting(CoreType.CN_SENT_EVAL, "今天天气很好"), recorderListener);
 * m.stopRecord();            // onRecordEnd, then onScore(json)
 * m.clearActivityListener();
 * m.recycle();
 * </pre>
 *
 * Every callback runs on the main thread. {@code onScore} receives the result JSON
 * {@code {tokenId, recordId, applicationId, userId, refText, eof, dtLastResponse, result, ...}} or
 * the error JSON {@code {tokenId, errId, error, eof, applicationId}}.
 */
public class SkEgnManager {
    public static final String SERVER_TYPE_CLOUD = "cloud";
    public static final String SERVER_TYPE_NATIVE = "native";
    public static final String SERVER_TYPE_MULTI = "multi";
    public static final int CODE_CREATE_ENGINE_FAIL = 0;
    public static final int CODE_SKEGN_START_FAIL = -1;
    public static final int CODE_START_INIT_ENGINE = 1;
    public static final int CODE_INIT_ENGINE_SUCCESS = 2;
    public static final int CODE_INIT_ENGINE_FAILED = 3;
    public static final int CODE_RECORD_START = 5;
    public static final int CODE_RECORD_RECORDING = 6;
    public static final int CODE_RECORD_END = 7;
    public static final int CODE_PLAY_END = 8;
    public static final int CODE_RECORD_BUFFER = 9;
    public static final int CODE_RECORDER_START = 10;
    public static final int CODE_RECORDER_ONSTARTRECORDFAIL = 11;
    public static final int CODE_RECORDER_TICK = 12;
    public static final int CODE_RECORDER_PAUSE = 13;
    public static final int CODE_RECORDER_END = 14;
    public static final int CODE_PLAY_START = 15;
    public static final int CODE_PLAY_START_FAIL = 16;

    /** Non-zero while an engine is initialised, 0 after recycle (the Shengtong SDK kept the native handle here). */
    public static long engine;

    /** Engine state: IDLE, RECORDING, PAUSED, STOP (recording ended, result pending or delivered). */
    public enum engine_status {
        IDLE,
        RECORDING,
        PAUSED,
        STOP
    }

    /** Receives the provision answer; in cloud mode always {@code {"provision":"cloud",...}}. */
    public interface InquireProvisionCallback {
        void run(String message);
    }

    private static volatile SkEgnManager instance;

    private final CompatEngine core;

    private SkEgnManager(CompatEngine core) {
        this.core = core;
    }

    public static SkEgnManager getInstance(Context context) {
        SkEgnManager m = instance;
        if (m == null) {
            synchronized (SkEgnManager.class) {
                m = instance;
                if (m == null) {
                    m = new SkEgnManager(CompatEngine.get(context));
                    instance = m;
                }
            }
        }
        if (context != null) {
            CompatEngine.get(context);
        }
        return m;
    }

    // ---------------------------------------------------------------- init

    public void initCloudEngine(String appKey, String secretKey, String userId) {
        initCloudEngine(appKey, secretKey, userId, EngineSetting.getInstance(core.context()));
    }

    public void initCloudEngine(String appKey, String secretKey, String userId, EngineSetting setting) {
        init(appKey, secretKey, userId, setting, EngineType.ENGINE_CLOUD);
    }

    /** Accepted for compatibility: logged at WARN and served by the cloud engine. */
    public void initNativeEngine(String appKey, String secretKey, String userId) {
        initNativeEngine(appKey, secretKey, userId, EngineSetting.getInstance(core.context()));
    }

    /** Accepted for compatibility: logged at WARN and served by the cloud engine. */
    public void initNativeEngine(String appKey, String secretKey, String userId, EngineSetting setting) {
        init(appKey, secretKey, userId, setting, EngineType.ENGINE_NATIVE);
    }

    /**
     * Initialises the engine: onStartInitEngine, then onInitEngineSuccess or
     * onInitEngineFailed(reason) on the main thread, to the listener of {@code setting}.
     */
    public void initEngine(String appKey, String secretKey, String userId, EngineSetting setting) {
        init(appKey, secretKey, userId, setting, null);
    }

    private void init(String appKey, String secretKey, String userId, EngineSetting setting, String type) {
        boolean ok = core.init(appKey, secretKey, userId, setting, type);
        engine = ok ? 1L : 0L;
    }

    // ---------------------------------------------------------------- recording

    /** Quick start: refText, coreType (either order), question type. */
    public void startRecord(String refText, String coreType, int qType, OnRecorderListener listener) {
        core.startRecord(quick(refText, coreType, qType), listener, null);
    }

    /**
     * Quick start: refText, coreType (either order), question type.
     *
     * @deprecated as in the Shengtong SDK; use {@link #startRecord(String, String, int, OnRecorderListener)}
     */
    @Deprecated
    public void startRecord(String refText, String coreType, int qType, OnRecordListener listener) {
        core.startRecord(quick(refText, coreType, qType), null, listener);
    }

    private static RecordSetting quick(String refText, String coreType, int qType) {
        RecordSetting s = new RecordSetting();
        if (CoreTypes.looksLikeCoreType(refText) && !CoreTypes.looksLikeCoreType(coreType)) {
            s.setCoreType(refText);
            s.setRefText(coreType);
        } else {
            s.setCoreType(coreType);
            s.setRefText(refText);
        }
        s.setqType(qType);
        return s;
    }

    public void setPlayerListener(OnPlayerListener listener) {
        core.setPlayerListener(listener);
    }

    /** Receives a copy of each captured PCM chunk on the main thread. */
    public void setOnRecordBufferListener(OnRecordBufferListener listener) {
        core.setBufferListener(listener);
    }

    /** Replaces the listener of the current recording and becomes the default for later sessions. */
    public void setOnRecorderListener(OnRecorderListener listener) {
        core.setRecorderListener(listener);
    }

    /**
     * Starts recording (or a stream session when {@code setIsStream(true)}). A busy engine answers
     * onStartRecordFail("engine is busy"); invalid settings answer onScore with errId 60003,
     * 60006 or 60007; a missing microphone answers onStartRecordFail.
     */
    public void startRecord(RecordSetting setting, OnRecorderListener listener) {
        core.startRecord(setting, listener, null);
    }

    /**
     * Starts recording; every error, including busy (60008) and microphone (60004), arrives in onRecordEnd(json).
     *
     * @deprecated as in the Shengtong SDK; use {@link #startRecord(RecordSetting, OnRecorderListener)}
     */
    @Deprecated
    public void startRecord(RecordSetting setting, OnRecordListener listener) {
        core.startRecord(setting, null, listener);
    }

    /**
     * Evaluates {@code setting.getAudioPath()}: onRecordStart, then onRecordEnd(json).
     *
     * @deprecated as in the Shengtong SDK; use {@link #existsAudioTrans(RecordSetting, OnRecorderListener)}
     */
    @Deprecated
    public void existsAudioTrans(RecordSetting setting, OnRecordListener listener) {
        core.existsAudioTrans(setting, null, listener, null, null);
    }

    /**
     * Evaluates {@code setting.getAudioPath()}.
     *
     * @param bufSize accepted for compatibility (the file is uploaded in one request)
     * @param timeout read timeout: seconds when 1000 or less, otherwise milliseconds
     * @deprecated as in the Shengtong SDK; use the OnRecorderListener variant
     */
    @Deprecated
    public void existsAudioTrans(RecordSetting setting, OnRecordListener listener, Integer bufSize, Integer timeout) {
        core.existsAudioTrans(setting, null, listener, bufSize, timeout);
    }

    /** Evaluates {@code setting.getAudioPath()}: onStart, onRecordEnd, then onScore(json). */
    public void existsAudioTrans(RecordSetting setting, OnRecorderListener listener) {
        core.existsAudioTrans(setting, listener, null, null, null);
    }

    /** See {@link #existsAudioTrans(RecordSetting, OnRecordListener, Integer, Integer)}. */
    public void existsAudioTrans(RecordSetting setting, OnRecorderListener listener, Integer bufSize, Integer timeout) {
        core.existsAudioTrans(setting, listener, null, bufSize, timeout);
    }

    /** Appends PCM (16 kHz, mono, 16 bit) in stream mode; a leading WAV header is skipped. */
    public void feed(byte[] data) {
        if (data != null) {
            core.feed(data, data.length);
        }
    }

    public void feed(byte[] data, int size) {
        core.feed(data, size);
    }

    /** Stops recording, posts onRecordEnd, then evaluates and posts the result. */
    public void stopRecord() {
        core.stopRecord();
    }

    /** RECORDING to PAUSED, posts onPause. */
    public void pauseRecord() {
        core.pauseRecord();
    }

    /** PAUSED to RECORDING. */
    public void restartRecord() {
        core.restartRecord();
    }

    /** Drops every activity listener reference (recorder, record, player, buffer listeners). */
    public void clearActivityListener() {
        core.clearActivityListener();
    }

    /** Drops the init listener reference. */
    public void clearInitListener() {
        core.clearInitListener();
    }

    /** Releases recorder, player, threads and listeners. Idempotent; initEngine again before the next use. */
    public void recycle() {
        core.recycle();
        engine = 0L;
    }

    /** Stops and discards the current session without any further callback. Idempotent. */
    public void cancel() {
        core.cancel();
    }

    /** Same as {@link #cancel()} (the misspelled name of the Shengtong SDK). */
    public void cancle() {
        core.cancel();
    }

    public void stopPlay() {
        core.stopPlay();
    }

    /** Plays the last recording. */
    public void playback() {
        core.playback();
    }

    public void playWithPath(String path) {
        core.playWithPath(path);
    }

    /** Releases a microphone opened by activeMic; false while a recording uses it. */
    public boolean releaseMic() {
        return core.releaseMic();
    }

    /** Opens the microphone ahead of the next recording; false when it is unavailable or not permitted. */
    public boolean activeMic() {
        return core.activeMic();
    }

    /** Checks a setting ahead of time; problems are logged at WARN. No I/O. */
    public void initParams(RecordSetting setting) {
        if (setting == null) {
            return;
        }
        int invalid = FormMapper.validate(setting);
        if (invalid != 0) {
            YLog.w("initParams: setting would be rejected with errId " + invalid);
        } else if (YLog.isLoggable(YLog.DEBUG)) {
            YLog.d("initParams: " + setting.getCoreType() + " fields " + FormMapper.fields(setting).keySet());
        }
    }

    public engine_status getEngineStatus() {
        return core.status();
    }

    public String getLastRecordPath() {
        return core.lastRecordPath();
    }

    /** Always {@code cloud}. */
    public String getCurrentEngineType() {
        return SERVER_TYPE_CLOUD;
    }

    /** {@code yugu-stkouyu-compat/2.0.0 (api 1.0.62)}. */
    public String getSDKVersion() {
        return CompatConfig.SDK_VERSION;
    }

    /** Answers {@code {"provision":"cloud","message":"cloud mode, no provision file needed"}} on the main thread. */
    public boolean inquireProvision(String provisionPath, final InquireProvisionCallback callback) {
        if (callback != null) {
            MainThread.post(new Runnable() {
                @Override
                public void run() {
                    callback.run("{\"provision\":\"cloud\",\"message\":\"cloud mode, no provision file needed\"}");
                }
            });
        }
        return true;
    }

    public boolean inquireProvision(InquireProvisionCallback callback) {
        return inquireProvision(null, callback);
    }

    /** No provision file is needed in cloud mode; returns true. */
    public boolean updateProvision(String provisionPath, String appKey, String secretKey) {
        return true;
    }

    /** No provision file is needed in cloud mode; returns true. */
    public boolean updateProvision(String appKey, String secretKey) {
        return true;
    }

    /** Test hook (package-private, not API). */
    static void resetForTests() {
        synchronized (SkEgnManager.class) {
            instance = null;
            engine = 0L;
        }
        CompatEngine.resetForTests();
    }
}
