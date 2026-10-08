/*
 * Copyright 2026 优谷雅言 open.shengzhiai.com
 * SPDX-License-Identifier: Apache-2.0
 *
 * coreType resolution and the mapping from KYTestConfig properties to the form fields of
 * POST /{coreType}. The table mirrors DESIGN 6.1 so that iOS and Android send the same fields.
 */
#include "ygst_core.h"

#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* KYTestType in declaration order (KYTestConfig.h). NULL: Shengtong publishes no string. */
static const char *const KY_TEST_TYPES[] = {
    "word.eval",     /* KYTestType_Word */
    "sent.eval",     /* KYTestType_Sentence */
    "para.eval",     /* KYTestType_Paragraph */
    "open.eval",     /* KYTestType_Open */
    "choice.rec",    /* KYTestType_Choice */
    "asr.rec",       /* KYTestType_Asr */
    "align.eval",    /* KYTestType_Align */
    "word.eval.pro", /* KYTestType_Word_Pro */
    "sent.eval.pro", /* KYTestType_Sentence_Pro */
    "word.eval.cn",  /* KYTestType_Word_Cn */
    "sent.eval.cn",  /* KYTestType_Sentence_Cn */
    "para.eval.cn",  /* KYTestType_Paragraph_Cn */
    "asr.eval",      /* KYTestType_AsrEval */
    "word.eval.fr",  /* KYTestType_Word_fr */
    "sent.eval.fr",  /* KYTestType_Sentence_fr */
    "para.eval.fr",  /* KYTestType_Paragraph_fr */
    "word.eval.kr",  /* KYTestType_Word_kr */
    "sent.eval.kr",  /* KYTestType_Sentence_kr */
    "para.eval.kr",  /* KYTestType_Paragraph_kr */
    "word.eval.jp",  /* KYTestType_Word_jp */
    "sent.eval.jp",  /* KYTestType_Sentence_jp */
    "para.eval.jp",  /* KYTestType_Paragraph_jp */
    NULL             /* KYTestType_Wordspell */
};

/* coreTypes the platform evaluates on POST /{coreType} (DESIGN 6.1). */
static const char *const SUPPORTED[] = {"word.eval",    "word.eval.pro", "sent.eval",    "sent.eval.pro",
                                        "para.eval",    "word.eval.cn",  "sent.eval.cn", "para.eval.cn",
                                        "alpha.eval",   "pinyin"};

/* Shengtong coreTypes that are answered with errId 60003 without network I/O. */
static const char *const UNSUPPORTED[] = {"choice.rec",   "open.eval",    "asr.rec",      "asr.eval",
                                          "align.eval",   "word.eval.fr", "sent.eval.fr", "para.eval.fr",
                                          "word.eval.jp", "sent.eval.jp", "para.eval.jp", "word.eval.kr",
                                          "sent.eval.kr", "para.eval.kr"};

const char *ygst_coretype_for_enum(unsigned long kytesttype) {
    if (kytesttype >= sizeof KY_TEST_TYPES / sizeof KY_TEST_TYPES[0]) return NULL;
    return KY_TEST_TYPES[kytesttype];
}

size_t ygst_coretype_enum_count(void) {
    return sizeof KY_TEST_TYPES / sizeof KY_TEST_TYPES[0];
}

ygst_ct_status ygst_coretype_check(const char *core_type) {
    size_t i;
    if (!core_type || !core_type[0]) return YGST_CT_UNKNOWN;
    for (i = 0; i < sizeof SUPPORTED / sizeof SUPPORTED[0]; i++) {
        if (strcmp(core_type, SUPPORTED[i]) == 0) return YGST_CT_SUPPORTED;
    }
    for (i = 0; i < sizeof UNSUPPORTED / sizeof UNSUPPORTED[0]; i++) {
        if (strcmp(core_type, UNSUPPORTED[i]) == 0) return YGST_CT_UNSUPPORTED;
    }
    return YGST_CT_UNKNOWN;
}

ygst_ct_status ygst_coretype_resolve(const char *core_type_ns, unsigned long kytesttype, char *out, size_t cap) {
    const char *mapped;
    if (!out || cap == 0) return YGST_CT_UNKNOWN;
    out[0] = 0;
    if (!ygst_is_blank(core_type_ns)) {
        ygst_trim_copy(core_type_ns, out, cap);
        return ygst_coretype_check(out);
    }
    mapped = ygst_coretype_for_enum(kytesttype);
    if (!mapped) return YGST_CT_UNSUPPORTED;
    if (strlen(mapped) >= cap) return YGST_CT_UNKNOWN;
    strcpy(out, mapped);
    return ygst_coretype_check(out);
}

int ygst_coretype_is_chinese(const char *core_type) {
    return core_type && (ygst_has_suffix_ci(core_type, ".cn") || strcmp(core_type, "pinyin") == 0);
}

int ygst_coretype_is_paragraph(const char *core_type) {
    return core_type && strncmp(core_type, "para.", 5) == 0;
}

int ygst_fields_apply_coretype_rules(ygst_fields *f, const char *core_type) {
    if (ygst_coretype_is_paragraph(core_type)) return ygst_fields_set(f, "paragraph_need_word_score", "1");
    return 0;
}

int ygst_validate_request(ygst_ct_status status, const char *core_type, const char *ref_text,
                          const char *ref_pinyin) {
    if (status != YGST_CT_SUPPORTED) return YGST_ERRID_CORETYPE_UNSUPPORTED;
    if (!ygst_is_blank(ref_text)) return 0;
    if (!ygst_is_blank(ref_pinyin) && ygst_coretype_is_chinese(core_type)) return 0;
    return YGST_ERRID_REFTEXT_EMPTY;
}

/* ------------------------------------------------------------------ number text */

static void fix_decimal_point(char *s) {
    for (; *s; s++) {
        if (*s == ',') *s = '.';
    }
}

static void trim_fraction_zeros(char *s) {
    char *dot = strchr(s, '.');
    char *end;
    if (!dot) return;
    end = s + strlen(s);
    while (end > dot + 1 && end[-1] == '0') end--;
    if (end == dot + 1) end = dot;
    *end = 0;
}

int ygst_fmt_double(double v, char *out, size_t cap) {
    char tmp[64];
    int p;
    if (!out || cap == 0) return -1;
    out[0] = 0;
    if (v != v || v > 1.7976931348623157e308 || v < -1.7976931348623157e308) return -1; /* NaN, inf */
    if (v == 0) {
        tmp[0] = '0';
        tmp[1] = 0;
    } else if (fabs(v) < 9.0e15 && v == floor(v)) {
        snprintf(tmp, sizeof tmp, "%.0f", v);
    } else if (fabs(v) >= 9.0e15) {
        snprintf(tmp, sizeof tmp, "%.0f", v);
    } else {
        for (p = 1; p <= 17; p++) {
            char *end = NULL;
            double back;
            snprintf(tmp, sizeof tmp, "%.*g", p, v);
            fix_decimal_point(tmp);
            back = strtod(tmp, &end);
            if (end && *end) {
                /* decimal comma locale: compare through the locale's own separator */
                char alt[64];
                char *dot;
                strcpy(alt, tmp);
                dot = strchr(alt, '.');
                if (dot) *dot = ',';
                back = strtod(alt, NULL);
            }
            if (back == v) break;
        }
        if (strchr(tmp, 'e') || strchr(tmp, 'E')) {
            /* plain decimal: enough fraction digits to keep the significant ones */
            int exp10 = (int)floor(log10(fabs(v)));
            int digits = (p > 17 ? 17 : p) - 1 - exp10;
            if (digits < 0) digits = 0;
            if (digits > 40) digits = 40;
            snprintf(tmp, sizeof tmp, "%.*f", digits, v);
            fix_decimal_point(tmp);
            trim_fraction_zeros(tmp);
        }
    }
    fix_decimal_point(tmp);
    if (strcmp(tmp, "-0") == 0) strcpy(tmp, "0");
    if (strlen(tmp) >= cap) return -1;
    strcpy(out, tmp);
    return 0;
}

int ygst_field_name_ok(const char *name) {
    if (!name || !name[0]) return 0;
    for (; *name; name++) {
        if (*name == '"' || *name == '\r' || *name == '\n') return 0;
    }
    return 1;
}

/* ------------------------------------------------------------------ mapping */

void ygst_test_params_init(ygst_test_params *p) {
    memset(p, 0, sizeof *p);
}

static int set_str(ygst_fields *f, const char *name, const char *v) {
    if (!v || !v[0]) return 0;
    return ygst_fields_set(f, name, v);
}

static int set_flag(ygst_fields *f, const char *name, int on) {
    return on ? ygst_fields_set(f, name, "1") : 0;
}

static int set_num(ygst_fields *f, const char *name, double v) {
    char tmp[64];
    if (v == 0 || ygst_fmt_double(v, tmp, sizeof tmp) != 0) return 0;
    return ygst_fields_set(f, name, tmp);
}

static int set_int(ygst_fields *f, const char *name, long v) {
    char tmp[32];
    snprintf(tmp, sizeof tmp, "%ld", v);
    return ygst_fields_set(f, name, tmp);
}

int ygst_params_to_fields(const ygst_test_params *p, ygst_fields *out) {
    static const char *const DICT[] = {NULL, "CMU", "KK", "IPA88"};
    int rc = 0;
    rc |= set_str(out, "refText", p->ref_text);
    rc |= set_str(out, "refPinyin", p->ref_pinyin);
    if (p->age_group >= 1 && p->age_group <= 3) rc |= set_int(out, "agegroup", p->age_group);
    rc |= set_num(out, "scale", p->scale);
    rc |= set_num(out, "precision", p->precision);
    rc |= set_num(out, "slack", p->slack);
    rc |= set_flag(out, "paragraph_need_word_score",
                   p->paragraph_need_word_score || ygst_coretype_is_paragraph(p->core_type));
    rc |= set_flag(out, "phoneme_output", p->phoneme_output);
    rc |= set_flag(out, "attachAudioUrl", p->attach_audio_url);
    if (p->phoneme_option >= 1 && p->phoneme_option <= 3) rc |= set_str(out, "dict_type", DICT[p->phoneme_option]);
    rc |= set_str(out, "dict_dialect", p->dict_dialect);
    rc |= set_str(out, "customized_lexicon", p->customized_lexicon_json);
    rc |= set_str(out, "customized_pron", p->customized_pron_json);
    if (p->readtype_diagnosis != 0) rc |= set_num(out, "readtype_diagnosis", p->readtype_diagnosis);
    rc |= set_flag(out, "output_rawtext", p->output_rawtext);
    rc |= set_flag(out, "punctuate", p->punctuate);
    rc |= set_num(out, "itn", p->itn);
    rc |= set_flag(out, "detect_nonscorable", p->detect_nonscorable);
    /* KYTestConfig.vad_detection travels under the Android RecordSetting name. */
    rc |= set_flag(out, "vad_detction", p->vad_detection);
    rc |= set_str(out, "keywords", p->keywords);
    rc |= set_str(out, "keypoints", p->keypoints_json);
    rc |= set_num(out, "keypoints_weight", p->keypoints_weight);
    rc |= set_str(out, "negative_keypoints", p->negative_keypoints_json);
    rc |= set_str(out, "negativeReftext", p->negative_reftext);
    if (p->mode == 1) rc |= ygst_fields_set(out, "mode", "home");
    if (p->q_type != 0) rc |= set_int(out, "qType", p->q_type);
    /* customParams in the order of the Android RecordSetting newParams: a name that is already
     * mapped keeps its position and takes the new value; request comes last */
    if (p->custom_params) {
        size_t i;
        for (i = 0; i < p->custom_params->count; i++) {
            const ygst_field *cp = &p->custom_params->items[i];
            if (!ygst_field_name_ok(cp->name) || !cp->value || !cp->value[0]) continue;
            rc |= ygst_fields_set(out, cp->name, cp->value);
        }
    }
    rc |= set_str(out, "request", p->request_json);
    /* customParams cannot switch the paragraph word details off */
    rc |= ygst_fields_apply_coretype_rules(out, p->core_type);
    return (rc != 0 || out->oom) ? -1 : 0;
}
