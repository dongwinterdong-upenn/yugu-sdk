/*
 * Copyright 2026 优谷雅言 open.shengzhiai.com
 * SPDX-License-Identifier: Apache-2.0
 *
 * Retry policy, backoff with a fixed seed, Retry-After, classification of platform responses
 * and the call controller.
 */
#include "ygt.h"

static void test_policy_defaults(void) {
    ygst_retry_policy p;
    ygst_retry_policy_default(&p);
    CHECK_INT(p.max_retries, 2);
    CHECK_INT(p.initial_delay_ms, 200);
    CHECK(p.multiplier == 2.0);
    CHECK_INT(p.max_delay_ms, 4000);
    CHECK(p.jitter == 0.3);
    CHECK_INT(p.respect_retry_after, 1);
    CHECK_INT(p.max_retry_after_ms, 30000);
    CHECK_INT(p.total_timeout_ms, 300000);
    CHECK_INT(p.auto_retry, 0);
    CHECK_INT(p.max_auto_retries, 2);
    CHECK_INT(p.n_err_ids, 1);
    CHECK_INT(p.err_ids[0], 20009);
}

static void test_policy_err_ids(void) {
    ygst_retry_policy p;
    int i;
    ygst_retry_policy_default(&p);
    CHECK_INT(ygst_retry_policy_add_err_id(&p, 20009), 0);
    CHECK_INT(p.n_err_ids, 1);
    CHECK_INT(ygst_retry_policy_add_err_id(&p, 40001), 0);
    CHECK(ygst_retry_policy_has_err_id(&p, 40001));
    CHECK(!ygst_retry_policy_has_err_id(&p, 40002));
    CHECK_INT(ygst_retry_policy_add_err_id(&p, 0), -1);
    CHECK_INT(ygst_retry_policy_add_err_id(&p, -5), -1);
    CHECK_INT(ygst_retry_policy_add_err_id(&p, 3000000000LL), -1);
    for (i = 0; i < 20; i++) ygst_retry_policy_add_err_id(&p, 100 + i);
    CHECK_INT(p.n_err_ids, YGST_MAX_ERR_IDS);
    CHECK_INT(ygst_retry_policy_add_err_id(&p, 999), -1);
}

static void test_backoff_no_jitter(void) {
    ygst_retry_policy p;
    static const long want[] = {200, 400, 800, 1600, 3200, 4000, 4000};
    int n;
    ygst_retry_policy_default(&p);
    p.jitter = 0;
    for (n = 1; n <= 7; n++) CHECK_INT(ygst_backoff_delay_ms(&p, n, NULL), want[n - 1]);
    CHECK_INT(ygst_backoff_delay_ms(&p, 0, NULL), 200);
    p.multiplier = 0;
    CHECK_INT(ygst_backoff_delay_ms(&p, 3, NULL), 200);
}

static void test_backoff_fixed_seed(void) {
    /* reference values computed with an independent Python splitmix64 for seed 42 */
    static const long want[] = {229, 318, 694, 1450, 2313, 4884};
    ygst_retry_policy p;
    ygst_rng r1, r2;
    int n;
    ygst_retry_policy_default(&p);
    ygst_rng_seed(&r1, 42);
    CHECK(ygst_rng_next(&r1) == 0xbdd732262feb6e95ull);
    CHECK(ygst_rng_next(&r1) == 0x28efe333b266f103ull);
    ygst_rng_seed(&r1, 42);
    ygst_rng_seed(&r2, 42);
    for (n = 1; n <= 6; n++) {
        long d = ygst_backoff_delay_ms(&p, n, &r1);
        double base = 200.0 * (1 << (n - 1));
        if (base > 4000) base = 4000;
        CHECK_INT(d, want[n - 1]);
        CHECK(d >= (long)(base * 0.7) && d <= (long)(base * 1.3 + 1));
        CHECK_INT(ygst_backoff_delay_ms(&p, n, &r2), d);
    }
    ygst_rng_seed(&r1, 7);
    for (n = 0; n < 1000; n++) {
        double u = ygst_rng_uniform(&r1);
        CHECK(u >= 0.0 && u < 1.0);
        if (!(u >= 0.0 && u < 1.0)) break;
    }
}

static void test_retry_after(void) {
    long ms = -1;
    /* Thu, 08 Oct 2026 08:21:32 GMT is 1791447692 s */
    int64_t now = 1791447692000LL - 5000;
    CHECK_INT(ygst_parse_retry_after("1", now, &ms), 0);
    CHECK_INT(ms, 1000);
    CHECK_INT(ygst_parse_retry_after(" 30 ", now, &ms), 0);
    CHECK_INT(ms, 30000);
    CHECK_INT(ygst_parse_retry_after("0", now, &ms), 0);
    CHECK_INT(ms, 0);
    CHECK_INT(ygst_parse_retry_after("Thu, 08 Oct 2026 08:21:32 GMT", now, &ms), 0);
    CHECK_INT(ms, 5000);
    CHECK_INT(ygst_parse_retry_after("Thu, 08 Oct 2026 08:21:32 GMT", now + 60000, &ms), 0);
    CHECK_INT(ms, 0);
    CHECK_INT(ygst_parse_retry_after("Sun, 29 Feb 2032 00:00:00 GMT", 1961625600000LL - 1000, &ms), 0);
    CHECK_INT(ms, 1000);
    CHECK_INT(ygst_parse_retry_after("Thu, 08 Okt 2026 08:21:32 GMT", now, &ms), -1);
    CHECK_INT(ygst_parse_retry_after("Thu, 32 Oct 2026 08:21:32 GMT", now, &ms), -1);
    CHECK_INT(ygst_parse_retry_after("abc", now, &ms), -1);
    CHECK_INT(ygst_parse_retry_after("", now, &ms), -1);
    CHECK_INT(ygst_parse_retry_after("-1", now, &ms), -1);
    CHECK_INT(ygst_parse_retry_after("99999999999", now, &ms), -1);
    CHECK_INT(ygst_parse_retry_after(NULL, now, &ms), -1);
}

static void test_is_retryable_rules(void) {
    static const int retry_http[] = {408, 425, 429, 500, 502, 503, 504};
    static const int no_http[] = {200, 400, 401, 403, 404, 405, 409, 413, 415, 422, 501};
    size_t i;
    CHECK(ygst_is_retryable(90001, 0, 0));
    CHECK(ygst_is_retryable(90002, 0, 0));
    CHECK(ygst_is_retryable(90007, 0, 0));
    CHECK(!ygst_is_retryable(90003, 0, 0));
    CHECK(!ygst_is_retryable(90005, 0, 0));
    CHECK(!ygst_is_retryable(90011, 0, 0));
    CHECK(!ygst_is_retryable(90002 + 1, 50000, 500)); /* a local code decides first */
    CHECK(ygst_is_retryable(0, 50000, 400));            /* then the business code */
    CHECK(!ygst_is_retryable(0, 40001, 503));
    CHECK(ygst_is_retryable(0, 12345, 503));            /* unknown code: HTTP status */
    CHECK(!ygst_is_retryable(0, 12345, 400));
    for (i = 0; i < sizeof retry_http / sizeof retry_http[0]; i++) CHECK(ygst_is_retryable(0, 0, retry_http[i]));
    for (i = 0; i < sizeof no_http / sizeof no_http[0]; i++) CHECK(!ygst_is_retryable(0, 0, no_http[i]));
}

static void test_errid_rules(void) {
    CHECK_INT(ygst_errid_for(0, 50000, 500, 1), 20009);
    CHECK_INT(ygst_errid_for(90001, 0, 0, 1), 20009);
    CHECK_INT(ygst_errid_for(0, 2003, 401, 0), 2003);
    CHECK_INT(ygst_errid_for(90005, 0, 200, 0), 90005);
    CHECK_INT(ygst_errid_for(90011, 0, 0, 0), 90011);
    CHECK_INT(ygst_errid_for(60003, 0, 0, 0), 60003);
    CHECK_INT(ygst_errid_for(60003, 0, 0, 1), 60003);
    CHECK_INT(ygst_errid_for(0, 0, 401, 0), 40100);
    CHECK_INT(ygst_errid_for(0, 0, 403, 0), 40300);
    CHECK_INT(ygst_errid_for(0, 0, 404, 0), 40400);
    CHECK_INT(ygst_errid_for(0, 0, 409, 0), 40900);
    CHECK_INT(ygst_errid_for(0, 0, 429, 0), 42900);
    CHECK_INT(ygst_errid_for(0, 0, 413, 0), 40001);
    CHECK_INT(ygst_errid_for(0, 0, 501, 0), 50010);
    CHECK_INT(ygst_errid_for(0, 0, 502, 0), 50200);
    CHECK_INT(ygst_errid_for(0, 0, 503, 0), 50200);
    CHECK_INT(ygst_errid_for(0, 0, 500, 0), 50000);
    CHECK_INT(ygst_errid_for(0, 0, 408, 0), 20009);
    CHECK_INT(ygst_errid_for(0, 0, 418, 0), 40001);
    CHECK_INT(ygst_errid_for(0, 0, 599, 0), 50000);
    CHECK_INT(ygst_errid_for(0, 0, 302, 0), 90005);
    CHECK_INT(ygst_errid_for(0, 0, 600, 0), 90005);  /* DESIGN 2.4: not 4xx or 5xx is UNKNOWN */
    CHECK(!ygst_is_retryable(0, 0, 599));             /* other 5xx: retryable only when listed */
    CHECK(!ygst_is_retryable(0, 0, 499));
    CHECK_INT(ygst_errid_for(0, 0, 200, 0), 90005);
}

/* ---------------------------------------------------------------- classification */

static void classify_text(int status, const char *body, ygst_classification *c) {
    ygst_outcome o;
    memset(&o, 0, sizeof o);
    o.http_status = status;
    o.body = body;
    o.body_len = body ? strlen(body) : 0;
    o.retry_after_ms = -1;
    ygst_classify(&o, c);
}

/* A top-level member of a fixture body: its span, and the decoded text when it is a string. */
static int fixture_member(const char *body, size_t len, const char *key, ygst_span *v, ygst_buf *text) {
    ygst_span root;
    if (text) ygst_buf_reset(text);
    if (ygst_json_parse(body, len, &root) != 0 || ygst_json_member(root, key, v) != 1) return 0;
    if (text && (ygst_json_type(*v) != 's' || ygst_json_string(*v, text) != 0)) return 0;
    return 1;
}

static void test_classify_success_fixtures(void) {
    static const char *names[] = {"compat_word.eval.json", "compat_sent.eval.json", "compat_sent.eval.cn.json",
                                  "compat_para.eval.cn.json", "compat_sent_eval_cn.json",
                                  "compat_sent.eval.cn_attach_audio_url.json", "compat_para.eval.cn_word_detail.json",
                                  "compat_para.eval.cn_single_sentence.json"};
    size_t i;
    for (i = 0; i < sizeof names / sizeof names[0]; i++) {
        char rel[128];
        size_t len = 0;
        char *body;
        ygst_classification c;
        ygst_span root, result, url_span;
        ygst_buf url;
        int with_flag = strstr(names[i], "attach_audio_url") != NULL;
        snprintf(rel, sizeof rel, "fixtures/platform/%s", names[i]);
        body = ygt_read_spec(rel, &len);
        CHECK(body != NULL);
        if (!body) continue;
        ygst_classification_init(&c);
        ygst_buf_init(&url);
        classify_text(200, body, &c);
        CHECK_INT(c.success, 1);
        CHECK(strncmp(ygst_buf_cstr(&c.record_id), "eval_", 5) == 0);
        /* the platform sends audioUrl only for attachAudioUrl=1, and the classification takes it as sent */
        CHECK_INT(fixture_member(body, len, "audioUrl", &url_span, &url), with_flag);
        CHECK_INT(c.has_audio_url, with_flag);
        if (with_flag) CHECK_STR(ygst_buf_cstr(&c.audio_url), ygst_buf_cstr(&url));
        CHECK_INT(ygst_json_parse(body, len, &root), 0);
        CHECK_INT(ygst_json_member(root, "result", &result), 1);
        CHECK(c.result.p == result.p && c.result.n == result.n);
        ygst_buf_free(&url);
        ygst_classification_free(&c);
        free(body);
    }
}

static int spans_equal(ygst_span a, ygst_span b) {
    return a.n == b.n && memcmp(a.p, b.p, a.n) == 0;
}

/* Every member of orig other than skip is in got with the same bytes, and got has as many members. */
static int same_members_except(ygst_span orig, ygst_span got, const char *skip) {
    ygst_span k, v, w;
    size_t pos = 0;
    int n_orig = 0, n_got = 0;
    while (ygst_json_object_next(orig, &pos, &k, &v) == 1) {
        ygst_buf name;
        int ok;
        ygst_buf_init(&name);
        ygst_json_string(k, &name);
        ok = strcmp(ygst_buf_cstr(&name), skip) == 0 ||
             (ygst_json_member(got, ygst_buf_cstr(&name), &w) == 1 && spans_equal(v, w));
        ygst_buf_free(&name);
        if (!ok) return 0;
        n_orig++;
    }
    pos = 0;
    while (ygst_json_object_next(got, &pos, &k, &v) == 1) n_got++;
    return n_orig == n_got;
}

/* Checks one aligned details item against the platform item: every platform member is still
 * there with the same bytes, overall and pronunciation were added from scores, nothing else. */
static int check_aligned_detail(ygst_span orig, ygst_span got, int *added) {
    ygst_span k, v, w, scores, so, sp;
    size_t pos = 0;
    int n_orig = 0, n_got = 0;
    while (ygst_json_object_next(orig, &pos, &k, &v) == 1) {
        ygst_buf name;
        int ok;
        ygst_buf_init(&name);
        ygst_json_string(k, &name);
        ok = ygst_json_member(got, ygst_buf_cstr(&name), &w) == 1 && spans_equal(v, w);
        ygst_buf_free(&name);
        if (!ok) return 0;
        n_orig++;
    }
    pos = 0;
    while (ygst_json_object_next(got, &pos, &k, &v) == 1) n_got++;
    *added = n_got - n_orig;
    if (ygst_json_member(orig, "overall", &v) == 1) return *added == 0;
    if (ygst_json_member(orig, "scores", &scores) != 1) return *added == 0;
    if (ygst_json_member(scores, "overall", &so) == 1) {
        if (ygst_json_member(got, "overall", &w) != 1 || !spans_equal(so, w)) return 0;
    }
    if (ygst_json_member(orig, "pronunciation", &v) != 1 && ygst_json_member(scores, "pronunciation", &sp) == 1) {
        if (ygst_json_member(got, "pronunciation", &w) != 1 || !spans_equal(sp, w)) return 0;
    }
    return 1;
}

/* Envelope from a real para.eval.cn response with paragraph_need_word_score=1: outside
 * sentences[].details[] the result is the platform's byte for byte, every details item gains
 * overall and pronunciation from its scores and nothing else, the same for one sentence or many. */
static void check_paragraph_fixture(const char *name, int want_sentences, int want_items, ygst_buf *first_text) {
    char rel[128];
    size_t len = 0;
    char *body;
    ygst_classification c;
    ygst_buf env, aligned;
    ygst_span root, result, fx_root, fx_result, s_got, s_orig, sg, so_, d_got, d_orig, dg, do_;
    size_t pos_g = 0, pos_o = 0;
    int sentences = 0, items = 0, added_total = 0;
    size_t extra = 0;
    snprintf(rel, sizeof rel, "fixtures/platform/%s", name);
    body = ygt_read_spec(rel, &len);
    CHECK(body != NULL);
    if (!body) return;
    ygst_classification_init(&c);
    ygst_buf_init(&env);
    ygst_buf_init(&aligned);
    classify_text(200, body, &c);
    CHECK_INT(c.success, 1);
    CHECK_INT(c.has_audio_url, 0);
    CHECK_INT(ygst_envelope_result(&env, "t", "\"eval_x\"", "ak", "", "", "d", c.result, NULL, NULL), 0);
    CHECK_INT(ygst_json_parse((const char *)env.data, env.len, &root), 0);
    CHECK_INT(ygst_json_member(root, "result", &result), 1);
    CHECK_INT(ygst_json_parse(body, len, &fx_root), 0);
    CHECK_INT(ygst_json_member(fx_root, "result", &fx_result), 1);
    CHECK(same_members_except(fx_result, result, "sentences"));
    CHECK_INT(ygst_json_member(result, "sentences", &s_got), 1);
    CHECK_INT(ygst_json_member(fx_result, "sentences", &s_orig), 1);
    for (;;) {
        size_t dg_pos = 0, do_pos = 0;
        ygst_span text;
        int a = ygst_json_array_next(s_got, &pos_g, &sg);
        int b = ygst_json_array_next(s_orig, &pos_o, &so_);
        if (a != 1 || b != 1) {
            CHECK(a != 1 && b != 1); /* as many sentences as the platform sent */
            break;
        }
        if (sentences++ == 0 && first_text && ygst_json_member(sg, "text", &text) == 1) {
            ygst_json_string(text, first_text);
        }
        /* sentence members other than details are unchanged */
        CHECK(same_members_except(so_, sg, "details"));
        CHECK_INT(ygst_json_member(sg, "details", &d_got), 1);
        CHECK_INT(ygst_json_member(so_, "details", &d_orig), 1);
        for (;;) {
            int added = -1;
            ygst_span sc, x;
            a = ygst_json_array_next(d_got, &dg_pos, &dg);
            b = ygst_json_array_next(d_orig, &do_pos, &do_);
            if (a != 1 || b != 1) {
                CHECK(a != 1 && b != 1); /* as many details items as the platform sent */
                break;
            }
            CHECK(check_aligned_detail(do_, dg, &added));
            CHECK_INT(added, 2);
            added_total += added;
            items++;
            CHECK_INT(ygst_json_member(do_, "scores", &sc), 1);
            if (ygst_json_member(sc, "overall", &x) == 1) extra += strlen(",\"overall\":") + x.n;
            if (ygst_json_member(sc, "pronunciation", &x) == 1) extra += strlen(",\"pronunciation\":") + x.n;
        }
    }
    CHECK_INT(sentences, want_sentences);
    CHECK_INT(items, want_items);
    CHECK_INT(added_total, 2 * want_items);
    /* the aligned result is the platform result plus exactly the added members */
    CHECK_INT(ygst_align_result(fx_result, &aligned), added_total);
    CHECK(aligned.len == result.n && memcmp(aligned.data, result.p, result.n) == 0);
    CHECK_INT(aligned.len, fx_result.n + extra);
    printf("     %s: sentences %d, details items aligned %d, members added %d\n", name, sentences, items,
           added_total);
    ygst_buf_free(&aligned);
    ygst_buf_free(&env);
    ygst_classification_free(&c);
    free(body);
}

/* Real responses captured 2026-10-08. With attachAudioUrl=1 the platform answers with a top-level
 * audioUrl, the download URL of the uploaded recording kept 7 days, and the envelope carries
 * exactly that URL as its last member. The same sentence without the flag has no audioUrl. A
 * para.eval.cn response with paragraph_need_word_score=1 carries sentences[].details[] whose
 * scores sit under scores. */
static void test_envelope_from_real_fixtures(void) {
    size_t len = 0;
    char *body = ygt_read_spec("fixtures/platform/compat_sent.eval.cn_attach_audio_url.json", &len);
    ygst_classification c;
    ygst_buf env, want, got, last;
    ygst_span root, k, v, want_span;
    size_t pos = 0;
    char rid[64];
    ygst_classification_init(&c);
    ygst_buf_init(&env);
    ygst_buf_init(&want);
    ygst_buf_init(&got);
    ygst_buf_init(&last);
    CHECK(body != NULL);
    if (!body) return;
    CHECK_INT(fixture_member(body, len, "audioUrl", &want_span, &want), 1);
    CHECK(strncmp(ygst_buf_cstr(&want), "https://", 8) == 0);
    classify_text(200, body, &c);
    CHECK_INT(c.success, 1);
    CHECK_INT(c.has_audio_url, 1);
    CHECK_STR(ygst_buf_cstr(&c.audio_url), ygst_buf_cstr(&want));
    snprintf(rid, sizeof rid, "%.*s", (int)c.record_id_raw.n, c.record_id_raw.p ? c.record_id_raw.p : "");
    CHECK_INT(ygst_envelope_result(&env, "t", rid, "ak", "u", "今天天气很好", "d", c.result, NULL,
                                   c.has_audio_url ? ygst_buf_cstr(&c.audio_url) : NULL),
              0);
    CHECK_INT(ygst_json_parse((const char *)env.data, env.len, &root), 0);
    CHECK_INT(ygst_json_member(root, "audioUrl", &v), 1);
    CHECK(spans_equal(v, want_span)); /* the platform's bytes */
    CHECK_INT(ygst_json_string(v, &got), 0);
    CHECK_STR(ygst_buf_cstr(&got), ygst_buf_cstr(&want));
    while (ygst_json_object_next(root, &pos, &k, &v) == 1) {
        ygst_buf_reset(&last);
        ygst_json_string(k, &last);
    }
    CHECK_STR(ygst_buf_cstr(&last), "audioUrl");
    free(body);

    body = ygt_read_spec("fixtures/platform/compat_sent.eval.cn.json", &len);
    CHECK(body != NULL);
    if (body) {
        CHECK_INT(fixture_member(body, len, "audioUrl", &want_span, NULL), 0);
        classify_text(200, body, &c);
        CHECK_INT(c.success, 1);
        CHECK_INT(c.has_audio_url, 0);
        ygst_buf_reset(&env);
        CHECK_INT(ygst_envelope_result(&env, "t", "\"eval_x\"", "ak", "u", "今天天气很好", "d", c.result, NULL,
                                       c.has_audio_url ? ygst_buf_cstr(&c.audio_url) : NULL),
                  0);
        CHECK_INT(ygst_json_parse((const char *)env.data, env.len, &root), 0);
        CHECK(ygst_json_member(root, "audioUrl", &v) != 1);
        free(body);
    }

    check_paragraph_fixture("compat_para.eval.cn_word_detail.json", 2, 15, NULL);
    ygst_buf_free(&last);
    ygst_buf_free(&got);
    ygst_buf_free(&want);
    ygst_buf_free(&env);
    ygst_classification_free(&c);
}

/* para.eval.cn with refText 今天天气很好。 captured 2026-10-08: one sentence, its 6 details items
 * aligned as in a multi-sentence result. */
static void test_envelope_single_sentence_paragraph(void) {
    ygst_buf text;
    ygst_buf_init(&text);
    check_paragraph_fixture("compat_para.eval.cn_single_sentence.json", 1, 6, &text);
    CHECK_STR(ygst_buf_cstr(&text), "今天天气很好。");
    ygst_buf_free(&text);
}

static void align_text(const char *in, ygst_buf *out, int want_added) {
    ygst_span v;
    ygst_buf_reset(out);
    CHECK_INT(ygst_json_parse(in, strlen(in), &v), 0);
    CHECK_INT(ygst_align_result(v, out), want_added);
    CHECK_INT(ygst_json_parse((const char *)out->data, out->len, &v), 0);
}

static void test_align_result_cases(void) {
    ygst_buf out;
    ygst_span none = {NULL, 0};
    ygst_buf_init(&out);
    /* item without overall: both added at the end, the original bytes kept */
    align_text("{\"sentences\":[{\"details\":[{\"word\":\"a\",\"scores\":{\"overall\":81,\"pronunciation\":79.5}}]}]}",
               &out, 2);
    CHECK_STR(ygst_buf_cstr(&out),
              "{\"sentences\":[{\"details\":[{\"word\":\"a\",\"scores\":{\"overall\":81,\"pronunciation\":79.5}"
              ",\"overall\":81,\"pronunciation\":79.5}]}]}");
    /* overall already present: item untouched, even without pronunciation */
    align_text("{\"sentences\":[{\"details\":[{\"overall\":5,\"scores\":{\"overall\":81,\"pronunciation\":79}}]}]}",
               &out, 0);
    CHECK_STR(ygst_buf_cstr(&out), "{\"sentences\":[{\"details\":[{\"overall\":5,\"scores\":{\"overall\":81,\"pronunciation\":79}}]}]}");
    /* own pronunciation kept, only overall added; scores without pronunciation */
    align_text("{\"sentences\":[{\"details\":[{\"pronunciation\":1,\"scores\":{\"overall\":2,\"pronunciation\":3}},"
               "{\"scores\":{\"overall\":4}}]}]}",
               &out, 2);
    CHECK_STR(ygst_buf_cstr(&out),
              "{\"sentences\":[{\"details\":[{\"pronunciation\":1,\"scores\":{\"overall\":2,\"pronunciation\":3},"
              "\"overall\":2},{\"scores\":{\"overall\":4},\"overall\":4}]}]}");
    /* pretty printed input and a string value are copied as written */
    align_text("{ \"sentences\" : [ { \"details\" : [ { \"scores\" : { \"overall\" : \"90\" } } ] } ] }", &out, 1);
    CHECK_STR(ygst_buf_cstr(&out), "{ \"sentences\" : [ { \"details\" : [ { \"scores\" : { \"overall\" : \"90\" } ,\"overall\":\"90\"} ] } ] }");
    /* nothing to align: copied unchanged */
    align_text("{\"sentences\":[{\"details\":[{\"scores\":5},{\"word\":\"x\"},7,{\"scores\":{}}]},3,{\"details\":{}}]}",
               &out, 0);
    CHECK_STR(ygst_buf_cstr(&out), "{\"sentences\":[{\"details\":[{\"scores\":5},{\"word\":\"x\"},7,{\"scores\":{}}]},3,{\"details\":{}}]}");
    align_text("{\"sentences\":{\"details\":[]},\"overall\":1}", &out, 0);
    align_text("{\"overall\":1,\"words\":[]}", &out, 0);
    CHECK_STR(ygst_buf_cstr(&out), "{\"overall\":1,\"words\":[]}");
    align_text("[1,2]", &out, 0);
    CHECK_STR(ygst_buf_cstr(&out), "[1,2]");
    CHECK_INT(ygst_align_result(none, &out), -1);
    ygst_buf_free(&out);
}

static void test_classify_audio_url(void) {
    ygst_classification c;
    ygst_classification_init(&c);
    classify_text(200, "{\"recordId\":\"eval_1\",\"audioUrl\":\"https://x/a.wav\",\"result\":{\"overall\":90},\"eof\":1}", &c);
    CHECK_INT(c.success, 1);
    CHECK_INT(c.has_audio_url, 1);
    CHECK_STR(ygst_buf_cstr(&c.audio_url), "https://x/a.wav");
    classify_text(200, "{\"recordId\":12,\"result\":{\"audioUrl\":\"/audio/b.wav\"}}", &c);
    CHECK_INT(c.success, 1);
    CHECK_STR(ygst_buf_cstr(&c.record_id), "12");
    CHECK_INT(c.has_record_id, 1);
    CHECK(c.record_id_raw.n == 2 && memcmp(c.record_id_raw.p, "12", 2) == 0);
    CHECK_INT(c.has_audio_url, 1);
    CHECK_STR(ygst_buf_cstr(&c.audio_url), "/audio/b.wav");
    classify_text(200, "{\"result\":{\"audioUrl\":5}}", &c);
    CHECK_INT(c.success, 1);
    CHECK_INT(c.has_audio_url, 0);
    CHECK_INT(c.has_record_id, 0);
    classify_text(200, "{\"audioUrl\":\"\",\"result\":{}}", &c);
    CHECK_INT(c.has_audio_url, 0);
    ygst_classification_free(&c);
}

static void test_classify_errors(void) {
    ygst_classification c;
    ygst_outcome o;
    ygst_classification_init(&c);

    classify_text(401, "{\"code\":2003,\"message\":\"签名验证失败\",\"timestamp\":1}", &c);
    CHECK_INT(c.success, 0);
    CHECK_INT(c.biz_code, 2003);
    CHECK_INT(c.retryable, 0);
    CHECK_INT(c.err_id, 2003);
    CHECK_STR(ygst_buf_cstr(&c.message), "签名验证失败");

    classify_text(500, "{\"code\":50000,\"message\":\"system busy\"}", &c);
    CHECK_INT(c.retryable, 1);
    CHECK_INT(c.err_id, 20009);

    classify_text(429, "{\"code\":42901,\"message\":\"too many\"}", &c);
    CHECK_INT(c.retryable, 1);
    CHECK_INT(c.err_id, 20009);

    classify_text(400, "{\"code\":40001,\"message\":\"bad\"}", &c);
    CHECK_INT(c.retryable, 0);
    CHECK_INT(c.err_id, 40001);

    classify_text(409, "{\"code\":40902,\"message\":\"quota\"}", &c);
    CHECK_INT(c.err_id, 40902);

    classify_text(409, "{\"code\":\"40901\",\"message\":\"in progress\"}", &c);
    CHECK_INT(c.biz_code, 40901);
    CHECK_INT(c.retryable, 1);

    classify_text(502, "{\"detail\":\"upstream down\"}", &c);
    CHECK_INT(c.biz_code, 0);
    CHECK_STR(ygst_buf_cstr(&c.message), "upstream down");
    CHECK_INT(c.err_id, 20009);

    classify_text(401, "{\"detail\":\"[2001] signature mismatch\"}", &c);
    CHECK_INT(c.biz_code, 2001);
    CHECK_INT(c.err_id, 2001);

    classify_text(401, "{\"detail\":\" [99999] who knows\"}", &c);
    CHECK_INT(c.biz_code, 99999);
    CHECK_INT(c.err_id, 99999);

    classify_text(401, "{\"detail\":\"[12] short\"}", &c);
    CHECK_INT(c.biz_code, 0);
    CHECK_INT(c.err_id, 40100);

    classify_text(422, "{\"detail\":[{\"loc\":[\"body\"],\"msg\":\"field required\"}]}", &c);
    CHECK_STR(ygst_buf_cstr(&c.message), "[{\"loc\":[\"body\"],\"msg\":\"field required\"}]");
    CHECK_INT(c.err_id, 40001);

    classify_text(422, "{\"message\":\"m\",\"detail\":[\"plain\"]}", &c);
    CHECK_STR(ygst_buf_cstr(&c.message), "m");

    classify_text(400, "{\"error\":\"oops\"}", &c);
    CHECK_STR(ygst_buf_cstr(&c.message), "oops");

    classify_text(400, "{\"msg\":\"short form\",\"error\":\"later\"}", &c);
    CHECK_STR(ygst_buf_cstr(&c.message), "short form");

    classify_text(400, "{\"errId\":41030,\"error\":\"Shengtong style\"}", &c);
    CHECK_INT(c.biz_code, 41030);
    CHECK_INT(c.err_id, 41030);
    CHECK_STR(ygst_buf_cstr(&c.message), "Shengtong style");

    /* 1004 in an error body is USER_DISABLED (errors table), never the AUDIO_NOISY warning */
    classify_text(400, "{\"code\":1004,\"message\":\"用户已禁用\"}", &c);
    CHECK_INT(c.biz_code, 1004);
    CHECK_INT(c.retryable, 0);
    CHECK_INT(c.err_id, 1004);
    CHECK_STR(ygst_error_find(1004)->name, "USER_DISABLED");
    CHECK_STR(ygst_error_find(1005)->name, "USER_LOCKED");
    CHECK_STR(ygst_warning_find(1004)->name, "AUDIO_NOISY");
    CHECK_STR(ygst_warning_find(1005)->name, "AUDIO_INCOMPLETE");
    CHECK(ygst_error_find(1001) == NULL); /* warning only */
    CHECK(ygst_error_find(1009) == NULL);

    classify_text(403, "{\"code\":40300}", &c);
    CHECK_STR(ygst_buf_cstr(&c.message), "HTTP 403");

    classify_text(404, "<html>nope</html>", &c);
    CHECK_STR(ygst_buf_cstr(&c.message), "HTTP 404");
    CHECK_INT(c.err_id, 40400);

    classify_text(503, NULL, &c);
    CHECK_INT(c.retryable, 1);
    CHECK_INT(c.err_id, 20009);

    classify_text(200, "not json", &c);
    CHECK_INT(c.success, 0);
    CHECK_INT(c.local_code, 90005);
    CHECK_INT(c.err_id, 90005);
    CHECK_STR(ygst_buf_cstr(&c.message), "invalid platform response: not a JSON object");

    classify_text(200, "{\"code\":40902,\"message\":\"quota\"}", &c);
    CHECK_INT(c.success, 0);
    CHECK_INT(c.err_id, 90005);
    CHECK_STR(ygst_buf_cstr(&c.message), "invalid platform response: platform response has no result");

    classify_text(200, "{\"result\":[1,2]}", &c);
    CHECK_INT(c.success, 1); /* the result value is passed through whatever its type */

    memset(&o, 0, sizeof o);
    o.local_code = YGST_LOCAL_TIMEOUT;
    ygst_classify(&o, &c);
    CHECK_INT(c.retryable, 1);
    CHECK_INT(c.err_id, 20009);
    CHECK_STR(ygst_buf_cstr(&c.message), "连接或读取超时");

    o.local_code = YGST_LOCAL_TLS;
    o.local_message = "certificate rejected";
    ygst_classify(&o, &c);
    CHECK_INT(c.retryable, 0);
    CHECK_INT(c.err_id, 90011);
    CHECK_STR(ygst_buf_cstr(&c.message), "certificate rejected");

    memset(&o, 0, sizeof o);
    ygst_classify(&o, &c); /* no status, no code: network */
    CHECK_INT(c.local_code, 90001);
    CHECK_INT(c.err_id, 20009);
    ygst_classification_free(&c);
}

static void classify_error_fixture(const char *rel, int want_status, int want_err, const char *needle) {
    size_t len = 0;
    char *text = ygt_read_spec(rel, &len);
    ygst_span root, st, body;
    ygst_buf b;
    long long status = 0;
    ygst_classification c;
    CHECK(text != NULL);
    if (!text) return;
    ygst_buf_init(&b);
    ygst_classification_init(&c);
    CHECK_INT(ygst_json_parse(text, len, &root), 0);
    CHECK_INT(ygst_json_member(root, "status", &st), 1);
    CHECK_INT(ygst_json_int(st, &status), 0);
    CHECK_INT(status, want_status);
    CHECK_INT(ygst_json_member(root, "body", &body), 1);
    ygst_json_string(body, &b);
    classify_text((int)status, ygst_buf_cstr(&b), &c);
    CHECK_INT(c.err_id, want_err);
    CHECK(strstr(ygst_buf_cstr(&c.message), needle) != NULL);
    ygst_classification_free(&c);
    ygst_buf_free(&b);
    free(text);
}

static void test_classify_error_fixtures(void) {
    classify_error_fixture("fixtures/platform/error_compat_pinyin_missing_refpinyin.json", 400, 40001, "refPinyin");
    classify_error_fixture("fixtures/platform/error_native_bad_signature.json", 401, 2003, "签名验证失败");
}

static void test_error_message(void) {
    ygst_classification c;
    ygst_outcome o;
    ygst_buf m;
    ygst_classification_init(&c);
    ygst_buf_init(&m);
    classify_text(503, "{\"code\":50000,\"message\":\"mock fault 503\"}", &c);
    ygst_error_message(&c, c.err_id, 3, &m);
    CHECK_STR(ygst_buf_cstr(&m), "网络或服务端临时故障，可重评，autoRetry 默认重评此码: HTTP 503 code=50000 mock fault 503 (attempts 3)");
    ygst_buf_reset(&m);
    classify_text(502, "<html/>", &c);
    ygst_error_message(&c, c.err_id, 0, &m);
    CHECK_STR(ygst_buf_cstr(&m), "网络或服务端临时故障，可重评，autoRetry 默认重评此码: HTTP 502 HTTP 502");
    ygst_buf_reset(&m);
    memset(&o, 0, sizeof o);
    o.local_code = YGST_LOCAL_TIMEOUT;
    o.local_message = "timeout: read timeout after 700 ms";
    ygst_classify(&o, &c);
    ygst_error_message(&c, c.err_id, 3, &m);
    CHECK_STR(ygst_buf_cstr(&m), "网络或服务端临时故障，可重评，autoRetry 默认重评此码: TIMEOUT timeout: read timeout after 700 ms (attempts 3)");
    ygst_buf_reset(&m);
    o.local_code = 12345; /* not in the table */
    o.local_message = NULL;
    ygst_classify(&o, &c);
    ygst_error_message(&c, 20009, 1, &m);
    CHECK(strstr(ygst_buf_cstr(&m), ": code=12345 network error (attempts 1)") != NULL);
    ygst_buf_reset(&m);
    classify_text(401, "{\"code\":2003,\"message\":\"签名验证失败\"}", &c);
    ygst_error_message(&c, c.err_id, 1, &m);
    CHECK_STR(ygst_buf_cstr(&m), "签名验证失败");
    ygst_buf_reset(&m);
    ygst_buf_reset(&c.message);
    ygst_error_message(&c, 60005, 0, &m);
    CHECK_STR(ygst_buf_cstr(&m), "音频短于 1 秒");
    ygst_buf_reset(&m);
    ygst_error_message(&c, 40001, 0, &m);
    CHECK_STR(ygst_buf_cstr(&m), "请求参数校验失败");
    ygst_buf_reset(&m);
    ygst_error_message(&c, 90011, 0, &m);
    CHECK_STR(ygst_buf_cstr(&m), "证书校验失败");
    ygst_buf_reset(&m);
    ygst_error_message(&c, 7, 0, &m);
    CHECK_STR(ygst_buf_cstr(&m), "");
    ygst_buf_free(&m);
    ygst_classification_free(&c);
}

/* ---------------------------------------------------------------- controller */

typedef struct {
    int status;
    const char *body;
    long retry_after;
} step;

static ygst_action feed(ygst_call *call, const step *s, int64_t now) {
    ygst_classification c;
    ygst_action a;
    ygst_classification_init(&c);
    classify_text(s->status, s->body, &c);
    a = ygst_call_next(call, &c, s->retry_after, now);
    ygst_classification_free(&c);
    return a;
}

static const step OK = {200, "{\"recordId\":\"eval_x\",\"result\":{\"overall\":1},\"eof\":1}", -1};
static const step E500 = {500, "{\"code\":50000,\"message\":\"x\"}", -1};
static const step E400 = {400, "{\"code\":40001,\"message\":\"x\"}", -1};

static void test_controller_basic(void) {
    ygst_call call;
    ygst_action a;
    ygst_retry_policy p;
    ygst_retry_policy_default(&p);

    ygst_call_init(&call, &p, 1, 0);
    a = feed(&call, &OK, 0);
    CHECK_INT(a.kind, YGST_ACT_SUCCESS);
    CHECK_INT(call.total_attempts, 1);

    ygst_call_init(&call, &p, 1, 0);
    a = feed(&call, &E500, 0);
    CHECK_INT(a.kind, YGST_ACT_RETRY);
    CHECK(a.delay_ms >= 140 && a.delay_ms <= 260);
    a = feed(&call, &E500, 300);
    CHECK_INT(a.kind, YGST_ACT_RETRY);
    CHECK(a.delay_ms >= 280 && a.delay_ms <= 520);
    a = feed(&call, &OK, 800);
    CHECK_INT(a.kind, YGST_ACT_SUCCESS);
    CHECK_INT(call.total_attempts, 3);

    ygst_call_init(&call, &p, 1, 0);
    feed(&call, &E500, 0);
    feed(&call, &E500, 0);
    a = feed(&call, &E500, 0);
    CHECK_INT(a.kind, YGST_ACT_FAIL);
    CHECK_INT(a.err_id, 20009);
    CHECK_INT(call.total_attempts, 3);

    ygst_call_init(&call, NULL, 1, 0);
    a = feed(&call, &E400, 0);
    CHECK_INT(a.kind, YGST_ACT_FAIL);
    CHECK_INT(a.err_id, 40001);
    CHECK_INT(call.total_attempts, 1);
}

static void test_controller_retry_after(void) {
    ygst_call call;
    ygst_action a;
    step s429 = {429, "{\"code\":42901,\"message\":\"busy\"}", 1500};
    step s429_long = {429, "{\"code\":42900,\"message\":\"queue\"}", 60000};
    ygst_retry_policy p;
    ygst_retry_policy_default(&p);
    ygst_call_init(&call, &p, 3, 0);
    a = feed(&call, &s429, 0);
    CHECK_INT(a.kind, YGST_ACT_RETRY);
    CHECK_INT(a.delay_ms, 1500);
    a = feed(&call, &s429_long, 2000);
    CHECK_INT(a.kind, YGST_ACT_RETRY);
    CHECK_INT(a.delay_ms, 30000);
    p.respect_retry_after = 0;
    ygst_call_init(&call, &p, 3, 0);
    a = feed(&call, &s429, 0);
    CHECK(a.delay_ms < 1500);
}

static void test_controller_auto_retry(void) {
    ygst_call call;
    ygst_action a;
    ygst_retry_policy p;
    int i, retries = 0, autos = 0;
    ygst_retry_policy_default(&p);
    p.auto_retry = 1;

    /* 3 failures exhaust the first submission, the autoRetry submission succeeds */
    ygst_call_init(&call, &p, 5, 0);
    for (i = 0; i < 3; i++) {
        a = feed(&call, &E500, 0);
        CHECK_INT(a.kind, YGST_ACT_RETRY);
        if (a.auto_retry) autos++;
    }
    CHECK_INT(autos, 1);
    CHECK_INT(call.submission, 1);
    a = feed(&call, &OK, 0);
    CHECK_INT(a.kind, YGST_ACT_SUCCESS);
    CHECK_INT(call.total_attempts, 4);

    /* everything fails: 3 submissions of 3 attempts, then errId 20009 */
    ygst_call_init(&call, &p, 5, 0);
    for (i = 0; i < 20; i++) {
        a = feed(&call, &E500, 0);
        if (a.kind != YGST_ACT_RETRY) break;
        retries++;
    }
    CHECK_INT(a.kind, YGST_ACT_FAIL);
    CHECK_INT(a.err_id, 20009);
    CHECK_INT(call.total_attempts, 9);
    CHECK_INT(retries, 8);

    /* a non-retryable errId is resubmitted only when the caller listed it */
    ygst_call_init(&call, &p, 5, 0);
    a = feed(&call, &E400, 0);
    CHECK_INT(a.kind, YGST_ACT_FAIL);
    ygst_retry_policy_add_err_id(&p, 40001);
    ygst_call_init(&call, &p, 5, 0);
    a = feed(&call, &E400, 0);
    CHECK_INT(a.kind, YGST_ACT_RETRY);
    CHECK_INT(a.auto_retry, 1);
    a = feed(&call, &E400, 0);
    CHECK_INT(a.kind, YGST_ACT_RETRY);
    a = feed(&call, &E400, 0);
    CHECK_INT(a.kind, YGST_ACT_FAIL);
    CHECK_INT(a.err_id, 40001);
    CHECK_INT(call.total_attempts, 3);
}

static void test_controller_deadline(void) {
    ygst_call call;
    ygst_action a;
    ygst_retry_policy p;
    ygst_retry_policy_default(&p);
    p.total_timeout_ms = 100;
    p.auto_retry = 1;
    ygst_call_init(&call, &p, 9, 1000);
    a = feed(&call, &E500, 1000);
    CHECK_INT(a.kind, YGST_ACT_FAIL);
    CHECK_INT(a.err_id, 20009);
    p.total_timeout_ms = 300000;
    ygst_call_init(&call, &p, 9, 0);
    feed(&call, &E500, 0);
    feed(&call, &E500, 0);
    a = feed(&call, &E500, 299990); /* the autoRetry submission would cross the deadline */
    CHECK_INT(a.kind, YGST_ACT_FAIL);
}

void suite_retry(void) {
    RUN("retry", test_policy_defaults);
    RUN("retry", test_policy_err_ids);
    RUN("retry", test_backoff_no_jitter);
    RUN("retry", test_backoff_fixed_seed);
    RUN("retry", test_retry_after);
    RUN("retry", test_is_retryable_rules);
    RUN("retry", test_errid_rules);
    RUN("retry", test_classify_success_fixtures);
    RUN("retry", test_classify_audio_url);
    RUN("retry", test_envelope_from_real_fixtures);
    RUN("retry", test_envelope_single_sentence_paragraph);
    RUN("retry", test_align_result_cases);
    RUN("retry", test_classify_errors);
    RUN("retry", test_classify_error_fixtures);
    RUN("retry", test_error_message);
    RUN("retry", test_controller_basic);
    RUN("retry", test_controller_retry_after);
    RUN("retry", test_controller_auto_retry);
    RUN("retry", test_controller_deadline);
}
