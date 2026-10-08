//
//  YGSTSupport.m
//  STKouyuEngine 平替层：日志，全局设置，工具函数，请求组装与 HTTP 执行。
//
//  Copyright 2026 优谷雅言 open.shengzhiai.com
//  SPDX-License-Identifier: Apache-2.0
//

#import "YGSTInternal.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

// ------------------------------------------------------------------ settings

@implementation YGSTSettings

+ (instancetype)shared {
    static YGSTSettings *shared;
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        shared = [[YGSTSettings alloc] init];
    });
    return shared;
}

- (instancetype)init {
    if ((self = [super init])) {
        _logLevelOverride = NSNotFound;
        _maxRetries = 2;
        _totalTimeout = 300;
        _configuredBaseURL = @YGST_DEFAULT_BASE_URL;
    }
    return self;
}

@end

// ------------------------------------------------------------------ logging

static NSObject *YGSTLogLock(void) {
    static NSObject *lock;
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        lock = [[NSObject alloc] init];
    });
    return lock;
}

static dispatch_queue_t YGSTLogQueue(void) {
    static dispatch_queue_t q;
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        q = dispatch_queue_create("com.shengzhiai.yugu.stcompat.log", DISPATCH_QUEUE_SERIAL);
    });
    return q;
}

static NSInteger g_logLevel = YGST_LEVEL_WARN;
static BOOL g_logConsole = YES;
static BOOL g_logFile = NO;
static NSString *g_logPath = nil;

void YGSTLogConfigure(BOOL fileEnabled, NSInteger level, NSString *filePath, BOOL consoleEnabled) {
    @synchronized(YGSTLogLock()) {
        g_logLevel = level < YGST_LEVEL_ERROR ? YGST_LEVEL_ERROR : (level > YGST_LEVEL_DEBUG ? YGST_LEVEL_DEBUG : level);
        g_logConsole = consoleEnabled;
        g_logFile = fileEnabled;
        g_logPath = filePath.length ? [filePath copy]
                                    : [YGSTDocumentsPath() stringByAppendingPathComponent:@"sdkLog.txt"];
    }
}

NSInteger YGSTLogLevel(void) {
    NSInteger o = [YGSTSettings shared].logLevelOverride;
    if (o != NSNotFound) return o;
    @synchronized(YGSTLogLock()) {
        return g_logLevel;
    }
}

void YGSTLogEmit(NSInteger level, NSString *message) {
    NSInteger threshold = YGSTLogLevel();
    BOOL console, file;
    NSString *path;
    void (^handler)(NSInteger, NSString *);
    static NSString *const names[] = {@"E", @"W", @"I", @"D"};
    if (level < YGST_LEVEL_ERROR || threshold < YGST_LEVEL_ERROR || level > threshold) return;
    @synchronized(YGSTLogLock()) {
        console = g_logConsole;
        file = g_logFile;
        path = g_logPath;
    }
    handler = [YGSTSettings shared].logHandler;
    if (handler) {
        handler(level, message);
    } else if (console) {
        NSLog(@"[STKouyuEngine][%@] %@", names[level > 3 ? 3 : level], message);
    }
    if (file && path.length) {
        char dt[32];
        NSString *line;
        ygst_format_dt(YGSTNowMs(), YGSTTimeZoneOffsetMinutes(), dt);
        line = [NSString stringWithFormat:@"%s [%@] %@\n", dt, names[level > 3 ? 3 : level], message];
        dispatch_async(YGSTLogQueue(), ^{
            NSFileManager *fm = [NSFileManager defaultManager];
            NSData *data = [line dataUsingEncoding:NSUTF8StringEncoding];
            NSFileHandle *h;
            if (![fm fileExistsAtPath:path]) {
                [fm createDirectoryAtPath:[path stringByDeletingLastPathComponent]
                    withIntermediateDirectories:YES
                                     attributes:nil
                                          error:NULL];
                [fm createFileAtPath:path contents:nil attributes:nil];
            }
            h = [NSFileHandle fileHandleForWritingAtPath:path];
            if (!h) return;
            @try {
                [h seekToEndOfFile];
                [h writeData:data];
            } @catch (NSException *e) {
                (void)e;
            }
            [h closeFile];
        });
    }
}

void YGSTLogf(NSInteger level, NSString *format, ...) {
    va_list ap;
    NSString *msg;
    if (level > YGSTLogLevel()) return;
    va_start(ap, format);
    msg = [[NSString alloc] initWithFormat:format arguments:ap];
    va_end(ap);
    YGSTLogEmit(level, msg);
}

// ------------------------------------------------------------------ helpers

void YGSTRandomBytes(unsigned char *out, size_t n) {
    arc4random_buf(out, n);
}

NSString *YGSTRandomHex(NSUInteger bytes) {
    unsigned char buf[64];
    char hex[129];
    if (bytes > 64) bytes = 64;
    YGSTRandomBytes(buf, bytes);
    ygst_hex_lower(buf, bytes, hex);
    return [NSString stringWithUTF8String:hex];
}

NSString *YGSTNewTokenId(void) {
    unsigned char rnd[16];
    char tok[33];
    YGSTRandomBytes(rnd, sizeof rnd);
    ygst_token_id(rnd, tok);
    return [NSString stringWithUTF8String:tok];
}

NSString *YGSTJSONText(id obj) {
    NSData *data;
    if (!obj || obj == [NSNull null]) return nil;
    if ([obj isKindOfClass:[NSString class]]) return obj;
    if (![NSJSONSerialization isValidJSONObject:obj]) return nil;
    data = [NSJSONSerialization dataWithJSONObject:obj options:NSJSONWritingSortedKeys error:NULL];
    if (!data) return nil;
    return [[NSString alloc] initWithData:data encoding:NSUTF8StringEncoding];
}

NSString *YGSTValueText(id value) {
    if (!value || value == [NSNull null]) return nil;
    if ([value isKindOfClass:[NSString class]]) return value;
    if ([value isKindOfClass:[NSNumber class]]) {
        NSNumber *n = value;
        const char *t = [n objCType];
        char buf[64];
        if (CFGetTypeID((__bridge CFTypeRef)n) == CFBooleanGetTypeID()) return [n boolValue] ? @"true" : @"false";
        if (t && t[0] && t[1] == 0 && strchr("cCsSiIlLqQB", t[0])) {
            if (strchr("CSILQ", t[0])) return [NSString stringWithFormat:@"%llu", [n unsignedLongLongValue]];
            return [NSString stringWithFormat:@"%lld", [n longLongValue]];
        }
        if (ygst_fmt_double([n doubleValue], buf, sizeof buf) == 0) return [NSString stringWithUTF8String:buf];
        return [n stringValue];
    }
    if ([value isKindOfClass:[NSArray class]] || [value isKindOfClass:[NSDictionary class]]) return YGSTJSONText(value);
    return [value description];
}

NSString *YGSTStringFromBuf(ygst_buf *b) {
    NSString *s;
    if (!b->len) return @"";
    s = [[NSString alloc] initWithBytes:b->data length:b->len encoding:NSUTF8StringEncoding];
    return s ?: @"";
}

NSString *YGSTDocumentsPath(void) {
    NSString *docs = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, YES).firstObject;
    return docs.length ? docs : NSTemporaryDirectory();
}

int64_t YGSTNowMs(void) {
    return (int64_t)([[NSDate date] timeIntervalSince1970] * 1000.0);
}

int64_t YGSTMonotonicMs(void) {
    return (int64_t)([[NSProcessInfo processInfo] systemUptime] * 1000.0);
}

int YGSTTimeZoneOffsetMinutes(void) {
    return (int)([[NSTimeZone localTimeZone] secondsFromGMTForDate:[NSDate date]] / 60);
}

NSString *YGSTNonEmpty(NSString *s) {
    return s ?: @"";
}

NSString *YGSTCompatMessage(int errId) {
    const char *m = ygst_compat_message(errId);
    if (!m) {
        const ygst_error_entry *e = ygst_local_find(errId);
        if (!e) e = ygst_error_find(errId);
        m = e ? e->message : "";
    }
    return [NSString stringWithUTF8String:m] ?: @"";
}

NSString *YGSTErrorJSON(NSString *tokenId, int errId, NSString *message, NSString *appKey) {
    ygst_buf b;
    NSString *s;
    ygst_buf_init(&b);
    ygst_envelope_error(&b, YGSTNonEmpty(tokenId).UTF8String, errId,
                        (message.length ? message : YGSTCompatMessage(errId)).UTF8String,
                        YGSTNonEmpty(appKey).UTF8String);
    s = YGSTStringFromBuf(&b);
    ygst_buf_free(&b);
    return s;
}

NSString *YGSTMaskedAppKey(NSString *appKey) {
    char out[16];
    ygst_mask_app_key(YGSTNonEmpty(appKey).UTF8String, out);
    return [NSString stringWithUTF8String:out] ?: @"***";
}

NSData *YGSTWavFromPCM(NSData *pcm, int sampleRate, int channels, int bits) {
    unsigned char header[44];
    NSMutableData *wav;
    ygst_wav_header(header, (uint32_t)pcm.length, sampleRate, channels, bits);
    wav = [NSMutableData dataWithCapacity:pcm.length + sizeof header];
    [wav appendBytes:header length:sizeof header];
    [wav appendData:pcm];
    return wav;
}

NSString *YGSTResultJSON(YGSTUploadResult *result, NSString *tokenId, NSString *appKey, NSString *userId,
                         NSString *refText, NSString *paramsJSON) {
    ygst_buf b;
    ygst_span raw;
    char dt[32];
    NSString *s;
    if (!result.success || !result.body || NSMaxRange(result.resultRange) > result.body.length) return nil;
    raw.p = (const char *)result.body.bytes + result.resultRange.location;
    raw.n = result.resultRange.length;
    ygst_format_dt(YGSTNowMs(), YGSTTimeZoneOffsetMinutes(), dt);
    ygst_buf_init(&b);
    if (ygst_envelope_result(&b, YGSTNonEmpty(tokenId).UTF8String, result.recordIdJSON.UTF8String,
                             YGSTNonEmpty(appKey).UTF8String, YGSTNonEmpty(userId).UTF8String,
                             YGSTNonEmpty(refText).UTF8String, dt, raw, paramsJSON.UTF8String,
                             result.audioUrl.UTF8String) != 0) {
        ygst_buf_free(&b);
        return nil;
    }
    s = [[NSString alloc] initWithBytes:b.data length:b.len encoding:NSUTF8StringEncoding];
    ygst_buf_free(&b);
    return s;
}

// ------------------------------------------------------------------ request

@implementation YGSTRequest

+ (instancetype)requestWithBaseURL:(NSString *)baseURL
                          coreType:(NSString *)coreType
                            fields:(const ygst_fields *)fields
                            secret:(NSString *)secret
                             audio:(NSData *)audio
                         extension:(NSString *)extension
                             error:(NSString **)error {
    char url[2048];
    char sig[45];
    char boundary[64];
    char contentType[160];
    char filename[64];
    const char *ext = ygst_audio_ext(extension.UTF8String);
    ygst_buf body;
    int rc = -2;
    int tries;
    YGSTRequest *r;
    if (ygst_build_url(baseURL.UTF8String, coreType.UTF8String, url, sizeof url) != 0) {
        if (error) *error = @"platform URL is too long";
        return nil;
    }
    if (ygst_sign(fields, YGSTNonEmpty(secret).UTF8String, sig) != 0) {
        if (error) *error = @"request signing failed";
        return nil;
    }
    snprintf(filename, sizeof filename, "audio.%s", ext);
    ygst_buf_init(&body);
    for (tries = 0; tries < 4 && rc == -2; tries++) {
        unsigned char rnd[16];
        YGSTRandomBytes(rnd, sizeof rnd);
        ygst_multipart_boundary(rnd, boundary);
        ygst_buf_reset(&body);
        rc = ygst_multipart_build(fields, boundary, "audio", filename, ygst_audio_content_type(ext),
                                  (const unsigned char *)audio.bytes, audio.length, &body);
    }
    if (rc != 0) {
        ygst_buf_free(&body);
        if (error) *error = @"multipart body could not be built";
        return nil;
    }
    ygst_multipart_content_type(boundary, contentType, sizeof contentType);
    r = [[YGSTRequest alloc] init];
    r.url = [NSURL URLWithString:[NSString stringWithUTF8String:url]];
    r.body = [NSData dataWithBytes:body.data length:body.len];
    r.contentType = [NSString stringWithUTF8String:contentType];
    r.signature = [NSString stringWithUTF8String:sig];
    ygst_buf_free(&body);
    if (!r.url) {
        if (error) *error = [NSString stringWithFormat:@"invalid platform URL %s", url];
        return nil;
    }
    return r;
}

@end

// ------------------------------------------------------------------ HTTP

NSURLSession *YGSTSharedURLSession(void) {
    static NSURLSession *session;
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        NSURLSessionConfiguration *c = [NSURLSessionConfiguration ephemeralSessionConfiguration];
        c.HTTPShouldSetCookies = NO;
        c.HTTPCookieAcceptPolicy = NSHTTPCookieAcceptPolicyNever;
        c.URLCache = nil;
        c.requestCachePolicy = NSURLRequestReloadIgnoringLocalCacheData;
        c.timeoutIntervalForRequest = 60;
        c.timeoutIntervalForResource = 600;
        session = [NSURLSession sessionWithConfiguration:c];
    });
    return session;
}

@implementation YGSTUploadResult
@end

static NSString *YGSTHeader(NSHTTPURLResponse *response, NSString *name) {
    NSDictionary *headers = response.allHeaderFields;
    for (id key in headers) {
        if ([key isKindOfClass:[NSString class]] && [(NSString *)key caseInsensitiveCompare:name] == NSOrderedSame) {
            id v = headers[key];
            return [v isKindOfClass:[NSString class]] ? v : [v description];
        }
    }
    return nil;
}

static int YGSTLocalCodeForError(NSError *error) {
    if ([error.domain isEqualToString:NSURLErrorDomain]) {
        switch (error.code) {
        case NSURLErrorTimedOut:
            return YGST_LOCAL_TIMEOUT;
        case NSURLErrorSecureConnectionFailed:
        case NSURLErrorServerCertificateHasBadDate:
        case NSURLErrorServerCertificateUntrusted:
        case NSURLErrorServerCertificateHasUnknownRoot:
        case NSURLErrorServerCertificateNotYetValid:
        case NSURLErrorClientCertificateRejected:
        case NSURLErrorClientCertificateRequired:
        case NSURLErrorAppTransportSecurityRequiresSecureConnection:
            return YGST_LOCAL_TLS;
        default:
            return YGST_LOCAL_NETWORK;
        }
    }
    return YGST_LOCAL_NETWORK;
}

@implementation YGSTUploader {
    NSURLSession *_session;
    dispatch_queue_t _queue;
    NSURLSessionTask *_task;
    BOOL _cancelled;
    ygst_call _call;
    NSURL *_url;
    NSString *_appKey;
    NSString *_signature;
    NSString *_key;
    NSString *_contentType;
    NSData *_body;
    NSTimeInterval _timeout;
    void (^_completion)(YGSTUploadResult *result);
}

- (instancetype)initWithURLSession:(NSURLSession *)session queue:(dispatch_queue_t)queue {
    if ((self = [super init])) {
        _session = session;
        _queue = queue;
    }
    return self;
}

- (void)startWithURL:(NSURL *)url
              appKey:(NSString *)appKey
           signature:(NSString *)signature
      idempotencyKey:(NSString *)idempotencyKey
                body:(NSData *)body
         contentType:(NSString *)contentType
      attemptTimeout:(NSTimeInterval)attemptTimeout
              policy:(const ygst_retry_policy *)policy
          completion:(void (^)(YGSTUploadResult *result))completion {
    uint64_t seed = 0;
    _url = url;
    _appKey = [appKey copy];
    _signature = [signature copy];
    _key = [idempotencyKey copy];
    _body = body;
    _contentType = [contentType copy];
    _timeout = attemptTimeout > 0 ? attemptTimeout : 60;
    _completion = [completion copy];
    YGSTRandomBytes((unsigned char *)&seed, sizeof seed);
    ygst_call_init(&_call, policy, seed, YGSTMonotonicMs());
    dispatch_async(_queue, ^{
        [self attempt];
    });
}

- (void)cancel {
    dispatch_async(_queue, ^{
        self->_cancelled = YES;
        [self->_task cancel];
        self->_task = nil;
        self->_completion = nil;
    });
}

- (void)attempt {
    NSMutableURLRequest *req;
    NSURLSessionUploadTask *task;
    if (_cancelled) return;
    req = [NSMutableURLRequest requestWithURL:_url
                                  cachePolicy:NSURLRequestReloadIgnoringLocalCacheData
                              timeoutInterval:_timeout];
    req.HTTPMethod = @"POST";
    [req setValue:_contentType forHTTPHeaderField:@"Content-Type"];
    [req setValue:_appKey forHTTPHeaderField:@"X-App-Key"];
    [req setValue:[NSString stringWithFormat:@"%lld", (long long)(YGSTNowMs() / 1000)]
        forHTTPHeaderField:@"X-Timestamp"];
    [req setValue:YGSTRandomHex(8) forHTTPHeaderField:@"X-Nonce"];
    [req setValue:_signature forHTTPHeaderField:@"X-Signature"];
    [req setValue:_key forHTTPHeaderField:@"Idempotency-Key"];
    [req setValue:@YGST_USER_AGENT forHTTPHeaderField:@"User-Agent"];
    YGSTLogf(YGST_LEVEL_DEBUG, @"POST %@ attempt %d key %@", _url.path, _call.total_attempts + 1, _key);
    task = [_session uploadTaskWithRequest:req
                                  fromData:_body
                         completionHandler:^(NSData *data, NSURLResponse *response, NSError *error) {
                             dispatch_async(self->_queue, ^{
                                 [self attemptFinished:data response:response error:error];
                             });
                         }];
    _task = task;
    [task resume];
}

- (void)attemptFinished:(NSData *)data response:(NSURLResponse *)response error:(NSError *)error {
    ygst_outcome o;
    ygst_classification c;
    ygst_action a;
    NS_VALID_UNTIL_END_OF_SCOPE NSString *localMessage = nil;
    NSHTTPURLResponse *http = nil;
    BOOL replayed = NO;
    _task = nil;
    if (_cancelled) return;
    memset(&o, 0, sizeof o);
    o.retry_after_ms = -1;
    if (error) {
        NSString *desc = error.localizedDescription ?: @"";
        o.local_code = YGSTLocalCodeForError(error);
        if (error.code == NSURLErrorAppTransportSecurityRequiresSecureConnection) {
            localMessage = @"TLS certificate check failed: App Transport Security blocks this http address, use https "
                           @"or an ATS exception";
        } else if (o.local_code == YGST_LOCAL_TIMEOUT) {
            localMessage = [@"timeout: " stringByAppendingString:desc];
        } else if (o.local_code == YGST_LOCAL_TLS) {
            localMessage = [@"TLS certificate check failed: " stringByAppendingString:desc];
        } else {
            localMessage = [NSString stringWithFormat:@"network error: %@ %ld %@", error.domain, (long)error.code, desc];
        }
    } else {
        NSString *ra, *rep;
        http = [response isKindOfClass:[NSHTTPURLResponse class]] ? (NSHTTPURLResponse *)response : nil;
        o.http_status = http ? (int)http.statusCode : 0;
        if (!http) o.local_code = YGST_LOCAL_PROTOCOL;
        ra = YGSTHeader(http, @"Retry-After");
        if (ra) {
            long ms = 0;
            if (ygst_parse_retry_after(ra.UTF8String, YGSTNowMs(), &ms) == 0) o.retry_after_ms = ms;
        }
        rep = YGSTHeader(http, @"Idempotency-Replayed");
        replayed = rep && [rep caseInsensitiveCompare:@"true"] == NSOrderedSame;
        o.replayed = replayed;
        o.body = (const char *)data.bytes;
        o.body_len = data.length;
    }
    o.local_message = localMessage.UTF8String;
    ygst_classification_init(&c);
    ygst_classify(&o, &c);
    a = ygst_call_next(&_call, &c, o.retry_after_ms, YGSTMonotonicMs());
    if (a.kind == YGST_ACT_RETRY) {
        NSString *what = o.local_code ? [NSString stringWithFormat:@"local %d", o.local_code]
                                      : [NSString stringWithFormat:@"HTTP %d code=%d", o.http_status, c.biz_code];
        if (a.auto_retry) {
            YGSTLogf(YGST_LEVEL_WARN, @"autoRetry %d/%d in %ld ms: errId %d", _call.submission,
                     _call.policy.max_auto_retries, a.delay_ms, c.err_id);
        } else {
            YGSTLogf(YGST_LEVEL_WARN, @"retry %d/%d in %ld ms: %@", _call.attempt, _call.policy.max_retries,
                     a.delay_ms, what);
        }
        ygst_classification_free(&c);
        dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)a.delay_ms * (int64_t)NSEC_PER_MSEC), _queue, ^{
            [self attempt];
        });
        return;
    } else {
        YGSTUploadResult *r = [[YGSTUploadResult alloc] init];
        void (^completion)(YGSTUploadResult *) = _completion;
        r.attempts = _call.total_attempts;
        r.replayed = replayed;
        if (a.kind == YGST_ACT_SUCCESS && data) {
            r.success = YES;
            r.body = data;
            r.resultRange = NSMakeRange((NSUInteger)(c.result.p - (const char *)data.bytes), c.result.n);
            r.recordId = YGSTStringFromBuf(&c.record_id);
            r.recordIdJSON = c.has_record_id ? [[NSString alloc] initWithBytes:c.record_id_raw.p
                                                                         length:c.record_id_raw.n
                                                                       encoding:NSUTF8StringEncoding]
                                             : nil;
            r.audioUrl = c.has_audio_url ? YGSTStringFromBuf(&c.audio_url) : nil;
            r.message = @"";
            YGSTLogf(YGST_LEVEL_INFO, @"evaluated %@ recordId %@ in %d attempt(s)%@", _url.path, r.recordId,
                     r.attempts, replayed ? @" (replayed)" : @"");
        } else {
            ygst_buf m;
            r.success = NO;
            r.errId = a.kind == YGST_ACT_SUCCESS ? YGST_LOCAL_PROTOCOL : a.err_id;
            ygst_buf_init(&m);
            ygst_error_message(&c, r.errId, _call.attempt, &m);
            r.message = YGSTStringFromBuf(&m);
            ygst_buf_free(&m);
            r.recordId = @"";
            YGSTLogf(YGST_LEVEL_WARN, @"evaluation failed after %d attempt(s): errId %d %@", r.attempts, r.errId,
                     r.message);
        }
        ygst_classification_free(&c);
        _completion = nil;
        if (completion) completion(r);
    }
}

@end
