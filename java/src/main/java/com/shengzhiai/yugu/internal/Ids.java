package com.shengzhiai.yugu.internal;

import com.shengzhiai.yugu.errors.ErrorTable;
import com.shengzhiai.yugu.errors.YuguErrors;
import com.shengzhiai.yugu.errors.YuguException;

import java.security.SecureRandom;
import java.util.UUID;
import java.util.regex.Pattern;

/** Idempotency keys, nonces and session ids. */
public final class Ids {
    /** Pattern a caller supplied idempotency key must match. */
    public static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[\\x21-\\x7E]{1,200}$");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private Ids() {
    }

    /** @return 32 lowercase hex characters: a random UUID v4 without dashes */
    public static String newIdempotencyKey() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** @return 16 lowercase hex characters */
    public static String nonce() {
        return hex(8);
    }

    /** @return short session id */
    public static String sessionId() {
        return "ws-" + hex(4);
    }

    private static String hex(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        char[] c = new char[bytes * 2];
        for (int i = 0; i < bytes; i++) {
            c[2 * i] = HEX[(b[i] >> 4) & 0xF];
            c[2 * i + 1] = HEX[b[i] & 0xF];
        }
        return new String(c);
    }

    /**
     * @param key caller key
     * @return the key
     * @throws YuguException {@code InvalidParameterException} 90010 when the key does not match
     */
    public static String validateIdempotencyKey(String key) throws YuguException {
        if (key == null || !IDEMPOTENCY_KEY.matcher(key).matches()) {
            throw YuguErrors.local(ErrorTable.INVALID_ARGUMENT,
                    "idempotencyKey must be 1 to 200 printable ASCII characters without spaces", null);
        }
        return key;
    }

    /**
     * Picks the key of a write call.
     *
     * @param callerKey key from the caller, may be null
     * @param auto      generate when the caller gave none
     * @return key, or null when none is used
     */
    public static String resolve(String callerKey, boolean auto) {
        if (callerKey != null) {
            return validateIdempotencyKey(callerKey);
        }
        return auto ? newIdempotencyKey() : null;
    }
}
