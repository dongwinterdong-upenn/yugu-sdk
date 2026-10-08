// SPDX-License-Identifier: Apache-2.0
package com.stkouyu;

/**
 * Evaluation kernels (Shengtong names). Supported on the Yugu platform: {@code word.eval},
 * {@code word.eval.pro}, {@code sent.eval}, {@code sent.eval.pro}, {@code para.eval},
 * {@code word.eval.cn}, {@code sent.eval.cn}, {@code para.eval.cn}, plus {@code alpha.eval} and
 * {@code pinyin}. Every other kernel answers with errId 60003 without network I/O.
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
