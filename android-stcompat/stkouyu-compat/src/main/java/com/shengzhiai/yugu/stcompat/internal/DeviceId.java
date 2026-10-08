// SPDX-License-Identifier: Apache-2.0
package com.shengzhiai.yugu.stcompat.internal;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Per-install random id (32 hex characters) kept in the app's private preferences. No hardware
 * identifier and no ANDROID_ID is read, so nothing that identifies the device leaves it.
 */
public final class DeviceId {
    private static final String PREFS = "yugu_stcompat";
    private static final String KEY = "install_id";
    private static volatile String cached;

    private DeviceId() {
    }

    public static synchronized String get(Context context) {
        String c = cached;
        if (c != null) {
            return c;
        }
        String id = null;
        if (context != null) {
            SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            id = p.getString(KEY, null);
            if (id == null || !id.matches("[0-9a-f]{32}")) {
                id = Codec.newTokenId();
                p.edit().putString(KEY, id).apply();
            }
        } else {
            id = Codec.newTokenId();
        }
        cached = id;
        return id;
    }

    static void resetForTests() {
        cached = null;
    }
}
