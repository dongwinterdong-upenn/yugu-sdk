// SPDX-License-Identifier: Apache-2.0
package com.shengzhiai.yugu.stcompat.internal;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * multipart/form-data body built by hand (DESIGN 5.2): text parts carry no filename (form
 * parameters on the platform, signed), the audio part carries a filename (file part, not signed).
 * The audio file is streamed, never fully copied in memory.
 */
public final class Multipart {
    /** One part of the body. */
    public static final class Part {
        public final String name;
        public final String value;
        public final String filename;
        public final String contentType;
        final File file;
        /** Length of the file when it was added: the declared Content-Length depends on it. */
        final long fileLength;
        final byte[] bytes;

        Part(String name, String value, String filename, String contentType, File file, byte[] bytes) {
            this.name = name;
            this.value = value;
            this.filename = filename;
            this.contentType = contentType;
            this.file = file;
            this.fileLength = file == null ? 0 : file.length();
            this.bytes = bytes;
        }

        public boolean isFile() {
            return filename != null;
        }
    }

    public static final String TEXT_CONTENT_TYPE = "text/plain; charset=UTF-8";

    private final String boundary;
    private final List<Part> parts = new ArrayList<Part>();

    public Multipart() {
        this("----YuguStCompat" + Codec.randomHex(12));
    }

    public Multipart(String boundary) {
        this.boundary = boundary;
    }

    public String boundary() {
        return boundary;
    }

    public String contentType() {
        return "multipart/form-data; boundary=" + boundary;
    }

    public List<Part> parts() {
        return Collections.unmodifiableList(parts);
    }

    public Multipart addText(String name, String value) {
        parts.add(new Part(name, value, null, TEXT_CONTENT_TYPE, null, null));
        return this;
    }

    public Multipart addFile(String name, String filename, String contentType, File file) {
        parts.add(new Part(name, null, filename, contentType, file, null));
        return this;
    }

    public Multipart addBytes(String name, String filename, String contentType, byte[] data) {
        parts.add(new Part(name, null, filename, contentType, null, data));
        return this;
    }

    private byte[] head(Part p) {
        StringBuilder sb = new StringBuilder();
        sb.append("--").append(boundary).append("\r\n");
        sb.append("Content-Disposition: form-data; name=\"").append(escape(p.name)).append('"');
        if (p.filename != null) {
            sb.append("; filename=\"").append(escape(p.filename)).append('"');
        }
        sb.append("\r\n");
        if (p.contentType != null) {
            sb.append("Content-Type: ").append(p.contentType).append("\r\n");
        }
        sb.append("\r\n");
        return sb.toString().getBytes(Codec.UTF_8);
    }

    private static String escape(String s) {
        return s.replace("\"", "%22").replace("\r", "%0D").replace("\n", "%0A");
    }

    private byte[] tail() {
        return ("--" + boundary + "--\r\n").getBytes(Codec.UTF_8);
    }

    private static final byte[] CRLF = {'\r', '\n'};

    private long payloadLength(Part p) {
        if (p.file != null) {
            return p.fileLength;
        }
        if (p.bytes != null) {
            return p.bytes.length;
        }
        return p.value == null ? 0 : p.value.getBytes(Codec.UTF_8).length;
    }

    public long contentLength() {
        long n = 0;
        for (Part p : parts) {
            n += head(p).length + payloadLength(p) + CRLF.length;
        }
        return n + tail().length;
    }

    public void writeTo(OutputStream out) throws IOException {
        byte[] buf = new byte[16 * 1024];
        for (Part p : parts) {
            out.write(head(p));
            if (p.file != null) {
                InputStream in = new FileInputStream(p.file);
                try {
                    long expected = p.fileLength;
                    long written = 0;
                    int n;
                    while (written < expected && (n = in.read(buf, 0, (int) Math.min(buf.length, expected - written))) > 0) {
                        out.write(buf, 0, n);
                        written += n;
                    }
                    if (written != expected || in.read() != -1) {
                        throw new IOException("audio file changed while uploading");
                    }
                } finally {
                    in.close();
                }
            } else if (p.bytes != null) {
                out.write(p.bytes);
            } else if (p.value != null) {
                out.write(p.value.getBytes(Codec.UTF_8));
            }
            out.write(CRLF);
        }
        out.write(tail());
        out.flush();
    }

    /** Whole body in memory, for tests and small bodies. */
    public byte[] toByteArray() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream((int) Math.min(contentLength(), Integer.MAX_VALUE));
        writeTo(bos);
        return bos.toByteArray();
    }
}
