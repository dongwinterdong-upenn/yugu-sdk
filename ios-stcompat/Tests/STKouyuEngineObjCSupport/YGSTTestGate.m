// Copyright 2026 优谷雅言 open.shengzhiai.com
// SPDX-License-Identifier: Apache-2.0
//
// See YGSTTestGate.h. Each relayed connection has two pumps on their own serial queues: engine to mock
// (reads the request head first to learn the token of the connection) and mock to engine (reads the
// response head, then for a WebSocket every frame; an answer waits on the gate's condition until the
// check lets its token through, the engine closes the connection or the gate closes).

#import "YGSTTestGate.h"

#include <arpa/inet.h>
#include <netinet/in.h>
#include <poll.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

/// One connection through the gate. Every property is guarded by the gate's condition.
@interface YGSTGateLink : NSObject
@property (nonatomic) int engineFd;
@property (nonatomic) int mockFd;
@property (nonatomic) NSUInteger number;
@property (nonatomic, copy) NSString *tokenId; // nil until the request head was read
@property (nonatomic) BOOL engineGone;         // the engine closed its side
@property (nonatomic) int pumpsDone;
@property (nonatomic) BOOL fdsClosed;
@end

@implementation YGSTGateLink
@end

static void YGSTGateNoSigPipe(int fd) {
    int one = 1;
    setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, &one, (socklen_t)sizeof one);
}

static BOOL YGSTGateSendAll(int fd, const unsigned char *p, size_t n) {
    while (n > 0) {
        ssize_t w = send(fd, p, n, 0);
        if (w <= 0) return NO;
        p += w;
        n -= (size_t)w;
    }
    return YES;
}

/// Reads until buf holds at least want bytes. NO at the end of the stream.
static BOOL YGSTGateFill(int fd, NSMutableData *buf, NSUInteger want) {
    unsigned char tmp[16384];
    while (buf.length < want) {
        ssize_t r = recv(fd, tmp, sizeof tmp, 0);
        if (r <= 0) return NO;
        [buf appendBytes:tmp length:(NSUInteger)r];
    }
    return YES;
}

/// The offset just after the "\r\n\r\n" that ends an HTTP head, 0 while it is incomplete.
static NSUInteger YGSTGateHeadEnd(NSData *buf) {
    const unsigned char *p = buf.bytes;
    NSUInteger i;
    for (i = 0; i + 4 <= buf.length; i++) {
        if (p[i] == '\r' && p[i + 1] == '\n' && p[i + 2] == '\r' && p[i + 3] == '\n') return i + 4;
    }
    return 0;
}

static BOOL YGSTGateStartsWith(const unsigned char *p, NSUInteger n, const char *prefix) {
    size_t k = strlen(prefix);
    return n >= k && memcmp(p, prefix, k) == 0;
}

static BOOL YGSTGateLowerEqual(const char *a, const char *b, size_t n) {
    size_t i;
    for (i = 0; i < n; i++) {
        char x = a[i], y = b[i];
        if (x >= 'A' && x <= 'Z') x = (char)(x - 'A' + 'a');
        if (y >= 'A' && y <= 'Z') y = (char)(y - 'A' + 'a');
        if (x != y) return NO;
    }
    return YES;
}

/// The token of a request head: idempotencyKey=<t> in the target of a WebSocket handshake, else the
/// Idempotency-Key header of a request. nil without one.
static NSString *YGSTGateTokenOfHead(NSData *head, NSUInteger end) {
    char text[65537];
    const char *line, *q;
    size_t n = end < sizeof text - 1 ? end : sizeof text - 1;
    memcpy(text, head.bytes, n);
    text[n] = 0;
    line = strstr(text, "\r\n");
    q = strstr(text, "idempotencyKey=");
    if (q && line && q < line) {
        q += strlen("idempotencyKey=");
        return [[NSString alloc] initWithBytes:q length:strcspn(q, "& \r\n") encoding:NSUTF8StringEncoding];
    }
    while (line && line[0] && line[2]) {
        const char *next = strstr(line + 2, "\r\n");
        const char *h = line + 2;
        if (next && (size_t)(next - h) > 16 && YGSTGateLowerEqual(h, "idempotency-key:", 16)) {
            const char *v = h + 16;
            while (*v == ' ' || *v == '\t') v++;
            return [[NSString alloc] initWithBytes:v length:(NSUInteger)(next - v) encoding:NSUTF8StringEncoding];
        }
        line = next;
    }
    return nil;
}

/// A final frame of the compat WebSocket: a text frame that is neither an event nor eof 0 progress.
static BOOL YGSTGateIsFinal(const unsigned char *payload, NSUInteger n) {
    return n > 0 && !YGSTGateStartsWith(payload, n, "{\"event\"") && !YGSTGateStartsWith(payload, n, "{\"eof\":0");
}

@implementation YGSTTestGate {
    NSCondition *_cond; // guards everything below and every link
    int _listenFd;
    uint16_t _mockPort;
    BOOL _closed;
    NSUInteger _count;
    NSMutableArray<YGSTGateLink *> *_links;
    NSMutableDictionary<NSString *, NSNumber *> *_allowed;
    NSMutableDictionary<NSString *, NSString *> *_state; // waiting, relayed, dropped
    NSMutableString *_report;
    NSDate *_opened;
    NSString *_baseURL;
}

+ (instancetype)gateToMock:(NSString *)mockBaseURL error:(NSString **)error {
    YGSTTestGate *gate = [[YGSTTestGate alloc] init];
    NSURL *url = [NSURL URLWithString:mockBaseURL];
    struct sockaddr_in a;
    socklen_t len = (socklen_t)sizeof a;
    int fd, one = 1;
    if (!url.port) {
        if (error) *error = [NSString stringWithFormat:@"gate: no port in %@", mockBaseURL];
        return nil;
    }
    fd = socket(AF_INET, SOCK_STREAM, 0);
    if (fd < 0) {
        if (error) *error = @"gate: socket";
        return nil;
    }
    setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &one, (socklen_t)sizeof one);
    memset(&a, 0, sizeof a);
    a.sin_len = (uint8_t)sizeof a;
    a.sin_family = AF_INET;
    a.sin_port = htons(0);
    a.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    if (bind(fd, (const struct sockaddr *)&a, (socklen_t)sizeof a) != 0 || listen(fd, 16) != 0 ||
        getsockname(fd, (struct sockaddr *)&a, &len) != 0) {
        close(fd);
        if (error) *error = @"gate: bind, listen or getsockname";
        return nil;
    }
    gate->_cond = [[NSCondition alloc] init];
    gate->_listenFd = fd;
    gate->_mockPort = (uint16_t)url.port.unsignedIntegerValue;
    gate->_links = [NSMutableArray array];
    gate->_allowed = [NSMutableDictionary dictionary];
    gate->_state = [NSMutableDictionary dictionary];
    gate->_report = [NSMutableString string];
    gate->_opened = [NSDate date];
    gate->_baseURL = [NSString stringWithFormat:@"http://127.0.0.1:%u", (unsigned)ntohs(a.sin_port)];
    dispatch_async(dispatch_queue_create("com.shengzhiai.yugu.test.gate.accept", DISPATCH_QUEUE_SERIAL), ^{
        [gate acceptLoop];
    });
    return gate;
}

- (NSString *)baseURL {
    return _baseURL;
}

/// Call with the condition locked.
- (void)noteLocked:(NSString *)event {
    [_report appendString:[NSString stringWithFormat:@"+%ldms %@\n", (long)(-[_opened timeIntervalSinceNow] * 1000.0), event]];
}

- (void)letThrough:(NSString *)tokenId {
    [_cond lock];
    _allowed[tokenId] = @YES;
    [self noteLocked:[NSString stringWithFormat:@"let through %@", tokenId]];
    [_cond broadcast];
    [_cond unlock];
}

- (BOOL)isWaiting:(NSString *)tokenId {
    BOOL waiting;
    [_cond lock];
    waiting = [_state[tokenId] isEqualToString:@"waiting"];
    [_cond unlock];
    return waiting;
}

- (BOOL)isRelayed:(NSString *)tokenId {
    BOOL relayed;
    [_cond lock];
    relayed = [_state[tokenId] isEqualToString:@"relayed"];
    [_cond unlock];
    return relayed;
}

- (NSString *)report {
    NSString *text;
    [_cond lock];
    text = [_report copy];
    [_cond unlock];
    return text;
}

- (void)close {
    [_cond lock];
    if (!_closed) {
        _closed = YES;
        [self noteLocked:@"closed"];
        for (YGSTGateLink *l in _links) {
            if (l.fdsClosed) continue;
            shutdown(l.engineFd, SHUT_RDWR);
            shutdown(l.mockFd, SHUT_RDWR);
        }
        [_cond broadcast];
    }
    [_cond unlock];
}

- (BOOL)isClosed {
    BOOL closed;
    [_cond lock];
    closed = _closed;
    [_cond unlock];
    return closed;
}

/// Accepts until the gate closes; polls so that it notices the close within 100 ms.
- (void)acceptLoop {
    while (![self isClosed]) {
        struct pollfd p;
        int c;
        p.fd = _listenFd;
        p.events = POLLIN;
        p.revents = 0;
        if (poll(&p, 1, 100) <= 0) continue;
        c = accept(_listenFd, NULL, NULL);
        if (c >= 0) [self startLinkWithEngineFd:c];
    }
    close(_listenFd);
}

- (void)startLinkWithEngineFd:(int)engineFd {
    YGSTGateLink *link = [[YGSTGateLink alloc] init];
    struct sockaddr_in a;
    int mockFd = socket(AF_INET, SOCK_STREAM, 0);
    memset(&a, 0, sizeof a);
    a.sin_len = (uint8_t)sizeof a;
    a.sin_family = AF_INET;
    a.sin_port = htons(_mockPort);
    a.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    if (mockFd < 0 || connect(mockFd, (const struct sockaddr *)&a, (socklen_t)sizeof a) != 0) {
        if (mockFd >= 0) close(mockFd);
        close(engineFd);
        return;
    }
    YGSTGateNoSigPipe(engineFd);
    YGSTGateNoSigPipe(mockFd);
    link.engineFd = engineFd;
    link.mockFd = mockFd;
    [_cond lock];
    if (_closed) {
        [_cond unlock];
        close(engineFd);
        close(mockFd);
        return;
    }
    link.number = ++_count;
    [_links addObject:link];
    [self noteLocked:[NSString stringWithFormat:@"link %lu opened", (unsigned long)link.number]];
    [_cond unlock];
    dispatch_async(dispatch_queue_create("com.shengzhiai.yugu.test.gate.up", DISPATCH_QUEUE_SERIAL), ^{
        [self pumpUp:link];
    });
    dispatch_async(dispatch_queue_create("com.shengzhiai.yugu.test.gate.down", DISPATCH_QUEUE_SERIAL), ^{
        [self pumpDown:link];
    });
}

/// Engine to mock. The request head is read before any of it goes on, so the token of the connection
/// is known before the mock can answer.
- (void)pumpUp:(YGSTGateLink *)link {
    NSMutableData *head = [NSMutableData data];
    BOOL headSeen = NO;
    unsigned char tmp[16384];
    for (;;) {
        ssize_t r = recv(link.engineFd, tmp, sizeof tmp, 0);
        if (r <= 0) break;
        if (!headSeen) {
            NSUInteger end;
            [head appendBytes:tmp length:(NSUInteger)r];
            end = YGSTGateHeadEnd(head);
            if (end || head.length > 65536) {
                NSString *token = end ? YGSTGateTokenOfHead(head, end) : nil;
                headSeen = YES;
                [_cond lock];
                link.tokenId = token;
                [self noteLocked:[NSString stringWithFormat:@"link %lu token %@", (unsigned long)link.number, token ?: @"-"]];
                [_cond unlock];
            }
        }
        if (!YGSTGateSendAll(link.mockFd, tmp, (size_t)r)) break;
    }
    shutdown(link.mockFd, SHUT_WR);
    [_cond lock];
    link.engineGone = YES;
    [self noteLocked:[NSString stringWithFormat:@"link %lu closed by the engine", (unsigned long)link.number]];
    [_cond broadcast];
    [_cond unlock];
    [self pumpDone:link];
}

/// The answer of link reached the gate: waits until the check lets its token through. NO when the
/// engine closed the connection or the gate closed meanwhile; the answer is dropped then.
- (BOOL)awaitTurnOf:(YGSTGateLink *)link bytes:(NSUInteger)bytes {
    BOOL go;
    NSString *token;
    [_cond lock];
    token = link.tokenId ?: @"-";
    _state[token] = @"waiting";
    [self noteLocked:[NSString stringWithFormat:@"link %lu answer of %@ waits (%lu B)", (unsigned long)link.number, token,
                                                (unsigned long)bytes]];
    [_cond broadcast];
    while (!_closed && !link.engineGone && !_allowed[token]) [_cond wait];
    go = !_closed && !link.engineGone;
    if (!go) {
        _state[token] = @"dropped";
        [self noteLocked:[NSString stringWithFormat:@"link %lu answer of %@ dropped", (unsigned long)link.number, token]];
    }
    [_cond unlock];
    return go;
}

- (void)relayedAnswerOf:(YGSTGateLink *)link {
    NSString *token;
    [_cond lock];
    token = link.tokenId ?: @"-";
    _state[token] = @"relayed";
    [self noteLocked:[NSString stringWithFormat:@"link %lu answer of %@ relayed", (unsigned long)link.number, token]];
    [_cond broadcast];
    [_cond unlock];
}

/// Mock to engine: a request's first response as a whole, or every frame of a WebSocket.
- (void)pumpDown:(YGSTGateLink *)link {
    NSMutableData *buf = [NSMutableData data];
    NSUInteger end = 0;
    while (!(end = YGSTGateHeadEnd(buf))) {
        if (!YGSTGateFill(link.mockFd, buf, buf.length + 1)) break;
    }
    if (end) {
        if (YGSTGateStartsWith(buf.bytes, end, "HTTP/1.1 101")) {
            if (YGSTGateSendAll(link.engineFd, buf.bytes, end)) [self relayFramesOf:link buffer:buf from:end];
        } else {
            [self relayResponseOf:link buffer:buf];
        }
    }
    shutdown(link.engineFd, SHUT_WR);
    [self pumpDone:link];
}

/// The response to a request waits as a whole; afterwards the connection is relayed as it comes.
- (void)relayResponseOf:(YGSTGateLink *)link buffer:(NSMutableData *)buf {
    unsigned char tmp[16384];
    if (![self awaitTurnOf:link bytes:buf.length]) return;
    if (!YGSTGateSendAll(link.engineFd, buf.bytes, buf.length)) return;
    [self relayedAnswerOf:link];
    for (;;) {
        ssize_t r = recv(link.mockFd, tmp, sizeof tmp, 0);
        if (r <= 0 || !YGSTGateSendAll(link.engineFd, tmp, (size_t)r)) return;
    }
}

/// WebSocket frames from pos on: every frame goes on as it comes, a final waits for its turn first.
- (void)relayFramesOf:(YGSTGateLink *)link buffer:(NSMutableData *)buf from:(NSUInteger)pos {
    for (;;) {
        const unsigned char *p;
        NSUInteger header = 2, length, total;
        BOOL final;
        if (!YGSTGateFill(link.mockFd, buf, pos + 2)) return;
        p = (const unsigned char *)buf.bytes + pos;
        length = p[1] & 0x7f;
        if (length == 126) {
            header = 4;
        } else if (length == 127) {
            header = 10;
        }
        if ((p[1] & 0x80) != 0) header += 4;
        if (!YGSTGateFill(link.mockFd, buf, pos + header)) return;
        p = (const unsigned char *)buf.bytes + pos;
        if (length == 126) {
            length = (NSUInteger)p[2] << 8 | (NSUInteger)p[3];
        } else if (length == 127) {
            int i;
            length = 0;
            for (i = 2; i < 10; i++) length = length << 8 | (NSUInteger)p[i];
        }
        total = header + length;
        if (!YGSTGateFill(link.mockFd, buf, pos + total)) return;
        p = (const unsigned char *)buf.bytes + pos;
        final = (p[0] & 0x0f) == 1 && (p[0] & 0x80) != 0 && (p[1] & 0x80) == 0 && YGSTGateIsFinal(p + header, length);
        if (final && ![self awaitTurnOf:link bytes:length]) return;
        p = (const unsigned char *)buf.bytes + pos;
        if (!YGSTGateSendAll(link.engineFd, p, total)) return;
        if (final) [self relayedAnswerOf:link];
        pos += total;
        if (pos == buf.length) {
            buf.length = 0;
            pos = 0;
        }
    }
}

- (void)pumpDone:(YGSTGateLink *)link {
    [_cond lock];
    link.pumpsDone += 1;
    if (link.pumpsDone == 2 && !link.fdsClosed) {
        link.fdsClosed = YES;
        close(link.engineFd);
        close(link.mockFd);
    }
    [_cond unlock];
}

@end
