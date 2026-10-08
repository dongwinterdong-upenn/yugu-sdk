// SPDX-License-Identifier: Apache-2.0
package com.stkouyu.util.httputil;

/** Text checks on {@link CharSequence}. */
public final class TextUtils {

    /** True for null or length 0. */
    public static boolean isEmpty(CharSequence s) {
        return s == null || s.length() == 0;
    }

    /** True for null, empty or whitespace only. */
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

    /** True when the text contains a whitespace character. */
    public static boolean containsBlanks(CharSequence s) {
        if (s == null) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) {
                return true;
            }
        }
        return false;
    }
}
