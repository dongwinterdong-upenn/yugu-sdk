package com.shengzhiai.yugu

import com.shengzhiai.yugu.audio.WavFormat
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * 整段评测的音频来源，支持字节数组，文件，输入流与裸 PCM。
 *
 * - [fromBytes]，[fromFile]，[fromStream]：WAV 或 MP3 等平台支持的格式，按原样上传。
 * - [fromPcm]：16 位小端裸 PCM，上传前 SDK 自动加 WAV 头。
 *
 * 输入流在 [fromStream] 调用时读到结尾，流由调用方关闭。单个音频最多读入 64 MB。
 */
public class AudioInput private constructor(
    private val loader: () -> ByteArray,
    /** 上传时 `audio` 段的文件名。 */
    public val fileName: String,
    private val declaredContentType: String?,
    internal val pcmSampleRate: Int,
    internal val pcmChannels: Int,
    internal val isRawPcm: Boolean,
) {
    @Volatile
    private var cached: ByteArray? = null

    /** 读出的原始字节，结果缓存，重试时复用。 */
    internal fun bytes(): ByteArray {
        cached?.let { return it }
        val b = loader()
        cached = b
        return b
    }

    /** 上传用字节：裸 PCM 加 WAV 头，其余原样。 */
    internal fun uploadBytes(): ByteArray =
        if (isRawPcm) WavFormat.fromPcm(bytes(), pcmSampleRate, pcmChannels) else bytes()

    /** 上传时 `audio` 段的 Content-Type。 */
    internal fun contentType(): String {
        declaredContentType?.let { return it }
        if (isRawPcm) return "audio/wav"
        val b = bytes()
        if (WavFormat.isWav(b)) return "audio/wav"
        return contentTypeForName(fileName)
    }

    public companion object {
        /** 单个音频读入内存的上限。 */
        public const val MAX_READ_BYTES: Int = 64 * 1024 * 1024

        /** 字节数组形式的音频，常见为 WAV 或 MP3。 */
        @JvmStatic
        @JvmOverloads
        public fun fromBytes(bytes: ByteArray, fileName: String = "audio.wav", contentType: String? = null): AudioInput {
            if (bytes.isEmpty()) throw invalidArgument("音频为空")
            return AudioInput({ bytes }, fileName, contentType, 0, 0, false)
        }

        /** 文件形式的音频，在评测调用时读取。 */
        @JvmStatic
        @JvmOverloads
        public fun fromFile(file: File, contentType: String? = null): AudioInput = AudioInput(
            {
                if (!file.isFile || !file.canRead()) throw invalidArgument("音频文件不存在或不可读：${file.path}")
                if (file.length() > MAX_READ_BYTES) throw invalidArgument("音频文件超过 64 MB：${file.path}")
                try {
                    file.readBytes().also { if (it.isEmpty()) throw invalidArgument("音频文件为空：${file.path}") }
                } catch (e: IOException) {
                    throw invalidArgument("读取音频文件失败：${file.path}", e)
                }
            },
            file.name.ifEmpty { "audio.wav" }, contentType, 0, 0, false,
        )

        /** 输入流形式的音频，调用时读到结尾，流由调用方关闭。 */
        @JvmStatic
        @JvmOverloads
        public fun fromStream(stream: InputStream, fileName: String = "audio.wav", contentType: String? = null): AudioInput {
            val bytes = readAll(stream)
            if (bytes.isEmpty()) throw invalidArgument("音频流为空")
            return AudioInput({ bytes }, fileName, contentType, 0, 0, false)
        }

        /** 16 位小端裸 PCM，默认 16 kHz 单声道，上传前加 WAV 头。 */
        @JvmStatic
        @JvmOverloads
        public fun fromPcm(pcm: ByteArray, sampleRate: Int = 16_000, channels: Int = 1): AudioInput {
            if (pcm.isEmpty()) throw invalidArgument("PCM 为空")
            if (sampleRate <= 0 || channels <= 0) throw invalidArgument("采样率与声道数必须为正数")
            return AudioInput({ pcm }, "audio.wav", "audio/wav", sampleRate, channels, true)
        }

        internal fun readAll(stream: InputStream): ByteArray {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            try {
                while (true) {
                    val n = stream.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > MAX_READ_BYTES) throw invalidArgument("音频流超过 64 MB")
                }
            } catch (e: IOException) {
                throw invalidArgument("读取音频流失败", e)
            }
            return out.toByteArray()
        }

        internal fun contentTypeForName(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
            "wav" -> "audio/wav"
            "mp3" -> "audio/mpeg"
            "m4a", "mp4", "aac" -> "audio/mp4"
            "ogg", "opus" -> "audio/ogg"
            "amr" -> "audio/amr"
            "flac" -> "audio/flac"
            else -> "application/octet-stream"
        }
    }
}

/** 看图说话的图片，仅 coreType 为 open 且 taskType 为 picture 时上传。 */
public class ImageInput @JvmOverloads constructor(
    internal val bytes: ByteArray,
    public val fileName: String = "image.jpg",
    public val contentType: String = "image/jpeg",
) {
    public companion object {
        /** 从文件读取图片。 */
        @JvmStatic
        @JvmOverloads
        public fun fromFile(file: File, contentType: String = "image/jpeg"): ImageInput {
            if (!file.isFile || !file.canRead()) throw invalidArgument("图片文件不存在或不可读：${file.path}")
            return try {
                ImageInput(file.readBytes(), file.name, contentType)
            } catch (e: IOException) {
                throw invalidArgument("读取图片失败：${file.path}", e)
            }
        }
    }
}
