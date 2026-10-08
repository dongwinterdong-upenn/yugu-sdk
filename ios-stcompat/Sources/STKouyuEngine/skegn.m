//
//  skegn.m
//  STKouyuEngine 平替层（优谷雅言）的 C 接口实现，替换 libskegn.a。
//
//  skegn_new 读取 cfg JSON 的 appKey、secretKey 与 cloud.server；skegn_start 读取 param JSON
//  的 app、audio 与 request；feed 收集音频；skegn_stop 后用与 KYTestEngine 相同的请求、重试与
//  结果 JSON 评测，结果经 callback(usrdata, tokenId, SKEGN_MESSAGE_TYPE_JSON, json, size)
//  在内部队列回调。
//
//  Copyright 2026 优谷雅言 open.shengzhiai.com
//  SPDX-License-Identifier: Apache-2.0
//

#import "include/STKouyuEngine/skegn.h"
#import "YGSTInternal.h"

#include <stdlib.h>
#include <string.h>

// Error numbers in the order of the public skegn_errno.h.
enum {
    YGST_SGN_NONE = 0,
    YGST_SGN_BADCFG = 1,
    YGST_SGN_OUT_OF_MEMORY = 2,
    YGST_SGN_PARAMETER_WRONG = 4,
    YGST_SGN_FUNCTION_FEED_BEFORE_START = 21,
    YGST_SGN_FUNCTION_WAIT_FOR_CALLBACK = 22,
    YGST_SGN_FUNCTION_START_AND_START = 23,
    YGST_SGN_FUNCTION_STOP_AND_STOP = 24,
    YGST_SGN_FUNCTION_STOP_BEFORE_START = 25,
    YGST_SGN_BAD_PRAMA = 26,
    YGST_SGN_ENGINE_IS_NULL = 31,
    YGST_SGN_ID_IS_NULL = 32,
    YGST_SGN_CALLBACK_IS_NULL = 33,
};

static volatile int g_skegn_last_error = 0;

static int YGSTSkegnFail(int err) {
    g_skegn_last_error = err;
    return -1;
}

static NSString *YGSTSkegnProvisionJSON(void) {
    return @"{\"provision\":\"cloud\",\"message\":\"cloud mode, no provision file needed\"}";
}

/// Request values as form text: strings as-is, JSON booleans as 1 or 0, numbers in plain form.
static NSString *YGSTSkegnFieldText(id value) {
    if ([value isKindOfClass:[NSNumber class]] &&
        CFGetTypeID((__bridge CFTypeRef)value) == CFBooleanGetTypeID()) {
        return [(NSNumber *)value boolValue] ? @"1" : @"0";
    }
    return YGSTValueText(value);
}

@interface YGSTCSession : NSObject {
@public
    ygst_fields _fields;
    ygst_retry_policy _policy;
}
@property (nonatomic, copy) NSString *tokenId;
@property (nonatomic, copy) NSString *coreType;
@property (nonatomic) ygst_ct_status coreTypeStatus;
@property (nonatomic, copy) NSString *userId;
@property (nonatomic, copy) NSString *refText;
@property (nonatomic, copy) NSString *audioType;
@property (nonatomic) int sampleRate;
@property (nonatomic) int channels;
@property (nonatomic) int sampleBytes;
@property (nonatomic) BOOL getParam;
@property (nonatomic) BOOL stopped;
@property (nonatomic) BOOL finished;
@property (nonatomic) BOOL overflow;
@property (atomic) BOOL cancelled;
@property (nonatomic, strong) NSMutableData *audio;
@property (nonatomic, strong) YGSTUploader *uploader;
@property (nonatomic) skegn_callback callback;
@property (nonatomic) const void *usrdata;
@end

@implementation YGSTCSession
- (instancetype)init {
    if ((self = [super init])) {
        ygst_fields_init(&_fields);
        ygst_retry_policy_default(&_policy);
        _audio = [NSMutableData data];
    }
    return self;
}
- (void)dealloc {
    ygst_fields_free(&_fields);
}
@end

@interface YGSTCEngine : NSObject
@property (nonatomic, copy) NSString *appKey;
@property (nonatomic, copy) NSString *secret;
@property (nonatomic, copy) NSString *baseURL;
@property (nonatomic) double serverTimeout;
@property (nonatomic) double connectTimeout;
@property (nonatomic, strong) dispatch_queue_t queue;
@property (nonatomic, strong) YGSTCSession *session;
@end

@implementation YGSTCEngine
@end

struct skegn {
    uint32_t magic;
    void *engine; // CFBridgingRetain(YGSTCEngine)
};

#define YGST_SKEGN_MAGIC 0x59475354u

static char kYGSTSkegnQueueKey;

/// dispatch_sync that runs inline when already on the engine queue (callbacks may call back in).
static void YGSTSkegnSync(YGSTCEngine *e, dispatch_block_t block) {
    if (dispatch_get_specific(&kYGSTSkegnQueueKey) == (__bridge void *)e) {
        block();
    } else {
        dispatch_sync(e.queue, block);
    }
}

static YGSTCEngine *YGSTEngineFromHandle(struct skegn *h) {
    if (!h || h->magic != YGST_SKEGN_MAGIC || !h->engine) return nil;
    return (__bridge YGSTCEngine *)h->engine;
}

static NSDictionary *YGSTParseJSONObject(const char *text) {
    NSData *data;
    id obj;
    if (!text) return nil;
    data = [NSData dataWithBytes:text length:strlen(text)];
    obj = [NSJSONSerialization JSONObjectWithData:data options:0 error:NULL];
    return [obj isKindOfClass:[NSDictionary class]] ? obj : nil;
}

static NSString *YGSTDictString(NSDictionary *d, NSString *key) {
    id v = [d isKindOfClass:[NSDictionary class]] ? d[key] : nil;
    if ([v isKindOfClass:[NSString class]]) return v;
    if ([v isKindOfClass:[NSNumber class]]) return [(NSNumber *)v stringValue];
    return nil;
}

static double YGSTDictDouble(NSDictionary *d, NSString *key, double fallback) {
    id v = [d isKindOfClass:[NSDictionary class]] ? d[key] : nil;
    if ([v isKindOfClass:[NSNumber class]] || [v isKindOfClass:[NSString class]]) {
        double x = [v doubleValue];
        return x > 0 ? x : fallback;
    }
    return fallback;
}

static void YGSTSkegnDeliver(YGSTCSession *s, NSString *json) {
    NSData *bytes;
    if (s.cancelled || !s.callback) return;
    s.finished = YES;
    bytes = [json dataUsingEncoding:NSUTF8StringEncoding];
    {
        NSMutableData *z = [bytes mutableCopy];
        [z appendBytes:"" length:1];
        s.callback(s.usrdata, s.tokenId.UTF8String, SKEGN_MESSAGE_TYPE_JSON, z.bytes, (int)bytes.length);
    }
}

struct skegn *skegn_new(const char *cfg) {
    NSDictionary *c = YGSTParseJSONObject(cfg);
    NSDictionary *cloud = [c[@"cloud"] isKindOfClass:[NSDictionary class]] ? c[@"cloud"] : nil;
    NSDictionary *log = [c[@"sdkLog"] isKindOfClass:[NSDictionary class]] ? c[@"sdkLog"] : nil;
    NSString *appKey = YGSTDictString(c, @"appKey");
    NSString *secret = YGSTDictString(c, @"secretKey");
    NSString *server = YGSTDictString(cloud, @"server") ?: YGSTDictString(c, @"server");
    YGSTCEngine *e;
    struct skegn *h;
    char base[1024];
    if (!c) {
        YGSTSkegnFail(YGST_SGN_BADCFG);
        YGSTLogf(YGST_LEVEL_ERROR, @"skegn_new: cfg is not a JSON object");
        return NULL;
    }
    if (log) {
        YGSTLogConfigure([log[@"enable"] boolValue], log[@"level"] ? [log[@"level"] integerValue] : YGST_LEVEL_WARN,
                         YGSTDictString(log, @"output"), YES);
    }
    if (!appKey.length || !secret.length) {
        YGSTSkegnFail(YGST_SGN_BADCFG);
        YGSTLogf(YGST_LEVEL_ERROR, @"skegn_new: appKey or secretKey is empty");
        return NULL;
    }
    if (c[@"native"] || c[@"native_cn"]) YGSTLogf(YGST_LEVEL_WARN, @"skegn_new: native resources ignored, cloud only");
    ygst_resolve_base_url(server.UTF8String, base, sizeof base);
    e = [[YGSTCEngine alloc] init];
    e.appKey = appKey;
    e.secret = secret;
    e.baseURL = [NSString stringWithUTF8String:base] ?: @YGST_DEFAULT_BASE_URL;
    e.serverTimeout = YGSTDictDouble(cloud, @"serverTimeout", 60);
    e.connectTimeout = YGSTDictDouble(cloud, @"connectTimeout", 20);
    e.queue = dispatch_queue_create("com.shengzhiai.yugu.stcompat.skegn", DISPATCH_QUEUE_SERIAL);
    dispatch_queue_set_specific(e.queue, &kYGSTSkegnQueueKey, (__bridge void *)e, NULL);
    h = (struct skegn *)calloc(1, sizeof *h);
    if (!h) {
        YGSTSkegnFail(YGST_SGN_OUT_OF_MEMORY);
        return NULL;
    }
    h->magic = YGST_SKEGN_MAGIC;
    h->engine = (void *)CFBridgingRetain(e);
    [YGSTSettings shared].configuredBaseURL = e.baseURL;
    YGSTLogf(YGST_LEVEL_INFO, @"skegn_new: appKey %@, platform %@", YGSTMaskedAppKey(appKey), e.baseURL);
    g_skegn_last_error = YGST_SGN_NONE;
    return h;
}

int skegn_delete(struct skegn *engine) {
    YGSTCEngine *e = YGSTEngineFromHandle(engine);
    if (!e) return YGSTSkegnFail(YGST_SGN_ENGINE_IS_NULL);
    skegn_cancel(engine);
    engine->magic = 0;
    CFBridgingRelease(engine->engine);
    engine->engine = NULL;
    free(engine);
    return 0;
}

int skegn_start(struct skegn *engine, const char *param, char id[64], skegn_callback callback, const void *usrdata) {
    YGSTCEngine *e = YGSTEngineFromHandle(engine);
    NSDictionary *p, *request, *audio, *app;
    YGSTCSession *s;
    __block int rc = 0;
    NSString *rejection = nil;
    char coreType[128];
    if (!e) return YGSTSkegnFail(YGST_SGN_ENGINE_IS_NULL);
    if (!id) return YGSTSkegnFail(YGST_SGN_ID_IS_NULL);
    if (!callback) return YGSTSkegnFail(YGST_SGN_CALLBACK_IS_NULL);
    p = YGSTParseJSONObject(param);
    request = [p[@"request"] isKindOfClass:[NSDictionary class]] ? p[@"request"] : nil;
    if (!request) {
        YGSTLogf(YGST_LEVEL_ERROR, @"skegn_start: param has no request object");
        return YGSTSkegnFail(YGST_SGN_BAD_PRAMA);
    }
    audio = [p[@"audio"] isKindOfClass:[NSDictionary class]] ? p[@"audio"] : nil;
    app = [p[@"app"] isKindOfClass:[NSDictionary class]] ? p[@"app"] : nil;

    s = [[YGSTCSession alloc] init];
    s.tokenId = YGSTNewTokenId();
    s.coreTypeStatus = ygst_coretype_resolve(YGSTDictString(request, @"coreType").UTF8String, 0, coreType, sizeof coreType);
    if (!YGSTDictString(request, @"coreType").length) s.coreTypeStatus = YGST_CT_UNKNOWN;
    s.coreType = [NSString stringWithUTF8String:coreType] ?: @"";
    s.userId = YGSTDictString(app, @"userId") ?: @"";
    s.refText = YGSTDictString(request, @"refText") ?: @"";
    s.audioType = YGSTDictString(audio, @"audioType") ?: @"wav";
    s.sampleRate = (int)YGSTDictDouble(audio, @"sampleRate", 16000);
    s.channels = (int)YGSTDictDouble(audio, @"channel", 1);
    s.sampleBytes = (int)YGSTDictDouble(audio, @"sampleBytes", 2);
    s.getParam = [request[@"getParam"] boolValue];
    s.callback = callback;
    s.usrdata = usrdata;
    for (NSString *key in request) {
        NSString *value;
        if (![key isKindOfClass:[NSString class]] || [key isEqualToString:@"coreType"] ||
            [key isEqualToString:@"tokenId"] || [key isEqualToString:@"getParam"]) {
            continue;
        }
        value = YGSTSkegnFieldText(request[key]);
        if (value && ygst_field_name_ok(key.UTF8String)) ygst_fields_set(&s->_fields, key.UTF8String, value.UTF8String);
    }
    // DESIGN 6 result-shape alignment: para.* always asks for word details
    ygst_fields_apply_coretype_rules(&s->_fields, s.coreType.UTF8String);
    if ([request[@"autoRetry"] boolValue]) {
        ygst_fields_remove(&s->_fields, "autoRetry");
        s->_policy.auto_retry = 1;
    }

    strncpy(id, s.tokenId.UTF8String, 63);
    id[63] = 0;

    {
        int invalid = ygst_validate_request(s.coreTypeStatus, s.coreType.UTF8String, s.refText.UTF8String,
                                            YGSTDictString(request, @"refPinyin").UTF8String);
        if (invalid) rejection = YGSTErrorJSON(s.tokenId, invalid, YGSTCompatMessage(invalid), e.appKey);
    }

    YGSTSkegnSync(e, ^{
        YGSTCSession *old = e.session;
        if (old && !old.stopped && !old.finished) {
            rc = YGSTSkegnFail(YGST_SGN_FUNCTION_START_AND_START);
            return;
        }
        if (old && !old.finished) {
            YGSTLogf(YGST_LEVEL_WARN, @"skegn_start: previous session %@ still waiting for its result, cancelled",
                     old.tokenId);
            old.cancelled = YES;
            [old.uploader cancel];
        }
        e.session = s;
        if (rejection) {
            s.stopped = YES; /* answered locally, no network I/O */
            dispatch_async(e.queue, ^{
                YGSTSkegnDeliver(s, rejection);
            });
        }
    });
    if (rc) return rc;
    g_skegn_last_error = YGST_SGN_NONE;
    return 0;
}

int skegn_feed(struct skegn *engine, const void *data, int size) {
    YGSTCEngine *e = YGSTEngineFromHandle(engine);
    __block int rc = 0;
    NSData *chunk;
    if (!e) return YGSTSkegnFail(YGST_SGN_ENGINE_IS_NULL);
    if (!data || size < 0) return YGSTSkegnFail(YGST_SGN_PARAMETER_WRONG);
    chunk = [NSData dataWithBytes:data length:(NSUInteger)size];
    YGSTSkegnSync(e, ^{
        YGSTCSession *s = e.session;
        if (!s || s.stopped || s.finished) {
            rc = YGSTSkegnFail(YGST_SGN_FUNCTION_FEED_BEFORE_START);
            return;
        }
        if (s.audio.length + chunk.length > YGST_MAX_AUDIO_BYTES) {
            s.overflow = YES;
            return;
        }
        [s.audio appendData:chunk];
    });
    return rc;
}

int skegn_stop(struct skegn *engine) {
    YGSTCEngine *e = YGSTEngineFromHandle(engine);
    __block int rc = 0;
    __block YGSTCSession *s = nil;
    if (!e) return YGSTSkegnFail(YGST_SGN_ENGINE_IS_NULL);
    YGSTSkegnSync(e, ^{
        s = e.session;
        if (!s) {
            rc = YGSTSkegnFail(YGST_SGN_FUNCTION_STOP_BEFORE_START);
        } else if (s.stopped || s.finished) {
            rc = YGSTSkegnFail(YGST_SGN_FUNCTION_STOP_AND_STOP);
        } else {
            s.stopped = YES;
        }
    });
    if (rc) return rc;
    dispatch_async(e.queue, ^{
        NSData *upload;
        NSString *ext = @"wav";
        NSString *error = nil;
        YGSTRequest *req;
        YGSTUploader *uploader;
        int check;
        if (s.cancelled) return;
        if (ygst_audio_type_is_pcm(s.audioType.UTF8String) && !ygst_is_riff_wave(s.audio.bytes, s.audio.length)) {
            check = ygst_audio_check_pcm(s.audio.length, s.sampleRate, s.channels, s.sampleBytes * 8);
            upload = YGSTWavFromPCM(s.audio, s.sampleRate, s.channels, s.sampleBytes * 8);
        } else {
            ext = [NSString stringWithUTF8String:ygst_audio_ext(s.audioType.UTF8String)];
            upload = [s.audio copy];
            check = ygst_audio_check_upload(upload.bytes, upload.length);
        }
        if (s.overflow) check = YGST_ERRID_AUDIO_TOO_LARGE;
        if (check) {
            YGSTSkegnDeliver(s, YGSTErrorJSON(s.tokenId, check, YGSTCompatMessage(check), e.appKey));
            return;
        }
        req = [YGSTRequest requestWithBaseURL:([YGSTSettings shared].baseURLOverride ?: e.baseURL)
                                     coreType:s.coreType
                                       fields:&s->_fields
                                       secret:e.secret
                                        audio:upload
                                    extension:ext
                                        error:&error];
        if (!req) {
            YGSTSkegnDeliver(s, YGSTErrorJSON(s.tokenId, 90010, error, e.appKey));
            return;
        }
        {
            ygst_retry_policy policy = s->_policy;
            NSInteger retries = [YGSTSettings shared].maxRetries;
            policy.max_retries = (int)(retries < 0 ? 0 : (retries > 5 ? 5 : retries));
            policy.total_timeout_ms = (int)([YGSTSettings shared].totalTimeout * 1000.0);
            uploader = [[YGSTUploader alloc] initWithURLSession:YGSTSharedURLSession() queue:e.queue];
            s.uploader = uploader;
            [uploader startWithURL:req.url
                            appKey:e.appKey
                         signature:req.signature
                    idempotencyKey:s.tokenId
                              body:req.body
                       contentType:req.contentType
                    attemptTimeout:MAX(e.serverTimeout, e.connectTimeout)
                            policy:&policy
                        completion:^(YGSTUploadResult *result) {
                            NSString *json = nil;
                            s.uploader = nil;
                            if (result.success) {
                                NSString *params = nil;
                                if (s.getParam) {
                                    ygst_buf b;
                                    ygst_buf_init(&b);
                                    ygst_params_json(&b, e.appKey.UTF8String, s.userId.UTF8String,
                                                     YGSTNowMs() / 1000, s.audioType.UTF8String, s.sampleRate,
                                                     s.channels, s.sampleBytes,
                                                     s.coreType.UTF8String, s.tokenId.UTF8String, &s->_fields);
                                    params = YGSTStringFromBuf(&b);
                                    ygst_buf_free(&b);
                                }
                                json = YGSTResultJSON(result, s.tokenId, e.appKey, s.userId, s.refText, params);
                            }
                            if (!json) json = YGSTErrorJSON(s.tokenId, result.success ? YGST_LOCAL_PROTOCOL : result.errId,
                                                            result.message, e.appKey);
                            YGSTSkegnDeliver(s, json);
                        }];
        }
    });
    g_skegn_last_error = YGST_SGN_NONE;
    return 0;
}

int skegn_cancel(struct skegn *engine) {
    YGSTCEngine *e = YGSTEngineFromHandle(engine);
    if (!e) return YGSTSkegnFail(YGST_SGN_ENGINE_IS_NULL);
    YGSTSkegnSync(e, ^{
        YGSTCSession *s = e.session;
        if (s) {
            s.cancelled = YES;
            [s.uploader cancel];
            s.uploader = nil;
            e.session = nil;
        }
    });
    return 0;
}

int skegn_get_device_id(char device_id[64]) {
    static NSString *const key = @"com.shengzhiai.yugu.stcompat.deviceId";
    NSUserDefaults *defaults = [NSUserDefaults standardUserDefaults];
    NSString *value;
    if (!device_id) return -1;
    value = [defaults stringForKey:key];
    if (!value.length) {
        value = YGSTRandomHex(16);
        [defaults setObject:value forKey:key];
    }
    strncpy(device_id, value.UTF8String, 63);
    device_id[63] = 0;
    return 0;
}

static int YGSTCopyOut(NSString *text, char *data, int size) {
    const char *s = text.UTF8String;
    size_t n = s ? strlen(s) : 0;
    if (!data || size <= 0 || n + 1 > (size_t)size) return YGSTSkegnFail(YGST_SGN_PARAMETER_WRONG);
    memcpy(data, s, n + 1);
    return (int)n;
}

int skegn_opt(struct skegn *engine, int opt, char *data, int size) {
    (void)engine;
    switch (opt) {
    case 1: // SKEGN_OPT_GET_VERSION
        return YGSTCopyOut(@"yugu-ios-stcompat " YGST_SDK_VERSION " (skegn " SKEGN_VERSION " API)", data, size);
    case 2: // SKEGN_OPT_GET_MODULES
        return YGSTCopyOut(@"{\"modules\":[\"cloud\"]}", data, size);
    case 3: // SKEGN_OPT_GET_TRAFFIC
        return YGSTCopyOut(@"0", data, size);
    case 4: // SKEGN_OPT_GET_PROVISION, or SKEGN_SET_WIFI_STATUS when built with USE_NATIVE
    case 5: // SKEGN_GET_SERIAL_NUMBER, or SKEGN_OPT_GET_PROVISION with USE_NATIVE
        return YGSTCopyOut(YGSTSkegnProvisionJSON(), data, size);
    case 6:
        return YGSTCopyOut(@"", data, size);
    default:
        return YGSTSkegnFail(YGST_SGN_PARAMETER_WRONG);
    }
}

int skegn_update_provision(const char *provision_path, const char *appkey, const char *secretkey) {
    (void)provision_path;
    (void)appkey;
    (void)secretkey;
    return 0;
}

int skegn_inquire_provision(const char *provision_path, skegn_callback callback, const void *usrdata) {
    const char *json;
    (void)provision_path;
    if (!callback) return YGSTSkegnFail(YGST_SGN_CALLBACK_IS_NULL);
    json = YGSTSkegnProvisionJSON().UTF8String;
    callback(usrdata, "", SKEGN_MESSAGE_TYPE_JSON, json, (int)strlen(json));
    return 0;
}

/* skegn.h declares skegn_get_last_error() without a prototype; give it one before defining it. */
int skegn_get_last_error(void);

int skegn_get_last_error(void) {
    return g_skegn_last_error;
}
