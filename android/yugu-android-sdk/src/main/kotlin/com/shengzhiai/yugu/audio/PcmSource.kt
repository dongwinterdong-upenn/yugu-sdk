package com.shengzhiai.yugu.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.shengzhiai.yugu.YuguErrors

/** Microphone abstraction so the recorder state machine is testable without hardware. */
internal interface PcmSource {
    /** Acquires and starts the microphone. Throws YuguException 90201 or 90202. */
    fun start()

    /** Blocking read; returns bytes read, 0 when stopped, a negative AudioRecord error code on failure. */
    fun read(buffer: ByteArray, offset: Int, length: Int): Int

    /** Stops capturing, keeps the instance (pause). */
    fun stop()

    /** Releases the microphone. Idempotent. */
    fun release()
}

/** AudioRecord 16 kHz mono PCM16 source. */
internal class AudioRecordSource(
    private val sampleRate: Int,
    private val frameBytes: Int,
    private val audioSource: Int = MediaRecorder.AudioSource.MIC,
) : PcmSource {

    @Volatile
    private var record: AudioRecord? = null

    @Synchronized
    @SuppressLint("MissingPermission")
    override fun start() {
        val rec = record ?: create().also { record = it }
        try {
            rec.startRecording()
        } catch (e: IllegalStateException) {
            release()
            throw YuguErrors.local(90202, "麦克风无法开始录音：${e.message}", e)
        } catch (e: SecurityException) {
            release()
            throw YuguErrors.local(90201, cause = e)
        }
        if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            release()
            throw YuguErrors.local(90202, "麦克风被占用，未进入录音状态")
        }
    }

    @SuppressLint("MissingPermission")
    private fun create(): AudioRecord {
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) throw YuguErrors.local(90202, "设备不支持 $sampleRate Hz 单声道 16 位录音，getMinBufferSize=$minBuf")
        val rec = try {
            AudioRecord(audioSource, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, frameBytes * 8))
        } catch (e: SecurityException) {
            throw YuguErrors.local(90201, cause = e)
        } catch (e: IllegalArgumentException) {
            throw YuguErrors.local(90202, "AudioRecord 参数不被支持：${e.message}", e)
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw YuguErrors.local(90202, "AudioRecord 初始化失败，麦克风可能被占用或没有权限")
        }
        return rec
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        record?.read(buffer, offset, length) ?: AudioRecord.ERROR_INVALID_OPERATION

    @Synchronized
    override fun stop() {
        try {
            record?.stop()
        } catch (_: IllegalStateException) {
            // not recording
        }
    }

    @Synchronized
    override fun release() {
        val rec = record ?: return
        record = null
        try {
            rec.stop()
        } catch (_: IllegalStateException) {
            // not recording
        }
        rec.release()
    }
}
