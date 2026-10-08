/*
 * Copyright 2026 优谷雅言 open.shengzhiai.com
 * SPDX-License-Identifier: Apache-2.0
 */
#include "ygt.h"

static ygst_span sp(const char *s) {
    ygst_span v;
    v.p = s;
    v.n = strlen(s);
    return v;
}

static void test_json_validate(void) {
    static const char *good[] = {"{}", "[]", "0", "-0.5e+10", "1E5", "\"x\"", "true", "false", "null",
                                 " {\"a\":[1,2,{\"b\":null}],\"c\":\"\\u00e9\\n\"} ", "[[[]]]", "\"\\/\\b\\f\\r\\t\""};
    static const char *bad[] = {"", " ", "{", "{\"a\"}", "{\"a\":}", "[1,]", "01", "1.", "-", "1e", "\"\\x\"",
                                "\"\x01\"", "tru", "{} x", "[1 2]", "{\"a\":1,}", "{1:2}", "\"abc", "\"\\u12\"",
                                "nul", "[", "{\"a\":1 \"b\":2}", "+1", ".5"};
    size_t i;
    ygst_span root;
    char deep[400];
    for (i = 0; i < sizeof good / sizeof good[0]; i++) {
        int ok = ygst_json_parse(good[i], strlen(good[i]), &root) == 0;
        CHECK(ok);
        if (!ok) fprintf(stderr, "    good[%zu] = %s\n", i, good[i]);
    }
    for (i = 0; i < sizeof bad / sizeof bad[0]; i++) {
        int rejected = ygst_json_parse(bad[i], strlen(bad[i]), &root) != 0;
        CHECK(rejected);
        if (!rejected) fprintf(stderr, "    bad[%zu] = %s\n", i, bad[i]);
    }
    CHECK_INT(ygst_json_parse(NULL, 0, &root), -1);
    CHECK_INT(ygst_json_parse(" [1] ", 5, &root), 0);
    CHECK_INT(root.n, 3);
    memset(deep, '[', 200);
    memset(deep + 200, ']', 200);
    CHECK_INT(ygst_json_parse(deep, 400, &root), -1); /* depth limit */
    CHECK_INT(ygst_json_parse(deep + 100, 200, &root), 0);
}

static void test_json_member_and_iteration(void) {
    const char *doc = "{\"a\":1,\"b\":{\"c\":[10,\"x\",true]},\"a\":2,\"\\u0064\":\"esc\",\"e\":null}";
    ygst_span root, v, el, k;
    size_t pos = 0;
    int count = 0;
    long long n = 0;
    ygst_buf b;
    ygst_buf_init(&b);
    CHECK_INT(ygst_json_parse(doc, strlen(doc), &root), 0);
    CHECK_INT(ygst_json_type(root), 'o');
    CHECK_INT(ygst_json_member(root, "a", &v), 1);
    CHECK_INT(ygst_json_int(v, &n), 0);
    CHECK_INT(n, 2); /* last duplicate wins */
    CHECK_INT(ygst_json_member(root, "d", &v), 1);
    ygst_json_string(v, &b);
    CHECK_STR(ygst_buf_cstr(&b), "esc");
    CHECK_INT(ygst_json_member(root, "zz", &v), 0);
    CHECK_INT(ygst_json_member(root, "e", &v), 1);
    CHECK_INT(ygst_json_type(v), 'z');
    CHECK_INT(ygst_json_member(sp("[1]"), "a", &v), -1);
    CHECK_INT(ygst_json_member(sp("{\"a\" 1}"), "a", &v), -1);
    CHECK_INT(ygst_json_member(root, "b", &v), 1);
    CHECK_INT(ygst_json_member(v, "c", &v), 1);
    while (ygst_json_array_next(v, &pos, &el) == 1) count++;
    CHECK_INT(count, 3);
    pos = 0;
    CHECK_INT(ygst_json_array_next(sp("[]"), &pos, &el), 0);
    pos = 0;
    CHECK_INT(ygst_json_array_next(sp("{}"), &pos, &el), -1);
    pos = 0;
    CHECK_INT(ygst_json_array_next(sp("[1;2]"), &pos, &el), 1);
    CHECK_INT(ygst_json_array_next(sp("[1;2]"), &pos, &el), -1);
    pos = 0;
    count = 0;
    while (ygst_json_object_next(root, &pos, &k, &v) == 1) count++;
    CHECK_INT(count, 5);
    pos = 0;
    CHECK_INT(ygst_json_object_next(sp("[]"), &pos, &k, &v), -1);
    pos = 0;
    CHECK_INT(ygst_json_object_next(sp("{}"), &pos, &k, &v), 0);
    ygst_buf_free(&b);
}

static void test_json_strings(void) {
    ygst_buf b;
    ygst_buf_init(&b);
    CHECK_INT(ygst_json_string(sp("\"\\u4f60\\u597d\""), &b), 0);
    CHECK_STR(ygst_buf_cstr(&b), "你好");
    ygst_buf_reset(&b);
    CHECK_INT(ygst_json_string(sp("\"\\ud83d\\ude00!\""), &b), 0);
    CHECK_STR(ygst_buf_cstr(&b), "\xF0\x9F\x98\x80!");
    ygst_buf_reset(&b);
    CHECK_INT(ygst_json_string(sp("\"\\ud83dx\""), &b), 0);
    CHECK_STR(ygst_buf_cstr(&b), "\xEF\xBF\xBDx");
    ygst_buf_reset(&b);
    CHECK_INT(ygst_json_string(sp("\"\\ud83d\\u0041\""), &b), 0);
    CHECK_STR(ygst_buf_cstr(&b), "\xEF\xBF\xBD" "A"); /* lone high surrogate, then a normal escape */
    ygst_buf_reset(&b);
    CHECK_INT(ygst_json_string(sp("\"\\ude00\""), &b), 0);
    CHECK_STR(ygst_buf_cstr(&b), "\xEF\xBF\xBD");
    ygst_buf_reset(&b);
    CHECK_INT(ygst_json_string(sp("\"a\\n\\t\\\"\\\\\\/\\b\\f\\r\\u00e9\\u0800\""), &b), 0);
    CHECK_STR(ygst_buf_cstr(&b), "a\n\t\"\\/\b\f\r\xC3\xA9\xE0\xA0\x80");
    ygst_buf_reset(&b);
    CHECK_INT(ygst_json_string(sp("12"), &b), -1);
    CHECK_INT(ygst_json_string(sp("\"open"), &b), -1);
    ygst_buf_reset(&b);
    ygst_json_escape(&b, "q\"b\\n\n\r\t\b\f\x01\x1f\xE4\xBD\xA0");
    CHECK_STR(ygst_buf_cstr(&b), "\"q\\\"b\\\\n\\n\\r\\t\\b\\f\\u0001\\u001f\xE4\xBD\xA0\"");
    ygst_buf_reset(&b);
    ygst_json_escape(&b, NULL);
    CHECK_STR(ygst_buf_cstr(&b), "\"\"");
    ygst_buf_free(&b);
}

static void test_json_numbers(void) {
    long long n = 0;
    double d = 0;
    int t = -1;
    CHECK_INT(ygst_json_int(sp("40001"), &n), 0);
    CHECK_INT(n, 40001);
    CHECK_INT(ygst_json_int(sp("-7"), &n), 0);
    CHECK_INT(n, -7);
    CHECK_INT(ygst_json_int(sp("2.0"), &n), 0);
    CHECK_INT(n, 2);
    CHECK_INT(ygst_json_int(sp("4e2"), &n), 0);
    CHECK_INT(n, 400);
    CHECK_INT(ygst_json_int(sp("2.5"), &n), -1);
    CHECK_INT(ygst_json_int(sp("99999999999999999999"), &n), -1);
    CHECK_INT(ygst_json_int(sp("1e300"), &n), -1);
    CHECK_INT(ygst_json_int(sp("\"1\""), &n), -1);
    CHECK_INT(ygst_json_number(sp("73.73"), &d), 0);
    CHECK(d == 73.73);
    CHECK_INT(ygst_json_number(sp("-1.5e-3"), &d), 0);
    CHECK(d == -0.0015);
    CHECK_INT(ygst_json_number(sp("x"), &d), -1);
    CHECK_INT(ygst_json_bool(sp("true"), &t), 0);
    CHECK_INT(t, 1);
    CHECK_INT(ygst_json_bool(sp("false"), &t), 0);
    CHECK_INT(t, 0);
    CHECK_INT(ygst_json_bool(sp("null"), &t), -1);
    CHECK_INT(ygst_json_type(sp("")), 0);
    CHECK_INT(ygst_json_type(sp("x")), 0);
    CHECK_INT(ygst_json_type(sp("-1")), 'n');
}

/* ---------------------------------------------------------------- coreType */

static void test_coretype_enum_table(void) {
    /* KYTestConfig.h declaration order, written out independently of the implementation */
    static const char *want[] = {"word.eval",    "sent.eval",    "para.eval",    "open.eval",    "choice.rec",
                                 "asr.rec",      "align.eval",   "word.eval.pro", "sent.eval.pro", "word.eval.cn",
                                 "sent.eval.cn", "para.eval.cn", "asr.eval",     "word.eval.fr", "sent.eval.fr",
                                 "para.eval.fr", "word.eval.kr", "sent.eval.kr", "para.eval.kr", "word.eval.jp",
                                 "sent.eval.jp", "para.eval.jp"};
    size_t i;
    CHECK_INT(ygst_coretype_enum_count(), 23);
    for (i = 0; i < sizeof want / sizeof want[0]; i++) CHECK_STR(ygst_coretype_for_enum(i), want[i]);
    CHECK(ygst_coretype_for_enum(22) == NULL); /* KYTestType_Wordspell */
    CHECK(ygst_coretype_for_enum(23) == NULL);
    CHECK(ygst_coretype_for_enum((unsigned long)-1) == NULL);
}

static void test_coretype_support(void) {
    static const char *supported[] = {"word.eval",    "word.eval.pro", "sent.eval",  "sent.eval.pro", "para.eval",
                                      "word.eval.cn", "sent.eval.cn",  "para.eval.cn", "alpha.eval",  "pinyin"};
    static const char *unsupported[] = {"choice.rec",   "open.eval",    "asr.rec",      "asr.eval",    "align.eval",
                                        "word.eval.fr", "sent.eval.fr", "para.eval.fr", "word.eval.jp", "sent.eval.jp",
                                        "para.eval.jp", "word.eval.kr", "sent.eval.kr", "para.eval.kr"};
    size_t i;
    for (i = 0; i < sizeof supported / sizeof supported[0]; i++)
        CHECK_INT(ygst_coretype_check(supported[i]), YGST_CT_SUPPORTED);
    for (i = 0; i < sizeof unsupported / sizeof unsupported[0]; i++)
        CHECK_INT(ygst_coretype_check(unsupported[i]), YGST_CT_UNSUPPORTED);
    CHECK_INT(ygst_coretype_check("sent.eval.promax"), YGST_CT_UNKNOWN);
    CHECK_INT(ygst_coretype_check("SENT.EVAL"), YGST_CT_UNKNOWN);
    CHECK_INT(ygst_coretype_check(""), YGST_CT_UNKNOWN);
    CHECK_INT(ygst_coretype_check(NULL), YGST_CT_UNKNOWN);
    /* every enum value maps to a supported or a known unsupported coreType */
    for (i = 0; i < ygst_coretype_enum_count(); i++) {
        const char *s = ygst_coretype_for_enum(i);
        if (s) CHECK(ygst_coretype_check(s) != YGST_CT_UNKNOWN);
    }
    CHECK(ygst_coretype_is_chinese("pinyin"));
    CHECK(ygst_coretype_is_chinese("sent.eval.cn"));
    CHECK(!ygst_coretype_is_chinese("sent.eval"));
    CHECK(!ygst_coretype_is_chinese(NULL));
    /* refPinyin replaces refText only for Chinese kernels (Android FormMapper.validate) */
    CHECK_INT(ygst_validate_request(YGST_CT_SUPPORTED, "sent.eval", "hi", NULL), 0);
    CHECK_INT(ygst_validate_request(YGST_CT_SUPPORTED, "sent.eval", " ", "ni3 hao3"), 60006);
    CHECK_INT(ygst_validate_request(YGST_CT_SUPPORTED, "sent.eval.cn", NULL, "ni3 hao3"), 0);
    CHECK_INT(ygst_validate_request(YGST_CT_SUPPORTED, "pinyin", "", "chong2 qing4"), 0);
    CHECK_INT(ygst_validate_request(YGST_CT_SUPPORTED, "pinyin", "", ""), 60006);
    CHECK_INT(ygst_validate_request(YGST_CT_UNSUPPORTED, "open.eval", "hi", NULL), 60003);
    CHECK_INT(ygst_validate_request(YGST_CT_UNKNOWN, "x.y", "hi", NULL), 60003);
}

static void test_coretype_resolve(void) {
    char out[32];
    CHECK_INT(ygst_coretype_resolve(" sent.eval.cn ", 0, out, sizeof out), YGST_CT_SUPPORTED);
    CHECK_STR(out, "sent.eval.cn");
    CHECK_INT(ygst_coretype_resolve(NULL, 1, out, sizeof out), YGST_CT_SUPPORTED);
    CHECK_STR(out, "sent.eval");
    CHECK_INT(ygst_coretype_resolve("   ", 10, out, sizeof out), YGST_CT_SUPPORTED);
    CHECK_STR(out, "sent.eval.cn");
    CHECK_INT(ygst_coretype_resolve("", 3, out, sizeof out), YGST_CT_UNSUPPORTED);
    CHECK_STR(out, "open.eval");
    CHECK_INT(ygst_coretype_resolve(NULL, 22, out, sizeof out), YGST_CT_UNSUPPORTED);
    CHECK_STR(out, "");
    CHECK_INT(ygst_coretype_resolve(NULL, 99, out, sizeof out), YGST_CT_UNSUPPORTED);
    CHECK_INT(ygst_coretype_resolve("my.core", 0, out, sizeof out), YGST_CT_UNKNOWN);
    CHECK_STR(out, "my.core");
    CHECK_INT(ygst_coretype_resolve(NULL, 7, out, 4), YGST_CT_UNKNOWN);
    CHECK_INT(ygst_coretype_resolve(NULL, 0, NULL, 0), YGST_CT_UNKNOWN);
}

void suite_json(void) {
    RUN("json", test_json_validate);
    RUN("json", test_json_member_and_iteration);
    RUN("json", test_json_strings);
    RUN("json", test_json_numbers);
}

void suite_coretype(void) {
    RUN("coretype", test_coretype_enum_table);
    RUN("coretype", test_coretype_support);
    RUN("coretype", test_coretype_resolve);
}
