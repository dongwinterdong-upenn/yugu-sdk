package com.shengzhiai.yugu.internal

import java.math.BigDecimal
import java.math.BigInteger

/**
 * Small JSON reader and writer used by the SDK.
 *
 * The SDK signs the exact text it sends, so it serializes JSON itself instead of relying on
 * android's org.json (whose escaping and number formatting differ from the JVM artifact used in
 * unit tests). Parsed objects are [LinkedHashMap] (key order kept), arrays are [ArrayList],
 * integral numbers are [Long] (or [BigInteger] when out of range), other numbers [Double].
 */
internal object Json {

    class JsonException(message: String) : RuntimeException(message)

    private const val MAX_DEPTH = 512

    // ------------------------------------------------------------------------------- parse

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skipWs()
        val v = p.readValue(0)
        p.skipWs()
        if (p.pos != text.length) throw JsonException("trailing data at ${p.pos}")
        return v
    }

    /** Parses [text] and returns it when it is a JSON object, otherwise null. Never throws. */
    fun parseObjectOrNull(text: String?): Map<String, Any?>? {
        if (text.isNullOrBlank()) return null
        return try {
            @Suppress("UNCHECKED_CAST")
            parse(text) as? Map<String, Any?>
        } catch (e: JsonException) {
            null
        }
    }

    private class Parser(val s: String) {
        var pos = 0

        fun skipWs() {
            while (pos < s.length) {
                val c = s[pos]
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') pos++ else break
            }
        }

        fun fail(what: String): Nothing = throw JsonException("$what at $pos")

        fun readValue(depth: Int): Any? {
            if (depth > MAX_DEPTH) fail("nesting too deep")
            if (pos >= s.length) fail("unexpected end")
            return when (val c = s[pos]) {
                '{' -> readObject(depth)
                '[' -> readArray(depth)
                '"' -> readString()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c in '0'..'9') readNumber() else fail("unexpected '$c'")
            }
        }

        fun literal(word: String, value: Any?): Any? {
            if (!s.startsWith(word, pos)) fail("bad literal")
            pos += word.length
            return value
        }

        fun readObject(depth: Int): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            pos++ // {
            skipWs()
            if (pos < s.length && s[pos] == '}') {
                pos++
                return out
            }
            while (true) {
                skipWs()
                if (pos >= s.length || s[pos] != '"') fail("expected key")
                val key = readString()
                skipWs()
                if (pos >= s.length || s[pos] != ':') fail("expected ':'")
                pos++
                skipWs()
                out[key] = readValue(depth + 1)
                skipWs()
                if (pos >= s.length) fail("unterminated object")
                when (s[pos]) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return out
                    }
                    else -> fail("expected ',' or '}'")
                }
            }
        }

        fun readArray(depth: Int): List<Any?> {
            val out = ArrayList<Any?>()
            pos++ // [
            skipWs()
            if (pos < s.length && s[pos] == ']') {
                pos++
                return out
            }
            while (true) {
                skipWs()
                out.add(readValue(depth + 1))
                skipWs()
                if (pos >= s.length) fail("unterminated array")
                when (s[pos]) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return out
                    }
                    else -> fail("expected ',' or ']'")
                }
            }
        }

        fun readString(): String {
            pos++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (pos >= s.length) fail("unterminated string")
                val c = s[pos++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (pos >= s.length) fail("bad escape")
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) fail("bad unicode escape")
                                val hex = s.substring(pos, pos + 4)
                                val code = hex.toIntOrNull(16) ?: fail("bad unicode escape")
                                sb.append(code.toChar())
                                pos += 4
                            }
                            else -> fail("bad escape '\\$e'")
                        }
                    }
                    c < ' ' -> fail("control character in string")
                    else -> sb.append(c)
                }
            }
        }

        fun readNumber(): Any {
            val start = pos
            if (s[pos] == '-') pos++
            if (pos >= s.length) fail("bad number")
            if (s[pos] == '0') {
                pos++
            } else if (s[pos] in '1'..'9') {
                while (pos < s.length && s[pos] in '0'..'9') pos++
            } else {
                fail("bad number")
            }
            var integral = true
            if (pos < s.length && s[pos] == '.') {
                integral = false
                pos++
                val d = pos
                while (pos < s.length && s[pos] in '0'..'9') pos++
                if (pos == d) fail("bad fraction")
            }
            if (pos < s.length && (s[pos] == 'e' || s[pos] == 'E')) {
                integral = false
                pos++
                if (pos < s.length && (s[pos] == '+' || s[pos] == '-')) pos++
                val d = pos
                while (pos < s.length && s[pos] in '0'..'9') pos++
                if (pos == d) fail("bad exponent")
            }
            val text = s.substring(start, pos)
            if (integral) {
                return text.toLongOrNull() ?: BigInteger(text)
            }
            return text.toDouble()
        }
    }

    // ------------------------------------------------------------------------------- write

    fun write(value: Any?): String {
        val sb = StringBuilder()
        writeTo(sb, value, 0)
        return sb.toString()
    }

    private fun writeTo(sb: StringBuilder, value: Any?, depth: Int) {
        if (depth > MAX_DEPTH) throw JsonException("nesting too deep")
        when (value) {
            null -> sb.append("null")
            is String -> quote(sb, value)
            is Boolean -> sb.append(if (value) "true" else "false")
            is Number -> sb.append(formatNumber(value))
            is Char -> quote(sb, value.toString())
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) sb.append(',')
                    first = false
                    quote(sb, k.toString())
                    sb.append(':')
                    writeTo(sb, v, depth + 1)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (v in value) {
                    if (!first) sb.append(',')
                    first = false
                    writeTo(sb, v, depth + 1)
                }
                sb.append(']')
            }
            is Array<*> -> writeTo(sb, value.asList(), depth)
            is IntArray -> writeTo(sb, value.asList(), depth)
            is LongArray -> writeTo(sb, value.asList(), depth)
            is DoubleArray -> writeTo(sb, value.asList(), depth)
            else -> quote(sb, value.toString())
        }
    }

    /**
     * Number text used both in JSON bodies and in signed values: integers as is, other numbers in
     * plain decimal form without exponent and without trailing zeros (`0.2`, `100`, `0.0000001`).
     */
    fun formatNumber(n: Number): String = when (n) {
        is Int, is Long, is Short, is Byte, is BigInteger -> n.toString()
        is BigDecimal -> plain(n)
        is Float -> {
            if (n.isNaN() || n.isInfinite()) throw JsonException("non-finite number")
            plain(BigDecimal(n.toString()))
        }
        else -> {
            val d = n.toDouble()
            if (d.isNaN() || d.isInfinite()) throw JsonException("non-finite number")
            plain(BigDecimal(d.toString()))
        }
    }

    private fun plain(b: BigDecimal): String {
        if (b.signum() == 0) return "0"
        val s = b.stripTrailingZeros().toPlainString()
        return s
    }

    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                ' ' -> sb.append("\\u2028")
                ' ' -> sb.append("\\u2029")
                else -> if (c < ' ') {
                    sb.append("\\u")
                    val h = Integer.toHexString(c.code)
                    for (i in h.length until 4) sb.append('0')
                    sb.append(h)
                } else {
                    sb.append(c)
                }
            }
        }
        sb.append('"')
    }
}

/** Typed read access to a parsed JSON object. Lenient: wrong types read as null. */
internal class JObj(val map: Map<String, Any?>) {

    fun has(key: String): Boolean = map.containsKey(key)

    fun raw(key: String): Any? = map[key]

    fun str(key: String): String? = when (val v = map[key]) {
        null -> null
        is String -> v
        is Number -> Json.formatNumber(v)
        is Boolean -> v.toString()
        else -> null
    }

    fun double(key: String): Double? = when (val v = map[key]) {
        is Number -> v.toDouble()
        is String -> v.trim().toDoubleOrNull()
        else -> null
    }

    fun long(key: String): Long? = when (val v = map[key]) {
        is Long -> v
        is Int -> v.toLong()
        is Number -> {
            val d = v.toDouble()
            if (d == Math.floor(d) && d >= -9.2e18 && d <= 9.2e18) d.toLong() else null
        }
        is String -> v.trim().toLongOrNull()
        else -> null
    }

    fun int(key: String): Int? = long(key)?.let { if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else null }

    fun bool(key: String): Boolean? = when (val v = map[key]) {
        is Boolean -> v
        is Number -> v.toDouble() != 0.0
        is String -> when (v.trim().lowercase()) {
            "true", "1" -> true
            "false", "0" -> false
            else -> null
        }
        else -> null
    }

    @Suppress("UNCHECKED_CAST")
    fun obj(key: String): JObj? = (map[key] as? Map<String, Any?>)?.let { JObj(it) }

    @Suppress("UNCHECKED_CAST")
    fun rawObj(key: String): Map<String, Any?>? = map[key] as? Map<String, Any?>

    fun list(key: String): List<Any?>? = map[key] as? List<Any?>

    @Suppress("UNCHECKED_CAST")
    fun objList(key: String): List<JObj> =
        (map[key] as? List<Any?>)?.mapNotNull { (it as? Map<String, Any?>)?.let { m -> JObj(m) } } ?: emptyList()

    companion object {
        fun parseOrNull(text: String?): JObj? = Json.parseObjectOrNull(text)?.let { JObj(it) }
    }
}
