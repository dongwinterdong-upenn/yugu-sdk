/*
 * Copyright 2026 优谷雅言 open.shengzhiai.com
 * SPDX-License-Identifier: Apache-2.0
 *
 * TtsClient and ReportClient (2.2.0): POST /api/v1/tts/generate and GET /api/v1/report/{recordId}
 * (CONTRACT 2.2, 2.3, 4, 6, 7). The JSON body of a TTS request with its signed parameters, the
 * report path, the absolute audio URL, the fields of the answers, the wait for the AI final draft and
 * the log line of a call. ygst_classify_api (ygst_retry.c) classifies the answers; the Objective-C
 * layer sends the requests with the session and the retry controller of the evaluations.
 */
#include "ygst_core.h"

#include <stdio.h>
#include <string.h>

/* ------------------------------------------------------------------ TTS request */

void ygst_tts_request_init(ygst_tts_request *r) {
    if (r) memset(r, 0, sizeof *r);
}

typedef struct {
    ygst_buf *json;
    ygst_fields *sign;
    int first;
    int rc;
} body_writer;

/* "name":<literal> into the body; the signed parameter is the literal text of the value */
static void put_member(body_writer *w, const char *name, const char *literal, int quoted) {
    if (!w->first) ygst_buf_append_char(w->json, ',');
    w->first = 0;
    ygst_json_escape(w->json, name);
    ygst_buf_append_char(w->json, ':');
    if (quoted) {
        ygst_json_escape(w->json, literal);
    } else {
        ygst_buf_append_str(w->json, literal);
    }
    if (ygst_fields_set(w->sign, name, literal) != 0) w->rc = -1;
}

static void put_str(body_writer *w, const char *name, const char *value) {
    if (value && value[0]) put_member(w, name, value, 1);
}

static void put_int(body_writer *w, const char *name, int has, long long value) {
    char tmp[32];
    if (!has) return;
    snprintf(tmp, sizeof tmp, "%lld", value);
    put_member(w, name, tmp, 0);
}

int ygst_tts_body(const ygst_tts_request *r, ygst_buf *json, ygst_fields *sign) {
    body_writer w;
    if (!r || !json || !sign) return -1;
    if (ygst_is_blank(r->text)) return YGST_LOCAL_INVALID_ARGUMENT;
    w.json = json;
    w.sign = sign;
    w.first = 1;
    w.rc = 0;
    ygst_buf_append_char(json, '{');
    put_str(&w, "text", r->text);
    put_str(&w, "language", r->language);
    put_str(&w, "voice", r->voice);
    put_str(&w, "format", r->format);
    put_int(&w, "speed", r->has_speed, r->speed);
    put_int(&w, "pitch", r->has_pitch, r->pitch);
    put_int(&w, "volume", r->has_volume, r->volume);
    put_int(&w, "sampleRate", r->has_sample_rate, r->sample_rate);
    put_str(&w, "emotion", r->emotion);
    put_str(&w, "style", r->style);
    put_str(&w, "pinyin", r->pinyin);
    put_str(&w, "genre", r->genre);
    ygst_buf_append_char(json, '}');
    ygst_buf_cstr(json);
    return (w.rc != 0 || json->oom || sign->oom) ? -1 : 0;
}

/* ------------------------------------------------------------------ TTS answer */

void ygst_tts_answer_init(ygst_tts_answer *a) {
    if (!a) return;
    memset(a, 0, sizeof *a);
    ygst_buf_init(&a->audio_url);
    ygst_buf_init(&a->format);
}

void ygst_tts_answer_free(ygst_tts_answer *a) {
    if (!a) return;
    ygst_buf_free(&a->audio_url);
    ygst_buf_free(&a->format);
}

/* 1 when obj[key] is a string, its text in out (replaced) */
static int string_member(ygst_span obj, const char *key, ygst_buf *out) {
    ygst_span v;
    ygst_buf_reset(out);
    if (ygst_json_member(obj, key, &v) != 1 || ygst_json_type(v) != 's') return 0;
    return ygst_json_string(v, out) == 0;
}

int ygst_tts_parse(ygst_span data, ygst_tts_answer *a) {
    ygst_span v;
    if (!a) return -1;
    ygst_buf_reset(&a->audio_url);
    ygst_buf_reset(&a->format);
    a->has_duration = 0;
    a->duration = 0;
    if (ygst_json_type(data) != 'o') return -1;
    string_member(data, "audioUrl", &a->audio_url);
    string_member(data, "format", &a->format);
    if (ygst_json_member(data, "duration", &v) == 1) {
        if (ygst_json_type(v) == 'n') {
            a->has_duration = ygst_json_number(v, &a->duration) == 0;
        } else if (ygst_json_type(v) == 's') {
            /* the platform writes the seconds as text, "1.348": read it as a JSON number */
            ygst_buf text;
            ygst_span num;
            ygst_buf_init(&text);
            if (ygst_json_string(v, &text) == 0 && text.len) {
                num.p = ygst_buf_cstr(&text);
                num.n = text.len;
                a->has_duration = ygst_json_type(num) == 'n' && ygst_json_number(num, &a->duration) == 0;
            }
            ygst_buf_free(&text);
        }
        if (!a->has_duration) a->duration = 0;
    }
    ygst_buf_cstr(&a->audio_url);
    ygst_buf_cstr(&a->format);
    return (a->audio_url.oom || a->format.oom) ? -1 : 0;
}

static int starts_with_ci(const char *s, const char *prefix) {
    for (; *prefix; s++, prefix++) {
        char c = *s;
        if (c >= 'A' && c <= 'Z') c = (char)(c + ('a' - 'A'));
        if (c != *prefix) return 0;
    }
    return 1;
}

int ygst_tts_absolute_url(const char *base, const char *audio_url, ygst_buf *out) {
    size_t n;
    if (!out) return -1;
    if (audio_url && audio_url[0]) {
        if (starts_with_ci(audio_url, "http://") || starts_with_ci(audio_url, "https://")) {
            ygst_buf_append_str(out, audio_url);
        } else {
            if (!base) base = "";
            n = strlen(base);
            while (n > 0 && base[n - 1] == '/') n--;
            ygst_buf_append(out, base, n);
            if (strncmp(audio_url, "/audio/", 7) == 0) {
                ygst_buf_append_str(out, "/tts"); /* CONTRACT 4.1: /audio/.. is served under /tts */
            } else if (audio_url[0] != '/') {
                ygst_buf_append_char(out, '/');
            }
            ygst_buf_append_str(out, audio_url);
        }
    }
    ygst_buf_cstr(out);
    return out->oom ? -1 : 0;
}

/* ------------------------------------------------------------------ report request */

int ygst_report_path(const char *record_id, ygst_buf *out) {
    ygst_buf id;
    int rc;
    if (!out) return -1;
    if (ygst_is_blank(record_id)) return YGST_LOCAL_INVALID_ARGUMENT;
    ygst_buf_init(&id);
    ygst_buf_append_trimmed(&id, record_id);
    ygst_buf_append_str(out, "api/v1/report/");
    ygst_url_encode(out, ygst_buf_cstr(&id)); /* recordId is one path segment */
    rc = (id.oom || out->oom) ? -1 : 0;
    ygst_buf_free(&id);
    return rc;
}

int ygst_api_url(const char *base, const char *path, ygst_buf *out) {
    size_t n;
    if (!base || !path || !out) return -1;
    n = strlen(base);
    while (n > 0 && base[n - 1] == '/') n--;
    ygst_buf_append(out, base, n);
    ygst_buf_append_char(out, '/');
    while (*path == '/') path++;
    ygst_buf_append_str(out, path);
    ygst_buf_cstr(out);
    return out->oom ? -1 : 0;
}

/* ------------------------------------------------------------------ report answer */

void ygst_report_init(ygst_report *r) {
    if (!r) return;
    memset(r, 0, sizeof *r);
    ygst_buf_init(&r->record_id);
    ygst_buf_init(&r->report_status);
    ygst_buf_init(&r->summary);
}

void ygst_report_free(ygst_report *r) {
    if (!r) return;
    ygst_buf_free(&r->record_id);
    ygst_buf_free(&r->report_status);
    ygst_buf_free(&r->summary);
}

static ygst_span object_member(ygst_span obj, const char *key) {
    ygst_span v;
    if (ygst_json_member(obj, key, &v) == 1 && ygst_json_type(v) == 'o') return v;
    v.p = NULL;
    v.n = 0;
    return v;
}

int ygst_report_parse(ygst_span data, ygst_report *r) {
    ygst_span v;
    if (!r) return -1;
    ygst_buf_reset(&r->record_id);
    ygst_buf_reset(&r->report_status);
    ygst_buf_reset(&r->summary);
    r->has_summary = 0;
    r->has_overall = 0;
    r->overall = 0;
    r->report.p = r->score.p = r->timing.p = r->suggestions.p = NULL;
    r->report.n = r->score.n = r->timing.n = r->suggestions.n = 0;
    if (ygst_json_type(data) != 'o') return -1;
    string_member(data, "recordId", &r->record_id);
    string_member(data, "reportStatus", &r->report_status);
    r->report = object_member(data, "report");
    r->score = object_member(data, "score");
    r->timing = object_member(data, "timing");
    if (r->report.p) {
        r->has_summary = string_member(r->report, "summary", &r->summary);
        if (ygst_json_member(r->report, "suggestions", &v) == 1 && ygst_json_type(v) == 'a') r->suggestions = v;
    }
    if (r->score.p && ygst_json_member(r->score, "overall", &v) == 1 && ygst_json_type(v) == 'n') {
        r->has_overall = ygst_json_number(v, &r->overall) == 0;
        if (!r->has_overall) r->overall = 0;
    }
    ygst_buf_cstr(&r->record_id);
    ygst_buf_cstr(&r->report_status);
    ygst_buf_cstr(&r->summary);
    return (r->record_id.oom || r->report_status.oom || r->summary.oom) ? -1 : 0;
}

int ygst_report_next_suggestion(ygst_span suggestions, size_t *pos, ygst_buf *out) {
    ygst_span item;
    if (!pos || !out || ygst_json_type(suggestions) != 'a') return 0;
    while (ygst_json_array_next(suggestions, pos, &item) == 1) {
        if (ygst_json_type(item) != 's') continue;
        ygst_buf_reset(out);
        if (ygst_json_string(item, out) != 0) continue;
        ygst_buf_cstr(out);
        return 1;
    }
    return 0;
}

static int status_is(const ygst_report *r, const char *status) {
    size_t n = strlen(status);
    return r && r->report_status.len == n && memcmp(r->report_status.data, status, n) == 0;
}

int ygst_report_is_pending(const ygst_report *r) {
    return status_is(r, "pending");
}

int ygst_report_is_final(const ygst_report *r) {
    return status_is(r, "final");
}

long ygst_report_poll_delay(long long wait_ms, int64_t started_ms, int64_t now_ms, int pending) {
    if (!pending || wait_ms <= 0) return -1;
    if ((long long)(now_ms - started_ms) + YGST_REPORT_POLL_MS > wait_ms) return -1;
    return YGST_REPORT_POLL_MS;
}

ygst_rp_action ygst_report_after_lookup(long long wait_ms, int64_t started_ms, int64_t now_ms, int ok, int pending,
                                        int answered) {
    ygst_rp_action a;
    a.kind = YGST_RP_RETURN;
    a.delay_ms = 0;
    a.degraded = 0;
    if (!ok) {
        /* the first lookup reports its error; a later one leaves the answer already received */
        if (answered) {
            a.degraded = 1;
        } else {
            a.kind = YGST_RP_FAIL;
        }
        return a;
    }
    a.delay_ms = ygst_report_poll_delay(wait_ms, started_ms, now_ms, pending);
    if (a.delay_ms >= 0) {
        a.kind = YGST_RP_AGAIN;
    } else {
        a.delay_ms = 0;
    }
    return a;
}

/* ------------------------------------------------------------------ log line */

/* a value of the log line: whitespace and control bytes become "_", so one value stays one token */
static void put_token_value(ygst_buf *b, const char *s) {
    const unsigned char *p = (const unsigned char *)s;
    if (!p || !*p) {
        ygst_buf_append_char(b, '-');
        return;
    }
    for (; *p; p++) ygst_buf_append_char(b, (char)((*p <= 0x20 || *p == 0x7F) ? '_' : *p));
}

int ygst_report_lookup_failed_line(const char *record_id, int code, int lookups, ygst_buf *out) {
    if (!out) return -1;
    ygst_buf_append_str(out, "getReport recordId=");
    put_token_value(out, record_id);
    ygst_buf_append_str(out, " lookup failed while waiting: code=");
    ygst_buf_append_int(out, code);
    ygst_buf_append_str(out, " lookups=");
    ygst_buf_append_int(out, lookups);
    ygst_buf_append_str(out, ", returning the latest answer");
    ygst_buf_cstr(out);
    return out->oom ? -1 : 0;
}

int ygst_api_log_line(const char *op, const char *record_id, const char *status, int code, int lookups,
                      int attempts, long long ms, ygst_buf *out) {
    if (!out) return -1;
    ygst_buf_append_str(out, op ? op : "");
    if (record_id) {
        ygst_buf_append_str(out, " recordId=");
        put_token_value(out, record_id);
    }
    if (code) {
        ygst_buf_append_str(out, " status=error code=");
        ygst_buf_append_int(out, code);
    } else {
        ygst_buf_append_str(out, " status=");
        put_token_value(out, status);
    }
    if (lookups >= 0) {
        ygst_buf_append_str(out, " lookups=");
        ygst_buf_append_int(out, lookups);
    }
    ygst_buf_append_str(out, " attempts=");
    ygst_buf_append_int(out, attempts);
    ygst_buf_append_str(out, " ms=");
    ygst_buf_append_int(out, ms);
    ygst_buf_cstr(out);
    return out->oom ? -1 : 0;
}
