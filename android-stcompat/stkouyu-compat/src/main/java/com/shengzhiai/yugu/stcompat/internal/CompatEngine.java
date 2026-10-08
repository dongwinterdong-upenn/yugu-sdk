// SPDX-License-Identifier: Apache-2.0
package com.shengzhiai.yugu.stcompat.internal;

import android.content.Context;
import android.os.SystemClock;

import com.stkouyu.SkEgnManager;
import com.stkouyu.listener.OnInitEngineListener;
import com.stkouyu.listener.OnPlayerListener;
import com.stkouyu.listener.OnRecordBufferListener;
import com.stkouyu.listener.OnRecordListener;
import com.stkouyu.listener.OnRecorderListener;
import com.stkouyu.setting.EngineSetting;
import com.stkouyu.setting.RecordSetting;
import com.stkouyu.util.AiUtil;
import com.stkouyu.util.DeviceUtils;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Engine behind {@code SkEgnManager} (DESIGN 6): cloud only, one evaluation at a time, every
 * listener callback on the main thread.
 *
 * <pre>
 * IDLE -startRecord-> RECORDING -pauseRecord-> PAUSED -restartRecord-> RECORDING
 * RECORDING|PAUSED -stopRecord, VAD end, duration, 300 s-> STOP (evaluating, then result delivered)
 * existsAudioTrans: IDLE|STOP -> STOP (evaluating)
 * any -cancel-> IDLE (no further callbacks)       any -recycle-> IDLE, not initialised
 * </pre>
 *
 * A new session may start once the previous result was delivered; until then the engine is busy
 * (onStartRecordFail("engine is busy") or errId 60008).
 */
public final class CompatEngine {
    /** At most 2 extra submissions for autoRetry. */
    static final int MAX_AUTO_RETRY = 2;
    /** PCM bytes of the 300 s duration limit (16 kHz mono 16 bit): recordings and stream sessions stop here. */
    static final long MAX_PCM_BYTES = (long) CompatConfig.MAX_AUDIO_SECONDS * Wav.BYTES_PER_SECOND;
    /** onRecording is reported at most every 100 ms of audio unless the VAD status changes. */
    static final int REPORT_BYTES = Wav.BYTES_PER_SECOND / 10;

    private static final Object INSTANCE_LOCK = new Object();
    private static volatile CompatEngine instance;

    /** Snapshot of the engine configuration taken at init. */
    static final class Config {
        final String appKey;
        final String secretKey;
        final String userId;
        final String baseUrl;
        final int connectTimeoutSec;
        final int serverTimeoutSec;
        final boolean vadEnabled;

        Config(String appKey, String secretKey, String userId, String baseUrl, int connectTimeoutSec,
               int serverTimeoutSec, boolean vadEnabled) {
            this.appKey = appKey;
            this.secretKey = secretKey;
            this.userId = userId;
            this.baseUrl = baseUrl;
            this.connectTimeoutSec = connectTimeoutSec;
            this.serverTimeoutSec = serverTimeoutSec;
            this.vadEnabled = vadEnabled;
        }
    }

    /** One recording or file evaluation. */
    static final class Session {
        final String tokenId;
        final RecordSetting setting;
        final Config config;
        final String coreType;
        final LinkedHashMap<String, String> fields;
        final boolean stream;
        final boolean fileMode;
        // settings snapshot: apps often reuse one RecordSetting for the next question
        final String refText;
        final String audioType;
        final boolean autoRetry;
        final List<String> errIds;
        final boolean needParams;
        final Integer serverTimeoutSec;
        final Integer duration;
        final Integer durationInterval;
        final CancelToken token = new CancelToken();
        final WavStreamStripper stripper = new WavStreamStripper();
        volatile OnRecorderListener recorderListener;
        volatile OnRecordListener recordListener;
        volatile boolean cancelled;
        /** Guarded by the engine lock. */
        boolean evaluating;
        File file;
        Wav.Writer writer;
        Capture capture;
        EnergyVad vad;
        boolean reportRecording;
        boolean vadAutoStop;
        boolean mutedMusic;
        Integer readTimeoutOverrideMs;
        long pcmBytes;
        boolean writerClosed;
        int lastReportedStatus = -1;
        long lastReportedBytes;
        boolean endRequested;
        /** Main thread only. */
        long startUptime;
        long pausedSince;
        long pausedTotal;
        boolean paused;
        long durationMs;
        long intervalMs;
        Runnable tickTask;

        Session(String tokenId, RecordSetting setting, Config config, OnRecorderListener rl, OnRecordListener l,
                boolean stream, boolean fileMode) {
            this.tokenId = tokenId;
            this.setting = setting;
            this.config = config;
            this.coreType = setting.getCoreType() == null ? "" : setting.getCoreType().trim();
            this.fields = FormMapper.fields(setting);
            this.stream = stream;
            this.fileMode = fileMode;
            this.refText = setting.getRefText();
            this.audioType = setting.getAudioType();
            this.autoRetry = setting.isAutoRetry();
            List<String> ids = setting.getErrIds();
            this.errIds = ids == null ? new ArrayList<String>() : new ArrayList<String>(ids);
            this.needParams = setting.isNeedRequestParamsInResult();
            this.serverTimeoutSec = setting.getServerTimeout();
            this.duration = setting.getDuration();
            this.durationInterval = setting.getDurationInterval();
            this.recorderListener = rl;
            this.recordListener = l;
        }
    }

    private final Object lock = new Object();
    /** Serialises the start-up of a session with stop, pause, restart and cancel from other threads. */
    private final Object startLock = new Object();
    private volatile Context context;
    private final Player player = new Player();

    // guarded by lock
    private Config config;
    private boolean initialized;
    private SkEgnManager.engine_status status = SkEgnManager.engine_status.IDLE;
    private Session current;
    private String lastRecordPath;
    private OnRecorderListener defaultRecorderListener;
    private OnPlayerListener playerListener;
    private OnRecordBufferListener bufferListener;
    private OnInitEngineListener initListener;
    private int initGeneration;
    private AudioInput preparedInput;
    private ExecutorService executor;

    private CompatEngine(Context context) {
        this.context = context;
    }

    /** Process-wide engine; a non-null context replaces the stored application context. */
    public static CompatEngine get(Context context) {
        CompatEngine e = instance;
        if (e == null) {
            synchronized (INSTANCE_LOCK) {
                e = instance;
                if (e == null) {
                    e = new CompatEngine(appContext(context));
                    instance = e;
                }
            }
        }
        if (context != null) {
            e.context = appContext(context);
        }
        return e;
    }

    private static Context appContext(Context c) {
        if (c == null) {
            return null;
        }
        Context app = c.getApplicationContext();
        return app != null ? app : c;
    }

    public Context context() {
        return context;
    }

    /** Platform base from the EngineSetting of the initialised engine, null when none. */
    public static String currentBaseUrl() {
        CompatEngine e = instance;
        if (e == null) {
            return null;
        }
        synchronized (e.lock) {
            return e.config == null ? null : e.config.baseUrl;
        }
    }

    // ================================================================ init

    /**
     * Validates the inputs and posts onStartInitEngine then onInitEngineSuccess or
     * onInitEngineFailed(reason) to the listener of the EngineSetting. No network I/O.
     */
    public boolean init(String appKey, String secretKey, String userId, EngineSetting setting, String requestedType) {
        EngineSetting es = setting != null ? setting : EngineSetting.getInstance(context);
        final int gen;
        final OnInitEngineListener listener = es.getOnInitEngineListener();
        synchronized (lock) {
            initListener = listener;
            gen = initGeneration;
        }
        postInit(gen, listener, 0, null);
        String type = requestedType != null ? requestedType : es.getEngineType();
        if (type != null && !"cloud".equals(type)) {
            YLog.w("engine type " + type + " is not available in the compat layer; running on cloud");
        }
        applyLogSettings(es);
        String reason = null;
        String base = null;
        if (Codec.isBlank(appKey)) {
            reason = "appKey is empty";
        } else if (Codec.isBlank(secretKey)) {
            reason = "secretKey is empty";
        } else {
            try {
                base = ServerAddress.resolve(es.getServerAddress());
            } catch (IllegalArgumentException e) {
                reason = e.getMessage();
            }
        }
        if (reason != null) {
            synchronized (lock) {
                initialized = false;
                config = null;
            }
            YLog.e("init failed: " + reason);
            postInit(gen, listener, 2, reason);
            return false;
        }
        String uid = userId != null ? userId : es.getUserId();
        Config c = new Config(appKey.trim(), secretKey.trim(), uid, base, es.getConnectTimeout(), es.getServerTimeout(),
                es.isVADEnabled());
        synchronized (lock) {
            config = c;
            initialized = true;
        }
        YLog.i("engine ready: appKey=" + YLog.maskKey(c.appKey) + " base=" + ServerAddress.effectiveBase(base)
                + " version=" + CompatConfig.SDK_VERSION);
        postInit(gen, listener, 1, null);
        return true;
    }

    private void applyLogSettings(EngineSetting es) {
        if (es.isSDKLogEnabled()) {
            int l = es.getLogLevel();
            YLog.setLevelFromSettings(l <= 0 ? YLog.ERROR : l == 1 ? YLog.WARN : l == 2 ? YLog.INFO : YLog.DEBUG);
        }
        if (es.getEnableSaveLogCatToFile()) {
            LogFileSink.install(new File(new File(AiUtil.externalFilesDir(context), "log"), "stkouyu_sdk.log"));
        }
        if (es.getEnableUploadLog()) {
            YLog.warnOnce("uploadLog", "log upload is not available in the compat layer; logs stay on the device");
        }
    }

    /** Posts one init callback; clearInitListener and recycle drop the pending ones. */
    private void postInit(final int gen, final OnInitEngineListener l, final int kind, final String reason) {
        if (l == null) {
            return;
        }
        MainThread.post(new Runnable() {
            @Override
            public void run() {
                synchronized (lock) {
                    if (gen != initGeneration) {
                        return;
                    }
                }
                if (kind == 0) {
                    l.onStartInitEngine();
                } else if (kind == 1) {
                    l.onInitEngineSuccess();
                } else {
                    l.onInitEngineFailed(reason);
                }
            }
        });
    }

    public boolean isInitialized() {
        synchronized (lock) {
            return initialized;
        }
    }

    // ================================================================ recording

    /** Starts a recording (microphone) or a stream session (setIsStream(true), audio from feed). */
    public void startRecord(RecordSetting setting, OnRecorderListener rl, OnRecordListener l) {
        synchronized (startLock) {
            startRecordLocked(setting, rl, l);
        }
    }

    private void startRecordLocked(RecordSetting setting, OnRecorderListener rl, OnRecordListener l) {
        RecordSetting s = setting != null ? setting : new RecordSetting();
        final Session session;
        synchronized (lock) {
            if (rl == null && l == null) {
                rl = defaultRecorderListener;
            }
            if (!initialized || config == null) {
                postEarlyError(rl, l, CompatErrIds.ENGINE_NOT_INITIALIZED, null);
                return;
            }
            if (busyLocked()) {
                YLog.w("startRecord rejected: engine is busy (" + status + ")");
                if (rl != null) {
                    postStartFail(rl, "engine is busy");
                } else {
                    postEarlyError(null, l, CompatErrIds.ENGINE_BUSY, config.appKey);
                }
                return;
            }
            int invalid = FormMapper.validate(s);
            if (invalid != 0) {
                postEarlyError(rl, l, invalid, config.appKey);
                return;
            }
            session = new Session(Codec.newTokenId(), s, config, rl, l, s.getIsStream(), false);
            boolean vadOn = config.vadEnabled || s.isVADEnabled();
            session.vad = new EnergyVad(s.getSeek(), s.getRef_length());
            session.reportRecording = vadOn || s.isNeedSoundIntensity();
            session.vadAutoStop = vadOn && !s.isForceRecord();
            current = session;
            status = SkEgnManager.engine_status.RECORDING;
        }
        try {
            session.file = recordFile(s, session.tokenId);
            session.writer = new Wav.Writer(session.file, Wav.SAMPLE_RATE, Wav.CHANNELS);
        } catch (IOException e) {
            abortStart(session, CompatErrIds.AUDIO_FILE_MISSING, "cannot create record file: " + e.getMessage());
            return;
        }
        if (!session.stream) {
            AudioInput in;
            try {
                in = takePreparedInput(s.getAudioSource());
                if (in == null) {
                    in = CompatConfig.audioInputFactory().open(context, s.getAudioSource());
                }
                session.capture = new Capture(in, captureSink(session), "yugu-stcompat-capture");
                session.capture.startDevice();
            } catch (AudioInput.AudioInputException e) {
                YLog.w("microphone unavailable: " + e.getMessage());
                abortStart(session, CompatErrIds.MICROPHONE_UNAVAILABLE,
                        "microphone unavailable or permission denied: " + e.getMessage());
                return;
            }
        }
        if (s.isMuteMusic()) {
            session.mutedMusic = DeviceUtils.muteAudioFocus(context, true);
        }
        synchronized (lock) {
            lastRecordPath = session.file.getAbsolutePath();
        }
        YLog.i("recording started tokenId=" + session.tokenId + " coreType=" + session.coreType
                + (session.stream ? " (stream)" : ""));
        MainThread.post(new Runnable() {
            @Override
            public void run() {
                if (session.cancelled) {
                    return;
                }
                session.startUptime = SystemClock.uptimeMillis();
                OnRecorderListener r = session.recorderListener;
                OnRecordListener rec = session.recordListener;
                if (r != null) {
                    r.onStart();
                } else if (rec != null) {
                    rec.onRecordStart();
                }
                startTicks(session);
            }
        });
        // onStart is queued before the first onRecording or onRecordBuffer of the capture thread
        if (session.capture != null) {
            session.capture.startThread();
        }
    }

    private void abortStart(Session session, int errId, String reason) {
        synchronized (lock) {
            if (current == session) {
                current = null;
                status = SkEgnManager.engine_status.IDLE;
            }
        }
        session.cancelled = true;
        closeWriter(session);
        if (session.file != null && session.file.length() <= Wav.HEADER_SIZE) {
            //noinspection ResultOfMethodCallIgnored
            session.file.delete();
        }
        YLog.w("start failed (errId " + errId + "): " + reason);
        if (session.recorderListener != null) {
            postStartFail(session.recorderListener, reason);
        } else if (session.recordListener != null) {
            final OnRecordListener l = session.recordListener;
            final String json = Envelope.error(session.tokenId, errId, reason, session.config.appKey);
            MainThread.post(new Runnable() {
                @Override
                public void run() {
                    l.onRecordEnd(json);
                }
            });
        }
    }

    private Capture.Sink captureSink(final Session s) {
        return new Capture.Sink() {
            @Override
            public void onPcm(byte[] data, int length) {
                handlePcm(s, data, 0, length);
            }

            @Override
            public void onCaptureError(final String reason) {
                MainThread.post(new Runnable() {
                    @Override
                    public void run() {
                        failRecording(s, CompatErrIds.MICROPHONE_UNAVAILABLE, reason);
                    }
                });
            }
        };
    }

    /** Appends PCM to the session file and drives VAD, intensity, buffer callbacks and auto stop. */
    void handlePcm(final Session s, byte[] data, int off, int len) {
        if (s.cancelled || len <= 0) {
            return;
        }
        int n;
        boolean limit;
        synchronized (s) {
            if (s.writerClosed) {
                return;
            }
            long room = MAX_PCM_BYTES - s.pcmBytes;
            n = (int) Math.max(0, Math.min(len, room));
            if (n > 0) {
                try {
                    s.writer.write(data, off, n);
                } catch (IOException e) {
                    final String reason = "cannot write record file: " + e.getMessage();
                    MainThread.post(new Runnable() {
                        @Override
                        public void run() {
                            failRecording(s, CompatErrIds.AUDIO_FILE_MISSING, reason);
                        }
                    });
                    return;
                }
                s.pcmBytes += n;
            }
            limit = s.pcmBytes >= MAX_PCM_BYTES;
        }
        int vadStatus = n > 0 ? s.vad.process(data, off, n) : s.vad.status();
        if (s.reportRecording && n > 0) {
            boolean report;
            synchronized (s) {
                report = vadStatus != s.lastReportedStatus || s.pcmBytes - s.lastReportedBytes >= REPORT_BYTES;
                if (report) {
                    s.lastReportedStatus = vadStatus;
                    s.lastReportedBytes = s.pcmBytes;
                }
            }
            if (report) {
                final int st = vadStatus;
                final int intensity = s.vad.takePeakIntensity();
                MainThread.post(new Runnable() {
                    @Override
                    public void run() {
                        if (s.cancelled) {
                            return;
                        }
                        OnRecorderListener r = s.recorderListener;
                        OnRecordListener rec = s.recordListener;
                        if (r != null) {
                            r.onRecording(st, intensity);
                        } else if (rec != null) {
                            rec.onRecording(st, intensity);
                        }
                    }
                });
            }
        }
        final OnRecordBufferListener bl;
        synchronized (lock) {
            bl = bufferListener;
        }
        if (bl != null && n > 0) {
            final byte[] copy = new byte[n];
            System.arraycopy(data, off, copy, 0, n);
            MainThread.post(new Runnable() {
                @Override
                public void run() {
                    if (!s.cancelled) {
                        bl.onRecordBuffer(copy, copy.length);
                    }
                }
            });
        }
        String why = null;
        synchronized (s) {
            if (!s.endRequested && ((vadStatus == 2 && s.vadAutoStop) || limit)) {
                s.endRequested = true;
                why = limit ? "300 s limit" : "VAD end of speech";
            }
        }
        if (why != null) {
            final String reason = why;
            MainThread.post(new Runnable() {
                @Override
                public void run() {
                    autoStop(s, reason);
                }
            });
        }
    }

    private void autoStop(Session s, String why) {
        synchronized (lock) {
            if (current != s || s.cancelled || (status != SkEgnManager.engine_status.RECORDING
                    && status != SkEgnManager.engine_status.PAUSED)) {
                return;
            }
        }
        YLog.i("recording stopped automatically: " + why);
        stopRecord();
    }

    /** Recording broke after it started: stop and deliver an error JSON. */
    private void failRecording(final Session s, final int errId, final String reason) {
        synchronized (lock) {
            if (current != s || s.cancelled || (status != SkEgnManager.engine_status.RECORDING
                    && status != SkEgnManager.engine_status.PAUSED)) {
                return;
            }
            status = SkEgnManager.engine_status.STOP;
            s.evaluating = true;
        }
        stopTicks(s);
        if (s.capture != null) {
            s.capture.stop();
        }
        YLog.e("recording failed (errId " + errId + "): " + reason);
        executor().execute(new Runnable() {
            @Override
            public void run() {
                if (s.capture != null) {
                    s.capture.await(3000);
                }
                closeWriter(s);
                restoreMusic(s);
                deliverFinal(s, Envelope.error(s.tokenId, errId, reason, s.config.appKey));
            }
        });
    }

    /** Appends caller audio in stream mode (setIsStream(true)). */
    public void feed(byte[] data, int len) {
        if (data == null || len <= 0) {
            return;
        }
        int n = Math.min(len, data.length);
        Session s;
        synchronized (lock) {
            s = current;
            if (s == null || s.cancelled || !s.stream || status != SkEgnManager.engine_status.RECORDING) {
                if (s != null && s.stream && status == SkEgnManager.engine_status.PAUSED) {
                    YLog.d("feed dropped while paused");
                } else {
                    YLog.warnOnce("feedIgnored", "feed ignored: no stream session is recording"
                            + " (use RecordSetting.setIsStream(true) and startRecord first)");
                }
                return;
            }
        }
        byte[] pcm;
        synchronized (s.stripper) {
            pcm = s.stripper.feed(data, n);
        }
        handlePcm(s, pcm, 0, pcm.length);
    }

    /** Stops capture, posts onRecordEnd, then evaluates and posts the result. */
    public void stopRecord() {
        synchronized (startLock) {
            stopRecordLocked();
        }
    }

    private void stopRecordLocked() {
        final Session s;
        synchronized (lock) {
            s = current;
            if (s == null || s.cancelled || s.fileMode || (status != SkEgnManager.engine_status.RECORDING
                    && status != SkEgnManager.engine_status.PAUSED)) {
                YLog.d("stopRecord ignored in state " + status);
                return;
            }
            status = SkEgnManager.engine_status.STOP;
            s.evaluating = true;
        }
        stopTicks(s);
        if (s.capture != null) {
            s.capture.stop();
        }
        executor().execute(new Runnable() {
            @Override
            public void run() {
                finishRecording(s);
            }
        });
    }

    private void finishRecording(final Session s) {
        if (s.capture != null && !s.capture.await(3000)) {
            YLog.w("capture thread did not stop within 3 s");
        }
        if (s.stream) {
            byte[] rest;
            synchronized (s.stripper) {
                rest = s.stripper.flush();
            }
            if (rest.length > 0) {
                handlePcm(s, rest, 0, rest.length);
            }
        }
        closeWriter(s);
        restoreMusic(s);
        if (s.cancelled) {
            return;
        }
        YLog.i("recording ended tokenId=" + s.tokenId + " pcmBytes=" + s.pcmBytes);
        MainThread.post(new Runnable() {
            @Override
            public void run() {
                OnRecorderListener r = s.recorderListener;
                if (!s.cancelled && r != null) {
                    r.onRecordEnd();
                }
            }
        });
        evaluate(s, s.file, new AudioFiles.Kind("audio/wav", "wav", false));
    }

    private void closeWriter(Session s) {
        synchronized (s) {
            if (s.writerClosed) {
                return;
            }
            s.writerClosed = true;
        }
        if (s.writer != null) {
            try {
                s.writer.close();
            } catch (IOException e) {
                YLog.w("cannot finish record file: " + e.getMessage());
            }
        }
    }

    private void restoreMusic(Session s) {
        if (s.mutedMusic) {
            s.mutedMusic = false;
            DeviceUtils.muteAudioFocus(context, false);
        }
    }

    // ================================================================ file evaluation

    /** Evaluates {@code setting.getAudioPath()} (DESIGN 6, existsAudioTrans). */
    public void existsAudioTrans(RecordSetting setting, OnRecorderListener rl, OnRecordListener l, Integer bufSize,
                                 Integer timeout) {
        RecordSetting s = setting != null ? setting : new RecordSetting();
        final Session session;
        synchronized (lock) {
            if (!initialized || config == null) {
                postEarlyError(rl, l, CompatErrIds.ENGINE_NOT_INITIALIZED, null);
                return;
            }
            if (busyLocked()) {
                postEarlyError(rl, l, CompatErrIds.ENGINE_BUSY, config.appKey);
                return;
            }
            int invalid = FormMapper.validate(s);
            if (invalid != 0) {
                postEarlyError(rl, l, invalid, config.appKey);
                return;
            }
            session = new Session(Codec.newTokenId(), s, config, rl, l, false, true);
            current = session;
            status = SkEgnManager.engine_status.STOP;
            session.evaluating = true;
            if (s.getAudioPath() != null) {
                lastRecordPath = s.getAudioPath();
            }
        }
        if (timeout != null && timeout > 0) {
            session.readTimeoutOverrideMs = timeout <= 1000 ? timeout * 1000 : timeout;
        }
        if (bufSize != null) {
            YLog.d("existsAudioTrans bufSize " + bufSize + " has no effect on REST upload");
        }
        MainThread.post(new Runnable() {
            @Override
            public void run() {
                if (session.cancelled) {
                    return;
                }
                OnRecorderListener r = session.recorderListener;
                OnRecordListener rec = session.recordListener;
                if (r != null) {
                    r.onStart();
                    r.onRecordEnd();
                } else if (rec != null) {
                    rec.onRecordStart();
                }
            }
        });
        executor().execute(new Runnable() {
            @Override
            public void run() {
                evaluateFile(session);
            }
        });
    }

    private void evaluateFile(Session s) {
        String path = s.setting.getAudioPath();
        File f = path == null || path.trim().isEmpty() ? null : new File(path.trim());
        if (f == null || !f.isFile() || !f.canRead()) {
            deliverError(s, CompatErrIds.AUDIO_FILE_MISSING, CompatErrIds.message(CompatErrIds.AUDIO_FILE_MISSING)
                    + ": " + path);
            return;
        }
        AudioFiles.Kind kind;
        try {
            kind = AudioFiles.kind(f, s.setting.getAudioType());
        } catch (IOException e) {
            deliverError(s, CompatErrIds.AUDIO_FILE_MISSING, "cannot read " + path + ": " + e.getMessage());
            return;
        }
        File upload = f;
        File temp = null;
        if (kind.rawPcm) {
            int rate = s.setting.getSampleRate() > 0 ? s.setting.getSampleRate() : Wav.SAMPLE_RATE;
            int ch = s.setting.getChannel() > 0 ? s.setting.getChannel() : Wav.CHANNELS;
            try {
                temp = File.createTempFile("yugu-" + s.tokenId, ".wav", cacheDir());
                Wav.wrapPcm(f, temp, rate, ch);
                upload = temp;
            } catch (IOException e) {
                deliverError(s, CompatErrIds.AUDIO_FILE_MISSING, "cannot prepare PCM file: " + e.getMessage());
                return;
            }
        }
        try {
            evaluate(s, upload, kind);
        } finally {
            if (temp != null) {
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
            }
        }
    }

    private File cacheDir() {
        Context c = context;
        File d = c != null ? c.getCacheDir() : null;
        return d != null ? d : new File(System.getProperty("java.io.tmpdir", "."));
    }

    // ================================================================ evaluation

    private void evaluate(Session s, File audio, AudioFiles.Kind kind) {
        if (s.cancelled) {
            return;
        }
        int pre = AudioFiles.precheck(audio, "audio/wav".equals(kind.contentType));
        if (pre != 0) {
            deliverError(s, pre, CompatErrIds.message(pre));
            return;
        }
        CompatClient.Request req = request(s, audio, kind);
        CompatClient client = CompatClient.fromConfig();
        int round = 0;
        while (true) {
            try {
                CompatClient.Result r = client.execute(req, s.token);
                String params = null;
                if (s.needParams) {
                    params = Envelope.params(s.config.appKey, s.config.userId, System.currentTimeMillis(),
                            s.audioType, Wav.SAMPLE_RATE, Wav.CHANNELS, s.coreType, s.tokenId, s.fields);
                }
                String json;
                try {
                    json = Envelope.success(s.tokenId, s.config.appKey, s.config.userId, s.refText,
                            r.body, params, System.currentTimeMillis());
                } catch (IllegalArgumentException e) {
                    deliverError(s, ErrorTable.PROTOCOL_ERROR, "invalid platform response: " + e.getMessage());
                    return;
                }
                YLog.i("result tokenId=" + s.tokenId + " attempts=" + r.attempts + (r.replayed ? " replayed" : ""));
                deliverFinal(s, json);
                return;
            } catch (CompatError e) {
                if (e.isCancelled() || s.cancelled) {
                    return;
                }
                int errId = ErrorMapper.errId(e);
                if (s.autoRetry && round < MAX_AUTO_RETRY && matches(s.errIds, errId)) {
                    round++;
                    long delay = CompatConfig.retryPolicy().delayMs(round, CompatConfig.random().nextDouble(), -1);
                    YLog.w("autoRetry " + round + "/" + MAX_AUTO_RETRY + " for errId " + errId + " in " + delay
                            + " ms, same tokenId " + s.tokenId);
                    if (!CompatConfig.sleeper().sleep(s.token, delay)) {
                        return;
                    }
                    continue;
                }
                deliverError(s, errId, ErrorMapper.message(e, errId));
                return;
            }
        }
    }

    static boolean matches(List<String> errIds, int errId) {
        if (errIds == null) {
            return false;
        }
        String id = String.valueOf(errId);
        for (String e : errIds) {
            if (e != null && id.equals(e.trim())) {
                return true;
            }
        }
        return false;
    }

    private CompatClient.Request request(Session s, File audio, AudioFiles.Kind kind) {
        CompatClient.Request req = new CompatClient.Request();
        req.baseUrl = ServerAddress.effectiveBase(s.config.baseUrl);
        req.coreType = s.coreType;
        req.appKey = s.config.appKey;
        req.secretKey = s.config.secretKey;
        req.idempotencyKey = s.tokenId;
        req.fields = s.fields;
        req.audioFile = audio;
        req.audioFilename = s.tokenId + "." + kind.extension;
        req.audioContentType = kind.contentType;
        req.policy = CompatConfig.retryPolicy();
        req.totalTimeoutMs = CompatConfig.totalTimeoutMs();
        int connect = CompatConfig.connectTimeoutMs();
        req.connectTimeoutMs = connect > 0 ? connect
                : (s.config.connectTimeoutSec > 0 ? s.config.connectTimeoutSec * 1000 : CompatConfig.DEFAULT_CONNECT_TIMEOUT_MS);
        Integer perRecord = s.serverTimeoutSec;
        int read = CompatConfig.readTimeoutMs();
        if (s.readTimeoutOverrideMs != null) {
            req.readTimeoutMs = s.readTimeoutOverrideMs;
        } else if (perRecord != null && perRecord > 0) {
            req.readTimeoutMs = perRecord * 1000;
        } else if (read > 0) {
            req.readTimeoutMs = read;
        } else {
            req.readTimeoutMs = s.config.serverTimeoutSec > 0 ? s.config.serverTimeoutSec * 1000
                    : CompatConfig.DEFAULT_READ_TIMEOUT_MS;
        }
        return req;
    }

    private void deliverError(Session s, int errId, String message) {
        YLog.w("evaluation failed tokenId=" + s.tokenId + " errId=" + errId + ": " + message);
        deliverFinal(s, Envelope.error(s.tokenId, errId, message, s.config.appKey));
    }

    /** Posts the final JSON; the session stops being busy right before the callback runs. */
    private void deliverFinal(final Session s, final String json) {
        MainThread.post(new Runnable() {
            @Override
            public void run() {
                synchronized (lock) {
                    if (s.cancelled) {
                        return;
                    }
                    s.evaluating = false;
                }
                OnRecorderListener r = s.recorderListener;
                OnRecordListener rec = s.recordListener;
                if (r != null) {
                    r.onScore(json);
                } else if (rec != null) {
                    rec.onRecordEnd(json);
                }
            }
        });
    }

    private void postEarlyError(final OnRecorderListener rl, final OnRecordListener l, int errId, String appKey) {
        final String json = Envelope.error(Codec.newTokenId(), errId, CompatErrIds.message(errId), appKey);
        YLog.w("request rejected (errId " + errId + "): " + CompatErrIds.message(errId));
        MainThread.post(new Runnable() {
            @Override
            public void run() {
                if (rl != null) {
                    rl.onScore(json);
                } else if (l != null) {
                    l.onRecordEnd(json);
                }
            }
        });
    }

    private static void postStartFail(final OnRecorderListener rl, final String reason) {
        MainThread.post(new Runnable() {
            @Override
            public void run() {
                rl.onStartRecordFail(reason);
            }
        });
    }

    private boolean busyLocked() {
        Session s = current;
        if (s == null || s.cancelled) {
            return false;
        }
        return status == SkEgnManager.engine_status.RECORDING || status == SkEgnManager.engine_status.PAUSED
                || s.evaluating;
    }

    // ================================================================ ticks (main thread)

    private void startTicks(final Session s) {
        Integer d = s.duration;
        if (d == null || d <= 0) {
            return;
        }
        Integer iv = s.durationInterval;
        s.durationMs = d;
        s.intervalMs = iv != null && iv > 0 ? iv : 100;
        scheduleTick(s);
    }

    private void scheduleTick(final Session s) {
        if (s.durationMs <= 0) {
            return;
        }
        MainThread.remove(s.tickTask);
        s.tickTask = new Runnable() {
            @Override
            public void run() {
                tick(s);
            }
        };
        long elapsed = elapsed(s);
        long nextBoundary = (elapsed / s.intervalMs + 1) * s.intervalMs;
        long delay = Math.max(1, Math.min(nextBoundary, s.durationMs) - elapsed);
        MainThread.postDelayed(s.tickTask, delay);
    }

    private void stopTicks(final Session s) {
        MainThread.run(new Runnable() {
            @Override
            public void run() {
                MainThread.remove(s.tickTask);
                s.tickTask = null;
            }
        });
    }

    private long elapsed(Session s) {
        long now = s.paused ? s.pausedSince : SystemClock.uptimeMillis();
        return Math.max(0, now - s.startUptime - s.pausedTotal);
    }

    private void tick(Session s) {
        synchronized (lock) {
            if (current != s || s.cancelled || status != SkEgnManager.engine_status.RECORDING) {
                return;
            }
        }
        long remaining = Math.max(0, s.durationMs - elapsed(s));
        double percent = s.durationMs > 0 ? remaining * 100.0 / s.durationMs : 0;
        OnRecorderListener r = s.recorderListener;
        if (r != null) {
            r.onTick(remaining, percent);
        }
        if (remaining <= 0) {
            YLog.i("recording reached duration " + s.durationMs + " ms");
            stopRecord();
        } else {
            scheduleTick(s);
        }
    }

    // ================================================================ pause, cancel, recycle

    public void pauseRecord() {
        synchronized (startLock) {
            pauseRecordLocked();
        }
    }

    private void pauseRecordLocked() {
        final Session s;
        synchronized (lock) {
            s = current;
            if (s == null || s.cancelled || status != SkEgnManager.engine_status.RECORDING) {
                YLog.d("pauseRecord ignored in state " + status);
                return;
            }
            status = SkEgnManager.engine_status.PAUSED;
        }
        if (s.capture != null) {
            s.capture.pause();
        }
        MainThread.run(new Runnable() {
            @Override
            public void run() {
                s.paused = true;
                s.pausedSince = SystemClock.uptimeMillis();
                MainThread.remove(s.tickTask);
                s.tickTask = null;
                OnRecorderListener r = s.recorderListener;
                if (!s.cancelled && r != null) {
                    r.onPause();
                }
            }
        });
    }

    public void restartRecord() {
        synchronized (startLock) {
            restartRecordLocked();
        }
    }

    private void restartRecordLocked() {
        final Session s;
        synchronized (lock) {
            s = current;
            if (s == null || s.cancelled || status != SkEgnManager.engine_status.PAUSED) {
                YLog.d("restartRecord ignored in state " + status);
                return;
            }
            status = SkEgnManager.engine_status.RECORDING;
        }
        if (s.capture != null && !s.capture.resume()) {
            MainThread.post(new Runnable() {
                @Override
                public void run() {
                    failRecording(s, CompatErrIds.MICROPHONE_UNAVAILABLE, "microphone could not be restarted");
                }
            });
            return;
        }
        MainThread.run(new Runnable() {
            @Override
            public void run() {
                if (s.paused) {
                    s.pausedTotal += SystemClock.uptimeMillis() - s.pausedSince;
                    s.paused = false;
                }
                if (!s.cancelled) {
                    scheduleTick(s);
                }
            }
        });
    }

    /** Stops and discards the current session; no further callbacks. Idempotent. */
    public void cancel() {
        synchronized (startLock) {
            cancelLocked();
        }
    }

    private void cancelLocked() {
        final Session s;
        synchronized (lock) {
            s = current;
            current = null;
            status = SkEgnManager.engine_status.IDLE;
            if (s == null || s.cancelled) {
                return;
            }
            s.cancelled = true;
        }
        YLog.i("session cancelled tokenId=" + s.tokenId);
        s.token.cancel();
        stopTicks(s);
        if (s.capture != null) {
            s.capture.stop();
        }
        Runnable cleanup = new Runnable() {
            @Override
            public void run() {
                if (s.capture != null) {
                    s.capture.await(3000);
                }
                closeWriter(s);
                restoreMusic(s);
            }
        };
        ExecutorService ex = executorIfRunning();
        if (ex != null) {
            ex.execute(cleanup);
        } else {
            new Thread(cleanup, "yugu-stcompat-cancel").start();
        }
    }

    /** Releases recorder, player, executor and listeners. Idempotent. */
    public void recycle() {
        cancel();
        MainThread.run(new Runnable() {
            @Override
            public void run() {
                player.stop(false);
            }
        });
        releaseMicInternal(true);
        ExecutorService ex;
        synchronized (lock) {
            initialized = false;
            config = null;
            defaultRecorderListener = null;
            playerListener = null;
            bufferListener = null;
            initListener = null;
            initGeneration++;
            status = SkEgnManager.engine_status.IDLE;
            ex = executor;
            executor = null;
        }
        if (ex != null) {
            ex.shutdown();
        }
    }

    public void clearActivityListener() {
        synchronized (lock) {
            defaultRecorderListener = null;
            playerListener = null;
            bufferListener = null;
            if (current != null) {
                current.recorderListener = null;
                current.recordListener = null;
            }
        }
    }

    public void clearInitListener() {
        synchronized (lock) {
            initListener = null;
            initGeneration++;
        }
    }

    public void setRecorderListener(OnRecorderListener l) {
        synchronized (lock) {
            defaultRecorderListener = l;
            if (current != null && current.recordListener == null) {
                current.recorderListener = l;
            }
        }
    }

    public void setPlayerListener(OnPlayerListener l) {
        synchronized (lock) {
            playerListener = l;
        }
    }

    public void setBufferListener(OnRecordBufferListener l) {
        synchronized (lock) {
            bufferListener = l;
        }
    }

    // ================================================================ microphone

    /** Opens the microphone ahead of the next recording. */
    public boolean activeMic() {
        synchronized (lock) {
            if (preparedInput != null) {
                return true;
            }
            if (current != null && current.capture != null && (status == SkEgnManager.engine_status.RECORDING
                    || status == SkEgnManager.engine_status.PAUSED)) {
                return true;
            }
        }
        AudioInput in;
        try {
            in = CompatConfig.audioInputFactory().open(context, null);
        } catch (AudioInput.AudioInputException e) {
            YLog.w("activeMic failed: " + e.getMessage());
            return false;
        }
        synchronized (lock) {
            if (preparedInput == null) {
                preparedInput = in;
                return true;
            }
        }
        in.release();
        return true;
    }

    /** Releases a microphone opened by activeMic; false while a recording uses it. */
    public boolean releaseMic() {
        return releaseMicInternal(false);
    }

    private boolean releaseMicInternal(boolean force) {
        AudioInput in;
        synchronized (lock) {
            if (!force && current != null && current.capture != null && !current.cancelled
                    && (status == SkEgnManager.engine_status.RECORDING || status == SkEgnManager.engine_status.PAUSED)) {
                return false;
            }
            in = preparedInput;
            preparedInput = null;
        }
        if (in != null) {
            in.release();
        }
        return true;
    }

    private AudioInput takePreparedInput(Integer audioSource) {
        synchronized (lock) {
            if (audioSource != null && audioSource != android.media.MediaRecorder.AudioSource.MIC) {
                return null;
            }
            AudioInput in = preparedInput;
            preparedInput = null;
            return in;
        }
    }

    // ================================================================ playback

    public void playback() {
        String path;
        synchronized (lock) {
            path = lastRecordPath;
        }
        playWithPath(path);
    }

    public void playWithPath(final String path) {
        MainThread.run(new Runnable() {
            @Override
            public void run() {
                player.play(path, new Player.Events() {
                    @Override
                    public void onStart() {
                        OnPlayerListener l = playerListener();
                        if (l != null) {
                            l.onPlayStart();
                        }
                    }

                    @Override
                    public void onFail(String reason) {
                        YLog.w("playback failed: " + reason);
                        OnPlayerListener l = playerListener();
                        if (l != null) {
                            l.onPlayStartFail(reason);
                        }
                    }

                    @Override
                    public void onEnd() {
                        OnPlayerListener l = playerListener();
                        if (l != null) {
                            l.onPlayEnd();
                        }
                    }
                });
            }
        });
    }

    public void stopPlay() {
        MainThread.run(new Runnable() {
            @Override
            public void run() {
                player.stop(true);
            }
        });
    }

    private OnPlayerListener playerListener() {
        synchronized (lock) {
            return playerListener;
        }
    }

    // ================================================================ state

    public SkEgnManager.engine_status status() {
        synchronized (lock) {
            return status;
        }
    }

    public String lastRecordPath() {
        synchronized (lock) {
            return lastRecordPath;
        }
    }

    /** Current tokenId, for tests and logs. */
    public String currentTokenId() {
        synchronized (lock) {
            return current == null ? null : current.tokenId;
        }
    }

    private ExecutorService executor() {
        synchronized (lock) {
            if (executor == null) {
                executor = Executors.newCachedThreadPool(new ThreadFactory() {
                    private final AtomicInteger n = new AtomicInteger();

                    @Override
                    public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, "yugu-stcompat-" + n.incrementAndGet());
                        t.setDaemon(true);
                        return t;
                    }
                });
            }
            return executor;
        }
    }

    private ExecutorService executorIfRunning() {
        synchronized (lock) {
            return executor;
        }
    }

    // ================================================================ files

    /** recordFilePath/recordName, or {@code <externalFilesDir>/record/<tokenId>.wav}. */
    File recordFile(RecordSetting s, String tokenId) {
        String dir = s.getRecordFilePath();
        String name = s.getRecordName();
        if (!Codec.isBlank(dir) && Codec.isBlank(name) && hasAudioExtension(dir)) {
            return new File(dir.trim());
        }
        File d = !Codec.isBlank(dir) ? new File(dir.trim()) : new File(AiUtil.externalFilesDir(context), "record");
        String n = !Codec.isBlank(name) ? name.trim() : tokenId + ".wav";
        return new File(d, n);
    }

    private static boolean hasAudioExtension(String path) {
        String p = path.trim().toLowerCase(Locale.ROOT);
        return p.endsWith(".wav") || p.endsWith(".mp3") || p.endsWith(".pcm");
    }

    /** Test hook: drops the process-wide instance after recycling it. */
    public static void resetForTests() {
        CompatEngine e;
        synchronized (INSTANCE_LOCK) {
            e = instance;
            instance = null;
        }
        if (e != null) {
            e.recycle();
        }
    }
}
