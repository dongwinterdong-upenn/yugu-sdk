package com.shengzhiai.yugu;

import com.fasterxml.jackson.databind.JsonNode;
import com.shengzhiai.yugu.internal.Auth;
import com.shengzhiai.yugu.internal.Json;
import com.shengzhiai.yugu.model.EvaluateConfig;
import com.shengzhiai.yugu.testing.Fixtures;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every case of spec/fixtures/sign/vectors.json must match. */
class SignerTest {

    static Map<String, String> params(JsonNode node) {
        Map<String, String> m = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> it = node.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            m.put(e.getKey(), e.getValue().isNull() ? null : e.getValue().asText());
        }
        return m;
    }

    @TestFactory
    List<DynamicTest> sharedVectors() {
        JsonNode cases = Fixtures.json("fixtures/sign/vectors.json").get("cases");
        assertTrue(cases.size() >= 7, "vectors present");
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode c : cases) {
            tests.add(DynamicTest.dynamicTest(c.get("name").asText(), () -> {
                Map<String, String> p = params(c.get("params"));
                assertEquals(c.get("payload").asText(), Signer.buildPayload(p), "payload");
                assertEquals(c.get("signature").asText(), Signer.sign(p, c.get("secret").asText()), "signature");
                assertEquals(c.get("signature").asText(), Signer.signPayload(c.get("payload").asText(), c.get("secret").asText()));
            }));
        }
        return tests;
    }

    @Test
    void contractVector() {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("refText", "北京你好");
        p.put("coreType", "sent.eval.cn");
        p.put("language", "zh-CN");
        assertEquals("A+6uVB/D7khxQEt8tzgCNjMUC1QtQQd1UF+NCYVYZqE=", Signer.sign(p, "test_secret_key_123"));
    }

    @Test
    void nullMapSignsEmptyPayload() {
        assertEquals("", Signer.buildPayload(null));
        assertEquals("q5Q0Pi4Q+j7PNBaI2X6mF6yMN8+9u202Riw462d2xjc=", Signer.sign(null, "test_secret_key_123"));
    }

    @Test
    void nativeConfigPartIsSignedAsExactText() {
        // the SDK signs {config: <exact JSON text>}; the vector fixes that text
        JsonNode c = Fixtures.json("fixtures/sign/vectors.json").get("cases").get(1);
        String configText = c.get("params").get("config").asText();
        Map<String, String> p = new LinkedHashMap<>();
        p.put("config", configText);
        assertEquals(c.get("signature").asText(), Signer.sign(p, "test_secret_key_123"));
        // and the SDK serialisation of the same config produces that very text
        EvaluateConfig cfg = new EvaluateConfig("sentence", "今天天气很好", "zh-CN").slack(0.2).scale(100).includeReport(false);
        String sdkJson = Json.write(cfg);
        assertEquals(configText, sdkJson);
        assertFalse(sdkJson.contains("extraParams"), sdkJson);
        assertEquals(c.get("signature").asText(), Signer.sign(Map.of("config", sdkJson), "test_secret_key_123"));
    }

    @Test
    void wsQuerySignsEveryParameterExceptSignature() {
        Auth auth = new Auth("ak_test", "test_secret_key_123", null);
        Map<String, String> business = new LinkedHashMap<>();
        business.put("idempotencyKey", "3f0b6a1c9d2e4f5a8b7c6d5e4f3a2b1c");
        Map<String, String> q = auth.wsQuery(business);
        String sig = q.remove("signature");
        assertEquals(Signer.sign(q, "test_secret_key_123"), sig);
        assertEquals("ak_test", q.get("appKey"));
        assertTrue(q.get("timestamp").matches("\\d{10}"));
        assertTrue(q.get("nonce").matches("[0-9a-f]{16}"));
        assertEquals("3f0b6a1c9d2e4f5a8b7c6d5e4f3a2b1c", q.get("idempotencyKey"));
    }

    @Test
    void tokenAuthUsesBearerAndTokenQuery() {
        Auth auth = new Auth(null, null, "jwt-1");
        assertTrue(auth.isToken());
        assertEquals("Bearer jwt-1", auth.restHeaders(Map.of()).get("Authorization"));
        assertNull(auth.restHeaders(Map.of()).get("X-Signature"));
        Map<String, String> q = auth.wsQuery(Map.of("idempotencyKey", "k"));
        assertEquals("jwt-1", q.get("token"));
        assertFalse(q.containsKey("signature"));
        assertEquals("?token=jwt-1&idempotencyKey=k", Auth.encodeQuery(q));
        assertEquals("", Auth.encodeQuery(Map.of()));
    }

    @Test
    void restHeadersSignBusinessParametersOnly() {
        Auth auth = new Auth("ak_test", "test_secret_key_123", null);
        Map<String, String> sign = Map.of("coreType", "sent.eval.cn", "language", "zh-CN", "refText", "北京你好");
        Map<String, String> h = auth.restHeaders(sign);
        assertEquals("ak_test", h.get("X-App-Key"));
        assertEquals("A+6uVB/D7khxQEt8tzgCNjMUC1QtQQd1UF+NCYVYZqE=", h.get("X-Signature"));
        Map<String, String> h2 = auth.restHeaders(sign);
        assertFalse(h.get("X-Nonce").equals(h2.get("X-Nonce")), "fresh nonce per attempt");
    }
}
