// SPDX-License-Identifier: Apache-2.0
package com.shengzhiai.yugu.stcompat.internal;

import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.UUID;

/** Encoding helpers that work on API 21 and on a plain JVM (no android.util.Base64, no java.util.Base64). */
public final class Codec {
    public static final Charset UTF_8 = Charset.forName("UTF-8");
    public static final Charset US_ASCII = Charset.forName("US-ASCII");

    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final char[] B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private Codec() {
    }

    public static String hex(byte[] data) {
        char[] out = new char[data.length * 2];
        for (int i = 0; i < data.length; i++) {
            out[i * 2] = HEX[(data[i] >> 4) & 0xF];
            out[i * 2 + 1] = HEX[data[i] & 0xF];
        }
        return new String(out);
    }

    /** Standard Base64 with padding, no line breaks. */
    public static String base64(byte[] data) {
        StringBuilder sb = new StringBuilder((data.length + 2) / 3 * 4);
        int i = 0;
        while (i + 3 <= data.length) {
            int v = ((data[i] & 0xFF) << 16) | ((data[i + 1] & 0xFF) << 8) | (data[i + 2] & 0xFF);
            sb.append(B64[(v >> 18) & 63]).append(B64[(v >> 12) & 63]).append(B64[(v >> 6) & 63]).append(B64[v & 63]);
            i += 3;
        }
        int rest = data.length - i;
        if (rest == 1) {
            int v = (data[i] & 0xFF) << 16;
            sb.append(B64[(v >> 18) & 63]).append(B64[(v >> 12) & 63]).append("==");
        } else if (rest == 2) {
            int v = ((data[i] & 0xFF) << 16) | ((data[i + 1] & 0xFF) << 8);
            sb.append(B64[(v >> 18) & 63]).append(B64[(v >> 12) & 63]).append(B64[(v >> 6) & 63]).append('=');
        }
        return sb.toString();
    }

    public static byte[] digest(String algorithm, byte[] data) {
        try {
            return MessageDigest.getInstance(algorithm).digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(algorithm + " not available", e);
        }
    }

    /** 32 lowercase hex characters, UUID v4 without dashes (DESIGN 2.2). Used as tokenId and Idempotency-Key. */
    public static String newTokenId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** Random lowercase hex string of {@code bytes * 2} characters. */
    public static String randomHex(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return hex(b);
    }

    public static boolean isEmpty(CharSequence s) {
        return s == null || s.length() == 0;
    }

    public static boolean isBlank(CharSequence s) {
        if (s == null) {
            return true;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isWhitespace(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
