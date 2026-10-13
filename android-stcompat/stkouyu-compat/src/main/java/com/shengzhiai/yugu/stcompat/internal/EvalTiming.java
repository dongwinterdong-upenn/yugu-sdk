// SPDX-License-Identifier: Apache-2.0
package com.shengzhiai.yugu.stcompat.internal;

import java.util.Locale;

/**
 * Phase times of one evaluation on the monotonic {@link System#nanoTime()} clock (SDK 2.1.1,
 * integrator requirement R1: see which step is slow). The engine, the realtime round and the REST
 * client mark the phases; at DEBUG every phase is logged as it happens ({@code stage ...}), at INFO
 * one summary line ({@code timing ...}) is logged right before the result callback:
 *
 * <pre>
 * timing tokenId=.. recordId=eval_7954d149c40a mode=ws audio=4.48s afterStop=1830ms upload=95ms wait=1490ms
 *   download=180ms parse=12ms deliver=3ms handshake=120ms queued=6400B final=41210B wire=6120B deflate
 *   server.total=1410ms server.engine=1205ms server.platform=205ms
 * </pre>
 *
 * <ul>
 * <li>recordId (SDK 2.2.0): the platform's record of the result, so the line can be matched with the
 * platform's per-record timing; {@code -} when the evaluation ended with an error JSON</li>
 * <li>mode: {@code ws} streamed while recording, {@code rest} uploaded after stop,
 * {@code ws>rest} streamed but the channel failed before end, so the recording was uploaded</li>
 * <li>afterStop: stop pressed (stopRecord, VAD end, duration, 300 s limit; for existsAudioTrans and
 * skegn_stop the call) to the start of the result callback</li>
 * <li>upload: stop to the last byte sent (WebSocket: end frame, REST: request body of the last
 * attempt); wait: from there to the first byte of the result; download: first to last byte of
 * the result, decompression included; parse: building the result JSON; deliver: reaching the
 * main thread</li>
 * <li>sizes: queued (audio not yet sent when end was queued), final or body (result bytes after
 * decompression), wire (as received, with deflate or gzip when compressed), sent (REST request)</li>
 * <li>server.*: the platform's own total, engine and platform times, from the result's timing
 * object or the Server-Timing header</li>
 * </ul>
 *
 * Unknown values are printed as {@code -}. Never holds secretKey, signatures or audio.
 */
public final class EvalTiming {
    public static final String MODE_REST = "rest";
    public static final String MODE_WS = "ws";
    public static final String MODE_FALLBACK = "ws>rest";

    private final String tokenId;
    private final long t0 = now();

    // guarded by this; times are System.nanoTime(), 0 when not reached
    private String recordId;
    private String mode = MODE_REST;
    private String fallbackReason;
    private long audioBytes = -1;
    private double audioSeconds = -1;
    private long stopAt;
    private int wsConnections;
    private long wsConnectAt;
    private long wsHandshakeAt;
    private long wsParamAt;
    private long wsFirstAudioAt;
    private long wsEndAt;
    private long wsQueuedBytes = -1;
    private long wsFinalFirstByteAt;
    private long wsFinalAt;
    private long wsFinalBytes = -1;
    private long wsFinalWireBytes = -1;
    private boolean wsFinalCompressed;
    private int restAttempts;
    private int restStatus;
    private long restStartAt;
    private long restUploadAt;
    private long restHeadersAt;
    private long restBodyAt;
    private long restUploadBytes = -1;
    private long restWireBytes = -1;
    private long restBodyBytes = -1;
    private String restEncoding;
    private ServerTiming serverHeader;
    private ServerTiming serverBody;
    private long parsedAt;
    private long deliveredAt;
    private int errId;
    private boolean replayed;

    public EvalTiming(String tokenId) {
        this.tokenId = tokenId;
    }

    static long now() {
        return System.nanoTime();
    }

    // ================================================================ session

    /** Stop pressed, or the evaluation call for files. The first call counts. */
    public void stop() {
        long at = now();
        synchronized (this) {
            if (stopAt != 0) {
                return;
            }
            stopAt = at;
        }
        stage("stop", null, at);
    }

    /** Audio of a recording or stream session: 16 kHz mono 16 bit PCM bytes. */
    public synchronized void audioPcm(long pcmBytes) {
        audioBytes = pcmBytes;
        audioSeconds = pcmBytes / (double) Wav.BYTES_PER_SECOND;
    }

    /** Audio of a file or SkEgn session; {@code seconds} below 0 when unknown. */
    public synchronized void audioFile(long bytes, double seconds) {
        audioBytes = bytes;
        audioSeconds = seconds;
    }

    /** The session streams over the realtime channel. */
    public synchronized void streaming() {
        mode = MODE_WS;
    }

    /** The realtime channel failed before end: the recording goes over REST. */
    public void fallback(String reason) {
        long at = now();
        synchronized (this) {
            mode = MODE_FALLBACK;
            fallbackReason = reason;
        }
        stage("ws.fallback", reason, at);
    }

    // ================================================================ realtime channel

    public void wsConnectStart() {
        long at = now();
        synchronized (this) {
            wsConnections++;
            if (wsConnectAt == 0) {
                wsConnectAt = at;
            }
        }
        stage("ws.connect", null, at);
    }

    /** Opening handshake done; {@code extensions} as agreed, "" for none. */
    public void wsHandshakeDone(String extensions) {
        long at = now();
        synchronized (this) {
            if (wsHandshakeAt == 0) {
                wsHandshakeAt = at;
            }
        }
        stage("ws.handshake", extensions == null || extensions.isEmpty() ? "no extension" : extensions, at);
    }

    public void wsParamSent() {
        long at = now();
        synchronized (this) {
            if (wsParamAt != 0) {
                return;
            }
            wsParamAt = at;
        }
        stage("ws.param", null, at);
    }

    /** An audio frame was sent; the first one counts. */
    public void wsAudioSent() {
        long at = now();
        synchronized (this) {
            if (wsFirstAudioAt != 0) {
                return;
            }
            wsFirstAudioAt = at;
        }
        stage("ws.audio.first", null, at);
    }

    /** End was queued after the audio; {@code queuedBytes} of audio were not sent yet. */
    public void wsEndQueued(long queuedBytes) {
        long at = now();
        synchronized (this) {
            if (wsQueuedBytes >= 0) {
                return;
            }
            wsQueuedBytes = queuedBytes;
        }
        stage("ws.end.queued", "queued=" + queuedBytes + "B", at);
    }

    /** The end frame was sent; the first one counts (a reconnect resends it). */
    public void wsEndSent() {
        long at = now();
        synchronized (this) {
            if (wsEndAt != 0) {
                return;
            }
            wsEndAt = at;
        }
        stage("ws.end", null, at);
    }

    /** The final frame was accepted; {@code in} null when the connection does not track arrivals. */
    public void wsFinal(WsConnection.Inbound in, long textBytes) {
        long at = now();
        long first;
        long done;
        String detail;
        synchronized (this) {
            first = in != null ? in.firstByteNanos : at;
            done = in != null ? in.completeNanos : at;
            wsFinalFirstByteAt = first;
            wsFinalAt = done;
            wsFinalBytes = in != null ? in.dataBytes : textBytes;
            wsFinalWireBytes = in != null ? in.wireBytes : textBytes;
            wsFinalCompressed = in != null && in.compressed;
            detail = "bytes=" + wsFinalBytes + " wire=" + wsFinalWireBytes + (wsFinalCompressed ? " deflate" : "");
        }
        stage("ws.final.first", null, first);
        stage("ws.final", detail, done);
    }

    // ================================================================ REST

    /** An attempt starts (1 for the first). */
    public void restAttempt(int attempt) {
        long at = now();
        synchronized (this) {
            restAttempts = attempt;
            restStartAt = at;
            restUploadAt = 0;
            restHeadersAt = 0;
            restBodyAt = 0;
        }
        stage("rest.request", "attempt=" + attempt, at);
    }

    /** The answer of the current attempt, with the transport's metrics when it has them. */
    public void restResponse(HttpTransport.Response r) {
        long at = now();
        HttpTransport.Metrics m = r.metrics;
        String header = r.header("Server-Timing");
        String upload;
        String body;
        long uploadAt;
        long headersAt;
        long bodyAt;
        synchronized (this) {
            restStatus = r.status;
            if (m != null) {
                restUploadAt = m.uploadDoneNanos;
                restHeadersAt = m.headersNanos != 0 ? m.headersNanos : at;
                restBodyAt = m.bodyDoneNanos != 0 ? m.bodyDoneNanos : at;
                restUploadBytes = m.uploadBytes;
                restWireBytes = m.wireBytes;
                restBodyBytes = m.bodyBytes;
                restEncoding = m.contentEncoding;
            } else {
                restHeadersAt = at;
                restBodyAt = at;
                restUploadBytes = -1;
                restWireBytes = -1;
                restBodyBytes = r.body.getBytes(Codec.UTF_8).length;
                restEncoding = null;
            }
            ServerTiming st = ServerTiming.fromHeader(header);
            if (st != null) {
                serverHeader = st;
            }
            uploadAt = restUploadAt;
            headersAt = restHeadersAt;
            bodyAt = restBodyAt;
            upload = "bytes=" + restUploadBytes;
            body = "bytes=" + restBodyBytes + " wire=" + restWireBytes + (restEncoding != null ? " " + restEncoding : "");
        }
        if (uploadAt != 0) {
            stage("rest.uploaded", upload, uploadAt);
        }
        stage("rest.headers", "status=" + r.status + (header != null ? " server-timing=\"" + header + "\"" : ""), headersAt);
        stage("rest.body", body, bodyAt);
    }

    // ================================================================ result

    /** The result's timing object, null when the result had none. */
    public synchronized void serverBody(ServerTiming t) {
        if (t != null) {
            serverBody = t;
        }
    }

    public synchronized void replayed(boolean r) {
        replayed |= r;
    }

    /** The result's recordId, null or empty when it has none (printed as {@code -}). */
    public synchronized void recordId(String id) {
        recordId = id == null || id.trim().isEmpty() ? null : id.trim();
    }

    /** The result JSON for the app was built. */
    public void parsed() {
        long at = now();
        synchronized (this) {
            parsedAt = at;
        }
        stage("parsed", null, at);
    }

    /** The evaluation ends with an error JSON. */
    public synchronized void error(int id) {
        errId = id;
    }

    /** The result callback is about to run on the main thread. */
    public void delivered() {
        long at = now();
        synchronized (this) {
            deliveredAt = at;
        }
        stage("delivered", null, at);
    }

    /** Logs the summary at INFO and the engine stages of the platform at DEBUG. */
    public void log() {
        if (YLog.isLoggable(YLog.INFO)) {
            YLog.i(summary());
        }
        if (YLog.isLoggable(YLog.DEBUG)) {
            String stages = engineStages();
            if (!stages.isEmpty()) {
                YLog.d("timing tokenId=" + tokenId + " engineStages " + stages);
            }
        }
    }

    /** The INFO summary line, see the class comment. */
    public synchronized String summary() {
        boolean ws = MODE_WS.equals(mode);
        long sent = ws ? wsEndAt : restUploadAt;
        long first = ws ? wsFinalFirstByteAt : restHeadersAt;
        long done = ws ? wsFinalAt : restBodyAt;
        StringBuilder sb = new StringBuilder(320);
        sb.append("timing tokenId=").append(tokenId).append(" recordId=").append(recordId != null ? recordId : "-");
        sb.append(" mode=").append(mode);
        sb.append(" audio=").append(audioSeconds >= 0 ? String.format(Locale.ROOT, "%.2fs", audioSeconds) : "-");
        sb.append(" afterStop=").append(ms(stopAt, deliveredAt));
        sb.append(" upload=").append(ms(stopAt, sent));
        sb.append(" wait=").append(ms(sent, first));
        sb.append(" download=").append(ms(first, done));
        sb.append(" parse=").append(ms(done, parsedAt));
        sb.append(" deliver=").append(ms(parsedAt, deliveredAt));
        if (ws) {
            sb.append(" handshake=").append(ms(wsConnectAt, wsHandshakeAt));
            sb.append(" queued=").append(bytes(wsQueuedBytes));
            sb.append(" final=").append(bytes(wsFinalBytes)).append(" wire=").append(bytes(wsFinalWireBytes));
            if (wsFinalCompressed) {
                sb.append(" deflate");
            }
            if (wsConnections > 1) {
                sb.append(" connections=").append(wsConnections);
            }
        } else {
            sb.append(" attempts=").append(restAttempts);
            sb.append(" sent=").append(bytes(restUploadBytes));
            sb.append(" body=").append(bytes(restBodyBytes)).append(" wire=").append(bytes(restWireBytes));
            if (restEncoding != null) {
                sb.append(' ').append(restEncoding);
            }
        }
        ServerTiming server = serverBody != null ? serverBody : serverHeader;
        if (server != null && !server.describe().isEmpty()) {
            sb.append(' ').append(server.describe());
        }
        if (replayed) {
            sb.append(" replayed");
        }
        if (errId != 0) {
            sb.append(" errId=").append(errId);
        }
        if (fallbackReason != null) {
            sb.append(" fallback=\"").append(fallbackReason).append('"');
        }
        return sb.toString();
    }

    /** engineStages of the platform's timing object, "" when absent. */
    public synchronized String engineStages() {
        return serverBody == null ? "" : serverBody.describeStages();
    }

    private void stage(String name, String detail, long at) {
        if (YLog.isLoggable(YLog.DEBUG)) {
            YLog.d("stage tokenId=" + tokenId + " +" + (Math.max(0, at - t0) / 1000000L) + "ms " + name
                    + (detail == null ? "" : " " + detail));
        }
    }

    /** Milliseconds between two marks, {@code -} when one is missing; marks taken on two threads may cross by microseconds. */
    private static String ms(long from, long to) {
        if (from == 0 || to == 0) {
            return "-";
        }
        return (Math.max(0, to - from) / 1000000L) + "ms";
    }

    private static String bytes(long b) {
        return b < 0 ? "-" : b + "B";
    }
}
