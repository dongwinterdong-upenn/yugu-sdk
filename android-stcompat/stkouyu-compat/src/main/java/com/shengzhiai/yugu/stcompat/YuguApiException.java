// SPDX-License-Identifier: Apache-2.0
package com.shengzhiai.yugu.stcompat;

/**
 * Failure of a {@link TtsClient} or {@link ReportClient} call (SDK 2.2.0), unchecked.
 *
 * <p>{@link #getCode()} follows the errId rule of the compat error JSON: 20009 when the network or
 * the platform failed temporarily and the retries are used up (connection failure, timeout, HTTP 408,
 * 425, 429, 5xx and the retryable codes of ERRORS.md), otherwise the platform code, for example 40001
 * with {@code 评测记录不存在} for an unknown recordId or 2001 to 2011 for authentication errors such
 * as 2003 {@code 签名验证失败}. {@link #getHttpStatus()} is the HTTP status of the last answer, 0 when
 * there was none.
 */
public class YuguApiException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final int code;
    private final int httpStatus;

    public YuguApiException(int code, int httpStatus, String message) {
        this(code, httpStatus, message, null);
    }

    public YuguApiException(int code, int httpStatus, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.httpStatus = httpStatus;
    }

    /** Platform code, or 20009 for a temporary failure after the retries. */
    public int getCode() {
        return code;
    }

    /** HTTP status of the last answer, 0 when no answer arrived. */
    public int getHttpStatus() {
        return httpStatus;
    }

    @Override
    public String toString() {
        return "YuguApiException{code=" + code + ", httpStatus=" + httpStatus + ", message=" + getMessage() + "}";
    }
}
