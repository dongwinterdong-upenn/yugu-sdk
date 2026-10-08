/*
 * Copyright 2026 优谷雅言 open.shengzhiai.com
 * SPDX-License-Identifier: Apache-2.0
 *
 * Shengtong style result and error JSON (DESIGN 6.2), the dtLastResponse clock text, the
 * request params echo, base URL resolution and small identifiers.
 */
#include "ygst_core.h"

#include <stdio.h>
#include <string.h>

/* civil_from_days (H. Hinnant) */
static void civil_from_days(long long z, long long *y, unsigned *m, unsigned *d) {
    long long era;
    unsigned doe, yoe, doy, mp;
    z += 719468;
    era = (z >= 0 ? z : z - 146096) / 146097;
    doe = (unsigned)(z - era * 146097);
    yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
    doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    mp = (5 * doy + 2) / 153;
    *d = doy - (153 * mp + 2) / 5 + 1;
    *m = mp < 10 ? mp + 3 : mp - 9;
    *y = (long long)yoe + era * 400 + (*m <= 2);
}

int ygst_format_dt(int64_t epoch_ms, int tz_offset_min, char out[32]) {
    long long ms = (long long)epoch_ms + (long long)tz_offset_min * 60000LL;
    long long days = ms >= 0 ? ms / 86400000LL : -((-ms + 86399999LL) / 86400000LL);
    long long rem = ms - days * 86400000LL;
    long long y;
    unsigned m, d, hh, mi, ss, mss;
    civil_from_days(days, &y, &m, &d);
    if (y < 0) y = 0;
    if (y > 9999) y = 9999;
    hh = (unsigned)(rem / 3600000LL) % 24u;
    mi = (unsigned)(rem / 60000LL) % 60u;
    ss = (unsigned)(rem / 1000LL) % 60u;
    mss = (unsigned)rem % 1000u;
    snprintf(out, 32, "%04u-%02u-%02u %02u:%02u:%02u:%03u", (unsigned)y % 10000u, m % 13u, d % 32u, hh, mi, ss,
             mss);
    return 0;
}

int ygst_align_result(ygst_span result, ygst_buf *out) {
    ygst_span sentences, sent, details, d, scores, v, so, sp;
    size_t spos = 0;
    size_t copied = 0; /* bytes of result already copied to out */
    int added = 0;
    if (!result.p) return -1;
    if (ygst_json_type(result) != 'o' || ygst_json_member(result, "sentences", &sentences) != 1 ||
        ygst_json_type(sentences) != 'a') {
        return ygst_buf_append(out, result.p, result.n) == 0 ? 0 : -1;
    }
    while (ygst_json_array_next(sentences, &spos, &sent) == 1) {
        size_t dpos = 0;
        if (ygst_json_type(sent) != 'o' || ygst_json_member(sent, "details", &details) != 1 ||
            ygst_json_type(details) != 'a') {
            continue;
        }
        while (ygst_json_array_next(details, &dpos, &d) == 1) {
            int add_overall, add_pron;
            size_t close;
            if (ygst_json_type(d) != 'o' || ygst_json_member(d, "overall", &v) == 1) continue;
            if (ygst_json_member(d, "scores", &scores) != 1 || ygst_json_type(scores) != 'o') continue;
            add_overall = ygst_json_member(scores, "overall", &so) == 1;
            add_pron = ygst_json_member(d, "pronunciation", &v) != 1 && ygst_json_member(scores, "pronunciation", &sp) == 1;
            if (!add_overall && !add_pron) continue;
            close = (size_t)(d.p + d.n - 1 - result.p); /* the closing brace of the item */
            ygst_buf_append(out, result.p + copied, close - copied);
            if (add_overall) {
                ygst_buf_append_str(out, ",\"overall\":");
                ygst_buf_append(out, so.p, so.n);
                added++;
            }
            if (add_pron) {
                ygst_buf_append_str(out, ",\"pronunciation\":");
                ygst_buf_append(out, sp.p, sp.n);
                added++;
            }
            copied = close;
        }
    }
    ygst_buf_append(out, result.p + copied, result.n - copied);
    return out->oom ? -1 : added;
}

static void key(ygst_buf *out, const char *k, int first) {
    if (!first) ygst_buf_append_char(out, ',');
    ygst_json_escape(out, k);
    ygst_buf_append_char(out, ':');
}

int ygst_envelope_result(ygst_buf *out, const char *token_id, const char *record_id_json, const char *app_key,
                         const char *user_id, const char *ref_text, const char *dt_last_response,
                         ygst_span result_raw, const char *params_json, const char *audio_url) {
    if (!result_raw.p || result_raw.n == 0) return -1;
    ygst_buf_append_char(out, '{');
    key(out, "tokenId", 1);
    ygst_json_escape(out, token_id);
    if (record_id_json && record_id_json[0]) {
        key(out, "recordId", 0);
        ygst_buf_append_str(out, record_id_json);
    }
    key(out, "applicationId", 0);
    ygst_json_escape(out, app_key);
    key(out, "userId", 0);
    ygst_json_escape(out, user_id);
    key(out, "refText", 0);
    ygst_json_escape(out, ref_text);
    key(out, "eof", 0);
    ygst_buf_append_char(out, '1');
    key(out, "dtLastResponse", 0);
    ygst_json_escape(out, dt_last_response);
    key(out, "result", 0);
    if (ygst_align_result(result_raw, out) < 0) return -1;
    if (params_json && params_json[0]) {
        key(out, "params", 0);
        ygst_buf_append_str(out, params_json);
    }
    if (audio_url && audio_url[0]) {
        key(out, "audioUrl", 0);
        ygst_json_escape(out, audio_url);
    }
    ygst_buf_append_char(out, '}');
    ygst_buf_cstr(out);
    return out->oom ? -1 : 0;
}

int ygst_envelope_error(ygst_buf *out, const char *token_id, int err_id, const char *message,
                        const char *app_key) {
    ygst_buf_append_char(out, '{');
    key(out, "tokenId", 1);
    ygst_json_escape(out, token_id);
    key(out, "errId", 0);
    ygst_buf_append_int(out, err_id);
    key(out, "error", 0);
    ygst_json_escape(out, message);
    key(out, "eof", 0);
    ygst_buf_append_char(out, '1');
    key(out, "applicationId", 0);
    ygst_json_escape(out, app_key);
    ygst_buf_append_char(out, '}');
    ygst_buf_cstr(out);
    return out->oom ? -1 : 0;
}

int ygst_params_json(ygst_buf *out, const char *app_key, const char *user_id, int64_t timestamp_sec,
                     const char *audio_type, int sample_rate, int channel, int sample_bytes,
                     const char *core_type, const char *token_id, const ygst_fields *request_fields) {
    size_t i;
    char ts[32];
    snprintf(ts, sizeof ts, "%lld", (long long)timestamp_sec);
    ygst_buf_append_str(out, "{\"app\":{");
    key(out, "applicationId", 1);
    ygst_json_escape(out, app_key);
    key(out, "userId", 0);
    ygst_json_escape(out, user_id);
    key(out, "timestamp", 0);
    ygst_json_escape(out, ts);
    ygst_buf_append_str(out, "},\"audio\":{");
    key(out, "audioType", 1);
    ygst_json_escape(out, audio_type);
    key(out, "sampleRate", 0);
    ygst_buf_append_int(out, sample_rate);
    key(out, "channel", 0);
    ygst_buf_append_int(out, channel);
    key(out, "sampleBytes", 0);
    ygst_buf_append_int(out, sample_bytes);
    ygst_buf_append_str(out, "},\"request\":{");
    key(out, "coreType", 1);
    ygst_json_escape(out, core_type);
    key(out, "tokenId", 0);
    ygst_json_escape(out, token_id);
    if (request_fields) {
        for (i = 0; i < request_fields->count; i++) {
            const char *n = request_fields->items[i].name;
            if (strcmp(n, "coreType") == 0 || strcmp(n, "tokenId") == 0) continue;
            key(out, n, 0);
            ygst_json_escape(out, request_fields->items[i].value);
        }
    }
    ygst_buf_append_str(out, "}}");
    ygst_buf_cstr(out);
    return out->oom ? -1 : 0;
}

/* ------------------------------------------------------------------ base URL */

static int prefix_ci(const char *s, const char *p) {
    while (*p) {
        char a = *s, b = *p;
        if (a >= 'A' && a <= 'Z') a = (char)(a + 32);
        if (a != b) return 0;
        s++;
        p++;
    }
    return 1;
}

static int host_is_shengtong(const char *host, size_t n) {
    static const char D[] = "stkouyu.com";
    size_t m = sizeof D - 1;
    char h[256];
    size_t i;
    if (n == 0 || n >= sizeof h) return 0;
    for (i = 0; i < n; i++) {
        char c = host[i];
        h[i] = (c >= 'A' && c <= 'Z') ? (char)(c + 32) : c;
    }
    h[n] = 0;
    if (n == m) return strcmp(h, D) == 0;
    return n > m && strcmp(h + n - m, D) == 0 && h[n - m - 1] == '.';
}

ygst_base_kind ygst_resolve_base_url(const char *server, char *out, size_t cap) {
    char s[1024];
    const char *scheme;
    const char *rest;
    const char *host_start;
    const char *host_end;
    const char *end;
    size_t n;
    int written;
    if (!out || cap == 0) return YGST_BASE_DEFAULT_EMPTY;
    snprintf(out, cap, "%s", YGST_DEFAULT_BASE_URL);
    n = ygst_trim_copy(server, s, sizeof s);
    if (n == 0) return YGST_BASE_DEFAULT_EMPTY;
    if (prefix_ci(s, "https://")) {
        scheme = "https";
        rest = s + 8;
    } else if (prefix_ci(s, "http://")) {
        scheme = "http";
        rest = s + 7;
    } else if (prefix_ci(s, "wss://")) {
        scheme = "https";
        rest = s + 6;
    } else if (prefix_ci(s, "ws://")) {
        scheme = "http";
        rest = s + 5;
    } else {
        return host_is_shengtong(s, strcspn(s, "/:?#")) ? YGST_BASE_DEFAULT_SHENGTONG : YGST_BASE_ERR_SCHEME;
    }
    host_start = rest;
    if (*host_start == '[') {
        host_end = strchr(host_start, ']');
        if (!host_end) return YGST_BASE_ERR_HOST;
        host_end++;
    } else {
        host_end = host_start;
        while (*host_end && *host_end != '/' && *host_end != ':' && *host_end != '?' && *host_end != '#') host_end++;
    }
    if (host_end == host_start) return YGST_BASE_ERR_HOST;
    if (host_is_shengtong(host_start, (size_t)(host_end - host_start))) return YGST_BASE_DEFAULT_SHENGTONG;
    end = rest;
    while (*end && *end != '?' && *end != '#') end++;
    while (end > host_end && end[-1] == '/') end--;
    written = snprintf(out, cap, "%s://%.*s", scheme, (int)(end - rest), rest);
    if (written < 0 || (size_t)written >= cap) {
        snprintf(out, cap, "%s", YGST_DEFAULT_BASE_URL);
        return YGST_BASE_ERR_HOST;
    }
    return YGST_BASE_CUSTOM;
}

int ygst_build_url(const char *base, const char *core_type, char *out, size_t cap) {
    size_t n;
    int w;
    if (!base || !core_type || !out) return -1;
    n = strlen(base);
    while (n > 0 && base[n - 1] == '/') n--;
    w = snprintf(out, cap, "%.*s/%s", (int)n, base, core_type);
    return (w < 0 || (size_t)w >= cap) ? -1 : 0;
}

int ygst_token_id(const unsigned char rnd[16], char out[33]) {
    ygst_hex_lower(rnd, 16, out);
    return 0;
}

int ygst_idempotency_key_ok(const char *k) {
    size_t n = 0;
    if (!k) return 0;
    for (; k[n]; n++) {
        unsigned char c = (unsigned char)k[n];
        if (c < 0x21 || c > 0x7E) return 0;
        if (n >= 200) return 0;
    }
    return n >= 1;
}

void ygst_mask_app_key(const char *app_key, char out[16]) {
    size_t n = app_key ? strlen(app_key) : 0;
    if (n > 4) n = 4;
    memcpy(out, app_key ? app_key : "", n);
    memcpy(out + n, "***", 4);
}
