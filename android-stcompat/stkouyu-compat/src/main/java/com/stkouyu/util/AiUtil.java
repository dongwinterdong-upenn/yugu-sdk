// SPDX-License-Identifier: Apache-2.0
package com.stkouyu.util;

import android.content.Context;

import com.shengzhiai.yugu.stcompat.internal.Codec;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** File, asset and hashing helpers of the Shengtong SDK. Native resource helpers are kept for compatibility. */
public class AiUtil {

    /** SHA-1 of the UTF-8 text as lower-case hex, null for null. */
    public static String sha1(String text) {
        if (text == null) {
            return null;
        }
        return Codec.hex(Codec.digest("SHA-1", text.getBytes(Codec.UTF_8)));
    }

    /** MD5 of a stream as lower-case hex (the stream is read to the end, not closed), null on error. */
    public static String md5(Context context, InputStream in) {
        if (in == null) {
            return null;
        }
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            return Codec.hex(md.digest());
        } catch (IOException e) {
            return null;
        } catch (NoSuchAlgorithmException e) {
            return null;
        }
    }

    /** Number of words: runs of Latin letters, digits and apostrophes, plus each CJK character. */
    public static long getWordCount(String text) {
        if (text == null) {
            return 0;
        }
        long count = 0;
        boolean inWord = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isHanzi(c)) {
                count++;
                inWord = false;
            } else if (Character.isLetterOrDigit(c) || c == '\'') {
                if (!inWord) {
                    count++;
                    inWord = true;
                }
            } else {
                inWord = false;
            }
        }
        return count;
    }

    /** Number of CJK unified ideographs. */
    public static long getHanziCount(String text) {
        if (text == null) {
            return 0;
        }
        long count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (isHanzi(text.charAt(i))) {
                count++;
            }
        }
        return count;
    }

    private static boolean isHanzi(char c) {
        return (c >= '一' && c <= '鿿') || (c >= '㐀' && c <= '䶿') || (c >= '豈' && c <= '﫿');
    }

    /** {@code getExternalFilesDir(null)}, or the internal files directory when external storage is unavailable. */
    public static File externalFilesDir(Context context) {
        if (context == null) {
            return new File(System.getProperty("java.io.tmpdir", "."));
        }
        File d = null;
        try {
            d = context.getExternalFilesDir(null);
        } catch (RuntimeException ignored) {
            d = null;
        }
        if (d == null || (!d.isDirectory() && !d.mkdirs() && !d.isDirectory())) {
            d = context.getFilesDir();
        }
        return d;
    }

    /** UTF-8 content of a file, null when it cannot be read. */
    public static String readFile(File file) {
        if (file == null || !file.isFile()) {
            return null;
        }
        try {
            return readStream(new FileInputStream(file));
        } catch (IOException e) {
            return null;
        }
    }

    /** UTF-8 content of an asset, null when it cannot be read. */
    public static String readFileFromAssets(Context context, String name) {
        if (context == null || name == null) {
            return null;
        }
        try {
            return readStream(context.getAssets().open(name));
        } catch (IOException e) {
            return null;
        }
    }

    private static String readStream(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), Codec.UTF_8);
        } finally {
            in.close();
        }
    }

    public static void writeToFile(String path, String content) {
        if (path != null) {
            writeToFile(new File(path), content);
        }
    }

    /** Writes UTF-8 text, creating parent directories; errors are ignored. */
    public static void writeToFile(File file, String content) {
        if (file == null) {
            return;
        }
        try {
            OutputStream out = open(file);
            try {
                out.write((content == null ? "" : content).getBytes(Codec.UTF_8));
            } finally {
                out.close();
            }
        } catch (IOException ignored) {
            // best effort, like the Shengtong helper
        }
    }

    /** Copies a stream into a file (the stream is closed); errors are ignored. */
    public static void writeToFile(File file, InputStream in) {
        if (file == null || in == null) {
            return;
        }
        try {
            copy(in, file);
        } catch (IOException ignored) {
            // best effort
        }
    }

    /** Extracts a zip asset into {@code <externalFilesDir>/<name without .zip>}; null on error. */
    public static File unzipFileCN(Context context, String assetName) {
        return unzipFile(context, assetName);
    }

    /** Extracts a zip asset into {@code <externalFilesDir>/<name without .zip>}; null on error. */
    public static File unzipFile(Context context, String assetName) {
        if (context == null || assetName == null) {
            return null;
        }
        String dirName = assetName.endsWith(".zip") ? assetName.substring(0, assetName.length() - 4) : assetName + ".d";
        File dir = new File(externalFilesDir(context), dirName);
        try {
            String root = dir.getCanonicalPath() + File.separator;
            ZipInputStream zin = new ZipInputStream(context.getAssets().open(assetName));
            try {
                ZipEntry e;
                while ((e = zin.getNextEntry()) != null) {
                    File out = new File(dir, e.getName());
                    if (!out.getCanonicalPath().startsWith(root)) {
                        throw new IOException("zip entry outside target: " + e.getName());
                    }
                    if (e.isDirectory()) {
                        //noinspection ResultOfMethodCallIgnored
                        out.mkdirs();
                    } else {
                        OutputStream os = open(out);
                        try {
                            byte[] buf = new byte[8192];
                            int n;
                            while ((n = zin.read(buf)) > 0) {
                                os.write(buf, 0, n);
                            }
                        } finally {
                            os.close();
                        }
                    }
                }
            } finally {
                zin.close();
            }
            return dir;
        } catch (IOException e) {
            return null;
        }
    }

    /** Copies an asset into {@code <externalFilesDir>/<name>}; null on error. */
    public static File copyDb2SD(Context context, String assetName) {
        if (context == null || assetName == null) {
            return null;
        }
        File out = new File(externalFilesDir(context), assetName);
        try {
            copy(context.getAssets().open(assetName), out);
            return out;
        } catch (IOException e) {
            return null;
        }
    }

    public static File getFilesDir(Context context) {
        return context == null ? null : context.getFilesDir();
    }

    /** Copies an asset into {@code <externalFilesDir>/<name>}. Not needed in cloud mode. */
    public static void copyNativeResToSD(Context context, String assetName) throws IOException {
        if (context == null || assetName == null) {
            throw new IOException("context and asset name are required");
        }
        copy(context.getAssets().open(assetName), new File(externalFilesDir(context), assetName));
    }

    private static OutputStream open(File file) throws IOException {
        File dir = file.getAbsoluteFile().getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("cannot create " + dir);
        }
        return new FileOutputStream(file);
    }

    private static void copy(InputStream in, File file) throws IOException {
        try {
            OutputStream out = open(file);
            try {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
    }
}
