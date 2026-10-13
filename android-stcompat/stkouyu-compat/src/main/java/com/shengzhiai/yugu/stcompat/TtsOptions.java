// SPDX-License-Identifier: Apache-2.0
package com.shengzhiai.yugu.stcompat;

/** Options of one {@link TtsClient#tts} call (SDK 2.2.0); null options use the defaults. */
public final class TtsOptions {
    private String idempotencyKey;

    /**
     * Idempotency-Key of the call, for example a business id: a repeated call with the same key and
     * the same request is synthesised and billed once. Default: a new 32 hex character key per call,
     * reused by the retries of that call.
     *
     * @param key 1 to 200 visible ASCII characters, null for the default
     * @throws IllegalArgumentException for another key
     */
    public TtsOptions setIdempotencyKey(String key) {
        if (key != null && !validKey(key)) {
            throw new IllegalArgumentException("idempotencyKey 须为 1 到 200 个可见 ASCII 字符");
        }
        this.idempotencyKey = key;
        return this;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    static boolean validKey(String key) {
        if (key.isEmpty() || key.length() > 200) {
            return false;
        }
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c < 0x21 || c > 0x7e) {
                return false;
            }
        }
        return true;
    }
}
