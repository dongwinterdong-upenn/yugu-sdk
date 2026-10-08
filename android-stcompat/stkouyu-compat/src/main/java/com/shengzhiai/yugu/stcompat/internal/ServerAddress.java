// SPDX-License-Identifier: Apache-2.0
package com.shengzhiai.yugu.stcompat.internal;

import java.util.Locale;

/**
 * Maps {@code EngineSetting.setServerAddress} to the platform REST base (DESIGN 6): empty values
 * and Shengtong addresses (any host under stkouyu.com) map to the default base
 * {@code https://open.shengzhiai.com}; other {@code http(s)://} and {@code ws(s)://} addresses
 * are used as the base, ws mapped to http and wss to https.
 */
public final class ServerAddress {
    private ServerAddress() {
    }

    /**
     * Platform base for a Shengtong server address.
     *
     * @throws IllegalArgumentException for an address without a supported scheme
     */
    public static String resolve(String serverAddress) {
        if (serverAddress == null || serverAddress.trim().isEmpty() || isShengtong(serverAddress)) {
            return CompatConfig.DEFAULT_BASE_URL;
        }
        return normalizeBase(serverAddress.trim());
    }

    /** Base URL with http or https scheme and without trailing slash. */
    public static String normalizeBase(String url) {
        String u = url.trim();
        String lower = u.toLowerCase(Locale.ROOT);
        if (lower.startsWith("wss://")) {
            u = "https://" + u.substring(6);
        } else if (lower.startsWith("ws://")) {
            u = "http://" + u.substring(5);
        } else if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            throw new IllegalArgumentException("server address needs http, https, ws or wss scheme: " + url);
        }
        String rest = u.substring(u.indexOf("://") + 3);
        if (rest.isEmpty() || rest.startsWith("/")) {
            throw new IllegalArgumentException("server address has no host: " + url);
        }
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }

    static boolean isShengtong(String address) {
        String a = address.trim().toLowerCase(Locale.ROOT);
        int scheme = a.indexOf("://");
        String rest = scheme >= 0 ? a.substring(scheme + 3) : a;
        int end = rest.length();
        for (char c : new char[] {'/', ':', '?', '#'}) {
            int k = rest.indexOf(c);
            if (k >= 0 && k < end) {
                end = k;
            }
        }
        String host = rest.substring(0, end);
        return host.equals("stkouyu.com") || host.endsWith(".stkouyu.com");
    }

    /** Effective base: YuguCompat override, then the EngineSetting address. */
    public static String effectiveBase(String engineSettingBase) {
        String o = CompatConfig.baseUrlOverride();
        return o != null ? o : (engineSettingBase != null ? engineSettingBase : CompatConfig.DEFAULT_BASE_URL);
    }
}
