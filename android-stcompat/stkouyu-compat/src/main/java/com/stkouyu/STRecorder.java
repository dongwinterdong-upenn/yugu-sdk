// SPDX-License-Identifier: Apache-2.0
package com.stkouyu;

import android.content.Context;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.Message;

import com.shengzhiai.yugu.stcompat.internal.AudioInput;
import com.shengzhiai.yugu.stcompat.internal.Capture;
import com.shengzhiai.yugu.stcompat.internal.CompatConfig;
import com.shengzhiai.yugu.stcompat.internal.MainThread;
import com.shengzhiai.yugu.stcompat.internal.Player;
import com.shengzhiai.yugu.stcompat.internal.Wav;
import com.shengzhiai.yugu.stcompat.internal.YLog;
import com.stkouyu.listener.OnSTRecorderListener;
import com.stkouyu.util.AiUtil;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Stand-alone recorder with the API of the Shengtong SDK, backed by the compat capture engine:
 * 16 kHz mono PCM16 written as WAV (also when the path ends with .mp3), PCM chunks delivered to
 * {@link Callback} on the capture thread, events sent to the {@link #setHandler(Handler) handler}
 * as SkEgnManager.CODE_* messages and to {@link OnSTRecorderListener} on the main thread.
 */
public class STRecorder {

    /** Receives each captured PCM chunk on the capture thread. */
    public interface Callback {
        void run(byte[] data, int size);
    }

    /** Plays a file in the background: {@code execute(path)}. */
    public class PlayBackTask extends AsyncTask<String, Integer, Void> {
        public PlayBackTask() {
        }

        @Override
        protected Void doInBackground(String... paths) {
            if (paths == null || paths.length == 0) {
                return null;
            }
            return playBackFile(paths[0]);
        }

        @Override
        protected void onCancelled() {
            stopPlayer(false);
        }

        @Override
        protected void onPostExecute(Void result) {
            if (mPlayBackTask == this) {
                mPlayBackTask = null;
            }
        }
    }

    private static volatile STRecorder instance;

    public volatile boolean isRecording;
    public volatile boolean mIsRecordPaused;
    public volatile boolean mIsReStartRecord;
    public PlayBackTask mPlayBackTask;

    private final Object lock = new Object();
    private final Player player = new Player();
    private volatile Context context;
    private volatile String recordDir;
    private volatile Integer audioSource;
    private volatile Handler handler;
    private volatile OnSTRecorderListener listener;
    private volatile String mp3Path;
    private volatile String lastPath;
    private volatile boolean mute;
    private Capture capture;
    private Wav.Writer writer;
    private AudioInput prepared;
    private CountDownLatch playDone;

    private STRecorder() {
    }

    /** Recordings without an explicit path go to this file (WAV data, see the class comment). */
    public void setMp3Path(String path) {
        this.mp3Path = path;
    }

    /** Receives SkEgnManager.CODE_RECORDER_* and CODE_PLAY_* messages. */
    public void setHandler(Handler handler) {
        this.handler = handler;
    }

    public void setOnSTRecorderListener(OnSTRecorderListener listener) {
        this.listener = listener;
    }

    public static STRecorder getInstance() {
        return getInstance(null, null, null, null);
    }

    public static STRecorder getInstance(Context context) {
        return getInstance(context, null, null, null);
    }

    /** @param recordDir directory for recordings started without a path */
    public static STRecorder getInstance(Context context, String recordDir) {
        return getInstance(context, recordDir, null, null);
    }

    /** @param sampleRate only 16000 is supported; other values are logged at WARN */
    public static STRecorder getInstance(Context context, String recordDir, Integer sampleRate) {
        return getInstance(context, recordDir, sampleRate, null);
    }

    /** @param audioSource {@code MediaRecorder.AudioSource} value, null for MIC */
    public static STRecorder getInstance(Context context, String recordDir, Integer sampleRate, Integer audioSource) {
        STRecorder r = instance;
        if (r == null) {
            synchronized (STRecorder.class) {
                r = instance;
                if (r == null) {
                    r = new STRecorder();
                    instance = r;
                }
            }
        }
        if (context != null) {
            Context app = context.getApplicationContext();
            r.context = app != null ? app : context;
        }
        if (recordDir != null) {
            r.recordDir = recordDir;
        }
        if (sampleRate != null && sampleRate != Wav.SAMPLE_RATE) {
            YLog.w("STRecorder records at 16000 Hz; sampleRate " + sampleRate + " is ignored");
        }
        if (audioSource != null) {
            r.audioSource = audioSource;
        }
        return r;
    }

    /** Releases the capture device and a microphone opened ahead of time. */
    protected boolean releaseRecorder() {
        Capture c;
        AudioInput p;
        synchronized (lock) {
            c = capture;
            capture = null;
            p = prepared;
            prepared = null;
        }
        if (c != null) {
            c.stop();
            c.await(1000);
        }
        if (p != null) {
            p.release();
        }
        closeWriter();
        isRecording = false;
        return true;
    }

    /** Opens the microphone ahead of the next {@link #start(String, Callback)}. */
    protected boolean activeRecorder() {
        synchronized (lock) {
            if (prepared != null || capture != null) {
                return true;
            }
        }
        try {
            AudioInput in = CompatConfig.audioInputFactory().open(context, audioSource);
            synchronized (lock) {
                if (prepared == null) {
                    prepared = in;
                    return true;
                }
            }
            in.release();
            return true;
        } catch (AudioInput.AudioInputException e) {
            YLog.w("STRecorder: microphone unavailable: " + e.getMessage());
            return false;
        }
    }

    protected void releasePlayer() {
        stopPlayer(false);
    }

    /** Stops everything and forgets the instance; getInstance creates a new one. */
    public void delete() {
        releaseRecorder();
        stopPlay();
        synchronized (STRecorder.class) {
            if (instance == this) {
                instance = null;
            }
        }
    }

    @Override
    public void finalize() {
        try {
            releaseRecorder();
        } catch (RuntimeException ignored) {
            // nothing to report from a finalizer
        }
    }

    /**
     * Starts recording into {@code path} (WAV data). A null path uses the mp3Path, then
     * {@code <recordDir or externalFilesDir/record>/<time>.wav}.
     */
    public void start(String path, final Callback callback) {
        synchronized (lock) {
            if (capture != null) {
                YLog.w("STRecorder.start ignored: already recording");
                send(SkEgnManager.CODE_RECORDER_ONSTARTRECORDFAIL, "recorder is busy");
                return;
            }
        }
        File file = target(path);
        final Wav.Writer w;
        try {
            w = new Wav.Writer(file, Wav.SAMPLE_RATE, Wav.CHANNELS);
        } catch (IOException e) {
            send(SkEgnManager.CODE_RECORDER_ONSTARTRECORDFAIL, "cannot create " + file + ": " + e.getMessage());
            return;
        }
        AudioInput in;
        synchronized (lock) {
            in = prepared;
            prepared = null;
        }
        Capture c;
        try {
            if (in == null) {
                in = CompatConfig.audioInputFactory().open(context, audioSource);
            }
            c = new Capture(in, new Capture.Sink() {
                @Override
                public void onPcm(byte[] data, int length) {
                    try {
                        w.write(data, 0, length);
                    } catch (IOException e) {
                        YLog.w("STRecorder: cannot write: " + e.getMessage());
                    }
                    if (callback != null) {
                        callback.run(data, length);
                    }
                }

                @Override
                public void onCaptureError(String reason) {
                    send(SkEgnManager.CODE_RECORDER_ONSTARTRECORDFAIL, reason);
                }
            }, "yugu-strecorder");
            c.setMute(mute);
            c.start();
        } catch (AudioInput.AudioInputException e) {
            if (in != null) {
                in.release();
            }
            closeQuietly(w);
            send(SkEgnManager.CODE_RECORDER_ONSTARTRECORDFAIL, "microphone unavailable: " + e.getMessage());
            return;
        }
        synchronized (lock) {
            capture = c;
            writer = w;
        }
        lastPath = file.getAbsolutePath();
        isRecording = true;
        mIsRecordPaused = false;
        mIsReStartRecord = false;
        send(SkEgnManager.CODE_RECORDER_START, lastPath);
        MainThread.post(new Runnable() {
            @Override
            public void run() {
                OnSTRecorderListener l = listener;
                if (l != null) {
                    l.onStart();
                }
            }
        });
    }

    private File target(String path) {
        if (path != null && !path.trim().isEmpty()) {
            return new File(path.trim());
        }
        if (mp3Path != null && !mp3Path.trim().isEmpty()) {
            return new File(mp3Path.trim());
        }
        File dir = recordDir != null ? new File(recordDir) : new File(AiUtil.externalFilesDir(context), "record");
        return new File(dir, System.currentTimeMillis() + ".wav");
    }

    /** Stops recording and completes the WAV file before returning. */
    public void stop() {
        Capture c;
        synchronized (lock) {
            c = capture;
            capture = null;
        }
        if (c != null) {
            c.stop();
            if (!c.await(2000)) {
                YLog.w("STRecorder: capture thread did not stop in time");
            }
        }
        closeWriter();
        boolean was = isRecording;
        isRecording = false;
        mIsRecordPaused = false;
        if (was) {
            send(SkEgnManager.CODE_RECORDER_END, lastPath);
        }
    }

    public void pause() {
        Capture c;
        synchronized (lock) {
            c = capture;
        }
        if (c != null && !mIsRecordPaused) {
            c.pause();
            mIsRecordPaused = true;
            mIsReStartRecord = false;
            send(SkEgnManager.CODE_RECORDER_PAUSE, null);
        }
    }

    public void restart() {
        Capture c;
        synchronized (lock) {
            c = capture;
        }
        if (c != null && mIsRecordPaused) {
            if (c.resume()) {
                mIsRecordPaused = false;
                mIsReStartRecord = true;
            } else {
                send(SkEgnManager.CODE_RECORDER_ONSTARTRECORDFAIL, "microphone could not be restarted");
            }
        }
    }

    /** Stops recording and deletes the file. */
    public void cancel() {
        boolean was = isRecording;
        stop();
        String p = lastPath;
        if (was && p != null) {
            //noinspection ResultOfMethodCallIgnored
            new File(p).delete();
        }
    }

    /** Plays the last recording; events go to {@code handler}. */
    public void playback(Handler handler) {
        playWithPath(lastPath, handler);
    }

    /** Plays a file in a {@link PlayBackTask}; events go to {@code handler}. */
    public void playWithPath(final String path, Handler handler) {
        if (handler != null) {
            this.handler = handler;
        }
        stopPlay();
        MainThread.run(new Runnable() {
            @Override
            public void run() {
                PlayBackTask t = new PlayBackTask();
                mPlayBackTask = t;
                t.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, path);
            }
        });
    }

    public void stopPlay() {
        PlayBackTask t = mPlayBackTask;
        mPlayBackTask = null;
        if (t != null) {
            t.cancel(false);
        }
        stopPlayer(true);
    }

    /** Mutes the captured audio (silence is recorded and delivered). */
    public void setbMute(boolean mute) {
        this.mute = mute;
        Capture c;
        synchronized (lock) {
            c = capture;
        }
        if (c != null) {
            c.setMute(mute);
        }
    }

    /** Plays {@code path} and returns when playback ends (immediately when called on the main thread). */
    public Void playBackFile(final String path) {
        if (MainThread.isMainThread()) {
            startPlayer(path, null);
            return null;
        }
        final CountDownLatch done = new CountDownLatch(1);
        MainThread.post(new Runnable() {
            @Override
            public void run() {
                startPlayer(path, done);
            }
        });
        try {
            done.await(30, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return null;
    }

    private void startPlayer(final String path, final CountDownLatch done) {
        synchronized (lock) {
            playDone = done;
        }
        player.play(path, new Player.Events() {
            @Override
            public void onStart() {
                send(SkEgnManager.CODE_PLAY_START, path);
            }

            @Override
            public void onFail(String reason) {
                send(SkEgnManager.CODE_PLAY_START_FAIL, reason);
                release(done);
            }

            @Override
            public void onEnd() {
                send(SkEgnManager.CODE_PLAY_END, path);
                release(done);
            }
        });
    }

    private void release(CountDownLatch done) {
        if (done != null) {
            done.countDown();
        }
        synchronized (lock) {
            if (playDone == done) {
                playDone = null;
            }
        }
    }

    private void stopPlayer(final boolean notify) {
        MainThread.run(new Runnable() {
            @Override
            public void run() {
                player.stop(notify);
                CountDownLatch d;
                synchronized (lock) {
                    d = playDone;
                    playDone = null;
                }
                if (d != null) {
                    d.countDown();
                }
            }
        });
    }

    private void closeWriter() {
        Wav.Writer w;
        synchronized (lock) {
            w = writer;
            writer = null;
        }
        closeQuietly(w);
    }

    private static void closeQuietly(Wav.Writer w) {
        if (w != null) {
            try {
                w.close();
            } catch (IOException e) {
                YLog.w("STRecorder: cannot finish file: " + e.getMessage());
            }
        }
    }

    private void send(int what, Object obj) {
        Handler h = handler;
        if (h != null) {
            Message m = h.obtainMessage(what, obj);
            h.sendMessage(m);
        }
    }
}
