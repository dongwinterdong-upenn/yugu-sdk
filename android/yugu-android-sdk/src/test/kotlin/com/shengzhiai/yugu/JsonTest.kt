package com.shengzhiai.yugu

import com.shengzhiai.yugu.internal.JObj
import com.shengzhiai.yugu.internal.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.math.BigDecimal
import java.math.BigInteger

class JsonTest {

    @Test
    fun parsesAllValueKinds() {
        val v = Json.parse("""{"s":"a\"b\\c\/d\n\t\u4e2d","i":-12,"d":1.5e2,"f":0.25,"t":true,"n":null,"a":[1,[],{}],"o":{"x":false}}""")
        @Suppress("UNCHECKED_CAST")
        val o = JObj(v as Map<String, Any?>)
        assertEquals("a\"b\\c/d\n\t中", o.str("s"))
        assertEquals(-12L, o.raw("i"))
        assertEquals(150.0, o.double("d")!!, 0.0)
        assertEquals(0.25, o.double("f")!!, 0.0)
        assertEquals(true, o.bool("t"))
        assertTrue(o.has("n"))
        assertNull(o.raw("n"))
        assertEquals(3, o.list("a")!!.size)
        assertEquals(false, o.obj("o")!!.bool("x"))
    }

    @Test
    fun bigIntegersSurvive() {
        assertEquals(BigInteger("123456789012345678901234567890"), Json.parse("123456789012345678901234567890"))
    }

    @Test
    fun surrogatePairsRoundTrip() {
        val s = Json.parse("\"\\ud83d\\ude00\"") as String
        assertEquals("😀", s)
        assertEquals("\"😀\"", Json.write(s))
    }

    @Test
    fun rejectsMalformedInput() {
        for (bad in listOf("", "{", "[1,", "{\"a\" 1}", "{\"a\":1,}", "tru", "01", "1.", "1e", "\"abc", "\"\\x\"", "\"\\u12\"", "{} x", "[1 2]", "-", "\"a\u0001\"")) {
            try {
                Json.parse(bad)
                fail("accepted: $bad")
            } catch (e: Json.JsonException) {
                // expected
            }
        }
        assertNull(Json.parseObjectOrNull("[1]"))
        assertNull(Json.parseObjectOrNull("not json"))
        assertNull(Json.parseObjectOrNull(null))
    }

    @Test
    fun deepNestingIsRefused() {
        val deep = "[".repeat(600) + "]".repeat(600)
        try {
            Json.parse(deep)
            fail("deep nesting accepted")
        } catch (e: Json.JsonException) {
            assertTrue(e.message!!.contains("deep"))
        }
    }

    @Test
    fun writesNumbersInPlainDecimal() {
        assertEquals("0.2", Json.formatNumber(0.2))
        assertEquals("100", Json.formatNumber(100.0))
        assertEquals("1", Json.formatNumber(1.0))
        assertEquals("0.0000001", Json.formatNumber(1e-7))
        assertEquals("12345678901234567000", Json.formatNumber(1.2345678901234567e19))
        assertEquals("0", Json.formatNumber(-0.0))
        assertEquals("0.1", Json.formatNumber(0.1f))
        assertEquals("42", Json.formatNumber(42))
        assertEquals("-7", Json.formatNumber(-7L))
        assertEquals("3.5", Json.formatNumber(BigDecimal("3.50")))
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY)) {
            try {
                Json.formatNumber(bad)
                fail("accepted $bad")
            } catch (e: Json.JsonException) {
                // expected
            }
        }
    }

    @Test
    fun writesEscapesAndContainers() {
        val m = linkedMapOf<String, Any?>(
            "s" to "q\"b\\n\n\r\t\b\u000C\u0001/中文\u2028",
            "n" to null,
            "b" to false,
            "l" to listOf(1, 2.5, "x"),
            "arr" to arrayOf("y"),
            "ia" to intArrayOf(3),
            "c" to 'z',
            "o" to object { override fun toString() = "custom" },
        )
        val text = Json.write(m)
        assertEquals(
            "{\"s\":\"q\\\"b\\\\n\\n\\r\\t\\b\\f\\u0001/中文\\u2028\",\"n\":null,\"b\":false,\"l\":[1,2.5,\"x\"],\"arr\":[\"y\"],\"ia\":[3],\"c\":\"z\",\"o\":\"custom\"}",
            text,
        )
        @Suppress("UNCHECKED_CAST")
        val back = Json.parse(text) as Map<String, Any?>
        assertEquals(m["s"], back["s"])
    }

    @Test
    fun lenientTypedAccess() {
        val o = JObj.parseOrNull("""{"n":"12","d":"1.5","b":"true","b0":0,"x":[1],"big":1e30,"neg":"no"}""")!!
        assertEquals(12, o.int("n"))
        assertEquals(12L, o.long("n"))
        assertEquals(1.5, o.double("d")!!, 0.0)
        assertEquals(true, o.bool("b"))
        assertEquals(false, o.bool("b0"))
        assertNull(o.bool("neg"))
        assertNull(o.obj("x"))
        assertNull(o.str("x"))
        assertNull(o.int("big"))
        assertNull(o.int("missing"))
        assertFalse(o.has("missing"))
        assertTrue(o.objList("x").isEmpty())
        assertEquals("12", o.str("n"))
    }
}
