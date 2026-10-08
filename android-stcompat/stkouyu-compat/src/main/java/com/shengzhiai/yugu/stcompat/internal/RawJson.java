// SPDX-License-Identifier: Apache-2.0
package com.shengzhiai.yugu.stcompat.internal;

import java.util.LinkedHashMap;

/**
 * Minimal JSON scanner and writer. The envelope delivered to {@code onScore} embeds the platform
 * {@code result} object as the exact text the platform sent (no re-serialisation, no number
 * reformatting), so the scanner returns raw value spans of a top-level object.
 */
public final class RawJson {
    private final String s;
    private int i;

    private RawJson(String s) {
        this.s = s;
    }

    /**
     * Top-level members of a JSON object as raw value text, in document order.
     *
     * @throws IllegalArgumentException when the text is not a JSON object
     */
    public static LinkedHashMap<String, String> members(String json) {
        if (json == null) {
            throw new IllegalArgumentException("null JSON");
        }
        RawJson p = new RawJson(json);
        LinkedHashMap<String, String> out = new LinkedHashMap<String, String>();
        p.ws();
        p.expect('{');
        p.ws();
        if (p.peek() == '}') {
            p.i++;
        } else {
            while (true) {
                p.ws();
                int ks = p.i;
                p.skipString();
                String key = unquote(json.substring(ks, p.i));
                p.ws();
                p.expect(':');
                p.ws();
                int vs = p.i;
                p.skipValue();
                out.put(key, json.substring(vs, p.i));
                p.ws();
                char c = p.next();
                if (c == '}') {
                    break;
                }
                if (c != ',') {
                    throw p.error("expected , or }");
                }
            }
        }
        p.ws();
        if (p.i != json.length()) {
            throw p.error("trailing characters");
        }
        return out;
    }

    /** True when the raw value is a JSON string literal. */
    public static boolean isString(String raw) {
        return raw != null && raw.length() >= 2 && raw.charAt(0) == '"';
    }

    /** Decodes a JSON string literal; returns null for anything else. */
    public static String unquote(String raw) {
        if (!isString(raw)) {
            return null;
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int k = 1; k < raw.length() - 1; k++) {
            char c = raw.charAt(k);
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            char e = raw.charAt(++k);
            switch (e) {
                case 'b':
                    sb.append('\b');
                    break;
                case 'f':
                    sb.append('\f');
                    break;
                case 'n':
                    sb.append('\n');
                    break;
                case 'r':
                    sb.append('\r');
                    break;
                case 't':
                    sb.append('\t');
                    break;
                case 'u':
                    sb.append((char) Integer.parseInt(raw.substring(k + 1, k + 5), 16));
                    k += 4;
                    break;
                default:
                    sb.append(e);
                    break;
            }
        }
        return sb.toString();
    }

    /** Integer value of a raw JSON number or numeric string, or {@code def}. */
    public static long toLong(String raw, long def) {
        if (raw == null) {
            return def;
        }
        String t = isString(raw) ? unquote(raw) : raw.trim();
        try {
            return Long.parseLong(t);
        } catch (NumberFormatException e) {
            try {
                double d = Double.parseDouble(t);
                return d == Math.rint(d) ? (long) d : def;
            } catch (NumberFormatException e2) {
                return def;
            }
        }
    }

    /** JSON string literal for {@code value} (null becomes JSON null). */
    public static String quote(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(value.length() + 2);
        sb.append('"');
        for (int k = 0; k < value.length(); k++) {
            char c = value.charAt(k);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                default:
                    if (c < 0x20 || c == ' ' || c == ' ') {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                    break;
            }
        }
        return sb.append('"').toString();
    }

    // ---------------------------------------------------------------- spans

    /** An object found at a position: member value spans {start, end} and the index of its closing brace. */
    public static final class ObjectSpan {
        public final LinkedHashMap<String, int[]> members = new LinkedHashMap<String, int[]>();
        public int close;
    }

    /**
     * Members of the object starting at {@code start} (which must be '{'), with absolute value spans.
     *
     * @throws IllegalArgumentException when the text there is not a JSON object
     */
    public static ObjectSpan objectAt(String json, int start) {
        RawJson p = new RawJson(json);
        p.i = start;
        ObjectSpan o = new ObjectSpan();
        p.expect('{');
        p.ws();
        if (p.peek() == '}') {
            o.close = p.i;
            return o;
        }
        while (true) {
            p.ws();
            int ks = p.i;
            p.skipString();
            String key = unquote(json.substring(ks, p.i));
            p.ws();
            p.expect(':');
            p.ws();
            int vs = p.i;
            p.skipValue();
            o.members.put(key, new int[] {vs, p.i});
            p.ws();
            char c = p.next();
            if (c == '}') {
                o.close = p.i - 1;
                return o;
            }
            if (c != ',') {
                throw p.error("expected , or }");
            }
        }
    }

    /**
     * Element spans {start, end} of the array starting at {@code start} (which must be '[').
     *
     * @throws IllegalArgumentException when the text there is not a JSON array
     */
    public static java.util.List<int[]> arrayAt(String json, int start) {
        RawJson p = new RawJson(json);
        p.i = start;
        java.util.List<int[]> out = new java.util.ArrayList<int[]>();
        p.expect('[');
        p.ws();
        if (p.peek() == ']') {
            return out;
        }
        while (true) {
            p.ws();
            int vs = p.i;
            p.skipValue();
            out.add(new int[] {vs, p.i});
            p.ws();
            char c = p.next();
            if (c == ']') {
                return out;
            }
            if (c != ',') {
                throw p.error("expected , or ]");
            }
        }
    }

    // ---------------------------------------------------------------- scanner

    private char peek() {
        if (i >= s.length()) {
            throw error("unexpected end");
        }
        return s.charAt(i);
    }

    private char next() {
        char c = peek();
        i++;
        return c;
    }

    private void expect(char c) {
        if (next() != c) {
            throw error("expected " + c);
        }
    }

    private void ws() {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
                i++;
            } else {
                break;
            }
        }
    }

    private void skipString() {
        expect('"');
        while (true) {
            char c = next();
            if (c == '\\') {
                next();
            } else if (c == '"') {
                return;
            }
        }
    }

    private void skipValue() {
        char c = peek();
        if (c == '"') {
            skipString();
        } else if (c == '{' || c == '[') {
            skipContainer();
        } else {
            int start = i;
            while (i < s.length()) {
                char d = s.charAt(i);
                if (d == ',' || d == '}' || d == ']' || d == ' ' || d == '\n' || d == '\r' || d == '\t') {
                    break;
                }
                i++;
            }
            String lit = s.substring(start, i);
            if (lit.isEmpty() || !(lit.equals("true") || lit.equals("false") || lit.equals("null") || isNumber(lit))) {
                throw error("bad literal " + lit);
            }
        }
    }

    private void skipContainer() {
        int depth = 0;
        while (true) {
            char c = next();
            if (c == '"') {
                i--;
                skipString();
            } else if (c == '{' || c == '[') {
                depth++;
            } else if (c == '}' || c == ']') {
                depth--;
                if (depth == 0) {
                    return;
                }
            }
        }
    }

    private static boolean isNumber(String lit) {
        try {
            Double.parseDouble(lit);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private IllegalArgumentException error(String what) {
        return new IllegalArgumentException("invalid JSON at " + i + ": " + what);
    }
}
