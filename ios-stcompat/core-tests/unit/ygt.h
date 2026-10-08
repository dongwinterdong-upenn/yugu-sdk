/*
 * Copyright 2026 优谷雅言 open.shengzhiai.com
 * SPDX-License-Identifier: Apache-2.0
 *
 * Tiny test harness for the C core: checks, test cases, suites and a JUnit XML report.
 */
#ifndef YGT_H
#define YGT_H

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "ygst_core.h"

extern int ygt_checks;
extern int ygt_failures_in_case;
extern const char *ygt_spec_dir;

void ygt_fail(const char *file, int line, const char *what);
void ygt_run(const char *suite, const char *name, void (*fn)(void));
/* Reads a file below the spec directory, NUL-terminated. Returns NULL when missing. */
char *ygt_read_spec(const char *rel, size_t *len);
char *ygt_read_file(const char *path, size_t *len);

#define CHECK(cond)                                                                 \
    do {                                                                            \
        ygt_checks++;                                                               \
        if (!(cond)) ygt_fail(__FILE__, __LINE__, #cond);                           \
    } while (0)

#define CHECK_INT(a, b)                                                             \
    do {                                                                            \
        long long ygt_a = (long long)(a), ygt_b = (long long)(b);                   \
        ygt_checks++;                                                               \
        if (ygt_a != ygt_b) {                                                       \
            char ygt_m[512];                                                        \
            snprintf(ygt_m, sizeof ygt_m, "%s == %s (%lld != %lld)", #a, #b, ygt_a, ygt_b); \
            ygt_fail(__FILE__, __LINE__, ygt_m);                                    \
        }                                                                           \
    } while (0)

#define CHECK_STR(a, b)                                                             \
    do {                                                                            \
        const char *ygt_a = (a), *ygt_b = (b);                                      \
        ygt_checks++;                                                               \
        if (!ygt_a || !ygt_b || strcmp(ygt_a, ygt_b) != 0) {                        \
            char ygt_m[4096];                                                       \
            snprintf(ygt_m, sizeof ygt_m, "%.400s == %.400s (\"%.1200s\" != \"%.1200s\")", #a, #b, \
                     ygt_a ? ygt_a : "(null)", ygt_b ? ygt_b : "(null)");           \
            ygt_fail(__FILE__, __LINE__, ygt_m);                                    \
        }                                                                           \
    } while (0)

#define RUN(suite, fn) ygt_run(suite, #fn, fn)

void suite_crypto(void);
void suite_sign(void);
void suite_json(void);
void suite_coretype(void);
void suite_params(void);
void suite_multipart(void);
void suite_audio(void);
void suite_retry(void);
void suite_errors(void);
void suite_envelope(void);

#endif
