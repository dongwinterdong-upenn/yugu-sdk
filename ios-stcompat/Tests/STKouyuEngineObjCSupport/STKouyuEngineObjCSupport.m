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
    EXPECT(STKouyuEngineVersionNumber == 2.2, @"version number");
    EXPECT(strstr((const char *)STKouyuEngineVersionString, "STKouyuEngine") != NULL, @"version string");
    EXPECT(strstr((const char *)STKouyuEngineVersionString, "yugu-ios-stcompat-2.2.0") != NULL, @"version string 2.2.0");
    EXPECT([YuguCompat.sdkVersion isEqualToString:@"2.2.0"], @"YuguCompat.sdkVersion");
    EXPECT([YuguCompat.userAgent isEqualToString:@"yugu-ios-stcompat-sdk/2.2.0"], @"YuguCompat.userAgent");
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
    NSString *restRecord = @"-", *wsRecord = @"-";
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
    restRecord = final[@"recordId"] ?: @"-";
    final = YGSTEvaluateOnce(engine, stream, pcm, timeout, &wsToken);
    EXPECT(final[@"result"] != nil, @"streamed evaluation at INFO");
    wsRecord = final[@"recordId"] ?: @"-";
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
    // 2.2.0: recordId of the result right after tokenId
    EXPECT(([text containsString:[NSString stringWithFormat:@"timing tokenId=%@ recordId=%@ mode=rest audio=", restToken,
                                                            restRecord]]),
           text);
    EXPECT(([text containsString:[NSString stringWithFormat:@"timing tokenId=%@ recordId=%@ mode=ws audio=", wsToken,
                                                            wsRecord]]),
           text);
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
    NSString *prefix = [NSString stringWithFormat:@"timing tokenId=%@ recordId=", tokenId]; // 2.2.0
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
    EXPECT(([line containsString:[NSString stringWithFormat:@" recordId=%@ mode=", final[@"recordId"]]]), line);
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
    EXPECT(([line containsString:[NSString stringWithFormat:@" recordId=%@ mode=", final[@"recordId"]]]), line);
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

// ------------------------------------------------------------------ unsupported coreType (2.2.0)

/// Every log line through YuguCompat's handler at DEBUG, as "<level> <message>".
@interface YGSTLineCapture : NSObject
@property (nonatomic, strong) NSMutableArray<NSString *> *lines;
/// How many lines contain every one of the texts.
- (NSUInteger)countWith:(NSArray<NSString *> *)texts;
@end

@implementation YGSTLineCapture
- (instancetype)init {
    if ((self = [super init])) _lines = [NSMutableArray array];
    return self;
}
- (NSUInteger)countWith:(NSArray<NSString *> *)texts {
    NSUInteger n = 0;
    @synchronized(self) {
        for (NSString *line in self.lines) {
            BOOL all = YES;
            for (NSString *t in texts) {
                if (![line containsString:t]) all = NO;
            }
            if (all) n++;
        }
    }
    return n;
}
@end

/// Captures until YGSTCaptureEnd.
static YGSTLineCapture *YGSTCaptureLines(void) {
    YGSTLineCapture *capture = [[YGSTLineCapture alloc] init];
    __weak YGSTLineCapture *weakCapture = capture;
    [YuguCompat setLogLevel:YuguCompatLogLevelDebug];
    [YuguCompat setLogHandler:^(NSInteger level, NSString *message) {
        YGSTLineCapture *c = weakCapture;
        if (!c) return;
        @synchronized(c) {
            [c.lines addObject:[NSString stringWithFormat:@"%ld %@", (long)level, message]];
        }
    }];
    return capture;
}

static NSString *const kYGSTUnsupported = @"不支持的评测内核：";
static const char *const kYGSTPort9 = "{\"appKey\":\"mock-app-key\",\"secretKey\":\"mock-secret-key\","
                                      "\"cloud\":{\"server\":\"http://127.0.0.1:9\"}}";

/// One start of KYTestEngine; the parsed result JSON, and the finishBlock JSON ({} for YES).
static NSDictionary *YGSTStartOnce(KYTestEngine *engine, KYTestConfig *test, BOOL withSteps, NSTimeInterval timeout,
                                   NSString **tokenId, NSDictionary **finishJSON) {
    __block NSDictionary *got = nil;
    __block NSDictionary *finished = nil;
    void (^result)(NSString *) = ^(NSString *r) {
        got = YGSTParse(r.UTF8String);
    };
    void (^finish)(BOOL, NSString *) = ^(BOOL ok, NSString *str) {
        finished = ok ? @{} : YGSTParse(str.UTF8String);
    };
    if (withSteps) {
        *tokenId = [engine startEngineWithTestConfig:test
                                        onStartBlock:nil
                                    onStartFailBlock:nil
                                        onPauseBlock:nil
                                         onTickBlock:nil
                                    onRecordingBlock:nil
                                    onRecordEndBlock:nil
                                        onScoreBlock:result
                                         finishBlock:finish];
    } else {
        *tokenId = [engine startEngineWithTestConfig:test result:result finishBlock:finish];
    }
    YGSTSpin(^BOOL {
        return got != nil && finished != nil;
    },
             timeout);
    *finishJSON = finished;
    return got;
}

static NSString *YGSTUnsupportedChecks(KYTestEngine *engine, YGSTLineCapture *capture, NSTimeInterval timeout) {
    NSArray<NSArray *> *cases = @[
        @[ @"open.eval", @(KYTestType_Sentence), @"open.eval" ],
        @[ @"  sent.eval.fr \n", @(KYTestType_Sentence), @"sent.eval.fr" ],
        @[ @"my.core", @(KYTestType_Word), @"my.core" ],
        @[ @"", @(KYTestType_Open), @"open.eval" ],
        @[ @" ", @(KYTestType_Paragraph_jp), @"para.eval.jp" ],
        @[ @"", @(KYTestType_Wordspell), @"KYTestType 22" ],
    ];
    NSUInteger i;
    EXPECT(engine != nil, @"initEngine");
    EXPECT(!YuguCompat.throwOnUnsupportedCoreType, @"throwOnUnsupportedCoreType is NO by default");
    for (i = 0; i < cases.count * 2; i++) {
        NSArray *c = cases[i % cases.count];
        KYTestConfig *test = [[KYTestConfig alloc] init];
        NSString *token = nil;
        NSDictionary *finish = nil;
        NSDictionary *got;
        NSString *want = [kYGSTUnsupported stringByAppendingString:c[2]];
        test.coreTypeNS = c[0];
        test.coreType = (KYTestType)[c[1] unsignedIntegerValue];
        test.refText = @"x";
        got = YGSTStartOnce(engine, test, i >= cases.count, timeout, &token, &finish);
        EXPECT([got[@"errId"] isEqual:@60003] && [got[@"error"] isEqualToString:want], got ? [got description] : want);
        EXPECT(got.count == 5 && [got[@"tokenId"] isEqualToString:token] && [got[@"eof"] isEqual:@1] &&
                   [got[@"applicationId"] isEqualToString:@"mock-app-key"],
               [got description]);
        EXPECT([finish[@"error"] isEqualToString:want], @"finishBlock NO with the same error JSON");
        // never silent: one WARN line naming the coreType and errId 60003
        EXPECT(([capture countWith:@[ @"1 ", token, @"errId 60003", want ]] == 1),
               ([NSString stringWithFormat:@"WARN line of %@: %@", want, capture.lines]));
    }
    {
        NSString *token = nil;
        NSDictionary *finish = nil;
        NSDictionary *got = YGSTStartOnce(engine, nil, NO, timeout, &token, &finish);
        NSString *want = [kYGSTUnsupported stringByAppendingString:@"未指定"];
        EXPECT([got[@"errId"] isEqual:@60003] && [got[@"error"] isEqualToString:want], [got description]);
        EXPECT(([capture countWith:@[ @"1 ", token, want ]] == 1), @"WARN line of a nil testConfig");
    }
    return nil;
}

NSString *YGSTCheckUnsupportedCoreType(NSTimeInterval timeout) {
    // nothing listens on port 9: a request would end in errId 20009, not 60003
    KYTestEngine *engine = YGSTMockEngine(@"http://127.0.0.1:9", timeout);
    YGSTLineCapture *capture = YGSTCaptureLines();
    NSString *failure = YGSTUnsupportedChecks(engine, capture, timeout);
    YGSTCaptureEnd();
    [engine deleteEngine];
    return failure;
}

static NSString *YGSTSkegnUnsupportedChecks(struct skegn *engine, YGSTCallbackBox *box, dispatch_semaphore_t sem,
                                            YGSTLineCapture *capture, NSTimeInterval timeout) {
    NSArray<NSArray<NSString *> *> *cases = @[
        @[ @"{\"request\":{\"coreType\":\" open.eval \",\"refText\":\"x\"}}", @"open.eval" ],
        @[ @"{\"request\":{\"coreType\":\"asr.rec\",\"refText\":\"x\"}}", @"asr.rec" ],
        @[ @"{\"request\":{\"coreType\":\"   \",\"refText\":\"x\"}}", @"未指定" ],
        @[ @"{\"request\":{\"refText\":\"x\"}}", @"未指定" ],
    ];
    EXPECT(engine != NULL, @"skegn_new");
    for (NSArray<NSString *> *c in cases) {
        char token[64] = {0};
        NSDictionary *j;
        NSString *want = [kYGSTUnsupported stringByAppendingString:c[1]];
        int feed, stop, feedError, stopError;
        EXPECT(skegn_start(engine, c[0].UTF8String, token, YGSTSupportCallback, box) == 0 && strlen(token) == 32,
               @"skegn_start returns 0 with the switch off");
        EXPECT(dispatch_semaphore_wait(sem, dispatch_time(DISPATCH_TIME_NOW, (int64_t)(timeout * NSEC_PER_SEC))) == 0,
               @"the 60003 callback comes at once, before skegn_stop");
        j = YGSTParse(box->json);
        EXPECT([j[@"errId"] isEqual:@60003] && [j[@"error"] isEqualToString:want], [NSString stringWithUTF8String:box->json]);
        feed = skegn_feed(engine, "ab", 2);
        feedError = skegn_get_last_error();
        stop = skegn_stop(engine);
        stopError = skegn_get_last_error();
        EXPECT(feed == -1 && feedError == 21, @"feed after the answer: -1, SGN_FUNCTION_FEED_BEFORE_START");
        EXPECT(stop == -1 && stopError == 24, @"stop after the answer: -1, SGN_FUNCTION_STOP_AND_STOP");
        EXPECT(([capture countWith:@[ @"1 ", [NSString stringWithUTF8String:token], @"errId 60003", want ]] == 1),
               ([NSString stringWithFormat:@"WARN line of skegn_start: %@", capture.lines]));
    }
    return nil;
}

NSString *YGSTCheckSkegnUnsupportedCoreType(NSTimeInterval timeout) {
    // heap allocated and freed only when no callback can come any more
    YGSTCallbackBox *box = calloc(1, sizeof(YGSTCallbackBox));
    dispatch_semaphore_t sem = dispatch_semaphore_create(0);
    struct skegn *engine = skegn_new(kYGSTPort9);
    YGSTLineCapture *capture = YGSTCaptureLines();
    NSString *failure;
    if (!box) return @"calloc";
    box->done = sem;
    failure = YGSTSkegnUnsupportedChecks(engine, box, sem, capture, timeout);
    YGSTCaptureEnd();
    if (engine) skegn_delete(engine);
    if (!failure) free(box);
    return failure;
}

/// The checks of YuguCompat.throwOnUnsupportedCoreType YES; the caller switches it on and off.
static NSString *YGSTThrowChecks(KYTestEngine *engine, struct skegn *skegnEngine, YGSTCallbackBox *box,
                                 dispatch_semaphore_t sem, NSTimeInterval timeout) {
    KYTestEngine *idle = [[KYTestEngine alloc] init]; // never initialized
    __block NSUInteger callbacks = 0;
    void (^result)(NSString *) = ^(NSString *r) {
        (void)r;
        callbacks++;
    };
    void (^finish)(BOOL, NSString *) = ^(BOOL ok, NSString *str) {
        (void)ok;
        (void)str;
        callbacks++;
    };
    NSArray<NSArray *> *cases = @[
        @[ @"open.eval", @(KYTestType_Sentence), @"open.eval" ],
        @[ @"", @(KYTestType_Choice), @"choice.rec" ],
        @[ @"", @(KYTestType_Wordspell), @"KYTestType 22" ],
    ];
    NSUInteger i;
    EXPECT(engine != nil && skegnEngine != NULL, @"initEngine and skegn_new");
    for (i = 0; i < cases.count * 2; i++) {
        NSArray *c = cases[i % cases.count];
        KYTestConfig *test = [[KYTestConfig alloc] init];
        NSString *name = nil;
        NSString *reason = nil;
        test.coreTypeNS = c[0];
        test.coreType = (KYTestType)[c[1] unsignedIntegerValue];
        test.refText = @"x";
        @try {
            if (i < cases.count) {
                [engine startEngineWithTestConfig:test result:result finishBlock:finish];
            } else {
                [engine startEngineWithTestConfig:test
                                     onStartBlock:nil
                                 onStartFailBlock:nil
                                     onPauseBlock:nil
                                      onTickBlock:nil
                                 onRecordingBlock:nil
                                 onRecordEndBlock:nil
                                     onScoreBlock:result
                                      finishBlock:finish];
            }
        } @catch (NSException *e) {
            name = e.name;
            reason = e.reason;
        }
        EXPECT([name isEqualToString:NSInvalidArgumentException], ([NSString stringWithFormat:@"%@ thrown: %@", c[2], name]));
        EXPECT([reason isEqualToString:[kYGSTUnsupported stringByAppendingString:c[2]]], reason ?: @"no reason");
    }
    {
        NSString *reason = nil;
        @try {
            [engine startEngineWithTestConfig:nil result:result finishBlock:finish];
        } @catch (NSException *e) {
            reason = e.reason;
        }
        EXPECT([reason isEqualToString:[kYGSTUnsupported stringByAppendingString:@"未指定"]], reason ?: @"nil config not thrown");
    }
    {
        // checked before anything else: an engine that was never initialized throws as well
        KYTestConfig *test = [[KYTestConfig alloc] init];
        BOOL thrown = NO;
        test.coreTypeNS = @"align.eval";
        test.refText = @"x";
        @try {
            [idle startEngineWithTestConfig:test result:result finishBlock:finish];
        } @catch (NSException *e) {
            thrown = [e.name isEqualToString:NSInvalidArgumentException];
        }
        EXPECT(thrown, @"thrown before the initialized check");
    }
    YGSTSpin(^BOOL {
        return NO;
    },
             0.5);
    EXPECT(callbacks == 0, @"a thrown start calls nothing back");
    {
        // every other error keeps its callback
        KYTestConfig *test = [[KYTestConfig alloc] init];
        NSString *token = nil;
        NSDictionary *finished = nil;
        NSDictionary *got;
        test.coreTypeNS = @"sent.eval";
        test.refText = @"";
        got = YGSTStartOnce(engine, test, NO, timeout, &token, &finished);
        EXPECT([got[@"errId"] isEqual:@60006], @"refText empty: 60006 through the callback");
        test.refText = @"x";
        got = YGSTStartOnce(idle, test, NO, timeout, &token, &finished);
        EXPECT([got[@"errId"] isEqual:@60007], @"not initialized: 60007 through the callback");
    }
    {
        // skegn_start cannot throw: -1, last error 60003, no callback, nothing started
        char token[64] = "untouched";
        int rc = skegn_start(skegnEngine, "{\"request\":{\"coreType\":\"open.eval\",\"refText\":\"x\"}}", token,
                             YGSTSupportCallback, box);
        int lastError = skegn_get_last_error();
        EXPECT(rc == -1 && lastError == 60003, @"skegn_start -1 with last error 60003");
        EXPECT(token[0] == 0, @"no tokenId for a start that failed");
        rc = skegn_start(skegnEngine, "{\"request\":{\"refText\":\"x\"}}", token, YGSTSupportCallback, box);
        lastError = skegn_get_last_error();
        EXPECT(rc == -1 && lastError == 60003, @"a missing coreType as well");
        EXPECT(dispatch_semaphore_wait(sem, dispatch_time(DISPATCH_TIME_NOW, (int64_t)(0.5 * NSEC_PER_SEC))) != 0,
               @"no callback");
        // a supported coreType starts as usual
        rc = skegn_start(skegnEngine, "{\"request\":{\"coreType\":\"sent.eval.cn\",\"refText\":\"x\"}}", token,
                         YGSTSupportCallback, box);
        EXPECT(rc == 0 && strlen(token) == 32, @"a supported coreType starts");
        EXPECT(skegn_cancel(skegnEngine) == 0, @"skegn_cancel");
    }
    return nil;
}

NSString *YGSTCheckThrowOnUnsupportedCoreType(NSTimeInterval timeout) {
    KYTestEngine *engine = YGSTMockEngine(@"http://127.0.0.1:9", timeout);
    YGSTCallbackBox *box = calloc(1, sizeof(YGSTCallbackBox));
    dispatch_semaphore_t sem = dispatch_semaphore_create(0);
    struct skegn *skegnEngine = skegn_new(kYGSTPort9);
    YGSTLineCapture *capture = YGSTCaptureLines();
    NSString *failure;
    if (!box) return @"calloc";
    box->done = sem;
    YuguCompat.throwOnUnsupportedCoreType = YES;
    failure = YGSTThrowChecks(engine, skegnEngine, box, sem, timeout);
    YuguCompat.throwOnUnsupportedCoreType = NO;
    YGSTCaptureEnd();
    [engine deleteEngine];
    if (skegnEngine) skegn_delete(skegnEngine);
    if (!failure) free(box);
    if (failure) return failure;
    EXPECT(([capture countWith:@[ @"1 ", @"errId 60003", @"不支持的评测内核：open.eval", @"NSInvalidArgumentException" ]] == 2),
           ([NSString stringWithFormat:@"one WARN line per thrown start: %@", capture.lines]));
    EXPECT(([capture countWith:@[ @"0 ", @"skegn_start", @"errId 60003", @"不支持的评测内核：open.eval" ]] == 1),
           ([NSString stringWithFormat:@"one ERROR line of skegn_start: %@", capture.lines]));
    return nil;
}

// ------------------------------------------------------------------ TtsClient and ReportClient (2.2.0)

/// The completion of one client call, waited for on the main run loop.
@interface YGSTClientCall : NSObject
@property (nonatomic, strong) id result;
@property (nonatomic, strong) NSError *error;
@property (nonatomic) BOOL done;
@property (nonatomic) BOOL onMain;
@property (nonatomic) NSTimeInterval seconds;
@end

@implementation YGSTClientCall
@end

static YGSTClientCall *YGSTTts(YuguTtsClient *client, YuguTtsRequest *request, YuguTtsOptions *options,
                               NSTimeInterval timeout) {
    YGSTClientCall *call = [[YGSTClientCall alloc] init];
    NSDate *start = [NSDate date];
    [client tts:request
           options:options
        completion:^(YuguTtsResult *result, NSError *error) {
            call.result = result;
            call.error = error;
            call.onMain = [NSThread isMainThread];
            call.done = YES;
        }];
    YGSTSpin(^BOOL {
        return call.done;
    },
             timeout);
    call.seconds = [[NSDate date] timeIntervalSinceDate:start];
    return call;
}

static YGSTClientCall *YGSTReport(YuguReportClient *client, NSString *recordId, NSInteger waitForFinalMs,
                                  NSTimeInterval timeout) {
    YGSTClientCall *call = [[YGSTClientCall alloc] init];
    YuguReportOptions *options = nil;
    NSDate *start = [NSDate date];
    if (waitForFinalMs > 0) {
        options = [[YuguReportOptions alloc] init];
        options.waitForFinalMs = waitForFinalMs;
    }
    [client getReport:recordId
              options:options
           completion:^(YuguReportResult *result, NSError *error) {
               call.result = result;
               call.error = error;
               call.onMain = [NSThread isMainThread];
               call.done = YES;
           }];
    YGSTSpin(^BOOL {
        return call.done;
    },
             timeout);
    call.seconds = [[NSDate date] timeIntervalSinceDate:start];
    return call;
}

/// Exactly one of result and error, on the main queue.
static NSString *YGSTCheckCall(YGSTClientCall *call, BOOL wantResult) {
    EXPECT(call.done, @"no completion");
    EXPECT(call.onMain, @"completion not on the main queue");
    EXPECT((call.result != nil) != (call.error != nil), @"exactly one of result and error");
    EXPECT(wantResult ? call.result != nil : call.error != nil,
           wantResult ? (call.error.localizedDescription ?: @"no result") : @"no error");
    if (call.error) EXPECT([call.error.domain isEqualToString:YuguCompatErrorDomain], call.error.domain);
    return nil;
}

/// The mock log entries of this method and path.
static NSArray<NSDictionary *> *YGSTMockEntries(NSString *base, NSString *method, NSString *path, NSTimeInterval timeout) {
    NSMutableArray<NSDictionary *> *out = [NSMutableArray array];
    for (NSDictionary *e in YGSTMockCall(base, @"GET", @"/__mock/log", nil, timeout)) {
        if ([e[@"method"] isEqualToString:method] && [e[@"path"] isEqualToString:path]) [out addObject:e];
    }
    return out;
}

static BOOL YGSTIsHex32(NSString *s) {
    NSUInteger i;
    if (s.length != 32) return NO;
    for (i = 0; i < 32; i++) {
        char c = s.UTF8String[i];
        if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return NO;
    }
    return YES;
}

static NSString *YGSTTtsChecks(KYTestEngine *engine, NSString *baseURL, NSTimeInterval timeout) {
    YuguTtsClient *client = [YuguTtsClient clientFromEngine];
    YuguTtsRequest *request = [[YuguTtsRequest alloc] initWithText:@"你好，世界"];
    YuguTtsOptions *options = [[YuguTtsOptions alloc] init];
    YGSTClientCall *call;
    YuguTtsResult *r;
    NSArray<NSDictionary *> *posts;
    NSString *why;
    EXPECT(engine != nil, @"initEngine");
    request.language = @"zh-CN";
    request.voice = @"xiaoyan";
    request.format = @"wav";
    request.speed = @50;
    request.pitch = @0;
    request.sampleRate = @16000;
    request.emotion = @"warm";
    request.genre = @"prose";
    YGSTMockCall(baseURL, @"POST", @"/__mock/reset", nil, timeout);
    call = YGSTTts(client, request, nil, timeout);
    if ((why = YGSTCheckCall(call, YES))) return why;
    r = call.result;
    EXPECT([r.audioUrl hasPrefix:@"/audio/mock-"], r.audioUrl ?: @"no audioUrl");
    EXPECT(([r.absoluteAudioUrl isEqualToString:[NSString stringWithFormat:@"%@/tts%@", baseURL, r.audioUrl]]),
           r.absoluteAudioUrl ?: @"no absoluteAudioUrl");
    EXPECT(r.duration > 1.3479 && r.duration < 1.3481, @"duration 1.348");
    EXPECT([r.format isEqualToString:@"wav"], r.format ?: @"no format");
    EXPECT([YGSTParse(r.rawJSON.UTF8String)[@"data"][@"audioUrl"] isEqualToString:r.audioUrl], r.rawJSON);
    posts = YGSTMockEntries(baseURL, @"POST", @"/api/v1/tts/generate", timeout);
    EXPECT(posts.count == 1, @"one request (the mock checked its signature)");
    EXPECT(YGSTIsHex32(posts[0][@"idempotencyKey"]), @"a fresh 32 hex Idempotency-Key");
    EXPECT([posts[0][@"contentType"] isEqualToString:@"application/json; charset=utf-8"], posts[0][@"contentType"]);
    EXPECT([posts[0][@"userAgent"] isEqualToString:@"yugu-ios-stcompat-sdk/2.2.0"], posts[0][@"userAgent"]);
    // a retry keeps the key, the caller's key is sent as given
    YGSTMockCall(baseURL, @"POST", @"/__mock/reset", nil, timeout);
    YGSTMockCall(baseURL, @"POST", @"/__mock/faults", @{@"faults" : @[ @{@"match" : @"/api/v1/tts", @"fault" : @"status:503"} ]},
                 timeout);
    options.idempotencyKey = @"order-42";
    call = YGSTTts(client, request, options, timeout);
    if ((why = YGSTCheckCall(call, YES))) return why;
    posts = YGSTMockEntries(baseURL, @"POST", @"/api/v1/tts/generate", timeout);
    EXPECT(posts.count == 2 && [posts[0][@"idempotencyKey"] isEqualToString:@"order-42"] &&
               [posts[1][@"idempotencyKey"] isEqualToString:@"order-42"],
           @"503 then success with the same key");
    // platform errors as NSError: code and HTTP status
    YGSTMockCall(baseURL, @"POST", @"/__mock/faults", @{@"faults" : @[ @{@"match" : @"/api/v1/tts", @"fault" : @"status:400:code=40001"} ]},
                 timeout);
    call = YGSTTts(client, request, nil, timeout);
    if ((why = YGSTCheckCall(call, NO))) return why;
    EXPECT(call.error.code == 40001 && [call.error.userInfo[YuguCompatHTTPStatusKey] isEqual:@400],
           [call.error description]);
    call = YGSTTts([[YuguTtsClient alloc] initWithAppKey:@"mock-app-key" secretKey:@"wrong" baseURL:baseURL], request, nil,
                   timeout);
    if ((why = YGSTCheckCall(call, NO))) return why;
    EXPECT(call.error.code == 2003 && [call.error.localizedDescription isEqualToString:@"签名验证失败"], [call.error description]);
    // explicit credentials
    call = YGSTTts([[YuguTtsClient alloc] initWithAppKey:@"mock-app-key" secretKey:@"mock-secret-key" baseURL:baseURL],
                   [[YuguTtsRequest alloc] initWithText:@"早上好"], nil, timeout);
    if ((why = YGSTCheckCall(call, YES))) return why;
    // local checks: no request
    YGSTMockCall(baseURL, @"POST", @"/__mock/reset", nil, timeout);
    call = YGSTTts(client, [[YuguTtsRequest alloc] initWithText:@"  "], nil, timeout);
    if ((why = YGSTCheckCall(call, NO))) return why;
    EXPECT(call.error.code == 90010, [call.error description]);
    options.idempotencyKey = @"has space";
    call = YGSTTts(client, request, options, timeout);
    if ((why = YGSTCheckCall(call, NO))) return why;
    EXPECT(call.error.code == 90010, [call.error description]);
    EXPECT(YGSTMockEntries(baseURL, @"POST", @"/api/v1/tts/generate", timeout).count == 0, @"no request for local errors");
    return nil;
}

NSString *YGSTCheckTtsClient(NSString *baseURL, NSTimeInterval timeout) {
    KYTestEngine *engine = YGSTMockEngine(baseURL, timeout);
    NSString *failure = YGSTTtsChecks(engine, baseURL, timeout);
    [engine deleteEngine];
    return failure;
}

/// waitForFinalMs as the Android shim decides it: after a pending answer the mock fails every lookup
/// (three 503), so the call ends with that pending answer, error nil, and one WARN line naming the
/// recordId, errId 20009 and the lookups so far.
static NSString *YGSTCheckLaterLookupFails(YuguReportClient *client, NSString *baseURL, NSString *run,
                                           NSTimeInterval timeout) {
    NSString *recordId = [@"eval_pending_later_" stringByAppendingString:run];
    NSString *path = [@"/api/v1/report/" stringByAppendingString:recordId];
    NSDictionary *fault = @{@"match" : path, @"fault" : @"status:503"};
    YuguReportOptions *options = [[YuguReportOptions alloc] init];
    YGSTClientCall *call = [[YGSTClientCall alloc] init];
    YGSTLineCapture *capture = YGSTCaptureLines();
    YuguReportResult *r;
    NSString *why;
    NSString *warn = [NSString stringWithFormat:@"getReport recordId=%@ lookup failed while waiting: code=20009 lookups=2, "
                                                @"returning the latest answer",
                                                recordId];
    options.waitForFinalMs = 6000;
    [client getReport:recordId
              options:options
           completion:^(YuguReportResult *result, NSError *error) {
               call.result = result;
               call.error = error;
               call.onMain = [NSThread isMainThread];
               call.done = YES;
           }];
    // the first lookup answers (pending); the faults then hit the second one and its retries
    YGSTSpin(^BOOL {
        return YGSTMockEntries(baseURL, @"GET", path, timeout).count > 0;
    },
             timeout);
    YGSTMockCall(baseURL, @"POST", @"/__mock/faults", @{@"faults" : @[ fault, fault, fault ]}, timeout);
    YGSTSpin(^BOOL {
        return call.done;
    },
             timeout);
    YGSTSpin(^BOOL {
        return NO;
    },
             0.2); // the log lines come before the completion is posted, the capture ends here
    YGSTCaptureEnd();
    if ((why = YGSTCheckCall(call, YES))) return why;
    r = call.result;
    EXPECT([r.reportStatus isEqualToString:@"pending"] && !r.isFinal && [r.recordId isEqualToString:recordId],
           r.reportStatus ?: @"no reportStatus");
    EXPECT([r.summary isEqualToString:@"即时稿：整体读得流畅。"], r.summary ?: @"no summary");
    EXPECT(([capture countWith:@[ @"1 ", warn ]] == 1), ([NSString stringWithFormat:@"one WARN line: %@", capture.lines]));
    EXPECT(([capture countWith:@[ @"2 ", [NSString stringWithFormat:@"getReport recordId=%@ status=pending lookups=2 attempts=4 ",
                                                                   recordId] ]] == 1),
           ([NSString stringWithFormat:@"the INFO line of the call: %@", capture.lines]));
    EXPECT(YGSTMockEntries(baseURL, @"GET", path, timeout).count == 4, @"one answer and three failed attempts");
    return nil;
}

static NSString *YGSTReportChecks(KYTestEngine *engine, NSString *baseURL, NSTimeInterval timeout) {
    YuguReportClient *client = [YuguReportClient clientFromEngine];
    // the mock counts the lookups of a recordId for its whole life: one fresh id per run
    NSString *run = [[NSUUID UUID].UUIDString substringToIndex:8];
    NSString *finalId = [@"eval_objc_" stringByAppendingString:run];
    NSString *pendingId = [@"eval_pending_objc_" stringByAppendingString:run];
    NSString *waitId = [@"eval_pending_wait_" stringByAppendingString:run];
    YGSTClientCall *call;
    YuguReportResult *r;
    NSArray<NSDictionary *> *gets;
    NSString *why;
    EXPECT(engine != nil, @"initEngine");
    YGSTMockCall(baseURL, @"POST", @"/__mock/reset", nil, timeout);
    call = YGSTReport(client, finalId, 0, timeout);
    if ((why = YGSTCheckCall(call, YES))) return why;
    r = call.result;
    EXPECT([r.recordId isEqualToString:finalId] && [r.reportStatus isEqualToString:@"final"] && r.isFinal,
           ([NSString stringWithFormat:@"%@ %@", r.recordId, r.reportStatus]));
    EXPECT([r.summary isEqualToString:@"mock report"], r.summary ?: @"no summary");
    EXPECT(r.suggestions.count == 1 && [r.suggestions[0] isEqualToString:@"“清”的后鼻音收得再完整一些。"], [r.suggestions description]);
    EXPECT(r.overall != nil && r.overall.doubleValue == 93.7, @"overall from score.overall");
    EXPECT([r.report[@"source"] isEqualToString:@"llm"] && [r.score[@"integrity"] isEqual:@100] &&
               [r.timing[@"channel"] isEqualToString:@"rest"],
           @"report, score and timing objects");
    EXPECT([YGSTParse(r.dataJSON.UTF8String)[@"recordId"] isEqualToString:finalId], r.dataJSON);
    gets = YGSTMockEntries(baseURL, @"GET", [@"/api/v1/report/" stringByAppendingString:finalId], timeout);
    EXPECT(gets.count == 1 && gets[0][@"idempotencyKey"] == [NSNull null], @"one GET without Idempotency-Key");
    // pending: one lookup without waitForFinalMs, a normal result
    call = YGSTReport(client, pendingId, 0, timeout);
    if ((why = YGSTCheckCall(call, YES))) return why;
    r = call.result;
    EXPECT([r.reportStatus isEqualToString:@"pending"] && !r.isFinal && [r.summary isEqualToString:@"即时稿：整体读得流畅。"],
           r.reportStatus ?: @"no reportStatus");
    // waitForFinalMs: again every second until final
    call = YGSTReport(client, waitId, 6000, timeout);
    if ((why = YGSTCheckCall(call, YES))) return why;
    r = call.result;
    EXPECT(r.isFinal && call.seconds >= 1.9, ([NSString stringWithFormat:@"%@ after %.2f s", r.reportStatus, call.seconds]));
    gets = YGSTMockEntries(baseURL, @"GET", [@"/api/v1/report/" stringByAppendingString:waitId], timeout);
    EXPECT(gets.count == 3, @"three lookups");
    if ((why = YGSTCheckLaterLookupFails(client, baseURL, run, timeout))) return why;
    // errors
    call = YGSTReport(client, @"nope", 0, timeout);
    if ((why = YGSTCheckCall(call, NO))) return why;
    EXPECT(call.error.code == 40001 && [call.error.userInfo[YuguCompatHTTPStatusKey] isEqual:@400] &&
               [call.error.localizedDescription isEqualToString:@"评测记录不存在: nope"],
           [call.error description]);
    call = YGSTReport(client, @" ", 0, timeout);
    if ((why = YGSTCheckCall(call, NO))) return why;
    EXPECT(call.error.code == 90010, [call.error description]);
    call = YGSTReport([[YuguReportClient alloc] initWithAppKey:@"mock-app-key" secretKey:@"mock-secret-key" baseURL:baseURL],
                      finalId, 0, timeout);
    if ((why = YGSTCheckCall(call, YES))) return why;
    call = YGSTReport([[YuguReportClient alloc] initWithAppKey:@"mock-app-key" secretKey:@"wrong" baseURL:baseURL], finalId,
                      0, timeout);
    if ((why = YGSTCheckCall(call, NO))) return why;
    EXPECT(call.error.code == 2003, [call.error description]);
    return nil;
}

NSString *YGSTCheckReportClient(NSString *baseURL, NSTimeInterval timeout) {
    KYTestEngine *engine = YGSTMockEngine(baseURL, timeout);
    NSString *failure = YGSTReportChecks(engine, baseURL, timeout);
    [engine deleteEngine];
    return failure;
}

static NSString *YGSTWithoutEngineChecks(struct skegn **skegnEngine, NSTimeInterval timeout) {
    KYTestEngine *engine = YGSTMockEngine(@"http://127.0.0.1:9", timeout);
    YGSTClientCall *call;
    NSString *why;
    struct skegn *e;
    EXPECT(engine != nil, @"initEngine");
    [engine deleteEngine]; // no initialized engine any more
    call = YGSTReport([YuguReportClient clientFromEngine], @"eval_x", 0, timeout);
    if ((why = YGSTCheckCall(call, NO))) return why;
    EXPECT(call.error.code == 60007 && [call.error.localizedDescription isEqualToString:@"引擎未初始化"], [call.error description]);
    call = YGSTTts([[YuguTtsClient alloc] init], [[YuguTtsRequest alloc] initWithText:@"x"], nil, timeout);
    if ((why = YGSTCheckCall(call, NO))) return why;
    EXPECT(call.error.code == 60007, [call.error description]);
    call = YGSTReport([[YuguReportClient alloc] initWithAppKey:@" " secretKey:@"s"], @"eval_x", 0, timeout);
    if ((why = YGSTCheckCall(call, NO))) return why;
    EXPECT(call.error.code == 90010, [call.error description]);
    call = YGSTTts([[YuguTtsClient alloc] initWithAppKey:@"ak" secretKey:@"sk" baseURL:@"ftp://example.com"],
                   [[YuguTtsRequest alloc] initWithText:@"x"], nil, timeout);
    if ((why = YGSTCheckCall(call, NO))) return why;
    EXPECT(call.error.code == 90010, [call.error description]);
    // skegn_new gives its credentials, skegn_delete takes them back: port 9 refuses, so 20009 after the retries
    e = skegn_new(kYGSTPort9);
    *skegnEngine = e;
    EXPECT(e != NULL, @"skegn_new");
    call = YGSTReport([YuguReportClient clientFromEngine], @"eval_x", 0, timeout);
    if ((why = YGSTCheckCall(call, NO))) return why;
    EXPECT(call.error.code == 20009 && call.error.userInfo[YuguCompatHTTPStatusKey] == nil, [call.error description]);
    *skegnEngine = NULL;
    skegn_delete(e);
    call = YGSTReport([YuguReportClient clientFromEngine], @"eval_x", 0, timeout);
    if ((why = YGSTCheckCall(call, NO))) return why;
    EXPECT(call.error.code == 60007, [call.error description]);
    return nil;
}

NSString *YGSTCheckClientsWithoutEngine(NSTimeInterval timeout) {
    struct skegn *e = NULL;
    NSString *failure = YGSTWithoutEngineChecks(&e, timeout);
    if (e) skegn_delete(e);
    return failure;
}

// The examples of README.md 不支持的内核, 语音合成 TtsClient and 报告查询 ReportClient, word for word.
// Never called: compiled with the tests so that the examples keep compiling against the package.
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wunused-function"

static void ReadmeThrowExample(KYTestConfig *test, KYTestResultBlock resultBlock, void (^finishBlock)(BOOL, NSString *)) {
    YuguCompat.throwOnUnsupportedCoreType = YES;
    @try {
        [[KYTestEngine sharedInstance] startEngineWithTestConfig:test result:resultBlock finishBlock:finishBlock];
    } @catch (NSException *e) {
        NSLog(@"%@ %@", e.name, e.reason); // NSInvalidArgumentException 不支持的评测内核：open.eval
    }
}

static void ReadmeTtsExample(void) {
    YuguTtsRequest *request = [[YuguTtsRequest alloc] initWithText:@"床前明月光"];
    request.language = @"zh-CN";
    request.voice = @"xiaoyan";
    [[YuguTtsClient clientFromEngine] tts:request options:nil completion:^(YuguTtsResult *result, NSError *error) {
        if (result) {
            NSLog(@"%@ %.2f", result.absoluteAudioUrl, result.duration);
        } else {
            NSLog(@"%ld %@", (long)error.code, error.localizedDescription);
        }
    }];
}

static void ReadmeReportExample(NSString *recordId) {
    YuguReportOptions *options = [[YuguReportOptions alloc] init];
    options.waitForFinalMs = 15000;
    [[YuguReportClient clientFromEngine] getReport:recordId options:options completion:^(YuguReportResult *result, NSError *error) {
        if (!result) {
            NSLog(@"%ld %@", (long)error.code, error.localizedDescription);
            return;
        }
        NSLog(@"%@ %d %@ %@", result.reportStatus, result.isFinal, result.summary, result.suggestions);
    }];
}

#pragma clang diagnostic pop
