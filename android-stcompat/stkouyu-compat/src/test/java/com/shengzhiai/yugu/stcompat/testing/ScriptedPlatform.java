package com.shengzhiai.yugu.stcompat.testing;

import java.io.EOFException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Compat realtime channel played on {@link FakeWs} like tools/mock-server and CONTRACT 5.5: after
 * every 16000 bytes of audio a progress frame with one unit read per 8000 bytes, Shengtong-shaped
 * incremental {@code words} and the {@code progress} list; at end one more unit, then the final.
 */
public final class ScriptedPlatform implements FakeWs.Script {
    public static final String STARTED = "{\"event\":\"started\",\"coreType\":\"sent.eval.cn\"}";

    private final String[] units;
    private final Map<FakeWs.Conn, long[]> state = Collections.synchronizedMap(new IdentityHashMap<FakeWs.Conn, long[]>());
    public final AtomicInteger ends = new AtomicInteger();
    /** Every progress frame sent, in order. */
    public final List<String> sentProgress = Collections.synchronizedList(new ArrayList<String>());
    public volatile String finalBody = Fixtures.text("spec/fixtures/platform/compat_sent.eval.cn.json").trim();
    /** The first N ends are answered by a dropped connection. */
    public volatile int failingEnds;
    /** Unit read but not matched, -1 for none. */
    public volatile int mismatch = -1;
    /** Frames with bytes only, as without tracking. */
    public volatile boolean progressOff;

    public ScriptedPlatform(String[] units) {
        this.units = units;
    }

    /** Units of a Chinese reference text: every character that is no punctuation or space. */
    public static String[] chineseUnits(String text) {
        List<String> u = new ArrayList<>();
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (Character.isLetterOrDigit(cp)) {
                u.add(new String(Character.toChars(cp)));
            }
            i += Character.charCount(cp);
        }
        return u.toArray(new String[0]);
    }

    /** A compat progress frame: words for units [from, read), progress for all units. */
    public static String progressFrame(String[] units, int from, int read, int bad, long bytes) {
        StringBuilder words = new StringBuilder();
        for (int i = from; i < read; i++) {
            boolean ok = i != bad;
            int s = ok ? 100 : 0;
            if (words.length() > 0) {
                words.append(',');
            }
            words.append("{\"charType\":0,\"scores\":{\"pronunciation\":").append(s).append(",\"skipped\":").append(ok ? 0 : 1)
                    .append(",\"tone\":").append(s).append(",\"overall\":").append(s).append(",\"overall_pron\":").append(s)
                    .append("},\"word\":\"").append(units[i]).append("\",\"word_parts\":[{\"beginIndex\":0,\"charType\":0,")
                    .append("\"endIndex\":0,\"part\":\"").append(units[i]).append("\"}]}");
        }
        StringBuilder prog = new StringBuilder();
        int matched = 0;
        for (int i = 0; i < units.length; i++) {
            boolean r = i < read;
            boolean m = r && i != bad;
            matched += m ? 1 : 0;
            if (i > 0) {
                prog.append(',');
            }
            prog.append("{\"word\":\"").append(units[i]).append("\",\"read\":").append(r).append(",\"matched\":").append(m).append('}');
        }
        return "{\"eof\":0,\"recordId\":\"\",\"result\":{\"kernel_version\":\"1.0.0\",\"resource_version\":\"1.0.0\",\"words\":["
                + words + "],\"bytes\":" + bytes + ",\"readUnits\":" + read + ",\"matchedUnits\":" + matched
                + ",\"totalUnits\":" + units.length + ",\"progress\":[" + prog + "]}}";
    }

    private String frame(long[] st, int extraUnit) {
        long bytes = st[0];
        if (progressOff) {
            return "{\"eof\":0,\"result\":{\"bytes\":" + bytes + "}}";
        }
        int read = (int) Math.min(units.length, Math.max(st[2], bytes / 8000 + extraUnit));
        String f = progressFrame(units, (int) st[2], read, mismatch, bytes);
        st[2] = read;
        sentProgress.add(f);
        return f;
    }

    @Override
    public void onText(FakeWs.Conn c, String text) {
        if (!FakeWs.END.equals(text)) {
            state.put(c, new long[3]);
            c.serverText(STARTED);
            return;
        }
        int n = ends.incrementAndGet();
        if (n <= failingEnds) {
            c.serverFail(new EOFException("connection reset after end"));
            return;
        }
        long[] st = state.get(c);
        c.serverText(frame(st, 1));
        c.serverText(finalBody);
    }

    @Override
    public void onBinary(FakeWs.Conn c, byte[] data) {
        long[] st = state.get(c);
        st[0] += data.length;
        if (st[0] - st[1] >= 16000) {
            st[1] = st[0];
            c.serverText(frame(st, 0));
        }
    }
}
