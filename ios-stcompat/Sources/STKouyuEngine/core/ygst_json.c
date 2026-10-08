/*
 * Copyright 2026 优谷雅言 open.shengzhiai.com
 * SPDX-License-Identifier: Apache-2.0
 *
 * Minimal validating JSON scanner (RFC 8259). It never builds a tree: values are spans into the
 * original text, so the platform result can be passed to the caller byte for byte.
 */
#include "ygst_core.h"

#include <stdlib.h>
#include <string.h>

#define YGST_JSON_MAX_DEPTH 128

static const char *ws(const char *p, const char *e) {
    while (p < e && (*p == ' ' || *p == '\t' || *p == '\n' || *p == '\r')) p++;
    return p;
}

static int hexval(char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
}

static const char *skip_string(const char *p, const char *e) {
    if (p >= e || *p != '"') return NULL;
    p++;
    while (p < e) {
        unsigned char c = (unsigned char)*p;
        if (c == '"') return p + 1;
        if (c < 0x20) return NULL;
        if (c == '\\') {
            p++;
            if (p >= e) return NULL;
            switch (*p) {
            case '"':
            case '\\':
            case '/':
            case 'b':
            case 'f':
            case 'n':
            case 'r':
            case 't':
                p++;
                break;
            case 'u': {
                int i;
                if (e - p < 5) return NULL;
                for (i = 1; i <= 4; i++) {
                    if (hexval(p[i]) < 0) return NULL;
                }
                p += 5;
                break;
            }
            default:
                return NULL;
            }
            continue;
        }
        p++;
    }
    return NULL;
}

static int is_digit(char c) {
    return c >= '0' && c <= '9';
}

static const char *skip_number(const char *p, const char *e) {
    if (p < e && *p == '-') p++;
    if (p >= e) return NULL;
    if (*p == '0') {
        p++;
    } else if (*p >= '1' && *p <= '9') {
        while (p < e && is_digit(*p)) p++;
    } else {
        return NULL;
    }
    if (p < e && *p == '.') {
        p++;
        if (p >= e || !is_digit(*p)) return NULL;
        while (p < e && is_digit(*p)) p++;
    }
    if (p < e && (*p == 'e' || *p == 'E')) {
        p++;
        if (p < e && (*p == '+' || *p == '-')) p++;
        if (p >= e || !is_digit(*p)) return NULL;
        while (p < e && is_digit(*p)) p++;
    }
    return p;
}

static const char *skip_lit(const char *p, const char *e, const char *lit) {
    size_t n = strlen(lit);
    if ((size_t)(e - p) < n || memcmp(p, lit, n) != 0) return NULL;
    return p + n;
}

static const char *skip_value(const char *p, const char *e, int depth) {
    if (depth > YGST_JSON_MAX_DEPTH) return NULL;
    p = ws(p, e);
    if (p >= e) return NULL;
    switch (*p) {
    case '{':
        p = ws(p + 1, e);
        if (p < e && *p == '}') return p + 1;
        for (;;) {
            p = skip_string(ws(p, e), e);
            if (!p) return NULL;
            p = ws(p, e);
            if (p >= e || *p != ':') return NULL;
            p = skip_value(p + 1, e, depth + 1);
            if (!p) return NULL;
            p = ws(p, e);
            if (p >= e) return NULL;
            if (*p == ',') {
                p++;
                continue;
            }
            if (*p == '}') return p + 1;
            return NULL;
        }
    case '[':
        p = ws(p + 1, e);
        if (p < e && *p == ']') return p + 1;
        for (;;) {
            p = skip_value(p, e, depth + 1);
            if (!p) return NULL;
            p = ws(p, e);
            if (p >= e) return NULL;
            if (*p == ',') {
                p++;
                continue;
            }
            if (*p == ']') return p + 1;
            return NULL;
        }
    case '"':
        return skip_string(p, e);
    case 't':
        return skip_lit(p, e, "true");
    case 'f':
        return skip_lit(p, e, "false");
    case 'n':
        return skip_lit(p, e, "null");
    default:
        if (*p == '-' || is_digit(*p)) return skip_number(p, e);
        return NULL;
    }
}

int ygst_json_parse(const char *s, size_t n, ygst_span *root) {
    const char *e;
    const char *start;
    const char *end;
    if (!s) return -1;
    e = s + n;
    start = ws(s, e);
    end = skip_value(start, e, 0);
    if (!end) return -1;
    if (ws(end, e) != e) return -1;
    if (root) {
        root->p = start;
        root->n = (size_t)(end - start);
    }
    return 0;
}

int ygst_json_type(ygst_span v) {
    if (!v.p || v.n == 0) return 0;
    switch (v.p[0]) {
    case '{':
        return 'o';
    case '[':
        return 'a';
    case '"':
        return 's';
    case 't':
        return 't';
    case 'f':
        return 'f';
    case 'n':
        return 'z';
    default:
        return (v.p[0] == '-' || is_digit(v.p[0])) ? 'n' : 0;
    }
}

int ygst_json_object_next(ygst_span obj, size_t *pos, ygst_span *key, ygst_span *value) {
    const char *e = obj.p + obj.n;
    const char *p;
    const char *k;
    const char *v;
    if (ygst_json_type(obj) != 'o') return -1;
    p = obj.p + (*pos ? *pos : 1);
    p = ws(p, e);
    if (p >= e) return -1;
    if (*p == '}') return 0;
    if (*pos) {
        if (*p != ',') return -1;
        p = ws(p + 1, e);
    }
    k = p;
    p = skip_string(p, e);
    if (!p) return -1;
    if (key) {
        key->p = k;
        key->n = (size_t)(p - k);
    }
    p = ws(p, e);
    if (p >= e || *p != ':') return -1;
    v = ws(p + 1, e);
    p = skip_value(v, e, 1);
    if (!p) return -1;
    if (value) {
        value->p = v;
        value->n = (size_t)(p - v);
    }
    *pos = (size_t)(p - obj.p);
    return 1;
}

/* Compares a raw key token (with quotes, possibly escaped) with a plain key. */
static int key_equals(ygst_span tok, const char *key) {
    size_t klen = strlen(key);
    if (tok.n >= 2 && memchr(tok.p, '\\', tok.n) == NULL) {
        return tok.n - 2 == klen && memcmp(tok.p + 1, key, klen) == 0;
    } else {
        ygst_buf b;
        int eq;
        ygst_buf_init(&b);
        eq = ygst_json_string(tok, &b) == 0 && b.len == klen && memcmp(ygst_buf_cstr(&b), key, klen) == 0;
        ygst_buf_free(&b);
        return eq;
    }
}

int ygst_json_member(ygst_span obj, const char *key, ygst_span *out) {
    size_t pos = 0;
    ygst_span k, v;
    int found = 0;
    int rc;
    if (ygst_json_type(obj) != 'o' || !key) return -1;
    while ((rc = ygst_json_object_next(obj, &pos, &k, &v)) == 1) {
        if (key_equals(k, key)) {
            found = 1;
            if (out) *out = v;
        }
    }
    if (rc < 0) return -1;
    return found;
}

int ygst_json_array_next(ygst_span arr, size_t *pos, ygst_span *out) {
    const char *e = arr.p + arr.n;
    const char *p;
    const char *v;
    if (ygst_json_type(arr) != 'a') return -1;
    p = arr.p + (*pos ? *pos : 1);
    p = ws(p, e);
    if (p >= e) return -1;
    if (*p == ']') return 0;
    if (*pos) {
        if (*p != ',') return -1;
        p = ws(p + 1, e);
    }
    v = p;
    p = skip_value(v, e, 1);
    if (!p) return -1;
    if (out) {
        out->p = v;
        out->n = (size_t)(p - v);
    }
    *pos = (size_t)(p - arr.p);
    return 1;
}

static void put_utf8(ygst_buf *out, uint32_t cp) {
    char b[4];
    if (cp < 0x80) {
        b[0] = (char)cp;
        ygst_buf_append(out, b, 1);
    } else if (cp < 0x800) {
        b[0] = (char)(0xC0 | (cp >> 6));
        b[1] = (char)(0x80 | (cp & 0x3F));
        ygst_buf_append(out, b, 2);
    } else if (cp < 0x10000) {
        b[0] = (char)(0xE0 | (cp >> 12));
        b[1] = (char)(0x80 | ((cp >> 6) & 0x3F));
        b[2] = (char)(0x80 | (cp & 0x3F));
        ygst_buf_append(out, b, 3);
    } else {
        b[0] = (char)(0xF0 | (cp >> 18));
        b[1] = (char)(0x80 | ((cp >> 12) & 0x3F));
        b[2] = (char)(0x80 | ((cp >> 6) & 0x3F));
        b[3] = (char)(0x80 | (cp & 0x3F));
        ygst_buf_append(out, b, 4);
    }
}

static uint32_t hex4(const char *p) {
    return (uint32_t)((hexval(p[0]) << 12) | (hexval(p[1]) << 8) | (hexval(p[2]) << 4) | hexval(p[3]));
}

int ygst_json_string(ygst_span v, ygst_buf *out) {
    const char *p;
    const char *e;
    if (ygst_json_type(v) != 's' || skip_string(v.p, v.p + v.n) != v.p + v.n) return -1;
    p = v.p + 1;
    e = v.p + v.n - 1;
    while (p < e) {
        const char *run = p;
        while (p < e && *p != '\\') p++;
        if (p > run) ygst_buf_append(out, run, (size_t)(p - run));
        if (p >= e) break;
        p++; /* backslash */
        switch (*p) {
        case 'b':
            ygst_buf_append_char(out, '\b');
            p++;
            break;
        case 'f':
            ygst_buf_append_char(out, '\f');
            p++;
            break;
        case 'n':
            ygst_buf_append_char(out, '\n');
            p++;
            break;
        case 'r':
            ygst_buf_append_char(out, '\r');
            p++;
            break;
        case 't':
            ygst_buf_append_char(out, '\t');
            p++;
            break;
        case 'u': {
            uint32_t cp = hex4(p + 1);
            p += 5;
            if (cp >= 0xD800 && cp <= 0xDBFF) {
                if (e - p >= 6 && p[0] == '\\' && p[1] == 'u') {
                    uint32_t lo = hex4(p + 2);
                    if (lo >= 0xDC00 && lo <= 0xDFFF) {
                        cp = 0x10000 + ((cp - 0xD800) << 10) + (lo - 0xDC00);
                        p += 6;
                    } else {
                        cp = 0xFFFD;
                    }
                } else {
                    cp = 0xFFFD;
                }
            } else if (cp >= 0xDC00 && cp <= 0xDFFF) {
                cp = 0xFFFD;
            }
            put_utf8(out, cp);
            break;
        }
        default: /* " \ / */
            ygst_buf_append_char(out, *p);
            p++;
            break;
        }
    }
    ygst_buf_cstr(out);
    return out->oom ? -1 : 0;
}

static int number_text(ygst_span v, char *tmp, size_t cap) {
    if (ygst_json_type(v) != 'n' || v.n >= cap) return -1;
    if (skip_number(v.p, v.p + v.n) != v.p + v.n) return -1;
    memcpy(tmp, v.p, v.n);
    tmp[v.n] = 0;
    return 0;
}

int ygst_json_number(ygst_span v, double *out) {
    char tmp[64];
    char *end = NULL;
    if (number_text(v, tmp, sizeof tmp) != 0) return -1;
    *out = strtod(tmp, &end);
    if (end && *end) {
        /* A locale with a decimal comma: retry with the separator it expects. */
        char *dot = strchr(tmp, '.');
        if (dot) {
            *dot = ',';
            *out = strtod(tmp, &end);
        }
        if (end && *end) return -1;
    }
    return 0;
}

int ygst_json_int(ygst_span v, long long *out) {
    char tmp[64];
    size_t i;
    int neg = 0;
    long long acc = 0;
    if (number_text(v, tmp, sizeof tmp) != 0) return -1;
    if (strpbrk(tmp, ".eE")) {
        double d;
        if (ygst_json_number(v, &d) != 0) return -1;
        if (d != d || d > 9.2e18 || d < -9.2e18 || d != (double)(long long)d) return -1;
        *out = (long long)d;
        return 0;
    }
    i = 0;
    if (tmp[0] == '-') {
        neg = 1;
        i = 1;
    }
    for (; tmp[i]; i++) {
        int dgt = tmp[i] - '0';
        if (acc > (9223372036854775807LL - dgt) / 10) return -1;
        acc = acc * 10 + dgt;
    }
    *out = neg ? -acc : acc;
    return 0;
}

int ygst_json_bool(ygst_span v, int *out) {
    int t = ygst_json_type(v);
    if (t == 't' && v.n == 4) {
        *out = 1;
        return 0;
    }
    if (t == 'f' && v.n == 5) {
        *out = 0;
        return 0;
    }
    return -1;
}

int ygst_json_escape_n(ygst_buf *out, const char *s, size_t n) {
    static const char H[] = "0123456789abcdef";
    size_t i;
    size_t run = 0;
    ygst_buf_append_char(out, '"');
    for (i = 0; i < n; i++) {
        unsigned char c = (unsigned char)s[i];
        const char *rep = NULL;
        char u[7];
        if (c == '"') {
            rep = "\\\"";
        } else if (c == '\\') {
            rep = "\\\\";
        } else if (c == '\n') {
            rep = "\\n";
        } else if (c == '\r') {
            rep = "\\r";
        } else if (c == '\t') {
            rep = "\\t";
        } else if (c == '\b') {
            rep = "\\b";
        } else if (c == '\f') {
            rep = "\\f";
        } else if (c < 0x20) {
            u[0] = '\\';
            u[1] = 'u';
            u[2] = '0';
            u[3] = '0';
            u[4] = H[c >> 4];
            u[5] = H[c & 15];
            u[6] = 0;
            rep = u;
        }
        if (rep) {
            if (i > run) ygst_buf_append(out, s + run, i - run);
            ygst_buf_append_str(out, rep);
            run = i + 1;
        }
    }
    if (n > run) ygst_buf_append(out, s + run, n - run);
    ygst_buf_append_char(out, '"');
    return out->oom ? -1 : 0;
}

int ygst_json_escape(ygst_buf *out, const char *s) {
    if (!s) s = "";
    return ygst_json_escape_n(out, s, strlen(s));
}
