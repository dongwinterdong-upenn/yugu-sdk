// Copyright 2026 优谷雅言 open.shengzhiai.com
// SPDX-License-Identifier: Apache-2.0
//
// Objective-C checks called from Tests/STKouyuEngineTests. See the header for why they are here.

#import "include/STKouyuEngineObjCSupport.h"

#import <STKouyuEngine/KYTestEngine.h>
#import <STKouyuEngine/STKouyuEngine.h>
#import <STKouyuEngine/YuguCompat.h>
#import <STKouyuEngine/skegn.h>
@import STKouyuEngine;

#include <stdlib.h>
#include <string.h>

#define EXPECT(cond, msg)                                                                     \
    do {                                                                                      \
        if (!(cond)) return [NSString stringWithFormat:@"%s:%d %@", __FILE__, __LINE__, (msg)]; \
    } while (0)

@interface YGSTSupportDelegate : NSObject <KYTestEngineDelegate>
@property (nonatomic, strong) NSMutableArray<NSString *> *scores;
@end

@implementation YGSTSupportDelegate
- (instancetype)init {
    if ((self = [super init])) _scores = [NSMutableArray array];
    return self;
}
- (void)kyTestEngineDidScore:(NSString *)str {
    [self.scores addObject:str];
}
@end

// The semaphore is owned by a strong local of each check; the plain C struct only points at it so
// that memset, calloc and free stay valid under ARC.
typedef struct {
    __unsafe_unretained dispatch_semaphore_t done;
    char json[65536];
    int type;
} YGSTCallbackBox;

static int YGSTSupportCallback(const void *usrdata, const char *id, int type, const void *message, int size) {
    YGSTCallbackBox *box = (YGSTCallbackBox *)usrdata;
    (void)id;
    box->type = type;
    if (size > 0 && (size_t)size < sizeof box->json) {
        memcpy(box->json, message, (size_t)size);
        box->json[size] = 0;
    }
    dispatch_semaphore_signal(box->done);
    return 0;
}

static NSDictionary *YGSTParse(const char *json) {
    NSData *d = [NSData dataWithBytes:json length:strlen(json)];
    id o = [NSJSONSerialization JSONObjectWithData:d options:0 error:NULL];
    return [o isKindOfClass:[NSDictionary class]] ? o : @{};
}

/// Waits on the main run loop so that blocks dispatched to the main queue can run.
static BOOL YGSTSpin(BOOL (^done)(void), NSTimeInterval timeout) {
    NSDate *limit = [NSDate dateWithTimeIntervalSinceNow:timeout];
    while (!done() && [limit timeIntervalSinceNow] > 0) {
        [[NSRunLoop mainRunLoop] runUntilDate:[NSDate dateWithTimeIntervalSinceNow:0.02]];
    }
    return done();
}

NSString *YGSTCheckConstantsAndLog(void) {
    EXPECT([KY_CloudServer_Release isEqualToString:@"ws://api.stkouyu.com:8080"], @"KY_CloudServer_Release");
    EXPECT([KY_CloudServer_Gray isEqualToString:@"ws://gray.stkouyu.com:8090"], @"KY_CloudServer_Gray");
    EXPECT(KYLOG_ERROR == 0 && KYLOG_WARN == 1 && KYLOG_INFO == 2 && KYLOG_DEBUG == 3, @"KYLOG values");
    EXPECT([KYEngineCloud isEqualToString:@"cloud"] && [KYEngineNative isEqualToString:@"native"], @"engine names");
    EXPECT(STKouyuEngineVersionNumber == 2.1, @"version number");
    EXPECT(strstr((const char *)STKouyuEngineVersionString, "STKouyuEngine") != NULL, @"version string");
    EXPECT([YuguCompat.sdkVersion isEqualToString:@"2.1.1"], @"YuguCompat.sdkVersion");
    EXPECT([YuguCompat.userAgent isEqualToString:@"yugu-ios-stcompat-sdk/2.1.1"], @"YuguCompat.userAgent");
    EXPECT(KY_CloudEngine == 0 && KY_MultiEngine == 2 && KYTestType_Wordspell == 22, @"enum values");
    KYLog(KYLOG_INFO, @"KYLog from the Objective-C checks %d", 1);
    return nil;
}

NSString *YGSTCheckDelegateAndProvision(NSTimeInterval timeout) {
    KYTestEngine *engine = [[KYTestEngine alloc] init];
    YGSTSupportDelegate *d = [[YGSTSupportDelegate alloc] init];
    __block NSString *provision = nil;
    engine.delegate = d;
    EXPECT([engine updateProvision], @"updateProvision");
    EXPECT([engine updateProvision:@"ak" secretkey:@"sk"], @"updateProvision:secretkey:");
    EXPECT([engine inquireProvision:^(NSString *message) {
               provision = message;
           }],
           @"inquireProvision:");
    EXPECT(YGSTSpin(^BOOL {
               return provision != nil;
           },
                    timeout),
           @"inquireProvision block on the main queue");
    EXPECT([provision containsString:@"\"provision\":\"cloud\""], provision);
    EXPECT(![engine getEngineStatus], @"idle engine status");
    EXPECT([engine getLastRecordPath] != nil, @"getLastRecordPath never nil");
    [engine deleteEngine];
    [engine deleteEngine];
    return nil;
}

NSString *YGSTCheckSkegnLocalError(NSTimeInterval timeout) {
    // heap allocated and freed only on success: an early return must not leave the callback
    // writing into a dead stack frame
    YGSTCallbackBox *box = calloc(1, sizeof(YGSTCallbackBox));
    dispatch_semaphore_t sem = dispatch_semaphore_create(0);
    char token[64] = {0};
    struct skegn *engine = skegn_new("{\"appKey\":\"mock-app-key\",\"secretKey\":\"mock-secret-key\","
                                     "\"cloud\":{\"server\":\"http://127.0.0.1:9\"}}");
    EXPECT(box != NULL && engine != NULL, @"skegn_new");
    box->done = sem;
    EXPECT(skegn_start(engine, "{\"request\":{\"coreType\":\"open.eval\",\"refText\":\"x\"}}", token,
                       YGSTSupportCallback, box) == 0,
           @"skegn_start");
    EXPECT(strlen(token) == 32, @"tokenId length");
    EXPECT(dispatch_semaphore_wait(sem, dispatch_time(DISPATCH_TIME_NOW, (int64_t)(timeout * NSEC_PER_SEC))) == 0,
           @"callback for an unsupported coreType");
    EXPECT(box->type == SKEGN_MESSAGE_TYPE_JSON, @"message type");
    EXPECT([YGSTParse(box->json)[@"errId"] isEqual:@60003], [NSString stringWithUTF8String:box->json]);
    EXPECT(skegn_feed(engine, "ab", 2) == -1, @"feed after the local answer");
    EXPECT(skegn_delete(engine) == 0, @"skegn_delete");
    EXPECT(skegn_start(NULL, "{}", token, YGSTSupportCallback, box) == -1, @"start with a NULL engine");
    EXPECT(skegn_get_last_error() == 31, @"SGN_ENGINE_IS_NULL");
    free(box);
    return nil;
}

// ------------------------------------------------------------------ realtime_feedback (2.1.0)

#if TARGET_OS_IPHONE
#import <UIKit/UIKit.h>

// The example of README.md 朗读进度, word for word. Never called: the iOS builds compile it so that
// the example keeps compiling against the package.
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wunused-function"

/// 按 result.progress 给参考文本着色：已读且对上为绿色，已读未对上为橙色，未读为灰色
static NSAttributedString *ColoredRefText(NSString *refText, NSArray<NSDictionary *> *progress) {
    NSMutableAttributedString *text = [[NSMutableAttributedString alloc]
        initWithString:refText attributes:@{NSForegroundColorAttributeName : UIColor.grayColor}];
    NSUInteger from = 0;
    for (NSDictionary *unit in progress) {
        NSRange r = [refText rangeOfString:unit[@"word"] options:0 range:NSMakeRange(from, refText.length - from)];
        if (r.location == NSNotFound) break;
        from = NSMaxRange(r);
        if (![unit[@"read"] boolValue]) continue;
        [text addAttribute:NSForegroundColorAttributeName
                     value:[unit[@"matched"] boolValue] ? UIColor.greenColor : UIColor.orangeColor
                     range:r];
    }
    return text;
}

static void StartReadingWithProgress(UILabel *label) {
    KYTestConfig *test = [[KYTestConfig alloc] init];
    test.coreType = KYTestType_Sentence_Cn;
    test.refText = @"我是中国人，我是中国人。我们都是中国人。中华民族是一家人。";
    test.realtime_feedback = YES;
    label.attributedText = ColoredRefText(test.refText, @[]);
    [[KYTestEngine sharedInstance] startEngineWithTestConfig:test
        result:^(NSString *testResult) {
            NSData *data = [testResult dataUsingEncoding:NSUTF8StringEncoding];
            NSDictionary *json = [NSJSONSerialization JSONObjectWithData:data options:0 error:NULL];
            if ([json[@"eof"] integerValue] == 0) {
                label.attributedText = ColoredRefText(test.refText, json[@"result"][@"progress"]);
            } else if (json[@"errId"]) {
                NSLog(@"errId %@ %@", json[@"errId"], json[@"error"]);
            } else {
                NSLog(@"overall %@", json[@"result"][@"overall"]);
            }
        }
        finishBlock:nil];
}

#pragma clang diagnostic pop
#endif

static NSString *const kYGSTCustomerText = @"我是中国人，我是中国人。我们都是中国人。中华民族是一家人。";
static NSString *const kYGSTCustomerUnits = @"我是中国人我是中国人我们都是中国人中华民族是一家人";

/// A control call of the mock platform (reset, faults, log, billing); the parsed JSON answer.
static id YGSTMockCall(NSString *base, NSString *method, NSString *path, id body, NSTimeInterval timeout) {
    NSMutableURLRequest *req = [NSMutableURLRequest requestWithURL:[NSURL URLWithString:[base stringByAppendingString:path]]
                                                       cachePolicy:NSURLRequestReloadIgnoringLocalCacheData
                                                   timeoutInterval:timeout];
    dispatch_semaphore_t sem = dispatch_semaphore_create(0);
    __block NSData *answer = nil;
    req.HTTPMethod = method;
    if (body) {
        req.HTTPBody = [NSJSONSerialization dataWithJSONObject:body options:0 error:NULL];
        [req setValue:@"application/json" forHTTPHeaderField:@"Content-Type"];
    }
    [[[NSURLSession sharedSession] dataTaskWithRequest:req
                                     completionHandler:^(NSData *data, NSURLResponse *response, NSError *error) {
                                         (void)response;
                                         (void)error;
                                         answer = data;
                                         dispatch_semaphore_signal(sem);
                                     }] resume];
    dispatch_semaphore_wait(sem, dispatch_time(DISPATCH_TIME_NOW, (int64_t)(timeout * NSEC_PER_SEC)));
    return answer ? [NSJSONSerialization JSONObjectWithData:answer options:0 error:NULL] : nil;
}

/// The PCM of a WAV fixture: the bytes of its data chunk.
static NSData *YGSTFixturePCM(NSString *specDir, NSString *name) {
    NSString *path = [[specDir stringByAppendingPathComponent:@"fixtures/audio"] stringByAppendingPathComponent:name];
    NSData *wav = [NSData dataWithContentsOfFile:path];
    const unsigned char *b = wav.bytes;
    NSUInteger pos = 12;
    while (wav && pos + 8 <= wav.length) {
        uint32_t size = (uint32_t)b[pos + 4] | (uint32_t)b[pos + 5] << 8 | (uint32_t)b[pos + 6] << 16 |
                        (uint32_t)b[pos + 7] << 24;
        if (memcmp(b + pos, "data", 4) == 0) {
            return [wav subdataWithRange:NSMakeRange(pos + 8, MIN((NSUInteger)size, wav.length - pos - 8))];
        }
        pos += 8 + size + (size & 1);
    }
    return nil;
}

static KYTestEngine *YGSTMockEngine(NSString *baseURL, NSTimeInterval timeout) {
    KYTestEngine *engine = [[KYTestEngine alloc] init];
    KYStartEngineConfig *cfg = [[KYStartEngineConfig alloc] init];
    __block BOOL done = NO;
    __block BOOL ok = NO;
    cfg.appKey = @"mock-app-key";
    cfg.secretKey = @"mock-secret-key";
    cfg.server = baseURL;
    [engine initEngine:KY_CloudEngine
        startEngineConfig:cfg
              finishBlock:^(BOOL isSuccess, NSString *str) {
                  (void)str;
                  ok = isSuccess;
                  done = YES;
              }];
    YGSTSpin(^BOOL {
        return done;
    },
             timeout);
    return ok ? engine : nil;
}

/// What one realtime round delivered through the result block and the delegate.
@interface YGSTRealtimeRun : NSObject
@property (nonatomic, copy) NSString *tokenId;
@property (nonatomic, strong) NSMutableArray<NSDictionary *> *results;   // result block, parsed, in order
@property (nonatomic, strong) NSMutableArray<NSDictionary *> *progress;  // the eof 0 ones
@property (nonatomic, strong) NSDictionary *finalResult;                // the eof 1 one
@property (nonatomic, copy) NSString *words;                            // every delivered word, joined
@property (nonatomic, strong) NSArray<NSDictionary *> *items;           // every delivered words item
@property (nonatomic) NSUInteger delegateScores;
@property (nonatomic, strong) NSArray *log;
@property (nonatomic, strong) NSDictionary *billing;
@end

@implementation YGSTRealtimeRun
@end

/// A stream session over the mock: zh_progress.wav fed in steps of 6400 bytes, fault queued for the
/// first WebSocket handshake, stopEngine half a second after the last step, cancelEngine instead
/// after 32000 bytes when cancel is YES. realtimeFeedback NO is the 2.1.1 default stream upload.
static YGSTRealtimeRun *YGSTRunRound(NSString *base, NSString *specDir, NSString *fault, BOOL realtimeFeedback,
                                     BOOL cancel, NSTimeInterval timeout, NSString **failure) {
    YGSTRealtimeRun *run = [[YGSTRealtimeRun alloc] init];
    YGSTSupportDelegate *d = [[YGSTSupportDelegate alloc] init];
    NSData *pcm = YGSTFixturePCM(specDir, @"zh_progress.wav");
    KYTestConfig *test = [[KYTestConfig alloc] init];
    KYTestEngine *engine;
    NSMutableArray<NSDictionary *> *results = [NSMutableArray array];
    NSMutableArray<NSDictionary *> *progress = [NSMutableArray array];
    NSMutableArray<NSDictionary *> *items = [NSMutableArray array];
    NSMutableString *words = [NSMutableString string];
    __block NSDictionary *finalResult = nil;
    NSUInteger off;
    if (pcm.length < 64000) {
        *failure = @"fixture zh_progress.wav";
        return nil;
    }
    YGSTMockCall(base, @"POST", @"/__mock/reset", nil, timeout);
    if (fault) {
        YGSTMockCall(base, @"POST", @"/__mock/faults", @{@"faults" : @[ @{@"match" : @"/sent.eval.cn", @"fault" : fault} ]},
                     timeout);
    }
    engine = YGSTMockEngine(base, timeout);
    if (!engine) {
        *failure = @"initEngine";
        return nil;
    }
    engine.delegate = d;
    test.coreTypeNS = @"sent.eval.cn";
    test.refText = kYGSTCustomerText;
    test.isStream = YES;
    test.realtime_feedback = realtimeFeedback;
    run.tokenId = [engine startEngineWithTestConfig:test
                                             result:^(NSString *testResult) {
                                                 NSDictionary *j = YGSTParse(testResult.UTF8String);
                                                 [results addObject:j];
                                                 if ([j[@"eof"] integerValue] == 0) {
                                                     [progress addObject:j];
                                                     for (NSDictionary *w in j[@"result"][@"words"]) {
                                                         [items addObject:w];
                                                         [words appendString:w[@"word"] ?: @"?"];
                                                     }
                                                 } else {
                                                     finalResult = j;
                                                 }
                                             }
                                        finishBlock:^(BOOL isSuccess, NSString *str) {
                                            (void)isSuccess;
                                            (void)str;
                                        }];
    for (off = 0; off < pcm.length; off += 6400) {
        NSUInteger n = MIN((NSUInteger)6400, pcm.length - off);
        [engine feedAudioData:(void *)((const unsigned char *)pcm.bytes + off) audioLength:(int)n];
        YGSTSpin(^BOOL {
            return NO;
        },
                 0.02);
        if (cancel && off >= 32000) break;
    }
    if (cancel) {
        [engine cancelEngine];
        YGSTSpin(^BOOL {
            return NO;
        },
                 1.0);
    } else {
        YGSTSpin(^BOOL {
            return NO;
        },
                 0.5);
        [engine stopEngine];
        YGSTSpin(^BOOL {
            return finalResult != nil;
        },
                 timeout);
    }
    YGSTSpin(^BOOL {
        return NO;
    },
             0.2); // callbacks queued on the main queue behind the final
    run.results = results;
    run.progress = progress;
    run.finalResult = finalResult;
    run.words = words;
    run.items = items;
    run.delegateScores = d.scores.count;
    run.log = YGSTMockCall(base, @"GET", @"/__mock/log", nil, timeout);
    run.billing = YGSTMockCall(base, @"GET", @"/__mock/billing", nil, timeout);
    [engine deleteEngine];
    return run;
}

/// realtime_feedback (2.1.0): the round with reading progress.
static YGSTRealtimeRun *YGSTRunRealtime(NSString *base, NSString *specDir, NSString *fault, BOOL cancel,
                                        NSTimeInterval timeout, NSString **failure) {
    return YGSTRunRound(base, specDir, fault, YES, cancel, timeout, failure);
}

/// Checks every eof 0 JSON (envelope keys, recordId "", words) and that the words of all of them are
/// the units read, in order, each once.
static NSString *YGSTCheckProgressOf(YGSTRealtimeRun *run) {
    NSArray *keys = @[ @"tokenId", @"recordId", @"applicationId", @"userId", @"refText", @"eof", @"dtLastResponse", @"result" ];
    for (NSDictionary *p in run.progress) {
        NSArray *words = p[@"result"][@"words"];
        EXPECT(p.count == keys.count, @"eof 0 envelope members");
        for (NSString *k in keys) EXPECT(p[k] != nil, k);
        EXPECT([p[@"recordId"] isEqualToString:@""], @"recordId \"\"");
        EXPECT([p[@"tokenId"] isEqualToString:run.tokenId], @"tokenId");
        EXPECT([p[@"applicationId"] isEqualToString:@"mock-app-key"], @"applicationId");
        EXPECT([p[@"refText"] isEqualToString:kYGSTCustomerText], @"refText");
        EXPECT([words isKindOfClass:[NSArray class]] && words.count > 0, @"an eof 0 callback carries words");
        EXPECT(p[@"result"][@"progress"] != nil && p[@"result"][@"readUnits"] != nil, @"result passed through");
    }
    // Apple's hasPrefix: answers NO for an empty prefix, and a round cut before any progress has no words
    EXPECT(run.words.length == 0 || [kYGSTCustomerUnits hasPrefix:run.words], run.words);
    return nil;
}

static NSString *YGSTCheckFinalOf(YGSTRealtimeRun *run) {
    EXPECT(run.finalResult != nil, @"final result");
    EXPECT([run.finalResult[@"eof"] integerValue] == 1 && run.finalResult[@"errId"] == nil, [run.finalResult description]);
    EXPECT([run.finalResult[@"tokenId"] isEqualToString:run.tokenId], @"final tokenId");
    EXPECT([run.finalResult[@"recordId"] hasPrefix:@"eval_"], @"final recordId");
    EXPECT(run.finalResult[@"result"][@"overall"] != nil, @"final result.overall");
    EXPECT(run.results.lastObject == run.finalResult, @"the final is the last callback");
    EXPECT(run.delegateScores == run.results.count, @"kyTestEngineDidScore: gets the same JSON");
    EXPECT([run.billing[@"billed"] integerValue] == 1, @"billed once");
    return nil;
}

/// The mock log as (method, idempotencyKey, rejected) lines.
static NSString *YGSTLogLine(NSArray *log) {
    NSMutableString *s = [NSMutableString string];
    for (NSDictionary *e in log) {
        [s appendString:[NSString stringWithFormat:@"%@ %@ %@;", e[@"method"], e[@"idempotencyKey"], e[@"rejected"] ?: @"-"]];
    }
    return s;
}

NSString *YGSTCheckRealtimeProgress(NSString *baseURL, NSString *specDir, NSTimeInterval timeout) {
    NSString *failure = nil;
    YGSTRealtimeRun *run = YGSTRunRealtime(baseURL, specDir, nil, NO, timeout, &failure);
    NSString *why;
    NSString *want;
    if (!run) return failure;
    if ((why = YGSTCheckProgressOf(run))) return why;
    if ((why = YGSTCheckFinalOf(run))) return why;
    EXPECT(run.progress.count >= 3, @"progress while reading");
    EXPECT(run.words.length == 19, run.words); // one unit per 8000 bytes of the mock, one more after end
    want = [NSString stringWithFormat:@"WS %@ -;", run.tokenId];
    EXPECT([YGSTLogLine(run.log) isEqualToString:want], YGSTLogLine(run.log));
    EXPECT([run.billing[@"records"][0][@"op"] isEqualToString:@"ws-compat"], @"evaluated over the WebSocket");
    return nil;
}

NSString *YGSTCheckRealtimeRefusedFallsBack(NSString *baseURL, NSString *specDir, NSTimeInterval timeout) {
    NSString *failure = nil;
    YGSTRealtimeRun *run = YGSTRunRealtime(baseURL, specDir, @"ws-refuse", NO, timeout, &failure);
    NSString *why;
    NSString *want;
    if (!run) return failure;
    if ((why = YGSTCheckFinalOf(run))) return why;
    EXPECT(run.progress.count == 0, @"no progress without the WebSocket");
    want = [NSString stringWithFormat:@"WS %@ 503;POST %@ -;", run.tokenId, run.tokenId];
    EXPECT([YGSTLogLine(run.log) isEqualToString:want], YGSTLogLine(run.log));
    EXPECT([run.billing[@"records"][0][@"op"] isEqualToString:@"compat"], @"evaluated over REST");
    return nil;
}

NSString *YGSTCheckRealtimeKilledFallsBack(NSString *baseURL, NSString *specDir, NSTimeInterval timeout) {
    NSString *failure = nil;
    YGSTRealtimeRun *run = YGSTRunRealtime(baseURL, specDir, @"ws-kill-after:3", NO, timeout, &failure);
    NSString *why;
    NSString *want;
    if (!run) return failure;
    if ((why = YGSTCheckProgressOf(run))) return why;
    if ((why = YGSTCheckFinalOf(run))) return why;
    want = [NSString stringWithFormat:@"WS %@ -;POST %@ -;", run.tokenId, run.tokenId];
    EXPECT([YGSTLogLine(run.log) isEqualToString:want], YGSTLogLine(run.log));
    EXPECT([run.billing[@"records"][0][@"op"] isEqualToString:@"compat"], @"evaluated over REST at stop");
    return nil;
}

NSString *YGSTCheckRealtimeReconnectAfterEnd(NSString *baseURL, NSString *specDir, NSTimeInterval timeout) {
    NSString *failure = nil;
    YGSTRealtimeRun *run = YGSTRunRealtime(baseURL, specDir, @"ws-error:50200", NO, timeout, &failure);
    NSString *why;
    NSString *want;
    if (!run) return failure;
    if ((why = YGSTCheckProgressOf(run))) return why;
    if ((why = YGSTCheckFinalOf(run))) return why;
    EXPECT(run.words.length == 19, run.words); // the replay delivered no unit twice
    want = [NSString stringWithFormat:@"WS %@ -;WS %@ -;", run.tokenId, run.tokenId];
    EXPECT([YGSTLogLine(run.log) isEqualToString:want], YGSTLogLine(run.log));
    EXPECT([run.billing[@"records"][0][@"op"] isEqualToString:@"ws-compat"], @"evaluated over the WebSocket");
    return nil;
}

NSString *YGSTCheckRealtimeProgressMismatch(NSString *baseURL, NSString *specDir, NSTimeInterval timeout) {
    NSString *failure = nil;
    YGSTRealtimeRun *run = YGSTRunRealtime(baseURL, specDir, @"progress-mismatch:7", NO, timeout, &failure);
    NSString *why;
    NSUInteger i;
    if (!run) return failure;
    if ((why = YGSTCheckProgressOf(run))) return why;
    if ((why = YGSTCheckFinalOf(run))) return why;
    EXPECT(run.items.count > 8, @"unit 7 was read");
    for (i = 0; i < run.items.count; i++) {
        NSDictionary *scores = run.items[i][@"scores"];
        EXPECT([scores[@"skipped"] integerValue] == (i == 7 ? 1 : 0), [run.items[i] description]);
        EXPECT([scores[@"overall"] integerValue] == (i == 7 ? 0 : 100), [run.items[i] description]);
    }
    return nil;
}

NSString *YGSTCheckRealtimeProgressOff(NSString *baseURL, NSString *specDir, NSTimeInterval timeout) {
    NSString *failure = nil;
    YGSTRealtimeRun *run = YGSTRunRealtime(baseURL, specDir, @"progress-off", NO, timeout, &failure);
    NSString *why;
    if (!run) return failure;
    if ((why = YGSTCheckFinalOf(run))) return why;
    EXPECT(run.progress.count == 0, @"bytes-only progress frames are not delivered");
    EXPECT(run.results.count == 1, @"only the final");
    return nil;
}

NSString *YGSTCheckRealtimeCancel(NSString *baseURL, NSString *specDir, NSTimeInterval timeout) {
    NSString *failure = nil;
    YGSTRealtimeRun *run = YGSTRunRealtime(baseURL, specDir, nil, YES, timeout, &failure);
    if (!run) return failure;
    EXPECT(run.finalResult == nil, @"no result after cancelEngine");
    EXPECT([run.billing[@"billed"] integerValue] == 0, @"the platform evaluated nothing: no end was sent");
    return nil;
}

NSString *YGSTCheckSkegnAgainstMock(NSString *baseURL, NSString *specDir, NSTimeInterval timeout) {
    NSString *cfg = [NSString stringWithFormat:@"{\"appKey\":\"mock-app-key\",\"secretKey\":\"mock-secret-key\","
                                               @"\"cloud\":{\"server\":\"%@\"}}",
                                               baseURL];
    NSData *wav = [NSData dataWithContentsOfFile:[specDir stringByAppendingPathComponent:@"fixtures/audio/zh_short.wav"]];
    YGSTCallbackBox *box;
    dispatch_semaphore_t sem = dispatch_semaphore_create(0);
    char token[64] = {0};
    struct skegn *engine;
    NSUInteger off;
    NSDictionary *j;
    EXPECT(wav.length > 78, @"fixture audio");
    engine = skegn_new(cfg.UTF8String);
    EXPECT(engine != NULL, @"skegn_new");
    box = calloc(1, sizeof *box);
    box->done = sem;
    EXPECT(skegn_start(engine,
                       "{\"coreProvideType\":\"cloud\",\"app\":{\"userId\":\"u1\"},"
                       "\"audio\":{\"audioType\":\"wav\",\"sampleRate\":16000,\"channel\":1,\"sampleBytes\":2},"
                       "\"request\":{\"coreType\":\"sent.eval.cn\",\"refText\":\"今天天气很好\"}}",
                       token, YGSTSupportCallback, box) == 0,
           @"skegn_start");
    for (off = 78; off < wav.length; off += 640) {
        NSUInteger n = MIN((NSUInteger)640, wav.length - off);
        EXPECT(skegn_feed(engine, (const char *)wav.bytes + off, (int)n) == 0, @"skegn_feed");
    }
    EXPECT(skegn_stop(engine) == 0, @"skegn_stop");
    EXPECT(skegn_stop(engine) == -1, @"second skegn_stop");
    EXPECT(dispatch_semaphore_wait(sem, dispatch_time(DISPATCH_TIME_NOW, (int64_t)(timeout * NSEC_PER_SEC))) == 0,
           @"result callback");
    j = YGSTParse(box->json);
    EXPECT([j[@"tokenId"] isEqualToString:[NSString stringWithUTF8String:token]], @"tokenId");
    EXPECT([j[@"userId"] isEqualToString:@"u1"], @"userId");
    EXPECT(j[@"result"] != nil, [NSString stringWithUTF8String:box->json]);
    skegn_delete(engine);
    free(box);
    return nil;
}

// ------------------------------------------------------------------ stream upload and timing (2.1.1)

NSString *YGSTCheckStreamUploadDefault(NSString *baseURL, NSString *specDir, NSTimeInterval timeout) {
    NSString *failure = nil;
    YGSTRealtimeRun *run = YGSTRunRound(baseURL, specDir, nil, NO, NO, timeout, &failure);
    NSString *why;
    NSString *want;
    if (!run) return failure;
    if ((why = YGSTCheckFinalOf(run))) return why;
    EXPECT(run.progress.count == 0 && run.results.count == 1, @"without realtime_feedback only the final arrives");
    want = [NSString stringWithFormat:@"WS %@ -;", run.tokenId];
    EXPECT([YGSTLogLine(run.log) isEqualToString:want], YGSTLogLine(run.log));
    EXPECT([run.billing[@"records"][0][@"op"] isEqualToString:@"ws-compat"], @"streamed over the WebSocket");
    EXPECT([run.billing[@"records"][0][@"bytes"] unsignedIntegerValue] == YGSTFixturePCM(specDir, @"zh_progress.wav").length,
           @"every PCM byte reached the platform");
    return nil;
}

NSString *YGSTCheckStreamUploadOff(NSString *baseURL, NSString *specDir, NSTimeInterval timeout) {
    NSString *failure = nil;
    YGSTRealtimeRun *run;
    NSString *why;
    NSString *want;
    YuguCompat.streamUpload = NO;
    run = YGSTRunRound(baseURL, specDir, nil, NO, NO, timeout, &failure);
    YuguCompat.streamUpload = YES;
    if (!run) return failure;
    if ((why = YGSTCheckFinalOf(run))) return why;
    want = [NSString stringWithFormat:@"POST %@ -;", run.tokenId];
    EXPECT([YGSTLogLine(run.log) isEqualToString:want], YGSTLogLine(run.log));
    EXPECT([run.billing[@"records"][0][@"op"] isEqualToString:@"compat"], @"one request after stopEngine, as in 2.1.0");
    return nil;
}

NSString *YGSTCheckStreamRefusedFallsBack(NSString *baseURL, NSString *specDir, NSTimeInterval timeout) {
    NSString *failure = nil;
    YGSTRealtimeRun *run = YGSTRunRound(baseURL, specDir, @"ws-refuse", NO, NO, timeout, &failure);
    NSString *why;
    NSString *want;
    if (!run) return failure;
    if ((why = YGSTCheckFinalOf(run))) return why;
    EXPECT(run.results.count == 1, @"only the final");
    want = [NSString stringWithFormat:@"WS %@ 503;POST %@ -;", run.tokenId, run.tokenId];
    EXPECT([YGSTLogLine(run.log) isEqualToString:want], YGSTLogLine(run.log));
    EXPECT([run.billing[@"records"][0][@"op"] isEqualToString:@"compat"], @"REST after stopEngine with the same tokenId");
    return nil;
}

NSString *YGSTCheckRealtimeWithStreamUploadOff(NSString *baseURL, NSString *specDir, NSTimeInterval timeout) {
    NSString *failure = nil;
    YGSTRealtimeRun *run;
    NSString *why;
    NSString *want;
    YuguCompat.streamUpload = NO;
    run = YGSTRunRound(baseURL, specDir, nil, YES, NO, timeout, &failure);
    YuguCompat.streamUpload = YES;
    if (!run) return failure;
    if ((why = YGSTCheckProgressOf(run))) return why;
    if ((why = YGSTCheckFinalOf(run))) return why;
    EXPECT(run.progress.count >= 3, @"realtime_feedback streams with progress whatever streamUpload is");
    want = [NSString stringWithFormat:@"WS %@ -;", run.tokenId];
    EXPECT([YGSTLogLine(run.log) isEqualToString:want], YGSTLogLine(run.log));
    return nil;
}

/// Runs one evaluation and waits for its final JSON. feed: PCM fed as a stream session in steps of
/// 6400 bytes before stopEngine; nil for audioPath sessions.
static NSDictionary *YGSTEvaluateOnce(KYTestEngine *engine, KYTestConfig *test, NSData *feed, NSTimeInterval timeout,
                                      NSString **tokenId) {
    __block NSDictionary *final = nil;
    NSUInteger off;
    *tokenId = [engine startEngineWithTestConfig:test
                                          result:^(NSString *testResult) {
                                              NSDictionary *j = YGSTParse(testResult.UTF8String);
                                              if ([j[@"eof"] integerValue] != 0) final = j;
                                          }
                                     finishBlock:nil];
    for (off = 0; feed && off < feed.length; off += 6400) {
        NSUInteger n = MIN((NSUInteger)6400, feed.length - off);
        [engine feedAudioData:(void *)((const unsigned char *)feed.bytes + off) audioLength:(int)n];
        YGSTSpin(^BOOL {
            return NO;
        },
                 0.01);
    }
    if (feed) [engine stopEngine];
    YGSTSpin(^BOOL {
        return final != nil;
    },
             timeout);
    // the timing line follows the callback on the engine queue, the log file on the log queue
    YGSTSpin(^BOOL {
        return NO;
    },
             0.5);
    return final;
}

static KYTestEngine *YGSTEngineWithLog(NSString *baseURL, BOOL fileLog, CGFloat level, NSString *path,
                                       NSTimeInterval timeout) {
    KYTestEngine *engine = [[KYTestEngine alloc] init];
    KYStartEngineConfig *cfg = [[KYStartEngineConfig alloc] init];
    __block BOOL done = NO;
    __block BOOL ok = NO;
    cfg.appKey = @"mock-app-key";
    cfg.secretKey = @"mock-secret-key";
    cfg.server = baseURL;
    cfg.sdkLogEnable = fileLog;
    cfg.logLevel = level;
    cfg.sdkLogPath = path;
    cfg.isOutputLog = NO;
    [engine initEngine:KY_CloudEngine
        startEngineConfig:cfg
              finishBlock:^(BOOL isSuccess, NSString *str) {
                  (void)str;
                  ok = isSuccess;
                  done = YES;
              }];
    YGSTSpin(^BOOL {
        return done;
    },
             timeout);
    return ok ? engine : nil;
}

/// The mock log entry of the REST request with this Idempotency-Key.
static NSDictionary *YGSTMockPost(NSArray *log, NSString *tokenId) {
    for (NSDictionary *e in log) {
        if ([e[@"method"] isEqualToString:@"POST"] && [e[@"idempotencyKey"] isEqualToString:tokenId]) return e;
    }
    return nil;
}

/// The documented switch: sdkLogEnable YES with logLevel 2 writes INFO to sdkLogPath, with one timing
/// line per evaluation and timing=1 in the request; logLevel 1, the default, neither.
NSString *YGSTCheckLogLevelInfoTiming(NSString *baseURL, NSString *specDir, NSTimeInterval timeout) {
    NSString *path = [NSTemporaryDirectory()
        stringByAppendingPathComponent:[NSString stringWithFormat:@"ygst-sdkLog-%@.txt", [NSUUID UUID].UUIDString]];
    NSString *audioPath = [[specDir stringByAppendingPathComponent:@"fixtures/audio"] stringByAppendingPathComponent:@"zh_short.wav"];
    NSData *pcm = YGSTFixturePCM(specDir, @"zh_progress.wav");
    KYTestEngine *engine;
    KYTestConfig *file = [[KYTestConfig alloc] init];
    KYTestConfig *stream = [[KYTestConfig alloc] init];
    NSString *restToken = nil, *wsToken = nil, *quietToken = nil;
    NSDictionary *final;
    NSString *text;
    NSArray *log;
    file.coreTypeNS = @"sent.eval.cn";
    file.refText = @"今天天气很好";
    file.audioPath = audioPath;
    stream.coreTypeNS = @"sent.eval.cn";
    stream.refText = kYGSTCustomerText;
    stream.isStream = YES;
    YGSTMockCall(baseURL, @"POST", @"/__mock/reset", nil, timeout);
    [YuguCompat resetLogLevel];
    engine = YGSTEngineWithLog(baseURL, YES, 2, path, timeout);
    EXPECT(engine != nil, @"initEngine with sdkLogEnable");
    final = YGSTEvaluateOnce(engine, file, nil, timeout, &restToken);
    EXPECT(final[@"result"] != nil, @"file evaluation at INFO");
    final = YGSTEvaluateOnce(engine, stream, pcm, timeout, &wsToken);
    EXPECT(final[@"result"] != nil, @"streamed evaluation at INFO");
    [engine deleteEngine];
    // the default level: no timing line, no timing field
    engine = YGSTEngineWithLog(baseURL, YES, 1, path, timeout);
    EXPECT(engine != nil, @"initEngine at WARN");
    final = YGSTEvaluateOnce(engine, file, nil, timeout, &quietToken);
    EXPECT(final[@"result"] != nil, @"file evaluation at WARN");
    [engine deleteEngine];
    // back to the defaults for the other tests: no log file, WARN
    engine = YGSTEngineWithLog(baseURL, NO, 1, nil, timeout);
    [engine deleteEngine];
    text = [NSString stringWithContentsOfFile:path encoding:NSUTF8StringEncoding error:NULL] ?: @"";
    [[NSFileManager defaultManager] removeItemAtPath:path error:NULL];
    EXPECT(([text containsString:[NSString stringWithFormat:@"timing tokenId=%@ mode=rest audio=", restToken]]), text);
    EXPECT(([text containsString:[NSString stringWithFormat:@"timing tokenId=%@ mode=ws audio=", wsToken]]), text);
    EXPECT((![text containsString:[NSString stringWithFormat:@"timing tokenId=%@ ", quietToken]]), @"no timing line at WARN");
    EXPECT(![text containsString:@"mock-secret-key"], @"the secret never reaches the log");
    EXPECT([text containsString:@" [I] "], @"INFO lines in sdkLogPath");
    log = YGSTMockCall(baseURL, @"GET", @"/__mock/log", nil, timeout);
    EXPECT([YGSTMockPost(log, restToken)[@"fields"][@"timing"] isEqualToString:@"1"], @"timing=1 at INFO");
    EXPECT(YGSTMockPost(log, quietToken) != nil && YGSTMockPost(log, quietToken)[@"fields"][@"timing"] == nil,
           @"no timing field at WARN");
    return nil;
}

/// The INFO lines captured from YuguCompat's log handler.
@interface YGSTLogCapture : NSObject
@property (nonatomic, strong) NSMutableArray<NSString *> *lines;
- (NSString *)timingLineOf:(NSString *)tokenId;
@end

@implementation YGSTLogCapture
- (instancetype)init {
    if ((self = [super init])) _lines = [NSMutableArray array];
    return self;
}
- (NSString *)timingLineOf:(NSString *)tokenId {
    NSString *prefix = [NSString stringWithFormat:@"timing tokenId=%@ mode=", tokenId];
    @synchronized(self) {
        for (NSString *line in self.lines) {
            if ([line hasPrefix:prefix]) return line;
        }
    }
    return nil;
}
@end

static YGSTLogCapture *YGSTCaptureInfo(void) {
    YGSTLogCapture *capture = [[YGSTLogCapture alloc] init];
    __weak YGSTLogCapture *weakCapture = capture;
    [YuguCompat setLogLevel:YuguCompatLogLevelInfo];
    [YuguCompat setLogHandler:^(NSInteger level, NSString *message) {
        YGSTLogCapture *c = weakCapture;
        (void)level;
        if (!c) return;
        @synchronized(c) {
            [c.lines addObject:message];
        }
    }];
    return capture;
}

static void YGSTCaptureEnd(void) {
    [YuguCompat setLogHandler:nil];
    [YuguCompat resetLogLevel];
}

/// The last entry of the timing server's log of this kind.
static NSDictionary *YGSTServerEntry(NSString *baseURL, NSString *kind, NSTimeInterval timeout) {
    NSDictionary *found = nil;
    for (NSDictionary *e in YGSTMockCall(baseURL, @"GET", @"/__ctl/log", nil, timeout)) {
        if ([e[@"kind"] isEqualToString:kind]) found = e;
    }
    return found;
}

static NSString *YGSTText(id v) {
    return [v isKindOfClass:[NSString class]] ? v : @"(none)";
}

NSString *YGSTCheckTimingServerWebSocket(NSString *baseURL, NSString *specDir, BOOL deflate, NSTimeInterval timeout,
                                         NSString **report) {
    YGSTLogCapture *capture;
    KYTestEngine *engine;
    KYTestConfig *test = [[KYTestConfig alloc] init];
    NSData *pcm = YGSTFixturePCM(specDir, @"zh_progress.wav");
    NSString *token = nil;
    NSDictionary *final, *entry;
    NSString *line, *offered;
    BOOL compressed;
    EXPECT(pcm.length > 64000, @"fixture zh_progress.wav");
    YGSTMockCall(baseURL, @"POST", @"/__ctl/reset", nil, timeout);
    YGSTMockCall(baseURL, @"POST", @"/__ctl/config", @{@"resultWords" : @6000, @"deflate" : deflate ? @"accept" : @"decline"},
                 timeout);
    capture = YGSTCaptureInfo();
    engine = YGSTMockEngine(baseURL, timeout);
    test.coreTypeNS = @"para.eval";
    test.refText = @"x";
    test.isStream = YES;
    final = engine ? YGSTEvaluateOnce(engine, test, pcm, timeout, &token) : nil;
    [engine deleteEngine];
    line = [capture timingLineOf:token];
    YGSTCaptureEnd();
    entry = YGSTServerEntry(baseURL, @"ws", timeout);
    offered = YGSTText(entry[@"offered"]);
    compressed = [entry[@"finalCompressed"] boolValue];
    if (report) {
        *report = [NSString stringWithFormat:@"server deflate %@: URLSessionWebSocketTask offered %@, negotiated %@, "
                                             @"client messages compressed %@ of the parameter frame, audio and end, "
                                             @"final %@ bytes sent as %@ bytes in %@ frames (compressed %@); %@",
                                             deflate ? @"accept" : @"decline", offered, YGSTText(entry[@"negotiated"]),
                                             entry[@"clientCompressedMessages"], entry[@"finalBytes"],
                                             entry[@"finalWireBytes"], entry[@"finalFrames"], compressed ? @"yes" : @"no",
                                             line ?: @"no timing line"];
    }
    EXPECT(engine != nil, @"initEngine");
    EXPECT([final[@"eof"] integerValue] == 1 && final[@"errId"] == nil, [final description] ?: @"no final");
    EXPECT([final[@"recordId"] hasPrefix:@"eval_ws"], @"evaluated over the WebSocket");
    EXPECT([final[@"result"][@"words"] count] == 6000, @"the whole final arrived");
    EXPECT(final[@"timing"] == nil && final[@"result"][@"timing"] == nil, @"the timing object never reaches the app");
    EXPECT([entry[@"end"] boolValue], @"end sent");
    EXPECT(entry[@"params"][@"realtime_feedback"] == nil, @"no realtime_feedback key without progress");
    EXPECT([entry[@"params"][@"timing"] integerValue] == 1, @"\"timing\":1 at INFO");
    EXPECT([entry[@"audioBytes"] unsignedIntegerValue] == pcm.length, @"every byte streamed");
    // the system's own offer: what the server does with it is the platform's choice
    if (deflate && [offered containsString:@"permessage-deflate"]) {
        EXPECT([YGSTText(entry[@"negotiated"]) hasPrefix:@"permessage-deflate"] && compressed,
               @"offered and accepted: the final is compressed");
        EXPECT([entry[@"finalWireBytes"] integerValue] * 4 < [entry[@"finalBytes"] integerValue], @"compressed final");
        EXPECT([entry[@"finalFrames"] integerValue] > 1, @"fragmented like Tomcat");
    }
    if (!deflate) EXPECT(!compressed, @"declined: uncompressed");
    EXPECT(line != nil, @"timing line at INFO");
    EXPECT([line containsString:@" mode=ws audio="], line);
    EXPECT(([line containsString:[NSString stringWithFormat:@" final=%@B wire=- ", entry[@"finalBytes"]]]), line);
    EXPECT([line hasSuffix:@" server.total=321ms server.engine=300ms server.platform=21ms"], line);
    EXPECT(![line containsString:@" upload=- "] && ![line containsString:@" wait=- "] &&
               ![line containsString:@" handshake=- "] && ![line containsString:@" errId="],
           line);
    // the upgrade response tells whether the system's offer was taken
    EXPECT([line containsString:@" deflate "] == [entry[@"negotiated"] isKindOfClass:[NSString class]], line);
    return nil;
}

NSString *YGSTCheckTimingServerRest(NSString *baseURL, NSString *specDir, NSTimeInterval timeout, NSString **report) {
    YGSTLogCapture *capture;
    KYTestEngine *engine;
    KYTestConfig *test = [[KYTestConfig alloc] init];
    NSString *token = nil;
    NSDictionary *final, *entry;
    NSString *line;
    YGSTMockCall(baseURL, @"POST", @"/__ctl/reset", nil, timeout);
    YGSTMockCall(baseURL, @"POST", @"/__ctl/config", @{@"resultWords" : @6000, @"gzip" : @YES}, timeout);
    capture = YGSTCaptureInfo();
    engine = YGSTMockEngine(baseURL, timeout);
    test.coreTypeNS = @"para.eval";
    test.refText = @"x";
    test.audioPath = [[specDir stringByAppendingPathComponent:@"fixtures/audio"] stringByAppendingPathComponent:@"zh_short.wav"];
    final = engine ? YGSTEvaluateOnce(engine, test, nil, timeout, &token) : nil;
    [engine deleteEngine];
    line = [capture timingLineOf:token];
    YGSTCaptureEnd();
    entry = YGSTServerEntry(baseURL, @"rest", timeout);
    if (report) {
        *report = [NSString stringWithFormat:@"REST: Accept-Encoding %@, gzip %@, body %@ bytes, %@ bytes on the wire; %@",
                                             YGSTText(entry[@"acceptEncoding"]), [entry[@"gzip"] boolValue] ? @"yes" : @"no",
                                             entry[@"bodyBytes"], entry[@"wireBytes"], line ?: @"no timing line"];
    }
    EXPECT(engine != nil, @"initEngine");
    EXPECT([final[@"eof"] integerValue] == 1 && [final[@"recordId"] hasPrefix:@"eval_rest"], [final description] ?: @"no final");
    EXPECT([final[@"result"][@"words"] count] == 6000, @"the whole body arrived, decoded");
    EXPECT(final[@"timing"] == nil, @"the timing object never reaches the app");
    EXPECT([entry[@"fields"][@"timing"] isEqualToString:@"1"], @"timing=1 at INFO, signed with the other fields");
    EXPECT([entry[@"gzip"] boolValue], @"NSURLSession asks for gzip by itself");
    EXPECT(line != nil, @"timing line at INFO");
    EXPECT([line containsString:@" mode=rest audio="], line);
    // NSURLSessionTaskMetrics: the split of the attempt, the decoded and the compressed size
    EXPECT(([line containsString:[NSString stringWithFormat:@" body=%@B wire=%@B gzip ", entry[@"bodyBytes"],
                                                            entry[@"wireBytes"]]]),
           line);
    EXPECT(![line containsString:@" upload=- "] && ![line containsString:@" wait=- "] &&
               ![line containsString:@" download=- "] && ![line containsString:@" afterStop=- "],
           line);
    EXPECT([line hasSuffix:@" server.total=321ms server.engine=300ms server.platform=21ms"], line);
    return nil;
}
