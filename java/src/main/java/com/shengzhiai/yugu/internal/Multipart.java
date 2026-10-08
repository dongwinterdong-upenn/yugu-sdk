package com.shengzhiai.yugu.internal;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Hand built {@code multipart/form-data} body. Text fields and the JSON {@code config} part carry no
 * file name, so the platform treats them as form parameters and the signature covers them.
 */
public final class Multipart {
    /** Content type of the JSON config part. */
    public static final String JSON_PART_TYPE = "application/json; charset=utf-8";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] ALNUM = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();

    private final byte[] body;
    private final String boundary;
    private final List<Part> parts;

    private Multipart(byte[] body, String boundary, List<Part> parts) {
        this.body = body;
        this.boundary = boundary;
        this.parts = parts;
    }

    /** @return a builder */
    public static Builder builder() {
        return new Builder();
    }

    /** @return body bytes */
    public byte[] body() {
        return body;
    }

    /** @return boundary */
    public String boundary() {
        return boundary;
    }

    /** @return the Content-Type header value */
    public String contentType() {
        return "multipart/form-data; boundary=" + boundary;
    }

    /** @return parts in order */
    public List<Part> parts() {
        return parts;
    }

    /** One part. */
    public static final class Part {
        private final String name;
        private final String fileName;
        private final String contentType;
        private final byte[] data;

        Part(String name, String fileName, String contentType, byte[] data) {
            this.name = name;
            this.fileName = fileName;
            this.contentType = contentType;
            this.data = data;
        }

        /** @return field name */
        public String name() {
            return name;
        }

        /** @return file name, null for form parameters */
        public String fileName() {
            return fileName;
        }

        /** @return content type, null when the part has no Content-Type header */
        public String contentType() {
            return contentType;
        }

        /** @return data */
        public byte[] data() {
            return data;
        }
    }

    /** Builder for {@link Multipart}. */
    public static final class Builder {
        private final List<Part> parts = new ArrayList<>();
        private String boundary;

        private Builder() {
        }

        /** @param b fixed boundary, for tests @return this */
        public Builder boundary(String b) {
            this.boundary = b;
            return this;
        }

        /**
         * @param name  field name
         * @param value UTF-8 text, sent without Content-Type (text/plain by default)
         * @return this
         */
        public Builder field(String name, String value) {
            parts.add(new Part(name, null, null, value.getBytes(StandardCharsets.UTF_8)));
            return this;
        }

        /**
         * @param name field name
         * @param json JSON text, sent with {@code Content-Type: application/json; charset=utf-8} and no file name
         * @return this
         */
        public Builder json(String name, String json) {
            parts.add(new Part(name, null, JSON_PART_TYPE, json.getBytes(StandardCharsets.UTF_8)));
            return this;
        }

        /**
         * @param name        field name
         * @param fileName    file name
         * @param contentType content type
         * @param data        bytes
         * @return this
         */
        public Builder file(String name, String fileName, String contentType, byte[] data) {
            parts.add(new Part(name, fileName == null ? "file" : fileName, contentType, data));
            return this;
        }

        /** @return the body */
        public Multipart build() {
            String b = boundary;
            byte[] out;
            while (true) {
                if (b == null) {
                    b = randomBoundary();
                }
                out = render(b);
                if (boundary != null || !containsBoundary(b)) {
                    break;
                }
                b = null;
            }
            return new Multipart(out, b, Collections.unmodifiableList(new ArrayList<>(parts)));
        }

        private boolean containsBoundary(String b) {
            byte[] needle = ("--" + b).getBytes(StandardCharsets.US_ASCII);
            for (Part p : parts) {
                if (indexOf(p.data, needle) >= 0) {
                    return true;
                }
            }
            return false;
        }

        private byte[] render(String b) {
            ByteArrayOutputStream o = new ByteArrayOutputStream(estimate());
            for (Part p : parts) {
                ascii(o, "--" + b + "\r\n");
                StringBuilder cd = new StringBuilder("Content-Disposition: form-data; name=\"").append(escape(p.name)).append('"');
                if (p.fileName != null) {
                    cd.append("; filename=\"").append(escape(p.fileName)).append('"');
                }
                utf8(o, cd.append("\r\n").toString());
                if (p.contentType != null) {
                    ascii(o, "Content-Type: " + p.contentType + "\r\n");
                }
                ascii(o, "\r\n");
                o.write(p.data, 0, p.data.length);
                ascii(o, "\r\n");
            }
            ascii(o, "--" + b + "--\r\n");
            return o.toByteArray();
        }

        private int estimate() {
            int n = 64;
            for (Part p : parts) {
                n += p.data.length + 160;
            }
            return n;
        }

        private static String randomBoundary() {
            char[] c = new char[24];
            for (int i = 0; i < c.length; i++) {
                c[i] = ALNUM[RANDOM.nextInt(ALNUM.length)];
            }
            return "----YuguFormBoundary" + new String(c);
        }
    }

    /** Escapes a name or file name the way browsers do: quote, CR and LF are percent encoded. */
    static String escape(String s) {
        return s.replace("\"", "%22").replace("\r", "%0D").replace("\n", "%0A");
    }

    private static void ascii(ByteArrayOutputStream o, String s) {
        byte[] b = s.getBytes(StandardCharsets.US_ASCII);
        o.write(b, 0, b.length);
    }

    private static void utf8(ByteArrayOutputStream o, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        o.write(b, 0, b.length);
    }

    static int indexOf(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= hay.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
