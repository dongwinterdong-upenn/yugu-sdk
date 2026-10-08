package com.shengzhiai.yugu.internal;

import com.shengzhiai.yugu.Signer;

import java.util.LinkedHashMap;
import java.util.Map;

/** Applies the configured credentials to REST attempts and WebSocket handshakes. */
public final class Auth {
    private final String appKey;
    private final String secretKey;
    private final String token;

    /**
     * @param appKey    app key, null for token authentication
     * @param secretKey secret key, null for token authentication
     * @param token     JWT, null for key authentication
     */
    public Auth(String appKey, String secretKey, String token) {
        this.appKey = appKey;
        this.secretKey = secretKey;
        this.token = token;
    }

    /** @return true for token authentication */
    public boolean isToken() {
        return token != null;
    }

    /** @return app key or null */
    public String appKey() {
        return appKey;
    }

    private static String nowSeconds() {
        return String.valueOf(System.currentTimeMillis() / 1000L);
    }

    /**
     * Headers of one REST attempt. Each attempt gets a fresh timestamp and nonce; the signature covers
     * the business parameters only.
     *
     * @param signParams business parameters of the call
     * @return headers
     */
    public Map<String, String> restHeaders(Map<String, String> signParams) {
        Map<String, String> h = new LinkedHashMap<>();
        if (token != null) {
            h.put("Authorization", "Bearer " + token);
            return h;
        }
        h.put("X-App-Key", appKey);
        h.put("X-Timestamp", nowSeconds());
        h.put("X-Nonce", Ids.nonce());
        h.put("X-Signature", Signer.sign(signParams, secretKey));
        return h;
    }

    /**
     * Query of one WebSocket handshake. Key mode signs every parameter except {@code signature}.
     *
     * @param business business parameters such as {@code idempotencyKey}
     * @return ordered query parameters
     */
    public Map<String, String> wsQuery(Map<String, String> business) {
        Map<String, String> q = new LinkedHashMap<>();
        if (token != null) {
            q.put("token", token);
            q.putAll(business);
            return q;
        }
        q.put("appKey", appKey);
        q.put("timestamp", nowSeconds());
        q.put("nonce", Ids.nonce());
        q.putAll(business);
        q.put("signature", Signer.sign(q, secretKey));
        return q;
    }

    /**
     * @param params query parameters
     * @return {@code ?a=b&c=d} with RFC 3986 encoding, empty for no parameters
     */
    public static String encodeQuery(Map<String, String> params) {
        if (params.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("?");
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (sb.length() > 1) {
                sb.append('&');
            }
            sb.append(PercentEncoding.encode(e.getKey())).append('=').append(PercentEncoding.encode(e.getValue()));
        }
        return sb.toString();
    }
}
