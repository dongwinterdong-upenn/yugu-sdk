// SPDX-License-Identifier: Apache-2.0
package com.shengzhiai.yugu.stcompat.internal;

import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.TreeMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Platform signature (CONTRACT 0.2, DESIGN 5.2): drop null and empty values, sort keys
 * lexicographically, join as {@code k=v&k=v} without URL encoding, then
 * {@code Base64(HMAC_SHA256(payload, secretKey))}. For compat REST the signed set is exactly the
 * text form fields sent; the audio file part is not signed.
 */
public final class Signer {
    private Signer() {
    }

    public static String payload(Map<String, String> params) {
        TreeMap<String, String> sorted = new TreeMap<String, String>();
        if (params != null) {
            for (Map.Entry<String, String> e : params.entrySet()) {
                if (e.getKey() != null && e.getValue() != null && e.getValue().length() > 0) {
                    sorted.put(e.getKey(), e.getValue());
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

    public static String sign(Map<String, String> params, String secretKey) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secretKey.getBytes(Codec.UTF_8), "HmacSHA256"));
            return Codec.base64(mac.doFinal(payload(params).getBytes(Codec.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("HmacSHA256 not available", e);
        } catch (InvalidKeyException e) {
            throw new IllegalArgumentException("invalid secretKey", e);
        }
    }
}
