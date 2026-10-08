// SPDX-License-Identifier: Apache-2.0
package com.shengzhiai.yugu.stcompat.demo;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.stkouyu.CoreType;
import com.stkouyu.SkEgnManager;
import com.stkouyu.listener.OnInitEngineListener;
import com.stkouyu.listener.OnRecorderListener;
import com.stkouyu.setting.EngineSetting;
import com.stkouyu.setting.RecordSetting;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Sample written exactly like an app for the Shengtong 17kouyu SDK: only com.stkouyu is used.
 * init, startRecord with RecordSetting and OnRecorderListener, stopRecord, onScore, recycle.
 */
public class MainActivity extends Activity {
    private static final int REQUEST_MIC = 1;

    private SkEgnManager manager;
    private EditText refText;
    private TextView status;
    private TextView result;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        refText = new EditText(this);
        refText.setText("今天天气很好");
        root.addView(refText);

        Button start = new Button(this);
        start.setText("开始录音");
        root.addView(start);
        Button stop = new Button(this);
        stop.setText("停止并评测");
        root.addView(stop);
        Button play = new Button(this);
        play.setText("回放");
        root.addView(play);

        status = new TextView(this);
        root.addView(status);
        result = new TextView(this);
        result.setMovementMethod(new ScrollingMovementMethod());
        root.addView(result, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        start.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startRecording();
            }
        });
        stop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                manager.stopRecord();
            }
        });
        play.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                manager.playback();
            }
        });

        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, REQUEST_MIC);
        }
        initEngine();
    }

    private void initEngine() {
        manager = SkEgnManager.getInstance(this);
        EngineSetting setting = EngineSetting.getInstance(this);
        if (!BuildConfig.SERVER.isEmpty()) {
            setting.setServerAddress(BuildConfig.SERVER);
        }
        setting.setVADEnabled(true);
        setting.setOnInitEngineListener(new OnInitEngineListener() {
            @Override
            public void onStartInitEngine() {
                status.setText("引擎初始化中");
            }

            @Override
            public void onInitEngineSuccess() {
                status.setText("引擎就绪 " + manager.getSDKVersion());
            }

            @Override
            public void onInitEngineFailed(String reason) {
                status.setText("初始化失败: " + reason);
            }
        });
        manager.initEngine(BuildConfig.APP_KEY, BuildConfig.SECRET_KEY, "compat-demo-user", setting);
    }

    private void startRecording() {
        RecordSetting setting = new RecordSetting(CoreType.CN_SENT_EVAL, refText.getText().toString());
        setting.setDuration(15000);
        setting.setNeedSoundIntensity(true);
        manager.startRecord(setting, new OnRecorderListener() {
            @Override
            public void onStart() {
                status.setText("录音中");
                result.setText("");
            }

            @Override
            public void onStartRecordFail(String reason) {
                status.setText("无法开始录音: " + reason);
            }

            @Override
            public void onPause() {
                status.setText("已暂停");
            }

            @Override
            public void onTick(long millisUntilFinished, double percentUntilFinished) {
                status.setText("录音中，剩余 " + millisUntilFinished / 1000 + " 秒");
            }

            @Override
            public void onRecordEnd() {
                status.setText("评测中");
            }

            @Override
            public void onRecording(int vadStatus, int soundIntensity) {
                status.setText("录音中，音量 " + soundIntensity + (vadStatus == 1 ? "，正在说话" : ""));
            }

            @Override
            public void onScore(String json) {
                showScore(json);
            }
        });
    }

    private void showScore(String json) {
        try {
            JSONObject o = new JSONObject(json);
            if (o.has("errId")) {
                status.setText("评测失败 errId " + o.getInt("errId") + ": " + o.optString("error"));
            } else {
                status.setText("总分 " + o.getJSONObject("result").optDouble("overall"));
            }
            result.setText(o.toString(2));
        } catch (JSONException e) {
            result.setText(json);
        }
    }

    @Override
    protected void onDestroy() {
        if (manager != null) {
            manager.clearActivityListener();
            manager.clearInitListener();
            manager.recycle();
        }
        super.onDestroy();
    }
}
