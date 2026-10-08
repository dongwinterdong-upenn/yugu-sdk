/*
 * Copyright 2026 优谷雅言 open.shengzhiai.com
 * SPDX-License-Identifier: Apache-2.0
 *
 * Ordered form field list and the platform request signature (CONTRACT.md 0.2):
 * drop empty values, sort by key, join key=value with &, Base64(HMAC_SHA256(payload, secret)).
 */
#include "ygst_core.h"

#include <stdlib.h>
#include <string.h>

void ygst_fields_init(ygst_fields *f) {
    f->items = NULL;
    f->count = 0;
    f->cap = 0;
    f->oom = 0;
}

void ygst_fields_free(ygst_fields *f) {
    size_t i;
    for (i = 0; i < f->count; i++) {
        free(f->items[i].name);
        free(f->items[i].value);
    }
    free(f->items);
    ygst_fields_init(f);
}

static long find_index(const ygst_fields *f, const char *name) {
    size_t i;
    for (i = 0; i < f->count; i++) {
        if (strcmp(f->items[i].name, name) == 0) return (long)i;
    }
    return -1;
}

int ygst_fields_remove(ygst_fields *f, const char *name) {
    long i = name ? find_index(f, name) : -1;
    if (i < 0) return 0;
    free(f->items[i].name);
    free(f->items[i].value);
    memmove(&f->items[i], &f->items[i + 1], (f->count - (size_t)i - 1) * sizeof(ygst_field));
    f->count--;
    return 1;
}

int ygst_fields_set(ygst_fields *f, const char *name, const char *value) {
    long i;
    char *v;
    if (!name || f->oom) return -1;
    if (!value) {
        ygst_fields_remove(f, name);
        return 0;
    }
    i = find_index(f, name);
    v = ygst_strdup(value);
    if (!v) {
        f->oom = 1;
        return -1;
    }
    if (i >= 0) {
        free(f->items[i].value);
        f->items[i].value = v;
        return 0;
    }
    if (f->count == f->cap) {
        size_t cap = f->cap ? f->cap * 2 : 16;
        ygst_field *p = (ygst_field *)realloc(f->items, cap * sizeof(ygst_field));
        if (!p) {
            free(v);
            f->oom = 1;
            return -1;
        }
        f->items = p;
        f->cap = cap;
    }
    f->items[f->count].name = ygst_strdup(name);
    if (!f->items[f->count].name) {
        free(v);
        f->oom = 1;
        return -1;
    }
    f->items[f->count].value = v;
    f->count++;
    return 0;
}

const char *ygst_fields_get(const ygst_fields *f, const char *name) {
    long i = name ? find_index(f, name) : -1;
    return i < 0 ? NULL : f->items[i].value;
}

int ygst_fields_copy(const ygst_fields *src, ygst_fields *dst) {
    size_t i;
    for (i = 0; i < src->count; i++) {
        if (ygst_fields_set(dst, src->items[i].name, src->items[i].value) != 0) return -1;
    }
    return 0;
}

/* Decodes one UTF-8 sequence. Invalid bytes decode as themselves so that ordering stays total. */
static uint32_t next_cp(const unsigned char **s) {
    const unsigned char *p = *s;
    uint32_t c = p[0];
    int n = 0;
    int i;
    if (c < 0x80) {
        *s = p + 1;
        return c;
    }
    if ((c & 0xE0) == 0xC0) {
        n = 1;
        c &= 0x1F;
    } else if ((c & 0xF0) == 0xE0) {
        n = 2;
        c &= 0x0F;
    } else if ((c & 0xF8) == 0xF0) {
        n = 3;
        c &= 0x07;
    } else {
        *s = p + 1;
        return 0xFFFD;
    }
    for (i = 1; i <= n; i++) {
        if ((p[i] & 0xC0) != 0x80) {
            *s = p + 1;
            return 0xFFFD;
        }
        c = (c << 6) | (p[i] & 0x3F);
    }
    *s = p + n + 1;
    return c;
}

/* Emits the UTF-16 code units of cp into u, returns how many (1 or 2). */
static int utf16_units(uint32_t cp, uint32_t u[2]) {
    if (cp >= 0x10000 && cp <= 0x10FFFF) {
        cp -= 0x10000;
        u[0] = 0xD800 + (cp >> 10);
        u[1] = 0xDC00 + (cp & 0x3FF);
        return 2;
    }
    u[0] = cp;
    return 1;
}

int ygst_utf16_compare(const char *a, const char *b) {
    const unsigned char *pa = (const unsigned char *)a;
    const unsigned char *pb = (const unsigned char *)b;
    uint32_t ua[2], ub[2];
    int na = 0, nb = 0, ia = 0, ib = 0;
    for (;;) {
        uint32_t ca, cb;
        if (ia == na) {
            if (!*pa) {
                na = 0;
            } else {
                na = utf16_units(next_cp(&pa), ua);
            }
            ia = 0;
        }
        if (ib == nb) {
            if (!*pb) {
                nb = 0;
            } else {
                nb = utf16_units(next_cp(&pb), ub);
            }
            ib = 0;
        }
        if (na == 0 || nb == 0) {
            if (na == 0 && nb == 0) return 0;
            return na == 0 ? -1 : 1;
        }
        ca = ua[ia++];
        cb = ub[ib++];
        if (ca != cb) return ca < cb ? -1 : 1;
    }
}

static int cmp_field_ptr(const void *x, const void *y) {
    const ygst_field *a = *(const ygst_field *const *)x;
    const ygst_field *b = *(const ygst_field *const *)y;
    return ygst_utf16_compare(a->name, b->name);
}

int ygst_sign_payload(const ygst_fields *f, ygst_buf *out) {
    const ygst_field **v;
    size_t n = 0;
    size_t i;
    int first = 1;
    if (f->oom) return -1;
    v = (const ygst_field **)malloc((f->count ? f->count : 1) * sizeof(*v));
    if (!v) return -1;
    for (i = 0; i < f->count; i++) {
        if (f->items[i].value && f->items[i].value[0]) v[n++] = &f->items[i];
    }
    qsort(v, n, sizeof(*v), cmp_field_ptr);
    for (i = 0; i < n; i++) {
        if (!first) ygst_buf_append_char(out, '&');
        first = 0;
        ygst_buf_append_str(out, v[i]->name);
        ygst_buf_append_char(out, '=');
        ygst_buf_append_str(out, v[i]->value);
    }
    free(v);
    ygst_buf_cstr(out);
    return out->oom ? -1 : 0;
}

int ygst_sign(const ygst_fields *f, const char *secret, char out[45]) {
    ygst_buf payload;
    unsigned char mac[32];
    int rc;
    out[0] = 0;
    if (!secret) return -1;
    ygst_buf_init(&payload);
    rc = ygst_sign_payload(f, &payload);
    if (rc == 0) {
        ygst_hmac_sha256(secret, strlen(secret), payload.data ? payload.data : (const unsigned char *)"",
                         payload.len, mac);
        if (ygst_base64_encode(mac, sizeof mac, out, 45) != 44) rc = -1;
    }
    ygst_buf_free(&payload);
    return rc;
}
