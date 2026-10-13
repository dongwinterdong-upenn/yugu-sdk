// SPDX-License-Identifier: Apache-2.0
package com.stkouyu;

/**
 * Evaluation kernels (Shengtong names). Supported on the Yugu platform: {@code word.eval},
 * {@code word.eval.pro}, {@code sent.eval}, {@code sent.eval.pro}, {@code para.eval},
 * {@code word.eval.cn}, {@code sent.eval.cn}, {@code para.eval.cn}, plus {@code alpha.eval},
 * {@code pinyin} and the Chinese open question {@code open.eval.cn} (since 2.1.2), which have no
 * constant here: pass the string. Every other kernel ({@link #EN_CHOICE_REC}, {@link #EN_OPEN_EVAL},
 * {@link #EN_ASR_REC}, {@link #EN_ASR_EVAL}, {@link #EN_ALIGN_EVAL}, French, Japanese, Korean) answers
 * with errId 60003 and {@code 不支持的评测内核：<coreType>} without network I/O and logs one WARN line, or
 * throws IllegalArgumentException with that text after {@code YuguCompat.setThrowOnUnsupportedCoreType(true)}.
 */
public class CoreType {
    public static final String EN_SENT_EVAL = "sent.eval";
    public static final String EN_WORD_EVAL = "word.eval";
    public static final String EN_CHOICE_REC = "choice.rec";
    public static final String EN_OPEN_EVAL = "open.eval";
    public static final String EN_PARA_EVAL = "para.eval";
    public static final String EN_ASR_REC = "asr.rec";
    public static final String EN_ALIGN_EVAL = "align.eval";
    public static final String WORD_EVAL_PRO = "word.eval.pro";
    public static final String SENT_EVAL_PRO = "sent.eval.pro";
    public static final String CN_WORD_EVAL = "word.eval.cn";
    public static final String CN_SENT_EVAL = "sent.eval.cn";
    public static final String CN_PARA_EVAL = "para.eval.cn";
    public static final String EN_ASR_EVAL = "asr.eval";
    public static final String FR_WORD_EVAL = "word.eval.fr";
    public static final String FR_SENT_EVAL = "sent.eval.fr";
    public static final String FR_PARA_EVAL = "para.eval.fr";
    public static final String JP_WORD_EVAL = "word.eval.jp";
    public static final String JP_SENT_EVAL = "sent.eval.jp";
    public static final String JP_PARA_EVAL = "para.eval.jp";
    public static final String KR_WORD_EVAL = "word.eval.kr";
    public static final String KR_SENT_EVAL = "sent.eval.kr";
    public static final String KR_PARA_EVAL = "para.eval.kr";
}
