/*
 * Copyright 2026 优谷雅言 open.shengzhiai.com
 * SPDX-License-Identifier: Apache-2.0
 *
 * Realtime feedback (2.1.0, DESIGN 14.3, CONTRACT 5.2 and 5.5): the compat WebSocket round of one
 * evaluation. Handshake URL and its signature, parameter frame, classification of platform
 * frames, the words dedupe of the reconnect rule and the round controller that decides
 * connecting, streaming, ending, falling back to REST before end and reconnecting after end.
 * 2.1.1: the same round streams every recording by default; the parameter frame asks for reading
 * progress only with realtime_feedback.
 */
#include "ygst_core.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* ------------------------------------------------------------------ URL */

int ygst_url_encode(ygst_buf *out, const char *s) {
    static const char H[] = "0123456789ABCDEF";
    const unsigned char *p = (const unsigned char *)(s ? s : "");
    for (; *p; p++) {
        unsigned char c = *p;
        if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '.' ||
            c == '_' || c == '~') {
            ygst_buf_append_char(out, (char)c);
        } else {
            char e[3];
            e[0] = '%';
            e[1] = H[c >> 4];
            e[2] = H[c & 15];
            ygst_buf_append(out, e, 3);
        }
    }
    ygst_buf_cstr(out);
    return out->oom ? -1 : 0;
}

static int starts_ci(const char *s, const char *prefix) {
    while (*prefix) {
        char a = *s;
        if (a >= 'A' && a <= 'Z') a = (char)(a + 32);
        if (a != *prefix) return 0;
        s++;
        prefix++;
    }
    return 1;
}

int ygst_ws_base_url(const char *rest_base, char *out, size_t cap) {
    const char *scheme;
    const char *rest;
    size_t n;
    int w;
    if (!out || cap == 0) return -1;
    out[0] = 0;
    if (!rest_base) return -1;
    if (starts_ci(rest_base, "https://")) {
        scheme = "wss://";
        rest = rest_base + 8;
    } else if (starts_ci(rest_base, "http://")) {
        scheme = "ws://";
        rest = rest_base + 7;
    } else if (starts_ci(rest_base, "wss://")) {
        scheme = "wss://";
        rest = rest_base + 6;
    } else if (starts_ci(rest_base, "ws://")) {
        scheme = "ws://";
        rest = rest_base + 5;
    } else {
        return -1;
    }
    n = strlen(rest);
    while (n > 0 && rest[n - 1] == '/') n--;
    if (n == 0 || rest[0] == '/') return -1;
    w = snprintf(out, cap, "%s%.*s", scheme, (int)n, rest);
    if (w < 0 || (size_t)w >= cap) {
        out[0] = 0;
        return -1;
    }
    return 0;
}

/* Query parameters the handshake sets itself. */
static int own_query_name(const char *n) {
    return strcmp(n, "appKey") == 0 || strcmp(n, "timestamp") == 0 || strcmp(n, "nonce") == 0 ||
           strcmp(n, "idempotencyKey") == 0 || strcmp(n, "signature") == 0;
}

int ygst_ws_handshake_url(const char *rest_base, const char *core_type, const char *app_key, const char *secret,
                          int64_t timestamp_sec, const char *nonce, const char *idempotency_key,
                          const ygst_fields *extra, ygst_buf *out) {
    char base[1024];
    char ts[32];
    char sig[45];
    ygst_fields q;
    size_t i;
    int rc = -1;
    if (!out || !core_type || !core_type[0] || ygst_is_blank(app_key) || !secret || !secret[0] || !nonce ||
        !nonce[0] || !ygst_idempotency_key_ok(idempotency_key)) {
        return -1;
    }
    if (ygst_ws_base_url(rest_base, base, sizeof base) != 0) return -1;
    snprintf(ts, sizeof ts, "%lld", (long long)timestamp_sec);
    ygst_fields_init(&q);
    ygst_fields_set(&q, "appKey", app_key);
    ygst_fields_set(&q, "timestamp", ts);
    ygst_fields_set(&q, "nonce", nonce);
    ygst_fields_set(&q, "idempotencyKey", idempotency_key);
    if (extra) {
        for (i = 0; i < extra->count; i++) {
            const ygst_field *f = &extra->items[i];
            if (!f->name || !f->name[0] || !f->value || !f->value[0] || own_query_name(f->name)) continue;
            ygst_fields_set(&q, f->name, f->value);
        }
    }
    if (q.oom || ygst_sign(&q, secret, sig) != 0) goto done;
    ygst_buf_append_str(out, base);
    ygst_buf_append_char(out, '/');
    ygst_url_encode(out, core_type);
    for (i = 0; i < q.count; i++) {
        ygst_buf_append_char(out, i == 0 ? '?' : '&');
        ygst_url_encode(out, q.items[i].name);
        ygst_buf_append_char(out, '=');
        ygst_url_encode(out, q.items[i].value);
    }
    ygst_buf_append_str(out, "&signature=");
    ygst_url_encode(out, sig);
    ygst_buf_cstr(out);
    rc = out->oom ? -1 : 0;
done:
    ygst_fields_free(&q);
    return rc;
}

/* ------------------------------------------------------------------ parameter frame */

/* The platform reads these names as commands (cmd, end, data) or the frame sets them itself. */
static int reserved_frame_name(const char *n, int flags) {
    return strcmp(n, "cmd") == 0 || strcmp(n, "end") == 0 || strcmp(n, "data") == 0 ||
           strcmp(n, "realtime_feedback") == 0 || strcmp(n, "idempotencyKey") == 0 ||
           ((flags & YGST_WSP_TIMING) && strcmp(n, "timing") == 0);
}

int ygst_ws_param_frame(const ygst_fields *fields, const char *idempotency_key, int flags, ygst_buf *out) {
    size_t i;
    int skipped = 0;
    if (!fields || !out || fields->oom || !ygst_idempotency_key_ok(idempotency_key)) return -1;
    ygst_buf_append_char(out, '{');
    for (i = 0; i < fields->count; i++) {
        const ygst_field *f = &fields->items[i];
        if (!f->name || reserved_frame_name(f->name, flags)) {
            skipped++;
            continue;
        }
        ygst_json_escape(out, f->name);
        ygst_buf_append_char(out, ':');
        ygst_json_escape(out, f->value ? f->value : "");
        ygst_buf_append_char(out, ',');
    }
    /* 2.1.1: progress frames only when the app asked for them, the timing object only for the log */
    if (flags & YGST_WSP_REALTIME_FEEDBACK) ygst_buf_append_str(out, "\"realtime_feedback\":true,");
    if (flags & YGST_WSP_TIMING) ygst_buf_append_str(out, "\"timing\":1,");
    ygst_buf_append_str(out, "\"idempotencyKey\":");
    ygst_json_escape(out, idempotency_key);
    ygst_buf_append_char(out, '}');
    ygst_buf_cstr(out);
    return out->oom ? -1 : skipped;
}

/* ------------------------------------------------------------------ frames */

/* An integer member, also when a gateway quoted it. 1 found, 0 absent or not an integer. */
static int int_member(ygst_span obj, const char *key, long long *out) {
    ygst_span v;
    if (ygst_json_member(obj, key, &v) != 1) return 0;
    if (ygst_json_type(v) == 's') {
        ygst_buf b;
        int ok = 0;
        ygst_buf_init(&b);
        if (ygst_json_string(v, &b) == 0 && b.len > 0 && b.len < 20) {
            char *end = NULL;
            long long x = strtoll(ygst_buf_cstr(&b), &end, 10);
            if (end && *end == 0) {
                *out = x;
                ok = 1;
            }
        }
        ygst_buf_free(&b);
        return ok;
    }
    return ygst_json_int(v, out) == 0;
}

static int bool_member(ygst_span obj, const char *key) {
    ygst_span v;
    int b = 0;
    if (ygst_json_member(obj, key, &v) != 1 || ygst_json_bool(v, &b) != 0) return 0;
    return b;
}

static int count_value(long long v) {
    if (v < 0) return -1;
    return v > 2147483647LL ? 2147483647 : (int)v;
}

/* The event name when it is a short JSON string, else "". */
static void event_name(ygst_span ev, char *out, size_t cap) {
    ygst_buf b;
    out[0] = 0;
    if (ygst_json_type(ev) != 's') return;
    ygst_buf_init(&b);
    if (ygst_json_string(ev, &b) == 0 && b.len < cap) memcpy(out, ygst_buf_cstr(&b), b.len + 1);
    ygst_buf_free(&b);
}

static void parse_progress(ygst_ws_frame *f) {
    ygst_span r, list, item;
    long long v;
    size_t pos = 0;
    int read = 0, matched = 0, total = 0;
    if (ygst_json_member(f->root, "result", &r) != 1 || ygst_json_type(r) != 'o') return;
    f->result = r;
    if (int_member(r, "bytes", &v) && v >= 0) f->bytes = v;
    f->read_units = int_member(r, "readUnits", &v) ? count_value(v) : -1;
    f->matched_units = int_member(r, "matchedUnits", &v) ? count_value(v) : -1;
    f->total_units = int_member(r, "totalUnits", &v) ? count_value(v) : -1;
    /* every unit with its state */
    if (ygst_json_member(r, "progress", &list) == 1 && ygst_json_type(list) == 'a') {
        f->has_progress = 1;
        while (ygst_json_array_next(list, &pos, &item) == 1) {
            total++;
            if (ygst_json_type(item) != 'o') continue;
            if (bool_member(item, "read")) read++;
            if (bool_member(item, "matched")) matched++;
        }
        if (f->read_units < 0) f->read_units = read;
        if (f->matched_units < 0) f->matched_units = matched;
        if (f->total_units < 0) f->total_units = total;
    }
    /* Shengtong items of the units read since the previous frame */
    if (ygst_json_member(r, "words", &list) == 1 && ygst_json_type(list) == 'a') {
        f->has_words = 1;
        f->words = list;
        pos = 0;
        while (ygst_json_array_next(list, &pos, &item) == 1) f->word_count++;
    }
}

static void parse_final(ygst_ws_frame *f) {
    ygst_span r;
    if (ygst_json_member(f->root, "result", &r) == 1) f->result = r;
    f->replayed = bool_member(f->root, "replayed");
}

int ygst_ws_frame_parse(const char *text, size_t n, ygst_ws_frame *f) {
    ygst_span root, v;
    if (!f) return -1;
    memset(f, 0, sizeof *f);
    f->kind = YGST_WSF_INVALID;
    f->bytes = -1;
    f->read_units = -1;
    f->matched_units = -1;
    f->total_units = -1;
    if (!text || ygst_json_parse(text, n, &root) != 0 || ygst_json_type(root) != 'o') return -1;
    f->root = root;
    if (ygst_json_member(root, "event", &v) == 1) {
        char ev[16];
        event_name(v, ev, sizeof ev);
        if (strcmp(ev, "connected") == 0) {
            f->kind = YGST_WSF_CONNECTED;
        } else if (strcmp(ev, "started") == 0) {
            f->kind = YGST_WSF_STARTED;
        } else if (strcmp(ev, "pong") == 0) {
            f->kind = YGST_WSF_PONG;
        } else if (strcmp(ev, "error") == 0) {
            long long code = 0;
            f->kind = YGST_WSF_ERROR;
            if (int_member(root, "code", &code) && code > 0) f->code = count_value(code);
        } else if (strcmp(ev, "result") == 0) {
            f->kind = YGST_WSF_FINAL;
            parse_final(f);
        } else {
            f->kind = YGST_WSF_OTHER;
        }
        return 0;
    }
    if (ygst_json_member(root, "eof", &v) == 1) {
        long long eof = -1;
        if (int_member(root, "eof", &eof) && eof == 0) {
            f->kind = YGST_WSF_PROGRESS;
            parse_progress(f);
        } else {
            f->kind = YGST_WSF_FINAL;
            parse_final(f);
        }
        return 0;
    }
    if (ygst_json_member(root, "result", NULL) == 1 || ygst_json_member(root, "recordId", NULL) == 1) {
        f->kind = YGST_WSF_FINAL;
        parse_final(f);
        return 0;
    }
    f->kind = YGST_WSF_OTHER;
    return 0;
}

int ygst_ws_classify_error(ygst_span frame, ygst_classification *c) {
    static const char *const KEYS[] = {"message", "msg", "error", NULL};
    const char *const *k;
    long long code = 0;
    c->success = 0;
    c->http_status = 0;
    c->local_code = 0;
    c->biz_code = 0;
    c->has_audio_url = 0;
    c->has_record_id = 0;
    c->record_id_raw.p = NULL;
    c->record_id_raw.n = 0;
    c->root.p = NULL;
    c->root.n = 0;
    c->result.p = NULL;
    c->result.n = 0;
    ygst_buf_reset(&c->record_id);
    ygst_buf_reset(&c->audio_url);
    ygst_buf_reset(&c->message);
    if (ygst_json_type(frame) == 'o') {
        if (int_member(frame, "code", &code) && code > 0) c->biz_code = count_value(code);
        for (k = KEYS; *k; k++) {
            ygst_span v;
            if (ygst_json_member(frame, *k, &v) == 1 && ygst_json_type(v) == 's') {
                ygst_json_string(v, &c->message);
                break;
            }
        }
    }
    if (!c->message.len) ygst_buf_append_str(&c->message, "error frame without a message");
    ygst_buf_cstr(&c->message);
    ygst_buf_cstr(&c->record_id);
    ygst_buf_cstr(&c->audio_url);
    /* without a business code the platform failed while evaluating: replay like a 5xx */
    c->retryable = c->biz_code ? ygst_is_retryable(0, c->biz_code, 0) : 1;
    c->err_id = ygst_errid_for(0, c->biz_code, 0, c->retryable);
    return c->message.oom ? -1 : 0;
}

/* ------------------------------------------------------------------ words dedupe */

void ygst_words_dedupe_init(ygst_words_dedupe *d) {
    memset(d, 0, sizeof *d);
}

int ygst_words_dedupe_skip(ygst_words_dedupe *d, int read_units, int word_count) {
    long start;
    long skip;
    if (word_count <= 0) return 0;
    if (read_units < 0) read_units = d->delivered + word_count; /* position unknown: the next units */
    start = (long)read_units - word_count;
    skip = d->delivered > start ? (long)d->delivered - start : 0;
    if (skip >= word_count) return word_count; /* every item was delivered already */
    if (read_units > d->delivered) d->delivered = read_units;
    return (int)skip;
}

/* ------------------------------------------------------------------ round controller */

void ygst_rt_init(ygst_rt *rt) {
    memset(rt, 0, sizeof *rt);
    ygst_retry_policy_default(&rt->policy);
    rt->connect_timeout_ms = 20000;
    rt->attempt_timeout_ms = 60000;
    rt->ping_interval_ms = 15000;
    rt->pong_timeout_ms = 30000;
    rt->max_frame_bytes = YGST_WS_MAX_FRAME_BYTES;
    rt->state = YGST_RT_IDLE;
    rt->frame.kind = YGST_WSF_INVALID;
    ygst_words_dedupe_init(&rt->dedupe);
    ygst_classification_init(&rt->cls);
    ygst_buf_init(&rt->failure);
}

void ygst_rt_free(ygst_rt *rt) {
    ygst_classification_free(&rt->cls);
    ygst_buf_free(&rt->failure);
}

static ygst_rt_actions no_actions(void) {
    ygst_rt_actions a;
    memset(&a, 0, sizeof a);
    return a;
}

static int on_connection(ygst_rt_state s) {
    return s == YGST_RT_CONNECTING || s == YGST_RT_STARTING || s == YGST_RT_STREAMING || s == YGST_RT_ENDING;
}

static void clear_timers(ygst_rt *rt) {
    rt->deadline_ms = 0;
    rt->next_ping_ms = 0;
    rt->pong_deadline_ms = 0;
}

static void note_failure(ygst_rt *rt, const char *why) {
    ygst_buf_reset(&rt->failure);
    ygst_buf_append_str(&rt->failure, why ? why : "");
    ygst_buf_cstr(&rt->failure);
}

static void open_connection(ygst_rt *rt, ygst_rt_actions *a, int64_t now) {
    a->connect = 1;
    rt->state = YGST_RT_CONNECTING;
    rt->connections++;
    rt->sent = 0;
    clear_timers(rt);
    rt->deadline_ms = now + rt->connect_timeout_ms;
}

static void send_pending_audio(ygst_rt *rt, ygst_rt_actions *a) {
    if (rt->total > rt->sent) {
        a->audio_from = rt->sent;
        a->audio_to = rt->total;
        rt->sent = rt->total;
    }
}

static void start_streaming(ygst_rt *rt, ygst_rt_actions *a, int64_t now) {
    rt->state = YGST_RT_STREAMING;
    clear_timers(rt);
    if (rt->ping_interval_ms > 0) rt->next_ping_ms = now + rt->ping_interval_ms;
    send_pending_audio(rt, a);
}

/* All audio from the current offset, then end. The first end starts the attempts of the REST
 * call controller; a reconnect only sends it again. */
static void send_end(ygst_rt *rt, ygst_rt_actions *a, int64_t now) {
    send_pending_audio(rt, a);
    a->send_end = 1;
    if (!rt->end_sent) {
        rt->end_sent = 1;
        ygst_call_init(&rt->call, &rt->policy, rt->seed, now);
    }
    rt->state = YGST_RT_ENDING;
    /* no heartbeat while the platform evaluates: the result timeout bounds the wait, as a REST
     * attempt's read timeout does */
    clear_timers(rt);
    rt->deadline_ms = now + rt->attempt_timeout_ms;
}

/* Before end: the platform never evaluated this round, REST evaluates it with the same tokenId. */
static void fall_back(ygst_rt *rt, ygst_rt_actions *a, const char *why) {
    a->close = 1;
    rt->state = YGST_RT_FALLBACK;
    clear_timers(rt);
    note_failure(rt, why);
    if (rt->end_requested) a->fallback = 1;
}

/* After end: rt->cls holds the outcome of this attempt; the REST call controller decides. */
static void attempt_failed(ygst_rt *rt, ygst_rt_actions *a, int64_t now) {
    ygst_action next = ygst_call_next(&rt->call, &rt->cls, -1, now);
    a->close = 1;
    clear_timers(rt);
    note_failure(rt, ygst_buf_cstr(&rt->cls.message));
    if (next.kind == YGST_ACT_RETRY) {
        rt->state = YGST_RT_BACKOFF;
        rt->reconnect_at_ms = now + next.delay_ms;
        a->retry = 1;
        a->delay_ms = next.delay_ms;
        return;
    }
    rt->state = YGST_RT_DONE;
    a->fail = 1;
    a->err_id = next.kind == YGST_ACT_FAIL ? next.err_id : YGST_LOCAL_PROTOCOL;
}

static void connection_failed(ygst_rt *rt, ygst_rt_actions *a, int local_code, int http_status, const char *message,
                              int64_t now) {
    ygst_outcome o;
    if (!rt->end_sent) {
        fall_back(rt, a, message);
        return;
    }
    memset(&o, 0, sizeof o);
    o.local_code = local_code;
    o.http_status = local_code ? 0 : http_status;
    o.retry_after_ms = -1;
    o.local_message = message;
    ygst_classify(&o, &rt->cls);
    if (!local_code && http_status > 0 && message && message[0]) {
        /* a rejected handshake has no body: say so instead of repeating the status */
        ygst_buf_reset(&rt->cls.message);
        ygst_buf_append_str(&rt->cls.message, message);
        ygst_buf_cstr(&rt->cls.message);
    }
    attempt_failed(rt, a, now);
}

ygst_rt_actions ygst_rt_start(ygst_rt *rt, int64_t now_ms) {
    ygst_rt_actions a = no_actions();
    if (rt->state != YGST_RT_IDLE) return a;
    if (rt->max_frame_bytes == 0) rt->max_frame_bytes = YGST_WS_MAX_FRAME_BYTES;
    open_connection(rt, &a, now_ms);
    return a;
}

ygst_rt_actions ygst_rt_on_audio(ygst_rt *rt, size_t total_bytes, int64_t now_ms) {
    ygst_rt_actions a = no_actions();
    (void)now_ms;
    if (rt->end_requested) return a; /* the round audio is complete */
    if (total_bytes > rt->total) rt->total = total_bytes;
    if (rt->state == YGST_RT_STREAMING) send_pending_audio(rt, &a);
    return a;
}

ygst_rt_actions ygst_rt_on_stop(ygst_rt *rt, size_t total_bytes, int64_t now_ms) {
    ygst_rt_actions a = no_actions();
    if (rt->end_requested || rt->state == YGST_RT_DONE) return a;
    if (total_bytes > rt->total) rt->total = total_bytes;
    rt->end_requested = 1;
    switch (rt->state) {
    case YGST_RT_IDLE:
    case YGST_RT_FALLBACK:
        rt->state = YGST_RT_FALLBACK;
        a.fallback = 1;
        break;
    case YGST_RT_STREAMING:
        send_end(rt, &a, now_ms);
        break;
    default:
        break; /* CONNECTING, STARTING: end goes out right after "started" */
    }
    return a;
}

ygst_rt_actions ygst_rt_on_text(ygst_rt *rt, const char *text, size_t n, int64_t now_ms) {
    ygst_rt_actions a = no_actions();
    if (!on_connection(rt->state)) return a;
    if (ygst_ws_frame_parse(text, n, &rt->frame) != 0) return a;
    switch (rt->frame.kind) {
    case YGST_WSF_CONNECTED:
        if (rt->state == YGST_RT_CONNECTING) {
            a.send_params = 1;
            rt->state = YGST_RT_STARTING;
        }
        break;
    case YGST_WSF_STARTED:
        if (rt->state == YGST_RT_STARTING) {
            if (rt->end_sent || rt->end_requested) {
                send_end(rt, &a, now_ms);
            } else {
                start_streaming(rt, &a, now_ms);
            }
        }
        break;
    case YGST_WSF_PROGRESS:
        /* delivered only with new words; empty words and bytes-only frames are not */
        if (rt->frame.word_count > 0) {
            int skip = ygst_words_dedupe_skip(&rt->dedupe, rt->frame.read_units, rt->frame.word_count);
            if (skip < rt->frame.word_count) {
                a.progress = 1;
                a.words_skip = skip;
            } else {
                a.progress_held = 1;
            }
        }
        break;
    case YGST_WSF_FINAL:
        if (rt->state != YGST_RT_ENDING) {
            if (!rt->end_sent) fall_back(rt, &a, "result frame before end");
            break;
        }
        {
            ygst_outcome o;
            memset(&o, 0, sizeof o);
            o.http_status = 200;
            o.body = text;
            o.body_len = n;
            o.retry_after_ms = -1;
            o.replayed = rt->frame.replayed;
            ygst_classify(&o, &rt->cls);
        }
        if (rt->cls.success) {
            (void)ygst_call_next(&rt->call, &rt->cls, -1, now_ms);
            rt->state = YGST_RT_DONE;
            clear_timers(rt);
            a.close = 1;
            a.final = 1;
        } else {
            attempt_failed(rt, &a, now_ms);
        }
        break;
    case YGST_WSF_ERROR:
        ygst_ws_classify_error(rt->frame.root, &rt->cls);
        if (!rt->end_sent) {
            fall_back(rt, &a, ygst_buf_cstr(&rt->cls.message));
        } else {
            attempt_failed(rt, &a, now_ms);
        }
        break;
    default:
        break; /* pong, other events */
    }
    return a;
}

ygst_rt_actions ygst_rt_on_pong(ygst_rt *rt, int64_t now_ms) {
    ygst_rt_actions a = no_actions();
    if (rt->state == YGST_RT_STREAMING && rt->pong_deadline_ms) {
        rt->pong_deadline_ms = 0;
        if (rt->ping_interval_ms > 0) rt->next_ping_ms = now_ms + rt->ping_interval_ms;
    }
    return a;
}

ygst_rt_actions ygst_rt_on_failure(ygst_rt *rt, int local_code, int http_status, const char *message,
                                   int64_t now_ms) {
    ygst_rt_actions a = no_actions();
    if (!on_connection(rt->state)) return a;
    connection_failed(rt, &a, local_code, http_status, message, now_ms);
    return a;
}

ygst_rt_actions ygst_rt_on_tick(ygst_rt *rt, int64_t now_ms) {
    ygst_rt_actions a = no_actions();
    char msg[96];
    if (rt->state == YGST_RT_BACKOFF) {
        if (now_ms >= rt->reconnect_at_ms) open_connection(rt, &a, now_ms);
        return a;
    }
    if (!on_connection(rt->state)) return a;
    if (rt->deadline_ms && now_ms >= rt->deadline_ms) {
        int ending = rt->state == YGST_RT_ENDING;
        if (ending) {
            snprintf(msg, sizeof msg, "timeout: no result within %d ms", rt->attempt_timeout_ms);
        } else {
            snprintf(msg, sizeof msg, "timeout: not started within %d ms", rt->connect_timeout_ms);
        }
        connection_failed(rt, &a, ending ? YGST_LOCAL_RESULT_TIMEOUT : YGST_LOCAL_TIMEOUT, 0, msg, now_ms);
        return a;
    }
    if (rt->pong_deadline_ms && now_ms >= rt->pong_deadline_ms) {
        snprintf(msg, sizeof msg, "timeout: no pong within %d ms", rt->pong_timeout_ms);
        connection_failed(rt, &a, YGST_LOCAL_TIMEOUT, 0, msg, now_ms);
        return a;
    }
    if (rt->state == YGST_RT_STREAMING && rt->next_ping_ms && !rt->pong_deadline_ms && now_ms >= rt->next_ping_ms) {
        a.send_ping = 1;
        rt->next_ping_ms = 0;
        rt->pong_deadline_ms = now_ms + rt->pong_timeout_ms;
    }
    return a;
}

void ygst_rt_cancel(ygst_rt *rt) {
    rt->state = YGST_RT_DONE;
    clear_timers(rt);
}

int ygst_rt_active(const ygst_rt *rt) {
    return on_connection(rt->state) || rt->state == YGST_RT_BACKOFF;
}

int ygst_rt_error_message(const ygst_rt *rt, int err_id, ygst_buf *out) {
    return ygst_error_message(&rt->cls, err_id, rt->call.attempt, out);
}
