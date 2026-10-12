/*
 * Copyright 2026 优谷雅言 open.shengzhiai.com
 * SPDX-License-Identifier: Apache-2.0
 *
 * Realtime feedback (2.1.0): percent-encoding, WebSocket base and handshake URL against the shared
 * signature vector, parameter frame, classification of the progress frames recorded from the
 * platform (spec/fixtures/platform/ *progress*.json), the eof 0 envelope with trimmed words, the
 * absolute-index dedupe of the reconnect rule and every path of the round controller.
 */
#include "ygt.h"

#define TOKEN "3f0b6a1c9d2e4f5a8b7c6d5e4f3a2b1c"
#define CUSTOMER_TEXT "我是中国人，我是中国人。我们都是中国人。中华民族是一家人。"

/* the customer sentence by character, the English one by word */
static const char *const ZH_UNITS[] = {"我", "是", "中", "国", "人", "我", "是", "中", "国", "人", "我", "们", "都",
                                       "是", "中", "国", "人", "中", "华", "民", "族", "是", "一", "家", "人"};
static const char *const EN_UNITS[] = {"Nice", "to", "see", "you", "again", "I",     "am",    "a",
                                       "student", "and", "I", "like", "reading", "books", "every", "day"};

/* ------------------------------------------------------------------ helpers */

static int hexdig(char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    return -1;
}

/* Percent-decoding the way the platform reads a query value (java.net.URLDecoder, '+' is a space). */
static void url_decode(const char *s, size_t n, ygst_buf *out) {
    size_t i;
    for (i = 0; i < n; i++) {
        if (s[i] == '%' && i + 2 < n + 0 && hexdig(s[i + 1]) >= 0 && hexdig(s[i + 2]) >= 0) {
            ygst_buf_append_char(out, (char)(hexdig(s[i + 1]) * 16 + hexdig(s[i + 2])));
            i += 2;
        } else if (s[i] == '+') {
            ygst_buf_append_char(out, ' ');
        } else {
            ygst_buf_append_char(out, s[i]);
        }
    }
    ygst_buf_cstr(out);
}

/* Verifies a handshake URL like the platform does: decode every query parameter, sign all of them
 * except signature, compare. Returns 1 when the signature matches; fills params when given. */
static int platform_verifies(const char *url, const char *secret, ygst_fields *params, char *sig_out) {
    const char *q = strchr(url, '?');
    ygst_fields f;
    char sig[45];
    char got[64] = {0};
    int ok;
    ygst_fields_init(&f);
    if (!q) return 0;
    q++;
    while (*q) {
        const char *amp = strchr(q, '&');
        const char *end = amp ? amp : q + strlen(q);
        const char *eq = memchr(q, '=', (size_t)(end - q));
        ygst_buf k, v;
        ygst_buf_init(&k);
        ygst_buf_init(&v);
        if (eq) {
            url_decode(q, (size_t)(eq - q), &k);
            url_decode(eq + 1, (size_t)(end - eq - 1), &v);
            if (strcmp(ygst_buf_cstr(&k), "signature") == 0) {
                snprintf(got, sizeof got, "%s", ygst_buf_cstr(&v));
            } else {
                ygst_fields_set(&f, ygst_buf_cstr(&k), ygst_buf_cstr(&v));
            }
        }
        ygst_buf_free(&k);
        ygst_buf_free(&v);
        q = amp ? amp + 1 : end;
    }
    ok = ygst_sign(&f, secret, sig) == 0 && strcmp(sig, got) == 0;
    if (sig_out) snprintf(sig_out, 64, "%s", got);
    if (params) ygst_fields_copy(&f, params);
    ygst_fields_free(&f);
    return ok;
}

static int str_member(ygst_span obj, const char *key, ygst_buf *out) {
    ygst_span v;
    ygst_buf_reset(out);
    if (ygst_json_member(obj, key, &v) != 1 || ygst_json_type(v) != 's') return 0;
    return ygst_json_string(v, out) == 0;
}

static long long int_member(ygst_span obj, const char *key) {
    ygst_span v;
    long long n = -1;
    if (ygst_json_member(obj, key, &v) == 1) ygst_json_int(v, &n);
    return n;
}

static int bool_of(ygst_span obj, const char *key) {
    ygst_span v;
    int b = -1;
    if (ygst_json_member(obj, key, &v) == 1) ygst_json_bool(v, &b);
    return b;
}

/* Keys of a JSON object joined with commas. */
static void key_list(ygst_span obj, ygst_buf *out) {
    size_t pos = 0;
    ygst_span k, v;
    ygst_buf_reset(out);
    while (ygst_json_object_next(obj, &pos, &k, &v) == 1) {
        if (out->len) ygst_buf_append_char(out, ',');
        ygst_json_string(k, out);
    }
    ygst_buf_cstr(out);
}

/* The i-th element of an array; 0 and an empty span when there is none. */
static int nth(ygst_span arr, int i, ygst_span *out) {
    size_t pos = 0;
    int n = 0;
    while (ygst_json_type(arr) == 'a' && ygst_json_array_next(arr, &pos, out) == 1) {
        if (n++ == i) return 1;
    }
    out->p = NULL;
    out->n = 0;
    return 0;
}

/* A Shengtong intermediate result item: charType, scores{pronunciation, skipped, tone, overall,
 * overall_pron}, word, word_parts[{beginIndex, charType, endIndex, part}]. Checks the shape, that
 * the word is `want` and the scores of a matched (100, skipped 0) or unmatched unit (0, skipped 1). */
static void check_item(ygst_span item, const char *want, int matched) {
    ygst_span scores, parts, part;
    ygst_buf w, keys;
    ygst_buf_init(&w);
    ygst_buf_init(&keys);
    CHECK_INT(ygst_json_type(item), 'o');
    if (ygst_json_type(item) != 'o') return;
    key_list(item, &keys);
    CHECK_STR(ygst_buf_cstr(&keys), "charType,scores,word,word_parts");
    CHECK(int_member(item, "charType") == 0 || int_member(item, "charType") == 1);
    CHECK(str_member(item, "word", &w));
    CHECK_STR(ygst_buf_cstr(&w), want);
    if (ygst_json_member(item, "scores", &scores) == 1 && ygst_json_type(scores) == 'o') {
        key_list(scores, &keys);
        CHECK_STR(ygst_buf_cstr(&keys), "pronunciation,skipped,tone,overall,overall_pron");
        CHECK_INT(int_member(scores, "overall"), matched ? 100 : 0);
        CHECK_INT(int_member(scores, "pronunciation"), matched ? 100 : 0);
        CHECK_INT(int_member(scores, "overall_pron"), matched ? 100 : 0);
        CHECK_INT(int_member(scores, "skipped"), matched ? 0 : 1);
    } else {
        CHECK(!"scores object");
    }
    if (ygst_json_member(item, "word_parts", &parts) == 1 && nth(parts, 0, &part) && ygst_json_type(part) == 'o') {
        key_list(part, &keys);
        CHECK_STR(ygst_buf_cstr(&keys), "beginIndex,charType,endIndex,part");
        CHECK(str_member(part, "part", &w));
    } else {
        CHECK(!"word_parts with a part object");
    }
    ygst_buf_free(&w);
    ygst_buf_free(&keys);
}

/* ------------------------------------------------------------------ URL and handshake */

static void test_url_encode(void) {
    ygst_buf b;
    ygst_buf_init(&b);
    CHECK_INT(ygst_url_encode(&b, "AZaz09-._~"), 0);
    CHECK_STR(ygst_buf_cstr(&b), "AZaz09-._~");
    ygst_buf_reset(&b);
    ygst_url_encode(&b, "a+b/c=d e&f?:");
    CHECK_STR(ygst_buf_cstr(&b), "a%2Bb%2Fc%3Dd%20e%26f%3F%3A");
    ygst_buf_reset(&b);
    ygst_url_encode(&b, "中");
    CHECK_STR(ygst_buf_cstr(&b), "%E4%B8%AD");
    ygst_buf_reset(&b);
    ygst_url_encode(&b, NULL);
    CHECK_STR(ygst_buf_cstr(&b), "");
    ygst_buf_free(&b);
}

static void test_ws_base_url(void) {
    char out[64];
    char tiny[8];
    CHECK_INT(ygst_ws_base_url("https://open.shengzhiai.com", out, sizeof out), 0);
    CHECK_STR(out, "wss://open.shengzhiai.com");
    CHECK_INT(ygst_ws_base_url("HTTP://127.0.0.1:8080/", out, sizeof out), 0);
    CHECK_STR(out, "ws://127.0.0.1:8080");
    CHECK_INT(ygst_ws_base_url("https://h.example/prefix//", out, sizeof out), 0);
    CHECK_STR(out, "wss://h.example/prefix");
    CHECK_INT(ygst_ws_base_url("wss://h", out, sizeof out), 0);
    CHECK_STR(out, "wss://h");
    CHECK_INT(ygst_ws_base_url("ws://[::1]:9", out, sizeof out), 0);
    CHECK_STR(out, "ws://[::1]:9");
    CHECK_INT(ygst_ws_base_url("ftp://h", out, sizeof out), -1);
    CHECK_STR(out, "");
    CHECK_INT(ygst_ws_base_url("https://", out, sizeof out), -1);
    CHECK_INT(ygst_ws_base_url("https:///x", out, sizeof out), -1);
    CHECK_INT(ygst_ws_base_url(NULL, out, sizeof out), -1);
    CHECK_INT(ygst_ws_base_url("https://h", NULL, 4), -1);
    CHECK_INT(ygst_ws_base_url("https://open.shengzhiai.com", tiny, sizeof tiny), -1);
    CHECK_STR(tiny, "");
}

/* The ws_handshake_query vector of spec/fixtures/sign/vectors.json through the URL builder. */
static void test_ws_handshake_vector(void) {
    size_t len = 0;
    char *text = ygt_read_spec("fixtures/sign/vectors.json", &len);
    ygst_span root, cases, el, params, sigv, secretv;
    size_t pos = 0;
    int found = 0;
    CHECK(text != NULL);
    if (!text) return;
    CHECK_INT(ygst_json_parse(text, len, &root), 0);
    CHECK_INT(ygst_json_member(root, "cases", &cases), 1);
    while (ygst_json_array_next(cases, &pos, &el) == 1) {
        ygst_buf name, secret, want, url, ak, ts, nonce, key;
        ygst_fields got;
        char sig[64];
        ygst_buf_init(&name);
        ygst_buf_init(&secret);
        ygst_buf_init(&want);
        ygst_buf_init(&url);
        ygst_buf_init(&ak);
        ygst_buf_init(&ts);
        ygst_buf_init(&nonce);
        ygst_buf_init(&key);
        ygst_fields_init(&got);
        str_member(el, "name", &name);
        if (strcmp(ygst_buf_cstr(&name), "ws_handshake_query") == 0) {
            found = 1;
            CHECK_INT(ygst_json_member(el, "params", &params), 1);
            CHECK_INT(ygst_json_member(el, "signature", &sigv), 1);
            CHECK_INT(ygst_json_member(el, "secret", &secretv), 1);
            ygst_json_string(sigv, &want);
            ygst_json_string(secretv, &secret);
            str_member(params, "appKey", &ak);
            str_member(params, "timestamp", &ts);
            str_member(params, "nonce", &nonce);
            str_member(params, "idempotencyKey", &key);
            CHECK_INT(ygst_ws_handshake_url("https://open.shengzhiai.com", "sent.eval.cn", ygst_buf_cstr(&ak),
                                            ygst_buf_cstr(&secret), atoll(ygst_buf_cstr(&ts)), ygst_buf_cstr(&nonce),
                                            ygst_buf_cstr(&key), NULL, &url),
                      0);
            CHECK_STR(ygst_buf_cstr(&url),
                      "wss://open.shengzhiai.com/sent.eval.cn?appKey=ak_test&timestamp=1791447600"
                      "&nonce=0123456789abcdef&idempotencyKey=3f0b6a1c9d2e4f5a8b7c6d5e4f3a2b1c"
                      "&signature=%2FnQKY6YLDY1o9QsZphE5etDBKxhUMyIjgqFEOlOFx8w%3D");
            CHECK(platform_verifies(ygst_buf_cstr(&url), ygst_buf_cstr(&secret), &got, sig));
            CHECK_STR(sig, ygst_buf_cstr(&want));
            CHECK_INT(got.count, 4);
            CHECK_STR(ygst_fields_get(&got, "idempotencyKey"), TOKEN);
        }
        ygst_fields_free(&got);
        ygst_buf_free(&name);
        ygst_buf_free(&secret);
        ygst_buf_free(&want);
        ygst_buf_free(&url);
        ygst_buf_free(&ak);
        ygst_buf_free(&ts);
        ygst_buf_free(&nonce);
        ygst_buf_free(&key);
    }
    CHECK(found);
    free(text);
}

static void test_ws_handshake_extra_and_guards(void) {
    ygst_buf url;
    ygst_fields extra, got;
    static const char *prefix = "ws://127.0.0.1:8080/sent.eval?appKey=mock-app-key&timestamp=1791447600&nonce=00ff"
                                "&idempotencyKey=" TOKEN "&mockFault=ws-kill-after%3A3&lang=%E4%B8%AD%E6%96%87%20a%2Bb"
                                "&signature=";
    ygst_buf_init(&url);
    ygst_fields_init(&extra);
    ygst_fields_init(&got);
    ygst_fields_set(&extra, "mockFault", "ws-kill-after:3");
    ygst_fields_set(&extra, "empty", "");
    ygst_fields_set(&extra, "signature", "forged");
    ygst_fields_set(&extra, "appKey", "other");
    ygst_fields_set(&extra, "lang", "中文 a+b");
    CHECK_INT(ygst_ws_handshake_url("http://127.0.0.1:8080/", "sent.eval", "mock-app-key", "mock-secret-key", 1791447600,
                                    "00ff", TOKEN, &extra, &url),
              0);
    CHECK(strncmp(ygst_buf_cstr(&url), prefix, strlen(prefix)) == 0);
    CHECK(strstr(ygst_buf_cstr(&url), "empty") == NULL);
    CHECK(strstr(ygst_buf_cstr(&url), "forged") == NULL);
    CHECK(strstr(ygst_buf_cstr(&url), "other") == NULL);
    /* the extra parameters are signed: the platform's check accepts the URL, another secret fails */
    CHECK(platform_verifies(ygst_buf_cstr(&url), "mock-secret-key", &got, NULL));
    CHECK_INT(got.count, 6);
    CHECK_STR(ygst_fields_get(&got, "lang"), "中文 a+b");
    CHECK_STR(ygst_fields_get(&got, "mockFault"), "ws-kill-after:3");
    CHECK(!platform_verifies(ygst_buf_cstr(&url), "wrong-secret", NULL, NULL));
    /* bad input */
    ygst_buf_reset(&url);
    CHECK_INT(ygst_ws_handshake_url("ftp://h", "sent.eval", "k", "s", 1, "n", TOKEN, NULL, &url), -1);
    CHECK_INT(ygst_ws_handshake_url("https://h", "", "k", "s", 1, "n", TOKEN, NULL, &url), -1);
    CHECK_INT(ygst_ws_handshake_url("https://h", "sent.eval", " ", "s", 1, "n", TOKEN, NULL, &url), -1);
    CHECK_INT(ygst_ws_handshake_url("https://h", "sent.eval", "k", "", 1, "n", TOKEN, NULL, &url), -1);
    CHECK_INT(ygst_ws_handshake_url("https://h", "sent.eval", "k", "s", 1, "", TOKEN, NULL, &url), -1);
    CHECK_INT(ygst_ws_handshake_url("https://h", "sent.eval", "k", "s", 1, "n", "bad key", NULL, &url), -1);
    CHECK_INT(ygst_ws_handshake_url("https://h", "sent.eval", "k", "s", 1, "n", TOKEN, NULL, NULL), -1);
    CHECK_INT(url.len, 0);
    ygst_fields_free(&extra);
    ygst_fields_free(&got);
    ygst_buf_free(&url);
}

/* ------------------------------------------------------------------ parameter frame */

static void test_ws_param_frame(void) {
    ygst_test_params p;
    ygst_fields f, odd;
    ygst_buf out;
    ygst_span root, v;
    ygst_buf_init(&out);
    ygst_fields_init(&f);
    ygst_fields_init(&odd);
    ygst_test_params_init(&p);
    p.core_type = "sent.eval.cn";
    p.ref_text = CUSTOMER_TEXT;
    p.phoneme_output = 1; /* KYTestConfig default */
    CHECK_INT(ygst_params_to_fields(&p, &f), 0);
    /* exactly the REST form fields of the same config, then the two realtime members */
    CHECK_INT(ygst_ws_param_frame(&f, TOKEN, &out), 0);
    CHECK_STR(ygst_buf_cstr(&out), "{\"refText\":\"" CUSTOMER_TEXT "\",\"phoneme_output\":\"1\","
                                   "\"realtime_feedback\":true,\"idempotencyKey\":\"" TOKEN "\"}");
    CHECK_INT(ygst_json_parse((const char *)out.data, out.len, &root), 0);
    CHECK_INT(ygst_json_member(root, "realtime_feedback", &v), 1);
    CHECK_INT(ygst_json_type(v), 't');
    /* paragraph rule, request JSON and escaping travel unchanged */
    ygst_fields_free(&f);
    ygst_fields_init(&f);
    p.core_type = "para.eval";
    p.ref_text = "Say \"hi\"\n";
    p.request_json = "{\"slack\":0.1}";
    p.attach_audio_url = 1;
    CHECK_INT(ygst_params_to_fields(&p, &f), 0);
    ygst_buf_reset(&out);
    CHECK_INT(ygst_ws_param_frame(&f, TOKEN, &out), 0);
    CHECK_STR(ygst_buf_cstr(&out), "{\"refText\":\"Say \\\"hi\\\"\\n\",\"paragraph_need_word_score\":\"1\","
                                   "\"phoneme_output\":\"1\",\"attachAudioUrl\":\"1\",\"request\":\"{\\\"slack\\\":0.1}\","
                                   "\"realtime_feedback\":true,\"idempotencyKey\":\"" TOKEN "\"}");
    CHECK_INT(ygst_json_parse((const char *)out.data, out.len, &root), 0);
    /* names the platform reads as commands or sets itself never come from customParams */
    ygst_fields_set(&odd, "refText", "x");
    ygst_fields_set(&odd, "end", "true");
    ygst_fields_set(&odd, "cmd", "end");
    ygst_fields_set(&odd, "data", "AAAA");
    ygst_fields_set(&odd, "realtime_feedback", "0");
    ygst_fields_set(&odd, "idempotencyKey", "other");
    ygst_buf_reset(&out);
    CHECK_INT(ygst_ws_param_frame(&odd, TOKEN, &out), 5);
    CHECK_STR(ygst_buf_cstr(&out), "{\"refText\":\"x\",\"realtime_feedback\":true,\"idempotencyKey\":\"" TOKEN "\"}");
    ygst_fields_free(&odd);
    ygst_fields_init(&odd);
    ygst_buf_reset(&out);
    CHECK_INT(ygst_ws_param_frame(&odd, TOKEN, &out), 0);
    CHECK_STR(ygst_buf_cstr(&out), "{\"realtime_feedback\":true,\"idempotencyKey\":\"" TOKEN "\"}");
    CHECK_INT(ygst_ws_param_frame(&odd, "", &out), -1);
    CHECK_INT(ygst_ws_param_frame(NULL, TOKEN, &out), -1);
    ygst_fields_free(&odd);
    ygst_fields_free(&f);
    ygst_buf_free(&out);
}

/* ------------------------------------------------------------------ recorded frames */

typedef struct {
    const char *file;
    const char *const *units;
    int total_units;
    int misread;   /* unit read but not matched at the end, -1 none */
    int compat;    /* compat channel: words and recordId "" */
} progress_fixture;

/* Every frame of a round recorded from the platform: kinds in order, counts against the progress
 * list, words items against the units they cover, every unit in words exactly once. */
static void check_progress_fixture(const progress_fixture *fx) {
    size_t len = 0;
    char path[256];
    char *text;
    ygst_span root, el, frame, v, item, unit;
    size_t pos = 0;
    int i = 0, frames = 0, progress = 0, with_words = 0, last_read = -1, last_matched = -1, covered = 0, j;
    long long last_bytes = -1;
    ygst_ws_frame f;
    ygst_buf s, keys;
    snprintf(path, sizeof path, "fixtures/platform/%s", fx->file);
    text = ygt_read_spec(path, &len);
    CHECK(text != NULL);
    if (!text) return;
    ygst_buf_init(&s);
    ygst_buf_init(&keys);
    CHECK_INT(ygst_json_parse(text, len, &root), 0);
    while (ygst_json_array_next(root, &pos, &el) == 1) frames++;
    pos = 0;
    while (ygst_json_array_next(root, &pos, &el) == 1) {
        CHECK_INT(ygst_json_member(el, "frame", &frame), 1);
        CHECK_INT(ygst_ws_frame_parse(frame.p, frame.n, &f), 0);
        if (i == 0) CHECK_INT(f.kind, YGST_WSF_CONNECTED);
        if (i == 1) CHECK_INT(f.kind, YGST_WSF_STARTED);
        if (i == frames - 1) {
            CHECK_INT(f.kind, YGST_WSF_FINAL);
            CHECK(f.result.p != NULL && ygst_json_type(f.result) == 'o');
            CHECK_INT(f.replayed, 0);
            CHECK_INT(int_member(f.root, "eof"), 1);
        } else if (i >= 2) {
            CHECK_INT(f.kind, YGST_WSF_PROGRESS);
            progress++;
            CHECK(f.bytes > 0 && f.bytes >= last_bytes);
            last_bytes = f.bytes;
            CHECK_INT(f.has_progress, 1);
            CHECK_INT(f.total_units, fx->total_units);
            CHECK_INT(f.read_units, int_member(f.result, "readUnits"));
            CHECK_INT(f.matched_units, int_member(f.result, "matchedUnits"));
            CHECK(f.matched_units <= f.read_units);
            /* one platform session: progress never goes back */
            CHECK(f.read_units >= last_read && f.matched_units >= last_matched);
            last_read = f.read_units;
            last_matched = f.matched_units;
            if (ygst_json_member(f.result, "progress", &v) != 1) {
                v.p = NULL;
                v.n = 0;
                CHECK(!"result.progress");
            }
            for (j = 0; j < fx->total_units; j++) {
                CHECK(nth(v, j, &unit));
                str_member(unit, "word", &s);
                CHECK_STR(ygst_buf_cstr(&s), fx->units[j]);
                CHECK_INT(bool_of(unit, "read"), j < f.read_units);
                CHECK(bool_of(unit, "matched") == 0 || bool_of(unit, "read") == 1); /* matched implies read */
            }
            if (fx->compat) {
                CHECK(str_member(f.root, "recordId", &s));
                CHECK_STR(ygst_buf_cstr(&s), "");
                CHECK(str_member(f.result, "kernel_version", &s));
                CHECK(str_member(f.result, "resource_version", &s));
                CHECK_INT(f.has_words, 1);
                /* the items cover [readUnits - words, readUnits), right after the previous frame */
                CHECK_INT(f.read_units - f.word_count, covered);
                for (j = 0; j < f.word_count; j++) {
                    int idx = f.read_units - f.word_count + j;
                    CHECK(nth(f.words, j, &item));
                    CHECK(nth(v, idx, &unit));
                    check_item(item, fx->units[idx], bool_of(unit, "matched") == 1);
                }
                covered = f.read_units;
                if (f.word_count) with_words++;
            } else {
                CHECK_INT(f.has_words, 0); /* the native channel has no Shengtong words */
                CHECK_INT(ygst_json_member(f.root, "recordId", NULL), 0);
            }
        }
        i++;
    }
    CHECK(progress > 10);
    CHECK_INT(last_read, fx->total_units);
    if (fx->compat) {
        CHECK_INT(covered, fx->total_units); /* every unit in words exactly once */
        CHECK(with_words > 3);
    }
    if (fx->misread >= 0) CHECK_INT(last_matched, fx->total_units - 1);
    printf("     %s: %d progress frames, %d with words, last %d/%d read, %d matched\n", fx->file, progress, with_words,
           last_read, fx->total_units, last_matched);
    ygst_buf_free(&s);
    ygst_buf_free(&keys);
    free(text);
}

static void test_frames_recorded_progress(void) {
    static const progress_fixture FX[] = {
        {"ws_compat_sent_eval_cn_progress_frames.json", ZH_UNITS, 25, -1, 1},
        {"ws_compat_sent_eval_cn_progress_misread_frames.json", ZH_UNITS, 25, 7, 1},
        {"ws_compat_sent_eval_progress_frames.json", EN_UNITS, 16, -1, 1},
        {"ws_native_sentence_progress_frames.json", ZH_UNITS, 25, -1, 0},
    };
    size_t i;
    for (i = 0; i < sizeof FX / sizeof FX[0]; i++) check_progress_fixture(&FX[i]);
}

/* The 2.0.0 recording: bytes-only progress frames, two started events. */
static void test_frames_recorded_2_0(void) {
    size_t len = 0;
    char *text = ygt_read_spec("fixtures/platform/ws_compat_sent_eval_cn_frames.json", &len);
    ygst_span root, el, frame;
    size_t pos = 0;
    int kinds[8] = {0};
    ygst_ws_frame f;
    CHECK(text != NULL);
    if (!text) return;
    CHECK_INT(ygst_json_parse(text, len, &root), 0);
    while (ygst_json_array_next(root, &pos, &el) == 1) {
        CHECK_INT(ygst_json_member(el, "frame", &frame), 1);
        CHECK_INT(ygst_ws_frame_parse(frame.p, frame.n, &f), 0);
        kinds[f.kind]++;
        if (f.kind == YGST_WSF_PROGRESS) {
            CHECK_INT(f.has_progress, 0);
            CHECK_INT(f.word_count, 0);
        }
    }
    CHECK_INT(kinds[YGST_WSF_CONNECTED], 1);
    CHECK_INT(kinds[YGST_WSF_STARTED], 2);
    CHECK_INT(kinds[YGST_WSF_FINAL], 1);
    free(text);
}

/* ------------------------------------------------------------------ synthetic frames */

static ygst_ws_frame parse(const char *s) {
    ygst_ws_frame f;
    ygst_ws_frame_parse(s, strlen(s), &f);
    return f;
}

static char g_frame[4096];

/* A compat progress frame of the three units 我 是 人: units [0, read) read, [0, matched) matched,
 * words for the units [from, read). */
static const char *progress_frame(int bytes, int from, int read, int matched) {
    static const char *const U[] = {"我", "是", "人"};
    char *p = g_frame;
    char *e = g_frame + sizeof g_frame;
    int i;
    p += snprintf(p, (size_t)(e - p),
                  "{\"eof\":0,\"recordId\":\"\",\"result\":{\"kernel_version\":\"1.0.0\",\"resource_version\":\"1.0.0\","
                  "\"bytes\":%d,\"readUnits\":%d,\"matchedUnits\":%d,\"totalUnits\":3,\"progress\":[",
                  bytes, read, matched);
    for (i = 0; i < 3; i++) {
        p += snprintf(p, (size_t)(e - p), "%s{\"word\":\"%s\",\"read\":%s,\"matched\":%s}", i ? "," : "", U[i],
                      i < read ? "true" : "false", i < matched ? "true" : "false");
    }
    p += snprintf(p, (size_t)(e - p), "],\"words\":[");
    for (i = from; i < read; i++) {
        int s = i < matched ? 100 : 0;
        p += snprintf(p, (size_t)(e - p),
                      "%s{\"charType\":0,\"scores\":{\"pronunciation\":%d,\"skipped\":%d,\"tone\":%d,\"overall\":%d,"
                      "\"overall_pron\":%d},\"word\":\"%s\",\"word_parts\":[{\"beginIndex\":0,\"charType\":0,"
                      "\"endIndex\":0,\"part\":\"%s\"}]}",
                      i > from ? "," : "", s, s ? 0 : 1, s, s, s, U[i], U[i]);
    }
    snprintf(p, (size_t)(e - p), "]}}");
    return g_frame;
}

static void test_frames_cases(void) {
    ygst_ws_frame f;
    ygst_span item;
    f = parse("{\"eof\":0,\"result\":{\"bytes\":16000}}");
    CHECK_INT(f.kind, YGST_WSF_PROGRESS);
    CHECK_INT(f.has_progress, 0);
    CHECK_INT(f.has_words, 0);
    CHECK_INT(f.word_count, 0);
    CHECK_INT(f.bytes, 16000);
    CHECK_INT(f.read_units, -1);
    f = parse(progress_frame(32000, 1, 2, 1));
    CHECK_INT(f.kind, YGST_WSF_PROGRESS);
    CHECK_INT(f.has_progress, 1);
    CHECK_INT(f.read_units, 2);
    CHECK_INT(f.matched_units, 1);
    CHECK_INT(f.total_units, 3);
    CHECK_INT(f.has_words, 1);
    CHECK_INT(f.word_count, 1);
    CHECK(nth(f.words, 0, &item));
    check_item(item, "是", 0);
    /* counts missing: taken from the progress list; quoted numbers are read too */
    f = parse("{\"eof\":\"0\",\"result\":{\"progress\":[{\"word\":\"a\",\"read\":true,\"matched\":false},"
              "{\"word\":\"b\",\"read\":true,\"matched\":true},{\"word\":\"c\",\"read\":false,\"matched\":false},7],"
              "\"words\":[]}}");
    CHECK_INT(f.kind, YGST_WSF_PROGRESS);
    CHECK_INT(f.bytes, -1);
    CHECK_INT(f.read_units, 2);
    CHECK_INT(f.matched_units, 1);
    CHECK_INT(f.total_units, 4);
    CHECK_INT(f.has_words, 1);
    CHECK_INT(f.word_count, 0);
    f = parse("{\"eof\":0,\"result\":{\"bytes\":\"32000\",\"readUnits\":\"3\",\"matchedUnits\":2,\"totalUnits\":9,"
              "\"progress\":[],\"words\":{}}}");
    CHECK_INT(f.bytes, 32000);
    CHECK_INT(f.read_units, 3);
    CHECK_INT(f.matched_units, 2);
    CHECK_INT(f.total_units, 9);
    CHECK_INT(f.has_words, 0);
    /* native shape: progress without words */
    f = parse("{\"eof\":0,\"result\":{\"bytes\":1,\"readUnits\":1,\"matchedUnits\":1,\"totalUnits\":1,"
              "\"progress\":[{\"word\":\"a\",\"read\":true,\"matched\":true}]}}");
    CHECK_INT(f.has_progress, 1);
    CHECK_INT(f.has_words, 0);
    f = parse("{\"eof\":0}");
    CHECK_INT(f.kind, YGST_WSF_PROGRESS);
    CHECK(f.result.p == NULL);
    f = parse("{\"event\":\"error\",\"code\":50200,\"message\":\"x\"}");
    CHECK_INT(f.kind, YGST_WSF_ERROR);
    CHECK_INT(f.code, 50200);
    f = parse("{\"event\":\"error\",\"code\":\"40001\"}");
    CHECK_INT(f.code, 40001);
    f = parse("{\"event\":\"error\",\"message\":\"no audio\"}");
    CHECK_INT(f.kind, YGST_WSF_ERROR);
    CHECK_INT(f.code, 0);
    CHECK_INT(parse("{\"event\":\"pong\",\"ts\":1}").kind, YGST_WSF_PONG);
    CHECK_INT(parse("{\"event\":\"connected\",\"coreType\":\"sent.eval\"}").kind, YGST_WSF_CONNECTED);
    CHECK_INT(parse("{\"event\":\"started\"}").kind, YGST_WSF_STARTED);
    CHECK_INT(parse("{\"event\":\"something-else-and-long\"}").kind, YGST_WSF_OTHER);
    CHECK_INT(parse("{\"event\":7}").kind, YGST_WSF_OTHER);
    f = parse("{\"event\":\"result\",\"recordId\":\"eval_1\",\"eof\":1,\"result\":{\"overall\":1}}");
    CHECK_INT(f.kind, YGST_WSF_FINAL);
    CHECK_INT(ygst_json_type(f.result), 'o');
    f = parse("{\"recordId\":\"eval_1\",\"eof\":1,\"result\":{\"overall\":1},\"replayed\":true}");
    CHECK_INT(f.kind, YGST_WSF_FINAL);
    CHECK_INT(f.replayed, 1);
    CHECK_INT(parse("{\"recordId\":\"eval_1\"}").kind, YGST_WSF_FINAL);
    CHECK_INT(parse("{\"result\":{}}").kind, YGST_WSF_FINAL);
    CHECK_INT(parse("{\"eof\":1}").kind, YGST_WSF_FINAL);
    CHECK_INT(parse("{}").kind, YGST_WSF_OTHER);
    CHECK_INT(ygst_ws_frame_parse("[1]", 3, &f), -1);
    CHECK_INT(f.kind, YGST_WSF_INVALID);
    CHECK_INT(ygst_ws_frame_parse("{\"eof\":", 7, &f), -1);
    CHECK_INT(ygst_ws_frame_parse(NULL, 0, &f), -1);
    CHECK_INT(ygst_ws_frame_parse("{}", 2, NULL), -1);
}

/* ------------------------------------------------------------------ eof 0 envelope */

static void test_envelope_progress(void) {
    ygst_ws_frame f;
    ygst_buf out, keys, s, want;
    ygst_span env, v, item;
    const char *frame;
    ygst_buf_init(&out);
    ygst_buf_init(&keys);
    ygst_buf_init(&s);
    ygst_buf_init(&want);
    frame = progress_frame(48000, 0, 3, 2);
    f = parse(frame);
    /* nothing left out: the result as the platform sent it */
    CHECK_INT(ygst_envelope_progress(&out, TOKEN, "mock-app-key", "u\"1", CUSTOMER_TEXT, "2026-10-12 12:00:00:123",
                                     f.result, 0),
              0);
    CHECK_INT(ygst_json_parse((const char *)out.data, out.len, &env), 0);
    key_list(env, &keys);
    CHECK_STR(ygst_buf_cstr(&keys), "tokenId,recordId,applicationId,userId,refText,eof,dtLastResponse,result");
    CHECK_INT(int_member(env, "eof"), 0);
    CHECK(str_member(env, "recordId", &s));
    CHECK_STR(ygst_buf_cstr(&s), "");
    str_member(env, "userId", &s);
    CHECK_STR(ygst_buf_cstr(&s), "u\"1");
    str_member(env, "refText", &s);
    CHECK_STR(ygst_buf_cstr(&s), CUSTOMER_TEXT);
    str_member(env, "applicationId", &s);
    CHECK_STR(ygst_buf_cstr(&s), "mock-app-key");
    str_member(env, "dtLastResponse", &s);
    CHECK_STR(ygst_buf_cstr(&s), "2026-10-12 12:00:00:123");
    CHECK_INT(ygst_json_member(env, "result", &v), 1);
    CHECK(v.n == f.result.n && memcmp(v.p, f.result.p, v.n) == 0);
    /* the first two words left out: everything else byte for byte */
    ygst_buf_reset(&out);
    CHECK_INT(ygst_envelope_progress(&out, TOKEN, "a", "u", "r", "d", f.result, 2), 0);
    CHECK_INT(ygst_json_parse((const char *)out.data, out.len, &env), 0);
    CHECK_INT(ygst_json_member(env, "result", &v), 1);
    key_list(v, &keys);
    CHECK_STR(ygst_buf_cstr(&keys), "kernel_version,resource_version,bytes,readUnits,matchedUnits,totalUnits,progress,"
                                    "words");
    CHECK_INT(int_member(v, "readUnits"), 3);
    CHECK_INT(ygst_json_member(v, "words", &v), 1);
    CHECK(nth(v, 0, &item));
    check_item(item, "人", 0);
    CHECK(!nth(v, 1, &item));
    /* the same bytes as a frame that carried only the last word */
    ygst_buf_append_str(&want, progress_frame(48000, 2, 3, 2));
    f = parse(ygst_buf_cstr(&want));
    ygst_buf_reset(&s);
    CHECK_INT(ygst_envelope_progress(&s, TOKEN, "a", "u", "r", "d", f.result, 0), 0);
    CHECK_STR(ygst_buf_cstr(&out), ygst_buf_cstr(&s));
    /* every item left out gives an empty array; a result without words is not touched */
    f = parse(progress_frame(48000, 0, 3, 2));
    ygst_buf_reset(&out);
    CHECK_INT(ygst_envelope_progress(&out, TOKEN, "a", "u", "r", "d", f.result, 9), 0);
    CHECK(strstr(ygst_buf_cstr(&out), "\"words\":[]}") != NULL);
    f = parse("{\"eof\":0,\"result\":{\"bytes\":1}}");
    ygst_buf_reset(&out);
    CHECK_INT(ygst_envelope_progress(&out, TOKEN, "a", "u", "r", "d", f.result, 1), 0);
    CHECK(strstr(ygst_buf_cstr(&out), "\"result\":{\"bytes\":1}}") != NULL);
    f.result.n = 0;
    CHECK_INT(ygst_envelope_progress(&out, TOKEN, "a", "u", "r", "d", f.result, 0), -1);
    f.result.p = NULL;
    CHECK_INT(ygst_envelope_progress(&out, TOKEN, "a", "u", "r", "d", f.result, 0), -1);
    ygst_buf_free(&out);
    ygst_buf_free(&keys);
    ygst_buf_free(&s);
    ygst_buf_free(&want);
}

/* ------------------------------------------------------------------ words dedupe */

static void test_words_dedupe(void) {
    ygst_words_dedupe d;
    ygst_words_dedupe_init(&d);
    CHECK_INT(ygst_words_dedupe_skip(&d, 2, 2), 0); /* units 0, 1 */
    CHECK_INT(d.delivered, 2);
    CHECK_INT(ygst_words_dedupe_skip(&d, 5, 0), 0); /* no words: nothing counted */
    CHECK_INT(d.delivered, 2);
    CHECK_INT(ygst_words_dedupe_skip(&d, 5, 3), 0); /* units 2, 3, 4 */
    CHECK_INT(d.delivered, 5);
    /* a reconnect: the platform restarts from zero while the audio is replayed */
    CHECK_INT(ygst_words_dedupe_skip(&d, 2, 2), 2); /* units 0, 1 again: nothing */
    CHECK_INT(ygst_words_dedupe_skip(&d, 4, 2), 2);
    CHECK_INT(d.delivered, 5);
    CHECK_INT(ygst_words_dedupe_skip(&d, 7, 3), 1); /* units 4, 5, 6: 4 was delivered */
    CHECK_INT(d.delivered, 7);
    CHECK_INT(ygst_words_dedupe_skip(&d, 9, 2), 0);
    CHECK_INT(ygst_words_dedupe_skip(&d, 15, 2), 0); /* units 13, 14 after a gap */
    CHECK_INT(d.delivered, 15);
    CHECK_INT(ygst_words_dedupe_skip(&d, -1, 2), 0); /* position unknown: the next units */
    CHECK_INT(d.delivered, 17);
    ygst_words_dedupe_init(&d); /* a new evaluation */
    CHECK_INT(ygst_words_dedupe_skip(&d, 1, 3), 2); /* more items than read units: the last one is unit 0 */
    CHECK_INT(d.delivered, 1);
}

/* ------------------------------------------------------------------ error frames */

static void test_ws_classify_error(void) {
    ygst_classification c;
    ygst_ws_frame f;
    ygst_buf m;
    ygst_classification_init(&c);
    ygst_buf_init(&m);
    f = parse("{\"event\":\"error\",\"code\":50200,\"message\":\"mock upstream error\"}");
    CHECK_INT(ygst_ws_classify_error(f.root, &c), 0);
    CHECK_INT(c.biz_code, 50200);
    CHECK_INT(c.retryable, 1);
    CHECK_INT(c.err_id, 20009);
    CHECK_INT(c.http_status, 0);
    CHECK_INT(c.local_code, 0);
    ygst_error_message(&c, c.err_id, 3, &m);
    CHECK_STR(ygst_buf_cstr(&m), "网络或服务端临时故障，可重评，autoRetry 默认重评此码: code=50200 mock upstream error (attempts 3)");
    f = parse("{\"event\":\"error\",\"code\":40001,\"message\":\"refText 不能为空\"}");
    ygst_ws_classify_error(f.root, &c);
    CHECK_INT(c.retryable, 0);
    CHECK_INT(c.err_id, 40001);
    ygst_buf_reset(&m);
    ygst_error_message(&c, c.err_id, 1, &m);
    CHECK_STR(ygst_buf_cstr(&m), "refText 不能为空");
    f = parse("{\"event\":\"error\",\"code\":\"40903\",\"msg\":\"key reused\"}");
    ygst_ws_classify_error(f.root, &c);
    CHECK_INT(c.err_id, 40903);
    CHECK_STR(ygst_buf_cstr(&c.message), "key reused");
    /* no business code: the platform failed while evaluating, replayed like a 5xx */
    f = parse("{\"event\":\"error\",\"message\":\"engine failed\"}");
    ygst_ws_classify_error(f.root, &c);
    CHECK_INT(c.biz_code, 0);
    CHECK_INT(c.retryable, 1);
    CHECK_INT(c.err_id, 20009);
    ygst_buf_reset(&m);
    ygst_error_message(&c, 20009, 3, &m);
    CHECK_STR(ygst_buf_cstr(&m), "网络或服务端临时故障，可重评，autoRetry 默认重评此码: engine failed (attempts 3)");
    f = parse("{\"event\":\"error\"}");
    ygst_ws_classify_error(f.root, &c);
    CHECK_STR(ygst_buf_cstr(&c.message), "error frame without a message");
    ygst_buf_free(&m);
    ygst_classification_free(&c);
}

/* ------------------------------------------------------------------ round controller */

static const char *CONNECTED = "{\"event\":\"connected\",\"coreType\":\"sent.eval.cn\"}";
static const char *STARTED = "{\"event\":\"started\",\"coreType\":\"sent.eval.cn\"}";
static const char *FINAL = "{\"recordId\":\"eval_rt1\",\"eof\":1,\"result\":{\"overall\":88,\"sentences\":[{\"details\":"
                           "[{\"char\":\"我\",\"scores\":{\"overall\":90,\"pronunciation\":91}}]}]}}";

static ygst_rt_actions text(ygst_rt *rt, const char *s, int64_t now) {
    return ygst_rt_on_text(rt, s, strlen(s), now);
}

static int nothing(ygst_rt_actions a) {
    return !a.close && !a.connect && !a.send_params && a.audio_to == a.audio_from && !a.send_end && !a.send_ping &&
           !a.progress && !a.progress_held && !a.retry && !a.final && !a.fail && !a.fallback;
}

static void rt_new(ygst_rt *rt) {
    ygst_rt_init(rt);
    rt->seed = 42;
    rt->connect_timeout_ms = 1000;
    rt->attempt_timeout_ms = 2000;
    rt->ping_interval_ms = 300;
    rt->pong_timeout_ms = 600;
}

/* Brings a new round to STREAMING at t=0 with `audio` bytes captured before "started". */
static void rt_streaming(ygst_rt *rt, size_t audio) {
    ygst_rt_actions a;
    rt_new(rt);
    a = ygst_rt_start(rt, 0);
    CHECK(a.connect && !a.send_params);
    CHECK_INT(rt->state, YGST_RT_CONNECTING);
    a = ygst_rt_on_audio(rt, audio, 0);
    CHECK(nothing(a)); /* nothing goes out before "started" */
    a = text(rt, CONNECTED, 0);
    CHECK(a.send_params && !a.connect);
    CHECK_INT(rt->state, YGST_RT_STARTING);
    a = text(rt, STARTED, 0);
    CHECK_INT(rt->state, YGST_RT_STREAMING);
    CHECK_INT(a.audio_from, 0);
    CHECK_INT(a.audio_to, audio);
    CHECK_INT(a.send_end, 0);
}

static void test_rt_happy_path(void) {
    ygst_rt rt;
    ygst_rt_actions a;
    ygst_buf env;
    char dt[32];
    rt_streaming(&rt, 6400);
    CHECK_INT(rt.max_frame_bytes, YGST_WS_MAX_FRAME_BYTES);
    CHECK(ygst_rt_active(&rt));
    a = ygst_rt_on_audio(&rt, 9600, 10);
    CHECK_INT(a.audio_from, 6400);
    CHECK_INT(a.audio_to, 9600);
    a = ygst_rt_on_audio(&rt, 9600, 10);
    CHECK(nothing(a));
    a = text(&rt, "{\"eof\":0,\"result\":{\"bytes\":16000}}", 20);
    CHECK(nothing(a)); /* bytes only: not delivered */
    a = text(&rt, progress_frame(16000, 0, 0, 0), 25);
    CHECK(nothing(a)); /* empty words: not delivered */
    a = text(&rt, progress_frame(16000, 0, 1, 1), 30);
    CHECK(a.progress && !a.progress_held);
    CHECK_INT(a.words_skip, 0);
    CHECK_INT(rt.frame.read_units, 1);
    a = text(&rt, "{\"event\":\"pong\",\"ts\":1}", 30);
    CHECK(nothing(a));
    a = text(&rt, "not json", 30);
    CHECK(nothing(a));
    a = ygst_rt_on_stop(&rt, 12800, 40);
    CHECK_INT(a.audio_from, 9600);
    CHECK_INT(a.audio_to, 12800);
    CHECK(a.send_end && !a.close);
    CHECK_INT(rt.state, YGST_RT_ENDING);
    CHECK_INT(rt.end_sent, 1);
    CHECK_INT(rt.deadline_ms, 40 + 2000);
    a = ygst_rt_on_audio(&rt, 20000, 41);
    CHECK(nothing(a)); /* the round audio is complete */
    a = ygst_rt_on_stop(&rt, 20000, 41);
    CHECK(nothing(a));
    /* progress still arrives after end, until the final frame */
    a = text(&rt, progress_frame(12800, 1, 3, 2), 50);
    CHECK(a.progress);
    CHECK_INT(a.words_skip, 0);
    CHECK_INT(rt.dedupe.delivered, 3);
    a = text(&rt, FINAL, 60);
    CHECK(a.final && a.close && !a.fail);
    CHECK_INT(rt.state, YGST_RT_DONE);
    CHECK(!ygst_rt_active(&rt));
    CHECK_INT(rt.cls.success, 1);
    CHECK_STR(ygst_buf_cstr(&rt.cls.record_id), "eval_rt1");
    CHECK_INT(rt.call.total_attempts, 1);
    CHECK_INT(rt.connections, 1);
    /* the final envelope comes from the same classification and code as REST */
    ygst_buf_init(&env);
    ygst_format_dt(0, 0, dt);
    CHECK_INT(ygst_envelope_result(&env, TOKEN, "\"eval_rt1\"", "ak", "u", "r", dt, rt.cls.result, NULL, NULL), 0);
    CHECK(strstr(ygst_buf_cstr(&env), "\"scores\":{\"overall\":90,\"pronunciation\":91},\"overall\":90,"
                                      "\"pronunciation\":91}") != NULL);
    ygst_buf_free(&env);
    /* nothing after the end */
    CHECK(nothing(text(&rt, FINAL, 70)));
    CHECK(nothing(ygst_rt_on_failure(&rt, YGST_LOCAL_NETWORK, 0, "gone", 70)));
    CHECK(nothing(ygst_rt_on_tick(&rt, 99999)));
    ygst_rt_free(&rt);
}

static void test_rt_stop_before_started(void) {
    ygst_rt rt;
    ygst_rt_actions a;
    rt_new(&rt);
    ygst_rt_start(&rt, 0);
    ygst_rt_on_audio(&rt, 1000, 1);
    a = ygst_rt_on_stop(&rt, 70000, 2);
    CHECK(nothing(a)); /* end waits for "started" */
    CHECK_INT(rt.end_requested, 1);
    CHECK_INT(rt.end_sent, 0);
    a = text(&rt, CONNECTED, 3);
    CHECK(a.send_params);
    a = text(&rt, STARTED, 4);
    CHECK_INT(a.audio_from, 0);
    CHECK_INT(a.audio_to, 70000);
    CHECK(a.send_end);
    CHECK_INT(rt.state, YGST_RT_ENDING);
    /* frames out of order are ignored */
    CHECK(nothing(text(&rt, CONNECTED, 5)));
    CHECK(nothing(text(&rt, STARTED, 5)));
    ygst_rt_free(&rt);
}

static void test_rt_fallback_before_end(void) {
    ygst_rt rt;
    ygst_rt_actions a;
    /* transport failure while streaming: close, REST at stop */
    rt_streaming(&rt, 3200);
    a = ygst_rt_on_failure(&rt, YGST_LOCAL_NETWORK, 0, "network error: connection reset", 100);
    CHECK(a.close && !a.fallback && !a.retry && !a.fail);
    CHECK_INT(rt.state, YGST_RT_FALLBACK);
    CHECK_STR(ygst_buf_cstr(&rt.failure), "network error: connection reset");
    CHECK(!ygst_rt_active(&rt));
    CHECK(nothing(ygst_rt_on_audio(&rt, 6400, 110)));
    CHECK(nothing(text(&rt, progress_frame(16000, 0, 1, 1), 120)));
    a = ygst_rt_on_stop(&rt, 6400, 200);
    CHECK(a.fallback && !a.send_end);
    ygst_rt_free(&rt);
    /* failure while waiting for "started" after stop: REST right away */
    rt_new(&rt);
    ygst_rt_start(&rt, 0);
    ygst_rt_on_stop(&rt, 32000, 1);
    a = ygst_rt_on_failure(&rt, 0, 503, "WebSocket handshake refused", 2);
    CHECK(a.close && a.fallback);
    CHECK_INT(rt.state, YGST_RT_FALLBACK);
    ygst_rt_free(&rt);
    /* error frame before end */
    rt_streaming(&rt, 3200);
    a = text(&rt, "{\"event\":\"error\",\"message\":\"audio exceeds 10MB; send parameters to retry\"}", 5);
    CHECK(a.close && !a.fallback);
    CHECK_STR(ygst_buf_cstr(&rt.failure), "audio exceeds 10MB; send parameters to retry");
    ygst_rt_free(&rt);
    /* a result before end breaks the protocol */
    rt_streaming(&rt, 3200);
    a = text(&rt, FINAL, 5);
    CHECK(a.close && !a.final);
    CHECK_INT(rt.state, YGST_RT_FALLBACK);
    ygst_rt_free(&rt);
    /* connect timeout: no "started" within connect_timeout_ms */
    rt_new(&rt);
    ygst_rt_start(&rt, 0);
    text(&rt, CONNECTED, 10);
    CHECK(nothing(ygst_rt_on_tick(&rt, 999)));
    a = ygst_rt_on_tick(&rt, 1000);
    CHECK(a.close);
    CHECK_INT(rt.state, YGST_RT_FALLBACK);
    CHECK_STR(ygst_buf_cstr(&rt.failure), "timeout: not started within 1000 ms");
    ygst_rt_free(&rt);
    /* stop on a round that never started */
    rt_new(&rt);
    a = ygst_rt_on_stop(&rt, 100, 0);
    CHECK(a.fallback);
    ygst_rt_free(&rt);
}

static void test_rt_heartbeat(void) {
    ygst_rt rt;
    ygst_rt_actions a;
    rt_streaming(&rt, 3200);
    CHECK_INT(rt.next_ping_ms, 300);
    CHECK(nothing(ygst_rt_on_tick(&rt, 299)));
    a = ygst_rt_on_tick(&rt, 300);
    CHECK(a.send_ping);
    CHECK_INT(rt.pong_deadline_ms, 900);
    CHECK(nothing(ygst_rt_on_tick(&rt, 400))); /* one ping outstanding at a time */
    ygst_rt_on_pong(&rt, 450);
    CHECK_INT(rt.pong_deadline_ms, 0);
    CHECK_INT(rt.next_ping_ms, 750);
    a = ygst_rt_on_tick(&rt, 750);
    CHECK(a.send_ping);
    a = ygst_rt_on_tick(&rt, 1350);
    CHECK(a.close && !a.send_ping);
    CHECK_INT(rt.state, YGST_RT_FALLBACK);
    CHECK_STR(ygst_buf_cstr(&rt.failure), "timeout: no pong within 600 ms");
    ygst_rt_free(&rt);
    /* no heartbeat while the platform evaluates, a late pong changes nothing */
    rt_streaming(&rt, 3200);
    ygst_rt_on_tick(&rt, 300);
    ygst_rt_on_stop(&rt, 3200, 310);
    CHECK_INT(rt.pong_deadline_ms, 0);
    CHECK_INT(rt.next_ping_ms, 0);
    CHECK(nothing(ygst_rt_on_pong(&rt, 320)));
    CHECK(nothing(ygst_rt_on_tick(&rt, 2000)));
    ygst_rt_free(&rt);
    /* ping_interval_ms 0 turns the heartbeat off */
    rt_new(&rt);
    rt.ping_interval_ms = 0;
    ygst_rt_start(&rt, 0);
    text(&rt, CONNECTED, 0);
    text(&rt, STARTED, 0);
    CHECK(nothing(ygst_rt_on_tick(&rt, 100000)));
    ygst_rt_free(&rt);
}

/* Ends a streaming round. */
static void rt_end(ygst_rt *rt, size_t audio, int64_t now) {
    ygst_rt_actions a = ygst_rt_on_stop(rt, audio, now);
    CHECK(a.send_end);
}

/* After a retry action: wait for the back-off, reconnect, replay. Returns the replay actions. */
static ygst_rt_actions rt_reconnect(ygst_rt *rt, ygst_rt_actions failed, int64_t *now, size_t audio) {
    ygst_rt_actions a;
    CHECK(failed.retry && failed.close && !failed.fail);
    CHECK_INT(rt->state, YGST_RT_BACKOFF);
    CHECK(ygst_rt_active(rt));
    CHECK(nothing(ygst_rt_on_tick(rt, *now + failed.delay_ms - 1)));
    *now += failed.delay_ms;
    a = ygst_rt_on_tick(rt, *now);
    CHECK(a.connect);
    CHECK_INT(rt->state, YGST_RT_CONNECTING);
    a = text(rt, CONNECTED, *now);
    CHECK(a.send_params);
    a = text(rt, STARTED, *now);
    /* the whole round again, then end */
    CHECK_INT(a.audio_from, 0);
    CHECK_INT(a.audio_to, audio);
    CHECK(a.send_end);
    return a;
}

static void test_rt_reconnect_after_end(void) {
    ygst_rt rt;
    ygst_rt_actions a;
    int64_t now;
    rt_streaming(&rt, 48000);
    CHECK(text(&rt, progress_frame(16000, 0, 1, 1), 1).progress);
    CHECK(text(&rt, progress_frame(32000, 1, 2, 2), 2).progress);
    rt_end(&rt, 48000, 10);
    a = ygst_rt_on_failure(&rt, YGST_LOCAL_NETWORK, 0, "network error: connection reset", 20);
    CHECK(a.delay_ms >= 140 && a.delay_ms <= 260); /* 200 ms +- 30 % */
    now = 20;
    rt_reconnect(&rt, a, &now, 48000);
    CHECK_INT(rt.connections, 2);
    /* the platform restarts progress from zero while the audio is replayed */
    a = text(&rt, progress_frame(16000, 0, 1, 1), now);
    CHECK(a.progress_held && !a.progress);
    a = text(&rt, progress_frame(32000, 1, 1, 1), now); /* no new words */
    CHECK(nothing(a));
    a = text(&rt, progress_frame(32000, 1, 2, 2), now);
    CHECK(a.progress_held);
    a = text(&rt, progress_frame(48000, 2, 3, 3), now);
    CHECK(a.progress);
    CHECK_INT(a.words_skip, 0);
    CHECK_INT(rt.dedupe.delivered, 3);
    a = text(&rt, "{\"recordId\":\"eval_rt1\",\"eof\":1,\"result\":{\"overall\":88},\"replayed\":true}", now);
    CHECK(a.final);
    CHECK_INT(rt.frame.replayed, 1);
    CHECK_INT(rt.cls.success, 1);
    CHECK_INT(rt.call.total_attempts, 2);
    ygst_rt_free(&rt);
    /* a replayed frame that covers delivered and new units is trimmed */
    rt_streaming(&rt, 48000);
    CHECK(text(&rt, progress_frame(16000, 0, 1, 1), 1).progress);
    rt_end(&rt, 48000, 10);
    a = ygst_rt_on_failure(&rt, YGST_LOCAL_NETWORK, 0, "network error: connection reset", 20);
    now = 20;
    rt_reconnect(&rt, a, &now, 48000);
    a = text(&rt, progress_frame(48000, 0, 3, 2), now);
    CHECK(a.progress);
    CHECK_INT(a.words_skip, 1);
    CHECK_INT(rt.dedupe.delivered, 3);
    ygst_rt_free(&rt);
}

static void test_rt_reconnect_exhausted(void) {
    ygst_rt rt;
    ygst_rt_actions a;
    ygst_buf m;
    int64_t now = 0;
    int i;
    ygst_buf_init(&m);
    rt_streaming(&rt, 32000);
    rt_end(&rt, 32000, 0);
    for (i = 0; i < 2; i++) {
        a = ygst_rt_on_failure(&rt, YGST_LOCAL_NETWORK, 0, "network error: connection reset", now);
        rt_reconnect(&rt, a, &now, 32000);
    }
    a = ygst_rt_on_failure(&rt, YGST_LOCAL_NETWORK, 0, "network error: connection reset", now);
    CHECK(a.fail && a.close && !a.retry);
    CHECK_INT(a.err_id, 20009);
    CHECK_INT(rt.state, YGST_RT_DONE);
    CHECK_INT(rt.connections, 3);
    ygst_rt_error_message(&rt, a.err_id, &m);
    CHECK_STR(ygst_buf_cstr(&m), "网络或服务端临时故障，可重评，autoRetry 默认重评此码: NETWORK_ERROR network error: "
                                 "connection reset (attempts 3)");
    ygst_buf_free(&m);
    ygst_rt_free(&rt);
    /* YuguCompat.maxRetries 0: no reconnect */
    rt_new(&rt);
    rt.policy.max_retries = 0;
    ygst_rt_start(&rt, 0);
    text(&rt, CONNECTED, 0);
    text(&rt, STARTED, 0);
    ygst_rt_on_stop(&rt, 100, 0);
    a = ygst_rt_on_failure(&rt, YGST_LOCAL_TIMEOUT, 0, "timeout: x", 0);
    CHECK(a.fail);
    CHECK_INT(a.err_id, 20009);
    ygst_rt_free(&rt);
}

static void test_rt_error_frames_after_end(void) {
    ygst_rt rt;
    ygst_rt_actions a;
    ygst_buf m;
    int64_t now = 0;
    ygst_buf_init(&m);
    /* retryable code: reconnect */
    rt_streaming(&rt, 32000);
    rt_end(&rt, 32000, 0);
    a = text(&rt, "{\"event\":\"error\",\"code\":50200,\"message\":\"mock upstream error\"}", 5);
    now = 5;
    rt_reconnect(&rt, a, &now, 32000);
    a = text(&rt, FINAL, now);
    CHECK(a.final);
    ygst_rt_free(&rt);
    /* not retryable: the platform code as errId, the platform message, one attempt */
    rt_streaming(&rt, 32000);
    rt_end(&rt, 32000, 0);
    a = text(&rt, "{\"event\":\"error\",\"code\":40903,\"message\":\"该 Idempotency-Key 已用于另一个不同的请求\"}", 5);
    CHECK(a.fail && a.close);
    CHECK_INT(a.err_id, 40903);
    ygst_rt_error_message(&rt, a.err_id, &m);
    CHECK_STR(ygst_buf_cstr(&m), "该 Idempotency-Key 已用于另一个不同的请求");
    CHECK_INT(rt.call.total_attempts, 1);
    ygst_rt_free(&rt);
    /* a final frame without result: 90005 */
    rt_streaming(&rt, 32000);
    rt_end(&rt, 32000, 0);
    a = text(&rt, "{\"recordId\":\"eval_x\",\"eof\":1}", 5);
    CHECK(a.fail && !a.final);
    CHECK_INT(a.err_id, 90005);
    ygst_rt_free(&rt);
    ygst_buf_free(&m);
}

static void test_rt_timeouts_and_handshake_after_end(void) {
    ygst_rt rt;
    ygst_rt_actions a;
    ygst_buf m;
    int64_t now = 0;
    ygst_buf_init(&m);
    /* no result within attempt_timeout_ms: reconnect, the replayed answer completes */
    rt_streaming(&rt, 32000);
    rt_end(&rt, 32000, 100);
    CHECK(nothing(ygst_rt_on_tick(&rt, 2099)));
    a = ygst_rt_on_tick(&rt, 2100);
    CHECK_STR(ygst_buf_cstr(&rt.cls.message), "timeout: no result within 2000 ms");
    CHECK_INT(rt.cls.local_code, YGST_LOCAL_RESULT_TIMEOUT);
    CHECK_INT(rt.cls.retryable, 1);
    now = 2100;
    rt_reconnect(&rt, a, &now, 32000);
    CHECK(text(&rt, FINAL, now).final);
    ygst_rt_free(&rt);
    /* a reconnect whose handshake is refused with 503 counts as an attempt and retries; 403 does not */
    rt_streaming(&rt, 32000);
    rt_end(&rt, 32000, 0);
    a = ygst_rt_on_failure(&rt, YGST_LOCAL_NETWORK, 0, "network error: lost", 0);
    now = 0;
    CHECK(a.retry);
    now += a.delay_ms;
    CHECK(ygst_rt_on_tick(&rt, now).connect);
    a = ygst_rt_on_failure(&rt, 0, 503, "WebSocket handshake refused", now);
    CHECK(a.retry);
    now += a.delay_ms;
    CHECK(ygst_rt_on_tick(&rt, now).connect);
    a = ygst_rt_on_failure(&rt, 0, 403, "WebSocket handshake refused", now);
    CHECK(a.fail);
    CHECK_INT(a.err_id, 40300);
    ygst_rt_error_message(&rt, a.err_id, &m);
    CHECK_STR(ygst_buf_cstr(&m), "WebSocket handshake refused");
    ygst_rt_free(&rt);
    /* a reconnect that never starts */
    rt_streaming(&rt, 32000);
    rt_end(&rt, 32000, 0);
    a = ygst_rt_on_failure(&rt, YGST_LOCAL_NETWORK, 0, "network error: lost", 0);
    now = a.delay_ms;
    CHECK(ygst_rt_on_tick(&rt, now).connect);
    a = ygst_rt_on_tick(&rt, now + 1000);
    CHECK(a.retry);
    CHECK_STR(ygst_buf_cstr(&rt.cls.message), "timeout: not started within 1000 ms");
    ygst_rt_free(&rt);
    /* TLS failure after end is not retried: 90011 like REST */
    rt_streaming(&rt, 32000);
    rt_end(&rt, 32000, 0);
    a = ygst_rt_on_failure(&rt, YGST_LOCAL_TLS, 0, "TLS certificate check failed: x", 1);
    CHECK(a.fail);
    CHECK_INT(a.err_id, 90011);
    ygst_rt_free(&rt);
    ygst_buf_free(&m);
}

static void test_rt_auto_retry_and_total_timeout(void) {
    ygst_rt rt;
    ygst_rt_actions a;
    int64_t now = 0;
    int i;
    /* autoRetry with the default errIds: after the retries a new submission over a new connection */
    rt_new(&rt);
    rt.policy.max_retries = 0;
    rt.policy.auto_retry = 1;
    ygst_rt_start(&rt, 0);
    text(&rt, CONNECTED, 0);
    text(&rt, STARTED, 0);
    ygst_rt_on_stop(&rt, 100, 0);
    for (i = 0; i < 2; i++) {
        a = text(&rt, "{\"event\":\"error\",\"code\":50000,\"message\":\"busy\"}", now);
        rt_reconnect(&rt, a, &now, 100);
    }
    a = text(&rt, "{\"event\":\"error\",\"code\":50000,\"message\":\"busy\"}", now);
    CHECK(a.fail);
    CHECK_INT(a.err_id, 20009);
    CHECK_INT(rt.call.submission, 2);
    CHECK_INT(rt.connections, 3);
    ygst_rt_free(&rt);
    /* total timeout: no reconnect that would end after the deadline */
    rt_new(&rt);
    rt.policy.total_timeout_ms = 100;
    ygst_rt_start(&rt, 0);
    text(&rt, CONNECTED, 0);
    text(&rt, STARTED, 0);
    ygst_rt_on_stop(&rt, 100, 0);
    a = ygst_rt_on_failure(&rt, YGST_LOCAL_NETWORK, 0, "network error: lost", 50);
    CHECK(a.fail && !a.retry);
    ygst_rt_free(&rt);
}

static void test_rt_cancel_and_idle(void) {
    ygst_rt rt;
    rt_new(&rt);
    CHECK(!ygst_rt_active(&rt));
    CHECK(nothing(text(&rt, CONNECTED, 0))); /* not started */
    CHECK(nothing(ygst_rt_on_failure(&rt, YGST_LOCAL_NETWORK, 0, "x", 0)));
    CHECK(nothing(ygst_rt_on_tick(&rt, 0)));
    CHECK(nothing(ygst_rt_on_pong(&rt, 0)));
    CHECK(ygst_rt_start(&rt, 0).connect);
    CHECK(nothing(ygst_rt_start(&rt, 0))); /* once */
    ygst_rt_cancel(&rt);
    CHECK_INT(rt.state, YGST_RT_DONE);
    CHECK(nothing(text(&rt, CONNECTED, 1)));
    CHECK(nothing(ygst_rt_on_stop(&rt, 10, 1)));
    CHECK(nothing(ygst_rt_on_tick(&rt, 100000)));
    ygst_rt_free(&rt);
    rt_new(&rt);
    rt.max_frame_bytes = 0;
    ygst_rt_start(&rt, 0);
    CHECK_INT(rt.max_frame_bytes, YGST_WS_MAX_FRAME_BYTES);
    ygst_rt_free(&rt);
}

/* The recorded customer round through the controller: every unit is delivered exactly once, in
 * order, with the words the platform sent, and the final frame classifies like the REST body. */
static void test_rt_recorded_round(void) {
    size_t len = 0;
    char *fixture = ygt_read_spec("fixtures/platform/ws_compat_sent_eval_cn_progress_frames.json", &len);
    ygst_span root, el, frame, v, item;
    size_t pos = 0;
    ygst_rt rt;
    ygst_rt_actions a;
    int frames = 0, delivered = 0, finals = 0, i = 0, unit = 0, j;
    ygst_buf w;
    CHECK(fixture != NULL);
    if (!fixture) return;
    ygst_buf_init(&w);
    CHECK_INT(ygst_json_parse(fixture, len, &root), 0);
    while (ygst_json_array_next(root, &pos, &el) == 1) frames++;
    rt_new(&rt);
    ygst_rt_start(&rt, 0);
    ygst_rt_on_audio(&rt, 177920, 0);
    pos = 0;
    while (ygst_json_array_next(root, &pos, &el) == 1) {
        int64_t at = int_member(el, "at");
        ygst_json_member(el, "frame", &frame);
        if (i == frames - 2) ygst_rt_on_stop(&rt, 177920, at); /* end went out before the last progress */
        a = ygst_rt_on_text(&rt, frame.p, frame.n, at);
        CHECK(!a.progress_held);
        if (a.progress) {
            delivered++;
            CHECK_INT(a.words_skip, 0);
            for (j = 0; nth(rt.frame.words, j, &item); j++) {
                ygst_buf_reset(&w);
                if (ygst_json_member(item, "word", &v) == 1) ygst_json_string(v, &w);
                CHECK_STR(ygst_buf_cstr(&w), ZH_UNITS[unit < 25 ? unit : 24]);
                unit++;
            }
        }
        if (a.final) {
            finals++;
            CHECK_INT(ygst_json_member(rt.cls.result, "overall", &v), 1);
            CHECK(rt.cls.has_record_id);
        }
        i++;
    }
    CHECK(delivered > 3);
    CHECK_INT(unit, 25);
    CHECK_INT(rt.dedupe.delivered, 25);
    CHECK_INT(finals, 1);
    ygst_buf_free(&w);
    ygst_rt_free(&rt);
    free(fixture);
}

void suite_realtime(void) {
    RUN("realtime", test_url_encode);
    RUN("realtime", test_ws_base_url);
    RUN("realtime", test_ws_handshake_vector);
    RUN("realtime", test_ws_handshake_extra_and_guards);
    RUN("realtime", test_ws_param_frame);
    RUN("realtime", test_frames_recorded_progress);
    RUN("realtime", test_frames_recorded_2_0);
    RUN("realtime", test_frames_cases);
    RUN("realtime", test_envelope_progress);
    RUN("realtime", test_words_dedupe);
    RUN("realtime", test_ws_classify_error);
    RUN("realtime", test_rt_happy_path);
    RUN("realtime", test_rt_stop_before_started);
    RUN("realtime", test_rt_fallback_before_end);
    RUN("realtime", test_rt_heartbeat);
    RUN("realtime", test_rt_reconnect_after_end);
    RUN("realtime", test_rt_reconnect_exhausted);
    RUN("realtime", test_rt_error_frames_after_end);
    RUN("realtime", test_rt_timeouts_and_handshake_after_end);
    RUN("realtime", test_rt_auto_retry_and_total_timeout);
    RUN("realtime", test_rt_cancel_and_idle);
    RUN("realtime", test_rt_recorded_round);
}
