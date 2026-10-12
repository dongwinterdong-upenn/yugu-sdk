// SPDX-License-Identifier: Apache-2.0
package com.shengzhiai.yugu.stcompat.internal;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.security.cert.CertificateException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.SSLPeerUnverifiedException;

/**
 * Error classification shared with the other Yugu SDKs (DESIGN 2.3 and 2.4) and the errId rule of
 * the compat envelope (DESIGN 6.2).
 *
 * <p>Retryable, first match wins: local codes 90001, 90002, 90007; a business code from
 * spec/errors.json uses its {@code retryable} flag; otherwise HTTP 408, 425, 429, 500, 502, 503,
 * 504. errId: 20009 for every retryable failure once retries are exhausted, the platform
 * business code for other server errors, the local code for other local failures.
 */
public final class ErrorMapper {
    private static final Pattern DETAIL_CODE = Pattern.compile("^\\s*\\[(\\d{3,6})\\]\\s*(.*)$", Pattern.DOTALL);

    private ErrorMapper() {
    }

    /** {@code isRetryable(error)} of DESIGN 2.3. */
    public static boolean isRetryable(int code, int httpStatus) {
        if (code != 0) {
            ErrorTable.Entry local = ErrorTable.LOCAL.get(code);
            if (local != null && httpStatus == 0) {
                return local.retryable;
            }
            ErrorTable.Entry e = ErrorTable.ERRORS.get(code);
            if (e != null) {
                return e.retryable;
            }
        }
        for (int s : ErrorTable.RETRYABLE_HTTP) {
            if (s == httpStatus) {
                return true;
            }
        }
        return false;
    }

    /** Maps a transport exception to a local error (90001 network, 90002 timeout, 90011 TLS). */
    public static CompatError fromException(IOException e) {
        if (e instanceof SocketTimeoutException || (e instanceof InterruptedIOException && isTimeoutMessage(e))) {
            return CompatError.local(ErrorTable.TIMEOUT, "timeout: " + e.getMessage(), e);
        }
        if (e instanceof SSLPeerUnverifiedException || hasCause(e, CertificateException.class)) {
            return CompatError.local(ErrorTable.TLS_ERROR, "TLS certificate check failed: " + e.getMessage(), e);
        }
        return CompatError.local(ErrorTable.NETWORK_ERROR, "network error: " + e.getClass().getSimpleName()
                + (e.getMessage() == null ? "" : " " + e.getMessage()), e);
    }

    /**
     * Failure of a realtime connection: a refused handshake is classified by its HTTP status like a
     * REST answer without body, a protocol violation is 90005, anything else as {@link #fromException}.
     */
    public static CompatError fromWsException(IOException e) {
        if (e instanceof WsClient.HandshakeException) {
            WsClient.HandshakeException h = (WsClient.HandshakeException) e;
            return new CompatError(0, h.status, isRetryable(0, h.status), h.retryAfterMs, e.getMessage(), null, e);
        }
        if (e instanceof WsClient.ProtocolError) {
            return CompatError.local(ErrorTable.PROTOCOL_ERROR, "realtime protocol error: " + e.getMessage(), e);
        }
        return fromException(e);
    }

    /**
     * Error frame {@code {"event":"error","code":N,"message":"..."}} of the realtime channel. A
     * code keeps the retryable flag of the table (40901, 42901, 50200 are retried, DESIGN 2.5); a
     * frame without code (also none in the {@code [2003] ...} message form) is a terminal server
     * error with code 50000.
     */
    public static CompatError fromWsError(int code, String message, String rawFrame) {
        String text = message == null || message.isEmpty() ? null : message;
        if (code == 0 && text != null) {
            Matcher m = DETAIL_CODE.matcher(text);
            if (m.matches()) {
                code = Integer.parseInt(m.group(1));
            }
        }
        if (code == 0) {
            return new CompatError(ErrorTable.INTERNAL_ERROR, 0, false, -1,
                    text != null ? text : "realtime evaluation failed on the platform", rawFrame, null);
        }
        return new CompatError(code, 0, isRetryable(code, 0), -1, text != null ? text : CompatErrIds.message(code),
                rawFrame, null);
    }

    /**
     * Close frame of the realtime channel before the result. 1000, 1002, 1003, 1007 to 1010 and
     * 4000 to 4999 end the round at once with 90005, because a new connection would be closed the
     * same way (DESIGN 2.5); other codes such as 1001, 1011 and 1012 are network failures and may
     * be retried.
     */
    public static CompatError fromWsClose(int code, String reason) {
        String text = "realtime connection closed by the platform with code " + code
                + (reason == null || reason.isEmpty() ? "" : " " + reason);
        boolean terminal = code == 1000 || code == 1002 || code == 1003 || (code >= 1007 && code <= 1010)
                || (code >= 4000 && code <= 4999);
        return CompatError.local(terminal ? ErrorTable.PROTOCOL_ERROR : ErrorTable.NETWORK_ERROR, text, null);
    }

    private static boolean isTimeoutMessage(IOException e) {
        return e.getMessage() != null && e.getMessage().toLowerCase(Locale.ROOT).contains("timed out");
    }

    private static boolean hasCause(Throwable t, Class<? extends Throwable> type) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (type.isInstance(c)) {
                return true;
            }
            if (c.getCause() == c) {
                break;
            }
        }
        return false;
    }

    /**
     * Maps a non-success response. Body shapes: platform {@code {code, message, timestamp}},
     * FastAPI {@code {detail: "..."}} or {@code {detail: [...]}}, Shengtong style
     * {@code {errId, error}}, or anything else (HTML from a proxy).
     */
    public static CompatError fromResponse(HttpTransport.Response r) {
        int code = 0;
        String message = null;
        try {
            Map<String, String> m = RawJson.members(r.body.trim());
            code = (int) RawJson.toLong(m.get("code"), 0);
            if (code == 0) {
                code = (int) RawJson.toLong(m.get("errId"), 0);
            }
            message = firstString(m, "message", "msg", "error");
            String detail = m.get("detail");
            if (detail != null) {
                if (RawJson.isString(detail)) {
                    String d = RawJson.unquote(detail);
                    Matcher mm = DETAIL_CODE.matcher(d);
                    if (mm.matches() && code == 0) {
                        code = Integer.parseInt(mm.group(1));
                    }
                    if (message == null) {
                        message = d;
                    }
                } else if (message == null) {
                    message = detail.length() > 300 ? detail.substring(0, 300) : detail;
                }
            }
        } catch (IllegalArgumentException notJson) {
            // fall back to the HTTP status
        }
        if (message == null || message.isEmpty()) {
            message = "HTTP " + r.status;
        }
        long retryAfter = parseRetryAfter(r.header("Retry-After"), System.currentTimeMillis());
        return new CompatError(code, r.status, isRetryable(code, r.status), retryAfter, message, r.body, null);
    }

    private static String firstString(Map<String, String> m, String... keys) {
        for (String k : keys) {
            String v = m.get(k);
            if (v != null && RawJson.isString(v)) {
                return RawJson.unquote(v);
            }
        }
        return null;
    }

    /** Retry-After in ms: delta seconds or an HTTP date; -1 when absent or unparsable. */
    public static long parseRetryAfter(String value, long nowMs) {
        if (value == null || value.trim().isEmpty()) {
            return -1;
        }
        String v = value.trim();
        try {
            long s = Long.parseLong(v);
            return s < 0 ? -1 : s * 1000L;
        } catch (NumberFormatException ignored) {
            // HTTP date
        }
        try {
            SimpleDateFormat f = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
            Date d = f.parse(v);
            return Math.max(0, d.getTime() - nowMs);
        } catch (ParseException e) {
            return -1;
        }
    }

    /** errId delivered in the compat error JSON (DESIGN 6.2). */
    public static int errId(CompatError e) {
        if (e.retryable) {
            return CompatErrIds.RETRYABLE;
        }
        if (e.code != 0) {
            return e.code;
        }
        return httpFallbackCode(e.httpStatus);
    }

    /** Representative platform code when a non-retryable response carried no business code. */
    public static int httpFallbackCode(int status) {
        String category = ErrorTable.HTTP_FALLBACK.get(status);
        if (category == null) {
            if (status >= 400 && status < 500) {
                return ErrorTable.VALIDATION_ERROR;
            }
            if (status >= 500) {
                return ErrorTable.INTERNAL_ERROR;
            }
            return ErrorTable.PROTOCOL_ERROR;
        }
        if ("AUTH".equals(category)) {
            return ErrorTable.UNAUTHORIZED;
        }
        if ("PERMISSION".equals(category)) {
            return ErrorTable.FORBIDDEN;
        }
        if ("NOT_FOUND".equals(category)) {
            return ErrorTable.NOT_FOUND;
        }
        if ("CONFLICT".equals(category)) {
            return ErrorTable.CONFLICT;
        }
        if ("RATE_LIMIT".equals(category)) {
            return ErrorTable.TOO_MANY_REQUESTS;
        }
        if ("SERVER".equals(category)) {
            return status == 501 ? ErrorTable.FEATURE_NOT_IMPLEMENTED : ErrorTable.INTERNAL_ERROR;
        }
        if ("UPSTREAM".equals(category)) {
            return ErrorTable.UPSTREAM_SERVICE_ERROR;
        }
        if ("TIMEOUT".equals(category)) {
            return CompatErrIds.RETRYABLE;
        }
        return ErrorTable.VALIDATION_ERROR;
    }

    /** Human readable message for the {@code error} field of the error JSON. */
    public static String message(CompatError e, int errId) {
        StringBuilder sb = new StringBuilder();
        if (errId == CompatErrIds.RETRYABLE) {
            sb.append(CompatErrIds.message(errId)).append(": ").append(e.describe());
            if (e.getMessage() != null && !e.getMessage().isEmpty()) {
                sb.append(' ').append(e.getMessage());
            }
            if (e.getAttempts() > 0) {
                sb.append(" (attempts ").append(e.getAttempts()).append(')');
            }
            return sb.toString();
        }
        if (e.getMessage() != null && !e.getMessage().isEmpty()) {
            return e.getMessage();
        }
        return CompatErrIds.message(errId);
    }
}
