// SPDX-License-Identifier: Apache-2.0
package com.shengzhiai.yugu.stcompat.internal;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/** Shengtong kernel names: which ones the platform compat path supports, and name detection. */
public final class CoreTypes {
    /**
     * Shengtong's Chinese open question (since 2.1.2). Its refText holds model answers separated by | or the question,
     * not text to read, so a reference pinyin cannot replace it. Shengtong publishes no constant for it; apps pass the
     * string.
     */
    public static final String OPEN_EVAL_CN = "open.eval.cn";

    /** Kernels served by {@code POST /{coreType}} on the platform (DESIGN 6.1). */
    public static final Set<String> SUPPORTED = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
            "word.eval", "word.eval.pro", "sent.eval", "sent.eval.pro", "para.eval",
            "word.eval.cn", "sent.eval.cn", "para.eval.cn", "alpha.eval", "pinyin", OPEN_EVAL_CN)));

    /** Every kernel name of the Shengtong SDK plus the platform extras. */
    private static final Set<String> KNOWN = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
            "sent.eval", "word.eval", "choice.rec", "open.eval", "para.eval", "asr.rec", "align.eval",
            "word.eval.pro", "sent.eval.pro", "word.eval.cn", "sent.eval.cn", "para.eval.cn", "asr.eval",
            "word.eval.fr", "sent.eval.fr", "para.eval.fr", "word.eval.jp", "sent.eval.jp", "para.eval.jp",
            "word.eval.kr", "sent.eval.kr", "para.eval.kr", "alpha.eval", "pinyin", OPEN_EVAL_CN)));

    private static final Pattern SHAPE = Pattern.compile("^[a-z]+\\.(eval|rec)(\\.[a-z]+)?$");

    private CoreTypes() {
    }

    public static boolean isSupported(String coreType) {
        return coreType != null && SUPPORTED.contains(coreType.trim());
    }

    /** True when the value is a kernel name rather than a reference text. */
    public static boolean looksLikeCoreType(String s) {
        if (s == null) {
            return false;
        }
        String t = s.trim();
        return KNOWN.contains(t) || SHAPE.matcher(t).matches();
    }

    /** True for Chinese kernels, where a reference pinyin may replace the reference text. */
    public static boolean isChinese(String coreType) {
        return coreType != null && (coreType.endsWith(".cn") || "pinyin".equals(coreType.trim()));
    }

    /** True for {@link #OPEN_EVAL_CN}: the refText, model answers or the question, must be present. */
    public static boolean isOpenQuestion(String coreType) {
        return coreType != null && OPEN_EVAL_CN.equals(coreType.trim());
    }
}
