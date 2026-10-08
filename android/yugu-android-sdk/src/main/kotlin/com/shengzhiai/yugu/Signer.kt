package com.shengzhiai.yugu

import java.nio.charset.StandardCharsets
import java.util.TreeMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 签名器，算法与平台 `SignatureUtil.signHmacSha256` 一致。
 *
 * 1. 取本次请求的业务参数，丢弃值为 null 或空串的项。
 * 2. 按参数名字典序升序排列，拼成 `k1=v1&k2=v2`，不做 URL 编码。
 * 3. 签名值为 `Base64(HMAC_SHA256(payload, secretKey))`，两者都按 UTF-8 编码。
 *
 * 各接口的业务参数口径见 README 的签名一节：原生整段评测签 `config` 段的原文，
 * 兼容接口签实际发送的文本表单字段，语音合成签请求体顶层非空标量，报告查询签空集，
 * 实时评测握手签除 `signature` 以外的全部 query 参数。
 */
public object Signer {

    private const val HMAC_SHA256 = "HmacSHA256"

    /** 按签名规则拼出待签名原文。 */
    @JvmStatic
    public fun buildPayload(params: Map<String, *>): String {
        val sorted = TreeMap<String, String>()
        for ((key, raw) in params) {
            if (key.isEmpty() || raw == null) continue
            val value = raw.toString()
            if (value.isEmpty()) continue
            sorted[key] = value
        }
        val sb = StringBuilder()
        for ((k, v) in sorted) {
            if (sb.isNotEmpty()) sb.append('&')
            sb.append(k).append('=').append(v)
        }
        return sb.toString()
    }

    /** 计算业务参数的签名值。 */
    @JvmStatic
    public fun sign(params: Map<String, *>, secretKey: String): String =
        hmacSha256Base64(buildPayload(params), secretKey)

    /** 对已拼好的原文计算 `Base64(HMAC_SHA256(payload, secretKey))`。 */
    @JvmStatic
    public fun hmacSha256Base64(payload: String, secretKey: String): String {
        val mac = Mac.getInstance(HMAC_SHA256)
        mac.init(SecretKeySpec(secretKey.toByteArray(StandardCharsets.UTF_8), HMAC_SHA256))
        return Base64Codec.encode(mac.doFinal(payload.toByteArray(StandardCharsets.UTF_8)))
    }
}

/**
 * Standard Base64 with padding. android.util.Base64 is unavailable in JVM unit tests and
 * java.util.Base64 needs API 26, the SDK supports API 21.
 */
internal object Base64Codec {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun encode(data: ByteArray): String {
        val sb = StringBuilder(((data.size + 2) / 3) * 4)
        var i = 0
        while (i + 2 < data.size) {
            val n = ((data[i].toInt() and 0xFF) shl 16) or
                ((data[i + 1].toInt() and 0xFF) shl 8) or
                (data[i + 2].toInt() and 0xFF)
            sb.append(ALPHABET[(n ushr 18) and 0x3F])
            sb.append(ALPHABET[(n ushr 12) and 0x3F])
            sb.append(ALPHABET[(n ushr 6) and 0x3F])
            sb.append(ALPHABET[n and 0x3F])
            i += 3
        }
        when (data.size - i) {
            1 -> {
                val n = (data[i].toInt() and 0xFF) shl 16
                sb.append(ALPHABET[(n ushr 18) and 0x3F])
                sb.append(ALPHABET[(n ushr 12) and 0x3F])
                sb.append("==")
            }
            2 -> {
                val n = ((data[i].toInt() and 0xFF) shl 16) or ((data[i + 1].toInt() and 0xFF) shl 8)
                sb.append(ALPHABET[(n ushr 18) and 0x3F])
                sb.append(ALPHABET[(n ushr 12) and 0x3F])
                sb.append(ALPHABET[(n ushr 6) and 0x3F])
                sb.append('=')
            }
        }
        return sb.toString()
    }
}
