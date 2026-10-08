package com.shengzhiai.yugu

import com.shengzhiai.yugu.internal.JObj
import com.shengzhiai.yugu.internal.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every case of spec/fixtures/sign/vectors.json must match (DESIGN 5.2). */
class SignerTest {

    private val cases: List<JObj> = JObj.parseOrNull(TestEnv.fixtureText("sign/vectors.json"))!!.objList("cases")

    @Test
    fun allSharedVectorsMatch() {
        assertTrue("vectors.json has cases", cases.size >= 7)
        for (c in cases) {
            val name = c.str("name")
            val secret = c.str("secret")!!
            val params: Map<String, Any?> = c.rawObj("params")!!
            assertEquals("payload of $name", c.str("payload"), Signer.buildPayload(params))
            assertEquals("signature of $name", c.str("signature"), Signer.sign(params, secret))
            assertEquals("hmac of $name", c.str("signature"), Signer.hmacSha256Base64(c.str("payload")!!, secret))
        }
    }

    @Test
    fun contractVectorIsTheV1Value() {
        val sig = Signer.sign(mapOf("coreType" to "sent.eval.cn", "language" to "zh-CN", "refText" to "北京你好"), "test_secret_key_123")
        assertEquals("A+6uVB/D7khxQEt8tzgCNjMUC1QtQQd1UF+NCYVYZqE=", sig)
    }

    @Test
    fun nativeConfigVectorIsTheWholeConfigText() {
        // The v1 Android bug signed 10 flattened config fields; v2 signs {config: exact text}.
        val c = cases.first { it.str("name") == "native_config_part" }
        val configText = c.rawObj("params")!!["config"] as String
        val reparsed = Json.write(Json.parse(configText))
        assertEquals("our writer reproduces the vector text", configText, reparsed)
        assertEquals(c.str("signature"), Signer.sign(mapOf("config" to reparsed), "test_secret_key_123"))
    }

    @Test
    fun nullEmptyAndOrderAreHandled() {
        val params = linkedMapOf<String, Any?>("z" to "1", "a" to "", "m" to null, "b" to 2, "" to "x")
        assertEquals("b=2&z=1", Signer.buildPayload(params))
    }

    @Test
    fun base64CoversAllPaddings() {
        assertEquals("", Base64Codec.encode(ByteArray(0)))
        assertEquals("Zg==", Base64Codec.encode("f".toByteArray()))
        assertEquals("Zm8=", Base64Codec.encode("fo".toByteArray()))
        assertEquals("Zm9v", Base64Codec.encode("foo".toByteArray()))
        assertEquals("Zm9vYmE=", Base64Codec.encode("fooba".toByteArray()))
        assertEquals("/+8=", Base64Codec.encode(byteArrayOf(0xFF.toByte(), 0xEF.toByte())))
    }
}
