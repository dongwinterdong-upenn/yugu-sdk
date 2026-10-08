/*
 * Copyright 2026 优谷雅言 open.shengzhiai.com
 * SPDX-License-Identifier: Apache-2.0
 *
 *   ygst_tests --spec <path to spec/> [--junit report.xml]
 */
#include "ygt.h"

int ygt_checks = 0;
int ygt_failures_in_case = 0;
const char *ygt_spec_dir = "../spec";

typedef struct {
    char suite[32];
    char name[96];
    int failed;
    char message[600];
} ygt_case;

static ygt_case g_cases[1024];
static int g_ncases = 0;
static int g_failed_cases = 0;
static int g_failed_checks = 0;
static ygt_case *g_current = NULL;

void ygt_fail(const char *file, int line, const char *what) {
    g_failed_checks++;
    ygt_failures_in_case++;
    fprintf(stderr, "    FAIL %s:%d: %s\n", file, line, what);
    if (g_current && !g_current->message[0]) {
        snprintf(g_current->message, sizeof g_current->message, "%s:%d: %s", file, line, what);
    }
}

void ygt_run(const char *suite, const char *name, void (*fn)(void)) {
    ygt_case *c = g_ncases < (int)(sizeof g_cases / sizeof g_cases[0]) ? &g_cases[g_ncases++] : NULL;
    ygt_failures_in_case = 0;
    if (c) {
        memset(c, 0, sizeof *c);
        snprintf(c->suite, sizeof c->suite, "%s", suite);
        snprintf(c->name, sizeof c->name, "%s", name);
    }
    g_current = c;
    fn();
    if (ygt_failures_in_case) {
        g_failed_cases++;
        if (c) c->failed = 1;
        printf("FAIL %s.%s\n", suite, name);
    } else {
        printf("ok   %s.%s\n", suite, name);
    }
    g_current = NULL;
}

char *ygt_read_file(const char *path, size_t *len) {
    FILE *f = fopen(path, "rb");
    char *buf;
    long n;
    if (!f) return NULL;
    if (fseek(f, 0, SEEK_END) != 0) {
        fclose(f);
        return NULL;
    }
    n = ftell(f);
    if (n < 0) {
        fclose(f);
        return NULL;
    }
    rewind(f);
    buf = (char *)malloc((size_t)n + 1);
    if (!buf) {
        fclose(f);
        return NULL;
    }
    if (n > 0 && fread(buf, 1, (size_t)n, f) != (size_t)n) {
        free(buf);
        fclose(f);
        return NULL;
    }
    fclose(f);
    buf[n] = 0;
    if (len) *len = (size_t)n;
    return buf;
}

char *ygt_read_spec(const char *rel, size_t *len) {
    char path[2048];
    snprintf(path, sizeof path, "%s/%s", ygt_spec_dir, rel);
    return ygt_read_file(path, len);
}

static void xml_escape(FILE *f, const char *s) {
    for (; *s; s++) {
        switch (*s) {
        case '<':
            fputs("&lt;", f);
            break;
        case '>':
            fputs("&gt;", f);
            break;
        case '&':
            fputs("&amp;", f);
            break;
        case '"':
            fputs("&quot;", f);
            break;
        default:
            fputc(*s, f);
        }
    }
}

static void write_junit(const char *path) {
    FILE *f = fopen(path, "w");
    int i;
    if (!f) {
        fprintf(stderr, "cannot write %s\n", path);
        return;
    }
    fprintf(f, "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
    fprintf(f, "<testsuite name=\"ios-stcompat-core\" tests=\"%d\" failures=\"%d\">\n", g_ncases, g_failed_cases);
    for (i = 0; i < g_ncases; i++) {
        fprintf(f, "  <testcase classname=\"core.%s\" name=\"", g_cases[i].suite);
        xml_escape(f, g_cases[i].name);
        fprintf(f, "\"");
        if (g_cases[i].failed) {
            fprintf(f, "><failure message=\"");
            xml_escape(f, g_cases[i].message);
            fprintf(f, "\"/></testcase>\n");
        } else {
            fprintf(f, "/>\n");
        }
    }
    fprintf(f, "</testsuite>\n");
    fclose(f);
}

int main(int argc, char **argv) {
    const char *junit = NULL;
    int i;
    for (i = 1; i < argc; i++) {
        if (strcmp(argv[i], "--spec") == 0 && i + 1 < argc) {
            ygt_spec_dir = argv[++i];
        } else if (strcmp(argv[i], "--junit") == 0 && i + 1 < argc) {
            junit = argv[++i];
        } else {
            fprintf(stderr, "usage: %s --spec <spec dir> [--junit file]\n", argv[0]);
            return 2;
        }
    }
    suite_crypto();
    suite_sign();
    suite_json();
    suite_coretype();
    suite_params();
    suite_multipart();
    suite_audio();
    suite_retry();
    suite_errors();
    suite_envelope();
    if (junit) write_junit(junit);
    printf("\nC core tests: %d cases, %d failed cases, %d checks, %d failed checks\n", g_ncases, g_failed_cases,
           ygt_checks, g_failed_checks);
    return g_failed_cases ? 1 : 0;
}
