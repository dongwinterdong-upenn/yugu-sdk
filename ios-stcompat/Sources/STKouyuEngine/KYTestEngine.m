//
//  KYTestEngine.m
//  STKouyuEngine 平替层（优谷雅言）
//
//  Session state lives on one private serial queue; every callback is dispatched to the main
//  queue in order. A session ends with exactly one result or error JSON unless cancelEngine or
//  deleteEngine was called first, then with none. Since 2.1.1 recordings and 16 kHz mono PCM feeds
//  stream over the compat WebSocket (YGSTRealtime.m) while they are captured, unless
//  YuguCompat.streamUpload is NO; a failure before end evaluates the recording in one request after
//  stop with the same tokenId. With realtime_feedback eof 0 reading progress JSON comes before the
//  result through the same callbacks (2.1.0). At log level INFO one timing line follows every
//  evaluation that reached the platform. Since 2.2.0 an unsupported coreType is answered with
//  "不支持的评测内核：<coreType>" and one WARN line, or throws NSInvalidArgumentException from the start
//  methods with YuguCompat.throwOnUnsupportedCoreType; initEngine gives TtsClient and ReportClient the
//  credentials of the engine.
//
//  2.3.0 pipelined evaluation, YuguCompat.maxPendingEvaluations read at each start. 0, the default, is
//  one evaluation at a time as before: _session is the engine's session until its result. n above 0:
//  once the stop of the recording has been handled the session moves from _session to _pending and is
//  evaluated there, so the next start can record at once; ygst_pipe_decide_start (core) refuses a start
//  while _session records or while more than n sessions are pending. Every session keeps its own
//  tokenId, blocks, recording file, uploader or WebSocket round, retries and REST fallback; its reading
//  progress reaches its own callbacks until its final, while it waits for its result as well.
//  cancelEngine, deleteEngine and initEngine end the recording session and every pending one without a
//  callback.
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
/// 2.3.0: YuguCompat.maxPendingEvaluations when the session was started; above 0 it moves to the
/// pending sessions once its stop has been handled.
@property (nonatomic) NSInteger maxPending;
/// 2.3.0: the waitForStop of the finalizeCapture queued by stopCapture (phase Stopping).
@property (nonatomic) BOOL finalizeWaitsForStop;
/// 2.3.0: counted in YuguCompat.pendingEvaluations, from the evaluation request to its end.
@property (nonatomic) BOOL evaluationCounted;
@property (nonatomic) ygst_ct_status coreTypeStatus;
@property (nonatomic) unsigned long coreTypeEnum;
@property (nonatomic) BOOL configMissing;
/// 2.2.0: the 60003 text naming the coreType asked for, nil when the coreType is supported.
@property (nonatomic, copy) NSString *unsupportedMessage;
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
/// 2.1.1: the session streams over the compat WebSocket: recording or 16 kHz 16 bit mono PCM feed,
/// iOS 13, and YuguCompat.streamUpload or realtime_feedback.
@property (nonatomic) BOOL streamOverWebSocket;
/// realtime_feedback and the session streams: the platform sends reading progress.
@property (nonatomic) BOOL realtimeFeedback;
/// The compat WebSocket round while it runs; nil for REST only.
@property (nonatomic, strong) YGSTRealtimeRound *realtime;
/// The phases of this evaluation (2.1.1), written on the engine queue.
@property (nonatomic, strong) YGSTTiming *timing;
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
    YGSTSession *_session; // the session that records, or one at a time the session until its result
    NSMutableArray<YGSTSession *> *_pending; // 2.3.0: handed over, evaluating, in hand-over order
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
        _pending = [NSMutableArray array];
    }
    return self;
}

/// An engine released while evaluations run (one that was never deleted): its sessions can no longer
/// deliver, so they leave YuguCompat.pendingEvaluations. No block of the queue holds the engine here.
- (void)dealloc {
    if (_session.evaluationCounted) [[YGSTSettings shared] addPendingEvaluations:-1];
    for (YGSTSession *s in _pending) {
        if (s.evaluationCounted) [[YGSTSettings shared] addPendingEvaluations:-1];
    }
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
        // 2.3.0: the pipelined sessions waiting for their results, which the old keys signed, end as well
        for (YGSTSession *p in [self->_pending copy]) {
            YGSTLogf(YGST_LEVEL_WARN, @"initEngine while session %@ waits for its result: the session is cancelled",
                     p.tokenId);
            [self detachAndTeardown:p];
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
        // 2.2.0: TtsClient and ReportClient made with clientFromEngine use these
        YGSTEngineCredentials *credentials = [[YGSTEngineCredentials alloc] init];
        credentials.appKey = appKey;
        credentials.secretKey = secret;
        credentials.baseURL = baseURL;
        [[YGSTSettings shared] setEngineCredentials:credentials owner:(__bridge const void *)self];
        YGSTLogf(YGST_LEVEL_INFO, @"engine ready: appKey %@, platform %@, sdk %s", YGSTMaskedAppKey(appKey),
                 [self effectiveBaseURL], YGST_USER_AGENT);
    } else {
        [[YGSTSettings shared] clearEngineCredentialsOfOwner:(__bridge const void *)self];
        YGSTLogf(YGST_LEVEL_ERROR, @"init engine failed: %@", message);
    }
    if (fb) {
        dispatch_async(dispatch_get_main_queue(), ^{
            fb(ok, message);
        });
    }
}

// ------------------------------------------------------------------ start

/// 2.2.0: the 60003 text of a config whose coreType the platform does not evaluate, nil when it does.
/// It names coreTypeNS trimmed, else the KYTestType's string, else "KYTestType <n>"; "未指定" for no config.
static NSString *YGSTUnsupportedMessageForConfig(KYTestConfig *c) {
    char coreType[128];
    ygst_buf requested;
    NSString *message;
    if (!c) return YGSTUnsupportedCoreTypeMessage(nil);
    if (ygst_coretype_resolve(c.coreTypeNS.UTF8String, (unsigned long)c.coreType, coreType, sizeof coreType) ==
        YGST_CT_SUPPORTED) {
        return nil;
    }
    ygst_buf_init(&requested);
    ygst_coretype_requested(c.coreTypeNS.UTF8String, (unsigned long)c.coreType, &requested);
    message = YGSTUnsupportedCoreTypeMessage(YGSTStringFromBuf(&requested));
    ygst_buf_free(&requested);
    return message;
}

/// 2.2.0, YuguCompat.throwOnUnsupportedCoreType: an unsupported coreType throws on the calling thread
/// before anything starts (no session, no microphone, no request, no callback), with the 60003 text.
static void YGSTThrowIfUnsupported(KYTestConfig *c) {
    NSString *message;
    if (![YGSTSettings shared].throwOnUnsupportedCoreType) return;
    message = YGSTUnsupportedMessageForConfig(c);
    if (!message) return;
    YGSTLogf(YGST_LEVEL_WARN, @"start rejected: errId %d, %@, thrown as NSInvalidArgumentException "
                              @"(YuguCompat.throwOnUnsupportedCoreType)",
             YGST_ERRID_CORETYPE_UNSUPPORTED, message);
    @throw [NSException exceptionWithName:NSInvalidArgumentException reason:message userInfo:nil];
}

- (NSString *)startEngineWithTestConfig:(KYTestConfig *)testConfig
                                 result:(KYTestResultBlock)testResultBlock
                            finishBlock:(void (^)(BOOL isSuccess, NSString *str))finishBlock {
    YGSTSession *s;
    YGSTThrowIfUnsupported(testConfig);
    s = [self newSessionFromConfig:testConfig];
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
    YGSTSession *s;
    YGSTThrowIfUnsupported(testConfig);
    s = [self newSessionFromConfig:testConfig];
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
    s.timing = [[YGSTTiming alloc] init];
    s.maxPending = [YGSTSettings shared].maxPendingEvaluations; // 2.3.0: read when the session starts
    if (!c) {
        s.configMissing = YES;
        s.unsupportedMessage = YGSTUnsupportedCoreTypeMessage(nil);
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
    s.unsupportedMessage = s.coreTypeStatus == YGST_CT_SUPPORTED ? nil : YGSTUnsupportedMessageForConfig(c);
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
    // 2.1.1: streaming is the default (YuguCompat.streamUpload); realtime_feedback asks for progress
    // on top and streams with streamUpload NO as well, as in 2.1.0
    if (c.realtime_feedback || [YGSTSettings shared].streamUpload) {
        s.streamOverWebSocket = [self streamEligible:s realtimeFeedback:c.realtime_feedback];
        s.realtimeFeedback = c.realtime_feedback && s.streamOverWebSocket;
    }

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

/// The round streams over the compat WebSocket while it is captured (2.1.1 by default, 2.1.0 with
/// realtime_feedback for reading progress). That needs audio as it is captured in 16 kHz 16 bit mono
/// PCM (recording, or feed of such PCM) and NSURLSessionWebSocketTask (iOS 13). Otherwise the round
/// is evaluated in one request after stop, with one INFO line on why; audioPath always is.
- (BOOL)streamEligible:(YGSTSession *)s realtimeFeedback:(BOOL)realtimeFeedback {
    NSString *why = nil;
    if (s.mode == YGSTModeFile) {
        if (realtimeFeedback) {
            why = @"realtime_feedback: audioPath is evaluated in one request, reading progress needs recording or feed";
        }
    } else if (s.mode == YGSTModeStream && !(ygst_audio_type_is_pcm(s.audioType.UTF8String) && s.sampleRate == 16000 &&
                                             s.channels == 1 && s.sampleBytes == 2)) {
        why = realtimeFeedback ? @"realtime_feedback: reading progress needs 16 kHz 16 bit mono PCM, this feed is "
                                 @"evaluated in one request at stopEngine"
                               : @"stream upload needs 16 kHz 16 bit mono PCM, this feed is evaluated in one request "
                                 @"at stopEngine";
    } else if (![YGSTRealtimeRound isAvailable]) {
        why = realtimeFeedback ? @"realtime_feedback needs iOS 13 or later for reading progress, the round is "
                                 @"evaluated in one request at stopEngine"
                               : @"stream upload needs iOS 13 or later, the recording is evaluated in one request at "
                                 @"stopEngine";
    } else {
        return YES;
    }
    if (why) YGSTLogf(YGST_LEVEL_INFO, @"%@", why);
    return NO;
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
    if (![self admitSession:s]) return;
    if ([s.coreProvideType isEqualToString:KYEngineNative]) {
        YGSTLogf(YGST_LEVEL_WARN, @"coreProvideType native: evaluated in the cloud");
    }
    invalid = s.configMissing ? YGST_ERRID_CORETYPE_UNSUPPORTED
                              : ygst_validate_request(s.coreTypeStatus, s.coreType.UTF8String, s.refText.UTF8String,
                                                      s.refPinyin.UTF8String);
    if (invalid) {
        // 2.2.0: the 60003 text names the coreType, in the error JSON and in the WARN line of reject:
        NSString *message = invalid == YGST_ERRID_CORETYPE_UNSUPPORTED ? s.unsupportedMessage : nil;
        NSString *what = invalid != YGST_ERRID_CORETYPE_UNSUPPORTED ? @"refText is empty"
                         : s.configMissing ? [YGSTNonEmpty(message) stringByAppendingString:@" (testConfig is nil)"]
                                           : YGSTNonEmpty(message);
        [self reject:s errId:invalid message:message reason:what delivery:YGSTRejectResult | YGSTRejectDelegate];
        return;
    }
    _session = s;
    if (s.maxPending > 0) {
        // 2.3.0: one INFO line per pipelined start with the sessions waiting for their results
        ygst_buf line;
        ygst_buf_init(&line);
        if (ygst_pipe_start_line(s.tokenId.UTF8String, (int)_pending.count, (int)s.maxPending, s.mode == YGSTModeFile,
                                 &line) == 0) {
            YGSTLogEmit(YGST_LEVEL_INFO, YGSTStringFromBuf(&line));
        }
        ygst_buf_free(&line);
    }
    YGSTLogf(YGST_LEVEL_INFO, @"session %@: %@ %@, %@", s.tokenId, s.coreType,
             s.mode == YGSTModeFile ? @"file" : (s.mode == YGSTModeStream ? @"stream" : @"microphone"),
             s.streamOverWebSocket ? (s.realtimeFeedback ? @"streamed with reading progress" : @"streamed")
                                   : @"one request after capture");
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

/// On the queue. 2.3.0, the busy rule (ygst_pipe_decide_start): YES when s may start now, otherwise s is
/// refused with 60008 as before 2.3.0. One at a time (s.maxPending 0) any session of the engine makes
/// the start busy. Pipelined, a stop of the recording session that is still being handled is finished
/// here first (the audio that reached the queue before this start is in the recording), so that a start
/// right after stopEngine records at once; the start is busy while a session records, is paused, waits
/// for permission or for stopEngine, and while more than s.maxPending sessions wait for their results.
- (BOOL)admitSession:(YGSTSession *)s {
    int pass;
    for (pass = 0; pass < 2; pass++) {
        YGSTSession *current = _session;
        ygst_pipe_decision d =
            ygst_pipe_decide_start((int)s.maxPending, current != nil, current.phase == YGSTPhaseStopping,
                                   (int)_pending.count);
        if (d == YGST_PIPE_START) return YES;
        if (d == YGST_PIPE_FINISH_STOP && pass == 0) {
            [self finalizeCapture:current waitForStop:current.finalizeWaitsForStop];
            continue;
        }
        if (d == YGST_PIPE_BUSY_PENDING) {
            ygst_buf line;
            ygst_buf_init(&line);
            if (ygst_pipe_busy_line((int)_pending.count, (int)s.maxPending, &line) == 0) {
                YGSTLogEmit(YGST_LEVEL_WARN, YGSTStringFromBuf(&line));
            }
            ygst_buf_free(&line);
        } else {
            // the running session owns the delegate, so only the blocks of this call hear about it
            YGSTLogf(YGST_LEVEL_WARN, @"start rejected: session %@ has not finished", current.tokenId);
        }
        [self reject:s errId:YGST_ERRID_ENGINE_BUSY message:nil reason:@"engine is busy" delivery:YGSTRejectStartFail];
        return NO;
    }
    return NO;
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
    // like existsAudioTrans on Android: the start succeeds, problems with the file come as results;
    // the timing line counts from this start as from stopEngine
    s.timing->_t.start_ms = YGSTMonotonicMs();
    s.timing->_t.stop_ms = s.timing->_t.start_ms;
    // 2.3.0: nothing to record, the evaluation is asked for now (pipelined: in the background at once)
    [self beginEvaluation:s];
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
    [self noteAudio:data session:s];
    [self evaluate:s audio:data extension:ext];
}

/// Audio of the evaluation for the timing line: the PCM of a WAV with its duration, else the bytes.
- (void)noteAudio:(NSData *)upload session:(YGSTSession *)s {
    ygst_wav_info info;
    s.timing->_t.audio_bytes = (long long)upload.length;
    s.timing->_t.audio_bytes_per_sec = 0;
    if (ygst_wav_parse(upload.bytes, upload.length, &info) == 0 && info.format == 1 && info.bits > 0) {
        s.timing->_t.audio_bytes = (long long)info.data_len;
        s.timing->_t.audio_bytes_per_sec = info.sample_rate * info.channels * (info.bits / 8);
    }
}

// ------------------------------------------------------------------ stream mode

- (void)startStreamSession:(YGSTSession *)s {
    if (_vadEnable) ygst_vad_init(&s->_vad, s.sampleRate, s.seek10ms > 0 ? s.seek10ms : _seek, s.refLength10ms);
    s.vadEnabled = _vadEnable && s.sampleBytes == 2 && s.channels == 1 && ygst_audio_type_is_pcm(s.audioType.UTF8String);
    s.phase = YGSTPhaseCapturing;
    s.captureStartMs = YGSTMonotonicMs();
    s.timing->_t.start_ms = s.captureStartMs;
    [self postStarted:s recording:YES];
    [self startRealtime:s];
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
        [s.realtime audioAppended:s.audio];
        if (s.vadEnabled && chunk.length) [self runVAD:s bytes:chunk];
        if (s.audio.length >= max && s.phase == YGSTPhaseCapturing) {
            YGSTLogf(YGST_LEVEL_WARN, @"stream reached the %d s limit, evaluating", YGST_MAX_AUDIO_MS / 1000);
            [self stopCapture:s waitForStop:NO];
        }
        return;
    }
    [s.audio appendData:chunk];
    [s.realtime audioAppended:s.audio];
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
    s.timing->_t.start_ms = s.captureStartMs;
    [self postStarted:s recording:YES];
    [self startTicks:s];
    [self startRealtime:s];
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
    [s.realtime audioAppended:s.audio];
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
    // the evaluation is asked for now: stopEngine marked it already, else VAD, duration or a limit
    if (!waitForStop && !s.timing->_t.stop_ms) s.timing->_t.stop_ms = YGSTMonotonicMs();
    s.phase = YGSTPhaseStopping;
    s.finalizeWaitsForStop = waitForStop; // 2.3.0: a pipelined start may finish the stop first
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
    [self noteAudio:upload session:s];
    written = [self writeRecording:upload toPath:file];
    if (written) _lastRecordPath = file;
    s.uploadAudio = upload;
    s.uploadExtension = ext;
    s.audio = [NSMutableData data];
    // 2.3.0: the stop is handled, the evaluation is asked for before the record-end callbacks run, so that
    // pendingEvaluations counts it there and a start from onRecordEndBlock records at once when pipelined
    if (!check && !waitForStop) [self beginEvaluation:s];
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
    [self submit:s audio:upload extension:ext];
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

/// Retries and autoRetry of one evaluation call: the session's policy with the YuguCompat settings.
- (ygst_retry_policy)policyForSession:(YGSTSession *)s {
    ygst_retry_policy policy = s->_policy;
    NSInteger retries = [YGSTSettings shared].maxRetries;
    policy.max_retries = (int)(retries < 0 ? 0 : (retries > 5 ? 5 : retries));
    policy.total_timeout_ms = (int)([YGSTSettings shared].totalTimeout * 1000.0);
    return policy;
}

/// Timeout of one attempt: the larger of serverTimeout and connectTimeout, in seconds.
- (NSTimeInterval)attemptTimeoutForSession:(YGSTSession *)s {
    NSTimeInterval timeout = s.serverTimeout > 0 ? s.serverTimeout : _serverTimeout;
    return timeout < _connectTimeout ? _connectTimeout : timeout;
}

// ------------------------------------------------------------------ realtime feedback

/// Starts the compat WebSocket round when capture starts; audio captured before the platform has
/// started goes out then.
- (void)startRealtime:(YGSTSession *)s {
    YGSTRealtimeRound *round;
    ygst_retry_policy policy;
    __weak KYTestEngine *weakSelf = self;
    __weak YGSTSession *weakSession = s;
    if (!s.streamOverWebSocket || s.realtime || s.cancelled) return;
    policy = [self policyForSession:s];
    s.timing->_t.mode = YGST_TM_WS;
    round = [[YGSTRealtimeRound alloc] initWithQueue:_q
                                             baseURL:[self effectiveBaseURL]
                                            coreType:s.coreType
                                              appKey:_appKey
                                              secret:_secretKey
                                             tokenId:s.tokenId
                                              userId:s.userId
                                             refText:s.refText
                                              fields:&s->_fields
                                              policy:&policy
                                      connectTimeout:_connectTimeout
                                      attemptTimeout:[self attemptTimeoutForSession:s]
                                    realtimeFeedback:s.realtimeFeedback
                                              timing:s.timing];
    round.progressHandler = ^(NSString *json) {
        [weakSelf deliverProgress:json session:weakSession];
    };
    round.resultHandler = ^(YGSTUploadResult *result) {
        YGSTSession *session = weakSession;
        if (session) [weakSelf uploadFinished:result session:session];
    };
    round.fallbackHandler = ^{
        YGSTSession *session = weakSession;
        if (session) [weakSelf evaluateAfterRealtimeFailed:session];
    };
    s.realtime = round;
    [round start];
    if (s.audio.length) [round audioAppended:s.audio];
}

/// stopEngine: a running WebSocket round sends end and delivers the final itself. Without one, or
/// when it failed before end, the round is evaluated in one request with the same tokenId.
- (void)submit:(YGSTSession *)s audio:(NSData *)audio extension:(NSString *)ext {
    [self beginEvaluation:s]; // forceRecord: stopEngine after the recording ended at duration
    if (s.realtime && [s.realtime finish]) {
        s.phase = YGSTPhaseEvaluating;
        return;
    }
    s.realtime = nil;
    [self evaluate:s audio:audio extension:ext];
}

/// The round failed before end while waiting to send it: REST evaluates the whole recording, for a
/// pipelined session waiting for its result as for the engine's session.
- (void)evaluateAfterRealtimeFailed:(YGSTSession *)s {
    if (![self isLive:s] || s.phase != YGSTPhaseEvaluating) return;
    s.realtime = nil;
    [self evaluate:s audio:s.uploadAudio extension:s.uploadExtension ?: @"wav"];
}

// ------------------------------------------------------------------ pipelined evaluation (2.3.0)

/// The engine's session, or a pipelined session waiting for its result, not cancelled.
- (BOOL)isLive:(YGSTSession *)s {
    if (!s || s.cancelled) return NO;
    return s == _session || [_pending indexOfObjectIdenticalTo:s] != NSNotFound;
}

/// The evaluation of s is asked for: its stop was handled, stopEngine ended a forceRecord recording, or
/// an audioPath session started. From here until its final is delivered or it is cancelled it counts in
/// YuguCompat.pendingEvaluations. Started pipelined, it moves from _session to the pending sessions,
/// and the next start may record. Idempotent.
- (void)beginEvaluation:(YGSTSession *)s {
    if (!s.evaluationCounted) {
        s.evaluationCounted = YES;
        [[YGSTSettings shared] addPendingEvaluations:1];
    }
    if (ygst_pipe_hands_over((int)s.maxPending) && _session == s) {
        _session = nil;
        [_pending addObject:s];
        YGSTLogf(YGST_LEVEL_DEBUG, @"session %@ evaluates in the background, %lu waiting for their results", s.tokenId,
                 (unsigned long)_pending.count);
    }
}

/// s delivered its final or was cancelled: it is neither the engine's session nor pending any more.
- (void)endEvaluation:(YGSTSession *)s {
    if (_session == s) _session = nil;
    [_pending removeObjectIdenticalTo:s];
    if (s.evaluationCounted) {
        s.evaluationCounted = NO;
        [[YGSTSettings shared] addPendingEvaluations:-1];
    }
}

/// eof 0 reading progress through the same callbacks as the result, until the session's final. 2.3.0: a
/// pipelined session waiting for its result keeps getting its own progress (the tail the platform sends
/// after the end frame included), as one at a time; nothing after its final, nothing to another session.
- (void)deliverProgress:(NSString *)json session:(YGSTSession *)s {
    if (![self isLive:s] || s.phase == YGSTPhaseDone) return;
    [self deliverScore:json session:s];
}

- (void)evaluate:(YGSTSession *)s audio:(NSData *)audio extension:(NSString *)ext {
    NSString *error = nil;
    YGSTRequest *req;
    YGSTUploader *uploader;
    ygst_retry_policy policy = [self policyForSession:s];
    NSTimeInterval timeout = [self attemptTimeoutForSession:s];
    __weak KYTestEngine *weakSelf = self;
    s.phase = YGSTPhaseEvaluating;
    [self beginEvaluation:s];
    // a round that streamed and failed before end falls back to this request
    s.timing->_t.mode = s.timing->_t.mode == YGST_TM_WS ? YGST_TM_WS_REST : YGST_TM_REST;
    req = [YGSTRequest requestWithBaseURL:[self effectiveBaseURL]
                                 coreType:s.coreType
                                   fields:&s->_fields
                                   timing:YGSTTimingWanted()
                                   secret:_secretKey
                                    audio:audio
                                extension:ext
                                    error:&error];
    if (!req) {
        [self complete:s json:YGSTErrorJSON(s.tokenId, 90010, error, _appKey)];
        return;
    }
    uploader = [[YGSTUploader alloc] initWithURLSession:YGSTSharedURLSession() queue:_q];
    uploader.timing = s.timing;
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
    int errId = result.success ? 0 : result.errId;
    if (![self isLive:s]) return; // cancelled, or replaced by initEngine
    s.uploader = nil;
    if (result.success) {
        json = YGSTResultJSON(result, s.tokenId, _appKey, s.userId, s.refText,
                              s.getParam ? [self paramsJSONForSession:s] : nil);
        if (!json) {
            errId = YGST_LOCAL_PROTOCOL;
            json = YGSTErrorJSON(s.tokenId, YGST_LOCAL_PROTOCOL, YGSTCompatMessage(YGST_LOCAL_PROTOCOL), _appKey);
        }
    } else {
        json = YGSTErrorJSON(s.tokenId, result.errId, result.message, _appKey);
    }
    s.timing->_t.parsed_ms = YGSTMonotonicMs();
    s.timing->_t.ok = errId == 0;
    s.timing->_t.err_id = errId;
    s.timing.recordId = errId == 0 ? result.recordId : nil; // 2.2.0: recordId=<id> of the timing line
    [self complete:s json:json];
}

- (void)complete:(YGSTSession *)s json:(NSString *)json {
    [self endEvaluation:s];
    s.phase = YGSTPhaseDone;
    s.uploadAudio = nil;
    [s.realtime cancel];
    s.realtime = nil;
    // answered locally (audio checks, request errors): no evaluation, no timing line
    if (!s.timing->_t.parsed_ms) s.timing->_t.mode = YGST_TM_NONE;
    [self deliverFinal:json session:s];
}

/// The final result or error JSON through the result callbacks, then (2.1.1) the timing line of the
/// evaluation on the engine queue, with the time the callbacks started on the main queue.
- (void)deliverFinal:(NSString *)json session:(YGSTSession *)s {
    dispatch_queue_t q = _q;
    [self post:s
         block:^(id<KYTestEngineDelegate> d) {
             int64_t delivered = YGSTMonotonicMs();
             if (s.resultBlock) s.resultBlock(json);
             if (s.onScore) s.onScore(json);
             if ([d respondsToSelector:@selector(kyTestEngineDidScore:)]) [d kyTestEngineDidScore:json];
             dispatch_async(q, ^{
                 s.timing->_t.delivered_ms = delivered;
                 [s.timing logForToken:s.tokenId];
             });
         }];
}

/// The result callbacks: testResultBlock or onScoreBlock, and kyTestEngineDidScore:.
- (void)deliverScore:(NSString *)json session:(YGSTSession *)s {
    [self post:s
         block:^(id<KYTestEngineDelegate> d) {
             if (s.resultBlock) s.resultBlock(json);
             if (s.onScore) s.onScore(json);
             if ([d respondsToSelector:@selector(kyTestEngineDidScore:)]) [d kyTestEngineDidScore:json];
         }];
}

- (void)stopEngine {
    int64_t pressed = YGSTMonotonicMs(); // the timing line counts from here
    dispatch_async(_q, ^{
        YGSTSession *s = self->_session;
        if (!s) {
            YGSTLogf(YGST_LEVEL_DEBUG, @"stopEngine: no session");
            return;
        }
        switch (s.phase) {
        case YGSTPhaseStarting:
            s.stopRequested = YES;
            s.timing->_t.stop_ms = pressed;
            break;
        case YGSTPhaseCapturing:
        case YGSTPhasePaused:
            s.timing->_t.stop_ms = pressed;
            [self stopCapture:s waitForStop:NO];
            break;
        case YGSTPhaseCaptured:
            s.timing->_t.stop_ms = pressed;
            [self submit:s audio:s.uploadAudio extension:s.uploadExtension ?: @"wav"];
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
    // the recorder belongs to the engine's session; a pipelined session waiting for its result leaves it
    // to the session that records now
    BOOL own = _session == s;
    s.cancelled = YES;
    [self endEvaluation:s];
    [self stopTicks:s];
    if (own && s.mode == YGSTModeMicrophone && (s.phase == YGSTPhaseCapturing || s.phase == YGSTPhasePaused ||
                                                s.phase == YGSTPhaseStarting)) {
        [_recorder stop];
    }
    if (own && s.mode == YGSTModeMicrophone) [self releaseRecorderHandlers];
    [s.uploader cancel];
    s.uploader = nil;
    [s.realtime cancel]; // closes the connection: without end the platform evaluates nothing
    s.realtime = nil;
    s.phase = YGSTPhaseDone;
    YGSTLogf(YGST_LEVEL_INFO, @"session %@ cancelled", s.tokenId);
}

/// The engine's session and (2.3.0) every pipelined session waiting for its result: none of them calls
/// back afterwards, pendingEvaluations drops by as many.
- (void)teardownAll {
    YGSTSession *s = _session;
    if (s) [self detachAndTeardown:s];
    for (YGSTSession *p in [_pending copy]) [self detachAndTeardown:p];
}

- (void)cancelEngine {
    [self onQueueSync:^{
        [self teardownAll];
    }];
}

- (void)deleteEngine {
    [self onQueueSync:^{
        [self teardownAll];
        [self->_recorder stop];
        self->_recorder = nil;
        self->_initialized = NO;
        self->_appKey = @"";
        self->_secretKey = @"";
    }];
    [[YGSTSettings shared] clearEngineCredentialsOfOwner:(__bridge const void *)self];
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
