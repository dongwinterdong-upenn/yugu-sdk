/*
 * Copyright 2026 优谷雅言 open.shengzhiai.com
 * SPDX-License-Identifier: Apache-2.0
 *
 * Integration driver: runs one Shengtong file evaluation through the C core exactly as
 * KYTestEngine does on iOS (coreType check, local audio checks, field mapping, signing,
 * multipart, retry controller, envelope), with a POSIX socket HTTP/1.1 transport standing in
 * for NSURLSession. Prints one JSON line:
 *
 *   {"tokenId":"..","outcome":"result|error","attempts":N,"replayed":0|1,"elapsedMs":N,"json":{..}}
 *
 * Usage: it_driver (--port N [--host 127.0.0.1] | --base-url URL)
 *        [--app-key K | --app-key-env VAR] [--secret S | --secret-env VAR] [--core-type T]
 *        [--ref-text S] [--ref-pinyin S] [--audio PATH] [--audio-synth-ms N] [--token T]
 *        [--auto-retry] [--err-id N]... [--max-retries N] [--read-timeout-ms N]
 *        [--connect-timeout-ms N] [--ca-file PEM] [--get-param] [--attach-audio-url] [--user-id U]
 *        [--fault F] [--scale X] [--custom K=V]...
 *
 * --base-url goes through the same resolution as KYStartEngineConfig.server. https needs a build
 * with -DYGST_IT_TLS and -lssl -lcrypto: OpenSSL with SNI, TLS 1.2 or later and certificate plus
 * host name verification against the system CA store, or against --ca-file. --app-key-env and
 * --secret-env read the keys from environment variables, so sandbox keys never appear on a
 * command line; the driver never prints them except as applicationId inside the envelope.
 */
#define _POSIX_C_SOURCE 200809L

#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <netdb.h>
#include <netinet/in.h>
#include <poll.h>
#include <signal.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <sys/types.h>
#include <time.h>
#include <unistd.h>

#ifdef YGST_IT_TLS
#include <openssl/err.h>
#include <openssl/ssl.h>
#include <openssl/x509v3.h>
#endif

#include "ygst_core.h"

#ifndef MSG_NOSIGNAL
#define MSG_NOSIGNAL 0 /* macOS: main ignores SIGPIPE instead */
#endif

static int64_t now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    return (int64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

static int64_t mono_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

static void sleep_ms(long ms) {
    struct timespec ts;
    if (ms <= 0) return;
    ts.tv_sec = ms / 1000;
    ts.tv_nsec = (ms % 1000) * 1000000L;
    while (nanosleep(&ts, &ts) != 0 && errno == EINTR) {
    }
}

static void random_bytes(unsigned char *out, size_t n) {
    FILE *f = fopen("/dev/urandom", "rb");
    size_t got = f ? fread(out, 1, n, f) : 0;
    if (f) fclose(f);
    if (got != n) {
        size_t i;
        for (i = 0; i < n; i++) out[i] = (unsigned char)(rand() & 0xFF);
    }
}

static char *read_file(const char *path, size_t *len) {
    FILE *f = fopen(path, "rb");
    char *buf;
    long n;
    if (!f) return NULL;
    fseek(f, 0, SEEK_END);
    n = ftell(f);
    rewind(f);
    buf = (char *)malloc(n > 0 ? (size_t)n : 1);
    if (!buf || (n > 0 && fread(buf, 1, (size_t)n, f) != (size_t)n)) {
        free(buf);
        fclose(f);
        return NULL;
    }
    fclose(f);
    *len = (size_t)n;
    return buf;
}

/* ------------------------------------------------------------------ HTTP transport */

typedef struct {
    int status;
    int local_code;
    long retry_after_ms;
    int replayed;
    ygst_buf body;
    char message[256];
} http_result;

/* scheme://authority[/prefix] of a resolved base URL */
typedef struct {
    int tls;
    int host_is_ip;
    char host[256];      /* without IPv6 brackets: getaddrinfo, SNI and certificate check */
    char authority[300]; /* Host header */
    char port[8];
    size_t path_offset; /* the request path starts here in the base URL and in every URL built on it */
} target;

typedef struct {
    int fd;
    int clean; /* the whole response was read: close the TLS session politely */
#ifdef YGST_IT_TLS
    SSL_CTX *ctx;
    SSL *ssl;
#endif
} conn;

#if defined(__GNUC__) || defined(__clang__)
static void set_error(http_result *r, int code, const char *fmt, ...) __attribute__((format(printf, 3, 4)));
#endif

static void set_error(http_result *r, int code, const char *fmt, ...) {
    va_list ap;
    r->local_code = code;
    va_start(ap, fmt);
    vsnprintf(r->message, sizeof r->message, fmt, ap);
    va_end(ap);
}

static int parse_target(const char *base, target *t) {
    const char *a;
    const char *end;
    const char *h;
    const char *he;
    const char *port;
    size_t n;
    memset(t, 0, sizeof *t);
    if (strncmp(base, "https://", 8) == 0) {
        t->tls = 1;
        a = base + 8;
        snprintf(t->port, sizeof t->port, "443");
    } else if (strncmp(base, "http://", 7) == 0) {
        a = base + 7;
        snprintf(t->port, sizeof t->port, "80");
    } else {
        return -1;
    }
    end = a + strcspn(a, "/");
    n = (size_t)(end - a);
    if (n == 0 || n >= sizeof t->authority || memchr(a, '@', n)) return -1;
    memcpy(t->authority, a, n);
    t->authority[n] = 0;
    t->path_offset = (size_t)(end - base);
    h = t->authority;
    if (*h == '[') {
        h++;
        he = strchr(h, ']');
        if (!he) return -1;
        port = he + 1;
        t->host_is_ip = 1;
    } else {
        he = h + strcspn(h, ":");
        port = he;
    }
    if (he == h || (size_t)(he - h) >= sizeof t->host) return -1;
    memcpy(t->host, h, (size_t)(he - h));
    t->host[he - h] = 0;
    if (*port == ':') {
        size_t pl = strlen(port + 1);
        if (pl == 0 || pl >= sizeof t->port || strspn(port + 1, "0123456789") != pl) return -1;
        memcpy(t->port, port + 1, pl + 1);
    } else if (*port) {
        return -1;
    }
    if (!t->host_is_ip) {
        unsigned char tmp[16];
        t->host_is_ip = inet_pton(AF_INET, t->host, tmp) == 1;
    }
    return 0;
}

static int wait_fd(int fd, short events, int timeout_ms) {
    struct pollfd p;
    int rc;
    p.fd = fd;
    p.events = events;
    p.revents = 0;
    do {
        rc = poll(&p, 1, timeout_ms);
    } while (rc < 0 && errno == EINTR);
    return rc;
}

static int connect_with_timeout(int fd, const struct sockaddr *sa, socklen_t len, int timeout_ms) {
    int flags = fcntl(fd, F_GETFL, 0);
    int rc;
    if (flags < 0 || fcntl(fd, F_SETFL, flags | O_NONBLOCK) == -1) return -1;
    rc = connect(fd, sa, len);
    if (rc != 0 && errno == EINPROGRESS) {
        int err = 0;
        socklen_t el = sizeof err;
        rc = wait_fd(fd, POLLOUT, timeout_ms);
        if (rc == 0) {
            errno = ETIMEDOUT;
            return -1;
        }
        if (rc < 0 || getsockopt(fd, SOL_SOCKET, SO_ERROR, &err, &el) != 0) return -1;
        if (err != 0) {
            errno = err;
            return -1;
        }
        rc = 0;
    }
    if (rc != 0) return -1;
    return fcntl(fd, F_SETFL, flags) == -1 ? -1 : 0;
}

#ifdef YGST_IT_TLS
static void tls_error(http_result *r, const char *what, int ret, SSL *ssl, int timeout_ms) {
    int e = SSL_get_error(ssl, ret);
    int saved = errno;
    unsigned long q = ERR_get_error();
    char buf[200];
    if (e == SSL_ERROR_SYSCALL && q == 0 && (saved == EAGAIN || saved == EWOULDBLOCK)) {
        set_error(r, YGST_LOCAL_TIMEOUT, "timeout: %s timeout after %d ms", what, timeout_ms);
        return;
    }
    if (q) {
        ERR_error_string_n(q, buf, sizeof buf);
    } else {
        snprintf(buf, sizeof buf, "%s", e == SSL_ERROR_SYSCALL && ret == 0 ? "connection closed" : strerror(saved));
    }
    set_error(r, YGST_LOCAL_NETWORK, "network error: %s failed: %s", what, buf);
    ERR_clear_error();
}

static int tls_open(conn *c, const target *t, const char *ca_file, int io_timeout_ms, http_result *r) {
    struct timeval tv;
    int ret;
    long vr;
    c->ctx = SSL_CTX_new(TLS_client_method());
    if (!c->ctx) {
        set_error(r, YGST_LOCAL_NETWORK, "network error: TLS setup failed");
        return -1;
    }
    SSL_CTX_set_min_proto_version(c->ctx, TLS1_2_VERSION);
    SSL_CTX_set_verify(c->ctx, SSL_VERIFY_PEER, NULL);
#ifdef SSL_OP_IGNORE_UNEXPECTED_EOF
    SSL_CTX_set_options(c->ctx, SSL_OP_IGNORE_UNEXPECTED_EOF);
#endif
    if ((ca_file ? SSL_CTX_load_verify_locations(c->ctx, ca_file, NULL) : SSL_CTX_set_default_verify_paths(c->ctx)) != 1) {
        set_error(r, YGST_LOCAL_TLS, "TLS certificate check failed: cannot load CA certificates%s%s",
                  ca_file ? " from " : "", ca_file ? ca_file : "");
        ERR_clear_error();
        return -1;
    }
    c->ssl = SSL_new(c->ctx);
    if (!c->ssl || SSL_set_fd(c->ssl, c->fd) != 1) {
        set_error(r, YGST_LOCAL_NETWORK, "network error: TLS setup failed");
        return -1;
    }
    if (t->host_is_ip) {
        X509_VERIFY_PARAM_set1_ip_asc(SSL_get0_param(c->ssl), t->host);
    } else {
        SSL_set_tlsext_host_name(c->ssl, t->host);
        SSL_set1_host(c->ssl, t->host);
    }
    /* backstop for a TLS record that arrives in pieces: poll alone cannot bound SSL_read */
    tv.tv_sec = io_timeout_ms / 1000;
    tv.tv_usec = (io_timeout_ms % 1000) * 1000;
    setsockopt(c->fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof tv);
    setsockopt(c->fd, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof tv);
    ERR_clear_error();
    ret = SSL_connect(c->ssl);
    if (ret != 1) {
        vr = SSL_get_verify_result(c->ssl);
        if (vr != X509_V_OK) {
            set_error(r, YGST_LOCAL_TLS, "TLS certificate check failed: %s", X509_verify_cert_error_string(vr));
            ERR_clear_error();
        } else {
            tls_error(r, "TLS handshake", ret, c->ssl, io_timeout_ms);
        }
        return -1;
    }
    return 0;
}
#endif

static int conn_open(const target *t, const char *ca_file, int connect_timeout_ms, int io_timeout_ms, conn *c,
                     http_result *r) {
    struct addrinfo hints;
    struct addrinfo *res = NULL;
    struct addrinfo *ai;
    int rc;
    int err = ECONNREFUSED;
    memset(c, 0, sizeof *c);
    c->fd = -1;
    memset(&hints, 0, sizeof hints);
    hints.ai_family = AF_UNSPEC;
    hints.ai_socktype = SOCK_STREAM;
    rc = getaddrinfo(t->host, t->port, &hints, &res);
    if (rc != 0) {
        set_error(r, YGST_LOCAL_NETWORK, "network error: cannot resolve %s: %s", t->host, gai_strerror(rc));
        return -1;
    }
    for (ai = res; ai; ai = ai->ai_next) {
        int fd = socket(ai->ai_family, ai->ai_socktype, ai->ai_protocol);
        if (fd < 0) {
            err = errno;
            continue;
        }
        if (connect_with_timeout(fd, ai->ai_addr, ai->ai_addrlen, connect_timeout_ms) == 0) {
            c->fd = fd;
            break;
        }
        err = errno;
        close(fd);
    }
    freeaddrinfo(res);
    if (c->fd < 0) {
        if (err == ETIMEDOUT) {
            set_error(r, YGST_LOCAL_TIMEOUT, "timeout: connect timeout after %d ms", connect_timeout_ms);
        } else {
            set_error(r, YGST_LOCAL_NETWORK, "network error: connect failed: %s", strerror(err));
        }
        return -1;
    }
    if (!t->tls) return 0;
#ifdef YGST_IT_TLS
    return tls_open(c, t, ca_file, io_timeout_ms, r);
#else
    (void)ca_file;
    (void)io_timeout_ms;
    set_error(r, YGST_LOCAL_NETWORK, "network error: https needs it_driver built with -DYGST_IT_TLS");
    return -1;
#endif
}

static int conn_write_all(conn *c, const unsigned char *p, size_t n, int timeout_ms, http_result *r) {
    (void)timeout_ms;
    while (n > 0) {
        ssize_t w;
#ifdef YGST_IT_TLS
        if (c->ssl) {
            int ret;
            ERR_clear_error();
            ret = SSL_write(c->ssl, p, n > 16384 ? 16384 : (int)n);
            if (ret <= 0) {
                tls_error(r, "send", ret, c->ssl, timeout_ms);
                return -1;
            }
            w = ret;
        } else
#endif
        {
            w = send(c->fd, p, n, MSG_NOSIGNAL);
            if (w < 0 && errno == EINTR) continue;
            if (w <= 0) {
                set_error(r, YGST_LOCAL_NETWORK, "network error: send failed: %s", strerror(errno));
                return -1;
            }
        }
        p += w;
        n -= (size_t)w;
    }
    return 0;
}

/* > 0 bytes read, 0 end of stream, -1 failure or timeout with r set */
static long conn_read(conn *c, unsigned char *buf, size_t cap, int timeout_ms, http_result *r) {
    for (;;) {
        int rc = 1;
#ifdef YGST_IT_TLS
        if (!c->ssl || SSL_pending(c->ssl) == 0)
#endif
            rc = wait_fd(c->fd, POLLIN, timeout_ms);
        if (rc == 0) {
            set_error(r, YGST_LOCAL_TIMEOUT, "timeout: read timeout after %d ms", timeout_ms);
            return -1;
        }
#ifdef YGST_IT_TLS
        if (c->ssl) {
            int ret;
            int e;
            ERR_clear_error();
            ret = SSL_read(c->ssl, buf, cap > 65536 ? 65536 : (int)cap);
            if (ret > 0) return ret;
            e = SSL_get_error(c->ssl, ret);
            if (e == SSL_ERROR_ZERO_RETURN) return 0;
            if (e == SSL_ERROR_WANT_READ || e == SSL_ERROR_WANT_WRITE) continue;
            if (e == SSL_ERROR_SYSCALL && ret == 0 && ERR_peek_error() == 0) return 0;
            tls_error(r, "read", ret, c->ssl, timeout_ms);
            return -1;
        }
#endif
        {
            ssize_t got = recv(c->fd, buf, cap, 0);
            if (got < 0 && errno == EINTR) continue;
            if (got < 0) {
                set_error(r, YGST_LOCAL_NETWORK, "network error: recv failed: %s", strerror(errno));
                return -1;
            }
            return (long)got;
        }
    }
}

static void conn_close(conn *c) {
#ifdef YGST_IT_TLS
    if (c->ssl) {
        if (c->clean) SSL_shutdown(c->ssl); /* sends close_notify, does not wait for the peer's */
        SSL_free(c->ssl);
        c->ssl = NULL;
    }
    if (c->ctx) {
        SSL_CTX_free(c->ctx);
        c->ctx = NULL;
    }
#endif
    if (c->fd >= 0) close(c->fd);
    c->fd = -1;
}

static const char *find_header(const char *headers, const char *name, char *out, size_t cap) {
    const char *p = headers;
    size_t n = strlen(name);
    while (p && *p) {
        const char *eol = strstr(p, "\r\n");
        size_t len = eol ? (size_t)(eol - p) : strlen(p);
        if (len > n && p[n] == ':') {
            size_t i;
            int same = 1;
            for (i = 0; i < n; i++) {
                char a = p[i], b = name[i];
                if (a >= 'A' && a <= 'Z') a = (char)(a + 32);
                if (b >= 'A' && b <= 'Z') b = (char)(b + 32);
                if (a != b) {
                    same = 0;
                    break;
                }
            }
            if (same) {
                const char *v = p + n + 1;
                size_t vl;
                while (*v == ' ') v++;
                vl = (size_t)((p + len) - v);
                if (vl >= cap) vl = cap - 1;
                memcpy(out, v, vl);
                out[vl] = 0;
                return out;
            }
        }
        p = eol ? eol + 2 : NULL;
    }
    return NULL;
}

#define NOT_FOUND ((size_t)-1)

static size_t find_crlf(const unsigned char *in, size_t n, size_t pos) {
    size_t i;
    for (i = pos; i + 1 < n; i++) {
        if (in[i] == '\r' && in[i + 1] == '\n') return i;
    }
    return NOT_FOUND;
}

/* Start and header length of the final response in raw, skipping interim 1xx responses. */
static int locate_final(const unsigned char *d, size_t n, size_t *start, size_t *head_len) {
    size_t pos = 0;
    for (;;) {
        size_t i = pos;
        size_t end = NOT_FOUND;
        for (; i + 3 < n; i++) {
            if (d[i] == '\r' && d[i + 1] == '\n' && d[i + 2] == '\r' && d[i + 3] == '\n') {
                end = i + 4;
                break;
            }
        }
        if (end == NOT_FOUND) return 0;
        if (end - pos > 12 && memcmp(d + pos, "HTTP/1.", 7) == 0 && d[pos + 9] == '1' &&
            !(d[pos + 10] == '0' && d[pos + 11] == '1')) {
            pos = end; /* 100 Continue and other interim 1xx responses; 101 is final */
            continue;
        }
        *start = pos;
        *head_len = end - pos;
        return 1;
    }
}

/* 0 when the body is a whole chunked stream (last chunk and trailers), 1 when more is needed */
static int chunked_pending(const unsigned char *in, size_t n) {
    size_t pos = 0;
    for (;;) {
        size_t eol = find_crlf(in, n, pos);
        unsigned long size;
        char line[32];
        if (eol == NOT_FOUND || eol - pos >= sizeof line) return 1;
        memcpy(line, in + pos, eol - pos);
        line[eol - pos] = 0;
        size = strtoul(line, NULL, 16);
        pos = eol + 2;
        if (size == 0) {
            for (;;) { /* trailers end with an empty line */
                eol = find_crlf(in, n, pos);
                if (eol == NOT_FOUND) return 1;
                if (eol == pos) return 0;
                pos = eol + 2;
            }
        }
        if (size > n - pos || n - pos - size < 2) return 1;
        pos += size + 2;
    }
}

static char *dup_range(const unsigned char *p, size_t n) {
    char *s = (char *)malloc(n + 1);
    if (!s) return NULL;
    memcpy(s, p, n);
    s[n] = 0;
    return s;
}

/* 1 when raw holds the whole final response, so a server that keeps the connection open does
 * not hold the read until the timeout */
static int response_complete(const ygst_buf *raw) {
    size_t start, hl, bl;
    char tmp[64];
    char *head;
    int status;
    int done = 0;
    if (!raw->data || !locate_final(raw->data, raw->len, &start, &hl)) return 0;
    head = dup_range(raw->data + start, hl);
    if (!head) return 0;
    status = atoi(head + 9);
    bl = raw->len - start - hl;
    if (status == 204 || status == 304) {
        done = 1;
    } else if (find_header(head, "Transfer-Encoding", tmp, sizeof tmp) && strstr(tmp, "chunked")) {
        done = !chunked_pending(raw->data + start + hl, bl);
    } else if (find_header(head, "Content-Length", tmp, sizeof tmp)) {
        done = bl >= (size_t)strtoul(tmp, NULL, 10);
    }
    free(head);
    return done;
}

static int dechunk(const unsigned char *in, size_t n, ygst_buf *out) {
    size_t pos = 0;
    for (;;) {
        size_t eol = find_crlf(in, n, pos);
        unsigned long size;
        char line[32];
        if (eol == NOT_FOUND || eol - pos >= sizeof line) return -1;
        memcpy(line, in + pos, eol - pos);
        line[eol - pos] = 0;
        size = strtoul(line, NULL, 16);
        pos = eol + 2;
        if (size == 0) return 0;
        if (size > n - pos) return -1;
        ygst_buf_append(out, in + pos, size);
        pos += size + 2;
    }
}

static void http_post(const target *t, const char *ca_file, int connect_timeout_ms, const char *request_head,
                      const ygst_buf *body, int read_timeout_ms, http_result *r) {
    conn c;
    ygst_buf raw;
    size_t start = 0;
    size_t head_len = 0;
    char tmp[64];
    r->status = 0;
    r->local_code = 0;
    r->retry_after_ms = -1;
    r->replayed = 0;
    r->message[0] = 0;
    ygst_buf_reset(&r->body);
    if (conn_open(t, ca_file, connect_timeout_ms, read_timeout_ms, &c, r) != 0 ||
        conn_write_all(&c, (const unsigned char *)request_head, strlen(request_head), read_timeout_ms, r) != 0 ||
        conn_write_all(&c, body->data, body->len, read_timeout_ms, r) != 0) {
        conn_close(&c);
        return;
    }
    ygst_buf_init(&raw);
    for (;;) {
        unsigned char chunk[8192];
        long got = conn_read(&c, chunk, sizeof chunk, read_timeout_ms, r);
        if (got <= 0) break;
        ygst_buf_append(&raw, chunk, (size_t)got);
        if (response_complete(&raw)) {
            c.clean = 1;
            break;
        }
    }
    conn_close(&c);
    if (r->local_code) {
        ygst_buf_free(&raw);
        return;
    }
    ygst_buf_cstr(&raw);
    if (!raw.data || !locate_final(raw.data, raw.len, &start, &head_len) ||
        strncmp((const char *)raw.data + start, "HTTP/1.", 7) != 0) {
        set_error(r, YGST_LOCAL_NETWORK, "network error: connection closed before a complete response");
        ygst_buf_free(&raw);
        return;
    }
    {
        char *head = dup_range(raw.data + start, head_len);
        const unsigned char *b = raw.data + start + head_len;
        size_t bl = raw.len - start - head_len;
        if (!head) {
            set_error(r, YGST_LOCAL_NETWORK, "network error: out of memory");
            ygst_buf_free(&raw);
            return;
        }
        r->status = atoi(head + 9);
        if (find_header(head, "Retry-After", tmp, sizeof tmp)) {
            long ms;
            if (ygst_parse_retry_after(tmp, now_ms(), &ms) == 0) r->retry_after_ms = ms;
        }
        if (find_header(head, "Idempotency-Replayed", tmp, sizeof tmp) && strcmp(tmp, "true") == 0) r->replayed = 1;
        if (find_header(head, "Transfer-Encoding", tmp, sizeof tmp) && strstr(tmp, "chunked")) {
            if (dechunk(b, bl, &r->body) != 0) {
                r->status = 0;
                set_error(r, YGST_LOCAL_NETWORK, "network error: truncated chunked body");
            }
        } else if (find_header(head, "Content-Length", tmp, sizeof tmp)) {
            size_t cl = (size_t)strtoul(tmp, NULL, 10);
            if (cl > bl) {
                r->status = 0;
                set_error(r, YGST_LOCAL_NETWORK, "network error: truncated body");
            } else {
                ygst_buf_append(&r->body, b, cl);
            }
        } else {
            ygst_buf_append(&r->body, b, bl);
        }
        free(head);
    }
    ygst_buf_cstr(&r->body);
    ygst_buf_free(&raw);
}

/* ------------------------------------------------------------------ main */

typedef struct {
    const char *host;
    int port;
    const char *base_url;
    const char *ca_file;
    const char *app_key;
    const char *secret;
    const char *core_type;
    const char *ref_text;
    const char *ref_pinyin;
    const char *audio;
    int synth_ms;
    const char *token;
    int auto_retry;
    long long err_ids[8];
    int n_err_ids;
    int max_retries; /* -1: the policy default */
    int read_timeout_ms;
    int connect_timeout_ms;
    int get_param;
    int attach_audio_url; /* KYTestConfig.attachAudioUrl */
    const char *user_id;
    const char *fault;
    const char *fixed_nonce; /* test only: reuse one nonce on every attempt */
    double scale;
    ygst_fields custom;
} options;

static void emit(const char *token, const char *outcome, int attempts, int replayed, long elapsed, const char *json) {
    printf("{\"tokenId\":\"%s\",\"outcome\":\"%s\",\"attempts\":%d,\"replayed\":%d,\"elapsedMs\":%ld,\"json\":%s}\n", token,
           outcome, attempts, replayed, elapsed, json);
    fflush(stdout);
}

static void emit_error(const options *o, const char *token, int err_id, const char *message, int attempts,
                       int64_t started) {
    ygst_buf e;
    ygst_buf_init(&e);
    ygst_envelope_error(&e, token, err_id, message, o->app_key);
    emit(token, "error", attempts, 0, (long)(mono_ms() - started), ygst_buf_cstr(&e));
    ygst_buf_free(&e);
}

/* a key from the environment; the value itself is never printed */
static const char *key_from_env(const char *var) {
    const char *v = getenv(var);
    if (!v || !*v) {
        fprintf(stderr, "environment variable %s is not set\n", var);
        return NULL;
    }
    return v;
}

int main(int argc, char **argv) {
    options o;
    char token[33];
    char core_type[64];
    ygst_ct_status cts;
    unsigned char *audio = NULL;
    size_t audio_len = 0;
    const char *ext = "wav";
    ygst_test_params tp;
    ygst_fields fields;
    char signature[45];
    char boundary[64];
    char content_type[128];
    char base[512];
    char url[600];
    const char *path;
    target tgt;
    ygst_buf body;
    ygst_retry_policy policy;
    ygst_call call;
    http_result hr;
    int64_t started = mono_ms();
    int i;
    int err;
    int last_replayed = 0;

    signal(SIGPIPE, SIG_IGN); /* a peer that closes early is an error result, not a crash */
    memset(&o, 0, sizeof o);
    o.host = "127.0.0.1";
    o.app_key = "mock-app-key";
    o.secret = "mock-secret-key";
    o.core_type = "sent.eval.cn";
    o.ref_text = "今天天气很好";
    o.max_retries = -1;
    o.read_timeout_ms = 5000;
    o.connect_timeout_ms = 20000; /* KYStartEngineConfig.connectTimeout */
    o.user_id = "it-user";
    ygst_fields_init(&o.custom);
    for (i = 1; i < argc; i++) {
        const char *a = argv[i];
        const char *v = i + 1 < argc ? argv[i + 1] : NULL;
#define OPT(name) (strcmp(a, name) == 0 && v && (++i, 1))
        if (OPT("--port")) o.port = atoi(v);
        else if (OPT("--host")) o.host = v;
        else if (OPT("--base-url")) o.base_url = v;
        else if (OPT("--ca-file")) o.ca_file = v;
        else if (OPT("--app-key")) o.app_key = v;
        else if (OPT("--secret")) o.secret = v;
        else if (OPT("--app-key-env")) {
            if (!(o.app_key = key_from_env(v))) return 2;
        } else if (OPT("--secret-env")) {
            if (!(o.secret = key_from_env(v))) return 2;
        } else if (OPT("--max-retries")) o.max_retries = atoi(v);
        else if (OPT("--connect-timeout-ms")) o.connect_timeout_ms = atoi(v);
        else if (OPT("--core-type")) o.core_type = v;
        else if (OPT("--ref-text")) o.ref_text = v;
        else if (OPT("--ref-pinyin")) o.ref_pinyin = v;
        else if (OPT("--audio")) o.audio = v;
        else if (OPT("--audio-synth-ms")) o.synth_ms = atoi(v);
        else if (OPT("--token")) o.token = v;
        else if (OPT("--err-id")) {
            if (o.n_err_ids < 8) o.err_ids[o.n_err_ids++] = atoll(v);
        } else if (OPT("--read-timeout-ms")) o.read_timeout_ms = atoi(v);
        else if (OPT("--user-id")) o.user_id = v;
        else if (OPT("--fault")) o.fault = v;
        else if (OPT("--fixed-nonce")) o.fixed_nonce = v;
        else if (OPT("--scale")) o.scale = atof(v);
        else if (OPT("--custom")) {
            const char *eq = strchr(v, '=');
            if (eq) {
                char k[128];
                size_t kl = (size_t)(eq - v) < sizeof k - 1 ? (size_t)(eq - v) : sizeof k - 1;
                memcpy(k, v, kl);
                k[kl] = 0;
                ygst_fields_set(&o.custom, k, eq + 1);
            }
        } else if (strcmp(a, "--auto-retry") == 0) o.auto_retry = 1;
        else if (strcmp(a, "--get-param") == 0) o.get_param = 1;
        else if (strcmp(a, "--attach-audio-url") == 0) o.attach_audio_url = 1;
        else {
            fprintf(stderr, "unknown or incomplete option %s\n", a);
            return 2;
        }
#undef OPT
    }
    if (o.base_url) {
        /* the KYStartEngineConfig.server rules: Shengtong hosts and an empty value give the default */
        ygst_base_kind kind = ygst_resolve_base_url(o.base_url, base, sizeof base);
        if (kind == YGST_BASE_ERR_SCHEME || kind == YGST_BASE_ERR_HOST) {
            fprintf(stderr, "--base-url %s is not an http or https URL with a host\n", o.base_url);
            return 2;
        }
    } else if (o.port) {
        snprintf(base, sizeof base, "http://%s:%d", o.host, o.port);
    } else {
        fprintf(stderr, "--port or --base-url is required\n");
        return 2;
    }
    if (parse_target(base, &tgt) != 0) {
        fprintf(stderr, "cannot parse the base URL %s\n", base);
        return 2;
    }
#ifndef YGST_IT_TLS
    if (tgt.tls) {
        fprintf(stderr, "%s needs it_driver built with -DYGST_IT_TLS and -lssl -lcrypto\n", base);
        return 2;
    }
#endif

    /* tokenId, also the Idempotency-Key of every attempt */
    if (o.token) {
        snprintf(token, sizeof token, "%s", o.token);
    } else {
        unsigned char rnd[16];
        random_bytes(rnd, sizeof rnd);
        ygst_token_id(rnd, token);
    }

    /* 1. coreType and refText: answered locally, never reach the network */
    cts = ygst_coretype_resolve(o.core_type, 0, core_type, sizeof core_type);
    err = ygst_validate_request(cts, core_type, o.ref_text, o.ref_pinyin);
    if (err) {
        emit_error(&o, token, err, ygst_compat_message(err), 0, started);
        return 0;
    }
    /* 3. audio */
    if (o.synth_ms > 0 || (o.audio && strcmp(o.audio, "-") == 0)) {
        size_t pcm = (size_t)o.synth_ms * 32;
        audio_len = 44 + pcm;
        audio = (unsigned char *)calloc(1, audio_len);
        ygst_wav_header(audio, (uint32_t)pcm, 16000, 1, 16);
        for (i = 0; (size_t)i < pcm / 2; i++) {
            int16_t s = (int16_t)((i % 64) < 32 ? 3000 : -3000);
            audio[44 + 2 * i] = (unsigned char)((uint16_t)s & 0xFF);
            audio[45 + 2 * i] = (unsigned char)((uint16_t)s >> 8);
        }
    } else if (o.audio) {
        const char *dot = strrchr(o.audio, '.');
        audio = (unsigned char *)read_file(o.audio, &audio_len);
        if (!audio) {
            char msg[600];
            snprintf(msg, sizeof msg, "%s: %s", ygst_compat_message(YGST_ERRID_AUDIO_FILE_MISSING), o.audio);
            emit_error(&o, token, YGST_ERRID_AUDIO_FILE_MISSING, msg, 0, started);
            return 0;
        }
        if (dot) ext = ygst_audio_ext(dot + 1);
    } else {
        audio = (unsigned char *)calloc(1, 1);
        audio_len = 0;
    }
    err = ygst_audio_check_upload(audio, audio_len);
    if (err) {
        emit_error(&o, token, err, ygst_compat_message(err), 0, started);
        free(audio);
        return 0;
    }

    /* 4. form fields, signature, multipart */
    ygst_test_params_init(&tp);
    tp.core_type = core_type;
    tp.ref_text = o.ref_text;
    tp.ref_pinyin = o.ref_pinyin;
    tp.phoneme_output = 1; /* KYTestConfig default */
    tp.attach_audio_url = o.attach_audio_url;
    tp.scale = o.scale;
    tp.custom_params = &o.custom;
    ygst_fields_init(&fields);
    ygst_params_to_fields(&tp, &fields);
    ygst_sign(&fields, o.secret, signature);
    ygst_buf_init(&body);
    for (;;) {
        unsigned char rnd[16];
        char filename[32];
        random_bytes(rnd, sizeof rnd);
        ygst_multipart_boundary(rnd, boundary);
        snprintf(filename, sizeof filename, "audio.%s", ext);
        ygst_buf_reset(&body);
        if (ygst_multipart_build(&fields, boundary, "audio", filename, ygst_audio_content_type(ext), audio, audio_len,
                                 &body) != -2)
            break;
    }
    ygst_multipart_content_type(boundary, content_type, sizeof content_type);
    ygst_build_url(base, core_type, url, sizeof url);
    path = url + tgt.path_offset;

    /* 5. attempts under the controller */
    ygst_retry_policy_default(&policy);
    if (o.max_retries >= 0) policy.max_retries = o.max_retries;
    policy.auto_retry = o.auto_retry;
    for (i = 0; i < o.n_err_ids; i++) {
        if (i == 0) policy.n_err_ids = 0;
        ygst_retry_policy_add_err_id(&policy, o.err_ids[i]);
    }
    ygst_call_init(&call, &policy, (uint64_t)now_ms(), mono_ms());
    ygst_buf_init(&hr.body);
    for (;;) {
        char head[2048];
        unsigned char nrnd[8];
        char nonce[17];
        ygst_outcome oc;
        ygst_classification cls;
        ygst_action act;
        random_bytes(nrnd, sizeof nrnd);
        ygst_hex_lower(nrnd, sizeof nrnd, nonce);
        if (o.fixed_nonce) snprintf(nonce, sizeof nonce, "%s", o.fixed_nonce);
        snprintf(head, sizeof head,
                 "POST %s HTTP/1.1\r\nHost: %s\r\nContent-Type: %s\r\nContent-Length: %zu\r\n"
                 "X-App-Key: %s\r\nX-Timestamp: %lld\r\nX-Nonce: %s\r\nX-Signature: %s\r\nIdempotency-Key: %s\r\n"
                 "User-Agent: %s\r\n%s%s%sConnection: close\r\n\r\n",
                 path, tgt.authority, content_type, body.len, o.app_key, (long long)(now_ms() / 1000), nonce,
                 signature, token, YGST_USER_AGENT, o.fault ? "X-Mock-Fault: " : "", o.fault ? o.fault : "",
                 o.fault ? "\r\n" : "");
        http_post(&tgt, o.ca_file, o.connect_timeout_ms, head, &body, o.read_timeout_ms, &hr);
        last_replayed = hr.replayed;
        memset(&oc, 0, sizeof oc);
        oc.http_status = hr.status;
        oc.local_code = hr.local_code;
        oc.body = (const char *)hr.body.data;
        oc.body_len = hr.body.len;
        oc.retry_after_ms = hr.retry_after_ms;
        oc.replayed = hr.replayed;
        oc.local_message = hr.message[0] ? hr.message : NULL;
        ygst_classification_init(&cls);
        ygst_classify(&oc, &cls);
        act = ygst_call_next(&call, &cls, hr.retry_after_ms, mono_ms());
        fprintf(stderr, "attempt %d: status=%d local=%d biz=%d retryable=%d -> action=%d delay=%ld err=%d %s\n",
                call.total_attempts, hr.status, hr.local_code, cls.biz_code, cls.retryable, act.kind, act.delay_ms,
                act.err_id, ygst_buf_cstr(&cls.message));
        if (act.kind == YGST_ACT_SUCCESS) {
            char dt[32];
            ygst_buf env, params;
            ygst_buf_init(&env);
            ygst_buf_init(&params);
            ygst_format_dt(now_ms(), 480, dt);
            if (o.get_param) {
                ygst_params_json(&params, o.app_key, o.user_id, now_ms() / 1000, "wav", 16000, 1, 2, core_type, token,
                                 &fields);
            }
            {
                char rid[256];
                snprintf(rid, sizeof rid, "%.*s", (int)cls.record_id_raw.n, cls.record_id_raw.p ? cls.record_id_raw.p : "");
                ygst_envelope_result(&env, token, cls.has_record_id ? rid : NULL, o.app_key, o.user_id,
                                 o.ref_text ? o.ref_text : "", dt, cls.result,
                                 o.get_param ? ygst_buf_cstr(&params) : NULL,
                                 cls.has_audio_url ? ygst_buf_cstr(&cls.audio_url) : NULL);
            }
            emit(token, "result", call.total_attempts, last_replayed, (long)(mono_ms() - started), ygst_buf_cstr(&env));
            ygst_buf_free(&env);
            ygst_buf_free(&params);
            ygst_classification_free(&cls);
            break;
        }
        if (act.kind == YGST_ACT_FAIL) {
            ygst_buf msg;
            ygst_buf_init(&msg);
            ygst_error_message(&cls, act.err_id, call.attempt, &msg);
            emit_error(&o, token, act.err_id, ygst_buf_cstr(&msg), call.total_attempts, started);
            ygst_buf_free(&msg);
            ygst_classification_free(&cls);
            break;
        }
        ygst_classification_free(&cls);
        sleep_ms(act.delay_ms);
        o.fault = NULL; /* a header fault applies to the first attempt only */
    }
    ygst_buf_free(&hr.body);
    ygst_buf_free(&body);
    ygst_fields_free(&fields);
    ygst_fields_free(&o.custom);
    free(audio);
    return 0;
}
