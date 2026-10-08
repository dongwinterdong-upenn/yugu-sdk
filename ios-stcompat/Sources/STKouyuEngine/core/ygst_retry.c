/*
 * Copyright 2026 优谷雅言 open.shengzhiai.com
 * SPDX-License-Identifier: Apache-2.0
 *
 * Retry policy (DESIGN 2.3), Shengtong errId mapping (DESIGN 6.2), response classification and
 * the per-call controller that decides between delivering, retrying and failing. The Objective-C
 * layer executes the decisions with NSURLSession; the Linux tests execute them with sockets.
 */
#include "ygst_core.h"

#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* ------------------------------------------------------------------ table lookups */

static const ygst_error_entry *find_in(const ygst_error_entry *t, size_t n, int code) {
    size_t i;
    for (i = 0; i < n; i++) {
        if (t[i].code == code) return &t[i];
    }
    return NULL;
}

const ygst_error_entry *ygst_error_find(int code) {
    return find_in(ygst_table_errors, ygst_table_errors_count, code);
}

const ygst_error_entry *ygst_warning_find(int code) {
    return find_in(ygst_table_warnings, ygst_table_warnings_count, code);
}

const ygst_error_entry *ygst_local_find(int code) {
    return find_in(ygst_table_local, ygst_table_local_count, code);
}

const char *ygst_compat_message(int err_id) {
    size_t i;
    for (i = 0; i < ygst_table_compat_count; i++) {
        if (ygst_table_compat[i].err_id == err_id) return ygst_table_compat[i].message;
    }
    return NULL;
}

int ygst_http_retryable(int status) {
    size_t i;
    for (i = 0; i < ygst_table_retryable_http_count; i++) {
        if (ygst_table_retryable_http[i] == status) return 1;
    }
    return 0;
}

/* ------------------------------------------------------------------ RNG (splitmix64) */

void ygst_rng_seed(ygst_rng *r, uint64_t seed) {
    r->s = seed;
}

uint64_t ygst_rng_next(ygst_rng *r) {
    uint64_t z = (r->s += 0x9E3779B97F4A7C15ull);
    z = (z ^ (z >> 30)) * 0xBF58476D1CE4E5B9ull;
    z = (z ^ (z >> 27)) * 0x94D049BB133111EBull;
    return z ^ (z >> 31);
}

double ygst_rng_uniform(ygst_rng *r) {
    return (double)(ygst_rng_next(r) >> 11) * (1.0 / 9007199254740992.0);
}

/* ------------------------------------------------------------------ policy */

void ygst_retry_policy_default(ygst_retry_policy *p) {
    memset(p, 0, sizeof *p);
    p->max_retries = 2;
    p->initial_delay_ms = 200;
    p->multiplier = 2.0;
    p->max_delay_ms = 4000;
    p->jitter = 0.3;
    p->respect_retry_after = 1;
    p->max_retry_after_ms = 30000;
    p->total_timeout_ms = 300000;
    p->auto_retry = 0;
    p->max_auto_retries = 2;
    p->err_ids[0] = YGST_ERRID_RETRYABLE;
    p->n_err_ids = 1;
}

int ygst_retry_policy_add_err_id(ygst_retry_policy *p, long long err_id) {
    if (err_id <= 0 || err_id > 2147483647LL) return -1;
    if (ygst_retry_policy_has_err_id(p, (int)err_id)) return 0;
    if (p->n_err_ids >= YGST_MAX_ERR_IDS) return -1;
    p->err_ids[p->n_err_ids++] = (int)err_id;
    return 0;
}

int ygst_retry_policy_has_err_id(const ygst_retry_policy *p, int err_id) {
    int i;
    for (i = 0; i < p->n_err_ids; i++) {
        if (p->err_ids[i] == err_id) return 1;
    }
    return 0;
}

long ygst_backoff_delay_ms(const ygst_retry_policy *p, int n, ygst_rng *rng) {
    double base;
    double factor = 1.0;
    double d;
    if (n < 1) n = 1;
    base = (double)p->initial_delay_ms * pow(p->multiplier > 0 ? p->multiplier : 1.0, (double)(n - 1));
    if (base > p->max_delay_ms) base = p->max_delay_ms;
    if (p->jitter > 0 && rng) factor = 1.0 + (ygst_rng_uniform(rng) * 2.0 - 1.0) * p->jitter;
    d = base * factor;
    if (d < 0) d = 0;
    return (long)(d + 0.5);
}

/* days since 1970-01-01 for a proleptic Gregorian date (H. Hinnant, days_from_civil) */
static long long days_from_civil(long long y, unsigned m, unsigned d) {
    long long era;
    unsigned yoe, doy, doe;
    y -= m <= 2;
    era = (y >= 0 ? y : y - 399) / 400;
    yoe = (unsigned)(y - era * 400);
    doy = (153 * (m + (m > 2 ? -3 : 9)) + 2) / 5 + d - 1;
    doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    return era * 146097 + (long long)doe - 719468;
}

int ygst_parse_retry_after(const char *value, int64_t now_ms, long *out_ms) {
    static const char *const MON[] = {"Jan", "Feb", "Mar", "Apr", "May", "Jun",
                                      "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};
    char v[64];
    size_t n;
    size_t i;
    if (!value || !out_ms) return -1;
    n = ygst_trim_copy(value, v, sizeof v);
    if (n == 0) return -1;
    for (i = 0; i < n && v[i] >= '0' && v[i] <= '9'; i++) {
    }
    if (i == n) {
        long secs = 0;
        for (i = 0; i < n; i++) {
            if (secs > 100000000L) return -1;
            secs = secs * 10 + (v[i] - '0');
        }
        *out_ms = secs * 1000L;
        return 0;
    }
    /* IMF-fixdate: "Sun, 06 Nov 1994 08:49:37 GMT" */
    if (n == 29 && v[3] == ',' && v[4] == ' ' && v[7] == ' ' && v[11] == ' ' && v[16] == ' ' && v[19] == ':' &&
        v[22] == ':' && v[25] == ' ' && strcmp(v + 26, "GMT") == 0) {
        unsigned day, mon = 0, hh, mm, ss;
        long long year;
        long long t;
        for (i = 0; i < 12; i++) {
            if (strncmp(v + 8, MON[i], 3) == 0) mon = (unsigned)i + 1;
        }
        if (!mon) return -1;
        day = (unsigned)((v[5] - '0') * 10 + (v[6] - '0'));
        year = (v[12] - '0') * 1000 + (v[13] - '0') * 100 + (v[14] - '0') * 10 + (v[15] - '0');
        hh = (unsigned)((v[17] - '0') * 10 + (v[18] - '0'));
        mm = (unsigned)((v[20] - '0') * 10 + (v[21] - '0'));
        ss = (unsigned)((v[23] - '0') * 10 + (v[24] - '0'));
        if (day < 1 || day > 31 || hh > 23 || mm > 59 || ss > 60) return -1;
        t = days_from_civil(year, mon, day) * 86400LL + hh * 3600LL + mm * 60LL + ss;
        t = t * 1000LL - now_ms;
        *out_ms = t > 0 ? (long)(t > 2000000000LL ? 2000000000LL : t) : 0;
        return 0;
    }
    return -1;
}

/* ------------------------------------------------------------------ retry and errId rules */

int ygst_is_retryable(int local_code, int biz_code, int http_status) {
    const ygst_error_entry *e;
    if (local_code) {
        return local_code == YGST_LOCAL_NETWORK || local_code == YGST_LOCAL_TIMEOUT ||
               local_code == YGST_LOCAL_RESULT_TIMEOUT;
    }
    if (biz_code) {
        e = ygst_error_find(biz_code);
        if (e) return e->retryable;
    }
    return ygst_http_retryable(http_status);
}

int ygst_http_fallback_code(int status) {
    const char *cat = ygst_http_fallback_category(status);
    if (strcmp(cat, "AUTH") == 0) return 40100;
    if (strcmp(cat, "PERMISSION") == 0) return 40300;
    if (strcmp(cat, "NOT_FOUND") == 0) return 40400;
    if (strcmp(cat, "CONFLICT") == 0) return 40900;
    if (strcmp(cat, "RATE_LIMIT") == 0) return 42900;
    if (strcmp(cat, "SERVER") == 0) return status == 501 ? 50010 : 50000;
    if (strcmp(cat, "UPSTREAM") == 0) return 50200;
    if (strcmp(cat, "TIMEOUT") == 0) return YGST_ERRID_RETRYABLE;
    if (strcmp(cat, "INVALID_PARAM") == 0) return 40001;
    return YGST_LOCAL_PROTOCOL;
}

int ygst_errid_for(int local_code, int biz_code, int http_status, int retryable) {
    if (local_code >= 60001 && local_code <= 60009) return local_code;
    if (retryable) return YGST_ERRID_RETRYABLE;
    if (biz_code) return biz_code;
    if (local_code) return local_code;
    return ygst_http_fallback_code(http_status);
}

/* ------------------------------------------------------------------ classification */

void ygst_classification_init(ygst_classification *c) {
    memset(c, 0, sizeof *c);
    ygst_buf_init(&c->record_id);
    ygst_buf_init(&c->audio_url);
    ygst_buf_init(&c->message);
}

void ygst_classification_free(ygst_classification *c) {
    ygst_buf_free(&c->record_id);
    ygst_buf_free(&c->audio_url);
    ygst_buf_free(&c->message);
}

static int member_int(ygst_span obj, const char *key, long long *out) {
    ygst_span v;
    if (ygst_json_member(obj, key, &v) != 1) return 0;
    if (ygst_json_type(v) == 's') {
        /* some gateways quote numeric codes */
        ygst_buf b;
        int ok = 0;
        ygst_buf_init(&b);
        if (ygst_json_string(v, &b) == 0 && b.len > 0 && b.len < 12) {
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

/* First of the keys whose value is a JSON string. */
static int first_string(ygst_span obj, const char *const *keys, ygst_buf *out) {
    for (; *keys; keys++) {
        ygst_span v;
        if (ygst_json_member(obj, *keys, &v) == 1 && ygst_json_type(v) == 's') {
            ygst_buf_reset(out);
            return ygst_json_string(v, out) == 0;
        }
    }
    return 0;
}

/* "[2001] signature mismatch": the compat engine code in a FastAPI detail, 3 to 6 digits. */
static int bracket_code(const char *msg) {
    long v = 0;
    int digits = 0;
    if (!msg) return 0;
    while (*msg == ' ' || *msg == '\t' || *msg == '\n' || *msg == '\r') msg++;
    if (*msg != '[') return 0;
    msg++;
    while (*msg >= '0' && *msg <= '9' && digits < 7) {
        v = v * 10 + (*msg - '0');
        msg++;
        digits++;
    }
    if (*msg != ']' || digits < 3 || digits > 6) return 0;
    return (int)v;
}

static void set_message(ygst_buf *m, const char *s) {
    ygst_buf_reset(m);
    ygst_buf_append_str(m, s ? s : "");
    ygst_buf_cstr(m);
}

int ygst_classify(const ygst_outcome *o, ygst_classification *c) {
    static const char *const MESSAGE_KEYS[] = {"message", "msg", "error", NULL};
    ygst_span root;
    int parsed = 0;
    long long code = 0;
    c->success = 0;
    c->http_status = o->http_status;
    c->local_code = o->local_code;
    c->biz_code = 0;
    c->has_audio_url = 0;
    c->has_record_id = 0;
    c->record_id_raw.p = NULL;
    c->record_id_raw.n = 0;
    c->result.p = NULL;
    c->result.n = 0;
    ygst_buf_reset(&c->record_id);
    ygst_buf_reset(&c->audio_url);
    ygst_buf_reset(&c->message);

    if (o->local_code || o->http_status <= 0) {
        const ygst_error_entry *e;
        if (!c->local_code) c->local_code = YGST_LOCAL_NETWORK;
        e = ygst_local_find(c->local_code);
        set_message(&c->message, (o->local_message && o->local_message[0]) ? o->local_message
                                                                            : (e ? e->message : "network error"));
        c->retryable = ygst_is_retryable(c->local_code, 0, 0);
        c->err_id = ygst_errid_for(c->local_code, 0, 0, c->retryable);
        return 0;
    }

    if (o->body && o->body_len) parsed = ygst_json_parse(o->body, o->body_len, &root) == 0 && ygst_json_type(root) == 'o';

    if (o->http_status >= 200 && o->http_status < 300) {
        ygst_span result, v;
        if (parsed && ygst_json_member(root, "result", &result) == 1) {
            ygst_span inner;
            c->success = 1;
            c->result = result;
            if (ygst_json_member(root, "recordId", &v) == 1) {
                c->has_record_id = 1;
                c->record_id_raw = v;
                if (ygst_json_type(v) == 's') {
                    ygst_json_string(v, &c->record_id);
                } else {
                    ygst_buf_append(&c->record_id, v.p, v.n);
                }
            }
            if (ygst_json_member(root, "audioUrl", &v) == 1) {
                if (ygst_json_type(v) == 's') c->has_audio_url = ygst_json_string(v, &c->audio_url) == 0;
            } else if (ygst_json_type(result) == 'o' && ygst_json_member(result, "audioUrl", &inner) == 1 &&
                       ygst_json_type(inner) == 's') {
                c->has_audio_url = ygst_json_string(inner, &c->audio_url) == 0;
            }
            if (c->has_audio_url && c->audio_url.len == 0) c->has_audio_url = 0;
            ygst_buf_cstr(&c->record_id);
            ygst_buf_cstr(&c->audio_url);
            c->retryable = 0;
            c->err_id = 0;
            return 0;
        }
        c->local_code = YGST_LOCAL_PROTOCOL;
        set_message(&c->message, parsed ? "invalid platform response: platform response has no result"
                                        : "invalid platform response: not a JSON object");
        c->retryable = 0;
        c->err_id = YGST_LOCAL_PROTOCOL;
        return 0;
    }

    if (parsed) {
        ygst_span detail;
        if (member_int(root, "code", &code) && code != 0) c->biz_code = (int)code;
        if (!c->biz_code && member_int(root, "errId", &code) && code != 0) c->biz_code = (int)code;
        first_string(root, MESSAGE_KEYS, &c->message);
        if (ygst_json_member(root, "detail", &detail) == 1) {
            if (ygst_json_type(detail) == 's') {
                ygst_buf d;
                ygst_buf_init(&d);
                ygst_json_string(detail, &d);
                if (!c->biz_code) c->biz_code = bracket_code(ygst_buf_cstr(&d));
                if (!c->message.len) set_message(&c->message, ygst_buf_cstr(&d));
                ygst_buf_free(&d);
            } else if (!c->message.len) {
                ygst_buf_reset(&c->message);
                ygst_buf_append(&c->message, detail.p, detail.n > 300 ? 300 : detail.n);
            }
        }
    }
    if (!c->message.len) {
        char tmp[32];
        snprintf(tmp, sizeof tmp, "HTTP %d", o->http_status);
        set_message(&c->message, tmp);
    }
    ygst_buf_cstr(&c->message);
    c->retryable = ygst_is_retryable(0, c->biz_code, o->http_status);
    c->err_id = ygst_errid_for(0, c->biz_code, o->http_status, c->retryable);
    return 0;
}

int ygst_error_message(const ygst_classification *c, int err_id, int attempts, ygst_buf *out) {
    const char *m = c->message.data ? (const char *)c->message.data : "";
    if (err_id == YGST_ERRID_RETRYABLE) {
        const char *base = ygst_compat_message(YGST_ERRID_RETRYABLE);
        ygst_buf_append_str(out, base ? base : "");
        ygst_buf_append_str(out, ": ");
        if (c->http_status > 0) {
            ygst_buf_append_str(out, "HTTP ");
            ygst_buf_append_int(out, c->http_status);
            if (c->biz_code) {
                ygst_buf_append_str(out, " code=");
                ygst_buf_append_int(out, c->biz_code);
            }
        } else {
            const ygst_error_entry *e = ygst_local_find(c->local_code);
            if (e) {
                ygst_buf_append_str(out, e->name);
            } else {
                ygst_buf_append_str(out, "code=");
                ygst_buf_append_int(out, c->local_code);
            }
        }
        if (m[0]) {
            ygst_buf_append_char(out, ' ');
            ygst_buf_append_str(out, m);
        }
        if (attempts > 0) {
            ygst_buf_append_str(out, " (attempts ");
            ygst_buf_append_int(out, attempts);
            ygst_buf_append_char(out, ')');
        }
    } else if (m[0]) {
        ygst_buf_append_str(out, m);
    } else {
        const char *cm = ygst_compat_message(err_id);
        const ygst_error_entry *e = cm ? NULL : ygst_error_find(err_id);
        if (!cm && !e) e = ygst_local_find(err_id);
        ygst_buf_append_str(out, cm ? cm : (e ? e->message : ""));
    }
    ygst_buf_cstr(out);
    return out->oom ? -1 : 0;
}

/* ------------------------------------------------------------------ controller */

void ygst_call_init(ygst_call *c, const ygst_retry_policy *p, uint64_t seed, int64_t now_ms) {
    memset(c, 0, sizeof *c);
    if (p) {
        c->policy = *p;
    } else {
        ygst_retry_policy_default(&c->policy);
    }
    ygst_rng_seed(&c->rng, seed);
    c->start_ms = now_ms;
}

static long apply_retry_after(const ygst_retry_policy *p, long delay, long retry_after_ms) {
    if (p->respect_retry_after && retry_after_ms >= 0) {
        long ra = retry_after_ms > p->max_retry_after_ms ? p->max_retry_after_ms : retry_after_ms;
        if (ra > delay) delay = ra;
    }
    return delay;
}

ygst_action ygst_call_next(ygst_call *c, const ygst_classification *cls, long retry_after_ms, int64_t now_ms) {
    ygst_action a;
    int64_t deadline = c->start_ms + c->policy.total_timeout_ms;
    a.kind = YGST_ACT_FAIL;
    a.delay_ms = 0;
    a.err_id = cls->err_id;
    a.auto_retry = 0;
    c->attempt++;
    c->total_attempts++;
    if (cls->success) {
        a.kind = YGST_ACT_SUCCESS;
        a.err_id = 0;
        return a;
    }
    if (cls->retryable && c->attempt <= c->policy.max_retries) {
        long d = apply_retry_after(&c->policy, ygst_backoff_delay_ms(&c->policy, c->attempt, &c->rng), retry_after_ms);
        if (now_ms + d <= deadline) {
            a.kind = YGST_ACT_RETRY;
            a.delay_ms = d;
            return a;
        }
        return a;
    }
    if (c->policy.auto_retry && c->submission < c->policy.max_auto_retries &&
        ygst_retry_policy_has_err_id(&c->policy, cls->err_id)) {
        long d = apply_retry_after(&c->policy, ygst_backoff_delay_ms(&c->policy, c->submission + 1, &c->rng),
                                   retry_after_ms);
        if (now_ms + d <= deadline) {
            c->submission++;
            c->attempt = 0;
            a.kind = YGST_ACT_RETRY;
            a.delay_ms = d;
            a.auto_retry = 1;
            return a;
        }
    }
    return a;
}
