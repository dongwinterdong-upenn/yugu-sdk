//
//  YGSTRealtime.m
//  STKouyuEngine 平替层：realtime_feedback 实时评测，兼容 WebSocket 通道与朗读进度（2.1.0）。
//
//  The C controller ygst_rt (core/ygst_realtime.c) makes every decision: when to connect, what to
//  send, which progress to deliver, when to fall back to REST and when to reconnect. This file only
//  carries its actions out with NSURLSessionWebSocketTask, which exists from iOS 13 and macOS 10.15.
//  Everything runs on the engine queue.
//
//  Copyright 2026 优谷雅言 open.shengzhiai.com
//  SPDX-License-Identifier: Apache-2.0
//

#import "YGSTInternal.h"

// ------------------------------------------------------------------ transport

typedef NS_ENUM(NSInteger, YGSTSocketEvent) {
    YGSTSocketEventText = 0,    // text: one text frame of the platform
    YGSTSocketEventPong = 1,    // the pong of the last ping
    YGSTSocketEventFailure = 2, // localCode, or httpStatus of a refused handshake, and reason
};

typedef void (^YGSTSocketHandler)(YGSTSocketEvent event, NSData *text, int localCode, NSInteger httpStatus,
                                  NSString *reason);

/// One WebSocket connection. Its events arrive on the queue in order, until close or the first failure.
@protocol YGSTSocket <NSObject>
- (void)sendText:(NSString *)text;
- (void)sendData:(NSData *)data;
- (void)sendPing;
- (void)close;
@end

static NSURLSession *YGSTWebSocketSession(void) {
    static NSURLSession *session;
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        NSURLSessionConfiguration *c = [NSURLSessionConfiguration ephemeralSessionConfiguration];
        c.HTTPShouldSetCookies = NO;
        c.HTTPCookieAcceptPolicy = NSHTTPCookieAcceptPolicyNever;
        c.URLCache = nil;
        c.requestCachePolicy = NSURLRequestReloadIgnoringLocalCacheData;
        // the round's own timers bound the handshake, the heartbeat and the wait for the result
        c.timeoutIntervalForRequest = 3600;
        c.timeoutIntervalForResource = 24 * 3600;
        session = [NSURLSession sessionWithConfiguration:c];
    });
    return session;
}

API_AVAILABLE(macos(10.15), ios(13.0))
@interface YGSTURLSessionSocket : NSObject <YGSTSocket>
- (instancetype)initWithURL:(NSURL *)url queue:(dispatch_queue_t)queue handler:(YGSTSocketHandler)handler;
@end

@implementation YGSTURLSessionSocket {
    dispatch_queue_t _queue;
    NSURLSessionWebSocketTask *_task;
    YGSTSocketHandler _handler; // nil after close or the first failure: no further events
}

- (instancetype)initWithURL:(NSURL *)url queue:(dispatch_queue_t)queue handler:(YGSTSocketHandler)handler {
    if ((self = [super init])) {
        NSMutableURLRequest *req = [NSMutableURLRequest requestWithURL:url
                                                           cachePolicy:NSURLRequestReloadIgnoringLocalCacheData
                                                       timeoutInterval:3600];
        [req setValue:@YGST_USER_AGENT forHTTPHeaderField:@"User-Agent"];
        _queue = queue;
        _handler = [handler copy];
        _task = [YGSTWebSocketSession() webSocketTaskWithRequest:req];
        _task.maximumMessageSize = 16 * 1024 * 1024;
        [_task resume];
        [self receiveOn:_task];
    }
    return self;
}

- (void)receiveOn:(NSURLSessionWebSocketTask *)task {
    __weak YGSTURLSessionSocket *weakSelf = self;
    dispatch_queue_t queue = _queue;
    [task receiveMessageWithCompletionHandler:^(NSURLSessionWebSocketMessage *message, NSError *error) {
        dispatch_async(queue, ^{
            YGSTURLSessionSocket *strongSelf = weakSelf;
            if (!strongSelf || strongSelf->_task != task || !strongSelf->_handler) return;
            if (error || !message) {
                [strongSelf failWithError:error];
                return;
            }
            // the platform sends text frames only; a binary frame is not part of the protocol
            if (message.type == NSURLSessionWebSocketMessageTypeString) {
                NSData *text = [message.string dataUsingEncoding:NSUTF8StringEncoding];
                strongSelf->_handler(YGSTSocketEventText, text ?: [NSData data], 0, 0, nil);
            }
            if (strongSelf->_handler && strongSelf->_task == task) [strongSelf receiveOn:task];
        });
    }];
}

/// The first failure ends the connection: a refused handshake with its HTTP status, a close frame
/// of the platform, or the URL loading error.
- (void)failWithError:(NSError *)error {
    NSURLSessionWebSocketTask *task = _task;
    YGSTSocketHandler handler = _handler;
    NSHTTPURLResponse *http = [task.response isKindOfClass:[NSHTTPURLResponse class]] ? (NSHTTPURLResponse *)task.response
                                                                                       : nil;
    NSInteger status = (http && http.statusCode != 101) ? http.statusCode : 0;
    NSInteger closeCode = task.closeCode;
    int local = 0;
    NSString *reason;
    _handler = nil;
    _task = nil;
    [task cancel];
    if (status) {
        reason = @"WebSocket handshake refused";
    } else if (closeCode != NSURLSessionWebSocketCloseCodeInvalid) {
        local = YGST_LOCAL_NETWORK;
        reason = [NSString stringWithFormat:@"network error: the platform closed the connection with code %ld",
                                            (long)closeCode];
    } else if (error) {
        local = YGSTLocalCodeForError(error);
        reason = YGSTLocalMessageForError(error);
    } else {
        local = YGST_LOCAL_NETWORK;
        reason = @"network error: the connection ended";
    }
    if (handler) handler(YGSTSocketEventFailure, nil, local, status, reason);
}

- (void)send:(NSURLSessionWebSocketMessage *)message {
    NSURLSessionWebSocketTask *task = _task;
    __weak YGSTURLSessionSocket *weakSelf = self;
    dispatch_queue_t queue = _queue;
    if (!task || !_handler) return;
    // URLSession keeps the order of the sends of one task
    [task sendMessage:message
        completionHandler:^(NSError *error) {
            if (!error) return;
            dispatch_async(queue, ^{
                YGSTURLSessionSocket *strongSelf = weakSelf;
                if (!strongSelf || strongSelf->_task != task || !strongSelf->_handler) return;
                [strongSelf failWithError:error];
            });
        }];
}

- (void)sendText:(NSString *)text {
    [self send:[[NSURLSessionWebSocketMessage alloc] initWithString:text]];
}

- (void)sendData:(NSData *)data {
    [self send:[[NSURLSessionWebSocketMessage alloc] initWithData:data]];
}

- (void)sendPing {
    NSURLSessionWebSocketTask *task = _task;
    __weak YGSTURLSessionSocket *weakSelf = self;
    dispatch_queue_t queue = _queue;
    if (!task || !_handler) return;
    [task sendPingWithPongReceiveHandler:^(NSError *error) {
        dispatch_async(queue, ^{
            YGSTURLSessionSocket *strongSelf = weakSelf;
            if (!strongSelf || strongSelf->_task != task || !strongSelf->_handler) return;
            if (error) {
                [strongSelf failWithError:error];
            } else {
                strongSelf->_handler(YGSTSocketEventPong, nil, 0, 0, nil);
            }
        });
    }];
}

- (void)close {
    NSURLSessionWebSocketTask *task = _task;
    _handler = nil;
    _task = nil;
    [task cancelWithCloseCode:NSURLSessionWebSocketCloseCodeNormalClosure reason:nil];
}

@end

// ------------------------------------------------------------------ round

static const int64_t kYGSTRealtimeTickMs = 250;

@implementation YGSTRealtimeRound {
    dispatch_queue_t _queue;
    ygst_rt _rt;
    NSString *_baseURL;
    NSString *_coreType;
    NSString *_appKey;
    NSString *_secret;
    NSString *_tokenId;
    NSString *_userId;
    NSString *_refText;
    NSString *_paramFrame;   // nil when it could not be built: the round falls back at once
    NSData *_audio;          // the round audio, the session's buffer
    id<YGSTSocket> _socket;
    dispatch_source_t _timer;
    BOOL _finished;          // final, error, fallback or cancel handled: no handler any more
    BOOL _toldFallback;      // the failure before end was logged
}

+ (BOOL)isAvailable {
    if (@available(iOS 13.0, macOS 10.15, *)) return YES;
    return NO;
}

- (instancetype)initWithQueue:(dispatch_queue_t)queue
                      baseURL:(NSString *)baseURL
                     coreType:(NSString *)coreType
                       appKey:(NSString *)appKey
                       secret:(NSString *)secret
                      tokenId:(NSString *)tokenId
                       userId:(NSString *)userId
                      refText:(NSString *)refText
                       fields:(const ygst_fields *)fields
                       policy:(const ygst_retry_policy *)policy
               connectTimeout:(NSTimeInterval)connectTimeout
               attemptTimeout:(NSTimeInterval)attemptTimeout {
    if ((self = [super init])) {
        ygst_buf frame;
        int skipped;
        uint64_t seed = 0;
        _queue = queue;
        _baseURL = [baseURL copy];
        _coreType = [coreType copy];
        _appKey = [appKey copy];
        _secret = [secret copy];
        _tokenId = [tokenId copy];
        _userId = [userId copy] ?: @"";
        _refText = [refText copy] ?: @"";
        _audio = [NSData data];
        ygst_rt_init(&_rt);
        _rt.policy = *policy;
        YGSTRandomBytes((unsigned char *)&seed, sizeof seed);
        _rt.seed = seed;
        if (connectTimeout > 0) _rt.connect_timeout_ms = (int)(connectTimeout * 1000.0);
        if (attemptTimeout > 0) _rt.attempt_timeout_ms = (int)(attemptTimeout * 1000.0);
        ygst_buf_init(&frame);
        skipped = ygst_ws_param_frame(fields, _tokenId.UTF8String, &frame);
        if (skipped >= 0) _paramFrame = YGSTStringFromBuf(&frame);
        if (skipped > 0) {
            YGSTLogf(YGST_LEVEL_WARN, @"customParams named cmd, end, data, realtime_feedback or idempotencyKey are "
                                      @"left out of the realtime parameter frame");
        }
        ygst_buf_free(&frame);
    }
    return self;
}

- (void)dealloc {
    if (_timer) dispatch_source_cancel(_timer);
    [_socket close];
    ygst_rt_free(&_rt);
}

- (void)start {
    int64_t now = YGSTMonotonicMs();
    if (_finished || _rt.state != YGST_RT_IDLE) return;
    YGSTLogf(YGST_LEVEL_INFO, @"session %@: realtime feedback over the compat WebSocket", _tokenId);
    [self startTimer];
    if (!_paramFrame.length) {
        (void)ygst_rt_start(&_rt, now);
        [self apply:ygst_rt_on_failure(&_rt, 90010, 0, "the realtime parameter frame could not be built", now) frame:nil];
        return;
    }
    [self apply:ygst_rt_start(&_rt, now) frame:nil];
}

- (void)audioAppended:(NSData *)audio {
    if (_finished || !audio) return;
    _audio = audio;
    [self apply:ygst_rt_on_audio(&_rt, audio.length, YGSTMonotonicMs()) frame:nil];
}

- (BOOL)finish {
    ygst_rt_actions a;
    if (_finished) return NO;
    a = ygst_rt_on_stop(&_rt, _audio.length, YGSTMonotonicMs());
    if (a.fallback) {
        YGSTLogf(YGST_LEVEL_INFO, @"session %@: evaluating in one request", _tokenId);
        [self teardown];
        return NO;
    }
    [self apply:a frame:nil];
    return YES;
}

- (void)cancel {
    if (_finished) return;
    ygst_rt_cancel(&_rt);
    [self teardown];
}

- (void)teardown {
    _finished = YES;
    [_socket close];
    _socket = nil;
    [self stopTimer];
    _progressHandler = nil;
    _resultHandler = nil;
    _fallbackHandler = nil;
}

// ------------------------------------------------------------------ timer

- (void)startTimer {
    __weak YGSTRealtimeRound *weakSelf = self;
    uint64_t interval = (uint64_t)kYGSTRealtimeTickMs * NSEC_PER_MSEC;
    dispatch_source_t t;
    if (_timer) return;
    t = dispatch_source_create(DISPATCH_SOURCE_TYPE_TIMER, 0, 0, _queue);
    dispatch_source_set_timer(t, dispatch_time(DISPATCH_TIME_NOW, (int64_t)interval), interval, interval / 5);
    dispatch_source_set_event_handler(t, ^{
        [weakSelf tick];
    });
    _timer = t;
    dispatch_resume(t);
}

- (void)stopTimer {
    if (!_timer) return;
    dispatch_source_cancel(_timer);
    _timer = nil;
}

- (void)tick {
    [self apply:ygst_rt_on_tick(&_rt, YGSTMonotonicMs()) frame:nil];
}

// ------------------------------------------------------------------ actions

- (void)connect {
    unsigned char rnd[8];
    char nonce[17];
    ygst_buf url;
    NSURL *u = nil;
    int64_t now = YGSTMonotonicMs();
    YGSTRandomBytes(rnd, sizeof rnd);
    ygst_hex_lower(rnd, sizeof rnd, nonce);
    ygst_buf_init(&url);
    if (ygst_ws_handshake_url(_baseURL.UTF8String, _coreType.UTF8String, _appKey.UTF8String, _secret.UTF8String,
                              YGSTNowMs() / 1000, nonce, _tokenId.UTF8String, NULL, &url) == 0) {
        u = [NSURL URLWithString:YGSTStringFromBuf(&url)];
    }
    ygst_buf_free(&url);
    if (!u) {
        [self apply:ygst_rt_on_failure(&_rt, 90010, 0, "invalid realtime URL", now) frame:nil];
        return;
    }
    // the URL carries the signature: only the path goes to the log
    YGSTLogf(YGST_LEVEL_DEBUG, @"session %@: realtime connection %d to %@", _tokenId, _rt.connections, u.path);
    if (@available(iOS 13.0, macOS 10.15, *)) {
        __weak YGSTRealtimeRound *weakSelf = self;
        _socket = [[YGSTURLSessionSocket alloc] initWithURL:u
                                                      queue:_queue
                                                    handler:^(YGSTSocketEvent event, NSData *text, int localCode,
                                                              NSInteger httpStatus, NSString *reason) {
                                                        [weakSelf socketEvent:event
                                                                         text:text
                                                                    localCode:localCode
                                                                   httpStatus:httpStatus
                                                                       reason:reason];
                                                    }];
    } else {
        [self apply:ygst_rt_on_failure(&_rt, YGST_LOCAL_NETWORK, 0, "WebSocket needs iOS 13", now) frame:nil];
    }
}

- (void)socketEvent:(YGSTSocketEvent)event
               text:(NSData *)text
          localCode:(int)localCode
         httpStatus:(NSInteger)httpStatus
             reason:(NSString *)reason {
    int64_t now = YGSTMonotonicMs();
    switch (event) {
    case YGSTSocketEventText:
        [self apply:ygst_rt_on_text(&_rt, (const char *)text.bytes, text.length, now) frame:text];
        break;
    case YGSTSocketEventPong:
        [self apply:ygst_rt_on_pong(&_rt, now) frame:nil];
        break;
    case YGSTSocketEventFailure:
        _socket = nil;
        [self apply:ygst_rt_on_failure(&_rt, localCode, (int)httpStatus, (reason ?: @"").UTF8String, now) frame:nil];
        break;
    }
}

/// Carries out the actions of the controller in their order. frame: the text the actions answer,
/// alive for the call, which the progress and final spans point into.
- (void)apply:(ygst_rt_actions)a frame:(NSData *)frame {
    if (_finished) return;
    if (a.close) {
        [_socket close];
        _socket = nil;
    }
    if (a.connect) {
        [self connect];
        if (_finished) return; // the connection could not be requested and the round ended
    }
    if (a.send_params) [_socket sendText:_paramFrame];
    if (a.audio_to > a.audio_from) [self sendAudioFrom:a.audio_from to:a.audio_to];
    if (a.send_end) [_socket sendText:@"{\"cmd\":\"end\"}"];
    if (a.send_ping) [_socket sendPing];
    if (a.progress) [self deliverProgressSkipping:a.words_skip];
    if (a.progress_held) {
        YGSTLogf(YGST_LEVEL_DEBUG, @"session %@: progress of units already delivered left out (readUnits %d)", _tokenId,
                 _rt.frame.read_units);
    }
    if (a.retry) {
        YGSTLogf(YGST_LEVEL_WARN, @"session %@: realtime reconnect %d/%d in %ld ms: %s", _tokenId, _rt.call.attempt,
                 _rt.policy.max_retries, a.delay_ms, ygst_buf_cstr(&_rt.failure));
    }
    if (a.final) {
        [self deliverResult:YGST_ACT_SUCCESS errId:0 body:frame];
        return;
    }
    if (a.fail) {
        [self deliverResult:YGST_ACT_FAIL errId:a.err_id body:nil];
        return;
    }
    if (a.fallback) {
        void (^handler)(void) = _fallbackHandler;
        _toldFallback = YES;
        YGSTLogf(YGST_LEVEL_WARN, @"session %@: realtime connection failed before end (%s), evaluating in one request",
                 _tokenId, ygst_buf_cstr(&_rt.failure));
        [self teardown];
        if (handler) handler();
        return;
    }
    if (!_finished && _rt.state == YGST_RT_FALLBACK && !_toldFallback) {
        _toldFallback = YES;
        [self stopTimer];
        YGSTLogf(YGST_LEVEL_WARN, @"session %@: realtime connection failed before end (%s), progress stops, the "
                                  @"evaluation goes in one request at stopEngine",
                 _tokenId, ygst_buf_cstr(&_rt.failure));
    }
}

- (void)sendAudioFrom:(size_t)from to:(size_t)to {
    size_t max = _rt.max_frame_bytes ? _rt.max_frame_bytes : YGST_WS_MAX_FRAME_BYTES;
    if (to > _audio.length) to = _audio.length;
    while (from < to) {
        size_t n = to - from < max ? to - from : max;
        [_socket sendData:[_audio subdataWithRange:NSMakeRange(from, n)]];
        from += n;
    }
}

- (void)deliverProgressSkipping:(int)skip {
    ygst_buf b;
    char dt[32];
    NSString *json = nil;
    void (^handler)(NSString *) = _progressHandler;
    ygst_format_dt(YGSTNowMs(), YGSTTimeZoneOffsetMinutes(), dt);
    ygst_buf_init(&b);
    if (ygst_envelope_progress(&b, _tokenId.UTF8String, _appKey.UTF8String, _userId.UTF8String, _refText.UTF8String, dt,
                               _rt.frame.result, skip) == 0) {
        json = YGSTStringFromBuf(&b);
    }
    ygst_buf_free(&b);
    if (json.length && handler) handler(json);
}

/// The final frame, or the error after the last attempt: built like the REST uploader's result.
- (void)deliverResult:(ygst_action_kind)kind errId:(int)errId body:(NSData *)body {
    BOOL replayed = kind == YGST_ACT_SUCCESS && _rt.frame.replayed;
    YGSTUploadResult *r = YGSTUploadResultMake(&_rt.cls, kind, errId, body, &_rt.call, replayed);
    void (^handler)(YGSTUploadResult *) = _resultHandler;
    if (r.success) {
        YGSTLogf(YGST_LEVEL_INFO, @"session %@: evaluated over the realtime connection, recordId %@, %d connection(s)%@",
                 _tokenId, r.recordId, _rt.connections, replayed ? @" (replayed)" : @"");
    } else {
        YGSTLogf(YGST_LEVEL_WARN, @"session %@: realtime evaluation failed after %d connection(s): errId %d %@", _tokenId,
                 _rt.connections, r.errId, r.message);
    }
    [self teardown];
    if (handler) handler(r);
}

@end
