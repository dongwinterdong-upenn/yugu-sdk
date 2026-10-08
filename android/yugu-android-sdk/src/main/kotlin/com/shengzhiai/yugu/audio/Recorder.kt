package com.shengzhiai.yugu.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import com.shengzhiai.yugu.StreamSession
import com.shengzhiai.yugu.YuguErrors
import com.shengzhiai.yugu.YuguException
import com.shengzhiai.yugu.internal.Platform
import com.shengzhiai.yugu.internal.SerialExecutor
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 录音器配置。默认 16 kHz，每帧 640 字节即 20 毫秒，最长 300 秒，保留录到的 PCM。
 */
public data class RecorderConfig @JvmOverloads constructor(
    val sampleRate: Int = 16_000,
    val frameBytes: Int = 640,
    val maxDurationMs: Long = 300_000,
    val keepPcm: Boolean = true,
) {
    init {
        require(sampleRate >= 8_000) { "sampleRate 过低" }
        require(frameBytes > 0 && frameBytes % 2 == 0) { "frameBytes 须为正偶数" }
        require(maxDurationMs > 0) { "maxDurationMs 须为正数" }
    }
}

/**
 * 录音器：AudioRecord 采集单声道 16 位 PCM。
 *
 * 状态为 IDLE，RECORDING，PAUSED，STOPPED，RELEASED。[stop]，[release] 与每条出错路径都会释放麦克风。
 * [release] 可重复调用，释放后同时注销监听器。
 *
 * 线程：[Listener.onFrame] 与 [Listener.onLevel] 在采集线程上回调，应尽快返回。
 * [Listener.onStateChanged] 与 [Listener.onError] 在回调线程上依次执行，默认主线程。
 *
 * 宿主应用须声明并在运行时申请 `RECORD_AUDIO`。传入 [Context] 时 SDK 在开始前检查该权限，
 * 没有权限抛 [com.shengzhiai.yugu.PermissionException]，错误码 90201。
 */
public class Recorder internal constructor(
    private val sourceFactory: () -> PcmSource,
    public val config: RecorderConfig,
    private val context: Context?,
    callbackExecutor: Executor?,
) : Closeable {

    @JvmOverloads
    public constructor(
        context: Context? = null,
        config: RecorderConfig = RecorderConfig(),
        callbackExecutor: Executor? = null,
    ) : this({ AudioRecordSource(config.sampleRate, config.frameBytes) }, config, context?.applicationContext, callbackExecutor)

    /** 录音器状态。 */
    public enum class State { IDLE, RECORDING, PAUSED, STOPPED, RELEASED }

    /** 录音回调，全部可选。 */
    public interface Listener {
        public fun onStateChanged(oldState: State, newState: State) {}

        /** 一帧 PCM，默认 640 字节，在采集线程上回调。 */
        public fun onFrame(pcm: ByteArray) {}

        /** 音量等级 0 到 100，每帧一次，在采集线程上回调。 */
        public fun onLevel(level: Int) {}

        /** 录音出错，麦克风已释放，状态已回到 STOPPED。 */
        public fun onError(error: YuguException) {}
    }

    private val lock = ReentrantLock()
    private val resumed = lock.newCondition()
    private val dispatcher = SerialExecutor(callbackExecutor ?: Platform.defaultCallbackExecutor())

    @Volatile
    private var listener: Listener? = null

    // ---- guarded by lock
    private var state = State.IDLE
    private var source: PcmSource? = null
    private var thread: Thread? = null
    private var session: StreamSession? = null
    private var generation = 0
    private var paused = false
    private val pcm = ByteArrayOutputStream()
    private var capturedBytes = 0L

    /** 设置监听器，传 null 即注销。 */
    public fun setListener(listener: Listener?) {
        this.listener = listener
    }

    /** 注销监听器。 */
    public fun removeListener() {
        listener = null
    }

    /** 当前状态。 */
    public fun getState(): State = lock.withLock { state }

    /** 开始录音。从 IDLE 或 STOPPED 开始，STOPPED 时清空上一次录到的音频。 */
    public fun start() {
        startInternal(null)
    }

    /** 开始录音，同时把每一帧送进实时评测会话。录音停止不会自动结束会话。 */
    public fun start(session: StreamSession) {
        startInternal(session)
    }

    private fun startInternal(target: StreamSession?) {
        lock.withLock {
            when (state) {
                State.RELEASED -> throw YuguErrors.local(90009, "录音器已释放")
                State.RECORDING, State.PAUSED -> throw YuguErrors.local(90009, "录音器正在录音")
                else -> Unit
            }
            checkPermission()
            val src = sourceFactory()
            try {
                src.start()
            } catch (e: YuguException) {
                src.release()
                throw e
            } catch (e: RuntimeException) {
                src.release()
                throw YuguErrors.local(90202, "麦克风不可用：${e.message}", e)
            }
            source = src
            session = target
            pcm.reset()
            capturedBytes = 0
            paused = false
            val gen = ++generation
            val t = Thread({ capture(gen, src) }, "yugu-recorder")
            t.isDaemon = true
            thread = t
            transitionLocked(State.RECORDING)
            t.start()
        }
    }

    private fun checkPermission() {
        val ctx = context ?: return
        val granted = try {
            ctx.checkPermission(Manifest.permission.RECORD_AUDIO, Process.myPid(), Process.myUid()) == PackageManager.PERMISSION_GRANTED
        } catch (e: RuntimeException) {
            true // cannot tell, let AudioRecord decide
        }
        if (!granted) throw YuguErrors.local(90201, "没有麦克风权限 RECORD_AUDIO")
    }

    /** 暂停，麦克风停止采集但不释放。 */
    public fun pause() {
        val src: PcmSource
        lock.withLock {
            if (state != State.RECORDING) return
            src = source ?: return
            paused = true
            transitionLocked(State.PAUSED)
        }
        src.stop()
    }

    /** 从暂停处继续录音。 */
    public fun resume() {
        lock.withLock {
            if (state != State.PAUSED) return
            val src = source ?: return
            try {
                src.start()
            } catch (e: YuguException) {
                failLocked(e)
                throw e
            }
            paused = false
            resumed.signalAll()
            transitionLocked(State.RECORDING)
        }
    }

    /** 停止录音并释放麦克风，可重复调用。录到的音频用 [pcm] 或 [toWav] 读取。 */
    public fun stop() {
        val src: PcmSource?
        val t: Thread?
        lock.withLock {
            if (state != State.RECORDING && state != State.PAUSED) return
            generation++
            paused = false
            resumed.signalAll()
            src = source
            t = thread
            source = null
            thread = null
            session = null
            transitionLocked(State.STOPPED)
        }
        src?.stop()
        if (t != null && t !== Thread.currentThread()) {
            try {
                t.join(JOIN_TIMEOUT_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        if (t == null || !t.isAlive) src?.release() // otherwise the capture thread releases on exit
    }

    /** 释放录音器与麦克风并注销监听器，可重复调用。 */
    public fun release() {
        stop()
        lock.withLock {
            if (state == State.RELEASED) return
            transitionLocked(State.RELEASED)
        }
        listener = null
    }

    /** 等同 [release]。 */
    override fun close() {
        release()
    }

    /** 录到的 PCM，无文件头。 */
    public fun pcm(): ByteArray = lock.withLock { pcm.toByteArray() }

    /** 录到的音频加 WAV 头。 */
    public fun toWav(): ByteArray = WavFormat.fromPcm(pcm(), config.sampleRate, 1)

    /** 把录到的音频写成 WAV 文件。 */
    public fun writeWav(file: File): File {
        file.writeBytes(toWav())
        return file
    }

    /** 已录时长，单位毫秒。 */
    public fun durationMs(): Long = lock.withLock { capturedBytes * 1000L / (config.sampleRate * 2L) }

    // ------------------------------------------------------------------------- capture thread

    private fun capture(gen: Int, src: PcmSource) {
        val frameBytes = config.frameBytes
        val maxBytes = config.maxDurationMs * config.sampleRate * 2L / 1000L
        val buf = ByteArray(frameBytes)
        try {
            while (true) {
                lock.withLock {
                    while (gen == generation && paused) resumed.await(200, TimeUnit.MILLISECONDS)
                    if (gen != generation) return
                }
                var filled = 0
                var error = 0
                while (filled < frameBytes) {
                    val n = src.read(buf, filled, frameBytes - filled)
                    if (n < 0) {
                        error = n
                        break
                    }
                    if (n == 0) break // stopped or paused
                    filled += n
                }
                if (error < 0) {
                    lock.withLock {
                        if (gen != generation) return
                        failLocked(YuguErrors.local(90203, "录音读取失败，AudioRecord 错误码 $error"))
                    }
                    return
                }
                if (filled == 0) {
                    lock.withLock {
                        if (gen == generation && !paused) {
                            // read returned nothing while recording, back off briefly
                            resumed.await(10, TimeUnit.MILLISECONDS)
                        }
                    }
                    continue
                }
                val frame = if (filled == frameBytes) buf.copyOf() else buf.copyOf(filled)
                val target: StreamSession?
                var reachedMax = false
                lock.withLock {
                    if (gen != generation) return
                    if (config.keepPcm) pcm.write(frame, 0, frame.size)
                    capturedBytes += frame.size
                    target = session
                    if (capturedBytes >= maxBytes) reachedMax = true
                }
                val l = listener
                if (l != null) {
                    try {
                        l.onFrame(frame)
                        l.onLevel(level(frame))
                    } catch (t: Throwable) {
                        // a listener bug must not stop the microphone loop
                    }
                }
                if (target != null && !target.sendAudio(frame)) {
                    lock.withLock { if (session === target) session = null }
                }
                if (reachedMax) {
                    lock.withLock {
                        if (gen != generation) return
                        generation++
                        source = null
                        thread = null
                        session = null
                        transitionLocked(State.STOPPED)
                    }
                    return
                }
            }
        } catch (t: Throwable) {
            lock.withLock {
                if (gen == generation) failLocked(YuguErrors.local(90203, "录音线程异常：$t", t))
            }
        } finally {
            src.release()
        }
    }

    /** Error path: back to STOPPED, microphone released by the capture thread or here. */
    private fun failLocked(error: YuguException) {
        val src = source
        generation++
        paused = false
        resumed.signalAll()
        source = null
        val t = thread
        thread = null
        session = null
        if (state == State.RECORDING || state == State.PAUSED) transitionLocked(State.STOPPED)
        if (t == null || t === Thread.currentThread() || !t.isAlive) src?.release() else src?.stop()
        val l = listener
        dispatcher.execute {
            try {
                l?.onError(error)
            } catch (_: Throwable) {
                // ignore listener failures
            }
        }
    }

    private fun transitionLocked(next: State) {
        val old = state
        if (old == next) return
        state = next
        val l = listener
        dispatcher.execute {
            try {
                l?.onStateChanged(old, next)
            } catch (_: Throwable) {
                // ignore listener failures
            }
        }
    }

    internal companion object {
        private const val JOIN_TIMEOUT_MS = 2_000L

        /** Level 0..100 from RMS, -60 dBFS and below is 0. */
        fun level(frame: ByteArray): Int {
            var sum = 0.0
            var n = 0
            var i = 0
            while (i + 1 < frame.size) {
                val v = ((frame[i].toInt() and 0xFF) or (frame[i + 1].toInt() shl 8)).toShort().toDouble()
                sum += v * v
                n++
                i += 2
            }
            if (n == 0 || sum == 0.0) return 0
            val db = 20.0 * log10(sqrt(sum / n) / 32768.0)
            return ((db + 60.0) / 60.0 * 100.0).roundToInt().coerceIn(0, 100)
        }
    }
}
