package com.shengzhiai.yugu;

import com.fasterxml.jackson.databind.JsonNode;
import com.shengzhiai.yugu.audio.AudioAnalysis;
import com.shengzhiai.yugu.audio.AudioPrecheck;
import com.shengzhiai.yugu.errors.ErrorTable;
import com.shengzhiai.yugu.errors.WarningCode;
import com.shengzhiai.yugu.errors.YuguErrors;
import com.shengzhiai.yugu.errors.YuguException;
import com.shengzhiai.yugu.internal.Auth;
import com.shengzhiai.yugu.internal.HttpEngine;
import com.shengzhiai.yugu.internal.Ids;
import com.shengzhiai.yugu.internal.Json;
import com.shengzhiai.yugu.internal.Log;
import com.shengzhiai.yugu.internal.Redact;
import com.shengzhiai.yugu.internal.ResultParser;
import com.shengzhiai.yugu.internal.SerialExecutor;
import com.shengzhiai.yugu.internal.WsTransport;
import com.shengzhiai.yugu.model.EvalResult;
import com.shengzhiai.yugu.model.StreamPartial;
import com.shengzhiai.yugu.model.Warning;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * One streaming evaluation over WebSocket (native {@code /api/v1/ws/evaluate} or compat
 * {@code /{coreType}}), with reconnect, audio buffering, heartbeat and a result timeout.
 *
 * <p>Typical use:
 * <pre>{@code
 * StreamSession s = client.streamEvaluate(config, listener);
 * for (byte[] frame : pcmFrames) {   // 16 kHz 16 bit mono PCM, 640 bytes = 20 ms recommended
 *     s.sendAudio(frame);
 * }
 * s.end();
 * EvalResult r = s.result().get(60, TimeUnit.SECONDS);
 * }</pre>
 *
 * <p>All methods are thread safe and return at once; work happens on the session's own serial
 * executor. {@link #cancel()} and {@link #close()} are idempotent.
 */
public final class StreamSession implements AutoCloseable {
    private static final String TAG = "stream";
    private static final int NORMAL_CLOSURE = 1000;
    private static final int ABNORMAL_CLOSURE = 1006;
    private static final long CLOSE_GRACE_MS = 1000;
    private static final String END_FRAME = "{\"cmd\":\"end\"}";
    /** Sample rate assumed for raw PCM sent to a stream. */
    public static final int SAMPLE_RATE = 16000;
    /**
     * Largest audio frame the SDK sends, 32000 bytes (1 s of 16 kHz audio). Longer {@link #sendAudio}
     * chunks are split, because the platform closes connections with 1009 when a frame exceeds 128 KB.
     */
    public static final int MAX_FRAME_BYTES = 32000;
    /** A session makes at most this many times {@code maxAttempts} reconnects in total. */
    public static final int SESSION_RECONNECT_FACTOR = 3;

    enum Kind { NATIVE, COMPAT }

    private final StreamContext ctx;
    private final Log log;
    private final Kind kind;
    private final String path;
    private final String startFrame;
    private final String sessionId;
    private final String idempotencyKey;
    private final StreamListener listener;
    private final ReconnectPolicy reconnect;
    private final AudioBufferPolicy bufferPolicy;
    private final long resultTimeoutMs;
    private final long replayLimit;
    private final SerialExecutor serial;
    private final CompletableFuture<EvalResult> resultFuture = new CompletableFuture<>();
    private final CountDownLatch closedLatch = new CountDownLatch(1);
    private final AudioPrecheck.Accumulator precheck = AudioPrecheck.accumulator(SAMPLE_RATE);

    private volatile SessionState state = SessionState.IDLE;
    /** Set by end() on the caller thread; only used to reject sendAudio() after end(). */
    private volatile boolean endRequested;

    // ---- fields below are only touched on the serial executor
    private int generation;
    private WsTransport.Connection conn;
    private boolean everStarted;
    private int reconnectAttempt;
    private int totalReconnects;
    private int connections;
    private ScheduledFuture<?> handshakeTimer;
    private ScheduledFuture<?> resultTimer;
    private ScheduledFuture<?> heartbeatTimer;
    private ScheduledFuture<?> reconnectTimer;
    private long lastInboundNs;
    private long lastPingNs;
    private int serverCloseCode = -1;
    private final List<byte[]> replay = new ArrayList<>();
    private long replayBytes;
    private boolean replayOverflow;
    private final ArrayDeque<byte[]> pending = new ArrayDeque<>();
    private long pendingBytes;
    private long totalBytes;
    private long bytesInServerSession;
    private boolean precheckDone;
    /**
     * Set when the end() task ran on the serial executor, after every audio chunk sent before end().
     * Only this flag decides when the end frame goes out, so no audio can follow it.
     */
    private boolean endAccepted;
    private List<Warning> localWarnings = Collections.emptyList();

    StreamSession(StreamContext ctx, Kind kind, String path, Map<String, Object> startFields, StreamListener listener,
                  StreamOptions options) {
        this.ctx = ctx;
        this.log = ctx.log;
        this.kind = kind;
        this.path = path;
        this.listener = listener;
        StreamOptions so = options == null ? StreamOptions.none() : options;
        this.idempotencyKey = Ids.resolve(so.getIdempotencyKey(), ctx.options.isAutoIdempotencyKey());
        ReconnectPolicy rp = so.getReconnect() != null ? so.getReconnect() : ctx.options.getReconnect();
        if (idempotencyKey == null && rp.isEnabled()) {
            log.debug(TAG, "no idempotency key, reconnect disabled for this session");
            rp = ReconnectPolicy.disabled();
        }
        this.reconnect = rp;
        this.bufferPolicy = so.getBufferPolicy() != null ? so.getBufferPolicy() : ctx.options.getAudioBufferPolicy();
        this.resultTimeoutMs = so.getResultTimeoutMs() != null ? so.getResultTimeoutMs() : ctx.options.getResultTimeoutMs();
        this.replayLimit = so.getReplayBufferBytes() != null ? so.getReplayBufferBytes() : StreamOptions.DEFAULT_REPLAY_BUFFER_BYTES;
        if (resultTimeoutMs <= 0 || replayLimit <= 0) {
            throw YuguErrors.local(ErrorTable.INVALID_ARGUMENT, "resultTimeoutMs and replayBufferBytes must be > 0", null);
        }
        Map<String, Object> frame = new LinkedHashMap<>();
        if (kind == Kind.NATIVE) {
            frame.put("cmd", "start");
        }
        frame.putAll(startFields);
        if (idempotencyKey != null) {
            frame.put("idempotencyKey", idempotencyKey);
        }
        this.startFrame = Json.write(frame);
        this.sessionId = Ids.sessionId();
        this.serial = new SerialExecutor(ctx.callbackPool, t -> log.error(TAG, sessionId + " internal error", t));
    }

    void start() {
        serial.execute(this::connect);
    }

    // =====================================================================================
    // public API
    // =====================================================================================

    /** @return id of this session, used in logs and metrics */
    public String getSessionId() {
        return sessionId;
    }

    /** @return idempotency key shared by every connection of the session, null when none */
    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    /** @return the state announced by the last {@code onStateChanged} */
    public SessionState getState() {
        return state;
    }

    /** @return true until the session reached COMPLETED, FAILED, CANCELLED or CLOSED */
    public boolean isActive() {
        return !state.isTerminal();
    }

    /** @return buffer policy of this session */
    public AudioBufferPolicy getBufferPolicy() {
        return bufferPolicy;
    }

    /**
     * Sends audio: 16 kHz 16 bit little endian mono PCM, chunks of any length (split into frames of at
     * most {@link #MAX_FRAME_BYTES}). Audio sent before the server session started or while reconnecting
     * is queued according to the {@link AudioBufferPolicy}. Calls after the session ended are ignored.
     *
     * @param pcm samples
     * @throws YuguException {@code InvalidParameterException} 90010 for empty audio,
     *                       {@code IllegalSessionStateException} 90009 after {@link #end()}
     */
    public void sendAudio(byte[] pcm) throws YuguException {
        if (pcm == null) {
            throw YuguErrors.local(ErrorTable.INVALID_ARGUMENT, "audio chunk is null", null);
        }
        sendAudio(pcm, 0, pcm.length);
    }

    /**
     * @param pcm    buffer
     * @param offset first byte
     * @param length byte count
     * @throws YuguException see {@link #sendAudio(byte[])}
     */
    public void sendAudio(byte[] pcm, int offset, int length) throws YuguException {
        if (pcm == null || offset < 0 || length < 0 || offset + length > pcm.length) {
            throw YuguErrors.local(ErrorTable.INVALID_ARGUMENT, "invalid audio chunk bounds", null);
        }
        if (length == 0) {
            throw YuguErrors.local(ErrorTable.INVALID_ARGUMENT, "audio chunk is empty", null);
        }
        if (endRequested) {
            throw YuguErrors.local(ErrorTable.INVALID_STATE, "sendAudio after end()", null);
        }
        if (state.isTerminal()) {
            log.debug(TAG, sessionId + " sendAudio ignored in state " + state);
            return;
        }
        for (int off = offset; off < offset + length; off += MAX_FRAME_BYTES) {
            byte[] frame = Arrays.copyOfRange(pcm, off, Math.min(offset + length, off + MAX_FRAME_BYTES));
            serial.execute(() -> onUserAudio(frame));
        }
    }

    /**
     * Ends the audio. Runs the audio precheck, then sends the end frame as soon as the server session
     * has started. The result arrives through {@link StreamListener#onResult}. Idempotent.
     */
    public void end() {
        if (endRequested) {
            return;
        }
        endRequested = true;
        serial.execute(this::onEndRequested);
    }

    /**
     * Cancels the session: no {@code onResult} or {@code onError} follows, only the state changes to
     * CANCELLED and CLOSED and {@code onClosed}. Idempotent.
     */
    public void cancel() {
        serial.execute(this::onCancel);
    }

    /** Releases the session; cancels it when still active. Idempotent. */
    @Override
    public void close() {
        if (state != SessionState.CLOSED) {
            cancel();
        }
    }

    /**
     * Result as a future: completes with the result, or exceptionally with the {@link YuguException}
     * delivered to {@code onError}, or with {@code RequestCancelledException} after a cancel.
     *
     * @return a new future view of the result
     */
    public CompletableFuture<EvalResult> result() {
        return resultFuture.copy();
    }

    /**
     * Waits until the session reached CLOSED.
     *
     * @param timeout timeout
     * @param unit    unit
     * @return true when closed in time
     * @throws InterruptedException when interrupted
     */
    public boolean awaitClosed(long timeout, TimeUnit unit) throws InterruptedException {
        return closedLatch.await(timeout, unit);
    }

    @Override
    public String toString() {
        return "StreamSession{" + sessionId + ", " + kind + " " + path + ", state=" + state + ", key=" + idempotencyKey + '}';
    }

    // =====================================================================================
    // connection handling (serial executor only)
    // =====================================================================================

    private void connect() {
        if (state.isTerminal()) {
            return;
        }
        generation++;
        final int gen = generation;
        connections++;
        bytesInServerSession = 0;
        serverCloseCode = -1;
        setState(SessionState.CONNECTING);
        Map<String, String> business = new LinkedHashMap<>();
        if (idempotencyKey != null) {
            business.put("idempotencyKey", idempotencyKey);
        }
        String url = ctx.options.getWsBaseUrl() + path + Auth.encodeQuery(ctx.auth.wsQuery(business));
        log.debug(TAG, sessionId + " connect " + Redact.url(url) + " (connection " + connections + ")");
        long connectTimeout = ctx.options.getConnectTimeoutMs();
        handshakeTimer = schedule(connectTimeout, () -> onHandshakeTimeout(gen));
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", ctx.options.getUserAgent());
        CompletableFuture<Void> f;
        try {
            f = ctx.transport.connect(URI.create(url), headers, Duration.ofMillis(connectTimeout), new ConnListener(gen, connections));
        } catch (RuntimeException e) {
            f = CompletableFuture.failedFuture(e);
        }
        final int attempt = connections;
        f.whenComplete((v, err) -> {
            if (err != null) {
                serial.execute(() -> onTransportFailure(gen, YuguErrors.fromThrowable(err, idempotencyKey, attempt)));
            }
        });
    }

    /** Forwards the events of one connection to the serial executor, tagged with its generation. */
    private final class ConnListener implements WsTransport.Listener {
        private final int gen;
        private final int attempt;

        ConnListener(int gen, int attempt) {
            this.gen = gen;
            this.attempt = attempt;
        }

        @Override
        public void onOpen(WsTransport.Connection connection) {
            serial.execute(() -> handleOpen(gen, connection));
        }

        @Override
        public void onText(String text) {
            serial.execute(() -> handleText(gen, text));
        }

        @Override
        public void onPong() {
            serial.execute(() -> {
                if (gen == generation) {
                    lastInboundNs = System.nanoTime();
                }
            });
        }

        @Override
        public void onClose(int code, String reason) {
            serial.execute(() -> handleServerClose(gen, code, reason));
        }

        @Override
        public void onError(Throwable error) {
            serial.execute(() -> onTransportFailure(gen, YuguErrors.fromThrowable(error, idempotencyKey, attempt)));
        }
    }

    private void handleOpen(int gen, WsTransport.Connection c) {
        if (gen != generation || state.isTerminal()) {
            c.abort();
            return;
        }
        conn = c;
        long now = System.nanoTime();
        lastInboundNs = now;
        lastPingNs = now;
        long ping = ctx.options.getPingIntervalMs();
        long tick = Math.max(20, Math.min(ping, ctx.options.getPongTimeoutMs() / 3));
        heartbeatTimer = scheduleRepeating(tick, () -> heartbeat(gen));
        log.debug(TAG, sessionId + " transport open");
    }

    private void handleText(int gen, String text) {
        if (gen != generation || state.isTerminal()) {
            return;
        }
        lastInboundNs = System.nanoTime();
        JsonNode n;
        try {
            n = Json.read(text);
        } catch (YuguException e) {
            fail(YuguErrors.create(e.toDetails().toBuilder().rawBody(text).idempotencyKey(idempotencyKey)
                    .attempts(connections).build()));
            return;
        }
        JsonNode ev = n.get("event");
        String event = ev != null && ev.isTextual() ? ev.asText() : null;
        if (event == null) {
            JsonNode eof = n.get("eof");
            if (eof != null && eof.asInt(1) == 0) {
                onPartialFrame(n);
            } else if (n.has("result") || n.has("recordId")) {
                onResultFrame(n);
            } else {
                log.debug(TAG, sessionId + " ignored frame without event");
            }
            return;
        }
        switch (event) {
            case "connected":
                onConnectedFrame();
                break;
            case "started":
                onStartedFrame();
                break;
            case "result":
                onResultFrame(n);
                break;
            case "error":
                onErrorFrame(n, text);
                break;
            case "pong":
                break;
            default:
                log.debug(TAG, sessionId + " ignored event " + event);
        }
    }

    private void onConnectedFrame() {
        if (state != SessionState.CONNECTING || conn == null) {
            return;
        }
        setState(SessionState.CONNECTED);
        safe("onConnected", listener::onConnected);
        conn.sendText(startFrame);
    }

    private void onStartedFrame() {
        if (state != SessionState.CONNECTED) {
            return;
        }
        cancel(handshakeTimer);
        // every announced onReconnecting gets its onReconnected, also when the first connection needed retries
        boolean wasReconnect = reconnectAttempt > 0;
        everStarted = true;
        setState(SessionState.STARTED);
        safe("onStarted", listener::onStarted);
        if (state != SessionState.STARTED) {
            return;
        }
        flushAudio();
        if (wasReconnect) {
            int attempt = reconnectAttempt;
            reconnectAttempt = 0;
            long dropped = bufferPolicy == AudioBufferPolicy.DROP ? totalBytes - bytesInServerSession : 0;
            log.info(TAG, sessionId + " reconnected after attempt " + attempt
                    + (bufferPolicy == AudioBufferPolicy.REPLAY ? ", replayed " + bytesInServerSession + " bytes" : ", dropped " + dropped + " bytes"));
            safe("onReconnected", () -> listener.onReconnected(attempt, dropped));
            fireReconnect(attempt, true);
        }
        if (endAccepted && state == SessionState.STARTED) {
            sendEnd();
        }
    }

    private void flushAudio() {
        if (bufferPolicy == AudioBufferPolicy.REPLAY) {
            for (byte[] b : replay) {
                sendChunk(b);
            }
        } else {
            byte[] b;
            while ((b = pending.poll()) != null) {
                sendChunk(b);
            }
            pendingBytes = 0;
        }
    }

    private void sendChunk(byte[] b) {
        conn.sendBinary(b);
        bytesInServerSession += b.length;
    }

    private void onPartialFrame(JsonNode n) {
        long bytes = n.path("result").path("bytes").asLong(-1);
        safe("onPartial", () -> listener.onPartial(new StreamPartial(bytes, n)));
    }

    private void onResultFrame(JsonNode n) {
        EvalResult r;
        try {
            r = ResultParser.parseEvaluation(n);
        } catch (YuguException e) {
            fail(YuguErrors.create(e.toDetails().toBuilder().idempotencyKey(idempotencyKey).attempts(connections).build()));
            return;
        }
        r.setIdempotencyKey(idempotencyKey);
        r.setAttempts(connections);
        r.setLocalWarnings(localWarnings);
        if (ctx.options.isStrictAudio() && r.hasWarning(WarningCode.NO_VALID_AUDIO)) {
            fail(YuguErrors.fromWarning(WarningCode.NO_VALID_AUDIO.getCode(), null, r));
            return;
        }
        cancelConnectionTimers();
        cancel(reconnectTimer);
        WsTransport.Connection c = conn;
        conn = null;
        generation++;
        setState(SessionState.COMPLETED);
        safe("onResult", () -> listener.onResult(r));
        resultFuture.complete(r);
        closeGracefully(c, NORMAL_CLOSURE, "completed");
        finish(NORMAL_CLOSURE, "completed");
    }

    private void onErrorFrame(JsonNode n, String raw) {
        int code = n.path("code").asInt(0);
        JsonNode m = n.get("message");
        YuguException e = YuguErrors.fromWsError(code, m == null || m.isNull() ? null : m.asText(), raw, idempotencyKey, connections);
        if (YuguErrors.isRetryable(e)) {
            onTransportFailure(generation, e);
        } else {
            fail(e);
        }
    }

    private void handleServerClose(int gen, int code, String reason) {
        if (gen != generation || state.isTerminal()) {
            return;
        }
        serverCloseCode = code;
        if (code == NORMAL_CLOSURE || isPermanentClose(code)) {
            // a normal close before the result, or a close a new connection would get again
            fail(YuguErrors.create(YuguErrors.local(ErrorTable.PROTOCOL_ERROR,
                    "server closed the connection before a result: " + code + " " + reason, null)
                    .toDetails().toBuilder().idempotencyKey(idempotencyKey).attempts(connections).build()));
            return;
        }
        onTransportFailure(gen, YuguErrors.create(YuguErrors.local(ErrorTable.NETWORK_ERROR,
                "connection closed by the server: " + code + (reason == null || reason.isEmpty() ? "" : " " + reason), null)
                .toDetails().toBuilder().idempotencyKey(idempotencyKey).attempts(connections).build()));
    }

    /**
     * Close codes that reject the data or the protocol use itself: 1002 protocol error, 1003 unsupported
     * data, 1007 invalid payload, 1008 policy violation, 1009 message too big, 1010 missing extension,
     * and every application code from 4000 to 4999. Reconnecting with the same session would be
     * refused the same way.
     */
    static boolean isPermanentClose(int code) {
        return code == 1002 || code == 1003 || code == 1007 || code == 1008 || code == 1009 || code == 1010
                || (code >= 4000 && code <= 4999);
    }

    private void onHandshakeTimeout(int gen) {
        if (gen != generation || state.isTerminal()) {
            return;
        }
        if (state == SessionState.CONNECTING || state == SessionState.CONNECTED) {
            onTransportFailure(gen, localError(ErrorTable.TIMEOUT,
                    "session did not start within " + ctx.options.getConnectTimeoutMs() + " ms"));
        }
    }

    private void onResultTimeout(int gen) {
        if (gen != generation || state != SessionState.ENDING) {
            return;
        }
        onTransportFailure(gen, localError(ErrorTable.RESULT_TIMEOUT,
                "no result within " + resultTimeoutMs + " ms after end()"));
    }

    private void heartbeat(int gen) {
        if (gen != generation || conn == null || state.isTerminal()) {
            return;
        }
        long now = System.nanoTime();
        long pongTimeoutNs = TimeUnit.MILLISECONDS.toNanos(ctx.options.getPongTimeoutMs());
        if (now - lastInboundNs >= pongTimeoutNs) {
            onTransportFailure(gen, localError(ErrorTable.TIMEOUT,
                    "no pong or frame for " + ctx.options.getPongTimeoutMs() + " ms"));
            return;
        }
        if (now - lastPingNs >= TimeUnit.MILLISECONDS.toNanos(ctx.options.getPingIntervalMs())) {
            lastPingNs = now;
            conn.sendPing();
        }
    }

    /** Transport failure, abnormal close, heartbeat or handshake timeout, result timeout, retryable error frame. */
    private void onTransportFailure(int gen, YuguException cause) {
        if (gen != generation || state.isTerminal() || state == SessionState.RECONNECTING) {
            return;
        }
        cancelConnectionTimers();
        WsTransport.Connection c = conn;
        conn = null;
        generation++;
        if (c != null) {
            c.abort();
        }
        if (reconnectAttempt > 0) {
            fireReconnect(reconnectAttempt, false);
        }
        if (!YuguErrors.isRetryable(cause)) {
            fail(cause);
            return;
        }
        if (bufferPolicy == AudioBufferPolicy.FAIL || !reconnect.isEnabled()) {
            fail(cause);
            return;
        }
        if (bufferPolicy == AudioBufferPolicy.REPLAY && replayOverflow) {
            fail(withCause(ErrorTable.REPLAY_BUFFER_OVERFLOW,
                    "connection lost and the replay buffer exceeded " + replayLimit + " bytes", cause));
            return;
        }
        if (bufferPolicy == AudioBufferPolicy.DROP && endAccepted) {
            fail(cause);
            return;
        }
        if (reconnectAttempt >= reconnect.getMaxAttempts()) {
            fail(withCause(ErrorTable.RECONNECT_EXHAUSTED,
                    "reconnect attempts exhausted (" + reconnect.getMaxAttempts() + "): " + cause.getRawMessage(), cause));
            return;
        }
        long sessionCap = (long) SESSION_RECONNECT_FACTOR * reconnect.getMaxAttempts();
        if (totalReconnects >= sessionCap) {
            // a server that accepts and then drops every session would otherwise be reconnected forever
            fail(withCause(ErrorTable.RECONNECT_EXHAUSTED, "reconnect limit of " + sessionCap
                    + " per session reached: " + cause.getRawMessage(), cause));
            return;
        }
        reconnectAttempt++;
        totalReconnects++;
        int attempt = reconnectAttempt;
        long delay = reconnect.delayMs(attempt, ctx.random);
        setState(SessionState.RECONNECTING);
        log.warn(TAG, sessionId + " connection lost (" + HttpEngine.describe(cause) + "), reconnect " + attempt + "/"
                + reconnect.getMaxAttempts() + " in " + delay + " ms", null);
        safe("onReconnecting", () -> listener.onReconnecting(attempt, delay, cause));
        if (state == SessionState.RECONNECTING) {
            reconnectTimer = schedule(delay, this::connect);
        }
    }

    // =====================================================================================
    // audio and end (serial executor only)
    // =====================================================================================

    private void onUserAudio(byte[] chunk) {
        if (state.isTerminal()) {
            return;
        }
        precheck.add(chunk);
        totalBytes += chunk.length;
        if (bufferPolicy == AudioBufferPolicy.REPLAY) {
            if (!replayOverflow) {
                if (replayBytes + chunk.length > replayLimit) {
                    if (state != SessionState.STARTED || conn == null) {
                        // the chunk can neither be sent live nor be kept for the replay
                        fail(localError(ErrorTable.REPLAY_BUFFER_OVERFLOW, "more than " + replayLimit
                                + " bytes of audio while the server session was not " + (everStarted ? "running" : "started")));
                        return;
                    }
                    replayOverflow = true;
                    replay.clear();
                    replayBytes = 0;
                    log.warn(TAG, sessionId + " replay buffer exceeded " + replayLimit
                            + " bytes; a reconnect now fails with 90008", null);
                } else {
                    replay.add(chunk);
                    replayBytes += chunk.length;
                }
            }
            if (state == SessionState.STARTED && conn != null) {
                sendChunk(chunk);
            }
            return;
        }
        if (state == SessionState.STARTED && conn != null) {
            sendChunk(chunk);
        } else if (!everStarted) {
            if (pendingBytes + chunk.length > replayLimit) {
                fail(localError(ErrorTable.REPLAY_BUFFER_OVERFLOW,
                        "more than " + replayLimit + " bytes queued before the session started"));
                return;
            }
            pending.add(chunk);
            pendingBytes += chunk.length;
        } else {
            log.debug(TAG, sessionId + " dropped " + chunk.length + " bytes while reconnecting");
        }
    }

    private void onEndRequested() {
        if (state.isTerminal()) {
            return;
        }
        if (!runPrecheck()) {
            return;
        }
        endAccepted = true;
        if (state == SessionState.STARTED) {
            sendEnd();
        }
    }

    private boolean runPrecheck() {
        if (precheckDone) {
            return true;
        }
        precheckDone = true;
        AudioPrecheckMode mode = ctx.options.getAudioPrecheck();
        if (mode == AudioPrecheckMode.OFF) {
            return true;
        }
        AudioAnalysis a = precheck.finish();
        if (mode == AudioPrecheckMode.REJECT && !a.getRejectingIssues().isEmpty()) {
            Warning w = a.getRejectingIssues().get(0);
            fail(YuguErrors.create(YuguErrors.local(w.getCode(), w.getMessage(), null).toDetails().toBuilder()
                    .idempotencyKey(idempotencyKey).build()));
            return false;
        }
        localWarnings = a.getIssues();
        for (Warning w : localWarnings) {
            log.warn("precheck", sessionId + " " + w.getCode() + " " + w.getMessage(), null);
            safe("onWarning", () -> listener.onWarning(w));
            if (state.isTerminal()) {
                return false;
            }
        }
        return true;
    }

    private void sendEnd() {
        if (bufferPolicy == AudioBufferPolicy.DROP && bytesInServerSession == 0 && totalBytes > 0) {
            fail(localError(ErrorTable.INVALID_STATE, "no audio left to score: the DROP policy discarded "
                    + totalBytes + " bytes and no audio followed the reconnect"));
            return;
        }
        conn.sendText(END_FRAME);
        setState(SessionState.ENDING);
        final int gen = generation;
        resultTimer = schedule(resultTimeoutMs, () -> onResultTimeout(gen));
    }

    // =====================================================================================
    // terminal transitions (serial executor only)
    // =====================================================================================

    private void fail(YuguException e) {
        if (state.isTerminal()) {
            return;
        }
        cancelConnectionTimers();
        cancel(reconnectTimer);
        WsTransport.Connection c = conn;
        conn = null;
        generation++;
        setState(SessionState.FAILED);
        log.warn(TAG, sessionId + " failed: " + HttpEngine.describe(e), null);
        safe("onError", () -> listener.onError(e));
        resultFuture.completeExceptionally(e);
        int code;
        if (serverCloseCode > 0) {
            code = serverCloseCode;
        } else if (c != null) {
            code = NORMAL_CLOSURE;
        } else {
            code = ABNORMAL_CLOSURE;
        }
        closeGracefully(c, NORMAL_CLOSURE, "client error");
        finish(code, e.getRawMessage());
    }

    private void onCancel() {
        if (state.isTerminal()) {
            return;
        }
        cancelConnectionTimers();
        cancel(reconnectTimer);
        WsTransport.Connection c = conn;
        conn = null;
        generation++;
        setState(SessionState.CANCELLED);
        resultFuture.completeExceptionally(YuguErrors.create(YuguErrors.local(ErrorTable.CANCELLED, "session cancelled", null)
                .toDetails().toBuilder().idempotencyKey(idempotencyKey).attempts(Math.max(1, connections)).build()));
        closeGracefully(c, NORMAL_CLOSURE, "cancelled");
        finish(NORMAL_CLOSURE, "cancelled");
    }

    private void finish(int code, String reason) {
        setState(SessionState.CLOSED);
        safe("onClosed", () -> listener.onClosed(code, reason));
        closedLatch.countDown();
        try {
            ctx.onFinished.accept(this);
        } catch (RuntimeException ignored) {
            // bookkeeping only
        }
    }

    private void setState(SessionState s) {
        SessionState old = state;
        if (old == s) {
            return;
        }
        state = s;
        if (log.isInfo()) {
            log.info(TAG, sessionId + " " + old + " -> " + s);
        }
        safe("onStateChanged", () -> listener.onStateChanged(old, s));
        if (ctx.events != null) {
            try {
                ctx.events.onSessionStateChanged(sessionId, old, s);
            } catch (RuntimeException e) {
                log.error(TAG, "eventListener.onSessionStateChanged threw", e);
            }
        }
    }

    private void fireReconnect(int attempt, boolean ok) {
        if (ctx.events != null) {
            try {
                ctx.events.onReconnect(sessionId, attempt, ok);
            } catch (RuntimeException e) {
                log.error(TAG, "eventListener.onReconnect threw", e);
            }
        }
    }

    private void safe(String what, Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            log.error(TAG, sessionId + " listener." + what + " threw", e);
        }
    }

    private YuguException localError(int code, String message) {
        return YuguErrors.create(YuguErrors.local(code, message, null).toDetails().toBuilder()
                .idempotencyKey(idempotencyKey).attempts(Math.max(1, connections)).build());
    }

    private YuguException withCause(int code, String message, YuguException cause) {
        return YuguErrors.create(YuguErrors.local(code, message, cause).toDetails().toBuilder()
                .idempotencyKey(idempotencyKey).attempts(Math.max(1, connections)).build());
    }

    // =====================================================================================
    // timers
    // =====================================================================================

    private ScheduledFuture<?> schedule(long delayMs, Runnable task) {
        try {
            return ctx.scheduler.schedule(() -> serial.execute(task), delayMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            serial.execute(task);
            return null;
        }
    }

    private ScheduledFuture<?> scheduleRepeating(long periodMs, Runnable task) {
        try {
            return ctx.scheduler.scheduleAtFixedRate(() -> serial.execute(task), periodMs, periodMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            return null;
        }
    }

    private static void cancel(ScheduledFuture<?> f) {
        if (f != null) {
            f.cancel(false);
        }
    }

    private void cancelConnectionTimers() {
        cancel(handshakeTimer);
        cancel(resultTimer);
        cancel(heartbeatTimer);
        handshakeTimer = null;
        resultTimer = null;
        heartbeatTimer = null;
    }

    private void closeGracefully(WsTransport.Connection c, int code, String reason) {
        if (c == null) {
            return;
        }
        try {
            c.close(code, reason);
        } catch (RuntimeException ignored) {
            // the abort below releases the connection anyway
        }
        try {
            ctx.scheduler.schedule(c::abort, CLOSE_GRACE_MS, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            c.abort();
        }
    }
}
