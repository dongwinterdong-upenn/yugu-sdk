//
//  YuguClients.m
//  STKouyuEngine 平替层（优谷雅言）的语音合成与报告查询（2.2.0）
//
//  TtsClient：POST /api/v1/tts/generate，ReportClient：GET /api/v1/report/{recordId}。请求体与签名参数，
//  报告路径，绝对音频地址，响应与报告字段，等待 AI 终稿的判断与日志行都在 C 核心 core/ygst_api.c，
//  由 Linux 上的单元测试与集成测试覆盖。请求经评测共用的 NSURLSession，重试判断与错误映射发出
//  （YGSTApiCall），回调在主线程。2.3.0 起 waitForFinalMs 在 reportStatus 为 partial 时同样再查。
//
//  Copyright 2026 优谷雅言 open.shengzhiai.com
//  SPDX-License-Identifier: Apache-2.0
//

#import "include/STKouyuEngine/YuguCompat.h"
#import "YGSTInternal.h"

NSErrorDomain const YuguCompatErrorDomain = @"YuguCompatErrorDomain";
NSString *const YuguCompatHTTPStatusKey = @"YuguCompatHTTPStatusKey";

static const NSTimeInterval kYGSTTtsTimeout = 60;
static const NSTimeInterval kYGSTReportTimeout = 30;

static NSString *const kYGSTTextBlank = @"TtsRequest.text 为空";
static NSString *const kYGSTRecordIdBlank = @"recordId 为空";
static NSString *const kYGSTKeyInvalid = @"idempotencyKey 须为 1 到 200 个可见 ASCII 字符";
static NSString *const kYGSTCredentialsBlank = @"appKey 或 secretKey 为空";

/// Decisions and completions of every call: one serial queue.
static dispatch_queue_t YGSTApiQueue(void) {
    static dispatch_queue_t q;
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        q = dispatch_queue_create("com.shengzhiai.yugu.stcompat.api", DISPATCH_QUEUE_SERIAL);
    });
    return q;
}

/// The retry policy of the evaluations: YuguCompat.maxRetries and totalTimeout.
static ygst_retry_policy YGSTApiPolicy(void) {
    ygst_retry_policy p;
    NSInteger retries = [YGSTSettings shared].maxRetries;
    ygst_retry_policy_default(&p);
    p.max_retries = (int)(retries < 0 ? 0 : (retries > 5 ? 5 : retries));
    p.total_timeout_ms = (int)([YGSTSettings shared].totalTimeout * 1000.0);
    return p;
}

static NSError *YGSTApiError(int code, NSString *message, int httpStatus) {
    NSMutableDictionary *info = [NSMutableDictionary dictionary];
    info[NSLocalizedDescriptionKey] = message.length ? message : YGSTCompatMessage(code);
    if (httpStatus > 0) info[YuguCompatHTTPStatusKey] = @(httpStatus);
    return [NSError errorWithDomain:YuguCompatErrorDomain code:code userInfo:info];
}

static NSString *YGSTStringFromSpan(ygst_span s) {
    NSString *text;
    if (!s.p || !s.n) return @"";
    text = [[NSString alloc] initWithBytes:s.p length:s.n encoding:NSUTF8StringEncoding];
    return text ?: @"";
}

static NSDictionary<NSString *, id> *YGSTDictionaryFromSpan(ygst_span s) {
    id o;
    if (!s.p || !s.n) return nil;
    o = [NSJSONSerialization JSONObjectWithData:[NSData dataWithBytes:s.p length:s.n] options:0 error:NULL];
    return [o isKindOfClass:[NSDictionary class]] ? o : nil;
}

/// The "data" object of a successful call.
static ygst_span YGSTDataOf(YGSTUploadResult *r) {
    ygst_span s;
    s.p = NULL;
    s.n = 0;
    if (r.success && r.body && NSMaxRange(r.resultRange) <= r.body.length) {
        s.p = (const char *)r.body.bytes + r.resultRange.location;
        s.n = r.resultRange.length;
    }
    return s;
}

/// One INFO line per call, WARN for an error; never the secret, signatures, audio or report text.
static void YGSTLogCall(NSString *operation, NSString *recordId, NSString *status, int code, int lookups, int attempts,
                        int64_t started) {
    ygst_buf b;
    ygst_buf_init(&b);
    if (ygst_api_log_line(operation.UTF8String, recordId.UTF8String, status.UTF8String, code, lookups, attempts,
                          (long long)(YGSTMonotonicMs() - started), &b) == 0) {
        YGSTLogEmit(code ? YGST_LEVEL_WARN : YGST_LEVEL_INFO, YGSTStringFromBuf(&b));
    }
    ygst_buf_free(&b);
}

// ------------------------------------------------------------------ credentials

@interface YGSTApiTarget : NSObject
@property (nonatomic, copy) NSString *appKey;
@property (nonatomic, copy) NSString *secretKey;
@property (nonatomic, copy) NSString *baseURL;
@end

@implementation YGSTApiTarget
@end

/// appKey, secretKey and platform address of one call: the engine's at call time, or the client's.
/// nil with *error set when there are none.
static YGSTApiTarget *YGSTResolveTarget(BOOL fromEngine, NSString *appKey, NSString *secretKey, NSString *baseURL,
                                        NSError **error) {
    YGSTApiTarget *t = [[YGSTApiTarget alloc] init];
    NSString *override = [YGSTSettings shared].baseURLOverride;
    if (fromEngine) {
        YGSTEngineCredentials *c = [[YGSTSettings shared] engineCredentials];
        if (!c.appKey.length || !c.secretKey.length) {
            *error = YGSTApiError(YGST_ERRID_NOT_INITIALIZED, YGSTCompatMessage(YGST_ERRID_NOT_INITIALIZED), 0);
            return nil;
        }
        t.appKey = c.appKey;
        t.secretKey = c.secretKey;
        t.baseURL = override.length ? override : (c.baseURL.length ? c.baseURL : @YGST_DEFAULT_BASE_URL);
        return t;
    }
    t.appKey = [appKey stringByTrimmingCharactersInSet:[NSCharacterSet whitespaceAndNewlineCharacterSet]];
    t.secretKey = secretKey;
    if (!t.appKey.length || !t.secretKey.length) {
        *error = YGSTApiError(YGST_LOCAL_INVALID_ARGUMENT, kYGSTCredentialsBlank, 0);
        return nil;
    }
    if (baseURL.length) {
        char base[1024];
        ygst_base_kind kind = ygst_resolve_base_url(baseURL.UTF8String, base, sizeof base);
        if (kind == YGST_BASE_ERR_SCHEME || kind == YGST_BASE_ERR_HOST) {
            *error = YGSTApiError(YGST_LOCAL_INVALID_ARGUMENT,
                                  [NSString stringWithFormat:@"baseURL 须为 http 或 https 地址: %@", baseURL], 0);
            return nil;
        }
        t.baseURL = [NSString stringWithUTF8String:base] ?: @YGST_DEFAULT_BASE_URL;
    } else {
        t.baseURL = override.length ? override : @YGST_DEFAULT_BASE_URL;
    }
    return t;
}

static NSURL *YGSTApiURL(NSString *baseURL, const char *path) {
    ygst_buf b;
    NSURL *url = nil;
    ygst_buf_init(&b);
    if (ygst_api_url(baseURL.UTF8String, path, &b) == 0) url = [NSURL URLWithString:YGSTStringFromBuf(&b)];
    ygst_buf_free(&b);
    return url;
}

// ------------------------------------------------------------------ TTS

@implementation YuguTtsRequest

- (instancetype)init {
    if ((self = [super init])) _text = @"";
    return self;
}

- (instancetype)initWithText:(NSString *)text {
    if ((self = [super init])) _text = [text copy] ?: @"";
    return self;
}

@end

@implementation YuguTtsOptions

- (instancetype)init {
    if ((self = [super init])) _timeout = kYGSTTtsTimeout;
    return self;
}

@end

@interface YuguTtsResult ()
@property (nonatomic, readwrite, copy, nullable) NSString *audioUrl;
@property (nonatomic, readwrite, copy, nullable) NSString *absoluteAudioUrl;
@property (nonatomic, readwrite) NSTimeInterval duration;
@property (nonatomic, readwrite, copy, nullable) NSString *format;
@property (nonatomic, readwrite, copy) NSString *rawJSON;
+ (instancetype)ygst_resultWithUpload:(YGSTUploadResult *)upload baseURL:(NSString *)baseURL;
@end

@implementation YuguTtsResult

- (instancetype)init {
    if ((self = [super init])) _rawJSON = @"";
    return self;
}

/// The fields of a successful answer (ygst_tts_parse); the audio URL resolved against the base.
+ (instancetype)ygst_resultWithUpload:(YGSTUploadResult *)upload baseURL:(NSString *)baseURL {
    YuguTtsResult *r = [[YuguTtsResult alloc] init];
    ygst_tts_answer a;
    ygst_buf absolute;
    ygst_tts_answer_init(&a);
    ygst_buf_init(&absolute);
    ygst_tts_parse(YGSTDataOf(upload), &a);
    ygst_tts_absolute_url(baseURL.UTF8String, ygst_buf_cstr(&a.audio_url), &absolute);
    r.audioUrl = a.audio_url.len ? YGSTStringFromBuf(&a.audio_url) : nil;
    r.absoluteAudioUrl = absolute.len ? YGSTStringFromBuf(&absolute) : nil;
    r.duration = a.has_duration ? a.duration : 0;
    r.format = a.format.len ? YGSTStringFromBuf(&a.format) : nil;
    r.rawJSON = upload.body ? ([[NSString alloc] initWithData:upload.body encoding:NSUTF8StringEncoding] ?: @"") : @"";
    ygst_tts_answer_free(&a);
    ygst_buf_free(&absolute);
    return r;
}

@end

@implementation YuguTtsClient {
    BOOL _fromEngine;
    NSString *_appKey;
    NSString *_secretKey;
    NSString *_baseURL;
}

+ (instancetype)clientFromEngine {
    return [[self alloc] init];
}

/// The engine's credentials, like clientFromEngine.
- (instancetype)init {
    if ((self = [super init])) _fromEngine = YES;
    return self;
}

- (instancetype)initWithAppKey:(NSString *)appKey secretKey:(NSString *)secretKey {
    return [self initWithAppKey:appKey secretKey:secretKey baseURL:nil];
}

- (instancetype)initWithAppKey:(NSString *)appKey secretKey:(NSString *)secretKey baseURL:(NSString *)baseURL {
    if ((self = [super init])) {
        _appKey = [appKey copy] ?: @"";
        _secretKey = [secretKey copy] ?: @"";
        _baseURL = [baseURL copy];
    }
    return self;
}

- (void)tts:(YuguTtsRequest *)request
       options:(YuguTtsOptions *)options
    completion:(void (^)(YuguTtsResult *, NSError *))completion {
    void (^done)(YuguTtsResult *, NSError *) = [completion copy];
    int64_t started = YGSTMonotonicMs();
    NSError *error = nil;
    YGSTApiTarget *target = YGSTResolveTarget(_fromEngine, _appKey, _secretKey, _baseURL, &error);
    NSString *key = options.idempotencyKey.length ? [options.idempotencyKey copy] : YGSTRandomHex(16);
    NSTimeInterval timeout = options.timeout > 0 ? options.timeout : kYGSTTtsTimeout;
    ygst_tts_request r;
    ygst_buf json;
    ygst_fields sign;
    char signature[45];
    NSData *body = nil;
    NSURL *url = nil;
    int rc;
    ygst_tts_request_init(&r);
    ygst_buf_init(&json);
    ygst_fields_init(&sign);
    r.text = request.text.UTF8String;
    r.language = request.language.UTF8String;
    r.voice = request.voice.UTF8String;
    r.format = request.format.UTF8String;
    r.has_speed = request.speed != nil;
    r.speed = request.speed.longLongValue;
    r.has_pitch = request.pitch != nil;
    r.pitch = request.pitch.longLongValue;
    r.has_volume = request.volume != nil;
    r.volume = request.volume.longLongValue;
    r.has_sample_rate = request.sampleRate != nil;
    r.sample_rate = request.sampleRate.longLongValue;
    r.emotion = request.emotion.UTF8String;
    r.style = request.style.UTF8String;
    r.pinyin = request.pinyin.UTF8String;
    r.genre = request.genre.UTF8String;
    rc = target ? ygst_tts_body(&r, &json, &sign) : 0;
    if (target && rc != 0) {
        error = YGSTApiError(YGST_LOCAL_INVALID_ARGUMENT, rc == YGST_LOCAL_INVALID_ARGUMENT ? kYGSTTextBlank
                                                                                             : @"request body: out of memory",
                             0);
    } else if (target && !ygst_idempotency_key_ok(key.UTF8String)) {
        error = YGSTApiError(YGST_LOCAL_INVALID_ARGUMENT, kYGSTKeyInvalid, 0);
    } else if (target) {
        ygst_sign(&sign, target.secretKey.UTF8String, signature);
        url = YGSTApiURL(target.baseURL, YGST_TTS_PATH);
        body = [NSData dataWithBytes:json.data length:json.len];
        if (!url) {
            error = YGSTApiError(YGST_LOCAL_INVALID_ARGUMENT,
                                 [NSString stringWithFormat:@"invalid platform URL %@", target.baseURL], 0);
        }
    }
    ygst_buf_free(&json);
    ygst_fields_free(&sign);
    if (error) {
        YGSTLogCall(@"tts", nil, nil, (int)error.code, -1, 0, started);
        dispatch_async(dispatch_get_main_queue(), ^{
            if (done) done(nil, error);
        });
        return;
    }
    {
        YGSTApiCall *call = [[YGSTApiCall alloc] initWithURLSession:YGSTSharedURLSession() queue:YGSTApiQueue()];
        ygst_retry_policy policy = YGSTApiPolicy();
        NSString *base = target.baseURL;
        NSString *sig = [NSString stringWithUTF8String:signature] ?: @"";
        [call startWithMethod:@"POST"
                          url:url
                       appKey:target.appKey
                    signature:sig
               idempotencyKey:key
                         body:body
                  contentType:@"application/json; charset=utf-8"
               attemptTimeout:timeout
                       policy:&policy
                    operation:@"tts"
                   completion:^(YGSTUploadResult *upload) {
                       YuguTtsResult *result = nil;
                       NSError *failure = nil;
                       if (upload.success) {
                           result = [YuguTtsResult ygst_resultWithUpload:upload baseURL:base];
                       } else {
                           failure = YGSTApiError(upload.errId, upload.message, upload.httpStatus);
                       }
                       YGSTLogCall(@"tts", nil, @"ok", upload.success ? 0 : upload.errId, -1, upload.attempts, started);
                       dispatch_async(dispatch_get_main_queue(), ^{
                           if (done) done(result, failure);
                       });
                   }];
    }
}

@end

// ------------------------------------------------------------------ report

@implementation YuguReportOptions

- (instancetype)init {
    if ((self = [super init])) _timeout = kYGSTReportTimeout;
    return self;
}

@end

@interface YuguReportResult ()
@property (nonatomic, readwrite, copy) NSString *recordId;
@property (nonatomic, readwrite, copy, nullable) NSString *reportStatus;
@property (nonatomic, readwrite, getter=isFinal) BOOL final;
@property (nonatomic, readwrite, copy, nullable) NSString *summary;
@property (nonatomic, readwrite, copy) NSArray<NSString *> *suggestions;
@property (nonatomic, readwrite, strong, nullable) NSNumber *overall;
@property (nonatomic, readwrite, copy, nullable) NSDictionary<NSString *, id> *report;
@property (nonatomic, readwrite, copy, nullable) NSDictionary<NSString *, id> *score;
@property (nonatomic, readwrite, copy, nullable) NSDictionary<NSString *, id> *timing;
@property (nonatomic, readwrite, copy) NSString *dataJSON;
+ (instancetype)ygst_resultWithReport:(const ygst_report *)rep data:(ygst_span)data requested:(NSString *)requested;
@end

@implementation YuguReportResult

- (instancetype)init {
    if ((self = [super init])) {
        _recordId = @"";
        _suggestions = @[];
        _dataJSON = @"";
    }
    return self;
}

/// The fields of an answer (ygst_report_parse); recordId falls back to the one asked for.
+ (instancetype)ygst_resultWithReport:(const ygst_report *)rep data:(ygst_span)data requested:(NSString *)requested {
    YuguReportResult *r = [[YuguReportResult alloc] init];
    NSMutableArray<NSString *> *suggestions = [NSMutableArray array];
    ygst_buf s;
    size_t pos = 0;
    r.recordId = rep->record_id.len ? YGSTStringFromBuf(&rep->record_id) : requested;
    r.reportStatus = rep->report_status.len ? YGSTStringFromBuf(&rep->report_status) : nil;
    r.final = ygst_report_is_final(rep) != 0;
    r.summary = rep->has_summary ? YGSTStringFromBuf(&rep->summary) : nil;
    ygst_buf_init(&s);
    while (ygst_report_next_suggestion(rep->suggestions, &pos, &s) == 1) [suggestions addObject:YGSTStringFromBuf(&s)];
    ygst_buf_free(&s);
    r.suggestions = suggestions;
    r.overall = rep->has_overall ? @(rep->overall) : nil;
    r.report = YGSTDictionaryFromSpan(rep->report);
    r.score = YGSTDictionaryFromSpan(rep->score);
    r.timing = YGSTDictionaryFromSpan(rep->timing);
    r.dataJSON = YGSTStringFromSpan(data);
    return r;
}

@end

/// One getReport call: a lookup, and with waitForFinalMs one per second while the report is pending or
/// (2.3.0) partial.
/// ygst_report_after_lookup decides, as the Android shim: the first lookup's error ends the call with
/// that error; a later failed lookup ends it with the latest answer and one WARN line. Runs on the
/// API queue.
@interface YGSTReportLookup : NSObject
@property (nonatomic, strong) NSURL *url;
@property (nonatomic, copy) NSString *appKey;
@property (nonatomic, copy) NSString *signature;
@property (nonatomic, copy) NSString *recordId;
@property (nonatomic) long long waitMs;
@property (nonatomic) NSTimeInterval timeout;
@property (nonatomic) int64_t started;
@property (nonatomic) int lookups;
@property (nonatomic) int attempts;
/// The latest answer of this call, nil before the first one.
@property (nonatomic, strong, nullable) YuguReportResult *latest;
@property (nonatomic, copy, nullable) void (^completion)(YuguReportResult *_Nullable result, NSError *_Nullable error);
- (void)next;
@end

@implementation YGSTReportLookup

- (void)next {
    YGSTApiCall *call = [[YGSTApiCall alloc] initWithURLSession:YGSTSharedURLSession() queue:YGSTApiQueue()];
    ygst_retry_policy policy = YGSTApiPolicy();
    self.lookups += 1;
    [call startWithMethod:@"GET"
                      url:self.url
                   appKey:self.appKey
                signature:self.signature
           idempotencyKey:nil
                     body:nil
              contentType:nil
           attemptTimeout:self.timeout
                   policy:&policy
                operation:@"getReport"
               completion:^(YGSTUploadResult *upload) {
                   [self answered:upload];
               }];
}

- (void)answered:(YGSTUploadResult *)upload {
    ygst_report rep;
    ygst_span data = YGSTDataOf(upload);
    ygst_rp_action a;
    self.attempts += upload.attempts;
    if (!upload.success) {
        a = ygst_report_after_lookup(self.waitMs, self.started, YGSTMonotonicMs(), 0, 0, self.latest != nil);
        if (a.kind == YGST_RP_FAIL) {
            [self finish:nil error:YGSTApiError(upload.errId, upload.message, upload.httpStatus)];
            return;
        }
        {
            // a later lookup failed while waiting: the latest answer, one WARN line
            ygst_buf b;
            ygst_buf_init(&b);
            if (ygst_report_lookup_failed_line(self.recordId.UTF8String, upload.errId, self.lookups, &b) == 0) {
                YGSTLogEmit(YGST_LEVEL_WARN, YGSTStringFromBuf(&b));
            }
            ygst_buf_free(&b);
        }
        [self finish:self.latest error:nil];
        return;
    }
    ygst_report_init(&rep);
    ygst_report_parse(data, &rep);
    self.latest = [YuguReportResult ygst_resultWithReport:&rep data:data requested:self.recordId];
    // 2.3.0: pending and partial (the AI summary and suggestions without the dimension comments) wait
    a = ygst_report_after_lookup(self.waitMs, self.started, YGSTMonotonicMs(), 1, ygst_report_is_waiting(&rep), 1);
    ygst_report_free(&rep);
    if (a.kind == YGST_RP_AGAIN) {
        YGSTLogf(YGST_LEVEL_DEBUG, @"getReport %@: %@, lookup %d in %ld ms", self.recordId,
                 YGSTNonEmpty(self.latest.reportStatus), self.lookups + 1, a.delay_ms);
        dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)a.delay_ms * (int64_t)NSEC_PER_MSEC), YGSTApiQueue(), ^{
            [self next];
        });
        return;
    }
    [self finish:self.latest error:nil];
}

- (void)finish:(YuguReportResult *)result error:(NSError *)error {
    void (^done)(YuguReportResult *, NSError *) = self.completion;
    self.completion = nil;
    YGSTLogCall(@"getReport", self.recordId, result.reportStatus, error ? (int)error.code : 0, self.lookups,
                self.attempts, self.started);
    dispatch_async(dispatch_get_main_queue(), ^{
        if (done) done(result, error);
    });
}

@end

@implementation YuguReportClient {
    BOOL _fromEngine;
    NSString *_appKey;
    NSString *_secretKey;
    NSString *_baseURL;
}

+ (instancetype)clientFromEngine {
    return [[self alloc] init];
}

/// The engine's credentials, like clientFromEngine.
- (instancetype)init {
    if ((self = [super init])) _fromEngine = YES;
    return self;
}

- (instancetype)initWithAppKey:(NSString *)appKey secretKey:(NSString *)secretKey {
    return [self initWithAppKey:appKey secretKey:secretKey baseURL:nil];
}

- (instancetype)initWithAppKey:(NSString *)appKey secretKey:(NSString *)secretKey baseURL:(NSString *)baseURL {
    if ((self = [super init])) {
        _appKey = [appKey copy] ?: @"";
        _secretKey = [secretKey copy] ?: @"";
        _baseURL = [baseURL copy];
    }
    return self;
}

- (void)getReport:(NSString *)recordId
          options:(YuguReportOptions *)options
       completion:(void (^)(YuguReportResult *, NSError *))completion {
    void (^done)(YuguReportResult *, NSError *) = [completion copy];
    int64_t started = YGSTMonotonicMs();
    NSError *error = nil;
    YGSTApiTarget *target = YGSTResolveTarget(_fromEngine, _appKey, _secretKey, _baseURL, &error);
    ygst_buf path, trimmed;
    ygst_fields none;
    char signature[45];
    NSString *requested;
    NSURL *url = nil;
    YGSTReportLookup *lookup;
    ygst_buf_init(&path);
    ygst_buf_init(&trimmed);
    ygst_fields_init(&none);
    ygst_buf_append_trimmed(&trimmed, recordId.UTF8String);
    requested = YGSTStringFromBuf(&trimmed);
    if (target && ygst_report_path(recordId.UTF8String, &path) != 0) {
        error = YGSTApiError(YGST_LOCAL_INVALID_ARGUMENT, kYGSTRecordIdBlank, 0);
    } else if (target) {
        ygst_sign(&none, target.secretKey.UTF8String, signature); /* the signed set of the report, CONTRACT 2.3 */
        url = YGSTApiURL(target.baseURL, ygst_buf_cstr(&path));
        if (!url) {
            error = YGSTApiError(YGST_LOCAL_INVALID_ARGUMENT,
                                 [NSString stringWithFormat:@"invalid platform URL %@", target.baseURL], 0);
        }
    }
    ygst_buf_free(&path);
    ygst_buf_free(&trimmed);
    ygst_fields_free(&none);
    if (error) {
        YGSTLogCall(@"getReport", requested, nil, (int)error.code, 0, 0, started);
        dispatch_async(dispatch_get_main_queue(), ^{
            if (done) done(nil, error);
        });
        return;
    }
    lookup = [[YGSTReportLookup alloc] init];
    lookup.url = url;
    lookup.appKey = target.appKey;
    lookup.signature = [NSString stringWithUTF8String:signature] ?: @"";
    lookup.recordId = requested;
    lookup.waitMs = options.waitForFinalMs > 0 ? (long long)options.waitForFinalMs : 0;
    lookup.timeout = options.timeout > 0 ? options.timeout : kYGSTReportTimeout;
    lookup.started = started;
    lookup.completion = done;
    dispatch_async(YGSTApiQueue(), ^{
        [lookup next];
    });
}

@end
