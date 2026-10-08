package com.shengzhiai.yugu.audio

import com.shengzhiai.yugu.LocalWarning
import com.shengzhiai.yugu.errors.ErrorTable
import java.util.Locale
import kotlin.math.log10
import kotlin.math.sqrt

/** Running peak and RMS over 16-bit little-endian PCM, fed chunk by chunk. */
internal class PcmStats {
    var samples: Long = 0
        private set
    var peak: Int = 0
        private set
    private var sumSquares = 0.0
    private var carry = -1 // low byte waiting for its high byte when a chunk has odd length

    fun add(b: ByteArray, off: Int = 0, len: Int = b.size) {
        var i = off
        val end = off + len
        if (carry >= 0 && i < end) {
            sample((carry or (b[i].toInt() shl 8)).toShort().toInt())
            carry = -1
            i++
        }
        while (i + 1 < end) {
            sample(((b[i].toInt() and 0xFF) or (b[i + 1].toInt() shl 8)).toShort().toInt())
            i += 2
        }
        if (i < end) carry = b[i].toInt() and 0xFF
    }

    private fun sample(v: Int) {
        val a = if (v < 0) -v else v
        if (a > peak) peak = a
        sumSquares += v.toDouble() * v
        samples++
    }

    val rms: Double get() = if (samples == 0L) 0.0 else sqrt(sumSquares / samples)

    /** RMS in dBFS relative to 32768, -inf for silence. */
    val dbfs: Double get() = if (rms <= 0.0) Double.NEGATIVE_INFINITY else 20.0 * log10(rms / 32768.0)
}

/**
 * Local audio precheck (DESIGN 2.9). Thresholds equal the platform silence guard, so audio the
 * SDK rejects as silent is audio the platform would have scored 0.
 */
internal object AudioPrecheck {
    /** Server limit for one REST upload. */
    const val MAX_UPLOAD_BYTES = 50L * 1024 * 1024

    /** Limit for the audio of one streaming round. */
    const val MAX_STREAM_BYTES = 10L * 1024 * 1024
    const val MIN_DURATION_MS = 1_000L
    const val MAX_DURATION_MS = 300_000L
    const val SILENT_PEAK = 200
    const val SILENT_RMS = 30.0
    const val LOW_VOLUME_DBFS = -45.0
    const val MIN_SAMPLE_RATE = 16_000

    const val TOO_SHORT = 90101
    const val TOO_LONG = 90102
    const val SILENT = 90103
    const val LOW_VOLUME = 90104
    const val FORMAT = 90105

    private fun fmt1(v: Double): String = String.format(Locale.ROOT, "%.1f", v)

    /** 90104 never blocks an upload, the other findings do in REJECT mode. */
    fun rejectable(code: Int): Boolean = code != LOW_VOLUME

    private fun warning(code: Int, detail: String): LocalWarning =
        LocalWarning(code, (ErrorTable.LOCAL[code]?.message ?: "audio $code") + "：" + detail)

    /** Checks a whole upload. [rawPcm] marks headerless PCM of the given format. */
    fun checkUpload(bytes: ByteArray, rawPcm: Boolean, sampleRate: Int, channels: Int): List<LocalWarning> {
        val out = ArrayList<LocalWarning>()
        if (bytes.size > MAX_UPLOAD_BYTES) out.add(warning(TOO_LONG, "大小 ${bytes.size} 字节，整段上传上限 50 MB"))
        if (rawPcm) {
            val stats = PcmStats().apply { add(bytes) }
            out.addAll(checkPcm(stats, bytes.size.toLong(), sampleRate, channels, sizeChecked = true))
            return out
        }
        if (!WavFormat.isWav(bytes)) return out // other containers: size check only
        val info = WavFormat.parse(bytes)
        if (info == null) {
            out.add(warning(FORMAT, "WAV 头无法解析"))
            return out
        }
        if (!info.isPcm16 || info.sampleRate < MIN_SAMPLE_RATE || info.channels <= 0) {
            out.add(warning(FORMAT, "格式 ${info.formatTag}，${info.bitsPerSample} 位，${info.sampleRate} Hz"))
            return out
        }
        val stats = PcmStats().apply { add(bytes, info.dataOffset, info.dataLength) }
        out.addAll(checkPcm(stats, info.dataLength.toLong(), info.sampleRate, info.channels, sizeChecked = true))
        return out
    }

    /** Checks accumulated 16-bit PCM, used for uploads and for a stream at end(). */
    fun checkPcm(stats: PcmStats, pcmBytes: Long, sampleRate: Int, channels: Int, sizeChecked: Boolean = false): List<LocalWarning> {
        val out = ArrayList<LocalWarning>()
        if (sampleRate < MIN_SAMPLE_RATE) {
            out.add(warning(FORMAT, "采样率 $sampleRate Hz"))
            return out
        }
        val bytesPerSecond = sampleRate.toLong() * channels * 2
        val durationMs = if (bytesPerSecond == 0L) 0 else pcmBytes * 1000 / bytesPerSecond
        if (!sizeChecked && pcmBytes > MAX_STREAM_BYTES) out.add(warning(TOO_LONG, "大小 $pcmBytes 字节，实时评测一轮上限 10 MB"))
        if (durationMs > MAX_DURATION_MS && out.none { it.code == TOO_LONG }) {
            out.add(warning(TOO_LONG, "时长 ${durationMs / 1000.0} 秒，上限 300 秒"))
        }
        if (durationMs < MIN_DURATION_MS) out.add(warning(TOO_SHORT, "时长 ${durationMs / 1000.0} 秒"))
        if (stats.samples > 0 || pcmBytes == 0L) {
            if (stats.peak < SILENT_PEAK && stats.rms < SILENT_RMS) {
                out.add(warning(SILENT, "峰值 ${stats.peak}，均方根 ${fmt1(stats.rms)}"))
            } else if (stats.dbfs < LOW_VOLUME_DBFS) {
                out.add(warning(LOW_VOLUME, "均方根电平 ${fmt1(stats.dbfs)} dBFS，低于 -45 dBFS"))
            }
        }
        return out
    }
}
