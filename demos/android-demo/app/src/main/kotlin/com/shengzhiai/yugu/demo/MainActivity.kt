package com.shengzhiai.yugu.demo

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import com.shengzhiai.yugu.AudioInput
import com.shengzhiai.yugu.Auth
import com.shengzhiai.yugu.CoreType
import com.shengzhiai.yugu.EvalResult
import com.shengzhiai.yugu.EvaluateConfig
import com.shengzhiai.yugu.Language
import com.shengzhiai.yugu.LogLevel
import com.shengzhiai.yugu.SessionState
import com.shengzhiai.yugu.StreamListener
import com.shengzhiai.yugu.StreamSession
import com.shengzhiai.yugu.YuguCallback
import com.shengzhiai.yugu.YuguClient
import com.shengzhiai.yugu.YuguException
import com.shengzhiai.yugu.audio.Recorder

/**
 * 示例：录音后整段评测，载入示例音频评测，边录边实时评测。
 *
 * 生命周期：onCreate 创建录音器，首次评测时按界面上的密钥创建客户端，onDestroy 释放录音器，
 * 取消实时会话并关闭客户端。全部回调默认在主线程执行，可以直接更新界面。
 */
class MainActivity : Activity() {

    private lateinit var appKey: EditText
    private lateinit var secretKey: EditText
    private lateinit var refText: EditText
    private lateinit var btnRecord: Button
    private lateinit var btnStop: Button
    private lateinit var btnSample: Button
    private lateinit var btnStream: Button
    private lateinit var status: TextView
    private lateinit var resultView: TextView

    private lateinit var recorder: Recorder
    private var client: YuguClient? = null
    private var clientCredentials: Pair<String, String>? = null
    private var session: StreamSession? = null
    private var afterPermission: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        appKey = findViewById(R.id.appKey)
        secretKey = findViewById(R.id.secretKey)
        refText = findViewById(R.id.refText)
        btnRecord = findViewById(R.id.btnRecord)
        btnStop = findViewById(R.id.btnStop)
        btnSample = findViewById(R.id.btnSample)
        btnStream = findViewById(R.id.btnStream)
        status = findViewById(R.id.status)
        resultView = findViewById(R.id.result)
        appKey.setText(BuildConfig.YUGU_APP_KEY)
        secretKey.setText(BuildConfig.YUGU_SECRET_KEY)

        recorder = Recorder(this)
        recorder.setListener(object : Recorder.Listener {
            override fun onStateChanged(oldState: Recorder.State, newState: Recorder.State) {
                if (newState == Recorder.State.RECORDING) status.text = "状态：录音中"
            }

            override fun onError(error: YuguException) {
                showError(error)
            }
        })

        btnRecord.setOnClickListener { withMicrophone { startRecording() } }
        btnStop.setOnClickListener { stopAndEvaluate() }
        btnSample.setOnClickListener { evaluateSample() }
        btnStream.setOnClickListener {
            val s = session
            if (s != null && s.isActive()) stopStreaming(s) else withMicrophone { startStreaming() }
        }
    }

    override fun onDestroy() {
        recorder.release()
        session?.cancel()
        client?.close()
        super.onDestroy()
    }

    // ------------------------------------------------------------------------- client

    private fun client(): YuguClient? {
        val creds = appKey.text.toString().trim() to secretKey.text.toString().trim()
        if (creds.first.isEmpty() || creds.second.isEmpty()) {
            status.text = "状态：请先填写 appKey 与 secretKey"
            return null
        }
        if (client == null || clientCredentials != creds) {
            client?.close()
            client = YuguClient.builder()
                .baseUrl(BuildConfig.YUGU_BASE_URL)
                .wsBaseUrl(BuildConfig.YUGU_WS_BASE_URL)
                .auth(Auth.appKey(creds.first, creds.second))
                .logLevel(LogLevel.INFO)
                .build()
            clientCredentials = creds
        }
        return client
    }

    private fun config(): EvaluateConfig = EvaluateConfig(
        coreType = CoreType.SENTENCE,
        referenceText = refText.text.toString().ifBlank { getString(R.string.default_ref_text) },
        language = Language.ZH_CN,
        includeReport = true,
    )

    // ------------------------------------------------------------------------- whole evaluation

    private fun startRecording() {
        try {
            recorder.start()
        } catch (e: YuguException) {
            showError(e)
            return
        }
        btnRecord.isEnabled = false
        btnStop.isEnabled = true
    }

    private fun stopAndEvaluate() {
        recorder.stop()
        btnRecord.isEnabled = true
        btnStop.isEnabled = false
        val pcm = recorder.pcm()
        if (pcm.isEmpty()) {
            status.text = "状态：没有录到音频"
            return
        }
        evaluate(AudioInput.fromPcm(pcm))
    }

    private fun evaluateSample() {
        val audio = assets.open("sample_zh.wav").use { AudioInput.fromStream(it, "sample_zh.wav") }
        refText.setText(R.string.default_ref_text)
        evaluate(audio)
    }

    private fun evaluate(audio: AudioInput) {
        val c = client() ?: return
        status.text = "状态：评测中"
        c.evaluateAsync(config(), audio, object : YuguCallback<EvalResult> {
            override fun onSuccess(result: EvalResult) {
                status.text = "状态：完成"
                show(result)
            }

            override fun onFailure(error: YuguException) {
                showError(error)
            }
        })
    }

    // ------------------------------------------------------------------------- streaming

    private fun startStreaming() {
        val c = client() ?: return
        resultView.text = ""
        val s = c.streamEvaluate(config(), object : StreamListener {
            override fun onStateChanged(oldState: SessionState, newState: SessionState) {
                status.text = "状态：$newState"
            }

            override fun onReconnecting(attempt: Int, delayMs: Long, cause: YuguException) {
                status.text = "状态：网络中断，${delayMs} 毫秒后第 $attempt 次重连"
            }

            override fun onReconnected(attempt: Int, droppedBytes: Long) {
                status.text = "状态：第 $attempt 次重连成功"
            }

            override fun onResult(result: EvalResult) {
                show(result)
            }

            override fun onError(error: YuguException) {
                recorder.stop()
                showError(error)
            }

            override fun onClosed(code: Int, reason: String) {
                btnStream.setText(R.string.btn_stream_start)
                btnRecord.isEnabled = true
                btnSample.isEnabled = true
            }
        })
        session = s
        try {
            recorder.start(s)
        } catch (e: YuguException) {
            s.cancel()
            showError(e)
            return
        }
        btnStream.setText(R.string.btn_stream_stop)
        btnRecord.isEnabled = false
        btnSample.isEnabled = false
    }

    private fun stopStreaming(s: StreamSession) {
        recorder.stop()
        s.end()
    }

    // ------------------------------------------------------------------------- output

    private fun show(r: EvalResult) {
        val d = r.dimensions
        val sb = StringBuilder()
        sb.append("总分：").append(r.overall ?: "--").append('\n')
        sb.append("发音：").append(d.pronunciation ?: "--")
            .append("  流利：").append(d.fluency ?: "--")
            .append("  完整：").append(d.integrity ?: "--")
            .append("  声调：").append(d.tone ?: "--").append('\n')
        for (w in r.words.take(20)) sb.append(w.word).append(' ').append(w.overall ?: "--").append("  ")
        sb.append('\n')
        if (r.warnings.isNotEmpty()) sb.append("平台警告：").append(r.warnings.joinToString { "${it.code} ${it.message}" }).append('\n')
        if (r.localWarnings.isNotEmpty()) sb.append("本地预检：").append(r.localWarnings.joinToString { it.message }).append('\n')
        sb.append("recordId：").append(r.recordId).append("  重放：").append(r.replayed)
        resultView.text = sb.toString()
    }

    private fun showError(e: YuguException) {
        status.text = "状态：失败 ${e.category} ${e.code}"
        resultView.text = "${e.message}\n可重试：${e.retryable}  尝试次数：${e.attempts}\n幂等键：${e.idempotencyKey}"
    }

    // ------------------------------------------------------------------------- permission

    private fun withMicrophone(action: () -> Unit) {
        if (Build.VERSION.SDK_INT < 23 || checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            action()
            return
        }
        afterPermission = action
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_MIC) return
        val action = afterPermission
        afterPermission = null
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            action?.invoke()
        } else {
            status.text = "状态：没有麦克风权限"
        }
    }

    private companion object {
        const val REQUEST_MIC = 1001
    }
}
