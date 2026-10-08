package com.shengzhiai.yugu.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** WAV 工具：PCM 加 WAV 头，解析 WAV 头。 */
public object WavFormat {

    /** WAV 头信息，[dataOffset] 与 [dataLength] 为 `data` 块在文件里的位置与长度。 */
    public data class Info(
        val formatTag: Int,
        val channels: Int,
        val sampleRate: Int,
        val bitsPerSample: Int,
        val dataOffset: Int,
        val dataLength: Int,
    ) {
        /** 是否 16 位整型 PCM。 */
        val isPcm16: Boolean get() = (formatTag == 1 || formatTag == 0xFFFE) && bitsPerSample == 16

        /** 时长，单位毫秒。 */
        val durationMs: Long
            get() {
                val bytesPerSecond = sampleRate.toLong() * channels * (bitsPerSample / 8).coerceAtLeast(1)
                return if (bytesPerSecond <= 0) 0 else dataLength * 1000L / bytesPerSecond
            }
    }

    /** 是否以 `RIFF....WAVE` 开头。 */
    @JvmStatic
    public fun isWav(bytes: ByteArray): Boolean =
        bytes.size >= 12 && tag(bytes, 0) == "RIFF" && tag(bytes, 8) == "WAVE"

    /** 解析 WAV 头，逐块查找 `fmt ` 与 `data`，不是 WAV 或头损坏时返回 null。 */
    @JvmStatic
    public fun parse(bytes: ByteArray): Info? {
        if (!isWav(bytes)) return null
        var pos = 12
        var fmt: IntArray? = null
        while (pos + 8 <= bytes.size) {
            val id = tag(bytes, pos)
            val size = le32(bytes, pos + 4)
            val body = pos + 8
            if (id == "fmt ") {
                if (size < 16 || body + 16 > bytes.size) return null
                var formatTag = le16(bytes, body)
                if (formatTag == 0xFFFE && size >= 40 && body + 26 <= bytes.size) {
                    // WAVE_FORMAT_EXTENSIBLE: the sub format GUID starts with the real tag.
                    formatTag = if (le16(bytes, body + 24) == 1) 0xFFFE else le16(bytes, body + 24)
                }
                fmt = intArrayOf(formatTag, le16(bytes, body + 2), le32(bytes, body + 4).toInt(), le16(bytes, body + 14))
            } else if (id == "data") {
                val f = fmt ?: return null
                val available = bytes.size - body
                // Streaming writers leave the size as 0 or 0xFFFFFFFF; use what is present.
                val len = if (size <= 0L || size > available) available else size.toInt()
                return Info(f[0], f[1], f[2], f[3], body, len)
            }
            if (size < 0 || size > Int.MAX_VALUE) return null
            pos = body + size.toInt() + (size.toInt() and 1)
        }
        return null
    }

    /** 给 16 位小端 PCM 加 44 字节标准 WAV 头。 */
    @JvmStatic
    @JvmOverloads
    public fun fromPcm(pcm: ByteArray, sampleRate: Int = 16_000, channels: Int = 1): ByteArray {
        val bits = 16
        val byteRate = sampleRate * channels * bits / 8
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + pcm.size)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1)
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort((channels * bits / 8).toShort())
        header.putShort(bits.toShort())
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(pcm.size)
        val out = ByteArray(44 + pcm.size)
        System.arraycopy(header.array(), 0, out, 0, 44)
        System.arraycopy(pcm, 0, out, 44, pcm.size)
        return out
    }

    /** 取 WAV 里的 PCM 数据，不是 WAV 时返回 null。 */
    @JvmStatic
    public fun pcmData(wav: ByteArray): ByteArray? {
        val info = parse(wav) ?: return null
        return wav.copyOfRange(info.dataOffset, info.dataOffset + info.dataLength)
    }

    private fun tag(b: ByteArray, at: Int): String =
        if (at + 4 > b.size) "" else String(b, at, 4, Charsets.US_ASCII)

    private fun le16(b: ByteArray, at: Int): Int = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    private fun le32(b: ByteArray, at: Int): Long =
        ((b[at].toLong() and 0xFF) or ((b[at + 1].toLong() and 0xFF) shl 8) or
            ((b[at + 2].toLong() and 0xFF) shl 16) or ((b[at + 3].toLong() and 0xFF) shl 24))
}
