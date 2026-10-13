/*
 * Copyright 2026 优谷雅言 open.shengzhiai.com
 * SPDX-License-Identifier: Apache-2.0
 *
 * Per-phase timing of one evaluation (2.1.1, BRIEF C and D): the Server-Timing header of the compat
 * REST responses, the "timing" object the platform adds to a final JSON on request, the INFO
 * summary line and the DEBUG detail line. The Objective-C layer and the integration driver record
 * the phases; everything that turns them into text is here, so that the Linux tests cover it.
 */
#include "ygst_core.h"

#include <stdio.h>
#include <string.h>

void ygst_timing_init(ygst_timing *t) {
    if (!t) return;
    memset(t, 0, sizeof *t);
    t->mode = YGST_TM_NONE;
    t->upload_ms = -1;
    t->wait_ms = -1;
    t->download_ms = -1;
    t->audio_bytes = -1;
    t->upload_bytes = -1;
    t->queued_at_stop = -1;
    t->wire_bytes = -1;
    t->body_bytes = -1;
    t->server_total_ms = -1;
    t->server_engine_ms = -1;
    t->server_platform_ms = -1;
}

int ygst_timing_wanted(int log_level) {
    return log_level >= 2;
}

/* ------------------------------------------------------------------ Server-Timing */

static int is_ows(char c) {
    return c == ' ' || c == '\t';
}

/* The n bytes at s equal word (lower case) ignoring ASCII case. */
static int word_eq(const char *s, size_t n, const char *word) {
    size_t i;
    if (strlen(word) != n) return 0;
    for (i = 0; i < n; i++) {
        char c = s[i];
        if (c >= 'A' && c <= 'Z') c = (char)(c + 32);
        if (c != word[i]) return 0;
    }
    return 1;
}

/* A plain non-negative decimal (5321, 12.75) in the n bytes at s, rounded half up to whole
 * milliseconds without the C locale's strtod. -1 when it is not one. */
static long long decimal_ms(const char *s, size_t n) {
    long long whole = 0;
    size_t i = 0;
    int digits = 0;
    int up = 0;
    while (i < n && s[i] >= '0' && s[i] <= '9') {
        if (whole > 900000000000000LL) return -1;
        whole = whole * 10 + (s[i] - '0');
        digits++;
        i++;
    }
    if (i < n && s[i] == '.') {
        i++;
        if (i < n && s[i] >= '0' && s[i] <= '9') up = s[i] >= '5';
        while (i < n && s[i] >= '0' && s[i] <= '9') {
            digits++;
            i++;
        }
    }
    if (!digits || i != n) return -1;
    return whole + up;
}

int ygst_server_timing_parse(const char *value, ygst_timing *t) {
    const char *p = value;
    unsigned found = 0;
    if (!value || !t) return 0;
    for (;;) {
        const char *name;
        size_t name_n;
        long long dur = -1;
        while (*p == ',' || is_ows(*p)) p++;
        if (!*p) break;
        name = p;
        while (*p && *p != ';' && *p != ',' && !is_ows(*p)) p++;
        name_n = (size_t)(p - name);
        for (;;) { /* ;param=value, value a token or a quoted string */
            const char *pn;
            const char *pv;
            size_t pn_n;
            size_t pv_n;
            while (is_ows(*p)) p++;
            if (*p != ';') break;
            p++;
            while (is_ows(*p)) p++;
            pn = p;
            while (*p && *p != '=' && *p != ';' && *p != ',' && !is_ows(*p)) p++;
            pn_n = (size_t)(p - pn);
            while (is_ows(*p)) p++;
            if (*p != '=') continue;
            p++;
            while (is_ows(*p)) p++;
            if (*p == '"') {
                pv = ++p;
                while (*p && *p != '"') {
                    if (*p == '\\' && p[1]) p++;
                    p++;
                }
                pv_n = (size_t)(p - pv);
                if (*p == '"') p++;
            } else {
                pv = p;
                while (*p && *p != ';' && *p != ',' && !is_ows(*p)) p++;
                pv_n = (size_t)(p - pv);
            }
            if (word_eq(pn, pn_n, "dur")) dur = decimal_ms(pv, pv_n);
        }
        while (*p && *p != ',') p++; /* anything else of this metric */
        if (dur < 0) continue;
        if (word_eq(name, name_n, "total")) {
            t->server_total_ms = dur;
            found |= 1u;
        } else if (word_eq(name, name_n, "engine")) {
            t->server_engine_ms = dur;
            found |= 2u;
        } else if (word_eq(name, name_n, "platform")) {
            t->server_platform_ms = dur;
            found |= 4u;
        }
    }
    return (int)((found & 1u) + ((found >> 1) & 1u) + ((found >> 2) & 1u));
}

/* ------------------------------------------------------------------ timing object */

static void number_ms_member(ygst_span obj, const char *key, long long *out) {
    ygst_span v;
    double d;
    if (ygst_json_member(obj, key, &v) != 1 || ygst_json_type(v) != 'n' || ygst_json_number(v, &d) != 0) return;
    if (d >= 0 && d < 9.0e15) *out = (long long)(d + 0.5);
}

int ygst_timing_member(ygst_span root, ygst_timing *t, ygst_span *timing) {
    ygst_span v;
    if (timing) {
        timing->p = NULL;
        timing->n = 0;
    }
    if (!root.p || ygst_json_type(root) != 'o') return -1;
    if (ygst_json_member(root, "timing", &v) != 1 || ygst_json_type(v) != 'o') return 0;
    if (timing) *timing = v;
    if (t) {
        number_ms_member(v, "totalMs", &t->server_total_ms);
        number_ms_member(v, "engineMs", &t->server_engine_ms);
        number_ms_member(v, "platformMs", &t->server_platform_ms);
    }
    return 1;
}

/* ------------------------------------------------------------------ log lines */

void ygst_timing_set_fallback(ygst_timing *t, const char *reason) {
    size_t i = 0;
    size_t n = 0;
    if (!t) return;
    while (reason && reason[i]) {
        unsigned char lead = (unsigned char)reason[i];
        size_t len = lead < 0x80 ? 1 : (lead >= 0xF0 ? 4 : (lead >= 0xE0 ? 3 : 2));
        size_t k;
        if (n + len >= sizeof t->fallback) break; /* whole UTF-8 sequences only */
        for (k = 0; k < len && reason[i + k]; k++) {
            char c = reason[i + k];
            if (c == '"') c = '\'';
            if (c == '\r' || c == '\n' || c == '\t') c = ' ';
            t->fallback[n++] = c;
        }
        i += k;
    }
    t->fallback[n] = 0;
}

static long long between(int64_t from, int64_t to) {
    if (!from || !to || to < from) return -1;
    return (long long)(to - from);
}

static void put_ms(ygst_buf *b, const char *key, long long ms) {
    ygst_buf_append_char(b, ' ');
    ygst_buf_append_str(b, key);
    ygst_buf_append_char(b, '=');
    if (ms < 0) {
        ygst_buf_append_char(b, '-');
        return;
    }
    ygst_buf_append_int(b, ms);
    ygst_buf_append_str(b, "ms");
}

static void put_bytes(ygst_buf *b, const char *key, long long n) {
    ygst_buf_append_char(b, ' ');
    ygst_buf_append_str(b, key);
    ygst_buf_append_char(b, '=');
    if (n < 0) {
        ygst_buf_append_char(b, '-');
        return;
    }
    ygst_buf_append_int(b, n);
    ygst_buf_append_char(b, 'B');
}

static const char *mode_name(ygst_tm_mode m) {
    switch (m) {
    case YGST_TM_REST:
        return "rest";
    case YGST_TM_WS:
        return "ws";
    case YGST_TM_WS_REST:
        return "ws>rest";
    default:
        return "local";
    }
}

static void put_audio(ygst_buf *b, const ygst_timing *t) {
    char s[48];
    if (t->audio_bytes >= 0 && t->audio_bytes_per_sec > 0) {
        long long bps = t->audio_bytes_per_sec;
        long long hundredths = (t->audio_bytes * 100 + bps / 2) / bps;
        snprintf(s, sizeof s, " audio=%lld.%02llds", hundredths / 100, hundredths % 100);
        ygst_buf_append_str(b, s);
    } else {
        ygst_buf_append_str(b, " audio=-");
    }
}

static void put_token(ygst_buf *b, const char *token_id) {
    ygst_buf_append_str(b, "timing tokenId=");
    ygst_buf_append_str(b, token_id ? token_id : "");
}

int ygst_timing_summary(const ygst_timing *t, const char *token_id, ygst_buf *out) {
    int ws;
    int64_t done;
    long long upload, wait, download;
    if (!t || !out) return -1;
    ws = t->mode == YGST_TM_WS;
    if (ws) {
        int64_t first = t->ws_final_first_ms;
        done = t->ws_final_ms;
        upload = between(t->stop_ms, t->ws_end_ms);
        wait = between(t->ws_end_ms, first ? first : done);
        download = between(first, done);
    } else {
        /* the split of the last attempt from the transport, placed after its start */
        int64_t body_sent = t->rest_start_ms && t->upload_ms >= 0 ? t->rest_start_ms + t->upload_ms : 0;
        done = t->rest_done_ms;
        upload = between(t->stop_ms, body_sent);
        wait = t->wait_ms;
        download = t->download_ms;
    }
    put_token(out, token_id);
    ygst_buf_append_str(out, " mode=");
    ygst_buf_append_str(out, mode_name(t->mode));
    put_audio(out, t);
    put_ms(out, "afterStop", between(t->stop_ms, t->delivered_ms));
    put_ms(out, "upload", upload);
    put_ms(out, "wait", wait);
    put_ms(out, "download", download);
    put_ms(out, "parse", between(done, t->parsed_ms));
    put_ms(out, "deliver", between(t->parsed_ms, t->delivered_ms));
    if (ws) {
        put_ms(out, "handshake", between(t->ws_connect_ms, t->ws_handshake_ms));
        put_bytes(out, "queued", t->queued_at_stop);
        put_bytes(out, "final", t->body_bytes);
        put_bytes(out, "wire", t->wire_bytes);
        if (t->deflate) ygst_buf_append_str(out, " deflate");
        if (t->attempts > 1) {
            ygst_buf_append_str(out, " connections=");
            ygst_buf_append_int(out, t->attempts);
        }
    } else {
        ygst_buf_append_str(out, " attempts=");
        ygst_buf_append_int(out, t->attempts);
        put_bytes(out, "sent", t->upload_bytes);
        put_bytes(out, "body", t->body_bytes);
        put_bytes(out, "wire", t->wire_bytes);
        if (t->encoding[0]) {
            ygst_buf_append_char(out, ' ');
            ygst_buf_append_str(out, t->encoding);
        }
    }
    if (t->server_total_ms >= 0) put_ms(out, "server.total", t->server_total_ms);
    if (t->server_engine_ms >= 0) put_ms(out, "server.engine", t->server_engine_ms);
    if (t->server_platform_ms >= 0) put_ms(out, "server.platform", t->server_platform_ms);
    if (t->replayed) ygst_buf_append_str(out, " replayed");
    if (!t->ok && t->err_id) {
        ygst_buf_append_str(out, " errId=");
        ygst_buf_append_int(out, t->err_id);
    }
    if (t->mode == YGST_TM_WS_REST && t->fallback[0]) {
        ygst_buf_append_str(out, " fallback=\"");
        ygst_buf_append_str(out, t->fallback);
        ygst_buf_append_char(out, '"');
    }
    ygst_buf_cstr(out);
    return out->oom ? -1 : 0;
}

int ygst_timing_phases(const ygst_timing *t, const char *token_id, ygst_buf *out) {
    static const char *const names[] = {"start",       "connect",      "handshake",    "params", "firstAudio",
                                        "stop",        "endWritten",   "finalFirstByte", "final", "requestStart",
                                        "responseDone", "parsed",      "delivered"};
    int64_t at[sizeof names / sizeof names[0]];
    int64_t origin = 0;
    size_t i;
    if (!t || !out) return -1;
    at[0] = t->start_ms;
    at[1] = t->ws_connect_ms;
    at[2] = t->ws_handshake_ms;
    at[3] = t->ws_params_ms;
    at[4] = t->ws_first_audio_ms;
    at[5] = t->stop_ms;
    at[6] = t->ws_end_ms;
    at[7] = t->ws_final_first_ms;
    at[8] = t->ws_final_ms;
    at[9] = t->rest_start_ms;
    at[10] = t->rest_done_ms;
    at[11] = t->parsed_ms;
    at[12] = t->delivered_ms;
    for (i = 0; i < sizeof names / sizeof names[0]; i++) {
        if (at[i] && (!origin || at[i] < origin)) origin = at[i];
    }
    put_token(out, token_id);
    ygst_buf_append_str(out, " phases");
    for (i = 0; i < sizeof names / sizeof names[0]; i++) {
        if (!at[i]) continue;
        ygst_buf_append_char(out, ' ');
        ygst_buf_append_str(out, names[i]);
        ygst_buf_append_str(out, "=+");
        ygst_buf_append_int(out, (long long)(at[i] - origin));
        ygst_buf_append_str(out, "ms");
    }
    ygst_buf_cstr(out);
    return out->oom ? -1 : 0;
}

/* " key=<number as the platform wrote it>" */
static void put_raw_number(ygst_buf *b, ygst_span obj, const char *key) {
    ygst_span v;
    if (ygst_json_member(obj, key, &v) != 1 || ygst_json_type(v) != 'n') return;
    ygst_buf_append_char(b, ' ');
    ygst_buf_append_str(b, key);
    ygst_buf_append_char(b, '=');
    ygst_buf_append(b, v.p, v.n);
}

int ygst_timing_stages(ygst_span timing, const char *token_id, ygst_buf *out) {
    ygst_buf members;
    ygst_span stages;
    int rc;
    if (!out) return -1;
    if (!timing.p || ygst_json_type(timing) != 'o') return 0;
    ygst_buf_init(&members);
    if (ygst_json_member(timing, "engineStages", &stages) == 1 && ygst_json_type(stages) == 'o') {
        size_t pos = 0;
        ygst_span key, value;
        while (ygst_json_object_next(stages, &pos, &key, &value) == 1) {
            if (ygst_json_type(value) != 'n') continue;
            ygst_buf_append_char(&members, ' ');
            ygst_json_string(key, &members);
            ygst_buf_append_char(&members, '=');
            ygst_buf_append(&members, value.p, value.n);
        }
    }
    put_raw_number(&members, timing, "receivedAt");
    put_raw_number(&members, timing, "wsFirstAudioAt");
    put_raw_number(&members, timing, "audioBytes");
    rc = members.oom ? -1 : 0;
    if (rc == 0 && members.len) {
        put_token(out, token_id);
        ygst_buf_append_str(out, " engineStages");
        ygst_buf_append(out, members.data, members.len);
        ygst_buf_cstr(out);
        rc = out->oom ? -1 : 1;
    }
    ygst_buf_free(&members);
    return rc;
}
