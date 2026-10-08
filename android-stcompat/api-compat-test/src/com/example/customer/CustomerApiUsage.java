// SPDX-License-Identifier: Apache-2.0
package com.example.customer;

import android.content.Context;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import com.stkouyu.AgeGroup;
import com.stkouyu.AppConfig;
import com.stkouyu.AudioType;
import com.stkouyu.Build;
import com.stkouyu.CoreType;
import com.stkouyu.CustomParam;
import com.stkouyu.EngineType;
import com.stkouyu.Mode;
import com.stkouyu.QType;
import com.stkouyu.STRecorder;
import com.stkouyu.SkEgn;
import com.stkouyu.SkEgnManager;
import com.stkouyu.lame.SimpleLame;
import com.stkouyu.listener.OnInitEngineListener;
import com.stkouyu.listener.OnPlayerListener;
import com.stkouyu.listener.OnRecordBufferListener;
import com.stkouyu.listener.OnRecordListener;
import com.stkouyu.listener.OnRecorderListener;
import com.stkouyu.listener.OnSTRecorderListener;
import com.stkouyu.setting.EngineSetting;
import com.stkouyu.setting.RecordSetting;
import com.stkouyu.util.AiUtil;
import com.stkouyu.util.CommandUtil;
import com.stkouyu.util.CountDownTimer;
import com.stkouyu.util.DeviceUtils;
import com.stkouyu.util.HandlerUtils;
import com.stkouyu.util.LogCat;
import com.stkouyu.util.MyLog;
import com.stkouyu.util.MyUtil;
import com.stkouyu.util.httputil.Args;
import com.stkouyu.util.httputil.Consts;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Customer-style code written against the public Shengtong 17kouyu 1.0.0 API: calls every public
 * constructor and method, implements every listener and callback type, extends every abstract
 * class, reads every constant (in constant expressions, so they must stay compile-time
 * constants) and every public field. It is compiled with plain javac against the original jar
 * and against the compat classes; it is never executed.
 */
@SuppressWarnings({"unused", "deprecation", "UnusedAssignment"})
public final class CustomerApiUsage {
    private final Context ctx;
    private final File file = new File("x.wav");
    private final InputStream in = new ByteArrayInputStream(new byte[0]);
    private final Handler handler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            int what = msg.what;
        }
    };
    private final SkEgnManager manager;
    private final EngineSetting engineSetting;
    private final RecordSetting recordSetting = new RecordSetting(CoreType.CN_SENT_EVAL, "今天天气很好");
    private final STRecorder recorder;
    private final CustomParam customParam = new CustomParam("language", "zh-CN");
    private final HandlerUtils handlerUtils = HandlerUtils.getInstance();

    // ---------------------------------------------------------------- listeners and callbacks
    private final OnInitEngineListener initListener = new OnInitEngineListener() {
        @Override
        public void onStartInitEngine() {
        }

        @Override
        public void onInitEngineSuccess() {
        }

        @Override
        public void onInitEngineFailed(String reason) {
        }
    };

    private final OnPlayerListener playerListener = new OnPlayerListener() {
        @Override
        public void onPlayStart() {
        }

        @Override
        public void onPlayStartFail(String reason) {
        }

        @Override
        public void onPlayEnd() {
        }
    };

    private final OnRecordBufferListener bufferListener = new OnRecordBufferListener() {
        @Override
        public void onRecordBuffer(byte[] buffer, int size) {
        }
    };

    private final OnRecorderListener recorderListener = new OnRecorderListener() {
        @Override
        public void onStart() {
        }

        @Override
        public void onStartRecordFail(String reason) {
        }

        @Override
        public void onPause() {
        }

        @Override
        public void onTick(long millisUntilFinished, double percentUntilFinished) {
        }

        @Override
        public void onRecordEnd() {
        }

        @Override
        public void onRecording(int vadStatus, int soundIntensity) {
        }

        @Override
        public void onScore(String json) {
        }
    };

    private final OnRecordListener recordListener = new OnRecordListener() {
        @Override
        public void onRecordStart() {
        }

        @Override
        public void onRecording(int vadStatus, int soundIntensity) {
        }

        @Override
        public void onRecordEnd(String json) {
        }
    };

    private final OnSTRecorderListener stRecorderListener = new OnSTRecorderListener() {
        @Override
        public void onStart() {
        }
    };

    private final SkEgn.skegn_callback skegnCallback = new SkEgn.skegn_callback() {
        @Override
        public int run(byte[] id, int type, byte[] data, int size) {
            return type == SkEgn.SKEGN_MESSAGE_TYPE_JSON ? 0 : -1;
        }
    };

    private final SkEgnManager.InquireProvisionCallback provisionCallback = new SkEgnManager.InquireProvisionCallback() {
        @Override
        public void run(String message) {
        }
    };

    private final STRecorder.Callback recorderCallback = new STRecorder.Callback() {
        @Override
        public void run(byte[] data, int size) {
        }
    };

    private final HandlerUtils.HandlerDispose handlerDispose = new HandlerUtils.HandlerDispose() {
        @Override
        public void handleMessage(Message msg) {
        }
    };

    private final CountDownTimer countDownTimer = new CountDownTimer(1000L, 100L) {
        @Override
        public void onTick(long millisUntilFinished) {
        }

        @Override
        public void onFinish(long elapsedMillis) {
        }
    };

    public CustomerApiUsage(Context context) {
        ctx = context;
        manager = SkEgnManager.getInstance(context);
        engineSetting = EngineSetting.getInstance(context);
        recorder = STRecorder.getInstance(context);
    }

    // ---------------------------------------------------------------- every public method and constructor
    void everyMethod() {
        // ---- com.stkouyu.AgeGroup
        Object o0 = new com.stkouyu.AgeGroup();
        // ---- com.stkouyu.AppConfig
        Object o1 = new com.stkouyu.AppConfig();
        // ---- com.stkouyu.AudioType
        Object o2 = new com.stkouyu.AudioType();
        // ---- com.stkouyu.Build
        Object o3 = new com.stkouyu.Build();
        // ---- com.stkouyu.CoreType
        Object o4 = new com.stkouyu.CoreType();
        // ---- com.stkouyu.CustomParam
        Object o5 = new com.stkouyu.CustomParam("s", (Object) "o");
        java.lang.String r6 = customParam.getKey();
        customParam.setKey("s");
        java.lang.Object r8 = customParam.getValue();
        customParam.setValue((Object) "o");
        // ---- com.stkouyu.EngineType
        Object o10 = new com.stkouyu.EngineType();
        // ---- com.stkouyu.lame.SimpleLame
        Object o11 = new com.stkouyu.lame.SimpleLame();
        com.stkouyu.lame.SimpleLame.init(1, 1, 1, 1, 1);
        int r13 = com.stkouyu.lame.SimpleLame.encodeInterleaved(new short[1], 1, new byte[1]);
        int r14 = com.stkouyu.lame.SimpleLame.encode(new short[1], new short[1], 1, new byte[1]);
        int r15 = com.stkouyu.lame.SimpleLame.flush(new byte[1]);
        com.stkouyu.lame.SimpleLame.tags("s");
        com.stkouyu.lame.SimpleLame.close();
        // ---- com.stkouyu.Mode
        Object o18 = new com.stkouyu.Mode();
        // ---- com.stkouyu.QType
        Object o19 = new com.stkouyu.QType();
        // ---- com.stkouyu.setting.EngineSetting
        boolean r20 = engineSetting.isAutoDetectNetwork();
        engineSetting.setAutoDetectNetwork(true);
        com.stkouyu.setting.EngineSetting r22 = com.stkouyu.setting.EngineSetting.getInstance(ctx);
        com.stkouyu.setting.EngineSetting r23 = engineSetting.getDefaultCloudInstance();
        com.stkouyu.setting.EngineSetting r24 = engineSetting.getDefaultNativeInstance();
        java.io.File r25 = engineSetting.getDefaultProvisionFile();
        java.lang.String r26 = engineSetting.getProvisionPath();
        com.stkouyu.setting.EngineSetting r27 = engineSetting.setProvisionPath("s");
        java.lang.String r28 = engineSetting.getServerAddress();
        com.stkouyu.setting.EngineSetting r29 = engineSetting.setServerAddress("s");
        java.lang.String r30 = engineSetting.getServerList();
        com.stkouyu.setting.EngineSetting r31 = engineSetting.setServerList("s");
        int r32 = engineSetting.getConnectTimeout();
        com.stkouyu.setting.EngineSetting r33 = engineSetting.setConnectTimeout(1);
        com.stkouyu.setting.EngineSetting r34 = engineSetting.setServerTimeout(1);
        int r35 = engineSetting.getServerTimeout();
        java.lang.String r36 = engineSetting.getNativeResourcePath();
        com.stkouyu.setting.EngineSetting r37 = engineSetting.setNativeResourcePath("s");
        java.lang.String r38 = engineSetting.getNativeCNResourcePath();
        com.stkouyu.setting.EngineSetting r39 = engineSetting.setNativeCNResourcePath("s");
        java.lang.String r40 = engineSetting.getNativeDbPath();
        com.stkouyu.setting.EngineSetting r41 = engineSetting.setNativeDbPath("s");
        boolean r42 = engineSetting.isVADEnabled();
        com.stkouyu.setting.EngineSetting r43 = engineSetting.setVADEnabled(true);
        boolean r44 = engineSetting.isSDKLogEnabled();
        com.stkouyu.setting.EngineSetting r45 = engineSetting.setSDKLogEnabled(true);
        boolean r46 = engineSetting.isUseOnlineProvision();
        com.stkouyu.setting.EngineSetting r47 = engineSetting.setUseOnlineProvision(true);
        com.stkouyu.setting.EngineSetting r48 = engineSetting.setNeedUpdateOnlineProvision(true);
        boolean r49 = engineSetting.isNeedUpdateOnlineProvision();
        java.lang.String r50 = engineSetting.getUserId();
        com.stkouyu.setting.EngineSetting r51 = engineSetting.setUserId("s");
        com.stkouyu.listener.OnInitEngineListener r52 = engineSetting.getOnInitEngineListener();
        com.stkouyu.setting.EngineSetting r53 = engineSetting.setOnInitEngineListener(initListener);
        java.lang.String r54 = engineSetting.getSdkCfgAddr();
        com.stkouyu.setting.EngineSetting r55 = engineSetting.setSdkCfgAddr("s");
        java.lang.String r56 = engineSetting.getEngineType();
        engineSetting.setEngineType("s");
        int r58 = engineSetting.getLogLevel();
        engineSetting.setLogLevel(1);
        engineSetting.setEnableUploadLog(true);
        boolean r61 = engineSetting.getEnableUploadLog();
        engineSetting.setEnableSaveLogCatToFile(true);
        boolean r63 = engineSetting.getEnableSaveLogCatToFile();
        // ---- com.stkouyu.setting.RecordSetting
        java.lang.Integer r64 = recordSetting.getSeek();
        recordSetting.setSeek(Integer.valueOf(1));
        java.lang.Integer r66 = recordSetting.getRef_length();
        recordSetting.setRef_length(Integer.valueOf(1));
        boolean r68 = recordSetting.isAutoRetry();
        recordSetting.setAutoRetry(true);
        boolean r70 = recordSetting.isForceRecord();
        recordSetting.setForceRecord(true);
        recordSetting.setRecordName("s");
        java.lang.String r73 = recordSetting.getRecordName();
        java.lang.String r74 = recordSetting.getRecordFilePath();
        recordSetting.setRecordFilePath("s");
        recordSetting.setErrIds(new java.util.ArrayList<String>());
        java.util.List<java.lang.String> r77 = recordSetting.getErrIds();
        java.lang.String r78 = recordSetting.toString();
        Object o79 = new com.stkouyu.setting.RecordSetting("s", "s");
        Object o80 = new com.stkouyu.setting.RecordSetting("s", 1);
        Object o81 = new com.stkouyu.setting.RecordSetting("s");
        Object o82 = new com.stkouyu.setting.RecordSetting();
        boolean r83 = recordSetting.isNeedSoundIntensity();
        com.stkouyu.setting.RecordSetting r84 = recordSetting.setNeedSoundIntensity(true);
        java.lang.String r85 = recordSetting.getAudioType();
        com.stkouyu.setting.RecordSetting r86 = recordSetting.setAudioType("s");
        int r87 = recordSetting.getSampleRate();
        com.stkouyu.setting.RecordSetting r88 = recordSetting.setSampleRate(1);
        int r89 = recordSetting.getChannel();
        com.stkouyu.setting.RecordSetting r90 = recordSetting.setChannel(1);
        java.lang.String r91 = recordSetting.getAudioPath();
        com.stkouyu.setting.RecordSetting r92 = recordSetting.setAudioPath("s");
        boolean r93 = recordSetting.isNeedRequestParamsInResult();
        com.stkouyu.setting.RecordSetting r94 = recordSetting.setNeedRequestParamsInResult(true);
        java.lang.String r95 = recordSetting.getCoreType();
        recordSetting.setCoreType("s");
        java.lang.String r97 = recordSetting.getRefText();
        recordSetting.setRefText("s");
        recordSetting.setRefAudio("s");
        java.lang.String r100 = recordSetting.getRefAudio();
        boolean r101 = recordSetting.isNeedWordScoreInParagraph();
        com.stkouyu.setting.RecordSetting r102 = recordSetting.setNeedWordScoreInParagraph(true);
        boolean r103 = recordSetting.isNeedAttachAudioUrlInResult();
        com.stkouyu.setting.RecordSetting r104 = recordSetting.setNeedAttachAudioUrlInResult(true);
        java.lang.String r105 = recordSetting.getDict_type();
        com.stkouyu.setting.RecordSetting r106 = recordSetting.setDict_type("s");
        boolean r107 = recordSetting.isNeedPhonemeOutputInWord();
        com.stkouyu.setting.RecordSetting r108 = recordSetting.setNeedPhonemeOutputInWord(true);
        java.lang.Double r109 = recordSetting.getScale();
        com.stkouyu.setting.RecordSetting r110 = recordSetting.setScale(1);
        com.stkouyu.setting.RecordSetting r111 = recordSetting.setScaleD(1.0);
        double r112 = recordSetting.getPrecision();
        com.stkouyu.setting.RecordSetting r113 = recordSetting.setPrecision(1.0);
        double r114 = recordSetting.getSlack();
        com.stkouyu.setting.RecordSetting r115 = recordSetting.setSlack(1.0);
        java.lang.String r116 = recordSetting.getKeywords();
        com.stkouyu.setting.RecordSetting r117 = recordSetting.setKeywords("s");
        recordSetting.setqType(1);
        int r119 = recordSetting.getqType();
        int r120 = recordSetting.getAgegroup();
        recordSetting.setAgegroup(1);
        java.lang.String r122 = recordSetting.getCustomized_lexicon();
        com.stkouyu.setting.RecordSetting r123 = recordSetting.setCustomized_lexicon("s");
        java.lang.String r124 = recordSetting.getMode();
        com.stkouyu.setting.RecordSetting r125 = recordSetting.setMode("s");
        recordSetting.setProtocol("s");
        java.lang.String r127 = recordSetting.getProtocol();
        java.util.ArrayList<com.stkouyu.CustomParam> r128 = recordSetting.getNewParams();
        com.stkouyu.setting.RecordSetting r129 = recordSetting.setNewParams(new java.util.ArrayList<com.stkouyu.CustomParam>());
        boolean r130 = recordSetting.isMuteMusic();
        recordSetting.setMuteMusic(true);
        java.lang.String r132 = recordSetting.getCoreProvideType();
        recordSetting.setCoreProvideType("s");
        java.lang.Integer r134 = recordSetting.getMax_ogg_delay();
        recordSetting.setMax_ogg_delay(Integer.valueOf(1));
        java.lang.Integer r136 = recordSetting.getRealtime_feedback();
        recordSetting.setRealtime_feedback(Integer.valueOf(1));
        java.lang.String r138 = recordSetting.getNegativeReftext();
        recordSetting.setNegativeReftext("s");
        java.lang.String r140 = recordSetting.getDict_dialect();
        recordSetting.setDict_dialect("s");
        java.lang.Integer r142 = recordSetting.getDetect_nonscorable();
        recordSetting.setDetect_nonscorable(Integer.valueOf(1));
        java.lang.String r144 = recordSetting.getCompress();
        recordSetting.setCompress("s");
        java.lang.String r146 = recordSetting.getCustomized_pron();
        recordSetting.setCustomized_pron("s");
        java.lang.Integer r148 = recordSetting.getOutput_rawtext();
        recordSetting.setOutput_rawtext(Integer.valueOf(1));
        java.lang.String r150 = recordSetting.getKeypoints();
        recordSetting.setKeypoints("s");
        java.lang.Double r152 = recordSetting.getKeypoints_weight();
        recordSetting.setKeypoints_weight(Double.valueOf(1.0));
        java.lang.String r154 = recordSetting.getNegative_keypoints();
        recordSetting.setNegative_keypoints("s");
        java.lang.Integer r156 = recordSetting.getVad_detction();
        recordSetting.setVad_detction(Integer.valueOf(1));
        recordSetting.setServerTimeout(1);
        java.lang.Integer r159 = recordSetting.getServerTimeout();
        recordSetting.setDuration(1);
        java.lang.Integer r161 = recordSetting.getDuration();
        recordSetting.setDurationInterval(1);
        java.lang.Integer r163 = recordSetting.getDurationInterval();
        java.lang.String r164 = recordSetting.getRefPinyin();
        recordSetting.setRefPinyin("s");
        recordSetting.setPunctuate(Integer.valueOf(1));
        java.lang.Integer r167 = recordSetting.getPunctuate();
        recordSetting.setChunkSize(Integer.valueOf(1));
        java.lang.Integer r169 = recordSetting.getChunkSize();
        boolean r170 = recordSetting.isVADEnabled();
        recordSetting.setVADEnabled(true);
        java.lang.String r172 = recordSetting.getCustomized_sig_url();
        recordSetting.setCustomized_sig_url("s");
        java.lang.String r174 = recordSetting.getCustomized_sig();
        recordSetting.setCustomized_sig("s");
        java.lang.Integer r176 = recordSetting.getReadtypeDiagnosis();
        recordSetting.setReadtypeDiagnosis(Integer.valueOf(1));
        recordSetting.setRequest("s");
        java.lang.String r179 = recordSetting.getRequest();
        recordSetting.setIsStream(true);
        boolean r181 = recordSetting.getIsStream();
        java.lang.Integer r182 = recordSetting.getItn();
        recordSetting.setItn(1);
        java.lang.Integer r184 = recordSetting.getAudioSource();
        recordSetting.setAudioSource(1);
        boolean r186 = recordSetting.getBlendPhonemeEnable();
        recordSetting.setBlendPhonemeEnable(true);
        // ---- com.stkouyu.SkEgn
        Object o188 = new com.stkouyu.SkEgn();
        long r189 = com.stkouyu.SkEgn.skegn_new("s", (Object) "o");
        int r190 = com.stkouyu.SkEgn.skegn_delete(1L);
        int r191 = com.stkouyu.SkEgn.skegn_start(1L, "s", new byte[1], skegnCallback, (Object) "o");
        int r192 = com.stkouyu.SkEgn.skegn_feed(1L, new byte[1], 1);
        int r193 = com.stkouyu.SkEgn.skegn_stop(1L);
        int r194 = com.stkouyu.SkEgn.skegn_cancel(1L);
        int r195 = com.stkouyu.SkEgn.skegn_opt(1L, 1, new byte[1], 1);
        int r196 = com.stkouyu.SkEgn.skegn_get_device_id(new byte[1], (Object) "o");
        int r197 = com.stkouyu.SkEgn.skegn_get_last_error();
        int r198 = com.stkouyu.SkEgn.skegn_update_provision("s", "s", "s");
        int r199 = com.stkouyu.SkEgn.skegn_inquire_provision("s", skegnCallback, (Object) "o");
        // ---- com.stkouyu.SkEgnManager$engine_status
        com.stkouyu.SkEgnManager.engine_status[] r200 = com.stkouyu.SkEgnManager.engine_status.values();
        com.stkouyu.SkEgnManager.engine_status r201 = com.stkouyu.SkEgnManager.engine_status.valueOf("s");
        // ---- com.stkouyu.SkEgnManager
        com.stkouyu.SkEgnManager r202 = com.stkouyu.SkEgnManager.getInstance(ctx);
        manager.initCloudEngine("s", "s", "s");
        manager.initCloudEngine("s", "s", "s", engineSetting);
        manager.initNativeEngine("s", "s", "s");
        manager.initNativeEngine("s", "s", "s", engineSetting);
        manager.initEngine("s", "s", "s", engineSetting);
        manager.startRecord("s", "s", 1, recorderListener);
        manager.startRecord("s", "s", 1, recordListener);
        manager.setPlayerListener(playerListener);
        manager.setOnRecordBufferListener(bufferListener);
        manager.setOnRecorderListener(recorderListener);
        manager.startRecord(recordSetting, recorderListener);
        manager.startRecord(recordSetting, recordListener);
        manager.existsAudioTrans(recordSetting, recordListener);
        manager.existsAudioTrans(recordSetting, recordListener, Integer.valueOf(1), Integer.valueOf(1));
        manager.existsAudioTrans(recordSetting, recorderListener);
        manager.existsAudioTrans(recordSetting, recorderListener, Integer.valueOf(1), Integer.valueOf(1));
        manager.feed(new byte[1]);
        manager.feed(new byte[1], 1);
        manager.stopRecord();
        manager.pauseRecord();
        manager.restartRecord();
        manager.clearActivityListener();
        manager.clearInitListener();
        manager.recycle();
        manager.cancel();
        manager.cancle();
        manager.stopPlay();
        manager.playback();
        manager.playWithPath("s");
        boolean r232 = manager.releaseMic();
        boolean r233 = manager.activeMic();
        manager.initParams(recordSetting);
        com.stkouyu.SkEgnManager.engine_status r235 = manager.getEngineStatus();
        java.lang.String r236 = manager.getLastRecordPath();
        java.lang.String r237 = manager.getCurrentEngineType();
        java.lang.String r238 = manager.getSDKVersion();
        boolean r239 = manager.inquireProvision("s", provisionCallback);
        boolean r240 = manager.inquireProvision(provisionCallback);
        boolean r241 = manager.updateProvision("s", "s", "s");
        boolean r242 = manager.updateProvision("s", "s");
        // ---- com.stkouyu.STRecorder
        recorder.setMp3Path("s");
        recorder.setHandler(handler);
        recorder.setOnSTRecorderListener(stRecorderListener);
        com.stkouyu.STRecorder r246 = com.stkouyu.STRecorder.getInstance();
        com.stkouyu.STRecorder r247 = com.stkouyu.STRecorder.getInstance(ctx);
        com.stkouyu.STRecorder r248 = com.stkouyu.STRecorder.getInstance(ctx, "s");
        com.stkouyu.STRecorder r249 = com.stkouyu.STRecorder.getInstance(ctx, "s", Integer.valueOf(1));
        com.stkouyu.STRecorder r250 = com.stkouyu.STRecorder.getInstance(ctx, "s", Integer.valueOf(1), Integer.valueOf(1));
        recorder.delete();
        recorder.finalize();
        recorder.start("s", recorderCallback);
        recorder.stop();
        recorder.pause();
        recorder.restart();
        recorder.cancel();
        recorder.playback(handler);
        recorder.playWithPath("s", handler);
        recorder.stopPlay();
        recorder.setbMute(true);
        java.lang.Void r262 = recorder.playBackFile("s");
        // ---- com.stkouyu.util.AiUtil
        Object o263 = new com.stkouyu.util.AiUtil();
        java.lang.String r264 = com.stkouyu.util.AiUtil.sha1("s");
        java.lang.String r265 = com.stkouyu.util.AiUtil.md5(ctx, in);
        long r266 = com.stkouyu.util.AiUtil.getWordCount("s");
        long r267 = com.stkouyu.util.AiUtil.getHanziCount("s");
        java.io.File r268 = com.stkouyu.util.AiUtil.externalFilesDir(ctx);
        java.lang.String r269 = com.stkouyu.util.AiUtil.readFile(file);
        java.lang.String r270 = com.stkouyu.util.AiUtil.readFileFromAssets(ctx, "s");
        com.stkouyu.util.AiUtil.writeToFile("s", "s");
        com.stkouyu.util.AiUtil.writeToFile(file, "s");
        com.stkouyu.util.AiUtil.writeToFile(file, in);
        java.io.File r274 = com.stkouyu.util.AiUtil.unzipFileCN(ctx, "s");
        java.io.File r275 = com.stkouyu.util.AiUtil.unzipFile(ctx, "s");
        java.io.File r276 = com.stkouyu.util.AiUtil.copyDb2SD(ctx, "s");
        java.io.File r277 = com.stkouyu.util.AiUtil.getFilesDir(ctx);
        try {
            com.stkouyu.util.AiUtil.copyNativeResToSD(ctx, "s");
        } catch (java.io.IOException e279) {
            // declared
        }
        // ---- com.stkouyu.util.CommandUtil
        Object o279 = new com.stkouyu.util.CommandUtil();
        int r280 = com.stkouyu.util.CommandUtil.execute("s");
        int r281 = com.stkouyu.util.CommandUtil.execute(new String[] {"true"});
        // ---- com.stkouyu.util.DeviceUtils
        Object o282 = new com.stkouyu.util.DeviceUtils();
        boolean r283 = com.stkouyu.util.DeviceUtils.hasFroyo();
        boolean r284 = com.stkouyu.util.DeviceUtils.muteAudioFocus(ctx, true);
        boolean r285 = com.stkouyu.util.DeviceUtils.ping();
        // ---- com.stkouyu.util.HandlerUtils
        com.stkouyu.util.HandlerUtils r286 = com.stkouyu.util.HandlerUtils.getInstance();
        android.os.Handler r287 = handlerUtils.getNewHandler();
        android.os.Handler r288 = handlerUtils.getNewHandlerCB(handlerDispose);
        android.os.Handler r289 = handlerUtils.getNewChildHandler();
        android.os.Handler r290 = handlerUtils.getNewChildHandlerCB(handlerDispose);
        android.os.Handler r291 = handlerUtils.getUIHandler();
        android.os.Handler r292 = handlerUtils.getUIHandlerCB(handlerDispose);
        handlerUtils.UIOnFinish();
        // ---- com.stkouyu.util.httputil.Consts
        Object o294 = new com.stkouyu.util.httputil.Consts();
        // ---- com.stkouyu.util.httputil.EncodingUtils
        Object o295 = new com.stkouyu.util.httputil.EncodingUtils();
        java.lang.String r296 = com.stkouyu.util.httputil.EncodingUtils.getString(new byte[1], 1, 1, "s");
        java.lang.String r297 = com.stkouyu.util.httputil.EncodingUtils.getString(new byte[1], "s");
        byte[] r298 = com.stkouyu.util.httputil.EncodingUtils.getBytes("s", "s");
        byte[] r299 = com.stkouyu.util.httputil.EncodingUtils.getAsciiBytes("s");
        java.lang.String r300 = com.stkouyu.util.httputil.EncodingUtils.getAsciiString(new byte[1], 1, 1);
        java.lang.String r301 = com.stkouyu.util.httputil.EncodingUtils.getAsciiString(new byte[1]);
        // ---- com.stkouyu.util.httputil.TextUtils
        Object o302 = new com.stkouyu.util.httputil.TextUtils();
        boolean r303 = com.stkouyu.util.httputil.TextUtils.isEmpty("cs");
        boolean r304 = com.stkouyu.util.httputil.TextUtils.isBlank("cs");
        boolean r305 = com.stkouyu.util.httputil.TextUtils.containsBlanks("cs");
        // ---- com.stkouyu.util.LogCat
        Object o306 = new com.stkouyu.util.LogCat();
        com.stkouyu.util.LogCat.destorytLogCat();
        com.stkouyu.util.LogCat.initLogCat(ctx, "s");
        com.stkouyu.util.LogCat.initLogCat(ctx, "s", "s");
        com.stkouyu.util.LogCat.pushLogManually(ctx);
        com.stkouyu.util.LogCat.pushLog(ctx);
        // ---- com.stkouyu.util.MyLog
        Object o312 = new com.stkouyu.util.MyLog();
        com.stkouyu.util.MyLog.w("s", (Object) "o");
        com.stkouyu.util.MyLog.e("s", (Object) "o");
        com.stkouyu.util.MyLog.d("s", (Object) "o");
        com.stkouyu.util.MyLog.i("s", (Object) "o");
        com.stkouyu.util.MyLog.v("s", (Object) "o");
        com.stkouyu.util.MyLog.w("s", "s");
        com.stkouyu.util.MyLog.e("s", "s");
        com.stkouyu.util.MyLog.e("s", "s", new Throwable("t"));
        com.stkouyu.util.MyLog.d("s", "s");
        com.stkouyu.util.MyLog.i("s", "s");
        com.stkouyu.util.MyLog.v("s", "s");
        com.stkouyu.util.MyLog.init(ctx, true);
        // ---- com.stkouyu.util.MyUtil
        Object o325 = new com.stkouyu.util.MyUtil();
        java.lang.String r326 = com.stkouyu.util.MyUtil.getSerialNumber(ctx, "s");
        boolean r327 = com.stkouyu.util.MyUtil.isExistsProvisionFileInDD(ctx);
        boolean r328 = com.stkouyu.util.MyUtil.isNull("s");
        boolean r329 = com.stkouyu.util.MyUtil.isNotNull("s");
    }

    // ---------------------------------------------------------------- types that need care
    void innerClassesAndProtectedOverrides() {
        // PlayBackTask is a non-static inner class of STRecorder
        STRecorder.PlayBackTask task = recorder.new PlayBackTask();
        AsyncTask<String, Integer, Void> asAsyncTask = task;
        STRecorder.PlayBackTask custom = recorder.new PlayBackTask() {
            @Override
            protected Void doInBackground(String... paths) {
                return super.doInBackground(paths);
            }

            @Override
            protected void onCancelled() {
                super.onCancelled();
            }

            @Override
            protected void onPostExecute(Void result) {
                super.onPostExecute(result);
            }
        };
        recorder.mPlayBackTask = custom;
        // CountDownTimer
        countDownTimer.cancel();
        boolean cancelled = countDownTimer.isCancelled();
        CountDownTimer stopped = countDownTimer.stop();
        CountDownTimer started = countDownTimer.start();
        long now = countDownTimer.getNowTime();
        // engine_status is an enum
        SkEgnManager.engine_status status = manager.getEngineStatus();
        Enum<SkEgnManager.engine_status> asEnum = status;
        switch (status) {
            case IDLE:
            case RECORDING:
            case PAUSED:
            case STOP:
            default:
                break;
        }
        int ordinal = SkEgnManager.engine_status.STOP.ordinal();
        // finalize is public and declares no exception
        recorder.finalize();
    }

    void generics() {
        List<String> errIds = recordSetting.getErrIds();
        recordSetting.setErrIds(new ArrayList<String>());
        ArrayList<CustomParam> params = recordSetting.getNewParams();
        ArrayList<CustomParam> mine = new ArrayList<CustomParam>();
        mine.add(customParam);
        RecordSetting fluent = recordSetting.setNewParams(mine);
        String notNull = Args.notNull("value", "name");
        StringBuilder notEmpty = Args.notEmpty(new StringBuilder("v"), "name");
        String notBlank = Args.notBlank("v", "name");
        String noBlanks = Args.containsNoBlanks("v", "name");
        ArrayList<Integer> list = Args.notEmpty(new ArrayList<Integer>(), "name");
        Collection<String> col = Args.notEmpty((Collection<String>) errIds, "name");
        Args.check(true, "message");
        Args.check(true, "%s %s", "a", "b");
        Args.check(true, "%s", (Object) "a");
        int p1 = Args.positive(1, "n");
        long p2 = Args.positive(1L, "n");
        int p3 = Args.notNegative(0, "n");
        long p4 = Args.notNegative(0L, "n");
        Object a = new Args();
    }

    void fields() {
        File provision = engineSetting.provisionFile;
        engineSetting.provisionFile = provision;
        boolean recording = recorder.isRecording;
        recorder.isRecording = recording;
        recorder.mIsRecordPaused = recorder.mIsRecordPaused;
        recorder.mIsReStartRecord = recorder.mIsReStartRecord;
        STRecorder.PlayBackTask task = recorder.mPlayBackTask;
        long handle = SkEgnManager.engine;
        SkEgnManager.engine = handle;
        SkEgn.SKEGN_MESSAGE_TYPE_JSON = SkEgn.SKEGN_MESSAGE_TYPE_JSON;
        SkEgn.SKEGN_MESSAGE_TYPE_BIN = SkEgn.SKEGN_MESSAGE_TYPE_BIN;
        SkEgn.SKEGN_OPT_GET_VERSION = SkEgn.SKEGN_OPT_GET_VERSION;
        SkEgn.SKEGN_OPT_GET_MODULES = SkEgn.SKEGN_OPT_GET_MODULES;
        SkEgn.SKEGN_OPT_GET_TRAFFIC = SkEgn.SKEGN_OPT_GET_TRAFFIC;
        SkEgn.SKEGN_OPT_SET_WIFI_STATUS = SkEgn.SKEGN_OPT_SET_WIFI_STATUS;
        SkEgn.SKEGN_OPT_GET_PROVISION = SkEgn.SKEGN_OPT_GET_PROVISION;
        SkEgn.SKEGN_OPT_GET_SERIAL_NUMBER = SkEgn.SKEGN_OPT_GET_SERIAL_NUMBER;
        MyLog.MYLOG_PATH_SDCARD_DIR = MyLog.MYLOG_PATH_SDCARD_DIR;
        MyLog log = new MyLog();
        log.context = ctx;
        String tag = CommandUtil.TAG;
        Charset utf8 = Consts.UTF_8;
        Charset ascii = Consts.ASCII;
        Charset latin1 = Consts.ISO_8859_1;
        SkEgnManager.engine_status[] all = {SkEgnManager.engine_status.IDLE, SkEgnManager.engine_status.RECORDING,
            SkEgnManager.engine_status.PAUSED, SkEgnManager.engine_status.STOP};
    }

    // ---------------------------------------------------------------- constants as compile-time constants
    static final String CONCAT = AppConfig.CLOUD_SERVER_ADDRESS + AppConfig.PROVISION + AudioType.WAV + AudioType.MP3
            + Build.VERSION + EngineType.ENGINE_CLOUD + EngineType.ENGINE_NATIVE + EngineType.ENGINE_MULTI
            + Mode.SCHOOL + Mode.HOME + CommandUtil.COMMAND_SH + CommandUtil.COMMAND_LINE_END + CommandUtil.COMMAND_EXIT;

    static int ints(int v) {
        switch (v) {
            case AgeGroup.AGEGROUP1:
            case AgeGroup.AGEGROUP2:
            case AgeGroup.AGEGROUP3:
                return 1;
            default:
                break;
        }
        switch (v) {
            case QType.QTYPE_EMPTY:
            case QType.QTYPE_ESSAY_TO_READ:
            case QType.QTYPE_SENTENCE_TRANSLATION:
            case QType.QTYPE_PARAGRAPH_TRANSLATION:
            case QType.QTYPE_STORY_RETELLING:
            case QType.QTYPE_LOOK_PIC_TO_SPEAK:
            case QType.QTYPE_SITUATIONAL:
            case QType.QTYPE_ORAL_COMPOSITION:
            case QType.QTYPE_SENTENCE_ALOUD:
                return 2;
            default:
                break;
        }
        switch (v) {
            case QType.QTYPE_ESSAY_ALOUD:
                return 3;
            default:
                break;
        }
        switch (v) {
            case SkEgnManager.CODE_CREATE_ENGINE_FAIL:
            case SkEgnManager.CODE_SKEGN_START_FAIL:
            case SkEgnManager.CODE_START_INIT_ENGINE:
            case SkEgnManager.CODE_INIT_ENGINE_SUCCESS:
            case SkEgnManager.CODE_INIT_ENGINE_FAILED:
            case SkEgnManager.CODE_RECORD_START:
            case SkEgnManager.CODE_RECORD_RECORDING:
            case SkEgnManager.CODE_RECORD_END:
            case SkEgnManager.CODE_PLAY_END:
            case SkEgnManager.CODE_RECORD_BUFFER:
            case SkEgnManager.CODE_RECORDER_START:
            case SkEgnManager.CODE_RECORDER_ONSTARTRECORDFAIL:
            case SkEgnManager.CODE_RECORDER_TICK:
            case SkEgnManager.CODE_RECORDER_PAUSE:
            case SkEgnManager.CODE_RECORDER_END:
            case SkEgnManager.CODE_PLAY_START:
            case SkEgnManager.CODE_PLAY_START_FAIL:
                return 4;
            default:
                break;
        }
        switch (v) {
            case Consts.CR:
            case Consts.LF:
            case Consts.SP:
            case Consts.HT:
                return 5;
            default:
                return 0;
        }
    }

    static int strings(String s) {
        switch (s) {
            case CoreType.EN_SENT_EVAL:
            case CoreType.EN_WORD_EVAL:
            case CoreType.EN_CHOICE_REC:
            case CoreType.EN_OPEN_EVAL:
            case CoreType.EN_PARA_EVAL:
            case CoreType.EN_ASR_REC:
            case CoreType.EN_ALIGN_EVAL:
            case CoreType.WORD_EVAL_PRO:
            case CoreType.SENT_EVAL_PRO:
            case CoreType.CN_WORD_EVAL:
            case CoreType.CN_SENT_EVAL:
            case CoreType.CN_PARA_EVAL:
            case CoreType.EN_ASR_EVAL:
            case CoreType.FR_WORD_EVAL:
            case CoreType.FR_SENT_EVAL:
            case CoreType.FR_PARA_EVAL:
            case CoreType.JP_WORD_EVAL:
            case CoreType.JP_SENT_EVAL:
            case CoreType.JP_PARA_EVAL:
            case CoreType.KR_WORD_EVAL:
            case CoreType.KR_SENT_EVAL:
            case CoreType.KR_PARA_EVAL:
                return 1;
            default:
                break;
        }
        switch (s) {
            case SkEgnManager.SERVER_TYPE_CLOUD:
            case SkEgnManager.SERVER_TYPE_NATIVE:
            case SkEgnManager.SERVER_TYPE_MULTI:
                return 2;
            default:
                return 0;
        }
    }

    // ---------------------------------------------------------------- classes customers may extend
    static class MyAgeGroup extends AgeGroup {
    }

    static class MyAppConfig extends AppConfig {
    }

    static class MyAudioType extends AudioType {
    }

    static class MyCoreType extends CoreType {
    }

    static class MyEngineType extends EngineType {
    }

    static class MyMode extends Mode {
    }

    static class MyQType extends QType {
    }

    static class MyCustomParam extends CustomParam {
        MyCustomParam() {
            super("k", null);
        }
    }

    static class MySimpleLame extends SimpleLame {
    }

    static class MyRecordSetting extends RecordSetting {
        MyRecordSetting() {
            super(CoreType.EN_WORD_EVAL, "apple");
        }

        @Override
        public String toString() {
            return super.toString();
        }
    }

    static class MyAiUtil extends AiUtil {
    }

    static class MyCommandUtil extends CommandUtil {
    }

    static class MyDeviceUtils extends DeviceUtils {
    }

    static class MyLogCat extends LogCat {
    }

    static class MyMyLog extends MyLog {
    }

    static class MyMyUtil extends MyUtil {
    }

    static class MyArgs extends Args {
    }
}
