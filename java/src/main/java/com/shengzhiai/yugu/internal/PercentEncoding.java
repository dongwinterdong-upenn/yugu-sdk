package com.shengzhiai.yugu.internal;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** RFC 3986 percent encoding: everything except unreserved characters is encoded. */
public final class PercentEncoding {
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private PercentEncoding() {
    }

    /**
     * @param s text
     * @return encoded text, safe for a path segment or a query component
     */
    public static String encode(String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder(b.length * 3 / 2);
        for (byte x : b) {
            int c = x & 0xFF;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '.' || c == '_' || c == '~') {
                sb.append((char) c);
            } else {
                sb.append('%').append(HEX[c >> 4]).append(HEX[c & 0xF]);
            }
        }
        return sb.toString();
    }

    /**
     * @param s encoded text; {@code +} stays a plus sign
     * @return decoded text
     */
    public static String decode(String s) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length()) {
                int hi = Character.digit(s.charAt(i + 1), 16);
                int lo = Character.digit(s.charAt(i + 2), 16);
                if (hi >= 0 && lo >= 0) {
                    out.write(hi * 16 + lo);
                    i += 2;
                    continue;
                }
            }
            int cp = s.codePointAt(i);
            byte[] b = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8);
            out.write(b, 0, b.length);
            i += Character.charCount(cp) - 1;
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
