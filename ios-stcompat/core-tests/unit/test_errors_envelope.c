/*
 * Copyright 2026 优谷雅言 open.shengzhiai.com
 * SPDX-License-Identifier: Apache-2.0
 *
 * Every entry of spec/errors.json against the compiled table and the errId mapping, then the
 * result and error JSON, dtLastResponse, params echo, base URL and identifiers.
 */
#include "ygt.h"

static int str_member(ygst_span obj, const char *key, ygst_buf *out) {
    ygst_span v;
    ygst_buf_reset(out);
    if (ygst_json_member(obj, key, &v) != 1) return 0;
    return ygst_json_string(v, out) == 0;
}

static long long int_member(ygst_span obj, const char *key) {
    ygst_span v;
    long long n = -1;
    if (ygst_json_member(obj, key, &v) == 1) ygst_json_int(v, &n);
    return n;
}

static int bool_member(ygst_span obj, const char *key) {
    ygst_span v;
    int b = -1;
    if (ygst_json_member(obj, key, &v) == 1) ygst_json_bool(v, &b);
    return b;
}

/* kind: 0 errors, 1 warnings, 2 local */
static int check_table(ygst_span root, const char *array, int kind) {
    ygst_span arr, el;
    size_t pos = 0;
    int n = 0;
    ygst_buf name, category, message;
    ygst_buf_init(&name);
    ygst_buf_init(&category);
    ygst_buf_init(&message);
    CHECK_INT(ygst_json_member(root, array, &arr), 1);
    while (ygst_json_array_next(arr, &pos, &el) == 1) {
        int code = (int)int_member(el, "code");
        int retryable = bool_member(el, "retryable");
        const ygst_error_entry *e = kind == 0 ? ygst_error_find(code) : kind == 1 ? ygst_warning_find(code)
                                                                                    : ygst_local_find(code);
        const ygst_error_entry *row = kind == 0 ? &ygst_table_errors[n]
                                    : kind == 1 ? &ygst_table_warnings[n]
                                                : &ygst_table_local[n];
        str_member(el, "name", &name);
        str_member(el, "category", &category);
        str_member(el, "message", &message);
        CHECK(e != NULL);
        CHECK(row == e); /* same order as errors.json, no duplicate codes inside one table */
        if (e) {
            CHECK_STR(e->name, ygst_buf_cstr(&name));
            CHECK_STR(e->category, ygst_buf_cstr(&category));
            CHECK_STR(e->message, ygst_buf_cstr(&message));
            CHECK_INT(e->retryable, retryable);
            if (kind == 0) CHECK_INT(e->http, int_member(el, "http"));
        }
        if (kind == 0) {
            /* a platform error body with this code: retry flag and errId per DESIGN 2.3 and 6.2 */
            ygst_classification c;
            ygst_outcome o;
            char body[512];
            ygst_classification_init(&c);
            memset(&o, 0, sizeof o);
            snprintf(body, sizeof body, "{\"code\":%d,\"message\":\"m\",\"timestamp\":1}", code);
            o.http_status = (int)int_member(el, "http");
            o.body = body;
            o.body_len = strlen(body);
            o.retry_after_ms = -1;
            ygst_classify(&o, &c);
            CHECK_INT(c.biz_code, code);
            CHECK_INT(c.retryable, retryable);
            CHECK_INT(c.err_id, retryable ? 20009 : code);
            CHECK_INT(ygst_is_retryable(0, code, 0), retryable);
            ygst_classification_free(&c);
        } else if (kind == 2) {
            ygst_classification c;
            ygst_outcome o;
            ygst_classification_init(&c);
            memset(&o, 0, sizeof o);
            o.local_code = code;
            ygst_classify(&o, &c);
            CHECK_INT(c.retryable, retryable);
            CHECK_INT(c.err_id, retryable ? 20009 : code);
            CHECK_STR(ygst_buf_cstr(&c.message), ygst_buf_cstr(&message));
            ygst_classification_free(&c);
        }
        n++;
    }
    ygst_buf_free(&name);
    ygst_buf_free(&category);
    ygst_buf_free(&message);
    return n;
}

static void test_errors_json_every_entry(void) {
    size_t len = 0;
    char *text = ygt_read_spec("errors.json", &len);
    ygst_span root, arr, el, v, k;
    size_t pos = 0;
    int n_err, n_warn, n_local, n_compat = 0, n_http = 0, n_fallback = 0;
    ygst_buf b;
    CHECK(text != NULL);
    if (!text) return;
    ygst_buf_init(&b);
    CHECK_INT(ygst_json_parse(text, len, &root), 0);
    str_member(root, "version", &b);
    CHECK_STR(ygst_table_version, ygst_buf_cstr(&b));
    n_err = check_table(root, "errors", 0);
    n_warn = check_table(root, "warnings", 1);
    n_local = check_table(root, "local", 2);
    CHECK_INT(n_err, ygst_table_errors_count);
    CHECK_INT(n_warn, ygst_table_warnings_count);
    CHECK_INT(n_local, ygst_table_local_count);

    CHECK_INT(ygst_json_member(root, "compatErrIds", &arr), 1);
    while (ygst_json_array_next(arr, &pos, &el) == 1) {
        int id = (int)int_member(el, "errId");
        str_member(el, "message", &b);
        CHECK_STR(ygst_compat_message(id), ygst_buf_cstr(&b));
        CHECK_STR(ygst_table_compat[n_compat].message, ygst_buf_cstr(&b));
        if (id >= 60001 && id <= 60009) {
            CHECK_INT(ygst_errid_for(id, 0, 0, 0), id);
        } else {
            CHECK_INT(id, 20009);
            CHECK_INT(ygst_errid_for(0, 50000, 500, 1), id);
        }
        n_compat++;
    }
    CHECK_INT(n_compat, ygst_table_compat_count);
    CHECK(ygst_compat_message(12345) == NULL);

    pos = 0;
    CHECK_INT(ygst_json_member(root, "retryableHttp", &arr), 1);
    while (ygst_json_array_next(arr, &pos, &el) == 1) {
        long long s = 0;
        ygst_json_int(el, &s);
        CHECK(ygst_http_retryable((int)s));
        CHECK_INT(ygst_table_retryable_http[n_http], s);
        n_http++;
    }
    CHECK_INT(n_http, ygst_table_retryable_http_count);
    CHECK(!ygst_http_retryable(400));

    pos = 0;
    CHECK_INT(ygst_json_member(root, "httpFallback", &v), 1);
    while (ygst_json_object_next(v, &pos, &k, &el) == 1) {
        ygst_buf key;
        ygst_buf_init(&key);
        ygst_json_string(k, &key);
        ygst_buf_reset(&b);
        ygst_json_string(el, &b);
        CHECK_STR(ygst_http_fallback_category(atoi(ygst_buf_cstr(&key))), ygst_buf_cstr(&b));
        ygst_buf_free(&key);
        n_fallback++;
    }
    CHECK(n_fallback >= 17);
    CHECK_STR(ygst_http_fallback_category(599), "SERVER");
    CHECK_STR(ygst_http_fallback_category(418), "INVALID_PARAM");
    CHECK_STR(ygst_http_fallback_category(499), "INVALID_PARAM");
    CHECK_STR(ygst_http_fallback_category(302), "UNKNOWN");
    CHECK_STR(ygst_http_fallback_category(600), "UNKNOWN");
    CHECK_STR(ygst_http_fallback_category(0), "UNKNOWN");
    CHECK(ygst_error_find(1) == NULL);
    CHECK(ygst_warning_find(2) == NULL);
    CHECK(ygst_local_find(3) == NULL);
    printf("     errors.json %s: %d errors, %d warnings, %d local, %d compat errIds, %d HTTP fallbacks\n",
           ygst_table_version, n_err, n_warn, n_local, n_compat, n_fallback);
    ygst_buf_free(&b);
    free(text);
}

/* ---------------------------------------------------------------- envelope */

static void test_format_dt(void) {
    char out[32];
    ygst_format_dt(0, 0, out);
    CHECK_STR(out, "1970-01-01 00:00:00:000");
    ygst_format_dt(1791447692255LL, 480, out);
    CHECK_STR(out, "2026-10-08 16:21:32:255");
    ygst_format_dt(1791447692255LL, -300, out);
    CHECK_STR(out, "2026-10-08 03:21:32:255");
    ygst_format_dt(1709164800123LL, 0, out);
    CHECK_STR(out, "2024-02-29 00:00:00:123");
    ygst_format_dt(-1, 0, out);
    CHECK_STR(out, "1969-12-31 23:59:59:999");
    ygst_format_dt(951782400000LL - 1, 0, out); /* the day before 2000-02-29 ends */
    CHECK_STR(out, "2000-02-28 23:59:59:999");
}

static void test_envelope_result(void) {
    ygst_buf out;
    ygst_span raw, root, v;
    const char *result = "{ \"overall\": 73.730, \"words\": [] }";
    ygst_buf s;
    raw.p = result;
    raw.n = strlen(result);
    ygst_buf_init(&out);
    ygst_buf_init(&s);
    CHECK_INT(ygst_envelope_result(&out, "0123456789abcdef0123456789abcdef", "\"eval_1\"", "ak", "u\"1", "今天\n",
                                   "2026-10-08 16:21:32:255", raw, NULL, NULL),
              0);
    CHECK_STR(ygst_buf_cstr(&out),
              "{\"tokenId\":\"0123456789abcdef0123456789abcdef\",\"recordId\":\"eval_1\",\"applicationId\":\"ak\","
              "\"userId\":\"u\\\"1\",\"refText\":\"今天\\n\",\"eof\":1,\"dtLastResponse\":\"2026-10-08 16:21:32:255\","
              "\"result\":{ \"overall\": 73.730, \"words\": [] }}");
    CHECK_INT(ygst_json_parse((const char *)out.data, out.len, &root), 0);
    ygst_buf_reset(&out);
    CHECK_INT(ygst_envelope_result(&out, "t", NULL, "a", "", "", "d", raw, "{\"app\":{}}", "https://x/a.wav"), 0);
    CHECK_INT(ygst_json_parse((const char *)out.data, out.len, &root), 0);
    CHECK_INT(ygst_json_member(root, "recordId", &v), 0); /* omitted when the platform sent none */
    CHECK_INT(ygst_json_member(root, "params", &v), 1);
    CHECK_INT(ygst_json_type(v), 'o');
    CHECK_INT(ygst_json_member(root, "audioUrl", &v), 1);
    ygst_json_string(v, &s);
    CHECK_STR(ygst_buf_cstr(&s), "https://x/a.wav");
    CHECK_INT(ygst_json_member(root, "eof", &v), 1);
    raw.n = 0;
    CHECK_INT(ygst_envelope_result(&out, "t", "r", "a", "", "", "d", raw, NULL, NULL), -1);
    ygst_buf_free(&out);
    ygst_buf_free(&s);
}

static void test_envelope_error(void) {
    ygst_buf out;
    ygst_span root;
    ygst_buf_init(&out);
    CHECK_INT(ygst_envelope_error(&out, "abc", 60003, "coreType 不支持: open.eval", "app\\key"), 0);
    CHECK_STR(ygst_buf_cstr(&out),
              "{\"tokenId\":\"abc\",\"errId\":60003,\"error\":\"coreType 不支持: open.eval\",\"eof\":1,"
              "\"applicationId\":\"app\\\\key\"}");
    CHECK_INT(ygst_json_parse((const char *)out.data, out.len, &root), 0);
    ygst_buf_free(&out);
}

static void test_params_json(void) {
    ygst_buf out;
    ygst_fields f;
    ygst_span root;
    ygst_buf_init(&out);
    ygst_fields_init(&f);
    ygst_fields_set(&f, "refText", "Hello");
    ygst_fields_set(&f, "coreType", "ignored");
    ygst_fields_set(&f, "scale", "100");
    CHECK_INT(ygst_params_json(&out, "ak", "uid", 1791447692, "wav", 16000, 1, 2, "sent.eval", "tok", &f), 0);
    CHECK_STR(ygst_buf_cstr(&out),
              "{\"app\":{\"applicationId\":\"ak\",\"userId\":\"uid\",\"timestamp\":\"1791447692\"},"
              "\"audio\":{\"audioType\":\"wav\","
              "\"sampleRate\":16000,\"channel\":1,\"sampleBytes\":2},\"request\":{\"coreType\":\"sent.eval\","
              "\"tokenId\":\"tok\",\"refText\":\"Hello\",\"scale\":\"100\"}}");
    CHECK_INT(ygst_json_parse((const char *)out.data, out.len, &root), 0);
    ygst_buf_reset(&out);
    CHECK_INT(ygst_params_json(&out, "ak", "", 0, "mp3", 16000, 1, 2, "word.eval", "tok", NULL), 0);
    CHECK_INT(ygst_json_parse((const char *)out.data, out.len, &root), 0);
    ygst_fields_free(&f);
    ygst_buf_free(&out);
}

static void test_base_url(void) {
    char out[256];
    CHECK_INT(ygst_resolve_base_url(NULL, out, sizeof out), YGST_BASE_DEFAULT_EMPTY);
    CHECK_STR(out, "https://open.shengzhiai.com");
    CHECK_INT(ygst_resolve_base_url("  ", out, sizeof out), YGST_BASE_DEFAULT_EMPTY);
    CHECK_INT(ygst_resolve_base_url("ws://api.stkouyu.com:8080", out, sizeof out), YGST_BASE_DEFAULT_SHENGTONG);
    CHECK_STR(out, "https://open.shengzhiai.com");
    CHECK_INT(ygst_resolve_base_url("ws://gray.stkouyu.com:8090", out, sizeof out), YGST_BASE_DEFAULT_SHENGTONG);
    CHECK_INT(ygst_resolve_base_url("WSS://STKOUYU.COM/x", out, sizeof out), YGST_BASE_DEFAULT_SHENGTONG);
    CHECK_INT(ygst_resolve_base_url("ws://notstkouyu.com", out, sizeof out), YGST_BASE_CUSTOM);
    CHECK_STR(out, "http://notstkouyu.com");
    CHECK_INT(ygst_resolve_base_url("ws://stkouyu.com.evil.example", out, sizeof out), YGST_BASE_CUSTOM);
    CHECK_INT(ygst_resolve_base_url("http://127.0.0.1:18900/", out, sizeof out), YGST_BASE_CUSTOM);
    CHECK_STR(out, "http://127.0.0.1:18900");
    CHECK_INT(ygst_resolve_base_url("https://example.com/base/?x=1", out, sizeof out), YGST_BASE_CUSTOM);
    CHECK_STR(out, "https://example.com/base");
    CHECK_INT(ygst_resolve_base_url("wss://example.com:443/path#frag", out, sizeof out), YGST_BASE_CUSTOM);
    CHECK_STR(out, "https://example.com:443/path");
    CHECK_INT(ygst_resolve_base_url("http://[::1]:8080", out, sizeof out), YGST_BASE_CUSTOM);
    CHECK_STR(out, "http://[::1]:8080");
    CHECK_INT(ygst_resolve_base_url("http://[::1", out, sizeof out), YGST_BASE_ERR_HOST);
    CHECK_INT(ygst_resolve_base_url("example.com", out, sizeof out), YGST_BASE_ERR_SCHEME);
    CHECK_STR(out, "https://open.shengzhiai.com");
    CHECK_INT(ygst_resolve_base_url("api.stkouyu.com:8080", out, sizeof out), YGST_BASE_DEFAULT_SHENGTONG);
    CHECK_INT(ygst_resolve_base_url("ftp://x", out, sizeof out), YGST_BASE_ERR_SCHEME);
    CHECK_INT(ygst_resolve_base_url("http://", out, sizeof out), YGST_BASE_ERR_HOST);
    CHECK_INT(ygst_resolve_base_url("https:///path", out, sizeof out), YGST_BASE_ERR_HOST);
    CHECK_INT(ygst_resolve_base_url("http://averyveryverylonghostname.example.com", out, 20), YGST_BASE_ERR_HOST);
    CHECK_INT(ygst_build_url("http://h:1/", "sent.eval", out, sizeof out), 0);
    CHECK_STR(out, "http://h:1/sent.eval");
    CHECK_INT(ygst_build_url("http://h:1", "sent.eval", out, 8), -1);
    CHECK_INT(ygst_build_url(NULL, "x", out, sizeof out), -1);
}

static void test_identifiers(void) {
    unsigned char rnd[16];
    char tok[33];
    char masked[16];
    char long_key[260];
    size_t i;
    for (i = 0; i < 16; i++) rnd[i] = (unsigned char)(0xF0 + i);
    ygst_token_id(rnd, tok);
    CHECK_STR(tok, "f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff");
    CHECK(ygst_idempotency_key_ok(tok));
    CHECK(!ygst_idempotency_key_ok(""));
    CHECK(!ygst_idempotency_key_ok(NULL));
    CHECK(!ygst_idempotency_key_ok("has space"));
    CHECK(!ygst_idempotency_key_ok("tab\t"));
    CHECK(!ygst_idempotency_key_ok("\xC3\xA9"));
    memset(long_key, 'k', 200);
    long_key[200] = 0;
    CHECK(ygst_idempotency_key_ok(long_key));
    long_key[200] = 'k';
    long_key[201] = 0;
    CHECK(!ygst_idempotency_key_ok(long_key));
    ygst_mask_app_key("mock-app-key", masked);
    CHECK_STR(masked, "mock***");
    ygst_mask_app_key("ab", masked);
    CHECK_STR(masked, "ab***");
    ygst_mask_app_key(NULL, masked);
    CHECK_STR(masked, "***");
}

void suite_errors(void) {
    RUN("errors", test_errors_json_every_entry);
}

void suite_envelope(void) {
    RUN("envelope", test_format_dt);
    RUN("envelope", test_envelope_result);
    RUN("envelope", test_envelope_error);
    RUN("envelope", test_params_json);
    RUN("envelope", test_base_url);
    RUN("envelope", test_identifiers);
}
