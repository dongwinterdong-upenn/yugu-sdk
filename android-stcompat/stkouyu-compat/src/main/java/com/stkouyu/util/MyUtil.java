// SPDX-License-Identifier: Apache-2.0
package com.stkouyu.util;

import android.content.Context;

import com.shengzhiai.yugu.stcompat.internal.DeviceId;

/** Device serial and string helpers of the Shengtong SDK. */
public class MyUtil {

    /** Per-install random id (32 hex characters, no hardware identifier); the second argument is accepted for compatibility. */
    public static String getSerialNumber(Context context, String appKey) {
        return DeviceId.get(context);
    }

    /** Always true: no provision file is needed in cloud mode. */
    public static boolean isExistsProvisionFileInDD(Context context) {
        return true;
    }

    /** True for null, empty, blank or the text "null". */
    public static boolean isNull(String s) {
        return s == null || s.trim().isEmpty() || "null".equalsIgnoreCase(s.trim());
    }

    public static boolean isNotNull(String s) {
        return !isNull(s);
    }
}
