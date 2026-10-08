//
//  KYTestEngine.m
//  STKouyuEngine 平替层（优谷雅言）
//
//  Session state lives on one private serial queue; every callback is dispatched to the main
//  queue in order. A session ends with exactly one result or error JSON unless cancelEngine or
//  deleteEngine was called first, then with none.
//
//  Copyright 2026 优谷雅言 open.shengzhiai.com
//  SPDX-License-Identifier: Apache-2.0
//

#import "include/STKouyuEngine/KYTestEngine.h"
#import "YGSTInternal.h"

#import <AVFoundation/AVFoundation.h>

typedef NS_ENUM(NSInteger, YGSTMode) {
    YGSTModeMicrophone = 0,
    YGSTModeStream = 1,
    YGSTModeFile = 2,
};

typedef NS_ENUM(NSInteger, YGSTPhase) {
    YGSTPhaseStarting = 0,   // waiting for permission and the recorder
    YGSTPhaseCapturing = 1,  // recording or accepting feed
    YGSTPhasePaused = 2,     // recording interrupted by the system
    YGSTPhaseStopping = 3,   // recorder stopped, queued audio still arriving
    YGSTPhaseCaptured = 4,   // forceRecord: recording ended at duration, waiting for stopEngine
    YGSTPhaseEvaluating = 5, // uploading
    YGSTPhaseDone = 6,
};

static NSString *const YGSTProvisionJSON = @"{\"provision\":\"cloud\",\"message\":\"cloud mode, no provision file needed\"}";

// ------------------------------------------------------------------ session

@interface YGSTSession : NSObject {
@public
    ygst_fields _fields;
    ygst_vad _vad;
    ygst_retry_policy _policy;
}
@property (nonatomic, copy) NSString *tokenId;
@property (atomic) BOOL cancelled;
@property (nonatomic) YGSTMode mode;
@property (nonatomic) YGSTPhase phase;
@property (nonatomic) ygst_ct_status coreTypeStatus;
@property (nonatomic) unsigned long coreTypeEnum;
@property (nonatomic) BOOL configMissing;
@property (nonatomic, copy) NSString *coreType;
@property (nonatomic, copy) NSString *coreProvideType;
@property (nonatomic, copy) NSString *refText;
@property (nonatomic, copy) NSString *refPinyin;
@property (nonatomic, copy) NSString *userId;
@property (nonatomic, copy) NSString *audioType;
@property (nonatomic, copy) NSString *audioPath;
@property (nonatomic, copy) NSString *recordFile;
@property (nonatomic) int sampleRate;
@property (nonatomic) int channels;
@property (nonatomic) int sampleBytes;
@property (nonatomic) BOOL getParam;
@property (nonatomic) BOOL forceRecord;
@property (nonatomic) BOOL soundIntensity;
@property (nonatomic) BOOL vadEnabled;
@property (nonatomic) BOOL stopRequested;
@property (nonatomic) BOOL feedOverflow;
@property (nonatomic) BOOL feedIsWav;
@property (nonatomic) double seek10ms;
@property (nonatomic) double refLength10ms;
@property (nonatomic) double durationMs;
@property (nonatomic) double tickIntervalMs;
@property (nonatomic) double callbackIntervalMs;
@property (nonatomic) double serverTimeout;
@property (nonatomic) int64_t lastCallbackMs;
@property (nonatomic) int64_t captureStartMs;
@property (nonatomic) int64_t pausedAtMs;
@property (nonatomic) int64_t pausedTotalMs;
@property (nonatomic, strong) NSMutableData *audio;
@property (nonatomic, strong) NSData *uploadAudio;
@property (nonatomic, copy) NSString *uploadExtension;
@property (nonatomic, strong) dispatch_source_t tickTimer;
@property (nonatomic, strong) YGSTUploader *uploader;
@property (nonatomic, copy) KYTestResultBlock resultBlock;
@property (nonatomic, copy) void (^onStart)(void);
@property (nonatomic, copy) void (^onStartFail)(NSString *failReason);
@property (nonatomic, copy) void (^onPause)(void);
@property (nonatomic, copy) void (^onTick)(CGFloat millisUntilFinished, CGFloat percentUntilFinished);
@property (nonatomic, copy) void (^onRecording)(int vad_status, int sound_intensity);
@property (nonatomic, copy) void (^onRecordEnd)(void);
@property (nonatomic, copy) void (^onScore)(NSString *result);
@property (nonatomic, copy) void (^finish)(BOOL isSuccess, NSString *str);
@end

@implementation YGSTSession

- (instancetype)init {
    if ((self = [super init])) {
        ygst_fields_init(&_fields);
        ygst_retry_policy_default(&_policy);
        _audio = [NSMutableData data];
        _phase = YGSTPhaseStarting;
    }
    return self;
}

- (void)dealloc {
    ygst_fields_free(&_fields);
}

@end

// ------------------------------------------------------------------ engine

static char kYGSTEngineQueueKey;

@interface KYTestEngine () <AVAudioPlayerDelegate>
@property (atomic) BOOL ygstCustomAudioSession;
@end

@implementation KYTestEngine {
    dispatch_queue_t _q;
    BOOL _initialized;
    NSString *_appKey;
    NSString *_secretKey;
    NSString *_baseURL;
    BOOL _vadEnable;
    double _seek;
    double _connectTimeout;
    double _serverTimeout;
    YGSTSession *_session;
    YGSTRecorder *_recorder;
    NSString *_lastRecordPath;
    // main thread only
    AVAudioPlayer *_player;
    KYPlayFinishBlock _playFinish;
}

+ (instancetype)sharedInstance {
    static KYTestEngine *shared;
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        shared = [[self alloc] init];
    });
    return shared;
}

- (instancetype)init {
    if ((self = [super init])) {
        _q = dispatch_queue_create("com.shengzhiai.yugu.stcompat.engine", DISPATCH_QUEUE_SERIAL);
        dispatch_queue_set_specific(_q, &kYGSTEngineQueueKey, (__bridge void *)self, NULL);
        _seek = 60;
        _connectTimeout = 20;
        _serverTimeout = 60;
        _baseURL = @YGST_DEFAULT_BASE_URL;
        _appKey = @"";
        _secretKey = @"";
        _lastRecordPath = @"";
    }
    return self;
}

// ------------------------------------------------------------------ queue and delivery

- (void)onQueueSync:(dispatch_block_t)block {
    if (dispatch_get_specific(&kYGSTEngineQueueKey) == (__bridge void *)self) {
        block();
    } else {
        dispatch_sync(_q, block);
    }
}

/// Runs block on the main queue unless the session was cancelled meanwhile.
- (void)post:(YGSTSession *)s block:(void (^)(id<KYTestEngineDelegate> delegate))block {
    __weak KYTestEngine *weakSelf = self;
    dispatch_async(dispatch_get_main_queue(), ^{
        KYTestEngine *strongSelf = weakSelf;
        if (s && s.cancelled) return;
        block(strongSelf.delegate);
    });
}

- (NSString *)effectiveBaseURL {
    NSString *o = [YGSTSettings shared].baseURLOverride;
    return o.length ? o : _baseURL;
}

// ------------------------------------------------------------------ init

- (void)initEngine:(KYEngineType)engineType
    startEngineConfig:(KYStartEngineConfig *)startEngineConfig
          finishBlock:(void (^)(BOOL isSuccess, NSString *str))finishBlock {
    KYStartEngineConfig *c = startEngineConfig;
    void (^fb)(BOOL, NSString *) = [finishBlock copy];
    NSString *appKey = [c.appKey stringByTrimmingCharactersInSet:[NSCharacterSet whitespaceAndNewlineCharacterSet]] ?: @"";
    NSString *secret = [c.secretKey copy] ?: @"";
    NSString *server = [c.server copy];
    BOOL ok;
    NSString *message;
    char base[1024];
    NSString *baseURL;
    ygst_base_kind kind;

    YGSTLogConfigure(c.sdkLogEnable, c ? (NSInteger)c.logLevel : YGST_LEVEL_WARN, c.sdkLogPath, c ? c.isOutputLog : YES);
    if (engineType != KY_CloudEngine) {
        YGSTLogf(YGST_LEVEL_WARN, @"engine type %lu requested: the Yugu platform evaluates in the cloud only, "
                                  @"native and multi engines run as cloud", (unsigned long)engineType);
    }
    if (c && !c.enable) YGSTLogf(YGST_LEVEL_WARN, @"KYStartEngineConfig.enable is NO; cloud evaluation stays on");
    if (c.native.length || c.native_cn.length || c.native_db_path.length || c.provison.length) {
        YGSTLogf(YGST_LEVEL_INFO, @"offline resources and provision files are not used by the cloud engine");
    }
    kind = ygst_resolve_base_url(server.UTF8String, base, sizeof base);
    if (!c) {
        ok = NO;
        message = @"startEngineConfig is nil";
    } else if (!appKey.length) {
        ok = NO;
        message = @"appKey is empty";
    } else if (!secret.length) {
        ok = NO;
        message = @"secretKey is empty";
    } else if (kind == YGST_BASE_ERR_SCHEME) {
        ok = NO;
        message = [NSString stringWithFormat:@"server address needs http, https, ws or wss scheme: %@", server];
    } else if (kind == YGST_BASE_ERR_HOST) {
        ok = NO;
        message = [NSString stringWithFormat:@"server address has no host: %@", server];
    } else {
        ok = YES;
        message = @"init engine success";
    }
    if (kind == YGST_BASE_DEFAULT_SHENGTONG) {
        YGSTLogf(YGST_LEVEL_INFO, @"server %@ is a Shengtong address, using %s", server, base);
    }
    self.ygstCustomAudioSession = c.customized_avaudiosession;
    baseURL = [NSString stringWithUTF8String:base] ?: @YGST_DEFAULT_BASE_URL;
    [self onQueueSync:^{
        if (self->_session) {
            YGSTLogf(YGST_LEVEL_WARN, @"initEngine while session %@ is running: the session is cancelled",
                     self->_session.tokenId);
            [self detachAndTeardown:self->_session];
        }
        self->_initialized = ok;
        self->_appKey = ok ? appKey : @"";
        self->_secretKey = ok ? secret : @"";
        self->_baseURL = baseURL;
        self->_vadEnable = c.vadEnable;
        self->_seek = c.seek > 0 ? c.seek : 60;
        self->_connectTimeout = c.connectTimeout > 0 ? c.connectTimeout : 20;
        self->_serverTimeout = c.serverTimeout > 0 ? c.serverTimeout : 60;
        [YGSTSettings shared].configuredBaseURL = self->_baseURL;
    }];
    if (ok) {
        YGSTLogf(YGST_LEVEL_INFO, @"engine ready: appKey %@, platform %@, sdk %s", YGSTMaskedAppKey(appKey),
                 [self effectiveBaseURL], YGST_USER_AGENT);
    } else {
        YGSTLogf(YGST_LEVEL_ERROR, @"init engine failed: %@", message);
    }
    if (fb) {
        dispatch_async(dispatch_get_main_queue(), ^{
            fb(ok, message);
        });
    }
}

// ------------------------------------------------------------------ start

- (NSString *)startEngineWithTestConfig:(KYTestConfig *)testConfig
                                 result:(KYTestResultBlock)testResultBlock
                            finishBlock:(void (^)(BOOL isSuccess, NSString *str))finishBlock {
    YGSTSession *s = [self newSessionFromConfig:testConfig];
    s.resultBlock = testResultBlock;
    s.finish = finishBlock;
    dispatch_async(_q, ^{
        [self beginSession:s];
    });
    return s.tokenId;
}

- (NSString *)startEngineWithTestConfig:(KYTestConfig *)testConfig
                           onStartBlock:(void (^)(void))onStartBlock
                       onStartFailBlock:(void (^)(NSString *failReason))onStartFailBlock
                           onPauseBlock:(void (^)(void))onPauseBlock
                            onTickBlock:(void (^)(CGFloat millisUntilFinished, CGFloat percentUntilFinished))onTickBlock
                       onRecordingBlock:(void (^)(int vad_status, int sound_intensity))onRecordingBlock
                       onRecordEndBlock:(void (^)(void))onRecordEndBlock
                           onScoreBlock:(void (^)(NSString *result))onScoreBlock
                            finishBlock:(void (^)(BOOL isSuccess, NSString *str))finishBlock {
    YGSTSession *s = [self newSessionFromConfig:testConfig];
    s.onStart = onStartBlock;
    s.onStartFail = onStartFailBlock;
    s.onPause = onPauseBlock;
    s.onTick = onTickBlock;
    s.onRecording = onRecordingBlock;
    s.onRecordEnd = onRecordEndBlock;
    s.onScore = onScoreBlock;
    s.finish = finishBlock;
    dispatch_async(_q, ^{
        [self beginSession:s];
    });
    return s.tokenId;
}

static const char *YGSTKeep(NSString *s, NSMutableArray<NSData *> *keep) {
    NSMutableData *d;
    if (!s.length) return NULL;
    d = [[s dataUsingEncoding:NSUTF8StringEncoding] mutableCopy];
    if (!d) return NULL;
    [d appendBytes:"" length:1];
    [keep addObject:d];
    return (const char *)d.bytes;
}

/// Copies everything the session needs from the config on the calling thread.
- (YGSTSession *)newSessionFromConfig:(KYTestConfig *)c {
    YGSTSession *s = [[YGSTSession alloc] init];
    char coreType[128];
    NS_VALID_UNTIL_END_OF_SCOPE NSMutableArray<NSData *> *keep = [NSMutableArray array];
    ygst_test_params p;
    ygst_fields custom;
    s.tokenId = YGSTNewTokenId();
    if (!c) {
        s.configMissing = YES;
        s.coreTypeStatus = YGST_CT_UNKNOWN;
        s.coreType = @"";
        s.refText = @"";
        s.refPinyin = @"";
        s.userId = @"";
        s.audioType = @"wav";
        s.mode = YGSTModeMicrophone;
        return s;
    }
    s.coreTypeStatus = ygst_coretype_resolve(YGSTKeep(c.coreTypeNS, keep), (unsigned long)c.coreType, coreType,
                                             sizeof coreType);
    s.coreType = [NSString stringWithUTF8String:coreType] ?: @"";
    s.coreTypeEnum = (unsigned long)c.coreType;
    s.coreProvideType = c.coreProvideType ?: @"";
    s.refText = c.refText ?: @"";
    s.refPinyin = c.refPinyin ?: @"";
    s.userId = c.userId ?: @"";
    s.audioType = c.audioType.length ? c.audioType : @"wav";
    s.audioPath = c.audioPath.length ? c.audioPath : nil;
    s.mode = c.isStream ? YGSTModeStream : (s.audioPath ? YGSTModeFile : YGSTModeMicrophone);
    s.sampleRate = c.sampleRate > 0 ? (int)c.sampleRate : 16000;
    s.channels = c.channel > 0 ? (int)c.channel : 1;
    s.sampleBytes = c.sampleBytes > 0 ? (int)c.sampleBytes : 2;
    s.getParam = c.getParam;
    s.forceRecord = c.forceRecord;
    s.soundIntensity = c.soundIntensityEnable;
    s.seek10ms = c.seek;
    s.refLength10ms = c.ref_length;
    s.durationMs = c.duration > 0 ? c.duration : 0;
    s.tickIntervalMs = c.durationInterval > 0 ? c.durationInterval : 100;
    s.callbackIntervalMs = c.recordCallbackInterval > 0 ? c.recordCallbackInterval : 0;
    s.serverTimeout = c.serverTimeout > 0 ? c.serverTimeout : 0;
    s.recordFile = [self recordFileForConfig:c tokenId:s.tokenId];
    if (c.realtime_feedback) YGSTLogf(YGST_LEVEL_INFO, @"realtime_feedback is not supported by the platform compat path");

    s->_policy.auto_retry = c.autoRetry ? 1 : 0;
    if (c.errIds.count) {
        s->_policy.n_err_ids = 0;
        for (id e in c.errIds) {
            long long v = 0;
            if ([e isKindOfClass:[NSNumber class]]) v = [(NSNumber *)e longLongValue];
            else if ([e isKindOfClass:[NSString class]]) v = [(NSString *)e longLongValue];
            if (ygst_retry_policy_add_err_id(&s->_policy, v) != 0) YGSTLogf(YGST_LEVEL_WARN, @"errIds: ignored %@", e);
        }
        if (s->_policy.n_err_ids == 0) ygst_retry_policy_add_err_id(&s->_policy, YGST_ERRID_RETRYABLE);
    }

    ygst_test_params_init(&p);
    ygst_fields_init(&custom);
    p.core_type = YGSTKeep(s.coreType, keep);
    p.ref_text = YGSTKeep(c.refText, keep);
    p.ref_pinyin = YGSTKeep(c.refPinyin, keep);
    p.attach_audio_url = c.attachAudioUrl ? 1 : 0;
    p.phoneme_option = (int)c.phonemeOption;
    p.phoneme_output = c.phoneme_output ? 1 : 0;
    p.age_group = (int)c.ageGroup;
    p.mode = (int)c.mode;
    p.paragraph_need_word_score = c.isParagraphNeedWordScore ? 1 : 0;
    p.scale = c.scale;
    p.precision = c.precision;
    p.slack = c.slack;
    p.keywords = YGSTKeep(c.keywords, keep);
    p.q_type = (int)c.qType;
    p.customized_lexicon_json = YGSTKeep(YGSTJSONText(c.customized_lexicon), keep);
    p.negative_reftext = YGSTKeep(c.negativeReftext, keep);
    p.dict_dialect = YGSTKeep(c.dict_dialect, keep);
    p.detect_nonscorable = c.detect_nonscorable ? 1 : 0;
    p.customized_pron_json = YGSTKeep(YGSTJSONText(c.customized_pron), keep);
    p.output_rawtext = c.output_rawtext ? 1 : 0;
    p.vad_detection = c.vad_detection ? 1 : 0;
    p.keypoints_json = YGSTKeep(YGSTJSONText(c.keypoints), keep);
    p.keypoints_weight = c.keypoints_weight;
    p.negative_keypoints_json = YGSTKeep(YGSTJSONText(c.negative_keypoints), keep);
    p.punctuate = c.punctuate ? 1 : 0;
    p.readtype_diagnosis = c.readtype_diagnosis;
    p.itn = c.itn;
    p.request_json = YGSTKeep(YGSTJSONText(c.request), keep);
    for (id key in c.customParams) {
        NSString *name = [key description];
        NSString *value = YGSTValueText(c.customParams[key]);
        if (!value || !ygst_field_name_ok(name.UTF8String)) {
            YGSTLogf(YGST_LEVEL_WARN, @"customParams: skipped %@", name);
            continue;
        }
        ygst_fields_set(&custom, YGSTKeep(name, keep), YGSTKeep(value, keep) ?: "");
    }
    p.custom_params = &custom;
    if (ygst_params_to_fields(&p, &s->_fields) != 0) YGSTLogf(YGST_LEVEL_ERROR, @"form fields: out of memory");
    ygst_fields_free(&custom);
    return s;
}

- (NSString *)recordFileForConfig:(KYTestConfig *)c tokenId:(NSString *)tokenId {
    NSString *dir = c.recordPath;
    NSString *name = c.recordName;
    NSString *ext = dir.pathExtension.lowercaseString;
    if (dir.length && !name.length && ([ext isEqualToString:@"wav"] || [ext isEqualToString:@"mp3"] ||
                                       [ext isEqualToString:@"pcm"])) {
        return dir; // a full file path in recordPath
    }
    if (!dir.length) dir = [YGSTDocumentsPath() stringByAppendingPathComponent:@"record"];
    if (!name.length) name = [tokenId stringByAppendingPathExtension:@"wav"];
    return [dir stringByAppendingPathComponent:name];
}

/// Which callbacks a start that did not become the running session gets, besides
/// finishBlock(NO, errorJSON), which it always gets. Mirrors the Android layer: errors that are
/// answered with an error JSON there (60007, 60003, 60006) go to the result callbacks, start
/// failures of the recorder (60004) and a busy engine (60008) go to the start-fail callbacks.
typedef NS_OPTIONS(NSUInteger, YGSTRejectDelivery) {
    YGSTRejectResult = 1 << 0,    // testResultBlock or onScoreBlock
    YGSTRejectStartFail = 1 << 1, // onStartFailBlock
    YGSTRejectDelegate = 1 << 2,  // the delegate methods matching the two above
};

/// On the queue. Validates and starts the session in its mode.
- (void)beginSession:(YGSTSession *)s {
    int invalid;
    if (s.cancelled) return;
    if (!_initialized) {
        [self reject:s
                errId:YGST_ERRID_NOT_INITIALIZED
              message:nil
               reason:@"engine is not initialized"
             delivery:YGSTRejectResult | YGSTRejectDelegate];
        return;
    }
    if (_session) {
        // the running session owns the delegate, so only the blocks of this call hear about it
        YGSTLogf(YGST_LEVEL_WARN, @"start rejected: session %@ has not finished", _session.tokenId);
        [self reject:s errId:YGST_ERRID_ENGINE_BUSY message:nil reason:@"engine is busy" delivery:YGSTRejectStartFail];
        return;
    }
    if ([s.coreProvideType isEqualToString:KYEngineNative]) {
        YGSTLogf(YGST_LEVEL_WARN, @"coreProvideType native: evaluated in the cloud");
    }
    invalid = s.configMissing ? YGST_ERRID_CORETYPE_UNSUPPORTED
                              : ygst_validate_request(s.coreTypeStatus, s.coreType.UTF8String, s.refText.UTF8String,
                                                      s.refPinyin.UTF8String);
    if (invalid) {
        NSString *what = s.configMissing ? @"testConfig is nil"
                         : invalid == YGST_ERRID_CORETYPE_UNSUPPORTED
                             ? [NSString stringWithFormat:@"coreType %@ is not supported",
                                                          s.coreType.length ? s.coreType
                                                                            : [NSString stringWithFormat:@"KYTestType %lu",
                                                                                                         s.coreTypeEnum]]
                             : @"refText is empty";
        [self reject:s errId:invalid message:nil reason:what delivery:YGSTRejectResult | YGSTRejectDelegate];
        return;
    }
    _session = s;
    YGSTLogf(YGST_LEVEL_INFO, @"session %@: %@ %@", s.tokenId, s.coreType,
             s.mode == YGSTModeFile ? @"file" : (s.mode == YGSTModeStream ? @"stream" : @"microphone"));
    switch (s.mode) {
    case YGSTModeFile:
        [self startFileSession:s];
        break;
    case YGSTModeStream:
        [self startStreamSession:s];
        break;
    case YGSTModeMicrophone:
        [self startMicrophoneSession:s];
        break;
    }
}

/// A start that never became the running session, or a recorder that failed while starting.
/// message nil uses the errId message of spec/errors.json.
- (void)reject:(YGSTSession *)s
         errId:(int)errId
       message:(NSString *)message
        reason:(NSString *)reason
      delivery:(YGSTRejectDelivery)delivery {
    NSString *json = YGSTErrorJSON(s.tokenId, errId, message.length ? message : YGSTCompatMessage(errId), _appKey);
    BOOL toDelegate = (delivery & YGSTRejectDelegate) != 0;
    YGSTLogf(YGST_LEVEL_WARN, @"session %@ not started: errId %d, %@", s.tokenId, errId, reason);
    if (_session == s) _session = nil;
    s.phase = YGSTPhaseDone;
    [self post:s
         block:^(id<KYTestEngineDelegate> d) {
             if (s.finish) s.finish(NO, json);
             if (delivery & YGSTRejectStartFail) {
                 if (s.onStartFail) s.onStartFail(reason);
                 if (toDelegate && [d respondsToSelector:@selector(kyTestEngineDidRecordStartFail:)]) {
                     [d kyTestEngineDidRecordStartFail:reason];
                 }
             }
             if (delivery & YGSTRejectResult) {
                 if (s.resultBlock) s.resultBlock(json);
                 if (s.onScore) s.onScore(json);
                 if (toDelegate && [d respondsToSelector:@selector(kyTestEngineDidScore:)]) [d kyTestEngineDidScore:json];
             }
         }];
}

- (void)postStarted:(YGSTSession *)s recording:(BOOL)recording {
    NSString *token = s.tokenId;
    [self post:s
         block:^(id<KYTestEngineDelegate> d) {
             if (s.finish) s.finish(YES, token);
             if (recording) {
                 if (s.onStart) s.onStart();
                 if ([d respondsToSelector:@selector(kyTestEngineDidRecordStart)]) [d kyTestEngineDidRecordStart];
             }
         }];
}

// ------------------------------------------------------------------ file mode

- (void)startFileSession:(YGSTSession *)s {
    NSError *error = nil;
    NSData *data;
    NSString *fileExt = s.audioPath.pathExtension.lowercaseString;
    NSString *ext = [NSString stringWithUTF8String:ygst_audio_ext((fileExt.length ? fileExt : s.audioType).UTF8String)];
    int check;
    // like existsAudioTrans on Android: the start succeeds, problems with the file come as results
    [self postStarted:s recording:NO];
    data = [NSData dataWithContentsOfFile:s.audioPath options:NSDataReadingMappedIfSafe error:&error];
    if (!data) {
        YGSTLogf(YGST_LEVEL_WARN, @"audio file %@: %@", s.audioPath, error.localizedDescription);
        [self complete:s
                  json:YGSTErrorJSON(s.tokenId, YGST_ERRID_AUDIO_FILE_MISSING,
                                     [NSString stringWithFormat:@"%@: %@", YGSTCompatMessage(YGST_ERRID_AUDIO_FILE_MISSING),
                                                                s.audioPath],
                                     _appKey)];
        return;
    }
    if ([fileExt isEqualToString:@"pcm"] || ([s.audioType caseInsensitiveCompare:@"pcm"] == NSOrderedSame &&
                                             !ygst_is_riff_wave(data.bytes, data.length))) {
        check = ygst_audio_check_pcm(data.length, s.sampleRate, s.channels, s.sampleBytes * 8);
        data = YGSTWavFromPCM(data, s.sampleRate, s.channels, s.sampleBytes * 8);
        ext = @"wav";
    } else {
        check = ygst_audio_check_upload(data.bytes, data.length);
    }
    if (check) {
        [self complete:s json:YGSTErrorJSON(s.tokenId, check, YGSTCompatMessage(check), _appKey)];
        return;
    }
    s.uploadExtension = ext;
    [self evaluate:s audio:data extension:ext];
}

// ------------------------------------------------------------------ stream mode

- (void)startStreamSession:(YGSTSession *)s {
    if (_vadEnable) ygst_vad_init(&s->_vad, s.sampleRate, s.seek10ms > 0 ? s.seek10ms : _seek, s.refLength10ms);
    s.vadEnabled = _vadEnable && s.sampleBytes == 2 && s.channels == 1 && ygst_audio_type_is_pcm(s.audioType.UTF8String);
    s.phase = YGSTPhaseCapturing;
    s.captureStartMs = YGSTMonotonicMs();
    [self postStarted:s recording:YES];
}

- (void)feedAudioData:(void *)audioData audioLength:(int)length {
    NSData *chunk;
    if (!audioData || length <= 0) {
        [self postFeedFail:@"feedAudioData: empty buffer"];
        return;
    }
    chunk = [NSData dataWithBytes:audioData length:(NSUInteger)length];
    dispatch_async(_q, ^{
        [self appendFeed:chunk];
    });
}

- (void)postFeedFail:(NSString *)why {
    YGSTLogf(YGST_LEVEL_WARN, @"%@", why);
    [self post:nil
         block:^(id<KYTestEngineDelegate> d) {
             if ([d respondsToSelector:@selector(kyTestEngineDidRecordFeedFail:)]) [d kyTestEngineDidRecordFeedFail:why];
         }];
}

- (void)appendFeed:(NSData *)chunk {
    YGSTSession *s = _session;
    if (!s || s.mode != YGSTModeStream || s.phase != YGSTPhaseCapturing) {
        [self postFeedFail:@"feedAudioData: no stream session is accepting audio (isStream must be YES and stopEngine not called)"];
        return;
    }
    if (s.audio.length + chunk.length > YGST_MAX_AUDIO_BYTES) {
        if (!s.feedOverflow) [self postFeedFail:@"feedAudioData: more than 50 MB of audio"];
        s.feedOverflow = YES;
        return;
    }
    if (s.audio.length == 0 && ygst_is_riff_wave(chunk.bytes, chunk.length)) {
        s.feedIsWav = YES; // the caller feeds a WAV file, not raw PCM
        s.vadEnabled = NO;
    }
    if (!s.feedIsWav && ygst_audio_type_is_pcm(s.audioType.UTF8String)) {
        // raw PCM: like a recording, the stream ends at the 300 s limit and is evaluated
        NSUInteger max = (NSUInteger)(YGST_MAX_AUDIO_MS / 1000) * (NSUInteger)(s.sampleRate * s.channels * s.sampleBytes);
        NSUInteger room = s.audio.length < max ? max - s.audio.length : 0;
        if (chunk.length > room) chunk = [chunk subdataWithRange:NSMakeRange(0, room)];
        [s.audio appendData:chunk];
        if (s.vadEnabled && chunk.length) [self runVAD:s bytes:chunk];
        if (s.audio.length >= max && s.phase == YGSTPhaseCapturing) {
            YGSTLogf(YGST_LEVEL_WARN, @"stream reached the %d s limit, evaluating", YGST_MAX_AUDIO_MS / 1000);
            [self stopCapture:s waitForStop:NO];
        }
        return;
    }
    [s.audio appendData:chunk];
}

// ------------------------------------------------------------------ microphone mode

- (void)startMicrophoneSession:(YGSTSession *)s {
    if (!self.ygstCustomAudioSession) {
        NSString *why = nil;
        if (!YGSTActivateAudioSession(&why)) YGSTLogf(YGST_LEVEL_WARN, @"%@", why);
    }
    if (_vadEnable) ygst_vad_init(&s->_vad, 16000, s.seek10ms > 0 ? s.seek10ms : _seek, s.refLength10ms);
    s.vadEnabled = _vadEnable;
    YGSTRequestMicrophone(_q, ^(BOOL granted) {
        [self microphoneDecision:granted session:s];
    });
}

- (void)microphoneDecision:(BOOL)granted session:(YGSTSession *)s {
    NSString *why = nil;
    __weak KYTestEngine *weakSelf = self;
    if (s.cancelled || _session != s) return;
    if (!granted) {
        NSString *reason = @"microphone unavailable or permission denied: record permission not granted";
        [self reject:s
                errId:YGST_ERRID_MIC_UNAVAILABLE
              message:reason
               reason:reason
             delivery:YGSTRejectStartFail | YGSTRejectDelegate];
        return;
    }
    if (!_recorder) _recorder = [[YGSTRecorder alloc] initWithQueue:_q];
    _recorder.pcmHandler = ^(NSData *pcm) {
        [weakSelf recorderPCM:pcm session:s];
    };
    _recorder.interruptionHandler = ^(BOOL began) {
        [weakSelf recorderInterrupted:began session:s];
    };
    _recorder.failureHandler = ^(NSString *message) {
        [weakSelf recorderFailed:message session:s];
    };
    if (![_recorder start:&why]) {
        NSString *reason = [@"microphone unavailable or permission denied: " stringByAppendingString:why ?: @"no input"];
        [self reject:s
                errId:YGST_ERRID_MIC_UNAVAILABLE
              message:reason
               reason:reason
             delivery:YGSTRejectStartFail | YGSTRejectDelegate];
        return;
    }
    s.phase = YGSTPhaseCapturing;
    s.captureStartMs = YGSTMonotonicMs();
    [self postStarted:s recording:YES];
    [self startTicks:s];
    if (s.stopRequested) [self stopCapture:s waitForStop:NO];
}

/// PCM bytes of the 300 s limit at 16 kHz mono 16 bit: recordings stop exactly there.
static const NSUInteger kYGSTMaxRecordBytes = (NSUInteger)(YGST_MAX_AUDIO_MS / 1000) * 32000u;

- (void)recorderPCM:(NSData *)pcm session:(YGSTSession *)s {
    NSUInteger room;
    if (s != _session || (s.phase != YGSTPhaseCapturing && s.phase != YGSTPhaseStopping)) return;
    room = s.audio.length < kYGSTMaxRecordBytes ? kYGSTMaxRecordBytes - s.audio.length : 0;
    if (pcm.length > room) pcm = [pcm subdataWithRange:NSMakeRange(0, room)];
    [s.audio appendData:pcm];
    if (s.phase != YGSTPhaseCapturing) return;
    if (s.vadEnabled && pcm.length) {
        [self runVAD:s bytes:pcm];
        if (s.phase != YGSTPhaseCapturing) return;
    }
    if (s.audio.length >= kYGSTMaxRecordBytes) {
        YGSTLogf(YGST_LEVEL_WARN, @"recording reached the %d s limit, stopping", YGST_MAX_AUDIO_MS / 1000);
        [self stopCapture:s waitForStop:NO];
    }
}

- (void)recorderInterrupted:(BOOL)began session:(YGSTSession *)s {
    int64_t now = YGSTMonotonicMs();
    if (s != _session) return;
    if (began && s.phase == YGSTPhaseCapturing) {
        s.phase = YGSTPhasePaused;
        s.pausedAtMs = now;
        [self post:s
             block:^(id<KYTestEngineDelegate> d) {
                 (void)d;
                 if (s.onPause) s.onPause();
             }];
    } else if (!began && s.phase == YGSTPhasePaused) {
        s.pausedTotalMs += now - s.pausedAtMs;
        s.phase = YGSTPhaseCapturing;
    }
}

- (void)recorderFailed:(NSString *)message session:(YGSTSession *)s {
    if (s != _session) return;
    if (s.phase == YGSTPhaseCapturing || s.phase == YGSTPhasePaused) {
        YGSTLogf(YGST_LEVEL_WARN, @"recording ended early (%@), evaluating what was captured", message);
        [self stopCapture:s waitForStop:NO];
    }
}

// ------------------------------------------------------------------ VAD and ticks

- (void)runVAD:(YGSTSession *)s bytes:(NSData *)bytes {
    int status = ygst_vad_process_bytes(&s->_vad, bytes.bytes, bytes.length);
    BOOL ended = s->_vad.ended_edge != 0;
    int intensity = s.soundIntensity ? s->_vad.intensity : 0;
    int64_t now = YGSTMonotonicMs();
    if (ended || s.callbackIntervalMs <= 0 || now - s.lastCallbackMs >= (int64_t)s.callbackIntervalMs) {
        s.lastCallbackMs = now;
        [self post:s
             block:^(id<KYTestEngineDelegate> d) {
                 if (s.onRecording) s.onRecording(status, intensity);
                 if ([d respondsToSelector:@selector(kyTestEngineDidVadScore:sound_intensity:)]) {
                     [d kyTestEngineDidVadScore:status sound_intensity:intensity];
                 }
             }];
    }
    if (ended && !s.forceRecord && s.mode == YGSTModeMicrophone) {
        YGSTLogf(YGST_LEVEL_INFO, @"session %@: speech ended (VAD)", s.tokenId);
        [self stopCapture:s waitForStop:NO];
    }
}

- (void)startTicks:(YGSTSession *)s {
    dispatch_source_t timer;
    uint64_t interval;
    __weak KYTestEngine *weakSelf = self;
    if (s.durationMs <= 0) return;
    interval = (uint64_t)(s.tickIntervalMs * (double)NSEC_PER_MSEC);
    timer = dispatch_source_create(DISPATCH_SOURCE_TYPE_TIMER, 0, 0, _q);
    dispatch_source_set_timer(timer, dispatch_time(DISPATCH_TIME_NOW, (int64_t)interval), interval, interval / 10);
    dispatch_source_set_event_handler(timer, ^{
        [weakSelf tick:s];
    });
    s.tickTimer = timer;
    dispatch_resume(timer);
}

- (void)stopTicks:(YGSTSession *)s {
    if (s.tickTimer) {
        dispatch_source_cancel(s.tickTimer);
        s.tickTimer = nil;
    }
}

- (void)tick:(YGSTSession *)s {
    double left = 0, percent = 0;
    double elapsed;
    if (s != _session || s.phase != YGSTPhaseCapturing) return;
    elapsed = (double)(YGSTMonotonicMs() - s.captureStartMs - s.pausedTotalMs);
    ygst_tick_compute(s.durationMs, elapsed, &left, &percent);
    [self post:s
         block:^(id<KYTestEngineDelegate> d) {
             if (s.onTick) s.onTick((CGFloat)left, (CGFloat)percent);
             if ([d respondsToSelector:@selector(kyTestEngineDidRecordTick:percentUntilFinished:)]) {
                 [d kyTestEngineDidRecordTick:(CGFloat)left percentUntilFinished:(CGFloat)percent];
             }
         }];
    if (left <= 0) {
        YGSTLogf(YGST_LEVEL_INFO, @"session %@: duration reached", s.tokenId);
        [self stopCapture:s waitForStop:s.forceRecord];
    }
}

// ------------------------------------------------------------------ stop and evaluate

/// Ends capture. Audio already queued on the engine queue is still appended before the
/// recording is finalised. waitForStop keeps the recording until stopEngine (forceRecord).
- (void)stopCapture:(YGSTSession *)s waitForStop:(BOOL)waitForStop {
    if (s.phase != YGSTPhaseCapturing && s.phase != YGSTPhasePaused) return;
    s.phase = YGSTPhaseStopping;
    [self stopTicks:s];
    if (s.mode == YGSTModeMicrophone) [_recorder stop];
    dispatch_async(_q, ^{
        [self finalizeCapture:s waitForStop:waitForStop];
    });
}

- (void)finalizeCapture:(YGSTSession *)s waitForStop:(BOOL)waitForStop {
    NSData *upload;
    NSString *ext = @"wav";
    NSString *file = s.recordFile;
    BOOL written;
    int check = 0;
    if (s != _session || s.phase != YGSTPhaseStopping) return;
    if (s.mode == YGSTModeMicrophone) {
        [self releaseRecorderHandlers];
        check = ygst_audio_check_pcm(s.audio.length, 16000, 1, 16);
        upload = YGSTWavFromPCM(s.audio, 16000, 1, 16);
    } else if (ygst_audio_type_is_pcm(s.audioType.UTF8String) && !ygst_is_riff_wave(s.audio.bytes, s.audio.length)) {
        check = ygst_audio_check_pcm(s.audio.length, s.sampleRate, s.channels, s.sampleBytes * 8);
        upload = YGSTWavFromPCM(s.audio, s.sampleRate, s.channels, s.sampleBytes * 8);
    } else {
        ext = [NSString stringWithUTF8String:ygst_audio_ext(s.audioType.UTF8String)];
        upload = [s.audio copy];
        check = ygst_audio_check_upload(upload.bytes, upload.length);
        if (![file.pathExtension.lowercaseString isEqualToString:ext]) {
            file = [[file stringByDeletingPathExtension] stringByAppendingPathExtension:ext];
        }
    }
    if (s.feedOverflow) check = YGST_ERRID_AUDIO_TOO_LARGE;
    written = [self writeRecording:upload toPath:file];
    if (written) _lastRecordPath = file;
    s.uploadAudio = upload;
    s.uploadExtension = ext;
    s.audio = [NSMutableData data];
    [self post:s
         block:^(id<KYTestEngineDelegate> d) {
             if (s.onRecordEnd) s.onRecordEnd();
             if ([d respondsToSelector:@selector(kyTestEngineDidRecordEnd)]) [d kyTestEngineDidRecordEnd];
             if ([d respondsToSelector:@selector(kyTestEngineDidRecordWriteAudioResult:)]) {
                 [d kyTestEngineDidRecordWriteAudioResult:written];
             }
         }];
    if (check) {
        [self complete:s json:YGSTErrorJSON(s.tokenId, check, YGSTCompatMessage(check), _appKey)];
        return;
    }
    if (waitForStop) {
        s.phase = YGSTPhaseCaptured;
        YGSTLogf(YGST_LEVEL_INFO, @"session %@: recording kept until stopEngine (forceRecord)", s.tokenId);
        return;
    }
    [self evaluate:s audio:upload extension:ext];
}

- (BOOL)writeRecording:(NSData *)data toPath:(NSString *)path {
    NSError *error = nil;
    NSString *dir = [path stringByDeletingLastPathComponent];
    if (!path.length) return NO;
    if (dir.length && ![[NSFileManager defaultManager] createDirectoryAtPath:dir
                                                withIntermediateDirectories:YES
                                                                 attributes:nil
                                                                      error:&error]) {
        YGSTLogf(YGST_LEVEL_WARN, @"record directory %@: %@", dir, error.localizedDescription);
        return NO;
    }
    if (![data writeToFile:path options:NSDataWritingAtomic error:&error]) {
        YGSTLogf(YGST_LEVEL_WARN, @"record file %@: %@", path, error.localizedDescription);
        return NO;
    }
    return YES;
}

- (void)evaluate:(YGSTSession *)s audio:(NSData *)audio extension:(NSString *)ext {
    NSString *error = nil;
    YGSTRequest *req;
    YGSTUploader *uploader;
    ygst_retry_policy policy = s->_policy;
    NSInteger retries = [YGSTSettings shared].maxRetries;
    NSTimeInterval timeout = s.serverTimeout > 0 ? s.serverTimeout : _serverTimeout;
    __weak KYTestEngine *weakSelf = self;
    s.phase = YGSTPhaseEvaluating;
    req = [YGSTRequest requestWithBaseURL:[self effectiveBaseURL]
                                 coreType:s.coreType
                                   fields:&s->_fields
                                   secret:_secretKey
                                    audio:audio
                                extension:ext
                                    error:&error];
    if (!req) {
        [self complete:s json:YGSTErrorJSON(s.tokenId, 90010, error, _appKey)];
        return;
    }
    policy.max_retries = (int)(retries < 0 ? 0 : (retries > 5 ? 5 : retries));
    policy.total_timeout_ms = (int)([YGSTSettings shared].totalTimeout * 1000.0);
    if (timeout < _connectTimeout) timeout = _connectTimeout;
    uploader = [[YGSTUploader alloc] initWithURLSession:YGSTSharedURLSession() queue:_q];
    s.uploader = uploader;
    YGSTLogf(YGST_LEVEL_DEBUG, @"session %@: POST %@ (%lu bytes audio)", s.tokenId, req.url.absoluteString,
             (unsigned long)audio.length);
    [uploader startWithURL:req.url
                    appKey:_appKey
                 signature:req.signature
            idempotencyKey:s.tokenId
                      body:req.body
               contentType:req.contentType
            attemptTimeout:timeout
                    policy:&policy
                completion:^(YGSTUploadResult *result) {
                    [weakSelf uploadFinished:result session:s];
                }];
}

- (NSString *)paramsJSONForSession:(YGSTSession *)s {
    ygst_buf b;
    NSString *out;
    BOOL mic = s.mode == YGSTModeMicrophone;
    ygst_buf_init(&b);
    ygst_params_json(&b, _appKey.UTF8String, s.userId.UTF8String, YGSTNowMs() / 1000,
                     mic ? "wav" : YGSTNonEmpty(s.uploadExtension ?: s.audioType).UTF8String, mic ? 16000 : s.sampleRate,
                     mic ? 1 : s.channels, mic ? 2 : s.sampleBytes, s.coreType.UTF8String, s.tokenId.UTF8String,
                     &s->_fields);
    out = YGSTStringFromBuf(&b);
    ygst_buf_free(&b);
    return out;
}

- (void)uploadFinished:(YGSTUploadResult *)result session:(YGSTSession *)s {
    NSString *json = nil;
    if (s.cancelled || s != _session) return;
    s.uploader = nil;
    if (result.success) {
        json = YGSTResultJSON(result, s.tokenId, _appKey, s.userId, s.refText,
                              s.getParam ? [self paramsJSONForSession:s] : nil);
        if (!json) json = YGSTErrorJSON(s.tokenId, YGST_LOCAL_PROTOCOL, YGSTCompatMessage(YGST_LOCAL_PROTOCOL), _appKey);
    } else {
        json = YGSTErrorJSON(s.tokenId, result.errId, result.message, _appKey);
    }
    [self complete:s json:json];
}

- (void)complete:(YGSTSession *)s json:(NSString *)json {
    if (_session == s) _session = nil;
    s.phase = YGSTPhaseDone;
    s.uploadAudio = nil;
    [self post:s
         block:^(id<KYTestEngineDelegate> d) {
             if (s.resultBlock) s.resultBlock(json);
             if (s.onScore) s.onScore(json);
             if ([d respondsToSelector:@selector(kyTestEngineDidScore:)]) [d kyTestEngineDidScore:json];
         }];
}

- (void)stopEngine {
    dispatch_async(_q, ^{
        YGSTSession *s = self->_session;
        if (!s) {
            YGSTLogf(YGST_LEVEL_DEBUG, @"stopEngine: no session");
            return;
        }
        switch (s.phase) {
        case YGSTPhaseStarting:
            s.stopRequested = YES;
            break;
        case YGSTPhaseCapturing:
        case YGSTPhasePaused:
            [self stopCapture:s waitForStop:NO];
            break;
        case YGSTPhaseCaptured:
            [self evaluate:s audio:s.uploadAudio extension:s.uploadExtension ?: @"wav"];
            break;
        default:
            break; // stopping or evaluating already
        }
    });
}

// ------------------------------------------------------------------ cancel and delete

- (void)releaseRecorderHandlers {
    _recorder.pcmHandler = nil;
    _recorder.interruptionHandler = nil;
    _recorder.failureHandler = nil;
}

- (void)detachAndTeardown:(YGSTSession *)s {
    s.cancelled = YES;
    if (_session == s) _session = nil;
    [self stopTicks:s];
    if (s.mode == YGSTModeMicrophone && (s.phase == YGSTPhaseCapturing || s.phase == YGSTPhasePaused ||
                                         s.phase == YGSTPhaseStarting)) {
        [_recorder stop];
    }
    if (s.mode == YGSTModeMicrophone) [self releaseRecorderHandlers];
    [s.uploader cancel];
    s.uploader = nil;
    s.phase = YGSTPhaseDone;
    YGSTLogf(YGST_LEVEL_INFO, @"session %@ cancelled", s.tokenId);
}

- (void)cancelEngine {
    [self onQueueSync:^{
        YGSTSession *s = self->_session;
        if (s) [self detachAndTeardown:s];
    }];
}

- (void)deleteEngine {
    [self onQueueSync:^{
        YGSTSession *s = self->_session;
        if (s) [self detachAndTeardown:s];
        [self->_recorder stop];
        self->_recorder = nil;
        self->_initialized = NO;
        self->_appKey = @"";
        self->_secretKey = @"";
    }];
    dispatch_async(dispatch_get_main_queue(), ^{
        self->_player.delegate = nil;
        [self->_player stop];
        self->_player = nil;
        self->_playFinish = nil;
    });
    YGSTLogf(YGST_LEVEL_INFO, @"engine deleted");
}

// ------------------------------------------------------------------ status

- (BOOL)getEngineStatus {
    __block BOOL recording = NO;
    [self onQueueSync:^{
        YGSTSession *s = self->_session;
        recording = s && s.mode != YGSTModeFile && (s.phase == YGSTPhaseCapturing || s.phase == YGSTPhasePaused);
    }];
    return recording;
}

- (NSString *)getLastRecordPath {
    __block NSString *path = @"";
    [self onQueueSync:^{
        path = self->_lastRecordPath ?: @"";
    }];
    return path;
}

// ------------------------------------------------------------------ playback (main thread)

- (void)onMain:(dispatch_block_t)block {
    if ([NSThread isMainThread]) {
        block();
    } else {
        dispatch_async(dispatch_get_main_queue(), block);
    }
}

- (void)playback {
    [self playback:nil];
}

- (void)playback:(KYPlayFinishBlock)playFinishBlock {
    [self playWithPath:[self getLastRecordPath] void:playFinishBlock];
}

- (void)playWithPath:(NSString *)wavPath {
    [self playWithPath:wavPath void:nil];
}

- (void)playWithPath:(NSString *)wavPath void:(KYPlayFinishBlock)playFinishBlock {
    KYPlayFinishBlock fb = [playFinishBlock copy];
    NSString *path = [wavPath copy];
    [self onMain:^{
        [self startPlayerWithPath:path finish:fb];
    }];
}

- (void)notifyPlayStartFail:(NSString *)why {
    id<KYTestEngineDelegate> d = self.delegate;
    YGSTLogf(YGST_LEVEL_WARN, @"playback: %@", why);
    if ([d respondsToSelector:@selector(kyTestEngineDidPlayStartFail:)]) [d kyTestEngineDidPlayStartFail:why];
}

- (void)startPlayerWithPath:(NSString *)path finish:(KYPlayFinishBlock)finish {
    NSError *error = nil;
    AVAudioPlayer *player;
    id<KYTestEngineDelegate> d;
    if (_player) [self endPlayback];
    if (!path.length || ![[NSFileManager defaultManager] fileExistsAtPath:path]) {
        [self notifyPlayStartFail:[NSString stringWithFormat:@"audio file not found: %@", path ?: @""]];
        return;
    }
    if (!self.ygstCustomAudioSession) {
        NSString *why = nil;
        if (!YGSTActivateAudioSession(&why)) YGSTLogf(YGST_LEVEL_WARN, @"%@", why);
    }
    player = [[AVAudioPlayer alloc] initWithContentsOfURL:[NSURL fileURLWithPath:path] error:&error];
    if (!player) {
        [self notifyPlayStartFail:error.localizedDescription ?: @"audio file cannot be played"];
        return;
    }
    player.delegate = self;
    [player prepareToPlay];
    if (![player play]) {
        [self notifyPlayStartFail:@"playback did not start"];
        return;
    }
    _player = player;
    _playFinish = finish;
    d = self.delegate;
    if ([d respondsToSelector:@selector(kyTestEngineDidPlayStart)]) [d kyTestEngineDidPlayStart];
}

- (void)endPlayback {
    KYPlayFinishBlock fb = _playFinish;
    id<KYTestEngineDelegate> d = self.delegate;
    _player.delegate = nil;
    _player = nil;
    _playFinish = nil;
    if ([d respondsToSelector:@selector(kyTestEngineDidPlayEnd)]) [d kyTestEngineDidPlayEnd];
    if (fb) fb();
}

- (void)audioPlayerDidFinishPlaying:(AVAudioPlayer *)player successfully:(BOOL)flag {
    (void)flag;
    if (player != _player) return;
    [self endPlayback];
}

- (void)audioPlayerDecodeErrorDidOccur:(AVAudioPlayer *)player error:(NSError *)error {
    if (player != _player) return;
    YGSTLogf(YGST_LEVEL_WARN, @"playback decode error: %@", error.localizedDescription);
    [self endPlayback];
}

- (void)stopPlay {
    [self onMain:^{
        if (!self->_player) return;
        [self->_player stop];
        [self endPlayback];
    }];
}

- (void)activeAudioSession {
    NSString *why = nil;
    if (!YGSTActivateAudioSession(&why)) YGSTLogf(YGST_LEVEL_WARN, @"%@", why);
}

// ------------------------------------------------------------------ provision (cloud: nothing to do)

- (BOOL)updateProvision:(NSString *)provison appkey:(NSString *)appkey secretkey:(NSString *)secretkey {
    (void)provison;
    (void)appkey;
    (void)secretkey;
    YGSTLogf(YGST_LEVEL_INFO, @"updateProvision: cloud mode, no provision file needed");
    return YES;
}

- (BOOL)updateProvision:(NSString *)appkey secretkey:(NSString *)secretkey {
    return [self updateProvision:nil appkey:appkey secretkey:secretkey];
}

- (BOOL)updateProvision {
    return [self updateProvision:nil appkey:nil secretkey:nil];
}

- (BOOL)inquireProvision:(NSString *)provision inquireProvisionBlock:(void (^)(NSString *message))inquireProvisionBlock {
    void (^b)(NSString *) = [inquireProvisionBlock copy];
    (void)provision;
    if (b) {
        dispatch_async(dispatch_get_main_queue(), ^{
            b(YGSTProvisionJSON);
        });
    }
    return YES;
}

- (BOOL)inquireProvision:(void (^)(NSString *message))inquireProvisionBlock {
    return [self inquireProvision:nil inquireProvisionBlock:inquireProvisionBlock];
}

@end
