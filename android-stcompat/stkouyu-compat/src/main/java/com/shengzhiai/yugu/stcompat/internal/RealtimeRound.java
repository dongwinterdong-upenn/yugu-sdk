// SPDX-License-Identifier: Apache-2.0
package com.shengzhiai.yugu.stcompat.internal;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One evaluation round over the compat realtime channel {@code /{coreType}} (DESIGN 14.3), used
 * when RecordSetting.realtime_feedback is 1 and by the SkEgn emulation when the request carries
 * realtime_feedback. The final frame is the same body as the REST answer, so callers build the
 * final envelope exactly as for REST.
 *
 * <pre>
 * start()    worker thread: handshake ws(s)://base/{coreType}?appKey&amp;timestamp&amp;nonce&amp;idempotencyKey&amp;signature,
 *            parameter frame {REST form fields, "realtime_feedback":true, "idempotencyKey":tokenId},
 *            then the PCM of audio() in frames of at most 32000 bytes, a protocol ping when idle
 * progress   {"eof":0,"recordId":"","result":{bytes, readUnits, matchedUnits, totalUnits, progress[],
 *            kernel_version, resource_version, words[]}}: words are Shengtong-shaped items of the units
 *            read since the previous progress frame, covering [readUnits - words.length, readUnits).
 *            A frame reaches the listener when it has words not delivered before in this round (by
 *            absolute index: after a reconnect the platform restarts from unit 0 while the audio is
 *            replayed), with words trimmed to the new ones
 * finish()   {"cmd":"end"} after the audio, then the final frame
 * </pre>
 *
 * Failure before end (connect, handshake, transport, error frame, more than 10 MB of audio): the
 * socket is closed and finish() answers FALLBACK; the caller evaluates over REST with the same
 * tokenId, which does not conflict because the platform registers the idempotency key only when the
 * evaluation runs. Failure after end: a new connection with the same tokenId replays the parameter
 * frame, all audio and end, at most {@link #MAX_RECONNECTS} times, then finish() answers ERROR
 * (errId 20009 for retryable causes); the platform answers a replay with the first result and
 * bills once.
 */
public final class RealtimeRound {
    /** Largest audio frame sent (DESIGN 2.5; the platform closes frames above 128 KB). */
    public static final int MAX_FRAME_BYTES = 32000;
    /** Audio limit of one round on the platform. */
    public static final long MAX_ROUND_BYTES = 10L * 1024 * 1024;
    /** New connections after a failure after end. */
    public static final int MAX_RECONNECTS = 2;
    /** Protocol ping when nothing was sent for this long before end. */
    public static final long PING_INTERVAL_MS = 15000;
    /**
     * Inbound cap from end on: the final frame carries the whole result (42 KB for the 25
     * characters of the customer sentence), so the 128 KB cap of the streaming phase would refuse
     * long paragraphs. Same cap as a REST answer ({@link UrlConnectionTransport#MAX_BODY}).
     */
    public static final int FINAL_MAX_MESSAGE_BYTES = UrlConnectionTransport.MAX_BODY;
    static final String END_FRAME = "{\"cmd\":\"end\"}";

    private static final Object END = new Object();
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();
    private static final int NEW = 0;
    private static final int STREAMING = 1;
    private static final int ENDING = 2;
    private static final int DONE = 3;
    private static final int FAILED = 4;
    private static final int CANCELLED = 5;

    /** Receives progress on a connection's reader thread. */
    public interface Listener {
        /**
         * A progress frame with newly read units.
         *
         * @param resultJson the text of the frame's {@code result} object, unchanged except that
         *                   {@code words} keeps only the items not delivered before
         * @param recordId the frame's top-level {@code recordId}, "" when absent
         */
        void onProgress(String resultJson, String recordId);
    }

    /** Parameters of a round. */
    public static final class Config {
        /** REST base, http or https; ws or wss is derived from it. */
        public String baseUrl = CompatConfig.DEFAULT_BASE_URL;
        public String coreType;
        public String appKey;
        public String secretKey;
        public String tokenId;
        /** Form fields of the REST request (DESIGN 6.1). */
        public Map<String, String> fields = new LinkedHashMap<String, String>();
        public int connectTimeoutMs = CompatConfig.DEFAULT_CONNECT_TIMEOUT_MS;
        public int readTimeoutMs = CompatConfig.DEFAULT_READ_TIMEOUT_MS;
        /** Deadline after end for the result, reconnects included. */
        public long totalTimeoutMs = CompatConfig.DEFAULT_TOTAL_TIMEOUT_MS;
    }

    /** What finish() found. */
    public static final class Outcome {
        /** {@link #body} is the final frame, the same body as the REST answer. */
        public static final int RESULT = 1;
        /** Failure after end, {@link #error} says why. */
        public static final int ERROR = 2;
        /** Failure before end: evaluate over REST with the same tokenId. */
        public static final int FALLBACK = 3;
        public static final int CANCELLED = 4;

        public final int kind;
        public final String body;
        public final CompatError error;
        public final String reason;
        /** Connections opened in this round. */
        public final int connections;
        public final boolean replayed;

        private Outcome(int kind, String body, CompatError error, String reason, int connections, boolean replayed) {
            this.kind = kind;
            this.body = body;
            this.error = error;
            this.reason = reason;
            this.connections = connections;
            this.replayed = replayed;
        }
    }

    private static final class Link {
        final WsConnection ws;
        final int gen;

        Link(WsConnection ws, int gen) {
            this.ws = ws;
            this.gen = gen;
        }
    }

    private final Config config;
    private final Listener listener;
    private final CancelToken token;
    private final String paramFrame;
    private final Map<String, String> headers;
    private final Object lock = new Object();
    private volatile long lastSendMs = now();

    // guarded by lock
    private final ArrayDeque<Object> queue = new ArrayDeque<Object>();
    /** Every audio frame sent in this round, replayed after a failure after end. */
    private final ArrayList<byte[]> sent = new ArrayList<byte[]>();
    private long audioBytes;
    private boolean endQueued;
    private int phase = NEW;
    private Outcome outcome;
    private WsConnection conn;
    private int generation;
    private int connections;
    private long deadlineMs;
    private String finalBody;
    private boolean finalReplayed;
    private CompatError failure;
    /** Units whose words were delivered, by absolute index [0, delivered). Kept across reconnects. */
    private long delivered;

    public RealtimeRound(Config config, Listener listener, CancelToken token) {
        this.config = config;
        this.listener = listener;
        this.token = token != null ? token : new CancelToken();
        this.paramFrame = paramFrame(config.fields, config.tokenId);
        Map<String, String> h = new LinkedHashMap<String, String>();
        h.put("User-Agent", CompatConfig.USER_AGENT);
        h.put("X-Yugu-SDK", CompatConfig.USER_AGENT);
        this.headers = Collections.unmodifiableMap(h);
        // cancelling the session (cancel, cancle, recycle, skegn_cancel) closes the channel too
        this.token.onCancel(new Closeable() {
            @Override
            public void close() {
                cancel();
            }
        });
    }

    // ================================================================ wire format

    /** REST form fields as JSON strings, then {@code "realtime_feedback":true} and the idempotency key. */
    static String paramFrame(Map<String, String> fields, String tokenId) {
        StringBuilder sb = new StringBuilder(256).append('{');
        if (fields != null) {
            for (Map.Entry<String, String> e : fields.entrySet()) {
                String k = e.getKey();
                String v = e.getValue();
                // the platform reads cmd, end and data as commands and takes its own realtime_feedback and key
                if (k == null || v == null || v.isEmpty() || "realtime_feedback".equals(k) || "idempotencyKey".equals(k)
                        || "cmd".equals(k) || "end".equals(k) || "data".equals(k)) {
                    continue;
                }
                sb.append(RawJson.quote(k)).append(':').append(RawJson.quote(v)).append(',');
            }
        }
        return sb.append("\"realtime_feedback\":true,\"idempotencyKey\":").append(RawJson.quote(tokenId)).append('}')
                .toString();
    }

    /** ws or wss base of a REST base: https to wss, http to ws (DESIGN 14.3). */
    static String wsBase(String restBase) {
        String b = restBase.trim();
        while (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        String lower = b.toLowerCase(Locale.ROOT);
        if (lower.startsWith("https://")) {
            return "wss://" + b.substring(8);
        }
        if (lower.startsWith("http://")) {
            return "ws://" + b.substring(7);
        }
        if (lower.startsWith("wss://") || lower.startsWith("ws://")) {
            return b;
        }
        throw new IllegalArgumentException("base URL needs http or https");
    }

    /**
     * Handshake URL {@code ws(s)://base/{coreType}?...&signature=...}: the signature covers every
     * query parameter (DESIGN 5.2), values are percent-encoded (RFC 3986).
     */
    static String handshakeUrl(String restBase, String coreType, Map<String, String> query, String secretKey) {
        LinkedHashMap<String, String> q = new LinkedHashMap<String, String>(query);
        q.remove("signature");
        q.put("signature", Signer.sign(q, secretKey));
        StringBuilder sb = new StringBuilder(256).append(wsBase(restBase)).append('/').append(coreType);
        char sep = '?';
        for (Map.Entry<String, String> e : q.entrySet()) {
            sb.append(sep).append(percentEncode(e.getKey())).append('=').append(percentEncode(e.getValue()));
            sep = '&';
        }
        return sb.toString();
    }

    static String percentEncode(String s) {
        byte[] b = s.getBytes(Codec.UTF_8);
        StringBuilder sb = new StringBuilder(b.length * 3);
        for (byte x : b) {
            int c = x & 0xFF;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' || c == '.'
                    || c == '_' || c == '~') {
                sb.append((char) c);
            } else {
                sb.append('%').append(HEX[c >> 4]).append(HEX[c & 15]);
            }
        }
        return sb.toString();
    }

    /** A text frame of the compat channel. */
    static final class Frame {
        static final int OTHER = 0;
        /** {@code {"eof":0,"recordId":"","result":{...}}} without event. */
        static final int PROGRESS = 1;
        /** {@code {"recordId","eof":1,"result"}}: the same body as the REST answer. */
        static final int FINAL = 2;
        /** {@code {"event":"error","code","message"}}. */
        static final int ERROR = 3;
        /** connected, started, pong and other events. */
        static final int EVENT = 4;

        int kind = OTHER;
        String event;
        /** Text of {@code result}. */
        String result;
        String recordId = "";
        /** Span of the {@code result.words} array inside {@link #result}, null when absent. */
        int[] wordsSpan;
        /** Element spans of {@code result.words}; empty when absent. */
        List<int[]> words = Collections.emptyList();
        long readUnits = -1;
        int code;
        String message;
        boolean replayed;

        /** {@link #result} with {@code words} reduced to the items from index {@code from} on. */
        String resultWithWordsFrom(int from) {
            if (from <= 0 || wordsSpan == null) {
                return result;
            }
            StringBuilder sb = new StringBuilder(result.length());
            sb.append(result, 0, wordsSpan[0]).append('[');
            for (int i = from; i < words.size(); i++) {
                if (i > from) {
                    sb.append(',');
                }
                int[] e = words.get(i);
                sb.append(result, e[0], e[1]);
            }
            return sb.append(']').append(result, wordsSpan[1], result.length()).toString();
        }

        static Frame parse(String text) {
            Frame f = new Frame();
            Map<String, String> m;
            try {
                m = RawJson.members(text.trim());
            } catch (IllegalArgumentException e) {
                return f;
            }
            String event = m.get("event");
            if (event != null) {
                f.event = RawJson.isString(event) ? RawJson.unquote(event) : event.trim();
                if ("error".equals(f.event)) {
                    f.kind = ERROR;
                    f.code = (int) RawJson.toLong(m.get("code"), 0);
                    String msg = m.get("message");
                    f.message = RawJson.isString(msg) ? RawJson.unquote(msg) : null;
                } else {
                    f.kind = EVENT;
                }
                return f;
            }
            String result = m.get("result");
            if (result == null || "null".equals(result.trim())) {
                return f;
            }
            if (RawJson.toLong(m.get("eof"), -1) == 0) {
                if (!result.trim().startsWith("{")) {
                    return f;
                }
                f.kind = PROGRESS;
                f.result = result.trim();
                String recordId = m.get("recordId");
                f.recordId = RawJson.isString(recordId) ? RawJson.unquote(recordId) : "";
                try {
                    RawJson.ObjectSpan r = RawJson.objectAt(f.result, 0);
                    int[] words = r.members.get("words");
                    if (words != null && f.result.charAt(words[0]) == '[') {
                        f.wordsSpan = words;
                        f.words = RawJson.arrayAt(f.result, words[0]);
                    }
                    int[] read = r.members.get("readUnits");
                    if (read != null) {
                        f.readUnits = RawJson.toLong(f.result.substring(read[0], read[1]), -1);
                    }
                } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
                    f.wordsSpan = null;
                    f.words = Collections.emptyList();
                }
                return f;
            }
            f.kind = FINAL;
            String replayed = m.get("replayed");
            f.replayed = replayed != null && "true".equals(replayed.trim());
            return f;
        }
    }

    // ================================================================ streaming phase

    /** Starts the worker thread: connect, parameter frame, then the audio as it arrives. Once. */
    public void start() {
        synchronized (lock) {
            if (phase != NEW) {
                return;
            }
            phase = STREAMING;
        }
        YLog.i("realtime round tokenId=" + config.tokenId + " over " + wsBase(config.baseUrl) + "/" + config.coreType);
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                stream();
            }
        }, "yugu-stcompat-ws");
        t.setDaemon(true);
        t.start();
    }

    /** Queues PCM (copied); never blocks and never touches the network. Ignored after end or failure. */
    public void audio(byte[] data, int off, int len) {
        if (data == null || len <= 0) {
            return;
        }
        boolean overflow;
        synchronized (lock) {
            if ((phase != NEW && phase != STREAMING) || endQueued) {
                return;
            }
            overflow = audioBytes + len > MAX_ROUND_BYTES;
            if (!overflow) {
                queue.add(Arrays.copyOfRange(data, off, off + len));
                audioBytes += len;
                lock.notifyAll();
            }
        }
        if (overflow) {
            failBeforeEnd("more than " + MAX_ROUND_BYTES + " bytes of audio in one round");
        }
    }

    private void stream() {
        Link link;
        try {
            link = open(WsClient.DEFAULT_MAX_MESSAGE_BYTES);
        } catch (IOException e) {
            failBeforeEnd("cannot open the realtime channel: " + describe(e));
            return;
        }
        if (link == null) {
            return;
        }
        long pingMs = pingIntervalMs();
        while (true) {
            Object item;
            synchronized (lock) {
                while (queue.isEmpty() && phase == STREAMING) {
                    long wait = pingMs - (now() - lastSendMs);
                    if (wait <= 0) {
                        break;
                    }
                    try {
                        lock.wait(wait);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                if (phase != STREAMING) {
                    return;
                }
                item = queue.poll();
            }
            if (Thread.currentThread().isInterrupted()) {
                failBeforeEnd("interrupted");
                return;
            }
            try {
                if (item == END) {
                    break;
                }
                if (item == null) {
                    link.ws.ping();
                    lastSendMs = now();
                } else {
                    sendAudio(link.ws, (byte[]) item);
                }
            } catch (IOException e) {
                failBeforeEnd("sending failed: " + describe(e));
                return;
            }
        }
        synchronized (lock) {
            if (phase != STREAMING) {
                return;
            }
            // from here on a failure is after end: the platform may already evaluate under the key
            phase = ENDING;
            deadlineMs = now() + config.totalTimeoutMs;
            lock.notifyAll();
        }
        link.ws.setMaxMessageBytes(FINAL_MAX_MESSAGE_BYTES);
        try {
            link.ws.sendText(END_FRAME);
        } catch (IOException e) {
            recordFailure(link.gen, ErrorMapper.fromWsException(e));
        }
    }

    private void sendAudio(WsConnection ws, byte[] chunk) throws IOException {
        for (int off = 0; off < chunk.length; off += MAX_FRAME_BYTES) {
            int n = Math.min(MAX_FRAME_BYTES, chunk.length - off);
            byte[] frame = n == chunk.length ? chunk : Arrays.copyOfRange(chunk, off, off + n);
            ws.sendBinary(frame, 0, n);
            synchronized (lock) {
                if (phase != STREAMING) {
                    return;
                }
                sent.add(frame);
            }
        }
        lastSendMs = now();
    }

    long pingIntervalMs() {
        return Math.min(PING_INTERVAL_MS, Math.max(1000L, config.readTimeoutMs / 2L));
    }

    /** New connection with a fresh timestamp, nonce and signature, then the parameter frame; null when the round ended. */
    private Link open(int maxMessageBytes) throws IOException {
        final int gen;
        synchronized (lock) {
            if (phase != STREAMING && phase != ENDING) {
                return null;
            }
            gen = ++generation;
            connections++;
            finalBody = null;
            finalReplayed = false;
            failure = null;
        }
        LinkedHashMap<String, String> q = new LinkedHashMap<String, String>();
        q.put("appKey", config.appKey);
        q.put("timestamp", String.valueOf(System.currentTimeMillis() / 1000L));
        q.put("nonce", Codec.randomHex(8));
        q.put("idempotencyKey", config.tokenId);
        String url = handshakeUrl(config.baseUrl, config.coreType, q, config.secretKey);
        WsConnection ws = CompatConfig.wsConnector().connect(url, headers, config.connectTimeoutMs, config.readTimeoutMs,
                connectionListener(gen));
        ws.setMaxMessageBytes(maxMessageBytes);
        synchronized (lock) {
            if (gen != generation || (phase != STREAMING && phase != ENDING)) {
                closeAsync(ws, false);
                return null;
            }
            conn = ws;
        }
        ws.sendText(paramFrame);
        lastSendMs = now();
        return new Link(ws, gen);
    }

    private WsConnection.Listener connectionListener(final int gen) {
        return new WsConnection.Listener() {
            @Override
            public void onText(String text) {
                onFrame(gen, text);
            }

            @Override
            public void onBinary(byte[] data) {
                YLog.d("realtime binary frame of " + data.length + " bytes ignored");
            }

            @Override
            public void onClosed(int code, String reason) {
                recordFailure(gen, ErrorMapper.fromWsClose(code, reason));
            }

            @Override
            public void onFailure(IOException error) {
                recordFailure(gen, ErrorMapper.fromWsException(error));
            }
        };
    }

    private void onFrame(int gen, String text) {
        Frame f = Frame.parse(text);
        switch (f.kind) {
            case Frame.PROGRESS:
                onProgress(gen, f);
                break;
            case Frame.FINAL:
                synchronized (lock) {
                    if (gen == generation && phase == ENDING && finalBody == null && failure == null) {
                        finalBody = text;
                        finalReplayed = f.replayed;
                        lock.notifyAll();
                        return;
                    }
                }
                YLog.d("realtime final frame outside the end phase ignored");
                break;
            case Frame.ERROR:
                recordFailure(gen, ErrorMapper.fromWsError(f.code, f.message, text));
                break;
            case Frame.EVENT:
                YLog.d("realtime event " + f.event);
                break;
            default:
                YLog.d("realtime frame ignored");
                break;
        }
    }

    /**
     * Delivers the newly read units of a progress frame once each (BRIEF change 2026-10-12): the
     * items of {@code words} cover units [readUnits - words.length, readUnits); items below the
     * units already delivered in this round are dropped (after a reconnect the platform restarts
     * from unit 0 while the audio is replayed) and the frame is skipped when none is left. Frames
     * without words (bytes only, or nothing newly read) are not delivered.
     */
    private void onProgress(int gen, Frame f) {
        int count = f.words.size();
        if (count == 0) {
            return;
        }
        int from;
        long start;
        long before;
        synchronized (lock) {
            if (gen != generation || (phase != STREAMING && phase != ENDING) || finalBody != null) {
                return;
            }
            before = delivered;
            start = f.readUnits >= count ? f.readUnits - count : delivered;
            from = (int) Math.max(0L, Math.min((long) count, delivered - start));
            if (from < count) {
                delivered = Math.max(delivered, start + count);
            }
        }
        if (from >= count) {
            YLog.d("progress dropped: units " + start + " to " + (start + count - 1) + " were delivered before ("
                    + before + " delivered)");
            return;
        }
        try {
            listener.onProgress(f.resultWithWordsFrom(from), f.recordId);
        } catch (RuntimeException e) {
            YLog.w("progress listener failed", e);
        }
    }

    private void recordFailure(int gen, CompatError error) {
        synchronized (lock) {
            if (gen != generation) {
                return;
            }
            if (phase == ENDING) {
                if (failure == null && finalBody == null) {
                    failure = error;
                    lock.notifyAll();
                }
                return;
            }
            if (phase != STREAMING) {
                return;
            }
        }
        failBeforeEnd(error.getMessage());
    }

    private void failBeforeEnd(String reason) {
        WsConnection c;
        int n;
        synchronized (lock) {
            if (phase != NEW && phase != STREAMING) {
                return;
            }
            phase = FAILED;
            n = connections;
            outcome = new Outcome(Outcome.FALLBACK, null, null, reason, n, false);
            queue.clear();
            sent.clear();
            audioBytes = 0;
            c = conn;
            conn = null;
            generation++;
            lock.notifyAll();
        }
        YLog.w("realtime channel failed before end (" + reason + "); tokenId " + config.tokenId
                + " is evaluated over REST instead");
        closeAsync(c, true);
    }

    // ================================================================ end phase

    /**
     * Sends end after the queued audio and waits for the final frame, reconnecting after end when
     * the connection fails. Blocks; call it off the main thread.
     */
    public Outcome finish() {
        synchronized (lock) {
            if (phase == NEW) {
                phase = FAILED;
                outcome = new Outcome(Outcome.FALLBACK, null, null, "realtime channel not started", 0, false);
                return outcome;
            }
            if (phase == STREAMING && !endQueued) {
                endQueued = true;
                queue.add(END);
                lock.notifyAll();
            }
            while (phase == STREAMING) {
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return new Outcome(Outcome.CANCELLED, null, null, "interrupted", connections, false);
                }
            }
            if (phase != ENDING) {
                return outcome;
            }
        }
        return afterEnd();
    }

    /**
     * autoRetry after an ERROR outcome: the same round (same tokenId, parameters and audio) on a new
     * connection, with a fresh reconnect budget and deadline.
     */
    public Outcome resubmit() {
        synchronized (lock) {
            if (phase != DONE || outcome == null || outcome.kind != Outcome.ERROR) {
                return outcome;
            }
            phase = ENDING;
            outcome = null;
            deadlineMs = now() + config.totalTimeoutMs;
        }
        replay();
        return afterEnd();
    }

    private Outcome afterEnd() {
        int reconnects = 0;
        while (true) {
            String body;
            boolean replayed;
            CompatError cause;
            WsConnection c;
            int n;
            synchronized (lock) {
                while (phase == ENDING && finalBody == null && failure == null) {
                    long left = deadlineMs - now();
                    if (left <= 0) {
                        failure = CompatError.local(ErrorTable.TIMEOUT, "no realtime result within "
                                + config.totalTimeoutMs + " ms after end", null);
                        break;
                    }
                    try {
                        lock.wait(left);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return new Outcome(Outcome.CANCELLED, null, null, "interrupted", connections, false);
                    }
                }
                if (phase != ENDING) {
                    return outcome;
                }
                body = finalBody;
                replayed = finalReplayed;
                cause = failure;
                c = conn;
                conn = null;
                n = connections;
                if (body != null) {
                    phase = DONE;
                    outcome = new Outcome(Outcome.RESULT, body, null, null, n, replayed);
                }
            }
            if (body != null) {
                closeAsync(c, true);
                return new Outcome(Outcome.RESULT, body, null, null, n, replayed);
            }
            closeAsync(c, false);
            cause.setAttempts(n);
            if (!cause.retryable || reconnects >= MAX_RECONNECTS) {
                return end(cause, n);
            }
            reconnects++;
            long delay = CompatConfig.retryPolicy().delayMs(reconnects, CompatConfig.random().nextDouble(), cause.retryAfterMs);
            if (now() + delay >= deadlineMs) {
                return end(cause, n);
            }
            YLog.w("realtime connection failed after end (" + cause.describe() + " " + cause.getMessage()
                    + "); reconnect " + reconnects + "/" + MAX_RECONNECTS + " in " + delay + " ms with the same tokenId "
                    + config.tokenId);
            if (!CompatConfig.sleeper().sleep(token, delay)) {
                synchronized (lock) {
                    return outcome != null ? outcome
                            : new Outcome(Outcome.CANCELLED, null, null, "cancelled", connections, false);
                }
            }
            replay();
        }
    }

    /** Opens a new connection and resends the parameter frame, every audio frame and end. */
    private void replay() {
        Link link = null;
        try {
            link = open(FINAL_MAX_MESSAGE_BYTES);
            if (link == null) {
                return;
            }
            List<byte[]> frames;
            synchronized (lock) {
                frames = new ArrayList<byte[]>(sent);
            }
            for (byte[] f : frames) {
                link.ws.sendBinary(f, 0, f.length);
            }
            link.ws.sendText(END_FRAME);
            lastSendMs = now();
        } catch (IOException e) {
            CompatError error = ErrorMapper.fromWsException(e);
            synchronized (lock) {
                if (phase == ENDING && finalBody == null && failure == null && (link == null || link.gen == generation)) {
                    failure = error;
                    lock.notifyAll();
                }
            }
        }
    }

    private Outcome end(CompatError cause, int n) {
        synchronized (lock) {
            if (phase != ENDING) {
                return outcome;
            }
            phase = DONE;
            outcome = new Outcome(Outcome.ERROR, null, cause, cause.getMessage(), n, false);
            lock.notifyAll();
            return outcome;
        }
    }

    // ================================================================ cancel

    /**
     * Closes the channel without end: nothing is evaluated or billed for an unfinished round.
     * Also releases the replay buffer once the outcome was used. Idempotent, never blocks.
     */
    public void cancel() {
        WsConnection c;
        synchronized (lock) {
            if (phase == CANCELLED) {
                return;
            }
            if (phase == NEW || phase == STREAMING || phase == ENDING) {
                outcome = new Outcome(Outcome.CANCELLED, null, null, "cancelled", connections, false);
            }
            phase = CANCELLED;
            queue.clear();
            sent.clear();
            audioBytes = 0;
            c = conn;
            conn = null;
            generation++;
            lock.notifyAll();
        }
        closeAsync(c, false);
    }

    /** Closes off the calling thread: socket I/O on the main thread is not allowed on Android. */
    private static void closeAsync(final WsConnection c, final boolean graceful) {
        if (c == null) {
            return;
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                if (graceful) {
                    c.close(1000, "");
                } else {
                    c.abort();
                }
            }
        }, "yugu-stcompat-ws-close");
        t.setDaemon(true);
        t.start();
    }

    private static String describe(IOException e) {
        return e.getClass().getSimpleName() + (e.getMessage() == null ? "" : " " + e.getMessage());
    }

    private static long now() {
        return System.nanoTime() / 1000000L;
    }
}
