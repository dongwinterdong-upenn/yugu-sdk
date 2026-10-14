/*
 * Copyright 2026 优谷雅言 open.shengzhiai.com
 * SPDX-License-Identifier: Apache-2.0
 *
 * Integration driver: runs one Shengtong evaluation through the C core exactly as KYTestEngine
 * does on iOS (coreType check, local audio checks, field mapping, signing, multipart, retry
 * controller, envelope), with a POSIX socket HTTP/1.1 transport standing in for NSURLSession.
 * With --realtime it runs a realtime_feedback round instead: the PCM of --audio is captured in
 * steps of --chunk-bytes every --pace-ms, the round controller (ygst_rt) streams it over a
 * WebSocket client standing in for NSURLSessionWebSocketTask, progress frames become eof 0
 * envelopes, a failure before end falls back to the REST call at stop. --stream runs the same round
 * without realtime_feedback, the 2.1.1 default for recordings: no progress is asked for. --timing
 * stands for log level INFO (2.1.1): the REST request carries timing=1 and the parameter frame
 * "timing":1. Every evaluation that reached the platform reports the summary and detail lines of
 * ygst_timing (Server-Timing header, the platform timing object, phases). Prints one JSON line:
 *
 *   {"tokenId":"..","outcome":"result|error","attempts":N,"replayed":0|1,"elapsedMs":N,
 *    ["mode":"ws|rest|local","connections":N,"held":N,"restReason":"..","paramFrame":{..},
 *     "progress":[eof 0 envelopes],] ["timing":"<INFO line>","timingPhases":"<DEBUG line>",
 *     "timingStages":"<DEBUG line or empty>",]
 *    "json":{..}}
 *
 * Usage: it_driver (--port N [--host 127.0.0.1] | --base-url URL)
 *        [--app-key K | --app-key-env VAR] [--secret S | --secret-env VAR] [--core-type T]
 *        [--ref-text S] [--ref-pinyin S] [--audio PATH] [--audio-synth-ms N] [--token T]
 *        [--auto-retry] [--err-id N]... [--max-retries N] [--read-timeout-ms N]
 *        [--connect-timeout-ms N] [--ca-file PEM] [--get-param] [--attach-audio-url] [--user-id U]
 *        [--fault F] [--scale X] [--custom K=V]...
 *        [--timing] [(--realtime | --stream) [--chunk-bytes N] [--pace-ms N] [--feed-after-start]
 *         [--ws-fault F] [--ping-interval-ms N] [--pong-timeout-ms N] [--attempt-timeout-ms N]]
 *        [--core-type-enum N] [--skegn]
 *        [--tts --tts-text T [--tts-set NAME=VALUE]... [--idempotency-key K]]
 *        [--report RECORD_ID [--wait-for-final-ms N]]
 *
 * 2.2.0: an unsupported coreType answers 60003 with "不支持的评测内核：<coreType>"; --core-type-enum is
 * the KYTestType of a blank --core-type, --skegn resolves --core-type like skegn_start. --tts and
 * --report run TtsClient.tts and ReportClient.getReport through the C core the way YuguClients.m does
 * (signing, headers, retry controller, waitForFinalMs) and print one JSON line with "op" instead of
 * the evaluation line.
 *
 * 2.3.0: --report looks up again through reportStatus partial as well as pending, and prints isPartial.
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
    char server_timing[256]; /* Server-Timing header, "" when absent (2.1.1) */
    int64_t t_start;         /* monotonic: request started, body written, head complete, response complete */
    int64_t t_uploaded;
    int64_t t_headers;
    int64_t t_done;
    long long upload_bytes;  /* request head and body */
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
    r->server_timing[0] = 0;
    r->t_start = mono_ms();
    r->t_uploaded = 0;
    r->t_headers = 0;
    r->t_done = 0;
    r->upload_bytes = (long long)(strlen(request_head) + body->len);
    ygst_buf_reset(&r->body);
    if (conn_open(t, ca_file, connect_timeout_ms, read_timeout_ms, &c, r) != 0 ||
        conn_write_all(&c, (const unsigned char *)request_head, strlen(request_head), read_timeout_ms, r) != 0 ||
        conn_write_all(&c, body->data, body->len, read_timeout_ms, r) != 0) {
        conn_close(&c);
        r->t_done = mono_ms();
        return;
    }
    r->t_uploaded = mono_ms();
    ygst_buf_init(&raw);
    for (;;) {
        unsigned char chunk[8192];
        long got = conn_read(&c, chunk, sizeof chunk, read_timeout_ms, r);
        if (got <= 0) break;
        ygst_buf_append(&raw, chunk, (size_t)got);
        if (!r->t_headers) {
            size_t s0, h0;
            if (locate_final(raw.data, raw.len, &s0, &h0)) r->t_headers = mono_ms();
        }
        if (response_complete(&raw)) {
            c.clean = 1;
            break;
        }
    }
    conn_close(&c);
    r->t_done = mono_ms();
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
        find_header(head, "Server-Timing", r->server_timing, sizeof r->server_timing);
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

/* ------------------------------------------------------------------ WebSocket client */

/* RFC 6455 client of the realtime scenarios, test code only: handshake, masked client frames,
 * unmasked server frames with continuation, pings answered with pongs, close. It does not check
 * Sec-WebSocket-Accept (the core has no SHA-1; the peer is the mock or the platform). The
 * connection keeps its buffers after ws_close so that a message handler may close it while the
 * frames of the same read are being parsed. */
typedef struct {
    conn c;
    int open;
    ygst_buf rx;  /* received, not parsed yet */
    ygst_buf msg; /* message being assembled from fragments */
    int msg_op;
} ws_conn;

typedef enum { WS_EV_TEXT = 1, WS_EV_PONG = 2, WS_EV_CLOSED = 3 } ws_event;
typedef void (*ws_handler)(void *ctx, ws_event ev, const char *text, size_t n, int close_code);

static void ws_init(ws_conn *w) {
    memset(w, 0, sizeof *w);
    w->c.fd = -1;
    ygst_buf_init(&w->rx);
    ygst_buf_init(&w->msg);
}

static int ws_send(ws_conn *w, int opcode, const unsigned char *p, size_t n, http_result *r) {
    unsigned char *f;
    unsigned char mask[4];
    size_t hl = 0;
    size_t i;
    int rc;
    if (!w->open) {
        set_error(r, YGST_LOCAL_NETWORK, "network error: the connection is closed");
        return -1;
    }
    f = (unsigned char *)malloc(n + 14);
    if (!f) {
        set_error(r, YGST_LOCAL_NETWORK, "network error: out of memory");
        return -1;
    }
    f[hl++] = (unsigned char)(0x80 | opcode);
    if (n < 126) {
        f[hl++] = (unsigned char)(0x80 | n);
    } else if (n < 65536) {
        f[hl++] = 0x80 | 126;
        f[hl++] = (unsigned char)(n >> 8);
        f[hl++] = (unsigned char)(n & 0xFF);
    } else {
        f[hl++] = 0x80 | 127;
        for (i = 0; i < 8; i++) f[hl++] = (unsigned char)(((uint64_t)n >> (56 - 8 * i)) & 0xFF);
    }
    random_bytes(mask, sizeof mask);
    memcpy(f + hl, mask, 4);
    hl += 4;
    for (i = 0; i < n; i++) f[hl + i] = (unsigned char)(p[i] ^ mask[i & 3]);
    rc = conn_write_all(&w->c, f, hl + n, 5000, r);
    free(f);
    if (rc != 0) w->open = 0;
    return rc;
}

static void ws_close(ws_conn *w) {
    http_result r;
    unsigned char code[2] = {0x03, 0xE8}; /* 1000 */
    if (w->open) ws_send(w, 0x8, code, sizeof code, &r);
    w->open = 0;
    conn_close(&w->c);
}

static void ws_free(ws_conn *w) {
    ws_close(w);
    ygst_buf_free(&w->rx);
    ygst_buf_free(&w->msg);
}

/* 0: open. -1: failed, r says why; r->status is the HTTP status of a refused handshake. */
static int ws_open(ws_conn *w, const char *url, const char *ca_file, int timeout_ms, http_result *r) {
    ygst_buf http_url, head;
    target t;
    unsigned char key[16];
    char key_b64[32];
    int64_t deadline;
    ws_free(w);
    ws_init(w);
    r->status = 0;
    r->local_code = 0;
    r->message[0] = 0;
    ygst_buf_init(&http_url);
    ygst_buf_init(&head);
    if (strncmp(url, "wss://", 6) == 0) {
        ygst_buf_append_str(&http_url, "https://");
        ygst_buf_append_str(&http_url, url + 6);
    } else if (strncmp(url, "ws://", 5) == 0) {
        ygst_buf_append_str(&http_url, "http://");
        ygst_buf_append_str(&http_url, url + 5);
    }
    if (!http_url.len || parse_target(ygst_buf_cstr(&http_url), &t) != 0) {
        set_error(r, YGST_LOCAL_NETWORK, "network error: not a WebSocket URL");
        ygst_buf_free(&http_url);
        return -1;
    }
    if (conn_open(&t, ca_file, timeout_ms, timeout_ms, &w->c, r) != 0) {
        conn_close(&w->c);
        ygst_buf_free(&http_url);
        return -1;
    }
    random_bytes(key, sizeof key);
    ygst_base64_encode(key, sizeof key, key_b64, sizeof key_b64);
    ygst_buf_append_str(&head, "GET ");
    ygst_buf_append_str(&head, ygst_buf_cstr(&http_url) + t.path_offset);
    ygst_buf_append_str(&head, " HTTP/1.1\r\nHost: ");
    ygst_buf_append_str(&head, t.authority);
    ygst_buf_append_str(&head, "\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: ");
    ygst_buf_append_str(&head, key_b64);
    ygst_buf_append_str(&head, "\r\nSec-WebSocket-Version: 13\r\nUser-Agent: " YGST_USER_AGENT "\r\n\r\n");
    ygst_buf_free(&http_url);
    if (conn_write_all(&w->c, head.data, head.len, timeout_ms, r) != 0) {
        ygst_buf_free(&head);
        conn_close(&w->c);
        return -1;
    }
    ygst_buf_free(&head);
    deadline = mono_ms() + timeout_ms;
    for (;;) {
        unsigned char chunk[4096];
        long got;
        int64_t left;
        const char *end = w->rx.data ? strstr(ygst_buf_cstr(&w->rx), "\r\n\r\n") : NULL;
        if (end) {
            size_t hl = (size_t)(end - (const char *)w->rx.data) + 4;
            int status = (w->rx.len > 12 && memcmp(w->rx.data, "HTTP/1.1 ", 9) == 0) ? atoi((const char *)w->rx.data + 9) : 0;
            if (status != 101) {
                r->status = status;
                if (status) {
                    snprintf(r->message, sizeof r->message, "WebSocket handshake refused");
                } else {
                    set_error(r, YGST_LOCAL_NETWORK, "network error: not a WebSocket handshake answer");
                }
                conn_close(&w->c);
                return -1;
            }
            memmove(w->rx.data, w->rx.data + hl, w->rx.len - hl);
            w->rx.len -= hl;
            w->open = 1;
            return 0;
        }
        left = deadline - mono_ms();
        if (left <= 0) {
            set_error(r, YGST_LOCAL_TIMEOUT, "timeout: WebSocket handshake not answered within %d ms", timeout_ms);
            conn_close(&w->c);
            return -1;
        }
        got = conn_read(&w->c, chunk, sizeof chunk, (int)left, r);
        if (got <= 0) {
            if (got == 0) set_error(r, YGST_LOCAL_NETWORK, "network error: connection closed during the WebSocket handshake");
            conn_close(&w->c);
            return -1;
        }
        ygst_buf_append(&w->rx, chunk, (size_t)got);
    }
}

/* Hands every complete message in rx to h. Stops when the handler closed the connection. */
static int ws_parse(ws_conn *w, ws_handler h, void *ctx, http_result *r) {
    while (w->open && w->rx.len >= 2) {
        unsigned char *d = w->rx.data;
        size_t n = w->rx.len;
        size_t pos = 2;
        size_t i;
        uint64_t len = d[1] & 0x7F;
        int fin = (d[0] & 0x80) != 0;
        int op = d[0] & 0x0F;
        int masked = (d[1] & 0x80) != 0;
        unsigned char mk[4] = {0, 0, 0, 0};
        unsigned char *payload;
        ygst_buf one;
        if (len == 126) {
            if (n < 4) return 0;
            len = ((uint64_t)d[2] << 8) | d[3];
            pos = 4;
        } else if (len == 127) {
            if (n < 10) return 0;
            len = 0;
            for (i = 0; i < 8; i++) len = (len << 8) | d[2 + i];
            pos = 10;
        }
        if (masked) {
            if (n < pos + 4) return 0;
            memcpy(mk, d + pos, 4);
            pos += 4;
        }
        if (len > 16u * 1024u * 1024u) {
            set_error(r, YGST_LOCAL_NETWORK, "network error: frame of %llu bytes", (unsigned long long)len);
            ws_close(w);
            return -1;
        }
        if (n - pos < len) return 0;
        payload = d + pos;
        if (masked) {
            for (i = 0; i < len; i++) payload[i] ^= mk[i & 3];
        }
        /* take the frame out of rx before any handler runs */
        ygst_buf_init(&one);
        ygst_buf_append(&one, payload, (size_t)len);
        memmove(w->rx.data, w->rx.data + pos + len, n - pos - (size_t)len);
        w->rx.len = n - pos - (size_t)len;
        switch (op) {
        case 0x0:
        case 0x1:
        case 0x2:
            if (op != 0) {
                ygst_buf_reset(&w->msg);
                w->msg_op = op;
            }
            ygst_buf_append(&w->msg, one.data, one.len);
            if (fin) {
                ygst_buf msg = w->msg;
                ygst_buf_init(&w->msg);
                if (w->msg_op == 1) h(ctx, WS_EV_TEXT, ygst_buf_cstr(&msg), msg.len, 0);
                ygst_buf_free(&msg);
            }
            break;
        case 0x8: {
            int code = one.len >= 2 ? (one.data[0] << 8) | one.data[1] : 1005;
            ws_close(w);
            h(ctx, WS_EV_CLOSED, NULL, 0, code);
            ygst_buf_free(&one);
            return -1;
        }
        case 0x9:
            ws_send(w, 0xA, one.data, one.len, r);
            break;
        case 0xA:
            h(ctx, WS_EV_PONG, NULL, 0, 0);
            break;
        default:
            break;
        }
        ygst_buf_free(&one);
    }
    return 0;
}

/* Waits up to timeout_ms for data and hands the messages that arrived to h. -1 when the
 * connection ended or failed (r says why), 0 otherwise. */
static int ws_pump(ws_conn *w, int timeout_ms, ws_handler h, void *ctx, http_result *r) {
    unsigned char chunk[16384];
    long got;
    int readable;
    if (ws_parse(w, h, ctx, r) != 0 || !w->open) return -1;
#ifdef YGST_IT_TLS
    if (w->c.ssl && SSL_pending(w->c.ssl) > 0) {
        readable = 1;
    } else
#endif
    {
        readable = wait_fd(w->c.fd, POLLIN, timeout_ms) > 0;
    }
    if (!readable) return 0;
    got = conn_read(&w->c, chunk, sizeof chunk, 1000, r);
    if (got <= 0) {
        if (got == 0) set_error(r, YGST_LOCAL_NETWORK, "network error: the connection was closed");
        ws_close(w);
        return -1;
    }
    ygst_buf_append(&w->rx, chunk, (size_t)got);
    return ws_parse(w, h, ctx, r);
}

/* ------------------------------------------------------------------ evaluation */

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
    int timing;             /* 2.1.1: log level INFO, timing=1 and "timing":1 */
    /* the compat WebSocket round: realtime_feedback (--realtime) or the 2.1.1 default stream (--stream) */
    int realtime;           /* stream the round over the WebSocket */
    int progress;           /* realtime_feedback: ask the platform for progress frames */
    int chunk_bytes;        /* captured audio per step */
    int pace_ms;            /* between steps, 0: everything at once */
    int feed_after_start;   /* capture only once the platform has started */
    const char *ws_fault;   /* mockFault query parameter of every handshake, signed with the others */
    int ping_interval_ms;   /* -1: the controller default */
    int pong_timeout_ms;
    int attempt_timeout_ms; /* -1: read_timeout_ms like a REST attempt */
    /* 2.2.0 */
    unsigned long core_type_enum; /* KYTestConfig.coreType when --core-type is blank (KYTestEngine) */
    int skegn;                    /* resolve --core-type like skegn_start: blank is no coreType */
    int tts;                      /* TtsClient: POST /api/v1/tts/generate */
    const char *tts_text;
    ygst_fields tts_set;          /* the other members, name=value, integers parsed */
    const char *idempotency_key;  /* TtsOptions.idempotencyKey */
    int report;                   /* ReportClient: GET /api/v1/report/{recordId} */
    const char *report_id;
    long long wait_for_final_ms;  /* ReportOptions.waitForFinalMs */
} options;

static void emit_full(const char *token, const char *outcome, int attempts, int replayed, long elapsed,
                      const char *extra, const char *json) {
    printf("{\"tokenId\":\"%s\",\"outcome\":\"%s\",\"attempts\":%d,\"replayed\":%d,\"elapsedMs\":%ld,%s\"json\":%s}\n",
           token, outcome, attempts, replayed, elapsed, extra ? extra : "", json);
    fflush(stdout);
}

static void emit_error(const options *o, const char *token, int err_id, const char *message, int attempts,
                       int64_t started, const char *extra) {
    ygst_buf e;
    ygst_buf_init(&e);
    ygst_envelope_error(&e, token, err_id, message, o->app_key);
    emit_full(token, "error", attempts, 0, (long)(mono_ms() - started), extra, ygst_buf_cstr(&e));
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

static ygst_retry_policy policy_of(const options *o) {
    ygst_retry_policy policy;
    int i;
    ygst_retry_policy_default(&policy);
    if (o->max_retries >= 0) policy.max_retries = o->max_retries;
    policy.auto_retry = o->auto_retry;
    for (i = 0; i < o->n_err_ids; i++) {
        if (i == 0) policy.n_err_ids = 0;
        ygst_retry_policy_add_err_id(&policy, o->err_ids[i]);
    }
    return policy;
}

/* 2.1.1: an evaluation that reached the platform ends with the INFO line and the two DEBUG lines
 * of ygst_timing, as the "timing", "timingPhases" and "timingStages" members. Parsed and delivered
 * are now: the driver has no app callback. */
static void timing_members(ygst_timing *tm, const char *token, const char *record_id, ygst_span timing_obj,
                           ygst_buf *ex) {
    ygst_buf line;
    if (!tm || tm->mode == YGST_TM_NONE) return;
    if (!tm->parsed_ms) tm->parsed_ms = mono_ms();
    tm->delivered_ms = mono_ms();
    ygst_buf_init(&line);
    ygst_timing_summary(tm, token, record_id, &line);
    ygst_buf_append_str(ex, "\"timing\":");
    ygst_json_escape(ex, ygst_buf_cstr(&line));
    ygst_buf_reset(&line);
    ygst_timing_phases(tm, token, &line);
    ygst_buf_append_str(ex, ",\"timingPhases\":");
    ygst_json_escape(ex, ygst_buf_cstr(&line));
    ygst_buf_reset(&line);
    ygst_timing_stages(timing_obj, token, &line);
    ygst_buf_append_str(ex, ",\"timingStages\":");
    ygst_json_escape(ex, ygst_buf_cstr(&line));
    ygst_buf_append_char(ex, ',');
    ygst_buf_free(&line);
}

/* The final result envelope: the same code for the REST body and the realtime final frame. The
 * envelope takes result, recordId and audioUrl only: the platform timing object stays out. */
static void emit_result(const options *o, const char *token, const char *core_type, const ygst_fields *fields,
                        const ygst_classification *cls, int attempts, int replayed, int64_t started,
                        const char *extra, ygst_timing *tm) {
    char dt[32];
    char rid[256];
    ygst_buf env, params, ex;
    ygst_span timing_obj;
    ygst_buf_init(&env);
    ygst_buf_init(&params);
    ygst_buf_init(&ex);
    ygst_format_dt(now_ms(), 480, dt);
    if (o->get_param) {
        ygst_params_json(&params, o->app_key, o->user_id, now_ms() / 1000, "wav", 16000, 1, 2, core_type, token, fields);
    }
    snprintf(rid, sizeof rid, "%.*s", (int)cls->record_id_raw.n, cls->record_id_raw.p ? cls->record_id_raw.p : "");
    ygst_envelope_result(&env, token, cls->has_record_id ? rid : NULL, o->app_key, o->user_id,
                         o->ref_text ? o->ref_text : "", dt, cls->result, o->get_param ? ygst_buf_cstr(&params) : NULL,
                         cls->has_audio_url ? (const char *)cls->audio_url.data : NULL);
    if (extra) ygst_buf_append_str(&ex, extra);
    if (tm) {
        tm->ok = 1;
        tm->parsed_ms = mono_ms();
        ygst_timing_member(cls->root, tm, &timing_obj);
        /* 2.2.0: the recordId of the result, as YGSTUploadResult.recordId on iOS */
        timing_members(tm, token, cls->has_record_id && cls->record_id.data ? (const char *)cls->record_id.data : NULL,
                       timing_obj, &ex);
    }
    emit_full(token, "result", attempts, replayed, (long)(mono_ms() - started), ygst_buf_cstr(&ex), ygst_buf_cstr(&env));
    ygst_buf_free(&env);
    ygst_buf_free(&params);
    ygst_buf_free(&ex);
}

/* An error after the platform was reached: emit_error with the timing members. */
static void emit_timed_error(const options *o, const char *token, int err_id, const char *message, int attempts,
                             int64_t started, const char *extra, ygst_timing *tm) {
    ygst_buf ex;
    ygst_span none;
    none.p = NULL;
    none.n = 0;
    ygst_buf_init(&ex);
    if (extra) ygst_buf_append_str(&ex, extra);
    tm->ok = 0;
    tm->err_id = err_id;
    timing_members(tm, token, NULL, none, &ex);
    emit_error(o, token, err_id, message, attempts, started, ygst_buf_cstr(&ex));
    ygst_buf_free(&ex);
}

/* POST /{coreType} under the call controller, exactly as YGSTUploader does; prints the outcome.
 * tm: the timing of this evaluation (REST, or the WebSocket round that fell back). */
static void rest_evaluate(const options *o, const char *token, const char *core_type, const ygst_fields *fields,
                          const unsigned char *audio, size_t audio_len, const char *ext, const char *base,
                          const target *tgt, int64_t started, const char *extra, ygst_timing *tm) {
    char signature[45];
    char boundary[64];
    char content_type[128];
    char url[600];
    const char *path;
    const char *fault = o->fault;
    ygst_buf body;
    ygst_retry_policy policy = policy_of(o);
    ygst_call call;
    http_result hr;
    int last_replayed = 0;
    ygst_fields sent; /* 2.1.1: timing=1 goes into the signed request only, never into params */
    ygst_fields_init(&sent);
    ygst_fields_copy(fields, &sent);
    if (o->timing) ygst_fields_set(&sent, "timing", "1");
    ygst_sign(&sent, o->secret, signature);
    ygst_buf_init(&body);
    for (;;) {
        unsigned char rnd[16];
        char filename[32];
        random_bytes(rnd, sizeof rnd);
        ygst_multipart_boundary(rnd, boundary);
        snprintf(filename, sizeof filename, "audio.%s", ext);
        ygst_buf_reset(&body);
        if (ygst_multipart_build(&sent, boundary, "audio", filename, ygst_audio_content_type(ext), audio, audio_len,
                                 &body) != -2)
            break;
    }
    ygst_multipart_content_type(boundary, content_type, sizeof content_type);
    ygst_build_url(base, core_type, url, sizeof url);
    path = url + tgt->path_offset;
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
        if (o->fixed_nonce) snprintf(nonce, sizeof nonce, "%s", o->fixed_nonce);
        snprintf(head, sizeof head,
                 "POST %s HTTP/1.1\r\nHost: %s\r\nContent-Type: %s\r\nContent-Length: %zu\r\n"
                 "X-App-Key: %s\r\nX-Timestamp: %lld\r\nX-Nonce: %s\r\nX-Signature: %s\r\nIdempotency-Key: %s\r\n"
                 "User-Agent: %s\r\n%s%s%sConnection: close\r\n\r\n",
                 path, tgt->authority, content_type, body.len, o->app_key, (long long)(now_ms() / 1000), nonce,
                 signature, token, YGST_USER_AGENT, fault ? "X-Mock-Fault: " : "", fault ? fault : "",
                 fault ? "\r\n" : "");
        http_post(tgt, o->ca_file, o->connect_timeout_ms, head, &body, o->read_timeout_ms, &hr);
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
        /* the phases of this attempt, as NSURLSessionTaskMetrics gives them on iOS */
        tm->attempts = call.total_attempts;
        tm->rest_start_ms = hr.t_start;
        tm->rest_done_ms = hr.t_done;
        tm->upload_ms = hr.t_uploaded ? (long)(hr.t_uploaded - hr.t_start) : -1;
        tm->wait_ms = hr.t_uploaded && hr.t_headers ? (long)(hr.t_headers - hr.t_uploaded) : -1;
        tm->download_ms = hr.t_headers ? (long)(hr.t_done - hr.t_headers) : -1;
        tm->upload_bytes = hr.upload_bytes;
        tm->wire_bytes = hr.status ? (long long)hr.body.len : -1; /* the driver asks for no compression */
        tm->body_bytes = hr.status ? (long long)hr.body.len : -1;
        tm->server_total_ms = tm->server_engine_ms = tm->server_platform_ms = -1;
        tm->replayed = hr.replayed;
        if (hr.server_timing[0]) ygst_server_timing_parse(hr.server_timing, tm);
        if (act.kind == YGST_ACT_SUCCESS) {
            emit_result(o, token, core_type, fields, &cls, call.total_attempts, last_replayed, started, extra, tm);
            ygst_classification_free(&cls);
            break;
        }
        if (act.kind == YGST_ACT_FAIL) {
            ygst_buf msg;
            ygst_buf_init(&msg);
            ygst_error_message(&cls, act.err_id, call.attempt, &msg);
            emit_timed_error(o, token, act.err_id, ygst_buf_cstr(&msg), call.total_attempts, started, extra, tm);
            ygst_buf_free(&msg);
            ygst_classification_free(&cls);
            break;
        }
        ygst_classification_free(&cls);
        sleep_ms(act.delay_ms);
        fault = NULL; /* a header fault applies to the first attempt only */
    }
    ygst_buf_free(&hr.body);
    ygst_buf_free(&body);
    ygst_fields_free(&sent);
}

/* ------------------------------------------------------------------ realtime round */

/* One realtime_feedback round the way KYTestEngine runs it: capture feeds PCM to the controller,
 * the controller's actions go out over the WebSocket client, progress frames become eof 0
 * envelopes, a failure before end falls back to REST at stop, the final frame becomes the same
 * envelope as a REST body. */
typedef struct {
    const options *o;
    const char *token;
    const char *core_type;
    const char *base;
    const target *tgt;
    const ygst_fields *fields;
    const unsigned char *pcm;
    size_t pcm_len;
    ygst_rt rt;
    ws_conn ws;
    size_t fed;
    int stopped;
    int64_t next_feed_ms;
    int64_t started;
    int done;
    int progress_count;
    int held;
    int sends_failed;
    ygst_buf progress;    /* the eof 0 envelopes as a JSON array */
    ygst_buf param_frame; /* the first parameter frame */
    ygst_buf rest_reason; /* why the round went to REST */
    ygst_timing tm;       /* 2.1.1 phases, as YGSTRealtime.m records them */
    int64_t text_ms;      /* when the text frame being handled arrived */
    size_t text_len;
} rt_run;

static void rt_apply(rt_run *x, ygst_rt_actions a);

static void rt_extra(rt_run *x, const char *mode, ygst_buf *out) {
    ygst_buf_append_str(out, "\"mode\":\"");
    ygst_buf_append_str(out, mode);
    ygst_buf_append_str(out, "\",\"connections\":");
    ygst_buf_append_int(out, x->rt.connections);
    ygst_buf_append_str(out, ",\"held\":");
    ygst_buf_append_int(out, x->held);
    ygst_buf_append_str(out, ",\"restReason\":");
    ygst_json_escape(out, ygst_buf_cstr(&x->rest_reason));
    ygst_buf_append_str(out, ",\"paramFrame\":");
    ygst_buf_append_str(out, x->param_frame.len ? ygst_buf_cstr(&x->param_frame) : "null");
    ygst_buf_append_str(out, ",\"progress\":[");
    ygst_buf_append_str(out, ygst_buf_cstr(&x->progress));
    ygst_buf_append_str(out, "],");
}

static void rt_rest(rt_run *x) {
    unsigned char hdr[44];
    unsigned char *wav = (unsigned char *)malloc(x->pcm_len + 44);
    ygst_buf extra;
    ygst_buf_init(&extra);
    if (!x->rest_reason.len) ygst_buf_append_str(&x->rest_reason, ygst_buf_cstr(&x->rt.failure));
    rt_extra(x, "rest", &extra);
    ygst_wav_header(hdr, (uint32_t)x->fed, 16000, 1, 16);
    if (wav) {
        memcpy(wav, hdr, 44);
        memcpy(wav + 44, x->pcm, x->fed);
        fprintf(stderr, "realtime: evaluating over REST (%s)\n", ygst_buf_cstr(&x->rest_reason));
        x->tm.mode = YGST_TM_WS_REST;
        ygst_timing_set_fallback(&x->tm, ygst_buf_cstr(&x->rest_reason));
        rest_evaluate(x->o, x->token, x->core_type, x->fields, wav, x->fed + 44, "wav", x->base, x->tgt, x->started,
                      ygst_buf_cstr(&extra), &x->tm);
        free(wav);
    }
    ygst_buf_free(&extra);
    x->done = 1;
}

/* A send failed: the connection is gone. */
static void rt_send_failed(rt_run *x, http_result *r) {
    x->sends_failed++;
    ws_close(&x->ws);
    rt_apply(x, ygst_rt_on_failure(&x->rt, r->local_code ? r->local_code : YGST_LOCAL_NETWORK, 0, r->message,
                                   mono_ms()));
}

static void rt_connect(rt_run *x) {
    unsigned char rnd[8];
    char nonce[17];
    ygst_buf url;
    ygst_fields extra;
    http_result r;
    int rc;
    random_bytes(rnd, sizeof rnd);
    ygst_hex_lower(rnd, sizeof rnd, nonce);
    ygst_buf_init(&url);
    ygst_fields_init(&extra);
    if (x->o->ws_fault) ygst_fields_set(&extra, "mockFault", x->o->ws_fault);
    rc = ygst_ws_handshake_url(x->base, x->core_type, x->o->app_key, x->o->secret, now_ms() / 1000, nonce, x->token,
                               &extra, &url);
    ygst_fields_free(&extra);
    if (rc != 0) {
        ygst_buf_free(&url);
        rt_apply(x, ygst_rt_on_failure(&x->rt, 90010, 0, "invalid realtime URL", mono_ms()));
        return;
    }
    memset(&r, 0, sizeof r);
    ygst_buf_init(&r.body);
    fprintf(stderr, "realtime: connection %d\n", x->rt.connections);
    rc = ws_open(&x->ws, ygst_buf_cstr(&url), x->o->ca_file, x->o->connect_timeout_ms, &r);
    ygst_buf_free(&url);
    ygst_buf_free(&r.body);
    if (rc != 0) {
        fprintf(stderr, "realtime: connection failed: status=%d local=%d %s\n", r.status, r.local_code, r.message);
        rt_apply(x, ygst_rt_on_failure(&x->rt, r.status ? 0 : r.local_code, r.status, r.message, mono_ms()));
    }
}

static void rt_apply(rt_run *x, ygst_rt_actions a) {
    http_result r;
    memset(&r, 0, sizeof r);
    if (x->done) return;
    if (a.close) ws_close(&x->ws);
    if (a.connect) {
        if (!x->tm.ws_connect_ms) x->tm.ws_connect_ms = mono_ms();
        rt_connect(x);
    }
    if (x->done) return;
    if (a.send_params) {
        ygst_buf frame;
        int flags = (x->o->progress ? YGST_WSP_REALTIME_FEEDBACK : 0) | (x->o->timing ? YGST_WSP_TIMING : 0);
        if (!x->tm.ws_params_ms) {
            x->tm.ws_handshake_ms = x->text_ms ? x->text_ms : mono_ms(); /* "connected" */
            x->tm.ws_params_ms = mono_ms();
        }
        ygst_buf_init(&frame);
        ygst_ws_param_frame(x->fields, x->token, flags, &frame);
        if (!x->param_frame.len) ygst_buf_append(&x->param_frame, frame.data, frame.len);
        if (ws_send(&x->ws, 0x1, frame.data, frame.len, &r) != 0) {
            ygst_buf_free(&frame);
            rt_send_failed(x, &r);
            return;
        }
        ygst_buf_free(&frame);
    }
    if (a.audio_to > a.audio_from) {
        size_t from = a.audio_from;
        if (!x->tm.ws_first_audio_ms) x->tm.ws_first_audio_ms = mono_ms();
        while (from < a.audio_to) {
            size_t n = a.audio_to - from < x->rt.max_frame_bytes ? a.audio_to - from : x->rt.max_frame_bytes;
            if (ws_send(&x->ws, 0x2, x->pcm + from, n, &r) != 0) {
                rt_send_failed(x, &r);
                return;
            }
            from += n;
        }
    }
    if (a.send_end) {
        if (ws_send(&x->ws, 0x1, (const unsigned char *)"{\"cmd\":\"end\"}", 13, &r) != 0) {
            rt_send_failed(x, &r);
            return;
        }
        x->tm.ws_end_ms = mono_ms(); /* the writes are synchronous: written to the network */
    }
    if (a.send_ping && ws_send(&x->ws, 0x9, (const unsigned char *)"hb", 2, &r) != 0) {
        rt_send_failed(x, &r);
        return;
    }
    if (a.progress) {
        char dt[32];
        ygst_format_dt(now_ms(), 480, dt);
        if (x->progress.len) ygst_buf_append_char(&x->progress, ',');
        ygst_envelope_progress(&x->progress, x->token, x->o->app_key, x->o->user_id, x->o->ref_text ? x->o->ref_text : "",
                               dt, x->rt.frame.result, a.words_skip);
        x->progress_count++;
    }
    if (a.progress_held) x->held++;
    if (a.retry) {
        fprintf(stderr, "realtime: reconnect %d/%d in %ld ms: %s\n", x->rt.call.attempt, x->rt.policy.max_retries,
                a.delay_ms, ygst_buf_cstr(&x->rt.failure));
    }
    if (a.final || a.fail) {
        ygst_buf extra;
        ygst_buf_init(&extra);
        rt_extra(x, "ws", &extra);
        x->tm.attempts = x->rt.connections;
        if (a.final) {
            x->tm.ws_final_ms = x->text_ms;
            x->tm.body_bytes = (long long)x->text_len;
            x->tm.wire_bytes = (long long)x->text_len; /* the driver offers no compression */
            x->tm.replayed = x->rt.frame.replayed;
            emit_result(x->o, x->token, x->core_type, x->fields, &x->rt.cls, x->rt.call.total_attempts,
                        x->rt.frame.replayed, x->started, ygst_buf_cstr(&extra), &x->tm);
        } else {
            ygst_buf msg;
            ygst_buf_init(&msg);
            ygst_rt_error_message(&x->rt, a.err_id, &msg);
            emit_timed_error(x->o, x->token, a.err_id, ygst_buf_cstr(&msg), x->rt.call.total_attempts, x->started,
                             ygst_buf_cstr(&extra), &x->tm);
            ygst_buf_free(&msg);
        }
        ygst_buf_free(&extra);
        x->done = 1;
        return;
    }
    if (a.fallback) rt_rest(x);
}

static void rt_on_message(void *ctx, ws_event ev, const char *text, size_t n, int close_code) {
    rt_run *x = (rt_run *)ctx;
    if (ev == WS_EV_TEXT) {
        x->text_ms = mono_ms();
        x->text_len = n;
        rt_apply(x, ygst_rt_on_text(&x->rt, text, n, x->text_ms));
    } else if (ev == WS_EV_PONG) {
        rt_apply(x, ygst_rt_on_pong(&x->rt, mono_ms()));
    } else if (ev == WS_EV_CLOSED) {
        char why[96];
        snprintf(why, sizeof why, "network error: the platform closed the connection with code %d", close_code);
        rt_apply(x, ygst_rt_on_failure(&x->rt, YGST_LOCAL_NETWORK, 0, why, mono_ms()));
    }
}

static void run_realtime(const options *o, const char *token, const char *core_type, const ygst_fields *fields,
                         const unsigned char *pcm, size_t pcm_len, const char *base, const target *tgt,
                         int64_t started) {
    rt_run x;
    int64_t guard = started + 240000;
    memset(&x, 0, sizeof x);
    x.o = o;
    x.token = token;
    x.core_type = core_type;
    x.base = base;
    x.tgt = tgt;
    x.fields = fields;
    x.pcm = pcm;
    x.pcm_len = pcm_len;
    x.started = started;
    x.next_feed_ms = started;
    ws_init(&x.ws);
    ygst_buf_init(&x.progress);
    ygst_buf_init(&x.param_frame);
    ygst_buf_init(&x.rest_reason);
    ygst_rt_init(&x.rt);
    ygst_timing_init(&x.tm);
    x.tm.mode = YGST_TM_WS;
    x.tm.start_ms = started;
    x.tm.audio_bytes_per_sec = 32000;
    x.rt.policy = policy_of(o);
    x.rt.seed = (uint64_t)now_ms();
    x.rt.connect_timeout_ms = o->connect_timeout_ms;
    x.rt.attempt_timeout_ms = o->attempt_timeout_ms >= 0 ? o->attempt_timeout_ms : o->read_timeout_ms;
    if (o->ping_interval_ms >= 0) x.rt.ping_interval_ms = o->ping_interval_ms;
    if (o->pong_timeout_ms >= 0) x.rt.pong_timeout_ms = o->pong_timeout_ms;
    rt_apply(&x, ygst_rt_start(&x.rt, mono_ms()));
    while (!x.done) {
        int64_t now = mono_ms();
        int wait = 20;
        int can_feed = !o->feed_after_start || (x.rt.state != YGST_RT_IDLE && x.rt.state != YGST_RT_CONNECTING &&
                                                x.rt.state != YGST_RT_STARTING);
        http_result r;
        memset(&r, 0, sizeof r);
        if (!x.stopped && can_feed && now >= x.next_feed_ms) {
            do {
                size_t n = pcm_len - x.fed < (size_t)o->chunk_bytes ? pcm_len - x.fed : (size_t)o->chunk_bytes;
                x.fed += n;
                rt_apply(&x, ygst_rt_on_audio(&x.rt, x.fed, now));
                x.next_feed_ms = now + o->pace_ms;
            } while (!x.done && o->pace_ms == 0 && x.fed < pcm_len);
            if (!x.done && x.fed >= pcm_len) {
                /* stopEngine: the local check of the recording first, like finalizeCapture */
                int check = ygst_audio_check_pcm(x.fed, 16000, 1, 16);
                x.stopped = 1;
                x.tm.stop_ms = mono_ms();
                x.tm.audio_bytes = (long long)x.fed;
                x.tm.queued_at_stop = (long long)(x.fed - x.rt.sent); /* the sends before were synchronous */
                if (check) {
                    ygst_buf extra;
                    ygst_buf_init(&extra);
                    ygst_rt_cancel(&x.rt);
                    ws_close(&x.ws);
                    rt_extra(&x, "local", &extra);
                    emit_error(o, token, check, ygst_compat_message(check), 0, started, ygst_buf_cstr(&extra));
                    ygst_buf_free(&extra);
                    x.done = 1;
                    break;
                }
                rt_apply(&x, ygst_rt_on_stop(&x.rt, x.fed, mono_ms()));
            }
            if (x.done) break;
        }
        if (!x.stopped && can_feed && x.next_feed_ms - now < wait) wait = (int)(x.next_feed_ms > now ? x.next_feed_ms - now : 0);
        if (x.ws.open) {
            if (ws_pump(&x.ws, wait, rt_on_message, &x, &r) != 0 && !x.done && !x.ws.open && r.message[0]) {
                rt_apply(&x, ygst_rt_on_failure(&x.rt, r.local_code ? r.local_code : YGST_LOCAL_NETWORK, 0, r.message,
                                                mono_ms()));
            }
        } else {
            sleep_ms(wait);
        }
        if (x.done) break;
        rt_apply(&x, ygst_rt_on_tick(&x.rt, mono_ms()));
        if (mono_ms() > guard) {
            ygst_buf extra;
            ygst_buf_init(&extra);
            rt_extra(&x, "stuck", &extra);
            emit_error(o, token, 90007, "driver: the round did not end within 240 s", 0, started, ygst_buf_cstr(&extra));
            ygst_buf_free(&extra);
            break;
        }
    }
    ws_free(&x.ws);
    ygst_rt_free(&x.rt);
    ygst_buf_free(&x.progress);
    ygst_buf_free(&x.param_frame);
    ygst_buf_free(&x.rest_reason);
}

/* ------------------------------------------------------------------ TtsClient and ReportClient (2.2.0) */

typedef struct {
    int ok;
    int err_id;
    int http_status;
    int attempts;
    ygst_buf message; /* error text, as the NSError description on iOS */
    ygst_buf body;    /* the last answer */
    ygst_span data;   /* its "data" object on success, into body */
} api_result;

static void api_result_init(api_result *r) {
    memset(r, 0, sizeof *r);
    ygst_buf_init(&r->message);
    ygst_buf_init(&r->body);
}

static void api_result_free(api_result *r) {
    ygst_buf_free(&r->message);
    ygst_buf_free(&r->body);
}

/* One call of the v1 API under the call controller, as YGSTApiCall does on iOS: a fresh timestamp and
 * nonce on every attempt, the same signature and Idempotency-Key (TTS only), ygst_classify_api. */
static void api_call(const options *o, const target *tgt, const char *method, const char *url, const ygst_buf *body,
                     const char *key, const char *signature, api_result *out) {
    ygst_retry_policy policy = policy_of(o);
    ygst_call call;
    http_result hr;
    ygst_buf empty;
    const char *fault = o->fault;
    const char *path = url + tgt->path_offset;
    ygst_buf_init(&empty);
    ygst_call_init(&call, &policy, (uint64_t)now_ms(), mono_ms());
    ygst_buf_init(&hr.body);
    for (;;) {
        unsigned char nrnd[8];
        char nonce[17];
        ygst_buf head;
        ygst_outcome oc;
        ygst_classification cls;
        ygst_action act;
        random_bytes(nrnd, sizeof nrnd);
        ygst_hex_lower(nrnd, sizeof nrnd, nonce);
        if (o->fixed_nonce) snprintf(nonce, sizeof nonce, "%s", o->fixed_nonce);
        ygst_buf_init(&head);
        ygst_buf_append_str(&head, method);
        ygst_buf_append_char(&head, ' ');
        ygst_buf_append_str(&head, path);
        ygst_buf_append_str(&head, " HTTP/1.1\r\nHost: ");
        ygst_buf_append_str(&head, tgt->authority);
        ygst_buf_append_str(&head, "\r\n");
        if (body) {
            ygst_buf_append_str(&head, "Content-Type: application/json; charset=utf-8\r\nContent-Length: ");
            ygst_buf_append_int(&head, (long long)body->len);
            ygst_buf_append_str(&head, "\r\n");
        }
        ygst_buf_append_str(&head, "Accept: application/json\r\nX-App-Key: ");
        ygst_buf_append_str(&head, o->app_key);
        ygst_buf_append_str(&head, "\r\nX-Timestamp: ");
        ygst_buf_append_int(&head, (long long)(now_ms() / 1000));
        ygst_buf_append_str(&head, "\r\nX-Nonce: ");
        ygst_buf_append_str(&head, nonce);
        ygst_buf_append_str(&head, "\r\nX-Signature: ");
        ygst_buf_append_str(&head, signature);
        ygst_buf_append_str(&head, "\r\n");
        if (key) {
            ygst_buf_append_str(&head, "Idempotency-Key: ");
            ygst_buf_append_str(&head, key);
            ygst_buf_append_str(&head, "\r\n");
        }
        ygst_buf_append_str(&head, "User-Agent: " YGST_USER_AGENT "\r\n");
        if (fault) {
            ygst_buf_append_str(&head, "X-Mock-Fault: ");
            ygst_buf_append_str(&head, fault);
            ygst_buf_append_str(&head, "\r\n");
        }
        ygst_buf_append_str(&head, "Connection: close\r\n\r\n");
        http_post(tgt, o->ca_file, o->connect_timeout_ms, ygst_buf_cstr(&head), body ? body : &empty,
                  o->read_timeout_ms, &hr);
        ygst_buf_free(&head);
        memset(&oc, 0, sizeof oc);
        oc.http_status = hr.status;
        oc.local_code = hr.local_code;
        oc.body = (const char *)hr.body.data;
        oc.body_len = hr.body.len;
        oc.retry_after_ms = hr.retry_after_ms;
        oc.replayed = hr.replayed;
        oc.local_message = hr.message[0] ? hr.message : NULL;
        ygst_classification_init(&cls);
        ygst_classify_api(&oc, &cls);
        act = ygst_call_next(&call, &cls, hr.retry_after_ms, mono_ms());
        fprintf(stderr, "%s attempt %d: status=%d local=%d biz=%d retryable=%d -> action=%d delay=%ld err=%d %s\n",
                method, call.total_attempts, hr.status, hr.local_code, cls.biz_code, cls.retryable, act.kind,
                act.delay_ms, act.err_id, ygst_buf_cstr(&cls.message));
        if (act.kind != YGST_ACT_RETRY) {
            out->attempts = call.total_attempts;
            out->http_status = hr.status;
            if (act.kind == YGST_ACT_SUCCESS) {
                size_t off = (size_t)(cls.result.p - (const char *)hr.body.data);
                out->ok = 1;
                out->body = hr.body; /* the data span points into it */
                ygst_buf_init(&hr.body);
                out->data.p = (const char *)out->body.data + off;
                out->data.n = cls.result.n;
            } else {
                out->err_id = act.err_id;
                ygst_error_message(&cls, act.err_id, call.attempt, &out->message);
                out->body = hr.body;
                ygst_buf_init(&hr.body);
            }
            ygst_classification_free(&cls);
            break;
        }
        ygst_classification_free(&cls);
        sleep_ms(act.delay_ms);
        fault = NULL; /* a header fault applies to the first attempt only */
    }
    ygst_buf_free(&hr.body);
}

/* "name":"text" with a leading comma */
static void put_str_member(ygst_buf *b, const char *name, const char *text) {
    ygst_buf_append_str(b, ",\"");
    ygst_buf_append_str(b, name);
    ygst_buf_append_str(b, "\":");
    ygst_json_escape(b, text ? text : "");
}

/* "name":<raw JSON or null> with a leading comma */
static void put_raw_member(ygst_buf *b, const char *name, ygst_span v) {
    ygst_buf_append_str(b, ",\"");
    ygst_buf_append_str(b, name);
    ygst_buf_append_str(b, "\":");
    if (v.p && v.n) {
        ygst_buf_append(b, v.p, v.n);
    } else {
        ygst_buf_append_str(b, "null");
    }
}

static void emit_api(const char *op, const api_result *r, int local_code, const char *local_message, const char *extra,
                     const char *log_line, int64_t started) {
    ygst_buf b;
    ygst_buf_init(&b);
    ygst_buf_append_str(&b, "{\"op\":\"");
    ygst_buf_append_str(&b, op);
    ygst_buf_append_str(&b, "\",\"outcome\":\"");
    ygst_buf_append_str(&b, r && r->ok ? "result" : "error");
    ygst_buf_append_str(&b, "\",\"code\":");
    ygst_buf_append_int(&b, local_code ? local_code : (r ? r->err_id : 0));
    put_str_member(&b, "message", local_code ? local_message : (r && r->message.data ? (const char *)r->message.data : ""));
    ygst_buf_append_str(&b, ",\"httpStatus\":");
    ygst_buf_append_int(&b, r ? r->http_status : 0);
    ygst_buf_append_str(&b, ",\"attempts\":");
    ygst_buf_append_int(&b, r ? r->attempts : 0);
    if (extra) ygst_buf_append_str(&b, extra);
    put_str_member(&b, "log", log_line);
    ygst_buf_append_str(&b, ",\"elapsedMs\":");
    ygst_buf_append_int(&b, (long long)(mono_ms() - started));
    ygst_buf_append_str(&b, "}");
    printf("%s\n", ygst_buf_cstr(&b));
    fflush(stdout);
    ygst_buf_free(&b);
}

static int tts_member(ygst_tts_request *req, const char *name, const char *value) {
    long long *num = NULL;
    int *has = NULL;
    if (strcmp(name, "language") == 0) req->language = value;
    else if (strcmp(name, "voice") == 0) req->voice = value;
    else if (strcmp(name, "format") == 0) req->format = value;
    else if (strcmp(name, "emotion") == 0) req->emotion = value;
    else if (strcmp(name, "style") == 0) req->style = value;
    else if (strcmp(name, "pinyin") == 0) req->pinyin = value;
    else if (strcmp(name, "genre") == 0) req->genre = value;
    else if (strcmp(name, "speed") == 0) num = &req->speed, has = &req->has_speed;
    else if (strcmp(name, "pitch") == 0) num = &req->pitch, has = &req->has_pitch;
    else if (strcmp(name, "volume") == 0) num = &req->volume, has = &req->has_volume;
    else if (strcmp(name, "sampleRate") == 0) num = &req->sample_rate, has = &req->has_sample_rate;
    else return -1;
    if (num) {
        *num = atoll(value);
        *has = 1;
    }
    return 0;
}

/* TtsClient.tts: the body and its signature, then one call with an Idempotency-Key for all attempts. */
static int run_tts(const options *o, const char *base, const target *tgt, int64_t started) {
    ygst_tts_request req;
    ygst_buf json, url, ex, line, abs;
    ygst_fields sign;
    char signature[45];
    char key[256];
    api_result res;
    size_t i;
    int rc;
    ygst_tts_request_init(&req);
    req.text = o->tts_text;
    for (i = 0; i < o->tts_set.count; i++) {
        if (tts_member(&req, o->tts_set.items[i].name, o->tts_set.items[i].value) != 0) {
            fprintf(stderr, "--tts-set: unknown member %s\n", o->tts_set.items[i].name);
            return 2;
        }
    }
    ygst_buf_init(&json);
    ygst_buf_init(&url);
    ygst_buf_init(&ex);
    ygst_buf_init(&line);
    ygst_buf_init(&abs);
    ygst_fields_init(&sign);
    api_result_init(&res);
    if (o->idempotency_key && o->idempotency_key[0]) { /* an empty key is no key, as in YuguClients.m */
        snprintf(key, sizeof key, "%s", o->idempotency_key);
    } else {
        unsigned char rnd[16];
        random_bytes(rnd, sizeof rnd);
        ygst_hex_lower(rnd, sizeof rnd, key);
    }
    rc = ygst_tts_body(&req, &json, &sign);
    if (rc != 0 || !ygst_idempotency_key_ok(key)) {
        const char *why = rc != 0 ? "TtsRequest.text 为空" : "idempotencyKey 须为 1 到 200 个可见 ASCII 字符";
        ygst_api_log_line("tts", NULL, NULL, YGST_LOCAL_INVALID_ARGUMENT, -1, 0, mono_ms() - started, &line);
        emit_api("tts", NULL, YGST_LOCAL_INVALID_ARGUMENT, why, NULL, ygst_buf_cstr(&line), started);
    } else {
        ygst_sign(&sign, o->secret, signature);
        ygst_api_url(base, YGST_TTS_PATH, &url);
        api_call(o, tgt, "POST", ygst_buf_cstr(&url), &json, key, signature, &res);
        put_str_member(&ex, "idempotencyKey", key);
        put_str_member(&ex, "signature", signature);
        ygst_buf_append_str(&ex, ",\"request\":");
        ygst_buf_append(&ex, json.data, json.len);
        if (res.ok) {
            ygst_tts_answer a;
            ygst_span raw;
            char num[64];
            ygst_tts_answer_init(&a);
            ygst_tts_parse(res.data, &a);
            ygst_tts_absolute_url(base, ygst_buf_cstr(&a.audio_url), &abs);
            put_str_member(&ex, "audioUrl", ygst_buf_cstr(&a.audio_url));
            put_str_member(&ex, "absoluteAudioUrl", ygst_buf_cstr(&abs));
            snprintf(num, sizeof num, ",\"hasDuration\":%d,\"duration\":%.6g", a.has_duration, a.duration);
            ygst_buf_append_str(&ex, num);
            put_str_member(&ex, "format", ygst_buf_cstr(&a.format));
            raw.p = (const char *)res.body.data;
            raw.n = res.body.len;
            put_raw_member(&ex, "raw", raw);
            ygst_tts_answer_free(&a);
        }
        ygst_api_log_line("tts", NULL, "ok", res.ok ? 0 : res.err_id, -1, res.attempts, mono_ms() - started, &line);
        emit_api("tts", &res, 0, NULL, ygst_buf_cstr(&ex), ygst_buf_cstr(&line), started);
    }
    api_result_free(&res);
    ygst_buf_free(&json);
    ygst_buf_free(&url);
    ygst_buf_free(&ex);
    ygst_buf_free(&line);
    ygst_buf_free(&abs);
    ygst_fields_free(&sign);
    return 0;
}

/* ReportClient.getReport: one lookup, and with waitForFinalMs one per second while the report is
 * pending or (2.3.0) partial. ygst_report_after_lookup decides, as YuguClients.m: the first lookup's
 * error ends the call with that error, a later failed lookup ends it with the latest answer and one
 * WARN line ("warn"). */
static int run_report(const options *o, const char *base, const target *tgt, int64_t started) {
    ygst_buf path, url, ex, line, id, starts, warn;
    ygst_fields none;
    char signature[45];
    api_result res;  /* the last lookup */
    api_result last; /* the latest answer */
    ygst_report rep;
    ygst_rp_action act;
    int lookups = 0;
    int attempts = 0;
    int answered = 0;
    int rc;
    ygst_buf_init(&path);
    ygst_buf_init(&url);
    ygst_buf_init(&ex);
    ygst_buf_init(&line);
    ygst_buf_init(&id);
    ygst_buf_init(&starts);
    ygst_buf_init(&warn);
    ygst_fields_init(&none);
    ygst_report_init(&rep);
    api_result_init(&res);
    api_result_init(&last);
    memset(&act, 0, sizeof act);
    ygst_buf_append_trimmed(&id, o->report_id);
    rc = ygst_report_path(o->report_id, &path);
    if (rc != 0) {
        ygst_api_log_line("getReport", ygst_buf_cstr(&id), NULL, YGST_LOCAL_INVALID_ARGUMENT, 0, 0, mono_ms() - started,
                          &line);
        emit_api("getReport", NULL, YGST_LOCAL_INVALID_ARGUMENT, "recordId 为空", NULL, ygst_buf_cstr(&line), started);
    } else {
        ygst_sign(&none, o->secret, signature); /* the signed set of the report, CONTRACT 2.3 */
        ygst_api_url(base, ygst_buf_cstr(&path), &url);
        for (;;) {
            if (starts.len) ygst_buf_append_char(&starts, ',');
            ygst_buf_append_int(&starts, (long long)(mono_ms() - started));
            api_result_free(&res);
            api_result_init(&res);
            api_call(o, tgt, "GET", ygst_buf_cstr(&url), NULL, NULL, signature, &res);
            lookups++;
            attempts += res.attempts;
            if (res.ok) {
                api_result_free(&last);
                last = res; /* the answer moves, rep points into it */
                api_result_init(&res);
                ygst_report_parse(last.data, &rep);
                /* 2.3.0: pending and partial are looked up again, as YuguClients.m */
                act = ygst_report_after_lookup(o->wait_for_final_ms, started, mono_ms(), 1, ygst_report_is_waiting(&rep),
                                               answered);
                answered = 1;
            } else {
                act = ygst_report_after_lookup(o->wait_for_final_ms, started, mono_ms(), 0, 0, answered);
            }
            if (act.kind != YGST_RP_AGAIN) break;
            fprintf(stderr, "report %s, next lookup in %ld ms\n", ygst_buf_cstr(&rep.report_status), act.delay_ms);
            sleep_ms(act.delay_ms);
        }
        if (act.degraded) {
            ygst_report_lookup_failed_line(ygst_buf_cstr(&id), res.err_id, lookups, &warn);
            ygst_buf_append_str(&ex, ",\"failedCode\":");
            ygst_buf_append_int(&ex, res.err_id);
            put_str_member(&ex, "warn", ygst_buf_cstr(&warn));
        }
        if (act.kind == YGST_RP_RETURN) {
            /* the latest answer is the outcome: this one, or an earlier one after a failed lookup */
            api_result_free(&res);
            res = last;
            api_result_init(&last);
        }
        res.attempts = attempts;
        ygst_buf_append_str(&ex, ",\"lookups\":");
        ygst_buf_append_int(&ex, lookups);
        ygst_buf_append_str(&ex, ",\"lookupMs\":[");
        ygst_buf_append(&ex, starts.data, starts.len);
        ygst_buf_append_str(&ex, "]");
        put_str_member(&ex, "path", ygst_buf_cstr(&url) + tgt->path_offset);
        put_str_member(&ex, "signature", signature);
        if (res.ok) {
            ygst_buf s;
            size_t pos = 0;
            int n = 0;
            char num[64];
            put_str_member(&ex, "recordId", rep.record_id.len ? ygst_buf_cstr(&rep.record_id) : ygst_buf_cstr(&id));
            put_str_member(&ex, "reportStatus", ygst_buf_cstr(&rep.report_status));
            snprintf(num, sizeof num, ",\"isFinal\":%d,\"isPending\":%d,\"isPartial\":%d,\"hasSummary\":%d",
                     ygst_report_is_final(&rep), ygst_report_is_pending(&rep), ygst_report_is_partial(&rep), rep.has_summary);
            ygst_buf_append_str(&ex, num);
            put_str_member(&ex, "summary", ygst_buf_cstr(&rep.summary));
            ygst_buf_append_str(&ex, ",\"suggestions\":[");
            ygst_buf_init(&s);
            while (ygst_report_next_suggestion(rep.suggestions, &pos, &s) == 1) {
                if (n++) ygst_buf_append_char(&ex, ',');
                ygst_json_escape(&ex, ygst_buf_cstr(&s));
            }
            ygst_buf_free(&s);
            ygst_buf_append_str(&ex, "]");
            if (rep.has_overall) {
                snprintf(num, sizeof num, ",\"overall\":%.17g", rep.overall);
                ygst_buf_append_str(&ex, num);
            } else {
                ygst_buf_append_str(&ex, ",\"overall\":null");
            }
            put_raw_member(&ex, "report", rep.report);
            put_raw_member(&ex, "score", rep.score);
            put_raw_member(&ex, "timing", rep.timing);
            put_raw_member(&ex, "data", res.data);
        }
        ygst_api_log_line("getReport", ygst_buf_cstr(&id), ygst_buf_cstr(&rep.report_status), res.ok ? 0 : res.err_id,
                          lookups, attempts, mono_ms() - started, &line);
        emit_api("getReport", &res, 0, NULL, ygst_buf_cstr(&ex), ygst_buf_cstr(&line), started);
    }
    api_result_free(&res);
    api_result_free(&last);
    ygst_report_free(&rep);
    ygst_buf_free(&path);
    ygst_buf_free(&url);
    ygst_buf_free(&ex);
    ygst_buf_free(&line);
    ygst_buf_free(&id);
    ygst_buf_free(&starts);
    ygst_buf_free(&warn);
    ygst_fields_free(&none);
    return 0;
}

/* ------------------------------------------------------------------ main */

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
    char base[512];
    target tgt;
    int64_t started = mono_ms();
    int i;
    int err;

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
    o.chunk_bytes = 3200;
    o.ping_interval_ms = -1;
    o.pong_timeout_ms = -1;
    o.attempt_timeout_ms = -1;
    ygst_fields_init(&o.custom);
    ygst_fields_init(&o.tts_set);
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
        else if (OPT("--chunk-bytes")) o.chunk_bytes = atoi(v);
        else if (OPT("--pace-ms")) o.pace_ms = atoi(v);
        else if (OPT("--ws-fault")) o.ws_fault = v;
        else if (OPT("--ping-interval-ms")) o.ping_interval_ms = atoi(v);
        else if (OPT("--pong-timeout-ms")) o.pong_timeout_ms = atoi(v);
        else if (OPT("--attempt-timeout-ms")) o.attempt_timeout_ms = atoi(v);
        else if (OPT("--custom")) {
            const char *eq = strchr(v, '=');
            if (eq) {
                char k[128];
                size_t kl = (size_t)(eq - v) < sizeof k - 1 ? (size_t)(eq - v) : sizeof k - 1;
                memcpy(k, v, kl);
                k[kl] = 0;
                ygst_fields_set(&o.custom, k, eq + 1);
            }
        } else if (OPT("--core-type-enum")) o.core_type_enum = strtoul(v, NULL, 10);
        else if (OPT("--tts-text")) o.tts_text = v;
        else if (OPT("--tts-set")) {
            const char *eq = strchr(v, '=');
            if (eq) {
                char k[64];
                size_t kl = (size_t)(eq - v) < sizeof k - 1 ? (size_t)(eq - v) : sizeof k - 1;
                memcpy(k, v, kl);
                k[kl] = 0;
                ygst_fields_set(&o.tts_set, k, eq + 1);
            }
        } else if (OPT("--idempotency-key")) o.idempotency_key = v;
        else if (OPT("--report")) {
            o.report = 1;
            o.report_id = v;
        } else if (OPT("--wait-for-final-ms")) o.wait_for_final_ms = atoll(v);
        else if (strcmp(a, "--tts") == 0) o.tts = 1;
        else if (strcmp(a, "--skegn") == 0) o.skegn = 1;
        else if (strcmp(a, "--auto-retry") == 0) o.auto_retry = 1;
        else if (strcmp(a, "--get-param") == 0) o.get_param = 1;
        else if (strcmp(a, "--attach-audio-url") == 0) o.attach_audio_url = 1;
        else if (strcmp(a, "--realtime") == 0) o.realtime = o.progress = 1;
        else if (strcmp(a, "--stream") == 0) o.realtime = 1;
        else if (strcmp(a, "--timing") == 0) o.timing = 1;
        else if (strcmp(a, "--feed-after-start") == 0) o.feed_after_start = 1;
        else {
            fprintf(stderr, "unknown or incomplete option %s\n", a);
            return 2;
        }
#undef OPT
    }
    if (o.chunk_bytes <= 0) o.chunk_bytes = 3200;
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

    /* 2.2.0: TtsClient and ReportClient, no evaluation */
    if (o.tts || o.report) {
        int rc = o.tts ? run_tts(&o, base, &tgt, started) : run_report(&o, base, &tgt, started);
        ygst_fields_free(&o.custom);
        ygst_fields_free(&o.tts_set);
        return rc;
    }

    /* tokenId, also the Idempotency-Key of every attempt */
    if (o.token) {
        snprintf(token, sizeof token, "%s", o.token);
    } else {
        unsigned char rnd[16];
        random_bytes(rnd, sizeof rnd);
        ygst_token_id(rnd, token);
    }

    /* 1. coreType and refText: answered locally, never reach the network. 2.2.0: the 60003 text names
     * the coreType, as KYTestEngine (coreTypeNS, else the KYTestType) or skegn_start (the text) asked */
    cts = o.skegn ? ygst_coretype_resolve_text(o.core_type, core_type, sizeof core_type)
                  : ygst_coretype_resolve(o.core_type, o.core_type_enum, core_type, sizeof core_type);
    err = ygst_validate_request(cts, core_type, o.ref_text, o.ref_pinyin);
    if (err) {
        ygst_buf requested, msg;
        ygst_buf_init(&requested);
        ygst_buf_init(&msg);
        if (o.skegn) {
            ygst_buf_append_str(&requested, o.core_type);
        } else {
            ygst_coretype_requested(o.core_type, o.core_type_enum, &requested);
        }
        if (err == YGST_ERRID_CORETYPE_UNSUPPORTED) {
            ygst_coretype_unsupported_message(ygst_buf_cstr(&requested), &msg);
        } else {
            ygst_buf_append_str(&msg, ygst_compat_message(err));
        }
        emit_error(&o, token, err, ygst_buf_cstr(&msg), 0, started, NULL);
        ygst_buf_free(&requested);
        ygst_buf_free(&msg);
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
            emit_error(&o, token, YGST_ERRID_AUDIO_FILE_MISSING, msg, 0, started, NULL);
            return 0;
        }
        if (dot) ext = ygst_audio_ext(dot + 1);
    } else {
        audio = (unsigned char *)calloc(1, 1);
        audio_len = 0;
    }
    if (!o.realtime) {
        err = ygst_audio_check_upload(audio, audio_len);
        if (err) {
            emit_error(&o, token, err, ygst_compat_message(err), 0, started, NULL);
            free(audio);
            return 0;
        }
    }

    /* 4. form fields */
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

    if (o.realtime) {
        /* 5a. realtime_feedback: the recording is 16 kHz 16 bit mono PCM, streamed as it is captured */
        ygst_wav_info info;
        if (ygst_wav_parse(audio, audio_len, &info) != 0 || info.format != 1 || info.sample_rate != 16000 ||
            info.channels != 1 || info.bits != 16) {
            fprintf(stderr, "--realtime needs a 16 kHz 16 bit mono PCM WAV file\n");
            free(audio);
            return 2;
        }
        run_realtime(&o, token, core_type, &fields, audio + info.data_offset, info.data_len, base, &tgt, started);
    } else {
        /* 5b. one request under the controller */
        ygst_timing tm;
        ygst_wav_info info;
        ygst_timing_init(&tm);
        tm.mode = YGST_TM_REST;
        tm.start_ms = started;
        tm.stop_ms = started; /* an audioPath evaluation: the start call stands for stop */
        tm.audio_bytes = (long long)audio_len;
        if (ygst_wav_parse(audio, audio_len, &info) == 0 && info.format == 1 && info.bits > 0) {
            tm.audio_bytes = (long long)info.data_len;
            tm.audio_bytes_per_sec = info.sample_rate * info.channels * info.bits / 8;
        }
        rest_evaluate(&o, token, core_type, &fields, audio, audio_len, ext, base, &tgt, started, NULL, &tm);
    }
    ygst_fields_free(&fields);
    ygst_fields_free(&o.custom);
    ygst_fields_free(&o.tts_set);
    free(audio);
    return 0;
}
