package com.shengzhiai.yugu;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Request signature of the platform (CONTRACT section 0.2), identical in every SDK and checked
 * against {@code spec/fixtures/sign/vectors.json}.
 *
 * <ol>
 *   <li>take the business parameters, drop null and empty values;</li>
 *   <li>sort by key in natural (TreeMap) order;</li>
 *   <li>join as {@code k1=v1&k2=v2} without URL encoding;</li>
 *   <li>{@code Base64(HMAC_SHA256(payload_utf8, secretKey_utf8))}.</li>
 * </ol>
 *
 * <p>Signed sets per call: native evaluate signs {@code {config: <exact JSON text of the config part>}};
 * TTS signs the top level scalar JSON fields; compat REST signs the form fields; the report query
 * signs nothing; WebSocket handshakes sign every query parameter except {@code signature}.
 */
public final class Signer {
    private static final String HMAC_SHA256 = "HmacSHA256";

    private Signer() {
    }

    /**
     * @param params business parameters; null and empty values are dropped
     * @return payload such as {@code coreType=sent.eval.cn&language=zh-CN&refText=北京你好}
     */
    public static String buildPayload(Map<String, ?> params) {
        TreeMap<String, String> sorted = new TreeMap<>();
        if (params != null) {
            for (Map.Entry<String, ?> e : params.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) {
                    continue;
                }
                String v = String.valueOf(e.getValue());
                if (!v.isEmpty()) {
                    sorted.put(e.getKey(), v);
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    /**
     * @param payload   payload built by {@link #buildPayload(Map)}
     * @param secretKey secret key
     * @return Base64 HMAC-SHA256 signature
     */
    public static String signPayload(String payload, String secretKey) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
            return Base64.getEncoder().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    /**
     * @param params    business parameters
     * @param secretKey secret key
     * @return signature for {@code X-Signature} or the {@code signature} query parameter
     */
    public static String sign(Map<String, ?> params, String secretKey) {
        return signPayload(buildPayload(params), secretKey);
    }
}
