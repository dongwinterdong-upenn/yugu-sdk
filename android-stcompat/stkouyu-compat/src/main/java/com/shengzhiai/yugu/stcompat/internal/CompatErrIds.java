// SPDX-License-Identifier: Apache-2.0
package com.shengzhiai.yugu.stcompat.internal;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * errIds of the Shengtong compat layers ({@code compatErrIds} in spec/errors.json). The generated
 * {@link ErrorTable} does not include this section, so the values live here; a unit test checks
 * them against spec/errors.json.
 */
public final class CompatErrIds {
    /** Network or server temporarily unavailable after retries; autoRetry re-evaluates this by default. */
    public static final int RETRYABLE = 20009;
    public static final int AUDIO_FILE_MISSING = 60001;
    public static final int AUDIO_EMPTY = 60002;
    public static final int CORE_TYPE_UNSUPPORTED = 60003;
    public static final int MICROPHONE_UNAVAILABLE = 60004;
    public static final int AUDIO_TOO_SHORT = 60005;
    public static final int REF_TEXT_EMPTY = 60006;
    public static final int ENGINE_NOT_INITIALIZED = 60007;
    public static final int ENGINE_BUSY = 60008;
    /** Larger than the 50 MB REST limit, or WAV and PCM longer than 300 s. */
    public static final int AUDIO_TOO_LARGE = 60009;

    public static final Map<Integer, String> MESSAGES;

    static {
        Map<Integer, String> m = new LinkedHashMap<Integer, String>();
        m.put(RETRYABLE, "网络或服务端临时故障，可重评，autoRetry 默认重评此码");
        m.put(AUDIO_FILE_MISSING, "音频文件不存在或不可读");
        m.put(AUDIO_EMPTY, "音频为空");
        m.put(CORE_TYPE_UNSUPPORTED, "coreType 不支持");
        m.put(MICROPHONE_UNAVAILABLE, "麦克风不可用或没有录音权限");
        m.put(AUDIO_TOO_SHORT, "音频短于 1 秒");
        m.put(REF_TEXT_EMPTY, "refText 为空");
        m.put(ENGINE_NOT_INITIALIZED, "引擎未初始化");
        m.put(ENGINE_BUSY, "引擎正忙，上一次评测未结束");
        m.put(AUDIO_TOO_LARGE, "音频大于 50 MB 或长于 300 秒");
        MESSAGES = Collections.unmodifiableMap(m);
    }

    private CompatErrIds() {
    }

    public static String message(int errId) {
        String m = MESSAGES.get(errId);
        if (m != null) {
            return m;
        }
        ErrorTable.Entry e = ErrorTable.ERRORS.get(errId);
        if (e == null) {
            e = ErrorTable.LOCAL.get(errId);
        }
        return e != null ? e.message : "error " + errId;
    }
}
