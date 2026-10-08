package com.shengzhiai.yugu.errors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.net.http.WebSocketHandshakeException;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.SSLException;

/**
 * Error mapping and the public retry decision.
 *
 * <p>Mapping order for platform responses: local error, then the body {@code code} (non-zero)
 * through the table generated from spec/errors.json, then the engine compat detail pattern
 * {@code [2001] ...}, then the HTTP status fallback.
 *
 * <p>Retry rules of {@link #isRetryable(Throwable)}, first match wins:
 * <ol>
 *   <li>local codes: 90001 network, 90002 timeout and 90007 result timeout are retryable,
 *       every other local code is not;</li>
 *   <li>a business code from the table: its {@code retryable} flag;</li>
 *   <li>unknown or absent code: HTTP 408, 425, 429, 500, 502, 503 and 504 are retryable.</li>
 * </ol>
 */
public final class YuguErrors {
    private static final Pattern DETAIL_CODE = Pattern.compile("^\\s*\\[(\\d{1,6})\\]\\s*(.*)$", Pattern.DOTALL);
    private static final ObjectMapper JSON = new ObjectMapper();

    private YuguErrors() {
    }

    // ------------------------------------------------------------------ retry decision

    /**
     * Whether repeating the failed call with the same idempotency key may succeed. The SDK retry
     * loop and the WebSocket reconnect logic use exactly this function.
     *
     * @param error any throwable; non SDK exceptions are classified first
     * @return true for retryable errors
     */
    public static boolean isRetryable(Throwable error) {
        if (error == null) {
            return false;
        }
        Throwable c = unwrap(error);
        if (c instanceof YuguException) {
            return ((YuguException) c).isRetryable();
        }
        return fromThrowable(c).isRetryable();
    }

    static boolean isRetryable(CodeKind kind, int code, int httpStatus) {
        ErrorTable.Entry e = entry(kind, code);
        if (e != null) {
            return e.retryable;
        }
        return isRetryableHttpStatus(httpStatus);
    }

    /**
     * @param httpStatus HTTP status
     * @return true when the status is retryable in the absence of a business code
     */
    public static boolean isRetryableHttpStatus(int httpStatus) {
        for (int s : ErrorTable.RETRYABLE_HTTP) {
            if (s == httpStatus) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ table lookups

    /**
     * Resolves the table of a code. Platform error codes win over warning codes for the two codes
     * present in both tables (1004, 1005); use {@link #fromWarning(int)} for warnings.
     *
     * @param code code
     * @return table kind
     */
    public static CodeKind kindOf(int code) {
        if (code == 0) {
            return CodeKind.NONE;
        }
        if (ErrorTable.LOCAL.containsKey(code)) {
            return CodeKind.LOCAL;
        }
        if (ErrorTable.ERRORS.containsKey(code)) {
            return CodeKind.ERROR;
        }
        if (ErrorTable.WARNINGS.containsKey(code)) {
            return CodeKind.WARNING;
        }
        return CodeKind.UNLISTED;
    }

    static ErrorTable.Entry entry(CodeKind kind, int code) {
        if (kind == null) {
            return null;
        }
        switch (kind) {
            case LOCAL:
                return ErrorTable.LOCAL.get(code);
            case ERROR:
                return ErrorTable.ERRORS.get(code);
            case WARNING:
                return ErrorTable.WARNINGS.get(code);
            default:
                return null;
        }
    }

    /**
     * @param code platform, warning or local code
     * @return category from the table, {@link ErrorCategory#UNKNOWN} for unlisted codes
     */
    public static ErrorCategory categoryOf(int code) {
        ErrorTable.Entry e = entry(kindOf(code), code);
        return e == null ? ErrorCategory.UNKNOWN : ErrorCategory.parse(e.category);
    }

    /**
     * @param httpStatus HTTP status
     * @return category from the HTTP fallback table, {@link ErrorCategory#UNKNOWN} when unlisted
     */
    public static ErrorCategory categoryForHttpStatus(int httpStatus) {
        String c = ErrorTable.HTTP_FALLBACK.get(httpStatus);
        return c == null ? ErrorCategory.UNKNOWN : ErrorCategory.parse(c);
    }

    /**
     * @param category category
     * @return the exception class used for that category
     */
    public static Class<? extends YuguException> exceptionClassFor(ErrorCategory category) {
        switch (category == null ? ErrorCategory.UNKNOWN : category) {
            case NETWORK:
                return NetworkException.class;
            case TIMEOUT:
                return RequestTimeoutException.class;
            case AUTH:
                return AuthException.class;
            case PERMISSION:
                return PermissionException.class;
            case INVALID_PARAM:
                return InvalidParameterException.class;
            case NOT_FOUND:
                return NotFoundException.class;
            case CONFLICT:
                return ConflictException.class;
            case RATE_LIMIT:
                return RateLimitException.class;
            case QUOTA:
                return QuotaExceededException.class;
            case SERVER:
            case UPSTREAM:
                return ServerException.class;
            case AUDIO:
                return AudioQualityException.class;
            case STATE:
                return IllegalSessionStateException.class;
            case CANCELLED:
                return RequestCancelledException.class;
            case PROTOCOL:
                return ProtocolViolationException.class;
            default:
                return YuguException.class;
        }
    }

    /**
     * Creates the exception subclass matching the category of the details.
     *
     * @param d details
     * @return exception, never null
     */
    public static YuguException create(ErrorDetails d) {
        switch (d.category) {
            case NETWORK:
                return new NetworkException(d);
            case TIMEOUT:
                return new RequestTimeoutException(d);
            case AUTH:
                return new AuthException(d);
            case PERMISSION:
                return new PermissionException(d);
            case INVALID_PARAM:
                return new InvalidParameterException(d);
            case NOT_FOUND:
                return new NotFoundException(d);
            case CONFLICT:
                return new ConflictException(d);
            case RATE_LIMIT:
                return new RateLimitException(d);
            case QUOTA:
                return new QuotaExceededException(d);
            case SERVER:
            case UPSTREAM:
                return new ServerException(d);
            case AUDIO:
                return new AudioQualityException(d);
            case STATE:
                return new IllegalSessionStateException(d);
            case CANCELLED:
                return new RequestCancelledException(d);
            case PROTOCOL:
                return new ProtocolViolationException(d);
            default:
                return new YuguException(d);
        }
    }

    // ------------------------------------------------------------------ factories by code

    /**
     * Builds the typed exception for a platform or local code using the table message. For the
     * codes 1001 to 1009 that are only warnings, an {@link AudioQualityException} is built.
     *
     * @param code code
     * @return exception
     */
    public static YuguException fromCode(int code) {
        return fromCode(code, null);
    }

    /**
     * @param code    code
     * @param message message, the table message when null
     * @return exception
     */
    public static YuguException fromCode(int code, String message) {
        CodeKind kind = kindOf(code);
        ErrorTable.Entry e = entry(kind, code);
        return create(ErrorDetails.builder()
                .category(e == null ? ErrorCategory.UNKNOWN : ErrorCategory.parse(e.category))
                .code(code)
                .codeKind(kind)
                .httpStatus(e == null ? 0 : e.http)
                .message(message != null ? message : e != null ? e.message : "unknown code " + code)
                .build());
    }

    /**
     * Builds an {@link AudioQualityException} for an audio warning code (1001 to 1005, 1009), for
     * callers who want to treat a warning as an error.
     *
     * @param code warning code
     * @return exception
     */
    public static AudioQualityException fromWarning(int code) {
        return fromWarning(code, null, null);
    }

    /**
     * @param code    warning code
     * @param message message, the table message when null
     * @param result  result that carried the warning, may be null
     * @return exception
     */
    public static AudioQualityException fromWarning(int code, String message, com.shengzhiai.yugu.model.EvalResult result) {
        ErrorTable.Entry e = ErrorTable.WARNINGS.get(code);
        ErrorDetails d = ErrorDetails.builder()
                .category(ErrorCategory.AUDIO)
                .code(code)
                .codeKind(e == null ? CodeKind.UNLISTED : CodeKind.WARNING)
                .message(message != null ? message : e != null ? e.message : "audio warning " + code)
                .recordId(result == null ? null : result.getRecordId())
                .idempotencyKey(result == null ? null : result.getIdempotencyKey())
                .build();
        return new AudioQualityException(d, result);
    }

    /**
     * Builds an exception for an SDK local code (90001 to 90203).
     *
     * @param code    local code
     * @param message message, the table message when null
     * @param cause   cause, may be null
     * @return exception
     */
    public static YuguException local(int code, String message, Throwable cause) {
        ErrorTable.Entry e = ErrorTable.LOCAL.get(code);
        if (e == null) {
            throw new IllegalArgumentException("not a local code: " + code);
        }
        return create(ErrorDetails.builder()
                .category(ErrorCategory.parse(e.category))
                .code(code)
                .codeKind(CodeKind.LOCAL)
                .message(message != null ? message : e.message)
                .cause(cause)
                .build());
    }

    /** Shortcut for {@link #local(int, String, Throwable)} without cause. */
    static YuguException local(int code, String message) {
        return local(code, message, null);
    }

    // ------------------------------------------------------------------ factories by response

    /**
     * Maps a failed HTTP response. Body shapes understood: platform {@code {code, message, timestamp}},
     * FastAPI {@code {detail: "..."}} or {@code {detail: [...]}}, engine detail {@code "[2001] ..."}.
     *
     * @param httpStatus status
     * @param body       response body, may be null
     * @return exception
     */
    public static YuguException fromHttpResponse(int httpStatus, String body) {
        return fromHttpResponse(httpStatus, body, -1, null, 1);
    }

    /**
     * @param httpStatus     status
     * @param body           response body, may be null
     * @param retryAfterMs   Retry-After in milliseconds, -1 when absent
     * @param idempotencyKey key of the call, may be null
     * @param attempts       attempts made
     * @return exception
     */
    public static YuguException fromHttpResponse(int httpStatus, String body, long retryAfterMs,
                                                 String idempotencyKey, int attempts) {
        ParsedBody p = ParsedBody.parse(body);
        int code = p.code;
        CodeKind kind = kindOf(code);
        ErrorTable.Entry e = entry(kind, code);
        String message = p.message;
        if (e == null && p.detailCode != 0) {
            // Engine compat detail "[2001] ...": 2001 to 2003 are AUTH in the table.
            CodeKind dk = kindOf(p.detailCode);
            ErrorTable.Entry de = entry(dk, p.detailCode);
            if (de != null && dk == CodeKind.ERROR) {
                code = p.detailCode;
                kind = dk;
                e = de;
            }
        }
        ErrorCategory category;
        if (e != null) {
            category = ErrorCategory.parse(e.category);
        } else {
            category = categoryForHttpStatus(httpStatus);
        }
        if (message == null || message.isEmpty()) {
            message = e != null ? e.message : "HTTP " + httpStatus;
        }
        return create(ErrorDetails.builder()
                .category(category)
                .code(code)
                .codeKind(kind)
                .httpStatus(httpStatus)
                .message(message)
                .rawBody(body)
                .recordId(p.recordId)
                .retryAfterMs(retryAfterMs)
                .idempotencyKey(idempotencyKey)
                .attempts(attempts)
                .build());
    }

    /**
     * Maps a WebSocket error frame {@code {"event":"error","code":N,"message":"..."}}. Frames without a
     * listed code are classified as {@link ErrorCategory#SERVER}, not retryable.
     *
     * @param code           code from the frame, 0 when absent
     * @param message        message from the frame
     * @param rawFrame       raw frame text
     * @param idempotencyKey key of the session
     * @param attempts       connection attempts so far
     * @return exception
     */
    public static YuguException fromWsError(int code, String message, String rawFrame, String idempotencyKey, int attempts) {
        CodeKind kind = kindOf(code);
        ErrorTable.Entry e = entry(kind, code);
        if (e == null && message != null) {
            Matcher m = DETAIL_CODE.matcher(message);
            if (m.matches()) {
                int dc = parseIntSafe(m.group(1));
                ErrorTable.Entry de = ErrorTable.ERRORS.get(dc);
                if (de != null) {
                    code = dc;
                    kind = CodeKind.ERROR;
                    e = de;
                }
            }
        }
        ErrorCategory category = e != null ? ErrorCategory.parse(e.category) : ErrorCategory.SERVER;
        return create(ErrorDetails.builder()
                .category(category)
                .code(code)
                .codeKind(kind)
                .message(message == null || message.isEmpty() ? (e != null ? e.message : "server error frame") : message)
                .rawBody(rawFrame)
                .idempotencyKey(idempotencyKey)
                .attempts(attempts)
                .build());
    }

    /**
     * Classifies a low level failure: timeouts are {@link ErrorCategory#TIMEOUT} 90002, TLS
     * failures 90011, other I/O failures {@link ErrorCategory#NETWORK} 90001, cancellation 90003, a
     * rejected WebSocket handshake by its HTTP status.
     *
     * @param error throwable
     * @return exception, the same instance when it already is a {@link YuguException}
     */
    public static YuguException fromThrowable(Throwable error) {
        return fromThrowable(error, null, 1);
    }

    /**
     * @param error          throwable
     * @param idempotencyKey key of the call, may be null
     * @param attempts       attempts made
     * @return exception
     */
    public static YuguException fromThrowable(Throwable error, String idempotencyKey, int attempts) {
        Throwable c = unwrap(error);
        if (c instanceof YuguException) {
            return (YuguException) c;
        }
        int code;
        String msg;
        if (c instanceof HttpConnectTimeoutException) {
            code = ErrorTable.TIMEOUT;
            msg = "connect timeout";
        } else if (c instanceof HttpTimeoutException || c instanceof SocketTimeoutException || c instanceof TimeoutException) {
            code = ErrorTable.TIMEOUT;
            msg = "read timeout";
        } else if (c instanceof WebSocketHandshakeException) {
            java.net.http.HttpResponse<?> r = ((WebSocketHandshakeException) c).getResponse();
            int status = r == null ? 0 : r.statusCode();
            Object b = r == null ? null : r.body();
            String body = b instanceof String ? (String) b : null;
            YuguException mapped = fromHttpResponse(status, body, -1, idempotencyKey, attempts);
            return create(mapped.toDetails().toBuilder()
                    .message("WebSocket handshake rejected: " + mapped.getRawMessage())
                    .cause(c)
                    .build());
        } else if (c instanceof SSLException) {
            code = ErrorTable.TLS_ERROR;
            msg = "TLS failure: " + c.getMessage();
        } else if (c instanceof CancellationException || c instanceof InterruptedException) {
            code = ErrorTable.CANCELLED;
            msg = "cancelled";
        } else if (c instanceof IOException) {
            code = ErrorTable.NETWORK_ERROR;
            msg = "network error: " + (c.getMessage() == null ? c.getClass().getSimpleName() : c.getMessage());
        } else if (c instanceof IllegalArgumentException) {
            code = ErrorTable.INVALID_ARGUMENT;
            msg = c.getMessage();
        } else {
            return create(ErrorDetails.builder()
                    .category(ErrorCategory.UNKNOWN)
                    .message(c == null ? "unknown error" : String.valueOf(c))
                    .cause(c)
                    .idempotencyKey(idempotencyKey)
                    .attempts(attempts)
                    .build());
        }
        ErrorTable.Entry e = ErrorTable.LOCAL.get(code);
        return create(ErrorDetails.builder()
                .category(ErrorCategory.parse(e.category))
                .code(code)
                .codeKind(CodeKind.LOCAL)
                .message(msg)
                .cause(c)
                .idempotencyKey(idempotencyKey)
                .attempts(attempts)
                .build());
    }

    static Throwable unwrap(Throwable t) {
        Throwable c = t;
        while ((c instanceof CompletionException || c instanceof ExecutionException || c instanceof UncheckedIOException)
                && c.getCause() != null) {
            c = c.getCause();
        }
        return c;
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** Fields extracted from an error body. */
    static final class ParsedBody {
        int code;
        int detailCode;
        String message;
        String recordId;

        static ParsedBody parse(String body) {
            ParsedBody p = new ParsedBody();
            if (body == null || body.isEmpty()) {
                return p;
            }
            JsonNode n;
            try {
                n = JSON.readTree(body);
            } catch (IOException | RuntimeException e) {
                p.message = null;
                return p;
            }
            if (n == null || !n.isObject()) {
                return p;
            }
            p.code = intField(n, "code");
            if (p.code == 0) {
                p.code = intField(n, "errId");
            }
            p.message = textField(n, "message");
            if (p.message == null) {
                p.message = textField(n, "msg");
            }
            if (p.message == null) {
                p.message = textField(n, "error");
            }
            JsonNode detail = n.get("detail");
            if (detail != null && !detail.isNull()) {
                String d;
                if (detail.isTextual()) {
                    d = detail.asText();
                } else if (detail.isArray()) {
                    StringBuilder sb = new StringBuilder();
                    for (JsonNode item : detail) {
                        String m = item.isObject() ? textField(item, "msg") : item.asText();
                        if (m == null) {
                            m = item.toString();
                        }
                        if (sb.length() > 0) {
                            sb.append("; ");
                        }
                        sb.append(m);
                    }
                    d = sb.toString();
                } else {
                    d = detail.toString();
                }
                Matcher m = DETAIL_CODE.matcher(d);
                if (m.matches()) {
                    p.detailCode = parseIntSafe(m.group(1));
                }
                if (p.message == null) {
                    p.message = d;
                }
            }
            p.recordId = textField(n, "recordId");
            JsonNode data = n.get("data");
            if (p.recordId == null && data != null && data.isObject()) {
                p.recordId = textField(data, "recordId");
            }
            return p;
        }

        private static int intField(JsonNode n, String name) {
            JsonNode v = n.get(name);
            if (v == null || v.isNull()) {
                return 0;
            }
            if (v.isNumber()) {
                return v.asInt();
            }
            if (v.isTextual()) {
                return parseIntSafe(v.asText());
            }
            return 0;
        }

        private static String textField(JsonNode n, String name) {
            JsonNode v = n.get(name);
            if (v == null || v.isNull()) {
                return null;
            }
            return v.isValueNode() ? v.asText() : v.toString();
        }
    }

    /**
     * Exposes the HTTP fallback table, for documentation and tests.
     *
     * @return unmodifiable status to category name map
     */
    public static Map<Integer, String> httpFallbackTable() {
        return ErrorTable.HTTP_FALLBACK;
    }
}
