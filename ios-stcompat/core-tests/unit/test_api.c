/*
 * Copyright 2026 优谷雅言 open.shengzhiai.com
 * SPDX-License-Identifier: Apache-2.0
 *
 * TtsClient and ReportClient (2.2.0): the TTS body and its signed parameters against signatures
 * computed independently (Python hmac), the absolute audio URL, the report path, the classification
 * of {code, message, data} answers under the retry controller, the answers as the platform and the
 * mock send them, the wait for the AI final draft and the log line.
 */
#include "ygt.h"

#define SECRET "mock-secret-key"

/* ------------------------------------------------------------------ TTS request */

static void full_request(ygst_tts_request *r) {
    ygst_tts_request_init(r);
    r->text = "你好，世界";
    r->language = "zh-CN";
    r->voice = "xiaoyan";
    r->format = "mp3";
    r->has_speed = 1;
    r->speed = 50;
    r->has_pitch = 1;
    r->pitch = 0; /* 0 is a value: sent and signed */
    r->has_volume = 1;
    r->volume = 100;
    r->has_sample_rate = 1;
    r->sample_rate = 16000;
    r->emotion = "warm";
    r->style = "温柔地朗读";
    r->pinyin = "ni3 hao3 shi4 jie4";
    r->genre = "prose";
}

static void test_tts_body_every_field(void) {
    ygst_tts_request r;
    ygst_buf json, payload;
    ygst_fields sign;
    ygst_span root, v;
    char sig[45];
    ygst_buf_init(&json);
    ygst_buf_init(&payload);
    ygst_fields_init(&sign);
    full_request(&r);
    CHECK_INT(ygst_tts_body(&r, &json, &sign), 0);
    /* the members in the order of the request, integers as JSON numbers */
    CHECK_STR(ygst_buf_cstr(&json),
              "{\"text\":\"你好，世界\",\"language\":\"zh-CN\",\"voice\":\"xiaoyan\",\"format\":\"mp3\",\"speed\":50,"
              "\"pitch\":0,\"volume\":100,\"sampleRate\":16000,\"emotion\":\"warm\",\"style\":\"温柔地朗读\","
              "\"pinyin\":\"ni3 hao3 shi4 jie4\",\"genre\":\"prose\"}");
    CHECK_INT(ygst_json_parse((const char *)json.data, json.len, &root), 0);
    CHECK_INT(ygst_json_member(root, "pitch", &v), 1);
    CHECK_INT(ygst_json_type(v), 'n');
    /* CONTRACT 2.3: the top-level scalar members with their literal text, sorted by name */
    CHECK_INT((long long)sign.count, 12);
    CHECK_STR(ygst_fields_get(&sign, "speed"), "50");
    CHECK_STR(ygst_fields_get(&sign, "pitch"), "0");
    CHECK_STR(ygst_fields_get(&sign, "text"), "你好，世界");
    CHECK_INT(ygst_sign_payload(&sign, &payload), 0);
    CHECK_STR(ygst_buf_cstr(&payload), "emotion=warm&format=mp3&genre=prose&language=zh-CN&pinyin=ni3 hao3 shi4 jie4&"
                                       "pitch=0&sampleRate=16000&speed=50&style=温柔地朗读&text=你好，世界&"
                                       "voice=xiaoyan&volume=100");
    /* computed with Python hmac, hashlib.sha256 and base64 over the same payload */
    CHECK_INT(ygst_sign(&sign, SECRET, sig), 0);
    CHECK_STR(sig, "aJjHXPSTFu/KY7e9rRyIA9Qt/Xgklouafz/QCRr3Dd8=");
    ygst_buf_free(&json);
    ygst_buf_free(&payload);
    ygst_fields_free(&sign);
}

static void test_tts_body_only_what_is_set(void) {
    ygst_tts_request r;
    ygst_buf json;
    ygst_fields sign;
    char sig[45];
    ygst_buf_init(&json);
    ygst_fields_init(&sign);
    ygst_tts_request_init(&r);
    r.text = "他说\"你好\"\\\n";
    r.language = "";   /* an empty string is not set */
    r.voice = NULL;
    r.has_speed = 0;
    r.speed = 70;      /* without has_speed: not sent */
    r.has_volume = 1;
    r.volume = -5;     /* sent as given, the platform validates the range */
    CHECK_INT(ygst_tts_body(&r, &json, &sign), 0);
    CHECK_STR(ygst_buf_cstr(&json), "{\"text\":\"他说\\\"你好\\\"\\\\\\n\",\"volume\":-5}");
    CHECK_INT((long long)sign.count, 2);
    CHECK_STR(ygst_fields_get(&sign, "text"), "他说\"你好\"\\\n"); /* the text itself, not its JSON escape */
    CHECK_STR(ygst_fields_get(&sign, "volume"), "-5");
    ygst_fields_free(&sign);
    ygst_fields_init(&sign);
    ygst_buf_reset(&json);
    r.has_volume = 0;
    CHECK_INT(ygst_tts_body(&r, &json, &sign), 0);
    CHECK_INT(ygst_sign(&sign, "s3cret", sig), 0);
    CHECK_STR(sig, "sJ5H828NNOUt5iXNapvtklKHdEx3NxAG60+fXaYbE7Y=");
    /* text is required: nothing is built */
    ygst_buf_reset(&json);
    r.text = "  \n ";
    CHECK_INT(ygst_tts_body(&r, &json, &sign), YGST_LOCAL_INVALID_ARGUMENT);
    r.text = NULL;
    CHECK_INT(ygst_tts_body(&r, &json, &sign), YGST_LOCAL_INVALID_ARGUMENT);
    CHECK_INT(json.len, 0);
    CHECK_INT(ygst_tts_body(NULL, &json, &sign), -1);
    CHECK_INT(ygst_tts_body(&r, NULL, &sign), -1);
    CHECK_INT(ygst_tts_body(&r, &json, NULL), -1);
    ygst_tts_request_init(NULL);
    /* the signed set of the report (CONTRACT 2.3), computed with Python hmac */
    ygst_fields_free(&sign);
    ygst_fields_init(&sign);
    CHECK_INT(ygst_sign(&sign, SECRET, sig), 0);
    CHECK_STR(sig, "IXjvKF9FS4uZuUgPyO3tPG69VW4pAZdL6V+MieFjMI4=");
    ygst_buf_free(&json);
    ygst_fields_free(&sign);
}

/* ------------------------------------------------------------------ URLs */

static void test_api_urls(void) {
    ygst_buf b;
    ygst_buf_init(&b);
    CHECK_INT(ygst_api_url("https://open.shengzhiai.com", YGST_TTS_PATH, &b), 0);
    CHECK_STR(ygst_buf_cstr(&b), "https://open.shengzhiai.com/api/v1/tts/generate");
    ygst_buf_reset(&b);
    CHECK_INT(ygst_api_url("http://127.0.0.1:8080/prefix//", "/api/v1/report/eval_x", &b), 0);
    CHECK_STR(ygst_buf_cstr(&b), "http://127.0.0.1:8080/prefix/api/v1/report/eval_x");
    CHECK_INT(ygst_api_url(NULL, "x", &b), -1);
    CHECK_INT(ygst_api_url("http://h", NULL, &b), -1);
    CHECK_INT(ygst_api_url("http://h", "x", NULL), -1);
    /* the recordId is one path segment: trimmed, percent-encoded */
    ygst_buf_reset(&b);
    CHECK_INT(ygst_report_path("eval_3fb45f4c8e71", &b), 0);
    CHECK_STR(ygst_buf_cstr(&b), "api/v1/report/eval_3fb45f4c8e71");
    ygst_buf_reset(&b);
    CHECK_INT(ygst_report_path("  eval_x y/z?#%  \n", &b), 0);
    CHECK_STR(ygst_buf_cstr(&b), "api/v1/report/eval_x%20y%2Fz%3F%23%25");
    ygst_buf_reset(&b);
    CHECK_INT(ygst_report_path("记录-1.a~b", &b), 0);
    CHECK_STR(ygst_buf_cstr(&b), "api/v1/report/%E8%AE%B0%E5%BD%95-1.a~b");
    ygst_buf_reset(&b);
    CHECK_INT(ygst_report_path(" \t ", &b), YGST_LOCAL_INVALID_ARGUMENT);
    CHECK_INT(ygst_report_path(NULL, &b), YGST_LOCAL_INVALID_ARGUMENT);
    CHECK_INT(b.len, 0);
    CHECK_INT(ygst_report_path("eval_x", NULL), -1);
    ygst_buf_free(&b);
}

static void check_absolute(const char *base, const char *url, const char *want) {
    ygst_buf b;
    ygst_buf_init(&b);
    CHECK_INT(ygst_tts_absolute_url(base, url, &b), 0);
    CHECK_STR(ygst_buf_cstr(&b), want);
    ygst_buf_free(&b);
}

static void test_tts_absolute_url(void) {
    const char *base = "https://open.shengzhiai.com";
    /* CONTRACT 4.1: /audio/... is served under /tts of the base */
    check_absolute(base, "/audio/2026/10/13/a.mp3", "https://open.shengzhiai.com/tts/audio/2026/10/13/a.mp3");
    check_absolute("http://127.0.0.1:8080/", "/audio/mock-3.mp3", "http://127.0.0.1:8080/tts/audio/mock-3.mp3");
    check_absolute("https://h/prefix", "/audio/x.wav", "https://h/prefix/tts/audio/x.wav");
    check_absolute(base, "https://cdn.example.com/a.mp3", "https://cdn.example.com/a.mp3");
    check_absolute(base, "HTTP://cdn.example.com/a.mp3", "HTTP://cdn.example.com/a.mp3");
    check_absolute(base, "/files/a.mp3", "https://open.shengzhiai.com/files/a.mp3");
    check_absolute(base, "tts/audio/a.mp3", "https://open.shengzhiai.com/tts/audio/a.mp3");
    check_absolute(base, "/audiox/a.mp3", "https://open.shengzhiai.com/audiox/a.mp3");
    check_absolute(base, "", "");
    check_absolute(base, NULL, "");
    check_absolute(NULL, "/audio/a.mp3", "/tts/audio/a.mp3");
    CHECK_INT(ygst_tts_absolute_url(base, "/audio/a", NULL), -1);
}

/* ------------------------------------------------------------------ answers */

static void classify(int status, int local, const char *body, ygst_classification *c) {
    ygst_outcome o;
    memset(&o, 0, sizeof o);
    o.http_status = status;
    o.local_code = local;
    o.body = body;
    o.body_len = body ? strlen(body) : 0;
    o.retry_after_ms = -1;
    ygst_classify_api(&o, c);
}

static int span_is(ygst_span s, const char *text) {
    return s.p && s.n == strlen(text) && memcmp(s.p, text, s.n) == 0;
}

static void test_classify_api(void) {
    ygst_classification c;
    ygst_buf m;
    const char *ok = "{\"code\":0,\"message\":\"success\",\"data\":{\"audioUrl\":\"/audio/x.mp3\",\"duration\":\"1.348\"},"
                     "\"timestamp\":1}";
    ygst_classification_init(&c);
    ygst_buf_init(&m);
    classify(200, 0, ok, &c);
    CHECK(c.success);
    CHECK(span_is(c.result, "{\"audioUrl\":\"/audio/x.mp3\",\"duration\":\"1.348\"}"));
    CHECK(span_is(c.root, ok));
    CHECK_INT(c.err_id, 0);
    CHECK_INT(c.has_record_id, 0);
    CHECK_INT(c.record_id.len, 0);
    /* no code, or a quoted 0 */
    classify(200, 0, "{\"data\":{}}", &c);
    CHECK(c.success && span_is(c.result, "{}"));
    classify(201, 0, "{\"code\":\"0\",\"data\":{\"a\":1}}", &c);
    CHECK(c.success && span_is(c.result, "{\"a\":1}"));
    /* a platform error with HTTP 200: its code, retried as spec/errors.json says */
    classify(200, 0, "{\"code\":40001,\"message\":\"text 不能为空\"}", &c);
    CHECK(!c.success);
    CHECK_INT(c.biz_code, 40001);
    CHECK_INT(c.retryable, 0);
    CHECK_INT(c.err_id, 40001);
    CHECK_STR(ygst_buf_cstr(&c.message), "text 不能为空");
    classify(200, 0, "{\"code\":50000,\"message\":\"busy\",\"data\":{}}", &c);
    CHECK(!c.success);
    CHECK_INT(c.retryable, 1);
    CHECK_INT(c.err_id, 20009);
    classify(200, 0, "{\"code\":\"42901\",\"msg\":\"m\"}", &c);
    CHECK_INT(c.biz_code, 42901);
    CHECK_INT(c.retryable, 1);
    classify(200, 0, "{\"code\":77777}", &c); /* not in the table: not retried */
    CHECK_INT(c.retryable, 0);
    CHECK_INT(c.err_id, 77777);
    CHECK_INT(c.message.len, 0);
    ygst_buf_reset(&m);
    ygst_error_message(&c, c.err_id, 1, &m);
    CHECK_STR(ygst_buf_cstr(&m), ""); /* neither a platform text nor a table entry */
    /* a 2xx answer without its data object: 90005 */
    classify(200, 0, "{\"code\":0}", &c);
    CHECK(!c.success);
    CHECK_INT(c.err_id, 90005);
    CHECK_INT(c.local_code, 90005);
    CHECK_STR(ygst_buf_cstr(&c.message), "invalid platform response: response has no data");
    classify(200, 0, "{\"code\":0,\"data\":null}", &c);
    CHECK_INT(c.err_id, 90005);
    classify(200, 0, "{\"code\":0,\"data\":[1]}", &c);
    CHECK_INT(c.err_id, 90005);
    classify(200, 0, "{\"recordId\":\"eval_1\",\"result\":{}}", &c); /* a compat answer is not a v1 one */
    CHECK(!c.success);
    CHECK_INT(c.err_id, 90005);
    CHECK_INT(c.has_record_id, 0);
    classify(200, 0, "<html>ok</html>", &c);
    CHECK_INT(c.err_id, 90005);
    CHECK_STR(ygst_buf_cstr(&c.message), "invalid platform response: not a JSON object");
    classify(200, 0, "[]", &c);
    CHECK_INT(c.err_id, 90005);
    classify(204, 0, NULL, &c);
    CHECK_INT(c.err_id, 90005);
    CHECK_INT(c.retryable, 0);
    /* error statuses exactly as the evaluations classify them */
    classify(400, 0, "{\"code\":40001,\"message\":\"评测记录不存在: nope\",\"timestamp\":1}", &c);
    CHECK(!c.success);
    CHECK_INT(c.err_id, 40001);
    CHECK_INT(c.retryable, 0);
    CHECK_INT(c.http_status, 400);
    CHECK_STR(ygst_buf_cstr(&c.message), "评测记录不存在: nope");
    classify(401, 0, "{\"code\":2003,\"message\":\"签名验证失败\"}", &c);
    CHECK_INT(c.err_id, 2003);
    classify(503, 0, "<html>503</html>", &c);
    CHECK_INT(c.retryable, 1);
    CHECK_INT(c.err_id, 20009);
    classify(404, 0, "<html>404</html>", &c);
    CHECK_INT(c.err_id, 40400);
    CHECK_STR(ygst_buf_cstr(&c.message), "HTTP 404");
    classify(0, YGST_LOCAL_NETWORK, NULL, &c);
    CHECK_INT(c.retryable, 1);
    CHECK_INT(c.err_id, 20009);
    classify(0, YGST_LOCAL_TLS, NULL, &c);
    CHECK_INT(c.retryable, 0);
    CHECK_INT(c.err_id, 90011);
    CHECK_INT(ygst_classify_api(NULL, &c), -1);
    ygst_buf_free(&m);
    ygst_classification_free(&c);
}

/* The call controller of the evaluations runs the v1 calls: 503, 503, 200 and 400 */
static void test_api_call_controller(void) {
    ygst_retry_policy p;
    ygst_call call;
    ygst_classification c;
    ygst_action a;
    ygst_buf m;
    ygst_retry_policy_default(&p);
    ygst_classification_init(&c);
    ygst_buf_init(&m);
    ygst_call_init(&call, &p, 7, 0);
    classify(503, 0, "{\"code\":50000,\"message\":\"system busy\"}", &c);
    a = ygst_call_next(&call, &c, -1, 10);
    CHECK_INT(a.kind, YGST_ACT_RETRY);
    classify(200, 0, "{\"code\":50200,\"message\":\"upstream\"}", &c);
    a = ygst_call_next(&call, &c, -1, 500);
    CHECK_INT(a.kind, YGST_ACT_RETRY);
    classify(200, 0, "{\"code\":0,\"data\":{\"recordId\":\"eval_1\"}}", &c);
    a = ygst_call_next(&call, &c, -1, 1500);
    CHECK_INT(a.kind, YGST_ACT_SUCCESS);
    CHECK_INT(call.total_attempts, 3);
    /* retries used up: 20009 with the last answer and the attempts */
    ygst_call_init(&call, &p, 7, 0);
    classify(503, 0, "{\"code\":50000,\"message\":\"system busy\"}", &c);
    ygst_call_next(&call, &c, -1, 10);
    ygst_call_next(&call, &c, -1, 300);
    a = ygst_call_next(&call, &c, -1, 900);
    CHECK_INT(a.kind, YGST_ACT_FAIL);
    CHECK_INT(a.err_id, 20009);
    ygst_error_message(&c, a.err_id, call.attempt, &m);
    CHECK_STR(ygst_buf_cstr(&m), "网络或服务端临时故障，可重评，autoRetry 默认重评此码: HTTP 503 code=50000 system busy "
                                 "(attempts 3)");
    /* not retried */
    ygst_call_init(&call, &p, 7, 0);
    classify(400, 0, "{\"code\":40001,\"message\":\"评测记录不存在: nope\"}", &c);
    a = ygst_call_next(&call, &c, -1, 10);
    CHECK_INT(a.kind, YGST_ACT_FAIL);
    CHECK_INT(a.err_id, 40001);
    CHECK_INT(call.total_attempts, 1);
    ygst_buf_free(&m);
    ygst_classification_free(&c);
}

static ygst_span span_of(const char *s) {
    ygst_span v;
    v.p = s;
    v.n = s ? strlen(s) : 0;
    return v;
}

static void test_tts_parse(void) {
    ygst_tts_answer a;
    ygst_tts_answer_init(&a);
    /* tools/mock-server and CONTRACT 4.1 */
    CHECK_INT(ygst_tts_parse(span_of("{\"audioUrl\":\"/audio/mock-3.mp3\",\"duration\":\"1.348\",\"format\":\"mp3\","
                                     "\"warnings\":[]}"),
                             &a),
              0);
    CHECK_STR(ygst_buf_cstr(&a.audio_url), "/audio/mock-3.mp3");
    CHECK_STR(ygst_buf_cstr(&a.format), "mp3");
    CHECK_INT(a.has_duration, 1);
    CHECK(a.duration > 1.3479 && a.duration < 1.3481);
    CHECK_INT(ygst_tts_parse(span_of("{\"audioUrl\":\"https://cdn/a\\/b.wav\",\"duration\":2.5}"), &a), 0);
    CHECK_STR(ygst_buf_cstr(&a.audio_url), "https://cdn/a/b.wav");
    CHECK_INT(a.format.len, 0);
    CHECK(a.has_duration && a.duration == 2.5);
    CHECK_INT(ygst_tts_parse(span_of("{\"duration\":\"abc\",\"audioUrl\":3}"), &a), 0);
    CHECK_INT(a.has_duration, 0);
    CHECK(a.duration == 0);
    CHECK_INT(a.audio_url.len, 0);
    CHECK_INT(ygst_tts_parse(span_of("{\"duration\":\"\"}"), &a), 0);
    CHECK_INT(a.has_duration, 0);
    CHECK_INT(ygst_tts_parse(span_of("{\"duration\":null}"), &a), 0);
    CHECK_INT(a.has_duration, 0);
    CHECK_INT(ygst_tts_parse(span_of("{\"duration\":\" 1.5\"}"), &a), 0); /* not a plain number */
    CHECK_INT(a.has_duration, 0);
    CHECK_INT(ygst_tts_parse(span_of("[1]"), &a), -1);
    CHECK_INT(ygst_tts_parse(span_of("{}"), NULL), -1);
    ygst_tts_answer_free(&a);
    ygst_tts_answer_init(NULL);
    ygst_tts_answer_free(NULL);
}

/* the data object of the report answer of tools/mock-server, final draft */
#define REPORT_FINAL                                                                                              \
    "{\"recordId\":\"eval_abc123\",\"overall\":93.7,\"score\":{\"overall\":93.7,\"integrity\":100},"               \
    "\"report\":{\"source\":\"llm\",\"summary\":\"mock report\",\"dimensions\":{\"integrity\":\"读得完整。\","     \
    "\"accuracy\":\"个别字的声调可以更准。\"},\"dimensionSuggestions\":{\"accuracy\":[\"“清”的后鼻音收得再完整一些。\"]}," \
    "\"suggestions\":[\"“清”的后鼻音收得再完整一些。\"],\"skillInsights\":[{\"aspect\":\"情景再现\","             \
    "\"observation\":\"读出了画面感。\",\"quote\":\"白毛浮绿水\",\"confidence\":\"medium\"}]},"                     \
    "\"reportStatus\":\"final\",\"timing\":{\"receivedAt\":1791886000000,\"totalMs\":2610,\"engineMs\":2420,"      \
    "\"platformMs\":190,\"audioBytes\":96044,\"channel\":\"rest\"}}"

static void test_report_parse(void) {
    ygst_report r;
    ygst_buf s;
    size_t pos = 0;
    ygst_report_init(&r);
    ygst_buf_init(&s);
    CHECK_INT(ygst_report_parse(span_of(REPORT_FINAL), &r), 0);
    CHECK_STR(ygst_buf_cstr(&r.record_id), "eval_abc123");
    CHECK_STR(ygst_buf_cstr(&r.report_status), "final");
    CHECK(ygst_report_is_final(&r));
    CHECK(!ygst_report_is_pending(&r));
    CHECK_INT(r.has_summary, 1);
    CHECK_STR(ygst_buf_cstr(&r.summary), "mock report");
    CHECK_INT(r.has_overall, 1);
    CHECK(r.overall == 93.7);
    CHECK(r.report.p && r.report.p[0] == '{' && strncmp(r.report.p, "{\"source\":\"llm\"", 15) == 0);
    CHECK(span_is(r.score, "{\"overall\":93.7,\"integrity\":100}"));
    CHECK(r.timing.p && strncmp(r.timing.p, "{\"receivedAt\":1791886000000", 27) == 0 && r.timing.p[r.timing.n - 1] == '}');
    CHECK(span_is(r.suggestions, "[\"“清”的后鼻音收得再完整一些。\"]"));
    CHECK_INT(ygst_report_next_suggestion(r.suggestions, &pos, &s), 1);
    CHECK_STR(ygst_buf_cstr(&s), "“清”的后鼻音收得再完整一些。");
    CHECK_INT(ygst_report_next_suggestion(r.suggestions, &pos, &s), 0);
    /* the instant draft while the AI draft is generated */
    CHECK_INT(ygst_report_parse(span_of("{\"recordId\":\"eval_pending_1\",\"score\":{\"overall\":81},\"report\":{"
                                        "\"summary\":\"即时稿：整体读得流畅。\",\"suggestions\":[\"注意后鼻音。\"]},"
                                        "\"reportStatus\":\"pending\"}"),
                                &r),
              0);
    CHECK(ygst_report_is_pending(&r));
    CHECK(!ygst_report_is_final(&r));
    CHECK_STR(ygst_buf_cstr(&r.summary), "即时稿：整体读得流畅。");
    CHECK(r.has_overall && r.overall == 81);
    CHECK(r.timing.p == NULL);
    /* an open question: its feedback is in score.feedback, no report, always final */
    CHECK_INT(ygst_report_parse(span_of("{\"recordId\":\"eval_o\",\"score\":{\"overall\":96,\"feedback\":{\"strengths\":"
                                        "\"s\",\"suggestions\":[\"a\"]}},\"report\":null,\"reportStatus\":\"final\"}"),
                                &r),
              0);
    CHECK(ygst_report_is_final(&r));
    CHECK(r.report.p == NULL && r.suggestions.p == NULL);
    CHECK_INT(r.has_summary, 0);
    CHECK_INT(r.summary.len, 0);
    CHECK(r.has_overall && r.overall == 96);
    pos = 0;
    CHECK_INT(ygst_report_next_suggestion(r.suggestions, &pos, &s), 0);
    /* no report at all, and a platform before 2026-10-13 without reportStatus */
    CHECK_INT(ygst_report_parse(span_of("{\"recordId\":\"eval_n\",\"score\":{\"overall\":\"80\"},\"reportStatus\":"
                                        "\"none\"}"),
                                &r),
              0);
    CHECK(!ygst_report_is_final(&r) && !ygst_report_is_pending(&r));
    CHECK_INT(r.has_overall, 0); /* only a number is an overall */
    CHECK_INT(ygst_report_parse(span_of("{\"recordId\":7,\"report\":{\"summary\":5,\"suggestions\":\"x\"}}"), &r), 0);
    CHECK_INT(r.report_status.len, 0);
    CHECK(!ygst_report_is_final(&r) && !ygst_report_is_pending(&r));
    CHECK_INT(r.record_id.len, 0);
    CHECK_INT(r.has_summary, 0);
    CHECK(r.suggestions.p == NULL && r.score.p == NULL);
    /* suggestions: the strings only, in order */
    CHECK_INT(ygst_report_parse(span_of("{\"report\":{\"summary\":\"\",\"suggestions\":[\"a\",1,null,\"b\\\"c\",{\"x\":1},"
                                        "[\"d\"],\"\"]}}"),
                                &r),
              0);
    CHECK_INT(r.has_summary, 1);
    CHECK_INT(r.summary.len, 0);
    pos = 0;
    CHECK_INT(ygst_report_next_suggestion(r.suggestions, &pos, &s), 1);
    CHECK_STR(ygst_buf_cstr(&s), "a");
    CHECK_INT(ygst_report_next_suggestion(r.suggestions, &pos, &s), 1);
    CHECK_STR(ygst_buf_cstr(&s), "b\"c");
    CHECK_INT(ygst_report_next_suggestion(r.suggestions, &pos, &s), 1);
    CHECK_STR(ygst_buf_cstr(&s), "");
    CHECK_INT(ygst_report_next_suggestion(r.suggestions, &pos, &s), 0);
    CHECK_INT(ygst_report_next_suggestion(span_of("{}"), &pos, &s), 0);
    CHECK_INT(ygst_report_next_suggestion(r.suggestions, NULL, &s), 0);
    CHECK_INT(ygst_report_parse(span_of("[]"), &r), -1);
    CHECK_INT(r.record_id.len, 0);
    CHECK(r.report.p == NULL);
    CHECK_INT(ygst_report_parse(span_of("{}"), NULL), -1);
    CHECK(!ygst_report_is_final(NULL) && !ygst_report_is_pending(NULL));
    ygst_buf_free(&s);
    ygst_report_free(&r);
    ygst_report_init(NULL);
    ygst_report_free(NULL);
}

/* the whole answer: classified like a call, then the fields of its data */
static void test_report_answer(void) {
    ygst_classification c;
    ygst_report r;
    const char *body = "{\"code\":0,\"message\":\"success\",\"data\":" REPORT_FINAL ",\"timestamp\":1791886000123}";
    ygst_classification_init(&c);
    ygst_report_init(&r);
    classify(200, 0, body, &c);
    CHECK(c.success);
    CHECK(span_is(c.result, REPORT_FINAL));
    CHECK_INT(ygst_report_parse(c.result, &r), 0);
    CHECK_STR(ygst_buf_cstr(&r.record_id), "eval_abc123");
    CHECK(ygst_report_is_final(&r));
    ygst_report_free(&r);
    ygst_classification_free(&c);
}

static void test_report_poll_delay(void) {
    int64_t t, start = 1000;
    int lookups = 0;
    /* waitForFinalMs 0: one lookup whatever the status */
    CHECK_INT(ygst_report_poll_delay(0, start, start, 1), -1);
    CHECK_INT(ygst_report_poll_delay(-5, start, start, 1), -1);
    /* not pending: done */
    CHECK_INT(ygst_report_poll_delay(5000, start, start + 50, 0), -1);
    /* pending with time left: one second later */
    CHECK_INT(ygst_report_poll_delay(5000, start, start + 50, 1), YGST_REPORT_POLL_MS);
    CHECK_INT(ygst_report_poll_delay(5000, start, start + 4000, 1), 1000); /* the lookup starts at 5000: in time */
    CHECK_INT(ygst_report_poll_delay(5000, start, start + 4001, 1), -1);
    CHECK_INT(ygst_report_poll_delay(999, start, start, 1), -1);
    /* a 3000 ms wait with 50 ms lookups: at 0, 1050 and 2100; the next would start at 3150 */
    t = start;
    for (;;) {
        long d;
        lookups++;
        t += 50; /* the answer, still pending */
        d = ygst_report_poll_delay(3000, start, t, 1);
        if (d < 0) break;
        t += d;
    }
    CHECK_INT(lookups, 3);
}

/* the whole getReport decision, the Android shim's rule: only the first lookup fails the call */
static void test_report_after_lookup(void) {
    int64_t start = 1000;
    ygst_rp_action a;
    int i;
    /* ok, pending, and when the answer or the failure came after start */
    static const struct {
        int ok;
        int pending;
        long long t;
    } seq[] = {{1, 1, 50}, {1, 1, 1100}, {0, 0, 2150}, {1, 0, 3200}};
    int lookups;
    int answered;
    /* an answer: looked up again while pending and time is left, else returned */
    a = ygst_report_after_lookup(5000, start, start + 50, 1, 1, 0);
    CHECK_INT(a.kind, YGST_RP_AGAIN);
    CHECK_INT(a.delay_ms, YGST_REPORT_POLL_MS);
    CHECK_INT(a.degraded, 0);
    a = ygst_report_after_lookup(5000, start, start + 4001, 1, 1, 1);
    CHECK_INT(a.kind, YGST_RP_RETURN);
    CHECK_INT(a.delay_ms, 0);
    CHECK_INT(a.degraded, 0);
    a = ygst_report_after_lookup(5000, start, start + 50, 1, 0, 0);
    CHECK_INT(a.kind, YGST_RP_RETURN);
    a = ygst_report_after_lookup(0, start, start + 50, 1, 1, 0); /* waitForFinalMs 0: one lookup */
    CHECK_INT(a.kind, YGST_RP_RETURN);
    /* the first lookup fails: its error, with or without waitForFinalMs */
    a = ygst_report_after_lookup(5000, start, start + 50, 0, 0, 0);
    CHECK_INT(a.kind, YGST_RP_FAIL);
    CHECK_INT(a.degraded, 0);
    a = ygst_report_after_lookup(0, start, start + 50, 0, 0, 0);
    CHECK_INT(a.kind, YGST_RP_FAIL);
    /* a later lookup fails after an answer: the latest answer, degraded, whatever the time left */
    a = ygst_report_after_lookup(5000, start, start + 1100, 0, 0, 1);
    CHECK_INT(a.kind, YGST_RP_RETURN);
    CHECK_INT(a.degraded, 1);
    a = ygst_report_after_lookup(5000, start, start + 9000, 0, 1, 1);
    CHECK_INT(a.kind, YGST_RP_RETURN);
    CHECK_INT(a.degraded, 1);
    /* a call: pending, pending, then the third lookup fails: three lookups, the second answer returned */
    lookups = 0;
    answered = 0;
    for (i = 0; i < (int)(sizeof seq / sizeof seq[0]); i++) {
        lookups++;
        a = ygst_report_after_lookup(6000, start, start + seq[i].t, seq[i].ok, seq[i].pending, answered);
        if (seq[i].ok) answered = 1;
        if (a.kind != YGST_RP_AGAIN) break;
    }
    CHECK_INT(lookups, 3);
    CHECK_INT(a.kind, YGST_RP_RETURN);
    CHECK_INT(a.degraded, 1);
}

static void test_report_lookup_failed_line(void) {
    ygst_buf b;
    ygst_buf_init(&b);
    CHECK_INT(ygst_report_lookup_failed_line("eval_pending_1", 20009, 3, &b), 0);
    CHECK_STR(ygst_buf_cstr(&b), "getReport recordId=eval_pending_1 lookup failed while waiting: code=20009 lookups=3, "
                                 "returning the latest answer");
    ygst_buf_reset(&b);
    CHECK_INT(ygst_report_lookup_failed_line("a b", 40300, 2, &b), 0);
    CHECK_STR(ygst_buf_cstr(&b), "getReport recordId=a_b lookup failed while waiting: code=40300 lookups=2, "
                                 "returning the latest answer");
    ygst_buf_reset(&b);
    CHECK_INT(ygst_report_lookup_failed_line(NULL, 2003, 4, &b), 0);
    CHECK(strstr(ygst_buf_cstr(&b), "recordId=- ") != NULL);
    CHECK_INT(ygst_report_lookup_failed_line("x", 1, 1, NULL), -1);
    ygst_buf_free(&b);
}

static void check_line(const char *op, const char *rid, const char *status, int code, int lookups, int attempts,
                       long long ms, const char *want) {
    ygst_buf b;
    ygst_buf_init(&b);
    CHECK_INT(ygst_api_log_line(op, rid, status, code, lookups, attempts, ms, &b), 0);
    CHECK_STR(ygst_buf_cstr(&b), want);
    ygst_buf_free(&b);
}

static void test_api_log_line(void) {
    check_line("tts", NULL, "ok", 0, -1, 1, 842, "tts status=ok attempts=1 ms=842");
    check_line("tts", NULL, NULL, 20009, -1, 3, 2410, "tts status=error code=20009 attempts=3 ms=2410");
    check_line("getReport", "eval_3fb4", "final", 0, 3, 3, 2150,
               "getReport recordId=eval_3fb4 status=final lookups=3 attempts=3 ms=2150");
    check_line("getReport", "eval_x", "pending", 40001, 1, 1, 35,
               "getReport recordId=eval_x status=error code=40001 lookups=1 attempts=1 ms=35");
    check_line("getReport", "", "", 0, 1, 1, 0, "getReport recordId=- status=- lookups=1 attempts=1 ms=0");
    check_line("getReport", "a b\nc\x7f", "pen ding", 0, 2, 2, 5,
               "getReport recordId=a_b_c_ status=pen_ding lookups=2 attempts=2 ms=5");
    check_line(NULL, NULL, "ok", 0, -1, 0, 0, " status=ok attempts=0 ms=0");
    CHECK_INT(ygst_api_log_line("tts", NULL, "ok", 0, -1, 1, 1, NULL), -1);
}

void suite_api(void) {
    RUN("api", test_tts_body_every_field);
    RUN("api", test_tts_body_only_what_is_set);
    RUN("api", test_api_urls);
    RUN("api", test_tts_absolute_url);
    RUN("api", test_classify_api);
    RUN("api", test_api_call_controller);
    RUN("api", test_tts_parse);
    RUN("api", test_report_parse);
    RUN("api", test_report_answer);
    RUN("api", test_report_poll_delay);
    RUN("api", test_report_after_lookup);
    RUN("api", test_report_lookup_failed_line);
    RUN("api", test_api_log_line);
}
