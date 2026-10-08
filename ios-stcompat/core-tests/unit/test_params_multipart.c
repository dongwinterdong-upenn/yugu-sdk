/*
 * Copyright 2026 优谷雅言 open.shengzhiai.com
 * SPDX-License-Identifier: Apache-2.0
 *
 * KYTestConfig property to form field mapping (one case per sent property) and the exact
 * multipart layout.
 */
#include "ygt.h"

typedef void (*setter)(ygst_test_params *p);

typedef struct {
    const char *property; /* KYTestConfig property name */
    setter set;
    const char *field;
    const char *value;
} map_case;

static void s_ref_text(ygst_test_params *p) { p->ref_text = "How are you"; }
static void s_ref_pinyin(ygst_test_params *p) { p->ref_pinyin = "chong2 qing4"; }
static void s_attach(ygst_test_params *p) { p->attach_audio_url = 1; }
static void s_phoneme_cmu(ygst_test_params *p) { p->phoneme_option = 1; }
static void s_phoneme_kk(ygst_test_params *p) { p->phoneme_option = 2; }
static void s_phoneme_ipa(ygst_test_params *p) { p->phoneme_option = 3; }
static void s_phoneme_output(ygst_test_params *p) { p->phoneme_output = 1; }
static void s_age1(ygst_test_params *p) { p->age_group = 1; }
static void s_age3(ygst_test_params *p) { p->age_group = 3; }
static void s_mode_home(ygst_test_params *p) { p->mode = 1; }
static void s_para_word(ygst_test_params *p) { p->paragraph_need_word_score = 1; }
static void s_scale(ygst_test_params *p) { p->scale = 100; }
static void s_scale_frac(ygst_test_params *p) { p->scale = 4.5; }
static void s_precision(ygst_test_params *p) { p->precision = 0.5; }
static void s_slack(ygst_test_params *p) { p->slack = -0.2; }
static void s_keywords(ygst_test_params *p) { p->keywords = "apple|banana"; }
static void s_qtype(ygst_test_params *p) { p->q_type = 6; }
static void s_lexicon(ygst_test_params *p) { p->customized_lexicon_json = "{\"apple\":[\"ae p l\"]}"; }
static void s_negative_reftext(ygst_test_params *p) { p->negative_reftext = "wrong answer"; }
static void s_dialect(ygst_test_params *p) { p->dict_dialect = "en_us"; }
static void s_nonscorable(ygst_test_params *p) { p->detect_nonscorable = 1; }
static void s_pron(ygst_test_params *p) { p->customized_pron_json = "{\"tomato\":\"t ah m aa t ow\"}"; }
static void s_rawtext(ygst_test_params *p) { p->output_rawtext = 1; }
static void s_vad_detection(ygst_test_params *p) { p->vad_detection = 1; }
static void s_keypoints(ygst_test_params *p) { p->keypoints_json = "[\"sunny\",\"warm\"]"; }
static void s_kp_weight(ygst_test_params *p) { p->keypoints_weight = 0.3; }
static void s_neg_kp(ygst_test_params *p) { p->negative_keypoints_json = "[\"rain\"]"; }
static void s_punctuate(ygst_test_params *p) { p->punctuate = 1; }
static void s_readtype(ygst_test_params *p) { p->readtype_diagnosis = 1; }
static void s_itn(ygst_test_params *p) { p->itn = 1; }
static void s_request(ygst_test_params *p) { p->request_json = "{\"slack\":0.1}"; }

static const map_case CASES[] = {
    {"refText", s_ref_text, "refText", "How are you"},
    {"refPinyin", s_ref_pinyin, "refPinyin", "chong2 qing4"},
    {"attachAudioUrl", s_attach, "attachAudioUrl", "1"},
    {"phonemeOption CMU", s_phoneme_cmu, "dict_type", "CMU"},
    {"phonemeOption KK", s_phoneme_kk, "dict_type", "KK"},
    {"phonemeOption IPA88", s_phoneme_ipa, "dict_type", "IPA88"},
    {"phoneme_output", s_phoneme_output, "phoneme_output", "1"},
    {"ageGroup Junior", s_age1, "agegroup", "1"},
    {"ageGroup Senior", s_age3, "agegroup", "3"},
    {"mode Home", s_mode_home, "mode", "home"},
    {"isParagraphNeedWordScore", s_para_word, "paragraph_need_word_score", "1"},
    {"scale", s_scale, "scale", "100"},
    {"scale fraction", s_scale_frac, "scale", "4.5"},
    {"precision", s_precision, "precision", "0.5"},
    {"slack", s_slack, "slack", "-0.2"},
    {"keywords", s_keywords, "keywords", "apple|banana"},
    {"qType", s_qtype, "qType", "6"},
    {"customized_lexicon", s_lexicon, "customized_lexicon", "{\"apple\":[\"ae p l\"]}"},
    {"negativeReftext", s_negative_reftext, "negativeReftext", "wrong answer"},
    {"dict_dialect", s_dialect, "dict_dialect", "en_us"},
    {"detect_nonscorable", s_nonscorable, "detect_nonscorable", "1"},
    {"customized_pron", s_pron, "customized_pron", "{\"tomato\":\"t ah m aa t ow\"}"},
    {"output_rawtext", s_rawtext, "output_rawtext", "1"},
    {"vad_detection", s_vad_detection, "vad_detction", "1"},
    {"keypoints", s_keypoints, "keypoints", "[\"sunny\",\"warm\"]"},
    {"keypoints_weight", s_kp_weight, "keypoints_weight", "0.3"},
    {"negative_keypoints", s_neg_kp, "negative_keypoints", "[\"rain\"]"},
    {"punctuate", s_punctuate, "punctuate", "1"},
    {"readtype_diagnosis", s_readtype, "readtype_diagnosis", "1"},
    {"itn", s_itn, "itn", "1"},
    {"request", s_request, "request", "{\"slack\":0.1}"},
};

static void test_params_default_sends_nothing(void) {
    ygst_test_params p;
    ygst_fields f;
    ygst_test_params_init(&p);
    ygst_fields_init(&f);
    CHECK_INT(ygst_params_to_fields(&p, &f), 0);
    CHECK_INT(f.count, 0);
    /* values that equal the engine default are not sent */
    p.age_group = 0;
    p.mode = 0;
    p.q_type = 0;
    p.phoneme_option = 0;
    p.scale = 0;
    p.ref_text = "";
    CHECK_INT(ygst_params_to_fields(&p, &f), 0);
    CHECK_INT(f.count, 0);
    p.age_group = 9;     /* out of range: not sent */
    p.phoneme_option = 7; /* out of range: not sent */
    CHECK_INT(ygst_params_to_fields(&p, &f), 0);
    CHECK_INT(f.count, 0);
    ygst_fields_free(&f);
}

static void test_params_every_property(void) {
    size_t i;
    for (i = 0; i < sizeof CASES / sizeof CASES[0]; i++) {
        ygst_test_params p;
        ygst_fields f;
        ygst_test_params_init(&p);
        ygst_fields_init(&f);
        CASES[i].set(&p);
        CHECK_INT(ygst_params_to_fields(&p, &f), 0);
        CHECK_INT(f.count, 1);
        if (f.count == 1) {
            CHECK_STR(f.items[0].name, CASES[i].field);
            CHECK_STR(f.items[0].value, CASES[i].value);
        } else {
            fprintf(stderr, "    property %s\n", CASES[i].property);
        }
        ygst_fields_free(&f);
    }
    printf("     KYTestConfig mapping cases: %zu\n", sizeof CASES / sizeof CASES[0]);
}

static void test_params_custom_params(void) {
    ygst_test_params p;
    ygst_fields custom, f;
    ygst_test_params_init(&p);
    ygst_fields_init(&custom);
    ygst_fields_init(&f);
    ygst_fields_set(&custom, "language", "en-US");
    ygst_fields_set(&custom, "refText", "override");
    ygst_fields_set(&custom, "bad\"name", "x");
    ygst_fields_set(&custom, "bad\nname", "x");
    ygst_fields_set(&custom, "empty", "");
    p.ref_text = "original";
    p.scale = 100;
    p.request_json = "{\"x\":1}";
    p.custom_params = &custom;
    CHECK_INT(ygst_params_to_fields(&p, &f), 0);
    CHECK_INT(f.count, 4);
    CHECK_STR(f.items[0].name, "refText"); /* overridden in place, like a Java LinkedHashMap put */
    CHECK_STR(f.items[0].value, "override");
    CHECK_STR(f.items[1].name, "scale");
    CHECK_STR(f.items[2].name, "language");
    CHECK_STR(f.items[3].name, "request"); /* request after customParams, as on Android */
    CHECK(ygst_fields_get(&f, "empty") == NULL); /* empty values are not sent */
    CHECK(ygst_fields_get(&f, "bad\"name") == NULL);
    ygst_fields_free(&custom);
    ygst_fields_free(&f);
}

static void test_params_paragraph_rule(void) {
    ygst_test_params p;
    ygst_fields custom, f;
    ygst_test_params_init(&p);
    ygst_fields_init(&custom);
    ygst_fields_init(&f);
    CHECK(ygst_coretype_is_paragraph("para.eval"));
    CHECK(ygst_coretype_is_paragraph("para.eval.cn"));
    CHECK(!ygst_coretype_is_paragraph("sent.eval"));
    CHECK(!ygst_coretype_is_paragraph(NULL));
    /* para.* always asks for word details, in the position of the mapped flag */
    p.core_type = "para.eval.cn";
    p.ref_text = "今天天气很好";
    p.scale = 100;
    p.attach_audio_url = 1;
    CHECK_INT(ygst_params_to_fields(&p, &f), 0);
    CHECK_INT(f.count, 4);
    CHECK_STR(f.items[2].name, "paragraph_need_word_score");
    CHECK_STR(f.items[2].value, "1");
    CHECK_STR(f.items[3].name, "attachAudioUrl");
    ygst_fields_free(&f);
    ygst_fields_init(&f);
    /* customParams cannot switch it off */
    ygst_fields_set(&custom, "paragraph_need_word_score", "0");
    p.custom_params = &custom;
    CHECK_INT(ygst_params_to_fields(&p, &f), 0);
    CHECK_STR(ygst_fields_get(&f, "paragraph_need_word_score"), "1");
    CHECK_STR(f.items[2].name, "paragraph_need_word_score");
    ygst_fields_free(&f);
    ygst_fields_init(&f);
    /* other kernels only with isParagraphNeedWordScore */
    p.core_type = "sent.eval";
    p.custom_params = NULL;
    CHECK_INT(ygst_params_to_fields(&p, &f), 0);
    CHECK(ygst_fields_get(&f, "paragraph_need_word_score") == NULL);
    ygst_fields_free(&f);
    ygst_fields_init(&f);
    /* the C API path: request fields first, then the coreType rule */
    ygst_fields_set(&f, "refText", "x");
    CHECK_INT(ygst_fields_apply_coretype_rules(&f, "para.eval"), 0);
    CHECK_INT(f.count, 2);
    CHECK_STR(ygst_fields_get(&f, "paragraph_need_word_score"), "1");
    CHECK_INT(ygst_fields_apply_coretype_rules(&f, "word.eval"), 0);
    CHECK_INT(f.count, 2);
    ygst_fields_free(&f);
    ygst_fields_free(&custom);
}

static void test_params_combined_signature(void) {
    /* the compat_form vector: refPinyin "" is dropped, paragraph flag becomes "1" */
    ygst_test_params p;
    ygst_fields custom, f;
    char sig[45];
    ygst_test_params_init(&p);
    ygst_fields_init(&custom);
    ygst_fields_init(&f);
    ygst_fields_set(&custom, "language", "en-US");
    p.ref_text = "How are you";
    p.ref_pinyin = "";
    p.paragraph_need_word_score = 1;
    p.custom_params = &custom;
    CHECK_INT(ygst_params_to_fields(&p, &f), 0);
    CHECK_INT(ygst_sign(&f, "test_secret_key_123", sig), 0);
    CHECK_STR(sig, "YVajpFF/THZKqoMEJ1YgumyNifcelD4k6SgggXMZKao=");
    ygst_fields_free(&custom);
    ygst_fields_free(&f);
}

static void test_fmt_double(void) {
    char out[64];
    char small[3];
    CHECK_INT(ygst_fmt_double(0, out, sizeof out), 0);
    CHECK_STR(out, "0");
    CHECK_INT(ygst_fmt_double(-0.0, out, sizeof out), 0);
    CHECK_STR(out, "0");
    ygst_fmt_double(100, out, sizeof out);
    CHECK_STR(out, "100");
    ygst_fmt_double(-1, out, sizeof out);
    CHECK_STR(out, "-1");
    ygst_fmt_double(0.2, out, sizeof out);
    CHECK_STR(out, "0.2");
    ygst_fmt_double(0.1 + 0.2, out, sizeof out);
    CHECK_STR(out, "0.30000000000000004");
    ygst_fmt_double(123456.789, out, sizeof out);
    CHECK_STR(out, "123456.789");
    ygst_fmt_double(1e-7, out, sizeof out);
    CHECK_STR(out, "0.0000001");
    ygst_fmt_double(1.5e-7, out, sizeof out);
    CHECK_STR(out, "0.00000015");
    ygst_fmt_double(-2.5e-5, out, sizeof out);
    CHECK_STR(out, "-0.000025");
    ygst_fmt_double(1e20, out, sizeof out);
    CHECK_STR(out, "100000000000000000000");
    ygst_fmt_double(73.73, out, sizeof out);
    CHECK_STR(out, "73.73");
    CHECK_INT(ygst_fmt_double(0.0 / 0.0, out, sizeof out), -1);
    CHECK_INT(ygst_fmt_double(1e308 * 10, out, sizeof out), -1);
    CHECK_INT(ygst_fmt_double(12345, small, sizeof small), -1);
    CHECK_INT(ygst_fmt_double(1, NULL, 0), -1);
    CHECK(ygst_field_name_ok("refText"));
    CHECK(!ygst_field_name_ok(""));
    CHECK(!ygst_field_name_ok(NULL));
    CHECK(!ygst_field_name_ok("a\rb"));
}

/* ---------------------------------------------------------------- multipart */

static void test_multipart_layout(void) {
    ygst_fields f;
    ygst_buf b;
    char ct[128];
    const unsigned char audio[] = {'R', 'I', 'F', 'F', 0, 1, 2};
    const char *want =
        "--BOUND\r\n"
        "Content-Disposition: form-data; name=\"refText\"\r\n"
        "\r\n"
        "今天天气很好\r\n"
        "--BOUND\r\n"
        "Content-Disposition: form-data; name=\"scale\"\r\n"
        "\r\n"
        "100\r\n"
        "--BOUND\r\n"
        "Content-Disposition: form-data; name=\"audio\"; filename=\"audio.wav\"\r\n"
        "Content-Type: audio/wav\r\n"
        "\r\n";
    size_t wl = strlen(want);
    ygst_fields_init(&f);
    ygst_buf_init(&b);
    ygst_fields_set(&f, "refText", "今天天气很好");
    ygst_fields_set(&f, "scale", "100");
    CHECK_INT(ygst_multipart_build(&f, "BOUND", "audio", "audio.wav", "audio/wav", audio, sizeof audio, &b), 0);
    CHECK_INT(b.len, wl + sizeof audio + strlen("\r\n--BOUND--\r\n"));
    CHECK(memcmp(b.data, want, wl) == 0);
    CHECK(memcmp(b.data + wl, audio, sizeof audio) == 0);
    CHECK(memcmp(b.data + wl + sizeof audio, "\r\n--BOUND--\r\n", 13) == 0);
    CHECK_INT(ygst_multipart_content_type("BOUND", ct, sizeof ct), 0);
    CHECK_STR(ct, "multipart/form-data; boundary=BOUND");
    CHECK_INT(ygst_multipart_content_type("BOUND", ct, 10), -1);
    ygst_fields_free(&f);
    ygst_buf_free(&b);
}

static void test_multipart_guards(void) {
    ygst_fields f;
    ygst_buf b;
    unsigned char rnd[16];
    char boundary[64];
    const unsigned char with_boundary[] = "xx--BOUNDyy";
    size_t i;
    for (i = 0; i < 16; i++) rnd[i] = (unsigned char)(i * 17);
    ygst_multipart_boundary(rnd, boundary);
    CHECK_STR(boundary, "----YuguSTCompat00112233445566778899aabbccddeeff");
    ygst_fields_init(&f);
    ygst_buf_init(&b);
    ygst_fields_set(&f, "refText", "contains BOUND inside");
    CHECK_INT(ygst_multipart_build(&f, "BOUND", "audio", "a.wav", NULL, NULL, 0, &b), -2);
    ygst_fields_free(&f);
    ygst_fields_init(&f);
    CHECK_INT(ygst_multipart_build(&f, "BOUND", "audio", "a.wav", NULL, with_boundary, sizeof with_boundary - 1, &b), -2);
    CHECK_INT(ygst_multipart_build(&f, "", "audio", "a.wav", NULL, NULL, 0, &b), -1);
    CHECK_INT(ygst_multipart_build(&f, "B", "au\"dio", "a.wav", NULL, NULL, 0, &b), -1);
    CHECK_INT(ygst_multipart_build(&f, "B", "audio", NULL, NULL, NULL, 0, &b), -1);
    ygst_fields_set(&f, "x\r", "v");
    CHECK_INT(ygst_multipart_build(&f, "B", "audio", "a.wav", NULL, NULL, 0, &b), -1);
    ygst_fields_free(&f);
    ygst_fields_init(&f);
    ygst_buf_reset(&b);
    CHECK_INT(ygst_multipart_build(&f, "B", "audio", "a.bin", NULL, NULL, 0, &b), 0);
    CHECK(strstr(ygst_buf_cstr(&b), "Content-Type: application/octet-stream") != NULL);
    ygst_fields_free(&f);
    ygst_buf_free(&b);
}

static void test_audio_types(void) {
    CHECK_STR(ygst_audio_ext(NULL), "wav");
    CHECK_STR(ygst_audio_ext(""), "wav");
    CHECK_STR(ygst_audio_ext("WAV"), "wav");
    CHECK_STR(ygst_audio_ext(".mp3"), "mp3");
    CHECK_STR(ygst_audio_ext("pcm"), "wav");
    CHECK_STR(ygst_audio_ext("wave"), "wav");
    CHECK_STR(ygst_audio_ext("mpeg"), "mp3");
    CHECK_STR(ygst_audio_ext("opus"), "opus");
    CHECK_STR(ygst_audio_ext("xyz"), "bin");
    CHECK_STR(ygst_audio_content_type("wav"), "audio/wav");
    CHECK_STR(ygst_audio_content_type("mp3"), "audio/mpeg");
    CHECK_STR(ygst_audio_content_type("ogg"), "audio/ogg");
    CHECK_STR(ygst_audio_content_type("speex"), "audio/ogg");
    CHECK_STR(ygst_audio_content_type("opus"), "audio/opus");
    CHECK_STR(ygst_audio_content_type("amr"), "audio/amr");
    CHECK_STR(ygst_audio_content_type("aac"), "audio/aac");
    CHECK_STR(ygst_audio_content_type("m4a"), "audio/mp4");
    CHECK_STR(ygst_audio_content_type("flv"), "video/x-flv");
    CHECK_STR(ygst_audio_content_type("silk"), "application/octet-stream");
    CHECK_STR(ygst_audio_content_type(NULL), "application/octet-stream");
    CHECK(ygst_audio_type_is_pcm(NULL));
    CHECK(ygst_audio_type_is_pcm("wav"));
    CHECK(ygst_audio_type_is_pcm(" PCM "));
    CHECK(!ygst_audio_type_is_pcm("mp3"));
}

void suite_params(void) {
    RUN("params", test_params_default_sends_nothing);
    RUN("params", test_params_every_property);
    RUN("params", test_params_custom_params);
    RUN("params", test_params_paragraph_rule);
    RUN("params", test_params_combined_signature);
    RUN("params", test_fmt_double);
}

void suite_multipart(void) {
    RUN("multipart", test_multipart_layout);
    RUN("multipart", test_multipart_guards);
    RUN("multipart", test_audio_types);
}
