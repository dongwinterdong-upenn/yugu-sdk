/*
 * Copyright 2026 优谷雅言 open.shengzhiai.com
 * SPDX-License-Identifier: Apache-2.0
 *
 * Per-phase timing (2.1.1): Server-Timing header, the platform timing object, the INFO summary
 * line, the DEBUG detail line, and that the timing object never reaches the app.
 */
#include "ygt.h"

#define TOKEN "0123456789abcdef0123456789abcdef"

/* The final JSON of the platform with timing=1 (BRIEF 2.1.1 D), as a REST body or WebSocket frame. */
#define TIMING_OBJECT                                                                                            \
    "{\"receivedAt\":1791800000123,\"totalMs\":5321,\"engineMs\":4100,\"platformMs\":1221,"                    \
    "\"engineStages\":{\"text_frontend_ms\":1909.3,\"asr_ms\":595.0,\"note\":\"x\",\"nested\":{\"a\":1}},"     \
    "\"audioBytes\":528000,\"wsFirstAudioAt\":1791799990000,\"futureKey\":[1,2]}"
#define FINAL_WITH_TIMING                                                                                        \
    "{\"recordId\":\"eval_7\",\"eof\":1,\"result\":{\"overall\":91.5,\"words\":[{\"word\":\"x\"}]},"           \
    "\"timing\":" TIMING_OBJECT "}"
#define FINAL_WITHOUT_TIMING "{\"recordId\":\"eval_7\",\"eof\":1,\"result\":{\"overall\":91.5,\"words\":[{\"word\":\"x\"}]}}"

static void test_init_and_wanted(void) {
    ygst_timing t;
    ygst_timing_init(&t);
    CHECK_INT(t.mode, YGST_TM_NONE);
    CHECK_INT(t.start_ms, 0);
    CHECK_INT(t.upload_ms, -1);
    CHECK_INT(t.wait_ms, -1);
    CHECK_INT(t.download_ms, -1);
    CHECK_INT(t.audio_bytes, -1);
    CHECK_INT(t.queued_at_stop, -1);
    CHECK_INT(t.wire_bytes, -1);
    CHECK_INT(t.body_bytes, -1);
    CHECK_INT(t.server_total_ms, -1);
    CHECK_INT(t.server_engine_ms, -1);
    CHECK_INT(t.server_platform_ms, -1);
    ygst_timing_init(NULL);
    /* the SDK asks for the timing object at INFO and DEBUG only: WARN is the default */
    CHECK_INT(ygst_timing_wanted(-1), 0);
    CHECK_INT(ygst_timing_wanted(0), 0);
    CHECK_INT(ygst_timing_wanted(1), 0);
    CHECK_INT(ygst_timing_wanted(2), 1);
    CHECK_INT(ygst_timing_wanted(3), 1);
}

static void test_server_timing_header(void) {
    ygst_timing t;
    /* the platform's header, word for word */
    ygst_timing_init(&t);
    CHECK_INT(ygst_server_timing_parse("total;dur=5321, engine;dur=4100, platform;dur=1221", &t), 3);
    CHECK_INT(t.server_total_ms, 5321);
    CHECK_INT(t.server_engine_ms, 4100);
    CHECK_INT(t.server_platform_ms, 1221);
    /* any order and case, spaces, quoted values, desc parameters, fractions rounded half up */
    ygst_timing_init(&t);
    CHECK_INT(ygst_server_timing_parse("Platform;DUR=1.5 ,ENGINE ; dur = 4100.49,total;desc=\"a;b,c\";dur=\"5321.5\"",
                                       &t),
              3);
    CHECK_INT(t.server_total_ms, 5322);
    CHECK_INT(t.server_engine_ms, 4100);
    CHECK_INT(t.server_platform_ms, 2);
    /* other metrics are ignored; one metric found */
    ygst_timing_init(&t);
    CHECK_INT(ygst_server_timing_parse("cache;desc=hit, db;dur=53, app;dur=47.2, total;dur=10", &t), 1);
    CHECK_INT(t.server_total_ms, 10);
    CHECK_INT(t.server_engine_ms, -1);
    /* the last value of a repeated metric wins, still counted once */
    ygst_timing_init(&t);
    CHECK_INT(ygst_server_timing_parse("total;dur=1, total;dur=2", &t), 1);
    CHECK_INT(t.server_total_ms, 2);
    /* without a usable dur nothing is taken */
    ygst_timing_init(&t);
    CHECK_INT(ygst_server_timing_parse("total", &t), 0);
    CHECK_INT(ygst_server_timing_parse("total;dur", &t), 0);
    CHECK_INT(ygst_server_timing_parse("total;dur=", &t), 0);
    CHECK_INT(ygst_server_timing_parse("total;dur=abc", &t), 0);
    CHECK_INT(ygst_server_timing_parse("total;dur=-5", &t), 0);
    CHECK_INT(ygst_server_timing_parse("total;dur=1e3", &t), 0);
    CHECK_INT(ygst_server_timing_parse("total;dur=99999999999999999999", &t), 0);
    CHECK_INT(ygst_server_timing_parse("total;dur=\"12", &t), 1); /* unterminated quote: what is there */
    CHECK_INT(t.server_total_ms, 12);
    ygst_timing_init(&t);
    CHECK_INT(ygst_server_timing_parse("", &t), 0);
    CHECK_INT(ygst_server_timing_parse(" , ;dur=4,", &t), 0);
    CHECK_INT(ygst_server_timing_parse(NULL, &t), 0);
    CHECK_INT(ygst_server_timing_parse("total;dur=5", NULL), 0);
    CHECK_INT(t.server_total_ms, -1);
    /* a dur of "." or ".5" */
    CHECK_INT(ygst_server_timing_parse("engine;dur=.", &t), 0);
    CHECK_INT(ygst_server_timing_parse("engine;dur=.5", &t), 1);
    CHECK_INT(t.server_engine_ms, 1);
}

static void test_timing_object(void) {
    ygst_timing t;
    ygst_span root, timing, v;
    const char *body = FINAL_WITH_TIMING;
    CHECK_INT(ygst_json_parse(body, strlen(body), &root), 0);
    ygst_timing_init(&t);
    CHECK_INT(ygst_timing_member(root, &t, &timing), 1);
    CHECK_INT(t.server_total_ms, 5321);
    CHECK_INT(t.server_engine_ms, 4100);
    CHECK_INT(t.server_platform_ms, 1221);
    CHECK_INT(ygst_json_type(timing), 'o');
    CHECK_INT(ygst_json_member(timing, "wsFirstAudioAt", &v), 1);
    /* without the object nothing changes */
    body = FINAL_WITHOUT_TIMING;
    CHECK_INT(ygst_json_parse(body, strlen(body), &root), 0);
    ygst_timing_init(&t);
    CHECK_INT(ygst_timing_member(root, &t, &timing), 0);
    CHECK(timing.p == NULL);
    CHECK_INT(t.server_total_ms, -1);
    /* not an object, fractions, missing and non-numeric members */
    body = "{\"timing\":1}";
    CHECK_INT(ygst_json_parse(body, strlen(body), &root), 0);
    CHECK_INT(ygst_timing_member(root, &t, &timing), 0);
    body = "{\"timing\":{\"totalMs\":12.5,\"engineMs\":\"4\",\"platformMs\":-3}}";
    CHECK_INT(ygst_json_parse(body, strlen(body), &root), 0);
    ygst_timing_init(&t);
    CHECK_INT(ygst_timing_member(root, &t, NULL), 1);
    CHECK_INT(t.server_total_ms, 13);
    CHECK_INT(t.server_engine_ms, -1);
    CHECK_INT(t.server_platform_ms, -1);
    body = "[1]";
    CHECK_INT(ygst_json_parse(body, strlen(body), &root), 0);
    CHECK_INT(ygst_timing_member(root, &t, &timing), -1);
    root.p = NULL;
    root.n = 0;
    CHECK_INT(ygst_timing_member(root, &t, &timing), -1);
}

/* The result envelope is built from result, recordId and audioUrl: a body with the timing object
 * gives the app the same JSON as the body without it (BRIEF 2.1.1 D), over REST and over the
 * WebSocket, and the timing stays readable for the log through the classification's root. */
static void test_timing_never_reaches_the_app(void) {
    const char *bodies[2] = {FINAL_WITH_TIMING, FINAL_WITHOUT_TIMING};
    ygst_buf env[2];
    ygst_classification c[2];
    ygst_timing t;
    ygst_span timing;
    ygst_ws_frame f;
    int i;
    for (i = 0; i < 2; i++) {
        ygst_outcome o;
        char rid[64];
        memset(&o, 0, sizeof o);
        o.http_status = 200;
        o.retry_after_ms = -1;
        o.body = bodies[i];
        o.body_len = strlen(bodies[i]);
        ygst_classification_init(&c[i]);
        CHECK_INT(ygst_classify(&o, &c[i]), 0);
        CHECK(c[i].success);
        snprintf(rid, sizeof rid, "%.*s", (int)c[i].record_id_raw.n, c[i].record_id_raw.p);
        ygst_buf_init(&env[i]);
        CHECK_INT(ygst_envelope_result(&env[i], TOKEN, rid, "ak", "u1", "x", "2026-10-13 12:00:00:000", c[i].result, NULL,
                                       NULL),
                  0);
    }
    CHECK_STR(ygst_buf_cstr(&env[0]), ygst_buf_cstr(&env[1]));
    CHECK(strstr(ygst_buf_cstr(&env[0]), "timing") == NULL);
    CHECK(strstr(ygst_buf_cstr(&env[0]), "receivedAt") == NULL);
    /* the root of a success is the body object */
    CHECK(c[0].root.p == bodies[0]);
    CHECK_INT(c[0].root.n, strlen(bodies[0]));
    ygst_timing_init(&t);
    CHECK_INT(ygst_timing_member(c[0].root, &t, &timing), 1);
    CHECK_INT(t.server_total_ms, 5321);
    CHECK_INT(ygst_timing_member(c[1].root, &t, &timing), 0);
    /* a failure has no root */
    {
        ygst_outcome o;
        memset(&o, 0, sizeof o);
        o.http_status = 503;
        o.retry_after_ms = -1;
        o.body = "{\"code\":50000,\"message\":\"busy\"}";
        o.body_len = strlen(o.body);
        CHECK_INT(ygst_classify(&o, &c[1]), 0);
        CHECK(c[1].root.p == NULL);
        CHECK_INT(ygst_ws_classify_error(c[0].root, &c[0]), 0);
        CHECK(c[0].root.p == NULL);
    }
    /* a WebSocket final frame with timing is a final like any other */
    CHECK_INT(ygst_ws_frame_parse(bodies[0], strlen(bodies[0]), &f), 0);
    CHECK_INT(f.kind, YGST_WSF_FINAL);
    for (i = 0; i < 2; i++) {
        ygst_classification_free(&c[i]);
        ygst_buf_free(&env[i]);
    }
}

static void fill_ws(ygst_timing *t) {
    ygst_timing_init(t);
    t->mode = YGST_TM_WS;
    t->ok = 1;
    t->attempts = 1;
    t->deflate = 1;
    t->start_ms = 1000;
    t->ws_connect_ms = 1005;
    t->ws_handshake_ms = 1090;
    t->ws_params_ms = 1091;
    t->ws_first_audio_ms = 1150;
    t->stop_ms = 32200;
    t->ws_end_ms = 32320;
    t->ws_final_ms = 37800;
    t->parsed_ms = 37840;
    t->delivered_ms = 37843;
    t->audio_bytes = 998400; /* 31.2 s of 16 kHz 16 bit mono */
    t->audio_bytes_per_sec = 32000;
    t->queued_at_stop = 6400;
    t->body_bytes = 1532118;
    t->server_total_ms = 5321;
    t->server_engine_ms = 4100;
    t->server_platform_ms = 1221;
}

static void fill_rest(ygst_timing *t) {
    ygst_timing_init(t);
    t->mode = YGST_TM_REST;
    t->ok = 1;
    t->attempts = 1;
    t->start_ms = 500;
    t->stop_ms = 12800;
    t->rest_start_ms = 12850;
    t->rest_done_ms = 18900;
    t->parsed_ms = 18940;
    t->delivered_ms = 18950;
    t->upload_ms = 1834;
    t->wait_ms = 4120;
    t->download_ms = 85;
    t->audio_bytes = 393600;
    t->audio_bytes_per_sec = 32000;
    t->upload_bytes = 394012;
    t->wire_bytes = 122880;
    t->body_bytes = 1532118;
    snprintf(t->encoding, sizeof t->encoding, "gzip");
    t->server_total_ms = 5321;
    t->server_engine_ms = 4100;
    t->server_platform_ms = 1221;
}

static void test_summary_line(void) {
    ygst_timing t;
    ygst_buf out;
    ygst_buf_init(&out);
    /* streamed: the rest of the upload after stop, the wait for the whole final (no first byte),
     * the format of the Android drop-in */
    fill_ws(&t);
    CHECK_INT(ygst_timing_summary(&t, TOKEN, "eval_77c081e19541", &out), 0);
    CHECK_STR(ygst_buf_cstr(&out), "timing tokenId=" TOKEN " recordId=eval_77c081e19541 mode=ws audio=31.20s "
                                   "afterStop=5643ms upload=120ms "
                                   "wait=5480ms download=- parse=40ms deliver=3ms handshake=85ms queued=6400B "
                                   "final=1532118B wire=- deflate server.total=5321ms server.engine=4100ms "
                                   "server.platform=1221ms");
    /* a transport that sees the first byte of the final splits wait and download; reconnects, replay */
    t.ws_final_first_ms = 37500;
    t.wire_bytes = 120001;
    t.deflate = 0;
    t.attempts = 2;
    t.replayed = 1;
    ygst_buf_reset(&out);
    CHECK_INT(ygst_timing_summary(&t, TOKEN, "eval_77c081e19541", &out), 0);
    CHECK(strstr(ygst_buf_cstr(&out), " wait=5180ms download=300ms ") != NULL);
    CHECK(strstr(ygst_buf_cstr(&out), " final=1532118B wire=120001B connections=2 server.total=5321ms") != NULL);
    CHECK(strstr(ygst_buf_cstr(&out), "server.platform=1221ms replayed") != NULL);
    /* one request after stop: upload from stop to the last byte of the body, the transport's split */
    fill_rest(&t);
    ygst_buf_reset(&out);
    CHECK_INT(ygst_timing_summary(&t, TOKEN, "eval_9a8b", &out), 0);
    CHECK_STR(ygst_buf_cstr(&out), "timing tokenId=" TOKEN " recordId=eval_9a8b mode=rest audio=12.30s afterStop=6150ms "
                                   "upload=1884ms "
                                   "wait=4120ms download=85ms parse=40ms deliver=10ms attempts=1 sent=394012B "
                                   "body=1532118B wire=122880B gzip server.total=5321ms server.engine=4100ms "
                                   "server.platform=1221ms");
    /* the WebSocket failed before end: the request after stop counts, with the reason */
    t.mode = YGST_TM_WS_REST;
    t.encoding[0] = 0;
    ygst_timing_set_fallback(&t, "WebSocket handshake refused \"503\"\n");
    ygst_buf_reset(&out);
    CHECK_INT(ygst_timing_summary(&t, TOKEN, "eval_9a8b", &out), 0);
    CHECK(strstr(ygst_buf_cstr(&out), " mode=ws>rest audio=12.30s afterStop=6150ms upload=1884ms ") != NULL);
    CHECK(strstr(ygst_buf_cstr(&out), " wire=122880B server.total=5321ms") != NULL);
    CHECK(strstr(ygst_buf_cstr(&out), "server.platform=1221ms fallback=\"WebSocket handshake refused '503' \"") != NULL);
    /* a file evaluation that failed: stop is the start call, no transport split, no server timing */
    ygst_timing_init(&t);
    t.mode = YGST_TM_REST;
    t.ok = 0;
    t.err_id = 20009;
    t.attempts = 3;
    t.start_ms = 100;
    t.stop_ms = 100;
    t.rest_start_ms = 2100;
    t.rest_done_ms = 2600;
    t.parsed_ms = 2601;
    t.delivered_ms = 2605;
    t.audio_bytes = 61518;
    t.upload_bytes = 61900;
    ygst_buf_reset(&out);
    /* an error has no record: "-" */
    CHECK_INT(ygst_timing_summary(&t, TOKEN, NULL, &out), 0);
    CHECK_STR(ygst_buf_cstr(&out), "timing tokenId=" TOKEN " recordId=- mode=rest audio=- afterStop=2505ms upload=- wait=- "
                                   "download=- parse=1ms deliver=4ms attempts=3 sent=61900B body=- wire=- errId=20009");
    /* nothing known */
    ygst_timing_init(&t);
    ygst_buf_reset(&out);
    CHECK_INT(ygst_timing_summary(&t, NULL, "", &out), 0);
    CHECK_STR(ygst_buf_cstr(&out), "timing tokenId= recordId=- mode=local audio=- afterStop=- upload=- wait=- download=- "
                                   "parse=- "
                                   "deliver=- attempts=0 sent=- body=- wire=-");
    /* audio rounds half up to hundredths; phases out of order count as unknown */
    fill_ws(&t);
    t.audio_bytes = 160; /* 5 ms */
    t.ws_end_ms = 30000; /* before stop */
    ygst_buf_reset(&out);
    CHECK_INT(ygst_timing_summary(&t, TOKEN, "eval_1", &out), 0);
    CHECK(strstr(ygst_buf_cstr(&out), " audio=0.01s ") != NULL);
    CHECK(strstr(ygst_buf_cstr(&out), " upload=- wait=7800ms ") != NULL);
    CHECK_INT(ygst_timing_summary(NULL, TOKEN, "eval_1", &out), -1);
    CHECK_INT(ygst_timing_summary(&t, TOKEN, "eval_1", NULL), -1);
    ygst_buf_free(&out);
}

static void test_fallback_reason(void) {
    ygst_timing t;
    char longer[400];
    size_t i;
    ygst_timing_init(&t);
    ygst_timing_set_fallback(&t, NULL);
    CHECK_STR(t.fallback, "");
    ygst_timing_set_fallback(&t, "timeout: no pong within 30000 ms");
    CHECK_STR(t.fallback, "timeout: no pong within 30000 ms");
    /* cut at a whole UTF-8 sequence: 3-byte characters never end half way */
    for (i = 0; i + 3 < sizeof longer; i += 3) memcpy(longer + i, "\xe6\x96\xad", 3);
    longer[i] = 0;
    ygst_timing_set_fallback(&t, longer);
    CHECK(strlen(t.fallback) < sizeof t.fallback);
    CHECK_INT(strlen(t.fallback) % 3, 0);
    CHECK(strlen(t.fallback) >= sizeof t.fallback - 4);
    ygst_timing_set_fallback(NULL, "x");
}

static void test_debug_lines(void) {
    ygst_timing t;
    ygst_buf out;
    ygst_span root, timing;
    const char *body = FINAL_WITH_TIMING;
    ygst_buf_init(&out);
    fill_ws(&t);
    CHECK_INT(ygst_timing_phases(&t, TOKEN, &out), 0);
    CHECK_STR(ygst_buf_cstr(&out), "timing tokenId=" TOKEN " phases start=+0ms connect=+5ms handshake=+90ms params=+91ms "
                                   "firstAudio=+150ms stop=+31200ms endWritten=+31320ms final=+36800ms "
                                   "parsed=+36840ms delivered=+36843ms");
    /* REST phases; the origin is the earliest phase */
    ygst_timing_init(&t);
    t.stop_ms = 2000;
    t.rest_start_ms = 2010;
    t.rest_done_ms = 2500;
    t.parsed_ms = 2510;
    t.delivered_ms = 2512;
    ygst_buf_reset(&out);
    CHECK_INT(ygst_timing_phases(&t, TOKEN, &out), 0);
    CHECK_STR(ygst_buf_cstr(&out), "timing tokenId=" TOKEN " phases stop=+0ms requestStart=+10ms responseDone=+500ms "
                                   "parsed=+510ms delivered=+512ms");
    CHECK_INT(ygst_timing_phases(NULL, TOKEN, &out), -1);
    /* the engine stages and the other members of the timing object */
    CHECK_INT(ygst_json_parse(body, strlen(body), &root), 0);
    CHECK_INT(ygst_timing_member(root, NULL, &timing), 1);
    ygst_buf_reset(&out);
    CHECK_INT(ygst_timing_stages(timing, TOKEN, &out), 1);
    CHECK_STR(ygst_buf_cstr(&out), "timing tokenId=" TOKEN " engineStages text_frontend_ms=1909.3 asr_ms=595.0 "
                                   "receivedAt=1791800000123 wsFirstAudioAt=1791799990000 audioBytes=528000");
    /* nothing to say: no line */
    body = "{\"timing\":{\"totalMs\":5,\"engineStages\":[]}}";
    CHECK_INT(ygst_json_parse(body, strlen(body), &root), 0);
    CHECK_INT(ygst_timing_member(root, NULL, &timing), 1);
    ygst_buf_reset(&out);
    CHECK_INT(ygst_timing_stages(timing, TOKEN, &out), 0);
    CHECK_INT(out.len, 0);
    timing.p = NULL;
    timing.n = 0;
    CHECK_INT(ygst_timing_stages(timing, TOKEN, &out), 0);
    CHECK_INT(ygst_timing_stages(timing, TOKEN, NULL), -1);
    ygst_buf_free(&out);
}

void suite_timing(void) {
    RUN("timing", test_init_and_wanted);
    RUN("timing", test_server_timing_header);
    RUN("timing", test_timing_object);
    RUN("timing", test_timing_never_reaches_the_app);
    RUN("timing", test_summary_line);
    RUN("timing", test_fallback_reason);
    RUN("timing", test_debug_lines);
}
