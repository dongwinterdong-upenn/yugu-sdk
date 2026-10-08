package com.shengzhiai.yugu.stcompat.testing;

import com.stkouyu.listener.OnInitEngineListener;
import com.stkouyu.listener.OnPlayerListener;
import com.stkouyu.listener.OnRecordBufferListener;
import com.stkouyu.listener.OnRecordListener;
import com.stkouyu.listener.OnRecorderListener;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Listeners that record every callback and whether it ran on the main thread. */
public final class Events {
    public final List<String> log = Collections.synchronizedList(new ArrayList<String>());
    public final List<String> offMain = Collections.synchronizedList(new ArrayList<String>());
    public volatile String json;
    public volatile String startFail;
    public final List<long[]> ticks = Collections.synchronizedList(new ArrayList<long[]>());
    public final List<double[]> tickPercents = Collections.synchronizedList(new ArrayList<double[]>());
    public final List<int[]> recording = Collections.synchronizedList(new ArrayList<int[]>());
    public volatile int bufferBytes;

    private void add(String e) {
        if (!Main.onMain()) {
            offMain.add(e);
        }
        log.add(e);
    }

    public boolean has(String event) {
        synchronized (log) {
            return log.contains(event);
        }
    }

    public int count(String event) {
        synchronized (log) {
            int n = 0;
            for (String s : log) {
                if (s.equals(event)) {
                    n++;
                }
            }
            return n;
        }
    }

    public JSONObject result() {
        try {
            return json == null ? null : new JSONObject(json);
        } catch (JSONException e) {
            throw new AssertionError("callback JSON is invalid: " + json, e);
        }
    }

    public final OnRecorderListener recorder = new OnRecorderListener() {
        @Override
        public void onStart() {
            add("onStart");
        }

        @Override
        public void onStartRecordFail(String reason) {
            startFail = reason;
            add("onStartRecordFail");
        }

        @Override
        public void onPause() {
            add("onPause");
        }

        @Override
        public void onTick(long millisUntilFinished, double percentUntilFinished) {
            ticks.add(new long[] {millisUntilFinished});
            tickPercents.add(new double[] {percentUntilFinished});
            add("onTick");
        }

        @Override
        public void onRecordEnd() {
            add("onRecordEnd");
        }

        @Override
        public void onRecording(int vadStatus, int soundIntensity) {
            recording.add(new int[] {vadStatus, soundIntensity});
            add("onRecording");
        }

        @Override
        public void onScore(String result) {
            json = result;
            add("onScore");
        }
    };

    public final OnRecordListener record = new OnRecordListener() {
        @Override
        public void onRecordStart() {
            add("onRecordStart");
        }

        @Override
        public void onRecording(int vadStatus, int soundIntensity) {
            recording.add(new int[] {vadStatus, soundIntensity});
            add("onRecording");
        }

        @Override
        public void onRecordEnd(String result) {
            json = result;
            add("onRecordEnd(json)");
        }
    };

    public final OnInitEngineListener init = new OnInitEngineListener() {
        @Override
        public void onStartInitEngine() {
            add("onStartInitEngine");
        }

        @Override
        public void onInitEngineSuccess() {
            add("onInitEngineSuccess");
        }

        @Override
        public void onInitEngineFailed(String reason) {
            startFail = reason;
            add("onInitEngineFailed");
        }
    };

    public final OnPlayerListener player = new OnPlayerListener() {
        @Override
        public void onPlayStart() {
            add("onPlayStart");
        }

        @Override
        public void onPlayStartFail(String reason) {
            startFail = reason;
            add("onPlayStartFail");
        }

        @Override
        public void onPlayEnd() {
            add("onPlayEnd");
        }
    };

    public final OnRecordBufferListener buffer = new OnRecordBufferListener() {
        @Override
        public void onRecordBuffer(byte[] data, int size) {
            bufferBytes += size;
            if (!Main.onMain()) {
                offMain.add("onRecordBuffer");
            }
        }
    };
}
