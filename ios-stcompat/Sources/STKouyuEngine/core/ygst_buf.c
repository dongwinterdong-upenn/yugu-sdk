/*
 * Copyright 2026 优谷雅言 open.shengzhiai.com
 * SPDX-License-Identifier: Apache-2.0
 *
 * Growable byte buffer and small string helpers.
 */
#include "ygst_core.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

void ygst_buf_init(ygst_buf *b) {
    b->data = NULL;
    b->len = 0;
    b->cap = 0;
    b->oom = 0;
}

void ygst_buf_free(ygst_buf *b) {
    free(b->data);
    ygst_buf_init(b);
}

void ygst_buf_reset(ygst_buf *b) {
    b->len = 0;
    b->oom = 0;
    if (b->data) b->data[0] = 0;
}

int ygst_buf_reserve(ygst_buf *b, size_t extra) {
    size_t need;
    size_t cap;
    unsigned char *p;
    if (b->oom) return -1;
    if (extra > (size_t)-1 - b->len - 1) {
        b->oom = 1;
        return -1;
    }
    need = b->len + extra + 1; /* room for the terminating NUL */
    if (need <= b->cap) return 0;
    cap = b->cap ? b->cap : 64;
    while (cap < need) {
        if (cap > ((size_t)-1) / 2) {
            cap = need;
            break;
        }
        cap *= 2;
    }
    p = (unsigned char *)realloc(b->data, cap);
    if (!p) {
        b->oom = 1;
        return -1;
    }
    b->data = p;
    b->cap = cap;
    return 0;
}

int ygst_buf_append(ygst_buf *b, const void *p, size_t n) {
    if (n == 0) return b->oom ? -1 : 0;
    if (ygst_buf_reserve(b, n) != 0) return -1;
    memcpy(b->data + b->len, p, n);
    b->len += n;
    b->data[b->len] = 0;
    return 0;
}

int ygst_buf_append_str(ygst_buf *b, const char *s) {
    return ygst_buf_append(b, s ? s : "", s ? strlen(s) : 0);
}

int ygst_buf_append_char(ygst_buf *b, char c) {
    return ygst_buf_append(b, &c, 1);
}

int ygst_buf_append_int(ygst_buf *b, long long v) {
    char tmp[32];
    int n = snprintf(tmp, sizeof tmp, "%lld", v);
    if (n < 0) return -1;
    return ygst_buf_append(b, tmp, (size_t)n);
}

const char *ygst_buf_cstr(ygst_buf *b) {
    if (!b->data) {
        if (ygst_buf_reserve(b, 0) != 0) return "";
        b->data[0] = 0;
    }
    return (const char *)b->data;
}

char *ygst_strdup(const char *s) {
    size_t n;
    char *p;
    if (!s) return NULL;
    n = strlen(s);
    p = (char *)malloc(n + 1);
    if (!p) return NULL;
    memcpy(p, s, n + 1);
    return p;
}

static int lower_ascii(int c) {
    return (c >= 'A' && c <= 'Z') ? c + ('a' - 'A') : c;
}

int ygst_strcaseeq(const char *a, const char *b) {
    if (!a || !b) return 0;
    while (*a && *b) {
        if (lower_ascii((unsigned char)*a) != lower_ascii((unsigned char)*b)) return 0;
        a++;
        b++;
    }
    return *a == 0 && *b == 0;
}

int ygst_has_suffix_ci(const char *s, const char *suffix) {
    size_t n;
    size_t m;
    if (!s || !suffix) return 0;
    n = strlen(s);
    m = strlen(suffix);
    if (m > n) return 0;
    return ygst_strcaseeq(s + (n - m), suffix);
}

static int is_space(unsigned char c) {
    return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f' || c == '\v';
}

size_t ygst_trim_copy(const char *s, char *out, size_t cap) {
    const char *e;
    size_t n;
    if (!out || cap == 0) return 0;
    out[0] = 0;
    if (!s) return 0;
    while (*s && is_space((unsigned char)*s)) s++;
    e = s + strlen(s);
    while (e > s && is_space((unsigned char)e[-1])) e--;
    n = (size_t)(e - s);
    if (n >= cap) n = cap - 1;
    memcpy(out, s, n);
    out[n] = 0;
    return n;
}

int ygst_is_blank(const char *s) {
    if (!s) return 1;
    while (*s) {
        if (!is_space((unsigned char)*s)) return 0;
        s++;
    }
    return 1;
}
